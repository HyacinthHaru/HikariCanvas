# HikariCanvas 变量系统使用指南

> HikariCanvas "动态信息屏" 完整指南（覆盖到 0.8.1-SNAPSHOT 行为）。变量系统自 0.4.0 引入，后续 0.4.2 加别名、0.4.3 加全局用户变量、0.4.4 加铁路网络。覆盖玩家入门、运维管理、端到端测试。
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
  - [1.12 变量别名（0.4.2）](#112-变量别名042)
  - [1.13 铁路网络（0.4.4）](#113-铁路网络044)
  - [1.14 全局用户变量（0.4.3）](#114-全局用户变量043)
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
| 系统变量 | `system` / `wall` | `${var:system/server.time}` / `${var:wall.alias}` | HikariCanvas 自维护 |
| PAPI 桥接 | `papi` | `${var:papi/%player_name%}` | PlaceholderAPI 实时算 |
| Scoreboard | `scoreboard` | `${var:scoreboard.points.Steve}` | Bukkit Scoreboard API |
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
- **画布内双击文本** 也进 chip 编辑器（inline 形态）；`${` 触发同样有效（0.4.1 起）

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

无需创建，HikariCanvas 启动就有 12 个 system / wall 变量可用（全局 8 + per-wall 4）：

**全局（namespace = `system`）：**

| 引用 | 类型 | 描述 | 刷新间隔 |
|---|---|---|---|
| `${var:system/server.time}` | STRING | 当前服务器本地时间 `HH:mm` | 60s |
| `${var:system/server.real_time}` | STRING | 完整 ISO 时间戳 | 60s |
| `${var:system/server.tick}` | NUMBER | Bukkit tick 计数 | 1s |
| `${var:system/server.online}` | NUMBER | 当前在线人数 | 30s |
| `${var:system/server.online_list}` | STRING | 在线玩家名（逗号分隔） | 30s |
| `${var:system/server.motd}` | STRING | 服务器 MOTD | 1h |
| `${var:system/server.tps}` | NUMBER | Paper TPS 1min 平均 | 30s |
| `${var:system/server.name}` | STRING | 服务器名 | 1h |

**per-wall（namespace = `wall`）：**

| 引用 | 描述 |
|---|---|
| `${var:wall.id}` | wall 短 ID（`w-<8hex>`） |
| `${var:wall.alias}` | wall 玩家命名（可能为空） |
| `${var:wall.owner}` | wall 创建者玩家名 |
| `${var:wall.owner_uuid}` | wall 创建者 UUID |

> **引用语法注意**：
> - **全局 `server.*` 变量必须写完整 `${var:system/server.time}`**——裸点号 alias `${var:server.time}` **不被注入**（interpolator 只对 `user/` `wall.` `schedule.` `scoreboard.` 做 namespace 注入），裸写会 miss 走 fallback `???`。
> - **per-wall `wall.*` 变量可裸写**——`${var:wall.alias}` 被 interpolator 自动注入成内部 `system:<wallId>/wall.alias`；写 `${var:wall/alias}` 斜杠形式也等价。

### 1.7 PlaceholderAPI 集成

装上 [PlaceholderAPI](https://wiki.placeholderapi.com/) 后自动启用——HikariCanvas 启动时反射检测 PAPI 类，存在则注册 `papi` namespace 的动态 Provider。

引用语法（推荐直接写原生 `%xxx%`）：

```
${var:papi/%player_name%}
${var:papi.%player_name%}
${var:papi/%server_online%}
${var:papi/%<expansion>_<key>%}
```

> **注意：冒号形态 `${var:papi:%player_name%}` 不被接受**——桥接只识别 `papi/...` 或 `papi....`（斜杠 / 点号分隔），冒号会被当成 key 的一部分 → miss 走 fallback。
>
> **编码形式也接受**：`${var:papi/pct_player_name_pct}` / `${var:papi.pct_player_name_pct}`。桥接内部把 PAPI 占位符的 `%` 编码为 `pct_` / `_pct`（store key 校验正则不允许 `%`），resolve 时 decode 回 `%xxx%` 再喂给 PAPI。写原生 `%xxx%` 时由桥接自动编码，玩家无需关心。

不装 PAPI 的服务器：`${var:papi/%xxx%}` 走 fallback 链（`???` 或自定义 `|fallback=...`）。

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

HikariCanvas 内置 **手动时刻表** 工具，给 server 没装专门列车 / 公交插件做兜底。支持每 wall 独立的 **分钟 / 秒精度**，共暴露 15 个变量（含 `eta_seconds` / `eta_mmss` / `arrival_status` / `precision` + 第二班 `next2_*` 系列）。需要完整线路 / 站点 / 车次抽象的服主见 §1.13 铁路网络。

操作：

1. TopBar 点 **Train**（列车）图标 → 弹 **Schedule Manager** modal
2. 设站名（终点）
3. **新**：选时间精度（分钟 / 秒）
   - 分钟（默认）：entry 用 `HH:mm`，刷新 30s 一次
   - 秒：entry 用 `HH:mm:ss`（或 `HH:mm` 自动补 `:00`），刷新 1s 一次
4. 配若干 entry
5. 关闭 modal → 自动注册 15 个 `schedule:<wallId>` 变量（8 个下一班 + 7 个第二班 `next2_*`）

引用（`schedule.X` 裸点号会被 interpolator 自动注入 wallId，无需写完整 namespace）：

**下一班：**

| 变量 | 类型 | 描述 |
|---|---|---|
| `${var:schedule.next_departure}` | STRING | 下一班发车时刻 `HH:mm` 或 `HH:mm:ss`（按 wall 精度） |
| `${var:schedule.next_destination}` | STRING | 下一班终点 |
| `${var:schedule.eta_minutes}` | NUMBER | 距下一班还有多少分钟（向下兼容） |
| `${var:schedule.eta_seconds}` | NUMBER | 距下一班还有多少秒（秒精度主用） |
| `${var:schedule.eta_mmss}` | STRING | 距下一班 `MM:SS` 格式（超 99min 仍按 MM 累加） |
| `${var:schedule.is_arriving}` | BOOLEAN | eta ≤ 阈值（默认 60s）→ `true` / `false` |
| `${var:schedule.arrival_status}` | STRING | 进站中文案 / 空闲文案（config 可改） |
| `${var:schedule.precision}` | STRING | wall 当前精度 `minute` / `second` |

**第二班（`next2_*`，地铁屏标配）：**

| 变量 | 类型 | 描述 |
|---|---|---|
| `${var:schedule.next2_departure}` | STRING | 第二班发车时刻 |
| `${var:schedule.next2_destination}` | STRING | 第二班终点 |
| `${var:schedule.next2_eta_minutes}` | NUMBER | 距第二班多少分钟 |
| `${var:schedule.next2_eta_seconds}` | NUMBER | 距第二班多少秒 |
| `${var:schedule.next2_eta_mmss}` | STRING | 第二班 `MM:SS` 格式 |
| `${var:schedule.next2_is_arriving}` | BOOLEAN | 第二班 ETA ≤ 阈值 |
| `${var:schedule.next2_arrival_status}` | STRING | 第二班进站 / 空闲文案 |

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
| 1 | `Variable.currentValue` 非空 **且未过 TTL（非 stale）** | `currentValue` |
| 2 | cached 缺失 / 为空 / 已过期，但占位符里写了 `\|fallback=X` | `X` |
| 3 | 无 `\|fallback=`，但 `Variable.defaultValue` 非 null | `defaultValue` |
| 4 | 全空 / 变量不存在 | `???`（系统兜底） |

> 注意优先级：**占位符内的 `\|fallback=X` 比变量自身的 `defaultValue` 优先**（cached 缺失时先用 inline fallback，没写才退到 default）。TTL 过期（stale）的 cached 值不再被采用，等同"缺失"走后续档。

例子：

```
分数: ${var:bedwars/red_score|fallback=未开始}
人数: ${var:system/server.online}（无 fallback；缺失走 ???）
```

### 1.11 变量被删除后？

VariablePanel 里删变量 → wall 内引用该变量的位置 **不会自动改文字**，但渲染时走 fallback `???`（除非占位符里写了 `|fallback=...`）。

编辑器额外提示（三层）：

1. **chip 编辑器** 内：被删变量对应的 chip 转为 **红色 + 删除线 + ⚠ 前缀**，点击红 chip 弹「是否立即创建？」确认对话框 → 一键补创 user 变量（0.4.1 起）
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

### 1.13 铁路网络（0.4.4）

0.4.0 ManualScheduleProvider 是**纯 per-wall** — 每个 wall 独立配自己的时刻表，
100 个地铁屏 = 100 套独立配置。

0.4.4 引入**完整铁路网络抽象**：定义"线路 / 站点 / 车次 / 每站精确时刻表"一次，
N 个 wall 都绑到该网络上自动同步。

#### 入口

TopBar 火车轨道图标 → **「铁路网络」** modal。

#### 三层结构

```
线路（1 号线）
  ├ 站点（按顺序排列）
  │   郑州火车站 → 二七广场 → 紫荆山 → ...
  └ 车次（一趟具体的车）
      ├ A01 ◊ 上行 ◊ 大站快车 ◊ 6 节 [详情]
      ├ A02 ◊ 上行 ◊ 站站停 ◊ 8 节
      └ B01 ◊ 下行 ◊ 区间车（郑州→紫荆山）
```

每个车次有完整运营语义：

| 字段 | 例子 | 备注 |
|---|---|---|
| 车次号 | `A01` | 同线唯一 |
| 方向 | `上行` / `下行` | 决定站点排序 |
| **服务类型** | `站站停` / `大站快车` / `区间车` / `特快` + 自定义 | 4 内置 enum + 任意字符串 |
| 编组 | `6` 节 | 整数；可空 |
| 区间起 / 止 | 郑州 → 紫荆山 | 区间车非首末站；null = 线路首站 / 末站 |
| 备注 | "末班车" / "节假日加开" | |
| **时刻表** | 每站精确到秒的到 / 发时间 + 是否停靠 | 支持站间不均 + 大站快车跳站 |

#### 自动生成时刻表

新建车次时点 **「自动生成时刻表」**：

- 首站发车时间（HH:mm:ss）
- 站间均匀秒数（如 90s）
- 停靠秒数（如 30s）
- 跳过的站集合（大站快车）

→ 生成完整 timetable rows，**预览后可逐站手调**。

#### wall 绑定铁路

在 Schedule Manager modal 里有可折叠「铁路绑定」section（线路 / 本站 / 方向 3 列下拉，0.4.5 起），
选定后走 `rail.wall.bind` WS op；也可用 `/canvas var inspect <wallId>` 命令查看 wall 当前绑定。

绑定后 wall 的 `${var:schedule.next_*}` 自动来自 RailScheduleProvider，
取代 0.4.0 ManualScheduleProvider 的 per-wall 配置。

#### 新增 14 个铁路变量（共享 schedule namespace）

| 变量 | 例子 | 来源 |
|---|---|---|
| `schedule:<wallId>/next_run_number` | "A01" | rail_runs.run_number |
| `schedule:<wallId>/next_service_type` | "express" | enum 值 |
| `schedule:<wallId>/next_service_type_text` | "大站快车" | i18n 友好（按 owner locale） |
| `schedule:<wallId>/next_cars` | "6" | 编组 |
| `schedule:<wallId>/next_terminus` | "郑州东" | run.end_station_id 站名（区间车显区间终点） |
| `schedule:<wallId>/next_notes` | "末班车" | 备注 |
| `schedule:<wallId>/next_arrival` | "06:02:30" | timetable.arrival_time（精确读，非估算） |
| `next2_*` 同上 7 个 | 第二班 |

兼容 0.4.0 的 `next_departure / eta_minutes / eta_seconds / eta_mmss / is_arriving /
arrival_status / precision` 等保留（rail provider 也写这些 key 让旧 wall 文本无感升级）。

#### wall 文本示例

```
下一班 ${var:schedule.next_run_number} 次 → ${var:schedule.next_terminus}
${var:schedule.next_cars} 节 · ${var:schedule.next_service_type_text}
ETA ${var:schedule.eta_mmss}
${var:schedule.next_notes}
```

渲染结果：
```
下一班 A01 次 → 郑州东
6 节 · 大站快车
ETA 02:30
末班车
```

#### 权限节点

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.rail.line.create` | true | 创建铁路线路 |
| `canvas.rail.line.edit.own` | true | owner 编辑自己的线路 / 站点 / 车次 / 时刻表 |
| `canvas.rail.line.edit.any` | op | admin override：编辑任意线路 |
| `canvas.rail.line.delete.own / .any` | true / op | owner / admin 删除线路 |
| `canvas.rail.wall.bind` | true | 把 wall 绑到铁路网络（wall owner 同款 schedule.own） |

#### 与 0.4.0 ManualScheduleProvider 的关系

- **共存**：`wall_rail_bindings.line_id IS NULL` 的 wall 仍走 0.4.0 旧路径
- **共享 namespace**：都用 `schedule:<wallId>/*`，但 RailScheduleProvider 接管的 wall
  自动让 ManualSchedule 跳过（避免双写同 key）
- **旧 wall 文本零修改**：现有 `${var:schedule.next_departure}` 在铁路绑定后自动来自
  RailScheduleProvider，**像素无差**

---

### 1.14 全局用户变量（0.4.3）

0.4.0 的 user 变量是 **per-wall** 的（namespace = `user:<wallId>`），跨画布不共享。
0.4.3 加入 **全局用户变量**（namespace = `userglobal`），玩家自定义、**全服可见、跨 wall 共享**。

#### 创建

VariablePanel → `+ 新建变量` → 第 2 个 toggle 切到「全局」：

| 选项 | 行为 |
|---|---|
| **本 wall** | 默认；创建 `user:<wallId>/<name>`，仅本画布可见，wall 删除时一并清 |
| **全局** | 创建 `userglobal/<name>`，**全服共享**；wall 删除不影响它，只有你和管理员能修改 |

文本里写 `${var:userglobal/red_score}` 即可引用（与 user 变量 `${var:user/X}` 同款写法，
但不注入 wallId）。

#### Picker 分组

VariablePicker 把 userglobal 变量分到两组：

- 🌐 **我的全局**：你创建的（owner = 当前玩家）
- 🌐 **其他全局**：其他玩家创建的（只读显示 + 显示 owner 名）

只有 owner（创建者）和管理员（`canvas.var.global.write.any` / `delete.any`）能修改 / 删除；
其他玩家只能读取 / 引用 / 起别名。

#### 配额

config.yml `dynamic.variables` 段：

```yaml
dynamic:
  variables:
    userglobal-max-per-owner: 500   # 每 owner 上限
    userglobal-max-total: 10000     # 全服总上限
```

超出抛 `QUOTA_EXCEEDED`。

#### 权限节点（paper-plugin.yml）

| 节点 | 默认 | 作用 |
|---|---|---|
| `canvas.var.global.create` | true | 创建全局变量 |
| `canvas.var.global.write.own` | true | owner 改自己的全局变量 |
| `canvas.var.global.write.any` | op | admin override：改任意全局变量 |
| `canvas.var.global.delete.own` | true | owner 删自己的全局变量 |
| `canvas.var.global.delete.any` | op | admin override：删任意全局变量 |

#### 与 user 变量的对比

| 项 | `user/X`（per-wall） | `userglobal/X`（全局，0.4.3） |
|---|---|---|
| 内部 namespace | `user:<wallId>` | `userglobal` |
| 跨 wall 共享 | ❌ | ✅ |
| wall 删除时 | FK CASCADE 一并删 | 保留（admin 可手动删） |
| 同 namespace 内 key 唯一性 | per-wall 唯一 | **全服唯一**（任何 owner 抢用同 key 都拒 `VARIABLE_EXISTS`） |
| 别名（0.4.2） | 复用同表，per-wall 独立别名 | 同上（每 wall 可对同一全局变量起不同别名） |
| `.canvas` 工程导出 | 含 user 变量值 | **不含**（服务器级状态，跨服务器无意义） |
| 外部插件可推 | ❌（namespace 保留） | ❌（`userglobal` 加入 RESERVED_NAMESPACES，**插件应用自己 namespace 实现全服共享**） |

#### 玩家场景示例

- 全服活动比分板：玩家 A 在画布 X 上创 `userglobal/red_score`，画布 Y / Z 都 ref 同一变量；
  A 在 X 上修改值，Y / Z 实时同步
- 公告状态：admin 创 `userglobal/announcement_text`，全服 wall 引用同一文案；
  admin 用 `/canvas var set userglobal/announcement_text "维护中"` 即可全服更新

---

## 第二部分：运维管理

### 2.1 /canvas var 命令族

权限：`canvas.var.command`（默认 op）。

7 子命令完整 reference：

#### `list [namespace]`

无参 → 列全部 namespace + 每个的变量数量：

```
$ /canvas var list
Variable namespaces:
  system · 8 var(s)
  system:w-1a2b3c4d · 4 var(s)        # per-wall wall.* 变量
  schedule:w-1a2b3c4d · 15 var(s)
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
  system '系统变量' [static] keys=12 interval=1s
  scoreboard 'Bukkit Scoreboard' [dynamic] keys=0 interval=10s
  papi 'PlaceholderAPI' [dynamic] keys=0 interval=30s
  schedule 'Manual Schedule' [static] keys=15 interval=1s
  rail 'Rail Schedule' [static] keys=... interval=...
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

- `user_variables` — per-wall user 变量（`wall_id / name / type / default_value / current_value / bound_to / created_at / updated_at`）
- `user_global_variables` — 全局 user 变量（0.4.3，namespace = `userglobal`，PK = name 全服唯一）
- `variable_aliases` — 变量别名（0.4.2，per-wall）
- `wall_schedules` — 时刻表 per-wall 配置（站名 / precision 等）
- `schedule_entries` — 时刻表 entry（HH:mm / 终点）
- `rail_lines` / `rail_stations` / `rail_runs` / `rail_timetable` / `wall_rail_bindings` — 铁路网络（0.4.4）

备份（停服或在 server 内 `save-all + save-off`）：

```bash
sqlite3 plugins/HikariCanvas/data.db ".backup '/path/to/backup-$(date +%Y%m%d).db'"
```

或简单 cp：

```bash
cp plugins/HikariCanvas/data.db /path/to/backup-$(date +%Y%m%d).db
```

恢复：停服 → 把备份文件覆盖回去 → 启动。schema 迁移（当前到 V017）会自动跑。

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
# 当前总数：~1989 test（覆盖全部版本）

# 前端
cd web && npm run test
# 当前总数：~1303 test
```

### 3.2 本地 dev server 端到端

```bash
./gradlew :plugin:runServer
# 端口：默认 7878（编辑器 URL）+ 25565（MC）
# 客户端连 localhost:25565；浏览器开 /canvas confirm 给的 URL
```

### 3.3 测试 checklist 31 步

按以下表逐项验证。`#` 列对应顺序，`步骤` 是操作描述，`验证范围` 是涉及的功能点，`预期` 是观察结果。

| # | 步骤 | 验证范围 | 预期 |
|--:|---|---|---|
| 1 | wand 创建 wall | wall 创建 | wall 出现 |
| 2 | `/canvas open <id>` | 编辑器打开 | 浏览器编辑器加载 |
| 3 | TopBar 看到 Variable + Train 按钮 | 变量面板 + 时刻表入口 | 两图标可见 |
| 4 | 点 Variable → 弹 380px drawer | 变量面板 | drawer 出现 |
| 5 | 创建 user 变量 "red"（NUMBER, 默认 0） | 用户变量创建 | VariablePanel 出现 |
| 6 | 单击 `[+1]` 5 次 | 变量改值 | 值 5 |
| 7 | 长按 `[+1]` 1 秒 | 长按累加 | 跳 ~19 |
| 8 | TextElement 写 `${var:user/red}` | 变量占位符解析 | live preview 显数字 |
| 9 | textarea 输入 `${` | 变量选择器自动弹出 | Picker 自动弹 |
| 10 | Picker 看到 system / wall / scoreboard / papi / schedule | 全命名空间列举 | 全显示 |
| 11 | 用 `${var:system/server.time}`（注意必须带 `system/` 前缀） | 系统变量 | wall 显 `HH:mm` |
| 12 | 用 `${var:wall.alias}` | 单 wall 系统变量 | wall 显 alias |
| 13 | `/scoreboard objectives add points dummy` + `${var:scoreboard.points.<你>}` | 计分板变量（动态注册） | 10s 后出现 |
| 14 | `/scoreboard players set <你> points 42` | 计分板变量刷新 | wall 10s 内更新 42 |
| 15 | 装 PAPI + `${var:papi/%player_name%}` | PlaceholderAPI 桥接 | 显玩家名 |
| 16 | TopBar Train → 配 2 条时刻表 + 站名 | 时刻表变量 | modal 关闭后 schedule.* 变量自动出现 |
| 17 | `${var:schedule.next_departure}` / `eta_minutes` | 时刻表变量刷新 | 实时倒数 |
| 18 | `./gradlew :examples:demo-train-plugin:jar` + 复制到 plugins/ + `/reload confirm` | 第三方插件变量推送 | console 看 "registered namespace 'demo_train'" |
| 19 | `${var:demo_train/line1.eta_minutes}` | 第三方插件变量推送 | 5s 更新一次 |
| 20 | `/demoscore add red 3` | 第三方插件命令推送 | demo_score/red 显 3 |
| 21 | `/demoscore reset` | 第三方插件命令推送 | 归零 |
| 22 | 玩家 join → demo_score/mvp 自动设 | 第三方插件事件推送 | wall mvp 更新 |
| 23 | `/canvas var list` | 变量命令族 | 输出全部 namespace |
| 24 | `/canvas var get user:<wallId>/red` | 变量命令族 | 完整元信息 |
| 25 | `/canvas var set demo_score/red 999` | 变量命令族 + 审计日志 | wall 显 999 + 日志 |
| 26 | `/canvas var providers` | 变量命令族 | 列 6+ provider |
| 27 | `/canvas var inspect <wallId>` | wall 变量引用追踪 | 列引用变量 |
| 28 | `/canvas var reload` | 变量命令族 | 限流配置重读 |
| 29 | 删除 user/red → wall 显 ??? | 变量删除 fallback | banner 红警告 + ??? |
| 30 | 切到另一 wall | wall 切换状态重置 | variables store reset |
| 31 | reload DemoTrain plugin | 第三方插件卸载清理 | "30s grace + purge" 日志 |

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
- [`docs/protocol.md`](protocol.md) — WS 协议（当前 v7）
- [`docs/data-model.md`](data-model.md) — SQLite / PDC 格式

变更日志 / 实施细节倒序见 [`docs/journal.md`](journal.md)。
