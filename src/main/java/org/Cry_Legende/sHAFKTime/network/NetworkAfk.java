package org.Cry_Legende.sHAFKTime.network;

import org.Cry_Legende.sHAFKTime.SHAFKTime;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Mode réseau : le temps AFK de chaque joueur est dans la base partagée au lieu de {@code players.yml}.
 *
 * <ul>
 *   <li>à la connexion : une fois le départ de l'autre serveur confirmé, son temps est lu en base (en attendant,
 *       aucune session AFK ne démarre pour lui) ;</li>
 *   <li>tant que le joueur est sur un serveur, la mémoire de ce serveur fait foi (la session AFK décompte en mémoire) ;
 *       la base reçoit sa valeur à chaque sauvegarde automatique, à chaque commande d'administration et à son départ ;</li>
 *   <li>au départ : la barrière de SHNetwork attend que cette écriture soit finie avant que l'autre serveur ne lise ;</li>
 *   <li>une commande d'administration visant un joueur d'un autre serveur y est exécutée, là où se trouve sa session.</li>
 * </ul>
 */
public final class NetworkAfk implements Listener {

    private static final int WRITE_ATTEMPTS = 3;
    private static final int LOAD_RETRY_SECONDS = 5;
    private static final int LOAD_RETRY_MAX = 60;
    private static final long PRELOAD_KEEP_MS = 120_000L;

    private record Preloaded(CompletableFuture<Long> seconds, long at) {
    }

