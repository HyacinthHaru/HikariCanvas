# 时间轴架构规范

**定位**：本文档定义 HikariCanvas 关键帧时间轴系统的完整契约，涵盖数据模型、轨道组织、服务端 `AnimationTicker` 逐帧渲染管线、双端插值与缓动一致性、`TimelineTriggerRegistry` 触发机制、WebSocket 协议、撤销合并策略与前端时间轴面板。

**关联契约**：`docs/rendering.md §9`（双端插值、缓动求解与线性 sRGB 色彩数学权威定义）· `docs/protocol.md §5.12 / §5.13 / §7`（时间轴与关键帧 WebSocket op 及状态模型）· `docs/data-model.md §2.4.2`（`project_json` 持久化结构）· `docs/architecture.md §5.5`（投影管线与节流分流）· `docs/scripting.md`（积木脚本编排时间轴与补间共存）。

---

## 1. 定位与范围

### 1.1 功能边界

- **核心职责**：为画布元素的几何、色彩与文本属性挂载关键帧（Keyframe）与缓动函数（Easing），支持单次（`once`）、循环（`loop`）与往返（`pingPong`）三种播放模式及手动、变量变化、时刻表三种自触发机制，由服务端按设定帧率逐帧插值渲染并推送到游戏内地图墙。
- **与积木脚本同画布共存**：时间轴负责确定性的时间维度预制动画（如欢迎墙淡入循环、旋转徽标、到站闪烁）；积木脚本（`docs/scripting.md`）负责事件驱动的条件分支与副作用，并可通过 `playTimeline` / `playTimelineAwait` 将时间轴作为编排素材启停或跳转。两套系统在同一画布上正交共存。
- **不做逐字形变形动画**：由于 TTF 字形 advance 栅格化量化受双端整数像素网格约束，文本元素仅支持整体几何/透明度/颜色插值及文本内容离散阶跃切换（step），不做单字拆解或路径 morph。

### 1.2 能力概览

单墙支持最多 **16 条独立时间轴**、**9 个可关键帧属性**（数值类 `x` / `y` / `w` / `h` / `rotation` / `opacity`、色彩填充类 `color` / `fill`、离散文本类 `text`）、**5 种缓动模式**（线性 + 3 种标准预设 + 自定义三次贝塞尔曲线控制点）、**3 种触发器**（`MANUAL` / `VARIABLE_CHANGE` / `SCHEDULE`）、服务端唯一权威产帧引擎 `AnimationTicker`，以及编辑器底部多轨时间轴面板。

---

## 2. 数据结构

时间轴字段采用可空加法演进挂载在 `ProjectState` 顶层：不包含时间轴字段的旧版 `project_json` 反序列化时自动回退为空列表与 `null`，保持纯静态画板行为。

### 2.1 `Timeline`

```java
record Timeline(
    String id,                           // "tl-<8hex>"
    String name,                         // 用户可读名称
    int durationMs,                      // 总时长（毫秒）
    int fps,                             // 目标帧率（默认 20，受服务器 timeline.max-fps 钳制）
    LoopMode loopMode,                   // ONCE / LOOP / PING_PONG（缺省 LOOP）
    TriggerConfig trigger,               // 触发配置（缺省 TriggerConfig.MANUAL）
    Map<String, List<Keyframe>> tracks   // elementId -> 按 timeMs 升序排列的关键帧列表
)
enum LoopMode { ONCE, LOOP, PING_PONG }  // wire: "once" | "loop" | "pingPong"（camelCase）
```

- **Wire 映射与反序列化防御**：`LoopMode` 在协议层序列化为 camelCase 字符串（`once` / `loop` / `pingPong`）；`Timeline` 构造器对缺省 `loopMode` 回退 `LOOP`、缺省 `trigger` 回退 `TriggerConfig.MANUAL`，并在反序列化期过滤空关键帧且深冻结 `tracks` 映射以保证不可变线程安全。
- **`tracks` 挂于 `Timeline` 内**：每条时间轴独立持有一组 `elementId -> List<Keyframe>`，允许同一面墙上的多条时间轴（例如「日常循环」与「进站告警」）对同一个元素定义互不干扰的动画轨迹。

