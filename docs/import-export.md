# 工程导入导出 与 SVG 导入（0.8）设计总纲

> **定稿 2026-06-16（brainstorming D1-D10 用户拍板）。** 本文件是 0.8 的契约总纲。
> 它把 `data-model.md §4`（`.canvas` 工程文件格式，此前标「规划中·未实装」）**升级为实装定稿**，
> 并新增 SVG 导入契约。配套下游契约（实装时按本文回填）：
> `data-model.md §4`、`security.md §4.4 / §4.7（新增）/ §13.5`、`protocol.md`（新端点）、`rendering.md`（SVG 坐标映射）。
>
> **写代码前必读本文。要改契约 → 先改本文，再改代码。**

一句话：让玩家的作品能**走出去**（导出 `.canvas` 分享 / 备份）和**走进来**（导入 `.canvas` 工程、导入 SVG 素材）。
`.canvas` 导入导出是 1.0 的最大硬前置；SVG 导入是增值新功能。

时间轴（0.6）回答「什么时间显示什么」、脚本（0.7）回答「什么条件下做什么」；
导入导出（0.8）回答「作品**怎么搬进搬出**」。

---

## 0. 决策摘要（固化后不可越界）

| # | 决策 | 结论 | 理由速记 |
|---|---|---|---|
| **D1** | 0.8 范围 | **两个功能**：①`.canvas` 工程文件导入/导出 ②SVG 矢量导入。不含其它新功能 | 长期评估收敛：核心能力链（招牌→模板→变量→时间轴→脚本）已是完全体，仅剩这两件「搬进搬出」 |
| **D2** | `.canvas` 格式 | **zip 多文件**（沿用 `data-model.md §4.1` 骨架）：`manifest.json` + `project.json` + **`scripts.json`** + `thumbnail.png`（可选）+ `assets/` | 体积小、可人工检视、缩略图独立；spec 与安全闸已写好，改方案=推翻两份契约 |
| **D3** | `.canvas` 含脚本 | **新增 `scripts.json`**（修正现有 §4.3「只导 ProjectState」的缺口） | 脚本独立存 `wall_scripts` 表、不在 ProjectState（脚本总纲 D7）；0.7 已是脚本时代，导出不含脚本=丢交互逻辑。**用户 AskUserQuestion 拍板：纳入** |
| **D4** | 前后端职责 | **导出=前端**（`fflate` 纯客户端打包下载）；**导入=后端**（信任边界，zip 安全闸全在 Java 侧） | 导出贴合 §4.5「不经服务器」；导入是攻击面，zip 炸弹/路径穿越/magic 校验必须在后端 |
| **D5** | SVG 导入路线 | **矢量解析成原生 `PathElement`**（不新增元素类型）；**动画 SVG 取首帧静态化** | `PathElement`/`Fill`(渐变)/双端 `PathParser` 全现成；栅格化反而受 magic-bytes 制约且丢核心卖点。**用户拍板：只做矢量完整版** |
| **D6** | SVG 解析位置 | **前端 `DOMParser` 解析 + 归一化到 `PathElement.d` 可渲染子集**；**后端零新解析器**（`PathParser.java` 已支持全 SVG path 文法） | 契合「无 SVG 库 / 纤瘦 jar」纪律；否决 Batik（体积大、XXE/SSRF CVE 史长） |
| **D7** | 孤儿关键帧轨 | **丢弃 + `log warn`**（定稿 `data-model.md §4.4` 留给 §10 的待定项） | 坏 elementId 引用进运行期会让 Ticker 空插值；丢弃最干净 |
| **D8** | 变量随工程走 | `user:` 变量**带定义、不带运行期当前值**；`userglobal/*` **不带**（既定纪律，跨服无意义→fallback `"???"`） | 定义让占位符有 default 兜底而非直接 `"???"`；当前值是临时态 |
| **D9** | SVG `fill-rule` | 导入时**按 SVG 显式承载**（SVG 默认 `nonzero`，项目 PathRenderer 多处默认 `even-odd`） | 不显式设会让「带洞图形」渲染错误——真实保真度坑 |
| **D10** | SVG 不做（防膨胀） | 不支持：`<text>` / `clipPath` / `mask` / `<filter>` 滤镜 / SMIL+CSS **动画** / `<foreignObject>` / 外部 `href` 引用 / `<use>`·`<symbol>` / CSS `@keyframes` 解析 | 这些要么无对应能力、要么安全面大、要么 scope 爆炸；留后续版本 |

