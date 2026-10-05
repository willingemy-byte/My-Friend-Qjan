# All In Visible 2.0.8 — continuité du VPN et recherche UID indépendante

Branche `aiv-vpn-background-2.0.8`, créée depuis le snapshot local `8419301` de `aiv-vpn-stability`. Le moteur 2.0.7 provient de `62b5f1848854b1366dd87ef2ee1dd48fb95afaa0`, lui-même issu de la base 2.0.6 `c4c107300b3a5ac0c2ac4a5760e8b9b907bed489`. Aucune fusion de branche. Paquet `com.allinvisible.aiv`, version 2.0.8/code 208; signature de production conservée.

## Défauts ciblés et limite de diagnostic

En 2.0.7, un flux peut fermer pendant qu'une écriture/signature retient l'observateur. L'ouverture et les paquets sont alors enregistrés, mais la recherche UID peut être abandonnée avant son premier appel Android. Le diagnostic contrôlé conserve ce défaut de référence. Par ailleurs, l'ancien watchdog arrête volontairement le VPN dès qu'une observation a plus de trois secondes de retard, même si le relais continue à transmettre.

Les observations du téléphone motivent ces corrections mais ne contiennent pas encore le motif d'arrêt du service. Un champ `closed` dans un flux prouve la fermeture de ce flux, pas celle du VPN. La cause précise de chaque arrêt sur le téléphone n'est donc pas déclarée démontrée. La 2.0.8 conserve son motif d'arrêt dans les préférences locales et l'affiche dans Flux pour rendre le prochain essai vérifiable.

## Corrections

- `UidProbe` recherche seulement le propriétaire du socket sur un worker distinct. Les demandes sont bornées à 2048, les nouvelles recherches passent avant les retries et les délais restent monotoniques: 0, 25, 100, 300, 750, 1500, 3000 et 6000 ms, maximum 10 secondes. Les API Android, labels, signatures et écritures ne tournent pas sur le relais natif.
- Une réponse UID arrivée après fermeture/révocation est rejetée. Chaque flux possède son propre ticket, séparé des tickets qui réutilisent le tuple ou l'identifiant. L'UID observé avant fermeture garde son horodatage et permet ensuite l'enrichissement du package, même après fermeture. Les retries de package utilisent des références de flux, pas un identifiant natif réutilisable.
- Les observations originales restent immuables et UNKNOWN si l'UID n'était pas encore intégré. L'enrichissement est écrit séparément. `identity_lookup_flow_closed`, `uid_lookup_status`, `uid_lookup_attempts`, `package_attempts` et leur portée distinguent l'état de capture du paquet de celui de l'enrichissement.
- Un retard temporaire supérieur à trois secondes est affiché; il ne coupe plus immédiatement le VPN. L'arrêt de protection intervient après 30 secondes sans progression de l'observateur, à saturation de la file ou si la persistance échoue. Le motif reste explicite et la reprise automatique est suspendue après un échec fatal.
- L'écran Flux expose l'état du VPN, son erreur et le dernier motif d'arrêt conservé. Les deux mentions de version de l'interface utilisent `BuildMetadata.VERSION_NAME`; l'en-tête affiche maintenant la version réellement compilée.

Les compteurs, le parseur DNS/SNI, les trajets, le format d'export et le schéma SQLite source ne sont pas modifiés. Un paquet observé garde son volume même si l'UID est inconnu. Les volumes de session viennent des instantanés cumulatifs et ne sont pas déduits du premier paquet.

## Validation

Depuis `aiv-v19/`, avec les dépendances hôte indiquées dans les instructions de test:

```sh
python3 tests/uid-probe.py
python3 tests/relay-callbacks.py --verify-short-flow
python3 tests/relay-callbacks.py --reproduce-short-flow
python3 tests/relay-callbacks.py
python3 tests/relay-callbacks.py --persistent-stall
python3 tests/network-observation.py
python3 tests/event-store-timestamps.py
python3 tests/journal-sqlite.py
python3 tests/native-observation.py
python3 -m unittest discover -s tests -v
bash tests/run-v23.sh
bash tests/run-v23-snapshot.sh
bash tests/run-permission-control.sh
bash tests/run-permission-integration.sh
```

Le scénario de référence 2.0.7 est compilé depuis son commit épinglé. Le scénario 2.0.8 vérifie que la recherche UID aboutit pendant qu'une écriture du journal d'un autre flux reste bloquée, avant de fermer le flux contrôlé. L'UID reste exploitable ensuite, la capture originale garde UNKNOWN et le paquet entrant ainsi que les compteurs finaux restent présents dans SQLite. La réponse Android est une fixture; aucun taux d'attribution réel du téléphone n'est inventé.

Les huit scénarios C/JNI du relais testent les retards journal/UID/signature TCP et UDP, 16 Kio TCP intact, l'arrêt borné, un retard transitoire de 3,4 s et la saturation. Le cas d'absence de progression utilise un seuil raccourci à 400 ms uniquement dans la copie compilée du test hôte; l'APK conserve 30 s. Les tests vérifient aussi le rejet d'un UID retourné après fermeture, une réutilisation du tuple avec un autre UID, l'enrichissement du package après fermeture, les volumes indépendants de l'identité et la récupération des exports.

Le registre de build conserve et classe chaque diagnostic. Les bibliothèques natives épinglées n'ont pas changé. Aucun émulateur, adb ou essai sur téléphone n'est revendiqué.

## Essai reproductible sur le téléphone

1. Installer la 2.0.8 en mise à jour, sans désinstaller AIV. Vérifier 2.0.8 dans l'en-tête et les paramètres Android.
2. Dans Flux, activer le VPN une fois et noter l'heure de départ. Faire les essais d'abord en Wi-Fi.
3. Pendant cinq minutes, alterner AIV → Chrome → ChatGPT, au moins cinq fois. Charger deux sites, publier une image dans ChatGPT, puis ouvrir Play et Photos. Vérifier que le réseau reste utilisable.
4. Revenir dans Flux et actualiser sans réactiver le VPN. Relever l'état du VPN, l'éventuel retard du journal et le dernier motif d'arrêt. Un motif présent est celui du dernier arrêt, pas nécessairement une erreur de la session active.
5. Exporter la même période en JSONL ou SQLite. Comparer RX/TX, paquets, attribution/inconnus sur les mêmes flux, délais capture → persistance, tentatives UID, DNS/SNI et trajets. Ne pas utiliser le taux d'une page comme mesure de toute la session.
6. Si le VPN s'arrête encore, conserver le motif exact, l'heure et les événements collecteur correspondants. Répéter séparément en cellulaire.

Une identification réseau n'attribue pas automatiquement l'application demandeuse derrière un service système, et une proximité temporelle ne devient pas un trajet causal. UNKNOWN reste explicite.
