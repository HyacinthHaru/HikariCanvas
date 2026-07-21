# 时间轴编辑器（0.6.0）设计总纲

> **实施前必读。** 0.6.0 把"静态 / 数据驱动招牌"升级到"能做循环 / 非线性动画的动态屏"——给已有
> 元素加关键帧（keyframe）+ 缓动（easing），由服务端定 cadence 逐帧推送。
>
> 与 0.7.0 Scratch 互补：时间轴 = 对**已有内容**做预制动画（欢迎墙文字淡入循环、旋转 logo、
> 滚动公告）；Scratch = 对**实时 / 未知数据**做事件驱动逻辑编排。
>
> **注（2026-06-10 修正，scripting.md D2）**：原"一画布只能二选一"已作废。脚本是上层（条件分支 +
> 副作用），时间轴是被编排的素材（脚本可 `playTimeline`），**同画布共存**；0.6 三种触发器原样保留给
> 简单场景。
>
> 本文取代 `dynamic-data.md §13.4` 的纸面设想（§13.4 已瘦为指向本文的摘要）。可行性评估（5 维度
> 深读真实代码 + 0.5.0 Benchmark 实测）的结论已并入本文，关键 file:line 证据随文给出。

**当前能力**：5 种缓动 + 自定义贝塞尔曲线编辑器 + 9 个可关键帧属性 + 多 timeline（上限 16）+ AE 风底部
dock。各节以"当前能力"小注给出与设计取值的偏差。

---

## 0. 决策摘要

| # | 决策 | 取值 | 依据 |
|---|---|---|---|
| D1 | **Keyframe 存法** | **方案 B**：独立 `KeyframeTrack`，不塞进 8 个 Element record | 避开 8-record 横切 + 撤销深拷贝不连带 + 编辑范围解耦（同 0.7 把 script 抽出 ProjectState 的理由） |
| D2 | **KeyframeTrack 挂点** | 挂 **`Timeline.tracks`**（每条时间轴一组 `Map<elementId, 关键帧列表>`） | 一条 timeline 是自带 trigger 的独立动画；多 timeline（如"欢迎循环"vs"告警闪烁"）可对同一元素有不同动画。挂画布级会强制每元素全局一套动画。 |
| D3 | **默认帧率** | **20fps**；每墙刷新率由服主自改；`config` 给 `timeline.max-fps` 服务器级安全阀（默 **60**，宽松） | 20fps 正好是 Bukkit 一 tick（50ms），是合理默认。**不做成本估算、不自动校准、不自动降级**——同 After Effects：工具只管渲染，墙卡是服主自己的事。max-fps 是管理员保护多租户服务器（别让一面墙极端 fps 连累全场）的总阀门，非保姆（见 §3.5） |
| D4 | **MVP 缓动集** | 先只 `LINEAR`；`CUBIC_BEZIER` / `EASE_IN/OUT` 留缓动 phase | 降低首个里程碑风险，曲线编辑器可独立交付 |
| D5 | **0.6 触发器范围** | `MANUAL` + `VARIABLE_CHANGE` + `SCHEDULE` | 三者全复用现成设施（§5） |

> **当前能力（D4/D5）**：D4 五种缓动 `linear/easeIn/easeOut/easeInOut/cubicBezier` 全部实装
> （`EasingSolver`/`ColorLerp` 双端逐位等价 + 拖控制点的 `EasingCurveEditor.vue`）。D5 的
> `TriggerType` 枚举**只有 `MANUAL`/`VARIABLE_CHANGE`/`SCHEDULE` 三个常量，无 `PLAYER_NEAR`**——
> 玩家靠近类触发在 0.7.0 Scratch 的 `TriggerRouter` 独立实现，时间轴的 `TriggerType` 不含它。
| D6 | **keyframe 编辑通道** | 专用 `keyframe.*` op（高频编辑，仿 `element.*` 的 ack 模型）+ `state.patch` 广播 | keyframe 拖动是高频小改，专用 op 比通用 patch 更清晰可控 |
| D7 | **撤销** | 路线 A：coalesce key 合并同一 keyframe 连续拖动 + `MAX_HISTORY` 条件提升（16→64） | 现状 16 步全快照（非纸面"100 步"），不合并会被一次拖动吞光历史（§7） |
| D8 | **新依赖** | **零**。插值 / 缓动纯算术自写，cubic-bezier **双端各写一份 + 共享测试向量**，禁引第三方 easing 库 | 双端逐位一致是硬纪律，第三方库的浮点实现对不齐 |
| D9 | **后端唯一权威** | 游戏内最终输出永远以后端 Ticker 为准；前端本地插值仅供编辑器预览 | 同 Live Paint"前端独占工具、输出走后端"的纪律例外 |

