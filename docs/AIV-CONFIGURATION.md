# Configuration universelle AIV — audit et première extraction

Base inspectée : branche `aiv-v37-v1.1.3`, commit `eedeb745a85809769b7d1ecbbe1201bdfc4675d1`, paquet `fr.erick.journallocal`, version 1.1.3, versionCode 113. La branche principale est différente (0.6.41) : elle n'est pas la base de cette extraction.

## Décisions d'architecture

« Valeurs » désigne ici les paramètres du programme et ses règles de décision. Une politique commune exprime les principes : décisions déterministes et explicables, permissions vérifiées, journalisation, séparation observation/action, absence d'attribution arbitraire d'un UID partagé. Elle ne donne pas les mêmes autorisations à tous les téléphones.

Le mini-Main existe déjà dans `MainEngine.java` : règles R1 à R6, versionnement des modifications dans SQLite, décisions et empreintes chaînées. Ses décisions portent `NOT_ENFORCED` : un verdict DENIED n'est pas un blocage réseau. Le nettoyage Shizuku est actuellement une voie distincte (`ShizukuCleanup` + `DefenseStore`). Il faudra raccorder ces voies à une politique commune avant d'affirmer que Main autorise toute action.

Ordre prévu : configuration commune validée → profil de distribution → réglages explicites de l'utilisateur dans les bornes communes → capacités effectivement disponibles sur le téléphone. Ce premier changement centralise les valeurs publiques de construction ; il n'ajoute pas encore des profils de distribution, un serveur ni une nouvelle interface de réglages.

## Inventaire et emplacement approprié

| Famille / sources inspectées | Valeurs ou décisions à regrouper | Propriétaire / emplacement | État |
|---|---|---|---|
| `WorkBudget` | 16 événements, 128 lignes de vérification, 64 lignes de statistiques, tranche 40 ms, pause minimale 1000 ms | Configuration technique commune, bornée | Centralisé |
| `AnomalyRules`, `AnomalyMonitor` | Fenêtre 300000 ms, groupe 900000 ms ; défaut 8 échecs et 10 Mio | Fenêtres communes ; seuils utilisateur initialisés depuis les défauts | Centralisé ; réglages existants conservés |
| `RecorderService`, `NetworkHealth` | Échantillonnage 60000 ms, trou d'observation 150000 ms, dérive horloge 5000 ms, inventaire 900000 ms | Configuration technique commune | Centralisé |
| `MainEngine` | Validité inventaire 86400000 ms, seuil par défaut 10485760 octets, configuration 32768 caractères, listes 100 entrées | Bornes communes ; seuil effectif par règle versionnée | Centralisé |
| `ReferenceSync` | Import 16777216 octets, 20000 apps, fiche 262144 caractères, 100 IP attendues | Bornes communes | Centralisé |
| `AnomalyMonitor` | Activer échecs/volume/DNS/collecte/surveillance/recherche, domaines surveillés, mode silencieux | Utilisateur, préférences `analysis` | Déjà modifiable ; pas migré |
| `MainEngine`, `aiv-schema.sql`, `CoherenceRules` | R1–R6, ALLOW/DENIED/WATCH, priorité, activation, seuil, domaines, liste autorisée | Mini-Main : validation commune + édition utilisateur explicite et versionnée | Existe ; unification avec défense à faire |
| `PermissionAudit`, `tools/penalty-model.js`, `tools/audit-rules.js`, `DefenseRules` | Niveaux L1–L5, provenance, droits accordés, comptages, couleurs, couverture inconnue, règles de capacités | Politique commune ; exceptions documentées de l'utilisateur | Audit identifié ; extraction ultérieure, sans réintroduire les anciens multiplicateurs |
| `DefenseActions`, `DefenseStore`, `ShizukuCleanup`, `MainActivity` | Nettoyage automatique, candidats admissibles, retrait/restauration des droits, exceptions, déclenchement au démarrage | Politique d'actions commune + choix utilisateur + capacité shell | Existant ; consentement durable et point de décision commun à formaliser |
| `ShizukuCleanup` | `pm revoke`, AppOps install/overlay/write-settings/usage/manage-storage ; identifiants validés ; retour shell ; snapshot | Adaptateur Android privilégié ; commandes permises fixes | Ne pas transformer en commandes libres fournies par config |
| `Continuous`, `RecorderService`, `MainActivity` | Marche/arrêt du journal, VPN, Bluetooth, reprise après démarrage, export/import | Utilisateur ; consentements Android quand nécessaires | Existant |
| `NetworkCaptureService`, code C du relais | MTU 1500, adresses locales TUN IPv4/IPv6, cadence des flux et limites réseau | Profil technique Android, compatible avec le relais natif | Pas migré ; ne pas changer une adresse Java sans vérifier le code C |
| `ReferenceCatalog`, `TrackerIndex`, catalogues embarqués | Bayton/AOSP, Exodus, révisions, sources et import local utilisateur | Référentiel public versionné ; valeurs locales séparées | Offline ; pas de service distant ajouté |
| `journal.html` | Palette, boutons bleus, taille des chiffres, cercles, rafraîchissement, pagination et état de calcul | Thème commun + préférences utilisateur | Encore dispersé dans CSS/JS ; extraction à faire |
| Stockages SQLite, `JournalSegments`, `SnapshotExporter`, `ExportFiles` | Taille segments, limites export, caches, rétention, chemins et schémas | Paramètres techniques ; choix utilisateur pour rétention/export | À inventorier finement avant migration des bases |
| Manifest/build/workflows | package/applicationId, autorités provider, min/target SDK, versionName/versionCode, ABI, versions SDK/NDK/Shizuku, noms d'artefacts | Profil de construction public | À unifier ; pas de renommage de paquet existant |
| Signature | P12, mot de passe de signature, éventuels tokens CI | Secrets du compilateur ; jamais config embarquée | Signature existante hors dépôt |
| Connexions éventuelles | URL publique, clé publique client, tokens utilisateurs ; clés serveur privilégiées | Paramètres publics / stockage protégé utilisateur / Secrets serveur selon catégorie | Aucun backend Supabase ajouté dans cette extraction |

