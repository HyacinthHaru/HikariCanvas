# HikariCanvas Plugin Push API

> 让外部插件（铁路 / PvP / 商店 / ...）实时向 HikariCanvas wall 推送动态变量。
> 引入版本：**0.4.0**（M28-P4）；接口稳定性：**实验性**（pre-1.0，详见 §10）。

---

## 1. 概览

`HikariCanvasAPI` 是 HikariCanvas 0.4.0 引入的"动态接入"接口，让外部插件可以：

- 注册一个 **namespace**（如 `bedwars` / `railway`）
- 声明可用变量 **key**（编辑器 Variable Picker 会自动补全）
- 推送变量当前值（HikariCanvas 渲染时自动替换 `${var:bedwars/red_score}` 等占位符）
- 玩家编辑器中拖拽 `${var:...}` 占位符到 wall 文本 → wall 自动跟随变量值实时刷新

**数据流**：

```
你的插件 → HikariCanvasAPI.setVariable(...)
         → PushRateLimiter（per-plugin 100/s + 全局 1000/s）
         → VariableStore（线程安全 mirror）
         → wall dirty marker（倒排索引）
         → ProjectionThrottler tick（200ms 合并）
         → wall canvas 重画 + 投影
```

整个过程：

- **异步**：不在 server tick 主线程做重活
- **错误隔离**：你的插件抛异常 / 推非法值不影响 HikariCanvas 本身
- **限流防御**：防 bug 插件刷爆，per-plugin 100/s + 全局 1000/s + 10s 全局熔断
- **零开销 fallback**：未安装 HikariCanvas 时，你的插件可 graceful skip

---

## 2. 快速开始

### 2.1 添加依赖（Gradle）

`build.gradle.kts`：

```kotlin
repositories {
    // HikariCanvas 当前只发布到 Maven Local / GitHub Packages；
    // 如未发布，把 HikariCanvas-<version>.jar 放到 libs/ 目录引用
}

dependencies {
    compileOnly(files("libs/HikariCanvas-0.3.0-SNAPSHOT.jar"))
    // 或 compileOnly("moe.hikari:hikari-canvas:0.3.0-SNAPSHOT")
}
```

`paper-plugin.yml`：

```yaml
name: BedWarsPlugin
main: com.example.BedWarsPlugin
version: 1.0.0
api-version: '1.21'

dependencies:
  server:
    HikariCanvas:
      load: BEFORE       # 让 HikariCanvas 先启动
      required: false    # false = 你的插件可独立装；true = 强依赖
      join-classpath: true
```

> **load 顺序**：填 `BEFORE` 让 HikariCanvas 在你的 onEnable 之前先 onEnable。这样你 `Bukkit.getServicesManager().load(HikariCanvasAPI.class)` 一定能拿到非 null。

### 2.2 拿 API 实例（两种方式）

**方式 A：Bukkit ServicesManager（推荐，零编译耦合）**

```java
import moe.hikari.canvas.api.HikariCanvasAPI;
import org.bukkit.Bukkit;

HikariCanvasAPI api = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
if (api == null) {
    // HikariCanvas 未装 / 未启动 → 选择 fallback 或 disable 自身功能
    getLogger().info("HikariCanvas not found; dynamic display disabled");
    return;
}
```

**方式 B：getAPI() 方法**

```java
import moe.hikari.canvas.HikariCanvas;     // 注意：需 import 主类，编译耦合
import moe.hikari.canvas.api.HikariCanvasAPI;
import org.bukkit.Bukkit;

HikariCanvas plugin = (HikariCanvas) Bukkit.getPluginManager().getPlugin("HikariCanvas");
HikariCanvasAPI api = plugin.getAPI();
```

**优先选方式 A**：方式 B 要求你 `import moe.hikari.canvas.HikariCanvas`，未来 HikariCanvas 主类重构会破坏你的编译；方式 A 只依赖 `moe.hikari.canvas.api.*` 包，该包受 shadowJar relocate exclude 保护（M28-P4-T），路径稳定。

### 2.3 完整接入示例