---

## 1. 目标与范围

### 1.1 做 / 不做

**做（0.8）：**
- `.canvas` **导出**：前端把当前工程（含时间轴 + 脚本 + 引用图片）打成 zip 下载
- `.canvas` **导入**：后端安全解包 + 校验 + 灌入当前会话墙 + 广播 `state.snapshot`
- **SVG 导入**：前端解析静态 SVG → 一组 `PathElement`（+ 内嵌位图 → `ImageElement`）插入当前工程
- 导入冲突 / 降级的用户提示（缺字体 / 缺变量 / 缺图标 / 被 block 脚本 / 动画已静态化）

**不做（明确砍掉，防 scope 膨胀）：**
- `.canvas` **跨墙合并 / 增量导入**——导入语义是「整体替换当前会话工程」，非 merge
- `.canvas` 服务端存储 / 工程库 / 云同步——导出纯客户端落盘，分享靠玩家自己传文件
- SVG **动画 → 时间轴**（见 §7 风险登记的专项说明）——动画 SVG 一律取首帧静态化
- SVG 的 D10 全部不支持项（text/clip/mask/滤镜/动画/foreignObject/外链/use）
- `manifest.kind = "pack"`（资源包 / 模板包）——保留字段位，本期只做 `"project"`
- 导出「服务端权威 state」模式——本期导出取前端 mirror，导出前强制同步一次（见 §10）

### 1.2 MVP 定义（两个功能各一闸）

- **`.canvas` MVP**：编辑器导出一个工程 → `.canvas` 文件 → 再导入回来，**ProjectState + 时间轴 + 脚本 roundtrip 还原**，后端 zip 安全闸全到位。
- **SVG MVP**：导入一个静态 SVG（含 `<path>` + 基本形状 + `fill`）→ 得到一组**可编辑、可缩放、可绑变量**的 `PathElement`，前后端渲染一致（snapshot CI 绿）。

---

## 2. 数据结构与格式

### 2.1 `.canvas` 文件结构（实装定稿，更新 `data-model.md §4.1`）

```
mysign.canvas                  （zip 包，扩展名 .canvas）
├── manifest.json              # 必选：元信息 + spec 版本
├── project.json               # 必选：完整 ProjectState（layers/elements/timelines/canvas）
├── scripts.json               # 可选：ScriptRule[]（D3 新增；无脚本则省略）
├── thumbnail.png              # 可选：预览缩略图 256×128
└── assets/
    ├── <sha256[:16]>.png      # 工程引用的上传图片（内容寻址，与服务端命名一致）
    └── icons/<id>.svg         # 工程引用的用户自定义图标（source=user/<id>）
```

- `assets/` 嵌**值**（图片/图标二进制随包走，跨服可用）；**字体不嵌**（只存 `fontId` 引用，缺失降级默认字体）。
- 老导入器遇到未知顶层条目（如未来的 `scripts.json`）应**忽略**而非报错——前向兼容。

### 2.2 `manifest.json`（沿用 §4.2 + 补充）

字段沿用 `data-model.md §4.2`（`spec` / `kind` / `created_at` / `created_by` / `server` / `plugin_version` / `name` / `wall{width,height}` / `template_origin`）。补充约束：

