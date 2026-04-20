# 数据模型

**状态：** 立项稿 v0.1 · 2026-04-19
**适用范围：** SQLite schema、PersistentDataContainer 约定、`.canvas` 工程文件格式、迁移策略

本文档定义所有持久化数据的结构。**一旦 v1.0 发布，schema 变更必须通过迁移脚本完成**；不允许在线上直接改表。

---

## 1. 存储分层

| 存储位置 | 内容 | 生命周期 |
| --- | --- | --- |
| SQLite `data.db` | 池元信息、招牌记录、审计日志、模板统计 | 跨服务器重启；随世界快照备份 |
| `MapView` PDC | 地图 owner、signId 标签、用途区分 | 随世界文件；MC 本体持久化 |
| `ItemFrame` PDC | sessionId / signId 标签 | 随世界文件 |
| 文件：`templates/*.yml` | 模板定义 | 人工管理 |
| 文件：`user-templates/<uuid>/` | 玩家上传模板（v1.x） | 按玩家 uuid 组织 |
| 文件：`fonts/*.ttf` / `*.woff2` | 字体 | 人工管理 |
| 文件：`.canvas` 工程导出 | 玩家导出的工程 | 外部管理 |

---

## 2. SQLite Schema

### 2.1 基础

- 文件路径：`plugins/HikariCanvas/data.db`
- 连接池：HikariCP，最大 4 连接（插件内异步 I/O 即可）
- 访问层：JDBI 3（轻量、类型安全，比 JOOQ 启动快）
- 所有时间戳：`INTEGER NOT NULL`，Unix 毫秒时间戳（UTC）
- 所有 UUID：`TEXT`，标准 36 字符带连字符格式
- 所有 JSON blob：`TEXT`

### 2.2 表：`schema_version`

追踪当前 DB schema 版本，迁移用。

```sql
CREATE TABLE schema_version (
  version INTEGER PRIMARY KEY,
  applied_at INTEGER NOT NULL
);
```

启动时读最大 `version`，若低于插件内置 `CURRENT_VERSION` 则按顺序应用迁移脚本。

### 2.3 表：`pool_maps`

预览地图池元数据。每条记录对应一张 MC map ID。

```sql
CREATE TABLE pool_maps (
  map_id        INTEGER PRIMARY KEY,       -- MC map ID
  state         TEXT NOT NULL,             -- 'FREE' | 'RESERVED' | 'PERMANENT'
  reserved_by   TEXT,                      -- session UUID (state=RESERVED 时)
  sign_id       TEXT,                      -- SignRecord.id (state=PERMANENT 时)
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER NOT NULL,
  world         TEXT                       -- PERMANENT 时冗余记录所在世界，便于清理
);

CREATE INDEX idx_pool_state ON pool_maps(state);
CREATE INDEX idx_pool_sign ON pool_maps(sign_id);
CREATE INDEX idx_pool_session ON pool_maps(reserved_by);
```

**不变式：**
- `state=FREE`：`reserved_by`、`sign_id` 均为 NULL
- `state=RESERVED`：`reserved_by` 非 NULL、`sign_id` 为 NULL
- `state=PERMANENT`：`sign_id` 非 NULL、`reserved_by` 为 NULL

插件启动时执行一次性扫描验证不变式，异常记录移回 FREE + 告警。

### 2.4 表：`sign_records`

已提交的招牌。一条记录对应一块完整招牌（含多张 map）。

```sql
CREATE TABLE sign_records (
  id            TEXT PRIMARY KEY,          -- UUID
  owner_uuid    TEXT NOT NULL,             -- 玩家 UUID
  owner_name    TEXT NOT NULL,             -- 冗余，避免玩家改名后查不到
  world         TEXT NOT NULL,
  origin_x      INTEGER NOT NULL,
  origin_y      INTEGER NOT NULL,
  origin_z      INTEGER NOT NULL,
  facing        TEXT NOT NULL,             -- 'NORTH' | 'SOUTH' | 'EAST' | 'WEST'
  width_maps    INTEGER NOT NULL,
  height_maps   INTEGER NOT NULL,
  map_ids       TEXT NOT NULL,             -- JSON array，长度 = width*height
  project_json  TEXT NOT NULL,             -- 完整 ProjectState，用于二次编辑
  template_id   TEXT,                      -- 源模板 ID（可空）
  template_version INTEGER,                 -- 当时模板的 version
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  deleted_at    INTEGER                    -- 软删除，NULL=存在
);

CREATE INDEX idx_sign_owner ON sign_records(owner_uuid);
CREATE INDEX idx_sign_world_xyz ON sign_records(world, origin_x, origin_z);
CREATE INDEX idx_sign_template ON sign_records(template_id);
CREATE INDEX idx_sign_created ON sign_records(created_at);
```

**软删除语义：** `/canvas remove` 不立即清理，只打 `deleted_at`；`/canvas cleanup` 真正回收 map 回池 + 物品框清除 + 记录删除。

### 2.5 表：`audit_log`

安全/操作审计日志。

