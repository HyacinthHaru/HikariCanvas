# HikariCanvas 系统架构

**状态：** 立项稿 v0.1 · 2026-04-19
**适用范围：** 后端插件 + 前端编辑器

本文档定义系统的组件划分、数据流、生命周期与关键机制。所有代码实现必须遵循此架构；如需调整，先改本文档再改代码。

---

## 1. 总览

### 1.1 一句话

玩家在游戏里锁定一面墙 → 打开浏览器编辑器 → 编辑过程实时投影到游戏里那面墙上 → 提交后成为永久招牌。

### 1.2 高层拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│                        玩家的计算机                              │
│                                                                  │
│   ┌───────────────────┐        ┌────────────────────────┐       │
│   │  Minecraft 客户端 │        │  浏览器（编辑器 UI）    │       │
│   │                   │        │   Vue 3 + Konva        │       │
│   └──────────┬────────┘        └───────────┬────────────┘       │
│              │                             │                    │
│              │ Minecraft 协议              │ HTTP + WebSocket   │
└──────────────┼─────────────────────────────┼────────────────────┘
               │                             │
               │                             │ （可选：反向代理）
               │                             │
┌──────────────▼─────────────────────────────▼────────────────────┐
│                      Minecraft 服务器                            │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐     │
│  │                 HikariCanvas 插件                   │     │
│  │                                                         │     │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐ │     │
│  │  │ Command  │  │  Web     │  │  Session Manager     │ │     │
│  │  │ 模块     │  │ (Javalin)│  │                      │ │     │
│  │  └────┬─────┘  └────┬─────┘  └──────────┬───────────┘ │     │
│  │       │             │                   │             │     │
│  │       └─────┬───────┴───────────────────┘             │     │
│  │             │                                         │     │
│  │             ▼                                         │     │
│  │  ┌───────────────────────────────────────────────┐   │     │
│  │  │           Render Engine                       │   │     │
│  │  │  Font · Palette · Layout · Effects            │   │     │
│  │  └────────────────────┬──────────────────────────┘   │     │
│  │                       │                              │     │
│  │                       ▼                              │     │
│  │  ┌───────────────────────────────────────────────┐   │     │
│  │  │           Map Pool（核心）                     │   │     │
│  │  │  FREE / RESERVED / PERMANENT                  │   │     │
│  │  └────────────────────┬──────────────────────────┘   │     │
│  │                       │                              │     │
│  │                       ▼                              │     │
│  │  ┌───────────────────────────────────────────────┐   │     │
│  │  │     Packet Sender（PacketEvents）             │───┼─────┼──→ MC Client
│  │  │  ClientboundMapItemDataPacket                 │   │     │
│  │  └───────────────────────────────────────────────┘   │     │
│  │                                                       │     │
│  │  ┌───────────────────────────────────────────────┐   │     │
│  │  │ Storage: SQLite · PDC · YAML Templates        │   │     │
│  │  └───────────────────────────────────────────────┘   │     │
│  └────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 核心数据流

**开启编辑：**
```
Player ─ /hc edit ─▶ Command ─▶ SessionManager
                                     │
                     ┌───────────────┤
                     │               │
                     ▼               ▼
              WallResolver      MapPool.reserve(N×M)
              （识别墙面朝向）    （借出预览地图）
                     │               │
                     └───────────────┤
                                     ▼
                               FrameDeployer
                               （放物品框 + 填入池中地图）
                                     │
                                     ▼
                               TokenService.issue(player)
                                     │
                                     ▼
                     玩家聊天栏收到可点击的 URL + token
```

**编辑过程：**
```
浏览器   编辑动作（如改某文字颜色）
   │
   │  WS: {op: "update", element: ..., props: ...}
   ▼
WebSocketHandler
   │
   ▼
EditSession.apply(op)  ← 服务端持有权威的工程状态
   │
   ▼
增量重渲染（只渲染受影响的元素 → 脏矩形）
   │
   ▼
PaletteMapper.map(rgb → paletteIndex)
   │
   ▼
MapPacketSender.push(mapId, dirtyRect, paletteBytes)
   │
   ▼
玩家 MC 客户端收到包 → 游戏内墙面像素更新
```

