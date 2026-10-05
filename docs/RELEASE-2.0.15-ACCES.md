# AIV 2.0.15 — Actions et permissions observées

Demande : faire apparaître ce que les applications font quand Android permet de l’observer, rapprocher ces informations des événements et réduire modérément les colonnes.

## Comportement

- Collecte AppOps en lecture seule, par Shizuku, sur un worker distinct de la capture. Une commande `dumpsys appops` au maximum par minute, délai de commande 12 s, sortie limitée à 1 Mio. Au plus 128 paquets vérifiés par cycle, rotation des paquets et budget de traitement de 6 s.
- Opérations suivies : contacts (lecture/écriture), micro, caméra, localisation, SMS, appels, état du téléphone, calendrier, médias, notifications et presse-papiers. Le presse-papiers est une opération Android sans permission runtime distincte.
- Un nouvel accès signalé ou un refus crée un événement `acces`, avec sa permission associée, son heure Android, son heure de relevé, son résultat, sa source et ses limites. Les accès en cours peuvent produire un état par minute. Les anciens derniers accès du premier relevé servent de référence sans être rejoués comme nouveaux événements.
- Les tableaux Journal, Flux et Anomalies montrent les accès rapprochés. L’export des anomalies contient le contexte des événements sources retenus et les références des observations d’accès. L’export original du journal reste intact et comprend les nouveaux événements d’accès.
- Rapprochement : même paquet, UID non partagé et signature, dans une fenêtre de ±60 s autour de l’événement (ou du dernier paquet d’un flux), ou intervalle d’accès en cours. Une réinstallation et un changement de signature empêchent d’associer les anciennes identités. Une proximité temporelle ne prouve pas le contenu envoyé ni une intention.
- Les opérations d’un intermédiaire sont affichées uniquement quand AppOps en expose la provenance. Aucune surveillance générale des communications Binder ou des données privées n’est prétendue.
- La vue de détail ouvre d’abord l’action et les accès rapprochés. Le JSON, les preuves et le dossier complet de l’application sont disponibles séparément, sans joindre automatiquement toutes les permissions au détail d’une connexion.
- Les colonnes d’au moins 240 dp sont réduites de 15 %, celles de 140 à 239 dp de 10 %. Les petites colonnes et boutons conservent leur largeur. La page Présentation garde exactement son rendu et ses sources de la 2.0.14.

## Limites et continuité

Le format du relevé est documenté par le code public AOSP AppOpsService. Le format Samsung et l’autorisation de lire AppOps restent à vérifier sur le téléphone. Une sortie refusée, tronquée ou non reconnue donne un état indisponible et ne produit aucune preuve d’accès. Une erreur de lecture du contexte laisse disponibles les lignes originales. La collecte réseau n’attend pas ce worker et n’est pas arrêtée par l’indisponibilité d’AppOps.

Cette mise à jour ne peut pas reconstituer les usages de permissions absents des anciens 70 000 événements. L’accès signalé à une catégorie ne prouve pas quels contacts ou autres éléments ont été lus. `INTERNET` est présenté comme la capacité Android requise pour une connexion directe, pas comme une étiquette extraite du paquet.

L’archive, le relais VPN, la page Présentation et les exceptions de consentement utilisateur de la 2.0.14 sont inchangés. Aucun nouveau droit n’est demandé par AIV, aucune permission d’une autre application n’est retirée par cette collecte.

Source de format : https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/appop/AppOpsService.java

## Vérifications

- `tests/permission-usage.py` : analyseur production (30 cas), collecteur et rapprochements avec SQLite réelle (25 cas), commandes Shizuku et réponses PackageManager simulées.
- Cas couverts : accès/refus, durées/proxy/attributions, micro actif, fuseau du téléphone, première référence, doublons, historique agrégé exclu, mauvais UID, signature différente, UID partagé, identité absente, fenêtre temporelle, relevé tronqué, format inconnu, paquet absent, arrêt explicite, écriture échouée et recherche indexée.
- Suites de contrôle et consentement existantes : 25 + 101 + 25 vérifications.
- Compilation SDK 35, signature compatible avec la version précédente et vérification v2/v3. Pas de test effectué sur le téléphone.