Les nombres UID 1000, séparation des profils par 100000, appId 10000, codes de permission, protocoles, versions de schémas et algorithmes de hachage décrivent des contrats Android ou de données. Ce ne sont pas des boutons de configuration utilisateur. « Universel » signifie une politique transportable avec des adaptateurs ; Android/Shizuku restent spécifiques à Android.

## Permissions de l'app 1.1.3

| Déclaration / mécanisme | Besoin actuel | Interaction / Shizuku |
|---|---|---|
| INTERNET, ACCESS_NETWORK_STATE | Relais des connexions originales et observation réseau | Permissions normales ; pas de boîte de dialogue d'exécution |
| QUERY_ALL_PACKAGES | Inventaire large | Permission déclarée ; distribution Play à examiner séparément |
| RECEIVE_BOOT_COMPLETED | Reprise | Déclaration ; soumise aux contraintes de démarrage Android |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE | Collecteur, analyse et VPN au premier plan | Déclarations et notification de service |
| POST_NOTIFICATIONS | Affichage des notifications sur Android récent | Demande utilisateur existante ; vérifier l'état effectif |
| BLUETOOTH jusqu'à API 30, BLUETOOTH_CONNECT | Journal Bluetooth facultatif | Demande seulement si l'utilisateur active cette fonction |
| REQUEST_DELETE_PACKAGES | Demande de désinstallation via Android | Présentation à l'utilisateur ; ne pas assimiler à désinstallation silencieuse |
| Shizuku API_V23 | Retrait de droits et AppOps par identité shell | Autorisation Shizuku existante et vérification du binder ; privilèges ADB limités |
| BIND_VPN_SERVICE sur le service | Protège l'accès au service VPN | Ce n'est pas une permission runtime que l'app peut s'accorder ; `VpnService.prepare()` gère le consentement VPN |
| INTERACT_ACROSS_USERS_FULL sur le provider Shizuku | Protège l'accès au provider | Ce n'est pas une permission demandée à l'utilisateur pour donner tous les profils à AIV |
| Export/import par sélecteur de document | Fichier choisi | Pas de permission générale de stockage à ajouter pour ce parcours |

Le shell peut réaliser certaines opérations autorisées par Android sans multiplier les demandes de permissions de l'app. Il n'obtient pas automatiquement tous les droits signature/privileged et ne remplace pas l'autorisation Shizuku ou tous les consentements Android. En cas d'indisponibilité, conserver les lectures et afficher l'état plutôt que bloquer l'interface. Rien ici ne prouve la cause d'un arrêt précédent de Shizuku.

Sources : https://shizuku.rikka.app/guide/setup/ ; https://github.com/RikkaApps/Shizuku-API ; https://developer.android.com/develop/connectivity/vpn . L'inventaire des déclarations provient du manifeste de cette branche.

## Secrets et configuration

