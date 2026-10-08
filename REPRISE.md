# Reprise — 3AI indépendant — 8 octobre 2026

Dernière décision utilisateur : produire **3AI**, assistant personnel **Jarvis**, APK complètement séparé d’AIV. Logo original 3AI conservé. Onglets Chat, Mémoire, Réglages, Accès. Mémoire personnelle éditable localement; pas de texte personnel publié.

Branche jarvis-standalone, environnement jarvis et OLLAMA_API_KEY existent. Le test Python Gemma réel a réussi (run 37803839584, réponse TREEAI_OK, 24/5 tokens). Aucun achat ou changement de facturation.

## Android construit

Code Java dans android/, paquet fr.erick.threeai, version 0.1.0, min SDK 26, cible 35. Chat via API compatible OpenAI, modèle gemma4:31b, URL https://ollama.com/v1. La clé existante doit être collée une fois dans les réglages du téléphone; elle est chiffrée par Android Keystore, liée à l’adresse de l’API et absente du build.

Mémoire privée memory.txt (2 Mo), conversations privées conversations.json (5 Mo/1 000 messages), prompt distinct. Import/export via sélecteur Android. Pas de suppression de journal SQLite AIV. Voix avec SpeechRecognizer/TTS Android; conversation continue reprend après la dernière portion TTS. Stop désactive le mode continu. Pas d’API vocale OpenAI.

Shizuku 13.1.5 : autorisation et UserService AIDL avec diagnostic fixe (id, modèle appareil, version Android), sans commandes autonomes du modèle. AIV actuel est com.allinvisible.aiv; ouverture et import/partage de texte/JSON vers le brouillon. Ancien paquet fr.erick.journallocal prévu en secours. Contacts et agenda lisibles après permissions Android, ajout au chat explicite.

## Vérification et signature

Le premier build compilé a réussi les quatre tests instrumentés Android 15 (run 37808469010), puis la capture a échoué parce que connectedDebugAndroidTest désinstalle le paquet. Le script QA réinstalle maintenant app-debug.apk avant les captures. Les fichiers de release et de debug sont construits sans secrets; les artefacts publics sont non signés.

La clé de signature propre à 3AI est créée et conservée en privé hors Git. Le ZIP privé de sauvegarde contient la clé et son mot de passe; ne jamais le publier. Les mises à jour de fr.erick.threeai devront réutiliser cette même signature et augmenter versionCode. Cette signature est indépendante d’AIV.

## Supabase restant

Seul le projet AIV existe; aucun projet 3AI créé. Ne pas utiliser AIV pour la mémoire 3AI. supabase/setup.sql prépare threeai_memory et threeai_messages avec RLS et révision optimiste; CloudMemory.java contient Auth/synchronisation manuelle. Schéma, connexion et politiques ne sont pas encore vérifiés sur un vrai projet. Aucune purge automatique locale. La création d’un projet payant demande organisation et coût confirmés selon le plugin.

La voix, le diagnostic Shizuku, le partage depuis la version installée d’AIV et le chat depuis le Samsung restent à vérifier sur appareil. Alibaba/ECS n’est pas déployé. Changer les trois champs URL/modèle/clé ne modifie pas la mémoire locale.

## Artefact livré

Source APK : commit 84f154a3d3c4cbeccf589bd131390f614f7c2797. Build Android : https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37809436292. APK signé 3AI-0.1.0.apk, 414 341 octets.
SHA-256 : 34ccc66384c444948b7bc6e26d5fd044b1ad54a914169f8d4f6403046026b671.
Certificat SHA-256 : 8f8103714f539f3489136ea7eb892d11c2438cf59538196e31134984372c84ad. Signature v2/v3 vérifiée avec l’outil officiel Android; paquet/version/SDK lus dans le manifeste compilé. Le logo original est présent sans modification des octets.

Fichiers privés mis à disposition dans la conversation : APK signé, guide 3AI-INSTALLATION.txt et sauvegarde 3AI-signature-private.zip. Les artefacts GitHub restent non signés; ne pas publier la sauvegarde de signature.

Résultat final confirmé : run 37809436292 terminé avec succès, job 113422436329. Quatre tests instrumentés Android 15, zéro échec/erreur/skip, puis les quatre onglets capturés avec UI Automator. Artefacts : threeai-unsigned (11564776349) et threeai-android-qa (11563909368). Les captures sont vérifiées visuellement. Huit tests Python repassés localement avec succès.

## Mise à jour 0.1.1 livrée

Correction des états et demandes des permissions; explications vers le bouton de récupération AIV. Paquet/signature conservés, versionCode 2. Build et tests réussis : run 37817492047, job 113449736628, commit 88a54357b3daf7e45be1a0a5933259f62f4e0e78. Cinq tests instrumentés sans échec et captures vérifiées; SHA256 APK cdafc786a4bf8fecb4ebdc8189c308570a08d1d82f930e3a9af6af17cf64d77b. Certificat inchangé 8f8103714f539f3489136ea7eb892d11c2438cf59538196e31134984372c84ad.

AIV 2.3.3-owner-trio (237) construit et signé avec le certificat AIV existant, depuis la source privée Owner 236. Jarvis/Ollama retirés d’AIV, trio AIV/Shizuku/3AI protégé en permanence, bouton de récupération des trois droits avec rapport durable et identité/signature épinglées. Publication des sources AIV refusée par auto-review faute d’autorisation explicite; ne pas contourner ce blocage. Snapshot privé de sources AIV conservé avec les APK. Aucun effacement du journal 47,88 Go. Réparation sur le téléphone reste à vérifier, verrous Android/politique et UID respectés.

## Mise à jour 0.1.2 livrée

Installation de 3AI confirmée par l’utilisateur. Un gros bloc opaque empêche la saisie; le contenu exact n’a pas été fourni. Corrigé : suppression totale du brouillon avec confirmation, sans effacer mémoire/conversations; imports texte confirmés, fichiers binaires refusés, images partagées routées séparément. VersionCode 3, signature existante conservée.

Build initial b198a0e8b7f749177b1f059ed8eda32709615074 compilé, deux tests de dialogue ont échoué parce que la recherche de texte n’ignorait pas les majuscules affichées par Android. Recherche corrigée. Build source 90f220029782a003386f0475c7189ec9d47c65a5, run 37828224221, job 113486498083 : sept tests instrumentés Android 15 réussis, zéro échec/erreur/skip et captures vérifiées. APK signé 418437 octets; SHA256 7dc9f7d51219e31a5bf6b6feb6e3d494c559ed8a08dbc5ae8b692aef32081aa7. Certificat inchangé 8f8103714f539f3489136ea7eb892d11c2438cf59538196e31134984372c84ad.

Perte Shizuku en quittant Wi-Fi signalée; guide officiel et discussion mainteneur #225 lus. Aide intégrée : maintenir Débogage USB et options développeur, option de délai ADB si disponible et autorisation d’arrière-plan, puis test Wi-Fi → mobile. Ce sont des réglages à vérifier sur le Samsung, pas un correctif système confirmé. Le chat utilise Internet indépendamment de Shizuku. Aucun root, modification silencieuse du débogage ou activation d’ADB TCP 5555.
