# 补间动画设计总纲（脚本「在 X 秒内」+ 非线性缓动）

> 0.7.2「稳的」版完工后单独 brainstorming 的大功能（设计于 2026-06-13）。**拟作 0.7.2 之后的独立功能版本**
> （版本号待定，0.7.x 大版本线一路 `0.6.0-SNAPSHOT` 下迭代）。
>
> 契约范式照 `docs/scripting.md`（0.7.0 总纲）/ `docs/timeline.md`（0.6 时间轴）/ `docs/scripting-0.7.x.md`。
> 双端插值/缓动数学权威在 `docs/rendering.md §9`（0.6 已落地）。**实施前对照本文档 + 下方调研依据。**

---

## 0. 决策摘要（brainstorming 固化，不可越界）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| **T1** | 补间积木形态 | **Scratch 式「在 X 秒内」C 形包裹积木**（独立新建，缓动可选，里面放哪个动作用户自己选） | 最直观、最像 Scratch；用户实测 0.7.x 积木后明确想要这种嵌套包裹 |
| **T2** | 补间引擎架构 | **A 独立补间引擎**（脚本侧自跑、改元素「基准值」），**不碰 `AnimationTicker`**（final 类） | ① 和时间轴**天然共存**（补间改基准值、时间轴读基准值叠加偏移，正交不打架）符合 scripting.md「脚本+时间轴共存」哲学；② 补间是**纯服务端运行时**（编辑器不预览补间播放）→ 架构 B「复用渲染链保双端一致」用不上；③ 不碰 final Ticker、末帧落盘天然、包裹任意属性动作灵活 |
| **T3** | 包裹语义 | **只放属性动作、多个并行补间**。非属性动作（发消息/播声音/改变量）放包裹**外** | 补间 = 属性过渡，语义最清晰；多个属性动作同时补间支持「边移动边放大边淡入」组合动画 |
| **T4** | 能补间的属性 | **6 数值**（x / y / w / h / rotation / opacity）+ **color** + **fill**（复用现有插值轨道，见调研点 3） | 复用 `KeyframeInterpolator` 已有的 NUMERIC / color / fill 插值；text 离散不补间 |
| **T5** | 缓动 | 复用 **`EasingSolver`**（线性 / 缓入 / 缓出 / 缓入出 / 自定义 cubic-bezier）；UI 下拉预设 + 可选自定义曲线（复用时间轴的 SVG 贝塞尔编辑器） | 双端镜像 + CI 校验现成（调研点 4），不重造数学 |
| **T6** | 执行方式 | **挂起式**（像 Scratch glide）——脚本走到「在 X 秒内」补间这 X 秒，补完才走下一动作。复用 `ScriptRunner` 挂起机制（`playTimelineAwait` 范式） | 符合 Scratch 直觉；非挂起「fire-and-forget」变体留 future |
| **T7** | 落盘 | 补间**完**目标值永久写进元素 state。**v1：补间期间每帧落 state**（`ElementPropertyApplier`）——才能与时间轴共存（时间轴读 DB base 叠加） | 调研点 5：时间轴播放不落 state，补间的本质差异是「补间完落盘」。**每帧落 state 是与时间轴共存的前提**，非末帧——见 §5 矛盾分析 |
| **T8** | 同元素多补间冲突 | 同元素同属性再来一个补间 → **后者接管**（从当前值重新补到新目标，不排队、不叠加） | 直觉简单；排队/叠加留 future |
| **T9** | 与时间轴共存 | 补间改「基准值」、时间轴读基准值「叠加偏移」，**正交共存**（背景时间轴循环 + 脚本补间滑入能同时） | 调研点 6 A 评估：二者不冲突；架构 A 的核心优势 |
| **T10** | 性能 | config 限「同时补间数上限 + 帧率（默 20fps）」；**数据透明、不自动降级**（服主自负） | 守 PROPOSAL §2.1「工具不是保姆」；DB 写压力是架构 A 的已知代价（§9） |

---

## 1. 范围

