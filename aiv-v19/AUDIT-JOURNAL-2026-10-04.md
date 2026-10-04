# All In Visible — audit de régression du journal

Date : 4 octobre 2026. Dépôt : willingemy-byte/My-Friend-Qjan.
Objet : établir une base factuelle pour CAPTURE → ATTRIBUTION → VOLUME → TRAJETS → EXPORT.

## Décision et limites de preuve

La référence de reconstruction recommandée est **abf2f4d7d1760d0d0da6c8a7d877330d65979cf4**, « Build and verify AIV 0.6.35 tracker trails », version 0.6.35, code 41. Elle réunit le relais natif, les compteurs cumulés, l’indexation de tous les événements de connexion pour les étapes des trajets et les exports vérifiables. La compilation Android complète a été reproduite dans cet audit.

Ce choix est un choix de source contrôlée, pas la preuve que la 0.6.35 aurait le meilleur taux d’attribution sur le téléphone. Les moteurs de 0.6.36 et 0.6.37 sont identiques sur les fichiers centraux comparés. Le dernier état réellement utile sur le téléphone ne peut pas être daté à partir des seuls commits. Aucun export de trafic des anciennes sessions, notamment des trajets Chrome évoqués, n’a été fourni pour établir cette comparaison quantitative.

Deux ruptures historiques sont confirmées : le changement de package Android isole les données de l’ancienne application, puis la première interface native retire les parcours de détail des trajets et les commandes d’export. Le relais C, EventStore et TrackerIndex n’ont pas été remplacés à cette transition. L’hypothèse d’identification trop précoce est reproductible dans le code, mais existait déjà en 0.6.35 : elle ne prouve donc pas, seule, la régression temporelle.

L’audit comprend les sources, des compilations, les journaux CI, les annotations disponibles, les tests existants et des expériences déterministes. Aucun téléphone n’a été piloté. Aucun taux réel d’attribution, volume réel ou nombre de trajets réel n’est présenté comme mesuré. Les observations réseau ne sont pas interprétées comme une fraude, une attaque ou une intention.

## 1. Chronologie des versions pertinentes

Dates exprimées en America/Toronto. Les SHA complets sont donnés en annexe de provenance.

| Repère | Version / code | Date locale | Commit | Observation technique |
| --- | --- | --- | --- | --- |
| Avant la référence | 0.6.34 / 40 | 27 sept. 21:08 | e365d8b5 | Interface d’investigation et chronologie; moteur de capture déjà identique à 0.6.35. |
| B — référence candidate | 0.6.35 / 41 | 28 sept. 02:09 | abf2f4d7 | Indexation des étapes depuis EventStore, groupes → sessions → trajets; CI avec tests. |
| Comparaison suivante | 0.6.36 / 42 | 28 sept. 14:38 | 1862d7f5 | Mêmes capture, EventStore, TrackerIndex et relais; différences principalement hors moteur. |
| Comparaison suivante | 0.6.37 / 43 | 28 sept. 22:58 | 4e475d15 | Même cœur; ajout d’outillage sans gain d’attribution démontré. |
| C — dernière WebView identifiée | 1.1.5 / 115 | 1 oct. 20:48 | 6dd779ba | Détails des trajets et exports toujours exposés par le pont Android. |
| Rupture de continuité | 1.2.0 / 120 | 2 oct. 14:00:24 | dbfb776c | fr.erick.journallocal → com.allinvisible.aiv : nouvelle application, autre espace de données. |
| D — première native | 1.2.0 / 120 | 2 oct. 14:00:27 | ba4469bb | Remplacement de MainActivity; retrait des commandes d’export et des parcours détaillés de trajets. |
| Suite native | manifeste 1.2.0 / 120 | 2 oct. 17:33 | a786bd6d | Branche appelée 1.2.1, mais manifeste encore 1.2.0; nom de branche insuffisant pour identifier le build. |
| E — APK 2.0.0 fourni | 2.0.0 / 200 | 2 oct. 20:29 | 36a6d08a | Journal natif limité aux 150 derniers événements; détails et volumes peu exposés. |
| Mode de capture modifié | 2.0.2 / 202 | 3 oct. 19:03 | 60c7e807 | VPN désactivé par défaut : collecteur général actif ne signifie pas capture de paquets active. |
| Dernier état source examiné | 2.0.6 / 206 | 3 oct. 23:05 | 7a86f7d2 | Cœur C/EventStore/TrackerIndex toujours conservé; exports réintroduits après 2.0.0, volumes mieux présentés en 2.0.4. Aucun run de build trouvé pour ce SHA. |

