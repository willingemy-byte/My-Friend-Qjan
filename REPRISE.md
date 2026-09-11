# Reprise du projet — 11 septembre 2026

## Demande actuelle

Erick souhaite disposer d’un espace de travail distinct dans GitHub Codespaces et y utiliser un exécuteur de code facturé sur son compte API OpenAI. Il utilise surtout son téléphone Samsung SM-S731W sous Android 16.

Priorité : transférer les fichiers existants dans un dépôt neuf nommé My Friend Qjan, puis connecter l’exécuteur. Ne pas analyser les anciens dépôts ou recommencer les recherches. Ne pas modifier l’application pendant ce transfert.

## Source de référence

Les 60 fichiers de `journal-local/` proviennent de l’archive livrée `journal-local-sources.zip` v0.4.

SHA-256 de l’archive originale :
`ae859660fffe51f73dd5eb351fc9f504876ff0e6f3ad7d05ce0592986a59510b`

Paquet Android : `fr.erick.journallocal`.

Les résultats des vérifications livrées sont dans `journal-local/verification.json`. Ce transfert ne constitue pas un nouveau test sur téléphone. La clé privée de signature n’est pas incluse : conserver la signature de l’application déjà installée sera nécessaire pour une mise à jour compatible.

## Décisions et limites à conserver

- **Proxy : choix non arrêté.** `docs/recherche-proxys-2026-09-11.md` conserve la recherche antérieure. Toute formulation de « raccord retenu » dans ce document est remplacée par cette décision actuelle.
- Une ligne de journal n’est pas nécessairement un message réseau distinct. Les compteurs de volume peuvent être cumulatifs par flux.
- Une IP distante peut héberger plusieurs services. Elle ne prouve pas à elle seule le nom d’un site ou l’origine d’une activité.
- Un UID partagé, notamment 1000, ne permet pas d’attribuer un flux à un paquet précis sans preuve supplémentaire. Une proximité temporelle n’est pas une preuve de causalité.
- Ne pas promettre le déchiffrement de tout le trafic. Conserver les limites de capture DNS/SNI/ECH et du système Android.
- Préserver les permissions limitées de l’application et distinguer les observations des hypothèses.

## Suite autorisée

1. Vérifier que le dépôt distant contient bien les sources transférées.
2. Configurer un exécuteur API dans le Codespace avec un mécanisme de secret adapté. Ne jamais écrire la clé dans Git, un fichier public, le terminal ou le chat.
3. Commencer par une tâche bornée, avec un seul lancement et un résultat consultable. Pas de boucle autonome illimitée ni d’appels facturés sans configuration explicite.
4. Reprendre ensuite les améliorations de l’application selon les instructions d’Erick.

Les analyses personnelles et les exports de trafic sont conservés séparément ; ils ne doivent pas être ajoutés à ce dépôt public.
