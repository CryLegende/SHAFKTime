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

Racine d'administration `/shafktime` (console comprise), sans alias. Sans argument, elle rappelle les sous-commandes que l'on a le droit d'utiliser.

| Commande | Description | Permission |
|---|---|---|
| `/shafktime check <joueur>` | Temps AFK en réserve. | `shafktime.admin.check` |
| `/shafktime add <joueur> <durée>` | Ajoute du temps (`5s`, `10m`, `2h`, `1d`). | `shafktime.admin.add` |
| `/shafktime take <joueur> <durée>` | Retire du temps. | `shafktime.admin.take` |
| `/shafktime set <joueur> <durée>` | Fixe le temps. | `shafktime.admin.set` |
| `/shafktime import` | Mode réseau : copie les temps de `players.yml` dans la base, sans rien écraser. | `shafktime.admin.import` |
| `/shafktime reload` | Recharge `config.yml` et `messages.yml`. Un fichier illisible ne change rien : le message donne le fichier et l'erreur. `players.yml` n'est jamais relu, la section `reseau` demande un redémarrage. | `shafktime.admin.reload` |

`add`, `take`, `set` et `check` visent un joueur connecté. `add` est aussi lancé en console par les récompenses de vote, la boutique et les caisses.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shafktime.admin` | Toute l'administration (parent de toutes les permissions `shafktime.admin.*`). | `op` |
| `shafktime.admin.check` | `/shafktime check`. | `op` |
| `shafktime.admin.add` | `/shafktime add`. | `op` |
| `shafktime.admin.take` | `/shafktime take`. | `op` |
| `shafktime.admin.set` | `/shafktime set`. | `op` |
| `shafktime.admin.import` | `/shafktime import`. | `op` |
| `shafktime.admin.reload` | `/shafktime reload`. | `op` |
| `shafktime.boost` | Permission de base pour appliquer un boost au gain (à utiliser avec le suffixe numérique, ex: shafktime.boost.20). | `true` |
| `shafktime.boost.<n>` | Gain majoré de n % (la plus grande valeur est retenue), par exemple `shafktime.boost.20`. | non déclarée |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHAFKTime/
├── <a href="src/main/resources/config.yml">config.yml</a>     réglages du gain, mondes, bossbar et actionbar, mode réseau
├── <a href="src/main/resources/messages.yml">messages.yml</a>   messages du plugin
└── players.yml    temps AFK de chaque joueur (données, créé par le plugin)
</pre>

Au premier démarrage, `messages.yml` est créé à partir des sections `messages` et `admin-messages` de l'ancien `config.yml` si elles existent, puis complété par les valeurs du jar. Ces sections ne sont plus lues ensuite. Le texte de la bossbar et celui de l'actionbar restent dans `config.yml`, avec leurs options d'affichage.

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
