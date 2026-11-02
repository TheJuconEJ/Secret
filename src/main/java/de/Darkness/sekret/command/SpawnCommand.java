package de.Darkness.sekret.command;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class SpawnCommand implements CommandExecutor, TabCompleter, Listener {

    private final SekretPlugin plugin;
    private final Map<UUID, Long> lastCombat = new HashMap<>();
    private final Map<UUID, List<Long>> usageHistory = new HashMap<>();
    private final File usageFile;
    private final YamlConfiguration usageData;

    public SpawnCommand(SekretPlugin plugin) {
        this.plugin = plugin;
        this.usageFile = new File(plugin.getDataFolder(), "spawn-data.yml");
        this.usageData = YamlConfiguration.loadConfiguration(usageFile);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl ist nur für Spieler verfügbar.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("set")) {
            if (!canSetSpawn(player)) {
                player.sendMessage(ChatColor.RED + "Du darfst den Server-Spawn nicht festlegen.");
                return true;
            }
            Location location = player.getLocation();
            if (!player.getWorld().setSpawnLocation(location)) {
                player.sendMessage(ChatColor.RED + "Der Server-Spawn konnte hier nicht festgelegt werden.");
                return true;
            }
            plugin.getConfig().set("spawn.locations", null);
            plugin.getConfig().set("spawn.location", location);
            plugin.saveConfig();
            player.sendMessage(ChatColor.GREEN + "Der gemeinsame Server-Spawn wurde festgelegt.");
            return true;
        }

        if (args.length != 0) {
            player.sendMessage(ChatColor.RED + "Verwendung: /spawn" + (canSetSpawn(player) ? " [set]" : ""));
            return true;
        }

        long now = System.currentTimeMillis();
        long combatDuration = Math.max(1, plugin.getConfig().getInt("spawn.combat_tag_seconds", 15)) * 1000L;
        Long lastFight = lastCombat.get(player.getUniqueId());
        if (!plugin.getConfig().getBoolean("spawn.allow_in_combat", false)
                && lastFight != null && now - lastFight < combatDuration) {
            long seconds = (combatDuration - (now - lastFight) + 999L) / 1000L;
            player.sendMessage(ChatColor.RED + "Du bist im Kampf! Warte noch " + seconds + " Sekunden ohne Kampf.");
            return true;
        }

        int maxUses = Math.max(1, plugin.getConfig().getInt("spawn.max_uses", 1));
        long window = Math.max(1, plugin.getConfig().getInt("spawn.window_seconds", 600)) * 1000L;
        List<Long> uses = usageHistory.computeIfAbsent(player.getUniqueId(),
                id -> new ArrayList<>(usageData.getLongList(id.toString())));
        uses.removeIf(timestamp -> timestamp <= now - window);
        if (uses.size() >= maxUses) {
            long nextUse = uses.get(uses.size() - maxUses) + window;
            long seconds = Math.max(1L, (nextUse - now + 999L) / 1000L);
            player.sendMessage(ChatColor.RED + "Dein Spawn-Limit ist erreicht. Warte noch "
                    + (seconds / 60L) + " Minuten und " + (seconds % 60L) + " Sekunden.");
            return true;
        }

        Location destination = plugin.getConfig().getLocation("spawn.location");
        if (destination == null || destination.getWorld() == null) {
            player.sendMessage(ChatColor.RED + "Es ist kein verfügbarer Server-Spawn festgelegt. Ein Admin muss /spawn set ausführen.");
            return true;
        }
        if (player.teleport(destination)) {
            uses.add(System.currentTimeMillis());
            usageData.set(player.getUniqueId().toString(), new ArrayList<>(uses));
            try {
                usageData.save(usageFile);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.WARNING, "Spawn-Nutzungen konnten nicht gespeichert werden.", exception);
            }
            player.sendMessage(ChatColor.GREEN + "Du wurdest zum Server-Spawn teleportiert.");
        } else {
            player.sendMessage(ChatColor.RED + "Die Teleportation wurde verhindert.");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0 || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }
        if (!(attacker instanceof LivingEntity)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.getEntity() instanceof Player victim) {
            lastCombat.put(victim.getUniqueId(), now);
        }
        if (attacker instanceof Player player) {
            lastCombat.put(player.getUniqueId(), now);
        }
    }

    private boolean canSetSpawn(CommandSender sender) {
        return sender.hasPermission("secret.spawn.set") || sender.hasPermission("secret.admin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && canSetSpawn(sender) && "set".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return Collections.singletonList("set");
        }
        return Collections.emptyList();
    }
}