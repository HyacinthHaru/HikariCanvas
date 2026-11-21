# `.canvas` 工程归档与 SVG 矢量导入规范

**定位**：本文档定义 HikariCanvas 作品导入导出系统的完整契约，包含 `.canvas` ZIP 工程归档格式（`manifest.json`、`project.json`、`scripts.json`、`assets/*`）、客户端打包导出与服务端安全解包物化管线（`ProjectImporter` / `ProjectMaterializer` / `AssetIngest` / `MissingResourceScanner`），以及前端静态 SVG 矢量解析转换管线（`web/src/lib/svg/*`）。

**关联契约**：`docs/data-model.md §4`（`.canvas` 字段与数据库映射）· `docs/template-pack.md`（`manifest.kind = "pack"` 模板包规范）· `docs/security.md §4.4 / §4.7 / §13.5`（归档、SVG 与脚本导入安全模型）· `docs/protocol.md §5.3 / §7`（元素创建操作与 `PathElement.fillRule`）· `docs/rendering.md §11`（`fillRule` 双端渲染与 SVG `viewBox` 坐标映射）。

---

## 1. 定位与范围

### 1.1 核心能力

- **`.canvas` 客户端导出**：前端收集当前会话工程状态（含图层、元素、关键帧时间轴）、墙脚本规则与引用图片资源，生成预览缩略图后通过 `fflate` 在浏览器本地打包为 `.canvas` ZIP 文件触发下载，无需服务端中转。
- **`.canvas` 服务端导入**：后端通过 `POST /api/project/import` 接收归档，执行流式三闸解包、版本与画布尺寸校验、结构数量配额与逐元素安全重校验、图片隔离解码与重哈希修正、孤儿关键帧轨清理及脚本重绑重校验，整体替换当前会话工程并广播 `state.snapshot` 与非阻断告警列表。
- **SVG 静态矢量导入**：前端使用浏览器原生 `DOMParser` 解析静态 SVG 文档，剥离危险节点与外链，将基本几何形状与路径变换烘焙归一化为原生 `PathElement`（`M/L/Q/C/Z` 绝对命令子集，显式保留 `fillRule`），将 `data:` 内嵌位图经 `/api/upload` 转为 `ImageElement`，追加写入当前活动图层。
- **`.canvas` 模板包互通**：`manifest.kind = "pack"` 复用同一套解包与物化管线，详见 `docs/template-pack.md`。

### 1.2 非目标与功能边界

- **不做跨墙增量合并**：导入 `.canvas` 工程的语义固定为「整体替换当前会话墙工程」，不提供图层级或元素级 merge；服务端不托管云端工程库。
- **不做动态/复杂 SVG 特性**：SVG 导入不支持 `<text>`、`<clipPath>`、`<mask>`、`<filter>`、`<foreignObject>`、`<use>` / `<symbol>`、外部网络 `href` 引用，也不解析 SMIL（`<animate*>` / `<set>`）或 CSS `@keyframes` 动画（动画节点在清洗阶段直接剥离，按静态首帧图形导入）。
- **自定义矢量图标归档预留**：归档白名单已预留 `assets/icons/<id>.svg` 路径位，当前 `IconElement` 的用户自定义 SVG 随包导出/摄入通道暂未启用，缺失时由 `MissingResourceScanner` 产出 `missing-icon` 告警提示。

---

## 2. 数据结构与映射规范

### 2.1 `.canvas` 归档结构

`.canvas` 本质为标准 ZIP 归档，内部目录树受严格白名单约束：

```
mysign.canvas
├── manifest.json              # 必选：归档元信息、kind 判别符与画布地图尺寸
├── project.json               # 必选：完整 ProjectState（layers / elements / timelines / canvas）
├── params.json                # 可选：模板包参数声明数组（仅 kind="pack" 使用，见 template-pack.md）
├── scripts.json               # 可选：ScriptRule[] 墙脚本规则数组（无脚本时省略）
├── thumbnail.png              # 可选：256×128 预览缩略图
└── assets/
    ├── <sha256[:16]>.png      # 可选：工程引用的内容寻址 PNG 图片资产
    └── icons/<id>.svg         # 预留：用户自定义 SVG 图标条目位
```

