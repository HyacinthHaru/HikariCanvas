# HikariCanvas Ultrareview — 2026-05-15

**审查方法**：12 个独立子代理并行深度只读审查，9 完成 / 1 超时（WebServer 鉴权链）/ 2 被中断（渲染引擎、构建审计）。
**审查对象**：M0-M14 全部代码（plugin/src 102 Java 文件 16,207 行 + web/src 43 TS+Vue 文件 10,716 行）。
**范围限定**：只读审查，不修代码。

---

## 总体评分

| 维度 | 评级 | 理由 |
|---|---|---|
| 架构纪律 | ✅ 优 | NMS 零引用；paper-plugin.yml 新格式；SQL 100% 参数化；palette/font 构建期生成 |
| 默认配置 | ✅ 良 | 127.0.0.1 默认、配额三层都有、依赖均为 2026 较新版本 |
| 实装与文档落差 | ❌ 差 | 安全文档承诺多项未实装（IP-ban、CSRF、0.0.0.0 警告、PERMISSION_DENIED 错误码） |
| 鉴权链一致性 | ❌ 差 | sessionId 长期复用、`/api/walls` 公开、`/canvas open` 不检 owner、`wall.alias` 不检 owner、`canvas.delete.own` 节点漏注册 |
| god class 债 | ❌ 差 | EditSession 2013 行 / WebServer 1111 / CanvasView 1327 / CanvasCompositor 986 / RightPanel 1076 |
| 测试覆盖 | ⚠️ 不足 | 后端 29 测试集中在纯逻辑层；MockBukkit **完全没引入**（与 CLAUDE.md 不符）；前端 0 单测 |
| 文档同步 | ❌ 差 | M11-M14 加的 op、Element、Fill、错误码大量未进 docs/protocol.md |

**关键发现总数**：P0 真 bug 31 条 / P1 高危 ~25 条 / P2 设计债 ~30 条 / P3 边界 ~40 条。下文给出 P0 / P1 全部 + P2 重点。

---

## P0 — 真 bug / 远程可触发 / 数据丢失

### 数据丢失 / 状态错乱

**P0-1** 前端多选拖拽 silent data loss
- `web/src/components/layout/CanvasView.vue:540-555, 572-590`
- dragmove 已经 mutate `el.x/el.y` 成 newPos，dragend 判等 `if (otherEl.x !== otherX || otherEl.y !== otherY)` 永远 false → ws.send 永远不发 → 服务端不更新 → 刷新页面回弹
- 修复：用 init 位置而非已 mutate 的 element 位置作比较

**P0-2** wall.unlock ack 因 NON_NULL 把 lockedAt:null 丢字段
- `plugin/.../web/WebServer.java:917-920` 全局 `JsonInclude.NON_NULL`
- 前端 `wsClient.ts:244` 的 `'lockedAt' in p && p.lockedAt === null` 永远不触发
- 用户点 unlock 后 UI 不切回可编辑态，必须刷新页面
- 修复：ack 改 `{locked: false}` 显式布尔

**P0-3** wsClient.handleAck 字段嗅探污染锁定状态
- `web/src/network/wsClient.ts:240-249`
- 任意 op 的 ack 偶然带 `lockedAt: number` 都会改写 `project.lockedAt`
- 后端目前不发，但形结构耦合 — 一旦新 op 用同名字段就崩
- 修复：用显式的 op-specific dispatch table

**P0-4** EditSession brush size 改后 bbox 不重算
- `plugin/.../state/EditSession.java:1462-1484`
- applyBrushPatch 改 size 字段后 element bbox 不变 → 渲染艺术错位

**P0-5** BrushStroke duplicate 浅拷贝 points
- `plugin/.../state/EditSession.java:1765-1769`（cloneElementWithNewId）+ `template/TemplateInstantiator.java:238`
- 复制元素后两个 brush 共享同一 points list，一方编辑另一方跟着变
- 同样问题在 ImageElement.mask 字段

**P0-6** brush 切 active layer race
- `plugin/.../state/EditSession.java:1380-1396`
- brush.start 记下 buf.layerId，但前端通常传 null → endBrush 时用当前 active layer
- 玩家自己在 brush 进行中切层 → 笔触落到切后的 layer 而非画时的 layer

**P0-7** HistoryMark 被 trim 静默丢失
- `plugin/.../state/EditSession.java:809-820, 853-854`
- past 容量 16，mark 也占 slot；16 个 mark 后第一个 mark 被踢
- 用户用 mark 作锚点找不到回归点

### 远程 DoS / OOM（攻击者可直接触发）

**P0-8** brush 坐标无上界
- `plugin/.../web/WebServer.java:797-820` parseBrushPoints + `state/EditSession.java:1321-1334, 1362-1374`
- 仅校 `Double.isFinite`，未校 ±10K bbox；客户端可发 1e308 坐标
- AWT Path2D flattener 处理超大坐标时内部分段算法 OOM
- 修复：parseBrushPoints 加 `validateCoord(x, -1e4, 1e4)`

**P0-9** brush 通道绕过 rateLimiter + 无 WS message size 上限
- `plugin/.../web/WebServer.java:727-730` 注释明说"不走 rateLimiter"
- Javalin `wsCfg` 未设 `maxTextMessageSize`，Jetty 默认 64KB 但 brush.point 累积可分批
- 攻击者持续灌点 → state.layers 内 brush 元素无上限 → 内存增长
- 修复：brush.* 也接 rate limiter；wsCfg 显式设 max message size

**P0-10** Mask.d 允许坐标 100K 但 element bbox ≤ 10K
- `plugin/.../state/PathDValidator.java:30` MAX_COORD=100_000
- `plugin/.../state/EditSession.java:1714-1734` parseMaskNullable 不二次约束到 element bbox
- 渲染时 `Area.subtract(rect, hugePath)` 几秒到几十秒每个 image element × 16 element/wall
- 修复：mask path 加 vertex 数上限（≤64）+ 坐标钳到 [0, w] / [0, h]

