package de.Darkness.sekret.manager;

import lombok.Getter;
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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

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

public final class DeathScoreboardManager implements Listener, CommandExecutor, TabCompleter {

    private static final int TOP_SIZE = 5;
    private static final int ANIMATION_STEPS = 8;

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private boolean enabled;

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
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            remember(player);
            refreshPlayer(player);
        }
        save();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("death_scoreboard.enabled", true);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayer(player);
        }
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
        Player player = event.getPlayer();
        remember(player);
        save();
        refresh();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refreshPlayer(player);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeSidebar(event.getPlayer());
    }

    public void shutdown() {
        save();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            removeSidebar(player);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text(plugin, sender, "§cNur Spieler können die Todesliste umschalten.", "§cOnly players can toggle the death leaderboard."));
            return true;
        }
        if (args.length > 1 || (args.length == 1
                && !args[0].equalsIgnoreCase("an") && !args[0].equalsIgnoreCase("aus")
                && !args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            player.sendMessage(text(plugin, player, "§cVerwendung: /" + label + " [an|aus]", "§cUsage: /" + label + " [on|off]"));
            return true;
        }
        boolean visible = args.length == 0 ? !isVisible(player)
                : args[0].equalsIgnoreCase("an") || args[0].equalsIgnoreCase("on");
        setVisible(player, visible);
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
        Sidebar sidebar = sidebars.get(player.getUniqueId());
        return enabled && !hidden.contains(player.getUniqueId()) && sidebar != null
                && player.getScoreboard() == sidebar.board;
    }

    public void setVisible(Player player, boolean visible) {
        if (visible && !enabled) {
            player.sendMessage(text(plugin, player, "§cDie Todesliste ist vom Admin deaktiviert.", "§cThe death leaderboard is disabled by an admin."));
            return;
        }
        remember(player);
        if (visible) {
            hidden.remove(player.getUniqueId());
            showSidebar(player, true);
        } else {
            hidden.add(player.getUniqueId());
            Sidebar sidebar = sidebars.get(player.getUniqueId());
            if (sidebar != null) {
                animate(player, sidebar, false);
            }
        }
        save();
    }

    public void refreshPlayer(Player player) {
        if (enabled && !hidden.contains(player.getUniqueId())) {
            showSidebar(player, false);
        } else {
            removeSidebar(player);
        }
    }

    private void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Sidebar sidebar = sidebars.get(player.getUniqueId());
            if (sidebar != null) {
                if (player.getScoreboard() != sidebar.board) {
                    removeSidebar(player);
                } else {
                    updateContent(player, sidebar);
                    renderFrame(sidebar);
                }
            }
        }
    }

    private void showSidebar(Player player, boolean animated) {
        Sidebar sidebar = sidebars.get(player.getUniqueId());
        if (sidebar != null && player.getScoreboard() != sidebar.board) {
            removeSidebar(player);
            sidebar = null;
        }
        if (sidebar == null) {
            Scoreboard board = plugin.getServer().getScoreboardManager().getNewScoreboard();
            Objective objective = board.registerNewObjective("vision_deaths", "dummy", " ");
            sidebar = new Sidebar(board, player.getScoreboard(), objective);
            for (int i = 0; i < TOP_SIZE + 9; i++) {
                String entry = "§" + Integer.toHexString(i) + "§r";
                Team team = board.registerNewTeam("vision_line_" + i);
                team.addEntry(entry);
                sidebar.lines.add(team);
                objective.getScore(entry).setScore(TOP_SIZE + 9 - i);
            }
            sidebar.progress = animated ? 0 : ANIMATION_STEPS;
            updateContent(player, sidebar);
            renderFrame(sidebar);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            sidebars.put(player.getUniqueId(), sidebar);
            player.setScoreboard(board);
        } else {
            updateContent(player, sidebar);
        }
        if (animated) {
            animate(player, sidebar, true);
        } else {
            cancelAnimation(sidebar);
            sidebar.progress = ANIMATION_STEPS;
            renderFrame(sidebar);
        }
    }

    private void updateContent(Player player, Sidebar sidebar) {
        List<UUID> players = new ArrayList<>(names.keySet());
        players.sort(Comparator.<UUID>comparingInt(id -> deaths.getOrDefault(id, 0))
                .reversed()
                .thenComparing(id -> names.get(id), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(UUID::toString));

        sidebar.title = text(plugin, player, "§f§lTodesliste", "§f§lDeaths");
        sidebar.content.clear();
        sidebar.content.add(" ");
        sidebar.content.add("§8▸ §7§lTOP 5");
        for (int i = 0; i < TOP_SIZE; i++) {
            String rankColor = switch (i) {
                case 0 -> "§6";
                case 1 -> "§f";
                case 2 -> "§c";
                default -> "§8";
            };
            if (i < players.size()) {
                UUID id = players.get(i);
                String nameColor = id.equals(player.getUniqueId()) ? "§b" : "§7";
                String name = names.get(id);
                if (name.length() > 12) {
                    name = name.substring(0, 11) + "…";
                }
                sidebar.content.add(rankColor + "◆ " + nameColor + name + " §8┃ §c" + deaths.getOrDefault(id, 0));
            } else {
                sidebar.content.add("§8◇ §8—");
            }
        }
        sidebar.content.add(" ");
        sidebar.content.add(text(plugin, player, "§8▸ §7§lSTATISTIK", "§8▸ §7§lSTATS"));
        sidebar.content.add(text(plugin, player, "§7Deine Tode §8┃ §c", "§7Your deaths §8┃ §c") + deaths.getOrDefault(player.getUniqueId(), 0));
        long total = deaths.values().stream().mapToLong(Integer::longValue).sum();
        sidebar.content.add(text(plugin, player, "§7Server gesamt §8┃ §c", "§7Server total §8┃ §c") + total);
        sidebar.content.add(" ");
        sidebar.content.add("§f/todesliste");
        sidebar.content.add("§f§m------------------");
    }

    private void renderFrame(Sidebar sidebar) {
        if (!sidebar.objective.getDisplayName().equals(sidebar.title)) {
            sidebar.objective.setDisplayName(sidebar.title);
        }
        for (int i = 0; i < sidebar.lines.size(); i++) {
            Team team = sidebar.lines.get(i);
            String prefix = reveal(sidebar.content.get(i), sidebar.progress);
            if (!team.getPrefix().equals(prefix)) {
                team.setPrefix(prefix);
            }
        }
    }

    private String reveal(String value, int progress) {
        if (progress <= 0) {
            return " ";
        }
        if (progress >= ANIMATION_STEPS) {
            return value;
        }
        int limit = (ChatColor.stripColor(value).length() * progress + ANIMATION_STEPS - 1) / ANIMATION_STEPS;
        StringBuilder result = new StringBuilder();
        int visible = 0;
        for (int i = 0; i < value.length() && visible < limit; i++) {
            char character = value.charAt(i);
            result.append(character);
            if (character == '§' && i + 1 < value.length()) {
                result.append(value.charAt(++i));
            } else {
                visible++;
            }
        }
        return result.append("§r").toString();
    }

    private void animate(Player player, Sidebar sidebar, boolean showing) {
        cancelAnimation(sidebar);
        if (showing && sidebar.progress == ANIMATION_STEPS) {
            renderFrame(sidebar);
            return;
        }
        if (!showing && sidebar.progress == 0) {
            removeSidebar(player);
            return;
        }
        sidebar.animation = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.getScoreboard() != sidebar.board) {
                removeSidebar(player);
                return;
            }
            sidebar.progress += showing ? 1 : -1;
            renderFrame(sidebar);
            if (sidebar.progress == 0) {
                removeSidebar(player);
            } else if (sidebar.progress == ANIMATION_STEPS) {
                cancelAnimation(sidebar);
            }
        }, 0L, 2L);
    }

    private void cancelAnimation(Sidebar sidebar) {
        if (sidebar.animation != null) {
            sidebar.animation.cancel();
            sidebar.animation = null;
        }
    }

    private void removeSidebar(Player player) {
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar == null) {
            return;
        }
        cancelAnimation(sidebar);
        if (player.getScoreboard() == sidebar.board) {
            player.setScoreboard(sidebar.previous);
        }
    }

    @Getter
    private static final class Sidebar {
        private final Scoreboard board;
        private final Scoreboard previous;
        private final Objective objective;
        private final List<Team> lines = new ArrayList<>();
        private final List<String> content = new ArrayList<>();
        private String title;
        private int progress;
        private BukkitTask animation;

        private Sidebar(Scoreboard board, Scoreboard previous, Objective objective) {
            this.board = board;
            this.previous = previous;
            this.objective = objective;
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
                if (entry.getBoolean("sidebar-hidden", false)) {
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
            data.set(path + ".sidebar-hidden", hidden.contains(entry.getKey()));
        }
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Todeszahlen konnten nicht gespeichert werden.", exception);
        }
    }
}