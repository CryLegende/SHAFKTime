package org.Cry_Legende.sHAFKTime;

import net.milkbowl.vault.economy.Economy;
import org.Cry_Legende.sHAFKTime.network.AfkDatabase;
import org.Cry_Legende.sHAFKTime.network.NetworkAfk;
import org.Cry_Legende.sHAFKTime.network.NetworkLink;
import org.Cry_Legende.sHAFKTime.network.ShNetworkLink;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SHAFKTime extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Economy economy;
    // Stocke uniquement les joueurs ayant un temps AFK > 0
    private final Map<UUID, Long> afkBalances = new HashMap<>();
    // Sessions AFK actives pour les joueurs dans un monde whitelist
    private final Map<UUID, AfkSession> activeSessions = new HashMap<>();
    // Données de sessions terminées à notifier lors de la reconnexion
    private final Map<UUID, OfflineSessionData> offlineSessions = new HashMap<>();

    private FileConfiguration configFile;
    // Fichier de données pour les joueurs (players.yml)
    private File playersFile;
    private FileConfiguration playersConfig;

    // Tâche d'autosave stockée pour pouvoir l'annuler
    private BukkitTask autosaveTask;

    // Faux tant que players.yml n'a pas été lu : on n'écrit alors jamais le fichier (voir onDisable)
    private boolean playersLoaded = false;

    // Non nul seulement avec SHNetwork : le temps AFK de chaque joueur est dans la base partagée
    private NetworkAfk network;
    // Mode réseau : joueurs dont le temps n'est pas encore lu en base (ni session, ni commande, ni écriture pour eux)
    private final Set<UUID> pending = new HashSet<>();

    // Réglages lus une seule fois (la config n'est jamais rechargée) : les sessions tournent chaque seconde
    private Set<String> whitelistWorlds = Set.of();
    private double moneyPerSecond;
    private int minSession;
    private String timeFormat;
    private boolean actionbarEnabled;
    private String actionbarMessage;
    private boolean bossbarEnabled;
    private String bossbarMessage;
    private BarColor bossbarColor;
    private BarStyle bossbarStyle;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configFile = getConfig();
        loadSettings();
        setupPlayersFile();

        if (!setupEconomy()) {
            getLogger().severe("Vault n'est pas trouvé ou l'économie n'est pas disponible !");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        enableNetwork();

        // Charger les données joueurs depuis players.yml (en mode réseau, le temps de chaque joueur est lu en base à sa connexion)
        if (network == null) {
            loadPlayersData();
        }
        playersLoaded = true;

        getServer().getPluginManager().registerEvents(this, this);
        if (network != null) {
            // joueurs déjà là (rechargement à chaud) : lus comme à une arrivée
            for (Player online : Bukkit.getOnlinePlayers()) {
                beginNetworkJoin(online);
            }
        }
        PluginCommand cmd = getCommand("shafktime");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        int autosaveDelay = Math.max(1, configFile.getInt("autosave-delay", 300));
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, this::asyncSavePlayersData, autosaveDelay * 20L, autosaveDelay * 20L);
    }

    @Override
    public void onDisable() {
        // Annuler l'autosave s'il est actif
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        // Terminer toutes les sessions actives (copie : endSession() retire la session de la map)
        for (AfkSession session : new ArrayList<>(activeSessions.values())) {
            session.endSession();
        }
        // Le scheduler refuse les tâches d'un plugin en cours d'arrêt : écriture finale synchrone.
        // Si Vault manquait au démarrage, les données n'ont pas été lues : écrire effacerait tout le fichier.
        if (playersLoaded) {
            if (network != null) {
                network.save(collectLocalBalances());
            } else {
                writePlayersData(collectPlayersData());
            }
        }
        if (network != null) {
            network.stop();
        }
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        if (!configFile.getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        AfkDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new AfkDatabase(
                configFile.getString("reseau.base-de-donnees.url", ""),
                configFile.getString("reseau.base-de-donnees.utilisateur", ""),
                configFile.getString("reseau.base-de-donnees.mot-de-passe", ""),
                Math.max(250, configFile.getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTable();
            Duration wait = Duration.ofSeconds(Math.max(1, configFile.getInt("reseau.attente-max-secondes", 10)));
            NetworkAfk net = new NetworkAfk(this, db, link, wait);
            net.start(this::flushPlayer);
            network = net;
            getLogger().info("SHNetwork détecté : le temps AFK est dans la base partagée.");
        } catch (Exception | LinkageError e) {
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, temps AFK local à ce serveur : " + e);
        }
    }

    // Mode réseau : lit le temps du joueur qui arrive (déjà lu à sa pré-connexion, sinon en base maintenant)
    private void beginNetworkJoin(Player p) {
        UUID uuid = p.getUniqueId();
        OptionalLong loaded = network.takePreloaded(uuid);
        if (loaded.isPresent()) {
            applyBalance(uuid, loaded.getAsLong());
            return;
        }
        pending.add(uuid);
        network.loadLater(uuid, seconds -> {
            if (!pending.remove(uuid)) return; // parti entre-temps
            applyBalance(uuid, seconds);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && isWorldWhitelisted(online.getWorld())) {
                startSession(online);
            }
        });
    }

    private void applyBalance(UUID uuid, long seconds) {
        if (seconds > 0) {
            afkBalances.put(uuid, seconds);
        } else {
            afkBalances.remove(uuid);
        }
    }

    // Temps du joueur à cet instant : celui de sa session en cours, sinon celui de sa réserve
    private long liveBalance(UUID uuid) {
        AfkSession session = activeSessions.get(uuid);
        return session != null ? session.getRemainingTime() : afkBalances.getOrDefault(uuid, 0L);
    }

    // Mode réseau : temps des joueurs de ce serveur dont la lecture en base est faite
    private Map<UUID, Long> collectLocalBalances() {
        Map<UUID, Long> balances = new HashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            if (!pending.contains(uuid)) {
                balances.put(uuid, liveBalance(uuid));
            }
        }
        return balances;
    }

    // Mode réseau, au départ du joueur (fil principal, après la fin de sa session) : écrit son temps puis l'oublie
    private CompletableFuture<Void> flushPlayer(Player p) {
        UUID uuid = p.getUniqueId();
        if (pending.remove(uuid)) {
            return CompletableFuture.completedFuture(null); // temps jamais lu : on n'écrase rien
        }
        long balance = liveBalance(uuid);
        afkBalances.remove(uuid);
        return network.save(Map.of(uuid, balance));
    }

    // Lit la config une fois pour toutes
    private void loadSettings() {
        whitelistWorlds = new HashSet<>(configFile.getStringList("whitelist-worlds"));
        moneyPerSecond = configFile.getDouble("money-per-second", 1.0);
        minSession = configFile.getInt("min-session", 300);
        timeFormat = configFile.getString("time-format", "{h}h:{m}m:{s}s");
        actionbarEnabled = configFile.getBoolean("actionbar.enabled", false);
        actionbarMessage = ChatColor.translateAlternateColorCodes('&', configFile.getString("actionbar.message", ""));
        bossbarEnabled = configFile.getBoolean("bossbar.enabled", false);
        bossbarMessage = ChatColor.translateAlternateColorCodes('&', configFile.getString("bossbar.message", ""));
        try {
            bossbarColor = BarColor.valueOf(configFile.getString("bossbar.color", "BLUE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            bossbarColor = BarColor.BLUE;
        }
        try {
            bossbarStyle = BarStyle.valueOf(configFile.getString("bossbar.style", "SOLID").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            bossbarStyle = BarStyle.SOLID;
        }
    }

    // Initialise le fichier players.yml
    private void setupPlayersFile() {
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            playersFile.getParentFile().mkdirs();
            saveResource("players.yml", false);
        }
        playersConfig = new YamlConfiguration();
        try {
            playersConfig.load(playersFile);
        } catch (IOException | InvalidConfigurationException e) {
            // Fichier illisible : on le met de côté avant que l'autosave ne l'écrase par des données vides
            getLogger().log(Level.SEVERE, "players.yml illisible, copie de sauvegarde puis démarrage à vide", e);
            try {
                Files.copy(playersFile.toPath(), new File(getDataFolder(), "players.yml.corrompu-" + System.currentTimeMillis()).toPath());
            } catch (IOException copyError) {
                getLogger().log(Level.SEVERE, "Sauvegarde de players.yml impossible", copyError);
            }
            playersConfig = new YamlConfiguration();
        }
    }

    // Configuration de Vault Economy
    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    // Chargement des données joueurs depuis players.yml (seuls ceux avec temps > 0)
    private void loadPlayersData() {
        if (playersConfig.isConfigurationSection("players")) {
            for (String key : playersConfig.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long time = playersConfig.getLong("players." + key + ".afkTime", 0);
                    if (time > 0) {
                        afkBalances.put(uuid, time);
                    }
                } catch (IllegalArgumentException e) {
                    getLogger().warning("UUID invalide dans players.yml: " + key);
                }
            }
        }
    }

    /**
     * Rassemble les données (incluant celles des sessions actives) et lance la sauvegarde asynchrone.
     */
    private void asyncSavePlayersData() {
        if (network != null) {
            network.save(collectLocalBalances());
            return;
        }
        // Récupérer les données sur le thread principal
        Map<UUID, Long> dataToSave = collectPlayersData();
        // Effectuer la sauvegarde asynchrone
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> writePlayersData(dataToSave));
    }

    private Map<UUID, Long> collectPlayersData() {
        Map<UUID, Long> dataToSave = new HashMap<>(afkBalances);
        for (Map.Entry<UUID, AfkSession> entry : activeSessions.entrySet()) {
            dataToSave.put(entry.getKey(), entry.getValue().getRemainingTime());
        }
        return dataToSave;
    }

    // synchronized : une sauvegarde async encore en cours ne doit pas croiser l'écriture de onDisable()
    private synchronized void writePlayersData(Map<UUID, Long> dataToSave) {
        FileConfiguration asyncConfig = new YamlConfiguration();
        asyncConfig.set("players", null);
        for (Map.Entry<UUID, Long> entry : dataToSave.entrySet()) {
            if (entry.getValue() > 0) {
                asyncConfig.set("players." + entry.getKey().toString() + ".afkTime", entry.getValue());
            }
        }
        // Fichier temporaire puis remplacement : un arrêt brutal ne laisse jamais un players.yml tronqué
        Path target = playersFile.toPath();
        Path tmp = target.resolveSibling(playersFile.getName() + ".tmp");
        try {
            Files.writeString(tmp, asyncConfig.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Impossible d'écrire players.yml", e);
        }
    }

    // -------------------- GESTION DES ÉVÉNEMENTS --------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        if (network != null) {
            beginNetworkJoin(p);
        }
        if (offlineSessions.containsKey(uuid)) {
            OfflineSessionData data = offlineSessions.remove(uuid);
            String message = ChatColor.translateAlternateColorCodes('&', configFile.getString("messages.session-end",
                "Tu as passé {time} en AFK et tu as gagné {money} !"));
            message = message.replace("{time}", formatTime(data.sessionSeconds))
                .replace("{money}", String.valueOf(data.moneyEarned))
                .replace("{boost}", String.valueOf(data.boostPercentage));
            p.sendMessage(message);
        }
        if (isWorldWhitelisted(p.getWorld())) {
            if (afkBalances.getOrDefault(uuid, 0L) > 0) {
                startSession(p);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (isWorldWhitelisted(p.getWorld())) {
            if (afkBalances.getOrDefault(p.getUniqueId(), 0L) > 0 && !activeSessions.containsKey(p.getUniqueId())) {
                startSession(p);
            }
        } else {
            endSession(p);
        }
    }

    // MONITOR : un autre plugin peut annuler la téléportation après nous, la session ne doit alors pas changer
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        if (event.getTo().getWorld() == null) return; // monde pas encore chargé : on ne sait rien décider
        if (isWorldWhitelisted(event.getTo().getWorld())) {
            if (afkBalances.getOrDefault(p.getUniqueId(), 0L) > 0 && !activeSessions.containsKey(p.getUniqueId())) {
                startSession(p);
            }
        } else {
            endSession(p);
        }
    }


    // Démarre une session AFK si le joueur a du temps et se trouve dans un monde whitelist
    private void startSession(Player p) {
        UUID uuid = p.getUniqueId();
        if (activeSessions.containsKey(uuid)) return;
        long balance = afkBalances.getOrDefault(uuid, 0L);
        if (balance <= 0) return;
        // Retirer le joueur de afkBalances pour éviter une double gestion
        afkBalances.remove(uuid);
        AfkSession session = new AfkSession(p, balance);
        activeSessions.put(uuid, session);
        session.start();
    }

    // Termine la session AFK active d'un joueur
    private void endSession(Player p) {
        UUID uuid = p.getUniqueId();
        if (activeSessions.containsKey(uuid)) {
            AfkSession session = activeSessions.remove(uuid);
            session.endSession();
        }
    }

    // Vérifie si le monde est dans la whitelist de la config
    private boolean isWorldWhitelisted(World world) {
        return whitelistWorlds.contains(world.getName());
    }

    /**
     * Renvoie le multiplicateur de boost basé sur la permission "shafktime.boost.X".
     * On parcourt toutes les permissions du joueur et on prend la valeur maximale.
     */
    private double getPlayerBoost(Player p) {
        double maxBoost = 0.0;
        for (org.bukkit.permissions.PermissionAttachmentInfo permInfo : p.getEffectivePermissions()) {
            String perm = permInfo.getPermission();
            if (perm.startsWith("shafktime.boost.")) {
                try {
                    double value = Double.parseDouble(perm.substring("shafktime.boost.".length()));
                    if (value > maxBoost) {
                        maxBoost = value;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignorer les permissions mal formées
                }
            }
        }
        return 1.0 + maxBoost / 100.0;
    }

    // Formate le temps selon le pattern défini dans la config (ex: "{h}h:{m}m:{s}s")
    private String formatTime(long seconds) {
        String format = timeFormat;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return format.replace("{h}", String.format("%02d", h))
            .replace("{m}", String.format("%02d", m))
            .replace("{s}", String.format("%02d", s));
    }

    // -------------------- GESTION DES COMMANDES --------------------
    // Commandes : /shafktime check <joueur>, add, take, set
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Récupération des messages d'administration depuis la config
        String usageMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.usage",
            "Usage: /shafktime <check|add|take|set> <joueur> [temps]"));
        String playerNotOnline = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.player-not-online",
            "Le joueur n'est pas en ligne !"));
        String timeInvalid = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.time-invalid",
            "Format de temps invalide."));
        String commandUnknown = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.command-unknown",
            "Commande inconnue."));
        String addedMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.added",
            "Ajouté {time} à {player}."));
        String takenMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.taken",
            "Enlevé {time} à {player}."));
        String setMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.set",
            "Temps AFK de {player} mis à {time}."));
        String checkMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.check",
            "{player} possède {time} de temps AFK."));

        if (args.length == 1 && args[0].equalsIgnoreCase("import")) {
            importPlayersFile(sender);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(usageMsg);
            return true;
        }
        String subCmd = args[0].toLowerCase();
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            if (network != null && forwardToOtherServer(sender, subCmd, args, usageMsg, timeInvalid, commandUnknown, checkMsg)) {
                return true;
            }
            sender.sendMessage(playerNotOnline);
            return true;
        }
        UUID uuid = target.getUniqueId();
        if (network != null && pending.contains(uuid)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.loading",
                "&7&l« &e&lSH &8&l» &7Le temps AFK de &e{player} &7est en cours de chargement, réessaie dans un instant.")
                .replace("{player}", target.getName())));
            return true;
        }
        long current;
        // Récupère le temps courant depuis la session active si présente
        if (activeSessions.containsKey(uuid)) {
            current = activeSessions.get(uuid).getRemainingTime();
        } else {
            current = afkBalances.getOrDefault(uuid, 0L);
        }
        if (subCmd.equals("check")) {
            sender.sendMessage(checkMsg.replace("{time}", formatTime(current)).replace("{player}", target.getName()));
        } else if (args.length < 3) {
            sender.sendMessage(usageMsg);
            return true;
        } else {
            long amount = parseTime(args[2]);
            if (amount < 0) {
                sender.sendMessage(timeInvalid);
                return true;
            }
            switch (subCmd) {
                case "add": {
                    long newTime = current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
                    if (activeSessions.containsKey(uuid)) {
                        activeSessions.get(uuid).addTime(amount);
                    } else {
                        afkBalances.put(uuid, newTime);
                        if (isWorldWhitelisted(target.getWorld())) {
                            startSession(target);
                        }
                    }
                    writeThrough(uuid);
                    sender.sendMessage(addedMsg.replace("{time}", formatTime(amount)).replace("{player}", target.getName()));
                    break;
                }
                case "take": {
                    long newTime = Math.max(0, current - amount);
                    if (activeSessions.containsKey(uuid)) {
                        activeSessions.get(uuid).setTime(newTime);
                    } else {
                        afkBalances.put(uuid, newTime);
                    }
                    writeThrough(uuid);
                    sender.sendMessage(takenMsg.replace("{time}", formatTime(amount)).replace("{player}", target.getName()));
                    break;
                }
                case "set": {
                    if (activeSessions.containsKey(uuid)) {
                        activeSessions.get(uuid).setTime(amount);
                    } else {
                        afkBalances.put(uuid, amount);
                        if (amount > 0 && isWorldWhitelisted(target.getWorld())) {
                            startSession(target);
                        }
                    }
                    writeThrough(uuid);
                    sender.sendMessage(setMsg.replace("{time}", formatTime(amount)).replace("{player}", target.getName()));
                    break;
                }
                default:
                    sender.sendMessage(commandUnknown);
            }
        }
        return true;
    }

    // Mode réseau : écrit tout de suite en base le temps d'un joueur de ce serveur après une commande d'administration
    private void writeThrough(UUID uuid) {
        if (network != null) {
            network.save(Map.of(uuid, liveBalance(uuid)));
        }
    }

    /**
     * Mode réseau : la commande vise un joueur connecté sur un autre serveur. add, take et set y sont exécutés,
     * là où se trouve sa session ; check lit la base (la valeur peut retarder de la durée de la sauvegarde automatique).
     *
     * @return vrai si le joueur est sur un autre serveur (la commande est traitée ou refusée)
     */
    private boolean forwardToOtherServer(CommandSender sender, String subCmd, String[] args, String usageMsg,
                                         String timeInvalid, String commandUnknown, String checkMsg) {
        NetworkLink link = network.link();
        Optional<NetworkLink.Remote> found = link.find(args[1]).filter(r -> !r.server().equalsIgnoreCase(link.serverName()));
        if (found.isEmpty()) {
            return false;
        }
        NetworkLink.Remote remote = found.get();
        if (subCmd.equals("check")) {
            network.read(remote.uuid(), seconds -> {
                if (seconds == null) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.database-error",
                        "&7&l« &c&lSH &8&l» &7La base du réseau ne répond pas.")));
                } else {
                    sender.sendMessage(checkMsg.replace("{time}", formatTime(seconds)).replace("{player}", remote.name()));
                }
            });
            return true;
        }
        if (!subCmd.equals("add") && !subCmd.equals("take") && !subCmd.equals("set")) {
            sender.sendMessage(commandUnknown);
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(usageMsg);
            return true;
        }
        if (parseTime(args[2]) < 0) {
            sender.sendMessage(timeInvalid);
            return true;
        }
        link.runFor(remote.name(), "shafktime " + subCmd + " " + remote.name() + " " + args[2]);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.forwarded",
            "&7&l« &e&lSH &8&l» &e{player} &7est sur le serveur &e{server}&7 : la commande lui a été &etransmise&7.")
            .replace("{player}", remote.name()).replace("{server}", remote.server())));
        return true;
    }

    // /shafktime import : copie les temps de players.yml dans la base du réseau, sans rien écraser
    private void importPlayersFile(CommandSender sender) {
        if (network == null) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', configFile.getString("admin-messages.import-no-network",
                "&7&l« &c&lSH &8&l» &7Cette commande demande le &cmode réseau &7(SHNetwork installé et base joignable).")));
            return;
        }
        Map<UUID, Long> balances = new HashMap<>();
        if (playersConfig.isConfigurationSection("players")) {
            for (String key : playersConfig.getConfigurationSection("players").getKeys(false)) {
                try {
                    balances.put(UUID.fromString(key), playersConfig.getLong("players." + key + ".afkTime", 0));
                } catch (IllegalArgumentException e) {
                    getLogger().warning("UUID invalide dans players.yml: " + key);
                }
            }
        }
        network.importBalances(balances, result -> sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
            result == null
                ? configFile.getString("admin-messages.import-failed", "&7&l« &c&lSH &8&l» &7L'importation a échoué, &cvérifiez la console&7.")
                : configFile.getString("admin-messages.import-done",
                    "&7&l« &a&lSH &8&l» &7Importation terminée : &a{imported} &7joueur(s) ajouté(s), &a{present} &7déjà présent(s).")
                    .replace("{imported}", String.valueOf(result[0])).replace("{present}", String.valueOf(result[1])))));
    }

    // Complétion d'onglets optimisée pour la commande /shafktime
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return network != null ? Arrays.asList("check", "add", "take", "set", "import") : Arrays.asList("check", "add", "take", "set");
        } else if (args.length == 2) {
            // Retourner null pour laisser le serveur suggérer les noms de joueurs en ligne
            return null;
        } else if (args.length == 3) {
            return Arrays.asList("5s", "5m", "1h", "1d");
        }
        return null;
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d+)([smhd])$");

    // Analyse une durée (ex : "5s", "10m", "2h", "1d")
    private long parseTime(String input) {
        Matcher matcher = TIME_PATTERN.matcher(input);
        if (!matcher.find()) return -1;
        try {
            long time = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "s" -> time;
                case "m" -> Math.multiplyExact(time, 60L);
                case "h" -> Math.multiplyExact(time, 3600L);
                case "d" -> Math.multiplyExact(time, 86400L);
                default -> -1L;
            };
        } catch (NumberFormatException | ArithmeticException e) {
            // Trop grand pour un long : même réponse qu'un format invalide
            return -1;
        }
    }

    // -------------------- CLASSES INTERNES --------------------

    // Gère une session AFK active pour un joueur
    private class AfkSession {
        private final Player player;
        private long sessionSeconds = 0;
        private double moneyEarned = 0.0;
        private long remainingBalance;
        private BukkitTask task;
        private final double boostMultiplier;
        // Arrondi le boost pour éviter les problèmes d'imprécision
        private final int boostPercentage;
        private BossBar bossBar;

        public AfkSession(Player player, long balance) {
            this.player = player;
            this.remainingBalance = balance;
            this.boostMultiplier = getPlayerBoost(player);
            this.boostPercentage = (int) Math.round((boostMultiplier - 1.0) * 100);
        }

        // Permet d'obtenir le temps restant de la session
        public long getRemainingTime() {
            return remainingBalance;
        }

        public void addTime(long time) {
            remainingBalance = remainingBalance > Long.MAX_VALUE - time ? Long.MAX_VALUE : remainingBalance + time;
        }

        public void setTime(long time) {
            remainingBalance = time;
        }

        public void start() {
            if (bossbarEnabled) {
                bossBar = Bukkit.createBossBar(bossbarMessage, bossbarColor, bossbarStyle);
                bossBar.addPlayer(player);
            }
            // Démarrer la tâche de mise à jour du timer et des messages chaque seconde
            task = Bukkit.getScheduler().runTaskTimer(SHAFKTime.this, () -> {
                if (!isWorldWhitelisted(player.getWorld())) {
                    endSession();
                    return;
                }
                if (remainingBalance > 0) {
                    remainingBalance--;
                    sessionSeconds++;
                    double gain = moneyPerSecond * boostMultiplier;
                    moneyEarned += gain;
                } else {
                    endSession();
                    return;
                }
                updateDisplay();
            }, 20L, 20L);
        }

        /**
         * Met à jour l'actionbar et la bossbar avec le temps restant, l'argent gagné et le boost.
         */
        private void updateDisplay() {
            if (actionbarEnabled) {
                String actionMsg = actionbarMessage;
                actionMsg = actionMsg.replace("{time}", formatTime(remainingBalance))
                    .replace("{money}", String.valueOf((int) moneyEarned))
                    .replace("{boost}", String.valueOf(boostPercentage));
                player.sendActionBar(actionMsg);
            }
            if (bossBar != null) {
                String updatedMsg = bossbarMessage;
                updatedMsg = updatedMsg.replace("{time}", formatTime(remainingBalance))
                    .replace("{money}", String.valueOf((int) moneyEarned))
                    .replace("{boost}", String.valueOf(boostPercentage));
                bossBar.setTitle(updatedMsg);
                double progress = (double) remainingBalance / Math.max(1, sessionSeconds + remainingBalance);
                bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
            }
        }

        public void endSession() {
            if (task != null) {
                task.cancel();
            }
            if (bossBar != null) {
                bossBar.removeAll();
            }
            UUID uuid = player.getUniqueId();
            activeSessions.remove(uuid);

            // Si du temps reste, on le remet dans afkBalances
            if (remainingBalance > 0) {
                afkBalances.put(uuid, remainingBalance);
            } else {
                afkBalances.remove(uuid);
            }

            if (moneyEarned > 0) {
                economy.depositPlayer(player, moneyEarned);
            }
            if (sessionSeconds >= minSession) {
                String msg = ChatColor.translateAlternateColorCodes('&', configFile.getString("messages.session-end",
                    "Tu as passé {time} en AFK et tu as gagné {money} !"));
                msg = msg.replace("{time}", formatTime(sessionSeconds))
                    .replace("{money}", String.valueOf((int) moneyEarned))
                    .replace("{boost}", String.valueOf(boostPercentage));
                if (player.isOnline()) {
                    player.sendMessage(msg);
                } else {
                    offlineSessions.put(uuid, new OfflineSessionData(sessionSeconds, moneyEarned, boostPercentage));
                }
            }
            // La sauvegarde du fichier sera effectuée par l'autosave ou dans onDisable()
        }
    }

    // Données de session à notifier lors de la reconnexion
    private class OfflineSessionData {
        long sessionSeconds;
        double moneyEarned;
        int boostPercentage;

        public OfflineSessionData(long sessionSeconds, double moneyEarned, int boostPercentage) {
            this.sessionSeconds = sessionSeconds;
            this.moneyEarned = moneyEarned;
            this.boostPercentage = boostPercentage;
        }
    }
}
