# 模板包规范（`.canvas` Pack）

**定位**：本文档定义 HikariCanvas 模板包（`.canvas` pack）的归档结构、来源层级与工坊权限、`params.json` 参数声明与类型校验、占位符替换与条件表达式语义，以及套用与交叉导入管线。模板包与普通工程共享 `.canvas` 容器格式，以 `manifest.kind = "pack"` 区分，天然承载多层元素、时间轴动画、积木脚本与图片资产。

**关联契约**：`docs/import-export.md`（`.canvas` 归档格式、解包与物化管线）· `docs/data-model.md §4`（manifest 与持久化结构）· `docs/security.md §4.4`（归档导入安全栈）· `docs/scripting.md §2.3`（共用条件表达式求值器）。

---

## 1. 概述与包结构

模板包是一个标准 `.canvas` ZIP 归档，通过 `manifest.json` 中的 `kind = "pack"` 标识，在普通工程归档之上叠加一层 `params.json` 参数声明与 `project.json` 字符串占位符：

```
my_template.canvas
├── manifest.json      # 必选：kind="pack" + spec + name + wall{width,height} + 可选 id
├── project.json       # 必选：完整 ProjectState（可含 ${param} 占位符与 ${var:X} 运行时变量）
├── params.json        # 可选：参数声明数组（零参数模板可省略）
├── scripts.json       # 可选：墙脚本规则数组（ScriptRule[]）
├── thumbnail.png      # 可选：256×128 预览缩略图
└── assets/*.png       # 可选：内容寻址图片资产
```

### 1.1 `manifest.json` 模板字段约束

除遵循 `docs/import-export.md §2.2` 的通用字段（`spec`、`kind`、`created_at`、`name`、`wall{width,height}`）外，模板包具有以下约定：

- **`kind`**：固定为 `"pack"`（模板注册表扫描到 `"project"` 归档时按 §5 宽容规则作零参数模板加载）。
- **`id`**（可选）：匹配 `^[a-z][a-z0-9_-]{2,63}$`。玩家发布工坊模板时写入自声明 ID（形如 `user-<uuid8>-<slug>`），使 `TemplateRegistry` 条目 key 与数据库 `templates.template_id` 严格一致；手写内置或服务器模板省略 `id` 时，注册表自动退回文件名去 `.canvas` 后缀的 stem（如 `subway.canvas` → `subway`）。
- **画布尺寸合成**：注册表加载时从 `manifest.wall` 合成固定尺寸的 `TemplateCanvas`（`size = "fixed"`, `maps = [width, height]`），供前端 `TemplateGallery` 展示尺寸并判定与当前会话墙是否兼容；完整画布内容留在 `project.json` 中，套用时按需解包物化。

### 1.2 全字段占位符机制

`project.json` 中任意字符串字面量位置均可书写 `${param}` 模板参数占位符（参数名匹配 `^[a-z][a-z0-9_]{0,31}$`，不含冒号）：

- **文本与颜色字段**：如 `"text": "${station} 站"`、`"fill": "${line_color}"`、`"fontId": "${title_font}"`。
- **数值字段**：在原始 `project.json` 中以字符串字面量书写占位符（如 `"x": "${offset_x}"`、`"fontSize": "${size}"`）。套用时先在 JSON 文本层替换为数值字面量字符串（如 `"x": "10"`），再由 `ProjectMaterializer` 借助 Jackson 默认开启的 scalar coercion 自动反序列化回目标数值类型（`int` / `double`）。非数值非法串由物化校验拦截并报 `IMPORT_MALFORMED`。
- **与运行时变量正交共存**：模板参数 `${param}` 不含冒号，运行时动态变量 `${var:X}` 必含冒号。模板套用阶段仅替换无冒号的 `${param}`，`${var:X}` 原样穿透保留至渲染期动态解析。

---

## 2. 来源层级、工坊隔离与权限

### 2.1 三层模板源与加载覆盖规则

`TemplateRegistry` 按固定顺序扫描加载三类来源（`TemplateSource` 枚举）：

