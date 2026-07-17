# 模板 YAML 规范

**状态：** 实装稿 · 对齐代码 2026-06-14
**格式版本：** `1`（`TemplateLoader.SUPPORTED_SPEC = 1`）
**文件扩展名：** `.yml`（或 `.yaml`）
**适用范围：** 所有内置模板、服务器自定义模板、社区分享模板

本文档定义模板的 YAML 结构。模板是用户面的重要扩展点，**格式一旦在 v1.0 发布就不再做破坏性变更**；新字段只能以向后兼容方式加入。

> **两种模板模式：**
> 1. **声明式 layout 模式**——本文 §2-§7 描述的手写 YAML（`canvas` + `params` + `layout`），内置模板与服主手写模板走这条。
> 2. **raw_state 模式**——玩家在编辑器里点「存当前招牌为模板」，系统把当前 `ProjectState` 整体内嵌进模板的 `raw_state` 字段（见 §2.1）。这是玩家创意工坊的主路径。
>
> 两种模式由 `raw_state` 字段是否存在区分（`TemplateSpec.isRawStateMode()`）：`raw_state` 非空时 `canvas` / `layout` 可省略，实例化绕开声明式路径。

---

## 1. 文件位置与加载

| 来源 | 路径 |
| --- | --- |
| 内置模板 | jar 内 `resources/templates/*.yml`（只读） |
| 服务器模板 | `plugins/HikariCanvas/templates/*.yml` |
| 玩家模板（创意工坊） | `plugins/HikariCanvas/user-templates/<uuid>/*.yml` |

加载顺序：**内置 → 服务器 → 玩家**（代码出处 `TemplateRegistry.reload`）。覆盖规则**不对称**：
- **服务器模板同 `id` 覆盖内置**（允许服主替换内置模板）
- **玩家模板同 `id` 跳过**（不覆盖 builtin / server，避免玩家抢占已有 id）

启动时全部扫描解析，失败的单个模板记 warn log，不影响其他模板加载。`/canvas reload templates` 热重载（管理员权限 `canvas.admin`，原子 swap 一次性替换 `volatile` registry 引用避免半态）。

**解析库：** `jackson-dataformat-yaml` 2.18.2，与项目 Jackson 主线一致。YAML 直接 `readValue` 到 record（详见 `docs/security.md §4.3`）。不开 polymorphic typing、不允许 `@class` 之类字段。

---

## 2. 顶层结构

```yaml
spec: 1                     # 模板格式版本（必填）
id: subway_station          # 全局唯一 ID（必填）
name: 地铁站牌               # 显示名（必填）
description: 标准地铁站风格   # 简介
version: 1                   # 模板内容版本，便于追踪改动
author: "hikari-canvas-official"
tags: [sign, transit, cjk]   # 搜索分类

canvas:                     # 画布定义（raw_state 模式可省略）
  ...

params:                     # 参数声明
  ...

layout:                     # 布局与元素（raw_state 模式可省略）
  ...

# raw_state:                # 创意工坊模式：内嵌完整 ProjectState（与 layout 互斥，见 §2.1）
#   ...
```

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `spec` | ✅ | int | 格式版本，当前 `1`。插件拒绝更高 spec |
| `id` | ✅ | string | 匹配 `^[a-z][a-z0-9_-]{2,63}$`（小写字母开头，含数字 / `_` / `-`，长度 3~64；允许 `-` 以容纳 `user-<uuid8>-<slug>` 工坊命名） |
| `name` | ✅ | string | UI 显示名，支持 Unicode，长度 ≤ 64 |
| `description` | | string | |
| `version` | | int | 模板内容版本，服主自管 |
| `author` | | string | |
| `tags` | | string[] | 编辑器筛选用 |
| `canvas` | △ | object | 见 §3。声明式 layout 模式必填；raw_state 模式可省略 |
| `params` | | map | 见 §5 |
| `layout` | △ | object | 见 §4。声明式 layout 模式必填；raw_state 模式可省略 |
| `raw_state` | △ | object | 见 §2.1。raw_state 模式专用；与 `layout` 二选一 |

