# AIV 2.0.6 — contrôle global des permissions

APK : `AIV-2.0.6.apk`, versionCode 206, paquet `com.allinvisible.aiv`.
Mise à jour de la 2.0.5 avec le même certificat. Aucun choix application par
application n’est requis pour le parcours global.

## Parcours sur le téléphone

1. Installer la mise à jour et démarrer Shizuku. Si nécessaire, autoriser AIV
   une fois dans Shizuku.
2. AIV → **Shizuku** → **Analyser et préparer le contrôle du téléphone**.
3. L’analyse lit toutes les applications visibles du profil courant, y compris
   préinstallées et désactivées, leurs permissions, états Android/Shell et verrous.
   Le plan global s’ouvre automatiquement après l’analyse.
4. Vérifier les propositions, puis **Appliquer les … retraits** et **Appliquer**.
   Tous les lots s’enchaînent. Le résultat des commandes s’ouvre automatiquement.

**Choisir les accès à réduire** règle une politique pour le téléphone entier :
arrière-plan, accès spéciaux, données personnelles, localisation, médias,
caméra/microphone, appareils proches et notifications. Arrière-plan et accès
spéciaux sont sélectionnés au départ. Les autres catégories sont disponibles
sans configurer chaque application. Un usage particulier déjà enregistré reste
prioritaire; son réglage est facultatif et le plan relit sa valeur actuelle.

Les droits sont proposés selon cette politique explicite. Les catalogues ne
constituent pas une preuve universelle qu’une permission est inutile : Bayton
fournit les descriptions, l’appareil fournit le type et les états; les signatures
Exodus aident à prioriser les applications à examiner. Les états de couverture
Exodus restent affichés, notamment quand l’analyse des DEX n’est pas terminée.

## Portée du contrôle

Les permissions runtime accordées et vérifiées sont retirées par `pm revoke`.
Les six accès spéciaux pris en charge passent par AppOps : superposition,
réglages, statistiques d’utilisation, installation d’APK, accès à tous les
fichiers et alarmes exactes. Le plan global retient les modes explicitement
actifs `allow` ou `foreground`; un mode `default` ne suffit pas pour conclure
que l’accès spécial est accordé.

L’origine d’une limite est conservée : verrou Android, type de permission,
protection AIV, portée UID ou observation incomplète. **Inclure les applications
protégées par AIV** permet d’inclure les rôles et composants que la 2.0.5
excluait dans le code. Cela ne modifie pas les verrous Android. Un UID partagé
reste en lecture seule : une commande sur l’un de ses packages peut concerner
le groupe. Les propres droits d’AIV sont préservés pour ne pas interrompre
l’exécuteur et empêcher la vérification de sa commande.

Les permissions normales, de signature et internes ne sont pas toutes
révocables par cette voie. Les permissions marquées `development` sont
identifiées comme un relevé spécifique non pris en charge par ce module;
elles ne sont pas présentées comme un refus effectif d’Android.

L’inventaire porte sur le profil courant; Dossier sécurisé et autres profils
restent hors de cette portée. Le module fonctionne sans VPN. Les fonctions
correspondant aux droits retirés peuvent cesser de fonctionner.

## Lots, vérification et restauration

Le plan garde tous les droits sélectionnés et les répartit par lots de
50 applications et 500 droits. Une seule validation démarre la file complète.
Chaque APK, rôle, portée et état de permission est revérifié immédiatement avant
sa commande. Les plans sont consommés une seule fois et expirent après cinq
minutes avant lancement. Chaque commande a une borne de douze secondes.

Les plans et reçus sont écrits atomiquement dans les fichiers privés d’AIV,
avant la mutation. Le succès exige une commande terminée correctement et un
effet observé. **Arrêter après la commande en cours** conserve les résultats
et arrête la file. Une analyse interrompue conserve sa couverture réelle;
la borne globale d’analyse est de quinze minutes et 20 000 applications.

**Résultats, restauration et export** donne accès au tableau des résultats,
à la restauration et à l’export. La restauration parcourt tous les lots exécutés
en ordre inverse, y compris après une interruption, et ne réaccorde que les
retraits confirmés dont l’identité et l’état restent vérifiables. Les différences
annexes observées sont consignées; leur attribution n’est pas inventée et elles
ne sont pas réaccordées automatiquement.

L’export contient l’inventaire courant, les relevés Shell datés de l’analyse et
les reçus. Il ne déclenche pas une nouvelle commande Shell pour chaque app.
Le bouton **Permissions** et le nom de chaque application ouvrent son détail
depuis les premières colonnes visibles. Les tableaux et résultats sont paginés
par cinquante lignes, sans supprimer les lignes suivantes.

## Vérification de cette livraison

- Compilation Java, D8, aapt2 et vérification de la signature APK v2/v3.
- 40 cas existants sur les profils, flags, commandes, expiration et restauration.
- 36 cas sur la politique globale, les protections, origines et partition des lots.
- 25 vérifications du contrôleur de production avec un téléphone simulé :
  57 applications, inventaire complet, politique globale sans choix par app,
  respect d’un usage enregistré après analyse, protection AIV optionnelle,
  lots successifs, APK remplacée, nouveau rôle, reçu durable avant chaque commande,
  verrou de l’exécuteur, résultat paginé, AppOps et restauration de tous les lots,
  export des relevés complets, arrêt et refus de rejouer un plan consommé.
- Exécuteur Shell réel avec processus Shizuku simulé : terminaison, autorisation,
  erreurs de pipe, sorties tronquées et délai maximal.
- Régressions V22/V23, configuration et politique d’accès.

Certificat SHA-256 :
`3c6b7dbdaecd823b512408d6442328cde2464c8d0b074ec714858e64f5bf08ff`.
Les bibliothèques natives et catalogues Bayton/Exodus restent ceux vérifiés dans
l’APK fourni. Le workflow manuel recompile les sources sans publier les secrets
ni la clé privée.

Aucun essai d’exécution de la 2.0.6 sur le téléphone ou dans un émulateur n’a été
réalisé. Les fixtures JVM exercent le contrôleur de production, mais simulent
les services Android et le transport Shell; elles ne remplacent pas cet essai.