### 1.1 做（v1）
- 「在 X 秒内」C 形包裹积木（T1）+ 缓动选择（T5）
- 独立补间引擎 `TweenScheduler`（T2）
- 5 类属性补间（移动 / 缩放 / 转动 / 透明度 / 变色，T4）+ 多个并行
- 挂起式执行（T6）+ 补间完落盘（T7）
- 与时间轴共存（T9）+ 同元素冲突接管（T8）
- config 限并发 + fps（T10）

### 1.2 不做（v1，留 future）
- **非挂起「fire-and-forget」变体**（启动补间后脚本不等、继续往下）
- **包裹内嵌套**（「在 X 秒内」里再套 if / repeat / 另一个「在 X 秒内」）——v1 包裹里只放扁平的属性动作
- **补间排队 / 叠加**（同元素多补间 v1 是后者接管，不排队）
- **「渲临时层省 DB」优化**（补间期间不每帧落 DB、只渲临时覆盖层）——见 §9 开放问题
- **缓动逐属性独立**（v1 一个包裹一个缓动，所有属性共享；逐属性不同缓动留 future）
- **text / 其他属性补间**（text 离散、image/brush 特有属性不补间）

---

## 2. 包裹积木形态 + 语义

### 2.1 形态（C 形包裹，照 repeat 范式）
```
在 [1.5] 秒内   缓动 [缓出 ▾]
┌──────────────────────┐
│ ▸ 移动到  x:100 y:50  │ ┐
│ ▸ 缩放到  w:200 h:80  │ ├ 同时并行补间
│ ▸ 透明度到  100       │ ┘
└──────────────────────┘
```
- 头部：`durationSeconds`（number）+ `easing`（缓动选择器，T5）
- C 臂 body：放 1~N 个**属性动作**（friendly 元素积木的子集，见 §2.3）
- blockId 同构：body[j] = `<blockId>/body/<j>`（照 repeat / repeatUntil 范式，**前后端逐字符同构**）

### 2.2 语义
1. 脚本执行到「在 X 秒内」→ 收集 body 里所有属性动作的 **(elementId, property, targetValue)** 三元组。
2. 对每个三元组，读元素**当前值**作 `fromValue`，动作里的目标作 `toValue`。
3. 注册一个 `TweenTask`（含所有三元组 + duration + easing）到 `TweenScheduler`。
4. **挂起脚本**（T6），补间这 X 秒。
5. 每帧并行推进所有三元组（§3）。
6. 补间完 → 目标值落盘（T7）+ 注销 task + **续接脚本**后续动作。

### 2.3 body 里能放的属性动作（friendly 元素积木子集）
| 友好积木 | 补间属性 | 插值 |
|---|---|---|
| 移动到 | x, y | 数值（`sampleNumeric`） |
| 缩放到 | w, h | 数值 |
| 转到 | rotation | 数值 |
| 透明度到 | opacity | 数值 |
| 变色到 | color / fill | `ColorLerp` / fill 插值 |

- body 里放**非属性动作**（发消息 / 播声音 / 改变量 / runCommand 等）→ **校验拒绝**（保存期 + 运行期双查），提示「补间里只能放移动/缩放/转动/透明度/变色」。
- 同一属性在 body 里被两个动作写（如两个「移动到」）→ 校验拒绝或后者覆盖（实施定，倾向保存期警告 + 运行期后者胜）。

---

## 3. 架构 A：独立补间引擎

### 3.1 `TweenScheduler`（照 `VariableProviderDaemon` 范式）
- 独立单线程 `ScheduledExecutorService`（线程名 `hikari-canvas-tween`，daemon）。**不复用 `AnimationTicker` 的 SES**（T2：不碰 final Ticker）。
- 活跃任务表 `Map<String, TweenTask>`（key = tweenId 或 wallId+seq）。
- cadence = `round(1000 / fps)` ms（fps 来自 config，默 20）。

### 3.2 `TweenTask`
```
TweenTask {
  wallId: String
  targets: List<PropTarget>      // (elementId, property, fromValue, toValue)
  startTimeMs: long
  durationMs: long
  easing: Easing                 // 复用 0.6 Easing record
  onComplete: Runnable           // 落盘 + 续接脚本
}
PropTarget { elementId, property, fromValue, toValue }
```

