<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHAFKTime

*Plugin du serveur SkyHand.*

<img src="https://img.shields.io/badge/Paper-1.20.2-f2a900?style=for-the-badge" alt="Paper 1.20.2"> <img src="https://img.shields.io/badge/Java-17-e76f00?style=for-the-badge" alt="Java 17"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Plugin du serveur SkyHand.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `Vault`

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

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`) :

- `config.yml`

## Compilation

Java 17 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
