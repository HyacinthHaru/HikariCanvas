# 数据模型

**状态：** 立项稿 v0.1 · 2026-04-19；M5.5 重构 · 2026-04-27；lock-state 重设计 · 2026-05-14
**适用范围：** SQLite schema、PersistentDataContainer 约定、`.canvas` 工程文件格式、迁移策略

本文档定义所有持久化数据的结构。**一旦 v1.0 发布，schema 变更必须通过迁移脚本完成**；不允许在线上直接改表。

> **M5.5 重构（2026-04-27）**：合并 `drafts` + `sign_records` → 单一 `walls` 表；`pool_maps.state` 由三态收为两态（FREE/RESERVED）；废止 commit 流程，新增 `published_at` 标签。

> **lock-state 重设计（2026-05-14）**：DB 列 `walls.published_at` 名字保留（避免 SQL 迁移风险），但语义改为 **lock 时间戳**：`null` = 可编辑，非 `null` = 已锁定（前端 readonly UI）。`walls.owner_uuid` 为作者权限依据。ItemFrame PDC `published_at` 不再写（FrameDeployer.markPublished 砍）；现有 PDC 数据保留无害。下文 §2.X 涉及 published 语义的描述均按"lock 时间戳"理解。详见 CLAUDE.md `§lock-state` + `docs/architecture.md §3.6`。

---

## 1. 存储分层

| 存储位置 | 内容 | 生命周期 |
| --- | --- | --- |
| SQLite `data.db` | 池元信息、walls 表、审计日志、模板统计、**image_uploads 配额表（M13）** | 跨服务器重启；随世界快照备份 |
| `ItemFrame` PDC | `wall_id` / `slot` 标签（`published_at` 不再写入，2026-05-14 砍） | 随世界文件 |
| 文件：`templates/*.yml` | 模板定义 | 人工管理 |
| 文件：`user-templates/<uuid>/` | 玩家上传模板（v1.x） | 按玩家 uuid 组织 |
| 文件：`fonts/*.ttf` / `*.woff2` | 字体 | 人工管理 |
| 文件：`.canvas` 工程导出 | 玩家导出的工程 | 外部管理 |
| **文件：`uploads/<sha256[:16]>.png`（M13）** | 玩家上传的图片（hash 内容寻址，跨 wall 引用同一文件不重复存） | 按 last_used_at LRU 清理；删 wall 不立即清 |

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
  state         TEXT NOT NULL,             -- 'FREE' | 'RESERVED'  (M5.5 起两态)
  reserved_by   TEXT,                      -- 'wall:<wall_id>' (state=RESERVED 时)
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER NOT NULL,
  world         TEXT                       -- 创建时所在世界，便于清理
);

