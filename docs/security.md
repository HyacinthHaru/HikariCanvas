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
| T9 | 恶意模板（YAML 解析 RCE） | 任意代码执行 |
| T10 | 恶意 `.canvas` 工程文件导入 | 客户端 XSS / 服务器异常 |
| T11 | 端口扫描识别本插件 | 辅助上述攻击 |
| T12 | 审计日志被清除 | 事后无法溯源 |
| T13 | 管理员误操作 | 数据丢失 |

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
- 每消息记录 `sessionId + opId`，审计日志可关联到 SignRecord

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

- 使用 SnakeYAML 的 **SafeConstructor**，禁止 `!!java/*` 等 tag
- 最大节点数 10000，最大深度 64
- 最大文件大小 256 KiB
- 解析失败的模板不加载，打 warn log

### 4.4 `.canvas` 文件导入

- 最大 zip 大小 10 MiB
- 单个文件解压后最大 10 MiB
- zip 条目总解压大小 50 MiB（防 zip bomb）
- 条目名必须通过安全路径校验：无 `..`、无绝对路径、无符号链接
- 只接受 `manifest.json`、`project.json`、`thumbnail.png`、`assets/` 前缀下的文件
- assets 文件仅允许 PNG，magic number 校验

---

## 5. 权限节点

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `canvas.use` | op=true, player=false | 使用任何功能（基础总开关） |
| `canvas.edit` | 继承 `canvas.use` | 开启编辑会话（`/canvas edit` / 持 Wand 交互） |
| `canvas.wand` | 继承 `canvas.edit` | 领取 Canvas Wand 物品 |
| `canvas.commit` | 继承 `canvas.edit` | 提交招牌（可分离以支持「只能预览不能提交」） |
| `canvas.template.use.*` | 继承 `canvas.edit` | 使用特定模板，如 `canvas.template.use.subway_station` |
| `canvas.template.all` | true 等价所有子节点 | |
| `canvas.import` | false | 导入 `.canvas` 工程 |
| `canvas.remove.own` | 继承 `canvas.edit` | 删除自己的招牌 |
| `canvas.remove.any` | op=true | 删除任何招牌 |
| `canvas.admin` | op=true | 管理命令（reload / stats / cleanup / fsck） |
| `canvas.admin.bypass-limit` | op=true | 无视限流与画布上限 |
| `canvas.admin.force-break` | op=true | 允许破坏插件保护的成品物品框 |

Bukkit 权限系统原生支持，配合 LuckPerms 等可细粒度授权。

---

## 6. 权限校验检查点

| 场景 | 检查点 |
| --- | --- |
| `/canvas edit` | `canvas.edit` |
| `/canvas wand` | `canvas.wand` |
| `/canvas confirm` | `canvas.edit`（同开启会话的权限） |
| WS auth 成功 | 再次校验 `canvas.edit`（防权限中途撤销） |
| `template.apply` | `canvas.template.use.<id>` 或 `canvas.template.all` |
| `commit` | `canvas.commit` |
| `/canvas remove <id>` | 招牌 owner == 自己 且 `canvas.remove.own` / 或 `canvas.remove.any` |
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
| `PERMISSION_DENIED` | player, node |
| `INPUT_REJECTED` | session, field, reason |

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
| SnakeYAML | YAML 解析 | **必用 SafeConstructor** |
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
