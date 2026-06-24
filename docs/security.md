# 安全规范

**状态：** 随代码同步更新（最近一次对照代码核对：2026-06-20，覆盖至 0.8 Part A `.canvas` 工程导入）
**适用范围：** 插件 Web 服务、认证、限流、输入校验、权限节点、审计、脚本运行时、部署建议

本文档定义 HikariCanvas 的安全模型与实现要求。所有面向网络的代码必须满足本文要求，否则 PR 不合并。

---

## 1. 威胁模型

### 1.1 保护目标

| 资产 | 威胁等级 |
| --- | --- |
| Minecraft 世界数据（地图 ID、物品框、方块） | 高 |
| 玩家游戏身份（UUID / 名字） | 高 |
| 服务器主机资源（CPU、内存、磁盘） | 高 |
| 玩家创作内容（招牌文字） | 低 |
| 审计日志 | 中 |

### 1.2 威胁清单



| 编号 | 威胁 | 影响 |
| --- | --- | --- |
| T1 | 未授权用户访问编辑器 | 他人冒充玩家生成招牌、污染世界 |
| T2 | Token 泄漏（URL 被分享、日志泄漏） | 同 T1 |
| T3 | Token 暴力枚举 | 同 T1 |
| T4 | 中间人篡改（公网明文 HTTP） | 劫持编辑动作 |
| T5 | WS 消息洪水（单会话内高速发送） | 服务器资源耗尽 |
| T6 | 大 payload 攻击（超大文本、超大画布） | 渲染 OOM、CPU 占满 |
| T7 | 预览地图池耗尽 | 其他玩家无法编辑 |
| T8 | PDC / SQLite 注入（非法字段值） | 数据损坏 |
| T9 | 恶意模板（YAML 解析 RCE） | 任意代码执行（M6 起 jackson-dataformat-yaml 默认即免疫 SnakeYAML `!!java/*` tag 路径）|
| T10 | 恶意 `.canvas` 工程文件导入（zip bomb / 路径穿越 / 伪造图片 / 越权脚本 / 不存在命令模板） | 资源耗尽 / 服务器异常 / 越权写。**0.8 Part A 起防御已实装**：解包三闸 + 路径校验 + 白名单（§4.4 `CanvasArchive`）、assets PNG magic + 隔离解码 + 配额（§4.4 `AssetIngest`）、脚本全量重校验 + wallId 重绑 + 命令模板白名单（§13.5 `ScriptImporter`）|
| T11 | 端口扫描识别本插件 | 辅助上述攻击 |
| T12 | 审计日志被清除 | 事后无法溯源 |
| T13 | 管理员误操作 | 数据丢失 |
| T14 | 图片上传滥用：超大文件 / 压缩炸弹 / 伪造 MIME / 路径穿越 / 磁盘填满 / 恶意 EXIF | 资源耗尽、RCE 风险（M13 引入；详细缓解见 §4.5） |
| T15 | URL 图片上传 SSRF（服务端代 fetch 任意 URL，可探内网 / 回环 / 云元数据） | 内网探测 / 凭证窃取。**0.4.9 起 SSRF 过滤已按用户要求移除，仅校验 scheme + 不跟重定向 + 10MB/30s 上限；公网部署服主自担风险，详见 §4.6** |
| T16 | 恶意 SVG 导入（XXE / 实体爆炸 / 嵌入脚本 / 超大 path → CPU/内存耗尽 / XSS） | 资源耗尽 / 脚本执行。**0.8 Part B 起防御已实装**：`preParseGuard`（体积上限 + 拒 DOCTYPE/ENTITY）+ `DOMParser` 天然挡 XXE/脚本执行 + `stripDangerous` 剥离危险节点 + `complexityGuard` 复杂度上限（§4.7） |

### 1.3 非目标

以下 **不在** 本插件的安全边界内，由 MC 服务器 / 运维 / 玩家自行负责：

- MC 协议层攻击（假玩家登录、世界修改）
- 服务器操作系统安全
- 反代配置错误（服主责任）
- 玩家账号盗用

---

## 2. 认证：Token 机制

### 2.1 生命周期

```
玩家游戏内 /canvas confirm
    │
    ▼
生成 token = 随机 32 字节 URL-safe base64 (43 字符)
    │
    │  存储：
    │    memory: TokenService.tokens[token] = {playerUuid, sessionId, issuedAt, ttlMillis, used}
    │    SQLite: audit_log 记录 AUTH_ISSUED（首发）/ AUTH_ROTATED（重连 rotate），存 token 的 SHA-256（字段 token_sha256）
    │
    ▼
拼接 URL = ${publicUrl}/?token=${token}
    │
    ▼
以可点击 TextComponent 发给玩家
    │
    ▼
玩家 15 分钟内点击 → 浏览器 GET /api/session/:token peek 验证（不消耗）→ WS auth 帧 consume 消耗
    │
    ▼
消耗后立即失效（不可复用，CAS mark used）；rotate 出新 token 给 WS 重连用（rotate token 同样单次使用）
```

### 2.2 强制要求

- **随机源**：`java.security.SecureRandom`，不得用 `Math.random()` 或 `ThreadLocalRandom`
- **长度**：至少 256 bit 熵（32 字节 base64）
- **存储**：仅内存（主体）+ SQLite SHA-256（审计溯源），**原文 token 不落盘**
- **日志**：token 原文禁止出现在任何 log（包括 DEBUG 级别）
- **传输**：默认绑定 127.0.0.1 规避明文传 token；公网场景强制 TLS（由反代提供）
- **TTL**：默认 15 分钟，可配置 1m~24h
- **单次使用**：消耗后立即失效
- **rotate**：WS 握手成功后签发新 token 用于后续断线重连；rotate 亦单次使用

### 2.3 Token 校验顺序

```
validateToken(t):
  if len(t) != 43: reject INVALID_FORMAT
  if !base64urlDecodable(t): reject INVALID_FORMAT
  record = tokenMap.get(t)
  if record == null: reject NOT_FOUND
  if record.used: reject ALREADY_USED
  if now > record.issuedAt + record.ttl: reject EXPIRED
  record.used = true
  return record.sessionInfo
```

失败场景统一返回 `AUTH_FAILED` + HTTP 401 / WS close 4001；不向外透露具体原因，避免枚举攻击。

### 2.4 防暴力

**原设计目标（部分未实装，见下方状态说明）：**

- 按源 IP 统计失败次数 + 阈值封禁（IP 级失败计数 → 临时封禁）
- 全局失败率「保守模式」（延迟签发）
- IP 的存储：SHA-256 哈希存 `audit_log.ip_hash`（IP 原文绝不入库）