- `spec`：独立整数（`data-model.md §6.1`）。**0.8 首发 = `spec: 1`**（此前从未真实导出过文件，1 即首个实装格式）。
- 导入时 `spec > 当前支持` → 拒绝 `IMPORT_SPEC_UNSUPPORTED` + 提示升级插件；`spec < 当前` → 走 ProjectState 既有 v1→v2→v3 `@JsonCreator` 兼容链自动升级（已实装，免新代码）。
- manifest 全部字符串字段渲进编辑器时走 Vue 默认转义（防 T10 XSS，`security.md §4` 威胁表）；禁 `v-html`。

### 2.3 `scripts.json`（D3 新增）

```jsonc
// scripts.json = 一个 JSON 数组，每项是一条 ScriptRule（见 scripting.md §2.1）
[
  {
    "id": "sr-1a2b3c4d",
    "wallId": "<导出时的 wallId>",   // 导入时由后端重绑到当前会话墙，见下
    "enabled": true,
    "name": "比分牌",
    "trigger": { "type": "playerKill" },
    "actions": [ /* … Action 树，含 If 嵌套 … */ ],
    "blockLayout": "<前端积木摆放坐标 JSON，后端原样存取>"
  }
]
```

- **`wallId` 重绑**：导出时记录的 `wallId` 在目标服无意义；导入时后端把每条规则的 `wallId` 改写为**当前会话墙的 wallId**，再写入 `wall_scripts` 表（脚本归墙，脚本总纲 D7）。
- **命令模板按名引用**：`runCommand.templateId` 在目标服 `config.yml` 不存在 → `CommandTemplateEngine` 返 `Blocked`，编辑器红 badge 灰显、不可执行，**规则其余照常**（`security.md §13.5`，已写无需新代码）。
- **积木重校验**：导入的 `trigger` / `action` 类型、参数范围、`if.condition` 语法**一律按生产路径重新服务端校验**（不信任文件内的 rule_json），复用脚本 create/update 的校验栈（`security.md §13.5` K16 等）。

### 2.4 SVG 导入产物（D5：映射到 `PathElement`）

前端把一份 SVG 文档解析成一组**原生元素**插入当前工程：

| SVG 输入 | 映射到 | 说明 |
|---|---|---|
| `<path d>` | `PathElement` | `d` **归一化到可渲染子集** M/L/Q/C/Z（H/V→L，S/T→C/Q，A 椭圆弧→cubic 近似），见 D6 + §3.3 |
| `<rect> <circle> <ellipse> <line> <polyline> <polygon>` | `PathElement`（转 `d`）或现成 `RectElement`/`CircleElement` | 基本形状先统一转 path，简单矩形/正圆可直映现成元素 |
| `fill`（纯色 / `linearGradient` / `radialGradient`） | `PathElement.fill`（`Fill` 联合，渐变现成） | 渐变 stop / gradientUnits 映射到项目 `LinearGradient`/`RadialGradient` |
| `stroke`（color + width） | `PathElement.stroke`（`Stroke`） | 项目 `Stroke` 仅 width+color；dasharray/cap/join/渐变描边丢失 |
| `transform`（translate/scale/rotate/matrix） | **烘焙进 `d` 坐标** | 不引入元素级 transform 字段；解析时算成画布绝对坐标 |
| `opacity` | `PathElement.opacity` | 元素级现成字段 |
| `<image href=data:…>` 内嵌位图 | `ImageElement` | 抽出 base64 → 走现有 `/api/upload` 校验/存储栈（§3.3） |
| `fill-rule` | 随 `PathElement` 承载（D9） | SVG 默认 nonzero；不显式设带洞图形会错 |

- **不新增元素类型**：零新数据模型、`.canvas` / `project_json` 序列化形态不变、双端一致天然成立。
- 一份 SVG 导入 = 多个元素**成组**插入（落在新建图层或当前图层），共享一次撤销栈条目。

---

## 3. 处理管线

### 3.1 导出流程（前端，D4）

