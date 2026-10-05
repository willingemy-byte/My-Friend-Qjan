# AIV 2.0.18 — candidate de convergence

Package `com.allinvisible.aiv`, versionCode 218. Source de départ : commit local
`7d4ca3860ee0af4159ecbf229219686779ace9c1` (2.0.17 / 217), confirmé par le commit
embarqué dans l’APK signé et par ses assets. La branche GitHub
`aiv-2.0.17-source-convergence`, commit `db08b233ac48f236f0dad08f04c70a332ced223a`,
contient exactement son arbre Git `025e1f84825cab22148d19b71175e95d58fafd1c`.
Le code 2.0.18 des endpoints descend directement de cette source; cette passe
intègre les autres capteurs sans changer le package ni le certificat.

Architecture réutilisée : EventStore conserve les observations; AnomalyMonitor
réunit la corrélation temps réel, le recalcul historique, les findings et le
pedigree des endpoints dans sa base SQLite existante. AccessibilityService
fournit des snapshots bornés; PermissionUsage/AppOpsCollector utilisent une
lecture Shizuku à argv fixes. Le contrôle qui modifie les droits conserve ses
protections. Le VPN et ses bibliothèques natives restent inchangés.

- AppOps : lecture gratuite, relevé global puis repli borné par application,
  état mesuré, modes/access/reject/running, signatures et installation vérifiées.
  Les temps relatifs de commandes sont des estimations avec incertitude conservée.
- Corrélation : horloge monotone au sein du même processus, identité/version
  concordantes, dossier avec établi/corrélé/inconnu. Aucun contenu envoyé déduit.
- Endpoints : catalogue enrichi, historique par paquet/signature/version, sessions
  distinctes et maximum des compteurs par flux. Une IP seule reste insuffisante.
- Overlay : santé distincte des alertes; deux IDs puis +N; ouverture explicite AIV
  ou du finding; compteur modifié après consultation. Liste paginée même en recalcul.
- Visuel : screenshot de fenêtre sur API 34+, écran sur API 30–33, sans contourner
  les protections. Images ordinaires détruites après hash/analyse. Crop uniquement
  pour un finding enregistré et une fenêtre/zone corroborées. Rétention configurable
  (aucune / 24 h / 7 jours), plafond 100 fichiers / 20 Mio. Refus = couverture AIV.

## Validation reproductible

Pipeline existant : `aiv-v19/build.py --unsigned` puis tests via
`aiv-v19/tests/run-convergence.sh`. Le script requiert javac, org.json, sqlite-jdbc
et slf4j dans les variables AIV_JSON_JAR, AIV_SQLITE_JAR et AIV_SLF4J_JAR.
Signature avec le certificat existant, jamais une nouvelle clé. Le pin existant
vérifie les sources et bibliothèques natives réutilisées; leur égalité avec l’APK
2.0.17 est contrôlée séparément.

## Limites qui empêchent l’acceptation finale

Cette candidate n’est pas déclarée validée sur téléphone : aucune cible adb ni
émulateur n’est connectée dans l’environnement. Installation, démarrage, fenêtres,
clics réels, capture/refus Android, surbrillance et relevé AppOps réel restent à
valider. Les tests hôte ne démontrent pas un last_success_ms du téléphone.

Le canal visuel réalise capture/hash, pas OCR ni comparaison du texte affiché avec
le texte sémantique : VISUAL_CAPTURE_ONLY, comparison_confirmed=false. La nouveauté
d’un endpoint ou un accès sensible proche d’un clic produit un signal à vérifier,
pas une preuve d’incohérence fonctionnelle ou d’exfiltration. Les observations DNS
restent des questions, sans réponse DNS ni preuve de résolution vers un autre flux.
Les niveaux DEGRADED_CONNECTIVITY et APP_CONNECTIVITY_FAILURE ne sont pas inférés :
la couverture actuelle justifie PATH_FAILURE et ne prouve pas une panne globale.
