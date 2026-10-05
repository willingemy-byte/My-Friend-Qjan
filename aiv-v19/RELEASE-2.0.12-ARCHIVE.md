# AIV 2.0.12 — archive et protection du stockage

Version remplacée par [2.0.13](RELEASE-2.0.13-SUPABASE.md): le plafond et les changements d’interface décrits ci-dessous ont été retirés à la demande d’Erick. Ce document décrit uniquement le comportement historique de 2.0.12.

Le premier segment de 50 000 événements avait été reçu intégralement, mais la finalisation SQL échouait sur `digest(text, unknown)`: pgcrypto est installé dans `extensions`, hors du search_path de cette fonction. La fonction corrigée appelle `extensions.digest` et utilise `chr(10)` comme séparateur du manifeste, identique au LF du client Android. Le contrôle réel a confirmé les 50 000 SHA individuels et l’égalité du manifeste client. Le segment a ensuite été finalisé avec l’état VERIFIED. Aucun événement distant n’a été supprimé ou réécrit.

La fonction Edge existante conserve l’authentification par clé cliente et secret d’installation. Sa réponse `begin` inclut désormais l’empreinte distante lorsqu’un segment est déjà vérifié. Android exige ce SHA et le compte exact, aussi bien pour ce reçu que pour la finalisation. Il conserve la progression après chaque lot confirmé. Si le compte distant ne correspond plus au préfixe local, il rejoue les lots de manière idempotente. Un segment entièrement reçu est finalisé sans renvoyer les 500 lots. Chaque passage traite au plus un segment, puis programme la suite; les erreurs attendent 30 secondes, puis un délai croissant plafonné à 15 minutes. Dernière erreur et prochaine tentative restent persistées.

La protection du stockage mesure les données privées de l’application: journal, WAL SQLite, projections, analyses, inventaires, préférences et copies temporaires. La collecte, le VPN d’observation et les écritures d’analyse sont suspendus avant d’approcher 2 Gio locaux, ou la réserve de 1 Gio libre. Une marge de 32 Mio et des estimations des écritures à venir protègent contre les lots en cours; ce seuil est une pause préventive, pas un quota de filesystem exact. La pause et sa raison restent enregistrées après redémarrage. Une notification et la page Supabase l’expliquent. Les réglages de reprise restent conservés.

Le segment partiel est scellé après rattrapage de l’index afin de permettre son envoi pendant la pause. Les envois, lectures et exports restent disponibles. Les tentatives différées fonctionnent tant que le processus Android reste vivant; la réouverture et le bouton Synchroniser relancent aussi l’archive. La reprise de collecte est explicite, avec moins de 1,5 Gio locaux et au moins 1,25 Gio libres.

L’archive ne supprime pas les journaux locaux. La chaîne d’intégrité actuelle exige leurs données originales; une purge automatique sans adapter cette chaîne et la consultation distante les rendrait incohérentes. Le plafond arrête leur croissance. Une copie temporaire d’export est retirée seulement après relecture et égalité SHA-256 de la destination choisie. Les données sources restent conservées.

Les corrections A1–A5 et la présentation de 2.0.11 sont incluses.

## Vérification

- `tests/archive-storage.py`: 1 687 assertions avec les vraies classes ArchiveSync, JournalSegments, StorageBudget et StorageGuard; SQLite réel et préférences persistées. HTTP, services Android, scheduling et espace libre sont simulés. Reprise, perte d’accusé de réception, perte de préfixe serveur, reçus invalides, manifeste de 50 000 événements, arriéré borné, fermeture partielle et reprise sur un nouveau segment.
- `tests/event-store-timestamps.py`: vrai chemin d’insertion; refus d’écriture lors de la protection, conservation des horodatages et export JSON/JSONL vérifié.
- Suites SQLite, index/export, segments, relais natif C/JNI TCP/UDP, contrôleurs de permissions, maintien, classement natif et V23: réussies. Classement: 330 vérifications, dont 324 comparaisons au modèle JS existant; présentation: 25; contrôle: 25; maintien: 101.
- `supabase/tests/journal-finalization.sql`: vraie fonction distante; manifeste LF correct, répétition, mauvais SHA, mauvais compte et mauvaise plage, permissions service_role. Toutes les fixtures sont annulées par ROLLBACK.
- Build natif Java/API 35: sans erreur; 52 avertissements de dépréciation Android déjà présents. Aucun test sur le téléphone Samsung n’est revendiqué.

Installer la mise à jour par-dessus AIV, puis ouvrir Supabase → Synchroniser Supabase maintenant → Actualiser l’état. Pour le segment initial, le téléphone doit récupérer le reçu VERIFIED déjà établi sur le serveur.