`config/defaults.json` est public et ne contient aucun identifiant. `tools/generate_config.py` refuse les sections/champs inconnus, booléens, valeurs hors bornes et intervalles incohérents. Il génère `AivConfig.java` avec une empreinte SHA-256. `build.py` exécute le générateur avant de compiler. Pour vérifier sans écrire : `python3 aiv-v19/tools/generate_config.py --check`.

Une valeur injectée depuis GitHub Secrets puis compilée dans Java, JS ou les assets est extractible de l'APK. Mettre seulement les vraies clés privées dans Secrets ne rend donc pas leur embarquement acceptable. Les réglages publics vont dans config ; P12/mot de passe restent accessibles uniquement à l'étape de signature ; les clés serveur restent au serveur. L'empreinte de config aide à identifier une configuration, elle n'est pas une signature d'authenticité.

## Vérification

Première extraction de 21 paramètres numériques publics et deux noms de chemins avec leurs valeurs actuelles. Les réglages utilisateur déjà enregistrés ont priorité sur leurs défauts initiaux. Aucune nouvelle autorisation, commande shell ou action de nettoyage n'est ajoutée.

Cinq tests de config réussis ; vérifications JS d'exposition, capacités, caches, audit, segmentation SQLite et fusion de référentiels réussies. Le lanceur complet s'arrête au compilateur Java absent dans l'environnement local. Le workflow `aiv-config.yml` ajoute compilation des moteurs Java purs et tests existants sur GitHub. Pas de test sur le téléphone ni d'APK compilé dans cette étape.

## Frontend, backend et sauvegardes — complément demandé

La source du frontend est maintenant dans `aiv-v19/frontend/` : template HTML, blocs CSS et scripts séparés. Le compilateur assemble la page locale embarquée. L'extraction restitue exactement la page existante, sans introduire de chargement externe ni de dépendance au réseau. Le backend local Android reste en Java, accessible par `JournalAndroid`. Une partie du scoring reste en JS : l'unification métier n'est pas encore réalisée.

| Couche | Responsabilité | Emplacement cible |
|---|---|---|
| Frontend | Design, navigation, affichage des résultats et états | APK ; sources `frontend/` |
| Moteur local | Journal SQLite, calcul déterministe, permissions, capture, export, queue de sauvegarde | APK, couche Java et adaptateurs Android |
| Service distant | Authentification, synchronisation autorisée, réception d'archives et gestion de parc | Serveur ; option Supabase selon le besoin |
| Données distantes | Index de segments et droits d'accès ; objets compressés/chiffrés dans stockage de fichiers | Base et stockage séparés ; pas de grosse archive dans une ligne SQL |
| GitHub | Sources, config publique, historique des builds | Dépôt de code ; aucune donnée brute du téléphone dans le dépôt public |

Constat sur les 50000 événements : `JournalSegments` indexe des tranches dans SQLite et conserve tous les événements dans la base originale. Ce code ne crée pas un fichier distant, ne capture pas d'image et ne libère pas la mémoire. Une capture d'écran du compteur n'est pas une sauvegarde des événements. L'export par `ExportFiles` crée un instantané temporaire dans le cache `exports`, puis copie vers l'URI choisie et vérifie la copie quand elle peut être relue. Le cache est temporaire.

Architecture proposée pour une vraie sauvegarde : segment fermé → export borné aux IDs du segment → nombre et SHA-256 vérifiés → compression/chiffrement → upload reprenable → accusé de stockage → essai de relecture/restauration. L'app peut collecter hors réseau ; une file locale attend le retour de la connexion. La rétention locale ne supprime des événements qu'après sauvegarde vérifiée selon la politique choisie. Pour une copie brute SQLite ouverte, utiliser un mécanisme de snapshot cohérent, jamais une simple copie du fichier sans traiter son journal WAL.

Le choix du stockage distant et de la rétention reste à fixer. Aucun upload, aucune suppression et aucune inspection de Supabase en production n'ont été faits dans cette étape. GitHub privé peut recevoir certains petits exports, mais un stockage d'objets est plus adapté aux journaux volumineux ; ne pas embarquer un token GitHub doté de droits d'écriture dans l'APK.

Chemins à réunir lors de la prochaine migration : asset du frontend, répertoire d'exports temporaires, sauvegardes persistantes, fichiers SQLite, snapshots de permissions, catalogues et destination distante. Les chemins Android sont à dériver de `Context`/URI, pas d'un chemin absolu propre au téléphone. Changer un nom de base existante exige une migration ; cette extraction n'en crée pas une nouvelle par inadvertance.

