# 视觉脚本与补间动画运行时规范

**定位**：本文档定义 HikariCanvas 视觉积木脚本系统与补间动画引擎（`TweenScheduler`）的完整契约，涵盖整体架构与时间轴共存机制、9 种触发器与 29 种动作/控制流积木的数据模型、运行预算与 SQLite 持久化、服务端执行管线、WebSocket 协议与试跑轨迹、命令模板白名单与分级权限、独立补间动画运行时，以及前端积木画布与 `PreviewPane` 幽灵拖动几何规范。

**关联契约**：`docs/protocol.md §5.14`（脚本 WebSocket op 与协议版本）· `docs/data-model.md §2.10 / §4.6`（`wall_scripts` 表与 `.canvas` `scripts.json`）· `docs/architecture.md §17`（运行时组件与三线程模型）· `docs/security.md §13`（脚本运行时威胁模型）· `docs/rendering.md §9`（缓动与线性 sRGB 插值数学权威）· `docs/template-pack.md §4.2`（共用条件表达式文法）· `docs/scripting-guide.md`（面向玩家的使用指南）。

---

## 1. 架构定位与时间轴共存

1. **服务端唯一执行权威**：所有脚本触发监听、条件求值、变量读写、元素修改、时间轴控制、补间推进与命令派发**仅在服务端运行**（`GameEventListenerHub` + `TriggerRouter` + `ScriptRunner` + `ActionExecutor` + `TweenScheduler`）。前端积木画布（`web/src/script/`）纯属可视化编排与草稿编辑界面，不在浏览器本地模拟执行脚本逻辑；编辑器「试跑」通过 WebSocket 触发服务端真实执行一次，并将执行轨迹异步回推前端逐积木高亮。
2. **与关键帧时间轴同画布正交共存**：关键帧时间轴（`docs/timeline.md`）负责确定性的预制循环或关键帧序列动画；积木脚本作为上层事件响应与逻辑编排层，既可通过 `playTimeline` / `playTimelineAwait` 直接启停、跳转本墙时间轴，也可通过 `tweenBlock` 在任意事件条件下平滑过渡元素基准属性，并由 `AnimationTicker.isWallAnimating(wallId)` 在补间引擎与时间轴引擎之间自动分流协作。
3. **单墙隔离边界**：每条脚本规则严格归属单面墙（`wallId`），存储在独立的 `wall_scripts` 表中，不进入 `ProjectState`（避免画布高频补丁与快照膨胀），也不跨墙直接篡改其他墙的元素或私有变量；跨墙状态共享统一通过 `userglobal/*` 全局用户变量或插件命名空间变量完成。

---

## 2. 数据模型与积木全集

### 2.1 `ScriptRule`

每条规则由一顶触发器「帽子」与一棵顺序/嵌套动作树组成：

```java
record ScriptRule(
    String id,            // "sr-<8hex>"，服务端创建时分配
    String wallId,        // 归属墙 ID
    boolean enabled,      // 启用开关
    String name,          // 规则名称（非空白，长度 ≤ 64）
    Trigger trigger,      // 触发器帽子（每条规则恰好 1 个）
    List<Action> actions, // 顶层动作序列（非空；含 If / Repeat / RepeatUntil / TweenBlock / RandomBranch 嵌套）
    String blockLayout    // 前端积木画布摆放坐标 JSON（长度 ≤ 65536；服务端原样存取、执行器不解析）
) {}
```

- **规模约束（`ScriptRuleValidator`）**：单墙最多挂载 `scripts.max-rules-per-wall` 条规则（默认 `16`）；单条规则的积木树静态节点总数上限 `MAX_TOTAL_BLOCKS = 50`（容器积木自身计 1 并递归累加子分支节点数，不乘循环轮数；运行期动态展开步数由 §2.4 Budget 管控）。
- **序列化形态**：`Trigger` 与 `Action` 均采用 `type` camelCase 判别字段 + 扁平业务字段的双向自定义 Jackson 序列化（`TriggerSerializer` / `TriggerDeserializer`、`ActionSerializer` / `ActionDeserializer`），与 `ProjectState` 脱钩，因此 `ProjectState.PROTOCOL_VERSION` 保持 `3` 不变。

### 2.2 `sealed Trigger`（9 种触发器）

`Trigger` 共有 9 个受限实现子类，按事件源与权限面划分如下：

| 子类（Java） | Wire `type` | 参数与校验范围 | 触发源与运行语义 | 附加权限面 |
| --- | --- | --- | --- | --- |
| `VariableChange` | `variableChange` | `fullName`（非空白） | 监听 `VariableStore` 变化；墙内相对名经 `VariableInterpolator.resolveFullName` 注入 `wallId` 匹配 | 无（仅 `edit`） |
| `Timer` | `timer` | `intervalSeconds` ∈ `[1, 86400]` | 共享守护定时线程池按固定秒数触发；**与墙是否部署或有无观察者无关**，启用即按周期运行 | 无（仅 `edit`） |
| `PlayerJoin` | `playerJoin` | — | Bukkit `PlayerJoinEvent`（`MONITOR`） | `trigger.global` |
| `PlayerKill` | `playerKill` | — | Bukkit `PlayerDeathEvent`（`MONITOR`，仅当 `killer != null` 时触发） | `trigger.global` |
| `PlayerNear` | `playerNear` | `rangeBlocks` ∈ `[1, 32]` | 主线程 `PlayerNearSampler` 周期采样（世界分桶 + 距离平方预筛）；**进入半径边沿触发一次**，持续在圈内不重触发，离开后重置 | 无（仅 `edit`） |
| `PlayerLeaveRange` | `playerLeaveRange` | `rangeBlocks` ∈ `[1, 32]` | 复用 `PlayerNearSampler` 状态跟踪，玩家从半径内跨出半径外（**离开边沿**）触发一次 | 无（仅 `edit`） |
| `WallReady` | `wallReady` | — | 墙投影首次部署完成或服务器启动恢复完成时触发 | 无（仅 `edit`） |
| `RightClickWall` | `rightClickWall` | — | Bukkit `PlayerInteractEntityEvent` 命中归属本墙的受管 `ItemFrame` 时触发 | `trigger.global` |
| `PlayerQuit` | `playerQuit` | — | Bukkit `PlayerQuitEvent` | `trigger.global` |

