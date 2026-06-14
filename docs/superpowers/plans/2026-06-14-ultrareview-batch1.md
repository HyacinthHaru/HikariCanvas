# 0.7.3 ultrareview 第一批修复实施计划

> 关联审查报告：`docs/ultrareview-2026-06-14.md`（第一批 = 8 条 0.7.3/补间「静默失败」+ 崩溃，**全部为直接修**，经 4 个独立子代理读真实代码核验属实）。
> 执行方式：4 子代理并行实施（按文件无冲突分组），**不 commit、不跑 Gradle**（统一编译测试），硬条目对抗审查。
> 修完不 commit，等用户游戏内实测 P1-1/P1-2/P1-5 后再 commit。

**Goal:** 修复 0.7.3 补间动画 + 4 新积木里 8 条「功能坏了但不报错」的缺陷，补齐 0.7.3 漏接的线。

**Architecture:** 纯逻辑修复，**不动数据结构/协议/玩法/存量数据**。前端 1 组 + 后端 3 组，共 4 并行批次，各改独立文件无冲突。

**Tech Stack:** Java 21 / Paper；Vue 3 + TS；后端 JUnit + MockBukkit，前端 vitest。

**契约对照（改前必读相关段）：** `docs/scripting-0.7.3.md §G2/决策2`（置顶置底走双路径）、`docs/scripting-tween.md §2.2/T4/T8`（补间落盘顺序 / 变色 / 接管）、`web/src/script/model/validator.ts` 文件头（前后端逐字段一致纪律）。

---

## 并行批次划分（文件无重叠）

| 批次 | 任务 | 改动文件 | 模型 |
|---|---|---|---|
| **A 补间引擎** | Task 7 + Task 8 | `render/AnimationTicker.java`、`script/engine/TweenScheduler.java` | opus（线程契约/深拷贝，硬） |
| **B 脚本积木逻辑** | Task 4 + Task 5 + Task 6 | `script/ScriptRuleValidator.java`、`web/ScriptOpDispatcher.java`、`script/engine/ActionExecutor.java` | sonnet |
| **C 置顶置底接线** | Task 1 | `HikariCanvas.java`、`session/SessionManager.java` | opus（多文件接线，硬） |
| **D 帧率 op + 变色补间** | Task 2 + Task 3 | `web/WebServer.java`、`web/src/script/model/blockDefs.ts`、`web/src/script/model/validator.ts` | sonnet |

---

## Task 1 — P1-1 · setElementLayer（置顶/置底）双路径未接线

**Files:**
- Modify: `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（匿名 `SessionPatchApplier` 装配处，约 742-775）
- Modify: `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`（新增方法）
- Read（确认，不改）: `plugin/.../script/engine/ElementPropertyApplier.java`（`applySetElementLayer` 已存在）

**现状（已核验）：** 生产装配的匿名 `SessionPatchApplier` 只 override 了 `apply/nudge/clone/delete`，**没 override `reorderToEdge`** → 走接口 default 直返 `SessionOutcome.noSession()`；`SessionManager` 也没有 `applyScriptElementReorder`。因此 `doSetElementLayer → applySetElementLayer` 调 `sessionApplier.reorderToEdge` 永远拿 `NO_SESSION` → 无条件 fall-through 到 `setElementLayerHeadless`（临时 EditSession 直写 DB）。后果：编辑器开着时脚本置顶/置底也绕过活跃 session，前端收不到 reorder 的 state.patch；且 headless 路径有竞态，会被 session 后续 persist **静默覆盖丢失**。违背 `docs/scripting-0.7.3.md §G2/决策2`。

**改法：**
1. `SessionManager` 新增 `applyScriptElementReorder(wallId, elementId, mode, varPushCallback, throttler)`，**照 `applyScriptElementClone` / `applyScriptElementDelete` 的范式**：找活跃 session → 在 session 上做 reorder（置顶/置底）→ 推 reorder 的 state.patch → 返 `SessionOutcome`。
2. 匿名 `SessionPatchApplier` override `reorderToEdge`，转调上面这个方法。
3. 确认 `ElementPropertyApplier.applySetElementLayer` 的 session 分支拿到非 NO_SESSION 后正常返回（headless fallback 保持不变）。

**测试：** 加测试覆盖「编辑器 session active 时 `reorderToEdge` 返非 NO_SESSION（走 session 路径）」+「无 session 时仍走 headless」。顺带补 `D-24` 缺口：headless 落地路径 + `layer.locked → LAYER_LOCKED → error step` 路径。

---

## Task 2 — P1-5 · canvas.tweenFps op 未接入 WebServer 分发

**Files:** Modify `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（`handleMessage` 的 op 分发 switch，约 772-789）

