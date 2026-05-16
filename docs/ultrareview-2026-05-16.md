# Ultrareview 2026-05-16

> **范围**：HikariCanvas 全栈代码（Java 插件 + Vue 前端 + 契约文档 + 测试 + 构建脚本）
> **方法**：14 个只读子代理并行覆盖后端 8 模块、前端 3 模块、3 个横切面（安全 / 并发 / 测试构建），无 token 预算、无时间预算
> **基线 commit**：`83bc79a`（M15.5 同步契约 + M15 整体 journal 收尾）
> **执行者**：Claude Opus 4.7 / 14× Explore subagent
> **原始发现**：≈ 340 条，**去重后 ≈ 200 条独立问题**

## 严重度分布（去重后）

| 严重度 | 数量 | 主要类型 |
|---|---|---|
| **P0** | ≈ 40 | 真实可被利用的安全/数据完整性问题、关键路径事务边界缺失、未审计的高危默认行为 |
| **P1** | ≈ 70 | 资源生命周期/异常吞没/守卫不完整/前后端漂移 |
| **P2** | ≈ 55 | 设计强度不足、可用性边角、文档与代码脱节 |
| **P3** | ≈ 40 | 风格、注释、低概率边角 |

## 高置信度共识问题（≥ 2 个 agent 独立发现）

1. **主线程纪律仅靠注释**：`confirm/commit/cancel`、`MapPool` 公共方法注释要求主线程但无 `Bukkit.isPrimaryThread()` 断言（A / M）
2. **`SessionManager.forgetHooks` 异常仅 `log.warning` 吞掉**（无堆栈、丢失上游故障）（A / M）
3. **`ImageStorage.renderDecoderPool` 线程池关闭路径脆弱**（仅 onDisable 单点 shutdown，异常路径无回收）（E / M）
4. **协议版本号硬编码 `env.v = 2`**（前后端都写死，无协商）（I / 首报）
5. **`__hk` 全局调试口**：依赖 Vite 死代码消除，无 build-time 硬校验（I / L）
6. **Konva 节点 / Pinia store 无销毁清理**（unmount 后内存持续增长）（I / J）
7. **lock-state 前端守卫不完整**：CanvasView marquee/draw 入口未查 `isLocked`，仅靠 readonly overlay（J / K）
8. **Optimistic mutation 无真正回滚**：toggleLock/alias/element.update 失败后不还原本地状态（I / K）
9. **`/api/upload/{hash}` 下载端点无鉴权**（IDOR：拿到 hash 即可拿图）（E / L）
10. **双端镜像算法无自动校验**：`PaletteLut` / `BayerDither` / `TextLayout.canonicalCharWidth` 三处镜像靠 code review（B / J）
11. **AWT Graphics2D / ImageIO 多线程不安全 + 异步线程访问**（B / M）
12. **AuditLog 关键事件覆盖不全**：`wall.lock` / `wall.unlock` / `wall.alias` / upload 等敏感操作无审计（H / L）
13. **WebServer 仍是 581 行 god class**（M15.2 拆 4 Dispatcher 但主类未拆）+ M1 demo paint handler 残留（C / 首报）
14. **错误响应直接 echo 内部异常**（Jackson / IO / SQL 的 `e.getMessage()` 回客户端，泄露内部结构）（C / D / E）
15. **用户输入在错误消息中 echo 风险**（wall_id / alias 出现在 ActionBar / 日志）（C / H）
16. **HikariCP 配置不当 + 缺 `leakDetectionThreshold`**（SQLite 单写场景推荐 `maxPoolSize=1`，现配 4）（D / M）
17. **SessionReaper 扫描周期与 brush stroke / ISSUED token 等超时窗口不匹配**（A / M）
18. **未实装的 SessionRateLimiter / token 暴力枚举防御**：文档 §2.4 规定但代码未找到实装（L / H）

---

## 一、后端 · 会话与状态管理

来源：`plugin/src/main/java/moe/hikari/canvas/state/` + `session/`

### P0

- **SessionManager 三 HashMap 同步范围过大**：`byId` / `byPlayer` / `byWall` 共用同一 `synchronized(this)`，覆盖到 `MapPool.reserveForWall` 等可能调 Bukkit API 的路径（`SessionManager.java:59-61`，性质同时见横切 §M）
- **byWall 锁释放条件竞争**：`releaseLocks()` 使用 3-arg `byWall.remove(key, sessionId)`，cancel / deleteWall / SessionReaper 并发时仅返 false 无异常，无法确认释放（`SessionManager.java:545-546`）
- **byPlayer 映射在 forget 时无版本校验**：玩家断线-重连之间若发起新 session，旧 forget 可能移除新 session 的映射（`SessionManager.java:551`）
- **confirm 失败回滚不对称**：`createWithMapIds` 抛异常 → releaseWall，但 releaseWall 本身失败时异常向外冒泡，调用方无法判定 map 是否真释放（`SessionManager.java:232-235`）

