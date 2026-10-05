# AIV 2.0.13 — retour à 2.0.11 avec Supabase corrigé

Le plafond de 2 Gio ajouté dans 2.0.12 interrompait la collecte malgré de l’espace disponible. Il est entièrement retiré, avec tous ses contrôles d’écriture et ses encadrés. `MainActivity.java`, qui construit les écrans, est exactement identique à la version 2.0.11, commit `3823632d8e31c39d640918e87e4ae903e9681712`. La présentation, les trois catégories, les grades, le bouton Statut, les écrans Supabase et les commandes existantes reprennent cette version. Les autres collecteurs, analyses, exports et contrôles Shizuku sont également rétablis depuis 2.0.11.

Les seuls écarts dans les sources embarquées par rapport à 2.0.11 sont le numéro de version, ArchiveSync, l’appel automatique d’archive de JournalSegments et la récupération de la pause dans Continuous. Le numéro 2.0.13 permet une mise à jour par-dessus 2.0.12 avec le même certificat, sans désinstallation.

La correction Supabase est conservée: empreinte SHA-256 et compte exact exigés dans le reçu distant, progression persistée après chaque lot confirmé, reprise après interruption, relecture d’un segment déjà reçu sans retransmettre les 500 lots, et nouvelles tentatives espacées. Le serveur corrigé appelle `extensions.digest` avec un séparateur LF identique au client. Contrôle distant du 4 octobre: 2 segments, 53 563 événements reçus, tous deux VERIFIED avec égalité des empreintes.

La pause persistante de 2.0.12 est levée à la mise à jour ou à l’ouverture d’AIV. Les services reprennent selon les réglages de collecte, d’analyse et de VPN déjà enregistrés. Un arrêt explicite de l’utilisateur reste respecté. Si Android refuse le démarrage depuis le récepteur de mise à jour, une marque de reprise reste persistée pour réessayer à l’ouverture de l’application. L’ancienne notification de plafond est retirée sans empêcher la reprise si cette suppression échoue.

L’envoi Supabase sauvegarde une copie. Les journaux restent aussi sur le téléphone. Cette mise à jour n’effectue aucune purge du journal et ne prétend pas libérer l’espace des événements archivés.

## Vérification

- Comparaison Git avec 2.0.11: interface strictement identique; seulement les quatre fichiers embarqués cités ci-dessus diffèrent. Aucun StorageGuard, StorageBudget ou contrôle d’écriture lié à ce plafond ne subsiste.
- `tests/archive-sync.py`: 1 671 assertions sur le vrai ArchiveSync avec SQLite et préférences persistées; transport HTTP simulé. Reprise, perte d’accusé de réception, données reçues manquantes, faux reçus, compte exact, manifeste de 50 000 événements et traitement borné de l’arriéré.
- `tests/continuous-recovery.py`: 17 assertions sur le vrai Continuous avec préférences persistées; services Android simulés. Reprise automatique après la pause, arrêt volontaire, réglages VPN/analyse conservés, autorisation VPN requise, refus en arrière-plan, démarrage asynchrone, réouverture et panne de notification.
- Classement: 330 contrôles dont 324 comparaisons au modèle JS existant; présentation: 25; contrôleur Shizuku: 25; maintien: 101. Suites SQLite, horodatages, index, export et segments: réussies.
- Compilation Java/API 35 réussie; 52 dépréciations Android déjà présentes. Bibliothèques natives reprises sans modification. Les essais hôte ne remplacent pas une vérification sur le Samsung.

Installer par-dessus 2.0.12 puis ouvrir AIV. La collecte interrompue par la pause peut reprendre automatiquement avec les réglages existants. Le bouton habituel Statut → Démarrer la collecte locale (sans VPN) reste disponible si la collecte avait été arrêtée volontairement.
