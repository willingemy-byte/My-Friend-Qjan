# AIV 2.0.11 — rétablir le classement A1–A5 natif

La 2.0.10 utilisait la file d’examen A4/A5 pour tous les grades affichés. Ce moteur renvoyait A4, A5 ou zéro, et ne pouvait donc jamais compter une application A1, A2 ou A3. Les zéros de ces trois colonnes ne décrivaient pas une analyse complète du téléphone.

La 2.0.11 porte en Java la fonction `PenaltyModel.exposure` existante de la V22. L’accueil, les listes d’applications et les grades du journal utilisent ce classement complet. Les données viennent de l’inventaire PackageManager et des références enregistrées, sans WebView. Les catégories et les compteurs sont calculés à partir d’un même inventaire achevé. Le dossier explique les règles et sources du grade.

- **A1** : concordance complète d’une liste Google Play vérifiée avec les identités de permissions déclarées pour la même version.
- **A2** : permission identifiée dans Toutes les autorisations et absente de la liste principale vérifiée pour cette version.
- **A3** : description Android/AOSP indiquant une action sans intervention immédiate, ou une capacité couverte par la règle d’autonomie V22 existante.
- **A4/A5** : avertissements Android/AOSP et liste de capacités système conservés.
- **Indéterminé** : preuves ou règles insuffisantes. Absence d’avertissement ne produit pas automatiquement A1. Une liste périmée, estimée ou exprimée en groupes ne confirme pas la visibilité d’identités individuelles.

Le grade d’une application est le plus haut niveau établi parmi ses permissions déclarées. Une permission refusée peut continuer à déterminer ce grade; son état d’octroi et une action réellement observée sont des informations distinctes. Les contradictions de listes restent signalées. La file d’examen A4/A5, les interventions Shizuku, la collecte et le moteur VPN ne changent pas.

## Validation

- `AIV_TEST_JSON_JAR=... bash aiv-v19/tests/run-exposure-native.sh` : 330 vérifications, dont 324 cas de comparaison avec le modèle JavaScript réellement conservé dans le projet. A1/A2 vérifiés, versions périmées, unités incorrectes, contradictions, descriptions négatives, autorisations refusées, doublons et capacités combinées.
- `AIV_TEST_JSON_JAR=... bash aiv-v19/tests/run-application-overview.sh` : 25 vérifications des catégories et totaux, y compris une liste de 543 applications.
- `bash aiv-v19/tests/run-v23.sh` : régressions du moteur historique, de la file d’examen et des règles d’audit réussies.
- Compilation SDK 35/JDK 17 : aucune erreur; 52 diagnostics de dépréciation existants.
- Le rendu et les résultats réels sur le Samsung restent à vérifier sur téléphone. Les essais utilisent des données synthétiques.
