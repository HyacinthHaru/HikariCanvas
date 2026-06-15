# 命令

HikariCanvas 只有一个根命令 `/canvas`，下面分成三组：日常招牌操作、变量管理 `var`、性能测试 `bench`。

## `/canvas` 主命令族

招牌的创建、编辑、删除都在这里。

| 命令 | 作用 | 权限 |
|---|---|---|
| `/canvas edit` | 进入框选模式并拿到画布魔杖 | `canvas.edit` |
| `/canvas wand` | 单独领一把画布魔杖 | `canvas.edit` |
| `/canvas confirm` | 确认框选，挂好画框并返回一条编辑器链接 | `canvas.edit` |
| `/canvas cancel` | 取消当前会话（招牌数据保留） | `canvas.edit` |
| `/canvas open <编号或别名>` | 直接打开一面已有招牌，返回编辑器链接 | `canvas.edit` |
| `/canvas list` | 列出你自己的所有招牌 | `canvas.edit` |
| `/canvas alias <别名>` | 给当前编辑的招牌起别名（2-32 位字母数字 `_` `-`） | `canvas.edit` |
| `/canvas delete <编号>` | 删除招牌第一步，给出 30 秒确认提示 | `canvas.delete.own` 或 `canvas.delete.any` |
| `/canvas delete <编号> confirm` | 30 秒内确认删除 | `canvas.delete.own` 或 `canvas.delete.any` |
| `/canvas stats` | 查看地图池、招牌、会话、令牌的实时统计 | `canvas.admin` |
| `/canvas reload templates` | 重新加载模板库 | `canvas.admin` |
| `/canvas reload config` | 重新加载 `config.yml`（改端口需重启生效） | `canvas.admin` |

::: tip 删除是两步
先 `/canvas delete <编号>`，再在 30 秒内补一句 `/canvas delete <编号> confirm` 才会真删。
:::

::: warning 别名归属
只有招牌作者本人能改别名；管理员需要 `canvas.alias.any` 才能改别人的招牌别名。
:::

`/canvas cleanup` 目前是占位，**暂未启用**，运行只会提示该功能尚未实装。

## `/canvas var` 变量命令族

管理动态变量（玩家变量、系统变量、插件推送的变量等）。整组命令统一需要 `canvas.var.command` 权限。

| 命令 | 作用 | 权限 |
|---|---|---|
| `/canvas var list [命名空间]` | 列出所有命名空间，或某个命名空间下的变量 | `canvas.var.command` |
| `/canvas var get <变量全名>` | 查看一个变量的完整信息（类型、默认值、当前值、来源等） | `canvas.var.command` |
| `/canvas var set <变量全名> <值...>` | 手动给变量设值（值可以含空格） | `canvas.var.command` |
| `/canvas var delete <变量全名>` | 删除一个变量 | `canvas.var.command` |
| `/canvas var providers` | 列出已注册的变量来源 | `canvas.var.command` |
| `/canvas var reload` | 重读 `config.yml` 的推送限流参数 | `canvas.var.command` |
| `/canvas var inspect <招牌编号>` | 查看某面招牌引用了哪些变量及其当前值 | `canvas.var.command` |

## `/canvas bench` 性能测试命令族

在后台跑纯服务端的渲染 / 补间 / 脚本性能测试，结果写到 `plugins/HikariCanvas/benchmarks/`。整组命令统一需要 `canvas.bench` 权限。

| 命令 | 作用 | 权限 |
|---|---|---|
| `/canvas bench list` | 列出所有内置测试场景 | `canvas.bench` |
| `/canvas bench run [场景] [轮次] [预热]` | 后台运行渲染性能测试，跑完发摘要并存报告 | `canvas.bench` |
| `/canvas bench run-tween [轮次] [预热]` | 补间动画帧率压测 | `canvas.bench` |
| `/canvas bench run-script [轮次] [预热]` | 脚本动作链开销压测 | `canvas.bench` |
| `/canvas bench report [id]` | 打印最近一份或指定的测试报告 | `canvas.bench` |
| `/canvas bench clear` | 清空已保存的测试报告 | `canvas.bench` |

::: tip 测试只测 CPU 和内存
性能测试只在后台模拟渲染与内存开销，**不碰网络、不写世界、不动地图池**，可以放心在线上跑。同一时刻只允许跑一个测试。
:::
