package de.Darkness.sekret.manager;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WaterManager implements Listener {

    private final SekretPlugin plugin;
    private final Map<UUID, Double> waterLevels = new HashMap<>();
    private final Set<UUID> dehydrationDeaths = new HashSet<>();
    private final Set<UUID> dehydrationWarnings = new HashSet<>();
    private final File dataFile;
    private BukkitTask monitorTask;
    private long lastSave;

    public WaterManager(SekretPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "water-data.yml");
    }

    public void start() {
        loadData();
        scheduleMonitor();
        for (Player player : Bukkit.getOnlinePlayers()) {
            waterLevels.putIfAbsent(player.getUniqueId(), maxWater());
            displayWater(player);
        }
    }

    public void reload() {
        saveData();
        if (monitorTask != null) {
            monitorTask.cancel();
        }
        scheduleMonitor();
    }

    public void shutdown() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }
        saveData();
        waterLevels.clear();
        dehydrationDeaths.clear();
        dehydrationWarnings.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("water.enabled", true)) {
            return;
        }
        waterLevels.putIfAbsent(event.getPlayer().getUniqueId(), maxWater());
        displayWater(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("water.enabled", true)) {
            return;
        }
        setWater(event.getPlayer(), maxWater());
        dehydrationWarnings.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        if (!plugin.getConfig().getBoolean("water.enabled", true)) {
            return;
        }
        if (!isWaterBottle(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        double restored = Math.max(0.0D, plugin.getConfig().getDouble("water.bottle_restore", 5.0D));
        setWater(player, waterLevels.getOrDefault(player.getUniqueId(), maxWater()) + restored);
        player.sendMessage("§bDein Durst wurde gestillt.");
    }

    private void scheduleMonitor() {
        if (!plugin.getConfig().getBoolean("water.enabled", true)) {
            return;
        }
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::monitor, 20L, 20L);
    }

    private void monitor() {
        if (!plugin.getConfig().getBoolean("water.enabled", true)) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                continue;
            }
            double drain = Math.max(0.0D, plugin.getConfig().getDouble("water.drain_per_second", 0.05D));
            if (plugin.getDiseaseManager().isDiseased(player)) {
                drain *= Math.max(1.0D, plugin.getConfig().getDouble("water.disease_drain_multiplier", 2.0D));
            }
            if (isDesert(player)) {
                drain *= Math.max(1.0D, plugin.getConfig().getDouble("water.desert_drain_multiplier", 2.0D));
            }
            double water = Math.max(0.0D, waterLevels.getOrDefault(player.getUniqueId(), maxWater()) - drain);
            waterLevels.put(player.getUniqueId(), water);
            if (water <= 0.0D) {
                damageDehydratedPlayer(player);
            } else {
                dehydrationWarnings.remove(player.getUniqueId());
            }
            displayWater(player);
        }
        long saveInterval = Math.max(1L, plugin.getConfig().getLong("water.data_save_interval_seconds", 30L)) * 1000L;
        if (System.currentTimeMillis() - lastSave >= saveInterval) {
            saveData();
        }
    }

    private boolean isDesert(Player player) {
        List<String> desertBiomes = plugin.getConfig().getStringList("water.desert_biomes");
        return desertBiomes.stream().anyMatch(biome -> biome.equalsIgnoreCase(player.getLocation().getBlock().getBiome().name()));
    }

    private boolean isWaterBottle(ItemStack item) {
        if (!item.getType().name().equalsIgnoreCase("POTION") || !(item.getItemMeta() instanceof PotionMeta potionMeta)) {
            return false;
        }
        return potionMeta.getBasePotionType() != null && potionMeta.getBasePotionType().name().equalsIgnoreCase("WATER");
    }

    private void damageDehydratedPlayer(Player player) {
        double damage = Math.max(0.0D, plugin.getConfig().getDouble("water.dehydration_damage", 1.0D));
        if (damage <= 0.0D) {
            return;
        }
        dehydrationDeaths.add(player.getUniqueId());
        player.damage(damage);
        dehydrationDeaths.remove(player.getUniqueId());
        if (!dehydrationWarnings.add(player.getUniqueId())) {
            return;
        }
        String warning = plugin.getConfig().getString("water.dehydration_warning", "&cDu verdurstest!");
        if (!warning.isBlank()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', warning));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!dehydrationDeaths.contains(event.getEntity().getUniqueId())) {
            return;
        }
        String message = plugin.getConfig().getString("water.death_message", "<player> ist verdurstet.");
        event.setDeathMessage(ChatColor.translateAlternateColorCodes('&', message.replace("<player>", event.getEntity().getName())));
    }

    private void setWater(Player player, double water) {
        waterLevels.put(player.getUniqueId(), Math.clamp(water, 0.0D, maxWater()));
        displayWater(player);
    }

    private void displayWater(Player player) {
        double water = waterLevels.getOrDefault(player.getUniqueId(), maxWater());
        int filledBars = (int) Math.ceil(water / maxWater() * 10.0D);
        StringBuilder bar = new StringBuilder("§bWasser §7[");
        for (int index = 0; index < 10; index++) {
            bar.append(index < filledBars ? "§b|" : "§8|");
        }
        bar.append("§7] §b").append((int) Math.ceil(water)).append("/").append((int) maxWater());
        player.sendActionBar(bar.toString());
    }

    private double maxWater() {
        return Math.max(1.0D, plugin.getConfig().getDouble("water.max", 20.0D));
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : dataConfig.getKeys(false)) {
            try {
                waterLevels.put(UUID.fromString(key), Math.clamp(dataConfig.getDouble(key, maxWater()), 0.0D, maxWater()));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration dataConfig = new YamlConfiguration();
        for (Map.Entry<UUID, Double> entry : waterLevels.entrySet()) {
            dataConfig.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            dataConfig.save(dataFile);
            lastSave = System.currentTimeMillis();
        } catch (IOException exception) {
            plugin.getLogger().warning("Wasserdaten konnten nicht gespeichert werden: " + exception.getMessage());
        }
    }
}