A — « dernière version réellement utile » reste un repère de terrain non établi. Les 0.6.35, 0.6.36 et 0.6.37 sont des candidats comparables sur le cœur. Les sources ne prouvent pas que la dernière WebView était moins capable de capturer. On distingue la continuité du moteur, la continuité des données et l’accès dans l’interface.

## 2. Pourquoi choisir abf2f4d7 comme baseline

La 0.6.35 introduit la liaison systématique EventStore.add → TrackerIndex.request et les étapes nécessaires aux trajets. La 0.6.34 n’est donc pas aussi complète pour cette référence. Les versions suivantes immédiates ne modifient pas ces mécanismes et apportent des changements sans rapport avec la priorité actuelle. Partir de ce commit évite de récupérer les couches commerciales et de contrôle de 2.x.

La CI historique 36385029315 est réussie avec compilation et run-v23. Une nouvelle compilation locale SDK 35 / build-tools 35 / NDK 27.3.13750724 réussit. Les dépendances téléchargées ont été vérifiées par les SHA prévus. Les tests de snapshot et d’export vérifient des contrats utiles.

Cette baseline comporte déjà des défauts : retries UID plafonnés, aucune relance package après obtention d’un UID, volumes d’étapes absents convertis en zéro, métadonnée de version d’export ancienne et tests d’interface obsolètes ou défaillants. Elle doit être stabilisée avant d’être qualifiée comme produit. Elle sert de référence du comportement et du schéma; elle ne signifie pas un retour commercial à la WebView ni l’activation de la capture sur un appareil.

## 3. Comparaison technique des cinq éléments

| Élément | Référence 0.6.35 / dernière WebView | Première native / 2.0.0 | Sources 2.0.6 |
| --- | --- | --- | --- |
| Capture | VpnService, relais C, TCP/UDP, requêtes DNS UDP, début TLS/TCP. | Même relais et parseurs. Pas de suppression de moteur prouvée. | Même relais; capture VPN devenue option désactivée par défaut. |
| Attribution | API Android propriétaire du socket; trois tentatives, UID partagé explicite. | Même limite; ajout ultérieur d’identité/signature sur le chemin d’enrichissement. | Pas de file de retry temporisée identifiée. |
| Volume | Compteurs cumulés indépendants de l’UID; résumé toutes les 5 s ou fermeture. | Données disponibles dans les résumés; 2.0.0 les expose mal dans le journal. | Présentation des volumes améliorée; absences encore normalisées à zéro dans certaines projections. |
| Trajets | Groupes, sessions, étapes et exports accessibles. | Index conservé, parcours groupes → sessions → étapes retiré de l’interface initiale. | Le stockage reste disponible; parité détaillée à reconstruire et tester. |
| Export | JSON/JSONL paginés, snapshot fixé, hash; fichiers préparés puis vérifiés. | Commandes d’export retirées à la première native, absentes de 2.0.0. | JSON/JSONL réintroduits après 2.0.0; métadonnée application_version encore figée à 1.2.0. |

Comparaison par empreintes : EventStore.java est identique de 0.6.35 à 2.0.6; TrackerIndex.java également. relay.c est identique de 0.6.34 à 2.0.6. Les sources C de l’application et du relais tiers ne présentent pas de différence entre la baseline et les états 2.x comparés. aiv-schema.sql est également inchangé. Cela exclut une réécriture de ces fichiers comme explication directe du passage WebView → natif, sans exclure les effets du cycle de vie, du mode de capture ou du téléphone.

## 4. Causes confirmées, reproduites et encore hypothétiques

### 4.1 Régression confirmée de restitution et d’accès

Au commit ba4469bb, MainActivity remplace les ponts trackerGroups/trackerJourneys/trackerTrail et les commandes d’export par des vues natives simplifiées. Les données de l’index peuvent encore exister sans être consultables par l’utilisateur. En 2.0.0, renderJournal lit 150 lignes, renderTrackers affiche des groupes sans parcours complet, et les vues de flux ont des limites sans pagination exploitée. Des volumes présents dans details ne sont pas présentés avec leur contexte. Ce sont des régressions de visibilité prouvées par les différences de source.

Les projections flowPage lisent un ensemble borné d’événements et reconstruisent des flux : un filtre appliqué avant regroupement peut produire une vue partielle. Certains états sont réécrits pendant une lecture descendante; leur cohérence « dernière observation » doit être testée. EventStore.page n’est pas limité par défaut à un segment de 50 000 lignes : cette limite appartient à d’autres parcours et ne doit pas être citée comme cause sans préciser l’API.