| 来源层级 | 枚举值 | 存储路径 | 所有权（`ownerUuid`） | 同 `id` 冲突处理 |
| --- | --- | --- | --- | --- |
| 内置模板 | `BUILTIN` | jar 内 `resources/templates/*.canvas`（优先按 `templates/_index.txt` 顺序清单读取，无清单时回退扫描 jar/classes 目录） | 空（全服可见） | 同层重复跳过并记 warn |
| 服务器模板（服主管理层） | `SERVER` | `plugins/HikariCanvas/templates/*.canvas` | 空（全服可见） | **覆盖同 `id` 的 `BUILTIN` 模板**；同为 `SERVER` 重复则跳过并记 warn |
| 玩家工坊模板 | `USER` | `plugins/HikariCanvas/user-templates/<uuid>/*.canvas` | `<uuid>`（目录名须为合法 UUID，非法子目录跳过并记 warn） | **不覆盖任何已有 `id`**（防玩家抢占内置、服主或其他玩家模板 ID） |

- **单包容错与本地解包闸**：单个 `.canvas` 包损坏或校验不过时仅记 warn 日志并跳过，不影响其余模板加载。本地磁盘/jar 扫描使用独立防炸弹安全上限（单包 ≤ 64 MiB、单条目解压 ≤ 64 MiB、总解压 ≤ 256 MiB）。
- **原子热重载**：`/canvas reload templates`（需 `canvas.admin`）在后台构建完整新映射后一次性替换 `volatile` 注册表引用，读取端无锁且绝不会观测到半加载状态。
- **持久化兼容**：玩家发布的工坊模板元数据写入 SQLite `templates` 表，落盘路径列沿用历史列名 `yaml_path`（受 forward-only schema 冻结纪律约束，实际存储 `<slug>.canvas` 路径，Java 侧映射为中性 `filePath`）。

### 2.2 工坊所有权隔离与发布配额

- **可见性与套用双向隔离**：`BUILTIN` 与 `SERVER` 模板对所有玩家公开可见、可套用。`USER` 工坊模板受跨用户隔离保护——非 owner 且不持有 `canvas.template.use-others` 权限的玩家，在会话 `ready` 帧及模板列表查询（`TemplateRegistry.listVisibleTo`）中不会收到他人的工坊模板（防止画布结构越权泄露），直接调用套用接口（`TemplateRegistry.byIdForApply`）亦被 `ForbiddenTemplateException` 拒绝。
- **发布配额**：`config.yml` 中 `templates.max-per-player` 控制单名玩家可发布的工坊模板数量上限（默认 `20`，设为 `0` 表示不限）。超出上限返回 `QUOTA_EXCEEDED`；更新同 `slug` 的已有自有模板不新增配额占用；持有 `canvas.template.bypass-limit` 的玩家豁免此上限。

### 2.3 权限节点

权限检查集中在 `TemplateOpDispatcher` 与 `paper-plugin.yml`：

| 权限节点 | 默认值 | 作用 |
| --- | --- | --- |
| `canvas.template.save` | `true` | 将当前招牌工程导出并发布为玩家工坊模板（`template.save`） |
| `canvas.template.delete.own` | `true` | 删除自己发布的工坊模板 |
| `canvas.template.delete.any` | `op` | 删除任意玩家的工坊模板（管理员审核治理） |
| `canvas.template.feature` | `op` | 在模板库标记或取消「精选」推荐状态 |
| `canvas.template.bypass-limit` | `op` | 豁免 `templates.max-per-player` 单玩家模板发布数量配额 |
| `canvas.template.use-others` | `op` | 查看并套用其他玩家拥有的工坊模板 |

---

## 3. 参数声明规范（`params.json`）

`params.json` 为顶层 JSON 数组，每项声明一个暴露给前端表单填写的模板参数（由 `PackParamResolver.parse` 解析为 `ParamDef(id, TemplateParam)`）：

