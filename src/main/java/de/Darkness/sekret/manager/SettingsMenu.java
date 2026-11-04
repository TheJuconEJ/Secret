package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XPotion;
import com.cryptomorin.xseries.XSound;
import com.cryptomorin.xseries.particles.XParticle;
import de.Darkness.sekret.SekretPlugin;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class SettingsMenu implements Listener {

    private static final int PAGE_SIZE = 45;
    private final SekretPlugin plugin;
    private final Map<UUID, Input> pending = new ConcurrentHashMap<>();

    public SettingsMenu(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String section, int page) {
        pending.remove(player.getUniqueId());
        if (!allowed(player)) {
            return;
        }
        List<String> entries = entries(section);
        int lastPage = Math.max(0, (entries.size() - 1) / PAGE_SIZE);
        SettingsHolder holder = new SettingsHolder(player, section, Math.max(0, Math.min(lastPage, page)), entries);
        render(player, holder);
        player.openInventory(holder.getInventory());
    }

    public void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof SettingsHolder holder) {
                open(player, holder.getSection(), holder.getPage());
            }
        }
    }

    public void shutdown() {
        pending.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof SettingsHolder) {
                player.closeInventory();
            }
        }
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("secret.admin")) {
            return true;
        }
        player.sendMessage(text(plugin, player, "§cDafür hast du keine Berechtigung.", "§cYou do not have permission."));
        return false;
    }

    private List<String> entries(String section) {
        ConfigurationSection config = section.isEmpty() ? plugin.getConfig() : plugin.getConfig().getConfigurationSection(section);
        List<String> result = new ArrayList<>();
        if (config != null) {
            for (String key : config.getKeys(false)) {
                String path = section.isEmpty() ? key : section + "." + key;
                if (!path.equals("thirst.damage_threshold")) {
                    result.add(path);
                }
            }
        }
        if (section.equals("spawn") && !result.contains("spawn.location")) {
            result.add("spawn.location");
        }
        String message = switch (section) {
            case "blow" -> "same_block";
            case "excitement" -> "excitement";
            case "tripping" -> "trip";
            case "leash" -> "leash_created";
            default -> null;
        };
        if (message != null) {
            result.add("messages." + message);
            result.add("translations.en.messages." + message);
        }
        if (section.equals("disease")) {
            result.add("translations.en.disease.death_message");
        }
        if (section.matches("disease\\.stages\\.stage_[1-4]")) {
            result.add("translations.en." + section + ".warning");
        }
        return result;
    }

    private void render(Player player, SettingsHolder holder) {
        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = holder.getPage() * PAGE_SIZE + slot;
            if (index >= holder.getEntries().size()) {
                break;
            }
            String path = holder.getEntries().get(index);
            Object value = plugin.getConfig().get(path);
            List<String> lore = new ArrayList<>();
            lore.add("§8" + path);
            lore.add("");
            String icon;
            if (path.equals("spawn.location")) {
                icon = "COMPASS";
                lore.add("§7" + display(player, value));
                lore.add(text(plugin, player, "§eKlicken: Spawn an deiner Position setzen", "§eClick: set spawn at your position"));
            } else if (value instanceof ConfigurationSection) {
                icon = "CHEST";
                lore.add(text(plugin, player, "§eKlicken: Untermenü öffnen", "§eClick: open submenu"));
            } else {
                icon = value instanceof Boolean state ? (state ? "LIME_DYE" : "RED_DYE")
                        : value instanceof Number ? "CLOCK" : value instanceof List<?> ? "WRITABLE_BOOK" : "PAPER";
                lore.add(text(plugin, player, "§7Aktuell:", "§7Current:"));
                addWrapped(lore, display(player, value));
                if (value instanceof Boolean) {
                    lore.add(text(plugin, player, "§eKlicken: umschalten", "§eClick: toggle"));
                } else {
                    lore.add(text(plugin, player, "§eLinksklick: Wert im Chat eingeben", "§eLeft-click: enter value in chat"));
                    if (value instanceof Number number) {
                        String step = step(path, number).stripTrailingZeros().toPlainString();
                        lore.add(text(plugin, player, "§bRechtsklick: +", "§bRight-click: +") + step);
                        lore.add(text(plugin, player, "§bShift + Rechtsklick: -", "§bShift + right-click: -") + step);
                    } else if (value instanceof List<?>) {
                        lore.add(text(plugin, player, "§7Einträge mit ; trennen, [] = leere Liste", "§7Separate entries with ;, [] = empty list"));
                    }
                }
            }
            inventory.setItem(slot, item(icon, "§b" + label(player, path), lore));
        }
        inventory.setItem(45, item("ARROW", text(plugin, player, "§eZurück", "§eBack"), List.of()));
        if (holder.getPage() > 0) {
            inventory.setItem(48, item("ARROW", text(plugin, player, "§eVorherige Seite", "§ePrevious page"), List.of()));
        }
        inventory.setItem(49, item("BOOK", text(plugin, player, "§6Einstellungen", "§6Settings"), List.of(
                "§7" + (holder.getSection().isEmpty() ? text(plugin, player, "Alle Funktionen", "All features") : holder.getSection()),
                text(plugin, player, "§7Seite ", "§7Page ") + (holder.getPage() + 1) + "/" + Math.max(1, (holder.getEntries().size() + PAGE_SIZE - 1) / PAGE_SIZE),
                text(plugin, player, "§7Änderungen werden sofort gespeichert.", "§7Changes are saved immediately."))));
        if ((holder.getPage() + 1) * PAGE_SIZE < holder.getEntries().size()) {
            inventory.setItem(50, item("ARROW", text(plugin, player, "§eNächste Seite", "§eNext page"), List.of()));
        }
        inventory.setItem(53, item("BARRIER", text(plugin, player, "§cFunktionsübersicht", "§cFeature overview"), List.of()));
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

    private void addWrapped(List<String> lore, String value) {
        int length = Math.min(value.length(), 360);
        for (int i = 0; i < length; i += 48) {
            lore.add("§f" + value.substring(i, Math.min(length, i + 48)));
        }
        if (value.length() > length) {
            lore.add("§7...");
        }
    }

    private String display(Player player, Object value) {
        if (value instanceof Boolean state) {
            return state ? text(plugin, player, "AN", "ON") : text(plugin, player, "AUS", "OFF");
        }
        if (value instanceof Location location) {
            return (location.getWorld() == null ? "?" : location.getWorld().getName()) + " "
                    + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "[]" : String.join("; ", list.stream().map(String::valueOf).toList());
        }
        if (value == null || value.toString().isEmpty()) {
            return text(plugin, player, "(leer / nicht gesetzt)", "(empty / not set)");
        }
        return value.toString();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SettingsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !allowed(player)
                || event.getClickedInventory() != top || (!event.isLeftClick() && !event.isRightClick())) {
            return;
        }
        int slot = event.getRawSlot();
        boolean right = event.isRightClick();
        boolean shift = event.isShiftClick();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != top || !allowed(player)) {
                return;
            }
            if (slot == 53 || (slot == 45 && holder.getSection().isEmpty())) {
                plugin.openFeatureMenu(player);
            } else if (slot == 45) {
                int dot = holder.getSection().lastIndexOf('.');
                open(player, dot < 0 ? "" : holder.getSection().substring(0, dot), 0);
            } else if (slot == 48 && holder.getPage() > 0) {
                open(player, holder.getSection(), holder.getPage() - 1);
            } else if (slot == 50 && (holder.getPage() + 1) * PAGE_SIZE < holder.getEntries().size()) {
                open(player, holder.getSection(), holder.getPage() + 1);
            } else if (slot >= 0 && slot < PAGE_SIZE) {
                int index = holder.getPage() * PAGE_SIZE + slot;
                if (index < holder.getEntries().size()) {
                    edit(player, holder, holder.getEntries().get(index), right, shift);
                }
            }
        });
    }

    private void edit(Player player, SettingsHolder holder, String path, boolean right, boolean shift) {
        Object value = plugin.getConfig().get(path);
        if (path.equals("spawn.location")) {
            Location location = player.getLocation();
            if (!player.getWorld().setSpawnLocation(location)) {
                player.sendMessage(text(plugin, player, "§cDer Spawn konnte hier nicht gesetzt werden.", "§cThe spawn could not be set here."));
                return;
            }
            plugin.getConfig().set("spawn.locations", null);
            save(player, path, location);
        } else if (value instanceof ConfigurationSection) {
            open(player, path, 0);
        } else if (value instanceof Boolean state) {
            save(player, path, !state);
        } else if (value instanceof Number number && right) {
            BigDecimal amount = step(path, number);
            BigDecimal next = new BigDecimal(number.toString()).add(shift ? amount.negate() : amount);
            try {
                save(player, path, parse(player, path, next.toPlainString(), number));
            } catch (IllegalArgumentException exception) {
                player.sendMessage("§c" + exception.getMessage());
            }
        } else if (value instanceof String || value instanceof Number || value instanceof List<?>) {
            Input input = new Input(path, holder.getSection(), holder.getPage());
            player.closeInventory();
            pending.put(player.getUniqueId(), input);
            player.sendMessage("§e" + label(player, path) + " §7(" + path + ")");
            player.sendMessage(text(plugin, player, "§7Aktuell: §f", "§7Current: §f") + display(player, value));
            player.sendMessage(text(plugin, player, "§eNeuen Wert im Chat eingeben. 'abbrechen' beendet die Eingabe.",
                    "§eEnter a new value in chat. Type 'cancel' to cancel."));
            if (value instanceof List<?>) {
                player.sendMessage(text(plugin, player, "§7Einträge mit ; trennen. [] leert die Liste.", "§7Separate entries with ;. [] clears the list."));
            } else if (value instanceof String) {
                player.sendMessage(text(plugin, player, "§7Farbcodes: &. Ein einzelnes - leert den Text.", "§7Color codes: &. A single - clears the text."));
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (pending.remove(player.getUniqueId(), input) && player.isOnline()) {
                    player.sendMessage(text(plugin, player, "§eEingabe wegen Zeitüberschreitung abgebrochen.", "§eInput cancelled because it timed out."));
                    open(player, input.getSection(), input.getPage());
                }
            }, 2400L);
        }
    }

    private BigDecimal step(String path, Number number) {
        return BigDecimal.valueOf(path.endsWith("chance") ? 0.001D : number instanceof Double || number instanceof Float ? 0.1D : 1.0D);
    }

    private void save(Player player, String path, Object value) {
        plugin.setSetting(path, value);
        player.sendMessage("§a" + label(player, path) + text(plugin, player, " gespeichert: §f", " saved: §f") + display(player, value));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Input input = pending.remove(event.getPlayer().getUniqueId());
        if (input == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !allowed(player)) {
                return;
            }
            if (message.trim().equalsIgnoreCase("abbrechen") || message.trim().equalsIgnoreCase("cancel")) {
                player.sendMessage(text(plugin, player, "§eEingabe abgebrochen.", "§eInput cancelled."));
                open(player, input.getSection(), input.getPage());
                return;
            }
            try {
                Object value = parse(player, input.getPath(), message, plugin.getConfig().get(input.getPath()));
                save(player, input.getPath(), value);
                open(player, input.getSection(), input.getPage());
            } catch (IllegalArgumentException exception) {
                player.sendMessage("§c" + exception.getMessage());
                player.sendMessage(text(plugin, player, "§eErneut eingeben oder 'abbrechen' schreiben.", "§eTry again or type 'cancel'."));
                pending.put(player.getUniqueId(), input);
            }
        });
    }

    private Object parse(Player player, String path, String input, Object current) {
        String trimmed = input.trim();
        if (current instanceof Number number) {
            double value;
            try {
                value = Double.parseDouble(trimmed.replace(',', '.'));
            } catch (NumberFormatException exception) {
                throw invalid(player, "Bitte eine Zahl eingeben.", "Please enter a number.");
            }
            double minimum = path.endsWith("seconds") || path.endsWith("ticks") || path.equals("spawn.max_uses")
                    || path.equals("thirst.accelerated_drain_multiplier") ? 1.0D : 0.0D;
            double maximum = path.endsWith("chance") ? 1.0D : Integer.MAX_VALUE;
            if (!Double.isFinite(value) || value < minimum || value > maximum) {
                throw invalid(player, "Erlaubter Zahlenbereich: " + minimum + " bis " + maximum,
                        "Allowed range: " + minimum + " to " + maximum);
            }
            if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
                if (value != Math.rint(value)) {
                    throw invalid(player, "Bitte eine ganze Zahl eingeben.", "Please enter a whole number.");
                }
                if (number instanceof Long) {
                    return (long) value;
                }
                return (int) value;
            }
            return value;
        }
        if (current instanceof List<?>) {
            List<String> values = trimmed.equals("[]") || trimmed.isEmpty() ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(trimmed.split(";", -1)));
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i).trim().toUpperCase(Locale.ROOT);
                if (value.isEmpty()) {
                    throw invalid(player, "Leere Listeneinträge sind nicht erlaubt.", "Empty list entries are not allowed.");
                }
                if (path.endsWith("biomes")) {
                    try {
                        Biome.valueOf(value);
                    } catch (IllegalArgumentException exception) {
                        throw invalid(player, "Unbekanntes Biom: " + value, "Unknown biome: " + value);
                    }
                }
                if (path.endsWith("effects") && XPotion.matchXPotion(value).isEmpty()) {
                    throw invalid(player, "Unbekannter Effekt: " + value, "Unknown effect: " + value);
                }
                values.set(i, value);
            }
            return values;
        }
        if (!(current instanceof String)) {
            throw invalid(player, "Diese Einstellung kann hier nicht bearbeitet werden.", "This setting cannot be edited here.");
        }
        String value = trimmed.equals("-") ? "" : input;
        if (path.equals("language")) {
            value = trimmed.toLowerCase(Locale.ROOT);
            if (!value.equals("de") && !value.equals("en")) {
                throw invalid(player, "Sprache muss de oder en sein.", "Language must be de or en.");
            }
        } else if (path.equals("scheduled_sound.timezone")) {
            try {
                ZoneId.of(trimmed);
                value = trimmed;
            } catch (java.time.DateTimeException exception) {
                throw invalid(player, "Ungültige Zeitzone, z. B. Europe/Berlin.", "Invalid time zone, e.g. Europe/Berlin.");
            }
        } else if (path.equals("scheduled_sound.time")) {
            try {
                LocalTime.parse(trimmed);
                value = trimmed;
            } catch (java.time.DateTimeException exception) {
                throw invalid(player, "Ungültige Uhrzeit. Format: HH:mm, z. B. 20:00.", "Invalid time. Format: HH:mm, e.g. 20:00.");
            }
        } else if (path.endsWith(".item")) {
            value = trimmed.toUpperCase(Locale.ROOT);
            if (XMaterial.matchXMaterial(value).map(XMaterial::parseItem).isEmpty()) {
                throw invalid(player, "Unbekannter Gegenstand.", "Unknown item.");
            }
        } else if (path.endsWith(".sound")) {
            value = value.trim().toUpperCase(Locale.ROOT);
            if (!value.isEmpty() && XSound.matchXSound(value).isEmpty()) {
                throw invalid(player, "Unbekannter Sound.", "Unknown sound.");
            }
        } else if (path.endsWith(".particle")) {
            value = value.trim().toUpperCase(Locale.ROOT);
            if (!value.isEmpty() && XParticle.of(value).isEmpty()) {
                throw invalid(player, "Unbekannter Partikel.", "Unknown particle.");
            }
        }
        return value;
    }

    private IllegalArgumentException invalid(Player player, String german, String english) {
        return new IllegalArgumentException(text(plugin, player, german, english));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SettingsHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private String label(Player player, String path) {
        String key = path.substring(path.lastIndexOf('.') + 1);
        String german = switch (key) {
            case "language" -> "Sprache";
            case "thirst" -> "Wasser / Durst";
            case "spawn" -> "Spawn /spawn";
            case "scheduled_sound" -> "Zeitgesteuerte Musik";
            case "death_scoreboard" -> "Todesliste /todesliste";
            case "blow" -> "Blow-Mechanik";
            case "excitement" -> "Erregung";
            case "tripping" -> "Stolpern";
            case "leash" -> "Anleinen";
            case "sit" -> "Hinsetzen /sit";
            case "disease" -> "Krankheiten";
            case "translations" -> "Übersetzungen";
            case "en" -> "Englische Texte";
            case "messages" -> "Nachrichten";
            case "enabled" -> "Aktiviert";
            case "drain_interval_seconds" -> "Wasserverlust: Intervall (Sekunden)";
            case "accelerated_drain_multiplier" -> "Wasserverlust bei Krankheit: Multiplikator";
            case "damage_interval_seconds" -> "Schadensintervall (Sekunden)";
            case "damage" -> "Schaden (2 = 1 Herz)";
            case "water_bottle_restore" -> "Auffüllen durch Wasserflaschen";
            case "milk_restore" -> "Auffüllen durch Milch";
            case "positive_potion_restore" -> "Auffüllen durch positive Tränke";
            case "max_uses" -> "Maximale Spawn-Nutzungen";
            case "window_seconds" -> "Spawn-Limit: Zeitraum (Sekunden)";
            case "allow_in_combat" -> "Spawn im Kampf erlauben";
            case "combat_tag_seconds" -> "Kampfmarkierung (Sekunden)";
            case "location" -> "Spawnposition";
            case "timezone" -> "Zeitzone";
            case "time" -> "Uhrzeit (HH:mm)";
            case "same_block" -> "Todesnachricht: Blow";
            case "trip" -> "Todesnachricht: Stolpern";
            case "leash_created" -> "Nachricht: Angeleint";
            case "distance" -> "Abstand (Blöcke)";
            case "sound" -> "Sound";
            case "particle" -> "Partikel";
            case "particle_count" -> "Partikelanzahl";
            case "required_time_seconds" -> "Benötigte Zeit (Sekunden)";
            case "check_interval_ticks" -> "Prüfintervall (Ticks, 20 = 1 Sekunde)";
            case "chance" -> "Wahrscheinlichkeit (0–1)";
            case "animation" -> "Animation";
            case "animation_delay_ticks" -> "Animationsverzögerung (Ticks)";
            case "max_distance" -> "Maximaler Abstand (Blöcke)";
            case "owner_only_release" -> "Nur Besitzer darf losleinen";
            case "infection_chance" -> "Ansteckungswahrscheinlichkeit (0–1)";
            case "infection_check_seconds" -> "Ansteckungsprüfung (Sekunden)";
            case "infection_biomes" -> "Ansteckungsbiome";
            case "spread_enabled" -> "Ansteckung zwischen Spielern";
            case "spread_distance" -> "Ansteckungsabstand (Blöcke)";
            case "spread_chance" -> "Übertragungswahrscheinlichkeit (0–1)";
            case "data_save_interval_seconds" -> "Speicherintervall (Sekunden)";
            case "death_message" -> "Todesnachricht: Krankheit";
            case "stages" -> "Krankheitsstufen";
            case "stage_1", "stage_2", "stage_3", "stage_4" -> "Krankheitsstufe " + key.charAt(6);
            case "stage_1_seconds", "stage_2_seconds", "stage_3_seconds", "stage_4_seconds" -> "Dauer Stufe " + key.charAt(6) + " (Sekunden)";
            case "warning_interval_seconds" -> "Warnungsintervall (Sekunden)";
            case "warning" -> "Warntext";
            case "effects" -> "Trankeffekte";
            case "healing" -> "Heilung";
            case "item" -> "Heilungsgegenstand";
            case "acceleration" -> "Krankheitsbeschleunigung";
            case "sprinting" -> "Beim Sprinten";
            case "jumping" -> "Beim Springen";
            case "fighting" -> "Beim Kämpfen";
            case "hunger" -> "Bei Hunger";
            case "biomes" -> "Beschleunigende Biome";
            case "seconds_per_activity" -> "Beschleunigung je Aktivität (Sekunden)";
            default -> key.replace('_', ' ');
        };
        String english = switch (key) {
            case "thirst" -> "Water / thirst";
            case "death_scoreboard" -> "Death leaderboard /todesliste";
            case "sit" -> "Sit down /sit";
            case "chance", "infection_chance", "spread_chance" -> key.replace('_', ' ') + " (0–1)";
            case "damage" -> "Damage (2 = 1 heart)";
            case "check_interval_ticks" -> "Check interval (ticks, 20 = 1 second)";
            default -> key.replace('_', ' ');
        };
        String result = text(plugin, player, german, english);
        if (path.startsWith("translations.en.")) {
            result += " [EN]";
        }
        return result;
    }

    @Getter
    private final class SettingsHolder implements InventoryHolder {
        private final String section;
        private final int page;
        private final List<String> entries;
        private final Inventory inventory;

        private SettingsHolder(Player player, String section, int page, List<String> entries) {
            this.section = section;
            this.page = page;
            this.entries = entries;
            this.inventory = plugin.getServer().createInventory(this, 54, text(plugin, player, "§8Secret | Einstellungen", "§8Secret | Settings"));
        }
    }

    @Getter
    @AllArgsConstructor
    private static final class Input {
        private final String path;
        private final String section;
        private final int page;
    }
}