#### 唯一性不变量：同一 `(element, property, timeMs)` 仅允许存在一帧

服务端 `TimelineOperations` 在所有写入入口强制维护该不变量：

| 写入操作 | 命中同 `(property, timeMs)` 已有帧时的处置 |
| --- | --- |
| `keyframe.add` | **覆盖**该帧的 `value` 与 `easing`（语义等价于更新），不追加重复帧，返回整轨替换 patch |
| `keyframe.move` / `keyframe.update` 修改 `timeMs` | 迁入帧**胜出保留**，目标时刻原有的旧帧被移除 |

在写入侧阻断重合帧可彻底避免多个关键帧在 UI 同一时刻叠点遮挡、导致修改或删除失效。`docs/rendering.md §9.1` 中「区间两端 `timeMs` 相等时取后一帧」的求值规则仍保留作为历史存量数据的兼容兜底，前端 `spanAt` / `planTransformUpsert` 与之保持一致。

### 2.2 关键帧轨道组织（Keyframe Tracks）

关键帧数据与元素本体解耦，**不嵌入 8 个 `Element` record 内部**，而是扁平存储在 `Timeline.tracks: Map<String, List<Keyframe>>` 中，由每个 `Keyframe.property` 字段标识其所属属性子轨，前端按 `(elementId, property)` 二级分组渲染为元素折叠组与属性子轨。

该结构带来两项核心收益：
1. **浅层 Patch 路径**：`state.patch` 路径固定为 `/timelines/<i>/tracks/<elementId>/<k>`，关键帧增删直接映射为 JSON Pointer 数组项增删，与 `/layers/<i>/elements/<j>` 同构。
2. **快照与编辑解耦**：元素静态属性编辑无需触碰时间轴数据，且历史栈深拷贝元素列表时不会连带复制庞大的关键帧集合。

### 2.3 `Keyframe`

```java
record Keyframe(
    String id,          // "kf-<8hex>"，供 patch 定位与连续拖动撤销合并使用
    String property,    // 9 个白名单属性之一
    int timeMs,         // 时间轴内时刻（0 <= timeMs <= durationMs）
    KfValue value,      // 多态值：数值 / 字符串 / Fill
    Easing easing       // 当前帧到同属性下一关键帧区间的缓动函数（末帧 easing 不参与求值）
)
```

`KfValue` 复用 `FillDeserializer` 的无标签多态反序列化模式（数值/字符串/渐变对象自动分流），同时允许写入 `${var:X}` 动态变量占位符（求值时机见 §4.1）。

可添加关键帧的属性白名单（`Keyframe.PROPERTIES`）严格固定为 **9 项**（不含 `fontId`）：
- **数值类（6 项）**：`x`、`y`、`w`、`h`、`rotation`、`opacity` —— 按缓动进度连续线性插值。
- **色彩与填充类（2 项）**：`color`（文本字色）、`fill`（几何/图标/笔刷填充）—— 在线性 sRGB 空间做色彩或同构渐变 stop 插值。
- **离散类（1 项）**：`text` —— 不做中间插值，按阶跃（step）取 `timeMs <= 当前时刻` 的最近一帧。

### 2.4 `Easing`

```java
record Easing(EasingType type, List<Double> bezier) // bezier 仅 CUBIC_BEZIER 非空：[x1, y1, x2, y2]
enum EasingType { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, CUBIC_BEZIER }
// wire: "linear" | "easeIn" | "easeOut" | "easeInOut" | "cubicBezier"
```

- `EasingType` 通过 `@JsonProperty` 显式映射为 camelCase 字符串；缺省值为 `Easing.LINEAR`。
- `CUBIC_BEZIER` 要求 `bezier` 恰好为 4 个有限浮点数，且横坐标满足 $x_1, x_2 \in [0, 1]$；非 `CUBIC_BEZIER` 类型禁止携带 `bezier` 数组。
- 预设曲线采用 CSS 标准控制点：`linear = [0, 0, 1, 1]`、`easeIn = [0.42, 0, 1, 1]`、`easeOut = [0, 0, 0.58, 1]`、`easeInOut = [0.42, 0, 0.58, 1]`。数学定义见 `docs/rendering.md §9.3`。

