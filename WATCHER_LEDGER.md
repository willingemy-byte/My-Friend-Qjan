# Watcher — User Activity

## Portée

Cette branche consigne uniquement les actions, décisions, changements d'outil, configurations et observations directement attribuables à l'utilisateur.

Elle ne contient aucune affirmation sur la cause externe d'un incident.

## Schéma

Chaque entrée comporte :

- **ID**
- **Timestamp**
- **Action**
- **Contexte**
- **Preuve**
- **Statut de preuve**
- **Motivation contemporaine**, si une trace de l'époque l'établit
- **Motivation rapportée plus tard**, si elle provient d'un souvenir ultérieur
- **Résultat observable**

## Événements initiaux

### UA-2026-09-01-001
- **Timestamp :** 2026-09-01 17:03, heure affichée dans ChatGPT
- **Action :** utilisation de `willingemy-byte/Demo-AIV` comme source de vérité GitHub pour une maquette privée du Site All In Visible via ChatGPT Work.
- **Preuve :** captures ChatGPT Work fournies le 25 septembre 2026.
- **Statut :** corroboré par capture.
- **Résultat :** aperçu privé annoncé comme construit depuis le commit vérifié du dépôt `Demo-AIV`.

### UA-2026-09-02-001
- **Timestamp :** 2026-09-02, heure exacte non établie.
- **Action :** utilisation de `Persistent-Memory`.
- **Preuve :** capture de l'historique GitHub du fichier `00_PERSONA/01_ROLE_ORCHESTRATOR.md`, commit `e51b31c`, message `docs(persona): define general orchestrator role`.
- **Statut :** corroboré par capture.
- **Résultat :** mémoire canonique versionnée dans GitHub et contrôlée par l'utilisateur.

### UA-2026-09-11-001
- **Timestamp :** 2026-09-11T18:54:30Z
- **Action :** transfert du Journal local vers `willingemy-byte/My-Friend-Qjan`.
- **Preuve :** commit `7197cbd48c56ccf61dafda0e5063c0a44d96f519`, message `Transférer Journal local v0.4 et les consignes de reprise`.
- **Statut :** corroboré par GitHub.
- **Contexte :** `REPRISE.md` mentionne explicitement l'intention d'utiliser un espace de travail GitHub Codespaces.

### UA-2026-09-11-002
- **Timestamp :** 2026-09-11T18:57:09Z
- **Action :** création du Codespace `super-waddle-96j9pqw7rq7x29v4v` pour `My-Friend-Qjan`.
- **Preuve :** capture du Codespace actif, panneau Copilot rapportant la date exacte de création.
- **Statut :** corroboré par preuve visuelle de l'environnement.
- **Motivation rapportée le 25 septembre :** l'utilisateur explique avoir introduit Codespaces parce que son workflow précédent entre ChatGPT/Work/Codex et GitHub ne lui permettait plus de faire avancer le projet de manière fiable.
- **Statut de cette motivation :** recollection; une trace contemporaine exprimant explicitement ce motif reste à retrouver.

### UA-2026-09-25-001
- **Timestamp :** 2026-09-25, heure exacte de réouverture non établie.
- **Action :** réouverture du Codespace existant et interrogation de Copilot sur sa date de création.
- **Preuve :** captures fournies dans la conversation du 25 septembre.
- **Statut :** corroboré par capture.
- **Résultat :** récupération de la date exacte `2026-09-11T18:57:09Z`.

## Règle pour les prochaines entrées

Une motivation ne devient un fait historique contemporain que lorsqu'une trace datée de l'époque la montre. Sinon, elle reste explicitement marquée comme recollection.
