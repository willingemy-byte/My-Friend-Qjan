# Tests AIV 2.0.8

Ces scripts nécessitent les sources de production correspondantes: `UidProbe`, `CaptureQueue` et `NetworkCaptureService` 2.0.8. Ils emploient des fixtures synthétiques, jamais une capture personnelle. Les scripts `event-store-timestamps.py`, `journal-sqlite.py` et `native-observation.py` restent applicables.

| Script | Résultat vérifié |
|---|---|
| `uid-probe.py` | Worker de production, retries et file bornés, arrêt, rejet d'une réponse après fermeture, réutilisation du tuple avec autre UID, progression/récupération de l'observateur. |
| `relay-callbacks.py --reproduce-short-flow` | Défaut de référence 2.0.7 depuis `62b5f1848854b1366dd87ef2ee1dd48fb95afaa0`: flux fermé derrière une écriture bloquée, zéro recherche UID. Nécessite ce commit dans l'historique local. |
| `relay-callbacks.py --verify-short-flow` | UID obtenu pendant le retard du journal, conservé après fermeture; observation originale immuable, paquet de 205 octets et cumuls indépendants de l'identité. Callbacks contrôlés, sans paquet réel pour ce scénario. |
| `relay-callbacks.py` | Huit scénarios avec relais C/JNI réel et faux TUN loopback: réponse TCP/UDP pendant retard, volumes, signatures identiques réutilisées, retard temporaire de 3,4 secondes toléré, saturation et arrêt borné. |
| `relay-callbacks.py --persistent-stall` | Arrêt de protection sans progression; seuil hôte abaissé à 400 ms, configuration APK inchangée à 30 s. |
| `network-observation.py` | Identité/package différés, enrichissement du package après fermeture depuis UID déjà observé, UNKNOWN/UID partagé/réservé, volumes, retries, immutabilité et exports. |

Variables: `AIV_ANDROID_JAR`, `AIV_JSON_JAR`, `AIV_SQLITE_JAR`, `AIV_JDK_INCLUDE` et, pour certains tests SQLite, `AIV_SLF4J_JAR`. JDK 17, Python 3, compilateur C et en-têtes JNI. `BuildMetadata.java` doit être généré par `build.py` pour les tests d'export. Les scripts de la défense existante utilisent aussi Node.js.

Les diagnostics des fixtures sont expliqués dans le registre de build 2.0.8: API Android dépréciée du service, retour `system()` ignoré du helper TUN tiers non appelé ici, absence de backend SLF4J et limites d'ASan/LSan. Une fixture propriétaire de socket ne vérifie pas le comportement réel d'Android sur le téléphone.
