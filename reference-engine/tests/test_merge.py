import json, tempfile, unittest
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from aiv_reference_merge import merge

class MergeTests(unittest.TestCase):
    def test_sources_stay_separate_and_custom_permissions_survive(self):
        device={"com.example.app":{"permission_meta":[
            {"name":"android.permission.CAMERA","granted":True,"protection_level":4097},
            {"name":"com.example.PRIVATE_SKILL","granted":True,"protection_level":2}
        ]}}
        bayton={"permissions":{"android.permission.CAMERA":{"protectionLevel":"dangerous","description":"Camera"}}}
        exodus={"trackers":{"42":{"name":"Example Tracker","network_signature":"\\.example\\.com","code_signature":"com.example.sdk","categories":["Analytics"]}}}
        out=merge(device,bayton,exodus)
        self.assertEqual(out["sources"]["bayton_aosp"]["kind"],"permission_catalog")
        self.assertEqual(out["sources"]["exodus"]["kind"],"tracker_catalog")
        perms=out["apps"][0]["permissions"]
        self.assertEqual(perms[0]["bayton_aosp"]["protectionLevel"],"dangerous")
        self.assertIsNone(perms[1]["bayton_aosp"])
        self.assertEqual(out["tracker_catalog"]["42"]["code_signature"],"com.example.sdk")
        self.assertNotIn("score",json.dumps(out).lower())
        self.assertNotIn("multiplier",json.dumps(out).lower())
if __name__=="__main__": unittest.main()
