# HikariCanvas 深度审查报告 — 2026-06-14

> 本文为一次独立的全栈深度代码审查（ultrareview）的问题清单。**只如实陈述问题本身（现象、原因机理、可能后果），不含修复方案、不指定修法。** 具体怎么改由维护者决定。

## 审查方法与口径

- **方式**：多代理并行审查。把代码库切成 30 个审查单元（按子系统 × 横切关注点划分，并故意让横切单元与子系统单元重叠以多视角覆盖）。每个单元一个审查代理寻找缺陷；每条发现再派一个独立的对抗式验证代理，读真实代码 + 读项目文档，判定其为「真问题 / 误报 / 文档已取舍 / 设计待定」。全程只读，未改动任何代码。
- **规模**：159 个代理、约 1580 万 token、约 59 分钟。
- **判定结果**：129 条原始发现 → **54 条确认为真问题** / 25 条设计层面待定 / 21 条误报 / 29 条文档已明确取舍。
- **本文收录范围**：仅收录「确认为真问题」与「设计层面待定」两类。**已剔除全部 21 条误报与 29 条文档已取舍项**（后者多属「工具不是保姆」哲学下的刻意设计：不自动降级、安全边界刻意放宽、某些防御留待 v1.x 等）。
- **去重说明**：同一问题被多个审查单元各自独立发现的，已合并为一条，并标注独立命中次数。54 条确认问题去重后约 47 个不重复问题。

## 总体观察

确认的问题**高度集中在 0.7.3 当前正在进行（尚未提交）的新代码**——补间动画引擎（`TweenScheduler`）与 0.7.3 四个新积木（尤以 `RandomBranch` 的双端校验分叉，被 6 个不同审查单元各自独立撞上）。成熟模块（变量系统、会话/地图池、协议协商、存储）整体扎实，确认问题多为次要边界。一个反复出现的模式是**「静默失败」**：乐观更新或异常隔离吞掉了错误，用户无可见反馈（如补间帧率改不动、文字变色无效、防抖串写、关闭编辑丢改动等）。

---

## 一、严重级（P1）— 崩溃 / 数据丢失 / 功能完全失效

### P1-1 · `setElementLayer`（置顶/置底）双路径未接线，编辑器开着时也只走 headless，触发数据丢失竞态
**位置**：`HikariCanvas.java`（匿名 `SessionPatchApplier` 装配处）+ `script/engine/ElementPropertyApplier.java`

生产装配的匿名 `SessionPatchApplier` 覆盖了 apply / nudge / clone / delete 四个方法，但**没有覆盖 `reorderToEdge`**。该接口方法的 default 实现直接返回 `SessionOutcome.noSession()`。因此 `doSetElementLayer → applySetElementLayer` 在调用 `sessionApplier.reorderToEdge` 时永远拿到 `NO_SESSION`，从而无条件 fall-through 到 `setElementLayerHeadless`（用临时 `EditSession` 直写 DB）。`SessionManager` 里也确实没有对应的 `applyScriptElementReorder` 方法。

后果：(1) 即使墙的编辑器正开着，脚本触发的置顶/置底也绕过活跃 session，前端收不到 reorder 的 state.patch，与 clone/delete/nudge 的实时可见行为不一致；(2) 命中 headless 路径注释里自承的竞态——编辑器 session 之后用旧 state 做 persist 时，会把这次 reorder **静默覆盖丢失**。这直接违背 `docs/scripting-0.7.3.md §G2/决策2` 明确承诺的「走 `ElementPropertyApplier` 双路径（session-patch / headless），照 clone/delete 的结构」。

### P1-2 · 文字元素「变色到」（setColor）补间彻底失效（静默）
**位置**：`script/engine/TweenScheduler.java`（buildTarget）+ `web/src/script/model/blockDefs.ts`（setColor 友好积木）
> 被 2 个审查单元独立发现（补间引擎单元 + 横切边界单元）。

设计总纲 T4 把「变色到」列为 `TextElement` 的核心补间能力。但前端 setColor 友好积木的 defaultPatch 是 `{ fill: '#FFFFFF' }`，即它产生的 `SetElementProperties.patch` 键恒为 `fill` 而非 `color`；校验白名单 `ELEMENT_PROPERTIES` 也只含 `fill` 不含 `color`。因此 `TweenScheduler.buildTarget` 永远走 `fill` 分支：对 `TextElement`，`readFillValue` 返回 null（TextElement 不在其 switch 里），于是 from 兜底成 `new SolidFill(目标色)`，得到 `from == to` 的 FillTarget——**中间帧无任何插值**。补间结束时 `formatFinalValue → fillToString → applyMany` 把 `fill` 键发给 TextElement，`EditSession.applyTextPatch` 对未知键 `fill` 抛 `ValidationException("unknown text field: fill")`，末帧落盘以 error step 失败（仅被 catch 记 WARNING 日志）。

净效果：文字颜色补间**既不动画也不落盘，静默失败**。连带 `buildTarget` 的 `color` 分支、`ColorTarget`、`readColorValue`、`isTweenableProperty` 里的 `color` case 全是不可达死代码——没有任何积木会产生 `color` 键。

### P1-3 · 动画关键帧 w/h 不受 `MAX_DIM` 约束，icon tint / image feather 按原始尺寸分配离屏 buffer → 渲染线程 OOM
**位置**：`render/IconRenderer.java`（tint 分支）、`render/ImageRenderer.java`（drawWithFeather）；上游 `TimelineOperations.parseValue`

关键帧数值（含 w/h）在 `parseValue` 只校验有限性（拒 NaN/Inf），不做 `MAX_DIM(10000)` / `MAX_COORD` 上限校验；`KeyframeInterpolator → StrictNumber.clampInt` 只钳到 Integer 范围（不钳画布/MAX_DIM）。这些值随后进入渲染器：`IconRenderer` 在 tint 分支用 `new BufferedImage(ic.w(), ic.h(), …)`、`ImageRenderer.drawWithFeather` 用 `new BufferedImage(w, h, …)`，按**原始 element w×h** 分配离屏缓冲，无画布裁剪、无 MAX_DIM 上限。（dither 路径按 `clip∩canvas` 分配是安全的，但 tint 与 feather 这两条非 dither 离屏路径不是。）

后果：把一个带 tint 的 icon、或带 feather mask 的 image 的 w/h 关键帧到约 50000（finite，校验放行），单帧即分配数 GB → `AnimationTicker`（owner 线程）`OutOfMemoryError`，连带池化与全墙动画一起垮。即便不经动画，直接创建受 MAX_DIM=10000 约束的元素已是约 400MB 的瞬时分配（既存问题），而动画路径连这道 10000 的闸都绕过。

### P1-4 · `Timeline.tracks` 的 `List.copyOf` 对含 null 元素的轨道会 NPE，使整面墙加载失败
**位置**：`state/Timeline.java`（canonical 构造器）

`Timeline` 的 canonical 构造器对每条轨道做 `List.copyOf(...)`，而 `List.copyOf` 对包含 null 元素的列表抛 `NullPointerException`。`Timeline` 走默认 Jackson record 反序列化（无自定义 deserializer），`WallRepo` 直接 `readValue(project_json, ProjectState.class)` 加载持久化 blob。若某条轨道在持久化 JSON 里出现 `[null]`（数据损坏 / 手工编辑 / 历史 bug 写入），反序列化在构造器即 NPE，导致**整面墙无法加载**。

这正是 `Easing` record 专门加防御去规避的同类故障（文档明确写过「`List.copyOf` 会 NPE 致整面墙加载失败，违反『坏数据不在反序列化期抛硬错』的承诺」），但 `Timeline.tracks` 没有得到同样的 null-元素守卫。同理，`KfValueDeserializer` 对 boolean / array 形态的 value 直接抛 `JsonMappingException`，也会冒泡导致整个 `ProjectState` 加载失败。

### P1-5 · `canvas.tweenFps` op 未接入 WebServer 分发，前端改补间帧率静默失败
**位置**：`web/WebServer.java`（handleMessage 的 op 分发 switch）

补间动画 per-wall 帧率 op `canvas.tweenFps` 在 `EditOpDispatcher` 里已有完整 handler（调 `es.setTweenFps`），前端也通过 `send('canvas.tweenFps', { fps })` 直接发，但 `WebServer.handleMessage` 的 op 分发 switch（列出所有 `editOpDispatcher` 路由的 op）**漏掉了 `canvas.tweenFps`**。同组的 `canvas.resize` / `background` / `grid` / `guides.set` 都在列表内，唯独 tweenFps 不在。结果该 op 落到 default 分支返回 `INVALID_OP: unknown op: canvas.tweenFps`。

后果：用户在脚本编辑器调「动画帧率」控件时，乐观更新会把前端 store 改掉（代码注释明确说「失败无回滚——state.patch 会覆盖回来」），但后端从未真正写入 `ProjectState.tweenFps`，也从不回推 state.patch，于是补间帧率**永远停留在默认 30fps，服主主动设的帧率被静默丢弃**，与文档「per-wall 帧率可调」承诺不符。根因之一是单测只直接驱动 `EditSession.setTweenFps`，从不经过 `WebServer.handleMessage` 的全链路 dispatch，因此漏网。这正是 0.7.3 当前未提交改动涉及的功能。

