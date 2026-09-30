# AIV 0.6.22 — inventaire, permissions et traqueurs

Base réelle : `aiv-v20-build` à `89ec9db`, APK 0.6.21, versionCode 27.
V22 : même paquet `fr.erick.journallocal`, versionCode 28. Le dossier historique
`aiv-v19` reste le dossier de compilation. Aucune nouvelle permission Android.

## Comportement livré

- Inventaire local des paquets visibles dans le profil courant, y compris les
  applications désactivées. Nom exact, UID, version, installation, composants,
  permissions demandées/accordées, API cible/minimale, certificats SHA-256 actuels
  et historique de rotation exposé par Android.
- Analyse indépendante des APK base et splits lisibles : empreintes SHA-256 et
  signatures Exodus dans les **classes DEX définies**. Cache invalidé par
  version, date de mise à jour, chemins/tailles/dates des APK et révision Exodus.
- Catalogue Jason Bayton/AOSP intégré : 1 138 permissions, référence Android 17
  API 37, révision déclarée 2026-07-15. Les descriptions de référence sont
  distinguées des définitions et états fournis par Android sur le téléphone.
- Catalogue Exodus intégré : 432 fiches, extrait du 2026-09-26, signatures réseau
  et code, catégories, sites et identifiants. Aucun inventaire ni APK envoyé.
- Correspondances SNI dans la vue Flux, et index Exodus persistant interrogeable
  par application, domaine et traqueur dans Référentiels. L'historique existant
  est repris par lots avec progression. Les noms DNS restent des candidats de
  requête; ils ne sont pas associés arbitrairement aux connexions ultérieures.
- Volumes cumulés conservés par identifiant de flux, sans additionner les
  instantanés répétés. UID partagés/réservés : attribution applicative inconnue.
  Les signatures partielles, les collisions entre plusieurs fiches et les noms
  TLS externes avec ECH sont explicitement conservés comme limites.
- Rapprochement à la lecture : mêmes identifiants Exodus dans le flux et dans
  l’APK actuel, avec version explicitée. Cela ne prouve pas la version historique
  ni que ce SDK a déclenché le flux.
- Pédigrée et SDK consultables dans la fiche Application. Export des dossiers
  enrichi; export séparé des correspondances Exodus avec état de progression.

## Niveaux demandés

| Niveau | Couleur | Critère |
|---|---|---|
| L1 | Vert | Listes nominatives concordantes, même version, source Google Play renseignée. |
| L2 | Jaune | Permission absente de la liste principale, présente dans Toutes les autorisations pour la même version. |
| L3 | Orange | Capacité d'autonomie documentée ou règle explicite de fonctionnement sans intervention immédiate. |
| L4 | Rouge | Description Android/Bayton mentionnant un détournement possible par une application malveillante. |
| L5 | Gris | Permission identifiée donnant une portée sur le système ou d'autres applications. |
| Indéterminé | Bleu | Preuves insuffisantes; jamais converti automatiquement en L1. |

Les niveaux expriment des **capacités déclarées**, pas des actions constatées.
L'état accordé/refusé est affiché séparément; les AppOps et politiques propres au
constructeur ne sont pas établis. L4 ne qualifie pas l'application de malveillante.
Les catégories Android `normal`, `dangerous`, `signature` ne sont pas ce barème.
Le classement L5 utilise une liste explicite de permissions Android; il ne
prétend pas classer toutes les permissions OEM inconnues. Une description
manquante et une absence de signature Exodus ne deviennent pas une assurance.

Le calcul numérique de V21 et les observations sauvegardées restent disponibles
comme calcul historique. Les couleurs V22 n'utilisent plus ses seuils. Les
nouvelles listes de visibilité peuvent être saisies ou importées dans le format
existant enrichi (`permission_names`, `surface`), sans perdre les anciens nombres.
La visibilité effective de Google Play ne se déduit pas du manifeste seul.

## Charge et continuité

Le collecteur programme les reprises chaque minute. L'index traite des lots de
200 événements dans une tranche d'environ 5 secondes. L'analyse APK examine des
applications pendant une tranche de 20 secondes, en terminant l'application en
cours; elle ne bloque pas l'inventaire de démarrage. Les fichiers inaccessibles
et limites de lecture sont marqués PARTIAL. Limites : 1 Gio par APK pour le hash,
32 Mio par DEX, 256 Mio de DEX par application. DEX 035–040; format 041 et code
natif/téléchargé/archives imbriquées hors couverture. Les passages suivants
reprennent grâce au cache. L'arrêt général ou de l'analyse est respecté; le
bouton de reprise réactive les analyses AIV.

Les journaux originaux et leur chaîne d'intégrité ne sont pas réécrits. Les
nouvelles bases dérivées sont séparées. Une nouvelle révision Exodus reconstruit
uniquement l'index dérivé; aucune ligne du journal source n'est supprimée.
L'index/export doit être interprété avec son checkpoint de couverture.
Le téléphone ne peut pas suivre les traitements internes d'un serveur distant.
Aucun marqueur n'est injecté dans les paquets Internet.

## Références et maintenance

Sources, dates, SHA-256 des réponses amont et attributions :
`app/src/main/assets/catalogs/`. Le script `tools/refresh_catalogs.py` permet une
actualisation explicite par le mainteneur, ou depuis deux fichiers téléchargés.
Il ne s'exécute jamais automatiquement sur le téléphone. Le code de l'inspecteur
web Bayton et des moteurs Exodus n'est pas copié dans l'APK; les fonctions locales
s'appuient sur PackageManager, une lecture DEX et les catalogues documentés.

## Vérifications et livraison

- Compilation locale Java + JNI arm64-v8a/x86_64, SDK 35 / NDK r27d, APK aligné.
- `bash tests/run-v22.sh` : niveaux/couleurs et visibilité, refus de permission,
  formules V21, cache de calcul, attribution UID, segments SQLite, fusion des
  références, signatures réseau et frontières de domaine, lecture DEX réelle et
  bornes invalides. Les classes sont aussi lues depuis l'APK construit.
- Parcours Chromium avec pont Android synthétique : démarrage, fiche L5,
  pédigrée, état des catalogues, recherche de traqueur, commande d’export et
  ouverture du flux vérifiés. Ce contrôle ne remplace pas un test Android.
- Le test historique `tests/test_verdicts.py` n'est pas un contrôle de cette
  livraison : il dépend d'un ancien `tools/generate_verdicts.py` absent de V21.
- Validation de fonctionnement sur le téléphone et vérification de consommation
  longue durée encore nécessaires. Aucun résultat de téléphone n'est simulé.
- L'APK de compilation est **non signé**. Une mise à jour conservant le journal
  exige la clé historique; la compilation signée existante vérifie son empreinte.
  Aucune nouvelle clé n'est créée. Ne pas désinstaller V21 pour contourner cela.
