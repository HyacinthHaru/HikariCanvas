# 变更日志

本文件记录 Claude 每次对本项目做出的修改。**新条目追加到文件顶部**（倒序）。每条应含：日期、改动范围、简要说明、关联文件。
代码与文档的日常提交信息写 git commit，本文件只留会话级摘要。

---

## 2026-04-21 · M3-T10 帧率节流（投影 5fps + 输入 40/2s）

**范围：** `docs/architecture.md §5.1` + `docs/protocol.md §9` 双层节流落地。

**新增：**

- `render/ProjectionThrottler.java`
  - per-session bucket `{ pending: DirtyRegion, lastProjectAt, flushTask }`
  - `submit(sid, region)`：距上次 flush ≥ 200ms 立即走；否则 `union` 进 pending 并 `runTaskLaterAsynchronously` 调尾帧
  - `union` coalesce 同窗口内的连续 op（10 次快改只投 1~2 次）
  - `discardSession` 取消 pending task、清状态

- `session/SessionRateLimiter.java`
  - 40 msg / 2s 固定窗口计数器（≈ 20 msg/s；协议 §9 阈值）
  - `allow(sid) → boolean`：超限时返 false，WebServer 返 `RATE_LIMITED` 错
  - 协议 §9 的"5 次 / 1min 重复触发 → close 1008"留 M7

**SessionManager forget hooks：**
- 新增 `addForgetHook(Consumer<String>)` + 在 `forget()` 末尾调用
- 主插件注册两条 hook：`throttler::discardSession` + `rateLimiter::discardSession`
- 长运行内存不再随会话数单调增长；hook 异常互不影响（try/catch 逐条执行）

**WebServer 改动：**
- `dispatchEditOp` 第一步就查 `rateLimiter.allow`；超限直接返 `RATE_LIMITED`
- 成功后 `throttler.submit(sid, dirty)` 代替直接 `canvasProjector.project`
- 移除不再用的 `canvasProjector` 字段与构造参数；throttler 内部持有

**前端 100ms 防抖说明：**
- 当前 probe UI 只有 `ping / paint` 按钮，没有真正的编辑 op 发送路径
- 100ms 输入防抖是 M5 编辑器 UI 才能真正验证的东西——现在先把**协议层和后端上限**定准，M5 前端代码自然会按该契约写
- 协议契约：前端**应**在输入中做 100ms 防抖，但**即使不做**，后端 20 msg/s 硬限流兜底；两层独立、缺一不塌

**线程模型：**
- throttler 的尾帧 flush 走 `runTaskLaterAsynchronously`（async 线程），与 compositor 纯函数 + canvasRenderer `ConcurrentMap` 一致，不必切主线程
- rateLimiter 的 `synchronized(bucket)` 锁粒度 = per-session，彼此独立

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/ProjectionThrottler.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionRateLimiter.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`（forget hooks）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（wiring + hooks）

---

## 2026-04-21 · M3-T9 per-viewer 同步（架构已满足）

**结论：** M3-T7/T8 引入的 `HikariCanvasRenderer` + `CanvasProjector` 组合已经天然提供 per-viewer 同步，T9 无代码改动。

**机制：**
- `HikariCanvasRenderer` 是挂在每张 `MapView` 上的 `MapRenderer(contextual=false)`
- Paper 每 tick 对 **每个看得见该 MapView 的 viewer** 调 `render(map, canvas, player)`，从 `pixelsByMapId` 拉最新像素写入 `MapCanvas`
- Paper 负责 diff + 下发 `ClientboundMapItemDataPacket` 到该 viewer

所以"会话外的第三个玩家路过墙面"→ Paper 发现他看到这张 map → 调 render → 拿到当前最新 pixels → 自动同步。无论谁看，数据源都是同一份 `ConcurrentMap<mapId, byte[]>`。

**M3 阶段 `super(false)` = non-contextual：** 所有 viewer 共享同一张 canvas，省 CPU。真正的 per-player 差异化（例如编辑辅助线只对编辑者可见、观众看纯画布）需要切到 `super(true)` 并维护 per-player buffer——留 M7 视需要再做。

**验证留给 M3 总集成实测**（两名玩家同一墙面编辑与旁观）。

**关联文件：** 无改动。

---

## 2026-04-21 · M3-T7 + T8 脏矩形差分 + 多图拼接渲染

**范围：** 编辑 op 成功后把 `ProjectState` 投影到游戏内墙面——受影响 `mapIds` 重绘 palette 像素并推到 `HikariCanvasRenderer`，下一 tick Paper 自动 sync 给所有 viewer。T8 "多图拼接"在 `CanvasCompositor` 的 per-map 合成内自然实现（详见下文），与 T7 一并结清。

**新增文件：**

| 文件 | 职责 |
|---|---|
| `render/DirtyRegion.java` | 画布坐标矩形；`of(element)` / `fullCanvas(state)` / `union` / `coveredMapIndices(w,h)` |
| `render/CanvasCompositor.java` | 纯函数；`compose(state, mapIndex) → byte[128*128]`；palette 缓存 |
| `render/CanvasProjector.java` | `project(session, region)` → 遍历受影响 mapIndex 调 compositor 写 canvasRenderer |
| `render/BitmapFont.java`（扩展） | +17 大写字母 + 14 标点；现覆盖 A-Z / 0-9 / `. , : ; ! ? - _ + = / ( )` |

**多图拼接（T8 实现要点）：** `CanvasCompositor.compose` 对每张 map 独立走：
1. `offsetX = mapCol * 128`、`offsetY = mapRow * 128`（该 map 在画布的左上角像素坐标）
2. 对每个可见 element，坐标转换为 local 域：`localX = e.x() - offsetX`、`localY = e.y() - offsetY`
3. `drawRect` / `drawText` 统一把超出 `[0, 128)` 的像素 clip 掉

这样一个 x=120 / w=30 的 rect 在 widthMaps=2 画布上：map0 localX=120 绘 `x∈[120,128)`、map1 localX=-8 绘 `x∈[0,22)`——拼起来还是一个完整 30px 矩形，无缝跨图。

**脏矩形规则（EditSession 侧扩展）：**

| op | region |
|---|---|
| `element.add` | 新元素 bbox |
| `element.update` | 旧 bbox ∪ 新 bbox |
| `element.delete` | 被删元素 bbox |
| `element.reorder` | 被移动元素 bbox（z-order 变化触发该区域下层元素重合成）|
| `element.transform` | = update 路径（旧 ∪ 新）|
| `canvas.background` | 整个画布 |
| `canvas.resize` (no-op) | `null`（无像素变化）|

`EditSession.OpResult.Ok` 扩字段 `DirtyRegion dirty`，WebServer 在 ack + pushPatch 之后按 region 调 `canvasProjector.project`。

**M3-T7 主动简化（documented）：**
- `rotation != 0` 的元素按 `rotation=0` 渲染（log WARN 一次），真 rotation 留 M4
- Text wrap 不实装，单行渲染；元素自身 `w` 仅用于 `align` 中心/右对齐偏移计算
- `fontId` 当前只识别 `"bitmap"`（默认），M4 TTF 系统接入再扩
- fontSize 离散映射：`scale = max(1, round(fontSize/7))`（7→1×、14→2×、21→3×）
- `rect.stroke.width` 自动 cap 到 `min(w,h)/2` 防溢出

**调色板策略：** 用 Bukkit `MapPalette.matchColor(int,int,int)`（256 色全映射）+ hex string → byte 缓存。Paper 1.21.11 这个 API 和它的 `Color` 重载都标 `@Deprecated(forRemoval=true)`，但官方没给替代且 `CLAUDE.md` 锁 Paper 1.21；`@SuppressWarnings("removal")` 收口，M4 `docs/rendering.md` 真正 LUT 接入后整类替代。

**线程模型：** compositor 纯函数，projector 写 `ConcurrentMap`，`canvasRenderer.render()` 由 Paper 主线程每 tick 调用——三层互不打架，WS 事件处理线程直接调 projector 即可，不必切主线程。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/DirtyRegion.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`（扩字表）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（OpResult.Ok 扩 dirty 字段）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（dispatchEditOp 接 projector）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（main wiring）

---

## 2026-04-21 · M3-T6 element.* + canvas.* op 族

**范围：** WS 上行编辑 op 全部接入 `EditSession`，落到 `ProjectState`，产出 `state.patch` 推回前端。契约对应 `docs/protocol.md §5.3 / §5.4`。

**新增 `state/EditSession.java`：**
- `OpResult` sealed 结果类型：`Ok(StatePatch) / Error(code, message)`
- 7 个 `apply*` 方法（全部 `synchronized(this)`，Jetty 线程池下并发安全）：
  - `addElement(type, props, afterId)` — 生成 `"e-<uuid>"`；默认参数兜底；支持 `afterId=null` 追加尾部
  - `updateElement(elementId, patch)` — 字段级部分更新；all-or-nothing 校验；不变量失败回滚
  - `deleteElement(elementId)`
  - `reorderElement(elementId, newIndex)` — 越界 clamp；same-position 空 ops + bump version
  - `transformElement(elementId, x?, y?, w?, h?, rotation?)` — 等价于 update 五字段子集
  - `resizeCanvas(widthMaps, heightMaps)` — **M3 仅接受 no-op 同尺寸**；差值返回 `POOL_EXHAUSTED`（真动态扩缩容 M7 再做）
  - `setBackground(color)`
- Validator 集中在文件内静态方法：
  - color `^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$`
  - rotation ∈ {0, 90, 180, 270}
  - text length ≤ 256 / fontSize 1..512 / stroke.width 0..128 / w/h 1..10000 / x/y ±10000
  - align ∈ {left, center, right}
- Rect 不变式：`fill == null && (stroke == null || stroke.width == 0)` 拒绝（至少一种填充方式）
- 失败路径统一通过内部 `ValidationException(code, msg)` 抛出 → apply 方法外层 catch 转成 `OpResult.Error`

**patch 路径约定（RFC 6902）：**
- `element.add` → `add /elements/{idx} {element}`
- `element.update` → 逐字段 `replace /elements/{idx}/{field} {value}`；**`value == null` 改用 `remove`** 以规避 `NON_NULL` 序列化丢 value 字段违反 RFC 的坑
- `element.delete` → `remove /elements/{idx}`
- `element.reorder` → `remove /elements/{from}` + `add /elements/{to} {element}`
- `element.transform` → 走 updateElement 路径，逐变字段 `replace`
- `canvas.background` → `replace /canvas/background {color}`
- `canvas.resize`（no-op 情况）→ 空 ops 列表 + bump version

**WebServer 分发（单个 switch 入栈）：**
- 新 helper `dispatchEditOp(ctx, in, sid)`：
  1. 取出 Session.editSession；不存在返回 `SESSION_CLOSED`
  2. 提取 payload Map（类型错误统一 `INVALID_PAYLOAD`）
  3. switch op → 调 EditSession 对应方法
  4. `Ok`：先发 `ack { version }`（对 client id），再 `pushPatch`（s-N id；空 ops 跳过推送）
  5. `Error`：发 `error { code, message }`（对 client id）
- switch 表达式统一 `yield OpResult.Error(...)` 代替 early `return`（Java switch expression 限制）
- 保留 M1 `paint` demo 通道作为 T6 阶段回归测试；M3 收尾（T12/T13）再清

**Session 接入：**
- Session 新增 `editSession` 字段 + public accessor
- `SessionManager.confirm`：`SELECTING → ISSUED` 转移时构造 `EditSession(projectState)` 一起挂到 session 上
- 随 session forget 一起消亡，无需额外清理

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（新建，~380 行）
- `plugin/src/main/java/moe/hikari/canvas/session/Session.java`
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T5 state.snapshot / state.patch 推送基建

**范围：** 服务端主动推送 {`state.snapshot`, `state.patch`} 的基建。契约对应 `docs/protocol.md §5.2`。

**新增：**
- `state/PatchOp.java`：RFC 6902 最小子集 `{ op, path, value }` record + `add/replace/remove` 工厂
- `state/StatePatch.java`：`{ version: long, ops: List<PatchOp> }` record
- `state/StatePatchBuilder.java`：累积式构建器（非线程安全，只在 SessionManager 锁内使用）

**WebServer 扩展：**
- `ConcurrentMap<String, WsContext> wsBySession`：session → 活跃 WS 连接
- `AtomicLong serverIdSeq`：服务端推送 `s-<N>` id 单调源
- 绑定点：`handleAuth` 成功后 put；`onClose` 用 `remove(k, v)` 原子 CAS 避免 race 把新连接抹掉
- 新增 public API：
  - `pushSnapshot(sessionId, ProjectState) → boolean`
  - `pushPatch(sessionId, StatePatch) → boolean`
  - 均返回 `false` 当 session 没有活跃 WS 连接
- 序列化：`NON_NULL` 策略让 `PatchOp.value == null`（remove op）自动省略

**M3-T5 scope 仅基建**：T6 element op 族接入时才开始真实发 patch；T5 本身不变更既有通道行为。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/PatchOp.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/StatePatch.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/StatePatchBuilder.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T4 ProjectState 模型 + Element 接口族

**范围：** 服务端权威工程状态的数据模型落地。契约对应 `docs/protocol.md §7`。

