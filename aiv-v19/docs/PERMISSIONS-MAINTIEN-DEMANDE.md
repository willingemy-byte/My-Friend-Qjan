# Demande : maintenir les permissions choisies par application

Besoin identifié pendant la correction du VPN : après des retraits via Shizuku, conserver les permissions établies et suivre automatiquement les écarts.

## État vérifié en 2.0.6/2.0.7

- `PermissionControl.apply` / `applyReview` vérifient les retraits choisis, conservent leurs rapports et déclenchent un inventaire. Ces opérations ne créent pas une règle persistante de réapplication des permissions.
- `RecorderService` redemande un inventaire toutes les 900000 ms (15 minutes) pendant la collecte, sous réserve de fonctionnement du service. Les événements d’inventaire/différences ne constituent pas une preuve de leur auteur.
- `DeveloperControl.tick/watch` peut maintenir un arrêt ou une désactivation d’application explicitement configurée. Il ne maintient pas une liste de permissions révoquées.
- `ShizukuCleanup` effectue un lot après une demande; le mot « automatique » ne signifie pas surveillance/retrait permanent des permissions choisies.

Cette fonctionnalité de maintien **n’est pas incluse dans le correctif VPN 2.0.7**. Une notification de changement ne prouve ni l’annulation de l’ensemble des retraits ni une intention malveillante.

## Comportement à construire après validation du VPN

1. Mémoriser les choix explicitement établis par application et profil Android, à partir des retraits réellement confirmés. Une restauration doit modifier ces choix. Aucun état inconnu ne doit devenir une règle de refus.
2. Un mode « Maintenir mes permissions » doit présenter les règles enregistrées et permettre pause/modification. Surveiller les changements de permission/AppOp et les mises à jour en plus d’un contrôle périodique borné.
3. En cas d’écart, relire l’état, vérifier l’identité du paquet et les contraintes Android, appliquer seulement les règles enregistrées, puis relire pour confirmer. Pas de retrait de nouvelles permissions sur le seul critère d’un nom d’application ou d’une notification.
4. Journaliser avant/après, UID/paquet/certificat, règle, commande, résultat vérifié et erreur. Conserver l’auteur UNKNOWN quand sa source n’est pas observable. Ne pas confondre runtime permissions, flags et AppOps.
5. Si Shizuku est indisponible, annoncer « surveillance disponible / correction en attente »; borner les retries et reprendre quand l’autorisation/service revient. Respecter un changement volontaire du profil et arrêter en cas d’identité ambiguë ou de verrou Android.

Tests nécessaires : permission réaccordée et correction confirmée; permission déjà conforme; Shizuku arrêté/repris; refus de commande; package remplacé/certificat changé; UID partagé; permissions liées et AppOps; restauration; redémarrage AIV; budget de contrôle et absence de blocage du VPN/journal. Le test téléphone doit utiliser une application d’essai et une règle précise avant d’activer un maintien sur le lot complet.

## Raccord à l’API officielle Shizuku

Les commandes AIV passent déjà par l’API embarquée : `PermissionControl` et `ShizukuCleanup` appellent `ControlShell.run`, qui vérifie le Binder et l’autorisation puis utilise encore `Shizuku.newProcess` par réflexion. L’API seule n’installe aucun observateur des permissions des autres applications et ne réapplique pas les retraits.

Le [guide officiel Shizuku-API](https://github.com/RikkaApps/Shizuku-API) recommande `UserService` pour remplacer `newProcess`. Le maintien doit donc prévoir un service privilégié à interface typée pour lire/appliquer/vérifier les seules règles validées, avec reconnexion et détection de perte de Shizuku. Les listeners de disponibilité du Binder ne sont pas des listeners de changement de permissions des applications.

L’observation des événements Android doit être distincte de l’exécution des commandes. Le processus UserService n’est pas un processus d’application Android normal : le guide indique notamment que `Context.registerReceiver` et `getContentResolver` n’y fonctionnent pas normalement. Ne pas construire un faux suivi en temps réel autour de ces appels. Vérifier les callbacks accessibles avec l’identité Shell et la version Android, et prévoir un contrôle périodique borné si des événements sont inaccessibles. Afficher la latence et les interruptions mesurées; aucune garantie d’observation instantanée universelle.

Ce raccord doit fonctionner hors des threads du VPN et du journal. Les commandes, lectures de profils et retries sont bornés; les états doivent distinguer « règles conformes vérifiées », « correction en attente », « Shizuku indisponible » et « résultat inconnu ». L’objectif demandé est proactif : une dérive détectée déclenche la correction autorisée et sa vérification, sans retrait nouveau fondé sur une supposition.

Référence primaire : [manuel Shizuku](https://shizuku.rikka.app/guide/setup/). En mode de démarrage ADB/débogage sans root, le démarrage doit être renouvelé après redémarrage du téléphone. AIV ne doit pas annoncer une correction active lorsque Shizuku n’est pas disponible.
