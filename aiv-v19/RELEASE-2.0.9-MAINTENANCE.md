# All In Visible 2.0.9 — maintien des refus et nouvelles applications

Branche `aiv-permission-maintenance-2.0.9`, depuis la 2.0.8 `66d2f6138bce20c0c45b4bac4c126e934df0bd81`. Paquet `com.allinvisible.aiv`, code 209, minimum Android 26, cible 35. La clé de signature compatible reste hors du dépôt.

## Comportement

Un nettoyage Shizuku terminé ne constituait pas une politique de maintien des droits retirés. Cette version ajoute un suivi local explicitement activé dans **Shizuku → Maintien automatique des droits → Enregistrer les refus actuels et activer**.

La référence enregistre les refus runtime dangereux vérifiés à la fois par Android et le relevé Shell, ainsi que les accès spéciaux dont l’AppOp est explicitement `ignore` ou `deny`. Les catégories choisies, les usages particuliers et les reçus de retraits confirmés déterminent les droits admissibles. Les refus vérifiables réglés manuellement dans Android sont inclus. Un état absent, inconnu ou fixé par Android n’est pas fabriqué comme refus maintenable. Les applications présentes dont l’identité est vérifiée deviennent la référence approuvée.

Le suivi fonctionne sur un worker distinct de la capture VPN. Tant que la collecte et Shizuku sont disponibles, il relit les droits enregistrés, réapplique un refus revenu à l’état autorisé, puis vérifie le résultat. Le témoin Shizuku vert indique la disponibilité du service et de l’autorisation; le nouvel état **Maintien** indique le suivi effectif. Une perte de Shizuku conserve l’intention sans lancer de commande; son retour permet la reprise. Une pause ou l’arrêt de la collecte empêche de nouvelles commandes; la commande déjà partie peut finir.

Ajouter les refus actuels fusionne les refus vérifiés avec la référence existante. Cela n’accepte pas silencieusement de nouvelles applications et ne remplace pas un ancien refus par un droit revenu à l’état autorisé. Les retraits Shizuku confirmés ajoutent aussi leur règle au suivi lorsqu’une référence existe. Une restauration volontaire dans AIV retire la règle avant de réaccorder le droit, y compris si le droit avait déjà été rétabli manuellement.

## Nouvelles installations

Une application absente de la référence, réinstallée ou dont le signataire/UID a changé apparaît dans la liste d’approbation. Les mises à jour du même propriétaire vérifié conservent les refus. Une identité non vérifiable reste visible pour examen manuel, sans désactivation automatique.

Le mode **Désactiver les nouvelles applications après détection** est distinct et désactivé au départ. Une fois activé, AIV tente `disable-user`, puis `force-stop`, uniquement sur une application admissible du profil courant. Les composants système, les applications système mises à jour, les rôles protégés et les UID partagés restent à examiner. L’approbation explicite est enregistrée avant une éventuelle réactivation afin qu’une erreur de disque ne provoque pas une nouvelle désactivation après réactivation.

Ce mode intervient après installation et détection. Il ne constitue pas un veto à l’installation ni une garantie d’absence d’activité avant détection. Une connexion réseau « Application non identifiée » n’est pas assimilée à une nouvelle installation.

## Vérification et bornes