> **`preview` 字段已废弃。** 编辑器缩略图走服务端动态渲染（`GET /api/template/{id}/preview.png`，见 §7 / §12），模板里写 `preview` 无效（解析时静默忽略，不报错）。

---

## 2.1 raw_state 模式（创意工坊）

玩家在编辑器里点「存当前招牌为模板」时，后端 `TemplateExporter` 把当前 `ProjectState` 反向序列化为模板（代码出处 `template/TemplateExporter.java` / `TemplatePublisher.java`）：

```yaml
spec: 1
id: user-1a2b3c4d-my-sign
name: 我的招牌
raw_state:                # 内嵌完整 ProjectState 的序列化形式（Map 结构）
  widthMaps: 4
  heightMaps: 1
  canvas: { ... }
  layers:
    - elements:
        - type: text
          text: "${text_1}"   # 自动参数化的文本
          ...
params:
  text_1:
    type: text
    default: "原始文字"
```

**raw_state 模式自动参数化范围 = 仅文本。** 导出时 `TemplateExporter` 按 z-order 扫所有 `TextElement`，给每个分配默认 paramId `text_1 / text_2 / ...`；用户对每个文本可选「保留为参数」（keep）或写死。keep 时把 element 的 `text` 字段替换为 `"${paramId}"`，并在 `params` 段生成一个 `type: text` 的参数。**raw_state 自动导出对非文本字段（颜色 / 字号 / 坐标 / fill 等）的参数化留 v1.x**（代码注释明确标注 future）。

> **声明式 layout 模式（§4）参数化范围更广。** 手写 YAML 时不止文本内容可参数化——关键属性如 `text.color` / `text.font` / `text.size`、`rect.fill` / `rect.stroke.width`、`effects.*.color`、`icon.source` / `icon.tint` 等都可写 `${param}`（实例化期 `interp` 插值，代码出处 `TemplateLoader.collectParamRefs` 校验声明 + `TemplateInstantiator.materialize` 插值）。复杂多参数化（含 raw_state 自动导出未覆盖的字段）建议直接走 raw_state 模式。

**实例化（`TemplateInstantiator`）：** 检测到 `raw_state` 非空时，深拷贝 raw_state → 遍历 Map 把**所有 String 字段**中的 `${param}` 占位符替换为参数值 → 反序列化回 `ProjectState` → 走 EditSession replace。raw_state 内的 element 同样跑 `validateElementForTemplateApply` 二次安全校验（坐标 / 尺寸 / 旋转 / fill / mask / image source 等），防止玩家通过模板注入畸形 element。

**安全阈值：** raw_state 嵌套深度上限 32（`MAX_DEEP_COPY_DEPTH`）；单文档 YAML 码点上限 5 MiB；anchor/alias 展开上限 50。

---

## 2.2 用户模板所有权与权限

玩家发布的工坊模板带**所有权标记**：`TemplateEntry.ownerUuid` 非空表示该条目来自 `user-templates/<uuid>/` 目录（DB `templates` 行同样持 owner UUID + name，代码出处 `TemplatePublisher`）。builtin / server 模板 `ownerUuid` 为空，所有玩家可见可用。

**权限节点**（代码出处 `web/TemplateOpDispatcher.java` + `paper-plugin.yml`）：

| 节点 | 默认 | 作用 |
| --- | --- | --- |
| `canvas.template.save` | `true` | 把当前 wall 发布为工坊模板 |
| `canvas.template.delete.own` | `true` | 删除自己创作的模板 |
| `canvas.template.delete.any` | `op` | 删除任意工坊模板（管理 / moderation） |
| `canvas.template.feature` | `op` | 在模板库标记 / 取消「精选」 |
| `canvas.template.bypass-limit` | `op` | 跳过每玩家发布数量配额 |
| `canvas.template.use-others` | `op` | apply 其他玩家拥有的工坊模板（跨用户隔离 bypass） |

