package id.javrock.streak;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class JavrockStreakPlugin extends JavaPlugin {

    private StreakManagerService streakService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.streakService = new StreakManagerService(this);
        getServer().getPluginManager().registerEvents(new StreakListener(streakService), this);

        StreakCommandExecutor executor = new StreakCommandExecutor(this, streakService);

        PluginCommand streakCmd = getCommand("streak");
        if (streakCmd != null) {
            streakCmd.setExecutor(executor);
            streakCmd.setTabCompleter(executor);
        }

        PluginCommand topCmd = getCommand("streaktop");
        if (topCmd != null) {
            topCmd.setExecutor(executor);
            topCmd.setTabCompleter(executor);
        }

        PluginCommand adminCmd = getCommand("streakadmin");
        if (adminCmd != null) {
            adminCmd.setExecutor(executor);
            adminCmd.setTabCompleter(executor);
        }

        getLogger().info("JavrockStreak v2.3.0 aktif untuk Paper 1.21.11.");
    }

    @Override
    public void onDisable() {
        if (streakService != null) {
            streakService.saveData();
        }
        getLogger().info("JavrockStreak dinonaktifkan.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (streakService != null) {
            streakService.saveData();
        }
    }
}
