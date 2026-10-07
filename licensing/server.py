#!/usr/bin/env python3
"""AIV license-only backend. Run behind HTTPS; no device journal endpoints.
SQLite reservations are serialized with BEGIN IMMEDIATE. Durable filesystem required.
"""
import base64, hashlib, json, os, secrets, sqlite3, time, uuid
from decimal import Decimal
from contextlib import contextmanager
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding

EVENTS = ('PAYMENT.CAPTURE.COMPLETED','PAYMENT.CAPTURE.DENIED','PAYMENT.CAPTURE.PENDING',
          'PAYMENT.CAPTURE.REFUNDED','PAYMENT.CAPTURE.REVERSED',
          'CUSTOMER.DISPUTE.CREATED','CUSTOMER.DISPUTE.RESOLVED','CHECKOUT.ORDER.APPROVED')
class Refused(Exception):
    pass
class Store:
    def __init__(self,path):
        self.path=path
        with self.db() as db:
            db.executescript('''
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS slots(number INTEGER PRIMARY KEY CHECK(number BETWEEN 1 AND 1000),
              reservation TEXT UNIQUE NOT NULL,key_id TEXT NOT NULL,request_id TEXT NOT NULL,
              created_at INTEGER NOT NULL,order_id TEXT UNIQUE,capture_id TEXT UNIQUE,
              state TEXT NOT NULL DEFAULT 'RESERVED',payment_status TEXT,confirmed_at INTEGER NOT NULL DEFAULT 0,
              UNIQUE(key_id,request_id));
            CREATE TABLE IF NOT EXISTS challenges(nonce TEXT PRIMARY KEY,public_key TEXT NOT NULL,expires INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS webhooks(id TEXT PRIMARY KEY,type TEXT NOT NULL,created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS payment_blocks(capture_id TEXT PRIMARY KEY,reason TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS deployment_scope(value TEXT PRIMARY KEY);
            ''')
    @contextmanager
    def db(self,atomic=False):
        db=sqlite3.connect(self.path,timeout=30,isolation_level=None);db.row_factory=sqlite3.Row
        try:
            if atomic: db.execute('BEGIN IMMEDIATE')
            yield db
            if atomic: db.commit()
        except Exception:
            if atomic:db.rollback()
            raise
        finally:db.close()
    def reserve(self,key_id,request_id):
        with self.db(True) as db:
            old=db.execute('SELECT * FROM slots WHERE key_id=? AND request_id=?',(key_id,request_id)).fetchone()
            if old:return dict(old)
            available=db.execute('''WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<1000)
              SELECT x FROM n WHERE x NOT IN (SELECT number FROM slots) ORDER BY x LIMIT 1''').fetchone()
            if not available:
                confirmed=db.execute('SELECT count(*) FROM slots WHERE confirmed_at>0').fetchone()[0]
                raise Refused('Offre fondateur terminée' if confirmed>=1000 else 'Toutes les places sont réservées; réessayer plus tard.')
            number=available[0]
            db.execute('INSERT INTO slots(number,reservation,key_id,request_id,created_at) VALUES(?,?,?,?,?)',
                       (number,str(uuid.uuid4()),key_id,request_id,int(time.time())))
            return dict(db.execute('SELECT * FROM slots WHERE number=?',(number,)).fetchone())
    def order(self,order_id):
        with self.db() as db:
            row=db.execute('SELECT * FROM slots WHERE order_id=?',(order_id,)).fetchone()
            if not row:raise Refused('Commande inconnue')
            return dict(row)
    def confirmed(self,order_id,capture_id):
        with self.db(True) as db:
            row=db.execute('SELECT * FROM slots WHERE order_id=?',(order_id,)).fetchone()
            if not row:raise Refused('Commande inconnue')
            if row['capture_id'] and row['capture_id']!=capture_id:raise Refused('Capture différente')
            if row['state'] in ('REVOKED','DENIED'):raise Refused('Paiement révoqué ou refusé')
            if db.execute('SELECT 1 FROM payment_blocks WHERE capture_id=?',(capture_id,)).fetchone():raise Refused('Capture bloquée')
            db.execute("UPDATE slots SET capture_id=?,state='ACTIVE',payment_status='COMPLETED',confirmed_at=CASE WHEN confirmed_at=0 THEN ? ELSE confirmed_at END WHERE order_id=?",(capture_id,int(time.time()),order_id))
            return row['number']
    def active(self,key_id):
        with self.db() as db:
            row=db.execute("SELECT * FROM slots WHERE key_id=? AND state='ACTIVE' ORDER BY number LIMIT 1",(key_id,)).fetchone()
            return dict(row) if row else None
    def challenge(self,spki):
        try:
            key=serialization.load_der_public_key(base64.b64decode(spki,validate=True))
            if not isinstance(key,ec.EllipticCurvePublicKey) or not isinstance(key.curve,ec.SECP256R1):raise ValueError()
        except Exception:raise Refused('Identité EC P256 requise')
        nonce=secrets.token_urlsafe(32)
        with self.db(True) as db:
            db.execute('DELETE FROM challenges WHERE expires<?',(int(time.time()),))
            db.execute('INSERT INTO challenges VALUES(?,?,?)',(nonce,spki,int(time.time())+120))
        return nonce
    def authenticate(self,path,body,nonce,signature):
        with self.db(True) as db:
            row=db.execute('SELECT * FROM challenges WHERE nonce=?',(nonce,)).fetchone()
            if not row or row['expires']<time.time():raise Refused('Challenge expiré ou déjà utilisé')
            raw=base64.b64decode(row['public_key'],validate=True);key=serialization.load_der_public_key(raw)
            try:key.verify(base64.b64decode(signature,validate=True),(nonce+'\n'+path+'\n').encode()+body,ec.ECDSA(hashes.SHA256()))
            except Exception:raise Refused('Signature identité invalide')
            db.execute('DELETE FROM challenges WHERE nonce=?',(nonce,))
            return hashlib.sha256(raw).hexdigest()

