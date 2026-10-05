# AIV 2.0.10 — accueil et maintien des droits

## Accueil

Les définitions A1–A5 apparaissent dans cinq encadrés avec les couleurs existantes : vert, jaune, orange, rouge et violet. Les trois grands boutons Android, Système et Utilisateur montrent le nombre de packages et leur répartition par grade avant sélection. Une sélection affiche toute la catégorie sous le bouton Statut, classée par grade décroissant puis par nom. Le rendu arrive par petits lots pour laisser l’interface répondre; il n’y a pas de plafond de 120 résultats sur cette page.

Chaque package appartient à une seule catégorie. Android contient les UID réservés, partagés ou dont l’attribution n’est pas unique; Système contient les packages système ou système mis à jour dont l’UID est unique; Utilisateur contient les autres packages à UID unique. Les compteurs concernent les packages du dernier inventaire achevé, dans le profil courant. Ils ne comptent pas les flux ou les paquets réseau. Un UID partagé n’identifie toujours pas l’auteur précis d’un flux.

Les grades viennent de l’analyse existante du même inventaire. Cette version ne change pas les règles de classement : le moteur local actuel enregistre surtout les niveaux A4/A5. Une application sans grade A1–A5 reste visible et est comptée séparément; aucun grade A1 n’est inventé pour compléter les totaux.

Statut ouvre les états détaillés, les modes d’utilisation et les commandes de collecte, d’inventaire et d’export.

## Activer le maintien

1. Démarrer Shizuku et vérifier qu’AIV y est autorisée.
2. Dans AIV, ouvrir Shizuku puis Maintien automatique des droits.
3. Appuyer sur Enregistrer les refus actuels et activer, puis confirmer Enregistrer et maintenir.
4. Attendre la fin de la préparation. L’écran affiche son avancement, puis Maintien actif et le nombre de refus enregistrés.

AIV démarre son collecteur local pour ce suivi. Le VPN n’est pas requis. La planification demande un contrôle toutes les 15 secondes; chaque cycle est borné, et Android peut retarder l’exécution. Après une référence déjà créée, Reprendre le maintien réutilise la référence. Vérifier maintenant demande un cycle immédiat. Mettre le maintien en pause conserve la référence et suspend les interventions.

Zéro correction signifie qu’aucun retour de droit n’a été corrigé. Zéro refus enregistré signifie qu’aucun refus admissible n’est encore suivi. Les refus manuels doivent être vérifiables et appartenir aux catégories choisies ou aux retraits confirmés.

Applications en attente d’approbation désigne les nouvelles identités à examiner; ce n’est pas l’état d’activation du maintien. Leur désactivation après détection demeure une option distincte.

Le suivi réapplique les refus qu’Android et Shizuku permettent de vérifier. La page distingue préparation, pause, maintien activé, attente de Shizuku et attente du collecteur. Un arrêt forcé, une perte de Shizuku ou une suspension Android peut interrompre le suivi.

## Validation

- Compilation SDK 35/JDK 17 réussie, 52 dépréciations existantes; aucune erreur Java.
- ApplicationOverview : 25 vérifications, notamment UID partagés, classes exclusives, doublons, grades manquants et 543 applications sans troncature.
- Contrôleur de permissions : 25 vérifications; maintien : 101 vérifications avec observations Android/Shizuku simulées et vrais fichiers de politique.
- Aucun changement aux sources de capture réseau ou aux bibliothèques natives de la 2.0.9.
- Le rendu et le fonctionnement réel sur Samsung Android 16 restent à vérifier sur téléphone.
