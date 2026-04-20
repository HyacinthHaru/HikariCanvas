# 模板 YAML 规范

**状态：** 立项稿 v0.1 · 2026-04-19
**格式版本：** `1`
**文件扩展名：** `.yml`（或 `.yaml`）
**适用范围：** 所有内置模板、服务器自定义模板、社区分享模板

本文档定义模板的 YAML 结构。模板是用户面的重要扩展点，**格式一旦在 v1.0 发布就不再做破坏性变更**；新字段只能以向后兼容方式加入。

---

## 1. 文件位置与加载

| 来源 | 路径 |
| --- | --- |
| 内置模板 | jar 内 `resources/templates/*.yml`（只读） |
| 服务器模板 | `plugins/HikariCanvas/templates/*.yml` |
| 玩家模板（v1.x 后） | `plugins/HikariCanvas/user-templates/<uuid>/*.yml` |

加载顺序：**内置 → 服务器 → 玩家**。同 `id` 后加载覆盖前者，允许服主覆盖内置模板。

启动时全部扫描解析，失败的单个模板记 warn log，不影响其他模板加载。`/canvas reload templates` 热重载。

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
preview: "previews/subway_station.png"   # 编辑器缩略图（可空）

canvas:                     # 画布定义
  ...

params:                     # 参数声明
  ...

layout:                     # 布局与元素
  ...
```

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `spec` | ✅ | int | 格式版本，当前 `1`。插件拒绝更高 spec |
| `id` | ✅ | string | 小写 + 下划线 + 数字，长度 3~64 |
| `name` | ✅ | string | UI 显示名，支持 Unicode |
| `description` | | string | |
| `version` | | int | 模板内容版本，服主自管 |
| `author` | | string | |
| `tags` | | string[] | 编辑器筛选用 |
| `preview` | | string | 相对模板文件的路径 |
| `canvas` | ✅ | object | 见 §3 |
| `params` | | map | 见 §5 |
| `layout` | ✅ | object | 见 §4 |

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
| `background` | color | `"#FFFFFF"` | |
| `padding` | int / `[int,int,int,int]` | `0` | 单值等同四值相同 |

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
- type: text                # text | rect | line | icon
  id: title                 # 元素局部 id（可空，系统自动生成）
  x: 0                      # free 时必填；stack/grid 忽略
  y: 0
  w: auto                   # "auto" 或具体像素，auto 由内容决定
  h: auto
  rotation: 0               # 0 | 90 | 180 | 270
  visible: true             # 支持 ${param}
  z_order: 0
```

### 4.3 text 元素

```yaml
- type: text
  content: "${name}"         # 支持参数插值
  font: sourcehan            # 引用 config.yml 中的字体 ID
  size: 48
  color: "#000000"
  align: center              # left | center | right
  line_height: 1.2
  letter_spacing: 0
  vertical: false
  effects:
    stroke:
      width: 1
      color: "#FFFFFF"
    shadow:
      dx: 2
      dy: 2
      color: "#808080"
    glow:
      radius: 4
      color: "#FFD700"
```

### 4.4 rect 元素

```yaml
- type: rect
  w: 100%                   # 百分号表示相对父容器
  h: 16
  fill: "${line_color}"
  stroke:
    width: 1
    color: "#000000"
```

### 4.5 line 元素

```yaml
- type: line
  from: [0, 0]
  to: [100, 0]
  width: 2
  color: "#000000"
```

### 4.6 icon 元素（v1.x）

```yaml
- type: icon
  source: "icons/subway.png"   # 模板包内路径
  w: 32
  h: 32
  tint: "${line_color}"
```

v1.0 不实现，保留字段定义避免后续破坏兼容。

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
| `font` | string | 字体 ID，下拉来自 config |

### 5.2 通用字段

| 字段 | 说明 |
| --- | --- |
| `label` | 表单显示名 |
| `description` | 辅助说明文本 |
| `required` | 是否必填 |
| `default` | 默认值 |
| `visible_when` | 条件显示表达式（见 §6） |
| `group` | 表单分组名（UI 分节） |

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

支持极简表达式子集：

```
<expr> := <term> (( "==" | "!=" | "&&" | "||" ) <term>)*
<term> := <ident> | <literal> | "(" <expr> ")" | "!" <term>
<literal> := <string> | <number> | "true" | "false"
```

不支持函数调用、算术、字段访问。

例：
```yaml
visible_when: "show_english == true && name != \"\""
```

### 6.3 尺寸百分比

`w: 100%` / `h: 50%` 表示相对**父容器**内容区的百分比。不支持嵌套父容器链上的复杂计算。

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

模板**不是**运行时活对象——实例化后即转为普通工程数据，玩家可任意自由编辑，修改不影响源模板。

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

- `spec` 必须为当前支持的版本
- `id` 匹配 `^[a-z][a-z0-9_]{2,63}$`
- `name` 非空，长度 ≤ 64
- `canvas.maps` / `canvas.min_maps` / `canvas.max_maps` 各维度 1~16
- 颜色字段匹配 `^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$`
- 参数 `id` 匹配 `^[a-z][a-z0-9_]{0,31}$`
- 参数 `enum` 的 options 非空
- 所有 `${param_name}` 引用的参数必须已声明
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
      font: sourcehan
      size: 48
      color: "#000000"
      align: center

    - type: text
      content: "${english_name}"
      font: sourcehan
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

- [ ] 是否允许模板间继承（`extends: base_template`）
- [ ] `grid` 布局细节（固定单元尺寸 vs 自适应）
- [ ] 参数组（`group`）的 UI 表现细节
- [ ] 模板 `preview` 图片尺寸与格式规范
- [ ] 百分比计算在 `stack` 布局下的父容器定义