**新增 `moe.hikari.canvas.state` 包：**
- `Element.java`：`sealed interface` + Jackson `@JsonTypeInfo(property="type")` 多态；permits `TextElement / RectElement`
- `TextElement.java`：record，M3 字段 = `id/x/y/w/h/rotation/locked/visible/text/fontId/fontSize/color/align`；effects / lineHeight / letterSpacing / vertical 留 M4
- `RectElement.java`：record，`fill`（可 null 表示空心）+ `stroke`（可 null 表示纯填充）
- `Stroke.java`：record `{ width, color }`，未来 M4 text outline 复用
- `ProjectState.java`：可变 class（不是 record——需要 mutator）；字段 `version / canvas / elements / history`；`@JsonAutoDetect(fieldVisibility=ANY, getterVisibility=NONE)` 让字段直接映射到 JSON 键名
  - 嵌套 record：`Canvas(widthMaps, heightMaps, background)`、`History(undoDepth, redoDepth)`
  - Java-side 无前缀 accessor + 显式 mutator (`addElement / replaceElementAt / removeElementAt / moveElement / bumpVersion / canvas(Canvas) / history(History)`)
  - 线程约束：只允许 `SessionManager.synchronized` 段内 mutator，`elements()` 返回 unmodifiable view

**Session 接入：**
- `Session` 新增 `projectState` 字段 + `projectState()` accessor + package-private `projectState(ProjectState)` mutator
- `SessionManager.confirm`：`SELECTING → ISSUED` 转移时实例化 `new ProjectState(wall.width(), wall.height())`，默认背景 `#FFFFFF`
- `WebServer.handleAuth`：`ready` payload 的 `projectState` 字段直接序列化 `session.projectState()` 对象（不再手写 Map）

**关联文件：**
- 6 个新文件（`state/*.java`）
- `plugin/src/main/java/moe/hikari/canvas/session/Session.java`
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T3 Token rotate（断线重连基建）

**范围：** WS auth 成功后立即 rotate 新 token 给前端，供后续 WS 断线重连使用。契约对应 `docs/security.md §2.2`、`docs/protocol.md §11`。

**后端：**
- `TokenService` 新增 `rotate(playerUuid, playerName, sessionId)`：与 `issue` 语义相同但审计事件为 `AUTH_ROTATED`（区分「首次签发 vs rotate 签发」）；两方法共享 `issueInternal` 私有实现
- `WebServer.handleAuth` 在 `markActive` 后立即 `rotate` 并把新 token 随 `ready` payload 回发：`payload.reconnectToken: string`
- 审计日志里 `AUTH_ROTATED` 只记 `token_sha256`，原文不落盘

**前端：**
- `ReadyPayload` 接口扩 `reconnectToken` 字段
- `handleReady` 存 token 到 `sessionStorage["hikari-canvas:reconnect-token"]`（tab-scoped，关闭失效）
- 页面加载：URL `?token=` 优先（新会话走链接打开），回退 sessionStorage（同 tab 刷新 / 重连）
- token 原文绝不进 console.log，仅 log length

**M3-T3 scope 仅基建**：真正的"WS 断线自动重连循环"（5/10/30 秒阶梯，protocol.md §3.4）留给后续 UX 迭代。目前只保证 rotate token 能发、能收、能存。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `web/src/main.ts`

---

## 2026-04-21 · M3-T2 WS 会话超时回收（SessionReaper）

**范围：** 把 M2 T10/T11 记入 journal 的遗留项「没有主动 schedule 的回收 task」补上。契约对应 `docs/architecture.md §3.1`。

**新增：**
- `session/SessionReaper.java`：主线程 `BukkitScheduler.runTaskTimer`，30 秒扫一次
- `SessionManager.ExpiredSession` record + `synchronized collectExpired(now, issuedTimeout, wsGrace, activeIdle)`：只做决策不做副作用，返回待 cancel 列表

**三条超时规则：**

| 状态 | 条件 | reason | 默认阈值 |
|---|---|---|---|
| `ISSUED` | `now - createdAt > issuedTimeout` | `"issued-timeout"` | 15 min（与 token TTL 一致）|
| `ACTIVE` + 断连 | `now - wsDisconnectedAt > wsGrace` | `"ws-reconnect-timeout"` | 5 min |
| `ACTIVE` + 在线 | `now - lastActivityAt > activeIdle` | `"idle-timeout"` | 30 min |
| `SELECTING` / `CLOSING` | 不超时 | — | — |

**回收流程**（复用 `/canvas cancel` 的模式）：
```
1. 先快照 session.wall().world()（cancel 会把 session forget）
2. sessionManager.cancel(id, reason) → 归还池 + 释放锁 + forget
3. frameDeployer.removeForSession(id, world) → 删 preview 物品框
4. log.info 记录 id / reason / 删框数
```

**主线程约束：** `cancel` 触发 `MapPool.returnToPool`，`removeForSession` 扫 world 实体，两者都只能主线程。用 `runTaskTimer` 不用 `runTaskTimerAsynchronously`。

**阈值来源：** 全部硬编码常量，TODO 待 M7 config.yml 接入后让运维可调。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/session/SessionReaper.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`

---

## 2026-04-21 · M3-T1 WallResolver 补 frame-space air 校验

**范围：** 修 M2 残留 "旁边有草/花时 ItemFrame 闪掉" bug。M3 启动第一步，清 M2 残债。

**改动：**
- `WallResolver.FailReason` 新增 `FRAME_SPACE_BLOCKED`（bbox 前一格被非 air 方块占）
- `resolve()` 内循环：检查 `wall.getRelative(face1)` 必须 `isAir()`；否则返回失败
- 顺序在 `BLOCK_NOT_SOLID` 之后、`OCCUPIED` 之前
- 拒绝一切非 air 方块：短草、花、水、熔岩、雪层等；只有纯 `AIR / CAVE_AIR / VOID_AIR` 通过

**理由：** M2 实测发现若墙面前一格是短草，frame `spawn()` 即便成功，客户端判非法并立即 despawn（"闪一下"）。`OCCUPIED` 只查 ItemFrame 碰撞，漏了非 frame 的占位方块。

**下游兼容：** `WandListener` 和 `CanvasCommand` 直接 print `reason().name()`，加新枚举值不破坏现有代码。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/deploy/WallResolver.java`

---

## 2026-04-21 · M2-T12 集成测试 + 运行时调试 + **M2 完成**

**范围：** 端到端实测联调，沿途修 5 个运行时 bug；M2 所有契约要求的功能就位。`PROPOSAL §6` M2 行从"2 周"更新为"已完成（2026-04-21）"。

**实测达成的完整闭环：**
```
MC 客户端 /canvas edit + 左右键两角 + /canvas confirm
  → 聊天栏收到可点击 editor URL
浏览器点击 URL（MC 弹确认 → 打开本机 8877/?token=...）
  → 页面加载 (plugin serve) → main.ts 取 token → WS auth
  → 收到 ready（带 projectState.canvas.WxH）→ 状态条变绿 + 按钮 enable
浏览器点 Paint
  → WS paint op → HikariCanvas.paintAllSessionMaps
  → 对活跃会话的所有 mapIds 填红色像素 → canvasRenderer.update(...)
  → Paper 下一 tick 自动把 canvas sync 给所有 viewer
  → 游戏里整面墙同时变红
```

**沿途修的 5 个运行时 bug（按发现顺序）：**

1. **命令注册但 Brigadier 报 "unknown"**
   - 症状：`/canvas edit` 等命令显示"未知或不完整的命令"
   - 原因：T11 加了 `.requires("canvas.edit")` 权限检查；玩家默认无此权限，Brigadier 在 tab 补全/执行时直接把命令藏起
   - 修：`paper-plugin.yml` 新增 `permissions:` 字段，`canvas.edit / wand / commit / use` `default: true`；`canvas.admin*` `default: op`

2. **ItemFrame 挂上立刻消失（"闪一下就没了"）**
   - 症状：`/canvas confirm` 后 6 个 frame 瞬间出现又瞬间消失；log 里 `valid=true` 看上去正常
   - 原因：`setFixed(true)` + `setVisible(false)` 在 Paper 1.21 的 `world.spawn(..., consumer)` 内部被 apply 时，客户端 entity desync——add packet 和 metadata update 顺序问题
   - 修：M2 阶段**暂不设 INVISIBLE / FIXED**，保护完全交给 `FrameProtectionListener`。M7 polish 时再回来精调（可能需要 scheduled 1-tick-later 设 fixed）

3. **Placeholder 像素"闪一下被空白覆盖"**
   - 症状：frame 留住了，但 Placeholder 图像只显示一瞬间，立即被 MC 自己 tick 成空白
   - 原因：Bukkit `MapView` 有 per-tick 渲染机制——即使清空 `getRenderers()`，Paper 每 tick 仍然把空 canvas sync 给 viewer，覆盖我们直接 push 的 `ClientboundMapItemDataPacket`
   - 修：**不再对抗 Paper tick**，而是**合作**——新增共享 {@link moe.hikari.canvas.render.HikariCanvasRenderer}（`super(false)` non-contextual），`MapPool` 为每张 MapView `addRenderer`；外部像素改动调 `canvasRenderer.update(mapId, pixels)`，Paper tick 时 renderer 会把像素写进 canvas，自然走官方 sync 通道
   - 关联：`MapCanvas.setPixel` 内部有 dirty flag，重复写相同值不产生 packet，CPU 可接受

4. **HikariCanvasRenderer 被 FrameDeployer 清掉**
   - 症状：bug #3 修完后仍然不生效，log 里看 MapView 当前 renderer 数 = 0
   - 原因：`FrameDeployer.deploy` 里残留了一段"清 renderer"的 debug 代码，把 `MapPool.initialize/expand` 刚装上的 `HikariCanvasRenderer` 又清掉了
   - 修：删除 FrameDeployer 里的清 renderer 代码；MapPool 负责唯一的 renderer 生命周期

5. **浏览器 Paint 点了没反应**
   - 症状：所有 frame OK、Placeholder 稳定显示、浏览器 auth 成功，但点 Paint 按钮墙不变红；后端 log 显示 `painted 0 held maps`
   - 原因：paintHandler 仍是 M1 demo 的"遍历在线玩家主手 `filled_map` 涂红"——M2 玩家 confirm 完手里已无 map item
   - 修：改为 `paintAllSessionMaps`：遍历 `SessionManager.liveSessionIds()`、对每个活跃会话的全部 `mapIds` 填红像素 → `canvasRenderer.update`

**运行时 cleanup（commit 里一并做）：**
- 去掉 FrameDeployer 里 T12 期间加的 spawn log / +1 tick 诊断 log
- 恢复 `FrameProtectionListener` 注册（T12 debug 阶段为排除嫌疑临时注释掉了）
- 修 `SessionManager.confirm` 的 `requireState` 抛异常问题：已 ISSUED 状态二次 confirm 现在返回 `ConfirmResult.NotReady` 而不是抛 IllegalStateException

**另两个已知问题暂未修（不阻塞 M2 验收）：**
- **旁边有草/花时 frame 仍然闪掉消失**：WallResolver 只校验墙面方块，没校验**墙面前方一格（frame 将要占据的格子）**是空气。短草/花/雪层等会让 MC 判 frame 不合法 → despawn。修法：WallResolver 遍历 bbox 时顺便 check `getRelative(facing).getType() == AIR`，非空气 → `OCCUPIED` 或新增 `FRAME_SPACE_BLOCKED` 错误码。留给 M2 polish 小修或 M3 集成时补
- **靠侧墙的某格 frame 不显示**：可能是客户端视距 / chunk tracker / 相邻实体碰撞的边缘情况。留 M7 单独调查

**未做（按 M2 契约范围有意留给后续）：**
- **Token rotate**（security.md §2.2）— 断线重连 M3 再做
- **定时回收 task**（WS 断连 5min 宽限 + auth 5s 超时 + idle disconnect）— M7
- **限流**（security.md §2.4）— M7
- **Origin 校验** — M7
- **`/canvas cleanup` 真正数据回收**（现 stub）— M7 `/canvas fsck`

**里程碑总结（M1→M2）：**

| 任务 | 关键点 |
|---|---|
| T1 改名 /hc → /canvas | 代码跟进 契约 |
| T2 SQLite + HikariCP + JDBI | 5 表 schema v1 |
| T3 TokenService | SecureRandom + CAS 原子 consume + SHA-256 审计 |
| T4 MapPool | **核心机制**，SQLite 幂等 upsert + 不变式自愈 |
| T5 WallResolver | 纯算法 + 7 种失败码 sealed 建模 |
| T6 Wand + SELECTING | 命令/物品双入口；PlayerInteract listener |
| T7 SessionManager | 汇合点，状态机 SELECTING→ISSUED→ACTIVE→CLOSING |
| T8 FrameDeployer + 保护 | 挂框 + PDC + HangingBreak/BlockBreak 拦截 |
| T9 PlaceholderRenderer | 手写 5×7 位图字表，浅灰底 + 水印 + "N/M" |
| T10 WS auth/ready | 预握手 peek + 首帧 consume + rotate 留坑 |
| T11 /canvas 命令族 | 5 sealed ConfirmResult pattern-match + sign_records 入库 + 顺手修 staticFiles fat-jar |
| T12 集成测试 | 5 运行时 bug + HikariCanvasRenderer 设计收敛 |

**M2 工期：** 2026-04-21 立项契约修订 + 当日完成 12 个任务 —— 1 天完成（原估 2 周）。

