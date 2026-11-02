package de.Darkness.sekret.manager;

import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class DeathScoreboardManager implements Listener, CommandExecutor, TabCompleter {

    private static final int TOP_SIZE = 5;

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Scoreboard> animationBoards = new HashMap<>();
    private final Map<UUID, BukkitTask> animations = new HashMap<>();
    private final List<String> entries = new ArrayList<>();
    private final List<Team> rows = new ArrayList<>();
    private Scoreboard scoreboard;
    private Objective objective;

    public DeathScoreboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "deaths.yml");
    }

    public void start() {
        load();
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
            if (player.getName() != null) {
                names.put(player.getUniqueId(), player.getName());
                deaths.putIfAbsent(player.getUniqueId(), 0);
            }
        }

        scoreboard = plugin.getServer().getScoreboardManager().getNewScoreboard();
        objective = scoreboard.registerNewObjective("secret_deaths", "dummy", title());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int i = 0; i < TOP_SIZE; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            Team team = scoreboard.registerNewTeam("death_row_" + i);
            team.addEntry(entry);
            entries.add(entry);
            rows.add(team);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            remember(player);
            show(player);
        }
        refresh();
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        remember(player);
        deaths.merge(player.getUniqueId(), 1, Integer::sum);
        save();
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
        show(event.getPlayer());
        save();
        refresh();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelAnimation(id);
        restore(event.getPlayer());
    }

    public void shutdown() {
        save();
        for (BukkitTask task : animations.values()) {
            task.cancel();
        }
        animations.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            restore(player);
        }
        animationBoards.clear();
        previousBoards.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Nur Spieler können die Todesliste umschalten.");
            return true;
        }
        if (args.length > 1 || (args.length == 1
                && !args[0].equalsIgnoreCase("an") && !args[0].equalsIgnoreCase("aus"))) {
            player.sendMessage(ChatColor.RED + "Verwendung: /" + label + " [an|aus]");
            return true;
        }
        UUID id = player.getUniqueId();
        boolean visible = args.length == 0 ? hidden.contains(id) : args[0].equalsIgnoreCase("an");
        if (visible == !hidden.contains(id)) {
            player.sendMessage(ChatColor.YELLOW + "Die Todesliste ist bereits "
                    + (visible ? "eingeblendet." : "ausgeblendet."));
            return true;
        }
        if (visible) {
            hidden.remove(id);
        } else {
            hidden.add(id);
        }
        save();
        animate(player, visible);
        player.sendMessage(ChatColor.GREEN + "Todesliste " + (visible ? "eingeblendet." : "ausgeblendet."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> completions = new ArrayList<>();
        for (String option : List.of("an", "aus")) {
            if (option.regionMatches(true, 0, args[0], 0, args[0].length())) {
                completions.add(option);
            }
        }
        return completions;
    }

    private void remember(Player player) {
        names.put(player.getUniqueId(), player.getName());
        deaths.putIfAbsent(player.getUniqueId(), 0);
    }

    private void show(Player player) {
        if (hidden.contains(player.getUniqueId())) {
            return;
        }
        previousBoards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
        player.setScoreboard(scoreboard);
    }

    private void cancelAnimation(UUID id) {
        BukkitTask task = animations.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private void restore(Player player) {
        UUID id = player.getUniqueId();
        Scoreboard animated = animationBoards.remove(id);
        Scoreboard previous = previousBoards.remove(id);
        if (previous != null && (player.getScoreboard() == scoreboard || player.getScoreboard() == animated)) {
            player.setScoreboard(previous);
        }
    }

    private void animate(Player player, boolean visible) {
        UUID id = player.getUniqueId();
        cancelAnimation(id);
        previousBoards.putIfAbsent(id, player.getScoreboard());
        Scoreboard animated = plugin.getServer().getScoreboardManager().getNewScoreboard();
        Objective animatedObjective = animated.registerNewObjective("secret_deaths", "dummy", title());
        List<Team> animatedRows = new ArrayList<>();
        int width = ChatColor.stripColor(title()).length();
        for (int i = 0; i < TOP_SIZE; i++) {
            Team team = animated.registerNewTeam("death_row_" + i);
            team.addEntry(entries.get(i));
            animatedRows.add(team);
            width = Math.max(width, ChatColor.stripColor(rows.get(i).getPrefix()).length());
        }
        final int fullWidth = Math.max(1, width);
        animationBoards.put(id, animated);
        player.setScoreboard(animated);
        BukkitTask task = new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                if (!player.isOnline() || player.getScoreboard() != animated) {
                    animations.remove(id);
                    animationBoards.remove(id);
                    previousBoards.remove(id);
                    cancel();
                    return;
                }
                int shownWidth = visible ? frame : fullWidth - frame;
                animatedObjective.setDisplayName(clip(title(), shownWidth));
                for (int i = 0; i < TOP_SIZE; i++) {
                    String entry = entries.get(i);
                    animated.resetScores(entry);
                    if (shownWidth > 0 && objective.getScore(entry).isScoreSet()) {
                        animatedRows.get(i).setPrefix(clip(rows.get(i).getPrefix(), shownWidth));
                        animatedObjective.getScore(entry).setScore(objective.getScore(entry).getScore());
                    }
                }
                if (shownWidth > 0) {
                    animatedObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
                } else {
                    animated.clearSlot(DisplaySlot.SIDEBAR);
                }
                if (frame++ >= fullWidth) {
                    animations.remove(id);
                    if (visible) {
                        player.setScoreboard(scoreboard);
                        animationBoards.remove(id);
                    } else {
                        restore(player);
                    }
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
        animations.put(id, task);
    }

    private String clip(String text, int width) {
        if (width <= 0) {
            return ChatColor.RESET.toString();
        }
        int end = 0;
        int characters = 0;
        while (end < text.length() && characters < width) {
            if (text.charAt(end) == ChatColor.COLOR_CHAR && end + 1 < text.length()) {
                end += 2;
            } else {
                end++;
                characters++;
            }
        }
        return text.substring(0, end) + ChatColor.RESET;
    }

    private String title() {
        return ChatColor.RED.toString() + ChatColor.BOLD + "Tode";
    }

    private void refresh() {
        List<UUID> players = new ArrayList<>(names.keySet());
        players.sort(Comparator.<UUID>comparingInt(id -> deaths.getOrDefault(id, 0))
                .reversed()
                .thenComparing(id -> names.get(id), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(UUID::toString));

        for (int i = 0; i < TOP_SIZE; i++) {
            scoreboard.resetScores(entries.get(i));
            if (i < players.size()) {
                UUID id = players.get(i);
                rows.get(i).setPrefix(ChatColor.WHITE + names.get(id));
                objective.getScore(entries.get(i)).setScore(deaths.getOrDefault(id, 0));
            }
        }
    }

    private void load() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            ConfigurationSection entry = players.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            try {
                UUID id = UUID.fromString(key);
                names.put(id, entry.getString("name", key));
                deaths.put(id, Math.max(0, entry.getInt("deaths", 0)));
                if (entry.getBoolean("hidden", false)) {
                    hidden.add(id);
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ungültige Spieler-UUID in deaths.yml: " + key);
            }
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            String path = "players." + entry.getKey();
            data.set(path + ".name", entry.getValue());
            data.set(path + ".deaths", deaths.getOrDefault(entry.getKey(), 0));
            data.set(path + ".hidden", hidden.contains(entry.getKey()));
        }
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Todeszahlen konnten nicht gespeichert werden.", exception);
        }
    }
}