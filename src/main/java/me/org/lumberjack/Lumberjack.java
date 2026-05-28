package me.org.lumberjack;

import org.bukkit.plugin.java.JavaPlugin;

public class Lumberjack extends JavaPlugin {

    private static TreeBreakListener listener;

    @Override
    public void onEnable() {
        listener = new TreeBreakListener();

        getServer().getPluginManager().registerEvents(listener, this);
        getCommand("lumberjack").setExecutor(new LumberjackCommand());

        getLogger().info("Lumberjack plugin enabled");
    }

    public static TreeBreakListener getListener() {
        return listener;
    }
}
