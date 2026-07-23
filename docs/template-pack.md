# 模板包（.canvas pack）——模板系统统一到工程格式

**定位**：把模板系统从独立的 YAML DSL（声明式 layout + raw_state 两模式）整体迁到 `.canvas`
工程格式，`manifest.kind = "pack"`。模板 = 一个 `.canvas` 包 + 一层参数定义。套用即
「参数替换后走工程导入管线」，因此模板天然支持时间轴动画、积木脚本、图片资产、跨服分享。

**契约关联**：`docs/import-export.md`（`.canvas` 格式 / 导入管线 / 安全栈）·
`docs/template-spec.md`（旧模板 DSL，本设计落地后整体作废）· `docs/data-model.md §4`
（manifest 字段）· `docs/security.md`（导入安全栈复用）。

**实施前必读本文。**

---

## 1. 背景

`.canvas`（0.8）已是完整工程容器：`project.json` 存完整 `ProjectState`（含 `timelines`）、
`scripts.json` 存积木脚本、`assets/` 存图片，配三闸解包 + 路径白名单 + materialize 重校验 +
script 重校验 + spec 版本兼容链。

模板系统（M6 起）是并行的另一套：YAML DSL + `TemplateInstantiator` + 独立安全校验。它的
`Result.Ok` 只透传 `elements + bg + 尺寸`，**结构上没有 timeline / script 通道** —— 模板永远
无法带动画和脚本。raw_state 模式本质是「手写的、阉割掉 timeline/script 的 ProjectState」，与
`.canvas` 的 `project.json` 高度重复。

`import-export.md` D1 决策当年已给模板包预留位置：`manifest.kind` 保留 `"pack"` 值位、本期
只做 `"project"`。本设计实装 `"pack"`。

模板独有、`.canvas` 没有的只有一件：**参数化**（`${param}` + 参数定义 + 套用时填值）。统一的
全部工作量就集中在「把参数化叠加到 `.canvas` 之上」。

---

## 2. 固化决策

| # | 项 | 决策 | 理由 |
|---|---|---|---|
| **D1** | 统一方向 | 模板 = `.canvas` pack（`kind="pack"`）。旧 YAML DSL 两模式**整体退役** | pre-1.0 是删旧路径的最后窗口；不留双轨、不留半个轮子 |
| **D2** | 参数注入点 | `ProjectImporter.importInto`：manifest 解析后、`materialize` 前，对 `project.json` bytes 做 `${param}` 替换 | 复用 materialize 之后的全部管线（元素校验 / asset / script / timeline / 广播），参数化只是一个前置 pass |
| **D3** | 参数化范围 | **全字段**：`project.json` 里任意字符串位置可写 `${param}`（含坐标 / 尺寸 / 颜色 / 渐变 stop / 字体） | 手写 pack 时想参数化什么都行；替换在 JSON 文本层，不挑字段 |
| **D4** | 参数语法 | `${param}` 沿用（`[a-z][a-z0-9_]{0,31}`，无冒号）。运行时变量 `${var:X}`（有冒号）天然不冲突，二者可共存 | 已验证：模板参数正则不吃冒号，`${var:X}` 原样穿透到运行期解析 |
| **D5** | 参数定义 | pack 内新增 `params.json`：`[{ id, type, label, default, ... }]`，沿用现有 `TemplateParam` 的 type 体系（string/text/color/enum/bool/font） | 参数 UI 与校验逻辑现成，直接搬 |
| **D6** | 存为模板 | 保留。玩家「存为模板」= 前端 `useProjectExport` 导出一个 pack + 参数标记 UI | 复用导出管线；参数标记是 `SaveAsTemplateModal` 的扩展 |
| **D7** | 内置模板 | jar `/templates/*.canvas`（zip）。作者手写工程 → 导出 pack → 入库。`_index.txt` 保留为展示顺序清单 | 内置模板也是 pack，无特例路径 |
| **D8** | 迁移 | 现有 7 内置 YAML 模板不迁移，作者重做为 pack。用户模板（`templates` 表 + `user-templates/`）pre-1.0 清空重来（pre-1.0 激进改 schema OK，见 data-model §6.6） | 旧模板设计本就要重做；用户模板量小、非冻结契约 |
| **D9** | 安全 | 参数替换后走 `materialize` 重校验（复用）。参数值套用 `Interpolator` 的 16KB/1MB 上限。pack 解包三闸复用。参数值不得再引入 `${param}`（单遍替换，不递归） | 不新增安全面；参数替换后的 JSON 与普通导入的 project.json 走同一校验 |
| **D10** | 预览 | `TemplatePreviewService` 改渲 pack：参数用 default 值替换 → `${var:X}` 走 fallback 收敛（已实装 `previewResolve`）→ rasterize | 缩略图 = 「默认参数 + 无数据源」的样子 |