### P1-6 · ImageIO 解码无尺寸预检：超大像素声明在单次分配阶段触发 OOM，200ms 超时拦不住
**位置**：`image/UploadHandler.java`（decodeCooperative）；`ImageStorage.load`（decodeFileCooperative）同样

`decodeCooperative()` 拿到 `ImageReader` 后直接 `reader.read(0)` 一次性解码整张图。bbox sanity（w/h ≤ 8192）在解码**之后**才校验。问题在于：一张体积很小、但 IHDR/SOF 头声明巨大尺寸的合法 PNG/JPEG/WebP（例如 30000×30000，文件只有几 KB，可通过大小、Content-Type、magic bytes 三道校验）会在 `reader.read(0)` 内部分配约 3.6GB 的 raster。该分配是单次原生操作，**200ms 的 `Future.get` 超时 + 跨线程 `reader.abort()` + `Thread.interrupt` 都无法打断一次正在进行的数组分配**——要么撑爆堆，要么直接抛 `OutOfMemoryError` 拖垮整服。

`security.md §4.5` 把「解码成功后做 8192² sanity」+「200ms 超时」作为压缩炸弹缓解，但这两者都只能拦 CPU 耗时型炸弹，拦不住**分配型炸弹**（声明大尺寸）。`onEnable` 的 IIORegistry 注销只移除了 GIF/BMP/TIFF，对 allowed 的 PNG/JPEG/WebP 这条分配路径无保护。bbox 检查永远来不及执行。

### P1-7 · 属性面板 80ms 防抖更新会写到「切换后」的另一个元素（跨元素串写）
**位置**：`web/src/components/layout/RightPanel.vue`（sendUpdate / sendUpdateDebounced）

`sendUpdate(patch)` 在执行时实时读取 `selected.value`（当前选中元素），且 patch 里不含 elementId；`sendUpdateDebounced = useDebounceFn(sendUpdate, 80)` 只捕获 patch，不捕获触发输入时的元素身份。`TransformSection` 的 x/y/w/h/letterSpacing/lineHeight/fontSize 等数值输入、`TextElementSection` 的文本输入都走该防抖更新。

典型路径：在元素 A 的 x 输入框敲一个值后 80ms 内立即点选元素 B——待 flush 的防抖回调执行时 `selected.value` 已是 B，于是把 **A 的输入值写进了 B**（既乐观本地 mutate，也 `ws.send element.update` 到 B.id）。vueuse 的 `useDebounceFn` 在 selected 变化时不会取消 pending 调用。opacity 滑块同理，但因有 `@change` 立即收口风险较低。

---

## 二、重要级（P2）— 逻辑错误 / 数据完整性 / 双端不一致

### P2-1 · `RandomBranch` 嵌套深度校验前后端不一致（编辑器拒绝、服务端接受）
**位置**：`script/ScriptRuleValidator.java`（RandomBranch case）vs `web/src/script/model/validator.ts`（randomBranch case）+ `blockTree.ts`（isIf）
> **被 6 个审查单元各自独立发现**（脚本数据模型、ScriptRunner、合成、前端积木模型、协议类型、横切边界），是本次置信度最高的发现。

0.7.3 文档说 `RandomBranch` 照 `Action.If` 实现，但两端对 if 嵌套深度（`MAX_IF_DEPTH = 4`）的处理分叉了：
- 后端 `ScriptRuleValidator` 对 RandomBranch 用 `validateActions(then, ifDepth)` / `validateActions(else, ifDepth)`——`ifDepth` **不递增**、也**不做 MAX_IF_DEPTH 检查**（与 Repeat/RepeatUntil/TweenBlock 相同，把它当成非条件容器）。
- 前端 `validator.ts` 对 randomBranch 做 `depth = ifDepth + 1`、检查 `depth > MAX_IF_DEPTH` 并以递增后的 depth 递归（与 if 完全相同对待）。`blockTree.ts` 的 `isIf` 守卫也已把 randomBranch 计入深度。

后果：一条 `RandomBranch` 外包 4 层嵌套 `If` 的规则——后端算到 depth=4（合法），前端算到 depth=5（在编辑器里红字报「分支嵌套超过 4 层」并阻止保存）。即**编辑器会拒绝服务端本会接受的合法规则**；反之，由于后端是唯一执行器，经 API / `.canvas` 导入 / 旧前端构造的深层 randomBranch 规则可绕过前端这道闸被后端接受并执行。两端校验语义相反，违反 `validator.ts` 文件头「逐字段复刻、任一漂移都会让前端放行后端打回（或反之）」的约定。

值得注意的是后端**自身内部也矛盾**：同文件 `countBlocks` 把 RandomBranch 当 if 计数（注释「同 If」），唯独 depth 维度当成 repeat；前端单测已把「计入深度」固化成断言，而后端对此行为零测试覆盖——典型的未察觉漂移。

### P2-2 · 保存期条件预解析（K16）漏掉 `RandomBranch` 分支，坏条件可保存、运行期静默恒 false
**位置**：`web/ScriptOpDispatcher.java`（checkConditionSyntax 递归）

K16 契约要求：所有 `if.condition` / `waitUntil.condition` / `repeatUntil.condition` 在保存期就做 parse-only 预检，坏条件保存时即拒，不等运行期静默 false。但 `checkConditionSyntax` 的递归只下钻 `If.then/else`、`Repeat.body`、`RepeatUntil.body`，**没有递归进 0.7.3 新增的 `RandomBranch` 的 then/else**。而 RandomBranch 的分支里完全可以放 If / WaitUntil / RepeatUntil（它们都带 condition）。

后果：嵌在 RandomBranch 里的坏条件绕过保存期预检。运行期 `ConditionEvaluator` 对解析失败的条件静默返回 false——正是 K16 要防的失败模式：if 永远走 else、waitUntil 永远等到超时、repeatUntil 永远不满足只靠 maxIterations 兜底，且无任何报错。爆炸半径小（仅嵌在随机分支里的语法错误条件），但属契约违反 + 静默逻辑损坏。

### P2-3 · `formatNumber` 不过滤非有限结果，increment/scale/round 可把 "Infinity"/"NaN" 字面量写入变量库
**位置**：`script/engine/ActionExecutor.java`（formatNumber 及其调用点）

`formatNumber(double v)` 只在「整数格式化」分支检查 `Double.isFinite`；非有限的 v 落到 else 分支 `String.valueOf(v)`，产出 `"Infinity"`/`"-Infinity"`/`"NaN"`。`doIncrement`（base+delta 两个有限 double 相加可溢出到 Infinity，如 `1e308 + 1e308`）、`doScale`（base×factor 可溢出）等都把 `formatNumber` 输出直接 `store.setValue` 写回；而 `VariableStore.setValue` 只校验长度，不拒非有限字面量。

后果：变量库被污染为 `"Infinity"`/`"NaN"` 字符串。数值消费方（`StrictNumber.parse`）会把它们当 0 自愈，但**文本消费方（Compositor 的 `${var:X}` 字面替换）会在墙上直接渲染出 "Infinity"/"NaN" 文本**——与 0.6 数值链「非有限 → 0.0」的全局语义不一致。

### P2-4 · `doRoundVariable` 用 `Double.parseDouble` 解析变量值，绕开 StrictNumber 严格文法
**位置**：`script/engine/ActionExecutor.java`（doRoundVariable）

`doRoundVariable` 用 `Double.parseDouble(raw.trim())` 解析变量当前值，而同族的 `doIncrement` / `doScale` 都用 `StrictNumber.parse`。`StrictNumber` 类注释明确把「任一处私自 `Double.parseDouble`（接受 `0x1p4` / 尾随 `d|f`）」列为禁止项，因为会造成解析分叉。具体差异：变量值为 `"NaN"` 时 `parseDouble` 返 NaN → `Math.floor(NaN)=NaN` → 写回 `"NaN"`（StrictNumber 本会返 0）；变量值为 `"Infinity"`/`"0x1p4"`/`"5d"` 时 parseDouble 成功而 StrictNumber 文法拒绝。这让 roundVariable 对同一变量值的处理与脚本引擎其余数值动作不一致，并进一步污染变量库。（注：`docs/scripting-0.7.3.md §G3` 字面写了「Double.parse」，但同段又说「照 ScaleVariable」，两者矛盾——ScaleVariable 用的是 StrictNumber。）

### P2-5 · 脚本挂起续接时刻早于补间末帧落盘，后续动作可能读到补间前的旧值
**位置**：`script/engine/ScriptRunner.java`（TweenBlock continuation 调度）

