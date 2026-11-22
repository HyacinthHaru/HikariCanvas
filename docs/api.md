# HikariCanvas 外部插件接入 API

`HikariCanvasAPI` 允许其他 Paper 插件（小游戏、经济、铁道车控、领地等）向招牌实时推送动态变量。玩家只需在网页编辑器里插入对应的 `${var:命名空间/键名}` 占位符，游戏内的招牌就会随你的插件数据自动刷新。

> **接口稳定性承诺**：`ac.haru.hikaricanvas.api.*` 在 `1.x` 大版本周期内保持向后兼容（详见 [第 11 节](#11-稳定性与向后兼容承诺)）。底层架构与协议细节见 [动态数据接入设计](dynamic-data.md)。

---

## 1. 工作机制概览

外部插件接入只需要做三件事：
1. 在 `onEnable` 中注册属于自己插件的**命名空间**（如 `bedwars`）；
2. 声明可供玩家选择的**变量键名与说明**（方便玩家在网页编辑器的变量选择器里直接搜索点选）；
3. 在数据变化或定时任务中调用 `setVariable` / `setVariables` 推送最新字符串值。

**内部流转路径**：

```text
你的插件调用 HikariCanvasAPI.setVariable(...)
  → 推送速率限流器（单插件 100 次/秒，全局 1000 次/秒）
  → 内存变量仓库（线程安全，O(1) 写入）
  → 标记引用了该变量的画板为待重绘
  → 投影节流器合并刷新（约 200ms 窗口内多次修改只重画一次）
  → 后台渲染并向周围玩家推送地图画面
```

整条链路具备四个特点：
- **不卡主线程**：你可以在主线程或任意异步线程调用 `setVariable`，重绘均在后台线程合并完成；
- **故障隔离**：参数超长或超频会被安全拦截并打印警告，不会拖崩 HikariCanvas 或你的插件；
- **命名空间防冒充**：每个命名空间与注册它的 `Plugin` 实例绑定，其他插件无法篡改你的变量；
- **软依赖友好**：服务器未安装 HikariCanvas 时，你的插件可以正常启动并跳过推送逻辑。

---

## 2. 快速上手

### 2.1 配置 Gradle 依赖与 `paper-plugin.yml`

`build.gradle.kts`：

```kotlin
dependencies {
    // 将下载的 HikariCanvas jar 放入项目的 libs/ 目录仅作编译期引用（切勿打包进你的插件 jar）
    compileOnly(files("libs/HikariCanvas-1.0.0.jar"))
}
```

`paper-plugin.yml`：

```yaml
name: BedWarsPlugin
main: com.example.BedWarsPlugin
version: '1.0.0'
api-version: '1.21'

dependencies:
  server:
    HikariCanvas:
      load: BEFORE       # 确保 HikariCanvas 先于你的插件完成启动注册
      required: false    # false = 软依赖（未装 HikariCanvas 也能独立运行）；true = 硬依赖
      join-classpath: true
```

### 2.2 获取 API 实例

推荐通过 Bukkit 的 `ServicesManager` 获取，只依赖公开稳定的 `ac.haru.hikaricanvas.api.*` 包，不与插件主类产生编译耦合：

```java
import ac.haru.hikaricanvas.api.HikariCanvasAPI;
import org.bukkit.Bukkit;

HikariCanvasAPI canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
if (canvas == null) {
    getLogger().info("未检测到 HikariCanvas，已跳过动态招牌接入");
    return;
}
```

### 2.3 完整接入示例

```java
package com.example;

import ac.haru.hikaricanvas.api.HikariCanvasAPI;
import ac.haru.hikaricanvas.api.NamespaceInfo;
import ac.haru.hikaricanvas.api.VarType;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public final class BedWarsPlugin extends JavaPlugin implements Listener {

    private static final String NS = "bedwars";
    private HikariCanvasAPI canvas;

    @Override
    public void onEnable() {
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas == null) {
            getLogger().info("未检测到 HikariCanvas，已跳过比分屏推送");
            return;
        }

        // 1. 注册命名空间（同一插件重复调用幂等）
        canvas.registerNamespace(this, NS, new NamespaceInfo(
                "起床战争比赛数据",
                getPluginMeta().getName(),
                getPluginMeta().getVersion()));

        // 2. 声明可用变量键名（玩家在网页编辑器输入 ${ 时即可看到分组与中文说明）
        canvas.declareKey(this, NS, "match_a.red_score", VarType.NUMBER, "A 场红队比分");
        canvas.declareKey(this, NS, "match_a.blue_score", VarType.NUMBER, "A 场蓝队比分");
        canvas.declareKey(this, NS, "match_a.mvp", VarType.STRING, "本场 MVP 玩家名");

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // 3. 在业务事件或定时任务中推送最新值
    @EventHandler
    public void onScoreUpdate(MatchScoreEvent event) {
        if (canvas == null) return;
        canvas.setVariable(this, NS, "match_a.red_score",
                String.valueOf(event.redScore()),
                Duration.ofMinutes(5)); // 5 分钟未更新则自动过期走兜底值
    }

    @Override
    public void onDisable() {
        // 无需手动清理：HikariCanvas 会自动监听 PluginDisableEvent 回收命名空间，
        // 并将旧缓存值保留 30 秒缓冲，避免插件热重载期间招牌闪烁成 "???"。
    }
}
```

接入完成后，玩家在网页编辑器的文本输入框中输入：

```text
红队：${var:bedwars/match_a.red_score}  蓝队：${var:bedwars/match_a.blue_score}
全场最佳：${var:bedwars/match_a.mvp|fallback=虚位以待}
```

游戏内的招牌就会随比赛进程自动更新。

---

## 3. 接口详细参考

### 3.1 `registerNamespace` —— 注册命名空间

```java
void registerNamespace(Plugin plugin, String namespace, NamespaceInfo info);
```

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `plugin` | `Plugin` | 调用方插件实例（用于校验归属权及插件卸载时自动清理） |
| `namespace` | `String` | 命名空间标识，须匹配 `^[a-zA-Z_][a-zA-Z0-9_]{0,31}$`（字母或下划线开头，1–32 位） |
| `info` | `NamespaceInfo` | 展示名称、插件名与版本号（均不可为空） |

- **异常**：格式不合法或撞上系统保留命名空间时抛 `IllegalArgumentException`；已被其他插件占用时抛 `NamespaceConflictException`。
- **幂等性**：同一插件实例重复注册同一命名空间会更新 `info` 元信息，保留首次注册时间。

### 3.2 `declareKey` —— 声明变量元数据

```java
void declareKey(Plugin plugin, String namespace, String key, VarType type, @Nullable String hint);
```

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `plugin` | `Plugin` | 必须与注册该命名空间时的插件实例一致 |
| `namespace` | `String` | 已注册的命名空间 |
| `key` | `String` | 变量键名，须匹配 `^[a-zA-Z0-9_.-]+$`，最长 64 个字符 |
| `type` | `VarType` | `STRING`、`NUMBER`、`BOOLEAN` 或 `COLOR` |
| `hint` | `@Nullable String` | 显示在编辑器变量选择器旁的说明文字（可传 `null`） |

- **说明**：`declareKey` 只登记键名和提示信息供网页编辑器补全展示，**不会写入变量值**；即使你还没调用过 `setVariable`，玩家也能先在编辑器里选到这个键并排好版。
- **异常**：未注册命名空间或跨插件调用时抛 `PluginNamespaceException`。

### 3.3 `setVariable` —— 推送单个变量值

```java
void setVariable(Plugin plugin, String namespace, String key, String value, @Nullable Duration ttl);
```

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `plugin` | `Plugin` | 归属插件实例 |
| `namespace` | `String` | 已注册的命名空间 |
| `key` | `String` | 变量键名（未提前 `declareKey` 也可直接写入，默认按 `STRING` 处理） |
| `value` | `String` | 变量字符串值（不可为 `null`，允许空字符串 `""`，最长 **4096** 字符） |
| `ttl` | `@Nullable Duration` | 有效期：`Duration.ZERO` 表示永久有效；传 `null` 表示沿用该变量上一次的 TTL；正数最小 `100ms`（低于 `100ms` 自动按 `100ms` 计） |

- **异常与容错**：仅在命名空间未注册或越权时抛 `PluginNamespaceException`（及 `value == null` 时抛 `NullPointerException`）；触发限流、值超长 4096 字符或键名格式不合法时，内部记录警告日志并静默丢弃本次写入，不中断调用方业务。

```java
// 5 分钟有效期
canvas.setVariable(this, "bedwars", "match_a.red_score", "5", Duration.ofMinutes(5));

// 永久有效（直到下次覆盖或插件卸载）
canvas.setVariable(this, "bedwars", "match_a.mvp", "Steve", Duration.ZERO);

// 沿用上一次设定的有效期
canvas.setVariable(this, "bedwars", "match_a.red_score", "6", null);
```

### 3.4 `setVariables` —— 批量推送多个变量

```java
void setVariables(Plugin plugin, String namespace, Map<String, VariableUpdate> updates);
```

需要同时更新同一命名空间下的多个键时，优先使用 `setVariables` 而非循环调用 `setVariable`：内部只做一次权限校验，并将受影响的画板一次性合并标记重绘。限流配额按 `updates.size()` 条数扣减。

```java
import ac.haru.hikaricanvas.api.VariableUpdate;
import java.util.Map;

canvas.setVariables(this, "bedwars", Map.of(
    "match_a.red_score",  new VariableUpdate("5", Duration.ofMinutes(5)),
    "match_a.blue_score", new VariableUpdate("3", Duration.ofMinutes(5)),
    "match_a.mvp",        new VariableUpdate("Steve", null)
));
```

### 3.5 `unsetVariable` —— 移除单个变量

```java
void unsetVariable(Plugin plugin, String namespace, String key);
```

移除单个变量值（保留命名空间本身）。移除后，画板上引用该变量的文字会按兜底顺序回退（见 [6.2 节](#62-缺值兜底顺序)）；若变量原本不存在则静默返回。

---

## 4. 命名空间隔离与保留名

### 4.1 系统保留命名空间（禁止外部插件注册）

| 保留名 | 系统用途 |
| --- | --- |
| `user` | 玩家单画布私有变量（`user:<wallId>`） |
| `userglobal` | 玩家跨画布共享的全局用户变量 |
| `system` | 服务器与画板系统自动变量 |
| `papi` | PlaceholderAPI 桥接命名空间 |
| `scoreboard` | Bukkit 原版计分板桥接命名空间 |
| `schedule` | 内置列车时刻表与铁路网络命名空间 |

尝试注册以上名称会直接抛出 `IllegalArgumentException`。命名空间名称只允许英文字母、数字和下划线（不含连字符 `-` 或冒号 `:`），推荐使用简短小写名称（如 `bedwars` 或加上团队前缀 `myteam_bedwars`）。

### 4.2 跨插件隔离规则

命名空间在运行期与注册它的 `Plugin` 实例严格绑定：
- 插件 A 尝试向插件 B 注册的命名空间写值或删值 → 抛出 `PluginNamespaceException(NAMESPACE_ACL_DENIED)`；
- 插件 A 尝试抢注已被插件 B 占用的同名命名空间 → 抛出 `NamespaceConflictException`。

---

## 5. 插件生命周期与重启行为

- **插件卸载或热重载**：HikariCanvas 监听 `PluginDisableEvent`。当你的插件卸载时，命名空间会立即释放（以便重载后的新实例重新注册），但已写入的变量缓存值会**保留 30 秒宽限期**——只要你的插件在 30 秒内完成重载并推入新值，游戏里的招牌全程不会闪烁成 `???`。因此你在 `onDisable` 中不需要手动清理任何变量。
- **服务器重启**：外部插件推送的变量全部存储在内存中，**不落盘写入 SQLite**。服务器重启后，由你的插件在 `onEnable` 中重新注册命名空间并推送首帧数据即可。
- **应对 HikariCanvas 重启**：如果服主使用插件管理工具单独重载了 HikariCanvas，你可以监听 `PluginEnableEvent`，当启用插件名为 `"HikariCanvas"` 时重新获取服务实例并执行一次初始化注册：

```java
@EventHandler
public void onPluginEnable(PluginEnableEvent event) {
    if ("HikariCanvas".equals(event.getPlugin().getName())) {
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas != null) {
            initCanvasVariables();
        }
    }
}
```

---

## 6. 占位符解析与线程模型

### 6.1 编辑器引用格式

```text
${var:bedwars/match_a.red_score}          引用外部插件变量（命名空间/键名）
${var:bedwars/match_a.red_score|fallback=0}  指定数据缺失或过期时的兜底文字
```

### 6.2 缺值兜底顺序

渲染画布时按以下优先级依次取值：
1. 变量存在且未超过 TTL 有效期 → 使用最新推送值；
2. 占位符内写了 `|fallback=xxx` → 使用 `xxx`；
3. 变量自身定义了默认值 → 使用默认值；
4. 以上均无 → 显示 `"???"`。

### 6.3 刷新合并与线程安全

- **高频推送自动合并**：即使你的插件在 1 毫秒内连续更新同一变量 50 次，每次调用只是 O(1) 改写内存值并打上重绘标记；后台投影节流器会按帧率周期取最新值只重绘 1 次，不会造成渲染风暴。
- **任意线程可调**：`VariableStore` 内部完全线程安全，无需套 `Bukkit.getScheduler().runTask(...)` 切回主线程，直接在异步数据库回调或网络线程中调用 `setVariable` 反而更省主线程开销。

---

## 7. 速率限制与推送建议

`config.yml` 默认对外部插件推送设有两级限流保护（服主修改后可通过 `/canvas var reload` 热生效）：

```yaml
dynamic:
  push-rate-limit:
    per-plugin-per-second: 100     # 单插件每秒最多推送条数
    global-per-second: 1000        # 全服所有插件合计每秒上限
    global-circuit-break-ms: 10000 # 触发全局上限后的熔断保护毫秒数
```

- **超限表现**：不抛异常、不踢出注册资格，仅丢弃超出配额的写入并按每秒最多 1 条的频率打印警告日志，窗口过后自动恢复。
- **实用建议**：
  1. **值未变化时不重复推送**：在插件侧做一次旧值比对，数值变了再调 `setVariable`，既省配额又避免无谓重绘；
  2. **定时轮询选合理间隔**：倒计时、排行榜等按 1 秒、5 秒或 30 秒推送即可，不要挂在每 tick（每秒 20 次 × 多个键很容易撞上限）；
  3. **多键同更用 `setVariables`**：一次调用完成整组状态更新；
  4. **TTL 设为推送间隔的 2–3 倍**：例如每 10 秒推送一次的数据，TTL 可设为 30 秒，防止偶发一次抖动就让画面跳回 `???`。

---

## 8. 异常汇总与调用模板

### 8.1 会抛出的运行时异常

| 异常类型 | 触发原因 | 处理方式 |
| --- | --- | --- |
| `IllegalArgumentException` | 命名空间格式非法、使用了保留命名空间，或必填参数为空 | 检查并修正代码中的命名与传参 |
| `NamespaceConflictException` | 命名空间已被服内另一款插件注册 | 更换命名空间名称（建议加上插件前缀） |
| `PluginNamespaceException`（`NAMESPACE_NOT_REGISTERED`） | 尚未调用 `registerNamespace` 就直接声明键或写值 | 确保 `onEnable` 先完成命名空间注册 |
| `PluginNamespaceException`（`NAMESPACE_ACL_DENIED`） | 尝试操作其他插件注册的命名空间 | 仅操作本插件自己注册的命名空间 |

### 8.2 推荐封装写法

```java
public void pushRedScore(int score) {
    if (canvas == null) return;
    try {
        canvas.setVariable(this, NS, "match_a.red_score",
                String.valueOf(score),
                Duration.ofMinutes(5));
    } catch (RuntimeException ex) {
        getLogger().warning("推送招牌变量失败: " + ex.getMessage());
    }
}
```

---

## 9. 可运行示例工程

仓库 [`examples/`](../examples/README.md) 目录内置了两个可直接编译测试的独立 Paper 插件工程：
- **`examples/demo-train-plugin/`** —— 定时推送示例（每 5 秒计算并批量更新两条线路的发车时间与倒计时）
- **`examples/demo-score-plugin/`** —— 事件与命令驱动示例（监听玩家进服事件并通过 `/demoscore` 命令实时加减红蓝队比分）

---

## 10. 常见问题（FAQ）

- **问：为什么不提供 `getVariable` 读取接口？**
  答：`HikariCanvasAPI` 定位是纯粹的单向展示通道（业务插件产出数据 → 画布负责渲染展示）。如果允许双向读写，多个插件并发读写同一状态会引入竞态与缓存一致性问题。业务侧需要读取状态时，请直接读你自己插件内部维护的数据。
- **问：推送的字符串里能塞 `§a` 或 `<red>` 颜色代码吗？**
  答：变量占位符只做纯文本替换，字体的颜色、字号、描边和渐变统一由画布上该文本元素的样式决定。如果想让数字和前缀文字用不同颜色或字号，在画布上拆成两个独立的文本元素排在一起即可。
- **问：单插件最多能注册多少个变量键？**
  答：单个外部插件命名空间最多保存 **1000** 个不同的键（防动态拼接随机键名撑爆内存）。如果业务有房间号之类的临时键，房间销毁时记得调用 `unsetVariable` 清理。

---

## 11. 稳定性与向后兼容承诺

- **`1.x` 接口冻结**：在 `1.x` 版本线内，`HikariCanvasAPI` 现有方法签名与行为保持向后兼容，新增能力仅通过追加新方法或默认方法提供。
- **包路径固定**：`ac.haru.hikaricanvas.api.*` 在打包配置中显式排除重定位（relocate），外部插件无需担心跨小版本升级时类路径变动。
- **枚举追加兼容**：`VarType` 未来若扩充新类型，仅在末尾追加，不改变既有枚举值名称。

---

## 12. 相关文档

- [示例插件说明](../examples/README.md) —— 编译与运行两个官方接入示例
- [变量与铁路教程](variables.md) —— 玩家在编辑器中使用变量与后台 `/canvas var` 命令说明
- [动态数据接入设计](dynamic-data.md) —— 变量系统底层架构与限流设计契约
- [系统架构](architecture.md) —— 插件整体模块划分与动态画板机制
