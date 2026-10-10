#!/usr/bin/env python3
"""Generate an illustrative PRIVATE local AIV config. NOT an enrollment mechanism."""
import json
import secrets
import sys
from pathlib import Path

def make_config():
    return {
        "SCHEMA": "aiv.user-config.v1",
        "CONFIG_REVISION": 1,
        "USER_ID": "usr_" + secrets.token_hex(16),
        "AIV_ID": "aiv_" + secrets.token_hex(16),
        "POLICY_ID": "aiv-privacy-v1",
        "CONSENT": {
            "ENCRYPTED_PRIVATE_BACKUP": False,
            "SHARE_COLLECTIVE_STATISTICS": False
        },
        "DESTINATIONS": {
            "PRIVATE_VAULT": "NOT_CONFIGURED",
            "COLLECTIVE_ANALYTICS": "DISABLED"
        },
        "SECURITY_STATE": "NOT_ENROLLED"
    }

def write_config(path):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    data = (json.dumps(make_config(), ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    # Exclusive create: refuse overwrite or symlink; owner-only on POSIX.
    with target.open("xb") as file:
        file.write(data)
    try:
        target.chmod(0o600)
    except OSError:
        pass
    return target

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: generate_demo_config.py /private/path/user-config.json")
    target = write_config(sys.argv[1])
    print("Private demo config created (NOT ENROLLED):", target)
