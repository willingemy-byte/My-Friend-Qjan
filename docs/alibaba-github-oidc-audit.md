# AIV - Connexion securisee GitHub -> Alibaba Cloud

**Etat : preparation uniquement.** Cette branche ne relie aucun compte, ne demarre aucun serveur et ne deploie pas le Main.

## Ce qui est deja verifie

- Depot AIV actuellement accessible : `willingemy-byte/My-Friend-Qjan` (public, branche `main`).
- Une partie des sources Android AIV existe deja dans ce depot. Le prototype de backend `All-In-Visible-Fondation-v0.1.zip` livre dans ChatGPT **n'est pas present dans ce depot** au moment de cette preparation.
- La connexion "Se connecter avec GitHub" de la console Alibaba **ne donne pas** a GitHub Actions le droit d'utiliser les API du compte Alibaba.
- OSS = stockage objet, **pas** methode de delegation d'identite. Pour connecter un workflow GitHub a Alibaba, utiliser **OIDC + RAM + STS**, avec identifiants temporaires et sans AccessKey permanente.
- L'utilisateur RAM `Jarvis` deja cree reste **distinct** du role temporaire GitHub. Ses nombreuses permissions ne doivent pas etre recopiees.

## Premier objectif : connexion d'audit, zero permission d'ecriture

Ce premier test appelle uniquement `GetCallerIdentity`. Alibaba indique que cette operation ne demande pas d'autorisation RAM supplementaire. **Ne rattacher aucune politique d'acces au role pour cette premiere verification.**

### 1. Alibaba Cloud : creer l'Identity Provider GitHub

Depuis un compte disposant des permissions d'administration RAM :

1. Aller dans **Resource Access Management (RAM)**.
2. Ouvrir **Integrations > SSO > Role-based SSO > OIDC > Create IdP** (les libelles exacts peuvent varier).
3. Nom : `aiv-github`.
4. Issuer URL : `https://token.actions.githubusercontent.com`.
5. Client ID / Audience : `sts.aliyuncs.com`.
6. Recuperer le *fingerprint* TLS avec le bouton fourni par Alibaba, puis enregistrer.

Reference : https://www.alibabacloud.com/help/en/ram/manage-an-oidc-idp

### 2. Alibaba Cloud : creer un role d'audit sans permissions

1. Dans RAM : **Identities > Roles > Create Role**.
2. Trusted Entity : **Identity Provider / OIDC** ; choisir `aiv-github`.
3. Nom du role : `aiv-github-audit`.
4. Dans l'editeur de politique de confiance (*trust policy*), limiter la federation au bon issuer, a la bonne audience et **au sujet exact du depot GitHub et de l'environnement** :

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "acs:ram::<ALI_CLOUD_ACCOUNT_ID>:oidc-provider/aiv-github"
      },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": {
          "oidc:iss": "https://token.actions.githubusercontent.com",
          "oidc:aud": "sts.aliyuncs.com",
          "oidc:sub": "repo:willingemy-byte@318904722/My-Friend-Qjan@1366580336:environment:alibaba-audit"
        }
      }
    }
  ]
}
```

Remplacer `<ALI_CLOUD_ACCOUNT_ID>` **dans la console Alibaba**, pas dans le depot, par le numero de compte. Ce numero ne constitue pas une cle.

**Important :** le depot est date du 11 septembre 2026, donc soumis par defaut au format GitHub OIDC **immutable** (ID proprietaire et ID depot). Si la configuration OIDC du depot a ete personnalisee, verifier le `sub` reel et l'adapter avant toute autorisation. Ne pas remplacer `oidc:sub` par un joker `*`.

**Ne rattacher aucune politique `Aliyun*FullAccess`, ni meme de lecture, pour ce premier test.**

References :
- https://www.alibabacloud.com/help/en/ram/user-guide/create-a-ram-role-for-a-trusted-idp
- https://docs.github.com/en/actions/reference/security/oidc

### 3. GitHub : creer un environnement restreint

Dans `willingemy-byte/My-Friend-Qjan` :
- **Settings > Environments > New environment** : `alibaba-audit`.
- Restreindre les branches autorisees a `main` si l'option est disponible.
- Ajouter une validation humaine (*required reviewers*) si compatible avec les droits du depot.
- Les autres workflows AIV ne doivent jamais reutiliser ce role.

**Ne pas creer d'environnement nomme autrement** : il fait partie du `sub` OIDC.

### 4. GitHub : renseigner deux variables (pas de cle ni mot de passe)

Dans **Settings > Secrets and variables > Actions > Variables**, creer :

- `AIV_ALIBABA_OIDC_PROVIDER_ARN` = `acs:ram::<ALI_CLOUD_ACCOUNT_ID>:oidc-provider/aiv-github`
- `AIV_ALIBABA_AUDIT_ROLE_ARN` = `acs:ram::<ALI_CLOUD_ACCOUNT_ID>:role/aiv-github-audit`

Ces deux identifiants de ressources ne sont pas des mots de passe. **Ne jamais creer ni transmettre d'AccessKey**, de secret ou de token permanent pour cette connexion.

### 5. Lancer le test, apres revision et fusion volontaire de cette PR

- Dans GitHub : **Actions > AIV - Test de connexion GitHub vers Alibaba (OIDC) > Run workflow** (branche `main`).
- Le test assume le role temporaire avec l'action officielle `aliyun/configure-aliyun-credentials-action`, puis verifie `GetCallerIdentity`.
- Sortie attendue : `AIV: connexion OIDC verifiee avec le bon role temporaire.`
- Si une etape echoue, conserver le role sans permissions et verifier la correspondance des trois champs `iss`, `aud`, `sub`.
- Ce test n'a **aucun droit de provisionner des ressources**.

## Audit de l'utilisateur RAM "Jarvis" existant

Depuis la console Alibaba : **RAM > Identities > Users > Jarvis > Permissions**, examiner les politiques directes **et heritees de groupes**. Une liste de 48 politiques ne prouve pas en soi les droits effectifs. Photographier ou recopier **les noms des politiques seulement** et, si necessaire, leur perimetre. Masquer mots de passe, AccessKeys et tokens. Un utilisateur RAM n'est pas un role STS.

Apres lecture des droits : creer separement un role de consultation de ressources cloud; un role de deploiement de **staging** borne aux seuls services AIV autorises; **aucun acces a la modification du Main**; mises a jour du Main uniquement via procedure de publication et signatures gerees par l'ordinateur administrateur hors ligne. Une politique RAM ne garantit pas a elle seule cette derniere separation.

## Etapes suivantes, pas dans cette PR

1. Audit RAM de Jarvis (politiques directes / groupes / exclusions).
2. Isolation du Main et du Journal : idealement compte ou perimetre cloud distinct, politiques explicites et verification des cles.
3. Test de permissions lecture seule sur ECS/OSS/ACK, sans fichiers personnels.
4. Deploiement de la fondation AIV sur environnement d'essai, **apres** audit des scripts, couts et secrets.
5. Identites cryptographiques par appareil / licence, journaux prives par proprietaire, accusé d'archivage avant purge locale.

Aucun flux client, aucune cle privee, aucune autorisation administrative n'est publie dans GitHub.
