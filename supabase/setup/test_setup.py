import unittest,importlib.util,time,json
from pathlib import Path
spec=importlib.util.spec_from_file_location('setup',Path(__file__).with_name('server.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Tests(unittest.TestCase):
 def test_pkce_session_callback_replay_expiry(self):
  s=m.Sessions();sid,v=s.new()
  with self.assertRaises(m.Refused):s.claim_callback(sid,'wrong')
  self.assertEqual(s.claim_callback(sid,v['state'])['stage'],'exchanging')
  with self.assertRaises(m.Refused):s.claim_callback(sid,v['state'])
  v['expires']=time.time()-1
  with self.assertRaises(m.Refused):s.get(sid)
 def test_installer_filters_secrets_and_verifies_rls(self):
  calls=[];ref='a'*20
  def fake(path,token=None,body=None,**kw):
   calls.append((path,body,kw))
   if '/api-keys' in path:return [{'type':'secret','api_key':'sb_secret_PRIVATE'},{'type':'publishable','api_key':'sb_publishable_PUBLIC123456789'}]
   if path.endswith('/database/query'):
    if 'to_regclass' in body['query']:return [{'existing':None}]
    if 'protected_tables' in body['query']:return [{'protected_tables':2}]
    return []
   return {'external_anonymous_users_enabled':True}
  old=m.call;m.call=fake
  try:
   with self.assertRaises(m.Refused):m.install('TOKEN','other', [{'id':ref}])
   self.assertFalse(calls);result=m.install('TOKEN',ref,[{'id':ref}]);self.assertNotIn('PRIVATE',json.dumps(result));self.assertNotIn('TOKEN',json.dumps(result));self.assertEqual(result['project_url'],'https://'+ref+'.supabase.co')
   self.assertTrue(any('enable row level security' in (b or {}).get('query','') for p,b,k in calls))
   self.assertEqual(set(result),{'schema','project_url','publishable_key'})
  finally:m.call=old
 def test_existing_schema_never_overwritten(self):
  old=m.call;calls=[]
  def fake(*a,**k):calls.append(a);return [{'existing':'aiv_personal_segments'}]
  m.call=fake
  try:
   with self.assertRaises(m.Refused):m.install('TOKEN','a'*20,[{'id':'a'*20}])
   self.assertEqual(len(calls),1)
  finally:m.call=old
if __name__=='__main__':unittest.main(verbosity=2)
