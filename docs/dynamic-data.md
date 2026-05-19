# 动态数据接入设计（0.4.0）

> **状态**：规划阶段（2026-05-19 定稿）；实施留待用户通知。所有数据模型 / 协议 / API 决策以本文为准。

## 0. 目标 & 设计哲学

**HikariCanvas 0.4.0 把"静态招牌"升级到"动态信息屏"**。覆盖场景：
- 路牌实时晚点 / 列车 ETA
- 服务器公告 / 在线人数 / 玩家欢迎屏
- PvP 比分实时更新
- 商店动态时价
- 任意 PAPI 占位符
- 玩家自定义变量手动改值（活动赛分 / 时刻表）

**核心定位**：HikariCanvas 是**展示层 + 通用扩展口 + 简单到中等内置编辑**，**不是业务系统**。专业数据（铁路时刻 / PvP 引擎 / 商店逻辑）由专业插件负责，HikariCanvas 提供统一 Push API 让它们推数据进来。

**绝对纪律**（避技术债）：
- **Push 优于 Pull**：插件主动 push，HikariCanvas 不做 active polling
- **变量是 string，业务在外**：HikariCanvas 只存字符串值 + 类型 hint；语义解析在插件侧
- **零外部依赖可用**：内置变量族（time / online / wall.*）够普通用户做基础动态招牌
- **PAPI 优先桥接**：不重新造已存在的轮子
- **可视化脚本走 Blockly（1.x）**：避代码沙盒 RCE，玩家友好

---

## 1. 四层数据源

```
┌─ Tier 1: 玩家自定义变量（用户定义） ──────────────┐
│   • 玩家在编辑器 UI 创建变量                       │
│   • user/<name> 命名空间                          │
│   • 手动改值（+/- 按钮 / 输入框 / 命令）          │
│   • 可"让插件接管"（绑定 plugin namespace push）   │
│   • 持久化到 .canvas / DB                         │
├─ Tier 2: 插件推送（专业接入） ─────────────────────┤
│   • HikariCanvasAPI.setVariable(ns, key, v, ttl) │
│   • <namespace>/<key> 命名空间                    │
│   • 插件控制更新频率                              │
│   • TTL 过期 fallback 到 default 或上次手动值     │
├─ Tier 3: 系统变量（内置） ───────────────────────┤
│   • server.time / server.online / server.motd     │
│   • wall.id / wall.alias / wall.owner             │
│   • scoreboard.<obj>.<player>（Bukkit 桥接）      │
├─ Tier 4: PAPI 桥接（生态兼容） ───────────────────┤
│   • papi/%placeholder% 自动暴露                   │
│   • 5s TTL，不缓存敏感数据                        │
│   • PAPI 未装则该层不存在                         │
└────────────────────────────────────────────────────┘
```

**优先级**（同 key 时）：Tier 2 > Tier 4 > Tier 3 > Tier 1 default

---

## 2. 数据模型

### 2.1 Variable record

```java
public record Variable(
    String namespace,        // "user" / "system" / "papi" / 插件 namespace
    String key,              // "红队比分" / "server_time" / "%player_name%"
    VarType type,            // STRING / NUMBER / BOOLEAN / COLOR
    String defaultValue,     // null = 无 fallback
    String currentValue,     // 当前缓存值（push 写入 / 手动设 / Tier 3 计算）
    long updatedAt,          // 上次更新时间戳
    long ttl,                // 0 = 永久；>0 ms = TTL
    @Nullable String source, // "BedWarsPlugin" / "manual" / "system"
    Set<String> referencedByWalls  // inverted index：哪些 wall 引用
) {}

enum VarType { STRING, NUMBER, BOOLEAN, COLOR }
```

### 2.2 VariableStore

- **Global store**：`Map<String fullName, Variable>` 共享
  - fullName = `<namespace>/<key>`（如 `user/红队比分` / `bedwars/match_a.red_score`）
- **Per-wall ACL**：每个 wall 只能引用 global store 的变量；不存在 wall-scoped 变量（简化模型）
- **持久化**：用户变量（`user/*` namespace）持久化到 DB；插件变量 / 系统变量 / PAPI 变量内存态，重启不保留

### 2.3 引用语法

文本字段（TextElement.text）含占位符：