**P0-11** RdpSimplifier 递归 5000 层接近栈深
- `plugin/.../state/RdpSimplifier.java:45-63`
- MAX_BRUSH_POINTS_PER_STROKE=5000，最坏情况递归深度 ≈ n
- JVM 默认栈 512KB，每帧 ~200B → ~2500 帧后 SO
- 修复：改迭代版本（栈数据结构模拟）

**P0-12** ImageIO 解码 200ms timeout 不真停线程
- `plugin/.../image/UploadHandler.java` decodeWithTimeout
- `Thread.interrupt()` 对 ImageIO 内部循环大多无效；timeout 之后 future.cancel(true) 不会真停
- 配合 P0-13 攻击者可让解码线程持续累积
- 修复：JNI/Process 隔离（重度但唯一可靠）或 IIORegistry 注销不需要的 reader

**P0-13** decoderPool unbounded
- `plugin/.../image/UploadHandler.java:82` `Executors.newCachedThreadPool()`
- 无最大线程数，无队列上限；恶意上传持续超时 → 线程数无限增长 → OOM
- 修复：`new ThreadPoolExecutor(2, 2, ..., new ArrayBlockingQueue(8), AbortPolicy)`

**P0-14** 磁盘配额 race
- `plugin/.../image/ImageStorage.java:191-213`（evictLruUntilUnder）+ UploadHandler
- 3 个查询（per-player 24h / per-wall / total disk）+ write 不在事务内
- 并发上传两个文件刚好踩满配额会双写超额

**P0-15** LRU evict 被攻击者堵死
- `plugin/.../image/ImageStorage.java:191-213` + `ImageUploadDao.java:143-156`
- 攻击者上传 16 张图并全部 element 引用 → `pickLruCandidates(16)` 16 行全 referenced → 全 filter 掉 → break
- 一旦磁盘配额满，**所有玩家**上传永久 fail
- 修复：SQL 用 `WHERE hash NOT IN (:referenced)` 而非内存 filter

**P0-16** wallPreviewCache 无界
- `plugin/.../web/WebServer.java:81`
- key = `wallId+updatedAt`，每次编辑产生新 key，旧 key 永不淘汰
- 注释"自然 LRU 容量靠 GC"是错的，ConcurrentHashMap 不因 GC 缩
- 修复：Caffeine `maximumSize(100).expireAfterAccess(5, TimeUnit.MINUTES)`

**P0-17** ImageStorage.load 渲染时无 timeout 隔离
- `plugin/.../image/ImageStorage.java:135`
- 仅上传路径包 200ms timeout；渲染 / WallRestorer / TemplatePreviewService 走 `ImageIO.read(file.toFile())` 裸调
- 磁盘上某 PNG 损坏 → 渲染主线程死锁
- 修复：所有 ImageIO.read 包装 timeout

### 鉴权破口

**P0-18** `SessionManager.open` 不检 owner
- `plugin/.../session/SessionManager.java:248-297`
- 任何玩家 `/canvas open w-deadbeef` 即可打开别人 wall
- 即使作者 lock 了，lock 是纯前端 readonly，`__hk.send('element.update', ...)` 一行绕过
- CLAUDE.md §lock-state 第 2 条："后端编辑 op **不读 lock**" — 设计本身的副作用是无权限隔离
- 修复：open 加 ownership 校验，或所有编辑 op 在 lock + 非 owner 时拒 FORBIDDEN

**P0-19** `/canvas alias <name>` 无 ownership 校验
- `plugin/.../command/CanvasCommand.java:310-332`
- Bob 用 P0-18 路径打开 Alice 的 wall 后，`/canvas alias bob-stole-it` 直接改名 Alice 的 wall
- 修复：runAlias 先校验 caller == owner_uuid

**P0-20** `canvas.delete.own` / `canvas.delete.any` / `canvas.alias.own` 等节点未在 paper-plugin.yml 注册
- `plugin/.../resources/paper-plugin.yml` + `command/CanvasCommand.java:129, 343, 377`
- 未注册节点 Paper 默认 `PermissionDefault.OP` → 普通玩家无法删自己的 wall（与 docs/security.md:276 声明"继承 canvas.edit"严重不符）
- 修复：补全节点 + 设 `default: true`

**P0-21** `/api/upload/{source}` 无 sessionId 校验
- `plugin/.../image/UploadHandler.java:234-251`
- 知道 hash 即可下载（hash 是 64-bit secret-by-knowledge）
- 配合 P0-22 文档泄漏路径，攻击者拿到 hash 后跨 session 访问
- 设计如此但 docs/security.md §2 未承认这是"capability by URL"模型

**P0-22** `window.__hk` 生产构建仍存在
- `web/src/App.vue:32, 43`
- 无 `import.meta.env.DEV` 守卫；CLAUDE.md:183 公开记载 `window.__hk.send("op", payload)` 作为调试入口
- 任何第三方脚本 / 浏览器扩展 / nginx 反代注入可一行删 wall 元素
- F12 console 一行绕过所有前端 UI 守卫（包括 lock）
- 修复：包裹 `if (import.meta.env.DEV)` 或 build-time strip

**P0-23** TemplateInstantiator `instantiateRawState` 绕过所有 EditSession 校验
- `plugin/.../template/TemplateInstantiator.java:129-168`
- M14 模板的 `raw_state` 字段被 `mapper.convertValue(rawMap, ProjectState.class)` 直接反序列化为 ProjectState
- 然后 `replaceContent(ok.backgroundColor(), ok.elements())` 不做 PathDValidator / IMAGE_SOURCE_RE / mask 校验
- 任意持 `canvas.template.save` 权限玩家（默认 default:true）可发布的 user 模板可注入：
  - `ImageElement.source: "../../../uploads/<别人的hash>.png"`（绕过 16-hex 正则）
  - `PathElement.d: "M 99999999999 ..."`（绕过 PathDValidator 4096 长度 / 100K 坐标）
  - `Mask.d` 任意字符串
- 修复：`replaceContent` 接收 `List<Element>` 后必须对每个 element 跑一次 EditSession-grade 校验

### MapPool 致命问题（CLAUDE.md 自己说"做不好整个项目报废"）