---

## 1. 目标与范围

### 1.1 做 / 不做

**做**：给元素的可插值属性（位置 x/y、尺寸 w/h、旋转、不透明度、颜色 / Fill、文本内容）加关键帧，
按时间轴插值，服务端逐帧渲染并推送到游戏内地图；支持循环 / 一次 / 往返三种播放模式 + 多种触发方式。

**不做（留 0.7 或更后）**：可视化积木逻辑（Scratch）；条件分支 / 副作用脚本；逐字形动画（字形 advance
量化是双端痛点，§4.2）。

> **当前能力**：原列入"不做"的两项已落地——**缓动曲线编辑器**（`EasingCurveEditor.vue` 拖控制点的 SVG
> cubic-bezier 编辑器，非关键帧列表）；**AE 风底部 dock**（`TimelineDock.vue` 每元素每属性
> 子轨 + scrubber）替代最简关键帧列表。"玩家靠近触发"由 0.7.0 Scratch 承担，时间轴侧无
> `PLAYER_NEAR` 枚举。

### 1.2 能力全集

多 timeline（**上限 16**）+ **9 个可关键帧属性**（x/y/w/h/rotation/opacity 数值 + color + fill + text）+
**5 种缓动 + 自定义贝塞尔曲线** + 三种触发器（MANUAL/VARIABLE_CHANGE/SCHEDULE）+ AnimationTicker 逐帧推
+ 后端唯一权威 + AE 风底部 dock。

首个可演示纵切为"一面欢迎墙文字淡入循环"，验证 Ticker → 池化 → 渲染 → 发包 → 双端一致全链路。

---

## 2. 数据结构

全部走 **v2 nullable 加法范式**（`Element.java:63-73` 三个 nullable 字段 + `effectiveXxx()` 兜底）：
旧工程 `project_json` 无新字段 → Jackson 反序列化填 null → 完全静态行为、baseline 零漂移。

### 2.1 `Timeline`

```
record Timeline(
    String id,              // "tl-<8hex>"
    String name,            // 用户可读名
    int durationMs,         // 总时长
    int fps,                // 该条时间轴帧率（D3：默认 20，受 config max-fps 钳）
    LoopMode loopMode,      // ONCE / LOOP / PING_PONG（缺省 LOOP）
    TriggerConfig trigger,  // 触发方式（§5；缺省 TriggerConfig.MANUAL）
    Map<String, List<Keyframe>> tracks   // D2：elementId -> 该元素的关键帧列表（方案 B）
)
enum LoopMode { ONCE, LOOP, PING_PONG }   // wire: once / loop / pingPong（camelCase，≠ Java 名）
```

> **代码核对**：`Timeline` canonical 构造器缺省 `loopMode=LOOP` + `trigger=TriggerConfig.MANUAL`
> （`Timeline.java`）。**wire ≠ Java 名**：`LoopMode` wire 是 `once`/`loop`/`pingPong`（camelCase）。
> `tracks` 反序列化期宽容滤 null 关键帧、深冻结保序。

> D2 取"tracks 挂 Timeline 上"。`tracks` 的 key 是 elementId，值是按 `timeMs` 升序的关键帧列表。
> 一个元素在一条 timeline 里**每个 property 一串关键帧**——见 §2.3 的 `property` 字段（同一 element
> 的不同 property 关键帧混在该 element 的列表里，按 property 分组）。

### 2.2 `KeyframeTrack`（方案 B 落地形态）

方案 B = keyframe **不进 Element record**。逻辑上"一条 track = 某元素某属性的关键帧序列"，物理上压平
进 `Timeline.tracks: Map<elementId, List<Keyframe>>`，每个 `Keyframe` 自带 `property` 标识它属于哪条
属性轨。前端按 `(elementId, property)` 分组渲染成多轨。

**为什么不单列一个 `KeyframeTrack` record**：压平成 `Map<elementId, List<Keyframe>>` 让 `state.patch`
路径最浅（`/timelines/<i>/tracks/<elementId>/<k>`），且 keyframe 增删 = 列表项增删，与现有
`/layers/<i>/elements/<j>` 数组 patch 同构（`project.ts` applier 只多认一段 token）。

### 2.3 `Keyframe`

