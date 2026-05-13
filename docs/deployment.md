# 部署指南

**状态：** M7 polish · 2026-05-13
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

当前版本（M7）的 wall 排他锁是**全局**的（详见 `docs/architecture.md §3`）：

- 任意时刻一个 wall 最多一个活跃 session。第二个玩家试图 `/canvas open` 同一 wall 会收 `WALL_OCCUPIED`。
- alias 是全服唯一（`UNIQUE INDEX idx_walls_alias`）。两个玩家不能用同一别名。
- 玩家**没有归属概念**：任何有 `canvas.edit` 权限的玩家都能开任何 wall（用 wall_id）。`canvas.delete.own` 限定只能删自己创建的。`canvas.delete.any` 删任意（默认 op 才有）。
- 已发布墙 `BlockBreakEvent` / `HangingBreakEvent` 全员拒绝（含 op force-break）—— 必须先 `/canvas unpublish` 才能动。详见 `docs/security.md §5`。

> 玩家身份认证（HomePage 点击直接打开、归属隔离、多人协作）是单独的 milestone，不在 M7 范围内。当前公网部署**应该把 HomePage `/api/walls` 视为对内网开放**——所有人都看得到所有人的画清单。

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
| `/canvas reload config` 看上去没生效 | host/port/池容量/超时这些"启动期注入"的字段需要重启。reload 只能刷新 config 引用 + log；hot-apply 是 M7+ 路线 |

---

## 7. config.yml 速查

完整字段说明见 jar 内嵌的 `resources/config.yml`（首次启动自动拷到 `plugins/HikariCanvas/`），下表是关键项：

| 字段 | 默认 | 含义 | 改了要重启？ |
|---|---|---|---|
| `network.host` | `127.0.0.1` | WS/HTTP 绑定 IP | ✅ |
| `network.port` | `8877` | 同上端口 | ✅ |
| `network.editor-url` | `http://{host}:{port}/?token={token}` | `/canvas confirm` 发给玩家的 URL 模板 | ✅ |
| `session.token-ttl-minutes` | `15` | token 有效期 | ❌（新 token 立即生效） |
| `session.idle-minutes` | `30` | 无输入多久回收 | ✅ |
| `map-pool.initial` / `.max` | `64` / `256` | 预览地图池容量 | ✅ |
| `throttle.projection-fps` | `5` | 服务端推送 MC 地图的 fps | ✅ |
| `throttle.input-rate-per-second` | `20` | 单玩家 WS op 速率上限 | ✅ |

> "改了要重启 = ✅" 表示 `/canvas reload config` 不足以让该字段生效，必须重启服务器。M7+ 会扩展 hot-apply 覆盖面。