```sql
CREATE TABLE audit_log (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  ts           INTEGER NOT NULL,
  event        TEXT NOT NULL,              -- 'AUTH_OK' | 'AUTH_FAIL' | 'COMMIT' | 'CANCEL' | 'CLEANUP' | 'POOL_EXPAND' | ...
  player_uuid  TEXT,                       -- 可空（如未认证阶段）
  player_name  TEXT,
  session_id   TEXT,
  ip_hash      TEXT,                       -- IP 的 SHA-256（避免存明文）
  details      TEXT                        -- JSON，事件附加信息
);

CREATE INDEX idx_audit_ts ON audit_log(ts);
CREATE INDEX idx_audit_player ON audit_log(player_uuid);
CREATE INDEX idx_audit_event ON audit_log(event);
```

**保留策略：** 默认保留 90 天，后台任务定期 `DELETE WHERE ts < now - 90d`。可配置。

### 2.6 表：`template_usage`

模板使用统计，供编辑器排序「最近用过」「热门」。

```sql
CREATE TABLE template_usage (
  template_id   TEXT NOT NULL,
  player_uuid   TEXT NOT NULL,
  use_count     INTEGER NOT NULL DEFAULT 0,
  last_used_at  INTEGER NOT NULL,
  PRIMARY KEY (template_id, player_uuid)
);

CREATE INDEX idx_usage_player ON template_usage(player_uuid, last_used_at DESC);
CREATE INDEX idx_usage_global ON template_usage(last_used_at DESC);
```

---

## 3. PersistentDataContainer 约定

### 3.1 命名空间

所有 PDC key 使用插件命名空间：`NamespacedKey(plugin, "<key>")`。
命名空间字符串固定：`"hikari_canvas"`。

### 3.2 Key 表

#### 对 MapView 的 PDC

| Key | 类型 | 说明 |
| --- | --- | --- |
| `pool_state` | STRING | `FREE` / `RESERVED` / `PERMANENT` |
| `owner` | STRING | UUID（PERMANENT 时） |
| `sign_id` | STRING | SignRecord UUID |
| `session_id` | STRING | 当前会话（RESERVED 时） |
| `created_at` | LONG | 创建时间戳 |

> 注：MapView 的 PDC 数据为 SQLite 的副本，保证世界备份时地图仍可还原。SQLite 与 PDC 不一致时以 SQLite 为权威。

#### 对 ItemFrame 的 PDC

| Key | 类型 | 说明 |
| --- | --- | --- |
| `sign_id` | STRING | 所属招牌（PERMANENT） |
| `session_id` | STRING | 所属会话（编辑期间） |
| `role` | STRING | `preview`（编辑中）/ `permanent`（成品） |
| `slot_x` | INT | 在招牌矩阵内的列索引 |
| `slot_y` | INT | 在招牌矩阵内的行索引 |

#### 对 Map Item 的 PDC

与 MapView 同步（Spigot/Paper map item 可读写 PDC）。

### 3.3 检索不属于 HikariCanvas 的 map

判断一张地图是否受本插件管理：**PDC 中存在 `pool_state` key**。否则视为外部地图，不触碰。

---

## 4. `.canvas` 工程文件格式

玩家在编辑器中可「导出工程」供离线保存 / 分享。

### 4.1 文件结构

`.canvas` 是 **zip 压缩包**，扩展名 `.canvas`。内部：

```
mysign.canvas
├── manifest.json            # 必选
├── project.json             # 必选：完整 ProjectState
├── thumbnail.png            # 可选：预览缩略图 256×128
└── assets/
    └── (玩家自定义图片资源，若使用 icon 元素)
```

### 4.2 `manifest.json`