- **`PlayerNearSampler` 采样机制**：底层固定以 2 tick 周期在主线程运行轻量跳帧计数器，累计达到 `scripts.player-near-sample-ticks`（默认 `10` tick = 0.5s，配置钳制 `1..200`，支持 `/canvas reload` 热更新）时执行一次世界分桶与距离平方比较，命中后立即异步投递 `TriggerRouter`，主线程开销保持微秒级。
- **「触发玩家」（`TriggerContext.triggerPlayer`）绑定规则**：供 `sendMessage` 与 `showTitle` 在 `target = "trigger"` 时定位接收方：
  - `PlayerJoin` / `PlayerQuit` / `PlayerNear` / `PlayerLeaveRange` / `RightClickWall`：事件当事玩家；
  - `PlayerKill`：**击杀者（killer）**；
  - `VariableChange` / `Timer` / `WallReady` 及编辑器 `script.test` 试跑：无触发玩家（`triggerPlayer == null`），此时 `target = "trigger"` 的通知动作直接跳过并在轨迹中记为 `skipped`。触发玩家身份由结构化字段独立传递，严禁从日志向的 `detail` 自由文本反解析。

### 2.3 `sealed Action`（29 种动作与控制流）

`Action` 共有 29 个受限子类（7 种控制流 + 7 种变量操作 + 6 种元素操作 + 3 种时间轴/补间编排 + 6 种玩家/世界反馈与命令/日志），完整目录如下：

#### A. 流程控制与延时（由 `ScriptRunner` 帧栈直接调度）

| 子类（Java） | Wire `type` | 字段与校验约束 | 执行语义 |
| --- | --- | --- | --- |
| `If` | `if` | `condition`（非空白，≤ 512 字符）、`then: Action[]`、`else: Action[]`（Java 字段名 `elseActions`）；与 `RandomBranch` 合计嵌套深度 ≤ `4` | 异步读取变量缓存求值 `condition`，为真压入 `then` 分支，否则压入 `else` 分支 |
| `RandomBranch` | `randomBranch` | `probability` ∈ `[0, 100]`、`then: Action[]`、`else: Action[]`；计入最大分支深度 `4` | 按注入随机数源判定 `rng.nextInt(100) < probability`，命中走 `then` 否则走 `else`；`blockId` 路径同 `if`（`/then/<j>`、`/else/<j>`） |
| `Repeat` | `repeat` | `count` ∈ `[1, 100]`、`body: Action[]`（非空） | 将 `body` 按 `count` 轮压栈执行；每轮内子动作照常计入单次运行 `max-actions-per-run` 预算；子动作 `blockId` 固定为 `<blockId>/body/<j>` |
| `RepeatUntil` | `repeatUntil` | `condition`（≤ 512 字符）、`maxIterations` ∈ `[1, 100]`（前端默认 `10`）、`body: Action[]`（非空） | 动态 `while` 语义（先判条件后跑循环体）：条件满足即跳出，否则执行一轮 `body` 并在 `RunState` 按 `blockId` 递增轮数；受 `maxIterations` 与 `max-actions-per-run` 双闸保护 |
| `Wait` | `wait` | `ms` ∈ `[50, 5000]`；嵌套延时深度 ≤ `3` | 将后续帧通过定时器异步重投递回 `ScriptRunner` 队列，不阻塞工作线程 |
| `WaitUntil` | `waitUntil` | `condition`（≤ 512 字符）、`timeoutMs` ∈ `[50, 60000]` | 首次进入计 1 步动作预算并启动独立 `pollWaitUntil` 周期轮询（显式携带截止时间戳），条件满足或超时后投递续接帧，轮询期间不重复累加动作步数 |
| `StopScript` | `stopScript` | 无字段 | 立即清空当前运行实例的帧栈并以 `ok` 正常收尾，后续未执行积木不再运行 |

- **条件表达式求值（`ConditionEvaluator`）**：`If`、`WaitUntil`、`RepeatUntil` 共用 `docs/template-pack.md §4.2` 定义的 `ExpressionParser` / `ExpressionEvaluator` 文法与 `StrictNumber` 数值规则（支持 `var("...")` 读取变量缓存、算术 `+ - * /`、比较 `> >= < <= == !=`、逻辑 `&& || !`，双侧数值形态走数值等值、否则走布尔/字符串等值），并在规则保存与导入期强制执行 `checkSyntax` 语法预检。

#### B. 变量运算（异步线程读写 `VariableStore`，均仅需 `canvas.script.edit`）

