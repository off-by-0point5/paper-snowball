package com.github.offby0point5.mc.plugin.paper.snowball;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.java.JavaPlugin;

public final class Snowball extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        this.getServer().getPluginManager().registerEvents(new SnowballEvents(), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        this.getServer().unloadWorld("world", false);  // disable saving
    }
}