class PayPal:
    def __init__(self):
        mode=os.environ.get('PAYPAL_MODE','sandbox')
        if mode not in ('sandbox','live'):raise ValueError('PAYPAL_MODE sandbox or live')
        self.base='https://api-m.sandbox.paypal.com' if mode=='sandbox' else 'https://api-m.paypal.com'
        self.mode=mode
    def call(self,path,body=None,request_id=None):
        credentials=(os.environ['PAYPAL_CLIENT_ID']+':'+os.environ['PAYPAL_CLIENT_SECRET']).encode()
        token_request=Request(self.base+'/v1/oauth2/token',data=b'grant_type=client_credentials',headers={'Authorization':'Basic '+base64.b64encode(credentials).decode(),'Content-Type':'application/x-www-form-urlencoded'})
        with urlopen(token_request,timeout=20) as response:token=json.load(response)['access_token']
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}
        if request_id:headers['PayPal-Request-Id']=request_id
        req=Request(self.base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
        try:
            with urlopen(req,timeout=30) as response:return json.load(response)
        except HTTPError as error:
            # Never include private upstream headers/token or echoed customer information in output.
            raise Refused('PayPal refuse cette opération (HTTP %d)'%error.code)
    def create(self,reservation):
        url=os.environ['PUBLIC_BASE_URL'].rstrip('/')
        return self.call('/v2/checkout/orders',{
          'intent':'CAPTURE','purchase_units':[{'custom_id':reservation['reservation'],'invoice_id':reservation['reservation'],
          'amount':{'currency_code':'CAD','value':'50.00'},'payee':{'merchant_id':os.environ['PAYPAL_MERCHANT_ID']}}],
          'payment_source':{'paypal':{'experience_context':{'return_url':url+'/return','cancel_url':url+'/cancel','user_action':'PAY_NOW'}}}},reservation['reservation'])
    def capture(self,order_id,reservation):return self.call('/v2/checkout/orders/'+order_id+'/capture',{},'capture-'+reservation)
    def order(self,order_id):return self.call('/v2/checkout/orders/'+order_id)
    def capture_state(self,capture_id):return self.call('/v2/payments/captures/'+capture_id)
    def verify_webhook(self,headers,event):
        payload={name:headers.get(header,'') for name,header in [('auth_algo','PAYPAL-AUTH-ALGO'),('cert_url','PAYPAL-CERT-URL'),('transmission_id','PAYPAL-TRANSMISSION-ID'),('transmission_sig','PAYPAL-TRANSMISSION-SIG'),('transmission_time','PAYPAL-TRANSMISSION-TIME')]}
        payload.update(webhook_id=os.environ['PAYPAL_WEBHOOK_ID'],webhook_event=event)
        return self.call('/v1/notifications/verify-webhook-signature',payload).get('verification_status')=='SUCCESS'

class Service:
    def __init__(self,store,paypal,private_key):
        self.store,self.paypal,self.private_key=store,paypal,private_key
        scope=getattr(paypal,'mode','sandbox')+':'+os.environ['PAYPAL_MERCHANT_ID']
        with store.db(True) as db:
            existing=db.execute('SELECT value FROM deployment_scope').fetchone()
            if existing and existing[0]!=scope:raise Refused('Ne pas mélanger licences sandbox/live ou marchands dans la même base')
            if not existing:db.execute('INSERT INTO deployment_scope VALUES(?)',(scope,))
    def reconcile_reservations(self):
        # No timeout-only release: an approved or ambiguous order might still capture.
        with self.store.db() as db:pending=[dict(r) for r in db.execute("SELECT * FROM slots WHERE state='RESERVED' AND confirmed_at=0 AND order_id IS NOT NULL")]
        released=0
        for slot in pending:
            order=self.paypal.order(slot['order_id'])
            if order.get('status')=='COMPLETED':self.accept_order(order)
            elif order.get('status')=='VOIDED' and not any(u.get('payments',{}).get('captures') for u in order.get('purchase_units',[])):
                with self.store.db(True) as db:
                    deleted=db.execute("DELETE FROM slots WHERE reservation=? AND state='RESERVED' AND confirmed_at=0",(slot['reservation'],))
                    released+=deleted.rowcount
        return released
    def create_order(self,key,request_id):
        if not isinstance(request_id,str):raise Refused('Identifiant de requête invalide')
        try:uuid.UUID(request_id)
        except ValueError:raise Refused('Identifiant de requête invalide')
        slot=self.store.reserve(key,request_id)
        if slot['state'] in ('REVOKED','DENIED'):raise Refused('Commande terminée : créer une nouvelle demande')
        if slot['order_id']:order=self.paypal.order(slot['order_id'])
        else:
            # Fail closed outside PayPal's idempotency retention if an earlier create was ambiguous.
            if time.time()-slot['created_at']>5*3600:raise Refused('Réservation ancienne : réconciliation serveur nécessaire')
            order=self.paypal.create(slot)
            order_id=order.get('id','')
            if not isinstance(order_id,str) or not order_id.isalnum():raise Refused('Réponse PayPal invalide')
            with self.store.db(True) as db:
                known=db.execute('SELECT order_id FROM slots WHERE number=?',(slot['number'],)).fetchone()[0]
                if known and known!=order_id:raise Refused('Création PayPal non idempotente')
                db.execute('UPDATE slots SET order_id=? WHERE number=?',(order_id,slot['number']))
        approve=next((x['href'] for x in order.get('links',[]) if x['rel'] in ('approve','payer-action')),None)
        if not approve:raise Refused('Commande déjà payée ou sans lien de confirmation')
        from urllib.parse import urlparse
        parsed=urlparse(approve)
        if parsed.scheme!='https' or parsed.hostname not in ('www.paypal.com','www.sandbox.paypal.com'):raise Refused('Lien PayPal inattendu')
        return {'order_id':order['id'],'approve_url':approve}
    def accept_order(self,order,event_capture=None):
        slot=self.store.order(order['id']);units=order.get('purchase_units',[])
        if order.get('status')!='COMPLETED' or order.get('intent')!='CAPTURE' or len(units)!=1:raise Refused('Paiement non complété')
        unit=units[0];captures=unit.get('payments',{}).get('captures',[])
        if unit.get('custom_id')!=slot['reservation'] or unit.get('invoice_id')!=slot['reservation'] or unit.get('payee',{}).get('merchant_id')!=os.environ['PAYPAL_MERCHANT_ID'] or len(captures)!=1:raise Refused('Commande non conforme')
        capture=captures[0];amount=capture.get('amount',{})
        if not isinstance(capture.get('id'),str) or not capture['id'].isalnum():raise Refused('Capture invalide')
        if self.paypal.capture_state(capture['id']).get('status')!='COMPLETED':raise Refused('Capture non active')
        if capture.get('status')!='COMPLETED' or capture.get('final_capture') is not True or amount.get('currency_code')!='CAD' or Decimal(amount.get('value','0'))!=Decimal('50.00') or (event_capture and capture.get('id')!=event_capture):raise Refused('Capture non conforme')
        if capture.get('payee',unit['payee']).get('merchant_id')!=os.environ['PAYPAL_MERCHANT_ID']:raise Refused('Marchand différent')
        self.store.confirmed(order['id'],capture['id'])
    def capture(self,key,order_id):
        if not isinstance(order_id,str) or not order_id.isalnum():raise Refused('Commande invalide')
        slot=self.store.order(order_id)
        if slot['key_id']!=key:raise Refused('Identité de commande différente')
        current=self.paypal.order(order_id)
        if current.get('status')!='COMPLETED':
            if slot['state'] in ('REVOKED','DENIED'):raise Refused('Paiement terminé sans licence active')
            try:self.paypal.capture(order_id,slot['reservation'])
            except Refused:
                # Resolve capture-already-completed after a response lost in transit.
                current=self.paypal.order(order_id)
                if current.get('status')!='COMPLETED':raise
        self.accept_order(self.paypal.order(order_id));return {'confirmed':True}
    def entitlement(self,key):
        slot=self.store.active(key)
        if not slot:return {'active':False}
        now=int(time.time());claims={'schema_version':1,'aud':'aiv-founder','key_id':key,'licence_id':slot['reservation'],
          'founder':True,'founder_number':slot['number'],'status':'ACTIVE','issued_at':now,'expires_at':now+7*86400}
        payload=json.dumps(claims,sort_keys=True,separators=(',',':')).encode()
        signature=self.private_key.sign(payload,padding.PKCS1v15(),hashes.SHA256())
        return {'active':True,'entitlement':{'payload':base64.b64encode(payload).decode(),'signature':base64.b64encode(signature).decode()}}
    def owner_licenses(self,key):
        allowed=os.environ.get('OWNER_KEY_ID','')
        if len(allowed)!=64 or not secrets.compare_digest(key,allowed):raise Refused('Identité propriétaire non autorisée')
        with self.store.db() as db:
            # Single read snapshot; no phone logs, PayPal payer names or email addresses.
            rows=[dict(r) for r in db.execute('SELECT number,reservation AS licence_id,key_id,state,payment_status,created_at,confirmed_at FROM slots ORDER BY number')]
        return {'schema_version':1,'owner_verified':True,'generated_at':int(time.time()),
          'mode':getattr(self.paypal,'mode','sandbox'),'sold_confirmed':sum(r['confirmed_at']>0 for r in rows),
          'active':sum(r['state']=='ACTIVE' for r in rows),'revoked':sum(r['state']=='REVOKED' for r in rows),
          'reserved':sum(r['state']=='RESERVED' for r in rows),'licenses':rows}
    def webhook(self,headers,event):
        if not self.paypal.verify_webhook(headers,event):raise Refused('Signature webhook invalide')
        kind=event.get('event_type');event_id=event.get('id')
        if not isinstance(event_id,str) or len(event_id)>128:raise Refused('Webhook invalide')
        with self.store.db() as db:
            if db.execute('SELECT 1 FROM webhooks WHERE id=?',(event_id,)).fetchone():return {'duplicate':True}
        resource=event.get('resource',{});order_id=resource.get('supplementary_data',{}).get('related_ids',{}).get('order_id')
        capture_id=resource.get('supplementary_data',{}).get('related_ids',{}).get('capture_id')
        if kind in ('PAYMENT.CAPTURE.COMPLETED','PAYMENT.CAPTURE.DENIED','PAYMENT.CAPTURE.REVERSED','PAYMENT.CAPTURE.PENDING'):capture_id=resource.get('id')
        # Dispute references use seller_transaction_id, which is the merchant's capture ID.
        if kind=='CUSTOMER.DISPUTE.CREATED':capture_id=next((x.get('seller_transaction_id') for x in resource.get('disputed_transactions',[]) if x.get('seller_transaction_id')),None)
        if kind=='PAYMENT.CAPTURE.COMPLETED':
            if not order_id:raise Refused('Webhook sans commande associée')
            self.accept_order(self.paypal.order(order_id),capture_id)
        elif kind in ('PAYMENT.CAPTURE.REFUNDED','PAYMENT.CAPTURE.REVERSED','CUSTOMER.DISPUTE.CREATED','PAYMENT.CAPTURE.DENIED'):
            with self.store.db(True) as db:
                if capture_id:db.execute('INSERT OR IGNORE INTO payment_blocks VALUES(?,?)',(capture_id,kind))
                row=db.execute('SELECT * FROM slots WHERE capture_id=? OR order_id=?',(capture_id,order_id)).fetchone()
                if row:db.execute("UPDATE slots SET state=?,payment_status=? WHERE number=?",('DENIED' if kind.endswith('DENIED') else 'REVOKED',kind,row['number']))
        # Disputes never reactivate automatically. Resolution requires verified merchant reconciliation.
        with self.store.db(True) as db:db.execute('INSERT OR IGNORE INTO webhooks VALUES(?,?,?)',(event_id,kind,int(time.time())))
        return {'ok':True}

class Handler(BaseHTTPRequestHandler):
    service=None
    def log_message(self,*args):pass # Never log request bodies, recovery identity or PayPal customer fields.
    def reply(self,status,payload):
        data=json.dumps(payload).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def do_GET(self):
        if self.path in ('/return','/cancel'):self.reply(200,{'message':'Retourner dans AIV et vérifier la licence. Aucun déverrouillage par ce retour.'})
        else:self.reply(404,{'error':'not_found'})
    def do_POST(self):
        try:
            size=int(self.headers.get('Content-Length','0'))
            if size<2 or size>65536:raise Refused('Requête hors limites')
            raw=self.rfile.read(size);body=json.loads(raw)
            if not isinstance(body,dict):raise Refused('Objet JSON requis')
            if self.path=='/webhook':result=self.service.webhook(self.headers,body)
            elif self.path=='/challenge':
                if set(body)!={'public_key'} or not isinstance(body['public_key'],str) or len(body['public_key'])>512:raise Refused('Champs identité invalides')
                result={'nonce':self.service.store.challenge(body['public_key'])}
            else:
                fields={'/orders':{'request_id'},'/capture':{'order_id'},'/entitlement':set(),'/release':set(),'/owner/licenses':set()}.get(self.path)
                if fields is None or set(body)!=fields:raise Refused('Seuls les champs de licence sont acceptés')
                key=self.service.store.authenticate(self.path,raw,self.headers.get('X-AIV-Nonce',''),self.headers.get('X-AIV-Signature',''))
                if self.path=='/orders':result=self.service.create_order(key,body['request_id'])
                elif self.path=='/capture':result=self.service.capture(key,body['order_id'])
                elif self.path=='/entitlement':result=self.service.entitlement(key)
                elif self.path=='/owner/licenses':result=self.service.owner_licenses(key)
                else:
                    if not self.service.store.active(key):raise Refused('Licence requise')
                    result=json.loads(open(os.environ['FOUNDER_RELEASE_MANIFEST']).read())
                    if set(result)!={'url','sha256','version_code'}:raise Refused('Manifeste distribution invalide')
            self.reply(200,result)
        except Refused as e:self.reply(409,{'error':str(e)})
        except Exception:self.reply(503,{'error':'Opération non confirmée; réessayer la vérification. Aucun déverrouillage.'})

def main():
    private_key=serialization.load_pem_private_key(open(os.environ['LICENSE_SIGNING_KEY_FILE'],'rb').read(),password=None)
    Handler.service=Service(Store(os.environ['LICENSE_DB_FILE']),PayPal(),private_key)
    import sys
    if '--reconcile' in sys.argv:
        print(json.dumps({'released_unpaid_voided_reservations':Handler.service.reconcile_reservations()}));return
    ThreadingHTTPServer((os.environ.get('BIND_HOST','127.0.0.1'),int(os.environ.get('PORT','8080'))),Handler).serve_forever()
if __name__=='__main__':main()