`ScriptRunner` 处理 `TweenBlock` 时按 `tb.durationMs()` 精确调度续接（在 Runner 自己的 SES 上）。但补间的末帧落盘发生在 `TweenScheduler` 的独立 SES 上、在「`now - startMs >= durationMs`」的那次 tick——由于 tween SES 以固定 cadence（最高 60fps → 约 16ms）触发，末帧落盘最晚可比续接晚约一个 cadence（约 16-50ms）。在静态墙上补间中间帧不落 DB，故续接先跑、若后续动作 headless 读元素当前值（如 nudgeElement 读 x/y、waitUntil/if 读元素几何），会读到**补间前的 DB 旧值**而非已补到的目标值。两个 SES 的起点也略有偏差。设计总纲 §2.2 本就是「补间完 → 落盘 → 续接脚本」的顺序，当前实现把续接和落盘解耦成两个独立定时器。

### P2-6 · `clearStaticDiff` 在调用线程同步移除，破坏 staticDiffs「仅 Ticker 线程访问」契约 → 孤儿 FrameDiff 泄漏
**位置**：`render/AnimationTicker.java`（renderStatic / clearStaticDiff）
> 被 2 个审查单元独立发现（AnimationTicker 单元 + 补间引擎单元）。

`staticDiffs` 字段注释明确声明「仅 Ticker 线程（scheduler）读写……线程限定安全」。`renderStatic` 正确地把操作投递到 Ticker 线程，但 `clearStaticDiff` 直接在调用者线程（tween / Runner / 关停线程）上执行 `staticDiffs.remove`，没有 trampoline，违反单线程限定契约。

`ConcurrentHashMap` 保证不损坏 map，但产生确定性的孤儿泄漏：补间末帧的执行序列为——(1) `ticker.renderStatic(finalFrame)` 入队 Ticker 线程任务 R（R 内 `computeIfAbsent` 取/建 diff 再 renderFrame）；(2) 紧接着同步调 `ticker.clearStaticDiff` 在 tween 线程立刻 remove。由于 R 尚未执行，clearStaticDiff 先 remove，随后 R 运行 `computeIfAbsent` **又新建一个空 FrameDiff** 并渲完末帧——这个新建的条目此后永不被清除。结果：每个「静态墙」补间完成都会遗留一条 `staticDiffs` 条目，其 `FrameDiff.lastFrames` 持有 `byte[mapCount][16384]`（8×8 墙约 1MB/条），直到该墙再次补间或插件关停。仅做一次补间且不再补间的墙就永久泄漏一条，并多触发一次无谓的全量重栅格。

### P2-7 · `GlowRenderer` 用 AWT FontMetrics 算 bbox，与前端 renderGlow 的 canonical 度量不一致（双端发光漂移）
**位置**：`render/GlowRenderer.java` vs `web/src/render/PreviewRenderer.ts`（renderGlow）

后端 `GlowRenderer` 计算发光外接矩形时直接读 AWT FontMetrics：`ascent = fm.getAscent()`、`descent = fm.getDescent()`、`chW = fm.charWidth(...)`。但前端 `renderGlow` 用固定规则度量：`ascent = round(fontSize * 0.8)`、`descent = fontSize - ascent`、`chW = ctx.measureText(...).width`。这正是 M20 引入 `charAdvance` / `canonicalCharWidth`（固定 0.8 ascentRatio + 规则宽度）专门要消除的 AWT-vs-Canvas FontMetrics 分叉来源。

后果：两端 bbox 的 minY/maxY（ascent/descent 不同）与 maxX（chW 不同）都会差，进而发光层落位偏移、blur padding 范围不同——**编辑器预览里的发光光晕位置/大小与游戏内实际渲染不一致**。注意主字形 fill 走的是已对齐前端的 `TextLayout.charAdvance`，唯独 glow 这条 effect 路径仍读 AWT 度量，未跟随 M20 统一。

### P2-8 · 渲染合成路径后端用 float32、前端用 float64，违反「位级一致」契约
**位置**：`render/BlendModes.java` vs `web/src/render/BlendModes.ts`

`rendering.md §6.6` 把 BlendMode + layer.opacity 合成路径定义为「两边算出来的 RGB 应该位级一致」的硬契约（与禁抗锯齿同级）。但后端 `BlendModes` 全程用 float32（`srcByte/255f`、`float layerOpacity`、`Math.round(b*255f)` 等），前端全程用 JS number（float64）。multiply/screen/overlay 的乘积与 source-over 除法在 float32 与 float64 下尾数精度不同（24-bit vs 52-bit），当中间结果落在 `k+0.5` 附近时两端 `Math.round` 可能取不同整数，得到差 ±1 的通道值。

该路径仅在 `layer.opacity ≠ 1` 或 `blendMode ≠ normal` 的慢路径触发。差 ±1 RGB 经 5-bit LUT 量化后多数会落回同一调色板格、被容差吸收，但在 **LUT cell 边界附近的像素会跨格 → 真实可见的双端预览 vs 游戏内不一致**。本仓库别处（ColorLerp 显式用 double）正是为消除这类分叉。

### P2-9 · migration 前自动备份用 `Files.copy` 复制 WAL 模式的 data.db，不 checkpoint，备份可能不一致/缺数据
**位置**：`storage/MigrationRunner.java`（tryBackup）

`Database` 用 `journal_mode=WAL` 打开连接池，且在 migration 之前已建立连接并写过 `schema_version` 表，因此运行时 data.db 旁会有 `-wal` / `-shm`，最近提交的数据可能仅存在于 `-wal` 中尚未 checkpoint。`tryBackup()` 用 `Files.copy(dbFilePath, backup)` 只复制主 data.db，既不先 `wal_checkpoint`，也不复制 `-wal`/`-shm`。

后果：(1) 备份缺失只在 WAL 里的已提交数据；(2) 运维若用该 `.bak` 恢复（只替换 data.db），残留的旧 `-wal` 会被 SQLite 重放叠加到旧主库上，产生不一致甚至损坏。这个备份本是 destructive migration 前的最后兜底，恰恰在最需要它正确时给出脏拷贝。当前默认 `databaseAutoBackup=false`（pre-release 关闭）所以是 dormant 风险，但注释里明确写了「0.1.0 发版后建议开」，届时会激活。

### P2-10 · `detectLeaks` 异步路径在缓存 miss 时调 `Bukkit.getWorld`，违反主线程纪律
**位置**：`pool/MapPool.java`（offerFreeByName，由 detectLeaks 调）

`detectLeaks()` 由插件经 `runTaskTimerAsynchronously` 在异步线程每 5 分钟跑。它对每个泄漏的 RESERVED map 调 `offerFreeByName`，后者先查 `worldNameToUid` 内存缓存，缓存 miss 时 fall-through 到 `Bukkit.getWorld(worldName)`——这是 Bukkit API，只允许主线程调用。该缓存只在 `offerFree()` 与 `reclaimUnknownBucketForWorld()` 填充，而 `initialize()` 对 RESERVED 持久化行只 `byId.put`、不 `offerFree`。

后果：一个 world 在本进程启动后其池内全部 map 都是 RESERVED 且本会话期间没做过任何 FREE/reserve/reclaim 时，缓存里没有该 world 条目；此时该 world 里某 RESERVED map 的 owner wall 被删，`detectLeaks` 标记 leaked → `offerFreeByName` 缓存 miss → **在异步线程调 Bukkit API**，可能抛异常 / 读到不一致状态，破坏 `idcounts.dat` 防膨胀这条核心防线（异常会让该 map 不被归还）。这回归了 `MapPool` javadoc 声称的「detectLeaks 走内存缓存而不调 Bukkit API」不变式。

### P2-11 · 非 raw_state 模板路径完全跳过元素校验，产出越界/超长/非法元素写入 ProjectState
**位置**：`template/TemplateInstantiator.java`（stack/free/grid → materialize → replaceContent）

raw_state 路径在 P0-23 显式加了 `validateElementForTemplateApply`，但常规布局路径（stack/free/grid）完全没有任何元素级校验。`materialize` 直接用模板/插值得到的原始值构造元素：
- 文本 content 经 Interpolator 插值后最长可达 16 KiB（远超 `MAX_TEXT_LEN=256`）；
- 坐标/尺寸由 layout 计算，可超 `MAX_COORD`/`MAX_DIM=10000` 或为负；
- TextElement 的 rotation、lineHeight（无 clamp，可超 [0.5,4.0]）、letterSpacing、fontSize 都不经对应校验。

`replaceContent` 只把 elements 塞进新 layer，不做任何校验。渲染层有 `w/h<=0` 与 finite 兜底不会崩，但这些非法元素会被 persist 进 `.canvas` 文件，且后续被普通 `element.update` 编辑时行为异常。与 raw_state 路径的安全保证不对称。

### P2-12 · 模板尺寸解析 `Integer.parseInt` 对超 int 数字串抛异常 + 百分比 `(int)` 收窄静默回绕
**位置**：`template/TemplateInstantiator.java`（resolveDimension / resolveDimensionWithAuto）

`INT_NUMERIC = ^-?\d+$` 匹配任意长度数字串。当 `${param}` 插值出 `"99999999999"`（合法匹配但溢出 int），`Integer.parseInt(s)` 抛 `NumberFormatException`（被上层捕获映射成 INVALID_TEMPLATE 不会崩，但把 JDK 原始 parse 文案外泄）；更隐蔽的是百分比分支：`Double.parseDouble` 对超大百分比得 Infinity 或巨值，`Math.round(contentBasis * pct)` 得 `Long.MAX_VALUE`，再 `(int)` 收窄**静默回绕**成任意值，产出极端/负向尺寸坐标，且不经 `StrictNumber.clampInt`（项目其它数值路径已统一用 clampInt 防双端回绕分叉）。