```java
package com.example;

import moe.hikari.canvas.api.HikariCanvasAPI;
import moe.hikari.canvas.api.NamespaceInfo;
import moe.hikari.canvas.api.VarType;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public class BedWarsPlugin extends JavaPlugin implements Listener {

    private static final String NS = "bedwars";
    private HikariCanvasAPI canvas;

    @Override
    public void onEnable() {
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas == null) {
            getLogger().info("HikariCanvas not found; score display disabled");
            return;
        }

        // 1. 注册 namespace（每个插件 onEnable 调一次即可，幂等）
        canvas.registerNamespace(this, NS, new NamespaceInfo(
                "BedWars 比赛数据",
                "BedWarsPlugin",
                getDescription().getVersion()));

        // 2. 声明可用 key（编辑器 Variable Picker 显示）
        canvas.declareKey(this, NS, "match_a.red_score", VarType.NUMBER, "红队比分");
        canvas.declareKey(this, NS, "match_a.blue_score", VarType.NUMBER, "蓝队比分");
        canvas.declareKey(this, NS, "match_a.mvp", VarType.STRING, "MVP 玩家名");

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // 3. 事件触发推送
    @EventHandler
    public void onPlayerKill(MatchKillEvent ev) {
        if (canvas == null) return;
        canvas.setVariable(this, NS, "match_a.red_score",
                String.valueOf(ev.matchRedScore()),
                Duration.ofMinutes(5));  // TTL：5 分钟无更新视为比赛结束
    }

    @Override
    public void onDisable() {
        // 不需要手动 unregister——HikariCanvas 自动监听 PluginDisableEvent
        // cached value 保留 30s 让 wall 显示旧值不闪屏，之后才清
    }
}
```

玩家在编辑器 textarea 输入：

```
红队: ${var:bedwars/match_a.red_score} 蓝队: ${var:bedwars/match_a.blue_score}
MVP: ${var:bedwars/match_a.mvp}
```

wall 实时显示当前比分。

---

## 3. API 接口完整参考

### 3.1 `registerNamespace`

```java
void registerNamespace(Plugin plugin, String namespace, NamespaceInfo info);
```

**参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `plugin` | `Plugin` | 调用方 plugin 实例。**用于 ACL spoof 防御** + plugin disable 时自动清理 |
| `namespace` | `String` | 必须匹配 `[a-zA-Z_][a-zA-Z0-9_]{0,31}`（首字符字母或 `_`，长度 ≤ 32） |
| `info` | `NamespaceInfo` | 元信息（`displayName` / `pluginName` / `version`，全部非空） |

**抛**：

- `IllegalArgumentException`: namespace 格式非法 / 与**保留 namespace** 冲突
- `NamespaceConflictException`: namespace 已被**另一**插件注册

**幂等**：同 plugin 重复注册同 namespace OK，会覆盖 `info`，但保留首次注册的 `registeredAt`。

**示例**：

```java
canvas.registerNamespace(this, "bedwars", new NamespaceInfo(
        "BedWars 比赛数据", "BedWarsPlugin", "1.2.0"));
```

### 3.2 `declareKey`

```java
void declareKey(Plugin plugin, String namespace, String key, VarType type, @Nullable String hint);
```

**参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `plugin` | `Plugin` | ACL 校验，必须与 `registerNamespace` 时一致 |
| `namespace` | `String` | 必须先 `registerNamespace` |
| `key` | `String` | 必须匹配 `[a-zA-Z0-9_.-]+`，最长 64 字符 |
| `type` | `VarType` | `STRING` / `NUMBER` / `BOOLEAN` / `COLOR` |
| `hint` | `String?` | 编辑器 Variable Picker 显示的描述（可空） |

**抛**：

- `PluginNamespaceException(NAMESPACE_NOT_REGISTERED)`: 没先 `registerNamespace` 直接 declareKey
- `PluginNamespaceException(NAMESPACE_ACL_DENIED)`: namespace 属于其他插件

**注意**：

- **`declareKey` 不写值** —— 仅声明 key 对编辑器可见。`setVariable` 才是写值。
- 同 key 重复 declare 会覆盖 `type` 与 `hint`。
- 玩家在 textarea 输入 `${` 时，Variable Picker 从 `GET /api/variable/list-all-namespaces` 拉数据，显示分组（即使没 push 任何 value 也能列出 key）。

**示例**：