> **当前实装状态（2026-05-25 起）**：`TokenRateLimiter` **已实装**——按源 IP 固定窗口限流（默认 **10 次 / 分钟**，`security.token-rate-limit.per-minute` 可配），在 **WS `auth` 帧 token 校验之前**拦截；超限 **close 4429** + `TOKEN_RATE_LIMIT_EXCEEDED` audit 事件。实现见 `web/TokenRateLimiter.java`（per-IP `ConcurrentHashMap` + 桶内 `synchronized`；P3-31 被动 sweep 每窗口清一次过期桶，内存 O(activeIp) 不无限增长），由 `WebServer.handleAuth` 调用 `tryConsume(authIp)`。
>
> **防御边界（明确）**：
> - **仅覆盖 WS `auth` 帧**这一路径；**不**含 HTTP `GET /api/session/:token` peek（该端点不读 token 计数）。
> - 反代部署下因 IP 绑定路径**不读 X-Forwarded-For**（见 §2.5 已知限制），`authIp` 退化为反代本机 IP，per-IP 桶变成「同一反代来源共享一个桶」——真实 IP 级限流仍需反代层 `limit_req_zone $binary_remote_addr` 弥补（与 §2.5 / §7.4 反代限制联动）。
>
> token 本体熵（256 bit · 单次使用 · 15min TTL）仍是第一道防线。**全局保守模式（延迟签发）+ IP 级失败计数 / 临时封禁**这两项进阶防御仍 **未实装**，留 v1.x。（`SessionRateLimiter` 是另一回事——它做的是**每会话编辑 op 限流 40/2s**，不是 token / IP 失败计数，见 §3.3。）

### 2.5 会话级 IP 绑定（M16-P6.6，2026-05-16）

为防御 token 在传输 / 浏览器历史 / 日志泄漏后被异地重放，Session 首次 `auth` 成功时绑定 caller IP（`Session.boundIp`，CAS 写入），后续所有帧到达必须从同 IP 来源；不一致 → `error: AUTH_FAILED` + close 4001。

**方案 B：绑 session 不绑 token**

- Token 已是单次使用 + 15min TTL，再绑 token IP 等于双重防御一个已被消耗的凭证，无收益
- Session 跨多次 WS 重连复用（5s~30s 阶梯重连），首次 auth 后任何重连都会触发 bindOrCheckIp
- 实现位置：`SessionManager.bindOrCheckIp(sessionId, callerIp)`，单方法 CAS + check 两态

**已知限制：**

- **IPv6 normalization**：当前用 `InetAddress.getHostAddress()` 字符串比较，未做 `::ffff:0:0/96` IPv4-mapped 归一化；玩家 IPv4/IPv6 切换会被误拒（需要重新走 token issue 路径，无安全风险但 UX 差）
- **反代 X-Forwarded-For**：未在 IP 绑定路径读 XFF（避免反代伪造 XFF 头突破 IP 绑定）。这意味着所有公网部署（反代→插件）下 boundIp 永远是反代本机 IP，绑定退化为「同一反代来源」。需要服主在反代层（nginx `limit_req_zone $binary_remote_addr`）做真实 IP 级限流弥补
- v1.x 修复方向：trusted-proxies 白名单内的 XFF 头才信任，配合 §7.4 trusted-proxies 联动

---

## 3. WebSocket 安全

### 3.1 连接层

- **WS upgrade Origin 白名单（M16-P1.3 实装，`WebServer.checkWsOrigin` / `isOriginAllowed`）**：放行三类 ——（1）无 / 空 / 字面 `"null"` 的 Origin（同源 fetch / 非浏览器客户端）；（2）`127.0.0.1:*` / `localhost:*`（开发环境）；（3）与服务端 `host:port` 完全同源，或显式命中 `network.allowed-origins` 配置白名单（严格大小写敏感匹配 scheme+host+port）。其余一律拒绝 upgrade（防 CSWSH 跨站 WS 劫持）。**注意：携带任意非白名单 Origin 的浏览器请求会被拒；缺失 Origin 的非浏览器客户端放行**
- 连接前需完成 HTTP 预握手 `GET /api/session/:token`（peek 校验，不消耗 token）
- 每连接在 5 秒内必须发送 `auth` 帧，否则强制 close 4001（`auth_timeout`）。该超时由**服务器被动驱动**——`onConnect` 时 `WebServer.scheduleAuthTimeout` 在内部专用 daemon `ScheduledExecutorService`（`hikari-ws-auth-timeout`）上注册一个 N 秒后触发的任务（非网络层自然超时）；到时若该连接仍未通过 auth（未设置 session attr）即主动 `ctx.closeSession(4001, "auth_timeout")`，auth 成功则 `cancelAuthTimeout` 撤销该任务

### 3.2 消息层

- JSON 严格模式解析（拒绝尾随逗号、注释）
- 消息最大 1 MiB，超过立即关闭连接
- 解析失败累计 3 次 → close
- 所有未认证状态下的消息除 `auth`/`ping` 外 → 拒绝
- 每消息记录 `sessionId + opId`，审计日志可关联到 wall_id（通过 session 持有的 wall）

### 3.3 限流

**当前实装（`SessionRateLimiter`）**：单会话固定窗口计数器 —— 默认 **40 msg / 2s**（≈ 20 msg/s 平均）。窗内超限本次 op 直接返回 `RATE_LIMITED` 并丢弃；**单次超限不关闭连接**，但 1 分钟内反复超限达 5 次会主动断连（close 1008，见下表）。

| 层级 | 规则 | 超过动作 | 状态 |
| --- | --- | --- | --- |
| 突发窗口 | 40 msg / 2s（`DEFAULT_BURST` / `DEFAULT_WINDOW_MS`） | 返回 `RATE_LIMITED`，丢弃本次 | ✅ 实装 |
| 反复超限 → close | 5 次 RATE_LIMITED / 1 min → close 1008 + 终止会话 | close 1008 + 断连 + `SESSION_RATE_LIMIT_DISCONNECT` 审计 | ✅ 实装（0.9.3，`SessionRateLimiter` 回调 → `WebServer.closeForRepeatedViolation`） |

这与 token 暴力枚举限流（§2.4 `TokenRateLimiter`，per-IP 10 次/分钟 → close 4429）是正交的两套限流。

**覆盖面（重要）**：`SessionRateLimiter.allow()` 只在 6 个编辑类 dispatcher 内调用 —— `element.* / layer.* / canvas.* / timeline.* / keyframe.*`（EditOpDispatcher）、`variable.*`、`variable.alias.*`、`schedule.*`、`rail.*`、`script.*`。**不计入**限流窗口的消息：`ping`（无副作用 echo）、`brush.*`（笔触流畅性考量，靠 `MAX_BRUSH_POINTS_PER_STROKE` / `MAX_ACTIVE_STROKES` 内存上限兜底）、`wall.*` / `template.*`（各有 ACL + DB 写锁兜底）。即「反复超限 → close 1008」只对编辑类 op 生效。把全部消息面纳入统一限流是一个会影响笔触体验的独立决策，留待后续按需评估（见 PROPOSAL「工具不是保姆」性能哲学，不预先过度防御）。

---

## 4. 输入校验

### 4.1 校验原则

