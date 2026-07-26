# HikariCanvas 系统架构

**状态：** 立项稿 v0.1 · 2026-04-19；wall 模型重构 · 2026-04-27；lock-state 重设计 · 2026-05-14
**适用范围：** 后端插件 + 前端编辑器

本文档定义系统的组件划分、数据流、生命周期与关键机制。所有代码实现必须遵循此架构；如需调整，先改本文档再改代码。

> **路线修正（2026-04-27）**：原"编辑 → commit 永久固化"二段式（drafts + sign_records / RESERVED + PERMANENT）已废止。新模型：单一 `walls` 表 + `published_at` 标签，wall 永远可改，命令族新增 `open / list / publish / delete` 替代 `commit`。

> **lock-state 重设计（2026-05-14）**：`/canvas publish` / `/canvas unpublish` 命令砍；DB 列 `walls.published_at` 保留但语义改为 lock 时间戳；新 WS op `wall.lock` / `wall.unlock`（owner-only）；前端 TopBar Lock 按钮 + RightPanel readonly UI 是 lock 的唯一执行者；后端编辑 op 路径与 lock 状态完全解耦（未来动态展示用例需要）；ItemFrame PDC 不再写 published_at；FrameProtectionListener "已发布拦截" 砍。下文 §6/§7 旧 publish 流程段落标 `[DEPRECATED 2026-05-14]`，请参考 CLAUDE.md `§lock-state` 与本文档 §3.6 新流程。

---

## 1. 总览

### 1.1 一句话

玩家在游戏里锁定一面墙 → 打开浏览器编辑器 → 编辑实时投影到那面墙上 → 任何时候都能再次打开继续改；作者可在前端 TopBar 触发 lock 把 wall 冻结为只读（其他玩家拿到 `/canvas open` 也只能查看，无解锁路径）。

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
│  │  │  FREE / RESERVED（owner = wall:<wall_id>）     │   │     │
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
- Placeholder 像素由 `render.PlaceholderRenderer`生成：预烘焙位图 ASCII 字表（当时无字体系统）+ 静态浅灰底色；同一会话多张地图共享同一张像素缓冲区，减小内存。

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

**锁定（前端 UX 层，非数据层）：**
```
玩家 A（owner） ─ TopBar Lock 按钮 ─▶ ws.send('wall.lock')
                                              │
                                              ▼
                                      WebServer.handleWallOp
                                              │
                                  校验：caller.uuid == wall.owner_uuid
                                              │
                                              ▼
                                  WallRepo.markPublished(wallId)
                                  （DB 列名保留 published_at；语义为 lock 时间戳；now 由 DAO 取）
                                              │
                                              ▼
                                  ack { lockedAt: now } 回前端
                                              │
                                              ▼
                                  前端 readonly UI 生效
                                  （CanvasView overlay + RightPanel 禁用 + 快捷键守卫）
```

lock 状态：DB 列 walls.published_at 保留原列名（避 SQL 迁移），语义为 lock 时间戳；non-owner 调用 wall.lock/unlock 返 FORBIDDEN。ItemFrame PDC 不写 published_at；所有 wall ItemFrame 由 canvas.modify 权限统一保护。

**删除（仅此操作真正移除 wall）：**
```
/canvas delete <wall_id>
   │  显示 "Re-run with confirm in 30s"
   ▼
/canvas delete <wall_id> confirm （30s 内）
   │
   ├─▶ FrameDeployer.removeForWall  → 拆 ItemFrame
   ├─▶ MapPool.releaseWall(wallId)   → owned maps 全 → FREE
   └─▶ WallRepo.delete(wallId)        → 删 walls 行
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
| 渲染 | `render/` | 字体、排版、调色板、效果、笔触简化、Bayer dither |
| 笔刷 | （EditSession 内 StrokeBuffer） | brush.* op 缓冲、RDP 简化、 Catmull-Rom 拟合 |
| 模板 | `template/` | YAML 解析（jackson-dataformat-yaml）、参数绑定、实例化、registry 热重载 |
| 地图池 | `pool/` | **核心**：预览地图借还 |
| 部署 | `deploy/` | 墙面识别、物品框、包发送 |
| **图片** | `image/`（`ImageStorage` / `UploadHandler` / `ImageQuotaService`） | sha256 内容寻址 + LRU + 配额 + ImageIO 解码隔离 |
| **工程档**（0.8-A） | `canvasfile/`（`CanvasArchive` / `CanvasManifest` / `ProjectMaterializer` / `AssetIngest` / `ScriptImporter` / `ProjectImporter`） | `.canvas` 工程档**导入**信任边界：zip 安全解包 + manifest 校验 + project.json 物化 + 图片摄入 + 脚本重绑（导出在前端，见 §18） |
| 存储 | `storage/` | SQLite、PDC 工具；新增 `image_uploads` 表 DAO |
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

> **状态机概要**：状态机为 SELECTING → ISSUED → ACTIVE → CLOSING，无 `commit` 转移分支。`ACTIVE → CLOSING` 仅由 cancel / disconnect 触发；`/canvas open` 直接进 ACTIVE（绕开 SELECTING / ISSUED 选区流程）。
>
> **状态枚举校正**：`SessionState` 实际只有 `SELECTING / ISSUED / ACTIVE / CLOSING` 四态，无独立 `CLOSED` 终态。会话取消 / 超时后进入 `CLOSING`（不可逆清理路径），SessionManager 将其从索引（byId / byPlayer / byWall）摘除、逻辑隐退；新会话由 `/canvas edit` / `/canvas open` 全新创建，而非从某个 `CLOSED` 态恢复。下文状态机图与转移表沿用历史叙述方便理解，凡标 `CLOSED` 处即指"无活跃会话"这一隐含起点 / 终点，非枚举中的真实状态。

### 3.1 状态机

> 注：图中 `(无会话)` 是"该玩家无活跃会话"这一隐含起点 / 终点，**非** `SessionState` 枚举里的真实状态（枚举只有 SELECTING / ISSUED / ACTIVE / CLOSING）。`CLOSING` 即终态：会话清理完成后被 SessionManager 从索引摘除、逻辑隐退，不再转入任何后续状态。（`EXPIRED` 是 `TokenService` 的 token 拒绝码，**非**会话状态；token 过期等同会话作废 → `CLOSING`。）

```
                       ┌─────────────┐
                       │  (无会话)   │
                       └──┬──────┬───┘
       /canvas edit       │      │ /canvas open <wall_id|alias>
       或  Wand 首次点击 │      │ 或  Wand 瞄已有 ItemFrame → 二次确认
                          ▼      ▼
                  ┌─────────────┐ │
                  │  SELECTING  │ │ ← 玩家挑选墙面（仅"新建"路径走此态）
                  └──┬──────┬───┘ │
   /canvas cancel    │      │ /canvas confirm
                     ▼      ▼     │
              ┌──────────┐ ┌────────┴────┐
              │  CLOSING  │ │   ISSUED    │ ← 物品框已挂 + placeholder
              └──────────┘ │             │   + Token 已签发
                           └─┬──────┬────┘
     Token 15min 过期        │      │ 浏览器握手
                             ▼      ▼
                        ┌───────┐  ┌──────────┐
                        │CLOSING│  │  ACTIVE  │
                        └───────┘  └────┬─────┘
                                        │  cancel / WS 断连 5min
                                        ▼
                               ┌───────────┐
                               │  CLOSING  │ ← 终态：清理完成后
                               └───────────┘   从索引摘除、逻辑隐退
                                                （wall 数据本体仍在 walls
                                                 表，仅释放 session/锁/wand）
```

> 「ACTIVE → CLOSING(commit)」分支已彻底废止。`wall.lock` / `wall.unlock` WS op 不改变 session 状态（它是 wall 元数据的一次 UPDATE，玩家继续在 ACTIVE 中编辑；前端 readonly UI 是 lock 唯一执行者）。

### 3.2 状态转移动作

| 转移 | 动作 |
| --- | --- |
| `(无会话) → SELECTING` | `/canvas edit`：记 SessionId + playerUuid；**不触碰池、不挂物品框**；发 wand；等待两角点击 |
| `SELECTING → SELECTING` | 记录 pos1 / pos2；WallResolver 预览；玩家覆盖重选 |
| `SELECTING → SELECTING (reselect)` | 已 SELECTING 时再次 `/canvas edit` 隐式清 pos1/pos2 重新开始 |
| `SELECTING → CLOSING` | `/canvas cancel`：丢弃 selection、收回 wand（清理完成后逻辑隐退，无后续 CLOSED 态） |
| `SELECTING → ISSUED` | `/canvas confirm`：解析墙面 →（路径 A 新建：池借 N×M、挂物品框、写 walls 新行；路径 B 现有 wall：bind owner、不挂物品框）→ Token 签发 |
| `(无会话) → ACTIVE` | `/canvas open <id>` 或 wand 二次点击已有 ItemFrame：直接 bind 已有 wall + 签发 Token + 跳过物品框部署 |
| `ISSUED → ACTIVE` | WS 握手成功，Token 标记为已使用 |
| `ISSUED → CLOSING` | Token 过期（`EXPIRED` 是 `TokenService` 拒绝码，**非** `SessionState` 枚举）：**仅释放 session/wand**，walls 数据 + ItemFrames 保留（路径 A 新建场景留下"未连入的 wall"，玩家可后续 `/canvas open` 接管） |
| `ACTIVE → ACTIVE (lock)` | `wall.lock` WS op（owner-only）：UPDATE walls.published_at = now（语义为 lock 时间戳）；session 状态不变；前端 readonly UI 生效 |
| `ACTIVE → CLOSING(cancel)` | `/canvas cancel`：仅释放 session/wand；wall 数据 + ItemFrames 保留 |
| `ACTIVE → CLOSING(disconnect)` | WS 断开 5min 触发，等同 cancel |
| `CLOSING（终态）` | session 清理完成后从索引（byId / byPlayer / byWall）摘除、逻辑隐退（wall 表数据**不动**）；无独立 CLOSED 终态 |

> **关键不变量**：会话生命周期（SELECTING/ISSUED/ACTIVE）只管"谁在编辑"；wall 数据生命周期（walls 表行 + map RESERVED + ItemFrames）只在 `/canvas delete` 显式清理。两者解耦。

### 3.3 并发约束

- **每玩家最多 1 个活跃会话**（包括 `SELECTING` 态）。`/canvas edit` 在已 SELECTING 时**隐式 reselect**而非报错；ISSUED/ACTIVE 仍提示先 cancel
- **每面墙最多 1 个活跃会话**（排他锁，wall_id 为 key）。不做协作编辑（OT/CRDT 超 scope）
- 池容量耗尽：拒绝新会话，提示用户稍后；wall 占的 map 一直占着不自动释放，需 `/canvas delete` 显式清

### 3.6 lock 状态

`walls.published_at` 列保留原名，**语义改为 lock 时间戳**：`null` = 可编辑，非 `null` = 已锁定。owner 由 `walls.owner_uuid` 决定。

```
玩家 A（owner）──── 前端 TopBar Lock 按钮 ──▶ ws.send(wall.lock)
                                                    │
                              WebServer 校验 caller UUID == owner_uuid
                                                    │
                              WallRepo.markPublished(wallId)  ── UPDATE walls.published_at = now（now 由 DAO 内部取）
                                                    │
                              ack 回前端 + 广播状态变更
                                                    │
                                          前端 readonly UI 生效

