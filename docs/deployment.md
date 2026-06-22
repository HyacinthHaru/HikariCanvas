# 部署指南

**状态：** 0.8.1-SNAPSHOT · 2026-06-22（命令族 / 功能已远超 M7：变量系统 / 铁路 / 时间轴 / 墙脚本均已合入）
**适用范围：** 把 HikariCanvas 部署给真实玩家（单机测试 → 内网 LAN → 公网服务器）

本文档讲三件事：

1. 服务怎么起来（最小可用）
2. 默认 `127.0.0.1` 绑定为什么不能在公网直开
3. 公网部署的两种姿势（反向代理 + TLS，强烈推荐 vs 直绑公网 + 自签证书，不推荐）

---

## 1. 单机 / 内网最小可用

把 shadow jar 扔进 `plugins/`、重启服务器，第一次启动会自动生成 `plugins/HikariCanvas/config.yml`。

```yaml
network:
  host: "127.0.0.1"
  port: 8877
  editor-url: "http://{host}:{port}/?token={token}"
```

- **同一台机器开服 + 编辑**：默认配置直接能用。`/canvas confirm` 后聊天里弹出的 `http://127.0.0.1:8877/...` 在你这台机器上点开就行。
- **同一局域网（LAN）开服 + 远程玩家编辑**：把 `host` 改成服务器的内网 IP（`192.168.x.x` 之类），`editor-url` 也跟着改。其他玩家用浏览器访问 `http://192.168.x.x:8877/?token=...` 即可。
  - 风险：内网 token 仍走明文 HTTP/WS。家庭/小型办公局域网可以接受。

> ⚠️ **重要：** 改完 `host` / `port` **必须重启服务器**。`/canvas reload config` 只刷新引用 + log 配置，但 socket 已经绑死的端口不会重绑。

---

## 2. 为什么默认 `127.0.0.1` ？

`docs/security.md` §3 把 TLS 列为**公网部署的硬性前提**。原因：

- **Token 单次使用但明文传输**：玩家从 `/canvas confirm` 拿到的 URL 里带一个一次性 token。HTTP 明文传输时，任何在中间链路上嗅探的人（同 WiFi、ISP、不信任代理）都能立刻拿到 token，在它过期前抢先 auth。本项目 token 有 15 分钟 TTL + 一次性 consume，但攻击窗口仍然存在。
- **WebSocket 帧明文**：之后所有 op（包括 element.update 的文本内容）也都走明文。在企业网 / 公共 WiFi 抓包可见。
- **CORS 不防内网横向**：本服务没有 CORS 限制，因为预期就是同源。公网开放后任何网站都能从浏览器跨域试图打这个端口。

`127.0.0.1` 强制限制了仅本机进程可以连过来。所有需要远程访问的场景，**必须**通过一个 TLS-terminating 反代来。

---

## 3. 公网部署 · 推荐路径（nginx / Caddy 反代 + TLS）

### 3.1 架构

```
   玩家浏览器
       │ HTTPS / WSS（443）
       ▼
   反代（nginx / Caddy）+ Let's Encrypt 证书
       │ HTTP / WS（环回 127.0.0.1:8877）
       ▼
   HikariCanvas WebServer
```

反代负责：
- TLS 终止（拿到 Let's Encrypt 证书）
- WebSocket 升级转发
- 限速 / 防 DDoS（可选）

HikariCanvas 还是绑 `127.0.0.1:8877`，**不直接暴露到公网**。

### 3.2 申请域名 + 证书

最简单：用一个能解析到你服务器公网 IP 的域名（如 `canvas.example.com`）。证书走 **Let's Encrypt**：

- **Caddy**：自动管理，零配置（见下方 Caddyfile）
- **nginx**：用 `certbot --nginx` 一次性签发 + 自动续期

### 3.3 Caddyfile（推荐）

`/etc/caddy/Caddyfile`：

