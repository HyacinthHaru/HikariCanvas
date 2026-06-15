# 数据与备份

HikariCanvas 的所有数据都在 `plugins/HikariCanvas/` 目录里。这一页讲哪些东西要备份、怎么备份不出错、升级时注意什么。

## 数据存在哪

| 路径 | 内容 |
|---|---|
| `data.db` | 主数据库：招牌、变量、模板记录、时刻表与铁路、脚本 |
| `data.db-wal` / `data.db-shm` | 数据库的 WAL 临时文件，和 `data.db` 是一体的 |
| `uploads/` | 玩家上传的图片 |
| `fonts/` | 你放进去的用户字体 |
| `user-templates/` | 玩家发布的模板文件 |
| `config.yml` | 插件配置 |

内置字体打包在 jar 里，不在这个目录，不用备份。

## 数据库是 WAL 模式

`data.db` 用 SQLite 的 WAL 模式运行，最新的写入可能还在 `data.db-wal` 里、没合并回主文件。所以**热备份（服务器在跑时拷）必须把三个文件一起拷**：

```
data.db
data.db-wal
data.db-shm
```

只拷 `data.db` 一个文件，可能丢掉最近的改动，甚至拷出一个不完整的库。

::: tip
最稳的做法是**先停服，再拷 `data.db`**。停服时 WAL 已经合并回主文件，单拷 `data.db` 就完整了。
:::

## 要备份什么

定期备份这几样：

1. `data.db`（连同 `data.db-wal`、`data.db-shm`，除非已停服）
2. `uploads/` 目录
3. `user-templates/` 目录（如果玩家发布过模板）
4. `config.yml`

把整个 `plugins/HikariCanvas/` 目录打包是最省心的办法。

## 升级

1. 升级前先备份（见上）。
2. 用新版 jar 替换 `plugins/` 里的旧 jar。
3. 重启服务器。

重启时插件会自动把数据库迁移到新版本结构，不用手动操作。**重大版本升级前务必先备份**——万一迁移出问题，你还能回退。

## 迁移前自动备份

`config.yml` 里有个开关，让插件在每次跑数据库迁移前先自动备份一份：

```yaml
database:
  auto-backup-before-migration: false
```

| 配置键 | 默认 | 作用 |
|---|---|---|
| `database.auto-backup-before-migration` | `false` | 开启后，每次有待执行的迁移前，先把 `data.db` 复制成 `data.db.pre-V<版本号>.bak` |

默认是关的。开启后，每个迁移版本各留一份带版本号的备份文件，方便出问题时回滚。

::: tip
这个自动备份只兜底数据库迁移这一个场景，**不能替代你自己的定期备份**——它不管 `uploads/`，也不管平时正常运行时的数据。
:::