### P2-13 · `next_departure` 暴露的是到达时刻而非发车时刻，与契约文档相反
**位置**：`variable/provider/RailScheduleProvider.java`（snapshotFields）

`snapshotFields()` 用 `departure = arrivalTime != null ? arrivalTime : departureTime`——即 `next_departure` 取的是该站的 `arrival_time`（到达时刻），fallback 才用 `departure_time`。而 `docs/dynamic-data.md §18.4` 明确规定：`next_arrival` = 该站精确「到达」时刻，`next_departure` = 该站精确「发车」时刻。

后果：对任何同时有 arrival 与 departure 的中间站，`next_arrival` 和 `next_departure` 都返回**同一个 arrival 值**，真正的 `departure_time` 永远无法被任何变量暴露。departure 与 arrival 的取值取反了。测试仅断言 key 是否 present，未校验数值，故未捕获。（`next_arrival` 字段本身是对的。）

### P2-14 · 删除被绑定的站点后，RailScheduleProvider 内存绑定快照残留，运行期永久卡在铁路接管态显示空值
**位置**：`web/RailOpDispatcher.java`（handleStationDelete）

`wall_rail_bindings.station_id` 的 FK 是 `ON DELETE SET NULL`。当用户 `rail.station.delete` 删掉某 wall 正绑定的站点时，DB 里该 binding 的 `station_id` 被置 NULL，但 `handleStationDelete` 既不调 `provider.unregisterWall` 也不重新注册——而 `RailScheduleProvider.registeredWalls` 持有的是带旧 stationId 的 binding 快照。

后果：(1) `refresh()` 持续用已删除的 stationId 查询，永远返回空 → push 空字符串覆盖所有 `schedule:*` 变量；(2) `hasWallBinding` 仍返 true，导致 `ManualScheduleProvider` 的 skipWallPredicate 持续跳过该 wall，manual 永不接手。**该 wall 屏在整个运行期显示空白，直到服务器重启**（重启时 initialize 的 `if (stationId == null) continue` 守卫才把它排除掉）。对比：`handleLineDelete` 已正确收集 boundWalls 并 unregister，station 删除路径漏了这个清理。

### P2-15 · 命令模板下拉：保存的 templateId 不在已加载列表时，参数子输入静默消失且孤儿引用无法被发现/修正
**位置**：`web/src/script/params/BlockParamInput.vue`（runCommand 复合控件）

runCommand 复合控件的模板下拉只把已加载模板渲染成 `<option>`，并用 `:value="commandValue.templateId"` 绑定原生 select。当一个已保存的积木引用的 templateId 在配置里被删/改名（服主 reload 后），加载回来的列表里没有该 id：(1) 原生 `<select>` 找不到匹配 option，浏览器显示默认成第一项（空占位），但底层数据仍是孤儿 templateId，且不触发 change，所以数据不会被纠正；(2) `selectedTemplate` 为 null → `v-if` 使所有 params 子输入消失，用户看不到也改不了已填参数；(3) 前端 validator 仅判 templateId 非空（非空就放行），不会报错，但后端运行时会拒。结果是一个看起来「空」但实际引用无效模板的积木悄悄通过校验。

### P2-16 · `closeEditing` 在校验未通过时静默丢弃用户未保存的脚本编辑
**位置**：`web/src/stores/scriptEdit.ts`（closeEditing / doSave）

`closeEditing()` 先调 `flushSave() → doSave()`，但 `doSave()` 在 `validationErrors.length > 0` 时直接 return（不发送、按设计保留 dirty）。随后 closeEditing 无条件执行 `workingCopy = null; dirty = false`。

后果：当 working copy 处于任何瞬时非法态（例如拖拽中途把容器体删空导致 actions 为空、condition 暂时为空、文本超长等）时，用户点关闭 / 切到别的规则 / 切 wall，**所有未保存改动被静默丢弃，没有任何确认或提示**。dirty 守卫只防 server 回声覆盖，挡不住本地 null 化。`selectRule` 切换规则同样命中此路径。

### P2-17 · 协议版本不匹配 close 4002 不在 terminal 集合 → 用同一不兼容版本无限重连
**位置**：`web/src/network/wsClient.ts`（onClose terminal 判定）

`onClose` 的 terminal 判定是 `code === 1000 || 4001 || 4008`，未含 4002。后端在 auth 阶段（ready 之前）遇 `client_v` 不在支持区间时会发 `VERSION_MISMATCH` 后 `close(4002)`。此时客户端从未把 `stopped` 置 true（handleReady 的 4002 自闭路径仅在收到 ready 且 accepted_v 失配时触发，但 auth 阶段拒绝根本不会发 ready），于是 `onClose(4002)` 落入非 terminal 分支 → `scheduleReconnect()`，用完全相同的 `CLIENT_V` 重连 5 次后才放弃。每次都必然再被 4002 拒，纯浪费 + 误导用户。`docs/protocol.md §6.2` 明确 4002 应视为终止态。

### P2-18 · Token 限流 close 4429 不在 terminal 集合 → 重连风暴反而加剧限流
**位置**：`web/src/network/wsClient.ts`（onClose terminal 判定）

terminal 集合写的是 `4008`（rate limit），但后端实际的 token 暴力枚举限流 close code 是 `4429`（`CLOSE_TOKEN_RATE_LIMITED`）；4008 是后端从未发出的死码。结果：被 4429 关闭后，4429 不在 terminal → 客户端继续重连，每次都消耗 token 校验配额，正好与限流器对抗。`Protocol.java` 与 journal 均明确写明「client 看到 4429 应显示『请稍后再试』而不是自动重连」——当前实现违背该契约。

### P2-19 · dither 元素 opacity 缺少 NaN/越界 clamp，与后端及普通路径防御不对称
**位置**：`web/src/render/PreviewRenderer.ts`（drawDitheredElement）

`drawDitheredElement` 应用 `element.opacity` 时只判断 `op !== undefined && op !== null && op < 1` 后直接 `ctx.globalAlpha *= op`，既不做 NaN 兜底也不 clamp 到 [0,1]。对比同文件普通路径 `drawElement` 明确做了 `!isFinite(op) ? 1 : clamp(0,1)`；后端 `CanvasCompositor.drawElementsTo` 在调用前已 `finiteOr(1) + clamp`。

后果：dither 元素若 opacity 为负值（可经模板 raw_state 反序列化绕过协议入口校验），前端会把 globalAlpha 乘成负数（浏览器行为未定义/被忽略），而后端 clamp 到 0（完全透明）→ **双端像素分叉**。（opacity>1 与 NaN 时两端恰好一致，真正分叉点是负 opacity。）

### P2-20 · resize 吸附把「正在移动的边」按其它锚点（含未移动的边）算出的 delta 应用，导致错位吸附
**位置**：`web/src/components/layout/CanvasView.vue`（boundBoxFunc）

`boundBoxFunc` 调 `snapManager.snap(...)` 拿到一个方向 delta。`snapAxis` 会在 left/center/right 三个锚点里取「离任意 candidate 最近」的 bestDelta——不区分本次 resize 实际在动哪条边。随后 `boundBoxFunc` 把该 delta 应用到正在动的那条边。

后果：拖右手柄时，若元素的 left 边恰好距某条 candidate 轴小于阈值，bestDelta 会来自「未移动的 left 锚点」，却被加到 width 上，把右边吸到一个与左对齐语义无关的位置（视觉上右边突然跳一下）。center 锚点同理。这是 resize 吸附的语义错误；单纯 drag（整体平移，三锚点 delta 一致）不受影响。

---

## 三、次要级（P3）— 边界 / 文案 / 局部一致性

### P3-1 · near/leaveRange 规则在「类型互换」竞态窗口内会用错误的边沿语义触发
**位置**：`script/engine/TriggerRouter.java`（firePlayerNear）

`PlayerNearSampler` 用快照里的 `leaveEdge` 决定是否投递（进入沿 vs 离开沿），随后调 `firePlayerNear`。但 `firePlayerNear` 不接收「是进入还是离开」这个信号，而是重新 `store.find` 拿最新规则，并按当前 trigger 的具体类型决定 source。稳态下一致，但存在竞态：若玩家进入范围触发了 enter 边（基于快照里 `leaveEdge=false` 的 PlayerNear 条目），而此刻该规则恰好被 update 成 PlayerLeaveRange，`firePlayerNear` 在主线程拿到的是「新的」PlayerLeaveRange，于是把一次「进入事件」当作「离开范围」投递（src=PLAYER_LEAVE_RANGE）。窗口极小（仅规则类型 near↔leaveRange 互换、且玩家恰好同时跨沿），不崩不损数据，但语义不正确。其他 fire 入口对「换型」是直接跳过，唯独 near 这条把「换型」当成「合法的另一沿」处理。

