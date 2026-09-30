# AIV 1.1.1 — clean V19 baseline

Source baseline: commit `a5dd649a150b36333ffe2add3085bec72950edea` (AIV 0.6.19).

This branch intentionally starts from that V19 state rather than the later V38–V41 line.

- package: `fr.erick.journallocal`
- versionName: `1.1.1`
- versionCode: `111`
- update requirement: must be signed with the historical AIV Android signing certificate
- historical signing certificate SHA-256: `4a14b9e2cf3869fa9faf2317cba96e948145e4dba32240f9547380c040deba7b`

The high versionCode is deliberate so Android accepts this clean baseline as an upgrade over 0.6.40/versionCode 46 without uninstalling the existing application.
