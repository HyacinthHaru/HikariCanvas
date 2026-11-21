# HikariCanvas 变量系统使用指南

> HikariCanvas「动态信息屏」使用与运维指南，覆盖玩家入门（用户变量、系统变量、PAPI 与计分板桥接、列车时刻表、铁路网络、全局变量、变量别名）与服务端运维管理。
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
  - [1.12 变量别名](#112-变量别名)
  - [1.13 铁路网络](#113-铁路网络)
  - [1.14 全局用户变量](#114-全局用户变量)
- [第二部分：运维管理](#第二部分运维管理)
  - [2.1 /canvas var 命令族](#21-canvas-var-命令族)
  - [2.2 config.yml 限流配置](#22-configyml-限流配置)
  - [2.3 第三方插件接入](#23-第三方插件接入)
  - [2.4 监控 + 故障排查](#24-监控--故障排查)
  - [2.5 备份 + 恢复](#25-备份--恢复)
  - [2.6 重载流程](#26-重载流程)

---

## 第一部分：玩家入门

### 1.1 什么是变量

普通招牌是**静态**的：画好以后内容不会变。**变量系统**让招牌变成**动态信息屏**——文字可以根据服务器状态、玩家分数、列车 ETA 等数据自动刷新。

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

打开 wall 编辑器，在顶栏找到 **变量**（Variable）图标：

1. 点变量图标 → 右侧弹出**变量面板**
2. 点面板顶部 **[+ 新建]** 按钮 → 弹出新建变量对话框
3. 输入名称（如 `red_score`），实时校验合法字符：`[a-zA-Z0-9_.-]+`
4. 选类型：**STRING** / **NUMBER** / **BOOLEAN** / **COLOR**
5. 配默认值（按类型显示对应控件，NUMBER 默认 `0`，COLOR 默认 `#FFFFFF`）
6. 点 **提交** → 变量保存并出现在变量面板中

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
| 手动改值 | 点变量行的值 → 弹出内联输入框 |

所有变量修改都会实时写回服务端并同步刷新画布预览。

### 1.5 VariablePicker 自动补全

**文字元素的文本框内置胶囊标签（chip）编辑器**：占位符 `${var:X}` 不再显示生硬的字面字符串，而是渲染成紫色胶囊标签——悬停可查看当前值 / 来源 / 缺失告警，点击可弹出选择器更换绑定。详见 §1.5.1。

在文本框内输入 `${`，**自动弹出变量选择器（VariablePicker）**——按命名空间分组列出全部可用变量，并支持搜索过滤。

操作：

- **键盘 ↑↓** 切换候选
- **Enter** 插入（自动补全 `${var:NAME}` 并把光标移到标签后）
- **Esc** 关闭
- **左侧按钮触发** 也能手动唤出选择器（不用打 `${`）
- **画布内双击文本** 进入内联编辑时，输入 `${` 同样会弹出选择器

选择器会汇总当前可用的全部命名空间（user / userglobal / system / wall / scoreboard / papi / schedule，以及第三方插件已声明的变量）。

#### 1.5.1 chip 编辑器交互

| 行为 | 触发 | 结果 |
|---|---|---|
| 看变量当前值 | 悬停 chip | 浮出提示：原始占位符 / 当前值 / 来源（user / system / plugin / papi） |
| 改绑定 | 点击普通 chip（紫色） | 弹出变量选择器，选新变量后整颗 chip 替换 |
| 补创缺失变量 | 点击红色 chip（变量已删除 / 不存在） | 确认对话框「是否立即创建？」→ 自动新建空字符串 user 变量；非 user 域提示「请通过对应 Provider 注册」 |
| 整体删除 chip | 光标停在 chip 边界按 Backspace / Delete | chip 一次性整体删除（不会逐字符拆散） |
| 粘贴升级 | 粘贴含 `${var:X}` 字面的纯文本 | 自动识别并转为胶囊 chip（无需手动重输 `${`） |
| 复制 chip | 选中 chip + Ctrl/Cmd+C | 剪贴板拿到字面占位符 `${var:X}`，粘贴到外部应用是纯文本字符串 |
| 字号联动 | 调整文字元素字号 | chip 尺寸随字号缩放并限制在合理比例（0.6×~1.2×），避免极大或极小字号失控 |

chip 视觉状态：

- 正常：紫色胶囊 + ⚡ 前缀（深浅主题自动适配对比度）
- 错误态（变量缺失）：红色 + 删除线 + ⚠ 前缀

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

首次引用时 HikariCanvas 会自动登记 `scoreboard.points.Steve` 并开始按 10s 周期同步计分板数值。第一次渲染可能短暂显示兜底值 `???`，最多 10s 后刷新为实际分数。

> `scoreboard` 命名空间按需动态绑定，无需事先创建变量，但也不会预列在自动补全列表里，直接手写 `${var:scoreboard.<计分项>.<目标>}` 即可。

### 1.9 列车时刻表（Manual Schedule）

HikariCanvas 内置 **手动时刻表** 工具，给 server 没装专门列车 / 公交插件做兜底。支持每 wall 独立的 **分钟 / 秒精度**，共暴露 15 个变量（含 `eta_seconds` / `eta_mmss` / `arrival_status` / `precision` + 第二班 `next2_*` 系列）。需要完整线路 / 站点 / 车次抽象的服主见 §1.13 铁路网络。

操作：

1. TopBar 点 **Train**（列车）图标 → 弹 **Schedule Manager** modal
2. 设站名（终点）
3. 选时间精度（分钟 / 秒）
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

1. **chip 编辑器** 内：被删变量对应的 chip 转为 **红色 + 删除线 + ⚠ 前缀**，点击红 chip 弹「是否立即创建？」确认对话框 → 一键补创 user 变量
2. **TextElement live preview** 下方标红 **banner**——警告 "Variable X was deleted, references will render as ???"
3. hover 红 chip 显 tooltip："变量已删除，最终渲染为 '???'"

> **删除不级联**：为避免误删变量导致画布整段文字消失，删除变量时原有占位符保留在文本中，按兜底规则渲染（详见 `docs/dynamic-data.md`）。

### 1.12 变量别名

变量的内部完整名（如 `user:w-3a17/red_score` / `schedule:w-3a17/eta_seconds` / `system/server.time`）在混用多个命名空间时较长、可读性一般。你可以给任何变量起一个易读的**别名**：

- **所有 namespace 都可加别名**：user / userglobal / schedule / system / papi / scoreboard / 第三方插件均支持
- **per-wall**：同一变量在不同 wall 可起不同别名（个性化），互不干扰；同一 wall 内一个变量只能有一个别名
- **仅 UI 显示用**：别名不参与 `${var:...}` 解析——文本中仍写 `${var:user/red_score}`，渲染时按原完整名取值；别名只在选择器、变量面板和胶囊 chip 上替代展示原名
- **chip 显示优先级**：别名 > 当前值 > fallback > 默认值 > 缺失提示（数值会变，别名稳定，更适合作为胶囊标签文字）

#### 怎么加别名

**方式 A：新建变量时一并起**

变量面板「+ 新建」对话框最下面有可选 **别名** 字段（留空 = 不起别名）。

**方式 B：在变量面板里改**

每个变量行末尾有 🏷 标签按钮 → 点击 → 行内变成输入框 + 保存/清空/取消按钮，提交即生效。

**方式 C：在变量选择器里改**

文本框输入 `${` 触发选择器，列表分 3 列展示：

| 别名 | 数值 | 变量名 |
|---|---|---|
| 红队 | 5 | user/red_score |
| ETA秒 | 90 | schedule/eta_seconds |
| — | 14:35 | system/server.time |

每行末尾有 ✏ 按钮，点一下即可就地起名，编辑文本时顺手给陌生变量加个易记名，不用切回变量面板。

#### 怎么搜

变量选择器与变量面板的搜索框**同时匹配原始变量名与别名**——给 `system/server.time` 起名「服务器时间」后，搜「服务器」也能命中。

#### 规则与权限

- 别名长度最多 64 个字符；同一面画布内允许不同变量使用相同别名。
- 画布作者（owner）可直接设置或清除别名；非作者需具备 `canvas.var.write.any` 权限。任何能打开该画布的玩家都能查看已有别名。
- 删除画布时，该画布下的所有变量别名会自动一并清理。（协议与表结构契约详见 `docs/protocol.md §5.13` 与 `docs/data-model.md`。）

### 1.13 铁路网络

前面的手动时刻表是**单画布独立配置**的——100 块站台屏就要配 100 套。**铁路网络**提供完整的「线路 / 站点 / 车次 / 每站精确时刻表」抽象：定义一次线路与时刻表，多块画布绑定后自动同步。

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

在 Schedule Manager 对话框内展开「铁路绑定」区域（线路 / 本站 / 方向 3 列下拉），选定后即可完成绑定；也可用 `/canvas var inspect <wallId>` 命令查看画布当前绑定。

绑定后该画布的 `${var:schedule.next_*}` 自动来自铁路网络，取代单画布的手动时刻表配置。

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

手动时刻表原有的 `next_departure / eta_minutes / eta_seconds / eta_mmss / is_arriving /
arrival_status / precision` 等变量继续保留并由铁路网络自动填充，既有画布文本无需改动。

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

#### 与手动时刻表的关系

- **共存**：未绑定铁路线路的画布仍走手动时刻表。
- **共享 namespace**：都用 `schedule:<wallId>/*`；绑定铁路后由铁路网络自动接管，手动时刻表自动跳过，避免双写同 key。
- **旧画布文本零修改**：现有的 `${var:schedule.next_departure}` 等占位符在绑定铁路后自动切换数据源，**像素无差**。

---

### 1.14 全局用户变量

普通 user 变量是 **per-wall** 的（namespace = `user:<wallId>`），跨画布不共享。
**全局用户变量**（namespace = `userglobal`）由玩家自定义、**全服可见、跨画布共享**。

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

| 项 | `user/X`（per-wall） | `userglobal/X`（全局） |
|---|---|---|
| 内部 namespace | `user:<wallId>` | `userglobal` |
| 跨 wall 共享 | ❌ | ✅ |
| wall 删除时 | 随画布一并删除 | 保留（owner 或管理员可手动删） |
| 同 namespace 内 key 唯一性 | per-wall 唯一 | **全服唯一**（同名会被拒绝） |
| 别名 | per-wall 独立别名 | 同上（每面画布可对同一全局变量起不同别名） |
| `.canvas` 工程导出 | 含 user 变量值 | **不含**（服务器级状态，跨服务器无意义） |
| 外部插件可推 | ❌（保留命名空间） | ❌（保留命名空间；**第三方插件请使用自有 namespace 实现全服共享**） |

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

第三方插件用 `HikariCanvasAPI` 推变量到 HikariCanvas，详见 **`docs/api.md`**（含两份示例插件代码、Gradle 配置、`ServicesManager` 推荐入口，以及 ACL / 限流 / 生命周期说明）。

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

变量数据持久化在 `plugins/HikariCanvas/data.db`（SQLite）。**仅用户侧变量与时刻表配置**持久化（插件 / 系统 / PAPI / 计分板变量均为内存态）。

涉及的表：

- `user_variables` — per-wall 用户变量（`wall_id / name / type / default_value / current_value / bound_to / created_at / updated_at`）
- `user_global_variables` — 全局用户变量（namespace = `userglobal`，`name` 主键全服唯一）
- `variable_aliases` — 变量别名（per-wall）
- `wall_schedules` — 手动时刻表 per-wall 配置（站名 / precision 等）
- `schedule_entries` — 手动时刻表条目（HH:mm / 终点）
- `rail_lines` / `rail_stations` / `rail_runs` / `rail_timetable` / `wall_rail_bindings` — 铁路网络

备份（停服或在 server 内 `save-all + save-off`）：

```bash
sqlite3 plugins/HikariCanvas/data.db ".backup '/path/to/backup-$(date +%Y%m%d).db'"
```

或简单 cp：

```bash
cp plugins/HikariCanvas/data.db /path/to/backup-$(date +%Y%m%d).db
```

恢复：停服 → 把备份文件覆盖回去 → 启动（若旧备份版本较低，启动期会自动执行向前兼容的 schema 迁移）。生产环境建议定期备份，完整升级与恢复流程见 `docs/deployment.md §9`。

### 2.6 重载流程

| 改了什么 | 怎么生效 |
|---|---|
| `config.yml` 限流参数 | `/canvas var reload` |
| `config.yml` 其他参数（host / port / 池容量等） | `/canvas reload config`（仅标 `[热更]` 的项当场生效，其余需重启） |
| user 变量 / schedule / 铁路数据 | 实时（自动推送到在线编辑会话与画布渲染） |
| `lang/*.yml` 游戏内文案 | `/canvas reload config` 热重载 |
| `paper-plugin.yml` 权限定义 | 重启 server |
| 第三方插件代码 | 触发 PluginDisableEvent → 30s grace → reload plugin |

---

## 相关文档

- [`docs/dynamic-data.md`](dynamic-data.md) — 变量系统设计 / 协议契约
- [`docs/api.md`](api.md) — 第三方插件接入 SDK
- [时间轴动画使用指南](timeline-guide.md) — 让元素随时间或变量变化动起来
- [积木脚本使用指南](scripting-guide.md) — 用积木读写变量、编排条件与动作
- [`docs/protocol.md`](protocol.md) — WebSocket 协议
- [`docs/data-model.md`](data-model.md) — SQLite / PDC 格式
