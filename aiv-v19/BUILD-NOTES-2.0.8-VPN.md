# Build AIV 2.0.8 — continuité VPN et attribution

SDK 35 r02, Build Tools 35.0.0, Java 17; minimum API 26, cible 35. Build avec `-Xlint:all`: **49 dépréciations Java, aucune erreur**. Aucun warning nouveau dans UidProbe/CaptureQueue. Les familles sont celles du [registre 2.0.6](BUILD-NOTES-2.0.6-OBSERVABILITY.md), dont les justifications restent applicables.

B = bénin et expliqué pour ce correctif; J = potentiellement lié au collecteur, à vérifier sur téléphone. Ce build conserve ces dépréciations; il ne prétend pas les avoir toutes éliminées.

| Source : ligne | Diagnostic | Classe / explication |
|---|---|---|
| `ApkEvidence.java:35` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `ApkEvidence.java:69` | [deprecation] signatures in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `AppIdentity.java:28` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `AppIdentity.java:48` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `DefenseActions.java:23` | [deprecation] ACTION_UNINSTALL_PACKAGE in Intent has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `DeveloperControl.java:51` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `DeveloperControl.java:59` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `DeviceIdentity.java:88` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `MainActivity.java:100` | [deprecation] setStatusBarColor(int) in Window has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `MainActivity.java:101` | [deprecation] setNavigationBarColor(int) in Window has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetLeft() in WindowInsets has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetTop() in WindowInsets has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetRight() in WindowInsets has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetBottom() in WindowInsets has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `NetworkCaptureService.java:169` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `PinVault.java:26` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `NetworkHealth.java:15` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `PermissionAudit.java:49` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionAudit.java:55` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionAudit.java:56` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionAudit.java:79` | [deprecation] getInstallerPackageName(String) in PackageManager has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionAudit.java:104` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionAudit.java:111` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionAudit.java:122` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionControl.java:242` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionControl.java:252` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionControl.java:288` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionControl.java:290` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionControl.java:312` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionControl.java:337` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionNorms.java:21` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionNorms.java:24` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `PermissionNorms.java:24` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `RecorderService.java:69` | [deprecation] addAction(int,CharSequence,PendingIntent) in Builder has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `RecorderService.java:94` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `RecorderService.java:94` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `RecorderService.java:112` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `RecorderService.java:113` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `RecorderService.java:116` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `RecorderService.java:166` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | J — API existante du collecteur ou de sélection réseau; vérification réelle Android requise. |
| `ScreenIntegrityService.java:90` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `ScreenIntegrityService.java:242` | [deprecation] obtain(AccessibilityNodeInfo) in AccessibilityNodeInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `ScreenIntegrityService.java:266` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `SecurityContext.java:13` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `ShizukuCleanup.java:362` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `ShizukuCleanup.java:362` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `SpecialAccess.java:92` | [deprecation] checkOpNoThrow(String,int,String) in AppOpsManager has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `TrackerIndex.java:251` | [deprecation] versionCode in PackageInfo has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |
| `WatcherService.java:69` | [deprecation] stopForeground(boolean) in Service has been deprecated | B — famille existante expliquée dans le registre 2.0.6; compatibilité conservée. |

## Tests, outils et limites

- Les bibliothèques ARM64/x86_64 et toutes leurs sources natives sont inchangées. Réutilisation depuis APK 2.0.4 épinglé avec vérification SHA-256 par `tools/native-reuse.json`; les diagnostics du build natif de référence restent classés dans le registre 2.0.6.
- Le compilateur hôte C signale `third_party/zdtun/utils.c:283`, retour de `system()` ignoré. Helper de configuration de TUN tiers non appelé par le faux TUN socketpair de ces essais. Warning préexistant, à corriger dans cet utilitaire; aucune preuve de panne du relais exercé.
- La note Java du harnais JNI correspond à la dépréciation `getAllNetworks`, classée J ci-dessus. La nouvelle classe UID et son test compilent avec `-Xlint:all` sans diagnostic.
- Le test natif utilise ASan/UBSan. LeakSanitizer reste désactivé dans ce conteneur faute d’accès aux tâches /proc; aucun résultat de détection de fuite annoncé.
- SQLite JDBC peut annoncer SLF4J sans backend/NOP. Les insertions et exports SQLite restent réellement exercés. Jars et stubs sont exclus de l’APK.
- Un test Python historique reste explicitement SKIP: ancien moteur retiré depuis V21. Les douze autres tests de cette suite passent; les règles natives sont vérifiées séparément.
- Le premier lancement de la défense a échoué car le PATH limité aux outils Android omettait Node. Le PATH a été corrigé. Le lancement suivant nécessitait aussi AIV_JSON_JAR pour le snapshot; cette variable a été rétablie avant de rejouer les scénarios restants. Il s’agit du harnais hôte, sans changement APK.
- Les huit scénarios C/JNI passent; le retard temporaire de 3,4 s conserve désormais le relais actif. Le test d’arrêt persistant réduit uniquement sa constante compilée hôte à 400 ms; l’APK conserve 30 s.
- Le défaut UID 2.0.7 est reproduit avec son service épinglé. Le test 2.0.8 observe le UID pendant un journal retardé et le conserve après fermeture. Le package après fermeture, le rejet d’une réponse tardive et un tuple réutilisé avec un autre UID sont vérifiés.
- Aucun adb, émulateur, essai batterie constructeur, transition physique Wi-Fi/cellulaire ou passage réel AIV/Chrome/ChatGPT n’est revendiqué. Les réponses propriétaire de socket, Keystore et services Android restent des fixtures contrôlées.
- Les formats JSON/JSONL, SQLite, hash, métadonnées et période restent vérifiés par les tests existants. Les assets HTML historiques passent leurs tests de régression mais ne sont pas embarqués dans l’interface native.
