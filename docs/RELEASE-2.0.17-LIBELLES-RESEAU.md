# AIV 2.0.17 — Libellés réseau

La vue Flux affichait « Google Ads » sous le transport FCM parce qu’une signature Exodus générale correspondait partiellement à `.google.com`. Une autre destination Firebase, `firebaselogging.googleapis.com`, restait sans service identifié. Les colonnes provenaient bien du même flux; la confusion venait des libellés.

- FCM et la journalisation Firebase ont des règles distinctes. Les destinations `firebaselogging.googleapis.com` et `firebaselogging-pa.googleapis.com` correspondent au transport de journaux CCT publié dans le SDK Android Firebase. Le nom seul ne précise ni le produit appelant ni le contenu transmis.
- Les signatures Exodus partielles restent dans les détails et les exports avec leur degré de correspondance. Elles ne nomment plus un pisteur dans le résumé du service. La colonne « Pisteurs candidats » distingue les candidats et les signatures partielles.
- Un flux UDP ou TCP sans nom, sur le port 53, affiche « DNS probable ». Cette compatibilité reste un candidat; elle ne prouve ni le protocole DNS décodé ni la question ou une destination ultérieure.
- Le contexte de lecture indique les directions renseignées. Un événement d’enrichissement d’identité sans paquet ni volume positif est expliqué comme tel; il ne prouve pas un échange aller-retour.
- Les libellés de service sont plus courts. Les cellules tronquées montrent une ellipse et le détail conserve le texte complet.
- Les dates, les observations brutes, les compteurs, les signatures et la capture restent inchangés. Le catalogue passe à `2026-10-05-v2` avec dix règles et huit sources officielles.

## Vérifications

- 54 cas d’analyse avec le catalogue Exodus embarqué, dont FCM et la signature Google Ads réelle, journalisation Firebase, domaines trompeurs, DNS présumé, UDP/TCP et autres transports, enrichissement d’identité et directions observées.
- Chargement réel des catalogues : 17 cas; catalogue indisponible : 2 cas.
- Suite SQLite existante : pagination, migration, volumes maximums, export de 503 enregistrements et conservation des observations brutes.
- Compilation Android et vérification de signature. Pas de test de cette version sur le téléphone.
