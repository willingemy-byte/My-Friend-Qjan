# All In Visible — GitHub Actions + Alibaba Cloud ACK (prototype uniquement)

**Statut : squelette GitHub, aucun cluster connecté ni déployé.** Aucun service ne reçoit de journal, et aucune clé ni donnée personnelle n'est autorisée dans ce dépôt public.

## Rôle des outils

- **GitHub** : contrôle du code, revue, tests et déclenchement des déploiements autorisés.
- **GitHub Actions** : jobs de validation sur runners **hébergés par GitHub** pendant le prototype. Il ne s'agit pas d'un service Kubernetes hébergé par GitHub.
- **Alibaba Cloud ACK** : futur cluster Kubernetes du bac à sable, une fois créé et sécurisé.
- **OIDC GitHub → RAM → STS Alibaba** : accès temporaire, restreint à un rôle et un environnement GitHub, sans AccessKey persistante. Voir le PR #35 (audit d'identité uniquement, non déployé).
- **OSS** : futur stockage objet chiffré pour une sauvegarde privée expressément choisie. OSS n'est ni une gestion d'identité ni un système d'anonymisation.
- **ARC (Actions Runner Controller)** : option future permettant d'exécuter des runners Actions **dans** Kubernetes. Ne pas activer avec ce dépôt public; utiliser exclusivement un dépôt privé, des runners éphémères isolés, et des permissions étroites.

## Prototype livré ici

`kustomization.yaml` agrège quatre ressources Kubernetes neutres : namespace `aiv-prototype`, politique réseau fermée par défaut, quota limité et ServiceAccount sans montage de jeton. Ce sont **des bases à auditer**, pas une sécurité complète. La politique réseau suppose un CNI compatible sur ACK. Il n'existe pas encore de service métier, de coffre, d'identité AIV ni de connexion Alibaba dans ces ressources.

Le workflow `.github/workflows/aiv-ack-prototype-validation.yml` ne fait que **rendre les manifests localement** via `kubectl kustomize` et vérifier des garde-fous; il n'appelle aucune API Alibaba, n'exécute pas `kubectl apply`, et ne demande pas de jeton OIDC. Les tests statiques ne vérifient pas l'application réelle des règles par un cluster.

## Séparation demandée (cible, non implémentée)

1. **Main déterministe** : code immuable à l'exécution pour Watcher; identité de déploiement distincte, signature et autorisation hors du périmètre Watcher. Idéalement compte/projet cloud séparé.
2. **Journal utilisateur** : coffre individuel privé, données originales chiffrées **avant** la sortie du téléphone. Les clés restent sous le contrôle de la personne; restaurabilité vérifiée. Jamais dans l'historique Git.
3. **Nettoyage local** : pipeline à liste blanche appliqué par Main; vérification de non-réidentification. Le simple retrait des noms ne suffit pas.
4. **Statistiques AIV** : compte/namespace d'analyse séparé, sans `USER_ID` ni `AIV_ID` dans les exports collectifs anonymisés.
5. **Watcher** : lecture des seules données autorisées, aucune permission RAM/Kubernetes d'administration de Main, aucun accès aux clés de déchiffrement.

## Ordre de connexion

1. Conserver ce prototype dans `willingemy-byte/My-Friend-Qjan` **sans** journaux réels ni secrets.
2. Valider l'OIDC **audit seulement** du PR #35 dans un environnement GitHub protégé.
3. Dans Alibaba, créer et examiner ACK pour le bac à sable, sa facturation, le réseau, RAM, quotas et l'isolation. Ne pas le créer depuis un rôle de lecture ni accorder `FullAccess`.
4. Après audit, fournir un rôle OIDC **distinct pour le déploiement bac à sable**; restriction à ce dépôt, à l'environnement protégé, et à la seule ressource approuvée.
5. Ajouter une étape de déploiement manuel avec approbation. Tester accès non autorisés, isolation réseau, restauration et journaux de sécurité.
6. Organiser la migration du dépôt dans l'organisation de l'entreprise **après** création/validation des droits. Le transfert peut changer les claims OIDC; la trust policy doit alors être mise à jour avant tout nouveau déploiement.
7. Ajouter plus tard l'adresse électronique professionnelle, le domaine et les sauvegardes privées indépendantes de GitHub.

**Attention à l'urgence du stockage téléphone :** ce prototype ne réduit pas l'utilisation de l'appareil. Il ne peut pas accéder au stockage privé d'une APK. Aucun effacement de l'appareil tant que les données complètes n'ont pas été sauvegardées, vérifiées et restaurées à blanc.

## Références

- https://docs.github.com/en/actions/concepts/runners/actions-runner-controller
- https://docs.github.com/en/actions/reference/security/secure-use
- https://www.alibabacloud.com/help/en/ram/user-guide/ram-role-overview
- https://docs.github.com/en/repositories/creating-and-managing-repositories/transferring-a-repository
