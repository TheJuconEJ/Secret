package de.Darkness.sekret.command;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SitCommand implements CommandExecutor {

    private final SekretPlugin plugin;

    public SitCommand(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player owner)) {
            sender.sendMessage("§cDieser Befehl kann nur von Spielern verwendet werden.");
            return true;
        }
        if (args.length > 1) {
            owner.sendMessage("§eBenutzung: /sit [angeleinter]");
            return true;
        }
        if (!plugin.getConfig().getBoolean("sit.enabled", true)) {
            owner.sendMessage("§cDie Sitz-Funktion ist derzeit deaktiviert.");
            return true;
        }

        Player target = owner;
        if (args.length == 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                owner.sendMessage("§cDieser Spieler ist nicht online.");
                return true;
            }
            if (!plugin.getLeashManager().isOwnerOf(owner.getUniqueId(), target.getUniqueId())) {
                owner.sendMessage("§cDieser Spieler gehört nicht dir.");
                return true;
            }
        }

        plugin.getSitManager().sit(owner, target);
        if (target.equals(owner)) {
            owner.sendMessage("§aDu sitzt jetzt.");
        } else {
            owner.sendMessage("§a" + target.getName() + " sitzt jetzt.");
        }
        return true;
    }
}