**P0-24** `MapPool.detectLeaks` 从未接线
- `plugin/.../pool/MapPool.java` 有 detectLeaks 方法但没人定时调用
- grep `detectLeaks` 在 plugin 源里只命中定义点 + 0 个调用方
- idcounts.dat 膨胀的最后防线完全没启动
- 修复：`Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> mapPool.detectLeaks(wallRepo.loadAllIds()), 0, 6000)` (5 min)

**P0-25** releaseWall / deleteWall 不清 HikariCanvasRenderer 缓存
- `plugin/.../render/HikariCanvasRenderer.pixelsByMapId` 持像素 buffer
- `pool/MapPool.releaseWall` + `session/SessionManager.deleteWall` 仅清 pool_maps / walls，不清 renderer 缓存
- mapId 借给新 wall 后，HikariCanvasRenderer 仍返回旧像素 → 跨 wall 像素泄漏（A 的画显示在 B 的 ItemFrame）
- 修复：releaseWall/deleteWall 路径加 `canvasRenderer.invalidate(mapIds)`

**P0-26** `MapPool.persist` 失败时内存与 DB 不一致
- `plugin/.../pool/MapPool.java:323-338`
- 调用方（reserveForWall:170 / releaseWall:218 / bindToWall:198）已 `byId.put(...)` 改内存
- persist 异常被吞 + 内存修改不回滚 → 内存说"已 RESERVED"，DB 说"FREE"
- 重启后 MapPool.initialize 从 DB 读回 FREE → 同一 map 借给两个 wall

### DB / 迁移

**P0-27** MigrationRunner `split(";")` 不识别引号内分号
- `plugin/.../storage/MigrationRunner.java:79`
- 未来一个 `INSERT VALUES ('a;b')` 会被截断成两半，分号后那段独立 execute
- 注释 strip 是按完整行做的，行尾才进入注释的 SQL 不会被剔除
- 修复：用 `org.sqlite.SQLiteConnection.execute(String)` 或手写 lexer 跳过引号块

**P0-28** 整个迁移不在事务里
- `plugin/.../storage/MigrationRunner.java:46-64`
- JDBI `useHandle` 在 SQLite 默认 autocommit，每个 `h.execute` 独立提交
- V005 中间任何一条失败（电源 / OOM / SIGKILL），前面已 DROP 的 `sign_records` / `drafts` 永久没了
- schema_version 不写入是好的，但下次启动重跑 V005 时源表已不存在 → 失败模式完全不同
- 修复：每个 migration 用 `h.useTransaction(...)`

**P0-29** V005 不可逆破坏迁移无备份
- `plugin/.../resources/db-migrations/V005__walls_unified.sql:8-10, 37`
- DROP TABLE drafts + sign_records + pool_maps，**没有任何数据搬运 SQL**
- 内部 M5.5 之前的部署升级会让玩家所有草稿 / 已发布 sign 直接抹掉
- 修复：加 V005-a 先 COPY 到 `walls_v5_backup_*` 备份表，30 天后再清；或 MigrationRunner 在 DROP 前自动 `cp data.db data.db.pre-V<NNN>`

**P0-30** TemplateRepo.listMarketplace 用 `+ limit` 拼 SQL + SELECT \*
- `plugin/.../storage/TemplateRepo.java:96-98`
- limit 是 int 不构成真注入，但 `SELECT *` + ctx.json 直接把 owner_uuid 等敏感字段返到公开端点 `/api/templates`
- 修复：bindList 命名参数 + 显式列名

**P0-31** 没设 busy_timeout
- `plugin/.../storage/Database.java:38-47`
- SQLite 在 WAL 模式下 2+ 并发写立即抛 `database is locked` / `SQLITE_BUSY`
- HikariCP 池 4 连接，ImageStorage.evictLruUntilUnder 嵌套 3+ 查询 + WallRepo.updateState 高频 + AuditLog 并发 → 必撞
- 所有 DAO catch 后 log.warning 吞掉 → 上游收到 success ack 但实际数据丢
- 修复：`cfg.addDataSourceProperty("busy_timeout", "5000")`

**P0-32** WallRepo.create + updateMapIds 不在事务里
- `plugin/.../session/SessionManager.java:204-213`
- create 写 walls 一行（mapIds=""），然后 reserveForWall，然后 updateMapIds
- updateMapIds 失败 → 僵尸 wall（DB 行存在 + map_ids 空），pool 4-8 张 map 永久 stuck RESERVED
- 修复：`jdbi.useTransaction(h -> { create + updateMapIds in one handle })`

**P0-33** AuditLog.record 异常被吞
- `plugin/.../storage/AuditLog.java:41-50`
- 注释说 fire-and-forget 但实际不 catch，JDBI useHandle 异常向上传，调用方也不 catch
- DB BUSY 时安全事件静默丢失，无 file fallback
- 修复：catch 后 `log.severe` 至少落到 server.log

---

## P1 — 高危但需要前置条件 / 配置错误

### 上传 / Image

- **PNG magic bytes 只校验 4 字节**（应 8）— UploadHandler:303-320 任意 `89 50 4E 47` 开头都被认作 PNG
- **deleteHash 失败时 DB 删了但文件留下** — ImageStorage:169-180，孤儿文件永远占磁盘
- **wall.delete 不清理 image 引用** — image_uploads.refcount 字段建了但 v1 不维护，wall 删后图片只在下次 LRU 触发才清
- **wallPreviewCache 触 touchLastUsed 副作用扭曲 LRU** — 攻击者频繁访问 preview 端点把自己的 hash 永远顶到 LRU 末尾
- **multipart sessionId 不限长** — formParam("sessionId") 可发 10MB 字符串，String.hashCode O(n) 占用 CPU
- **downscale 之前的解码占满堆** — 8000×8000 PNG 解码后 256MB transient，并发 20 → 5GB
- **Files.write 非原子** — 两个并发线程同 hash 写时 TRUNCATE_EXISTING 竞态

### MapPool / Deploy

