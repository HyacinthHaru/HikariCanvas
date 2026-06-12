# 视觉运行时体验优化(0.7.1)设计总纲

> **定稿 2026-06-11（brainstorming E1-E9 用户拍板）。** 0.7.0 把脚本从 console JSON 升级成可视化
> 积木编辑器；0.7.1 是**体验优化版**——让"改元素"从抽象手输坐标变成画布上拖一下，并大幅扩充
> 积木/动作/触发器。本文件是 0.7.1 契约总纲，配套 `scripting.md`(0.7.0 总纲，仍为权威基座)。
>
> **写代码前必读本文 + scripting.md。要改契约 → 先改本文，再改代码。**

一句话:把 0.6 时间轴"拉就设"的可视化便利，搬进 0.7 脚本的元素操作——你在预览框里拖一下，
脚本就记下目标坐标，不用再敲数字。

---

## 0. 决策摘要(固化后不可越界)

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| **E1** | 预览框设坐标的交互语义 | **幽灵虚影拾取**:预览显示元素现状(实体，不动)；点积木坐标字段进取点模式 → 拖半透明虚影到目标 → 松手取虚影坐标填积木；虚影留作"该积木的目标"标记 | 脚本"移到 xy"是运行时副作用，元素初始态不该被污染；虚影=纯坐标拾取手势，语义最干净 |
| **E2** | 布局 | **左右分栏可折叠**:左积木画布(主 ~65%)+ 右预览框(~35%，可拖宽/折叠)。预览复用 `PreviewRenderer` 渲当前墙 + 叠幽灵交互层 | 最像 Scratch「积木区+舞台」；预览常驻方便参照，不用时折叠腾空间 |
| **E3** | 深度3是否碰后端 | **纯前端**:幽灵拖动只是积木 x/y 字段的设值 UI，不改数据模型、不碰协议。积木"移到 xy"的 xy 仍是普通数字 | 降低复杂度——深度3是前端交互工程，后端零感知 |
| **E4** | 元素绑定方式 | **下拉 + 预览点选**:积木"元素"字段既可下拉选 elementId(人类可读名)，也可在预览点选高亮，两者同步 | 兼顾键盘/可视两种习惯 |
| **E5** | 多移动动作的虚影显示 | **只显当前选中积木的虚影**:点哪个移动积木，预览只显那一个的目标虚影；切积木切虚影 | 一条脚本多个"移到"时不乱；脚本有 if 分支，多目标连线语义不准 |
| **E6** | 友好元素积木实现 | **8 个友好积木**(移到/改大小/旋转/透明度/显示隐藏/改文字/改颜色/相对移动)。前 7 个绝对设值序列化成**新后端 action `setElementProperties`**(elementId + patch map + kind 皮肤标记；底层 updateElement 已支持批量 patch，1 积木=1 条 action 守 blockId 同构)；**相对移动 (+dx,+dy)** 走 `nudgeElement`（运行时读当前值 + 增量）。保留万能"改元素属性"积木(setElementProperty 单数) | 1 积木=1 action 守同构(P4 幽灵拖动/试跑高亮/undo 天然正确)；E6 原"协议零改"细化为"P1 加 setElementProperties 1 个 action"——P1 本就加发消息/nudge 等后端动作，顺势(2026-06-11 拍板) |
| **E7** | 新动作/触发器协议 | **升 v4 → v5 干净切换**(前端 CLIENT_V=5)：新 Action/Trigger 子类扩了 wire 内容，照 0.6/0.7 先例 | 旧前端(理论不存在)遇新 type 不报错；一致性 |
| **E8** | 循环积木 | **有界「重复 N 次」**:N ≤ 100 硬上限 + 走 Budget(动作数/链深计入)+ 熔断；循环体含 wait 走 0.7.0 调度续接不阻塞线程 | v1 砍循环防失控；有上限版可控，能做跑马灯/批量效果 |
| **E9** | OnCommand 触发器 | **推迟 0.7.2**:动态注册命令 + 防冲突 + 权限的安全设计成本高，单独做 | 不拖慢 0.7.1 主线 |
| **E10** | P4 虚影形态 | **元素半透明真样子**:导出 `PreviewRenderer.drawElement` 渲绑定元素副本(按 patch 覆盖目标 x/y/w/h/rotation)+ `globalAlpha=0.5`，画在墙像素 canvas 上。非抽象方框/连线 | 所见即所得——拖到哪、变多大、转多少角，半透明真样子直接看出效果(2026-06-13 拍板) |
| **E11** | P4 支持的坐标积木 | **移到 + 改大小 + 旋转全 transform**:moveTo 拖虚影中心设 x/y；resize 拖角 handle 设 w/h(左上锚定)；rotateTo 转手柄设 rotation。复用主画布 Transformer 数学(`newSize=oldSize×距离比`、度数 normalize、`rotatePolygon` 旋转矩阵)。P4 内部分 a/b/c 三批各自独立实测 | 三种目标几何都值得可视化拖；分批让"移到"核心闸先到(2026-06-13 拍板) |
| **E12** | P4 进入拖拽 | **选中坐标积木自动显虚影可拖**:聚焦 moveTo/resize/rotateTo 积木(其任意字段 focusin)→ 该积木成 `activeCoordBlock`(复用 scriptEdit `activeElementBinding` 扩展)→ 预览自动显其虚影 + 控制点，直接拖。绑元素仍走 P3「从预览点选」准星，不加额外按钮 | 少一步"进取点模式"；选中即拖最直接，与 E1 原"点字段进取点"统一为"聚焦即显"(2026-06-13 拍板) |

