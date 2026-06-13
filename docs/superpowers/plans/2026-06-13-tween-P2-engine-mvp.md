# 补间动画 P2 实施计划：补间引擎 MVP

> **For agentic workers:** subagent-driven。P2 是核心难点（新引擎 + 挂起 + 渲染 + 落盘），一个后端子代理做，主控深度 review + 对抗审查。

**Goal:** `TweenScheduler` 独立补间引擎跑起来——脚本「在 X 秒内 移动到」→ 静态墙招牌每帧平滑滑动（渲临时态、不落 DB）→ 补完目标值落盘。**MVP 先做「移动（x/y）单属性」**，多属性/颜色/fill/缓动接入 P3。

**Architecture（基于 P2 两轮调研）:**
- 落盘：`ElementPropertyApplier.applyMany`（末帧一次）。
- 挂起：复用 `PlayTimelineAwait` 范式（ScriptRunner 压帧栈 + `scheduler.schedule(cont, durationMs)` + return）。
- 渲染：**路径 Z**——`AnimationTicker` 加 `renderStatic(wallId, ProjectState frame)` 公开入口（Ticker 线程跑，复用 `renderFrame` 的 viewer-gated + diff）。补间每帧自持插值好的 `ProjectState frame`（内存，不落 DB），调 `renderStatic` 渲。
- 调度器：独立单线程 SES（`hikari-canvas-tween`），照 `ScriptRunner.SesScheduler` 范式。

**契约:** `docs/scripting-tween.md`（T1-T10；P2 落地后回填 T7/§5：静态墙渲临时态省 DB）。

---

## 锚点（两轮调研已摸）
- `ElementPropertyApplier.applyMany(wallId, blockId, elementId, Map<String,String> patch)` → TraceStep（`engine/ElementPropertyApplier.java`，双路径封装好）。
- 读元素当前值：`wallRepo.loadById(wallId)` → `wall.state().layers()` → `layer.elements()` → `el.id().equals(elementId)` → `el.x()/y()/w()/h()/rotation()/effectiveOpacity()`；写时数值转 `String.valueOf`。
- 挂起范式：`ScriptRunner.java:342-370`（PlayTimelineAwait）+ `:327-340`（Wait）。TweenBlock P1 占位在 `:373-377`。
- `EasingSolver.ease(Easing, double)` → [0,1]（`render/EasingSolver.java:36`）。
- `AnimationTicker`：单线程 SES（`:191-195`）+ `FrameRenderer.renderFrame(Wall, ProjectState, FrameDiff, boolean force)`（`CanvasProjector.java:351`）+ `FrameDiff`（`:104-113`，per-wall byte[][]，线程限定）+ `entries`（ConcurrentHashMap，key wallId）。
- 调度器范式：`ScriptRunner.SesScheduler`（`:525-555`）+ shutdown（`AnimationTicker.shutdown :403-418`）。
- 装配：`HikariCanvas.java` onEnable scriptRunner 之后（约 :790）；`cleanupResources`（:1032-1048）scriptRunner.shutdown 之后加 tweenScheduler.shutdown。

---

## Task（后端引擎，一个子代理 + 主控深度 review）

### 步骤 1：`AnimationTicker.renderStatic`（路径 Z，~30 行）
`AnimationTicker` 加公开方法 + per-wall 静态 diff 表：
```java
private final Map<String, FrameDiff> staticDiffs = new ConcurrentHashMap<>();

/** 渲染一次某 wall 的当前 state（补间用，wall 无 timeline entry 时）。Ticker 线程执行保 BufferPool/diff 契约。 */
public void renderStatic(String wallId, ProjectState frame) {
    if (shutdown || wallId == null || frame == null) return;
    scheduler.execute(() -> {
        try {
            if (entries.containsKey(wallId)) return;        // 有 timeline 在播，不抢（共存留 P3）
            Wall wall = wallSource.load(wallId).orElse(null);
            if (wall == null) return;
            FrameDiff diff = staticDiffs.computeIfAbsent(wallId, k -> new FrameDiff());
            renderer.renderFrame(wall, frame, diff, false);  // viewer-gated：无观众返 -1 不 rasterize
        } catch (Throwable t) {
            log.log(Level.WARNING, "renderStatic failed: " + t.getMessage(), t);
        }
    });
}
/** 补间结束清理 per-wall 静态 diff（防泄漏）。 */
public void clearStaticDiff(String wallId) { staticDiffs.remove(wallId); }
```
- `wallSource` / `renderer` / `scheduler` / `entries` / `FrameDiff` 都是 AnimationTicker 现有字段——照实际字段名调整。
- `TickerControl` 接口加 `renderStatic(wallId, frame)` + `clearStaticDiff(wallId)`，`TickerControl.of(ticker)` 转发。

### 步骤 2：构造插值 frame 的 helper
补间每帧要把 base state 的某些元素属性替换成插值值 → 新 `ProjectState`。找现成 immutable 重建路径：
- 优先复用 `EditSession`：`new EditSession(baseState)` → 对每个 target `es.updateElement(elementId, Map.of(property, value))` → `es.state()`。**确认 EditSession.updateElement 是纯重建无副作用**（不发包/不落 DB）；若有副作用，改用更底层的 element rebuild（参照 `KeyframeInterpolator` 怎么产临时 state）。
- frame 是 immutable，跨线程（tween 线程构造 → 传给 ticker 线程 renderStatic）安全。

