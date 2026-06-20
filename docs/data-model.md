# 数据模型

**状态：** 立项稿 v0.1 · 2026-04-19；M5.5 重构 · 2026-04-27；lock-state 重设计 · 2026-05-14；代码对齐回填 · 2026-06-14（迁移至 V017）；**`.canvas` 导入导出回填 · 2026-06-19（0.8 Part A 实装）**
**适用范围：** SQLite schema、PersistentDataContainer 约定、`.canvas` 工程文件格式（0.8 实装）、迁移策略

本文档定义所有持久化数据的结构。**一旦 v1.0 发布，schema 变更必须通过迁移脚本完成**；不允许在线上直接改表。

> **代码对齐说明（2026-06-14）**：本文档与当前代码（`plugin/src/main/resources/db-migrations/` 最高 V017、`plugin/src/main/java/moe/hikari/canvas/storage/`、`deploy/FrameDeployer.java`）逐条核对回填。当前 DB schema 已演进到 **V017**（V009 跳号未落地脚本）。

> **`.canvas` 导入导出回填（2026-06-19）**：`.canvas` 工程文件格式（§4）已在 **0.8 Part A 落地**（契约总纲见 `docs/import-export.md`）。导出为纯前端（`web/src/composables/useProjectExport.ts` + `web/src/lib/canvasFile.ts`，用 **fflate** 打 zip）；导入为后端 `POST /api/project/import`（包 `moe.hikari.canvas.canvasfile` + `web/ProjectImportHandler`）。`.canvas spec = 1`。本节据已实装代码逐条核对回填。**仍未实装的是 Part B（SVG → 原生元素导入）**——`assets/icons/*.svg` 的写入 / 摄入属 Part B，详见 §4.1 注。

> **M5.5 重构（2026-04-27）**：合并 `drafts` + `sign_records` → 单一 `walls` 表；`pool_maps.state` 由三态收为两态（FREE/RESERVED）；废止 commit 流程，新增 `published_at` 标签。

> **lock-state 重设计（2026-05-14）**：DB 列 `walls.published_at` 名字保留（避免 SQL 迁移风险），但语义改为 **lock 时间戳**：`null` = 可编辑，非 `null` = 已锁定（前端 readonly UI）。`walls.owner_uuid` 为作者权限依据。ItemFrame PDC `published_at` 不再写（FrameDeployer.markPublished 砍）；现有 PDC 数据保留无害。下文 §2.X 涉及 published 语义的描述均按"lock 时间戳"理解。详见 CLAUDE.md `§lock-state` + `docs/architecture.md §3.6`。

---

## 1. 存储分层

| 存储位置 | 内容 | 生命周期 |
| --- | --- | --- |
| SQLite `data.db` | 池元信息、walls 表、审计日志、模板统计、image_uploads 配额表（M13）、用户变量 / 别名 / 全局变量、列车时刻表、铁路网络、墙脚本 | 跨服务器重启；随世界快照备份 |
| `ItemFrame` PDC | `wall_id` / `slot` 标签（`published_at` 不再写入，2026-05-14 砍） | 随世界文件 |
| 文件：`templates/*.yml` | 模板定义 | 人工管理 |
| 文件：`user-templates/<uuid>/` | 玩家上传模板（v1.x） | 按玩家 uuid 组织 |
| 文件：`fonts/*.ttf` / `*.woff2` | 字体 | 人工管理 |
| 文件：`.canvas` 工程导出 | 玩家导出的工程（0.8 实装；纯前端 fflate 打包下载，不经服务端存储） | 外部管理 |
| **文件：`uploads/<sha256[:16]>.png`（M13）** | 玩家上传的图片（hash 内容寻址，跨 wall 引用同一文件不重复存） | 按 last_used_at LRU 清理；删 wall 不立即清 |

---

## 2. SQLite Schema

### 2.1 基础

- 文件路径：`plugins/HikariCanvas/data.db`
- 连接池：HikariCP，**最大 4 连接** + `setLeakDetectionThreshold(30_000)`（M16-P5.2）
- 访问层：JDBI 3（轻量、类型安全，比 JOOQ 启动快）

