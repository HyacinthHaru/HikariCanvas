# Plugin Push API

让你的插件把自定义数据推给 HikariCanvas，显示在游戏内的招牌上。你推一个值，玩家在招牌文本里写 `${var:你的命名空间/键}` 就能引用它——值变了，招牌实时跟着变。

典型用法：比分牌、列车到站倒计时、商店库存、在线人数、任务进度……任何你算得出、想让玩家在墙上看到的动态数据。

## 工作方式

```
你的插件 → api.setVariable(...) → HikariCanvas 存值 → 引用它的招牌自动重画
```

整条链路是单向的（你只管推，HikariCanvas 负责显示），而且：

- **可在任意线程调**——内部线程安全，不用 `runTask` 包裹。
- **错误不会牵连你**——你推非法值或内部出错，HikariCanvas 自己 log 并丢弃，不影响你的插件。
- **未装 HikariCanvas 也能跑**——拿不到 API 时优雅跳过即可（见下文）。

## 快速开始

下面是一个能跑的最小插件：进服时把当前玩家名推成 MVP 显示。

### 1. 声明依赖

`paper-plugin.yml`：

```yaml
name: MyPlugin
version: '1.0.0'
main: com.example.MyPlugin
api-version: '1.21'

dependencies:
  server:
    HikariCanvas:
      load: BEFORE       # 让 HikariCanvas 先于你启动
      required: false    # false=可独立装；true=强依赖（HikariCanvas 没装则你也不启动）
      join-classpath: true
```

`load: BEFORE` 保证 HikariCanvas 在你的 `onEnable` 之前完成启动，这样你一定拿得到 API 实例。

构建时把 API 类符号引进来（编译期可见，运行期由 HikariCanvas 主插件提供），`build.gradle.kts`：

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // 把 HikariCanvas 的 jar 放到 libs/ 引用其 API 包符号：
    compileOnly(files("libs/HikariCanvas-0.7.3-SNAPSHOT.jar"))
}
```

> 你只用到 `moe.hikari.canvas.api.*` 这一个包。运行期这些类由已安装的 HikariCanvas 提供，所以是 `compileOnly`，不要打进你自己的 jar。

### 2. 拿 API、注册、推值

```java
package com.example;

