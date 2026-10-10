# All In Visible — contrat des identités et données v1

**Statut : conception/prototype de format.** Aucun téléphone, serveur Alibaba, coffre privé, moteur de nettoyage ni système de licence n'est connecté par ce changement.

## But

Créer un fichier de configuration **privé, par installation**, avec des valeurs normalisées (`USER_ID`, `AIV_ID`, `POLICY_ID`, consentements), puis organiser deux circuits indépendants : sauvegarde des journaux complets chiffrés au bénéfice de la personne; et télémétrie minimisée vers les services collectifs, seulement après autorisation. Le Main déterministe applique les règles. Le Watcher suggère mais ne peut pas élargir les accès.

## Identifiants et portée

| Champ | Signification | Traitement |
| --- | --- | --- |
| `USER_ID` | Identifiant aléatoire pseudonyme de compte AIV, stable tant que le compte existe | Dossier de l'utilisateur/coffre privé; ne **jamais** l'inclure dans une exportation dite anonyme |
| `AIV_ID` | Identifiant aléatoire d'installation de l'application, renouvelable lors d'un nouvel enrôlement | Lié au coffre et à l'identité cryptographique; jamais utilisé comme nom de fichier public |
| `POLICY_ID` | Version de la politique déterministe de minimisation | Non secret, contrôlé par Main; les changements doivent être audités |
| `CONSENT` | Choix explicites de l'utilisateur, initialement tous désactivés | Révocation prise en compte; refus par défaut |
| `DESTINATIONS` | États des canaux de synchronisation | Désactivés avant enrôlement et vérification cryptographique |
| `SECURITY_STATE` | État de l'enrôlement cryptographique | `NOT_ENROLLED` dans la maquette : **aucune** autorisation de mise en production |

`ETC_VALUE` n'est **pas** un champ libre. Ajouter plutôt des champs avec un nom précis, un type validé et une utilité documentée. Le schéma interdit les propriétés inconnues.

`USER_ID` et `AIV_ID` sont des **pseudonymes, non des données anonymisées et non des secrets cryptographiques**. La clé privée doit être créée sur Android (Keystore/StrongBox lorsque disponible) et ne doit jamais figurer dans le JSON. La clé publique et sa preuve d'enrôlement seront associées ultérieurement à `AIV_ID`. Un mécanisme de récupération de clés, distinct de l'appareil, est nécessaire avant d'affirmer qu'un nouvel appareil peut restaurer un ancien coffre chiffré.

## Circuit A : journal personnel

1. AIV observe et journalise localement; conserve la source non modifiée ainsi que les preuves de signature, la chronologie et les limites.
2. AIV chiffre les segments **sur l'appareil**, avec une clé uniquement accessible à la personne/aux appareils explicitement autorisés et un mécanisme de récupération défini.
3. Le coffre **privé choisi par l'utilisateur** reçoit uniquement les segments chiffrés et un manifeste avec empreintes/numéros; l'accès doit être authentifié, borné et audité.
4. Le serveur confirme de façon vérifiable la réception **et** la persistance des segments; AIV vérifie la correspondance des empreintes et l'ordre.
5. Un nettoyage local n'est autorisé qu'après confirmation et selon une politique de rétention approuvée. Un signalement d'upload réussi **sans vérification** ne suffit jamais.

**L'utilisation de GitHub est optionnelle** pour les personnes disposant déjà d'un compte; elle ne doit pas être une condition d'installation. Ni dépôt ni branche publique ne reçoit un journal personnel. Une branche, même protégée, n'est pas une frontière de confidentialité. Pour les journaux volumineux, préférer un stockage objet privé chiffré plutôt que l'historique Git. Une authentification OAuth GitHub ne donne pas automatiquement de droits Alibaba RAM.

Si l'objectif est **aucune donnée personnelle sur Alibaba**, le coffre privé est situé hors de l'environnement Alibaba et Alibaba ne reçoit que des données agrégées correctement dépersonnalisées. Même un journal chiffré stocké sur OSS peut exposer des métadonnées (volumes, adresses IP de transfert, dates, association au compte).

## Circuit B : données collectives

1. Le moteur de minimisation **local et déterministe** est exécuté avant tout partage et utilise une liste blanche de champs.
2. Retirer les adresses IP, domaines/URL, textes, noms de fichiers, captures, UID, signatures d'appareil, `USER_ID`, `AIV_ID`, horodatages fins et données personnelles.
3. Agréger par catégories sur des périodes assez larges. La suppression de noms et la pseudonymisation ne suffisent pas pour affirmer une anonymisation; appliquer des mesures contre la réidentification (seuils, anti-corrélation, contrôles des catégories rares, revue de risque).
4. Vérifier le consentement explicite et la politique signée avant l'envoi. **Le prototype n'implémente PAS ce moteur, donc aucun envoi n'est autorisé.**
5. Le système Alibaba de statistiques ne doit pas recevoir les identifiants personnels/liaisons permettant de remonter à un individu. Séparer complètement service de licence, coffre privé, et jeux de données statistiques.

## Contrôle des autorisations

- **Main** : politiques et validation déterministes; mises à jour selon la procédure de signature du propriétaire.
- **Watcher / IA** : lecture des éléments explicitement autorisés, signalement et recommandations seulement; interdiction de modifier Main et les consentements.
- **Kubernetes** : orchestre les services mais **ne chiffre pas automatiquement** les journaux et ne rend pas un rôle sûr par lui-même. Appliquer RAM, réseau, isolation des comptes, KMS, et séparation des clés.
- **Client** : peut choisir, révoquer et exporter ses données; le refus du partage collectif n'empêche pas l'usage local AIV.
- **Build GitHub** : seulement code, schémas, exemples fictifs et tests. Pas de vraie configuration d'utilisateur, journal, secret, clé, capture ou fichier d'authentification.

## Fichiers

- `aiv-user-config.schema.json` : contrat JSON de configuration **locale privée**.
- `aiv-user-config.example.json` : exemple fictif, non enrôlé.
- `tools/aiv-user-config/generate_demo_config.py` : génération de fichier **prototype local**, excluant les secrets.
- `tools/aiv-user-config/test_generate_demo_config.py` : tests de non-écrasement, de confidentialité élémentaire et des valeurs initiales.

Le générateur ne constitue **ni un mécanisme d'authentification ni une preuve d'identité**. Une authentification cryptographique, une attestation de clé et une configuration signée doivent être ajoutées avant production.

## Étapes bloquantes avant tout effacement réel

1. Mesurer précisément les tailles `Données` et `Cache` de l'application AIV sur Android et les tailles des bases d'événements / fichiers téléchargés.
2. Définir le coffre privé **effectivement accessible**, la récupération de clé, les limites de rétention et les quotas.
3. Réaliser un export chiffré et importer un échantillon sur un appareil d'essai; vérifier le nombre d'événements, l'intégrité et la possibilité de restitution.
4. Mettre en place l'accusé de durabilité du serveur; ajouter une purge locale bornée et réversible tant que possible.
5. Enrôler une identité cryptographique réelle et vérifier les restrictions du Main / Watcher sur Alibaba.

**Aucune donnée existante du téléphone n'a été envoyée, déplacée ou effacée par ce changement GitHub.**
