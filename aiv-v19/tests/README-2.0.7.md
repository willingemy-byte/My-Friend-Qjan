# Tests de régression du journal et du relais VPN 2.0.7

Cette publication contient quatre scripts de test et leurs instructions, à partir du snapshot public 2.0.6 `c4c107300b3a5ac0c2ac4a5760e8b9b907bed489`. Les correctifs de production 2.0.7 sont nécessaires aux scénarios qui vérifient le fonctionnement corrigé. Cette branche de tests conserve les sources de production 2.0.6; elle ne constitue donc pas une release 2.0.7.

Les fixtures utilisent des identités, paquets, adresses et volumes synthétiques. Le relais C/JNI réel est exercé avec un faux périphérique TUN et des serveurs loopback. La résolution UID, le keystore et les services Android sont simulés. Ces tests ne valident pas l'installation sur un Samsung ni son bouton de confirmation.

| Script | Vérification |
| --- | --- |
| `relay-callbacks.py --baseline` | Reproduit les blocages TCP/UDP de la 2.0.6 quand le journal, la résolution UID ou la signature sont retardés. Ce scénario est exécutable sur cette branche. |
| `relay-callbacks.py` | Avec le moteur corrigé: continuité du relais sous retard, arrêt borné, saturation de la file, watchdog et absence de redémarrage VPN automatique après échec fatal. |
| `relay-callbacks.py --reproduce-short-flow` | Avec les sources 2.0.7: reproduit une fenêtre UID manquée lorsqu'un flux court est entièrement capturé pendant qu'une écriture du journal retient l'observateur. Diagnostic séparé des tests du relais. |
| `event-store-timestamps.py` | Avec le moteur corrigé: horodatage de capture préservé malgré une écriture tardive ou hors ordre, volume UNKNOWN et récupération JSON/JSONL avec hash. |
| `network-observation.py` | Avec le moteur corrigé: retries UID/paquet, identité inconnue/partagée, volumes manquants ou nuls, fermeture et réutilisation d'identifiants. |
| `journal-sqlite.py` | Index des flux, enrichissement tardif, pagination des trajets, migration SQLite et export intégral avec métadonnées et hash. |

Exécution depuis `aiv-v19/`: Python 3, JDK 17, compilateur C et en-têtes JNI. Configurer `AIV_ANDROID_JAR`, `AIV_JSON_JAR`, `AIV_SQLITE_JAR` et `AIV_SLF4J_JAR` vers les dépendances locales. `AIV_JDK_INCLUDE` permet de préciser le dossier des en-têtes JNI. Les scripts qui exportent des métadonnées requièrent aussi `build/generated/fr/erick/journallocal/BuildMetadata.java`, produit par le build du snapshot à tester.

```bash
python3 tests/relay-callbacks.py --baseline
```

Le build 2.0.7 testé précédemment a passé les scénarios corrigés. Le scénario de référence reproduit six blocages; les huit scénarios corrigés ont aussi été rejoués après adaptation du générateur pour compiler la référence sans la classe nouvelle `CaptureQueue`.

La reproduction des flux courts se lance avec les sources du moteur 2.0.7, sans `--baseline`:

```bash
python3 tests/relay-callbacks.py --reproduce-short-flow
```

Une écriture du journal d'un premier flux est bloquée par une barrière contrôlée. Pendant ce blocage, les callbacks synthétiques d'un second flux UDP ouvrent le flux, observent un paquet sortant de 60 octets puis un paquet entrant de 205 octets, et annoncent sa fermeture. La barrière est relâchée après 500 ms supplémentaires. Le test ne fait circuler aucun paquet pour ce scénario et ne reprend aucun identifiant ou journal du téléphone.

Résultat obtenu sur le moteur 2.0.7: zéro appel UID pour le second flux, `CLOSED_UNRESOLVED`, et `closed: false` sur son événement antérieur de premier paquet entrant. Le délai capture → persistance mesuré était de 530 ms. Le callback de fermeture a retiré le flux de `liveFlows` avant que l'observateur ne traite son ouverture; `identify()` abandonne donc avant le premier appel Android. Le champ `closed` conserve toutefois l'état séquentiel des observations déjà consommées. Ces deux états se réfèrent à des instants différents et leur présentation actuelle est ambiguë.

Le paquet entrant garde ses 205 octets observés. Ses totaux de flux restent inconnus jusqu'au traitement de `COUNTER_SNAPSHOT`; l'instantané final garde 60 octets TX, 205 octets RX et un paquet dans chaque sens malgré l'UID inconnu. Le test vérifie aussi les horodatages de capture, la présence des enregistrements SQLite et l'absence d'attribution inventée ou de perte de commandes.

Ce résultat prouve un mécanisme de fenêtre manquée dans le moteur testé. Il ne prouve pas qu'Android aurait retourné un UID pour une connexion particulière sur un téléphone. Ce diagnostic décrit le défaut restant; il ne valide pas encore sa correction. La correction à vérifier séparément consiste à déclencher la recherche UID pendant que le flux est actif, indépendamment des écritures/signatures, puis à enrichir depuis cette observation horodatée. Interroger un tuple déjà fermé ou prolonger seulement les retries ne résout pas cette fenêtre et risque une fausse attribution en cas de réutilisation du tuple.

Diagnostics du harnais: `javac` signale les API Android dépréciées du service existant, classées dans le registre de build 2.0.6. Le compilateur C signale le retour ignoré de `system()` dans `third_party/zdtun/utils.c:283`: ce helper configure un TUN système dans les utilitaires tiers. Ici, le TUN est un `socketpair` créé par le helper JNI du test; ce chemin de configuration n'est pas appelé. Ce warning préexistant reste à corriger dans l'utilitaire tiers et n'est pas une preuve d'échec du relais testé.

Les conditions de reproduction et la distinction entre fixture hôte et téléphone doivent accompagner toute comparaison des résultats.