---

## 3. 数据格式

pack 是一个 `.canvas` zip（与 project 同扩展名，靠 `manifest.kind` 区分）：

```
my_template.canvas
├── manifest.json      # kind="pack" + name + wall{w,h} + spec
├── project.json       # 完整 ProjectState，字符串位置可含 ${param}
├── params.json        # 参数定义数组（pack 独有）
├── scripts.json       # 可选：积木脚本
└── assets/*.png       # 可选：图片资产
```

**`params.json`**：

```jsonc
[
  { "id": "station",     "type": "string", "label": "站名", "default": "人民广场", "max_length": 8 },
  { "id": "line_color",  "type": "color",  "label": "线路色", "default": "#E4002B",
    "presets": [ { "label": "1 号线红", "value": "#E4002B" } ] },
  { "id": "show_footer", "type": "bool",   "label": "显示落款", "default": true }
]
```

字段与现有 `TemplateParam` 一致（`type` / `label` / `default` / `required` / `max_length` /
`presets` / `group` / `visible_when`）。

**`project.json` 里的占位符**：任意字符串字段，例如
`"text": "${station}"`、`"fill": "${line_color}"`、`"x": "${offset_x}"`（数值字段以字符串
形态写占位符，替换后 materialize 解析回数值）。

---

## 4. 套用管线（apply）

```
template.apply(templateId, params)
  → 取 pack bytes（内置 jar / 用户存储）
  → CanvasArchive.unpack（三闸）
  → CanvasManifest.parse（kind=pack 校验 + spec 兼容）
  → 【新】params.json 校验用户填值（复用 TemplateParam 校验）
  → 【新】对 project.json bytes 做 ${param} 替换（复用 Interpolator，含上限）
  → ProjectMaterializer.materialize（元素重校验 + 尺寸匹配）—— 与普通导入同一入口
  → asset 摄入 / 孤儿轨丢弃 / replaceProject（含 timeline）/ scripts 导入（重绑+重校验）
  → 快照广播 + 地图重绘
```

D2/D4 的价值：`template.apply` 与 `.canvas` 导入**收敛到同一条 `ProjectImporter` 管线**，只在
manifest 解析后多一个「校验参数 + 替换占位符」的前置段。

---

## 5. 交叉导入与入口语义

**唯一判别符**：`manifest.kind`（`"project"` / `"pack"`）。两种文件同为 `.canvas`、同扩展名，
靠此字段区分，不靠扩展名或文件名。

**入口矩阵**：

| 入口 | 正常输入 | 产出 / 去向 |
|---|---|---|
| 导出工程（右上角） | 当前工程 | `.canvas`（kind=project，当前快照，无参数） |
| 存为模板 | 当前工程 | `.canvas`（kind=pack，带 `params.json` + `${param}` 标记） |
| 普通导入（右上角） | project 文件 | 导入到当前墙 |
| 模板导入（模板库） | pack 文件 | 存入模板库（可反复套用） |
| 模板套用（Gallery） | 库内 pack | 填参数 → 当前墙 |

**pack ⊃ project**：pack 去掉 params 层、`${param}` 用 default 替换 = 一个合法 project；
project = 零参数 pack。二者可互转，故交叉导入**宽容处理、双向不报错**：

| 场景 | 处理 |
|---|---|
| 普通导入遇 **pack** | 用 default 参数实例化后当工程导入本墙；顶部**非阻塞提示**：「这是一个模板文件，可在模板导入页导入以获得更好的展示与编辑体验」引导去模板入口 |
| 模板导入遇 **project** | 当零参数模板处理（无参数可填，套用即原样）入库 |

设计哲学：文件通用、分享无摩擦；kind 差异靠**提示透明传达**，不靠拦截。提示文案是 i18n key，
实施时定稿。

---

## 6. 退役 / 改造清单

