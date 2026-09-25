import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
P=ROOT/'tools/generate_verdicts.py'
spec=importlib.util.spec_from_file_location('aiv_test_engine',P)
engine=importlib.util.module_from_spec(spec)
sys.modules[spec.name]=engine
spec.loader.exec_module(engine)

class VerdictTests(unittest.TestCase):
    def test_gmail_prefix_does_not_capture_google_play_services(self):
        self.assertEqual('email',engine.infer_category('com.google.android.gm'))
        self.assertEqual('system',engine.infer_category('com.google.android.gms'))
        self.assertEqual('system',engine.infer_category('com.google.android.gms.supervision'))
        self.assertEqual('browser',engine.infer_category('com.android.chrome'))
        self.assertEqual('unknown',engine.infer_category('com.whatsappfake'))
    def test_penalty_breakdown_matches_score(self):
        for package in ['com.google.android.gm','com.accuweather.demo','com.whatsapp','android']:
            r=engine.evaluate_local(engine.AppInfo(package,permissions=['CALL_PHONE','WRITE_CALL_LOG','READ_SMS','INSTALL_PACKAGES']))
            self.assertEqual(r.score_local,max(0,100-sum(x.penalty for x in r.incoherences_local)))
    def test_weather_call_phone_below_65(self):
        self.assertLess(engine.evaluate_local(engine.AppInfo('com.accuweather.demo',permissions=['CALL_PHONE'])).score_local,65)
    def test_unknown_does_not_invent_p1(self):
        r=engine.evaluate_local(engine.AppInfo('org.example.unknown',permissions=['CALL_PHONE']))
        self.assertEqual(100,r.score_local);self.assertEqual([],r.incoherences_local)
    def test_fingerprint_stable(self):
        self.assertEqual(engine.fingerprint_of(['CALL_PHONE','INTERNET']),engine.fingerprint_of(['android.permission.INTERNET','CALL_PHONE','CALL_PHONE']))
    def test_output_json_serializable(self):
        r=engine.evaluate_local(engine.AppInfo('com.google.android.gm',permissions=['INSTALL_PACKAGES']))
        self.assertIsInstance(json.dumps({'score':r.score_local,'fingerprint':r.fingerprint}),str)

if __name__=='__main__': unittest.main()