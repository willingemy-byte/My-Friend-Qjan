# AIV 0.6.23 — Défense et ménage

Cette version ajoute uniquement le parcours de ménage et le suivi des changements. Elle reprend la V22 et conserve le journal, les référentiels, les calculs et les réglages existants.

## Utilisation

1. Ouvrir AIV. L’inventaire des applications du profil courant prépare automatiquement les dossiers L4/L5.
2. Accueil → **Ménage**, ou Applications → **Défense et ménage · L4 / L5**.
3. Pour chaque dossier : **Conserver cette application**, **Désinstaller…**, ou **Autorisations et désactivation**. Les accès spéciaux recensés disposent de raccourcis vers leurs réglages Android.
4. Android demande la confirmation du retrait. La désinstallation peut supprimer les données locales de l’application; AIV conserve son dossier d’observation, pas une sauvegarde de ces données ni de l’APK.
5. **Dossier et enquête locale** conserve versions, permissions, provenance et changements. **Exporter le journal de ménage** exporte toutes les observations et actions de ce module.

## Automatisation réelle

- Analyse au démarrage, au retour dans AIV et après un événement d’installation, remplacement, changement ou retrait reçu d’Android.
- Réutilisation de l’inventaire périodique existant (15 minutes lorsque la collecte fonctionne). Les diffusions d’installation utilisent un récepteur dynamique, car Android restreint leur réception par manifeste.
- Le choix de conserver une application porte sur son état et sa version. Une modification constatée réouvre le dossier. Les permissions ajoutées, retirées, devenues accordées, refusées ou inconnues sont distinguées.
- Notification lorsqu’un nouveau dossier L4/L5 ou un changement demande un examen; la file reste disponible si les notifications sont refusées.
- L’inventaire après redémarrage rattrape les différences, sans inventer l’heure précise ni l’auteur des modifications.

Une application ordinaire ne peut pas supprimer silencieusement les autres applications, imposer leurs mises à jour, révoquer leurs permissions ou empêcher toute installation. Cette version n’emploie ni root, ni automatisation d’accessibilité, ni provisionnement administrateur. Elle ajoute seulement `REQUEST_DELETE_PACKAGES` pour ouvrir la confirmation de désinstallation Android.

## Choix et preuves

- Les seuils L4/L5 sont ceux de `PenaltyModel.exposure` V22. Aucun multiplicateur ne décide de l’entrée dans cette file.
- La présence d’une capacité et son état accordé/refusé sont affichés séparément. L4/L5 ne signifie pas « inutile » ou « malveillant ».
- Pas de retrait direct depuis la file pour AIV, les applications préinstallées, les UID système/partagés/inconnus, les rôles essentiels identifiés (accueil, clavier, appels, SMS, administration), les services VPN ou d’accessibilité. Leur fiche Android reste accessible pour un examen manuel. En cas d’échec de lecture des rôles, le retrait direct est fermé.
- Avant une action : relire la version et les permissions, revalider les protections, vérifier que le dossier n’est pas périmé et archiver l’état disponible, dont les résultats APK déjà en cache pour cette version.
- Une demande, un écran ouvert, une annulation, une erreur et un retrait confirmé ont des événements différents. Le résultat Android OK doit être accompagné de l’absence du paquet pour déclarer une désinstallation confirmée. Une diffusion système de retrait sans remplacement ni archivage établit un retrait dans le profil courant, sans en désigner l’auteur.
- Un archivage Android reste distinct d’une désinstallation et conserve le dossier.
- Une simple absence de l’inventaire reste « non retournée ». Un retour des réglages ne confirme pas à lui seul une révocation. Les AppOps d’autres applications ne sont pas collectés.
- Après une interruption sans résultat Android, **Vérifier après mon retour d’Android** consigne un résultat inconnu et relance l’inventaire.
- Les dossiers sont conservés dans `defense.sqlite`, indépendante des bases et journaux bruts existants. Pas de transmission externe.
- Couverture limitée au profil courant. Arrêt forcé, profil professionnel et Dossier sécurisé ne sont pas couverts par une autre instance d’AIV.

## Vérification

`bash aiv-v19/tests/run-v23.sh` exécute les tests précédents, la concordance du seuil L5 avec l’affichage et les tests natifs de politique, états et différences de permissions.

`node aiv-v19/tests/reader-v23.cjs` vérifie le parcours mobile avec un pont Android simulé : aucune suppression au chargement, protection système, conserver/réexaminer, accès spéciaux, demande/annulation/retrait, dossier après retrait et export. Ce test ne remplace pas une vérification sur téléphone Android.

`AIV_JSON_JAR=/chemin/json.jar AIV_ANDROID_JAR=/chemin/android.jar bash aiv-v19/tests/run-v23-snapshot.sh` vérifie le calcul natif de l’empreinte après compilation : ordre et heures stables, invalidation lors d’un changement de version, provenance, UID ou permissions. Le JAR JSON utilisé est `org.json:json:20240303`.

La compilation et la signature utilisent le mécanisme existant. La clé historique demeure hors du dépôt. La V23 porte le versionCode 29.

## Références Android

- https://developer.android.com/reference/android/content/Intent#ACTION_UNINSTALL_PACKAGE
- https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
- https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPermissionGrantState(android.content.ComponentName,%20java.lang.String,%20java.lang.String,%20int)
- https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
