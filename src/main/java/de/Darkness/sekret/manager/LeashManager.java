package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.particles.XParticle;
import de.Darkness.sekret.SekretPlugin;
import de.Darkness.sekret.util.PluginMessages;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LeashManager implements Listener {

    private final SekretPlugin plugin;
    private final File storageFile;
    private final Map<UUID, UUID> ownerByLeashed = new HashMap<>();
    private final Map<UUID, Set<UUID>> leashedByOwner = new HashMap<>();
    private final Map<UUID, UUID> visualLeashes = new HashMap<>();
    private BukkitTask particleTask;

    public LeashManager(SekretPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "leashes.yml");
        loadRelationships();
    }

    public void start() {
        startParticleTask();
    }

    public void reload() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        for (UUID targetId : new HashSet<>(visualLeashes.keySet())) {
            removeVisual(targetId);
        }
        if (!plugin.getConfig().getBoolean("leash.enabled", true)) {
            return;
        }
        for (UUID targetId : ownerByLeashed.keySet()) {
            refresh(targetId);
        }
        startParticleTask();
    }

    public void shutdown() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        for (UUID targetId : new HashSet<>(visualLeashes.keySet())) {
            removeVisual(targetId);
        }
        saveRelationships();
    }

    public boolean isOwnerOf(UUID ownerId, UUID targetId) {
        return ownerId.equals(ownerByLeashed.get(targetId));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!plugin.getConfig().getBoolean("leash.enabled", true) || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        if (!event.getHand().name().equals("HAND") || !isHoldingLead(event.getPlayer())) {
            return;
        }

        Player owner = event.getPlayer();
        if (owner.getUniqueId().equals(target.getUniqueId())) {
            return;
        }

        UUID currentOwner = ownerByLeashed.get(target.getUniqueId());
        if (currentOwner == null) {
            event.setCancelled(true);
            createRelationship(owner, target);
            return;
        }

        event.setCancelled(true);
        if (currentOwner.equals(owner.getUniqueId())) {
            removeRelationship(target.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().distanceSquared(event.getTo()) <= 0.0D) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        if (ownerByLeashed.containsKey(playerId)) {
            syncVisual(playerId);
            pullTowardsOwner(playerId);
        }
        Set<UUID> targets = leashedByOwner.get(playerId);
        if (targets != null) {
            for (UUID targetId : Set.copyOf(targets)) {
                pullTowardsOwner(targetId);
            }
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        detachAssociatedVisuals(event.getPlayer().getUniqueId());
        runNextTick(() -> refreshAssociated(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        runNextTick(() -> refreshAssociated(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        detachAssociatedVisuals(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        detachAssociatedVisuals(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        runNextTick(() -> refreshAssociated(event.getPlayer().getUniqueId()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnleash(EntityUnleashEvent event) {
        if (visualLeashes.containsValue(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageProxy(EntityDamageByEntityEvent event) {
        if (visualLeashes.containsValue(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void createRelationship(Player owner, Player target) {
        UUID ownerId = owner.getUniqueId();
        UUID targetId = target.getUniqueId();
        ownerByLeashed.put(targetId, ownerId);
        leashedByOwner.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(targetId);
        saveRelationships();
        refresh(targetId);
        PluginMessages.broadcast(plugin, "messages.leash_created", null, owner, target);
    }

    private void removeRelationship(UUID targetId) {
        UUID ownerId = ownerByLeashed.remove(targetId);
        if (ownerId == null) {
            return;
        }
        Set<UUID> targets = leashedByOwner.get(ownerId);
        if (targets != null) {
            targets.remove(targetId);
            if (targets.isEmpty()) {
                leashedByOwner.remove(ownerId);
            }
        }
        removeVisual(targetId);
        saveRelationships();
    }

    private void refreshAssociated(UUID playerId) {
        if (ownerByLeashed.containsKey(playerId)) {
            refresh(playerId);
        }
        Set<UUID> targets = leashedByOwner.get(playerId);
        if (targets != null) {
            for (UUID targetId : Set.copyOf(targets)) {
                refresh(targetId);
            }
        }
    }

    private void detachAssociatedVisuals(UUID playerId) {
        if (ownerByLeashed.containsKey(playerId)) {
            removeVisual(playerId);
        }
        Set<UUID> targets = leashedByOwner.get(playerId);
        if (targets != null) {
            for (UUID targetId : Set.copyOf(targets)) {
                removeVisual(targetId);
            }
        }
    }

    private void refresh(UUID targetId) {
        UUID ownerId = ownerByLeashed.get(targetId);
        Player target = Bukkit.getPlayer(targetId);
        Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
        if (target == null || owner == null || target.isDead() || owner.isDead() || target.getWorld() != owner.getWorld()) {
            removeVisual(targetId);
            return;
        }

        UUID visualId = visualLeashes.get(targetId);
        Entity existing = visualId == null ? null : Bukkit.getEntity(visualId);
        if (existing instanceof ArmorStand stand && existing.isValid()) {
            stand.teleport(getNeckLocation(target));
            stand.setLeashHolder(owner);
            return;
        }
        if (visualId != null) {
            visualLeashes.remove(targetId);
        }

        ArmorStand stand = target.getWorld().spawn(getNeckLocation(target), ArmorStand.class);
        stand.setVisible(false);
        stand.setSmall(true);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCollidable(false);
        stand.setPersistent(false);
        stand.setLeashHolder(owner);
        visualLeashes.put(targetId, stand.getUniqueId());
    }

    private void syncVisual(UUID targetId) {
        UUID visualId = visualLeashes.get(targetId);
        Player target = Bukkit.getPlayer(targetId);
        if (visualId == null || target == null) {
            refresh(targetId);
            return;
        }
        Entity entity = Bukkit.getEntity(visualId);
        if (!(entity instanceof ArmorStand stand) || !entity.isValid()) {
            visualLeashes.remove(targetId);
            refresh(targetId);
            return;
        }
        stand.teleport(getNeckLocation(target));
    }

    private void pullTowardsOwner(UUID targetId) {
        UUID ownerId = ownerByLeashed.get(targetId);
        Player target = Bukkit.getPlayer(targetId);
        Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
        if (target == null || owner == null || target.isDead() || owner.isDead() || target.getWorld() != owner.getWorld()) {
            removeVisual(targetId);
            return;
        }

        double maximumDistance = Math.max(0.5D, plugin.getConfig().getDouble("leash.max_distance", 10.0D));
        Vector direction = owner.getLocation().toVector().subtract(target.getLocation().toVector());
        if (direction.lengthSquared() <= maximumDistance * maximumDistance) {
            return;
        }

        direction.normalize().multiply(0.45D);
        direction.setY(Math.max(-0.15D, Math.min(0.35D, direction.getY())));
        target.setVelocity(direction);
    }

    private void removeVisual(UUID targetId) {
        UUID visualId = visualLeashes.remove(targetId);
        if (visualId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(visualId);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void startParticleTask() {
        if (!plugin.getConfig().getBoolean("leash.enabled", true)) {
            return;
        }
        if (particleTask != null) {
            particleTask.cancel();
        }
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderLeashParticles, 0L, 2L);
    }

    private void renderLeashParticles() {
        if (!plugin.getConfig().getBoolean("leash.enabled", true)) {
            return;
        }
        if (ownerByLeashed.isEmpty()) {
            return;
        }
        for (UUID targetId : Set.copyOf(ownerByLeashed.keySet())) {
            UUID ownerId = ownerByLeashed.get(targetId);
            Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
            Player target = Bukkit.getPlayer(targetId);
            if (owner == null || target == null || owner.isDead() || target.isDead() || owner.getWorld() != target.getWorld()) {
                removeVisual(targetId);
                continue;
            }
            drawParticleLeash(owner, target);
        }
    }

    private void drawParticleLeash(Player owner, Player target) {
        Location start = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().normalize().multiply(0.45D)).subtract(0.0D, 0.35D, 0.0D);
        Location end = getNeckLocation(target);
        Vector line = end.toVector().subtract(start.toVector());
        double length = line.length();
        if (length == 0.0D) {
            return;
        }
        Vector step = line.normalize().multiply(0.25D);
        Particle.DustOptions brownDust = new Particle.DustOptions(Color.fromRGB(105, 63, 35), 1.0F);
        Particle dust = XParticle.of("DUST").map(XParticle::get).orElse(null);
        if (dust == null) {
            return;
        }
        for (double distance = 0.0D; distance < length; distance += 0.25D) {
            Location point = start.clone().add(step.clone().multiply(distance / 0.25D));
            owner.getWorld().spawnParticle(dust, point, 1, 0.0D, 0.0D, 0.0D, 0.0D, brownDust);
        }
    }

    private Location getNeckLocation(Player player) {
        return player.getLocation().add(0.0D, 1.45D, 0.0D);
    }

    private void loadRelationships() {
        if (!storageFile.exists()) {
            return;
        }
        FileConfiguration storage = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection section = storage.getConfigurationSection("leashes");
        if (section == null) {
            return;
        }
        for (String targetText : section.getKeys(false)) {
            String ownerText = section.getString(targetText);
            try {
                UUID targetId = UUID.fromString(targetText);
                UUID ownerId = UUID.fromString(ownerText);
                if (!targetId.equals(ownerId)) {
                    ownerByLeashed.put(targetId, ownerId);
                    leashedByOwner.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(targetId);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveRelationships() {
        FileConfiguration storage = new YamlConfiguration();
        for (Map.Entry<UUID, UUID> relationship : ownerByLeashed.entrySet()) {
            storage.set("leashes." + relationship.getKey(), relationship.getValue().toString());
        }
        try {
            storage.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Leinenbeziehungen konnten nicht gespeichert werden: " + exception.getMessage());
        }
    }

    private boolean isHoldingLead(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return item.getType().getKey().getKey().equals("lead");
    }

    private void runNextTick(Runnable task) {
        BukkitTask ignored = Bukkit.getScheduler().runTask(plugin, task);
    }
}