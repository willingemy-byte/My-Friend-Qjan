#!/usr/bin/env python3
"""Sign an existing AIV APK with the historical Android signing identity.

No private key or password is stored in this repository. The caller supplies a
private signing directory containing journal-local.p12 and password.txt.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
from pathlib import Path

EXPECTED_CERT_SHA256 = "4a14b9e2cf3869fa9faf2317cba96e948145e4dba32240f9547380c040deba7b"
KEY_ALIAS = "journal-local"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def expected_unsigned_sha(apk: Path, explicit: str | None) -> str:
    if explicit:
        return explicit.strip().lower()
    companion = Path(str(apk) + ".sha256")
    if not companion.is_file():
        raise SystemExit(
            "Missing unsigned checksum. Supply --sha256 or keep <apk>.sha256 beside the APK."
        )
    value = companion.read_text(encoding="utf-8").strip().split()[0].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise SystemExit(f"Invalid SHA-256 in {companion}")
    return value


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--apk", required=True, type=Path)
    p.add_argument("--apksigner", required=True, type=Path)
    p.add_argument("--signing-dir", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--sha256", help="Expected SHA-256 of the unsigned APK")
    args = p.parse_args()

    apk = args.apk.resolve()
    apksigner = args.apksigner.resolve()
    signing_dir = args.signing_dir.resolve()
    output = args.output.resolve()

    if not apk.is_file():
        raise SystemExit(f"Unsigned APK not found: {apk}")
    if not apksigner.is_file():
        raise SystemExit(f"apksigner.jar not found: {apksigner}")
    if output == apk:
        raise SystemExit("Output must be different from the unsigned APK")
    if output.exists():
        raise SystemExit(f"Refusing to overwrite existing output: {output}")

    actual_unsigned = sha256_file(apk)
    expected_unsigned = expected_unsigned_sha(apk, args.sha256)
    if actual_unsigned != expected_unsigned:
        raise SystemExit(
            f"Unsigned APK checksum mismatch: expected {expected_unsigned}, got {actual_unsigned}"
        )

    key = signing_dir / "journal-local.p12"
    password = signing_dir / "password.txt"
    if not key.is_file() or not password.is_file():
        raise SystemExit(
            "Private signing directory must contain journal-local.p12 and password.txt"
        )

    certificate = subprocess.check_output(
        [
            "keytool",
            "-exportcert",
            "-keystore",
            str(key),
            "-storetype",
            "PKCS12",
            "-alias",
            KEY_ALIAS,
            "-storepass:file",
            str(password),
        ]
    )
    cert_sha = hashlib.sha256(certificate).hexdigest()
    if cert_sha != EXPECTED_CERT_SHA256:
        raise SystemExit(
            f"Unexpected signing certificate: expected {EXPECTED_CERT_SHA256}, got {cert_sha}"
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "java",
            "-jar",
            str(apksigner),
            "sign",
            "--ks",
            str(key),
            "--ks-type",
            "PKCS12",
            "--ks-key-alias",
            KEY_ALIAS,
            "--ks-pass",
            "file:" + str(password),
            "--out",
            str(output),
            str(apk),
        ],
        check=True,
    )

    verification = subprocess.check_output(
        [
            "java",
            "-jar",
            str(apksigner),
            "verify",
            "--verbose",
            "--print-certs",
            str(output),
        ],
        text=True,
    )
    match = re.search(
        r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)",
        verification,
    )
    if not match or match.group(1).lower() != EXPECTED_CERT_SHA256:
        output.unlink(missing_ok=True)
        raise SystemExit("Signed APK identity verification failed")

    final_sha = sha256_file(output)
    sha_path = Path(str(output) + ".sha256")
    sha_path.write_text(f"{final_sha}  {output.name}\n", encoding="utf-8")

    print(f"Signed APK: {output}")
    print(f"Unsigned SHA-256: {actual_unsigned}")
    print(f"Certificate SHA-256: {EXPECTED_CERT_SHA256}")
    print(f"Signed APK SHA-256: {final_sha}")
    print(verification.rstrip())


if __name__ == "__main__":
    main()