### 4.2 Historique séparé par le nouveau package — confirmé

dbfb776c crée com.allinvisible.aiv, distinct de fr.erick.journallocal. Android attribue un nouvel espace privé à cette application; elle ne récupère pas automatiquement journal.sqlite de l’ancienne. Le journal vide ou moins riche peut donc correspondre à une nouvelle collecte plutôt qu’à une destruction de l’ancien journal. Le changement de package n’est pas une migration SQLite. Aucune migration destructrice du journal n’a été trouvée au changement d’interface : SQLiteOpenHelper reste en version 1 et refuse les upgrades non prévus pour préserver les données.

### 4.3 Hypothèse UID tardif — défaut reproduit, impact terrain inconnu

identify(Flow) sort si uid >= 0 ou lookupAttempts >= 3. Chaque rappel de connexion ou de paquet peut consommer une tentative immédiatement. Il n’y a pas de délai imposé ni de file d’enrichissement. onFlowOpen enregistre déjà un événement avant les résumés suivants. Les premières lignes restent immuables; aucune mise à jour de leur attribution n’est effectuée.

L’expérience compile le corps réel de identify de cinq snapshots avec des services Android simulés. Android est configuré pour rendre l’UID disponible à 50 ms. Les appels à 0, 1 et 2 ms renvoient -1. L’appel à 50 ms ne consulte plus l’API : le flux reste à -1 avec trois appels consommés. Résultat identique en 0.6.35, 1.1.5, première native, 2.0.0 et 2.0.6.

Deuxième expérience : UID connu immédiatement, liste de packages indisponible puis disponible à 50 ms. La seconde identification est abandonnée parce que uid >= 0; le package reste absent. Une troisième expérience confirme qu’un UID partagé par deux packages n’est pas transformé en attribution certaine à l’un d’eux.

Ces tests prouvent des chemins de défaillance du code. Ils ne mesurent pas la latence réelle de getConnectionOwnerUid sur le téléphone et ne démontrent pas que la migration native les a aggravés. Pour établir la cause terrain, mesurer les instants et les résultats de chaque appel, les états de socket et les erreurs avant/après sur le même appareil.

### 4.4 UNKNOWN classé Android — ambiguïté confirmée

Flow commence avec uid=-1 mais journalGroup="android". L’interface peut ainsi ranger des inconnus parmi les événements Android alors qu’aucun propriétaire n’a été identifié. Le fallback « Android · UID » est justifié pour un UID système réservé ou partagé; il ne désigne pas un package unique. Un UID applicatif dont la liste de packages est invisible doit rester explicitement inconnu, tout en conservant l’UID connu.

Un catch général remet actuellement uid=-1 même si l’exception survient après l’obtention d’un UID, par exemple pendant l’enrichissement. Les données de propriétaire et les erreurs de package/label/signature doivent être dissociées. Le code ne doit jamais masquer un UID partagé ni inférer un processus/service à partir d’une simple proximité temporelle.

### 4.5 Volume absent, zéro et cumul — défauts de représentation confirmés

Le relais compte pkt->len : octets IP comprenant les en-têtes réseau et transport, paquets/retransmissions inclus. Ce ne sont ni des octets de contenu TLS ni une facture opérateur. Les compteurs TX/RX ne dépendent pas de l’identification du package. En revanche, les événements d’ouverture et DNS/TLS ne portent pas tous un résumé de compteurs; le premier paquet porte first_packet_bytes.

TrackerIndex.storeStep utilise optLong sur les champs absents; son schéma les transforme en 0. La projection mélange donc « mesure absente » et « mesure égale à zéro ». Les résumés cumulés doivent être pris une seule fois par connexion, avec le dernier compteur valide ou MAX, puis additionnés entre connexions distinctes. Les groupes de trackers peuvent référencer une même connexion : leur somme n’est pas le total du téléphone.

Les résumés sont produits toutes les 5 secondes ou à la fermeture. Un arrêt brutal peut perdre les derniers compteurs non persistés. Les observations initiales ne conservent pas tous les paquets bruts ni leurs volumes individuels; certains paquets rejetés/fragmentés ne sont représentés que par un compteur de problèmes. Il est impossible de promettre une couverture complète par paquet ou des volumes exacts par sous-période à partir de ces seuls résumés.

### 4.6 DNS, noms et trajets — portée historique limitée

