package ac.haru.hikaricanvas.variable;

import ac.haru.hikaricanvas.state.StrictNumber;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渲染期文本占位符替换。
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

    /**
     * 跨墙引用被拦下时替换成的哨兵名。
     *
     * <p>占位符里允许直接写 store 的内部形态（插件 namespace 就靠这条透传），但
     * per-wall 三族 {@code user:} / {@code system:} / {@code schedule:} 只认本墙的 wallId——
     * 写别人的 wallId 会被换成这个名字，store 必然查不到，走 fallback 链显示
     * {@value #UNRESOLVED}（{@code dynamic-data.md §2.3} 跨墙隔离）。</p>
     *
     * <p>名字里的 {@code !} 不在 {@code VariableStore} 的 namespace 文法内，所以真实变量
     * 永远不可能叫这个，也就不会误命中谁的数据；动态 namespace hook 同样匹配不上。</p>
     */
    public static final String CROSS_WALL_DENIED = "!denied/cross-wall";

    /** per-wall namespace 前缀（这三族的 namespace 形如 {@code <前缀>:<wallId>}）。 */
    private static final String[] PER_WALL_PREFIXES = {"user:", "system:", "schedule:"};

    /**
     * Provider 自有的 namespace（脚本不许写；它们的值由各自 Provider 周期覆盖，
     * 让脚本写进去只会伪造系统数据）。带 {@code :wallId} 后缀的 per-wall 形态一并覆盖。
     */
    private static final String[] PROVIDER_OWNED = {"system", "papi", "scoreboard", "schedule"};

    /** 二次扫描最大深度，防止数据 / 算法异常下死循环。 */
    private static final int MAX_INTERPOLATE_DEPTH = 2;

    private static final Logger LOG = Logger.getLogger(VariableInterpolator.class.getName());

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
     * <p>二次扫描兜底（服务器重启后字面 {@code ${var:} 残留场景）：把 {@link #doInterpolate}
     * 拆成内部实现 + 外层 wrapper 做二次扫描。当首次替换后输出仍含 {@code ${var:} 字面（典型场景：
     * chip editor roundtrip 漏 escape / 数据写入了双重嵌套 {@code ${${var:X}}}），最多再 interpolate
     * {@value #MAX_INTERPOLATE_DEPTH} 次兜底；若真展开了一层（depth&gt;0）则 {@link Logger#warning}
     * 提示用户数据可能嵌套。<b>某一轮没有任何变化就立刻收工</b>——那说明剩下的 {@code ${var:}
     * 根本匹配不上正则（典型：行尾漏了右花括号），再扫也是同一个串，不必白跑也不该告警。
     * 输出与"跑满两轮"完全一致，双端兜底语义不变。</p>
     *
     * @param text   原始文本；可为 null（返 null + 空 referenced）
     * @param wallId 当前 wall ID；非空时把 {@code ${var:user/X}} 注入成 {@code user:<wallId>/X}；
     *               为 null 时 user 命名空间占位符按字面 {@code user/X} 查 store（必然 miss，
     *               走 fallback → "???"），便于无 wall 上下文的预览 / 模板 publish 路径
     * @return 解析结果（替换后文本 + 被引用的内部 fullName 集合）
     */
    public Result interpolate(@Nullable String text, @Nullable String wallId) {
        Result result = doInterpolate(text, wallId);
        int depth = 0;
        while (result.text() != null && result.text().indexOf("${var:") >= 0
                && depth < MAX_INTERPOLATE_DEPTH) {
            Result next = doInterpolate(result.text(), wallId);
            // 没变化 = 剩下的 ${var: 根本匹配不上正则（典型：漏了右花括号的 ${var:foo），
            // 再扫多少遍都是同一个串。立刻收工——否则每次渲染都白跑满 depth 且打一条
            // WARNING，动画墙上就是每秒几十条日志（谁都能靠一个残缺占位符刷屏）。
            if (java.util.Objects.equals(next.text(), result.text())) {
                break;
            }
            Set<String> mergedRefs = new HashSet<>(result.referencedFullNames());
            mergedRefs.addAll(next.referencedFullNames());
            result = new Result(next.text(), mergedRefs, next.segments());
            depth++;
        }
        // depth>0 = 真的嵌套了一层并被展开（数据可能有问题），值得告警；
        // 匹配不上的残缺占位符走上面的 break，不在这里刷日志。
        if (depth > 0) {
            String preview = text == null ? "(null)"
                    : text.length() > 100 ? text.substring(0, 100) + "..." : text;
            LOG.warning("[VariableInterpolator] nested placeholder detected; depth=" + depth
                    + " text=" + preview);
        }
        return result;
    }

    /**
     * 数值关键帧轨的 {@code ${var:X}} 求值（rendering.md §9.5）。
     * 把 {@code raw}（单个模板 / 含模板的字符串 / 纯数字字符串）经标准 fallback 链
     * （cached → inline fallback → default → "???"）resolve 后解析为 double；
     * 非数值（含 "???"）/ 非有限值 / 解析失败一律落到链终点 {@code 0.0}。
     *
     * <p>由 {@code AnimationTicker} 在<b>插值前</b>调用（Ticker 线程；store cached 值
     * 异步读安全），插值器拿到的是纯数值——变量变化经 per-map diff 自然
     * 反映到下一帧。</p>
     */
    public double resolveAsNumber(@Nullable String raw, @Nullable String wallId) {
        if (raw == null || raw.isEmpty()) return 0.0;
        String resolved = raw;
        if (raw.indexOf("${var:") >= 0) {
            resolved = interpolate(raw, wallId).text();
        }
        // §9.5：与渲染层 / op 层共用严格文法，不私自 parseDouble（否则变量值会接受
        // 0x1p4 / 5d 等而字面值不接受，且与 TS 端分叉）
        return StrictNumber.parse(resolved);
    }

    /** 单次扫描实现；调用方（外层 {@link #interpolate}）负责二次扫描兜底。 */
    private Result doInterpolate(@Nullable String text, @Nullable String wallId) {
        if (text == null || text.isEmpty() || text.indexOf("${var:") < 0) {
            return new Result(text, Set.of(), List.of());
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        Set<String> referenced = new HashSet<>();
        List<Segment> segments = new ArrayList<>();
        // 替换循环：手工累积输出字符串以记录每个 placeholder 在替换后串中的 char range。
        // 不用 Matcher.appendReplacement 因为它对 $ / \ 做特殊解释；改为手工 append + escape-free
        int prevEnd = 0;
        while (m.find()) {
            // 1. 复制非 placeholder 段
            out.append(text, prevEnd, m.start());
            String rawName = m.group(1).trim();
            String fallback = m.group(2); // null = 无 |fallback= 语法；"" = 显式空 fallback
            String fullName = resolveFullName(rawName, wallId);
            referenced.add(fullName);
            String resolved = resolveValue(fullName, fallback);
            int segStart = out.length();
            out.append(resolved);
            int segEnd = out.length();
            segments.add(new Segment(segStart, segEnd, fullName, m.group()));
            prevEnd = m.end();
        }
        // 复制末尾非 placeholder 段
        if (prevEnd < text.length()) {
            out.append(text, prevEnd, text.length());
        }
        return new Result(out.toString(), referenced, List.copyOf(segments));
    }

    /**
     * 把对外 placeholder 内的 rawName 转成 {@link VariableStore} 的 fullName 编码：
     * <ul>
     *   <li>{@code user/X} → {@code user:<wallId>/X}（wallId 非空时；为空走字面）</li>
     *   <li>{@code wall.X} → {@code system:<wallId>/wall.X}（
     *       {@link ac.haru.hikaricanvas.variable.provider.SystemVariableProvider} 按 per-wall
     *       namespace 注册 wall.id / wall.alias / wall.owner / wall.owner_uuid，wallId 为空跳过）</li>
     *   <li>{@code scoreboard.<obj>.<player>} → {@code scoreboard/<obj>.<player>}（
     *       动态 namespace 别名：把第一个 {@code .} 当 namespace/key 分隔——让
     *       {@link ac.haru.hikaricanvas.variable.provider.ScoreboardVariableProvider}
     *       存的 {@code scoreboard/<obj>.<player>} 被字面查询命中）</li>
     *   <li>{@code bedwars/score} → 字面 {@code bedwars/score}</li>
     *   <li>{@code server.time} → 字面 {@code server.time}（system namespace 点分号 alias
     *       仍按字面；SystemVariableProvider 内部 fullName 是 {@code system/server.time}，
     *       编辑器 Picker 使用 slash 形式；点分号 alias 完整支持尚未实现）</li>
     * </ul>
     */
    public static String resolveFullName(String rawName, @Nullable String wallId) {
        if (wallId != null && !wallId.isEmpty()
                && rawName.startsWith(VariableStore.USER_NAMESPACE_PREFIX + "/")) {
            // ${var:user/X} → user:<wallId>/X
            String key = rawName.substring(VariableStore.USER_NAMESPACE_PREFIX.length() + 1);
            return VariableStore.USER_NAMESPACE_PREFIX + ":" + wallId + "/" + key;
        }
        // wall.* 系统变量按 per-wall namespace 注入
        // e.g. ${var:wall.id} + wallId="w-abc" → "system:w-abc/wall.id"
        // wallId == null（模板 publish / 预览路径）跳过注入，让 wall.* 字面查询（多半 fallback）
        if (wallId != null && !wallId.isEmpty() && rawName.startsWith("wall.")) {
            return "system:" + wallId + "/" + rawName;
        }
        // schedule.* per-wall namespace 注入（与 wall.* 同款）
        // e.g. ${var:schedule.next_departure} + wallId="w-abc" → "schedule:w-abc/next_departure"
        // ManualScheduleProvider 按 namespace=schedule:<wallId> 注册 4 个 key（next_departure /
        // next_destination / eta_minutes / is_arriving）。wallId == null 跳过注入，字面查询走 fallback。
        if (wallId != null && !wallId.isEmpty() && rawName.startsWith("schedule.")) {
            String tail = rawName.substring("schedule.".length());
            return "schedule:" + wallId + "/" + tail;
        }
        // 用户直觉的 namespace/key 斜杠语法 — 与 ${var:user/X} 同款风格。
        // 不破坏 schedule.X / wall.X 点号语法，仅作为 fallback；wallId 为空时跳过，字面查询走 fallback。
        // e.g. ${var:schedule/eta_seconds} + wallId="w-abc" → "schedule:w-abc/eta_seconds"
        // e.g. ${var:wall/id} + wallId="w-abc" → "system:w-abc/wall.id"（注入到 SystemVariableProvider
        // 注册的同款 fullName 形态）
        if (wallId != null && !wallId.isEmpty() && rawName.startsWith("wall/")) {
            String tail = rawName.substring("wall/".length());
            return "system:" + wallId + "/wall." + tail;
        }
        if (wallId != null && !wallId.isEmpty() && rawName.startsWith("schedule/")) {
            String tail = rawName.substring("schedule/".length());
            return "schedule:" + wallId + "/" + tail;
        }
        // scoreboard.<obj>.<player> 点分号 alias → scoreboard/<obj>.<player>
        // 与 ScoreboardVariableProvider.handleDynamic 存储侧约定一致。
        if (rawName.startsWith("scoreboard.")) {
            String tail = rawName.substring("scoreboard.".length());
            if (!tail.isEmpty() && tail.indexOf('/') < 0) {
                return "scoreboard/" + tail;
            }
        }
        // 上面各分支都没接住 = 字面透传（插件 namespace 靠这条）。透传前拦一道跨墙：
        // 直接写 user:<别人的墙>/key 这种内部形态就能读到别人墙的变量值，破坏 namespace 隔离。
        if (isForeignPerWall(rawName, wallId)) {
            return CROSS_WALL_DENIED;
        }
        return rawName;
    }

    /**
     * 这个 fullName 是不是"别的墙"的 per-wall 变量？
     *
     * <p>{@code user:<w>/…} / {@code system:<w>/…} / {@code schedule:<w>/…} 三族的 {@code <w>}
     * 与当前 {@code wallId} 不一致即为跨墙（{@code wallId} 为空的路径——模板预览 / publish——
     * 一律算跨墙，那些路径本来就不该读到任何墙的私有数据）。非 per-wall 形态（插件
     * namespace / 全局 system 变量 {@code system/server.time} 等）恒返 false。</p>
     */
    public static boolean isForeignPerWall(@Nullable String fullName, @Nullable String wallId) {
        if (fullName == null) return false;
        for (String prefix : PER_WALL_PREFIXES) {
            if (!fullName.startsWith(prefix)) continue;
            int slash = fullName.indexOf('/', prefix.length());
            // 缺 key 段（如 "user:w-1"）不是合法 fullName，按跨墙处理（保守）
            if (slash < 0) return true;
            String owner = fullName.substring(prefix.length(), slash);
            return wallId == null || wallId.isEmpty() || !owner.equals(wallId);
        }
        return false;
    }

    /**
     * 这个 fullName 是不是 Provider 自有 namespace（{@code system} / {@code papi} /
     * {@code scoreboard} / {@code schedule}，含 {@code <ns>:<wallId>} 的 per-wall 形态）？
     * 脚本的变量族积木据此拒写（{@code security.md §13.8}）。
     */
    public static boolean isProviderOwned(@Nullable String fullName) {
        if (fullName == null) return false;
        int slash = fullName.indexOf('/');
        if (slash < 0) return false;
        String namespace = fullName.substring(0, slash);
        int colon = namespace.indexOf(':');
        String base = colon < 0 ? namespace : namespace.substring(0, colon);
        for (String owned : PROVIDER_OWNED) {
            if (owned.equals(base)) return true;
        }
        return false;
    }

    /** 按 {@code docs/dynamic-data.md §5.3} fallback 链解析。 */
    private String resolveValue(String fullName, @Nullable String inlineFallback) {
        Optional<Variable> opt = store.get(fullName);
        if (opt.isPresent()) {
            Variable v = opt.get();
            String cur = v.currentValue();
            // TTL 过期后不再返回 cached value，走 fallback 链。
            // ttl=0 表示永久；isStale 内已处理。stale 时与"变量不存在"语义一致：触发
            // dynamic lookup hook 让 Provider 有机会刷新，再走 fallback / default / UNRESOLVED。
            if (cur != null && !cur.isEmpty() && !v.isStale(System.currentTimeMillis())) {
                return cur;
            }
            if (inlineFallback != null) return inlineFallback;
            String def = v.defaultValue();
            if (def != null) return def;
            return UNRESOLVED;
        }
        // 变量不存在（被删 / 未声明 / 命名不匹配）。
        // 触发动态 namespace 注册 hook——首次渲染走 fallback，后续 tick 起有数据。
        // 本方法仍 O(1) 短路：hook 实现应快速返回；阻塞处理走自己的 daemon。
        store.notifyDynamicLookup(fullName);
        if (inlineFallback != null) return inlineFallback;
        return UNRESOLVED;
    }

    /**
     * 解析结果。
     *
     * @param text                替换后的最终文本（纯文本路径下与入参完全相同的引用）
     * @param referencedFullNames 本次解析引用到的内部 fullName 集合；
     *                            供 {@link VariableStore#markWallReferences} 使用
     * @param segments            每个 placeholder 在<b>替换后</b> {@link #text} 中的 char range +
     *                            原始 fullName + 原 placeholder 字符串。游戏内
     *                            Compositor 渲染时<b>忽略 segments</b>（保持双端像素一致），仅前端
     *                            PreviewRenderer 用 segments 画 placeholder hint chip 风格背景。
     *                            纯文本路径或无 placeholder 时为空列表
     */
    public record Result(@Nullable String text, Set<String> referencedFullNames,
                         List<Segment> segments) {
    }

    /**
     * 单个 placeholder 在替换后文本中的位置标记。
     *
     * @param start    替换后文本中起始 char index（inclusive）
     * @param end      替换后文本中结束 char index（exclusive）
     * @param fullName 内部 fullName（{@link VariableStore} 编码后；如 {@code user:w-1/X}）
     * @param raw      原始 placeholder 字符串（如 {@code "${var:user/X}"}）；调试 / hover tooltip 用
     */
    public record Segment(int start, int end, String fullName, String raw) {
    }
}