```caddy
canvas.example.com {
    # WebSocket 升级 + HTTP 反代一锅端
    reverse_proxy 127.0.0.1:8877 {
        # WS 帧 / 大 payload 给充足窗口
        transport http {
            keepalive 60s
        }
    }

    # 推荐：限速避免被刷
    @editor_path {
        path /
    }
    rate_limit @editor_path 60r/m

    # 编辑期 WS 长连接，禁止响应缓冲
    encode zstd gzip
}
```

`systemctl reload caddy`，证书自动签 + 续。

### 3.4 nginx 配置（如果你坚持用 nginx）

`/etc/nginx/sites-available/hikari-canvas.conf`：

```nginx
server {
    listen 443 ssl http2;
    server_name canvas.example.com;

    ssl_certificate     /etc/letsencrypt/live/canvas.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/canvas.example.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # 编辑器静态资源 + HTTP API + WS 升级，全代到 127.0.0.1:8877
    location / {
        proxy_pass         http://127.0.0.1:8877;
        proxy_http_version 1.1;
        # WS 升级
        proxy_set_header   Upgrade      $http_upgrade;
        proxy_set_header   Connection   "upgrade";
        # 透传客户端 IP（用于服务端日志）
        proxy_set_header   X-Real-IP    $remote_addr;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   Host         $host;
        # WS 长连接 idle 上限
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}

# 80 → 443 跳转
server {
    listen 80;
    server_name canvas.example.com;
    return 301 https://$host$request_uri;
}
```

`certbot --nginx -d canvas.example.com` 一行搞定证书，然后 `nginx -s reload`。

### 3.5 调整 HikariCanvas config

```yaml
network:
  host: "127.0.0.1"      # 不变，仍然只绑本机
  port: 8877             # 不变
  editor-url: "https://canvas.example.com/?token={token}"
  #          ^^^^^                       ^^^^^^^^
  #          关键：协议改 https；域名改成反代域名
```

`/canvas reload config` 让配置生效，**然后重启服务器**（因为 url-template 字段在 onEnable 时被传给了 WandListener / CanvasCommand 等不参与 reload 的组件）。

### 3.6 防火墙

公网服务器：
- `:443` 开（反代入口）
- `:8877` **关闭对外**（仅本机 lo 接口可达）
- `:25565`（MC 默认）按需开

iptables 示例：

```bash
# 只允许本机访问 8877
iptables -A INPUT -p tcp --dport 8877 -s 127.0.0.1 -j ACCEPT
iptables -A INPUT -p tcp --dport 8877 -j DROP
```

或者 ufw：

```bash
ufw allow from 127.0.0.1 to any port 8877
ufw deny 8877
```

---

## 4. 公网部署 · 不推荐路径（直绑公网）

如果你**真的**只是临时测试、且能接受明文 token 风险，可以：

```yaml
network:
  host: "0.0.0.0"        # 监听所有网卡
  port: 8877
  editor-url: "http://your-server-ip:8877/?token={token}"
```

**不要长期这样做。** 明文 token 在公网链路上等于"谁先抓到谁就能编辑你的画"。

如果只是想用自签证书省事，那 HikariCanvas 内置 WebServer 没有 TLS 模块（用了 Javalin 默认 HTTP）—— 还是得起反代。建议直接走 §3。

---

## 5. 多玩家场景注意事项

当前版本的 wall 排他锁是**全局**的（详见 `docs/architecture.md §3`）：

- 任意时刻一个 wall 最多一个活跃 session。第二个玩家试图 `/canvas open` 同一 wall 会收 `WALL_OCCUPIED`。
- alias 是全服唯一（`UNIQUE INDEX idx_walls_alias`）。两个玩家不能用同一别名。
- 玩家**没有归属概念**：任何有 `canvas.edit` 权限的玩家都能开任何 wall（用 wall_id）。`canvas.delete.own` 限定只能删自己创建的。`canvas.delete.any` 删任意（默认 op 才有）。
- wall 的 ItemFrame `BlockBreakEvent` / `HangingBreakEvent` 由 `canvas.modify` 权限统一保护——无该权限的玩家拒绝破坏。详见 `docs/security.md §5`。（2026-05-14 lock 状态重设计后，「已发布墙全员拒绝破坏」语义已废止；`/canvas publish` / `/canvas unpublish` 命令一并砍除——锁定/解锁现由网页编辑器 TopBar 的 Lock 按钮触发，走 WS `wall.lock` / `wall.unlock` op，owner-only，且仅作前端只读冻结，不影响游戏内破坏行为。）

