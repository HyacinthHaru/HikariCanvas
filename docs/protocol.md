# WebSocket 通信协议

**状态：** v2 规划稿 · 2026-05-13
**适用范围：** 浏览器编辑器 ↔ 插件
**协议版本：** `2.0`（M8 起；v1 不再兼容）

本协议定义浏览器与插件之间的消息格式、生命周期、错误处理。**前后端必须严格按此实现**；任何变更必须升级协议版本并在此文档记录。

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

## 1. 传输层

| 项 | 约定 |
| --- | --- |
| 传输 | WebSocket（RFC 6455） |
| 默认路径 | `ws://127.0.0.1:8877/ws` |
| 编码 | UTF-8 JSON 文本帧 |
| 压缩 | `permessage-deflate`（必开启） |
| 心跳 | WS ping/pong，30s 间隔 |
| 最大消息尺寸 | 1 MiB（snapshot 时可能接近上限） |

二进制帧保留不使用。调色板像素数据走 MC 原生 map packet，不经 WS。

---

## 2. 消息信封

所有消息均为 JSON 对象，**顶层字段固定**：

```json
{
  "v": 1,
  "op": "element.update",
  "id": "c-17",
  "ts": 1713528000000,
  "payload": { ... }
}
```

| 字段 | 类型 | 方向 | 说明 |
| --- | --- | --- | --- |
| `v` | int | 双向 | 协议版本，当前 `2`（M8 起；v1 不再支持） |
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

响应 200：

```json
{
  "sessionId": "e1b2...",
  "playerName": "Steve",
  "wall": { "width": 4, "height": 2 },
  "mapIds": [101, 102, 103, 104, 105, 106, 107, 108],
  "templates": [ ... ],
  "palette": { ... },
  "fonts": [ { "id": "sourcehan", "url": "/assets/fonts/sourcehan.woff2" } ],
  "wsUrl": "/ws"
}
```

响应 401：token 无效/过期。响应 409：会话已占用。

### 3.2 WS 握手

1. 打开 `wss://.../ws`（或 `ws://` 本地）
2. 客户端首帧必须发送 `auth`，**必须**携带 `clientProtocolVersion: 2`：

```json
{ "v": 2, "op": "auth", "id": "c-0",
  "payload": { "token": "...", "clientProtocolVersion": 2 } }
```

> 服务端收到 `clientProtocolVersion < 2` 或缺字段 → 立刻发 `error: VERSION_MISMATCH` + close 4002。v1 客户端不再兼容（v1 未正式发布、无外部依赖）。

3. 服务器校验通过 → `ready`：

```json
{ "v": 2, "op": "ready", "id": "s-0",
  "payload": {
    "sessionId": "e1b2...",
    "serverVersion": "1.0.0",
    "protocolVersion": 2,
    "reconnectToken": "...",
    "projectState": { /* v2 形态，见 §7 */ },
    "wallId": "w-1a2b3c4d",
    "alias": "subway-test",
    "lockedAt": 1714200000000,
    "ownerUuid": "00112233-4455-6677-8899-aabbccddeeff",
    "selfUuid": "ffeeddcc-bbaa-9988-7766-554433221100",
    "templates": [ ... ]
  }
}

> **2026-05-14**：ready payload 字段 `publishedAt` 改名 `lockedAt`；新增 `ownerUuid`（wall.owner_uuid） + `selfUuid`（当前 session 玩家）让前端判 `isOwner = selfUuid === ownerUuid`。详见 CLAUDE.md `§lock-state`。
```

> **M6 决策（2026-05-11）**：`templates` 字段一次性全量下发，不走单独 `template.list` op。理由：5 个内置模板每个 ~1-2KB，合计 5-10KB；服主自定义模板少（v1 阶段 < 50KB），WS 单帧足够。未来若模板数量爆炸（v2 模板包生态）再切 index + on-demand `template.fetch`。

4. 失败 → `error` + WS close（见 §6 close 码）

### 3.3 稳态

客户端发送编辑 op，服务器按需回 `ack` / `state.patch` / `error`。服务器也可以主动推送 `state.patch`（例如另一端同步）。

### 3.4 断开与重连

- **客户端主动关闭**：先发 `cancel`（释放 session；wall 数据保留）再关闭。不发直接关也等同 `disconnect`。M5.5 起 `commit` op 废止——保存通过每次 `element.*` op 的隐式 auto-save（walls 表 UPDATE）实现，不需要客户端显式发包。
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
| `canvas.background` | C→S | `{ color }` |
| `canvas.grid` | C→S | `{ size: int }`（0 = 关闭网格） |
| `canvas.guides.set` | C→S | `{ guides: [{ axis, position }, ...] }`（整组替换；前端拖动期不发，松手 batch 发） |
| `template.apply` | C→S | `{ templateId, params }` （会清空所有层 + 用 Default Layer 包结果） |

