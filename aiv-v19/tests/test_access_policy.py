import copy
import importlib.util
import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('generate_access_policy',ROOT/'tools/generate_access_policy.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class AccessTests(unittest.TestCase):
    def setUp(self): self.data=json.loads((ROOT/'config/access-policy.json').read_text())
    def test_generated_policy_matches(self):
        self.assertEqual(module.render(self.data),(ROOT/'app/src/main/java/fr/erick/journallocal/AccessPolicy.java').read_text())
    def test_paid_service_can_share_the_same_catalog(self):
        self.data['service_minimum_tiers']['backup.remote']=2
        self.assertIn('case "backup.remote": return 2;',module.render(self.data))
    def test_reject_invalid_or_reserved_minimum(self):
        for value in [True,0,3,'2',999]:
            self.data['service_minimum_tiers']['journal.read']=value
            with self.assertRaises(ValueError): module.validate(self.data)
    def test_reject_injected_service_name(self):
        self.data['service_minimum_tiers']['bad"service']=1
        with self.assertRaises(ValueError): module.validate(self.data)
    def test_reject_changed_tier_identity(self):
        self.data['tiers']['paid']=3
        with self.assertRaises(ValueError): module.validate(self.data)
if __name__=='__main__': unittest.main()
