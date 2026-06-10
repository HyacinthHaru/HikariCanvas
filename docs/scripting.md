# 视觉运行时 / 积木脚本（0.7.0）设计总纲

> **定稿 2026-06-10（brainstorming D1-D6 用户拍板）。** 本文件是 0.7.0 的契约总纲,取代
> `dynamic-data.md §13.5` 的纸面预估(该节保留为档案,以本文为权威)。配套契约:
> `protocol.md`(v3→v4)、`data-model.md`(V017 wall_scripts)、`architecture.md`(TriggerRouter / ScriptRunner)。
>
> **写代码前必读本文。要改契约 → 先改本文,再改代码。**

一句话:让墙从「被动展示数据」升级到「主动响应事件并执行副作用」。玩家用 Scratch 风格的
可视化积木编排逻辑:「有人被击杀 → 比分 +1 → 如果比分 ≥ 10 → 播 MVP 时间轴 + 播声音 + 执行命令模板」。

时间轴(0.6)回答「**什么时间**显示什么」;视觉运行时(0.7)回答「**什么条件下**做什么」。

---

## 0. 决策摘要(固化后不可越界)

| # | 决策 | 结论 | 理由速记 |
|---|---|---|---|
| **D1** | 产品形态 | **自写积木画布**(真 Scratch 风;不用 Blockly、不用规则卡片) | Blockly 双向 schema 同步是全期最大维护负担 + UI 与 Catppuccin 不合 + bundle +300KB;自写引擎 ~80-100h 但完全可控 |
| **D2** | 与时间轴关系 | **脚本是上层,时间轴是被编排的素材**;同画布共存,「一画布二选一」作废 | 0.6 触发器已是微型规则引擎;脚本 playTimeline 把时间轴当资源;0.6 三种触发器原样保留给简单场景 |
| **D3** | v1 触发器 | **6 个**:变量变化 / 定时器 / 玩家进服 / 玩家被击杀 / 玩家靠近(周期采样) / 墙就绪 | 覆盖地铁屏/PvP/欢迎墙全部已知场景;OnCommand/OnLockChange 低频且安全面大,推后续 |
| **D4** | v1 动作 | **8 个**:设变量 / 变量增减 / 改元素属性 / 播时间轴(play·pause·seek) / 播声音 / 等待 / 执行命令 / 日志。**执行命令 = 服主 config 白名单模板 + 填参,禁自由拼接** | RCE 面收敛到「服主自己写的模板」内;白名单空 = 积木灰显;挂命令积木需独立权限默 op |
| **D5** | 执行权威 | **后端唯一执行器,前端积木纯 UI**;编辑器「试跑」= WS op 让后端真执行一次,轨迹回推逐积木高亮 | 零双端逻辑分叉(双端一致哲学);试跑即生产行为;副作用真实发生(文档明示) |
| **D6** | 权限 | **分级放权**:`script.edit` 默 true(无害触发器+无害动作)/ `script.trigger.global` 默 true(击杀/进服帽子)/ `script.sound` 默 true / `script.command` 默 op | 沿用项目一贯「默认玩家可用 + 敏感面独立节点」;服主可按面收紧 |
| **D7** | 数据归属 | **独立 ScriptStore + V017 `wall_scripts` 表,不进 ProjectState**;积木摆放坐标(blockLayout)随 ScriptRule 存但执行器不读 | 脚本与渲染/编辑解耦,state.patch 不膨胀;`.canvas` 工程文件**包含脚本**(随墙走),命令模板按名引用、导入端缺失则积木灰显 |
| **D8** | ABA 熔断哲学 | 触发链深度 ≥ 8 掐断本次执行 + audit 记录,**不自动禁用脚本** | 工具不是保姆(PROPOSAL §2.1):告诉服主,不替他关 |

---

## 1. 目标与范围

### 1.1 做 / 不做

