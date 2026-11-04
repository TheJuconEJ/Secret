package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XMaterial;
import de.Darkness.sekret.SekretPlugin;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ThirstManager implements Listener {

    private static final int MAX_WATER = 20;
    private static final Set<String> POSITIVE_EFFECTS = Set.of(
            "speed", "haste", "strength", "instant_health", "jump_boost", "regeneration",
            "resistance", "fire_resistance", "water_breathing", "invisibility", "night_vision",
            "health_boost", "absorption", "saturation", "luck", "slow_falling", "conduit_power",
            "dolphins_grace", "hero_of_the_village");
    private static final Set<String> NEGATIVE_EFFECTS = Set.of(
            "poison", "wither", "hunger", "nausea", "weakness", "slowness", "mining_fatigue",
            "blindness", "darkness", "unluck", "instant_damage", "levitation", "bad_omen",
            "raid_omen", "trial_omen", "infested", "oozing", "weaving", "wind_charged");

    private final SekretPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Integer> water = new HashMap<>();
    private final Map<UUID, Integer> elapsed = new HashMap<>();
    private final Map<UUID, Integer> damageElapsed = new HashMap<>();
    private final Set<UUID> displayedBars = new HashSet<>();
    private final Set<UUID> loadedResourcePacks = new HashSet<>();
    private UUID resourcePackId = UUID.randomUUID();
    private String resourcePackUrl;
    private byte[] resourcePackHash;
    private BukkitTask task;
    private int saveTicks;

    public ThirstManager(SekretPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "thirst-data.yml");
    }

    public void start() {
        File resourcePack = new File(plugin.getDataFolder(), "Vision-Wassertropfen.zip");
        if (!resourcePack.exists()) {
            plugin.saveResource("Vision-Wassertropfen.zip", false);
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    water.put(id, Math.max(0, Math.min(MAX_WATER, players.getInt(key + ".water", MAX_WATER))));
                    elapsed.put(id, Math.max(0, players.getInt(key + ".elapsed", 0)));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Ungültige Spieler-UUID in thirst-data.yml: " + key);
                }
            }
        }
        reload();
    }

    public void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clearBar(player);
        }
        displayedBars.clear();
        damageElapsed.clear();
        reloadResourcePack();
        if (enabled()) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
            refresh();
        }
        save();
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clearBar(player);
            if (resourcePackUrl != null) {
                player.removeResourcePack(resourcePackId);
            }
        }
        displayedBars.clear();
        loadedResourcePacks.clear();
        save();
    }

    private void reloadResourcePack() {
        if (resourcePackUrl != null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.removeResourcePack(resourcePackId);
            }
        }
        loadedResourcePacks.clear();
        resourcePackId = UUID.randomUUID();
        resourcePackUrl = null;
        resourcePackHash = null;
        String url = plugin.getConfig().getString("thirst.resource_pack.url", "").trim();
        if (url.isEmpty()) {
            return;
        }
        try {
            URI uri = URI.create(url);
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("Die URL muss eine öffentliche HTTP- oder HTTPS-Adresse sein.");
            }
            String hash = plugin.getConfig().getString("thirst.resource_pack.sha1", "").trim();
            if (!hash.isEmpty()) {
                if (!hash.matches("[0-9a-fA-F]{40}")) {
                    throw new IllegalArgumentException("Der SHA-1-Hash muss aus 40 Hexadezimalzeichen bestehen.");
                }
                resourcePackHash = HexFormat.of().parseHex(hash);
            }
            resourcePackUrl = uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Wasser-Resourcepack wird nicht geladen: " + exception.getMessage());
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sendResourcePack(player);
        }
    }

    private void sendResourcePack(Player player) {
        if (resourcePackUrl == null) {
            return;
        }
        try {
            player.setResourcePack(resourcePackId, resourcePackUrl, resourcePackHash,
                    "Vision: Resourcepack für volle, halbvolle und leere Wassertropfen laden?", false);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Wasser-Resourcepack für " + player.getName()
                    + " konnte nicht angefordert werden: " + exception.getMessage());
        }
    }

    public void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updateBar(player);
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("thirst.enabled", true);
    }

    private boolean active(Player player) {
        return enabled() && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
    }

    private void tick() {
        int interval = Math.max(1, plugin.getConfig().getInt("thirst.drain_interval_seconds", 60));
        int multiplier = Math.max(1, plugin.getConfig().getInt("thirst.accelerated_drain_multiplier", 2));
        int damageInterval = Math.max(1, plugin.getConfig().getInt("thirst.damage_interval_seconds", 5));
        double damage = Math.max(0.0D, plugin.getConfig().getDouble("thirst.damage", 1.0D));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (active(player)) {
                water.putIfAbsent(id, MAX_WATER);
                boolean sick = plugin.getConfig().getBoolean("disease.enabled", true)
                        && plugin.getDiseaseManager().isDiseased(player);
                boolean negativeEffect = player.getActivePotionEffects().stream()
                        .anyMatch(effect -> NEGATIVE_EFFECTS.contains(effect.getType().getKey().getKey()));
                long seconds = (long) elapsed.getOrDefault(id, 0) + (sick || negativeEffect ? multiplier : 1);
                if (seconds >= interval) {
                    int loss = (int) Math.min(MAX_WATER, seconds / interval);
                    water.computeIfPresent(id, (key, value) -> Math.max(0, value - loss));
                    seconds %= interval;
                }
                elapsed.put(id, (int) seconds);
                if (water.get(id) == 0 && damage > 0.0D) {
                    int damageSeconds = damageElapsed.getOrDefault(id, 0) + 1;
                    if (damageSeconds >= damageInterval) {
                        damageElapsed.remove(id);
                        player.damage(damage);
                    } else {
                        damageElapsed.put(id, damageSeconds);
                    }
                } else {
                    damageElapsed.remove(id);
                }
            } else {
                damageElapsed.remove(id);
            }
            updateBar(player);
        }
        if (++saveTicks >= 60) {
            saveTicks = 0;
            save();
        }
    }

    private void updateBar(Player player) {
        if (!active(player)) {
            clearBar(player);
            return;
        }
        UUID id = player.getUniqueId();
        int level = water.computeIfAbsent(id, key -> MAX_WATER);
        boolean customGlyphs = loadedResourcePacks.contains(id);
        String full = plugin.getConfig().getString("thirst.resource_pack.full", "\uE000");
        String half = plugin.getConfig().getString("thirst.resource_pack.half", "\uE001");
        String empty = plugin.getConfig().getString("thirst.resource_pack.empty", "\uE002");
        StringBuilder droplets = new StringBuilder();
        if (customGlyphs) {
            droplets.append("§r§f");
        }
        for (int index = 0; index < MAX_WATER / 2; index++) {
            if (index > 0 && !customGlyphs) {
                droplets.append(' ');
            }
            int remaining = level - index * 2;
            if (customGlyphs) {
                droplets.append(remaining >= 2 ? full : remaining == 1 ? half : empty);
            } else {
                droplets.append(remaining >= 2 ? "§b" : remaining == 1 ? "§3" : "§8");
                droplets.append("§l💧");
            }
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(droplets.toString()));
        displayedBars.add(id);
    }

    private void clearBar(Player player) {
        if (displayedBars.remove(player.getUniqueId())) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!active(event.getPlayer())) {
            return;
        }
        int amount = restoration(event.getItem());
        if (amount <= 0) {
            return;
        }
        // Apply after all consume handlers so a later cancellation cannot grant water.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (!event.isCancelled() && player.isOnline() && active(player)) {
                water.compute(player.getUniqueId(), (id, level) -> (int) Math.min(MAX_WATER,
                        (long) (level == null ? MAX_WATER : level) + amount));
                if (water.get(player.getUniqueId()) > 0) {
                    damageElapsed.remove(player.getUniqueId());
                }
                updateBar(player);
                save();
            }
        });
    }

    private int restoration(ItemStack item) {
        if (XMaterial.matchXMaterial(item.getType()).name().equals("MILK_BUCKET")) {
            return Math.max(0, plugin.getConfig().getInt("thirst.milk_restore", 6));
        }
        if (!XMaterial.matchXMaterial(item.getType()).name().equals("POTION")
                || !(item.getItemMeta() instanceof PotionMeta meta)) {
            return 0;
        }
        PotionType base = meta.getBasePotionType();
        List<PotionEffect> effects = new ArrayList<>();
        if (base != null) {
            effects.addAll(base.getPotionEffects());
        }
        effects.addAll(meta.getCustomEffects());
        if (effects.isEmpty()) {
            return base != null && base.name().equals("WATER")
                    ? Math.max(0, plugin.getConfig().getInt("thirst.water_bottle_restore", 6)) : 0;
        }
        for (PotionEffect effect : effects) {
            if (!POSITIVE_EFFECTS.contains(effect.getType().getKey().getKey())) {
                return 0;
            }
        }
        return Math.max(0, plugin.getConfig().getInt("thirst.positive_potion_restore", 4));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sendResourcePack(event.getPlayer());
        updateBar(event.getPlayer());
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!event.getID().equals(resourcePackId)) {
            return;
        }
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> loadedResourcePacks.add(event.getPlayer().getUniqueId());
            case ACCEPTED, DOWNLOADED -> {
                return;
            }
            default -> loadedResourcePacks.remove(event.getPlayer().getUniqueId());
        }
        updateBar(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        water.put(id, MAX_WATER);
        elapsed.put(id, 0);
        damageElapsed.remove(id);
        save();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                updateBar(player);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        damageElapsed.remove(event.getPlayer().getUniqueId());
        displayedBars.remove(event.getPlayer().getUniqueId());
        loadedResourcePacks.remove(event.getPlayer().getUniqueId());
        save();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : water.entrySet()) {
            String path = "players." + entry.getKey();
            data.set(path + ".water", entry.getValue());
            data.set(path + ".elapsed", elapsed.getOrDefault(entry.getKey(), 0));
        }
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Wasserwerte konnten nicht gespeichert werden.", exception);
        }
    }
}