| 子类（Java） | Wire `type` | 字段与校验约束 | 执行语义 |
| --- | --- | --- | --- |
| `SetVariable` | `setVariable` | `fullName`（非空白）、`value`（非 `null`，≤ 4096 字符） | 对 `value` 执行 `${var:...}` 插值后写入目标变量 |
| `IncrementVariable` | `incrementVariable` | `fullName`、`delta`（有限 `double`） | 按 `double` 解析当前值（非数值或未设置按 `0` 起算），加上 `delta` 后写回 |
| `ScaleVariable` | `scaleVariable` | `fullName`、`op` ∈ `{multiply, divide}`、`factor`（有限 `double`；`divide` 时禁止 `0.0`） | 读取当前数值执行乘或除运算后写回；当前值非数值时记 `error` 步 |
| `RoundVariable` | `roundVariable` | `fullName`、`mode` ∈ `{round, floor, ceil}` | 读取当前数值按四舍五入、向下取整或向上取整后写回整数串；当前值非数值时记 `error` 步 |
| `SetRandomVariable` | `setRandomVariable` | `fullName`、`min`、`max`（有限 `double` 且 `min <= max`） | 在 `[min, max]` 闭区间均匀随机采样（若两端均为整数则输出整数串）并写入变量 |
| `CopyVariable` | `copyVariable` | `target`、`source`（均非空白） | 读取 `source` 当前缓存值写入 `target`；`source` 不存在时记 `error` 步 |
| `AppendVariable` | `appendVariable` | `fullName`、`text`（≤ 4096 字符） | 将经过 `${var:...}` 插值的 `text` 追加到目标变量当前字符串末尾 |

#### C. 画布元素操作（经 `ElementPropertyApplier` 双路径落地，均仅需 `canvas.script.edit`）

| 子类（Java） | Wire `type` | 字段与校验约束 | 执行语义 |
| --- | --- | --- | --- |
| `SetElementProperty` | `setElementProperty` | `elementId`、`property` ∈ `{x, y, w, h, rotation, opacity, text, fill, color}`、`value`（非空白） | 万能单属性修改积木：`fill` 与 `color` 接受 `#RRGGBB[AA]`，数值属性经 `StrictNumber` 取整钳制 |
| `SetElementProperties` | `setElementProperties` | `elementId`、`patch: Map<String,String>`（1~8 键，键属上述 9 属性白名单，`text` 允许空串）、`kind`（≤ 32 字符） | 8 种可视化友好元素积木（`moveTo` 移到坐标、`resize` 改尺寸、`rotateTo` 旋转、`setOpacity` 设透明度、`show` 显示、`hide` 隐藏、`setText` 改文字、`setColor` 改颜色）的统一序列化目标；`kind` 供前端还原友好积木皮肤与幽灵拖动模式，后端合并 `patch` 一次原子应用，保持 1 积木 = 1 动作的 `blockId` 同构 |
| `NudgeElement` | `nudgeElement` | `elementId`、`dx`、`dy`（有限 `double`） | 相对移动：原子读取元素当前 `(x, y)` 并加上四舍五入后的 `(dx, dy)` 增量（`long` 防溢出后钳制回 `int`） |
| `CloneElement` | `cloneElement` | `elementId`、`offsetX`、`offsetY` ∈ `[-4096, 4096]` | 在所属同一图层克隆目标元素（分配新 `el-<8hex>` ID 并叠加偏移），受 `scripts.max-elements-per-wall`（默认 `200`）总数配额硬闸约束，超限返回 `error` 步 |
| `DeleteElement` | `deleteElement` | `elementId` | 从所属图层删除指定元素；元素不存在返回 `error` 步 |
| `SetElementLayer` | `setElementLayer` | `elementId`、`mode` ∈ `{front, back}` | 调整元素在所属图层内的渲染次序：`front` 移至列表末尾（最顶层显示），`back` 移至列表开头（最底层显示） |

#### D. 时间轴与补间动画编排（均仅需 `canvas.script.edit`）

| 子类（Java） | Wire `type` | 字段与校验约束 | 执行语义 |
| --- | --- | --- | --- |
| `PlayTimeline` | `playTimeline` | `timelineId`、`op` ∈ `{play, pause, seek}`、`seekMs`（仅 `seek` 时必填且 ≥ 0） | 调用 `AnimationTicker` 控制目标时间轴播放、暂停或跳转，立即继续后续积木 |
| `PlayTimelineAwait` | `playTimelineAwait` | `timelineId` | 启动目标时间轴播放，并将脚本挂起一轮 `durationMs` 时长后再续接后续动作 |
| `TweenBlock` | `tweenBlock` | `durationMs` ∈ `[1, 60000]`、`easing: Easing`、`body: Action[]`（非空，仅允许 `SetElementProperties` 且 `kind` ∈ `{moveTo, resize, rotateTo, setOpacity, setColor}`） | C 形包裹积木：在 `durationMs` 内按 `easing` 曲线并行平滑过渡 `body` 内所有目标属性，挂起脚本直至末帧落盘完成（详见 §7） |

#### E. 玩家通知、环境效果、白名单命令与日志

| 子类（Java） | Wire `type` | 字段与校验约束 | 线程与附加权限面 | 执行语义 |
| --- | --- | --- | --- | --- |
| `SendMessage` | `sendMessage` | `text`（非 `null`，≤ 256 字符）、`channel` ∈ `{chat, actionbar, title}`、`target` ∈ `{trigger, all}`（缺省反序列化兼容为 `"trigger"`） | 主线程 hop；`target = "all"` 需 `broadcast` | `${var:...}` 插值后发送给触发玩家（`trigger`）或全服在线玩家（`all`，即「全服广播」） |
| `ShowTitle` | `showTitle` | `title` / `subtitle`（单字段 ≤ 256 字符，两者至少一个非空白）、`fadeInMs` / `fadeOutMs` ∈ `[0, 10000]`、`stayMs` ∈ `[0, 60000]`、`target` ∈ `{trigger, all}` | 主线程 hop；`target = "all"` 需 `broadcast` | `${var:...}` 插值后将毫秒换算为 tick（`ms / 50`）调用 `player.sendTitle` 发送屏幕大标题弹窗 |
| `PlaySound` | `playSound` | `soundId`（≤ 128 字符）、`volume` ∈ `[0.0, 2.0]`、`pitch` ∈ `[0.5, 2.0]`、`scope` ∈ `{near, all}` | 主线程 hop；需 `sound` | 在墙体周围 16 格（`near`）或全服在线玩家（`all`）播放注册音效 |
| `PlayParticle` | `playParticle` | `particle`（14 项内置白名单，如 `minecraft:flame`、`minecraft:heart`、`minecraft:totem_of_undying` 等）、`count` ∈ `[1, 1000]`、有限偏移 `offsetX/Y/Z` | 主线程 hop；复用 `sound` | 在墙体原点叠加偏移后的世界坐标生成指定粒子效果 |
| `RunCommand` | `runCommand` | `templateId`（非空白）、`params: Map<String,String>`（单值 ≤ 256 字符） | 主线程 hop；需 **`command`（默认 `op`）** | 渲染服主配置的命令白名单模板并以控制台身份派发执行（§5.2） |
| `Log` | `log` | `message`（非 `null`，≤ 256 字符） | 异步；仅 `edit` | `${var:...}` 插值后写入插件日志供调试（不写 `audit_log` 表以防高频刷库） |

