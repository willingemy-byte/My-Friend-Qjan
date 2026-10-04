# All In Visible 2.0.5 — permissions et Shizuku

VersionCode 205, paquet `com.allinvisible.aiv`. Mise à jour de la branche
`aiv-2.0.4-tables-supabase`, sans reconstruction de l’application depuis zéro.

## Utiliser la version

1. Installer l’APK signé en choisissant **Mettre à jour**. Il utilise le même
   certificat que l’APK 2.0.4 fourni; aucune désinstallation n’est nécessaire.
2. Démarrer Shizuku et ouvrir AIV → **Shizuku** → **Autoriser AIV dans Shizuku**.
3. Le tableau contient les applications du profil courant, préinstallées et
   désactivées comprises. Utiliser les pages suivantes pour parcourir tout
   l’inventaire. Ouvrir une application pour son tableau complet de permissions.
4. **Choisir l’usage** propose un examen météo, calculatrice simple, lampe
   torche, jeu sans fonctions sociales ou lecture hors ligne. Ces usages sont
   choisis explicitement; ils ne déduisent pas la nécessité des permissions du
   nom de l’application. L’examen manuel conserve toutes les cases décochées.
5. Sélectionner les droits hors usage révocables ou cocher les droits souhaités.
   La sélection couvre plusieurs pages. **Préparer le retrait** affiche les
   commandes exactes et les états avant; **Appliquer** exécute ce lot.
6. **Restaurer le dernier lot** rejoue les inverses des retraits confirmés après
   contrôle de l’identité, de la version, du profil, des rôles et de l’état courant.
7. **Exporter les rapports Shell** exporte le tableau courant avec les
   descriptions, usages et signatures Exodus, puis les reçus de retrait et de
   restauration. Les états Shell historiques sont dans les reçus; l’inventaire
   d’export ne déclenche pas un nouveau diagnostic Shell pour chaque application.

Le bouton de préparation pour les usages enregistrés regroupe les droits
admissibles de plusieurs applications : maximum 50 applications et 500 droits
par lot. Aucun retrait n’est lancé au démarrage de l’application.

## Ce que les commandes font

- Permissions runtime : `pm revoke --user <profil> <package> <permission>`.
- Accès spéciaux pris en charge : opération AppOps `ignore` pour superposition,
  modification des réglages, statistiques d’utilisation, installation depuis
  cette source, tous les fichiers et alarmes exactes, si l’état est vérifiable.
- Une autorisation Shizuku pour AIV est nécessaire; aucun dialogue Android de
  permission n’est ouvert pour chaque application ciblée.
- Les commandes restent exécutées par Android sous l’identité Shell de Shizuku.
  Elles ne transforment pas Shell en root et ne retirent pas les permissions
  normales, de signature ou internes par `pm revoke`.
- Les permissions SYSTEM_FIXED/POLICY_FIXED, rôles essentiels et UID partagés
  sont affichés avec leur limite. Certaines permissions d’applications
  préinstallées sont modifiables si Android ne les verrouille pas et si leur
  application n’est pas réservée.
- Les autres profils, dont Dossier sécurisé, restent hors de l’inventaire courant.

## État et restauration

Chaque lot et chaque commande sont enregistrés dans les fichiers privés d’AIV
avant exécution, avec écriture atomique. Les commandes ont un délai de 12 s;
stdout/stderr sont drainés sans appeler `exitValue()` prématurément. Une sortie
tronquée ou une lecture interrompue invalide l’observation. Les états runtime
sont rapprochés du relevé Shell du package et du profil exacts; les AppOps à
l’échelle de l’UID ne sont pas changées silencieusement.

Le résultat n’est confirmé que si la commande termine correctement et que son
effet est observé. Une permission déjà refusée ne reçoit pas de commande de
réaccord arbitraire. La restauration conserve les modes AppOps précédents et
les flags utilisateur modifiables; elle refuse d’écraser les changements
ultérieurs et de réaccorder des droits à une APK remplacée. Les reçus confirmés
peuvent être restaurés après un redémarrage interrompant le lot.

Les dépendances de localisation sont ordonnées pour restaurer la localisation
au premier plan avant l’arrière-plan. Les autres changements observés autour du
lot sont consignés séparément, sans attribution inventée ni réaccord automatique.
Une restauration ne garantit donc pas de reproduire tous les effets indirects
d’Android ou d’une application concurrente.

## Catalogues et VPN

Les catalogues Exodus et Bayton restent identiques à ceux de l’APK fourni.
Bayton API 37 est une référence descriptive; l’appareil est sous API 36. Les
types et états de permissions effectifs viennent de l’appareil. Les signatures
Exodus présentes dans les DEX ne prouvent ni exécution ni envoi de données.

Le module de permissions fonctionne sans VPN. Le manifeste retire le support
du VPN permanent pendant ce diagnostic. Si les réglages Android précédents
gardent le blocage sans VPN actif, désactiver ce réglage dans Android. Cette
version ne prétend pas modifier automatiquement ces réglages globaux.

## Vérification

Compilation de toutes les sources Java et génération D8/aapt2. 40 cas de
régression sur les permissions, profils, flags, commandes, états AppOps,
expiration et restauration. Test de l’exécuteur réel avec processus Shizuku
simulé : terminaison, `exitValue()` interdit, sorties tronquées, erreur de pipe,
autorisation absente et délai maximal. Régressions V22/V23, configuration,
politique d’accès et inventaire des valeurs publiques.

Signature APK v2/v3 vérifiée, certificat identique à l’APK 2.0.4 fourni :
`3c6b7dbdaecd823b512408d6442328cde2464c8d0b074ec714858e64f5bf08ff`.
Les bibliothèques natives sont reprises à l’identique de cette APK, avec hashes
de l’APK, des bibliothèques et des sources vérifiés dans `tools/native-reuse.json`.
Le workflow manuel peut les recompiler avec le NDK.

Aucun test d’exécution sur le téléphone de l’utilisateur ou dans un émulateur
n’a été effectué. La compilation et les tests JVM ne remplacent pas cette étape.
La clé privée, son mot de passe et le bugreport personnel ne sont pas versionnés.