---

## 1. 目标与范围

### 1.1 做 / 不做

**做(0.7.1)**:
- 预览框 + 幽灵拖动设目标坐标(深度3，核心)
- 8 个友好元素积木(前端糖)
- 7 个新动作(后端)+ 4 个新触发器(后端)
- 有界「重复 N 次」循环
- 协议 v5

**不做(明确砍/推迟)**:
- OnCommand 触发器 → 0.7.2(E9)
- 无界循环 / while → 永不(防失控)
- 预览框编辑元素初始态 → 那是主画布 `CanvasView` 的事；预览只读 + 取点，不改 ProjectState
- 预览框模拟脚本运行效果(动画播放) → 预览是**静态坐标参照**，不跑脚本
- 多目标路径连线 → E5 只显当前积木虚影
- 自定义函数/过程积木 → 留远期

### 1.2 完整实测闸

P4 末:用户在预览框里拖虚影设"移到 xy"的目标坐标 → 积木记下 → 部署的墙触发时元素真的移到那。
全程不手输坐标。

---

## 2. 核心交互:预览框 + 幽灵拖动(深度3)

### 2.1 布局重构(E2)

`ScriptEditorOverlay` 从全屏单画布 → 左右分栏:
- 左:`BlockCanvas`(现有，主区 ~65%)
- 右:新 `PreviewPane`(~35%，可拖宽边界 + 折叠按钮；折叠后左积木全宽)
- 折叠态存 ui store(`scriptPreviewCollapsed`)+ localStorage 记宽度

### 2.2 `PreviewPane` 组成
- **渲染层**:复用 `PreviewRenderer`(0.7.0 已有的前端 Canvas 墙渲染)显示当前墙全部元素的**现状**(读 ProjectState / project store)。只读，不响应编辑。
- **交互层**(叠在渲染层上):元素 hit-test(点选高亮)+ 幽灵拖动。
- **坐标系**:PreviewPane 有自适应缩放(墙像素 → 预览像素)+ 偏移；`previewToWall(px,py)` / `wallToPreview(x,y)` 互换。幽灵松手坐标经 `previewToWall` 换算回墙像素坐标填积木。

### 2.3 幽灵拖动流程(E1/E5/E10/E11/E12，P4)

**进入(E12)**:聚焦某个坐标积木(moveTo/resize/rotateTo 的任意字段 focusin)→ 该积木 path 记入 `activeCoordBlock`（复用 scriptEdit `activeElementBinding` 扩展：坐标 friendly 积木的元素字段 / 坐标字段 focusin 都设 path）。切积木 / 失焦 → 换虚影(E5：只显当前积木那一个)。

**虚影渲染(E10)**:积木已绑元素 → PreviewPane 在墙像素 canvas 上叠画该元素的**半透明真样子**——构造元素副本(原元素 + patch 覆盖目标几何：moveTo 改 x/y、resize 改 w/h、rotateTo 改 rotation)，`ctx.save()` + `globalAlpha=0.5` + 导出的 `drawElement` + `restore()`。实体元素仍由底层 `renderProjectState` 正常画(现状，不动)；虚影是"该积木目标"的预览叠层。

