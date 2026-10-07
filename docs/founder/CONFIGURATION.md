# Configuration et essais réels avant publication

## PayPal Sandbox

1. Dans PayPal Developer, créer une application REST Sandbox et disposer d'un compte marchand et d'un compte acheteur Sandbox distincts.
2. Sur le serveur seulement, renseigner `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_MERCHANT_ID`, `PAYPAL_WEBHOOK_ID`. Le mode par défaut est `sandbox`. Aucune valeur privée dans l'APK, Git ou la conversation.
3. Exposer le backend derrière HTTPS sur votre domaine et enregistrer `https://VOTRE-DOMAINE/webhook` dans l'application Sandbox.
4. S'abonner à PAYMENT.CAPTURE.COMPLETED, DENIED, PENDING, REFUNDED, REVERSED, CHECKOUT.ORDER.APPROVED et CUSTOMER.DISPUTE.CREATED/RESOLVED. Le backend appelle l'API PayPal de vérification du webhook, puis relit la commande/capture pour la complétion. Un litige est bloqué sans réactivation automatique; la résolution nécessite une réconciliation marchand vérifiée.
5. Configurer `PUBLIC_BASE_URL`, le chemin du manifeste de distribution et le fichier de clé RSA de signature licence (2048 bits ou plus). Exporter uniquement la clé publique SPKI base64 dans la configuration de build.
6. Faire un vrai achat Sandbox : navigateur externe → approbation → bouton de vérification AIV → capture serveur → entitlement → APK vérifié → Package Installer.
7. Tester refus, sortie du navigateur, réversion/remboursement, duplication et événements hors ordre. Les tests locaux sont des fixtures; ils ne remplacent pas ces essais.

Documentation : https://developer.paypal.com/api/orders/v2 ; https://developer.paypal.com/api/rest/webhooks/event-names/ ; https://developer.paypal.com/api/rest/reference/idempotency/ .

## Backend licence

`licensing/server.py` et `licensing/requirements.txt` : Python + cryptography 46.0.0. Installer dans un environnement isolé, fournir les variables de `.env.example`, puis lancer `python3 licensing/server.py`. Le processus écoute uniquement 127.0.0.1:8080. Le reverse proxy doit fournir HTTPS, limites de taille, délais et limitation de requêtes/challenges/créations pour protéger les réservations contre l'abus. Configurer une tâche de réconciliation des réservations VOIDED; aucun déclenchement planifié n'est installé ici.

Utiliser un seul serveur avec SQLite sur disque durable local; les transactions concurrentes de ce serveur sont testées. Plusieurs serveurs avec copies indépendantes de la base sont interdits : ils permettraient chacun 1000 places. Pour plusieurs instances, migrer vers une base transactionnelle unique avec verrouillage équivalent. Sauvegarder régulièrement la base et la clé licence hors du dépôt. À la restauration du serveur, réconcilier les commandes/captures avec PayPal avant reprise des ventes pour ne pas perdre le registre historique.

Sandbox et live ont des bases et clés de licence distinctes. Le backend refuse de réutiliser une base sous un autre mode ou marchand. Aucun serveur public, domaine ou secret PayPal n'a été configuré dans ce travail. Les APK de validation n'ont pas de serveur ni de clé publique licence configurés; le paiement est donc indisponible et l'édition Founder reste verrouillée sans entitlement.

`distribution/license-public.example.json` ne contient que des valeurs publiques : URL HTTPS licence, clé publique RSA SPKI, origine officielle APK et empreinte du certificat AIV. Compléter ces quatre valeurs puis reconstruire les deux éditions. Le fichier serveur `founder-release.json` contient seulement `url`, `sha256`, `version_code` pour l'APK officiel signé. L'origine HTTPS de l'APK doit correspondre à celle embarquée; les redirections de téléchargement sont refusées.

La clé APK attendue est le certificat AIV `3c6b7dbdaecd823b512408d6442328cde2464c8d0b074ec714858e64f5bf08ff`. Le vieux `sign-existing-apk.py` du dépôt attend une autre identité historique : ne pas l'utiliser pour ces APK. Le build existant vérifie `all-in-visible.p12`, `password.txt` et `certificate.sha256` en dehors du dépôt.

## Supabase personnel

1. Créer son projet dans Supabase. AIV fonctionne sans cette étape.
2. Exporter depuis AIV le schéma SQL v1, ou utiliser `supabase/personal/schema-v1.sql`, et l'exécuter dans SQL Editor de ce projet neuf. Le schéma est destiné à une première installation; ne pas réexécuter ce fichier sur des tables déjà créées.
3. Activer les anonymous sign-ins dans Supabase Auth. Ils créent une session authentifiée sans demander email ou mot de passe. RLS limite les lignes à auth.uid(). La seule publishable key n'a aucun droit sur les tables AIV.
4. Dans Réglages > Sauvegarde personnelle > Supabase, entrer l'URL canonique `https://PROJECT.supabase.co` et une clé `sb_publishable_...`. Secret, service_role, JWT, mot de passe et URL PostgreSQL sont refusés.
5. Enregistrer et tester : connexion Auth, RPC de schéma, lecture réelle et écriture dans une sous-transaction annulée. Le résultat fournit version et droits réellement testés. Puis activer volontairement la sauvegarde.
6. Le journal courant demeure local. Un segment scellé est envoyé en lots bornés; le serveur recalcule chaque hash et le SHA des IDs/hashes ordonnés. AIV vérifie compte + hash + état VERIFIED avant confirmation locale. HTTP 200 seul est insuffisant. Toutes les données locales sont conservées.

La session anonyme appartient à cette installation et ses jetons sont chiffrés. Après désinstallation/perte de clé, la récupération de cette session n'est pas implémentée; le propriétaire du projet conserve l'accès SQL à ses archives. Aucun changement d'identité silencieux en cas d'échec de refresh. La sauvegarde ne se reconnecte pas au projet personnel d'Erick.

Documentation : https://supabase.com/docs/guides/getting-started/api-keys ; https://supabase.com/docs/guides/auth/auth-anonymous ; https://supabase.com/docs/guides/database/postgres/row-level-security .
