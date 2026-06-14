# 备选积木批（0.7.3）设计总纲

> 补间动画完工后补一批轻量备选积木。契约范式照 `docs/scripting.md`（0.7.0 总纲）/ `scripting-0.7.x.md`。
> 4 个新积木 + 协议升 v7 + 版本号 bump 0.7.3-SNAPSHOT。

---

## 0. 决策摘要（brainstorming 固化，用户确认 2026-06-14）

| # | 积木 | 决策 | 范式参照 |
|---|---|---|---|
| **G1** | 随机分支 | `RandomBranch(probability, then, else)`；probability ∈ [0,100] 百分比；**控制流**（ScriptRunner 随机选臂） | `Action.If`（C 形双臂 + ScriptRunner if 分支） |
| **G2** | 元素置顶/置底 | `SetElementLayer(elementId, mode)`；mode = `front`/`back`；**结构性改 state**（reorder layer 内元素顺序） + 落盘广播 | `CloneElement`/`DeleteElement`（双路径 ElementPropertyApplier / EditSession） |
| **G3** | 变量取整 | `RoundVariable(fullName, mode)`；mode = `round`/`floor`/`ceil`；async | `ScaleVariable`（读变量 → 运算 → setValue，async） |
| **G4** | 标题弹窗 | `ShowTitle(title, subtitle, fadeInMs, stayMs, fadeOutMs, target)`；target = `trigger`/`all`；主线程 | `SendMessage`（主线程 hop + target 分流 + `${var}` 插值） |

**固化默认**：概率百分比（0-100）/ 置顶置底一个积木下拉 / 标题弹窗有 target（复用 sendMessage 范式）/ 取整 3 种。

---

## 1. 各积木详细

### G1 随机分支（控制流，照 if）
- `record RandomBranch(int probability, List<Action> then, List<Action> else)`，wireType `randomBranch`，compact ctor `then/else = List.copyOf`。
- 校验：`probability ∈ [0, 100]`；then/else 可空（至少一臂有动作？——参照 if，then/else 都可空，递归校验）。
- **ScriptRunner**：instanceof 链拦截（照 if 分支）——运行时 `rng.nextInt(100) < probability` → 压 then，否则压 else。**rng 用注入 seam**（`IntSupplier`/`Random`，测试可注入确定性）。blockId：`then[j]=<blockId>/then/<j>` / `else[j]=<blockId>/else/<j>`（照 if 逐字符同构）。
- 前端：C 形双臂（照 if 的 then/else 渲染）；probability number 字段（0-100）。

### G2 元素置顶/置底（结构性改 state）
- `record SetElementLayer(String elementId, String mode)`，wireType `setElementLayer`；mode ∈ {`front`,`back`}。
- 校验：elementId 非空；mode 白名单。
- **执行**：把 elementId 在其所属 layer 的 `elements` 列表移到**末尾**（front = 最后渲染 = 显示最上）或**开头**（back = 最先渲染 = 最下）。走 `ElementPropertyApplier` 双路径（session-patch / headless EditSession）——**EditSession 加 `moveElementToFront/Back(elementId)`** 方法（找元素所在 layer + reorder + 重建 state）。落盘 + 广播 + ticker.invalidate（照 clone/delete 的 `applyClone`/`applyDelete` 结构）。
- 找不到元素 → error step。
- 前端：元素字段 + mode 下拉（置顶/置底）。

### G3 变量取整（async）
- `record RoundVariable(String fullName, String mode)`，wireType `roundVariable`；mode ∈ {`round`,`floor`,`ceil`}。
- 校验：fullName 合法（照 setVariable）；mode 白名单。
- **执行**（照 `ScaleVariable`，async）：读变量当前值 → `Double.parse` → `Math.round`/`floor`/`ceil` → `setValue(String.valueOf(结果))`。非数值 → error step（照 scaleVariable 解析失败）。
- 前端：变量字段 + mode 下拉。

### G4 标题弹窗（主线程）
- `record ShowTitle(String title, String subtitle, int fadeInMs, int stayMs, int fadeOutMs, String target)`，wireType `showTitle`；target ∈ {`trigger`,`all`}。
- 校验：title 可空但 title+subtitle 不全空？（参照 sendMessage：text 非空。这里 title 或 subtitle 至少一个非空）；时长 ≥ 0 + 上限（如 fadeIn/out ≤ 10s、stay ≤ 60s）；target 白名单。
- **执行**（照 `SendMessage` doSendMessage，主线程 hop + target 分流）：`player.sendTitle(interpolate(title), interpolate(subtitle), fadeInTicks, stayTicks, fadeOutTicks)`（ms → tick = ms/50）。target=all → `Bukkit.getOnlinePlayers()`；trigger → TRIGGER_DETAIL 触发玩家。title/subtitle `${var}` 插值（照 sendMessage）。
- 前端：主标题 + 副标题 + 3 个时长 number（给默认 fadeIn 500/stay 2000/fadeOut 500）+ target 下拉。

---

## 2. 范围
- **做**：4 个新 Action（record + permits + ActionDeserializer/ActionSerializer/ScriptRuleValidator/ScriptPermissions case + ActionExecutor 或 ScriptRunner + 前端 protocol/blockDefs/blockTree（随机分支 body 容器）/validator/i18n）。
- **协议升 v7**（`Protocol.java` SUPPORTED_MIN/MAX 6→7 + 前端 CLIENT_V 6→7）。
- **版本号** 0.6.0 → 0.7.3-SNAPSHOT（5 处：build.gradle.kts / paper-plugin.yml / 2 demo example / web/package.json + lock）——收尾。

## 3. 不做
- 变量取随机列表（`setRandomVariable` 已有随机数）。

## 4. 关键架构纪律
1. **随机分支 rng 注入 seam**：`ScriptRunner` 的 RandomBranch 用注入的随机源（测试确定性），不直接 `Math.random()`（守可测 + 不破 resume——但脚本运行时不需 resume，主要为测试）。
2. **元素置顶置底走 ElementPropertyApplier 双路径**：编辑器开着 session-patch、headless EditSession，照 clone/delete；reorder 是结构性改 state，落盘广播。
3. **标题弹窗复用 sendMessage 的 target 分流 + 插值 + 主线程 hop**：最少新代码。
4. **blockId 同构**：RandomBranch then/else 照 if（`/then/<j>` `/else/<j>`），前后端逐字符一致。
