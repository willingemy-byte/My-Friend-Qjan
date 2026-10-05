# Build AIV 2.0.7 — stabilité du VPN

SDK 35 r02, Build Tools 35.0.0, Java 17; minimum API 26, cible 35.
Build avec `-Xlint:all`, diagnostics conservés. **49 dépréciations Java, aucune erreur.** Aucun diagnostic nouveau dans CaptureQueue ou les méthodes d’horodatage.

B = bénin et expliqué pour ce correctif. J = potentiellement lié à la collecte et à vérifier sur téléphone.
Les 49 occurrences sont les mêmes familles que dans [le registre 2.0.6](BUILD-NOTES-2.0.6-OBSERVABILITY.md), avec les lignes actuelles ci-dessous. Ce correctif ne prétend pas supprimer toutes les dépréciations de l’application.

| Source : ligne | Diagnostic | Classe |
|---|---|---|
| `ApkEvidence.java:35` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `ApkEvidence.java:69` | [deprecation] signatures in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `AppIdentity.java:28` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `AppIdentity.java:48` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `DefenseActions.java:23` | [deprecation] ACTION_UNINSTALL_PACKAGE in Intent has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `DeveloperControl.java:51` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `DeveloperControl.java:59` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `DeviceIdentity.java:88` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `MainActivity.java:100` | [deprecation] setStatusBarColor(int) in Window has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `MainActivity.java:101` | [deprecation] setNavigationBarColor(int) in Window has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetLeft() in WindowInsets has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetTop() in WindowInsets has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetRight() in WindowInsets has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetBottom() in WindowInsets has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `NetworkCaptureService.java:161` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `PinVault.java:26` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `NetworkHealth.java:15` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `PermissionAudit.java:49` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionAudit.java:55` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionAudit.java:56` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionAudit.java:79` | [deprecation] getInstallerPackageName(String) in PackageManager has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionAudit.java:104` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionAudit.java:111` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionAudit.java:122` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionControl.java:242` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionControl.java:252` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionControl.java:288` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionControl.java:290` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionControl.java:312` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionControl.java:337` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionNorms.java:21` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionNorms.java:24` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `PermissionNorms.java:24` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `RecorderService.java:69` | [deprecation] addAction(int,CharSequence,PendingIntent) in Builder has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `RecorderService.java:94` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `RecorderService.java:94` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `RecorderService.java:112` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `RecorderService.java:113` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `RecorderService.java:116` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `RecorderService.java:166` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | J — API existante de sélection réseau/collecteur; essai Android requis. |
| `ScreenIntegrityService.java:90` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `ScreenIntegrityService.java:242` | [deprecation] obtain(AccessibilityNodeInfo) in AccessibilityNodeInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `ScreenIntegrityService.java:266` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `SecurityContext.java:13` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `ShizukuCleanup.java:362` | [deprecation] protectionLevel in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `ShizukuCleanup.java:362` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `SpecialAccess.java:92` | [deprecation] checkOpNoThrow(String,int,String) in AppOpsManager has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `TrackerIndex.java:251` | [deprecation] versionCode in PackageInfo has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |
| `WatcherService.java:69` | [deprecation] stopForeground(boolean) in Service has been deprecated | B — API existante; justification de la famille dans le registre 2.0.6. |

## Entrées natives et annotations des tests

Le premier essai de préparation a échoué au lancement d’aapt2 : archive extraite avec le mode non exécutable. Le mode exécutable d’aapt2/zipalign a été restauré, puis le build complet a réussi. Les en-têtes JNI hôte absents ont été obtenus du tag immuable OpenJDK `jdk-17+35`; ce dépannage concerne les tests hôte et ne modifie pas l’APK.

Le C/JNI n’est pas modifié. Les `.so` ARM64/x86_64 sont repris de l’APK signé 2.0.4 épinglé (`232cdeb2a518434bf41a81865908b15194b3b1a364ed85af02f50753f1b2cae8`). `build.py` vérifie son hash, chaque source native/tiers et le hash de chacune des deux bibliothèques contre `tools/native-reuse.json`. Aucun NDK ou binaire natif nouveau n’est supposé validé par cette réutilisation. Les 24 occurrences du build NDK de référence sont classées dans le registre 2.0.6.

- Compilation du test JNI sur hôte : `utils.c:283`, retour de `system()` ignoré. Helper tiers de diagnostic non appelé par AIV ou ces essais; aucun changement du tiers. Le helper de TUN du test vérifie désormais son propre retour de `write()`.
- Compilation Java du test JNI : note de dépréciation pour `getAllNetworks`, déjà classée J ci-dessus. La compilation de l’APK expose sa ligne complète.
- Test natif : ASan/UBSan actifs; LeakSanitizer désactivé car accès `/proc/.../task` refusé dans ce conteneur. Aucune conclusion sur les fuites mémoire.
- SQLite JDBC : SLF4J sans implémentation annonce le logger NOP. Le moteur SQLite et les exports sont réellement exercés; les jars/stubs hôte sont exclus de l’APK.
- `test_verdicts.py` : SKIP explicite, ancien générateur retiré depuis V21. Les règles natives applicables ont leurs tests Java.
- Les tests HTML historiques portent sur des assets exclus de l’APK. Un passage de ces tests ne valide pas l’interface Android native. Les contrôles Playwright de présentation ne sont pas revendiqués comme passés.
- Aucun adb, téléphone ou émulateur disponible. Les réponses d’Android (UID, Shizuku, Keystore, préférences, binding réseau) sont contrôlées dans les fixtures. Consentement VPN, fermeture réelle du TUN, maintien d’Internet, transitions Wi-Fi/cellulaire, mise à jour sans perte de journal et performance restent à confirmer sur téléphone.
- Si une API Android ne revient jamais, les observations déjà en attente ne peuvent pas être garanties écrites. Le relais s’arrête indépendamment de cet observateur, et les commandes acceptées sont vidées lorsque l’API revient. Une saturation est une lacune explicite, sans estimation de paquets perdus.
- Clé de signature et captures personnelles absentes du Git. Les tests utilisent des données synthétiques et des serveurs loopback. Aucun export du téléphone envoyé à un serveur.
