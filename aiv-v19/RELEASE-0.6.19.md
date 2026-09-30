# AIV 0.6.19 — collecte continue et reprise des segments

Installer l’APK comme mise à jour de Journal local, sans désinstaller ni effacer ses données. Le paquet et la clé de signature restent ceux de la version précédente. Une désinstallation effacerait la base privée du téléphone.

## Initialisation automatique

Aucun JSON à importer ou à appliquer pour démarrer. À l’ouverture, AIV prépare l’inventaire et la référence locale, installe ou migre le barème intégré en conservant les réglages personnels, puis calcule les résultats avant de retirer l’écran d’initialisation. Une erreur d’enregistrement reste signalée : elle ne devient pas une fausse confirmation. La référence dérivée reste une estimation locale, pas une collecte automatique de Google Play.

## Fonctionnement

- Au premier lancement de cette version, la collecte système et l’analyse progressive sont demandées automatiquement. Android demande son autorisation pour le VPN local si nécessaire. Ce VPN remplace tout autre VPN actif dans le profil.
- Paramètres → Collecte continue : reprise, autorisation/arrêt des connexions et arrêt général. L’arrêt volontaire est mémorisé. Les notifications gardent aussi leur action d’arrêt.
- Les services demandent une reprise après interruption du processus, redémarrage du téléphone et mise à jour. Une fermeture de l’écran ne constitue pas une demande d’arrêt.
- Android et Samsung peuvent néanmoins interrompre les services, notamment après un arrêt forcé ou selon leurs restrictions d’arrière-plan. Aucun événement pendant une interruption ne peut être reconstitué par cette version.
- L’inventaire des permissions est actualisé en arrière-plan au plus toutes les 15 minutes pendant la collecte. L’analyse du journal avance par lots avec ses points de reprise. Les pénalités affichées sont calculées depuis le relevé préparé à l’ouverture ou lors d’une actualisation; leur moteur JavaScript n’est pas exécuté en boucle quand l’interface est fermée.

## Historique

La V13 APK possédait un index `journal_segments` absent des sources des versions compilées suivantes. Cette version reprend ce schéma et ses points de reprise existants. Aucun événement n’est supprimé.

Chaque segment contient au plus 50 000 événements. Le dernier ID indexé et les compteurs sont enregistrés dans la même transaction SQLite. Une transaction interrompue est reprise au dernier point validé. Les IDs peuvent comporter des trous : le nombre d’événements détermine la clôture, pas la différence d’IDs.

Il s’agit de segments indexés dans la base privée existante, pas de sauvegardes indépendantes hors du téléphone. L’export demeure nécessaire pour disposer d’une copie externe.

Dans Journal :

- Segment 0 affiche le segment le plus récent. Si l’index rattrape un historique ancien, une fenêtre bornée des IDs récents reste consultable immédiatement.
- Entrer un numéro positif pour consulter un segment historique. Recherche, filtres et pagination portent sur ce segment; ils ne parcourent plus automatiquement les 1,3 million d’événements.
- Le nombre « indexés » est explicitement partiel pendant le rattrapage. Une indexation encore à zéro ne masque plus les lignes déjà enregistrées.
- L’indicateur « nouveaux événements » est léger : il ne remplace pas les lignes, les champs ni le détail en cours. Actualiser charge les nouvelles lignes.
- Le bouton « Consulter le journal pendant le calcul » permet de quitter l’écran d’initialisation sans attendre l’inventaire des permissions.

## Calculs conservés

Le centre du cercle affiche la moyenne de base parmi les applications ayant un écart. Le multiplicateur de gravité reste séparé et commande la couleur. Les règles automatiques V18, les preuves, les inconnues, les réglages de couleurs et les paramètres restent conservés. Le seul nombre de permissions moins visibles ne suffit pas à inventer un niveau supérieur.

## Vérifications

Avant construction : compilation Java réussie; tests des calculs automatiques, du barème, de l’interface, de l’initialisation, de la stabilité du journal et du nouveau contrat de segments réussis. Tests SQLite : compatibilité du schéma V13, conservation du checkpoint, annulation transactionnelle, frontière de 50 000 et IDs non contigus.

Le travail de construction doit ensuite vérifier la signature, le paquet, la version, l’alignement et les fichiers de l’APK avant livraison. Aucun téléphone ni émulateur ADB n’était disponible ici : démarrage Android, redémarrage du téléphone et comportement Samsung en arrière-plan restent à valider sur appareil. Aucun temps de réponse réel n’a été mesuré.

Références Android : [services VPN](https://developer.android.com/develop/connectivity/vpn), [restrictions de démarrage des services au premier plan](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## Instantanés et recalcul

Les résultats des permissions disposent d’un instantané privé écrit de manière atomique. Son empreinte couvre le moteur, les applications, les références et le barème. Un simple nouveau numéro d’inventaire ne suffit pas à l’invalider; une modification de ses données ou des paramètres le fait recalculer. Une empreinte incorrecte ou un fichier endommagé déclenche aussi le recalcul.

Les boutons de modification du barème affichent « Recalcul en cours » avant d’appliquer les paramètres et de refaire les résultats. La référence locale dérivée reste une estimation : ce chargement garantit la cohérence avec les données disponibles, pas une connaissance exhaustive du téléphone.

Les anomalies conservent leur progression transactionnelle, ainsi qu’un instantané de l’état du moteur tous les 50 000 événements traités. Les fenêtres d’analyse traversent les limites des segments. Changer un seuil d’analyse provoque une nouvelle révision et une relecture de l’historique. Les résultats de la révision précédente restent archivés; ils sont masqués dans la vue courante pendant la relecture. Changer seulement le filtre de bruit n’impose pas cette relecture.

Limite distincte : les décisions R1–R6 déjà inscrites dans la chaîne d’intégrité restent des décisions historiques, avec leurs règles d’origine. Cette version ne les réécrit pas rétroactivement. Leur vue est étiquetée en conséquence.
