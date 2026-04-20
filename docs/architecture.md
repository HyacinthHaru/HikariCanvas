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

**开启编辑（两段式：先选区再确认）：**
```
Player ─ /canvas edit ─▶ SessionManager 开启 SELECTING 状态
          或持 Canvas Wand 首次点击              │
                                                │
Player 左/右键点击墙面（空手 / 方块 / wand 均可） │
                                                ▼
                             记录 pos1 / pos2 + WallResolver 预览
                                                │
                             聊天栏回显坐标与矩形信息（N×M 张地图）
                                                │
Player ─ /canvas confirm ─▶ SessionManager.create
                                                │
                     ┌───────────────────┬──────┴──────────┐
                     ▼                   ▼                 ▼
              WallResolver         MapPool.reserve(N×M)  FrameDeployer
              （锁定朝向/坐标）     （借出预览地图）      （挂物品框 +
                                                         填 Placeholder）
                                                │
                                                ▼
                                       TokenService.issue(player)
                                                │
                                                ▼
                               玩家聊天栏收到可点击的 URL + token
```

**说明：**
- `/canvas confirm` **立即**挂物品框并填入 Placeholder 地图（浅灰底 + "HikariCanvas" 水印 + 坐标文字），让玩家在游戏内直接看到所选墙面的物理占位，不必等浏览器才知道选对了位置。
- Placeholder 像素由 `render.PlaceholderRenderer`（M2 引入）生成：预烘焙位图 ASCII 字表（M4 前无字体系统）+ 静态浅灰底色；同一会话多张地图共享同一张像素缓冲区，减小内存。

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
| 命令 | `command/` | `/canvas` 所有子命令（Brigadier） |
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
                              │ /canvas edit  或  首次持 Wand 点击
                              ▼
                       ┌─────────────┐
                       │  SELECTING  │ ← 玩家尚在挑选墙面
                       └──┬──────┬───┘
       /canvas cancel     │      │ /canvas confirm
                          ▼      ▼
                     ┌───────┐  ┌─────────────┐
                     │ CLOSED│  │   ISSUED    │ ← 物品框已挂 + placeholder
                     └───────┘  │             │   + Token 已签发
                                └─┬──────┬────┘
          Token 15 分钟过期        │      │ 浏览器握手
                                  ▼      ▼
                             ┌───────┐  ┌──────────┐
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
| `CLOSED → SELECTING` | 仅记 SessionId + playerUuid；**不触碰池、不挂物品框**；等待玩家点击两角 |
| `SELECTING → SELECTING` | 记录 pos1 / pos2；WallResolver 做合法性预览；聊天栏回显；玩家可以覆盖重选 |
| `SELECTING → CLOSED` | `/canvas cancel`：仅丢弃 selection 状态 |
| `SELECTING → ISSUED` | `/canvas confirm`：墙面锁、池借出 N×M 地图、挂物品框填 placeholder、Token 签发 |
| `ISSUED → ACTIVE` | WS 握手成功，Token 标记为已使用 |
| `ISSUED → EXPIRED` | Token 过期，归还预览地图到池、卸下物品框 |
| `ACTIVE → CLOSING(commit)` | 预览地图转 PERMANENT、写 SignRecord、补池 |
| `ACTIVE → CLOSING(cancel)` | 归还池、卸物品框 |
| `ACTIVE → CLOSING(disconnect)` | WS 断开 5 分钟后触发，等同 cancel |
| `CLOSING → CLOSED` | 清理完成 |

### 3.3 并发约束

- **每玩家最多 1 个活跃会话**（包括 `SELECTING` 态）。`/canvas edit` 时若已有会话，提示「已有活跃会话，先 `/canvas cancel`」
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

**清理（管理员 `/canvas cleanup`）：**
扫描 SQLite 中所有 PERMANENT 记录 → 验证物品框仍存在 → 不存在则转 FREE 归还池。

### 4.4 Placeholder 地图

`/canvas confirm` 后物品框**立刻**挂上并填入地图，但浏览器尚未打开——此时显示一张静态 Placeholder：

**视觉：**
- 浅灰底色（palette 索引待 M4 调色板 LUT 就位后固化；M2 先用 MC map palette 中贴近 `#CCCCCC` 的一个索引）
- 顶部："HikariCanvas"（约 12px 高位图字，居中）
- 底部：坐标文字 `(x, y, z) → (x', y', z')` 与尺寸 `N×M`（告诉玩家「这块墙就是你刚选的」）

