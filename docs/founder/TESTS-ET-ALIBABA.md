# Livraison de test — 7 octobre 2026

Deux aperçus indépendants : AIV Free Test (`com.allinvisible.aiv.free.test`) et AIV Founder Test (`com.allinvisible.aiv.founder.test`). Nouvelle clé commune de test, différente de la clé propriétaire. Données et identités Android séparées. Installer via Android normalement sans désinstaller AIV actuel. Les autorités Shizuku/installation sont distinctes pour éviter une collision de ContentProvider.

Founder Test affiche les pages complètes mais ne possède aucune licence payée. L'affichage des pages n'active pas le contrôle : `verifiedTier` et `ControlShell` exigent toujours une preuve réelle. Ne pas activer son VPN pour regarder l'interface : Android ne permet qu'un VPN actif par profil. Les pages réseau restent vides tant que ce test n'a pas collecté ses propres données ; aucun exemple fabriqué n'est injecté.

Les deux éditions publiques gardent un seul package et une même clé de distribution entre elles. Ces deux aperçus ne sont pas leur canal de mise à niveau.

## Console propriétaire

`OwnerConsole` configure uniquement l'URL HTTPS du serveur, chiffrée par Android Keystore. Elle réutilise la clé locale et signe un challenge pour `/owner/licenses`. Le serveur n'autorise que `OWNER_KEY_ID`, configuré hors APK. Liste : numéro, licence, empreinte de clé, statut, création/confirmation. Compteurs de ventes confirmées, actives, révoquées et réservées distincts. Pas de données du téléphone, noms ou adresses courriel PayPal.

Build `OWNER_INTERNAL` facultative : même package et certificat que le propriétaire existant, versionCode 229. Réserve le contrôle local TI au propriétaire, comme sa base 224. Cette build n'est pas une édition commerciale et ne doit pas être distribuée aux acheteurs. Les modes FREE/FOUNDER ne peuvent pas activer ce bypass. Le build refuse un certificat différent de celui de l'APK propriétaire témoin. Une installation constitue une mise à jour de l'app propriétaire, contrairement aux deux aperçus ; elle n'est pas nécessaire pour examiner les aperçus. Exporter les données avant cet essai : installation réelle/migration sur téléphone non vérifiée.

## Alibaba Cloud

Paquet Docker Compose à déployer sur une ECS avec disque persistant. `deploy/alibaba/compose.yaml` : site AIV, serveur de licences, assistant Supabase distinct, HTTPS via Caddy. Aucune ressource Alibaba créée ni facturée par cette livraison. Aucun accès Alibaba disponible pour le déploiement.

1. Créer/choisir ECS Linux avec Docker Compose et stockage persistant. Configurer trois sous-domaines DNS : site, licence, assistant. Autoriser TCP 80/443 ; ne pas exposer directement 8080/8081.
2. Copier `domains.env.example` en `domains.env`, puis configurer les trois domaines réels.
3. Copier `licensing/.env.example` en `deploy/alibaba/license.env`. Renseigner PayPal Sandbox client ID, client secret, merchant ID, webhook ID, PUBLIC_BASE_URL. Ne jamais committer ces fichiers. `OWNER_KEY_ID` = empreinte affichée par la console de l'app propriétaire. Ne pas confondre celle d'un aperçu indépendant avec celle du propriétaire.
4. Créer `deploy/alibaba/private/license-signing.pem` (RSA 3072) hors Git, droits 600. Exporter uniquement la clé publique SPKI pour compiler les éditions publiques. Conserver la clé privée et sauvegarder le volume `license_state` par sauvegarde cohérente SQLite ; les APK n'ont aucune clé privée serveur.
5. Copier `setup.env.example` en `setup.env`. Enregistrer une intégration OAuth Supabase avec callback exact `https://DOMAINE_ASSISTANT/callback`, scopes projets read, database write, auth read/write et secrets read (API keys). La portée secrets read de Supabase est plus large que les seules publishable keys : elle est annoncée par l'écran d'autorisation ; l'assistant ne renvoie jamais une clé secrète à Android. Le client secret OAuth reste côté serveur.
6. Depuis la racine : `docker compose -f deploy/alibaba/compose.yaml config`, puis `docker compose -f deploy/alibaba/compose.yaml up -d --build`. Vérifier HTTPS et le callback avant utilisation. Docker/images/connexion réseau non vérifiés ici.
7. Enregistrer les événements PayPal documentés dans CONFIGURATION.md sur `https://DOMAINE_LICENCE/webhook`. Tester réellement Sandbox avant live. La même DB ne peut pas mélanger sandbox/live ou marchands.
8. Ajouter seulement des APK publics vérifiés dans `deploy/alibaba/releases`, créer founder.json avec URL officielle, SHA256 et version_code. Recompiler FREE/FOUNDER avec les URLs et clé publique du serveur. Le site fourni reste en pré-lancement, téléchargement et vente désactivés : ne pas y afficher des liens invalides ni vendre avant ces contrôles.