玩家 B（非 owner）/canvas open ──▶ ready payload 含 lockedAt + ownerUuid + selfUuid
                                       │
                              前端 computed isOwner = (selfUuid === ownerUuid) = false
                                       │
                              UI 显示"已锁定，仅作者可解锁"，Lock 按钮 disabled
                                       │
                              用户开发者工具绕过前端 lock → 编辑 op 仍能发送 + 后端接受
                              （后端编辑 op 不读 lock 状态——这是 lock-state 重设计的核心）
```

**关键不变量**：lock 是 UX 层概念，不阻挡 op 路径。如果作者发布锁定的 wall，但内部系统（如未来的动态展示）想用 ws.send(element.update) 更新内容，**不会被 lock 拒**。

### 3.6.1 草稿 wall 协作语义

**未锁定 wall（lockedAt=null）= 协作中间态**：任何 `canvas.edit` 玩家可 `/canvas open <wall_id>`，进入 ACTIVE 编辑。`byWall` 排他锁保证同一时刻只有一个活跃 session，但接力 / 切换 owner 完全开放——前一玩家 `cancel` 后下一玩家立即 open。这是 v1 的协作模型（接力 ≠ 实时多人，OT/CRDT 永久不做）。

**锁定 wall（lockedAt 非 null）**：鉴权方案 C：仅 owner（`caller UUID == owner_uuid`）或持 `canvas.admin.bypass-lock` 的管理员可 open；其他玩家拒 `FORBIDDEN`（`SessionManager.open` 入口拦截）。

> **`wall.lock` / `wall.unlock` WS op 本身严格 owner-only，无 admin bypass**（`WallOpDispatcher` 直接比 `wall.ownerUuid == caller`，非 owner 一律 `FORBIDDEN`）。`canvas.admin.bypass-lock` 仅作用于"打开已锁定 wall"的 open 路径，不放行"代替 owner 锁/解锁"。

**未来 ACL（owner-only 草稿）**：若服主想要"草稿也仅 owner 可改"的语义，走 v1.x 协作 scope（新增 `walls.acl` 列 + acl-aware open 校验）；详见 §13 动态画板路径的同源扩展思路（acl 字段同样不进编辑 op 路径，仅在 open 鉴权点生效）。

### 3.6.2 多世界假设

**MapPool 按 world UUID 分桶**：原 §4 暗示单世界共享池；现 `MapPool` 内部维护 `Map<UUID worldId, PoolBucket>`，每 world 独立 FREE/RESERVED 队列。

- `acquireForWall(World world, String wallId, int count)`：从指定 world bucket 借出；该 bucket 不足时 expand（全局受 `map-pool.max` 限制）
- `bindToWall(World world, ...)`：**强校验** `mapView.world == world`；不一致抛 `IllegalStateException`（之前 silent bind 会让 map 显示在错误维度）
- 跨世界绑定路径被根除：WallRestorer 启动恢复 / `/canvas open` / `confirm` 三处都走 world-aware 路径
- 失败兜底分两种，看异常发生在 bind 之前还是之后：
  - **bind 之前**（world 未加载 / `bindToWall` 抛）→ 这一轮已借到的 mapId 走 `MapPool.releaseToFree` 回 FREE，不留半态预留（避免 idcounts.dat 膨胀，**项目核心风险**，详见 PROPOSAL §5.2.6）；审计 `POOL_RELEASE_TO_FREE`
  - **bind 之后**（渲染阶段抛）→ **保留绑定**，只记 SEVERE + 把 wall 记入 `failedRestoreWallIds` 等下次重启重试。walls 行还在，这些地图本就属于该 wall，泄漏检测不会回收它们；反而是还回 FREE 会被下一次 confirm 借走 → 两面墙共用一张地图 + 该 wall 下次 bind 必失败、永久恢复不了

**配置（config.yml，实际键名）**：

```yaml
map-pool:
  initial: 64            # 全局默认预热张数
  max: 256               # 池上限（全局）
  per-world:             # 可选：按 world name 覆写 initial（值是单个 int，仅预热张数）
    world: 32            # overworld 预热 32 张
    world_nether: 8      # nether 预热 8 张
```

per-world 只覆写每世界的预热张数（`initial`），无独立 max；未列出的 world 走 on-demand 扩容（首次 confirm 时 `createMap`），全局 `max` 是唯一总上限。无 per-world 配置时各 world 仍独立分桶，按需扩容。

---

### 3.7 图片上传数据流

`/api/upload` 走纯 HTTP（不经 WS），与编辑 op 通道完全解耦。配额跟踪在专表 `image_uploads`；ImageElement.source 持 sha256[:16] hash，跨 wall 引用同 hash 文件零重复存储。

```
浏览器 ─ 拖拽 / paste / file input ─▶ ws-token 校验
                                          │
              POST /api/upload (multipart) │
                                          ▼
                                  UploadHandler.handle
                                          │
                            (a) 大小校验 Content-Length ≤ max-size-kb
                            (b) MIME Content-Type ∈ allowed-mime
                            (c) magic bytes 真实 MIME 校验
                            (d) ImageIO.read 隔离（ExecutorService.get 200ms）
                            (e) bbox sanity；边长 > 1024 自动 downscale
                            (f) ImageQuotaService：3 层配额检查
                                  │
                                  ▼
                          ImageStorage.persist(decoded BufferedImage)
                                  │
                       sha256[:16] = computeHash(pngBytes)
                                  │
                       已存在 hash？─ 是 → last_used_at touch
                                  │
                                  否 → 写 plugins/HikariCanvas/uploads/<hash>.png
                                       INSERT image_uploads（uploader / bytes / mime / ...）
                                       磁盘超总配额 → LRU sweep 删未被任何 wall 引用的最老文件
                                  │
                                  ▼
                          ack { source: <hash>, width, height }
                                  │
        前端拿 hash ───▶ ws.send element.add type=image props.source=hash
                                  │
                                  ▼
                          EditSession.buildImage / persist project_json
                                  │
                                  ▼
                          下次 rasterize：CanvasCompositor.drawImage(e.source)
                          ───▶ ImageStorage.load(hash) → BufferedImage
                                  ↑
                                内存缓存（LRU MRU 60s）
```

**关键不变量：**
- ImageStorage.load 内存缓存：图片首次解码后保留 60s（LRU MRU 队列）；过期重读磁盘
- 引用统计走**实时 sweep**（无持久 refcount 列，原列已 V010 DROP，见 `docs/data-model.md §6.5.1`）：LRU 候选 = 遍历所有 `walls.project_json` 收集被引用 hash（`ImageStorage.collectReferencedHashes`）后 `NOT IN` 剔除孤儿（`ImageUploadDao.pickLruCandidates`）
- 删 wall 不立即清磁盘（其他 wall 可能引用同 hash）；待下次 sweep 时若无任何 wall 引用该 hash 才由 LRU 自然回收
- 客户端无路径控制：filename 仅日志，存储路径完全由 hash 决定（防路径穿越）

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
├── state: FREE | RESERVED         （两态。PERMANENT 已废止）
├── reservedBy: String?            RESERVED 时指向 owner，格式 "wall:<wall_id>"
├── lastUsedAt: long               用于 LRU 清理
└── paletteBuffer: byte[128*128]   当前像素（调色板索引）
```

> `reservedBy` 在 wall 模型下是 `wall:<wall_id>`。`session_id` 概念不写入 pool_maps —— 会话只是临时持有者，真正持有 map 的是 wall。

池持有一个 `List<PooledMap>` + 两个索引：
- `freeQueue: Deque<PooledMap>` — O(1) 借出
- `byId: Map<Integer, PooledMap>` — O(1) 按 ID 查找

### 4.3 生命周期

**启动：**
1. 读取配置 `map-pool.initial`（默认 64，钳 [1,1024]）和 `map-pool.max`（默认 256）
2. 查询 SQLite，恢复既有池地图（插件重启不丢池）
3. 若不足 `initial-size`，补充新建

**借出（新建 wall）：**
```
reserveForWall(wallId, count) -> List<PooledMap>:
    if freeQueue.size() < count:
        if pool.size() + (count - freeQueue.size()) > maxSize:
            throw PoolExhausted
        expandPool(count - freeQueue.size())
    owner = "wall:" + wallId
    result = []
    for i in 0..count:
        m = freeQueue.poll()
        m.state = RESERVED
        m.reservedBy = owner
        result.append(m)
    return result
```

**绑定（打开已有 wall：启动时 WallRestorer 跑一次；运行期 `/canvas open` 也走同路径）：**
```
bindWall(wallId, mapIds) -> bool:
    owner = "wall:" + wallId
    # 仅接受这些 map 是 FREE 或已是该 wall 持有的状态
    for id in mapIds:
        m = byId[id]
        if m == null or (m.state == RESERVED and m.reservedBy != owner): return false
    for id in mapIds:
        m = byId[id]
        if m.state == FREE: freeQueue.remove(id)
        m.state = RESERVED; m.reservedBy = owner
    return true
```

