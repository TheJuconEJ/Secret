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
        boolean enabled = plugin.getConfig().getBoolean("death_scoreboard.enabled", true);
        boolean visible = plugin.getDeathScoreboardManager().isVisible(player);
        inventory.setItem(11, item("SKELETON_SKULL", text(plugin, player, "§c§lTodesliste", "§c§lDeath leaderboard"), List.of(
                text(plugin, player, "§7Top 5, deine Tode und Gesamtsumme.", "§7Top 5, your deaths and the total."),
                visible ? text(plugin, player, "§7Sidebar: §aSichtbar", "§7Sidebar: §aVisible")
                        : text(plugin, player, "§7Sidebar: §cAusgeblendet", "§7Sidebar: §cHidden"),
                text(plugin, player, "§7Deine Auswahl wird gespeichert.", "§7Your choice is saved."),
                enabled ? (visible ? text(plugin, player, "§eKlicken zum Ausblenden", "§eClick to hide")
                        : text(plugin, player, "§eKlicken zum Einblenden", "§eClick to show"))
                        : text(plugin, player, "§6Vom Admin deaktiviert.", "§6Disabled by an admin."))));
        inventory.setItem(15, item("PAPER", text(plugin, player, "§b§lSprache: Deutsch", "§b§lLanguage: English"), List.of(
                text(plugin, player, "§7Gilt nur für dich, nicht für andere Spieler.", "§7Applies only to you, not to other players."),
                text(plugin, player, "§eKlicken für Englisch", "§eClick for German"))));
        inventory.setItem(26, item("BARRIER", text(plugin, player, "§cSchließen", "§cClose"), List.of()));
        if (player.hasPermission("vision.admin")) {
            inventory.setItem(13, item("ENDER_CHEST", text(plugin, player, "§a§lPlugin-Updates", "§a§lPlugin updates"), List.of(
                    text(plugin, player, "§7Updates suchen, Quellen zuordnen und installieren.", "§7Find updates, assign sources and install."),
                    "§e/update check | install | source",
                    text(plugin, player, "§eKlicken zum Öffnen", "§eClick to open"))));
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
                    plugin.getDeathScoreboardManager().setVisible(player,
                            !plugin.getDeathScoreboardManager().isVisible(player));
                    open(player);
                }
                case 13 -> {
                    if (player.hasPermission("vision.admin")) {
                        plugin.getPluginUpdateManager().open(player, 0);
                    }
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