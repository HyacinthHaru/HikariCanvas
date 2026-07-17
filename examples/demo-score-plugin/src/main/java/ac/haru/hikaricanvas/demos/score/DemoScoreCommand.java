package ac.haru.hikaricanvas.demos.score;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /demoscore <add|reset|set> [team] [value]} 命令处理器。
 *
 * <h2>用法</h2>
 * <pre>
 *   /demoscore add red 1
 *   /demoscore add blue 2
 *   /demoscore set red 0
 *   /demoscore reset
 * </pre>
 */
public final class DemoScoreCommand implements CommandExecutor {
    private final DemoScorePlugin plugin;

    public DemoScoreCommand(DemoScorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§7Usage: /demoscore <add|reset|set> [team] [value]");
            return true;
        }
        String op = args[0].toLowerCase();
        try {
            switch (op) {
                case "add" -> {
                    if (args.length < 3) {
                        sender.sendMessage("§c/demoscore add <red|blue> <n>");
                        return true;
                    }
                    int n = Integer.parseInt(args[2]);
                    if (args[1].equalsIgnoreCase("red")) {
                        plugin.addRed(n);
                    } else if (args[1].equalsIgnoreCase("blue")) {
                        plugin.addBlue(n);
                    } else {
                        sender.sendMessage("§cteam must be red/blue");
                        return true;
                    }
                    sender.sendMessage("§a+ " + n + " to " + args[1]);
                }
                case "set" -> {
                    if (args.length < 3) {
                        sender.sendMessage("§c/demoscore set <red|blue> <n>");
                        return true;
                    }
                    int n = Integer.parseInt(args[2]);
                    if (args[1].equalsIgnoreCase("red")) {
                        plugin.setRed(n);
                    } else if (args[1].equalsIgnoreCase("blue")) {
                        plugin.setBlue(n);
                    } else {
                        sender.sendMessage("§cteam must be red/blue");
                        return true;
                    }
                    sender.sendMessage("§a" + args[1] + " = " + n);
                }
                case "reset" -> {
                    plugin.reset();
                    sender.sendMessage("§areset");
                }
                default -> sender.sendMessage("§cunknown op: " + op);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cinvalid number: " + e.getMessage());
        }
        return true;
    }
}