```java
canvas.declareKey(this, "bedwars", "match_a.red_score", VarType.NUMBER, "红队比分（整数）");
canvas.declareKey(this, "bedwars", "match_a.mvp", VarType.STRING, "MVP 玩家名");
```

### 3.3 `setVariable`

```java
void setVariable(Plugin plugin, String namespace, String key, String value, @Nullable Duration ttl);
```

**参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `plugin` | `Plugin` | ACL 校验 |
| `namespace` | `String` | 已 `registerNamespace` 的 namespace |
| `key` | `String` | `[a-zA-Z0-9_.-]+`，最长 64 char |
| `value` | `String` | 字符串值（非 null；空串合法）；最长 **4096** 字符 |
| `ttl` | `Duration?` | `null` = 沿用变量原 TTL；`Duration.ZERO` = 永久；`> 0` 必须 ≥ **100ms**（小于自动 clamp） |

**抛**（仅 ACL 错误抛；其他错误内部 log + drop）：

- `PluginNamespaceException(NAMESPACE_NOT_REGISTERED)`
- `PluginNamespaceException(NAMESPACE_ACL_DENIED)`

**静默 drop**（不抛异常 / 调用方无感知）：

- 限流命中（log WARN）
- `value` 长度超 4096（log WARN）
- `ttl < 100ms`（自动 clamp 到 100ms）
- 内部未知异常（log SEVERE，HikariCanvas 自身不受影响）

**返回**：`void`

**自动 create**：变量不存在时自动创建，类型取 `declareKey` 声明，未声明则默认 `STRING`。

**示例**：

```java
// 5 分钟 TTL
canvas.setVariable(this, "bedwars", "match_a.red_score", "5", Duration.ofMinutes(5));

// 永久
canvas.setVariable(this, "bedwars", "match_a.mvp", "Notch", Duration.ZERO);

// 沿用原 TTL（不传）
canvas.setVariable(this, "bedwars", "match_a.red_score", "6", null);
```

### 3.4 `setVariables`

```java
void setVariables(Plugin plugin, String namespace, Map<String, VariableUpdate> updates);
```

批量推送。**性能上优于循环调 `setVariable`**：

- 内部 dirty wall merge（一次性 mark 所有受影响 wall）
- 一次 ACL check（而非每条都校验）

**限流计费**：按 `updates.size()` 计 token（10 条 update 占用 10 个 token）。

**示例**：

```java
import moe.hikari.canvas.api.VariableUpdate;
import java.util.Map;

canvas.setVariables(this, "bedwars", Map.of(
    "match_a.red_score",  new VariableUpdate("5", Duration.ofMinutes(5)),
    "match_a.blue_score", new VariableUpdate("3", Duration.ofMinutes(5)),
    "match_a.mvp",        new VariableUpdate("Notch", null)
));
```

**抛**：同 `setVariable` 的 ACL 异常。空 map 合法（noop）。

### 3.5 `unsetVariable`

```java
void unsetVariable(Plugin plugin, String namespace, String key);
```

删除单个变量（**不删 namespace 本身**）。

引用该变量的 wall 文本走 fallback 链：cached → `${var:X|fallback=...}` → `Variable.default` → `"???"`（详见 §6.3）。

**抛**：

- ACL 异常同上

**静默**：变量不存在时静默返回（不抛 `VariableException`）。

**示例**：

```java
canvas.unsetVariable(this, "bedwars", "match_a.mvp");
```

---

## 4. namespace ACL & 保留 namespace

### 4.1 保留 namespace（外部插件禁注册）

| Namespace | 用途 |
|---|---|
| `user` / `user:<wallId>` | 玩家用户变量（per-wall） |
| `system` / `system:<wallId>` | 系统变量（`server.time` / `wall.alias` 等） |
| `papi` | PlaceholderAPI 桥接 |
| `scoreboard` | Bukkit 记分板 |
| `schedule` / `schedule:<wallId>` | 内置 Manual Schedule（兜底列车功能） |

注册保留 namespace → 抛 `IllegalArgumentException`。

> **注意**：你也不能注册带 `:` `-` 的 namespace —— 这两个字符保留给 HikariCanvas 内部 `<ns>:<wallId>` 形态。

### 4.2 ACL spoof 防御

`PluginNamespaceRegistry` 用 `ConcurrentHashMap.putIfAbsent` 做原子注册：