**做(v1)**:
- 6 触发器 + 8 动作 + 条件分支(如果/否则 + 比较/与或非/变量取值)
- 自写积木画布引擎(拖拽 / 吸附 / 嵌套 / 序列化)
- 后端执行引擎(Budget + ABA 熔断 + 主线程 hop + audit)
- 试跑 + 轨迹高亮
- 命令模板白名单系统

**不做(明确砍掉,防 scope 膨胀)**:
- 循环积木(while/for)——v1 无循环,重复靠定时器触发;防失控的最大杀器
- 自定义函数 / 过程积木——留 1.x
- 跨墙脚本(一个脚本操作多面墙)——脚本归墙
- 客户端本地模拟执行——D5 否决,永不
- OnCommand / OnLockChange 触发器——推后续
- 脚本市场 / 分享——模板创意工坊范式留 1.x

### 1.2 MVP 定义(P2 末闸)

命令行(或测试)以 JSON 建一条规则:「变量 X 变化 → 如果 X ≥ 10 → 设变量 Y + 播时间轴」,
部署的墙在游戏内生效。无前端 UI。

---

## 2. 数据结构

### 2.1 `ScriptRule`(顶层,一条规则 = 一顶帽子 + 一串积木)

```java
record ScriptRule(
    String id,            // "sr-<8hex>"
    String wallId,
    boolean enabled,
    String name,          // 用户起的名
    Trigger trigger,      // 帽子(每条规则恰好一顶)
    List<Action> actions, // 顺序执行;If 是一种 Action(含嵌套 then/else)
    String blockLayout    // 前端积木画布摆放坐标 JSON;后端不解析、原样存取
) {}
```

- 一面墙可挂多条规则(上限 config `scripts.max-rules-per-wall` 默 16)。
- `condition` 不独立存在——条件以 `If` 动作形态进 actions 树(Scratch 同款语义),
  比「规则级 condition 字段」表达力强(支持多分支、分支后继续)。

### 2.2 `sealed Trigger`(6 子类;wire 格式照 Fill/KfValue 多态范式,type 字段 camelCase)

| type | 字段 | 路由源 |
|---|---|---|
| `variableChange` | `fullName`(支持 `${var:...}` 同款 namespace 注入) | VariableStore ChangeListener(复用 0.6 TimelineTriggerRegistry 的解析+debounce 模式) |
| `timer` | `intervalSeconds`(min 1,max 86400) | ScheduledExecutorService(共享 VariableProvider daemon 线程池) |
| `playerJoin` | — | PlayerJoinEvent(MONITOR) |
| `playerKill` | — | PlayerDeathEvent(MONITOR;killer 非 null 才触发) |
| `playerNear` | `rangeBlocks`(1-32) | **周期采样器**:每 10 tick 主线程扫在线玩家 × 挂此帽子的墙;按世界分桶 + 距离平方预筛;进入范围沿(edge-trigger)才触发,持续在内不重复触发,离开后重置 |
| `wallReady` | — | 墙部署完成 / 服务器启动恢复完成(AnimationTicker autoRegisterAll 同时机) |

### 2.3 `sealed Action`(8 + If;同多态范式)

| type | 字段 | 线程 | 权限面 |
|---|---|---|---|
| `setVariable` | `fullName, value`(value 支持 `${var:...}` 插值) | async | edit |
| `incrementVariable` | `fullName, delta`(double;非数值变量按 0 起算) | async | edit |
| `setElementProperty` | `elementId, property, value`(白名单属性:x/y/w/h/rotation/opacity/text/fill,与 0.6 可动画属性集对齐) | **走 EditSession 标准 op 路径**(不绕 history/lock) | edit |
| `playTimeline` | `timelineId, op`(play/pause/seek)`, seekMs?` | AnimationTicker(现成) | edit |
| `playSound` | `soundId, volume(0-2), pitch(0.5-2), scope`(near=墙周 16 格 / all=全服) | 主线程 hop | sound |
| `wait` | `ms`(50-5000) | 调度续接(不占线程睡眠) | edit |
| `runCommand` | `templateId, params: Map<String,String>` | 主线程 hop,console sender | **command(默 op)** |
| `log` | `message`(插值后入 plugin logger;**不进 audit**——玩家级高频动作进 audit 会刷库,P2 实施期修订;SCRIPT_* 管理事件照常入 audit) | async | edit |
| `if` | `condition: Expr 源串, then: List<Action>, else: List<Action>` | — | edit |

