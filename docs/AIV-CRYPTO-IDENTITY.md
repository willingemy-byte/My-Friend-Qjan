# AIV — identité cryptographique locale v1

Implémentation initiale du 1er octobre 2026 sur la base AIV 1.1.4.

## Ce que les écrans VPN Samsung montrent réellement

Le formulaire Android/Samsung IKEv2/IPsec sait sélectionner des éléments du magasin de certificats (FMKeyStore, FindMyMobile, etc.) pour authentifier un **profil VPN IKEv2** auprès d'un serveur VPN. Cela confirme qu'Android sait utiliser des clés/certificats protégés pour une identité de client ou de serveur.

AIV utilise toutefois un autre mécanisme : VpnService, un VPN local qui reçoit les paquets IP et les relaie vers leur destination. Le profil IKEv2 du système et VpnService sont deux couches différentes. Sélectionner FMKeyStore dans le formulaire IKEv2 ne donne donc pas automatiquement une clé cryptographique distincte à chaque application installée.

La construction AIV reprend la brique utile sans inventer une propriété qu'Android ne fournit pas :

1. **AIV / appareil** possède sa propre paire de clés dans Android Keystore.
2. **Chaque APK installé** est identifié par son nom de paquet, son profil Android et les empreintes SHA-256 de ses certificats de signature accessibles via PackageManager.
3. **Chaque flux VPN** est d'abord attribué au UID par Android. Si le UID correspond à un seul paquet, le flux reçoit l'identité cryptographique de ce paquet. Pour un UID partagé ou réservé, AIV conserve une identité de groupe et refuse d'inventer quel paquet précis est l'auteur.
4. Les identités réseau distantes (TLS, domaines, IP, certificats distants) restent des observables séparés. Elles ne sont jamais fusionnées automatiquement avec l'identité APK.

## Identité AIV de l'installation

DeviceIdentity.java crée une clé EC P-256 avec SHA256withECDSA dans AndroidKeyStore.

- alias : aiv-device-identity-v1
- clé privée : non exportée par AIV
- StrongBox : demandé sur Android 9+ quand disponible, avec repli vers Android Keystore normal
- key_id_sha256 : SHA-256 de la clé publique encodée
- clé APK : **distincte**, reste hors du téléphone et hors du dépôt

DeviceIdentity.sign(...) existe pour la future enveloppe de rapports signés. Aucun point JavaScript générique n'expose la signature arbitraire : une page locale ne doit pas pouvoir demander à la clé de signer n'importe quel texte.

## Identité des applications

AppIdentity.java produit : package_name, profile_id, uid, current_signer_sha256, signing_history_sha256, app_identity_id et l'état d'attribution réseau.

L'identifiant dérivé est calculé sur une représentation déterministe contenant le paquet, le profil et les signataires courants. Le numéro de version n'entre pas dans l'identité, afin qu'une mise à jour signée par la même identité reste reconnaissable. Le numéro de version demeure enregistré comme observation.

Cette identité ne signifie pas « Google », « OpenAI » ou une autre personne morale à elle seule. Elle signifie : **ce paquet Android, dans ce profil, avec ces certificats de signature observés**. Le lien vers un éditeur connu nécessite une référence explicite et vérifiée séparément.

## Intégration au journal

PermissionAudit ajoute app_identity à chaque inventaire.

NetworkCaptureService ajoute app_identity aux détails d'un flux après résolution du UID. Les paquets aller et retour d'un même flux partagent déjà flow_correlation_id; l'identité d'application est donc attachée au même état local sans modifier les paquets Internet.

Cas importants :

- UID unique + un paquet : identité du paquet disponible.
- UID partagé : identité de groupe avec membres et signataires; auteur précis non affirmé.
- UID système réservé : identité de groupe Android; auteur précis non affirmé.
- paquet non visible : UID conservé, identité déclarée indisponible.
- changement de certificat courant : l'identité dérivée change et peut être signalée comme évolution à examiner.

## Ce que cette étape ne fait pas

- elle ne remplace pas TLS ou IPsec;
- elle ne crée pas un certificat privé dans une application tierce;
- elle ne déchiffre pas TLS;
- elle n'authentifie pas une entreprise à partir d'un nom de paquet;
- elle ne transforme pas une IP ou un domaine en identité certaine;
- elle ne configure pas encore un serveur IKEv2;
- elle ne connecte pas encore un parc TI ou un serveur d'enrôlement.

## Étape suivante

L'étape suivante peut construire l'enveloppe de rapport signée définie dans AIV-ARCHITECTURE-PARC.md : identifiant d'appareil, séquence monotone, horodatage, empreinte des preuves, politique/règle, résultat, signature ECDSA. Ensuite seulement vient l'enrôlement serveur, la rotation/révocation et le parc TI.

Pour un futur tunnel réseau distant, deux choix doivent rester séparés :

- **VPN local AIV (VpnService)** : observation/contrôle par UID et identité APK, sans serveur VPN obligatoire.
- **IKEv2/IPsec ou autre tunnel distant** : identité cryptographique de l'appareil/client face au serveur, avec certificats et authentification du serveur.

Ils peuvent être combinés dans une architecture, mais ce ne sont pas la même brique.