### 2.5 `TriggerConfig`

```java
record TriggerConfig(TriggerType type, Map<String, String> params)
enum TriggerType { MANUAL, VARIABLE_CHANGE, SCHEDULE }
// wire: "manual" | "variableChange" | "schedule"
```

- 时间轴自身的触发器枚举仅含 `MANUAL`、`VARIABLE_CHANGE`、`SCHEDULE` 三项（空间距离、右键交互、进退服等游戏事件触发统一归属积木脚本 `TriggerRouter`）。
- 缺省值为 `TriggerConfig.MANUAL`（`type = MANUAL`，空 `params`）。

### 2.6 `ProjectState` 与 `Element` 结构

- **`ProjectState`**：持有可空字段 `List<Timeline> timelines`（缺省空列表）与 `String activeTimelineId`（`null` 表示无激活时间轴）。单工程最多允许 16 条时间轴、单条时间轴最多 2048 个关键帧、单属性轨最多 256 个关键帧。
- **`Element`**：8 种元素 record 结构保持纯粹静态字段，不含任何时间轴耦合属性。

### 2.7 持久化

`timelines` 与 `activeTimelineId` 随 `ProjectState` 直接序列化进 `walls.project_json` 列，无需独立数据库表，导出 `.canvas` 工程包或模板包时天然随 `project.json` 一起打包。

版本号分属两个独立维度：
- **状态序列化版本**：`ProjectState.PROTOCOL_VERSION = 3`（标识 `project_json` schema 含多层与时间轴字段）。
- **WebSocket 业务协议版本**：`Protocol.SUPPORTED_MIN / MAX = 8`（随新增业务 op 演进；时间轴与关键帧 op 持续包含在现行协议集中）。

---

## 3. 渲染管线

### 3.1 `AnimationTicker`（服务端主动产帧引擎）

静态画板遵循「静止 0fps」原则，仅在编辑操作或变量更新时由 `ProjectionThrottler` 被动触发重绘。`AnimationTicker` 则提供主动定频产帧能力：

- **独立单线程调度器**：内部持有守护线程 `ScheduledExecutorService`（`hikari-canvas-anim-ticker`），按每条时间轴的 `cadenceMs = max(1, round(1000.0 / fps))` 调度固定周期任务。不依赖 Bukkit 主线程定时器（Bukkit 最小粒度 1 tick = 50ms = 20fps，无法支撑 20fps 以上的平滑帧率）。
- **单帧推进链路**：每 tick 根据单调时钟 `nanoClock` 计算当前播放进度 `posMs` → 按 `LoopMode` 映射时间坐标 `t`（`ONCE` 到达 `durationMs` 时强制渲染一次终态帧后自动 `stopWall` 注销）→ 调用 `KeyframeInterpolator.interpolate` 基于缓存的基准 `ProjectState` 生成临时插值帧 → 调用 `FrameRenderer.renderFrame` 栅格化并发包。
- **观察者门控（Viewer-Gated）**：`renderFrame` 在栅格化前检查该墙可见范围内的在线玩家列表；无观察者时跳过栅格化与发包（但墙钟播放位置照常推进，玩家回到视野时动画处于准确时刻），避免空墙空转消耗 CPU。
- **异常隔离与并发安全**：单墙单帧异常被捕获并记日志，不中断调度循环；播放控制（`play` / `pause` / `seek` / `stopWall`）通过条目锁与 `invalidationGen` 代次校验消除锁外读库与并发 `invalidate` 之间的过期快照覆盖窗口。
- **暂停与重启语义**：`pause` 在内存中保留当前播放毫秒位置，再次对同一 `timelineId` 调用 `play` 时从暂停处续播；播放位置不持久化落库，服务器重启后 `autoRegisterAll` 对符合自动播放条件的时间轴统一从 `0ms` 起播。

