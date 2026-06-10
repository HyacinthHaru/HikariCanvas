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

**P3-J 引入的 namespace 注入 / 别名规则**（双端 interpolator 一致）：

- `${var:user/X}` + `wallId="w-abc"` → 内部 `user:w-abc/X`（user 变量是 per-wall）
- `${var:wall.X}` + `wallId="w-abc"` → 内部 `system:w-abc/wall.X`（{{SystemVariableProvider}} 按
  per-wall namespace 注册 `wall.id` / `wall.alias` / `wall.owner` / `wall.owner_uuid`）；wallId 为
  null（模板 publish / 预览路径）跳过注入
- `${var:scoreboard.<obj>.<player>}` → 内部 `scoreboard/<obj>.<player>`（点分号 alias →
  slash；与 {{ScoreboardVariableProvider}} `store.create("scoreboard", "<obj>.<player>", …)` 存储
  侧约定一致）
- `${var:server.time}` 等系统点分号 alias **暂未实现完整映射**（P3-J 仅 wall.* / scoreboard.\*）；
  系统变量当前以 slash 形式访问：`${var:system/server.time}`。完整 dot-alias 留 0.4.1+

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

- `GET /api/variable/list?wall=<wallId>` → 该 wall 引用的变量当前快照（暂未实装；ready payload 已能下发 wall 引用快照，前端不需要主动 fetch）
- `GET /api/variable/list-all-namespaces?sessionId=<id>&wallId=<wallId>` → **所有可用 namespace + 已声明 keys**（编辑器 VariablePicker 自动补全用，P3-M 实装）
- 只读端点 + 短 cache（仅 wallId 缺省路径 5s server-side cache；带 wallId 因 user 变量增删频繁直走实时算）

#### `/api/variable/list-all-namespaces` 返样

```json
{
  "namespaces": [
    {
      "namespace": "user:w-3a17b2c1",
      "displayName": "我的变量",
      "dynamic": false,
      "keys": [
        {"key": "red_score", "type": "NUMBER", "description": "默认值: 0", "ttlMs": 0}
      ]
    },
    {
      "namespace": "system",
      "displayName": "系统变量",
      "dynamic": false,
      "keys": [
        {"key": "server.time", "type": "STRING", "description": "当前服务器本地时间 HH:mm", "ttlMs": 60000},
        {"key": "wall.alias", "type": "STRING", "description": "wall 玩家命名（可空）（per-wall）", "ttlMs": 5000}
      ]
    },
    {
      "namespace": "scoreboard",
      "displayName": "记分板",
      "dynamic": true,
      "keys": []
    },
    {
      "namespace": "papi",
      "displayName": "PlaceholderAPI",
      "dynamic": true,
      "keys": []
    },
    {
      "namespace": "schedule:w-3a17b2c1",
      "displayName": "列车时刻表（per-wall）",
      "dynamic": false,
      "keys": [...]
    }
  ]
}
```

**字段语义**：