### P3-2 · 颜色/fill 补间「后者接管」（T8）在静态墙会跳变
**位置**：`script/engine/TweenScheduler.java`（buildTarget 的 color/fill 分支）

T8 决策要求同元素同属性再来一个补间时「取其当前值作新 from、从当前值平滑补到新目标，不跳变」。`buildTarget` 只为数值属性实现了接管（用旧补间当前插值位作新 from），但 color 分支与 fill 分支都没有接管逻辑——它们的 from 恒读 baseState（enqueue 时的 DB 快照）。在静态墙上补间中间帧从不落 DB，故 DB base 仍是补间起始前的原值。因此对一个正在变色/变 fill 的元素再发一个新的变色补间，新补间的 from 会瞬间跳回原始颜色而非当前可见的中间色，产生明显跳变，违反 T8「平滑接管」。（动画墙因每帧落 DB 反而能接管，静态墙——补间主场景——不能。）

### P3-3 · 动画墙上补间被中断（关停/异常/接管）会把元素 DB base 永久留在补间中间值
**位置**：`script/engine/TweenScheduler.java`（tickOne 的 animating 分支）

对 `isWallAnimating` 的墙，补间走每帧 `applyMany` 落 DB，把插值中间值永久写进 wall base state。若补间在末帧前被打断——关服、tickOne 抛异常被 catch 清理、或被同墙新补间接管但新补间随后又失败——元素的持久 base 会停在某个随机中间帧值，既不是起点也不是目标，重启后墙显示半截动画的定格。设计文档把「每帧落 DB」作为共存代价接受，但未讨论中断后的中间态残留。

### P3-4 · TweenScheduler 未注册 wall 删除钩子，删墙后条目滞留至补间时长结束
**位置**：`HikariCanvas.java`（addWallDeleteHook 注册处）+ `script/engine/TweenScheduler.java`

`SessionManager.addWallDeleteHook` 为 `AnimationTicker`（`ticker::stopWall`）、`TimelineTriggerRegistry`、`ScriptStore`、变量索引等都注册了删墙清理，但**没有为 TweenScheduler 注册任何钩子**。当一面墙在补间进行中被删除时，`TweenScheduler.active` 仍持该 wallId 的任务，tick 继续按缓存的 baseState 推进，每帧 `applyFn` 落到已删除 wall（loadById 返 null 优雅返回，这部分安全）。后果是有界泄漏（最长一个补间时长）+ 每帧无谓的 DB loadById 与 warning 日志刷屏。与 AnimationTicker 在删墙瞬间立即注销的纪律不一致。

### P3-5 · 补间末帧在 animating 墙分支不清 staticDiff，静态→动画切换遗留的条目无法回收
**位置**：`script/engine/TweenScheduler.java`（tickOne 末帧 `if(!animating)` 包裹）

`tickOne` 在 animating 为 true 时整条末帧路径都不调 `clearStaticDiff`。若某墙补间初期为静态（`renderStatic` 已在 staticDiffs 建过条目），中途用户开启 timeline 使其变为 animating，则补间末帧走 animating 分支跳过清理——之前建立的 `staticDiffs[wallId]` 条目就此遗留，此后只有「同墙再起一个补间且当时为静态态」或插件关停才会被清。这是与 P2-6 相互独立的泄漏入口，根因同样是 staticDiffs 生命周期管理依赖 animating 这一跨线程读到的瞬时值。

### P3-6 · `buildShape` 的 innerRatio 用裸 `(Number)` 强转，畸形 payload 抛 ClassCastException 被误分类为 INTERNAL_ERROR
**位置**：`state/EditSession.java`（buildShape）

buildShape 读取 innerRatio 字段用裸强转 `((Number) irRaw).floatValue()`，不像同方法内其他字段那样走会抛 `ValidationException` 的校验 helper。若客户端发来 `{"type":"shape","innerRatio":"0.5"}` 或 `innerRatio:true`，强转抛 `ClassCastException`。该异常不是 `ValidationException`，不会被 addElement 的对应 catch 捕获，会逃逸到兜底 catch 返回 `INTERNAL_ERROR`。但这本质是客户端输入错误，按全项目惯例应返回 `INVALID_PAYLOAD`。结果：把客户端错误误分类为服务端内部错误，错误信息对前端无指导性（不会崩连接，故 P3）。

### P3-7 · 模板插值出的 fill/tint 颜色不再校验，双端渲染分叉（后端退白、前端原样喂 fillStyle）
**位置**：`template/TemplateInstantiator.java`（materialize 的 Rect.fill / stroke.color / Icon.tint）

`resolveBackground` 对插值后的背景色做了格式校验，但 materialize 里 Rect.fill、stroke.color、Icon.tint 插值后都不再校验颜色格式。`TemplateLoader.checkColor` 只对不含 `${` 的静态颜色做校验（含 `${` 的留实例化期判，但实例化期并没有判）。于是 `fill:"${c}"` 且用户传入 `"red"` 或 `"#GGGGGG"` 等非法 hex 时：后端 `parseColor` 不匹配 → fallback `Color.WHITE`；而前端 `fillToCss` 对 solid 直接 return 原值交给 `ctx.fillStyle`——浏览器把 `"red"` 当 CSS 红、把 `"#GGGGGG"` 当无效值保留上次样式。结果**同一模板在 MC 内渲白、在浏览器预览渲红/脏色**，构成双端不一致。

### P3-8 · `repeatUntil.condition` 的 var() 引用未被「本脚本变量实时预览」收集
**位置**：`web/src/script/model/extractVars.ts`（collectFromAction）

`extractReferencedVariables` 收集每个动作引用的变量。条件文本里的 `var("X")` 引用通过 `if` / `waitUntil` 分支收集，但 `repeatUntil` 也有 `condition: string` 字段（0.7.2-P3 引入），同样可写 `var("user/x") > 0`，却落到 switch 的 `default: break` 被漏掉。`scripting-0.7.2.md` 明确写明实现应让 scanAction 对 if/waitUntil/repeatUntil 的 condition 都提取 var() 引用——即设计意图要收集，但前端漏改。后果：用户在 repeatUntil 条件里引用的变量不会出现在「本脚本变量实时预览」面板，看不到该变量的实时值（功能不完整，非崩溃）。

### P3-9 · `appendVariable.text` 长度上限校验缺失（前端放行、后端打回）
**位置**：`web/src/script/model/validator.ts`（appendVariable case）

后端 `ScriptRuleValidator` 会拒绝 `text.length > SET_VALUE_MAX`（4096），但前端 validator 的 appendVariable case 只校验 fullName 非空，完全没有 text 长度检查。因此一段超过 4096 字符的拼接文本会通过前端预校验、send 到后端才被 `SCRIPT_INVALID` 打回——正是 `validator.ts` 文件头声称要避免的情形。前端单测也只覆盖了 fullName，未覆盖 text 长度，故此遗漏无测试拦截。

### P3-10 · tweenBlock：bezier 参数存在但 easing 非 cubicBezier 时未拒（前端放行、后端打回）
**位置**：`web/src/script/model/validator.ts`（tweenBlock easing 校验）

后端在 `easing.type != CUBIC_BEZIER` 时，若 `easing.bezier != null` 会报「bezier 参数仅允许 cubicBezier 缓动」。前端 validator 只在 `type === 'cubicBezier'` 分支里校验 bezier，对「type=linear/easeIn 但携带 bezier 数组」这种坏状态不做检查（else 分支缺失）。因此 `{type:'linear', bezier:[...]}` 会通过前端、被后端拒。属低风险（正常 UI 切换 easing 类型不会残留 bezier），但仍是与后端不一致的真分歧。

### P3-11 · 0.7.3 / tween 多条校验错误文案与后端不逐字一致
**位置**：`web/src/script/model/validator.ts` vs `script/ScriptRuleValidator.java`
> 合并自两条独立发现（涵盖 0.7.3 四新积木 + tween）。

`validator.ts` 文件头明确要求每条错误文案与后端逐字一致，以保证用户在前端看到的提示与漏到后端时一致。但多条 0.7.3 / tween 文案与后端不符（判定结论相同，仅文案漂移），例如：
- randomBranch probability：前端「随机概率需在 0..100 之间」/ 后端「…（百分比）」
- setElementLayer：前端「元素置层缺少元素 ID」「置层方向不在允许范围」/ 后端「元素置顶/置底缺少元素 ID」「置顶/置底模式…」
- roundVariable：前端「取整变量名不能为空」「取整方式…」/ 后端「变量取整的变量名不能为空」「取整模式…」
- showTitle：前端「标题和副标题不能同时为空」「弹窗发送对象…」/ 后端「标题弹窗的主标题和副标题不能同时为空」「发送对象…」
- tweenBlock body：前端「补间里至少要放一个动作」「…移动/缩放/转动/透明度/变色」/ 后端「…至少要有一个属性动作」「…变色动作」

这些不影响校验结论（都拒同样的输入），但破坏了文件头自我声明的「逐字一致」纪律，且部分前端单测已把发散文案固化成断言。

### P3-12 · `VERSION_MISMATCH` 的 i18n 文案语义错误（写成「画板被他人改动正在同步」）
**位置**：`web/src/i18n/messages.ts`