```
record Keyframe(
    String id,          // "kf-<8hex>"，coalesce / patch 定位用
    String property,    // "x"/"y"/"w"/"h"/"rotation"/"opacity"/"color"/"fill"/"text"
    int timeMs,         // 在 timeline 内的时刻
    KfValue value,      // 多态：数值 / 字符串 / Fill（见下）
    Easing easing       // 到**下一个**关键帧的缓动（最后一帧的 easing 无意义）
)
```

`value` 多态（数值 / 字符串 / Fill）**复用现有 `FillDeserializer` 的多态反序列化范式**
（`Fill.java:24` + `FillDeserializer.java:28-49`，string→Solid / object 按 `type` 分流）——不需新序列化
基建。`value` 也可以是 `${var:X}` 字符串（取值时机见 §4.1）。

按 `property` 的可插值性分三类：
- **数值类**（x/y/w/h/rotation/opacity）：线性 + easing 插值。
- **Fill / color 类**：渐变插值（在 sRGB 线性空间，§4.4）。
- **离散类**（仅 `text`）：不插值，取 `timeMs <= 当前` 的最近关键帧（step）。可加关键帧的属性白名单共
  **9 个**（`Keyframe.PROPERTIES`：x/y/w/h/rotation/opacity/color/fill/text），**无 `fontId`**。

### 2.4 `Easing`

```
record Easing(EasingType type, List<Double> bezier)  // bezier 仅 CUBIC_BEZIER 用：[x1,y1,x2,y2]
enum EasingType { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, CUBIC_BEZIER }
// wire: linear / easeIn / easeOut / easeInOut / cubicBezier
// （wire 用 camelCase 字符串，与 Java enum name 仅大小写不同，由 @JsonProperty 映射）
```

> **代码核对**：`bezier` 在 Java 端是 `List<Double>`（非 `double[]`），缺省 `Easing.LINEAR`
> （`Easing.java`）。**wire 用 camelCase 字符串，与 Java enum name 仅大小写不同**（同物两种写法，由
> `EasingType.java` 的 `@JsonProperty` 显式映射：`LINEAR`→`linear` / `EASE_IN`→`easeIn` /
> `EASE_OUT`→`easeOut` / `EASE_IN_OUT`→`easeInOut` / `CUBIC_BEZIER`→`cubicBezier`）。

> **当前能力（D4 已超出）**：**5 种缓动全部已实装**——`LINEAR` + `EASE_IN/OUT/IN_OUT` 三预设 + 完整
> `CUBIC_BEZIER`。求值器 `EasingSolver`（Java）/ `web/src/timeline/easing.ts`（TS）双端逐位等价；前端
> `EasingCurveEditor.vue` 提供拖控制点的自定义贝塞尔曲线编辑器。`EASE_IN/OUT/IN_OUT` 是 `CUBIC_BEZIER`
> 的预设控制点（取 CSS 同名关键字标准值）。权威定义落 `rendering.md §9.3`。

### 2.5 `TriggerConfig`

```
record TriggerConfig(TriggerType type, Map<String,String> params)
enum TriggerType { MANUAL, VARIABLE_CHANGE, SCHEDULE }
```

> **代码核对**：`TriggerType` 实际**只有 3 个常量 `MANUAL`/`VARIABLE_CHANGE`/`SCHEDULE`，没有
> `PLAYER_NEAR`**（`TriggerType.java`）。**wire ≠ Java 名**：协议 wire 是 camelCase
> `manual`/`variableChange`/`schedule`（`@JsonProperty` 显式映射），不是 Java enum name。`TriggerConfig`
> 缺省常量 `TriggerConfig.MANUAL`（type=MANUAL + 空 params）。

详见 §5。`VARIABLE_CHANGE` 的 `params.fullName` 须经 `VariableInterpolator.resolveFullName`
注入 wallId 才能匹配内部形式，否则 listener 永不命中（§5.2 R）。

### 2.6 `ProjectState` / `Element` 的加法

- `ProjectState`：加 `List<Timeline> timelines`（nullable）+ `String activeTimelineId`（nullable，
  null = 静态画板）。走 `ProjectState.java:140` 的 `@JsonCreator` 入口加 `@JsonProperty` 参数，缺失退
  `List.of()` / `null`。
- **`Element` 不动**（方案 B 的核心好处）——8 个 record（`Element.java:40-41` permits）零改动。

### 2.7 持久化（无新表）