- **服务端是权威**：前端校验仅为 UX，服务端必须重复所有校验
- **拒绝而非修正**：输入非法直接返回 `INVALID_PAYLOAD` 而不尝试修正
- **白名单优先**：能用 enum / regex 的不用自由字符串

### 4.2 各字段规则

| 字段 | 规则 |
| --- | --- |
| 任何字符串 | UTF-8、长度 ≤ `limits.text-max-length`（默认 256）、无控制字符（`\x00-\x08\x0B\x0C\x0E-\x1F\x7F`） |
| 文字内容（TextElement.text） | 长度 ≤ `limits.text-max-length`、允许换行 `\n`、不允许其他控制字符 |
| 颜色 | 严格 `^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$` |
| 字体 ID | 必须存在于 config 声明的字体 |
| 元素坐标/尺寸 | 整数，`-10000 ≤ x,y ≤ 10000`，`0 < w,h ≤ 10000` |
| 字号 | 整数 1~512 |
| 画布矩阵 | `1 ≤ w,h ≤ limits.canvas-max-maps` |
| 旋转 | 严格枚举 `0, 90, 180, 270` |
| 元素 ID | `^e-[a-zA-Z0-9-]{1,64}$` |
| 会话 ID / 招牌 ID | UUID v4 格式 |
| 模板 ID | `^[a-z][a-z0-9_]{2,63}$` |
| 参数值 | 按模板 `params` 声明类型校验，多做一次（不信任客户端） |

### 4.3 YAML 解析（模板）

- M6 起使用 **jackson-dataformat-yaml**（2.18.2）替代原计划的 SnakeYAML
- jackson-dataformat-yaml 底层基于 SnakeYAML 但不暴露 `!!java/*` tag 接口，默认不允许任意类实例化，无 SnakeYAML SafeConstructor 那种"忘记切换 → RCE"的失误面
- 不开 Jackson 的 `enableDefaultTyping` / `@class` 多态机制
- 最大文件大小 256 KiB（启动加载时按 byte 长度预筛）
- 解析失败的模板不加载，打 warn log；YAMLMapper 的 `MISSING_PROPERTY` / `UNKNOWN_PROPERTY` 视情设 strict 或忽略未来兼容

### 4.4 `.canvas` 文件导入

`.canvas` 是一个 zip：`manifest.json` + `project.json` + `scripts.json`（可选）+ `thumbnail.png`（可选）+ `assets/<hash>.png`。导入入口 `POST /api/project/import`（multipart，字段 `sessionId` + `file`），权限 `canvas.edit`（fail-closed：玩家离线拿不到 live `Player` 即视为无权限拒）。导入是「整体替换工程」的破坏性写。

**解包三闸 + 路径 + 白名单（`CanvasArchive.unpack` / `isSafeEntryName`）：**