后端 `VERSION_MISMATCH` 错误码唯一来源是协议版本不兼容（close 4002，全后端仅此一处）。但 i18n 文案为「画板被其他人改动过，正在自动同步…」/「Wall was changed elsewhere — syncing…」，完全是另一个（状态同步）语义，会让用户以为是协作冲突而非版本不匹配。配合 P2-17 的 4002 重连问题，用户既看不到正确原因也无法自愈。

### P3-13 · handleReady 设置的精确版本不匹配提示被 onClose 的通用「连接断开」文案覆盖
**位置**：`web/src/network/wsClient.ts`（handleReady / onClose）

当 `ready.accepted_v !== CLIENT_V` 时，`handleReady` 先设 `net.lastError = '协议版本不兼容…请升级'` 并 `close(4002) + stopped=true`。随后 `onClose(4002)` 因 stopped=true 进入 terminal 分支，code 既非 1000 也非 4001，落到 else 把 lastError 覆写为 `CONNECTION_CLOSED（code 4002）`。用户最终看到的是泛化「连接断开」而非「请升级」，丢失了精确诊断信息。

### P3-14 · fontSize / letterSpacing / lineHeight 数值输入无客户端下限钳位（可乐观写入 0/负值）
**位置**：`web/src/components/properties/TextElementSection.vue`（onNumberChange）

`onNumberChange` 只做 `isFinite` 校验便 emit 更新，未做下限钳位。HTML `min="1"` 只约束 spinner/validity，不阻止键盘直接键入 0 或负数。父组件会乐观把 `selected.fontSize` 写成 0/负数。对比 `TransformSection` 对 w/h 显式 `Math.max(1, v)`，这里属遗漏。后果：负 fontSize 时 PreviewRenderer 把 `ctx.font` 设为 `"-5px ..."`（无效值，Canvas 静默忽略沿用上一字体）；fontSize=0 时字形布局退化（不崩但显示异常）。非硬崩溃，但本地与后端校验后会出现一次值不一致 + 视觉错乱，且与其它数值字段的钳位约定不一致。letterSpacing/lineHeight 同样可输入负值。

### P3-15 · boundBoxFunc 在 distribute（equalGap）命中时也会对 resize 应用居中 delta
**位置**：`web/src/components/layout/CanvasView.vue`（boundBoxFunc）

`boundBoxFunc` 把 hasHits 计入 equalGapX/equalGapY，并用对应 delta 应用到移动边。snapManager 在 axis 未命中、但 distribute（findEqualGap）命中时会返回非空 snapped 值，其语义是「把整个元素居中到两侧最近邻之间」。对 resize 而言这没有意义——用户在调尺寸而非平移居中，却可能因 distribute 命中而让正在动的边被加上一个「居中 delta」，造成尺寸被意外拉伸/收缩。drag 路径下 distribute 合理，但 resize 路径不应吃 distribute 结果。

### P3-16 · 补间引擎跨线程数据竞争：renderStatic 接收的「插值帧」是被原地复用 + 原地 mutate 的共享 baseState
**位置**：`script/engine/TweenScheduler.java`（buildInterpolatedFrame）+ `render/AnimationTicker.java`（renderStatic）

`buildInterpolatedFrame` 用 `new EditSession(task.baseState())` 后调 `es.updateElement(...)`，而 EditSession 构造器把传入 state **按引用**持有，updateElement 通过 `elements().set(idx, updated)` **原地 mutate** 这个 ArrayList。因此 `es.state()` 返回的就是同一个 `task.baseState()` 对象，每个 tick 都在原地改它。

问题在于 tickOne 把这个 frame 传给 `ticker.renderStatic(wallId, frame)`，而 renderStatic **不在调用线程同步渲染**，而是把 frame 捕获进 lambda、**异步在 Ticker 线程**读取。于是 tween 线程在下一 tick 继续原地改同一组 Layer ArrayList，Ticker 线程可能正在 renderFrame 里遍历这同一个 list——对非线程安全 ArrayList 的并发 set/iterate，造成撕裂读（渲染到半更新的元素）。本类 Javadoc 多处声明「frame 为 immutable record，跨线程安全」，但实际 frame 既非新对象也非不可变，安全论证前提不成立。高 tweenFps（60，cadence 约 16ms）下窗口尤其明显。

### P3-17 · `RoundVariable` 对 NaN/Infinity 输入在三种 mode 下行为不一致且产出脏值
**位置**：`script/engine/ActionExecutor.java`（doRoundVariable）

`doRoundVariable` 用 `Double.parseDouble` 解析变量当前值，而它会接受 `"NaN"` / `"Infinity"` / `"-Infinity"` / 极大指数（如 `"1e999"→Infinity`）。后续 switch：floor/ceil 对 NaN 得 NaN → 写回 `"NaN"`；round 对 NaN 得 0 → `"0"`；对 Infinity：floor/ceil 得 `"Infinity"`，round 得 `Long.MAX_VALUE` → `"9223372036854775807"`。即**同一非有限输入在三种取整模式下落地完全不同的脏字符串值**，且把 "NaN"/"Infinity" 写回 NUMBER 变量后续又被当 0 处理。文档 §G3 仅规定「非数值 → error step」，而 NaN/Infinity 技术上是合法 double 故未被 `NumberFormatException` 拦下，绕过了 error 语义。

### P3-18 · `Protocol.java` 类级 Javadoc 未补 v7 条目
**位置**：`web/Protocol.java`（类级注释）

`SUPPORTED_MIN/MAX` 已正确升到 7（前端 `CLIENT_V=7` 同步、双向协商均走常量，功能无误），但类级 Javadoc 的版本沿革只写到「v6 = tween」，没有补「v7 = 0.7.3 四新积木」的条目，与 v3/v4/v5/v6 每次升版都留注记的惯例不一致。`scripting-0.7.3.md §2` 明确要求协议升 v7。纯文档遗漏，非功能 bug，但会让后续维护者对照沿革时缺一节。

---

## 四、设计层面待定 / 缺口（needs-design）

> 以下现象属实且非刻意，但属于设计层面的缺口/不一致/脆弱不变量，而非可直接判定的硬 bug。多数当前被上游守卫兜住或概率极低，列出以备评估。

### D-1 · `ShowTitle.title/subtitle` 为 null 时 serialize→deserialize 不可逆（null 被归一为空串）
**位置**：`script/ActionSerializer.java` + `ActionDeserializer.java`

ShowTitle 的 title/subtitle 是普通 String 字段，record 不收敛 null，validator 显式允许 null。序列化端对 null 写出 JSON `"title":null`，反序列化端用 `optionalText(..., "")` 对 null 节点返回 `""`。因此 `ShowTitle(null, "sub", ...)` 经一次往返变成 `ShowTitle("", "sub", ...)`——往返不等价，破坏类注释强调的「序列化严格互逆」。其他多态 Action（如 SetElementProperties.kind 显式归一）已规避。从 wire 进来的总是非 null，仅当 Java 代码直接构造 null 或未来 DB 手改产 null 再序列化时暴露；现有 wire 测试全用非 null 串未覆盖此路径。

### D-2 · firePlayerJoin/Kill/Quit/RightClick 等主线程事件转发未包 try-catch
**位置**：`script/engine/TriggerRouter.java`（fireGlobal / fireRightClickWall）

这些 fire 入口在循环里直接调 `runner.submit`，没有 per-ref 或整体 try-catch。submit 内部已 catch `RejectedExecutionException` 且 `store.find` 不会抛，故当前正常路径安全。但这是脆弱的隐式契约：一旦 submit 路径将来抛出任何 RuntimeException，它会沿 `GameEventListenerHub` 的 MONITOR handler 冒泡，被 Bukkit 记成 listener 异常并可能影响同优先级其他监听。对比 `PlayerNearSampler.sample()` 对每个 fire 都包了 try-catch、`onTimerFire` 整体包了 Throwable，这两条游戏事件路径缺少同等防御。

### D-3 · DB 加载路径不经 ScriptRuleValidator，corrupt/legacy 规则的 Timer/near 数值仅靠下游兜底
**位置**：`script/engine/TriggerRouter.java` + `ScriptStore.loadFromDb`

`ScriptRuleValidator.validate` 只在 WS 写入路径调用，`loadFromDb` 不做范围校验。因此旧版本/手改 DB 落下的规则可能带 `intervalSeconds<=0` 或 `rangeBlocks` 远超 `NEAR_MAX`。当前下游有兜底（`Math.max(1, intervalSeconds)`、`(long)rangeBlocks*rangeBlocks` 防溢出），不会崩，但 rangeBlocks 取一个被校验层本应拒绝的巨值时，near 规则会在远超设计上限的半径生效（几乎永远判「在内」），语义偏离 `NEAR_MAX=32` 的约束，且管理员无法从 UI 察觉。属 pre-release「激进改 schema OK」范围内。

### D-4 · tick 异常隔离路径漏清 `lastRenderAt`，泄漏一个 Long 项
**位置**：`script/engine/TweenScheduler.java`（tick 的 per-task catch）