Deux noms de chemins sont désormais centralisés : cache d’exports et snapshot de nettoyage. Les mêmes constantes servent à écrire et relire les fichiers. Les noms sont validés sans sous-dossier ni chemin absolu. Les valeurs actuelles sont conservées ; changer le nom du snapshot existant demanderait de prévoir sa migration.

## Paliers d'accès

Décision utilisateur du 1er octobre 2026 : `tier=1` gratuit, `tier=2` payant, `tier=3` réservé sans offre définie. Le palier n'est pas l'identifiant d'un utilisateur. Le fichier public `config/access-policy.json` associe chaque service nommé à son `minimum_tier` (valeur dans `service_minimum_tiers`). Le palier 2 inclut les services de palier 1. Un service inconnu ou un palier non défini est refusé par le moteur de politique.

Les six fonctions locales actuelles sont initialisées à 1 pour conserver leur disponibilité. C'est une base de compatibilité, pas une tarification commerciale définitive. Aucun nouveau service payant n'est activé, aucun paiement n'est traité. Exemple futur : `"backup.remote": 2` donnerait accès à la sauvegarde distante pour un palier 2 vérifié ; il n'implémente pas la sauvegarde.

`AccessPolicy.java` est généré depuis le fichier public. `JournalAndroid.accessPolicy()` fournit le même catalogue au frontend. Le moteur `allows(service, verifiedTier)` est disponible pour les points d'entrée natifs/serveur à protéger ; les commandes actuelles ne lui sont pas encore raccordées. Le frontend peut annoncer les fonctions disponibles, mais l'autorisation effective doit être vérifiée au point d'exécution. Le choix d'une formule ne donne jamais une permission Android/Shizuku.

Le futur droit payant doit venir d'une preuve d'achat/abonnement vérifiée. Une préférence locale ou un nombre envoyé par le frontend n'est pas une preuve. Identité utilisateur, palier, permissions Android et choix personnels demeurent quatre notions distinctes.

Vérification ajoutée : cinq tests de validation/génération, plus un test Java couvrant gratuit/payant, héritage des fonctions gratuites, palier 3 réservé, palier invalide et services inconnus. La compilation/exécution Java se fait dans le workflow GitHub.

## Valeurs exactes actuellement centralisées

Les noms ci-dessous sont ceux de `AivConfig.java`, générés depuis `config/defaults.json`.

| Nom dans le code | Valeur | Correspondance |
|---|---:|---|
| `WORK_EVENTS` | `16` | Événements par lot coopératif |
| `WORK_VERIFY_ROWS` | `128` | Lignes de vérification par lot |
| `WORK_STATS_ROWS` | `64` | Lignes de statistiques par lot |
| `WORK_SLICE_MS` | `40` | Budget coopératif de temps, ms |
| `WORK_MIN_PAUSE_MS` | `1000` | Pause minimale entre travaux, ms |
| `ANOMALY_WINDOW_MS` | `300000` | Fenêtre d'analyse de 5 minutes |
| `ANOMALY_GROUP_MS` | `900000` | Regroupement d'analyse de 15 minutes |
| `ANOMALY_FAILURE_COUNT_DEFAULT` | `8` | Seuil initial d'échecs répétés |
| `ANOMALY_UPLOAD_MIB_DEFAULT` | `10` | Seuil initial de données envoyées, Mio |
| `COLLECTION_GAP_MS` | `150000` | Intervalle signalant un trou entre relevés, 150 secondes |
| `COLLECTION_CLOCK_DRIFT_MS` | `5000` | Écart entre temps civil et monotone, 5 secondes |
| `COLLECTION_INVENTORY_INTERVAL_MS` | `900000` | Intervalle des demandes d'inventaire, 15 minutes |
| `COLLECTION_HEARTBEAT_MS` | `60000` | Cadence des relevés du collecteur, 1 minute |
| `MAIN_INVENTORY_MAX_AGE_MS` | `86400000` | Âge maximal applicable de l'inventaire, 24 heures |
| `MAIN_THRESHOLD_BYTES_DEFAULT` | `10485760` | Seuil initial de règle, 10 Mio |
| `MAIN_CONFIG_MAX_CHARS` | `32768` | Taille maximale d'une configuration de règle en caractères |
| `MAIN_LIST_MAX_ITEMS` | `100` | Entrées maximales d'une liste de règle |
| `REFERENCE_IMPORT_MAX_BYTES` | `16777216` | Taille maximale d'import référentiel, 16 Mio |
| `REFERENCE_MAX_APPS` | `20000` | Apps maximales dans un import |
| `REFERENCE_APP_MAX_CHARS` | `262144` | Taille maximale d'une fiche d'app en caractères |
| `REFERENCE_MAX_EXPECTED_IPS` | `100` | Adresses attendues maximales par fiche |
| `PATHS_EXPORTS_CACHE` | `"exports"` | Nom du sous-dossier de cache privé des exports |
| `PATHS_CLEANUP_SNAPSHOT` | `"aiv-shizuku-cleanup-last.json"` | Nom du snapshot local de nettoyage |

