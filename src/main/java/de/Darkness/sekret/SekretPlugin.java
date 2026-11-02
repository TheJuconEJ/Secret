package de.Darkness.sekret;

import de.Darkness.sekret.command.SekretCommand;
import de.Darkness.sekret.command.SitCommand;
import de.Darkness.sekret.command.SpawnCommand;
import de.Darkness.sekret.manager.DeathMechanicsManager;
import de.Darkness.sekret.manager.DiseaseManager;
import de.Darkness.sekret.manager.LeashManager;
import de.Darkness.sekret.manager.SitManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SekretPlugin extends JavaPlugin {

    private LeashManager leashManager;
    private DeathMechanicsManager deathMechanicsManager;
    private DiseaseManager diseaseManager;
    private SitManager sitManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        leashManager = new LeashManager(this);
        deathMechanicsManager = new DeathMechanicsManager(this);
        diseaseManager = new DiseaseManager(this);
        sitManager = new SitManager(this);

        getServer().getPluginManager().registerEvents(leashManager, this);
        getServer().getPluginManager().registerEvents(deathMechanicsManager, this);
        getServer().getPluginManager().registerEvents(diseaseManager, this);
        getServer().getPluginManager().registerEvents(sitManager, this);
        leashManager.start();

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
    }

    @Override
    public void onDisable() {
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
        reloadConfig();
        leashManager.reload();
        deathMechanicsManager.reload();
        diseaseManager.reload();
        sitManager.reload();
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