> lock 不动池；`wall.lock` WS op（lock-state 重设计后取代旧 `/canvas publish`）只 UPDATE `walls.published_at`（语义=lock 时间戳），map 状态不变，也不写 ItemFrame PDC（FrameDeployer.markPublished 已砍）。

**取消（释放会话占用，wall 数据不动）：**
```
cancel(session):
    for m in session.reservedMaps:
        m.state = FREE
        m.reservedBy = null
        clearBuffer(m)
        pushWhitePacket(m)   # 客户端视角清空
        freeQueue.offer(m)
```

**清理（管理员 `/canvas cleanup`，原 PERMANENT 校对路径已废止）：**
**未实装（stub）。** `CanvasCommand.runCleanup` 当前仅回一句 "cleanup is stubbed" 提示，不做任何实际操作。规划语义为：扫 walls 表，对每行验证 `(world, origin, facing, map_ids)` 与世界中 ItemFrame 的对应关系，孤立行（ItemFrame 全丢）由管理员决定 delete；ItemFrame 不在 walls 表里的（外来）报告但不动。该 fsck 实现尚未落地（至今未做）。

> **泄漏防护不依赖 cleanup**：idcounts.dat 防膨胀的实际防线是后台 `MapPool.detectLeaks` 周期任务（每 5 分钟，硬编码于 `HikariCanvas` onEnable，见 §4.5 / §10.2）+ confirm/部署失败时的原子 `releaseToFree` 回滚，与未实装的 cleanup 命令无关。

### 4.4 Placeholder 地图

`/canvas confirm` 后物品框**立刻**挂上并填入地图，但浏览器尚未打开——此时显示一张静态 Placeholder：

**视觉：**
- 浅灰底色（palette 索引待调色板 LUT 就位后固化；先用 MC map palette 中贴近 `#CCCCCC` 的一个索引）
- 顶部："HikariCanvas"（约 12px 高位图字，居中）
- 底部：坐标文字 `(x, y, z) → (x', y', z')` 与尺寸 `N×M`（告诉玩家「这块墙就是你刚选的」）

**实现：**
- 位图字表：预烘焙一个 ASCII 字表（只用英文字母+数字+括号+逗号+箭头），因为当时还没有 TTF 字体系统
- 单张 128×128 图像预生成后**所有会话共享**同一张像素缓冲（只读，内存节省）
- 每张物品框渲染的 Placeholder 需要叠加自己的"位置标签"（例如 "2/6" 表示这是 6 张地图里的第 2 张）→ 用**字符贴图 + 叠加**，不重渲整张；所有可能的标签预生成有限集
- 打印代码归属：`render/PlaceholderRenderer.java`

**协议契约：** Placeholder 的像素布局与字表坐标不算公开契约；`ProjectState` 中不存在 Placeholder 元素，任何编辑动作一旦发出（`element.add` 等），Placeholder 立刻被真实渲染覆盖。

### 4.5 健康指标

插件暴露指标（`/canvas stats` 管理员命令）：

- `pool.size`：池总量
- `pool.free`：空闲数
- `pool.reserved`：被 wall 持有数（合并 RESERVED + 原 PERMANENT，因为已无后者）
- `pool.unowned_reserved`：RESERVED 但 `reservedBy` 不指向任何 walls 行的疑似泄漏数

**泄漏检测：** 每 5 分钟后台扫描，若 RESERVED 的 `reservedBy = "wall:<id>"` 但 walls 表无对应行，强制归还并记日志。`reservedBy` 是临时 `session:<sid>`（不应出现，本来 wall 模型不再这样写）则视为旧版残留，同样回收。

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
- **session 关闭前最后一帧**：一次完整（非差分）推送，确保最终帧 100% 正确（旧模型称"提交时全量"，新模型下不存在显式 commit，改为 cancel/disconnect 前 ProjectionThrottler flush）。

**两条产帧路径（0.6 引入）。** 上面描述的是**反应式路径**；时间轴动画引入第二条**主动 cadence 路径**。两条按 wall 是否有活跃动画分流：

| 路径 | 适用 wall | 驱动 | 静止行为 |
| --- | --- | --- | --- |
| 反应式（原 ProjectionThrottler） | 静态 / 编辑中 wall | 事件驱动（op / 变量变化） | 无事件 0fps |
| 主动 cadence（AnimationTicker，0.6 引入） | 活跃动画 wall | 按 `timeline.fps` 定 cadence 主动产帧（§5.5） | 由 Ticker 主驱，不依赖外部事件 |

**分流 gate（不可越界）：** 当某 wall 既有活跃动画、又同时在编辑器里被编辑时，两条路径会对同一 wallId 重复写 `HikariCanvasRenderer.update(mapId)`。装配层按 wallId gate：动画接管期间，编辑 op 产生的 reactive flush 退让给 Ticker，由 Ticker 出帧（详见 `timeline.md §3.2`）。`ProjectionThrottler.setIntervalForSession` 只**放宽节流上限**、不自驱产帧——**不能**当 Ticker 用，它仅用于编辑器内预览动画时把节流放宽。

### 5.2 脏矩形计算

每次收到 op 后：
1. EditSession 算出哪些元素受影响（添加/删除/修改）
2. 每个受影响元素的包围盒**按实际会画到的像素外扩**后合并 → 整体脏矩形 `(x, y, w, h)`
3. 脏矩形按 128×128 网格切片，每张涉及的 map 各一个局部 packet
4. 若脏矩形覆盖整图 > 80%，降级为整图推送

**外扩规则（`DirtyRegion.of`）** —— 元素画出来的像素常常超出 bbox，脏区不跟着扩就会在相邻 map 上留下擦不掉的残影（前端每帧全量重画看不见，只在游戏内出现）：

| 情形 | 外扩 |
| --- | --- |
| 文字 `effects.shadow` | 按 `(dx, dy)` 单向 |
| 文字 `effects.stroke` / `glow` | 按 `width/2` / `radius` 四向 |
| 圆 / 多边形 / path 的 `stroke` | `ceil(width/2)` 四向（`BasicStroke` 以路径为中心分摊，向外溢出一半） |
| path 的箭头 / 圆点 marker | 再按 marker 尺寸四向（marker 画在端点之外） |
| 矩形的 `stroke` | 不扩（4 条 `fillRect` 画在 bbox 内部） |
| `rotation` 非 0 / 180 | 旋转后四角的外接矩形 |

已知未覆盖：`PathElement.d` 的坐标可以画到 `w/h` 之外（改 `d` 时 bbox 不同步），要覆盖得解析 `d` 求真实范围，暂不做。

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

### 5.5 AnimationTicker（0.6 引入）

时间轴动画的产帧引擎。详细设计见 `timeline.md §3`；本节固化其在投影管线中的定位与约束。

**定位：** 独立 `ScheduledExecutorService`，按 `timeline.fps` 定 cadence 主动产帧（反应式路径 §5.1 无活跃动画 wall 时彻底静止，给不出"按时间推进"的帧）。

- **不用 Bukkit 定时器。** Bukkit scheduler 最细 1 tick = 50ms（= 20fps 整），给不出高于 20fps 的刷新率。统一用独立 `ScheduledExecutorService` 覆盖到 config `timeline.max-fps` 全范围。
- **照 `VariableProviderDaemon` 范式造**：`ScheduledExecutorService` + `scheduleAtFixedRate` + 三层异常隔离 + 幂等关停，是现成参考实现。

**每 tick 流程：**

```
对每个活跃动画 wall:
    findViewersForWall(wallId) 为空 → 跳过该 wall（viewer-gated，§下）
    按 loopMode（ONCE / LOOP / PING_PONG）推进 timeMs
    KeyframeInterpolator 算插值后的临时 ProjectState（record copy，只改值不改结构）
    CanvasProjector.projectByWall(wallId) 出帧 → MapPacketSender 发送
```

插值数学的权威定义在 `rendering.md §9`（输出帧 = `Rasterize(Interpolate(state, timeMs))`）；临时 ProjectState 只改值不改结构，不 mutate 持久化状态（§13.3）。

**viewer-gated：** `findViewersForWall(wallId)` 返空就停该 wall 的 tick——空墙不烧 CPU，符合"数据透明、不替服主决策"的一贯原则。

**`BufferedImage` 池化：** `rasterize` 每帧 `new BufferedImage(...TYPE_INT_ARGB)`，其并发安全靠"每次 new"保证。动机墙是 8×8 多墙叠加 + 蒙版图（蒙版图约 42MB/帧，高 fps 物理不可行，池化前禁动画）。**池化只在 Ticker 单线程内做**：Ticker 单线程串行出帧，每帧借一张、量化完归还（`Graphics2D.clearRect` 复用而非 new），**不跨线程借还**，不破坏 rasterize "每次 new 保证并发安全"的契约。

**帧率策略：**

1. `Timeline.fps` 是服主显式参数，**默认 20fps**（= 一个 Bukkit tick 50ms）。每墙刷新率由服主自改。
2. config `timeline.max-fps` 是**服务器级安全阀**（默 **60**）：管理员保护多租户服务器、防单墙极端 fps 拖垮渲染线程 / 网络的总阀门。`Timeline.fps` 受此阀钳。
3. **不做成本估算、不自动校准、不自动降级。** 服主自负性能，工具不替其决策（"数据透明、不替服主决策"原则的延续，见 §13）。想知道单机承载，服主自跑 `/canvas bench`（0.5.0，与时间轴解耦）。

**帧间脏区：** 动画帧间脏区 ≈ 整画布，§5.2 的 dirty-region 增量基本失效。改做 **per-map 帧间 diff**：只发"像素变了的 map"（不是变了的像素）——逐 map 比对相邻两帧，未变 map 不发包。这是基本不浪费的优化，直接做，不 gate 在实测上。

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

> **墙面方向限制**：`WallResolver` **仅支持垂直墙面**（朝向必须是水平的 N/S/E/W）。天花板 / 地板（UP/DOWN）会被拒为 `VERTICAL_ONLY`。

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