**关联文件：** `PROPOSAL.md`（§6 M2 行状态更新）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java`、`plugin/src/main/java/moe/hikari/canvas/pool/MapPool.java`、`plugin/src/main/java/moe/hikari/canvas/render/HikariCanvasRenderer.java`（新）、`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`、`plugin/src/main/resources/paper-plugin.yml`、`docs/journal.md`

---

## 2026-04-21 · M2-T11 /canvas 命令族完整实装 + 端到端闭环

**范围：** 把 M2 所有模块（TokenService / MapPool / SessionManager / FrameDeployer / PlaceholderRenderer / WebServer）串成完整业务链路；同时做一个顺手的修复：Javalin 7 静态资源 fat-jar discovery bug（T7a 留的 TODO）。

**后端改动：**

`command/CanvasCommand.java` 完整重写：
- 构造签名从 `(plugin, mapPacketSender, sessionManager)` 重新设计成 `(plugin, sessionManager, frameDeployer, tokenService, mapPool, database, editorUrlTemplate)` —— 去掉 `mapPacketSender`，因为 M2 正式命令路径不直接发包
- 正式子命令全部接入权限 `.requires()`（contract `docs/security.md §6`）：
  - `edit` / `wand` / `confirm` / `cancel` → `canvas.edit`
  - `commit` → `canvas.commit`
  - `stats` / `cleanup` → `canvas.admin`
- `/canvas confirm`：调 `SessionManager.confirm` pattern-match 5 种结果（Ok / NotReady / WallFailed / WallOccupied / PoolExhausted），Ok 时走「`FrameDeployer.deploy` → `TokenService.issue` → 发可点击 Adventure Component URL（`ClickEvent.openUrl` + `HoverEvent.showText`）→ 移除 wand」；FrameDeployer 抛异常时立即 `SessionManager.cancel("deploy-failed")` 回滚
- `/canvas commit`：commit 前**快照 `wall / mapIds / ownerUuid / ownerName`** 到局部变量（因为 `SessionManager.commit` 会 forget session）；然后 `SessionManager.commit → FrameDeployer.promote → INSERT sign_records`
- `/canvas cancel` 增强：commit 前 snapshot `wall.world()`，确保 cancel 后 `FrameDeployer.removeForSession` 有 world 参照
- `/canvas stats`：`MapPool.stats() + SessionManager.size() + TokenService.activeCount()` 一行输出
- `/canvas cleanup`：**M2 阶段 stub**，只打印 `soft-deleted sign_records` 数量；实际数据回收 + map 归还留给 M7 的 `/canvas fsck` 实装
- 删除 M1/M2 过渡 DEPRECATED 子命令：`give` / `paint` / `placeholder`（T1 起保留至今）
- 删除 T5 demo 用的 `MapPacketSender` / `PlaceholderRenderer` 字段（FrameDeployer 内部持有，主命令路径不再直接用）

**sign_records 写入：** M2 阶段 `project_json` 存 `"{}"` 占位、`template_id` / `template_version` 为 null。M3 真正的编辑协议族会逐步填充 `project_json`。

**顺手修复 Javalin 7 staticFiles fat-jar bug（T7a TODO）：**
`web/WebServer.java` 用两条显式 `Endpoint` 替代 `cfg.staticFiles.add`：
```java
GET /                → serveClasspath("web/index.html")
GET /assets/{file}   → serveClasspath("web/assets/" + file)   // 防路径穿越检查
```
`serveClasspath` 用 `ClassLoader.getResourceAsStream` 直接流式返回；MIME 按扩展名手动映射（html / js / css / json / woff2 / svg / png）。单 jar 部署在 M2 正式可用；Vite dev 模式也仍然能跑（跨源 WS 到 8877）。

**前端 (`web/src/main.ts`) 改 auth-first 流程：**
- 页面加载时从 `window.location.search` 取 `token`
- 自动 `connect(token)` → `open → sendAuth(token)` → `onmessage` 等 `op=ready`
- `ready` 到来：`handleReady` 从 `payload.projectState.canvas.widthMaps/heightMaps` 显示"wall W×H"，enable ping / paint 按钮
- 没 token 时显示明确提示"Start via /canvas confirm in Minecraft"（而不是静默失败）
- 增加 `#status` 彩色状态条（pending/ready/err 三色）
- **token 原文不进 log**（security.md §2.2 要求）
- TypeScript 类型收窄陷阱：`instanceof` narrowing 在 `throw` 外不会传播到 outer `const`；加一组局部变量 `logEl / statusEl / pingBtn / paintBtn` 做类型假设

**`web/index.html`**：增加 `#status` 状态条，初始 `disabled` 两个按钮

**主类接入（HikariCanvas.java）：** 构造 CanvasCommand 时传 `(this, sessionManager, frameDeployer, tokenService, mapPool, database, "http://127.0.0.1:8877/?token={token}")`

**端到端链路（T12 要实测的完整流程）：**
```
MC 客户端                                       浏览器
   ├─ /canvas edit                                 
   ├─ 左键 / 右键点两角                             
   ├─ /canvas confirm                              
   │   ├─ SessionManager.confirm: SELECTING→ISSUED
   │   ├─ MapPool.reserve N 张
   │   ├─ FrameDeployer.deploy (挂框 + 填 Placeholder)
   │   ├─ TokenService.issue → token
   │   └─ 发可点击聊天消息 [Open editor: http://...?token=...]
   ├─ 点击聊天链接  ──────────────────────► GET / → index.html
   │                                       GET /assets/*.js → 加载
   │                                       JS 从 URL 取 token → WS /ws
   │                                       → send {op:auth, token}
   │                                       ← {op:ready, projectState:{wall W×H}}
   │                                       UI 变绿，按钮 enabled
   ├─ (编辑侧 M3 才做，M2 编辑器只能 ping/paint)
   └─ /canvas commit                              
       ├─ SessionManager.commit: ACTIVE→CLOSING→CLOSED
       ├─ MapPool.promoteToPermanent → refill FREE
       ├─ FrameDeployer.promote (PDC session→sign)
       └─ INSERT sign_records row
```

**未做（按 M2 范围有意留到后续）：**
- 定时回收 task（WS 断连 5min 宽限 / auth 5s 超时 / idle disconnect）—— M7 polish
- token rotate（WS 握手成功后重发新 token）—— M3 / M7
- 限流（security.md §2.4 单玩家 / 全局）—— M7
- Origin 校验（公网部署时开）—— M7
- `/canvas cleanup` 实际数据回收 + `/canvas fsck` —— M7
- `project_json` 真实内容 —— M3

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- 前端 `npm run build` 通过（index.html 2.29 KB / index-CZM-HPIQ.js 3.28 KB）
- 端到端真实闭环测试留到 T12

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`web/index.html`、`web/src/main.ts`、`docs/journal.md`

---

## 2026-04-21 · M2-T10 WS 预握手 + auth/ready 协议

**范围：** 按 `docs/protocol.md §3.1 / §3.2` 落地会话进入协议：HTTP 预握手校验 token 并返回会话元信息；WS /ws 首帧必须是 `op=auth`；`ready` 响应带 `projectState` 占位。

**改动：**
- `WebServer.java` 重大扩展：
  - 构造器签名：`(log, host, port, tokenService, sessionManager, serverVersion, paintHandler)`
  - `GET /api/session/{token}` → `tokenService.peek(token)`（不消耗），200 返回 session 元信息 / 401 AUTH_FAILED / 409 SESSION_CLOSED
  - WS auth-first 状态机：`ctx.attribute("sessionId")` 是否绑定判定阶段；未绑定时只接受 `op=auth`，其它 op → `AUTH_FAILED` + close 4001
  - `handleAuth`：`tokenService.consume(token)` → `sessionManager.markActive(sessionId)` → `ctx.attribute("sessionId", id)` → 发 `{op:"ready", payload:{sessionId, serverVersion, protocolVersion, projectState}}`
  - 已认证路径：每条消息 `sessionManager.touch(sid)` 更新 `lastActivityAt`；`ping`/`pong` 保留；`paint` demo 通道保留待 T11 删
  - `onClose`：若绑定了 sessionId，调 `sessionManager.markDisconnected` 记时间戳（T11/M7 的定时回收 task 会扫这个）
- `HikariCanvas.java`：构造 WebServer 时传入 tokenService / sessionManager / serverVersion（从 `getPluginMeta().getVersion()` 取）

**Javalin 7 API 踩坑：**
- `cfg.routes.addEndpoint(Endpoint endpoint)` 接受 `io.javalin.router.Endpoint`——**直接 `new Endpoint(HandlerType, String, Handler)`**；没有 `Endpoint.create(...).builder().build()` 链式 API
- `WsCloseStatus` 是 **enum**（NORMAL_CLOSURE / POLICY_VIOLATION 等预定义常量），**没有**自定义 4001 的 enum 常量；应该用 `ctx.closeSession(int code, String reason)` 重载直接传 `4001`
- 对应 JavalinException 规避以 `javap -public` 查真实 class 签名最快

**`ready` 响应的 `projectState` 占位结构（`protocol.md §7` 子集）：**
```json
{
  "version": 0,
  "canvas": { "widthMaps": W, "heightMaps": H, "background": "#CCCCCC" },
  "elements": [],
  "history": { "undoDepth": 0, "redoDepth": 0 }
}
```
M3 做增量编辑时会让 `projectState` 真正带元素。

**未做（留给后续）：**
- **Token rotate**（security.md §2.2 要求）：WS 握手成功后重发新 token 给前端用于断线重连。M2 不做——WS 断开后玩家只能重新 `/canvas edit` + confirm 签发新 token。留给 M3 + M7 polish
- **Auth 超时**（protocol.md §3.1 要求 5 秒）：当前用 Javalin/Jetty 默认 30 秒 idle timeout。T11 或 M7 polish 时加自定义 watchdog
- **Origin 校验**（security.md §3.1）：默认关闭；公网部署时再开，M2 只跑本地
- **per-WS-connection 限流**（security.md §2.4 + protocol.md §9）：20 msg/s / 40 突发 / 5 次拒绝 close 1008——延到 M7
- **断线重连 5min 宽限自动回收**：`markDisconnected` 已记时间戳，但定时扫描回收 task 未实装；T11 或 M7 做

**验证：** `./gradlew :plugin:compileJava` 通过。真实端到端验证要等 T11 把 `/canvas confirm` 接到 TokenService.issue 后才能跑。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T8 FrameDeployer + FrameProtectionListener

**范围：** 物品框的批量挂装 / 会话终止时移除 / commit 升级 permanent，以及保护 listener。契约 `docs/architecture.md §7.2` 与 `docs/security.md §5`。T11 命令族实装时把这些 hook 串到 SessionManager 状态转移上。

**文件：**
- `deploy/FrameDeployer.java`（新）：
  - `deploy(Session, wall, mapIds)`：按 bbox 逐格 spawn ItemFrame，`INVISIBLE + FIXED + rotation NONE + mapItem`；PDC 打 `session / slot / role=preview`；slot 编号 = `row × width + col`，`row=0` 为最上排；deploy 后立即 push Placeholder 像素**只发会话玩家**（per-viewer 同步走 M3）
  - `removeForSession(sessionId, world)`：扫世界 ItemFrame 按 PDC session 删除；cancel 路径用
  - `promote(sessionId, signId, world)`：preview → permanent，改 PDC（`sessionKey` 移除 / `signKey` 设 / `roleKey = "permanent"`），**不重建实体** = frame 保持可见
  - `isProtectedFrame(ItemFrame)`：保护 listener 判定入口（PDC 里有 session 或 sign key）
  - 4 个 NamespacedKey（`session / sign / slot / role`）全部在 `hikari_canvas` namespace 下
- `deploy/FrameProtectionListener.java`（新）：
  - `HangingBreakEvent`：实体原因（爆炸/物理失联）一律拒绝；玩家攻击由下一个 handler 处理
  - `HangingBreakByEntityEvent`：拒绝，除非破坏者是持 `canvas.admin.force-break` 权限的玩家
  - `PlayerInteractEntityEvent`：右键改内容拒绝
  - `BlockBreakEvent`：扫 4 个水平相邻格，若有 attached 在本方块的 protected frame 则取消；同样 `canvas.admin.force-break` bypass。M2 只支持垂直墙面，故只扫水平 4 方向；M4+ 放宽后补 UP/DOWN
- `HikariCanvas.java`：`onEnable` 构造 `FrameDeployer(this, new PlaceholderRenderer(), mapPacketSender)` + 注册 `FrameProtectionListener`

**关键取舍：**
- **per-viewer 同步局限**：Minecraft MapData 是 per-player 推送，不是全局广播。T8 只给会话玩家发 Placeholder；其他在线玩家看到的是 MC 客户端本地缓存（新地图会是空白 / 灰）。完整 per-viewer 差分同步是 M3 `protocol.md §state.patch` 的事
- **promote 不重建实体**：仅改 PDC `sessionKey → signKey`，物品框保持原位可见；好处是不会有"空白闪一帧"
- **slot 编号 vs 视觉方向**：按 bbox 坐标最小值递增命名（row=0 为最高、col=0 靠 minZ/minX），不对齐"玩家视角左/右"。T11 + Placeholder 对齐后可能要调；M2 demo 阶段保持一致即可
- **没写 deploy 的回滚**：若某一 slot spawn 失败，之前成功的 frame 不会自动删除；M2 demo 阶段接受这种局部损坏，T12 集成测试时再看

**与其他模块的对接清单（T11 将完成）：**
- SessionManager.confirm 成功后：调 `frameDeployer.deploy(session, wall, mapIds)`
- SessionManager.cancel 成功后：调 `frameDeployer.removeForSession(sessionId, wall.world())`
- SessionManager.commit 成功后：调 `frameDeployer.promote(sessionId, signId, world)`

**验证：**
- `./gradlew :plugin:shadowJar` 通过；Paper 1.21 API 里 `setFacingDirection(face, force=true)` / `ItemFrame#setVisible(false)` / `setFixed(true)` 全部编译通过
- 运行时验证留给 T11（T11 完整命令族串好后自然触发）或 T12 集成

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/FrameProtectionListener.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T6 Canvas Wand + SELECTING 玩家交互入口

