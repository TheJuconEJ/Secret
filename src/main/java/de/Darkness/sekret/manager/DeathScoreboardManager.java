package de.Darkness.sekret.manager;

import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class DeathScoreboardManager implements Listener {

    private static final int TOP_SIZE = 5;

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();
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
        previousBoards.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        save();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Scoreboard previous = previousBoards.get(player.getUniqueId());
            if (previous != null && player.getScoreboard() == scoreboard) {
                player.setScoreboard(previous);
            }
        }
        previousBoards.clear();
    }

    private void remember(Player player) {
        names.put(player.getUniqueId(), player.getName());
        deaths.putIfAbsent(player.getUniqueId(), 0);
    }

    private void show(Player player) {
        previousBoards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
        player.setScoreboard(scoreboard);
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
        }
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Todeszahlen konnten nicht gespeichert werden.", exception);
        }
    }
}