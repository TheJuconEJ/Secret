package de.Darkness.sekret.manager;

import com.cryptomorin.xseries.XMaterial;
import de.Darkness.sekret.SekretPlugin;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static de.Darkness.sekret.util.PluginMessages.text;

public final class PluginUpdateManager implements Listener {

    private static final long MAX_JAR_BYTES = 128L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("(?<![0-9])([0-9]+(?:\\.[0-9]+){1,3})(?![0-9])");
    private final SekretPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private List<Row> rows = List.of();
    private Instant checkedAt;
    private boolean busy;
    private volatile boolean closed;

    public PluginUpdateManager(SekretPlugin plugin) {
        this.plugin = plugin;
    }

    public void handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vision.admin")) {
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                open(player, 0);
            } else {
                check(sender);
            }
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("check")) {
            check(sender);
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("source")) {
            if (busy) {
                sender.sendMessage(text(plugin, sender, "§eBitte warte auf den laufenden Vorgang.", "§ePlease wait for the current operation."));
                return;
            }
            Plugin target = plugin.getServer().getPluginManager().getPlugin(args[1]);
            if (target == null || target == plugin) {
                sender.sendMessage(text(plugin, sender, "§cUnbekanntes Plugin. Vision wird über Kodari aktualisiert.", "§cUnknown plugin. Update Vision through Kodari."));
                return;
            }
            boolean automatic = args[2].equalsIgnoreCase("auto");
            String project = args[2].equalsIgnoreCase("none") || automatic ? "" : projectId(args[2]);
            if (project == null) {
                sender.sendMessage(text(plugin, sender, "§cGib eine Modrinth-ID, einen Modrinth-/Spigot-Link oder spigot:<ID> an.", "§cSpecify a Modrinth ID, a Modrinth/Spigot URL or spigot:<ID>."));
                return;
            }
            plugin.getConfig().set("plugin_updates.projects." + target.getName(), automatic ? null : project);
            plugin.saveConfig();
            rows = List.of();
            checkedAt = null;
            sender.sendMessage("§a" + target.getName() + " §8→ §f" + (automatic ? "auto" : project.isEmpty() ? "—" : sourceLink(project)));
            sender.sendMessage(text(plugin, sender, "§7Danach /update check. Namenstreffer vor der Zuordnung selbst prüfen.", "§7Then run /update check. Verify name matches before assigning them."));
            refreshMenus();
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("install")) {
            if (args.length == 3 && args[2].equalsIgnoreCase("confirm")) {
                install(sender, args[1]);
            } else {
                sender.sendMessage(text(plugin, sender,
                        "§eUpdates sind ausführbarer Fremdcode. Prüfe die Quellen und sichere deinen Server. Keine Garantie für Hybrid-Kompatibilität.",
                        "§eUpdates execute third-party code. Verify sources and back up your server. Hybrid compatibility is not guaranteed."));
                sender.sendMessage("§e/update install " + args[1] + " confirm");
            }
            return;
        }
        sender.sendMessage("§e/update [check]");
        sender.sendMessage("§e/update source <Plugin> <Modrinth-ID|Spigot-URL|auto|none>");
        sender.sendMessage("§e/update install <Plugin|all> confirm");
    }

    public List<String> complete(String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            choices.addAll(List.of("check", "source", "install"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("source") || args[0].equalsIgnoreCase("install"))) {
            if (args[0].equalsIgnoreCase("install")) {
                choices.add("all");
            }
            for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
                if (installed != plugin) {
                    choices.add(installed.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("install")) {
            choices.add("confirm");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("source")) {
            choices.addAll(List.of("auto", "none"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    public void open(Player player, int page) {
        if (!player.hasPermission("vision.admin")) {
            return;
        }
        if (rows.isEmpty() && !busy) {
            rows = snapshot();
        }
        int lastPage = Math.max(0, (rows.size() - 1) / 45);
        MenuHolder holder = new MenuHolder(player, Math.max(0, Math.min(page, lastPage)), null);
        render(player, holder);
        player.openInventory(holder.inventory);
    }

    private void render(Player player, MenuHolder holder) {
        holder.inventory.clear();
        for (int index = 0; index < 45 && holder.page * 45 + index < rows.size(); index++) {
            Row row = rows.get(holder.page * 45 + index);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + row.currentVersion + (row.update == null ? "" : " §8→ §a" + row.update.version));
            lore.add(text(plugin, player, "§7Quelle: §f", "§7Source: §f") + (row.source.isEmpty() ? "—" : sourceLink(row.source)));
            lore.add(text(plugin, player, "§7Status: §f", "§7Status: §f") + status(player, row.status));
            for (String suggestion : row.suggestions) {
                lore.add("§e" + sourceLink(suggestion));
            }
            lore.add("");
            lore.add(text(plugin, player, "§eKlick: Update mit Bestätigung vorbereiten", "§eClick: stage update after confirmation"));
            lore.add(text(plugin, player, "§7Quelle zuordnen: /update source", "§7Assign source: /update source"));
            holder.inventory.setItem(index, item(row.update != null ? "LIME_DYE" : "PAPER", "§b" + row.name, lore));
        }
        holder.inventory.setItem(45, item("ARROW", "§e←", List.of()));
        holder.inventory.setItem(46, item("COMPASS", text(plugin, player, "§bUpdates prüfen", "§bCheck updates"), List.of(
                busy ? text(plugin, player, "§eVorgang läuft …", "§eOperation in progress …") : text(plugin, player, "§7Quellen automatisch suchen und Updates prüfen.", "§7Find sources automatically and check updates."))));
        holder.inventory.setItem(48, item("CHEST", text(plugin, player, "§aAlle verfügbaren Updates", "§aAll available updates"), List.of(
                text(plugin, player, "§7Download erst nach Bestätigung.", "§7Downloads require confirmation."),
                text(plugin, player, "§7JAR-Sicherung; Installation beim Neustart.", "§7JAR backup; installation on restart."))));
        holder.inventory.setItem(49, item("BARRIER", text(plugin, player, "§cZurück", "§cBack"), List.of()));
        holder.inventory.setItem(50, item("BOOK", text(plugin, player, "§6Wichtige Hinweise", "§6Important information"), List.of(
                text(plugin, player, "§7Nur Bukkit-/Spigot-Plugins, keine Forge-Mods.", "§7Bukkit/Spigot plugins only, not Forge mods."),
                text(plugin, player, "§7Premium-Plugins und fehlende Quellen: manuell.", "§7Premium plugins and missing sources: manual."),
                text(plugin, player, "§7Externe Spigot-Downloads: manuell.", "§7External Spigot downloads: manual."),
                text(plugin, player, "§7Spigot/Spiget liefert keine SHA-512-Prüfsumme.", "§7Spigot/Spiget provides no SHA-512 checksum."),
                text(plugin, player, "§7Arclight-Kompatibilität vorher prüfen.", "§7Check Arclight compatibility first."),
                "§e/update source <Plugin> <URL>")));
        holder.inventory.setItem(53, item("ARROW", "§e→", List.of()));
    }

    private List<Row> snapshot() {
        List<Row> snapshot = new ArrayList<>();
        for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
            String source = plugin.getConfig().getString("plugin_updates.projects." + installed.getName());
            boolean automatic = source == null;
            if (source == null) {
                source = projectId(installed.getDescription().getWebsite());
            }
            if (source == null) {
                source = "";
            } else if (!source.isEmpty()) {
                String parsed = projectId(source);
                source = parsed == null ? "" : parsed;
            }
            Path jar = null;
            try {
                URI location = installed.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
                if ("file".equalsIgnoreCase(location.getScheme())) {
                    Path candidate = Path.of(location).toRealPath();
                    Path directory = plugin.getDataFolder().getParentFile().toPath().toRealPath();
                    if (Files.isRegularFile(candidate) && candidate.getParent().equals(directory)
                            && candidate.getFileName().toString().endsWith(".jar")) {
                        jar = candidate;
                    }
                }
            } catch (Exception ignored) {
            }
            String status = installed == plugin ? "self" : jar == null ? "nojar"
                    : source.isEmpty() ? automatic ? "discover" : "disabled" : "unchecked";
            snapshot.add(new Row(installed.getName(), installed.getDescription().getVersion(),
                    installed.getDescription().getMain(), jar, source, status));
        }
        snapshot.sort(Comparator.comparing(row -> row.name, String.CASE_INSENSITIVE_ORDER));
        return snapshot;
    }

    private void check(CommandSender sender) {
        if (busy) {
            sender.sendMessage(text(plugin, sender, "§eEin Update-Vorgang läuft bereits.", "§eAn update operation is already running."));
            return;
        }
        List<Row> snapshot = snapshot();
        String minecraft = plugin.getServer().getBukkitVersion().split("-", 2)[0];
        String serverName = plugin.getServer().getName().toLowerCase(Locale.ROOT);
        List<String> loaders = new ArrayList<>(List.of("bukkit", "spigot"));
        if (serverName.contains("paper") || serverName.contains("purpur")) {
            loaders.addAll(List.of("paper", "purpur"));
        }
        busy = true;
        sender.sendMessage(text(plugin, sender, "§ePlugin-Updates werden geprüft …", "§eChecking plugin updates …"));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Row row : snapshot) {
                if (closed) {
                    return;
                }
                if (!row.status.equals("unchecked") && !row.status.equals("discover")) {
                    continue;
                }
                try {
                    if (row.status.equals("discover")) {
                        discover(row, loaders);
                    }
                    if (row.status.equals("unchecked")) {
                        findUpdate(row, minecraft, loaders);
                    }
                } catch (Exception exception) {
                    row.status = "error";
                    plugin.getLogger().warning("Update-Prüfung " + row.name + ": " + exception.getMessage());
                }
            }
            sync(() -> {
                rows = snapshot;
                checkedAt = Instant.now();
                busy = false;
                long count = rows.stream().filter(row -> row.update != null).count();
                sender.sendMessage(text(plugin, sender, "§aPrüfung beendet. Verfügbare Updates: §f", "§aCheck complete. Available updates: §f") + count);
                for (Row row : rows) {
                    sender.sendMessage("§7" + row.name + " §8» §f" + status(sender, row.status)
                            + (row.update == null ? "" : " §a" + row.currentVersion + " → " + row.update.version));
                    if (!row.source.isEmpty()) {
                        sender.sendMessage("§7  " + sourceLink(row.source));
                    }
                    for (String suggestion : row.suggestions) {
                        sender.sendMessage("§e  /update source " + row.name + " " + sourceLink(suggestion));
                    }
                }
                refreshMenus();
            });
        });
        refreshMenus();
    }

    private void findUpdate(Row row, String minecraft, List<String> loaders) throws Exception {
        if (row.source.startsWith("spigot:")) {
            findSpigotUpdate(row, minecraft);
            return;
        }
        String loaderJson = loaders.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(",", "[", "]"));
        String url = "https://api.modrinth.com/v2/project/" + row.source + "/version?include_changelog=false&game_versions="
                + encode("[\"" + minecraft + "\"]") + "&loaders=" + encode(loaderJson);
        HttpResponse<InputStream> response = http.send(request(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        String json;
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Modrinth HTTP " + response.statusCode());
            }
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) {
                throw new IOException("Modrinth-Antwort zu groß");
            }
            json = new String(bytes, StandardCharsets.UTF_8);
        }
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString("{\"versions\":" + json + "}");
        List<ConfigurationSection> versions = new ArrayList<>();
        for (Map<?, ?> map : parsed.getMapList("versions")) {
            ConfigurationSection version = section(map);
            if (version.getString("version_type", "").equals("release")
                    && version.getStringList("game_versions").contains(minecraft)
                    && version.getStringList("loaders").stream().anyMatch(loaders::contains)) {
                versions.add(version);
            }
        }
        versions.sort(Comparator.comparing((ConfigurationSection value) -> value.getString("date_published", "")).reversed());
        if (versions.isEmpty()) {
            row.status = "incompatible";
            return;
        }
        ConfigurationSection latest = versions.getFirst();
        String version = latest.getString("version_number", "");
        Integer comparison = compareVersions(version, row.currentVersion);
        if (comparison == null) {
            row.status = "version";
            return;
        }
        if (comparison <= 0) {
            row.status = "current";
            return;
        }
        List<ConfigurationSection> files = new ArrayList<>();
        for (Map<?, ?> map : latest.getMapList("files")) {
            ConfigurationSection file = section(map);
            if (file.getString("filename", "").endsWith(".jar") && file.getString("file_type", "").isEmpty()) {
                files.add(file);
            }
        }
        ConfigurationSection file = files.stream().filter(value -> value.getBoolean("primary", false)).findFirst()
                .orElse(files.size() == 1 ? files.getFirst() : null);
        if (file == null || !file.getString("hashes.sha512", "").matches("[a-fA-F0-9]{128}")) {
            row.status = "file";
            return;
        }
        URI download = URI.create(file.getString("url", ""));
        if (!isOfficialDownload(download)) {
            row.status = "file";
            return;
        }
        row.update = new Update(version, download, file.getString("hashes.sha512"), file.getLong("size", -1));
        row.status = "available";
    }

    private void discover(Row row, List<String> loaders) throws Exception {
        List<String> errors = new ArrayList<>();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream input = Files.newInputStream(row.jar)) {
                byte[] buffer = new byte[16384];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Abgebrochen");
                    }
                    digest.update(buffer, 0, length);
                }
            }
            ConfigurationSection match = api(URI.create("https://api.modrinth.com/v2/version_file/"
                    + HexFormat.of().formatHex(digest.digest()) + "?algorithm=sha512"), true);
            if (match != null && match.getStringList("loaders").stream().anyMatch(loaders::contains)) {
                String project = match.getString("project_id", "");
                if (project.matches("[A-Za-z0-9_-]{1,100}")) {
                    row.source = project;
                    row.status = "unchecked";
                    return;
                }
            }
        } catch (Exception exception) {
            errors.add("Modrinth-Dateiabgleich: " + exception.getMessage());
        }
        try {
            String facets = loaders.stream().map(value -> "\"categories:" + value + "\"")
                    .collect(Collectors.joining(",", "[[", "]]"));
            ConfigurationSection result = api(URI.create("https://api.modrinth.com/v2/search?limit=20&query="
                    + encode(row.name) + "&facets=" + encode(facets)), false);
            for (Map<?, ?> map : result.getMapList("hits")) {
                ConfigurationSection hit = section(map);
                if (sameName(row.name, hit.getString("title", ""))) {
                    addSuggestion(row, projectId(hit.getString("project_id", "")));
                }
            }
        } catch (Exception exception) {
            errors.add("Modrinth-Suche: " + exception.getMessage());
        }
        try {
            ConfigurationSection result = api(URI.create("https://api.spiget.org/v2/search/resources/"
                    + encode(row.name).replace("+", "%20") + "?field=name&size=20"), true);
            if (result != null) {
                for (Map<?, ?> map : result.getMapList("entries")) {
                    ConfigurationSection hit = section(map);
                    if (sameName(row.name, hit.getString("name", ""))) {
                        String id = hit.getString("id", "");
                        if (id.matches("[0-9]{1,12}")) {
                            addSuggestion(row, "spigot:" + id);
                        }
                    }
                }
            }
        } catch (Exception exception) {
            errors.add("Spigot-Suche: " + exception.getMessage());
        }
        row.status = row.suggestions.isEmpty() ? errors.isEmpty() ? "source" : "searcherror" : "review";
        if (!errors.isEmpty()) {
            plugin.getLogger().warning("Quellensuche " + row.name + ": " + String.join("; ", errors));
        }
    }

    private static void addSuggestion(Row row, String source) {
        if (source != null && !row.suggestions.contains(source) && row.suggestions.size() < 8) {
            row.suggestions.add(source);
        }
    }

    private static boolean sameName(String installed, String title) {
        String first = title.split("\\s+[|–—-]\\s+|\\[", 2)[0];
        String expected = installed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return !expected.isEmpty() && expected.equals(first.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }

    private void findSpigotUpdate(Row row, String minecraft) throws Exception {
        String id = row.source.substring("spigot:".length());
        String base = "https://api.spiget.org/v2/resources/" + id;
        ConfigurationSection resource = api(URI.create(base), false);
        if (resource.getBoolean("premium", false)) {
            row.status = "premium";
            return;
        }
        if (resource.getBoolean("external", false) || resource.getBoolean("file.external", false)) {
            row.status = "external";
            return;
        }
        String family = minecraft.replaceFirst("^(\\d+\\.\\d+)\\..*$", "$1");
        if (resource.getStringList("testedVersions").stream().noneMatch(value -> value.equals(minecraft) || value.equals(family))) {
            row.status = "incompatible";
            return;
        }
        ConfigurationSection latest = api(URI.create(base + "/versions/latest"), false);
        String version = latest.getString("name", "");
        if (Pattern.compile("(?i)(alpha|beta|snapshot|pre[- .]?release|dev|(?:^|[^a-z])rc[0-9]*)").matcher(version).find()) {
            row.status = "unstable";
            return;
        }
        Integer comparison = compareVersions(version, row.currentVersion);
        if (comparison == null) {
            row.status = "version";
        } else if (comparison <= 0) {
            row.status = "current";
        } else if (!resource.getString("file.type", "").equalsIgnoreCase(".jar")) {
            row.status = "file";
        } else {
            row.update = new Update(version, URI.create(base + "/download"), "", -1);
            row.status = "available";
        }
    }

    private ConfigurationSection api(URI uri, boolean allowMissing) throws Exception {
        HttpResponse<InputStream> response = http.send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (allowMissing && response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IOException(uri.getHost() + " HTTP " + response.statusCode());
            }
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) {
                throw new IOException("API-Antwort zu groß");
            }
            String json = new String(bytes, StandardCharsets.UTF_8).strip();
            YamlConfiguration parsed = new YamlConfiguration();
            parsed.loadFromString(json.startsWith("[") ? "{\"entries\":" + json + "}" : json);
            return parsed;
        }
    }

    private void install(CommandSender sender, String target) {
        if (!sender.hasPermission("vision.updates.install")) {
            sender.sendMessage(text(plugin, sender, "§cBenötigt zusätzlich vision.updates.install (standardmäßig nur OP).", "§cAlso requires vision.updates.install (OP by default)."));
            return;
        }
        if (busy || checkedAt == null || Duration.between(checkedAt, Instant.now()).toMinutes() >= 10) {
            sender.sendMessage(text(plugin, sender, "§eZuerst /update check ausführen und abwarten.", "§eFirst run /update check and wait for it to finish."));
            return;
        }
        List<Row> selected = rows.stream().filter(row -> row.update != null && row.status.equals("available")
                && (target.equalsIgnoreCase("all") || target.equalsIgnoreCase(row.name))).toList();
        if (selected.isEmpty()) {
            sender.sendMessage(text(plugin, sender, "§eKeine passenden Updates verfügbar.", "§eNo matching updates available."));
            return;
        }
        Path directory = plugin.getDataFolder().getParentFile().toPath().toAbsolutePath().normalize();
        Path updateFolder = plugin.getServer().getUpdateFolderFile().toPath().toAbsolutePath().normalize();
        Set<String> installedNames = rows.stream().map(row -> row.name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String minecraft = plugin.getServer().getBukkitVersion().split("-", 2)[0];
        busy = true;
        sender.sendMessage(text(plugin, sender, "§eUpdates werden geprüft, gesichert und heruntergeladen …", "§eValidating, backing up and downloading updates …"));
        if (selected.stream().anyMatch(row -> row.source.startsWith("spigot:"))) {
            sender.sendMessage(text(plugin, sender,
                    "§eSpigot/Spiget: keine veröffentlichte SHA-512-Prüfsumme. HTTPS-Download und Plugin-Identitätsprüfung bleiben aktiv.",
                    "§eSpigot/Spiget: no published SHA-512 checksum. HTTPS download and plugin identity validation remain active."));
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> successful = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (Row row : selected) {
                if (closed) {
                    return;
                }
                try {
                    stage(row, directory, updateFolder, installedNames, minecraft);
                    successful.add(row.name);
                } catch (Exception exception) {
                    failures.add(row.name + ": " + exception.getMessage());
                    plugin.getLogger().warning("Update " + row.name + " nicht vorbereitet: " + exception.getMessage());
                }
            }
            sync(() -> {
                for (Row row : selected) {
                    if (successful.contains(row.name)) {
                        row.status = "staged";
                    }
                }
                busy = false;
                sender.sendMessage(text(plugin, sender, "§aVorbereitet: §f", "§aStaged: §f") + successful.size());
                for (String failure : failures) {
                    sender.sendMessage("§c" + failure);
                }
                if (!successful.isEmpty()) {
                    sender.sendMessage("§a" + String.join(", ", successful));
                    sender.sendMessage(text(plugin, sender,
                            "§eServer vollständig neu starten. Keine /reload- oder PlugMan-Updates! JAR-Sicherungen: plugins/Vision/plugin-backups/",
                            "§eFully restart the server. Do not use /reload or PlugMan! JAR backups: plugins/Vision/plugin-backups/"));
                }
                refreshMenus();
            });
        });
        refreshMenus();
    }

    private void stage(Row row, Path directory, Path updateFolder, Set<String> installedNames, String minecraft) throws Exception {
        Path realDirectory = directory.toRealPath();
        if (row.jar == null || !row.jar.toRealPath().getParent().equals(realDirectory)) {
            throw new IOException("Keine originale Plugin-JAR gefunden");
        }
        Files.createDirectories(updateFolder);
        Path realUpdates = updateFolder.toRealPath();
        if (realUpdates.equals(realDirectory) || !realUpdates.startsWith(realDirectory)) {
            throw new IOException("Unsicherer oder externer Bukkit-Updateordner");
        }
        Path destination = realUpdates.resolve(row.jar.getFileName());
        if (Files.exists(destination)) {
            throw new IOException("Update bereits vorhanden; zuerst neu starten oder die vorbereitete JAR manuell prüfen");
        }
        boolean spigot = row.source.startsWith("spigot:");
        if (row.update.size > MAX_JAR_BYTES || (!spigot && row.update.size <= 0)
                || !(spigot ? isSpigotDownload(row.update.url) : isOfficialDownload(row.update.url))) {
            throw new IOException("Ungültige Downloadgröße oder Quelle");
        }
        Path temporary = Files.createTempFile(realUpdates, "vision-download-", ".tmp");
        try {
            HttpResponse<InputStream> response = download(row.update.url, spigot);
            MessageDigest hash = MessageDigest.getInstance("SHA-512");
            long count = 0;
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(temporary)) {
                if (response.statusCode() != 200) {
                    throw new IOException("Download HTTP " + response.statusCode());
                }
                byte[] buffer = new byte[16384];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Abgebrochen");
                    }
                    count += length;
                    if (count > MAX_JAR_BYTES || (row.update.size > 0 && count > row.update.size)) {
                        throw new IOException("Download zu groß");
                    }
                    hash.update(buffer, 0, length);
                    output.write(buffer, 0, length);
                }
            }
            if (count == 0 || (!spigot && (count != row.update.size
                    || !HexFormat.of().formatHex(hash.digest()).equalsIgnoreCase(row.update.hash)))) {
                throw new IOException("Größe oder SHA-512 stimmt nicht");
            }
            validateJar(temporary, row, installedNames, minecraft);
            Path backups = plugin.getDataFolder().toPath().resolve("plugin-backups");
            Files.createDirectories(backups);
            Path backup = backups.resolve(System.currentTimeMillis() + "-" + row.jar.getFileName());
            Files.copy(row.jar, backup);
            if (closed) {
                throw new IOException("Abgebrochen");
            }
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private HttpResponse<InputStream> download(URI initial, boolean spigot) throws Exception {
        URI uri = initial;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!(spigot ? isSpigotDownload(uri) : isOfficialDownload(uri))) {
                throw new IOException("Externe Downloadquelle; bitte manuell aktualisieren");
            }
            HttpResponse<InputStream> response = http.send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                return response;
            }
            try (InputStream ignored = response.body()) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Download-Weiterleitung ohne Ziel"));
                uri = uri.resolve(location);
            }
        }
        throw new IOException("Zu viele Download-Weiterleitungen");
    }

    private void validateJar(Path jar, Row row, Set<String> installedNames, String minecraft) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("plugin.yml");
            if (entry == null || entry.getSize() > 256 * 1024) {
                throw new IOException("Keine gültige Bukkit-plugin.yml; kein Plugin-Update");
            }
            YamlConfiguration descriptor = new YamlConfiguration();
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(256 * 1024 + 1);
                if (bytes.length > 256 * 1024) {
                    throw new IOException("plugin.yml zu groß");
                }
                descriptor.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            }
            if (!row.name.equalsIgnoreCase(descriptor.getString("name", ""))
                    || !row.main.equals(descriptor.getString("main", ""))) {
                throw new IOException("Plugin-Name oder Hauptklasse stimmt nicht; Update manuell prüfen");
            }
            Integer version = compareVersions(descriptor.getString("version", ""), row.currentVersion);
            if (version == null || version <= 0) {
                throw new IOException("JAR enthält keine eindeutig neuere Version");
            }
            Integer expected = compareVersions(descriptor.getString("version", ""), row.update.version);
            if (expected == null || expected != 0) {
                throw new IOException("JAR-Version entspricht nicht dem geprüften Update; erneut prüfen oder manuell aktualisieren");
            }
            String requiredApi = descriptor.getString("api-version", "");
            Integer api = requiredApi.isEmpty() ? 0 : compareVersions(requiredApi, minecraft);
            if (api == null || api > 0) {
                throw new IOException("Plugin benötigt eine neuere oder unbekannte Minecraft-API");
            }
            for (String dependency : descriptor.getStringList("depend")) {
                if (!installedNames.contains(dependency.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Fehlende Abhängigkeit: " + dependency);
                }
            }
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Vision/" + plugin.getDescription().getVersion() + " (Bukkit plugin updater)");
    }

    private static ConfigurationSection section(Map<?, ?> map) {
        return new YamlConfiguration().createSection("entry", map);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isOfficialDownload(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "cdn.modrinth.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static boolean isSpigotDownload(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && Set.of("api.spiget.org", "cdn.spiget.org", "spigotmc.org", "www.spigotmc.org").contains(host.toLowerCase(Locale.ROOT))
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static String sourceLink(String source) {
        return source.startsWith("spigot:") ? "https://www.spigotmc.org/resources/" + source.substring(7) + "/"
                : "https://modrinth.com/plugin/" + source;
    }

    private static String projectId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        input = input.trim();
        if (input.matches("(?i)spigot:[0-9]{1,12}")) {
            return "spigot:" + input.substring(7);
        }
        if (input.matches("(?i)modrinth:[A-Za-z0-9_-]{1,100}")) {
            return input.substring(9);
        }
        if (input.matches("[A-Za-z0-9_-]{1,100}")) {
            return input;
        }
        try {
            URI uri = URI.create(input);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("spigotmc.org") || host.equals("www.spigotmc.org")) {
                Matcher resource = Pattern.compile("^/resources/(?:[^/]*\\.)?([0-9]{1,12})(?:/.*)?$").matcher(uri.getPath());
                return resource.matches() ? "spigot:" + resource.group(1) : null;
            }
            if (!host.equals("modrinth.com") && !host.equals("www.modrinth.com")) {
                return null;
            }
            String[] parts = uri.getPath().split("/");
            if (parts.length >= 3 && (parts[1].equals("plugin") || parts[1].equals("project"))
                    && parts[2].matches("[A-Za-z0-9_-]{1,100}")) {
                return parts[2];
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static Integer compareVersions(String candidate, String current) {
        Matcher a = VERSION.matcher(candidate);
        Matcher b = VERSION.matcher(current);
        if (!a.find() || !b.find()) {
            return null;
        }
        String[] first = a.group(1).split("\\.");
        String[] second = b.group(1).split("\\.");
        try {
            for (int index = 0; index < Math.max(first.length, second.length); index++) {
                long left = index < first.length ? Long.parseLong(first[index]) : 0;
                long right = index < second.length ? Long.parseLong(second[index]) : 0;
                int comparison = Long.compare(left, right);
                if (comparison != 0) {
                    return comparison;
                }
            }
        } catch (NumberFormatException exception) {
            return null;
        }
        return 0;
    }

    private String status(CommandSender viewer, String status) {
        return switch (status) {
            case "self" -> text(plugin, viewer, "Vision über Kodari aktualisieren", "Update Vision through Kodari");
            case "nojar" -> text(plugin, viewer, "Original-JAR nicht gefunden; manuell", "Original JAR not found; manual update");
            case "source" -> text(plugin, viewer, "Keine eindeutige Quelle gefunden; manuell zuordnen", "No unambiguous source found; assign manually");
            case "discover" -> text(plugin, viewer, "Automatische Quellensuche bei der Prüfung", "Automatic source discovery during check");
            case "disabled" -> text(plugin, viewer, "Quellensuche aus; mit source <Plugin> auto aktivieren", "Discovery disabled; enable with source <Plugin> auto");
            case "review" -> text(plugin, viewer, "Quellenvorschläge gefunden; Link prüfen und zuordnen", "Source suggestions found; verify and assign a URL");
            case "searcherror" -> text(plugin, viewer, "Quellensuche teilweise fehlgeschlagen; siehe Konsole", "Source discovery partially failed; see console");
            case "premium" -> text(plugin, viewer, "Premium-Plugin; manuell herunterladen", "Premium plugin; download manually");
            case "external" -> text(plugin, viewer, "Externer Download; manuell herunterladen", "External download; download manually");
            case "unstable" -> text(plugin, viewer, "Neueste Spigot-Version ist keine stabile Veröffentlichung", "Latest Spigot version is not a stable release");
            case "unchecked" -> text(plugin, viewer, "Noch nicht geprüft", "Not checked yet");
            case "incompatible" -> text(plugin, viewer, "Keine passende stabile Bukkit-/Spigot-Version", "No matching stable Bukkit/Spigot version");
            case "version" -> text(plugin, viewer, "Versionsschema manuell prüfen", "Check version scheme manually");
            case "file" -> text(plugin, viewer, "Keine eindeutige geprüfte JAR", "No unambiguous verified JAR");
            case "current" -> text(plugin, viewer, "Keine eindeutig neuere Version", "No unambiguously newer version");
            case "available" -> text(plugin, viewer, "Update verfügbar", "Update available");
            case "staged" -> text(plugin, viewer, "Vorbereitet; Neustart erforderlich", "Staged; restart required");
            default -> text(plugin, viewer, "Prüfung fehlgeschlagen; siehe Konsole", "Check failed; see console");
        };
    }

    private void confirm(Player player, String target) {
        MenuHolder holder = new MenuHolder(player, 0, target);
        holder.inventory.setItem(11, item("LIME_DYE", text(plugin, player, "§aDownload bestätigen", "§aConfirm download"), List.of(
                "§f" + target,
                text(plugin, player, "§7Fremdcode aus Modrinth oder Spigot/Spiget.", "§7Third-party code from Modrinth or Spigot/Spiget."),
                text(plugin, player, "§eSpigot: ohne veröffentlichte SHA-512-Prüfsumme.", "§eSpigot: no published SHA-512 checksum."),
                text(plugin, player, "§7Hybrid-Kompatibilität selbst prüfen.", "§7Verify hybrid compatibility yourself."),
                text(plugin, player, "§7Installation erst nach vollständigem Neustart.", "§7Installation only after a full restart."))));
        holder.inventory.setItem(15, item("BARRIER", text(plugin, player, "§cAbbrechen", "§cCancel"), List.of()));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("vision.admin")
                || event.getClickedInventory() != holder.inventory || !event.isLeftClick()) {
            return;
        }
        int slot = event.getRawSlot();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.hasPermission("vision.admin")
                    || player.getOpenInventory().getTopInventory() != holder.inventory) {
                return;
            }
            if (holder.target != null) {
                if (slot == 11) {
                    install(player, holder.target);
                    open(player, 0);
                } else if (slot == 15) {
                    open(player, 0);
                }
                return;
            }
            if (slot < 45) {
                int index = holder.page * 45 + slot;
                if (index >= 0 && index < rows.size()) {
                    Row row = rows.get(index);
                    if (row.update != null && row.status.equals("available")) {
                        confirm(player, row.name);
                    } else {
                        for (String suggestion : row.suggestions) {
                            player.sendMessage("§e/update source " + row.name + " " + sourceLink(suggestion));
                        }
                        player.sendMessage("§e/update source " + row.name + " <Modrinth-/Spigot-URL>");
                        player.sendMessage("§e/update check");
                    }
                }
            } else if (slot == 45 || slot == 53) {
                open(player, holder.page + (slot == 45 ? -1 : 1));
            } else if (slot == 46) {
                check(player);
            } else if (slot == 48) {
                confirm(player, "all");
            } else if (slot == 49) {
                plugin.openFeatureMenu(player);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private ItemStack item(String material, String name, List<String> lore) {
        ItemStack item = XMaterial.matchXMaterial(material).map(XMaterial::parseItem).orElseThrow();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void refreshMenus() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder && holder.target == null) {
                render(player, holder);
            }
        }
    }

    private void sync(Runnable action) {
        if (!closed && plugin.isEnabled()) {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!closed) {
                        action.run();
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            }
        }
    }

    public void shutdown() {
        closed = true;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        }
    }

    @Getter
    private final class MenuHolder implements InventoryHolder {
        private final Inventory inventory;
        private final int page;
        private final String target;

        private MenuHolder(Player player, int page, String target) {
            this.page = page;
            this.target = target;
            inventory = plugin.getServer().createInventory(this, target == null ? 54 : 27,
                    text(plugin, player, "§8Vision §7| §8Plugin-Updates", "§8Vision §7| §8Plugin updates"));
        }
    }

    @Getter
    private static final class Row {
        private final String name;
        private final String currentVersion;
        private final String main;
        private final Path jar;
        private String source;
        private final List<String> suggestions = new ArrayList<>();
        private String status;
        private Update update;

        private Row(String name, String currentVersion, String main, Path jar, String source, String status) {
            this.name = name;
            this.currentVersion = currentVersion;
            this.main = main;
            this.jar = jar;
            this.source = source;
            this.status = status;
        }
    }

    @Getter
    @AllArgsConstructor
    private static final class Update {
        private final String version;
        private final URI url;
        private final String hash;
        private final long size;
    }
}