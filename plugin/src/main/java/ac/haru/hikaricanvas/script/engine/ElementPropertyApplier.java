package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.StrictNumber;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * {@code setElementProperty} 动作的双路径落地（{@code docs/scripting.md §2.3 / §3}）：
 *
 * <ul>
 *   <li><b>路径 A（墙开着编辑器）</b>：经 {@link SessionPatchApplier} seam 反查活跃
 *       session → {@code EditSession.updateElement} 标准链（进 history + 前端 patch +
 *       投影 + persistWall）——与 {@code EditOpDispatcher} case {@code "element.update"}
 *       的收尾链一致。生产实现 = {@code SessionManager.applyScriptElementPatch}
 *       （装配时绑 OpPushCallback + ProjectionThrottler）。</li>
 *   <li><b>路径 B（headless）</b>：WallRepo 读 state → 临时 {@link EditSession} 套同一个
 *       {@code updateElement}（校验 / immutable 重建与编辑器路径单一权威，零语义分叉）→
 *       {@code wallRepo.updateState} → {@link #repaintAfterHeadlessWrite} 让画面跟上
 *       （在播 invalidate / 有 activeTimeline refreshAutoPlay / 静态墙 renderStatic 补画）。</li>
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

        /**
         * nudge 原子：session 锁内读当前 x/y + delta 写回。无 session 返 noSession。
         * default = noSession（旧 fake / 调用方自动走 headless 读改写）。
         */
        default SessionOutcome nudge(String wallId, String elementId, int dx, int dy) {
            return SessionOutcome.noSession();
        }

        /**
         * 活跃 session 克隆元素（新 id + 偏移）→ 标准 element.add 链 + 前端 patch。
         * 无 session 返 noSession（调用方走 headless）。default = noSession（旧 fake 兼容）。
         */
        default SessionOutcome clone(String wallId, String elementId, int offsetX, int offsetY) {
            return SessionOutcome.noSession();
        }

        /**
         * 活跃 session 删除元素 → 标准 element.delete 链 + 前端 patch。
         * 无 session 返 noSession。default = noSession（旧 fake 兼容）。
         */
        default SessionOutcome delete(String wallId, String elementId) {
            return SessionOutcome.noSession();
        }

        /**
         * 活跃 session 置顶/置底元素 → reorderElement 标准链 + 前端 patch。
         * mode = "front"（末尾）/ "back"（开头）。无 session 返 noSession。
         * default = noSession（旧 fake 兼容）。
         */
        default SessionOutcome reorderToEdge(String wallId, String elementId, String mode) {
            return SessionOutcome.noSession();
        }
    }

    private final @Nullable SessionPatchApplier sessionApplier;
    private final @Nullable WallRepo wallRepo;
    private final @Nullable TickerControl ticker;
    private final Logger log;

    /**
     * headless 克隆路径的单 wall 元素数上限。由 HikariCanvas 装配后注入
     * （{@code ScriptsConfig.maxElementsPerWall()}）；{@code <= 0} = 不限。路径 A（活跃 session）
     * 的配额由 session 自己的 EditSession 在 open/confirm 时注入，不读此字段。默认 0（未注入）
     * → headless 不限，测试零侵入。{@code /canvas reload} 经 {@link #setMaxElementsPerWall} 热更。
     */
    private volatile int maxElementsPerWall;

    public ElementPropertyApplier(@Nullable SessionPatchApplier sessionApplier,
                                  @Nullable WallRepo wallRepo,
                                  @Nullable TickerControl ticker,
                                  Logger log) {
        this.sessionApplier = sessionApplier;
        this.wallRepo = wallRepo;
        this.ticker = ticker;
        this.log = log;
    }

    /** 注入 headless 克隆路径的元素数上限（{@code <= 0} = 不限）。 */
    public void setMaxElementsPerWall(int max) {
        this.maxElementsPerWall = max;
    }

    /** 入口（Runner 线程）。任何失败 → error step，不抛。 */
    public TraceStep apply(String wallId, String blockId,
                           String elementId, String property, String value) {
        if (elementId == null || elementId.isEmpty()) {
            return TraceStep.error(blockId, "elementId missing");
        }
        Map<String, Object> patch;
        try {
            patch = buildPatch(property, value);
        } catch (IllegalArgumentException e) {
            return TraceStep.error(blockId, e.getMessage());
        }
        return applyPatch(wallId, blockId, elementId, patch, property);
    }

    /**
     * 批量设属性（friendly 积木）。{@code rawPatch} 每个 (key,val) 过
     * {@link #buildPatch} 合并成一个 element.update patch，一次落地（同 session/headless
     * 双路径，同 {@link #apply}）。任一键非法 → error step（链不断）。
     */
    public TraceStep applyMany(String wallId, String blockId, String elementId,
                               Map<String, String> rawPatch) {
        if (elementId == null || elementId.isEmpty()) {
            return TraceStep.error(blockId, "elementId missing");
        }
        if (rawPatch == null || rawPatch.isEmpty()) {
            return TraceStep.error(blockId, "patch is empty");
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> e : rawPatch.entrySet()) {
                merged.putAll(buildPatch(e.getKey(), e.getValue()));
            }
        } catch (IllegalArgumentException ex) {
            return TraceStep.error(blockId, ex.getMessage());
        }
        return applyPatch(wallId, blockId, elementId, merged, "patch" + merged.keySet());
    }

    /**
     * patch 落地核心（单属性 / 批量共用）：路径 A 活跃 session 标准链 → 否则 headless。
     * {@code desc} 仅进 trace 文案。
     */
    private TraceStep applyPatch(String wallId, String blockId, String elementId,
                                 Map<String, Object> patch, String desc) {
        // 路径 A：活跃 session 标准链
        if (sessionApplier != null) {
            SessionOutcome outcome;
            try {
                outcome = sessionApplier.apply(wallId, elementId, patch);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "[script] session path element.update error: wall="
                        + wallId + " element=" + elementId + " err=" + e.getMessage(), e);
                outcome = SessionOutcome.failed(String.valueOf(e.getMessage()));
            }
            if (outcome != null) {
                switch (outcome.status()) {
                    case APPLIED -> {
                        return TraceStep.ok(blockId, "action",
                                desc + " -> session(element.update)");
                    }
                    case FAILED -> {
                        return TraceStep.error(blockId, String.valueOf(outcome.detail()));
                    }
                    case NO_SESSION -> { /* fall through 到 headless */ }
                }
            }
        }
        return applyHeadless(wallId, blockId, elementId, desc, patch);
    }

    /**
     * 相对移动。dx/dy round 成 int 增量。session 路径锁内原子读改写；
     * headless 路径读 DB state 当前 x/y + 增量（已知低危竞态同 {@link #applyHeadless} 注释）。
     */
    public TraceStep applyNudge(String wallId, String blockId, String elementId,
                                double dx, double dy) {
        if (elementId == null || elementId.isEmpty()) {
            return TraceStep.error(blockId, "elementId missing");
        }
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            return TraceStep.error(blockId, "dx/dy must be finite");
        }
        int idx = (int) Math.round(dx);
        int idy = (int) Math.round(dy);
        // 路径 A：session 原子 nudge
        if (sessionApplier != null) {
            SessionOutcome outcome;
            try {
                outcome = sessionApplier.nudge(wallId, elementId, idx, idy);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "[script] session nudge error: wall=" + wallId
                        + " element=" + elementId + " err=" + e.getMessage(), e);
                outcome = SessionOutcome.failed(String.valueOf(e.getMessage()));
            }
            if (outcome != null) {
                switch (outcome.status()) {
                    case APPLIED -> {
                        return TraceStep.ok(blockId, "action",
                                "nudge -> session(+" + idx + "," + idy + ")");
                    }
                    case FAILED -> {
                        return TraceStep.error(blockId, String.valueOf(outcome.detail()));
                    }
                    case NO_SESSION -> { /* fall through headless */ }
                }
            }
        }
        // 路径 B：headless 读 state 当前 x/y + 增量
        if (wallRepo == null) {
            return TraceStep.error(blockId, "headless path unavailable (WallRepo not wired)");
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null || wall.state() == null) {
            return TraceStep.error(blockId, "wall not found or has no state: " + wallId);
        }
        Integer curX = null;
        Integer curY = null;
        for (var layer : wall.state().layers()) {
            for (var el : layer.elements()) {
                if (el.id().equals(elementId)) {
                    curX = el.x();
                    curY = el.y();
                }
            }
        }
        if (curX == null) {
            return TraceStep.error(blockId, "element not found: " + elementId);
        }
        // 升 long 相加防 int 回绕（idx 可接近 Integer.MAX_VALUE），与 session 路径
        // SessionManager.applyScriptElementNudge 的 (long) 升级对齐；下游 clampInt 收窄
        return applyMany(wallId, blockId, elementId,
                Map.of("x", String.valueOf((long) curX + idx),
                        "y", String.valueOf((long) curY + idy)));
    }

    /**
     * 克隆元素（新 id + 偏移）。双路径同 {@link #apply}：路径 A 活跃 session
     * 走 {@code EditSession.cloneElement} 标准链（前端 add patch 实时可见）；NO_SESSION → headless。
     */
    public TraceStep applyClone(String wallId, String blockId, String elementId,
                                int offsetX, int offsetY) {
        if (elementId == null || elementId.isEmpty()) {
            return TraceStep.error(blockId, "elementId missing");
        }
        if (sessionApplier != null) {
            SessionOutcome outcome;
            try {
                outcome = sessionApplier.clone(wallId, elementId, offsetX, offsetY);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "[script] session clone error: wall=" + wallId
                        + " element=" + elementId + " err=" + e.getMessage(), e);
                outcome = SessionOutcome.failed(String.valueOf(e.getMessage()));
            }
            if (outcome != null) {
                switch (outcome.status()) {
                    case APPLIED -> {
                        return TraceStep.ok(blockId, "action", "clone " + elementId
                                + " (+" + offsetX + "," + offsetY + ") -> session");
                    }
                    case FAILED -> {
                        return TraceStep.error(blockId, String.valueOf(outcome.detail()));
                    }
                    case NO_SESSION -> { /* fall through headless */ }
                }
            }
        }
        return cloneHeadless(wallId, blockId, elementId, offsetX, offsetY);
    }

    /**
     * 删除元素。双路径同 {@link #apply}：路径 A 活跃 session 走
     * {@code EditSession.deleteElement} 标准链（前端 remove patch）；NO_SESSION → headless。
     */
    public TraceStep applyDelete(String wallId, String blockId, String elementId) {
        if (elementId == null || elementId.isEmpty()) {
            return TraceStep.error(blockId, "elementId missing");
        }
        if (sessionApplier != null) {
            SessionOutcome outcome;
            try {
                outcome = sessionApplier.delete(wallId, elementId);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "[script] session delete error: wall=" + wallId
                        + " element=" + elementId + " err=" + e.getMessage(), e);
                outcome = SessionOutcome.failed(String.valueOf(e.getMessage()));
            }
            if (outcome != null) {
                switch (outcome.status()) {
                    case APPLIED -> {
                        return TraceStep.ok(blockId, "action", "delete " + elementId + " -> session");
                    }
                    case FAILED -> {
                        return TraceStep.error(blockId, String.valueOf(outcome.detail()));
                    }
                    case NO_SESSION -> { /* fall through headless */ }
                }
            }
        }
        return deleteHeadless(wallId, blockId, elementId);
    }

    /**
     * 元素置顶/置底（双路径，照 {@link #applyClone}/{@link #applyDelete}）。
     * 路径 A：session 内 reorderElement 标准链；NO_SESSION → headless。
     */
    public TraceStep applySetElementLayer(String wallId, String blockId,
                                          String elementId, String mode) {
        if (elementId == null || elementId.isEmpty()) {
            return TraceStep.error(blockId, "elementId missing");
        }
        if (sessionApplier != null) {
            SessionOutcome outcome;
            try {
                outcome = sessionApplier.reorderToEdge(wallId, elementId, mode);
            } catch (RuntimeException e) {
                log.log(java.util.logging.Level.WARNING,
                        "[script] session reorderToEdge error: wall=" + wallId
                                + " element=" + elementId + " err=" + e.getMessage(), e);
                outcome = SessionOutcome.failed(String.valueOf(e.getMessage()));
            }
            if (outcome != null) {
                switch (outcome.status()) {
                    case APPLIED -> {
                        return TraceStep.ok(blockId, "action",
                                "setElementLayer " + mode + " -> session");
                    }
                    case FAILED -> {
                        return TraceStep.error(blockId, String.valueOf(outcome.detail()));
                    }
                    case NO_SESSION -> { /* fall through headless */ }
                }
            }
        }
        return setElementLayerHeadless(wallId, blockId, elementId, mode);
    }

    /** 路径 B：headless 置顶/置底（临时 EditSession → moveElementToFront/Back → updateState + Ticker）。 */
    private TraceStep setElementLayerHeadless(String wallId, String blockId,
                                              String elementId, String mode) {
        boolean front = "front".equals(mode);
        return runHeadless(wallId, blockId, "setElementLayer(" + mode + ") " + elementId,
                es -> front ? es.moveElementToFront(elementId) : es.moveElementToBack(elementId));
    }

    /** 路径 B：headless 克隆（临时 EditSession 注入元素数配额 → cloneElement → updateState + Ticker）。 */
    private TraceStep cloneHeadless(String wallId, String blockId, String elementId,
                                    int offsetX, int offsetY) {
        return runHeadless(wallId, blockId, "clone " + elementId, es -> {
            es.setMaxElementsPerWall(maxElementsPerWall);
            return es.cloneElement(elementId, offsetX, offsetY);
        });
    }

    /** 路径 B：headless 删除（临时 EditSession → deleteElement → updateState + Ticker）。 */
    private TraceStep deleteHeadless(String wallId, String blockId, String elementId) {
        return runHeadless(wallId, blockId, "delete " + elementId,
                es -> es.deleteElement(elementId));
    }

    /**
     * 路径 B 通用骨架（clone / delete 共用，照 {@link #applyHeadless} 的 loadById → 临时
     * EditSession → updateState → Ticker 链）：{@code op} 在临时 session 上执行一次结构变更
     * （history / patch 产物即弃，仅 state 落库）。成本 / 已知竞态同 {@link #applyHeadless} 注释。
     */
    private TraceStep runHeadless(String wallId, String blockId, String desc,
                                  java.util.function.Function<EditSession,
                                          EditSession.OpResult> op) {
        if (wallRepo == null) {
            return TraceStep.error(blockId, "headless path unavailable (WallRepo not wired)");
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null || wall.state() == null) {
            return TraceStep.error(blockId, "wall not found or has no state: " + wallId);
        }
        EditSession es = new EditSession(wall.state());
        EditSession.OpResult r = op.apply(es);
        if (r instanceof EditSession.OpResult.Error er) {
            return TraceStep.error(blockId, er.code() + ": " + er.message());
        }
        if (!(r instanceof EditSession.OpResult.Ok)) {
            return TraceStep.error(blockId, "unexpected op result: " + r.getClass().getSimpleName());
        }
        wallRepo.updateState(wallId, es.state());
        repaintAfterHeadlessWrite(wallId, es.state());
        return TraceStep.ok(blockId, "action", desc + " -> headless(updateState)");
    }

    /**
     * 路径 B：headless 直改。临时 EditSession 复用 {@code updateElement} 的校验 +
     * immutable 重建（其 history / patch 产物即弃），落库后照 persistWall 链处理 Ticker。
     *
     * <p><b>成本记账（scripting.md §10：每 action 直落，不节流）</b>：每次 apply =
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
            return TraceStep.error(blockId, "headless path unavailable (WallRepo not wired)");
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null || wall.state() == null) {
            return TraceStep.error(blockId, "wall not found or has no state: " + wallId);
        }
        EditSession es = new EditSession(wall.state());
        EditSession.OpResult r = es.updateElement(elementId, patch);
        if (r instanceof EditSession.OpResult.Error er) {
            return TraceStep.error(blockId, er.code() + ": " + er.message());
        }
        if (!(r instanceof EditSession.OpResult.Ok)) {
            return TraceStep.error(blockId, "unexpected op result: " + r.getClass().getSimpleName());
        }
        wallRepo.updateState(wallId, es.state());
        repaintAfterHeadlessWrite(wallId, es.state());
        return TraceStep.ok(blockId, "action", property + " -> headless(updateState)");
    }

    /**
     * headless 落库后让游戏内的地图跟上新 state。三种墙分三条路：
     *
     * <ul>
     *   <li>在播动画 → {@code invalidate}，Ticker 下一帧自己拿新 state 重渲；</li>
     *   <li>没在播但挂了时间轴 → {@code refreshAutoPlay}（照 {@code SessionManager.persistWall}）；</li>
     *   <li><b>静态墙 → {@code renderStatic} 补一次重画</b>。这里原先什么都不做，于是没人开编辑器、
     *       又没时间轴的墙被脚本改完只有数据库变了，画面要等到重启 / 有人打开编辑器 / 恰好某个
     *       变量触发重画才更新。补间在静态墙上落末帧走的就是这条 renderStatic 路径，语义一致。</li>
     * </ul>
     *
     * <p>渲完立刻 {@code clearStaticDiff}：两次调用都投递到 Ticker 的单线程 executor，FIFO 保证
     * 清理排在渲染之后，既不留 {@code byte[mapCount][16384]} 的 per-wall diff 缓存，也不影响本次出帧
     * （代价是下次脚本改动重新全量推，与变量重画路径 {@code projectByWall} 同量级）。</p>
     *
     * <p>活跃编辑器 session 的墙不会走到这——那条是路径 A，压根不进 headless。</p>
     */
    private void repaintAfterHeadlessWrite(String wallId, ac.haru.hikaricanvas.state.ProjectState state) {
        if (ticker == null) return;
        if (ticker.isWallAnimating(wallId)) {
            ticker.invalidate(wallId);
        } else if (state.activeTimelineId() != null) {
            ticker.refreshAutoPlay(wallId);
        } else {
            ticker.renderStatic(wallId, state);
            ticker.clearStaticDiff(wallId);
        }
    }

    /**
     * 值串 → element.update patch（单属性）。白名单 9 属性
     * （{@code ScriptRuleValidator.ELEMENT_PROPERTIES}）由校验层兜底，此处防御重申。
     *
     * <p>本 switch 必须与 {@code ScriptRuleValidator.ELEMENT_PROPERTIES} 逐项对齐——
     * 校验层放行而这里 default 抛错的属性，会让积木保存成功但运行时恒返 error step，
     * 且补间末帧无法落库（{@code color} 曾漏此 case）。</p>
     *
     * @throws IllegalArgumentException 属性不在白名单 / fill / color 格式非法
     */
    static Map<String, Object> buildPatch(String property, String value) {
        if (property == null) throw new IllegalArgumentException("property missing");
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
                            "fill only supports #RRGGBB / #RRGGBBAA, got: " + value);
                }
                // 字符串形态交给 EditSession（ElementValidator.parseFillNullable
                // string → SolidFill 的 M11 既有兼容路径）
                yield Map.of(property, value);
            }
            case "color" -> {
                // text 字色。EditSession.applyTextPatch 的 color case 走
                // ElementValidator.validateColor（COLOR_RE 与本类 HEX_FILL 同正则）。
                if (value == null || !HEX_FILL.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            "color only supports #RRGGBB / #RRGGBBAA, got: " + value);
                }
                yield Map.of(property, value);
            }
            default -> throw new IllegalArgumentException("unsupported property: " + property);
        };
    }
}