import moe.hikari.canvas.api.HikariCanvasAPI;
import moe.hikari.canvas.api.NamespaceInfo;
import moe.hikari.canvas.api.VarType;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin implements Listener {

    private static final String NS = "myplugin";   // 你的命名空间
    private HikariCanvasAPI canvas;

    @Override
    public void onEnable() {
        // 1. 拿 API（拿不到说明 HikariCanvas 没装/没启动）
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas == null) {
            getLogger().info("HikariCanvas 未安装，动态显示功能关闭");
            return;
        }

        // 2. 注册命名空间（onEnable 调一次，幂等）
        canvas.registerNamespace(this, NS, new NamespaceInfo(
                "我的插件数据",                   // 编辑器里显示的名字
                getName(),                       // 插件名
                getDescription().getVersion())); // 插件版本

        // 3. 声明 key（让玩家在编辑器变量选择器里看得到、能自动补全）
        canvas.declareKey(this, NS, "mvp", VarType.STRING, "当前 MVP 玩家名");

        getServer().getPluginManager().registerEvents(this, this);
    }

    // 4. 在事件里推值
    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        if (canvas == null) return;
        canvas.setVariable(this, NS, "mvp", ev.getPlayer().getName(), null);
    }
}
```

玩家在招牌的文本框里写：

```
本场 MVP：${var:myplugin/mvp}
```

招牌就会实时显示当前 MVP。无需在 `onDisable` 做任何清理——见[生命周期](#生命周期)。

## 获取 API

推荐用 Bukkit 的 ServicesManager，它只依赖 `moe.hikari.canvas.api.*` 包，编译耦合最小：

```java
HikariCanvasAPI api = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
if (api == null) {
    // HikariCanvas 未装 / 未启动 → 优雅降级
    return;
}
```

::: tip 为什么不用 `getAPI()`
HikariCanvas 主类上也有 `((HikariCanvas) plugin).getAPI()`，但那要求你 `import moe.hikari.canvas.HikariCanvas` 主类，未来主类重构会破坏你的编译。ServicesManager 路径只碰公开 API 包，跨版本更稳。
:::

## API 参考

所有方法第一个参数都是你的 `Plugin` 实例（即 `this`）——HikariCanvas 用它做权限校验和卸载时的自动清理。

| 方法 | 作用 |
|---|---|
| `registerNamespace` | 注册你的命名空间，`onEnable` 调一次 |
| `declareKey` | 声明一个 key，让它出现在编辑器的变量选择器里 |
| `setVariable` | 推送一个变量的当前值 |
| `setVariables` | 一次批量推多个值（比循环单推高效） |
| `unsetVariable` | 删掉一个变量 |

### registerNamespace

```java
void registerNamespace(Plugin plugin, String namespace, NamespaceInfo info);
```

注册你的命名空间。同一插件用同名重复调是幂等的（覆盖更新 `info`）。

| 参数 | 类型 | 说明 |
|---|---|---|
| `plugin` | `Plugin` | 你的插件实例（`this`） |
| `namespace` | `String` | 匹配 `[a-zA-Z_][a-zA-Z0-9_]{0,31}`：首字符为字母或 `_`，总长 ≤ 32，**不含** `:` 和 `-` |
| `info` | `NamespaceInfo` | 元信息 record，三个字段都非空 |

`NamespaceInfo` 的构造：

```java
new NamespaceInfo(
    "我的插件数据",  // displayName：编辑器选择器里显示
    getName(),      // pluginName：约定填 plugin.getName()
    "1.0.0");       // version：你的插件版本，无格式约束