**提交：**
```
浏览器 ─ WS: {op: "commit"} ─▶ EditSession.commit()
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
               MapPool              SQLite             PDC
               RESERVED → PERMANENT 写入 SignRecord   打 owner 标记
                    │
                    ▼
               补充新 FREE 地图回池
```

---

## 2. 组件分层

### 2.1 后端（插件）

| 层 | 包 | 职责 |
| --- | --- | --- |
| 入口 | `HikariCanvas` | 生命周期、依赖装配 |
| 命令 | `command/` | `/hc` 所有子命令（Brigadier） |
| Web | `web/` | Javalin HTTP + WebSocket + 静态资源 |
| 认证 | `web/auth/` | Token 签发、校验、过期 |
| 会话 | `session/` | 编辑会话状态、每玩家最多 1 活跃 |
| 渲染 | `render/` | 字体、排版、调色板、效果 |
| 模板 | `template/` | YAML 解析、参数绑定、实例化 |
| 地图池 | `pool/` | **核心**：预览地图借还 |
| 部署 | `deploy/` | 墙面识别、物品框、包发送 |
| 存储 | `storage/` | SQLite、PDC 工具 |
| 配置 | `config/` | YAML 配置读取 |

### 2.2 前端（编辑器）

| 层 | 目录 | 职责 |
| --- | --- | --- |
| 应用壳 | `App.vue` | 路由、全局布局 |
| 画布 | `components/Canvas/` | Konva 画布、图层渲染、选中变换 |
| 工具栏 | `components/Toolbar/` | 新增元素、撤销重做、缩放 |
| 图层面板 | `components/LayerPanel/` | 图层顺序、显隐、锁定 |
| 属性面板 | `components/PropertiesPanel/` | 选中元素的参数编辑 |
| 模板库 | `components/TemplateGallery/` | 模板浏览与载入 |
| 网络 | `network/` | WS 客户端、重连、消息序列化 |
| 预览 | `render/PreviewRenderer.ts` | 与后端一致的 Canvas 渲染 |
| 状态 | `stores/` | Pinia：工程状态、UI 状态、网络状态 |

---

## 3. 编辑会话生命周期

### 3.1 状态机

```
                       ┌─────────────┐
                       │   CLOSED    │
                       └──────┬──────┘
                              │ /hc edit
                              ▼
                       ┌─────────────┐
                       │   ISSUED    │ ← Token 已签发，未使用
                       └──┬──────┬───┘
         Token 15 分钟过期│      │ 浏览器握手
                          ▼      ▼
                     ┌─────┐  ┌──────────┐
                     │EXPIRED│  │  ACTIVE  │
                     └───────┘  └─┬───┬────┘
                                   │   │
                       commit      │   │  cancel / WS 断连 5min
                                   ▼   ▼
                           ┌───────────┐
                           │  CLOSING  │
                           └─────┬─────┘
                                 │ 资源回收完成
                                 ▼
                           ┌───────────┐
                           │   CLOSED  │
                           └───────────┘
```

### 3.2 状态转移动作

| 转移 | 动作 |
| --- | --- |
| `CLOSED → ISSUED` | 墙面锁定、池借出地图、物品框部署、Token 签发 |
| `ISSUED → ACTIVE` | WS 握手成功，Token 标记为已使用 |
| `ISSUED → EXPIRED` | Token 过期，归还预览地图到池、卸下物品框 |
| `ACTIVE → CLOSING(commit)` | 预览地图转 PERMANENT、写 SignRecord、补池 |
| `ACTIVE → CLOSING(cancel)` | 归还池、卸物品框 |
| `ACTIVE → CLOSING(disconnect)` | WS 断开 5 分钟后触发，等同 cancel |
| `CLOSING → CLOSED` | 清理完成 |

### 3.3 并发约束

- **每玩家最多 1 个活跃会话**。`/hc edit` 时若已有会话，提示「已有活跃会话，先 `/hc cancel`」
- **每面墙最多 1 个活跃会话**。以起始方块坐标 + 法线为锁 key，先到先得
- 池容量耗尽：拒绝新会话，提示用户稍后

---

## 4. 预览地图池（核心机制）

