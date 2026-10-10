import json
import tempfile
import unittest
from pathlib import Path
from generate_demo_config import make_config, write_config

class UserConfigTest(unittest.TestCase):
    def test_unique_and_default_private(self):
        a, b = make_config(), make_config()
        self.assertNotEqual(a["USER_ID"], b["USER_ID"])
        self.assertNotEqual(a["AIV_ID"], b["AIV_ID"])
        self.assertFalse(a["CONSENT"]["SHARE_COLLECTIVE_STATISTICS"])
        self.assertFalse(a["CONSENT"]["ENCRYPTED_PRIVATE_BACKUP"])
        self.assertEqual(a["SECURITY_STATE"], "NOT_ENROLLED")
        self.assertNotIn("SECRET", json.dumps(a))

    def test_write_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "config.json"
            write_config(target)
            data = json.loads(target.read_text())
            self.assertTrue(data["USER_ID"].startswith("usr_"))
            with self.assertRaises(FileExistsError):
                write_config(target)

if __name__ == "__main__":
    unittest.main()
