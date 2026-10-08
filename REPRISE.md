# Reprise — Jarvis indépendant — 8 octobre 2026

Erick demande une branche dédiée et un APK Jarvis séparé d'All In Visible.

Étape actuelle : créer la branche et fournir le lien afin qu'Erick configure OLLAMA_API_KEY dans l'environnement GitHub jarvis, limité à jarvis-standalone.

Ensuite : intégration Ollama minimale selon la documentation officielle, test authentifié borné, puis construction de Jarvis et connexion à un projet Supabase distinct pour les conversations et la mémoire éditable par Erick.

Le journal AIV et son problème de volume ne sont pas la priorité actuelle. Le CRM/licences/PayPal reste dans AIV. La séparation demandée n'autorise pas à écraser les données, les signatures ou les branches AIV existantes.

Le projet AIV Android App existant (poqahjwwjcidznxmyquv) refuse les connexions SQL lors des derniers relevés. Il ne doit pas être supposé utilisable ou choisi comme mémoire Jarvis.

Aucun secret n'est fourni à ce point. GitHub Actions injecte les secrets dans ses jobs; il ne les rend pas lisibles à l'assistant et ne fournit pas à lui seul un backend permanent.