### 4.1 为什么需要

Minecraft 的 map ID 存于世界文件 `data/idcounts.dat`，每次 `Bukkit.createMap()` 递增。如果编辑过程里每次重渲染都创建新 MapView，一小时编辑 = 数千个 map ID 泄漏。

**预览地图池的核心思想：** 预分配一批 MapView，编辑期间**只更新像素、不新建**。

### 4.2 数据结构

```
PooledMap
├── id: int                        MC map ID
├── mapView: MapView               Bukkit 对象
├── state: FREE | RESERVED | PERMANENT
├── reservedBy: SessionId?         RESERVED 时指向会话
├── signId: UUID?                  PERMANENT 时指向 SignRecord
├── lastUsedAt: long               用于 LRU 清理
└── paletteBuffer: byte[128*128]   当前像素（调色板索引）
```

池持有一个 `List<PooledMap>` + 两个索引：
- `freeQueue: Deque<PooledMap>` — O(1) 借出
- `byId: Map<Integer, PooledMap>` — O(1) 按 ID 查找

### 4.3 生命周期

**启动：**
1. 读取配置 `pool.initial-size`（默认 64）和 `pool.max-size`（默认 256）
2. 查询 SQLite，恢复既有池地图（插件重启不丢池）
3. 若不足 `initial-size`，补充新建

**借出（会话开启）：**
```
reserve(sessionId, count) -> List<PooledMap>:
    if freeQueue.size() < count:
        if pool.size() + (count - freeQueue.size()) > maxSize:
            throw PoolExhausted
        expandPool(count - freeQueue.size())
    result = []
    for i in 0..count:
        m = freeQueue.poll()
        m.state = RESERVED
        m.reservedBy = sessionId
        result.append(m)
    return result
```

**提交（转永久）：**
```
commit(session) -> SignRecord:
    signId = UUID.random()
    record = buildSignRecord(session, signId)
    for m in session.reservedMaps:
        m.state = PERMANENT
        m.signId = signId
        m.reservedBy = null
        pdc.set(m.mapView, "hc:owner", session.playerUuid)
        pdc.set(m.mapView, "hc:sign", signId)
    sqlite.insert(record)
    pool.refill()   # 补充新 FREE 到 initial-size
    return record
```

**取消（归还）：**
```
cancel(session):
    for m in session.reservedMaps:
        m.state = FREE
        m.reservedBy = null
        clearBuffer(m)
        pushWhitePacket(m)   # 客户端视角清空
        freeQueue.offer(m)
```

**清理（管理员 `/hc cleanup`）：**
扫描 SQLite 中所有 PERMANENT 记录 → 验证物品框仍存在 → 不存在则转 FREE 归还池。

### 4.4 健康指标

插件暴露指标（`/hc stats` 管理员命令）：

- `pool.size`：池总量
- `pool.free`：空闲数
- `pool.reserved`：在用数
- `pool.permanent`：已转永久数
- `pool.leaked`：疑似泄漏数（RESERVED 但无对应 ACTIVE 会话，应为 0）

**泄漏检测：** 每 5 分钟后台扫描，若 RESERVED 但 `reservedBy` 的会话已不存在，强制归还并记日志。

---

## 5. 实时投影管线

### 5.1 帧率策略

```
用户输入事件（前端）
     │
     ▼
 防抖 100ms ────▶ 间隔期间覆盖缓存最新意图
     │
     ▼
 WS 发送 → 后端
     │
     ▼
 后端限流（5 fps 上限）
     │
     ▼
 重渲染 → 脏矩形 → 发包
```

- **静止**：无事件 = 无推送。最后一帧的状态已在客户端地图上，自持。
- **输入中**：100ms 防抖 + 5 fps 节流。
- **提交**：一次完整（非差分）推送，确保最终帧 100% 正确。

### 5.2 脏矩形计算

每次收到 op 后：
1. EditSession 算出哪些元素受影响（添加/删除/修改）
2. 每个受影响元素的包围盒合并 → 整体脏矩形 `(x, y, w, h)`
3. 脏矩形按 128×128 网格切片，每张涉及的 map 各一个局部 packet
4. 若脏矩形覆盖整图 > 80%，降级为整图推送