- **`MapPacketSender.sendFullMap` 死代码** — PacketEvents 依赖整个浪费（仅 onLoad/onEnable/onDisable 调生命周期方法）
- **重启后 ItemFrame mapId 指向已 release 的 map** — 服务器 crash 在 releaseWall 之后但 removeForWall 之前，重启后 mapId=42 被分给新 wall，旧 ItemFrame 显示新 wall 像素
- **WallRestorer bindToWall 失败仍推像素** — pool 状态可能错但视觉先恢复
- **WebSocket forget hook 不通知 close** — SessionReaper cancel 时 stale wsBySession 残留
- **walls.map_ids CSV 字段无校验** — 脏数据导致 slice 越界

### 命令 / 权限

- **`/canvas open <alias>` 越权打开他人 wall**（P0-18 同根因，独立列因攻击路径不同）
- **delete 第一步泄漏 wall 存在性 + alias** — CanvasCommand:334-356，区分"不存在"vs"不是你的"消息
- **pendingDeletes 内存态跨服重启 / config reload 失效** — 30s 窗口不持久化，多人并发 confirm 写 double-audit

### Template

- **server 模板被 syncBuiltinToDb 标 builtin=true 永远删不掉** — TemplatePublisher:171-180 把 BUILTIN+SERVER 都标 builtin，admin 无法 unfeature / delete
- **AutoTextAction.label / description 无 sanitize** — 透传到 yaml → 前端 fetch → 潜在 stored XSS（依赖 Vue 自动转义保命）
- **单模板 YAML 大小无上限** — 默认 default:true 权限可发 20×大文件
- **`Pattern.compile(p.pattern())` ReDoS** — 模板 pattern 用户控，`(a+)+$` + 几十字符输入卡死 WS worker
- **replacePlaceholders / deepCopyMap 递归无深度上限** — yaml 嵌套 5000 层 → StackOverflowError
- **TemplatePreviewService 无认证 + 首次同步阻塞** — 16×16 maps × 多模板首次访问可 burst 320MB

### 前端

- **iconCache / imageCache 模块级 Map 无 LRU** — PreviewRenderer:428, 488，每个 HTMLImageElement 持几 MB，切 wall 不清
- **CanvasView 无 onBeforeUnmount cleanup** — rAF / BrushController / document.fonts.ready / hook 全泄漏
- **100MB 上传冻结主线程** — FileReader.readAsDataURL 同步读 + 无 progress + 无 AbortController
- **paste 路径只信 item.type** — 可塞 image/svg+xml 等，后端拒但前端 UI 等一次 round-trip
- **attachTransformer 深度 watch elements** — 多选拖拽时每次 mutate 都重挂 transformer
- **wsClient send 失败 silent drop** — readyState !== OPEN 时只 pushLog，调用方不感知

### 协议 / 类型

- **RATE_LIMITED / POOL_EXHAUSTED 错误的 `retryable` 始终 false** — Envelope.java:30 写死 false，违反 protocol.md §6.1
- **`pathScale.ts:10` 正则缺小写 `m`** — 相对 move-to 路径 resize 时数据破坏，后端 PathDValidator 拒，前端本地状态 drift
- **wsClient.ts:287 判 `ev.code === 4008`** — protocol 只定义 1008，4008 永不发生，rate-limit close 被当 retryable 错处理
- **13 个错误码代码发但 docs 没列**：FORBIDDEN / INVALID_ALIAS_FORMAT / UNEXPECTED / TOO_MANY_STROKES / INVALID_STROKE / STROKE_TOO_LONG / QUOTA_PER_WALL / QUOTA_PER_DAY / QUOTA_DISK_FULL / QUOTA_EXCEEDED / NOT_FOUND / DB_FAILED / WRITE_FAILED 等
- **`PERMISSION_DENIED` docs 写了但代码从不发** — 实际用 `FORBIDDEN`
- **13 个 op 后端实装但前端无 UI**：element.move-to-layer / canvas.background / canvas.guides.set / canvas.resize / history.mark / template.delete / template.feature / template.unfeature 等
- **§5.9 brush 段彻底过时** — payload 字段、固化类型（BrushStrokeElement 不是 PathElement）、cancel 全错

---

## P2 — 设计债 / 性能 / 一致性

### god class 拆分（最痛的债）

**EditSession.java 2013 行** — 建议拆 6 文件：
- `EditSessionDispatcher` — op routing
- `ElementValidator` — 所有 field validation（关闭 P0-23）
- `LayerOperations` — layer.* op
- `BrushSession` — brush.* op + 笔触会话状态机
- `HistoryStack` — undo/redo + mark
- `StateGuards` — concurrency / lock

**CanvasView.vue 1327 行** — 建议拆 10 composable + 子组件：
- `CanvasGridOverlay.vue` / `CanvasZoomBar.vue` / `CanvasUploadGateway.vue` / `TextInlineEditor.vue`
- `useMarqueeSelection()` / `useDrawToCreate()` / `useBrushHost()` / `useTransformerManager()` / `useCanvasShortcuts()` / `usePanScroll()`

**RightPanel.vue 1076 行**、**WebServer.java 1111 行**、**CanvasCompositor.java 986 行** — 同样需要拆分。

### DB / 性能

- **外键约束完全没建** — `foreign_keys=true` 白设；删 template 后 walls.template_id 悬挂引用
- **updateState 每个 op 都写整个 ProjectState JSON** — 1 MB/sec × 4 并发会话 = 4 MB/sec 写盘，WAL checkpoint 抢锁延迟尖刺
- **loadByMapId 用 `%xxx` LIKE 全表扫** — wand 瞄 ItemFrame 热路径，1000 walls 时 600ms/查
- **HikariCP 未设 leakDetectionThreshold / connectionTimeout / maxLifetime** — 业务线程等连接 30s 超时被吞，无法察觉池耗尽
- **schema_version 表无 checksum** — 不能检测旧迁移 SQL 被偷改

### 测试

- **MockBukkit 完全没引入** — 与 CLAUDE.md 声明的"JUnit 5 + MockBukkit"不符
- **前端 0 单元测试** — `scalePathD`、PathParser、`canonicalCharWidth` 双端一致性全无测试
- **上传 HTTP 全链路 / WS auth / 并发 / 鉴权全无集成测试** — UploadHandlerHelpersTest 自己说"需 JavalinTest 依赖，未引入"
- **FrameDeployer 邻接 frame regression** — 需 MockBukkit world/Entity 设施，未补
- **wall.lock owner-only mock** — 未补

