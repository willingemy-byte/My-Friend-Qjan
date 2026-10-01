# AIV — appareils, parc TI et coopération défensive

Proposition d'architecture du 1er octobre 2026. Cette proposition ne déploie aucun cluster, n'enrôle aucun appareil et n'envoie aucun journal. Le code dispose désormais des trois paliers et d'un basculement d'interface ; le parc affiche honnêtement qu'il n'est pas connecté.

## Paliers et points de vue

| Palier | Accès | Périmètre |
|---|---|---|
| 1, gratuit | Observer : journal, flux, permissions, anomalies | Son appareil |
| 2, payant | Observer et exécuter des actions locales autorisées | Son appareil |
| 3, TI | Fonctions du palier 2 et administration d'un parc enrôlé | Son appareil ou son organisation |

Une identité de compte ne change pas quand on change de vue. Séparer `entitlement_tier` (droits commerciaux vérifiés), `view_scope` (`device`/`fleet`), `tenant_id` (organisation) et `admin_role` (lecture, politiques, enrôlement ou action). Le palier 3 seul ne donne pas accès au parc d'une autre organisation. Le téléphone personnel du TI demeure distinct des appareils de son employeur.

Le tableau de bord TI doit montrer les appareils par site/type/système, leur dernière présence, la version de leurs politiques, les anomalies vérifiées, les actions réussies/échouées et les états inconnus. Un appareil hors ligne n'est ni sain ni automatiquement compromis. Filtres, pagination et agrégation côté serveur ; aucune lecture de tous les événements dans la WebView. Un écran par appareil mène à ses preuves et à l'historique d'actions, avec les droits de l'administrateur vérifiés côté serveur.

## Répartition entre appareil et serveur

Chaque appareil conserve la capture locale, une base SQLite, le petit Main déterministe, une file de rapports et un adaptateur de contrôle propre au système. Android utilise les capacités VPN/Shizuku disponibles ; un serveur ou un ordinateur utilise un autre adaptateur. Ni le protocole commun ni Kubernetes ne transforment un téléphone en appareil root.

Le Main central reçoit des rapports signés, les valide, rapproche leurs observations, produit des avis ou politiques versionnées et diffuse uniquement les mises à jour pertinentes. La décision finale d'action locale reste exécutée par le petit Main, dans les permissions et délégations acceptées pour cet appareil.

Les flux ordinaires continuent de passer directement vers leur destination depuis le VPN local. Le canal central transporte d'abord des rapports et des politiques, pas tout le trafic. Un relais distant sélectif pourra être ajouté pour les organisations qui le demandent ; il doit avoir ses règles, sa capacité et son mode de panne. Le chemin de décision local ne doit pas attendre une réponse cloud pour chaque paquet.

Le code actuel `MainEngine` écrit encore des décisions `NOT_ENFORCED`. Les retraits Shizuku sont une voie d'exécution réelle séparée, désormais contrôlée par la politique de palier. L'unification de ces voies, la prévention inline par le VPN, les délégations d'actions autonomes et la diffusion centrale restent à réaliser.

## Identités cryptographiques et empreintes observées

1. **Notre appareil/agent** : créer une paire de clés propre à l'installation dans Android Keystore ; préférer une protection matérielle et vérifier l'attestation quand elle est disponible. La clé privée ne sort pas de l'appareil. Enrôlement auprès de l'organisation, identifiant et certificat de courte durée, rotation et révocation. La clé qui signe l'APK reste une clé distincte, hors téléphone.
2. **Nos services centraux** : identités de workloads, certificats mTLS et rotation ; SPIFFE/SPIRE peut fournir ce modèle dans Kubernetes. Les identités de mobiles passent par leur protocole d'enrôlement et ne deviennent pas automatiquement des workloads SPIRE.
3. **Une entité externe** : nous ne pouvons pas lui inventer une identité cryptographique certaine. Un APK accessible peut avoir une empreinte SHA-256 et une empreinte de certificat de signature ; une clé TLS observée peut avoir une empreinte ; une IP, un domaine ou un comportement restent des indicateurs. Un même acteur peut changer de clé, et un certificat d'éditeur peut couvrir plusieurs versions et apps. Rien ne garantit de reconnaître un acteur qui change tous ses artefacts.

L'identité du rapporteur, l'empreinte de ce qu'il a observé et l'identité supposée d'un acteur sont trois champs différents. Le chiffrement TLS/ECH et les limites Android réduisent certaines observations ; aucune interception TLS généralisée n'est ajoutée. Un rapport signé établit son origine et son intégrité, pas la vérité de toutes ses conclusions ni l'absence de compromission du rapporteur.

