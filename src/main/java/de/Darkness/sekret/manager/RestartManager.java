package de.Darkness.sekret.manager;

import de.Darkness.sekret.SekretPlugin;
import de.Darkness.sekret.util.PluginMessages;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class RestartManager implements Listener {

    private final SekretPlugin plugin;
    private BukkitTask countdown;
    private boolean executing;

    public RestartManager(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!intercepts(event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String label = label(event.getMessage());
        String base = label.substring(label.lastIndexOf(':') + 1);
        boolean allowed = player.hasPermission("vision.restart")
                || (base.equals("restart") && player.hasPermission("bukkit.command.restart"))
                || (base.equals("stop") && (player.hasPermission("bukkit.command.stop")
                || player.hasPermission("minecraft.command.stop")));
        if (!allowed) {
            player.sendMessage(PluginMessages.text(plugin, player,
                    "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
            return;
        }
        request(player, event.getMessage());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (!(event instanceof RemoteServerCommandEvent)) {
            interceptConsole(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        interceptConsole(event);
    }

    private void interceptConsole(ServerCommandEvent event) {
        if (intercepts(event.getCommand())) {
            event.setCancelled(true);
            String command = event.getCommand();
            CommandSender sender = event.getSender();
            if (plugin.getServer().isPrimaryThread()) {
                request(sender, command);
            } else {
                plugin.getServer().getScheduler().runTask(plugin, () -> request(sender, command));
            }
        }
    }

    private boolean intercepts(String command) {
        if (executing || !plugin.getConfig().getBoolean("restart.enabled", true)) {
            return false;
        }
        String label = label(command);
        return plugin.getConfig().getStringList("restart.commands").stream()
                .anyMatch(entry -> label(entry).equals(label));
    }

    public void requestMenuRestart(Player player) {
        if (!player.hasPermission("vision.admin") || !player.hasPermission("vision.restart")) {
            player.sendMessage(PluginMessages.text(plugin, player,
                    "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
            return;
        }
        request(player, plugin.getConfig().getString("restart.menu_command", "restart"));
    }

    private void request(CommandSender sender, String rawCommand) {
        if (countdown != null) {
            sender.sendMessage(PluginMessages.text(plugin, sender,
                    "§eEs läuft bereits ein Neustart-/Stopp-Countdown.",
                    "§eA restart/shutdown countdown is already running."));
            return;
        }
        String command = clean(rawCommand);
        if (command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            sender.sendMessage("§cUngültiger Neustartbefehl / Invalid restart command.");
            return;
        }
        plugin.getLogger().info("Neustart/Stopp angefordert von " + sender.getName() + ": " + command);
        String commandLabel = label(command);
        boolean stopping = commandLabel.substring(commandLabel.lastIndexOf(':') + 1).equals("stop");
        String message = stopping ? "restart.stop_message" : "restart.message";
        String finalMessage = stopping ? "restart.stop_now_message" : "restart.now_message";
        int seconds = plugin.getConfig().getBoolean("restart.enabled", true)
                ? Math.max(0, plugin.getConfig().getInt("restart.delay_seconds", 30)) : 0;
        if (seconds == 0) {
            broadcast(finalMessage, 0);
            execute(command);
            return;
        }
        Set<Integer> announcements = new HashSet<>(plugin.getConfig().getIntegerList("restart.announce_seconds"));
        broadcast(message, seconds);
        long started = System.nanoTime();
        int[] lastRemaining = {seconds};
        countdown = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long elapsed = (System.nanoTime() - started) / 1_000_000_000L;
            int remaining = (int) Math.max(0L, seconds - elapsed);
            if (remaining == 0) {
                shutdown();
                broadcast(finalMessage, 0);
                execute(command);
            } else if (remaining != lastRemaining[0]) {
                lastRemaining[0] = remaining;
                if (announcements.contains(remaining)) {
                    broadcast(message, remaining);
                }
            }
        }, 20L, 20L);
    }

    private void execute(String command) {
        executing = true;
        try {
            if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) {
                plugin.getLogger().warning("Neustart-/Stoppbefehl nicht gefunden: " + command
                        + ". Bitte restart.menu_command bzw. restart.commands prüfen.");
                plugin.getServer().broadcastMessage("§cVision: Neustart-/Stoppbefehl nicht verfügbar. Bitte die Konsole prüfen.");
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Neustart-/Stoppbefehl fehlgeschlagen: " + command, exception);
            plugin.getServer().broadcastMessage("§cVision: Neustart/Stopp fehlgeschlagen. Bitte die Konsole prüfen.");
        } finally {
            executing = false;
        }
    }

    private void broadcast(String path, int seconds) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sendAnnouncement(player, path, seconds);
        }
        sendAnnouncement(plugin.getServer().getConsoleSender(), path, seconds);
    }

    private void sendAnnouncement(CommandSender recipient, String path, int seconds) {
        String message = PluginMessages.configured(plugin, recipient, path).replace("<seconds>", String.valueOf(seconds));
        if (!message.isBlank()) {
            recipient.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    private String clean(String command) {
        String result = command.trim();
        return result.startsWith("/") ? result.substring(1).trim() : result;
    }

    private String label(String command) {
        return clean(command).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    }

    public void reload() {
        if (!plugin.getConfig().getBoolean("restart.enabled", true) && countdown != null) {
            shutdown();
            plugin.getLogger().info("Neustart-/Stopp-Countdown durch Deaktivierung abgebrochen.");
        }
    }

    public void shutdown() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
    }
}