**跨用户隔离：** 非 owner 且无 `canvas.template.use-others` 的玩家，ready 帧模板列表里看不到他人的工坊模板，apply 时也被 `ForbiddenTemplateException` 拦截。builtin / server 模板不受此限。

**发布配额：** `config.yml → templates.max-per-player`（默 20；`0` = 不限）。持 `canvas.template.bypass-limit` 的玩家跳过配额；超额发布返 `QUOTA_EXCEEDED`（更新同 slug 的现有模板不计配额，给宽限）。

---

## 3. canvas 定义

```yaml
canvas:
  size: auto                # auto | fixed
  maps: [4, 1]              # fixed 时：固定地图数；auto 时忽略
  min_maps: [3, 1]          # auto 时：允许的最小矩阵
  max_maps: [8, 2]          # auto 时：允许的最大矩阵
  background: "#FFFFFF"     # 画布底色，支持 ${param}
  padding: [8, 8, 8, 8]     # 上/右/下/左，像素
```

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `size` | enum | `auto` | `auto`=根据内容撑开；`fixed`=按 `maps` 固定 |
| `maps` | `[int, int]` | — | `[width, height]`，`size: fixed` 必填 |
| `min_maps` | `[int, int]` | `[1, 1]` | `size: auto` 时下限 |
| `max_maps` | `[int, int]` | `[8, 4]` | `size: auto` 时上限 |
| `background` | color | `"#FFFFFF"` | hex `#RRGGBB[AA]` 或 `${param}`。**声明式 layout 模式下仅 hex 字符串/占位符**（`TemplateCanvas.background` 为 `String`）；渐变背景请走 raw_state 模式（ProjectState 的 `canvas.background` 是 Fill 联合类型，见 §4.4） |
| `padding` | int / `[int,int,int,int]` | `0` | 单值等同四值相同；上/右/下/左 |

> **校验：** `maps` / `min_maps` / `max_maps` 各维度范围 `[1, 16]`（`TemplateLoader.validateMapsDim`）。`size: fixed` 时 `maps` 必填。

---

## 4. layout 定义

### 4.1 layout.type

```yaml
layout:
  type: stack               # stack | grid | free
  direction: vertical       # stack 使用
  gap: 4                    # 子元素间距
  elements: [ ... ]
```

| `type` | 子元素布局 |
| --- | --- |
| `stack` | 按 `direction: vertical | horizontal` 依次排列，自动换行可选 |
| `grid` | 规则网格，需 `columns` 与 `rows` |
| `free` | 子元素用显式 `x/y/w/h` 定位，画布坐标系 |

### 4.2 元素通用字段

```yaml
- type: text                # text | rect | line | icon（声明式 layout 模式的 4 种元素）
  id: title                 # 元素局部 id（可空，系统自动生成 e-<uuid>）
  x: 0                      # free 时必填；stack/grid 忽略
  y: 0
  w: auto                   # "auto" 或具体像素，auto 由内容决定
  h: auto
  rotation: 0               # 连续整数，范围 [0, 360)；任意角度均可（非仅直角）
  visible: true             # 支持 ${param}
  z_order: 0
```

> **声明式 layout 元素 = `text` / `rect` / `line` / `icon` 共 4 种**（代码出处 `template/TemplateElement.java` sealed permits）。这是模板 DSL 的简化元素集，**不是**运行时的完整元素体系。
>
> 运行时 `ProjectState` 有 **8 种** element：`text` / `rect` / `circle` / `shape` / `path` / `brush` / `image` / `icon`（代码出处 `state/Element.java`）。raw_state 模式（§2.1）内嵌的是完整 ProjectState，故 8 种全部可用；声明式 layout 模式只暴露上述 4 种。
>
> **`rotation` 是连续整数**，范围 `[0, 360)`，任意角度均可（`ElementValidator.validateRotation` 校验 `0 ≤ r < 360`）。

### 4.3 text 元素

