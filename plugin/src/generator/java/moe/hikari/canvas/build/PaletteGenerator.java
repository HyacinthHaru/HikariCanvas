package moe.hikari.canvas.build;

import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * M4-T1：构建期从 Paper {@link MapPalette} 导出 256 色 palette 到 JSON。
 *
 * <p>由 Gradle 任务 {@code generatePalette} 调用；运行时仅依赖 Paper API 的
 * {@link MapPalette#getColor(byte)}，不需要启动 Bukkit Server。</p>
 *
 * <p>输出格式（一行一个索引，便于 git diff 审查）：</p>
 * <pre>
 * [
 *   {"index": 0, "rgb": [0, 0, 0], "alpha": 0},
 *   {"index": 1, "rgb": [127, 178, 56], "alpha": 255},
 *   ...
 * ]
 * </pre>
 *
 * <p>这不是运行时渲染类；它只在构建期跑一次。shadow jar 里会多一份 class 文件
 * （约 600 字节），可忽略。</p>
 *
 * <p>用法：{@code java -cp ... moe.hikari.canvas.build.PaletteGenerator <outfile>}</p>
 */
public final class PaletteGenerator {

    private PaletteGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: PaletteGenerator <output-path>");
            System.exit(2);
        }
        Path out = Paths.get(args[0]);
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // MapPalette 内部 colors 数组长度随 MC 版本变（Paper 1.21.11 为 248；
        // 历史版本曾 192 / 256）。不假定固定长度，动态探测：遇 AIOOBE 停。
        List<String> entries = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            @SuppressWarnings("removal") // Paper 1.21 整族 matchColor/getColor 标 forRemoval，暂无替代
            Color c;
            try {
                c = MapPalette.getColor((byte) i);
            } catch (ArrayIndexOutOfBoundsException stop) {
                break;
            }
            entries.add(String.format(
                    "  {\"index\": %d, \"rgb\": [%d, %d, %d], \"alpha\": %d}",
                    i, c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()));
        }

        List<String> lines = new ArrayList<>(entries.size() + 2);
        lines.add("[");
        for (int i = 0; i < entries.size(); i++) {
            lines.add(entries.get(i) + (i < entries.size() - 1 ? "," : ""));
        }
        lines.add("]");
        Files.write(out, lines);
        System.out.println("wrote " + entries.size() + " palette entries -> " + out);
    }
}