**控制点 + 拖动(E11)**，按 `activeCoordBlock` 的 kind 显示对应 handle(墙坐标空间画，handle 半径 `R/scale` 墙像素 → 显示恒定)：
- **moveTo**:整个虚影可拖(命中虚影 bbox)→ 拖动改虚影中心 → 写 patch `{x,y}`(左上 = 中心 − w/2，round 取整)
- **resize**:虚影右下角一个 handle → 拖它改 w/h(左上 x/y 锚定，`newW=max(1,round(指针墙X − x))`、`newH` 同理)
- **rotateTo**:虚影中心正上方一个旋转手柄 → 绕中心 `atan2(py−cy, px−cx)` 算角度 → 写 patch `{rotation}`(normalize 0..359)
- 松手 → `edit.updateActionField(activeCoordBlock, {patch})` 写回(值 String 化)；拖动中本地 mutate patch 让虚影跟手

**坐标反算(P3 审查 M1 正解)**:指针 client 坐标 → 墙坐标，**以 canvas 自己的 `getBoundingClientRect()` 为原点**：`wallX=(e.clientX−crect.left)×wallW/crect.width`、`wallY=(e.clientY−crect.top)×wallH/crect.height`。`crect.width` 就是 `round(wallW*scale)` 后的真实 CSS 宽，比例映射天然消掉 ~0.5px 居中 round 偏差，**不再经 `transform.offset/scale`**。P3 点选也统一改走此式(顺手消 ≤1px 偏差)。

**hit-test 优先级**:onPointerDown 先判虚影控制点(handle / moveTo 虚影 bbox)→ 命中进拖动；否则落回 P3 元素点选(`findElementAt` → 改绑 elementId)。虚影拖动与点选改绑同屏共存、互不抢。

### 2.4 元素绑定(E4)
- 积木的"元素"字段(setElementProperty 及友好积木的 elementId)：
  - 下拉:project 的 `allElements`(0.7.0 已建，人类可读名)
  - 预览点选:点 PreviewPane 里的元素 → 高亮 → 填该积木 elementId；当前积木绑定的元素在预览里描边高亮
- 元素字段空(未绑)→ 幽灵拖动禁用 + 提示"先选元素"

### 2.5 不碰后端(E3)
幽灵拖动、预览框全是前端编辑辅助；产出是友好积木 `setElementProperties {elementId, patch, kind}`(P1 已加的后端 action)的 patch 设值——拖虚影 = 改 patch 的 x/y/w/h/rotation。**P4 不新增任何协议 / 数据模型**:后端 action 形态 P1 已定，P4 只给它的 patch 提供"可视化拖"的设值 UI（唯一写回入口 `edit.updateActionField`）。

---

## 3. 友好元素积木(E6)

新增 8 个友好积木。**前 7 个绝对设值** → 序列化成新后端 action `setElementProperties`(elementId + patch map + kind 皮肤标记)；**相对移动** → `nudgeElement`。一个友好积木 = 一条 action(守 0.7.0 blockId 同构；P4 幽灵拖动 / 试跑高亮 / undo 天然正确)。`kind` 让前端按友好皮肤渲染并消除 patch 推断歧义(后端执行忽略 kind)。万能「改元素属性」仍用 `setElementProperty`(单数)。

| 友好积木 | 序列化 → action | 字段 |
|---|---|---|
| 移动元素到 (x, y) | setElementProperties patch{x,y} kind=moveTo | elementId + x + y(P4 幽灵拖) |
| 移动元素 (+dx, +dy) | nudgeElement(运行时读当前 + 增量) | elementId + dx + dy |
| 改变元素大小 (w, h) | setElementProperties patch{w,h} kind=resize | elementId + w + h |
| 旋转元素到 N° | setElementProperties patch{rotation} kind=rotateTo | elementId + angle |
| 设透明度 / 淡入 / 淡出 | setElementProperties patch{opacity} kind=setOpacity | elementId + opacity |
| 显示 / 隐藏元素 | setElementProperties patch{opacity:1/0} kind=show/hide | elementId |
| 改文字内容 | setElementProperties patch{text} kind=setText | elementId + text(变量插值) |
| 改颜色 / 填充 | setElementProperties patch{fill} kind=setColor | elementId + fill(hex) |

