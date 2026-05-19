package moe.hikari.canvas.variable;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渲染期文本占位符替换。0.4.0-P1-C 核心类。
 *
 * <p>把 {@code ${var:<name>}} 或 {@code ${var:<name>|fallback=<default>}} 形式的占位符
 * 替换成 {@link VariableStore} 当前缓存值；不在 MC 主线程跑 resolve（Provider 任务 E 才提供
 * async 拉取），本类<b>只读 store 缓存</b>，O(1) lookup，不发起任何 I/O。</p>
 *
 * <h2>fullName 编码注入</h2>
 * <p>对外占位符语法见 {@code docs/dynamic-data.md §2.3}：
 * <ul>
 *   <li>{@code ${var:user/红队比分}} — 用户变量，本类会注入当前 wallId 转成内部
 *       {@code user:<wallId>/红队比分}（与 {@link VariableStore} fullName 编码一致）。</li>
 *   <li>{@code ${var:bedwars/score}} — 插件变量，直接按字面 fullName 查 store。</li>
 *   <li>{@code ${var:server.time}} — 系统变量点分号 alias，按字面查 store（system namespace
 *       由 Provider 任务 E 在启动期注册同名 fullName）。</li>
 * </ul></p>
 *
 * <h2>fallback 链</h2>
 * <p>详见 {@code docs/dynamic-data.md §5.3 / §11}：
 * <ol>
 *   <li>store 内 {@code currentValue} 非空 → 用</li>
 *   <li>{@code currentValue} 空 / 变量不存在 → 用占位符语法里的 {@code |fallback=...}</li>
 *   <li>无 fallback 语法 → 用 {@link Variable#defaultValue()}</li>
 *   <li>{@code defaultValue} 也空 → 系统兜底 {@value #UNRESOLVED}</li>
 * </ol></p>
 *
 * <h2>线程安全</h2>
 * <p>无可变状态；{@link VariableStore#get} 自身线程安全（ConcurrentHashMap）。可在 WS / 主线程 /
 * BukkitScheduler async 任意线程调 {@link #interpolate}。</p>
 */
public final class VariableInterpolator {

    /**
     * 占位符正则。锚定 {@code ${var:NAME}} 或 {@code ${var:NAME|fallback=VALUE}}：
     * <ul>
     *   <li>{@code group(1)} — 变量名（不含 {@code |} / {@code }}）</li>
     *   <li>{@code group(2)} — fallback 值（可空字符串）；缺失 {@code |fallback=...} 则为 null</li>
     * </ul>
     * 名字与 fallback 内均不允许 {@code }} 出现，简化解析、不支持嵌套。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\$\\{var:([^|}]+)(?:\\|fallback=([^}]*))?\\}");

    /** 系统兜底文案（fallback 链最后一档）。 */
    public static final String UNRESOLVED = "???";

    private final VariableStore store;

    public VariableInterpolator(VariableStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 解析 {@code text} 中的 {@code ${var:...}} 占位符。
     *
     * <p>对纯文本（不含 {@code ${var:}} 子串）走 O(1) 短路返原 text 与空 referenced 集合；
     * 否则 O(n) regex 单趟扫描。</p>
     *
     * @param text   原始文本；可为 null（返 null + 空 referenced）
     * @param wallId 当前 wall ID；非空时把 {@code ${var:user/X}} 注入成 {@code user:<wallId>/X}；
     *               为 null 时 user 命名空间占位符按字面 {@code user/X} 查 store（必然 miss，
     *               走 fallback → "???"），便于无 wall 上下文的预览 / 模板 publish 路径
     * @return 解析结果（替换后文本 + 被引用的内部 fullName 集合）
     */
    public Result interpolate(@Nullable String text, @Nullable String wallId) {
        if (text == null || text.isEmpty() || text.indexOf("${var:") < 0) {
            return new Result(text, Set.of());
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        Set<String> referenced = new HashSet<>();
        while (m.find()) {
            String rawName = m.group(1).trim();
            String fallback = m.group(2); // null = 无 |fallback= 语法；"" = 显式空 fallback
            String fullName = resolveFullName(rawName, wallId);
            referenced.add(fullName);
            String resolved = resolveValue(fullName, fallback);
            // quoteReplacement 防 $ / \ 在 appendReplacement 里被当反向引用 / 转义符
            m.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(out);
        return new Result(out.toString(), referenced);
    }

    /**
     * 把对外 placeholder 内的 rawName 转成 {@link VariableStore} 的 fullName 编码：
     * <ul>
     *   <li>{@code user/X} → {@code user:<wallId>/X}（wallId 非空时；为空走字面）</li>
     *   <li>{@code bedwars/score} → 字面 {@code bedwars/score}</li>
     *   <li>{@code server.time} → 字面 {@code server.time}（system namespace alias，
     *       由 Provider 任务 E 在 store 内注册同名 fullName）</li>
     * </ul>
     */
    private static String resolveFullName(String rawName, @Nullable String wallId) {
        if (wallId != null && !wallId.isEmpty()
                && rawName.startsWith(VariableStore.USER_NAMESPACE_PREFIX + "/")) {
            // ${var:user/X} → user:<wallId>/X
            String key = rawName.substring(VariableStore.USER_NAMESPACE_PREFIX.length() + 1);
            return VariableStore.USER_NAMESPACE_PREFIX + ":" + wallId + "/" + key;
        }
        return rawName;
    }

    /** 按 {@code docs/dynamic-data.md §5.3} fallback 链解析。 */
    private String resolveValue(String fullName, @Nullable String inlineFallback) {
        Optional<Variable> opt = store.get(fullName);
        if (opt.isPresent()) {
            Variable v = opt.get();
            String cur = v.currentValue();
            if (cur != null && !cur.isEmpty()) return cur;
            if (inlineFallback != null) return inlineFallback;
            String def = v.defaultValue();
            if (def != null) return def;
            return UNRESOLVED;
        }
        // 变量不存在（被删 / 未声明 / 命名不匹配）
        if (inlineFallback != null) return inlineFallback;
        return UNRESOLVED;
    }

    /**
     * 解析结果。
     *
     * @param text                替换后的最终文本（纯文本路径下与入参完全相同的引用）
     * @param referencedFullNames 本次解析引用到的内部 fullName 集合；
     *                            供 {@link VariableStore#markWallReferences} 使用
     */
    public record Result(@Nullable String text, Set<String> referencedFullNames) {
    }
}