### 5.6 历史类

| op | 方向 | payload |
| --- | --- | --- |
| `undo` | C→S | `{}` |
| `redo` | C→S | `{}` |
| `history.mark` | C→S | `{ label }` 打一个可命名的历史点 |

### 5.7 会话终结

| op | 方向 | payload |
| --- | --- | --- |
| `cancel` | C→S | `{}` - 服务器回 `ack` 后关闭 session（wall 数据保留） |
| `wall.lock` | C→S | `{}` - **owner-only**：caller UUID == wall.owner_uuid 才接受；UPDATE walls.published_at=now（DB 列名保留，语义为 lock 时间戳）；返回 `ack { lockedAt }`；非 owner 返 `FORBIDDEN`；session 不关闭。**2026-05-14 引入** |
| `wall.unlock` | C→S | `{}` - **owner-only**：UPDATE walls.published_at=NULL；返回 `ack { lockedAt: null }`；非 owner 返 `FORBIDDEN`；session 不关闭 |
| `wall.alias` | C→S | `{ "alias": "shop-a" }` - 设别名；冲突返回 error `ALIAS_TAKEN`；session 不关闭 |

> M5.5 起 `commit` op 废止。`wall.*` 系列是 wall 元数据修改，与编辑 op 解耦——不影响 session 生命周期。
>
> **2026-05-14**：`wall.publish` / `wall.unpublish` 砍，新 `wall.lock` / `wall.unlock`。lock 是 UX 层概念，**后端编辑 op（element.* / canvas.* / layer.*）路径与 lock 状态完全解耦**——锁定的 wall 仍能接受编辑 op（动态展示场景需要），前端 readonly UI 是 lock 唯一的执行者。`/canvas publish` / `/canvas unpublish` 命令同时砍。

### 5.9 笔刷流（M12 占位，未实施）

笔刷 op 走专用通道避开 `state.patch` 5fps 节流。设计草案：

| op | 方向 | payload |
| --- | --- | --- |
| `brush.start` | C→S | `{ layerId?, props: { color, size, opacity, ... } }` → 返回 `ack { strokeId }` |
| `brush.point` | C→S | `{ strokeId, points: [[x,y,pressure,t], ...] }` 批量点；服务端立即 dirty bbox |
| `brush.end` | C→S | `{ strokeId }` 服务端固化为 PathElement 写入 layer |

M8 阶段不实装；只在 §12 列为已规划，不写代码前再补具体语义。

### 5.8 服务端主动推送

| op | 方向 | 说明 |
| --- | --- | --- |
| `session.warning` | S→C | 非致命警告（如池即将耗尽、限流） |
| `session.terminated` | S→C | 服务端强制结束（管理员操作或超时） |

---

## 6. 错误模型

### 6.1 应用层错误（`op: "error"`）