- Un cycle est demandé toutes les 15 secondes par le collecteur; un événement de package demande aussi un passage. Les demandes concurrentes sont coalescées, sans file de tâches croissante. Les contrôleurs de permissions et de packages partagent le même verrou d’exécution.
- Chaque passage peut traiter au plus 12 commandes de changement et 20 cibles AppOps, avec rotation entre les cibles. Les lectures de confirmation sont supplémentaires. La fenêtre d’ordonnancement vaut 30 secondes; un appel déjà lancé peut finir dans sa borne Shell de 12 secondes. Le délai effectif dépend du volume de règles, de la veille Android, des API et des autres interventions.
- L’état local est borné à 10 000 droits, 20 000 applications connues/en attente et 8 Mio par fichier. La préparation a une borne de 15 minutes; une préparation arrêtée n’active pas de référence partielle.
- Avant chaque commande: identité APK, UID, installation, rôle et portée recontrôlés; reçu durable écrit avant exécution, puis nouvelle vérification après cette écriture. Un code de retour nul seul n’est jamais une confirmation. Trois échecs suspendent cette cible; un changement d’identité/rôle suspend le droit concerné.
- Une erreur d’écriture du reçu de maintien suspend les actions automatiques. Le dernier reçu conserve les états avant/après; l’export de permissions ajoute la politique et le dernier résultat. Aucun log personnel ni secret n’est inclus dans les tests ou le dépôt.

## Limites

Les permissions normales d’installation telles qu’`INTERNET`, les permissions de signature, les verrous Android et les réglages réseau propres au fabricant ne sont pas maintenus par ce mécanisme. AIV ne transforme pas un réglage de données mobiles ou d’arrière-plan en révocation du droit Android `INTERNET`. Les refus sont réappliqués après leur changement; leur réapparition n’est pas empêchée à la source.

Un plan à zéro signifie qu’aucun retrait admissible n’est proposé dans les catégories examinées. Le pourcentage d’attribution de Flux concerne les flux admissibles de la page affichée. Ni l’un ni l’autre ne certifie l’intégralité du téléphone. Les sources du moteur VPN, de l’observateur UID, du relais natif et du journal SQLite restent identiques à la 2.0.8.

## Validation hôte et essai téléphone

La validation hôte exerce le vrai contrôleur de maintien avec fichiers AtomicFile et observations Android/Shizuku simulées: retour de droits, refus manuels, perte/reprise Shizuku, pause/arrêt, mise à jour/réinstallation/signataire, approbation durable, rôle changé pendant l’écriture du reçu, restauration réelle par PermissionControl, erreurs de journal/disque, refus de commande, code nul sans changement, bornes et rotation. Les tests existants de permissions, attribution, C/JNI, volumes et export SQLite passent aussi. Le [registre de build](BUILD-NOTES-2.0.9-MAINTENANCE.md) classe les diagnostics; les [instructions de test](tests/README-2.0.9.md) décrivent les fixtures. Aucun test sur téléphone ou émulateur n’est revendiqué.

Pour l’essai sur téléphone:

1. Installer 2.0.9 en mise à jour, sans désinstaller 2.0.8. Vérifier la version dans l’en-tête et dans Android. Confirmer que le VPN reste connecté pendant les passages AIV/Chrome/ChatGPT.
2. Garder Shizuku lancé et AIV autorisée. Activer le maintien, attendre la fin de la référence, puis consulter le nombre de refus enregistrés et les erreurs éventuelles. Le suivi est activé explicitement, jamais déduit de la présence d’un témoin vert.
3. Sur une application de test sans rôle essentiel, réaccorder un droit préalablement enregistré. Vérifier sa nouvelle révocation et le reçu confirmé. Ne pas utiliser une application de navigation, d’accessibilité ou un composant système pour cet essai.
4. Mettre le maintien en pause; vérifier qu’il laisse le droit réaccordé. Reprendre; vérifier la correction. Arrêter Shizuku puis le relancer; vérifier l’état de suspension et la reprise sans créer de nouvelle référence.
5. Effectuer un retrait dans AIV puis sa restauration explicite. Vérifier que le suivi ne défait pas cette restauration.
6. Facultativement, activer le mode des nouvelles installations et installer une application de test connue. Vérifier sa présence en attente et sa désactivation après détection, puis l’approuver et vérifier qu’elle reste activée. Examiner séparément les cas protégés.
7. Exporter les résultats de permissions et une période réseau comparable. Conserver les états inconnus et les limites de couverture; ne pas annoncer un taux global à partir d’une page.