```json
[
  {
    "id": "station",
    "type": "string",
    "label": "站名",
    "description": "显示在站牌中央的主标题",
    "required": true,
    "default": "人民广场",
    "max_length": 8,
    "placeholder": "例：人民广场",
    "group": "基础信息"
  },
  {
    "id": "line_color",
    "type": "color",
    "label": "线路色",
    "default": "#E4002B",
    "presets": [
      { "label": "1 号线红", "value": "#E4002B" },
      { "label": "2 号线绿", "value": "#00A651" }
    ],
    "group": "视觉样式"
  },
  {
    "id": "show_english",
    "type": "bool",
    "label": "显示英文站名",
    "default": true
  },
  {
    "id": "english_name",
    "type": "string",
    "label": "英文站名",
    "default": "People's Square",
    "max_length": 32,
    "visible_when": "show_english == true"
  }
]
```

### 3.1 8 种冻结参数类型

`id`（非空白字符串，匹配 `^[a-z][a-z0-9_]{0,31}$`）与 `type` 均为必填字段。`type` 取值集合自 `v1.0` 起冻结为以下 **8 种**（缺失或不在集合内在解析期即抛出 `IMPORT_MALFORMED`，避免坏包混入模板库后在套用阶段报错）：

| `type` | 转换目标类型 | 类型专属约束字段 | 校验与转换规则（`PackParamResolver`） |
| --- | --- | --- | --- |
| `string` | `String` | `min_length`, `max_length`, `placeholder`, `pattern` | 转字符串；校验字符长度上下界；若声明 `pattern` 则执行带步数硬闸的正则整串匹配 |
| `text` | `String` | `min_length`, `max_length`, `placeholder`, `pattern` | 多行文本输入控件；后端校验与转义规则与 `string` 一致 |
| `int` | `Integer` | `min`, `max`, `step` | 接受 JSON 数值或匹配 `^-?\d+$` 的整数字符串（超出 32 位 `int` 范围判非法）；校验 `[min, max]` 闭区间 |
| `float` | `Double` | `min`, `max`, `step` | 接受有限 JSON 数值或可解析为有限 `double` 的字符串（`NaN` / `Infinity` 一律拒绝，防止绕过上下界比较）；校验 `[min, max]` 闭区间 |
| `bool` | `Boolean` | — | 接受 JSON 布尔值或大小写不敏感的 `"true"` / `"false"` 字符串 |
| `color` | `String` | `presets: [{label, value}]` | 必须匹配十六进制颜色正则 `^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$`（`#RRGGBB` 或 `#RRGGBBAA`） |
| `enum` | `String` | `options: [{label, value}]` | `options` 条目的 `value` 可为字符串或数字；填值 `toString()` 必须命中某个选项的 `String.valueOf(value)` |
| `font` | `String` | — | 字体 ID 字符串（如 `source_han_sans`、`ark_pixel`），前端下拉选项取自 `GET /api/font/list` |

### 3.2 通用元数据字段

所有参数类型共享以下声明字段（未知多余字段由 `@JsonIgnoreProperties(ignoreUnknown = true)` 宽容忽略）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `string` | **必填**。占位符标识符，对应 `project.json` 中的 `${id}` |
| `type` | `string` | **必填**。上表 8 种类型之一 |
| `label` | `string` | 表单显示名称 |
| `description` | `string` | 表单辅助说明文案 |
| `required` | `boolean` | 是否必填（默认 `false`） |
| `default` | `any` | 用户未传或传 `null` 时的回退默认值（数值参数若用于数值字段占位符，应提供合法数值 `default`） |
| `visible_when` | `string` | 表单项条件显示表达式（文法见 §4.2） |
| `group` | `string` | 表单分组标题；`TemplateGallery` 按 `group` 首次出现顺序分节展示并支持折叠，未设 `group` 的参数归入顶部无标题默认组 |

### 3.3 参数校验与正则回溯防护