**现状（已核验）：** `EditOpDispatcher` 已有完整 `canvas.tweenFps` handler（调 `es.setTweenFps`），前端也 `send('canvas.tweenFps',{fps})`，但 `WebServer.handleMessage` 的 op switch 列了 `canvas.resize/background/grid/guides.set` 唯独漏 `canvas.tweenFps` → 落 default 返 `INVALID_OP`。用户改帧率乐观更新本地、后端从不写入、永远卡 30fps。

**改法：** switch 在 `case "canvas.guides.set":` 后加 `case "canvas.tweenFps":` → 路由到 `editOpDispatcher.dispatch`（与同组 canvas.* 一致）。

**测试：** 加**全链路**测试——经 `WebServer.handleMessage` 发 `canvas.tweenFps` 验证 `es.setTweenFps` 真被调（现有单测只直驱 `EditSession.setTweenFps`，漏了这层 dispatch，所以 bug 没被拦）。

---

## Task 3 — P1-2 · 文字「变色到」补间彻底失效（静默）

**Files:**
- Modify: `web/src/script/model/blockDefs.ts`（`setColor` 友好积木定义，约 726-732）
- Modify: `web/src/script/model/validator.ts`（`ELEMENT_PROPERTIES` 白名单，约 62-64）
- Read（确认，**不改**）: `plugin/.../script/engine/TweenScheduler.java`（`color` 分支已存在且功能完整——已核验）

**现状（已核验）：** `setColor` 友好积木 `defaultPatch = { fill:'#FFFFFF' }`（键恒为 `fill` 而非 `color`），白名单 `ELEMENT_PROPERTIES` 只含 `fill` 不含 `color`。于是 TweenScheduler 对 TextElement 走 `fill` 分支：`readFillValue` 对 TextElement 返 null → from 兜底 `SolidFill(目标色)` → `from==to` 无中间帧；末帧把 `fill` 键发给 TextElement，`EditSession.applyTextPatch` 抛 `unknown text field: fill`。净效果：文字变色补间**既不动画也不落盘**。后端 `color` 分支/ColorTarget/readColorValue 本就齐活，只是没积木产生 `color` 键。

**改法：**
1. `blockDefs.ts` 的 `setColor` 友好积木：`defaultPatch` 改 `{ color:'#FFFFFF' }`，对应字段 key `fill` → `color`（属性下拉/表单同步）。
2. `validator.ts` 的 `ELEMENT_PROPERTIES` 白名单加 `'color'`。
3. **只改前端**。确认（只读）TweenScheduler color 分支端到端可达即可；**若发现后端 color 路径真有问题，停下报告，不要改 TweenScheduler.java**（那是批次 A 的文件，避免冲突）。

**测试：** 前端 vitest——`setColor` 积木产出 patch 的键为 `color`、validator 放行；blockDefs 的 setColor def 断言。

---

## Task 4 — P2-1 · RandomBranch 嵌套深度校验前后端不一致（6 单元命中，置信度最高）

**Files:** Modify `plugin/src/main/java/moe/hikari/canvas/script/ScriptRuleValidator.java`（`RandomBranch` case，约 484-493）
**对齐目标（只读）：** `web/src/script/model/validator.ts`（`randomBranch` 约 577-582）、`web/src/script/model/blockTree.ts`（`isIf` 约 58-61）