- **资源内嵌与引用边界**：工程上传的位图以二进制内嵌于 `assets/`，保证跨服开箱可用；字体**不内嵌**二进制，仅在元素中保留 `fontId` 字符串引用（目标服缺失该字体时回退默认字体并记 `missing-font` 告警）。
- **变量随包边界**：墙内私有 `user:` 变量随工程携带定义与默认值（不携带运行期瞬时缓存值）；全服级 `userglobal/*` 变量属服务器全局状态，**不打入 `.canvas` 归档**（目标服不存在同名全局变量时走占位符回退链并记 `missing-variable` 告警）。

### 2.2 `manifest.json`

由后端 `CanvasManifest.parse` 解析并校验核心元数据（宽松忽略未知扩展字段）：

```json
{
  "spec": 1,
  "kind": "project",
  "created_at": 1750000000000,
  "created_by": "PlayerName",
  "plugin_version": "1.0.0",
  "name": "中央车站欢迎牌",
  "wall": { "width": 4, "height": 2 }
}
```

- **`spec`**：归档格式版本整数，当前最高支持版本 `ProjectImporter.CANVAS_SPEC_MAX = 1`。`spec <= 0` 报 `IMPORT_MALFORMED`；`spec > 1` 报 `IMPORT_SPEC_UNSUPPORTED` 并提示升级插件；旧版 `project.json` 内部状态结构通过 `ProjectState` `@JsonCreator` 的 `v1 → v2 → v3` 兼容链自动升级。
- **`kind`**：必填判别符，仅接受 `"project"` 或 `"pack"`（其余值报 `IMPORT_MALFORMED`）。模板包可额外携带 `id` 字符串字段（见 `docs/template-pack.md §1.1`）。
- **`wall`**：`width` 与 `height` 必须为正整数（单位：地图张数）。
- **前端转义约束**：`manifest` 所有字符串字段在前端界面展示时一律走 Vue 默认文本插值转义，严禁使用 `v-html`。

### 2.3 `scripts.json`

`scripts.json` 为顶层 JSON 数组，每项对应一条序列化的 `ScriptRule`（模型定义见 `docs/scripting.md §2.1`）：

```json
[
  {
    "id": "sr-1a2b3c4d",
    "wallId": "w-00112233",
    "enabled": true,
    "name": "比分播报",
    "trigger": { "type": "playerKill" },
    "actions": [],
    "blockLayout": "{}"
  }
]
```

导入时由 `ScriptImporter.importScripts` 执行三项处理：
1. **`wallId` 重绑**：忽略归档内记录的源服 `wallId`，强制重写为当前导入会话绑定的目标 `wallId` 后写入 `wall_scripts` 表。
2. **全量安全重校验**：不信任文件内的 JSON 内容，逐条重跑 `ScriptRuleValidator.validate` 与所有条件积木的 `ConditionEvaluator.checkSyntax`；单条规则不合法时跳过该条并产出 `script-invalid` 告警，不阻断其余合法规则与画布本体导入；超出目标服 `scripts.max-rules-per-wall` 上限时停止导入后续规则并产出 `script-quota` 告警。
3. **缺失命令模板降级**：若规则内 `runCommand.templateId` 在目标服 `config.yml` 中未定义，`CommandTemplateEngine` 将其标记为 `Blocked`（编辑器内显示红色徽章并灰显禁用该积木），规则其余部分正常保留并产出 `script-command-blocked` 告警。

### 2.4 SVG 矢量导入映射

前端将静态 SVG 文档转换为现有的原生 `PathElement` 与 `ImageElement` 组合，**不引入任何新元素类型或后端 SVG 解析器**：