**范围：** 玩家侧的墙面选区 UX 全部就绪。命令入口（`/canvas edit`）+ Wand 物品入口双通道、左/右键点击记 pos1/pos2、聊天栏实时 echo 坐标与墙面预览、`/canvas cancel` 放弃。此时 T7 的 SessionManager 状态机第一次有了真正的驱动源。

**文件：**
- `deploy/CanvasWand.java`（新）：
  - `Material.GOLDEN_SHOVEL` + Adventure 名称/lore
  - PDC key `hikari_canvas:wand_owner` 存玩家 UUID 字符串；`isWandFor` 校验防别人捡到误触
  - `removeAllFrom(player, plugin)` 工具方法（confirm/cancel 后把 wand 从背包收回）
- `session/WandListener.java`（新，Bukkit Listener）：
  - 只处理 `LEFT_CLICK_BLOCK` / `RIGHT_CLICK_BLOCK`；用 `getHand() == HAND` 去重避免副手触发
  - 触发条件：**玩家持 wand** 或 **玩家已在 SELECTING 态**。否则不干预，正常建筑行为不受影响
  - 触发时 `event.setCancelled(true)`——阻止左键破坏 / 右键放置
  - Wand 入口但尚未有会话时，隐式调 `SessionManager.beginSelecting` 开会话
  - 已在 ISSUED/ACTIVE/CLOSING 阶段点击 → 红字提示先 `/canvas cancel`
  - 两角设完立即跑 `preview()`，Ok 显示 "Wall: WxH (N maps), facing F. From (x,y,z) to (x',y',z'). Run /canvas confirm."，Failed 显示 "Selection invalid: REASON — detail"
  - `PlayerQuitEvent` 处理：SELECTING 态玩家掉线立即释放；ISSUED/ACTIVE 保留（WS 重连宽限走 T10）
- `command/CanvasCommand.java`：
  - 新增 `edit / wand / cancel` 三个正式子命令
  - `cancel` 调用 `CanvasWand.removeAllFrom` 一并收回 wand
  - 构造参数从 `(mapPacketSender)` 扩展为 `(plugin, mapPacketSender, sessionManager)`
- `HikariCanvas.java`：`onEnable` 注册 `WandListener`（Bukkit `PluginManager.registerEvents`），传入 plugin + sessionManager；`CanvasCommand` 构造同步加参数

**玩家交互规范（对应 architecture.md §7.1）：**

| 入口 | 前提 | 行为 |
|---|---|---|
| `/canvas edit` | 无活跃会话 | 开启 SELECTING 态，actionbar 提示 |
| 持 Canvas Wand 点击 | 任意 | 隐式 beginSelecting；已有会话时忽略 wand 作用 |
| 空手 / 任意方块点击 | 玩家已 SELECTING | 记 pos1（左键）/ pos2（右键），聊天栏 echo + preview |
| `/canvas cancel` | 任意 | 会话 cancel + wand 收回 |

**实测路径（待手动验证）：**

```
/canvas edit                       # 进 SELECTING
左键 点 墙面某方块 (10, 64, -5)    # 聊天栏："First corner (10, 64, -5) facing EAST"
右键 点 墙面另一方块 (13, 65, -5)  # 聊天栏："Second corner (...)" +
                                   #           "Wall: 3x2 (6 maps), facing EAST. From (10,64,-5)
                                   #            to (13,65,-5). Run /canvas confirm."
/canvas cancel                     # "Session cancelled (was SELECTING)."
```

然后 Wand 模式：

```
/canvas wand                       # "Received Canvas Wand."
右键 / 左键 点墙面                 # 自动进 SELECTING + 记角 + 回显
```

**留给 T11：** `/canvas confirm` 命令尚未实装——T6 的聊天栏提示里引导用户敲这条命令，但实际执行会报"Unknown command"。T11 把 SessionManager.confirm() 接到命令上即可。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/CanvasWand.java`、`plugin/src/main/java/moe/hikari/canvas/session/WandListener.java`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T7 SessionManager + 会话状态机

**范围：** 编辑会话的生命周期核心。契约 `docs/architecture.md §3`。汇合点——后续 T6 Wand 提供玩家入口、T10 WS auth 标 ACTIVE、T11 命令族调 confirm / commit / cancel。

**文件：**
- `session/SessionState.java`（新）：四个状态 `SELECTING / ISSUED / ACTIVE / CLOSING`（CLOSED 不用显式枚举——从 `byId` 中移除即 CLOSED）
- `session/WallKey.java`（新）：`(world, originX, originY, originZ, facing)` 墙面排他锁 key
- `session/Session.java`（新）：会话可变 POJO；package-private setter 只允许 SessionManager 在持锁段内修改；字段按状态分阶段生效（SELECTING：pos1/pos2/face；ISSUED+：wall/mapIds/wallKey；ACTIVE：lastActivityAt/wsDisconnectedAt）
- `session/SessionManager.java`（新，核心）：
  - 索引 `byId` / `byPlayer` / `byWall`；所有公共方法 `synchronized(this)`
  - `beginSelecting` 返回 sealed `BeginResult.{Ok, AlreadyHasSession}`；后者封装了"每玩家最多 1 会话"约束
  - `recordPos(sessionId, isFirstCorner, block, face)` + `preview()`：selecting 阶段的聊天栏回显 hook
  - `confirm` 返回 sealed `ConfirmResult.{Ok, NotReady, WallFailed, WallOccupied, PoolExhausted}`——把所有失败路径显式建模，命令层 pattern-match 后对玩家产出对应的友好消息
  - `commit` 返回 sealed `CommitResult.{Ok, NotActive}`；ACTIVE 或 ISSUED 都允许 commit（命令通道 vs WS 通道）
  - `cancel(sessionId, reason)`：幂等、任何非 CLOSING 状态可调；自动归还池 + 释放 wallKey
  - `markActive` / `touch` / `markDisconnected` 是 T10 WS 层 hook
  - `liveSessionIds()` 给 {@code MapPool.detectLeaks} 消费
- `HikariCanvas.java`：`onEnable` 构造 `WallResolver(16)` + `SessionManager(log, mapPool, wallResolver, auditLog)` 并挂在 plugin 上

**设计取舍：**
- **失败路径用 sealed interface + record 显式建模**：不用 exception。调用方（T11 命令族）能编译期穷举所有 case，避免遗漏分支
- **Session 可变 + 外部不该直接改**：Java 17+ sealed + 可见性不支持"只 package 能读"；退而用 package-private setter + 文档约束"只在 SessionManager 持锁下修改"
- **状态机无 CLOSED 枚举**：`forget(s)` 从 map 里移除 = CLOSED；减少状态判定需要
- **wallKey 即排他锁**：`byWall` 本身就是锁表；commit/cancel 时 `byWall.remove(wallKey, sessionId)` 原子释放
- **本 M2 不加定时回收 task**：WS idle 5min、auth 超时这些留给 T10/T11。理由：idle 判定需要 `lastActivityAt`，该字段只在 WS 消息到达时更新，而 T7 阶段还没有 WS 绑定；时机还未到

**未做（留给后续）：**
- 定时回收 task：T10 或 T11 加 Bukkit scheduler 周期扫 `wsDisconnectedAt > 0 && now - wsDisconnectedAt > 5min` 触发 cancel
- 权限校验：T11 在命令层做（`canvas.edit` / `canvas.commit`）；SessionManager 不做权限判断
- SignRecord 写入：T11 commit 流程里 SessionManager.commit 之后由调用方 insert 到 `sign_records` 表

**验证：**
- `./gradlew :plugin:compileJava` 通过
- SessionManager 虽已挂在 HikariCanvas 但无调用入口，runServer 启动表现与 T9 一致（空跑不崩）。真正闭环测试在 T11+T12。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/session/` 4 个新文件、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T9 PlaceholderRenderer + BitmapFont

**范围：** 按 `docs/architecture.md §4.4` 渲染 Placeholder 占位图。128×128 浅灰底 + 顶部 "HIKARICANVAS" 水印 + 底部 "N/M" 位置标签。M4 真字体接入前就靠这套。

**文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`（新）：手工定义的 5×7 ASCII 位图字表
  - 字符集仅覆盖 **H/I/K/A/R/C/N/V/S + 0-9 + "/" + 空格**（共 21 个）—— 刚够拼 "HIKARICANVAS" 水印 + 位置标签 "N/M"
  - 存储：`Map<Character, int[7]>`，每行一个 int 的低 5 位（MSB→LSB 代表从左到右 5 个像素）
  - 未知字符返回 `EMPTY` 空白（不报错），小写自动 `toUpperCase()`
- `plugin/src/main/java/moe/hikari/canvas/render/PlaceholderRenderer.java`（新）：
  - `render(slotIndex, totalSlots) → byte[128*128]`
  - 背景填色 palette 索引 33（浅色）；前景 44（深色）—— M4 调色板 LUT 就位后修正精确值
  - 顶部 "HIKARICANVAS" scale=1（12 字符 × 6 像素 = 71 px 宽，y=12 居中）
  - 底部 "N/M" scale=3（显眼大号，y=97）
  - 无状态，并发安全
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`：新增 `/canvas placeholder <slot> <total>` DEPRECATED 子命令，方便玩家**手动预览渲染效果**（T11 一起删）

**设计取舍：**
- **字体字符集极小**：contract 只说"HikariCanvas 水印"和"坐标文字"。我把"坐标文字"改成更简洁的 "N/M"（如 "2/6"）——实际 128×128 像素空间下世界坐标 `(x,y,z)→(x',y',z')` 太挤，"第几张/共几张" 反而更有用
- **全大写**："HIKARICANVAS" 比"HikariCanvas"省字形。M4 真字体接入后再换大小写混排
- **Palette 索引 33/44 是经验值**：M2 demo 够用；M4 RGB→palette LUT 建好后以 `#CCCCCC` 和 `#3A3A3A` 重选精确索引
- **位图字表用硬编码 int 数组**：不用图片资源 / PNG 解码，启动零 I/O；字符总数 21，代码不到 30 行
- **slot 从 0 开始传入，渲染时显示 +1**：代码层保留 0-based 习惯，UI 层玩家看到的是 1-based

**手动验证方式（测试命令已就绪）：**
```
/canvas give                       # 拿一张空白地图
/canvas placeholder 0 6            # 显示 "1/6"
/canvas placeholder 2 6            # 显示 "3/6"
/canvas placeholder 5 6            # 显示 "6/6"
```

