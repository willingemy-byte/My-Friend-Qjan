# AIV 1.1.4 — capacités vérifiées et distribution locale

Évaluation du 1er octobre 2026. Cette version est une candidate locale à tester sur téléphone, pas un service commercial ou une protection exhaustive certifiée.

## Ce qui fonctionne dans le code

| Fonction | Capacité actuelle | Limite |
|---|---|---|
| Journal SQLite | Événements locaux, recherche, exports, analyse en arrière-plan | Ne contient pas toutes les opérations internes Android; les segments de 50 000 ne sont pas des sauvegardes |
| VPN local | Relais direct et métadonnées des flux pris en charge | Pas de déchiffrement TLS généralisé; limites DNS/SNI/ECH et attribution par UID |
| Inventaire | Permissions déclarées/accordées, paquets visibles, versions et UID | Profils séparés et conteneurs Knox peuvent limiter la visibilité; UID partagé ne désigne pas un paquet unique |
| Shizuku | Retraits runtime et AppOps compatibles, avec résultat vérifié | Identité shell/root, version Android et fabricant déterminent ce qui est permis |
| Norme météo | Aperçu explicite, validation avant retrait, refus des aperçus périmés | Profil proposé, pas une preuve de comportement; seules les permissions runtime accordées sont retirées |
| Restauration | État concerné conservé avant modification; commande reconstruite et état relu | Pas une image complète du téléphone; Android peut refuser un rétablissement |
| Mini-Main | Décisions locales déterministes, explications et historique | `MainEngine` produit encore `NOT_ENFORCED`; ses décisions ne sont pas un pare-feu inline |
| Vue TI | Basculement appareil/parc, trois paliers | Aucun parc enrôlé ou serveur central connecté |

## Périmètre de contrôle

Le propriétaire choisit les accès nécessaires; aucune utilisation préalable n'est exigée pour qu'il décide de refuser une capacité. La politique souhaitée et l'état effectivement appliqué restent distincts.

Les chemins de nettoyage et de normalisation excluent AIV, Shell, les UID système inférieurs à 10000, les autres profils et les UID partagés. Des applications préinstallées ayant un UID d'application propre restent admissibles selon leurs droits réels. Les composants exclus restent observables dans la mesure des sources accessibles.

L'audit ou un recalcul ne lance plus de nettoyage. Les actions viennent des commandes de l'utilisateur. Une délégation persistante à des règles versionnées devra être développée avant d'activer des actions autonomes après analyse. La normalisation fait relire l'aperçu avant exécution.

Cette version ne retire pas les déclarations du manifeste d'un autre APK et n'impose pas une interdiction persistante d'accorder à nouveau une permission. Un profil refusant une capacité doit devenir une politique persistante; une simple révocation ou vérification périodique ne garantit pas cette interdiction. Android Enterprise/MDM, root ou un système modifié sont des voies distinctes à évaluer, pas des fonctions débloquées par le palier payant.

## Rupture de collecte et relais central

Un heartbeat du serveur signalerait l'absence de réponse d'un agent, sans en établir la cause. Une capture réseau indépendante, préconfigurée et autorisée, peut continuer à voir le trafic qui passe par elle. Elle ne peut pas recréer les événements internes qui n'ont jamais été enregistrés, identifier systématiquement le paquet derrière un UID partagé ou entrer dans un téléphone devenu injoignable.

Prévoir une file locale bornée, des numéros de séquence, des rapports signés, une alerte de couverture interrompue, puis une reprise explicite. Les opérations de reprise sont journalisées localement et centralement. En mode géré, un VPN permanent avec blocage hors tunnel est une option à mesurer et tester; il peut couper la connectivité lors d'une panne. Aucun relais distant ni nouvelle capture n'est activé par cette version.

Un volume transféré, une icône absente ou un dossier visible mais non lisible ne prouvent pas une intrusion. L'inventaire doit distinguer paquet, profil utilisateur, état activé/masqué et visibilité d'un raccourci. Les applications ordinaires peuvent aussi avoir des défauts ou des comportements indésirables; limiter l'analyse aux applications système manquerait des risques.

## Les deux APK

- **Personnel** : configuration `distribution_profile=personal`, palier 3 de développement. Fonctionnalités locales de contrôle disponibles avec Shizuku autorisé; vue TI sans parc connecté. À conserver pour Erick.
- **Gratuit** : configuration `distribution_profile=public`, palier 1. Observation locale; les points d'entrée natifs du contrôle sont refusés. À partager pour les premiers essais.

Même package `fr.erick.journallocal`, version 1.1.4/code 114 et même certificat historique : ces APK se remplacent, ils ne s'installent pas côte à côte. Pas de désinstallation demandée pour la mise à jour. Une mise à jour compatible dépend aussi de la version et du certificat déjà installés; aucun test sur le téléphone n'est prétendu.

Les paliers compilés ne constituent ni une preuve d'achat ni une autorisation serveur. Les offres payantes/TI, les comptes, la facturation, l'enrôlement, la cryptographie et les contrôles multi-organisations sont encore à développer avant une commercialisation complète.

Les runners produisent uniquement des APK non signées et les outils publics Android. La signature est effectuée séparément avec le P12 historique; aucun mot de passe, clé privée, journal personnel ou capture du téléphone n'est publié dans GitHub.

## Configuration et travaux restants

Les 21 paramètres numériques et deux chemins centralisés figurent dans `config/defaults.json`; leurs noms Java exacts et significations sont dans [AIV-CONFIGURATION.md](AIV-CONFIGURATION.md). Les minima de services sont dans `config/access-policy.json`; les normes de permissions sont dans `app/src/main/assets/permission-baselines.json`. Les secrets de signature sont séparés.

Tout n'est pas universalisé : constantes de présentation, portions de scoring JavaScript, délais de certaines actions/rafraîchissements et noms internes de bases restent à inventorier avant migration. Ne pas déplacer aveuglément les constantes imposées par Android. Le bornage des processus shell, l'historique de plusieurs restaurations et les tests de panne sur appareil restent des critères avant d'annoncer une récupération fiable.

Ordre proposé : tester la mise à jour locale et les exports; valider des retraits/restaurations sur une application de test; persister les choix du propriétaire; unifier le Main et les actions; ajouter l'identité cryptographique et un petit parc de test. L'architecture centrale est décrite dans [AIV-ARCHITECTURE-PARC.md](AIV-ARCHITECTURE-PARC.md).