```

**抛出**：

- `IllegalArgumentException`——命名空间格式非法，或撞了[保留命名空间](#命名空间规则)。
- `NamespaceConflictException`——这个命名空间已被**另一个**插件注册（可读 `.namespace()` / `.existingPluginName()`）。

### declareKey

```java
void declareKey(Plugin plugin, String namespace, String key, VarType type, @Nullable String hint);
```

声明一个 key。这**只是让它在编辑器的变量选择器里可见、可自动补全，并不写值**——哪怕你一次都没 `setVariable`，玩家也能在 `${` 弹出的列表里看到它。实际值要靠 `setVariable` 推。

| 参数 | 类型 | 说明 |
|---|---|---|
| `plugin` | `Plugin` | 必须和 `registerNamespace` 时是同一个 |
| `namespace` | `String` | 必须先 `registerNamespace` |
| `key` | `String` | 匹配 `[a-zA-Z0-9_.-]+`，≤ 64 字符（可用 `.` 分组，如 `line1.eta_minutes`） |
| `type` | `VarType` | `STRING` / `NUMBER` / `BOOLEAN` / `COLOR`，决定编辑器给玩家什么输入控件 |
| `hint` | `String?` | 选择器里显示的人类可读说明，可为 `null` |

同 key 重复声明会更新 `type` 和 `hint`。

**抛出**：`PluginNamespaceException`——命名空间没注册（`NAMESPACE_NOT_REGISTERED`）或不属于你（`NAMESPACE_ACL_DENIED`）。

### setVariable

```java
void setVariable(Plugin plugin, String namespace, String key, String value, @Nullable Duration ttl);
```

推送一个变量的当前值。变量不存在时自动创建（类型取之前 `declareKey` 声明的，没声明默认 `STRING`）。

| 参数 | 类型 | 说明 |
|---|---|---|
| `plugin` | `Plugin` | 你的插件实例 |
| `namespace` | `String` | 已注册的命名空间 |
| `key` | `String` | `[a-zA-Z0-9_.-]+`，≤ 64 字符 |
| `value` | `String` | 字符串值，**非 null**（空串合法），≤ 4096 字符 |
| `ttl` | `Duration?` | 存活时长：`null`=沿用原 TTL；`Duration.ZERO`=永久；`>0` 必须 ≥ 100ms |

值都是字符串——数值、布尔也用字符串存（`String.valueOf(score)`），业务语义由你保证。

`ttl` 是"这个值多久后算过期"，不是"多久推一次"。过期后招牌走 [fallback](#错误处理)。如果你定时推，把 TTL 设成推送间隔的 2~3 倍，避免偶尔一次推送失败就让值过早过期。

```java
// 5 分钟内不再更新就算过期
canvas.setVariable(this, "myplugin", "red_score", "5", Duration.ofMinutes(5));

// 永久，直到下次覆盖
canvas.setVariable(this, "myplugin", "mvp", "Notch", Duration.ZERO);

// 沿用变量原有的 TTL
canvas.setVariable(this, "myplugin", "red_score", "6", null);
```

**抛出**：仅 `PluginNamespaceException`（ACL 相关）。其他问题静默丢弃，见[错误处理](#错误处理)。

### setVariables

```java
void setVariables(Plugin plugin, String namespace, Map<String, VariableUpdate> updates);
```

一次推多个变量。比循环调 `setVariable` 更高效：只做一次权限校验，受影响的招牌只合并标记一次。

```java
import moe.hikari.canvas.api.VariableUpdate;
import java.util.Map;
import java.time.Duration;

canvas.setVariables(this, "myplugin", Map.of(
    "red_score",  new VariableUpdate("5", Duration.ofMinutes(5)),
    "blue_score", new VariableUpdate("3", Duration.ofMinutes(5)),
    "mvp",        new VariableUpdate("Notch", null)   // ttl 可为 null
));
```

`VariableUpdate` 是 `(String value, @Nullable Duration ttl)`，语义同 `setVariable` 的对应参数。空 map 合法（什么都不做）。限流按条数计——10 条 update 占 10 个配额。

**抛出**：同 `setVariable` 的 ACL 异常。

### unsetVariable

```java
void unsetVariable(Plugin plugin, String namespace, String key);
```

删掉一个变量（不删命名空间本身）。之后引用它的招牌走 [fallback](#错误处理)。变量本来就不存在时静默返回，不报错。

```java
canvas.unsetVariable(this, "myplugin", "mvp");
```

**抛出**：同上的 ACL 异常。

## 命名空间规则

- **格式**：`[a-zA-Z_][a-zA-Z0-9_]{0,31}`——首字符字母或 `_`，总长 ≤ 32，**不能含** `:` 或 `-`（这两个字符 HikariCanvas 内部留用）。
- **小写更友好**：`myplugin` ✓。`MyPlugin` 也合法但不推荐。
- **加前缀防撞名**：和别的插件作者撞名时建议带组织前缀，如 `hyacinth_bedwars`。
- **一个插件管自己的命名空间**：别去推别人注册的命名空间，会被 ACL 拒绝。

下列命名空间是 HikariCanvas 内部保留的，**注册会抛 `IllegalArgumentException`**：

| 命名空间 | 用途 |
|---|---|
| `user` | 玩家在某面墙上自定义的变量 |
| `userglobal` | 玩家自定义、跨墙共享的全局变量 |
| `system` | 系统变量（`server.time` / `wall.alias` 等） |
| `papi` | PlaceholderAPI 桥接 |
| `scoreboard` | Bukkit 记分板 |
| `schedule` | 内置列车时刻表 |

想做"全服共享"的数据，**用你自己的命名空间**（如 `bedwars/*`），不要去抢 `userglobal`。

## 限流

防止 bug 插件刷爆，HikariCanvas 对 push 限流。默认值（服主可在 `config.yml` 的 `dynamic.push-rate-limit` 段改）：

| 限制 | 默认值 |
|---|---|
| 单插件每秒 | 100 |
| 全局每秒 | 1000 |
| 全局熔断时长 | 10 秒 |

触限时：单插件超额的推送被丢弃（每秒最多记一条 WARN，避免刷屏）；全局超额触发 10 秒保护期，期间所有 push 被拒。**限流不抛异常，调用方无感知**——只在日志里有 WARN。需要严格成功保证就自己控频率/加重试。

实战上很难触限：

- **事件型推送**（玩家击杀、商品购买）天生低频，几乎不会触。
- **定时推送**用合理间隔（每 5s / 30s），别每 tick 推。
- **值没变就别推**，省配额也少触发招牌重画。

## 生命周期

**你不需要在 `onDisable` 做任何清理。** HikariCanvas 监听 `PluginDisableEvent`，你的插件卸载时自动：

1. **立即**释放你的命名空间（其他插件可重新注册）；
2. **立即**停止把你的 key 暴露给编辑器选择器；
3. **30 秒后**才清掉你推过的变量值。

那 30 秒延迟是给 reload 窗口的——插件 disable→enable 之间通常很短，这期间招牌还显示着旧值，不会闪屏。你重新 enable 后再推一次即可。

几点要记住：

- **重启/reload 后值是空的。** 插件、系统、PAPI、记分板这些命名空间的变量都不持久化。你的插件 `onEnable` 重新 `registerNamespace` + `declareKey` + 推第一次，就恢复了。（只有玩家的 `user/*` 变量持久化到数据库。）
- **HikariCanvas 重新启用要重新初始化。** 如果它在你之后重启，可监听 `PluginEnableEvent` 重新拿 API：

```java
@EventHandler
public void onPluginEnable(PluginEnableEvent ev) {
    if ("HikariCanvas".equals(ev.getPlugin().getName())) {
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas != null) {
            // 重新 registerNamespace + declareKey + push 第一次
        }
    }
}
```

## 错误处理

**抛异常的情况**（都是 `RuntimeException`，不强制 `throws`，但你该 catch 决定降级还是 fail-fast）：

| 异常 | 何时抛 | 怎么办 |
|---|---|---|
| `IllegalArgumentException` | 命名空间格式非法 / 撞保留名 / 参数为 null | 改代码，别 catch 忽略 |
| `NamespaceConflictException` | 命名空间被别的插件占了 | 换个名（建议带前缀） |
| `PluginNamespaceException` (`NAMESPACE_NOT_REGISTERED`) | 没注册就 `setVariable` / `declareKey` | `onEnable` 先 `registerNamespace` |
| `PluginNamespaceException` (`NAMESPACE_ACL_DENIED`) | 推了别人的命名空间 | 用自己的 |

**静默丢弃的情况**（不抛异常，只在 HikariCanvas 日志里留痕，不影响你）：

- 命中限流（WARN）；
- `value` 超 4096 字符（WARN，丢这次写）；
- `key` 格式非法（WARN，丢这次写）；
- `ttl` 小于 100ms（自动抬到 100ms）；
- HikariCanvas 内部未知异常（SEVERE，丢这次写，但它自己不受影响）。

> `value == null` 仍会抛 `NullPointerException`——这是你的代码 bug，自己校验。

当变量缺失或过期，引用它的招牌按 fallback 链取值：缓存值 → `${var:键|fallback=...}` 里写的兜底 → 变量默认值 → 最后实在没有就显示 `???`（让玩家一眼看出变量配错了）。

推荐的防御写法：

```java
public void onScoreChanged(int newScore) {
    if (canvas == null) return;   // HikariCanvas 没装
    try {
        canvas.setVariable(this, NS, "red_score",
                String.valueOf(newScore), Duration.ofMinutes(5));
    } catch (RuntimeException e) {
        // 实际只有 ACL 异常会到这；其他问题已被静默处理
        getLogger().warning("推送变量失败：" + e.getMessage());
    }
}
```

## 下一步

- 看两个能跑的完整示例：[示例插件](/api/examples)