1. **空值回退与累计报错**：`PackParamResolver.resolve` 将缺失键与显式 `null` 同等对待，均回退至 `default`；若回退后仍为 `null`，`required = true` 时记录缺失错误，`required = false` 时映射为空串 `""`。一次套用会遍历全部参数累计所有校验错误，非空则统一抛出 `IMPORT_INVALID_PARAM`。
2. **不可信正则硬闸（防 ReDoS）**：由于第三方 `.canvas` pack 可同时携带恶意 `pattern`（如 `(a+)+$`）与触发回溯的 `default` 值，`PackParamResolver` 对 `pattern` 实施双重硬限：
   - 正则源串长度上限 `MAX_PATTERN_LEN = 256`；
   - 匹配过程以 `StepLimited` 包装 `CharSequence`，每次 `charAt` 读取扣减预算，单次匹配最多允许 `MAX_MATCH_STEPS = 200_000` 步，超限立即确定性中止并记入参数校验错误，不依赖超时线程、不阻塞工作线程。

---

## 4. 参数替换、表达式文法与套用管线

### 4.1 文本层 JSON 转义替换（`PackParamResolver.substitute`）

参数校验通过后，`PackParamResolver.substitute` 在 `project.json` 原始文本上执行**单遍、非递归**替换：

1. **JSON 字符串内容转义**：每个参数值先按 JSON 字符串字面量规则转义内部引号、反斜杠、换行与控制字符（等价于 Jackson `writeValueAsString` 剥除首尾引号），再拼入 `${param}` 位置。多行文本或含引号的招牌文案不会破坏 `project.json` 的 JSON 语法结构，而纯数字字面量转义后保持原样以供后续 scalar coercion。
2. **单遍防注入与膨胀上限**：底层委托 `Interpolator.interpolate` 单遍扫描替换——用户输入的参数值即使包含 `${other}` 字面量也**不会**触发二次展开。单参数替换值上限 **16 KiB**，`project.json` 替换后净膨胀上限 **1 MiB**；引用未在 `params.json` 声明的 `${param}` 或超出体积上限均抛出 `IMPORT_MALFORMED`。

### 4.2 条件表达式文法与求值语义

`template/expr/` 包下的 `ExpressionParser` 与 `ExpressionEvaluator` 定义了项目统一的无副作用条件表达式语言，**由模板参数 `visible_when` 与积木脚本 `ConditionEvaluator`（`if` / `waitUntil` / `repeatUntil`）共同复用**：

```
or      := and ( "||" and )*
and     := equ ( "&&" equ )*
equ     := cmp ( ("==" | "!=") cmp )*
cmp     := add ( ("<" | "<=" | ">" | ">=") add )?     # 禁止连串比较
add     := mul ( ("+" | "-") mul )*
mul     := unary ( ("*" | "/") unary )*
unary   := "!" unary | "-" unary | primary
primary := "var" "(" string ")" | ident | number | string
         | "true" | "false" | "(" or ")"
```

- **运算符优先级（高 → 低）**：一元 `!` / `-` → `*` / `/` → `+` / `-` → 大小比较 `<` / `<=` / `>` / `>=` → 相等比较 `==` / `!=` → 逻辑与 `&&` → 逻辑或 `||`。
- **语法约束**：大小比较禁止连串（如 `1 < 2 < 3` 直接计为语法错误，须写为 `1 < 2 && 2 < 3`）；数字字面量不支持科学计数法（如 `1e3` 为语法错误）；`var("...")` 为内置变量取值保留形式（供脚本条件读取运行时变量，`var` 不可作普通标识符），除此之外不支持任何函数调用或属性访问。
- **`==` / `!=` 数值与字符串比较语义**：数值解析统一以 `StrictNumber` 为单一权威。当左右操作数**双侧均为数值形态**（数字字面量，或整串符合 `StrictNumber` 文法的字符串）时，按 `double` 数值等值比较（例如 `"3.50" == 3.5` 求值为 `true`）；只要任一侧非数值形态，则退回布尔或字符串字面量等值比较（因此 `"abc" == 0` 与 `"" == 0` 恒为 `false`，不存在隐式零值转换陷阱）。

### 4.3 套用管线、预览与导出

套用模板（WS `template.apply`）与普通 `.canvas` 工程导入共用同一条 `ProjectImporter` 核心管线：

