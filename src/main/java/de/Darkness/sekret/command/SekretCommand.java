package de.Darkness.sekret.command;

import com.cryptomorin.xseries.XMaterial;
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
import java.util.Locale;
import java.util.Map;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class SekretCommand implements CommandExecutor, TabCompleter {

    private static final Map<String, String> FEATURE_PATHS = new HashMap<>();
    private static final Map<String, String> TEST_EFFECTS = Map.ofEntries(
            Map.entry("blow", "blow"), Map.entry("blowjob", "blow"),
            Map.entry("excitement", "excitement"), Map.entry("erregung", "excitement"),
            Map.entry("tripping", "tripping"), Map.entry("stolpern", "tripping"),
            Map.entry("disease", "disease"), Map.entry("krankheit", "disease"),
            Map.entry("thirst", "thirst"), Map.entry("durst", "thirst"),
            Map.entry("deaths", "deaths"), Map.entry("todesliste", "deaths"),
            Map.entry("leash", "leash"), Map.entry("anleinen", "leash"),
            Map.entry("sit", "sit"), Map.entry("sitzen", "sit"),
            Map.entry("spread", "spread"), Map.entry("ansteckung", "spread"),
            Map.entry("healing", "healing"), Map.entry("heilungsitem", "healing"),
            Map.entry("sound", "sound"), Map.entry("musik", "sound"), Map.entry("spawn", "spawn"));

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
                || (args.length == 0 && !sender.hasPermission("vision.admin"))) {
            if (sender instanceof Player player) {
                plugin.getPlayerMenu().open(player);
            } else {
                sender.sendMessage(text(plugin, sender, "§cDas Menü ist nur im Spiel verfügbar.", "§cThe menu is only available in-game."));
            }
            return true;
        }
        if (!sender.hasPermission("vision.admin")) {
            sender.sendMessage(text(plugin, sender, "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("test")) {
            if (args.length < 2 || args.length > 4 || !TEST_EFFECTS.containsKey(args[1].toLowerCase(Locale.ROOT))) {
                sender.sendMessage("§e/" + label + " test <" + String.join("|", TEST_EFFECTS.keySet()) + "> [Spieler] [Partner]");
                sender.sendMessage(text(plugin, sender, "§cAchtung: Todes-Tests töten den Spieler; Krankheit und Durst sind echt!",
                        "§cWarning: Death tests kill the player; disease and thirst are real!"));
                return true;
            }
            String effect = TEST_EFFECTS.get(args[1].toLowerCase(Locale.ROOT));
            if (effect.equals("sound")) {
                if (args.length != 2) {
                    sender.sendMessage("§e/" + label + " test sound");
                    return true;
                }
                sender.sendMessage(plugin.getScheduledSoundManager().testPlayback()
                        ? text(plugin, sender, "§aMusiktest für alle Spieler gestartet.", "§aMusic test started for all players.")
                        : text(plugin, sender, "§cMusik deaktiviert, Voice Chat nicht bereit oder sounds/sound.mp3 fehlt.",
                                "§cMusic disabled, Voice Chat not ready or sounds/sound.mp3 missing."));
                return true;
            }
            boolean paired = effect.equals("leash") || effect.equals("sit") || effect.equals("spread");
            if (args.length == 4 && !paired) {
                sender.sendMessage("§e/" + label + " test " + args[1] + " [Spieler]");
                return true;
            }
            Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player player ? player : null;
            if (target == null) {
                sender.sendMessage(text(plugin, sender, "§cGib einen Spieler an, der online ist.", "§cSpecify an online player."));
                return true;
            }
            Player partner = args.length == 4 ? Bukkit.getPlayerExact(args[3]) : sender instanceof Player player ? player : null;
            if (paired && (partner == null || partner.equals(target) || partner.isDead() || partner.getWorld() != target.getWorld())) {
                sender.sendMessage(text(plugin, sender, "§eBenutzung: /" + label + " test " + args[1] + " <Spieler> <anderer Partner in derselben Welt>",
                        "§eUsage: /" + label + " test " + args[1] + " <player> <different partner in the same world>"));
                return true;
            }
            if (target.isDead()) {
                sender.sendMessage(text(plugin, sender, "§cDieser Spieler ist tot.", "§cThis player is dead."));
                return true;
            }
            if (effect.equals("spawn")) {
                var spawn = plugin.getCommand("spawn");
                if (spawn != null) {
                    spawn.execute(target, spawn.getName(), new String[0]);
                }
                return true;
            }
            boolean success = switch (effect) {
                case "disease" -> plugin.getDiseaseManager().infect(target);
                case "thirst" -> plugin.getThirstManager().testDehydration(target);
                case "leash" -> plugin.getLeashManager().testLeash(partner, target);
                case "sit" -> {
                    boolean enabled = plugin.getConfig().getBoolean("sit.enabled", true);
                    if (enabled) {
                        plugin.getSitManager().sit(partner, target);
                    }
                    yield enabled && target.isInsideVehicle();
                }
                case "spread" -> plugin.getConfig().getBoolean("disease.spread_enabled", true)
                        && plugin.getDiseaseManager().isDiseased(partner) && plugin.getDiseaseManager().infect(target);
                case "healing" -> {
                    var item = XMaterial.matchXMaterial(plugin.getConfig().getString("disease.healing.item", "GOLDEN_APPLE"))
                            .map(XMaterial::parseItem).orElse(null);
                    boolean given = plugin.getConfig().getBoolean("disease.enabled", true)
                            && plugin.getConfig().getBoolean("disease.healing.enabled", true)
                            && item != null && target.getInventory().addItem(item).isEmpty();
                    if (given) {
                        target.sendMessage(text(plugin, target, "§eHeilungsitem erhalten – zum Testen konsumieren.",
                                "§eHealing item received – consume it to test healing."));
                    }
                    yield given;
                }
                case "deaths" -> {
                    boolean enabled = plugin.getConfig().getBoolean("death_scoreboard.enabled", true);
                    if (enabled) {
                        plugin.getDeathScoreboardManager().setVisible(target, true);
                    }
                    yield enabled;
                }
                default -> plugin.getDeathMechanicsManager().testEffect(target, effect);
            };
            if (!success) {
                sender.sendMessage(text(plugin, sender, "§cTest nicht möglich: Prüfe Aktivierung, Spielmodus, Spielerzustand, Entfernung und freien Inventarplatz. Für Ansteckung muss der Partner krank sein.",
                        "§cCannot test: check enabled features, game mode, player state, distance and inventory space. For spreading, the partner must be sick."));
                return true;
            }
            sender.sendMessage(text(plugin, sender, "§aTest ausgelöst für ", "§aTest triggered for ") + target.getName() + ": " + args[1]);
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
            sender.sendMessage(text(plugin, sender, "§aDie Vision-Konfiguration wurde neu geladen.", "§aThe Vision configuration has been reloaded."));
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

        if (args.length >= 1 && (args[0].equalsIgnoreCase("heilen") || args[0].equalsIgnoreCase("heal"))) {
            if (args.length > 2) {
                sender.sendMessage("§e/" + label + " heal [Spieler]");
                return true;
            }
            Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player player ? player : null;
            if (target == null) {
                sender.sendMessage(text(plugin, sender, "§cGib einen Spieler an, der online ist.", "§cSpecify an online player."));
                return true;
            }
            if (!plugin.getDiseaseManager().heal(target)) {
                sender.sendMessage("§e" + target.getName() + text(plugin, sender, " ist nicht krank.", " is not sick."));
                return true;
            }
            if (!sender.equals(target)) {
                sender.sendMessage(text(plugin, sender, "§aDu hast " + target.getName() + " sofort von der Krankheit geheilt.",
                        "§aYou instantly cured " + target.getName() + " of disease."));
            }
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

        sender.sendMessage(text(plugin, sender, "§eBenutzung: /" + label + " [menu|admin] | reload | heal [Spieler] | anstecken <Spieler>",
                "§eUsage: /" + label + " [menu|admin] | reload | heal [player] | infect <player>"));
        sender.sendMessage("§e/" + label + " language <de|en>");
        sender.sendMessage("§e/" + label + " test <" + String.join("|", TEST_EFFECTS.keySet()) + "> [Spieler] [Partner]");
        sender.sendMessage("§e/" + label + " <" + String.join("|", FEATURE_PATHS.keySet()) + "> <on|off>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            List<String> subcommands = new ArrayList<>(List.of("menu"));
            if (sender.hasPermission("vision.admin")) {
                subcommands.addAll(List.of("admin", "reload", "heilen", "anstecken", "heal", "infect", "language", "sprache", "test"));
                subcommands.addAll(FEATURE_PATHS.keySet());
            }
            for (String subcommand : subcommands) {
                if (subcommand.startsWith(args[0].toLowerCase())) {
                    completions.add(subcommand);
                }
            }
            return completions;
        }
        if (sender.hasPermission("vision.admin") && args[0].equalsIgnoreCase("test")) {
            if (args.length == 2) {
                return TEST_EFFECTS.keySet().stream()
                        .filter(effect -> effect.startsWith(args[1].toLowerCase(Locale.ROOT))).sorted().toList();
            }
            if (args.length == 3 || (args.length == 4 && List.of("leash", "sit", "spread")
                    .contains(TEST_EFFECTS.getOrDefault(args[1].toLowerCase(Locale.ROOT), "")))) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
            }
            return Collections.emptyList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("language") || args[0].equalsIgnoreCase("sprache")) && sender.hasPermission("vision.admin")) {
            List<String> completions = new ArrayList<>();
            for (String language : List.of("de", "en")) {
                if (language.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    completions.add(language);
                }
            }
            return completions;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("heilen") || args[0].equalsIgnoreCase("anstecken")
                || args[0].equalsIgnoreCase("heal") || args[0].equalsIgnoreCase("infect")) && sender.hasPermission("vision.admin")) {
            List<String> completions = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
            return completions;
        }
        if (args.length == 2 && FEATURE_PATHS.containsKey(args[0].toLowerCase()) && sender.hasPermission("vision.admin")) {
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