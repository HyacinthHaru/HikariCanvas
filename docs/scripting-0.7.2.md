# 视觉运行时打磨 + 积木扩充（0.7.2「稳的」版）设计总纲

> 0.7.1 完工后用户实测提的 2 个小问题 + 一批"稳的"新功能。**补间动画（在 X 秒内 + 缓动）是大功能，
> 单独 brainstorming + 写设计文档后再排，本版不含**（见 §7）。
>
> 契约范式照 `docs/scripting.md`（0.7.0 总纲）/ `docs/scripting-0.7.1.md`。实施前对照本文档。

---

## 0. 决策摘要（固化后不可越界）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| **F1** | 补间动画是否进 0.7.2 | **不进**。「在 X 秒内 <动作> + 非线性缓动」单独 brainstorming + 写设计文档（架构 A 脚本侧自跑 vs B 程序生成临时时间轴、"补间完落 state"语义、缓动 UI、与时间轴并存都要先定死）后再排 | 大功能、有真架构决策；0.6 的 EasingSolver/ColorLerp 数学可复用但"怎么播+怎么落盘"是新问题，不仓促 |
| **F2** | 停止/等待积木分类 | `stopScript` / `waitUntil` / `wait` 的 category 从 `action`(蓝) 改 **`control`(绿)**，与 `if`/`repeat` 同类 | 它们是控制流不是副作用动作；颜色语义对齐 |
| **F3** | 条件里的变量进预览 | `extractVars` 加正则，从 `if`/`waitUntil` 的 condition 文本抠 `var("X")` 引用 → 进右下角"本脚本用到的变量"预览 | 现在只扫"结构字段"型 fullName，漏了条件文本里的占位符引用 |
| **F4** | 变量预览 +1/-1 | 右下角变量面板每个**数值型**变量行加 `−1 / +1`，复用 `useLongPressIncrement`（单击 ±1 / 长按连加）→ 发 `variable.set`；文本型不显 | 不用跳画布就能调变量，调试脚本顺手 |
| **F5** | 克隆/删除元素 = 改 state | 走 EditSession（像 `setElementProperties`/`nudgeElement` 一样改 ProjectState + 持久化 + 广播），**不是渲染层临时改** | 脚本驱动的编辑动作（事件触发的真编辑），与 P-2 反模式（外部定时 patch）不同；克隆/删除是结构性改画板内容 |
| **F6** | 重复直到条件 = 动态循环 | `RepeatUntil(condition, maxIterations, body)`：每轮执行 body → 查 condition，满足/达 maxIterations 上限/Budget 熔断才停。**不预展开**（轮数不定，靠调度逐轮压栈） | 有界 while；maxIterations + Budget 双闸防失控（守"工具不是保姆"——上限是安全阀非自动调优） |
| **F7** | 全服广播 | `sendMessage` 加可选 `target` 字段（`trigger` 默认 / `all`），友好积木「全服广播」= `target=all`；executor 按 target 分流（trigger→触发玩家、all→`Bukkit.getOnlinePlayers()`） | 复用 sendMessage 的 channel(chat/actionbar/title) + 插值；最少新代码，向后兼容（旧 sendMessage 无 target=trigger） |
| **F8** | 变量复制/拼接 | `CopyVariable(targetFullName, sourceFullName)`（读 source cached 值 → set target）；`AppendVariable(fullName, text)`（读当前 + 追加文本 → set，text 支持插值）。走 VariableStore，async 线程 | 补齐变量运算（已有 set/increment/random/scale，缺"变量间复制""文本拼接"） |
| **F9** | 积木边界 | 新积木只围绕**元素 / 变量 / 流程 / 通知玩家**；**不让脚本随意改世界**（放方块、给物品等走命令白名单模板，不做成积木） | 守 `docs/scripting.md` D6（命令走服主白名单）+ 安全面最小 |
| **F10** | 克隆元素配额 | 克隆受**每墙元素总数上限**约束（config，默如 200）；超限克隆动作返 error step、不崩不串 | 防脚本无限克隆撑爆 state（数据透明 + 安全上限，非自动降级） |

---

## 1. 范围

### 1.1 做
- 2 个小修（F2 category + F3 extractVars）
- 变量预览 +1/-1 快捷调（F4）
- 6 个新积木（§4）
- 积木 UI 打磨（§5）

### 1.2 不做（本版）
- **补间动画 + 非线性缓动**（F1，单独设计 → 后续版本）
- 备选积木（元素置顶/置底、随机分支、标题弹窗、变量取整/取随机列表）——留 future，按需再加
- 让脚本改世界（放方块/给物品/传送，F9）

---

## 2. 两个小修