**留给后续任务：**
- T8 `FrameDeployer` 会在 `/canvas confirm` 时调 `render(slot, total)` 给每张物品框填 placeholder
- M4 渲染引擎会用真 TTF 字体替换此处所有逻辑；本包 `render/` 会保留但 `PlaceholderRenderer` / `BitmapFont` 这两个类的实现完全重写

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`、`plugin/src/main/java/moe/hikari/canvas/render/PlaceholderRenderer.java`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T5 WallResolver

**范围：** 纯算法类，将玩家两次点击（pos1/pos2 + BlockFace）解析为墙面矩形 + 合法性校验；输出 `Result.Ok` 或 `Result.Failed(reason, detail)`。T6 Wand + SELECTING 会调用它做选区预览，T7 SessionManager 在 `/canvas confirm` 时复用同一份逻辑。

**设计：**
- 无状态、无副作用，构造只接收 `maxMaps` 上限（对应 `limits.canvas-max-maps` = 16 默认）
- Result 用 sealed interface + 两个 record（`Ok` / `Failed`），调用方 pattern-match
- M2 仅支持**水平四向墙**（N/S/E/W）；UP/DOWN 返回 `VERTICAL_ONLY`，M4+ 再放宽

**失败码清单（返回 `FailReason`）：**
- `NORMAL_MISMATCH` — 两次点击 BlockFace 不同（两面墙）
- `DIFFERENT_WORLDS` — 两 block 跨世界
- `VERTICAL_ONLY` — normal = UP/DOWN
- `NOT_COPLANAR` — 同 normal 但不在同一平面（X 或 Z 轴需一致）
- `TOO_LARGE` — `width × height > maxMaps`
- `BLOCK_NOT_SOLID` — bbox 内某方块非实心 full cube（排除台阶/栅栏/玻璃板）
- `OCCUPIED` — bbox 前方一格已挂 ItemFrame（扫描 `getNearbyEntitiesByType(ItemFrame.class, ...)`）

**算法要点：**
- 同平面判定：`EAST/WEST` 要求 `pos1.x == pos2.x`；`NORTH/SOUTH` 要求 `pos1.z == pos2.z`
- `width` 随法线轴决定（法线 X → width 沿 Z；法线 Z → width 沿 X）
- `isSolid() && isOccluding()` 判实心 full cube（`isSolid` 包含台阶，`isOccluding` 更严）
- ItemFrame 占用检测兼容 Paper 1.21 `getAttachedFace()` 的边界差异：既查 `attachedFace.getOpposite() == face`，又查 frame 位置是否落在 adjacent 方块内

**验证：** `./gradlew :plugin:compileJava` 通过。运行时 smoke test 留到 T6 有真实玩家交互入口后做。

**未包含：**
- 不校验墙面背后是否有"支撑"（物品框挂在固体墙上，MC 不要求背面有支撑；若 T8 发现问题再补）
- 不处理透明方块（glass）——`isOccluding=false` 会被 `BLOCK_NOT_SOLID` 拦，合理
- Maximum 尺寸以地图数计（16 默认），不再单独检查单维上限——若玩家选 1×16，算法允许

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/WallResolver.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T4 MapPool —— PROPOSAL 风险表 #1 核心机制落地

**范围：** 预览地图池状态机（FREE/RESERVED/PERMANENT）+ SQLite 同步 + 启动恢复 + 泄漏检测。**这是 M2 的技术核心**，也是整个项目防止 `idcounts.dat` 膨胀的唯一防线。

**文件：**
- `plugin/src/main/java/moe/hikari/canvas/pool/PoolState.java`（新）：三状态枚举
- `plugin/src/main/java/moe/hikari/canvas/pool/PooledMap.java`（新）：不可变 record，`withFree` / `withReserved` / `withPermanent` 原子 transition
- `plugin/src/main/java/moe/hikari/canvas/pool/PoolExhaustedException.java`（新）：容量耗尽时抛
- `plugin/src/main/java/moe/hikari/canvas/pool/MapPool.java`（新，核心）：
  - 索引：`Map<Integer, PooledMap> byId` + `Deque<Integer> freeQueue`
  - 全部公开方法 `synchronized(this)`
  - `initialize(World)`：读 `pool_maps` 表 → 校验不变式（异常行降级 FREE + 告警）→ `Bukkit.getMap(id)` 检查 MapView 仍在（丢失则 DELETE 行 + `POOL_ORPHAN_ROW` 告警）→ FREE 数量 < `initial-size` 时 expand 补齐
  - `reserve(sessionId, count)`：FREE 不够时自动 expand；expand 超 `max-size` 抛 `PoolExhaustedException`（对应 protocol.md §6.1 错误码 `POOL_EXHAUSTED`）
  - `returnToPool(sessionId)`：cancel 路径；遍历 RESERVED 且 `reservedBy==sessionId` → FREE + 重入 freeQueue
  - `promoteToPermanent(sessionId, signId, world)`：commit 路径；RESERVED → PERMANENT（从"可用"计数中抽走，但保留在 `byId`）；随后 refill FREE 到 `initial-size`
  - `detectLeaks(liveSessions)`：RESERVED 但 sessionId 不在活会话中 → 强制归还 + `POOL_LEAK` 告警
  - 所有状态转移 `persist()` 用 `INSERT ... ON CONFLICT DO UPDATE`（SQLite upsert），幂等
- `HikariCanvas.java`：onEnable 构造 `MapPool(initial=64, max=256)` 并 `initialize(Bukkit.getWorlds().get(0))`

**关键约束与设计取舍：**
- **线程安全 / 主线程约束：** `Bukkit.createMap` 与 `MapView` 相关调用必须在主线程，因此 `initialize`、`reserve`、`promoteToPermanent` 三个"可能触发 createMap"的方法**只能主线程调用**（javadoc 已标）。`detectLeaks` 只读状态和归还（不 createMap），可异步调用——给后台 leak detection 留出空间
- **PDC 标记暂不实装**：data-model.md §3.3 说"SQLite 与 PDC 不一致时以 SQLite 为权威"，M2 阶段只用 SQLite 作为单一 source of truth；MapView PDC 标记（`pool_state` / `owner` / `session_id` 等）留给 M7 打磨期增强韧性——那时再一次性跨所有状态转移打 PDC
- **initial/max 硬编码 64/256**：config.yml 接入延后；contract 已定默认值
- **不变式异常一律降级 FREE**：启动时若发现违反不变式的记录（例如 FREE 但 `reserved_by` 非空），不尝试推断原状态，**直接强制归还 FREE**，让池进入已知干净状态。数据安全 > 便利性
- **missing MapView 处理**：DB 有记录但 `Bukkit.getMap(id)` 返回 null（典型场景：世界文件丢失 / 手动删 idcounts.dat）→ 删 DB 行 + `POOL_ORPHAN_ROW` 审计；让 MapPool 退回干净状态

**实测（首次启动 2026-04-21 16:22）：**
- 日志链：`Database initialized` → `DB schema current version: 1` → `MapPool recovered 0 entries` → `MapPool growing FREE by 64 to reach initial-size=64` → `HikariCanvas enabled`
- 创建 64 张 MapView 用时约 3s
- `sqlite3 data.db "SELECT state, COUNT(*) FROM pool_maps GROUP BY state"` → `FREE|64`
- `audit_log` 里出现两条：`POOL_EXPAND` 和 `POOL_INITIALIZED`
- 下次启动（未测试，T12 集成时验证）应走 "MapPool recovered 64 entries; free=64 ..." 分支而不是再 expand

**留给后续任务：**
- Placeholder 像素填充（T9）：reserve 出来的地图目前 MapView 是空的（客户端会看到旧缓存）；T9 实现 PlaceholderRenderer 后由 T8 FrameDeployer 挂物品框时一并 push 初始像素
- Leak detection 调度（T7）：当前 `detectLeaks` 方法有了但没挂定时任务——需要 SessionManager 提供 `liveSessions: Set<String>`，T7 SessionManager 就位后再串起来
- `/canvas stats` 输出（T11）：`MapPool.stats()` 已准备好 record，等命令族实装

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/pool/`（新 4 个文件）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T3 TokenService + AuditLog 封装

**范围：** 一次性 token 签发 / 校验 / 消耗的核心服务，并带 SHA-256 审计。按 `docs/security.md §2` 落地。限流（§2.4）延后到 M2-T10 WS 握手时一起做（需要 IP 上下文）。

**改动：**
- `plugin/src/main/java/moe/hikari/canvas/storage/AuditLog.java`（新）：`audit_log` 表的薄封装；字段 `event / player_uuid / player_name / session_id / ip_hash / details(JSON)`；`details` 用 Jackson 序列化 `Map<String, Object>`；插入失败 fire-and-forget
- `plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`（新）：
  - `SecureRandom` + 32 字节 + `Base64.getUrlEncoder().withoutPadding()` = 43 字符
  - 内存 `ConcurrentHashMap<String, Record>` 主存
  - `issue(playerUuid, playerName, sessionId)` → 返回原文 token，同步 `AUTH_ISSUED` 事件入 `audit_log`（**只存 token 的 SHA-256**，永不落盘原文）
  - `peek(token)` / `consume(token)` 共用 `evaluate(..., consume=)`：长度 / base64 解码 / 不存在 / 已使用 / 过期**五重校验**，按 security.md §2.3 顺序
  - `consume` 使用 `ConcurrentHashMap.replace(k, oldV, newV)` 做原子 CAS，防并发重复消费
  - `purgeExpired()` 惰性清理，HikariCanvas 每 5 分钟异步调用一次
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：onEnable 构造 AuditLog + TokenService 并挂 5min 周期 purge task；onDisable cancel task

**设计细节与取舍：**
- **TTL 硬编码 15 min**（contract 默认）；config.yml 接入留给 M2-T10 或后续 polish 任务一并做
- **API 分 `peek` 和 `consume` 两个入口**：HTTP 预握手 `GET /api/session/:token` 只 peek 不消耗（供客户端在 WS auth 前确认会话信息）；WS `auth` 帧到达时才 consume（security.md §2.1 的"消耗后立即失效"）
- **rotate**（§2.2 的 WS 握手成功后签发新 token）没独立 API；调用方拿到 Ok 结果后自己 `issue` 一次即可——避免给 TokenService 加隐含状态
- **RejectReason 不向外透露**（security.md §2.3："失败场景统一返回 AUTH_FAILED；不向外透露具体原因，避免枚举攻击"）——内部 log 记具体原因供运维排错，WS/HTTP 响应只返回统一 401/4001
- 限流实现延后：**单玩家 10/5min 封禁 / 全局 100/min 保守模式** 需要 IP 信息，WS 握手前无从获取；T10 实现 `GET /api/session/:token` 时再在入口做限流

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- runServer 启动正常（Done 8.675s）；TokenService + 5min async purge task 挂上无异常
- `audit_log` 表仍为空（还没有业务链路触发 `issue`；M2-T10/T11 会出现真实记录）

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/storage/AuditLog.java`、`plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T2 SQLite + HikariCP + JDBI 接入 + schema v1 建表

**范围：** 按 data-model.md §2 全量建表；持久化基础设施就位，后续 T3/T4/T7 等能直接写 SQL

**依赖（当前稳定版实测）：**
- `org.xerial:sqlite-jdbc:3.53.0.0`（Gradle 冲突解析实际拿到 `3.49.1.0`——paperweight-userdev 的 paperDevBundle 传递依赖强制锁了低版本；3.49.1.0 功能相同，stable。M2 不折腾，待 M7 打磨期看是否要 force 3.53。异常栈里能看到版本号已确认）
- `com.zaxxer:HikariCP:7.0.2`
- `org.jdbi:jdbi3-core:3.52.1`
- `org.jdbi:jdbi3-sqlite:3.52.1`（注意包名是 `org.jdbi.v3.sqlite3`——末尾 `3` 是模块约定，不是版本号；不是 `sqlite`）

**踩坑记录：**
1. **JDBI sqlite 模块包名陷阱**：import 写 `org.jdbi.v3.sqlite.SQLitePlugin` 编译失败；真实路径是 `org.jdbi.v3.sqlite3`。用 `jar tf` 查 jar 内 class 一眼看出
2. **迁移脚本注释处理 bug**（运行时 crash）：
   - `V001__initial.sql` 开头有 3 行文件级注释 `-- ...`，紧接第一个 `CREATE TABLE pool_maps`
   - 原实现按 `;` split 后再 `trimmed.startsWith("--")` 跳过注释——**整个片段以 `--` 开头（实际内含 CREATE TABLE）被误跳过**
   - 结果：`CREATE TABLE pool_maps` 没跑，下一条 `CREATE INDEX ... ON pool_maps(state)` 报 `no such table`
   - 修法：loadResource 阶段**逐行剥注释**再拼接；拆分阶段只跳空串，不再判 `--`
3. **sqlite-jdbc 不支持一次 execute 多条语句**：无论用 `;` 分隔还是批处理都要自己拆。拆分时遇到数据里含 `;` 的情况会有风险（M2 schema 没有，future schema 若有字符串字面量含 `;` 要用更严谨的 SQL tokenizer）

**改动文件：**
- `plugin/build.gradle.kts`：新增 4 个 implementation 依赖（sqlite-jdbc/HikariCP/jdbi3-core/jdbi3-sqlite）
- `plugin/src/main/resources/db-migrations/V001__initial.sql`（新）：按 data-model.md §2.3~§2.6 建 `pool_maps`/`sign_records`/`audit_log`/`template_usage` 四张表 + 全部索引。不含 `schema_version`（由 Java 代码首次确保存在）
- `plugin/src/main/java/moe/hikari/canvas/storage/Database.java`（新）：HikariCP 连接池封装，最大 4 连接；SQLite 打 WAL 模式 + 外键约束；暴露 `Jdbi jdbi()`；`AutoCloseable`
- `plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`（新）：显式列表式迁移（不做 classpath 扫描，shadow jar 下不稳定）；`ensureSchemaVersionTable` 用 IF NOT EXISTS；应用每个脚本后 INSERT schema_version；逐行剥注释 + 按 `;` 拆
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：onEnable 构造 Database 并跑 MigrationRunner；onDisable 关闭

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- runServer 启动成功：`Database initialized` → `DB schema current version: 0` → `Applying migration V001 ...` → `✓ V001 applied` → `WebServer listening` → `HikariCanvas enabled` → `Done (8.165s)!`
- `sqlite3 plugin/run/plugins/HikariCanvas/data.db ".tables"` 输出全部 5 张表：`audit_log pool_maps schema_version sign_records template_usage`
- `SELECT * FROM schema_version` → `1|1776697225399`
- 4 张业务表空（新库）

**M2 剩余任务（M2-T3~T12）从这里开始都可以假设 `database.jdbi()` 可用。**

**关联文件：** `plugin/build.gradle.kts`、`plugin/src/main/resources/db-migrations/V001__initial.sql`、`plugin/src/main/java/moe/hikari/canvas/storage/Database.java`、`plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T1 代码层改名 Hc → Canvas

**范围：** 契约已改，代码层跟进：类名、根 literal、import、用户消息字串

**改动：**
- `git mv` `HcCommand.java` → `CanvasCommand.java`；类名同步；根 literal `"hc"` → `"canvas"`
- `HikariCanvas.java`：import + `new HcCommand(...)` → `new CanvasCommand(...)`
- 内部 javadoc、聊天栏消息里的 `/hc give` / `/hc paint` 全改 `/canvas give` / `/canvas paint`
- 子命令 `give` / `paint` **保留为 DEPRECATED demo**（代码注释明确标记，运行时消息前缀加 `[DEPRECATED demo]`）——M2 实施中间阶段还没有正式命令族，需要它们手动验证发包链路；T11 命令族完整实装时一起删除
- `./gradlew :plugin:shadowJar` 通过

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`（rename from `HcCommand.java`）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2 立项契约修订：命令前缀 /canvas + 墙面交互规范 + Placeholder 地图

**范围：** 进入 M2 前的契约层对齐。三件事：命令前缀从 `/hc` 全局改为 `/canvas`（含权限节点）；细化墙面交互流程（SELECTING 状态 + 两段式确认）；新增 Placeholder 地图规范。纯文档变更，代码留到 M2 任务逐步落实。

**为什么要改：**
- `/hc` 对新玩家意义不明（"hikari canvas" 的缩写不直观）；`/canvas` 一眼见义
- architecture.md 原版「锁定墙面」段只写了射线检测 + 尺寸从模板来，没定玩家如何告诉插件"就是这面墙"——M2 实施前这一空白必须填上
- M1 完成时发现：玩家从命令到真正看到编辑效果中间有一大段纯文字反馈，不直观。`/canvas confirm` 立即挂物品框 + Placeholder 能让玩家在游戏里马上看到"墙被选中了"，UX 更自然

