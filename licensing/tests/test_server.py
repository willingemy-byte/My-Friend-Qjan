import base64, concurrent.futures, hashlib, importlib.util, json, os, tempfile, time, unittest, uuid
from pathlib import Path
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec,rsa,padding
spec=importlib.util.spec_from_file_location('server',Path(__file__).parents[1]/'server.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class FakePayPal:
 def __init__(self):self.orders={};self.valid=True;self.declined=False
 def create(self,s):
  order={'id':'O'+str(s['number']),'status':'CREATED','intent':'CAPTURE','purchase_units':[{'custom_id':s['reservation'],'invoice_id':s['reservation'],'payee':{'merchant_id':'MERCHANT'},'payments':{'captures':[]}}],'links':[{'rel':'payer-action','href':'https://www.sandbox.paypal.com/checkoutnow?token=TEST'}]};self.orders[order['id']]=order;return order
 def order(self,id):return self.orders[id]
 def capture(self,id,reservation):
  if self.declined:raise m.Refused('declined')
  order=self.orders[id];order['status']='COMPLETED';order['purchase_units'][0]['payments']['captures']=[{'id':'C'+id,'status':'COMPLETED','final_capture':True,'amount':{'currency_code':'CAD','value':'50.00'}}];return order
 def capture_state(self,id):return {'status':'COMPLETED'}
 def verify_webhook(self,headers,event):return self.valid
class Tests(unittest.TestCase):
 def setUp(self):
  os.environ['PAYPAL_MERCHANT_ID']='MERCHANT';self.tmp=tempfile.TemporaryDirectory();self.store=m.Store(str(Path(self.tmp.name)/'licenses.sqlite'));self.paypal=FakePayPal();self.key=rsa.generate_private_key(public_exponent=65537,key_size=2048);self.service=m.Service(self.store,self.paypal,self.key);os.environ['PAYPAL_MERCHANT_ID']='MERCHANT'
 def tearDown(self):self.tmp.cleanup()
 def buy(self,key='k'):
  order=self.service.create_order(key,str(uuid.uuid4()));self.service.capture(key,order['order_id']);return order['order_id']
 def test_atomic_999_1000_1001(self):
  # Existing 998 confirmed licenses then 24 simultaneous buyers; exactly two get a slot.
  with self.store.db(True) as db:
   db.executemany("INSERT INTO slots(number,reservation,key_id,request_id,created_at,order_id,capture_id,state,payment_status,confirmed_at) VALUES(?,?,?,?,?,?,?,'ACTIVE','COMPLETED',1)",[(n,str(uuid.uuid4()),str(n),str(uuid.uuid4()),0,'O'+str(n),'C'+str(n)) for n in range(1,999)])
  def reserve(n):
   try:return self.store.reserve('new'+str(n),str(uuid.uuid4()))
   except m.Refused:return None
  with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:results=list(pool.map(reserve,range(24)))
  admitted=[x for x in results if x];self.assertEqual(sorted(x['number'] for x in admitted),[999,1000])
  for x in admitted:
   order=self.paypal.create(x)
   with self.store.db(True) as db:db.execute('UPDATE slots SET order_id=? WHERE number=?',(order['id'],x['number']))
   self.service.capture(x['key_id'],order['id'])
  self.assertEqual(self.store.active(admitted[-1]['key_id'])['state'],'ACTIVE')
  with self.store.db() as db:self.assertEqual(db.execute("SELECT count(*) FROM slots WHERE state='ACTIVE'").fetchone()[0],1000)
  with self.assertRaises(m.Refused):self.store.reserve('1001',str(uuid.uuid4()))
 def test_idempotent_order_and_capture(self):
  request=str(uuid.uuid4());one=self.service.create_order('k',request);two=self.service.create_order('k',request);self.assertEqual(one,two)
  self.service.capture('k',one['order_id']);self.service.capture('k',one['order_id'])
  with self.store.db() as db:self.assertEqual(db.execute('SELECT count(*) FROM slots').fetchone()[0],1)
 def test_cancel_return_never_activates(self):
  self.service.create_order('k',str(uuid.uuid4()));self.assertFalse(self.service.entitlement('k')['active'])
 def test_declined_and_owner_mismatch(self):
  order=self.service.create_order('k',str(uuid.uuid4()))['order_id'];self.paypal.declined=True
  with self.assertRaises(m.Refused):self.service.capture('k',order)
  with self.assertRaises(m.Refused):self.service.capture('other',order)
  self.assertFalse(self.service.entitlement('k')['active'])
 def test_amount_currency_merchant_capture_final(self):
  for field in ('amount','currency','merchant','final','pending'):
   with self.subTest(field=field):
    k=field;order=self.service.create_order(k,str(uuid.uuid4()))['order_id'];self.paypal.capture(order,'x');unit=self.paypal.orders[order]['purchase_units'][0];capture=unit['payments']['captures'][0]
    if field=='amount':capture['amount']['value']='0.01'
    if field=='currency':capture['amount']['currency_code']='USD'
    if field=='merchant':unit['payee']['merchant_id']='OTHER'
    if field=='final':capture['final_capture']=False
    if field=='pending':capture['status']='PENDING'
    with self.assertRaises(m.Refused):self.service.accept_order(self.paypal.orders[order])
    self.assertFalse(self.service.entitlement(k)['active'])
 def test_webhook_duplicate_refund_and_signature(self):
  order=self.buy();event={'id':'E1','event_type':'PAYMENT.CAPTURE.COMPLETED','resource':{'id':'C'+order,'supplementary_data':{'related_ids':{'order_id':order}}}}
  self.assertTrue(self.service.webhook({},event)['ok']);self.assertTrue(self.service.webhook({},event)['duplicate'])
  event={'id':'E2','event_type':'PAYMENT.CAPTURE.REFUNDED','resource':{'id':'R1','supplementary_data':{'related_ids':{'capture_id':'C'+order}}}}
  self.paypal.valid=False
  with self.assertRaises(m.Refused):self.service.webhook({},event)
  self.paypal.valid=True;self.service.webhook({},event);self.assertFalse(self.service.entitlement('k')['active'])
  with self.assertRaises(m.Refused):self.service.accept_order(self.paypal.orders[order])
  with self.store.db() as db:self.assertEqual(db.execute('SELECT count(*) FROM slots').fetchone()[0],1)
 def test_out_of_order_reversal(self):
  order=self.service.create_order('k',str(uuid.uuid4()))['order_id'];self.paypal.capture(order,'x')
  self.service.webhook({}, {'id':'R','event_type':'PAYMENT.CAPTURE.REVERSED','resource':{'id':'C'+order}})
  with self.assertRaises(m.Refused):self.service.accept_order(self.paypal.orders[order])
 def test_proof_signature_expiry_replay(self):
  key=ec.generate_private_key(ec.SECP256R1());raw=key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo);nonce=self.store.challenge(base64.b64encode(raw).decode());body=b'{}';data=(nonce+'\n/entitlement\n').encode()+body;sig=base64.b64encode(key.sign(data,ec.ECDSA(hashes.SHA256()))).decode()
  with self.assertRaises(m.Refused):self.store.authenticate('/orders',body,nonce,sig)
  self.assertEqual(self.store.authenticate('/entitlement',body,nonce,sig),hashlib.sha256(raw).hexdigest())
  with self.assertRaises(m.Refused):self.store.authenticate('/entitlement',body,nonce,sig)
 def test_signed_entitlement(self):
  self.buy();envelope=self.service.entitlement('k')['entitlement'];payload=base64.b64decode(envelope['payload']);self.key.public_key().verify(base64.b64decode(envelope['signature']),payload,padding.PKCS1v15(),hashes.SHA256());claims=json.loads(payload);self.assertEqual(claims['founder_number'],1);self.assertEqual(claims['key_id'],'k')
if __name__=='__main__':unittest.main(verbosity=2)