### 2.4 运行预算与配额配置（`Budget`）

`config.yml` 的 `scripts` 段集中控制规则配额、执行熔断预算、采样周期、补间上限与命令模板（均支持 `/canvas reload` 热更新）：

```yaml
scripts:
  max-rules-per-wall: 16           # 单墙脚本规则数上限
  max-elements-per-wall: 200       # 单墙元素总数上限（约束 cloneElement 无限克隆）
  player-near-sample-ticks: 10     # playerNear / playerLeaveRange 采样周期（tick，钳制 1..200）
  budget:
    max-actions-per-run: 50        # 单次触发展开执行的动作步数硬限（含分支与循环展开）
    max-runs-per-second: 10        # 单条规则每秒最大触发次数（超限丢弃并记 SCRIPT_RUN_BLOCKED）
    max-chain-depth: 8             # 变量写触发下游脚本的最大级联深度（防循环互触发死锁）
    max-delay-depth: 3             # wait 嵌套层数上限
    max-delay-ms: 5000             # 单次 wait 最大毫秒数
  tween:
    max-fps: 60                    # 补间调度器 cadence 上限兼单墙 tweenFps 钳制上界
    max-concurrent: 16             # 全服同时活跃补间墙数上限
  command-templates: {}            # 服主手写命令白名单模板（默认空 = runCommand 积木灰显禁用）
```

### 2.5 持久化与导入导出（`wall_scripts`）

脚本规则存储在 SQLite `wall_scripts` 表中：

```sql
CREATE TABLE wall_scripts (
  id         TEXT PRIMARY KEY,
  wall_id    TEXT NOT NULL REFERENCES walls(wall_id) ON DELETE CASCADE,
  enabled    INTEGER NOT NULL DEFAULT 1,
  name       TEXT NOT NULL,
  rule_json  TEXT NOT NULL,     -- ScriptRule 完整 JSON（trigger / actions / blockLayout）
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_wall_scripts_wall ON wall_scripts(wall_id);
```

- **坏数据隔离**：启动或加载单条损坏的 `rule_json` 时跳过该规则并输出 `SEVERE` 日志，不影响同墙其余规则与墙体渲染。
- **`.canvas` 随包迁移**：导出工程或模板包时将本墙规则写入 `scripts.json`；导入或套用模板时由 `ScriptImporter` 将每条规则的 `wallId` 重绑为当前目标墙，全量重跑 `ScriptRuleValidator` 与 `ConditionEvaluator.checkSyntax`，并对目标服务器未配置的 `runCommand.templateId` 标记缺失（前端红色徽章灰显停用该积木，规则其余积木照常可用）。

---

## 3. 执行管线与线程契约

### 3.1 核心组件与分发链路

```
Bukkit 事件（GameEventListenerHub） / 变量变更（VariableStore） / 定时器 / PlayerNearSampler
        ↓
TriggerRouter —— (triggerType → wallId → ruleId) 双层索引，O(1) 路由投递
        ↓
ScriptRunner —— 独立单线程异步执行队列
        │  ① 校验速率闸（max-runs-per-second）与级联链深（max-chain-depth）
        │  ② 帧栈驱动控制流（If / RandomBranch / Repeat / RepeatUntil / StopScript）
        │  ③ 挂起式动作（Wait / WaitUntil / PlayTimelineAwait / TweenBlock）异步回调重入队列
        ↓
ActionExecutor & ElementPropertyApplier —— 按动作类型分发执行与三层异常隔离
```

- 单个动作执行失败（如元素不存在、除零、非数值取整、配额超限）仅将该步标记为 `error` 并记录告警日志，后续积木继续执行不断链。

### 3.2 双路径元素修改与重绘同步（`ElementPropertyApplier`）

所有修改画布元素状态的动作（`SetElementProperty`、`SetElementProperties`、`NudgeElement`、`CloneElement`、`DeleteElement`、`SetElementLayer`）统一经由 `ElementPropertyApplier` 自动分流两条路径（均不读取墙级 `lockedAt` 元数据，与后端编辑操作透明纪律一致）：