### P1

- `Session.wsDisconnectedAt` 字段缺 `volatile`，外层锁保证的可见性是隐式的（`Session.java:42`）
- `ProjectState.layers()` 返回 unmodifiable view 但内部仍是 ArrayList；快照防御不彻底（`ProjectState.java:168-169`）
- `Canvas.guides` record 持 `List<Guide>` 未在 compact constructor 做 `List.copyOf`
- `BrushSession.strokes` 用 `HashMap`，仅靠 EditSession.synchronized 外部保护
- `TokenService.purgeExpired` 与 `replace(token, rec, marked)` 并发时无法区分"已用 vs 已被清"
- `TokenService.Record` CAS 失败时返 `ALREADY_USED`，但与"已 purge"语义混淆
- `SessionManager.deleteWall` 内 `new ArrayList<>(byId.values())` 快照后新 session 进入不会被 cancel
- `collectExpired` 中 `wsDisconnectedAt > 0` 与初值 `-1` 的语义耦合，易误用
- `Session.touchActivity` 同时重置 `wsDisconnectedAt`，模糊了"活动"与"重连"
- `ProjectState.version` 用 `++version` 无 volatile/Atomic，依赖外层锁
- `EditSession.purgeStaleStrokes` 由 SessionReaper 调，扫描周期可能让 stale stroke 超时 +30s 才被清

### P2 / P3

- `HistoryStack.MAX_HISTORY = 16` 硬编码（`HistoryStack.java:26`）
- `ElementValidator` 单字段有上限但工程总文本量无上限（千个 256 字符 text element 不被拒）
- `EditSession.addElement` 插入位置无 clamp 文档
- `Session` 仅 `createdAt + lastActivityAt`，无阶段时间戳无法观测瓶颈
- `WallKey` 是 record，自动 hashCode 依赖 BlockFace.hashCode 稳定性
- `EditSession.addElement` 对 `layer.elements()` 无 null 检查（依赖 record compact 构造器规范化）

---

## 二、后端 · 渲染管线

来源：`plugin/src/main/java/moe/hikari/canvas/render/`

### P0

- **RectRenderer 未防御负 w/h**：AWT `fillRect` 对负参数行为未定义（`RectRenderer.java:21`）
- **`CanvasCompositor` 注释声明线程安全但 imageLoader volatile 写中途读会拿部分新部分旧值**（`CanvasCompositor.java:84, 253-265`）
- **ImageRenderer mask path 越界未防御**：`Area.subtract` 在极端 path 下抛异常（`ImageRenderer.java:74-86`）
- **BayerDither offset 浮点边界对称性**：`Math.round(t*AMPLITUDE*2)` 在 -0.5 处与 TS 实现可能分歧（`BayerDither.java:96`）
- **rotation NaN 未被滤**：`if (e.rotation() != 0)` 对 NaN 永真，进入 `Math.toRadians(NaN)`（`CanvasCompositor.java:182-186`）

### P1

- AWT `Graphics2D` 多线程不安全：`rasterize` 与 `ProjectionThrottler` 异步 flush 可能并发写 buffer
- `dither element` bbox `(int) Math.ceil(Math.hypot(w,h))` 跨 element 边界 dither 相位错位
- `PaletteLut` 构建未防御 RGB 中的 NaN / 越界（恶意 palette.json）
- `GlowRenderer` bbox 计算可整数溢出（`(maxX-minX)+200` 无 16384 上限检查）
- `TextRenderer.drawPixelatedGlyph` 二维扫 maxCol 是 O(n²)；mask 全透明时 `actualW=0` 致 `BufferedImage(0,h)` 失败
- `PathParser` 切线单位化在 `len<1e-9` 时爆炸（多 lineTo / curveTo 累积浮点误差）

### P2 / P3

- `FontRegistry` 双内置字体缺失时 fallback 链路无显式异常
- `FillPaintBuilder` 退化线性渐变回退纯色 stops[0]，视觉跳跃
- `BrushRenderer` 压感 0 时 fallback 0.05 魔数无前端镜像注释
- `IconRenderer` tint 解析失败回 WHITE 用户难以察觉
- `HikariCanvasRenderer.render` 对外部传错长度的 pixels 无再次校验
- `GlowRenderer` 用 `FontMetrics.charWidth` 与 `TextLayout.canonicalCharWidth` 不一致 → glow 位置偏离
- `CanvasCompositor.toPaletteSlice` 未校验 mapIndex 范围
- `PathRenderer` Arrow Clip 用 1e6 硬编码"无穷大"
- `TextLayout` 竖排路径未实装行首禁则（注释自承认）
- `BayerDither.apply` 对 null palette 静默 return 无 log

---

## 三、后端 · 网络层

来源：`plugin/src/main/java/moe/hikari/canvas/web/`

### P0

