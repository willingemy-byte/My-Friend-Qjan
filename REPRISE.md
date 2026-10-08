# Reprise — TreeAI indépendant — 8 octobre 2026

Erick demande une branche dédiée et un APK TreeAI séparé d'All In Visible.

Étape actuelle : créer la branche et fournir le lien afin qu'Erick configure OLLAMA_API_KEY dans l'environnement GitHub jarvis, limité à jarvis-standalone.

Ensuite : intégration Ollama minimale selon la documentation officielle, test authentifié borné, puis construction de TreeAI et connexion à un projet Supabase distinct pour les conversations et la mémoire éditable par Erick.

Le journal AIV et son problème de volume ne sont pas la priorité actuelle. Le CRM/licences/PayPal reste dans AIV. La séparation demandée n'autorise pas à écraser les données, les signatures ou les branches AIV existantes.

Le projet AIV Android App existant (poqahjwwjcidznxmyquv) refuse les connexions SQL lors des derniers relevés. Il ne doit pas être supposé utilisable ou choisi comme mémoire TreeAI.

Aucun secret n'est fourni à ce point. GitHub Actions injecte les secrets dans ses jobs; il ne les rend pas lisibles à l'assistant et ne fournit pas à lui seul un backend permanent.

## Identité visuelle adoptée

Nom public : TreeAI. Logo original : assets/branding/treeai-logo.jpg, conservé sans modification, avec 3AI dans le dessin. Branche et secret restent configurés selon les noms techniques existants. Aucun APK n'a encore été généré.

## Avancement confirmé après configuration du secret

L'utilisateur a ajouté le secret et limité l'environnement à une branche. Le workflow a effectivement reçu OLLAMA_API_KEY, masquée dans les logs. La requête Ollama Cloud avec le modèle deepseek-v4-pro:0813 a reçu HTTP 402. Run 37797282192; commit 780b495428afd49f9559e8c9bb103842f68a9708. Il ne faut plus demander à l'utilisateur de refaire la configuration GitHub : le blocage suivant est le refus Ollama. Sa cause financière exacte reste à confirmer; aucun achat n'est autorisé automatiquement.

Le connecteur natif Python standard-library et son test sont publiés. Trois contrôles locaux passent. Aucune génération confirmée du modèle, aucun APK et aucune mémoire Supabase à ce point.