### 3.3 每帧推进（tick）
```
t = clamp((now - startTimeMs) / durationMs, 0, 1)
eased = EasingSolver.ease(easing, t)          // 复用，调研点 4
for each target:
    value = lerp(fromValue, toValue, eased)    // 数值线性 / ColorLerp 颜色 / fill 插值
    应用到元素属性（见 §3.4 落盘 + 渲染）
if t >= 1:
    onComplete.run()   // 落终值 + 注销 + 续接脚本
```
- 数值插值：`from + (to - from) * eased`。
- 颜色：`ColorLerp.lerpHex(from, to, eased)`（sRGB 线性空间，调研点 4）。
- fill：复用 `KeyframeInterpolator` 的 fill 插值（同类型同 stop 数逐 stop，否则 step，调研点 3）。

### 3.4 落盘 + 渲染（P2 落地：路径 Z 分情况，比原计划「每帧落 DB」更优）

> **实施修正**：调研发现**路径 Z**（给 `AnimationTicker` 加「渲静态墙一帧」轻量入口 `renderStatic`），
> 补间得以**渲临时态不落 DB**。原 §3.4/§5 写的「每帧落 DB」只在「有时间轴的墙」才必要；静态墙（补间主场景）
> 省掉了每帧 DB 写。按 wall 有无时间轴分两路：

- **静态墙（无 timeline，补间主场景）= P2 已实现**：补间引擎自持插值 frame（内存），每帧
  `ticker.renderStatic(wallId, frame)` 渲临时态（Ticker 线程、复用 renderFrame 的 viewer-gated + diff、
  **不落 DB**）；**末帧** `ElementPropertyApplier.applyMany` 落 DB（目标值永久）。`renderStatic` 内 `entries`
  守卫——有 timeline entry 时 no-op，不抢 Ticker。
- **有时间轴的墙（共存场景）= P3 实现**：补间改走**每帧 `applyMany` 落 DB**（updateState + invalidate），
  让时间轴下一帧 reload 读到补间基准值 + 叠加关键帧（见 §5）。这条 wall 数通常少，DB 写压力可接受。

§9 的「渲临时覆盖层省 DB」优化在静态墙**已由路径 Z 在 P2 兑现**（原列为 future）。

### 3.5 挂起 + 续接（复用 `ScriptRunner` 挂起机制）
- 「在 X 秒内」是**挂起式 Action**（照 `PlayTimelineAwait` 范式，调研点 2）：`ActionExecutor` 触发补间注册后，`ScriptRunner` 据 `durationMs` 挂起（不阻塞线程，帧栈续接）。
- 补间 `onComplete` 回调通知 `ScriptRunner` 续接后续动作。
- body 含补间 + 后续动作的 blockId 同构（`<blockId>/body/<j>`），trace 高亮可定位。

---

## 4. 能补间的属性 + 缓动

### 4.1 属性（复用现有插值轨道，调研点 3）
- **NUMERIC（6）**：x / y / w / h / rotation / opacity —— `sampleNumeric` 线性。
- **color**：仅 `TextElement` —— `ColorLerp`（sRGB 线性）；含 `${var:}` 的颜色值不补间（取目标值瞬切，照 timeline P3 语义）。
- **fill**：Rect / Icon / Path / Circle / Shape / Brush 6 类 —— 同类型同 stop 数逐 stop，否则 step。

### 4.2 数值轨的 `${var:X}`
- 目标值若是 `${var:X}`（变量）→ 注册补间时 **resolve 一次**（取当前变量值作 `toValue`），补间过程不再随变量变（补间是「到某个确定目标」）。复用 timeline 数值轨 `${var:X}` resolve 范式。

### 4.3 缓动（T5，复用 `EasingSolver`）
- 预设：`LINEAR` / `EASE_IN` / `EASE_OUT` / `EASE_IN_OUT`（CSS 标准控制点）。
- 自定义：`CUBIC_BEZIER`（4 参），UI 复用时间轴的 SVG cubic-bezier 曲线编辑器。
- 一个包裹一个 `easing`，所有属性共享（逐属性不同缓动留 future）。

---

## 5. 与时间轴共存（架构 A 的核心 + 「每帧落盘」的由来）