```
${var:user/红队比分}                            完整命名
${var:红队比分}                                 简写（自动加 user/ 前缀）
${var:bedwars/score}                            插件变量
${var:server.time}                              系统变量（点分号 alias 兼容）
${var:papi:%player_name%}                       PAPI

# fallback 语法
${var:bedwars/score|fallback=0}                 变量不存在 / 过期时显示 0

# 格式化（v0.4.0 不做，留 0.5.0）
${var:server.time|format=HH:mm}
${var:eta_minutes|format=int|suffix=min}
```

**正则**：`\$\{var:([^|}]+)(\|fallback=([^}]+))?\}`

---

## 3. WS 协议扩展

### 3.1 新 op 族

```typescript
// variable.create — 玩家创建用户变量
{ "op": "variable.create", "payload": {
    "name": "红队比分",        // 自动加 user/ 前缀
    "type": "number",
    "defaultValue": "0"
} }

// variable.update — 改名 / 改类型 / 改 default
{ "op": "variable.update", "payload": {
    "fullName": "user/红队比分",
    "patch": { "default": "0", "type": "number" }
} }

// variable.set — 玩家手动改当前值
{ "op": "variable.set", "payload": {
    "fullName": "user/红队比分",
    "value": "5"
} }

// variable.delete
{ "op": "variable.delete", "payload": { "fullName": "user/红队比分" } }

// variable.bind — 让插件接管
{ "op": "variable.bind", "payload": {
    "fullName": "user/红队比分",
    "boundTo": "BedWarsPlugin"   // null = unbind
} }
```

### 3.2 state.patch 扩展

VariableStore 变更通过 state.patch 推到客户端：

```typescript
{
    "op": "state.patch",
    "payload": {
        "version": 42,
        "patches": [
            { "op": "replace", "path": "/variables/user~1红队比分/currentValue", "value": "5" }
        ]
    }
}
```

注意：variables 不在 ProjectState 内（per-wall），而在 global VariableStore，但 state.patch 仍走同 WS 通道。客户端的 VariableStore mirror 也是单例 store。

### 3.3 HTTP 端点

- `GET /api/variable/list?wall=<wallId>` → 该 wall 引用的变量当前快照
- `GET /api/variable/list-all-namespaces` → 所有可用 namespace + key（编辑器自动补全用）
- 这些是只读端点 + 短 cache（5s）

---

## 4. 插件 Push API

### 4.1 接口设计

```java
package moe.hikari.canvas.api;

public interface HikariCanvasAPI {
    /**
     * 主动 push 变量值。插件每次数据变化时调用。
     * 
     * <p>HikariCanvas 自动：
     * 1. 写入 VariableStore
     * 2. 查 inverted index 找引用该变量的 wall
     * 3. mark wall dirty → 下次 ProjectionThrottler tick 重画
     * 
     * @param namespace 插件 namespace，必须 == 注册时声明的（防 spoof）
     * @param key       变量名，[a-zA-Z0-9_.-]+ ≤ 64
     * @param value     字符串值
     * @param ttl       TTL；null = 永久；建议合理值如 30s 防卡僵尸数据
     * @throws PermissionDeniedException 如果 namespace 与注册不匹配
     */
    void setVariable(String namespace, String key, String value, @Nullable Duration ttl);
    
    /**
     * 批量 push（性能：内部 dirty wall merge，比单次循环高效）
     */
    void setVariables(String namespace, Map<String, VariableUpdate> updates);
    
    /**
     * 注册 namespace。每个插件 onEnable 时调用一次。
     */
    void registerNamespace(String namespace, NamespaceInfo info);
    
    /**
     * 列出当前 namespace 下的所有 key（编辑器自动补全用）
     */
    void declareKey(String namespace, String key, VarType type, @Nullable String hint);
    
    /**
     * 撤销 variable（如插件停用）
     */
    void unsetVariable(String namespace, String key);
}

public record NamespaceInfo(
    String displayName,        // "BedWars 比赛数据"
    String pluginName,
    String version
) {}

public record VariableUpdate(
    String value,
    @Nullable Duration ttl
) {}
```

### 4.2 插件接入示例

