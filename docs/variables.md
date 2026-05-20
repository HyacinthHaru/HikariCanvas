# HikariCanvas 变量系统使用指南

> HikariCanvas 0.4.0 "动态信息屏" 完整指南。覆盖玩家入门、运维管理、端到端测试。
>
> 配套文档：
> - `docs/dynamic-data.md`（设计 / 协议 / 数据模型契约）
> - `docs/api.md`（第三方插件接入 SDK）

## 目录

- [第一部分：玩家入门](#第一部分玩家入门)
  - [1.1 什么是变量](#11-什么是变量)
  - [1.2 创建你的第一个变量](#12-创建你的第一个变量)
  - [1.3 在 wall 上使用变量](#13-在-wall-上使用变量)
  - [1.4 NUMBER 类快捷操作](#14-number-类快捷操作)
  - [1.5 VariablePicker 自动补全](#15-variablepicker-自动补全)
  - [1.6 系统自动变量](#16-系统自动变量)
  - [1.7 PlaceholderAPI 集成](#17-placeholderapi-集成)
  - [1.8 Scoreboard 动态变量](#18-scoreboard-动态变量)
  - [1.9 列车时刻表（Manual Schedule）](#19-列车时刻表manual-schedule)
  - [1.10 fallback 语法](#110-fallback-语法)
  - [1.11 变量被删除后？](#111-变量被删除后)
- [第二部分：运维管理](#第二部分运维管理)
  - [2.1 /canvas var 命令族](#21-canvas-var-命令族)
  - [2.2 config.yml 限流配置](#22-configyml-限流配置)
  - [2.3 第三方插件接入](#23-第三方插件接入)
  - [2.4 监控 + 故障排查](#24-监控--故障排查)
  - [2.5 备份 + 恢复](#25-备份--恢复)
  - [2.6 重载流程](#26-重载流程)
- [第三部分：完整测试 checklist](#第三部分完整测试-checklist)
  - [3.1 自动单测](#31-自动单测)
  - [3.2 本地 dev server 端到端](#32-本地-dev-server-端到端)
  - [3.3 测试 checklist 31 步](#33-测试-checklist-31-步)
  - [3.4 压力 / 异常测试](#34-压力--异常测试)
  - [3.5 SQL 完整性检查](#35-sql-完整性检查)

---

## 第一部分：玩家入门

### 1.1 什么是变量

HikariCanvas 0.4.0 之前的招牌是 **静态** 的：保存后内容不会变。0.4.0 引入 **变量系统**，让招牌变成 **动态信息屏**——文字可以根据服务器状态、玩家分数、列车 ETA 等数据自动刷新。

核心机制：在 TextElement 文本里写 **占位符** `${var:NAME}`，渲染时 HikariCanvas 把占位符替换成变量当前值。

变量按 **来源**（namespace）分四层，覆盖几乎所有用例：

| 数据源 | namespace | 示例 | 谁负责更新 |
|---|---|---|---|
| 玩家变量 | `user:<wallId>` | `user/red_score` | 玩家手动 / 插件 push |
| 插件变量 | 插件自定义（如 `bedwars`） | `bedwars/red_score` | 第三方插件 push |
| 系统变量 | `system` / `wall` | `system.server.time` / `wall.alias` | HikariCanvas 自维护 |
| PAPI 桥接 | `papi` | `papi/pct_player_name_pct` | PlaceholderAPI 实时算 |
| Scoreboard | `scoreboard` | `scoreboard.points.Steve` | Bukkit Scoreboard API |
| Schedule | `schedule:<wallId>` | `schedule.next_departure` | HikariCanvas 自维护（用户配的时刻表） |

> 详细架构 / 协议见 `docs/dynamic-data.md`。

### 1.2 创建你的第一个变量

打开 wall 编辑器，TopBar 找到 **Variable**（变量）图标：

1. 点 Variable 图标 → 右侧弹出 **VariablePanel**（380px 抽屉）
2. 点抽屉顶部 **[+ 新建]** 按钮 → 弹出 **NewVariableDialog**
3. 输入 name（如 `red_score`），name 实时校验：合法 `[a-zA-Z0-9_.-]+`
4. 选 type：**STRING** / **NUMBER** / **BOOLEAN** / **COLOR**
5. 配 default value（按 type 不同显示不同控件，NUMBER 默认 0，COLOR 默认 `#FFFFFF`）
6. 点 **提交** → 变量进库 + 在 VariablePanel 内出现

新建变量自动归到 **User**（本 wall）分组，namespace = `user:<wallId>`。

### 1.3 在 wall 上使用变量

随便加一个 TextElement，在 textarea 写：

```
红队比分: ${var:user/red_score}
```

立即看到 wall 上文字被替换成变量当前值。改变量值 → wall 内文字 200ms 内 live preview。

> 200ms 是输入 debounce 上限，最终落到 wall 渲染只算一次性能（详见 `dynamic-data.md §10.2`）。

### 1.4 NUMBER 类快捷操作

NUMBER 类型变量在 VariablePanel 里有两组按钮：`-1` / `+1`。操作：

| 操作 | 效果 |
|---|---|
| 单击 `+1` | 当前值 +1 |
| 单击 `-1` | 当前值 -1 |
| **长按 300ms 后** `+1` | 每 50ms 自动累加 1（连续按住直到松开） |
| 手动改值 | 点变量行的值 → 弹 inline editor |

长按累加是 `useLongPressIncrement` composable 实现——300ms 长按门槛 + 50ms 重复间隔 + `pointercancel` / `blur` / `onBeforeUnmount` 全栈清理；不会卡按住状态。

跨设备 sync：所有变更走 WS `variable.set` op → 服务端写库 + state.patch 广播 → 同一 wall 的其他在编辑客户端实时同步。

### 1.5 VariablePicker 自动补全

**0.4.1 起 TextElement 文本框升级为 chip 编辑器**（Notion 风格）：占位符 `${var:X}` 不再显示字面字符串，而是渲染成 Catppuccin Mauve 的胶囊 chip——hover 看当前值 / 来源 / 删除告警，click 弹 picker 改绑定。详见 §1.5.1。

在 chip 编辑器内输入 `${`，**自动弹出 VariablePicker popover**——列出全部已知变量（按 namespace 分组）+ 搜索框。

操作：

- **键盘 ↑↓** 切换候选
- **Enter** 插入（自动补全 `${var:NAME}` 后并把光标移到 `}` 后）
- **Esc** 关闭
- **左侧按钮触发** 也能手动召唤（不用打 `${`）
- **画布内双击文本** 也进 chip 编辑器（inline 形态）；`${` 触发同样有效（0.4.1-P3.5 起）

Picker 拉取的元数据来源：`GET /api/variable/list-all-namespaces`，包含 user / system / wall / scoreboard / papi / schedule / 插件 namespace 全部 declared keys。

#### 1.5.1 chip 编辑器交互（0.4.1）

| 行为 | 触发 | 结果 |
|---|---|---|
| 看变量当前值 | hover chip | 浮 tooltip：原始占位符 / 当前值 / 来源（user / system / plugin / papi） |
| 改绑定 | 点击普通 chip（紫色） | 弹 VariablePicker，选新变量后整 chip 替换 |
| 补创缺失变量 | 点击红色 chip（变量已删除 / 不存在） | 确认对话框「是否立即创建？」→ 自动新建空字符串 user 变量；非 user 域提示「请通过对应 Provider 注册」 |
| 整体删除 chip | 光标停在 chip 边界按 Backspace / Delete | chip 一次性整体消失（不是拆字符） |
| 粘贴升级 | 粘贴含 `${var:X}` 字面的 plain text | 自动识别并升级为 chip（无需手动重输 `${`） |
| 复制 chip | 选中 chip + Ctrl/Cmd+C | clipboard 拿到字面占位符 `${var:X}`，粘贴到任何外部应用都是字符串 |
| 字号联动 | 切 element.fontSize | chip 比例自动 clamp 在 0.6×~1.2× 之间（防极大 / 极小字号失控） |

chip 视觉：

- 默认（latte）：Mauve 紫色 pill + ⚡ 前缀
- 暗色（frappe / macchiato）：相同 Mauve 但提高填充比例补偿色亮度衰减
- 错误态（变量缺失）：destructive red 红色 + 删除线 + ⚠ 前缀

技术实现走 `lexical` 0.44 core + `DecoratorNode` 自包装（不引 `lexical-vue` 编译产物），bundle 拆 `lexical` 独立 chunk（~155 kB / gzip ~50 kB）；main bundle 保持 ~655 kB。

### 1.6 系统自动变量

无需创建，HikariCanvas 启动就有 13 个 system / wall 变量可用：

**全局（namespace = `system`）：**

| 引用 | 类型 | 描述 | 刷新间隔 |
|---|---|---|---|
| `${var:server.time}` | STRING | 当前服务器本地时间 `HH:mm` | 60s |
| `${var:server.real_time}` | STRING | 完整 ISO 时间戳 | 60s |
| `${var:server.tick}` | NUMBER | Bukkit tick 计数 | 1s |
| `${var:server.online}` | NUMBER | 当前在线人数 | 30s |
| `${var:server.online_list}` | STRING | 在线玩家名（逗号分隔） | 30s |
| `${var:server.motd}` | STRING | 服务器 MOTD | 1h |
| `${var:server.tps}` | NUMBER | Paper TPS 1min 平均 | 30s |
| `${var:server.name}` | STRING | 服务器名 | 1h |

**per-wall（namespace = `wall`）：**

| 引用 | 描述 |
|---|---|
| `${var:wall.id}` | wall 短 ID（`w-<8hex>`） |
| `${var:wall.alias}` | wall 玩家命名（可能为空） |
| `${var:wall.owner}` | wall 创建者玩家名 |
| `${var:wall.owner_uuid}` | wall 创建者 UUID |

引用 system / wall 变量时 **可省 namespace 前缀**——`${var:server.time}` 等同于 `${var:system/server.time}`。HikariCanvas 内部按 `.` 分隔自动识别。

### 1.7 PlaceholderAPI 集成

装上 [PlaceholderAPI](https://wiki.placeholderapi.com/) 后自动启用——HikariCanvas 启动时反射检测 PAPI 类，存在则注册 `papi` namespace 的动态 Provider。

引用语法：

```
${var:papi/pct_player_name_pct}
${var:papi/pct_server_online_pct}
${var:papi/pct_<expansion>_<key>_pct}
```

> **为什么写 `pct_xxx_pct` 而不是 `%xxx%`？**
>
> PAPI 原生占位符语法 `%player_name%` 里的 `%` 会被 HikariCanvas 占位符解析器误判为变量边界。所以 PAPI 桥接做了 **编码层**：将 PAPI 占位符的 `%` 换为 `pct_` / `_pct`，引用时写编码形式，HikariCanvas 内部 resolve 时 decode 回 `%xxx%` 再喂给 PAPI。

不装 PAPI 的服务器：`${var:papi/xxx}` 走 fallback 链（`???` 或自定义 `|fallback=...`）。

### 1.8 Scoreboard 动态变量

任何 Bukkit Scoreboard 都能引用——HikariCanvas 自动检测 + 注册。

先建 objective：

```
/scoreboard objectives add points dummy
/scoreboard players set Steve points 42
```

然后在 wall 写：

```
分数: ${var:scoreboard.points.Steve}
```

第一次引用时 HikariCanvas 触发 `dynamicLookupHook` 把 `scoreboard.points.Steve` 注册进 store + 加入 ScoreboardProvider 的刷新清单（10s 一次扫 Scoreboard）。第一次渲染走 fallback `???`，10s 后值就出现。

> `scoreboard` namespace 是 **动态** 的（`isDynamic() = true`）——declared keys 列表是空（无穷大），按需注册。

### 1.9 列车时刻表（Manual Schedule）

HikariCanvas 内置 **手动时刻表** 工具，给 server 没装专门列车 / 公交插件做兜底。**0.4.0 bugfix** 后支持每 wall 独立的 **分钟 / 秒精度** + 4 个新变量（`eta_seconds` / `arrival_status` / `precision` 等）。

操作：

1. TopBar 点 **Train**（列车）图标 → 弹 **Schedule Manager** modal
2. 设站名（终点）
3. **新**：选时间精度（分钟 / 秒）
   - 分钟（默认）：entry 用 `HH:mm`，刷新 30s 一次
   - 秒：entry 用 `HH:mm:ss`（或 `HH:mm` 自动补 `:00`），刷新 1s 一次
4. 配若干 entry
5. 关闭 modal → 自动注册 7 个 `schedule:<wallId>` 变量

引用：

| 变量 | 类型 | 描述 |
|---|---|---|
| `${var:schedule.next_departure}` | STRING | 下一班发车时间 `HH:mm` 或 `HH:mm:ss`（按 wall 精度） |
| `${var:schedule.next_destination}` | STRING | 下一班终点 |
| `${var:schedule.eta_minutes}` | NUMBER | 距下一班还有多少分钟（向下兼容） |
| `${var:schedule.eta_seconds}` | NUMBER | **0.4.0 bugfix**：距下一班还有多少秒（秒精度主用） |
| `${var:schedule.is_arriving}` | BOOLEAN | eta ≤ 阈值（默认 60s）→ `true` / `false` |
| `${var:schedule.arrival_status}` | STRING | **0.4.0 bugfix**：进站中文案 / 空闲文案（config 可改） |
| `${var:schedule.precision}` | STRING | **0.4.0 bugfix**：wall 当前精度 `minute` / `second` |

**配置**（`config.yml`）：

```yaml
dynamic:
  schedule:
    arriving-threshold-seconds: 60   # is_arriving / arrival_status 阈值（秒）
    arriving-text: "进站中"           # arrival_status 进站文案
    idle-text: ""                    # arrival_status 空闲文案
```

> per-wall：每个 wall 一组独立时刻表。`wall.id` 自动注入到 fullName 内（namespace = `schedule:w-xxxxx`）。
> precision 字段由 V013 migration 引入，现有 wall 默认 `minute`，无数据丢失。

### 1.10 fallback 语法

变量值缺失时（变量被删 / TTL 过期 / Provider 还没拉到第一次数据），HikariCanvas 按 **4 档兜底链** 解析：

```
${var:NAME}                     无 fallback → 走默认链
${var:NAME|fallback=N/A}        显式 fallback → 当 currentValue / defaultValue 都缺时用 "N/A"
```

完整链（按优先级）：

| 档 | 条件 | 输出 |
|---|---|---|
| 1 | `Variable.currentValue` 非 null | `currentValue` |
| 2 | `currentValue` 为 null 但 `defaultValue` 非 null | `defaultValue` |
| 3 | 上面两个都空，但占位符里写了 `\|fallback=X` | `X` |
| 4 | 全空 / 变量不存在 | `???`（系统兜底） |

例子：

```
分数: ${var:bedwars/red_score|fallback=未开始}
人数: ${var:server.online}（无 fallback；缺失走 ???）
```

### 1.11 变量被删除后？

VariablePanel 里删变量 → wall 内引用该变量的位置 **不会自动改文字**，但渲染时走 fallback `???`（除非占位符里写了 `|fallback=...`）。

编辑器额外提示（三层）：

1. **chip 编辑器** 内：被删变量对应的 chip 转为 **红色 + 删除线 + ⚠ 前缀**，点击红 chip 弹「是否立即创建？」确认对话框 → 一键补创 user 变量（0.4.1-P3.4 起）
2. **TextElement live preview** 下方标红 **banner**——警告 "Variable X was deleted, references will render as ???"
3. hover 红 chip 显 tooltip："变量已删除，最终渲染为 '???'"

> **删除不级联** 是按 `dynamic-data.md §16-5` 决策：避免变量误删导致 wall 整段文字消失。改 fallback 处理代替自动删 element。

### 1.12 变量别名（0.4.2）

变量的"内部 fullName"（如 `user:w-3a17/red_score` / `schedule:w-3a17/eta_seconds` / `system/server.time`）对玩家不友好；尤其混用多个 namespace 时屏幕信息密度大、可读性差。0.4.2 引入**别名**机制：

- **所有 namespace 都可加别名**：user / schedule / system / papi / scoreboard / 第三方插件均支持
- **per-wall**：同一变量在不同 wall 可起不同别名（个性化），互不干扰；同一 wall 内一个变量只能有一个别名
- **仅 UI 显示用**：别名不参与 `${var:...}` 解析——文本中仍写 `${var:user/red_score}`，渲染时按原 fullName 取值；别名只在 picker / panel / chip 上替代展示原名
- **chip 显示优先级**：alias > currentValue > fallback > defaultValue > UNRESOLVED（数值会变，别名稳定，更适合 chip）

#### 怎么加别名

**方式 A：新建变量时一并起**

VariablePanel 「+ 新建变量」对话框最下面有可选 **别名** 字段（留空 = 不起）。创建成功后两步串联：先 `variable.create`，再 `variable.alias.set`。

**方式 B：在 VariablePanel 里改**

每个变量行末尾有 🏷 标签按钮 → 点击 → 行内变成 input + 保存/清空/取消三按钮。提交即时生效（别人打开同 wall 也立刻看到）。

**方式 C：在 VariablePicker 里改**

文本框打 `${` 触发 picker。picker 改成 3 列表格：

| 别名 | 数值 | 变量名 |
|---|---|---|
| 红队 | 5 | user/red_score |
| ETA秒 | 90 | schedule/eta_seconds |
| — | 14:35 | system/server.time |

每行末尾有 ✏ 按钮 → 整行替换为 inline 编辑器。**特别有用**：编辑文本时直接给陌生变量起一个易记名，不用回 VariablePanel。

#### 怎么搜

VariablePicker / VariablePanel 的搜索框现在**既匹配 fullName 又匹配别名**——给 `system/server.time` 起名 "服务器时间" 后，搜 "服务器" 也能命中。

#### 协议层

3 个 WS op（详见 `docs/protocol.md §5.13`）：

- `variable.alias.set {fullName, alias}` — 起 / 改名
- `variable.alias.clear {fullName}` — 删除别名
- `variable.alias.list` — 查当前 wall 所有别名

权限：复用 `canvas.var.write.own/any`（owner 默认放行，非 owner 需要 any）。list 是只读，任何能 `open` 该 wall 的玩家可调。

state.patch 推 `/aliases/<encoded fullName>`（与变量通道同款 JSON Pointer 编码）；前端 `VariableAliasStore` 自动 mirror。

#### 数据库 schema

```sql
CREATE TABLE variable_aliases (
    wall_id     TEXT    NOT NULL,
    full_name   TEXT    NOT NULL,
    alias       TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (wall_id, full_name),
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);
```

- alias 长度 ≤ 64 字符（dispatcher 层校验）
- 不要求别名 wall 内唯一（用户自己负责，UI 不报错）
- wall 删除时 FK CASCADE 清；同时业务侧显式调 `deleteByWall` 兜底

---

## 第二部分：运维管理

### 2.1 /canvas var 命令族

权限：`canvas.var.command`（默认 op）。

7 子命令完整 reference：

#### `list [namespace]`

无参 → 列全部 namespace + 每个的变量数量：

```
$ /canvas var list
Variable namespaces (4 ns / 17 total):
  system · 8 var(s)
  wall · 4 var(s)
  schedule:w-1a2b3c4d · 4 var(s)
  user:w-1a2b3c4d · 1 var(s)
```

有 ns 参 → 列该 namespace 所有 fullName + currentValue + ttl：

```
$ /canvas var list system
Namespace 'system' (8 variable(s)):
  system/server.time [STRING] = 14:23 ttl=60s
  system/server.online [NUMBER] = 3 ttl=30s
  ...
```

#### `get <fullName>`

显示完整元信息：

```
$ /canvas var get user:w-1a2b3c4d/red_score
Variable user:w-1a2b3c4d/red_score
  namespace: user:w-1a2b3c4d
  key:       red_score
  type:      NUMBER
  default:   0
  current:   42
  updated:   2026-05-19 14:23:55
  ttl:       永久
  source:    (none)
  refByWalls:1
```

#### `set <fullName> <value...>`

手动改当前值（`args[2..]` 拼接 → 支持带空格 value）：

```
$ /canvas var set user:w-1a2b3c4d/red_score 999
✓ Set user:w-1a2b3c4d/red_score = 999
```

audit 日志事件：`VARIABLE_COMMAND_SET`（与 WS op `VARIABLE_SET` 区分，便于运维事后追溯来源）。

#### `delete <fullName>`

删除变量：

```
$ /canvas var delete bedwars/old_key
✓ Deleted bedwars/old_key
```

audit 日志事件：`VARIABLE_COMMAND_DELETE`。

> user 变量同时从 DB（`user_variables` 表）删；插件 / 系统变量仅 in-memory。

#### `providers`

列已注册 Provider：

```
$ /canvas var providers
Registered providers (6):
  system 'システム変数' [static] keys=12 interval=1s
  scoreboard 'Scoreboard' [dynamic] keys=0 interval=10s
  papi 'PlaceholderAPI' [dynamic] keys=0 interval=30s
  schedule:w-1a2b3c4d '列车时刻表' [static] keys=4 interval=60s
  bedwars 'BedWars' [static] keys=0 interval=ZERO
  ...
```

#### `reload`

重读 `config.yml` → 重建 `PushRateLimiter` → 热替换到 `HikariCanvasAPIImpl`：

```
$ /canvas var reload
✓ Config reloaded. PushRateLimiter:
  per-plugin:           100/s
  global:               1000/s
  circuit-break:        10000ms
```

> 仅 `dynamic.push-rate-limit` 段会立即生效——其他段（host / port / DB）仍需重启。`/canvas reload config` 命令仍有效作整体 reload；`/canvas var reload` 是 push 限流专项。

#### `inspect <wallId>`

倒排索引可视化：

```
$ /canvas var inspect w-1a2b3c4d
Wall w-1a2b3c4d references 3 variable(s):
  system/server.time = 14:23
  user:w-1a2b3c4d/red_score = 42
  papi/pct_player_name_pct = (null)
```

显示该 wall 当前 **引用** 的变量集合（来自 Compositor 上次渲染 `markWallReferences`），帮助排查 "为什么这个变量不更新"。

### 2.2 config.yml 限流配置

```yaml
# config.yml
dynamic:
  push-rate-limit:
    # 单插件每秒 push 上限 — 防一个 plugin bug 把 store 灌爆
    per-plugin-per-second: 100
    # 全局每秒 push 上限 — 总闸
    global-per-second: 1000
    # 全局触限后保护期（ms）— 期间所有 push reject
    global-circuit-break-ms: 10000
```

调整后 `/canvas var reload` 立即生效。

详细行为：`docs/dynamic-data.md §10.2`。

### 2.3 第三方插件接入

第三方插件用 `HikariCanvasAPI` 推变量到 HikariCanvas，详见 **`docs/api.md`**（660 行完整接入教程，含 BedWars / Scoreboard 两份 example plugin 代码 + Gradle 配置 + ServicesManager 推荐入口 + ACL / 限流 / 生命周期细节）。

### 2.4 监控 + 故障排查

| 日志模式 | 含义 | 处理 |
|---|---|---|
| `[HikariCanvas] plugin 'X' push rate exceeded` | 单插件超 100/s | 看 plugin 是不是 push 过频，调推送间隔 |
| `[HikariCanvas] global push rate exceeded` | 全局超 1000/s | 多插件同时密集 push；可在 config 调高限额 |
| `[HikariCanvas] PAPI invoke failed for ...` | PAPI placeholder 报错 | 检查 PAPI 自己 / 对应 expansion |
| `[HikariCanvas] plugin 'X' disabled; unregistered N namespaces (cached values retained for 30s)` | plugin 卸载触发清理 | 正常行为 |
| `[HikariCanvas] purged N/N orphan namespace(s) after 30s grace period` | 30s grace 后清理完成 | 正常行为 |
| `[HikariCanvas] setVariable rejected: QUOTA_EXCEEDED` | 单插件变量超 1000 个 | 检查 plugin 是不是漏 unset；考虑 setVariables 批量 |
| `[HikariCanvas] setVariable unexpected error` | 未知异常 + stack trace | 看具体 trace；可能 HikariCanvas bug，报 issue |
| `[AUDIT FALLBACK]` | DB 写 audit 失败 fallback 到 log | DB 大概锁住了；查 SQLite busy |

### 2.5 备份 + 恢复

变量数据持久化在 `plugins/HikariCanvas/data.db`（SQLite）。**仅 user 变量** 持久化（插件 / 系统 / PAPI 都是内存态）。

涉及的表：

- `user_variables` — `wall_id / name / type / default_value / current_value / bound_to / created_at / updated_at`
- `wall_schedules` — 时刻表 per-wall 配置（站名等）
- `schedule_entries` — 时刻表 entry（HH:mm / 终点）

备份（停服或在 server 内 `save-all + save-off`）：

```bash
sqlite3 plugins/HikariCanvas/data.db ".backup '/path/to/backup-$(date +%Y%m%d).db'"
```

或简单 cp：

```bash
cp plugins/HikariCanvas/data.db /path/to/backup-$(date +%Y%m%d).db
```

恢复：停服 → 把备份文件覆盖回去 → 启动。schema 迁移（V010 / V011 / V012）会自动跑。

> **0.x 阶段** schema 可能向前不兼容；启动期日志会提示。生产环境推荐每周一备。

### 2.6 重载流程

| 改了什么 | 怎么生效 |
|---|---|
| `config.yml` 限流参数 | `/canvas var reload` |
| `config.yml` 其他参数（host / port / 池容量等） | `/canvas reload config` + 部分需重启 |
| user 变量 / schedule 数据 | 实时（state.patch 广播） |
| `paper-plugin.yml` 权限定义 | 重启 server |
| i18n / 前端代码 | 重 vite build + 玩家重连 |
| 第三方插件代码 | 触发 PluginDisableEvent → 30s grace → reload plugin |

---

## 第三部分：完整测试 checklist

### 3.1 自动单测

```bash
# 后端
./gradlew :plugin:test
# 当前总数：~700+ test

# 前端
cd web && npm run test
# 当前总数：93+ test
```

### 3.2 本地 dev server 端到端

```bash
./gradlew :plugin:runServer
# 端口：默认 7878（编辑器 URL）+ 25565（MC）
# 客户端连 localhost:25565；浏览器开 /canvas confirm 给的 URL
```

### 3.3 测试 checklist 31 步

按以下表逐项验证。`#` 列对应顺序，`步骤` 是操作描述，`验证范围` 是涉及的 milestone phase，`预期` 是观察结果。

| # | 步骤 | 验证范围 | 预期 |
|--:|---|---|---|
| 1 | wand 创建 wall | M1-M2 | wall 出现 |
| 2 | `/canvas open <id>` | M3+ | 浏览器编辑器加载 |
| 3 | TopBar 看到 Variable + Train 按钮 | P2-G + P3-L | 两图标可见 |
| 4 | 点 Variable → 弹 380px drawer | P2-G | drawer 出现 |
| 5 | 创建 user 变量 "red"（NUMBER, 默认 0） | P1+P2 | VariablePanel 出现 |
| 6 | 单击 `[+1]` 5 次 | P2-G | 值 5 |
| 7 | 长按 `[+1]` 1 秒 | useLongPressIncrement | 跳 ~19 |
| 8 | TextElement 写 `${var:user/red}` | P1+P2 interpolator | live preview 显数字 |
| 9 | textarea 输入 `${` | P2-H 双触发 | Picker 自动弹 |
| 10 | Picker 看到 system / wall / scoreboard / papi / schedule | P3-M list-all-namespaces | 全显示 |
| 11 | 用 `${var:server.time}` | P3-J SystemProvider | wall 显 `HH:mm` |
| 12 | 用 `${var:wall.alias}` | P3-J per-wall | wall 显 alias |
| 13 | `/scoreboard objectives add points dummy` + `${var:scoreboard.points.<你>}` | P3-J scoreboard 混合注册 | 10s 后出现 |
| 14 | `/scoreboard players set <你> points 42` | Bukkit | wall 10s 内更新 42 |
| 15 | 装 PAPI + `${var:papi/pct_player_name_pct}` | P3-K reflection | 显玩家名 |
| 16 | TopBar Train → 配 2 条时刻表 + 站名 | P3-L | modal 关闭后 schedule.* 变量自动出现 |
| 17 | `${var:schedule.next_departure}` / `eta_minutes` | P3-L | 实时倒数 |
| 18 | `./gradlew :examples:demo-train-plugin:jar` + 复制到 plugins/ + `/reload confirm` | P4 + R | console 看 "registered namespace 'demo_train'" |
| 19 | `${var:demo_train/line1.eta_minutes}` | P4 ServicesManager | 5s 更新一次 |
| 20 | `/demoscore add red 3` | P4 命令 | demo_score/red 显 3 |
| 21 | `/demoscore reset` | P4 | 归零 |
| 22 | 玩家 join → demo_score/mvp 自动设 | P4 事件 | wall mvp 更新 |
| 23 | `/canvas var list` | P5 命令 | 输出全部 namespace |
| 24 | `/canvas var get user:<wallId>/red` | P5 | 完整元信息 |
| 25 | `/canvas var set demo_score/red 999` | P5 + AUDIT | wall 显 999 + 日志 |
| 26 | `/canvas var providers` | P5 | 列 6+ provider |
| 27 | `/canvas var inspect <wallId>` | P5 倒排 | 列引用变量 |
| 28 | `/canvas var reload` | P5 | 限流配置重读 |
| 29 | 删除 user/red → wall 显 ??? | P2-G + P3 fallback | banner 红警告 + ??? |
| 30 | 切到另一 wall | P2-I ready hookup | variables store reset |
| 31 | reload DemoTrain plugin | P4 PluginCleanupListener | "30s grace + purge" 日志 |

### 3.4 压力 / 异常测试

- **限流触发**：临时改 DemoTrain 让它每 tick push → 看 WARN 日志（per-plugin 限流触发）
- **PAPI 卸载** → `papi/*` namespace 自动消失（PluginCleanupListener）
- **多插件同时高频 push** → 全局 1000/s 触限 → circuit break 10s
- **插件 crash + 重新 enable** → 30s 内 namespace 可被新插件抢用？
- **删 wall** → user 变量 cascade DELETE；schedule 表 cascade DELETE

### 3.5 SQL 完整性检查

停服后跑：

```bash
sqlite3 plugins/HikariCanvas/data.db "SELECT * FROM user_variables;"
sqlite3 plugins/HikariCanvas/data.db "SELECT * FROM wall_schedules;"
sqlite3 plugins/HikariCanvas/data.db "SELECT * FROM schedule_entries;"
```

预期：

- ✓ user 变量持久化（重启后值还在）
- ✓ 插件 / 系统变量不持久化（重启后空）
- ✓ delete wall 后对应行 cascade 删

---

## 相关文档

- [`PROPOSAL.md`](../PROPOSAL.md) — 项目立项总纲
- [`docs/dynamic-data.md`](dynamic-data.md) — 变量系统设计 / 协议契约
- [`docs/api.md`](api.md) — 第三方插件接入 SDK
- [`docs/architecture.md`](architecture.md) — 系统架构
- [`docs/protocol.md`](protocol.md) — WS 协议 v2
- [`docs/data-model.md`](data-model.md) — SQLite / PDC 格式

变更日志 / 实施细节倒序见 [`docs/journal.md`](journal.md)。