**已定(P1，2026-06-11)**:做 ① —— 新增后端 Action `nudgeElement`(相对移动，运行时读当前值 + 增量)，归入"新动作"(§4)，不是纯前端糖。「显示/隐藏元素」用 opacity 0/1(零 schema 改)。

---

## 4. 新动作(§4，后端新 Action 子类，协议 v5)

| 动作 | 字段 | 线程 | 权限面 | 备注 |
|---|---|---|---|---|
| 发消息给玩家 | text(插值) + channel(chat/actionbar/title) | 主线程 hop | edit | 给**触发该脚本的玩家**(TriggerContext.detail) |
| 播放粒子 | particle + count + 墙周偏移 | 主线程 hop | sound(复用声音面或新 particle 面) | 墙世界坐标 |
| 设随机数变量 | fullName + min + max | async | edit | 补变量运算 |
| 变量乘 / 除 | fullName + factor | async | edit | 补 increment |
| 播时间轴并等播完 | timelineId | Ticker + 续接 | edit | 衔接 0.6；等待靠时间轴 durationMs |
| 重复 N 次 | count(≤100) + body: Action[] | 调度续接 | edit | E8，见 §5 |
| 等待直到条件 | condition + timeoutMs | 调度轮询 | edit | 复用 ConditionEvaluator + 超时 |
| 停止本脚本 | — | — | edit | 中止当前 run |
| nudgeElement(相对移动) | elementId + dx + dy | EditSession op(读当前+增量) | edit | §3 友好积木「相对移动」的后端 |
| setElementProperties(友好积木批量设属性) | elementId + patch map + kind | EditSession op | edit | §3 前 7 个友好积木的序列化目标；1 积木=1 条守同构；kind 后端忽略 |

§3 友好积木里"发消息""相对移动"等若需运行时逻辑，归这里(后端 Action)；纯属性设值的归 §3 前端糖。

## 5. 有界循环「重复 N 次」(E8)

`Action.Repeat(int count, List<Action> body)`:
- 校验:count ∈ [1,100]；body 非空；body 内可嵌套(深度计入 MAX_IF_DEPTH 同族限制)
- 执行(ScriptRunner):展开为 count 轮 body，每轮的 action 计入 `max-actions-per-run`(50)——**所以"重复 100 次 × body 1 动作"会撞 50 上限被熔断**，这是预期(Budget 是总闸)；用户要大循环需调 config 或拆
- body 含 wait → 走 0.7.0 帧栈调度续接，不阻塞线程
- 前端:C 形积木(像 if，但单"循环体"槽 + count 字段)

## 6. 新触发器(后端，协议 v5)

| 触发器 | 字段 | 事件源 |
|---|---|---|
| 右键墙 | — | ItemFrame 交互事件(PlayerInteractEntityEvent，命中本墙 ItemFrame) |
| 玩家离开靠近区域 | rangeBlocks | PlayerNearSampler 的**离开沿**(现只有进入沿，补离开) |
| 玩家退服 | — | PlayerQuitEvent |

权限:右键墙/退服走 `canvas.script.trigger.global`(同 join/kill 族)。右键墙的"哪面墙"由 ItemFrame → wallId 解析。

## 7. 协议影响(E7)

- 升 `Protocol.SUPPORTED_MIN/MAX = 5/5`，前端 `CLIENT_V = 5`，干净切换(4002 拒旧)。
- 新 Action 子类(发消息/粒子/随机/乘除/播完等待/重复/等待直到/停止/nudge)+ 新 Trigger 子类(右键墙/离开区域/退服)加进 sealed 接口 + 双向 wire 多态 + validator + 前端 blockDefs + TS 类型镜像。
- friendly 元素积木(§3)序列化成新 action `setElementProperties` / `nudgeElement`(§4)——属 v5 新后端子类；万能「改元素属性」仍用 `setElementProperty`(单数，v4 已有)。
- `script.*` 5 op 形态不变，ready/patch 路径不变。

## 8. 分期(~150h)