```json
{
  "spec": 1,
  "kind": "project",
  "created_at": 1713528000000,
  "created_by": "Steve",
  "server": "play.example.com",
  "plugin_version": "1.0.0",
  "name": "新都市人民政府",
  "wall": { "width": 6, "height": 2 },
  "template_origin": "plaque_vertical"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `spec` | ✅ | 格式版本 |
| `kind` | ✅ | `"project"`（与未来的 `"pack"` 区分） |
| `created_at` | ✅ | 导出时间 |
| `name` | | 工程名 |
| `template_origin` | | 若来自模板，记录原 id |

### 4.3 `project.json`

直接包含 `protocol.md §7` 定义的 `ProjectState` 对象。

### 4.4 导入语义

玩家在编辑器选「导入 `.canvas`」：
1. 解析 manifest，校验 `spec` 与当前插件兼容
2. 加载 `project.json` 替换当前工程状态
3. 若 `project.canvas` 超出当前会话墙面尺寸 → 提示并中止，让玩家开新会话
4. 若包含 `assets/`（v1.x 图标功能），文件由插件临时保存，会话结束清理

### 4.5 导出语义

编辑器「导出」动作：
1. 序列化当前 `ProjectState` → `project.json`
2. 渲染当前 RGBA 画布缩略图到 `thumbnail.png`（可空）
3. manifest 填充
4. 打 zip → 浏览器下载

导出**不**经过服务器存储，完全在浏览器端用 JSZip 打包。

---

## 5. 配置文件

### 5.1 `plugins/HikariCanvas/config.yml`

见 `architecture.md §11` 的骨架。此处补充**字段约束：**

| 字段 | 类型 | 范围 | 默认 |
| --- | --- | --- | --- |
| `web.bind` | string | IP | `127.0.0.1` |
| `web.port` | int | 1~65535 | `8877` |
| `web.context-path` | string | 以 `/` 开头或空 | `""` |
| `pool.initial-size` | int | 1~1024 | `64` |
| `pool.max-size` | int | ≥ initial-size，≤ 8192 | `256` |
| `session.token-ttl` | duration | `1m` ~ `24h` | `15m` |
| `session.idle-disconnect` | duration | `30s` ~ `1h` | `5m` |
| `limits.ws-messages-per-second` | int | 1~1000 | `20` |
| `limits.text-max-length` | int | 1~4096 | `256` |
| `limits.canvas-max-maps` | int | 1~64 | `16` |

所有 duration 支持 `s` / `m` / `h` 后缀。

---

## 6. 迁移策略

### 6.1 版本号定义

- **插件版本**：SemVer（`MAJOR.MINOR.PATCH`）
- **DB schema 版本**：单调整数，每次变更 +1
- **模板 spec 版本**：独立整数，见 `template-spec.md`
- **协议版本**：独立整数，见 `protocol.md`
- **.canvas spec 版本**：独立整数

### 6.2 DB 迁移流程

```
src/main/resources/db-migrations/
├── V001__initial.sql
├── V002__add_template_usage.sql
├── V003__add_soft_delete.sql
└── ...
```

启动时：
1. 读 `schema_version` 表最大 version `N`
2. 查找文件系统中 `V(N+1)__*.sql` ... 按序应用
3. 每应用一个脚本 → 在 `schema_version` 表插入新 row
4. 全流程在一个事务里；失败则回滚并拒绝启动

### 6.3 破坏性变更处理

- **pool_maps schema 变更**：必须保持既有 map 数据可用
- **sign_records.project_json 结构变更**：对应 `protocol.md §7` 升版；迁移脚本或启动时懒转换
- **模板 spec 升版**：旧模板文件保持可加载，读取时 `adapter.transform(oldYaml) → currentSpec`

### 6.4 备份与恢复

插件不主动备份 DB。但文档提示：
- `data.db` 应随世界文件一并快照
- 恢复时确保 DB 与世界文件的时间一致，否则 map 与 PDC 可能不匹配

---

## 7. 一致性与修复

### 7.1 不一致场景

| 场景 | 处理 |
| --- | --- |
| SQLite 中 PERMANENT 但 PDC 无标签 | 重新打 PDC 标签 |
| PDC 有标签但 SQLite 无记录 | 迁入 SQLite 或降为 FREE（配置决定） |
| `sign_records.map_ids` 引用的 map 不在 `pool_maps` | 记录损坏，移入 quarantine 表并告警 |
| 物品框消失但 SignRecord 存在 | 保留记录，标记 `detached=1`（SignRecord v2 schema） |
| 物品框存在但 SignRecord 被删 | 下次交互时提示并可一键清除 |

### 7.2 `/canvas fsck`（v1.x）

管理员命令，扫描全局一致性并输出报告。v1.0 不含，v1.1 补。

---

## 8. 查询示例

```sql
-- 玩家招牌清单
SELECT id, world, origin_x, origin_y, origin_z, created_at
FROM sign_records
WHERE owner_uuid = ? AND deleted_at IS NULL
ORDER BY created_at DESC;

-- 某区域的所有招牌
SELECT * FROM sign_records
WHERE world = ? AND origin_x BETWEEN ? AND ? AND origin_z BETWEEN ? AND ?
  AND deleted_at IS NULL;

-- 池健康快照
SELECT state, COUNT(*) FROM pool_maps GROUP BY state;

-- 玩家最近模板
SELECT template_id, use_count, last_used_at
FROM template_usage
WHERE player_uuid = ?
ORDER BY last_used_at DESC
LIMIT 10;
```

---

## 9. 敏感数据清单

| 数据 | 敏感度 | 处理 |
| --- | --- | --- |
| 玩家 UUID | 中 | 存储，不外发 |
| 玩家名 | 低 | 冗余存储 |
| 玩家 IP | 高 | **仅 SHA-256 存 `audit_log.ip_hash`**，不存明文 |
| Token | 高 | 生成后仅存 SHA-256 → SQLite，原文件只在内存 + 返回玩家一次 |
| 项目内容（招牌文字） | 低 | 明文存储，属玩家作品 |

---

## 10. 未决问题

- [ ] `pool_maps` 删除（池缩容）是否支持在线执行
- [ ] 软删除的 sign_records 保留多久（目前无过期策略，未来可加）
- [ ] 世界卸载/加载时 DB 的行为（某世界下线，其中的 sign_records 如何处理）
- [ ] `audit_log` 是否分库以免主 DB 膨胀
- [ ] 多服务器共享 DB 的场景（暂不支持，但考虑未来是否兼容）
