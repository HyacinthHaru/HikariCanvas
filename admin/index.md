# 安装与部署

HikariCanvas 是一个 Paper 插件，内置一个浏览器编辑器。玩家在游戏里框墙、拿到链接，在浏览器里排版招牌。这一页讲怎么把它装起来、默认绑在哪、以及上公网要做什么。

## 环境要求

| 项 | 要求 |
|---|---|
| 服务端 | Paper 1.21 或更高 |
| Java | 21 |

## 安装

1. 把 jar 放进服务器的 `plugins/` 目录。
2. 重启服务器。

首次启动时插件会自动创建数据目录 `plugins/HikariCanvas/`，并生成 `config.yml` 和数据库。装好后玩家就能 `/canvas edit` 开始用了。

## 默认只绑本机

插件启动后会监听一个 HTTP 端口给编辑器用。默认配置是：

| 配置键 | 默认值 | 含义 |
|---|---|---|
| `network.host` | `127.0.0.1` | 监听地址，只回环 |
| `network.port` | `8877` | 监听端口 |

`network.host` 默认是 `127.0.0.1`，意味着**只有运行服务器的那台机器能打开编辑器**。玩家在自己电脑上点链接是连不上的——这是有意为之的安全默认值。token 走的是明文 HTTP，不该直接暴露到公网。

改 `host` 或 `port` 需要重启服务器才生效。

## 编辑器链接

玩家 `/canvas confirm` 后，聊天栏给的链接由这个模板生成：

```yaml
network:
  editor-url: "http://{host}:{port}/?token={token}"
```

`{host}`、`{port}` 启动时替换成上面的配置，`{token}` 在每次签发链接时替换成一次性 token。本机默认就是 `http://127.0.0.1:8877/?token=...`。

上公网时把它改成你的域名，例如：

```yaml
editor-url: "https://canvas.example.com/?token={token}"
```

::: warning
`editor-url` 只接受 `http://` 和 `https://`。填成别的协议或带换行，插件启动时会报错并回退到默认模板。
:::

## 公网部署

要让其他机器上的玩家用编辑器，**不要**直接把 `host` 改成 `0.0.0.0` 暴露端口——那样 token 是明文传输。正确做法是放在反向代理后面，由代理负责 TLS。

最小化跑起来的流程：

1. 保持 `network.host: "127.0.0.1"` 不动，让插件只在本机监听。
2. 装 nginx 或 Caddy，配一个域名（如 `canvas.example.com`），开 HTTPS，把请求反代到 `127.0.0.1:8877`（含 WebSocket 升级）。
3. 把 `network.editor-url` 改成 `https://canvas.example.com/?token={token}`。
4. 把域名加进 `network.allowed-origins`，否则浏览器的 WebSocket 连接会被拒：

```yaml
network:
  allowed-origins:
    - "https://canvas.example.com"
```

5. 重启服务器。

::: danger
公网部署必须套 TLS 反代。直接暴露 `8877` 端口等于把一次性编辑 token 明文发到公网。
:::

反代配置示例、Origin 白名单细节、token 生命周期等见[安全与公网部署](/admin/security)。

## 下一步

- 备份和升级：[数据与备份](/admin/backup)
- 收紧权限、公网安全：[安全与公网部署](/admin/security)
