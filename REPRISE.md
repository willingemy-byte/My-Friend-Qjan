# Reprise — TreeAI indépendant — 8 octobre 2026

Erick demande une branche dédiée et un APK TreeAI séparé d'All In Visible.

Branche jarvis-standalone créée; environnement jarvis limité à cette branche et secret OLLAMA_API_KEY confirmé par le premier job.

Ensuite : intégration Ollama minimale selon la documentation officielle, test authentifié borné, puis construction de TreeAI et connexion à un projet Supabase distinct pour les conversations et la mémoire éditable par Erick.

Le journal AIV et son problème de volume ne sont pas la priorité actuelle. Le CRM/licences/PayPal reste dans AIV. La séparation demandée n'autorise pas à écraser les données, les signatures ou les branches AIV existantes.

Le projet AIV Android App existant (poqahjwwjcidznxmyquv) refuse les connexions SQL lors des derniers relevés. Il ne doit pas être supposé utilisable ou choisi comme mémoire TreeAI.

GitHub Actions injecte OLLAMA_API_KEY dans ses jobs; il ne rend pas le secret lisible à l'assistant et ne fournit pas à lui seul un backend permanent.

## Identité visuelle adoptée

Nom public : TreeAI. Logo original : assets/branding/treeai-logo.jpg, conservé sans modification, avec 3AI dans le dessin. Branche et secret restent configurés selon les noms techniques existants. Aucun APK n'a encore été généré.

## Avancement confirmé après configuration du secret

L'utilisateur a ajouté le secret et limité l'environnement à une branche. Le workflow a effectivement reçu OLLAMA_API_KEY, masquée dans les logs. La requête Ollama Cloud avec le modèle deepseek-v4-pro:0813 a reçu HTTP 402. Run 37797282192; commit 780b495428afd49f9559e8c9bb103842f68a9708. Il ne faut plus demander à l'utilisateur de refaire la configuration GitHub : le blocage suivant est le refus Ollama. Sa cause financière exacte reste à confirmer; aucun achat n'est autorisé automatiquement.

Le connecteur natif Python standard-library et son test sont publiés. Trois contrôles locaux passent. Aucune génération confirmée du modèle, aucun APK et aucune mémoire Supabase à ce point.

## Décision du 8 octobre : modèle initial gratuit et migration simple

La capture Usage 11 h 41 confirme les modèles gratuits : gemma4:31b, gpt-oss:120b, gpt-oss:20b, nemotron-3-nano:30b, nemotron-3-super, nemotron-3-ultra. Le solde acheté affiché est 0 $; le quota gratuit affiche 0 % utilisé. Erick dit avoir configuré un rechargement de 5 $ avec une carte dont le paiement échouerait; cela n'est pas une preuve de crédits achetés. Ne pas modifier les paiements ou acheter des crédits.

Choix initial : Gemma 4 31B pour conversation et images. Nouveau connecteur compatible OpenAI server/model_client.py : TREEAI_BASE_URL, TREEAI_MODEL, TREEAI_KEY_ENV. Réutilisation du secret Ollama existant uniquement pour Ollama; Alibaba/ECS demandent leur propre clé et adresse explicites. Le connecteur natif antérieur reste une référence, mais tools/test_ollama.py utilise le connecteur configurable.

Huit tests locaux passent : changement de fournisseur/modèle/clé, clé absente, URL invalide, protection de la clé, redirections refusées, réponse tronquée et erreur 402 sans repli. Le push déclenche une seule nouvelle génération Gemma, limitée à 128 tokens; vérifier le résultat GitHub avant d'annoncer une réponse réelle. Interface Android, backend permanent, mémoire Supabase et serveur ECS restent à créer. Le switch de configuration est réalisé au niveau du connecteur serveur; les boutons de l'APK sont une exigence future, pas livrés.

## Test Gemma confirmé

Le 8 octobre 2026, run 37803839584, job 113402789845, commit testé 4bf02fa8c9c4b11e49fc61b98e8e8d16699eadfc : huit tests locaux réussis et une requête réelle réussie vers gemma4:31b via https://ollama.com/v1/chat/completions. Réponse exacte : TREEAI_OK. Usage retourné : 24 tokens d'entrée, 5 tokens de sortie. Aucun achat ni appel à un modèle hors liste gratuite. Cela confirme seulement le connecteur texte Ollama; images, APK, mémoire Supabase et Alibaba/ECS ne sont pas encore testés/déployés.

Lien : https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37803839584