### 步骤 3：`TweenScheduler`（新类 `engine/TweenScheduler.java`）
```java
public final class TweenScheduler {
    record PropTarget(String elementId, String property, double from, double to) {}
    record TweenTask(String wallId, List<PropTarget> targets, long startMs, long durationMs,
                     Easing easing, ProjectState baseState) {}

    private final ScheduledExecutorService scheduler = /* 单线程 hikari-canvas-tween daemon */;
    private final Map<String, TweenTask> active = new ConcurrentHashMap<>();  // key = wallId（一墙一补间 MVP）
    private final ElementPropertyApplier applier;
    private final TickerControl ticker;
    private final WallRepo wallRepo;
    private final LongSupplier clock;     // 注入 seam，测试用
    private final int maxConcurrent, fps;

    // 构造：scheduleAtFixedRate(this::tick, 0, round(1000/fps), MS)，catch Throwable
}
```
- **enqueue**（Runner 线程调）：
  - `active.size() >= maxConcurrent` → 返 `TraceStep.error("补间已达上限")`。
  - `wallRepo.loadById(wallId)` 读 base；遍历 targets 读 fromValue（当前属性）；toValue 从 SetElementProperties.patch 解析。
  - 元素/属性找不到 → `TraceStep.error`。
  - **同 wall 已有补间**（MVP 一墙一补间）→ 接管：取旧 task 当前值作新 from，覆盖 active.put（T8 简化版）。
  - `active.put(wallId, task)` → 返 `TraceStep.ok`。
- **tick**（tween 线程，每帧）：
  - 遍历 active：`local = clamp((now-startMs)/durationMs, 0, 1)`；`eased = EasingSolver.ease(easing, local)`；每 target `value = from + (to-from)*eased`。
  - 构造 frame（步骤 2）→ `ticker.renderStatic(wallId, frame)`（**不落 DB**）。
  - `local >= 1`：末帧 → 对每 target `applier.applyMany(wallId, blockId, elementId, Map.of(property, String.valueOf(round(to))))`（**落 DB**，目标值）+ `ticker.clearStaticDiff(wallId)` + `active.remove(wallId)`。
- **shutdown**：照范式（scheduler.shutdown + awaitTermination 5s + shutdownNow）。

### 步骤 4：`ScriptRunner` TweenBlock 分支（替换 P1 占位 :373-377）
```java
if (a instanceof Action.TweenBlock tb) {
    TraceStep step = tweenScheduler.enqueue(st.wallId, blockId, tb);   // 内部读 from/to
    st.trace.add(step);
    if ("ok".equals(step.result()) && tb.durationMs() > 0) {
        stack.push(new Frame(acts, i + 1, f.prefix()));
        Deque<Frame> cont = new ArrayDeque<>(stack);
        if (!shutdown) scheduler.schedule(() -> runFrames(st, cont), tb.durationMs(), MILLISECONDS);
        return;     // 挂起
    }
    i++; continue;   // enqueue 失败 / duration=0 → 不挂起，链继续
}
```
- `ScriptRunner` 构造注入 `TweenScheduler tweenScheduler`。
- `enqueue` 接收 tb（含 body 的 SetElementProperties），内部读 from（loadById）+ 解析 to（patch）。

### 步骤 5：`HikariCanvas` 装配
- onEnable（scriptRunner 初始化前后）：`tweenScheduler = new TweenScheduler(propertyApplier, tickerControl, wallRepo, System::currentTimeMillis, cfg.maxConcurrent, cfg.fps);` 注入 ScriptRunner。
- `cleanupResources`：`scriptRunner.shutdown()` 之后、`animationTicker.shutdown()` 之前 → `tweenScheduler.shutdown()`。
- config：`scripts.tween.{fps:20, max-concurrent:16, max-duration-seconds:60}`（P5 完善，P2 先给默认值常量或简单读 config）。

### 步骤 6：单测
- `TweenSchedulerTest`：注入 fake clock + fake applier + fake ticker。验：① enqueue 读 from/to 正确 ② tick 在 local=0/0.5/1 时 value 正确（线性 + 缓动）③ 末帧调 applier.applyMany（落 to）+ clearStaticDiff + active 清空 ④ maxConcurrent 超限返 error ⑤ 同 wall 接管（旧 from→新 from）。
- `ScriptRunner` 挂起：tweenBlock enqueue ok → schedule(cont, durationMs)；enqueue error → 不挂起继续。

**验证:** `./gradlew :plugin:compileJava` + `:plugin:test`（全绿）。**不要 commit。**

---

## 主控深度 review 重点（对抗审查）
1. **线程契约**：enqueue（Runner 线程）/ tick（tween 线程）/ renderStatic（ticker 线程）三线程跨越——`active` 是 ConcurrentHashMap？TweenTask 字段是否只单线程改？frame immutable 跨线程安全？
2. **renderStatic 不抢有 timeline 的 wall**：`entries.containsKey` 守卫对不对？有 timeline 的 wall 补间 P2 怎么办（MVP 可先 enqueue 但 renderStatic no-op + 末帧落 DB 让 Ticker 叠加；或 P2 限制只静态墙、记 TODO P3）。
3. **挂起 + 删墙/重启**：补间挂起期间 wall 被删 / 服务器重启 → cont 续接时 wall 不存在 → 安全（loadById null 处理）？TweenTask 是内存态，重启丢失（补间不跨重启，可接受，记录）。
4. **末帧落盘的 blockId**：applyMany 的 blockId 用 tweenBlock 的还是 body 子动作的？trace 定位。
5. **EditSession.updateElement 有无副作用**（步骤 2 关键）：若有发包/落 DB 副作用，每帧构造 frame 会污染——必须确认是纯重建。
6. **diff 泄漏**：staticDiffs 末帧 clearStaticDiff 清；补间中途失败/wall 删也要清。