### 3.2 与反应式节流器分流

动态时间轴墙由 `AnimationTicker` 驱动出帧，静态墙由 `ProjectionThrottler` 驱动出帧。当某面墙既有正在播放的时间轴动画、同时又有玩家在编辑器内修改其静态基值时，装配层通过 `AnimationTicker.isWallAnimating(wallId)`（仅非暂停的活跃播放态返回 `true`）执行分流门控：动画播放期间，编辑操作落库后仅调用 `ticker.invalidate(wallId)` 让下一动画帧重载新基值，反应式 `ProjectionThrottler` 退让不重复写地图像素。暂停态（`isWallAnimating == false`）立即放行反应式路径与脚本补间静态渲染路径，并在恢复播放时自动置 `diffResetPending` 重置帧间差分基准。

> `ProjectionThrottler.setIntervalForSession` 仅用于放宽会话级反应式节流间隔，本身不主动产帧。

### 3.3 `BufferedImage` 池化

全画布栅格化按 `TYPE_INT_ARGB`（4 字节/像素）分配画布缓冲区，高频动画下的分配速率如下：

| 画布规格 | 单帧大小 | 20fps 分配速率 | 60fps 分配速率 |
| --- | --- | --- | --- |
| 2×2 文字墙 | 256 KiB | 5.0 MB/s | 15 MB/s |
| 4×2 欢迎墙 | 512 KiB | 10 MB/s | 30 MB/s |
| 8×8 大屏 | 4 MiB | 80 MB/s | 240 MB/s |
| 铺满蒙版图片 | 约 42 MB/帧（含离屏蒙版与模糊缓冲） | 840 MB/s | 2.52 GB/s |

为在不破坏 `CanvasCompositor.rasterize` 无状态并发安全契约的前提下消除大屏 GC 压力，`BufferPool` **严格绑定在 `AnimationTicker` 单一调度线程内使用**（`bindBufferPool`）：Ticker 线程串行借出缓冲区、`clearRect` 清零复用、量化完毕当场归还，不跨线程共享。

### 3.4 帧间脏区比对

动画帧间通过每个播放条目专属的 `FrameDiff` 执行子地图级（per-map sub-rect）像素比对：仅向客户端发送像素实际发生变化的地图块及其最小包围脏矩形，未发生像素变化的地图块零包跳过。

### 3.5 帧率策略

1. **显式配置与硬上限**：`config.yml` 的 `timeline.default-fps`（默认 `20`，对应 50ms）设定新建时间轴的初始帧率；`timeline.max-fps`（默认 `60`）作为服务器级多租户保护硬上限，协议层与调度层将每条时间轴的 `fps` 钳制在 `[1, max-fps]` 范围内。
2. **不自动降级**：系统严格按用户设定的 `fps` 调度，不做隐式掉帧、自动降频或后台成本拦截。服主可通过 `/canvas bench` 独立压测评估服务器承载能力并按需调整 `timeline.max-fps`。

---

## 4. 双端插值与缓动一致性

插值与缓动数学的逐位权威定义位于 `docs/rendering.md §9`，后端 `KeyframeInterpolator` / `EasingSolver` / `ColorLerp` 与前端 `web/src/timeline/{interpolation,easing,colorLerp}.ts` 严格镜像实现。

### 4.1 动态变量取值时机

- **数值类关键帧引用 `${var:X}`**：Ticker 在执行缓动插值**之前**，通过 `WallNumberResolver`（装配 `VariableInterpolator::resolveAsNumber`）读取内存缓存将占位符解析为当前 `double` 数值，再做区间线性缓动插值。
- **文本类关键帧引用 `${var:X}`**：先按离散阶跃（step）选出当前生效的文本关键帧字符串，再在栅格化阶段由 `CanvasCompositor.maybeInterpolateText` 统一解析最新变量值（受 `MAX_INTERPOLATE_DEPTH = 2` 防嵌套递归保护）。