**现状（已核验）：** 后端对 `RandomBranch` 用 `validateActions(then, ifDepth)` / `validateActions(else, ifDepth)`——`ifDepth` **不递增、不查 `MAX_IF_DEPTH`**（当成非条件容器）；前端把 `randomBranch` 当 `if`：`depth = ifDepth + 1`、查 `depth > MAX_IF_DEPTH`、递增递归。两端语义相反 → `RandomBranch` 外包 4 层 If 时后端算 depth=4（放行）、前端算 depth=5（编辑器红字阻止保存），**编辑器拒绝服务端本会接受的合法规则**。后端 `countBlocks` 还把它当 if 计数（自相矛盾）。

**改法：** 后端 `validateAction` 的 RandomBranch 分支改为 `validateActions(then, ifDepth + 1)` / `validateActions(else, ifDepth + 1)`，并在递归前加 `if (ifDepth + 1 > MAX_IF_DEPTH)` 报错（镜像前端 + If 的对待）。

**测试：** 加测试「RandomBranch 外包 4 层 If → 后端算 depth=5 拒」+「3 层放行」，把行为固化（报告指出后端对此零测试覆盖）。

---

## Task 5 — P2-2 · 保存期条件预解析（K16）漏 RandomBranch 分支

**Files:** Modify `plugin/src/main/java/moe/hikari/canvas/web/ScriptOpDispatcher.java`（`checkConditionSyntax` 递归，约 438-478）

**现状（已核验）：** `checkConditionSyntax` 递归只下钻 `If.then/else`、`Repeat.body`、`RepeatUntil.body`，**没递归进 `RandomBranch.then/else`**；而 RandomBranch 分支里完全可放 `If/WaitUntil/RepeatUntil`（都带 condition）。后果：嵌在 RandomBranch 里的坏条件绕过保存期预检，运行期 `ConditionEvaluator` 对解析失败条件静默返 false（K16 要防的失败模式）。

**改法：** `checkConditionSyntax` 的递归里追加 `else if (a instanceof Action.RandomBranch rb)` → 递归 `rb.then()` 和 `rb.elseActions()`（RandomBranch 自身无 condition 字段，不检查自身）。

**测试：** 加测试「RandomBranch 分支里的坏条件被保存期预检拒（返非空错误）」。

---

## Task 6 — P2-3 + P2-4 + P3-17 · 非有限数值污染变量库 / roundVariable 绕过 StrictNumber

**Files:** Modify `plugin/src/main/java/moe/hikari/canvas/script/engine/ActionExecutor.java`（`formatNumber` 约 266-272、`doRoundVariable` 约 702）

**现状（已核验）：** `formatNumber(double v)` 只在整数分支查 `Double.isFinite`，非有限 v 落 else `String.valueOf(v)` → 产 `"Infinity"`/`"NaN"` 写进变量库 → Compositor `${var:X}` 直接渲染到墙。`doRoundVariable` 用 `Double.parseDouble(raw.trim())`（接受 `"NaN"`/`"Infinity"`/`"0x1p4"`/`"5d"`），绕开 `StrictNumber`；NaN/Infinity 在 floor/ceil/round 三 mode 下产**不同脏字符串**（floor/ceil(NaN)→"NaN"、round(NaN)→"0"、round(Inf)→"9223372036854775807"）。`docs/scripting-0.7.3.md §G3` 字面写「Double.parse」但同段又说「照 ScaleVariable」（用 StrictNumber）——文档自相矛盾，**按 StrictNumber 这条统一**。

**改法：**
1. `formatNumber` 开头加 `if (!Double.isFinite(v)) return "0";`（兜所有调用方：doIncrement/doScale/doSetRandom/doRoundVariable）。
2. `doRoundVariable` 的 `Double.parseDouble(raw.trim())` 换成 `StrictNumber.parse(raw)`；解析失败/非有限走 error step（与同族 doIncrement/doScale 一致）。

