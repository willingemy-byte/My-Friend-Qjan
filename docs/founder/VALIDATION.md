# Validation — 7 octobre 2026

## Référence vérifiée

APK 2.2.4-final original retrouvé et conservé, SHA-256 `a020f138541d485d42f6557fc4554b8e72580682983eb775510d4933cf5c0fcc`.

Package `com.allinvisible.aiv`, versionCode 224, signature AIV `3c6b7dbdaecd823b512408d6442328cde2464c8d0b074ec714858e64f5bf08ff`. Le commit bf1e226 est embarqué. Reconstruction à partir du commit exact : même taille de DEX, seuls l'horodatage de build et les champs checksum/signature DEX diffèrent; mêmes ressources et mêmes quatre bibliothèques natives. Les métadonnées de signature changent naturellement lors de la reconstruction.

## Tests exécutés sur hôte

La suite existante `run-convergence.sh` est passée sur la baseline puis après les modifications. AppOps (49 contrôles parseur, 38 collecteur SQLite), corrélation (63), snapshots (10), encadrement (18), catalogue (80), rapport (19 + 2 cas erreur), journal SQLite, horloges, observation réseau, relais DNS/SNI natif, règles de contrôle (40), revue (36), shell/timeout, contrôle permissions (25), maintien (101), consentement (25), politiques/configuration publiques. Les tests Android emploient leurs fixtures hôtes existantes; aucune prétention de test physique.

Tests supplémentaires :

- Backend licence : 9 tests unitaires, SQLite réel et PayPal simulé. Inclut 998 licences existantes puis 24 réservations simultanées (seuls 999/1000 admis), refus 1001, idempotence commande/capture, annulation sans activation, refus paiement, identité différente, montant/devise/marchand/capture finale, webhook dupliqué, signature webhook refusée, remboursement, réversion hors ordre, preuve d'identité/rejeu et entitlement signé.
- Entitlement Java : signature RSA réelle, modification du payload, mauvaise identité, expiration, date future, révocation, audience/schema/Founder et numéro 1001 refusés.
- ProductAccess réel, avec vérificateur isolé : FREE ne peut pas obtenir le contrôle même avec preuve; Founder nécessite preuve; aperçu/palier UI et override propriétaire inopérants.
- ArchiveSync réel avec SQLite et transport contrôlé : 1674 assertions, reprise après interruption, lot idempotent, mauvais reçus, hash de 50k, progression persistante, aucune suppression. Les reçus sont séparés de ceux de l'ancien Supabase.
- Schéma SQL réellement exécuté sur PostgreSQL via PGlite 0.5.8 : probe annulé sans résidu, RLS entre deux utilisateurs, anon sans accès, RPC begin/batch/finalize, Unicode, données immuables, 50 000 événements en 500 appels batch, SHA-256 indépendant égal au reçu distant.
- Entrées BYO : 15 cas, publishable acceptée; secret, JWT/service_role et URLs d'administration refusés; HTTPS et domaine projet vérifiés.
- Compilation Java/D8 et assemblage des deux APK; signature v2/v3 et empreinte vérifiées; package/versionCode vérifiés; bibliothèques natives inchangées. Le dossier livré contient les journaux et SHA256SUMS.

## Non vérifié / non finalisé

| Élément | État réel |
|---|---|
| Installation/démarrage sur téléphone | Non testé : aucune cible Android disponible |
| VPN, collecte, attribution, flux et export physiques | Tests hôtes existants passés; réception réelle sur appareil à vérifier |
| Accessibilité/pastille, A1-A5 et présentation graphique | Logique et compilation testées; rendu/clics sur appareil non vérifiés |
| Shizuku réel dans les deux éditions | Contrôle hôte et verrouillage testés; binder/shell Android non testés |
| Installation FREE neuve | APK signé préparé; installation non effectuée |
| Upgrade FREE → FOUNDER et nouvelle FOUNDER | Compatibilité statique package/certificat/version; conservation sur appareil non vérifiée |
| Paiement PayPal Sandbox réel, refus, annulation et remboursement | Fixtures testées; compte/app/webhook Sandbox non configurés |
| Backend public / domaine officiel APK | Code/configuration fournis; non déployés |
| Configuration des APK avec serveur de licence | Valeurs publiques laissées vides; paiement indisponible, Founder verrouillé |
| Connexion Supabase hébergé, Auth/refresh et test depuis Android | Schéma PostgreSQL local passé; vrai projet personnel non configuré |
| Chiffrement Android Keystore et signature apksig depuis Android | Compilés; exécution physique à vérifier |
| Récupération après perte d'identité licence | Architecture et IDs de paiement conservés; politique/flow de restauration à définir |
| Récupération de session Supabase après désinstallation | Non implémentée; données restent dans le projet du propriétaire |
| Purge automatique | Non implémentée/activée; toutes les données locales sont conservées |
| Distribution commerciale | Non prête tant que les vérifications réelles et la configuration manquantes ne sont pas terminées |

Les résultats locaux ne remplacent pas une recette sur Android ni un paiement PayPal Sandbox de bout en bout. Les deux APK sont des builds de validation, et l'application de référence d'Erick n'a pas été installée, remplacée ou supprimée.
