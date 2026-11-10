package org.Cry_Legende.sHAFKTime.network;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Ce dont le mode réseau a besoin de SHNetwork. Cette interface ne mentionne aucune classe de SHNetwork :
 * le plugin la manipule sans jamais charger {@link ShNetworkLink}, donc sans SHNetwork il ne se passe rien.
 */
public interface NetworkLink {

    /** Joueur connecté quelque part sur le réseau. */
    record Remote(UUID uuid, String name, String server) {
    }

    /** Nom de ce serveur sur le réseau. */
    String serverName();

    /** Attend que le serveur de départ ait fini de sauvegarder le joueur ; se termine hors du fil principal. */
    CompletableFuture<Boolean> awaitSaved(UUID uuid, Duration timeout);

    /** Inscrit la tâche exécutée au départ de chaque joueur (fil principal, pendant PlayerQuitEvent). */
    void registerSaveTask(Function<Player, CompletableFuture<Void>> task);

    /** Joueur connecté sur le réseau (ce serveur compris), par pseudo ou UUID. */
    Optional<Remote> find(String nameOrUuid);

    /** Exécute une commande console là où se trouve le joueur, une seule fois. */
    void runFor(String nameOrUuid, String consoleCommand);
}
