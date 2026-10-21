package org.Cry_Legende.sHAFKTime;

import net.milkbowl.vault.economy.Economy;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configFile = getConfig();
        setupPlayersFile();

        if (!setupEconomy()) {
            getLogger().severe("Vault n'est pas trouvé ou l'économie n'est pas disponible !");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Charger les données joueurs depuis players.yml
        loadPlayersData();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("shafktime");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        int autosaveDelay = configFile.getInt("autosave-delay", 300);
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
        // Le scheduler refuse les tâches d'un plugin en cours d'arrêt : écriture finale synchrone
        writePlayersData(collectPlayersData());
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
            e.printStackTrace();
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
        try {
            asyncConfig.save(playersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // -------------------- GESTION DES ÉVÉNEMENTS --------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
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

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return; // Ne rien faire si l'événement a été annulé
        Player p = event.getPlayer();
        event.getTo();
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
        List<String> whitelist = configFile.getStringList("whitelist-worlds");
        return whitelist.contains(world.getName());
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
        String format = configFile.getString("time-format", "{h}h:{m}m:{s}s");
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

        if (args.length < 2) {
            sender.sendMessage(usageMsg);
            return true;
        }
        String subCmd = args[0].toLowerCase();
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(playerNotOnline);
            return true;
        }
        UUID uuid = target.getUniqueId();
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
                    long newTime = current + amount;
                    if (activeSessions.containsKey(uuid)) {
                        activeSessions.get(uuid).addTime(amount);
                    } else {
                        afkBalances.put(uuid, newTime);
                        if (isWorldWhitelisted(target.getWorld())) {
                            startSession(target);
                        }
                    }
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
                    sender.sendMessage(setMsg.replace("{time}", formatTime(amount)).replace("{player}", target.getName()));
                    break;
                }
                default:
                    sender.sendMessage(commandUnknown);
            }
        }
        return true;
    }

    // Complétion d'onglets optimisée pour la commande /shafktime
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("check", "add", "take", "set");
        } else if (args.length == 2) {
            // Retourner null pour laisser le serveur suggérer les noms de joueurs en ligne
            return null;
        } else if (args.length == 3) {
            return Arrays.asList("5s", "5m", "1h", "1d");
        }
        return null;
    }

    // Analyse une durée (ex : "5s", "10m", "2h", "1d")
    private long parseTime(String input) {
        Pattern pattern = Pattern.compile("^(\\d+)([smhd])$");
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) return -1;
        long time = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        switch (unit) {
            case "s":
                return time;
            case "m":
                return time * 60;
            case "h":
                return time * 3600;
            case "d":
                return time * 86400;
            default:
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
            remainingBalance += time;
        }

        public void setTime(long time) {
            remainingBalance = time;
        }

        public void start() {
            if (configFile.getBoolean("bossbar.enabled", false)) {
                String bossMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("bossbar.message", ""));
                String colorStr = configFile.getString("bossbar.color", "BLUE");
                String styleStr = configFile.getString("bossbar.style", "SOLID");
                BarColor color;
                BarStyle style;
                try {
                    color = BarColor.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    color = BarColor.BLUE;
                }
                try {
                    style = BarStyle.valueOf(styleStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    style = BarStyle.SOLID;
                }
                bossBar = Bukkit.createBossBar(bossMsg, color, style);
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
                    double gain = configFile.getDouble("money-per-second", 1.0) * boostMultiplier;
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
            if (configFile.getBoolean("actionbar.enabled", false)) {
                String actionMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("actionbar.message", ""));
                actionMsg = actionMsg.replace("{time}", formatTime(remainingBalance))
                    .replace("{money}", String.valueOf((int) moneyEarned))
                    .replace("{boost}", String.valueOf(boostPercentage));
                player.sendActionBar(actionMsg);
            }
            if (bossBar != null) {
                String updatedMsg = ChatColor.translateAlternateColorCodes('&', configFile.getString("bossbar.message", ""));
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
            int minSession = configFile.getInt("min-session", 300);
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