- **WS 消息解析异常直接 echo 给客户端**：`handleMessage` 把 `e.getMessage()` 回 client，泄露 Jackson 内部信息（`WebServer.java:385`）
- **Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 未配置**：未知字段被静默接受，协议升级有歧义（`WebServer.java:147-148`）
- **未认证 WS 连接无 auth 超时**：`idleTimeout=60s` 不防"连上不 auth"型 DoS（`WebServer.java:254-264`）
- **Auth 失败到 close 的时间窗口**：`closeAuthFailed` 是否同步生效未确认，客户端可能继续发包（`WebServer.java:399-400`）

### P1

- **CORS / Origin 校验完全缺失**：WS upgrade 无 Origin 检查，CSWSH 攻击面（`WebServer.java:253-268`）
- 静态资源路径穿越防护不完整：仅检 `"/"` 与 `".."`，未防 null 字节 / Unicode 归一化
- WS 二进制帧大小未限制（仅 text 65536）
- Dispatcher `iae.getMessage()` 直接回客户端（多处）
- `Envelope.payload` 类型为 `Object`，Jackson 多态反序列化面隐含
- 预握手 HTTP 端点未对 sessionId/token 做格式预检
- 上传文件名原文出现在日志 / 错误消息

### P2 / P3

- WS ctx takeover 时旧 onClose 的 CAS remove 可能误删新连接 ctx
- demo `op=paint` 在 authenticated 通道无 feature flag
- `state.patch` 中 `version` 自增可能 long 回绕未防御
- 无服务端心跳推送（仅依赖前端 ping）
- `/api/upload` 权限 fallback：玩家离线时跳过 `canvas.upload` 校验
- `ownerName` 暴露在公开 `/api/templates`（设计已确认 v1 保留，仅记录）

---

## 四、后端 · 存储与数据库

来源：`plugin/src/main/java/moe/hikari/canvas/storage/` + `resources/db-migrations/`

### P0

- **confirm 多步操作跨 mapPool + wallRepo 缺事务**：reserve → createWithMapIds → release → bind 任一步失败导致 map 与 wall 行不一致（`SessionManager.java:223-244`）
- **wall delete 跨子系统无原子性**：mapPool releaseWall / wallRepo delete / FrameDeployer removeForWall 任一失败留下脏状态
- **HikariCP `maxPoolSize=4` 与 SQLite 单写冲突**：busy_timeout=5000 不能根治，并发写易 SQLITE_BUSY（`Database.java:40`）
- **AuditLog 非事务性**：业务事务 commit 后 audit insert 失败无法回滚，事件可丢（`AuditLog.java:53`）
- **`image_uploads.refcount` 列设计但代码不更新**：注释自承"v1 不做实时增减"，列名仍出现在 insert（`ImageUploadDao.java:48-70`）

### P1

- `WallRepo.loadByMapId` 用 LIKE 模式拼字（虽参数化绑定但语义不清，匹配噪声风险）
- `TemplateRepo.listMarketplace(limit=0)` 当 limit 非正时返回全表
- 大量 `catch (...) { log.warning } return Optional.empty()` 把 "no row" 与 "DB 故障"混淆
- `WallRepo.migrateAllToV2` 启动期 `SELECT *` 全量反序列化，walls > 1000 时撑爆堆
- `walls.protocol_version` INSERT 未显式设值，依赖 SQLite DEFAULT 1

### P2 / P3

- V001 `CREATE TABLE` 未见 `FOREIGN KEY ... ON DELETE` 策略，foreign_keys=ON 是否真生效未验证
- V005 `DROP TABLE pool_maps` 不幂等（无 `IF EXISTS`）
- `WallRepo.parseMapIds` 静默丢弃非法数字
- `ImageUploadDao.pickLruCandidates` 的 `NOT IN (...)` 大集合性能差
- MigrationRunner 未校验 `PRAGMA journal_mode / foreign_keys` 实际值
- `AuditLog.details` 字段无 schema 约束（理论上业务可写 password 进 JSON）
- UUID 列 collation 不明（大小写匹配靠应用层规范）
- 无定时审计日志清理（`data-model.md` 说 90 天保留但代码未实装）

---

## 五、后端 · 图片上传 / M13

来源：`plugin/src/main/java/moe/hikari/canvas/image/`

> **背景**：CLAUDE.md 写 "M13 未实施"，但代码层后端 6 层校验栈、ImageStorage、UploadHandler、ImageQuotaService 均已落地约 70%+。这是审查中问题最密的模块。

### P0