方案 B 下 timelines + tracks 都是 `ProjectState` 字段，**序列化进 `project_json` blob**（和 layers/
elements 同级），**不需要新 SQLite 表**，`.canvas` 导出天然带上。

`data-model.md §2.4.1` 现有 "project_json v1→v2 lazy migration"，0.6 加一条 **v2→v3**：旧 v2 blob 无
timelines 字段 → 读为 null → 静态。`ProjectState.PROTOCOL_VERSION` 2→3（`ProjectState.java`）。

> **代码核对**：`ProjectState.PROTOCOL_VERSION` 当前 = **3**（这是 state blob 的版本号，0.6 后未再动）。
> 注意它与**业务协议号**是两层（见 §6 校正）：业务协议（WS op / payload schema，`Protocol.SUPPORTED_MIN/
> MAX`）已随 0.7.x 多次升版到 **v7**；时间轴 op 自 v3 起一直在协议内，不因业务协议号上升而失效。

---

## 3. 渲染管线

### 3.1 `AnimationTicker`（新建，核心）

**现状**：全项目没有"主动定 cadence 产帧"的东西。`ProjectionThrottler.submit` 永远由外部 op / 变量
变化触发，无 op 则彻底静止（这是 `architecture.md §5.1`「静止 0fps」的实现）。

**AnimationTicker** = 独立 `ScheduledExecutorService`，按 `timeline.fps` 定 cadence（20fps → 50ms，
config max-fps 默 60 → 16.7ms 为上界）tick。每 tick：对每个活跃动画 wall，按 `loopMode` 推进 `timeMs` → 用
`KeyframeInterpolator` 算出插值后
的临时 `ProjectState`（record copy，只改值不改结构，仿 `CanvasCompositor.maybeInterpolateText`
`:315`）→ 调 **`CanvasProjector.projectByWall(wallId)`**（`CanvasProjector.java:255`）出帧。

**关键约束（容易踩的坑）**：
- **不能依赖 Bukkit 定时器**——Bukkit scheduler 最细 1 tick = 50ms（=20fps 整），**给不出高于 20fps 的
  刷新率**。统一用独立 `ScheduledExecutorService` 覆盖到 config max-fps 全范围。
- **照 `VariableProviderDaemon`（`variable/provider/VariableProviderDaemon.java:29`）造**——它已是
  `ScheduledExecutorService` + `scheduleAtFixedRate` + 三层异常隔离 + 幂等关停的成品参考。
- **viewer-gated**：`findViewersForWall(wallId)`（`CanvasProjector.java:177`）返空就停 tick，否则空墙也
  高 fps 烧 CPU——违反"工具不是保姆"。

### 3.2 与反应式 throttler 分流

动画 wall 走 Ticker；静态 wall 走原 `ProjectionThrottler`。当某 wall **既有活跃动画又有人在编辑器里
编辑**时，两条产帧路径会对同一 wallId 重复写 `HikariCanvasRenderer.update(mapId)`。需在装配层 gate：
动画接管期间，编辑 op 产生的 reactive flush 退让给 Ticker（仿 `sessionIntervalOverride` 范式）。

> 注：`ProjectionThrottler.setIntervalForSession`（`:137`）只是**放宽节流上限**，不会自驱产帧——
> **不能**当 Ticker 用。它仅用于"编辑器内预览动画时把节流放宽到 33/66ms"。

### 3.3 `BufferedImage` 池化

`rasterize` 每帧 `new BufferedImage(...TYPE_INT_ARGB)`（`CanvasCompositor.java:179`）。分配速率（ARGB
= 4 字节/像素 × 像素数 × fps）：

| 画布 | 每帧 | 20fps 速率（默认） | 60fps 速率（上界） |
|---|---|---|---|
| 2×2 文字墙 | 256 KiB | 5.0 MB/s | 15 MB/s |
| 4×2 欢迎墙 | 512 KiB | 10 MB/s | 30 MB/s |
| 8×8 大屏 | 4 MiB | 80 MB/s | 240 MB/s |
| 蒙版图片铺满 | **42 MB/帧**（实测，ImageRenderer 内部 off-buffer + mask + box blur）| 840 MB/s | **2.52 GB/s** |

小文字墙不必池化；**8×8 多墙叠加 + 蒙版图片是池化的真正动机墙**（蒙版图高 fps 物理不可行）。

