package de.Darkness.sekret.util;

import de.Darkness.sekret.SekretPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class PluginMessages {

    private PluginMessages() {
    }

    public static String text(JavaPlugin plugin, String german, String english) {
        return plugin.getConfig().getString("language", "de").equalsIgnoreCase("en") ? english : german;
    }

    public static String language(JavaPlugin plugin, CommandSender recipient) {
        if (recipient instanceof Player player && plugin instanceof SekretPlugin sekret && sekret.getPlayerMenu() != null) {
            return sekret.getPlayerMenu().language(player);
        }
        return "en".equalsIgnoreCase(plugin.getConfig().getString("language", "de")) ? "en" : "de";
    }

    public static String text(JavaPlugin plugin, CommandSender recipient, String german, String english) {
        return language(plugin, recipient).equals("en") ? english : german;
    }

    public static String configured(JavaPlugin plugin, String path) {
        String localizedPath = text(plugin, path, "translations.en." + path);
        return plugin.getConfig().getString(localizedPath, plugin.getConfig().getString(path, ""));
    }

    public static String configured(JavaPlugin plugin, CommandSender recipient, String path) {
        String localizedPath = text(plugin, recipient, path, "translations.en." + path);
        return plugin.getConfig().getString(localizedPath, plugin.getConfig().getString(path, ""));
    }

    public static String format(String message, Player player, Player owner, Player leashed) {
        String result = message == null ? "" : message;
        result = result.replace("<player>", nameOf(player));
        result = result.replace("<owner>", nameOf(owner));
        result = result.replace("<leashed>", nameOf(leashed));
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    public static void broadcast(SekretPlugin plugin, String path, Player player, Player owner, Player leashed) {
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            recipient.sendMessage(format(configured(plugin, recipient, path), player, owner, leashed));
        }
        Bukkit.getConsoleSender().sendMessage(format(configured(plugin, path), player, owner, leashed));
    }

    public static void playEffect(ConfigurationSection section, Player player) {
        if (section == null || player == null || !player.isOnline()) {
            return;
        }

        String soundName = section.getString("sound", "");
        if (!soundName.isBlank()) {
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
                player.getWorld().playSound(player.getLocation(), sound, 1.0F, 1.0F);
            } catch (IllegalArgumentException ignored) {
            }
        }

        String particleName = section.getString("particle", "");
        int count = section.getInt("particle_count", 0);
        if (!particleName.isBlank() && count > 0) {
            try {
                Particle particle = Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
                player.getWorld().spawnParticle(particle, player.getLocation().add(0.0D, 1.0D, 0.0D), count, 0.3D, 0.5D, 0.3D, 0.05D);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static String nameOf(Player player) {
        return player == null ? "" : player.getName();
    }
}