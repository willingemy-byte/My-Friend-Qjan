# AIV 2.0.18 — Liens entre applications et services réseau

La vue Flux laissait des destinations OpenAI et ChatGPT à « service à identifier ». Elle pouvait aussi afficher le texte d’un rapprochement avec les accès aux données alors que la liste d’observations était vide. Cette version ajoute des fonctions de service documentées ou présumées, un lien explicite avec le propriétaire réseau et un résultat précis du rapprochement AppOps.

- Le catalogue `2026-10-05-v3` reconnaît les hôtes Android et les mises à jour des conversations ChatGPT, les familles OpenAI/ChatGPT, les destinations de contenus, d’authentification et de ressources, ainsi que Sentry, RevenueCat et le résolveur Cloudflare. Il contient 22 règles et 12 sources officielles.
- Les règles spécifiques priment sur les familles générales. Un sous-domaine comme `aw.api.openai.com` ou `ab.chatgpt.com` conserve sa fonction précise indéterminée. Les rôles documentés, les rôles présumés et la seule reconnaissance d’une famille ont des statuts distincts.
- `owner_service_relation` rapproche les indices du même flux : propriétaire réseau, paquet unique, nom annoncé, service et sources. Le détail explique ce lien. Un domaine OpenAI ne remplace pas le propriétaire observé par ChatGPT lorsque celui-ci est Chrome ou un UID partagé.
- DNS et les noms TLS externes ECH restent des indices candidats. Le lien ne démontre ni l’application demandeuse derrière un service système, ni le contenu, ni une livraison applicative du retour.
- Le contexte des accès distingue `TEMPORAL_MATCHES_PRESENT`, `NO_MATCHING_OBSERVATIONS` et `CORRELATION_UNAVAILABLE`. Les motifs comprennent un propriétaire inconnu ou partagé, une identité contradictoire ou non corroborée, et une date de première installation manquante.
- Aucune date d’installation actuelle ne remplit rétroactivement une identité historique. Une liste d’accès vide n’est pas présentée comme une absence d’accès aux données. Les observations et leurs dates restent intactes; l’enrichissement est calculé à la lecture.

## Vérifications

- 80 vérifications des règles avec les catalogues réels : fonctions, priorités, domaines trompeurs, DNS/ECH, UID réservés ou partagés, identités contradictoires, propriétaire Chrome et contenu non déduit.
- Intégration des catalogues : 19 vérifications; catalogue indisponible : 2 vérifications.
- AppOps : 30 vérifications du parseur et 31 de la collecte et du rapprochement SQLite, dont l’identité historique sans date d’installation.
- Suite SQLite existante : identités tardives, volumes maximums, pagination, migration, export et conservation des observations brutes.
- La livraison est vérifiée par compilation Android et contrôle de la signature. Pas de test de cette version sur téléphone.

Les rapports personnels ne font pas partie des sources versionnées.