```
1. 取当前 ProjectState（useProjectStore().state；导出前强制 sync 一次，§10）
2. 渲染缩略图 256×128（复用 LayerThumbnailRenderer / PreviewRenderer）→ thumbnail.png
3. 收集引用资源：扫 element 里的 image hash（去重）+ user/<id> 图标 → fetch 字节塞 assets/
4. 序列化：project.json = ProjectState；scripts.json = 该墙 wall_scripts（若有）；manifest.json 填充
5. fflate 打 zip → Blob → a.download 触发下载（无服务器往返）
```

### 3.2 导入流程（后端，D4 — 信任边界）

```
1. 前端选文件 → POST multipart 到 /api/project/import（复用 /api/upload 多部分范式）
2. 后端【zip 安全解包】流式边解边计数：包≤10MiB / 单文件解压≤10MiB / 总解压≤50MiB
   + 条目名安全校验（无 ../、无绝对路径、无符号链接）+ 白名单条目（§5.1）
3. 解析 manifest → spec 兼容校验（§2.2）
4. project.json → ProjectState（复用 @JsonCreator + ElementValidator/FillValidator/PathDValidator/StrictNumber，
   不信任文件内任何数值；w/h clamp ≤ canvas-max-maps）
5. 尺寸匹配：超当前会话墙尺寸 → IMPORT_SIZE_MISMATCH 中止，提示开匹配尺寸新会话（§4.4 现状）
6. assets/*.png 逐个走 magic + ImageIO 隔离解码（200ms 超时）+ 落 hash 存储（复用 §4.5 校验栈）；
   assets/icons/*.svg 走 SVG 清洗（§5.3）后落 icons 目录
7. scripts.json：wallId 重绑当前墙 + 积木重校验（§2.3）→ 写 wall_scripts
8. 孤儿关键帧轨丢弃 + warn（D7）；扫缺字体/变量/图标产出 warning 清单
9. 灌入会话 ProjectState + 广播 state.snapshot + audit PROJECT_IMPORT
10. 前端收 snapshot 全量刷新 + 展示 warning 清单
```

### 3.3 SVG 解析流程（前端，D6）

```
1. 文件大小预闸（≤ svg-import-max-kb，默 512）+ 拒含 <!DOCTYPE/<!ENTITY 的源串（§5.3）
2. new DOMParser().parseFromString(svg, 'image/svg+xml')  ← 不取外部实体、不执行脚本
3. 剥离危险节点：<script> / <foreignObject> / on* 属性 / href^=javascript: / 外部 <image href=http…> / <use>外链
4. 遍历 DOM：basic shapes → path d；逐节点累乘 transform 矩阵 + viewBox→目标尺寸 scale，烘焙进 d 坐标
5. d 归一化到 M/L/Q/C/Z 子集（A/S/T/H/V 展开；A→cubic 复用后端 PathParser F.6 算法移植）
   ← 为何归一化：PathElement 前端预览走 render/PathParser.ts（M/L/Q/C/Z 子集），后端 PathParser.java 虽支持全文法，
      但统一归一化才能双端一致 + 复用现有 PathElement 渲染
6. fill/stroke/opacity/fill-rule 映射（§2.4）；动画节点（<animate>/CSS）→ 取 t=0 静态值后丢弃
7. <image> base64 → 经 /api/upload 落 hash → ImageElement
8. 成组插入 element（走现有 element 创建 op，后端再校验一遍）
9. 节点数 / 顶点数超闸 → 前端熔断提示（§5.3）
```

---

## 4. 协议（新增端点 + 错误码）

| 端点 / op | 方向 | 用途 |
|---|---|---|
| `POST /api/project/import` | HTTP multipart | 导入 `.canvas`（大二进制不走 WS——`security.md` WS 上限 1 MiB）。后端解包校验后经 WS `state.snapshot` 推全量 |
| 导出 | 纯前端 | 无端点（fflate 客户端打包 + 下载）；取图片字节复用现有图片下载端点（实现期核对签名） |
| SVG 导入 | 纯前端 + 现有 op | 前端解析后走**现有 element 创建 op**插入；内嵌位图走现有 `POST /api/upload` |

**导入错误码**（`protocol.md` 回填）：

