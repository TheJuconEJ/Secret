package de.Darkness.sekret.command;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class SitCommand implements CommandExecutor {

    private final SekretPlugin plugin;

    public SitCommand(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player owner)) {
            sender.sendMessage(text(plugin, "§cDieser Befehl kann nur von Spielern verwendet werden.", "§cOnly players can use this command."));
            return true;
        }
        if (args.length > 1) {
            owner.sendMessage(text(plugin, owner, "§eBenutzung: /sit [angeleinter]", "§eUsage: /sit [leashed player]"));
            return true;
        }
        if (!plugin.getConfig().getBoolean("sit.enabled", true)) {
            owner.sendMessage(text(plugin, owner, "§cDie Sitz-Funktion ist derzeit deaktiviert.", "§cSitting is currently disabled."));
            return true;
        }

        Player target = owner;
        if (args.length == 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                owner.sendMessage(text(plugin, owner, "§cDieser Spieler ist nicht online.", "§cThis player is not online."));
                return true;
            }
            if (!plugin.getLeashManager().isOwnerOf(owner.getUniqueId(), target.getUniqueId())) {
                owner.sendMessage(text(plugin, owner, "§cDieser Spieler gehört nicht dir.", "§cYou do not own this player."));
                return true;
            }
        }

        plugin.getSitManager().sit(owner, target);
        if (target.equals(owner)) {
            owner.sendMessage(text(plugin, owner, "§aDu sitzt jetzt.", "§aYou are now sitting."));
        } else {
            owner.sendMessage("§a" + target.getName() + text(plugin, owner, " sitzt jetzt.", " is now sitting."));
        }
        return true;
    }
}