| SVG 源结构 | 目标画布模型 | 转换与近似规则 |
| --- | --- | --- |
| `<path d="...">` | `PathElement` | `d` 归一化为绝对坐标 `M/L/Q/C/Z` 命令子集（`H/V → L`、`S → C`、`T → Q`、`A` 椭圆弧按 W3C SVG F.6 标准算法分解为三次贝塞尔段） |
| `<rect>` `<circle>` `<ellipse>` `<line>` `<polyline>` `<polygon>` | `PathElement` | 统一由 `shapesToPath.ts` 转为等价 `path d`（圆与椭圆使用 $\kappa = 0.5522847498$ 的 4 段三次贝塞尔拟合），收敛到单一路径管线 |
| `fill`（纯色 / `linearGradient` / `radialGradient`） | `PathElement.fill`（`Fill` 联合类型） | 纯色转 `SolidFill`；渐变 `url(#id)` 降维映射为 `LinearGradient`（`angle = atan2(dy, dx)`）或 `RadialGradient`（归一化 `cx/cy/r`），色标排序并钳制至 `[2, 8]` 个（忽略 `gradientTransform`、`spreadMethod` 与双焦点偏移） |
| `stroke`（颜色与线宽） | `PathElement.stroke`（`Stroke`） | 保留纯色与宽度；虚线 `stroke-dasharray`、端点帽 `linecap/linejoin` 与渐变描边自动舍弃 |
| `transform`（`translate` / `scale` / `rotate` / `matrix` / `skewX` / `skewY`） | 烘焙入 `PathElement.d` 顶点坐标 | 逐层累乘祖先 2×3 仿射变换矩阵及 `viewBox` 缩放矩阵，直接烘焙至路径控制点并重定原点至元素包围盒左上角 |
| `opacity` | `PathElement.opacity` | 直接映射至元素不透明度字段 |
| `fill-rule`（`nonzero` / `evenodd`） | `PathElement.fillRule` | 显式承载 SVG 填充缠绕规则（SVG 默认 `nonzero`），由后端 `PathRenderer` 与前端 `PreviewRenderer.drawPath` 双端一致渲染带孔洞复合路径（见 `docs/rendering.md §11.1`） |
| `<image href="data:...">` 内嵌位图 | `ImageElement` | 提取 `data:` URL 解码为 `Blob`/`File`，调用 `POST /api/upload` 通过完整图片安全校验栈换取内容寻址 `sha256` 标识，再创建 `ImageElement` |

一次 SVG 导入对应在当前活动图层依次发送多条标准 `element.add` WebSocket 操作，每个生成的元素均可独立选中、缩放、上色、绑定变量与逐步撤销。

---

## 3. 处理管线

### 3.1 前端导出流程（`useProjectExport`）

1. **状态同步**：读取当前 `useProjectStore().state`（确保最新编辑操作已完成同步）。
2. **生成缩略图**：调用前端渲染管线生成 `256×128` 预览图编码为 `thumbnail.png`。
3. **收集引用资产**：扫描所有图层 `ImageElement.source` 去重哈希集合，拉取对应 PNG 字节放入 `assets/<hash>.png`。
4. **序列化清单与脚本**：写出 `project.json`、当前墙非空的 `scripts.json` 以及填充元信息的 `manifest.json`（`spec: 1, kind: "project"`）。
5. **本地打包下载**：通过 `fflate` 在客户端内存压缩生成 `.canvas` Blob，触发浏览器文件下载。

### 3.2 后端导入流程（`ProjectImporter`）

HTTP `POST /api/project/import`（鉴权 `canvas.edit`，multipart 上传）在读取文件前先校验请求体不超过 `config.import.canvas-max-mb`，随后执行完整流水线：