- `if` 嵌套深度 ≤ 4(校验期拒绝);`wait` 嵌套深度 ≤ 3(Budget)。
- 条件求值:**复用 `template/expr/*`**(Expr AST + ExpressionParser + ExpressionEvaluator),
  扩比较(`> >= < <= == !=`)/ 算术(`+ - * /`)/ 逻辑(`&& || !`)/ 变量取值函数 `var("...")`。
  数值语义走 0.6 `StrictNumber` 单一权威,杜绝双端/多处解析分叉。
- `==` / `!=` 对**双侧均为数值形态**的操作数走数值等值(`var("score") == 42` 直接可用,
  `"3.50" == 3.5` 为 true);任一侧非数值形态仍走原链(Boolean truthy / toString 等值),
  故 `"abc" == 0` / `"" == 0` 恒 false(P2-2b 契约修订,规格审查者建议采纳)。
- 条件里的数字字面量不支持科学计数法(`1e3` 是 parse error);变量值字符串按 `StrictNumber`
  文法(含指数形态)可被数值比较。

### 2.4 `Budget`(config `scripts.budget` 段,全部可调)

```yaml
scripts:
  max-rules-per-wall: 16
  budget:
    max-actions-per-run: 50      # 单次触发展开执行的动作总数(含嵌套)
    max-runs-per-second: 10      # 单规则触发频率上限(超出丢弃 + audit RUN_BLOCKED)
    max-chain-depth: 8           # ABA 熔断:脚本写变量→触发别的脚本 的链深
    max-delay-depth: 3           # wait 嵌套
    max-delay-ms: 5000
  command-templates: {}          # 见 §5.2;空 = runCommand 积木灰显
```

### 2.5 持久化(V017)

```sql
CREATE TABLE wall_scripts (
  id         TEXT PRIMARY KEY,
  wall_id    TEXT NOT NULL REFERENCES walls(wall_id) ON DELETE CASCADE,
  enabled    INTEGER NOT NULL DEFAULT 1,
  name       TEXT NOT NULL,
  rule_json  TEXT NOT NULL,     -- ScriptRule 整体 Jackson 序列化(trigger/actions/blockLayout)
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_wall_scripts_wall ON wall_scripts(wall_id);
```

- 整体 JSON 列(照 project_json 范式)而非逐字段拆列:积木树是深嵌套结构,拆列无查询价值。
- 坏 blob 防御:加载失败的规则跳过 + SEVERE log,不拖垮整墙(0.6 P1 同款纪律)。
- `.canvas` 导出**含脚本**;导入时 `runCommand.templateId` 在本服 config 不存在 → 该积木标记
  缺失(前端灰显 + 红 badge),规则其余部分照常可用。

---

## 3. 执行管线

```
游戏事件(Bukkit listener,集中 GameEventListenerHub 一个类)──┐
变量变化(VariableStore ChangeListener)─────────────────────┤
定时器(daemon 线程池)/ playerNear 采样器(主线程,10 tick)──┘
        ↓
TriggerRouter —— 按 (triggerType → wallId → ruleId) 双层索引,O(1) 路由
        │        墙没部署 / 规则 disabled / 无观察者按需照常触发(脚本副作用与渲染无关)
        ↓
ScriptRunner(async 单线程执行队列,与 AnimationTicker 同范式)
        │  ① Budget 闸(runs/s、chain depth)
        │  ② 逐 action 执行;If → ExpressionEvaluator 求值(async,变量读 cached)
        │  ③ wait → 调度续接(队列重入,不睡线程)
        ↓
ActionExecutor —— 按 action 类型分发
        ├ setVariable / increment / log → VariableStore(async 安全)
        ├ setElementProperty → 主线程 hop → EditSession 标准 op(墙开着编辑器时同步进 history;
        │   没开编辑器时走 headless 直改 ProjectState + persistWall,语义同 0.4 动态变量路径)
        ├ playTimeline → AnimationTicker.play/pause/seek(现成,线程安全入口)
        ├ playSound / runCommand → 主线程 hop(Bukkit.dispatchCommand console sender)
        └ 全程三层异常隔离(单 action 失败 → log + 跳过,不断链;照 Provider daemon 范式)
```