**池化与"rasterize 纯函数并发安全"的冲突**（`CanvasCompositor.java:50-52` 注释保证靠每次 new）：
**池化只在 Ticker 单线程内做**——Ticker 是单 `ScheduledExecutorService` 线程串行出帧，每帧借一张、量化
完归还（`Graphics2D.clearRect` 复用而非 new），不跨线程借还，不破坏 rasterize 的并发契约。

### 3.4 帧间脏区

动画帧间脏区 ≈ 整画布，dirty-region 增量基本失效。两条路：(a) 接受全画布重渲（rasterize 复用即可）；
(b) **per-map 帧间 diff**：只发"像素变了的 map"（不是变了的像素）——MC 地图 packet 支持发 sub-rect，
可只发 dirty 子矩形而非全 128²。(b) 是基本的不浪费（同 AE 不重编码没变的帧），就该做，不 gate 在实测上。

### 3.5 帧率策略（D3 展开）

**落地（极简，照 After Effects）**：
1. `Timeline.fps` 是服主显式参数，**默认 20fps**（= 一个 Bukkit tick，合理默认）。config 段
   `timeline.default-fps`（默 **20**）= 新建 timeline 的初始帧率；`timeline.max-fps`（默 **60**）= 服务器级硬
   上限。default-fps 在加载时自动钳到 max-fps 之内（`HikariCanvasConfig.TimelineConfig`）。
2. 每墙刷新率服主可自改；`config` 给 `timeline.max-fps` 服务器级安全阀（默 **60**，宽松）——它是管理员
   保护多租户服务器（别让一面墙的极端 fps 拖垮渲染线程/网络、连累别的玩家和插件）的总阀门，**不是保姆**。
   op 层 clamp 每条 timeline 的 fps 到 `[1, max-fps]`。
3. **不做成本估算、不自动校准、不自动降级**。AE 不会因为你电脑卡就偷偷降你的导出分辨率——工具只管渲染，
   墙卡是服主自己的事。想知道自己机器能扛多少，服主自己跑独立的 `/canvas bench`（0.5.0，与时间轴解耦）。

---

## 4. 双端插值 + 缓动（一致性核心）

> 数学的**权威定义落 `rendering.md` 新节**（双端镜像清单的延伸，同 TTF/禁抗锯齿那条纪律）。两端
> （Java `KeyframeInterpolator` + 前端 `interpolation.ts`）照同一份实现。本节是设计概述，**rendering.md
> 先于代码写**。

### 4.1 取值时机

`rasterize` 内变量替换在每帧重新 resolve（`maybeInterpolateText` `:315`，store 缓存值、任意线程可调）。
固化语义：
- **数值 / Fill 类 keyframe 引用 `${var:X}`**：Ticker 在**插值前**先把 `${var:X}` resolve 成当前值，再
  对数值做 easing 插值（需给 `VariableInterpolator` 加 `resolveAsNumber`）。
- **字符串类 keyframe**：整段当文本，插值（step 取最近帧）后由 rasterize 的 `maybeInterpolateText` 统一
  resolve（每帧取最新值，免费）。
- cached 值在异步线程安全；`VariableInterpolator` 的 `MAX_INTERPOLATE_DEPTH=2`
  双扫描兜底防嵌套。

### 4.2 逐类型插值规则

| property 类 | 规则 |
|---|---|
| 数值（x/y/w/h/rotation/opacity） | 线性 + easing：`v = a + (b-a) × ease(t)` |
| Fill / color | 按 stop 对齐做分量插值，**sRGB 线性空间**（§4.4） |
| 离散（仅 text） | step：取 `timeMs <= 当前` 的最近关键帧，不插值（白名单仅 9 属性，**无** `fontId`） |

**字形动画不做**：逐字 advance 量化是双端已知痛点（CLAUDE.md），文本只整体属性动画 + 内容 step
切换，不做字形级 morph。

**基值覆盖语义（按属性粒度）**：打了关键帧的属性在播放期间以关键帧求值
为准——**基值（ProjectState 里的元素字段值）被覆盖**，编辑器里拖动 / 改该属性对游戏内播放无视觉
反映（动画软件标准语义）。未打关键帧的属性与内容（文本 / 颜色 / 未建轨的几何属性 / 新增元素）来自
基值 state，编辑落库 → Ticker invalidate → 下一帧（≤1 帧延迟）即反映。

### 4.3 cubic-bezier（双端逐位等价）

自写求解器（约 30 行），双端各一份：给定控制点 (x1,y1,x2,y2) + 输入进度 t（0..1），先用**固定步数
牛顿迭代**（迭代次数 + 终止阈值两端写死相同，如 4 步 / 1e-6）解出贝塞尔参数，再求 y。**禁引第三方
库**（D8）——浮点实现对不齐会让 snapshot 在边界像素抖动。

