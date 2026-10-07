# Installation et conservation des données

Ces fichiers sont des builds de validation signés, pas une annonce de diffusion publique.

FREE 225 → FOUNDER 226 : même `com.allinvisible.aiv`, même certificat AIV. Ne pas désinstaller FREE. Après paiement vérifié, le bouton d'installation télécharge l'APK depuis l'origine officielle configurée, vérifie SHA-256, signature cryptographique avec apksig, certificat, package et versionCode supérieur. Android propose ensuite une mise à jour normale. L'utilisateur peut refuser. Aucun contournement de REQUEST_INSTALL_PACKAGES ou du Package Installer.

L'application ne lance aucune suppression des bases, préférences ou identités. Les données de licence sont enregistrées avant téléchargement; elles demeurent disponibles dans la mise à jour. Les identités de sauvegarde et de VPN préexistantes ne sont pas régénérées. Les nouvelles préférences et tables d'état de sauvegarde sont additives. Les modifications ultérieures de schéma nécessiteront snapshot cohérent + migration testée; aucune modification du schéma historique n'est faite ici.

Pour chaque nouvelle livraison, prendre un versionCode au-dessus de **toutes** les éditions précédemment distribuées. Exemple : FREE 227, FOUNDER 228. Après Founder 226, Founder 228 est une mise à jour. Un appareil Founder ne doit pas recevoir automatiquement un APK FREE plus récent; le manifeste de mise à jour doit sélectionner l'édition en fonction de l'entitlement.

Avant diffusion : export local et copie de réglages de test, relever le nombre d'événements, les empreintes d'identité, les préférences et les historiques; installer FREE puis Founder sans désinstaller; comparer. Faire la même vérification après une deuxième mise à jour Founder. Ce test sur Android n'est pas effectué ici faute d'appareil/émulateur connecté. La concordance package/signature/version est vérifiée dans les APK; elle ne remplace pas la preuve de conservation sur appareil.
