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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class FeatureMenu implements Listener {

    private final SekretPlugin plugin;

    public FeatureMenu(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("vision.admin")) {
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
        ItemStack background = item("GRAY_STAINED_GLASS_PANE", " ", List.of());
        ItemStack border = item("BLUE_STAINED_GLASS_PANE", " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, slot < 9 || (slot >= 27 && slot < 36) ? border : background);
        }
        int active = 0;
        for (Feature feature : Feature.values()) {
            boolean enabled = plugin.getConfig().getBoolean(feature.getPath(), true);
            if (enabled) {
                active++;
            }
            List<String> lore = new ArrayList<>();
            lore.add("§7" + description(player, feature));
            lore.add("");
            lore.add("§7Status: " + (enabled ? text(plugin, player, "§a● Aktiviert", "§a● Enabled") : text(plugin, player, "§c● Deaktiviert", "§c● Disabled")));
            if (feature.getPath().startsWith("disease.") && !feature.getPath().equals("disease.enabled")
                    && !plugin.getConfig().getBoolean("disease.enabled", true)) {
                lore.add(text(plugin, player, "§6Krankheitssystem ist momentan aus.", "§6The disease system is currently disabled."));
            }
            lore.add("");
            lore.add(enabled ? text(plugin, player, "§eLinksklick §8» §7Ausschalten", "§eLeft-click §8» §7Disable")
                    : text(plugin, player, "§eLinksklick §8» §7Einschalten", "§eLeft-click §8» §7Enable"));
            lore.add(text(plugin, player, "§bRechtsklick §8» §7Einstellungen", "§bRight-click §8» §7Settings"));
            inventory.setItem(feature.getSlot(), item(feature.getIcon(),
                    (enabled ? "§a§l" : "§c§l") + name(player, feature), lore));
        }
        inventory.setItem(4, item("NETHER_STAR", text(plugin, player, "§b§lVision §8| §fVerwaltung", "§b§lVision §8| §fAdministration"), List.of(
                text(plugin, player, "§7Alle Serverfunktionen auf einen Blick.", "§7All server features at a glance."),
                "",
                text(plugin, player, "§7Eingeschaltet: §a", "§7Enabled: §a") + active + " §8/ §f" + Feature.values().length,
                text(plugin, player, "§7Angemeldet als: §f", "§7Signed in as: §f") + player.getName())));
        inventory.setItem(31, item("LIME_DYE", text(plugin, player, "§fBedienung & Status", "§fControls & status"), List.of(
                text(plugin, player, "§aGrün §8= §7eingeschaltet", "§aGreen §8= §7enabled"),
                text(plugin, player, "§cRot §8= §7ausgeschaltet", "§cRed §8= §7disabled"),
                "",
                text(plugin, player, "§eLinksklick §8» §7Funktion umschalten", "§eLeft-click §8» §7Toggle feature"),
                text(plugin, player, "§bRechtsklick §8» §7Werte bearbeiten", "§bRight-click §8» §7Edit values"))));
        inventory.setItem(36, item("BOOK", text(plugin, player, "§6§lHilfe", "§6§lHelp"), List.of(
                text(plugin, player, "§7Änderungen gelten sofort für alle Spieler.", "§7Changes apply immediately to all players."),
                text(plugin, player, "§7Einstellungen bleiben nach Neustarts erhalten.", "§7Settings persist after restarts."),
                text(plugin, player, "§7Nur Admins können dieses Menü bedienen.", "§7Only admins can use this menu."))));
        inventory.setItem(38, item("COMPARATOR", text(plugin, player, "§b§lAlle Einstellungen", "§b§lAll settings"), List.of(
                text(plugin, player, "§7Die vollständige Vision-Konfiguration bearbeiten.", "§7Edit the complete Vision configuration."),
                text(plugin, player, "§7Schalter, Zahlen, Texte, Listen und Spawnposition.", "§7Toggles, numbers, texts, lists and spawn location."),
                "",
                text(plugin, player, "§eKlicken zum Öffnen", "§eClick to open"))));
        inventory.setItem(37, item("WRITABLE_BOOK", text(plugin, player, "§e§lNachrichten", "§e§lMessages"), List.of(
                text(plugin, player, "§7Todesnachrichten und Anleintext bearbeiten.", "§7Edit death messages and leash text."),
                text(plugin, player, "§eKlicken zum Öffnen", "§eClick to open"))));
        inventory.setItem(39, item("BOOK", text(plugin, player, "§e§lÜbersetzungen", "§e§lTranslations"), List.of(
                text(plugin, player, "§7Englische Nachrichten und Warntexte bearbeiten.", "§7Edit English messages and warning texts."),
                text(plugin, player, "§eKlicken zum Öffnen", "§eClick to open"))));
        inventory.setItem(43, item("POTION", text(plugin, player, "§b§lDurstanzeige", "§b§lThirst display"), List.of(
                text(plugin, player, "§7Die verwendete ItemsAdder-HUD-ID einstellen.", "§7Configure the ItemsAdder HUD ID in use."),
                text(plugin, player, "§eKlicken zum Öffnen", "§eClick to open"))));
        inventory.setItem(41, item("CLOCK", text(plugin, player, "§c§lServer neustarten", "§c§lRestart server"), List.of(
                text(plugin, player, "§7Ankündigung vor dem Neustart: §f", "§7Warning before restart: §f")
                        + (plugin.getConfig().getBoolean("restart.enabled", true)
                        ? Math.max(0, plugin.getConfig().getInt("restart.delay_seconds", 30)) : 0) + " s",
                text(plugin, player, "§cShift + Linksklick: Neustart starten", "§cShift + left-click: start restart"),
                text(plugin, player, "§bRechtsklick: Einstellungen", "§bRight-click: settings"))));
        inventory.setItem(40, item("BARRIER", text(plugin, player, "§c§lSchließen", "§c§lClose"), List.of(text(plugin, player, "§7Menü schließen.", "§7Close menu."))));
        ItemStack playerHead = item("PLAYER_HEAD", text(plugin, player, "§b§lMein Menü", "§b§lMy menu"), List.of(
                "§f" + player.getName(),
                text(plugin, player, "§7Eigene Sprache und Todesanzeige einstellen.", "§7Set your own language and death display."),
                "",
                text(plugin, player, "§eKlicken zum Öffnen", "§eClick to open")));
        if (playerHead.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
            playerHead.setItemMeta(skullMeta);
        }
        inventory.setItem(42, playerHead);
        inventory.setItem(44, item("PAPER", text(plugin, player, "§b§lStandardsprache: ", "§b§lDefault language: ")
                + plugin.getConfig().getString("language", "de"), List.of(
                text(plugin, player, "§7Für Spieler ohne persönliche Sprachauswahl.", "§7For players without a personal language preference."),
                "",
                text(plugin, player, "§eKlicken zum Umschalten", "§eClick to toggle"))));
    }

    private String title(Player player) {
        return text(plugin, player, "§8Vision §7| §8Funktionen", "§8Vision §7| §8Features");
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
            case SIT -> "Sit on the ground, stairs, slabs or other players.";
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
        if (!player.hasPermission("vision.admin")) {
            player.sendMessage(text(plugin, player, "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
            return;
        }
        if (event.getClickedInventory() != top || (!event.isLeftClick() && !event.isRightClick())) {
            return;
        }
        if (event.getRawSlot() == 41) {
            boolean settings = event.isRightClick();
            if (settings || event.isShiftClick()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && player.hasPermission("vision.admin") && player.getOpenInventory().getTopInventory() == top) {
                        if (settings) {
                            plugin.openSettingsMenu(player, "restart");
                        } else {
                            player.closeInventory();
                            plugin.getRestartManager().requestMenuRestart(player);
                        }
                    }
                });
            }
            return;
        }
        String settingsSection = switch (event.getRawSlot()) {
            case 37 -> "messages";
            case 38 -> "";
            case 39 -> "translations";
            case 43 -> "thirst.itemsadder";
            default -> null;
        };
        if (settingsSection != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.hasPermission("vision.admin") && player.getOpenInventory().getTopInventory() == top) {
                    plugin.openSettingsMenu(player, settingsSection);
                }
            });
            return;
        }
        if (event.getRawSlot() == 44) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.hasPermission("vision.admin") && player.getOpenInventory().getTopInventory() == top) {
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
        SIT(14, "sit", "sit.enabled", "Hinsetzen", "Auf Boden, Treppen, Stufen oder Spielern sitzen.", "OAK_STAIRS"),
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