- **`future.cancel(true)` 在 ImageIO 内部循环大多不响应中断**：200ms 压缩炸弹防御实际有效性未验证（`UploadHandler.java:299-314`）
- **配额 check / insert 间无原子性**：per-day / total-disk 三层检查都是 stateless 查询 → insert，并发上传可超额（`UploadHandler.java:199-237` + `ImageQuotaService.java:48-72`）
- **磁盘写失败 vs DB insert 的顺序非原子**：`Files.write` 写半截再 insert 走 raced 重读路径，可能留孤儿文件 + DB 行
- **异常 `e.getMessage()` 直接回客户端**：UPLOAD_REJECTED / INTERNAL_ERROR 中泄露磁盘路径 / 文件名（`UploadHandler.java:158, 178, 228`）
- **IIORegistry 注销不完全**：JDK 版本差异、单 format 多 SPI、GIF/BMP 历史 CVE 字节码仍在类路径中可被重注册

### P1

- `renderDecoderPool` / `decoderPool` 仅 onDisable 单点 shutdown，异常路径无回收
- LRU evict 每次扫所有 walls + Jackson 反序列化 project_json，walls 多时同步执行卡 HTTP 线程
- 同一 hash 并发 putIfAbsent 都执行 `Files.write` → IO 放大
- 内存缓存 LinkedHashMap LRU 驱逐与外部使用 BufferedImage 之间无引用保护
- **`sha256[:16]` = 64 位**：生日攻击 2^32 即可碰撞（攻击者上传海量小图覆盖合法 hash）
- 下载端点 `/api/upload/{hash}` 无鉴权（与 §安全/IDOR 共识）
- Javalin multipart 全局大小未配，仅 UploadHandler 自检

### P2 / P3

- bypass-limit 权限在玩家离线时 fallback 为 false
- per-wall 配额延迟到 EditSession.add-element 检查，上传已扣费
- 缓存 TTL 仅在 load() 路径懒清理
- `touchLastUsed` 高频更新可让 LRU 排序错乱
- `deleteHash` 文件删除失败被吞 → 孤儿文件累积
- SVG 未支持但识别路径无明确拒策
- polyglot 文件依赖 magic bytes 前 16 字节
- downscale 极端宽高比生成 1×1024 视觉异常
- `ImageRenderer.mask.inverted` 在大 bbox 时 `Area.subtract` 渲染卡顿
- 上传 200ms vs 渲染 500ms 超时不对称
- compress bomb stress / 并发上传超配额 stress 测试缺失

---

## 六、后端 · MapPool 与部署

来源：`plugin/src/main/java/moe/hikari/canvas/pool/` + `deploy/`

### P0

- **MapPool 隐式单世界假设**：扩容硬编码 `Bukkit.getWorlds().get(0)`，与 walls 表 world 字段语义不一致（`MapPool.java:158-159`）
- **pool_maps 超容量行为未文档化也未实装**：恢复 50 张但 initial=30 时既不释放也不缩容，运维不明（`MapPool.java:128-133`）
- **`bindToWall` 不校验 world 匹配**：跨世界 wall-map 绑定不被拒
- **FrameDeployer.removeForWall 与 deleteWall 多步无事务**：中途失败留下 ItemFrame / DB / Pool 三方不一致

### P1

- **WallRestorer bind 失败时继续 compose**：把像素写到错误 mapId + 失败 maps 永不回 FREE = `idcounts.dat` 膨胀（项目核心风险）（`WallRestorer.java:54-63`）
- WandListener 选第三方 ItemFrame 时仅做"是否自家"判定，无所属 wall 权限校验
- **`FrameProtectionListener.onBlockBreak` 仅检 `force-break`**：绕过 `canvas.modify`，OP 玩家可无审计破坏支撑块
- `isProtectedFrame` 仅校 `wall_id` 单 PDC key，不做 slot 配套校验（NBT 编辑工具理论上可伪造）
- `SessionManager.confirm` 失败路径下 wallRepo.delete 抛异常时不可幂等

### P2 / P3

- MapPool 内部 `freeQueue` 是 `Deque<Integer>` 非线程安全（受 synchronized 保护，未来子类化风险）
- **`HikariCanvasRenderer.update(mapId, pixels)` 整数组替换 + ConcurrentHashMap**：render 读会看到字节撕裂（部分新部分旧像素）
- `WallResolver.Result.Ok.hasExistingFrames` 启动后第三方破坏不更新
- MapView renderer 重装与 MapPool.initialize 时序约束不强制
- `MapPacketSender.sendFullMap` 无频率上限（多玩家 view distance 内 flood 风险）
- WallResolver 极端坐标（Y<0 / Y>320 / 跨区块）未校验
- PDC `published_at` 字段已砍但旧数据无清理机制
- `wall.refresh` 补支撑方块硬编码 STONE，覆盖玩家装饰

---

## 七、后端 · 模板系统

来源：`plugin/src/main/java/moe/hikari/canvas/template/` + `expr/`

### P0

