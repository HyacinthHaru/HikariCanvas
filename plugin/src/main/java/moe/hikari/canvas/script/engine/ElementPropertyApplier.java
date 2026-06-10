package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.StrictNumber;
import moe.hikari.canvas.storage.WallRepo;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * {@code setElementProperty} 动作的双路径落地（T4；{@code docs/scripting.md §2.3 / §3}）：
 *
 * <ul>
 *   <li><b>路径 A（墙开着编辑器）</b>：经 {@link SessionPatchApplier} seam 反查活跃
 *       session → {@code EditSession.updateElement} 标准链（进 history + 前端 patch +
 *       投影 + persistWall）——与 {@code EditOpDispatcher} case {@code "element.update"}
 *       的收尾链一致。生产实现 = {@code SessionManager.applyScriptElementPatch}（批次 3
 *       装配时绑 OpPushCallback + ProjectionThrottler）。</li>
 *   <li><b>路径 B（headless）</b>：WallRepo 读 state → 临时 {@link EditSession} 套同一个
 *       {@code updateElement}（校验 / immutable 重建与编辑器路径单一权威，零语义分叉）→
 *       {@code wallRepo.updateState} → Ticker 处理照 {@code SessionManager.persistWall}
 *       链（在播 invalidate / 有 activeTimeline refreshAutoPlay / 静态墙不碰）。</li>
 * </ul>
 *
 * <p><b>纪律</b>：两路径都不读 wall lock（CLAUDE.md §lock-state 第 2 条——后端编辑路径
 * 与 lock 解耦；layer.locked 仍由 EditSession 语义生效）。值转换：数值属性走
 * {@link StrictNumber}（非数值 → 0.0，与 0.6 变量数值链同语义）；fill 仅收
 * {@code #RRGGBB(AA)}（→ EditSession 内 string→SolidFill 既有兼容路径），其他格式
 * error step；property×元素类型错配（如 text 元素给 fill）由 EditSession 校验拒 →
 * error step，链不断。</p>
 *
 * <p>线程：Runner 线程直调安全（EditSession synchronized / WallRepo / Ticker 入口
 * 均任意线程可调；persistWall 非主线程 OK——见各自类注释），无需主线程 hop。</p>
 */
public final class ElementPropertyApplier {

    /** fill 值文法（镜像 {@code ElementValidator.COLOR_PATTERN}：#RRGGBB / #RRGGBBAA）。 */
    private static final Pattern HEX_FILL = Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");

    /** 路径 A 结果三态。 */
    public enum SessionStatus { NO_SESSION, APPLIED, FAILED }

    /** 路径 A 结果（detail 仅 FAILED 时有意义）。 */
    public record SessionOutcome(SessionStatus status, @Nullable String detail) {
        public static SessionOutcome applied() {
            return new SessionOutcome(SessionStatus.APPLIED, null);
        }

        public static SessionOutcome noSession() {
            return new SessionOutcome(SessionStatus.NO_SESSION, null);
        }

        public static SessionOutcome failed(String detail) {
            return new SessionOutcome(SessionStatus.FAILED, detail);
        }
    }

    /**
     * 路径 A seam：定位绑定 {@code wallId} 的活跃 session 并走标准 element.update 链。
     * 生产 = {@code SessionManager.applyScriptElementPatch}；测试注 fake。
     */
    public interface SessionPatchApplier {
        SessionOutcome apply(String wallId, String elementId, Map<String, Object> patch);
    }

    private final @Nullable SessionPatchApplier sessionApplier;
    private final @Nullable WallRepo wallRepo;
    private final @Nullable TickerControl ticker;
    private final Logger log;

    public ElementPropertyApplier(@Nullable SessionPatchApplier sessionApplier,
                                  @Nullable WallRepo wallRepo,
                                  @Nullable TickerControl ticker,
                                  Logger log) {
        this.sessionApplier = sessionApplier;
        this.wallRepo = wallRepo;
        this.ticker = ticker;
        this.log = log;
    }

    /** 入口（Runner 线程）。任何失败 → error step，不抛。 */
    public TraceStep apply(String wallId, String blockId,
                           String elementId, String property, String value) {
        if (elementId == null || elementId.isEmpty()) {
            return TraceStep.error(blockId, "elementId 缺失");
        }
        Map<String, Object> patch;
        try {
            patch = buildPatch(property, value);
        } catch (IllegalArgumentException e) {
            return TraceStep.error(blockId, e.getMessage());
        }

        // 路径 A：活跃 session 标准链
        if (sessionApplier != null) {
            SessionOutcome outcome;
            try {
                outcome = sessionApplier.apply(wallId, elementId, patch);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "[脚本] session 路径 element.update 异常: wall="
                        + wallId + " element=" + elementId + " err=" + e.getMessage(), e);
                outcome = SessionOutcome.failed(String.valueOf(e.getMessage()));
            }
            if (outcome != null) {
                switch (outcome.status()) {
                    case APPLIED -> {
                        return TraceStep.ok(blockId, "action",
                                property + " → session(element.update)");
                    }
                    case FAILED -> {
                        return TraceStep.error(blockId, String.valueOf(outcome.detail()));
                    }
                    case NO_SESSION -> { /* fall through 到 headless */ }
                }
            }
        }
        return applyHeadless(wallId, blockId, elementId, property, patch);
    }

    /**
     * 路径 B：headless 直改。临时 EditSession 复用 {@code updateElement} 的校验 +
     * immutable 重建（其 history / patch 产物即弃），落库后照 persistWall 链处理 Ticker。
     *
     * <p><b>成本记账（scripting.md §10 P2 拍板：每 action 直落，不节流）</b>：每次 apply =
     * 一次 loadById（全量 JSON 反序列化）+ 一次 updateState（全量序列化落库）；同 run 内
     * 连续 N 个 setElementProperty 即 N 次往返。上限受 Budget 三闸封顶（10 runs/s ×
     * 50 actions），与编辑器 persistWall 频率同级，可接受。</p>
     *
     * <p><b>已知竞态（可接受，勿当新缺陷重报）</b>：与编辑器 session open/close 瞬间并发时
     * （路径 A 查无 session → 本路径写 DB → 新 session 持旧 state 首次 persist 覆盖；或
     * CLOSING session 的最终 persist 覆盖），脚本改动可能丢一次——单属性、低频低危。</p>
     */
    private TraceStep applyHeadless(String wallId, String blockId, String elementId,
                                    String property, Map<String, Object> patch) {
        if (wallRepo == null) {
            return TraceStep.error(blockId, "headless 路径不可用（WallRepo 未装配）");
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null || wall.state() == null) {
            return TraceStep.error(blockId, "wall 不存在或无 state: " + wallId);
        }
        EditSession es = new EditSession(wall.state());
        EditSession.OpResult r = es.updateElement(elementId, patch);
        if (r instanceof EditSession.OpResult.Error er) {
            return TraceStep.error(blockId, er.code() + ": " + er.message());
        }
        if (!(r instanceof EditSession.OpResult.Ok)) {
            return TraceStep.error(blockId, "意外的 op 结果: " + r.getClass().getSimpleName());
        }
        wallRepo.updateState(wallId, es.state());
        // 照 SessionManager.persistWall 的 Ticker 链：在播 → invalidate（下一帧用新 state）；
        // 没在播但有 activeTimeline → refreshAutoPlay；静态墙不碰（省一次 loadWall DB 读）
        if (ticker != null) {
            if (ticker.isWallAnimating(wallId)) {
                ticker.invalidate(wallId);
            } else if (es.state().activeTimelineId() != null) {
                ticker.refreshAutoPlay(wallId);
            }
        }
        return TraceStep.ok(blockId, "action", property + " → headless(updateState)");
    }

    /**
     * 值串 → element.update patch（单属性）。白名单 8 属性
     * （{@code ScriptRuleValidator.ELEMENT_PROPERTIES}）由 P1 校验兜底，此处防御重申。
     *
     * @throws IllegalArgumentException 属性不在白名单 / fill 格式非法
     */
    static Map<String, Object> buildPatch(String property, String value) {
        if (property == null) throw new IllegalArgumentException("property 缺失");
        return switch (property) {
            // 数值属性：StrictNumber 严格文法（非数值 → 0.0，与 0.6 变量数值链同语义）
            case "x", "y", "w", "h", "rotation" -> Map.of(property,
                    StrictNumber.clampInt(Math.round(StrictNumber.parse(value))));
            case "opacity" -> Map.of(property,
                    Math.min(1.0, Math.max(0.0, StrictNumber.parse(value))));
            case "text" -> Map.of(property, value == null ? "" : value);
            case "fill" -> {
                if (value == null || !HEX_FILL.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            "fill 仅支持 #RRGGBB / #RRGGBBAA，收到: " + value);
                }
                // 字符串形态交给 EditSession（ElementValidator.parseFillNullable
                // string → SolidFill 的 M11 既有兼容路径）
                yield Map.of(property, value);
            }
            default -> throw new IllegalArgumentException("不支持的属性: " + property);
        };
    }
}
