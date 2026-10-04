# Notes de build — AIV 2.0.6, journal corrigé

SDK 35, NDK r27d, ARM64/x86_64, minimum API 26. `-Xlint:all`, `-Wall`, `-Wextra` exposent les diagnostics. Aucun diagnostic n'est supprimé par une annotation ou un flag.

**49 diagnostics Java de dépréciation, 24 occurrences natives (12 diagnostics sur deux ABI).** Le cast redondant `PinVault` a été corrigé : le premier build exposait 50 diagnostics Java. Aucun diagnostic de compilation nouveau dans les classes du moteur ajoutées. La toolchain a nécessité la restauration de deux fichiers NDK tronqués lors de l'extraction depuis son archive vérifiée SHA-256.

B = bénin et expliqué. J = potentiellement lié au journal/capture, inspecté et soumis aux vérifications téléphone. Les API dépréciées compilent; cela ne prouve pas leur compatibilité future.

| Fichier : ligne | Diagnostic | Occurrences | Classe / justification |
|---|---|---:|---|
| `third_party/zdtun/zdtun.c:291` | unused parameter 'ctx' [-Wunused-parameter] | 2 | B — argument de callback ou variable de diagnostic désactivée par NO_DEBUG; aucun compteur/UID supprimé. |
| `third_party/zdtun/zdtun.c:529` | unused parameter 'tun' [-Wunused-parameter] | 2 | B — argument de callback ou variable de diagnostic désactivée par NO_DEBUG; aucun compteur/UID supprimé. |
| `third_party/zdtun/zdtun.c:537` | unused parameter 'tun' [-Wunused-parameter] | 2 | B — argument de callback ou variable de diagnostic désactivée par NO_DEBUG; aucun compteur/UID supprimé. |
| `third_party/zdtun/zdtun.c:568` | unused parameter 'tun' [-Wunused-parameter] | 2 | B — argument de callback ou variable de diagnostic désactivée par NO_DEBUG; aucun compteur/UID supprimé. |
| `third_party/zdtun/zdtun.c:624` | comparison of integers of different signs: 'int' and 'uint32_t' (aka 'unsigned int') [-Wsign-compare] | 2 | J — comparaison du relais inspectée; valeurs non négatives gardées avant comparaison, revue ci-dessous. |
| `third_party/zdtun/zdtun.c:857` | unused variable 'buf' [-Wunused-variable] | 2 | B — argument de callback ou variable de diagnostic désactivée par NO_DEBUG; aucun compteur/UID supprimé. |
| `third_party/zdtun/zdtun.c:1479` | unused variable 'data' [-Wunused-variable] | 2 | B — argument de callback ou variable de diagnostic désactivée par NO_DEBUG; aucun compteur/UID supprimé. |
| `third_party/zdtun/zdtun.c:1690` | comparison of integers of different signs: 'int' and 'unsigned long' [-Wsign-compare] | 2 | J — comparaison du relais inspectée; valeurs non négatives gardées avant comparaison, revue ci-dessous. |
| `third_party/zdtun/zdtun.c:1773` | comparison of integers of different signs: 'u_int32_t' (aka 'unsigned int') and 'int' [-Wsign-compare] | 2 | J — comparaison du relais inspectée; valeurs non négatives gardées avant comparaison, revue ci-dessous. |
| `third_party/zdtun/socks5.c:165` | comparison of integers of different signs: 'int' and 'unsigned long' [-Wsign-compare] | 2 | J — comparaison du relais inspectée; valeurs non négatives gardées avant comparaison, revue ci-dessous. |
| `third_party/zdtun/utils.c:243` | unused variable 'cmd_buf' [-Wunused-variable] | 2 | B — argument de callback ou variable de diagnostic désactivée par NO_DEBUG; aucun compteur/UID supprimé. |
| `third_party/zdtun/utils.c:279` | comparison of integers of different signs: 'int' and 'unsigned long' [-Wsign-compare] | 2 | J — comparaison du relais inspectée; valeurs non négatives gardées avant comparaison, revue ci-dessous. |
| `ApkEvidence.java:35` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `ApkEvidence.java:69` | [deprecation] signatures in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `AppIdentity.java:28` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `AppIdentity.java:48` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `DefenseActions.java:23` | [deprecation] ACTION_UNINSTALL_PACKAGE in Intent has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `DeveloperControl.java:51` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `DeveloperControl.java:59` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `DeviceIdentity.java:88` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `MainActivity.java:100` | [deprecation] setStatusBarColor(int) in Window has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `MainActivity.java:101` | [deprecation] setNavigationBarColor(int) in Window has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetLeft() in WindowInsets has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetTop() in WindowInsets has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetRight() in WindowInsets has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `MainActivity.java:124` | [deprecation] getSystemWindowInsetBottom() in WindowInsets has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `NetworkCaptureService.java:113` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | 1 | J — sélection du réseau physique hors VPN; transition réseau à vérifier sur Android. |
| `PinVault.java:26` | [deprecation] isInsideSecureHardware() in KeyInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `NetworkHealth.java:15` | [deprecation] getAllNetworks() in ConnectivityManager has been deprecated | 1 | J — sélection du réseau physique hors VPN; transition réseau à vérifier sur Android. |
| `PermissionAudit.java:49` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionAudit.java:55` | [deprecation] protectionLevel in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionAudit.java:56` | [deprecation] protectionLevel in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionAudit.java:79` | [deprecation] getInstallerPackageName(String) in PackageManager has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionAudit.java:104` | [deprecation] protectionLevel in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionAudit.java:111` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionAudit.java:122` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionControl.java:242` | [deprecation] GET_SIGNATURES in PackageManager has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionControl.java:252` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionControl.java:288` | [deprecation] protectionLevel in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionControl.java:290` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionControl.java:312` | [deprecation] protectionLevel in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionControl.java:337` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionNorms.java:21` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionNorms.java:24` | [deprecation] protectionLevel in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `PermissionNorms.java:24` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `RecorderService.java:69` | [deprecation] addAction(int,CharSequence,PendingIntent) in Builder has been deprecated | 1 | J — états Android du collecteur; réception réelle à vérifier, hors résolution des UID réseau. |
| `RecorderService.java:94` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | 1 | J — états Android du collecteur; réception réelle à vérifier, hors résolution des UID réseau. |
| `RecorderService.java:94` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | 1 | J — états Android du collecteur; réception réelle à vérifier, hors résolution des UID réseau. |
| `RecorderService.java:112` | [deprecation] ACTION_DEVICE_STORAGE_LOW in Intent has been deprecated | 1 | J — états Android du collecteur; réception réelle à vérifier, hors résolution des UID réseau. |
| `RecorderService.java:113` | [deprecation] ACTION_DEVICE_STORAGE_OK in Intent has been deprecated | 1 | J — états Android du collecteur; réception réelle à vérifier, hors résolution des UID réseau. |
| `RecorderService.java:116` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | 1 | J — états Android du collecteur; réception réelle à vérifier, hors résolution des UID réseau. |
| `RecorderService.java:166` | [deprecation] <T>getParcelableExtra(String) in Intent has been deprecated | 1 | J — états Android du collecteur; réception réelle à vérifier, hors résolution des UID réseau. |
| `ScreenIntegrityService.java:90` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `ScreenIntegrityService.java:242` | [deprecation] obtain(AccessibilityNodeInfo) in AccessibilityNodeInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `ScreenIntegrityService.java:266` | [deprecation] recycle() in AccessibilityNodeInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `SecurityContext.java:13` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `ShizukuCleanup.java:362` | [deprecation] protectionLevel in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `ShizukuCleanup.java:362` | [deprecation] PROTECTION_MASK_BASE in PermissionInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `SpecialAccess.java:92` | [deprecation] checkOpNoThrow(String,int,String) in AppOpsManager has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `TrackerIndex.java:251` | [deprecation] versionCode in PackageInfo has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |
| `WatcherService.java:69` | [deprecation] stopForeground(boolean) in Service has been deprecated | 1 | B — API Android existante; justification par famille ci-dessous. |

