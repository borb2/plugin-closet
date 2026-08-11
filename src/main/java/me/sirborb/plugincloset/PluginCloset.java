package me.sirborb.plugincloset;

import org.bukkit.plugin.java.JavaPlugin;

public final class PluginCloset extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("PluginCloset enabled");
    }
}
