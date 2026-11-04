package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XMaterial;
import de.Darkness.sekret.SekretPlugin;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class FeatureMenu implements Listener {

    private final SekretPlugin plugin;

    public FeatureMenu(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("secret.admin")) {
            player.sendMessage(text(plugin, player, "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
            return;
        }
        MenuHolder holder = new MenuHolder(player);
        render(player, holder.getInventory());
        player.openInventory(holder.getInventory());
    }

    public void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (inventory.getHolder() instanceof MenuHolder) {
                if (!player.getOpenInventory().getTitle().equals(title(player))) {
                    open(player);
                } else {
                    render(player, inventory);
                }
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

    private void render(Player player, Inventory inventory) {
        for (Feature feature : Feature.values()) {
            boolean enabled = plugin.getConfig().getBoolean(feature.getPath(), true);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + description(player, feature));
            lore.add("");
            lore.add("§7Status: " + (enabled ? text(plugin, player, "§aAN", "§aON") : text(plugin, player, "§cAUS", "§cOFF")));
            if (feature.getPath().startsWith("disease.") && !feature.getPath().equals("disease.enabled")
                    && !plugin.getConfig().getBoolean("disease.enabled", true)) {
                lore.add(text(plugin, player, "§6Krankheitssystem ist momentan aus.", "§6The disease system is currently disabled."));
            }
            lore.add(enabled ? text(plugin, player, "§eKlicken zum Ausschalten", "§eClick to disable")
                    : text(plugin, player, "§eKlicken zum Einschalten", "§eClick to enable"));
            lore.add(text(plugin, player, "§bRechtsklick: alle Einstellungen", "§bRight-click: all settings"));
            inventory.setItem(feature.getSlot(), item(feature.getIcon(),
                    (enabled ? "§a" : "§c") + name(player, feature), lore));
        }
        inventory.setItem(36, item("BOOK", text(plugin, player, "§6Secret-Verwaltung", "§6Secret Administration"), List.of(
                text(plugin, player, "§7Änderungen gelten sofort für alle Spieler.", "§7Changes apply immediately to all players."),
                text(plugin, player, "§7Einstellungen bleiben nach Neustarts erhalten.", "§7Settings persist after restarts."),
                text(plugin, player, "§7Nur Admins können dieses Menü bedienen.", "§7Only admins can use this menu."))));
        inventory.setItem(38, item("COMPARATOR", text(plugin, player, "§bAlle Einstellungen", "§bAll settings"), List.of(
                text(plugin, player, "§7Werte, Texte, Listen und Untermenüs bearbeiten.", "§7Edit values, texts, lists and submenus."),
                text(plugin, player, "§eKlicken zum Öffnen", "§eClick to open"))));
        inventory.setItem(40, item("BARRIER", text(plugin, player, "§cSchließen", "§cClose"), List.of(text(plugin, player, "§7Menü schließen.", "§7Close menu."))));
        inventory.setItem(42, item("PLAYER_HEAD", text(plugin, player, "§bMein Menü", "§bMy menu"), List.of(
                text(plugin, player, "§7Eigene Sprache und Todesanzeige einstellen.", "§7Set your own language and death display."))));
        inventory.setItem(44, item("PAPER", text(plugin, player, "§bStandardsprache: ", "§bDefault language: ")
                + plugin.getConfig().getString("language", "de"), List.of(
                text(plugin, player, "§7Für Spieler ohne persönliche Sprachauswahl.", "§7For players without a personal language preference."),
                text(plugin, player, "§eKlicken zum Umschalten", "§eClick to toggle"))));
    }

    private String title(Player player) {
        return text(plugin, player, "§8Secret §7| §8Funktionen", "§8Secret §7| §8Features");
    }

    private String name(Player player, Feature feature) {
        return text(plugin, player, feature.getName(), switch (feature) {
            case BLOW -> "Blow mechanic";
            case EXCITEMENT -> "Excitement";
            case TRIPPING -> "Tripping";
            case LEASH -> "Leash players";
            case SIT -> "Sit down";
            case DISEASE -> "Diseases";
            case SOUND -> "Scheduled music";
            case SPAWN -> "Spawn teleport";
            case DEATHS -> "Death leaderboard";
            case SPREAD -> "Disease spread";
            case HEALING -> "Healing item";
            case THIRST -> "Water / thirst";
        });
    }

    private String description(Player player, Feature feature) {
        return text(plugin, player, feature.getDescription(), switch (feature) {
            case BLOW -> "SNEAK: death mechanic near players.";
            case EXCITEMENT -> "Death mechanic after prolonged player proximity.";
            case TRIPPING -> "Random tripping while walking.";
            case LEASH -> "Lead players with a leash.";
            case SIT -> "Make leashed players sit down.";
            case DISEASE -> "Disabling pauses existing diseases.";
            case SOUND -> "Play sound.mp3 at the configured time.";
            case SPAWN -> "Enable the /spawn command.";
            case DEATHS -> "Display and death tracking; existing data is kept.";
            case SPREAD -> "Spread diseases between players.";
            case HEALING -> "Heal with the configured item.";
            case THIRST -> "Refill with water bottles, milk or positive potions.";
        });
    }

    private ItemStack item(String material, String name, List<String> lore) {
        ItemStack item = XMaterial.matchXMaterial(material).map(XMaterial::parseItem)
                .orElseGet(() -> XMaterial.matchXMaterial("PAPER").map(XMaterial::parseItem)
                        .orElseThrow(() -> new IllegalStateException("PAPER ist nicht verfügbar")));
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.hasPermission("secret.admin")) {
            player.sendMessage(text(plugin, player, "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
            return;
        }
        if (event.getClickedInventory() != top || (!event.isLeftClick() && !event.isRightClick())) {
            return;
        }
        if (event.getRawSlot() == 38) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory() == top) {
                    plugin.openSettingsMenu(player, "");
                }
            });
            return;
        }
        if (event.getRawSlot() == 44) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.hasPermission("secret.admin") && player.getOpenInventory().getTopInventory() == top) {
                    plugin.setLanguage(plugin.getConfig().getString("language", "de").equalsIgnoreCase("en") ? "de" : "en");
                    player.sendMessage(text(plugin, player, "§aStandardsprache geändert. Persönliche Sprachen bleiben erhalten.",
                            "§aDefault language changed. Personal languages are kept."));
                }
            });
            return;
        }
        if (event.getRawSlot() == 42) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory() == top) {
                    plugin.getPlayerMenu().open(player);
                }
            });
            return;
        }
        if (event.getRawSlot() == 40) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory() == top) {
                    player.closeInventory();
                }
            });
            return;
        }
        for (Feature feature : Feature.values()) {
            if (event.getRawSlot() == feature.getSlot()) {
                if (event.isRightClick()) {
                    String section = feature.getPath().substring(0, feature.getPath().lastIndexOf('.'));
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && player.getOpenInventory().getTopInventory() == top) {
                            plugin.openSettingsMenu(player, section);
                        }
                    });
                    return;
                }
                boolean enabled = !plugin.getConfig().getBoolean(feature.getPath(), true);
                plugin.setFeatureEnabled(feature.getPath(), enabled);
                player.sendMessage("§e" + name(player, feature) + (enabled ? text(plugin, player, " §aaktiviert.", " §aenabled.")
                        : text(plugin, player, " §cdeaktiviert.", " §cdisabled.")));
                return;
            }
        }
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
            inventory = plugin.getServer().createInventory(this, 45, title(player));
        }
    }

    @Getter
    @AllArgsConstructor
    public enum Feature {
        BLOW(10, "blow", "blow.enabled", "Blow-Mechanik", "SNEAK: Todesmechanik bei Spielernähe.", "FEATHER"),
        EXCITEMENT(11, "excitement", "excitement.enabled", "Erregung", "Todesmechanik bei längerer Spielernähe.", "BLAZE_POWDER"),
        TRIPPING(12, "tripping", "tripping.enabled", "Stolpern", "Zufälliges Stolpern beim Gehen.", "LEATHER_BOOTS"),
        LEASH(13, "leash", "leash.enabled", "Spieler anleinen", "Spieler mit einer Leine führen.", "LEAD"),
        SIT(14, "sit", "sit.enabled", "Hinsetzen", "Angeleinte Spieler hinsetzen.", "OAK_STAIRS"),
        DISEASE(15, "disease", "disease.enabled", "Krankheiten", "Ausschalten pausiert bestehende Krankheiten.", "SPIDER_EYE"),
        SOUND(16, "sound", "scheduled_sound.enabled", "Zeitgesteuerte Musik", "sound.mp3 zur eingestellten Uhrzeit abspielen.", "JUKEBOX"),
        SPAWN(19, "spawn", "spawn.enabled", "Spawn-Teleport", "Den Befehl /spawn freigeben.", "COMPASS"),
        DEATHS(20, "deaths", "death_scoreboard.enabled", "Todesliste", "Anzeige und Todeszählung; vorhandene Daten bleiben.", "SKELETON_SKULL"),
        SPREAD(21, "spread", "disease.spread_enabled", "Ansteckung", "Krankheiten zwischen Spielern übertragen.", "ROTTEN_FLESH"),
        HEALING(22, "healing", "disease.healing.enabled", "Heilungsitem", "Heilung mit dem eingestellten Gegenstand.", "GOLDEN_APPLE"),
        THIRST(23, "thirst", "thirst.enabled", "Wasser / Durst", "Wasserflaschen, Milch oder positive Tränke füllen auf.", "POTION");

        private final int slot;
        private final String command;
        private final String path;
        private final String name;
        private final String description;
        private final String icon;
    }
}