| code | 触发 |
|---|---|
| `IMPORT_ZIP_TOO_LARGE` | 包 / 单文件 / 总解压 超闸 |
| `IMPORT_BAD_ENTRY` | 条目名路径穿越 / 非白名单条目 / assets 非 PNG |
| `IMPORT_SPEC_UNSUPPORTED` | `manifest.spec` 高于当前插件支持 |
| `IMPORT_SIZE_MISMATCH` | 工程尺寸超当前会话墙 |
| `IMPORT_MALFORMED` | manifest/project.json 解析失败 / 校验不过 |

导入成功响应：`{ ok:true, warnings:[ {kind, detail} … ] }`（warning 不阻断，kind ∈ 缺字体/缺变量/缺图标/脚本命令被block/动画已静态化/孤儿轨已丢弃）。

---

## 5. 安全

### 5.1 `.canvas` 导入 zip 闸（沿用 `security.md §4.4` + 补充）

现有 §4.4 已定：包≤10MiB、单文件解压≤10MiB、总解压≤50MiB、路径安全校验、白名单条目（`manifest.json`/`project.json`/`thumbnail.png`/`assets/*`）、assets 仅 PNG+magic。**本期补充：**

- **流式边解边计数**：闸必须在解压过程中累计，不能先全解压再查（否则形同虚设）。
- **白名单加 `scripts.json` + `assets/icons/*.svg`**（D3 + SVG 图标）。
- **`thumbnail.png` 也是攻击面**：同走 magic + ImageIO 隔离解码 + 尺寸上限（防超大 PNG）。
- **配额**：导入落地的图片**计入图片配额**（`config.images.*`，`security.md §4.5d`），防止用导入绕过上传限频灌图。

### 5.2 `scripts.json` 安全（沿用 `security.md §13.5`）

- 缺命令模板 → `Blocked` 灰显（已写）。
- 导入积木**全量重校验**（类型/参数/condition 语法），不信任文件内 rule_json（§2.3）。
- 导入的命令字符串走与生产同样的 Budget 三闸 + 权限面检查。

### 5.3 SVG 导入闸（`security.md §4.7` 新增，T11 新增威胁项）

| 闸 | 值 / 做法 |
|---|---|
| 源串体积 | ≤ `limits.svg-import-max-kb`（默 512） |
| XML 实体 | **拒含 `<!DOCTYPE` / `<!ENTITY`**（防十亿笑）；`DOMParser('image/svg+xml')` 本身不取外部实体（天然挡 XXE） |
| 危险节点 | 剥离 `<script>` / `<foreignObject>` / `on*` 事件属性 / `href^=javascript:` / 外部 `<image href=http…>` / `<use>` 外链（§3.3 步骤 3） |
| path 复杂度 | 单 `d` ≤ `svg-import-max-path-bytes`（默 64KB，比 `PathDValidator` 的 4096 放宽，但有界）；总元素数 / 总顶点数有界，前端解析期熔断 |
| 后端兜底 | 插入的每个 `PathElement.d` 仍过 `PathDValidator`（放宽上限版）；ImageElement 走 §4.5 全栈 |
| SSRF | MVP **一律拒外部引用**（不 fetch SVG 内任何外链）；与 `security.md §4.6` 已移除 URL-SSRF 防御保持「不新增 fetch 面」 |

> `security.md` 威胁表新增 **T11 恶意 SVG 导入**（XXE / 实体爆炸 / 嵌入脚本 / 超大 path → DoS）。

---

## 6. 前端

### 6.1 导出 UI
- TopBar「更多菜单」（0.7.4 OverflowMenu）新增「导出工程」→ fflate 打包 → 下载 + 错误提示。

### 6.2 导入 UI
- 「导入 `.canvas`」文件选择 → POST → 进度 → 成功后展示 **warning 清单**（缺字体/变量/图标/被 block 脚本/动画已静态化/孤儿轨）。
- 锁定 / 未保存工程的导入是**破坏性替换** → 必须二次确认（防覆盖丢数据）。