```java
public class BedWarsPlugin extends JavaPlugin {
    private HikariCanvasAPI canvas;
    
    @Override
    public void onEnable() {
        // 1. 检测 HikariCanvas
        Plugin hikari = Bukkit.getPluginManager().getPlugin("HikariCanvas");
        if (hikari == null) {
            getLogger().info("HikariCanvas not found, score display disabled");
            return;
        }
        canvas = ((HikariCanvas) hikari).getAPI();
        
        // 2. 注册 namespace
        canvas.registerNamespace("bedwars", new NamespaceInfo(
            "BedWars 比赛数据", "BedWarsPlugin", getDescription().getVersion()));
        
        // 3. 声明可用 key（让编辑器自动补全显示）
        canvas.declareKey("bedwars", "match_a.red_score", VarType.NUMBER, "红队当前分数");
        canvas.declareKey("bedwars", "match_a.blue_score", VarType.NUMBER, "蓝队当前分数");
        canvas.declareKey("bedwars", "match_a.mvp", VarType.STRING, "MVP 玩家名");
    }
    
    // 4. 比赛事件触发 push
    @EventHandler
    public void onPlayerKill(MatchKillEvent ev) {
        canvas.setVariable("bedwars", "match_a.red_score",
                String.valueOf(ev.matchRedScore()),
                Duration.ofMinutes(5));  // 5 分钟无更新视为比赛结束
    }
}
```

### 4.3 错误隔离

**HikariCanvas 内部**：
- 所有 `setVariable` 调用包 `try-catch`：插件抛异常 → log + 该次 push 丢弃，**不影响 HikariCanvas 本身**
- Provider listener 注册在 daemon 线程：crash 不卡主线程
- 单 plugin 推送频率 > 100/s → 警告 + 限流到 100/s

---

## 5. 渲染期变量解析

### 5.1 线程模型（**核心纪律**）

**变量 resolve 不在 MC 主线程跑**：

```
[主线程 (server tick)]
  ProjectionThrottler 每 200ms tick：
    1. 取 wall 的 cached element list
    2. 对每个 TextElement，用 cached variable values 替换 ${var:X}
       （cached values 来自 VariableStore，O(1) lookup）
    3. 渲染到 BufferedImage → 投影
  
  没有同步 resolve 调用 → 完全无阻塞
  
[异步 daemon (var-resolve pool)]
  Tier 3 系统变量定时算（server.time 每秒 / online 每 10s）
  Tier 4 PAPI 桥接也走异步（每 wall 引用 PAPI 时 5s TTL 触发 resolve）
  Tier 2 插件 push 是事件驱动，无定时
  Tier 1 用户变量手动改值，无定时
```

### 5.2 缓存

每个 Variable 内部含 `updatedAt` + `ttl`：
- 读取时若 `now - updatedAt > ttl` → 标记 stale，但仍返回 cached value（避免渲染期阻塞）
- async daemon 检测到 stale → 触发 refresh（Tier 3/4）
- refresh 完成 → 写入 → trigger wall dirty → 下次 tick 重画

**TTL 边界**：
- 最小 100ms（防虐用）
- 最大 永久（0 = 永久，玩家手动变量默认永久）
- 默认 30s（Tier 2 没声明 TTL 时）

### 5.3 fallback 链

```
渲染时取 ${var:X} 值：
  1. cached_value 存在且非空 → 用 cached_value
  2. cached_value 为空 / null → 用 ${var:X|fallback=...} 语法里的 fallback
  3. 无 fallback → 用 Variable.default
  4. default 也无 → 用 "???"（系统兜底，让用户看出来变量配错了）
```

---

## 6. 编辑器 UX

### 6.1 变量管理面板（0.4.0-P2）

新组件 `VariablePanel.vue`，挂在 RightPanel 底部或 Topbar 按钮触发：

```
┌─ 变量管理 ──────────────────────────┐
│ [🔍 搜索]      [+ 新建变量]          │
│                                     │
│ 🌐 系统（自动）                      │
│   server.time         "14:35:22"    │
│   server.online       "12"          │
│   wall.alias          "郑州地铁1号线" │
│                                     │
│ 📦 由插件提供                        │
│   bedwars/match_a.red_score   "5"   │
│     ↳ BedWarsPlugin │ TTL 5min      │
│                                     │
│   train/line1.eta             "(过期 2min)" │
│     ↳ ⚠ RailwayCraft 未更新        │
│     ↳ fallback → 08:15              │
│                                     │
│ 👤 我的变量                          │
│   user/红队比分              [number] │
│     当前 "5" │ 默认 "0"             │
│     [-1] [+1] [改值] [让插件接管]   │
│   user/下一班车              [string] │
│     当前 "08:30 站台 1"             │
│     [✏ 编辑] [删除]                 │
└─────────────────────────────────────┘
```