CREATE INDEX idx_pool_state ON pool_maps(state);
CREATE INDEX idx_pool_owner ON pool_maps(reserved_by);
```

**不变式：**
- `state=FREE`：`reserved_by` 为 NULL
- `state=RESERVED`：`reserved_by` 非 NULL，格式 `wall:<wall_id>`

插件启动时执行一次性扫描验证不变式，异常记录移回 FREE + 告警。

> **M5.5 删字段**：`sign_id` 列删除（合并入 `reserved_by`）。原 `PERMANENT` 状态废止——wall 占的 map 一直 RESERVED 直到 `/canvas delete`。详见 architecture.md §4。

### 2.4 表：`walls`（M5.5 替代 `sign_records` + `drafts`）

每行 = 一面墙上的一幅画。`(world, origin, facing)` 唯一索引保证一墙一画。`published_at` 是纯 UI 标签，不影响底层行为（wall 始终可改）。

```sql
CREATE TABLE walls (
  wall_id       TEXT PRIMARY KEY,          -- 'w-<8hex>'，玩家可见短 ID
  world         TEXT NOT NULL,
  origin_x      INTEGER NOT NULL,
  origin_y      INTEGER NOT NULL,
  origin_z      INTEGER NOT NULL,
  facing        TEXT NOT NULL,             -- 'NORTH'|'SOUTH'|'EAST'|'WEST'|'UP'|'DOWN'
  width_maps    INTEGER NOT NULL,
  height_maps   INTEGER NOT NULL,
  map_ids       TEXT NOT NULL,             -- CSV，长度 = width_maps*height_maps
  project_json  TEXT NOT NULL,             -- 完整 ProjectState，op 后 UPDATE
  owner_uuid    TEXT NOT NULL,             -- 创建者
  owner_name    TEXT NOT NULL,             -- 冗余，避免玩家改名后查不到
  alias         TEXT,                      -- 玩家命名，nullable，唯一
  published_at  INTEGER,                   -- nullable timestamp；NULL=可编辑，非 NULL=已锁定（lock 时间戳，2026-05-14 起语义化）
  template_id   TEXT,                      -- M6 模板系统填，源模板 ID
  template_version INTEGER,                -- M6 当时模板版本
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE UNIQUE INDEX idx_walls_location ON walls(world, origin_x, origin_y, origin_z, facing);
CREATE UNIQUE INDEX idx_walls_alias    ON walls(alias) WHERE alias IS NOT NULL;
CREATE INDEX idx_walls_owner       ON walls(owner_uuid);
CREATE INDEX idx_walls_published   ON walls(published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idx_walls_updated     ON walls(updated_at DESC);
```

**唯一性：**
- `wall_id`：主键
- `(world, origin, facing)`：一墙一画
- `alias`（非 NULL 时）：玩家命名不允许重复

**published_at 语义（关键，2026-05-14 lock-state 重设计）：**
- NULL → wall 处于"可编辑"状态，任意 canvas.edit 玩家可 `/canvas open` 进入编辑器
- 非 NULL → wall 处于"已锁定（只读）"状态，时间戳 = 锁定那一刻的 ms epoch；前端 readonly UI 拦截编辑控件 + 快捷键
- 列名保留 `published_at` 是为避免 SQL 迁移；代码层应用方读它时按 "lockedAt" 语义理解
- 锁/解锁由前端 TopBar Lock 按钮触发 `wall.lock` / `wall.unlock` WS op（owner-only：caller UUID == walls.owner_uuid）
- **后端编辑 op 不读 lock 状态**——element.* / canvas.* / layer.* 仍可在锁定的 wall 上执行（动态展示用例必需）；lock 是纯 UX 层概念

**删除语义：** `/canvas delete <wall_id>` 第一次只显示"30s 内输入 `/canvas delete <wall_id> confirm` 才真删"；二次确认后**直接 DELETE 行**（不软删），同时拆 ItemFrame + 释放 map 回 FREE。无软删 / `deleted_at` 字段（用户操作明确）。

**与 sessions 的关系：** sessions 是临时编辑器持有者；walls 是永久墙上的画。一个 wall 可以有 0 或 1 个活跃 session 编辑它（`byWall` 排他锁）。session cancel/disconnect 不动 walls；wall delete 强制 cancel 关联 session。

### 2.4.1 `project_json` v1 → v2 lazy migration（M8）

**触发：** WallRepo 读 `project_json` 时，Jackson 反序列化前先快速 peek 顶层结构，发现含 `elements:` 但不含 `layers:` → 视为 v1 形态，走 migrate。

**Migrate 规则：**

```
v1 形态:
{
  "version": N,
  "canvas": { widthMaps, heightMaps, background },
  "elements": [ <Element>, ... ],
  "history": { undoDepth, redoDepth }
}

v2 形态:
{
  "version": N,
  "protocolVersion": 2,
  "canvas": {
    "widthMaps", "heightMaps", "background",
    "gridSize": 0,           // 默认无网格
    "guides": []             // 默认无参考线
  },
  "layers": [{
    "id": "l-<UUID 8 位>",
    "name": "Default Layer",
    "visible": true,
    "locked": false,
    "opacity": 1.0,
    "blendMode": "normal",
    "elements": [ <每个 element 补 opacity:1.0/blendMode:"normal"/renderMode:"clean">, ... ]
  }],
  "activeLayerId": "l-<同上>",
  "history": { undoDepth, redoDepth }
}
```

**执行时机选项：**

- **A 启动期全库扫描**：M8 onEnable 时一次性把所有 wall.project_json 升级 + 回写。优：透明、无 read-time 开销；缺：启动慢（按 walls 数量）
- **B Lazy（每次 load 时升）**：WallRepo.loadById 内置检测 + 即时升 + 写回。优：启动快；缺：分布式写、首次 load 多一次 UPDATE
- **C 双路径**：服务端代码同时识别 v1/v2 形态（构造 ProjectState 时归一化），不写回 DB。优：零迁移；缺：代码长期维护 v1 兼容

**决策（2026-05-13）：** **选 A**。理由：
- 简单、确定
- HikariCanvas 单服 walls 数量上限通常 < 200（典型创意服 ~50）；扫描 200 条 JSON 反序列化耗时 < 200ms，可接受
- A 之后所有运行期代码只需要处理 v2 形态，长期维护成本最低
- 失败容忍：若某条 project_json 解析失败 → log warn 跳过该 wall（不让坏数据卡启动）；启动后该 wall `/canvas open` 时仍会 lazy 再尝试

**实施位置：** `MigrationRunner` V006 + `WallRepo.migrateProjectJsonV1ToV2` 静态方法。M8-B 子阶段实施。

### 2.5 表：`audit_log`

安全/操作审计日志。

```sql
CREATE TABLE audit_log (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  ts           INTEGER NOT NULL,
  event        TEXT NOT NULL,              -- 'AUTH_OK' | 'AUTH_FAILED' | 'SESSION_BEGIN/CONFIRM/CANCEL' | 'WALL_PUBLISH/UNPUBLISH/DELETE' | 'POOL_RESERVE/RETURN/EXPAND/LEAK/ATTACH' | 'CLEANUP' | ...
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

### 2.6.5 表：`image_uploads`（M13 引入）

按 sha256[:16] hash 内容寻址。一个 hash 对应磁盘上一个 PNG 文件 + 一条 image_uploads 行；多个 ImageElement.source 可指同一 hash（跨 wall 引用零重复存储）。

```sql
CREATE TABLE image_uploads (
    hash            TEXT    PRIMARY KEY,    -- sha256[:16] hex（16 字符）
    bytes           INTEGER NOT NULL,        -- 磁盘字节数（downscale 后）
    width           INTEGER NOT NULL,
    height          INTEGER NOT NULL,
    mime            TEXT    NOT NULL,        -- 'image/png'（统一存储格式，jpeg/webp 上传时转）
    uploader_uuid   TEXT    NOT NULL,
    uploaded_at     INTEGER NOT NULL,
    last_used_at    INTEGER NOT NULL,        -- ImageElement 引用时更新；LRU 清理依据
    refcount        INTEGER NOT NULL         -- 当前被多少 ImageElement.source 引用；0 = 可 LRU 删
);

CREATE INDEX idx_uploads_uploader ON image_uploads(uploader_uuid, uploaded_at DESC);
CREATE INDEX idx_uploads_lru ON image_uploads(refcount, last_used_at) WHERE refcount = 0;
```

**关键字段语义：**

- `refcount`：服务端在 `element.add type=image` / `element.update fill` / `element.delete` 时增减
- `last_used_at`：每次 wall 重新打开 / element 被引用渲染时刷新
- LRU 清理：`SELECT hash FROM image_uploads WHERE refcount=0 ORDER BY last_used_at ASC LIMIT N` → unlink 磁盘文件 + DELETE 表行

**配额查询（M13）：**

```sql
-- 玩家 24h 上传次数
SELECT COUNT(*) FROM image_uploads
WHERE uploader_uuid = ? AND uploaded_at > strftime('%s', 'now') * 1000 - 86400000;

-- 单 wall 关联图片数（通过 ImageElement.source）—— project_json JSON_EACH 查
-- v1 简化：在 EditSession 内存层算（layers 内所有 image element.source 去重）

-- 总磁盘字节
SELECT SUM(bytes) FROM image_uploads;
```

### 2.7 表：`template_usage`

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

### 3.2 Key 表（M5.5 简化）

#### 对 MapView 的 PDC

**不写。** M2 立项时设计要把 SQLite 状态镜像到 MapView PDC 作为冗余，但代码从未实装。M5.5 起承认现状：SQLite 是单一来源；MapView PDC 不写业务字段。

#### 对 ItemFrame 的 PDC

| Key | 类型 | 说明 |
| --- | --- | --- |
| `wall_id` | STRING | 所属 wall（核心 key，M5.5 起替代旧的 `session_id` / `sign_id`） |
| `slot` | INT | 在 wall 矩阵内的位置序号（row * width + col） |
| `published_at` | LONG | **2026-05-14 不再写入**；FrameDeployer.markPublished + FrameProtectionListener "已发布拦截" 都砍；现存 PDC 数据保留无害但不被读 |

#### 对 Map Item 的 PDC

不写。Map Item 在 ItemFrame 里挂着；要识别属于哪个 wall，从 ItemFrame PDC 取 `wall_id` 即可。

### 3.3 检索不属于 HikariCanvas 的 ItemFrame

判断一个 ItemFrame 是否受本插件管理：**PDC 中存在 `wall_id` key**。否则视为外部画框，不触碰。

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

直接包含 `protocol.md §7` 定义的 `ProjectState` 对象。v2 起为 layered 形态（含 `layers[]` / `activeLayerId` / `canvas.gridSize` / `canvas.guides` / 元素级 `opacity` / `blendMode` / `renderMode`）。导入老的 v1 `project.json`（含 `elements[]`）时自动 migrate（见 §2.4.1）。

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
- **walls.project_json 结构变更**：对应 `protocol.md §7` 升版；迁移脚本或启动时懒转换
- **模板 spec 升版**：旧模板文件保持可加载，读取时 `adapter.transform(oldYaml) → currentSpec`

### 6.5 M5.5 V005 整体重置（2026-04-27）

M5.5 重构涉及 schema 大改：合并 `drafts` + `sign_records` → `walls`、`pool_maps` 删 `sign_id` 列。决策按 V005 一次性 drop + recreate 而不是 alter：

```sql
-- V005__walls_unified.sql （示意，非最终）
DROP TABLE IF EXISTS sign_records;
DROP TABLE IF EXISTS drafts;
DROP INDEX IF EXISTS idx_pool_sign;
ALTER TABLE pool_maps DROP COLUMN sign_id;

CREATE TABLE walls (...);  -- 完整 §2.4 schema
CREATE INDEX/UNIQUE INDEX ...;
```

理由：M5.5 阶段无生产数据，drafts/sign_records 累计 < 50 行；走 alter + 数据迁移成本远高于 drop + 重建。生产发布前最后一次允许 drop。后续任何破坏性变更必须走严格 alter 迁移。

### 6.4 备份与恢复

插件不主动备份 DB。但文档提示：
- `data.db` 应随世界文件一并快照
- 恢复时确保 DB 与世界文件的时间一致，否则 map 与 PDC 可能不匹配

---

## 7. 一致性与修复

### 7.1 不一致场景

| 场景 | 处理 |
| --- | --- |
| `pool_maps` RESERVED `wall:<id>` 但 walls 表无对应行 | 视为泄漏；`detectLeaks` 强制 → FREE + 告警 |
| `walls.map_ids` 引用的 map 不在 `pool_maps` 或非该 wall 持有 | 启动时 WallRestorer 检测；行 → quarantine + 告警，不阻塞启动 |
| ItemFrame PDC `wall_id` 但 walls 表无对应行 | 启动时报告，不主动拆框（玩家可能误删后想恢复）；管理员 `/canvas cleanup` 决定 |
| walls 行存在但所有 ItemFrame 消失 | 行保留（玩家可能后续走到原位置 `/canvas open` 恢复）；`/canvas list` 标记 detached |
| ItemFrame 存在但 walls 表行被删（不应发生，因为 delete 会拆框） | 下次 wand 交互时识别为"陌生 ItemFrame"，提示用户手动拆 |

### 7.2 `/canvas fsck`（M7+）

管理员命令，扫描全局一致性并输出报告。M5.5 不做；当前 `/canvas cleanup` 命令保留 stub 占位。

---

## 8. 查询示例

```sql
-- 玩家的画清单（按更新时间倒序）
SELECT wall_id, alias, world, origin_x, origin_y, origin_z, facing,
       width_maps, height_maps, published_at, updated_at
FROM walls
WHERE owner_uuid = ?
ORDER BY updated_at DESC;

-- 全局已发布画（首页"最近发布"）
SELECT wall_id, alias, owner_name, published_at
FROM walls
WHERE published_at IS NOT NULL
ORDER BY published_at DESC
LIMIT 50;

-- 反查 mapId → wall_id（wand 瞄 ItemFrame 时用）
SELECT wall_id FROM walls WHERE map_ids LIKE ? || ',%' OR map_ids LIKE '%,' || ? || ',%' OR map_ids LIKE '%,' || ? OR map_ids = ?;
-- 工程实现建议：单独维护 walls_map_index(map_id PK, wall_id) 反向表（M5.5 P1 加）

-- 某区域的所有 walls
SELECT * FROM walls
WHERE world = ? AND origin_x BETWEEN ? AND ? AND origin_z BETWEEN ? AND ?;

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
- [ ] 世界卸载/加载时 DB 的行为（某世界下线，其中的 walls 行如何处理）
- [ ] **M5.5 引入**：`walls_map_index(map_id PK, wall_id)` 反向表是 P1 加，还是直接走 LIKE 查询（数据量小可不加）
- [ ] **M5.5 引入**：alias 大小写敏感性（"Subway" vs "subway" 是否同名冲突）
- [ ] **M5.5 引入**：wall delete 时是否需要保留 audit log 一份 project_json 备份（防误删）
- [ ] `audit_log` 是否分库以免主 DB 膨胀
- [ ] 多服务器共享 DB 的场景（暂不支持，但考虑未来是否兼容）