### 6.3 SVG 解析器
- 前端 `composables/useSvgImport`：`DOMParser` + 危险节点剥离 + transform 烘焙 + d 归一化 + 内嵌位图抽取。
- 导入对话框：目标尺寸 / 位置（viewBox→画布映射，§见 rendering 回填）。
- 解析失败 / 超闸 / 含动画的友好提示（大白话，例：「这张图自带动画，已按初始样子导入」）。

---

## 7. 风险登记

| 风险 | 缓解 |
|---|---|
| zip 炸弹 / 路径穿越 | 流式三闸 + 条目名规范化后 startsWith 校验（§5.1） |
| 恶意 SVG（XXE / 实体爆炸 / 脚本 / 超大 path） | 拒 DOCTYPE/ENTITY + 剥离危险节点 + 复杂度上限 + 后端 PathDValidator 兜底（§5.3） |
| **双端渲染不一致**（前端 PathParser.ts 子集 vs 后端全文法） | SVG d **归一化到 M/L/Q/C/Z 子集**（§3.3 步骤 5）+ snapshot CI 新增用例 |
| `fill-rule` 保真度 | 按 SVG 显式承载（D9）；带洞图形回归用例 |
| 前端 mirror state 滞后 | 导出前强制 sync 一次（§3.1 步骤 1；§10 待决是否改取服务端权威） |
| 地图量化损失 | 渐变 / 抗锯齿 / 细线经 MC 调色板量化会损失——用户文案讲清「导入后是像素化效果」 |
| **SVG 动画无法转时间轴** | 见下方专项 |

**专项：SVG 动画 → 时间轴（本期明确不做，记录评估结论）**
真实世界动画 SVG 普遍依赖 CSS `@keyframes`（我们无 CSS 解析器）+ SMIL 渐变 stop 动画（我们无此概念）+ `stroke-dashoffset` 画线（无对应能力）+ 多周期并发（一墙一时刻只播一条时间轴）。即便将来单做「SVG 动画→时间轴」转换器，也只有 A 档（平移/旋转/透明度/keySplines→cubicBezier）能近无损映射，B/C 档（颜色流动/morph/motion-path）撞核心模型。**结论**：动画 SVG 一律取首帧静态化；「SVG 动画→时间轴」作为 0.9+ 独立候选（约 50h，只做 A 档 + CSS 解析），不进 0.8。

---

## 8. 分期与工时（理想工时，不含 review/commit）

> SVG 跳过「阶段 0 单 path 图标上传甜点」（用户选「只做矢量完整版」），直接 B1 起。

**Part A — `.canvas` 导入导出（~53h）**

| 批 | 内容 | 工时 |
|---|---|---|
| A1 | 导出（前端）：fflate 打包 + manifest + project.json + 缩略图 + 收集图片塞 assets + 菜单入口/下载 | ~14h |
| A2 | 导入解析 + 安全（后端）：`/api/project/import` + zip 安全解包三闸 + manifest/spec + ProjectState 校验 + 尺寸匹配 + assets magic 解码 + 孤儿轨/缺资源扫描 + 灌会话/广播/audit | ~20h |
| A3 | 导入前端 UI：文件选择/进度/错误 + warning 清单 + 破坏性导入二次确认 + i18n | ~9h |
| A4 | 脚本纳入（D3）：导出 `scripts.json` + 导入 wallId 重绑/重校验/写 wall_scripts + user 变量定义（D8） | ~10h |

**Part B — SVG 导入（~52-76h，取中 ~64h）**

| 批 | 内容 | 工时 |
|---|---|---|
| B1 | 前端 SVG 文档解析器：DOMParser 遍历 + basic shapes→path + transform 烘焙 + fill/stroke 映射 | 16-24h |
| B2 | → 多 `PathElement` 成组插入：d 归一化（A/S/T/H/V 展开）+ 分组/落点/撤销栈 | 10-14h |
| B3 | gradient + viewBox/尺寸映射对话框 + 内嵌位图抽取 | 10-14h |
| B4 | 安全硬化：体积/实体/节点上限 + 危险节点剥离 + PathDValidator 兜底 + 单测 | 8-12h |
| B5 | 双端一致（snapshot CI）+ 文档回填 + i18n + 大白话提示 | 8-12h |

