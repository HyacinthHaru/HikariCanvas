# 权限

HikariCanvas 的设计是「默认开放」：画招牌、改文字、传图片、用模板、起变量、绑时刻表这些日常功能，**默认对所有玩家开放**，服主按需收紧即可。

只有少数敏感操作默认**仅 OP**：执行命令积木、全服事件触发器、改别人的内容、上传/配额放行、以及所有管理类操作。

下面按功能分组列出全部权限节点。「默认」一栏中，**所有人**表示默认所有玩家都有，**仅 OP** 表示默认只有管理员有。

## 基础编辑

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.use` | 所有人 | 使用 HikariCanvas 的任意功能 |
| `canvas.edit` | 所有人 | 打开编辑会话 |
| `canvas.wand` | 所有人 | 领取画布魔杖 |
| `canvas.commit` | 所有人 | 保存招牌 |
| `canvas.delete.own` | 所有人 | 删除自己的招牌 |
| `canvas.delete.any` | 仅 OP | 删除任何人的招牌 |
| `canvas.alias.any` | 仅 OP | 给任何招牌改别名（管理用途） |

## 管理

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.admin` | 仅 OP | 管理命令（统计 / 清理 / 重载） |
| `canvas.admin.force-break` | 仅 OP | 破坏 HikariCanvas 挂的画框 / 支撑方块 |
| `canvas.admin.bypass-limit` | 仅 OP | 绕过频率限制和画布尺寸上限 |
| `canvas.admin.bypass-lock` | 仅 OP | 打开 / 编辑别人锁定的招牌 |
| `canvas.bench` | 仅 OP | 运行服务端性能测试 |

## 上传

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.upload` | 所有人 | 上传图片用作图片元素 |
| `canvas.upload.bypass-limit` | 仅 OP | 绕过单人 / 单墙 / 总磁盘的图片配额 |

## 模板

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.template.save` | 所有人 | 把当前招牌发布为创意工坊模板 |
| `canvas.template.delete.own` | 所有人 | 删除自己发布的模板 |
| `canvas.template.delete.any` | 仅 OP | 删除任何模板（管理用途） |
| `canvas.template.feature` | 仅 OP | 把模板设为 / 取消「精选」 |
| `canvas.template.bypass-limit` | 仅 OP | 绕过单人模板发布数量配额 |
| `canvas.template.use-others` | 仅 OP | 套用别人发布的用户模板 |

## 变量

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.var.read` | 所有人 | 查看变量列表与值 |
| `canvas.var.write.own` | 所有人 | 在自己的招牌上创建 / 修改用户变量 |
| `canvas.var.write.any` | 仅 OP | 在任意招牌上修改用户变量 |
| `canvas.var.delete.own` | 所有人 | 删除自己招牌上的用户变量 |
| `canvas.var.delete.any` | 仅 OP | 删除任意招牌上的用户变量 |
| `canvas.var.bind` | 仅 OP | 把用户变量绑定到插件命名空间（敏感） |
| `canvas.var.command` | 仅 OP | 使用 `/canvas var` 命令族 |
| `canvas.var.global.create` | 所有人 | 创建跨招牌共享的全局用户变量 |
| `canvas.var.global.write.own` | 所有人 | 修改自己创建的全局用户变量 |
| `canvas.var.global.write.any` | 仅 OP | 修改任意全局用户变量 |
| `canvas.var.global.delete.own` | 所有人 | 删除自己创建的全局用户变量 |
| `canvas.var.global.delete.any` | 仅 OP | 删除任意全局用户变量 |

## 时刻表与铁路

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.schedule.own` | 所有人 | 管理自己招牌的列车 / 公交时刻表 |
| `canvas.schedule.any` | 仅 OP | 管理任意招牌的时刻表 |
| `canvas.rail.line.create` | 所有人 | 创建铁路线路 |
| `canvas.rail.line.edit.own` | 所有人 | 编辑自己创建的线路 / 站点 / 车次 / 时刻表 |
| `canvas.rail.line.edit.any` | 仅 OP | 编辑任意铁路线路 |
| `canvas.rail.line.delete.own` | 所有人 | 删除自己创建的铁路线路 |
| `canvas.rail.line.delete.any` | 仅 OP | 删除任意铁路线路 |
| `canvas.rail.wall.bind` | 所有人 | 把招牌绑定到铁路网络 |

## 脚本

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.script.edit` | 所有人 | 给自己能打开的招牌编排积木脚本 |
| `canvas.script.trigger.global` | 所有人 | 使用全服事件触发器（玩家进服 / 被击杀等） |
| `canvas.script.sound` | 所有人 | 在脚本里使用「播放声音」积木 |
| `canvas.script.command` | 仅 OP | 在脚本里使用「执行命令模板」积木（危险面） |
