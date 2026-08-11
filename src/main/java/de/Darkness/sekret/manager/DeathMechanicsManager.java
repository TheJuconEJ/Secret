package de.Darkness.sekret.manager;

import de.Darkness.sekret.SekretPlugin;
import de.Darkness.sekret.util.PluginMessages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class DeathMechanicsManager implements Listener {

    private final SekretPlugin plugin;
    private final Map<PairKey, Long> excitementStarts = new HashMap<>();
    private final Map<UUID, DeathReason> pendingDeaths = new HashMap<>();
    private final Map<UUID, Long> tripChecks = new HashMap<>();
    private final Set<UUID> pendingTrips = new HashSet<>();
    private BukkitTask excitementTask;

    public DeathMechanicsManager(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setCollidable(false);
        }
        scheduleExcitementMonitor();
    }

    public void reload() {
        if (excitementTask != null) {
            excitementTask.cancel();
            excitementTask = null;
        }
        excitementStarts.clear();
        scheduleExcitementMonitor();
    }

    public void shutdown() {
        if (excitementTask != null) {
            excitementTask.cancel();
        }
        excitementStarts.clear();
        pendingDeaths.clear();
        tripChecks.clear();
        pendingTrips.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !plugin.getConfig().getBoolean("blow.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        if (!canKill(player)) {
            return;
        }

        double distance = Math.max(0.0D, plugin.getConfig().getDouble("blow.distance", 0.5D));
        double maximumDistanceSquared = distance * distance;
        for (Entity nearby : player.getNearbyEntities(distance, distance, distance)) {
            if (!(nearby instanceof Player other) || other.isDead() || !other.isOnline()) {
                continue;
            }
            if (player.getLocation().distanceSquared(other.getLocation()) <= maximumDistanceSquared) {
                kill(player, DeathReason.SAME_BLOCK);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld() || from.distanceSquared(to) <= 0.0D) {
            return;
        }

        Player player = event.getPlayer();
        tryTrip(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().setCollidable(false);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        removeExcitementTimers(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        removeExcitementTimers(playerId);
        tripChecks.remove(playerId);
        pendingTrips.remove(playerId);
        pendingDeaths.remove(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();
        DeathReason reason = pendingDeaths.remove(playerId);
        pendingTrips.remove(playerId);
        tripChecks.remove(playerId);
        removeExcitementTimers(playerId);

        if (reason == null) {
            return;
        }

        String messagePath = switch (reason) {
            case SAME_BLOCK -> "messages.same_block";
            case EXCITEMENT -> "messages.excitement";
            case TRIP -> "messages.trip";
        };
        event.setDeathMessage(PluginMessages.format(plugin.getConfig().getString(messagePath, ""), player, null, null));
    }

    private void scheduleExcitementMonitor() {
        if (!plugin.getConfig().getBoolean("excitement.enabled", true)) {
            return;
        }
        long interval = Math.max(1L, plugin.getConfig().getLong("excitement.check_interval_ticks", 10L));
        excitementTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkExcitement, interval, interval);
    }

    private void checkExcitement() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("excitement");
        if (section == null || !section.getBoolean("enabled", true)) {
            excitementStarts.clear();
            return;
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<PairKey> closePairs = new HashSet<>();
        double distance = Math.max(0.0D, section.getDouble("distance", 0.5D));
        double maximumDistanceSquared = distance * distance;

        for (int firstIndex = 0; firstIndex < players.size(); firstIndex++) {
            Player first = players.get(firstIndex);
            if (!canKill(first)) {
                continue;
            }
            for (int secondIndex = firstIndex + 1; secondIndex < players.size(); secondIndex++) {
                Player second = players.get(secondIndex);
                if (!canKill(second) || first.getWorld() != second.getWorld()) {
                    continue;
                }
                if (first.getLocation().distanceSquared(second.getLocation()) <= maximumDistanceSquared) {
                    closePairs.add(PairKey.of(first.getUniqueId(), second.getUniqueId()));
                }
            }
        }

        excitementStarts.keySet().removeIf(pair -> !closePairs.contains(pair));

        long now = System.currentTimeMillis();
        long requiredMillis = Math.max(0L, section.getLong("required_time_seconds", 10L)) * 1000L;
        double chance = Math.clamp(section.getDouble("chance", 1.0D), 0.0D, 1.0D);
        for (PairKey pair : closePairs) {
            long startedAt = excitementStarts.computeIfAbsent(pair, ignored -> now);
            if (now - startedAt < requiredMillis || ThreadLocalRandom.current().nextDouble() > chance) {
                continue;
            }

            Player first = Bukkit.getPlayer(pair.first());
            Player second = Bukkit.getPlayer(pair.second());
            if (first != null && second != null && canKill(first) && canKill(second) && first.getWorld() == second.getWorld()) {
                killBoth(first, second);
            }
            excitementStarts.remove(pair);
        }
    }

    private void tryTrip(Player player) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("tripping");
        if (section == null || !section.getBoolean("enabled", true) || !canKill(player) || pendingTrips.contains(player.getUniqueId())) {
            return;
        }

        long minimumDelayMillis = Math.max(1L, section.getLong("check_interval_ticks", 20L)) * 50L;
        long now = System.currentTimeMillis();
        Long lastCheck = tripChecks.put(player.getUniqueId(), now);
        if (lastCheck != null && now - lastCheck < minimumDelayMillis) {
            return;
        }

        double chance = Math.clamp(section.getDouble("chance", 0.001D), 0.0D, 1.0D);
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        if (!section.getBoolean("animation", true)) {
            kill(player, DeathReason.TRIP);
            return;
        }

        pendingTrips.add(player.getUniqueId());
        Vector tumble = player.getVelocity().clone();
        tumble.setY(Math.max(tumble.getY(), 0.35D));
        player.setVelocity(tumble);
        long delay = Math.max(1L, section.getLong("animation_delay_ticks", 4L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTrips.remove(player.getUniqueId());
            if (player.isOnline() && canKill(player)) {
                kill(player, DeathReason.TRIP);
            }
        }, delay);
    }

    private void killBoth(Player first, Player second) {
        if (!canKill(first) || !canKill(second)) {
            return;
        }
        pendingDeaths.put(first.getUniqueId(), DeathReason.EXCITEMENT);
        pendingDeaths.put(second.getUniqueId(), DeathReason.EXCITEMENT);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("excitement");
        PluginMessages.playEffect(section, first);
        PluginMessages.playEffect(section, second);
        first.setHealth(0.0D);
        second.setHealth(0.0D);
    }

    private void kill(Player player, DeathReason reason) {
        if (!canKill(player)) {
            return;
        }
        pendingDeaths.put(player.getUniqueId(), reason);
        String sectionPath = switch (reason) {
            case SAME_BLOCK -> "blow";
            case EXCITEMENT -> "excitement";
            case TRIP -> "tripping";
        };
        PluginMessages.playEffect(plugin.getConfig().getConfigurationSection(sectionPath), player);
        player.setHealth(0.0D);
    }

    private boolean canKill(Player player) {
        return player.isOnline() && !player.isDead() && player.getHealth() > 0.0D && !pendingDeaths.containsKey(player.getUniqueId());
    }

    private void removeExcitementTimers(UUID playerId) {
        excitementStarts.keySet().removeIf(pair -> pair.first().equals(playerId) || pair.second().equals(playerId));
    }

    private enum DeathReason {
        SAME_BLOCK,
        EXCITEMENT,
        TRIP
    }

    static record PairKey(UUID first, UUID second) {
        static PairKey of(UUID first, UUID second) {
            return first.compareTo(second) <= 0 ? new PairKey(first, second) : new PairKey(second, first);
        }
    }
}