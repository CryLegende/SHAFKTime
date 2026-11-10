<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHAFKTime

*Rapporte des SHCoins pour le temps passé AFK dans les mondes autorisés, avec bossbar et boosts par permission.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Rapporte des SHCoins pour le temps passé AFK dans les mondes autorisés, avec bossbar et boosts par permission.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `Vault`
- Optionnelles : `SHNetwork` (mode réseau)

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent le temps AFK :

- il est en MariaDB (table `shafktime` : `uuid`, `seconds`) au lieu de `players.yml` ;
- à la connexion, une fois que l'autre serveur a fini de sauvegarder le joueur, son temps est lu en base ;
- tant que le joueur est sur un serveur, la mémoire de ce serveur fait foi (la session AFK décompte en mémoire) ; la base reçoit sa valeur à chaque sauvegarde automatique, après chaque commande d'administration et à son départ, avant que l'autre serveur ne le lise ;
- `add`, `take` et `set` visant un joueur d'un autre serveur lui sont transmis et exécutés là où se trouve sa session ; `check` lit la base ;
- le temps déjà dans `players.yml` se copie une fois avec `/shafktime import` (rien n'est écrasé).

Les mondes autorisés (`whitelist-worlds`) n'existent que sur le serveur du spawn : les sessions AFK ne tournent que là. Le temps, lui, se remplit depuis n'importe quel serveur (votes, boutique), d'où la base partagée.

Sans SHNetwork, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : `players.yml`. Si la base est injoignable au démarrage, il reste en mode local. La table se crée toute seule, ou à la main avec `sql/schema.sql`.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shafktime` | — | Commande de gestion du temps AFK. | `shafktime.admin` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shafktime.admin` | Permet d'utiliser les commandes administratives du plugin SHAFKTime. | `op` |
| `shafktime.boost` | Permission de base pour appliquer un boost au gain (à utiliser avec le suffixe numérique, ex: shafktime.boost.20). | `true` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHAFKTime/
└── <a href="src/main/resources/config.yml">config.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