### 配置 / 部署

- **config reload 几乎所有字段都需重启** — host/port/editor-url/token-ttl/idle-minutes/map-pool/throttle/templates/images 全 final，提示"host/port changes require server restart"严重低估
- **paper-plugin.yml 未声明 packetevents depend** — 启动时 PacketEvents 未加载 → onLoad NoClassDefFoundError
- **api-version 偏低** — paper-plugin.yml 写 `1.21` 应 `1.21.11`
- **wall_id 32-bit 生日攻击** — 65K 行 50% 碰撞，5x 重试在 10K+ wall 时不够
- **canvas.commit 是死节点** — paper-plugin.yml:22-24 残留 M5.5 之前的命令

### 渲染 / 一致性

- **mask inverted 双端 fill rule 不等价** — 后端 `Area.subtract` vs 前端 `evenodd`，复杂自交叉/多 subpath 时结果不一致
- **smoothing=0 → epsilon=0 → 不简化** — 笔触渲染时 5000 段 BasicStroke
- **scalePathD 4 位精度让 d 长度膨胀** — 多次拖动后超 4096 字符上限
- **TemplateAssetService cache 无 LRU**
- **memCache 过期 entry 不主动清** — 仅 load 命中过期才清

---

## P3 — 边界（节选 10 条最值得注意的）

- **PathDValidator MAX_COORD=100K vs EditSession.MAX_COORD=10K 不一致** — mask 可写超 bbox 坐标
- **PathDValidator.normalize toLowerCase 改变 d 语义** — trap-prone API
- **PathParser C 命令起点切线退化无 fallback**（Q 有）
- **alias 命名空间与 `w-<hex>` 形态重叠** — 玩家可设 alias=`w-12345678` 造成视觉混淆
- **`/canvas open` 复用旧 session 时仍签发新 token** — 旧 token 仍可被旁人抢先消费造成 session 抢占
- **WorldUnloadEvent 不监听** — 卸载世界后 wall 无法 open
- **MapPool.expand 写死 worlds[0]** — 多世界场景 metadata 漂移
- **PathDValidator 无命令数上限** — 仅 4096 字符长度
- **HASH_RE 16 hex (64 bit)** — 4B 张图 50% 碰撞，第二个上传者 silently 引用第一个
- **i18n 全英文硬编码** — 命令族 36 处 `Component.text("...", NamedTextColor.X)` 无 TranslationRegistry

---

## 修复优先级建议

### 立即（30 分钟内 + 单行修复）

1. **`pathScale.ts:10`** 加 `m` 进正则（一行）
2. **`App.vue:32`** 把 `window.__hk` 包 `if (import.meta.env.DEV)`（两行）
3. **`Database.java`** 加 `cfg.addDataSourceProperty("busy_timeout", "5000")`（一行）
4. **`paper-plugin.yml`** 加 `dependencies.server.packetevents.load: BEFORE` + 注册 `canvas.delete.{own,any}` / `canvas.alias.{own,any}` 节点 + `api-version: '1.21.11'`

### 本周

5. **`SessionManager.open`** 加 owner 校验（或所有编辑 op 在 locked 时拒非 owner）
6. **`wall.unlock` ack** 改 `{locked: false}` 显式布尔
7. **`MapPool.detectLeaks`** 接 BukkitScheduler 定时
8. **`releaseWall` / `deleteWall`** 路径清 `HikariCanvasRenderer.pixelsByMapId`
9. **`EditSession.replaceContent`** 引入 `ElementValidator.validate(element)` 关闭 P0-23 模板破口
10. **brush 路径**：parseBrushPoints 加 `validateCoord` + WS message size cap + brush.* 接 rateLimiter
11. **`MigrationRunner`** 每个 migration 包 `useTransaction` + SQL splitter 识别引号
12. **`AuditLog`** catch 后 file fallback log

### 本月

13. **TemplateRepo.listMarketplace** 改 bindList + 显式列名隐藏 owner_uuid
14. **`updateState`** coalesce 写入（dirty flag + 1s flush 而非每 op）
15. **`/api/walls` / `/api/templates`** 加 sessionId 校验或 owner-only
16. **wallPreviewCache** 改 Caffeine（max 100 + TTL 5 min）
17. **decoderPool** 改有界 ThreadPoolExecutor
18. **HikariCP** 完整配置 leakDetection + connectionTimeout
19. **docs/protocol.md** 全面同步（M11-M14 加的所有 op + Element 类型 + Fill 类型 + 18 个错误码）

### 长期重构（M15+）

20. **EditSession 拆 6 文件**（ElementValidator / LayerOperations / BrushSession / HistoryStack / StateGuards / Dispatcher）
21. **CanvasView 拆 10 composable + 子组件**
22. **DAO 层"吃异常返空"模式整体改成抛出**
23. **引入 MockBukkit + JavalinTest** 补 HTTP / WS 集成测试 + frame regression 测试
24. **wall_id 扩到 12 hex**（48 bit）
25. **walls.map_ids CSV → wall_maps 桥接表**（O(1) loadByMapId）
26. **MapPacketSender 死代码**：要么真用 PacketEvents 发包，要么彻底拆掉这条依赖

---

## 补充审查（2026-05-16 第二轮）

第一轮 12 个代理中超时 / 中断的 3 个域已在第二轮补完。下列发现编号从 P0-23 续起，不与第一轮 P0-1~P0-33 冲突。

### A. WebServer 鉴权链（11 条新发现）

#### P0 鉴权 / 远程未授权

**P0-Web-1** Token consume TOCTOU 让多连接活跃同 session
- `plugin/.../session/TokenService.java:106-135` + `web/WebServer.java:1001-1023`
- `tokens.replace(token, rec, marked)` CAS 之后到 `markActive` 之前有窗口；两个并发 WS auth 极短时间内都能读到未标 used 的 Record
- 即使第二个最终被 `markActive` state 检查拒，wsBySession 已被覆盖，第一个连接的 ctx 被踢
- 破坏 "per-player 1 active session" 约束