**目标**：同一 wall 上「用户预排的时间轴（如背景淡入 LOOP）」+「脚本补间（如玩家进服时招牌滑入）」**能同时**。

**机制（正交）**：
- 时间轴：`KeyframeInterpolator.interpolate` 读 wall **base state** + 关键帧 → 产临时叠加 state → 渲染（**不落 state**，调研点 5）。
- 补间：改 wall **base state** 本身（落 DB）。
- 二者正交：时间轴的关键帧是「相对 base 的动画」，补间改的是 base。时间轴下一帧 reload 新 base + 叠加关键帧 → 自然叠加（调研点 6 A 评估「二者不冲突」）。

**落盘策略（P2 落地路径 Z，按有无时间轴分两路）**：
- **静态墙（无 timeline）= P2 已实现**：补间渲临时态（`renderStatic`）不每帧落 DB，**末帧才落**。省 DB
  （路径 Z；原计划「每帧落 DB」在静态墙不必要）。
- **有时间轴的墙 = P3**：要让时间轴叠加补间中间值——时间轴 `reloadLocked` 从 `wallSource.load` 读 DB base
  （调研点 7），故这条 wall 的补间**每帧 `applyMany` 落 DB**（updateState + invalidate），时间轴下一帧
  reload 才叠加得到。wall 数少、DB 压力可接受。
- **分流依据**：`ticker.isWallAnimating(wallId)`——true（有 timeline 在播）走每帧落 DB；false（静态）走
  `renderStatic` 渲临时态 + 末帧落。补间引擎按此在 tick 内分流，不碰 Ticker 内部（守 T2）。

---

## 6. 冲突语义（T8）

- **同元素同属性**再来一个补间（还在移动又来一个「移动到」）→ **后者接管**：注册新 task 时，查同 (elementId, property) 的活跃补间 → 取其当前值作新 `fromValue`、注销旧的、从当前值补到新目标（平滑接管，不跳变）。
- **同元素不同属性**（在移动 + 又来一个变色）→ **并存**（两个 task 各管各的属性）。
- **补间 + 用户拖动/编辑同元素**：补间期间 wall 通常无编辑器开（运行时）；若开着，补间每帧落 state 广播会和用户编辑抢 → 由 lock / 排他 session 既有机制兜底（补间不特殊处理）。

---

## 7. 协议（新 Action + 序列化 / 校验 / 执行）

照 0.7.x「一个积木 = 一条 Action」范式（sealed Action + Deserializer/Serializer/Validator/Permissions case + Executor/Runner + 前端镜像）：

- **新 `Action.TweenBlock`**（挂起式包裹）：`durationMs: long` + `easing: Easing` + `body: List<Action>`（仅属性动作）。
  - `body = List.copyOf(body)`（compact ctor）。
- **`ActionDeserializer`**：`tweenBlock` case，读 durationMs / easing（复用 0.6 Easing 反序列化）/ body（`readBranch`，仅允许属性动作）。
- **`ScriptRuleValidator`**：duration ∈ [1, 上限]（config，默如 60s）；easing 合法；body 非空 + **每条必须是属性动作**（白名单）+ 同属性不重复（警告/拒绝）。
- **`ScriptPermissions`**：`edit` 面（同其他元素动作）。
- **`ScriptRunner`**：拦截 `TweenBlock`（控制流，照 if/repeat/wait 在 `instanceof` 链）→ 注册补间 + 挂起（§3.5）。
- **`ActionExecutor`**：补间注册逻辑（收集 targets + 调 `TweenScheduler`）。
- **协议版本**：升一版（照 0.7.x 干净升版范式）。
- **前端镜像**：`protocol.ts` TweenBlock 类型 + `blockDefs` 定义 + `blockTree` body 容器（泛化 `isBodyContainer`）+ `validator` 镜像 + i18n。

---

## 8. 前端（包裹积木 UI）