Le parseur voit les questions DNS UDP en clair sur le port 53. Il ne conserve pas les réponses A/AAAA/CNAME/TTL pour relier réellement un nom à l’IP d’une autre connexion. DNS sur TCP, DoH, DoT et les noms QUIC ne sont pas couverts de cette manière. Le TLS est limité au début de ClientHello TCP et à 32 Kio; ECH peut ne laisser qu’un nom externe. Une absence de domaine ne prouve pas une absence de trafic.

Les flows de TrackerIndex sont centrés sur des correspondances avec le catalogue de trackers; ils ne comptent pas toutes les connexions réseau. Les steps peuvent conserver d’autres étapes d’un même flux, mais la liste des trackers ne constitue pas le journal réseau global. Un trajet ordonné par événements représente une chronologie locale; il ne prouve pas une chaîne de redirections serveur ni la destination suivante de l’application. Le parcours est limité à 250 étapes et doit conserver le signal truncated.

### 4.7 Capture inactive et nouveaux coûts — pistes à mesurer

Depuis 60c7e807, le VPN est désactivé par défaut. Un état « collecteur actif » peut concerner les événements Android généraux sans paquets réseau. Les modes doivent être explicités dans les métriques; une session VPN inactive ne peut pas fournir une mesure d’attribution du trafic.

Les appels AppIdentity/DeviceIdentity ajoutés en 2.x exécutent de l’enrichissement et de la signature sur le chemin des callbacks. Leur coût et leurs exceptions peuvent retarder le traitement. Aucune mesure de charge ne permet ici de les qualifier comme cause principale. Le traitement des flows est actuellement confiné au thread natif; une future file asynchrone doit éviter les accès concurrents, la réutilisation des tuples de socket et l’enrichissement après fermeture à partir d’un autre propriétaire.

Les erreurs Shizuku présentes dans le fichier de contrôle ne prouvent pas une erreur de résolution réseau. Elles ne sont pas utilisées pour attribuer la régression du journal. Aucun travail de contrôle du téléphone n’est entrepris dans cette phase.

## 5. Fichiers et méthodes précis

Les chemins Java ci-dessous sont sous aiv-v19/app/src/main/java/fr/erick/journallocal/.

| Fichier | Zones à traiter | Risque ou rôle |
| --- | --- | --- |
| NetworkCaptureService.java | Flow, identify, details, onFlowOpen/Direction/Update, onDnsQuestion, onTlsHello | UID/package tardifs; classification inconnue; snapshots précoces; erreurs distinctes; diagnostics de retry. |
| app/src/main/cpp/relay.c | accounting callbacks, boucle run/poll, report_one, report_at | Compteurs IP, cadence 5 s, fermeture, couverture de paquets et horloge. |
| app/src/main/cpp/tls_sni.c | réassemblage ClientHello et limites | SNI observé/externe, buffers, retransmissions, ECH; pas de contenu déchiffré. |
| third_party/zdtun/zdtun.c, socks5.c, utils.c | débit, fenêtres TCP, bornes de longueurs | Diagnostics C à expliquer; pas de mise à jour aveugle du vendor. |
| EventStore.java | add, page, export snapshot, SQLiteOpenHelper | Observation immuable, erreurs d’écriture, déclenchement de l’index; migrations préservées. |
| TrackerIndex.java | storeStep, process, groups, journeys, trail, export | Absence/0, index trackers vs connexions globales, agrégation, limites de lecture. |
| MainActivity.java | ancien pont; renderJournal, renderTrackers, renderFlows, flowPage | Retrait de restitution, bornes et pagination, tri et cohérence de la projection. |
| SecurityContext.java, AppIdentity.java, DeviceIdentity.java | UID/package/version/signature et erreurs | Enrichissement indépendant, cache borné avec invalidation, coût mesuré. |
| SnapshotExporter.java, ExportFiles.java, JournalRecovery.java | write, stage/copy/verify, recover | Export récupérable, version/build exacts, hash, troncature et erreur disque. |
| AivStore.java, assets/aiv-schema.sql | stockage complémentaire et schémas | Version de schéma, migrations transactionnelles; ne pas confondre avec journal.sqlite. |
| Continuous.java, NetworkHealth.java, RecorderService.java | mode de capture, disponibilité réseau, arrêts/stockage | Capture active vs collecte générale; trous de couverture explicités. |
| build.py, workflows GitHub, tests/ | lint, runners, invariants, fixtures | Diagnostics masqués; tests obsolètes; preuve de build différente de preuve terrain. |

