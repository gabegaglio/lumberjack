package me.org.lumberjack;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LumberjackCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§cUsage: /lumberjack <enable|disable>");
            return true;
        }

        TreeBreakListener listener = Lumberjack.getListener();

        switch (args[0].toLowerCase()) {
            case "enable" -> {
                listener.allow(player);
                player.sendMessage("§aLumberjack enabled. Sneak + right-click with an axe to toggle.");
            }
            case "disable" -> {
                listener.disallow(player);
                player.sendMessage("§cLumberjack disabled.");
            }
            default -> player.sendMessage("§cUsage: /lumberjack <enable|disable>");
        }

        return true;
    }
}