- **YAMLFactory 未配大小/alias 上限**：无 `maxAliasesForCollections` / `maxCodepointLimit`，Billion Laughs 风格 DoS（`TemplateLoader.java`）
- **`deepCopyMap` 无递归深度限制**：嵌套千层 Map/List 触发 StackOverflowError 崩服（`TemplateInstantiator.java:202-219`）
- **Interpolator `toString()` 无长度限制**：参数为 100KB 字符串 × 1000 占位符 = 100MB String 拼接（`Interpolator.java:42-58`）
- **`user-templates/<uuid>/*.yml` 无跨用户隔离鉴权**：玩家 B 可加载 / 引用玩家 A 目录下的模板（`TemplateRegistry.java:290-343`）

### P1

- **参数 `pattern` 每次实例化都 `Pattern.compile`**：模板定义 `^(a+)+$` 等灾难回溯正则 → ReDoS（`TemplateInstantiator.java:295-302`）
- Icon source 含 `${...}` 时 loader 跳过 `isValidName` 校验（路径遍历边角）
- rawState 反序列化只对 elements 走校验，Canvas / Layer 字段无校验

### P2 / P3

- `ExpressionParser` 递归无深度限制，深嵌套表达式栈溢出
- 元素数量无上限：`grid: columns=${a}, rows=${b}` 可生成百万 element
- `Interpolator` 对任意 Object `toString()` 可生成 `{...}` 类不可预期输出
- `TemplateExporter` 模板 ID 含 UUID 前 8 位（弱隐私）
- 模板 ID 重复检查跨源策略不一致（builtin 可被 server 覆盖，user 不能）
- 模板加载失败仅 warn log，无管理员查询端点

---

## 八、后端 · 命令 / 权限 / 配置

来源：`plugin/src/main/java/moe/hikari/canvas/command/` + `paper-plugin.yml` + `config.yml`

### P0

- **`/canvas alias` 不写审计日志**（敏感操作缺溯源）
- **`/canvas open <id|alias>` 草稿 wall 无所有者校验**：非 owner 也能 open 别人未锁的 wall（`CanvasCommand.java:230-275`）
- **`pendingDeletes` 是单层 `UUID → PendingDelete`**：玩家连敲两次 `/canvas delete` 不同 wall_id，第一条被覆盖（`CanvasCommand.java:75`）
- **`wall.alias` WS op 未做 owner-only**（命令侧做了，WS 侧漏）（`WallOpDispatcher.java:100-114`）

### P1

- TabCompleter 是否过滤其他玩家 wall_id / alias 未验证
- **`editor-url` 占位符替换不校验协议**：用户设 `javascript:alert({token})` 玩家点击执行
- `paper-plugin.yml` 声明 `canvas.use` / `canvas.wand` / `canvas.commit` 但代码不查 / 已废
- 删除确认窗口 30s 是绝对时间，无续期逻辑

### P2 / P3

- alias / wall_id 在错误消息中 echo（颜色码 / `\n` 注入面）
- wall 不存在时错误消息泄露存在性（`"No such wall: " + wallId`）
- `reload-config` 后 host/port 等字段不重启服务（注释说明但是隐含坑）
- `mapPoolInitial=0` / `idle-minutes=-1` 等极端值无 sanity check
- `runStats` 输出活跃 session 数（admin 可见，低风险信息泄露）
- `canvas.commit` 权限节点冗余（命令已废止）
- `ALIAS_PATTERN` 两处定义未共享常量

---

## 九、前端 · WebSocket 客户端 / Pinia stores

来源：`web/src/network/` + `web/src/stores/`

### P0

- **`pendingAcks` Map 在 onClose 不清理**：长时间运行 + 频繁断线 → 内存递增（`wsClient.ts:276-301`）
- **reconnect backoff 无 jitter**：固定 [1, 2, 5, 10, 30]s，服务恢复时惊群（`wsClient.ts:14-15`）
- **`__hk` 调试口仅 `import.meta.env.DEV` 网关**：依赖 Vite tree-shake，无 CI 校验 prod bundle 真无此符号（`App.vue:32-51`）
- **协议版本硬编码 v=2**：无协商机制，后端升 v3 强制前端同步发版（`wsClient.ts:99, 144`）
- **Optimistic mutation 无回滚**：`toggleLock` / alias commit 失败仅靠 watch lastError，不还原 `project.lockedAt` / `project.alias`（`TopBar.vue:34-91`）

### P1

- `sendWithAck(op, payload, 0)` timeout=0 永不超时
- ack 到达乱序时仅按 id 查表无 op 二次校验
- heartbeat 20s 无 pong-timeout 反查，tab 后台 throttle 时无心跳但前端不感知
- 未知 op 仅 log 不处理对应 ack 的 reject
- reconnect 5 次后停止，UI 无重连入口
- ws:// 在 HTTP 页面下 token 明文在 URL 查询串
- rotate 后旧 token 留在 sessionStorage 不清

### P2 / P3