1. **流式安全解包（`CanvasArchive.unpack`）**：边流式解压边统计真实解压字节数（不信任 ZIP 头声明长度），强制执行单包、单条目、总解压三道体积闸与条目名路径安全及白名单检查，校验必含 `manifest.json` 与 `project.json`。
2. **清单校验与模板参数前置替换**：`CanvasManifest.parse` 校验 `spec <= 1` 与 `kind`；若包内含 `params.json`（交叉导入的模板包），调用 `PackParamResolver` 以各参数默认值执行单遍 JSON 转义替换（普通 `project` 包无 `params.json`，字节零改动直通）。
3. **工程物化与配额校验（`ProjectMaterializer.materialize`）**：反序列化 `project.json`，校验工程地图宽高不超过当前会话墙尺寸（超限抛 `IMPORT_SIZE_MISMATCH`），执行 §5.1a 结构数量配额检查，并对每个元素运行 `ElementValidator.validateElementForTemplateApply` 全字段范围重校验。
4. **缺资源扫描（`MissingResourceScanner.scan`）**：扫描本服缺失的字体 ID、缺失的用户图标及缺失的 `userglobal/*` 变量，生成对应的 `missing-*` 告警项。
5. **图片摄入与重哈希修正（`AssetIngest.ingestAll` + `remapAssetSources`）**：逐张对 `assets/*.png` 执行 PNG 魔数检查、200ms 隔离线程 `ImageIO` 解码重编码与配额扣减落盘；若本服重编码后的内容寻址指纹与包内原文件名指纹存在差异，自动改写对应 `ImageElement.source` 指向新指纹并记录 `asset-rehashed` 告警，避免图片断链变占位符。
6. **孤儿关键帧轨剔除（`stripOrphanTracksAndCollect`）**：遍历所有时间轴 `tracks`，丢弃指向不存在 `elementId` 的孤儿轨道并逐条记录 `orphan-track-dropped` 告警。
7. **替换会话与导入脚本**：调用 `EditSession.replaceProject` 整体替换会话工程，若有 `scripts.json` 则调用 `ScriptImporter.importScripts` 重绑落库。
8. **广播、投影、持久化与审计**：向会话推送 `state.snapshot` 全量快照，提交 `ProjectionThrottler` 触发游戏内地图全画布重绘，更新 `walls` 表状态，并写入 `PROJECT_IMPORT` 审计记录。
   > 注：导入路径通过 `ProjectionThrottler` 立即刷新静态画面；若导入工程包含自动循环时间轴，将在手动点击播放、编辑持久化或下次墙加载/会话关闭时启动 `AnimationTicker`。

### 3.3 前端 SVG 转换流程（`web/src/lib/svg/`）

1. **预解析拦截（`svgSecurity.preParseGuard`）**：检查源文本字节长度（≤ 512 KB），若包含 `<!DOCTYPE` 或 `<!ENTITY` 立即拒绝（防 XML 实体膨胀）。
2. **安全 DOM 解析**：调用 `new DOMParser().parseFromString(svg, 'image/svg+xml')`，不加载外部实体、不执行内嵌脚本。
3. **危险节点剥离（`svgSecurity.stripDangerous`）**：深度优先遍历删除 `<script>`、`<foreignObject>`、`<use>`、`<symbol>`、`<animate*>`、`<set>`、`<style>` 节点，剥除所有 `on*` 事件属性、`javascript:` 链接以及非 `data:` 前缀的外部 `<image>` 节点。
4. **形状收集与仿射烘焙（`svgParse` + `shapesToPath` + `transform` + `bakePath`）**：解析 `viewBox`/`width`/`height` 到目标画布的映射缩放（`docs/rendering.md §11.2`），将基本形状转为路径，累乘各级 `transform` 2×3 矩阵烘焙入控制点坐标并归一化至局部包围盒左上角。
5. **命令集归一化（`normalizeD`）**：将所有相对命令与简写命令展开为绝对坐标 `M/L/Q/C/Z` 子集，确保前后端 `PathParser` 渲染结果逐像素一致。
6. **复杂度熔断（`svgSecurity.complexityGuard`）**：校验总形状数 `maxShapes <= 500` 且总顶点数 `maxTotalVertices <= 50000`，超限抛出 `SVG_TOO_COMPLEX`。
7. **样式映射与元素创建（`fillMap` + `useSvgImport`）**：完成填充、描边、不透明度与 `fillRule` 映射；内嵌 `data:` 图片先上传 `/api/upload`，随后按图层顺序逐条发送 `element.add` 落入工程。

