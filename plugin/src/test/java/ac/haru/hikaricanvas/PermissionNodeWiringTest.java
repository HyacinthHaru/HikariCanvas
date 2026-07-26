package ac.haru.hikaricanvas;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code paper-plugin.yml} 里声明的每个权限节点都必须在代码里真的被查过。
 *
 * <p>定义了却没人查的节点比不定义更糟：服主收掉它以为封停了功能，实际全功能照用，
 * 而且从日志到游戏内都看不出任何异常。0.9.17 之前 {@code canvas.use} / {@code canvas.wand} /
 * {@code canvas.commit} / {@code canvas.admin.bypass-limit} 四个节点全仓零引用，
 * 而 {@code docs/security.md §5} 还把它们当正经功能列着（§6 的检查点表甚至写明
 * {@code /canvas wand → canvas.wand}，实际查的是 {@code canvas.edit}）。</p>
 *
 * <p>这条守卫要么逼你接线，要么逼你把节点删掉，不给「先定义着以后再说」留口子。</p>
 */
class PermissionNodeWiringTest {

    /** 从 repo 根往上找，兼容不同工作目录（Gradle 通常在 plugin/ 下跑测试）。 */
    private static Path resolveFromRepo(String relative) {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && cur != null; i++) {
            Path candidate = cur.resolve(relative);
            if (Files.exists(candidate)) return candidate;
            cur = cur.getParent();
        }
        throw new IllegalStateException("找不到 " + relative + "（cwd=" + Path.of("").toAbsolutePath() + "）");
    }

    /** 解析 paper-plugin.yml 的 permissions: 段，取出节点名。 */
    private static List<String> declaredNodes() throws IOException {
        Path yml = resolveFromRepo("plugin/src/main/resources/paper-plugin.yml");
        List<String> out = new ArrayList<>();
        boolean inPermissions = false;
        Pattern node = Pattern.compile("^ {2}([A-Za-z0-9._-]+):\\s*$");
        for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
            if (line.startsWith("permissions:")) { inPermissions = true; continue; }
            if (!inPermissions) continue;
            // 段结束：回到顶格的非注释、非空行
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) break;
            Matcher m = node.matcher(line);
            if (m.matches()) out.add(m.group(1));
        }
        return out;
    }

    /** 主源码树 + 配置里出现过的所有字面量（找 "canvas.xxx" 引用用）。 */
    private static String mainSourcesBlob() throws IOException {
        Path src = resolveFromRepo("plugin/src/main/java");
        StringBuilder sb = new StringBuilder(1 << 20);
        try (Stream<Path> files = Files.walk(src)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 已知未接线、且已在契约文档里<b>显式记录</b>的节点。
     *
     * <p>这三个属于变量子系统：{@code docs/dynamic-data.md §9.1} 白纸黑字写着
     * 「已声明但当前未被代码引用 —— per-wall user/* 删除复用 write.own」，
     * 并在正文里解释了原因。文档没骗人，所以暂时放过；但它们仍是待决项
     * （要么真接线、要么删节点），别往这个名单里再加东西。</p>
     */
    private static final Set<String> DOCUMENTED_UNWIRED = Set.of(
            "canvas.var.read", "canvas.var.delete.own", "canvas.var.delete.any");

    @Test
    void everyDeclaredPermissionNodeIsReferencedInCode() throws IOException {
        List<String> nodes = declaredNodes();
        assertFalse(nodes.isEmpty(), "没解析到任何权限节点，说明解析逻辑坏了");

        String blob = mainSourcesBlob();
        Set<String> orphans = new LinkedHashSet<>();
        for (String n : nodes) {
            if (DOCUMENTED_UNWIRED.contains(n)) continue;
            if (!blob.contains("\"" + n + "\"")) orphans.add(n);
        }
        assertTrue(orphans.isEmpty(),
                "以下权限节点在 paper-plugin.yml 里声明了，但全仓没有任何 hasPermission 引用 —— "
                        + "要么接线，要么从 yml + docs/security.md §5/§6 删掉：" + orphans);
    }

    /** security.md §6 检查点表写的是 canvas.wand，命令树也必须查这个节点。 */
    @Test
    void wandCommandGuardsOnCanvasWandNode() throws IOException {
        Path cmd = resolveFromRepo(
                "plugin/src/main/java/ac/haru/hikaricanvas/command/CanvasCommand.java");
        String src = Files.readString(cmd, StandardCharsets.UTF_8);
        int wandIdx = src.indexOf("Commands.literal(\"wand\")");
        assertTrue(wandIdx > 0, "找不到 /canvas wand 子命令");
        String wandBlock = src.substring(wandIdx, Math.min(src.length(), wandIdx + 600));
        assertTrue(wandBlock.contains("\"canvas.wand\""),
                "/canvas wand 的门禁必须是 canvas.wand（security.md §6），实际片段：\n" + wandBlock);
    }

    /** canvas.use 是基础总开关，必须挂在 /canvas 根节点上，收掉即整族命令不可见。 */
    @Test
    void rootCommandGuardsOnCanvasUseNode() throws IOException {
        Path cmd = resolveFromRepo(
                "plugin/src/main/java/ac/haru/hikaricanvas/command/CanvasCommand.java");
        String src = Files.readString(cmd, StandardCharsets.UTF_8);
        int rootIdx = src.indexOf("Commands.literal(\"canvas\")");
        assertTrue(rootIdx > 0, "找不到 /canvas 根节点");
        String rootBlock = src.substring(rootIdx, Math.min(src.length(), rootIdx + 400));
        assertTrue(rootBlock.contains("\"canvas.use\""),
                "canvas.use 必须 gate 在根节点上，实际片段：\n" + rootBlock);
    }
}