### 4.2 逐类型插值与基值覆盖语义

| 属性类别 | 插值规则 |
| --- | --- |
| 数值类（`x` / `y` / `w` / `h` / `rotation` / `opacity`） | 区间缓动线性插值：$v = a + (b - a) \times \text{ease}(t)$，整数几何属性经 `StrictNumber` 四舍五入并安全钳制 |
| 色彩与填充类（`color` / `fill`） | 纯色及同类型、同 stop 数量的渐变在线性 sRGB 空间逐分量插值；异构填充退化为阶跃切换 |
| 离散类（`text`） | 阶跃取值（step）：取 `timeMs <= 当前时刻` 的最近关键帧，首帧之前取首帧值 |

**按属性粒度的基值覆盖语义**：播放期间，建有关键帧的属性以时间轴插值结果覆盖 `ProjectState` 中的静态元素基值；**未建关键帧的属性**（如未打帧的文本、颜色、几何坐标或新添加的元素）始终读取 `ProjectState` 实时基值，编辑器修改落库后经 `ticker.invalidate` 在下一帧（≤ 1 帧延迟）立即生效。

### 4.3 零外部依赖三次贝塞尔求解

双端各自实现无第三方依赖的 `EasingSolver`：给定控制点 $(x_1, y_1, x_2, y_2)$ 与归一化时间进度 $t \in [0, 1]$，采用固定迭代步数与统一收敛阈值的牛顿迭代（辅以二分法兜底）反解参数 $u$ 后求取 $y(u)$，消除不同第三方缓动库浮点近似差异导致的像素抖动。

### 4.4 线性 sRGB 色彩插值空间

所有颜色与渐变色标插值统一先由 sRGB gamma 解码至线性光空间执行线性混合，再编码回 8-bit sRGB（详见 `docs/rendering.md §9.4`），避免直接在伽马编码空间插值造成的中间过渡色发灰偏暗。

### 4.5 双端一致性验证

1. **跨语言测试向量**：共享测试集 `easing.json`（控制点组合 × 采样点 $t$ → 期望输出，容差 $10^{-6}$），Java 与 TypeScript 单元测试跑同一份基准向量。
2. **多帧渲染快照**：带时间轴的几何测试 fixture 在多个采样时刻（如 `0 / 250 / 500 / 750ms`）分别栅格化并与基准 PNG 逐像素比对。

---

## 5. 触发器机制

### 5.1 职责划分

时间轴内置 `MANUAL`、`VARIABLE_CHANGE`、`SCHEDULE` 三种轻量数据/时间驱动触发器，直接复用变量存储与时刻表基础设施。需要监听 Bukkit 实体/玩家交互事件或编排复杂条件分支的场景，统一交由积木脚本系统的 `TriggerRouter` 处理（见 `docs/scripting.md §2.2`）。

### 5.2 三种触发器行为

- **`MANUAL`**：支持通过编辑器或脚本发送 `timeline.play / pause / seek` 显式控制；同时满足「墙已设置 `activeTimelineId`、对应时间轴 `trigger.type == MANUAL` 且 `loopMode == LOOP`」的墙，在服务器启动恢复、编辑持久化及会话关闭时由 `AnimationTicker` 自动挂起循环播放。
- **`VARIABLE_CHANGE`**：由 `TimelineTriggerRegistry`（`render/TimelineTriggerRegistry.java`）维护 `解析后 fullName -> Set<(wallId, timelineId)>` 的反向内存索引，并挂载 `VariableChangeListener` 监听变量的 `CREATED` / `UPDATED` / `VALUE_SET` 事件：
  - **命名空间注入**：触发器参数中填写的墙内相对变量名（如 `user/score`）在登记索引时必须经过 `VariableInterpolator.resolveFullName` 注入 `wallId`，转化为内部标准键 `user:<wallId>/score`，确保精确命中变更事件。
  - **200ms 去抖保护**：按 `(wallId, timelineId)` 粒度实施 200ms 触发去抖窗口，防止绑定 `eta_seconds` 等每秒高频跳变变量时引发重播抖动。
  - **触发语义**：命中后调用 `AnimationTicker.play(wallId, timelineId)`（对 `ONCE` 时间轴表现为每次变量变化从头播放一轮）。配置为 `VARIABLE_CHANGE` 或 `SCHEDULE` 的时间轴**不会**在墙加载时自动循环起播，仅由事件驱动触发。
