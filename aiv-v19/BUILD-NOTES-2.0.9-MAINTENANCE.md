# Build AIV 2.0.9 — maintien des permissions

SDK 35 r02, Build Tools 35.0.0, Java 17; minimum API 26, cible 35. Compilation avec `-Xlint:all`: **52 dépréciations Java, aucune erreur**. Les 49 diagnostics de la [2.0.8](BUILD-NOTES-2.0.8-VPN.md) restent présents; trois diagnostics concernent la nouvelle classe de maintien. Aucun diagnostic n’est masqué par une annotation de suppression.

B = compatibilité bénigne expliquée; J = API existante du collecteur nécessitant encore validation téléphone; M = nouvel usage pour le maintien nécessitant vérification Android réelle. Les champs dépréciés restent utilisés à dessein dans le code compatible API 26; les tests hôte ne certifient pas les réponses des API Samsung.

| Source : ligne | Diagnostic | Classe / explication |
|---|---|---|
| `ApkEvidence.java:35` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `ApkEvidence.java:69` | [deprecation] signatures in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `AppIdentity.java:28` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `AppIdentity.java:48` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `DefenseActions.java:23` | [deprecation] ACTION_UNINSTALL_PACKAGE in Intent has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `DeveloperControl.java:51` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `DeveloperControl.java:59` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `DeviceIdentity.java:88` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `MainActivity.java:100` | [deprecation] setStatusBarColor(int) in Window has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `MainActivity.java:101` | [deprecation] setNavigationBarColor(int) in Window has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetLeft() in WindowInsets has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetTop() in WindowInsets has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetRight() in WindowInsets has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetBottom() in WindowInsets has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `NetworkCaptureService.java:169` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `PinVault.java:26` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `NetworkHealth.java:15` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `PermissionAudit.java:49` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionAudit.java:55` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionAudit.java:56` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionAudit.java:79` | [deprecation] getInstallerPackageName(String) in PackageManager has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionAudit.java:104` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionAudit.java:111` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionAudit.java:122` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionControl.java:242` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionControl.java:252` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionControl.java:288` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionControl.java:290` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionControl.java:312` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionControl.java:337` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionMaintenance.java:66` | [deprecation] versionCode in PackageInfo has been deprecated | B — lecture de versionCode réservée à API 26–27; getLongVersionCode utilisé dès API 28. |
| `PermissionMaintenance.java:103` | [deprecation] protectionLevel in PermissionInfo has been deprecated | M — classification danger/développement par masque de protection; résultat Shell et état Android relus, API constructeur à valider sur téléphone. |
| `PermissionMaintenance.java:187` | [deprecation] protectionLevel in PermissionInfo has been deprecated | M — classification danger/développement par masque de protection; résultat Shell et état Android relus, API constructeur à valider sur téléphone. |
| `PermissionNorms.java:21` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionNorms.java:24` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `PermissionNorms.java:24` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `RecorderService.java:72` | [deprecation] addAction(int,CharSequence,PendingIntent) in Builder has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `RecorderService.java:97` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `RecorderService.java:97` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `RecorderService.java:115` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `RecorderService.java:116` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `RecorderService.java:119` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `RecorderService.java:169` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | J — API existante du collecteur; mêmes dépréciations que 2.0.8, vérification Android réelle requise. |
| `ScreenIntegrityService.java:90` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `ScreenIntegrityService.java:242` | [deprecation] obtain(AccessibilityNodeInfo) in AccessibilityNodeInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `ScreenIntegrityService.java:266` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `SecurityContext.java:13` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `ShizukuCleanup.java:362` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `ShizukuCleanup.java:362` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `SpecialAccess.java:92` | [deprecation] checkOpNoThrow(String,int,String) in AppOpsManager has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `TrackerIndex.java:251` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |
| `WatcherService.java:69` | [deprecation] stopForeground(boolean) in Service has been deprecated | B — famille existante justifiée dans le registre 2.0.8; compatibilité conservée. |

## Outils, tests et limites

- Les bibliothèques ARM64/x86_64 et les sources natives restent identiques à la 2.0.8. Réutilisation de l’APK 2.0.4 épinglé, avec vérification SHA-256 et sources par `tools/native-reuse.json`. Les diagnostics du build natif de référence sont classés dans le registre 2.0.6.
- Le harnais C/JNI signale le retour `system()` ignoré de `third_party/zdtun/utils.c:283`. Le helper de configuration TUN concerné n’est pas appelé par le faux TUN socketpair du test. Diagnostic préexistant, sans nouvelle modification native.
- La note de dépréciation Java du harnais C/JNI provient de `getAllNetworks`, classé J. Les huit scénarios du relais passent, dont un retard temporaire de 3,4 secondes toléré.
- SQLite JDBC peut signaler SLF4J sans backend; les requêtes et exports SQLite sont réellement exécutés. Les stubs, jars hôte et classes de test ne sont pas compilés dans l’APK.
- Les tests de maintien passent 96 vérifications, dont restaurations par le vrai PermissionControl, écriture de l’approbation avant réactivation, refus de commande après changement de rôle, erreurs de disque/journal, code nul sans effet, bornes et rotation. Les 25 vérifications antérieures du contrôleur passent aussi. Les règles de permissions passent 40 cas, l’examen 36 cas et les bornes du transport Shell restent vérifiées.
- Les scénarios d’UID, d’observation réseau, d’horodatage EventStore, de pagination/export SQLite et de C/JNI passent. Les sources de NetworkCaptureService, UidProbe, CaptureQueue, EventStore, JournalSqlite, du relais C et de ControlShell sont identiques à la base 2.0.8.
- Aucun adb, émulateur, essai de veille/batterie Samsung, Keystore réel, ni temps de détection mesuré sur téléphone n’est revendiqué. L’essai téléphone figure dans les notes de version. Le maintien ne garantit ni l’immédiateté, ni un veto d’installation, ni l’absence de réattribution inconnue sur le réseau.