**关键决策（讨论后拍板）：**

1. **命令前缀 /hc → /canvas，权限前缀 hc. → canvas.**
   - PDC namespace `hikari_canvas` **保持不变**（底层数据标记，不与用户直接交互，改动纯属噪声）
   - Java 类名 `HcCommand` → `CanvasCommand`（M2 实现时改）

2. **墙面交互采用命令 + Wand 双入口组合（用户选定）：**
   - `/canvas edit`：进入 SELECTING 状态，玩家空手或任何方块点击均可（零背包负担，偶尔使用者友好）
   - `/canvas wand`：发命名金铲「Canvas Wand」，持 wand 时左/右键直接交互，无需先打命令（频繁使用者友好）
   - 左键 = pos1，右键 = pos2；每次点击聊天栏 echo 坐标与预览
   - 手打 `/canvas confirm` 才真正 commit 选区（刻意保留一步手动动作，避免误点意外创建会话）
   - `/canvas confirm` 后**立即挂物品框 + 借池地图 + 填 Placeholder** → 玩家即刻看到占位网格

3. **新状态 SELECTING 加入会话状态机**（原来只有 CLOSED/ISSUED/ACTIVE/EXPIRED/CLOSING）
   - SELECTING 态**不占池、不挂物品框**——只是个"选区草稿"；任何时候可 `/canvas cancel` 或玩家掉线即释放

4. **Placeholder 地图视觉（用户选 A）：**
   - 浅灰底 + 顶部 "HikariCanvas" 水印 + 底部坐标文字
   - M4 前无字体系统，所以用**预烘焙位图 ASCII 字表**（只覆盖英文字母/数字/标点）
   - 所有会话共享同一只读像素缓冲；每张物品框叠加独立的 "位置标签"（如 "2/6"）以区分

5. **删除 /hc give demo 命令**
   - M1 阶段临时用来让玩家拿 filled_map 测试涂色的快捷命令
   - M2 有 `/canvas edit` + wand 正规流程，give 不再需要；保留会误导用户走错工作流

**改动文件：**
- `CLAUDE.md` 标识表：命令前缀 / 权限前缀
- `PROPOSAL.md`：§4.1 命令清单（补 edit/wand/confirm/cancel/commit/cleanup/stats，删 give/undo）；§5.2 UX 文字；§5.3 项目结构；§7 风险表 `/hc/` → `/canvas/`
- `docs/architecture.md`：§1.3 数据流图两段式重绘；§2.1 组件说明；§3.1 状态机增加 SELECTING 态；§3.2 状态转移表 4 条新增；§7.1 从"锁定墙面"改写为"交互与选区（两段式）"；§7.2 物品框部署补"填 Placeholder"；**新增 §4.4 Placeholder 地图规范**；旧 §4.4 健康指标改号 §4.5；若干 `/hc` 字串替换
- `docs/security.md`：§5 权限节点表全改名（新增 `canvas.wand` / `canvas.admin.force-break`）；§6 校验检查点表全改；nginx 配置示例 `/hc/` → `/canvas/`；审计命令
- `docs/data-model.md`：`/hc remove` / `/hc cleanup` / `/hc fsck` 改名（PDC namespace 保持 `hikari_canvas` 不动）
- `docs/template-spec.md`：`/hc reload templates` 改名

**journal 历史条目里的 /hc 字串**（M1-T5 / T6 / T7 / 改名条目里的 give/paint 等）**不动**——journal 是过程记录，不是契约文档；改动会伪造历史。

**M2 任务拆解（等用户最终 OK 后正式建 Task）：**
1. SQLite + HikariCP + JDBI 接入 + 建表脚本（data-model.md §2）
2. 命令 / 权限 / 类名 `HcCommand` → `CanvasCommand`（文档已改，代码跟进；顺便删 M1 的 give/paint demo 命令）
3. TokenService（内存主 + SHA256 审计；TTL 15min；单次使用 + rotate）
4. MapPool 实现（FREE/RESERVED/PERMANENT 状态机；借/还/refill/leak detection）
5. WallResolver（pos1/pos2 → bounding box → 合法性校验 + BlockFace 法线识别）
6. Canvas Wand 物品 + PlayerInteractEvent listener + SELECTING 状态机
7. SessionManager（每玩家 1 活跃 + 每墙面排他锁 + disconnect 5min 宽限）
8. FrameDeployer（挂物品框 + PDC 标记 + 保护 listener：HangingBreak/BlockBreak/PlayerInteractEntity）
9. PlaceholderRenderer（位图 ASCII 字表 + 预烘焙共享缓冲）
10. WebServer 预握手 `GET /api/session/:token` + WS `auth` 帧 + `ready` + 初步 `state.snapshot`
11. /canvas 命令族完整实装（edit/wand/confirm/cancel/commit/cleanup/stats）
12. 集成测试：完整 SELECTING → ISSUED → ACTIVE → CLOSING(commit) 一轮

**关联文件：** `CLAUDE.md`、`PROPOSAL.md`、`docs/architecture.md`、`docs/security.md`、`docs/data-model.md`、`docs/template-spec.md`、`docs/journal.md`

---

## 2026-04-20 · M1-T7a 端到端联调通过 · **M1 正式完成**

**范围：** M1 最终验收标准实装——浏览器按钮 → WebSocket → 游戏内地图像素变化。

**里程碑：** PROPOSAL §6 M1 行从「1 周」更新为「已完成（2026-04-20）」。从 M0 立项（2026-04-18）到 M1 收尾共 3 个工作日，较原估 1 周缩短。

**端到端链路（实测）：**

```
Minecraft 客户端 ─ /hc give ─▶ 插件发地图 item + MapView 给玩家
浏览器 ─ 点 Paint 按钮 ─▶ ws://127.0.0.1:8877/ws ─ {op:"paint"}
插件 WebServer.handleMessage(paint) ─▶ paintHandler.run()
HikariCanvas.paintAllHeldMapsRed ─▶ Bukkit 主线程 runTask
遍历 online players 主手 filled_map ─▶ MapPacketSender.sendFullMap
PacketEvents 发 ClientboundMapItemDataPacket ─▶ MC 客户端地图变红
```

**改动（代码层）：**

**Gradle ↔ npm 联动（`plugin/build.gradle.kts`）：**
- `installWebDeps`（Exec）：`npm install` in `web/`，`onlyIf` 判断 `node_modules` 不存在（避免每次 CI/clean 重复下载）
- `buildWeb`（Exec）：`npm run build`；声明完整 inputs（`package.json`/`package-lock.json`/`src/`/`index.html`/`vite.config.ts`/`tsconfig.json`）+ outputs `web/dist`，Gradle up-to-date 检查得以生效
- `copyWebToResources`（Copy）：把 `web/dist` 拷到 `build/generated/web-resources/web/`
- `sourceSets.main.resources.srcDir(build/generated/web-resources)` + `processResources.dependsOn(copyWebToResources)`：把前端产物自动并入 plugin 资源，shadowJar 自带包进
- **陷阱：** Gradle 9 的 Exec task `doFirst {}` 里不再可用 `exec { ... }` 闭包（"Too many arguments for 'fun exec(): Unit'"）。改成**独立 Exec task + onlyIf** 解决

**后端（`WebServer.java` / `HikariCanvas.java`）：**
- `WebServer` 构造参数加 `Runnable paintHandler`；`handleMessage` 新增 case `"paint"` → `paintHandler.run() + ack(submitted:true)`
- `HikariCanvas.paintAllHeldMapsRed()`：`Bukkit.getScheduler().runTask(this, ...)` 切主线程；遍历 `Bukkit.getOnlinePlayers()`；对主手 `filled_map` 调 `MapPacketSender.sendFullMap`
- 架构纪律 §5.2.6 继续被遵守：所有 packet 发送仍在 `MapPacketSender` 内部，HikariCanvas 主类只调 sender API

**前端（`web/` 子项目）：**
- `vite.config.ts`：dev server 端口 `5173` → `9173` + `strictPort: true`（用户本地 5173 被占；strictPort 让冲突时直接报错而不是静默降级）
- `index.html`：新增 `Paint held map → red` 按钮（红色主色调）
- `main.ts`：抽出 `send(op)` helper；新增 paint 按钮 handler；`resolveWsUrl()` 改得不依赖具体 dev 端口——只要 origin 不是 `127.0.0.1:8877` 就跨源连插件（未来再换 dev 端口无需改代码）

**TODO 留给 M7 打磨阶段：** `Javalin 7 的 cfg.staticFiles.add("/web", CLASSPATH)` 在 shadow/fat jar setup 下 directory discovery 失败，抛 `JavalinException: "... does not exist. Depending on your setup, empty folders might not get copied to classpath."`。web 资源**实际已进 jar**（`jar tf` 可见 `web/index.html` 和 `web/assets/*`）。M1-T7a 先绕开：开发期走 Vite dev + 跨源 WS。M7 单 jar 部署时改用手写 GET handler 读 classpath 资源，或 `Location.EXTERNAL` + 已知文件系统路径。WebServer 代码里已留 `TODO(M7)` 注释。

**WS idle timeout：** 实测时出现过 `WebSocketTimeoutException: Idle Timeout 30005/30000 ms` —— Jetty 12 默认 30s 无消息就断，属 Javalin/Jetty 默认行为。前端 `ensureConnected()` 已处理重连；M2 做会话/token 时可一并调大或上应用层心跳。

**实测（你自测）：**
- MC 1.21.11 客户端连 127.0.0.1 → `/hc give` 拿到空白 canvas 地图
- 浏览器 `http://127.0.0.1:9173/` → 点 Paint → 游戏内地图可视区**变红**
- server console 出现 `WS paint op: painted 1 held maps`
- 无 exception / Caused by

**里程碑总结（M0→M1）：**

| 任务 | commit | 关键成果 |
| --- | --- | --- |
| T1 Gradle 骨架 | `525ac54` | paperweight-userdev 2.0.0-beta.21 + paper-api 1.21.11 sync 通过 |
| T2 插件主类 | `404a4af` | 最小 `JavaPlugin` + `paper-plugin.yml`，jar 能 load |
| T3 runServer | `4c14fc8` | `xyz.jpenilla.run-paper` 接入；EULA 自动接受；生命周期日志两端跑通 |
| T4 Javalin + WS | `dd8097c` | Javalin 7 + Jetty 12 + Jackson + shadow 胖 jar；ws upgrade 101 握手通过 |
| T5 /hc paint + Packet | `73ce7bc` | **核心风险验证通过**：`WrapperPlayServerMapData` 发包直接改像素，游戏内地图涂红 |
| T6 前端骨架 | `8838ea5` | Vite 8.0.9 + TS 6.0.3；原生 DOM；build 产物 < 2KB |
| T7b 契约修正 | `8d497e7` | snapshot 测试台推迟到 M4 |
| T7a 端到端 | *本次* | 浏览器 → WS → 游戏内地图像素变化（**M1 验收**） |

**关联文件：** `PROPOSAL.md`（§6 M1 行状态更新）、`plugin/build.gradle.kts`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`web/index.html`、`web/src/main.ts`、`web/vite.config.ts`、`docs/journal.md`

---

## 2026-04-20 · M1-T7b 契约修正：snapshot 测试台推迟到 M4

**范围：** 纯文档变更，PROPOSAL 三处表述从"M1 就建 snapshot 测试台"改为"M4 渲染引擎立项期建立"

**触发原因：** T1~T6 完成后回头评估——M1 阶段前后端都不涉及字体/排版/效果渲染（那些在 M4），此时"像素级对比测试台"没有实际可比的内容，搭起来只是空壳基础设施，不如在 M4 两端真实渲染代码就位时一次建好。这是一次**契约回调**：立项期的判断现在看过于超前。

**我之前曾保留 "M1 就建" 的决策（见 2026-04-19 · CLAUDE.md 首版 + M1 技术选型拍板 条目的第 4 条）。**本次根据实施经验调整。

**改动：**
- `PROPOSAL.md` §5.2.1：末尾补充括号注「**M4 渲染引擎立项期同步搭建**；M1 不含」
- `PROPOSAL.md` §6 里程碑表 M4 行：补「**同步建立双端像素级 snapshot 测试台**（见 §5.2.1）」+ 产出列补「+ snapshot 基准集」
- `PROPOSAL.md` §7 风险表「浏览器 Canvas 与 Java Graphics2D 渲染不一致」应对列：`M1` → `M4`，并解释推迟理由
- `docs/rendering.md` 已有的 snapshot 相关表述（§渲染管线 / §发光 / §抖动）**不涉及时点**，无需改

**未动：**
- 其他 snapshot 相关文本（`docs/rendering.md` 里 `rendering-test/glow-*.png` 基准、Floyd-Steinberg 抖动覆盖等）都是**机制说明**而非时点说明，保留
- `docs/protocol.md` 里的 `state.snapshot` 是协议 op，与渲染 snapshot 无关

**这一改动让 M1 最终只剩 T7a（端到端联调），M1 真正的验收标准（浏览器按钮 → WS → 游戏内地图变红）得以聚焦。**

**关联文件：** `PROPOSAL.md`、`docs/journal.md`

---

## 2026-04-20 · M1-T6 前端按钮页面（原生 DOM + Vite + TypeScript 骨架）

**范围：** 在仓库里建 `web/` 子项目；一个按钮点击时打开 WebSocket 到 `ws://127.0.0.1:8877/ws` 并发 `ping`，把服务端响应渲染到页面。构建链路跑通即算完成（真正的端到端 round-trip 留给 T7）。