> **尊重保护插件**：选点的三个 listener（方块左/右键、画框左/右键）都不处理已被别的插件取消的交互——方块层看 `PlayerInteractEvent.useInteractedBlock() == DENY`（不是笼统的 `isCancelled()`，那会把"只禁了手上这件物品"的情形也算进来），实体层直接 `ignoreCancelled = true`；三者都注册在 `EventPriority.HIGH`，确保跑在保护插件（多在 LOWEST~NORMAL 拒绝）之后。否则玩家能在 WorldGuard / 领地保护区里选一片墙，`confirm` 之后由插件自己动方块 + 挂画框，等于绕过保护。

**SELECTING 期间的其他行为：**
- 玩家重复点击 = 覆盖更新最近的同键位点
- `/canvas cancel` = 丢弃 selection，进 CLOSING 后逻辑隐退（无活跃会话）
- 玩家断线 / 离线 = SELECTING 立即释放（无资源占用）

### 7.2 物品框部署（仅"新建 wall"路径走，`/canvas confirm` 后立即执行）

对区域内每个方块位置：
- `spawnItemFrame(block, facing = normal)`
- `frame.setItem(mapItem)` ← 从池 reserveForWall 借出的地图；像素填 Placeholder（§4.4）
- `frame.setRotation(NONE)`
- `frame.setFixed(true)` ← 防止破坏/旋转
- `frame.setInvisible(true)` 看场景需求（实测发现 spawn-time 设 invisible 会与客户端 spawn-consumer 时序冲突，目前先 visible，留后续打磨）

PDC 标记（namespace 固定 `hikaricanvas`，`NamespacedKey(plugin, key)` 取插件名小写）：
- `hikaricanvas:wall_id = <wall_id>` ← 核心 key（替代旧的 `session` / `sign`）
- `hikaricanvas:slot = <index>` ← 该 frame 在 wall 里的位置序号
- ~~`hikaricanvas:published_at`~~ ← **2026-05-14 lock-state 重设计砍**：`FrameDeployer.markPublished` 已移除，ItemFrame PDC 不再写此 key（现存旧画框残留的该 key 保留无害，不再读）。lock 状态只存 DB 列 `walls.published_at`，不下放到 PDC

> **路径 B「打开已有 wall」（`/canvas open` 或 wand 二次点击 / 启动恢复）不走 7.2，物品框已存在不重新部署，直接 bind 池 + 写 ProjectState。**

**部署 / 修复时对世界的改动边界**（`FrameDeployer`）：

| 情况 | 行为 |
| --- | --- |
| 墙面 bbox 内支撑方块变成 AIR | 补回 `STONE`（这是墙自己的格子，玩家撸掉支撑画框就会掉） |
| 画框位置被草 / 雪 / 水等**可被放置覆盖**的方块占住 | 清掉后照常挂画框 |
| 画框位置被玩家**放置的方块**占住（`Block.isReplaceable() == false`） | **跳过这一格**并 warning，绝不替玩家删方块。玩家清空后再点一次刷新即可补上 |
| 画框位置 1 格内有掉落物 | 只清 `ITEM_FRAME` / `GLOW_ITEM_FRAME` / `FILLED_MAP` / `MAP`（本插件自己弄掉的东西）；玩家的其它掉落物不动 |

> `wall.refresh` 的修复路径（`repairFor`）在扫现存画框之前会**同步加载墙面所在区块**。Bukkit 的实体查询只看得见已加载区块，玩家在远处点刷新时若不先加载，会把"看不见"当成"画框没了"，再 spawn 一整面新的 → 区块加载回来就是两层画框重叠。同理 `/canvas delete` 拆框前也必须先确保区块已加载（`CanvasCommand.runDeleteConfirm` 世界未加载直接拒绝 + 按墙尺寸预加载一圈）。

### 7.3 wall 数据生命周期 vs 会话生命周期

| 操作 | 影响 wall 数据？ | 影响 ItemFrames？ | 影响 session？ |
| --- | --- | --- | --- |
| `/canvas confirm`（新建路径） | 新增 walls 行 | 新挂 N×M 个 | SELECTING → ISSUED |
| `/canvas confirm`（现有 wall：bind 路径） | 不变 | 不变 | SELECTING → ISSUED |
| `/canvas open` | 不变 | 不变 | (无会话) → ACTIVE |
| `wall.lock` WS op（取代旧 `/canvas publish`） | UPDATE published_at（=lock 时间戳） | 不变（PDC 不再写） | 不变 |
| `wall.unlock` WS op（取代旧 `/canvas unpublish`） | UPDATE published_at=NULL | 不变（PDC 不再写） | 不变 |
| `/canvas alias` | UPDATE alias | 不变 | 不变 |
| `/canvas cancel` | 不变 | 不变 | 释放（→ CLOSING 终态，逻辑隐退） |
| WS 5min disconnect | 不变 | 不变 | 释放 |
| `/canvas delete <id>` | 第一次提示等 confirm；30s 内 confirm → DELETE walls 行 | 拆除 | 释放 |

---

## 8. 持久化

### 8.1 分层

| 存储 | 内容 | 生命周期 |
| --- | --- | --- |
| **SQLite** (`plugins/HikariCanvas/data.db`) | 池元信息、walls 表、审计日志、模板使用统计 | 跨重启 |
| **PDC**（每张 ItemFrame） | `wall_id` / `slot`（2026-05-14 起不再写 `published_at`，见 §7.2） | 随世界文件 |
| **文件**（`templates/*.yml`） | 模板定义 | 人工管理 |
| **文件**（`fonts/*.ttf`） | 字体 | 人工管理 |

### 8.2 walls 表概览（取代 SignRecord）

（详细 schema 在 `data-model.md`）

| 字段 | 说明 |
| --- | --- |
| `wall_id` | TEXT PK，玩家可见短 ID（`w-<8hex>`） |
| `world / origin_xyz / facing` | 墙面锚点；与 `wall_id` 一对一（同 `(world, origin, facing)` 唯一索引） |
| `width_maps / height_maps` | 地图矩阵尺寸 |
| `map_ids` | CSV，指向池中地图 |
| `project_json` | 完整工程数据；任何 op 后 UPDATE |
| `owner_uuid / owner_name` | 创建者；非排他锁，仅做归属展示 |
| `alias` | 玩家命名，nullable |
| `published_at` | nullable timestamp；**语义=lock 时间戳**（2026-05-14 lock-state 重设计，列名保留）：`wall.lock` WS op 写入（`WallRepo.markPublished`），`wall.unlock` 清空（`WallRepo.markUnpublished`）。非 null = 已锁定（前端 readonly） |
| `created_at / updated_at` | 时间戳；updated_at 在每次 op save 时刷新 |

---

## 9. Web 服务层

### 9.1 路由

| 路径 | 方法 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `/` | GET | 127.0.0.1 trust | 静态 `index.html` |
| `/assets/{file}` | GET | 127.0.0.1 trust | 静态资源（JS/CSS/字体）；含 `..` / `/` 拒绝 |
| `/api/session/{token}` | GET | token | 会话预握手，校验 token + 返会话元信息 |
| `/api/walls` | GET | 127.0.0.1 trust | 首页"近期项目"列表（全 walls summary） |
| `/api/templates` · `/api/template/{id}/preview.png` · `/api/template-asset/icons/{name}` | GET | 127.0.0.1 trust | 模板库 |
| `/api/palette` · `/api/font/{metrics,file,list}` · `/api/icon/{list,paths}` | GET | 127.0.0.1 trust | 调色板 / 字体 / 图标资源（无玩家鉴权） |
| `/api/wall/{id}/preview.png` | GET | 127.0.0.1 trust | wall 缩略图 |
| `/api/upload`（POST）· `/api/upload/url`（POST）· `/api/upload/quota` · `/api/upload/{source}` | POST/GET | **sessionId**（query 或 form 字段）| 图片上传 / 下载 / 配额；缺失或失效返 401 |
| `/api/project/import` | POST | **sessionId** + `canvas.edit` | `.canvas` 工程档导入（multipart `file`）；整体替换会话工程，破坏性写故要编辑权限（fail-closed，玩家离线即拒）。仅当 `AssetIngest` + 导入限额装配时注册。详见 §18 |
| `/api/variable/list-all-namespaces` | GET | **sessionId**（query，401 拒） | Provider declaredKeys 聚合（仅当变量系统装配时注册） |
| `/api/script/command-templates` | GET | **sessionId** | 命令模板列表，仅返 id/params，不泄 command 原文（仅当脚本系统装配时注册） |
| `/ws` | WS | auth 帧 + Origin 白名单 + 5s 超时 + IP 绑定 | 编辑器主通道 |

> **鉴权模型**：默认 `bind: 127.0.0.1`，本机非敏感资源（静态 / 模板 / 调色板 / 字体 / 图标 / wall 列表与缩略图）走 loopback trust 不鉴权；触碰玩家私有数据或可枚举内容的端点（上传文件下载 / 变量命名空间 / 命令模板）要求 `sessionId` 对应一个活 session，校验失败 401。公网部署须反代 + TLS。
>
> ~~`/health`~~：**未实装/规划中**（WebServer 当前无此路由）。

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
| 单次 confirm 部署 8×4 物品框 + 首帧 | < 500ms |
| 插件内存稳态（池 64 张） | < 100MB |
| 并发活跃会话 | ≥ 10 |
| 主线程 tick 时间增加 | < 1ms |

### 10.2 线程模型

- **主线程（Bukkit）**：物品框操作、PDC、MapView 生命周期
- **异步线程（插件 executor）**：渲染、调色板映射、WS I/O、SQLite
- **AnimationTicker（0.6 引入）**：独立 `ScheduledExecutorService`，按 `timeline.fps` 主动产帧（§5.5）；不访问非线程安全 Bukkit API（viewer 查询走 `world.getPlayers()` / `Player.getLocation()` 线程安全只读路径，双层 try-catch 收窄瞬态异常——与 ProjectionThrottler async 线程同纪律）；产帧后经 MapPacketSender 发送
- **推送**：异步线程构造 packet → PacketEvents 内部处理发送

**禁止**在异步线程调用 Bukkit API（除明确标注线程安全的）。

### 10.3 可观测性

- SLF4J + 配置文件控制 log level
- `/canvas stats` 管理员命令：池状态（total/free/reserved）、walls 计数（含 locked）、活跃会话数、活跃 token 数
- `/canvas cleanup` 管理员命令：**未实装/stub**（见 §4.3）
- `/canvas reload templates` / `/canvas reload config` 管理员命令：热重载模板 registry / config.yml（host/port 改动需重启生效）
- ~~`/canvas debug <sessionId>`~~：**未实装/规划中**（CanvasCommand 无此子命令）

