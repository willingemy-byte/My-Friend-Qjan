# AIV — archivage sécurisé des journaux (préparation)

**État : préparation du pipeline, PAS de sauvegarde du téléphone.**

Cette branche publique contient seulement la procédure, le schéma de manifeste et les outils non confidentiels. **Aucun journal du téléphone, capture, clé, sauvegarde SQLite, compte utilisateur, manifeste réel ou contenu privé ne doit être envoyé dans GitHub**, y compris dans `jarvis-standalone`. Une branche Git ne rend pas un dépôt public privé.

## Objectif

Libérer le stockage occupé par AIV **sans perdre les événements**, sans fabriquer une seconde copie gigantesque sur le téléphone et sans promettre une corrélation que les données ne prouvent pas.

## Rôles des services

- **GitHub** : source du logiciel, procédure de récupération, schémas, contrôles d'intégrité et, plus tard, déclenchement des déploiements. Pas de base de données personnelle en dépôt Git ni en GitHub Actions artifact.
- **Alibaba Cloud OSS** : stockage durable d'objets chiffrés, bucket **privé** séparé du site et des services publics. Éviter de réutiliser une clé du compte principal. Les droits d'écriture, lecture et suppression sont séparés.
- **AIV sur Android** : seul processus ayant naturellement accès à sa propre base privée. Il faudra que la version installée exporte *en flux*, avec segments bornés, sans écrire une seconde base de dizaines de Go.
- **Kubernetes** : facultatif pour le serveur d'autorisation, les analyses et le traitement ultérieur ; non nécessaire pour que des objets existent dans OSS.

## Parcours de données attendu

1. Fixer un point de reprise / identifiant de dernier événement dans la base, puis ouvrir une lecture cohérente **sans arrêter durablement la collecte**. Si le code actuel ne le permet pas, ne pas simuler cette garantie.
2. Lire par pages stables et produire des segments limités (par exemple 8 à 32 Mio). Ne pas dupliquer l'intégralité de la base en stockage local.
3. Compresser si utile, puis chiffrer localement avec un chiffrement authentifié et des nonces uniques par segment. Prévoir une **récupération de clé hors téléphone** : une clé non exportable perdue avec une application désinstallée rendrait les archives inutilisables.
4. Envoyer en HTTPS vers **OSS privé** à l'aide de jetons temporaires à portée limitée ou d'URL signées à courte durée, émis par un service d'autorisation. **Ne pas intégrer de clé permanente à l'APK, au dépôt ou à un GitHub Action log.**
5. Conserver dans un manifeste non public : identifiant de lot, format, bornes d'événements, taille et SHA-256 **des octets chiffrés** de chaque segment, identifiant d'objet distant et accusé de réception vérifié. Journaliser aussi les segments échoués.
6. Vérifier côté serveur la présence, la taille et le SHA-256 par relecture ; restaurer et déchiffrer au moins un échantillon, puis tester l'intégrité des enregistrements. Un HTTP 200 ou un ETag OSS ne suffit pas comme preuve complète de restauration.
7. Seulement après preuve de récupération indépendante et **accord explicite de l'utilisateur**, déplacer le point de purge. Conserver une fenêtre de sécurité locale et la trace des lacunes éventuelles.
8. Vérifier que l'espace Android est réellement récupéré : supprimer des rangées d'une base SQLite ne réduit pas nécessairement la taille du fichier. Prévoir une rotation des bases / compaction compatible avec l'espace disponible, jamais une opération qui réclame une seconde copie intégrale.

## Garde-fous

- La préparation du pipeline ne doit **jamais** lancer d'effacement Android.
- Ne pas effacer les données de l'application ni désinstaller AIV pour « libérer de la place » avant une restauration vérifiée.
- Une branche du dépôt `My-Friend-Qjan` est publique comme le dépôt. Ne pas la confondre avec un coffre privé.
- `jarvis-standalone` correspond à l'application 3AI indépendante et ne doit pas servir de dépôt de journaux AIV.
- Ne pas stocker des SMS, contacts, identifiants, URLs sensibles ou empreintes de téléphone brutes dans les métadonnées GitHub.
- Ne jamais activer une règle de suppression automatique distante sans sauvegarde secondaire et politique de rétention choisie.
- Sur appareil presque plein, afficher une alerte de quota, ne pas multiplier les snapshots et éviter de générer une copie complète de la base.

## Configuration nécessaire avant un transfert réel

- Bucket Alibaba OSS créé et **privé**, région confirmée, chiffrement et règles d'accès appliqués.
- Méthode d'authentification temporaire définie (RAM/STS/URL signée), secrets absents de GitHub et de l'APK.
- Code source exact de l'application AIV installée disponible pour implémenter ou vérifier l'export en flux.
- Test d'un petit segment non personnel, preuve de relecture et de restauration, puis seulement migration par lots.

## Manifeste d'exemple (FAUX, aucune donnée réelle)

```json
{
  "schema": "aiv-archive-manifest/1",
  "segments": [
    {
      "filename": "segment-000001.aivenc",
      "size_bytes": 12345,
      "sha256_ciphertext": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
  ]
}
```

L'outil `tools/aiv_archive_verify.py` vérifie *hors ligne* les copies locales téléchargées contre un manifeste. Ce n'est **pas** un programme de transfert et il ne supprime aucun fichier.

## État réel des opérations

- [x] Branche de préparation créée, sans données privées.
- [x] Plan de conservation documenté.
- [ ] Destination OSS confirmée et configurée.
- [ ] Export AIV de la version installée étudié.
- [ ] Transfert chiffré testé sur un segment.
- [ ] Restauration indépendante vérifiée.
- [ ] Stockage Android libéré et mesuré.