**交互细节**：
- "+/- 按钮"：number 类专属
- "让插件接管" → 弹 picker 选已注册的 namespace + key
- "类型化输入框"：number 用 numeric input；color 用 color picker；boolean 用 toggle
- "删除"：弹确认 + 列出引用该变量的 wall 元素警告

### 6.2 TextElement 内嵌入变量

**0.4.0 朴素版**：textarea 直接输入 `${var:name}` 字面字符串。下方提供：
- "插入变量"按钮 → 弹 Variable Picker → 选完插入到光标位置
- live preview：textarea 下方 200ms debounce 显示最终渲染结果

**0.4.1 chip 版（独立 milestone）**：Notion-style contentEditable，变量渲染成蓝色 pill。鼠标 hover 显示当前值；点击 chip 弹 picker 改绑定。

### 6.3 Variable Picker（自动补全）

弹 popover 分类显示所有可用 variable：

```
┌─ 插入变量 ──────────────────────┐
│ [搜索框]                         │
│ ▼ 🌐 系统                        │
│   server.time          14:35     │
│   server.online        12        │
│   wall.alias           郑州地铁  │
│ ▼ 📦 BedWarsPlugin               │
│   bedwars/match_a.red_score     │
│   bedwars/match_a.blue_score    │
│   bedwars/match_a.mvp           │
│ ▼ 🔌 PAPI                        │
│   papi:%player_name%             │
│   papi:%server_uptime%           │
│ ▼ 👤 我的变量                    │
│   user/红队比分                  │
│   user/下一班车                  │
└─────────────────────────────────┘
```

数据来源：`GET /api/variable/list-all-namespaces`

---

## 7. 内置 Provider 实现

### 7.1 Tier 3 系统变量

| Variable | 数据源 | TTL | 备注 |
|---|---|---|---|
| `server.time` | `LocalTime.now()` formatted | 60s | `HH:mm` 默认；24h |
| `server.real_time` | `Instant.now()` | 60s | full ISO |
| `server.tick` | `Bukkit.getCurrentTick()` | 1s | tick 计数 |
| `server.online` | `Bukkit.getOnlinePlayers().size()` | 30s | |
| `server.online_list` | join names | 30s | 逗号分隔 |
| `server.motd` | server.properties | 1h | 静态 |
| `server.tps` | Paper TPS API | 30s | 例如 `19.8` |
| `server.name` | config | 1h | |
| `wall.id` | wall self | 永久 | |
| `wall.alias` | wall self | 5s | 可改名 |
| `wall.owner` | wall self | 永久 | 玩家名 |
| `wall.owner_uuid` | wall self | 永久 | UUID |
| `scoreboard.<obj>.<player>` | `Bukkit.getScoreboardManager()` | 10s | |

### 7.2 Tier 4 PAPI 桥接

```java
public final class PapiVariableBridge {
    /** Plugin lifecycle: onEnable 检测 PAPI */
    public void init() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        registered = true;
    }
    
    /** Tier 4 resolve hook */
    public Optional<String> resolve(String papiPlaceholder, @Nullable Player wallOwner) {
        if (!registered) return Optional.empty();
        try {
            String result = PlaceholderAPI.setPlaceholders(wallOwner, papiPlaceholder);
            return Optional.of(result);
        } catch (Exception e) {
            log.warning("PAPI resolve failed for " + papiPlaceholder + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
```

PAPI placeholder 自动 wrap 为 `papi/<placeholder>` 变量；TTL 默认 5s（PAPI placeholder 通常是查询型）。

### 7.3 内置 Manual Schedule Provider（兜底列车功能）

零外部依赖的"时刻表" provider，让玩家不依赖第三方铁路插件也能做基础站牌：

- 玩家在 wall 的 "Schedule Manager" panel 配时刻表（JSON-like UI）
- 内置 provider 暴露变量：
  - `schedule.<wallId>.next_departure` → "08:30"
  - `schedule.<wallId>.next_destination` → "郑州东站"
  - `schedule.<wallId>.eta_minutes` → "12"
  - `schedule.<wallId>.is_arriving` → "true"（5min 内为 true）

**v0.4.0 包含**——价值高 + 工时不重（~20h）。

---

## 8. 持久化

### 8.1 用户变量（`user/*` namespace）

存到 `data.db` 新表：