**线程纪律**:
1. 条件求值 / 变量读写不在主线程(读 cached,fallback 链同 `${var:X}` 4 档)。
2. 任何 Bukkit 触碰(声音/命令/元素属性走 EditSession)必须主线程 hop。
3. `playerNear` 采样器本身在主线程(读玩家位置必须),但只做距离平方比较 + 投递,单轮预算
   微秒级;触发后续全部 async。
4. ScriptRunner 单线程队列:同一时刻最多一个 action 在跑,天然串行化同墙脚本副作用,
   避免并发写 ProjectState。

**ABA 熔断(D8)**:`setVariable/increment` 写入时携带 `(sourceRuleId, chainDepth)`;
ChangeListener 触发下游脚本时 depth+1;≥ `max-chain-depth` → 掐断本次执行 +
audit `SCRIPT_RUN_BLOCKED(reason=chain)` + plugin logger WARN(含链路径)。**不自动禁用规则**。

---

## 4. 协议(v3 → v4)

干净切换(照 v2→v3 范式):`Protocol.SUPPORTED_MIN/MAX = 4/4`,前端 `CLIENT_V = 4`,Envelope 壳不动。

### 4.1 新 op(5 个)

| op | payload | 权限 | 备注 |
|---|---|---|---|
| `script.create` | `{rule}`(完整 ScriptRule,id 服务端发) | script.edit + 按 trigger/action 检查面权限 | 不进画布 undo(见 §4.3) |
| `script.update` | `{ruleId, rule}`(全量 rule 替换,积木编辑粒度太碎不做 patch op) | 同上 | 不进画布 undo;保存粒度低频,无需 coalesce |
| `script.delete` | `{ruleId}` | script.edit | 不进画布 undo;前端走 inline confirm(0.4.5 范式) |
| `script.enable` | `{ruleId, enabled}` | script.edit | 不进画布 undo |
| `script.test` | `{ruleId}` | script.edit(**试跑也过 command/sound 面权限**) | 真实执行(D5),audit 标 TEST;返回执行轨迹 |

- 面权限检查在 create/update 时按规则内容逐积木判:含全服帽子 → 需 trigger.global;
  含 playSound → 需 sound;含 runCommand → 需 command。**保存时检查,执行时不再检查**
  (规则是 owner 权限快照;owner 失权后旧规则照跑,服主可 disable——与 wall 所有权语义一致)。
- 锁定墙(lockedAt ≠ null):前端积木画布 readonly(同 lock-state 纪律,后端 op 不读 lock)。

### 4.2 ready payload + state.patch

- ready 加 `scripts: ScriptRuleDto[]`(本墙全部规则)。
- 脚本变更走 state.patch `/scripts/<ruleId>` 路径分拣(照 0.4.2 `/aliases/` 范式),
  广播到本墙全部 session。
- `script.test` 轨迹**不走 patch**,作为该 op 的 ack result 直接返回:
  ```json
  { "steps": [ {"blockId":"...", "kind":"trigger|condition|action",
                "result":"ok|skipped|blocked|error", "detail":"条件值/错误信息"} ] }
  ```
  blockId = **动作树路径**(如 `trigger` / `actions/0` / `actions/2/then/1`),由后端执行期
  按树位置生成(P2 实施期修订——P1 数据模型无 per-action id,树路径与前端积木树天然同构,
  前端无需在 rule_json 里存 id 即可定位高亮)。

