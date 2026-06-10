package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.template.expr.Expr;
import moe.hikari.canvas.template.expr.ExpressionEvaluator;
import moe.hikari.canvas.template.expr.ExpressionParser;
import moe.hikari.canvas.variable.Variable;
import moe.hikari.canvas.variable.VariableInterpolator;
import moe.hikari.canvas.variable.VariableStore;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.7.0-P2(T2)：脚本条件求值入口——包装 {@link ExpressionParser} +
 * {@link ExpressionEvaluator}（K2 扩展文法）+ 变量 resolver 注入（K3）。
 * 契约 {@code docs/scripting.md}。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>{@code var("user/score")} 经 {@link VariableInterpolator#resolveFullName}
 *       注入 wall 上下文（{@code user/X} → {@code user:<wallId>/X} 等）后查
 *       {@link VariableStore} cached 值；fresh currentValue → 该值；stale / 空 →
 *       defaultValue（可 null）；变量不存在 → {@code notifyDynamicLookup} + null
 *       （照 {@code VariableInterpolator.resolveValue} 的 fallback 链，去掉 inline
 *       fallback 档——条件文法无 {@code |fallback=} 语法）。null → truthy=false</li>
 *   <li>parse 失败 / eval 抛（含模板路径专属的 UndeclaredParam 等）→ {@code false} +
 *       WARNING（<b>每条件串只 WARN 一次</b>,防坏条件每帧刷屏）——坏条件不许炸链</li>
 *   <li>条件 null / 空白 → {@code false}（validator 已拦非空,这里是兜底,不 WARN）</li>
 * </ul>
 *
 * <h2>parse 缓存</h2>
 *
 * <p>{@code condition 串 → Expr} 的 {@link ConcurrentHashMap} 缓存；<b>parse 失败缓存
 * 负结果</b>（{@code PARSE_FAILED} 哨兵）,坏条件不会每次触发重 parse。规则更新换
 * condition 串即换 key,旧 entry 由容量上限兜底清理（{@link #CACHE_MAX} 超限整体
 * clear——规则总量受 per-wall 配额约束,正常永远到不了上限）。</p>
 *
 * <p>线程安全：全部状态是并发容器,可被 ScriptRunner 线程 + 任意管理线程并发调用。</p>
 */
public final class ConditionEvaluator {

    /** parse 缓存容量上限,超限整体 clear（防异常输入面把缓存撑爆）。 */
    static final int CACHE_MAX = 4096;

    /** parse 失败负缓存哨兵。 */
    private static final Object PARSE_FAILED = new Object();

    private final Logger log;
    /** fullName（已 wall 注入）→ 当前值 / null。 */
    private final @Nullable Function<String, String> lookup;
    private final ExpressionParser parser = new ExpressionParser();

    /** condition 串 → {@link Expr} 或 {@link #PARSE_FAILED}。 */
    private final ConcurrentHashMap<String, Object> parseCache = new ConcurrentHashMap<>();
    /** 已 WARN 过的条件串（每串只刷一次日志；I-1：同受 {@link #CACHE_MAX} 上界约束）。 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** 实际 parse 次数（缓存生效的单测侧证;非公开契约）。 */
    private final AtomicInteger parseCount = new AtomicInteger();

    /** 生产装配：resolver 读 {@link VariableStore} cached 值。store 可 null（求值时变量恒 miss）。 */
    public ConditionEvaluator(Logger log, @Nullable VariableStore store) {
        this(log, store == null ? null : storeLookup(store));
    }

    /** 测试装配：直接注入 fullName → 值 的 lookup（可 null = 变量恒 miss）。 */
    public ConditionEvaluator(Logger log, @Nullable Function<String, String> lookup) {
        this.log = log;
        this.lookup = lookup;
    }

    /**
     * 求值条件。{@code wallId} 用于 {@code var(...)} 的 fullName wall 注入
     * （{@link VariableInterpolator#resolveFullName}）。任何失败 → {@code false}。
     */
    public boolean eval(String condition, String wallId) {
        if (condition == null || condition.isBlank()) return false;
        Object cached = parseCache.get(condition);
        if (cached == null) {
            if (parseCache.size() >= CACHE_MAX) {
                // 极端防御：缓存撑爆整体重建（正常规则量级远到不了这里）。
                // I-1：warned 与 parseCache 同按条件串记账，同步清防两者漂移
                parseCache.clear();
                warned.clear();
            }
            cached = parseCache.computeIfAbsent(condition, c -> {
                parseCount.incrementAndGet();
                try {
                    return parser.parse(c);
                } catch (RuntimeException e) {
                    warnOnce(c, "parse failed", e);
                    return PARSE_FAILED;
                }
            });
        }
        if (cached == PARSE_FAILED) return false;
        Expr expr = (Expr) cached;

        // per-eval 构一个带 wallId 注入的 evaluator（两字段对象,分配可忽略;
        // resolver 必须捕获本次 eval 的 wallId,不能跨 wall 复用）
        Function<String, String> resolver = lookup == null ? null
                : raw -> lookup.apply(VariableInterpolator.resolveFullName(raw, wallId));
        ExpressionEvaluator evaluator = new ExpressionEvaluator(resolver, log);
        try {
            return evaluator.evalBoolean(expr, Map.of());
        } catch (RuntimeException e) {
            // UndeclaredParam（脚本条件不支持模板参数标识符）/ VarUnsupported（lookup
            // 为 null 时写了 var()）等 → false,不炸链
            warnOnce(condition, "eval failed", e);
            return false;
        }
    }

    /** 实际 parse 次数（包级,单测缓存侧证用）。 */
    int parseCountForTest() {
        return parseCount.get();
    }

    private void warnOnce(String condition, String stage, RuntimeException e) {
        // I-1：warned 自身设独立上界——eval 失败的条件串不进 parseCache 负缓存，
        // 单靠 parseCache 超限同步清不足以约束 warned 的增长
        if (warned.size() >= CACHE_MAX) {
            warned.clear();
        }
        if (warned.add(condition)) {
            log.log(Level.WARNING, "脚本条件" + stage + "（该条件后续恒为 false,只警告一次）: "
                    + condition + " — " + e.getMessage());
        }
    }

    /**
     * 默认 lookup：照 {@code VariableInterpolator.resolveValue} 的 fallback 链
     * （cached fresh → default → null）,去掉 inline fallback 档。
     *
     * <p>包级共享：{@code ActionExecutor.incrementVariable} 读当前值用同款链
     * （T4——保证条件判定与累加起算值取自同一语义）。</p>
     */
    static Function<String, String> storeLookup(VariableStore store) {
        return fullName -> {
            Optional<Variable> opt = store.get(fullName);
            if (opt.isPresent()) {
                Variable v = opt.get();
                String cur = v.currentValue();
                if (cur != null && !cur.isEmpty() && !v.isStale(System.currentTimeMillis())) {
                    return cur;
                }
                return v.defaultValue();
            }
            // 触发动态 namespace 注册 hook（scoreboard 等）——首次 miss,后续有数据
            store.notifyDynamicLookup(fullName);
            return null;
        };
    }
}
