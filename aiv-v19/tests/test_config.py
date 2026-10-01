import copy
import importlib.util
import json
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('generate_config', ROOT/'tools/generate_config.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT/'config/defaults.json').read_text())
    def test_generated_file_matches_valid_config(self):
        self.assertEqual(module.render(self.data), (ROOT/'app/src/main/java/fr/erick/journallocal/AivConfig.java').read_text())
    def test_invalid_types_bounds_and_unknown_fields(self):
        for field, value in [('events', True), ('events', 0), ('events', 1025), ('secret', 'private')]:
            data=copy.deepcopy(self.data);data['work'][field]=value
            with self.assertRaises(ValueError): module.validate(data)
    def test_gap_cannot_be_shorter_than_sampling(self):
        self.data['collection']['gap_ms']=self.data['collection']['heartbeat_ms']
        with self.assertRaises(ValueError): module.validate(self.data)
    def test_paths_cannot_escape_local_directory(self):
        for value in ['../public', '/sdcard/export', 'nested/path', '', 'x\n']:
            data=copy.deepcopy(self.data);data['paths']['exports_cache']=value
            with self.assertRaises(ValueError): module.validate(data)
    def test_modified_config_changes_digest_and_value(self):
        before=module.render(self.data);self.data['work']['events']=32;after=module.render(self.data)
        self.assertNotEqual(before, after)
        self.assertIn('WORK_EVENTS=32;', after)

if __name__ == '__main__': unittest.main()
