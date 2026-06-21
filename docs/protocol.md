# WebSocket 通信协议

**状态：** 已实装（随代码更新）· 2026-06-14
**适用范围：** 浏览器编辑器 ↔ 插件

本协议有**三个相互独立的版本号**，务必分清（详见下方「三层版本号」一节）：

| 版本号 | 当前值 | 代码出处 | 何时升 |
|---|---|---|---|
| **业务协议版本**（business protocol） | **7** | `Protocol.SUPPORTED_MIN = SUPPORTED_MAX = 7`；前端 `wsClient.ts CLIENT_V = 7` | 新增 op / 改 payload 语义 |
| **信封壳版本**（envelope schema） | **恒为 2** | `Envelope.of` 固定写 `2`；前端 `wsClient.ts ENVELOPE_V = 2` | 只有改信封字段（`v / op / id / ts / payload`）才动 |
| **ProjectState schema** | **3** | `ProjectState.PROTOCOL_VERSION = 3` | 只有 project_json schema 变化才同步 bump |

本协议定义浏览器与插件之间的消息格式、生命周期、错误处理。**前后端必须严格按此实现**；任何变更必须升级相应版本号并在此文档记录。

## 三层版本号

代码里存在三个版本号，含义不同、各自独立递增（见 `web/Protocol.java` javadoc 与 `web/src/network/wsClient.ts` 常量注释）：

1. **业务协议版本**（business protocol）= 业务 op / payload schema 的版本。client 在 auth 帧携 `client_v`，server 在 ready 帧回 `accepted_v`；不匹配 close `4002`。常量在 `Protocol.SUPPORTED_MIN / SUPPORTED_MAX`（当前都 = `7`），前端 `CLIENT_V = 7`。历史：v2（M8 图层）→ v3（0.6 时间轴）→ v4（0.7.0 脚本）→ v5（0.7.1 新触发器 + 有界循环）→ v6（0.7.3 补间 tweenBlock）→ v7（0.7.3 备选积木批 + 协议 v7）。每次都取"干净切换"（`MIN = MAX` 同步提升，不维持双轨）。
2. **信封壳版本**（envelope schema）= 消息容器格式版本，`Envelope.v` 恒为 `2`（`Envelope.of` 固定写 2，前端 `ENVELOPE_V = 2`）。改 envelope 字段才会动这个号，业务升级时**不动**。所有出帧（含 auth / ping）用它做 `v`。
3. **ProjectState schema** = `ProjectState.PROTOCOL_VERSION`，当前 `3`，序列化进 `project_json`。0.6 的 timeline 字段进了 ProjectState 故 bump 到 3；0.7 脚本**不进 ProjectState**（有意为之，见 scripting.md D7），故 v4-v7 升业务版本时此号留 3 不动。

## v1 → v2 变更总览（M8）

| 维度 | v1 | v2 |
|---|---|---|
| ProjectState 顶层 | `elements: Element[]` 扁平列表 | `layers: Layer[]` 树形 + `activeLayerId` |
| Element 字段 | x/y/w/h/rotation/visible/locked + 类型字段 | **新增** `opacity` / `blendMode` / `renderMode` |
| Canvas | widthMaps/heightMaps/background | **新增** `gridSize` / `guides[]` |
| state.patch path | `/elements/{i}/x` | `/layers/{i}/elements/{j}/x` |
| op 族 | element.* / canvas.* / wall.* / template.* / undo / redo / history.mark | **新增** layer.* + canvas.guides.* / canvas.grid；element.add 加可选 `layerId` |
| 客户端协商 | auth payload 不带版本 | auth payload **必须**带 `clientProtocolVersion: 2`；服务端遇 `< 2` 直接 reject `VERSION_MISMATCH` + close 4002 |
| v1 客户端兼容 | — | **不兼容**。v1 没正式发布，不维持双轨

---

## v2 → v3 变更总览（0.6）

时间轴编辑器（`docs/timeline.md`）给工程加时间维：`Timeline` + 关键帧（`Keyframe`）+ 缓动（`Easing`），由服务端 `AnimationTicker` 定 cadence 逐帧渲染。协议随之升 v3。

| 维度 | v2 | v3 |
|---|---|---|
| ProjectState 顶层 | `layers` / `activeLayerId` | **新增** `timelines?: Timeline[]` / `activeTimelineId?: string` |
| op 族 | element.* / layer.* / canvas.* / variable.* 等 | **新增** `timeline.*`（§5.12）+ `keyframe.*`（§5.13） |
| state.patch path | `/layers/<i>/elements/<j>/...` 等 | **新增** `/timelines/<i>/...` 与 `/timelines/<i>/tracks/<elementId>/<k>/...`（§5.2） |
| 客户端协商 | auth payload `clientProtocolVersion: 2` | auth payload **必须**带 `clientProtocolVersion: 3`；服务端遇 `< 3` 直接 reject `VERSION_MISMATCH` + close 4002 |
| v2 客户端兼容 | — | **不兼容**，取干净切换（不维持 v2 双轨） |

**为什么取干净切换、不维持 v2 双轨**（与 M8 的 v1→v2 同样处理）：前端 bundle 由插件自带分发，客户端与服务端协议版本在实际部署中永远匹配；版本协商设施（`Protocol.SUPPORTED_MIN/MAX`，M16.6 已建）只作安全校验，非用于支撑混版运行。timeline 字段在形态上虽是 nullable 加法（v2 工程读为 null = 静态画板），但若让 v2 编辑器打开含 timeline 的 v3 工程，保存时会按 v2 schema 丢弃 `timelines`（数据丢失）。故取干净切换：服务端遇 `client_v < 3` 直接 reject，避免混版导致的静默数据丢失。

> **双层版本注**（M16.6 设计，见 `web/Protocol.java`）：本次升的是 **business protocol**
> （`client_v` / `accepted_v`，校验 `Protocol.SUPPORTED_MIN/MAX = 3`）。消息壳 `Envelope.v`
> 是独立的 envelope schema version，0.6 未改信封字段（`v / op / id / ts / payload`），故仍为 `2`。

---

## v3 → v4 变更总览（0.7）

视觉运行时（`docs/scripting.md`）给墙加脚本规则（ScriptRule = 触发器 + 动作树）。协议随之升 v4。

| 维度 | v3 | v4 |
|---|---|---|
| op 族 | timeline.* / keyframe.* 等 | **新增** `script.*` 5 op（§5.14） |
| ready payload | aliases / variables / railBinding 等 | **新增** `scripts: ScriptRule[]`（本墙全部规则快照；store 未配或无墙回 `[]`） |
| state.patch path | `/timelines/...` 等 | **新增** `/scripts/<encoded ruleId>`（add=完整 rule 对象，replace 语义统一用 add；remove 幂等） |
| 客户端协商 | `clientProtocolVersion: 3` | **必须** `4`；服务端遇 `< 4` reject + close 4002 |
| v3 客户端兼容 | — | **不兼容**，干净切换（同 v2→v3 理由） |

要点：
- **脚本不进 ProjectState**（scripting.md D7）——`ProjectState.PROTOCOL_VERSION` 仍为 3 是**有意为之**：
  该常量只描述 project_json schema（v4 未改其形态），且仅作序列化输出、无导入校验。v2/v3 两次升版
  恰逢 ProjectState schema 变化才同步 bump，本次不变。
- 脚本 op **不进画布 undo/redo**（scripting.md §4.3；alias/schedule/rail 族同例）。
- `script.*` patch 推送沿用 alias 通道纪律：`StatePatch.version` 取当前 `ProjectState.version` 不写 0
  （Ultrareview 2026-05-25 #17）；一墙一活跃 session（byWall 排他锁），单 session push 等价全墙广播。

---

## v4 → v5 / v6 / v7 变更总览（0.7.1 / 0.7.3）

0.7.1 起脚本系统连续扩充，每次都干净切换业务协议版本（`Protocol.SUPPORTED_MIN = MAX` 同步提升）。**这些变更只动 Trigger / Action 的 wire 多态联合形态，不新增 op 族、不改信封壳、不改 ProjectState schema。**

| 版本 | 范围 | wire union 变化 |
|---|---|---|
| **v5**（0.7.1） | 3 个新触发器（`rightClickWall` / `playerLeaveRange` / `playerQuit`）+ 有界循环「重复 N 次」动作 | Trigger union 新增 3 种；Action union 新增 `repeat`（带 count + body） |
| **v6**（tween，0.7.3） | 补间动画包裹积木 | Action union 新增 `tweenBlock`（`durationMs` + `easing` + `body`）；契约见 `docs/scripting-tween.md` |
| **v7**（0.7.3） | 备选积木批（随机分支 / 元素置顶置底 / 变量取整 / 标题弹窗等） | Action union 扩充若干内置积木 |

> Trigger / Action 的完整 wire 多态形态（type 判别 + 扁平字段）以 `docs/scripting.md §2.2/§2.3` 及各分版设计稿（`scripting-0.7.1.md` / `scripting-0.7.2.md` / `scripting-0.7.3.md` / `scripting-tween.md`）为权威；本协议文档只记录版本号边界。

---

## 1. 传输层

| 项 | 约定 |
| --- | --- |
| 传输 | WebSocket（RFC 6455） |
| 默认路径 | `ws://127.0.0.1:8877/ws` |
| 编码 | UTF-8 JSON 文本帧 |
| 压缩 | `permessage-deflate`（必开启） |
| 心跳 | 前端每 **20s** 发应用层 `ping`（`wsClient.ts HEARTBEAT_INTERVAL_MS = 20_000`）；Jetty WS idleTimeout 设 **60s**（`WebServer.start` modifyWebSocketServletFactory），两层兜底。真正的 session 超时由 SessionReaper 负责（wsGrace 5min / idle 30min） |
| 最大消息尺寸 | **入站 WS 文本帧硬上限 64 KiB**（`factory.setMaxTextMessageSize(65536)`，M15.1 防 flood）。snapshot 由服务端出站，不受此限 |

二进制帧保留不使用。调色板像素数据走 MC 原生 map packet，不经 WS。

---

## 2. 消息信封

所有消息均为 JSON 对象，**顶层字段固定**：

```json
{
  "v": 2,
  "op": "element.update",
  "id": "c-17",
  "ts": 1713528000000,
  "payload": { ... }
}
```

| 字段 | 类型 | 方向 | 说明 |
| --- | --- | --- | --- |
| `v` | int | 双向 | **信封壳版本**，恒为 `2`（与业务协议版本 `client_v`/`accepted_v` 解耦，见「三层版本号」）。`Envelope.of` 固定写 2 |
| `op` | string | 双向 | 消息类型，见 §5 |
| `id` | string | 双向（可选） | 请求 ID，用于对应响应；客户端发起用 `"c-<序号>"`，服务器发起用 `"s-<序号>"` |
| `ts` | int | 双向（可选） | 毫秒时间戳，用于日志与延迟测量 |
| `payload` | object | 双向 | 消息负载，结构取决于 `op` |

