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

import static de.Darkness.sekret.util.PluginMessages.text;
import static de.Darkness.sekret.util.PluginMessages.language;

public final class DeathScoreboardManager implements Listener, CommandExecutor, TabCompleter {

    private static final int TOP_SIZE = 5;
    private static final int ROW_COUNT = TOP_SIZE + 5;

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Scoreboard> animationBoards = new HashMap<>();
    private final Map<UUID, BukkitTask> animations = new HashMap<>();
    private final List<String> entries = new ArrayList<>();
    private final Map<String, Scoreboard> boards = new HashMap<>();
    private boolean enabled;
    private boolean numberFormatWarningLogged;

    public DeathScoreboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "deaths.yml");
    }

    public void start() {
        load();
        enabled = plugin.getConfig().getBoolean("death_scoreboard.enabled", true);
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
            if (player.getName() != null) {
                names.put(player.getUniqueId(), player.getName());
                deaths.putIfAbsent(player.getUniqueId(), 0);
            }
        }

        for (int i = 0; i < ROW_COUNT; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            entries.add(entry);
        }
        for (String language : List.of("de", "en")) {
            Scoreboard board = plugin.getServer().getScoreboardManager().getNewScoreboard();
            Objective objective = board.registerNewObjective("vision_deaths", "dummy", title(language));
            hideScoreNumbers(objective);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < ROW_COUNT; i++) {
                Team team = board.registerNewTeam("death_row_" + i);
                team.addEntry(entries.get(i));
            }
            boards.put(language, board);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            remember(player);
            show(player);
        }
        refresh();
        save();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("death_scoreboard.enabled", true);
        for (BukkitTask task : animations.values()) {
            task.cancel();
        }
        animations.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            restore(player);
            if (enabled) {
                show(player);
            }
        }
        animationBoards.clear();
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled) {
            return;
        }
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
            sender.sendMessage(text(plugin, sender, "§cNur Spieler können die Todesliste umschalten.", "§cOnly players can toggle the death leaderboard."));
            return true;
        }
        if (!enabled) {
            player.sendMessage(text(plugin, player, "§cDie Todesliste ist vom Admin deaktiviert.", "§cThe death leaderboard is disabled by an admin."));
            return true;
        }
        if (args.length > 1 || (args.length == 1
                && !args[0].equalsIgnoreCase("an") && !args[0].equalsIgnoreCase("aus")
                && !args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            player.sendMessage(text(plugin, player, "§cVerwendung: /" + label + " [an|aus]", "§cUsage: /" + label + " [on|off]"));
            return true;
        }
        UUID id = player.getUniqueId();
        boolean visible = args.length == 0 ? hidden.contains(id) : args[0].equalsIgnoreCase("an") || args[0].equalsIgnoreCase("on");
        if (visible == !hidden.contains(id)) {
            player.sendMessage(visible ? text(plugin, player, "§eDie Todesliste ist bereits eingeblendet.", "§eThe death leaderboard is already visible.")
                    : text(plugin, player, "§eDie Todesliste ist bereits ausgeblendet.", "§eThe death leaderboard is already hidden."));
            return true;
        }
        setVisible(player, visible);
        player.sendMessage(visible ? text(plugin, player, "§aTodesliste eingeblendet.", "§aDeath leaderboard shown.")
                : text(plugin, player, "§aTodesliste ausgeblendet.", "§aDeath leaderboard hidden."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> completions = new ArrayList<>();
        for (String option : List.of(text(plugin, sender, "an", "on"), text(plugin, sender, "aus", "off"))) {
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

    public boolean isVisible(Player player) {
        return !hidden.contains(player.getUniqueId());
    }

    public void setVisible(Player player, boolean visible) {
        remember(player);
        if (visible) {
            hidden.remove(player.getUniqueId());
        } else {
            hidden.add(player.getUniqueId());
        }
        save();
        if (enabled) {
            animate(player, visible);
        }
    }

    public void refreshPlayer(Player player) {
        cancelAnimation(player.getUniqueId());
        restore(player);
        show(player);
    }

    private void show(Player player) {
        if (!enabled || hidden.contains(player.getUniqueId())) {
            return;
        }
        previousBoards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
        player.setScoreboard(boards.get(language(plugin, player)));
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
        if (previous != null && (boards.containsValue(player.getScoreboard()) || player.getScoreboard() == animated)) {
            player.setScoreboard(previous);
        }
    }

    private void animate(Player player, boolean visible) {
        UUID id = player.getUniqueId();
        cancelAnimation(id);
        previousBoards.putIfAbsent(id, player.getScoreboard());
        String selectedLanguage = language(plugin, player);
        Scoreboard scoreboard = boards.get(selectedLanguage);
        Objective objective = scoreboard.getObjective("vision_deaths");
        Scoreboard animated = plugin.getServer().getScoreboardManager().getNewScoreboard();
        Objective animatedObjective = animated.registerNewObjective("vision_deaths", "dummy", title(selectedLanguage));
        hideScoreNumbers(animatedObjective);
        List<Team> animatedRows = new ArrayList<>();
        int width = ChatColor.stripColor(title(selectedLanguage)).length();
        for (int i = 0; i < ROW_COUNT; i++) {
            Team team = animated.registerNewTeam("death_row_" + i);
            team.addEntry(entries.get(i));
            animatedRows.add(team);
            width = Math.max(width, ChatColor.stripColor(scoreboard.getTeam("death_row_" + i).getPrefix()).length());
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
                animatedObjective.setDisplayName(clip(title(selectedLanguage), shownWidth));
                for (int i = 0; i < ROW_COUNT; i++) {
                    String entry = entries.get(i);
                    animated.resetScores(entry);
                    if (shownWidth > 0 && objective.getScore(entry).isScoreSet()) {
                        animatedRows.get(i).setPrefix(clip(scoreboard.getTeam("death_row_" + i).getPrefix(), shownWidth));
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

    private void hideScoreNumbers(Objective objective) {
        try {
            Class<?> formatClass = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blankFormat = formatClass.getMethod("blank").invoke(null);
            Objective.class.getMethod("numberFormat", formatClass).invoke(objective, blankFormat);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!numberFormatWarningLogged) {
                plugin.getLogger().warning(
                        "Die öffentliche Paper-API zum Ausblenden der Scoreboard-Zahlen ist nicht verfügbar. Die Todesliste verwendet die normale Zahlenspalte.");
                numberFormatWarningLogged = true;
            }
        }
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

    private String title(String language) {
        return "§c§l☠ §f§l" + (language.equals("en") ? "DEATH LEADERBOARD" : "TODESLISTE");
    }

    private void refresh() {
        List<UUID> players = new ArrayList<>(names.keySet());
        players.sort(Comparator.<UUID>comparingInt(id -> deaths.getOrDefault(id, 0))
                .reversed()
                .thenComparing(id -> names.get(id), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(UUID::toString));

        long total = deaths.values().stream().mapToLong(Integer::longValue).sum();
        for (Map.Entry<String, Scoreboard> boardEntry : boards.entrySet()) {
            Scoreboard board = boardEntry.getValue();
            boolean english = boardEntry.getKey().equals("en");
            setLine(board, 0, "§8§m────────────────────────");
            setLine(board, 1, english ? "§7The top §f5 §7at a glance" : "§7Die Top §f5 §7im Überblick");
            setLine(board, 2, " ");
            String[] rankColors = {"§6§l", "§f§l", "§c§l", "§7", "§7"};
            for (int i = 0; i < TOP_SIZE; i++) {
                if (i < players.size()) {
                    UUID id = players.get(i);
                    setLine(board, i + 3, rankColors[i] + (i + 1) + ". §f" + names.get(id)
                            + " §8» §c" + deaths.getOrDefault(id, 0) + " §4☠");
                } else {
                    setLine(board, i + 3, "§8" + (i + 1) + ". §7—");
                }
            }
            setLine(board, 8, "§8§m────────────────────────");
            setLine(board, 9, (english ? "§7Total deaths: §c" : "§7Tode insgesamt: §c") + total);
        }
    }

    private void setLine(Scoreboard board, int index, String value) {
        board.getTeam("death_row_" + index).setPrefix(value);
        board.getObjective("vision_deaths").getScore(entries.get(index)).setScore(ROW_COUNT - index);
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