> 玩家身份认证（HomePage 点击直接打开、归属隔离、多人协作）是单独的 milestone，尚未实现。当前公网部署**应该把 HomePage `/api/walls` 视为对内网开放**——所有人都看得到所有人的画清单。

---

## 6. 常见问题排查

| 现象 | 可能原因 |
|---|---|
| 浏览器打开 `http://127.0.0.1:8877` 卡白屏 | shadow jar 没打 web/ 资源；跑 `./gradlew :plugin:shadowJar` 重打 |
| WS 连上后立即 `4001 AUTH_FAILED` | token 已被消费 / 已过期。重新 `/canvas confirm` |
| nginx 后 WS 不停断 | nginx `proxy_read_timeout` 太短；改 3600s 或更长 |
| Caddy 自动证书签不出 | 域名 A 记录没解析到本机 / 80 端口没开 / cloud provider 防火墙没放行 |
| WS 连不上但 HTTP 能开 | 反代漏配 `Upgrade: websocket` 头转发 |
| 公网 `host: 0.0.0.0` 后玩家访问超慢 | MC 服务器主线程被画布渲染卡到了；降低 `throttle.projection-fps` 或减少 wall 尺寸 |
| `/canvas reload config` 看上去没生效 | host/port/池容量/超时这些"启动期注入"的字段需要重启。`/canvas reload config` 当前只热更脚本相关字段（`scripts.*` 配额 / budget / playerNear 采样间隔 / 克隆元素配额），其余字段刷新引用但需重启才生效；插件 Push 限流另走 `/canvas var reload` 热替换 |

---

## 7. config.yml 速查

完整字段说明见 jar 内嵌的 `resources/config.yml`（首次启动自动拷到 `plugins/HikariCanvas/`，每行都带中文注释），共 13 个顶级段。下表按段列关键字段：

| 段 / 字段 | 默认 | 含义 | 改了要重启？ |
|---|---|---|---|
| `network.host` | `127.0.0.1` | WS/HTTP 绑定 IP | ✅ |
| `network.port` | `8877` | 同上端口 | ✅ |
| `network.editor-url` | `http://{host}:{port}/?token={token}` | `/canvas confirm` 发给玩家的 URL 模板（仅 http/https，其它协议被拒） | ✅ |
| `network.ws-auth-timeout-seconds` | `5` | WS 建连后多少秒内必须 auth，超时 close 4001 | ✅ |
| `network.allowed-origins` | `[]` | WS upgrade Origin 白名单（回环 + 同源默认放行；反代域名要加进来） | ✅ |
| `session.token-ttl-minutes` | `15` | token 有效期 | ❌（新 token 立即生效） |
| `session.ws-grace-minutes` | `5` | 断线后保留 session 多久供重连 | ✅ |
| `session.idle-minutes` | `30` | 无输入多久回收（0 = 永不超时） | ✅ |
| `map-pool.initial` / `.max` | `64` / `256` | 预览地图池容量 | ✅ |
| `map-pool.per-world` | `{}` | 可选 per-world 预热 map 数（多世界服务器） | ✅ |
| `throttle.projection-fps` | `5` | 服务端推送 MC 地图的 fps | ✅ |
| `throttle.input-rate-per-second` / `.input-burst` | `20` / `40` | 单玩家 WS op 速率 + 突发上限 | ✅ |
| `rendering.adaptive-fps.*` | 见 config | 0.4.0 方案 B 自适应渲染（秒级变量墙的高频间隔 + 主动推帧） | ✅ |
| `templates.auto-reload-on-startup` / `.preview-cache-seconds` / `.max-per-player` | `true` / `300` / `20` | 模板自动 reload + 缩略图缓存 TTL + 每玩家发布上限 | 部分（模板内容可 `/canvas reload templates`） |
| `images.max-size-kb` / `.allowed-mime` / `.downscale-max-edge` / `.max-per-wall` / `.max-uploads-per-day` / `.max-total-storage-mb` | `2048` / png·jpeg·webp / `1024` / `16` / `50` / `1024` | M13 图片上传：单文件 / MIME / 降采样 / 单墙 / 每日 / 全服磁盘配额 | ✅ |
| `import.canvas-max-mb` / `.canvas-max-entry-mb` / `.canvas-max-total-mb` | `10` / `10` / `50` | 0.8 `.canvas` 工程导入限额（防 zip 炸弹） | ✅ |
| `security.token-rate-limit.per-minute` | `10` | 每 IP 每分钟 WS auth token 尝试次数（防暴破） | ✅ |
| `database.auto-backup-before-migration` | `false` | migration 前自动备份 `data.db`（stable 发版后建议开） | ✅ |
| `dynamic.push-rate-limit.*` | `100` / `1000` / `10000` | 插件 Push API 单插件 / 全局每秒上限 + 熔断保护期 ms | `/canvas var reload` 热替换 |
| `dynamic.schedule.*` | `60` / "进站中" / "" | 兜底列车 ETA 进站阈值秒 + 进站 / 空闲文案 | ✅ |
| `dynamic.variables.userglobal-max-per-owner` / `.userglobal-max-total` | `500` / `10000` | 0.4.3 全局用户变量配额（每 owner / 全服） | ✅ |
| `timeline.default-fps` / `.max-fps` | `20` / `60` | 0.6 时间轴默认帧率 + 服务器级 fps 安全阀 | ✅ |
| `scripts.max-rules-per-wall` / `.max-elements-per-wall` / `.player-near-sample-ticks` / `.budget.*` / `.tween.*` / `.command-templates` | 见 config | 0.7 墙脚本：规则 / 元素配额 + 靠近采样 + 执行预算三闸 + 补间帧率 + 命令模板白名单 | `/canvas reload config` 热更（除补间 SES 上限） |

