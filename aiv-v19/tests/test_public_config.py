import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class PublicConfigTests(unittest.TestCase):
    def test_relocated_repository_and_moved_defaults(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)/'relocated-project'
            tools = root/'aiv-v19/tools'
            tools.mkdir(parents=True)
            shutil.copy(ROOT/'aiv-v19/tools/public_config.py', tools/'public_config.py')
            target = root/'settings/defaults.json'
            target.parent.mkdir()
            target.write_text('{}')
            index = {'schema':'aiv-config-index/1','sources':{'DEFAULTS_CONFIG':'settings/defaults.json'}}
            (root/'AIV_CONFIG.json').write_text(json.dumps(index))
            spec = importlib.util.spec_from_file_location('relocated_config',tools/'public_config.py')
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            self.assertEqual(target,module.config_source('DEFAULTS_CONFIG'))
            for path in ['/outside.json','../outside.json']:
                index['sources']['DEFAULTS_CONFIG']=path
                (root/'AIV_CONFIG.json').write_text(json.dumps(index))
                with self.assertRaises(ValueError): module.config_source('DEFAULTS_CONFIG')

    def test_inventory_matches_sources_and_has_no_required_secrets(self):
        subprocess.run([sys.executable,str(ROOT/'aiv-v19/tools/list_public_values.py'),'--check'],check=True)
        data=json.loads((ROOT/'AIV_VALUES.json').read_text())
        self.assertEqual([],data['secrets_required_for_unsigned_build'])
        for key in data['values']: self.assertRegex(key,r'^[A-Z][A-Z0-9_]*$')
        self.assertEqual([1,2,3],[data['values'][f'USER_TIER_{tier}'] for tier in ['FREE','PAID','IT']])

if __name__=='__main__': unittest.main()
