package ac.haru.hikaricanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code paper-plugin.yml} 里声明的每个权限节点都必须在代码里真的被查过。
 *
 * <p>定义了却没人查的节点会让服主收掉权限后以为封停了功能，实际功能仍照常可用。
 * 本测试确保声明节点与代码引用保持一致。</p>
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
        throw new IllegalStateException("Cannot locate " + relative + " (cwd=" + Path.of("").toAbsolutePath() + ")");
    }

    /** 使用 Jackson {@link YAMLMapper} 解析 {@code paper-plugin.yml} 的 {@code permissions} 段。 */
    private static List<String> declaredNodes() throws IOException {
        Path yml = resolveFromRepo("plugin/src/main/resources/paper-plugin.yml");
        JsonNode root = new YAMLMapper().readTree(yml.toFile());
        JsonNode permissions = root.get("permissions");
        List<String> out = new ArrayList<>();
        if (permissions != null && permissions.isObject()) {
            permissions.fieldNames().forEachRemaining(out::add);
        }
        return out;
    }

    /** 主源码树里出现过的所有源码文本（查找 {@code "canvas.xxx"} 引用用）。 */
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
     * 已知未接线、且已在契约文档（{@code docs/dynamic-data.md §9.1}）里显式记录的节点：
     * per-wall {@code user/*} 删除复用 {@code write.own}。
     */
    private static final Set<String> DOCUMENTED_UNWIRED = Set.of(
            "canvas.var.read", "canvas.var.delete.own", "canvas.var.delete.any");

    @Test
    void everyDeclaredPermissionNodeIsReferencedInCode() throws IOException {
        List<String> nodes = declaredNodes();
        assertFalse(nodes.isEmpty(), "No permission nodes parsed from paper-plugin.yml");

        String blob = mainSourcesBlob();
        Set<String> orphans = new LinkedHashSet<>();
        for (String n : nodes) {
            if (DOCUMENTED_UNWIRED.contains(n)) continue;
            if (!blob.contains("\"" + n + "\"")) orphans.add(n);
        }
        assertTrue(orphans.isEmpty(),
                "Declared permission nodes in paper-plugin.yml have zero references in code: " + orphans);
    }
}