tick() 的 per-task catch 在单墙补间抛异常时清理 `active.remove` + `clearStaticDiff`，但没有 `lastRenderAt.remove`。正常完成路径和接管路径都会清 lastRenderAt，唯独异常路径漏了，导致残留一个 stale Long 项。有界小泄漏（按墙数），但与其他路径不一致，且残留项会让该墙下一个补间的首个中间帧节流判断偏一帧（实际无害，但语义脏）。

### D-5 · 前端 image mask 不做后端的「mask bbox > 10× 元素面积」sanity 降级（预览有蒙版 / 游戏内无蒙版）
**位置**：`render/ImageRenderer.java`（applyImageMaskClipSafely）vs `web/src/render/PreviewRenderer.ts`（drawImage 硬边 mask 分支）

后端有一道 sanity 守卫：当 mask path bbox 面积 > 元素面积 ×10 时，跳过 mask 直接画原图（为防 Area 布尔运算 O(n²) 卡死）。前端硬边 mask 分支只解析 path 后无条件 `ctx.clip`，没有这道降级。结果：对于 bbox 远超元素范围的极端 mask，编辑器预览按 mask 裁切显示，而游戏内后端跳过 mask 画完整图——双端可见结果分叉。后端文档明确这是刻意的稳定性降级，但前端未镜像该逻辑。

### D-6 · 元素坐标/尺寸整数路径缺整数回绕守卫，超 `Integer.MAX_VALUE` 的值静默回绕可绕过范围校验
**位置**：`state/ElementValidator.java`（intValue / intFieldOrDefault）

二者都直接 `n.intValue()`，没有像 `TimelineOperations.intOrThrow`（对超 int 范围显式拒绝）那样的越界守卫。JSON 里 `x: 4294967346`（= 2³² + 50）被 Jackson 解析为 Long，`intValue()` 静默回绕成 50，随后 `validateCoord(50)` 通过——即客户端可用大于 2³² 的整数让校验「看不到」真实值。虽回绕后的值仍落在合法区间（不致渲染崩溃），但语义上绕过了「数值类型/范围」入口校验，与 timeline op 路径处理纪律不一致；同一字段在 timeline op（拒）与 element op（静默回绕）下行为分叉。影响所有走 intValue 的字段。

### D-7 · `Keyframe` 构造器不校验 value/property 为 null，坏 blob 可生成 value==null 的关键帧
**位置**：`state/Keyframe.java`（canonical 构造器）

构造器只补 easing 默认，不拒绝 `value==null` 或 `property==null`。WS op 路径安全，但从 DB 加载持久化 blob 的反序列化路径无此保护：`KfValueDeserializer` 对 JSON null 返回 null，所以 `{"property":"opacity","value":null}` 会反序列化出一个 value==null 的 Keyframe。渲染期目前靠下游每个采样器正确前置守卫（如 `sampleColor` 的无检查强转前有循环守卫），不直接 NPE，但这层安全完全依赖下游每个采样器都正确，Keyframe 自身不设防形成隐性契约风险，与同包 TriggerConfig/Easing 对坏 blob「构造器内防御性清洗」的纪律不一致。

### D-8 · grid 布局 cellW/cellH 整数运算无下界保护，gap 过大或 cols/rows 过大时产负尺寸/溢出坐标
**位置**：`template/TemplateInstantiator.java`（gridLayout）+ `TemplateLoader.validateLayout`

`cellW = (contentW - (cols-1)*gap) / max(1,cols)`。validateLayout 只校验 grid 的 columns/rows ≥ 1，对其上界与 gap 无任何限制。当 gap 较大而 content 区较小时 cellW/cellH 为负，materialize 得到负宽高元素（被渲染层 `w/h<=0` 静默跳过，产隐形空元素）；当 columns 极大时 `(cols-1)*gap` 与 `col*(cellW+gap)` 都会 int 溢出，得乱序坐标。属可由模板文件触发的数据完整性/溢出缺陷。

### D-9 · grid 布局中 `visible_when=false` 的元素仍占用网格槽位（与 stack/free 不一致）
**位置**：`template/TemplateInstantiator.java`（gridLayout）

gridLayout 对不可见元素 `idx++; continue`——照样递增 idx，于是它占据的 row/col 槽位被留空，后续元素不前移补位。而 stackLayout / freeLayout 对不可见元素是纯 continue 不消耗位置。两种行为不一致：grid 模式下首元素 `visible_when` 为 false 会在网格里留一个空洞。若设计意图是「槽位稳定」则可接受，但与同文件其它布局的「跳过即不占位」直觉相反，且无文档明示，可能导致服主困惑。

### D-10 · 启动时 ManualScheduleProvider 早于 RailScheduleProvider 注册，skipWallPredicate 为 null 期间对 rail-bound wall 双写
**位置**：`variable/provider/ProviderBootstrap.java`

`daemon.register(manualProvider)` 会同步执行其 initialize → forceRefreshAll → pushValues，并立即安排周期 refresh，但此刻 `manualProvider.skipWallPredicate` 仍是 null（要到 railProvider 注册后才 set）。因此在 manual 的 initialize 推送瞬间，`shouldSkipWall()` 对所有 rail-bound 且同时存在 manual schedule 行的 wall 返 false，manual 会把 `next_departure/eta_*` 等 SHARED_BASE_KEYS 用 manual 数据写一遍；随后 railProvider 注册时再覆盖回 rail 值。这是一个启动期的瞬时双写/值闪烁窗口（被 rail init 紧接覆盖），违反「同 namespace 避免双写」纪律但破坏性有限。根因是注册顺序而非拦截点。

### D-11 · rail.station.delete / run.delete 删除不存在/越权对象静默返回 deleted=0
**位置**：`web/RailOpDispatcher.java`（handleStationDelete / handleRunDelete）

二者直接 `dao.deleteStation(id)` / `deleteRun(id)` 并回 `ack{deleted:n}`，n 可能为 0（id 为 null、不存在、或 DAO 异常被吞返 0）。对 op/控制台仍会执行 `delete(null)` 返 0 并报「成功」。与其他 op（如 timetable.set 先 findRun isEmpty 返 NOT_FOUND）风格不一致，缺少对象存在性校验，前端拿到 `deleted:0` 难以区分「已删/本就不存在/越权」。

### D-12 · RailScheduleProvider.refresh 节流 interval 与 daemon 周期相等（均 1000ms），调度抖动偶发跳过整秒 push
**位置**：`variable/provider/RailScheduleProvider.java`（refresh 节流）

refresh() 的节流 interval 固定 1000ms，而 daemon 用 `scheduleAtFixedRate` 周期也恰好 1000ms。`if (now - last < interval) continue` 在 `now-last` 因调度提前/时钟抖动略小于 1000ms（如 998ms）时会跳过本次 push，该秒的 eta/arrival 不更新。地铁屏标榜 second 精度但实际偶尔丢秒。属轻微抖动而非功能错误。

### D-13 · 积木画布自动布局坐标可能与已有显式坐标的积木堆重叠
**位置**：`web/src/script/canvas/BlockCanvas.vue`（basePositionedStacks）

先收集所有有显式 blockLayout 坐标的规则，再对缺坐标的规则调 autoLayout 纵向补位（x=40, y=40+missingSeq*320）。autoLayout 不感知 explicit 已占用的坐标区域：若某显式规则恰好位于 (40,40) 附近，新建/缺坐标规则的第一个 autoLayout 位会与之重叠，两个积木堆叠在一起难以分辨/拖动。serialize 侧 autoLayout 也只跳过已有 key 的规则，不做空间避让。

### D-14 · `blockKindAt` 忽略 path 首段，硬编码顶层序列键为 actions
**位置**：`web/src/script/canvas/useBlockDrag.ts`（blockKindAt）

blockKindAt（用于拖块浮层标题）从 `path[i+1]` 取下标、`path[i+2]` 取分支键，完全忽略 `path[i]`（序列键）。对当前形态能工作，但与 `blockTree.ts` 的权威导航（会逐段校验序列键属于该容器块）行为不一致：blockKindAt 不校验序列键，若 path 段错位只会静默落到 'log' 兜底。功能上无害（仅浮层标签），但这是 blockTree 头注反复强调「path 必须与后端逐字符同构」之外的一处平行手写导航，未来新增容器序列键时容易被漏改而悄悄退化。

### D-15 · optional 数值字段（seekMs）无法清空回「未设置」——空输入被吞，`FieldDef.optional` 标志从未被消费
**位置**：`web/src/script/params/BlockParamInput.vue`（number 控件）

number 控件的 `onNumberInput` 对空输入直接 return（保留旧值），且 BlockParamInput / BlockNode 全程未读取 `FieldDef.optional`（确认零引用）。`playTimeline.seekMs` 声明 `optional:true`、wire 类型 `seekMs?:number`。一旦用户给 seekMs 填过值，就再没有 UI 路径把它清回 undefined/null：清空输入框无效，也没有清空按钮。op 从 seek 切回 play 时 seekMs 残留在数据里。可选数值语义在该控件层缺失。

### D-16 · useCommandTemplates：缓存命中时 loading 永不置 true，且 finally 闭包绑定首个调用实例的 loading ref
**位置**：`web/src/script/params/useCommandTemplates.ts`