## 6. Builds, tests et notes

| Vérification | 0.6.35 | 2.0.0 |
| --- | --- | --- |
| CI historique | Succès, run 36385029315; run-v23 exécuté. | Succès, run 37082292023; compilation et greps d’invariants, sans suite de régression exécutée dans ce workflow. |
| Compilation locale | Build complet natif arm64/x86_64 + Java + APK non signé : succès. | Compilation Java des sources engagées : succès. Le builder n’a pas été exécuté pour éviter de régénérer silencieusement une politique différente du commit. |
| Diagnostic Java -Xlint:all | 31 warnings / 44 fichiers Java. | 42 warnings / 56 fichiers Java. |
| Diagnostic C -Wall -Wextra | 12 warnings; sources communes aux états comparés. | Les mêmes sources sont inchangées; le diagnostic commun s’applique. |
| run-v23, incluant run-v22 | Réussite : JS, audit, segmentation, référence Python, Java tracker/DEX et règles. | Réussite également. Ne teste pas les délais UID ni un VPN sur téléphone. |
| Snapshot natif synthétique | Réussite de run-v23-snapshot. | Règles Java AccessPolicy et ControlRules : réussite; aucune action Android exécutée. |
| Découverte tests Python | Erreur d’import de test_verdicts.py : chemin tools/generate_verdicts.py absent. | 11 réussites, 1 échec de cohérence de politique générée, 1 erreur du test historique. |
| Parcours UI reader-v22 | Échec : bouton Application exemple attendu par le test absent de la vue. | Même échec sur l’ancien HTML conservé; ne valide pas l’interface native. |
| Parcours UI reader-v23 | Échec : bouton de ménage présent mais invisible au clic. | Même échec sur l’ancien HTML conservé; ne valide pas le natif. |
| Identification tardive simulée | Deux défauts reproduits : cap trois appels et package non relancé. | Résultats identiques; vérifiés aussi en 1.1.5 et 2.0.6. |
| Export réel avec source synthétique | JSON et JSONL : événements à IDs non contigus, UID inconnu et volume conservés, hash vérifié; troncature explicitement incomplète. | Backend d’export de même contrat; accès dans 2.0.0 absent et métadonnée obsolète. |

test_verdicts.py est déjà décrit comme hors livraison dans RELEASE-0.6.22.md : son erreur est expliquée, elle ne devient pas une réussite. Les échecs UI restent ouverts; il faut distinguer dérive des fixtures et régression de parcours sans contourner la visibilité du bouton. Le test de politique 2.0.0 constate que AccessPolicy.java engagé ne correspond pas à la politique source; exécuter le générateur pendant le build peut masquer cette divergence. Ces éléments empêchent de déclarer « tous les tests passent ».

Le build original utilise -Xlint:-deprecation; les logs CI ne montrent que deux notes générales demandant de recompiler avec les diagnostics. La classification détaillée de chaque warning Java/C est donnée dans WARNINGS-JOURNAL-2026-10-04.md. Les API obsolètes ne sont pas toutes une erreur : plusieurs branches sont nécessaires à Android 26–28. Chaque occurrence est expliquée, et les warnings touchant le réseau, les longueurs ou le stockage restent à vérifier.

Les deux checks CI ont chacun une annotation warning : actions/checkout@v4, actions/cache@v4 et actions/upload-artifact@v4 ciblent Node 20 mais sont forcées sur Node 24. À corriger en choisissant des versions compatibles vérifiées; aucune causalité avec les UID de l’APK. Les logs signalent aussi DEP0040 punycode, DEP0169 url.parse, et le hint Git sur master : notes d’outillage expliquées, pas de mesure réseau. Le cache de 2.0.0 n’a pas pu réserver sa clé, car un autre job pouvait la créer : effet attendu sur la vitesse du build, pas sur les octets compilés. Le check Supabase ignoré est hors périmètre et ne prouve pas un défaut Android.

L’environnement local avait initialement des exécutables NDK extraits incomplets et sans droits d’exécution. Leur taille a été comparée aux archives vérifiées, les membres ont été réextraits intégralement, puis le build a réussi. Ces erreurs d’environnement ne sont pas imputées au produit. L’APK local n’est pas signé et n’a pas été installé. L’égalité binaire avec un ancien build n’est pas démontrée.

## 7. Reconstruction par étapes courtes