- 插件 A 推 plugin B 的 namespace → `PluginNamespaceException(NAMESPACE_ACL_DENIED)`
- 重复注册同 namespace 但不同 plugin → `NamespaceConflictException`
- 同 plugin 重复注册同 namespace → **幂等覆盖** `info`，保留 `registeredAt`

### 4.3 namespace 命名建议

- **小写**：`bedwars` ✓，`BedWars` 也合法但不推荐
- **简洁**：`bedwars` ✓，`my_bedwars_plugin_v2` 嫌长
- **加前缀防撞**：多插件作者同时建议 `myorg_bedwars`（如 `hyacinth_bedwars`）
- **保留 namespace**：仅五个（见 §4.1），社区可视情况扩展

---

## 5. 生命周期 & 清理

### 5.1 自动清理

`PluginDisableEvent` 触发 HikariCanvas 内部 listener：

1. **立即** unregister namespace（释放给其他插件可注册）
2. **立即** 移除该插件的 `PluginNamespaceProvider`（停止 declaredKeys 暴露给编辑器 Picker）
3. **30s 后** purge VariableStore 内该 namespace 所有变量数据

**为什么 30s 延迟？** 让 plugin reload 窗口（plugin disable → enable 之间通常 ≤ 30s）内 wall 仍显示 cached value，不闪屏。reload 完成后立刻 push 新值即可。

### 5.2 手动 unregister？

**不需要**。`onDisable` 你不需要做任何 cleanup。

但如果你想运行时清空一组变量（不卸载插件），可循环调 `unsetVariable`。

### 5.3 服务器重启

HikariCanvas 重启 / reload 后，**所有 plugin / system / PAPI / scoreboard 变量丢失**（不持久化）。你的插件 `onEnable` 重新 `registerNamespace` + `declareKey` + 第一次 push 即可恢复。

只有 `user/*` namespace（玩家用户变量）持久化到 DB，重启自动恢复。

---

## 6. 渲染 / 变量解析

### 6.1 引用语法

文本字段（如 `TextElement.text`）含占位符：

```
${var:bedwars/match_a.red_score}              完整命名
${var:user/红队比分}                          玩家用户变量
${var:server.time}                            系统变量点分号 alias（暂未完整实装，0.4.1+）
${var:system/server.time}                     系统变量 slash 形态（当前可用）
${var:papi:%player_name%}                     PAPI placeholder

# fallback 语法
${var:bedwars/score|fallback=0}               变量不存在 / 过期时显示 0
```

### 6.2 fallback 链

变量取值优先级（从高到低）：

1. **cached_value** 存在且非空 → 用 cached_value
2. cached_value 为空 / null → 用 `${var:X|fallback=...}` 语法里的 fallback
3. 无 fallback → 用 `Variable.default`
4. default 也无 → 用 `"???"`（系统兜底，让用户看出来变量配错了）

### 6.3 wall dirty 合并（性能）

```
插件 1ms 内连推 100 次（同一变量） →
  1. setVariable 调用 100 次 → 每次写 VariableStore（O(1)）
  2. 每次 mark wall dirty（O(1)）
  3. 但 ProjectionThrottler 200ms 才 tick 一次
  4. 200ms 间隔最终只重画 1 次（取最新值）
```

push 频率不影响渲染负担 —— push 是廉价的写操作，重画在 throttler 端合并。

### 6.4 线程模型

- **变量 resolve 不在 MC 主线程跑** —— `ProjectionThrottler` 直接读 cached value（O(1)）
- 你的 `setVariable` 调用可在任意线程（main / async 都行）—— `VariableStore` 内部线程安全
- 不需要 `Bukkit.getScheduler().runTask(...)` 包裹

---

## 7. 限流

### 7.1 默认限制（config.yml 可调）

```yaml
dynamic:
  push-rate-limit:
    per-plugin-per-second: 100
    global-per-second: 1000
    global-circuit-break-ms: 10000
```

### 7.2 触限行为

| 场景 | 行为 |
|---|---|
| per-plugin 超 100/s | drop tail（每秒最多 1 次 WARN log，避日志爆炸） |
| 全局超 1000/s | 触发**保护期** 10s，期间所有 push reject（log WARN）|
| 保护期结束 | 自动恢复，下次 push 正常 |

