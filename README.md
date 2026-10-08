# 3AI — assistant Android indépendant

![Logo 3AI](assets/branding/treeai-logo.jpg)

**3AI** est le nom public choisi par Erick le 8 octobre 2026. **Jarvis** est le nom personnel de l’assistant, modifiable dans les réglages. Le logo original est conservé.

Branche : `jarvis-standalone`. Paquet Android : `fr.erick.threeai`. Version : `0.1.1`. Android 8 ou ultérieur. Application native Java, indépendante des services de collecte et du CRM/licences d’All In Visible.

## Utilisation

1. Installer l’APK signé fourni dans la conversation.
2. Dans **Réglages**, coller sa clé Ollama existante, enregistrer, puis tester. Valeurs initiales : `https://ollama.com/v1` et `gemma4:31b`.
3. Dans **Mémoire**, coller son texte personnel et enregistrer. La mémoire reste dans les données privées de l’application, séparée du prompt et des conversations. Exporter une copie avant de désinstaller ou de vider les données Android.
4. Dans **Chat**, utiliser le texte, joindre une image ou appuyer sur **Parler**. Autoriser le microphone. La lecture des réponses utilise une voix française Android. L’option de conversation continue envoie la transcription puis reprend l’écoute après la lecture.
5. Dans **Accès**, autoriser Shizuku et lancer le diagnostic. Ouvrir AIV ou importer un rapport; le texte importé reste un brouillon jusqu’à son envoi explicite.

Aucun gros modèle n’est installé sur le téléphone. Le modèle reçoit les messages et le bloc mémoire si son utilisation est activée. Les clés sont chiffrées par Android Keystore et associées à l’adresse exacte de l’API. Aucun secret GitHub n’est intégré dans l’APK.

## Écrans et limites

- **Chat** : texte, une image par requête, dictée, lecture vocale, conversation continue, export JSON et nouveau chat.
- **Mémoire** : texte éditable jusqu’à 2 Mo, import/export, conservation locale et boutons de synchronisation Supabase à configurer.
- **Réglages** : nom, prompt, température, tokens, contexte estimé, fournisseur, URL, modèle et clé.
- **Accès** : Shizuku, ouverture d’AIV, import/partage de rapports, contacts et agenda autorisés par Android.

Historique local limité à 1 000 messages ou 5 Mo. Les 80 derniers messages sont affichés; jusqu’à 40 messages sont envoyés au modèle. La mémoire est envoyée intégralement: si le budget estimé est dépassé, la requête est refusée sans couper le texte. Le serveur peut imposer ses propres limites. Les images restent jointes à leur requête actuelle, pas archivées dans le chat.

La voix utilise les services Android, avec reconnaissance sur appareil privilégiée si disponible. Un service vocal Android peut utiliser son cloud. Les fonctions vocales OpenAI ne sont pas utilisées.

Shizuku permet ici une connexion réelle à un UserService et un diagnostic limité à `id` et deux propriétés système. Cette version ne donne pas au modèle l’exécution de commandes arbitraires. Le niveau d’accès dépend du démarrage Shizuku (ADB ou root). L’ouverture d’AIV et l’import d’un rapport ne sont pas un accès direct à ses données privées.

## Changer de modèle

Modifier **URL de l’API**, **Modèle** et la clé correspondante dans les réglages, enregistrer puis tester. La mémoire locale reste intacte.

Ollama Cloud utilise `https://ollama.com/v1`. Alibaba Model Studio demande son URL compatible OpenAI de région et sa propre clé. Un serveur ECS doit d’abord héberger une API Chat Completions compatible OpenAI, protégée par HTTPS et authentification. Le choisir dans l’application ne déploie pas le serveur.

Pas de clé Ollama implicitement envoyée à une autre adresse, de redirection HTTP, de repli automatique sur un autre modèle ou d’achat de crédits. Chaque fournisseur/modèle doit être vérifié pour les images, les limites et les paramètres pris en charge.

## Supabase — préparé, pas déployé

