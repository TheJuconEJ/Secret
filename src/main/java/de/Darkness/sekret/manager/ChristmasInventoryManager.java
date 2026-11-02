package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XMaterial;
import de.Darkness.sekret.SekretPlugin;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ChristmasInventoryManager implements Listener, CommandExecutor, TabCompleter {

    private final SekretPlugin plugin;
    private boolean enabled;

    public ChristmasInventoryManager(SekretPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("christmas_inventory.enabled", false);
        if (!enabled) {
            shutdown();
        }
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ChristmasMenu) {
                player.closeInventory();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cBenutze /weihnachten <an|aus>.");
                return true;
            }
            if (!enabled) {
                player.sendMessage("§cDas Weihnachtsinventar ist momentan deaktiviert.");
                return true;
            }
            player.openInventory(new ChristmasMenu().getInventory());
            return true;
        }
        if (!sender.hasPermission("secret.admin")) {
            sender.sendMessage("§cDafür hast du keine Berechtigung.");
            return true;
        }
        if (args.length != 1 || (!args[0].equalsIgnoreCase("an") && !args[0].equalsIgnoreCase("aus"))) {
            sender.sendMessage("§cBenutze /weihnachten [an|aus].");
            return true;
        }
        boolean activate = args[0].equalsIgnoreCase("an");
        plugin.getConfig().set("christmas_inventory.enabled", activate);
        plugin.saveConfig();
        reload();
        sender.sendMessage(activate
                ? "§aDas Weihnachtsinventar ist jetzt für alle aktiviert."
                : "§cDas Weihnachtsinventar ist jetzt für alle deaktiviert.");
        if (activate) {
            Bukkit.broadcastMessage("§c§lFrohe §a§lWeihnachten! §eÖffne das Weihnachtsinventar mit §f/weihnachten§e.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("secret.admin")) {
            return Collections.emptyList();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Arrays.asList("an", "aus").stream()
                .filter(option -> option.startsWith(prefix))
                .collect(Collectors.toList());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ChristmasMenu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == 49 && event.getWhoClicked() instanceof Player player) {
            Inventory menu = event.getView().getTopInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory() == menu) {
                    player.closeInventory();
                }
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ChristmasMenu) {
            event.setCancelled(true);
        }
    }

    private static ItemStack decoration(String material, String name, String... lore) {
        ItemStack item = XMaterial.matchXMaterial(material).map(XMaterial::parseItem)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Material: " + material));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            meta.setLore(Arrays.stream(lore)
                    .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                    .collect(Collectors.toList()));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static final class ChristmasMenu implements InventoryHolder {

        @Getter
        private final Inventory inventory;

        private ChristmasMenu() {
            inventory = Bukkit.createInventory(this, 54, "§c§lFrohe §a§lWeihnachten");
            ItemStack background = decoration("WHITE_STAINED_GLASS_PANE", " ");
            ItemStack redBorder = decoration("RED_STAINED_GLASS_PANE", " ");
            ItemStack greenBorder = decoration("GREEN_STAINED_GLASS_PANE", " ");
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                boolean border = slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8;
                inventory.setItem(slot, border ? (slot % 2 == 0 ? redBorder : greenBorder) : background);
            }

            ItemStack leaves = decoration("SPRUCE_LEAVES", "&aWeihnachtsbaum");
            for (int slot : new int[]{13, 21, 22, 23, 29, 30, 31, 32, 33}) {
                inventory.setItem(slot, leaves);
            }
            inventory.setItem(4, decoration("NETHER_STAR", "&e&lWeihnachtsstern",
                    "&7Eine schöne Weihnachtszeit für alle!"));
            inventory.setItem(21, decoration("RED_STAINED_GLASS_PANE", "&cWeihnachtskugel"));
            inventory.setItem(23, decoration("YELLOW_STAINED_GLASS_PANE", "&eWeihnachtskugel"));
            inventory.setItem(31, decoration("RED_STAINED_GLASS_PANE", "&cWeihnachtskugel"));
            inventory.setItem(40, decoration("SPRUCE_LOG", "&6Baumstamm"));
            inventory.setItem(38, decoration("CHEST", "&cGeschenke", "&7Weihnachtliche Dekoration."));
            inventory.setItem(42, decoration("ENDER_CHEST", "&aGeschenke", "&7Weihnachtliche Dekoration."));
            inventory.setItem(19, decoration("COOKIE", "&6Weihnachtsplätzchen"));
            inventory.setItem(25, decoration("SNOWBALL", "&fSchneeball"));
            inventory.setItem(49, decoration("BARRIER", "&cSchließen", "&7Klicken, um das Menü zu schließen."));
        }
    }
}