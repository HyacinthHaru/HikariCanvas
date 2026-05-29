# 安全规范

**状态：** 立项稿 v0.1 · 2026-04-19
**适用范围：** 插件 Web 服务、认证、限流、输入校验、权限节点、审计、部署建议

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
| T10 | 恶意 `.canvas` 工程文件导入 | 客户端 XSS / 服务器异常 |
| T11 | 端口扫描识别本插件 | 辅助上述攻击 |
| T12 | 审计日志被清除 | 事后无法溯源 |
| T13 | 管理员误操作 | 数据丢失 |
| T14 | 图片上传滥用：超大文件 / 压缩炸弹 / 伪造 MIME / 路径穿越 / 磁盘填满 / 恶意 EXIF | 资源耗尽、RCE 风险（M13 引入；详细缓解见 §4.5） |

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
    │    memory: TokenService.tokenMap[token] = {playerUuid, sessionId, issuedAt, ttl}
    │    SQLite: audit_log 记录 AUTH_ISSUED，存 token 的 SHA-256
    │
    ▼
拼接 URL = ${publicUrl}/?token=${token}
    │
    ▼
以可点击 TextComponent 发给玩家
    │
    ▼
玩家 15 分钟内点击 → 浏览器 GET /api/session/:token 验证 → WS auth 消耗
    │
    ▼
消耗后立即失效（不可复用）；rotate 出新 token 给 WS 重连用
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

- 按源 IP 统计失败次数：**10 次失败 / 5 分钟 → 封禁该 IP 30 分钟**
- 全局失败率：**100 次 / 分钟 → 切换入「保守模式」**，所有新 token 签发延迟 1s
- IP 的存储：SHA-256 哈希存 `audit_log.ip_hash`，配置允许加 salt

> **当前实装状态（2026-05-25 起）**：`TokenRateLimiter` **已实装**——按源 IP 固定窗口限流（默认 **10 次 / 分钟**，`security.token-rate-limit.per-minute` 可配），在 **WS `auth` 帧 token 校验之前**拦截；超限 **close 4429** + `TOKEN_RATE_LIMIT_EXCEEDED` audit 事件。实现见 `web/TokenRateLimiter.java`（per-IP `ConcurrentHashMap` + 桶内 `synchronized`；P3-31 被动 sweep 每窗口清一次过期桶，内存 O(activeIp) 不无限增长），由 `WebServer.handleAuth` 调用 `tryConsume(authIp)`。
>
> **防御边界（明确）**：
> - **仅覆盖 WS `auth` 帧**这一路径；**不**含 HTTP `GET /api/session/:token` peek（该端点不读 token 计数）。
> - 反代部署下因 IP 绑定路径**不读 X-Forwarded-For**（见 §2.5 已知限制），`authIp` 退化为反代本机 IP，per-IP 桶变成「同一反代来源共享一个桶」——真实 IP 级限流仍需反代层 `limit_req_zone $binary_remote_addr` 弥补（与 §2.5 / §7.4 反代限制联动）。
>
> token 本体熵（256 bit · 单次使用 · 15min TTL）仍是第一道防线。**全局保守模式（100 次/分钟 → 延迟签发）+ `SessionRateLimiter` IP 级失败计数**这两项进阶防御仍 **未实装**，留 v1.x。

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

- 只接受本机或反代的 `Origin` 头（可配置白名单；默认 `null` 关闭 Origin 校验以兼容原生 WS 客户端）
- 连接前必须完成 HTTP 预握手 `GET /api/session/:token`
- 每连接在 5 秒内必须发送 `auth` 帧，否则强制关闭

### 3.2 消息层

- JSON 严格模式解析（拒绝尾随逗号、注释）
- 消息最大 1 MiB，超过立即关闭连接
- 解析失败累计 3 次 → close
- 所有未认证状态下的消息除 `auth`/`ping` 外 → 拒绝
- 每消息记录 `sessionId + opId`，审计日志可关联到 wall_id（通过 session 持有的 wall）

### 3.3 限流

实现三层漏桶：

| 层级 | 规则 | 超过动作 |
| --- | --- | --- |
| 即时速率 | 20 msg/s | 返回 `RATE_LIMITED`，丢弃本次 |
| 突发 | 40 msg / 2s | 同上 |
| 反复超限 | 5 次 RATE_LIMITED / 1 min | WS close 1008 + 会话终止 |

数值来自 `limits.ws-messages-per-second` 配置，默认给出推荐值。

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

- 最大 zip 大小 10 MiB
- 单个文件解压后最大 10 MiB
- zip 条目总解压大小 50 MiB（防 zip bomb）
- 条目名必须通过安全路径校验：无 `..`、无绝对路径、无符号链接
- 只接受 `manifest.json`、`project.json`、`thumbnail.png`、`assets/` 前缀下的文件
- assets 文件仅允许 PNG，magic number 校验

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

---