### 2.1 条件里的变量进预览（F3）
`web/src/script/model/extractVars.ts` 现在 `scanAction` 只认"结构字段"的 fullName（setVariable / incrementVariable / setRandomVariable / scaleVariable 的 fullName 字段）。**漏了 condition 文本**。

修：scanAction 对 `if` / `waitUntil` / `repeatUntil`(新) 的 `condition` 字符串，用与 interpolator 一致的正则提取 `var("rawName")` 里的 rawName，`collectName` 进集合。正则复用前端 interpolator 的 `var(...)` 模式（保持一致）。补 extractVars.test.ts 用例（if 条件 / waitUntil 条件 / 嵌套 / 多个 var）。

### 2.2 停止/等待归控制类（F2）
`web/src/script/model/blockDefs.ts`：`stopScript`(L431) / `waitUntil`(L451) 的 `category: 'action'` → `'control'` + `colorVar: CATEGORY_COLOR_VAR.control`；`wait` 同改。无逻辑改动，纯归类 + 配色。确认 BlockPalette 分组随之把它们挪到"控制"组。

---

## 3. 变量预览 +1/-1（F4）

`ScriptVariableWatch.vue`（右下角面板）每行变量：
- 仅 `type` 为数值型（number/int 之类——按变量 metadata 判定）显示 `−1 / +1` 小按钮
- 复用 `useLongPressIncrement`（0.4.0-P2 既有：单击 ±1 / 300ms 后连加）
- 点击 → `wsClient.sendVariableSet(fullName, 新值)`（现有 op）；乐观本地更新 + 失败回滚
- lock 守卫：墙锁定时按钮 disabled（变量改值本不受 lock 影响，但 UI 一致性——实际变量 set 不读 wall lock，按现有 variable op 行为）
- 文本型变量：不显 ±1（无意义）

> 复用约束（见记忆 `reference_variablepicker_reuse`）：本功能是面板内按钮，不涉及 picker 浮层，无 onClickOutside 陷阱。

---

## 4. 更多积木（6 个，全栈：后端 Action + 序列化 + 校验 + 执行 + 前端镜像）

每个积木 = 一条新 `Action`（照 P5 范式：record + permits + Deserializer/Serializer case + Validator case + Executor/Runner + ScriptPermissions + 前端 protocol/blockDefs/validator/i18n）。

| 积木 | Action | 字段 | 线程 | 权限面 | 复杂度 |
|---|---|---|---|---|---|
| 克隆元素 | `CloneElement` | elementId + offsetX + offsetY | EditSession（主线程 hop 或 session 线程，照 setElementProperties） | edit | 中 |
| 删除元素 | `DeleteElement` | elementId | EditSession | edit | 低 |
| 重复直到条件 | `RepeatUntil` | condition + maxIterations + body: Action[] | ScriptRunner 调度（动态压栈） | edit | 中 |
| 全服广播 | （`SendMessage` 加 `target`） | text + channel + target | 主线程 hop | edit | 低 |
| 变量复制 | `CopyVariable` | targetFullName + sourceFullName | async | edit | 低 |
| 文本拼接 | `AppendVariable` | fullName + text | async | edit | 低 |

### 4.1 克隆/删除元素（F5 + F10）
- 走 EditSession（参照 `setElementProperties` 的改 state 路径 + `nudgeElement` 的读改写）：克隆 = 读元素 → 深拷 + 新 id（`el-<8hex>` 风格）+ 加 offset → addElement 到同 layer；删除 = removeElement。改后持久化 + 广播 state.patch（前端 mirror 自动更新 + Ticker invalidate）。
- 克隆配额（F10）：addElement 前查每墙元素总数 ≥ config 上限 → 返 error step（不崩）。
- 找不到元素 → error step（链继续，照 P5 风格）。

### 4.2 重复直到条件（F6）
- `RepeatUntil(condition, maxIterations≤?, body)`：校验 condition 非空 + 走 K16 保存期预检（接 `checkConditionSyntax`，照 P5 审查修的）；maxIterations ∈ [1, 上限]。
- ScriptRunner：每轮——先查 condition，满足 → 跳出（i++ 继续后续）；否则压一轮 body 帧 + 自身续帧（带轮数计数防超 maxIterations）。body 含 wait/waitUntil → 走帧栈续接。**每轮 body 的 action 计入 max-actions-per-run**（Budget 总闸兜底）。blockId 同构：body[j] = `<blockId>/body/<j>`（不带轮数，照 Repeat 范式）。
- 与 Repeat 区别：Repeat 固定 count 预展开；RepeatUntil 动态（每轮查条件决定是否再来一轮），需要在 Runner 维护"当前轮数 + maxIterations"——用一个轻量循环帧状态（参照 WaitUntil 的 deadline 作参数传递思路，轮数作循环帧字段或 RunState）。

