package de.Darkness.sekret.command;

import de.Darkness.sekret.SekretPlugin;
import de.Darkness.sekret.manager.FeatureMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class SekretCommand implements CommandExecutor, TabCompleter {

    private static final Map<String, String> FEATURE_PATHS = new HashMap<>();

    static {
        for (FeatureMenu.Feature feature : FeatureMenu.Feature.values()) {
            FEATURE_PATHS.put(feature.getCommand(), feature.getPath());
        }
    }

    private final SekretPlugin plugin;

    public SekretCommand(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if ((args.length == 1 && args[0].equalsIgnoreCase("menu"))
                || (args.length == 0 && !sender.hasPermission("secret.admin"))) {
            if (sender instanceof Player player) {
                plugin.getPlayerMenu().open(player);
            } else {
                sender.sendMessage(text(plugin, sender, "§cDas Menü ist nur im Spiel verfügbar.", "§cThe menu is only available in-game."));
            }
            return true;
        }
        if (!sender.hasPermission("secret.admin")) {
            sender.sendMessage(text(plugin, sender, "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
            return true;
        }

        if (args.length >= 1 && (args[0].equalsIgnoreCase("language") || args[0].equalsIgnoreCase("sprache"))) {
            if (args.length != 2 || (!args[1].equalsIgnoreCase("de") && !args[1].equalsIgnoreCase("en"))) {
                sender.sendMessage("§e/" + label + " language <de|en>");
                return true;
            }
            plugin.setLanguage(args[1].toLowerCase(java.util.Locale.ROOT));
            sender.sendMessage(text(plugin, sender, "§aDie Standardsprache wurde auf " + args[1] + " gesetzt. Persönliche Sprachen bleiben erhalten.",
                    "§aThe default language was set to " + args[1] + ". Personal languages are kept."));
            return true;
        }

        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("admin"))) {
            if (sender instanceof Player player) {
                plugin.openFeatureMenu(player);
            } else {
                sender.sendMessage(text(plugin, sender, "§eDas Menü ist nur im Spiel verfügbar. Nutze /" + label + " <Funktion> <on|off>.",
                        "§eThe menu is only available in-game. Use /" + label + " <feature> <on|off>."));
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage(text(plugin, sender, "§aDie Secret-Konfiguration wurde neu geladen.", "§aThe Secret configuration has been reloaded."));
            return true;
        }

        if (args.length == 2 && FEATURE_PATHS.containsKey(args[0].toLowerCase())) {
            String state = args[1].toLowerCase();
            if (!state.equals("on") && !state.equals("off")) {
                sender.sendMessage(text(plugin, sender, "§eBenutzung: /", "§eUsage: /") + label + " " + args[0].toLowerCase() + " <on|off>");
                return true;
            }

            boolean enabled = state.equals("on");
            plugin.setFeatureEnabled(FEATURE_PATHS.get(args[0].toLowerCase()), enabled);
            sender.sendMessage("§e" + args[0].toLowerCase() + (enabled
                    ? text(plugin, sender, " §aaktiviert.", " §aenabled.") : text(plugin, sender, " §cdeaktiviert.", " §cdisabled.")));
            return true;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("heilen") || args[0].equalsIgnoreCase("heal"))) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(text(plugin, sender, "§cDieser Spieler ist nicht online.", "§cThis player is not online."));
                return true;
            }
            if (!plugin.getDiseaseManager().heal(target)) {
                sender.sendMessage("§e" + target.getName() + text(plugin, sender, " ist nicht krank.", " is not sick."));
                return true;
            }
            sender.sendMessage(text(plugin, sender, "§aDu hast " + target.getName() + " geheilt.", "§aYou healed " + target.getName() + "."));
            target.sendMessage(text(plugin, target, "§aDu fühlst dich wieder gesund.", "§aYou feel healthy again."));
            return true;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("anstecken") || args[0].equalsIgnoreCase("infect"))) {
            if (!plugin.getConfig().getBoolean("disease.enabled", true)) {
                sender.sendMessage(text(plugin, sender, "§cDas Krankheitssystem ist deaktiviert.", "§cThe disease system is disabled."));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(text(plugin, sender, "§cDieser Spieler ist nicht online.", "§cThis player is not online."));
                return true;
            }
            if (!plugin.getDiseaseManager().infect(target)) {
                sender.sendMessage("§e" + target.getName() + text(plugin, sender, " ist bereits krank.", " is already sick."));
                return true;
            }
            sender.sendMessage(text(plugin, sender, "§aDu hast " + target.getName() + " angesteckt.", "§aYou infected " + target.getName() + "."));
            return true;
        }

        sender.sendMessage(text(plugin, sender, "§eBenutzung: /" + label + " [menu|admin] | reload | heilen <Spieler> | anstecken <Spieler>",
                "§eUsage: /" + label + " [menu] | reload | heal <player> | infect <player>"));
        sender.sendMessage("§e/" + label + " language <de|en>");
        sender.sendMessage("§e/" + label + " <" + String.join("|", FEATURE_PATHS.keySet()) + "> <on|off>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            List<String> subcommands = new ArrayList<>(List.of("menu"));
            if (sender.hasPermission("secret.admin")) {
                subcommands.addAll(List.of("admin", "reload", "heilen", "anstecken", "heal", "infect", "language", "sprache"));
                subcommands.addAll(FEATURE_PATHS.keySet());
            }
            for (String subcommand : subcommands) {
                if (subcommand.startsWith(args[0].toLowerCase())) {
                    completions.add(subcommand);
                }
            }
            return completions;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("language") || args[0].equalsIgnoreCase("sprache")) && sender.hasPermission("secret.admin")) {
            List<String> completions = new ArrayList<>();
            for (String language : List.of("de", "en")) {
                if (language.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    completions.add(language);
                }
            }
            return completions;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("heilen") || args[0].equalsIgnoreCase("anstecken")
                || args[0].equalsIgnoreCase("heal") || args[0].equalsIgnoreCase("infect")) && sender.hasPermission("secret.admin")) {
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