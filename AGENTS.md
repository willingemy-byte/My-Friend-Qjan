# Instructions — TreeAI indépendant

- Lire REPRISE.md et README.md avant d'intervenir. Communiquer en français simple et concis.
- Travailler sur jarvis-standalone. Ce projet doit produire son propre APK, sans reprendre les services de collecte AIV.
- Respecter la procédure officielle Ollama. Lire la documentation avant d'écrire l'intégration. Si OLLAMA_API_KEY manque, demander sa configuration sécurisée.
- Ne jamais enregistrer de clé API, clé privée de signature, conversation privée ou journal personnel dans Git.
- Utiliser l'environnement GitHub jarvis pour les jobs recevant OLLAMA_API_KEY; borner les appels de test et ne jamais afficher la clé.
- Prévoir un projet Supabase distinct pour TreeAI. L'utilisateur contrôle les modifications de la mémoire persistante.
- Distinguer création, compilation, signature, test simulé, requête distante confirmée et essai Android réel.
- Ne pas annoncer un APK installé/testé sur le téléphone sans preuve.
- Ne pas fusionner cette branche dans une branche AIV dans le cadre de cette tâche.
- Ne pas lancer d'agents supplémentaires ni de boucle autonome illimitée.

- Nom affiché de l'application : TreeAI. Respecter exactement cette casse.
- Utiliser assets/branding/treeai-logo.jpg pour l'icône et l'identité visuelle. Conserver le 3AI du dessin original; ne pas le remplacer ni recréer le logo.
- L'image originale est un JPEG malgré le nom PNG reçu. Ses octets sont conservés dans le fichier .jpg.
- Les identifiants techniques jarvis-standalone et environnement jarvis restent inchangés.