1. **活跃编辑器会话路径**：若目标墙当前存在打开的编辑会话（`SessionPatchApplier` 命中），直接在会话锁内调用 `EditSession` 对应标准方法，走完整的历史入栈、`state.patch` 广播、`ProjectionThrottler` 投影与 `persistWall` 落库链路，编辑器画面与游戏内地图同步更新。
2. **无会话后台路径（Headless）**：无活跃编辑器会话时，从 `WallRepo` 加载最新状态构造临时 `EditSession` 执行同一套校验与不可变重建，写回 `wallRepo.updateState`（每动作直接落库，受 Budget 封顶），随后按墙体动画状态走三路重绘同步（`repaintAfterHeadlessWrite`）：
   - 时间轴正在播放（`ticker.isWallAnimating`）→ `ticker.invalidate(wallId)`，下一帧自动读取新基值；
   - 未在播放但配了 `activeTimelineId` → `ticker.refreshAutoPlay(wallId)`；
   - 纯静态墙 → 投递 `ticker.renderStatic(wallId, state)` 立即重绘游戏内地图，紧接投递 `ticker.clearStaticDiff(wallId)`（单线程 FIFO 保证渲染完毕后释放差分缓冲）。
   > **并发边界说明**：后台写入与玩家打开/关闭编辑会话的瞬间若恰好交错，单次脚本属性写入可能被会话快照持久化覆盖；该窗口仅影响瞬时单次更新且不会造成状态损坏。

### 3.3 线程纪律与链深熔断

1. **主线程隔离**：条件求值、变量读写与无会话 `EditSession` 重建全部在 `ScriptRunner` 异步线程完成；仅声音、粒子、聊天/标题发送及控制台命令派发通过主线程调度器 hop 到 Bukkit 主线程执行。
2. **级联链深熔断**：脚本通过变量动作写入 `VariableStore` 时携带当前 `(sourceRuleId, chainDepth)`；若该变量更新触发下游脚本规则，链深递增为 `chainDepth + 1`。一旦达到 `scripts.budget.max-chain-depth`（默认 `8`），立即中止本次下游触发、写入审计事件 `SCRIPT_RUN_BLOCKED(reason=chain)` 并输出 `WARN` 链路日志，但**不自动禁用用户的脚本规则**。

---

## 4. WebSocket 协议与试跑轨迹

当前 WebSocket 业务协议版本为 `Protocol.SUPPORTED_MIN / MAX = 8`（版本不匹配以 `4002` 关闭连接）。完整消息信封见 `docs/protocol.md §5.14`。

### 4.1 操作集与保存期校验

| 操作名（`op`） | 载荷（`payload`） | 鉴权要求 | 说明 |
| --- | --- | --- | --- |
| `script.create` | `{ rule }` | `canvas.script.edit` + 规则所需附加权限面 | 校验结构与条件语法，分配 `sr-<8hex>` ID 落库并推送补丁 |
| `script.update` | `{ ruleId, rule }` | 同上 | 整条规则全量替换保存 |
| `script.delete` | `{ ruleId }` | `canvas.script.edit` | 删除规则并注销触发路由 |
| `script.enable` | `{ ruleId, enabled }` | `canvas.script.edit`（`enabled = true` 时重验附加权限面） | 切换启用态；开启方向重新扫描库内规则所需权限面，防止无权限者重新激活高危规则；关闭方向始终放行 |
| `script.test` | `{ ruleId }` | `canvas.script.edit` + 规则所需附加权限面 | 异步触发一次真实执行（计入 Budget 并记 `SCRIPT_TEST` 审计），ack 立即返回 `{ accepted: true, ruleId }` |
| `canvas.tweenFps` | `{ fps }` | `canvas.edit` | 设置单墙补间目标帧率（`[1, 60]`；传 `0` 或 `null` 恢复默认值 `30`），存入 `ProjectState.tweenFps` |

- **保存期快照鉴权**：附加权限面在 `script.create`、`script.update`、`script.enable(true)` 及 `script.test` 时由 `ScriptPermissions.requiredFacets` 递归扫描校验；正常游戏事件触发执行期不再重复查创建者实时在线权限（规则代表保存时的授权快照，服主可随时禁用或删除）。
- **条件语法预检**：保存时对树中所有 `if`、`waitUntil`、`repeatUntil` 的 `condition` 执行 `ConditionEvaluator.checkSyntax`，语法错误直接拒绝返回 `SCRIPT_INVALID` 并附带定位路径。

### 4.2 会话同步与异步轨迹（`script.trace`）

- **初始加载与增量同步**：会话 `ready` 载荷携带本墙全部 `scripts: ScriptRuleDto[]`；增删改启停通过 `state.patch` 的 `/scripts/<ruleId>` 路径推送至当前墙会话。
- **异步轨迹回推（`script.trace`）**：由于合法规则可包含多段 `wait`、`waitUntil`、`playTimelineAwait` 或长达数十秒的 `tweenBlock`，`script.test` 不在 WS ack 中同步阻塞等待运行结束，而是在整条运行链最终收尾（含延时/补间续接完成或被 Budget 拦截）后由服务端主动下发 `script.trace` 消息：

```json
{
  "ruleId": "sr-1a2b3c4d",
  "steps": [
    { "blockId": "trigger", "kind": "trigger", "result": "ok" },
    { "blockId": "actions/0", "kind": "condition", "result": "ok", "detail": "true" },
    { "blockId": "actions/0/then/0", "kind": "action", "result": "ok" }
  ]
}
```

`blockId` 为后端执行期按动作树位置即时生成的确定性路径（如 `trigger`、`actions/0`、`actions/1/then/0`、`actions/2/body/0`），与前端内存积木树逐字符同构，无需在持久化 JSON 中为每个动作节点存储冗余 ID。

### 4.3 撤销语义

脚本规则独立于 `ProjectState`，因此 `script.*` 持久化操作不进入画布 `HistoryStack`。积木编辑器在 `stores/scriptEdit.ts` 内部维护工作副本的本地草稿撤销/重做栈；删除规则在前端采用行内二次确认气泡保护。

---

## 5. 安全模型与命令白名单模板

### 5.1 威胁防御矩阵