---

## 3. 连接生命周期

### 3.1 预握手（HTTP）

在建立 WS 之前，前端先发一次 HTTP 请求确认 token：

```http
GET /api/session/:token HTTP/1.1
```

响应 200（**M15.4 P0-Web-2 起精简**）：

```json
{
  "ok": true,
  "playerName": "Steve",
  "wsUrl": "/ws"
}
```

> **M15.4 协议变更（2026-05-16）**：预握手响应从全量元数据精简到 `{ ok, playerName, wsUrl }` 三字段。理由：HTTP 响应可被同源页面 / 浏览器历史 / 代理缓存嗅探，把 `sessionId / wall / mapIds / templates / palette` 这种敏感元数据放 HTTP 等于扩大攻击面。改为 token consume 后只确认"会话存在 + WS 入口位置"，所有敏感初始化数据通过 WS `ready` 帧下发（见 §3.2）。`templates` / `palette` / `fonts` / `wall` / `mapIds` 等全部移到 `ready` payload。

响应 401：token 无效/过期（JSON `{ "error": "AUTH_FAILED" }`）。响应 409：会话已占用 / CLOSING（JSON `{ "error": "SESSION_CLOSED" }`）。

### 3.2 WS 握手

1. 打开 `wss://.../ws`（或 `ws://` 本地）
2. 客户端首帧必须发送 `auth`，**必须**携带 `client_v`（M16-P6.2 起的字段名；2026-05-16 之前别名 `clientProtocolVersion` 兼容期保留）：

```json
{ "v": 2, "op": "auth", "id": "c-0",
  "payload": { "token": "...", "client_v": 7, "clientProtocolVersion": 7 } }
```

> 前端实际同时发 `client_v`（M16-P6.2 起的主字段名）和旧别名 `clientProtocolVersion`（兼容回滚到旧 jar 的情形，见 `wsClient.ts sendAuth`）。服务端优先读 `client_v`，缺则回退读 `clientProtocolVersion`（`WebServer.handleAuth`）。
>
> 服务端收到 `client_v` 不在 `[Protocol.SUPPORTED_MIN, SUPPORTED_MAX]`（当前都 = `7`）或非数字 / 缺字段 → 立刻发 `error: VERSION_MISMATCH` + close `4002` (`CLOSE_PROTOCOL_VERSION_UNSUPPORTED`)。版本号常量集中在 `moe.hikari.canvas.web.Protocol`（M16-P6.2 引入；前后端双向校验）。**版本检查在 token consume 之前**（避免为不兼容客户端浪费一次性 token），但在 per-IP 限流之后（防绕过）。
>
> **未认证 5s 超时**（M16-P1.2）：WS 升级后未在 `network.ws-auth-timeout-seconds`（默认 5s，代码钳到 `1..60`）内收到合法 `auth` 帧 → close `4001` (`auth_timeout`)。防止恶意客户端占 WS 槽。
>
> **Origin 白名单**（M16-P1.3）：WS upgrade 时校验 `Origin` 头（`checkWsOrigin` / `isOriginAllowed`）。**放行**：① 无 Origin / `null`（同源 fetch / 非浏览器）；② `127.0.0.1:*` 与 `localhost:*`（任何端口）；③ 与服务端 `host:port` 完全相同的同源；④ `network.allowed-origins` 精确匹配（大小写敏感）。其余 → 403 + 不 upgrade。注意：与表格描述不同，回环始终放行，并非"默认空 = 不校验"。
>
> **per-IP token 限流**（2026-05-25）：auth 进校验前先做 per-IP 速率限制（`TokenRateLimiter`，默配见 config）；超限 → `error: RATE_LIMITED` + close `4429` (`CLOSE_TOKEN_RATE_LIMITED`)。client 看到 4429 应显示"请稍后再试"而非自动重连。
>
> **auth 时复查权限**（2026-05-25 #3）：token 签发后玩家可能被撤权（lp/pex/reload），故 auth 路径经主线程 hop 复查 `canvas.edit`；被撤权 → `error: PERMISSION_DENIED` + close `4003`（同 takeover 码，client 不重连）。同一 hop 顺带解析 `canvas.template.use-others` 供 ready 帧模板可见性过滤。
>
> **会话级 IP 绑定**（M16-P6.6）：首次 auth 时 `bindOrCheckIp` 把 client socket peer IP（**不解析 XFF**，避免伪造头攻击）CAS 绑定到 `Session.boundIp`；后续重连 IP 不一致 → `error: AUTH_FAILED` + close `4001`（文本 `ip_mismatch`）。

3. 服务器校验通过 → `ready`：

```json
{ "v": 2, "op": "ready", "id": "s-0",
  "payload": {
    "sessionId": "e1b2...",
    "serverVersion": "1.0.0",
    "protocolVersion": 3,
    "accepted_v": 7,
    "reconnectToken": "...",
    "projectState": { /* 见 §7；含 timelines（v3 起） */ },
    "wallId": "w-1a2b3c4d",
    "alias": "subway-test",
    "lockedAt": 1714200000000,
    "ownerUuid": "00112233-4455-6677-8899-aabbccddeeff",
    "selfUuid": "ffeeddcc-bbaa-9988-7766-554433221100",
    "templates": [ ... ],
    "variables": [
      {
        "namespace": "user:w-1a2b3c4d",
        "key": "red_score",
        "type": "NUMBER",
        "defaultValue": "0",
        "currentValue": "5",
        "updatedAt": 1684512345678,
        "ttl": 0,
        "source": "manual"
      }
    ],
    "aliases": { "user:w-1a2b3c4d/red_score": "红队分" },
    "scripts": [ /* ScriptRule[]，本墙全部规则；见 §5.14 */ ],
    "railBinding": null
  }
}
```

**ready payload 字段总表**（来源 `WebServer.handleAuth` payload 构造）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 登录态 session id |
| `serverVersion` | string | 插件版本字符串 |
| `protocolVersion` | int | = `ProjectState.PROTOCOL_VERSION`（当前 **3**）；描述 project_json schema，**不是**业务协议版本 |
| `accepted_v` | int | server 实际接受的业务协议版本（= 协商的 `client_v`，当前 **7**）。前端收到后断言 `accepted_v === CLIENT_V`，不一致则主动 close `4002`（`wsClient.handleReady`） |
| `reconnectToken` | string | auth 成功后 rotate 出的新 token，供断线重连 |
| `projectState` | object | 权威工程状态，见 §7 |
| `wallId` | string? | 仅 session 绑了 wall 时存在 |
| `alias` | string? | wall 别名，缺省略 |
| `lockedAt` | number? | = `walls.published_at`（DB 列名保留，**语义为 lock 时间戳**）；非 null = 已锁定，前端 readonly。缺省（null）略 |
| `ownerUuid` | string? | wall 作者 UUID；与 `selfUuid` 比对得 `isOwner` |
| `selfUuid` | string | 当前 session 玩家 UUID（始终下发） |
| `templates` | TemplateSpec[] | 按 caller owner + `canvas.template.use-others` 过滤后的可见模板 |
| `variables` | VariableDto[] | 当前 wall 可见的变量快照（`listVisibleToWall`，含 user/system/schedule/scoreboard/papi）；剔除 `referencedByWalls` 防泄露。wall=null 或 store 未配 → `[]` |
| `aliases` | Map<fullName, string> | 当前 wall 的变量别名（0.4.2）；wall=null 或 dao 未配 → `{}` |
| `scripts` | ScriptRule[] | 当前 wall 全部脚本规则快照（0.7.0）；wall=null 或 store 未配 → `[]` |
| `railBinding` | object? | 当前 wall 的铁路绑定 `{ wallId, lineId, stationId?, direction? }`（0.4.5）；未绑定 → `null` |

> **M16-P6.2 协议字段**：ready 携带 `accepted_v: number`（服务端实际接受的业务协议版本，= 协商的 `client_v`）。前端收到 ready 后做「accepted_v == CLIENT_V」断言，不一致则**主动 close 4002 并停止重连**（`wsClient.handleReady`，比原 console.warn 更严格）。

> **2026-05-14**：ready payload 字段 `publishedAt` 改名 `lockedAt`；新增 `ownerUuid`（wall.owner_uuid） + `selfUuid`（当前 session 玩家）让前端判 `isOwner = selfUuid === ownerUuid`。详见 CLAUDE.md `§lock-state`。

> **0.4.0-P2-F（2026-05-19）**：ready payload 新增 `variables: VariableDto[]` 字段，携带当前 wall **可见**的变量快照（0.4.0 bugfix Bug 1 起改用 `listVisibleToWall`，按 namespace 形态判定可见性，含 system/schedule/scoreboard/papi，不再依赖 byWall 倒排索引），前端无需额外 HTTP round-trip 初始化 VariableStore mirror。`VariableDto` 字段对应 `Variable` record 投影 = `{namespace, key, type, defaultValue?, currentValue?, updatedAt, ttl, source?}`（0.4.3 起 userglobal 变量另注入 `ownerUuid` / `ownerName`）；**主动剔除**内部倒排索引字段 `referencedByWalls`（防泄露 peer wallId 元数据）。`type` 走 Jackson 默认枚举 name 序列化：`"STRING" | "NUMBER" | "BOOLEAN" | "COLOR"`。`null` 字段被 `NON_NULL` inclusion 略去。

> **M6 决策（2026-05-11）**：`templates` 字段一次性全量下发，不走单独 `template.list` op。理由：5 个内置模板每个 ~1-2KB，合计 5-10KB；服主自定义模板少（v1 阶段 < 50KB），WS 单帧足够。未来若模板数量爆炸（v2 模板包生态）再切 index + on-demand `template.fetch`。

4. 失败 → `error` + WS close（见 §6 close 码）

### 3.3 稳态

客户端发送编辑 op，服务器按需回 `ack` / `state.patch` / `error`。服务器也可以主动推送 `state.patch`（例如另一端同步）。

### 3.4 断开与重连

- **客户端主动关闭**：直接关 WS（`cancel` op 当前未实装，见 §5.7）；服务端 onClose → `markDisconnected`，wall 数据保留，session 由 SessionReaper 回收（wsGrace 5min）。M5.5 起 `commit` op 废止——保存通过每次 `element.*` op 的隐式 auto-save（walls 表 UPDATE）实现，不需要客户端显式发包。
- **网络断连**：前端自动重连，5 秒、10 秒、30 秒阶梯；重新握手时复用同一 token（仍在 TTL 内）
- **服务端超时断开**：5 分钟无消息 → 踢连 + 会话进入 CLOSING
- **协议版本不匹配**：close 码 `4002`

---

## 4. 请求/响应模型