## Assistant Supabase automatique

L'utilisateur crée son propre projet, ouvre « Connecter Supabase », autorise l'intégration et choisit le projet. Après confirmation explicite, l'assistant exécute le SQL fixe v1, vérifie RLS, active les connexions anonymes et produit `AIV-Supabase-configuration.json`. Aucun SQL arbitraire fourni par le navigateur. Un schéma AIV existant n'est jamais écrasé. Un projet hors liste autorisée est refusé.

AIV importe ce fichier (uniquement URL + publishable key + version de format), affiche le projet pour confirmation, puis exécute ses tests réels lecture/écriture/version. L'utilisateur active ensuite volontairement la sauvegarde. L'import évite la copie manuelle de la clé ; le retour automatique du navigateur vers l'app n'est pas encore implémenté. Dans Founder Test, les commandes de sauvegarde restent verrouillées sans licence réelle.

L'assistant est séparé du backend de licences, n'a aucune route pour recevoir un journal, n'enregistre pas de tokens OAuth sur disque, ne conserve pas le refresh token. Sessions temporaires 10 min, PKCE S256, state, cookie sécurisé HttpOnly, protection Origin et CSRF, callback à usage unique. L'accès temporaire de gestion ne doit jamais servir à lire les journaux. Les permissions doivent être révoquées depuis Supabase après configuration si l'utilisateur le souhaite. Les journaux sont ensuite envoyés directement au Supabase personnel. CAPTCHA éventuel n'est jamais désactivé ; le test Android peut donc demander une adaptation avant réussite.

## Purge — non activée

L'upload vérifié d'un segment de 50 000 ne déclenche aucune suppression. `AivStore.verify` exige actuellement la présence des événements/projections dans SQLite. Une simple suppression après HTTP 200, ou même après confirmation distante, casserait l'intégrité et certains rapprochements locaux. La purge nécessite un modèle de segments archivés/ancres et restauration à tester séparément. Elle ne doit pas supprimer l'application ni ses clés/réglages.

## Vérification

Tests locaux : modes d'accès publics et aperçu, signature des APK v2/v3, packages/autorités distincts, bibliothèques natives identiques à 224, 10 tests serveur (dont 999/1000/1001 et console propriétaire), 3 tests assistant avec réponses simulées (state/rejeu/expiration, projet autorisé, refus d'écrasement, filtrage secrets/RLS). Les résultats réels sont joints dans la livraison.

Non vérifiés : installation/affichage/VPN/upgrade sur téléphone ; OAuth Supabase réel ; API de gestion et CAPTCHA ; connexion distante ; PayPal Sandbox réel ; Docker Compose/images/HTTPS sur ECS ; purge/restauration/rotation d'identité. Les tests simulés ne valent pas ces validations.
