#!/usr/bin/env python3
"""Ephemeral OAuth installer. Separate from license backend. No phone journal endpoint."""
import os,json,secrets,time,base64,hashlib,html,re,threading
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from http.cookies import SimpleCookie
from urllib.parse import urlencode,urlparse,parse_qs
from urllib.request import Request,urlopen
from urllib.error import HTTPError
BASE='https://api.supabase.com'
SCHEMA=Path(__file__).parents[1]/'personal/schema-v1.sql'
class Refused(Exception):pass
class Sessions:
 def __init__(self):self.values={};self.lock=threading.RLock()
 def new(self):
  with self.lock:
   self.values={k:v for k,v in self.values.items() if v['expires']>time.time()}
   if len(self.values)>=200:raise Refused('Assistant occupé. Réessayez plus tard.')
   sid=secrets.token_urlsafe(32);v={'expires':time.time()+600,'state':secrets.token_urlsafe(32),'verifier':secrets.token_urlsafe(48),'csrf':secrets.token_urlsafe(32),'stage':'authorize'};self.values[sid]=v;return sid,v
 def get(self,sid):
  with self.lock:
   v=self.values.get(sid)
   if not v or v['expires']<time.time():self.values.pop(sid,None);raise Refused('Session expirée; recommencez la connexion.')
   return v
 def claim_callback(self,sid,state):
  with self.lock:
   v=self.get(sid)
   if v['stage']!='authorize' or not secrets.compare_digest(v['state'],state):raise Refused('Retour OAuth invalide')
   v['stage']='exchanging';return v
 def remove(self,sid):
  with self.lock:self.values.pop(sid,None)
SESSIONS=Sessions()
def call(path,token=None,body=None,method=None,form=False):
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 data=None
 if form:
  auth=(os.environ['SUPABASE_OAUTH_CLIENT_ID']+':'+os.environ['SUPABASE_OAUTH_CLIENT_SECRET']).encode();headers['Authorization']='Basic '+base64.b64encode(auth).decode();headers['Content-Type']='application/x-www-form-urlencoded';data=urlencode(body).encode()
 elif body is not None:headers['Content-Type']='application/json';data=json.dumps(body).encode()
 try:
  with urlopen(Request(BASE+path,data=data,headers=headers,method=method),timeout=30) as r:
   raw=r.read(2_000_001)
   if len(raw)>2_000_000:raise Refused('Réponse Supabase hors limites')
   return json.loads(raw)
 except HTTPError as e:raise Refused('Supabase a refusé une étape (HTTP %d). Aucun succès présumé.'%e.code)
