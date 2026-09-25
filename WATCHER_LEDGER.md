# Watcher — System Events

## Portée

Cette branche consigne uniquement les événements externes documentés : incidents publics, changements de service, mises à jour, limitations et états opérationnels.

Elle ne contient aucune affirmation sur l'activité de l'utilisateur comme cause de ces événements.

## Schéma

- **ID**
- **Timestamp / période**
- **Fournisseur**
- **Composant**
- **Événement**
- **Source**
- **Lien causal officiel**, uniquement lorsqu'une source l'établit elle-même

## Événements initiaux

### SE-2026-08-27-001
- **Fournisseur :** OpenAI
- **Événement :** hausse des erreurs dans Workspace Agents et ChatGPT Work sur Web/Mobile.
- **Période publiée :** 2026-08-27.
- **Source :** OpenAI Status.

### SE-2026-08-31-001
- **Fournisseur :** OpenAI
- **Événement :** `ChatGPT Work seeing elevated errors and latency`.
- **Période publiée :** 2026-08-31.
- **Source :** OpenAI Status.

### SE-2026-09-01-001
- **Fournisseur :** OpenAI
- **Événement :** latence élevée dans Responses API, incident commencé le 31 août et résolu le 1er septembre.
- **Source :** OpenAI Status.

### SE-2026-09-01-002
- **Fournisseur :** GitHub
- **Événement :** délais dans le traitement des commits; les mises à jour après push pouvaient être retardées.
- **Source :** GitHub Status.

### SE-2026-09-03-001
- **Fournisseur :** OpenAI
- **Événement :** `ChatGPT Work Mode High Error Rates`.
- **Composants :** Work, Codex Desktop, Search, uploads, Agent, Connectors/Apps, autres.
- **Source :** OpenAI Status.

### SE-2026-09-03-002
- **Fournisseur :** OpenAI
- **Événement :** `Elevated errors across ChatGPT and Codex`.
- **Composants :** Work, Connectors/Apps, Codex Web/API/CLI/VS Code, autres.
- **Source :** OpenAI Status.

### SE-2026-09-10-001
- **Fournisseur :** OpenAI
- **Événement :** `Elevated errors affecting ChatGPT Work`.
- **Source :** OpenAI Status.

### SE-2026-09-11-001
- **Fournisseur :** OpenAI
- **Événement :** environ 1 % des tours ChatGPT Work sur fils existants échouent; incident prolongé au 12 septembre.
- **Source :** OpenAI Status.

### SE-2026-09-13-001
- **Fournisseur :** GitHub
- **Événement :** panne multi-services touchant environ 28 services, notamment Pull Requests, Actions et Codespaces.
- **Source :** GitHub Status.

### SE-2026-09-13-002
- **Fournisseur :** OpenAI
- **Événement :** `Codex GitHub Review and Pull Request Failures`.
- **Lien causal officiel :** OpenAI indique une perturbation GitHub en amont affectant reviews et création de pull requests dans Codex.
- **Source :** OpenAI Status.

### SE-2026-09-14-001
- **Fournisseur :** OpenAI
- **Événement :** taux d'erreur élevés pour Codex et ChatGPT Work.
- **Source :** OpenAI Status.

### SE-2026-09-14-002
- **Fournisseur :** OpenAI
- **Événement :** erreurs Work Mode; accès limité aux outils du workspace et aux fichiers pour certains utilisateurs.
- **Source :** OpenAI Status.

### SE-2026-09-15-001
- **Fournisseur :** GitHub
- **Événement :** perturbation de Copilot Code Review.
- **Source :** GitHub Status.

### SE-2026-09-16-001
- **Fournisseur :** OpenAI
- **Événement :** erreurs élevées dans ChatGPT Work.
- **Source :** OpenAI Status.

### SE-2026-09-17-001
- **Fournisseur :** OpenAI
- **Événement :** erreurs élevées dans ChatGPT Work.
- **Source :** OpenAI Status.

### SE-2026-09-17-002
- **Fournisseur :** OpenAI
- **Événement :** taux d'erreur élevés sur plusieurs modèles/API.
- **Source :** OpenAI Status.

### SE-2026-09-17-003
- **Fournisseur :** GitHub
- **Événement :** erreurs élevées sur plusieurs modèles OpenAI fournis via Copilot.
- **Source :** GitHub Status.

### SE-2026-09-20-001
- **Fournisseur :** GitHub
- **Événement :** dégradation du service Pull Requests avec retards de commits merge/test-merge et certains workflows Actions.
- **Source :** GitHub Status.

### SE-2026-09-22-001
- **Fournisseur :** OpenAI
- **Événement :** erreurs élevées ChatGPT Work sur plusieurs plans.
- **Source :** OpenAI Status.

### SE-2026-09-23-001
- **Fournisseur :** OpenAI
- **Événement :** utilisateurs mobiles incapables de voir Work Mode et le sélecteur de modèle.
- **Source :** OpenAI Status.

### SE-2026-09-23-002
- **Fournisseur :** GitHub
- **Événement :** incident multi-services touchant API/Projects et se poursuivant au 24 septembre.
- **Source :** GitHub Status.

### SE-2026-09-25-001
- **Fournisseur :** OpenAI
- **Événement :** état public pleinement opérationnel lors de la vérification.
- **Source :** OpenAI Status.

### SE-2026-09-25-002
- **Fournisseur :** GitHub
- **Événement :** état public opérationnel; aucun incident rapporté ce jour lors de la vérification.
- **Source :** GitHub Status.
