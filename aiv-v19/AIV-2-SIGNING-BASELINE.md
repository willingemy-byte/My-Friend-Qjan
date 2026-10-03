# AIV 2.x signing baseline

Official Android package:

`com.allinvisible.aiv`

Starting with the clean AIV 2.x baseline, every installable APK in the 2.x line must be signed with the same APK signing identity.

Expected APK signing certificate SHA-256:

`4a14b9e2cf3869fa9faf2317cba96e948145e4dba32240f9547380c040deba7b`

Rules:

1. Do not generate or substitute a new APK signing key for a 2.x update.
2. The private signing key and password stay outside the repository.
3. CI may build unsigned artifacts; final installable APKs must be verified against the expected certificate before release.
4. Before release, record APK SHA-256, Git commit, versionCode and versionName.
5. If the installed APK has another signing identity, it must be uninstalled once before installing this clean 2.x baseline. That uninstall removes local app data and Android special-access grants must then be re-enabled by the user.
6. Android Keystore keys used inside AIV for journal/export identity are separate from the APK signing identity.

Current clean baseline candidate:

- package: `com.allinvisible.aiv`
- versionCode: `201`
- versionName: `2.0.1`