### 5.3 Packet 格式

`ClientboundMapItemDataPacket` 字段：

| 字段 | 说明 |
| --- | --- |
| `mapId` | 池中 PooledMap.id |
| `scale` | 固定 0（1:1） |
| `locked` | `true`（避免客户端渲染 decoration） |
| `decorations` | `null` |
| `colorPatch.x/y` | 脏矩形在本地图内起点 |
| `colorPatch.width/height` | 脏矩形尺寸 |
| `colorPatch.data` | `byte[width * height]`，调色板索引 |

### 5.4 压缩

- **WebSocket（浏览器 ↔ 插件）：** 启用 `permessage-deflate`（Javalin 支持）。JSON 指令压缩率 3~8x。
- **MC 协议（插件 ↔ 客户端）：** MC 原生协议层 zlib（默认 256B 阈值自动压）。
- **不自行再加一层。**

---

## 6. 双端渲染一致性

### 6.1 问题

浏览器 Canvas 和 Java Graphics2D 在同一字体同一字号下渲染结果**有差异**（hinting、metrics、subpixel），导致玩家看到的游戏内结果与网页预览不一致。

### 6.2 强制规则

1. **同一 TTF 文件**：`src/main/resources/fonts/*.ttf` 和前端 `public/fonts/*.woff2` 由同一源字体转出，构建脚本保证一致
2. **关抗锯齿**：
   - Java：`KEY_TEXT_ANTIALIASING = VALUE_TEXT_ANTIALIAS_OFF`
   - Browser Canvas：`ctx.imageSmoothingEnabled = false`
3. **像素字体优先**：默认模板全部用像素字体，规避 hinting 差异
4. **统一调色板映射**：前端和后端共享同一份 `palette.json`（构建时生成），前端预览也做调色板量化
5. **不用系统字体**：编辑器禁用 `font-family` 回退到系统字体

### 6.3 验证

CI 集成：
- 固定文本集合（20 段覆盖中英数字符号）
- 两端各自渲染输出 PNG
- 像素级 diff，容忍度 < 1%
- 超过阈值则 build 失败

详见 `rendering.md`。

---

## 7. 墙面识别与物品框部署

### 7.1 锁定墙面

玩家执行 `/hc edit` 时：
1. 射线检测玩家视线，命中方块 B 与法线 N（必须为四个水平方向之一：N/S/E/W）
2. 从 B + N 向右/上扩展搜索平整的 `width × height` 方块区域（模板决定尺寸）
3. 验证区域内所有方块为实心、无阻挡、无已有物品框
4. 若失败，提示玩家「请面向一整面 3×2 的墙」等

### 7.2 物品框部署

对区域内每个方块位置：
- `spawnItemFrame(block, facing = N)`
- `frame.setItem(mapItem)`  ← 池借出的地图
- `frame.setRotation(NONE)`
- `frame.setInvisible(true)`  ← 编辑中隐藏边框
- `frame.setFixed(true)`  ← 防止破坏/旋转

PDC 标记：`hc:session = sessionId`，便于清理。

### 7.3 提交 vs 取消

- **提交**：物品框保持可见（可配置）或继续 INVISIBLE；PDC 标记改为 `hc:sign = signId`；`FIXED` 保持
- **取消**：物品框整体移除；预览地图归还池

---

## 8. 持久化

### 8.1 分层

| 存储 | 内容 | 生命周期 |
| --- | --- | --- |
| **SQLite** (`plugins/HikariCanvas/data.db`) | 池元信息、SignRecord、审计日志、模板使用统计 | 跨重启 |
| **PDC**（每张 MapView / ItemFrame） | owner、signId、sessionId 等标签 | 随世界文件 |
| **文件**（`templates/*.yml`） | 模板定义 | 人工管理 |
| **文件**（`fonts/*.ttf`） | 字体 | 人工管理 |

### 8.2 SignRecord 表概览

（详细 schema 在 `data-model.md`）