`AivConfig.SHA256` est calculé automatiquement à partir de la configuration publique. Il sert à identifier sa version ; ce n'est ni un secret ni une preuve d'authenticité.

| Nom dans la politique d'accès | Valeur | Correspondance |
|---|---:|---|
| `TIER_FREE` | `1` | Palier gratuit |
| `TIER_PAID` | `2` | Palier payant |
| `TIER_RESERVED` | `3` | Réservé, aucun accès défini |
| `journal.read` | `1` | Lecture locale du journal |
| `journal.record` | `1` | Collecte locale |
| `network.capture` | `1` | Capture VPN locale, avec consentement Android |
| `permissions.audit` | `1` | Audit local des permissions |
| `analysis.local` | `1` | Analyse locale |
| `export.local` | `1` | Export local |

## Design et contrôle — 1er octobre 2026

Le logo fourni AIV est utilisé sans modification comme icône Android (`res/drawable/aiv_logo.png`), logo de l'en-tête et image d'initialisation. Le compilateur embarque les images dans la page locale sous forme data URI pour conserver la CSP et l'absence de chargement externe. Le thème natif et la WebView utilisent le fond `#04102f`. Les commandes d'observation gardent le bleu ; les actions de contrôle, l'onglet Shizuku et l'onglet Traqueurs utilisent l'orange. L'anneau d'initialisation tourne autour du logo avec respect de la préférence de mouvement réduit.

« Ouvrir le journal maintenant » ferme immédiatement l'écran d'initialisation, laisse le calcul en cours et conserve la navigation actuelle lorsque le résultat arrive. Il ne désactive ni le journal ni l'analyse. Les scores restent dépendants de leur état de calcul.

L'onglet Shizuku regroupe l'état du service, les contrôles existants et une norme proposée « météo ». L'utilisateur choisit explicitement une app et ce profil, examine les permissions concernées puis applique les retraits affichés. Le backend refait l'aperçu et refuse un état périmé. Seules les permissions runtime accordées et listées dans cette norme sont ciblées : téléphone, appels, caméra, micro, contacts. Localisation et Internet ne sont pas retirés par cette norme. Les droits non révocables par ce mécanisme sont signalés ; il n'y a pas d'effacement, de désinstallation ou de reset du téléphone. L'action utilise Shizuku, vérifie les résultats, écrit un snapshot et utilise la restauration existante. Ces normes sont configurables dans `assets/permission-baselines.json` ; ce sont des choix d'usage, pas une preuve automatique de malveillance. Le mécanisme ne traverse pas les profils Android.

Répartition choisie : lecture du journal, flux, audit et anomalies au palier 1 ; actions Shizuku au palier 2. Lecture des traqueurs maintenue à 1 dans l'attente d'une décision commerciale. `distribution_profile="personal"` conserve les actions de l'app personnelle au palier 2 ; `"public"` démarre au palier 1. Ce profil n'est pas une preuve d'achat. L'abonnement payant vérifié reste à implémenter avant distribution commerciale. Les points d'exécution des commandes Shizuku contrôlent désormais la politique de palier ; Shizuku doit en plus être réellement autorisé.

Tests ajoutés : navigateur avec données synthétiques (logo, fond, accès immédiat au journal, onglet dédié, aperçu sans action, refus des contrôles en mode gratuit) et build Android complet avec ressources. Aucune permission de téléphone réel n'est modifiée par les tests.

### Mise à jour : le palier 3 est TI

La décision suivante remplace le palier 3 réservé : `TIER_IT=3`, capacités `fleet.view`, `fleet.enroll`, `fleet.policy`, `fleet.report` de minimum 3. Les paliers 1 et 2 conservent leur sens. Le build personnel utilise désormais 3 pour permettre les essais des deux points de vue ; un build public démarre à 1 jusqu'au raccordement des droits vérifiés. Le bouton Mon parc présente un état vide explicite : aucun parc n'est encore enrôlé. L'architecture cible est décrite dans `AIV-ARCHITECTURE-PARC.md`.
