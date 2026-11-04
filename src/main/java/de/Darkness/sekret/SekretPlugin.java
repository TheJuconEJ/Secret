package de.Darkness.sekret;

import de.Darkness.sekret.command.SekretCommand;
import de.Darkness.sekret.command.SitCommand;
import de.Darkness.sekret.command.SpawnCommand;
import de.Darkness.sekret.manager.DeathMechanicsManager;
import de.Darkness.sekret.manager.DeathScoreboardManager;
import de.Darkness.sekret.manager.DiseaseManager;
import de.Darkness.sekret.manager.LeashManager;
import de.Darkness.sekret.manager.ScheduledSoundManager;
import de.Darkness.sekret.manager.SitManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;

public final class SekretPlugin extends JavaPlugin {

    private LeashManager leashManager;
    private DeathMechanicsManager deathMechanicsManager;
    private DiseaseManager diseaseManager;
    private SitManager sitManager;
    private DeathScoreboardManager deathScoreboardManager;
    private ScheduledSoundManager scheduledSoundManager;

    @Override
    public void onEnable() {
        if (!updateConfiguration()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        leashManager = new LeashManager(this);
        deathMechanicsManager = new DeathMechanicsManager(this);
        diseaseManager = new DiseaseManager(this);
        sitManager = new SitManager(this);
        deathScoreboardManager = new DeathScoreboardManager(this);

        getServer().getPluginManager().registerEvents(leashManager, this);
        getServer().getPluginManager().registerEvents(deathMechanicsManager, this);
        getServer().getPluginManager().registerEvents(diseaseManager, this);
        getServer().getPluginManager().registerEvents(sitManager, this);
        getServer().getPluginManager().registerEvents(deathScoreboardManager, this);
        leashManager.start();

        PluginCommand deathsCommand = getCommand("todesliste");
        if (deathsCommand != null) {
            deathsCommand.setExecutor(deathScoreboardManager);
            deathsCommand.setTabCompleter(deathScoreboardManager);
        }
        PluginCommand command = getCommand("secret");
        if (command != null) {
            SekretCommand executor = new SekretCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PluginCommand sitCommand = getCommand("sit");
        if (sitCommand != null) {
            sitCommand.setExecutor(new SitCommand(this));
        }
        PluginCommand spawnCommand = getCommand("spawn");
        if (spawnCommand != null) {
            SpawnCommand executor = new SpawnCommand(this);
            spawnCommand.setExecutor(executor);
            spawnCommand.setTabCompleter(executor);
            getServer().getPluginManager().registerEvents(executor, this);
        }

        deathMechanicsManager.start();
        diseaseManager.start();
        deathScoreboardManager.start();
        scheduledSoundManager = new ScheduledSoundManager(this);
        scheduledSoundManager.start();
    }

    @Override
    public void onDisable() {
        if (scheduledSoundManager != null) {
            scheduledSoundManager.shutdown();
        }
        if (deathScoreboardManager != null) {
            deathScoreboardManager.shutdown();
        }
        if (deathMechanicsManager != null) {
            deathMechanicsManager.shutdown();
        }
        if (diseaseManager != null) {
            diseaseManager.shutdown();
        }
        if (leashManager != null) {
            leashManager.shutdown();
        }
        if (sitManager != null) {
            sitManager.shutdown();
        }
    }

    public void reloadPlugin() {
        if (!updateConfiguration()) {
            getLogger().warning("Neuladen abgebrochen. Die bisherigen Einstellungen bleiben aktiv.");
            return;
        }
        leashManager.reload();
        deathMechanicsManager.reload();
        diseaseManager.reload();
        sitManager.reload();
        scheduledSoundManager.reload();
    }

    private boolean updateConfiguration() {
        File directory = getDataFolder();
        File file = new File(directory, "config.yml");
        try (InputStream resource = getResource("config.yml")) {
            if (resource == null) {
                getLogger().severe("Die Standardkonfiguration fehlt in der Plugin-JAR.");
                return false;
            }
            Files.createDirectories(directory.toPath());
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(resource, StandardCharsets.UTF_8));
            YamlConfiguration configuration = new YamlConfiguration();
            boolean changed = !file.exists();
            if (file.exists()) {
                try {
                    configuration.load(file);
                } catch (InvalidConfigurationException exception) {
                    getLogger().warning("config.yml enthält ungültiges YAML. Sie wird vor der Wiederherstellung gesichert.");
                    configuration = new YamlConfiguration();
                    changed = true;
                }
            }

            for (String path : defaults.getKeys(true)) {
                Object expected = defaults.get(path);
                Object current = configuration.get(path);
                boolean compatible;
                if (expected instanceof ConfigurationSection) {
                    compatible = current instanceof ConfigurationSection;
                } else if (expected instanceof Number) {
                    compatible = current instanceof Number;
                } else if (expected instanceof Boolean) {
                    compatible = current instanceof Boolean;
                } else if (expected instanceof String) {
                    compatible = current instanceof String;
                } else if (expected instanceof List<?>) {
                    compatible = current instanceof List<?>;
                } else {
                    compatible = current != null && expected.getClass().isInstance(current);
                }
                if (compatible) {
                    continue;
                }
                if (current != null) {
                    getLogger().warning("Veralteter oder ungültiger Konfigurationstyp bei '" + path + "'; verwende den Standardwert.");
                }
                if (expected instanceof ConfigurationSection) {
                    configuration.set(path, null);
                    configuration.createSection(path);
                } else {
                    configuration.set(path, expected);
                }
                changed = true;
            }

            if (changed) {
                if (file.exists()) {
                    Path backup = Files.createTempFile(directory.toPath(), "config-backup-", ".yml");
                    Files.copy(file.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
                    getLogger().info("Bisherige Konfiguration gesichert: " + backup.getFileName());
                }
                Path temporary = Files.createTempFile(directory.toPath(), "config-update-", ".tmp");
                try {
                    configuration.save(temporary.toFile());
                    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                getLogger().info("Konfiguration automatisch ergänzt bzw. wiederhergestellt. Spielerdaten bleiben unverändert.");
            }
            reloadConfig();
            return true;
        } catch (IOException | InvalidConfigurationException exception) {
            getLogger().log(Level.SEVERE, "Konfiguration konnte nicht aktualisiert werden. Den Plugin-Ordner nicht löschen; bitte Dateizugriff und Konfiguration prüfen.", exception);
            return false;
        }
    }

    public LeashManager getLeashManager() {
        return leashManager;
    }

    public SitManager getSitManager() {
        return sitManager;
    }

    public DiseaseManager getDiseaseManager() {
        return diseaseManager;
    }
}