package de.Darkness.sekret.manager;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SitManager implements Listener {

    private final SekretPlugin plugin;
    private final Map<UUID, UUID> seatByPlayer = new HashMap<>();
    private final Map<UUID, UUID> ownerBySeatedPlayer = new HashMap<>();

    public SitManager(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    public void sit(Player owner, Player target) {
        if (!plugin.getConfig().getBoolean("sit.enabled", true)) {
            return;
        }
        standUp(target.getUniqueId());
        target.leaveVehicle();

        ArmorStand seat = target.getWorld().spawn(target.getLocation().subtract(0.0D, 1.25D, 0.0D), ArmorStand.class);
        seat.setVisible(false);
        seat.setSmall(true);
        seat.setMarker(true);
        seat.setGravity(false);
        seat.setInvulnerable(true);
        seat.setSilent(true);
        seat.setCollidable(false);
        seat.setPersistent(false);
        seat.addPassenger(target);

        seatByPlayer.put(target.getUniqueId(), seat.getUniqueId());
        ownerBySeatedPlayer.put(target.getUniqueId(), owner.getUniqueId());
    }

    public void shutdown() {
        for (UUID playerId : seatByPlayer.keySet().toArray(UUID[]::new)) {
            standUp(playerId);
        }
    }

    public void reload() {
        if (!plugin.getConfig().getBoolean("sit.enabled", true)) {
            shutdown();
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player && seatByPlayer.get(player.getUniqueId()) != null
                && seatByPlayer.get(player.getUniqueId()).equals(event.getVehicle().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSeatInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ArmorStand seat)) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : seatByPlayer.entrySet()) {
            if (!entry.getValue().equals(seat.getUniqueId())) {
                continue;
            }
            UUID seatedPlayerId = entry.getKey();
            if (event.getPlayer().getUniqueId().equals(ownerBySeatedPlayer.get(seatedPlayerId))) {
                standUp(seatedPlayerId);
            }
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        standUp(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        standUp(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        standUp(playerId);
        for (Map.Entry<UUID, UUID> entry : ownerBySeatedPlayer.entrySet().toArray(Map.Entry[]::new)) {
            if (entry.getValue().equals(playerId)) {
                standUp(entry.getKey());
            }
        }
    }

    private void standUp(UUID playerId) {
        ownerBySeatedPlayer.remove(playerId);
        UUID seatId = seatByPlayer.remove(playerId);
        if (seatId == null) {
            return;
        }
        Entity seat = Bukkit.getEntity(seatId);
        if (seat != null && seat.isValid()) {
            seat.remove();
        }
    }
}