> **maxPoolSize=4 不缩到 1 的理由（M16 确认）**：SQLite 单写但允许并发读（WAL 模式）。4 池让 read-heavy 路径（preview 渲染查询 / quota check / template registry load）不阻塞主线程的 write 路径。写一致性靠 SQLite `busy_timeout=5000ms` + `jdbi.inTransaction(SERIALIZABLE)` + `BEGIN IMMEDIATE` 写锁串行化（M16-P2.1 上传配额路径已切）。缩到 1 会让任何一个长查询（如 `WallRepo.listByOwner` 走全表）阻塞所有后续连接获取，触发 Bukkit 主线程卡顿。`leakDetectionThreshold=30s` 在连接借出 30s 未还时打印 stack trace 兜底排查未关连接的代码路径。
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
  protocol_version INTEGER NOT NULL DEFAULT 1, -- V006（M8-B）加：标记 project_json 形态（1=v1 / 2=v2 layered）
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE UNIQUE INDEX idx_walls_location ON walls(world, origin_x, origin_y, origin_z, facing);
CREATE UNIQUE INDEX idx_walls_alias    ON walls(alias) WHERE alias IS NOT NULL;
CREATE INDEX idx_walls_owner       ON walls(owner_uuid);
CREATE INDEX idx_walls_published   ON walls(published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idx_walls_updated     ON walls(updated_at DESC);
CREATE INDEX idx_walls_protocol_version ON walls(protocol_version);  -- V006
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

### 2.4.2 `project_json` v2 → v3 加法（0.6）

0.6 时间轴在 `ProjectState` 顶层加两个 **nullable** 字段（依据 `timeline.md §2.6/§2.7`）：

| 字段 | 类型 | 缺省 | 语义 |
| --- | --- | --- | --- |
| `timelines` | `List<Timeline>` | `null` | 工程下所有时间轴；每条含 `tracks: Map<elementId, List<Keyframe>>`（方案 B：关键帧轨压平进 timeline，不进 Element） |
| `activeTimelineId` | `String` | `null` | 当前激活时间轴 id；`null` = 静态画板（本节加法不生效） |

`Timeline` / `Keyframe` / `Easing` / `TriggerConfig` record 形态以 `timeline.md §2.1–§2.5` 为权威。

**与 v1→v2（§2.4.1）的关键区别：v2→v3 是纯加法，不重构结构、不需主动 rewrite 迁移。** v1→v2 把 `elements[]` 包进 `layers[]`、改了树形结构，故走启动期全库扫描回写（方案 A）；v2→v3 只是顶层多两个字段，沿用 **M8 v2 nullable 加法范式**：旧 v2 blob 无这两字段 → Jackson 反序列化填 `null` → `timelines == null` 走完全静态行为、baseline 零漂移（`ProjectState` 的 `@JsonCreator` 入口加两个 `@JsonProperty` 参数，缺失退 `null`）。

**`Element` 8 个 record 零改动**——这是方案 B 的核心好处：关键帧不进 Element，故 sealed permits 与全部元素字段不动，`.canvas` / `project_json` 里既有 element 的序列化形态完全不变。

**`protocolVersion` 内部字段 2 → 3**，但**不主动回写**旧 blob：

- 不做启动期扫描升级（无结构变更，无须 rewrite）。
- 运行期代码**同时接受 2 与 3**：读到 v2（无 `timelines`）按静态处理，读到 v3 按带时间轴处理。
- **lazy on-write**：某 wall 下次保存时 `project_json` 自然写成 `protocolVersion: 3`，无显式迁移步骤。

**无新 SQLite 表 / 无新 schema 版本。** `timelines` 以及每元素关键帧轨（`Timeline.tracks: Map<elementId, List<Keyframe>>`）全部序列化进 `walls.project_json` blob，与 `layers` / `elements` 同级。**0.6 不加 DB schema 版本、不加表、不做 `ALTER`**——本次变更全在 `project_json` blob 层（与 §6 的 schema 版本约定对照见 §6.2 末）。

### 2.5 表：`audit_log`

安全/操作审计日志。

```sql
CREATE TABLE audit_log (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  ts           INTEGER NOT NULL,
  event        TEXT NOT NULL,              -- 见下方「实际记录的事件清单」
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

**实际记录的事件清单（2026-06-14 按代码 `auditLog.record(...)` 调用点核对，约 30+ 个）：**

| 分组 | 事件字面量 | 出处 |
| --- | --- | --- |
| 认证 | `AUTH_OK` | `SessionManager`（认证成功；`AUTH_FAILED` 是 WS/HTTP 错误响应码，不入 audit 表） |
| 会话 | `SESSION_OPEN` `SESSION_BEGIN` `SESSION_CONFIRM` `SESSION_CANCEL` | `SessionManager` |
| 墙 | `WALL_ALIAS` `WALL_LOCK` `WALL_UNLOCK` `WALL_DELETE` | 命令族 / lock dispatcher |
| 地图池 | `POOL_INITIALIZED` `POOL_RESERVE` `POOL_BIND_WALL` `POOL_RELEASE_WALL` `POOL_RELEASE_TO_FREE` `POOL_EXPAND` `POOL_LEAK` `POOL_ORPHAN_ROW` | `MapPool` / 启动扫描 |
| 图片上传 | `IMAGE_UPLOAD_OK` `IMAGE_UPLOAD_REJECTED` | `UploadHandler` |
| 安全 | `PERMISSION_DENIED` `PLUGIN_NAMESPACE_DENIED` `TOKEN_RATE_LIMIT_EXCEEDED` | 鉴权 / Plugin API / token rate limit |
| 变量（命令） | `VARIABLE_COMMAND_SET` `VARIABLE_COMMAND_DELETE` | `/canvas var` 命令 |
| 变量（WS op） | `VARIABLE_CREATE` `VARIABLE_UPDATE` `VARIABLE_SET` `VARIABLE_DELETE` `VARIABLE_BIND`（global 走 `VARIABLE_GLOBAL_*` 前缀） | `VariableOpDispatcher`（按 op 拼前缀 + 动作名） |
| 变量别名 | `VARIABLE_ALIAS_SET` `VARIABLE_ALIAS_CLEAR` | `VariableAliasDispatcher`（0.4.2） |
| 时刻表 | `SCHEDULE_UPSERT` `SCHEDULE_ENTRY_ADD` `SCHEDULE_ENTRY_UPDATE` `SCHEDULE_ENTRY_DELETE` | `ScheduleOpDispatcher`（0.4.0） |
| 铁路网络 | `RAIL_LINE_CREATE/UPDATE/DELETE` `RAIL_STATION_ADD/UPDATE/DELETE` `RAIL_RUN_CREATE/UPDATE/DELETE` `RAIL_TIMETABLE_SET` `RAIL_WALL_BIND` | `RailOpDispatcher`（0.4.4） |
| 脚本 | `SCRIPT_CREATE/UPDATE/DELETE/ENABLE/TEST` `SCRIPT_COMMAND_EXECUTED` `SCRIPT_RUN_BLOCKED` | `ScriptOpDispatcher` / 脚本执行引擎（0.7.0） |

> 注：`SESSION_CLOSED` / `WALL_NOT_FOUND` / `SCRIPT_INVALID` / `SCRIPT_NOT_FOUND` / `SCRIPT_QUOTA_EXCEEDED` / `SCRIPT_ENGINE_UNAVAILABLE` 等是 WS `Envelope.error(...)` 的**错误码**（返回给前端），并非全部都写入 audit 表——以代码 `auditLog.record(...)` 实际调用为准。

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
    last_used_at    INTEGER NOT NULL         -- ImageElement 引用时更新；LRU 清理依据
);

CREATE INDEX idx_uploads_uploader ON image_uploads(uploader_uuid, uploaded_at DESC);
CREATE INDEX idx_uploads_lru ON image_uploads(last_used_at);
```

> **无 `refcount` 列**：M15.4 起引用计数改为运行期实时 sweep（见下方 LRU 清理）；原 `refcount INTEGER` 列已在 V010 DROP（详见 §6.5.1）。

**关键字段语义：**

- `last_used_at`：每次 wall 重新打开 / element 被引用渲染时刷新
- LRU 清理：不依赖 refcount 列，而是**实时 sweep** —— 遍历所有 `walls.project_json` 收集被引用的 image hash 集合（`ImageStorage.collectReferencedHashes`），再 `SELECT hash FROM image_uploads WHERE hash NOT IN (<被引用集合>) ORDER BY last_used_at ASC LIMIT N`（`ImageUploadDao.pickLruCandidates`）剔除孤儿 → unlink 磁盘文件 + DELETE 表行

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

### 2.8 表：`user_variables`（0.4.0 引入，V011）

玩家在 wall 内创建的用户变量持久化。Tier 1 数据源（详见 `docs/dynamic-data.md`）。

```sql
CREATE TABLE user_variables (
    wall_id        TEXT NOT NULL,             -- 所属 wall（per-wall scope）
    name           TEXT NOT NULL,             -- "红队比分"（不含 user/ 前缀）
    type           TEXT NOT NULL,             -- 'STRING' / 'NUMBER' / 'BOOLEAN' / 'COLOR'
    default_value  TEXT,                      -- 可空：fallback when push 失效
    current_value  TEXT,                      -- 当前值（手动设 / 插件 push 后被写回）
    bound_to       TEXT,                      -- 绑定到的插件 namespace；NULL = 手动管理
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    PRIMARY KEY (wall_id, name),
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

CREATE INDEX idx_uvar_wall ON user_variables(wall_id);
CREATE INDEX idx_uvar_bound ON user_variables(bound_to) WHERE bound_to IS NOT NULL;
```

- **持久化**：仅 `user/*` namespace 变量持久化；插件 / 系统 / PAPI 变量内存态重启不留
- **级联删除**：wall 删除时 cascade（FOREIGN KEY 已声明）
- **migration V011**：pre-release 0.x SNAPSHOT 阶段允许加表（§6.6.1 规则）

### 2.9 表：`wall_schedules` + `schedule_entries`（0.4.0 引入，V012；V013 加 precision）

per-wall 列车 / 公交时刻表元数据。`ManualScheduleProvider` 启动期 loadAll 注册所有 wall 的 schedule 变量。

```sql
-- V012：基础表
CREATE TABLE wall_schedules (
    wall_id        TEXT PRIMARY KEY,
    station_name   TEXT,                  -- 玩家命名站点（可空）
    updated_at     INTEGER NOT NULL,
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

CREATE TABLE schedule_entries (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    wall_id         TEXT NOT NULL,
    departure_time  TEXT NOT NULL,        -- "HH:mm" 或 "HH:mm:ss"（V013 后允许 HH:mm:ss）
    destination     TEXT,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

-- V013：0.4.0 bugfix（Bug 4）—— per-wall 精度
ALTER TABLE wall_schedules ADD COLUMN precision TEXT NOT NULL DEFAULT 'minute';
```

- **precision**：`'minute'`（默认；HH:mm + 30s 刷新）或 `'second'`（HH:mm:ss + 1s 刷新）
- **migration V013**：现有 wall 行 ALTER ADD COLUMN DEFAULT 'minute'，无数据丢失，向下兼容
- **级联删除**：wall 删除时 cascade（FK CASCADE 已声明；ScheduleDao.deleteByWall 显式调用兜底）

### 2.9.1 表：`variable_aliases`（0.4.2 引入，V014）

per-wall 变量别名。别名仅 UI 展示用（picker / panel / chip），**不参与 `${var:...}` 解析**。所有 namespace 通用（user / system / papi / scoreboard / schedule / plugin 等）。

```sql
CREATE TABLE variable_aliases (
    wall_id     TEXT    NOT NULL,
    full_name   TEXT    NOT NULL,             -- 完整变量名（含 namespace）
    alias       TEXT    NOT NULL,             -- 玩家起的短别名
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (wall_id, full_name),         -- 同 wall 内一个 fullName 只一个别名
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

CREATE INDEX idx_variable_aliases_wall ON variable_aliases(wall_id);
```

### 2.9.2 表：`user_global_variables`（0.4.3 引入，V015）

全服共享的用户变量（`userglobal/<name>` namespace；`name` 单字段全服唯一，**不带 wallId**）。补 0.4.0 user 变量 per-wall 不能跨画布的遗留。

```sql
CREATE TABLE user_global_variables (
    name           TEXT    PRIMARY KEY,        -- 不含 userglobal/ 前缀；全服唯一
    owner_uuid     TEXT    NOT NULL,           -- 创建者
    owner_name     TEXT    NOT NULL,           -- 创建时玩家名
    type           TEXT    NOT NULL,           -- 'STRING' / 'NUMBER' / 'BOOLEAN' / 'COLOR'
    default_value  TEXT,                       -- fallback 链中段
    current_value  TEXT,                       -- 当前值
    bound_to       TEXT,                       -- 绑定的插件 namespace（可空）
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX idx_ugvar_owner ON user_global_variables(owner_uuid);
CREATE INDEX idx_ugvar_bound ON user_global_variables(bound_to) WHERE bound_to IS NOT NULL;
```

- **无 wall 外键**：全服级状态，不随某个 wall cascade 删除；`.canvas` 工程文件不含 userglobal（跨服务器无意义）
- **配额**：per-owner 500 + 全服 10000（config 可调）

### 2.9.3 表：铁路网络 5 表（0.4.4 引入，V016）

完整铁路网络抽象：线路 + 站点 + 车次（含服务类型 / 编组 / 区间 / 备注）+ 每站精确时刻表 + wall 绑定。`RailScheduleProvider` 接管 rail-bound wall，从 `rail_timetable` 精确查站时刻（非估算）。

```sql
CREATE TABLE rail_lines (
    id           TEXT    PRIMARY KEY,    -- "line-<8hex>" 或玩家命名
    name         TEXT    NOT NULL,
    code         TEXT,                   -- 短代号（"L1" / "M2"）；可空
    color        TEXT,                   -- "#RRGGBB"；可空
    owner_uuid   TEXT    NOT NULL,
    owner_name   TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);

CREATE TABLE rail_stations (
    id           TEXT    PRIMARY KEY,
    line_id      TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    code         TEXT,
    sort_order   INTEGER NOT NULL,
    is_terminus  INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE CASCADE
);

CREATE TABLE rail_runs (
    id                TEXT    PRIMARY KEY,
    line_id           TEXT    NOT NULL,
    run_number        TEXT    NOT NULL,
    direction         TEXT    NOT NULL,                  -- "up" / "down"
    service_type      TEXT    NOT NULL DEFAULT 'local',  -- 4 内置（local/express/section/limited）+ 自定义字符串
    cars              INTEGER,                           -- 编组节数；可空
    start_station_id  TEXT,                              -- null = 线路首站
    end_station_id    TEXT,                              -- null = 线路末站
    notes             TEXT,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    UNIQUE (line_id, run_number),
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE CASCADE,
    FOREIGN KEY (start_station_id) REFERENCES rail_stations(id) ON DELETE SET NULL,
    FOREIGN KEY (end_station_id) REFERENCES rail_stations(id) ON DELETE SET NULL
);

CREATE TABLE rail_timetable (
    run_id          TEXT    NOT NULL,
    station_id      TEXT    NOT NULL,
    arrival_time    TEXT,                  -- HH:mm:ss；首站可空
    departure_time  TEXT,                  -- HH:mm:ss；末站可空
    stops_here      INTEGER NOT NULL DEFAULT 1,  -- 0 = 跳站（大站快车）
    PRIMARY KEY (run_id, station_id),
    FOREIGN KEY (run_id) REFERENCES rail_runs(id) ON DELETE CASCADE,
    FOREIGN KEY (station_id) REFERENCES rail_stations(id) ON DELETE CASCADE
);

CREATE TABLE wall_rail_bindings (
    wall_id      TEXT    PRIMARY KEY,
    line_id      TEXT,                     -- NULL = 未绑定（fallback ManualScheduleProvider 旧路径）
    station_id   TEXT,
    direction    TEXT,                     -- "up" / "down" / "both"
    updated_at   INTEGER NOT NULL,
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE,
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE SET NULL,
    FOREIGN KEY (station_id) REFERENCES rail_stations(id) ON DELETE SET NULL
);
```

- **rail + manual 共享 `schedule:*` namespace**：RailScheduleProvider 接管的 wall 让 ManualScheduleProvider 跳过 push，避免双写同 key
- **`wall_rail_bindings.line_id IS NULL` 走 fallback**：兼容只用 ManualSchedule 的旧 server

### 2.10 表：`wall_scripts`（0.7.0 引入，V017）

墙脚本规则（视觉运行时；契约 `docs/scripting.md §2`）。脚本不进 ProjectState（D7），
独立表 + `ScriptStore` 内存镜像（onEnable `loadFromDb` 全量加载）。

```sql
-- V017
CREATE TABLE IF NOT EXISTS wall_scripts (
    id         TEXT    PRIMARY KEY,                -- "sr-<8hex>"
    wall_id    TEXT    NOT NULL REFERENCES walls(wall_id) ON DELETE CASCADE,
    enabled    INTEGER NOT NULL DEFAULT 1,
    name       TEXT    NOT NULL,
    rule_json  TEXT    NOT NULL,                   -- ScriptRule 整体 Jackson 序列化
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_wall_scripts_wall ON wall_scripts(wall_id);
```

- **rule_json 整体存**（照 project_json 范式不拆列）：trigger / actions / blockLayout 全在内。
  积木树是深嵌套结构，拆列无查询价值
- **enabled / id / wall_id 列权威**：load 时若 blob 内值与列不符，以列重建 record——
  `script.enable` 只翻列不重写 blob，避免两处漂移
- **坏 blob 防御**：单行解析失败跳过 + SEVERE log，不拖垮整墙；`loadAll` 整体查询失败
  异常外响（启动期与 migration 失败同级），`loadByWall` 保持整体防御返空
- **级联删除三层**：FK CASCADE 删 DB 行 + SessionManager wallDeleteHook → `ScriptStore.clearWall`
  清内存 + 前端 `project.reset()` → `useScriptStore().reset()`
- **配额**：单墙 ≤ `scripts.max-rules-per-wall`（config，默 16）

---

## 3. PersistentDataContainer 约定

### 3.1 命名空间

所有 PDC key 使用插件命名空间：`NamespacedKey(plugin, "<key>")`。
命名空间字符串固定：`"hikaricanvas"`（`NamespacedKey(plugin, key)` 取插件名小写，`HikariCanvas` → `hikaricanvas`）。

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

## 4. `.canvas` 工程文件格式（0.8 Part A 实装）

> **实装状态（2026-06-19 核对）：本节描述的 `.canvas` zip 导出 / 导入功能已在 0.8 Part A 落地。**
> 代码核对结论：
> - 导出：纯前端 `web/src/composables/useProjectExport.ts` + `web/src/lib/canvasFile.ts`，用 **fflate** 的 `zipSync` 打 zip（无服务端往返、无 JSZip）；
> - 导入：后端 `POST /api/project/import`（`web/ProjectImportHandler` → `canvasfile/ProjectImporter` 编排，`canvasfile/CanvasArchive` 流式安全解包用 `java.util.zip.ZipInputStream`）；
> - 前端有「导出工程」（TopBar 更多菜单）/「导入 `.canvas`」（`ImportProjectModal`）UI 入口。
>
> 契约总纲见 `docs/import-export.md`。下文 §4.1–§4.6 据已实装代码回填。
>
> **仍属 Part B（未实装）**：SVG → 原生元素导入（D5/D6，前端 `useSvgImport` + 后端 SVG 清洗落 `assets/icons/`）。故 `assets/icons/*.svg` 当前**只被解包白名单接纳、不被任何代码写入或摄入**（见 §4.1 注）。

玩家在编辑器中可「导出工程」供离线保存 / 分享，也可「导入 `.canvas`」整体替换当前会话工程。

### 4.1 文件结构

`.canvas` 是 **zip 压缩包**，扩展名 `.canvas`。内部（解包白名单见 `CanvasArchive.ALLOWED_TOP` + `ASSETS_PREFIX`）：

```
mysign.canvas
├── manifest.json            # 必选
├── project.json             # 必选：完整 ProjectState
├── scripts.json             # 可选：ScriptRule[]（0.8-A4 新增；无脚本则省略）
├── thumbnail.png            # 可选：预览缩略图 256×128
└── assets/
    ├── <sha256[:16]>.png    # 工程引用的上传图片（内容寻址，与服务端命名一致）
    └── icons/<id>.svg       # 工程引用的用户自定义图标（见下注，Part B 才写/读）
```

- **必选项缺失 → `IMPORT_MALFORMED`**：解包后若缺 `manifest.json` 或 `project.json`，`CanvasArchive.unpack` 直接拒（`CanvasArchive.java`）。
- **`assets/<hash>.png`**：导出时 `collectImageHashes` 扫所有图层的 image 元素 `source` 去重，逐张拉字节塞进包；导入时逐张走与上传同等的不可信防御链落 hash（`AssetIngest`，见 §4.4）。
- **`assets/icons/<id>.svg`（Part B）**：解包白名单**接纳** `assets/icons/<file>` 条目（`CanvasArchive.isSafeEntryName`），但当前导出**不写**（`useProjectExport` 只收 image hash、不收 `user/<id>` 图标）、导入摄入器**显式跳过**（`AssetIngest.isAssetPng` 只认 `assets/<file>.png`，注释明确「排除 `assets/icons/*.svg`」）。SVG 图标的写入 / 清洗 / 落盘是 **Part B**（`import-export.md §2.4/§3.3/§5.3`），结构图保留该路径作前向占位。
- **前向兼容**：老导入器遇到未知顶层条目应忽略而非报错（当前白名单外条目由 `isSafeEntryName` 拒为 `IMPORT_BAD_ENTRY`，白名单内的可选条目缺失则正常跳过）。

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

v3 起 `project.json` 可含可选 `timelines[]` / `activeTimelineId`（0.6 引入，见 §2.4.2）。导入老的 v1/v2 `project.json`（无这两个字段）时按静态处理（`timelines` 读为 `null`），无须迁移。时间轴是工程状态的一部分（序列化进 `ProjectState`），故 `.canvas` 导出天然带上它，无需 `assets/` 之类外置资源承载。

> **脚本不进 `project.json`**：墙脚本独立存 `wall_scripts` 表（不在 ProjectState，见 §2.10 / 脚本总纲 D7），故 `.canvas` 用独立的 `scripts.json` 条目承载（0.8-A4 新增）。形态见 **§4.6**。

### 4.4 导入语义

导入是后端 `POST /api/project/import`（multipart 收 `.canvas` 字节），鉴权 `canvas.edit`（fail-closed）。编排见 `ProjectImporter.importInto`（流程契约 `import-export.md §3.2`）：

1. **流式安全解包**（`CanvasArchive.unpack`）：三闸边解边计数（包 / 单条目 / 总解压，闸值来自 config `import.*`，见 §5.1）+ 条目名安全校验（无 `..` / 绝对路径 / 反斜杠 / NUL）+ 白名单条目（§4.1）；缺 `manifest.json` / `project.json` → `IMPORT_MALFORMED`。
2. **解析 manifest**（`CanvasManifest.parse`）：校验 `spec`（缺失/≤0 → `IMPORT_MALFORMED`；> `CANVAS_SPEC_MAX`(=1) → `IMPORT_SPEC_UNSUPPORTED` 提示升级插件）、`kind=="project"`、`wall.width/height>0`。
3. **物化 `project.json` → `ProjectState`**（`ProjectMaterializer.materialize`）：复用既有 `@JsonCreator` + 元素/数值校验栈，不信任文件内任何数值；并与**当前会话墙尺寸**匹配，超出 → `IMPORT_SIZE_MISMATCH` 中止，提示开匹配尺寸新会话。
4. **`assets/*.png` 摄入**（`AssetIngest.ingestAll`）：逐张走 magic + ImageIO 隔离解码（200ms 超时 + 头部尺寸预检）+ 按内容重算 hash + 配额事务落盘；单张缺/拒**静默跳过**（计入图片配额，防绕过上传限频），差额产出 `asset-quota` warning。
5. **孤儿关键帧轨丢弃 + warn**（`ProjectImporter.stripOrphanTracksAndCollect`）：若 `project.json` 含 `timelines[]`，其 `tracks` 的 key 是 elementId；对照所有图层的 elementId 集合，引用**不存在 elementId** 的 track（孤儿轨，常见于只导入了部分元素或元素被删过的工程）**剔除**，每条加一条 `orphan-track-dropped` warning。**此前 §10 留待定的「丢弃 vs 保留」现定稿为：丢弃 + warn**（不让坏引用进运行期，否则 Ticker 按 elementId 查不到元素会空插值）。
6. **整体替换会话工程**（`EditSession.replaceProject`）：保留多层 / 时间轴。
7. **`scripts.json` 导入**（若包内含且会话已绑墙，见 §4.6）。
8. 全量快照广播（`pushSnapshot`）+ 游戏内地图全画布重绘（`throttler.submit`）+ 持久化（`wallRepo.updateState`）+ `audit PROJECT_IMPORT`。

> **已知限制（导入路径不起播动画）**：throttler 只投静态像素帧；`persistWall` 全链的 Ticker 自动播 / 触发器 rebuild **不**在导入路径触发，故导入工程里的时间轴动画不会自动起播——需手动播一次，或随墙下次加载 / 会话回收时自然起播。
>
> **导入成功响应** `{ ok:true, warnings:[{kind, detail}…] }`；warning 不阻断，`kind` ∈ `asset-quota` / `orphan-track-dropped` / `script-invalid` / `script-command-blocked` / `script-quota`（错误码 → HTTP status 见 `import-export.md §4`：`IMPORT_ZIP_TOO_LARGE`→413、`IMPORT_SPEC_UNSUPPORTED`/`IMPORT_SIZE_MISMATCH`→409、`IMPORT_BAD_ENTRY`/`IMPORT_MALFORMED`→400）。

### 4.5 导出语义

编辑器「导出」动作（纯前端 `useProjectExport.exportProject` + `canvasFile.ts`，无服务器往返）：
1. 取当前 `ProjectState`；`collectImageHashes` 扫图层 image 元素 → 逐张走现有图片下载端点 `GET /api/upload/{hash}?sessionId=…` 拉字节塞 `assets/<hash>.png`（任何失败优雅跳过，不中断导出）
2. 渲染缩略图 256×128（`renderExportThumbnail`）→ `thumbnail.png`（可空）
3. `buildManifest` 填充（`spec=CANVAS_SPEC=1`、`kind="project"`、`wall{width,height}` 等；`plugin_version` 取 ready 时后端上报的 `serverVersion`，缺省省略）
4. 取当前墙脚本 `useScriptStore().listSorted` → `scripts.json`（非空才写，见 §4.6）
5. `assembleCanvasZip` 用 **fflate** 的 `zipSync`（`level: 6`）打 zip → `downloadBlob` 触发 `<name>.canvas` 下载

> **打包库 = fflate**（`web/package.json` 依赖 `fflate@0.8.3`，非 JSZip；项目从未引入 JSZip / file-saver）。导出**不**经过服务器存储，完全在浏览器端完成。

### 4.6 `scripts.json` 形态（0.8-A4 新增）

`scripts.json` = 一个 JSON 数组，每项是一条 `ScriptRule`（record 形态以 `scripting.md §2.1` 为权威：`id` / `wallId` / `enabled` / `name` / `trigger` / `actions`（Action 树，含 If 嵌套）/ `blockLayout`）。墙脚本独立存 `wall_scripts`（不在 ProjectState，§2.10 / D7），故用独立条目承载。无脚本时**省略**该条目（`assembleCanvasZip` 的 `scriptsJson` 可选）。

```jsonc
// scripts.json = ScriptRule[]（见 scripting.md §2.1）
[
  {
    "id": "sr-1a2b3c4d",
    "wallId": "<导出时的 wallId>",   // 导入时由后端重绑到当前会话墙
    "enabled": true,
    "name": "比分牌",
    "trigger": { "type": "playerKill" },
    "actions": [ /* … Action 树 … */ ],
    "blockLayout": "<前端积木摆放坐标 JSON，后端原样存取>"
  }
]
```

**导入语义**（`ScriptImporter.importScripts`，§4.4 步骤 7；契约 `import-export.md §2.3` / `security.md §13.5`）：

- **`wallId` 重绑**：文件内的 `id` / `wallId` 在目标服无意义；逐条经 `ScriptStore.create` 落库时**忽略 incoming id/wallId**、生成全新 `sr-<8hex>` 并强制绑定到**当前会话墙**。
- **全量重校验**（不信任文件内 `rule_json`）：结构校验 `ScriptRuleValidator.validate`（不过 → 跳过 + `script-invalid`）+ 递归 `ConditionEvaluator.checkSyntax` 预检所有 `if/waitUntil/repeatUntil` 条件语法（不过 → 跳过 + `script-invalid`）。
- **命令模板缺失**：`runCommand.templateId` 在目标服 config 不存在 → `script-command-blocked`（detail=templateId），**不跳过**、规则照常落库（运行期 `CommandTemplateEngine` 判为 Blocked、编辑器灰显）。
- **落 `wall_scripts`**：`ScriptStore.create`；单墙配额超 `scripts.max-rules-per-wall`（默 16）→ `script-quota` warning + **停止**处理后续规则。
- 整个 `scripts.json` 无法解析（非数组等）→ 单条 `script-invalid` warning，**不抛**（脚本导入失败不中断整次工程导入）。

> **装配可空**：`ProjectImporter` 的 `ScriptImporter` 可为 null（`ScriptStore` 未配的旧装配 / 无脚本子系统）；此时包内 `scripts.json` 被静默忽略，工程本体照常导入。

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
| `import.canvas-max-mb` | int | ≥ 1 | `10` |
| `import.canvas-max-entry-mb` | int | ≥ 1 | `10` |
| `import.canvas-max-total-mb` | int | ≥ 1 | `50` |

所有 duration 支持 `s` / `m` / `h` 后缀。

> **`import.*`（0.8）**：`.canvas` 导入 zip 三闸，单位 **MB**，防恶意大文件 / zip 炸弹（`HikariCanvasConfig.ImportConfig` + `config.yml` `import:` 段）。下界均为 `1`（`Math.max(1, …)` 兜底）。`canvas-max-mb` = 整个包上限（超 → 413 `IMPORT_ZIP_TOO_LARGE`）；`canvas-max-entry-mb` = 单个内部文件解压上限（边读边计数，不信声明大小）；`canvas-max-total-mb` = 所有内部文件解压总和上限（防多文件累加炸弹）。`CanvasArchive` 把 MB × 1024×1024 换算成 byte 后施闸。

---

## 6. 迁移策略

### 6.1 版本号定义

- **插件版本**：SemVer（`MAJOR.MINOR.PATCH`）
- **DB schema 版本**：单调整数，每次变更 +1
- **模板 spec 版本**：独立整数，见 `template-spec.md`
- **协议版本**：独立整数，见 `protocol.md`
- **.canvas spec 版本**：独立整数。**0.8 首发 = `1`**（前端 `CANVAS_SPEC` / 后端 `ProjectImporter.CANVAS_SPEC_MAX` 均 = 1，§4.4–§4.5）；导入 `spec > 当前支持` → `IMPORT_SPEC_UNSUPPORTED`

### 6.2 DB 迁移流程

```
plugin/src/main/resources/db-migrations/
├── V001__initial.sql
├── V002__drafts.sql
├── ...
└── V017__wall_scripts.sql
```

**迁移清单不靠目录扫描** —— `MigrationRunner.MIGRATIONS` 静态 `List` 显式声明每个 `(version, 资源路径)`（shadow jar 下 classpath 目录扫描不稳定）。新增迁移必须同时落 SQL 文件 + 往该列表末尾追加条目。

**当前迁移清单（核对 `MigrationRunner.java`，最高 V017；V009 跳号未落地脚本）：**

| 版本 | 文件 | 引入版本 | 主要内容 |
| --- | --- | --- | --- |
| V001 | `V001__initial.sql` | M2 | 初始 4 表：`pool_maps` / `sign_records` / `audit_log` / `template_usage` |
| V002 | `V002__drafts.sql` | M2-M5 | 加 `drafts` 表（二段式编辑模型） |
| V003 | `V003__drafts_add_maps.sql` | M3 | `drafts` 加 maps 字段 |
| V004 | `V004__drafts_wall_id_alias.sql` | M4 | `drafts` 加 wall_id / alias |
| V005 | `V005__walls_unified.sql` | M5.5 | **重构**：DROP `sign_records` + `drafts`，drop+recreate `pool_maps`（去 `sign_id`），新建 `walls` 表 |
| V006 | `V006__walls_protocol_version.sql` | M8-B | `walls` 加 `protocol_version` 列（标记 project_json 形态 v1/v2） |
| V007 | `V007__image_uploads.sql` | M13 | 新建 `image_uploads` 表（含 `refcount` 列） |
| V008 | `V008__templates.sql` | M14 | 新建 `templates` 表（玩家模板） |
| V009 | （跳号） | — | 迭代中预留，**未落地脚本**，`MIGRATIONS` 列表无此项 |
| V010 | `V010__remove_refcount.sql` | M16-P6 | `ALTER TABLE image_uploads DROP COLUMN refcount` |
| V011 | `V011__user_variables.sql` | 0.4.0 | 新建 `user_variables` 表 |
| V012 | `V012__wall_schedules.sql` | 0.4.0 | 新建 `wall_schedules` + `schedule_entries` 表 |
| V013 | `V013__schedule_precision.sql` | 0.4.0 bugfix | `wall_schedules` 加 `precision` 列 |
| V014 | `V014__variable_aliases.sql` | 0.4.2 | 新建 `variable_aliases` 表（per-wall 别名） |
| V015 | `V015__user_global_variables.sql` | 0.4.3 | 新建 `user_global_variables` 表（全服唯一） |
| V016 | `V016__rail_network.sql` | 0.4.4 | 铁路网络 5 表：`rail_lines` / `rail_stations` / `rail_runs` / `rail_timetable` / `wall_rail_bindings` |
| V017 | `V017__wall_scripts.sql` | 0.7.0 | 新建 `wall_scripts` 表（视觉运行时脚本） |

> 时间轴（0.6）**不建表**——序列化进 `walls.project_json` blob（见 §2.4.2），故 schema 版本无对应条目。

启动时：
1. 读 `schema_version` 表最大 version `N`
2. 遍历 `MigrationRunner.MIGRATIONS`，对 `version > N` 的条目按序应用
3. 每应用一个脚本 → 在 `schema_version` 表插入新 row
4. **每个 migration 各自包一个事务**（M15.4 P0-28），失败回滚不留半态；可选 `auto-backup`（pre-release 默认关）

**0.6 例外（无 schema 变更）：** 0.6 时间轴把协议版本由 2 升至 3（详见 `protocol.md`），但**不引入新的 DB schema 版本**——无新表、无 `ALTER`，`timelines` / 关键帧轨全在 `walls.project_json` blob 层加（§2.4.2）。与历来"每次 DB 变更 +1"（§6.1）的惯例对照：本次变更不触碰任何表结构，故 `schema_version` 不动；版本演进体现在 `project_json` 内部的 `protocolVersion` 字段（lazy on-write 写成 3），而非 schema 整数。

### 6.3 破坏性变更处理

- **pool_maps schema 变更**：必须保持既有 map 数据可用
- **walls.project_json 结构变更**：对应 `protocol.md §7` 升版；迁移脚本或启动时懒转换
- **模板 spec 升版**：旧模板文件保持可加载，读取时 `adapter.transform(oldYaml) → currentSpec`

### 6.5 M5.5 V005 整体重置（2026-04-27）

M5.5 重构涉及 schema 大改：合并 `drafts` + `sign_records` → `walls`、`pool_maps` 删 `sign_id` 列。决策按 V005 一次性 drop + recreate 而不是 alter：

```sql
-- V005__walls_unified.sql （实际脚本要点，已核对）
DROP TABLE IF EXISTS sign_records;
DROP TABLE IF EXISTS drafts;
DROP INDEX IF EXISTS idx_pool_sign;
DROP INDEX IF EXISTS idx_pool_session;
-- SQLite 不支持 DROP COLUMN（V005 当时），pool_maps 走「建新表 → drop 旧表」recreate
-- 去掉 sign_id 列（不是 ALTER DROP COLUMN）：
CREATE TABLE pool_maps_new (...);   -- 新 schema，无 sign_id
DROP TABLE pool_maps;
-- （注：脚本以 pool_maps_new 承接；列对齐细节见 db-migrations/V005__walls_unified.sql）

CREATE TABLE walls (...);  -- 完整 §2.4 schema
CREATE INDEX/UNIQUE INDEX ...;
```

理由：M5.5 阶段无生产数据，drafts/sign_records 累计 < 50 行；走 alter + 数据迁移成本远高于 drop + 重建。生产发布前最后一次允许 drop。后续任何破坏性变更必须走严格 alter 迁移。

### 6.5.1 V010 DROP COLUMN refcount（M16-P6.3，2026-05-16）

`image_uploads.refcount` 列在 V010 中 DROP。理由：M15.4 起 refcount 改为运行期从 `project_json` JSON_EACH 算（避免 element.add / element.delete 时多一次 DB UPDATE 引入事务竞争）；refcount 列变成 stale 数据源，留着误导调试。Pre-release（0.x SNAPSHOT）阶段允许激进 DROP COLUMN，符合 §6.6.1 规则。首次 stable（≥1.0.0）发版后类似清理必须走"逻辑删除"路径。

### 6.4 备份与恢复

插件不主动备份 DB。但文档提示：
- `data.db` 应随世界文件一并快照
- 恢复时确保 DB 与世界文件的时间一致，否则 map 与 PDC 可能不匹配

### 6.6 Migration 兼容性规则（pre-release vs stable 发版）

> **M15.4 P0-28/29 落地**（2026-05-16）：M15 之前 migration 走的是"激进 drop+recreate"（如 V005 整体重置），适合 pre-release 阶段；首次 stable（≥1.0.0）之后必须切到 forward-only + 强制 auto-backup。
>
> **版本语义**（M18 后调整）：`0.x.y-SNAPSHOT` 全部视为 pre-release 阶段，允许激进改 schema；`1.0.0` 起视为 stable 发版。当前 `0.2.0-SNAPSHOT` 仍处 pre-release。

#### 6.6.1 Pre-release（0.x SNAPSHOT）

允许激进改 schema：V<N+1> 可 `DROP` 旧表 / 重命名列 / 删字段。
`database.auto-backup-before-migration` config 默认 `false`。

#### 6.6.2 Stable（≥1.0.0）发版后

强制 forward-only：

- 不允许 `DROP TABLE` / `DROP COLUMN`（用户数据可能丢失）
- 不允许 `ALTER COLUMN` type 改变（兼容性破坏）
- 新加列 `ADD COLUMN` 必须有 default 值或 nullable
- 列删除走"逻辑删除"（保留物理列 + 应用层不用）
- 表删除走"重命名为 `_v<NNN>_archive`"（保留 30 天后清）

强制 auto-backup：

- `database.auto-backup-before-migration: true`（config 默认值改）
- 每个 migration 前自动 `cp data.db data.db.pre-V<NNN>.bak`
- 备份保留 30 天，超出由 BackupReaper（v2 加）清

#### 6.6.3 Migration 测试要求（stable 发版后）

每个新 migration 必须有 fixture 测试：跑 V<N-1> baseline DB → V<N> →
验证关键查询仍返同等价数据。测试 fixture 在 `plugin/src/test/resources/migration-fixtures/V<NNN>__before.sql`。

详见 `docs/journal.md` 2026-05-16 M15.4 条目（P0-28/29 落地）。

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

-- 全局已锁定画（published_at 非 NULL = 已锁定/只读，时间戳 = 锁定时刻；非"已发布"语义，见 §2.4）
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
- [x] **0.6 引入 / 0.8 定稿**：孤儿关键帧轨（导入的 `timelines[].tracks` 引用当前工程不存在的 elementId）丢弃 vs 保留——**已定稿为「丢弃 + `orphan-track-dropped` warn」**（0.8 Part A 实装，`ProjectImporter.stripOrphanTracksAndCollect`，见 §4.4 步骤 5）
- [ ] **0.6 引入**：`timelines` 含大量关键帧时 `project_json` blob 体积是否需要上限约束（单 wall 一个 blob，关键帧数无天然边界；与 `limits.*` config 段的关系待定）