- `applyPatch` 不校验版本递增（乱序到达可越过历史）
- `parsePath` 不处理 RFC 6901 转义（`~0` / `~1`）
- `ErrorPayload.retryable` 字段定义但不被使用
- `palette` store 在 store 函数体内调 `useProjectStore()` 形成运行时强耦合
- `brush` store localStorage 无版本号，schema 升级无迁移
- M8-A 兼容层 `state.elements` 与 `layers[active].elements` 双维护易漂移
- Pinia store 无 reset，多次进出页面状态累积
- `resolveWsUrl` 硬连 `127.0.0.1:8877`，公网部署调试受限
- MAX_LOG=200 行无时间轮换
- ALIAS_RE 前后端独立维护易漂移
- 大量 `as unknown as` 类型断言，无运行时 schema 校验

---

## 十、前端 · Canvas / Konva / 渲染交互

来源：`web/src/components/layout/CanvasView.vue` + `web/src/render/` + `web/src/composables/` + `web/src/brush/`

### P0

- **`setPointerCapture` 异常路径不 release**：快速切工具 / window blur 时 UI 陷入半死状态（`useBrushHost.ts:48`）
- **Konva Stage/Layer/Transformer 无 `onBeforeUnmount` destroy**：路由离开后内部 listeners + canvas context 泄漏
- **`useCanvasShortcuts` 的 `onKeyStroke` 无 cleanup**：重复挂载会重复触发
- **window paste 事件清理依赖 `@vueuse` 自动释放**：若改为原生 addEventListener 会漏（`useCanvasUpload.ts:147`）

### P1

- App.vue 只对 Delete/Backspace 查 `isLocked`，其他快捷键（V/M/L/A/C/S/B）只查 `inEditable()` 不查 lock
- CanvasView marquee/draw-to-create 入口不查 `isLocked`，仅靠 readonly overlay
- RightPanel 编辑字段仅 CSS `pointer-events: none` 屏蔽，无 `:disabled`

### P2 / P3

- BrushController floating canvas 异常中断时 cleanupFloating 不完整
- drawDrag / marquee 不处理 window blur / pointercancel
- PreviewRenderer 每帧 `document.createElement('canvas')` 多达 6+ 个未复用
- `requestAnimationFrame` ID 未保存，无 `cancelAnimationFrame`
- BayerDither / canonicalCharWidth / PaletteLut 三处算法 TS↔Java 无 fixture 自动核验
- PaletteLut.build O(32³ × 248) 在全局 lazy，但 palette 改变无失效路径
- canonicalCharWidth 对日文标点排版的 corner case 未处理
- marquee bbox 相交忽略 element.rotation（注释自承"近似"未实装）
- brush palette fetch 超时时首帧 dither→clean→dither 视觉抖动
- `useBrushHost` 无 pointerleave 处理
- `useTransformerManager` transformEnd 重置 scale 时序闪烁
- TextInlineEditor textarea focus 与全局快捷键判定脆弱
- `fill.ts` 每帧创建新 CanvasGradient 对象
- Konva rect listening 切换不同步 hit area
- `ui.selectedIds` 在 element 被其他客户端删除后不清理

---

## 十一、前端 · 右栏 / 工具栏 / 类型协议 / i18n

来源：`web/src/components/layout/RightPanel.vue` + 各 Section + `web/src/types/protocol.ts`

### P0

- **多处数值字段无前端范围校验**：stroke width / shadow dx/dy / glow radius 仅 `parseInt(...) || 0`
- **shape.sides 协议要求 [3..32]，UI 仅 HTML5 `min/max` 无 JS 严格守卫**
- **PathElement.d 字符串前端完全无校验**（注释自承应复用 PathDValidator 但未实装）
- **Opacity 滑块 draft 缓冲与快速切换元素时视觉不一致**
- **Mask 预设（circle/ellipse/roundedRect）生成 d 字符串时未防御负数 / NaN 的 w/h**

### P1

- 多选状态下完全隐藏可批量编辑的字段（opacity / blendMode / renderMode）
- `sendUpdate` 无 await / 无 onError / 无超时检测
- ColorInput popover 无 ESC 关闭、无焦点陷阱
- Fill.stops 重叠 / 全堆末尾时新 stop 反复取中点，无上限
- TopBar Lock 按钮无连击防护（快速点击可让 optimistic 卡死）

### P2 / P3

- Layer "删除最后一层" 仅前端按钮防守，无后端二次校验
- stroke width 是否允许浮点未定义
- rotation 输入 max=359 但逻辑钳到 [0,360)，体验不一致
- Gradient angle 数字输入框无负数防守
- 禁用状态仅靠父级 CSS，子 input 缺 `:disabled`
- IconElement.tint 字段右栏完全无 UI
- BrushPanel 部分参数无全量验证
- PathElement.markerStart / markerEnd 右栏无 UI
- ColorInput hex maxlength=9 与 #RRGGBBAA 长度对齐但注释缺
- Element ID 显示无复制按钮
- Mask preset 反推启发式仅按 C/Q 计数，手编 mask 易误判

---

## 十二、横切 · 安全 / OWASP

来源：全栈 grep + 威胁建模

### P0 / P1