**P0-Web-2** 预握手明文返回 sessionId + sessionId 长期复用
- `web/WebServer.java:302-328`
- `GET /api/session/:token` 用 `peek` 不消耗 token，返回 sessionId UUID 明文
- token rotate 后 reconnectToken 改了，但**仍绑同一 sessionId**
- 嗅探 HTTP 响应 / 浏览器历史 → 拿到 sessionId → 配合 P0-21（上传无 token 校验）跨 session 操作

**P0-Web-3** `/api/upload/quota` 仅凭 sessionId query 鉴权
- `plugin/.../image/UploadHandler.java:255-266`
- `resolveSession` 只检查 sessionId 是否在 map 中，不验证请求者身份
- 嗅到他人 sessionId 即可查其上传配额（perDay/perWall/totalDisk）
- 同样问题在 POST `/api/upload`：只校 sessionId 存在性 + `Bukkit.getPlayer(uploader)`，玩家离线时不检 permission

**P0-Web-4** WS auth 成功到 wsBySession.put 之间 session 可被 cancel race
- `web/WebServer.java:1010-1025`
- `byId()` 返对象引用；其他线程（`/canvas cancel` / SessionReaper）可在期间把状态改 CLOSING + releaseLocks
- L1019 state 检查走 else 分支 `touch()` 而非 `markActive`，但 L1025 `wsBySession.put` 无论如何执行
- 结果：CLOSING 状态的 session 仍被纳入 wsBySession，pushPatch/pushSnapshot 路由到已死 session

#### P1 信息泄漏 / DoS

**P1-Web-1** 预握手响应字段过详
- `web/WebServer.java:317-327`
- 返回 sessionId / playerName / wall 几何 / mapIds / 完整 templates 列表 / wsUrl
- HTTP 响应可被中间人 / 浏览器扩展 / nginx 日志记录
- 攻击者凭此识别特定玩家画布、定位 MC map ID、构造针对性 payload
- 修复：预握手只返 `{ ok: true, wsUrl }`，敏感信息走 ready 帧

**P1-Web-2** 无 HTTP 安全头
- `web/WebServer.java:125-248` 全无 `ctx.header(...)`
- 缺 CSP / X-Frame-Options / X-Content-Type-Options / Referrer-Policy / Strict-Transport-Security
- iframe 嵌入 clickjacking、MIME-sniffing（SVG 当 HTML 执行）、referer 泄漏全敞着

**P1-Web-3** WS 消息大小无显式上限
- `web/WebServer.java:231-247` wsCfg 未设 `maxTextMessageSize`
- Jetty 默认 64KB 但 brush.point 注释明说"不走 rateLimiter"
- 单个 brush.point 含 10K 坐标 ≈ 1 MB+，Jetty 缓存内存可被刷爆
- 修复：`wsCfg.maxTextMessageSize(65536)` + brush.* 接入 rateLimiter（与第一轮 P0-9 重叠加强）

**P1-Web-4** Exception.getMessage 直接返客户端
- `web/WebServer.java:368, 439, 556, 582, 741, 764` + `image/UploadHandler.java:151, 171, 221`
- `"malformed envelope: " + e.getMessage()` 等模式泄漏文件路径 / 类名 / JVM 内部信息
- 修复：错误消息改通用，详情仅 server log

#### P2 配置缺陷

**P2-Web-1** WS onConnect 无认证前限制
- `web/WebServer.java:232` `onConnect(ctx -> log.info("WS connected"))` 裸连无任何检查
- 未认证连接持续占 Jetty WS 句柄；发垃圾 JSON 引发 P1-Web-4 异常路径
- 修复：onConnect 设 30s 强制 auth timeout + 限预认证连接数

**P2-Web-2** SessionRateLimiter bucket 永不清理
- `session/SessionRateLimiter.java:27` + `SessionManager.java:507-514`
- SessionManager 有 forgetHooks 机制，但 WebServer 构造时**没注册** `rateLimiter::discardSession`
- session 消亡后 Bucket 仍在 ConcurrentHashMap，长期运行内存膨胀

**P2-Web-3** ObjectMapper FAIL_ON_UNKNOWN_PROPERTIES disabled + 无深度限制
- `template/TemplateLoader.java:54`
- 攻击者发深嵌套 JSON（1000+ 层）→ Jackson StackOverflowError
- 修复：`JsonFactory.setStreamReadConstraints(maxNestingDepth=20)`

**P2-Web-4** 无 max session 数量限制
- `session/SessionManager.java:85-99`
- 僵尸账户不断 `/canvas edit`，session 积压等 reaper 清；配合 wallPreviewCache 无界（第一轮 P0-16）快速 OOM
- 修复：`MAX_SESSIONS = 10000` 硬上限

#### P3 边界

**P3-Web-1** asset 路径黑名单易绕过
- `web/WebServer.java:143-151` 只检 `/` 和 `..`，未检 null byte、不限扩展名
- classpath 资源难穿越但应改白名单 `^[a-zA-Z0-9._-]+\.(js|css|png|woff2)$`

---

### B. 渲染引擎 / 字体 / palette（13 条新发现）

#### P0 OOM / 主线程死锁

**P0-Render-1** Canvas widthMaps/heightMaps 完全无上限 → 远程 OOM
- `plugin/.../state/ProjectState.java:49-76` Canvas record 零校验
- `render/CanvasCompositor.java:136` `new BufferedImage(widthMaps*128, heightMaps*128, RGB)`
- 攻击者发 `canvas: {widthMaps: 1000, heightMaps: 1000}` → 128000×128000 = **48 GB heap** → JVM 直 OOM 服务器崩
- EditSession `MAX_COORD=10K` / `MAX_DIM=10K` 只管 element，**未限 canvas 尺寸**
- 修复：Canvas 构造器加 `widthMaps/heightMaps ∈ [1, 32]`