### 4.3 撤销(2026-06-10 P1 实施期修订)

**脚本 op 不进画布 undo/redo。** 原因:D7 决定脚本不在 ProjectState,而 HistoryStack 快照的是
ProjectSnapshot——「脚本 CRUD 进 HistoryStack」与 D7 矛盾。且项目先例一致:alias / schedule /
rail 这族「不进 ProjectState 的 per-wall 资源」全部不走 EditSession/history。

- 积木画布内的**未保存编辑**由前端引擎层本地 undo(画布编辑态是草稿,保存时一次 `script.update`)。
- `script.delete` 前端走 inline confirm popover(0.4.5 删除范式),弥补不可撤销。

---

## 5. 安全

### 5.1 威胁模型增量(security.md 实施时同步)

| 威胁 | 防御 |
|---|---|
| 命令注入夺权(`/op @s`) | **白名单模板 + 填参**(§5.2),禁自由字符串;模板由服主手写进 config,插件不内置任何模板 |
| 脚本风暴(高频触发拖垮服务器) | Budget 三闸(runs/s + actions/run + chain depth)+ ScriptRunner 单线程队列天然背压 |
| ABA 无限环 | chain depth 熔断(§3) |
| 声音轰炸 | playSound 走 sound 面权限可收;volume/pitch clamp;Budget 限频 |
| 越权挂全服监听 | trigger.global 面权限,保存时检查 |
| `.canvas` 导入带恶意命令 | templateId 按名引用,本服 config 没有 = 积木灰显不可执行 |
| 试跑刷副作用 | script.test 同样过 Budget + 面权限 + audit |

### 5.2 命令模板

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
        player: { type: online-player }   # 只接受在线玩家名,杜绝选择器
