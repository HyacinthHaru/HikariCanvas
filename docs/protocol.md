# WebSocket 通信协议

**状态：** 立项稿 v0.1 · 2026-04-19
**适用范围：** 浏览器编辑器 ↔ 插件
**协议版本：** `1.0`

本协议定义浏览器与插件之间的消息格式、生命周期、错误处理。**前后端必须严格按此实现**；任何变更必须升级协议版本并在此文档记录。

---

## 1. 传输层

| 项 | 约定 |
| --- | --- |
| 传输 | WebSocket（RFC 6455） |
| 默认路径 | `ws://127.0.0.1:8877/ws` |
| 编码 | UTF-8 JSON 文本帧 |
| 压缩 | `permessage-deflate`（必开启） |
| 心跳 | WS ping/pong，30s 间隔 |
| 最大消息尺寸 | 1 MiB（commit 时可能接近上限） |

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
| `v` | int | 双向 | 协议版本，当前 `1` |
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
2. 客户端首帧必须发送 `auth`：

```json
{ "v": 1, "op": "auth", "id": "c-0", "payload": { "token": "..." } }
```

3. 服务器校验通过 → `ready`：

```json
{ "v": 1, "op": "ready", "id": "s-0",
  "payload": {
    "sessionId": "e1b2...",
    "serverVersion": "1.0.0",
    "protocolVersion": 1,
    "projectState": { ... } }
}
```

4. 失败 → `error` + WS close（见 §6 close 码）

### 3.3 稳态

客户端发送编辑 op，服务器按需回 `ack` / `state.patch` / `error`。服务器也可以主动推送 `state.patch`（例如另一端同步）。

### 3.4 断开与重连

- **客户端主动关闭**：先发 `cancel`（希望抛弃）或 `commit`（希望保存），再关闭。不发直接关也等同 `disconnect`。
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
| `project.load` | C→S | 载入既有 SignRecord 进行二次编辑 |

### 5.3 元素编辑类

所有元素编辑均通过 `element.*` 族 op。服务端是权威状态持有者，客户端发意图、服务端算结果。

| op | 方向 | payload |
| --- | --- | --- |
| `element.add` | C→S | `{ type, props, after? }` |
| `element.update` | C→S | `{ elementId, patch }` |
| `element.delete` | C→S | `{ elementId }` |
| `element.reorder` | C→S | `{ elementId, index }` |
| `element.transform` | C→S | `{ elementId, x?, y?, w?, h?, rotation? }` |

### 5.4 画布与模板

| op | 方向 | payload |
| --- | --- | --- |
| `canvas.resize` | C→S | `{ widthMaps, heightMaps }` (前提：池有容量) |
| `canvas.background` | C→S | `{ color }` |
| `template.apply` | C→S | `{ templateId, params }` （会清空现有工程） |

### 5.5 历史类

| op | 方向 | payload |
| --- | --- | --- |
| `undo` | C→S | `{}` |
| `redo` | C→S | `{}` |
| `history.mark` | C→S | `{ label }` 打一个可命名的历史点 |

### 5.6 会话终结

| op | 方向 | payload |
| --- | --- | --- |
| `commit` | C→S | `{}` - 服务器回 `ack { signId }` 后关闭 |
| `cancel` | C→S | `{}` - 服务器回 `ack` 后关闭 |

### 5.7 服务端主动推送

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
| `INTERNAL_ERROR` | 服务器内部错误 | 视情况 |

### 6.2 WS Close 码

| code | 说明 |
| --- | --- |
| 1000 | 正常关闭（commit / cancel 后） |
| 1008 | 策略违反（限流反复触发） |
| 1011 | 服务端错误 |
| 4001 | 认证失败 |
| 4002 | 协议版本不匹配 |
| 4003 | 会话被其他连接接管 |
| 4004 | 空闲超时 |

---

## 7. 工程状态模型

客户端与服务器共享同一份数据结构：

```typescript
type ProjectState = {
  version: number;            // 递增版本号，每次变更 +1
  canvas: {
    widthMaps: number;        // 横向地图数
    heightMaps: number;       // 纵向地图数
    background: string;       // "#RRGGBB"
  };
  elements: Element[];        // 按 z-order 排列，index 越大越上层
  history: {
    undoDepth: number;
    redoDepth: number;
  };
};

type Element =
  | TextElement
  | RectElement
  | LineElement
  | IconElement;   // v1.x

type BaseElement = {
  id: string;       // "e-<uuid>"
  type: string;
  x: number;        // 画布内像素坐标（0 ~ widthMaps*128）
  y: number;
  w: number;
  h: number;
  rotation: number; // 度，限定 0/90/180/270 以规避双端渲染差异
  locked: boolean;
  visible: boolean;
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

（服务端算出 "人民广场" → "静安寺" 的脏矩形，构造 MC map packet 推送）

前端继续快速改色、改字号……每个 op 走同样流程。
```

### 8.3 提交

```
前端 ─── { op: "commit", id: "c-42" }
                                           ← { op: "ack", id: "c-42",
                                               payload: { signId: "sg-xyz" } }
（服务端执行 commit 流程：转 PERMANENT、写 SQLite、补池）
                                           ← WS close code 1000
```

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
- [ ] 历史 `history.mark` 的 label 是否持久化到 SignRecord
- [ ] 画布 resize 是否允许缩小（需处理越界元素）
- [ ] template.apply 是否支持保留现有自由元素（merge 语义）
- [ ] 多人协作（v2）时的协议扩展（是否需要 CRDT）
