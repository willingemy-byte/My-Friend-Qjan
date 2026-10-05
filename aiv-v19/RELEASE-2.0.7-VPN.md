# All In Visible 2.0.7 — stabilité du relais VPN

Branche `aiv-vpn-stability`, issue uniquement de `c4c107300b3a5ac0c2ac4a5760e8b9b907bed489` (2.0.6, moteur d’observation corrigé). Pas de mélange de branches. Paquet `com.allinvisible.aiv`, version 2.0.7/code 207, signature de production identique à la 2.0.6.

## Problème et preuve limitée

Lorsque le VPN local 2.0.6 est actif, les callbacks JNI exécutent l’identification Android, les métadonnées/signatures Keystore et les écritures SQLite directement sur le thread qui relaie tous les paquets. Une opération lente peut donc immobiliser TCP et UDP et retarder l’arrêt du relais.

Le test `relay-callbacks.py --baseline` compile le **service 2.0.6 du commit ci-dessus, le JNI et le relais C réels**. Il bloque tour à tour l’écriture du journal, la recherche d’UID et la signature dans des fixtures contrôlées : aucun retour UDP ni SYN/ACK TCP pendant 600 ms; le retour arrive après libération. Les six cas reproduisent ce mécanisme. Ce résultat n’identifie pas à lui seul la cause exacte de la panne sur le téléphone; aucun logcat du téléphone n’est disponible.

## Changement livré

- Les callbacks JNI conservent leurs signatures et passent leurs arguments à un observateur FIFO borné à 4096 commandes. Le relais ne résout plus les UID, ne signe plus les métadonnées et n’écrit plus SQLite. La protection/binding de ses sockets reste dans le relais.
- La première observation garde son heure de capture et UNKNOWN. L’identité est recherchée avant le premier accès disque, puis enregistrée séparément comme `IDENTITY_ENRICHMENT`. Un UID retourné après fermeture du flux est rejeté. Le temps effectif de l’observation d’UID est conservé.
- `EventStore.addObserved` distingue `timestamp_ms`/`elapsed_ms` de capture et `persisted_at_ms`. Les événements existants et le schéma SQL source version 1 sont conservés. Les anciens producteurs peuvent toujours utiliser `add`.
- La signature d’identité du flux est réutilisée lorsque ses métadonnées canoniques sont identiques; elle ne disparaît pas et n’est pas injectée dans les paquets.
- Si une observation reste en attente plus de 3 secondes, la file sature, ou l’enregistrement échoue, le relais est arrêté. La reprise automatique du VPN est désactivée; réactivation manuelle nécessaire. Le journal accepte sa file restante quand l’API bloquée revient et écrit un bilan (commandes acceptées/traitées/rejetées, lacune, erreur). Il ne fabrique pas de volume pour les observations rejetées.
- L’interface décrit une « Interface VPN active », ce qui ne promet pas l’accessibilité de chaque destination.

## Vérifications reproductibles

Les jars et les outils hôte sont fournis par variables; aucun de ces stubs n’est embarqué dans l’APK. Après génération de `BuildMetadata` avec `build.py` :

```sh
python3 tests/relay-callbacks.py --baseline
python3 tests/relay-callbacks.py
python3 tests/event-store-timestamps.py
python3 tests/network-observation.py
python3 tests/journal-sqlite.py
python3 tests/native-observation.py
python3 -m unittest discover -s tests -v
bash tests/run-v23.sh
bash tests/run-v23-snapshot.sh
bash tests/run-permission-control.sh
bash tests/run-permission-integration.sh
```

- JNI actuel : les six cas TCP/UDP passent pendant les mêmes blocages; échange TCP de 16 Kio intact. Arrêt natif en moins de 900 ms malgré un lookup UID bloqué. Watchdog et saturation 4096 vérifiés; reprise automatique désactivée. Le cumul UDP est exactement 44 octets IP TX, 44 RX, un paquet dans chaque sens. Les compteurs TCP sont comparés aux paquets réellement injectés/lus, sans additionner des instantanés cumulatifs.
- Insertions EventStore de production : capture 5 secondes avant insertion, insertion hors ordre, UNKNOWN avec volume, colonne SQLite et payload concordants, JSON/JSONL récupérables avec hash et période correcte.
- Régressions journal : UID différé, package différé, UID conservé lors d’erreurs facultatives, pas d’attribution à un UID partagé/réservé; limites de retry et de file; DNS/SNI, trajets, volumes et exports SQLite. Les tests du contrôle des permissions existant restent applicables.
- [Registre complet des diagnostics et limites](BUILD-NOTES-2.0.7-VPN.md) : 49 dépréciations Java, pas d’erreur. Les sources/bibliothèques natives épinglées sont inchangées et vérifiées par hash.

## Essai téléphone à réaliser

Installer en mise à jour, sans désinstaller AIV. Vérifier la version 2.0.7 et conserver un export avant l’essai. Pendant cinq minutes : capture inactive, ouvrir Chrome/ChatGPT; activer la capture; recharger 2–3 sites, ChatGPT, Play et Photos; revenir dans AIV; arrêter puis réactiver la capture. Tester séparément Wi-Fi et cellulaire.

Vérifier l’accès Internet pendant la capture et après arrêt, l’absence de boucle de redémarrage après une erreur, les flux et octets RX/TX même pour UNKNOWN, l’heure de capture/enrichissement, les erreurs/lacunes et les exports JSON/JSONL/SQLite. Comparer les taux d’attribution/inconnu et la couverture de volume sur la même période, puis les trajets documentés. Un arrêt de protection doit être visible et ne constitue pas une capture complète.

Aucun essai sur téléphone, transition Wi-Fi/cellulaire réelle, HTTPS Android/QUIC complet ou performance longue durée n’est revendiqué. La résolution Android et son délai réels restent à mesurer. Les clés privées et observations personnelles ne sont pas publiées; l’APK signé est livré séparément par téléchargement privé.
