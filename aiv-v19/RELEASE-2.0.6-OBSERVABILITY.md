# AIV 2.0.6 — correction du journal

Base native exacte : `7a86f7d21b334dd62f7f05ac6532de2f221f461a`.
Branche : `aiv-2.0.6-observability`. Version Android : `2.0.6`, code `206`, package `com.allinvisible.aiv`. Le commit et l’empreinte des sources applicatives sont inscrits dans les métadonnées d’export lors de chaque compilation.

## Changements livrés

- Capture volontaire depuis **Flux → Activer la capture réseau (VPN local)**, avec autorisation Android. Revenir dans AIV conserve la capture active. Le démarrage initial reste volontaire; **Démarrer la collecte locale (sans VPN)** arrête explicitement une capture réseau active.
- Identification temporisée sur le worker du relais, y compris au repos : échéances 0, 25, 100, 300, 750, 1500, 3000, 6000 ms, huit tentatives au maximum dans une fenêtre de 10 s. Une rafale de paquets ne consomme plus trois tentatives instantanément. File bornée à 2048 entrées, passages bornés à 16 entrées et 4 ms; le réveil natif au repos peut espacer les essais d’environ 200 ms. Les erreurs d’enrichissement du paquet, du libellé ou de la signature n’effacent plus un UID déjà observé.
- Les observations initiales restent immuables. Un événement `IDENTITY_ENRICHMENT`, relié par `flow_correlation_id`, conserve l’identité obtenue plus tard. Les tuples fermés et le callback TLS de fermeture ne sont pas réinterrogés. Les UID partagés, réservés et contradictoires sont explicites.
- Compteurs indépendants de l’identité. `null` signifie non observé, `0` un zéro mesuré. Les valeurs négatives et dépassements sont marqués invalides avec leurs valeurs originales. Une projection conserve le maximum cumulatif mesuré, sans additionner les instantanés.
- Index de tous les flux, y compris inconnus et hors catalogue. Migration du seul index dérivé de version 2 à 3 et reconstruction depuis les observations. `journal.sqlite` reste au schéma 1; aucune suppression ni réécriture des observations. Un défaut de l’index reste signalé et ne provoque plus une exception non interceptée dans sa reprise.
- Journal, groupes, connexions, trajets et étapes paginés. Les curseurs des trajets utilisent les identifiants d’événements et conservent les entrées de même timestamp. Les lectures de trajets passent hors du thread de l’interface.
- Export **JSON, JSONL ou SQLite** par le sélecteur de fichier Android. Instantané borné, compteur d’événements, période des observations, version/build, définitions, métriques et SHA-256 des événements. La copie terminée est relue et son SHA-256 complet est affiché. SQLite contient `events(id,timestamp_ms,payload)` et `metadata`; c’est un instantané portable des observations, sans copie à chaud du WAL ni des réglages privés.

## Ce que les données démontrent

`confidence` sépare connexion/IP observée, UID observé, paquet attribué, question DNS ou nom TLS observé et provenance applicative inconnue. Le rapprochement avec un catalogue est une corrélation, sans conclusion sur le contenu, le SDK exécuté ou l’intention.

Les compteurs portent sur l’interface VPN locale : en-têtes, retransmissions et réponses TCP produites par le relais inclus. Ils ne mesurent ni la facture de l’opérateur ni la taille des messages applicatifs. DNS clair observé = question adressée au résolveur; aucune association inventée à l’IP d’une connexion ultérieure. QUIC, DNS chiffré, ECH, autres profils et interruptions demeurent des limites explicites.

Les métriques de la vue Flux concernent **la page filtrée affichée**, avec la dernière identité disponible. Le footer du journal exporté compte les **observations originales avant enrichissement**; son taux initial ne représente pas le taux final par connexion. Les UID réellement partagés/réservés et protocoles non pris en charge sont exclus de l’attribution unique; les échecs d’identification TCP/UDP sont inclus. Dénominateur vide = `null`. La couverture de volume de l’export concerne premiers paquets et instantanés, sans compter DNS/SNI/ouverture comme de nouveaux paquets. Aucun volume global n’est obtenu en additionnant les lignes du journal.

## Validation effectuée