- 客户端发送带 `id` 的请求 → 服务器回响应，`id` 与请求一致
- 服务器主动推送事件使用 `s-<n>` 自增 id，客户端可选择是否回 `ack`
- 响应 op 固定为 `ack`（成功）或 `error`（失败）

```
C → S:  { op: "element.update", id: "c-17", payload: {...} }
S → C:  { op: "ack",            id: "c-17", payload: { version: 42 } }
```

失败：

```
S → C:  { op: "error", id: "c-17",
          payload: { code: "INVALID_ELEMENT", message: "...", retryable: false } }
```

---

## 5. 消息类型清单

### 5.1 系统类

| op | 方向 | 说明 |
| --- | --- | --- |
| `auth` | C→S | 首帧认证 |
| `ready` | S→C | 认证成功 + 初始状态 |
| `ack` | S→C | 通用成功响应 |
| `error` | S→C | 错误响应（见 §6） |
| `ping` / `pong` | 双向 | 应用层心跳（WS 层 ping 也用） |

### 5.2 项目状态类

| op | 方向 | 说明 |
| --- | --- | --- |
| `state.snapshot` | S→C | 完整工程状态（握手后 + undo 后） |
| `state.patch` | S→C | 增量补丁（JSON Patch RFC 6902 子集） |
| `project.load` | C→S | 载入既有 wall 进行二次编辑（M5.5 起：`/canvas open` 已经在握手前完成 load，此 op 实际可能不再需要，待 P2 实施时确认） |

**state.patch 时间轴路径（v3 新增）**：v3 起 `state.patch` 新增 `/timelines/<i>/...`（时间轴属性，如 `/timelines/0/durationMs`）与 `/timelines/<i>/tracks/<elementId>/<k>/...`（某元素第 `k` 个关键帧的字段，如 `/timelines/0/tracks/e-abc/2/timeMs`）两类路径。沿用现有 JSON Pointer 分拣，与 `/layers/<i>/elements/<j>/...` 同构（前端 `project.ts` applier 多认一段 token）；keyframe 数组的增删 = 列表项 `add` / `remove`（与现有 elements 数组 patch 完全一致）。

### 5.3 元素编辑类

所有元素编辑均通过 `element.*` 族 op。服务端是权威状态持有者，客户端发意图、服务端算结果。

v2 起：`element.add` 接受可选 `layerId`；缺省 = 落到 `activeLayerId`。所有元素 op 在 locked 层上拒 `LAYER_LOCKED`。

| op | 方向 | payload |
| --- | --- | --- |
| `element.add` | C→S | `{ type, props, after?, layerId? }`（缺 layerId 用 activeLayerId） |
| `element.update` | C→S | `{ elementId, patch }`（patch 可含 opacity / blendMode / renderMode） |
| `element.delete` | C→S | `{ elementId }` |
| `element.reorder` | C→S | `{ elementId, index, layerId? }`（在层内换位；跨层用 element.move-to-layer） |
| `element.move-to-layer` | C→S | `{ elementId, targetLayerId, index? }`（跨层移动；index 缺省 = 落底） |
| `element.transform` | C→S | `{ elementId, x?, y?, w?, h?, rotation? }` |

**M18 Live Paint 注**：油漆桶工具不引入新 op。点击空白 gap 走 `element.add type=path`（payload `props.d` = gap polygon 转的 SVG path 字符串 + `props.fill` = 当前 fill）；点击元素内部走 `element.update {patch:{fill}}`（vector-fill 决策 A）。拓扑计算完全在前端 Web Worker 跑，详见 `docs/architecture.md §16` 与 `docs/rendering.md §8.4`。

**0.8 Part B SVG 矢量导入注**：SVG 导入不引入新 op，走现有 `element.add`（server-authoritative）。前端解析一份 SVG → N 个 PathElement draft + 可选 ImageElement draft，循环发 N 条 `element.add { type, props, layerId }`；服务端逐条校验（`PathDValidator` + `parseFillRuleNullable` 等）后写入 state，各条独立可撤销。`props` 含 `fillRule`（`"nonzero"`/`"evenodd"`/缺省）字段（`ElementValidator` 0.8 Part B 起支持）。内嵌位图（`<image data:…>`）先 `POST /api/upload` 拿 `source` hash，再发 `element.add type=image`。

### 5.4 图层（v2 新增）

| op | 方向 | payload | 说明 |
| --- | --- | --- | --- |
| `layer.create` | C→S | `{ name?, afterLayerId? }` | 新建空层，落到 afterLayerId 之上；无该参数 → 顶端 |
| `layer.delete` | C→S | `{ layerId }` | 删除层及层内 element。至少保留 1 层；删最后一层报 `INVALID_OP` |
| `layer.update` | C→S | `{ layerId, patch: {name?,visible?,locked?,opacity?,blendMode?} }` | 部分更新 |
| `layer.reorder` | C→S | `{ layerId, index }` | 调整层间 z-order |
| `layer.duplicate` | C→S | `{ layerId }` | 复制层（含所有 element，新 uuid） |
| `layer.set-active` | C→S | `{ layerId }` | 仅更新 activeLayerId；不进 undo 栈 |

### 5.5 画布、网格、参考线、模板

| op | 方向 | payload |
| --- | --- | --- |
| `canvas.resize` | C→S | `{ widthMaps, heightMaps }` (前提：池有容量) |
| `canvas.background` | C→S | `{ fill: Fill }` 推荐 / `{ color: "#RRGGBB[AA]" }` 兼容 — 见下方 schema |
| `canvas.grid` | C→S | `{ size: int }`（0 = 关闭网格） |
| `canvas.guides.set` | C→S | `{ guides: [{ axis, position }, ...] }`（整组替换；前端拖动期不发，松手 batch 发） |
| `canvas.tweenFps` | C→S | 0.7.3：设置本 wall 补间动画帧率（per-wall）；走 editOpDispatcher 路径 |
| `template.apply` | C→S | `{ templateId, params }` （会清空所有层 + 用 Default Layer 包结果） |

**`canvas.background` payload schema（M17 升级）：**

```jsonc
// 新格式（推荐，支持渐变）
{ "fill": Fill }

// 兼容旧格式（仅支持纯色 hex）
{ "color": "#RRGGBB[AA]" }
```

- **优先识别 `fill` 字段**，缺则降级读 `color`（包成 SolidFill 内部表示）
- **两者都缺** → `INVALID_PAYLOAD`
- `Fill` 联合类型 schema 复用 element fill（M11 引入）：`SolidFill { type: "solid", color }` / `LinearFill { type: "linear", stops, angle }` / `RadialFill { type: "radial", stops, cx, cy, r }`。完整字段见 `state/Fill.java` + `web/src/types/protocol.ts`
- 渐变背景的 bbox = 整画布；CanvasCompositor 通过 `FillPaintBuilder.fillToPaint(canvas.background(), 0, 0, w, h)` 渲染
- 持久化兼容：`ProjectState.Canvas.background` 反序列化时 `FillDeserializer` 自动把字符串 `"#xxx"` wrap 成 SolidFill；旧 .canvas 文件与 fixture 0 漂移

### 5.6 历史类

| op | 方向 | payload |
| --- | --- | --- |
| `undo` | C→S | `{}` |
| `redo` | C→S | `{}` |
| `history.mark` | C→S | `{ label }` 打一个可命名的历史点 |

### 5.7 会话终结

| op | 方向 | payload |
| --- | --- | --- |
| `cancel` | C→S | **⚠️ 未实装**：`WebServer.handleMessage` switch 无 `cancel` 分支（发了会回 `INVALID_OP`），前端也不发。实际"关闭浏览器"路径靠直接断开 WS（onClose → `markDisconnected`）+ SessionReaper 回收。规划语义为：`{}` - 服务器回 `ack` 后关闭 session（wall 数据保留） |
| `wall.lock` | C→S | `{}` - **owner-only**：caller UUID == wall.owner_uuid 才接受；UPDATE walls.published_at=now（DB 列名保留，语义为 lock 时间戳）；返回 `ack { lockedAt }`；非 owner 返 `FORBIDDEN`；session 不关闭。**2026-05-14 引入** |
| `wall.unlock` | C→S | `{}` - **owner-only**：UPDATE walls.published_at=NULL；返回 `ack { lockedAt: null }`；非 owner 返 `FORBIDDEN`；session 不关闭 |
| `wall.alias` | C→S | `{ "alias": "shop-a" }` - 设别名；不符合 `[A-Za-z0-9_-]{2,32}` 返 `INVALID_ALIAS_FORMAT`；冲突返 `ALIAS_TAKEN`；session 不关闭 |
| `wall.refresh` | C→S | `{}` - 玩家撸掉支撑方块 / 画框时手动触发；切回主线程跑 `FrameDeployer.repairFor`（补方块 + 补 spawn 缺失画框）后整画布脏矩形 reprojection；`ack { framesRespawned, framesReAttached, wallBlocksReplaced }` |

> M5.5 起 `commit` op 废止。`wall.*` 系列是 wall 元数据修改，与编辑 op 解耦——不影响 session 生命周期。
>
> **2026-05-14**：`wall.publish` / `wall.unpublish` 砍，新 `wall.lock` / `wall.unlock`。lock 是 UX 层概念，**后端编辑 op（element.* / canvas.* / layer.*）路径与 lock 状态完全解耦**——锁定的 wall 仍能接受编辑 op（动态展示场景需要），前端 readonly UI 是 lock 唯一的执行者。`/canvas publish` / `/canvas unpublish` 命令同时砍。

### 5.9 笔刷流（M12 实施）

笔刷 op 走 `BrushOpDispatcher` 独立路径，**不走** edit 路径的 rateLimiter（brush.point 高频低消息，限流会卡笔触流畅性）；内存安全靠 `MAX_BRUSH_POINTS_PER_STROKE` + `MAX_ACTIVE_STROKES` 双闸门。

| op | 方向 | payload | 响应 |
| --- | --- | --- | --- |
| `brush.start` | C→S | `{ layerId?, props: BrushProps }` `BrushProps = { color: "#RRGGBB[AA]", size: number, opacity: 0..1, smoothing?: 0..1, taper?: bool, hardness?: 0..1 }` | `ack { strokeId }` / `error TOO_MANY_STROKES / LAYER_LOCKED` |
| `brush.point` | C→S | `{ strokeId, points: [[x, y, pressure, t], ...] }` 批量点，pressure 0..1，t 是相对 stroke.start 的 ms | **无 ack**（高频）；服务端立即更 dirty bbox 推 MC packet；点数超限拒 `STROKE_TOO_LONG` |
| `brush.end` | C→S | `{ strokeId }` | `ack { version }` + `state.patch`（固化为 `BrushStrokeElement` 写入 layer，附带 RDP 简化 + Catmull-Rom 平滑）；`INVALID_STROKE` 若 strokeId 不存在 |
| `brush.cancel` | C→S | `{ strokeId }` | `ack {}`；丢弃 stroke 不持久化；空 patch |