```yaml
- type: text
  content: "${name}"         # 支持参数插值（必填）
  font: source_han_sans      # 内置字体 ID；支持 ${param}（动态字体选择）；缺省 ark_pixel
  size: 48                   # 字号；缺省 24；钳位 [1, 512]（MAX_FONT_SIZE）
  color: "#000000"           # 缺省 #000000；支持 ${param}
  align: center              # left | center | right；缺省 left
  line_height: 1.2           # 缺省 1.2；钳位 [0.5, 4.0]
  letter_spacing: 0          # 缺省 0；钳位 [-32, 128]
  vertical: false            # 竖排
  effects:
    stroke:
      width: 1               # 描边宽度，范围 [0, 128]
      color: "#FFFFFF"       # 支持 ${param}
    shadow:
      dx: 2                  # 偏移 ±128
      dy: 2
      color: "#808080"       # 支持 ${param}
    glow:
      radius: 4              # 范围 [0, 64]
      color: "#FFD700"       # 支持 ${param}
```

> **字体 ID 示例。** 内置字体 ID 形如 `source_han_sans`（黑体）/ `source_han_serif`（宋体）/ `ark_pixel`（像素）/ `smiley_sans` 等（见 CLAUDE.md 字体矩阵）。模板未指定 `font` 时缺省 `ark_pixel`（`TemplateInstantiator` 默认值）。`content` 缺省空串、`color` 缺省 `#000000`、`size` 缺省 24、`align` 缺省 `left`。
>
> **数值范围（钳位 / 拒绝，代码出处 `state/ElementValidator.java`）：** `size` 钳位 `[1, 512]`、`line_height` 钳位 `[0.5, 4.0]`、`letter_spacing` 钳位 `[-32, 128]`；`stroke.width ≤ 128`、`shadow` 偏移 `±128`、`glow.radius ≤ 64`。文本内容（插值后）长度 > 256（`MAX_TEXT_LEN`）报 `INVALID_TEMPLATE`。`effects` 块内三处 `color` 均支持 `${param}`。

### 4.4 rect 元素

```yaml
- type: rect
  w: 100%                   # 百分号表示相对父容器
  h: 16
  fill: "${line_color}"     # 声明式 layout 模式：hex 字符串 / ${param}（→ SolidFill）
  stroke:
    width: 1                # 支持 ${param}
    color: "#000000"
```

> **声明式 layout 模式下 `rect.fill` 仅纯色。** `TemplateElement.Rect.fill` 是 `String`，实例化时包成 `SolidFill`（代码出处 `TemplateInstantiator.materialize`）。
>
> **渐变填充（solid / linear / radial 三态联合）走 raw_state 模式。** 运行时 `Fill` 是联合类型：
> - `solid` — `{type: solid, color: "#RRGGBB[AA]"}`
> - `linear` — `{type: linear, angle: 0..360, stops: [...]}`（线性渐变）
> - `radial` — `{type: radial, cx, cy, r, stops: [...]}`（径向渐变，`cx/cy ∈ [0,1]`、`r ∈ (0,2]`）
>
> `stops` 为 2~8 个 `{position(0..1, 非递减), color}`（代码出处 `state/FillValidator.java`，`MIN_STOPS=2` / `MAX_STOPS=8`）。raw_state 内嵌的 rect / circle / shape / path / icon 元素都可用渐变 fill。

### 4.5 line 元素

```yaml
- type: line
  from: [0, 0]
  to: [100, 0]
  width: 2
  color: "#000000"
```

> **⚠️ `line` 元素 v1 不渲染。** 声明式 layout 路径的 `line` 元素在 `TemplateInstantiator.materialize` 中**直接返回 null 被跳过**（代码注释：「v1 不渲染，但保留 instantiate 链路以待 v2+」）。解析阶段仍校验 `from` / `to` 必须为 `[x, y]`，但实例化时不产出任何 element。需要画线请用 raw_state 模式的 `path` 元素（运行时 8 种元素之一）。

### 4.6 icon 元素

```yaml
- type: icon
  source: warning          # 声明式 layout 模式：PNG 资源名（^[a-z0-9_-]{1,32}$；whitelist 防路径穿越）
  x: 0
  y: 0
  w: 32
  h: 32
  tint: "${line_color}"    # deprecated：染色（source-in 合成）；空 = 原色。新数据改用 fill
```