**实现：**
- 位图字表：M2 阶段预烘焙一个 ASCII 字表（只用英文字母+数字+括号+逗号+箭头），因为 M4 之前还没有 TTF 字体系统
- 单张 128×128 图像预生成后**所有会话共享**同一张像素缓冲（只读，内存节省）
- 每张物品框渲染的 Placeholder 需要叠加自己的"位置标签"（例如 "2/6" 表示这是 6 张地图里的第 2 张）→ 用**字符贴图 + 叠加**，不重渲整张；所有可能的标签预生成有限集
- 打印代码归属：`render/PlaceholderRenderer.java`（M2 引入）

**协议契约：** Placeholder 的像素布局与字表坐标不算公开契约；`ProjectState` 中不存在 Placeholder 元素，任何编辑动作一旦发出（`element.add` 等），Placeholder 立刻被真实渲染覆盖。

### 4.5 健康指标

插件暴露指标（`/canvas stats` 管理员命令）：

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

### 7.1 交互与选区（两段式）

**第一段：进入 SELECTING 状态**

两条入口，二择一：

| 入口 | 条件 |
| --- | --- |
| `/canvas edit` 命令 | 玩家空手或手持任何方块都可，进入 SELECTING |
| 持 Canvas Wand 点击方块 | 无需命令，首次点击即隐式开启 SELECTING |

前者对偶尔使用的玩家友好（零背包负担），后者对频繁使用的玩家效率更高（WorldEdit 式肌肉记忆）。两种选完成后的语义等价。

**第二段：指定对角线并确认**

1. **选 pos1**：在 SELECTING 状态下，玩家**左键**点击墙面任一方块
   - 服务端记录 `pos1 = block.location`、`normal = interactEvent.getBlockFace()`
   - 聊天栏回显：`§7第一角 §f(10, 64, -5) §8朝 §fEast`
2. **选 pos2**：玩家**右键**点击另一方块
   - 必须与 `pos1` 位于同一平面（同一 normal + 两点的 normal 方向坐标相等）
   - WallResolver 做合法性预览：bounding box 内所有方块是否可放物品框（实心方块 + 当前无挂件）
   - 预览成功 → 聊天栏回显：`§7选区 §f3×2 §8(6 张地图) §f(10, 64, -5) → (13, 65, -5)  §7/canvas confirm 确认`
   - 预览失败 → 说明具体原因（平面不一致 / 方块不是实心 / 已有物品框等），`pos1/pos2` 均保持，允许玩家继续覆盖重选
3. **手打 `/canvas confirm` 确认**
   - 立即走 7.2 的部署流程
   - 确认成功后**从玩家 inventory 移除 Wand**（如果持有）

**SELECTING 期间的其他行为：**
- 玩家重复点击 = 覆盖更新最近的同键位点
- `/canvas cancel` = 丢弃 selection，回到 CLOSED
- 玩家断线 / 离线 = SELECTING 立即释放（无资源占用）

### 7.2 物品框部署（/canvas confirm 后立即执行）

对区域内每个方块位置：
- `spawnItemFrame(block, facing = normal)`
- `frame.setItem(mapItem)` ← **池借出的地图；像素填 Placeholder（§4.x）**
- `frame.setRotation(NONE)`
- `frame.setInvisible(true)` ← 编辑中隐藏边框
- `frame.setFixed(true)` ← 防止破坏/旋转

PDC 标记：`hikari_canvas:session = sessionId`，便于清理与后续 WS 关联。

部署完成后玩家**立即能在游戏中看到浅灰的 Placeholder 网格**，直观验证墙面选对。

### 7.3 提交 vs 取消

- **提交**：物品框保持可见（可配置）或继续 INVISIBLE；PDC 标记改为 `hikari_canvas:sign = signId`；`FIXED` 保持
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
- 支持配置 `context-path`（如 `/canvas/`），便于反代下挂多个插件

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
- `/canvas stats` 管理员命令：池状态、活跃会话、近期事件
- `/canvas debug <sessionId>`：单会话详情
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
  context-path: ""                  # 反代时可设 "/canvas"
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
