package de.Darkness.sekret.command;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class SekretCommand implements CommandExecutor, TabCompleter {

    private static final Map<String, String> FEATURE_PATHS = Map.of(
            "blow", "blow.enabled",
            "excitement", "excitement.enabled",
            "tripping", "tripping.enabled",
            "leash", "leash.enabled",
            "disease", "disease.enabled",
            "sit", "sit.enabled",
            "water", "water.enabled"
    );

    private final SekretPlugin plugin;

    public SekretCommand(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("secret.admin")) {
            sender.sendMessage("§cDafür hast du keine Berechtigung.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage("§aDie Secret-Konfiguration wurde neu geladen.");
            return true;
        }

        if (args.length == 2 && FEATURE_PATHS.containsKey(args[0].toLowerCase())) {
            String state = args[1].toLowerCase();
            if (!state.equals("on") && !state.equals("off")) {
                sender.sendMessage("§eBenutzung: /" + label + " " + args[0].toLowerCase() + " <on|off>");
                return true;
            }

            boolean enabled = state.equals("on");
            plugin.getConfig().set(FEATURE_PATHS.get(args[0].toLowerCase()), enabled);
            plugin.saveConfig();
            plugin.reloadPlugin();
            sender.sendMessage("§aDie Funktion §e" + args[0].toLowerCase() + "§a wurde "
                    + (enabled ? "§aaktiviert" : "§cdeaktiviert") + "§a.");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("heilen")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cDieser Spieler ist nicht online.");
                return true;
            }
            if (!plugin.getDiseaseManager().heal(target)) {
                sender.sendMessage("§e" + target.getName() + " ist nicht krank.");
                return true;
            }
            sender.sendMessage("§aDu hast " + target.getName() + " geheilt.");
            target.sendMessage("§aDu fühlst dich wieder gesund.");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("anstecken")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cDieser Spieler ist nicht online.");
                return true;
            }
            if (!plugin.getDiseaseManager().infect(target)) {
                sender.sendMessage("§e" + target.getName() + " ist bereits krank.");
                return true;
            }
            sender.sendMessage("§aDu hast " + target.getName() + " angesteckt.");
            return true;
        }

        sender.sendMessage("§eBenutzung: /" + label + " <reload|heilen|anstecken <Spieler>|"
                + "blow|excitement|tripping|leash|disease|sit|water> <on|off>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String subcommand : List.of("reload", "heilen", "anstecken", "blow", "excitement", "tripping", "leash", "disease", "sit", "water")) {
                if (!sender.hasPermission("secret.admin")) {
                    continue;
                }
                if (subcommand.startsWith(args[0].toLowerCase())) {
                    completions.add(subcommand);
                }
            }
            return completions;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("heilen") || args[0].equalsIgnoreCase("anstecken")) && sender.hasPermission("secret.admin")) {
            List<String> completions = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
            return completions;
        }
        if (args.length == 2 && FEATURE_PATHS.containsKey(args[0].toLowerCase()) && sender.hasPermission("secret.admin")) {
            List<String> completions = new ArrayList<>();
            for (String state : List.of("on", "off")) {
                if (state.startsWith(args[1].toLowerCase())) {
                    completions.add(state);
                }
            }
            return completions;
        }
        return Collections.emptyList();
    }
}