- **`blockDefs`**：`tweenBlock` 定义，category `control`（绿，流程控制类）或新 `tween` 类（紫，待定）；C 形 body 容器。
- **`BlockNode`**：`hasBodySlot` 泛化含 tweenBlock；头部渲染 duration（number 控件）+ easing（缓动选择器）。
- **缓动选择器**：下拉预设 + 「自定义」→ 弹时间轴的 SVG cubic-bezier 曲线编辑器（复用 0.6 P4 组件）。
- **body 拖入限制**：只接受属性动作（friendly 元素积木的移动/缩放/转动/透明度/变色子集）；拖非属性动作进来 → 拒绝 + 提示。
- **i18n**：中英（tweenBlock / duration / easing 预设名 / body 限制提示）。

---

## 9. 性能 / config（T10，守「工具不是保姆」）

- **config `scripts.tween`**：
  - `max-concurrent`（同时补间数上限，默如 16）—— 超限的补间动作返 error step（不崩、不串）。
  - `fps`（补间帧率，默 20；上限 config，不自动降级）。
  - `max-duration-seconds`（单补间最长时长，默如 60）。
- **DB 写压力（架构 A 已知代价）**：每帧 `ElementPropertyApplier` × 活跃补间数 × fps 次 state 写。缓解：
  - `TweenScheduler` 缓存 wall state（启动 load 一次，每帧只 write 变化属性，免每帧 load）。
  - config 限并发 + fps。
  - **数据透明、不自动降级**：`/canvas bench` 或日志暴露补间 DB 写频，服主自负（PROPOSAL §2.1 / §5.2.7）。
- **单线程 SES**：所有 wall 补间串行 tick；高并发下帧延迟累积由 config `max-concurrent` 兜底。

---

## 10. 分期（拟 5 phase，照 0.7.0 范式）

| 段 | 内容 | 闸 |
|---|---|---|
| **P1 ✅** | 数据模型 + 协议：`Action.TweenBlock` + 序列化/校验/permissions + 协议升 v6 + 前端类型镜像 | 编译 + 单测 |
| **P2 ✅** | 补间引擎 MVP：`TweenScheduler`（单线程 SES + TweenTask + EasingSolver 算值）+ **路径 Z `renderStatic` 渲临时态省 DB** + 挂起 + 最简前端可拼 + **per-wall 帧率**（tweenFps + 节流）+ 缓动两层 bug 修 | ✅ 实测过：招牌滑入 + 缓动 + 挂起 + 帧率可调 |
| **P3**（进行中） | 全属性 + 缓动 + 共存：多属性并行 + **color/fill 轨**（ColorLerp + fill 插值）+ 全 EasingType + **与时间轴共存**（有 timeline 墙走每帧 applyMany，§5 分流）+ 冲突接管（T8） | 实测：组合动画 + 共存 |
| **P4** | 前端 C 形包裹积木 UI + 缓动选择器（下拉 + 自定义曲线）+ body 拖入限制 + i18n | 实测：编辑器拼补间积木 |
| **P5** | config 限并发 + fps + 性能透明 + 收尾（docs / journal / 版本号） | 实测 + 收口 |

---

## 11. 开放问题（实施时回填）

- [ ] **渲染触发统一入口**：补间引擎对「有时间轴 / 无时间轴」的 wall 统一调「渲染 wall 当前 state（可选叠加时间轴）」——具体复用 `FrameRenderer.renderFrame` 还是新入口？`ticker.invalidate` 对无 entry 的 wall 无效，补间引擎要自渲。P2 定。
- [ ] **「渲临时覆盖层省 DB」优化**：补间期间维护内存覆盖层、时间轴读覆盖层叠加 → 省每帧 DB。要改 Ticker/Interpolator（违背 T2），收益 vs 复杂度，留 future 评估。
- [ ] **DB 写压力实测**：P2/P3 实测每帧 `ElementPropertyApplier` 在 N 并发补间下的 DB 写频 + mspt 影响，决定 config 默认值。
- [ ] **同属性 body 内重复**：保存期警告 vs 拒绝 vs 运行期后者胜，P1 定。
- [ ] **缓动 category 配色**：tweenBlock 归 `control`（绿）还是新 `tween`（紫），P4 定。
- [ ] **非挂起变体**：future「启动补间后脚本不等」的积木形态（独立动作 vs 包裹加 toggle）。
- [ ] **版本号**：补间作为 0.7.x 哪个子版本 / 是否触发 0.7.0 整体 release bump，收尾定。