**测试：** `formatNumber` 非有限 → `"0"`；`doRoundVariable` 对 `"NaN"`/`"Infinity"`/`"0x1p4"`/`"5d"` 的行为（对齐 StrictNumber 语义）；补 `D-24` 的 round 半值方向（`2.5→3`、`-2.5→-2` 的 half-up 非对称）。

---

## Task 7 — P2-6 · clearStaticDiff 破坏线程契约 → 孤儿 FrameDiff 泄漏

**Files:** Modify `plugin/src/main/java/moe/hikari/canvas/render/AnimationTicker.java`（`clearStaticDiff` 约 435-436；`staticDiffs` 字段 约 151-154）

**现状（已核验）：** `staticDiffs` 注释声明「仅 Ticker 线程读写」。`renderStatic` 正确把操作投递到 Ticker 线程（`scheduler.execute`），但 `clearStaticDiff` **直接在调用线程**执行 `staticDiffs.remove`，无 trampoline。补间末帧序列：tween 线程先 `clearStaticDiff`（remove），再 `renderStatic`（异步投 Ticker 线程 → 在 Ticker 线程 `computeIfAbsent` 新建空 FrameDiff）→ 该条目永不被清。每个静态墙补间完成泄漏一条（持 `byte[mapCount][16384]`，约 1MB/8×8 墙），到该墙再补间或插件关停才回收。

**改法：** `clearStaticDiff` 改为 `scheduler.execute(() -> staticDiffs.remove(wallId))`，保证排在 `renderStatic` 任务之后执行。检查 `TweenScheduler.shutdown` / 其它调用点是否依赖同步语义（若有，确认改异步无破坏）。

**测试：** 加测试验证「末帧 renderStatic + clearStaticDiff 后 staticDiffs 不残留该 wall」（或验证两个任务的投递顺序）。

---

## Task 8 — P3-16 · renderStatic 接收的插值帧是原地 mutate 的共享 baseState

**Files:** Modify `plugin/src/main/java/moe/hikari/canvas/script/engine/TweenScheduler.java`（`buildInterpolatedFrame` 约 572-580）

**现状（已核验）：** `buildInterpolatedFrame` 用 `new EditSession(task.baseState())`，而 `EditSession` 构造器**按引用**持有 state、`updateElement` 通过 `elements().set(idx, updated)` **原地 mutate** 这个 ArrayList；`es.state()` 返回同一 `baseState` 对象。`tickOne` 把此 frame 传 `ticker.renderStatic` **异步在 Ticker 线程**读，而 tween 线程下个 tick 继续原地改同组 Layer ArrayList → 对非线程安全 ArrayList 并发 set/iterate → **撕裂读**（渲染到半更新元素）。Javadoc 称「frame immutable record 跨线程安全」前提不成立（ProjectState 内层是可变 ArrayList）。高 tweenFps（60，cadence≈16ms）窗口尤明显。

**改法：** `buildInterpolatedFrame` 构造 EditSession 前先**深拷贝** `task.baseState()`（复用 `ProjectSnapshot.deepCopy` 或等价深拷贝路径，确保 layers + elements 都是独立副本），每帧操作独立副本，不污染 baseState、不与 Ticker 线程共享可变结构。

**测试：** `TweenSchedulerTest` 验证「连续两次 `buildInterpolatedFrame` 不污染 `task.baseState()`」（baseState 元素值在多 tick 后保持原值）；确认现有 TweenScheduler 测试 baseline 不漂移。

---

## 收尾（由分配者统一执行，非子代理）

1. 后端：`rm -rf web/dist plugin/build/generated/web-resources` 后 `./gradlew :plugin:compileJava :plugin:test`。
2. 前端：`cd web && ./node_modules/.bin/vitest run` + `./node_modules/.bin/vite build --clearScreen false`。
3. 对抗审查硬条目：Task 1（接线竞态）/ Task 7（线程契约）/ Task 8（深拷贝完整性）。
4. 汇报：哪些单测已验、哪些需用户游戏内验（P1-1 置顶置底 / P1-2 变色补间 / P1-5 帧率）。
5. **不 commit**，等用户实测确认后再 commit + 写 journal + push。