**合计 ~117h + 文档回填 ~6h ≈ ~120h（3-4 周单人）。**

**排期建议**：Part A 先行（1.0 硬前置，且导出/导入是后续 SVG 测试的载体）；Part B 跟进。每批走 TDD（先写失败测试）+ 子代理实现 + 双段审查（subagent-driven-development）。

---

## 9. 文档回填清单（实装时同步）

| 文档 | 动作 |
|---|---|
| `data-model.md §4` | 去掉「未实装」警示；§4.1 加 `scripts.json` + `assets/icons/`；§4.4 定稿孤儿轨=丢弃（D7）；§4.5 把 JSZip 改 fflate；新增 §4.6 `scripts.json` 形态 |
| `security.md` | §4.4 补 thumbnail 校验 + scripts.json 白名单 + 配额计入；**新增 §4.7 SVG 导入**；威胁表加 **T11** |
| `protocol.md` | 新增 `POST /api/project/import` 契约 + 5 个 `IMPORT_*` 错误码 + 导入后 `state.snapshot` 语义；SVG 走现有 element 创建 op 说明 |
| `rendering.md` | 新增 SVG `viewBox`→画布坐标映射公式（复用 IconRenderer contain 范式）+ `fill-rule` 约定 + 双端一致要求 |
| `data-model.md §5.1` / `architecture.md §11` | config 新增 `limits.canvas-import-max-mb` / `limits.svg-import-max-kb` / `limits.svg-import-max-path-bytes` 等字段 |
| `architecture.md` | 补「导入/导出数据流」节（导出前端 / 导入后端信任边界） |
| 用户文档（操作类） | 大白话讲「导出分享 / 导入工程 / 导入 SVG 是像素化、不支持动画」——可等实现稳定后写 |

---

## 10. 未决问题（实现期回填）

- [ ] **fflate 的 zip 写入 API 易用度**——`fflate` 已在 `package-lock.json` 传递依赖，但其 zip 打包 API 是否满足前端用法需 spike 1h（备选 JSZip，体积大一个数量级）。
- [ ] **导出取前端 mirror vs 服务端权威 state**——本期取 mirror + 导出前强制 sync（§3.1）；若实测 mirror 滞后坑多，再评估改后端导出（需同步改 §4.5）。
- [ ] **缩略图渲染**复用前端 `LayerThumbnailRenderer` 还是后端 `WallPreviewService`——倾向前端（导出全客户端）。
- [ ] **SVG path 节点数 / 总顶点数具体上限值**——B4 实测定。
- [ ] **基本形状直映现成元素 vs 一律转 path**——简单矩形/正圆是否直映 `RectElement`/`CircleElement`（更可编辑）还是统一 `PathElement`（更简单），B2 定。
- [ ] **SVG `<text>` 是否阶段 2 映射 TextElement**——本期 D10 不做，记录为后续候选。

---

## 11. 与现有契约的关系（指针）

本文是 0.8 的**契约总纲**。以下文档是被本文**升级 / 扩展**的下游契约，以本文为权威，实装时按 §9 回填：

- `data-model.md §4`（`.canvas` 格式）：本文 D2/D3 + §2.1-2.3 升级它（「规划」→「实装定稿」+ 加 `scripts.json`）。
- `security.md §4.4 / §13.5`：本文 §5.1/§5.2 沿用 + 补充；§5.3 新增 §4.7。
- `scripting.md §2.1`（`ScriptRule`）：本文 §2.3 `scripts.json` 直接复用其结构，不改脚本数据模型。
- `protocol.md §7`（`ProjectState`）：本文 `project.json` 直接复用，不改元素 / 状态数据模型（SVG 导入 D5 不新增元素类型）。
