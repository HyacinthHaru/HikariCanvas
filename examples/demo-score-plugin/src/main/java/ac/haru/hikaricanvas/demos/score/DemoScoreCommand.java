package ac.haru.hikaricanvas.demos.score;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /demoscore <add|reset|set> [team] [value]} 命令处理器（基于 Paper {@link BasicCommand}）。
 *
 * <h2>用法</h2>
 * <pre>
 *   /demoscore add red 1
 *   /demoscore add blue 2
 *   /demoscore set red 0
 *   /demoscore reset
 * </pre>
 */
public final class DemoScoreCommand implements BasicCommand {
    private static final List<String> OPS = List.of("add", "set", "reset");
    private static final List<String> TEAMS = List.of("red", "blue");

    private final DemoScorePlugin plugin;

    public DemoScoreCommand(DemoScorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable String permission() {
        return "hikari.demo.score";
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (args.length < 1) {
            sender.sendMessage(Component.text(
                    "用法: /demoscore <add|set|reset> [red|blue] [数值]", NamedTextColor.GRAY));
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (op) {
                case "add" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Component.text(
                                "用法: /demoscore add <red|blue> <数值>", NamedTextColor.RED));
                        return;
                    }
                    int n = Integer.parseInt(args[2]);
                    if ("red".equalsIgnoreCase(args[1])) {
                        plugin.addRed(n);
                    } else if ("blue".equalsIgnoreCase(args[1])) {
                        plugin.addBlue(n);
                    } else {
                        sender.sendMessage(Component.text("队伍只能是 red 或 blue", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text(
                            "已为 " + args[1].toLowerCase(Locale.ROOT) + " 增加 " + n, NamedTextColor.GREEN));
                }
                case "set" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Component.text(
                                "用法: /demoscore set <red|blue> <数值>", NamedTextColor.RED));
                        return;
                    }
                    int n = Integer.parseInt(args[2]);
                    if ("red".equalsIgnoreCase(args[1])) {
                        plugin.setRed(n);
                    } else if ("blue".equalsIgnoreCase(args[1])) {
                        plugin.setBlue(n);
                    } else {
                        sender.sendMessage(Component.text("队伍只能是 red 或 blue", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text(
                            "已将 " + args[1].toLowerCase(Locale.ROOT) + " 设为 " + n, NamedTextColor.GREEN));
                }
                case "reset" -> {
                    plugin.reset();
                    sender.sendMessage(Component.text("红蓝两队比分已归零", NamedTextColor.GREEN));
                }
                default -> sender.sendMessage(Component.text("未知子命令: " + op, NamedTextColor.RED));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("数值格式无效: " + args[2], NamedTextColor.RED));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return OPS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && ("add".equalsIgnoreCase(args[0]) || "set".equalsIgnoreCase(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return TEAMS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
