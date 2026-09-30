Tests de V22 : `bash aiv-v19/tests/run-v22.sh` depuis la racine.
Java 17, Node et Python 3 sont nécessaires. Le modèle testé est extrait de
l'interface réellement embarquée, sans copie concurrente du code métier.
Les données de test sont synthétiques; aucune capture privée du téléphone.

Parcours de l’interface : `node aiv-v19/tests/reader-v22.cjs` avec Playwright
et Chromium disponibles. `AIV_CHROMIUM` peut désigner un exécutable Chromium.
Le pont Android est simulé par `v22-bridge.js`; ce test ne valide pas Android.