---

## 4. 协议端点、错误码与告警

| 通道 | 方向与形式 | 说明 |
| --- | --- | --- |
| `POST /api/project/import` | HTTP `multipart/form-data` | 上传 `.canvas` 归档（避开 WebSocket 1 MiB 单帧限制）；成功后经 WS `state.snapshot` 同步状态 |
| 工程导出 | 纯客户端本地处理 | `fflate` 浏览器端打包下载，不新增专用导出 HTTP 端点 |
| SVG 矢量导入 | 前端解析 + 既有端点/WS op | 路径元素走既有 WS `element.add`；内嵌 `data:` 位图复用 `POST /api/upload` |

**致命导入错误码**（返回非 2xx HTTP 状态并中止导入，当前会话工程保持原状）：

| 错误码（`code`） | HTTP 状态 | 触发条件 |
| --- | --- | --- |
| `IMPORT_ZIP_TOO_LARGE` | `413` | 归档总大小、单条目解压大小或累计总解压大小超出安全阈值 |
| `IMPORT_SPEC_UNSUPPORTED` | `409` | `manifest.spec` 高于当前插件支持的最高版本（`1`） |
| `IMPORT_SIZE_MISMATCH` | `409` | 归档 `canvas` 地图宽高超出当前会话墙的物理尺寸 |
| `IMPORT_BAD_ENTRY` | `400` | ZIP 含路径穿越（`..`、前导 `/`、`\`、空字节）或非白名单条目 |
| `IMPORT_MALFORMED` | `400` | ZIP 损坏、缺必选文件、JSON 解析失败、模板参数非法、元素校验失败或超出 §5.1a 结构数量配额 |

**非阻断导入告警**（导入成功返回 `{ ok: true, warnings: [{ kind, detail }, ...] }`，共 9 种 `kind`）：

| `kind` | 含义与 `detail` 内容 |
| --- | --- |
| `missing-font` | 工程引用的字体本服未安装，已回退默认字体渲染（`detail` = `fontId`） |
| `missing-icon` | 工程引用的用户自定义图标（`user/<id>`）本服不存在，显示为空占位（`detail` = `source`） |
| `missing-variable` | 工程引用的全局用户变量（`userglobal/*`）本服未创建，按占位符回退显示（`detail` = 变量全名） |
| `asset-quota` | 图片配额已满、超出单包 256 张上限或个别图片解码失败，对应资产已跳过（`detail` = 跳过张数说明） |
| `asset-rehashed` | 图片在本服隔离重编码后内容哈希发生变化，元素引用已自动重定向至新指纹（`detail` = 原始指纹） |
| `orphan-track-dropped` | 时间轴中存在指向已不存在元素的孤儿关键帧轨，已自动清理（`detail` = `elementId`） |
| `script-invalid` | 单条脚本规则未通过结构或条件语法重校验，已跳过该条规则（`detail` = 校验原因） |
| `script-command-blocked` | 脚本引用的 `runCommand.templateId` 本服未配置，该命令积木已停用、规则其余部分照常导入（`detail` = `templateId`） |
| `script-quota` | 导入脚本条数达到本服 `scripts.max-rules-per-wall` 上限，超额规则已停止导入 |

---

## 5. 安全与配额校验栈

### 5.1 `.canvas` ZIP 解包与资产安全闸（`CanvasArchive` & `AssetIngest`）

1. **流式字节三闸**：在 `ZipInputStream` 循环读取过程中实时累加字节计数（配置段 `config.import.*`，默认单包 ≤ **10 MiB**、单条目解压 ≤ **10 MiB**、总解压 ≤ **50 MiB**），彻底防御篡改 ZIP 头尺寸的压缩炸弹。
2. **路径与条目白名单**：拒绝含 `/` 前缀、`\`、`..` 或 `\0` 的条目名；顶层仅放行 `manifest.json`、`project.json`、`params.json`、`scripts.json`、`thumbnail.png` 及 `assets/` 前缀文件。
3. **图片隔离解码与条数上限**：`assets/*.png` 与 `thumbnail.png` 均须通过 PNG 魔数校验与 200ms 超时线程池隔离 `ImageIO` 解码，落盘计入玩家与全服图片配额（`config.images.*`，见 `docs/security.md §4.5`）。同时限制单包 `assets/*.png` 最多处理 **256 张**，防止海量微型合法 PNG 触发数万次磁盘/数据库事务造成拒绝服务（超额部分计入 `asset-quota` 告警）。

### 5.1a 工程结构配额（`ProjectMaterializer`）

为防止合规体积内的 `project.json` 塞入极端数量的图层、元素或关键帧拖垮合成与存储，`ProjectMaterializer.materialize` 在物化阶段对 HTTP 工程导入与 `template.apply` 模板套用统一实施以下硬上限（超限一律拒绝为 `IMPORT_MALFORMED`）：

| 校验维度 | 硬上限 | 对齐依据 |
| --- | --- | --- |
| 图层总数 | `64` | 与 `layer.create` 上限一致 |
| 单图层名称长度 | `64` 字符 | 与 `layer.create` 上限一致 |
| 图层 `opacity` | 有限浮点数且 $\in [0, 1]$ | 防止 `NaN` 或越界透明度在合成期产生像素噪点或整层消失 |
| 时间轴总数 | `16` | 与 `timeline.create` 上限一致 |
| 单条时间轴关键帧总数 | `2048` | 与关键帧编辑操作上限一致 |
| 单属性轨关键帧数 | `256` | 与关键帧编辑操作上限一致 |
| 全工程元素总数 | `4096` | 画布元素结构天花板 |
| 单墙引用的不同图片数 | `config.images.max-per-wall`（默认 `16`） | 与 `element.add`（`type = image`）单墙图片配额一致 |

### 5.2 `scripts.json` 安全重校验

导入脚本严格遵循零信任原则（对齐 `docs/security.md §13.5`）：全量重跑 `ScriptRuleValidator` 与 `ConditionEvaluator.checkSyntax`，未知 `templateId` 强制进入 `Blocked` 不可执行态，运行期照常受 `ScriptBudget` 三闸与变量写命名空间白名单约束。

### 5.3 SVG 导入安全闸（`docs/security.md §4.7`）

| 防护层级 | 规则与阈值 | 实现模块 |
| --- | --- | --- |
| 源文本体积闸 | 原始 SVG 字符串长度 ≤ **512 KB**（超限报 `SVG_TOO_LARGE`） | `svgSecurity.preParseGuard` |
| XML 实体拦截 | 源串含 `<!DOCTYPE` 或 `<!ENTITY` 立即拒绝报 `SVG_HAS_ENTITY`；`DOMParser` 不解析外部实体 | `svgSecurity.preParseGuard` + `DOMParser` |
| 危险标签与外链剥离 | 移除 `<script>` / `<foreignObject>` / `<use>` / `<symbol>` / `<animate*>` / `<set>` / `<style>`、`on*` 属性、`javascript:` 协议及一切非 `data:` 外链图片（零 SSRF 面） | `svgSecurity.stripDangerous` |
| 几何复杂度熔断 | 形状数 ≤ **500**、累计路径顶点数 ≤ **50,000**（超限报 `SVG_TOO_COMPLEX`） | `svgSecurity.complexityGuard` |
| 服务端二次兜底 | 每条生成的 `PathElement.d` 仍须通过后端 `PathDValidator`（命令子集、顶点数与坐标范围校验）；内嵌位图走 `/api/upload` 完整校验栈 | `ElementValidator` / `UploadHandler` |

---

## 6. 前端集成

### 6.1 导出交互

TopBar 溢出菜单提供「导出工程」入口，调用 `useProjectExport` 在本地完成缩略图渲染、资产收集与 `fflate` 压缩下载，失败时展示本地化错误提示。

### 6.2 导入交互与覆盖确认

TopBar 溢出菜单「导入工程」打开 `ImportProjectModal`：由于 `.canvas` 导入会整体覆盖当前画布内容，提交前强制弹出破坏性替换二次确认；上传完成后自动应用下行快照，并以人类可读列表分类展示返回的非空 `warnings`。

### 6.3 SVG 解析模块与导入弹窗

- **编排入口**：`web/src/composables/useSvgImport.ts` 串联解析、内嵌位图上传与批量 `element.add` 发送。
- **核心库（`web/src/lib/svg/`，8 个纯逻辑模块）**：`svgSecurity.ts`、`svgParse.ts`、`shapesToPath.ts`、`transform.ts`、`normalizeD.ts`、`bakePath.ts`、`fillMap.ts`、`svgToElements.ts`。
- **交互入口**：TopBar 溢出菜单「导入 SVG」打开 `SvgImportModal.vue`（接受 `.svg` / `image/svg+xml`）；因 SVG 导入属于当前图层元素追加而非整墙覆盖，无需破坏性二次确认；触发 `SVG_TOO_LARGE` / `SVG_HAS_ENTITY` / `SVG_TOO_COMPLEX` 等错误码时通过 i18n 展示明确提示。

---

## 7. 关键约束与静态化边界

| 工程约束 | 处理机制 |
| --- | --- |
| 压缩包体积膨胀与路径穿越 | `CanvasArchive` 流式三闸计数 + 严格路径字符与条目名白名单校验（§5.1） |
| 恶意或超复杂 SVG 输入 | 实体声明阻断 + 危险节点与外链剥离 + 500 形状 / 50,000 顶点熔断 + 后端 `PathDValidator` 兜底（§5.3） |
| 前后端路径渲染一致性 | 前端 `normalizeD` 统一展开椭圆弧与简写指令至 `M/L/Q/C/Z` 绝对子集，显式同步 `fillRule`（§2.4 / §3.3） |
| 跨环境 PNG 重编码指纹漂移 | `ProjectImporter.remapAssetSources` 自动将元素 `source` 重映射至本服落盘新指纹并回传 `asset-rehashed` 告警（§3.2） |
| 调色板量化与像素化表现 | 导入的平滑渐变与细矢量曲线在地图输出端均经过 248 色调色板量化，UI 文案明确传达像素化渲染特性 |
| 动画 SVG 静态化处理 | 真实动画 SVG 普遍依赖 CSS `@keyframes` 样式表、SMIL 渐变色标动画、`stroke-dashoffset` 描边推进及多独立周期并发，超出单时间轴关键帧属性模型范围；因此解析器统一剥离动画节点，提取静态首帧几何导入 |

---

## 8. 关联契约

- **`docs/data-model.md §4`**：`.canvas` 归档字段字典与持久化表结构映射。
- **`docs/template-pack.md`**：`manifest.kind = "pack"` 模板包参数声明、替换与工坊隔离规范。
- **`docs/security.md §4.4 / §4.7 / §13.5`**：`.canvas` 归档解包、SVG 矢量导入与脚本导入的安全规范。
- **`docs/scripting.md §2.1`**：`scripts.json` 复用的 `ScriptRule` 数据模型与校验规则。
- **`docs/protocol.md §5.3 / §7`**：`element.add` 协议载荷与 `PathElement`（含 `fillRule`）状态定义。
- **`docs/rendering.md §11`**：`PathElement.fillRule` 双端渲染实现与 SVG `viewBox` 到画布像素空间的坐标映射公式。