### 4.4 色彩插值空间

颜色 / Fill 插值统一在 **sRGB 线性空间**双端约定一处（gamma 解码→线性插值→编码），避免"在 8-bit
sRGB 直接线性插值"导致的中间色偏暗。这条写进 `rendering.md`。

### 4.5 一致性验证

现有 `RendererSnapshotTest` 是"无时间维度"的（读 fixture → `rasterize(state)` 一次 → 比单张 PNG，
`:119-122`）——**测不了 easing 双端一致**。新增两层防线：
1. **缓动测试向量**：`easing.json`（控制点 + 一批 t → expected progress，容差 1e-6），Java 端 + TS 端
   各跑同一向量。纯算术、跨平台稳定。
2. **多帧 snapshot**：新 `rasterize(state, timeMs)` 路径 + 带 timeline 的 fixture，同一 timeline 在
   t=0/250/500/750ms 各出一张 PNG 比基线（注意：文本 fixture 因 AWT 度量差已在 CI 跳过，缓动 fixture
   用纯几何元素避开字体）。

---

## 5. 触发器

### 5.1 范围（D5）

**0.6 做（全部已落地）**：`MANUAL`（玩家/命令/编辑器播放）、`VARIABLE_CHANGE`（变量变触发）、
`SCHEDULE`（到点触发）。

理由：三者全复用现成设施（下）。"玩家靠近"类触发未进时间轴 `TriggerType` 枚举——它要从零建事件层
（规划期代码库 0 个 `PlayerMoveEvent` / 距离监听），且与 0.7 Scratch 触发系统重叠，故合到 **0.7.0 Scratch
的 `TriggerRouter`** 里独立实现（已完工：进服 / 击杀 / 玩家靠近 / 离开区域 / 退服等）。时间轴侧从未引入
`PLAYER_NEAR` 常量。

### 5.2 各 trigger 设计

- **MANUAL**：WS op `timeline.play/pause/seek`（owner 触发）+ 一个"wall ready 自动播 LOOP"的简化默认。
- **VARIABLE_CHANGE**：注册一个 `VariableChangeListener`（`VariableStore.java:244`），`onChange` 里读
  `event.referencingWalls()`（已是精确的"引用该变量的 wall 集合"）触发播放——**零新基础设施**。
  - R（一致性坑）：trigger 配的 `user/X` 必须经 `resolveFullName`（`VariableInterpolator.java:160`）注入
    wallId 才能匹配内部形式 `user:<wallId>/X`。存原始 rawName，注册时统一过 resolveFullName，加 4
    namespace 单测。
  - 去抖：高频变量（`/eta_seconds` 等）绑 VARIABLE_CHANGE 会每秒重触发 → 状态机"已播放则忽略重复"，
    复用 `isWallHighFreq` 高频 key 名单警示。
- **SCHEDULE**：复用 `ManualScheduleProvider` / `RailScheduleProvider` 的 schedule 变量 + 挂
  VARIABLE_CHANGE listener，不造新调度器。

> **触发实现**：核心 = `render/TimelineTriggerRegistry`（薄索引 `解析后fullName →
> Set<(wallId,timelineId)>` + 监听）。装配在 `HikariCanvas.onEnable` 加第 3 个 `VariableChangeListener`，
> 只在 VALUE_SET / UPDATED / CREATED 时调 `onVariableChange`；命中 → `AnimationTicker.play`（任意线程安全，
> 重播 = ONCE「每次变化重播」语义）。**去抖 200ms**（per-(wall,timeline)，挡 `eta_seconds` 等高频变量 thrash）。
> trigger 配的 `user/X` 经 `VariableInterpolator.resolveFullName` 注入 wallId → `user:<wallId>/X` 才匹配
> 事件 fullName（§5.2 一致性坑 R）。**自动播门控**：`AnimationTicker` 的「wall ready 自动播 LOOP」改为只对
> `MANUAL` 触发生效；VARIABLE_CHANGE / SCHEDULE 不自动播、改登记进 registry。索引随编辑持久化 / session
> 关闭 / wall 删除增量重建（挂 `SessionManager` 现有 ticker hook 同源点）。编辑器内预览不受触发器影响
> （始终手动 / 本地 scrubber）。

### 5.3 与 0.7 Scratch 的收敛

