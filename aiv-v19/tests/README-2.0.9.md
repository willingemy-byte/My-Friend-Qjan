# Tests AIV 2.0.9

Les sources de production 2.0.9 sont nécessaires. Les données sont entièrement synthétiques et les fixtures sont isolées du répertoire embarqué de l’APK.

| Vérification | Résultat |
|---|---|
| `bash tests/run-permission-integration.sh` | PermissionControl: 25 vérifications; PermissionMaintenance: 96 vérifications. Vrais contrôleurs, fichiers locaux réellement écrits; Android, Shizuku, notifications et journal Android simulés. |
| `bash tests/run-permission-control.sh` | 40 règles de permissions, 36 règles d’examen; exécution Shell distante, délais, autorisation, lectures bornées et échecs de pipes. |
| `python3 tests/uid-probe.py` | Recherche UID indépendante, fermeture, réutilisation du tuple, bornes, retard et récupération. |
| `python3 tests/network-observation.py` | Attribution différée, immutabilité, flux UNKNOWN/UID partagé/réservé, volume et exports. |
| `python3 tests/event-store-timestamps.py` | Insertions SQLite et JSON/JSONL hashés, horodatage capture/persistance, volumes indépendants de l’identité. |
| `python3 tests/journal-sqlite.py` | SQLite réel, enrichissement tardif, compteurs maximaux, pagination, 501 étapes et export de 503 événements sans modifier le brut. |
| `python3 tests/relay-callbacks.py` | Huit scénarios avec vrai relais C/JNI et faux TUN local: TCP/UDP, retards du journal/UID/signature, watchdog temporaire et saturation. |

Le maintien vérifie les refus runtime et spéciaux réglés manuellement, les protections Android, la perte/reprise Shizuku, l’arrêt/pause, les mises à jour et réinstallations, le signataire, les UID partagés et les composants système mis à jour. Les essais d’approbation exigent que la politique soit enregistrée avant réactivation et qu’un changement de rôle pendant le reçu empêche la commande. Un faux disque plein, un journal indisponible et un code de retour nul sans changement réel ne produisent pas de fausse confirmation. La restauration passe par le vrai PermissionControl, y compris lorsqu’un droit a déjà été réaccordé manuellement. Les cycles de 12 mutations et de 20 cibles spéciales conservent une rotation qui atteint les dernières cibles.

Variables: `AIV_TEST_JSON_JAR` pour l’intégration; `AIV_ANDROID_JAR`, `AIV_JSON_JAR`, `AIV_SQLITE_JAR`, `AIV_SLF4J_JAR` et `AIV_JDK_INCLUDE` pour les scénarios de capture/export. JDK 17, Python 3 et compilateur C requis. `BuildMetadata.java` est généré par `build.py`. Le JSON jar de l’intégration est épinglé à SHA-256 `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.

Les résultats hôte ne vérifient pas la mise en veille Samsung, l’application réelle des AppOps Android 16, le temps de détection sur téléphone ou un taux d’attribution réel. Le moteur réseau conservé est vérifié par ses scénarios de régression, sans introduire d’observation personnelle dans les fixtures. Les diagnostics du [registre de build](../BUILD-NOTES-2.0.9-MAINTENANCE.md) restent explicites.
