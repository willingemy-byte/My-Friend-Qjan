AIV 0.6.8 CLEAN BUILD
=====================

Run bootstrap-clean-build.sh on a Linux x86_64 executor with outbound HTTPS.
It downloads ONLY the exact official Android/Maven artifacts recorded by this project and verifies size + SHA-256 before use.

Required private files (DO NOT commit/publish):
  .signing-private/journal-local.p12
  .signing-private/password.txt

Expected final artifact:
  build/AIV-0.6.8-clean.apk

App package: fr.erick.journallocal
Version: 0.6.8-clean / versionCode 14
Expected signing certificate SHA-256:
  4a14b9e2cf3869fa9faf2317cba96e948145e4dba32240f9547380c040deba7b