```

- `{param}` 替换前转义:剥行内 `/`、换行、`@` 选择器字符(除 `type: online-player` 显式放行
  玩家名白名单),长度上限默 64。
- 执行身份 = console sender(模板是服主写的,服主授权);audit `SCRIPT_COMMAND_EXECUTED`
  记 templateId + 替换后全文 + 来源规则。

### 5.3 audit 事件(7 个)

`SCRIPT_CREATE / SCRIPT_UPDATE / SCRIPT_DELETE / SCRIPT_ENABLE / SCRIPT_RUN_BLOCKED /
SCRIPT_COMMAND_EXECUTED / SCRIPT_TEST`

---

## 6. 前端积木引擎(自写,D1)

### 6.1 分层(引擎与内容解耦)

**引擎层 `web/src/blocks/engine/`**(不知道任何业务积木):
- DOM + CSS 渲染(积木是文字/下拉/输入框密集体,Konva 不合适;画布 pan/zoom 用 CSS transform)
- 无限画布 + 拖拽(PointerEvent,复用 M12 接管经验)+ 吸附点(snap slot,垂直序列吸附 +
  C 形嵌套槽)+ 拖出断链 / 拖入接链
- 积木树 ↔ ScriptRule JSON 双向序列化;blockId 在创建积木时分配
- 本地 undo 栈(画布草稿态)

**内容层 `web/src/blocks/defs/`**(声明式注册):
- 每种积木一个 def:`{ kind, category, color, slots: [label|dropdown|input|expr|statement] }`
- 6 帽子 + 8 动作 + if/else + 条件表达式积木(比较/与或非/变量取值/字面量)
- 下拉数据源复用现有 store:变量下拉 = VariablePicker(**按钮触发开 + fixed 浮层**,
  见 memory 约束)/ 时间轴下拉 = project.timelines / 元素下拉 = project.elements /
  模板下拉 = 新 `GET /api/script/command-templates`(只返 id+param 名,不泄命令原文)

### 6.2 视觉

- Catppuccin 按类别分色:触发=peach / 动作=blue / 条件·逻辑=green / 时间轴=mauve / 危险(命令)=red
- 入口:TopBar 新按钮(Puzzle 图标)→ **全屏 overlay 积木画布**(不是侧 panel,积木需要空间);
  左侧积木 palette(按类别分组,拖出生成),右上「试跑 / 保存 / 关闭」
- 试跑轨迹:steps 按 blockId 逐个亮绿边框(blocked=黄 / error=红),120ms 间隔步进动画
- 懒加载独立 chunk(照 0.6 dock 范式),不进主 bundle

### 6.3 i18n

全部积木文案大白话(「当 有玩家被击杀」「把变量 _ 增加 _」);禁内部术语。中英双语。

---

## 7. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| 自写积木引擎工时失控(拖拽/吸附/嵌套边缘 case 多) | **高** | P4 只做引擎层 + 假积木验收闸;嵌套深度 ≤4 收敛 case;不做循环积木 |
| playerNear 采样器主线程成本(玩家多 × 墙多) | 中 | 世界分桶 + 距离平方 + 仅挂该帽子的墙;预算实测进 P6 压测;config 可调采样间隔 |
| ChangeListener 同步 for-loop 被脚本放大(§13.5 风险 4) | 中 | TriggerRouter 只在 listener 里做 O(1) 投递,重活全在 async 队列;实测后如仍痛再改异步分发 |
| EditSession headless 直改与开着编辑器的双路径分叉 | 中 | 0.4 动态变量已踩过同路径,照其范式;P2 单测覆盖两路径 |
| 试跑真副作用被误解 | 低 | UI 试跑按钮旁固定提示「真的会执行」;教程明示 |
| 协议 v4 干净切换的旧客户端 | 低 | 4002 close + 提示刷新(0.6 v3 同款,已验证) |

---

## 8. 分期(6 段,~340h;MVP 闸在 P2 末)

| 段 | 内容 | 闸 | 估时 |
|---|---|---:|---:|
| **P1** ✅ 2026-06-10 | 数据模型 + V017 + ScriptStore/Dao + sealed Trigger/Action Jackson 多态 + 协议 v4 5 op + 权限节点 + ready/patch + 前端镜像(types/wsClient/store)。4 批次 + 3 轮质量修复 + 全程对抗终审;后端 1378 / 前端 529 全绿 | 后端单测全绿 ✅ | ~50h |
| **P2** ✅ 2026-06-10 | 执行引擎:TriggerRouter(变量/定时/墙就绪 3 触发器,无 debounce——Budget 即节流)+ ScriptRunner(单线程帧栈 + wait 续接 + K1 ThreadLocal 链深)+ ActionExecutor(8 动作,setElementProperty 双路径)+ Budget 三闸/ABA 熔断 + ConditionEvaluator(expr 扩比较/算术/var() + == 数值等值修订)。3 批次 + MVP 集成测试 7 case;后端 1515 / 前端 529 全绿 | **MVP 闸:JSON 建规则游戏内生效(待用户实测)** | ~70h |
| **P3** | 游戏事件层:GameEventListenerHub(进服/击杀)+ playerNear 采样器 + 命令模板系统 + script.test 轨迹 | 6 触发 8 动作全通(单测) | ~50h |
| **P4** | 积木引擎层:画布/拖拽/吸附/嵌套/序列化/本地 undo,2-3 个假积木验收 | 引擎可拖可嵌可存(用户实测) | ~70h |
| **P5** | 积木内容层:全部积木 def + 下拉集成 + 试跑高亮 + 全屏 overlay + i18n | **完整用户实测闸** | ~70h |
| **P6** | 对抗审查(恶意脚本/熔断/采样器压测)+ 大白话教程 `docs/scripting-guide.md` + security.md/architecture.md 回填 + 版本号 + 收尾 | 全绿收口 | ~30h |

节奏照 0.6:每段一闸可演示;P2 / P4 / P5 三道用户实测闸。

## 9. 工时

~340h(原 §13.5 估 360h;0.6 资产——TimelineTriggerRegistry 模式 / AnimationTicker.play /
StrictNumber / coalesce / 协议切换机制——抵掉 ~20h)。wall-clock 按既往节奏预计 1-2 周。

## 10. 未决问题(实现时回填)

- [x] ~~`setElementProperty` headless 路径的 persistWall 节流策略~~ → **P2 拍板:每 action 直落,不节流**。
  理由:Budget 三闸已封顶(10 runs/s × 50 actions/run),且与编辑器 session 路径的 persistWall
  频率同级;同 run 内连续多个 setElementProperty 是 N 次 load+save,成本上限已知可接受
  (ElementPropertyApplier javadoc 记账)。**已知竞态(可接受)**:headless 写入与编辑器
  open/close 瞬间并发时,脚本改动可能被 session 的旧 state persist 覆盖——单属性丢一次
  更新,低频低危,下游 ultrareview 勿当新缺陷重报
- [ ] `timer` 触发器在墙未部署时是否照跑(脚本副作用与渲染无关,倾向照跑;P2 定)
- [ ] playerNear 采样间隔 config 默认值(10 tick 起步,P6 压测回填)
- [ ] 积木画布 pan/zoom 手势与浏览器缩放冲突处理(P4 实测定)

## 11. P1 终审记账(2026-06-10;后续 phase 必读)

P1 全程对抗终审排出的设计债,按归属 phase 记账:

**P2 首任务清单**:
- [x] **ScriptStore 暴露面**(P2-1 ✅ snapshotAll + Listener):补「枚举全部墙规则」snapshot API + mutation 监听钩子(照 VariableStore
  ChangeListener 范式)——TriggerRouter 要建 `(triggerType → wallId → ruleId)` 索引并增量维护;
  byTriggerType 索引放 Router 侧,store 保持哑存储
- [ ] **ScriptTestSeam 必须异步化**:现同步签名阻塞 Jetty WS worker,而合法规则可串 wait 至分钟级,
  前端 sendWithAck 5s 超时必爆。P2 改先 ack 受理 + 轨迹另走帧(或试跑压缩 wait)
- [x] **script.update 缺 enabled 默 true**(P2-1 ✅ 继承现值) → 改继承现值(防第三方 WS 客户端悄悄重启已禁用规则)
- [x] **权限拒绝路径 dispatch 级测试**(P2-1 ✅ MainThreadPerms.testResolver seam + 5 case):checkBasePermission 在线真拒 / checkFacets 拒绝 + audit
  全链零测试(批次3 #1 修的正是这条路径)——P2 用 MockBukkit 在线玩家 deny case 补
- [x] 数值字段小数静默截断(P2-1 ✅ K8 收紧:非整数值拒 INVALID_PAYLOAD)(`intervalSeconds=1.9→1`,canConvertToInt 只查范围):P2 决定收紧或接受

**P4/P5 记账**:
- [ ] 4 个错误码 i18n key(`SCRIPT_INVALID / SCRIPT_NOT_FOUND / SCRIPT_QUOTA_EXCEEDED /
  SCRIPT_ENGINE_UNAVAILABLE`),目前 ack reject 回退 raw code
- [ ] 前端 validator 镜像(本地预校验,别让用户拖完积木保存才被打回)+ `setElementProperty.property`
  TS 窄化到 8 白名单 union
- [ ] blockLayout 实际预算须低于 WS 入帧 64KB(BLOCK_LAYOUT_MAX 与帧限同值,贴限必 1009 断连);
  前端发送前长度检查

**已澄清(防后人误判)**:
- `ProjectState.PROTOCOL_VERSION` 留 3 是**有意**(D7 脚本不进 ProjectState,project_json schema
  未变;该常量仅序列化输出无导入校验)
- patch 只推 caller session ≠ 漏广播:byWall 排他锁一墙一活跃 session,等价全墙广播(alias 同例)