| Étape | Changement borné | Preuve requise avant la suite |
| --- | --- | --- |
| 0 — provenance et vérité | Branche créée uniquement depuis abf2f4d7; audit, inventaire des warnings; diagnostics visibles; inconnus et absence de compteurs explicites. | Build frais, lint classé, contrôles existants, fixtures UID/package/volume; aucun mélange de branches. |
| 1 — capture durable | Observation originale immuable avec ID/session, heures UTC et monotone, protocole, tuple IP/ports, direction, taille et compteurs valides séparés. Retenir les erreurs/rejets et trous de couverture. | Rejouer TCP/UDP IPv4/IPv6/ICMP et rejets; octets/paquets attendus connus; arrêt brutal et stockage plein. |
| 2 — enrichissement et retry | Résolution UID séparée du package/label. File bornée, backoff 25/100/300/750/1500/3000 ms, budget initial à calibrer ≤10 s; erreurs typées et état terminal explicite. Append d’enrichissement référencé à l’observation; projection actualisée sans modifier la preuve originale. | UID tardifs de 1 à 3000 ms, package tardif, SecurityException, UID partagé/réservé, profils, fermeture et tuple réutilisé; zéro fausse attribution. |
| 3 — volumes fiables | Compteurs complets même UNKNOWN; null ≠ 0; delta entre snapshots avec reset/session détectés; agrégation par connexion distincte, app, destination et période avec portée expliquée. | Comparaison aux fixtures IP exactes, retransmissions, RX=0 réel, snapshots doublés, flux multi-trackers, arrêt/redémarrage; pas de double comptage. |
| 4 — relations démontrables | Index réseau global séparé des correspondances trackers; réponses DNS A/AAAA/CNAME/TTL corrélées dans le même contexte, SNI/ECH explicites; trajets paginés avec type et confiance par lien. | DNS cache/TTL, IP partagée, multiples noms, réponses absentes, ECH/QUIC, simultanéité sans causalité, >250 étapes; aucun chemin inventé. |
| 5 — export autonome | JSONL/JSON, CSV dérivé, snapshot SQLite cohérent avec WAL; schémas, build/SHA/version exacts, période, compteurs, manifest et SHA-256 fichier complet. Export récupérable même si l’UI échoue. | Append concurrent pendant snapshot, IDs non contigus, interruption, espace plein, export modifié/tronqué; intégrité SQLite et hash; aucune transmission serveur. |
| 6 — restitution native | Réexposer événements/détails, connexions, volumes et trajets enrichis avec pagination, inconnus et niveaux de confiance; conserver le moteur validé. | Même fixture capture → stockage → export → UI; nombres concordants; parcours sur appareil et rapport de 5 minutes. |

La file d’enrichissement doit rester sur un propriétaire de données unique ou transférer des résultats immuables à ce propriétaire. Les caches UID/package doivent tenir compte du profil, de la version d’inventaire, des installations/retraits et des UID partagés. Un UID trouvé après disparition/réutilisation de la connexion ne peut pas être appliqué aveuglément à un ancien paquet. Une erreur de label ou de signature ne doit pas effacer le propriétaire déjà obtenu.

Niveaux de preuve par champ et par lien : OBSERVÉ pour le tuple, paquet ou nom réellement lu; ATTRIBUÉ pour une identité déterminée par une méthode documentée; CORRÉLÉ pour une association appuyée par les observations et sa portée; INCONNU lorsque la donnée manque. Un score global ne remplace pas ces distinctions. PID, processus ou service restent inconnus si aucune source ne permet de les déterminer.

## 8. Protocole reproductible de cinq minutes et métriques

Préparer deux sessions comparables sans effacer l’ancien journal : relever version/build, SHA de l’APK, Android/appareil/profil, mode VPN et réseau, options DNS, heure UTC/monotone, ID de session, compteur initial et intervalle. Le test de trafic nécessite une capture VPN explicitement activée par l’utilisateur; une session VPN désactivé sert de témoin distinct. Les clics dans une application ne prouvent pas qu’elle a émis des paquets. Aucun serveur ne reçoit les exports sans consentement explicite.

