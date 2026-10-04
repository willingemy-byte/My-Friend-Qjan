# Tests de régression du journal et du relais VPN 2.0.7

Cette publication contient quatre scripts de test et leurs instructions, à partir du snapshot public 2.0.6 `c4c107300b3a5ac0c2ac4a5760e8b9b907bed489`. Les correctifs de production 2.0.7 sont nécessaires aux scénarios qui vérifient le fonctionnement corrigé. Cette branche de tests conserve les sources de production 2.0.6; elle ne constitue donc pas une release 2.0.7.

Les fixtures utilisent des identités, paquets, adresses et volumes synthétiques. Le relais C/JNI réel est exercé avec un faux périphérique TUN et des serveurs loopback. La résolution UID, le keystore et les services Android sont simulés. Ces tests ne valident pas l'installation sur un Samsung ni son bouton de confirmation.

| Script | Vérification |
| --- | --- |
| `relay-callbacks.py --baseline` | Reproduit les blocages TCP/UDP de la 2.0.6 quand le journal, la résolution UID ou la signature sont retardés. Ce scénario est exécutable sur cette branche. |
| `relay-callbacks.py` | Avec le moteur corrigé: continuité du relais sous retard, arrêt borné, saturation de la file, watchdog et absence de redémarrage VPN automatique après échec fatal. |
| `event-store-timestamps.py` | Avec le moteur corrigé: horodatage de capture préservé malgré une écriture tardive ou hors ordre, volume UNKNOWN et récupération JSON/JSONL avec hash. |
| `network-observation.py` | Avec le moteur corrigé: retries UID/paquet, identité inconnue/partagée, volumes manquants ou nuls, fermeture et réutilisation d'identifiants. |
| `journal-sqlite.py` | Index des flux, enrichissement tardif, pagination des trajets, migration SQLite et export intégral avec métadonnées et hash. |

Exécution depuis `aiv-v19/`: Python 3, JDK 17, compilateur C et en-têtes JNI. Configurer `AIV_ANDROID_JAR`, `AIV_JSON_JAR`, `AIV_SQLITE_JAR` et `AIV_SLF4J_JAR` vers les dépendances locales. `AIV_JDK_INCLUDE` permet de préciser le dossier des en-têtes JNI. Les scripts qui exportent des métadonnées requièrent aussi `build/generated/fr/erick/journallocal/BuildMetadata.java`, produit par le build du snapshot à tester.

```bash
python3 tests/relay-callbacks.py --baseline
```

Le build 2.0.7 testé précédemment a passé les scénarios corrigés. Le scénario de référence reproduit six blocages; les huit scénarios corrigés ont aussi été rejoués après adaptation du générateur pour compiler la référence sans la classe nouvelle `CaptureQueue`.

Diagnostics du harnais: `javac` signale les API Android dépréciées du service existant, classées dans le registre de build 2.0.6. Le compilateur C signale le retour ignoré de `system()` dans `third_party/zdtun/utils.c:283`: ce helper configure un TUN système dans les utilitaires tiers. Ici, le TUN est un `socketpair` créé par le helper JNI du test; ce chemin de configuration n'est pas appelé. Ce warning préexistant reste à corriger dans l'utilitaire tiers et n'est pas une preuve d'échec du relais testé.

Les conditions de reproduction et la distinction entre fixture hôte et téléphone doivent accompagner toute comparaison des résultats.
