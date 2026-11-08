package de.Darkness.sekret;

import de.Darkness.sekret.command.SekretCommand;
import de.Darkness.sekret.command.SitCommand;
import de.Darkness.sekret.command.SpawnCommand;
import de.Darkness.sekret.manager.DeathMechanicsManager;
import de.Darkness.sekret.manager.DeathScoreboardManager;
import de.Darkness.sekret.manager.DiseaseManager;
import de.Darkness.sekret.manager.FeatureMenu;
import de.Darkness.sekret.manager.LeashManager;
import de.Darkness.sekret.manager.PlayerMenu;
import de.Darkness.sekret.manager.PluginUpdateManager;
import de.Darkness.sekret.manager.ScheduledSoundManager;
import de.Darkness.sekret.manager.SettingsMenu;
import de.Darkness.sekret.manager.SitManager;
import de.Darkness.sekret.manager.ThirstManager;
import lombok.Getter;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
    @Getter
    private DeathMechanicsManager deathMechanicsManager;
    private DiseaseManager diseaseManager;
    private SitManager sitManager;
    @Getter
    private DeathScoreboardManager deathScoreboardManager;
    @Getter
    private ScheduledSoundManager scheduledSoundManager;
    private FeatureMenu featureMenu;
    @Getter
    private ThirstManager thirstManager;
    private SettingsMenu settingsMenu;
    @Getter
    private PlayerMenu playerMenu;
    @Getter
    private PluginUpdateManager pluginUpdateManager;

    @Override
    public void onEnable() {
        if (!updateConfiguration()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playerMenu = new PlayerMenu(this);
        leashManager = new LeashManager(this);
        deathMechanicsManager = new DeathMechanicsManager(this);
        diseaseManager = new DiseaseManager(this);
        sitManager = new SitManager(this);
        deathScoreboardManager = new DeathScoreboardManager(this);
        featureMenu = new FeatureMenu(this);
        thirstManager = new ThirstManager(this);
        settingsMenu = new SettingsMenu(this);
        pluginUpdateManager = new PluginUpdateManager(this);

        getServer().getPluginManager().registerEvents(leashManager, this);
        getServer().getPluginManager().registerEvents(deathMechanicsManager, this);
        getServer().getPluginManager().registerEvents(diseaseManager, this);
        getServer().getPluginManager().registerEvents(sitManager, this);
        getServer().getPluginManager().registerEvents(deathScoreboardManager, this);
        getServer().getPluginManager().registerEvents(featureMenu, this);
        getServer().getPluginManager().registerEvents(thirstManager, this);
        getServer().getPluginManager().registerEvents(settingsMenu, this);
        getServer().getPluginManager().registerEvents(playerMenu, this);
        getServer().getPluginManager().registerEvents(pluginUpdateManager, this);
        leashManager.start();

        PluginCommand deathsCommand = getCommand("todesliste");
        if (deathsCommand != null) {
            deathsCommand.setExecutor(deathScoreboardManager);
            deathsCommand.setTabCompleter(deathScoreboardManager);
        }
        PluginCommand command = getCommand("vision");
        if (command != null) {
            SekretCommand executor = new SekretCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PluginCommand updateCommand = getCommand("update");
        if (updateCommand != null) {
            SekretCommand executor = new SekretCommand(this);
            updateCommand.setExecutor(executor);
            updateCommand.setTabCompleter(executor);
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
        thirstManager.start();
        try {
            scheduledSoundManager = new ScheduledSoundManager(this);
            scheduledSoundManager.start();
        } catch (LinkageError error) {
            if (scheduledSoundManager != null) {
                scheduledSoundManager.shutdown();
                scheduledSoundManager = null;
            }
            getLogger().warning("Simple-Voice-Chat-API nicht verfügbar oder inkompatibel. Nur die Musikfunktion bleibt aus. Details: " + error);
        }
    }

    @Override
    public void onDisable() {
        if (pluginUpdateManager != null) {
            pluginUpdateManager.shutdown();
        }
        if (playerMenu != null) {
            playerMenu.shutdown();
        }
        if (settingsMenu != null) {
            settingsMenu.shutdown();
        }
        if (thirstManager != null) {
            thirstManager.shutdown();
        }
        if (featureMenu != null) {
            featureMenu.shutdown();
        }
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
        if (scheduledSoundManager != null) {
            scheduledSoundManager.reload();
        }
        deathScoreboardManager.reload();
        thirstManager.reload();
        featureMenu.refresh();
        settingsMenu.refresh();
        playerMenu.refresh();
    }

    public void openFeatureMenu(Player player) {
        featureMenu.open(player);
    }

    public void refreshPlayerLanguage(Player player) {
        deathScoreboardManager.refreshPlayer(player);
        thirstManager.refresh();
        featureMenu.refresh();
        settingsMenu.refresh();
    }

    public void openSettingsMenu(Player player, String section) {
        settingsMenu.open(player, section, 0);
    }

    public void setLanguage(String language) {
        getConfig().set("language", language);
        saveConfig();
        deathScoreboardManager.reload();
        thirstManager.refresh();
        featureMenu.refresh();
        settingsMenu.refresh();
        playerMenu.refresh();
    }

    public void setFeatureEnabled(String path, boolean enabled) {
        setSetting(path, enabled);
    }

    public void setSetting(String path, Object value) {
        if (path.equals("language")) {
            setLanguage((String) value);
            return;
        }
        getConfig().set(path, value);
        saveConfig();
        switch (path.split("\\.", 2)[0]) {
            case "blow", "excitement", "tripping" -> deathMechanicsManager.reload();
            case "leash" -> leashManager.reload();
            case "disease" -> diseaseManager.reload();
            case "sit" -> sitManager.reload();
            case "scheduled_sound" -> {
                if (scheduledSoundManager != null) {
                    scheduledSoundManager.reload();
                }
            }
            case "death_scoreboard" -> deathScoreboardManager.reload();
            case "thirst" -> thirstManager.reload();
        }
        featureMenu.refresh();
        settingsMenu.refresh();
        playerMenu.refresh();
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