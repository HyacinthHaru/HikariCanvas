# HikariCanvas 部署指南

这篇指南面向**服主与运维人员**，按从易到难的顺序讲清楚三件事：

1. 单机与局域网怎么装上即用；
2. 为什么默认只绑 `127.0.0.1`、不能把端口直接裸露到公网；
3. 公网服务器如何用 Caddy 或 nginx 配好 HTTPS 反向代理，以及日常配置、多语言与备份升级流程。

---

## 1. 单机与局域网快速部署

> **必装前置：PacketEvents 插件。**
> HikariCanvas 通过 PacketEvents 向客户端发送地图画面数据包。由于 PacketEvents 采用 GPL-3.0 协议，为避免传染本项目的 MIT 协议，插件包内不捆绑它——请先前往 [Modrinth](https://modrinth.com/plugin/packetevents) 下载 **PacketEvents（`2.13.0` 及以上）** 放入 `plugins/`，再放入 HikariCanvas。未安装前置时服务器启动会提示缺少必需依赖。

把两个 jar 放进 `plugins/` 目录并启动服务器，首次运行会自动生成 `plugins/HikariCanvas/config.yml`。发布包体积约 86–90 MB，是因为内置了 22 款开源字体、矢量图标库和完整的网页编辑器，服主无需额外安装系统字体，首次启动稍慢属于正常现象。

**服务端与 Java 版本对应关系**（同一份 jar 直接通吃，无需按版本下载不同包）：
- 运行 **Paper 1.21.11**：使用 **Java 21**
- 运行 **Paper 26.1 / 26.2**：使用 **Java 25**（Minecraft 26 自身的运行要求）

默认的网络配置如下：

```yaml
network:
  host: "127.0.0.1"
  port: 8877
  editor-url: "http://{host}:{port}/?token={token}"
```

- **本机开服、本机编辑**：保持默认配置即可。进游戏执行 `/canvas edit` 选墙、`/canvas confirm` 确认后，直接在本机浏览器点开聊天栏弹出的 `http://127.0.0.1:8877/...` 链接就能开始画。
- **局域网联机（同一路由器 / 内网）**：把 `host` 改成那台开服电脑的内网 IP（例如 `192.168.1.100`），`editor-url` 保持上面模板不变或同步填入内网 IP，然后**重启服务器**。同局域网玩家点开链接即可访问（家庭或寝室局域网内明文 HTTP 风险可控；公网环境请务必按第 3 节配置 HTTPS）。

> 修改 `host`、`port`、`editor-url` 或 `allowed-origins` 后**必须重启服务器**——监听端口在启动时绑定，`/canvas reload config` 不会重新绑定端口。

---

## 2. 为什么默认只绑 `127.0.0.1`？

网页编辑器的链接里包含一次性访问令牌（`token`），编辑过程中的画布操作与图片上传则通过 WebSocket / HTTP 传输。如果把 `8877` 端口直接以明文 HTTP 暴露在公网上：

- **令牌容易在公网链路被截获**：在公共 Wi-Fi、校园网或不可信网络环境下，明文传输的链接一旦被中间节点嗅探，对方就能在令牌失效前抢先打开会话；
- **编辑内容全程明文可见**：后续所有的文字修改、脚本配置在网络层都是未加密状态。

因此插件默认锁死 `127.0.0.1`（仅允许服务器本机进程连接）。公网开服时，让 Caddy 或 nginx 在前面承接 443 端口的 HTTPS / WSS 加密流量，再在服务器本机环回转发给 `127.0.0.1:8877`，既安全又省心。

---

## 3. 公网服务器部署（推荐：Caddy / nginx + HTTPS）

### 3.1 流量走向

```text
玩家浏览器
   │  HTTPS / WSS（公网 443 端口，全程 TLS 加密）
   ▼
反向代理（Caddy 或 nginx，自动管理 Let's Encrypt 证书）
   │  HTTP / WS（仅本机内部 127.0.0.1:8877）
   ▼
HikariCanvas 内置网页服务
```

HikariCanvas 自身继续保持 `host: "127.0.0.1"`，不需要向公网开放 `8877` 端口。

### 3.2 准备一个域名

准备一个解析到服务器公网 IP 的域名或子域名（例如 `canvas.example.com`），确保服务器防火墙与云厂商安全组放行了 `80`（用于签发证书）与 `443` 端口。

### 3.3 方案一：使用 Caddy（最省事，自动签发并续期证书）

编辑 `/etc/caddy/Caddyfile`：

```caddy
canvas.example.com {
    reverse_proxy 127.0.0.1:8877 {
        transport http {
            keepalive 60s
        }
    }

    encode zstd gzip
}
```

保存后执行 `systemctl reload caddy`，Caddy 会自动申请 Let's Encrypt 证书并转发 HTTP 与 WebSocket 连接（令牌鉴权与接口限流由插件后端自带保护，无需额外编译第三方 Caddy 限速模块）。

### 3.4 方案二：使用 nginx

编辑 `/etc/nginx/sites-available/hikari-canvas.conf`（并软链到 `sites-enabled/`）：

```nginx
server {
    listen 443 ssl http2;
    server_name canvas.example.com;

    ssl_certificate     /etc/letsencrypt/live/canvas.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/canvas.example.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass         http://127.0.0.1:8877;
        proxy_http_version 1.1;

        # 转发 WebSocket 升级头（漏配会导致网页能打开但连不上画布）
        proxy_set_header   Upgrade           $http_upgrade;
        proxy_set_header   Connection        "upgrade";

        # 透传真实客户端 IP 与域名
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   Host              $host;

        # 保持编辑会话长连接不断线
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}

server {
    listen 80;
    server_name canvas.example.com;
    return 301 https://$host$request_uri;
}
```

用 `certbot --nginx -d canvas.example.com` 签发证书后，执行 `nginx -s reload` 即可。

### 3.5 修改 `plugins/HikariCanvas/config.yml`

反代配好后，把对外域名填进 `editor-url` 和 `allowed-origins`（**两处都要配**，漏配 `allowed-origins` 会触发防跨站拦截导致网页报 403 连不上）：

```yaml
network:
  host: "127.0.0.1"      # 保持本机监听不变
  port: 8877             # 与反代转发端口一致
  editor-url: "https://canvas.example.com/?token={token}"
  allowed-origins:
    - "https://canvas.example.com"
```

保存后**重启 Minecraft 服务器**即可生效。

### 3.6 防火墙检查

确认公网防火墙只对外开放 `443`（反代）与游戏端口（如 `25565`），**不要对外放行 `8877`**。因为插件默认绑在 `127.0.0.1`，内核层面本就不会接收外部网卡流量；若你想在防火墙再加一道双保险：

```bash
# ufw 示例
ufw allow 443/tcp
ufw deny 8877/tcp
```

---

## 4. 临时直绑公网（不推荐）

如果你只是临时开一台测试服验证功能、没有域名，且清楚明文传输令牌的风险，可以短暂改为：

```yaml
network:
  host: "0.0.0.0"
  port: 8877
  editor-url: "http://你的公网IP:8877/?token={token}"
```

**切勿在正式服长期这样运行**——公网明文传输下，链路上的第三方只要抓到链接就能抢先接管编辑会话。插件内置的网页服务不直接挂载证书文件，正式对外请一律采用第 3 节的反向代理方案。

---

## 5. 多语言与提示文案

### 5.1 自动跟随客户端语言

游戏内的命令回复、魔棒选区提示和压测输出会自动匹配**每位玩家自己的 Minecraft 客户端语言**：中文客户端看简体中文（`zh_cn`），英文客户端看英文（`en_us`），服主不需要手动切换。

当某位玩家的客户端语言没有对应的语言文件时，会回落使用 `config.yml` 中的 `i18n.default-locale`（默认 `en_us`）。

### 5.2 自定义文案或新增语种

首次启动后，内置的 `zh_cn.yml` 和 `en_us.yml` 会自动释放到 `plugins/HikariCanvas/lang/` 目录：

- **修改现有提示语**：直接编辑对应 `.yml` 文件（支持 `<gold>`、`<gradient:...>` 等 MiniMessage 颜色格式），保存后执行 `/canvas reload config` 即刻生效，不用重启服务器。
- **添加其他语言**：按 Minecraft 语言代号命名放入同目录（例如 `zh_tw.yml`、`ja_jp.yml`），再执行 `/canvas reload config` 即可识别。
- **强制全服统一显示中文**：把 `config.yml` 里的 `i18n.default-locale` 设为 `zh_cn`，并删掉（或替换）其他语种的 `.yml` 文件，然后 `/canvas reload config`。

---

## 6. 多人协作、防破坏与控制台管理

日常运营中常遇到的几个多人规则（完整权限节点配法见 [玩家与服主使用手册 §4](user-guide.md#第四部分服主权限配置指南)）：

- **单画板排他编辑**：同一时刻一块画板最多只能被一个人打开编辑；第二个人尝试 `/canvas open` 时会提示该画板正在被他人编辑。
- **别名全服唯一**：画板别名全服不重名，先到先得。
- **草稿协作与作者锁定**：未锁定的画板默认允许其他有编辑权限的玩家用编号打开协作；作者点击网页编辑器顶栏的 **锁定** 按钮后，画板变为只读，非作者无法再打开修改（拥有 `canvas.admin.bypass-lock` 权限的管理员除外）。
- **游戏内防破坏保护**：所有由插件生成的招牌展示框及其背后的支撑方块默认受保护，普通玩家无法敲掉（无论画板是否锁定）；只有拥有 **`canvas.admin.force-break`** 权限的管理员才能强行破坏。正常拆除画板请使用 `/canvas delete <画板ID>`。

### 6.1 从服务端控制台直接管理画布

服主不进游戏也能在后台控制台管理全服画板：

| 控制台命令 | 作用 |
| --- | --- |
| `canvas list` | 列出全服所有画板 |
| `canvas open <画板ID或别名>` | 打开任意画板（含玩家已锁定的画板），在控制台打印一条编辑链接 |
| `canvas alias <新别名>` | 修改当前控制台已打开画板的别名 |
| `canvas delete <画板ID> [confirm]` | 删除任意画板（同样需在 30 秒内带 `confirm` 二次确认） |
| `canvas cancel` | 结束当前控制台编辑会话，释放画板占用 |

> **日志脱敏提醒**：在控制台执行 `canvas open` 时，带一次性访问令牌的链接会一并写入 `logs/latest.log`。虽然该令牌 15 分钟自动过期且首次打开即绑定浏览器 IP，但如果你准备把 `latest.log` 发给他人帮忙排错，请先执行一次 `canvas cancel` 让令牌立即作废。

> **首页公开列表说明**：直接访问编辑器根地址（不带 `?token=`）会展示当前服务器的画板概览列表（只读展示编号、尺寸与更新时间，无法点进画布修改）；只有持有游戏内或控制台签发的有效令牌才能进入编辑。

---

## 7. 快速排障对照

遇到问题时，管理员可先在游戏或控制台跑两条只读检查命令：
- `/canvas stats` —— 看地图池余量、活跃会话、变量与动画概况；
- `/canvas diagnose <画板ID>` —— 逐项体检某块画板为什么不显示。

| 常见现象 | 常见原因与处理办法 |
| --- | --- |
| 打开编辑链接一直转圈或拒绝连接 | 仍在用默认的 `127.0.0.1` 从外网/别机访问；按第 1 节改局域网 IP 或按第 3 节配反向代理 |
| 通过域名打开网页提示连不上，后台报 `Origin=... not in allowlist` | `config.yml` 的 `network.allowed-origins` 漏填了你的 `https://域名`，补上后重启服务器 |
| 网页能打开但立刻提示认证失败（`4001`） | 链接已用过一次或超过 15 分钟过期；回游戏重新 `/canvas open <编号>` 拿新链接 |
| 走 nginx 反代时编辑几分钟就断线重连 | nginx 默认读取超时太短；在对应 `location` 加上 `proxy_read_timeout 3600s;` |
| 网页能开但一直卡在连接中 | 反代漏配了 WebSocket 升级头（`Upgrade` 与 `Connection "upgrade"`） |
| 跑了 `/canvas reload config` 但改的配置没生效 | 端口、域名、地图池、配额等需要在启动时初始化的项必须**重启服务器**才生效（见下表） |

更完整的故障现象说明见 [常见问题排错](troubleshooting.md)。

---

## 8. `config.yml` 核心配置速查

首次启动生成的 `plugins/HikariCanvas/config.yml` 内每一项都附有详细中文注释。下表汇总常用字段及修改后是否需要重启服务器：

| 配置项 | 默认值 | 含义说明 | 生效方式 |
| --- | --- | --- | --- |
| `network.host` / `.port` | `127.0.0.1` / `8877` | 内置网页与 WebSocket 监听地址和端口 | 重启服务器 |
| `network.editor-url` | `http://{host}:{port}/?token={token}` | 发给玩家的编辑器链接模板（仅支持 `http` / `https`） | 重启服务器 |
| `network.ws-auth-timeout-seconds` | `5` | 网页建连后完成令牌认证的超时秒数 | 重启服务器 |
| `network.allowed-origins` | `[]` | 反向代理对外域名白名单（如 `https://canvas.example.com`） | 重启服务器 |
| `session.token-ttl-minutes` | `15` | 编辑链接令牌的有效期（分钟） | 重启服务器 |
| `session.ws-grace-minutes` | `5` | 浏览器断线后保留会话供重连的宽限期（分钟） | 重启服务器 |
| `session.idle-minutes` | `30` | 打开编辑器后长时间无操作自动释放的分钟数（`0` 为不超时） | 重启服务器 |
| `map-pool.initial` / `.max` | `64` / `256` | 地图池初始预热张数与最大容量上限 | 重启服务器 |
| `map-pool.per-world` | `{}` | 多世界服务器按世界单独指定初始预热地图张数 | 重启服务器 |
| `limits.canvas-max-maps` | `16` | 新建单块招牌最多允许占用的地图张数（`1`–`64`，不影响已有老招牌） | 重启服务器 |
| `throttle.projection-fps` | `5` | 常规编辑时向游戏内地图推送画面的每秒帧数上限 | 重启服务器 |
| `throttle.input-rate-per-second` / `.input-burst` | `20` / `40` | 单名玩家每秒编辑操作速率与突发上限 | 重启服务器 |
| `rendering.adaptive-fps.*` | 见配置文件 | 含秒级动态变量画板的刷新间隔设置 | 重启服务器 |
| `templates.auto-reload-on-startup` / `.preview-cache-seconds` / `.max-per-player` | `true` / `300` / `20` | 启动自动加载模板、缩略图缓存秒数、单人发布工坊模板上限 | 配置需重启；新增模板文件可跑 `/canvas reload templates` |
| `images.max-size-kb` / `.downscale-max-edge` / `.max-per-wall` / `.max-uploads-per-day` / `.max-total-storage-mb` | `2048` / `1024` / `16` / `50` / `1024` | 图片上传限制：单图大小、最大边长缩放、单墙张数、单人每日次数、全服总磁盘空间（标准 JDK 默认支持 PNG 与 JPEG） | 重启服务器 |
| `import.canvas-max-mb` / `.canvas-max-entry-mb` / `.canvas-max-total-mb` | `10` / `10` / `50` | `.canvas` 工程包导入体积上限（防超大压缩包） | 重启服务器 |
| `security.token-rate-limit.per-minute` | `10` | 同一 IP 每分钟最多尝试验证令牌的次数 | 重启服务器 |
| `i18n.default-locale` | `en_us` | 玩家客户端语言无匹配文件时的默认语言（如 `zh_cn`） | `/canvas reload config` 即时生效 |
| `database.auto-backup-before-migration` / `.backup-retention-days` | `true` / `30` | 插件升级变更数据库结构前自动备份 `data.db`，以及旧备份保留天数 | 重启服务器 |
| `database.audit-retention-days` | `90` | 操作审计日志保留天数（`0` 为永久保留） | 重启服务器 |
| `dynamic.push-rate-limit.*` | `100` / `1000` / `10000` | 第三方插件推送变量的单插件上限、全服上限与熔断冷却毫秒数 | `/canvas var reload` 即时生效 |
| `dynamic.schedule.*` | `60` / `"进站中"` / `""` | 列车倒计时进站阈值（秒）及进站、空闲显示文字 | 重启服务器 |
| `dynamic.variables.userglobal-max-*` | `500` / `10000` | 全局用户变量的单人上限与全服总上限 | 重启服务器 |
| `timeline.default-fps` / `.max-fps` | `20` / `60` | 时间轴动画默认帧率与全服最高允许帧率 | 重启服务器 |
| `scripts.*`（配额、预算、靠近采样、命令模板白名单） | 见配置文件 | 单墙脚本规则上限、元素上限、靠近检测间隔、执行预算三道安全阀与白名单命令模板 | `/canvas reload config` 即时生效（补间并发池大小除外） |

---

## 9. 备份、版本升级与回滚

插件的所有持久化数据都集中在 `plugins/HikariCanvas/` 目录下（核心数据库 `data.db`、上传图片目录 `uploads/`、玩家工坊模板 `user-templates/` 以及配置文件 `config.yml`），地图物品本身则保存在对应世界的存档数据中。

### 9.1 常规升级步骤

1. **停服备份**：先正常关停服务器，把整个插件数据目录完整拷贝一份留底：
   ```bash
   cp -r plugins/HikariCanvas plugins/HikariCanvas.bak.$(date +%Y%m%d)
   ```
2. **替换插件包**：将新版 `HikariCanvas-<版本>.jar` 放入 `plugins/`，删除旧版 jar。
3. **启动自动迁移**：启动服务器。如果新版包含数据库表结构升级，插件会在升级前自动在目录内额外生成一份 `data.db.pre-V<编号>.bak` 备份，随后完成向前兼容迁移并在日志打印 `DB schema current version`。
4. **进服抽查**：执行 `/canvas stats` 和 `/canvas list`，确认原有画板显示与打开编辑一切正常。确认稳定运行一两天后再清理手动备份即可。

### 9.2 异常回滚

如果升级后遇到异常需要退回旧版：

1. 关停服务器；
2. 先删除 `plugins/HikariCanvas/` 目录中可能残留的 `data.db-wal` 与 `data.db-shm`，再用升级前的 `data.db.pre-V<编号>.bak` 覆盖恢复 `plugins/HikariCanvas/data.db`（若备份时一并生成了对应的 `.bak-wal` 与 `.bak-shm`，也同步覆盖恢复为 `data.db-wal` 与 `data.db-shm`；或直接整目录恢复 9.1 中的手动备份）；
3. 换回旧版插件 jar 并启动服务器即可。