**声明式 layout 模式的 icon**（`TemplateElement.Icon`）：解析阶段对 `source` 做严格 whitelist 校验 `^[a-z0-9_-]{1,32}$`（`TemplateAssetService.SAFE_NAME`，仅 PNG）。运行期解析顺序：先 classpath `/template-assets/icons/<source>.png`（jar 内 builtin）→ 后 `plugins/HikariCanvas/assets/icons/<source>.png`（服主自定义）。找不到 → 占位虚线方框 + `?`，不阻塞渲染。前端通过 `/api/template-asset/icons/<source>.png` 拿真图。

**运行时 icon（raw_state 模式 / 编辑器内的 `IconElement`）source 规则**（代码出处 `state/IconElement.java`，`SOURCE_RE = ^[a-z0-9_-]+(/[a-z0-9_.-]+)?$`，长度 ≤ 64）：
- `fa-solid/<name>` / `fa-regular/<name>` / `fa-brands/<name>` — Font Awesome Free 矢量图标（如 `fa-solid/heart`）
- `material/<name>` — Material Symbols
- `user/<id>` — 用户自定义 SVG（`plugins/HikariCanvas/icons/<id>.svg`）
- 不含 `/` 的 legacy 形态（如 `heart`）— 走 PNG 兼容路径

**染色字段：`tint` 已 deprecated，主字段是 `fill`。** 矢量 path 填充走 `Fill` 联合类型（与 rect / circle / shape 共用）。模板若只写了 `tint`，实例化 / 反序列化时升级为 `SolidFill(tint)`；同时存在 `fill` + `tint` 时以 `fill` 为准。`fill == null` 表示用图标包默认色。新数据不应再写 `tint`。

后端 PNG 染色用 `AlphaComposite.SrcIn`，前端用 `globalCompositeOperation: 'source-in'`，**两端语义一致**。builtin PNG 图标示例：`info` / `warning` / `star` / `arrow_right`（32×32，纯白 alpha 形状）。

---

## 5. 参数声明（params）

模板暴露给用户填写的字段。编辑器会根据声明自动生成表单。

```yaml
params:
  name:
    type: string
    label: 站名
    description: 显示在牌子中央
    required: true
    default: "站名"
    max_length: 8
    placeholder: "例：人民广场"

  line_color:
    type: color
    label: 线路色
    default: "#E4002B"
    presets:
      - { label: 1号线红, value: "#E4002B" }
      - { label: 2号线绿, value: "#00A651" }
      - { label: 3号线黄, value: "#FFD200" }

  show_english:
    type: bool
    label: 显示英文站名
    default: false

  english_name:
    type: string
    label: 英文站名
    required: false
    max_length: 32
    visible_when: "show_english == true"
```

### 5.1 参数类型

| type | JSON 对应 | 附加字段 |
| --- | --- | --- |
| `string` | string | `max_length`, `min_length`, `placeholder`, `pattern` (正则) |
| `text` | string | 多行文本，`max_length` |
| `int` | int | `min`, `max`, `step` |
| `float` | number | `min`, `max`, `step` |
| `bool` | boolean | |
| `color` | `#RRGGBB` | `presets[]` |
| `enum` | string | `options: [{label, value}]` |
| `font` | string | 字体 ID（如 `source_han_sans`），下拉来自后端 `/api/font/list` |

> **全 8 种参数类型均已实装**（`TemplateLoader.ALLOWED_PARAM_TYPES`）。`option` / `presets` 的 `value` 可为字符串或数字（`TemplateParam.Option.value` 为 `Object`）。

### 5.2 通用字段

| 字段 | 说明 |
| --- | --- |
| `label` | 表单显示名 |
| `description` | 辅助说明文本 |
| `required` | 是否必填 |
| `default` | 默认值 |
| `visible_when` | 条件显示表达式（见 §6） |
| `group` | 表单分组名（UI 分节：TemplateGallery 按 `group` 字符串分节、首次出现顺序排序，section header 点击折叠；无 `group` 的 param 进默认组无标题） |