**立项期决策再重申**（见 PROPOSAL §5.1、CLAUDE.md 技术栈表）：
- M1~M4 **仅用原生 DOM + 原生 WebSocket API + TypeScript**
- Vue 3 / Konva / Pinia **M5 才引入**
- 目的：M1 端到端验证不需要前端框架；M5 再一次性搭 Canva 式编辑器

**技术选型（当前稳定版实测）：**
- Vite **8.0.9**（2026-03-12 release；Rolldown 作为统一 Rust 打包器替代 esbuild+Rollup，构建 10-30x 加速）
- TypeScript **6.0.3**（2026-03-23 release；基于 JS 编译器的最后一个大版本；7.0 年中转 Go）
- Node **25.2.1** / npm **11.6.2**（本机 brew 装的当前版本）
- Vite dev server bind `127.0.0.1:5173`（不监听 `0.0.0.0`——不要无意暴露到公网，同 Paper 插件一个安全默认）

**文件结构：**
- `web/package.json`：`type: module`；scripts `dev / build / preview`；devDeps `vite ^8.0.9 / typescript ^6.0.3`
- `web/vite.config.ts`：dev server 127.0.0.1:5173；`build.outDir = "dist"`；`build.target = "es2022"`
- `web/tsconfig.json`：严格模式全开（`strict / noUnusedLocals / noUnusedParameters / noImplicitReturns / noFallthroughCasesInSwitch / verbatimModuleSyntax`）；lib 包含 `DOM / DOM.Iterable`
- `web/index.html`：页面壳 + `<button id="ping-btn">` + `<div id="log">`；极简 system-ui 样式
- `web/src/main.ts`：
  - `Envelope<P>` TypeScript interface 与 `docs/protocol.md` §2 对齐（`v / op / id? / ts? / payload?`）
  - 按钮点击 → 首次连 `ws://127.0.0.1:8877/ws`（之后复用）→ 发 `{v:1, op:"ping", id:"c-<seq>", ts:Date.now()}` → 收到响应打印到 log
  - `open / message / close / error` 四个事件各有独立样式（sent/recv/err/meta）

**构建验证：**
- `npm install` 成功（16 packages，无 vulnerability）
- `npm run build`（`tsc --noEmit && vite build`）`25ms` 完成；产物：
  - `dist/index.html` 1.60 KB（gzip 0.84 KB）
  - `dist/assets/index-<hash>.js` 1.93 KB（gzip 1.02 KB）
- 这个产物体积反映了「不引任何框架」的初衷——整个前端 2KB 不到

**.gitignore 覆盖验证：**
- `node_modules/` / `dist/` 都被根 `.gitignore` 已有规则排除
- 入库的是 `package.json` / `package-lock.json` / `vite.config.ts` / `tsconfig.json` / `index.html` / `src/main.ts` 共 6 个源文件

**未做（留给 T7）：**
- Gradle ↔ npm 联动（`./gradlew build` 自动触发 `npm run build` + 产物拷到 `plugin/src/main/resources/web/`）——T7 做，届时插件 serve 静态资源 + WS 在同源
- 端到端实测（runServer + Vite dev server 同时跑、点按钮看 `pong`）——T7 做
- Jackson 回填的 `payload` 是 `{}` 空对象，前端 `recv` 只打 log 不解析 payload 结构——足够 T6

**关联文件：** `web/package.json`、`web/package-lock.json`、`web/vite.config.ts`、`web/tsconfig.json`、`web/index.html`、`web/src/main.ts`、`docs/journal.md`

---

## 2026-04-20 · M1-T5 /hc paint + PacketEvents 发包链路打通（M1 核心风险验证通过）

**范围：** 在插件里集成 PacketEvents 2.11.2，注册 Brigadier 命令 `/hc paint`，玩家主手地图被整张涂红——**PROPOSAL 风险表 #1「PacketEvents 版本升级破坏兼容」已实测无问题；#2「预览地图池机制」的前置能力（不走 MapRenderer，直接发包改像素）验证成立**

**依赖与打包调整：**
- `plugin/build.gradle.kts` 新增 codemc 仓库 `https://repo.codemc.io/repository/maven-releases/`（PacketEvents 主发行地）
- `implementation("com.github.retrooper:packetevents-spigot:2.11.2")` — shade 进胖 jar。注：这是 M1 最省事的方案，M2+ 可改为 `compileOnly` + 依赖独立 PacketEvents 插件（避免多插件 shade 冲突），届时同步更新 paper-plugin.yml 的 `dependencies.server`
- 胖 jar 体积上升到（略），新增 `com/github/retrooper/**`、`io/github/retrooper/**`

**PacketEvents 标准初始化流程：**
- `HikariCanvas.onLoad`：`PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))` + `PacketEvents.getAPI().load()`
- `HikariCanvas.onEnable`：`PacketEvents.getAPI().init()` 之后再 start WebServer / 注册命令
- `HikariCanvas.onDisable`：`PacketEvents.getAPI().terminate()`（包在 try/ignore 里，防止 init 失败时 disable 报错）

**架构纪律落地（PROPOSAL §5.2.6 / CLAUDE.md）：**
- 新增 `plugin/src/main/java/moe/hikari/canvas/deploy/MapPacketSender.java`
- 对外只有 `sendFullMap(Player, mapId, byte[128*128])` 一个方法——**所有对 `WrapperPlayServerMapData` 构造与 `sendPacket` 的调用都集中在此**。未来 PacketEvents 2.12.x（for Paper 26.x）的 wrapper 签名变动，只需要改这一个文件
- `WrapperPlayServerMapData` 构造签名（v2.11.2 实测得出）：`(int mapId, byte scale, boolean trackingPosition, boolean locked, @Nullable List<MapDecoration> decorations, int columns, int rows, int x, int z, byte @Nullable [] data)`
- PROPOSAL §5.2.5 文档示例用的是 NMS 类名 `ClientboundMapItemDataPacket(mapId, scale, locked, null, data)` 和 `MapData(...)`——与真实 PacketEvents 2.x API 不一致，待后续单独补 PR 修正（契约/实现一致性）

**命令注册（Paper 1.21 Brigadier，新格式）：**
- `HcCommand` 通过 Paper 的 `LifecycleEvents.COMMANDS` 挂 `/hc` 根节点
- `requires(src -> src.getSender() instanceof Player)` — 允许非 OP 玩家使用（M1 demo 需要）
- `/hc give`：插件代码直接 `Bukkit.createMap(world)` + 清空默认 renderer + `inventory.addItem`，让玩家无需 OP 就能拿到一张空白 canvas
- `/hc paint`：整张 128×128 填 palette index 18（红色）→ `MapPacketSender.sendFullMap`
- 测试流程无摩擦：连 127.0.0.1 → `/hc give` → `/hc paint` → 地图变红

**测试服便利性调整（`plugin/run/server.properties`）：**
- `online-mode=false`（任意用户名登录，本地测试场景）
- `gamemode=creative`（能飞、物品框任意摆）
- `spawn-protection=0`（允许在出生点附近放物品）
- 这些都是 `plugin/run/` 下的 runtime 文件，已被 `run/` gitignore 规则排除，不入仓库

**实测结论（用户自测）：**
- Paper 启动日志看到 PacketEvents banner（"build: 2.11.2"）+ `HikariCanvas enabled` + `Done (7.3s)!`
- 客户端进服、`/hc give`、`/hc paint` 一气呵成，地图**可视区域肉眼变红**
- 全程 server console 无 exception / Caused by
- `Painted map #<id> red (palette=18)` 聊天提示到位

**留给后续任务：**
- PROPOSAL §5.2.5 的代码示例要更新为真实 PacketEvents API（契约/实现一致性，T7 前或单独一次 doc PR）
- 预览地图池本身（pool borrow/return、PERMANENT 标记、SQLite）是 M2 的事；T5 只是验证了「直接发包能改像素」这个底层能力
- 调色板 LUT（RGB → palette index）属于 M4 渲染引擎，T5 用的硬编码 index 18 只是 demo

**关联文件：** `plugin/build.gradle.kts`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/MapPacketSender.java`、`plugin/src/main/java/moe/hikari/canvas/command/HcCommand.java`、`docs/journal.md`

---

## 2026-04-19 · M1-T4 Javalin HTTP + WebSocket 最小实现

**范围：** 在插件里起一个 Javalin 7 服务，`/ws` 端点接受 WebSocket 握手；消息信封遵循 `docs/protocol.md` §2；实现 `ping`→`pong`、未知 op → `error`

**技术选型踩坑与澄清：**
- paperweight-userdev 2.0 不再做 reobf 也不再负责合并 implementation 依赖 → 需要自己引 **shadow 插件**把 Javalin/Jetty/Jackson 打进胖 jar
  - 用 `com.gradleup.shadow` **9.4.1**（旧 `com.github.johnrengelman.shadow` 已 fork 为 GradleUp 新品牌）
  - `tasks.jar.enabled = false` + `shadowJar { archiveClassifier.set("") }`：让 shadowJar 独占输出名，避免与默认 jar 冲突
  - `runServer.pluginJars.from(shadowJar.flatMap { it.archiveFile })`：告诉 run-paper 用胖 jar
- Javalin **6 → 7 API 重大调整**，一路踩坑（以下是实测得出的正确签名）：
  - `app.ws(path, cfg)` 移除，Javalin 主类不再暴露路由方法
  - 配置类分裂为 `cfg.router`（RouterConfig，只含 `contextPath` 等配置字段）和 `cfg.routes`（RoutesConfig，路由注册入口）——名字近似但职责完全不同
  - 正确写法：`cfg.routes.addWsHandler(WsHandlerType.WEBSOCKET, "/ws", wsCfg -> {...})`
  - `cfg.startup.showJavalinBanner = false`（原来在根级）
  - `cfg.jsonMapper(new JavalinJackson().updateMapper(...))` 用于全局 `JsonInclude.Include.NON_NULL`（替代 POJO 上的 `@JsonInclude`）
- **Jackson 不在 Javalin 编译 classpath**（runtime 自带，compile 期不可见），需显式 `implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")`
- 教训：Javalin 7 的用户指南页面和若干博客示例仍在流传 v6 语法；以 **`javap -public` 看真实 class 文件** 是解决这类 API 对齐问题的最快路径