- **IDOR — `/api/upload/{hash}` GET 无任何鉴权**（拿 hash 即拿图）
- **IDOR — `wall.alias` WS op 无 owner-only**
- **`/canvas open <id|alias>` 草稿 wall 非 owner 可 open**
- **Token 无 IP / UA 绑定**：XSS / 网络截获后异机重连
- **`SessionRateLimiter` / token 暴力枚举防御** docs §2.4 规定但代码未找到实装
- **`editor-url` 配置占位符替换不校验协议**（`javascript:` URL 可注入）
- **错误响应多模块泄露内部异常 / 路径 / Jackson 信息**
- **`__hk` 调试口依赖 Vite 死代码消除**，无 build-time 硬校验
- 前端 `lock-state` 是唯一执行者，后端编辑 op 透明放行 → 任何前端绕过即可写

### P2

- `/api/walls` 列表端点无身份认证（即使本机绑定，反代误配会暴露）
- `/api/templates` 公开返回 `ownerName`（已设计确认，记录为已知）
- 日志注入：sessionId / reason 中 `\n` 可伪造多行日志
- 审计事件覆盖：缺 lock/unlock/upload/alias/permission_denied 详情
- SQLite 无加密（依赖磁盘加密，文档未强调）
- `bind: 0.0.0.0` 时启动期警告强度不足
- CSP / X-Frame-Options / Cache-Control 等安全头插件侧未设（依赖反代）
- 字体 SHA-256 校验失败有重试 + 允许手工放文件，弱化校验

### P3

- 多服务器 token 跨服共享（docs §13 未决，仅记录）
- PacketEvents 滥用：grep 确认仅 `MapPacketSender` 单点（纪律遵守）
- 多服器同 `plugins/HikariCanvas/data/` 复用风险（部署文档应明示）

---

## 十三、横切 · 并发 / 资源生命周期

来源：全栈 grep `Executors` / `synchronized` / `BukkitTask` / `addEventListener`

### P0

- **`ImageStorage.renderDecoderPool` 仅 onDisable 单点 shutdown**（异常路径无回收）
- **`SessionManager` 三 HashMap 共用同一 monitor，持锁中调 Bukkit API 死锁风险**
- **异步线程上调用 Bukkit API 的隐藏调用链**：ProjectionThrottler 异步 flush → projector → Bukkit API
- **`SessionManager.byId/byPlayer/byWall` 无上限**：reaper 故障即累积
- **`ImageStorage.memCache` TTL 仅在 load() 路径懒清**：长期无访问不释放
- **PacketEvents listener onDisable terminate 异常被吞**

### P1

- `UploadHandler.decoderPool.shutdown()` 无 `awaitTermination` 等待
- `SessionReaper.stop()` 缺 try-finally 保证 task.cancel
- `confirm/commit/cancel` 无 `Bukkit.isPrimaryThread()` 断言
- `MapPool` synchronized 块内调 Bukkit `createMap`（主线程阻塞放大）
- `WebServer.serveClasspath` `ctx.result(in)` 依赖 Javalin 关流不保证
- `ImageStorage.readPngBytes` 用 `Files.readAllBytes` 无 timeout
- `EditSession` 用 `synchronized` 不可重入，内部回调再进同一方法死锁
- `WallOpDispatcher` `wall.refresh` 切主线程后 send ack 异常被吞
- `TokenService.tokens` 依赖 purgeTask，task 异常中止则累积
- 前端 setTimeout / setInterval 多处无 cleanup（HomePage / TopBar 等）

### P2 / P3

- `ProjectionThrottler` 节流精度 ±50ms（tick 对齐）
- `SessionReaper` 周期 30s vs ISSUED 5min 容忍但 brush stroke 30s 容忍可重叠 ~35s
- `HikariCanvasConfig.projectionFps > 20` 时 ProjectionThrottler 抖动
- 前端 fetch 无 AbortController/timeout
- `Promise.catch(() => ({}))` 吞异常后空对象继续使用

---

## 十四、横切 · 测试 / 构建 / 依赖

来源：`plugin/build.gradle.kts` + `plugin/src/test/` + `web/package.json`

### P0

- **shadowJar 无 `relocate`**：Jackson / Caffeine / HikariCP / JDBI 都裸暴露，与其他插件类冲突生产环境直接崩
- **`ImageRenderer` 无单测**（核心 mask 路径仅依赖 RendererSnapshotTest fixture 间接覆盖）
- **`FrameDeployer` 完全无单测**（PDC / frame 生成 / repair 三关键路径无回归保证）
- **`MapPool` 完全无单测**（CLAUDE.md 自承"技术核心"，无并发 / 状态机 / 扩容测试）
- **核心链路（Session → MapPool → FrameDeployer）无集成测试**

### P1