---

## 6. 表达式与插值

### 6.1 值插值

`${param_name}` 在字符串字段中插入参数值：

```yaml
content: "${name} 站"
fill: "${line_color}"
```

插值发生在**模板实例化时**，结果必须符合目标字段类型。

### 6.2 条件表达式（visible_when / visible）

文法（模板 `visible_when` 与脚本条件共用 `ExpressionParser`，代码出处 `template/expr/ExpressionParser.java`）：

```
or      := and ( "||" and )*
and     := equ ( "&&" equ )*
equ     := cmp ( ("==" | "!=") cmp )*
cmp     := add ( ("<" | "<=" | ">" | ">=") add )?     # 禁止连串
add     := mul ( ("+" | "-") mul )*
mul     := unary ( ("*" | "/") unary )*
unary   := "!" unary | "-" unary | primary
primary := "var" "(" string ")" | ident | number | string
         | "true" | "false" | "(" or ")"
```

优先级（高 → 低）：`! -`（一元）→ `* /` → `+ -` → 比较（`< <= > >=`）→ `== !=` → `&&` → `||`，与 C 系语言一致。**比较禁止连串**：`1 < 2 < 3` 直接 parse error（用 `&&` 拼接）。`var("user/score")` 解析为变量引用（脚本条件用；裸 `var` 是保留字，不允许当标识符）。除 `var` 外不支持函数调用、字段访问。

`==` / `!=` 语义（实现与脚本条件共用 `ExpressionEvaluator`）：
**双侧均为数值形态**（数字字面量或整串匹配 StrictNumber 文法的字符串）时走数值等值
（`"3.50" == 3.5` 为 true）；任一侧非数值形态走 Boolean truthy / 字符串等值链
（`"abc" == 0` 为 false）。

例：
```yaml
visible_when: "show_english == true && name != \"\""
```

### 6.3 尺寸百分比

`w: 100%` / `h: 50%` 表示相对**父容器**内容区的百分比。不支持嵌套父容器链上的复杂计算。父容器 = canvas 内容区（canvas pixel 尺寸减 padding 的 4 元数组），`stack` 与 `free` 一致；`stack` 内 element 的 `w/h` 默认撑满父容器，显式 N% 也按父容器算。

---

## 7. 实例化语义

当用户在编辑器中选模板 + 填参数 + 提交，后端执行：

1. **参数校验**：按 `params` 声明校验类型、范围、必填、正则
2. **计算画布尺寸**：若 `canvas.size: auto`，依据元素与约束算出最小符合的 `widthMaps × heightMaps`
3. **元素实例化**：
   - 插值所有字符串字段
   - 求值 `visible_when`
   - 展开百分比为绝对像素
   - 按 `layout.type` 计算每个元素最终 `x, y, w, h`
4. **转换为 ProjectState**：输出 `protocol.md §7` 定义的数据结构，推入当前 EditSession

**Apply 语义：replace**——`template.apply` 清空当前 wall 的 `elements` 列表 + 改 `canvas.background`，写入模板实例化产物。前端 UI 必须在调用前弹"应用会覆盖当前内容"二次确认。merge 语义（保留现有自由元素 + 叠加模板）留 v2+。

**Layout 实装范围：** `stack` + `free` + `grid`（`grid` 按 `columns × rows` 平铺、cell 尺寸 = `(content - (n-1)·gap) / n`，超容截断）。

**raw_state 模式实例化（§2.1）走另一条路径：** 跳过上述步骤 2-3 的尺寸计算与 layout 排布；尺寸 / canvas / element 全在内嵌 ProjectState 内自带，只做占位符替换 + element 安全二次校验后 replace。

**关联 walls 表：** apply 成功后服务端写入 `walls.template_id` 与 `walls.template_version`，便于「网页首页 / `/canvas list` 显示该 wall 出自哪个模板」。但模板**不是**运行时活对象——实例化后即转为普通工程数据，玩家可任意自由编辑，修改不影响源模板，亦不影响 `template_id` 字段（保留作 audit）。

