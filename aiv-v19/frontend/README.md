# Frontend AIV

Modifier `journal.template.html`, les fichiers `style-*.css` pour le design et `script-*.js` pour le comportement d'affichage existant. `parts.json` décrit l'assemblage. Exécuter `python3 aiv-v19/tools/build_frontend.py` depuis la racine du dépôt ; le build le fait automatiquement.

`app/src/main/assets/journal.html` reste l'artefact assemblé embarqué dans l'APK, conservé pour les tests existants. Son contenu est identique avant/après cette extraction. Le chargement WebView, les restrictions réseau et la CSP restent compatibles : les ressources sont assemblées dans une seule page locale.

La couche native reste dans `app/src/main/java/fr/erick/journallocal`. Le contrat actuel est `JournalAndroid` ; la séparation en fichiers n'en modifie pas les méthodes. Une partie du calcul de score demeure dans les scripts existants : son déplacement vers un moteur métier unique est une migration suivante, à comparer aux résultats existants.

Ne placer aucun secret ni accès de signature dans le frontend.