| 风险面 | 防御机制 |
| --- | --- |
| 任意命令注入提权 | 严禁自由命令字符串；`runCommand` 仅能按 `templateId` 引用服主在 `config.yml` 手写的白名单模板并经参数净化（§5.2），插件默认不内置任何命令模板 |
| 高频触发或死循环耗尽资源 | 单规则 `max-runs-per-second` 限频 + 单次运行 `max-actions-per-run` 步数硬闸 + `Repeat`/`RepeatUntil` 轮数上限 + `ScriptRunner` 单线程串行背压 |
| 跨规则变量死锁环 | 变量写入携带级联深度，达 `max-chain-depth` 立即熔断并记审计日志（§3.3） |
| 音效、粒子与全服广播骚扰 | 音量/音高/粒子数与粒子类型白名单硬限，且独立划分 `canvas.script.sound` 与 `canvas.script.broadcast` 权限节点，服主可按需整服收回 |
| 跨墙或伪造系统变量写入 | 变量写入前强制目标命名空间校验：仅允许写本墙 `user:` 变量、调用方拥有的 `userglobal/` 变量或插件开放命名空间；`system` / `papi` / `scoreboard` / `schedule` 只读命名空间一律拒绝（详见 `docs/security.md §13.8`） |

### 5.2 命令模板引擎（`CommandTemplateEngine`）

```yaml
scripts:
  command-templates:
    announce:
      command: "say [招牌] {msg}"
      params:
        msg: { max-length: 64 }
    give-reward:
      command: "give {player} diamond 1"
      params:
        player: { type: online-player }
```

- **参数净化规则**：渲染 `{param}` 前自动剥除换行符（`\r` / `\n`）与 `§` 颜色控制符；单参数长度不超过模板声明的 `max-length`（默认 `64`）；`type: text`（默认类型）剥除控制符后若仍含 `@` 字符则整条命令拒绝执行，彻底阻断 `@a` / `@e` 选择器注入；`type: online-player` 要求参数值与当前在线玩家名**大小写敏感精确匹配**。
- **派发与脱敏暴露**：最终命令串剥除前导 `/` 后以控制台身份调用 `Bukkit.dispatchCommand`；前端下拉列表通过 `GET /api/script/command-templates` 获取可用模板，接口仅返回 `templateId` 与参数名列表，不向客户端暴露原始命令字符串。

### 5.3 审计事件

脚本生命周期与敏感动作记录以下 7 类审计事件（写入 `audit_log` 表）：
`SCRIPT_CREATE`、`SCRIPT_UPDATE`、`SCRIPT_DELETE`、`SCRIPT_ENABLE`、`SCRIPT_RUN_BLOCKED`、`SCRIPT_COMMAND_EXECUTED`（含 `templateId`、渲染后完整命令与来源规则 ID）、`SCRIPT_TEST`。

---

## 6. 权限体系（`canvas.script.*`）

权限采用「基础编辑默认开放 + 影响范围与高危能力分级独立节点」模型（定义于 `ScriptPermissions.java` 与 `paper-plugin.yml`）：

| 权限节点 | 默认值 | 覆盖范围 |
| --- | --- | --- |
| `canvas.script.edit` | `true` | 基础脚本创建、编辑、删除、启停与试跑（涵盖墙级触发器、控制流、变量运算、画布元素操作、时间轴与补间动作、仅发给触发玩家的通知及调试日志） |
| `canvas.script.trigger.global` | `true` | 使用全服或任意玩家交互触发器：`playerJoin`、`playerKill`、`playerQuit`、`rightClickWall` |
| `canvas.script.sound` | `true` | 使用环境音视动作：`playSound`、`playParticle` |
| `canvas.script.broadcast` | `true` | 在 `sendMessage` 或 `showTitle` 中选择 `target = "all"` 向全服在线玩家广播 |
| `canvas.script.command` | `op` | 使用 `runCommand` 执行服主白名单命令模板 |

`ScriptPermissions.requiredFacets` 使用无 `default` 分支的穷尽式 `switch` 递归遍历整棵动作树（含 `If`、`RandomBranch`、`Repeat`、`RepeatUntil`、`TweenBlock` 内部子动作），编译期强制保证任何新增动作类型都显式完成权限面归类。

---

## 7. 补间动画运行时（`TweenScheduler`）

### 7.1 包裹积木与挂起续接语义

`TweenBlock`（「在 X 毫秒内 + 缓动」）是一个 C 形容器积木，内部仅容纳可连续插值的友好元素属性积木（`SetElementProperties` 且 `kind` ∈ `{moveTo, resize, rotateTo, setOpacity, setColor}`）：

1. **起点采样与目标解析**：脚本执行到 `TweenBlock` 时，从数据库读取当前墙基准 `ProjectState`，为包裹内每个属性构造 `(elementId, property, fromValue, toValue)` 目标项。数值属性若写入变量占位符则在入队时解析一次当前数值作为确定性终点；颜色（`TextElement.color`）或填充（`Rect` / `Circle` / `Shape` / `Path` / `Brush` / `Icon` 的 `fill`）若含 `${var:...}` 占位符则标记为 `snap = true`，中间帧保持不变、末帧瞬切写入原占位符字符串。
2. **严格有序的挂起续接**：入队成功后 `ScriptRunner` 挂起当前脚本分支（不阻塞线程）；`TweenScheduler` 将续接回调包装为 `AtomicBoolean` 守护的一次性 `Runnable`，确保**严格在末帧目标值持久化落库完成后**（或任务因异常清理、被同属性新补间接管淘汰时）将续接任务投递回 `ScriptRunner` 线程执行，从而保证补间之后的积木读取元素属性时必定读到补间终值。

### 7.2 独立调度器与双端一致插值

