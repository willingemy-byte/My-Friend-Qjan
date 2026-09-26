# WATCHER — Journal chronologique V20

Ce journal consigne des événements observés pendant le développement de V20.
Il sépare les faits observables des causes non établies.

## Jour 1 — 2026-09-25 — Première tentative de chaîne de build V20

### Contexte

La chaîne GitHub destinée à V20 a été mise en place à partir de la base V19 validée.

Branches de travail utilisées :
- `aiv-reference-engine` : moteur de référence Bayton/AOSP + Exodus et tests.
- `aiv-build-ci` : chaîne GitHub Actions pour produire un APK de test non signé.
- `aiv-design-v20` : travail visuel V20, séparé du moteur et du build.

Important : les premiers builds utilisent encore la base applicative V19. Il s'agit de valider la chaîne qui servira ensuite à V20, pas encore d'une release V20 finale.

### Série d'événements observés

1. Le workflow GitHub Actions de build APK est lancé.
2. Premier blocage : `bootstrap-clean-build.sh` échoue sous `set -u` avec `name: unbound variable`.
3. Le correctif initial contient par erreur un `\\n` littéral ; il est ensuite corrigé avec un vrai saut de ligne.
4. Le build avance jusqu'au téléchargement du toolchain Android.
5. Les archives Android Platform 35, Build Tools 35 et NDK r27d sont téléchargées et leurs SHA-256 validés.
6. Maven Central renvoie HTTP 429 pour `ecj-3.40.0.jar`.
7. La chaîne est modifiée pour permettre l'utilisation de `javac` Java 17 sur GitHub Actions au lieu de dépendre d'ECJ.
8. Le build avance ensuite jusqu'aux tests source.
9. Le test `tests/audit-rules.cjs` s'arrête parce que `tools/audit-rules.js` n'est pas présent dans la copie GitHub de la base V19.
10. L'inspection de ce test montre qu'il doit vérifier 29 cas : seuils de classification, références absentes, provenance, versions, UID partagés/non attribuables et conservation du groupe/package de Google Photos.

### Événement d'interface observé pendant cette même session

Pendant l'analyse du test d'audit, une question a été dictée avec de la musique en arrière-plan.

Observation rapportée et visible dans la capture :
- une première transcription audio incorrecte a été envoyée suffisamment loin pour que ChatGPT commence à réfléchir ;
- l'utilisateur a vu que la transcription ne correspondait pas à ce qu'il avait dit et a recommencé sa question ;
- le message suivant visible commence par : « C'est pas ça que j'ai dit » ;
- lorsque l'historique a été regardé ensuite, la première question/transcription n'était plus visible dans la conversation.

Cette entrée constate uniquement la séquence observée. Elle n'attribue pas de cause à la disparition du premier tour et ne conclut pas à une intention.

### Preuve associée

Capture fournie par l'utilisateur :
- fichier d'origine : `10665.jpg`
- dimensions : 787 × 1536
- SHA-256 : `588a51247ca8a9fcec2f09177fd1c1b0565bb8943068255f3d5c046ca0b93f00`
- la capture montre le message visible à 22:20 commençant par « C'est pas ça que j'ai dit » ainsi que la réponse sur `aiv-v19/tests/audit-rules.cjs`.

La capture binaire n'est pas incluse dans ce commit ; son empreinte est enregistrée ici pour permettre une comparaison ultérieure avec l'original.

### État à la fin de cette entrée

- Tests du moteur de référence Bayton/Exodus : exécutés avec succès dans GitHub Actions.
- Build APK : chaîne fonctionnelle jusqu'aux tests source ; bloquée sur une dépendance de test V19 manquante, `tools/audit-rules.js`.
- Aucune release V20 finale n'a été produite à ce stade.