`supabase/setup.sql` prépare deux stockages séparés dans un **nouveau projet dédié à 3AI** : `threeai_memory` et `threeai_messages`. Accès par utilisateur avec RLS, connexion Supabase Auth et révision optimiste de la mémoire. Le code Android possède les opérations de connexion, envoi mémoire/conversations et lecture de la mémoire.

Le projet 3AI n’est pas encore créé, son schéma n’est pas appliqué et cette synchronisation n’est pas testée de bout en bout. Le projet AIV existant n’est pas utilisé. Dans cette version, la mémoire et les conversations restent locales; les copies locales sont conservées après un envoi manuel. Aucun ménage automatique du journal SQLite AIV n’est effectué.

## Compilation et vérification

Projet : `android/`. Gradle 8.9, AGP 8.7.3, Java 17 pour compiler, compile/target SDK 35. Dépendances Shizuku 13.1.5. Workflow `.github/workflows/threeai-android.yml` : compilation release/debug, APK de test, cinq tests instrumentés Android 15 et captures des quatre onglets. L’artefact public contient l’APK **non signé** et l’outil officiel de signature. La clé privée de signature est conservée séparément et n’est jamais publiée.

Les tests couvrent une mémoire de 270 000 caractères après recréation, le changement de modèle sans perte de mémoire, le chiffrement d’une clé de test, le partage d’un rapport comme brouillon et le refus d’un budget insuffisant. Les fonctions vocales et Shizuku doivent encore être essayées sur le téléphone réel.

## Ollama : procédure et preuve distante

Documentation lue avant intégration : [index](https://docs.ollama.com/llms.txt), [cloud](https://docs.ollama.com/cloud.md), [introduction](https://docs.ollama.com/api/introduction.md), [authentification](https://docs.ollama.com/api/authentication.md), [compatibilité OpenAI](https://docs.ollama.com/api/openai-compatibility.md).

Le secret `OLLAMA_API_KEY` existe dans l’environnement GitHub **jarvis**, réservé à cette branche. Il sert au test Python; GitHub ne le rend pas lisible pour préconfigurer l’APK. Aucune nouvelle création de secret n’est nécessaire.

Le [test Gemma du 8 octobre](https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37803839584), commit `4bf02fa8c9c4b11e49fc61b98e8e8d16699eadfc`, a reçu **TREEAI_OK**, 24 tokens d’entrée et 5 de sortie. Huit tests Python ont réussi. Cette preuve concerne le connecteur texte vers Ollama, pas un essai de chat depuis le Samsung. Un test antérieur DeepSeek avait reçu HTTP 402; aucun achat ni modification de facturation n’a été effectué.

Le connecteur Python `server/model_client.py` garde les variables historiques `TREEAI_BASE_URL`, `TREEAI_MODEL` et `TREEAI_KEY_ENV` pour éviter de casser le workflow. L’application personnelle actuelle appelle directement l’API avec la clé saisie sur l’appareil. Une distribution commerciale devra utiliser un service protégeant les clés partagées; le secret du propriétaire ne doit pas être embarqué dans un APK distribué.

## Vérification de l’APK 0.1.0

[Build et tests Android réussis](https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37809436292), source `84f154a3d3c4cbeccf589bd131390f614f7c2797` : quatre tests instrumentés, zéro échec, captures des quatre onglets. APK signé fourni dans la conversation : 414 341 octets, signature v2/v3 vérifiée. SHA-256 : `34ccc66384c444948b7bc6e26d5fd044b1ad54a914169f8d4f6403046026b671`.

## Mise à jour 0.1.1 — récupération des accès

Même paquet et certificat que 0.1.0, versionCode 2. Onglet Accès : microphone, contacts et agenda affichés même sans Shizuku; demande des droits manquants et guide vers la réparation dans AIV Owner 2.3.3. Une mise à jour ne réaccorde pas seule les droits refusés.

[Build et tests Android 15](https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37817492047), commit 88a54357b3daf7e45be1a0a5933259f62f4e0e78 : cinq tests instrumentés, aucun échec, captures des quatre onglets. Signature de mise à jour vérifiée en privé. APK SHA256 : cdafc786a4bf8fecb4ebdc8189c308570a08d1d82f930e3a9af6af17cf64d77b. Téléphone personnel non testé.
