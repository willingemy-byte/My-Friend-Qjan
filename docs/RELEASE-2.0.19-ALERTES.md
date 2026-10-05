# AIV 2.0.19 — compteur et encadrement

Source directe : branche `aiv-2.0.18-convergence-rc`, commit
`6c633024f9ce93c8eb954432518f52c83b62fe4b`, arbre
`46d7d5f63b86439e3e78a3567287f48b830ba0ca`. Package inchangé :
`com.allinvisible.aiv`. Version 2.0.18 / 218 → 2.0.19 / 219.
La provenance de la 2.0.17 est conservée dans RELEASE-2.0.18-CONVERGENCE-RC.md.

- Le compteur et le filtre des alertes non consultées portent sur les findings
  applicatifs produits en temps réel. Le recalcul historique reste consultable,
  avec une origine explicite, sans remplir la pastille orange au démarrage.
- « Remettre le compteur à zéro », dans le statut AIV et la page Anomalies,
  marque les alertes comme consultées. Les dossiers restent enregistrés. Une
  nouvelle alerte créée après la limite sélectionnée conserve son état non lu.
- Un finding applicatif des règles réseau peut recevoir le dernier clic de la
  même identité, version et horloge monotone, dans une fenêtre de trois secondes.
  Le dossier décrit ce rapprochement temporel sans attribuer de causalité au clic.
  La persistance précède la notification du service d'accessibilité.
- L'encadrement exige le même package, la même fenêtre et un élément visible
  réellement retrouvé avec ses bounds, classe, texte, description et identifiant.
  Il expire après cinq secondes et disparaît si l'élément a changé. Une anomalie
  sans élément lié reste dans la pastille; aucune zone arbitraire n'est encadrée.
- « Tester l'encadrement · sans anomalie » dessine un cadre bleu explicitement
  marqué TEST sur un élément cliquable visible. Aucun finding ni capture de preuve
  n'est créé par ce diagnostic.
- Le statut de la pastille et la page Intégrité actualisent les mêmes états.
  Un paquet reçu après une limitation du relais n'efface plus cette limitation;
  une nouvelle session VPN réinitialise ce constat. L'écran AIV ne réutilise pas
  une capture ancienne d'une autre application pour annoncer un canal visuel actif.
- Le parseur AppOps reconnaît les lignes valides de mode seul sans les transformer
  en activité ni les compter comme erreurs de format. Les valeurs absentes restent
  absentes. Les commandes de lecture et les contrôles mutateurs restent séparés.
- L'export suit la révision courante et les findings temps réel; les dossiers
  d'anciennes révisions restent conservés en base. La santé du capteur reste
  distincte des alertes applicatives et de l'encadrement.

## Validation et limites

Réutilisation de `aiv-v19/build.py` et `aiv-v19/tests/run-convergence.sh`.
Tests hôte sur les règles de production, SQLite réel et requêtes de dessin avec
des nœuds Android simulés : compteur, reset, export, persistance avant notification,
bounds, fenêtre, disparition, expiration et recyclage. Ces tests ne démontrent
pas le rendu physique de l'overlay Android.

Aucune cible adb n'est connectée : l'installation et l'encadrement de cette
2.0.19 restent à vérifier sur téléphone. Capture/hash uniquement, pas OCR ni
comparaison visuelle confirmée. Les images ordinaires restent éphémères; crop
uniquement pour un finding enregistré et une zone corroborée. Aucun contournement
de FLAG_SECURE. Aucun changement de WebView, de schéma du journal, de certificat
de signature ni de bibliothèque native VPN.