- 无并发测试（10+ 玩家同 wall 编辑）
- `UploadHandler` 完整 6 层校验 e2e 缺失（已引入 javalin-testtools 但未使用）
- SQL 迁移幂等性 / 前置依赖 / FK 约束 测试缺失
- 双端镜像算法（PaletteLut / BayerDither / TextLayout）无跨语言 fixture 自动核验
- 模板表达式恶意输入测试缺失（溢出、嵌套深度、null 参数）
- 字体下载 SHA-256 校验有 3 次重试 + 允许手工放文件（弱化）
- `paperweight-userdev 2.0.0-beta.21` 是 beta（无 stable 替代）
- generator sourceSet runtimeClasspath 包含 main compileClasspath（隔离不完全）
- processResources 中字体 / palette / web 资源覆盖顺序无显式 outputs 验证
- `npm install` 未用 `--ci`（lock 文件不严格遵守）
- Paper API SNAPSHOT 仓库未显式配置优先级
- ImageQuotaService 无单测（per-player / per-wall / total 三层原子性）

### P2 / P3

- 前端无 vitest（已知，CLAUDE.md 自承）
- snapshot 测试首跑自动建 baseline 可掩盖回归
- MockBukkit 使用约定缺规范（防 mock 滥用）
- ImageElement.update 路径 corner case 测试不足
- Gradle dependency lock / version catalog 未使用
- 无 CI（`.github/workflows/` 不存在）
- `paper-plugin.yml` 格式未在审查中正面验证
- Jackson 各子模块版本未通过 BOM 集中管理
- PacketEvents 2.11.2 是 1.21.x final，未来升级路径未明
- `0.1.0-SNAPSHOT` 与 `data-model.md §6.6` forward-only 触发条件衔接未定义
- `web/package.json` 已 `private: true` 但与后端版本同步策略未文档化

---

## 与契约文档脱节的发现

- **CLAUDE.md 写 "M13 未实施"**：实际上后端 ImageStorage / UploadHandler / ImageQuotaService / `image_uploads` 表 + 前端 `ImageElement` 协议 / `PreviewRenderer.drawImage` / Path2D mask / useCanvasUpload 三入口已落地 ~70%。CLAUDE.md 状态需修订。
- **`docs/security.md §2.4` 限流规范** vs 代码：未在 `TokenService.evaluate` / WS auth 路径找到限流实装
- **`docs/architecture.md §12` 多世界 / 池超容量缩容** vs 代码：`Bukkit.getWorlds().get(0)` 硬编码暗示单世界，池保留行为未文档化
- **`docs/protocol.md §12` history.mark 持久化** vs 代码：M7 承诺 `walls.history_json` 未实装
- **`docs/rendering.md §11` 中文缺字 fallback chain** vs 代码：FontRegistry 缺字处理路径不完整
- **`docs/data-model.md §2.5` 审计日志 90 天保留** vs 代码：无定时清理任务
- **M5.5 + 2026-05-14 lock-state 重设计** vs 代码：旧字段（drafts / sign_records / `FrameDeployer.markPublished`）已砍但残留 PDC 数据无清理；契约文档未统一标记 deprecated

---

## 未审到 / 受限

- `.canvas` 工程文件导入流程（文档 §4.4 规范但代码位置未定位）
- Bukkit Permission 与 LuckPerms 等权限插件的集成边界
- 生产 nginx / Caddy 反代具体安全头模板
- `paper-plugin.yml` 格式正面验证
- 前端 Vue 组件内 `v-html` / `innerHTML` 全量扫描（仅初步未见）
- 完整数据库连接池监控指标
- JVM heap / GC 配置与本插件长期运行匹配性
- Docker / K8s 部署时资源限制
- 多服务器场景下 plugins/HikariCanvas/data/ 跨实例复用
- Gradle wrapper jar 签名验证
- `paperweight reobf` 是否真禁用（架构纪律：Mojang mappings 输出）
- 浏览器 E2E（Playwright / Cypress）框架完全缺失

---

## 各 agent 覆盖范围与原始条数

| Agent | 范围 | 原始条数 |
|---|---|---|
| A | state/ + session/ | 30 |
| B | render/ | 28 |
| C | web/（Javalin + WS） | 19 |
| D | storage/ + db-migrations/ | 18 |
| E | image/（M13 全模块） | 26 |
| F | pool/ + deploy/ | 19 |
| G | template/ + expr/ | 14 |
| H | command/ + paper-plugin.yml + config | 20 |
| I | web/src/network/ + stores/ | 28 |
| J | web/src/components/layout/CanvasView + render/ + composables/ + brush/ | 30 |
| K | web/src/components 右栏与工具栏 + types/protocol.ts | 25 |
| L | 全栈 OWASP 横切 | 20 |
| M | 全栈并发 / 资源 / 错误处理横切 | 34 |
| N | 测试 + 构建 + 依赖 | 23 |
| **合计** | | **≈ 334** |

去重后 ≈ 200 条独立问题。所有原始 agent transcript 保留在审查会话 tasks/ 输出文件中。