```json
{
  "v": 1, "op": "error", "id": "c-17",
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
| `AUTH_FAILED` | token 无效/过期 | ❌ |
| `VERSION_MISMATCH` | 协议版本不兼容 | ❌ |
| `RATE_LIMITED` | 超过限流阈值 | ✅ |
| `POOL_EXHAUSTED` | 预览池耗尽，resize 失败 | ✅ |
| `INVALID_OP` | 未知 op | ❌ |
| `INVALID_PAYLOAD` | payload 校验失败 | ❌ |
| `INVALID_ELEMENT` | 元素 id 不存在或属性非法 | ❌ |
| `PERMISSION_DENIED` | 权限不足 | ❌ |
| `SESSION_CLOSED` | 会话已关闭 | ❌ |
| `ALIAS_TAKEN` | wall.alias 已被其他 wall 占用 | ❌ |
| `WALL_NOT_FOUND` | wall.* op 但当前 session 没绑定 wall（不应发生） | ❌ |
| `LAYER_LOCKED` | element.* op 命中 locked 层；v2 起 | ❌ |
| `LAYER_NOT_FOUND` | layer.* op 指向不存在的 layerId | ❌ |
| `LAST_LAYER` | layer.delete 试图删最后一层 | ❌ |
| `UPLOAD_REJECTED` | 图片上传被拒（M13）；message 含具体原因（大小 / MIME / 配额） | ❌ |
| `INTERNAL_ERROR` | 服务器内部错误 | 视情况 |

### 6.2 WS Close 码

| code | 说明 |
| --- | --- |
| 1000 | 正常关闭（cancel 后或客户端主动断） |
| 1008 | 策略违反（限流反复触发） |
| 1011 | 服务端错误 |
| 4001 | 认证失败 |
| 4002 | 协议版本不匹配 |
| 4003 | 会话被其他连接接管 |
| 4004 | 空闲超时 |

---

## 7. 工程状态模型（v2）

客户端与服务器共享同一份数据结构。v2 起 elements 数组被层包裹：

```typescript
type ProjectState = {
  version: number;            // 递增版本号，每次变更 +1
  protocolVersion: 2;
  canvas: {
    widthMaps: number;
    heightMaps: number;
    background: string;
    gridSize?: number;        // 0/缺省 = 不显示网格；常用值 8/16/32（仅前端预览，不入 MC）
    guides?: Guide[];         // 用户参考线，仅前端预览
  };
  layers: Layer[];            // 至少 1 个；层间 z-order = index（大 = 上）
  activeLayerId: string;      // 当前 UI 操作层；服务端中继 + element.add 缺 layerId 时用
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
```

---

## 8. 完整交互示例

### 8.1 打开编辑器到首次渲染

```
前端 ─── HTTP GET /api/session/abc123 ───▶ 插件
                                           ← 200 { sessionId, wall, mapIds, ... }
前端 ─── WS open /ws ─────────────────────▶
前端 ─── { op: "auth", id: "c-0", payload: { token } }
                                           ← { op: "ready", id: "s-0", payload: { projectState } }

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

### 8.3 发布与终结（M5.5）

```
（编辑期间任何 element.* op 都已 auto-save 到 walls 表，不需要显式 commit）

# 玩家点击"发布"按钮
前端 ─── { op: "wall.publish", id: "c-42" }
                                           ← { op: "ack", id: "c-42",
                                               payload: { publishedAt: 1714200000000 } }
（服务端 UPDATE walls.published_at；ItemFrame PDC 写 published_at；session 仍 ACTIVE）

# 玩家关闭浏览器
前端 ─── { op: "cancel", id: "c-99" }
                                           ← { op: "ack", id: "c-99" }
                                           ← WS close code 1000
（服务端释放 session/wand；wall 数据 + ItemFrame 完整保留，下次可 /canvas open <wall_id> 继续）
```

> M5.5 前的 `commit` op 流程（转 PERMANENT、写 sign_records、补池、close 1000）已废止。

---

## 9. 限流

| 维度 | 阈值 | 超过行为 |
| --- | --- | --- |
| 单会话 op 速率 | 20 msg/s | 返回 `RATE_LIMITED` 并丢弃本次 op |
| 单会话 op 突发 | 40 msg / 2s | 同上 |
| 重复触发 | 5 次 / 1min | close 1008 |

对 `ping` / `ack` 不计速率。

---

## 10. 版本化与兼容

- 协议版本字段 `v` 只在**不兼容**变更时递增
- **向后兼容新增**：加新 op、加新可选字段、加新错误码 — 不升 `v`
- **不兼容变更**：改字段类型、改必填字段、改语义 — `v` +1
- 插件拒绝 `v < minSupported` 的客户端：`error: VERSION_MISMATCH` + close 4002
- 协议版本协商在 `auth` 帧进行；客户端用多大的 `v` 作为上限由握手时 `serverVersion` 决定

---

## 11. 安全要求（参考 `security.md`）

- Token 必须通过 HTTPS/WSS（公网部署）
- Token 单次使用：握手成功后立即 rotate，新 token 供重连用
- 所有 payload 字段在服务端二次校验（长度、数值范围、颜色格式）
- 任何字符串字段最大长度 256；富文本字段单独定义最大长度
- 颜色必须为 `#RRGGBB` 或 `#RRGGBBAA` 格式，拒绝 CSS 关键字

---

## 12. 未决问题

- [ ] 是否支持 batch op（多个操作打包一次发送，减少延迟）
- [ ] 历史 `history.mark` 的 label 是否持久化到 walls.project_json（M5.5 决策：当前 walls 不存 history，cancel 后 redo/undo 栈丢失；如要保留可 M7 加 `walls.history_json`）
- [ ] 画布 resize 是否允许缩小（需处理越界元素）
- [x] template.apply 是否支持保留现有自由元素（merge 语义）—— **M6 v1 不做**，沿用 replace（清空 elements + 替换 background）；UI 上加"应用模板会覆盖当前内容"提示。merge 留 v2+
- [x] 多人协作（v2）时的协议扩展（是否需要 CRDT）—— **永久不做**。接力编辑（前一玩家 cancel 后下一玩家 /canvas open）已满足需求
- [x] **M5.5**：ready payload 加 `wallId` / `alias` / `publishedAt` —— **已实装**
- [x] **M6**：ready payload 加 `templates`（全量 TemplateSpec 列表）—— **已实装**
- [x] **M8**：图层模型 + 协议 v2 + opacity/blendMode/gridSize/guides 一次性升级 —— **协议固化，待实施**
- [ ] **M12** 笔刷流 brush.* 通道：能否复用 element.update 还是必须独立通道 → 决策时机：M11 dither 完成后回头评估带宽
- [ ] **M13** 图片上传 `/api/upload` 端点能否走 chunked 大文件 vs 一次性 multipart → 取决于 max-size-kb 默认值