> 限制：`MAX_ACTIVE_STROKES` = 8（同 session 同时活跃 stroke 上限）；`MAX_BRUSH_POINTS_PER_STROKE` = 4096（单 stroke 累计点数硬上限）。超出返 `TOO_MANY_STROKES` / `STROKE_TOO_LONG`，前端 UI 应拦截不该发到这一层。

### 5.10 模板创意工坊（M14 引入）

玩家可发布 / 删除 / 推荐自己的模板。走 `TemplateOpDispatcher`。权限节点：
- `canvas.template.save`（发布）
- `canvas.template.delete.own`（删自己） / `canvas.template.delete.any`（管理员删任意）
- `canvas.template.feature`（推荐 / 取消推荐，管理员）
- `canvas.template.bypass-limit`（绕过 per-player 模板数配额）

| op | 方向 | payload | 响应 |
| --- | --- | --- | --- |
| `template.save` | C→S | `{ slug: string, displayName: string, description?: string, paramConfig?: { textActions: { <autoId>: { action: "keep"\|"prompt", name?, label?, description? } } } }` 当前 session 的 `projectState` 作为模板内容 | `ack { templateId }` / `error QUOTA_EXCEEDED / WRITE_FAILED / DB_FAILED / FORBIDDEN` |
| `template.delete` | C→S | `{ templateId }` | `ack { templateId }` / `error NOT_FOUND / FORBIDDEN / DB_FAILED` |
| `template.feature` | C→S | `{ templateId }` | `ack { templateId, featured: true }` / `error NOT_FOUND / FORBIDDEN / DB_FAILED` |
| `template.unfeature` | C→S | `{ templateId }` | `ack { templateId, featured: false }` / `error NOT_FOUND / FORBIDDEN / DB_FAILED` |

### 5.11 变量系统（0.4.0 规划，详见 `docs/dynamic-data.md`）

