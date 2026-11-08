package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XAttribute;
import com.cryptomorin.xseries.XMaterial;
import de.Darkness.sekret.SekretPlugin;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.lone.itemsadder.api.FontImages.PlayerHudsHolderWrapper;
import dev.lone.itemsadder.api.FontImages.PlayerQuantityHudWrapper;
import dev.lone.itemsadder.api.ItemsAdder;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import static de.Darkness.sekret.util.PluginMessages.text;

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
    private final NamespacedKey dehydrationKey;
    private final Map<UUID, Integer> water = new HashMap<>();
    private final Map<UUID, Integer> elapsed = new HashMap<>();
    private final Map<UUID, Integer> damageElapsed = new HashMap<>();
    private final Set<UUID> displayedBars = new HashSet<>();
    private final Set<UUID> pendingBarRefreshes = new HashSet<>();
    private BukkitTask task;
    private int saveTicks;
    private String hudId = "iasurvival:thirst_bar";
    private boolean missingHudWarned;

    public ThirstManager(SekretPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "thirst-data.yml");
        this.dehydrationKey = new NamespacedKey(plugin, "dehydration");
    }

    public void start() {
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
        hudId = plugin.getConfig().getString("thirst.itemsadder.hud", "iasurvival:thirst_bar").trim();
        missingHudWarned = false;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (enabled()) {
                tick();
            } else {
                refresh();
            }
        }, 20L, 20L);
        refresh();
        save();
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clearBar(player);
        }
        displayedBars.clear();
        save();
    }

    @EventHandler
    public void onItemsAdderLoad(ItemsAdderLoadDataEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            missingHudWarned = false;
            refresh();
        });
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

    public boolean testDehydration(Player player) {
        if (!active(player)) {
            return false;
        }
        water.put(player.getUniqueId(), 0);
        elapsed.remove(player.getUniqueId());
        damageElapsed.remove(player.getUniqueId());
        updateBar(player);
        save();
        return true;
    }

    public boolean refill(Player player) {
        if (!active(player)) {
            return false;
        }
        water.put(player.getUniqueId(), MAX_WATER);
        elapsed.remove(player.getUniqueId());
        damageElapsed.remove(player.getUniqueId());
        updateBar(player);
        save();
        return true;
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
        updateDehydration(player, water.getOrDefault(player.getUniqueId(), MAX_WATER) == 0);
        if (!ItemsAdder.areItemsLoaded()) {
            return;
        }
        PlayerHudsHolderWrapper holder = new PlayerHudsHolderWrapper(player);
        PlayerQuantityHudWrapper hud = new PlayerQuantityHudWrapper(holder, hudId);
        if (!hud.exists()) {
            if (!missingHudWarned) {
                plugin.getLogger().warning("ItemsAdder-HUD fehlt: " + hudId
                        + ". Die HUD-Konfiguration in ItemsAdder installieren und /iareload ausführen.");
                missingHudWarned = true;
            }
            return;
        }
        UUID id = player.getUniqueId();
        int level = water.computeIfAbsent(id, key -> MAX_WATER);
        hud.setFloatValue(level / 2.0F);
        hud.setVisible(true);
        holder.sendUpdate();
        displayedBars.add(id);
    }

    private void clearBar(Player player) {
        updateDehydration(player, false);
        displayedBars.remove(player.getUniqueId());
        if (ItemsAdder.areItemsLoaded()) {
            PlayerHudsHolderWrapper holder = new PlayerHudsHolderWrapper(player);
            PlayerQuantityHudWrapper hud = new PlayerQuantityHudWrapper(holder, hudId);
            if (hud.exists()) {
                hud.setVisible(false);
                holder.sendUpdate();
            }
        }
    }

    private void updateDehydration(Player player, boolean dehydrated) {
        XAttribute.of("movement_speed").ifPresent(attribute -> {
            AttributeInstance instance = player.getAttribute(attribute.get());
            if (instance == null) {
                return;
            }
            List<AttributeModifier> modifiers = instance.getModifiers().stream()
                    .filter(modifier -> modifier.getKey().equals(dehydrationKey)).toList();
            if (dehydrated && modifiers.isEmpty()) {
                instance.addModifier(new AttributeModifier(dehydrationKey, -0.35D, AttributeModifier.Operation.ADD_SCALAR));
                player.sendTitle(text(plugin, player, "§c§lDU VERDURSTEST", "§c§lDEHYDRATED"),
                        text(plugin, player, "§eTrinke Wasser!", "§eDrink water!"), 10, 60, 20);
                player.sendMessage(text(plugin, player, "§cDein Wasser ist leer! Du bist langsamer und erleidest Durstschaden.",
                        "§cYour water is empty! You move slower and take dehydration damage."));
            } else if (!dehydrated) {
                modifiers.forEach(instance::removeModifier);
            }
        });
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
    public void onDeath(PlayerDeathEvent event) {
        damageElapsed.remove(event.getEntity().getUniqueId());
        clearBar(event.getEntity());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateBar(event.getPlayer());
        scheduleBarRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        scheduleBarRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        scheduleBarRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (!active(event.getPlayer()) && event.isCancelled()) {
            scheduleBarRefresh(event.getPlayer());
        }
    }

    private void scheduleBarRefresh(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingBarRefreshes.add(id)) {
            return;
        }
        for (long delay : new long[]{1L, 5L, 20L}) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (delay == 20L) {
                    pendingBarRefreshes.remove(id);
                }
                if (player.isOnline()) {
                    updateBar(player);
                }
            }, delay);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleBarRefresh(event.getPlayer());
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
        updateDehydration(event.getPlayer(), false);
        damageElapsed.remove(event.getPlayer().getUniqueId());
        displayedBars.remove(event.getPlayer().getUniqueId());
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