- **`SCHEDULE`**：直接复用 `ManualScheduleProvider` / `RailScheduleProvider` 推送的 `schedule:*` 变量更新事件，挂载于同一套 `TimelineTriggerRegistry` 监听链路，不额外交替创建定时线程。

### 5.3 索引生命周期与脚本协同

`TimelineTriggerRegistry` 在服务器启动、编辑操作持久化（`SessionManager.persistWall`）、会话关闭以及删除墙时按 `wallId` 增量重建索引。积木脚本的 `TriggerRouter` 与 `TimelineTriggerRegistry` 并行监听各自注册的规则，互不阻塞；编辑器本地预览始终由用户在时间轴面板手动控制，不受服务端触发器影响。

---

## 6. WebSocket 协议

完整字段 schema 见 `docs/protocol.md §5.12 / §5.13` 与 `§7`。

### 6.1 操作集（`EditOpDispatcher`）

- **时间轴管理**：`timeline.create`、`timeline.update`、`timeline.delete`（持久化修改 `ProjectState.timelines` 与 `activeTimelineId`）。
- **关键帧编辑**：`keyframe.add`、`keyframe.update`、`keyframe.delete`、`keyframe.move`（高频细粒度编辑操作，支持可选 `coalesceKey`）。
- **服务端播放控制**：`timeline.play`、`timeline.pause`、`timeline.seek`（直接控制部署墙上的后端 `AnimationTicker`，不修改 `ProjectState`、不入撤销栈）。

### 6.2 `state.patch` 增量同步路径

时间轴与关键帧变更通过标准 JSON Pointer 路径下发增量补丁：`/timelines/<i>/...` 与 `/timelines/<i>/tracks/<elementId>/<k>/...`，前端 `stores/project.ts` 补丁应用器按路径前缀原位更新响应式状态树。

### 6.3 编辑器本地预览与后端权威

用户在时间轴面板拖动播放头（scrubber）或在编辑器内点击本地预览播放时，**全程在浏览器本地以 60fps 计算插值并重绘预览画布，不发送 WebSocket 消息**；`timeline.play / pause / seek` 仅用于操控游戏内已部署墙体的服务端 `AnimationTicker` 输出。游戏内地图画面始终以服务端 `AnimationTicker` 渲染结果唯一权威。

---

## 7. 撤销与历史合并

### 7.1 快照栈特征

`HistoryStack` 采用全状态 `ProjectSnapshot` 快照模型。由于关键帧调整（拖动时间位置、连续调节数值滑块）及「整体帧」批量写属性会产生密集操作流，若逐条独立压栈会迅速冲刷掉先前的画布编辑历史。

### 7.2 合并策略与容量粘性提升

1. **单帧连续拖动合并**：`HistoryStack.commitHistoryCoalesced` 默认以 `{elementId}:{keyframeId}:{property}` 作为合并键，在 **500ms 时间窗口**内命中相同合并键的连续修改直接更新栈顶快照并清空 redo 栈，不新增历史步。
2. **整体帧批量操作合并**：当一次用户交互同时写入同一元素的多个几何变换关键帧（如「拉就设」或整组关键帧平移，对应多条 `keyframe.*` op）时，前端为同批请求附带相同的显式 `coalesceKey`（如 `integ:{elementId}:{timeMs}`）。后端优先按客户端传入的 `coalesceKey` 合并，使一次 `Ctrl+Z` 即可原子撤回整组 6 个变换属性关键帧；未传 `coalesceKey` 时自动回退至单帧默认键。
3. **历史栈容量粘性提升（`16 → 64`）**：静态工程默认 `MAX_HISTORY = 16`；一旦会话内观测到激活的时间轴（`activeTimelineId != null`），历史栈上限立即提升至 **64 步**，且在当前会话生命周期内**保持粘性不回落**——防止删除最后一条时间轴的那一次提交将历史容量骤降回 16 而当场截断最多 48 步历史记录。
4. **快照克隆隔离**：由于关键帧位于 `Timeline.tracks` 而非 `Element` 内部，普通元素编辑生成快照克隆图层元素列表时无需深拷贝关键帧集合，撤销开销不随关键帧规模膨胀。

