package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XPotion;
import de.Darkness.sekret.SekretPlugin;
import de.Darkness.sekret.util.PluginMessages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class DiseaseManager implements Listener {

    private final SekretPlugin plugin;
    private final Map<UUID, DiseaseData> diseases = new HashMap<>();
    private final Set<UUID> diseaseDeaths = new HashSet<>();
    private final File dataFile;
    private BukkitTask monitorTask;
    private long lastInfectionCheck;
    private long lastSave;
    private Method biomeLookup;
    private boolean biomeLookupUnavailable;

    public DiseaseManager(SekretPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "disease-data.yml");
    }

    public void start() {
        loadData();
        scheduleMonitor();
    }

    public void reload() {
        saveData();
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            DiseaseData data = diseases.get(player.getUniqueId());
            if (data != null) {
                if (plugin.getConfig().getBoolean("disease.enabled", true)) {
                    applyEffects(player, data.stage());
                } else {
                    removeDiseaseEffects(player);
                }
            }
        }
        scheduleMonitor();
    }

    public void shutdown() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }
        saveData();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (diseases.containsKey(player.getUniqueId())) {
                removeDiseaseEffects(player);
            }
        }
        diseases.clear();
        diseaseDeaths.clear();
    }

    public boolean heal(Player player) {
        UUID playerId = player.getUniqueId();
        if (diseases.remove(playerId) == null) {
            return false;
        }
        removeDiseaseEffects(player);
        saveData();
        return true;
    }

    public boolean isDiseased(Player player) {
        return diseases.containsKey(player.getUniqueId());
    }

    public boolean infect(Player player) {
        if (!plugin.getConfig().getBoolean("disease.enabled", true) || diseases.containsKey(player.getUniqueId())) {
            return false;
        }
        diseases.put(player.getUniqueId(), new DiseaseData(1, 0L, 0L, 0L));
        player.sendMessage(text(plugin, player, "§eDu fühlst dich plötzlich krank...", "§eYou suddenly feel sick..."));
        applyEffects(player, 1);
        showDiseaseStatus(player, 1);
        saveData();
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!plugin.getConfig().getBoolean("disease.enabled", true) || !plugin.getConfig().getBoolean("disease.healing.enabled", true)) {
            return;
        }
        ItemStack item = event.getItem();
        String healingItem = plugin.getConfig().getString("disease.healing.item", "GOLDEN_APPLE");
        if (item.getType().name().equalsIgnoreCase(healingItem) && heal(event.getPlayer())) {
            event.getPlayer().sendMessage(text(plugin, event.getPlayer(), "§aDu fühlst dich wieder gesund.", "§aYou feel healthy again."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            accelerate(event.getPlayer(), "sprinting");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (to.getY() > from.getY() && event.getPlayer().getVelocity().getY() > 0.0D) {
            accelerate(event.getPlayer(), "jumping");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) {
            accelerate(attacker, "fighting");
        }
        if (event.getEntity() instanceof Player victim) {
            accelerate(victim, "fighting");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && event.getFoodLevel() < player.getFoodLevel()) {
            accelerate(player, "hunger");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        DiseaseData data = diseases.get(event.getPlayer().getUniqueId());
        if (data != null && plugin.getConfig().getBoolean("disease.enabled", true)) {
            applyEffects(event.getPlayer(), data.stage());
            showDiseaseStatus(event.getPlayer(), data.stage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (diseases.containsKey(event.getPlayer().getUniqueId())) {
            removeDiseaseEffects(event.getPlayer());
            saveData();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        boolean causedByDisease = diseaseDeaths.remove(playerId);
        if (diseases.remove(playerId) != null) {
            removeDiseaseEffects(event.getEntity());
            saveData();
        }
        if (causedByDisease) {
            event.setDeathMessage(PluginMessages.format(PluginMessages.configured(plugin, "disease.death_message"), event.getEntity(), null, null));
        }
    }

    private void scheduleMonitor() {
        if (!plugin.getConfig().getBoolean("disease.enabled", true)) {
            return;
        }
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::monitor, 20L, 20L);
    }

    private void monitor() {
        if (!plugin.getConfig().getBoolean("disease.enabled", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            DiseaseData data = diseases.get(player.getUniqueId());
            if (data == null || player.isDead()) {
                continue;
            }
            progress(player, data, 1L);
            accelerateForBiome(player);
        }

        long infectionInterval = Math.max(1L, plugin.getConfig().getLong("disease.infection_check_seconds", 60L)) * 1000L;
        if (now - lastInfectionCheck >= infectionInterval) {
            lastInfectionCheck = now;
            infectPlayers();
        }

        long saveInterval = Math.max(1L, plugin.getConfig().getLong("disease.data_save_interval_seconds", 30L)) * 1000L;
        if (now - lastSave >= saveInterval) {
            saveData();
        }
    }

    private void infectPlayers() {
        double randomChance = clamp(plugin.getConfig().getDouble("disease.infection_chance", 0.01D));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!diseases.containsKey(player.getUniqueId()) && canBeRandomlyInfected(player) && ThreadLocalRandom.current().nextDouble() < randomChance) {
                infect(player);
            }
        }

        if (!plugin.getConfig().getBoolean("disease.spread_enabled", true)) {
            return;
        }
        double distance = Math.max(0.0D, plugin.getConfig().getDouble("disease.spread_distance", 2.0D));
        double chance = clamp(plugin.getConfig().getDouble("disease.spread_chance", 0.05D));
        double squaredDistance = distance * distance;
        for (Player sickPlayer : Bukkit.getOnlinePlayers()) {
            if (!diseases.containsKey(sickPlayer.getUniqueId())) {
                continue;
            }
            for (Entity entity : sickPlayer.getNearbyEntities(distance, distance, distance)) {
                if (entity instanceof Player nearby && !diseases.containsKey(nearby.getUniqueId()) && sickPlayer.getLocation().distanceSquared(nearby.getLocation()) <= squaredDistance && ThreadLocalRandom.current().nextDouble() < chance) {
                    infect(nearby);
                }
            }
        }
    }

    private boolean canBeRandomlyInfected(Player player) {
        String biome = biomeName(player);
        if (biome == null) {
            return false;
        }
        for (String configuredBiome : plugin.getConfig().getStringList("disease.infection_biomes")) {
            if (biome.equalsIgnoreCase(configuredBiome)) {
                return true;
            }
        }
        return false;
    }

    private String biomeName(Player player) {
        if (biomeLookupUnavailable) {
            return null;
        }
        try {
            // Biome is an enum on older APIs and an interface on newer ones.
            if (biomeLookup == null) {
                biomeLookup = Block.class.getMethod("getBiome");
            }
            Object biome = biomeLookup.invoke(player.getLocation().getBlock());
            if (biome instanceof Enum<?> enumBiome) {
                return enumBiome.name();
            }
            if (biome instanceof Keyed keyedBiome) {
                return keyedBiome.getKey().getKey().toUpperCase(Locale.ROOT);
            }
            throw new ReflectiveOperationException("Unbekannter Biom-Typ: " + biome);
        } catch (ReflectiveOperationException | LinkageError exception) {
            biomeLookupUnavailable = true;
            plugin.getLogger().warning("Biom-Abfrage auf dieser Serverversion nicht unterstützt. Nur die biomabhängigen Krankheitsregeln werden bis zum Neustart deaktiviert: " + exception);
            return null;
        }
    }

    private void accelerate(Player player, String activity) {
        if (!plugin.getConfig().getBoolean("disease.enabled", true)
                || !plugin.getConfig().getBoolean("disease.acceleration." + activity, false)) {
            return;
        }
        DiseaseData data = diseases.get(player.getUniqueId());
        if (data != null) {
            progress(player, data, Math.max(1L, plugin.getConfig().getLong("disease.acceleration.seconds_per_activity", 5L)));
        }
    }

    private void accelerateForBiome(Player player) {
        DiseaseData data = diseases.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        String biome = biomeName(player);
        if (biome == null) {
            return;
        }
        for (String configuredBiome : plugin.getConfig().getStringList("disease.acceleration.biomes")) {
            if (biome.equalsIgnoreCase(configuredBiome)) {
                progress(player, data, Math.max(1L, plugin.getConfig().getLong("disease.acceleration.seconds_per_activity", 5L)));
                return;
            }
        }
    }

    private void progress(Player player, DiseaseData initialData, long seconds) {
        DiseaseData data = initialData;
        long remaining = seconds;
        while (remaining > 0L && diseases.containsKey(player.getUniqueId())) {
            long duration = stageDuration(data.stage());
            long untilNextStage = Math.max(1L, duration - data.stageElapsedSeconds());
            long applied = Math.min(remaining, untilNextStage);
            long elapsed = data.stageElapsedSeconds() + applied;
            long damageElapsed = data.damageElapsedSeconds() + applied;
            long warningElapsed = data.warningElapsedSeconds() + applied;
            remaining -= applied;

            data = playSymptoms(player, new DiseaseData(data.stage(), elapsed, damageElapsed, warningElapsed));
            if (elapsed >= duration) {
                if (data.stage() == 4) {
                    diseaseDeaths.add(player.getUniqueId());
                    player.setHealth(0.0D);
                    return;
                }
                data = new DiseaseData(data.stage() + 1, 0L, 0L, 0L);
                player.sendMessage(text(plugin, player, "§cDeine Krankheit wird immer schlimmer!", "§cYour disease is getting worse!"));
                applyEffects(player, data.stage());
                showDiseaseStatus(player, data.stage());
            } else {
                data = new DiseaseData(data.stage(), elapsed, data.damageElapsedSeconds(), data.warningElapsedSeconds());
            }
            diseases.put(player.getUniqueId(), data);
        }
    }

    private DiseaseData playSymptoms(Player player, DiseaseData data) {
        ConfigurationSection section = stageSection(data.stage());
        if (section == null) {
            return data;
        }
        long damageElapsed = data.damageElapsedSeconds();
        long warningElapsed = data.warningElapsedSeconds();
        long damageInterval = Math.max(1L, section.getLong("damage_interval_seconds", 20L));
        if (damageElapsed >= damageInterval) {
            double damage = Math.max(0.0D, section.getDouble("damage", 0.0D));
            double safeDamage = Math.min(damage, Math.max(0.0D, player.getHealth() - 1.0D));
            if (safeDamage > 0.0D) {
                player.damage(safeDamage);
            }
            damageElapsed = 0L;
        }
        long warningInterval = Math.max(1L, section.getLong("warning_interval_seconds", 30L));
        if (warningElapsed >= warningInterval) {
            String warning = PluginMessages.configured(plugin, player, "disease.stages.stage_" + data.stage() + ".warning");
            if (!warning.isBlank()) {
                player.sendMessage(text(plugin, player, "§4[KRANK – Stufe ", "§4[SICK – Stage ")
                        + data.stage() + "/4] §r" + ChatColor.translateAlternateColorCodes('&', warning));
            }
            PluginMessages.playEffect(section, player);
            warningElapsed = 0L;
        }
        applyEffects(player, data.stage());
        return new DiseaseData(data.stage(), data.stageElapsedSeconds(), damageElapsed, warningElapsed);
    }

    private void applyEffects(Player player, int stage) {
        if (!plugin.getConfig().getBoolean("disease.enabled", true)) {
            return;
        }
        XPotion.matchXPotion("NAUSEA").map(potion -> potion.buildPotionEffect(240, 0)).ifPresent(player::addPotionEffect);
        ConfigurationSection section = stageSection(stage);
        if (section == null) {
            return;
        }
        for (String effectName : section.getStringList("effects")) {
            XPotion.matchXPotion(effectName).map(potion -> potion.buildPotionEffect(240, 0)).ifPresent(player::addPotionEffect);
        }
    }

    private void showDiseaseStatus(Player player, int stage) {
        player.sendTitle(text(plugin, player, "§4§lKRANK", "§4§lSICK"),
                text(plugin, player, "§eKrankheitsstufe ", "§eDisease stage ") + stage + "/4", 10, 70, 20);
    }

    private void removeDiseaseEffects(Player player) {
        XPotion.matchXPotion("NAUSEA").map(potion -> potion.buildPotionEffect(1, 0).getType()).ifPresent(player::removePotionEffect);
        for (int stage = 1; stage <= 4; stage++) {
            ConfigurationSection section = stageSection(stage);
            if (section == null) {
                continue;
            }
            for (String effectName : section.getStringList("effects")) {
                XPotion.matchXPotion(effectName).map(potion -> potion.buildPotionEffect(1, 0).getType()).ifPresent(player::removePotionEffect);
            }
        }
    }

    private ConfigurationSection stageSection(int stage) {
        return plugin.getConfig().getConfigurationSection("disease.stages.stage_" + stage);
    }

    private long stageDuration(int stage) {
        return Math.max(1L, plugin.getConfig().getLong("disease.stages.stage_" + stage + "_seconds", 60L));
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = dataConfig.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                ConfigurationSection section = players.getConfigurationSection(key);
                if (section != null) {
                    diseases.put(playerId, new DiseaseData(Math.clamp(section.getInt("stage", 1), 1, 4), Math.max(0L, section.getLong("stage_elapsed_seconds", 0L)), Math.max(0L, section.getLong("damage_elapsed_seconds", 0L)), Math.max(0L, section.getLong("warning_elapsed_seconds", 0L))));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration dataConfig = new YamlConfiguration();
        for (Map.Entry<UUID, DiseaseData> entry : diseases.entrySet()) {
            String path = "players." + entry.getKey();
            DiseaseData data = entry.getValue();
            dataConfig.set(path + ".stage", data.stage());
            dataConfig.set(path + ".stage_elapsed_seconds", data.stageElapsedSeconds());
            dataConfig.set(path + ".damage_elapsed_seconds", data.damageElapsedSeconds());
            dataConfig.set(path + ".warning_elapsed_seconds", data.warningElapsedSeconds());
        }
        try {
            dataConfig.save(dataFile);
            lastSave = System.currentTimeMillis();
        } catch (IOException exception) {
            plugin.getLogger().warning("Krankheitsdaten konnten nicht gespeichert werden: " + exception.getMessage());
        }
    }

    private double clamp(double value) {
        return Math.clamp(value, 0.0D, 1.0D);
    }

    private record DiseaseData(int stage, long stageElapsedSeconds, long damageElapsedSeconds, long warningElapsedSeconds) {
    }
}