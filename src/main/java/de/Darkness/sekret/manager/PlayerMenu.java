package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XMaterial;
import de.Darkness.sekret.SekretPlugin;
import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class PlayerMenu implements Listener {

    private final SekretPlugin plugin;
    private final File file;
    private final YamlConfiguration preferences;

    public PlayerMenu(SekretPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "player-preferences.yml");
        preferences = YamlConfiguration.loadConfiguration(file);
    }

    public String language(Player player) {
        String selected = preferences.getString("players." + player.getUniqueId() + ".language",
                plugin.getConfig().getString("language", "de"));
        return "en".equalsIgnoreCase(selected) ? "en" : "de";
    }

    private void setLanguage(Player player, String language) {
        preferences.set("players." + player.getUniqueId() + ".language", language);
        try {
            preferences.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Spielereinstellungen konnten nicht gespeichert werden.", exception);
        }
        plugin.refreshPlayerLanguage(player);
        player.sendMessage(text(plugin, player, "§aDeine Sprache ist jetzt Deutsch.", "§aYour language is now English."));
    }

    public void open(Player player) {
        MenuHolder holder = new MenuHolder(player);
        Inventory inventory = holder.getInventory();
        ItemStack border = item("GRAY_STAINED_GLASS_PANE", " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, border);
        }
        boolean visible = plugin.getDeathScoreboardManager().isVisible(player);
        boolean enabled = plugin.getConfig().getBoolean("death_scoreboard.enabled", true);
        inventory.setItem(11, item("SKELETON_SKULL", text(plugin, player, "§c§lTodesanzeige", "§c§lDeath leaderboard"), List.of(
                text(plugin, player, "§7Nur deine Anzeige wird geändert.", "§7Only your own display is changed."),
                text(plugin, player, "§7Deine Auswahl: ", "§7Your preference: ")
                        + (visible ? text(plugin, player, "§aSichtbar", "§aVisible") : text(plugin, player, "§cAusgeblendet", "§cHidden")),
                enabled ? text(plugin, player, "§eKlicken zum Umschalten", "§eClick to toggle")
                        : text(plugin, player, "§6Vom Admin deaktiviert; Auswahl bleibt gespeichert.", "§6Disabled by admin; preference is still saved."))));
        inventory.setItem(15, item("PAPER", text(plugin, player, "§b§lSprache: Deutsch", "§b§lLanguage: English"), List.of(
                text(plugin, player, "§7Gilt nur für dich, nicht für andere Spieler.", "§7Applies only to you, not to other players."),
                text(plugin, player, "§eKlicken für Englisch", "§eClick for German"))));
        inventory.setItem(26, item("BARRIER", text(plugin, player, "§cSchließen", "§cClose"), List.of()));
        if (player.hasPermission("vision.admin")) {
            inventory.setItem(22, item("COMPARATOR", text(plugin, player, "§6Admin-Verwaltung", "§6Admin settings"), List.of(
                    text(plugin, player, "§7Globale Funktionen und Einstellungen bearbeiten.", "§7Edit global features and settings."))));
        }
        player.openInventory(inventory);
    }

    public void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                open(player);
            }
        }
    }

    public void shutdown() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        }
    }

    private ItemStack item(String material, String name, List<String> lore) {
        ItemStack item = XMaterial.matchXMaterial(material).map(XMaterial::parseItem)
                .orElseGet(() -> XMaterial.matchXMaterial("PAPER").map(XMaterial::parseItem).orElseThrow());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != top
                || (!event.isLeftClick() && !event.isRightClick())) {
            return;
        }
        int slot = event.getRawSlot();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != top) {
                return;
            }
            switch (slot) {
                case 11 -> {
                    DeathScoreboardManager manager = plugin.getDeathScoreboardManager();
                    manager.setVisible(player, !manager.isVisible(player));
                    open(player);
                }
                case 15 -> {
                    setLanguage(player, language(player).equals("de") ? "en" : "de");
                    open(player);
                }
                case 22 -> {
                    if (player.hasPermission("vision.admin")) {
                        plugin.openFeatureMenu(player);
                    }
                }
                case 26 -> player.closeInventory();
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @Getter
    private final class MenuHolder implements InventoryHolder {
        private final Inventory inventory;

        private MenuHolder(Player player) {
            inventory = plugin.getServer().createInventory(this, 27,
                    text(plugin, player, "§8Vision §7| §8Mein Menü", "§8Vision §7| §8My menu"));
        }
    }
}