```
template.apply(templateId, userParams)
  → TemplateRegistry.byIdForApply（跨用户隔离校验）
  → ProjectImporter.applyPack：
      1. CanvasArchive.unpack（流式三闸 + 路径安全 + 条目白名单）
      2. CanvasManifest.parse（spec 兼容校验）
      3. PackParamResolver.parse → resolve → substitute（参数校验 + JSON 转义单遍替换）
      4. ProjectMaterializer.materialize（画布尺寸匹配 + 结构配额闸 + 逐元素安全重校验）
      5. MissingResourceScanner.scan → AssetIngest.ingestAll（含重编码 hash 重映射）
      6. 孤儿关键帧轨剔除 → EditSession.replaceProject（整体替换工程，含图层与时间轴）
      7. ScriptImporter.importScripts（wallId 重绑目标墙 + 脚本与条件语法重校验）
  → WS dispatcher 推送 state.snapshot + 触发 ProjectionThrottler 重绘 + 持久化 walls 行
```

- **Replace 覆盖语义与溯源记录**：套用模板整体替换当前会话墙的画布工程（前端调用前弹出覆盖二次确认），并在 `walls` 表记录 `template_id` 与 `template_version` 供列表展示与审计溯源；套用完成后画板即为独立普通工程，后续自由编辑与源模板解耦。
- **动态缩略图预览（`TemplatePreviewService`）**：`GET /api/template/{id}/preview.png` 对模板包执行默认参数替换（`userParams = Map.of()`）→ 物化 `ProjectState` → 运行时变量 `${var:X}` 按 `previewResolve` 回退链收敛 → 栅格化输出预览 PNG。
- **存为模板导出（`TemplateExporter` / `TemplatePublisher`）**：玩家在编辑器触发「存为模板」时，生成 `manifest.kind = "pack"` 的 `.canvas` 归档（写出 `project.json`、`params.json`、可选 `scripts.json` 与引用图片资产，并执行物化 roundtrip 自校验），按 `user-<uuid8>-<slug>` 落盘至 `user-templates/<uuid>/` 并登记数据库与注册表。

---

## 5. 交叉导入与入口语义

普通工程与模板包同为 `.canvas` 扩展名，**唯一判别符是 `manifest.kind`**（`"project"` 或 `"pack"`），不依赖文件名或后缀。

### 5.1 编辑器入口矩阵

| 入口 | 标准输入 | 产出 / 目标去向 |
| --- | --- | --- |
| 导出工程（TopBar 菜单） | 当前会话工程 | 下载 `.canvas`（`kind = "project"`，当前快照，无 `params.json`） |
| 存为模板 | 当前会话工程 + 参数标记 | 生成 `.canvas`（`kind = "pack"`，含 `params.json` 与 `${param}` 占位符）并入库 |
| 导入工程（`POST /api/project/import`） | `project` 归档 | 整体替换当前会话墙工程 |
| 模板库加载 / 导入 | `pack` 归档 | 注册进 `TemplateRegistry` 供反复填参套用 |
| 模板套用（`TemplateGallery`） | 库内模板条目 + 用户填参 | 参数替换后整体替换当前会话墙工程 |

### 5.2 双向宽容互操作规则

从结构上看，`pack` 用默认值收敛全部 `${param}` 后即等价于合法 `project`，而 `project` 等价于零参数 `pack`（无 `params.json` 时 `applySubstitution` 直接无操作透传）。因此两条导入路径对交叉文件采取**双向宽容、不报错拦截**的设计：

| 交叉场景 | 系统行为 |
| --- | --- |
| 在「导入工程」入口上传 **`kind = "pack"`** 文件 | `ProjectImporter.importInto` 以空参数集调用 `applySubstitution`，自动用各参数的 `default` 值完成替换并导入当前墙；前端顶部展示非阻塞引导提示，告知用户该文件为模板包、可在模板入口导入以获得参数填写体验 |
| 在模板目录或模板导入入口放入 **`kind = "project"`** 文件 | `TemplateRegistry` 将其作为零参数模板加载入库（无参数表单，套用时原样还原整份工程） |