- **独立守护线程**：`TweenScheduler` 运行在独立的单线程 `ScheduledExecutorService`（`hikari-canvas-tween`）上，以 `max(1, round(1000.0 / maxFps))` ms 固定节拍驱动，按墙聚合当前所有活跃任务后按该墙的 `effectiveTweenFps()` 节流渲染（末帧无视节流强制渲染，确保精确停在目标值）。
- **数学与跨线程隔离**：缓动求解直接复用 `EasingSolver`（`LINEAR` / `EASE_IN` / `EASE_OUT` / `EASE_IN_OUT` / `CUBIC_BEZIER`），色彩与同构渐变插值复用 `ColorLerp` 线性 sRGB 空间运算，整数几何属性经 `StrictNumber.clampInt(Math.round(...))` 收敛（与 `docs/rendering.md §9` 完全一致）。构造内存插值帧时先经 `deepCopyState` 克隆图层与元素列表再做更新，确保异步投递给 `AnimationTicker.renderStatic` 的帧对象彻底不可变、无跨线程并发读写撕裂。

### 7.3 静态墙与时间轴墙共存分流

每 tick 实时查询 `AnimationTicker.isWallAnimating(wallId)`（不缓存，适应运行期随时启停时间轴），按墙体状态自动选择最优渲染与落盘路径：

- **静态墙（`!isWallAnimating`，常规场景）**：将同墙所有活跃补间的当前插值合并构造为独立内存帧，调用 `ticker.renderStatic(wallId, frame)` 直接渲染并发包（复用观察者门控与 `staticDiffs` 子矩形差分），**中间帧零数据库写入**；仅在补间到达末帧时调用 `ElementPropertyApplier.applyMany` 将目标值一次性持久化落库并调用 `clearStaticDiff` 释放差分缓存。
- **正在播放时间轴的墙（`isWallAnimating == true`，共存叠加场景）**：由于 `AnimationTicker` 基于数据库基准 `ProjectState` 叠加关键帧偏移，补间引擎在此类墙上改为每渲染帧调用 `ElementPropertyApplier.applyMany` 更新基准状态并触发 `ticker.invalidate`，使时间轴下一帧自动加载新基准值并叠加自身关键帧动画，实现背景时间轴循环与事件补间动画同时运行且互不覆盖。

### 7.4 逐属性冲突接管与活跃会话交互

- **按 `(elementId, property)` 粒度接管与并存**：同一面墙上针对不同元素、或同一元素**不同属性**（如一条规则补间移动 `x/y`，另一条规则同时补间变色 `fill`）的多个 `TweenTask` 并行共存，并在单帧内合并渲染；仅当新入队任务与已有活跃任务争用**同一个 `(elementId, property)`** 时触发平滑接管——新任务直接读取旧任务在当前时刻的实时插值结果作为新起点 `fromValue`，淘汰旧任务（立即触发其一次性续接回调防挂死）并重置帧节流时间戳，视觉过渡连续无跳变。
- **活跃编辑器会话交互**：补间写库走统一的 `ElementPropertyApplier.applyMany`，当该墙打开着编辑器会话时自动经会话通道广播补丁，未打开编辑器时走后台落库；插件关停（`shutdown`）时幂等清空活跃表与静态差分缓存并关闭线程池。

### 7.5 帧率与并发配额

- **每墙独立帧率（`ProjectState.tweenFps`）**：积木编辑器顶栏提供当前墙的补间动画帧率设置（缺省有效值 `30` fps），运行时受服务器 `scripts.tween.max-fps`（默认 `60`）上限钳制。
- **并发上限**：`scripts.tween.max-concurrent`（默认 `16`）限制同时存在活跃补间的墙数；新墙超出并发上限时该 `TweenBlock` 返回 `error` 轨迹步并直接放行后续脚本，不阻塞、不静默降频。

---

## 8. 前端积木编辑器、预览框与幽灵拖动

### 8.1 模块分层与画布交互

前端脚本子系统位于 `web/src/script/`（打包为独立懒加载 chunk `script-engine`），与状态库 `web/src/stores/scriptEdit.ts` 配合工作：

- **`web/src/script/model/`**：`blockDefs.ts`（声明式积木定义、参数槽与分类配色）、`blockTree.ts`（动作树增删移与路径解析）、`dropTarget.ts`（序列垂直吸附槽与 C 形单臂/双臂嵌套槽碰撞几何）、`condition.ts`（条件积木与表达式文本双向转换）、`extractVars.ts`（提取动作字段及 `if`/`waitUntil`/`repeatUntil` 条件串内的 `var("...")` 引用）、`serialize.ts`（`blockLayout` 坐标存取）、`validator.ts`（前端校验镜像）。
- **`web/src/script/canvas/`**：`ScriptEditorOverlay.vue`（TopBar 拼图入口打开的全屏分栏工作台）、`BlockPalette.vue`（按触发 peach / 控制 green / 动作 blue / 时间轴 mauve / 命令 red 分类的左侧积木抽屉）、`BlockCanvas.vue` / `BlockStack.vue` / `BlockNode.vue`、`useBlockCanvas.ts`（DOM + CSS transform 无限画布平移缩放）、`useBlockDrag.ts`（`PointerEvent` 拖出断链与吸附接链）。
- **`web/src/script/params/`**：`BlockParamInput.vue`（复用固定定位浮层 `VariablePicker`、元素/时间轴下拉、缓动曲线选择器）、`ConditionBuilder.vue`、`useCommandTemplates.ts`。
- **布局结构**：`ScriptEditorOverlay` 采用左右可调分栏布局——左侧为主积木画布（默认约 65%），右侧为可拖拽调宽与一键折叠的墙面实时预览框 `PreviewPane.vue`（默认约 35%，折叠态与宽度持久化在 `ui` store 与 `localStorage`）。锁定墙（`isLocked == true`）进入只读浏览态，禁用积木修改与幽灵拖动。