- 包大小上限：默认 10 MiB（config `import.canvas-max-mb`）
- 单条目解压后上限：默认 10 MiB（config `import.canvas-max-entry-mb`）
- zip 条目总解压上限：默认 50 MiB，防 zip bomb（config `import.canvas-max-total-mb`）
- 流式边读边计数，**不信任 `ZipEntry.getSize()`**（声明大小可造假）
- 条目名安全路径校验：拒 `..`、拒绝对路径（`/` 开头）、拒反斜杠 `\`、拒 NUL 字节
- 白名单条目：`manifest.json`、`project.json`、`scripts.json`、`thumbnail.png`，外加 `assets/` 前缀下的文件；其余一律 `IMPORT_BAD_ENTRY` 拒整包
- 缺 `manifest.json` 或 `project.json` → `IMPORT_MALFORMED`

**`project.json` 物化（`ProjectMaterializer`）**：untrusted JSON → 经 `ElementValidator.validateElementForTemplateApply` 逐元素重校验（不信任任何元素数值）；画布尺寸超当前墙 → `IMPORT_SIZE_MISMATCH`；`manifest.spec` 高于插件支持上限 → `IMPORT_SPEC_UNSUPPORTED`。

**assets 摄入（`AssetIngest`，每张走与 `/api/upload` 同等的不可信防御链）：**

每个 `assets/` 下、以 `.png` 结尾的真实文件条目逐张：

1. **magic 校验**：读首字节判 PNG（`89 50 4E 47`）；**非 PNG 直接跳过**（不信文件扩展名）
2. **隔离解码**：独立 daemon 线程 `hc-asset-decoder` + **200ms 硬超时**（`ImageReader.abort()` 协作式中断）；**解码前先读头部尺寸预检**（只解析头不解码全图），> 8192×8192 直接拒，拦"小体积巨尺寸"分配型炸弹；解码失败 / 超时 / 超尺寸 → 跳过
3. **解码后 bbox sanity**：拒 0×0、拒超 8192×8192
4. **规范化 + 按内容重算 hash**：`encodePng` 重编码 + `sha256Hex16` **按内容算 hash**（不信文件名，hash 内容寻址、跨画去重）
5. **SERIALIZABLE 配额事务**：per-hash 锁 + 串行化隔离事务里查配额（`tryReserveQuotaOn`）；**配额拒（每日上传数 / 总磁盘 LRU 后仍超）→ 跳过该张**
6. **原子落盘 + 失败补偿**：事务 commit 后 `writeFileAtomic` 写盘；写盘失败则回滚 DB 行避免孤儿 row，并清 LRU 已 evict 的孤儿文件

**任何单张失败只跳过该张、不抛异常、不中止整次导入**；跳过张数汇总成一条 `asset-quota` warning 回前端。

- **assets 配额计入图片配额**：摄入走的就是 `/api/upload` 同一套 `ImageQuotaService`（每玩家每日上传数 / 服务端总磁盘 LRU），落 `image_uploads` 表，owner = 导入者。导入侧不开 bypass（`canvas.upload.bypass-limit`）。
- **`assets/icons/<id>.svg` 不被摄入**：SVG 条目虽因 `assets/` 前缀被解包白名单接纳，但 `AssetIngest` 只摄入 `.png`（`isAssetPng`），SVG 既不被解码也不计入配额、也不被导出收集——当前无 SVG 处理路径。
- **`thumbnail.png` 导入侧无专门校验**：核对全代码（`ProjectImporter` / `AssetIngest` / `image` 包）确认，`thumbnail.png` 仅被 `CanvasArchive` 解包白名单接纳进条目 map，此后**不被任何下游阶段读取 / magic 校验 / ImageIO 解码 / 计入配额**——`ProjectImporter` 只消费 `manifest.json` / `project.json` / `assets/*.png` / `scripts.json`，从不取 `thumbnail.png`。它受三闸大小约束（占总解压量），但无独立内容校验。（与 §4.5 `/api/upload` 那条严格解码栈不同，勿混淆。）

### 4.5 图片导入 `/api/upload`（M13）

`canvas.upload` 权限（默认绑 `canvas.edit`）的玩家可通过编辑器上传图片。后端走严格校验栈：

**a) 大小限制（config.images.max-size-kb）**

- 单文件默认上限 2 MB（2048 KB）
- 超出 → 413 + `UPLOAD_REJECTED: file too large`
- 服主可调高/调低；上限 0 表示无限制（不推荐）

**b) MIME 双重校验**

- 第一层：HTTP `Content-Type` 必须在 `config.images.allowed-mime`（默认 `image/png` / `image/jpeg` / `image/webp`）
- 第二层：**读首 16 字节 magic 校验真实类型**
  - PNG: `89 50 4E 47 0D 0A 1A 0A`
  - JPEG: `FF D8 FF`
  - WEBP: `52 49 46 46 ?? ?? ?? ?? 57 45 42 50`
- 两层不一致 → 拒绝（防伪造 Content-Type 走畸形图片解析路径）

**c) 内容解码验证**

- ImageIO.read 返回 null 或抛异常 → 不是合法图像 → 拒
- 解码成功后宽 × 高 + 字节深度做 sanity check（拒绝 0×0、超 8192×8192）
- 解码出来若边长 > `config.images.downscale-max-edge`（默认 1024）→ 自动 downscale（bilinear），节省存储

**d) 每画 / 每玩家配额**

- 每 wall 关联图片数：`config.images.max-per-wall`（默认 8 张）
- 每玩家 24 小时上传次数：`config.images.max-uploads-per-day`（默认 50）
- 服务端总磁盘配额：`config.images.max-total-storage-mb`（默认 1024 MB）
- 任一超限 → `UPLOAD_REJECTED` + 提示

**e) 存储与命名**

- 服务端按内容 hash 命名：`plugins/HikariCanvas/uploads/<sha256[:16]>.png`
- 跨画引用同一文件不重复存储（hash 内容寻址）
- ImageElement.source 持 hash，渲染时按 hash 查文件
- 删 wall 不立即清文件（其他 wall 可能引用）；走 **LRU 清理**：磁盘配额接近上限时按 last-used 删最老的

**f) 文件名 sanitize**

- 客户端 multipart 字段名 / 原始 filename 一律忽略（仅用作日志）
- 内部存储路径完全由服务端 hash 决定，杜绝任何客户端控制的路径

**g) 内容扫描**

- 不实施杀毒（超 scope）
- 但**Java 的 ImageIO 解码本身就是隔离测试**：如果文件让 ImageIO 抛 / 死循环 / OOM，会被 try-catch 抓住拒掉
- ImageIO 解码超时（如 200ms）→ 拒（防压缩炸弹），用 `ExecutorService.submit(...).get(200, MS)`

**h) 配额查询端点**

`GET /api/upload/quota` 返回当前玩家剩余配额（次数 / 字节），前端在 UI 提示。

**i) ImageElement.mask 校验（M13 决策 2026-05-14）**

`ImageElement.mask.d` 是客户端控制的 SVG path 字符串，与 `PathElement.d` 共享攻击面：
- **复用 M9 `PathDValidator`**：M/L/Q/C/Z 子集（大小写绝对/相对）、数值范围、命令-参数对应
- 坐标须在 `(0, 0)..(w, h)` element bbox 内（v1 仅 sanity 警告，不强拒；超出由 `Graphics2D.setClip` 自然裁掉）
- d 字符串长度上限 4096 字符（同 PathElement.d）
- `inverted` 字段是 boolean，无注入面

**权限：**

- `canvas.upload`：默认绑 `canvas.edit`
- `canvas.upload.bypass-limit`：默认 op=true，跳过配额（紧急用）

**M13 不做（v1 范围）：**

- mask 不支持其他元素作 alpha mask（PS "图层蒙版用图层" 概念）—— 仅 path 几何 mask
- 多文件批量上传 / chunked 大文件（v2 视频支持时再加）
- EXIF 信息读取 / 隐私元数据清除（ImageIO 解码后重写 PNG 时自然丢，不依赖额外 scrub）
- 杀毒 / 内容审核（超 scope；走 Bukkit 服管手动责任）

### 4.6 URL 图片上传 `POST /api/upload/url`（0.4.7 引入）

编辑器允许玩家粘贴一个图片 URL，由服务端代为 fetch 后走与文件上传相同的解码 / 配额 / 存储栈。

> ⚠️ **SSRF 防御已移除（0.4.9 hotfix-3 起，按用户明确要求）—— 公网部署服主必读**
>
> `UrlFetchSafety.check`（代码注释明确「SSRF 风险由用户自担」）**仅校验**：
> - scheme 必须是 `http` / `https`（仍拒 `file://` / `ftp://` / `data:` / `javascript:` 等）
> - URI 语法合法且 host 非空
>
> **已被删除的过滤（务必知悉）**：DNS 解析、私有地址段（RFC1918）、回环（127.0.0.0/8）、link-local、CGNAT（100.64.0.0/10）、IPv6 unique-local（fc00::/7）、`localhost` 字符串黑名单。`UrlFetchSafety.Reason` 中的 `UNRESOLVABLE_HOST` / `PRIVATE_ADDRESS` 枚举项保留但**新代码不再返回**。
>
> **后果**：任意持 `canvas.upload` 权限的玩家可让服务端向**任意内网 / 回环地址**发起 GET 请求（如 `http://169.254.169.254/`（云元数据）/ `http://127.0.0.1:<内部端口>/`），这是典型 **SSRF**。设计取舍是「本地 / 可信玩家场景，风险用户自担」。
>
> **公网或开放注册服务器必须额外缓解**：在反代 / 防火墙层禁止插件进程主动访问内网网段，或直接关闭 URL 上传入口（不给 `canvas.upload` 权限）。**不要假设本插件还在防 SSRF。**

**残留缓解（仅这三项，非 SSRF 防护）**：

- **不跟随重定向**：`HttpURLConnection.setInstanceFollowRedirects(false)`（302 等不自动跳，避免重定向绕过 scheme 校验）
- **字节上限 10 MB**：硬编码 `URL_MAX_BYTES`，超出 abort（注意：此处先按硬上限读，再按可配 `images.max-size-kb` 默认 2 MB 复查）
- **总超时 30s**：连接 + 读取各 15s（`URL_TIMEOUT_MS / 2`）
- fetch 回来的字节仍走与文件上传完全相同的 magic 校验 / ImageIO 隔离解码 / 配额 / hash 存储栈

### 4.7 SVG 矢量导入（0.8 Part B 实装）

**状态：已实装（0.8 Part B）**。SVG 导入是纯前端操作（`web/src/lib/svg/`），后端无新 SVG 解析器；导入产物（`PathElement`）走与手绘路径完全相同的 `element.add` 校验栈，后端只看路径 d 的 `PathDValidator` 校验，不接触原始 SVG 文档。

**防御层次（前端三闸 + DOMParser 天然隔离）：**

**1. `preParseGuard`（解析前体积 + 实体拦截）**

- 体积上限默认 **512 KB**（字符串字节数，可配 `limits.svg-import-max-kb`）；超限抛 `SVG_TOO_LARGE`
- 拒含 `<!DOCTYPE` 或 `<!ENTITY` 的源串（防十亿笑 / XXE 表面）；抛 `SVG_HAS_ENTITY`

**2. `DOMParser('image/svg+xml')` 天然隔离**

- 浏览器 DOMParser 不取外部实体、不执行 `<script>`、不发起网络请求；SVG 文档只被解析成内存 DOM，**不注入当前页面 DOM**（天然挡 XSS + XXE）
- 解析出来的 DOM 树不含 SVG 内联样式的 `@import` / 外链字体等副作用

**3. `stripDangerous`（危险节点 / 属性剥离）**

深度优先遍历 DOM，执行以下剥离：

| 剥离目标 | 处理方式 |
|---|---|
| `<script>` / `<foreignObject>` / `<use>` / `<symbol>` | 删整个节点（含子节点） |
| `<animate>` / `<animateTransform>` / `<animateMotion>` / `<set>` | 删整个节点（取首帧静态值） |
| `<style>` | 删整个节点 |
| `on*` 事件属性 | 删属性（任意元素） |
| `href` / `xlink:href` 含 `javascript:` | 删属性（任意元素） |
| `<image>` 的 `href` / `xlink:href` 为外链（非 `data:` 前缀） | 删整个 `<image>` 元素（不允许外链位图） |

**4. `complexityGuard`（复杂度上限）**

统计解析后的形状数与估算顶点数；超限抛 `SVG_TOO_COMPLEX`，前端熔断并展示大白话提示：

| 闸 | 默认上限 |
|---|---|
| `maxShapes` | 500（单 SVG 最大形状数） |
| `maxTotalVertices` | 50000（估算总顶点数） |

**5. 后端兜底**

SVG 导入生成的每个 `PathElement.d` 仍经后端 `PathDValidator` 校验（M/L/Q/C/Z 命令子集 + 数值范围），非法路径以 `INVALID_PAYLOAD` 拒。内嵌位图（`<image data:…>`）走与 `/api/upload` 完全相同的 magic + ImageIO 隔离解码 + 配额栈，不绕过任何上传限制。

**不做（D10 明确不支持的攻击面）**：`<text>`、`<clipPath>`/`<mask>`、CSS/SMIL 动画（取首帧静态化，剩余节点被 `stripDangerous` 删除）、`<foreignObject>`、外部 `href` 引用、`<use>`/`<symbol>`。这些功能连带其攻击面均不在实装范围内。

---

## 5. 权限节点

> 下表与 `plugin/src/main/resources/paper-plugin.yml` 的 `permissions:` 段为权威，二者必须一致。默认值列直接取 yml 的 `default`（`true` = 所有玩家、`op` = 仅 OP）。

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `canvas.use` | true | 使用任何功能（基础总开关） |
| `canvas.edit` | true | 开启编辑会话（`/canvas edit` / 持 Wand 交互） |
| `canvas.wand` | true | 领取 Canvas Wand 物品 |
| `canvas.commit` | true | 提交（保存）招牌 |
| `canvas.bench` | op | 跑服务端渲染 benchmark（`/canvas bench`，0.5.0） |
| `canvas.upload` | true | 通过 `/api/upload` 上传图片（M13） |
| `canvas.upload.bypass-limit` | op | 跳过每画 / 每日 / 总磁盘配额检查（M13） |
| `canvas.delete.own` | true | 删除自己的画（`/canvas delete <wall_id>`） |
| `canvas.delete.any` | op | 删除任何画 |
| `canvas.alias.any` | op | 修改任意 wall 的 alias（默认 wall.alias WS op 只允许 owner 改） |
| `canvas.admin` | op | 管理命令（stats / cleanup / reload） |
| `canvas.admin.bypass-limit` | op | 无视限流与画布上限 |
| `canvas.admin.force-break` | op | 允许破坏插件保护的成品物品框 / 支撑方块 |
| `canvas.admin.bypass-lock` | op | M15.3 鉴权方案 C：绕过 lock-aware open 校验，对已锁定的非自己 wall 也能 open |
| `canvas.template.save` | true | 把当前 wall 发布为创意工坊模板 |
| `canvas.template.delete.own` | true | 删除自己发布的模板 |
| `canvas.template.delete.any` | op | 删除任意模板（moderation） |
| `canvas.template.feature` | op | 标记 / 取消模板为精选 |
| `canvas.template.bypass-limit` | op | 跳过每玩家模板发布数配额 |
| `canvas.template.use-others` | op | 使用其他玩家发布的用户模板（M16-P1.6；TemplateRegistry `byIdForApply` 跨用户隔离） |
| `canvas.var.read` | true | 0.4.0：查看变量列表与值（编辑器自动补全） |
| `canvas.var.write.own` | true | 0.4.0：在自己 wall 上创建 / 改值 user/* 变量 |
| `canvas.var.write.any` | op | 0.4.0：在任意 wall 上修改用户变量 |
| `canvas.var.delete.own` | true | 0.4.0：删除自己 wall 上的 user/* 变量 |
| `canvas.var.delete.any` | op | 0.4.0：删除任意 wall 上的用户变量 |
| `canvas.var.bind` | op | 0.4.0：让 user/* 变量被插件 push 接管（敏感操作） |
| `canvas.var.command` | op | 0.4.0：用 `/canvas var` 命令族 |
| `canvas.var.global.create` | true | 0.4.3：创建全局用户变量（`userglobal/*`，跨 wall 共享） |
| `canvas.var.global.write.own` | true | 0.4.3：修改自己创建的全局用户变量 |
| `canvas.var.global.write.any` | op | 0.4.3：修改任意全局用户变量（管理员 override） |
| `canvas.var.global.delete.own` | true | 0.4.3：删除自己创建的全局用户变量 |
| `canvas.var.global.delete.any` | op | 0.4.3：删除任意全局用户变量（管理员 override） |
| `canvas.schedule.own` | true | 0.4.0-P3-L：管理自己 wall 的列车 / 公交时刻表 |
| `canvas.schedule.any` | op | 管理任意 wall 的时刻表（管理员） |
| `canvas.rail.line.create` | true | 0.4.4：创建铁路线路 |
| `canvas.rail.line.edit.own` | true | 0.4.4：编辑自己创建的线路 / 站点 / 车次 / 时刻表 |
| `canvas.rail.line.edit.any` | op | 0.4.4：编辑任意铁路线路（管理员） |
| `canvas.rail.line.delete.own` | true | 0.4.4：删除自己创建的铁路线路 |
| `canvas.rail.line.delete.any` | op | 0.4.4：删除任意铁路线路（管理员） |
| `canvas.rail.wall.bind` | true | 0.4.4：把 wall 绑定到铁路网络 |
| `canvas.script.edit` | true | 0.7.0：给自己能打开的墙编排积木脚本（基础脚本权限） |
| `canvas.script.trigger.global` | true | 0.7.0：使用全服事件触发器（进服 / 被击杀 / 退服 / 右键墙） |
| `canvas.script.sound` | true | 0.7.0：在脚本里用"播放声音 / 粒子"积木 |
| `canvas.script.command` | op | 0.7.0：在脚本里用"执行命令模板"积木（危险面，见 §13.1） |

Bukkit 权限系统原生支持，配合 LuckPerms 等可细粒度授权。

> **lock/unlock op 权限（2026-05-14）**：`wall.lock` / `wall.unlock` 不走权限节点，由 owner-only 校验代替——后端直接对比 caller.uuid == wall.owner_uuid，非 owner 拒 FORBIDDEN。无权限节点。

> **插件 namespace ACL（0.4.0）**：`HikariCanvasAPI.setVariable` 走 namespace 注册中心校验——插件 A 不能 push 插件 B 的 namespace（防 spoof）。无玩家权限节点，由 plugin 自声明 namespace 后强制绑定。详见 `docs/dynamic-data.md §9.2`。

---

## 6. 权限校验检查点

| 场景 | 检查点 |
| --- | --- |
| `/canvas edit` | `canvas.edit` |
| `/canvas wand` | `canvas.wand` |
| `/canvas confirm` | `canvas.edit`（同开启会话的权限） |
| WS auth 成功 | 再次校验 `canvas.edit`（防权限中途撤销） |
| `template.apply`（他人发布的用户模板） | `canvas.template.use-others`（内置模板 / 自己发布的模板无需此节点） |
| `/canvas delete <wall_id>` | wall owner == 自己 且 `canvas.delete.own` / 或 `canvas.delete.any`；二次确认强制 30s |
| 管理员命令 | `canvas.admin` |
| 超出画布 `max-maps` | 需 `canvas.admin.bypass-limit` |
| 破坏成品物品框 | 需 `canvas.admin.force-break`（否则 event cancel） |

任何检查失败 → 返回 `PERMISSION_DENIED` + 审计记录。

---

## 7. 部署安全建议

### 7.1 绑定

- **默认 `bind: 127.0.0.1`**。若服主显式改为 `0.0.0.0` → 启动时 **强烈警告**（连打 3 行 red log）
- 建议搭配系统级防火墙（`ufw` / `firewalld`）只放行反代所在主机

### 7.2 TLS

- 插件本体不内置 TLS（简化依赖）
- 公网部署必须通过反代（nginx / Caddy）加 TLS
- `deployment.md` 提供完整 nginx + Let's Encrypt 示例
- 文档明确：**没有 TLS 的公网部署是不安全的，严禁用于生产**

### 7.3 示例 nginx 配置（选粹）

```nginx
server {
    listen 443 ssl http2;
    server_name signs.example.com;

    ssl_certificate     /etc/letsencrypt/live/signs.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/signs.example.com/privkey.pem;

    # 推荐 TLS 配置（Mozilla Modern）
    # ...

    location /canvas/ {
        proxy_pass http://127.0.0.1:8877/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

插件配置 `web.context-path: "/canvas"` 配合。

### 7.4 反代 IP 识别

- 插件信任 `X-Real-IP` 或 `X-Forwarded-For` **仅当请求源 IP 在 `web.trusted-proxies` 白名单**
- 默认白名单为空 → 不信任任何代理头
- 公网部署必须配置 `trusted-proxies: ["127.0.0.1", "::1"]`（反代在本机）

---

## 8. 审计

### 8.1 必须记录

> 下表事件名与代码 `auditLog.record("...")` 调用点的字面量为权威（脚本相关事件另见 §13.7）。
> **注意**：`AUTH_FAILED` / `RATE_LIMITED` / `INVALID_PAYLOAD` 等是**错误响应码**（走 `Envelope.error` / HTTP status），**不是审计事件**，不会写入 `audit_log` 表。

**认证 / 会话：**

| 事件 | 触发 | 字段 |
| --- | --- | --- |
| `AUTH_ISSUED` | `/canvas confirm` 首发 token | player, session, token_sha256, ttl_ms |
| `AUTH_ROTATED` | WS auth 成功后 rotate 重连 token | player, session, token_sha256, ttl_ms |
| `AUTH_OK` | WS auth 帧校验通过 | player, session, ip_hash |
| `SESSION_BEGIN` | `/canvas edit` 开新会话 | player, session, wall, mapIds |
| `SESSION_OPEN` | `/canvas open` 打开已有 wall | player, session, wall |
| `SESSION_CONFIRM` | `/canvas confirm` 提交 | player, session |
| `SESSION_CANCEL` | `/canvas cancel` 取消 | player, session |
| `TOKEN_RATE_LIMIT_EXCEEDED` | per-IP token 限流超限（close 4429） | ipHash（仅 SHA-256，不存原文 IP） |

**权限 / wall / 上传：**

| 事件 | 触发 | 字段 |
| --- | --- | --- |
| `PERMISSION_DENIED` | 任一权限校验失败 | player, node（M16-P6.4：write 失败 SEVERE stack trace 兜底） |
| `WALL_DELETE` | `/canvas delete` 删除 wall | player, session, wall_id |
| `WALL_LOCK` | `wall.lock` op | player, session, wall_id, locked_at |
| `WALL_UNLOCK` | `wall.unlock` op | player, session, wall_id |
| `WALL_ALIAS` | `wall.alias` op | player, session, wall_id, old_alias, new_alias |
| `IMAGE_UPLOAD_OK` | 上传成功 | player, session, hash, bytes, width, height |
| `IMAGE_UPLOAD_REJECTED` | 上传被拒（含 URL fetch 失败 / 解码超时等 reason） | player, session, reason, content_length |
| `PLUGIN_NAMESPACE_DENIED` | 插件 push 撞别人 namespace（ACL 拒） | plugin, namespace |

**地图池（`MapPool`）：**

| 事件 | 字段 |
| --- | --- |
| `POOL_INITIALIZED` | size |
| `POOL_EXPAND` | old_size, new_size |
| `POOL_RESERVE` / `POOL_BIND_WALL` / `POOL_RELEASE_WALL` | wall_id, map_ids |
| `POOL_RELEASE_TO_FREE` | wall_id, map_ids, reason（M16-P2.5；WallRestorer 失败回收路径） |
| `POOL_LEAK` / `POOL_ORPHAN_ROW` | map_ids（泄漏 / 孤儿行检测） |

**变量 / 时刻表 / 铁路 / 时间轴：**

| 事件 | 触发 |
| --- | --- |
| `VARIABLE_CREATE/UPDATE/SET/DELETE/BIND` | `variable.*` WS op（per-wall 用户变量） |
| `VARIABLE_GLOBAL_CREATE/UPDATE/SET/DELETE/BIND` | 同上但 `userglobal/*` 全局变量（0.4.3） |
| `VARIABLE_ALIAS_SET` / `VARIABLE_ALIAS_CLEAR` | `variable.alias.*` op（0.4.2） |
| `VARIABLE_COMMAND_SET` / `VARIABLE_COMMAND_DELETE` | `/canvas var set/delete` 命令 |
| `SCHEDULE_UPSERT` / `SCHEDULE_ENTRY_ADD/UPDATE/DELETE` | `schedule.*` op（0.4.0-P3-L） |
| `RAIL_LINE_*` / `RAIL_STATION_*` / `RAIL_RUN_*` / `RAIL_TIMETABLE_SET` / `RAIL_WALL_BIND` | `rail.*` op（0.4.4，共 11 个） |
| `TIMELINE_PLAY` / `TIMELINE_PAUSE` / `TIMELINE_SEEK` | `timeline.*` 播放控制 op（0.6.0） |

脚本相关审计事件（`SCRIPT_*`）单列于 §13.7。

### 8.2 访问控制

- 审计日志仅 `canvas.admin` 可通过 `/canvas audit` 查阅
- 日志**不可在游戏内删除**；如需删除，DB 层外部操作
- 保留 90 天（可配）

---

## 9. 错误响应的信息披露

所有错误响应遵循 **最小披露** 原则：

| 错误 | 返回给用户 | 记日志 |
| --- | --- | --- |
| Token 无效 | `AUTH_FAILED` | 完整（含具体原因） |
| 权限不足 | `PERMISSION_DENIED` + 节点名 | 完整 |
| payload 非法 | `INVALID_PAYLOAD` + 字段名 | 完整 |
| 内部异常 | `INTERNAL_ERROR` + errorId | **堆栈与细节仅进日志** |
| 限流 | `RATE_LIMITED` | 聚合计数 |

前端不在 UI 中暴露 `errorId` 之外的内部信息。

---

## 10. 依赖安全

### 10.1 依赖清单

| 依赖 | 用途 | 安全关注 |
| --- | --- | --- |
| Paper API | 宿主 | 跟随 MC 版本 |
| Javalin | HTTP/WS | 关注 CVE，及时升级 |
| PacketEvents | 包发送 | 关注 API 破坏 |
| jackson-dataformat-yaml | YAML 解析 | M6 起用，**不开 `enableDefaultTyping`**；底层 SnakeYAML tag 接口不暴露 |
| HikariCP + JDBI + SQLite JDBC | DB | 稳定 |
| SLF4J | 日志 | |
| 前端：Vue / Vite / Konva | 编辑器 | npm audit 纳入 CI |

### 10.2 CI

> **当前实装状态**：`.github/workflows/ci.yml` 跑前端 vitest + vite build + 后端 `:plugin:test` + `shadowJar`。下列依赖安全扫描项为**规划目标，尚未接入 CI**：
>
> - Dependabot（或 Renovate）监控依赖升级 —— **未配置**（无 `.github/dependabot.yml`）
> - 每日 `gradle dependencyCheck`（OWASP 插件）—— **未接入**
> - 前端 `npm audit --audit-level=high` 纳入 PR 检查 —— **未接入**（CI 里 npm ci 失败回退时反而带 `--no-audit`）

### 10.3 发布构件

- jar 不包含源码的 `.git`、`.idea`、`.env`、`*.keystore`
- 发布前检查 jar 体积，异常增大触发人工审查

---

## 11. 发布通道

- Modrinth / SpigotMC / Hangar 发布
- 每个发布上传：jar + SHA-256 + GitHub Release 关联
- 关键安全修复：在发布说明头部明确标注 `[SECURITY]`

---

## 12. 响应渠道

- GitHub Security Advisory 接收私密上报
- `SECURITY.md` 在仓库根目录说明上报流程与响应 SLA（0.9.3 已创建）
- 披露政策：漏洞修复发布后 7 日解密细节

---

## 13. 脚本运行时威胁模型（0.7.0 引入）

0.7.0 引入了可视化积木脚本系统，让墙可以响应游戏事件并执行副作用（改变量、播动画、执行命令）。这一层新增了独立的攻击面，以下按威胁→缓解格式记录。

### 13.1 命令执行面

**威胁**：脚本的"执行命令"积木允许在服务器以 console sender 身份执行 Bukkit 命令（`dispatchCommand`），若允许自由拼接命令字符串，攻击者可执行任意命令（如 `/op @s`）。

**实际缓解（代码：`CommandTemplateEngine.java`）**：

- **服主白名单模板（K13）**：`runCommand` 积木只能引用 `config.yml` 中 `scripts.command-templates` 段预先声明的模板，**不接受任意字符串**。模板表为空时，该积木在编辑器内灰显且不可执行。
- **参数净化**：替换值执行前强制剥去换行符（`\n`/`\r`）与 `§` 颜色码；text 类型参数若净化后仍含 `@` 字符（`@a`/`@e` 选择器），整条命令拒绝执行并记 error step。
- **online-player 参数类型**：声明 `type: online-player` 的参数值必须精确命中当前在线玩家名（大小写敏感），不允许任何选择器语法。
- **单参数长度上限**：默认 64 字符（`max-length` 可调）。
- **执行权限面**：使用该积木的规则在保存（`script.create`/`script.update`）时检查 caller 持有 `canvas.script.command`（默认 op），缺权限拒 `PERMISSION_DENIED`；**执行期不再二次检查**（规则是 owner 权限快照，与 wall 所有权语义一致）。
- **audit**：每次命令执行记 `SCRIPT_COMMAND_EXECUTED`（templateId + 替换后全文 + 来源 ruleId），从不静默。

### 13.2 熔断 Budget（资源耗尽防御）

**威胁**：恶意或低质量脚本可能以极高频率触发，或通过 ABA 链（A 写变量 → 变量触发 B → B 写变量 → 再触发 A）形成无限环，耗尽服务器 CPU / 线程资源。

**实际缓解（代码：`ScriptBudget.java`，config `scripts.budget` 段）**：

Budget 三闸全部 volatile 字段，`/canvas reload` 热更，`LongSupplier` 时钟注入保证单测确定性。

| 闸 | 机制 | 默认值 | 超限行为 |
|---|---|---|---|
| **runs/s** | per-rule 1s 固定窗计数（`tryAcquireRun`）；窗内超限立即 false | 10 次/s | 本次 run 丢弃；记 `SCRIPT_RUN_BLOCKED`（K5 per-rule 10s 限频，防 audit 表被刷爆） |
| **actions/run** | 单次触发展开动作总数累计（含嵌套 if / wait 续接跨段）；`actionsExceeded` | 50 | 掐断剩余动作；blocked step + audit（K5 限频） |
| **chain depth（ABA 熔断，D8）** | `ScriptRunner.CHAIN_DEPTH` ThreadLocal：runner 线程持有当前链深；`VariableStore.fireChange` 同步回调 `TriggerRouter.onVariableChange` 直读 ThreadLocal，非脚本来源（null）depth=0，脚本写变量触发则 depth+1；`chainDepthExceeded` 检查 ≥ max | 8 | 整个 run 掐断 + WARNING 日志（含链路径）；**不自动禁用规则**（D8 工具不是保姆原则） |

`ScriptRunner` 单线程队列天然背压：同一时刻最多一个 action 在跑，极端高频触发会在队列侧堆积，不会无限并发扩张。

### 13.3 触发器权限面

**威胁**：全局事件触发器（玩家进服、被击杀、退服）监听全服行为，任意玩家都能触发；若无权限控制，低权限玩家可借此实现全服级副作用。

**实际缓解（代码：`ScriptPermissions.java`；`paper-plugin.yml` §133-144）**：

`ScriptPermissions.requiredFacets` 在规则保存时（create/update）递归扫描触发器与动作：

| 触发器/动作类型 | 所需附加权限面 | 默认 |
|---|---|---|
| playerJoin / playerKill / playerQuit / rightClickWall（全局语义） | `canvas.script.trigger.global` | **true** |
| playSound / playParticle | `canvas.script.sound` | **true** |
| runCommand | `canvas.script.command` | **op** |
| 其余（setVariable / setElementProperty / playTimeline / wait / if 等） | 无（仅基础 `canvas.script.edit`） | **true** |

权限面在**保存时检查**，不在执行时二次查。Owner 失权后已保存的规则照跑（语义与 wall 锁定一致），服主可手动 disable 规则。

`playerNear` / `playerLeaveRange` 是墙周范围触发，不属于全局面，仅需基础 `canvas.script.edit`。

### 13.4 脚本编辑鉴权

**威胁**：恶意玩家尝试向不属于自己的 wall 写入脚本规则。

**实际缓解（代码：`ScriptOpDispatcher.java`）**：

- `script.create` / `script.update` / `script.delete` / `script.enable` / `script.test` 全部先过 `canvas.script.edit` 基础权限，再按规则内容逐积木检查附加面（§13.3）。
- 鉴权基准：caller 必须能打开该 wall（open 路径鉴权，M15.3 方案 C）——非 owner 且 wall 已锁定 → open 时已拒 FORBIDDEN；后端编辑 op 路径不读 lock（lock-state §3.6 架构纪律）。
- 条件语法保存期预检（K16）：`if.condition` 字段在 create/update 时过 `ConditionEvaluator.checkSyntax` parse-only 检查，语法错误立即拒 `SCRIPT_INVALID`（返回 parse 错误首行 + blockId 定位），不等运行期静默 false。

### 13.5 `.canvas` 工程文件导入中的脚本

**威胁**：导入含脚本规则的 `.canvas` 文件（zip 内 `scripts.json`）时，文件内的 `rule_json` 完全不可信——可能含越权 / 畸形 / 语法错误的规则，也可能引用本服不存在的命令模板。

**实际缓解（代码：`ScriptImporter.importScripts`，编排自 `ProjectImporter` 第 6.5 步调用）**：整个 `scripts.json` 必须是 `ScriptRule[]` JSON 数组；解析不出数组 → 单条 `script-invalid` warning 返回（**不抛**，脚本导入失败不中断整次工程导入）。逐条规则**全量重校验、不信任文件内 `rule_json`**：

- **多态反序列化**：`ScriptRule`（trigger / actions 注解自带判别）反序列化；单条失败 → 跳过 + `script-invalid`
- **wallId 重绑**：由 `ScriptStore.create` 承担——它忽略 incoming 的 `id` / `wallId`，生成全新 `sr-<8hex>` 并强制绑定到**目标墙**（等价 `ScriptOpDispatcher` 的服务端权威覆写，杜绝跨墙注入）
- **结构校验**：`ScriptRuleValidator.validate` 非空 → 跳过 + `script-invalid`
- **条件语法预检**：递归对所有 `if` / `waitUntil` / `repeatUntil` 条件调 `ConditionEvaluator.checkSyntax`（parse-only）→ 非空跳过 + `script-invalid`（不等运行期静默 false，同 §13.4 K16）
- **命令模板检查**：扫所有 `runCommand.templateId`，本服 `config.yml` 模板表缺 → `script-command-blocked`（detail = templateId）；**不跳过、规则照常落库**，运行期 `CommandTemplateEngine` 会把该积木判为 `Blocked`（编辑器内红 badge 灰显不可执行）。规则其余部分照常可用
- **配额闸**：落库 `ScriptStore.create` 抛 `QuotaExceededException`（每墙脚本数超限）→ `script-quota` warning + **停止处理后续规则**

> **注意**：导入侧不做执行期权限面（§13.3 的 `canvas.script.command` 等）二次检查——脚本权限面是**保存期**校验的（owner 权限快照，与 wall 所有权语义一致）。导入是「把规则落到目标墙」，鉴权由 §13.4 的 wall open 路径承担（caller 必须能打开该墙）。命令积木的真正执行闸是上面的模板白名单 + 运行期 `Blocked` 判定。

### 13.6 试跑副作用

**威胁**：`script.test` op 若不受约束，可被用于无限次刷副作用（播声音/执行命令/改变量）。

**实际缓解**：`script.test` 走与生产触发完全相同的执行路径（D5 决策），**不豁免 Budget 三闸**（K12），同样过权限面检查，记 `SCRIPT_TEST` audit 事件（标注为 TEST run）。ack 立即返受理回执（`{accepted:true, ruleId}`），执行轨迹通过独立 `script.trace` S→C op 异步推回（避免 Jetty worker 被 wait 续接阻塞 5s 超时，K11）。

### 13.7 脚本相关 audit 事件

| 事件 | 触发时机 | 字段 |
|---|---|---|
| `SCRIPT_CREATE` | script.create op 成功 | sessionId, wallId, ruleId, name, facets |
| `SCRIPT_UPDATE` | script.update op 成功 | sessionId, wallId, ruleId, facets |
| `SCRIPT_DELETE` | script.delete op 成功 | sessionId, wallId, ruleId |
| `SCRIPT_ENABLE` | script.enable op 成功 | sessionId, wallId, ruleId, enabled |
| `SCRIPT_TEST` | script.test op 受理 | sessionId, wallId, ruleId, facets |
| `SCRIPT_RUN_BLOCKED` | Budget 闸拒（K5 per-rule 10s 去重） | ruleId, reason(chain\|runs\|actions), chainDepth |
| `SCRIPT_COMMAND_EXECUTED` | runCommand 积木执行命令（不限频，每次记） | templateId, rendered 全文, ruleKey |

`log` 动作（`Action.Log`）**不进 audit**（玩家级高频动作进 audit 会刷库）；仅进 plugin logger INFO 级别。

---

## 14. 未决问题

- [ ] 是否支持 OAuth 登录（如 Microsoft Account / Minecraft）以替代一次性 token——复杂度高，v1.0 不做
- [ ] 多服务器场景下的共享 session / token 传递
- [ ] DoS 防护是否需要集成 fail2ban（通过 `audit_log` 导出规则）
- [ ] WS 消息是否值得签名（防中间人篡改）—— 若强制 TLS 则不必
- [ ] 审计日志的数字签名 / 追加性防篡改（目前不做，信任 DB 层）