> "改了要重启 = ✅" 表示 `/canvas reload config` 不足以让该字段生效，必须重启服务器。当前 `/canvas reload config` 的 hot-apply 仅覆盖 `scripts.*` 字段（规则配额 / budget / playerNear 采样 / 克隆元素配额，且只影响后续创建，不裁剪已有）；模板内容走 `/canvas reload templates`；插件 Push 限流走 `/canvas var reload`。其余字段（host/port/池容量/超时等启动期注入项）一律需重启。各字段含义以 jar 内嵌 `config.yml` 行内注释为准（标 `[需重启]` 的项 hot-reload 不影响）。

---

## 8. 版本升级 SOP（stable 1.0.0+）

> **配套契约：** `docs/data-model.md §6.6` 定义 stable 发版（≥1.0.0）后 schema 强制 forward-only + 强制 auto-backup。本节是运维侧的 SOP。当前 `0.8.1-SNAPSHOT` 仍处 pre-release。

### 8.1 升级前

1. 停服（保证 `data.db` / world 文件不变）
2. **手动备份**：`cp -r plugins/HikariCanvas plugins/HikariCanvas.bak.<date>`
3. 备份 `data.db`（即便 plugin 自动 backup 也建议手工冗余）

### 8.2 升级中

4. 替换 jar 文件
5. 启动服务器
6. 启动期 `MigrationRunner` 自动跑 `V<currentVersion+1>..V<latest>`
   （`database.auto-backup-before-migration: true` 时每个 migration
   前再 `cp data.db data.db.pre-V<NNN>.bak`）
7. 看 server log 确认 `DB schema current version: <newVersion>` 和
   `✓ V<NNN> applied` 信息

### 8.3 升级后

8. 跑 `/canvas list` 验证 walls 数据完整
9. 至少 1 个玩家打开旧 wall 验证编辑流程正常
10. 24h 后无问题再删 backup（disk 充裕则保留 30d）

### 8.4 回滚

如启动失败 / migration 异常：

1. 停服
2. `cp data.db.pre-V<NNN>.bak data.db`（或恢复手动备份）
3. 换回旧版本 jar
4. 启动 — server log 应看到 `current version: <旧 N>` 跳过新 migration