| 字段 | 说明 |
| --- | --- |
| `id` | UUID |
| `owner_uuid` | 玩家 |
| `world` | 世界名 |
| `origin_xyz + facing` | 墙面锚点 |
| `width × height` | 地图矩阵尺寸 |
| `map_ids` | JSON 数组，指向池中地图 |
| `project_json` | 完整工程数据（可重新载入编辑） |
| `template_id` | 源模板（可为空） |
| `created_at / updated_at` | 时间戳 |

---

## 9. Web 服务层

### 9.1 路由

| 路径 | 方法 | 说明 |
| --- | --- | --- |
| `/` | GET | 静态 `index.html` |
| `/assets/*` | GET | 静态资源（JS/CSS/字体） |
| `/api/session/:token` | GET | 会话握手信息（校验 token） |
| `/ws` | WS | 编辑器主通道 |
| `/health` | GET | 健康检查（只返回 OK，不泄漏信息） |

### 9.2 WebSocket 握手

1. 前端从 URL query 取 token
2. `GET /api/session/:token` 拿到会话初始数据（模板、预览池 size、墙面 WxH）
3. 打开 WS 连接，首帧发送 `{op: "auth", token}`
4. 服务端校验 token → 绑定会话 → 回 `{op: "ready", ...}`

（具体消息格式见 `protocol.md`。）

### 9.3 绑定与部署

- 默认 `bind: 127.0.0.1`，端口默认 `8877`（可配置）
- 公网场景：必须反代 + TLS
- 支持配置 `context-path`（如 `/hc/`），便于反代下挂多个插件

---

## 10. 关键非功能需求

### 10.1 性能目标

| 指标 | 目标 |
| --- | --- |
| 编辑端到端延迟（按键 → 游戏内显示） | < 300ms |
| 单次 commit 生成 8×4 招牌 | < 500ms |
| 插件内存稳态（池 64 张） | < 100MB |
| 并发活跃会话 | ≥ 10 |
| 主线程 tick 时间增加 | < 1ms |

### 10.2 线程模型

- **主线程（Bukkit）**：物品框操作、PDC、MapView 生命周期
- **异步线程（插件 executor）**：渲染、调色板映射、WS I/O、SQLite
- **推送**：异步线程构造 packet → PacketEvents 内部处理发送

**禁止**在异步线程调用 Bukkit API（除明确标注线程安全的）。

### 10.3 可观测性

- SLF4J + 配置文件控制 log level
- `/hc stats` 管理员命令：池状态、活跃会话、近期事件
- `/hc debug <sessionId>`：单会话详情
- 审计日志：commit / cancel / cleanup / auth 失败，写 SQLite

### 10.4 安全

见 `security.md`，此处只列原则：
- 默认不暴露公网
- Token 单次使用 + 过期 + UUID 绑定
- WS 消息限流
- 输入严格校验（字符长度、颜色格式、坐标范围）
- 权限节点细分

---

## 11. 配置文件骨架

```yaml
# plugins/HikariCanvas/config.yml

web:
  bind: 127.0.0.1
  port: 8877
  context-path: ""                  # 反代时可设 "/hc"
  public-url: "http://127.0.0.1:8877" # 生成给玩家的链接

pool:
  initial-size: 64
  max-size: 256
  leak-scan-interval: 5m

session:
  token-ttl: 15m
  idle-disconnect: 5m
  per-player-limit: 1

render:
  default-font: "sourcehan"
  palette-lut: "built-in"           # built-in | custom-path

limits:
  ws-messages-per-second: 20
  text-max-length: 256
  canvas-max-maps: 16               # 单个招牌最多 16 张地图

storage:
  sqlite-path: "plugins/HikariCanvas/data.db"

logging:
  audit: true
```

---

## 12. 未决问题

- [ ] 预览地图池初始化时，若 SQLite 恢复数量 > `initial-size`，超出部分如何处理（保留 vs 缩容）
- [ ] 反代下的 `public-url` 自动探测是否可行，或仍要求服主手动配置
- [ ] commit 后物品框默认可见还是隐藏（用户偏好决定）
- [ ] 会话 disconnect 5 分钟宽限是否太长（公网弱网场景 vs 池占用）
- [ ] 多世界支持：同一池跨世界共享 vs 按世界分池

这些问题在 M1/M2 实现时根据实际情况回填本文档。