## Justifications des familles Java

- `versionCode`, `GET_SIGNATURES`, `signatures`, `isInsideSecureHardware` : compatibilité API 26/27; voies modernes gardées par la version Android. Une erreur d'identité cryptographique ne peut désormais plus effacer l'UID observé.
- `PermissionInfo.protectionLevel`, `PROTECTION_MASK_BASE`, AppOps et installateur : métadonnées des permissions existantes; ces dépréciations ne constituent pas un échec du lookup connexion → UID.
- `Window`/`WindowInsets`, `AccessibilityNodeInfo.obtain/recycle` : interface et intégrité existantes, hors capture/compteurs.
- notifications, stockage, `getParcelableExtra`, `stopForeground(boolean)` : chemins de services existants; contraintes réelles d'Android à contrôler dans le protocole téléphone.
- `getAllNetworks` : sélection actuelle d'un réseau physique lorsque le réseau actif est le VPN. La transition Wi-Fi/cellulaire et la reprise nécessitent un essai réel.

## Revue des cinq comparaisons natives

- `zdtun.c:624` : disponible dans le buffer limité à `max(disponible,0)` avant comparaison avec la fenêtre TCP non signée; aucun compteur de volume dans cette conversion.
- `zdtun.c:1690` : erreur `recv` traitée avant comparaison de longueur ICMP, donc tailles non négatives.
- `zdtun.c:1773` : erreur/fermeture `recv` traitées avant comparaison de longueur TCP à la fenêtre.
- `socks5.c:165` : validation d'une réponse du chemin SOCKS existant, non activé par cette livraison.
- `utils.c:279` : `rv < 0` est évalué avant comparaison avec `sizeof`; la seconde branche compare une longueur non négative.