**调用方无感知**：限流不抛异常，仅 log。如果你的插件需要严格 push 成功保证，请控制 push 频率或自己加重试。

### 7.3 最佳实践

- **事件型推送**（PlayerKill / 商品购买）：低频，几乎不会触限
- **定时推送**：合理间隔（每 5s / 30s），避免每 tick push（20/s × N 个变量很快触限）
- **批量推送**：用 `setVariables` 一次性推 N 个 key（按 entry 计费但**一次 ACL check** + 一次 wall dirty 合并）
- **去重**：如果值没变就别 push（节省 limit 配额 + 减少 wall 重画概率）

---

## 8. 错误码 / 异常表

### 8.1 抛给调用方的异常

| 异常 | 何时抛 | 处理建议 |
|---|---|---|
| `IllegalArgumentException` | namespace 格式非法 / 保留 ns / null 参数 | **立即修代码**，不能 catch 忽略 |
| `NamespaceConflictException` | 重复注册不同 plugin | 选另一个 namespace 名（建议加 plugin name 前缀） |
| `PluginNamespaceException(NAMESPACE_NOT_REGISTERED)` | 未 `registerNamespace` 直接 `setVariable` / `declareKey` / `unsetVariable` | `onEnable` 先调 `registerNamespace` |
| `PluginNamespaceException(NAMESPACE_ACL_DENIED)` | 推别人的 namespace | 用自己的 namespace |

所有异常都是 `RuntimeException`，不要求声明 `throws`。

### 8.2 静默 drop（不抛异常 / 仅 log）

- 限流触发（log WARN，每秒最多 1 条）
- `ttl < 100ms`（自动 clamp 到 100ms）
- `value` 长度 > 4096（log WARN + drop 该次写）
- `key` 格式非法（log WARN + drop）
- `value == null`（NullPointerException 仍会抛——校验你的代码）
- 内部未知 Exception（log SEVERE + drop，HikariCanvas 自身不受影响）

### 8.3 防御性编程模板

```java
public void onScoreChanged(int newScore) {
    if (canvas == null) return;  // HikariCanvas 未装
    try {
        canvas.setVariable(this, NS, "red_score",
                String.valueOf(newScore),
                Duration.ofMinutes(5));
    } catch (RuntimeException e) {
        // 仅 ACL 异常会到这里；其他错误已静默
        getLogger().warning("Failed to push variable: " + e.getMessage());
    }
}
```

---

## 9. 完整示例插件

参见仓库 `examples/` 目录：

- **`examples/demo-train-plugin/`** — 定时器范型（每 5s 推 ETA）
- **`examples/demo-score-plugin/`** — 事件 + 命令范型

每个示例附 README + 完整 build.gradle.kts + paper-plugin.yml。

---

## 10. FAQ

### Q: 我的插件不依赖 HikariCanvas 也能装吗？

A: 用 §2.2 方式 A（`ServicesManager.load` 返 null 时 graceful 处理）。`paper-plugin.yml` 写 `required: false`。这样 HikariCanvas 没装时你的插件正常启动，只是失去动态显示功能。

### Q: 重启服务器后我的变量值是什么？

A: 插件 / 系统 / PAPI / scoreboard namespace 的变量**不持久化**，重启后空。等你的插件下一次 push 才有值。这是 Push 模式的自然属性（详见 `docs/dynamic-data.md §8.2`）。

引用方在 push 完成前走 fallback 链显示占位。

### Q: 我能让玩家在编辑器看到我声明的 key 吗？

A: 是。`declareKey(...)` 把 key 加入 namespace 的 declared 列表，前端 Variable Picker 从 `GET /api/variable/list-all-namespaces` 拉取后按 namespace 分组显示（即使还没 push 任何 value）。`hint` 字段会显示在 key 旁边作说明。

### Q: TTL 怎么选？

A:

| 更新频率 | 建议 TTL |
|---|---|
| 高频（每秒 / sub-second） | `30s` |
| 中频（每分钟） | `5min` |
| 低频（玩家事件触发） | `Duration.ZERO`（永久，直到下次更新） |
| 一次性公告 | `Duration.ofHours(1)` 之类 |

**TTL 不是"几时该 push"**，而是"过期后 cached 失效，render 走 fallback"。如果你能保证定时推，TTL 设为 push 间隔的 2-3 倍即可（避免一次 push 失败 + 下次还没到时 cached 误过期）。

