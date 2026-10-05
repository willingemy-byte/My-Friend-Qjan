# AIV 2.0.14 — conserver les permissions réaccordées par l’utilisateur

Le maintien de 2.0.13 réappliquait les refus enregistrés sans tenir compte du choix utilisateur Android. Réaccorder le micro ou les notifications d’une application pouvait donc être suivi d’un retrait au prochain cycle. Cette version conserve les permissions runtime réaccordées lorsque le relevé Shell concorde avec Android et porte USER_SET, USER_FIXED ou ONE_TIME, sans verrou SYSTEM_FIXED ou POLICY_FIXED.

L’exception retire uniquement le couple application/permission de la liste des refus maintenus. Elle est enregistrée dans le fichier atomique de politique avant toute autre intervention et inscrite dans le journal, avec l’identité de l’installation, les flags observés et l’heure. Les autres permissions restent suivies. Les exceptions survivent au redémarrage, à la pause/reprise du maintien, à l’ajout des refus actuels et aux mises à jour de la même installation. Un nouveau retrait confirmé et explicitement demandé avec AIV remet cette permission sous maintien. Une application réinstallée ou avec un autre signataire n’hérite pas de l’exception de l’ancienne installation après approbation.

Une seconde lecture des flags juste avant la commande protège aussi le cas où le choix utilisateur arrive pendant l’écriture du reçu. La commande est alors annulée et un reçu « skipped_user_choice » est conservé. Si l’enregistrement de la politique ou du journal échoue, le maintien se suspend; AIV ne retire pas la permission pour compenser cet échec.

## Limite des observations Android

USER_SET indique qu’un choix utilisateur a été effectué et peut subsister après une intervention antérieure. Il ne prouve pas qui a réalisé le dernier changement. AIV donne priorité à ce marqueur de consentement et ne prétend pas identifier l’auteur exact. Une permission retournée sans ces marqueurs reste soumise au maintien existant; les accès spéciaux AppOps conservent également leur comportement précédent. Les autorisations temporaires restent soumises à l’expiration décidée par Android. La dernière observation et la commande Shell ne forment pas une transaction avec les réglages système.

Références primaires consultées : [permissions Android et USER_SET](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/permission/Permissions.md), [états AppOps](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/app/AppOps.md).

## Périmètre et validation

La présentation, la collecte, le VPN, les grades et Supabase sont inchangés par rapport à 2.0.13. Dans MainActivity, seuls les textes et le compteur du dialogue « Maintien automatique des droits » changent. L’état détaillé/export expose le nombre et le détail des exceptions.

- Contrôleur Shizuku : 25 contrôles d’intégration.
- Maintien existant : 101 contrôles d’intégration, dont bornes, annulation, identité, reprises, audit et restauration. Les retours automatiques simulés sont explicitement dépourvus de marqueur utilisateur.
- Consentement : 25 contrôles sur le vrai contrôleur et le vrai fichier de politique, avec observations Android simulées : micro, notifications, autres droits de la même application, ancienne politique, redémarrage, retrait explicite AIV, flags exacts, verrou Android, changement pendant l’écriture, pannes de disque/journal et changement d’installation.
- Règles de contrôle : 40; catégories : 36; transport Shell : achèvement, troncature, erreur de tube, autorisation et délai.
- Présentation : 25 contrôles et comparaison des sources pour confirmer l’absence de changement de ses écrans, de la collecte et de Supabase.

Les essais hôte ne remplacent pas une vérification sur le téléphone. Installer par-dessus 2.0.13, ouvrir AIV, puis réaccorder le micro/les notifications dans la fiche Android de l’application concernée. Le maintien enregistre ensuite l’exception au cours de ses cycles.
