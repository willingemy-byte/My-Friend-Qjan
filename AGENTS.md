# Instructions — 3AI indépendant

- Lire REPRISE.md et README.md avant d’intervenir. Communiquer en français simple et concis.
- Travailler sur jarvis-standalone. Produire un APK indépendant, sans reprendre les services de collecte ou le CRM/licences AIV.
- Décision explicite du 8 octobre 2026 : nom public **3AI**, nom personnel de l’assistant **Jarvis**. Cela remplace le nom antérieur TreeAI.
- Conserver le logo original assets/branding/treeai-logo.jpg, avec son inscription 3AI. Ne pas recréer le logo. Le fichier reçu était un JPEG malgré son extension PNG.
- Les identifiants techniques jarvis-standalone, environnement jarvis et variables Python TREEAI_* restent inchangés.
- Lire les documents officiels Ollama avant une modification d’intégration. Le secret GitHub OLLAMA_API_KEY est déjà configuré; ne pas demander de le refaire.
- Ne jamais publier clés API, clé privée de signature, conversations privées ou journal personnel dans Git.
- Les jobs recevant OLLAMA_API_KEY utilisent environment: jarvis; borner les appels et ne jamais afficher la clé.
- La mémoire personnelle reste éditable et peut rester locale selon la dernière instruction d’Erick. Un projet Supabase distinct est préparé mais pas encore créé/déployé/testé.
- Distinguer compilation, signature, test instrumenté, requête distante et essai sur téléphone réel. Ne pas annoncer des accès Android, actions autonomes ou synchronisations sans preuve.
- Conserver la clé de signature privée pour les mises à jour du même paquet. Ne jamais remplacer ou modifier la signature d’AIV.
- Ne pas fusionner cette branche dans AIV dans le cadre de cette tâche.
- Ne pas lancer d’agents supplémentaires ni de boucle autonome illimitée.