| 段 | 内容 | 闸 | 估时 |
|---|---|---|---:|
| **P1** ✅ | 友好元素积木(§3，8 个)+ 低风险新动作(发消息/随机/乘除/播完等待) + nudge 相对移动(走后端 setElementProperties/nudgeElement) | 实测 | ~30h |
| **P2** ✅ | 新触发器(右键墙/离开区域/退服)+ 有界循环「重复N次」+ 协议 v5 升版 | 实测 | ~30h |
| **P3** ✅ | 预览框布局重构(左右分栏)+ PreviewPane 渲染 + 元素点选取当前值(深度2) | 实测 | ~40h |
| **P4** | 幽灵拖动设目标坐标(深度3核心，全 transform)：**P4a 移到**(拖虚影中心设 x/y + 虚影渲染基建 + 坐标反算 M1 修 + `activeCoordBlock`)/ **P4b 改大小**(角 handle 设 w/h)/ **P4c 旋转**(手柄设 rotation) | **完整实测闸**(P4a 核心) | ~50h |
| **P5** | 剩余动作(粒子/等待直到/停止)+ i18n + validator 镜像补 + 收尾 | 全绿收口 | ~20h |

节奏照 0.7.0:每段一闸、可演示；P1/P3/P4 三道用户实测闸(P4 是核心)。

## 9. 未决问题(实现时回填)

- [x] §3「移动 (+dx,+dy)」相对移动:**做后端 `nudgeElement` Action**(运行时读当前值 + 增量)(P1 已定 2026-06-11)
- [x] 显示/隐藏元素:**用 opacity 0/1**，零 schema 改(P1 已定 2026-06-11)
- [ ] 粒子动作权限面:复用 `canvas.script.sound` 还是新 `canvas.script.particle`(P5 定)
- [x] PreviewPane 预览渲染性能:**先全量重绘**(watch project.state → renderProjectState，requestAnimationFrame 合并避免同帧多次)，元素多时脏区优化**留实测**(工具不是保姆，简单优先；P3 实测闸看卡不卡再定)(P3 已定 2026-06-12)
- [x] P4 虚影形态:**元素半透明真样子**(导出 `drawElement` 渲绑定元素副本 + `globalAlpha=0.5`，按 patch 覆盖目标几何)——非抽象方框/连线(P4 已定 2026-06-13)
- [x] P4 支持的坐标积木:**移到 + 改大小 + 旋转全 transform**(拖中心 / 拖角 / 转手柄)，P4 内部分 a/b/c 三批(P4 已定 2026-06-13)
- [x] P4 进入拖拽:**选中坐标积木(聚焦其字段)自动显虚影可拖**，绑元素仍走 P3 准星(P4 已定 2026-06-13)
- [x] P4 坐标反算 M1:**以 canvas `getBoundingClientRect()` 为原点比例映射** `wallX=(clientX−crect.left)×wallW/crect.width`，消 round 偏差、绕开 `transform.offset`(P4 已定 2026-06-13)
- [ ] 幽灵拖动在折叠态/极小预览框时的最小可用尺寸(P4 实测定)
- [ ] P4 resize 旋转态视觉漂移：rotation≠0 时拖角改大小，SE 角与指针有漂移（写回绕 startG 固定中心反旋，但写回后元素中心随 w/h 移动 → 渲染绕新中心转）。**rotation=0 精确、w/h 值始终对、有数字表单兜底**。对抗审查确认（2026-06-13）；留实测，困扰则旋转态隐藏 resize handle + 文案引导
- [ ] P4 moveTo 虚影 bbox 拦截改绑：moveTo 虚影拦整个 bbox（拖整块），被盖住的元素点不到改绑（resize/rotate 只拦小圆点 handle 不受影响）。留实测，困扰则 moveTo 改"中心拖动把手"+ bbox 其它区落回点选
- [ ] P4 rotate handle 贴墙顶越界：元素贴顶时旋转手柄在 y<0（canvas 外）抓不到，有 rotation 数字表单兜底。留实测，困扰则 handle 夹到画布内
- [x] 重复 N 次撞 max-actions-per-run(50)的用户提示:**不做前端预估警告**——靠运行时 Budget 熔断(超 50 动作 → blocked + 试跑 trace 可见),符合"工具不是保姆"哲学(展开数含 if 分支难准估,运行时数据透明更诚实)。`countBlocks` 仍计 repeat body 节点(积木树硬限 50，非展开数)(P2 已定 2026-06-12)