> **命令族现状**（`CanvasCommand.build`）：玩家级 `edit / wand / confirm / cancel / open / list / alias / delete`（`publish / unpublish` 已于 2026-05-14 砍，锁定改走前端 `wall.lock/unlock` WS op）；管理员级 `stats / cleanup(stub) / reload {templates,config}`；另有按子系统装配挂载的 `/canvas var`（0.4.0 变量系统）与 `/canvas bench`（0.5.0 Benchmark）子命令族（对应 handler 为 null 时不注册）。
- 审计日志：`SESSION_BEGIN/CONFIRM/CANCEL` / `WALL_LOCK/UNLOCK/DELETE`（2026-05-14 起，旧 `WALL_PUBLISH/UNPUBLISH` 已随命令砍）/ `WALL_ALIAS` / `AUTH_OK/FAILED` / `POOL_*` / `IMAGE_UPLOAD_OK/REJECTED` / `PERMISSION_DENIED`，写 SQLite

### 10.4 安全

见 `security.md`，此处只列原则：
- 默认不暴露公网
- Token 单次使用 + 过期 + UUID 绑定
- **会话级 IP 绑定**：Session 首次 auth 时 CAS 绑定 caller IP，后续帧不一致 close 4001。绑 session 不绑 token——token 已单次使用 + TTL，再绑 token IP 是冗余；session 跨重连复用，绑定语义更稳定。已知限制：IPv6 norm + 反代 XFF 见 `security.md §2.5`
- WS 消息限流 + WS upgrade Origin 白名单 + 未认证 5s 超时 close 4001 auth_timeout
- 输入严格校验（字符长度、颜色格式、坐标范围）
- 权限节点细分

---

## 10.5 图层模型（协议 v2）

**心智模型：** ProjectState 不再持有扁平 `elements: Element[]`，而是 `layers: Layer[]`。每个 Layer 是一组共享可见性 / 锁 / 不透明度 / 混合模式的元素集合。Z-order 在两层：层间（`layers[i]` 的 i 越大越上层）+ 层内（layer.elements[j] 的 j 越大越上层）。

### 数据形态

```
ProjectState {
  version: long
  protocolVersion: int = 2
  canvas: Canvas {
    widthMaps, heightMaps, background
    gridSize: int?     // 0/null = 不显示。常用 8, 16, 32
    guides: Guide[]    // 用户从标尺拖出的参考线
  }
  layers: Layer[]      // 至少 1 个（创建 wall 时自动生成 "Default Layer"）
  activeLayerId: string  // 当前操作所在层；UI 状态，服务端只是中继
  history
}

Layer {
  id: "l-<uuid>"
  name: string             // 用户可改，默认 "Layer N"
  visible: boolean
  locked: boolean          // 锁后无法增删/移动元素，但 visibility / name 仍可改
  opacity: float           // 0.0 - 1.0
  blendMode: enum          // normal | multiply | screen | overlay
  elements: Element[]      // 层内 z-order = 索引
}

Element {
  // 现有字段 + 新增：
  opacity: float = 1.0
  blendMode: enum = normal
  renderMode: 'clean' | 'dither' = 'clean'  // 量化策略；详见 docs/rendering.md
}

Guide {
  axis: 'x' | 'y'          // 垂直 / 水平参考线
  position: int            // 像素坐标
}
```

### 生命周期

- **创建 wall（confirm 路径）**：自动生成 1 个 `Default Layer`，所有 element 落入该层；activeLayerId 指向它
- **template.apply（replace 语义）**：清空所有层 → 生成 1 个 `Default Layer` 包住模板物化结果。**不保留**旧的多层结构（与 replace 语义一致）
- **/canvas open 重新打开**：activeLayerId 沿用 DB 持久化值；若 DB 没有（v1 老画 migrate 后）→ 取第一个 layer
- **/canvas delete**：所有层一起删（layer 不跨 wall 共享）

### 渲染顺序

CanvasCompositor.rasterize 大体流程：

```
对每个 visible 层 (i = 0..n-1):
    若 layer.locked + layer.visible == false → skip
    为该 layer 分配临时 ARGB buffer
    对每个 visible element 按层内 z-order 顺序：
        画到 layer buffer（element 自己的 opacity / blendMode 在 element vs layer buffer 之间生效）
    应用 layer.opacity + layer.blendMode 合成到主 buffer

最后整张主 buffer 走 toPaletteSlice 量化输出
```

层内 element 与层间 layer 的合成两次执行，符合 PS / Figma 通用心智模型。

### activeLayerId 的服务端职责

- `state.snapshot` 下行携带 activeLayerId（UI 恢复用）
- 客户端发任何 `element.add` 不带 `layerId` 字段时，服务端默认落到 `activeLayerId`
- 客户端 `layer.set-active` op 更新 server-side 值（仅一个字段；不进 undo 栈）

### locked 层行为

- 服务端校验：locked layer 内的 element 收到 add/update/delete/reorder/transform 一律拒 `LAYER_LOCKED`
- locked 层自己仍可改 visible / name / opacity / blendMode / 解锁

### v1 老画迁移

启动期遇到老的 `project_json`（无 `layers` 字段、有 `elements` 字段）→ 自动包装：

```json
{
  "layers": [{
    "id": "l-<新 uuid>",
    "name": "Default Layer",
    "visible": true, "locked": false,
    "opacity": 1.0, "blendMode": "normal",
    "elements": [<原 elements 数组>]
  }],
  "activeLayerId": "l-<新 uuid>"
}
```

具体迁移脚本在 `docs/data-model.md`。

---

## 11. 配置文件骨架

> **以打包的 `plugin/src/main/resources/config.yml` 为权威**。本节是早期骨架，部分键名已与实现分叉，仅作结构示意。已知差异：`web.public-url` 实为 `web.editor-url`；`session.token-ttl` 实为 `session.token-ttl-minutes`、`session.idle-disconnect` 实为 `session.idle-minutes`；`render:` 段不存在（字体 / 调色板走构建期资源，非运行配置）；帧率限流在 `throttle.projection-fps`（默 5）而非下方 `render` 段。`map-pool` 段与泄漏扫描周期（硬编码 5 分钟）见 §3.6.2 / §4.5 已校正。

