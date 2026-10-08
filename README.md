# TreeAI — application Android indépendante

![Logo TreeAI](assets/branding/treeai-logo.jpg)

Nom affiché : **TreeAI** — `T` majuscule, `ree` minuscules, `AI` majuscules. Le logo original conserve l'inscription stylisée **3AI**, conformément au choix d'Erick. Le nom Jarvis désignait ce projet avant ce choix de marque.

Branche de travail : `jarvis-standalone`.

Projet dédié à TreeAI, créé avec un arbre de fichiers neuf dans le dépôt My-Friend-Qjan. L'historique Git du dépôt reste accessible. Les autres branches conservent leurs fichiers.

## Première étape : clé Ollama

Ouvrir [les environnements GitHub du dépôt](https://github.com/willingemy-byte/My-Friend-Qjan/settings/environments).

1. Cliquer **New environment**, nommer l'environnement **jarvis**, puis **Configure environment**.
2. Dans **Deployment branches and tags**, choisir **Selected branches and tags**, puis autoriser la branche **jarvis-standalone**.
3. Dans **Environment secrets**, cliquer **Add secret**.
4. Nom : **OLLAMA_API_KEY**. Valeur : la clé créée dans le compte Ollama.
5. Enregistrer, puis indiquer dans la conversation : « Le secret jarvis est créé ».

Un secret GitHub est associé à un environnement ou au dépôt, pas directement à une branche. La restriction de l'environnement réserve son usage à cette branche. Les futurs jobs TreeAI devront déclarer `environment: jarvis`.

Le secret reste dans GitHub : il n'est pas copié dans un fichier public ni dans l'APK. Il sera fourni au processus de test via sa variable d'environnement. Sa création ne connecte pas automatiquement l'application Android et ne déploie pas un serveur.

## Objectif de l'application

- APK TreeAI distinct : paquet Android, données et signature propres.
- Ollama Cloud pour le modèle; aucune installation du gros modèle sur le téléphone.
- Projet Supabase dédié à TreeAI, distinct du projet de journal AIV.
- Conversations et mémoire persistante conservées séparément.
- Écran pour consulter, créer, modifier et supprimer les souvenirs.
- TreeAI peut proposer des souvenirs; l'utilisateur décide des changements à la mémoire durable.
- Connexions Android/Shizuku à développer selon les autorisations accordées sur l'appareil.
- Interface lisible, boutons cohérents, voix et images à vérifier avec le modèle et les services choisis.
- CRM, licences et PayPal gérés dans le projet AIV.

## Procédure Ollama à suivre avant l'intégration

Lire :
- https://docs.ollama.com/llms.txt
- https://docs.ollama.com/cloud.md
- https://docs.ollama.com/api/introduction.md
- https://docs.ollama.com/api/authentication.md
- https://docs.ollama.com/api/openai-compatibility.md si un connecteur compatible OpenAI est utilisé.

API native : `https://ollama.com/api`.
API compatible OpenAI : `https://ollama.com/v1`.
Authentification : `Authorization: Bearer` avec `OLLAMA_API_KEY`.
Vérifier le nom exact du modèle dans https://ollama.com/api/tags avant de faire le test.

Ne pas commencer l'intégration authentifiée si la clé est absente. Après ajout du secret, préparer un test borné, effectuer une requête et conserver la réponse réelle. Un test GitHub Actions terminé ne constitue pas un serveur permanent pour l'APK.

## État au 8 octobre 2026

Le connecteur serveur et un test authentifié sont publiés. Aucun nouvel APK, projet Supabase TreeAI ou backend permanent n'est encore réalisé.

La branche technique `jarvis-standalone` et l'environnement `jarvis` gardent leurs noms pour poursuivre la configuration déjà commencée. Le nom de l'application sera TreeAI.

## Premier test distant — 8 octobre 2026

Connecteur natif minimal : server/ollama_client.py. Test borné : tools/test_ollama.py. GitHub Actions : .github/workflows/treeai-ollama-test.yml.

Le secret OLLAMA_API_KEY est présent dans le job et masqué. Le catalogue cloud a été lu; la requête de génération vers deepseek-v4-pro:0813 a reçu HTTP 402. Aucune réponse du modèle n'est obtenue. Vérifier l'accès au modèle, l'offre et le solde Ollama; le détail de l'erreur n'a pas été enregistré. Ne pas présenter ce test comme réussi.

Run : https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37797282192
Commit testé : 780b495428afd49f9559e8c9bb103842f68a9708

Les contrôles locaux couvrent secret absent, réponse complète et réponse interrompue. Aucun nouvel APK ou projet Supabase n'est encore créé.

## Changer de modèle ou de fournisseur

Le connecteur actif `server/model_client.py` utilise le protocole Chat Completions compatible OpenAI, avec une configuration serveur :
- `TREEAI_BASE_URL` : adresse HTTPS de l'API, sans /chat/completions.
- `TREEAI_MODEL` : identifiant exact du modèle.
- `TREEAI_KEY_ENV` : nom de la variable qui contient la clé du fournisseur.

Par défaut : Ollama Cloud, https://ollama.com/v1, gemma4:31b, clé OLLAMA_API_KEY. La capture Usage fournie par Erick confirme Gemma 4 31B dans les modèles accessibles avec ses crédits gratuits. Choix initial pour texte et images; aucune affirmation de classement universel. Aucun achat ni changement de facturation effectué.

Pour remplacer seulement le modèle Ollama, modifier TREEAI_MODEL (par exemple gpt-oss:120b pour du texte). Pour Alibaba Model Studio, fournir l'adresse compatible OpenAI de la région/espace, l'identifiant du modèle et une clé Alibaba dans une variable distincte. Pour un modèle hébergé sur ECS, installer et exposer une API compatible OpenAI protégée par HTTPS et authentification, puis fournir son adresse, son modèle et sa clé ECS. ECS ne fournit pas un modèle opérationnel par sa seule création : déploiement et dimensionnement du serveur restent à réaliser.

La clé Ollama n'est jamais implicitement réutilisée sur un autre hôte. Pas de redirection, de relance automatique ni de changement automatique vers un modèle payant. Limite de réponse configurable et contrôlée. Le connecteur ne dépend pas du stockage des conversations ou des souvenirs; il reçoit les messages préparés par le futur backend. La mémoire Supabase reste à construire séparément. Changer le modèle ne doit pas déclencher une migration/suppression de cette mémoire. Si le modèle d'embeddings change un jour, réindexer les vecteurs séparément.

L'APK utilisera le backend TreeAI avec une adresse stable. Les champs visibles Fournisseur / Modèle et le bouton Tester puis Enregistrer sont prévus pour l'interface future, pas encore implémentés. La configuration serveur peut déjà changer sans modifier le connecteur; l'application Android et l'interface ne sont pas encore construites. Les fonctions avancées (images, outils, voix) doivent être vérifiées pour chaque modèle.

Local : lancer `python3 -m unittest discover -s tests -v`. Le test distant fait une seule requête avec une réponse limitée à 128 tokens. Les variables sont injectées par GitHub Actions ou le déploiement; cet exemple .env n'est pas chargé automatiquement par le connecteur.

Documentation : https://docs.ollama.com/api/openai-compatibility et https://www.alibabacloud.com/help/en/model-studio/base-url.

## Test Gemma confirmé

Le 8 octobre 2026, run 37803839584, job 113402789845, commit testé 4bf02fa8c9c4b11e49fc61b98e8e8d16699eadfc : huit tests locaux réussis et une requête réelle réussie vers gemma4:31b via https://ollama.com/v1/chat/completions. Réponse exacte : TREEAI_OK. Usage retourné : 24 tokens d'entrée, 5 tokens de sortie. Aucun achat ni appel à un modèle hors liste gratuite. Cela confirme seulement le connecteur texte Ollama; images, APK, mémoire Supabase et Alibaba/ECS ne sont pas encore testés/déployés.

Lien : https://github.com/willingemy-byte/My-Friend-Qjan/actions/runs/37803839584