| op | 方向 | payload | 行为 |
| --- | --- | --- | --- |
| `variable.create` | C→S | `{ name, type, defaultValue?, scope? }` | 玩家创建用户变量。`scope='wall'`（默认）→ 加 `user/` 前缀（per-wall）；`scope='global'`（0.4.3）→ `userglobal/<name>`（全服共享）。`ack { fullName }` / `error VARIABLE_EXISTS / INVALID_PAYLOAD` |
| `variable.update` | C→S | `{ fullName, patch: { type?, defaultValue? } }` | 改类型 / default；`ack` / `error VARIABLE_NOT_FOUND` |
| `variable.set` | C→S | `{ fullName, value }` | 手动设当前值（仅 user/* 与 userglobal/*）；`ack` / `error VARIABLE_TYPE_MISMATCH` |
| `variable.bind` | C→S | `{ fullName, boundTo: pluginName \| null }` | 让 user/* 变量被插件 push 接管 |
| `variable.delete` | C→S | `{ fullName }` | 删除 user/* 变量；引用该变量的 element 显示 fallback |

state.patch 扩展：variables 变更走相同 `state.patch` 通道，path 形如 `/variables/<encoded-fullName>/currentValue`（整节点 add/replace/remove 也支持；前端 `applyVariablePatches` 路由）。`userglobal/*` 的变更经 `SessionManager.broadcastVariableChangeToAll` 广播到所有 session。

> **0.4.3 全局用户变量**：`variable.create scope='global'` 与对 `userglobal/*` 的 update/set/delete/bind 走同一组 op，但 dispatcher 按 `fullName.startsWith("userglobal/")` 分流到 owner-only + admin override 权限节点（`canvas.var.global.*`）。`userglobal` 在 `PluginNamespaceRegistry.RESERVED_NAMESPACES` 中，外部插件禁推。

### 5.11.1 变量别名（0.4.2）

全 namespace 通用、per-wall 隔离。走 `VariableAliasDispatcher`，复用 `canvas.var.write.own/any` 权限（list 只读放行）。

| op | 方向 | payload | 行为 |
| --- | --- | --- | --- |
| `variable.alias.set` | C→S | `{ fullName, alias }` | 给 fullName 起别名（覆盖已有），alias 1..64 字符；推 `state.patch` 的 `/aliases/<encoded fullName>` |
| `variable.alias.clear` | C→S | `{ fullName }` | 清掉别名（不存在也幂等）；推 `remove /aliases/<encoded>` |
| `variable.alias.list` | C→S | `{}` | 只读返当前 wall 全部别名 |

> 别名仅在 UI 层展示（picker / panel / chip），**不参与 `${var:...}` 解析**。ready payload 的 `aliases` 字段一次性初始化前端 `VariableAliasStore` mirror。

### 5.11.2 时刻表（0.4.0-P3-L，ManualScheduleProvider）

走 `ScheduleOpDispatcher`；per-wall。

| op | 方向 | payload | 行为 |
| --- | --- | --- | --- |
| `schedule.list` | C→S | `{}` | 返当前 wall 完整时刻表 `{ schedule }`（含 0.4.5 railBinding） |
| `schedule.upsert` | C→S | `{ stationName, precision? }` | 创建/更新元数据（站名 + 时间精度 minute/second，0.4.0 bugfix Bug 4）；precision 不传时保留现值 |
| `schedule.entry.add` | C→S | `{ departureTime, destination, sortOrder }` | 添加条目，返回生成的 id |
| `schedule.entry.update` | C→S | `{ id, departureTime, destination, sortOrder }` | 按 id 改条目 |
| `schedule.entry.delete` | C→S | `{ id }` | 按 id 删条目 |

### 5.11.3 铁路网络（0.4.4 + 0.4.5）

走 `RailOpDispatcher`；线路级 owner ACL（`canvas.rail.*` 6 权限节点）。共 13 op（0.4.4 的 12 + 0.4.5 新增 `rail.line.detail`）。

| op | 方向 | payload | 行为 |
| --- | --- | --- | --- |
| `rail.line.list` | C→S | `{}` | 列所有线路（含 owner / color 元数据） |
| `rail.line.detail` | C→S | `{ lineId }` | 0.4.5：聚合查 stations + runs + `timetableByRun`（避免 N+1） |
| `rail.line.create` | C→S | `{ name, code?, color? }` | 返 `{ lineId, line }` |
| `rail.line.update` | C→S | `{ lineId, name?/code?/color? }` | |
| `rail.line.delete` | C→S | `{ lineId }` | |
| `rail.station.add` | C→S | `{ lineId, name, code?, sortOrder?, isTerminus }` | 返 `{ stationId, station }` |
| `rail.station.update` | C→S | `{ stationId, ...patch }` | |
| `rail.station.delete` | C→S | `{ stationId }` | |
| `rail.run.create` | C→S | `{ lineId, runNumber, direction, serviceType, cars?, startStationId?, endStationId?, notes?, generateOptions? }` | 返 `{ runId, run }`；`generateOptions` 触发自动时刻表生成 |
| `rail.run.update` | C→S | `{ runId, ...patch }` | |
| `rail.run.delete` | C→S | `{ runId }` | |
| `rail.run.timetable.set` | C→S | `{ runId, entries: [{ stationId, arrival?, departure?, stopsHere }] }` | 整表替换，返 `{ rows }` |
| `rail.wall.bind` | C→S | `{ wallId?, lineId?, stationId?, direction? }` | 绑定当前 wall 到线路+站+方向（wallId 缺省由后端注入） |

> 铁路网络的协议细节以 `docs/dynamic-data.md §18.7` 为权威。

### 5.12 时间轴（v3 新增）

时间轴的增删改走 `timeline.*` op，落 DB（序列化进 `project_json`，见 `docs/data-model.md` v3）+ 进 history。播放控制（`play/pause/seek`）单独一档，**不落 DB、不进 history**——它只在真实部署 wall 上启停 / 定位后端 `AnimationTicker`。

| op | 方向 | payload | 说明 |
| --- | --- | --- | --- |
| `timeline.create` | C→S | `{ name, durationMs, fps, loopMode, trigger }` | 新建时间轴；`ack { version }`，新 timeline（含 id）经 `state.patch` 的 `add /timelines/<i>` 下发（同 `element.add` 范式）。`fps` 受 config `timeline.max-fps` 钳（默 60），超出按上限截断 |
| `timeline.update` | C→S | `{ timelineId, patch: { name?, durationMs?, fps?, loopMode?, trigger? } }` | 部分更新；`fps` 同样受 max-fps 钳；任何字段显式传 `null` 拒 `INVALID_PAYLOAD`（不支持「null = 清字段」语义，含 `trigger`）；`durationMs` 缩短到低于现有关键帧时刻拒 `INVALID_KEYFRAME_TIME`；`error TIMELINE_NOT_FOUND` |
| `timeline.delete` | C→S | `{ timelineId }` | 删除时间轴及其 tracks；`error TIMELINE_NOT_FOUND` |
| `timeline.play` | C→S | `{ timelineId? }` | 在部署 wall 上启动后端 Ticker；`timelineId` 缺省时后端用 `activeTimelineId`（前端不带该键）；**不落 DB、不进 history**；`ack` |
| `timeline.pause` | C→S | `{}` | 停止后端 Ticker；payload 无参；**不落 DB、不进 history**；`ack` |
| `timeline.seek` | C→S | `{ atMs, timelineId? }` | 定位后端 Ticker 到 `atMs`（前端始终携带；`timelineId` 缺省用 `activeTimelineId`）；**不落 DB、不进 history**；`ack` |

> 编辑器内 scrubber 拖动的本地预览是**纯前端、不发 WS**（60fps 跟手，见 `docs/timeline.md §6.3`）。`timeline.play/pause/seek` 仅用于操控真实部署 wall 上的后端 Ticker；游戏内最终输出永远以后端为权威（`docs/timeline.md` D9）。

### 5.13 关键帧（v3 新增）

关键帧是高频编辑（一次拖动一秒几十条），仿 `element.*` 的 ack 模型走专用 `keyframe.*` op（`docs/timeline.md` D6）。

| op | 方向 | payload | 说明 |
| --- | --- | --- | --- |
| `keyframe.add` | C→S | `{ timelineId, elementId, property, timeMs, value, easing, coalesceKey? }` | 在指定元素属性轨上加关键帧；`ack { version }`，新 keyframe（含 id）经 `state.patch` 下发（同 `element.add` 范式）；`error TIMELINE_NOT_FOUND / INVALID_ELEMENT`（elementId 不存在）`/ INVALID_KEYFRAME_TIME / INVALID_EASING / INVALID_PAYLOAD`（property 不在白名单 / 配额超限 / 值类型不匹配） |
| `keyframe.update` | C→S | `{ timelineId, keyframeId, patch: { timeMs?, value?, easing? }, coalesceKey? }` | 部分更新；`error KEYFRAME_NOT_FOUND / INVALID_KEYFRAME_TIME / INVALID_EASING` |
| `keyframe.delete` | C→S | `{ timelineId, keyframeId }` | 删除关键帧；`error KEYFRAME_NOT_FOUND` |
| `keyframe.move` | C→S | `{ timelineId, keyframeId, timeMs, coalesceKey? }` | 仅挪时刻的高频快捷；拖动期前端本地 mutate，`dragend` 发一条；`error KEYFRAME_NOT_FOUND / INVALID_KEYFRAME_TIME` |

> `property` 取值集合（`x`/`y`/`w`/`h`/`rotation`/`opacity`/`color`/`fill`/`text` 等）与其插值类别（数值 / 颜色 / 离散）见 `docs/rendering.md §9.2`；`easing` 结构（`type` + 可选 `bezier` 控制点）见 `docs/rendering.md §9.3`。
>
> **`coalesceKey`（可选，0.6 P4.5b 新增）**：一个用户动作映射到多条 `keyframe.*` op 时（"整体帧"——拉就设 / 加帧 / 整体块拖动一次性写元素全部 transform 属性）让它们共享一个撤销步。同 `coalesceKey` 的连续 op 在历史栈按 `commitHistoryCoalesced` 合并为一步（窗口 500ms，见 `timeline.md §7.2`），一次撤销整组回收。**缺省（不传）= 保持单帧粒度**（`add` 各自一步；`update`/`move` 按默认键 `{elementId}:{keyframeId}:{property}` 合并连续拖动），向后兼容。
>
> 撤销侧 keyframe 连续拖动会按 coalesce key 合并（`docs/timeline.md §7`），协议层无需感知——前端在 `dragend` 才发终值一条 op，服务端按常规处理。

### 5.14 墙脚本（v4 新增；契约 `docs/scripting.md`）

| op | 方向 | payload | 说明 |
| --- | --- | --- | --- |
| `script.create` | C→S | `{ rule: { name, enabled?, trigger, actions, blockLayout? } }` | id / wallId 服务端权威（客户端给了也覆写）；enabled 缺省 true（**存在但非布尔 → INVALID_PAYLOAD**，不静默纠正）；blockLayout 缺省 `"{}"`。ack `{ rule }`（含服务端 id）+ patch `add /scripts/<id>` |
| `script.update` | C→S | `{ ruleId, rule: {...同上全量} }` | 全量替换（积木编辑粒度太碎不做 patch op）；ruleId 必须属本 session 的 wall（跨墙 → `SCRIPT_NOT_FOUND`）。ack `{ rule }` + patch add |
| `script.delete` | C→S | `{ ruleId }` | ack `{ ruleId, removed }`；不存在也推 `remove /scripts/<id>` 幂等 |
| `script.enable` | C→S | `{ ruleId, enabled }` | 启停开关。ack `{ rule }` + patch add |
| `script.test` | C→S | `{ ruleId }` | **0.7.0-P3 起异步**（K11）：试跑即真实执行（D5）——过 sound/command 面权限 + audit `SCRIPT_TEST` + Budget 全闸（K12：TEST 不豁免）；ack **立即**返 `{ accepted: true, ruleId }`（受理回执，不等执行——合法规则可串 wait 至分钟级）；轨迹在 run 结束后经 `script.trace` 推送。引擎未装配恒回 `SCRIPT_ENGINE_UNAVAILABLE` |
| `script.trace` | S→C | `{ ruleId, steps: [{ blockId, kind, result, detail? }] }` | `script.test` 的异步执行轨迹，推给发起 session（session 断了静默丢，不补发）。run 最终段结束（含 wait 续接）/ Budget 掐断 / 投递闸拒（blocked trigger step）都恰推一次。`blockId` = 动作树路径（`trigger` / `actions/0` / `actions/2/then/1`）；`kind` ∈ trigger\|condition\|action；`result` ∈ ok\|skipped\|blocked\|error；`detail` 为 null 时省略 |

权限（保存时检查，执行时不查——scripting.md §4.1）：5 op 恒查 `canvas.script.edit`
（default true；在线被显式收回**真拒**，仅离线/解析失败走 default 兜底）；create/update/test
按规则内容加查面节点——全服事件帽子 → `canvas.script.trigger.global`、播声音 →
`canvas.script.sound`、执行命令 → `canvas.script.command`（默 op）。**不读 wall lock**
（lock-state 纪律）。脚本 op 不进画布 undo/redo（§4.3）。

create/update 校验链（0.7.0-P3 起，K16）：`ScriptRuleValidator` 结构校验后，所有
`if.condition` 过 `ConditionEvaluator.checkSyntax`（parse-only）预检——坏条件保存时即
`SCRIPT_INVALID`（错误信息首行 + blockId 定位），不等运行期静默 false。

`trigger` / `actions` 的 wire 多态形态（type 判别 + 扁平字段、if 的 then/else 递归、
playTimeline.seekMs 仅 seek 携带等）以 `docs/scripting.md §2.2/§2.3` 为权威。

### 5.8 服务端主动推送

| op | 方向 | 说明 |
| --- | --- | --- |
| `state.snapshot` / `state.patch` | S→C | 状态推送（见 §5.2，实装的主力 S→C 通道） |
| `script.trace` | S→C | 0.7.0-P3：`script.test` 的异步执行轨迹（**已实装**；详见 §5.14） |
| `session.warning` | S→C | **未实装**（规划：非致命警告，如池即将耗尽、限流） |
| `session.terminated` | S→C | **未实装**（规划：服务端强制结束）。实际强制断开走 close code（4001/4003）而非此 op |
| `variable.changed` | S→C | **未实装**（规划：变量值变化通知）。实际变量变化走 `state.patch /variables/*` 通道 |

---

## 6. 错误模型

### 6.1 应用层错误（`op: "error"`）

```json
{
  "v": 2, "op": "error", "id": "c-17",
  "payload": {
    "code": "INVALID_ELEMENT",
    "message": "text box width must be > 0",
    "retryable": false,
    "details": { "elementId": "e-3", "field": "w" }
  }
}
```

**错误码表：**

| code | 说明 | retryable |
| --- | --- | --- |
| `AUTH_FAILED` | token 无效/过期；M16-P6.6 会话 IP 绑定不一致也用此码 | ❌ |
| `UNAUTHORIZED` | M16-P1.1：`GET /api/upload/{hash}?session=...` 缺 sessionId query 或 sessionId 不匹配活跃 session（HTTP 401） | ❌ |
| `VERSION_MISMATCH` | 协议版本不兼容（含 `client_v` 缺 / 超出 [SUPPORTED_MIN, SUPPORTED_MAX]） | ❌ |
| `QUOTA_EXCEEDED_DISK` | M16-P2.1：上传时插件 uploads 总字节超 `images.max-total-storage-mb` 且 LRU 无可回收行（与 `QUOTA_DISK_FULL` 同语义，M16 起统一新码） | ❌ |
| `RATE_LIMITED` | 超过限流阈值 | ✅ |
| `POOL_EXHAUSTED` | 预览池耗尽，resize 失败 | ✅ |
| `INVALID_OP` | 未知 op | ❌ |
| `INVALID_PAYLOAD` | payload 校验失败 | ❌ |
| `INVALID_ELEMENT` | 元素 id 不存在或属性非法 | ❌ |
| `INVALID_ALIAS_FORMAT` | wall.alias 不满足 `[A-Za-z0-9_-]{2,32}`（M11） | ❌ |
| `PERMISSION_DENIED` | 权限不足 | ❌ |
| `FORBIDDEN` | M15.3 鉴权方案 C：lock-aware open / template.* / wall.lock/unlock 非 owner（或缺管理员 bypass）；与 PERMISSION_DENIED 区别是基于运行期身份（owner_uuid / lock 状态）而非静态权限节点。0.8-A 起 `POST /api/project/import` 缺 `canvas.edit`（含玩家离线 fail-closed）也用此码（HTTP 403） | ❌ |
| `SESSION_CLOSED` | 会话已关闭 | ❌ |
| `ALIAS_TAKEN` | wall.alias 已被其他 wall 占用 | ❌ |
| `WALL_NOT_FOUND` | wall.* op 但当前 session 没绑定 wall（不应发生） | ❌ |
| `LAYER_LOCKED` | element.* op 命中 locked 层（M8 layer.locked=true） | ❌ |
| `LAYER_NOT_FOUND` | layer.* op 指向不存在的 layerId | ❌ |
| `LAST_LAYER` | layer.delete 试图删最后一层 | ❌ |
| `TOO_MANY_STROKES` | M12 brush：active stroke 数超 `MAX_ACTIVE_STROKES`（默认 8） | ❌ |
| `INVALID_STROKE` | M12 brush：strokeId 不存在 / 已 end / 已 cancel | ❌ |
| `STROKE_TOO_LONG` | M12 brush：单 stroke 点数超 `MAX_BRUSH_POINTS_PER_STROKE`（默认 4096） | ❌ |
| `VARIABLE_NOT_FOUND` | 0.4.0：variable.* op 指向不存在的 fullName | ❌ |
| `VARIABLE_EXISTS` | 0.4.0：variable.create 同名已存在 | ❌ |
| `VARIABLE_TYPE_MISMATCH` | 0.4.0：variable.set 值与声明 type 不符 | ❌ |
| `VARIABLE_NAMESPACE_DENIED` | 0.4.0：HikariCanvasAPI.setVariable 试图推非注册 namespace | ❌ |
| `TIMELINE_NOT_FOUND` | 0.6：timeline.* / keyframe.* op 指向不存在的 timelineId | ❌ |
| `KEYFRAME_NOT_FOUND` | 0.6：keyframe.update / delete / move 指向不存在的 keyframeId | ❌ |
| `INVALID_EASING` | 0.6：cubicBezier 控制点 x 越界 `[0,1]`，或缺 bezier 字段 | ❌ |
| `INVALID_KEYFRAME_TIME` | 0.6：keyframe timeMs 为负值或超出所属 timeline 的 durationMs | ❌ |
| `SCRIPT_INVALID` | 0.7：ScriptRuleValidator 结构校验拒（message 为人读原因首行；细节进 server 日志） | ❌ |
| `SCRIPT_NOT_FOUND` | 0.7：script.update / delete / enable / test 指向不存在或非本墙的 ruleId | ❌ |
| `SCRIPT_QUOTA_EXCEEDED` | 0.7：单墙规则数超 `scripts.max-rules-per-wall`（默 16）。另 0.7.2 起单墙脚本可操作元素数受 `scripts.max-elements-per-wall`（默 200）约束 | ❌ |
| `SCRIPT_ENGINE_UNAVAILABLE` | 0.7：script.test 时执行引擎（ScriptTestLauncher）未装配——启动早期窗口 / 测试装配缺时回此码（P2-P5 已落地，正常运行不再触发） | ❌ |
| `UPLOAD_REJECTED` | 图片上传被拒（M13）；message 含具体原因（大小 / MIME / decode timeout / bbox） | ❌ |
| `QUOTA_PER_WALL` | M13/M14：当前 wall 引用图片数超 `images.max-per-wall` | ❌ |
| `QUOTA_PER_DAY` | M13/M14：玩家 24h 上传次数超 `images.max-uploads-per-day` | ❌ |
| `QUOTA_DISK_FULL` | M13/M14：插件 uploads 目录总字节超 `images.max-total-storage-mb`，且 LRU 无可回收行 | ❌ |
| `QUOTA_EXCEEDED` | M14：模板发布超 `templates.max-per-player`，且无 `canvas.template.bypass-limit` | ❌ |
| `NOT_FOUND` | M14：template.delete / template.feature / template.unfeature 指向不存在 templateId | ❌ |
| `DB_FAILED` | M14：TemplatePublisher 写 SQLite 失败（templates upsert / featured update） | ✅ |
| `WRITE_FAILED` | M14：TemplatePublisher 写 YAML 文件失败（user-templates/<uuid>/*.yml） | ✅ |
| `NO_SESSION` | 0.8-A：`POST /api/project/import` 缺 sessionId 或会话未知（HTTP 401） | ❌ |
| `SESSION_NOT_READY` | 0.8-A：`POST /api/project/import` 会话无可写活动墙（HTTP 409） | ❌ |
| `NO_FILE` | 0.8-A：`POST /api/project/import` 缺 `file` multipart 字段（HTTP 400） | ❌ |
| `IMPORT_ZIP_TOO_LARGE` | 0.8-A：`.canvas` 包体积 / 单条或总解压量超限，防 zip 炸弹（HTTP 413） | ❌ |
| `IMPORT_SPEC_UNSUPPORTED` | 0.8-A：`manifest.spec` 高于本插件支持的最高版本，需升级插件（HTTP 409） | ❌ |
| `IMPORT_SIZE_MISMATCH` | 0.8-A：工程画布尺寸与目标墙尺寸不一致（HTTP 409） | ❌ |
| `IMPORT_BAD_ENTRY` | 0.8-A：`.canvas` zip 含非法条目名（路径穿越等，HTTP 400） | ❌ |
| `IMPORT_MALFORMED` | 0.8-A：`.canvas` zip 无法解析 / 缺 manifest.json 或 project.json / 结构非法（HTTP 400） | ❌ |
| `INTERNAL` | 0.8-A：`POST /api/project/import` 编排期意外运行期异常兜底（HTTP 500；不静默 500-without-body） | ❌ |
| `UNEXPECTED` | 服务端断言失败（如 brush op 返了 OkSnapshot），通常是 bug，含上下文 | ❌ |
| `INTERNAL_ERROR` | 服务器内部错误 | 视情况 |

### 6.2 WS Close 码

实际由服务端发出的 close code（`WebServer` 各 `closeXxx` 助手；前端 `wsClient.isTerminalCloseCode` 决定是否重连）：

| code | 说明 | 前端重连 |
| --- | --- | --- |
| 1000 | 正常关闭（客户端主动 `close()`） | 否（terminal） |
| 4001 | 认证失败 / `auth_timeout`（M16-P1.2：未认证 5s 超时）/ 会话级 IP 绑定不一致（M16-P6.6，文本 `ip_mismatch`）/ `session_forgotten`（服务端 forget 陈旧连接） | 否（terminal）— 清掉本地 token |
| 4002 | 业务协议版本不匹配（`CLOSE_PROTOCOL_VERSION_UNSUPPORTED`，M16-P6.2 常量化）。前端 `handleReady` 检出 `accepted_v !== CLIENT_V` 时也主动发此码 | 否（terminal）— 需升级客户端 |
| 4003 | 会话被其他连接接管（`session-takeover`）**或**认证后权限被撤销（`PERMISSION_REVOKED`，2026-05-25 #3 复用同码） | **是**（非 terminal，会退避重连）— 接管/撤权场景下重连会再走 auth 自然失败 |
| 4429 | token 暴力枚举超限（`CLOSE_TOKEN_RATE_LIMITED`，2026-05-25）。沿用 HTTP 429 语义，client 应显示"请稍后再试" | 否（terminal） |

> **未实装**：原表中的 `1008`（策略违反 / 限流反复触发）、`1011`（服务端错误）、`4004`（空闲超时）当前代码均**未作为 WS close code 发出**——`SessionRateLimiter` 注释明确"close 1008 留 M7 polish"，idle/空闲回收走 SessionReaper 的 `markDisconnected` 而非显式 4004 close。保留记录以备规划。
>
> 前端另有一个内部用的 `4000`（`ready_timeout` / `malformed_ready`）——`wsClient` 在 open→ready 看门狗超时或 ready payload 畸形时**客户端自己**发的 close 码（非服务端发出），落非 terminal 分支触发重连。

---

## 7. 工程状态模型

客户端与服务器共享同一份数据结构。v2 起 elements 数组被层包裹；`protocolVersion` = `ProjectState.PROTOCOL_VERSION`，当前 **3**（v3 timeline 进 ProjectState 时 bump；脚本不进 ProjectState 故 v4-v7 不动）：

```typescript
type ProjectState = {
  version: number;            // 递增版本号，每次变更 +1
  protocolVersion: 3;         // = ProjectState.PROTOCOL_VERSION
  canvas: {
    widthMaps: number;
    heightMaps: number;
    background: Fill;         // M17 起 string→Fill 联合类型（solid/linear/radial）；
                             // FillDeserializer 自动把旧 "#xxx" 字符串 wrap 成 SolidFill
    gridSize?: number;        // 0/缺省 = 不显示网格；常用值 8/16/32（仅前端预览，不入 MC）
    guides?: Guide[];         // 用户参考线，仅前端预览
  };
  layers: Layer[];            // 至少 1 个；层间 z-order = index（大 = 上）
  activeLayerId: string;      // 当前 UI 操作层；服务端中继 + element.add 缺 layerId 时用
  timelines?: Timeline[];     // v3 新增；缺省/null = 静态画板
  activeTimelineId?: string;  // v3 新增；缺省/null = 无激活时间轴
  history: { undoDepth, redoDepth };
};

type Guide = {
  axis: "x" | "y";
  position: number;           // 像素坐标
};

type Layer = {
  id: string;                 // "l-<uuid>"
  name: string;
  visible: boolean;
  locked: boolean;
  opacity: number;            // 0..1
  blendMode: BlendMode;       // 见下
  elements: Element[];        // 层内 z-order = index
};

type BlendMode = "normal" | "multiply" | "screen" | "overlay";

type Element =
  | TextElement
  | RectElement
  | IconElement
  | PathElement       // M9
  | CircleElement     // M9
  | ShapeElement      // M9（正多边形 / 星）
  | ImageElement;     // M13

type BaseElement = {
  id: string;       // "e-<uuid>"
  type: string;
  x: number;        // 画布内像素坐标（0 ~ widthMaps*128）
  y: number;
  w: number;
  h: number;
  rotation: number; // 0..359
  locked: boolean;
  visible: boolean;
  opacity?: number;           // v2 新增；默认 1.0
  blendMode?: BlendMode;      // v2 新增；默认 "normal"
  renderMode?: "clean" | "dither";  // v2 新增；默认 "clean"
};

type TextElement = BaseElement & {
  type: "text";
  text: string;
  fontId: string;
  fontSize: number;  // px
  color: string;
  align: "left" | "center" | "right";
  lineHeight: number;
  letterSpacing: number;
  vertical: boolean;
  effects: {
    stroke?: { width: number; color: string };
    shadow?: { dx: number; dy: number; color: string };
    glow?: { radius: number; color: string };
  };
};

type RectElement = BaseElement & {
  type: "rect";
  fill: string;
  stroke?: { width: number; color: string };
};

// M9 PathElement：通用 SVG-like 路径（M/L/Q/C/Z 子集）。
// d 坐标相对 element (x,y)（即 element bbox 左上角）。
// fillRule 字段 0.8 Part B 新增：SVG fill-rule，null 等价 nonzero（默认）。
// 无 DB migration：fillRule 在 project_json blob 内，旧记录缺字段 = 视为 null。
type PathElement = BaseElement & {
  type: "path";
  d: string;                              // SVG path d（M/L/Q/C/Z 绝对命令子集）
  fill?: Fill;                            // 填充（solid/linear/radial）
  stroke?: { width: number; color: string };
  marker?: string;                        // 箭头等标记（可空）
  fillRule?: "nonzero" | "evenodd";       // 0.8 Part B 新增；null/缺省 = nonzero
};
// element.add（type="path"）和 element.update（patch 含 fillRule）均支持 fillRule 字段；
// 服务端 ElementValidator.parseFillRuleNullable 校验：仅接受 "nonzero"/"evenodd"/null。
// 双端渲染：后端 PathRenderer 走 Path2D.WIND_EVEN_ODD/WIND_NON_ZERO；
// 前端 PreviewRenderer.drawPath 走 ctx.fill(path, fillRule ?? 'nonzero')。

// M13：图片元素。source 是上传时返回的 sha256[:16] hash（内容寻址）；
// 客户端用 GET /api/upload/{source} 拉取原图。mask 是可选 SVG path
// 蒙版（M9 PathDValidator 子集 M/L/Q/C/Z），坐标相对 element bbox 0..w/0..h；
// inverted=true 时取 mask 外部像素（图层蒙版反相）。
type ImageElement = BaseElement & {
  type: "image";
  source: string;          // sha256[:16] hash
  mask?: {
    d: string;             // SVG path d，相对 (0,0)..(w,h)
    inverted: boolean;     // false=显示 mask 内（默认），true=显示 mask 外
  };
};

// === 时间轴（v3 新增） ===
// 形态对应后端 record（docs/timeline.md §2）；enum 字段的 wire 形态由后端 record 的
// @JsonProperty 显式映射为 camelCase（同 BlendMode 范式：Java enum 常量 + @JsonProperty
// 注解），双端对齐，非 Java enum name 直出。

type Timeline = {
  id: string;                    // "tl-<8hex>"
  name: string;                  // 用户可读名
  durationMs: number;            // 总时长
  fps: number;                   // 该条时间轴帧率（默认 20，受 config timeline.max-fps 钳）
  loopMode: LoopMode;
  trigger: TriggerConfig;        // 触发方式
  tracks: Record<string, Keyframe[]>;  // key = elementId
};

// tracks 的 key 是 elementId，值是该元素**所有属性混在一起**、按 timeMs 升序的关键帧列表。
// 前端按 (elementId, property) 二级分组渲染成多条属性子轨（方案 B，见 docs/timeline.md §2.2）。

type LoopMode = "once" | "loop" | "pingPong";

type Keyframe = {
  id: string;                    // "kf-<8hex>"，coalesce / patch 定位用
  property: string;              // "x"/"y"/"w"/"h"/"rotation"/"opacity"/"color"/"fill"/"text" 等
  timeMs: number;                // 在 timeline 内的时刻
  value: KfValue;                // 见下
  easing: Easing;                // 到**下一个**关键帧的缓动（末帧的 easing 无意义）
};

// value 多态：数值 / 字符串 / Fill。复用 element fill 的 FillDeserializer 多态范式
// （string→Solid / object 按 type 分流），不需新序列化基建；亦可为 `${var:X}` 字符串。
type KfValue = number | string | Fill;

type Easing = {
  type: EasingType;
  bezier?: [number, number, number, number];  // 仅 cubicBezier 用：[x1,y1,x2,y2]
};

type EasingType = "linear" | "easeIn" | "easeOut" | "easeInOut" | "cubicBezier";

type TriggerConfig = {
  type: TriggerType;
  params: Record<string, string>;   // 各 trigger 的参数（如 variableChange 的 fullName）
};

// playerNear 留 0.7（需从零建事件层 + 与 0.7 Scratch 触发系统重叠，见 docs/timeline.md D5）
type TriggerType = "manual" | "variableChange" | "schedule";
// 0.6 P5 落地：variableChange / schedule 必须带 params.fullName（绑定的变量 rawName，如 user/hp /
// schedule/eta_seconds），缺失 / 空白 → timeline.create/update 拒 INVALID_PAYLOAD。fullName 用
// 与 ${var:X} 同款 rawName 形态，后端 TimelineTriggerRegistry 注册时经 resolveFullName 注入 wallId 匹配
// 变化事件；同一 (wall,timeline) 触发去抖窗 200ms（服务端状态机内部决策，不在协议层暴露，见 timeline.md §5.2）。
```

---

## 8. 完整交互示例

### 8.1 打开编辑器到首次渲染

```
前端 ─── HTTP GET /api/session/abc123 ───▶ 插件
                                           ← 200 { ok: true, playerName, wsUrl }   (M15.4 精简，敏感元数据走 ready)
前端 ─── WS open /ws ─────────────────────▶
前端 ─── { op: "auth", id: "c-0", payload: { token, client_v: 7 } }
                                           ← { op: "ready", id: "s-0", payload: { projectState, ... } }

（画布空白，玩家看到白色预览墙面，无任何文字）

前端 ─── { op: "template.apply", id: "c-1",
          payload: { templateId: "subway_station",
                     params: { name: "人民广场", line_color: "#E4002B" } } }
                                           ← { op: "ack", id: "c-1", payload: { version: 1 } }
                                           ← { op: "state.snapshot", id: "s-1",
                                               payload: { projectState: {...} } }

（游戏内 4 张地图各收到一个 map packet，显示模板初始状态）
```

### 8.2 编辑一个文字

```
前端 ─── { op: "element.update", id: "c-5",
          payload: { elementId: "e-abc", patch: { text: "静安寺" } } }
                                           ← { op: "ack", id: "c-5", payload: { version: 2 } }

（服务端算出 "人民广场" → "静安寺" 的脏矩形，构造 MC map packet 推送。
 patch path 是 /layers/{i}/elements/{j}/text，由服务端按 elementId 查到正确层和位置）

前端继续快速改色、改字号……每个 op 走同样流程。
```

### 8.2.5 图层操作（v2）

```
# 新建图层（叠加到 "线条" 层之上）
前端 ─── { op: "layer.create", id: "c-7",
          payload: { name: "标注", afterLayerId: "l-base" } }
                                           ← { op: "ack", id: "c-7",
                                               payload: { layerId: "l-a1b2c3d4" } }
                                           ← { op: "state.patch", id: "s-3",
                                               payload: { ops: [
                                                 { op: "add", path: "/layers/2",
                                                   value: { id: "l-a1b2c3d4", ... } }
                                               ] } }

# 把元素拖到另一层
前端 ─── { op: "element.move-to-layer", id: "c-8",
          payload: { elementId: "e-abc", targetLayerId: "l-a1b2c3d4" } }
                                           ← { op: "state.patch", id: "s-4",
                                               payload: { ops: [
                                                 { op: "remove", path: "/layers/0/elements/3" },
                                                 { op: "add", path: "/layers/2/elements/0", value: {...} }
                                               ] } }

# 切换当前活动层（仅 UI 状态；不进 history；不发 patch）
前端 ─── { op: "layer.set-active", id: "c-9",
          payload: { layerId: "l-a1b2c3d4" } }
                                           ← { op: "ack", id: "c-9" }
```

### 8.3 锁定与终结（lock-state 重设计 · 2026-05-14）

```
（编辑期间任何 element.* op 都已 auto-save 到 walls 表，不需要显式 commit）

# 玩家（必须是 owner）点击 TopBar Lock 按钮
前端 ─── { op: "wall.lock", id: "c-42" }
                                           ← { op: "ack", id: "c-42",
                                               payload: { lockedAt: 1714200000000 } }
（服务端校验 caller.uuid == wall.owner_uuid，UPDATE walls.published_at=now；
 ItemFrame PDC **不再** 写 published_at（2026-05-14 lock-state 重设计砍）；
 session 仍 ACTIVE；后端编辑 op 路径不被 lock 状态影响——前端 readonly UI 是唯一执行者）

# 非 owner 试图 lock
前端 ─── { op: "wall.lock", id: "c-42" }
                                           ← { op: "error", id: "c-42",
                                               payload: { code: "FORBIDDEN",
                                                          message: "only wall owner can lock" } }

# 玩家解锁
前端 ─── { op: "wall.unlock", id: "c-43" }
                                           ← { op: "ack", id: "c-43",
                                               payload: { locked: false } }
> 注意：M15.1 P0-2 起 `lockedAt: null` 改为显式 `locked: false`（避免 JsonInclude.NON_NULL 全局策略把字段吞掉导致前端收空对象）。

# 玩家关闭浏览器
前端 ─── { op: "cancel", id: "c-99" }
                                           ← { op: "ack", id: "c-99" }
                                           ← WS close code 1000
（服务端释放 session/wand；wall 数据 + ItemFrame 完整保留，下次可 /canvas open <wall_id> 继续）
```

> M5.5 前的 `commit` op 流程（转 PERMANENT、写 sign_records、补池、close 1000）已废止。  
> 2026-05-14 起 `wall.publish` / `wall.unpublish` 也废止，由 `wall.lock` / `wall.unlock` 取代；MC 命令族不再含 publish/unpublish 子命令。

---

## 9. 限流

| 维度 | 阈值 | 超过行为 |
| --- | --- | --- |
| 单会话 op 速率 | 20 msg/s | 返回 `RATE_LIMITED` 并丢弃本次 op |
| 单会话 op 突发 | 40 msg / 2s | 同上 |
| 重复触发 | 5 次 / 1min | **未实装**：`SessionRateLimiter` 注释明确 "close 1008 留 M7 polish"；当前只有上面两档软限流 |

另有 per-IP 的 **token 暴力枚举限流**（`TokenRateLimiter`，2026-05-25），在 auth 阶段触发 → close `4429`（见 §3.2 / §6.2），与上面的 op 速率限流是两套机制。

对 `ping` / `ack` 不计速率。

---

## 9.5 HTTP API（M13 引入）

部分操作不走 WS，而是 HTTP 端点：

### `POST /api/upload`（M13）

玩家上传图片。请求体 `multipart/form-data` + 字段 `file`。

**校验栈**（详见 `security.md §4.5`）：
1. 权限：caller 必须有 `canvas.upload`（默认绑 `canvas.edit`）
2. `Content-Length` ≤ `config.images.max-size-kb`（默认 2 MB），否则 `413` + `UPLOAD_REJECTED: file too large`
3. `Content-Type` ∈ `config.images.allowed-mime`（默认 `image/png|jpeg|webp`）
4. **Magic bytes** 校验真实 MIME（前 16 字节），两层不一致拒
5. `ImageIO.read` 超时 200ms（`ExecutorService.submit(...).get`）；解码失败 / 死循环 / OOM 拒
6. Bbox sanity：0 < w/h ≤ 8192；边长 > `downscale-max-edge`（默认 1024）→ 自动 bilinear downscale
7. 配额三层：`max-per-wall` / `max-uploads-per-day` / `max-total-storage-mb`，任一超限 → LRU 删最老或拒

**响应**（`200 OK`）：
```json
{ "source": "9f3a2b7e4c1d0a5f", "width": 512, "height": 512, "bytes": 87432 }
```

**错误**：`401` 未认证 / `403` 无权限 / `413` 太大 / `400` `UPLOAD_REJECTED`（含 reason）/ `429` 配额耗尽

### `POST /api/project/import`（0.8-A）

导入一个 `.canvas` 工程包（zip），**整体替换**当前会话绑定墙的工程内容（保留多层 / 时间轴语义）。后端 `ProjectImportHandler.handleImport` 处理；`WebServer` 注册此端点时要求 `AssetIngest` 与 `images` 导入配置（`ImportConfig`）均装配，**任一缺则该端点不注册**（请求 404）。

请求体 `multipart/form-data`，字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `sessionId` | 是 | 当前活跃编辑会话 id（form param）；定位要导入的目标墙 |
| `file` | 是 | `.canvas` 工程包（zip）。安全解包：条目名白名单 + 单条/总解压量上限（防 zip 炸弹），必须含 `manifest.json` + `project.json` |

**鉴权 / 权限**：

1. `sessionId` 必须对应一个活跃会话，且该会话已绑定可写墙（`wallId` / `editSession` / `projectState` 均就绪），否则分别 `401` / `409`。
2. 权限节点 `canvas.edit`（导入是「整体替换工程」的破坏性写，与上传绑 `canvas.upload` 同理）。**fail-closed**：经 live `Player.hasPermission` 判定，玩家离线（拿不到在线 `Player`）一律视为无权限 → `403`。

**响应**（`200 OK`）——导入成功（含「带降级」成功）：

```json
{ "ok": true, "warnings": [ { "kind": "asset-quota", "detail": "2 image(s) skipped (quota full or undecodable)" } ] }
```

`warnings` 为非致命提示列表（导入照常完成，仅告知某些内容被降级处理）；完全无损导入时为空数组 `[]`。每项 `{ kind, detail }`：`kind` 是稳定类别码（前端据此选大白话文案），`detail` 是该类别的具体对象（缺失字体名 / 被丢弃的 elementId 等），无对象时为空串。

**warning `kind` 全集**：

| kind | 含义 |
| --- | --- |
| `missing-font` | 引用的字体在本服未注册（detail = fontId；运行期降级为默认字体）。**已实装** |
| `missing-variable` | 引用了本服没有的**全局用户变量** `userglobal/<key>`（detail = `userglobal/<key>`；运行期显示为 `???` 占位）。**已实装**（仅扫 `userglobal`：`user/*` 为 wall-scoped、导入新墙本就需重设；`schedule`/`scoreboard`/`wall`/`rail`/`system` 等由本服 provider 运行期 resolve；皆不扫，避免误报） |
| `missing-icon` | 引用的**用户自定义图标** `user/<id>` 在本服未注册（detail = `user/<id>`；运行期留空）。**已实装**（仅扫 `user/*`；内置 `fa-*` 恒在、legacy PNG 形态不在范围） |
| `script-command-blocked` | 脚本规则的 `runCommand` 命中未注册命令模板（detail = templateId）；**规则照常落库**，仅该命令运行期被拦 |
| `script-invalid` | 脚本规则结构 / 条件语法校验失败，跳过该规则（整份 `scripts.json` 无法解析时返单条此 kind） |
| `script-quota` | 导入脚本规则数超单墙上限（`scripts.max-rules-per-wall`），停止处理后续规则 |
| `animation-flattened` | 动画被压平为静态（保留位，当前实现未产出） |
| `orphan-track-dropped` | 关键帧轨引用了不存在的 elementId，该轨被丢弃（detail = elementId） |
| `asset-quota` | 部分 `assets/*.png` 因配额满 / 不可解码被跳过（detail = 跳过张数） |

> 上表为文档约定的完整集合（与 `ImportWarning` 类注释一致）。**当前实装实际产出 8 种**：`missing-font` / `missing-variable`（仅 `userglobal`）/ `missing-icon`（仅 `user/*`）/ `asset-quota` / `orphan-track-dropped` / `script-invalid` / `script-command-blocked` / `script-quota`；仅 `animation-flattened` 仍为预留 kind，代码暂未触发。前端按全集翻译即可。

**错误**——失败响应统一为 `{ "error": <code>, "message": <人读原因> }` + 对应 HTTP status：

| code | HTTP | 触发 |
| --- | --- | --- |
| `NO_SESSION` | 401 | 缺 `sessionId` 或会话未知 |
| `SESSION_NOT_READY` | 409 | 会话没有可写的活动墙（未绑 wall / editSession / projectState） |
| `FORBIDDEN` | 403 | 缺 `canvas.edit`（含玩家离线 fail-closed） |
| `NO_FILE` | 400 | 缺 `file` multipart 字段 |
| `IMPORT_ZIP_TOO_LARGE` | 413 | zip 体积 / 单条或总解压量超限（防 zip 炸弹） |
| `IMPORT_SPEC_UNSUPPORTED` | 409 | `manifest.spec` 高于本插件支持的最高版本（提示升级插件） |
| `IMPORT_SIZE_MISMATCH` | 409 | 工程画布尺寸与目标墙尺寸不一致 |
| `IMPORT_BAD_ENTRY` | 400 | zip 内含非法条目名（路径穿越等） |
| `IMPORT_MALFORMED` | 400 | zip 无法解析 / 缺 `manifest.json` 或 `project.json` / manifest / `project.json` 结构非法 / 读取上传文件失败 |
| `INTERNAL` | 500 | 编排期意外运行期异常（兜底，不静默 500-without-body） |

> 映射实现：`NO_SESSION` / `SESSION_NOT_READY` / `FORBIDDEN` / `NO_FILE` 由 handler 前置校验直接定 status；`IMPORT_*` 经 `ProjectImportHandler.statusFor` 映射（`IMPORT_ZIP_TOO_LARGE`→413、`IMPORT_SPEC_UNSUPPORTED`/`IMPORT_SIZE_MISMATCH`→409、其余 `IMPORT_*` 默认 400）；`INTERNAL` 为 catch-all 500。

**导入成功后的下行语义**（两条独立通道，前端编辑器 + 游戏内地图都会刷新）：

1. **WS 推 `state.snapshot`**：经 `push.pushSnapshot(session, projectState)` 向该会话推一帧完整工程快照（§5.2）；前端 `wsClient.handleSnapshot` 整体刷新编辑器状态。
2. **游戏内全画布重绘**：经 `ProjectionThrottler.submit` 提交脏区投影，把新工程重绘到游戏内地图（否则玩家在游戏里要等墙重载才能看到新内容）。

> 脚本是 wall-scoped 状态、与 `ProjectState` 解耦，不进 `state.snapshot`——`scripts.json` 仅落 `wall_scripts` 库。
>
> **SVG 矢量导入（0.8 Part B 实装）**走纯前端路径：前端解析 SVG → 一组 `PathElement`（+内嵌位图 `ImageElement`）→ 经现有 `element.add` op 循环写入（每个元素独立一条 op，N 条 element.add = N 次可撤销）；内嵌位图走 `POST /api/upload` 先上传拿 hash 再 element.add。SVG 导入**不走本端点**，无需新 HTTP 端点。

### `GET /api/upload/{source}?session=<sessionId>`（M13；M16-P1.1 起强制鉴权）

按 sha256[:16] hash 拉取原图。返回 `image/png`（统一存储为 PNG，jpeg/webp 上传时已转）。

> **M16-P1.1 鉴权变更（2026-05-16）**：原"无需 token，hash 不可枚举即视为脱敏"的假设被推翻——hash 会出现在 `project_json` / 客户端 DOM / WS 帧日志中，任何能拿到 ws 流量的第三方插件 / 服内调试工具都能枚举出 hash 列表。现强制要求 query `?session=<sessionId>`，服务端校验：(a) sessionId 对应一个活跃 ACTIVE session；(b) 该 session 绑定 wall 的 `project_json` 内任意 ImageElement.source == 请求的 hash。不通过 → HTTP 401 + `UNAUTHORIZED`。这保证图片只对正在编辑该 wall 的玩家可见。

### `GET /api/upload/quota`（M13）

返当前 player 剩余配额，前端 UI 显示。
```json
{
  "perWall": { "limit": 8, "used": 3 },
  "perDay":  { "limit": 50, "used": 17 },
  "totalDiskMb": { "limit": 1024, "used": 412 }
}
```

---

## 10. 版本化与兼容

- 协议版本字段 `v` 只在**不兼容**变更时递增
- **向后兼容新增**：加新 op、加新可选字段、加新错误码 — 不升 `v`
- **不兼容变更**：改字段类型、改必填字段、改语义 — `v` +1
- 插件拒绝 `v < minSupported` 的客户端：`error: VERSION_MISMATCH` + close 4002
- 协议版本协商在 `auth` 帧进行；客户端用多大的 `v` 作为上限由握手时 `serverVersion` 决定
- **0.6 起协议升至 v3**（取干净切换，不维持 v2 双轨；理由见开头「v2 → v3 变更总览（0.6）」）。`ProjectState.PROTOCOL_VERSION` 同步 bump 到 3
- **0.7.0 起 v4**（墙脚本 `script.*`），**0.7.1 起 v5**（新触发器 + 有界循环），**0.7.3 起 v6**（补间 tweenBlock）→ **v7**（备选积木批）。均干净切换；`Protocol.SUPPORTED_MIN = MAX` 当前都 = **7**，前端 `CLIENT_V = 7`。v4 起脚本不进 ProjectState，故 `ProjectState.PROTOCOL_VERSION` 留 **3** 不动（有意为之）。auth 帧 `client_v` 不在范围 → reject `VERSION_MISMATCH` + close 4002，沿用 M16.6 既有版本协商路径

---

## 11. 安全要求（参考 `security.md`）

- Token 必须通过 HTTPS/WSS（公网部署）
- Token 单次使用：握手成功后立即 rotate，新 token 供重连用
- **会话级 IP 绑定**（M16-P6.6）：Session.boundIp 在首次 auth 时 CAS 绑定 caller IP；后续帧 IP 不一致 → close 4001 + `AUTH_FAILED`。绑 session 不绑 token（token 已单次 + TTL）。已知限制（IPv6 norm / 反代 XFF）见 `security.md §2.5`
- 所有 payload 字段在服务端二次校验（长度、数值范围、颜色格式）
- 任何字符串字段最大长度 256；富文本字段单独定义最大长度
- 颜色必须为 `#RRGGBB` 或 `#RRGGBBAA` 格式，拒绝 CSS 关键字
- Jackson 接收侧严格（M16-P6.1）：`FAIL_ON_UNKNOWN_PROPERTIES=true`，未知字段直接拒 `INVALID_PAYLOAD`；服务端错误消息脱敏（不回传字段实际值 / 内部路径）

---

## 12. 未决问题

- [ ] 是否支持 batch op（多个操作打包一次发送，减少延迟）
- [ ] 历史 `history.mark` 的 label 是否持久化到 walls.project_json（M5.5 决策：当前 walls 不存 history，cancel 后 redo/undo 栈丢失；如要保留可 M7 加 `walls.history_json`）
- [ ] **0.6**：`timeline.play/pause/seek` 是否需在服务端持久化"上次播放位置"，还是每次从 0 起（与 `docs/timeline.md §12` 同一项对齐，不另造结论）
- [ ] **0.6**：触发器 `variableChange` 绑高频变量（如 `eta_seconds` 每秒变）时的去抖策略是否需要在协议层可见（`trigger.params` 暴露去抖窗口），还是纯服务端状态机内部决策（`docs/timeline.md §5.2`）
- [ ] 画布 resize 是否允许缩小（需处理越界元素）
- [x] template.apply 是否支持保留现有自由元素（merge 语义）—— **M6 v1 不做**，沿用 replace（清空 elements + 替换 background）；UI 上加"应用模板会覆盖当前内容"提示。merge 留 v2+
- [x] 多人协作（v2）时的协议扩展（是否需要 CRDT）—— **永久不做**。接力编辑（前一玩家 cancel 后下一玩家 /canvas open）已满足需求
- [x] **M5.5**：ready payload 加 `wallId` / `alias` / `publishedAt` —— **已实装**
- [x] **M6**：ready payload 加 `templates`（全量 TemplateSpec 列表）—— **已实装**
- [x] **M8**：图层模型 + 协议 v2 + opacity/blendMode/gridSize/guides 一次性升级 —— **协议固化，待实施**
- [ ] **M12** 笔刷流 brush.* 通道：能否复用 element.update 还是必须独立通道 → 决策时机：M11 dither 完成后回头评估带宽
- [x] **M13** 图片上传 `/api/upload` 端点 chunked 大文件 vs 一次性 multipart → **决策（2026-05-14）：一次性 multipart**。默认 max-size-kb=2048（2 MB），单 HTTP body multipart 足够；chunked 上传增加协议复杂度，单文件上限提到 10 MB 内都可承受。chunked 留 v2 加视频文件支持时再上