- `namespace`：用于在 placeholder 文本里引用的 namespace（如 `${var:system/server.time}`）。
- `displayName`：UI 显示分组名（picker 标题等）。
- `dynamic`：是否为动态 namespace（{@link VariableProvider#isDynamic()}）；动态 namespace `keys` 始终为空，编辑器应给出模板字符串说明（如 `scoreboard.<obj>.<player>` / `papi:%placeholder%`）。
- `keys[i].key`：完整 key（不含 namespace 前缀）。
- `keys[i].type`：`STRING` / `NUMBER` / `BOOLEAN` / `COLOR`。
- `keys[i].description`：人类可读说明（可选）。
- `keys[i].ttlMs`：TTL（毫秒），0 = 永久。让前端知道刷新频率（如标识 "动态" 类型变量）。

**鉴权**：必须带 `sessionId` query param（同 `/api/upload/{source}` / `/api/upload/quota`）；不通过 401 `{"error":"UNAUTHORIZED"}`。失败 500 `{"error":"INTERNAL"}`，不 echo 异常内容（防内部细节泄露）。

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
     * @param plugin    调用方 Plugin 实例（用于 ACL spoof 防御 + plugin disable 时自动清理）
     * @param namespace 插件 namespace，必须 == 注册时声明的（防 spoof）
     * @param key       变量名，[a-zA-Z0-9_.-]+ ≤ 64
     * @param value     字符串值（非 null；空串合法，≤ 4096 char）
     * @param ttl       TTL；null = 沿用变量原 TTL；Duration.ZERO = 永久；>0 必须 ≥ 100ms
     * @throws PluginNamespaceException 如果 namespace 未注册或不属于 plugin
     */
    void setVariable(Plugin plugin, String namespace, String key, String value, @Nullable Duration ttl);
    //               ^^^^^^^^^^^^ 新增第一参数（M28-P4 实施决策）

    /**
     * 批量 push（性能：内部 dirty wall merge，比单次循环高效）
     */
    void setVariables(Plugin plugin, String namespace, Map<String, VariableUpdate> updates);

    /**
     * 注册 namespace。每个插件 onEnable 时调用一次。
     *
     * @throws NamespaceConflictException 已被其他插件注册
     * @throws IllegalArgumentException   namespace 格式非法 / 与保留前缀冲突
     */
    void registerNamespace(Plugin plugin, String namespace, NamespaceInfo info);

    /**
     * 列出当前 namespace 下的所有 key（编辑器自动补全用）。
     * declareKey 不写值；仅供 Variable Picker 列出可选项。
     */
    void declareKey(Plugin plugin, String namespace, String key, VarType type, @Nullable String hint);

    /**
     * 撤销 variable（如插件停用）
     */
    void unsetVariable(Plugin plugin, String namespace, String key);
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

**实施实际接口**（M28-P4 落地）：

- 接口位置：`plugin/src/main/java/moe/hikari/canvas/api/HikariCanvasAPI.java`
- 实现位置：`plugin/src/main/java/moe/hikari/canvas/variable/plugin/HikariCanvasAPIImpl.java`
- 注册中心：`PluginNamespaceRegistry`（防 spoof，原子 `putIfAbsent`）
- 限流：`PushRateLimiter`（per-plugin 100/s + 全局 1000/s + 10s circuit break）
- 卸载清理：`PluginCleanupListener`（`PluginDisableEvent` → 立即 unregister + 30s 延迟 purge）
- API 包独立 enum：`moe.hikari.canvas.api.VarType`（不引用内部 `variable.VarType`，shadowJar relocate exclude 保护外部插件 import 路径稳定）
- 异常体系：`NamespaceConflictException`（跨 plugin 冲突）+ `PluginNamespaceException`（Code = `NAMESPACE_NOT_REGISTERED` / `NAMESPACE_ACL_DENIED`），均 RuntimeException
- 完整接入教程：`docs/api.md`

### 4.2 插件接入示例

```java
public class BedWarsPlugin extends JavaPlugin {
    private HikariCanvasAPI canvas;

    @Override
    public void onEnable() {
        // 1. 检测 HikariCanvas（推荐 ServicesManager 方式，零编译耦合）
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas == null) {
            getLogger().info("HikariCanvas not found, score display disabled");
            return;
        }

        // 2. 注册 namespace（首参 this = Plugin 实例，用于 ACL spoof 防御）
        canvas.registerNamespace(this, "bedwars", new NamespaceInfo(
            "BedWars 比赛数据", "BedWarsPlugin", getDescription().getVersion()));

        // 3. 声明可用 key（让编辑器自动补全显示）
        canvas.declareKey(this, "bedwars", "match_a.red_score", VarType.NUMBER, "红队当前分数");
        canvas.declareKey(this, "bedwars", "match_a.blue_score", VarType.NUMBER, "蓝队当前分数");
        canvas.declareKey(this, "bedwars", "match_a.mvp", VarType.STRING, "MVP 玩家名");
    }

    // 4. 比赛事件触发 push
    @EventHandler
    public void onPlayerKill(MatchKillEvent ev) {
        canvas.setVariable(this, "bedwars", "match_a.red_score",
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

**P3-K 实装**：`plugin/.../variable/provider/PapiVariableBridge.java`。软依赖 PAPI（不在 build.gradle 加 dep），通过 reflection 调 `me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(OfflinePlayer, String)`。PAPI 未装时 `refreshInterval=ZERO` → daemon 不调度，整 `papi/*` namespace 不出现，**零开销**。

```java
// 抽象：PapiAccessor（生产用 ReflectionPapiAccessor，测试注入 fake）
public interface PapiAccessor {
    void initialize();           // 检测 PAPI + 加载 reflection method
    boolean isAvailable();
    @Nullable String resolve(String placeholder);
    default void shutdown() {}
}

public final class PapiVariableBridge implements VariableProvider {
    @Override public String namespace() { return "papi"; }
    @Override public boolean isDynamic() { return true; }
    @Override public Duration refreshInterval() {
        return accessor.isAvailable() ? Duration.ofMillis(5_000) : Duration.ZERO;
    }

    @Override public void initialize() {
        accessor.initialize();
        if (!accessor.isAvailable()) return;
        store.registerDynamicLookupHook((fullName, ns) -> {
            if ("papi".equals(ns)) handleDynamic(fullName);
        });
    }
    // refresh() 在 daemon 线程跑（PAPI placeholder 多 stateless thread-safe）；
    // 失败 → log + 不写 value，保留旧 cached 让 fallback 链工作
}
```

**store key 编码层**：VariableStore key 校验正则 `[a-zA-Z0-9_.-]+` 不允许 `%`，所以本桥接对外形态：

| 玩家语法 | store fullName | tracker 内 |
|---|---|---|
| `${var:papi:%player_name%}` | `papi/pct_player_name_pct` | 原文 `%player_name%` |

`handleDynamic` 接受两种形态（编码 / 原文 dot+slash），统一编码为 `pct_<inner>_pct`；refresh 时按 tracker 内原文调 PAPI、按 encoded key 写 store。**P3-K 实装时 interpolator 侧未做编码层**——`${var:papi:%xxx%}` 语法的 interpolator 编码留 P3-M 一同接入。

**ACL**：HikariCanvas 不做额外 ACL，完全信任 PAPI（详见 §9.3）。

**线程模型**：`refresh()` 在 daemon 线程跑——不切主线程。理由：(a) PAPI placeholder 量大时切主线程会拖 TPS；(b) 社区主流 placeholder stateless；(c) 失败 catch + log，旧 cached 保留，不污染。失败 placeholder 不阻断同 tick 内其他 placeholder 的 refresh（per-key try-catch）。

PAPI placeholder 自动 wrap 为 `papi/<encoded>` 变量；TTL 默认 5s（PAPI placeholder 通常是查询型）。

### 7.3 内置 Manual Schedule Provider（兜底列车功能）

零外部依赖的"时刻表" provider，让玩家不依赖第三方铁路插件也能做基础站牌。**0.4.0 bugfix 后**支持每 wall 独立的分钟 / 秒精度，并把 `is_arriving` 阈值改为可配（默认 60s）。

- 玩家在 wall 的 "Schedule Manager" panel 配时刻表 + 选精度（minute / second）
- 内置 provider 暴露 7 个变量（namespace = `schedule:<wallId>`）：

| 变量 | 类型 | 示例 | 说明 |
|---|---|---|---|
| `next_departure` | STRING | `"08:30"` / `"08:30:45"` | 下一班车出发时间；HH:mm 或 HH:mm:ss（按 wall 精度） |
| `next_destination` | STRING | `"郑州东站"` | 下一班车终点 |
| `eta_minutes` | NUMBER | `"12"` | 距下一班车几分钟（向下兼容，整除丢秒） |
| `eta_seconds` | NUMBER | `"742"` | **0.4.0 bugfix**：距下一班车几秒（秒精度主用） |
| `is_arriving` | BOOLEAN | `"true"` | eta ≤ `arriving-threshold-seconds` 时为 true |
| `arrival_status` | STRING | `"进站中"` / `""` | **0.4.0 bugfix**：进站中文案 / 空闲文案（config 可改） |
| `precision` | STRING | `"minute"` / `"second"` | **0.4.0 bugfix**：当前 wall 精度 |

**config.yml**（默认值）：

```yaml
dynamic:
  schedule:
    arriving-threshold-seconds: 60   # is_arriving / arrival_status 阈值
    arriving-text: "进站中"           # arrival_status 进站文案
    idle-text: ""                    # arrival_status 空闲文案
```

**刷新频率**：
- `precision="minute"` → 每 30s 一次 push（默认；现有 wall 升级后行为不变）
- `precision="second"` → 每 1s 一次 push

**schema**：V013 `ALTER TABLE wall_schedules ADD COLUMN precision TEXT NOT NULL DEFAULT 'minute'`；
现有 wall 平滑升级到 minute 精度。

**v0.4.0 + bugfix 包含**——价值高 + 工时不重（~25h）。

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

## 13. 0.5.0+ 路线（性能 Benchmark → 时间轴 → 视觉运行时）

> **2026-05-25 重订。** 原 §13 把 0.5.0 定为"动画+时间轴"、0.6.0 定为"Blockly 脚本"。经架构可行性评估（3 子代理深查现状 + 时间轴/Scratch 复杂度）后重排为：**先做性能 Benchmark 摸清硬件成本，再做时间轴（需 30fps 渲染管线），最后做视觉运行时（需时间轴的 action 底座）**。当前进度速览见 `CLAUDE.md` 路线图表；本节是详细设计。

### 13.0 设计哲学（前提）

所有 0.5.0+ 路线遵守"工具不是保姆"哲学（`PROPOSAL.md §2.1`）：**数据透明不替服主决策 / 不自动降级 / 不擦屁股**。性能测评 4 原则见 `PROPOSAL.md §5.2.7`。

**两个编辑器分支（终极愿景；2026-06-10 0.7.0 立项时修订）**：
- **层 A（After Effects-like 时间轴，0.6.0 已落地）**：keyframe + easing 编排**已有内容**，做循环 / 非线性动画（如服务器入口墙的"欢迎介绍"循环播放）。
- **层 B（Scratch-like 视觉运行时，0.7.0）**：可视化积木 + 事件驱动条件分支，编排**未知 / 实时更新**的内容（如地铁站牌"车到→图标亮→红色闪"、PvP"有人被击杀→比分++→比赛结束出 MVP→播全屏特效"）。

> **原"一画布二选一"已作废（scripting.md D2）**：脚本是上层，时间轴是被编排的素材（脚本可
> playTimeline/pause/seek），同画布共存；0.6 的三种触发器原样保留给简单场景。

### 13.1 版本顺序与依赖

| 版本 | 内容 | 为什么是这个顺序 |
|---|---|---|
| 0.4.10 | 修补批 + 哲学固化 | 外部 bug 审查后收口；为 0.5.0 留干净基线 |
| 0.5.0 | 纯服务端性能 Benchmark | **数据先行**：时间轴/动画是高风险投入，立项前必须有真实 rasterize/GC 成本数据 |
| 0.6.0 | 时间轴编辑器 | action（blink / play）是 0.7.0 Scratch 的依赖；且需把渲染管线推到 30fps |
| 0.7.0 | Scratch-like 视觉运行时 | 复用 0.6.0 的动画 action + 已有 template.expr / ChangeListener |

跳过 Benchmark 直接做时间轴 = 赌博（不知 30fps 在目标硬件上行不行）；跳过时间轴直接做 Scratch = action 集合缩水（只能改属性不能触发动画）。

### 13.2 — 0.4.10 修补批（~15h）

外部 bug 审查结果出来后的打磨 + 设计哲学固化（PROPOSAL §2.1/§5.2.7 已写）。范围按审查反馈定，不预设。可并入 0.5.0-P0。

### 13.3 — 0.5.0 纯服务端性能 Benchmark（~191h）

**目标**：让服主摸清"我这台服务器能撑多少画布"。production-grade，不做 MVP / 半成品。

**4 原则**（详 `PROPOSAL.md §5.2.7`）：后台模拟不破坏世界 / 数据透明 / 测可控的 / 不测网络。

**测什么（服务端可控成本）**：
- `rasterize` 耗时 p50/p95/p99（含 element draw / text layout / dither）
- `toPaletteSlice` 量化耗时
- GC 分配速率（BufferedImage 是大头）
- per-element-type 耗时分解（Text/Rect/Path/Image/Brush 各自 ms）
- 模拟 viewer 数的 packet 序列化成本（每 viewer 一份序列化 = 服务端 CPU 成本，**不是网络成本**）

**不测什么**：带宽 / 压缩比 / RTT / 丢包 / 服主的 zlib 配置——全砍（PROPOSAL §2.1 原则 3）。

**基本单位推敲**：
- 朴素单位"1 tile / 1 玩家 / 1 次刷新"方向对，但掩盖 2 类成本：rasterize 与 **wall 像素数**线性（5×5 一次 rasterize 比 5 个 1×1 便宜，因为一次性扫整 buffer），序列化与 **tile×fps** 线性；二者不能用同一单位 capture。
- "4×4@5fps = 3×3@10fps" 作为粗略 rule-of-thumb 可以（80 vs 90 tile-refresh/s，差 ~12%），但精确换算需分开 RENDER（按 wall 像素）与 SERIALIZE（按 tile×fps）。
- **50 mspt 预算公式**（给服主自算，不给结论）：
  ```
  主线程预算 = 50ms × 20tps = 1000 ms/s
  可用份额 ≈ 30%（其余给 world tick / 其他插件）= 300 ms/s
  单 wall×fps 主线程成本 = rasterize_p95 × fps（含安全 margin；系数由报告标定）
  可载 wall 数 ≈ 300 ÷ (单 wall×fps 成本)
  ```
- 注：rasterize 走 async 线程，主线程只做 schedule + packet handoff；真正的主线程成本需 Benchmark 实测标定，公式系数由报告给出。

**报告结构**：① 服务端可控部分（mspt / GC / per-element breakdown 三块 percentile）② 服主自算公式区（带宽自己 ping 自己测）。**给原料 + 公式，不给"你能开 N 个 wall"**。

**4 个已锁定决策（2026-05-30 brainstorming，不可越界）**：
1. **CI 不做性能数值门禁**——本地 commit baseline JSON + CI 只断言「bench 能跑通 + 不崩 + 在 timeout 内」功能性检查；性能 drift 人工复查。理由：0.4.7/0.4.8/0.4.9 三次 CI flaky 全栽在 perf/平台敏感断言，共享 runner ±2-3x 抖动，数值门禁要么松到没用要么紧到 flaky。
2. **viewer 缩放只测纯渲染管线**——16KB 像素 byte 对同 tile 所有 viewer 是<b>同一份</b>（rasterize 产物共享），per-viewer 只剩「重复 encode 同样 16KB + send」，而 send 是网络边界。故 benchmark 测 `rasterize → toPaletteSlice → byte[16384]`，viewer 数当作「encode 重复次数」的线性<b>外推</b>乘数，**不实跑 PacketEvents**；报告诚实标注「非实测」。
3. **报告 = JSON + CLI 表 + 独立自包含 HTML**（内联 SVG 图表，无外链依赖）。JSON 是 CI baseline + 服主自有工具的原料，CLI 给控制台人读，HTML 给可视化——图表只可视化原料、不给「推荐配置」结论。
4. **scene 全元素覆盖**——text/rect/circle/shape/path/image+mask/brush/icon/变量插值 text + 特效 + 真实混合，per-element 分解是核心价值。

**运行模型 = 方案 A：单一 headless 核心 + 两适配器**。核心（`SceneTimer`/`SceneLibrary`/`BenchCompositor`/`Instrumentation`）零 Bukkit/PacketEvents（rasterize 本就是纯函数）；适配器 1 = `/canvas bench` 命令（活服务器 async 线程），适配器 2 = JUnit/gradle harness（CI headless）。两上下文跑同一套数字、不分叉——正是决策②（不碰 PacketEvents）解锁的。

**Phase 分解**（每 phase 自身完整，非"TODO 待补"半成品）：
- **P1（~50h）✅ 2026-05-30**：Instrumentation（`ThreadMXBean` 分配计数 + GC bean 采样 + warmup）+ 全元素 `SceneLibrary`（21 确定性场景：9 单元素 + 5 特效 + 3 混合 + 4 尺寸梯度，固定 seed）+ `SceneTimer`（warmup→measure，rasterize/palette 分开计时 + blackhole 防 DCE）+ `BenchCompositor`（复刻 `RendererSnapshotTest` 无头装配 + 合成图片 loader 注入让 image+mask 渲真实像素）+ `/canvas bench list/run/report/clear` 命令族（async 守护线程 + JSON/CLI 输出 + 单 bench 守卫）+ 3 共享契约 record。10 单测含端到端 smoke（全 21 场景 headless 跑通，兼 P4 CI gate 种子）。**留 P2 精化**：IconElement 走占位（无 headless IconRegistry）、合成图固定 256² 代表性近似。
- **P2（~50h）✅ 2026-05-30**：6 聚合 record（`Percentiles` 线性插值 p50/p95/p99 + mean/min/max/stddev / `SceneResult` / `PerElementCost` / `GcSummary` / `EnvInfo` / `BenchmarkReport`）+ `ResultAggregator`（聚合 + alloc 均值 + per-element 边际 = 隔离场景均值 − 同尺寸空白基线 ÷ 元素数）+ `BenchmarkRunner`（选场景 → 测空白基线 → 逐场景计时 → 聚合 → per-element → GC/env 组装报告）+ `/canvas bench` 改产 `report.json`（聚合）+ `summary.txt` + 控制台 percentile 表。**关键澄清（修正 §13.3 原“matrix”措辞）**：rasterize 成本<b>不依赖 fps/viewer</b>（canvas 尺寸已烘进每个场景），故每场景<b>只测一次</b>，fps/viewer 仅作 P3 公式参数随报告记录，<b>不</b>为每个组合重复测量（否则是在重复测同一个东西）。9 P2 单测（percentile 数学 + 聚合 + per-element + Jackson round-trip）。**留 P3+**：真实 icon 成本（需无头 IconRegistry）/ 合成图逐尺寸精化
- **P3（~55h）✅ 2026-05-30**：`HtmlReportRenderer`（自包含 HTML5，<b>零外链</b>，Catppuccin Latte，仅内联 `<style>`+`<script>`）+ `SvgBarChart`（响应式内联 SVG 横向条形图，Locale.ROOT 防逗号小数 + 退化输入守卫）+ `BudgetFormula`（50mspt 预算公式 + 保守下界 disclaimer）。HTML 报告含：环境卡（机器/JVM/堆/GC 透明）+ config + 逐场景 percentile 表 + rasterize p95 条形图 + per-element 边际条形图 + GC + **50mspt 交互计算器**（服主填 mspt/tps/份额/fps，内联 JS 镜像 `BudgetFormula` 实时算每场景「可载 wall 数」）+ footer「给原料+公式不给结论」。两层转义（`esc` HTML + `jsStr` 防 `</script>` 逃逸）。`/canvas bench run` 现产 `report.json` + `summary.txt` + `report.html` 三件。4 P3 单测（公式数学 + SVG 边界 + HTML 自包含/转义对抗）。
- **P4（~20h）✅ 2026-05-30**：`BenchmarkPipelineSmokeTest`（CI 功能性 gate——compositor→runner→HTML 全管线 headless 跑通，只断言「能跑通 + 不崩 + 产出非空报告」，<b>0 性能数值断言</b>，随 `:plugin:test` 在 CI 每次 push/PR 跑）+ `docs/benchmark.md`（281 行运维指南：命令族 / 报告怎么读 / 50mspt 公式与交互计算器 / 为什么没有自动门禁 / 用实测容量设 config 软上限 / 4 原则）+ 版本号 0.4.10→0.5.0-SNAPSHOT（7 处）。**无自动 drift 报警 / 不提交 baseline**（数字机器特定、不跨机迁移，由服主在自己机器对比 report.json 人工复查）；**无自动 prune**（`/canvas bench clear` 手动清理，符合「不擦屁股」）。

> **0.5.0 完工（2026-05-30）**：P1 底座 + P2 聚合 + P3 HTML 报告 + P4 CI gate/docs 全部落地。后端 879 test 全绿 / shadow jar 159 MB / 0 baseline 漂移。下一步 0.6.0 时间轴需先做 P0 spike（30fps×4maps 实测 GC/mspt），用本期 Benchmark 工具量化。

### 13.4 — 0.6.0 时间轴编辑器（~360h；After Effects-like）

> **本节原为纸面设想，已被 `docs/timeline.md`（设计总纲）取代。** 数据结构 / 协议 v3 / 渲染管线 /
> 插值缓动数学 / 触发器 / 分期 / 工时一切以 timeline.md 为权威；配套契约见 `rendering.md §9`（插值+缓动）、
> `protocol.md`（v2→v3）、`data-model.md §2.4.2`（project_json v3）、`architecture.md §5.5`（AnimationTicker）。

定稿时对本节纸面设想做了几处更正，列此以免后人按旧设想实现：

- **Keyframe 存法取方案 B，不进 Element。** 原写 `Element.keyframes?`（方案 A）已否决；关键帧压平进
  `Timeline.tracks: Map<elementId, List<Keyframe>>`，`Element` 8 record 零改动（timeline.md D1/§2.2）。
- **默认帧率 20fps（config `timeline.max-fps` 默 60 安全阀），不是统一 30fps。** 不做成本估算 / 自动校准 /
  自动降级（timeline.md D3/§3.5）。
- **`rasterize` 走异步线程，不占主线程 tick budget。** 原"主线程 tick budget"风险不成立；约束是渲染线程
  算力 + 发包 + GC（timeline.md §3.5）。
- **HistoryStack 上限是 16，不是 100**（`HistoryStack.MAX_HISTORY=16`）；keyframe 连续拖动靠 coalesce 合并 +
  有 timeline 时 16→64（timeline.md D7/§7）。
- **0.6 触发器 = MANUAL + VARIABLE_CHANGE + SCHEDULE；PLAYER_NEAR 推迟 0.7**（需从零建事件层，与 0.7 Scratch
  触发系统重叠，timeline.md D5/§5）。
- **分期 6 段**（独立 P0 spike 折进 P2 首任务，一道 MVP 闸）：P1 数据模型+协议 v3+撤销(60) → P2 Ticker+池化
  +MVP(50) → P3 缓动+双端插值器+一致性 CI(70) → P4 前端 AE panel(100) → P5 触发器(35) → P6 一致性 CI+收尾(15)。
  详见 timeline.md §10/§11。

### 13.5 — 0.7.0 Scratch-like 视觉运行时（~360h）

> **本节原为纸面预估，已被 `docs/scripting.md`（0.7.0 设计总纲，2026-06-10 定稿）取代。**
> 数据结构 / 触发器 / 动作 / 安全模型 / 协议 v4 / 分期工时一切以 scripting.md 为权威；本节保留为档案。
> 定稿时的主要更正：① 事件系统层"几乎为零"已过时——0.6 P5 建成 TimelineTriggerRegistry（变量→播放
> 路由 + debounce），0.7 TriggerRouter 照其范式扩展；② action blink/playAnimation 依赖已就位
> （AnimationTicker.play/pause/seek）；③ "一画布二选一"作废，改为分层共存（scripting.md D2）；
> ④ 积木库选 **自写积木画布**，Blockly 否决（D1）；⑤ ExecuteCommand 走服主白名单模板 + 填参（D4）；
> ⑥ 工时 360h → ~340h（0.6 资产抵扣）。

**评估结论**：Medium-Low。变量系统层准备好（push + cached + ChangeListener + dynamic lookup），**但事件系统层几乎为零**——现有 4 个 Provider 全 polling，0 个 Bukkit gameplay event listener。Scratch trigger 需从零搭建。原 200h（Blockly）估偏低，含条件 / sandbox / 多 trigger / 前端积木真实落地 ~360h。

**重大利好**：`template/expr/*`（`Expr` AST + `ExpressionParser` + `ExpressionEvaluator`，~447 行）已是 Scratch condition 求值器的半成品，扩比较 / 算术运算仅 ~50 行。

**新数据结构（独立 ScriptStore，不进 ProjectState）**：
- 新表 `wall_scripts`（与渲染 / 编辑解耦，避免 state.patch 推送范围膨胀到脚本表达式）
- `ScriptRule { id, wallId, enabled, name, trigger, condition?(复用 Expr), actions[], budget }`
- `sealed Trigger { OnVariableChange / OnTimer / OnPlayerJoin / OnPlayerKill / OnCommand / OnLockChange / OnWallReady }`
- `sealed Action { SetVariable / SetElementProperty / ExecuteCommand(白名单) / PlaySound / Blink / Delay(≤5s) / Log }`
- `Budget { maxSteps:100, maxActions:50, maxInvocationsPerSecond:10, maxNestedDelayDepth:3 }`

**必须新建**：ScriptStore + TriggerListenerRegistry（7-10 个 Bukkit listener + 路由）+ ConditionEvaluator（extend ExpressionEvaluator）+ ActionExecutor（白名单 + 主线程 hop，element.update 类必须走 EditSession 标准 op 路径不绕过 history/lock）+ ScriptRunner（执行管线 + budget + circuit break）+ 前端积木 UI。

**主要风险**：
1. **Sandbox/RCE**（最高）：`ExecuteCommand` 必须强制白名单 + 模板参数化，禁字符串拼接（防 `/op @s` 夺权）；`Delay` 防 ABA loop（A→setVar→触发 B→setVar→触发 A）。
2. 主线程 hop（element.update / playSound / executeCommand 必须主线程；`onPlayerMove` 类高频 trigger 必须 sample，不能每 tick 跑）。
3. 双端 schema 一致性（建议**后端唯一权威 + 前端积木仅 UI**，不做客户端执行预览，避免分叉）。
4. ChangeListener 滥用（`fireChange` 是同步 for-loop，单 wall 挂 50 listener 会拖慢 → 改异步分发 + namespace/fullName index 路由）。
5. action `blink / playAnimation` **依赖 0.6.0 时间轴**——这是顺序约束的根因。

**ROI 提醒**：用户列举的场景（地铁站牌 / PvP 比分 / 倒计时）在 0.4.4 + 变量系统下已 **90% 可实现**（schedule + variable + textElement）；Scratch 只在"主动条件 + 副作用"超出展示层时才显著加值。**立项前再评估**。

**积木库选择**：Blockly（Google MIT）vs 自写——待定。Blockly 双向 schema 同步是全期最大维护负担，自写积木可控但工程量大。0.7.0 立项时定。

**Phase**：P0 spike（ChangeListener 挂 1 trigger + 主线程 hop + 1 action 走通端到端，30h）→ 5 trigger + 5 action 无条件分支命令行（80h）→ 条件分支 + sandbox（70h）→ 前端积木 UI（90h，工时大头）→ 剩余 trigger/action（60h）→ 压测+docs（30h）。

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

### docs/api.md（M28-P4 已落地）
- `HikariCanvasAPI` 完整接口文档 + 接入教程 + 示例插件 + FAQ
- 见 `docs/api.md`

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

---

## 17. 0.4.3 全局用户变量（规划，2026-05-21 定稿）

### 17.1 背景

0.4.0 P1 决策 3 把 user 变量按 wall 持久化（namespace = `user:<wallId>/X`），不能跨画布共享。
用户场景需要"全服可见、跨画布共享"的玩家自定义变量（如全服活动比分、公告状态等），
独立 namespace `userglobal/<key>` 不带 wallId 后缀。

### 17.2 数据模型

新 namespace：`userglobal/<key>`（key 规则同 user 变量：`[a-zA-Z0-9_.-]+` ≤ 64）。

**V015 migration** 新表 `user_global_variables`：

```sql
CREATE TABLE IF NOT EXISTS user_global_variables (
    name TEXT PRIMARY KEY,            -- 不含 userglobal/ 前缀
    owner_uuid TEXT NOT NULL,         -- 创建者
    owner_name TEXT NOT NULL,         -- 创建时玩家名（用于 Picker UI 显示，不强一致）
    type TEXT NOT NULL,               -- STRING / NUMBER / BOOLEAN / COLOR
    default_value TEXT,
    current_value TEXT,
    bound_to TEXT,                    -- 绑定插件 namespace（可空）
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX idx_user_global_variables_owner ON user_global_variables(owner_uuid);
```

注：name 是 PRIMARY KEY（全服唯一）— 全局变量名不能与其他玩家创建的重名。

### 17.3 ACL（已锁定决策）

- **外部插件禁推 `userglobal/*`**：`userglobal` 加入 `PluginNamespaceRegistry.RESERVED_NAMESPACES`。
  插件 `registerNamespace("userglobal", ...)` 抛 IllegalArgumentException。插件仍可用自己
  namespace 实现全服可见（如 `bedwars/X`），不需要也不应抢用 userglobal。
- **owner-only + admin override**：
  - `canvas.var.global.create` (default: true) — 任何玩家可创建
  - `canvas.var.global.write.own` (default: true) — owner 可改自己的
  - `canvas.var.global.write.any` (default: op) — **admin override**，可改任何
  - `canvas.var.global.delete.own` (default: true)
  - `canvas.var.global.delete.any` (default: op)
- **类型冲突**：name 已存在（任意 owner 创建过）→ 拒 `VARIABLE_EXISTS`；只能删原 + 重建
- **owner 离开后**：变量永久保留（同 user_variables 现状）；admin 可手动删

### 17.4 配额（已锁定决策）

```yaml
dynamic:
  variables:
    userglobal-max-per-owner: 500     # 每玩家上限
    userglobal-max-total: 10000       # 全服总上限
```

`VariableStore.createGlobal()` 检查：
- 该 owner_uuid 已有 N ≥ 500 → 拒 `OWNER_QUOTA_EXCEEDED`
- 全服总数 ≥ 10000 → 拒 `GLOBAL_QUOTA_EXCEEDED`

### 17.5 WS 协议

复用 0.4.0 `variable.*` 5 op，**加 `scope` 字段**：

```typescript
// variable.create payload
{
    "name": "red_score",
    "type": "NUMBER",
    "defaultValue": "0",
    "scope": "global"   // 0.4.3 新增；缺省 "wall" 保留 0.4.0 行为（per-wall user 变量）
}
```

`variable.update / set / delete / bind` 直接用 fullName 区分：
- `user:w-xxx/red_score` → wall 局部
- `userglobal/red_score` → 全局

ack 含 `{ fullName: "userglobal/red_score" }`。

### 17.6 interpolator 行为

`${var:userglobal/X}` 字面查询 store（**不**注入 wallId）。同 user 变量风格：
- `${var:user/X}` → `user:<wallId>/X`（注入）
- `${var:userglobal/X}` → `userglobal/X`（字面）

interpolator.resolveFullName 不动；天然支持（fallback 路径返字面）。

### 17.7 state.patch 广播：所有 session

per-wall 变量变更只推该 wallId 的 session。**全局变量变更要广播给所有连接的 session**：

```java
// SessionManager.broadcastVariableChangeToAll(event)
// 与现有 broadcastVariableChangeToWall(wallId, event) 并列
```

VariableStore.ChangeListener 触发时，HikariCanvas listener 检测：
- `event.fullName.startsWith("userglobal/")` → 走 broadcastToAll
- 否则走 broadcastToWall（现有 referencingWalls 路由）

### 17.8 Picker / UI 改造

VariablePicker 新分组：
- 👤 **我的**（wall 局部）
- 🌐 **我的全局变量**（owner = self 的 userglobal/*）
- 🌐 **其他全局变量**（owner ≠ self 的 userglobal/*，显示只读 + owner 名）
- 🚂 列车 / 📦 插件 / 🔌 PAPI / 🎯 系统

NewVariableDialog 加 **scope toggle**：
- [ 本 wall | 全局 ]
- 全局模式下提示文案："其他玩家可读取，但只有你和管理员能修改"

VariablePanel 行内显示 owner_name（自己 = 不显，他人 = 显 owner badge）+ 只读时禁用编辑控件。

### 17.9 别名（复用 0.4.2）

`variable_aliases` 表已支持任意 fullName（含 userglobal）。每 wall 独立别名：
- wall A 给 `userglobal/red_score` 起别名 "红队"
- wall B 给同变量起别名 "Red Team"

### 17.10 .canvas 文件

**不入 .canvas 工程文件导出**。理由：全局变量是服务器级状态（owner_uuid + 跨 wall 共享），
导入到另一服务器时无意义。引用全局变量的 wall 在导入后该占位符走 fallback "???"。

### 17.11 删除联动

owner / admin 删除 `userglobal/red_score`：
1. DB DELETE FROM user_global_variables WHERE name = ?
2. VariableStore.delete() → fireChange DELETED → state.patch 广播全 session
3. 所有引用该变量的 wall 渲染时走 fallback "???"
4. **不级联删除** wall 上引用它的 TextElement（与 user 变量同款规则）

### 17.12 实施 Phase（~13h）

| Phase | 范围 | 工时 |
|---|---|---:|
| **P1** | V015 migration + UserGlobalVariableDao + VariableStore.createGlobal/listGlobal | 3h |
| **P2** | EditSession scope='global' 路径 + PluginNamespaceRegistry userglobal 保留 + 5 权限节点 + AuditLog | 3h |
| **P3** | broadcastVariableChangeToAll + ChangeListener 路由 + interpolator 双端测试（已天然支持，加测）| 2h |
| **P4** | NewVariableDialog scope toggle + VariablePanel owner 显示 + Picker 分组改造 + i18n | 4h |
| **P5** | 配额 config + Quota 单测 + docs/variables.md §1.12 新节 + journal + 版本号 0.4.2 → 0.4.3-SNAPSHOT + push | 1h |
| **总** | 单 commit 合 5 phase | **13h** |

wall-clock 估 **~3 天**（按 0.4.x 节奏 agent 并行 / 串干）。

### 17.13 单测覆盖

- VariableStoreTest 加 createGlobal / listGlobal / userglobal namespace ACL
- UserGlobalVariableDao 单测（CRUD + per-owner 隔离）
- EditSessionTest 加 scope='global' 路径 + 配额拒
- PluginNamespaceRegistryTest 加 reserved namespace 检查（userglobal）
- broadcastVariableChangeToAll 单测（mock SessionManager）
- 端到端 EndToEndSmokeTest 加 1 case：玩家 A 创全局变量 + 玩家 B 不同 wall 读

至少 18 个新 case。

---

## 18. 0.4.4 铁路网络（线路 / 站点 / 车次 / 时刻表）（规划，2026-05-21 定稿）

### 18.1 背景

0.4.0 P3-L 的 ManualScheduleProvider 是**纯 per-wall**：每个 wall 独立配自己的时刻表，
100 个地铁屏 = 100 套独立配置。无法共享"1 号线"概念。

0.4.4 引入完整铁路网络抽象 + **真实地铁系统语义**：
- 线路 / 站点 / **车次（含服务类型 / 编组 / 区间 / 备注）** / **每站详细时刻表**
- wall 编辑器内下拉选**线路 + 本站 + 方向**自动绑定该站时刻，**改一处全服同步**
- wall 上可展示"A01 次 → 郑州东（6 节 大站快车）"完整运营语义

### 18.2 数据模型（5 表，V016 migration）

```sql
-- 线路（如 "1 号线" / "2 号线"）
CREATE TABLE rail_lines (
    id TEXT PRIMARY KEY,              -- "line1" 或 UUID
    name TEXT NOT NULL,               -- "1 号线"
    code TEXT,                        -- "L1" 短代号（可选）
    color TEXT,                       -- "#FF0000" 线路主题色
    owner_uuid TEXT NOT NULL,
    owner_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 站点（线路上的物理车站，有顺序）
CREATE TABLE rail_stations (
    id TEXT PRIMARY KEY,
    line_id TEXT NOT NULL,
    name TEXT NOT NULL,               -- "郑州火车站"
    code TEXT,                        -- "ZHF" 短代号（可选）
    sort_order INTEGER NOT NULL,      -- 0..N 在线路上的顺序
    is_terminus INTEGER NOT NULL DEFAULT 0,  -- 1 = 物理终点（首/末站）
    created_at INTEGER NOT NULL,
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE CASCADE
);
CREATE INDEX idx_rail_stations_line ON rail_stations(line_id);

-- ★车次（一趟具体的车，含运营语义元数据）
CREATE TABLE rail_runs (
    id TEXT PRIMARY KEY,
    line_id TEXT NOT NULL,
    run_number TEXT NOT NULL,         -- "A01" / "B02" 车次号（同线唯一）
    direction TEXT NOT NULL,          -- "up" (sort_order 递增) / "down" (递减)
    service_type TEXT NOT NULL DEFAULT 'local',  -- 4 内置 + 自定义字符串：
                                                  --   local（站站停）
                                                  --   express（大站快车）
                                                  --   section（区间车）
                                                  --   limited（特快）
                                                  --   <custom>（admin/owner 自定义）
    cars INTEGER,                     -- 编组节数（如 6 / 8 / null=未指定）
    start_station_id TEXT,            -- 起始站（区间车非首站；null = 线路首站）
    end_station_id TEXT,              -- 终点站（区间车非末站；null = 线路末站）
    notes TEXT,                       -- "末班车" / "节假日加开" 等备注（可空）
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (line_id, run_number),
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE CASCADE,
    FOREIGN KEY (start_station_id) REFERENCES rail_stations(id) ON DELETE SET NULL,
    FOREIGN KEY (end_station_id) REFERENCES rail_stations(id) ON DELETE SET NULL
);

-- ★车次时刻表（每车次到每站的到/发时间明细，精确到秒）
CREATE TABLE rail_timetable (
    run_id TEXT NOT NULL,
    station_id TEXT NOT NULL,
    arrival_time TEXT,                -- HH:mm:ss 到站时间（首站可空）
    departure_time TEXT,              -- HH:mm:ss 发车时间（末站可空）
    stops_here INTEGER NOT NULL DEFAULT 1,  -- 0 = 大站快车跳过此站
    PRIMARY KEY (run_id, station_id),
    FOREIGN KEY (run_id) REFERENCES rail_runs(id) ON DELETE CASCADE,
    FOREIGN KEY (station_id) REFERENCES rail_stations(id) ON DELETE CASCADE
);
CREATE INDEX idx_rail_timetable_run ON rail_timetable(run_id);
CREATE INDEX idx_rail_timetable_station ON rail_timetable(station_id);

-- wall 绑定到某线某站某方向
CREATE TABLE wall_rail_bindings (
    wall_id TEXT PRIMARY KEY,
    line_id TEXT,                     -- null = 未绑定（fallback 到 0.4.0 ManualSchedule）
    station_id TEXT,
    direction TEXT,                   -- "up" / "down" / "both"
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE,
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE SET NULL,
    FOREIGN KEY (station_id) REFERENCES rail_stations(id) ON DELETE SET NULL
);
```

### 18.3 计算逻辑

`RailScheduleProvider`（接替 / 补充 `ManualScheduleProvider`）：

对每个绑定的 wall（wall_rail_bindings 含 line + station + direction）：
1. 查 `rail_timetable WHERE station_id = ? AND stops_here = 1`，按 arrival_time 排序
2. 取前两班 ≥ 当前时刻（or 过零点回首班）
3. JOIN `rail_runs` 拿 run_number / service_type / cars / end_station_id / notes
4. JOIN `rail_stations` 拿 terminus name
5. 暴露到 `schedule:<wallId>/*` 变量（兼容 0.4.0 schedule namespace）

**关键**：每站时刻**精确从 rail_timetable 读**，不再走"travel_seconds 均匀推算"。
这样支持站间不均匀（如长区间 vs 短区间）+ 大站快车跳站 + 区间车不到全线。

### 18.4 暴露变量（含车次语义）

兼容 0.4.0 已有的 `next_*` / `next2_*` 字段，新增车次语义字段：

| 变量 | 例子 | 备注 |
|---|---|---|
| `schedule:<wallId>/next_run_number` | "A01" | 车次号 |
| `schedule:<wallId>/next_service_type` | "express" / "local" / "section" | 服务类型枚举值 |
| `schedule:<wallId>/next_service_type_text` | "大站快车" / "站站停" | i18n 友好显示文本（按 owner locale） |
| `schedule:<wallId>/next_cars` | "6" / "" | 编组（null → 空字符串） |
| `schedule:<wallId>/next_terminus` | "郑州东" | 终点站名（区间车显示中间终点） |
| `schedule:<wallId>/next_notes` | "末班车" / "" | 备注（null → 空字符串） |
| `schedule:<wallId>/next_arrival` | "06:02:30" | 该站精确到达时刻（从 timetable 读，非估算） |
| `schedule:<wallId>/next_departure` | "06:03:00" | 该站精确发车时刻（兼容 0.4.0 含义微调）|
| `schedule:<wallId>/next_eta_*` | 同 0.4.0 | 兼容旧 wall 引用 |
| `next2_*` 同上 | 第二班 |
| 旧 `is_arriving / arrival_status / precision` | 保留 | 兼容 |

wall 文本示例：
```
下一班 ${var:schedule.next_run_number} 次 → ${var:schedule.next_terminus}
（${var:schedule.next_cars} 节 ${var:schedule.next_service_type_text}）
ETA ${var:schedule.next_eta_mmss}
${var:schedule.next_notes}
```

### 18.5 UI

#### 新 modal：铁路网络管理（TopBar 火车图标二级菜单）

```
┌─ 铁路网络 ──────────────────────────────────────────┐
│ ▼ 线路列表                                            │
│   ├ 1 号线（红色 L1）[✏][🗑][+ 站点][+ 车次]            │
│   │  站点：                                          │
│   │  ├ 郑州火车站 (ZHF, sort=0, 终点)                 │
│   │  ├ 二七广场 (EQ, sort=1)                          │
│   │  └ ...                                           │
│   │  车次：                                          │
│   │  ├ A01 ◊ up ◊ 大站快车 ◊ 6 节 [✏][🗑]            │
│   │  ├ A02 ◊ up ◊ 站站停 ◊ 8 节                       │
│   │  └ B01 ◊ down ◊ 区间车 (郑州→紫荆)                │
│   └ 2 号线（蓝色 L2）                                 │
└──────────────────────────────────────────────────────┘
```

#### 车次详情编辑（modal 内子页 / inline 展开）

```
车次 A01 ◊ 1 号线 ◊ up 方向
├ [服务类型 ▼]  大站快车 (express)        ← 4 内置 + admin/owner 自定义
├ [编组]        6 节
├ [区间起点]    郑州火车站 ▼              ← null = 线路首站（默认）
├ [区间终点]    紫荆山 ▼                  ← null = 线路末站
├ [备注]        末班车
│
└ 时刻表（按 station sort_order）：
    郑州火车站   ── / 06:00:00 (发)        ← 首站无到达
    二七广场     06:02:30 / 06:03:00
    紫荆山       [☐ stops_here]            ← 大站快车跳站（uncheck）
    民航路       06:08:00 / 06:08:30
    郑州东       06:15:00 / ──    (到)    ← 末站（区间终点）
```

**创建车次时弹"自动生成对话框"**（用户已决策）：
- [首站发车时间] 06:00:00
- [站间均匀时长] 90 秒
- [停靠时长] 30 秒
- [跳过站点] [☐][☐][☑][☐][☐] 勾选大站快车跳过的站
- [区间起 / 止] 起 / 止站选择
- → 生成所有 timetable rows，**预览后可逐站调整**

#### Schedule Manager modal 改造（保持现状 + 加铁路绑定）

顶部加 **"铁路网络绑定"** 段：
- ☐ 启用铁路网络模式
- [线路] 1 号线 ▼
- [本站] 郑州火车站 ▼
- [方向] up / down / both
- 启用后下方 entries 灰显（自动从 timetable 计算）；关闭则走 0.4.0 ManualSchedule

### 18.6 ACL / 权限节点

- `canvas.rail.line.create` (default: true) — 任何玩家可建线路
- `canvas.rail.line.edit.own` (default: true) — owner 可改
- `canvas.rail.line.edit.any` (default: op) — admin
- `canvas.rail.line.delete.own / .any` (default: true / op)
- `canvas.rail.run.edit.own / .any`  — 车次同款 ACL（继承线路 owner）
- `canvas.rail.wall.bind` (default: true) — wall 绑定（wall 自身鉴权同 schedule）

### 18.7 WS 协议（新 11 个 op）

```
rail.line.create        { name, code?, color? }
rail.line.update        { lineId, name?, code?, color? }
rail.line.delete        { lineId }

rail.station.add        { lineId, name, code?, sortOrder, isTerminus? }
rail.station.update     { stationId, name?, code?, sortOrder?, isTerminus? }
rail.station.delete     { stationId }

rail.run.create         { lineId, runNumber, direction, serviceType, cars?,
                          startStationId?, endStationId?, notes?,
                          generateOptions?: { firstDeparture, travelSeconds,
                                              dwellSeconds, skipStationIds? } }
                          ← generateOptions 非空 = 自动生成 timetable
rail.run.update         { runId, runNumber?, serviceType?, cars?, ... }
rail.run.delete         { runId }
rail.run.timetable.set  { runId, entries: [{ stationId, arrival?, departure?, stopsHere }] }
                          ← 批量更新该车次的所有 timetable rows

rail.wall.bind          { wallId, lineId?, stationId?, direction }
```

### 18.8 实施 Phase（~60h）

| Phase | 范围 | 工时 |
|---|---|---:|
| **P1** | V016 5 表 migration + 5 DAO（LineDao / StationDao / RunDao / TimetableDao / BindingDao）+ record + Auto-generator helper（首站时间 + 站间秒 + 跳站集合 → timetable rows） | 12h |
| **P2** | RailScheduleProvider 计算（按 timetable 精确查 + 兼容旧 ManualSchedule fallback） | 10h |
| **P3** | 11 个 WS op + 6 个 `canvas.rail.*` 权限节点 + RailOpDispatcher + AuditLog | 8h |
| **P4** | 前端铁路网络管理 modal（线路 + 站点 + 车次 + 时刻表 + 自动生成对话框 + 拖动排序） | 16h |
| **P5** | Schedule Manager modal 加铁路绑定段 + 车次语义变量预览 + i18n + 单测 + docs | 8h |
| **P6** | 收尾 + 版本号 0.4.3 → 0.4.4-SNAPSHOT + journal + push | 6h |
| **总** | 单 milestone 多 commit | **60h** |

wall-clock 估 **~1.5-2.5 周**。

### 18.9 兼容性

- 0.4.0 ManualScheduleProvider **不删**——`wall_rail_bindings.line_id IS NULL` 的 wall 仍走旧路径
- 旧 `schedule:<wallId>/next_departure` 等变量**保留**——RailScheduleProvider 也会写这些 key
  让既有 wall 文本不需要改写
- 新增 `next_run_number / next_service_type / next_cars / next_terminus / next_notes` 等**追加**变量

### 18.10 服务类型 i18n

`service_type` 字段存内部 enum 值（local / express / section / limited 或 custom 字符串）。
`next_service_type_text` 变量按 owner locale 查 i18n：

| enum | 中文 | English |
|---|---|---|
| local | 站站停 | Local |
| express | 大站快车 | Express |
| section | 区间车 | Section |
| limited | 特快 | Limited |
| `<custom>` | 原样输出 | as-is |

### 18.11 单测覆盖

- 5 DAO 各 5+ case（CRUD + FK CASCADE + 排序）
- Auto-generator 8+ case（首末站时间 / 跳站 / 区间车起止 / 边界）
- RailScheduleProvider 计算 10+ case（按时刻 + 方向 + 大站快车跳站 + 区间车 + 过零点）
- RailOpDispatcher 11 op + 权限拒 + 自动生成路径
- 前端 RailNetworkManagerModal vitest 关键交互（线路 CRUD / 车次创建 + 时刻表编辑）

至少 50 个新 case。

---

## 19. 0.4.x 路线图速览（2026-05-21）

| 版本 | 范围 | 工时 | 状态 |
|---|---|---:|---|
| 0.4.0 | 变量系统底座 + Provider + Plugin API + 命令族 | 150h | ✅ |
| 0.4.1 | chip 编辑器（Lexical / Notion 风格） | 25h | ✅ |
| 0.4.2 | 变量别名（per-wall） + Picker 表格 | 10h | ✅ |
| **0.4.3** | **全局用户变量**（userglobal namespace） | **13h** | ✅ |
| **0.4.4** | **铁路网络**（线路 + 站点 + 车次 + 时刻表 + 服务类型）| **60h** | ✅ |
| 0.4.5–0.4.9 | 打磨 / 体验 / ultrareview / Live Paint 收尾 | — | ✅ |
| 0.4.10 | 修补批 + 设计哲学固化 | ~40h | ✅ |
| 0.5.0 | 纯服务端性能 Benchmark（不测网络，见 §13.3 + PROPOSAL §2.1/§5.2.7 + docs/benchmark.md） | ~150h | ✅ |
| 0.6.0 | 时间轴编辑器（AE-like，设计总纲 `docs/timeline.md`，摘要见 §13.4） | ~360h | 远期 |
| 0.7.0 | Scratch-like 视觉运行时（见 §13.5） | ~360h | 远期 |

> 路线图以 `CLAUDE.md` 速览表为准；本表为 dynamic-data 内部参考。原"0.5.0 动画 / 0.6.0 Blockly"已于 2026-05-25 重排（见 §13 重订说明）。