    private final SHAFKTime plugin;
    private final AfkDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "SHAFKTime-reseau");
        thread.setDaemon(true);
        return thread;
    });
    /** Temps lus pendant la pré-connexion, repris à l'arrivée du joueur. */
    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();

    public NetworkAfk(SHAFKTime plugin, AfkDatabase db, NetworkLink link, Duration waitMax) {
        this.plugin = plugin;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
        io.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        io.setRemoveOnCancelPolicy(true);
    }

    public NetworkLink link() {
        return link;
    }

    /** Branche le mode réseau : lecture à la connexion et sauvegarde au départ de chaque joueur. */
    public void start(Function<Player, CompletableFuture<Void>> saveTask) {
        link.registerSaveTask(saveTask);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Termine les écritures en cours puis ferme la base. */
    public void stop() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des écritures de temps AFK n'ont pas pu être terminées à l'arrêt.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    // ------------------------------------------------------------------ lecture

    /**
     * Lance la lecture du temps dès la pré-connexion, sans l'attendre : le serveur quitté ne sauvegarde le joueur
     * qu'une fois cette connexion terminée (le proxy ferme l'ancienne connexion après), attendre ici bloquerait la
     * connexion jusqu'au délai maximal. La lecture se fait sur le fil de la base une fois le départ confirmé.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        long now = System.currentTimeMillis();
        preloaded.values().removeIf(p -> now - p.at() > PRELOAD_KEEP_MS);
        CompletableFuture<Long> seconds = new CompletableFuture<>();
        preloaded.put(uuid, new Preloaded(seconds, now));
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + name + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : temps AFK relu quand même.");
            }
            try {
                io.execute(() -> {
                    try {
                        seconds.complete(db.load(uuid));
                    } catch (SQLException e) {
                        // le joueur entre quand même : son temps est relu dès que la base répond (voir loadLater)
                        plugin.getLogger().warning("Temps AFK de " + name + " non lu en base : " + e.getMessage());
                        seconds.completeExceptionally(e);
                    }
                });
            } catch (RejectedExecutionException stopped) {
                seconds.completeExceptionally(stopped);
            }
        });
    }

    /** Temps lu à la pré-connexion du joueur qui entre, s'il l'est déjà ; sinon vide (voir loadLater). */
    public OptionalLong takePreloaded(UUID uuid) {
        Preloaded loaded = preloaded.get(uuid);
        if (loaded == null || !loaded.seconds().isDone() || loaded.seconds().isCompletedExceptionally()) {
            return OptionalLong.empty();
        }
        preloaded.remove(uuid, loaded);
        return OptionalLong.of(loaded.seconds().join());
    }

    /**
     * Lit le temps d'un joueur qui entre : reprend la lecture lancée à sa pré-connexion, sinon (rechargement à chaud,
     * base absente à sa connexion) lit en base. Réessaie tant que la base ne répond pas ; {@code onMain} reçoit le
     * temps sur le fil principal.
     */
    public void loadLater(UUID uuid, Consumer<Long> onMain) {
        Preloaded started = preloaded.remove(uuid);
        if (started != null) {
            // lecture lancée à la pré-connexion, pas encore finie : reprise telle quelle, relancée si elle a échoué
            started.seconds().whenComplete((seconds, error) -> {
                if (error == null) {
                    toMain(() -> onMain.accept(seconds));
                } else {
                    submit(() -> loadOnce(uuid, onMain, 0));
                }
            });
            return;
        }
        submit(() -> loadOnce(uuid, onMain, 0));
    }

    private void loadOnce(UUID uuid, Consumer<Long> onMain, int attempt) {
        try {
            long seconds = db.load(uuid);
            toMain(() -> onMain.accept(seconds));
        } catch (SQLException e) {
            plugin.getLogger().warning("Temps AFK de " + uuid + " non lu en base : " + e.getMessage());
            if (attempt + 1 >= LOAD_RETRY_MAX) {
                plugin.getLogger().severe("Temps AFK de " + uuid + " jamais lu en base, abandon.");
                return;
            }
            try {
                io.schedule(() -> loadOnce(uuid, onMain, attempt + 1), LOAD_RETRY_SECONDS, TimeUnit.SECONDS);
            } catch (RejectedExecutionException stopped) {
                // plugin arrêté
            }
        }
    }

    /** Lit le temps d'un joueur sans le charger (commande {@code check} sur un joueur d'un autre serveur). */
    public void read(UUID uuid, Consumer<Long> onMain) {
        submit(() -> {
            Long seconds = null;
            try {
                seconds = db.load(uuid);
            } catch (SQLException e) {
                plugin.getLogger().warning("Temps AFK de " + uuid + " non lu en base : " + e.getMessage());
            }
            Long result = seconds;
            toMain(() -> onMain.accept(result));
        });
    }

    // ------------------------------------------------------------------ écriture

    /** Écrit les temps donnés ; le futur se termine quand la base les a reçus (ou après les essais). */
    public CompletableFuture<Void> save(Map<UUID, Long> balances) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (balances.isEmpty()) {
            done.complete(null);
            return done;
        }
        Map<UUID, Long> copy = new HashMap<>(balances);
        try {
            io.execute(() -> {
                try {
                    write(copy);
                } finally {
                    done.complete(null);
                }
            });
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    private void write(Map<UUID, Long> balances) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                db.save(balances);
                return;
            } catch (SQLException e) {
                if (attempt == WRITE_ATTEMPTS) {
                    plugin.getLogger().log(Level.SEVERE, "Temps AFK non écrit en base (" + balances + ") : " + e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ importation

    /**
     * Copie les temps de l'ancien players.yml dans la base, sans toucher aux joueurs qui y ont déjà une ligne.
     * {@code done} reçoit {importés, déjà présents} sur le fil principal, ou null si la base a refusé.
     */
    public void importBalances(Map<UUID, Long> balances, Consumer<int[]> done) {
        submit(() -> {
            int[] result;
            try {
                result = db.importBalances(balances);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Importation des temps AFK impossible : " + e.getMessage());
                result = null;
            }
            int[] finalResult = result;
            toMain(() -> done.accept(finalResult));
        });
    }

    // ------------------------------------------------------------------ fils

    private void toMain(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException stopped) {
            // plugin arrêté : plus personne à prévenir
        }
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à faire
        }
    }
}