---

## 8. 版本兼容

### 8.1 spec 升级规则

`spec: 1` 是 v1.0 基线。

**可以不升 spec 的变更：**
- 新增可选字段
- 新增元素类型
- 新增参数类型
- 新增字段的可选值

**必须升 spec 的变更：**
- 修改字段语义
- 删除字段
- 改变必填性
- 改变默认值

遇到 `spec > 当前插件支持的最高版本` 的模板 → 不加载 + warn log。

### 8.2 模板版本字段

`version` 由模板作者维护，编辑器在模板库展示，供服主追踪更新。插件不据此做任何行为。

---

## 9. 校验规则

解析模板时的校验（失败则该模板不加载，不影响其他）：

- `spec` 必须为当前支持的版本（≤ `SUPPORTED_SPEC = 1`，且 > 0）
- `id` 匹配 `^[a-z][a-z0-9_-]{2,63}$`（含 `-`）
- `name` 非空，长度 ≤ 64
- `canvas` 在声明式 layout 模式必填，raw_state 模式可省略
- `canvas.maps` / `canvas.min_maps` / `canvas.max_maps` 各维度 1~16；`size: fixed` 时 `maps` 必填
- `canvas.padding` 为单 int 或 `[int,int,int,int]`
- 颜色字段（含 `${param}` 时跳过格式判，留实例化期）匹配 `^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$`
- 参数 `id` 匹配 `^[a-z][a-z0-9_]{0,31}$`
- 参数 `type` ∈ `{string, text, int, float, bool, color, enum, font}`
- 参数 `enum` 的 options 非空
- `layout` 在声明式 layout 模式必填且 `elements` 非空；raw_state 模式可省略
- `layout.type` ∈ `{stack, free, grid}`；`grid` 需 `columns ≥ 1` 且 `rows ≥ 1`
- 所有 `${param_name}` 引用的参数必须已声明（含 `content` / `font` / `color` / `fill` / `effects.*.color` / icon `source` / stroke `width` 等可插值字段）
- `visible_when` 表达式可解析

---

## 10. 示例：完整模板

```yaml
spec: 1
id: subway_station
name: 地铁站牌
description: 标准地铁站风格，横排，白底黑字，上方线路色条
version: 1
author: "hikari-canvas-official"
tags: [sign, transit, cjk]

canvas:
  size: auto
  min_maps: [3, 1]
  max_maps: [8, 2]
  background: "#FFFFFF"
  padding: [8, 8, 8, 8]

params:
  name:
    type: string
    label: 站名
    required: true
    max_length: 8
  line_color:
    type: color
    label: 线路色
    default: "#E4002B"
    presets:
      - { label: 1号线红, value: "#E4002B" }
      - { label: 2号线绿, value: "#00A651" }
  show_english:
    type: bool
    label: 显示英文
    default: true
  english_name:
    type: string
    label: 英文站名
    required: false
    max_length: 32
    visible_when: "show_english == true"

layout:
  type: stack
  direction: vertical
  gap: 4
  elements:
    - type: rect
      w: 100%
      h: 12
      fill: "${line_color}"

    - type: text
      content: "${name}"
      font: source_han_sans
      size: 48
      color: "#000000"
      align: center

    - type: text
      content: "${english_name}"
      font: source_han_sans
      size: 16
      color: "#666666"
      align: center
      visible_when: "show_english == true"
```

---

## 11. 模板包（v2.0）

规划中：服主/玩家可将多个模板打包为 `.canvas` 压缩包，包含：

```
subway-pack.canvas  (zip)
├── pack.yml           # 包元信息（作者、license、模板列表）
├── templates/
│   ├── station.yml
│   ├── entrance.yml
│   └── line-map.yml
├── fonts/             # 可选：包内自带字体
│   └── metro-sans.woff2
└── icons/             # 可选：包内图标
    └── subway.png
```

v1.0 不实现，`pack.yml` 字段设计在 v2.0 专项讨论。

---

## 12. 未决问题

- [ ] 是否允许模板间继承（`extends: base_template`）—— v2+