def install(token,ref,projects):
 if not re.fullmatch('[a-z0-9]{20}',ref) or ref not in {p['id'] for p in projects}:raise Refused('Projet non autorisé')
 prefix='/v1/projects/'+ref
 sql=lambda q:call(prefix+'/database/query',token,{'query':q})
 rows=sql("select to_regclass('public.aiv_personal_segments')::text as existing")
 if rows[0]['existing'] is not None:raise Refused('Schéma AIV déjà présent : conserver les données et utiliser sa configuration. Aucune réinstallation automatique.')
 keys=call(prefix+'/api-keys?reveal=true',token)
 key=next((k.get('api_key','') for k in keys if k.get('type')=='publishable' and k.get('api_key','').startswith('sb_publishable_')),None)
 if not key:raise Refused('Publishable key indisponible. Aucun secret accepté en remplacement.')
 # Deliberately discard every other returned key. Never serialize a management token to Android.
 del keys
 sql(SCHEMA.read_text())
 check=sql("select count(*)::int as protected_tables from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in ('aiv_personal_segments','aiv_personal_events') and c.relrowsecurity")
 if check[0]['protected_tables']!=2:raise Refused('Vérification RLS échouée')
 call(prefix+'/config/auth',token,{'external_anonymous_users_enabled':True},method='PATCH')
 auth=call(prefix+'/config/auth',token)
 if not auth.get('external_anonymous_users_enabled'):raise Refused('Session anonyme non activée')
 return {'schema':'aiv-backup-config/1','project_url':'https://'+ref+'.supabase.co','publishable_key':key}
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def send(self,status,value,typ='text/html; charset=utf-8',extra=None):
  data=value.encode();self.send_response(status);self.send_header('Content-Type',typ);self.send_header('Cache-Control','no-store');self.send_header('Referrer-Policy','no-referrer');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'");self.send_header('Content-Length',str(len(data)))
  for k,v in (extra or {}).items():self.send_header(k,v)
  self.end_headers();self.wfile.write(data)
 def page(self,status,body):self.send(status,'<!doctype html><html lang="fr"><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8"><title>AIV · Supabase personnel</title><style>body{background:#051329;color:#eff5ff;font:18px/1.6 system-ui;max-width:680px;margin:40px auto;padding:20px}button,a{color:#62b8ff;font:inherit}button{background:#0b2346;border:1px solid #62b8ff;padding:14px}select{font:inherit;max-width:100%}</style><h1>AIV · Sauvegarde personnelle</h1>'+body+'</html>')
 def sid(self):
  cookie=SimpleCookie();cookie.load(self.headers.get('Cookie',''));return cookie['aiv_setup'].value if 'aiv_setup' in cookie else ''
 def do_GET(self):
  sid=self.sid()
  try:
   parsed=urlparse(self.path)
   if parsed.path=='/':self.page(200,'<p>Créez votre projet Supabase, puis autorisez AIV à installer son schéma dans le projet choisi. Les journaux seront envoyés directement par votre téléphone à ce projet.</p><p>L’autorisation permet de choisir le projet, installer les tables et protections, activer les sessions privées et récupérer uniquement la configuration publique destinée à AIV.</p><a href="/connect">Connecter Supabase</a>');return
   if parsed.path=='/connect':
    if sid:SESSIONS.remove(sid)
    sid,s=SESSIONS.new();challenge=base64.urlsafe_b64encode(hashlib.sha256(s['verifier'].encode()).digest()).decode().rstrip('=')
    q=urlencode({'client_id':os.environ['SUPABASE_OAUTH_CLIENT_ID'],'redirect_uri':os.environ['SETUP_ORIGIN']+'/callback','response_type':'code','state':s['state'],'code_challenge':challenge,'code_challenge_method':'S256'})
    self.send(302,'',extra={'Location':BASE+'/v1/oauth/authorize?'+q,'Set-Cookie':'aiv_setup='+sid+'; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=600'});return
   if parsed.path=='/callback':
    q=parse_qs(parsed.query);s=SESSIONS.claim_callback(sid,q.get('state',[''])[0])
    if 'error' in q:raise Refused('Autorisation annulée')
    tokens=call('/v1/oauth/token',body={'grant_type':'authorization_code','code':q.get('code',[''])[0],'redirect_uri':os.environ['SETUP_ORIGIN']+'/callback','code_verifier':s.pop('verifier')},form=True)
    s['token']=tokens['access_token'];del tokens
    s['projects']=call('/v1/projects',s['token']);s['stage']='choose'
    options=''.join('<option value="'+html.escape(p['id'],quote=True)+'">'+html.escape(p.get('name',p['id']))+'</option>' for p in s['projects'] if re.fullmatch('[a-z0-9]{20}',p['id']))
    self.page(200,'<form method="post" action="/install"><input type="hidden" name="csrf" value="'+s['csrf']+'"><label>Votre projet <select name="project">'+options+'</select></label><p>AIV installera son schéma v1 avec RLS. Vos tables existantes seront conservées. Les sessions anonymes privées seront activées.</p><button>Installer le schéma et obtenir la configuration AIV</button></form>');return
   self.page(404,'<p>Page absente.</p>')
  except Exception:
   SESSIONS.remove(sid);self.page(409,'<p>Étape non confirmée. Recommencez la connexion ; si des tables AIV existent déjà, utilisez leur configuration sans les réinstaller.</p>')
 def do_POST(self):
  sid=self.sid()
  try:
   if self.path!='/install' or self.headers.get('Origin')!=os.environ['SETUP_ORIGIN']:raise Refused('Origine refusée')
   n=int(self.headers.get('Content-Length','0'))
   if n<=0 or n>4096:raise Refused('Formulaire hors limites')
   fields=parse_qs(self.rfile.read(n).decode());s=SESSIONS.get(sid)
   with SESSIONS.lock:
    if s['stage']!='choose' or set(fields)!={'csrf','project'} or not secrets.compare_digest(fields['csrf'][0],s['csrf']):raise Refused('Confirmation invalide')
    s['stage']='installing'
   config=install(s['token'],fields['project'][0],s['projects']);SESSIONS.remove(sid)
   self.send(200,json.dumps(config),'application/json',{'Content-Disposition':'attachment; filename="AIV-Supabase-configuration.json"','Set-Cookie':'aiv_setup=; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=0'})
  except Exception:
   SESSIONS.remove(sid);self.page(409,'<p>Installation non confirmée. Aucun état « connecté » déclaré. Vérifiez le projet avant de réessayer.</p>')
def main():
 origin=os.environ['SETUP_ORIGIN'];parsed=urlparse(origin)
 if parsed.scheme!='https' or parsed.path or parsed.query or parsed.fragment or parsed.username:raise ValueError('SETUP_ORIGIN: HTTPS origin only')
 ThreadingHTTPServer((os.environ.get('BIND_HOST','127.0.0.1'),int(os.environ.get('PORT','8081'))),Handler).serve_forever()
if __name__=='__main__':main()