**P0-Render-2** dither element buffer 按整 canvas 分配
- `render/CanvasCompositor.java:384-404` `drawDitheredElement`
- 注释说"简化裁剪"，实际是分配**整张 canvas 大小的 ARGB buffer**
- 5 个 dither element × 1024×1024 ARGB = 20 MB 额外内存
- 配合 P0-Render-1 放大数百倍

**P0-Render-3** GlyphVector 极端字号 StackOverflowError
- `render/CanvasCompositor.java:849-865` drawGlyphOutline
- `MAX_FONT_SIZE=512` 不是硬限；用户可通过模板 / 直接 patch 设大字号
- `GlyphVector.getOutline` 返超复杂 Path2D，`g.draw` 栈溢出

#### P1 双端一致性

**P1-Render-1** canonicalCharWidth 浮点 rounding 跨端差异
- 后端 `render/TextLayout.java:46` `Math.round(fontSize * 0.5f)`
- 前端 `web/src/render/TextLayout.ts` 若用 `Math.round(fontSize / 2)` 或 `Math.floor(...)`
- fontSize=13：后端 `round(6.5f)=7`，前端 `floor(6.5)=6` → **差 1 px**
- 多行文本换行点前后端不一致 → 布局错位
- 无单元测试覆盖

**P1-Render-2** PathParser 退化阈值前后端可能差异
- `render/PathParser.java:97-101` 后端 `len < 1e-9` 跳过 tangent 更新
- 前端 PathParser.ts 若用 `1e-8` → 起/终点 marker 朝向错位
- 例：`d="M 100,100 L 100.0000001,100.0000001 L 200,200"` 双端行为不同

**P1-Render-3** Bayer dither double 精度跨端差异
- `render/BayerDither.java:55` `MATRIX[y&3][x&3] / 16.0 - 0.5`
- 后端 `double`，前端 `number`（IEEE 64-bit 但 JS 引擎实现细节不同）
- offset = `Math.round(t * AMPLITUDE * 2)` 在 rounding 边界（如 13.999999... vs 14.0）双端可能不一致
- 注释说"双端镜像硬约束"但无单测验证

#### P2 性能 / 功能间隙

**P2-Render-1** BlendModes 性能差
- `render/BlendModes.java:51-89` 每像素 5 次 float 乘法 + Math.round
- 1024×512 canvas 估 ~50ms；可用 int 算术 + 预乘 alpha 优化数倍

**P2-Render-2** M14 arrow stroke 修复未覆盖其他 marker / shape
- `render/CanvasCompositor.java:533-545` + `MarkerRenderer.java`
- 修了 PathElement arrow 但 CircleElement / ShapeElement(star) / dot marker 的粗 stroke 仍可突破 bbox
- 用户在 circle 上加大 stroke 时 → stroke 凸出圆形边界

#### P3 边界

**P3-Render-1** 0-width / 0-height rect 仍画 1px stroke
- `render/CanvasCompositor.java:679` `Math.min(s.width(), Math.max(1, Math.min(r.w(), r.h()) / 2))`
- `r.w()=0` 时仍走 stroke 路径
- 修复：`if (r.w() <= 0 || r.h() <= 0) return;`

**P3-Render-2** DirtyRegion 漏处理 rotation=90/270
- `render/DirtyRegion.java:55-66` 分支 `if (rot != 0 && rot != 180)` 走外接矩形公式
- 90/270 数学退化为 swap，但**代码没显式实现 swap 分支**，按公式算结果偏小
- 影响：rotation=90 元素脏矩形过小，map 更新遗漏

**P3-Render-3** 极小 fontSize 像素字体压成竖条纹
- `render/CanvasCompositor.java:801` `dstW = max(1, round(actualW * targetSize / nativeSize))`
- targetSize=1, nativeSize=12, actualW=8 → dstW=1 → 字形被压到 1px → NN 采样全是第一列 → **字形渲染成竖条纹**
- 修复：下界 `Math.max(nativeSize/4, ...)`

**P3-Render-4** TextLayout CJK 禁则回溯逻辑顺序反
- `render/TextLayout.java:171` 先允许换行后再回溯纠正禁则
- 多禁则字符相邻时反复回溯 → 浪费 CPU；理论上可造死循环（未实测）

#### P4 字体加载

**P4-Render-1** 外部字体目录无文件大小限制
- `render/FontRegistry.java:87-112` `loadExternal` 扫 `plugins/HikariCanvas/fonts/`
- `Font.createFont` 对 500 MB TTF 文件分配等量内存
- 修复：`if (Files.size(path) > 50_000_000) return;`
- 历史 CVE：Java 21 已修知名 TTF 解析洞，但畸形 glyph 表仍可触发 sun.font 解析路径异常

#### P5 测试覆盖

**P5-Render-1** Snapshot fixture 缺关键覆盖
- 13 fixtures 缺：layer opacity / layer blendMode（M8 v2 关键）/ 多层叠加 / rotation=45 / 极端参数（fontSize=1 或 512、strokeWidth=128）/ M14 arrow stroke 修复验证 / text+effects+dither 混合

---

### C. 构建系统 / 依赖 / 部署（8 条新发现）

#### 依赖审计

✅ **2026-05 时点已知 CVE 全清**：
- jackson 2.18.2 / Javalin 7.1.0 / Jetty 12 / SQLite 3.53.0.0 / HikariCP 7.0.2 / JDBI 3.52.1 / PacketEvents 2.11.2 / paperweight-userdev 2.0.0-beta.21 — 均为 2026 较新版本，无已知 CVE
- jackson-dataformat-yaml 2.18.2 默认禁多态类型化（M6 决策确实关闭了 SnakeYAML `!!java/*` RCE 面）
- 前端 Vue 3.5.33 / Vite 8.0.9 / Tailwind 4.2.4 / TypeScript 6.0.3 / Konva 9.3.22 — 均无已知 CVE

⚠️ **依赖管理小问题**：
- 缺 `gradle/libs.versions.toml` — 版本散落 `plugin/build.gradle.kts:29-42`，更新需全局搜索

#### Shadow JAR

