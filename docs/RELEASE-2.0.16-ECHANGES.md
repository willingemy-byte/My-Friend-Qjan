# AIV 2.0.16 — Interprétation des échanges

Les lignes réseau expliquent désormais le rôle documenté du service et les catégories des candidats Exodus au lieu de répéter seulement « INTERNET / réseau ».

- Catalogue local de huit règles : transport FCM, API d’envoi, abonnements, Firebase Installations, Remote Config, Realtime Database, Firestore et dépendances possibles de FCM. Les sources officielles, dates et noms d’hôtes sont conservés dans `catalogs/endpoints.json`.
- Les 432 fiches Exodus existantes sont conservées. Les correspondances réseau incluent leurs catégories et références. Une correspondance partielle, une question DNS ou un nom TLS externe restent explicitement identifiés comme candidats.
- Les vues Journal, Flux et Anomalies utilisent ce contexte avec les accès Android déjà rapprochés. L’échec du catalogue laisse les observations disponibles.
- Le détail expose la fonction documentée, l’interprétation, les indices, les événements justificatifs et les limites de chaque lien. Il distingue la date de l’événement et celle de l’analyse à la lecture. Les anciens événements peuvent être enrichis sans être redatés ni prétendre à une capture rétroactive de données.
- Dans Flux, « Exporter le rapport des échanges » produit un JSON avec les flux indexés, les rôles des services, les candidats Exodus, les volumes et les accès rapprochés. L’état de l’index figure dans l’en-tête. L’export des anomalies conserve également ces interprétations.
- Les observations originales, la capture réseau et les exports bruts conservent leur fonctionnement. Le catalogue ne déclenche aucun changement de permissions ou blocage.

Un identifiant local de flux rattache l’aller et le retour au propriétaire réseau. Il ne démontre pas une redistribution vers une application derrière un UID partagé. Le rôle du service et la catégorie du pisteur ne révèlent pas le contenu chiffré. Les accès Android proches restent des rapprochements temporels.

## Vérifications

- Analyse et catalogue : 37 cas synthétiques, dont FCM, fonctions Firebase distinctes, domaine trompeur, IP/port seuls, DNS, ECH, signature Exodus partielle, UID partagé, compteurs absents et dates d’événement/analyse distinctes.
- Collecte AppOps existante : 30 cas d’analyseur et 25 cas de collecte/rapprochement avec SQLite réelle.
- Chargement réel des catalogues et gestion d’échec : 13 + 2 cas. Export du rapport avec SQLite réelle : JSON valide, contexte inclus et flux stockés inchangés; suites existantes de pagination, migration et export brut conservées.
- Compilation SDK 35 et signature vérifiées. Aucun essai sur le téléphone n’est prétendu.