| Temps | Action manuelle | Contrôle attendu |
| --- | --- | --- |
| 0:00–0:30 | Ouvrir AIV et vérifier l’état exact de capture. | État du VPN, réseau physique, session et éventuelles interruptions. |
| 0:30–2:00 | Chrome : example.com, wikipedia.org, developer.android.com. | Flux de l’UID Chrome si réellement présents; DNS/SNI disponibles ou explicitement absents; RX/TX. |
| 2:00–3:00 | Ouvrir ChatGPT et faire une action reproduite. | Flux observés, source déterminée ou inconnue; aucun contenu TLS inventé. |
| 3:00–3:30 | Ouvrir Google Play et une fiche d’application. | Connexions visibles et événements Android distincts. |
| 3:30–4:00 | Ouvrir Photos et faire la même navigation. | Attribution propre au profil; trafic absent possible à cause du cache. |
| 4:00–5:00 | Revenir dans AIV; laisser un résumé final se produire, arrêter proprement et exporter. | Snapshot cohérent, hash, compteurs, inconnus, diagnostics et limites. |

Répéter avant/après avec la même liste d’actions, même durée, même profil et conditions documentées. Utiliser en complément une application fixture sans données personnelles, émettant un nombre connu de paquets/octets sur des sockets connues; les variations du réseau réel ne suffisent pas à prouver une amélioration du moteur.

Pour le dénominateur, « attribuable » ne signifie jamais « déjà attribué ». Définir a priori les observations TCP/UDP sur un Android supportant la méthode et pendant capture active, y compris celles dont la résolution échoue. Conserver les ICMP/protocoles hors méthode dans une catégorie de couverture distincte. Mesurer séparément propriétaire UID et application précise. L’exclusion d’un UID partagé/réservé du taux par package doit être justifiée par une observation indépendante, jamais par le seul label inconnu. Publier aussi le taux brut sur tous les événements du périmètre.

Attribution rate = événements attribués à une application unique / événements admissibles à cette attribution. Unknown rate = événements non attribués de ce même périmètre / événements admissibles. Volume coverage = événements réseau disposant d’une mesure valide et d’une unité définie / événements réseau capturés. Les valeurs nulles ne sont pas des zéros; si le dénominateur est zéro, afficher N/A avec raison. Un volume de premier paquet est valide pour ce paquet, mais ne couvre pas le total de la connexion. Ajouter une couverture séparée des connexions ayant un compteur final valide et des paquets observés/persistés lorsque le moteur les compte.

Le rapport de session doit contenir : applications identifiées/inconnues, flux distincts, événements admissibles et exclus par motif, attribution initiale puis après enrichissement, RX/TX/total/paquets par connexion et période, domaines et IP, questions/réponses DNS effectivement disponibles, trajets et confiance de chaque lien, événements système séparés, nombre/latence/résultat des tentatives UID/package, erreurs et trous de couverture, taille/hash/schéma d’export. Ne pas sommer des instantanés cumulés pour fabriquer le total.

Seuils de validation : 100 % de conservation des observations et volumes attendus dans les fixtures prises en charge; zéro attribution certaine d’un UID partagé à un package arbitraire; toutes les résolutions tardives prévues avant échéance récupérées; statut inconnu explicite après échéance; aucun échec d’export présenté comme complet; mesures export/UI concordantes. Sur téléphone, comparer les distributions et écarts avant/après plutôt qu’inventer une cible d’attribution universelle de 100 %.

Pour les fichiers disponibles dans cet audit, les métriques de trafic sont N/A : le JSON fourni est un rapport de contrôle, pas un export de paquets/flux. Un état de collecteur ou de Shizuku n’est pas une mesure de capture réseau. La prochaine preuve nécessaire est le protocole ci-dessus avec un export réseau local et ses métadonnées.

## Provenance et références

| Repère | SHA complet |
| --- | --- |
| 0.6.34 | e365d8b5b611290f6e88ab46d1b85828f0241d4a |
| 0.6.35 | abf2f4d7d1760d0d0da6c8a7d877330d65979cf4 |
| 0.6.36 | 1862d7f52f0a4edf27510eab1064d800c22d9037 |
| 0.6.37 | 4e475d15c1f493570b9f1a031de07c384e2310ae |
| 1.1.5 | 6dd779ba77f1d3302176a6eb90c166b84c30ce69 |
| Package 1.2.0 | dbfb776cabbbc3d404a8110738f48993b61a3515 |
| Première native | ba4469bb56ab7e21d16cbd0d4e3018f89c84f352 |
| Suite native | a786bd6d178fc0de4c199e6c8f795d0e5015a0db |
| 2.0.0 | 36a6d08a11584daa36a4825432c88bf4571839e2 |
| VPN off 2.0.2 | 60c7e80761ef50fd6bdc657ce2b20abcdc18d14c |
| 2.0.6 | 7a86f7d21b334dd62f7f05ac6532de2f221f461a |