0.6 的 VARIABLE_CHANGE 是"注册一个 ChangeListener"的简单版；0.7 的 `TriggerRouter`（实际类名，非
规划期所称 `TriggerListenerRegistry`）泛化为统一触发路由。**0.6 的简单版被 0.7 吸收**（不重写）——0.6 落地
为薄索引 `render/TimelineTriggerRegistry`（构造参数全是 seam：player / resolver / wallSource / clock），
0.7 的 `TriggerRouter` 走自己一套触发器，二者并存。

---

## 6. 协议（state v2 → v3；业务协议号现 v7）

`Protocol.SUPPORTED_MIN/MAX` 双向校验设施 M16.6 已建（`web/Protocol.java`），升 v3 走既有路径。

> **代码核对（两层版本号）**：
> - **state blob 版本** `ProjectState.PROTOCOL_VERSION` = **3**（0.6 引入 timelines 时升的，之后未动）。
> - **业务协议版本** `Protocol.SUPPORTED_MIN/MAX` = **7**（每升一版 = 干净切换 MIN=MAX 同步提升）。
>   时间轴的 `timeline.*` / `keyframe.*` op 自业务协议 v3 起进入协议，0.7.x 多次 bump（v4 script.* /
>   v5 新触发器+Repeat / v6 tween / v7）后**仍在协议内**——业务号上升不影响时间轴 op 可用性。下文
>   "v3"措辞理解为"时间轴 op 自该版起在协议内"即可。

### 6.1 新 op（在 `EditOpDispatcher.dispatch` switch 加 case，`EditOpDispatcher.java:104`）

- `timeline.create / update / delete`
- `keyframe.add / update / delete / move`（D6：专用高频 op）
- `timeline.play / pause / seek`（编辑器预览,不落 DB）

### 6.2 state.patch 路径

`/timelines/<i>/...`、`/timelines/<i>/tracks/<elementId>/<k>/...`——走现有 JSON Pointer 分拣（仿
`/variables/`、`/aliases/` 前缀分拣，`wsClient.ts:623`）。keyframe 数组增删 = 列表项 add/remove，前端
`project.ts` applier 加 `/timelines/` 分支 + keyframe 数组路径。

### 6.3 编辑器预览

scrubber 拖动 / 本地播放**纯前端**（不发 WS，60fps 跟手）；`timeline.play/pause/seek` 只用于"在真实
部署 wall 上启停后端 Ticker"。游戏内最终输出永远后端权威（D9）。

---

## 7. 撤销 / 历史

### 7.1 现状

`HistoryStack.MAX_HISTORY = 16`（`HistoryStack.java:26`），且**每步全状态深拷贝
快照**（`ProjectSnapshot` 克隆整棵 layers 树，`ProjectSnapshot.java:31-44`），调用点遍布每个 mutator。
keyframe 拖动是高频小改（一秒几十次 op），不处理会**瞬间填满 16 步、把拖动前的真实编辑历史全挤掉**。

### 7.2 coalescing（D7 路线 A）

- **coalesce key**：默认 `{elementId}:{keyframeId}:{property}`（单属性连续拖动 / 滑块合并）。同 key 的连续 op
  在 `commitHistory` 处**合并**（不 push 新快照，只更新栈顶 + `future.clear()`），加一个时间窗（500ms 内算
  同一次拖动）。
- **整体帧批量 op**：一个用户动作（拉就设 / 加帧 / 整体块拖动）一次性写元素全部 transform 属性 =
  多条 `keyframe.*` op。前端给这组 op 传**同一个** `coalesceKey`（如 `integ:{elementId}:{timeMs}`），后端优先用
  它合并 → 一次撤销整组回收。**缺省回退**到上面的单帧默认键，向后兼容（见 `protocol.md §5.13`）。这保证
  整体帧的 6 个属性一次 ctrl+z 整组回收、整体帧块整块消失。
- `MAX_HISTORY` 条件提升：有 `activeTimelineId` 的工程 16→64。**提升是会话内粘性的**——一旦观察到激活
  时间轴即保持 64，不随时间轴删除回落；否则「删最后一条时间轴」的 commit 自身会按 16 当场 trim，
  瞬间丢弃最多 48 条真实历史。
- **路线 B（history 改 op 式 / inverse-op）留远期**——op coalescing 改成 op 式的工作量大。0.6 只做路线 A。
- 方案 B 的附带好处：keyframe 不在 Element 里 → `ProjectSnapshot` 深拷贝 element 时**不连带拷贝 keyframe
  列表**，撤销性能不随 keyframe 数膨胀。