**改动：**
- `plugin/build.gradle.kts`：引入 `com.gradleup.shadow:9.4.1`；`implementation io.javalin:javalin:7.1.0`；`implementation com.fasterxml.jackson.core:jackson-databind:2.18.2`；`jar.enabled=false`；`shadowJar { archiveBaseName="HikariCanvas"; archiveClassifier=""; mergeServiceFiles() }`；`assemble.dependsOn(shadowJar)`；`runServer.pluginJars.from(shadowJar)`
- `plugin/src/main/java/moe/hikari/canvas/web/Envelope.java`：record 实现协议信封 `{v, op, id, ts, payload}`，提供 `of/pong/error` 工厂方法；不用 `@JsonInclude` 注解（改由 mapper 全局 `NON_NULL` 策略）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`：Javalin 7 服务封装；`start(host, port)` / `stop()`；`/ws` onConnect/onClose/onMessage/onError；`ping`→`pong`；未知 op → `error(INVALID_OP, ...)`；payload 反序列化失败 → `error(INVALID_PAYLOAD, ...)`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：`onEnable` 构造并启动 `WebServer`（`127.0.0.1:8877` 硬编码，待 config.yml）；`onDisable` 停

**验证：**
- `./gradlew :plugin:shadowJar` 成功，胖 jar 9.4 MB / 5388 entries，含 `io/javalin/**` `com/fasterxml/jackson/**` `org/eclipse/jetty/**` `moe/hikari/canvas/**` 与 `paper-plugin.yml`
- runServer 启动日志依次出现：`WebServer listening on 127.0.0.1:8877` → `HikariCanvas enabled (skeleton)` → `Done (7.017s)!`
- `curl` 手动发 WS upgrade（`Upgrade: websocket` + `Sec-WebSocket-Key`）→ 服务器响应 `HTTP/1.1 101 Switching Protocols` + 正确的 `Sec-WebSocket-Accept`，说明 Javalin 7 + Jetty 在 Paper classloader 里完整可用
- SIGTERM → `HikariCanvas disabled`；`ClosedChannelException` 是 curl 超时关连接的正常错误，不代表 bug

**留到后续任务：**
- 消息级 round-trip（`{op:"ping"}`→`{op:"pong"}`）的完整验证留给 T6（有 HTML 前端后）/T7（端到端）
- auth 帧 / 会话 token / 预握手 `GET /api/session/:token` 暂未实现——M2 阶段再做
- 依赖包没做 relocate，Paper classloader 里如与其他插件的 Javalin/Jetty 冲突再处理

**关联文件：** `plugin/build.gradle.kts`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/web/Envelope.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`docs/journal.md`

---

## 2026-04-19 · M1-T3 runServer 可用 + 插件生命周期验证

**范围：** 在本地用 `./gradlew :plugin:runServer` 起一个 Paper 测试服，验证我们的 jar 能被正确 load/enable/disable

**关键认识：** paperweight-userdev 2.0 **不再自带 `runServer` task**。通过 PaperMC 官方 test-plugin 的 `build.gradle.kts` 发现他们用 `xyz.jpenilla.run-paper` 3.0.2（同系列还有 `run-velocity` / `run-waterfall`）。引入这个插件即可。

**改动：**
- `plugin/build.gradle.kts`：
  - 新增插件 `id("xyz.jpenilla.run-paper") version "3.0.2"`
  - `runServer { minecraftVersion("1.21.11") }`
  - `doFirst` 自动写 `run/eula.txt` 为 `eula=true`（首次跑会被 Paper 初始化为 `eula=false`，卡住启动；加此 hook 后幂等、下次 `clean` 后也能自动复活）。`logger.lifecycle` 会明示"已接受 Mojang EULA"，不做无声操作。
- `.gitignore` 新增 `.claude/`（Claude Code 产物，不入仓库）

**验证（从 runServer 日志中直接引用）：**
- `[HikariCanvas] HikariCanvas enabled (skeleton)` — `onEnable` 触发
- `Done (7.959s)! For help, type "help"` — Paper 1.21.11 build #130 启动完成
- `SIGTERM` 后 `[HikariCanvas] HikariCanvas disabled` — `onDisable` 也正确触发
- Gradle 的 `BUILD FAILED`（exit 143 = 128 + 15）是我主动 kill 导致，**不代表 Paper 或插件异常**

**路径与 gitignore：**
- run-paper 默认工作目录 `plugin/run/`，已被现有 `run/` 规则 ignored（不带 `/` 前缀匹配任何深度）
- Paper server jar 由 run-paper 缓存在 `~/.gradle/caches/run-task-jars/paper/jars/1.21.11/130.jar`，不进项目目录

**关联文件：** `plugin/build.gradle.kts`、`.gitignore`、`docs/journal.md`

---

## 2026-04-19 · M1-T2 插件主类 + paper-plugin.yml

**范围：** 最小可 load 的 Paper 插件（skeleton），先把 `./gradlew build` 出 jar 的链路跑通；功能逻辑留给后续任务

**改动：**
- 新增 `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：继承 `JavaPlugin` 的骨架主类，`onEnable` / `onDisable` 只打 log
- 新增 `plugin/src/main/resources/paper-plugin.yml`（用新格式，非 `plugin.yml`）：
  - `name: HikariCanvas` / `main: moe.hikari.canvas.HikariCanvas` / `api-version: '1.21'`
  - `authors: [HaruHyacinth]` / 描述 / 仓库 URL
  - 暂不声明 `bootstrapper:` / `loader:` / `dependencies:` / `commands:`——T5 加 `/hc` 命令时回填 commands（也可能用 Brigadier API 运行时注册，不放 yml）
- `./gradlew :plugin:build` 首次 SUCCESSFUL（`3m 1s`，其中 `paperweightUserdevSetup` 约 2m 51s——一次性缓存，后续 incremental 秒级）
- jar 产物 `plugin/build/libs/HikariCanvas-0.1.0-SNAPSHOT.jar`（1.4 KB）内含 `moe/hikari/canvas/HikariCanvas.class` + `paper-plugin.yml`

**验证通过的关键事项：**
- paperweight-userdev 2.0.0-beta.21 在 Gradle 9.4.1 + Java 25 launcher 上能完成 vanilla server download / mapping remap / mache sources / paperclip patch / devBundle patches 全套 setup
- Java 21 toolchain 自动拉下来并用于编译（日志虽未显式 print 但 `options.release = 21` 生效）
- Mojang mappings 输出路径通，未触发任何 reobf 任务（符合 CLAUDE.md 架构纪律 §3）

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/resources/paper-plugin.yml`、`docs/journal.md`

---

## 2026-04-19 · M1-T1 Gradle 多模块骨架 + 本地目录错乱修复

**范围：** 搭起 Gradle 骨架并修复本地目录名与远端不一致的历史遗留

**背景与修复：** 开工前发现本地仓库真实路径是 `/Users/haru/Desktop/项目/HikariBetterText 2/`（改名时未同步目录名；" 2" 后缀疑似 Finder 冲突自动加的），而 Claude Code 环境声明的 primary dir `/Users/haru/Desktop/项目/HikariCanvas/` 是一个空壳。Gradle 骨架一度写到空壳里。已将 Gradle 文件挪回真实仓库，删除空壳，将真实仓库重命名为 `HikariCanvas`。本地路径、远端、CLAUDE.md 声明、包名终于对齐。

**改动：**
- 新增 `settings.gradle.kts`（`rootProject.name = "hikari-canvas"`；`include("plugin")`）
- 新增根 `build.gradle.kts`（`group = "moe.hikari"`；`version = "0.1.0-SNAPSHOT"`）
- 新增 `plugin/build.gradle.kts`：
  - `io.papermc.paperweight.userdev` `2.0.0-beta.21`（官方唯一支持最新版；Mojang mappings 输出）
  - Java 21 toolchain；UTF-8 编码；`options.release = 21`
  - `paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")`
  - `mavenCentral()` 为唯一仓库（PacketEvents / codemc 等仓库待 T4/T5 按需加）
  - jar `archiveBaseName = "HikariCanvas"`
- 生成 `gradlew` / `gradlew.bat` / `gradle/wrapper/`（wrapper 锁 Gradle 9.4.1）
- 本地装了 `gradle` 9.4.1（brew install）用于一次性生成 wrapper；日常使用走 `./gradlew`
- `./gradlew :plugin:dependencies --configuration compileClasspath` sync 成功，`io.papermc:mache:1.21.11+build.1` 与 Paper 完整依赖树（netty 4.2.7、brigadier 1.3.10、datafixerupper 9.0.19、fastutil 8.5.18 等）均解析

**未做（按增量原则留给后续任务）：**
- PacketEvents / Javalin / SQLite / JDBI / HikariCP / SnakeYAML 等依赖——T4/T5 按需加
- `runServer` / configuration cache / JVM 参数优化——T3 处理
- `web/` 子模块——T6 处理
- Java 源码与 `paper-plugin.yml`——T2 处理

**关联文件：** `settings.gradle.kts`、`build.gradle.kts`、`plugin/build.gradle.kts`、`gradle/wrapper/gradle-wrapper.{jar,properties}`、`gradlew`、`gradlew.bat`、`docs/journal.md`

---

## 2026-04-19 · 锁定版本基线 + 新增 26.x 升级纪律 + PROPOSAL 遗留 bug

**范围：** 版本调研确认当前稳定组合；PROPOSAL 与 CLAUDE.md 同步具体版本号与架构纪律

**背景：** 我的知识截止早于当前时间，且 MC 于 2026 起改版本命名（`YY.D.H`）。用户提示最新 26.1 不稳定，故通过 WebSearch/WebFetch 核实：Paper 稳定为 1.21.11；PacketEvents 1.21.x 专属最终版为 2.11.2；Javalin 6 已过时（7.1.0 最新）；paperweight-userdev 官方唯一支持 2.0.0-beta.21；Gradle 9.4.1。

**改动：**
- `PROPOSAL.md` §5.1 后端技术栈表：新增「版本」列并填入 Java 21 / Paper 1.21.11 / Gradle 9.4.1 / userdev 2.0.0-beta.21 / PacketEvents 2.11.2 / **Javalin 6 → 7.1.0**
- `PROPOSAL.md` 新增 §5.2.6「向 Paper 26.x 的平滑升级策略」：说明 26.1 移除 Spigot 重映射的影响；列明三条架构纪律（禁 NMS / PacketEvents 调用集中到 `MapPacketSender` / Mojang mappings 输出）；列明未来升级时需改动的文件清单
- `PROPOSAL.md` §5.3 项目结构遗留 bug：`java/moe/hikari/bettertext/` → `java/moe/hikari/canvas/`（改名那次未清干净）
- `PROPOSAL.md` §7 风险表「Paper API 版本变动」扩展表述，关联 §5.2.6 纪律
- `CLAUDE.md` 技术栈从短列表改为版本锁定表，明确每一项具体版本
- `CLAUDE.md` 新增「架构纪律（26.x 升级保障）」小节，与 PROPOSAL §5.2.6 对应

**关联文件：** `PROPOSAL.md`、`CLAUDE.md`、`docs/journal.md`

---

## 2026-04-19 · CLAUDE.md 首版 + M1 技术选型拍板

**范围：** 为仓库补充工程规范入口文件；固化 M1 前期决策

**改动：**
- 新增根目录 `CLAUDE.md`：项目标识、技术栈、契约文档清单、文档先行规则、Git 提交约定、不可越界的技术决策、里程碑
- 拍板 M1 前的几项技术选型（记录在此以便未来追溯）：
  - 前端 M1~M4 只写原生 DOM + Vite + TypeScript，Vue 3 + Konva + Pinia 推迟到 M5 引入（避免 M1 端到端验证搭冗余骨架）
  - 插件描述文件用 `paper-plugin.yml`，不用旧格式 `plugin.yml`
  - 本地测试服使用 `paperweight-userdev` 的 `./gradlew runServer`
  - M1 的双端 snapshot 测试台**按 PROPOSAL §5.2.1 / §7 原计划保留**——M1 阶段就搭起测试基础设施，即使首轮只比对固定形状/纯色

**关联文件：** `CLAUDE.md`（新）、`docs/journal.md`

---

## 2026-04-19 · 项目改名 HikariBetterText → HikariCanvas

**范围：** 全局重命名 + 新增 journal.md + .gitignore + git 初始化

**改动：**
- 全局替换文档里的项目标识：
  - `HikariBetterText` → `HikariCanvas`
  - `moe.hikari.bettertext` → `moe.hikari.canvas`
  - `hikari_better_text` (PDC 命名空间) → `hikari_canvas`
  - `HbtCommand` → `HcCommand`
  - `/hbt` (游戏内命令) → `/hc`
  - `hbt.` (权限节点前缀) → `hc.`
  - `hbt:` (PDC key 前缀) → `hc:`
  - `.hbt` (工程导出文件扩展名) → `.canvas`
- 新增本文件 `docs/journal.md`
- 新增根目录 `.gitignore`
- 初始化 git 仓库，创建 GitHub `HyacinthHaru/HikariCanvas` 公开仓库并首次推送

**关联文件：** `PROPOSAL.md`、`docs/architecture.md`、`docs/protocol.md`、`docs/rendering.md`、`docs/template-spec.md`、`docs/data-model.md`、`docs/security.md`、`docs/journal.md`（新）、`.gitignore`（新）

---

## 2026-04-19 · 立项期 6 份契约文档完成

**范围：** 所有立项期需要的设计文档一次性写完

**改动：**
- `docs/architecture.md`：系统架构总览、组件分层、编辑会话生命周期状态机、预览地图池机制（核心）、实时投影管线、双端渲染一致性原则、墙面识别、持久化分层、Web 服务层、关键非功能需求、配置骨架
- `docs/protocol.md`：浏览器 ↔ 插件 WebSocket 协议 v1；消息信封格式、连接生命周期、请求/响应模型、所有 op 类型、错误模型（应用层 error + WS close 码）、工程状态模型 TypeScript 定义、完整交互示例、限流、版本化规则
- `docs/rendering.md`：渲染五层管线（Layout / Rasterize / Composite / Quantize / Slice）、字体管理（同 TTF 双端）、排版算法、Graphics2D 与 Canvas 必设项、效果（描边/阴影/发光）实现、调色板 LUT 预生成、CIE76 距离、透明处理、双端 snapshot 测试规范与 CI 要求
- `docs/template-spec.md`：模板 YAML 格式 spec v1；顶层字段、canvas 定义、layout 三种类型、text/rect/line/icon 元素、参数系统类型表、表达式子集、实例化语义、版本兼容规则、完整示例
- `docs/data-model.md`：SQLite schema（schema_version / pool_maps / sign_records / audit_log / template_usage 五表）、PDC key 约定、`.canvas` 工程文件 zip 格式、配置字段约束、迁移策略、一致性不变式与修复
- `docs/security.md`：威胁模型 T1~T13、Token 机制与防暴力、WebSocket 安全、输入校验白名单、权限节点表、部署安全建议（nginx 配置）、审计事件清单、依赖安全、响应渠道

**关联文件：** `docs/*.md`

---

## 2026-04-19 · 网页优先方向确认 + PROPOSAL 大改

**范围：** 核心交互从「命令 + GUI」转为「网页编辑器 + 实时投影」，立项文件随之重写

**改动：**
- `PROPOSAL.md`：
  - 核心创新 3.3 改为「网页编辑器 + 实时投影」
  - v1 MVP 整体替换为「Canva 式完整编辑器 + 实时投影链路 + 预览地图池 + 命令辅助」
  - 新增 5.2 关键机制：双端渲染一致性、预览地图池、帧率策略（静止 0fps / 输入防抖 100ms + 5fps 上限 / 提交全量）、脏矩形差分、WebSocket permessage-deflate
  - 网络绑定默认 `127.0.0.1`，公网部署必须反代 + TLS
  - 技术栈加前端（Vue 3 + Vite + Konva + TypeScript）和 Javalin / PacketEvents / SQLite
  - 项目结构改多模块（`plugin/` + `web/`）
  - 里程碑重排为 M1~M7，总工期 3.5 个月
  - 风险表新增：池机制缺陷、双端一致性、公网暴露、服主端口不足等

**关联文件：** `PROPOSAL.md`

---

## 2026-04-18 · 立项初稿

**范围：** 项目从 0 到 1 的立项讨论与初稿

**改动：**
- 新建 `PROPOSAL.md` 作为立项文件，定义背景、定位、创新点、功能范围、技术栈、里程碑、风险、成功标准
- 讨论并确认定位：避开 `text_display` 的普通文字场景，主打像素风 / 艺术字 / 大招牌
- 确认三大创新点：内置渲染、模板化、分阶段交互
- 初版里程碑：M1~M6，工期约 2 个月（后续因网页优先方向调整）

**关联文件：** `PROPOSAL.md`（新）