### Q: 不同 plugin 的 namespace 能同名 key 吗？

A: 是。`bedwars/red_score` 和 `railway/red_score` 完全独立（不同 namespace）。同 namespace 内同 key 后写覆盖前写。

### Q: 我能查询某个变量当前值吗？

A: **0.4.0 暂不提供** `getVariable` 方法 —— HikariCanvas 设计为单向 Push（你推 → HC 显示），双向同步会引入 race condition + 缓存一致性问题。

如果你需要业务侧也用变量值，请在你自己的插件里维护一份镜像（push 时同时写自己的内存）。

### Q: 我能让 wall 显示的变量值带颜色 / 格式化吗？

A: `0.4.0` 的 `${var:...}` 占位符仅做**纯字符串替换**。颜色和格式化由 `TextElement` 本身的 fill / font 控制，整个 element 同一样式。

未来 `0.5.0+` 计划引入 chip 编辑器 + 格式化语法（`${var:X|format=int|suffix=min}` 等），届时支持 per-variable 样式。

### Q: 我可以用 PAPI placeholder 而非自己写 plugin 吗？

A: 可以。任何 `${var:papi:%placeholder%}` 会自动桥接到 PlaceholderAPI（前提是服务器装了 PAPI）。详见 `docs/dynamic-data.md §7.2`。

但如果你的数据来源是自己的插件，写 Push API 比期望玩家装 PAPI + 你的 PAPI 扩展更可靠（少一层依赖 + 编辑器自动补全更友好）。

### Q: 推送频率太高会被封吗？

A: 不会"封"，但会被限流（详见 §7）。日志会有 WARN，调用方静默。你不会被踢出 server / 失去注册资格，调整频率即可。

### Q: 我能在 async 线程调 setVariable 吗？

A: 可以。`VariableStore` 线程安全。事实上推荐 async 调（不阻塞 server tick）。

### Q: HikariCanvas 重启 / reload 期间我的 push 会怎样？

A: 如果 HikariCanvas plugin 处于 disabled 状态，`Bukkit.getServicesManager().load(...)` 返 null，你的 push 不会发生（如果你按 §8.3 模板做 null check）。

HC enable 完成后，你需要重新 `registerNamespace` + push 第一次。**建议**监听 `PluginEnableEvent` 检测 HikariCanvas 重新启用并重新初始化。

```java
@EventHandler
public void onPluginEnable(PluginEnableEvent ev) {
    if ("HikariCanvas".equals(ev.getPlugin().getName())) {
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas != null) initRegistration();
    }
}
```

---

## 11. 升级 / 向后兼容承诺

- **pre-1.0**（`0.x.y`，含当前 `0.3.0-SNAPSHOT`）：API 可能小破坏，会在 CHANGELOG / journal.md 通告
- **1.0 起**：API 接口冻结，新方法只追加不修改；现有方法签名 / 行为保持向后兼容
- **包路径**：`moe.hikari.canvas.api.*` 是公开 SPI 包，受 shadowJar relocate `exclude` 保护（M28-P4-T），跨 HC 版本路径稳定
- **VarType enum**：新增类型走追加（不重排顺序 / 不删除现有）

如有破坏性变更，**总在 minor 版本号上调**（如 `0.4.x` → `0.5.0`）+ journal.md 突出标注。

---

## 12. 参考文档

- `docs/dynamic-data.md` —— 0.4.0 完整动态数据设计（§4 是本 API 的设计源）
- `docs/protocol.md` —— WebSocket 协议（编辑器与后端的通讯，与本 API 无直接关系）
- `docs/architecture.md §13` —— 动态画板架构纪律（P-1 / P-2 / P-3 三种路径）
- `docs/security.md` —— 权限节点 + 审计日志
- `examples/demo-train-plugin/` / `examples/demo-score-plugin/` —— 可运行示例插件

---

## 13. 反馈 & 贡献

- 仓库：https://github.com/HyacinthHaru/HikariCanvas
- Issue：欢迎提 bug / feature request
- API 设计讨论：在 `docs/dynamic-data.md` 上下文里讨论，避免直接改 `api.md`（本文件追随 dynamic-data.md 演进）