---

## 8. 前端编辑器

### 8.1 面板结构

底部可折叠 dock（200–320px，`App.vue` 在 `flex-1` 与 StatusBar 间插一个高度槽，`CanvasView.fitToViewport`
联动）。组成：
- 左：轨道列表（每元素一组，可展开为每属性子轨）——复用 `LayerPanel.vue` 嵌套 v-for + 拖拽状态机。
- 中：时间标尺 + 网格 + 关键帧点 + 播放头 scrubber。
- 顶：播放控制（play/pause/loop/fps + 当前时间）。
- 缓动曲线编辑器（贝塞尔手柄，纯 SVG）。

> **实现位置**：`web/src/components/timeline/`——`TimelineDock.vue`（AE 风底部 dock，每元素每
> 属性子轨 + 时间标尺 + scrubber）+ `EasingCurveEditor.vue`（拖控制点的 SVG cubic-bezier 编辑器）
> + `timelineLogic.ts`（纯逻辑）。双端插值器在 `web/src/timeline/`
> （`interpolation.ts` / `easing.ts` / `colorLerp.ts`）。

**自写时间轴 UI，不引库**（与项目"Konva 画布 + 其余手写 Vue"口味一致；第三方时间轴库多 React/canvas，
集成成本 > 自写）。**懒加载拆 chunk**（仿 Lexical），否则破 700KB bundle 线。

### 8.2 本地预览 vs 后端权威（D9）

- **编辑期 scrubber 必须本地**（60fps 跟手，往返后端不可接受）：`useTimelinePlayback` 持一份"插值后
  瞬时快照"局部 ref，直接喂 `renderProjectState`，**绕开 `project.state` 的 deep watch**。
- **只对几何 / 数值 / Fill 做本地插值**，文本内容动画靠后端推帧（双端一致面积最小化）。
- 游戏内运行时永远后端权威。

### 8.3 复用点（省工）

`renderProjectState` 纯函数（`PreviewRenderer.ts:22`）/ `requestDraw` 已 rAF 合帧 / 拖拽排序范式
（`LayerPanel.vue:131`）/ 属性段 `sendUpdateDebounced`（`RightPanel.vue:55`）/ 选中态双向联动
（`ui.ts selectedIds`）/ keyframe 拖动照 onDragMove 模型"拖时本地 mutate、dragend 才发一条 WS"
（`CanvasView.vue:866`，前端天然只发终值，不新增 op 流量）。

---

## 9. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| `BufferedImage` GC（8×8 多墙 / 蒙版图 42MB/帧） | 高 | 池化（§3.3）；蒙版图高 fps 物理不可行，池化前禁 |
| 撤销栈被 keyframe 编辑吞光 | 高 | coalescing（§7.2），MVP 必做 |
| 双端 cubic-bezier 不一致 | 高 | 自写 + 共享测试向量 + 多帧 snapshot（§4.5） |
| Bukkit 定时器给不出高于 20fps | 中（已知解） | 独立 `ScheduledExecutorService`（§3.1） |
| packet 带宽（高 fps × N viewer 全量 map） | 中（服主自负） | per-map 帧间 diff（§3.4，基本不浪费，直接做） |
| Ticker 与编辑 op 并发改 state | 中 | 进 EditSession monitor，复用 `projectUnderEditLock` 锁范式 |
| 前端 deep watch 性能 | 中 | 预览走局部快照 ref，绕开 deep watch（§8.2） |
| bundle 破 700KB | 低 | timeline panel 懒加载拆 chunk |

---

## 10. 未决问题

- 多 timeline 切换（`activeTimelineId`）的 UX：编辑器同时编辑多条还是一次一条
- `.canvas` 导入到无该元素的工程时，孤儿 keyframe track 的处理（丢弃 / 保留）
- EASE 预设控制点值（已固化）：`linear=[0,0,1,1]` / `easeIn=[0.42,0,1,1]` /
  `easeOut=[0,0,0.58,1]` / `easeInOut=[0.42,0,0.58,1]`（取 CSS 同名关键字标准值，`EasingCurveEditor.vue`
  PRESET_POINTS + rendering.md §9.3）。
- `timeline.play/pause/seek` 不持久化"上次播放位置"：pause 记内存位置，重启后 LOOP 墙总是自动播
  （`AnimationTicker`，play 暂停态恢复保位置 / 否则从 0 起）。
