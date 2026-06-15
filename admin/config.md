# 配置

所有配置在 `plugins/HikariCanvas/config.yml`。首次启动会自动生成一份带注释的模板。

几条通用规则：

- **缺字段走默认值。** 删掉或漏写某一项不会报错，插件用内置默认值顶上。所以你只需要写要改的那几行。
- 改完用 `/canvas reload config`（需管理员权限）或重启服务器生效。
- 下表里**标「需重启」的项热重载不生效**，必须重启服务器；其余项 `/canvas reload config` 即时生效。
- 配置值会被钳进合理范围（比如帧率、配额的上下限）。填越界的值不会崩，会被自动收到边界内。

## network — 编辑器服务

编辑器的 HTTP / WebSocket 监听设置。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `network.host` | `127.0.0.1` | 监听地址。生产环境强烈建议保持回环 + 反代加 TLS（需重启） | 任意 IP |
| `network.port` | `8877` | 监听端口（需重启） | 任意端口 |
| `network.editor-url` | `http://{host}:{port}/?token={token}` | 玩家 `/canvas confirm` 后看到的链接模板。`{host}` `{port}` `{token}` 自动替换。公网部署改成你的反代域名 | 只接受 `http://` / `https://`，其它协议被拒并回退默认 |
| `network.ws-auth-timeout-seconds` | `5` | 连接建立后多少秒内必须完成认证，超时断开 | 1–60 |
| `network.allowed-origins` | `[]` | 反代域名白名单。回环、`localhost`、与监听地址同源的请求无需配置 | 字符串列表 |

::: warning 公网部署
直接把 `host` 绑到 `0.0.0.0` 会暴露未加密的访问令牌。公网必须用 nginx / Caddy 反代加 TLS，并把反代域名填进 `editor-url` 和 `allowed-origins`。
:::

## session — 会话生命周期

从玩家 `/canvas confirm` 拿到链接，到关闭浏览器的整个过程。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `session.token-ttl-minutes` | `15` | 链接令牌有效期（分钟），过期需重新 `/canvas open` | ≥ 1 |
| `session.ws-grace-minutes` | `5` | 断线后保留会话多久，供断网重连 | ≥ 1 |
| `session.idle-minutes` | `30` | 活跃会话无任何操作多久后清理。填 `0` = 永不超时（谨慎，可能泄漏内存） | ≥ 0 |
| `session.reaper-scan-seconds` | `30` | 过期会话扫描周期（秒），越小回收越及时但更费 CPU | ≥ 5 |
| `session.token-purge-minutes` | `5` | 过期令牌清理周期（分钟） | ≥ 1 |

## map-pool — 地图池

预创建的预览地图数量。这是性能核心——编辑期间只刷像素、不新建地图，避免存档膨胀。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `map-pool.initial` | `64` | 启动时预创建多少张地图。每张约 64 KiB 内存 | 1–1024 |
| `map-pool.max` | `256` | 池上限。满了之后新会话会被拒并提示等待 | ≥ initial |
| `map-pool.per-world` | `{}` | 可选。给指定世界预热地图数（多世界服务器用）。键 = 世界名 | 各项 ≥ 0，总和 ≤ max |

地图池建议值 ≈ 同时编辑的会话数 × 平均墙面格数 + 一些余量。单服 256 张约够 25 人同时编辑 4×2 的墙。

多世界示例：

```yaml
map-pool:
  per-world:
    world: 32
    world_nether: 8
    world_the_end: 4
```

未列出的世界第一次用到时按需扩容。墙和它的地图必须在同一世界。

## throttle — 投影帧率与输入限流

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `throttle.projection-fps` | `5` | 服务端把画面推到游戏地图的最大帧率。玩家停手即 0 帧 | 1–30 |
| `throttle.input-rate-per-second` | `20` | 单会话每秒输入操作上限 | ≥ 1 |
| `throttle.input-burst` | `40` | 任意两秒窗口内的突发操作上限 | ≥ input-rate-per-second |

## rendering — 自适应刷新

墙引用秒级变量（如列车 ETA）时，自动把投影间隔切到高频，并主动给在视野内的玩家补推画面。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `rendering.adaptive-fps.default-min-interval-ms` | `200` | 普通墙的渲染间隔（毫秒），200ms = 5 帧/秒 | ≥ 33 |
| `rendering.adaptive-fps.high-freq-min-interval-ms` | `50` | 高频墙的渲染间隔（毫秒），50ms = 20 帧/秒 | ≥ 33，不超过普通间隔 |
| `rendering.adaptive-fps.push-packets-enabled` | `true` | 是否主动给视野内玩家补推画面。关掉更省带宽，但画面同步会有抖动 | true / false |

## images — 图片上传

玩家拖图进编辑器的上传规则。文件按内容哈希去重存储，跨墙引用同一张图不重复占空间。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `images.max-size-kb` | `2048` | 单文件最大大小（KiB），超出拒收 | ≥ 1 |
| `images.allowed-mime` | `["image/png", "image/jpeg", "image/webp"]` | 允许的图片格式 | MIME 列表 |
| `images.downscale-max-edge` | `1024` | 解码后边长上限，超出自动缩放 | ≥ 64 |
| `images.max-per-wall` | `16` | 单面墙图片元素上限。`0` = 不限 | ≥ 0 |
| `images.max-uploads-per-day` | `50` | 单玩家 24 小时上传次数。`0` = 不限 | ≥ 0 |
| `images.max-total-storage-mb` | `1024` | 全服图片磁盘总配额（MiB），超出触发自动清理最久未用的文件。`0` = 不限 | ≥ 0 |