Le code tiers reste celui du snapshot natif, avec ces diagnostics exposés et documentés. L'essai natif exerce UDP/DNS/SNI et les volumes/fermeture; aucun test exhaustif de TCP sur Android ou de consommation longue durée n'est revendiqué.

## Toutes les autres annotations / limites de validation

- Test host C : glibc signale aussi le retour de `system()` ignoré, `utils.c:283`. Fonction de diagnostic non appelée par le test ni par le relais AIV; aucun contrôle Android exécuté.
- LeakSanitizer : lecture de `/proc/.../task` refusée dans ce conteneur. `detect_leaks=0` est explicite dans le test; ASan et UBSan restent actifs. Aucune conclusion sur les fuites mémoire.
- Pilote JDBC : logger SLF4J absent, mode NOP annoncé; SQLite est réellement utilisé. Ces dépendances/stubs de tests ne sont pas inclus dans l'APK.
- Python : 12 tests applicables passent. `test_verdicts.py` déclaré SKIP avec son motif : générateur retiré depuis V21, déjà documenté dans `RELEASE-0.6.22.md`. Les règles natives passent leur suite Java.
- Tentative `design-control.cjs` : bloquée par Chromium Playwright absent. `design-control`, `developer-control`, `reader-v22` et `reader-v23` concernent le HTML historique exclu de l'APK. Ils ne valident pas l'interface native et ne sont pas annoncés comme passés.
- Aucun téléphone/émulateur connecté. Installation, conservation effective du journal existant, consentement VPN, dialogues SAF, transitions réseau et taux réel d'attribution à mesurer sur le téléphone.
- Les exports et le journal sont locaux. Les jeux des tests sont synthétiques. Aucune capture personnelle ni clé de signature n'est publiée dans cette branche.