**退役（删）**：
- `TemplateInstantiator`（声明式 layout + raw_state 两路径）
- `TemplateLoader`（YAML 解析）
- `TemplateElement` / `TemplateLayout` / `TemplateCanvas` / `TemplateEffects`（声明式 DSL 类型）
- `template/expr/Interpolator`（并入参数替换 pass；正则与语义保留）
- `docs/template-spec.md`（旧 DSL 契约，整体作废或改为「已废」存档）

**改造**：
- `TemplateRegistry`：从读 `*.yml` 改读 `*.canvas`（解 manifest + params.json 供 Gallery）
- `TemplatePreviewService`：渲 pack（default 参数替换 → materialize → rasterize）
- `TemplateOpDispatcher.template.save`：存 pack（前端导出的 pack bytes 落库 / 落盘）
- `EditOpDispatcher.template.apply`：走 §4 管线
- `TemplateExporter` / `TemplatePublisher`：产出 pack + params.json
- 数据表 `templates`：blob 从 YAML 文本改为 pack bytes（schema 调整）

**复用（不动）**：
- `ProjectImporter` / `ProjectMaterializer` / `CanvasArchive` / `CanvasManifest` / `AssetIngest` /
  `ScriptImporter` / `MissingResourceScanner`
- `TemplateParam`（参数类型 + 校验）
- 前端参数填写 UI、`useProjectExport` / `useProjectImport`

**前端改造**：
- `TemplateGallery`：从 pack manifest + params.json 渲染卡片；预览走后端 pack 缩略图
- `SaveAsTemplateModal`：加「标记可参数化字段」步骤（全字段 → 生成 params.json + 把选中字段值
  替换为 `${param}` 写进导出的 project.json）
- `stores/templates` / `types/template`：类型对齐 pack

---

## 7. 分期（草案，实施前细化）

- **P1 后端管线** ✅（2026-07-22，0.9.16）：`PackParamResolver`（`params.json` 解析 + 校验 + `${param}`
  替换）；`ProjectImporter` 拆 build/propagate + 新 `applyPack`；`CanvasManifest` 接受 `kind=pack`；
  `TemplateRegistry` 三源读 `*.canvas`；`EditOpDispatcher.template.apply` 命中 pack 重路由。additive——YAML
  路径不动。端到端由测试 fixture 打通（内置 pack 待作者手工制作后于 P4 预置）。
- **P2 存储 + 存为模板** ✅（2026-07-23，0.9.16）：`TemplateExporter` 产 pack（project.json +
  params.json + manifest，含自校验 roundtrip）；`TemplatePublisher` 存 `<slug>.canvas`；pack manifest
  自声明 `id` 使 registry 条目 id == DB `template_id`；`PackParamResolver.substitute` 值按 JSON 转义
  （连带硬化 P1 applyPack）。**表 schema 不动**——`yaml_path` 列改名被 forward-only 守卫（V18+）挡下，
  沿用旧列名存 `.canvas` 路径。
- **P3 预览 + 前端**：`TemplatePreviewService` 渲 pack；`TemplateGallery` / `SaveAsTemplateModal`
  改造 + 参数标记 UI。
- **P4 退役 + 内置模板**：删旧 DSL 全套；作者手写内置 pack 入库；`template-spec.md` 归档。
- **P5 收尾**：迁移说明、测试、文档同步（import-export.md kind=pack 落地、data-model 表结构）。

---

## 8. 未决问题（实施时回填）

- [ ] `params.json` 的 `visible_when` 表达式在 pack 里怎么求值（现有声明式模式在实例化期求值；
      pack 走 import 管线，可能需要在参数替换 pass 里先算可见性再决定占位符替换 / 元素保留）
- [x] 数值字段占位符（`"x": "${offset_x}"`）替换后 materialize 能否解析回数值 —— **已验证**
      （2026-07-22 探针）：`materialize` 用默认 `ObjectMapper`，Jackson scalar coercion 默认开，
      `"5"`→int 5、`"45"`→double 45.0、`"0.0"`→stop position 0.0 均正确；非数字串（`"abc"`）被
      materialize 拒绝归 `IMPORT_MALFORMED`；空串退 0（故数值参数的 `default` 须给合法值）。
      **参数替换可为纯文本 pass，不挑字段类型，无需类型感知。**
- [x] `templates` 表 blob 改 pack bytes 后，旧行处理 —— **已定**（D8）：pre-1.0 清空重来，不写迁移器
- [ ] pack 缩略图缓存 key（现按 templateId + 内容 hash？pack bytes 变则失效）