## dynamic — 变量与时刻表

动态数据相关：插件推送限流、兜底时刻表文案、全局变量配额。

### dynamic.push-rate-limit — 插件推送限流

防止有问题的第三方插件刷爆变量系统。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `dynamic.push-rate-limit.per-plugin-per-second` | `100` | 单个插件每秒推送上限，超出丢弃 | ≥ 1 |
| `dynamic.push-rate-limit.global-per-second` | `1000` | 全局每秒推送上限，超出触发保护期 | ≥ per-plugin 值 |
| `dynamic.push-rate-limit.global-circuit-break-ms` | `10000` | 触发全局上限后的保护期（毫秒），期间所有推送拒收 | ≥ 0 |

正常业务（比分、列车 ETA 等）每秒 1–10 次足矣，默认上限绰绰有余。

### dynamic.schedule — 兜底时刻表

简易列车/公交时刻表的进站判定与显示文案。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `dynamic.schedule.arriving-threshold-seconds` | `60` | 距发车时间小于等于此值（秒）即视为「进站中」 | ≥ 0 |
| `dynamic.schedule.arriving-text` | `进站中` | 进站时显示的文案 | 任意文本 |
| `dynamic.schedule.idle-text` | （空） | 非进站时显示的文案 | 任意文本 |

### dynamic.variables — 全局变量配额

玩家创建的跨画布共享变量的数量上限。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `dynamic.variables.userglobal-max-per-owner` | `500` | 每位玩家可创建的全局变量数 | ≥ 1 |
| `dynamic.variables.userglobal-max-total` | `10000` | 全服全局变量总数 | ≥ 1 |

## timeline — 时间轴动画

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `timeline.default-fps` | `20` | 新建时间轴的默认帧率 | ≥ 1，不超过 max-fps |
| `timeline.max-fps` | `60` | 单墙帧率硬上限，保护整台服务器 | ≥ 1 |

`max-fps` 是管理员保护服务器的总阀门——插件不做成本估算、不自动降级，帧率由你主动设。

## scripts — 墙脚本

视觉脚本（积木）的资源约束与命令权限。

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `scripts.max-rules-per-wall` | `16` | 单面墙可挂的脚本规则数上限 | ≥ 1 |
| `scripts.max-elements-per-wall` | `200` | 单面墙元素总数上限。仅约束脚本「克隆元素」积木，不限制正常编辑 | ≥ 1 |
| `scripts.player-near-sample-ticks` | `10` | 「玩家靠近」触发器的采样间隔（游戏 tick，20 tick = 1 秒）。越小越灵敏，越费主线程 | 1–200 |
| `scripts.budget.max-actions-per-run` | `50` | 单次触发可执行的动作总数（含嵌套分支与等待续接） | ≥ 1 |
| `scripts.budget.max-runs-per-second` | `10` | 单条规则每秒最多触发次数，超出丢弃并记审计 | ≥ 1 |
| `scripts.budget.max-chain-depth` | `8` | 脚本互相触发的链深上限，达到即掐断本次执行（防死循环） | ≥ 1 |
| `scripts.tween.max-fps` | `60` | 补间动画的单墙帧率硬上限 | ≥ 1 |
| `scripts.tween.max-concurrent` | `16` | 同时活跃的补间动画数上限，超限新任务报错 | ≥ 1 |

以上各项 `/canvas reload config` 热重载生效，且只影响后续创建/触发，不裁剪已有的规则与任务。`player-near-sample-ticks` 因底层任务固定每 2 tick 跑一次，填 `1` 和 `2` 效果相同。

### scripts.command-templates — 执行命令白名单

这是脚本里「执行命令」积木能调用的命令清单，也是**给玩家放开服务器命令的唯一安全闸**。

**默认不内置任何模板。** 没在这里写的命令，脚本引用了也不会执行。要让玩家脚本能跑命令，你必须自己在这里登记。

每条模板长这样：

```yaml
scripts:
  command-templates:
    announce:
      command: "say [招牌] {msg}"
      params:
        msg: { max-length: 64 }
    give-reward:
      command: "give {player} diamond 1"
      params:
        player: { type: online-player }
```

- `command` 是真正执行的命令全文，`{参数名}` 是占位符，由脚本作者填值。
- 每个占位符都要在 `params` 里声明，否则不会被替换。填入的值会自动剥掉换行和颜色码。
- `max-length` 限制单个参数的长度，默认 `64`。
- 命令以**服务器控制台身份**执行——模板是你写的，等于你授权了这些命令。
- 每次执行都会进审计日志。

::: warning 选择器夺权防护
参数值里带 `@`（如 `@a` / `@e`）一律拒绝执行，除非该参数声明了 `type: online-player`（此时值必须精确匹配某个在线玩家名）。设计模板时把自由文本参数（如 `say` 的消息）放在命令**末尾**，别放在 `give` 这类按空格切分参数的命令中间。
:::

## security — 令牌限流

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `security.token-rate-limit.per-minute` | `10` | 每个 IP 每分钟可尝试的令牌次数，防暴力枚举。超限断开并记审计 | ≥ 1 |

合法用户的重连远低于每分钟 10 次，超出基本就是攻击。

## database — 迁移备份

| 键 | 默认 | 作用 | 取值范围 |
|---|---|---|---|
| `database.auto-backup-before-migration` | `false` | 升级时跑数据库结构迁移前，是否先自动备份。备份文件带版本号便于回滚 | true / false |

正式上线后建议改成 `true`，给每次结构升级留一份回滚点。