---

## 8. 前端编辑器

### 8.1 面板组成与代码结构

时间轴编辑器位于画布底部可折叠停靠栏（高 200–320px，展开/收起时联动 `CanvasView.fitToViewport` 自适应视口）：

- **组件实现（`web/src/components/timeline/`）**：
  - `TimelineDock.vue`：左侧元素折叠轨道与每属性子轨列表、顶部播放控制栏（播放/暂停、循环模式、帧率、时间轴切换与当前时刻）、中部时间标尺网格与关键帧菱形节点及播放头 scrubber。
  - `EasingCurveEditor.vue`：纯 SVG 实现的可拖拽双控制点三次贝塞尔曲线编辑器及标准预设快捷切换。
  - `timelineLogic.ts`：关键帧分组、区间查找、整体帧规划等纯函数逻辑。
- **插值核心（`web/src/timeline/`）**：`interpolation.ts`、`easing.ts`、`colorLerp.ts`，与后端逐算法镜像。
- **按需分包**：时间轴面板拆分为独立懒加载构建 chunk，控制首屏主包体积。

### 8.2 本地预览旁路渲染

`useTimelinePlayback` 维护一份本地插值后的瞬时 `ProjectState` 局部引用，在本地播放或拖动播放头期间直接将其传入纯函数 `renderProjectState` 绘制底层预览画布，**绕开 `project.state` 的深度响应式侦听器（deep watch）**，确保多元素高帧率预览顺滑跟手且不污染真实编辑状态；关键帧拖拽同样在指针移动期间本地即时更新视觉位置、`pointerup` 时发送单条终值 op。

### 8.3 编辑器交互协同

轨道列表的选中态与主画布 `ui.selectedIds` 双向同步；当播放头停在某时刻并在主画布直接拖拽或缩放已启用时间轴的元素时，自动按当前 `timeMs` 规划关键帧插入或覆盖并附带统一 `coalesceKey`。

---

## 9. 关键边界与保障措施

| 工程边界 | 保障机制 |
| --- | --- |
| 大画布高帧率下的 `BufferedImage` 分配压力 | Ticker 单线程专属 `BufferPool` 原地清零复用缓冲区（§3.3），不破坏 `rasterize` 纯函数并发契约 |
| 高频关键帧微调冲刷撤销历史 | 500ms 窗口 `coalesceKey` 合并 + 整体帧共享键 + 会话粘性 64 步历史容量（§7.2） |
| 双端三次贝塞尔与色彩插值像素偏差 | 零第三方依赖固定步数牛顿求解器 + 线性 sRGB 混合 + 跨端 `easing.json` 向量与多帧快照测试（§4.3–§4.5） |
| 高于 20fps 的帧率调度精度 | 独立守护 `ScheduledExecutorService` 毫秒级定频调度，解耦 Bukkit 50ms 主线程 tick（§3.1） |
| 多观察者高帧率地图包带宽开销 | 观察者为空停渲（viewer-gated）+ per-map 子矩形 `FrameDiff` 仅推送脏区（§3.1 / §3.4） |
| `AnimationTicker` 与实时编辑并发读写状态 | `EditSession` 同步锁保护 + `PlaybackEntry` 锁与 `invalidationGen` 代次重读校验 + `isWallAnimating` 分流门控（§3.1 / §3.2） |
| 编辑器本地 60fps 预览触发 Vue 深度侦听抖动 | 瞬时插值快照直接驱动 `renderProjectState`，绕开 `project.state` deep watch（§8.2） |