| Vérification | Résultat et portée |
|---|---|
| Compilation native + Java + DEX + ressources | ARM64/x86_64, SDK 35, min API 26, sans WebView dans le runtime |
| `tests/network-observation.py` | Vrais callbacks Java avec réponses Android contrôlées : rafale 0/1/2 ms puis UID 50 ms, enrichissement au repos, paquet tardif, exceptions, UID partagés/réservés/profil, UNKNOWN, zéro/absent, négatif/overflow, fermeture/TLS/ID réutilisé, file bornée, export sans altération |
| `tests/journal-sqlite.py` | Vrai SQLite host via JDBC et vrai code de l’index/export : migration v2→v3, reprise, erreur d’index, conservation des compteurs et identités, UID contradictoires, 501 étapes et 105 flux paginés, timestamps égaux, export 503 événements, rejet d’un instantané incomplet |
| `tests/native-observation.py` | Vrai relais C sur substitut local de TUN et serveur UDP loopback : 2 paquets TX / 2 RX, 67 octets par sens, sauvegarde à la fermeture; DNS tronqué et ClientHello fragmenté/désordonné/contradictoire/limite; ASan/UBSan |
| `tests/run-v23.sh` | Suites existantes règles/snapshots/signatures/DEX/segments et moteur de références : passent |
| `tests/run-permission-control.sh` | 40 vérifications de contrôle, 36 cas de règles, résultats distants/timeout : passent, sans commande sur téléphone |
| `tests/run-permission-integration.sh` | 25 vérifications de l’intégration existante : passent avec stubs |
| `tests/run-v23-snapshot.sh` | Empreinte de snapshot native et invalidation d’actions : passent |
| Découverte Python `test_*.py` | 12 tests applicables passent; 1 module historique explicitement SKIP, justification dans les notes de build |

Voir **BUILD-NOTES-2.0.6-OBSERVABILITY.md** pour chaque diagnostic, les essais HTML historiques et les limites des outils. Les dépendances de tests host ne sont pas incluses dans l’APK : org.json 20240303 SHA-256 `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`, SQLite JDBC 3.46.1.0 `6dc7464e3803648d3ff18a7359bab6adf079fcd8495b18991f6f5edcb8ac6e3b`, SLF4J 1.7.36 `d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0`.

La documentation Android confirme les contraintes de `getConnectionOwnerUid` : API 29+, TCP/UDP, application VPN active du profil courant. Source primaire : https://developer.android.com/reference/android/net/ConnectivityManager#getConnectionOwnerUid(int,%20java.net.InetSocketAddress,%20java.net.InetSocketAddress).

## Protocole téléphone — cinq minutes

1. Installer l’APK signé en **mise à jour**, sans désinstaller AIV. Vérifier la version 2.0.6 et le build affiché dans AIV. Attendre la reconstruction de l’index en suivant son avancement.
2. Depuis Flux, activer volontairement la capture et accepter le dialogue VPN Android. Noter l’heure et le réseau. Vérifier que l’état est actif avant de commencer.
3. Pendant cinq minutes, ouvrir Chrome, visiter `example.com` et `wikipedia.org`, puis ouvrir ChatGPT, Google Play et Photos. Noter les heures des actions. Une ouverture d’application servie depuis le cache ne garantit aucun trafic.
4. Revenir dans AIV : la capture doit être encore active. Actualiser Flux, consulter UID/attribution/IP/destination, compteurs TX/RX et métriques de chaque page. Explorer un groupe/trajet s’il existe une correspondance DNS/SNI réellement observée.
5. Arrêter la capture depuis Flux afin de sauvegarder les compteurs finaux, attendre le rattrapage de l’index, puis exporter le journal en JSONL et SQLite. Conserver les SHA-256 affichés et la période. Aucune transmission de cette capture personnelle n’est automatique.
6. Comparer les captures de durée et de conditions équivalentes en distinguant observations et connexions enrichies : applications identifiées/inconnues, dénominateur, taux d’attribution/inconnus, couverture des volumes, connexions, RX/TX, IP, questions DNS/noms TLS, UID système/partagés, erreurs et interruptions.

Critères d’acceptation : retour à AIV sans arrêt involontaire; UNKNOWN avec volumes lorsqu’ils sont observés; zéros et valeurs absentes distincts; enrichissement sans altérer l’observation initiale; pagination complète; exports intègres et récupérables. Aucun objectif de taux réel n’est annoncé sans mesure sur le téléphone. Les trois sources de tests host ne remplacent pas Android, JNI, SAF ni un essai réel Wi-Fi/cellulaire.

La signature de livraison doit correspondre au certificat des APK existants 2.0.0 et 2.0.6 : SHA-256 `3c6b7dbdaecd823b512408d6442328cde2464c8d0b074ec714858e64f5bf08ff`. Les secrets de signature restent en dehors du dépôt.
