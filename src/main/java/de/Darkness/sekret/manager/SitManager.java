package de.Darkness.sekret.manager;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SitManager implements Listener {

    private final SekretPlugin plugin;
    private final Map<UUID, UUID> seatByPlayer = new HashMap<>();
    private final Map<UUID, UUID> ownerBySeatedPlayer = new HashMap<>();
    private final Map<UUID, UUID> carrierByPlayer = new HashMap<>();
    private final Map<UUID, UUID> riderByCarrier = new HashMap<>();
    private final Map<UUID, Location> exitByPlayer = new HashMap<>();
    private final Map<UUID, Block> blockByPlayer = new HashMap<>();

    public SitManager(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean sit(Player owner, Player target) {
        if (!enabled() || !canSit(target)) {
            return false;
        }
        UUID lockedOwner = ownerBySeatedPlayer.get(target.getUniqueId());
        if (lockedOwner != null && !lockedOwner.equals(owner.getUniqueId())) {
            return false;
        }
        if (managed(target.getUniqueId())) {
            releaseTree(target.getUniqueId(), true);
        }
        if (target.isInsideVehicle() || !target.isOnGround()) {
            return false;
        }
        Location surface = target.getLocation();
        RayTraceResult floor = surface.getWorld().rayTraceBlocks(surface.clone().add(0, 0.15D, 0),
                new Vector(0, -1, 0), 0.75D, FluidCollisionMode.NEVER, true);
        if (floor == null || floor.getHitBlockFace() != BlockFace.UP) {
            return false;
        }
        surface.setY(floor.getHitPosition().getY());
        return createSeat(target, surface, owner.equals(target) ? null : owner.getUniqueId(), null);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("sit.enabled", true);
    }

    private boolean canSit(Player player) {
        return player.isOnline() && !player.isDead() && !player.isSleeping()
                && !player.isSwimming() && !player.isGliding() && player.getGameMode() != GameMode.SPECTATOR;
    }

    private boolean managed(UUID playerId) {
        return seatByPlayer.containsKey(playerId) || carrierByPlayer.containsKey(playerId);
    }

    private boolean clickAllowed(Player player) {
        return enabled() && canSit(player) && !player.isSneaking() && !player.isInsideVehicle()
                && !managed(player.getUniqueId())
                && (!plugin.getConfig().getBoolean("sit.empty_hand_only", true)
                || player.getInventory().getItemInMainHand().getType().isAir());
    }

    private boolean inReach(Player player, Location location) {
        double distance = Math.max(0.0D, Math.min(10.0D,
                plugin.getConfig().getDouble("sit.max_click_distance", 4.5D)));
        return player.getWorld().equals(location.getWorld())
                && player.getEyeLocation().distanceSquared(location) <= distance * distance;
    }

    private boolean createSeat(Player player, Location surface, UUID owner, Block block) {
        if (!clearAbove(surface, 0.65D, 1.1D)) {
            return false;
        }
        Location exit = player.getLocation();
        ArmorStand seat = surface.getWorld().spawn(surface, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setSmall(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setCollidable(false);
            stand.setPersistent(false);
            stand.setBasePlate(false);
        });
        if (!seat.addPassenger(player)) {
            seat.remove();
            return false;
        }
        UUID id = player.getUniqueId();
        seatByPlayer.put(id, seat.getUniqueId());
        exitByPlayer.put(id, exit);
        if (owner != null) {
            ownerBySeatedPlayer.put(id, owner);
        }
        if (block != null) {
            blockByPlayer.put(id, block);
        }
        player.setFallDistance(0);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || !clickAllowed(event.getPlayer())) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        Location surface = block.getLocation().add(0.5D, 0, 0.5D);
        if (block.getBlockData() instanceof Stairs stairs
                && plugin.getConfig().getBoolean("sit.click_stairs", true)) {
            if (stairs.getHalf() == Bisected.Half.BOTTOM) {
                surface.add(-stairs.getFacing().getModX() * 0.3D, 0.5D,
                        -stairs.getFacing().getModZ() * 0.3D);
            } else {
                surface.add(0, 1, 0);
            }
            surface.setDirection(stairs.getFacing().getOppositeFace().getDirection());
        } else if (block.getBlockData() instanceof Slab slab
                && plugin.getConfig().getBoolean("sit.click_slabs", true)) {
            surface.add(0, slab.getType() == Slab.Type.BOTTOM ? 0.5D : 1.0D, 0);
            surface.setYaw(player.getLocation().getYaw());
        } else {
            return;
        }
        if (!inReach(player, surface) || blockByPlayer.containsValue(block)) {
            return;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (clickAllowed(player) && inReach(player, surface) && !blockByPlayer.containsValue(block)
                    && (block.getBlockData() instanceof Stairs && plugin.getConfig().getBoolean("sit.click_stairs", true)
                    || block.getBlockData() instanceof Slab && plugin.getConfig().getBoolean("sit.click_slabs", true))) {
                createSeat(player, surface, null, block);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSeatInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        UUID clickedId = event.getRightClicked().getUniqueId();
        if (event.getRightClicked() instanceof ArmorStand) {
            for (Map.Entry<UUID, UUID> entry : seatByPlayer.entrySet()) {
                if (entry.getValue().equals(clickedId)) {
                    event.setCancelled(true);
                    if (player.getUniqueId().equals(ownerBySeatedPlayer.get(entry.getKey()))
                            || player.getUniqueId().equals(entry.getKey()) && !ownerBySeatedPlayer.containsKey(entry.getKey())) {
                        UUID seatedId = entry.getKey();
                        plugin.getServer().getScheduler().runTask(plugin, () -> releaseTree(seatedId, true));
                    }
                    return;
                }
            }
            return;
        }
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        if (player.getUniqueId().equals(ownerBySeatedPlayer.get(clickedId))) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> releaseTree(clickedId, true));
            return;
        }
        if (!plugin.getConfig().getBoolean("sit.click_players", true) || !clickAllowed(player)
                || !inReach(player, target.getLocation())) {
            return;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> mountPlayer(player, target));
    }

    private void mountPlayer(Player rider, Player clicked) {
        if (!plugin.getConfig().getBoolean("sit.click_players", true) || !clickAllowed(rider)
                || !canSit(clicked) || !inReach(rider, clicked.getLocation())) {
            return;
        }
        Player bottom = clicked;
        Set<UUID> tower = new HashSet<>();
        while (carrierByPlayer.containsKey(bottom.getUniqueId())) {
            if (!tower.add(bottom.getUniqueId())) {
                return;
            }
            Player carrier = Bukkit.getPlayer(carrierByPlayer.get(bottom.getUniqueId()));
            if (carrier == null) {
                return;
            }
            bottom = carrier;
        }
        tower.clear();
        Player top = bottom;
        while (true) {
            if (!tower.add(top.getUniqueId()) || !canSit(top) || top.equals(rider)) {
                return;
            }
            UUID next = riderByCarrier.get(top.getUniqueId());
            if (next == null) {
                if (!top.getPassengers().isEmpty()) {
                    return;
                }
                break;
            }
            Player passenger = Bukkit.getPlayer(next);
            if (passenger == null || !top.getPassengers().contains(passenger)) {
                return;
            }
            top = passenger;
        }
        Player movingTop = rider;
        while (true) {
            if (!tower.add(movingTop.getUniqueId())) {
                return;
            }
            UUID next = riderByCarrier.get(movingTop.getUniqueId());
            if (next == null) {
                if (!movingTop.getPassengers().isEmpty()) {
                    return;
                }
                break;
            }
            Player passenger = Bukkit.getPlayer(next);
            if (passenger == null || !movingTop.getPassengers().contains(passenger)) {
                return;
            }
            movingTop = passenger;
        }
        int limit = Math.max(0, plugin.getConfig().getInt("sit.player_stack_limit", 0));
        if (limit > 0 && tower.size() > limit) {
            return;
        }
        if (top.addPassenger(rider)) {
            carrierByPlayer.put(rider.getUniqueId(), top.getUniqueId());
            riderByCarrier.put(top.getUniqueId(), rider.getUniqueId());
            rider.setFallDistance(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLockedDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && ownerBySeatedPlayer.containsKey(player.getUniqueId())
                && event.getDismounted().getUniqueId().equals(seatByPlayer.get(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && managed(player.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (managed(player.getUniqueId()) && !player.isInsideVehicle()) {
                    releaseTree(player.getUniqueId(), true);
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && ownerBySeatedPlayer.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        for (Map.Entry<UUID, Block> entry : Map.copyOf(blockByPlayer).entrySet()) {
            if (entry.getValue().equals(event.getBlock())) {
                plugin.getServer().getScheduler().runTask(plugin, () -> releaseTree(entry.getKey(), true));
            }
        }
    }

    public void shutdown() {
        Set<UUID> players = new HashSet<>(seatByPlayer.keySet());
        players.addAll(carrierByPlayer.keySet());
        for (UUID id : players) {
            releaseTree(id, true);
        }
    }

    public void reload() {
        if (!enabled()) {
            shutdown();
            return;
        }
        for (UUID id : new HashSet<>(carrierByPlayer.keySet())) {
            releaseTree(id, true);
        }
        for (Map.Entry<UUID, Block> entry : Map.copyOf(blockByPlayer).entrySet()) {
            if (entry.getValue().getBlockData() instanceof Stairs
                    && !plugin.getConfig().getBoolean("sit.click_stairs", true)
                    || entry.getValue().getBlockData() instanceof Slab
                    && !plugin.getConfig().getBoolean("sit.click_slabs", true)) {
                releaseTree(entry.getKey(), true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        releaseTree(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        releaseTree(event.getEntity().getUniqueId(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        releaseTree(id, false);
        for (Map.Entry<UUID, UUID> entry : Map.copyOf(ownerBySeatedPlayer).entrySet()) {
            if (entry.getValue().equals(id)) {
                releaseTree(entry.getKey(), true);
            }
        }
    }

    private void releaseTree(UUID id, boolean relocate) {
        Location anchor = null;
        Player base = Bukkit.getPlayer(id);
        Set<UUID> visited = new HashSet<>();
        while (base != null && visited.add(base.getUniqueId())) {
            anchor = exitByPlayer.getOrDefault(base.getUniqueId(), base.getLocation()).clone();
            UUID carrier = carrierByPlayer.get(base.getUniqueId());
            if (carrier == null) {
                break;
            }
            base = Bukkit.getPlayer(carrier);
        }
        List<UUID> players = new ArrayList<>();
        visited.clear();
        UUID next = id;
        while (next != null && visited.add(next)) {
            players.add(next);
            next = riderByCarrier.get(next);
        }
        Location exit = relocate && anchor != null ? safeExit(anchor) : null;
        for (int index = players.size() - 1; index >= 0; index--) {
            standUp(players.get(index), exit);
        }
    }

    private void standUp(UUID id, Location exit) {
        ownerBySeatedPlayer.remove(id);
        exitByPlayer.remove(id);
        blockByPlayer.remove(id);
        UUID seatId = seatByPlayer.remove(id);
        UUID carrierId = carrierByPlayer.remove(id);
        if (carrierId != null) {
            riderByCarrier.remove(carrierId, id);
        }
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.getVehicle() != null
                && (player.getVehicle().getUniqueId().equals(seatId)
                || player.getVehicle().getUniqueId().equals(carrierId))) {
            player.leaveVehicle();
        }
        if (seatId != null) {
            Entity seat = Bukkit.getEntity(seatId);
            if (seat != null) {
                seat.remove();
            }
        }
        if ((seatId != null || carrierId != null) && exit != null && player != null && !player.isDead()) {
            Location destination = exit.clone();
            destination.setYaw(player.getLocation().getYaw());
            destination.setPitch(player.getLocation().getPitch());
            player.teleport(destination);
            player.setFallDistance(0);
        }
    }

    private Location safeExit(Location anchor) {
        double[][] offsets = {{0, 0}, {0.8D, 0}, {-0.8D, 0}, {0, 0.8D}, {0, -0.8D},
                {0.8D, 0.8D}, {-0.8D, 0.8D}, {0.8D, -0.8D}, {-0.8D, -0.8D}};
        for (double[] offset : offsets) {
            Location start = anchor.clone().add(offset[0], 0.6D, offset[1]);
            RayTraceResult floor = start.getWorld().rayTraceBlocks(start, new Vector(0, -1, 0),
                    2.0D, FluidCollisionMode.ALWAYS, true);
            if (floor == null || floor.getHitBlockFace() != BlockFace.UP
                    || floor.getHitBlock() == null || floor.getHitBlock().isLiquid()) {
                continue;
            }
            Location feet = floor.getHitPosition().toLocation(start.getWorld()).add(0, 0.01D, 0);
            if (clearAbove(feet, 0.02D, 1.8D)) {
                return feet;
            }
        }
        return null;
    }

    private boolean clearAbove(Location feet, double startHeight, double height) {
        for (double x : new double[]{-0.28D, 0, 0.28D}) {
            for (double z : new double[]{-0.28D, 0, 0.28D}) {
                if (feet.getWorld().rayTraceBlocks(feet.clone().add(x, startHeight, z),
                        new Vector(0, 1, 0), height, FluidCollisionMode.NEVER, true) != null) {
                    return false;
                }
            }
        }
        return true;
    }
}