## Rapport minimal et vérification

Enveloppe prévue : `schema_version`, `tenant_id`, `reporter_device_id`, `reporter_key_id`, `report_id`, `sequence`, `observed_at`, `expires_at`, `subject_type`, `subject_fingerprints`, `observables`, `rule_id`, `rule_version`, `confidence`, `evidence_digest`, `action_requested`, `action_result` et `signature`. Les données doivent avoir un encodage canonique défini avant signature.

Le serveur vérifie l'enrôlement, la signature, la validité/révocation de la clé, l'appartenance au tenant, le numéro de séquence et l'identifiant contre le rejeu, la fenêtre de validité, la version de schéma et les limites de taille. La chaîne d'empreintes locale est utile, mais une empreinte seule n'authentifie pas un expéditeur. Les preuves brutes restent locales par défaut ; un envoi supplémentaire est explicite et réduit au besoin.

## Propagation défensive sans emballement

Observation → rapport signé → validation → corroboration indépendante → avis ou politique signée → diffusion ciblée → décision locale → résultat signé.

Une alerte isolée peut déclencher de l'observation. Une action autonome demande une règle préautorisée, des preuves suffisantes et une portée limitée. Compter des appareils enrôlés distincts et indépendants, pas le nombre brut de messages ; un appareil compromis ne doit pas pouvoir fabriquer des milliers de votes. Une IP de CDN ou une réputation non confirmée ne devient pas une interdiction universelle.

Les politiques centrales ont un identifiant, une version monotone, une portée (tenant/groupe/appareil), une date d'expiration, une justification, une signature et une procédure de retrait. Vérification locale avant application. Déploiement progressif, confirmation du résultat, restauration et publication d'une correction si le signal était faux. La fédération entre organisations partage seulement des indicateurs validés selon leur accord, pas automatiquement leurs journaux ou identités d'employés. Le format STIX distingue observations, indicateurs et sightings ; il peut servir de format d'échange au serveur sans charger un modèle complet sur le téléphone.

## Kubernetes et charge

Kubernetes hébergerait les API d'enrôlement/rapports, les workers de validation, le service de politiques et le tableau de bord. Il ne donne pas directement accès aux téléphones. Pour démarrer, une API modulaire et quelques workers suffisent ; conserver des contrats permettant leur séparation lorsque la charge le justifie. Le dimensionnement vient des mesures, pas du nombre de services sur un diagramme.

Pour plusieurs organisations, imposer autorisation par tenant sur chaque objet et requête. Les namespaces Kubernetes seuls ne suffisent pas : RBAC, identités des services, politiques réseau, quotas et isolation des stockages sont nécessaires. Environnements plus stricts : clusters dédiés ou isolation renforcée selon les exigences réelles.

Sur l'appareil : lots bornés, déduplication par sujet/règle, file persistante plafonnée, compression, reprise et backoff. Rapports critiques en priorité ; heartbeat et synthèses groupés. Pas de recalcul complet à chaque événement, pas de polling UI à haute fréquence, pas de cloud synchrone dans le relais réseau. Mesurer CPU, batterie, mémoire, taille de file, volume transmis, latence de décision et faux positifs avant de modifier les budgets.

Sur le serveur : journal d'audit append-only, stockage objet pour les archives et preuves volumineuses, base pour l'index, file d'événements pour les workers et diffusion incrémentale de politiques. Rétention et collecte minimales par organisation. Le journal complet n'est pas un dépôt GitHub public.

## Ordre de réalisation

1. Stabiliser app locale, design et points d'exécution vérifiés.
2. Unifier le mini-Main, les profils de permissions et les actions ; config versionnée, consentements durables et restauration.
3. Identité d'appareil, signatures locales et tests de rejeu/rotation/révocation.
4. API d'enrôlement et de rapports pour un petit parc de test ; séparation des tenants.
5. Tableau de bord TI et politiques signées, d'abord en observation.
6. Actions préautorisées limitées, tests de panne, retrait de politique et déploiement progressif.
7. Fédération défensive et relais réseau sélectif après mesure des besoins.

## Références techniques primaires

- Android Keystore : https://developer.android.com/privacy-and-security/keystore
- Attestation des clés : https://developer.android.com/privacy-and-security/security-key-attestation
- SPIFFE : https://spiffe.io/docs/latest/spiffe-about/spiffe-concepts/
- Isolation Kubernetes : https://kubernetes.io/docs/concepts/security/multi-tenancy/
- STIX 2.1 : https://docs.oasis-open.org/cti/stix/v2.1/os/stix-v2.1-os.html