```yaml
# plugins/HikariCanvas/config.yml（示意，非逐字；权威见打包 config.yml）

web:
  bind: 127.0.0.1
  port: 8877
  context-path: ""                  # 反代时可设 "/canvas"
  public-url: "http://127.0.0.1:8877" # 生成给玩家的链接

# 实际 config.yml 用 `map-pool:` 段（旧 `pool.*` 名已废）。泄漏扫描周期不可配——
# 硬编码 5 分钟（HikariCanvas onEnable 的 mapPoolLeakTask）。
map-pool:
  initial: 64                       # 启动预创建张数（HikariCanvasConfig 钳 [1, 1024]）
  max: 256                          # 池上限；达上限新会话拿 POOL_EXHAUSTED
  per-world: {}                     # 可选：按 world name 覆写 initial（见 §3.6.2）

session:
  token-ttl: 15m
  idle-disconnect: 5m
  per-player-limit: 1

render:
  default-font: "sourcehan"
  palette-lut: "built-in"           # built-in | custom-path

timeline:                           # 0.6 引入
  default-fps: 20                   # 新建 timeline 的默认帧率（= 一个 Bukkit tick 50ms）
  max-fps: 60                       # 服务器级安全阀：单墙 fps 的硬上限，钳每条 timeline 的 fps
                                    #   （多租户保护，防单墙极端 fps 拖垮渲染线程 / 网络；不做自动降级）

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
- [ ] 会话 disconnect 5 分钟宽限是否太长（公网弱网场景 vs 池占用）
- [x] 多世界支持：同一池跨世界共享 vs 按世界分池 —— **按 world UUID 分桶**。MapPool 内部 `Map<UUID worldId, PoolBucket>`；`acquireForWall(World, ...)` / `bindToWall` 强校验 mapView.world 一致；跨世界绑定抛 IllegalStateException。config `map-pool.per-world: {}` 按 world name 覆写 size。详见 §3.6.2
- [ ] published wall 是否需要"自动归档"（长期无 op 自动 unpublish 释放给 `/canvas list` 滚动列表）—— 倾向不做，walls 数量 < 100 不需要
- [x] 协作编辑（多 client 同时编辑同一 wall_id）—— **永久不做**。`byWall` 排他锁阻止，玩家接力编辑（前一玩家 cancel 后下一玩家 `/canvas open <wall_id>`）已是现状
- [ ] 图层数 / 层内元素数上限（暂无；建议 layers ≤ 32、单层 elements ≤ 200 作为软上限做 warn 不做 hard cap）
- [ ] blendMode v1 选 normal/multiply/screen/overlay 4 个；其他 PS 风格 mode 等用户呼声
- [ ] **0.6 引入**：AnimationTicker 与编辑 op 并发改 state 的锁范式落点——倾向进 EditSession monitor、复用 `ProjectionThrottler.projectUnderEditLock`（`ProjectionThrottler.java:221`，`synchronized (es)`）的锁范式；实现时确认是否需要单列。对齐 `timeline.md §9` 风险登记

这些问题实现时根据实际情况回填本文档。

---

## 13. 动态画板设计约束

> **0.4.0 详细设计**：`docs/dynamic-data.md`（变量系统 + Push API + UX + 权限）。本节为顶层架构约束。

### 13.1 路径选择：P-1 + P-3 混合

**最终架构 = P-1 渲染期占位符 + P-3 Plugin Push API**（0.4.0 实施）：

- **P-1 渲染期**：TextElement.text 含 `${var:name}` / `${var:bedwars/score}` 占位符；`ProjectState` 仅存「模板字符串」不存「实际值」；`CanvasCompositor.drawText` 渲染前用 cached value 替换
- **P-3 Push API**：外部插件 `HikariCanvasAPI.setVariable(namespace, key, value, ttl)` 主动 push；HikariCanvas 写入 VariableStore + mark wall dirty 触发重画
- ✅ 与方案 C 兼容：渲染走 `CanvasProjector` 不经过 `SessionManager.open`
- ✅ Lock 锁的是「模板编辑」，不是「显示内容更新」
- ✅ 玩家 lock 后模板冻结，但每帧仍读最新动态值

**关键约束**：

1. **Push > Pull**：HikariCanvas 不做 active polling 外部数据；插件主动 push
2. **变量是 string**：HikariCanvas 不解析业务语义，纯字符串 forward
3. **不在主线程 resolve**：`ProjectionThrottler` 用 cached value 渲染（O(1) lookup）；async daemon 后台拉系统变量 / PAPI
4. **namespace 严格隔离**：插件注册 namespace 后只能 push 自己 namespace（防 spoof）
5. **fallback 链**：cached → `${var:X\|fallback=...}` → `Variable.default` → `"???"`

### 13.2 四层数据源（详见 dynamic-data.md §1）

- Tier 1 用户变量（`user/*`，持久化，玩家手动改）
- Tier 2 插件 push（`<namespace>/*`，HikariCanvasAPI.setVariable）
- Tier 3 系统变量（`server.*` / `wall.*` / `scoreboard.*`，内置）
- Tier 4 PAPI 桥接（`papi/%placeholder%`，自动暴露）

### 13.3 反模式（不允许）

**P-2 定时 patch ProjectState**：后台 task 每 N 秒 `EditSession.updateElement`。
**不允许** — 会撞 lock-aware open 鉴权 + 撑爆 history 栈 + 产生大量 WS 流量。

P-2 由变量系统取代。

**AnimationTicker（0.6，§5.5）不属于 P-2 反模式。** 它在 Ticker 线程内算**临时**插值 ProjectState 直接渲染（与 P-1 用 cached 变量值渲染同路），**从不** mutate 持久化 ProjectState、不进 history 栈、不发 WS 编辑流量。与被禁的"后台 task 定时 `EditSession.updateElement`"有本质区别。

详见 `docs/journal.md` 2026-05-16 + 2026-05-19 0.4.0 规划条目。

---

## 14. 前端工具栏 & 交互模式

LeftTools 工具栏分两组：

| 组 | 工具 | 快捷键 | 行为 |
| --- | --- | --- | --- |
| 非绘制 | `select` | V | 默认；点击 / 框选 / Transformer 锚点 |
| | `move` | M | 同 select 的拖动子模式，禁用 marquee |
| | `hand` | H | pan 模式；左键拖 outer scroll；与 marquee 互斥 |
| 绘制 | `line` / `arrow` / `circle` / `star` | — | drag-to-create |
| | `brush` | B | PointerEvent 接管 |

**Space 临时切 hand**：keydown Space 闭包 `spaceSavedTool` 保存原工具 → 切 hand；keyup 恢复。`e.repeat` 保护防 OS 长按重复；window blur 兜底防卡死；keydown preventDefault 防 Space 触发页面滚动。

**hand 工具下交互屏蔽**：hitConfig `listening: false`（与 drawTool 同路径）→ 左键穿透到 outer pan；onStageMouseDown 早 return 不启 marquee / draw；useTransformerManager 不挂 Transformer 锚点；cursor `grab` / 拖动时 `grabbing`。

**1024px 虚空白边**：CanvasView inner wrapper scoped CSS `padding: 1024px`；onMounted nextTick 主动 `scrollLeft/Top = (scrollWidth - clientWidth) / 2` 居中。fitToViewport 不读 scrollWidth 故不受影响。

---

## 15. 前端智能对齐 useSnapManager

`web/src/composables/useSnapManager.ts` 是 drag + resize 共用的对齐能力 composable。**O(n) 线性**，100 elements ≈ 1800 比较 / frame，无 spatial index（留 v1.x）。

### 15.1 候选轴（三类）

| 类 | 数量 | 候选 |
| --- | --- | --- |
| canvas | 6 | x: 0 / w/2 / w；y: 0 / h/2 / h |
| element（每元素） | 6 | x: left / cx / right；y: top / cy / bottom（按 `layer.visible && el.visible` 过滤，`excludeIds` 排除） |
| grid | 2 | gridSize > 0 时 floor / ceil 倍数 |

### 15.2 snapAxis 两遍扫描

1. **第一遍**：anchors（dragged 元素的 left/center/right）× candidates 找最近距离 ≤ threshold 的 `bestDelta`
2. **第二遍**：收集"应用 bestDelta 后恰好命中"的所有 candidate 作 `activeAxes`（多线同时命中 → visualizer 多线可视化）

### 15.3 distribute 间距均分（v2）

`findEqualGapX` / `findEqualGapY`：A.right < dragged.left 中 right 最大的 + C.left > dragged.right 中 left 最小的；A/C 必须同方向找到；span < w/h 跳过；**与同方向 axis snap 互斥**（axis 命中时不跑 distribute）。「任意三元素均分」留 v1.x 扩展。SnapHints 含 `equalGapX / equalGapY`（含 aRight / bLeft / bRight / cLeft + yCenter / x 镜像）。

### 15.4 resize snap

走 Konva Transformer 的 `boundBoxFunc(oldBox, newBox)` 钩子，**每帧拖锚点 call**（transformend 是结束时一次性事件无法做拖动反馈）。比对 newBox vs oldBox 找正在动的边（leftMoved / rightMoved / topMoved / bottomMoved），按边把 snap delta 应用到 `x or width` / `y or height`，对任何锚点（角 / 边中点）都正确无视觉跳动。

- `rotation != 0` → return newBox 跳过 snap（旋转后 bbox 不对齐画布轴）
- `w/h < 1` → return newBox
- 多选时 excludeIds 整组 selectedIds 排除

完整版（按锚点显式映射 + rotated bbox 支持）留 v1.x。

### 15.5 shift 临时禁用

CanvasView 内 window `keydown/keyup/blur` 维护 `isShiftDown` ref → 传给 snapManager 的 `bypass` 钩子，true 时 snap 透传 raw 坐标。

### 15.6 持久化偏好

ui store 字段：`snapEnabled / snapToGrid / snapToCanvas / snapToElement / snapToDistribute / snapThreshold`，localStorage key **`hikari-canvas:snap`**（与 theme / locale 同级），threshold 范围 [1, 64]，默认 `{enabled:true, toGrid:false, toCanvas:true, toElement:true, toDistribute:true, threshold:8}`。SnapSettingsPopover（TopBar Magnet 按钮）暴露 UI 开关。

### 15.7 视觉反馈

`SnapGuideOverlay.vue`（vue-konva v-layer + v-line / v-rect / v-text）挂载在 v-stage 内 marquee / drawPreview 同级独立 layer，覆盖 element / transformer 上方：

- snap axis 红色虚线 `#ef4444` + dash `[4,3] / zoom`
- equalGap 绿色实线 `#22c55e` + 两端短刻度 + 中间像素距离标签（白字 + 绿底圆角 pill）
- strokeWidth / fontSize 全部 `1 / zoom` 保持视觉密度

**fade**：drag / transform end 立刻清 `activeSnapHints = null`，layer `v-if` 自然卸载；CSS fade 留 v1.x。

## 16. Live Paint 子系统

油漆桶工具：用户点击画布上某个位置 → 系统识别该点所在的"闭合空白 gap"（被一组元素轮廓包围的连通区域）→ 生成对应 PathElement 并以当前 fill 填充；点击元素内部时直接修改该元素的 fill 字段（vector-fill 决策 A）。

### 16.1 设计纪律：前端独占

拓扑计算**完全在浏览器 Web Worker 跑**，后端 Java 不做镜像。这是 `docs/rendering.md §8.4` 显式记录的双端镜像例外。理由见 rendering.md §8.4。

### 16.2 算法管线

```
elements (visibleLayers, sorted)
   │
   ▼
ElementToPolygon       每个 element → polygon ring（含 rotation 应用）
   │                   rect: 4 顶点 / circle: 32 采样 / shape: 正多边形
   │                   star: outer/inner 交替 / path: M/L/Q/C/Z 自实装 de Casteljau
   │                   text/image/brush: bbox 兜底
   ▼
polygon-clipping.union 元素覆盖区域（occupied multipolygon）
   │
   ▼
canvas rect - occupied 差集 = gap multipolygons
   │
   ▼
RdpSimplifier          顶点 > 240 时迭代 tolerance 阶梯简化
   │
   ▼
gap polygons (cached in worker)
   │
   ▼ （用户点击）
pointInPolygon         O(n) ray-cast 找命中的 gap（含 hole 处理）
   │
   ▼
PolygonToPath          ring → SVG path d（M/L/Z + hole 用第二条 subpath）
   │
   ▼
element.add type=path  落库 + 后端 PathRenderer 渲染
```

退化输入（自交 / 共线 / 浮点累计误差导致 polygon-clipping 抛或返空）→ 返 `{gaps:[], degraded:true}`；UI 不创建 PathElement 而是显示「无法识别此区域」提示。

### 16.3 Web Worker 隔离 + lazy invalidate

- `livePaintWorker.ts` = module worker，discriminated union message（`build` / `result` / `error`）
- `useLivePaint.ts` Vue composable：`enabled` gate（仅 `paint-bucket` 工具激活时跑）+ debounce 100ms（element mutation 高频时合并）+ requestId race（最新请求胜出，弃旧 response）+ JSON 深 clone（隔离 store 反应式对象）+ `onScopeDispose` cleanup
- 不预热：用户切到 paint-bucket 工具时才首次 build；切走后保留最后一次 graph 直到下次 mutation

### 16.4 文件清单

`web/src/livepaint/`：

| 文件 | 职责 |
|---|---|
| `types.ts` | Polygon / Ring / Point / BuildRequest / BuildResult discriminated union |
| `ElementToPolygon.ts` | 单 element → ring；rect/circle/shape/star/path/text/image/brush 分支 + rotation |
| `LivePaintCore.ts` | union 占用 + difference 求 gaps + `pointInPolygon` ray-cast |
| `PolygonToPath.ts` | ring → SVG path d（含 evenodd hole）；gap → PathElement props |
| `RdpSimplifier.ts` | 迭代式 RDP（防递归爆栈）+ tolerance 阶梯简化到 ≤ 240 顶点 |
| `livePaintWorker.ts` | module worker entry，封装 build pipeline |
| `useLivePaint.ts` | Vue composable：debounce + race + enabled + dispose |
| `index.ts` | export 桶 |

`web/src/components/canvas/LivePaintHoverOverlay.vue` = hover hint（vue-konva v-path + `fillRule='evenodd'` + 蓝色半透明）。

### 16.5 vector-fill 决策 A

`findElementAt(canvasX, canvasY)` 倒序遍历 visibleElements（z-order 顶层优先）+ `elementToPolygon` + `pointInPolygon` 精确命中（非 bbox，避免 circle / star / path 角落误判）。

`onPaintBucketClick` 优先级链：

1. wall locked → 拒（`livePaint.wallLocked`）
2. graph 未就绪 → 拒（`livePaint.graphNotReady`）
3. graph degraded → 拒（`livePaint.graphDegraded`）
4. 命中 gap → `element.add type=path` + 当前 fill + 乐观本地 mutate
5. 命中 element：
   - `rect / circle / shape / path` → `element.update {patch:{fill}}` + 乐观本地 mutate（vector-fill 快捷，沿用 Fill 联合类型）
   - `text / image / brush` → `livePaint.elementUnsupported(type)` 提示（这些元素 fill 不是颜色平铺语义）
6. 都没命中 → `livePaint.noGap` 提示

不引入新 WS op：建路径走既有 `element.add`，改 fill 走既有 `element.update`，与协议正交。

### 16.6 性能与边界


- RDP 顶点上限 240（PathDValidator 实际阈值），tolerance 阶梯 0.5→1→2→4→8→16 直到达标
- 极小 gap 过滤 `MIN_GAP_AREA = 4 px²`（防点击噪声）
- DEV-only `console.debug` perf log（tree-shake prod 构建期由 `__DEV__` 常量剥掉）
- 100 elements 实测 build < 50ms（worker 内）；UI debounce 100ms 后调度
- 自交 path → ElementToPolygon 改用 bbox fallback（不把无效 ring 喂给 polygon-clipping）
- vitest 28 单测覆盖 ElementToPolygon / LivePaintCore / PolygonToPath / RdpSimplifier 四模块全部分支

---

## 17. 脚本运行时架构

> **契约总纲**：`docs/scripting.md`（D1-D8 决策 / 执行管线 §3 / Budget §2.4 / 命令白名单 §5 / 权限 §6）。本节仅固化脚本系统在整体架构中的定位与线程契约，不重复设计细节。

### 17.1 执行引擎三组件

```
游戏事件（GameEventListenerHub，主线程 MONITOR）
变量变化（VariableStore.ChangeListener，写方线程同步）
定时器（TriggerRouter 自持单线程 daemon，hikari-script-trigger）
playerNear 采样器（Bukkit 主线程 task，每 10 tick 扫距离）
        ↓
TriggerRouter — 路由层：按 (triggerType → wallId → ruleId) 双层索引 O(1) 查找，
               只存引用 (RuleRef = wallId:ruleId)，触发时刻 store.find 拿最新规则
               （防 stale rule 执行；rebuild 竞态窗口内残留索引无害）
        ↓
ScriptRunner — 单线程 SES（hikari-script-runner）：帧栈 + wait 续接（不睡线程）
              Budget 三闸（ABA 链深 / runs/s / actions/run）全部在 submit 入口处检查
              ThreadLocal CHAIN_DEPTH / RULE_KEY / TRIGGER_PLAYER 贯穿整个执行段
        ↓
ActionExecutor — 按动作类型分发：
  ├ setVariable / incrementVariable / log → VariableStore（async 安全，ChangeListener 同步回调）
  ├ setElementProperty → ElementPropertyApplier 双路径（§17.4）
  ├ playTimeline → AnimationTicker.play/pause/seek（现成线程安全入口）
  ├ playTimeline(await) → TweenScheduler（补间；§17.2）
  ├ playSound / runCommand → 主线程 hop（Bukkit.getScheduler().runTask）
  └ 三层异常隔离：单动作 throw → error step + WARNING log，链不断
```

### 17.2 三线程模型（脚本 + 时间轴 + 补间）

| 线程 | 名称 | 职责 | 跨线程契约 |
|---|---|---|---|
| ScriptRunner SES | `hikari-script-runner` | 脚本帧栈逐动作执行；wait 续接重入队列不睡线程 | 单线程串行化同墙副作用；写 VariableStore 触发 ChangeListener 在本线程同步回调（ABA 链深由此计量） |
| TweenScheduler SES | `hikari-canvas-tween` | 补间动画按帧推进；末帧落盘（`ApplyManyFn`） | 不访问非线程安全 API；与 Ticker 共享 `TickerControl` 线程安全入口；末帧落盘走 `ElementPropertyApplier` 主线程 hop |
| AnimationTicker SES | `hikari-canvas-ticker` | 时间轴关键帧插值产帧；viewer-gated 不 rasterize 空墙 | 见 §5.5；与脚本的关系：脚本 playTimeline 动作经 `TickerControl` 接口投递，不直接碰 Ticker 内部 |

**主线程 hop 要求**：playSound（`Bukkit.getScheduler().runTask` + `player.playSound`）和 runCommand（`Bukkit.dispatchCommand` console sender）必须在主线程执行，统一走 `Bukkit.getScheduler().runTask(plugin, runnable)`（plugin 为 null 时测试路径直接执行）。

### 17.3 触发器种类与游戏事件接入

| 触发器 | 路由来源 | 线程 | 倒排索引 |
|---|---|---|---|
| `variableChange` | `VariableStore.ChangeListener`（写方线程同步） | 任意（写变量的线程） | fullName → Set\<RuleRef\>（ConcurrentHashMap） |
| `timer` | TriggerRouter 自持单线程 daemon SES | `hikari-script-trigger` | — （每 timer 规则一个 ScheduledFuture） |
| `wallReady` | `TriggerRouter.fireWallReady`（启动恢复 + `SessionManager.confirm`） | 主线程 | 不进倒排——直查 store.listByWall |
| `playerJoin` | `GameEventListenerHub.onPlayerJoin`（MONITOR） | 主线程 | 全局 joinRules Set\<RuleRef\> |
| `playerKill` | `GameEventListenerHub.onPlayerDeath`（MONITOR，killer ≠ null） | 主线程 | 全局 killRules Set\<RuleRef\> |
| `playerQuit` | `GameEventListenerHub.onPlayerQuit`（MONITOR） | 主线程 | 全局 quitRules Set\<RuleRef\> |
| `playerNear` / `playerLeaveRange` | `PlayerNearSampler.tick`（Bukkit 主线程 task，每 2 tick 扫，按 sampleTicks 跳帧） | 主线程 | nearByWall → 扁平 nearSnapshot（volatile 整体替换） |
| `rightClickWall` | `GameEventListenerHub.onPlayerInteractEntity`（`ignoreCancelled=false`；PDC 反查 wallId） | 主线程 | rightClickByWall Map\<wallId, Set\<RuleRef\>\> |

`GameEventListenerHub` 是全部游戏事件的唯一入口类（MONITOR 优先级），本身无分支逻辑，只做一行转发给 TriggerRouter。右键墙触发器设 `ignoreCancelled=false` 是有意设计——`FrameProtectionListener` 会 cancel 所有 wall frame 交互，遵守 `ignoreCancelled=true` 则永远收不到右键墙事件。

### 17.4 setElementProperty 双路径

脚本改元素属性时走两条路径：

| 场景 | 路径 | 行为 |
|---|---|---|
| wall 当前有活跃编辑器 session | `EditSession` 标准 op | 副作用进编辑器 history 栈；UI 实时可见 |
| 无活跃 session（headless） | 直改持久化 `ProjectState` + `persistWall` | 不进 history；下次 open 可见 |

headless 路径的已知竞态（可接受）：若 session open/close 与 headless 写入并发，脚本改动可能被 session 旧 state 覆盖（单属性丢一次更新，低频低危，scripting.md §10 已记账）。

### 17.5 与时间轴和变量系统的关系

**脚本是上层，时间轴是被编排的素材（D2）**——对应 §13 动态画板路径中的反模式约定：

- 脚本 `playTimeline` 动作调 `AnimationTicker` 的公开线程安全入口；时间轴关键帧插值不感知脚本存在。
- 脚本 `setVariable` / `incrementVariable` 写 `VariableStore`；`VariableStore.ChangeListener` 回调 `TriggerRouter.onVariableChange`（ABA 链深在此计量）；渲染期 `Compositor` 读 VariableStore cached 值替换占位符（P-1 路径，同 §13.1）。
- **补间（TweenScheduler）**改 base ProjectState 字段；时间轴 KeyframeInterpolator 在 base 上叠加关键帧偏移。两层叠加不冲突——补间是"改终态"，时间轴是"在终态基础上周期插值"。
- **脚本不 mutate 时间轴的 `timeMs`（播放进度），只能通过 `playTimeline(play/pause/seek)` 命令式控制**。时间轴的进度只在 AnimationTicker 线程内推进，不允许脚本直接写。

### 17.6 反模式守则（对照 §13.3）

§13.3 已禁止"P-2 定时 patch ProjectState"（后台 task 定时 `EditSession.updateElement`）。脚本系统 `setElementProperty` 走 `ElementPropertyApplier` 双路径（§17.4），**不经 `EditSession.updateElement`**（不进 history 栈 / 不触发 WS 编辑流量）——属于 P-1 渲染期内嵌路径的合规延伸，**不是 P-2 反模式**。

脚本的正确定位：**条件分支 + 副作用**的上层调度器，使用时间轴（作为可播放素材）和变量（作为状态载体），不绕过这两个子系统直接写渲染状态。

---

## 18. `.canvas` 工程导入 / 导出

`.canvas` 是 HikariCanvas 的工程档格式——一个普通 zip，内含 `manifest.json`（spec/kind/wall 尺寸等元信息）+ `project.json`（整棵 `ProjectState`，多层 + 时间轴）+ 可选 `scripts.json`（墙脚本规则数组）+ 可选 `thumbnail.png`（256×128 缩略图）+ `assets/<hash>.png`（工程引用到的图片字节）。完整档案布局与 manifest 字段见 `docs/import-export.md`；本节只讲两条数据流与信任边界。

> **信任边界**：**导出在前端、导入在后端**。导出只是把已在内存的可信状态打成 zip 让浏览器下载，不经服务器；导入收的是用户上传的不可信 zip，所有防御（zip 炸弹 / 路径穿越 / magic 校验 / 元素与脚本重校验 / 配额）一律在 Java 侧做，前端不参与任何安全判定。

### 18.1 导出数据流（前端 fflate，不经服务器）

导出是纯前端动作（`web/src/composables/useProjectExport.ts` + `web/src/lib/canvasFile.ts`），用 **fflate** 在浏览器里 `zipSync` 客户端打包，再触发下载。服务器不参与打包，唯一的网络往返是逐张拉图片字节（复用已有的图片下载端点）。

```
useProjectExport.exportProject()
        │
        ├─ collectImageHashes(state)：扫所有 image 元素的 source hash（去重）
        │       │
        │       └─ 逐 hash fetch GET /api/upload/{hash}?sessionId=...  ← 唯一服务器往返
        │              （401/404/网络错误 → 静默跳过该图，导出不中断）
        │
        ├─ renderExportThumbnail(state) → 256×128 thumbnail.png（缩略图，best-effort）
        │
        ├─ buildManifest(state, …)：spec=CANVAS_SPEC(=1)、kind=project、wall 尺寸、
        │                            plugin_version 用 ready 时后端上报的 serverVersion（缺则省略）
        │
        ├─ scripts.json：useScriptStore().listSorted（服务端顺序的只读快照）；无脚本则省略
        │
        └─ assembleCanvasZip(...)：fflate zipSync({ manifest.json, project.json,
                                    [scripts.json], [thumbnail.png], assets/<hash>.png }, level 6)
                │
                └─ downloadBlob(zip, "<name>.canvas")  ← 浏览器直接下载，不上传
```

**关键不变量：**
- 前端 `CANVAS_SPEC = 1` 与后端 `ProjectImporter.CANVAS_SPEC_MAX = 1` 对齐；后端只接受 `spec ≤ MAX`，更高版本回 `IMPORT_SPEC_UNSUPPORTED`（提示升级插件）。
- 导出**不打安全闸**——它信任本端内存里的 `ProjectState`；所有炸弹 / 穿越 / 解码防御都留给导入侧。
- 缺图不致命：任一图片拉取失败优雅跳过，导出照常出一个不含该图字节的 `.canvas`。

### 18.2 导入数据流（后端 `canvasfile` 包，信任边界所在）

导入端点 `POST /api/project/import`（`web/ProjectImportHandler`）收 multipart `file` → 鉴权（sessionId 对应活 session + 该会话已绑 wall）→ 权限（live `Player.hasPermission("canvas.edit")`，玩家离线即 fail-closed 拒）→ 调 `ProjectImporter.importInto`。`canvasfile` 包按职责拆成单一职责的零件，`ProjectImporter` 把它们串成一条编排链：

| 零件 | 职责 |
| --- | --- |
| `CanvasArchive` | zip 流式安全解包：三闸（包 / 单条目 / 解压总量上限，单位由 `ImportConfig` 的 MB 换算）+ 边读边计数（不信 `entry.getSize()`）+ 路径校验（拒 `..` / 绝对路径 / 反斜杠 / NUL）+ 顶层白名单（`manifest.json` / `project.json` / `scripts.json` / `thumbnail.png` / `assets/`） |
| `CanvasManifest` | 解析 `manifest.json`，校验 `spec`（>0 且 ≤ `CANVAS_SPEC_MAX`，否则 `IMPORT_SPEC_UNSUPPORTED`）、`kind == project`、`wall` 尺寸合法；宽松忽略未知字段 |
| `ProjectMaterializer` | 把不可信 `project.json` 反序列化成 `ProjectState`（`@JsonCreator` 处理 v1/v2/v3 迁移），与会话当前墙尺寸做**匹配**（工程不得大于墙，否则 `IMPORT_SIZE_MISMATCH`），并对每个元素跑 `ElementValidator`（不信任任何元素数值，校验失败归 `IMPORT_MALFORMED`） |
| `AssetIngest` | `assets/*.png` 逐张安全摄入，走与图片上传**同等**的防御链：magic bytes 校验 → ImageIO 隔离解码（独立 daemon 线程 + 200ms 超时 + 解码前 8192 头部尺寸预检拦分配型炸弹）→ 规范化 PNG 后**按内容重算 hash**（不信文件名）→ per-hash 锁 + SERIALIZABLE 配额事务落库落盘。任一张失败**只跳过该张、不中止导入** |
| `ScriptImporter` | 导入 `scripts.json`：每条规则**重绑到目标墙**（经 `ScriptStore.create` 生成新 `sr-<id>` + 强制 wallId）+ **全量重校验**（结构 `ScriptRuleValidator` + 条件语法 `ConditionEvaluator.checkSyntax`）+ 命令模板缺失检查（缺则 `script-command-blocked` 但不跳过，运行期判 Blocked）+ 落 `wall_scripts`；配额超限 `script-quota` 即停 |
| `ProjectImporter` | **编排**：把上述零件串成完整导入链 + 孤儿轨丢弃 + 灌入会话 + 广播 / 投影 / 持久化 / 留痕（见下） |

`ImportWarning`（非致命提示）/ `ImportResult`（含 warnings 列表）/ `CanvasImportException`（致命失败，带稳定 `IMPORT_*` 码供端点映射 HTTP status）是这条链的公共数据载体。

```
POST /api/project/import (multipart file, sessionId)
        │
ProjectImportHandler：sessionId → 活 session（带 wall）+ canvas.edit（live Player，离线 fail-closed）
        │
        ▼
ProjectImporter.importInto(session, canvasBytes, uploader)
        │
   1) CanvasArchive.unpack ── 流式安全解包（三闸 + 路径校验 + 白名单）
   2) CanvasManifest.parse ── 校验 spec ≤ MAX、kind、wall 尺寸
   3) ProjectMaterializer.materialize ── project.json → 校验过的 ProjectState
            └─ 尺寸匹配当前墙（超墙 → IMPORT_SIZE_MISMATCH）+ 逐元素 ElementValidator
   4) AssetIngest.ingestAll ── assets/*.png 逐张摄入（magic + 200ms 隔离解码 + 8192 预检 + 配额 + 落 hash）
            └─ 摄入张数 < 请求张数 → asset-quota warning（差额跳过，不中止）
   5) 孤儿关键帧轨丢弃 ── timeline 里引用不存在 elementId 的 track 剔除 + orphan-track-dropped warning
   6) EditSession.replaceProject(imported) ── 整体替换会话工程，【保留多层 + 时间轴】
   6.5) ScriptImporter.importScripts ── scripts.json 重绑目标墙 + 重校验 + 落 wall_scripts（脚本 wall-scoped，不进 snapshot）
   7) push.pushSnapshot ── 全量快照广播下行（照 EditOpDispatcher OkSnapshot 分支，前端编辑器刷新）
   7b) ProjectionThrottler.submit(sessionId, dirty) ── 游戏内地图全画布重绘（否则玩家要等墙重载才见新内容）
   8) wallRepo.updateState ── 持久化（照 SessionManager#persistWall 的 DB 写）
   9) auditLog.record("PROJECT_IMPORT", …) ── 留痕（wall_id / spec / elements / assets）
        │
        ▼
   ImportResult{ warnings: [...] } → ctx 200 { ok:true, warnings }
   （CanvasImportException → IMPORT_* 码映射 HTTP：ZIP_TOO_LARGE→413、
     SPEC_UNSUPPORTED/SIZE_MISMATCH→409、BAD_ENTRY/MALFORMED→400）
```

`EditSession.replaceProject` 是导入专用的**保留多层**整体替换——区别于模板套用走的 `replaceContent`（把内容拍平成单层）；它采用 imported 的 canvas + 整棵 layers 树 + timelines + tweenFps，深拷贝 layers（替换后会话 state 与传入对象不再共享可变集合），bump version 并把替换前状态压 undo 栈，返回结构跳变 `OkSnapshot`。

### 18.3 装配（依赖注入）

- `HikariCanvas` bootstrap 处 `new AssetIngest(...)`，**复用已装配的图片栈**（`imageStorage` / `imageQuota` / `imageDao` / `wallRepo` / `jdbi`——与 `UploadHandler` 同一套），把它作为构造参数传给 `WebServer`。
- `WebServer` 构造内部 `new ProjectImporter` / `new ScriptImporter` / `new ProjectImportHandler`：`ProjectImporter` **复用 dispatcher 同款 push / throttler seam**——`OpPushCallback push`（与 `EditOpDispatcher` 共享的服务端主动推送）做 snapshot 广播、与 `EditOpDispatcher` **同一实例**的 `ProjectionThrottler` 做游戏内重绘、`wallRepo` 做持久化，收尾范式照搬 `EditOpDispatcher` 的 OkSnapshot 分支。
- 可空降级：`AssetIngest` 或导入限额任一缺失 → 不注册 `/api/project/import` 端点；`ScriptStore` 未配 → `scriptImporter` 为 null，工程档里的 `scripts.json` 被静默忽略（工程本体照常导入）；`auditLog` / `throttler` 为 null 时对应副作用 best-effort 跳过（不影响 replaceProject / pushSnapshot / 持久化主链，便于裸装配测试）。

### 18.4 已知限制

- **导入未走 `SessionManager.persistWall` 全链**：导入路径只投静态像素帧（`ProjectionThrottler.submit`），`persistWall` 里的 `AnimationTicker` 自动播刷新与触发器 rebuild **不**在导入时触发。故导入工程里的时间轴动画不会自动起播——需手动播一次，或随墙下次加载 / 会话回收时自然起播。
- **`thumbnail.png` 仅导出生成、导入侧不读取**（缩略图是给文件管理 / 未来工程库用的，导入不依赖它）。
- **`assets/icons/*.svg` 仅白名单接纳、不摄入**——`AssetIngest` 只摄 `assets/<file>.png`，SVG 条目虽过解包白名单但被忽略。SVG 导入是 **Part B**（尚未实装），本节只覆盖 `.canvas` 工程档的 Part A 实装。