## 5. 权限节点

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `canvas.use` | op=true, player=false | 使用任何功能（基础总开关） |
| `canvas.edit` | 继承 `canvas.use` | 开启编辑会话（`/canvas edit` / 持 Wand 交互） |
| `canvas.wand` | 继承 `canvas.edit` | 领取 Canvas Wand 物品 |
| `canvas.template.use.*` | 继承 `canvas.edit` | 使用特定模板，如 `canvas.template.use.subway_station` |
| `canvas.template.all` | true 等价所有子节点 | |
| `canvas.import` | false | 导入 `.canvas` 工程 |
| `canvas.upload` | 继承 `canvas.edit` | 通过 `/api/upload` 上传图片（M13） |
| `canvas.upload.bypass-limit` | op=true | 跳过每画 / 每日配额检查（M13） |
| `canvas.template.use-others` | op=true | 使用其他玩家发布的用户模板（**M16-P1.6 引入**；TemplateRegistry `byIdForApply` 跨用户隔离，无此节点只能用自己发布的 + 内置模板） |
| `canvas.alias.any` | op=true | 修改任意 wall 的 alias（**M16-P1.7 引入**；默认 wall.alias WS op 只允许 owner 改） |
| `canvas.admin.bypass-lock` | op=true | M15.3 鉴权方案 C：绕过 lock-aware open 校验，对已锁定的非自己 wall 也能 open（M15 引入） |
| `canvas.delete.own` | 继承 `canvas.edit` | 删除自己的画（`/canvas delete <wall_id>`，M5.5 起替代 `canvas.remove.own`） |
| `canvas.delete.any` | op=true | 删除任何画（M5.5 起替代 `canvas.remove.any`） |
| `canvas.admin` | op=true | 管理命令（reload / stats / cleanup / fsck） |
| `canvas.admin.bypass-limit` | op=true | 无视限流与画布上限 |
| `canvas.admin.force-break` | op=true | 允许破坏插件保护的成品物品框 |
| `canvas.var.read` | 继承 `canvas.use` | 0.4.0：查看变量列表（编辑器自动补全） |
| `canvas.var.write.own` | 继承 `canvas.edit` | 0.4.0：在自己 wall 上创建 / 改值 user/* 变量 |
| `canvas.var.write.any` | op=true | 0.4.0：在任意 wall 上 |
| `canvas.var.delete.own` | 继承 `canvas.edit` | 0.4.0：删除自己 wall 上的 user/* 变量 |
| `canvas.var.delete.any` | op=true | 0.4.0 |
| `canvas.var.bind` | op=true | 0.4.0：让 user/* 变量被插件 push 接管（敏感操作） |
| `canvas.var.command` | op=true | 0.4.0：用 `/canvas var` 命令族 |

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
| `template.apply` | `canvas.template.use.<id>` 或 `canvas.template.all` |
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

| 事件 | 字段 |
| --- | --- |
| `AUTH_ISSUED` | player, session, token_hash, ttl |
| `AUTH_OK` | player, session, ip_hash |
| `AUTH_FAIL` | ip_hash, reason |
| `EDIT_START` | player, session, wall, mapIds |
| `COMMIT` | player, session, sign_id, element_count |
| `CANCEL` | player, session, reason |
| `CLEANUP` | admin, target_sign_ids |
| `POOL_EXPAND` | old_size, new_size |
| `POOL_SHRINK` | old_size, new_size |
| `RATE_LIMITED` | session, op_count |
| `PERMISSION_DENIED` | player, node（M16-P6.4：write 失败 SEVERE stack trace 兜底） |
| `INPUT_REJECTED` | session, field, reason |
| `WALL_LOCK` | player, session, wall_id, locked_at（**M16-P6.4 新增**） |
| `WALL_UNLOCK` | player, session, wall_id（**M16-P6.4 新增**） |
| `WALL_ALIAS` | player, session, wall_id, old_alias, new_alias（M16-P1.7） |
| `IMAGE_UPLOAD_OK` | player, session, hash, bytes, width, height（**M16-P6.4 新增**） |
| `IMAGE_UPLOAD_REJECTED` | player, session, reason, content_length（**M16-P6.4 新增**） |
| `POOL_RELEASE_TO_FREE` | wall_id, map_ids, reason（M16-P2.5；WallRestorer 失败回收路径） |

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

- Dependabot（或 Renovate）监控依赖升级
- 每日扫描 `gradle dependencyCheck`（OWASP 插件）
- 前端 `npm audit --audit-level=high` 纳入 PR 检查

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
- `SECURITY.md` 在仓库根目录说明上报流程与响应 SLA（v1.0 发布前创建）
- 披露政策：漏洞修复发布后 7 日解密细节

---

## 13. 未决问题

- [ ] 是否支持 OAuth 登录（如 Microsoft Account / Minecraft）以替代一次性 token——复杂度高，v1.0 不做
- [ ] 多服务器场景下的共享 session / token 传递
- [ ] DoS 防护是否需要集成 fail2ban（通过 `audit_log` 导出规则）
- [ ] WS 消息是否值得签名（防中间人篡改）—— 若强制 TLS 则不必
- [ ] 审计日志的数字签名 / 追加性防篡改（目前不做，信任 DB 层）