templates/loading 是每个 composable 实例独立的 ref，但 cachePromise/cacheSessionId 是模块级共享。doLoad 里只有在新建 cachePromise 的分支才设 `loading=true`，并把 `finally(()=>loading=false)` 绑定到「当前这个实例」的 loading。后果：(1) 第二个实例命中已存在的 cachePromise 时，它的 loading 永远停在 false（即便请求仍在飞），UI 不显示加载态；(2) 若首个实例在 resolve 前 unmount，finally 仍写它已废弃的 loading ref（悬挂副作用），而真正等待该 promise 的其它实例 loading 不会被复位。属可观测性/一致性瑕疵，不致崩。

### D-17 · wall 切换 / 规则被远端删除时 closeEditing→flushSave 会为已失效的规则发 script.update
**位置**：`web/src/stores/scriptEdit.ts`（closeEditing 的 watch 触发链）

两条 watch 都会在规则不再有效时调 closeEditing()：① wallId 变化；② server 把当前规则删了使 `scripts.get` 返 null。closeEditing 的第一步是 `flushSave()→doSave()`，此时 selectedRuleId/workingCopy 仍是旧规则且 dirty 可能为 true，于是会向 server 发一条针对旧规则的 update（wall 切换场景甚至可能走已绑到新 wall 的连接）。两种都至少产生一次 INVALID/NOT_FOUND 报错 toast，且无论如何旧改动都丢。

### D-18 · activeTimeline 被删除时残留 `previewActive=true`（停留在预览态空转）
**位置**：`web/src/stores/timeline.ts`（watch activeTimeline）

`watch(activeTimeline)` 在 tl 变 null 时只把 playheadMs 归 0，不复位 previewActive / playing / draggingElementIds。若用户正在本地预览或播放时激活的 timeline 被删（或被另一会话删掉），previewActive 仍为 true。playback 会在下一帧自停，但 previewActive 永久卡 true（内部 `if(tl)` 兜住不崩，渲染退回基值）。属状态机未彻底收口的残留态，配合 selectedGroups/expandedElements 也未清，下次新建 timeline 时可能带入旧选中残影。

### D-19 · error 帧 payload 为 null/缺字段时 handleError 抛异常，导致对应 sendWithAck 不被 reject（等到超时才落定）
**位置**：`web/src/network/wsClient.ts`（handleError）

handleError 第一行就访问 `payload.code`（再访问 `payload.message`）。若服务端结构漂移/forward-compat 发来 error 帧但 payload 为 undefined/null（或缺 code），这里立刻抛 TypeError，被外层 try/catch 兜住记日志，但执行流在「`if (errId && pendingAcks.has(errId)) … reject`」之前就中断了——该 id 对应的 sendWithAck promise 不会被立即 reject，只能等 ack 超时（默认 5s/8s）。文件已为 ready payload 加了运行时空值守卫，error payload 却没有同等防御。若将来有 timeoutMs=0 的调用方，该 promise 会永久 pending。

### D-20 · connect 早返回只判 OPEN 不判 CONNECTING，理论上可创建并行 socket
**位置**：`web/src/network/wsClient.ts`（connect）

connect() 的幂等早返回只检查 `readyState === OPEN`。由于 `this.ws` 只在 open 回调里赋值，若在「新 socket 已 new 但尚未 open」的窗口内再次调用 connect（如外部手动 connect 撞上 reconnect 定时器刚 fire 的 connect），会 new 出第二个 WebSocket；两个 open 回调先后触发，后者覆盖 `this.ws`，前者变成无主 socket（监听器仍在、仍可能触发一次额外 scheduleReconnect）。当前调用方只有启动时单次 connect，实际触发概率低，但属隐患。

### D-21 · rendering.md §9.4 半数进位「对正数一致」论断未覆盖负值 round（当前恰好安全但缺防护说明）
**位置**：`web/src/timeline/interpolation.ts`（withAnimated）+ 后端 `KeyframeInterpolator.ix`

`rendering.md §9.4` 写明「round(x×255) 半数进位（Java Math.round 与 JS Math.round 对正数一致）」，只论证了正数。但 x/y/rotation 数值轨的写回（`clampInt(Math.round(v))`）会对负坐标取 round。经核验 JS 与 Java `Math.round` 对 .5 边界均「向 +∞ 取整」，故当前两端一致、无 bug。但这依赖一个文档未显式覆盖的等价性（负 .5 半数进位方向恰好同向），属易回归隐患——若任一端将来改用 toward-zero 或 banker's rounding 即分叉，而 CI 向量集不覆盖负坐标 round。

### D-22 · 前端 PathParser 仅支持 M/L/Q/C/Z，缺 H/V/S/T/A（当前被 PathDValidator 兜住，属脆弱不变量）
**位置**：`web/src/render/PathParser.ts` vs `render/PathParser.java`

后端 PathParser 支持完整命令集 M/L/H/V/Q/T/C/S/A/Z（含 A 椭圆弧 cubic 近似）。前端只认 M/L/Q/C/Z，遇到 H/V/S/T/A 时在命令字母处 `scanNumber` 返回 endIdx===i，触发 `break` 直接停止解析、**静默截断后续整条 path**。当前安全，因为用户 PathElement 入口经 `PathDValidator`（同样只允许 M/L/Q/C/Z）拦截，IconElement 走浏览器原生 Path2D（不经此 parser）。但这是未在代码内强制的隐性不变量，且前端 JSDoc 仍写「必须与 PathParser.java 逐行同公式」与实际命令集不符。一旦未来放宽 PathDValidator、Live Paint 生成含 H/V/A 的 d、或让 PathElement 渲染复用到含这些命令的路径，前端会静默丢弃路径尾部而后端正常渲染，造成游戏内有图形/浏览器空白的难排查分叉。

### D-23 · KeyframeInterpolator 插值后的临时 ProjectState 后端丢弃 tweenFps、前端保留（结构不对称，当前无渲染影响）
**位置**：`render/KeyframeInterpolator.java` vs `web/src/timeline/interpolation.ts`

后端 interpolate 重建临时 state 时把 timelines/activeTimelineId/tweenFps 三参全置 null，即 tweenFps 被丢弃；前端则保留 base.tweenFps。两端临时插值帧的 tweenFps 字段不对称。目前无害——tweenFps 只从 wall 的 base state 读，从不从插值帧读，且插值帧不持久化。但这是易被未来改动踩中的脆弱点：若有人让插值帧流入任何读 tweenFps 的路径，后端会拿到默认 30 而前端拿到 per-wall 值，节流帧率分叉。

### D-24 · Action073BehaviorTest 仅覆盖 happy path（RoundVariable NaN/round-half/headless、setElementLayer headless 与 layer.locked 未测）
**位置**：`plugin/src/test/.../Action073BehaviorTest.java`

新行为测试覆盖度有缺口：(1) RoundVariable 只测了普通值与非数值 abc，未测 NaN/Infinity（即 P3-17 的盲区）、未测 round 的半值方向（`Math.round` 对 2.5→3、-2.5→-2 的非对称 half-up）、未测空值分支；(2) setElementLayer 只测了 session seam，但 headless 落地路径以及 `layer.locked → LAYER_LOCKED → error step` 完全没测，而 headless 是脚本在无活跃编辑器时的主路径；(3) ShowTitle 未覆盖 fadeIn/stay/fadeOut 的 ms→tick 整除截断（如 stayMs=49→0 tick 导致标题瞬隐）。属「真覆盖边界 vs 只测 happy path」的后者倾向。

### D-25 · `doShowTitle` 用已弃用的旧版 `sendTitle(String...)`，而 sendMessage/title channel 走 Adventure Component，渲染语义不一致
**位置**：`script/engine/ActionExecutor.java`（doShowTitle vs sendTo）

`doShowTitle` 走 `p.sendTitle(titleText, subtitleText, fadeInTicks, stayTicks, fadeOutTicks)`——这是 Bukkit 的已弃用 String 重载（按 legacy 文本解析，会把 §/& 颜色码当 legacy 处理）。而同文件 sendTo 的 title channel 用 Adventure `Title.title(Component.text(...))`（不解析 legacy 码，纯文本）。两条「显示标题」路径对含 § 或 & 的同一插值文本会有不同呈现，且 `sendTitle(String)` 在未来 Paper 版本随时可能移除（项目架构纪律强调禁 NMS / 用稳定公开 API）。文档 §G4 仅说「照 sendMessage」，并未要求用 legacy 重载。

---

## 附：未纳入本文的判定（透明度）

- **21 条误报**：对抗式验证代理读代码后确认审查者误读（已有守卫 / 逻辑正确 / 不可达），全部剔除。
- **29 条文档已取舍**：凡文档明确取舍或属「工具不是保姆」哲学（不自动降级、安全边界刻意放宽、Token 暴力枚举防御留 v1.x 等）的均判为刻意设计，未计入。

*（本报告由多代理只读审查生成，所有发现均经独立对抗式验证。问题位置精确到文件/方法，未给出逐行修复方案，具体修法由维护者决定。）*