### 4.3 全服广播（F7）
- `SendMessage` record 加 `String target`（"trigger" / "all"）；Deserializer `optionalText` 默认 "trigger"（向后兼容旧 payload 无 target）；executor doSendMessage 按 target 分流：trigger→现有 TRIGGER_DETAIL 取触发玩家；all→`Bukkit.getOnlinePlayers()` 逐个发。
- 前端：友好积木「全服广播」序列化 sendMessage target=all（kind 皮肤，像 setElementProperties 的 friendly）；或独立动作积木——**定：sendMessage 加 target 字段 + 前端两个积木入口（发消息=trigger / 全服广播=all）共用 sendMessage def 但默认值不同**。

### 4.4 变量复制 / 文本拼接（F8）
- `CopyVariable(target, source)`：executor 读 source 的 cached 值（VariableStore.get）→ setValue target。source 不存在 → error step。
- `AppendVariable(fullName, text)`：读当前值 + 追加 interpolate(text) → setValue。text 支持 `${var:X}` 插值。
- 都 async（不主线程），照现有 setVariable/scaleVariable 范式。

---

## 5. 积木 UI 打磨（§5）

纯前端视觉，不改数据/协议：
- 积木圆角 / 阴影 / 分类配色再精细（trigger 帽子形、control 绿、action 蓝、danger 红、timeline 紫的层次）
- 帽子积木（触发器）造型（顶部圆弧凸起，像 Scratch）
- 拖拽落位手感（吸附高亮、空槽提示样式、拖影）
- 积木间距 / 字号 / 参数槽内边距统一
- hover / 选中态反馈

具体清单实施时按现有 BlockNode/BlockStack/BlockPalette 的样式细化，留 ~12h。不引入新依赖。

---

## 6. 分期（~50h）

| 段 | 内容 | 闸 | 估时 |
|---|---|---|---:|
| **P1** | 2 小修（F2 category + F3 extractVars）+ 变量预览 +1/-1（F4） | 实测 | ~10h |
| **P2** | 元素积木（克隆 + 删除，F5/F10）+ 变量积木（复制 + 拼接，F8） | 实测 | ~16h |
| **P3** | 流程积木（重复直到，F6）+ 全服广播（F7） | 实测 | ~12h |
| **P4** | 积木 UI 打磨（§5）+ i18n + 收尾 | 实测 | ~12h |

节奏照 0.7.x：每段一闸、可演示。P1 先把小修 + 变量±1 推出（用户最快见效）。

---

## 7. 补间动画（单独设计，本版不含）

「在 X 秒内把元素移动到/变色到目标 + 非线性缓动」—— 0.7.2「稳的」版完工 + 实测通过后，**单独走一轮 brainstorming + 写 `docs/scripting-tween.md`（或 0.7.3 设计文档）**，把下列决策定死再动手：
- **架构**：A 脚本侧自跑补间（解耦，自建帧推进 + 与时间轴冲突协调）vs B 程序生成临时单段时间轴 + 复用 AnimationTicker（复用整条插值/渲染链，但改 Ticker 承载临时 timeline + 防持久化 + 一墙一时刻约束协调）。调研倾向 B。
- **落 state 语义**：补间完元素停在目标值（永久落盘）——这是补间 vs 时间轴（播完不改 state）的本质差异，两条路都要专门处理"末帧写回"。
- **可复用**：0.6 `EasingSolver`（cubic-bezier + EASE 预设）+ `ColorLerp`（sRGB）双端镜像、缓动选择器前端现成——数学不用重造。
- **缓动 UI**：积木里怎么选缓动（下拉预设 + 可选自定义 cubic-bezier 曲线编辑器，复用时间轴的）。

---

## 8. 未决问题（实现时回填）

- [x] 克隆元素的每墙元素总数上限默认值：**`scripts.max-elements-per-wall` 默 200**（config 可调 + `/canvas reload` 热更 headless 路径与后续新开 session）(P2 已定 2026-06-13)
- [ ] P2 克隆/删除在编辑器开着时抢用户选中焦点：`App.vue` watch `lastAddedElementId`，脚本克隆的 add patch 也触发 auto-select → LOOP 克隆 + 编辑器开着时反复夺走用户选中。对抗审查发现（次要，不丢数据 / 不崩 / 不影响 headless 运行时）；留实测确认烦扰度，修则区分"本地 op-ack add（auto-select）"vs"远端推送 add（不抢焦点）"
- [ ] RepeatUntil 的 maxIterations 上限默认值 + 是否独立 config 还是复用 repeat 的 count 上限（100）——P3 定
- [ ] 变量预览 +1/-1 的"数值型"判定口径（按变量 VarType metadata？字符串能 parse 成数？）——P1 定
- [ ] UI 打磨具体改哪些（圆角值/阴影/配色 token）——P4 按现有样式系统细化