```sql
CREATE TABLE IF NOT EXISTS user_variables (
    wall_id TEXT NOT NULL,              -- 变量所属 wall（user 变量是 per-wall）
    name TEXT NOT NULL,                 -- "红队比分"（不含 user/ 前缀）
    type TEXT NOT NULL,                 -- 'STRING' / 'NUMBER' / 'BOOLEAN' / 'COLOR'
    default_value TEXT,                 -- 可空
    current_value TEXT,                 -- 当前值
    bound_to TEXT,                      -- 绑定到的插件 namespace，null = 手动
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (wall_id, name)
);
```

**Migration**：V011__user_variables.sql（M28 时新加）

### 8.2 插件 / 系统 / PAPI 变量

**不持久化**——重启后由插件 / 系统重新 push。这是 Push 模式的自然属性。

如果插件没及时 push，渲染时走 fallback 链。

### 8.3 .canvas 工程文件

`ProjectState` 增加 `userVariables: Map<String, Variable>` 字段：
- export `.canvas` 时序列化
- import `.canvas` 时反序列化 + 写入 user_variables 表

---

## 9. 权限模型

### 9.1 玩家权限节点

| 权限 | default | 用途 |
|---|---|---|
| `canvas.var.read` | true | 查看变量列表 |
| `canvas.var.write.own` | true | 在自己 wall 上创建 / 改值 user/* 变量 |
| `canvas.var.write.any` | op | 在任意 wall 上 |
| `canvas.var.delete.own` | true | 删除自己 wall 上的 user/* |
| `canvas.var.delete.any` | op | |
| `canvas.var.bind` | op | 让 user/* 变量被插件接管（敏感操作） |
| `canvas.var.command` | op | 用 `/canvas var` 命令 |

### 9.2 插件 namespace ACL

- 插件注册 namespace 时**自己声明**自己的 namespace
- HikariCanvas 拒绝插件 A 推 plugin B 的 namespace（防 spoof）
- 跨插件污染防御：注册中心 `Map<namespace, RegisteringPlugin>`，重复注册 throw

### 9.3 PAPI 桥接 ACL

- HikariCanvas 不做额外 ACL，**完全信任 PAPI**
- PAPI placeholder `%player_X_balance%` 的 ACL 由 PAPI / Vault / 经济插件自己负责
- HikariCanvas 只是 placeholder string 的 forwarder

### 9.4 变量值长度限制

防止 push 滥用：
- 单变量值 ≤ 4096 字符（够长字符串但不会爆内存）
- 单 namespace 变量数 ≤ 1000
- 全局变量数 ≤ 10000

---

## 10. 性能 / 节流

### 10.1 Wall dirty 合并

```
插件 1ms 内连推 100 次（同一变量） →
  1. setVariable 调用 100 次 → 每次写 VariableStore（O(1)）
  2. 每次 mark wall dirty（O(1)）
  3. 但 ProjectionThrottler 200ms 才 tick 一次
  4. 200ms 间隔最终只重画 1 次（取最新值）

所以 push 频率不影响渲染负担——push 是廉价的写操作。
```

### 10.2 Push 限流

防卡服务器：
- 单插件 push > 100/s → 警告 + 限流（drop tail）
- 全局 push > 1000/s → 警告 + reject 新 push（保护期 10s）
- 限流策略可在 `config.yml` 调

### 10.3 编辑器 live preview throttle

textarea 输入 → 200ms debounce → 渲染预览（避免每 keystroke 都重画）。

---

## 11. 已知限制 & 边界

| 项 | 说明 |
|---|---|
| 命名空间冲突 | 玩家 `user/X` + 插件 `bedwars/X` 不冲突（不同 namespace）；同 namespace 内同 key 后写覆盖前写 |
| TTL 过期 fallback | 优先级：cached value → fallback 语法 → Variable.default → "???" |
| 重启后 | 用户变量从 DB 恢复；插件 / 系统 / PAPI 变量等插件 / system 重新 push |
| 单 wall 多变量 | 性能无问题；wall dirty 合并保证只重画一次 |
| 变量删除 | 引用该变量的 element 显示 fallback / "???"；不级联删除 element |
| Wall 删除 | user/* 变量级联删除（user_variables 表 cascade by wall_id） |
| 玩家变量长度 | 单值 ≤ 4096 char |
| 编辑期 placeholder 解析 | 编辑器走 live preview，渲染当前 cached value |

---

## 12. 0.4.0 实施 Phase

| Phase | 内容 | 工时 |
|---|---|---:|
| **P1 变量系统底座** | VariableStore + 协议 + 持久化 + Compositor 渲染替换 + threading + 权限 | 62h |
| **P2 编辑器基础 UX** | 变量管理面板 + 朴素 textarea + Variable Picker | 30h |
| **P3 内置 Provider** | 系统变量 13 项 + Scoreboard 桥接 + PAPI 桥接 + Manual Schedule | 20h |
| **P4 Plugin Push API + 示例** | HikariCanvasAPI 接口 + 注册中心 + DemoTrainPlugin + DemoScorePlugin | 28h |
| **P5 命令族 + 测试 + docs** | `/canvas var` 命令 + 单测 + baseline + 教程 | 10h |
| **总** | | **150h** |

约 **6-7 周 wall-clock**。

每 phase 结束**可推出演示**：
- P1 完成 → demo "WS op + 简单 ${var:X} 替换"
- P2 完成 → demo "玩家创建变量 + 实时改值"
- P3 完成 → demo "PAPI / Scoreboard / Manual Schedule 列车站牌"
- P4 完成 → demo "外部插件接入推送（模拟 BedWars 比分）"
- P5 完成 → demo "命令行运维 + 完整教程"

### 0.4.1（Notion chip 编辑器，~25h，1 周）

`${var:X}` chip 化 + 鼠标悬停显示当前值 + click 弹 picker 改绑定。

---

## 13. 0.5.0+ 路线（动画 + 时间轴 + Blockly）

**0.5.0**（~120h，5-6 周）：
- AnimationLayer：keyframe 插值
- 时间轴编辑器 UI（AE 风格简化版）
- 元素事件（onTick / onVariableChange）

**0.6.0+ Blockly 脚本**（~200h，8-10 周）：
- Blockly 块编辑器（Google MIT 库）
- 编译为内部 DSL JSON（**不是 JS / Lua**，避代码沙盒 RCE）
- HikariCanvas 解释器执行 JSON tree
- 事件驱动：玩家走近 / 变量变化 / 定时器
- 安全限制：单事件 1000 步上限 / 无递归 / 无文件 / 无网络

---

## 14. 不在 0.4.0 范围

留 v1.x：
- 自定义脚本（Blockly 留 0.6.0+）
- 多人协作 OT/CRDT
- 完整动画 / 时间轴（留 0.5.0）
- per-player personalized 变量（每玩家看不同值，性能成本高）
- 变量数据类型扩展（list / map / object 留 1.x）
- 加密 / 签名变量（防恶意插件篡改）

---

## 15. 参考实施清单

### docs/protocol.md 更新
- §6 op 列表加 `variable.create / update / set / delete / bind`
- §error codes 加 `VARIABLE_NOT_FOUND` / `VARIABLE_NAMESPACE_DENIED` / `VARIABLE_TYPE_MISMATCH`
- §close codes 不动

### docs/data-model.md 更新
- §1 SQLite schema 加 `user_variables` 表
- §6.5 V011 migration 备注（pre-release 阶段可加 column）

### docs/security.md 更新
- §权限节点加 `canvas.var.*` 段
- §审计事件加 `VARIABLE_BIND / VARIABLE_SET / VARIABLE_DELETE`
- §限流加 push throttle

### docs/architecture.md 更新
- §13 动态画板段细化：P-1（渲染期占位符）= 本设计；P-2 反模式不动；P-3 = HikariCanvasAPI Push

### CLAUDE.md 更新
- 里程碑列表加 0.4.0 路线段
- 「其他不可越界的技术决策」加：**Push 模式 + 不在主线程 resolve**

### docs/api.md（新文件）
- `HikariCanvasAPI` 完整接口文档 + 接入教程 + 示例插件
- M28 实施时落地（本规划阶段先不写）

---

## 16. 设计决策固化（不动）

1. **Push 模式** > Pull 模式（性能 / 解耦 / 扩展性）
2. **变量是 string**（业务语义在插件侧，HikariCanvas 不解析）
3. **用户变量持久化**（DB + .canvas）
4. **插件 / 系统 / PAPI 变量不持久化**（重启 push 重建）
5. **resolve 不在主线程**（PrejectionThrottler 用 cache，async daemon 后台拉）
6. **namespace 严格隔离**（防 plugin spoof）
7. **PAPI 桥接零 ACL**（信任 PAPI 自己）
8. **fallback 链**：cached → ${var:X|fallback=...} → default → "???"
9. **TTL 全局 min 100ms**（防虐用）+ **默认 30s**
10. **每 phase 可演示**（0.4.0-P1..P5 都是可用 milestone）