APK fourni : com.allinvisible.aiv, 2.0.0/code 200, minSdk 26, targetSdk 35. SHA-256 : 5be1ef314bf35b560da07e4984995e521c21b6637cceaac0bbc13c791a59552a. Vérification de signature v2/v3 réussie; certificat de test SHA-256 : 3c6b7dbdaecd823b512408d6442328cde2464c8d0b074ec714858e64f5bf08ff. Le manifeste et l’empreinte de l’APK concordent avec la livraison étudiée; il n’y a pas de SHA Git embarqué permettant de prouver la correspondance source/binaire par ce seul manifeste.

Documentation Android vérifiée : ConnectivityManager.getConnectionOwnerUid est disponible à partir de l’API 29 pour TCP/UDP; INVALID_UID peut être renvoyé, et le VPN actif du bon utilisateur est requis. VpnService ne fournit pas à lui seul une identité par paquet : le descripteur TUN rend les paquets sortants et reçoit ceux renvoyés à Android. Ces contraintes sont intégrées au protocole et au périmètre des métriques.

Sources primaires :
- https://github.com/willingemy-byte/My-Friend-Qjan/commit/abf2f4d7d1760d0d0da6c8a7d877330d65979cf4
- https://github.com/willingemy-byte/My-Friend-Qjan/commit/ba4469bb56ab7e21d16cbd0d4e3018f89c84f352
- https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/36385029315
- https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37082292023
- https://developer.android.com/reference/android/net/ConnectivityManager#getConnectionOwnerUid(int,%20java.net.InetSocketAddress,%20java.net.InetSocketAddress)
- https://developer.android.com/reference/android/net/VpnService

Le présent audit n’envoie aucun journal personnel à un serveur de produit. Les exemples de tests sont synthétiques. Les corrections de retries, stockage, index et interface devront rester séparées et validées avant de qualifier le moteur reconstruit.

## Première correction engagée après l’audit

La branche aiv-journal-rebuild a été créée directement depuis abf2f4d7d1760d0d0da6c8a7d877330d65979cf4. Aucun merge ni cherry-pick d’une autre branche. Le premier changement de produit touche uniquement NetworkCaptureService.java et build.py; un harness de régression et les documents accompagnent cette étape.

Le service classe maintenant un propriétaire inconnu dans unknown. Un UID applicatif connu sans package visible ne devient plus une application Android fictive. Une erreur de package/label ne supprime plus le UID déjà obtenu. Des niveaux de confiance distincts sont exportés pour observation, propriétaire et application. Les compteurs cumulatifs absents sont null et INCONNU; un vrai zéro mesuré reste zéro. Un volume de premier paquet est distingué du total du flux. Les compteurs invalides ou dont la somme dépasse long restent conservés en valeurs brutes, mais ne deviennent pas une mesure valide.

Le builder affiche -Xlint:all pour Java et -Wall -Wextra pour C. Le nouveau build complet réussit avec 31 warnings Java et 24 occurrences C, soit les mêmes 12 diagnostics C compilés pour deux ABI. Aucun diagnostic nouveau n’a été introduit. Les warnings sont exposés et classifiés; ils ne sont pas tous corrigés.

Le nouveau test exécute les corps réels de Flow, identify, details et les callbacks de capture avec des réponses Android synthétiques, puis le véritable SnapshotExporter/JournalRecovery. Il vérifie inconnus, null/zéro, volumes sans UID, dépassement de compteur, package invisible ou en erreur, UID partagé/réservé/profil et conservation JSON/JSONL avec SHA-256. Il échoue sur la source 0.6.35 non modifiée (« Unknown owner presented as Android »), puis réussit après correction. run-v23 réussit également.

Le test ne valide pas le relais C, SQLite sur Android ou le téléphone. Les échecs des anciennes suites UI et du test retiré restent documentés. Le plafonnement des retries UID et l’absence de relance du package ne sont pas corrigés dans cette étape. TrackerIndex et les projections UI peuvent encore transformer null en zéro : leur correction est l’étape suivante liée aux volumes, pas un bénéfice prétendu de ce premier commit. L’export de version reste également à corriger. Cette branche est une base de travail, pas une livraison validée pour installation.

Commande du nouveau contrôle : AIV_JSON_JAR=/chemin/org.json-20240303.jar python3 aiv-v19/tests/network-observation.py. Option AIV_SERVICE_SOURCE pour rejouer le même critère sur un snapshot ancien. Java/JDK requis. Les dépendances sont celles du contrôle snapshot déjà prévu; aucune donnée personnelle ni clé de signature n’est nécessaire.