### 8.2 右侧预览框（`PreviewPane`）与元素点选绑定

- **1:1 像素画布渲染**：`PreviewPane` 内部 `<canvas>` 分辨率严格等于墙面真实像素宽高（`canvasPixelWidth × canvasPixelHeight`），通过 CSS `image-rendering: pixelated` 等比居中缩放适配容器，调用只读 `renderProjectState` 实时渲染当前墙元素现状（`requestAnimationFrame` 合帧重绘），不直接修改 `ProjectState`。
- **双通道元素绑定**：聚焦任何含 `elementId` 字段的积木时记录 `activeElementBinding` 路径，当前已绑定元素在预览框内以 Catppuccin mauve 线框（线宽按 `2 / scale` 反补偿保持视觉约 2 CSS px）高亮；点击预览画布时按逆渲染序调用 `elementToPolygon`（精确含旋转矩形）+ `pointInPolygon` 射线法命中顶层可见元素，自动回填 `elementId` 并按友好积木种类抓取元素当前几何值填入 `patch` 初始值。

### 8.3 幽灵虚影拖动与旋转逆变换几何（`ghostDrag.ts`）

当用户在积木画布聚焦 `moveTo`、`resize` 或 `rotateTo` 三类坐标友好积木的任意字段且已绑定可见元素时，`PreviewPane` 自动激活 `activeCoordBlock` 幽灵取点层（无需额外切换模式，纯前端交互，写回统一走 `scriptEdit.updateActionField`）：

1. **半透明真样虚影与恒定手柄**：基于绑定元素叠加当前积木 `patch` 构造目标几何副本 `ghostEl`，在墙像素空间以 `globalAlpha = 0.5` 调用 `drawElement`（强制关闭 dither 提前返回分支以确保半透明生效）叠画元素在目标状态的真实外观，外描 mauve 旋转包围盒轮廓与半径 `7 / scale` 墙像素（屏幕恒定 7 CSS px）的控制手柄。同一时刻仅显示当前聚焦积木这一个目标虚影。
2. **零舍入漂移的客户端→墙坐标反算**：指针屏幕坐标直接以 `<canvas>` 自身的 `getBoundingClientRect()` 为基准线性反算：
   $$\text{wallX} = \frac{(\text{clientX} - \text{rect.left}) \times \text{wallW}}{\text{rect.width}}, \quad \text{wallY} = \frac{(\text{clientY} - \text{rect.top}) \times \text{wallH}}{\text{rect.height}}$$
   直接消除居中偏移与 CSS 像素取整带来的半像素累积误差。`pointerdown` 优先检测虚影手柄与包围盒，未命中时自然回退至 §8.2 元素点选改绑。
3. **三类几何手柄与旋转逆变换数学**：设屏幕坐标系（$y$ 轴向下，正角顺时针，与 `PreviewRenderer` 一致）绕中心 $(c_x, c_y) = (x + w/2, y + h/2)$ 旋转 $\theta$ 度的变换为 $\text{rotatePoint}(p_x, p_y, c_x, c_y, \theta)$，起拖瞬间冻结初始虚影快照 `startG` 并拍入一次草稿撤销快照（拖动过程 `coalesce = true` 不刷爆撤销栈，同时置 `dragging = true` 屏蔽远端树刷新覆盖）：
   - **`moveTo`（平移）**：无外置圆点，直接命中虚影自身包围盒（将指针按 $-\theta$ 逆旋转回局部未旋转空间判定是否落在 $[x, x+w] \times [y, y+h]$ 内）；拖动按墙坐标平移增量更新 `x = round(startG.x + curWx - startWx)`、`y = round(startG.y + curWy - startWy)`。
   - **`resize`（缩放宽高）**：右下角控制点位于 $\text{rotatePoint}(x+w, y+h, c_x, c_y, \theta)$；拖动时固定左上角 `(startG.x, startG.y)` 锚定，将当前指针绕 `startG` 中心 $(c_x, c_y)$ 按 $-\text{startG.rotation}$ 逆旋转回局部空间得到 $(\text{local}_x, \text{local}_y)$，计算 `w = max(1, round(local.x - startG.x))`、`h = max(1, round(local.y - startG.y))`。
   - **`rotateTo`（旋转角度）**：旋转手柄位于上沿外延 24 墙像素处经 $\theta$ 旋转后的位置 $\text{rotatePoint}(c_x, y - 24, c_x, c_y, \theta)$（连杆连接中心）；拖动时按中心指向当前指针的方位角换算顺时针角度（正上方对应 `0°`）并规范化至 `[0, 360)` 整数：`rotation = (((round(atan2(curWy - cy, curWx - cx) * 180 / PI + 90)) % 360) + 360) % 360`。

### 8.4 变量监控面板与试跑高亮

- **脚本变量监控（`ScriptVariableWatch.vue`）**：右下角面板通过 `extractVars` 自动汇总当前脚本在动作字段与条件表达式中引用的全部变量及其实时值；对数值型变量提供单步 `−1 / +1` 按钮（支持长按连加 `useLongPressIncrement`，直接发送 `variable.set` 并带乐观更新回滚），便于无需离开脚本界面即可调试阈值分支。
- **试跑轨迹步进高亮**：收到服务端 `script.trace` 后存入 `lastTrace`，按 `steps` 顺序以 120ms 间隔在对应 `blockId` 积木外框播放状态高亮（`ok` = 绿、`blocked` = 黄、`error` = 红，鼠标悬停查看条件求值结果或错误详情）。