**P1-Build-1** JAR 内重复文件
- `jar tf HikariCanvas-0.1.0-SNAPSHOT.jar` 发现 `palette 2.json` / `palette 3.json` / `palette 4.json` 多份
- 字体也有重名副本
- 根因：增量 build 残留 / multi-build 产物未清；输出仍可用（jar 读第一个）但 size 浪费 + 排查困难
- 修复：`./gradlew clean` 后重 build；或检查 `generatedPaletteResources` 与 `downloadedFontsDir` 的 outputs.upToDate 逻辑

**P2-Build-1** 无 Shadow Relocation
- `plugin/build.gradle.kts:271-275` `shadowJar { archiveBaseName / mergeServiceFiles }` 不 relocate
- 1.21 生态实际无冲突，但多插件共存时若另一插件也打不同版本 jackson-databind，可能版本 mismatch
- 风险等级：低（生产可用），但是隐性技术债

**P2-Build-2** copyWebToResources 缺 inputs 声明
- `plugin/build.gradle.kts` copyWebToResources task 没声明 `inputs.dir(webBuildDir.dir("dist"))`
- src 改 → buildWeb 重跑产出新 dist → copyWebToResources 因无 input 监听不重跑 → 旧产物进 jar
- 缓解：习惯性 `./gradlew clean` 但增量 build 失效
- 修复：补 `inputs.dir(webBuildDir.dir("dist"))`

**P2-Build-3** generatePalette 无降级 fallback
- `plugin/build.gradle.kts:96-122` PaletteGenerator JavaExec 失败时整个构建卡住
- 无缺省 palette.json 兜底；Paper API 不可达时构建完全无法继续

#### 字体校验

✅ **SHA-256 pinning 实施完整**：
- `plugin/build.gradle.kts:156-226` 两个字体都 pin（Source Han Sans SC `f1d8611...`、Ark Pixel `2fa78b40...`）
- 3 次重试 + 上游内容变更（CDN poisoning）会被检测
- 防御有效；但若 GitHub Release URL 本身被劫持仍无防护（pinning 是文件内容校验）

#### CI / 测试

**P2-Build-4** 完全无 CI pipeline
- 无 `.github/workflows` / `.gitlab-ci.yml` / `.circleci`
- 364 test case 全靠手工 `./gradlew :plugin:test`，push 后无强制
- 修复：补 `.github/workflows/test.yml`（push + PR 触发 gradle test）

#### 许可合规

**P2-Build-5** 字体许可未打入 JAR
- 项目本体 MIT，字体 SIL OFL
- shadow jar 内 `fonts/SourceHanSansSC-Regular.otf` + `fonts/ark-pixel-12px-monospaced-zh_cn.ttf` 但**无 OFL.txt / LICENSE 文件**
- 第三方依赖（jackson Apache 2.0、Jetty Apache 2.0+EPL 1.0 dual）也无 NOTICE
- 修复：`plugin/src/main/resources/FONTS-LICENSE.txt`（SIL OFL 全文）+ 根目录 NOTICE 列依赖许可

**P2-Build-6** 根目录 LICENSE / NOTICE 缺失
- 项目声明 MIT 但 repo 根目录无 LICENSE 文件
- README 也未明示许可

#### TypeScript / 前端

**P2-Build-7** tsconfig 未启用 noUncheckedIndexedAccess
- `web/tsconfig.json:8` strict=true 但缺 `noUncheckedIndexedAccess`
- `arr[i]` 不强制 undefined check → 容易遗漏边界
- 修复：加 `"noUncheckedIndexedAccess": true`（要修一批现有 TS 报错）

**P2-Build-8** Vite 无显式 chunk 分割
- `web/vite.config.ts` 走默认 vendor chunk 分割，无显式 `rollupOptions.output.manualChunks`
- M14 后 JS 477.79 KB；后续增长可能产生大 chunk

#### 部署文档

✅ `docs/deployment.md` 完整度高：单机 / 反代（Caddy + nginx） / 防火墙 / FAQ / config.yml 速查全有
✅ `docs/security.md` 威胁模型完整（T1-T14）/ token 生命周期透彻 / 6 层上传校验

⚠️ 缺：throttle.projection-fps 调参指南 / 大 wall 资源占用预估 / S3 对象存储反代缓冲（M13 后可能需要）

---

### 第二轮综合数据

| 域 | 新增 P0 | 新增 P1 | 新增 P2/P3/P4/P5 | 评分 |
|---|---|---|---|---|
| WebServer 鉴权 | 4 | 4 | 5 | 鉴权链多处脱节，session 长期复用是最大破口 |
| 渲染引擎 | 3 | 3 | 7 | Canvas 尺寸无上限是远程 OOM 关键路径 |
| 构建 / 依赖 | 0 | 1 | 7 | 依赖版本干净；许可合规缺失 + CI 完全没有 |

**第二轮新增 P0 总计：7 条**，最严重三条：
1. **Canvas widthMaps/heightMaps 无上限** — 一条 WS op 即可 48 GB heap → 服务器崩
2. **预握手明文返回 sessionId** + sessionId 长期复用 — token 单次性保护被绕过
3. **`/api/upload/quota` 仅凭 sessionId** — 跨用户配额查询 + 上传冒充

---

## 已审完的良好实践（值得保留）

- 架构纪律：NMS 零引用、`MapPacketSender` 唯一 sendPacket 调用点（即便目前是死代码，纪律仍守住）
- SQL 100% 参数化（JDBI `:placeholder` 全覆盖，零拼接）
- 路径穿越防护严密：HASH_RE / ALIAS_PATTERN / SAFE_NAME 多正则锁死，`/assets/{file}` 显式禁 `..` 和 `/`
- 默认配置安全：127.0.0.1 / 配额三层 / TTL 合理
- 双端 canonicalCharWidth 实现一致（M5-D2 决策落地正确）
- EXIF / metadata 通过 PNG re-encode 自动剥除
- 表达式求值器干净（无反射、无算术、严格 AST 白名单）
- jackson-dataformat-yaml 默认禁 polymorphic typing（M6 决策正确）
- Brigadier `.requires()` 让 tab-completion 自动隐藏无权限分支，零泄漏

---

**审查者**：Claude Opus 4.7（12 子代理并行 ultrareview）
**报告日期**：2026-05-15
