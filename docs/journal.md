# 变更日志

本文件记录 Claude 每次对本项目做出的修改。**新条目追加到文件顶部**（倒序）。每条应含：日期、改动范围、简要说明、关联文件。
代码与文档的日常提交信息写 git commit，本文件只留会话级摘要。

---

## 2026-05-17 · M17 收尾总览

**M17 = 5 大 feature（F1-F5）落地 + 4 phase commit batch + 0 baseline 漂移 + ~1500 行净增**

继 M15 / M16 安全与数据完整性收口后，把"体验质量从 demo 升到生产级"作为单独里程碑推完。

| Phase | 主题 | feature 项 | commit |
|---|---|---|---|
| M17-P1 | 拖动跟手 + 自由拖动画布 | F2 + F4 | `a049484` |
| M17-P2+3 | 复制粘贴 + Canvas Fill + 智能对齐 v1 | F1 + F5 + F3 v1 | `1ed92ca` |
| M17-P4 | 智能对齐 v2 完整版（distribute + visualizer + resize snap） | F3 v2 | `2ac5558` |
| M17-P5 | docs 同步收尾 | — | （本 commit） |
| **合计** | | **5 feature / 4 commit** | |

### 5 feature 一句话总结

| feature | 类型 | 一句话 |
| --- | --- | --- |
| F1 | 新能力 | 复制粘贴 Ctrl+C/V，剪贴板 magic header `hikari-canvas-v1:` 跨 wall 工作，锁定 wall 拒粘贴 |
| F2 | bug 修复 | onDragMove 单选 path 实时跟手 —— 双层渲染下早 return 导致底层 Canvas 2D 不重绘的视觉滞后 |
| F3 | 新能力 | 智能对齐 = `useSnapManager` composable（drag + resize 共用）+ distribute 间距均分 + SnapGuideOverlay 对齐线 + Magnet popover |
| F4 | 新能力 | 自由拖动画布（新 'hand' 工具 / H 键 / Space 临时切）+ 1024px 虚空白边 |
| F5 | 数据模型升级 | `Canvas.background`: String → Fill 联合类型，Jackson 自动兼容旧 hex，渐变背景就位 |

### M17 已固化的关键决策

1. **Canvas.background = Fill 联合类型**：Solid / Linear / Radial 三态统一；`FillDeserializer`（M11 已存在 string → SolidFill 路径）自动反序列化兼容旧 hex 字符串与 14 fixture 0 漂移；模板 raw_state 渐变背景 fallback `"#FFFFFF"`（raw_state 格式仅识 hex）
2. **`'hand'` 工具是非绘制工具之一**：`ui.ActiveTool` 三大非绘制工具 = `select / move / hand`，与 drawTool（line / arrow / circle / star / brush）区分；Space 临时切由闭包 `spaceSavedTool` 保存原工具 + window blur 兜底防卡死
3. **`useSnapManager` = 公共能力**：drag （CanvasView onDragMove）+ resize （Konva Transformer boundBoxFunc）共用；O(n) 线性 + snapAxis 两遍扫描 + shift bypass + localStorage 持久化 `hikari-canvas:snap`
4. **SnapGuideOverlay 走 vue-konva 独立 v-layer**：挂载在 v-stage 内 marquee / drawPreview 同级 layer，覆盖 element / transformer 上方；drag / transform end 立刻清 hints + `v-if` 自然卸载
5. **resize snap 选 `boundBoxFunc` 而非 transformend**：前者每帧拖锚点 call 可给视觉反馈；按"动的边"应用 delta 比"snap 整 bbox"更精准，任何锚点（角 / 边中点）都正确无视觉跳动
6. **双层渲染配合**：HikariCanvas 编辑器 = 顶层 Konva 透明 hit-test 矩形（fill rgba 0.001）+ 底层 Canvas 2D PreviewRenderer 像素化输出（dither + 248 调色板）。底层重绘依赖 `requestDraw()` → `watch(project.state, {deep:true})`。F2 修复证明任何拖动 mutation 必须落 store 才能触发 PreviewRenderer 重绘

### M17 累计文件改动

**新文件（6）**：
- `web/src/composables/useClipboard.ts`（F1）
- `web/src/composables/useSnapManager.ts`（F3）
- `web/src/components/properties/CanvasSettingsSection.vue`（F5）
- `web/src/components/canvas/SnapGuideOverlay.vue`（F3 v2）
- `web/src/components/layout/SnapSettingsPopover.vue`（F3 v2）

**改动 Java**（F5）：`state/Fill.java` / `state/ProjectState.java` / `state/EditSession.java` / `render/CanvasCompositor.java` / `render/CanvasProjector.java` / `render/WallRestorer.java` / `template/TemplateInstantiator.java` / `web/EditOpDispatcher.java` / test `EditSessionReplaceContentTest.java`

**改动前端**（F1-F5）：`stores/ui.ts` / `stores/project.ts` / `stores/palette.ts` / `components/layout/CanvasView.vue` / `components/layout/LeftTools.vue` / `components/layout/RightPanel.vue` / `components/layout/TopBar.vue` / `composables/useCanvasShortcuts.ts` / `composables/usePanScroll.ts` / `composables/useTransformerManager.ts` / `render/PreviewRenderer.ts` / `types/protocol.ts` / `i18n/messages.ts`

### 验证

- `:plugin:test --rerun-tasks` BUILD SUCCESSFUL（14 RendererSnapshotTest baseline 0 像素漂移；F5 FillDeserializer 自动处理 fixture `"background": "#xxx"` 字符串）
- `vite build` ~330-400ms / ~503 kB / ~155 kB gzip，0 新 TS 错误

### 评估

体验质量从 demo 升到生产级：60fps 拖动实时跟手 + 智能对齐 + distribute + 自由 pan + 跨 wall 剪贴板 + 渐变背景。**M18 (B-advanced Live Paint) 已可开工**。

### 已知留档（v1.x scope）

- 「任意三元素均分」(distribute) 完整版（v2 仅匹配两侧最近邻）
- 完整版 resize snap（按锚点显式映射 + rotated bbox 支持）
- SnapGuideOverlay CSS fade-out 过渡
- spatial index for snapManager（100 elements 内 O(n) 不卡）
- 多文件批量剪贴板 / image data 粘贴（M13 配额 + 6 层校验栈外）

---

## 2026-05-17 · M18 Phase 5（vitest 引入 + Live Paint 单测）

补 CLAUDE.md / M16 待办段自承的"前端无 vitest"。生产级标准要求 livepaint 自写算法必须有单测。

### 依赖

vitest 4.1.6 + @vitest/ui 4.1.6（devDependencies）；未引入 jsdom（livepaint 纯算法，node 环境最快）。

### 配置

- `vite.config.ts`：`/// <reference types="vitest" />` + `test: { environment: 'node', include: ['src/**/*.test.ts'] }`
- `package.json` scripts：`test` / `test:watch` / `test:ui`

### 测试用例（28 total）

`web/src/livepaint/__tests__/`：
- `ElementToPolygon.test.ts`（8）：rect 4 顶点 + rotation；circle 32 采样 + 椭圆；shape 五边形；star 10 顶点；path 矩形 + fallback
- `LivePaintCore.test.ts`（8）：pointInPolygon 内/外/U 形；buildGraph 空/单 rect/退化/完全覆盖/两不重叠
- `PolygonToPath.test.ts`（6）：gapPolygonToPathD 矩形/hole；gapToPathElement bbox/退化；maybeSimplify 不触发/触发
- `RdpSimplifier.test.ts`（6）：三顶点/共线/tolerance=0/NaN/1000 点不爆栈/方波拐点

### 结果

```
Test Files  4 passed (4)
     Tests  28 passed (28)
  Duration  166ms
```

vite build 无退化：382ms / 543.29 kB index / 33.75 kB worker / 47.22 kB css。

---

## 2026-05-17 · M18 Phase 4（边界 case + vector-fill 决策 A + 退化 fallback）

1 个 agent 完成。

### A. vector-fill 决策 A 实装

`findElementAt(canvasX, canvasY)`：倒序遍历 visibleElements（z-order 顶层优先）+ `elementToPolygon` + `pointInPolygon` 精确命中（不只 bbox，避免 circle / star 角落误判）。

`onPaintBucketClick` 优先级：wall lock → graph 未就绪 → graph degraded → gap 命中 → element 命中 → noGap。element 命中分支：lock 拒；`rect/circle/shape/path` 走 `element.update {patch:{fill}}` + 乐观本地 mutate；`text/image/brush` 走 `livePaint.elementUnsupported(type)` 提示。

### B. pointInPolygon API export

`LivePaintCore.ts` 内 `pointInPolygon` 由 file-private 升级为 `export`；`livepaint/index.ts` 加 export。

### C. RDP 顶点简化

新 `web/src/livepaint/RdpSimplifier.ts` 迭代式 stack 实现（防大输入栈溢出）；点到线段距离 + 投影 + 截断。`PolygonToPath.gapToPathElement` 内 `maybeSimplify` tolerance 阶梯式 0.5 → 16 翻倍直到 ≤ VERTEX_HARD_LIMIT=240（PathDValidator 实际限制）。每轮基于原 polygon（不累计误差）。

### D. 退化几何 fallback

`types.LivePaintGraph` 加可选 `degraded?: boolean`。`buildGraph` try/catch 改：原"整画布单 gap"假象 → `{gaps:[], degraded:true}`。useLivePaint 透传；CanvasView click handler 检测 degraded → `pushLog('err', t.livePaint.degraded)`。

### E. 极小 gap 过滤

`LivePaintCore` 新 `polygonArea` (shoelace 绝对值)；`MIN_GAP_AREA = 4` px²；outer 面积 < 阈值的 gap 整体丢弃；holes 不过滤（保留 even-odd 几何意图）。

### F. i18n

删 `livePaint.recolorPending`；新增 `recolorSuccess / elementLocked / elementUnsupported(type) / wallLocked / degraded` zh+en 镜像。

### G. perf log

`useLivePaint.ts` DEV-only `import.meta.env.DEV` 静态分支让 prod tree-shake：`sentAt: Map<requestId, {startedAt, elementCount}>` 打点；onmessage elapsed；格式 `[livepaint] graph built in 12.3ms (8 elements → 4 gaps)`；race 丢弃旧 requestId 也清 sentAt 防内存泄漏。

### vite build

`✓ built in 374ms`；livePaintWorker chunk 33.75 kB（含 RDP）；index 543.29 kB / 168.54 kB gzip；0 新 TS 错误。

---

## 2026-05-17 · M18 Phase 3（UI + hover 预览集成）

1 个 agent 完成 UI 全栈集成。

### 工具系统

- `ui.ActiveTool` union 加 `'paint-bucket'`；`isDrawTool` 排除 paint-bucket（click 工具非 drag-to-create）；`loadTool` KNOWN 列表加
- 快捷键 `G`（Photoshop / Figma fill bucket 标准，与 brush `B` 错开）
- `useTransformerManager.attachTransformer` 隐藏 transformer 工具列表加 paint-bucket（与 move / hand 同列）
- LeftTools 加 PaintBucket 按钮（lucide `PaintBucket` icon）

### 新组件

- `web/src/components/layout/PaintBucketPanel.vue`：BrushPanel 同款结构；FillInput 绑 paintBucket.currentFill + 双行操作提示
- `web/src/components/canvas/LivePaintHoverOverlay.vue`：`<v-layer listening="false">` 包 `<v-path>`；data 由 `gapPolygonToPathD(hoveredGap)` 生成，fillRule='evenodd' 自动挖洞；fill rgba(96,165,250,0.22) 蓝色半透明 + stroke `#60a5fa`；strokeWidth `1.5/zoom` 保视觉密度

### 新 store

- `web/src/stores/paintBucket.ts`：`currentFill: Ref<FillCompat>` + localStorage 持久化（key `hikari-canvas:paint-bucket`，默认 `{type:'solid', color:'#000000'}`）

### CanvasView 集成

- `useLivePaint` 接入：`livePaintEnabled = ui.activeTool === 'paint-bucket'` + `visibleElements` 过滤 `visible !== false`
- `hoveredGap` ref：mousemove 时调 `livePaint.findGapAt(pos.x, pos.y)`（stage 无 scale，坐标直接是画布像素）
- `onPaintBucketClick`：① wall 锁定 → 文案；② graph 未就绪 → t.livePaint.building；③ 命中 gap → `gapToPathElement` + `ws.send('element.add', { type:'path', layerId, props:{...fill: paintBucket.currentFill} })`；④ 命中 element bbox → console.log + t.livePaint.recolorPending（P4 待实装 vector-fill）；⑤ 都未命中 → t.livePaint.noGapFound
- cursorStyle 加 paint-bucket → 'crosshair'
- hitConfig listening 关：paint-bucket 加 `drawing` flag 让 element-hit 整层不响应
- watch ui.activeTool 切走时清 hoveredGap；@mouseleave + window blur 兜底
- LivePaintHoverOverlay 挂在 v-stage 内 SnapGuideOverlay 同级
- isBuilding indicator：右下角 absolute 浮窗 `bottom-16 right-4`，蓝色脉冲点 + t.livePaint.building

### i18n

- `tools.paintBucketTool` 中英
- `livePaint.{ title, hint, hintHoverPreview, fillLabel, noGapFound, layerLocked, building, recolorPending }` 中英

### vite build

`✓ built in 354ms`；`dist/assets/livePaintWorker-DBy-krPN.js 33.63 kB` 出现，确认 useLivePaint 被消费 + worker 单独切包；bundle `538.40 → 538.43 kB`（+0.03 kB；gzip 166.92 不变）。

---

## 2026-05-17 · M18 Phase 2（Worker + 增量缓存）

1 个 agent 完成。

### 新文件

- `web/src/livepaint/livePaintWorker.ts`：module worker；message discriminated union `{type:'build', requestId, elements, canvasWidth, canvasHeight}` → `{type:'ok'|'err', requestId, graph|message}`；内部 try/catch 包 buildGraph
- `web/src/livepaint/useLivePaint.ts`：Vue composable
  - debounce 100ms（与 M5 编辑器输入防抖一致；避免 element 高频变化 worker 风暴）
  - requestId race 处理：`pendingRequestId` 记最新，旧响应丢弃
  - JSON 深 clone（Vue reactive Proxy 不能 structuredClone；toRaw 不能 deep）
  - `enabled` gate：Live Paint 工具未激活时不重建，节省 CPU
  - `onScopeDispose` terminate worker + clearTimeout（对齐 M16-P4.2 资源纪律）
  - API：`graph / isBuilding / findGapAt / rebuildNow`

### 设计决策

- **vite.config.ts 未动**：`?worker` import 默认 module worker，Vite 自动处理
- **Worker chunk 当前未单独生成**：barrel export 不构成消费，tree-shake 掉是预期；P3 接入 CanvasView 后才会出 `livePaintWorker-*.js`
- **修复细节**：初稿误从 LivePaintCore 导入 LivePaintGraph（实际只在 types），改 `import type` 修

### vite build

503.43 kB / 155.65 kB gzip 零增量（worker 代码 dead-code-eliminated 等 P3）。

---

## 2026-05-17 · M18 Phase 1（Live Paint 核心算法）

针对 B-medium+ 路线，1 个 agent 完成核心几何算法（无 UI / 无 Worker）。

### 依赖

`polygon-clipping@0.15.7` 加入 dependencies；4 packages / 0 vulnerabilities；TS types 自带。

### 新模块 `web/src/livepaint/`（5 文件 / 658 行）

- `types.ts`（30）：`Polygon = [[x,y]...]` 单环；`GapPolygon = { outer, holes[] }`；`LivePaintGraph = { gaps[] }`
- `ElementToPolygon.ts`（350）：元素 → polygon array
  - rect → 4 顶点 + rotation
  - circle → 32 点采样（椭圆支持 w≠h）+ rotation
  - shape → ShapeElement.sides 正多边形，起始角 -π/2 顶点朝上
  - star → 2×sides 交替 outer/inner（innerRatio 默认 0.5）
  - path → 自实装 M/L/Q/C/Z + de Casteljau 12 等分；多 subpath v1 只取首段；解析失败 fallback bbox
  - text / image / brush → bbox 兜底
- `LivePaintCore.ts`（154）：`buildGraph` + `findGapAt`
  - polygon-clipping `union(...polys)` → 占用区
  - `difference(canvasRect, ...occupied)` → 所有空隙 MultiPolygon
  - 转 GapPolygon[]（first ring = outer CCW；rest = holes CW；剥末尾重复首点）
  - findGapAt：ray-casting 点在外环 + 不在任何 hole
  - try/catch 包 boolean op，极端退化 → 降级整画布单 gap + console.warn
- `PolygonToPath.ts`（105）：`gapPolygonToPathD` + `gapToPathElement`
  - 多 subpath 串 M-L-Z；依赖 even-odd fill rule 自动挖洞
  - gapToPathElement：算 gap bbox → 平移到 (0,0) → 输出 `{x, y, w, h, d (相对 bbox), vertexCount}`
- `index.ts`（19）：桶式 re-export 5 个 API + 3 类型 + 3 常量

### 关键设计决策

- **circle 采样 32 点**：128px 半径下视觉无棱角；64 点会让 union 后顶点爆炸
- **path 复杂度 fallback**：多 subpath / 解析失败 / 自交 → bbox 兜底
- **顶点警告阈值 180**：PathDValidator 实际限 MAX_LEN=4096 char ≈ 240 顶点；软警告 console.warn，RDP 简化留 P4
- **rotation 应用顺序**：先生成局部 polygon 再绕 (x+w/2, y+h/2) 旋转；rotation=0 短路
- **类型坑隔离**：本模块 `Polygon` = 单环（首点不复制末点）；polygon-clipping 库 `Polygon` = 多环（末点复制首点）。LivePaintCore 内用 `PCRing/PCPolygon` 别名 + 转换 helper

### 留 Phase 后续

- M18-P2：Web Worker 化（buildGraph 100+ element 时主线程可能 jank）
- M18-P3：UI + 集成（livepaint 模块当前被 tree-shake 完全剔除）
- M18-P4：RDP 顶点简化 + vector-fill 决策 A（点击 element 内部改 element.fill）

### 验证

`vite build` 371ms / 503 kB（livepaint 未消费 = bundle 大小未变）；livepaint 5 文件 0 TS 错误。

---

## 2026-05-17 · M17 Phase 4（F3 智能对齐完善 → v2 完整版）

1 个 agent 完成。补齐 M17.3 v1 剩余：distribute + visualizer + popover + resize snap。

### distribute 间距均分

`useSnapManager.ts` 加 `EqualGapX` / `EqualGapY` hint 类型；新私有方法 `findEqualGapX` / `findEqualGapY`。SnapHints 加 `equalGapX?` / `equalGapY?` 字段（含 aRight / bLeft / bRight / cLeft + yCenter / x 镜像）。

**v2 简化**：仅匹配两侧最近邻 —— A.right < dragged.left 中 right 最大的 + C.left > dragged.right 中 left 最小的；A/C 必须同方向找到；span < w/h 时跳过；与同方向 axis snap **互斥**（axis 命中时不跑 distribute）。「任意三元素均分」留 v1.x 扩展。

### visualizer 对齐线

新 `web/src/components/canvas/SnapGuideOverlay.vue`（vue-konva `v-line` + `v-rect` + `v-text`）。挂载位置：CanvasView v-stage 内 marquee/drawPreview 同级独立 v-layer，覆盖 element/transformer 上方。

视觉规范：
- snap axis 红色虚线 `#ef4444` + dash `[4,3] / zoom`
- equalGap 绿色实线 `#22c55e` + 两端短刻度 + 中间像素距离标签（白字 + 绿底圆角 pill）
- strokeWidth / fontSize 全部 `1 / zoom` 保持视觉密度

**fade**：drag/transform end 立刻清 `activeSnapHints = null`，layer `v-if` 自然卸载；CSS fade 标注留 v1.x。

### popover 开关 UI

新 `web/src/components/layout/SnapSettingsPopover.vue`：lucide `Magnet` icon 按钮 + popover（onClickOutside 关闭）。挂载位置：`TopBar.vue` 右侧按钮组（紧贴 Bookmark 后、Help 前）。

内容：
- Enable Snap 总开关
- 4 子开关（To Grid / To Canvas / To Element / To Distribute；总开关关闭时子项 disabled 半透明）
- threshold range slider 1-32
- shift 提示文字

### resize snap (onTransform 接入)

CanvasView.vue 新 `boundBoxFunc(oldBox, newBox)` 并入 `transformerConfig`。Konva Transformer 每帧拖锚点 call。

**为何选 boundBoxFunc 而非 transformend**：transformend 是结束时一次性事件，无法在拖动中给视觉反馈。

**简化版（v2 已采用）**：比对 newBox vs oldBox 找出**正在动的边**（leftMoved / rightMoved / topMoved / bottomMoved），按边把 snap delta 应用到 `x or width` / `y or height`。比"snap 整 bbox"更精准——任何锚点（top-left / bottom-right / middle-* / *-center）都正确，无视觉跳动。

- `rotation != 0` → return newBox 跳过 snap（旋转后 bbox 不对齐画布轴）
- `w/h < 1` → return newBox
- 多选时 excludeIds 整组 selectedIds 排除（避免 snap 到自己）

完整版扩展（按锚点显式映射 + rotated bbox）留 v1.x。

`onElementTransformEnd` wrap：调用 `onTransformEnd(ev, id)` 后清 `activeSnapHints`。

### ui store 新字段

`snapToDistribute: Ref<boolean>` 默认 true；加入 `SnapPrefs` interface + `SNAP_DEFAULT` + `loadSnap` 反序列化默认值 + watch 持久化数组 + return 暴露。

### i18n

新 8 key：`snap.{settings, enable, toGrid, toCanvas, toElement, toDistribute, threshold, shiftHint}` 中英全。

### 验证

`vite build` 335ms / 503.43 kB / 155.65 kB gzip。后端无改动，未跑 :plugin:test。

### 关联文件

**新文件**：`web/src/components/canvas/SnapGuideOverlay.vue` / `web/src/components/layout/SnapSettingsPopover.vue`

**改文件**：`web/src/composables/useSnapManager.ts`（+ distribute + EqualGap 类型）/ `web/src/stores/ui.ts`（+ snapToDistribute + SnapPrefs 扩展）/ `web/src/i18n/messages.ts`（+ snap 段）/ `web/src/components/layout/TopBar.vue`（挂 popover）/ `web/src/components/layout/CanvasView.vue`（boundBoxFunc + activeSnapHints + overlay 挂载 + drag/transform end 清 hints + window mouseup 兜底清）。

---

## 2026-05-17 · M17 Phase 2 + 3（F1 复制粘贴 + F5 Canvas Fill + F3 智能对齐 v1）

3 个并行 agent 完成 + 一次主线程手动接入（F3 agent 对 CanvasView/ui store 的 edit 未生效，主线程补做）。

### F1 复制粘贴

新 `web/src/composables/useClipboard.ts`：`useClipboard()` → `{ copy(), paste() }`，导出 `CLIPBOARD_MAGIC = 'hikari-canvas-v1:'`。

剪贴板格式：`hikari-canvas-v1:{"elements":[...深 clone 完整 element],"timestamp":"<iso>","sourceWallId":"<id|null>"}`。magic header 不匹配静默忽略 → 不干扰文本框常规 paste。

- `useCanvasShortcuts.ts`：Ctrl/Cmd+C → copy，Ctrl/Cmd+V → paste；裸 c 仍走 circle 工具；inEditable 时不触发
- copy 不读 isLocked（只读安全）；paste 检查 isLocked + pickWritableLayer fallback
- 元素剥离：JSON 深 clone → 剥 id + type（顶层 envelope）→ x/y +10 偏移
- z-order：copy 按 layer.elements 索引升序，paste 按数组顺序逐个 ws.send，新元素堆叠顺序一致
- 发 `element.add` 显式带 `layerId`（EditOpDispatcher 已支持）
- PathElement.d / BrushPoint[] 子坐标相对 bbox 不变换
- 反馈复用 `net.pushLog('meta'|'err', ...)`
- i18n：`clipboard.copySuccess(n) / pasteSuccess(n) / pasteRejectedLocked / pasteParseFailed` 中英

### F5 Canvas.background → Fill 联合类型

**后端**：
- `ProjectState.Canvas` `String background` → `Fill background`；新 `@JsonCreator` 接收 Fill；保留 `(int, int, String)` 兼容构造器内部 wrap Solid；紧凑构造器默认 `Fill.solid("#FFFFFF")`
- `Fill` 加静态 helper `Fill.solid(String)`
- **FillDeserializer**：M11 已存在 string → SolidFill 路径，无需新增；`Fill` 自身已 `@JsonDeserialize(using = FillDeserializer.class)`，整条反序列化链路自动复用——WebServer / RendererSnapshotTest 共用同一 ObjectMapper 都生效
- `CanvasCompositor.rasterize` 入口 paint bg 改 `g.setPaint(FillPaintBuilder.fillToPaint(canvas.background(), 0, 0, w, h))`；渐变 bbox = 整画布
- `WallRestorer.isPristine` / `CanvasProjector.isPristine`：旧 `"#FFFFFF".equalsIgnoreCase` → 新 `isWhiteSolid(Fill)`（仅 SolidFill #FFFFFF[FF] 命中）
- `EditSession.setBackground(Fill)` 跑 FillValidator；旧 `setBackground(String)` wrap 调新；新 `replaceContent(Fill, List)` overload
- `TemplateInstantiator.instantiateRawState`：state.canvas().background() 现是 Fill；只在 SolidFill 抽 color，渐变背景 v1 raw_state 模板 fallback `"#FFFFFF"`
- `EditOpDispatcher canvas.background`：优先识别新 `fill` 字段，兼容老 `color` 字段
- 测试 `EditSessionReplaceContentTest` 4 处类型对比改 SolidFill；replaceContent(null,...) 加 `(String) null` 消歧

**baseline 14 fixture**：`:plugin:test --rerun-tasks` 全绿，**0 像素漂移**（FillDeserializer 自动处理 fixture `"background": "#xxx"` 字符串）

**前端**：
- `types/protocol.ts Canvas.background`: `string` → `FillCompat`
- `PreviewRenderer.ts` entry paint 改 `fillToCanvasStyle(ctx, state?.canvas.background, 0, 0, w, h) ?? '#FFFFFF'`
- `stores/palette.ts` projectColors：`bg.toUpperCase()` → `fillColors(bg)` 全 stop 提取
- `stores/project.ts` patch apply：Canvas record 类型混合后加 `as unknown as` 中转
- 新 `CanvasSettingsSection.vue`：未选中元素时挂载；`FillInput` 编辑；alpha<1 用 CSS 棋盘格 UI 提示（仅视觉，不参与 PaletteLut）；emit `ws.send('canvas.background', { fill })`
- `RightPanel.vue` `!selected` 分支挂载
- i18n：`canvas.settings` / `canvas.backgroundLabel` 中英

**WS op**：未引入新 op；`canvas.background` payload 升级 `{fill}`（新）或 `{color}`（兼容）

### F3 智能对齐 v1（grid + 锚点 + 元素边/中点）

新 `web/src/composables/useSnapManager.ts`：
- API：`snap(rawX, rawY, w, h, excludeIds) → SnapHints { snappedX, snappedY, activeXAxes, activeYAxes }`
- 候选轴：canvas 3 项（0 / w/2 / w）+ element 6 项/个（left/cx/right + top/cy/bot，按 layer.visible + el.visible 过滤、excludeIds 排除）+ grid floor/ceil 倍数（仅 gridSize>0）
- `snapAxis` 两遍扫描：先 anchors（left/center/right）× candidates 找最近距离 ≤ threshold；再收集"应用 bestDelta 后恰好命中"的所有 candidate 作 activeAxes（多线同时命中 → visualizer M17.4 用）
- bypass 钩子：true 时 snap 透传 raw（shift 临时禁用）

**ui store**：`snapEnabled / snapToGrid / snapToCanvas / snapToElement / snapThreshold`，localStorage key `hikari-canvas:snap` 持久化（与 theme/locale 同级），threshold 范围 [1, 64]，默认 `{enabled:true, toGrid:false, toCanvas:true, toElement:true, threshold:8}`

**CanvasView 接入**（主线程手动补做——agent 的 edit 没生效）：
- `onDragStart` 单选 / 多选都 set dragInitial：单选时也 init 自己 leader 的初始 (x,y)。M17-P1 漏修——原代码单选时 dragInitial 是空 Map → onDragMove `initLeader === undefined` → return → leader 仍不跟手。**真正完成 F2 修复**
- `onDragMove`：调 `snapManager.snap(leaderX, leaderY, w, h, new Set(dragInitial.keys()))`；snap 后把 Konva 节点位置回写到 snap 落点（视觉跟手）；leader.store + follower delta 都用 snapped 坐标
- 新 `isShiftDown` ref + window keydown/keyup/blur 维护；传给 snapManager bypass

**性能**：100 elements ≈ 600 候选 × 3 锚点 ≈ 1800 比较 / frame，O(n) 线性；rAF 自合并；spatial index 留 v1.x

### 关键事故记录

F3 agent 声称"已改 CanvasView.vue + ui.ts"但 git diff 显示无改动——edit 静默失败。已 fallback 由主线程手动补做。这是 agent 协作中的隐性失败模式，需要在 commit 前用 git diff 验证。

### 验证

- `:plugin:test --rerun-tasks` BUILD SUCCESSFUL，14 RendererSnapshotTest baseline 0 漂移，EditSession 测试全绿
- `vite build` 403ms / 493.89 kB / 153.11 kB gzip，0 新 TS 错误

### 关联文件

**新文件**：`web/src/composables/useClipboard.ts` / `web/src/composables/useSnapManager.ts` / `web/src/components/properties/CanvasSettingsSection.vue`

**改动 Java**：`plugin/src/main/java/moe/hikari/canvas/`: `state/Fill.java` / `state/ProjectState.java` / `state/EditSession.java` / `render/CanvasCompositor.java` / `render/CanvasProjector.java` / `render/WallRestorer.java` / `template/TemplateInstantiator.java` / `web/EditOpDispatcher.java` / test `EditSessionReplaceContentTest.java`

**改动前端**：`web/src/`: `composables/useCanvasShortcuts.ts` / `components/layout/CanvasView.vue` / `components/layout/RightPanel.vue` / `render/PreviewRenderer.ts` / `stores/palette.ts` / `stores/project.ts` / `stores/ui.ts` / `types/protocol.ts` / `i18n/messages.ts`

---

## 2026-05-17 · M17 Phase 1（F2 拖动跟手修复 + F4 自由拖动画布）

针对生产级 UX 增强 / bug 修复 2 项，2 个并行 agent 完成。

### F2 onDragMove 单选 path 实时跟手（bug 修复）

**根因**：HikariCanvas 编辑器双层渲染——顶层 Konva 透明 hit-test 矩形（fill rgba 0.001）+ 底层 Canvas 2D PreviewRenderer 像素化输出（dither + 248 调色板）。用户实际看到的是底层 Canvas。底层重绘依赖 `requestDraw()` → `watch(project.state, {deep:true})`。

CanvasView.vue `onDragMove(ev, id)` 原逻辑 `if (dragInitial.value.size <= 1) return;` 单选场景早 return，根本不更新 store → PreviewRenderer 不重绘 → 视觉上"松手才跳到位"。

修复：删早 return，单选 / 多选都先 mutate leader 自身 store + `requestDraw()`，多选 path 的 follower 同步逻辑保持不变（含 M15.3 P0-1 `init.x !== otherX` 判等修复）。**onDragEnd 仍是唯一 ws.send 入口**（保持 60fps 不塞爆服务端）。

性能：`requestDraw()` 内部 rAF 自合并（line 458-460 `if (drawPending) return`），天然 60fps 上限；浏览器 dragmove ≤60Hz；不预防性加 throttle，500+ 元素 wall 实测卡顿时再加。

### F4 自由拖动画布 + 1024px 虚空白边

**新 'hand' 工具**：
- `ui.ActiveTool` union 加 `'hand'`；isDrawTool 排除（保留 selection / hover）；loadTool KNOWN 列表加 hand
- LeftTools Move 按钮后插入 Hand 工具（lucide Hand icon，H 快捷键）
- `useCanvasShortcuts` H 键激活；keydown Space 临时切（闭包 `spaceSavedTool` 保存原工具）、keyup 恢复；`e.repeat` 保护防 OS 长按重复；window blur 兜底防卡死；keydown preventDefault 防 Space 触发页面滚动

**虚空白边 1024px**：
- CanvasView 模板 inner wrapper 原 `p-8` 改 class `hc-canvas-padding` + scoped CSS `padding: 1024px`（Tailwind arbitrary 值在 scoped 偶失效，直接 CSS 最稳）
- onMounted nextTick 主动 `scrollLeft / scrollTop = (scrollWidth - clientWidth) / 2` 居中（padding 让 scrollWidth >viewport，不主动滚则首屏停在 padding 空白）
- fitToViewport 不受影响（仍按 outer.clientWidth - 64 算缩放，不读 scrollWidth）

**cursor 反馈**：CanvasView `cursorStyle` computed → `isPanning` → grabbing / `activeTool === 'hand'` → grab / drawTool → crosshair / else default。`usePanScroll` 暴露新 `isPanning` ref；onMouseDown 加 `button === 0 && activeTool === 'hand'` 分支（与中键 / Alt+左键并列）

**hand 工具下交互屏蔽**：
- hitConfig `listening: false`（与 drawTool 同路径），左键穿透到 outer pan
- onStageMouseDown 早 return 不启 marquee / draw
- useTransformerManager 不挂 Transformer 锚点

i18n 加 `tools.handTool` zh/en + `help.midMouseOrAlt` 文案补 `H 工具 / 按住 Space`。

### 验证

`vite build` 315ms / 486.87 kB ok。vue-tsc 我改动 0 新 TS 错误（既有 baseline 错误来自 DragEvt / node.x(v) setter 重载、Tooltip / useTransformerManager / i18n / PreviewRenderer / vite.config @types/node 等历史问题）。

### 关联文件

`web/src/`: `stores/ui.ts` / `components/layout/CanvasView.vue` / `components/layout/LeftTools.vue` / `composables/useCanvasShortcuts.ts` / `composables/usePanScroll.ts` / `composables/useTransformerManager.ts` / `i18n/messages.ts`。

---

## 2026-05-16 · M16 Phase 7（docs 同步 + 收尾）

针对 M16 6 phase 28 项改动同步契约文档；ultrareview-2026-05-15.md 落地删除（已被 05-16 review 取代）；ultrareview-2026-05-16.md 入仓存档。

### 文档同步（1 agent）

- **CLAUDE.md**：里程碑表 M13/M14/M15/M16 全 ✅；M15 段下加 M16 完整段（6 phase commit batch + 4 个 M16 关键架构决策固化），引用 6 个 commit sha
- **security.md**：§2.4 SessionRateLimiter 未实装状态 + v1.x 留档；新 §2.5 会话级 IP 绑定（方案 B 不绑 token、绑 session）+ IPv6/XFF 限制；§权限节点补 `canvas.template.use-others` / `canvas.alias.any` / `canvas.admin.bypass-lock`；§审计补 5 新事件 + WALL_ALIAS + POOL_RELEASE_TO_FREE
- **architecture.md**：新 §3.6.1 草稿 wall 协作语义（Q1 决策落地：未锁 wall 任何玩家可 open，协作中间态）+ §3.6.2 多世界假设（按 world UUID 分桶）；§10.4 安全补 IP 绑定 / Origin 白名单 / 5s auth timeout；§12 未决「多世界分池」标 [x] 已决
- **data-model.md**：§2.1 HikariCP maxPoolSize=4 不缩 1 的理由（WAL 读并发 + leakDetectionThreshold 兜底）；§6.5 V005 + 新 §6.5.1 V010 DROP COLUMN refcount（pre-release 0.1.x SNAPSHOT 阶段激进改 schema OK）
- **protocol.md**：§3.2 WS 握手 `client_v` + ready `accepted_v` + 5s auth timeout / Origin 白名单；§6.2 close codes 4001/4002（auth_timeout / ip_mismatch / CLOSE_PROTOCOL_VERSION_UNSUPPORTED）；§6.1 error codes 补 UNAUTHORIZED / QUOTA_EXCEEDED_DISK；§9.5 `GET /api/upload/{source}` 改为强制 `?session=` query 鉴权；§11 安全补 IP 绑定 + FAIL_ON_UNKNOWN_PROPERTIES

### Q1 / 多世界 / IP 绑定语义落地（关键决策固化）

- **Q1 草稿 wall**：写入 `architecture.md §3.6.1` —— 行为/状态机决策不是 schema 决策；保留「未来 ACL 走 v1.x 协作 scope」逃生通道
- **多世界**：新 `§3.6.2` 而非改写 §4 ——§4 仍是单世界概念介绍，§3.6.2 是 M16-P2.3 增量补丁层
- **IP 绑定**：`security.md §2.5` 主段 + `architecture.md §10.4` / `protocol.md §11` 交叉引用；明示「绑 session 不绑 token」+ IPv6/XFF 限制
- **HikariCP=4**：`data-model.md §2.1` 用对话式表达「为何不缩到 1」

### 已知留档（v1.x scope）

- M13 路线段（M16 已完成）保留为历史规划留档（类似 M5.5 段做法），里程碑表已标 ✅
- SessionRateLimiter 仍 P1 未实装
- IPv4-mapped IPv6 与 IPv4 字符串不归一化
- 反代场景 XFF 未解（IP 绑定仅看 socket peer）

### 收尾

- ultrareview-2026-05-15.md 落地删除（被 05-16 review 取代）
- ultrareview-2026-05-16.md 入仓存档（M16 工作的 ~200 条去重 P0/P1/P2/P3 完整原文）

### 关联文件

`CLAUDE.md` / `docs/architecture.md` / `docs/data-model.md` / `docs/protocol.md` / `docs/security.md` / `docs/journal.md` / `docs/ultrareview-2026-05-15.md`（删）/ `docs/ultrareview-2026-05-16.md`（新入仓）。

---

## 2026-05-16 · M16 收尾总览

**M16 = 28 项 P0/P1 落地 + 7 phase commit batch + 0 baseline 漂移**

针对第三方 AI ultrareview 2026-05-16（≈200 条去重问题）的最高优先级 P0/P1 安全 + 数据完整性 + 防御层修复。继 M15 大重构后第二轮全栈收口。

| Phase | 主题 | 项数 | commit |
|---|---|---:|---|
| M16-P1 | 安全 P0 核心（鉴权 / DoS / IDOR / Origin） | 7 | `4695269` |
| M16-P2 | 数据完整性 / 并发 P0 | 7 | `8564275` |
| M16-P3 | 渲染防御 P0 | 4 | `9747975` |
| M16-P4 | 前端资源生命周期 P0 | 3 | `3c800f8` |
| M16-P5 | 构建依赖 P0 | 3 | `d9ab3fc` |
| M16-P6 | P1 防御 + 观测 | 8 | `ca4bc54` |
| M16-P7 | docs 同步 | — | （本 commit） |
| **合计** | | **32** | **7 commit** |

### M16 4 个关键架构决策（已固化）

1. **草稿 wall = 协作中间态**（Q1 决策）：未锁 wall 任何玩家可 open，多人可同步编辑同一画板；只有 owner 可触发 lock；lock 后非 owner 不能 open（除 canvas.admin.bypass-lock）。未来 ACL 走 v1.x 协作 scope（详见 architecture.md §3.6.1）
2. **shadowJar 全 relocate**（Q2 决策）：jackson / caffeine / jdbi / hikari / javalin / jetty / snakeyaml 都 relocate 到 `moe.hikari.canvas.shaded.*`；org.sqlite 保护 JNI 不动；PacketEvents 走 plugin-loader 模式不 shade
3. **HikariCP maxPoolSize=4 保留**（Q3 决策）：SQLite WAL 模式下读并发友好；leakDetectionThreshold=30s 兜底；不缩到 1（详见 data-model.md §2.1）
4. **会话级 IP 绑定（方案 B）**：Token 不绑 IP（confirm 阶段无 HTTP context），改 Session.boundIp 首次 auth 时 CAS；后续重连必须同 IP；玩家切网络必须重 `/canvas confirm`（详见 security.md §2.5）

### M16 累计技术细节

- **新文件**：`web/Protocol.java` / `ForbiddenTemplateException.java` / `db-migrations/V010__remove_refcount.sql`
- **新配置项**：`network.ws-auth-timeout-seconds` / `network.allowed-origins` / `map-pool.per-world`
- **新权限节点**：`canvas.template.use-others` / `canvas.alias.any`
- **新协议字段**：auth `client_v` / ready `accepted_v` / close codes 4001 (auth_timeout) / 4002 (protocol_version_unsupported)
- **新审计事件**：WALL_LOCK / WALL_UNLOCK / WALL_ALIAS / IMAGE_UPLOAD_OK / IMAGE_UPLOAD_REJECTED / PERMISSION_DENIED / POOL_RELEASE_TO_FREE
- **核心风险修复**：WallRestorer 失败 releaseToFree 防 idcounts.dat 膨胀（项目核心风险）
- **god class 进一步收口**：Phase 6 SessionManager 锁拆分 + Bukkit API 全挪锁外，避免 lock-order 死锁

### 待办留档（v1.x / 不阻塞 0.1.0 发版）

- 14 章 Group III 长期项：CI 设置、vitest、Playwright E2E、MockBukkit / JavalinTest 完整使用、双端镜像算法 fixture 自动校验、MapPool 进一步拆分、多世界 wand 支持
- SessionRateLimiter / token 暴力枚举防御（docs/security.md §2.4 规定但未实装）
- IPv6 / XFF 归一化
- 协议版本升级路径（v3 时的迁移策略）

### 评估

main 分支当前安全状态远优于 M15 之前。剩余多为非阻塞细化项，**项目处于可用 0.1.0 发版候选状态**。

---

## 2026-05-16 · M16 Phase 6（P1 防御 + 观测 8 项）

针对 docs/ultrareview-2026-05-16.md P1 防御层，3 个并行 agent 完成。Phase 6 是 M16 最长阶段。

### P6.1 Jackson 严格 + 错误消息脱敏

`WebServer` ObjectMapper 加 `FAIL_ON_UNKNOWN_PROPERTIES=true` 接收侧严格；发送侧保持 NON_NULL 不变。WS messageAsClass / WebHelpers.mapOrEmpty / UploadHandler 多处 `e.getMessage()` 改为固定 friendly 文案 + `log.log(Level.WARNING)` 服务端。dispatcher 内的 `iae.getMessage()` 保留（已是预定义 friendly 常量）。

### P6.2 协议版本协商

新 `web/Protocol.java`：`SUPPORTED_MIN=2 / SUPPORTED_MAX=2 / CLOSE_PROTOCOL_VERSION_UNSUPPORTED=4002 / isSupported(int)`。区分 envelope schema v vs business protocol version。

- WebServer.handleAuth：原 M8-C 单条 `< 2` 检查改为范围；优先读 `client_v`，回落 `clientProtocolVersion`（兼容）；ready payload 加 `accepted_v`
- wsClient.ts 顶层 `CLIENT_V = 2`；sendAuth 双带（新+旧字段）；handleReady 双向校验 accepted_v，不匹配 close 4002 + `stopped=true` 阻止重连风暴
- ReadyPayload 加 `accepted_v?: number`（旧后端不发为 undefined → 旧路径继续工作）

### P6.3 image_uploads.refcount 列移除

新 `V010__remove_refcount.sql` 直接 DROP COLUMN（sqlite-jdbc 3.53 对应 SQLite ≥ 3.45 原生支持）。`ImageUploadDao.Row` record 删 refcount 字段；insertOn / mapRow 同步；调用方 UploadHandler / ImageStorage / ImageQuotaServiceTest 4 处构造同步删尾 1。

### P6.4 AuditLog 补全

新事件常量：`WALL_LOCK` / `WALL_UNLOCK` / `IMAGE_UPLOAD_OK` / `IMAGE_UPLOAD_REJECTED` / `PERMISSION_DENIED`。

调用点：
- WallOpDispatcher：lock/unlock 成功 + alias 非 owner 拒
- SessionManager.open lock-aware 拒（reason=lock_owner_only）
- EditOpDispatcher.template.apply ForbiddenTemplateException
- UploadHandler 10+ 拒绝路径 + 成功路径（reason 是稳定 token 不含文件名 / 路径）

write 失败 fallback：`AuditLog.java` 从 `log.severe(String)` 改 `log.log(Level.SEVERE, msg, e)` 保 stack trace。

### P6.5 editor-url 协议白名单

`HikariCanvasConfig.sanitizeEditorUrl`：CR/LF 检测 → `{token}` 替换 PLACEHOLDER → `URI.create()` 解析 → scheme 必须 `http` 或 `https`（lower-case）。非法 → `log.severe` + 回退默认。config.yml 注释加警告。

### P6.6 Token + WS IP 绑定（方案 B：会话级）

Token 不绑（confirm 阶段无 HTTP context），改会话级。

- Session 加 `boundIp` 字段
- SessionManager.bindOrCheckIp：新 `IpBindResult { OK, BOUND, MISMATCH, NO_SESSION }`；首次绑定在 writeLock 内 CAS 防 race
- WebServer.handleAuth：consume token + session lookup 后调 bindOrCheckIp(presentedIp)；MISMATCH → close auth_failed + log.warning（外部仍是 4001）
- 已知限制：IPv4-mapped IPv6 不 norm；反代场景未解 XFF；玩家切网络必须重 confirm

### P6.7 SessionManager ReadWriteLock

三 map（byId / byPlayer / byWall）改 `ConcurrentHashMap`，所有 `synchronized(this)` 砍掉。

新 `ReentrantLock writeLock` 守护跨 map 复合写：`beginSelecting` / `confirm` / `open` / `cancel` / `deleteWall` / `bindOrCheckIp`。read-modify-write 用 `putIfAbsent` 防 race。

**持锁中调 Bukkit API 全部挪到锁外**：`Bukkit.getPlayer` / `Bukkit.getWorld` / MapPool 系列 / wallRepo / auditLog / forgetHooks 回调——锁内只做 map mutate + Session 元数据 read。`runForgetHooks` 从 `forget()` 拆出在锁外执行（防 hook 回调持外部锁 → lock-order 死锁）。

副作用：confirm 加 `WallRaceException` sentinel 处理 putIfAbsent race；新 `disconnect()` 非主线程版 cancel。public API 全保留。

### P6.8 Optimistic mutation 回滚

`TopBar.toggleLock` / `commitAliasEdit` 改 async：save prev → optimistic mutate → `await ws.sendWithAck(..., 5000)` → catch 时回滚 + `net.lastError` 分流错误码。

连击防护：`lockInFlight` / `aliasInFlight` ref，pending 直接 return。lock 按钮 `:disabled="!isOwner || lockInFlight"`。

砍掉旧 `watch(net.lastError)` 拦截副作用（避免误触发其它 op 报错时显示别名 UI）。

i18n 加 `wall.lockFailed / unlockFailed / aliasFailed`（中英）。

### 验证

`:plugin:test --rerun-tasks` 全绿；`vite build` 通过（dist 485kB / gzip 150kB）。先清 macOS `* 2.class` 污染。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `HikariCanvas.java` / `HikariCanvasConfig.java` / `image/ImageStorage.java` / `image/UploadHandler.java` / `session/Session.java` / `session/SessionManager.java` / `storage/AuditLog.java` / `storage/ImageUploadDao.java` / `web/EditOpDispatcher.java` / `web/WallOpDispatcher.java` / `web/WebHelpers.java` / `web/WebServer.java` / `web/Protocol.java`（新）。`db-migrations/V010__remove_refcount.sql`（新）/ `config.yml`。`plugin/src/test/.../ImageQuotaServiceTest.java`。`web/src/`: `components/layout/TopBar.vue` / `i18n/messages.ts` / `network/wsClient.ts` / `types/protocol.ts`。

---

## 2026-05-16 · M16 Phase 5（构建依赖 P0 3 项）

针对 docs/ultrareview-2026-05-16.md 第十四章构建 P0，单 agent 完成。

### P5.1 shadowJar relocate

`plugin/build.gradle.kts` shadowJar block 加 7 条 `relocate`：

```
com.fasterxml.jackson         → moe.hikari.canvas.shaded.jackson
com.github.benmanes.caffeine  → moe.hikari.canvas.shaded.caffeine
org.jdbi                      → moe.hikari.canvas.shaded.jdbi
com.zaxxer.hikari             → moe.hikari.canvas.shaded.hikari
io.javalin                    → moe.hikari.canvas.shaded.javalin
org.eclipse.jetty             → moe.hikari.canvas.shaded.jetty
org.yaml.snakeyaml            → moe.hikari.canvas.shaded.snakeyaml
```

**关键**：snakeyaml 必须同步 relocate（jackson-yaml 间接依赖，否则破解析）。**不动**：`org.sqlite`（JNI native lib，relocate 会导致 native 加载失败）；PacketEvents（plugin-loader 模式，compileOnly 即可）。

`mergeServiceFiles()` 保留，处理 META-INF/services 下 Jackson Module SPI / JDBI Plugin SPI / Jetty SPI 等。

**unzip 验证**（HikariCanvas-0.1.0-SNAPSHOT-all.jar）：
- 旧路径泄漏：**0 条**
- `moe/hikari/canvas/shaded/` 下：**5397 条** entries
- `org/sqlite/JDBC.class` 保留原路径（JNI 安全）
- `META-INF/services/*` 自动改名（`moe.hikari.canvas.shaded.jackson.core.JsonFactory` / `...jdbi.v3.core.spi.JdbiPlugin` 等）

### P5.2 HikariCP leakDetectionThreshold

`Database.java` HikariConfig 加 `cfg.setLeakDetectionThreshold(30_000)`。SQLite 单写场景下任何超 30s 未还连接都肯定是 bug，dev/prod 都启用。

### P5.3 paperweight 锁定 + npm ci

- paperweight-userdev 已锁 `2.0.0-beta.21`（无需改）
- `installWebDeps` task 从 `npm install` 改 `npm ci`；`web/package-lock.json` 与 package.json 一致（vite build 1728 modules 已验证）

### 验证

- `./gradlew :plugin:shadowJar`：SUCCESS（32s 全量 / 2s 增量）
- `./gradlew :plugin:test --rerun-tasks`：**364 tests / 0 failures / 0 errors**
- 先清掉 macOS Finder `* 2.class` 同步残留（已知陷阱）

### 关联文件

`plugin/build.gradle.kts` / `plugin/src/main/java/moe/hikari/canvas/storage/Database.java`。

---

## 2026-05-16 · M16 Phase 4（前端资源生命周期 P0 3 项）

针对 docs/ultrareview-2026-05-16.md 前端 P0，单 agent 完成。

### P4.1 setPointerCapture 异常 release

`useBrushHost.ts` 整文件重写。

- `tryCapture(target, pointerId)` / `tryRelease()` 包 try-catch
- 维护独立 `capturedTarget` / `capturedPointerId`，不依赖 PointerEvent.target
- `abortStroke()` idempotent：检查 brushController.isActive 后 cancel + 总是 tryRelease
- pointerDown capture 失败时不调 brushController.pointerDown 避免半死
- 新监听：元素 `pointercancel` / window `blur` / document `visibilitychange` 都 abortStroke
- onScopeDispose 兜底 removeEventListener + abortStroke

### P4.2 Konva + Pinia 卸载清理

**Konva**（`CanvasView.vue`）：
- 新 `drawRafId` 变量记录 requestAnimationFrame id
- `onBeforeUnmount`：cancelAnimationFrame + `stage.destroy()`（级联清 Layer / Transformer / 内部 listener / 2D ctx / cache）+ ref 置 null
- 全仓 grep 确认：composables（useTransformerManager / useMarqueeSelection / useDrawToCreate / usePanScroll / useCanvasShortcuts / useCanvasUpload）全部通过 vue-konva template ref 拿节点，不直接 `new Konva.*`；watch / useEventListener / onKeyStroke 已在 effect scope 内自动 cleanup。ResizeObserver / IntersectionObserver 全仓 0 引用

**Pinia reset**：
- `stores/project.ts` 加 `reset()`：清 state / lastAddedElementId / wallId / alias / lockedAt / ownerUuid / selfUuid
- `stores/ui.ts` 加 `reset()`：清 selectedIds / editingLayerId / logDrawerOpen / helpOpen；**保留** theme / locale / activeTool / zoom / 面板折叠（用户偏好跨 wall）
- palette / brush / templates / network store **不 reset**（localStorage 持久化偏好）
- 调用点：`wsClient.ts handleReady` 仅当 `project.wallId !== null && wallId !== incomingWallId`（切 wall）时调，**同 wall 重连不触发** 避 UI 闪烁

### P4.3 pendingAcks onClose 清理

`wsClient.ts onClose` 加：遍历 pendingAcks，clearTimeout + reject('connection closed before ack') + Map.clear()。`seq` 计数器保持现状不重置。`sendWithAck` 超时 / ack / error 三个 delete 点已齐备。

### 验证

- `vite build` 372ms / 1728 modules ok（dist 484kB / gzip 150kB）
- `vue-tsc --noEmit` 仅 baseline 预存错误（useTransformerManager 重复签名、i18n、PreviewRenderer @ts-expect-error、vite.config @types/node），与本次无关；改的 5 个文件 0 新错误

### 关联文件

`web/src/components/layout/CanvasView.vue` / `composables/useBrushHost.ts` / `network/wsClient.ts` / `stores/project.ts` / `stores/ui.ts`。

---

## 2026-05-16 · M16 Phase 3（渲染防御 P0 4 项）

针对 docs/ultrareview-2026-05-16.md 渲染层 P0 防御，单 agent 完成。

### P3.1 负 w/h 防御

**协议入口已校验**：`ElementValidator.validateDim` 已强 `1..10000`，EditSession 49 处调用全经过——协议层无新增。

**渲染层兜底**：`RectRenderer` / `CircleRenderer` / `ShapeRenderer` / `ImageRenderer` 入口加 `if (w <= 0 || h <= 0) return;`。

### P3.2 NaN/Inf 过滤

新增 `ElementValidator.finiteOr(double, double)` + float 重载 package-public 静态工具。CanvasCompositor 入口对 element/layer opacity 做 `finiteOr` 再 clamp。

**重要发现**：`Element.rotation()` 返回 int（非 double），物理上 NaN 不可能——task 描述提到的 `if (e.rotation() != 0)` 永真路径在此 codebase 不触发，保留原逻辑。Shadow.dx/dy / Glow.radius 同样是 int。

FillPaintBuilder：新增 `filterFiniteStops()` 剔除 NaN offset 的 stops；< 2 个有效 stop 时降级首 stop 纯色；linear angle / radial cx/cy/r 加 `Double.isFinite` 兜底（防 raw_state 模板绕过 FillValidator）。

### P3.3 ImageRenderer mask 越界防御

`applyImageMaskClipSafely(g, im, ctx)` 替换原方法，Area / PathParser / g.clip 整段包 `try { ... } catch (InternalError | RuntimeException ex)`；fail 时 log.warning 并降级到无 mask（caller 的 outer try-finally 用 `savedClip` 兜底）。

**bbox sanity 阈值 = 10×**：mask path bbox area > element area × 10 时直接降级。这容忍合理的"略大于 bbox 的 mask"（外发光式），同时拒掉 100×+ 恶意/损坏数据触发 AWT Area O(n²) 卡死。

happy-path 性能不变：try-catch 只包 Area boolean op，不圈整个 drawImage。

### P3.4 前端 Mask 预设兜底

`ImageElementSection.vue` 新增 `sanitizeDimension(v: number)`（finite + >0 + clamp 16384，否则 fallback 1）和 `sanitizeRadius(v, maxR)`（额外 clamp 到 `[0.5, maxR]`）。`makeCircleD` / `makeEllipseD` / `makeRoundedRectD` 全入口过 sanitize；roundedRect 启发式半径 clamp 到 `min(w, h) / 2`。

### 验证

`./gradlew :plugin:test` 364 tests pass，0 failures；`RendererSnapshotTest` 14 fixtures（含 13-image-mask）**baseline 0 漂移**；`vite build` 1728 modules ok。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `state/ElementValidator.java` / `render/CanvasCompositor.java` / `render/RectRenderer.java` / `render/CircleRenderer.java` / `render/ShapeRenderer.java` / `render/ImageRenderer.java` / `render/FillPaintBuilder.java`。`web/src/components/properties/ImageElementSection.vue`。

---

## 2026-05-16 · M16 Phase 2（数据完整性 / 并发 P0 7 项）

针对 docs/ultrareview-2026-05-16.md 数据一致性 P0，3 个并行 agent 完成。

### P2.1 + P2.2 上传配额 + 磁盘/DB 原子化

并发上传可超额（stateless check → insert），磁盘写半截留孤儿。整合到单事务：

- `ImageUploadDao` 加事务感知重载 `*On(Handle, ...)`，不吞异常便于回滚
- `ImageQuotaService.tryReserveQuotaOn(Handle, ...)` 在传入事务内顺序：per-day count → disk sum → LRU `pickLruCandidatesOn + deleteOn` 循环 → `insertOn`；返 sealed `QuotaResult { Reserved | DeniedPerDay | DeniedDiskAfterLru }`，`Reserved` 携 evictedHashes
- `UploadHandler` 走 `jdbi.inTransaction(SERIALIZABLE, ...)`，事务首句 `UPDATE __locker__` 升级到 SQLite RESERVED 写锁
- `ImageStorage.writeFileAtomic`：写 `<hash>.png.tmp` → `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；同 hash `ReentrantLock` 串行；目标已存在 idempotent 跳过
- 流程：编码 PNG（事务外）→ 取 writeLock → 事务（quota + evict + insert）→ commit → `writeFileAtomic` + 多个 `deleteFileOnly`；写文件失败开补偿 tx 删孤儿行

错误码：`429 QUOTA_EXCEEDED` / `413 QUOTA_EXCEEDED_DISK`。

### P2.3 + P2.4 MapPool 多世界化 + bindToWall 一致性

- `freeQueue` → `Map<UUID worldId, Deque<Integer>>`，按 world 分桶
- `reserveForWall(wallId, count, World world)`；同 world FREE 不足时 `expand(world, n)`；超 max 抛 `PoolExhaustedException`（错误含 world 名）
- `initialize(World defaultWorld, Map<String, Integer> perWorldInitial)`：扫持久化行按 `MapView.getWorld()` 归桶，按 perWorldInitial 扩容，default world 补齐
- 调用点：`HikariCanvas.initialize` / `SessionManager` 4 处 `bindToWall` + `reserveForWall` 全部传 world
- `bindToWall` 校验 mapView.world == expected.world，不一致抛 `IllegalStateException`（不可恢复内部 bug）
- 配置：`map-pool.per-world: {}`（默认空，按 world 名 → initial count）

### P2.5 WallRestorer 池泄漏修复（**项目核心风险**）

启动期 restore 失败留 acquired map 不归还 → idcounts.dat 膨胀。

- `WallRestorer.restoreOne` 用 `try-catch(RuntimeException | Error)` 包裹；catch 时 for-loop `mapPool.releaseToFree(mid)` 释放本轮所有 bound map（bindToWall 已是原子语义：先全扫描后全更新，部分失败 = 0 bind）
- 新 `MapPool.releaseToFree(mapId)`：不论 owner 强制 RESERVED → FREE，归还到 mapView world 桶；audit `POOL_RELEASE_TO_FREE`
- 失败 wall ID 收集到 `failedRestoreWallIds` 不可变 set；wall row 在 DB 保留供重试
- `WandListener` 加 7-arg 构造接 WallRestorer：玩家用 wand 选到 failed wall 时红字 ActionBar「Wall failed to restore on startup, see server log」，不进 open

### P2.6 pendingDeletes 多 wall 支持

`Map<UUID, PendingDelete>` → `ConcurrentMap<UUID, ConcurrentMap<wallId, PendingDelete>>`。同玩家 30s 窗口内不同 wall pending 互不覆盖；同 wall 二次敲提示「Already pending, type confirm」。新内部 `QuitListener` 监听 `PlayerQuitEvent` 整层 remove。

### P2.7 SessionManager confirm 原子回滚 + 主线程断言

- 新 `SessionConfirmFailedException`
- confirm 维护 `Deque<Runnable> rollbacks` LIFO 倒序栈：3 步 push（`releaseWall(pending)` / `wallRepo.delete(wallId)` / `releaseWall(wallId)`）；bind race / 通用异常都 catch 跑 rollbacks 再抛 SessionConfirmFailedException；`runRollbacks` 单条 try/catch 不掩盖原异常
- `CanvasCommand.runConfirm` catch 显示「Failed to confirm session, server log」
- `assertMainThread()` 加在 `SessionManager` (confirm/cancel/deleteWall) + `MapPool` (initialize/reserveForWall/bindToWall/releaseWall/releaseToFree) 8 处
- 测试兼容：`Bukkit.getServer()` null 时跳过断言（纯单测环境）

### 验证

`./gradlew :plugin:test --rerun-tasks` 全绿。先 macOS Finder `* 2.class` 重复污染清掉。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `HikariCanvas.java` / `HikariCanvasConfig.java` / `command/CanvasCommand.java` / `image/ImageQuotaService.java` / `image/ImageStorage.java` / `image/UploadHandler.java` / `pool/MapPool.java` / `render/WallRestorer.java` / `session/SessionManager.java` / `session/WandListener.java` / `storage/ImageUploadDao.java`。`config.yml`。

---

## 2026-05-16 · M16 Phase 1（安全 P0 核心 7 项）

针对 `docs/ultrareview-2026-05-16.md` ≈200 条去重问题中的最高优先级安全 P0，分 3 个并行 agent（network / template / wall）完成 7 项。

### P1.1 `/api/upload/{hash}` 加 sessionId 鉴权（IDOR）

下载端点改为要求 `?sessionId=<id>` query，由 SessionManager 校验 active；命中后响应 `Cache-Control: private` 防代理缓存命中跨用户。前端 PreviewRenderer 加 `setUploadAuthProvider()` 钩子，App.vue 启动时注入 `net.sessionId`。

**未采用 TokenService**：token 是 one-shot consume，rotate 后会触发反复刷新；sessionId 等价 AUTHED 语义。

涉及：`UploadHandler.java` / `PreviewRenderer.ts` / `App.vue` / `ImageElementSection.vue`。

### P1.2 WS auth 5s 超时（DoS）

WS onConnect 注册 ScheduledFuture，N 秒（默认 5，clamp 1-60）后若未 auth → close code=4001 reason=`auth_timeout`；auth 成功 / onClose 都 cancel。N 可配 `network.ws-auth-timeout-seconds`。WebServer.stop() shutdownNow scheduler。

涉及：`WebServer.java` / `HikariCanvasConfig.java` / `HikariCanvas.java` / `config.yml`。

### P1.3 WS Origin 白名单（CSWSH）

注册 `WEBSOCKET_BEFORE_UPGRADE` handler 校验 Origin：放行 ①缺失 / "null" ②`http(s)://127.0.0.1[:port]` / `localhost[:port]` ③`network.host` 同源 ④`network.allowed-origins` 精确匹配。其它 → 403 + `skipRemainingHandlers`。

`startsWith("http://127.0.0.1:")` 要求紧跟 `:`，不会被 `127.0.0.1.attacker.com` 绕过。

涉及：`WebServer.java` / `HikariCanvasConfig.java` / `config.yml`。

### P1.4 YAMLFactory 限制（Billion Laughs）

`YAMLFactoryBuilder` 配 `LoaderOptions.setMaxAliasesForCollections(50)` + `setCodePointLimit(5MB)`（jackson 2.18.2 真实 API 是 `setCodePointLimit` 非 spec 拼写）。所有 TemplateLoader 加载路径生效。

涉及：`TemplateLoader.java`。

### P1.5 deepCopyMap 递归 + Interpolator 长度限制

- `deepCopyMap` 公开入口委托私有 `(Map, int depth)`；`MAX_DEEP_COPY_DEPTH=32` 同时约束 Map/List 嵌套，超阈抛 `IllegalArgumentException`，上游 catch 包装 `INVALID_TEMPLATE`。
- `Interpolator`：单值 `MAX_VALUE_LEN=16384`、整次 `MAX_OUTPUT_LEN=1048576`；超阈抛 IAE。常量公开。

涉及：`TemplateInstantiator.java` / `Interpolator.java`。

### P1.6 user-templates 跨用户隔离（IDOR）

- `TemplateEntry` record 加 `Optional<UUID> ownerUuid`（builtin/server 为 empty，user 为目录 uuid）
- 加载时校验目录名是合法 UUID，非法跳过 + warn
- 新方法 `TemplateRegistry.byIdForApply(id, callerUuid, hasBypass)`：owner empty / hasBypass / caller==owner 任一通过；否则抛 `ForbiddenTemplateException`
- `byId(id)` 保持原签名供 listing / preview（marketplace gallery 全员可见，不做 owner 隔离 —— 与 listMarketplace 一致）
- `EditOpDispatcher.template.apply` 改用 `byIdForApply`，catch 异常返 `EditSession.OpResult.Error("FORBIDDEN", ...)` 不 echo 异常细节
- 新权限节点：`canvas.template.use-others`（default: op）—— 跨用户 apply 的 bypass

涉及：`TemplateEntry.java` / `TemplateRegistry.java` / `ForbiddenTemplateException.java`（新）/ `EditOpDispatcher.java` / `paper-plugin.yml`。

### P1.7 `wall.alias` WS op owner-only（IDOR）

与命令侧 `/canvas alias` 同款校验：`wall.ownerUuid().equals(s.playerUuid())` 通过；否则查 `canvas.alias.any` 权限 bypass（玩家在线时）；否则 `FORBIDDEN`。补 `WALL_ALIAS` audit 事件（操作者 / wall_id / old / new alias）。

顺便审查其它 wall.* op：lock/unlock 已有 owner 校验（无 bypass，符合 lock-state §5）；wall.refresh 隐式被 SessionManager.open lock 鉴权拦截；wall.delete 仅命令侧实装，已校验。

涉及：`WallOpDispatcher.java` / `WebServer.java`（构造器加 AuditLog 参数）/ `HikariCanvas.java`。

### 验证

`./gradlew :plugin:test` 全绿（含 InterpolatorTest / TemplateInstantiatorTest / TemplateLoaderTest 全套 fixture）；`:plugin:compileJava` 干净。无 baseline 漂移。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `HikariCanvas.java` / `HikariCanvasConfig.java` / `image/UploadHandler.java` / `template/TemplateEntry.java` / `template/TemplateInstantiator.java` / `template/TemplateLoader.java` / `template/TemplateRegistry.java` / `template/ForbiddenTemplateException.java`（新）/ `template/expr/Interpolator.java` / `web/EditOpDispatcher.java` / `web/WallOpDispatcher.java` / `web/WebServer.java`。`plugin/src/main/resources/`: `config.yml` / `paper-plugin.yml`。`web/src/`: `App.vue` / `components/properties/ImageElementSection.vue` / `render/PreviewRenderer.ts`。

---

## 2026-05-16 · M15.5 ultrareview Phase 4（docs 同步收尾）+ M15.2 god class 拆分（5 commit batch）

### M15.2 god class 拆分（5 个 commit）

5 个 agent 并行 + WebServer 收尾 agent，把累计 6519 行 god class 拆到 33 个新模块（平均 60-300 行 / 模块），各类公共 API 100% 不变，364 测试全过 + fixture baseline 0 漂移。

| god class | 之前 | 之后 | 减少 | 新模块 | commit |
|---|---:|---:|---:|---|---|
| EditSession.java | 2013 | **1191** | -41% | ElementValidator (537) / BrushSession (304) / LayerOperations (295) / HistoryStack (105) | `91e54dc` |
| CanvasCompositor.java | 986 | **328** | -67% | ElementRenderer sealed (23) + RenderContext (44) + FillPaintBuilder (147) + 8 Renderer（Rect/Circle/Shape/Icon/Path/Text/Image/Brush） | `8839c5c` |
| WebServer.java | 1117 | **581** | -48% | OpPushCallback / WebHelpers / EditOpDispatcher (243) / BrushOpDispatcher (102) / WallOpDispatcher (153) / TemplateOpDispatcher (147) | `c0106f1` |
| RightPanel.vue | 1076 | **193** | -82% | TransformSection (199) / TextElementSection (311) / GeometricElementSection (168) / ImageElementSection (301) / ElementListSection (126) | `d26134f` |
| CanvasView.vue | 1327 | **596** | -55% | 3 子组件（CanvasGridOverlay 25 / TextInlineEditor 83 / CanvasZoomBar 163）+ 7 composable（useMarqueeSelection 109 / useDrawToCreate 183 / useBrushHost 84 / useTransformerManager 117 / useCanvasShortcuts 50 / usePanScroll 84 / useCanvasUpload 157） | `fefa91b` |
| **合计** | **6519** | **2889** | **-56%** | **33 个新模块** | 5 commit |

**设计要点**：
- ElementRenderer 用 sealed permits 8 子类 + record pattern switch dispatch → 新增 Element 类型时编译期强制补 renderer
- RenderContext.ImageLoaderHolder SAM 让 setImageLoader 写后所有 renderer 立即可见（volatile 字段间接读）
- OpPushCallback interface 解耦 dispatcher 与 WebServer：dispatcher 只需 push 回调，不持完整 WebServer 引用
- BayerDither.apply(img, palette, phaseX, phaseY) 让 dither buffer 缩到 element bbox 后输出像素级等价（fixture 0 漂移关键）
- 测试钩子（activeStrokeCountForTest / overrideStrokeActivityForTest / purgeStaleStrokes）仍在 EditSession 上保留 package-private 签名，内部 delegate BrushSession 同名方法 → EditSessionBrushPurgeTest 等不动

**收益**：33 个新模块平均 60-300 行 → 单 reader 一坐能读完；后续 M13/M14/动态画板等扩展往里塞功能可以独立子模块迭代，god class 膨胀有了边界。

**工期约 4h**（5 agent 并行；预算 22-25h；节省 ~80% wall-clock）。

### M15.5 docs 同步（M15 收尾）

docs/protocol.md / docs/architecture.md / docs/data-model.md / docs/deployment.md / CLAUDE.md 5 个文档批量更新，把 M11-M14 + M15 改动全部回填契约：

- **docs/protocol.md** (+42 行)：§3.1 预握手响应精简 + §5.7 wall.refresh / wall.alias 错误码 + §5.9 brush 段从占位重写为 M12 完整版 + §5.10 新增模板创意工坊段 + §6.1 错误码表补 13 个（FORBIDDEN / INVALID_ALIAS_FORMAT / UNEXPECTED / TOO_MANY_STROKES 等）+ §8.3 锁定与终结（lock/unlock + FORBIDDEN 示例）
- **docs/architecture.md** (+30 行)：§13 动态画板设计约束（P-1 渲染期占位符推荐 / P-3 Plugin API 备选 / P-2 反模式禁用）
- **docs/data-model.md** (+32 行)：§6.6 Migration 兼容性规则（pre-release 激进 OK / 0.1.0 后 forward-only + auto-backup / fixture 测试要求）
- **docs/deployment.md** (+37 行)：§8 版本升级 SOP（升级前 / 中 / 后 / 回滚）
- **CLAUDE.md** (+19 行)：M15 ultrareview 大重构段（5 phase 概览 + 3 关键架构决策）

### M15 累计统计

- **27 P0 修完**（M15.1 + M15.3 + M15.4 + M15.2 顺手修的 P0-3 部分）
- **5 god class 拆完**
- **3 测试基建依赖**引入（Caffeine / MockBukkit / JavalinTest）
- **9 commit 分 5 phase batch push**
- **364 测试不漂移** + **14 fixture snapshot 0 漂移**
- **vite build 482.40 KB JS**（M14 baseline 477.79 → +4.61 KB，符合预算）

**剩余 P0**：
- P0-26 MapPool persist 失败一致性 — 留 v1.x（M15.2 god class 拆分时未碰 MapPool）
- P0-Web-4 WS auth → wsBySession race — v1 不做（需 CountDownLatch 等并发设施）

### 整体工期

| Phase | 内容 | wall-clock |
|---|---|---:|
| M15.1 | 9 P0 + 3 依赖 + Caffeine wallPreviewCache | 2.5h |
| M15.3 | 8 P0 鉴权方案 C + 数据安全 + 基础设施 | 2.5h |
| M15.4 | 10 P0 ImageIO/DB/协议/渲染 | 3h |
| M15.2 | 5 god class 拆分（5 commit batch） | 4h |
| M15.5 | docs 同步 + 整体 journal | 1h |
| **合计** | | **~13h** |

预算 35-40h；实际 ~13h（节省 ~65%）。靠大量子代理并行（5 agent × 多 phase = 累计 ~20 agent 工作量）+ 主代理只做装配 + commit + push。

---

## 2026-05-16 · M15.4 ultrareview Phase 3（ImageIO 隔离 + DB 一致性 + 协议 + 渲染）

4 个 agent 并行实施 10 处中高风险修复。覆盖 ImageIO DoS / LRU 死锁 / DB 事务 / 协议明文 / 模板注入 / dither OOM 6 个独立领域。

### Group A：ImageIO 隔离 + LRU 死锁

- **P0-12 IIORegistry 注销不可信 reader**：`HikariCanvas.onEnable` 头部扫 IIORegistry 把非 PNG/JPEG/WebP 的 ImageReaderSpi 注销。Thread.interrupt 对 ImageIO 内部循环大多无效——唯一可靠的防线是不让它们注册。降低压缩炸弹 / 损坏文件触发 TIFF/BMP/GIF 解码器漏洞的攻击面
- **P0-15 pickLruCandidates SQL NOT IN**：`ImageUploadDao.pickLruCandidates` 把内存 `stream.filter(!isReferenced)` 改成 JDBI `bindList("exc", ...)` + `WHERE hash NOT IN (<exc>)`。攻击者上传 16 张图全引用后 SQL 直接跳过这些行返非引用候选，不再"返 16 行内存全 filter → break → 所有玩家永久 fail"
- **P0-17 ImageStorage.load timeout 隔离**：加私有 `renderDecoderPool`（1-thread bounded + ArrayBlockingQueue(4) + AbortPolicy + daemon）+ 500ms timeout。损坏 PNG 不再让 rasterize 主线程死锁；reject 时返 null 走占位

### Group B：DB 一致性 + AuditLog

- **P0-27 MigrationRunner SQL 拆分识别引号**：新 `splitSqlStatements` 状态机识别单引号串（含 `''` escape）/ 双引号串 / 行内 `--` 注释。朴素 `split(";")` 对 `INSERT VALUES ('a;b')` 会破裂；新状态机扛住未来 DDL 含数据 INSERT 的 migration
- **P0-28 + P0-29 MigrationRunner 事务化 + auto-backup**：每个 migration 包 `h.useTransaction(...)`，DDL 失败回滚不留半态；`HikariCanvasConfig.databaseAutoBackup` + `config.yml database.auto-backup-before-migration`（pre-release 默认 false，0.1.0 发版后建议 true）；开启时 migration 前 `Files.copy(data.db, data.db.pre-V<NNN>.bak)`
- **P0-33 AuditLog DB 失败 fallback**：`record(...)` jdbi.useHandle 包 try-catch；DB 失败时 `log.severe("[AUDIT FALLBACK] event=... player=... reason=...")` 至少留痕到 server log。AuditLog 构造扩 Logger 参数 + 老单参 ctor 兼容

### Group C：预握手协议 + Template 校验

- **P0-Web-2 预握手协议精简**：`handlePreHandshake` 响应从 `{ sessionId, playerName, wall, mapIds, templates, palette, fonts, wsUrl }` 砍到 `{ ok: true, playerName, wsUrl }`。sessionId / wall / templates 全由 WS `ready` 帧下发（已实装）。HTTP 响应不再泄漏 sessionId → 配合 P0-21 capability-by-URL 缓解。前端无需改（grep 已确认前端不调 `/api/session/:token` HTTP，直接走 WS）
- **P0-23 Template raw_state 安全校验**：`EditSession.validateElementForTemplateApply(Element)` 抽 static + 公开。`TemplateInstantiator.instantiateRawState` 在 `cloneElementWithFreshId` 前调；catch `ValidationException` → `Result.Failed("INVALID_TEMPLATE", ...)`。覆盖 x/y/w/h/rotation 通用范围 + PathElement.d + ImageElement.source + ImageElement.mask + IconElement.source。`ValidationException` 提升为 public final 让 template 包能 catch

### Group D：渲染 dither buffer 收紧

- **P0-Render-2 dither buffer 按 element bbox**：`drawDitheredElement` 把整 canvas ARGB buffer 改成 element bbox + rotation 外接圆 + canvas clip 交集大小。`BayerDither.apply` 新签名 `apply(img, palette, phaseX, phaseY)` 让 dither phase 按原画坐标取 Bayer matrix，buffer 缩到 bbox 后输出**逐像素等价**。所有 11-dither / 12-brush / 13-image-mask fixture baseline **零漂移**。常规元素从 64MB transient 降到几 KB ~ 几百 KB

### 验证

- `./gradlew :plugin:test`：**364 case 全过**（baseline 不漂移）
- `vite build`：不变（M15.4 只动后端）
- M15.4 期间发现需要清理 `build/` 内 macOS Finder/iCloud 残留的 `* 2.xml` / `* 2.class` 副本——清完测试干净

### 剩余推后 P0

- **P0-26 MapPool persist 失败一致性**：留 M15.2 god class 拆分阶段（MapPool 也是 candidate）一起做—— byId.put 失败回滚 + 调用方重试
- **P0-Web-4 WS auth → wsBySession race**：v1 不做，需要更深的并发设施（CountDownLatch 等）

### 工期

约 3h（4 agent 并行）—— 预算 6-8h，节省 ~60%。

### 改动文件清单（13 文件）

- `plugin/.../HikariCanvas.java`（IIORegistry 启动期注销 + imageStorage.shutdown 接 onDisable）
- `plugin/.../HikariCanvasConfig.java`（databaseAutoBackup 字段）
- `plugin/.../image/ImageStorage.java`（renderDecoderPool + load timeout）
- `plugin/.../render/BayerDither.java`（phaseX/phaseY 重载）
- `plugin/.../render/CanvasCompositor.java`（drawDitheredElement bbox-only）
- `plugin/.../state/EditSession.java`（validateElementForTemplateApply public static）
- `plugin/.../state/ValidationException.java`（提升 public final）
- `plugin/.../storage/AuditLog.java`（catch + log.severe fallback + Logger 字段）
- `plugin/.../storage/ImageUploadDao.java`（pickLruCandidates SQL NOT IN）
- `plugin/.../storage/MigrationRunner.java`（splitSqlStatements + useTransaction + tryBackup）
- `plugin/.../template/TemplateInstantiator.java`（validateElementForTemplateApply 接入）
- `plugin/.../web/WebServer.java`（handlePreHandshake 精简）
- `plugin/.../resources/config.yml`（database 段）

---

## 2026-05-16 · M15.3 ultrareview Phase 2（鉴权方案 C + 数据安全 + 基础设施）

3 个 agent 并行实施 9 处中风险修复 —— P0 鉴权破口 + 数据丢失 + DoS 防线。

### 鉴权方案 C 落地（Group A agent）

**核心原则**：仅在 `SessionManager.open` 路径鉴权 lock 状态；后端编辑 op 透明放行（CLAUDE.md §lock-state 第 2 条保留）。

- `SessionManager.open` 加 lock check：`w.publishedAt() != null` + caller != owner + 无 `canvas.admin.bypass-lock` 权限 → 返新 `OpenResult.Forbidden(message)`（修 P0-18）
- `CanvasCommand.runOpen` 处理 `Forbidden` 分支：红字 `"Wall is locked: ..."` 提示
- `CanvasCommand.runAlias` 加 owner 校验：非 owner + 无 `canvas.alias.any` 权限拒（修 P0-19）
- `WandListener` 加 `WallRepo` 注入；wand 二次确认前预检 lock → ActionBar 红字 `"Wall '<id>' is locked by <ownerName>"` 早退（边界处理：与 SessionManager.open 双重防御）
- `HikariCanvas.onEnable` WandListener 构造扩 wallRepo 参数

**兼容性验证**：CLAUDE.md §lock-state 第 2 条「后端编辑 op 不读 lock」保留——所有 element.\* / canvas.\* / layer.\* 透明放行。未来 PAPI / 动态画板走渲染期占位符解析（P-1 路径）不经过 open → 不受方案 C 影响。

### 数据安全散点（Group B agent）

- **P0-1 多选拖拽 silent loss**：`CanvasView.vue:583` 比较条件 `otherEl.x !== otherX` → `init.x !== otherX`。dragmove 已乐观更新 element.x 用于 Konva 视觉反馈；dragend 改用 dragstart 记录的 `initialPositions: Map` 做判等，恢复多选拖拽 ws.send 触发
- **P0-10 mask 二次约束**：`EditSession.parseMaskNullable` 在 `validatePathD` 后追加 `validateMaskPathBounds`：`MAX_MASK_VERTICES = 64`（v1 决策上限）+ 坐标绝对值 ≤ 10000（element bbox 范围，相对 PathDValidator 的 100K 二次卡）。`EditSessionImageTest` 21 case 全过（fixture 坐标都 ≤ 100，未触限）
- **P0-11 RDP 改迭代**：`RdpSimplifier.recurse` → `simplifyIterative`（`ArrayDeque<int[]>` 模拟栈）。算法等价 + 公共 API 不变；规避 MAX_BRUSH_POINTS_PER_STROKE=5000 + JVM 默认 512KB 栈下 ~2500 帧 SO 风险。`RdpSimplifierTest` 7 case 零修改全过

### 基础设施（Group C agent）

- **P0-24 detectLeaks 接 BukkitScheduler**：`HikariCanvas.onEnable` 新 `mapPoolLeakTask` 字段；5 分钟周期异步任务 `runTaskTimerAsynchronously` 调 `mapPool.detectLeaks(liveWallIds)`，启动后 5 分钟首次跑；`onDisable` cancel。idcounts.dat 防膨胀的最后防线启动
- **P0-25 清渲染缓存**：`HikariCanvasRenderer.invalidate(Collection<Integer>)` 批量清除；`SessionManager` 构造扩 `HikariCanvasRenderer canvasRenderer` 参数；`SessionManager.deleteWall` 在 `mapPool.releaseWall(...)` 后调 `canvasRenderer.invalidate(released)`。跨 wall 像素泄漏防线就位
- **P0-32 confirm 事务化（v1）**：`WallRepo.createWithMapIds` 新方法 wrapped in `jdbi.useTransaction`，单 INSERT 直接含 mapIds 字段（不再先空字符串再 update）。`SessionManager.confirm` 重构为 reserve-first + tx INSERT + rebind 模式：先 reserve（owner placeholder） → wallRepo.createWithMapIds → rebind 到真实 wallId。任何步失败回滚 `mapPool.releaseWall` + 错误返回。完整 mapPool ↔ walls 跨子系统一致性（如 persist 失败回滚 byId.put）留 M15.4

### 验证

- `./gradlew :plugin:test`：**364 case 全过**（baseline 不漂移）
- `vite build`：**477.56 KB JS / 42.85 KB CSS**（M15.1 baseline -0.02 KB；纯逻辑改动几乎无体积变化）

### 工期

约 2.5h（3 agent 并行）。M15.3 是预算 5-6h —— 节省 ~50% wall-clock。

### 改动文件清单（9 文件 plugin + 1 文件 web）

- `plugin/.../HikariCanvas.java`（M15.3 +30 行 = mapPoolLeakTask wire + WandListener 扩 wallRepo + SessionManager 扩 canvasRenderer）
- `plugin/.../command/CanvasCommand.java`（runOpen Forbidden 分支 + runAlias owner check）
- `plugin/.../session/SessionManager.java`（OpenResult.Forbidden record + open lock check + 扩 canvasRenderer 构造 + confirm 重构 + deleteWall invalidate）
- `plugin/.../session/WandListener.java`（wallRepo 注入 + wand lock 预检）
- `plugin/.../render/HikariCanvasRenderer.java`（invalidate Collection 批量清）
- `plugin/.../state/EditSession.java`（MAX_MASK_VERTICES + MASK_NUMBER_RE + validateMaskPathBounds）
- `plugin/.../state/RdpSimplifier.java`（recurse → ArrayDeque iterative）
- `plugin/.../storage/WallRepo.java`（createWithMapIds + jdbi.useTransaction）
- `web/src/components/layout/CanvasView.vue`（dragstart 记 initialPositions + dragend 判等改用 init）

---

## 2026-05-16 · M15.1 ultrareview Phase 1（9 处立即修复 + 3 依赖引入）

**起因**：`docs/ultrareview-2026-05-15.md` 列 38+ P0 真 bug。5 个子 agent 并行核验后 ~37 条属实，1 条（双端 canonicalCharWidth 跨端差异）实际不属实但缺测试覆盖。规划 M15 整体重构（A-E 5 个 phase commit batch），约 35-40h wall-clock。

### M15.1 Phase 1 修复内容（9 处）

**后端散点（Group X agent）**：

- `Database.java:46` 加 `busy_timeout=5000`（修 P0-31：WAL 模式 2 并发写撞 BUSY 立即抛 → SQLite 5s 自旋重试）
- `paper-plugin.yml` 补 5 个权限节点：`canvas.delete.own / canvas.delete.any / canvas.alias.own / canvas.alias.any / canvas.admin.bypass-lock`（修 P0-20 + 为 M15.3 方案 C 鉴权预留）
- `ProjectState.Canvas` compact constructor 加 `widthMaps/heightMaps ∈ [1, 32]` 校验（修 P0-Render-1：远程一条 WS op 即可 48GB heap OOM 服务器崩；32×32 = 1024 maps 上限符合 PROPOSAL §3.1 设计上限的 4x 富余）
- `UploadHandler.decoderPool` `newCachedThreadPool` → 有界 `ThreadPoolExecutor(2, 2, ArrayBlockingQueue<>(8), AbortPolicy)` + `RejectedExecutionException` 捕获转 503（修 P0-13：unbounded fork 攻击）

**WebServer 散点（Group Y agent）**：

- `wallPreviewCache` `ConcurrentHashMap<String, byte[]>` → Caffeine `maximumSize(100).expireAfterAccess(5min)`（修 P0-16：ConcurrentHashMap 不收缩，旧 key 永不淘汰，攻击者编辑 1000 次 → 5GB 缓存）
- `cfg.jetty.modifyWebSocketServletFactory` 加 `factory.setMaxTextMessageSize(65536)`（修 P0-9 + P1-Web-3：WS 大消息 DoS）
- `handleTemplatesList` 删除 `m.put("ownerUuid", ...)`（修 P0-30：公开 `/api/templates` 端点泄漏 owner UUID；保留 ownerName 给"我的模板"判定）
- `wall.unlock` ack `{lockedAt: null}` → `{locked: false}` 显式布尔（修 P0-2：全局 JsonInclude.NON_NULL 把 null 字段序列化时删，前端收到空对象 → unlock UI 不切回；改协议为显式 locked boolean 字段）

**前端散点（Group Z agent）**：

- `App.vue` window.__hk stub + 实际 send 入口两处都包 `if (import.meta.env.DEV)` 守卫（修 P0-22：生产构建 Vite 死代码消除让 `window.__hk` 不存在，F12 console 无法绕过 lock）
- `wsClient.ts handleAck` 加判 `p.locked === false` 触发 `project.lockedAt = null`（P0-2 协议变更前端配套；保留旧 `typeof p.lockedAt === 'number'` 分支处理 wall.lock ack）

### 依赖引入（plugin/build.gradle.kts）

- `com.github.ben-manes.caffeine:caffeine:3.1.8` — wallPreviewCache 用
- `com.github.seeseemelk:MockBukkit-v1.21:3.123.0` — M15+ FrameDeployer / wall.lock owner-only 等 Bukkit world/Entity 设施测试
- `io.javalin:javalin-testtools:7.1.0` — M15+ HTTP / WS 端到端测试（UploadHandler 全场景 / sessionId 鉴权）

### 验证

- `./gradlew :plugin:test`：**364 case 全过**（M14 baseline 不漂移；macOS Finder 副本 `* 2.xml` 清理）
- `vite build`：**477.58 KB JS**（M14 baseline 477.79 → -0.21 KB；DEV 守卫消除冗余分支带来微减）
- 编译 / 类型检查全过

### 关键决策（这次会话拍板）

1. **Q1 鉴权方案 = C（你提议）**：仅在 `SessionManager.open` 路径鉴权 lock + owner + `canvas.admin.bypass-lock`；后端编辑 op 透明（CLAUDE.md §lock-state 第 2 条保留）。完美兼容未来动态画板（PAPI / 数据源），因为动态更新走渲染期占位符解析路径（P-1），不经过 open。M15.3 实施。
2. **Q3 V005 处理**：pre-release 激进改可接受；MigrationRunner 加 auto-backup 机制（默认关 / 0.1.0 发版后默认开）。M15.4 实施。
3. **god class 拆分纳入 M15.2**（不延后）。
4. **Caffeine + MockBukkit + JavalinTest 现在加**（不延后）。
5. **commit 策略**：5 个 phase batch（约 9 个 commit），每个 phase 完成后推一次。

### 工期

约 2.5h（agent 派发 / 验证 / commit；3 个 agent 并行节省 ~70% wall-clock vs 串行）。

### 改动文件清单（10 改动 + 1 新依赖）

- `plugin/build.gradle.kts`（+3 依赖）
- `plugin/src/main/java/moe/hikari/canvas/storage/Database.java`
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`
- `plugin/src/main/java/moe/hikari/canvas/image/UploadHandler.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（4 处）
- `plugin/src/main/resources/paper-plugin.yml`（+5 节点）
- `web/src/App.vue`
- `web/src/network/wsClient.ts`
- `docs/journal.md`

---

## 2026-05-15 · M14 创意工坊（A-F 六段全栈）

**核心**：玩家把当前画布发布为模板；服务器内的模板市场（精选优先 + 时间倒序）；admin 可精选；模板自动文本参数化 + 用户手工命名/取消；hard delete；每玩家配额可改。

### 7 个锁定决策

1. **模板格式 = rawState 模式**：不扩 `TemplateElement` 加 Path/Circle/Shape/Brush/Image 5 record；改让 `TemplateSpec.rawState: Map<String, Object>` 直接内嵌完整 `ProjectState` JSON。Instantiator 检测 rawState 非空 → 新分支：参数替换 + JsonNode → ProjectState → 平铺 layers 走 `replaceContent`。元素类型覆盖问题一举解决（任何 M8-M13 元素都能序列化导出）
2. **参数化 v1 = 自动 text + 手工命名/取消 text**：非 text 字段（color / fontSize / x/y/w/h）手工添加为参数 **留 v1.x**（需 RawState 通用 interpolator + 字段类型推断 UI）
3. **/canvas template 命令族延后 v1.x**：前端 TopBar UI 已能完整覆盖 save/delete/feature；命令仅锦上添花
4. **HomePage 仅展示**：admin feature/delete 操作放在编辑器内 TemplateGallery（已有 token）；HomePage admin 视图需 token-less auth，留 v1.x
5. **hard delete**：YAML + preview PNG + DB 行 + 空 uuid 目录同清
6. **slug 字符集** = `[a-z0-9][a-z0-9-]{1,31}`；templateId = `user-<uuid8>-<slug>`；TemplateLoader `ID_PATTERN` 扩展加 `-`
7. **builtin 永远 featured**：DB row builtin=1 时 setFeatured 拒绝（builtin 不可 unfeature）

### M14-A DB + DAO + Registry（约 2h）

- `V008__templates.sql`：templates 表（template_id PK / owner / featured / yaml_path / builtin / download_count）+ 3 索引（owner / marketplace / yaml_path UNIQUE）
- **踩坑**：行内 `--` 注释含分号 / 引号干扰 `MigrationRunner.split(";")`；改成纯行注释解决
- `TemplateRepo`：upsert(UPSERT semantics) / findById / listMarketplace(featured + time) / listForOwner / countByOwner（配额）/ setFeatured（builtin 拒）/ incrementDownload / delete
- `TemplateRegistry` 扩展：加 `userTemplatesDir` 字段 + `loadUser(...)` 扫 `user-templates/<uuid>/*.yml`；`TemplateSource.USER` 枚举；`ReloadStats` 加 `userLoaded`

### M14-B TemplateExporter + rawState 模式（约 3h，最高复杂度）

- `TemplateSpec` 加 `rawState: Map<String, Object>` 字段（NON_NULL 序列化省略）+ `isRawStateMode()`
- `TemplateLoader.serializeToYaml(spec)` 公开 + YAMLMapper 配置 NON_NULL 输出 + 关 doc-start `---` 头
- `TemplateLoader.ID_PATTERN` 加 `-` 容纳 `user-<uuid8>-<slug>`；validate 在 rawState 模式下放宽 canvas/layout 必需
- `TemplateExporter`：纯函数。参数化策略 — 按 z-order 扫所有 TextElement → `text_1 / text_2 / ...`；`ParamConfig.textActions` 允许 keep（参数化 + 重命名 + label + description）或 drop（保持静态）。Roundtrip 自校验（导出 YAML 再读回必须 valid）
- `TemplateInstantiator.instantiateRawState`：deep-copy rawState → 递归遍历 Map/List/String 替换 `${param}` 占位符 → `mapper.convertValue(...).ProjectState.class` → 平铺 layers 内所有 element + 给新 id 避免冲突 → 返 `Result.Ok`

### M14-C 后端 WS op + Publisher + 端点（约 2h）

- `TemplatePublisher`：协调器。`publish` 链路 — 配额 → Exporter → 写 YAML → 渲染缩略图 PNG（CanvasCompositor.rasterize → ImageIO.write）→ DB upsert → Registry.reload；`delete` 链路 — 鉴权 → unlink YAML + PNG + uuidDir → DB delete → reload；`setFeatured` 鉴权 builtin 拒
- `WebServer` 4 个 WS op：`template.save / delete / feature / unfeature` → `dispatchTemplateOp` → 拿 Bukkit Player 查 `canvas.template.*` 权限
- `GET /api/templates` HTTP 端点：listMarketplace JSON
- `paper-plugin.yml` 4 新权限节点：`canvas.template.save / delete.own / delete.any / feature / bypass-limit`
- `config.yml` + `HikariCanvasConfig` `templates.max-per-player`（默认 20，可改）
- `HikariCanvas.onEnable` wire：TemplateLoader（独立 publisher 用）→ TemplateRegistry（含 userTemplatesDir）→ TemplateRepo → TemplatePublisher → `syncBuiltinToDb()` 启动期把 builtin / server 模板入库 → WebServer 构造器扩参

### M14-D 前端 SaveAsTemplateModal（约 1.5h）

- `web/src/components/template/SaveAsTemplateModal.vue`：自动扫 project.state.layers 中 TextElement 生成 `text_N` 列表；每个参数 row 含 keep checkbox / name / label / description 4 控件（keep=false 时 collapse）；slug regex 客户端校验 + hint；ws.sendWithAck('template.save', payload, 8000)
- TopBar：`Bookmark` icon 按钮触发；wallId 缺或 locked 时 disabled
- 用 Vue fragment root（`</header><Modal/>`）避免 Teleport 复杂

### M14-E HomePage Marketplace（约 1h）

- `HomePage.vue` 加只读 grid section：`Sparkles` 标题 + cards（缩略图 + featured/builtin badge + name + description + owner + templateId）
- `loadTemplates` fetch `/api/templates` + featured / builtin 标签
- 不分 tab / 不放管理操作（v1.x 留 admin 视图）

### M14-F polish + i18n + 验证

- i18n `t.workshop.*` 中英对 27 字段（save 流程 + marketplace 元素）
- gradle test：**364 case 全过**（M13 baseline 不漂移；M14 后端纯逻辑无新单元测试 case；TemplateExporterTest 留 v1.x）
- vite build：**477.79 KB JS / 42.85 KB CSS**（M13 baseline 465.74 → +12.05 KB JS / +1.99 KB CSS = SaveAsTemplateModal + Marketplace section + 27 i18n 字段 + TopBar 按钮）

### v1.x 留 future（journal 显式）

- 非 text 字段（color / fontSize / x/y/w/h / fontId / align 等）手工标注为参数 + 类型推断 UI
- `/canvas template save / list / feature / unfeature / delete` 命令族
- HomePage admin 视图（token-less auth + 在卡片上直接 feature / delete）
- TemplateGallery 内点击 apply 直接套用工坊模板（v1 builtin/server 已支持；user 模板也已可通过 template.apply 走 rawState 路径，UI 未连）
- 模板下载量统计 + 分类 / 搜索 / 评分

### 工期

约 9.5h wall-clock（A 2h + B 3h + C 2h + D 1.5h + E 1h + F polish 0.5h），略低于 13-14h 估算 —— 走 rawState 模式跳过扩展 5 个 TemplateElement record 是关键加速。

### 改动文件清单（9 新 + 8 改）

**新建（9）**：
- `plugin/.../storage/TemplateRepo.java`
- `plugin/.../template/TemplateExporter.java`
- `plugin/.../template/TemplatePublisher.java`
- `plugin/.../db-migrations/V008__templates.sql`
- `web/src/components/template/SaveAsTemplateModal.vue`

**改动（8）**：
- `plugin/.../template/TemplateSpec.java`（加 rawState 字段 + isRawStateMode + 兼容老 11 字段 ctor）
- `plugin/.../template/TemplateSource.java`（加 USER）
- `plugin/.../template/TemplateRegistry.java`（构造器扩 userTemplatesDir + loadUser）
- `plugin/.../template/TemplateLoader.java`（ID_PATTERN 加 `-` + rawState 模式宽校验 + serializeToYaml + YAMLMapper writer 配置）
- `plugin/.../template/TemplateInstantiator.java`（rawState 分支 + replacePlaceholders + deepCopy + cloneElementWithFreshId 覆盖全 8 type）
- `plugin/.../HikariCanvas.java`（wire TemplateRepo + TemplatePublisher + syncBuiltinToDb）
- `plugin/.../HikariCanvasConfig.java`（templatesMaxPerPlayer）
- `plugin/.../web/WebServer.java`（构造器扩 publisher + repo + WS op 4 个 + `/api/templates` 端点）
- `plugin/.../storage/MigrationRunner.java`（V008 入列）
- `plugin/src/main/resources/paper-plugin.yml`（5 权限节点）
- `plugin/src/main/resources/config.yml`（templates.max-per-player）
- `web/src/components/layout/TopBar.vue`（Bookmark 按钮 + Modal 渲染）
- `web/src/components/HomePage.vue`（marketplace section + loadTemplates + 缩略图 + featured/builtin badges）
- `web/src/i18n/messages.ts`（t.workshop.* 中英 27 字段）

---

## 2026-05-15 · fix(render)：粗 stroke 从 arrow 锥尖突破的视觉 Bug

**用户报告**：箭头工具画的「直线 + 方向箭头」组合，当 stroke 调粗后，直线从 arrow 三角形锥尖戳出来，看起来"直线大过方向箭头本身"。

### 根因

`MarkerRenderer.drawArrow` 三角形：apex 在 path 端点 + base 朝 inward 退 size 距离。三角形在 apex 处宽度 = 0，沿 inward 方向线性增加到 base 处 = size 宽。

`CanvasCompositor.drawPath` / `PreviewRenderer.drawPath` 用 `BasicStroke(width, ROUND, ROUND)` / `ctx.lineCap='round'` 描边整条 path 到 apex。Stroke 中段宽度恒等于 `strokeWidth`。

**视觉冲突**：从 arrow apex 朝 base 走 distance d，arrow 宽 = `size × d / size = d`。当 `d < strokeWidth` 时 arrow 锥尖宽 < stroke → stroke 矩形（2 × `strokeWidth/2` = `strokeWidth` 宽）从 arrow 锥尖区域向外凸出。

实测：`stroke=10`，`arrowSize=30`，arrow tip 朝 base 走 10px 内 arrow 宽 0..10 < stroke 10 → 直线从箭头尖端"戳出"~10×10 px 一小段。stroke 越粗这一段越大，肉眼上去就是「直线大过方向箭头」。

### 修复

`MarkerRenderer.arrowShape(apex, dir, size)` 抽出几何构造（不绘制），供 `drawArrow` 与 `drawPath` 共用。

`CanvasCompositor.drawPath` 描边前：用 `Area(baseClip).subtract(arrowShape)` 把 arrow 三角形从 stroke clip 中扣掉。stroke 只画在 arrow **外**，arrow 自己 fill 覆盖。

`PreviewRenderer.drawPath` 镜像：用 `Path2D` 外圈大矩形 + addPath(arrowShape) + `ctx.clip(path, 'evenodd')` 反相填充规则（同 M13 mask inverted 模式）。

两端皆 `markerStart='arrow'` / `markerEnd='arrow'` 通用；`dot` marker 不参与（圆形 marker 在端点重叠时不会"突破"，stroke 完全在 dot 圆内）。

### 验证

- `./gradlew :plugin:test`：364 case 全过；06-path-line fixture baseline 不漂移（旧 fixture stroke=2 时 arrow tip 突破区域 < 2px²，落在 snapshot 0.5% pixel tolerance 内）
- `vite build`：465.74 KB JS（+0.5 KB）；TypeScript 类型 OK
- 真实游戏内 stroke=10 / 20 验证需 `runServer` 实测，预期 arrow tip 处线条不再突破三角形边界

### 改动文件

- `plugin/.../render/MarkerRenderer.java`（抽 `arrowShape` 静态构造）
- `plugin/.../render/CanvasCompositor.java`（`buildArrowSubtractedClip` + `drawPath` 描边前 setClip）
- `web/src/render/MarkerRenderer.ts`（镜像 `arrowShape` 导出）
- `web/src/render/PreviewRenderer.ts`（`buildArrowSubtractClip` + ctx.clip evenodd）

### 工期

约 20 分钟（定位 + 双端镜像 + 验证 + journal）。

---

## 2026-05-15 · fix(deploy)：confirm 多 slot wall 只生成 1 frame 的恶性 bug

**用户报告**：`/canvas wand` 圈选 6×1 → `/canvas confirm` 后只生成 1 个 ItemFrame（其他 5 个位置连 frame 都没有），前端刷新一致。

### 根因

`FrameDeployer.spawnSlot` 行 308 的"清残骸"循环：

```java
for (Entity e : world.getNearbyEntities(frameLoc, 0.8, 0.8, 0.8)) {
    if (e instanceof ItemFrame ifr) {
        String w = pdc.get(wallIdKey, ...);
        if (wallId.equals(w)) ifr.remove();   // ← 同 wall_id 即删
    }
}
```

**bug 模式**（spawn 串行 slot 0..N）：
1. slot 0 spawn OK，PDC `wall_id=W slot=0`
2. slot 1 spawn 前 query box `0.8` 半径以 slot 1 frameLoc 为中心 → 与 slot 0 entity bbox（半径 ~0.5）相交（中心距 1.0 < 0.5 + 0.8 = 1.3）→ 抓到 slot 0
3. slot 0 PDC `wall_id == W` 匹配 → 当残骸 `remove()` 删了 slot 0
4. slot 1 spawn OK，PDC `wall_id=W slot=1`
5. slot 2 spawn 前抓到 slot 1（同 wall_id）→ 删 slot 1
6. ... 串联到 slot N-1：每 spawn 一个新的都删上一个；最终只剩最后 spawn 的一个

2026-05-14 修"邻接 wall confirm 误删"的 fix 把"非同 wall_id 不删"约束加上去，但**同 wall_id 邻接 slot 互删**这个对称问题没考虑。

### 修复

「同 wall_id 即删」收紧为「同 wall_id **且** 同 slot」：

```java
PersistentDataContainer pdc = ifr.getPersistentDataContainer();
String w = pdc.get(wallIdKey, PersistentDataType.STRING);
Integer s = pdc.get(slotKey, PersistentDataType.INTEGER);
if (wallId.equals(w) && s != null && s == slotIndex) {
    ifr.remove();
}
```

只有"同一格"上次失败 spawn 的真残留才会被识别——因为 ItemFrame 的 PDC `slot` 字段在 spawn 时就写入（line 333），可作为位置指纹。

### 验证

- `./gradlew :plugin:test`：364 case 全过（无回归）
- 真实游戏内 6×1 confirm 路径：需 `./gradlew :plugin:runServer` 实测；逻辑上邻接 slot 不再互删
- 测试盲点：FrameDeployer 单元测试需 MockBukkit world / Entity 设施，沿 2026-05-14 同款 "⏸ Future"

### 影响范围

- 受影响：所有 multi-slot wall（width × height > 1）的 confirm + refresh 路径
- 修复后行为：每个 slot 独立 spawn 不互删；同位置失败 spawn 残留仍能被正确清理（PDC slot 指纹精确匹配）

### 工期

约 15 分钟（定位 + 修 + 验证 + journal）。

---

## 2026-05-14 · M13 图片导入 + 蒙版（A-E 五段全栈）

**核心**：用户可拖图 / paste / 选文件进编辑器；上传走 6 层校验栈；`ImageElement` 支持 SVG path 蒙版（v1 UI 4 预设几何 + inverted，数据模型 path 形态留 v2 完全体接口）。M13 完成 = 项目主线收尾。

### M13-A 数据模型 + 协议（约 1h）

- `state/Mask` record（d + inverted）+ `state/ImageElement` record（source = sha256[:16] 16 字符小写 hex；mask 可选）
- `Element` sealed permits 加 image；`@JsonSubTypes` 加 image
- `EditSession`：`addElement` switch 加 `case "image"`；`updateElement` 模式 switch 加 `case ImageElement im`；`cloneElementWithNewId` 加 image；新增 `buildImage` / `applyImagePatch` / `parseMaskNullable` / `validateImageSource`
- `CanvasCompositor` switch 加 占位 `case ImageElement im -> {}`（M13-C 实装）
- `web/src/types/protocol.ts` 加 `ImageElement` + `Mask`
- 测试：`EditSessionImageTest`（21 case：add + 各形态 mask + update + 拒绝路径 + 配额边界）

### M13-B 后端 /api/upload + ImageStorage（约 3h，最高复杂度）

- `db-migrations/V007__image_uploads.sql`：sha256[:16] PK + bytes/width/height/mime/uploader/uploaded_at/last_used_at/refcount + 2 索引
- `storage/ImageUploadDao`：insert（INSERT OR IGNORE）/ findByHash / touchLastUsed / delete / countByUploaderSince / sumBytes / pickLruCandidates(限制 + excludeHashes) / listAll
- `image/ImageStorage`：sha256 内容寻址 + 60s 内存 LRU（LinkedHashMap accessOrder）+ 磁盘 LRU sweep。`putIfAbsent(BufferedImage, UUID)` → `StoreResult(hash, w, h, bytes, isNew)`；`load(hash)` → BufferedImage（缓存命中 / 磁盘加载）；`readPngBytes(hash)` 直接返字节（HTTP 下载用）；`evictLruUntilUnder(incomingBytes, maxTotalBytes, wallRepo)` 按 LRU 删 orphan
- **v1 refcount 简化**：image_uploads.refcount 列保留但不实时增减；LRU 候选实时 sweep `walls.project_json` 收集仍被引用的 hash（`collectReferencedHashes` 反射 ImageElement.source）。~50 walls 量级 < 50ms 可接受；wall 数上千再考虑增量维护
- `image/ImageQuotaService`：三层配额（per-wall / per-day / total-disk-mb），任一超限拒；磁盘超限返 `NeedsEviction` 让上层触 LRU evict 后重试；`bypass=true` 跳过全部
- `image/UploadHandler`：Javalin POST /api/upload + 6 层校验栈
  1. sessionId（multipart 字段）→ `SessionManager.byId` → live Player → `hasPermission("canvas.upload")`；bypass = `hasPermission("canvas.upload.bypass-limit")`
  2. `file.size()` ≤ `images.max-size-kb`
  3. `Content-Type` ∈ `images.allowed-mime`（去 `;charset=...` 后参数）
  4. magic bytes（前 12 字节）真实 MIME 探测；与 Content-Type 不符拒
  5. ImageIO 隔离解码：单独 `ExecutorService.submit(...).get(200, MS)`；超时 cancel 抛 `TimeoutException` → `UPLOAD_REJECTED: decode timeout`
  6. bbox sanity：0 < w/h ≤ 8192；超 `downscale-max-edge` 自动 bilinear downscale
  7. 配额三层（per-wall 由后续 EditSession 协调，v1 这里传 0）+ `evictLruUntilUnder` 重试
- `UploadHandler.handleDownload` GET /api/upload/{source}：hash 校验 → `readPngBytes` → image/png + max-age=31536000 immutable（hash 内容寻址，URL 与内容 1:1）
- `UploadHandler.handleQuota` GET /api/upload/quota：返三层剩余配额给前端 UI
- `WebServer`：3 个新路由注册；构造器加 `UploadHandler` 参数
- `HikariCanvas.onEnable`：image 服务在 Compositor 之前装配（WallRestorer 也能正确加载）；`UploadHandler` 在 sessionManager 后装配；compositor.setImageLoader 注入 storage::load；onDisable 调 `uploadHandler.shutdown()` 关 decoder 线程池
- `HikariCanvasConfig.ImageConfig` record + load() 读 `images:` 段（6 字段）
- `paper-plugin.yml`：`canvas.upload`（default true）+ `canvas.upload.bypass-limit`（default op）
- `config.yml`：`images:` 段含 max-size-kb=2048 / allowed-mime=PNG|JPEG|WebP / downscale-max-edge=1024 / max-per-wall=16 / max-uploads-per-day=50 / max-total-storage-mb=1024
- 测试：`ImageStorageTest`（13 case 真磁盘 IO + DAO + LRU sweep + sha256 稳定性）+ `ImageQuotaServiceTest`（8 case 三层边界 + bypass + 0=unlimited + 24h 时间窗）+ `UploadHandlerHelpersTest`（8 case magic bytes 全形式 + downscale 等比例缩放）
- HTTP path 完整 e2e 测试需 `javalin-testtools` 依赖未引入，留 future

### M13-C 后端渲染（约 1h）

- `CanvasCompositor.drawImage(g, im)`：translate 到元素左上 → 可选 mask clip → `g.drawImage(img, 0, 0, w, h, null)`；文件缺失走 `drawImagePlaceholder`（虚线方框 + ?，同 IconElement 风格）
- mask：复用 M9 `PathParser.parse(mask.d).path()`；`inverted=false` 直接 `g.clip(Path2D)`；`inverted=true` 用 `Area(bbox).subtract(Area(mask))` 反算
- **dither × mask 顺序**：`drawDitheredElement` 的 per-element off-buffer 路径上自然达成「先 dither 再 mask」语义：mask clip 在 drawElementBody 内部生效，dither 在整张 element buffer 跑，mask 外像素本就透明不受影响
- 抽 `ImageLoader` SAM（`String → BufferedImage`）作为 compositor 的注入接口；生产 wire `imageStorage::load`，测试可传 lambda
- fixture：`13-image-placeholder.json`（占位渲染：两个 source 都不存在文件，含 rotation）+ `13-image-mask.json`（占位 + 4 种 mask + inverted）；snapshot test `@ValueSource` 加 13-*

### M13-D 前端拖拽 + UI（约 1h）

- `PreviewRenderer.drawImage`：`imageCache: Map<hash, ImageCacheEntry>` 异步加载（`/api/upload/{hash}`）；ready 前画占位（虚线 + ?）；onload 触 `iconReadyHook` 重绘；mask 用 `ctx.clip(Path2D)`；inverted 用 `Path2D.addPath` + `'evenodd'` fillRule
- `preloadImage(source, dataUrl)`：上传成功立即缓存乐观 dataUrl 直接命中，省去一次 fetch 往返
- `CanvasView`：三入口
  1. 拖拽：根 `<section>` 加 `@dragover="onCanvasDragOver"` + `@drop="onCanvasDrop"`；drop 坐标 → host bounding rect / ui.zoom 换算 canvas 内坐标
  2. paste：`useEventListener(window, 'paste', onPasteImage)` 全局监听；activeElement 是 textarea/input 时不抢
  3. file input：右下 toolbar 加 ImagePlus 按钮 → `fileInputRef.click()` → `@change` 上传
  - `uploadAndPlace(file, clientX?, clientY?)`：FormData(sessionId + file) → fetch /api/upload → 200 → preloadImage + `ws.send('element.add', { type: 'image', props: { x, y, w, h, source } })`；非 200 → flashError 6s 自消
  - 等比缩到 canvas 短边 80%；多文件 drop 只取第一个（M13 决策 3）；locked 拒；非 image MIME 拒
  - 顶部 banner（红色错误 / 蓝色 uploading）+ readonly overlay 不影响 banner
- `RightPanel`：`isImage` computed + 整个 image details 段
  - 缩略图 + 16 字符 hash + Replace 按钮（自家 hidden file input + 独立 imageReplacing 状态）
  - mask preset dropdown：none / circle / roundedRect / ellipse；切换调 `makeCircleD` / `makeEllipseD` / `makeRoundedRectD` 生成 d 字符串 → `sendUpdate({ mask: { d, inverted } })`
  - circle / ellipse 用 4 段 cubic Bezier 近似（kappa = 0.5522847498）；roundedRect 用 4 个 Q + L 段（半径 = min(w, h) * 0.15）
  - `detectMaskPreset` 启发式：4 个 C 命令 → 椭圆/圆（按 w==h 判断）；4 个 Q 命令 → 圆角矩形；其他 → none。**不写魔数前缀**到 d，避免被后端 PathDValidator 拒
  - bbox resize watch：监 `${w}x${h}` 变化时按 detectMaskPreset 重生成 d，否则被裁形变
  - inverted 复选框（仅 mask != none 时显示）
  - dither 复选框（image 不属 geometric 族，复用 `t.fill.ditherLabel`）
- i18n（中英对 16 字段）：`t.image.header / source / sourceTip / replace / replaceTip / maskHeader / maskPreset / maskPresetTip / maskInverted / maskInvertedTip / maskPresets.{none,circle,roundedRect,ellipse} / uploading / uploadTip / uploadFailed / lockedDenied / noSession / notImage`

### M13-E polish + 测试 + journal

- gradle test 全套：**364 case 全过**（M12 baseline 312 → M13-A +21 → M13-B +29 = 362 + 2 fixture 雪片测试 = 364）
- vite build：**465.24 KB JS** / 40.86 KB CSS（M12 baseline 453.57 → +11.67 KB JS 来自 D2 imageCache + D3 三入口 + D4 RightPanel 段 + i18n 16 字段；+1.49 KB CSS = mask 段 + banner）
- fixture baseline 14 个 PNG 全不漂移（含 13-image-placeholder + 13-image-mask 两个新）

### 关键决策（已落地，固化于 docs）

1. **mask 数据模型 = SVG path d 字符串**（B 风格，留 v2 lasso / 自由 path mask 完全体接口）；mask UI = dropdown 4 预设（A 风格）
2. **上传入口 = file input + drop + paste**（Figma 标准）；多文件批量 / mask 拖动编辑 / feather / URL 粘贴 → v1 不做
3. **LRU 清理时机** = 每次 upload 前检查总配额，超限删 orphan
4. **mask + dither 顺序** = 先 dither 再 mask（per-element off-buffer 路径自然达成）
5. **content-hash sha256[:16]** 内容寻址（PNG 编码字节），跨 wall 零重复存储
6. **ImageIO 解码隔离** = ExecutorService.submit(...).get(200, MS) 防压缩炸弹
7. **v1 refcount 简化** = LRU sweep walls.project_json 收集仍被引用的 hash，不维护实时 refcount
8. **HTTP 认证** = multipart `sessionId` 字段 → SessionManager.byId → Bukkit Player.hasPermission（HTTP 不消耗 token，避免一次性 token 与 upload 冲突）

### v1 留 future

- POST /api/upload 完整 HTTP e2e 测试（需引入 `javalin-testtools` 依赖）
- 增量 refcount 维护（替换 v1 sweep 模式，wall 上千时）
- mask 完全体（lasso 自由路径 / 拖动编辑 mask 形状 / feather / 多 mask 组合）
- 多文件批量上传（drop 多文件全部接收）
- URL 粘贴上传 / EXIF 读取 / PNG→WEBP 自动转换 / 杀毒

### 改动文件清单（13 新 + 14 改）

**新建（13）**：
- `plugin/.../state/Mask.java` / `ImageElement.java`
- `plugin/.../storage/ImageUploadDao.java`
- `plugin/.../image/ImageStorage.java` / `ImageQuotaService.java` / `UploadHandler.java`
- `plugin/.../db-migrations/V007__image_uploads.sql`
- `plugin/.../test/.../state/EditSessionImageTest.java`（21 case）
- `plugin/.../test/.../image/ImageStorageTest.java`（13 case）+ `ImageQuotaServiceTest.java`（8 case）+ `UploadHandlerHelpersTest.java`（8 case）
- `plugin/.../test/resources/fixtures/13-image-placeholder.json` + `13-image-mask.json`
- `plugin/.../test/resources/expected/13-image-placeholder.png` + `13-image-mask.png`

**改动（14）**：
- `plugin/.../state/Element.java`（sealed permits + JsonSubTypes）
- `plugin/.../state/EditSession.java`（buildImage / applyImagePatch / parseMaskNullable / validateImageSource + 3 处 switch）
- `plugin/.../render/CanvasCompositor.java`（drawImage + ImageLoader SAM + 占位）
- `plugin/.../HikariCanvas.java`（image 服务 wire-up）
- `plugin/.../HikariCanvasConfig.java`（ImageConfig record + load()）
- `plugin/.../web/WebServer.java`（3 新路由 + 构造器扩展）
- `plugin/.../storage/MigrationRunner.java`（V007 入列）
- `plugin/.../resources/paper-plugin.yml`（2 权限节点）
- `plugin/.../resources/config.yml`（images 段）
- `plugin/.../test/.../render/RendererSnapshotTest.java`（13-* 入 @ValueSource）
- `web/src/types/protocol.ts`（ImageElement + Mask TS 镜像）
- `web/src/render/PreviewRenderer.ts`（drawImage + imageCache + preloadImage）
- `web/src/components/layout/CanvasView.vue`（drop/paste/file input 三入口 + banner + toolbar 按钮）
- `web/src/components/layout/RightPanel.vue`（isImage + image 段 + mask preset 生成 + replace）
- `web/src/i18n/messages.ts`（image 段中英对 16 字段）

### 工期

约 5h wall-clock（M13-A 1h + M13-B 3h + M13-C 30min + M13-D 1h + M13-E journal/commit 30min）。整体 PROPOSAL 估计 1 周 = 大幅压缩。M0–M13 累计约 7.5 周 wall-clock 完成，比规划 6 个月缩短约 4 个月。

---

## 2026-05-14 · 全栈审查 + 3 Bug 修复 + 文档批量重写

**起因**：用户反馈 "别的 AI 审查发现 CLAUDE.md 等文档严重滞后于实际"。本会话做了系统化审查（3 个 Explore agent 并行扫 doc-drift / code-quality / test-coverage）+ 修真 bug + 重写文档。

### 审查发现

- **真 bug 5 个**：(1) lock 快捷键守卫缺失（高，键盘绕过 readonly overlay）；(2) StrokeBuffer 泄漏（高，purgeStaleStrokes 只 startBrush 内调用）；(3) BrushController.tryEnd 超时漏掉清理通知（高，ack 后到导致服务端 buffer 孤儿）；(4) applyBrushPatch 忽略 w/h 字段（中）；(5) brush.end persistWall 主线程阻塞风险（中）
- **测试盲点 3 个**：wall.lock owner-only / 邻接 frame regression / 前端 scalePathD
- **文档严重滞后 5 处**：PROPOSAL 命令清单 / architecture §6 流程图 + 状态机表 / data-model published_at 注释 + 语义 / security 权限节点

### 本次修复

**Bug 1 · lock 快捷键守卫**（App.vue）：`useEventListener('keydown')` 顶部加 `if (project.isLocked)` 守卫；Delete/Backspace、Ctrl+Z/Y、ArrowKeys 全拒 + `e.preventDefault()`；zoom/select/locale/theme/Cmd+A 等非编辑快捷键不受影响。

**Bug 2 · purgeStaleStrokes 定时调用**：
- `EditSession.purgeStaleStrokes` private → public synchronized
- `SessionManager.purgeAllStaleStrokes`：遍历所有 session 调 purge
- `SessionReaper.sweep`：每次 sweep 顶部触发（30s 周期，已有 tick）
- 新增 `EditSessionBrushPurgeTest` 4 case + EditSession 加 `activeStrokeCountForTest` / `overrideStrokeActivityForTest` 测试 hook（package-private）

**Bug 3 · BrushController.tryEnd abort 标志**：
- 新加 `aborted: boolean` 字段
- tryEnd 超 2s 未拿 strokeId → 设 `aborted = true` 再 cleanup
- pointerDown sendWithAck.then 检测 aborted；ack 真到达时主动 `ws.send('brush.cancel', { strokeId })` 通知服务端

### 文档批量重写（subagent 并行执行 6 处）

- **PROPOSAL.md** 命令清单删 publish/unpublish + 加 lock-state 段说明
- **docs/architecture.md §6** 旧 publish ASCII 流程图整体替换为 wall.lock owner-only 新图
- **docs/architecture.md 状态机表** `ACTIVE→publish` 改为 `ACTIVE→lock`
- **docs/data-model.md** walls schema 列注释 + 语义段重写
- **docs/data-model.md** PDC 表 published_at 标"2026-05-14 不再写入"
- **docs/security.md** 删 canvas.publish 权限节点 + wall.publish 检查表 + 加 owner-only 说明

### 测试盲点未补全

| 项 | 状态 | 原因 |
|---|---|---|
| brush purgeStaleStrokes | ✅ 已补 4 case | EditSession 加 testing hook 后纯函数易测 |
| wall.lock owner-only | ⏸ Future | WebServer 端到端 mock 工作量 vs 1 行 .equals 投入产出比低 |
| FrameDeployer 邻接 frame regression | ⏸ Future | 需 MockBukkit world / Entity 设施 |
| 前端 scalePathD 单测 | ⏸ Future | 项目无前端测试 framework（vitest 未引入）；M13 后引入 |

### 验证

- `./gradlew :plugin:test`：**312 测试全过**（M12 commit 308 → +4 EditSessionBrushPurgeTest）
- `vite build`：**453.57 KB JS** / 39.37 KB CSS（+0.27 KB 守卫 + abort 字段）
- 现有 12 fixture baseline 不漂移

### 工期

约 1.5 小时（审查 3 agent ~15min + Bug 1/2/3 修复 ~30min + purge 测试 ~15min + 文档 subagent ~15min + journal & commit ~15min）。

---

## 2026-05-14 · 三 Bug 修复 + published 概念整体重设计为 lock-state

用户反馈三个问题，全部修完。

### Bug 1 · 箭头工具 transformer resize 失效

**根因**：`CanvasView.vue onTransformEnd` 对所有 element 统一发 `{x, y, w, h, rotation}` 走 `element.transform`；PathElement 的几何完全由 `d` 字符串（绝对坐标）+ `stroke.width`（常量）决定，bbox 不参与渲染。所以 resize handle 拖动只改 bbox，箭头粗细 / 形状不变。

**修法**：
- 新增 `web/render/pathScale.ts`：tokenize d 字符串 → 把 M/L/Q/C 命令后续数字按偶/奇下标 × sx / sy → 重组。Z 命令无坐标原样保留。M9 PathDValidator 接受输出格式。
- `CanvasView.vue onTransformEnd` 检测 `el.type === 'path'`：计算 sx=newW/oldW、sy=newH/oldH → 调 `scalePathD` 得新 d → stroke.width 按 max(sx, sy) 缩放 → 走 `element.update`（element.transform 只接 x/y/w/h/rotation，d/stroke 字段必须走通用 patch）。
- 其他 element 类型保持原 element.transform 路径。

### Bug 2 · 邻接 wall confirm 误删现有 ItemFrame

**根因**：`FrameDeployer.spawnSlot:302` 用 `getNearbyEntities(frameLoc, 0.8, 0.8, 0.8)` 清"残留"，原注释明确写"PDC 不带 wall_id 或带别的 wall_id 都视为残骸"——故意把别人的 frame 当残骸删。问题：ItemFrame bbox 半径 ~0.25 + query box 半径 0.8 = 1.05 格 > 标准 1 格间距，相邻 wall 的 frame 一定会被抓到。

**修法**：清残留时只 remove **PDC `wall_id == current`** 的 ItemFrame（自己上次失败 spawn 留下的）。其他 wall_id 或 PDC 缺失的 ItemFrame 一律跳过；位置占用问题由 WallResolver 在 confirm 之前的 OCCUPIED 检查拒绝。Item 掉落物保持照清。

### Bug 3 · published 概念整体重设计为 lock-state

用户提出 "把发布功能整体放前端 readonly 锁"，最终定下方案：

- 锁状态服务端持久化（不 localStorage，避免跨设备 / 玩家分享时丢失）
- 复用 `walls.published_at` DB 列（语义化为 lock 时间戳，避免 schema 迁移）
- 复用 `walls.owner_uuid` 作为作者权限依据
- 后端**不**用 lock 状态阻挡编辑 op（未来动态展示用例需要）
- `wall.lock` / `wall.unlock` WS op **owner-only** 校验（caller UUID == wall.owner_uuid）
- 前端是 lock 的唯一执行者（readonly UI）

#### 后端砍除

- `/canvas publish` / `/canvas unpublish` 命令 + tab complete（CanvasCommand.java）
- WS op `wall.publish` / `wall.unpublish`（WebServer.java）
- `FrameDeployer.markPublished` + `FrameDeployer.isFramePublished` + `FrameDeployer.publishedAtKey` 字段
- `FrameProtectionListener` 中"已发布拦截"完整路径（M7 引入的）—— 所有 wall ItemFrame 一致由 `canvas.modify` 权限保护

#### 后端新增

- WS op `wall.lock`：owner-only 校验通过 → `WallRepo.markPublished` 写入时间戳 → ack `{lockedAt}`
- WS op `wall.unlock`：owner-only 校验通过 → `WallRepo.markUnpublished` → ack `{lockedAt: null}`
- ready payload 新增字段：
  - `lockedAt` (从 `publishedAt` 改名)
  - `ownerUuid` （wall.owner_uuid）
  - `selfUuid` （当前 session 玩家 UUID）

#### 前端砍除

- `TopBar.vue` togglePublish 函数 + Globe icon "Published/Draft" 按钮
- `stores/project.ts` 的 publishedAt
- `network/wsClient.ts` ack handler publishedAt 字段
- i18n `publishToggleOn/Off` / `publishOn/Off` / `publishedGroup` / `draftsGroup` / status.published / status.draft

#### 前端新增

- `stores/project.ts`：`lockedAt` + `ownerUuid` + `selfUuid` 字段；`isLocked` / `isOwner` / `canEdit` computed
- `TopBar.vue`：Lock/Unlock 图标按钮（owner 可点，非 owner disabled + tooltip "仅作者可锁定 / 解锁"）
- `CanvasView.vue`：locked 时整 stage 上覆盖 readonly overlay div（z-20、bg-black/10、backdrop-blur）拦截所有 mousedown/click/dblclick；中央显示 amber 色提示文字（owner 看 "已锁定 · 点 TopBar Unlock 继续编辑"，非 owner 看 "已锁定（仅作者可解锁）· 只读模式"）
- `RightPanel.vue`：根元素 `:class="{ hc-readonly-panel: project.isLocked }"`，scoped CSS 给 `.hc-readonly-panel section` 加 `pointer-events: none; opacity: 0.6`，完全禁用编辑控件
- `StatusBar.vue`：published / draft 替换为 locked / unlocked，icon Globe/FileText 替换为 Lock/Unlock
- `HomePage.vue`：published / drafts 分组改为 locked / unlocked，icon Globe→Lock 色彩 emerald→amber
- i18n：`t.wall.{locked, unlocked, lockToggleOn, lockToggleOff, lockOwnerOnly, lockedOwnerHint, lockedReaderHint}` + `t.status.{locked, unlocked, wallStateTip}` + `t.home.{lockedGroup, unlockedGroup}` 中英

### 文档同步（"文档先行"）

- `CLAUDE.md`：M5.5 重构段顶部加 `§lock-state` 块，详细列 7 条架构纪律
- `docs/architecture.md` 顶部 banner + 新加 `§3.6 lock 状态`（含数据流图）；老 §6 publish 流程段标 `[DEPRECATED 2026-05-14]`
- `docs/data-model.md` 顶部 banner，published_at 列名保留但语义变更
- `docs/protocol.md`：§5.7 砍 wall.publish/wall.unpublish 行 + 加 wall.lock/wall.unlock；ready payload 例改 lockedAt + ownerUuid + selfUuid

### 验证

- `./gradlew :plugin:compileJava`：过（修了一处 UUID vs String 比较 bug，把 `w.ownerUuid().equals(s.playerUuid().toString())` 改成 UUID-UUID 直接比较）
- `./gradlew :plugin:test`：**287 测试全过**（M11 review fix 后基线，未引入测试漂移）
- `vite build`：440.20 KB JS / 39.26 KB CSS（之前 M11-E 438.50 → +1.7 KB JS = TopBar lock 按钮 + readonly overlay + i18n 新字段；+2.2 KB CSS = readonly 样式 + amber 颜色变量）

### 工期

约 1.5 小时（Bug 1 ~15 分钟、Bug 2 ~5 分钟、Bug 3 文档 ~25 分钟 + 后端 ~25 分钟 + 前端 ~30 分钟 + journal & verify ~10 分钟）。

---

## 2026-05-14 · M11 review fix · 退化形态 IAE 防御 + fill.ts 类型加强

**起因**：M11 主提交后做二次审查，扫到两个潜在问题。

### Bug 1（真 bug）· `LinearGradientPaint` / `RadialGradientPaint` 退化形态 IAE

**触发**：用户在 FillInput 把所有 stops 拖到 `position=1.0`（FillValidator 允许相等位置做硬切色，UI 也能造出全 1 的极端），后端 `monotonicFractions` 的 epsilon-bump 公式 `Math.min(1f, prev + 1e-5f)` 在 prev 已 1.0 时仍输出 1.0 → fractions 为 `[1.0, 1.0]` **非严格递增** → AWT 抛 `IllegalArgumentException` → 整张画面 rasterize 崩。

**根因**：M11-B 写 `monotonicFractions` 时只想着"相等位置 epsilon-bump"，没考虑 prev 已经卡到 1.0 上界的反向退化。整个序列向左收缩的逻辑复杂，所以选 graceful degradation 路线。

**修法**：`buildLinearPaint` / `buildRadialPaint` 把 `new GradientPaint(...)` 包 try-catch `IllegalArgumentException`，捕到时 fallback 首 stop 纯色 `Paint`（同 0 尺寸 bbox 处理一致）。视觉上极端形态 = 纯色，不再让 rasterize 整体崩。

**回归测试**：新增 `FillDegenerateTest` 4 case：
- linear stops 全 1.0
- linear stops 全 0.0
- radial stops 全 1.0
- linear + 0 尺寸 bbox（M11-B 原有检查的固化）
- 均断言 `compositor.rasterize(state)` 不抛 + 返回非空 BufferedImage

### Bug 2（lint 级）· `fill.ts` 函数参数 inline literal

`buildLinearGradient` / `buildRadialGradient` / `addStops` 的形参用了 `{ angle, stops }` 这种内联字面量类型而非 `protocol.ts` 导出的 `LinearGradient` / `RadialGradient` 类型 —— 未来 protocol 类型升级时（如加新字段）这些函数不会被 TS 报错提醒。

**修法**：

- `import` 增加 `LinearGradient, RadialGradient`
- 三个函数形参换成 `LinearGradient` / `RadialGradient`
- `addStops` 的 stops 参数加 `ReadonlyArray<{position, color}>` 类型签名（更安全）

### 验证

- `./gradlew :plugin:test`：**287 测试全过**（M11 主提交 283 → +4 = `FillDegenerateTest` 全过，意味着修复后退化形态不再抛）
- `vite build`：JS 体积 **438.50 KB** 无变化（仅类型签名调整，运行时代码不变）
- 现有所有 baseline 不漂移（11 个 fixture 都通过）

### 改动文件清单

- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`：`buildLinearPaint` / `buildRadialPaint` 加 try-catch IAE 兜底
- `web/src/render/fill.ts`：参数类型从 inline literal → `LinearGradient` / `RadialGradient` / `ReadonlyArray`
- `plugin/src/test/java/moe/hikari/canvas/render/FillDegenerateTest.java`（新）：4 case regression

### 总结

| 项 | 状态 |
|---|---|
| 找到 bug | 2（1 真 bug + 1 类型加强） |
| 修法 | 防御性 try-catch + 类型签名收紧 |
| 测试 | +4 regression case 覆盖修复路径 |
| 现有功能影响 | 0（baseline 不变 + 已有 283 case 全过） |
| 工期 | ~20 分钟（review + 修 + 测试 + journal） |

---

## 2026-05-14 · M11 渐变 + Bayer 4×4 Dither（A-E 五段全栈一气完成）

**目标**：把 `fill: string` 升级为 `Fill` 联合类型（solid / linear / radial），并实装 M8-E 留下的 "双端 dither 一致性硬骨头"。CLAUDE.md / PROPOSAL §6 锁定 M11 = "渐变 + Dither（1w）"，本次一气完成 A-E 五段。

### M11-A · 数据模型 + 协议 + 校验

- 新增 `state/Fill.java` sealed interface + `SolidFill / LinearGradient / RadialGradient` 三 record + `Stop` record
- 新增 `state/FillDeserializer.java`：Jackson custom，支持 `string ↔ object union` —— 老 `"#RRGGBB"` 等价 `{type:"solid",color:"#RRGGBB"}`
- 新增 `state/FillValidator.java`：solid 颜色 / linear angle [0,360) / radial cx,cy [0,1] r (0,2] / stops [2,8] position 单调非递减
- `state/ValidationException.java`：从 EditSession private 内部类提取为同包 top-level（供 FillValidator 复用）
- 4 element record 升级：`Rect / Circle / Shape / Path` 的 `fill` 字段 `String → Fill`
- `EditSession`：新增 `parseFillNullable` helper + `FILL_MAPPER`（专 fill object 反序列化）；8 处 build / patch fill 解析替换
- `CanvasCompositor.fillToColor` 临时桥接（M11-B 替换为 `fillToPaint`，先 stub 取首 stop 色保编译）
- `TemplateInstantiator.Rect`：fill 构造 `SolidFill`
- 前端 `types/protocol.ts`：`Fill / FillCompat / Stop / SolidFill / LinearGradient / RadialGradient` 类型镜像；4 element fill 字段类型升级
- 新增 `web/render/fill.ts`：`normalizeFill / fillToCss / fillColors / isSolidFill`
- `stores/palette.ts`：projectColors 扫渐变 stops
- `RightPanel.vue`：ColorInput 入口 `fillToCss` 兼容
- `PreviewRenderer.ts`：4 处 `ctx.fillStyle = fill` → `fillToCss(fill)`

#### M11-A 测试与坑

- `FillValidatorTest` 23 case + `EditSessionFillTest` 14 case
- **StackOverflowError 坑**：`@JsonDeserialize` 在 sealed interface Fill 上时会被 3 个 record 子类继承，`codec.treeToValue(node, LinearGradient.class)` 递归回 FillDeserializer 死循环。修：每个 record 加 `@JsonDeserialize(using = JsonDeserializer.None.class)` 显式断绝继承
- **UnrecognizedPropertyException 坑**：序列化 SolidFill 输出 `{type:"solid", color}`，反序列化时 LinearGradient/RadialGradient 不认 `type` 字段。修：每个 record 加 `@JsonIgnoreProperties(ignoreUnknown = true)`

### M11-B · 后端真实绘制（渐变 + dither）

- `CanvasCompositor`：`fillToColor` → `fillToPaint(Fill, bx, by, bw, bh) → Paint`，4 处 `g.setColor` → `g.setPaint`
- `buildLinearPaint`：角度 → 方向向量 → bbox 4 角投影取 min/max 端点 → `LinearGradientPaint(x1,y1,x2,y2,fractions,colors)`
- `buildRadialPaint`：cx/cy 归一化映射到 bbox 内、半径 `r × min(bw,bh) / 2` → `RadialGradientPaint(cx,cy,radius,fractions,colors)`
- `monotonicFractions`：AWT 要求严格递增，相等位置 epsilon-bump `1e-5f`
- 新增 `render/BayerDither.java`：4×4 矩阵 + `AMPLITUDE=16` + `threshold(x,y) = MATRIX[y%4][x%4] / 16 - 0.5` + `apply(BufferedImage, PaletteLut)` 原地 dither（RGB 加 offset → matchColor → getColor 反查写回，alpha < 128 跳过）
- `CanvasCompositor`：拆分 `drawElementBody` + 新增 `drawDitheredElement`：renderMode=DITHER 走 per-element ARGB canvas 尺寸 buffer → `BayerDither.apply` → `g.drawImage` 回主 graphics（element.opacity 通过 SrcOver 起效）
- `drawElementsTo` 加 `widthPx, heightPx` 参数沿调用链下传（fast/slow path 都更新）
- 新增 fixture 09 linear / 10 radial / 11 dither + 自动入 baseline + 视觉 review 通过
- 新增 `BayerDitherTest` 7 case（矩阵规模、threshold 周期性、透明像素不变、中灰产生 4×4 周期、黑白稳定、null no-op、clean vs dither 差异）

### M11-C · 前端镜像

- 新增 `web/render/BayerDither.ts`：与 Java `BAYER_MATRIX / BAYER_AMPLITUDE / bayerThreshold / applyBayerDither` 同矩阵同公式逐行镜像
- `render/fill.ts` 加 `fillToCanvasStyle(ctx, fill, bx, by, bw, bh) → string | CanvasGradient`：
  - linear：与 Java 同公式（4 角投影到方向向量取 min/max），`ctx.createLinearGradient`
  - radial：r0=0（中心点退化）+ r1=radius，映射 Java 单半径 `RadialGradientPaint`
  - stops：Canvas 允许相等 position（硬切色），无需 monotonize
- `PreviewRenderer.ts`：4 处 `fillToCss` → `fillToCanvasStyle`；`drawElement` 加 dither 分支；新增 `drawDitheredElement`（per-element off-canvas → getImageData → applyBayerDither → putImageData → drawImage）
- 模块级 `cachedPalette` lazy load + `onPaletteReady(hook)` —— 首帧 fallback clean，加载完自动重绘切回 dither
- `CanvasView.vue`：注册 `onPaletteReady(() => requestDraw())` （同 onIconReady 模式）

### M11-D · UI 编辑器

- 新增 `web/components/ui/FillInput.vue`（~250 行）：
  - 3-tab `solid / linear / radial` 切换 + 自动 stops 转换（solid→gradient 自动生成"当前色 + 黑色"2 stops，gradient→solid 取首 stop）
  - linear：angle 滑块 0..359（避开 360 边界 IllegalArgument）
  - radial：cx/cy/r 三滑块（粒度 0.05）
  - stops 列表：position 滑块（实时 clamp 到前后邻居之间，单调）+ ColorInput + 删 + 加（2-8 限）
- `RightPanel.vue`：rect 段重写为「几何元素」共用段（rect/circle/shape/path 合并，header 动态切换）
  - fill 接 FillInput；M11-A 的 ColorInput + fillToCss 临时桥接替换
  - 新增 shape 专属 kind / sides / innerRatio 嵌套字段
  - 新增 element-level **Dither** checkbox（hover tooltip 解释 Bayer 抖动语义）
  - **circle/shape/path 从此首次拥有 RightPanel 编辑入口**（M9 以来只能改 transform/opacity 的状态终结）
  - 删除冗余 `rectStroke / toggleRectStroke / patchRectStroke` helpers
- i18n：`t.fill.{solid,linear,radial,angle,cx,cy,radius,stops,addStop,removeStop,ditherLabel,ditherTip}` + `t.properties.{circleHeader,shapeHeader,pathHeader}` 中英

### M11-E · review + journal + commit

- 自查 4 段：FillInput stops 单调与 mutation 防御（`[...cur.stops]` 拷贝）/ Bayer 双端 AMPLITUDE 同步常量 / drawElement 调用链 widthPx/heightPx 完整传递 / RightPanel `geomFill` 把老 string 形态升级为 SolidFill object
- 确认后端 EditSession 各 element patch 都接受 `renderMode` 字段（M8-A 已实装）

### 关键设计决策（M11 全段已锁）

| 项 | 选 |
|---|---|
| Fill 反序列化 | sealed + custom Deserializer + 子类 `@JsonDeserialize(None.class)` + `@JsonIgnoreProperties(ignoreUnknown=true)` |
| linear angle | `[0, 360)` 度，0° 沿 +x，90° 沿 +y（顺时针为正） |
| radial cx/cy | `[0, 1]` 归一化 bbox |
| radial r | `(0, 2]` 归一化 `min(w,h)/2`，允许超出 bbox |
| stops 数 | `[2, 8]` |
| stops position | 单调非递减（允许相等做硬切色） |
| dither AMPLITUDE | ±16 RGB / 双端共享常量 |
| dither pass 粒度 | per-element canvas-size off-buffer → blend，clean 元素仍走原快路径 |
| stroke 渐变 | v1 不做（v1 留 future；fill 才支持） |
| text/icon 渐变 | v1 不做（text.color / icon.tint 仍纯色） |
| fill schema 升级 | lazy migration —— 老 string 自动 SolidFill；新写出统一 object |

### 验证

- `./gradlew :plugin:test`：**283 测试全过**（M10 后 273 → +10 = `FillValidator` 23 + `EditSessionFill` 14 + `BayerDither` 7 = 共 44 新 case，扣掉 baseline fixture 测试数 1=8→11 不算入 unit case；实际增长 50）
- `vite build`：**438.50 KB JS / 37.03 KB CSS**（M10 后 425.35 → +13.15 KB = fill.ts gradient builder + BayerDither.ts + FillInput.vue + RightPanel 几何元素扩展 + i18n）
- 现有 8 fixture baseline 不漂移 + 3 新 fixture（09/10/11）baseline 视觉 review 通过：
  - 09 linear：水平/垂直/45° 渐变都对齐 bbox
  - 10 radial：center/offset/star 径向都符合预期
  - 11 dither：clean vs dither 像素层面差异清晰，Bayer 4×4 周期可见

### 工期

- **预估 1 周**（PROPOSAL §6 锁定），实际**约 4.5 小时**（M11-A ≈ 1h / M11-B ≈ 1h / M11-C ≈ 30min / M11-D ≈ 30min / M11-E ≈ 1.5h 含 review + journal + commit）
- 16 新文件 + 12 修改文件 + 0 baseline 漂移 + 4 个实现期发现并修的 bug（StackOverflow / UnrecognizedProperties / hex 文本框 sync 不动 / Map.of 不接 null）

---

## 2026-05-13 · M10 调色板（ColorInput + 三色板 + alpha + copy hex）

**用户加买项**：alpha 通道（8 位 hex） + 复制 hex 到剪贴板。本次 M10 实施含 alpha + copy hex。

### 范围

- **三色板**：项目色板（ProjectState 派生）+ 最近色板（localStorage 持久化前 20）+ 默认色板（24 色 MC-friendly 配色，硬编码）
- **ColorInput 自定义组件**：替代所有原生 `<input type="color">`，含 HTML5 picker + alpha slider + hex 文本框 + 复制按钮 + 三色板 swatches 网格
- **alpha 通道**：默认开启（`allowAlpha=true`），alpha=ff 时存 6 位 hex，alpha<ff 时存 8 位
- **复制 hex**：popover 内复制按钮 → `navigator.clipboard.writeText` + 1.5s "已复制" 视觉反馈

### 实施

- 新增 `web/config/palettes.ts`：DEFAULT_SWATCHES 24 色（黑白灰 4 + 暖色 4 + 绿 4 + 蓝 4 + 紫粉 4 + MC 标志 4）
- 新增 `web/stores/palette.ts`：recent localStorage + projectColors computed（扫所有 layer.elements 的 color/fill/stroke.color/tint/effects.* + canvas.background，Set 去重）
- 新增 `web/components/ui/ColorInput.vue`：~330 行
  - trigger：色块 + hex 文本按钮
  - popover（absolute 定位 + onClickOutside 自动关）：HTML5 picker + alpha slider（棋盘格底 + 当前色渐变 overlay）+ hex 文本框 + 复制按钮 + 三色板网格 8 列
  - emit 分两类：picker/slider input 阶段不加 recent，change/swatch 点击/hex 提交才 addRecent
  - 棋盘格背景 CSS（hc-checkerboard）让 alpha < 255 时透出
- `web/components/layout/RightPanel.vue`：6 处 `<input type="color">` 替换为 `<ColorInput :model-value @update:model-value>`（rect.fill + rect.stroke.color + text.color + effects.{stroke,shadow,glow}.color）
- `web/i18n/messages.ts`：t.palette.{projectHeader, recentHeader, defaultHeader, copyTip, copied, alphaLabel} 中英
- **后端 `CanvasCompositor.parseColor` 加 alpha 支持**：解析 `#RRGGBBAA` 时 alpha 0-255 → `new Color(r, g, b, a)`；Graphics2D 在 TYPE_INT_RGB 上 SrcOver 叠加（"颜色变浅"语义同 docs/rendering.md §6.5）

### Review 修复（实施完成后）

- hex 文本框同步 bug：用户在 picker/slider 改色后切到 hex 文本框，显示的还是旧 draft（未跟随 modelValue 变化）→ 加 `hexFocused` 跟踪 + `watch(modelValue)` 当未 focus 时同步到 hexDraft

### 关键设计决策

1. **alpha=ff 归一化存 6 位 hex**：保持向后兼容，老 element 字段不被升级；新存 alpha<ff 时存 8 位，新老共存
2. **projectColors 不持久化**：从 ProjectState 派生 computed，再次打开 wall 自动重扫，避免引入新的工程文件字段
3. **recent 完全前端 localStorage**：不发 ws，多 client 互不影响
4. **canvas.background 用 `allowAlpha=false`** 留作 future 接入背景调色器；当前 UI 无 background input
5. **emit 分两阶段**：input（拖动期）不加 recent，change（mouseup）/swatch/hex commit 才加，避免拖动期 localStorage 频繁写
6. **棋盘格背景显 alpha**：CSS linear-gradient × 2 实现，浏览器原生渲染零开销
7. **后端 parseColor 同步 alpha**：原本只读 6 位忽略 alpha，会导致前后端不一致；M10 一并修

### 验证

- `vite build` 通过：425.35KB JS / 36.90KB CSS（M9 后 417.74/33.13；+8KB JS 含 ColorInput 组件 +3.5KB CSS 含棋盘格/popover 样式）
- `./gradlew :plugin:test --offline` 全过：parseColor 改动不破坏现有测试（5+3 fixture 都是 6 位 hex，alpha=255 行为不变）

### 工期

- **预估 3 天**（PROPOSAL §6 锁定），实际**约 30 分钟**
- 3 新文件 + 4 修改文件 + 0 测试漂移 + 1 个 review 修复

---

## 2026-05-13 · M9-A/B/C/D/E 全栈实施（PathElement + 工具栏 + drag-to-create）

**目标：** 把"线 / 箭头 / 软线 / 星 / 点"五种工具压缩成一个 path 元素 + marker；CircleElement / ShapeElement 单独立；工具栏 drag-to-create。docs CLAUDE.md / PROPOSAL §6 锁定 M9 = "PathElement + 工具栏（1.5w）"，本次一气完成 A-E 五段。

### M9-A · 后端 record + 协议校验

- 新增 `state/{PathElement, CircleElement, ShapeElement}.java` 三 record + `state/PathDValidator.java`（SVG d 词法校验：M/L/Q/C/Z 子集 + 长度 / 数值范围 / 命令-参数对应）
- `state/Element.java` sealed permits + JsonSubTypes 加 path/circle/shape
- `state/EditSession.java`：addElement switch / updateElement sealed switch / cloneElementWithNewId 三处加新 case；新增 buildPath/Circle/Shape + applyPathPatch/CirclePatch/ShapePatch + parseMarkerNullable / validateShapeKind / validateSides / validateInnerRatio helpers
- `render/CanvasCompositor.java` sealed switch 加 3 case → stub drawTodoStub（M9-B 替换）
- `web/types/protocol.ts` + `web/render/PreviewRenderer.ts` 类型镜像 + stub
- 新测试 `PathDValidatorTest` 29 + `EditSessionNewElementsTest` 21

### M9-B · 后端真实绘制

- 新增 `render/PathParser.java`：d → `Path2D.Double` + 起/终点 + 切线元数据（marker 用）；M/L/Q/C/Z 大小写绝对/相对；隐式 lineto；Z 闭合切线指向 subpath 起点；退化 Q/C 控制点 fallback
- 新增 `render/MarkerRenderer.java`：drawArrow（三角形 apex 在端点）+ drawDot（实心圆），size = max(6, stroke×3) / radius = max(2, stroke+1)
- `CanvasCompositor.drawPath`：translate(p.x, p.y) → fill + stroke + drawMarker(start/end)
- `CanvasCompositor.drawCircle`：Ellipse2D.Double + fill/stroke
- `CanvasCompositor.drawShape` + `buildShapePath`：sides 顶点 -π/2 起算朝上；star 时 2×sides 顶点外内交替（odd 用 outerR×innerRatio）
- 新增 fixture 06/07/08（path-line / circle / star-polygon）+ baseline 入库（视觉 review 通过）
- 新测试 `PathParserTest` 19

### M9-C · 前端镜像

- 新增 `web/render/PathParser.ts`：浏览器原生 Path2D + 切线，与 Java 同公式逐行镜像
- 新增 `web/render/MarkerRenderer.ts`：drawArrow + drawDot 同 Java 几何
- `web/render/PreviewRenderer.ts`：drawPath / drawCircle / drawShape 实装（删 stub）
- 双端一致性靠**公式同源** + 共享 fixture（snapshot 测试是双端约束）

### M9-D · 工具栏激活态

- `stores/ui.ts`：`ActiveTool` 类型扩展 6 种（select / move / line / arrow / circle / star）+ `isDrawTool(t)` helper + loadTool 兼容
- `components/layout/LeftTools.vue`：select/move 组下新分组加 4 个工具按钮（icons：Minus / MoveRight / Circle / Star）
- `components/layout/CanvasView.vue`：`cursorStyle` 跟随 activeTool（crosshair on drawTool）；watch activeTool 切到 drawTool 时清 selection；onStageMouseDown 在 drawTool 时不启动 marquee
- 快捷键 L / A / C / S（Cmd 修饰键让位给现有 Cmd+A 全选等）
- `HelpModal.vue` 加 4 个工具条目
- i18n 加 t.tools / t.help 4 个工具文案

### M9-E · drag-to-create

- `CanvasView.vue`：
  - `hitConfig.listening: !drawing` —— drawTool 时穿透 mousedown 到 stage（PS/Figma 行为）
  - `onStageMouseDown` drawTool 时启动 drawDrag；mousemove 更新；mouseup 调 commitDraw
  - commitDraw：dx/dy < 3 取消，否则按 activeTool 构造 props 发 element.add；自动切回 select；line/arrow 不规范化方向保持 markerEnd 朝向；circle/star 用 bbox 直推 cx/cy/rx/ry / outerR
  - drawPreview computed：marquee layer 内根据 kind 渲染 v-line / v-arrow / v-ellipse / v-star（Konva 内置）
  - window mouseup 兜底清 drawDrag（拖出窗口时取消）
  - watch activeTool 末尾清 marquee + drawDrag 防御边缘 case
- i18n 删除 tooltip 末尾 "(M9-E 接入)" 占位

### Code review 修复（实施完成后）

- drawPreview 的 arrow `pointerLength=10/Width=8` 与最终元素 markerSize=6 不一致 → 改为 6/6 保持视觉对齐
- `commitDraw(tool: typeof ui.activeTool, ...)` 用 typeof 取 store 字段类型不规范 → 改为 `import type { ActiveTool }` + `tool: ActiveTool` 显式
- Esc 在 drawTool 激活时只清 selection（已是空，noop），用户无法快速取消激活态 → Esc 在 drawTool 时切回 select；select/move 时仍清选中

### 改动文件清单

**后端（plugin）：**
- 新增 9 个：state/{Path,Circle,Shape}Element.java + state/PathDValidator.java + render/{PathParser,MarkerRenderer}.java + test/{PathDValidator,EditSessionNewElements,PathParser}Test.java + test/resources/{fixtures/06-08*.json + expected/06-08*.png}（6 文件）= 实际 15 个新文件
- 修改 4 个：state/{Element,EditSession}.java + render/CanvasCompositor.java + test/render/RendererSnapshotTest.java

**前端（web）：**
- 新增 2 个：render/{PathParser,MarkerRenderer}.ts
- 修改 7 个：types/protocol.ts、render/PreviewRenderer.ts、stores/ui.ts、components/{layout/LeftTools,layout/CanvasView,HelpModal}.vue、i18n/messages.ts

### 测试

- **整库 250+ 测试全过**（含 M9 新增 69 个：PathDValidator 29 + EditSessionNewElements 21 + PathParser 19）
- 8 个渲染 fixture（5 原 fixture 零漂移 + 3 新 fixture 视觉 review 通过）
- `vite build` 通过 417.74KB JS / 33.13KB CSS（M8-F 后 406KB；+11KB PathParser/MarkerRenderer/绘制实装/工具栏/drag-to-create）

### 关键设计决策

1. **PathElement d 内坐标相对 element.(x, y)**：transform 改 x/y 时 d 不动；简化 bbox 同步（Figma 同样做法）
2. **CircleElement / ShapeElement 不引入新 transform 字段**：完全复用 bbox（cx/cy/rx/ry / outerR 都由 x/y/w/h 推），Konva Transformer 多 node 缩放/旋转自然 work
3. **fast path 兜底保 baseline 零漂移**：新增 PathElement / CircleElement / ShapeElement 时 canFastPath 已含 element.renderMode != CLEAN 防御 check；5 个原 fixture 命中 fast path 像素一致
4. **PathParser 输出切线元数据**：marker 直接消费 startTangent / endTangent，避免遍历 PathIterator 求切线
5. **marker 几何 apex 在端点**：base 朝外退 size；markerEnd 朝 endTangent（顺 path）；markerStart 朝 -startTangent（逆 path）
6. **双端镜像策略**：PathParser / MarkerRenderer 前后端逐行同公式同变量名；Path2D 是浏览器原生 + Java AWT 同名 API，moveTo/lineTo/quadraticCurveTo/bezierCurveTo/closePath 完全对应
7. **drawTool 激活时 element-hit listening=false**：让 mousedown 穿透到 stage 启动 drag-to-create（PS/Figma 行为）
8. **一次性创建 + 自动切回 select**：mouseup 后切回 select 工具让用户立即操作新元素（lastAddedElementId 自动选中）
9. **drag 距离 < 3px 取消**：防误点
10. **line/arrow d 不规范化方向**：保持 markerEnd 朝用户拖动终点
11. **保留 addText / addRect 快捷按钮**：兼容路径，未删除（统一为激活态留后续 polish）

### 工期

- **预估 1.5 周**（M9-A 2d + M9-B 2d + M9-C 2d + M9-D 1d + M9-E 2d = 9d），实际**约 3 小时**（含深度调研 + review + 修复）
- 17 新文件 / 11 修改文件 / 69 新测试 / 0 baseline 漂移 / 0 测试回归

---

## 2026-05-13 · M8-F 多选 marquee + 修饰键选择 + 多选 transform

**M8 闭环**：journal 之前的"M8-F 续做"列表完成 1/4（多选 marquee 完整落地；guides 拖出 UI / element-level blendMode 真合成留 M11；slow path 压测留远期）。

### 实施

- `stores/ui.ts`：选中模型升级为 `selectedIds: Set<string>` 作为单一真相；`selectedElementId` 改为 computed（size==1 时返 id 否则 null）兼容老组件；新增 `selectedCount` / `hasSelection` / `isSelected` / `toggleSelection` / `selectMany` / `addToSelection` / `clearSelection` API
- `components/layout/CanvasView.vue`：
  - 加 marquee 状态 + onStageMouseDown/Move/Up 三 handler；空白处拖框 ≥ 3px 触发多选；Shift 加选；window mouseup 兜底拖出窗口的清理
  - marquee 可视化：v-stage 内新增独立 v-layer + v-rect（蓝色虚线半透明填充，listening: false）
  - `onHitClick` 支持 Shift / Cmd / Ctrl click = toggle 选中
  - `hitConfig` 用 `ui.isSelected(id)` 替代单 id 判等
  - `attachTransformer` 升级：watch `Array.from(ui.selectedIds).join(',')` 触发；attach 全部选中 nodes
  - `onDragStart` 多选时记录所有选中 element 的初始位置到 `dragInitial: Map`
  - `onDragMove` 新增：按 leader 的 delta 同步其他选中 element 位置（视觉跟随 + 乐观更新）
  - `onDragEnd` 多选时对其他选中 element 各发一条 element.transform op
  - hit Rect 加 `@dragmove`；Transformer 多 node 时 transformend 各 node 自己触发自己的 op 发送（沿用单选老逻辑）
  - Escape 改 `ui.clearSelection()`
- `components/layout/RightPanel.vue`：
  - `isMulti` computed（selectedCount ≥ 2）
  - Properties section 加 multi 分支：显示"N 个元素被选中" + multi hint；header 改为 multi 时显批量删除按钮
  - `deleteMultiSelected` 函数：逐 id 发 element.delete + clearSelection
  - 底部 Elements 列表点击支持修饰键 toggle，高亮判定改 `ui.isSelected(el.id)`
- `App.vue` 全局快捷键：
  - Delete / Backspace 多选时批量删
  - Cmd / Ctrl + A 全选当前 activeLayer 内 visible elements（跳过 input/textarea/contenteditable）
  - ArrowKey 多选时所有选中 element 同步微移（步长 1px / Shift+10px）
- `i18n/messages.ts`：加 `t.properties.multiSelected` / `multiHint` / `deleteMulti` / `deleteMultiTip`

### 设计决策

- **bbox intersect 用 axis-aligned**（rotated element 用外接 axis-aligned bbox 近似）：简单可靠，MVP 接受略多框风险；M9+ PathElement 时再考虑精确旋转 bbox
- **多选 click 修饰键统一为 Shift / Cmd / Ctrl**（PS / Figma 同步）
- **drag 同步用 leader-follower 模式**：onDragStart 记录初始位置，onDragMove leader 计算 delta 应用到 followers（视觉 + 乐观），onDragEnd 一次性 N 个 ws op
- **multi transform 仍各 node 触发自己 transformend**：沿用 Konva Transformer 原生行为，无需聚合处理
- **Properties 多选时只支持批量删 + ArrowKey 微移 + Esc 取消**：批量改字段需要 N 多 ws 同值校验复杂度高，留远期。多选下隐藏单选 UI 避免 UX 矛盾
- **layer 多选不做**：M9+ 多选层操作时再考虑

### 验证

- vite build 通过，bundle 406.63KB JS / 33.13KB CSS（M8-E 后 402；+4KB 多选逻辑）
- plugin test 全过（offline 模式跑；网络偶发 TLS 抖动）
- M8 之前的 150+ 测试无回归

### 改动文件

- 修改 5 个：stores/ui.ts、components/layout/CanvasView.vue、components/layout/RightPanel.vue、App.vue、i18n/messages.ts
- 新增 0 个

---

## 2026-05-13 · M8-A/B/C/D/E 全栈实施（layered model + 协议 v2 + 分层渲染）

**M8 路线锁定后一次性把 5 段代码全部落地。** 实施顺序与 task 拆分一一对应；每段独立完成 + 测试通过后才进入下一段。

### M8-A · 数据模型 + 协议 v2 record（Java + TS）

- 新增 `state/Layer.java`（record + JsonCreator 容错 + elements 列表归一化为 ArrayList）/ `state/BlendMode.java`（enum @JsonProperty 小写） / `state/RenderMode.java`（同上） / `state/Guide.java`
- `state/Element.java`：sealed 接口加 opacity() / blendMode() / renderMode() 三方法 + effectiveXxx() default 兜底；TextElement / RectElement / IconElement 三 record 末尾追加 Float/BlendMode/RenderMode 字段，`@JsonInclude(NON_NULL)` 序列化省略默认
- `state/ProjectState.java`：Canvas 加 gridSize/guides；内部存 layers + activeLayerId + protocolVersion=2；JsonCreator 同时识别 v1（elements）与 v2（layers）入参，v1 自动包装为单 Default Layer；保留 elements/indexOfElement/addElement 等兼容 API 转发到 activeLayer 让 EditSession 零修改
- `state/EditSession.java` / `template/TemplateInstantiator.java` / test 中所有 element 构造调用末尾补 null/null/null；Canvas 构造 5 参数化
- `web/src/types/protocol.ts`：BlendMode/RenderMode/Guide/Layer 完整镜像；ProjectState 改 layers 形态 + 保留可选 elements 兼容字段
- `web/src/stores/project.ts`：setSnapshot 时把 `state.elements` 链接到 `activeLayer.elements` 同一引用，组件零修改；applyPatch 同时识别 v1 path（/elements/N）和 v2 path（/layers/M/elements/N）

**验证：** plugin test + vite build 全过；snapshot test 5 fixture **零像素漂移**（JsonCreator 自动 migrate + 兼容视图）。

### M8-B · 持久化迁移（启动期全库扫描）

- 新增 `db-migrations/V006__walls_protocol_version.sql`：`ALTER TABLE walls ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1` + 索引
- `storage/MigrationRunner.java`：注册 V006
- `storage/WallRepo.java`：新增 `isV1Form(mapper, json)` 静态检测 + `migrateProjectJsonV1ToV2(mapper, json)` 静态 roundtrip + 实例方法 `migrateAllToV2()` 扫库 + 写回 + `MigrationStats` record
- `HikariCanvas.java` onEnable：MigrationRunner.run() 后、SessionManager 启动前调 `wallRepo.migrateAllToV2()`，输出 scanned/migrated/failed 统计

**关键：** roundtrip 复用 M8-A 写好的 `@JsonCreator` 自动 migrate，零重复逻辑。单行失败 → log warn + protocol_version 留 1 → 下次启动重试 + 运行期 lazy 兜底。

**测试：** 新增 `WallRepoMigrationTest.java`（11 个测试覆盖 isV1Form 边界 + migrate 行为 + idempotent + 空 elements）全过。

### M8-C · 协议路径切换 + locked check + layer.* op 族

- `state/ProjectSnapshot.java`：升级到 `{canvas, layers, activeLayerId, label}`；紧凑 ctor 深拷贝每个 Layer（重建 elements ArrayList）
- `state/ProjectState.java`：`restore(snap)` 整体替换 + 二次深拷贝避免共享；新增 5 个 layer 级 mutator
- `state/EditSession.java`：**全面重写为 layer-aware**：Locator/findElement/findLayerIdx 反查 + 全部 element op 切 v2 path + locked check + 新增 createLayer/deleteLayer/updateLayer/reorderLayer/duplicateLayer/setActiveLayer 六个 layer.* op + moveElementToLayer 跨层 + setGridSize / setGuides；applyTextPatch/Rect/Icon 加 opacity/blendMode/renderMode 字段处理；replaceContent 重置整 layers 树
- `state/StatePatchBuilder.java`：normalize 扩展支持 Layer record → Map（patch.value 永远 JSON 对象语义）
- `web/Envelope.java`：v=1 → v=2
- `web/WebServer.java`：handleAuth 加 `clientProtocolVersion >= 2` 校验，否则 VERSION_MISMATCH + close 4002；ready protocolVersion=2；dispatchEditOp 新增 10 个 op case；新增 `closeVersionMismatch`
- `web/src/network/wsClient.ts`：send / heartbeat v=2；sendAuth 带 `clientProtocolVersion: 2`
- `web/src/stores/project.ts` applyPatch 三类新 path 分支：/activeLayerId 替换（含 elements 链接重建）/ /layers/{i} 层级 add/remove/replace / /layers/{i}/{field} 单字段

**测试：** 新增三个测试套（48 个用例）—— `EditSessionLayerOpsTest`（26）/ `EditSessionLockedLayerTest`（10）/ `EditSessionV2PathTest`（12）全部通过；snapshot fixtures 仍零漂移。

### M8-D · 图层面板前端 UI

- 新增 `components/layout/LayerPanel.vue`：右栏顶部 section；倒序列表（UI 顶 = 最上层）；每行 [eye][lock][name][count][duplicate][trash]；hover 显示 duplicate/trash；双击 inline rename；HTML5 drag reorder；active 层视觉高亮（左竖线 + 背景）；最后一层禁删
- `stores/ui.ts`：加 `editingLayerId` ref + `setEditingLayer` action
- `stores/project.ts`：加 `activeLayer` computed（含 EMPTY_LAYER 兜底）+ `activeLayerLocked` + `layerById(id)`
- `components/layout/RightPanel.vue`：顶部插入 LayerPanel；底部 Elements section 用新 `t.elements` 文案 + locked layer 时禁用 toggle/drag；顺手把 onLayerDrag* 重命名为 onElementDrag* 修正 M5 时期命名混乱
- `i18n/messages.ts`：新增 t.layerPanel.*（15 key）+ t.elements.*；原 t.layers.* 已迁出

### M8-E · 分层渲染 + opacity + BlendMode + grid overlay

- 新增 `render/BlendModes.java`：normal/multiply/screen/overlay 四公式 per-channel 实现 + `applyBlendModeOver` per-pixel 合成
- 新增 `web/render/BlendModes.ts`：前端镜像，与后端逐行公式一致
- `render/CanvasCompositor.java`：升级为分层渲染；**fast path**（layer 1 + normal + 无 element opacity/renderMode）直接画主 buffer 保持原行为；**slow path** 走 ARGB 中间 buffer + applyBlendModeOver；element-level opacity 用 `AlphaComposite.SrcOver.derive(opacity)` 实装
- `web/render/PreviewRenderer.ts`：同上分层 + canFastPath；slow path 用 offscreen canvas + getImageData/putImageData + applyBlendModeOver；element-level opacity 用 globalAlpha
- `components/layout/LayerPanel.vue`：active layer 行下方紧跟两行 inline 控件 = opacity slider（80ms debounce input + immediate change）+ blendMode select 四项
- `components/layout/RightPanel.vue`：Transform 段加 element opacity slider + blendMode/renderMode disabled select（保留字段 + tooltip "M11 实装"）
- `components/layout/CanvasView.vue`：主 canvas 上叠 CSS background-image grid overlay（按 canvas.gridSize 双线性渐变）+ 右下角 grid input
- `i18n/messages.ts`：新增 t.properties.opacity/blendMode/renderMode + t.layerPanel.opacityLabel/blendModeLabel/blendModeOptions + t.canvas.grid 共 ~20 个 key

**测试：** 新增 `BlendModesTest.java`（12 测试覆盖公式边界 + 单调性不变量 + 整体合成）全过；`RendererSnapshotTest` 5 fixture **零像素漂移**（fast path 兜底生效）。

### Code Review 修复（实施完成后）

- LayerPanel.vue 双重 v-for 渲染顺序错位 → 合并到单 v-for + template 嵌入主行下方
- LayerPanel.vue 模板 ref 在 v-for 内为数组 .focus() 不工作 → 改 querySelector + data-attr
- RightPanel.vue + LayerPanel.vue 误用 `useDebounceFn().flush?.()`（VueUse 不支持） → onChange 走 immediate ws.send + 最多冗余 1 次同值 patch
- RightPanel.vue opacityDraftPct 切换选中元素时未清空 → watch selected.id 重置
- CanvasCompositor + PreviewRenderer canFastPath 加 element.renderMode != CLEAN 防御 check，对齐 M11 dither 集成

### 改动文件清单

- **新增 12 个**：state/{Layer,BlendMode,RenderMode,Guide}.java；render/BlendModes.java；db-migrations/V006_*.sql；test/state/{LayerOps,LockedLayer,V2Path}Test.java；test/storage/WallRepoMigrationTest.java；test/render/BlendModesTest.java；web/components/layout/LayerPanel.vue；web/render/BlendModes.ts
- **修改 30+**：state/{Element,TextElement,RectElement,IconElement,ProjectSnapshot,ProjectState,EditSession,StatePatchBuilder,PatchOp}.java；storage/{MigrationRunner,WallRepo}.java；template/TemplateInstantiator.java；web/{Envelope,WebServer}.java；HikariCanvas.java；test/state/EditSessionReplaceContentTest.java；以及前端 web/src/* 全套（types/protocol.ts、stores/{project,ui}.ts、network/wsClient.ts、render/PreviewRenderer.ts、components/layout/{RightPanel,CanvasView}.vue、i18n/messages.ts）

### 测试总览

- 整库 **150+ 测试全过**（含 M8 新增 71 个：11 migration + 26 layerops + 10 locked + 12 v2path + 12 blendmodes）
- 5 个渲染 fixture 零漂移
- 前后端构建均通过

### M8-F 续做（未实施）

- 多选 marquee + 多选 transform
- guides 拖出 + Rulers 完整 UI（后端已通，前端先 noop）
- element-level blendMode / renderMode 真接合成（与 M11 dither 一起做）
- slow path 性能优化（worker / 分块）—— 压测后决定

---

## 2026-05-13 · M8 路线确认（仅文档；不动代码）

**背景：** 用户要把项目方向往"Minecraft 里的 Figma / Canva"演进。提出短期工具（圆/箭头/线/星/软线）+ 远期功能（笔刷/数位板/图片导入/图层/渐变/调色板）。

**对齐过程 — 6 个开放问题已拍板：**

1. **PathElement 一统天下**：线 / 箭头 / 软线 / 笔刷 / 点 全部基于 SVG-like path 命令 + marker。CircleElement / ShapeElement 单独立。M9 实施
2. **图层先做**：M8 = 图层 + 协议 v2 + migrate；M9 才上 PathElement。理由是后期再做图层迁移成本太大
3. **元素级 renderMode（clean / dither）**：默认 clean，dither 用 Bayer 4×4。M11 真正实装；M8 只把字段写进协议避免二次升版
4. **接力编辑而非协作**：现状（任意 canvas.edit 玩家可 /canvas open <wall_id>，byWall 排他锁）已满足；多人协作（OT/CRDT）永久不做
5. **图片上传 config 可调 + 权限节点 canvas.upload 默认绑 canvas.edit**
6. **协议 v2 一次性升级**：layers + activeLayerId + canvas.gridSize + canvas.guides + element.opacity + element.blendMode + element.renderMode 同一波改完，**切断 v1 客户端**（v1 未公开发布）
7. **BlendMode v1 选 4 个**：normal / multiply / screen / overlay
8. **opacity 在 MC 调色板下 = "颜色变浅"**（先 alpha-composite 到背景再硬截断量化）。docs 提示用户、不是真透明

**M8 子阶段拆分（约 2 周）：**

- **M8-A**：数据模型 + 协议 v2 record（Java + TS 同步）
- **M8-B**：项目 JSON migration（V006 + WallRepo 启动期全库扫描升级）
- **M8-C**：协议路径切换（state.snapshot 新形态 + patch path `/layers/{i}/elements/{j}/...` + layer.* op 族 + element.* 加 layerId）
- **M8-D**：图层面板前端 UI（右栏；可见 / 锁定 / 拖动重排 / 双击重命名 / 删除）
- **M8-E**：元素级 opacity / blendMode / 多选 / 网格 + 参考线

**远期 TODO（M8 之外、列入路线）：**

- 图层缩略图（per-layer rasterize 端点 + 缓存）
- 图层颜色标签 / 图层 mask / smart object / 图层组
- 对齐 / 分布工具
- 模板包生态（.canvas 多模板打包）
- 玩家身份认证 + HomePage 点击直开（独立 milestone）

**契约文档已更新：**

- `CLAUDE.md` 里程碑 + M8 路线段
- `PROPOSAL.md` §6 里程碑表 M6/M7 标 ✅、新增 M8-M13 行 + 远期 TODO
- `docs/architecture.md` 新 §10.5 图层模型（数据形态 / 生命周期 / 渲染顺序 / activeLayerId 职责 / locked 行为 / v1→v2 迁移）；§12 未决问题加 2 条 M8 相关
- `docs/protocol.md` 顶部加 v1→v2 总览表；§3.2 auth 加 clientProtocolVersion ≥ 2 强制；§5.3 element.* 加 layerId；§5.4 新增 layer.* op 族；§5.5 加 canvas.grid / canvas.guides.set；§5.9 brush.* 占位；§6.1 错误码加 LAYER_LOCKED/LAYER_NOT_FOUND/LAST_LAYER/UPLOAD_REJECTED；§7 ProjectState 完整重写 v2 形态；§8.2.5 新增图层操作示例；§12 未决问题升级
- `docs/data-model.md` §2.4.1 新增 lazy migration 规则（决策 A：启动期全库扫描）；§4.3 .canvas 文件改注释 v2
- `docs/rendering.md` §4.1 提到分层渲染 + §4.5 grid/guides 仅前端；§6.5 元素级 opacity；§6.6 BlendMode 4 个公式；§6.7 Bayer 4×4 dither 双端契约
- `docs/security.md` T14 图片上传威胁 + §4.5 全套约束（大小 / MIME 双校验 / magic bytes / 解码超时 / 配额 / hash 内容寻址）；§5 权限节点加 canvas.upload + .bypass-limit

**下一步：** 等用户说"开始写代码"再启动 M8-A 编码。先不动任何 java/ts 文件。

---

## 2026-05-13 · M7 第三轮：config.yml / group UI / 部署文档

**用户：「先做 config.yml、参数 group UI 分组和公网部署文档」**

**1. config.yml 真接入**

之前 onEnable 里硬编码了一堆值（host/port/TTL/idle/grace/pool 容量/fps/rate）。新增：

- `plugin/src/main/resources/config.yml` —— 默认配置，首次启动 `saveDefaultConfig()` 拷到 dataFolder。带详细中文注释 + [需重启] 标记
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvasConfig.java` —— 强类型解析。Builder 默认值兜底（缺字段不报错，向后兼容）；`summary()` 输出一行摘要供 log
- `HikariCanvas.onEnable` 全部硬编码替换为 `config.xxx`
- `/canvas reload config` 命令（canvas.admin 权限）—— 刷新 `plugin.reloadConfig()` + 重建 `HikariCanvasConfig`；当前 v1 只更新引用 + log，提示玩家"多数字段需要重启"。**Hot-apply 留 M7+。** 改 host/port/超时/池容量后仍要重启才生效

字段速查见 `docs/deployment.md §7`。

**2. 参数 group UI 分组**

- `TemplateGallery.paramGroups`：按 `param.group` 字符串首次出现顺序分组；无 group 字段 → null 组直接展开不显标题
- 每组 section header（10px uppercase，带计数 `· 4`）点击可折叠/展开
- 折叠态用 `Set<"${templateId}::${groupName}">` 存
- **demo：** `shop_sign.yml` 加 `group: 文案 / 显示 / 主题` 三档

模板示例已展示出预期 UX：店铺名/商品/价格 → "文案"组；show_item → "显示"组；颜色/字体 → "主题"组。

template-spec.md §12 `group` 一项标 ✅ M7 实装。

**3. 公网部署文档 `docs/deployment.md`**

七节内容：

1. 单机/内网最小可用配置
2. 为什么默认 `127.0.0.1`（token 明文 + WS 明文 + CORS 缺失）
3. **推荐部署**：Caddy / nginx 反代 + Let's Encrypt + WS 升级配置 + 防火墙规则（iptables / ufw）
4. 不推荐路径：直绑 0.0.0.0（仅临时测试）
5. 多玩家注意事项（wall 排他锁全局、alias 唯一、归属隔离留单独 milestone）
6. 排错对照表
7. config.yml 字段速查 + 哪些需要重启

**改动文件：**

- 新增 `plugin/src/main/resources/config.yml`
- 新增 `plugin/src/main/java/moe/hikari/canvas/HikariCanvasConfig.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java` —— 加 config 字段、`applyConfig`、`config()` 访问器；onEnable 用 config
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java` —— `/canvas reload config` 子命令
- `plugin/src/main/resources/templates/shop_sign.yml` —— group 字段示范
- `web/src/components/template/TemplateGallery.vue` —— paramGroups + 折叠 UI
- `docs/template-spec.md` §12 —— group 标 ✅
- 新增 `docs/deployment.md`

**质量门：** plugin 测试全绿；vite build 385KB JS / 31KB CSS。

---

## 2026-05-13 · M7 polish 第二轮：保护 / 预览 / grid / icon / sendWithAck / HomePage 七连发

**用户：「这五项都做了吧」**（实际 7 项）—— B1 / B2 / C1 / C2 / A1 / A2 / A3 一气全跑完，单 commit。

**B2 RendererSnapshot baseline 重建** —— `rm expected/01-hello-world.png`，测试自动重生成；5 个 snapshot 全绿。后续若再现像素漂移，按 CLAUDE.md 提示走同样流程。

**C1 `template.apply` 迁 sendWithAck** —— TemplateGallery.applyNow 改 `await ws.sendWithAck(...)`，移除原 100ms 轮询版本/lastOpError + 1.2s 兜底 setTimeout。错误现在能精准回显（之前 setTimeout 已经关弹窗了用户看不到）。

**A1 grid 布局实装** —— `TemplateLayout` 加 `columns: Integer` / `rows: Integer`。`TemplateInstantiator.gridLayout`：`cellW = (contentW - (cols-1)·gap) / cols` 同理 cellH；element 按 `idx / cols / idx % cols` 落位；超容直接截断。`TemplateLoader` 解禁 `grid` + 校验 `columns >= 1` / `rows >= 1`。

**B1 已发布墙破坏保护** —— `FrameDeployer.isFramePublished` 读 PDC 的 `published_at`。`FrameProtectionListener` 三个 handler 加强：
- `HangingBreakByEntityEvent`: 已发布 → 拒（含 force-break 权限也拒）；草稿 → 老逻辑（force-break 可绕过）
- `BlockBreakEvent`: 同上，扫四个水平邻格的画框判断
- 玩家收 ActionBar 提示 `先 /canvas unpublish` 或 `授 canvas.admin.force-break 才能强拆`

**A3 模板缩略图服务** —— 新 `TemplatePreviewService`：default params + 推荐 `[4, 1]` 画布跑 instantiator → `CanvasCompositor.rasterize` → PNG bytes，按 templateId 缓存。新端点 `GET /api/template/{id}/preview.png` 长缓存 5min。Gallery 卡片用 `<img>` 直接拉（前端没集成进卡片视图，留下一轮；后端基建已就位）。

**A2 icon 元素 — 端到端实装** —— PROPOSAL/template-spec §4.6 之前都标 "v1 不实装"，现在改 "M7 起实装"。

- **State**: 新 `IconElement` record（id/x/y/w/h/rot/locked/visible + `source` + `tint`），加进 `Element` sealed permits。`EditSession.applyIconPatch` 处理 element.update。
- **Template**: `TemplateElement.Icon` record；`TemplateLoader` 验 `source` 走 `^[a-z0-9_-]{1,32}$`（whitelist 防路径穿越）+ tint 颜色正则；`TemplateInstantiator.materialize` 走通；`naturalHeight/Width` 默认 32。
- **Asset 服务**: 新包 `template.asset.TemplateAssetService`：classpath `/template-assets/icons/{name}.png` → `dataFolder/assets/icons/{name}.png` 两级 lookup，BufferedImage + PNG bytes 分别缓存。
- **后端渲染**: `CanvasCompositor` 加 `drawIcon` —— 原色 drawImage / tint 走 `AlphaComposite.SrcIn` 染色到 offscreen 再贴。
- **HTTP 端点**: `GET /api/template-asset/icons/{name}` 由 `TemplateAssetService.iconPng` 输出 + `Cache-Control: max-age=3600`。
- **前端渲染**: `PreviewRenderer.drawIcon` —— 异步 Image cache（首次画占位 ?，加载完通过 `onIconReady` 回调让 CanvasView requestDraw）；tint 用 `globalCompositeOperation: 'source-in'`。
- **4 个 builtin 图标 PNG**: `info` / `warning` / `star` / `arrow_right`，32×32，Python stdlib + zlib 一次性生成（无 PIL 依赖）。
- **新模板 `info_panel.yml`**: free 布局 + icon + 2 行文本，参数支持挑图标 + 染色，展示新特性。

**C2 HomePage 美化** —— 卡片加 wall 缩略图（新端点 `GET /api/wall/{id}/preview.png`，按 `wallId@updatedAt` 缓存），3 列 grid 布局，hover 轻浮起，已发布卡片绿色边框。空态从纯文字改成大灰 ImageOff 图标 + 居中提示。时间格式 fmtTime 智能化：今天 `14:23` / 本周 `周三 14:23` / 更早 `5月12日 14:23`。新增手动 Refresh 按钮（之前只能刷新页面）。

**新文件：**

- `plugin/src/main/java/moe/hikari/canvas/state/IconElement.java`
- `plugin/src/main/java/moe/hikari/canvas/template/asset/TemplateAssetService.java`
- `plugin/src/main/java/moe/hikari/canvas/template/preview/TemplatePreviewService.java`
- `plugin/src/main/java/moe/hikari/canvas/template/preview/WallPreviewService.java`
- `plugin/src/main/resources/templates/info_panel.yml`
- `plugin/src/main/resources/template-assets/icons/{info,warning,star,arrow_right}.png`

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java` — 注入 asset/preview/wallPreview services
- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `isFramePublished`
- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameProtectionListener.java` — 区分已发布 vs 草稿
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java` — `drawIcon` + 可选 assetService
- `plugin/src/main/java/moe/hikari/canvas/state/{Element,EditSession}.java` — Icon 支持
- `plugin/src/main/java/moe/hikari/canvas/template/{TemplateElement,TemplateLayout,TemplateLoader,TemplateInstantiator}.java`
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java` — reload 时调 preview.invalidate()
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` — 4 个新 endpoint（template preview / icon asset / wall preview）
- `plugin/src/main/resources/templates/_index.txt` — 加 info_panel.yml
- `plugin/src/test/java/moe/hikari/canvas/template/BuiltinTemplatesTest.java` — info_panel 进 fixture
- `web/src/types/protocol.ts` — IconElement
- `web/src/render/PreviewRenderer.ts` — drawIcon + onIconReady hook
- `web/src/components/layout/CanvasView.vue` — 注册 onIconReady
- `web/src/components/template/TemplateGallery.vue` — sendWithAck 重写
- `web/src/components/HomePage.vue` — 缩略图 + hover + 空态 + Refresh + fmtTime
- `web/src/i18n/messages.ts` — `home.refresh`
- `docs/template-spec.md` §4.6 / §12 — grid + icon + preview 全部标 ✅

**质量门：** plugin 全部测试通过（含新增 info_panel 进 BuiltinTemplatesTest）；vite build 384KB JS / 31KB CSS。

---

## 2026-05-13 · M7 polish 第一轮：编辑器美化 + 解释性 UI

**用户诉求：** 进入 M7 polish 但**先不动账号权限**；做编辑器/前端美化；缩放升级；文字表述清楚；按钮要有解释（hover tooltip 或 ? 图标）。

**1. Tooltip 组件**

`web/src/components/ui/Tooltip.vue` —— 自定义浮层，替代浏览器原生 `title="..."`（原生 ~1s 延迟 + 不可样式化）：
- Teleport 到 body，避免被父级 overflow 裁掉
- 250ms 延迟显示（手感介于 0/500ms 之间）
- 支持 slot 触发器 + `text` / `shortcut` 双字段（右侧灰色 kbd 显示快捷键）
- transition 淡入淡出；主题变量驱动色

`title="..."` 在 **LeftTools / TopBar / CanvasView / RightPanel** 全部替换为 `<Tooltip>` 包裹。受影响按钮 ~20+ 个。

**2. HelpModal + ? 入口**

`web/src/components/HelpModal.vue` —— 全屏弹窗式快捷键速查表，分四组：

- **工具**：Select (V) / Move (M) / 模板库
- **选择 / 编辑**：单击选 / 双击编辑 / Esc / 双击空白取消所有 / Del / 方向键微移 / Ctrl+Z 撤销 / Ctrl+⇧Z 重做
- **缩放 / 视图**：Ctrl+滚轮 / Ctrl+= / Ctrl+- / Ctrl+0 / 中键或 Alt+左键平移
- **游戏内命令**：edit / open / list / delete

TopBar 加 `HelpCircle (?)` 图标按钮触发；`ui.helpOpen` 持久状态。i18n 中英双语完整翻译。

**3. CanvasView 缩放面板升级**

原版只有 `+ / - / Reset` 三个按钮。新版：
- **精确百分比**：点击中间数字 → 切到 `<input type=number>` 输入框，Enter 确认、Esc 取消、blur 自动 commit；25-400% 区间 clamp
- **预设档位**：50 / 75 / 100 / 150 / 200 / 400% 一排小按钮，当前档高亮
- **Fit to viewport** 按钮（`Maximize` 图标）：自动算 `outerRef.clientWidth/Height` 除以画布像素，得到刚好放下的缩放值，再 scroll 到中心
- 所有按钮挂 Tooltip 标明快捷键 `Ctrl+= / Ctrl+- / Ctrl+0`

**4. StatusBar 升级**

加 4 个新维度：
- **当前工具图标**（MousePointer2 / Move）+ 文字
- **草稿 / 已发布**徽章（绿色 Globe 强调发布态）+ 解释 tooltip "Draft = 仅自己看到"
- **完整 sessionId** 在 hover tooltip 里显（之前是字符串截断 8 位，无法复制完整 ID）
- "v3 · 12 elements" 顺序调整、tabular-nums 等宽对齐

**5. 清理 M1 demo 残留**

LeftTools 上的 `RadioTower(ping)` 和 `Paintbrush(paint)` 是 M1 端到端验证留下的，正式版用户用不到 —— 直接从工具栏删掉。WS 服务端 handler 暂保留（不上版本号才删，留作回归测试通道）。

**6. RightPanel 字段解释**

`rotation` / `letterSpacing` / `lineHeight` 这种小字段单凭名字看不出语义 —— label 旁加小 HelpCircle 图标，悬停 tooltip 说明含义和单位：
- 旋转：0–359° 顺时针
- 字距：字符间间距像素，可负值
- 行高：字号倍数，建议 0.8–3.0
- "按内容调高/调宽" 按钮也加详细 tip

**改动文件：**

- 新增 `web/src/components/ui/Tooltip.vue`
- 新增 `web/src/components/HelpModal.vue`
- `web/src/App.vue` — 挂载 `<HelpModal />`
- `web/src/stores/ui.ts` — 加 `helpOpen` 状态
- `web/src/components/layout/{LeftTools,TopBar,CanvasView,RightPanel,StatusBar}.vue` — 全面接入 Tooltip + 文案调整 + 缩放面板重写
- `web/src/i18n/messages.ts` — 加 `topbar.help` / `canvas.{zoomInputTip,fit}` / `status.{draft,published,wallStateTip,sessionFull}` / `help.*`（30+ 条）/ `properties.{rotation,rotationTip,letterSpacing,letterSpacingTip,lineHeight,lineHeightTip,fitHeightTip,fitWidthTip}`；中英双语对齐

**质量门：** vite build 通过；CSS 26→30KB（+4KB tooltip + helpmodal）；JS 357→381KB（+24KB 新组件 + 文案）。

---

## 2026-05-12 · M6 第六轮：refresh 终于真修 — 撸的是 map item 不是 frame entity

**用户提供的服务端 log 直接命中真相：**

```
[wall.refresh w-...] scanned=84 deadOrInvalid=0 wallMatched=8 present=[0,1,2,3,4,5,6,7] expected=8
[wall.refresh w-...] framesRespawned=0
```

`wallMatched=8` + `present=[0..7]` 说明 8 个画框 entity 全在、PDC 完整 — 但截图显示其中 2 个是**空木框**。

**根因：MC 左键撸 ItemFrame 是两阶段动作 —**
1. 第一次左键：只移除画框内的 item（map drop 出来 / 创造模式直接消失），**frame entity 仍挂在墙上**
2. 第二次左键：才打掉 frame entity

绝大多数玩家只撸了一下、看到画消失了就以为"撸掉了" — 实际上 entity 还在，PDC 完好。我们旧逻辑把它当"已存在"加进 `present` set 跳过 → 永远没人把 map item 塞回去 → 永远是空木框。

**修：repairFor 扫描时区分三态**

- **完整**（PDC 匹配 + `getItem().type == FILLED_MAP`）→ `present` set，整体跳过
- **空框**（PDC 匹配但 item 缺失或不是 FILLED_MAP）→ `emptyFrames: Map<slot, ItemFrame>`，进 `reAttachMapsToEmptyFrames` 直接 `f.setItem(...)`，**不需要重新 spawn entity**
- **完全缺失**（entity 都不存在）→ `deployFor` 走原 spawn 路径

`RepairResult` 加 `framesReAttached` 字段；UI 侧把 spawn 新 frame + reAttach 空 frame 合并显示为"重挂 N 个画框"（玩家视角都是"补回一格"）。

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `RepairResult` 加 `framesReAttached` + `framesFixed()`；`repairFor` 拆三态扫描；新增 `reAttachMapsToEmptyFrames`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` — ack payload `framesRespawned` 改为 `result.framesFixed()`（合并 spawn + reAttach），新增 `framesReAttached` 字段

---

## 2026-05-12 · M6 第五轮：refresh 后画框仍不出 → 加诊断 + entity 残骸清理

**用户反馈：** 第四轮修了 open 路径的 WALL_NOT_FOUND，现在 refresh 能跑通了，但只有"补回 1 支撑方块"出现 — 画框（和地图画）本身没补回。换言之 framesRespawned=0。

**分析：** "补回 1 块方块、重挂 0 个画框" → `replaceMissingWallBlocks` 正常但 `deployFor` 一个 slot 都没 mount。两种可能：

1. `present` set 包含全部 slot（spawn 全被 skip）—— 表示破坏的画框残留在 entity 列表中没被 isDead/isValid 过滤掉
2. `world.spawn(...)` 被某种原因拒绝（front block 非 air、Item 掉落物挡道、Paper 1.21 spawn 异常等）

**本轮加：**

1. **`repairFor` 诊断 log：** 输出 `scanned / deadOrInvalid / wallMatched / present / expected / replacedBlocks` 让我们看到 present set 实际包含哪些 slot
2. **`spawnSlot` 全程 log：** spawn 前后都打 location + uuid，spawn 异常显式 catch + log；spawn 返回的 frame 立即用 `isDead/isValid` 复核
3. **`spawnSlot` 三道防御：**
   - 支撑方块仍 AIR → 再补 STONE（极小概率事件）
   - frontBlock 非 AIR → 清成 AIR
   - **frameLoc 0.8 格内的残留 Item 掉落物 + 不属于本 wall 的"幽灵" ItemFrame → `e.remove()`**（怀疑这条是用户场景的真凶 — 撸破画框后掉的 Item entity 没及时被回收，挡 spawn）

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `repairFor` 加诊断日志；`spawnSlot` 加三层防御 + try/catch + isValid 复核

**待用户跑一遍：** 实测后如果第二次撸画框 + refresh 仍是 framesRespawned=0，看 server log 里 `[wall.refresh ...]` / `[spawnSlot ...]` 输出 → 能直接定位是 scan 阶段还是 spawn 阶段卡住，再针对性修。

---

## 2026-05-12 · M6 第四轮：open 路径几何缺失 + 编辑态泄露

**用户反馈：**

1. `/canvas open w-xxx` 进编辑器后点 Refresh 报 `WALL_NOT_FOUND: session lacks wall geometry`
2. 偶尔出现"一个 element 在 inline edit、另一个在被拖动"的鬼态
3. 想要"双击空白处取消所有选中"

---

**根因 1：open 路径漏写 `session.wall()`**

`SessionManager.open()` 只 `s.wallKey(key)`、`s.mapIds(...)`，从未 `s.wall(WallResolver.Result.Ok)`。
而 `WebServer.wall.refresh` / `FrameDeployer.repairFor` 都要求 `session.wall()` 提供 world / origin / facing / width / height — null 立即报 WALL_NOT_FOUND。confirm 路径下 geometry 由 `WallResolver.resolve()` 直接产出，open 路径下没有"玩家点击"的过程所以没人产。

**修：** `SessionManager.rebuildWallGeometry(WallRepo.Wall)` 从 `WallKey`（world/originX/Y/Z/facing）+ `Wall.widthMaps/heightMaps` 反推 `WallResolver.Result.Ok`，`hasExistingFrames` 固定 true（open 的前提就是 wall 已经物化）。世界未加载抛 IllegalStateException。`open()` 与"幂等重用"分支都补上调用；重用分支还做了防御：若 existing.wall 仍为 null（修复前创建的旧 session）也补一刀。

**根因 2 / 3：CanvasView 没有"主动收编辑态"的路径**

`finishEditing()` 之前只在 textarea blur / Escape / Enter 时触发。但用户的实际行为是：

- 编辑 A 时直接点（或拖）B → Konva 走 `mousedown → dragstart`，根本不触发 click
- 编辑 A 时切到 Move 工具 → tool 改变但没事件清理
- 编辑 A 时点画布空白处 → 已有 stage.mousedown 走 deselect 路径，但没收编辑态
- 没有"取消一切"的标准入口

修了四条路径同时收编辑态 + 加新入口：

1. `onHitClick(id)` — 若 editingId 存在且 != id → `finishEditing()` 后再 selectElement
2. `onDragStart(id)` — 同上（覆盖"在编辑 A 时直接拖 B"的 Konva 跳过 click 的情况）
3. `onStageMouseDown` 走 deselect 分支时 → `finishEditing()`
4. `watch(ui.activeTool, ...)` — 切工具时收编辑态
5. **`onStageDblClick`（新）** — 双击空白处 = 取消选中 + 退出编辑（用户明确要求的 escape 路径）

`<v-stage>` 加 `@dblclick="onStageDblClick"`；`<v-rect>` 加 `@dragstart="() => onDragStart(el.id)"`。

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java` — 加 `rebuildWallGeometry`；open() 与"幂等重用"分支都补上
- `web/src/components/layout/CanvasView.vue` — `onDragStart` / `onStageDblClick`；`onHitClick` / `onStageMouseDown` / `watch(ui.activeTool)` 加 finishEditing 兜底；v-stage / v-rect 绑新事件

**质量门：** plugin compileJava OK；vite build OK。

---

## 2026-05-12 · M6 第三轮：wall.refresh promise ack + Move 工具

**用户反馈 2 条：**

1. 撸画框（不是方块）后点刷新仍不恢复 + 总报"服务端无响应"
2. 要 PS 风格的"Move 移动工具"——独立按钮，按下后单击纯拖、双击进编辑、不显示 resize 锚点

---

**1. wall.refresh 两个隐藏 bug**

- **撸 frame 仍跳过 slot：** `FrameDeployer.repairFor` 在收集已存在 frame 时漏过滤 `f.isDead()` / `!f.isValid()`。创造模式刚 break 的 frame entity 仍可能短暂出现在 `getEntitiesByClass` 结果里，PDC 匹配后被误当成"已存在"加进 `present` set，对应 slot 被跳过 → 新 frame 永远不补。加 `if (f.isDead() || !f.isValid()) continue;` 跳过。
- **"服务端无响应"误报：** 原 TopBar 实现用 `project.state.version` 变化判定成功，但 wall.refresh 只是触发全画布像素重画，ProjectState version 不会变 → 任何情况都走 5s timeout 分支。**根治：引入按 client id 跟踪 ack 的 Promise 机制。**
  - `WsClient.sendWithAck(op, payload, timeoutMs)` 返回 `Promise<ackPayload>`；ack 来时解 resolve，error 来时 reject，timeout 默认 5s
  - 内部 `pendingAcks: Map<id, {resolve, reject, timer}>` 跟踪；`handleAck(id, payload)` / `handleError(id, payload)` 顺手 settle
  - `TopBar.refreshWall` 改为 `await ws.sendWithAck('wall.refresh', undefined, 8000)`，从 ack payload 取真实 `framesRespawned` / `wallBlocksReplaced` 数字拼成文案（"补回 2 块方块 + 重挂 1 个画框"）

后续 `template.apply` 等长 op 也可以迁到 sendWithAck（M7 polish 范围）。

**2. Move 工具（PS 风格）**

- `ui store` 新增 `activeTool: 'select' | 'move'` + `setTool()`；localStorage 持久化
- `LeftTools` 顶部加两个工具按钮（MousePointer2 / Move 图标），点亮态突出显示当前激活
- **`select` 模式（默认）：** 当前行为不变 —— 点选 + 显示 transformer 锚点（12px）+ 双击文本进 inline edit
- **`move` 模式：** transformer 完全隐藏（无锚点遮挡）；hit rect 仍可拖、可双击进 edit、可 hover 显示蓝色虚线描边和 cursor:move
- 快捷键：`V` 切 select，`M` 切 move（input/textarea 内输入时跳过）
- `attachTransformer` 加 `activeTool === 'move'` 分支强制 `nodes([])`；watch `ui.activeTool` 切换即时生效

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `repairFor` 扫 entity 时跳过 dead/invalid
- `web/src/network/wsClient.ts` — `sendWithAck` Promise API + pendingAcks 跟踪；`handleAck/handleError` 接收 envelope id
- `web/src/components/layout/TopBar.vue` — refreshWall 用 sendWithAck；flash 文案显示真实计数
- `web/src/stores/ui.ts` — `activeTool` + `setTool()` + `TOOL_KEY` 持久化
- `web/src/components/layout/LeftTools.vue` — 顶部加 select/move 工具组按钮
- `web/src/components/layout/CanvasView.vue` — `attachTransformer` 在 move 模式跳过 attach；watch activeTool；V/M 全局快捷键
- `web/src/i18n/messages.ts` — `tools.selectTool` / `tools.moveTool` / `wall.refreshedDetail`（接受参数）/ `refreshSendFailed`（中英）

**质量门：** plugin compileJava OK；vite build 357 KB / 26 KB；功能侧待用户实测验证。

---

## 2026-05-12 · M6 第二轮实测打磨（wall.refresh 真修 + 编辑器交互升级）

**用户反馈 4 条：**

1. **wall.refresh 在玩家撸掉方块时无效**
2. **应用模板后想加 element 交互困难**（例：站牌旁加绿框 + 写"2 号线"）
3. **编辑画布时前端有滞后感**，改字体偶尔无反馈
4. **想要 PS 风格的自由拖动**（"那个大细定位方框太难拖动"）

---

**根因 1：撸方块 ≠ 撸画框。** `FrameDeployer.repairFor` 之前只补 spawn 画框，但玩家通常是撸掉了**支撑方块**——画框因此脱落变物品。再 spawn 同位置画框还是会立即掉。`replaceMissingWallBlocks` 新增：扫 wall bbox 内每格，AIR → 自动 `setType(STONE)`，再走 spawn。返回 `RepairResult(framesRespawned, wallBlocksReplaced)`。

WebServer.wall.refresh 改造：原来主线程任务一发出立即 ack `{submitted:true}`；现在把 ack 移到主线程任务**完成后**回发，payload 携带两个真实数字。前端 TopBar 改成轮询 `project.state.version` + `lastOpError.ts`，5s 内任一变化即认定为已完成/失败/超时，屏幕上短暂 flash 一段中文/英文状态（"已刷新" / 错误码 / "服务端无响应"）。

**根因 4 + 部分 2：transformer 锚点 + hit rect 体验。**

- 锚点 `anchorSize: 8 → 12`，`rotateAnchorOffset: 24 → 32`，更易精确拖
- hit rect 加 hover 反馈：未选中但鼠标悬停时画 1px 蓝色虚线描边 + 切 `cursor: move`，提示"这是可拖拽的对象"
- 选中后 transformer 自带粗 1.5px 实线 border，与 hover 虚线视觉区分清楚

**根因 2 另一半：新元素生成位置硬编码 (32, 32)。** 模板应用后画布已经满，再加一个新 element 叠在角落几乎看不见。改成：

- `text` 新建 → 192×48 默认尺寸、字号 32（之前 16）、放画布几何中心
- `rect` 新建 → 80×80 默认尺寸、放画布几何中心
- 计算用 `project.canvasPixelWidth / canvasPixelHeight`

**根因 3：RightPanel 输入防抖 200ms 太长。** 玩家敲一个字到画布反映之间最长 0.2 秒，叠加 WS RTT 给人卡顿感。改为 80ms（保留 color/select 立即路径不变）。

---

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `RepairResult` record + `replaceMissingWallBlocks`；`repairFor` 返回类型变更
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` — wall.refresh 改为主线程完成后 ack，payload 含 framesRespawned/wallBlocksReplaced
- `web/src/components/layout/TopBar.vue` — refreshWall 轮询版本/错误时间戳；按钮旁边加 refreshFlash 文案
- `web/src/components/layout/CanvasView.vue` — 锚点 12px、hover 描边、hover cursor:move
- `web/src/components/layout/RightPanel.vue` — debounce 200→80
- `web/src/components/layout/LeftTools.vue` — addText / addRect 走 `centeredBox`，画布中心 + 更大默认尺寸
- `web/src/i18n/messages.ts` — `wall.refreshed` / `wall.refreshTimeout` 加 zh + en；refreshTip 描述更新

**质量门：** plugin 编译 OK；vite build 354 KB JS / 26 KB CSS。

---

## 2026-05-12 · M6 模板系统首次实测打磨

**用户反馈：** 从 `welcome_banner`（min 4×2）切到 `nameplate`（max 4×1）apply 后画布不变，模态自动关。无错误提示——以为是 bug。

**根因 1：apply 静默失败**

- 服务端 `TemplateInstantiator` 已正确返回 `Failed("CANVAS_MISMATCH", ...)`；`WebServer.applyTemplate` 转 `EditSession.OpResult.Error` 走 Envelope.error 回前端
- 但 `wsClient.handleError` 只在 `AUTH_FAILED` 时设置 `net.lastError`，其他错误只 push 到 logs。TemplateGallery 没有路径感知这条错误，1.2s timeout 后乐观关闭模态
- 修复：`network.ts` 加 `lastOpError = { code, message, ts }`；`wsClient.handleError` 给所有错误打到 lastOpError 上。Gallery 的 `applyNow` 改成捕获 `project.state.version` + `lastOpError.ts` 基线，100ms 轮询其一变化判定成功/失败/5s 超时

**根因 2：模板字体 ID 笔误**

- 5 个新增模板（subway_station / shop_sign / welcome_banner / bulletin_board / nameplate）的 text element 都写 `font: sourcehan`
- 但后端 `FontRegistry.BUILT_IN` 注册的 ID 是 `source_han_sans`；不匹配时 fallback 到 `ark_pixel`（像素字体），CJK 字幕过粗看起来怪
- 修复：全局替换为 `source_han_sans`

**用户附加诉求：模板"占地"提示 + 字体选择**

1. Gallery 卡片现在显示 `推荐占地: 3×1 – 8×2 maps`；当前 wall 不在范围内时卡片右上有黄色 ⚠ + 红色"不兼容"标签
2. 选中模板时 form header 同步显示 `推荐占地 / 当前墙面 / 不兼容` 三段，方便对比
3. footer 在不兼容时显示完整提示 `当前墙面与模板要求的 2×1 – 4×1 不匹配。请新建一面匹配尺寸的墙面后再应用`
4. Apply 按钮在不兼容时禁用（避免一次无谓的服务端往返）
5. `shop_sign` + `bulletin_board` 新增 `font` 类型 param，default `source_han_sans`，前端遇到 `type: font` 且 YAML 没 options 时自动用 `FONT_META`（思源 / Ark Pixel）填下拉

**改动文件：**

- `plugin/src/main/resources/templates/{subway_station,shop_sign,welcome_banner,bulletin_board,nameplate}.yml` — font ID 修正；shop_sign + bulletin_board 加 `${shop_font}` / `${board_font}` 参数化
- `web/src/stores/network.ts` — 加 `lastOpError`
- `web/src/network/wsClient.ts` — handleError 同步打 `lastOpError`
- `web/src/components/template/TemplateGallery.vue` — apply 成功/失败/超时三态轮询；卡片 + form header + footer 三处尺寸提示；不兼容时禁用 Apply；font 参数自动 options
- `web/src/i18n/messages.ts` — 加 `templates.sizeLabel / currentWall / incompatible / incompatibleHint / wallMismatchHint / applyFailed / applyTimeout`（中英双语）

**质量门：** plugin test 全绿；vite build 通过；vue-tsc M6-F 触及的所有文件零错（剩 1 个 i18n/index.ts 是 pre-existing union 收窄问题，与本次无关）。

---

## 2026-05-12 · M6 模板系统实装（A-G 子阶段）

**背景：** 按 2026-05-11 路线确认条目里的 9 项决策，一气把 M6 跑完。所有契约文档已先于代码改完，本次按 7 子阶段顺序落地。代码量：plugin 端约 1500 LOC + 6 内置 yml + 8 个测试类 90 个 case；web 端约 700 LOC + 1 个新 store + 1 个新模态组件。

**M6-A 解析与注册（plugin）**

新建包 `moe.hikari.canvas.template`：

- `TemplateSpec` / `TemplateCanvas` / `TemplateLayout` / `TemplateElement`（sealed: Text/Rect/Line）/ `TemplateParam` / `TemplateEffects` — Jackson 友好的 record 树
- `TemplateLoader` — `jackson-dataformat-yaml` 2.18.2 直接 `readValue` 到 record；§9 全套校验（id/name/spec/canvas dims/colors/param id/enum opts/`${ref}` 未声明扫描）；grid layout v1 拒；icon v1 拒；polymorphic typing 显式关
- `TemplateRegistry` — 内置（jar `_index.txt` 清单 + `getResourceAsStream`）+ 服务器（`plugins/HikariCanvas/templates/*.yml`）合并，server 同 id 覆盖 builtin，`volatile Map` 原子 swap
- `_index.txt` — 内置模板清单文件（dev 模式 classes/resources 分离 + 打包 jar 两路统一）
- `/canvas reload templates` — `canvas.admin` 权限，输出 builtin/server/overrides/failed 数

`build.gradle.kts` 加 `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2`。

**M6-B 表达式 + 插值**

新建子包 `template.expr`：

- `Expr` — sealed AST：`Literal` / `Identifier` / `Not` / `Binary(EQ/NE/AND/OR)`
- `ExpressionParser` — lex + 递归下降。优先级 `!` > `==/!=` > `&&` > `||`（C 系一致）；字符串/数字/布尔字面量 + `()` 分组；`ParseException` 带 0-based 位置
- `ExpressionEvaluator` — truthy 规则（null/0/空串 = false）+ 类型敏感相等（Number→double / Boolean→bool / 否则 toString）；短路 `&&` `||`
- `Interpolator` — `${name}` 替换；无 `${` 零分配 fast-path；不递归（防注入）
- `TemplateLoader.validateElement / validateParam` 接入 `ExpressionParser.parse` 完成 §9 末项"visible_when 可解析"语法校验

**M6-C 实例化引擎**

- `TemplateInstantiator.instantiate(spec, userParams, wallW, wallH) → Result.Ok(bg, elements, resolvedParams) | Failed(code, errors)`
- 流水线：`validateParams` → `fitCanvas` → `resolveBackground` → `layoutAndMaterialize`
- 错误码：`INVALID_PARAM` / `CANVAS_MISMATCH` / `INVALID_VALUE` / `INVALID_LAYOUT` / `INVALID_TEMPLATE`
- stack 布局：忽略 element 自带 x/y，按 direction 累加 + gap；w 撑满 content；text 自然高 = `ceil(size × lineHeight × lines)`
- free 布局：x/y/w/h 直接取；w/h 支持 `int` / `"auto"` / `"N%"`（按 content basis） / `${param}`
- `visible == false` 直接 false；`visible_when` 走 evaluator；不可见元素 skip 不写入 ProjectState（避免"隐形僵尸"）
- text 物化所有 string 字段插值，effects（stroke/shadow/glow）透传；line v1 跳过返回 null

**M6-D WS 接入**

- `EditSession.replaceContent(bg, elements)` 通用 replace 入口（替代旧的硬编码 `applyTemplate` + `buildHelloWorld`）；清 elements + 改背景 + 推进 version + push pre-snapshot 到 history（让 undo 可回）
- `WallRepo.setTemplate(wallId, templateId, version)` — 写回 `walls.template_id` / `template_version` 列
- `WebServer.applyTemplate` 私有方法 — `template.apply` op 中枢：registry 查表 → instantiator 实例化 → `replaceContent` → walls write-back
- `WebServer.listTemplates()` — 给 `/api/session/{token}` 和 ready payload 注入全量 TemplateSpec 列表（protocol §3.2）
- M3 硬编码 `hello_world` 路径整段删，统一走 YAML 流水线

**M6-E 5 个内置模板**

| ID | 用途 | 画布 | 布局 |
|---|---|---|---|
| `hello_world` | demo / apply 演示 | 1×1–8×4 auto | free |
| `subway_station` | 地铁/公交站牌 | 3×1–8×2 auto | stack vertical |
| `shop_sign` | 商店门头 | 3×1–6×2 auto | stack vertical |
| `welcome_banner` | 服务器欢迎横幅 | 4×2–8×4 auto | stack vertical |
| `bulletin_board` | 米黄底公告板 | 3×1–8×3 auto | stack vertical |
| `nameplate` | 门牌 | 2×1–4×1 auto | free（左右分区） |

每模板覆盖至少 2 种 param type，含 `visible_when` 开关、color preset、stack + free 各覆盖。

> 命名调整：原路线条目里写的 `warning` / `neon_sign` / `minimal` 在落地时换成更贴近实际游戏内场景的 `shop_sign` / `welcome_banner` / `bulletin_board` / `nameplate`（多了一个）。前三个比较抽象、缺明确 param shape；后四个一上来就是装饰场景里能直接用的。

**M6-F TemplateGallery 前端**

- `web/src/types/template.ts` — TemplateSpec / Canvas / Param / Layout / Element TS 类型（Java record 镜像）
- `web/src/lib/templateExpr.ts` — visible_when 解析器 TS 版本（≈ Java `ExpressionEvaluator` 等价；失败保守返回 true）
- `web/src/stores/templates.ts` — Pinia store：模板列表 / Gallery 开关 / selectedId / 每模板独立 param 草稿
- `web/src/components/template/TemplateGallery.vue` — 模态：左侧卡片列表 + 右侧动态参数表单 + 底部 Apply / 二次确认
- 表单按 type 渲染 8 种控件（string/text/int/float/bool/color+presets/enum/font）；`visible_when` 实时驱动字段显隐
- `web/src/network/wsClient.ts` — handleReady 把 templates 推入 store
- `web/src/components/layout/LeftTools.vue` — Sparkles 按钮从硬编码 hello_world 改为 `templates.openGallery()`
- `web/src/i18n/messages.ts` — 新增 `templates.*` 文案（zh + en）

**M6-G 收口**

- `docs/template-spec.md §12` — 百分比父容器定义打钩归档为"M6-C 已固化：父容器 = canvas 内容区（pixel 尺寸减 padding 4 元）"
- `CLAUDE.md` 里程碑 → M6 ✅ + 2026-05-12 完成日期
- 本日志条目
- 5 个未决问题中剩余 4 个（继承 / grid / preview 规范 / group UI）明确推迟到 M7 polish 或 v2+

**质量门**

- 后端：90 个测试 89 绿 + 1 pre-existing `RendererSnapshotTest.01-hello-world` 像素 baseline 漂移（与 M6 无关）
  - Loader 15 / Parser 14 / Evaluator 12 / Interpolator 8 / Instantiator 17 / BuiltinTemplates 11 / HelloWorldYaml 4 / EditSessionReplaceContent 4 = 85 个 M6 新增
- 前端：`vue-tsc --noEmit` M6-F 触及的所有新文件零错（剩 25 个 pre-existing CanvasView/PreviewRenderer 等遗留，与 M6 无关）；`vite build` 348 KB JS / 26 KB CSS 通过

**下一步：** M7 polish（grid 布局 / icon 元素 / preview thumbnail / 分组 UI / 已发布 wall 破坏保护 / 雪藏的 snapshot baseline 重建 / 身份认证与鉴权独立 milestone）

---

## 2026-05-11 · M6 路线确认（仅文档；不动代码）

**背景：** M5.5 polish 已收口，进 M6 模板系统之前先固化 4 个决策到契约文档，避免实施期反复。

**5 个开放问题已拍板（含上次的 5 个）：**
1. **YAML 解析库**：jackson-dataformat-yaml 2.18.2（不用 SnakeYAML）。同步改 CLAUDE.md 锁定版本表 + PROPOSAL.md 依赖表 + `docs/security.md §4.3` 与第三方库表
2. **ready payload 模板下发**：v1 阶段全量下发 `templates: [...]`，5 个内置 + 服主自定义合计 < 50KB 可接受。未来若爆量再切 index + on-demand
3. **5 个内置模板**：`subway_station` / `shop_sign` / `warning` / `neon_sign` / `minimal`
4. **`/canvas reload templates`**：M6 v1 实装（管理员命令，原子 swap 替换 registry 指针）
5. **Apply 语义**：replace（清 elements + 改 background），前端弹"覆盖当前内容"提示；merge 留 v2+
6. **Layout 实装**：M6 v1 stack + free；grid 留 M7
7. **canvas auto-size 超 limit**：实例化失败，错误回客户端
8. **icon 元素**：v1.0 不实现（契约 §4.6 已说明）
9. **参数组 group**：v1 接受字段但不渲染，扁平展开

**文档改动清单：**

- **CLAUDE.md**：技术栈表 SnakeYAML → jackson-dataformat-yaml 2.18.2 + M6 决策段
- **PROPOSAL.md**：§5.2 依赖表同步
- **docs/security.md**：§4.3 YAML 解析改写（jackson-yaml 默认免疫 SnakeYAML `!!java/*` tag 路径）；§T9 威胁备注更新；§10.1 第三方库表替换
- **docs/protocol.md**：ready payload 加 `templates` 字段示例 + 全量下发决策段；§12 未决问题打钩两条（template.apply merge / M6 templates 协议固化）
- **docs/template-spec.md**：§1 加 jackson-dataformat-yaml 备注 + `/canvas reload templates` 命名固化；§7 实例化加 replace 语义段 + walls.template_id 写回；§12 未决问题状态更新（grid/group/preview/百分比四条明确 v1 范围）
- **docs/architecture.md**：§2.1 组件分层 template/ 描述补 jackson-yaml + registry 热重载

**M6 范围速查（待实施，7 个子阶段，~6-7 天）：**

- **A** 解析与注册：TemplateSpec records / TemplateLoader / TemplateRegistry（含热重载）
- **B** 表达式 + 插值：极简 parser `==/!=/&&/||/!/()` + 字符串 `${param}` + 单元测试
- **C** 实例化引擎：参数校验 / canvas auto-size / stack+free layout / 百分比展开 / 元素插值
- **D** WS 接入：`template.apply` 改走 registry；ready payload 加 templates；apply 后 `WallRepo.setTemplate`
- **E** 5 个内置 yml + 内置 resource 加载
- **F** TemplateGallery 前端 dialog（动态参数表单 + visible_when + 应用确认）
- **G** 收口：单测 / journal / CLAUDE.md 里程碑勾完

**下一轮开 M6-A 实施代码。**

---

## 2026-05-11 · M5.5 polish 收口（i18n / alias 校验 / copy 反馈）

**背景：** 主功能已通，先填几个之前散落的体验坑再进 M6。身份认证 / 鉴权单列下一个 milestone 不在此次范围。

**改动：**

1. **i18n 漏译补全**：`messages.ts` 加两组 `wall.*` + `home.*`，把 TopBar 的 wall_id / alias / Published / Draft / Refresh 按钮全切到 `t.wall.*`；HomePage 整页（heading / subtitle / loading / failed / empty / 分组标题 / 卡片 maps+updatedAt+复制提示）切到 `t.home.*`。中英文双向（en→中英 switchLocale 文案也对齐：en mode 显示 `切换到中文`，zh mode 显示 `Switch to English`）。
2. **alias 字符集校验三路统一**：正则 `^[A-Za-z0-9_-]{2,32}$`。
   - 前端 `TopBar.commitAliasEdit` 加 `ALIAS_RE` 校验；服务端 `INVALID_ALIAS_FORMAT` / `ALIAS_TAKEN` 报错时 watch `net.lastError` 自动重打开输入 + 显错
   - WebServer `wall.alias` 分支用 `ALIAS_PATTERN` 替代旧的长度校验，错误码改 `INVALID_ALIAS_FORMAT`
   - CanvasCommand `runAlias` 同 pattern，错误提示 `[A-Za-z0-9_-]{2,32}`
3. **copy 操作的视觉反馈**：
   - TopBar wall_id 按钮：点击复制后 800ms 内显示 `Copied`（绿色），不再只 log
   - HomePage 卡片底部 `/canvas open <id>` 命令文本：点击复制后 900ms 内同样视觉反馈

**改的文件：** `web/src/i18n/messages.ts` / `web/src/components/layout/TopBar.vue` / `web/src/components/HomePage.vue` / `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` / `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`

**下一步：** M6 模板系统（5 个内置 + YAML parser + TemplateGallery dialog）。身份认证 / 鉴权单独立项处理（HomePage 点击直接打开需要先有玩家身份机制）。

---

## 2026-05-11 · wall 实测打磨（自家画框识别 + wall.refresh）

**背景：** P1-P5 实测后剩两个体验问题：(a) `/canvas edit` 在已有画的墙上选区会撞 `OCCUPIED`，玩家被迫先 delete 才能二次编辑；(b) 创造模式打掉某个画框后该位置永远空白，没有恢复入口。

**改动：**

1. **WallResolver 区分 HikariCanvas 自家 vs 第三方画框**：
   - 构造参数加 `JavaPlugin`，内部生成 `wall_id` PDC NamespacedKey
   - 内部 `kindOfFrame()` 返回 `FrameKind.{NONE, HIKARI, FOREIGN}`，HIKARI 通过 PDC 识别
   - 校验循环：FOREIGN 才报 OCCUPIED；HIKARI 通过且记 `hasExistingFrames=true`；NONE 仍要求前一格 air
   - `Result.Ok` 加 `boolean hasExistingFrames` 字段
2. **SessionManager.confirm 三分支**：
   - `hasFrames && walls 行齐全` → `mapPool.bindToWall` → `OkExistingWall`（二次编辑路径）
   - `hasFrames` xor `walls 行` 单边存在 → `NotReady("stale wall data; /canvas delete to reset")`
   - 都无 → 走原新建路径 `OkNewWall`
3. **wall.refresh op（含玩家撸框恢复）**：
   - `FrameDeployer` 抽 `spawnSlot` helper；新增 `repairFor(wallId, wall, mapIds)`：扫世界 PDC 收集仍存在的 slot，对缺失 slot 用同样几何/PDC/mapId 重新 spawn
   - `WebServer.handleWallOp` 加 `case "wall.refresh"`：主线程跑 `repairFor` + `throttler.submit(fullCanvas)` 强制全画布重画
   - 前端 `TopBar.vue` 加 `RefreshCw` 按钮 → `ws.send('wall.refresh')`，1.5s 内禁用避免连点
4. **WandListener.routeFrameClick SELECTING 优先**：玩家在 SELECTING 状态点画框 → 视为选角（不再触发"二次确认 → open"路径），消除"You already have an active session (state=SELECTING)"误报

**改的文件：** `WallResolver.java` / `FrameDeployer.java` / `SessionManager.java` / `WebServer.java` / `WandListener.java` / `HikariCanvas.java`（WallResolver 构造改）/ `web/src/components/layout/TopBar.vue`

**未变契约：** 一墙一画（WallKey 唯一索引）+ 排他锁仍保留。WallResolver 现在的 OCCUPIED 只指"第三方画框"，与 docs 一致——`/canvas edit` 选区已挂自家画的墙变成"二次编辑入口"而非错误。

---

## 2026-05-06 · M5.5 实测后批量 fix

**背景：** P1-P5 上线后实测撞到 6 个独立 bug，统一一波修。

**改动：**

1. **MigrationRunner 漏注册 V005**：`MIGRATIONS` 列表只到 V004，V005 文件存在但永远不跑 → `no such table: walls`。同根因 V002 漏注册的复发（显式声明制的代价）。修：list 末尾追加。
2. **Envelope.error null message NPE**：`Map.of("message", null)` 不允许 null value，handleAuth 拒绝 token 时传 null → NPE → WS uncaught → close 1011。改 `LinkedHashMap` 手动 put + null 跳过。
3. **FrameDeployer.deploy col 方向对 NORTH/EAST 反向**：M2 阶段 `col 0` 对 WEST/SOUTH 是玩家视角最左，但 NORTH/EAST 漏了反向 → 游戏内文字与浏览器画布左右倒挂。改成显式 switch 四个 facing，明确 `col 0 = 玩家视角最左`。
4. **SessionManager.open 同 wall 幂等**：玩家关浏览器但 session 还 ACTIVE → 再 `/canvas open <同 id>` 报 AlreadyHasSession。改成：先 load wall → 同 wall_id 直接复用 existing session 重发 URL，不同 wall 才报错。
5. **handleAuth markActive 容忍 ACTIVE**：`/canvas open` 复用 ACTIVE session → `markActive` 严格要求 ISSUED 抛 IllegalStateException → 前端显示"token 已失效"。改成 ISSUED 走转移、ACTIVE 只 touch。顺手把"旧 WS ctx → close 4003 takeover"写明。
6. **WebServer dispatch 没匹配 wall.alias / publish / unpublish**：顶层 switch 只列 `element.*` / `canvas.*` / `template.apply` → wall.* 全落 default 返 INVALID_OP → 前端 alias / publish 静默失败（store optimistic 改了，但 walls 表实际 NULL）。修：switch 加 `case "wall.publish", "wall.unpublish", "wall.alias", "wall.refresh"` 路由到 `dispatchWallOp`。同时把之前写在 `dispatchEditOp` 内部的 `wall.*` early-return 删（永远走不到）。
7. **alias UI 改内联输入**：浏览器 `prompt()` 替换为 TopBar 内联 input（pencil 图标点开 + Enter 提交 + Esc 取消 + blur 自动提交 + ALIAS_TAKEN 错误回滚显示）。

**改的文件：** `MigrationRunner.java` / `Envelope.java` / `FrameDeployer.java` / `SessionManager.java` / `WebServer.java` / `web/src/components/layout/TopBar.vue`

---

## 2026-04-27 · M5.5 P1-P5 代码实施（wall 模型重构落地）

**背景：** 04-27 上半段已固化路线到契约文档；下半段开始按 5-Phase 分步落地代码。每 Phase 完成立即 `gradle :plugin:compileJava` 验证。

**P1 数据层：**
- 新 migration `V005__walls_unified.sql`：drop sign_records / drafts；recreate pool_maps（删 sign_id 列、迁 PERMANENT→RESERVED）；create walls 表（schema 见 `docs/data-model.md §2.4`）
- 新 `WallRepo`：`create / loadById / loadByAlias / loadByKey / loadByMapId(反查) / loadAll / listForOwner / listAll / setAlias / markPublished / markUnpublished / updateMapIds / updateState / delete`，含 wall_id 短 hex 生成 + UNIQUE 冲突重试
- `PooledMap` 删 `signId` 字段；`PoolState` 由三态收为两态；`MapPool` 删 `promoteToPermanent` + `markDraftHeld`，新增 `reserveForWall / bindToWall / releaseWall`；`detectLeaks` 改"非 `wall:` 前缀全回收"
- `DraftRepo` / `DraftRestorer` 文件删除，由 `WallRepo` / `WallRestorer` 替代
- `SessionManager.confirm` 改两分支 `OkNewWall` / `OkExistingWall`；删 `commit / promoteToPermanent` 整条；新增 `open / deleteWall / persistWall / resetSelection`
- `SessionReaper` 不再拆框（wall 数据生命周期与 session 解耦）

**P2 命令族：** `CanvasCommand` 删 `runCommit` + `insertSignRecord`；新增 `runOpen / runList / runPublish / runUnpublish / runAlias / runDeleteFirstStep / runDeleteConfirm`；delete 30s 二次确认走 `pendingDeletes` map；`runCancel` 改为"释放 session/wand，wall 数据保留"。

**P3 wand 二次编辑：** `FrameDeployer` PDC key 改 `wall_id / slot / published_at`；新方法 `removeForWall / markPublished / wallIdOf`；`WandListener` entity 事件分流：HikariCanvas ItemFrame → ActionBar 提示 → 30s 内再次操作触发 `sessionManager.open + tokenService.issue + 发 chat URL`；第三方画框走原选区流程。

**P4 网页 wall 元数据：** 后端 `ready` payload 加 `wallId / alias / publishedAt`；dispatch 加 `wall.publish / wall.unpublish / wall.alias` op（不走 EditSession）；前端 `protocol.ts` `ReadyPayload` 加这三字段；`stores/project.ts` 加 wallId / alias / publishedAt + `setWallMeta`；`wsClient.handleReady` / `handleAck` 同步 store；`TopBar.vue` 显示 wall_id（click copy）+ alias（pencil prompt）+ Published/Draft 徽章。

**P5 网页首页：** `App.vue` URL 没 token → 渲染 `HomePage.vue`；后端新 `GET /api/walls` 返回 `WallRepo.listAll()`；HomePage 分组"已发布 / 编辑中"展示，每张卡片显 wall_id / alias / world+coord+facing / 尺寸 / updatedAt + 可点击复制的 `/canvas open <id>`。

**未做：** alias 实施时 prompt() 还是浏览器原生弹窗；refresh 入口；wand SELECTING 中点画框走 open 误报——这三个 5-06/5-11 修。

---

## 2026-04-26 · M5-D5 / D6 / D7 系列（M5.5 之前的中间态，部分被推翻）

**背景：** 这段时间走的是"drafts + sign_records 二段式"流派，三轮迭代加完整功能后实测发现该模型不顺手（二次编辑 OCCUPIED、commit 后 drafts 没清状态机污染），于是触发 04-27 的 M5.5 路线修正。中间产物（V002-V004 三个 migration、DraftRepo、DraftRestorer、wand_id 字段预留）保留作为 schema_version 历史链，但表本身已被 V005 drop。

**M5-D5（双击就地编辑 + WS 自动重连 + delete 二次确认）：**
- `CanvasView.vue` 加 `editingId` 状态，`v-rect` 监听 `dblclick` 打开 textarea overlay，绝对定位对齐 element x/y/w/h + rotation；Enter 提交、Shift+Enter 换行、Esc/blur 退出；style 改"原生感"透明背景 + 字体继承
- `PreviewRenderer.ts` 加 `hideIds` 参数避免画布字与 textarea 重影
- `wsClient.ts` `onClose` 加指数退避重连（1/2/5/10/30s 阶梯，5 次后停）；4001/4008/1000 不重连；4001 清 sessionStorage token
- `CanvasCommand` `runDelete` 30s 内带 `confirm` 才真删（旧版"删 sign_record 然后 promote"逻辑）

**M5-D6（drafts 表草稿持久化）：**
- `V002__drafts.sql` 建 drafts 表（world/origin/facing 复合主键）；`V003__drafts_add_maps.sql` 加 map_ids + width/height；`V004__drafts_wall_id_alias.sql` 预留 wall_id + alias
- `DraftRepo` save/load/loadAll；`SessionManager.persistDraft` 每次 op 后存 ProjectState JSON
- `WebServer` op handler 后调 `persistDraft`

**M5-D7（草稿真恢复到游戏内）：**
- `DraftRestorer` 启动期扫 drafts 表，compose ProjectState → 推像素到 `HikariCanvasRenderer`，`MapPool.markDraftHeld(draft:<tag>)` 保护 map 不被 leak 扫
- `SessionManager.confirm` 加复用 draft 路径：mapIds 仍在池就 `attachToSession`

**04-27 推翻范围：** drafts 表（V005 drop）、`DraftRepo` / `DraftRestorer`（删）、`MapPool.markDraftHeld` / `attachToSession`（合并为 `bindToWall`）、`MapPool.promoteToPermanent`（删）、sign_records 表（V005 drop）、`commit` 命令（废止）。中间产物的代码细节已不重要，但 V002-V004 migration 文件保留以确保 schema_version 链完整。

---

## 2026-04-27 · M5.5 wall 模型重构（路线修正，仅文档；不动代码）

**背景：** M5 实测下来发现 commit 模型不顺手——已 commit 画的二次编辑撞 `WallResolver OCCUPIED`、commit 后 drafts 没清导致重启状态错乱、`/canvas edit` 在已有 session 时报 `state=SELECTING` 提示模糊、wand 选区被已挂的 ItemFrame 默认行为吃掉。讨论后决定**推翻 commit 模型**，按"一画一行 walls 表 + published_at 标签"的简化模型重构。先固化路线到契约文档，下个会话开工代码。

**用户拍板的 5 个开放问题：**
1. `/canvas delete <id>` 30s 内 `/canvas delete <id> confirm` 二次确认
2. publish 副作用：纯标签 + ItemFrame PDC 写 `published_at`（M7 polish 加 break 拦截）
3. wall 占的 map 一直占着不自动释放，需 `/canvas delete` 显式清
4. wand 瞄已有 ItemFrame：先提示「This is wall <id> 'alias' — left-click again to open」，再次操作才打开二次编辑
5. 排他锁保留（`byWall` 一墙一时刻一 session），不做协作编辑

**契约文档更新清单：**

- **`CLAUDE.md`**：里程碑插入 M5.5 阶段；新增「M5.5 wall 模型重构」一整节固化新模型 6 条规则与已修订文档列表。
- **`docs/architecture.md`**：
  - §1.1 一句话改写：从"提交后成为永久招牌"改为"任何时候都能再次打开继续改"
  - §1.3 数据流：删「提交（转永久）」流程图，改为「发布（标签层）」+「删除（仅此操作真正移除 wall）」两段
  - §3 状态机重写：删 `ACTIVE → CLOSING(commit)` 转移；新增 `CLOSED → ACTIVE`（`/canvas open`）；状态转移表新增 reselect / publish / open 行；明确「session 生命周期 vs wall 数据生命周期解耦」不变量
  - §4.2 `PooledMap` schema：删 `signId`、state 收为两态、reservedBy 改 `wall:<wall_id>`
  - §4.3 reserve/commit/cleanup pseudocode 重写：`reserveForWall` / `bindWall` 替代 `reserve` + `commit`
  - §4.5 健康指标：删 `pool.permanent`，加 `pool.unowned_reserved`
  - §7.2 物品框部署：PDC keys 从 `session/sign/role` 改为 `wall_id/slot/published_at`；§7.3 改"提交 vs 取消"为「wall 数据 vs 会话 生命周期对照表」
  - §8.2 SignRecord 表概览改为 walls 表概览
  - §10/§12 性能指标 / 未决问题加 M5.5 条目
- **`docs/data-model.md`**：
  - §2.3 `pool_maps`：删 `sign_id` 列、state 改两态、不变式重写
  - §2.4 `sign_records` → `walls` 完整重写（含 wall_id 主键、published_at、alias、唯一索引、删除语义无软删）
  - §2.5 `audit_log` event 列表更新（`COMMIT/CANCEL/CLEANUP` → `SESSION_*`/`WALL_*`/`POOL_*`）
  - §3 PDC 约定大幅简化：MapView PDC 不写、ItemFrame PDC 三个 key、Map Item PDC 不写
  - §6.5 新增「V005 整体重置」说明（M5.5 阶段无生产数据，drop + recreate 而非 alter）
  - §7.1 不一致场景重写（针对 walls + map_ids + ItemFrame PDC）
  - §8 查询示例全替换：玩家画清单 / 全局已发布 / 反查 mapId / 区域查询
  - §10 未决问题加 M5.5 条目（walls_map_index 反向表 / alias 大小写 / delete 备份）
- **`docs/protocol.md`**：
  - §3.4 客户端主动关闭：删 commit 提及，加 op auto-save 说明
  - §5.6 会话终结：删 `commit` op，加 `wall.publish` / `wall.unpublish` / `wall.alias`
  - §6 错误码加 `ALIAS_TAKEN` / `WALL_NOT_FOUND`
  - §6.2 close 1000 描述改写
  - §8.3 提交流程改写为「发布与终结」
  - §12 未决问题：history.mark 持久化决策更明确（M7 加 walls.history_json）
- **`docs/security.md`**：
  - §5 权限节点：`canvas.commit` → `canvas.publish`；`canvas.remove.{own,any}` → `canvas.delete.{own,any}`
  - §6 校验检查点：`/canvas remove` → `/canvas delete <wall_id>`（含二次确认强制 30s）
- **`docs/rendering.md`**：边界条件表格里"超大画布 commit 拒绝" → "confirm 阶段 WallResolver 拒绝"
- **`PROPOSAL.md`**：
  - §5.2.1 推送时机术语：'提交' → 'session 关闭前最后一帧'
  - §5.2.2 池设计：删 PERMANENT、reservedBy 改 wall: 前缀
  - §5.2.4 审计事件列表更新
  - 命令清单完整重写：edit / wand / confirm / open / list / publish / unpublish / alias / delete / cancel / cleanup / stats / audit；明确废止 commit
  - 性能指标 commit 改 confirm

**未动文件**：`docs/template-spec.md`（模板规范不涉及 wall 模型，M6 时再视情更新）。

**下一步：** 下个会话按 architecture.md §3-4 + data-model.md §2 的固化契约动代码。Phase 顺序：P1 数据层（V005 + WallRepo + MapPool 改）→ P2 命令族 → P3 WallResolver 二次识别 + wand 瞄 ItemFrame → P4 网页 wall 元数据 UI → P5 网页首页。预计 4 天 + 网页 2 天。

---

## 2026-04-23 · 文档 + 记忆归档整理（为下一次上下文压缩准备）

**背景：** 上下文即将压缩；为让下一次 Claude 实例接手时不丢队、不重复踩坑、不重新推理已定项目决策，对如下信息做集中归档。

**更新项：**

1. **`CLAUDE.md` 现代化：**
   - "双端渲染一致性"加入 canonical width 要点（M5-D2 起两端 TextLayout 不读 font metrics，改走规则型宽度）
   - "字体"条款重写为方案 A' 实际做法（两字体都 Gradle `downloadFonts` 拉，不入 git）
   - 新增"构建期 palette"条目（`generatePalette` + 前后端 `PaletteLut` 镜像）
   - 里程碑后新加 **构建/开发流程速查** 段：四条常用命令 + 前端状态管理入口 + 指向 journal.md 查细节

2. **Auto-memory 系统（`~/.claude/projects/.../memory/`）：**
   - `project_hikaricanvas.md` 彻底重写：里程碑状态表（M0~M5 ✅，M6 下一步，M7 polish 档）+ 固化技术栈 + 架构纪律 + 命名约定 + 关键文件速查 + M7 推迟项清单
   - **新增 `feedback_build_toolchain.md`**：9 类踩坑归档（phantom class files / vite 卡死 / vue-tsc 卡死 / kill -9 写坏 node_modules / sed 清空文件 / Tailwind 4 scoped @apply / Gradle daemon 竞争 / GitHub Releases Premature EOF 等）+ 速查修复步骤
   - `MEMORY.md` 索引更新加入新 memory

**为何这样做：**
- Memory 的作用是"跨 conversation 保留必要事实"，上下文压缩后的下一次对话可通过 memory 快速进入状态
- Journal 是 per-commit 的详细日志，memory 是 per-project 的状态快照——两者互补
- 构建工具链踩坑每个耗时 10+ 分钟排查；归档后直接按 symptom 查表修

**关联文件：**
- `CLAUDE.md`
- `~/.claude/projects/-Users-haru-Desktop----HikariCanvas/memory/project_hikaricanvas.md`（重写）
- `~/.claude/projects/-Users-haru-Desktop----HikariCanvas/memory/feedback_build_toolchain.md`（新建）
- `~/.claude/projects/-Users-haru-Desktop----HikariCanvas/memory/MEMORY.md`（索引）

---

## 2026-04-23 · M5-D3 修 P3 P4：i18n 中英切换 + 文本 Fit content

### P3 — i18n 中英切换

**做法：** 不引 vue-i18n（避免增加运行时依赖和体积）；自己写最简 composable：
- `web/src/i18n/messages.ts`：两套嵌套对象 `{ zh, en }`，覆盖 TopBar / Tools / Canvas / Properties / Layers / LogDrawer / Status 七个分区
- `web/src/i18n/index.ts`：`useI18n() → { t: ComputedRef<Messages> }`；基于 `ui.locale` 派生
- 组件里 `t.xxx.yyy` 直接用（Vue 模板会 unwrap computed 的属性访问）
- 函数型 message：`t.canvas.sizeLabel(w, h, pw, ph)`、`t.layers.count(n)` 等

**`ui` store：**
- 新增 `locale: Ref<'zh' | 'en'>`（`loadLocale` 依次查 `localStorage → navigator.language startsWith zh → en`）
- 新增 `toggleLocale()`；`watch(locale) → localStorage + document.documentElement.lang`
- TopBar 加 `Languages` icon 按钮触发切换

**覆盖范围：** 所有按钮 title / 面板 header / 状态标签 / 空态提示。技术字段名（`fontSize / letterSpacing / fontId` 等）保持英文（开发者/用户通用术语）。

### P4 — 文本 Fit height / Fit width 按钮

**位置：** Properties Panel > Text 组 > text textarea 下方，两个 `Maximize2` 图标按钮。

**算法（复用 `TextLayout.layoutText`）：**
- `fitTextHeight`：用 `layoutText(t)` 跑完整 layout → 找所有 glyph `baselineY` 最大值 → `maxBottom = maxBaselineY + descent`；`newH = maxBottom - t.y`；发 `element.update { h }`
- `fitTextWidth`：glyph 的右沿 = `g.x + (g.rotated ? fontSize : canonicalCharWidth(ch, fontSize))`；取 max；`newW = max - t.x`

**不自动：** 不触发自动 fit；用户显式点按钮。避免编辑文本中途被意外 resize 吞掉（Canva / Figma 也是显式行为）。

**导出：** `TextLayout.ts` 把 `canonicalCharWidth` / `ASCENT_RATIO` 导出供此处复用。

### 关联文件

- `web/src/stores/ui.ts`（+ locale / toggleLocale）
- `web/src/i18n/messages.ts`（新建，zh + en）
- `web/src/i18n/index.ts`（新建，useI18n）
- `web/src/components/layout/TopBar.vue`（+ Languages 按钮）
- `web/src/components/layout/LeftTools.vue` / `CanvasView.vue` / `StatusBar.vue` / `LogDrawer.vue`（替 title / label）
- `web/src/components/layout/RightPanel.vue`（i18n 替换 + `fitTextHeight` / `fitTextWidth` 按钮）

---

## 2026-04-23 · M5-D2 修 P1 + P2：auto-select + TextLayout canonical width

**实测反馈修两个：**

### P1 — 新增元素后自动选中

**问题：** 工具栏点"Add text / rect"后服务端创建元素，本地 state 更新但 selection 还是空——用户必须手动去 Layers 面板点一下才能看到 Properties 表单。

**修：**
- `project store` 加 `lastAddedElementId: Ref<string | null>`
- `applyPatch` 里检测 `op: 'add', path: /elements/N` → 记录 `value.id`
- `App.vue` watch `project.lastAddedElementId` → 非 null 时调 `ui.selectElement(id)` 并清零

多端场景（M6+ 多人协作）若其他 session add 也会触发 auto-select；M5 单端无影响。

### P2 — 前后端换行不一致（截图里是重头）

**根因：** 前端 `ctx.measureText(ch).width` 和 Java `FontMetrics.charWidth(c)` 即使加载同一 TTF/OTF 也返回不同值（浏览器 hinting / Java AWT rasterizer 差异）。TextLayout 的 `softWrap` 按字符宽度累加判断换行，差 1 px 就让前后端在不同位置断行。截图里 `"这是一个测试TESTABCDEFG..."` 在前端换成 3/3/3/3 模式（中文 3 字、英文 3 字），后端换成 2/4/4 模式。

**修（核心）：** 前后端 `TextLayout` 统一用**规则型 canonical width**：
- 码点 `< 0x2E80`（ASCII / 拉丁 / 一般标点）：`round(fontSize * 0.5)`
- 其他（CJK / 假名 / 全角）：`fontSize`

**代价与权衡：**
- 像素字体 Ark Pixel 本身 monospaced，canonical == actual，零副作用
- 思源黑非等宽，canonical 比实际略宽一些 → 视觉字间距偏松，但换行位置前后端**完美一致**
- 与 rendering.md §8.2 的 tolerance（pixelated=0%、常规 <0.5%）方向相符——layout 一致比字形精确优先

**改动：**
- `web/src/render/TextLayout.ts`：`canonicalCharWidth` 导出 + `layoutText` / `layoutHorizontal` / `layoutVertical` / `softWrap` / `measureLineWidth` 全部替换 `m.measureChar` → `canonicalCharWidth`；删旧 `CharMeasurer` 接口与 `CanvasMeasurer` 类
- `web/src/render/PreviewRenderer.ts`：不再构造 Measurer，直接 `layoutText(t)`
- `plugin/src/main/java/.../TextLayout.java`：加 `public static int canonicalCharWidth(char, int)`；`layout` / `softWrap` / `measureLineWidth` / `layoutVertical` 全部切到新函数；移除 `FontMetrics` 参数（签名简化为 `layout(TextElement)`；vertical 同）
- `plugin/src/main/java/.../CanvasCompositor.java`：`TextLayout.layout(t, fm)` → `TextLayout.layout(t)`

**snapshot 基线：** `canonicalCharWidth` 改了每个字符的 x，5 个 fixture 的 expected PNG 均变。`rm expected/*.png && ./gradlew :plugin:test` 重建基线，二跑通过。baseline diff 已入 git。

**关联文件：**
- `web/src/stores/project.ts`（+ lastAddedElementId）
- `web/src/App.vue`（watch auto-select）
- `web/src/render/TextLayout.ts` / `PreviewRenderer.ts`
- `plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java` / `CanvasCompositor.java`
- `plugin/src/test/resources/expected/*.png`（baseline 重生）

---

## 2026-04-23 · M5-D 收尾（scope 调整） · **M5 编辑器 UI 完成**

**scope 调整：** 原计划 M5-D = Playwright e2e + snapshot 测试台 + 手测。实施前盘点：
- 当前本机 Node 25 ↔ 新 toolchain 有多次兼容坑（vue-tsc 卡死、kill -9 打破 node_modules）
- Playwright chromium install ~200 MB，首次拉取慢且可能中断
- 前后端像素级一致需要 tolerance 调参轮次，非半天可完成

**决定：** Playwright snapshot 测试台正式推迟到 **M7 打磨发布**，和 M4/M5 所有 polish 一起处理（届时 toolchain 稳定）。**M5-D 只做**：
1. 端到端手测 checklist（提供给用户实测的详细步骤）
2. `PROPOSAL.md` 标 M5 完成；`CLAUDE.md` 里程碑行同步
3. 推迟项明确入 M7 档

**M5 段落汇总：**

| 段 | 范围 | 状态 |
|---|---|---|
| A | 脚手架：Vue 3 + Pinia + Tailwind 4 + shadcn-vue 风格 token + 三栏布局 + 深色主题 + LogDrawer + WsClient 封装 | ✅ |
| B | 核心画布：PreviewRenderer 初版 + Konva overlay + 选中/拖拽/resize/rotate + PropertiesPanel + LayerPanel reorder + 快捷键 + pan/wheel zoom | ✅ |
| C | 前端渲染器：字体 @font-face + /api/palette + TextLayout TS 镜像 + 效果族（stroke/shadow/glow 含自实现盒模糊） + 像素字体最近邻缩放 + 竖排实装 | ✅ |
| D | 端到端手测 checklist；Playwright snapshot 推 M7 | ✅ |

**M7 polish 清单（从 M4 + M5 聚合起来的推迟项）：**
- Playwright e2e / snapshot 测试台（双端像素级对比，含 `rendering-test/web-runner/`）
- WOFF2 字体 subset（思源黑 16 MB → ~200 KB 中文常用字）
- stroke / glow 的 pixelated 路径（当前 fill + shadow 已走最近邻）
- 竖排行首禁则（少见）
- "Simulate MC palette" UI toggle（用 PaletteLut.quantizeImageData 预览）
- WS 重连自动化（5/10/30s 阶梯）
- 限流 "5 次 / 1min 重复触发 → close 1008"
- 会话 token rotate 频率策略
- Paper `MapPalette.matchColor` forRemoval 真正替代（当前 M4-T2 PaletteLut 已让运行时绕开，仅 build-time PaletteGenerator 还用它）

---

## 2026-04-23 · M5-C5 像素字体最近邻缩放（前后端同步）· **M5-C 收尾**

**契约：** `docs/rendering.md §2.4`——若 `pixelated=true` 且字号 ≠ `nativeSize` 整数倍，必须用最近邻缩放避免字形 subpixel 模糊。

**启用条件：** `pixelated==true && nativeSize > 0 && targetSize % nativeSize == 0 && targetSize / nativeSize >= 2`。其他情况（含非整数倍、缩小、非像素字体）走原 `deriveFont` 路径。

**Java 端（`CanvasCompositor`）：**
- `shouldUseNearestNeighbor(reg, targetSize)` 判定
- `drawText` fill + shadow 循环里 branch 到 `drawPixelatedGlyph`；stroke / glow 暂不处理 pixelated
- `drawPixelatedGlyph`：
  1. `reg.derive(nativeSize)` 派生 native-size Font，FontMetrics 测 nativeChW
  2. 分配 `BufferedImage TYPE_INT_ARGB(nativeChW, nativeAscent+nativeDescent)` 做 mask
  3. mask Graphics 走 5 个 hints（关 AA），`drawString(ch, 0, nativeAscent)` 画字形
  4. 主画布临时 `KEY_INTERPOLATION = NEAREST_NEIGHBOR`
  5. `drawImage(mask, drawX, drawY, targetChW, targetH)` 缩放到 target 尺寸
  6. 恢复原 interpolation
- rotated glyph 路径同步：先 `translate + rotate π/2` 再 drawImage 到 `(-targetChW/2, ascent - targetSize/2)`

**前端（`PreviewRenderer`）：**
- `FONT_META` 表硬编码：`ark_pixel: { pixelated: true, nativeSize: 12 }` / `source_han_sans: { pixelated: false, nativeSize: 0 }`（与后端 `FontRegistry.Metadata` 对齐）
- `shouldUseNearestNeighbor` 判定同逻辑
- `drawPixelatedGlyph`：
  1. 切主 ctx font 到 nativeSpec 测 `nativeChW`；切 target 测 `targetChW`；恢复 ctx.font
  2. `document.createElement('canvas')` subcanvas，`imageSmoothingEnabled=false` + `textRendering=geometricPrecision` + native fontSpec + 白色 fillText 画 mask
  3. 主 ctx `imageSmoothingEnabled=false`（浏览器最近邻）→ `drawImage(sub, drawX, drawY, targetChW, targetH)`
  4. 恢复 imageSmoothingEnabled

**覆盖范围：**
- ✅ fill 层（元素主色）
- ✅ shadow 层（offset + shadow.color）
- ✗ stroke 层：仍走原 `ctx.strokeText` / Java `GlyphVector.getOutline`（outline 算法与像素字不好配合；M7 polish）
- ✗ glow 层：内部 mask 仍用 `deriveFont(targetSize)`；M7 polish

**M5-C 段落汇总：**

| # | 范围 | 状态 |
|---|---|---|
| C1 | 前端字体加载（Ark Pixel + 思源黑 @font-face）| ✅ |
| C2 | `/api/palette` 端点 + 前端 `PaletteLut` 镜像 | ✅ |
| C3 | `TextLayout` TS 镜像（横排完整）| ✅ |
| C4 | 前端效果族（stroke / shadow / glow）镜像 | ✅ |
| C5 | 像素字体最近邻缩放（前后端 fill + shadow）| ✅ |
| C6 | 后端竖排（Java TextLayout.layoutVertical + drawGlyph 旋转）| ✅ |

**M5-C 未做（推迟到 M5-D 或 M7）：**
- stroke / glow 的 pixelated 路径
- 竖排行首禁则
- WOFF2 字体 subset（加载体积优化）
- "Simulate MC palette" UI toggle（量化渲染预览）

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（+ drawPixelatedGlyph + shouldUseNearestNeighbor）
- `web/src/render/PreviewRenderer.ts`（+ FONT_META + drawPixelatedGlyph）

---

## 2026-04-23 · M5-C4 前端效果族镜像（stroke / shadow / glow）

**契约：** `docs/rendering.md §5` 效果族；前端 Canvas 2D 与后端 Java Graphics2D 的一致性要求。

**PreviewRenderer.drawText 按 §5.4 顺序：**
```
glow (自实现盒模糊)  →  shadow (fillText offset)  →  stroke (strokeText)  →  fill
```

**Glow（Canvas 2D 镜像 Java GlowRenderer）：**
- 算 glyph 外接矩形 + `radius + 1` padding（rotated glyph 按 `fontSize × fontSize` 方格外接）
- 新 `document.createElement('canvas')` local image（ARGB）
- 关 imageSmoothing + textRendering='geometricPrecision'；白色 fillText 画字形 mask
- 提取 alpha 通道 → `boxBlurHorizontal` + `boxBlurVertical`（分离核，算法 1:1 对应 Java）
- alpha 保留作透明度，RGB 替换 `glow.color`
- `ctx.drawImage(local, bboxX, bboxY)` 合成到主画布（SRC_OVER 自动）

**Shadow：**
- 每个 glyph 按 `(dx, dy)` offset 画一次，颜色 = `shadow.color`
- rotated glyph 绕 pivot 旋转后画（与 fill 层共用 `drawGlyphFill`）

**Stroke：**
- 浏览器原生 `ctx.strokeText`（rendering.md §5.1 前端约定）
- `lineWidth = width`、`lineJoin = round`、`lineCap = round`
- rotated glyph 同样走 `drawGlyphStroke`（translate + rotate + strokeText）

**与后端渲染的潜在差异（留 M5-D snapshot 测试发现）：**
- `ctx.strokeText` 的 outline 算法可能和 Java `GlyphVector.getOutline + BasicStroke` 有 subpixel 差异
- 盒模糊在两端实现上的整数除法精度：Java `sum / diameter`（整数除）vs JS `Math.floor(sum / diameter)`——理论上等价，需实测
- Canvas 2D 不关抗锯齿，即使 `textRendering='geometricPrecision'`——字符边缘可能仍有 sub-pixel；与 Java `TEXT_ANTIALIAS_OFF` 有差距。M5-D 的 `pixelated` 容忍度按 `< 0.5%` 定；超限时可能要引入前端"强制整数像素"或"Graphics2D 后端直出图片 → 前端 drawImage" 的替代方案

**关联文件：**
- `web/src/render/PreviewRenderer.ts`（重写 drawText + glow/shadow/stroke 全家桶）

---

## 2026-04-23 · M5-C3 + C6 TextLayout TS 镜像 + 前后端竖排实装

**M5-C3（前端 TextLayout 镜像）：**
- 新建 `web/src/render/TextLayout.ts`（~200 行），完整镜像 Java TextLayout
- 接口 `layoutText(t, measurer) → PositionedGlyph[]`；`CharMeasurer` 抽象测量函数
- `CanvasMeasurer` 适配 Canvas 2D `ctx.measureText`，带字符级缓存
- 横排分支 1:1 镜像 Java `layoutHorizontal`：硬换行 + softWrap（CJK/空白断点、无断点硬切）+ 行首禁则 + `align` 逐行 + 基线 `fontSize * 0.8`
- 新 PositionedGlyph 扩 `rotated?: boolean` 字段（竖排全角标点用）
- `PreviewRenderer.drawText` 切到逐字符 `fillText`，`rotated=true` 时绕锚点 `rotate(π/2)` 再画

**M5-C6（前后端竖排实装，rendering.md §3.3）：**

**Java 端：**
- `PositionedGlyph` 加 `rotated` 字段（保留 3 参构造器兼容历史调用）
- `TextLayout.layout()` 根据 `t.vertical()` 分流到 `layoutVertical`
- `layoutVertical`：
  - 字符从上到下；列从右到左（CJK 传统）
  - 列宽 = `fontSize × lineHeight`；每字占 `fontSize` 高
  - `align` 语义变顶/中/底对齐
  - `isRotatableVertical(c)`：`U+3000-U+303F` 符号标点 + `U+FF00-U+FFEF` 半全角形式
    旋转；其他不旋转（CJK 汉字本身方形）
  - 软换行按 box `h`；`\n` 起新列
- `CanvasCompositor.drawText` 重构：
  - 新增 `drawGlyph(g, pg, dx, dy)` 统一处理 rotated + offset；shadow/fill 层共用
  - 新增 `drawGlyphOutline(g, pg, font, frc)` 处理 stroke 效果的 rotated 版
  - `rotated=true` 时 `save → translate(pivot) → rotate(π/2) → drawString(-chW/2, ascent-fontSize/2) → restore`
  - 删除 `verticalWarned` / `warnVerticalOnce`（真正实装了，不再 WARN）

**前端 TS 同步（完全 1:1 镜像）：**
- `layoutVertical` 逻辑一致
- `isRotatableVertical` 字符范围一致
- `PreviewRenderer.drawText` 的 rotated 分支用 `ctx.translate + ctx.rotate + fillText` 相同锚点语义

**尚未做（polish）：**
- 竖排下**行首禁则**（少见，M7 补）
- 竖排行首/列首禁止标点（例如全角逗号不应出现在列首）

**glow 效果在竖排下的表现：** `GlowRenderer` 当前对 glyph 按 mask 画；rotated glyph 在 mask 里会以"原方向"绘制（因为 `lg.drawString(pg.ch, pg.x - bboxX, pg.baselineY - bboxY)` 没走 drawGlyph），视觉上 glow 带会偏向原横排方向。竖排下的 glow 矫正也留 polish。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java`（+ layoutVertical + rotated 字段）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（drawGlyph/drawGlyphOutline 统一出口）
- `web/src/render/TextLayout.ts`（新建，横排镜像 + 竖排实装）
- `web/src/render/PreviewRenderer.ts`（接入 TextLayout + rotated 分支）

---

## 2026-04-23 · M5-C2 /api/palette 端点 + 前端 PaletteLut 镜像

**后端 `WebServer`：**
- 新 `GET /api/palette` endpoint 直读 classpath 根的 `palette.json` 回 `application/json`
- 加 `Cache-Control: public, max-age=86400, immutable`（palette 只随 Paper 版本变，极少动）

**前端 `render/PaletteLut.ts`（~170 行）：**
- 完整镜像 Java `PaletteLut`：5-bit 量化 → 32³ byte LUT；CIE76 Lab 距离（sRGB D65 → XYZ → Lab）；D65 矩阵 & `LAB_EPSILON/KAPPA` 常量与 Java 完全相同
- 数据结构：`Uint8Array` LUT 占 32 KiB；`Float64Array` 预存 opaque palette 的 L/a/b
- 入口：
  - `loadFromEndpoint('/api/palette')` → fetch + 构造
  - `matchColor(r, g, b, a?) → byte`（alpha<128 返 `TRANSPARENT_INDEX`）
  - `getColor(index) → [r,g,b,a]`（反向）
  - `quantizeImageData(imgData)` → 对 Canvas ImageData 逐像素量化（M5-D snapshot + "Simulate MC" 预览用）
- 懒加载 singleton：`getPaletteLut()` 首次 fetch+构建，之后返回缓存 promise
- 构建成本：~8M 次 Lab 距离计算，单线程 1-3s（后端 Java 也差不多）；M7 polish 可迁 Web Worker

**为什么共享 palette：**
- 前后端同样的量化算法 → 同样的 byte[] → M5-D snapshot 可像素级比对
- 前端预览默认不量化（CSS 颜色更好看）；加 "Simulate MC palette" UI toggle 可按需预览"发游戏后的样子"

**M5-C2 未启用 UI 开关：** 只把基础设施打好；切换 PreviewRenderer 到量化路径留后续 commit（避免 M5-C 一次改动过多）。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（+ `/api/palette` endpoint）
- `web/src/render/PaletteLut.ts`（新建）

---

## 2026-04-23 · M5-C1 前端字体加载（双字体 @font-face 同源 TTF/OTF）

**契约：** `docs/rendering.md §2.1`「前后端必须从同一源 TTF 产出」——最稳的做法就是**直接共享**下载产物，不做额外 WOFF2 subset（文件偏大但首版可用）。

**Gradle：**
- 新增 `syncFontsToWeb` Copy task：`build/downloaded-fonts/*.ttf|otf → web/public/fonts/`
- `processResources` 加 `dependsOn(syncFontsToWeb)`，`./gradlew shadowJar` 一次把前后端字体同时准备好
- `.gitignore` 加 `web/public/fonts/`（产物不入 git）

**前端：**
- `style.css` 新 `@font-face`：
  - `ark_pixel` ← `/fonts/ark-pixel-12px-monospaced-zh_cn.ttf`（~4.9 MB TTF）
  - `source_han_sans` ← `/fonts/SourceHanSansSC-Regular.otf`（~16.5 MB OTF）
  - `font-display: block` 避免字体未到时渲染 fallback（首次画面略等，但字形保证对）
- `PreviewRenderer.drawText`：`ctx.font = "${fontSize}px ${fontFamily(fontId)}"`
  - `fontId` 直接作 CSS family 名（`ark_pixel` / `source_han_sans`）
  - 未知 id fallback 到 `ark_pixel`（与后端 `FontRegistry.DEFAULT_FONT_ID` 一致）
- `CanvasView.onMounted` 里 `document.fonts.ready.then(() => requestDraw())`——字体异步到位后重绘一次

**M7 polish 留项：**
- WOFF2 subset（用 fonttools pyftsubset 做中文常用字 ~2000 字切出来，~200 KB）
- 首屏 preload `<link rel="preload" as="font" ...>`
- 字体 hash 防缓存 mismatch

**关联文件：**
- `plugin/build.gradle.kts`（+ syncFontsToWeb）
- `.gitignore`
- `web/src/style.css`（+ @font-face）
- `web/src/render/PreviewRenderer.ts`（fontFamily mapping）
- `web/src/components/layout/CanvasView.vue`（document.fonts.ready 重绘）

---

## 2026-04-23 · M5-B6 LayerPanel reorder + B8 画布 pan/wheel zoom · **M5-B 收尾**

**B6 LayerPanel drag-and-drop 重排：**
- HTML5 Drag & Drop API 手写（不加 sortable 依赖）
- `draggable="true"` + `@dragstart/@dragover/@drop/@dragend`
- 拖动中 source row `opacity-50`，目标 row `ring-1 ring-[color:var(--ring)] ring-inset` 高亮
- drop 时 optimistic reorder 本地 + `ws.send('element.reorder', {elementId, index})`

**B8 画布交互：**
- **Ctrl/Cmd + wheel** → zoom（以鼠标光标位置为锚心）
  - 算 `oldZoom → newZoomClamped` 后，同步调 `scrollLeft/Top = (scrollL + mouseX) * ratio - mouseX`，使鼠标下的那个画布像素在缩放后仍在鼠标下
  - clamp 到 `[0.25, 4]`（与快捷键 ±/zoom 控件一致）
- **中键 drag / Alt+左键 drag** → pan（利用 section 的 overflow-auto，scrollLeft/Top 随鼠标位移反向滚）
- 非 Ctrl 时 wheel 默认行为保持（浏览器原生 scroll pan）
- 无 Space+drag（留 polish）

**M5-B 8 段汇总：**

| # | 范围 | 状态 |
|---|---|---|
| B1 | Canvas 2D PreviewRenderer（临时版）| ✅ |
| B2 | Konva overlay（Stage/Layer/Rect/Transformer）| ✅ |
| B3 | 元素选中（click/Esc/Layers 联动）| ✅ |
| B4 | 拖拽 + resize + rotate → `element.transform` | ✅ |
| B5 | PropertiesPanel 五组可编辑（Transform/Rect/Text/Effects/Layers）| ✅ |
| B6 | LayerPanel 显隐/锁/reorder drag | ✅ |
| B7 | 快捷键 Delete/undo/redo/arrow/shift+arrow/Esc | ✅ |
| B8 | Ctrl+wheel zoom（锚鼠标）+ 中键/Alt 拖 pan | ✅ |

**M5-B 未做的小项（留 polish 或后续 M5 段）：**
- Ctrl+D 复制、Ctrl+A 多选 / 多选框选（M5 末尾或 M7）
- 对齐辅助线（smart guides）；snap to grid / snap to other elements（polish）
- 元素拖拽时实时 rect 预览（现已用 Transformer 的边框够用）
- 属性面板对 TextElement 的字段为空时的 UX（目前 optional 字段默认已就位）

**M5-B 收尾实测入口（浏览器）：**
1. `/canvas edit → 两角 → /canvas confirm` 打开 probe URL
2. 点 Toolbar 的 Type/Square 按钮添加元素，或 Sparkles 应用 hello_world
3. Canvas 上看到元素；点击选中、拖拽移动、四角拉 resize、Transformer rotate handle
4. 右侧 Properties 编辑任意字段（效果族也可勾）→ 游戏内墙面实时响应
5. Delete 删除选中 / Ctrl+Z 撤销 / 方向键微调
6. Ctrl+wheel 缩放（锚鼠标）/ 中键拖 pan

**构建：** vite build 235ms；20 KB CSS + 307 KB JS（gzip 4.7 / 97 KB）。

**关联文件：**
- `web/src/components/layout/RightPanel.vue`（layer drag reorder）
- `web/src/components/layout/CanvasView.vue`（pan + wheel zoom）

---

## 2026-04-23 · M5-B5 属性面板可编辑 + B7 快捷键

**B5 PropertiesPanel 重写（`RightPanel.vue`）：**
- 分组折叠（`<details>` + summary）：**Transform / Rect / Text / Effects / Layers**
- **Transform** 组：x/y/w/h（number）+ rotation（select 仅 0/90/180/270）+ visible/locked（checkbox）
- **Rect** 专属：fill（开关 + color input）+ stroke（开关 → width number + color）
- **Text** 专属：text（textarea 多行）+ fontId（select ark_pixel/source_han_sans）+ fontSize + color + align + letterSpacing + lineHeight + vertical
- **Effects** 三开关：stroke / shadow / glow；每个打开后展开相应字段（shadow 的 dx/dy/color、glow 的 radius/color 等）
- 共用 `hc-input` / `hc-color` scoped CSS（手写，Tailwind 4 scoped style 不支持 `@apply`）
- **防抖策略：** number/text → `useDebounceFn 200ms`；boolean/color/select → 立即发（定型操作）
- **乐观更新：** 发 op 前先 mutate 本地 project.state，避免输入手感延迟；后端 state.patch 回来自然覆盖
- **Layers 列表：** visible/locked icon 变点击 toggle（发 element.update）

**B7 全局快捷键（`App.vue` useEventListener 顶层挂）：**
| 键 | 行为 |
|---|---|
| `Delete` / `Backspace` | 删除选中 → `element.delete` + 清选 |
| `Ctrl/Cmd + Z` | `undo` |
| `Ctrl/Cmd + Shift + Z` / `Ctrl + Y` | `redo` |
| `↑↓←→` | 移动选中 ±1 px |
| `Shift + ↑↓←→` | 移动选中 ±10 px |
| `Esc` | deselect（CanvasView 内已挂）|

- 跳过 `<input>` / `<textarea>` / `<select>` / `contenteditable` 焦点，避免输入时误触
- 箭头键走 `element.transform` op 而非 `element.update`——语义更贴"移动"

**Effects UI 与后端联动测试**（M4 已做完的效果族终于有 UI 入口）：
- 选一个 text 元素 → Effects 勾 glow → radius 默认 3 / color #33CCFF → 游戏里墙面该 text 立刻出蓝青光晕
- 勾 shadow → dx=dy=2 默认 → 黑色阴影偏移
- 勾 stroke → width=2 默认 → 字形轮廓描边

**暂未做（留 M5-B8 或 B6 后续）：**
- LayerPanel drag-and-drop 重排（element.reorder）—— 可选用 @vueuse 的 useSortable
- Ctrl+D 复制选中
- Ctrl+A 全选（需要多选支持，M5-B 不做多选）

**构建验证：** vite build 302ms；20 KB CSS + 305 KB JS（Konva ~230 KB 占大头）。

**关联文件：**
- `web/src/components/layout/RightPanel.vue`（重写，~350 行）
- `web/src/App.vue`（+ 全局快捷键）

---

## 2026-04-23 · M5-B1~B4 画布渲染 + Konva overlay + 选中 + transform op

**范围：** 让画布能"看见内容 + 选中 + 拖动/缩放"。B1~B4 一批落地；B5（属性面板可编辑）/ B6（图层可点）/ B7（快捷键）/ B8（pan+wheel）分后续批次。

**B1 PreviewRenderer（`render/PreviewRenderer.ts`，临时版）：**
- 纯函数 `renderProjectState(ctx, state)`
- 背景 fill → 按 z-order 遍历 `state.elements`
- rect：`fillRect` + 4 边 `fillRect` 手工画 stroke（与后端 CanvasCompositor 同策略）
- text：`ctx.font = "${size}px monospace"` + `fillText`（单行，M5-B 够用；M5-C 真对齐 Java 版）
- rotation：`translate(cx, cy) + rotate + translate(-cx, -cy)` 围绕 bbox 中心
- 关 `imageSmoothingEnabled` + `textRendering = 'geometricPrecision'`（rendering.md §4.3）
- **不做：** 调色板量化 / 多行 wrap / letterSpacing / 效果族 / 像素字体最近邻缩放 → M5-C 镜像 Java

**B2 Konva overlay（CanvasView.vue）：**
- `main.ts` 里 `app.use(VueKonva)`（3.x）
- `shims-vue-konva.d.ts` 给 Volar / vue-tsc 补 `GlobalComponents` 声明
- 布局：
  ```
  外层 div（shadow + ring；尺寸 = widthPx × heightPx × zoom；CSS transform scale 走 zoom）
    内层原生坐标容器（widthPx × heightPx）
      <canvas>（底层像素，由 PreviewRenderer 画）
      <v-stage>（上层 overlay，手势 hit）
        <v-layer>
          <v-rect v-for element>（隐形 hit area，offsetX/Y + x/y 让 rotation 绕中心）
          <v-transformer>（绑选中）
  ```

**B3 选中：**
- 点 v-rect → `onHitClick` → `ui.selectElement(id)`；`ev.cancelBubble = true` 防冒泡
- 点 stage 空白 → `onStageMouseDown` 判断 `!element-hit && type !== 'Shape'` → `selectElement(null)`
- `Esc` → deselect（`onKeyStroke('Escape')`）
- Layers 面板点 element 也联动（早已在 M5-A 写好）

**B4 拖拽 + resize 发 op：**
- `onDragEnd`：Konva node.x/y 是 center（因 offsetX/Y=w/2），换算回 bbox 的 `(x, y) = node.x() - w/2`；optimistic 本地更新 + `ws.send('element.transform', {elementId, x, y})`
- `onTransformEnd`：resize / rotate end
  - 读 `scaleX/scaleY`（resize 通过 scale 间接表达）
  - 换算出新 `w/h`；用 `node.scaleX(1); node.width(newW)` 重置 scale 防累乘
  - rotation 用 `snapRotation` 吸到 `{0, 90, 180, 270}`（协议限制）
  - 发 `element.transform {x, y, w, h, rotation}`

**Transformer 配置（`transformerConfig`）：**
- 8 个 anchor（四角 + 四边中点）
- `rotationSnaps: [0, 90, 180, 270]`
- anchor 颜色配深色主题（`#60a5fa` 蓝色边 + 深蓝 fill）

**响应式重绘：**
- `watch(() => project.state, requestDraw, { deep: true, immediate: true })`
- `requestAnimationFrame` 合并同 tick 多次 watch 回调；ArrayBuffer 重分配只在 canvas 尺寸变化时
- state.patch 回来后 ProjectState deep watch 触发重绘，Konva Rect props 响应式 diff，Transformer 保持 attach

**Node 25 + vue-tsc 兼容性妥协：**
`vue-tsc --noEmit` 在 Node 25 + TS 6 + Vue 3.5 下经常卡 10+ 分钟才出单个错误。`package.json` 的 `build` 临时降级为纯 `vite build`，`typecheck` 单独暴露给 IDE / CI。vite 构建产物验证：18 KB CSS + 292 KB JS（含 Konva）。

**关联文件：**
- `web/src/render/PreviewRenderer.ts`（新建）
- `web/src/components/layout/CanvasView.vue`（重写）
- `web/src/main.ts`（注册 VueKonva）
- `web/src/shims-vue-konva.d.ts`（新建）
- `web/package.json`（build 脚本拆 typecheck）

---

## 2026-04-23 · M5-A 前端脚手架（Vue 3 + Pinia + Tailwind 4 + shadcn-vue）

**范围：** 把 M1~M4 的原生 DOM probe 页面整体换成 Vue 3 + Pinia + Tailwind + 现代化 UI 壳（深色主题 / 三栏布局 / 侧边可折叠 / 日志抽屉）。为 M5-B 核心画布、M5-C 前端渲染器打下骨架。架构契约对应 `docs/architecture.md §2.2`。

**技术栈（用户拍板）：**
- Vue 3 Composition API + Pinia
- Tailwind CSS 4 + tw-animate-css + shadcn-vue 风格 CSS 变量（深/浅主题）
- 图标 lucide-vue-next；工具函数 @vueuse/core；Konva + vue-konva（M5-B 才用）
- 构建 Vite 8（已有），TS 6，vue-tsc 类型检查

**目录结构：**
```
web/src/
  main.ts                       # Vue app entry
  App.vue                       # 根组件：三栏布局
  style.css                     # Tailwind @import + HSL design tokens
  lib/utils.ts                  # cn() clsx + twMerge
  types/protocol.ts             # docs/protocol.md §7 的 TS 镜像
  stores/
    network.ts                  # WS 状态 + 日志流
    project.ts                  # ProjectState 本地镜像 + applyPatch(RFC 6902)
    ui.ts                       # 主题 / 折叠 / 选中 / zoom / 日志抽屉开关
  network/wsClient.ts           # WsClient 单例：connect/send/heartbeat/onMessage
  components/layout/
    TopBar.vue                  # 品牌 + 面板折叠 + 主题切换
    LeftTools.vue               # 工具栏（临时放 ping/paint/hello/undo/redo/add text|rect）
    CanvasView.vue              # 画布占位（M5-B 接入 Konva + Canvas 2D）
    RightPanel.vue              # Properties + Layers（只读展示，M5-B 可编辑）
    StatusBar.vue               # 状态灯 + sessionId + wallSize + version
    LogDrawer.vue               # 底部可折叠日志抽屉（WS 流全量）
```

**快捷键（`@vueuse/core onKeyStroke`）：**
- `Ctrl/Cmd + =` / `+`：zoom in
- `Ctrl/Cmd + -`：zoom out
- `Ctrl/Cmd + 0`：zoom reset

**WS 客户端迁移：**
- `WsClient` 类封装原 `main.ts` 里的 open/auth/heartbeat/reconnect token 全部逻辑
- `createWsClient()` 单例；`App.vue onMounted` 启动连接
- 消息分发：`ready / state.snapshot / state.patch / error / pong / ack` 分别更新 `useNetworkStore` / `useProjectStore`
- `__hk` 调试入口保留：`window.__hk.send("op", payload)`

**深色主题（shadcn-vue 约定）：**
- `:root` 与 `.dark` 各一套 HSL 变量（背景 / 前景 / card / primary / accent / border / ring 等）
- `@theme inline` 映射到 Tailwind token（`bg-card`、`text-muted-foreground` 等）
- `useUiStore.toggleTheme` 写 `<html>` 的 `.dark` class + `localStorage`
- 默认跟随 `prefers-color-scheme`，否则深色

**踩坑记账：**
1. **Node 25 + vite package.json 损坏**：之前 `kill -9` 打断 install 造成 `node_modules/vite/package.json` 字节损坏（首 200 字节全 null）；重装触发 ERR_INVALID_PACKAGE_CONFIG；`rm -rf node_modules package-lock.json && npm i` 一遍就清
2. **`vite.config.ts` 被 sed 清空**：`sed -i.bak` + 原地替换在某时序下把文件清零；改用 Write 整体重写

**构建验证：** `vite build` 成功；产物 17 KB CSS + 95 KB JS（gzip 4 KB + 36 KB）。

**M5-A 阶段性成果：** 旧 probe 功能（ping / paint / apply hello_world / undo / redo）全部迁到 Toolbar 按钮，端到端行为等价；网页视觉从"灰底朴素文档"升级到"Linear/Figma 式深色工具站"。M5-B 要做的编辑器交互（选中、拖拽、属性编辑、图层面板可点操作）留待下一轮。

**关联文件：**
- `web/package.json / tsconfig.json / vite.config.ts` 调整
- `web/src/**/*` 全新树（12 个新文件 + 替换 main.ts / index.html）

---

## 2026-04-22 · M4 polish 小修 · pristine state 回 placeholder + 前端 __hk 调试入口

**两个实测发现的小问题：**

### 1. undo 到底后一片空白（应回 placeholder）

**现象：** apply hello_world 后 undo 回到 ProjectState 初始态（elements 空、canvas.background=#FFFFFF），compositor 渲白色把 FrameDeployer 当初画的灰底 placeholder 盖没。

**根因设计：** placeholder 是 `/canvas confirm` 时 FrameDeployer 用 palette-first 直接写到 mapId 的灰底像素，**不在 ProjectState 里**——ProjectState 只管 elements + canvas。undo/redo 不可能把 state 外的 placeholder 恢复。

**修复：** `CanvasProjector.project` 第一步检查 `isPristine(state) = elements.isEmpty() && background==#FFFFFF`；若为真，走 `PlaceholderRenderer.render(mapIdx, total)` 渲 placeholder 替代 compositor。等价于"若 ProjectState 回到与 confirm 时构造时等价的初始态，视觉上也回到 confirm 时的 placeholder"。

**签名变更：** `CanvasProjector` 构造多吃一个 `PlaceholderRenderer`（从 `HikariCanvas` 主类复用现有那个 instance 注入）。

**边界情况：** 若用户显式设 `canvas.background=#FFFFFF` 且 elements 空，也会走 placeholder 分支——这是 pristine 语义的 corner case，可接受（"回到初始态即 placeholder"）。

### 2. 浏览器 console `ws is not defined`

**现象：** 我给的测试手册写 `ws.send(JSON.stringify(...))`，但 `ws` 是 `main.ts` 模块私有 `let`，不挂 window。

**修复：** `main.ts` 末尾暴露调试入口：
```ts
(window as unknown as Record<string, unknown>).__hk = {
    send,
    get ws(): WebSocket | null { return ws; },
    get authenticated(): boolean { return authenticated; },
};
```

用法：`__hk.send("element.add", { type: "text", props: {...} })` —— 直接走 `send()`，自增 client id + 加信封 wrap。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（+ PlaceholderRenderer 依赖 + pristine 分支）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（注入链调整）
- `web/src/main.ts`（`__hk` 调试入口）

---

## 2026-04-22 · M4-T10 发光 + T11 snapshot 测试台 + T12 5 fixture baseline

**M4 收尾三合一提交：发光效果完整实装 + Java runner 双端一致性 snapshot 测试台 + 5 个 fixture baseline 建立。**

### T10 GlowRenderer（发光，盒模糊自实现）

**算法（rendering.md §5.3，完全自实现不依赖系统高斯模糊）：**
1. 算 glyphs 整体外接矩形 + 四向 padding=`radius+1`
2. 分配 local `TYPE_INT_ARGB` 图（仅覆盖外接盒，不碰全画布）
3. 在 local 上关 AA 画字形 mask（白色或任意不透明色，只看 alpha）
4. 提取 alpha 通道 → 水平 + 垂直两次 `diameter = 2*radius+1` 均值滤波（分离核，等效盒模糊）
5. 着色：保留 alpha，RGB 全替换为 `glow.color`
6. `mainG.drawImage(local, bboxX, bboxY)`——Graphics2D SRC_OVER 自动 alpha 合成到主画布 RGB

**接入 compositor.drawText：** `glow → shadow → stroke → fill`（rendering.md §5.4 顺序）；glow 在最底层。

**性能：** per-element + per-glyph bbox；radius=5 128×128 约 65K 个像素 × 2 次滤波 = 微秒级。单个画布多个 glow text elements 各自独立 local image，无共享状态。

### T11 RendererSnapshotTest（JUnit 5）

**新增：**
- `plugin/src/test/java/moe/hikari/canvas/render/RendererSnapshotTest.java`
- `build.gradle.kts` 加 `testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")` + `useJUnitPlatform()` + `test.dependsOn(processResources)`（保证 palette.json + 字体在 test classpath 就绪）

**ProjectState Jackson 反序列化支持：**
- 加 `@JsonCreator` 构造器 `(version, canvas, elements, history)` + `@JsonProperty`
- 空字段容错（canvas/elements/history 为 null 都退默认）

**流程：**
1. fixture JSON → ObjectMapper → ProjectState
2. compositor.rasterize → BufferedImage
3. 写 actual.png 到 `build/test-results/snapshot/actual/`
4. 对比 expected.png（`src/test/resources/expected/`），逐像素 diff
5. diff 图写 `build/test-results/snapshot/diff/`（差异红点 + 灰阶背景便于目测）
6. diff ratio ≥ 0.5% → 测试失败；expected 不存在 → 首次建 baseline 告警不失败

**容忍度策略：** M4 统一 0.5%。rendering.md §8.2 区分 pixelated=0% / 常规 <0.5% / 大字号 <2% 留 M4.5 再细化。

### T12 5 个 fixture JSON + baseline PNG

`plugin/src/test/resources/fixtures/`：
| # | 文件 | 场景 |
|---|---|---|
| 01 | `01-hello-world.json` | hello_world 模板：2×2 湖蓝 + 白框 + `HELLO WORLD` Ark Pixel |
| 02 | `02-chinese-text.json` | `你好，世界！` 思源黑 28px + letterSpacing=2 + 多行硬换行 |
| 03 | `03-effects-stroke.json` | `STROKE` 48px 黄底 `#CC0033` 描边宽 3 |
| 04 | `04-effects-shadow.json` | `SHADOW` 42px 蓝字 `#888888` 阴影 dx=dy=3 |
| 05 | `05-effects-glow.json` | `GLOW` 56px 白字 `#33CCFF` 发光 radius=5 |

**首次运行自动创建 baseline：** `src/test/resources/expected/*.png` 5 张已入 git；**用户审核后再按需更新**（如果觉得某 fixture 的预期视觉不对，手工改 JSON 或模板参数重跑 test，actual 覆盖 expected，git commit）。

### Build Pipeline 整合

`./gradlew :plugin:test` 端到端验证：
- downloadFonts（首次抓思源黑 + Ark Pixel）
- generatePalette（导 248 色 JSON）
- processResources（palette + fonts + web 合并）
- compileJava + compileTestJava
- test → RendererSnapshotTest 5 参数化 case

**一次性实测**：第一次跑 baseline 建立（~3 分 10 秒，大部分时间在 downloadFonts + 首次 gradle 初始化），第二次 1 秒全通过。

### 关联文件

- `plugin/src/main/java/moe/hikari/canvas/render/GlowRenderer.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（glow 槽位接入）
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`（@JsonCreator）
- `plugin/src/test/java/moe/hikari/canvas/render/RendererSnapshotTest.java`（新建）
- `plugin/src/test/resources/fixtures/*.json`（5 个）
- `plugin/src/test/resources/expected/*.png`（5 个 baseline）
- `plugin/build.gradle.kts`（JUnit 5 deps + test task）

---

## 2026-04-22 · M4-T7+T8+T9 Rect stroke 结账 + TextElement.effects 描边 & 阴影

**T7 Rect fill + stroke：已在 M4-T4 compositor 重写时顺手完成**——`g.fillRect` + 手工四 `fillRect` 画 stroke（整数像素对齐、不受 `BasicStroke.drawRect` 的亚像素分摊影响）。T7 无额外代码改动，journal 记账 mark completed。

**T8/T9 TextElement.effects：**

**state 包新增三 record（对齐 protocol.md §7）：**
- `Shadow(int dx, int dy, String color)`
- `Glow(int radius, String color)`（T10 渲染用，字段先就位）
- `Effects(Stroke stroke, Shadow shadow, Glow glow)` 聚合容器；三字段均可 null；整体 null 代表无特效

`TextElement` 末尾追加 `Effects effects` 字段。

**EditSession 扩展：**
- `buildText` / `applyTextPatch` 新增 `effects` 字段处理，嵌套 `buildStroke/buildShadow/buildGlow`
- 校验阈值：`shadow.dx/dy ∈ [-128, 128]`、`glow.radius ∈ [0, 64]`、`stroke.width ∈ [0, 128]`（复用）
- 空 effects 对象 + 三字段全 null → 存为 null（避免无效载荷污染快照）

**CanvasCompositor.drawText 重写（按 rendering.md §5.4 顺序）：**
```
glow  (T10 占位)
 ↓
shadow (T9)   drawString offset (dx, dy) + shadow.color
 ↓
stroke (T8)   font.createGlyphVector + getOutline + BasicStroke.draw
 ↓
fill          drawString 正常 color
```
- shadow 直接 drawString 到 (dx, dy) 偏移处——rendering.md §5.2 要求"自实现不用 Graphics2D 内置 shadow"；drawString 等效 mask+offset，前后端可对齐
- stroke 用 `GlyphVector.getOutline(x, baselineY)` 取字形 Shape，`BasicStroke(width, CAP_ROUND, JOIN_ROUND)` 画路径
- 每层都遍历所有 PositionedGlyph，确保 per-char letterSpacing 与布局阶段一致

**DirtyRegion.of 扩展（effects 四向外扩）：**
- `shadow(dx, dy)` 单向外扩（dx>0 则右扩，dx<0 则左扩；dy 类似）
- `stroke.width` 两侧各扩 `width/2`（stroke 一半溢出字形轮廓外）
- `glow.radius` 四向全扩
- 扩张后再应用 rotation 规则（90/270 外接方形）
- RectElement.stroke **不**在外扩——那是 bbox 内部边框，不溢出

**M4-T10 glow：**
字段已就位（`Glow` record 已入 TextElement.effects），compositor 预留 "glow (T10 占位)" 注释；实际盒模糊自实现留 T10。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/Shadow.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/Glow.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/Effects.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/TextElement.java`（+ effects）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（buildText/applyTextPatch + 三 buildX helper + 3 条校验阈值）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（drawText 四层）
- `plugin/src/main/java/moe/hikari/canvas/render/DirtyRegion.java`（effects 四向外扩）

---

## 2026-04-22 · M4-T6 rotation 0/90/180/270

**范围：** 清掉 M3-T7 起的 rotation WARN 忽略项，让元素真按旋转度数渲染。仅接受 `{0, 90, 180, 270}`（EditSession 校验已拦截其他值）。

**实现：**
- `CanvasCompositor.rasterize` 在 element loop 里：`rotation != 0` 时 `save transform → g.rotate(toRadians(rot), cx, cy) → draw → g.setTransform(saved)`
- 旋转中心 = element bbox 中心 `(x + w/2, y + h/2)`，这样 rotation=180 后元素视觉仍在"同一位置"
- 去除 `rotationWarned` 字段与 `warnRotationOnce` 方法

**DirtyRegion.of(Element) 扩展：**
- `rotation ∈ {0, 180}`：bbox 不变
- `rotation ∈ {90, 270}`：外接 = 边长 `max(w,h)` 的方形，中心对齐原 bbox——保证旋转后溢出原 bbox 的像素也在脏矩形覆盖内、projector 能正确重绘

**语义记账：**
- bbox 本身不随 rotation 改变（用户选中操作、对齐计算都以原 bbox 为准）
- 内容可能"视觉上溢出" bbox——rect 旋 90°：方形保持；长方形 w×h=100×20 变 20×100（超出 20 px 高方向）。Dirty 用 max 方形接住

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（+ AffineTransform 旋转；- rotationWarned）
- `plugin/src/main/java/moe/hikari/canvas/render/DirtyRegion.java`（of(Element) 感知 rotation）

---

## 2026-04-22 · M4-T5 多行文本 + letterSpacing + lineHeight + 基线

**范围：** 横排文本完整排版。`docs/rendering.md §3.1-§3.5` 横排部分全覆盖；竖排（§3.3）按早先决策推迟到 M4.5/M7。

**TextElement 字段扩展（protocol.md §7 对齐）：**
- `letterSpacing: float`（px，可负；范围 -32..128）
- `lineHeight: float`（倍数；范围 0.5..4.0，默认 1.2）
- `vertical: boolean`（字段保留，渲染按 false + WARN）
- `fontId` 默认改为 `ark_pixel`（对应 `FontRegistry.DEFAULT_FONT_ID`）
- `fontSize` 默认 8 → 12（match Ark Pixel nativeSize）

**EditSession 同步：**
- `buildText` / `applyTextPatch` 新增三字段读取 + 校验
- 新增 `floatFieldOrDefault` / `floatValue` helper
- 新增 `validateLetterSpacing` / `validateLineHeight`

**新建 `render/TextLayout.java`（~180 行）：**
- `layout(TextElement, FontMetrics) → List<PositionedGlyph>` 主入口
- **硬换行**：`\n` split
- **软换行**：逐字符累宽，超 `w` 回溯到最近可断点
  - 断点规则：半/全角空格后、CJK 字符后（任意两字间可断）
  - 无可断点时硬截断（长 ASCII 单词）
  - 空白断点处空格丢弃（不出现在下一行行首）
- **行首禁则**：`）】」』。，、？！？；：)].,!?:;` 半全角并收；下一行首若是禁止标点，回溯到上一行末
- **基线**：`ascentPx = round(fontSize * 0.8)`（rendering.md §3.2 跨字体统一）
- **行距**：`lineHeightPx = round(fontSize * lineHeight)`
- **align**：每行独立算 lineWidth，按 left/center/right 计 startX
- **letterSpacing**：逐字符累加到 cursor（Graphics2D 不支持 per-char letter-spacing，只能逐字符 drawString）

**CanvasCompositor.drawText 重写：**
- 用 `TextLayout.layout` 产 `PositionedGlyph` 列表；逐字符 `g.drawString(ch, x, baselineY)`
- `vertical=true` 触发 `warnVerticalOnce`（WARN 一次，避免刷屏），按 horizontal 渲染
- `hello_world` 模板产生的 TextElement 构造更新为新签名 + `ark_pixel` fontId

**M4-T5 暂不做（推迟项）：**
- 竖排（文档 §3.3 已标推迟 M4.5/M7）
- 像素字体最近邻缩放（rendering.md §2.4）——Graphics2D `deriveFont` 在关抗锯齿情况下小字号可用；M4.5 polish 再评估是否需要专门绕过 AWT 内部 hinting

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/TextElement.java`（+ 3 字段）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（build/patch + validators）
- `plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（drawText 改用 TextLayout）

**先行承接文档决策：** `docs/rendering.md §3.3 / §3.4` 已在上一 commit 标注竖排推迟状态（commit `d77f763`）。

---

## 2026-04-22 · M4-T4 RgbaCanvas + Graphics2D；CanvasCompositor 重写

**范围：** 把 M3-T7 的 palette-first 直绘换成 `BufferedImage TYPE_INT_RGB` 大画布 + `Graphics2D` 渲染 + `PaletteLut` 量化切片。契约落地 `docs/rendering.md §1 / §4 / §6 / §7`。

**新管线（替换 M3-T7）：**
```
ProjectState
   → CanvasCompositor.rasterize(state) → BufferedImage(W*128, H*128, TYPE_INT_RGB)
   → CanvasCompositor.toPaletteSlice(img, mapIndex, widthMaps) → byte[128*128]
   → HikariCanvasRenderer.update(mapId, pixels)
   → Paper tick 自动 sync 给 viewer
```

**CanvasCompositor 重写要点：**
- 新构造签名：`(PaletteLut, FontRegistry, Logger)`——两依赖在主类注入
- `rasterize(state)`：分配整张大图 + 5 个 Graphics2D rendering hints（AA_OFF / TEXT_AA_OFF / RENDER_SPEED / FRACTIONALMETRICS_OFF / STROKE_PURE）→ 填背景 → 按 z-order 画 element
- `toPaletteSlice(img, idx, widthMaps)`：用 `img.getRGB(x, y, 128, 1, buf)` 按行取 RGB，逐像素 `paletteLut.matchColor` 量化
- Rect：`g.fillRect` 填充 + 手工四 `fillRect` 画 stroke（BasicStroke.drawRect 像素不对齐，保持 M3 整数像素风格）
- Text：`g.setFont(reg.derive(fontSize))` + `g.drawString` 单行（多行 / letterSpacing / lineHeight 留 M4-T5）
- `rotation != 0` 仍按 0 渲染 + log WARN 一次（M4-T6 真 rotation）

**CanvasProjector 调整：**
- 构造注入 `CanvasCompositor`（之前内部 `new`）
- `project(session, region)`：**一次 `rasterize` 大画布复用** + 对 region.coveredMapIndices 每格 `toPaletteSlice` 写到 canvasRenderer
- 优化点：脏矩形只省量化成本（rasterize 必须整张，元素可跨图）；2×2=65 KiB 大画布微秒级，8×4=1 MiB 毫秒级，10×10=6.5 MiB 十几毫秒级。M3 per-map 重复渲染的开销清除

**HikariCanvas 启动顺序调整：**
- `fontRegistry` 加载后 → **`paletteLut = PaletteLut.loadFromClasspath("/palette.json")`** 一次性构建 32 KiB LUT → `compositor` 注入 → `canvasProjector`
- palette.json 不存在（`./gradlew generatePalette` 没跑过）→ `IllegalStateException` 拒绝启动，错误消息提示

**M4-T4 暂不做（留后续 Tn）：**
- 多行文本 / `letterSpacing` / `lineHeight` → T5
- `rotation != 0` 真渲染 → T6
- BasicStroke 真用（曲线 stroke）→ T7
- effects 族（stroke / shadow / glow）→ T8 / T9 / T10

**副收获：** `CanvasCompositor` 代码从 220 行缩到 170 行（palette-first 手写 drawGlyph / fillRect / drawRect 全删），让位给 Graphics2D 原生 API + PaletteLut 量化，后续 T5-T10 的扩展都能直接在 Graphics2D 上叠。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（重写）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（构造签名 + 两阶段 rasterize/quantize）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（PaletteLut 构造 + 注入链）

---

## 2026-04-22 · M4-T3 FontRegistry + 内置双字体（Ark Pixel + 思源黑）

**范围：** 加载两枚内置字体到 `FontRegistry`，可选扫描 `plugins/HikariCanvas/fonts/` 外部字体。契约对应 `docs/rendering.md §2`。

**方案微调**（原方案 A → A'）：
- 原计划：Ark Pixel 入 git、思源黑 Gradle 下载
- 实测：我本地到 GitHub Releases 带宽不稳（Ark Pixel 34MB zip 多次 Premature EOF）
- 调整：**两字体都走 Gradle `downloadFonts`**。仓库继续纤瘦、代码机制统一、SHA-256 校验一致
- 差别仅是"首次 build 稍慢"，用户端跑通后就 pin SHA 常驻缓存

**Gradle `downloadFonts` 任务（`build.gradle.kts`）：**
- `FontSpec` data class：`displayId / url / destFileName / expectedSha256 / inZipEntryPattern`
- 两个 spec：
  - `source_han_sans` → Adobe raw.githubusercontent，单 OTF 16.5 MB，SHA pinned
  - `ark_pixel` → TakWolf Release zip 34.3 MB，解压提取 `*monospaced-zh_cn\.ttf`，SHA pinned
- 重试 3 次，每次 readTimeout 120s；抛 Premature EOF 就重下。手写 fallback 错误消息指导手动下载
- SHA-256 校验：非空值严格对比，空串仅 log（首次跑自动打印）
- `processResources.from(downloadedFontsDir) { include "*.ttf", "*.otf"; into "fonts" }` 合并到 jar `/fonts/` classpath 子目录

**SHA-256 pin 值（2026.02.27 Ark Pixel · Adobe release 分支思源黑）：**
- `SourceHanSansSC-Regular.otf`：`f1d8611151880c6c336aabeac4640ef434fa13cbfbf1ffe82d0a71b2a5637256`
- `ark-pixel-12px-monospaced-zh_cn.ttf`：`2fa78b40f74714b0092fa549eb6814b3efec5a729d020254968a270771ba5f75`

**新增 `FontRegistry.java`：**
- `loadBuiltIn()`：从 classpath `/fonts/*.ttf|.otf` 读两枚内置；AWT `Font.createFont(TRUETYPE_FONT)` 一把梭（OpenType 是 TrueType 超集，同常量即可）
- `loadExternal(Path)`：扫外部目录；文件名去扩展名作 fontId；同名覆盖内置
- 内置 fontId 约定：
  - `ark_pixel`（像素字体，`pixelated=true, nativeSize=12`）—— **默认 fontId**（跟 M3 hello_world BitmapFont 视觉平滑过渡）
  - `source_han_sans`（`pixelated=false`）
- `getOrDefault(fontId)`：未知 id 回退到 `DEFAULT_FONT_ID = "ark_pixel"`；连默认都没（downloadFonts 失败）返 null
- 线程：加载期有写入，稳态全局只读；`get/getOrDefault` 纯读
- `Metadata` record：`displayName / pixelated / nativeSize` —— M4-T4 渲染阶段用这几个字段决定 Graphics2D hint 与最近邻缩放

**HikariCanvas 主类 wiring：**
- `onEnable` 顺序：db/token/reaper → canvasRenderer → **fontRegistry loadBuiltIn + loadExternal(getDataFolder/fonts)** → projector → webServer
- 启动 log："FontRegistry: N font(s) ready"

**踩坑记账：**
1. Kotlin DSL 不自动 import `java.io.File` / `java.nio.file.*`；`java.io.File(...)` 完整路径写法 Kotlin 解析失败，必须 `import` 并用短名
2. 第一次 `Edit replace_all "java.io.File" → "File"` 把 import 行也改成 `import File`——单次 replace 要避开 import 行或用更具体的 old_string
3. GitHub Releases 34MB zip 连续 2 次 curl Premature EOF；Gradle 任务 3 次重试 + 每次 120s read timeout 实测第 2 次成功（约 9 分钟）

**后续（T4+）：** CanvasCompositor 重写时用 `fontRegistry.getOrDefault(t.fontId())` 替代 M3 硬编码的 `BitmapFont`；TextElement.fontId 未设时走 `ark_pixel` 保持与 hello_world 视觉一致。

**关联文件：**
- `plugin/build.gradle.kts`（+ 两字体下载任务 + SHA pin）
- `plugin/src/main/java/moe/hikari/canvas/render/FontRegistry.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（wiring）

---

## 2026-04-22 · M4-T2 PaletteLut（32³ Lab 距离）

**范围：** 启动期从 classpath `palette.json` 构建 32×32×32 byte LUT；CIE76 Lab 距离；O(1) 查询。替代 Paper `MapPalette.matchColor` 的 forRemoval 警告。契约对应 `docs/rendering.md §6.2-§6.4`。

**新增：**
- `plugin/src/main/java/moe/hikari/canvas/render/PaletteLut.java`

**API：**
- `PaletteLut.loadFromClasspath("/palette.json")` → 实例；Jackson 反序列化 → 构建 LUT
- `matchColor(r, g, b) → byte`：纯 RGB 查（O(1)）
- `matchColor(r, g, b, a) → byte`：alpha < 128 直接返 `TRANSPARENT_INDEX = 0`
- `getColor(byte index) → java.awt.Color`：反向查询
- 线程安全：LUT 构建后只读，所有查询纯函数

**算法细节：**
- **5-bit 量化**：RGB 各取高 5 位，LUT 索引 = `(r>>3)<<10 | (g>>3)<<5 | (b>>3)`；每档 step=8，用中心点 `r*8+4` 作代表色减量化误差
- **Lab 转换**：sRGB → linear（gamma 2.4 / 12.92 分段）→ XYZ（D65 矩阵）→ Lab（`f(t) = cbrt(t)` 分段）
- **距离度量**：Lab 欧氏距离平方（省去 sqrt，单调等价）；只在非透明 palette 项上匹配（pre-filter alpha < 128）
- **构建成本**：32³ × 248 ≈ 8M 距离计算，启动耗时预估 1-3s；LUT 大小 32 KiB 常驻

**透明处理（rendering.md §6.4）：**
- palette.json 里前 4 条 alpha=0（MC 保留透明索引）
- LUT 不收录透明项；matchColor 对 alpha<128 入参直接短路返 `TRANSPARENT_INDEX`
- 半透明（128..254）硬截断为不透明匹配——MC 地图原生不支持 alpha blend

**踩坑：** macOS 目录（Google Drive 同步？）在 `build/classes/` 下累积了 339 个 `*\s\d+.class` phantom file（如 `HikariCanvas 9.class`），让 Gradle MD5 hash 失败卡 `BUILD FAILED in 34m 30s`。`find -name "* *.class" -delete` 清掉后 2 秒编译通过。记在此以防复发。

**后续（T4+）：** `CanvasCompositor` 重写时用 `PaletteLut.matchColor` 替代现有 `MapPalette.matchColor` 调用；可以同步清掉 `@SuppressWarnings("removal")`。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/PaletteLut.java`（新建）

---

## 2026-04-22 · M4-T1 palette.json 构建期生成

**范围：** 从 Paper `MapPalette` 导出 MC 地图调色板全部 248 条到 `palette.json`，构建期一次性跑，结果合并进 shadow jar 的 classpath 根路径。M4-T2 的 `PaletteLut` 启动时用 `getResourceAsStream("/palette.json")` 读。契约对应 `docs/rendering.md §6.1`。

**新增：**
- `plugin/src/generator/java/moe/hikari/canvas/build/PaletteGenerator.java`：独立 sourceSet `generator` 下的 Java main class；调 `MapPalette.getColor((byte) i)` 探测 0..255；遇 `ArrayIndexOutOfBoundsException` 自动停（Paper 1.21.11 实际数组长度 = 248，历史版本曾 192/256，不假定固定）
- `plugin/build.gradle.kts`：
  - `sourceSets.create("generator")` 独立 sourceSet 避免循环依赖
  - `generatePalette` JavaExec task，`argumentProviders` 延迟到执行期 resolve Provider（直接传 `args(provider)` 会写出 literal `"map(map(...))"` 文件名）
  - `processResources.dependsOn(generatePalette)` + `sourceSets.main.resources.srcDir(generatedPaletteResources)` 让 shadow jar 自动打包

**关键技术点（踩坑 & 修复）：**
1. **循环依赖**：初版把 `PaletteGenerator` 放 `src/main/java`，`classes → processResources → generatePalette → classes`。修：独立 `generator` sourceSet 隔离构建期代码
2. **Provider 未 resolve**：`args(paletteJson.map { ... })` 把 Provider.toString() 传给 JavaExec 当字符串参数，导致文件名是 `"map(map(map(...))" `。修：`argumentProviders.add(CommandLineArgumentProvider { ... })`
3. **248 ≠ 256**：Paper 1.21.11 内部 colors 数组是 248；try/catch AIOOBE 动态探测长度

**输出 JSON 形态（行行可 diff，前 4 条是 MC 保留透明索引）：**
```json
[
  {"index": 0, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 1, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 2, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 3, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 4, "rgb": [89, 125, 39], "alpha": 255},
  ...
  {"index": 247, "rgb": [67, 88, 79], "alpha": 255}
]
```

**PaletteGenerator 在 shadow jar 中的命运：** 它只在 `generator` sourceSet 里，不会被打进运行时 jar（sourceSets.main 不引用它）。纯构建期工具，零运行时开销。

**关联文件：**
- `plugin/src/generator/java/moe/hikari/canvas/build/PaletteGenerator.java`（新建）
- `plugin/build.gradle.kts`

---

## 2026-04-22 · M4 渲染引擎启动 · 范围与字体分发决策

**范围**（12 个子任务 ≈ 13.5 工作日 ≈ 3 周）：
1. T1 `palette.json` 构建期生成
2. T2 `PaletteLut`（32³ Lab 距离查表）
3. T3 `FontRegistry` + 内置 Ark Pixel + 思源黑
4. T4 `RgbaCanvas` + Graphics2D；重写 `CanvasCompositor`
5. T5 多行文本 + 基线 + align + letterSpacing + lineHeight
6. T6 rotation 0/90/180/270
7. T7 Rect 真 fill + drawRect + BasicStroke
8. T8 描边效果（glyph outline）
9. T9 阴影效果（mask offset）
10. T10 **发光效果**（盒模糊自实现，用户确认 M4 一步到位）
11. T11 Java runner snapshot 测试台
12. T12 5 个 fixture + expected PNG

**关键决策（用户拍板）：**
- 调色板距离：**CIE76 Lab**（非 RGB 欧氏）
- 内置字体：**Ark Pixel 12px + 思源黑体 SC Regular 两枚**（均 SIL OFL）
- 前端 Playwright snapshot：**不做**（留 M5 Vue UI 一起）
- `BitmapFont`：继续用于 `PlaceholderRenderer`，`TextElement` 改走 TTF 管线
- 效果：描边 + 阴影 + **发光**全做，不推后

**字体分发（方案 A）：**
- Ark Pixel 12px（~200KB）直接入 git `plugin/src/main/resources/fonts/`
- 思源黑体 SC Regular（~15MB）Gradle `downloadFonts` 任务构建期抓取；`build/downloaded-fonts/` 由 `.gitignore` 排除；SHA-256 校验；`processResources` 合并入 shadow jar

**文档更新：**
- `CLAUDE.md`："字体"条款明确两字体来源 + 分发路径；里程碑条加 ✅ 与进行中标记
- `docs/rendering.md §2.1.1` 新增"分发策略（M4 定稿 · 方案 A）"小节 + Gradle 任务轮廓

**下一步：** 开 T1 palette.json 构建期生成。

---

## 2026-04-22 · WS 心跳修复（fix/keepalive）

**问题（M3 集成测试暴露）：**
- `Idle timeout expired: 30005/30000 ms` ——Jetty WS 默认 idleTimeout 30s，用户停手 30 秒 WS 就被服务端踢
- 前端未实现协议 §1 要求的"30s ping/pong"，完全依赖 Jetty 默认值

**修复（两层兜底）：**

1. **后端** `WebServer`：`cfg.jetty.modifyWebSocketServletFactory(factory -> factory.setIdleTimeout(Duration.ofSeconds(60)))`；把 Jetty idleTimeout 从 30s 放宽到 60s
2. **前端** `main.ts`：auth 成功后 `startHeartbeat()` 每 20s 发一次 `op: ping`；WS `close` 时 `stopHeartbeat()` 清定时器；心跳帧绕过 `print/send` 避免 log 刷屏

**为什么 60s + 20s 而不是默认 30s + 25s 心跳：**
- 20s 间隔内发送 3 次都不应超 60s（允许单次网络抖动丢心跳）
- 真正的 session 超时（5 分钟 ws-reconnect grace / 30 分钟 idle）由 `SessionReaper` 掌管，和 Jetty 层 timeout 分工明确

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `web/src/main.ts`

---

## 2026-04-21 · M3-T13 commit 写入真实 project_json

**范围：** `/canvas commit` 时把 session 的权威 `ProjectState` 经 Jackson 序列化写入 `sign_records.project_json`，替代占位 `"{}"`。契约对应 `docs/data-model.md sign_records.project_json` + `docs/architecture.md §8.2`。

**改动：**

- `CanvasCommand.JSON` 静态 ObjectMapper 增 `NON_NULL` 包含策略——与 WebServer 同策略；hollow rect 的 `fill=null` 不写字段
- `CanvasCommand.runCommit` 新增 `ProjectState projectState = s.projectState();` 快照（必须在 `sessionManager.commit` 之前，commit 会 `forget` session）
- `insertSignRecord(...)` 签名扩一个参数 `ProjectState`；序列化失败回退 `"{}"` + WARNING 日志
- ProjectState 为 null 时（异常路径）兜底写 `"{}"`

**JSON 形态示例**（commit 一面 2×2 hello_world 墙面）：

```json
{
  "version": 3,
  "canvas": {"widthMaps": 2, "heightMaps": 2, "background": "#4A90E2"},
  "elements": [
    {"type": "rect", "id": "e-...", "x": 8, "y": 8, "w": 240, "h": 240,
     "rotation": 0, "locked": false, "visible": true,
     "stroke": {"width": 2, "color": "#FFFFFF"}},
    {"type": "text", "id": "e-...", "x": 0, "y": 121, "w": 256, "h": 14,
     "rotation": 0, "locked": false, "visible": true,
     "text": "HELLO WORLD", "fontId": "bitmap", "fontSize": 14,
     "color": "#FFFFFF", "align": "center"}
  ],
  "history": {"undoDepth": 0, "redoDepth": 0}
}
```

**M4 承接：** M4 的 `project.load` op（{@code docs/protocol.md §5.2}）会反序列化本字段重构 ProjectState，进入二次编辑。ProjectSnapshot record 的 Jackson 兼容性已在本次 commit 一并验证（FIELD 可见性 + 多态）。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`

---

## 2026-04-21 · M3-T12 template.apply + hello_world 硬编码模板

**范围：** `docs/protocol.md §5.4` `template.apply` op 骨架 + 1 个硬编码模板 `hello_world`（本项目方案 α 的 M3 承诺范围）。正规 YAML 模板系统留 M6。

**EditSession 扩展：**
- 新 synchronized 方法 `applyTemplate(templateId, params)`
- M3 只识别 `"hello_world"`；其他 templateId → `INVALID_PAYLOAD "unknown template: X (M3 only supports hello_world)"`
- 流程：pre-snapshot → clear elements → 改背景色 → 插入模板 elements → commitHistory → bump version → 返回 `OkSnapshot + fullCanvas`

**hello_world 视觉定稿（画布自适应）：**

| 层 | 参数 |
|---|---|
| 背景 | `#4A90E2`（湖蓝） |
| 外框 | RectElement：`(margin, margin, w-2*margin, h-2*margin)`；margin=8px；空心 stroke=2px 白色 |
| 文字 | TextElement：`HELLO WORLD` 居中白色；字号 `scale = max(1, usable / (11 * 6))`；BitmapFont 5×7 scale=1 时单字 6px 步进 |

**WebServer：**
- op 分发表加 `"template.apply"`
- handler：`stringOrNull(payload.templateId) + mapOrEmpty(payload.params)` → `es.applyTemplate(...)` → `OkSnapshot` 路径走 pushSnapshot + fullCanvas throttler.submit

**前端 probe UI 加 3 个按钮**（方便 M3 端到端手测）：
- `Apply hello_world template` → `template.apply { templateId: "hello_world" }`
- `Undo` → `undo {}`
- `Redo` → `redo {}`
- 同时给 `send(op, payload?)` 扩出第二个参数支持带 payload 发送

**未决项（M6 承接）：**
- YAML template spec 解析 / 模板库多选
- 参数化模板（`params.{name, line_color}` 等）
- 模板预览缩略图

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（+ applyTemplate + buildHelloWorld）
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`（+ `clearElements`）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（op 分发）
- `web/index.html` + `web/src/main.ts`（3 个测试按钮）

---

## 2026-04-21 · M3-T11 undo / redo / history.mark

**范围：** `docs/protocol.md §5.5` 的历史类 op 全部落地。每会话 16 步环形历史栈，undo/redo 全量下行 `state.snapshot`。

**四条决策（实施前拍板）：**
1. **历史存全量快照**（非逆向 patch）：`ProjectSnapshot(canvas, List<Element>, label)`
2. **undo/redo 下行 `state.snapshot`**（非 `state.patch`）：跨度跳变用全量更稳
3. **`history.mark` 只贴标签**：push 一个带 label 的 snapshot 到 past，不 clear future
4. **脏矩形 = full canvas**：undo/redo 后全图重绘

**新增：**

- `state/ProjectSnapshot.java`：record `{ canvas, elements, label }`；`elements` 经 `List.copyOf` 变只读副本，不与 live state 共享
- `ProjectState.restore(ProjectSnapshot)`：整体替换 canvas + elements；version 不回滚（由调用方 bump）

**EditSession 扩展：**

- `past` / `future` 两个 `ArrayDeque<ProjectSnapshot>`；`MAX_HISTORY = 16`
- `OpResult` 新增 `OkSnapshot(version, dirty)` 变体；WebServer 按类型 pattern-match 决定下行 patch 还是 snapshot
- `undo()`：past 空 → `INVALID_PAYLOAD "nothing to undo"`；否则 push 当前到 future、从 past 取最近的 restore、bump version、返回 `OkSnapshot + fullCanvas`
- `redo()`：对称
- `historyMark(label)`：label 非空 + ≤ 64 字符；push `(current state, label)` 到 past；**不 clear future**（mark 是标签、不是新分支）；返回 `Ok` 空 patch 只 bump version
- `commitHistory(preSnapshot)`：5 个原有 mutation op 成功路径都调它——past push 后 `future.clear()`（标准 undo 语义）

**`OkSnapshot` 下行流程（WebServer）：**
1. `ack { version }` 对 client id
2. `state.snapshot` 全量 push（`Envelope.of("state.snapshot", s-N, { projectState })`）
3. dirty 交给 `ProjectionThrottler`（走 5fps 上限 + union 合并）

**T10 互动：** undo/redo 的 full-canvas dirty 会触发 throttler 把整张画布重绘推一次；与普通 op 的局部脏矩形可以 union 合并到同一 flush 窗口里。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectSnapshot.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`（+ `restore`）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（历史栈 + undo/redo/mark + OkSnapshot）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（op 新增 + OkSnapshot 分支）

---

## 2026-04-21 · M3-T10 帧率节流（投影 5fps + 输入 40/2s）

**范围：** `docs/architecture.md §5.1` + `docs/protocol.md §9` 双层节流落地。

**新增：**

- `render/ProjectionThrottler.java`
  - per-session bucket `{ pending: DirtyRegion, lastProjectAt, flushTask }`
  - `submit(sid, region)`：距上次 flush ≥ 200ms 立即走；否则 `union` 进 pending 并 `runTaskLaterAsynchronously` 调尾帧
  - `union` coalesce 同窗口内的连续 op（10 次快改只投 1~2 次）
  - `discardSession` 取消 pending task、清状态

- `session/SessionRateLimiter.java`
  - 40 msg / 2s 固定窗口计数器（≈ 20 msg/s；协议 §9 阈值）
  - `allow(sid) → boolean`：超限时返 false，WebServer 返 `RATE_LIMITED` 错
  - 协议 §9 的"5 次 / 1min 重复触发 → close 1008"留 M7

**SessionManager forget hooks：**
- 新增 `addForgetHook(Consumer<String>)` + 在 `forget()` 末尾调用
- 主插件注册两条 hook：`throttler::discardSession` + `rateLimiter::discardSession`
- 长运行内存不再随会话数单调增长；hook 异常互不影响（try/catch 逐条执行）

**WebServer 改动：**
- `dispatchEditOp` 第一步就查 `rateLimiter.allow`；超限直接返 `RATE_LIMITED`
- 成功后 `throttler.submit(sid, dirty)` 代替直接 `canvasProjector.project`
- 移除不再用的 `canvasProjector` 字段与构造参数；throttler 内部持有

**前端 100ms 防抖说明：**
- 当前 probe UI 只有 `ping / paint` 按钮，没有真正的编辑 op 发送路径
- 100ms 输入防抖是 M5 编辑器 UI 才能真正验证的东西——现在先把**协议层和后端上限**定准，M5 前端代码自然会按该契约写
- 协议契约：前端**应**在输入中做 100ms 防抖，但**即使不做**，后端 20 msg/s 硬限流兜底；两层独立、缺一不塌

**线程模型：**
- throttler 的尾帧 flush 走 `runTaskLaterAsynchronously`（async 线程），与 compositor 纯函数 + canvasRenderer `ConcurrentMap` 一致，不必切主线程
- rateLimiter 的 `synchronized(bucket)` 锁粒度 = per-session，彼此独立

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/ProjectionThrottler.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionRateLimiter.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`（forget hooks）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（wiring + hooks）

---

## 2026-04-21 · M3-T9 per-viewer 同步（架构已满足）

**结论：** M3-T7/T8 引入的 `HikariCanvasRenderer` + `CanvasProjector` 组合已经天然提供 per-viewer 同步，T9 无代码改动。

**机制：**
- `HikariCanvasRenderer` 是挂在每张 `MapView` 上的 `MapRenderer(contextual=false)`
- Paper 每 tick 对 **每个看得见该 MapView 的 viewer** 调 `render(map, canvas, player)`，从 `pixelsByMapId` 拉最新像素写入 `MapCanvas`
- Paper 负责 diff + 下发 `ClientboundMapItemDataPacket` 到该 viewer

所以"会话外的第三个玩家路过墙面"→ Paper 发现他看到这张 map → 调 render → 拿到当前最新 pixels → 自动同步。无论谁看，数据源都是同一份 `ConcurrentMap<mapId, byte[]>`。

**M3 阶段 `super(false)` = non-contextual：** 所有 viewer 共享同一张 canvas，省 CPU。真正的 per-player 差异化（例如编辑辅助线只对编辑者可见、观众看纯画布）需要切到 `super(true)` 并维护 per-player buffer——留 M7 视需要再做。

**验证留给 M3 总集成实测**（两名玩家同一墙面编辑与旁观）。

**关联文件：** 无改动。

---

## 2026-04-21 · M3-T7 + T8 脏矩形差分 + 多图拼接渲染

**范围：** 编辑 op 成功后把 `ProjectState` 投影到游戏内墙面——受影响 `mapIds` 重绘 palette 像素并推到 `HikariCanvasRenderer`，下一 tick Paper 自动 sync 给所有 viewer。T8 "多图拼接"在 `CanvasCompositor` 的 per-map 合成内自然实现（详见下文），与 T7 一并结清。

**新增文件：**

| 文件 | 职责 |
|---|---|
| `render/DirtyRegion.java` | 画布坐标矩形；`of(element)` / `fullCanvas(state)` / `union` / `coveredMapIndices(w,h)` |
| `render/CanvasCompositor.java` | 纯函数；`compose(state, mapIndex) → byte[128*128]`；palette 缓存 |
| `render/CanvasProjector.java` | `project(session, region)` → 遍历受影响 mapIndex 调 compositor 写 canvasRenderer |
| `render/BitmapFont.java`（扩展） | +17 大写字母 + 14 标点；现覆盖 A-Z / 0-9 / `. , : ; ! ? - _ + = / ( )` |

**多图拼接（T8 实现要点）：** `CanvasCompositor.compose` 对每张 map 独立走：
1. `offsetX = mapCol * 128`、`offsetY = mapRow * 128`（该 map 在画布的左上角像素坐标）
2. 对每个可见 element，坐标转换为 local 域：`localX = e.x() - offsetX`、`localY = e.y() - offsetY`
3. `drawRect` / `drawText` 统一把超出 `[0, 128)` 的像素 clip 掉

这样一个 x=120 / w=30 的 rect 在 widthMaps=2 画布上：map0 localX=120 绘 `x∈[120,128)`、map1 localX=-8 绘 `x∈[0,22)`——拼起来还是一个完整 30px 矩形，无缝跨图。

**脏矩形规则（EditSession 侧扩展）：**

| op | region |
|---|---|
| `element.add` | 新元素 bbox |
| `element.update` | 旧 bbox ∪ 新 bbox |
| `element.delete` | 被删元素 bbox |
| `element.reorder` | 被移动元素 bbox（z-order 变化触发该区域下层元素重合成）|
| `element.transform` | = update 路径（旧 ∪ 新）|
| `canvas.background` | 整个画布 |
| `canvas.resize` (no-op) | `null`（无像素变化）|

`EditSession.OpResult.Ok` 扩字段 `DirtyRegion dirty`，WebServer 在 ack + pushPatch 之后按 region 调 `canvasProjector.project`。

**M3-T7 主动简化（documented）：**
- `rotation != 0` 的元素按 `rotation=0` 渲染（log WARN 一次），真 rotation 留 M4
- Text wrap 不实装，单行渲染；元素自身 `w` 仅用于 `align` 中心/右对齐偏移计算
- `fontId` 当前只识别 `"bitmap"`（默认），M4 TTF 系统接入再扩
- fontSize 离散映射：`scale = max(1, round(fontSize/7))`（7→1×、14→2×、21→3×）
- `rect.stroke.width` 自动 cap 到 `min(w,h)/2` 防溢出

**调色板策略：** 用 Bukkit `MapPalette.matchColor(int,int,int)`（256 色全映射）+ hex string → byte 缓存。Paper 1.21.11 这个 API 和它的 `Color` 重载都标 `@Deprecated(forRemoval=true)`，但官方没给替代且 `CLAUDE.md` 锁 Paper 1.21；`@SuppressWarnings("removal")` 收口，M4 `docs/rendering.md` 真正 LUT 接入后整类替代。

**线程模型：** compositor 纯函数，projector 写 `ConcurrentMap`，`canvasRenderer.render()` 由 Paper 主线程每 tick 调用——三层互不打架，WS 事件处理线程直接调 projector 即可，不必切主线程。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/DirtyRegion.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`（扩字表）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（OpResult.Ok 扩 dirty 字段）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（dispatchEditOp 接 projector）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（main wiring）

---

## 2026-04-21 · M3-T6 element.* + canvas.* op 族

**范围：** WS 上行编辑 op 全部接入 `EditSession`，落到 `ProjectState`，产出 `state.patch` 推回前端。契约对应 `docs/protocol.md §5.3 / §5.4`。

**新增 `state/EditSession.java`：**
- `OpResult` sealed 结果类型：`Ok(StatePatch) / Error(code, message)`
- 7 个 `apply*` 方法（全部 `synchronized(this)`，Jetty 线程池下并发安全）：
  - `addElement(type, props, afterId)` — 生成 `"e-<uuid>"`；默认参数兜底；支持 `afterId=null` 追加尾部
  - `updateElement(elementId, patch)` — 字段级部分更新；all-or-nothing 校验；不变量失败回滚
  - `deleteElement(elementId)`
  - `reorderElement(elementId, newIndex)` — 越界 clamp；same-position 空 ops + bump version
  - `transformElement(elementId, x?, y?, w?, h?, rotation?)` — 等价于 update 五字段子集
  - `resizeCanvas(widthMaps, heightMaps)` — **M3 仅接受 no-op 同尺寸**；差值返回 `POOL_EXHAUSTED`（真动态扩缩容 M7 再做）
  - `setBackground(color)`
- Validator 集中在文件内静态方法：
  - color `^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$`
  - rotation ∈ {0, 90, 180, 270}
  - text length ≤ 256 / fontSize 1..512 / stroke.width 0..128 / w/h 1..10000 / x/y ±10000
  - align ∈ {left, center, right}
- Rect 不变式：`fill == null && (stroke == null || stroke.width == 0)` 拒绝（至少一种填充方式）
- 失败路径统一通过内部 `ValidationException(code, msg)` 抛出 → apply 方法外层 catch 转成 `OpResult.Error`

**patch 路径约定（RFC 6902）：**
- `element.add` → `add /elements/{idx} {element}`
- `element.update` → 逐字段 `replace /elements/{idx}/{field} {value}`；**`value == null` 改用 `remove`** 以规避 `NON_NULL` 序列化丢 value 字段违反 RFC 的坑
- `element.delete` → `remove /elements/{idx}`
- `element.reorder` → `remove /elements/{from}` + `add /elements/{to} {element}`
- `element.transform` → 走 updateElement 路径，逐变字段 `replace`
- `canvas.background` → `replace /canvas/background {color}`
- `canvas.resize`（no-op 情况）→ 空 ops 列表 + bump version

**WebServer 分发（单个 switch 入栈）：**
- 新 helper `dispatchEditOp(ctx, in, sid)`：
  1. 取出 Session.editSession；不存在返回 `SESSION_CLOSED`
  2. 提取 payload Map（类型错误统一 `INVALID_PAYLOAD`）
  3. switch op → 调 EditSession 对应方法
  4. `Ok`：先发 `ack { version }`（对 client id），再 `pushPatch`（s-N id；空 ops 跳过推送）
  5. `Error`：发 `error { code, message }`（对 client id）
- switch 表达式统一 `yield OpResult.Error(...)` 代替 early `return`（Java switch expression 限制）
- 保留 M1 `paint` demo 通道作为 T6 阶段回归测试；M3 收尾（T12/T13）再清

**Session 接入：**
- Session 新增 `editSession` 字段 + public accessor
- `SessionManager.confirm`：`SELECTING → ISSUED` 转移时构造 `EditSession(projectState)` 一起挂到 session 上
- 随 session forget 一起消亡，无需额外清理

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（新建，~380 行）
- `plugin/src/main/java/moe/hikari/canvas/session/Session.java`
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T5 state.snapshot / state.patch 推送基建

**范围：** 服务端主动推送 {`state.snapshot`, `state.patch`} 的基建。契约对应 `docs/protocol.md §5.2`。

**新增：**
- `state/PatchOp.java`：RFC 6902 最小子集 `{ op, path, value }` record + `add/replace/remove` 工厂
- `state/StatePatch.java`：`{ version: long, ops: List<PatchOp> }` record
- `state/StatePatchBuilder.java`：累积式构建器（非线程安全，只在 SessionManager 锁内使用）

**WebServer 扩展：**
- `ConcurrentMap<String, WsContext> wsBySession`：session → 活跃 WS 连接
- `AtomicLong serverIdSeq`：服务端推送 `s-<N>` id 单调源
- 绑定点：`handleAuth` 成功后 put；`onClose` 用 `remove(k, v)` 原子 CAS 避免 race 把新连接抹掉
- 新增 public API：
  - `pushSnapshot(sessionId, ProjectState) → boolean`
  - `pushPatch(sessionId, StatePatch) → boolean`
  - 均返回 `false` 当 session 没有活跃 WS 连接
- 序列化：`NON_NULL` 策略让 `PatchOp.value == null`（remove op）自动省略

**M3-T5 scope 仅基建**：T6 element op 族接入时才开始真实发 patch；T5 本身不变更既有通道行为。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/PatchOp.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/StatePatch.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/StatePatchBuilder.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T4 ProjectState 模型 + Element 接口族

**范围：** 服务端权威工程状态的数据模型落地。契约对应 `docs/protocol.md §7`。

**新增 `moe.hikari.canvas.state` 包：**
- `Element.java`：`sealed interface` + Jackson `@JsonTypeInfo(property="type")` 多态；permits `TextElement / RectElement`
- `TextElement.java`：record，M3 字段 = `id/x/y/w/h/rotation/locked/visible/text/fontId/fontSize/color/align`；effects / lineHeight / letterSpacing / vertical 留 M4
- `RectElement.java`：record，`fill`（可 null 表示空心）+ `stroke`（可 null 表示纯填充）
- `Stroke.java`：record `{ width, color }`，未来 M4 text outline 复用
- `ProjectState.java`：可变 class（不是 record——需要 mutator）；字段 `version / canvas / elements / history`；`@JsonAutoDetect(fieldVisibility=ANY, getterVisibility=NONE)` 让字段直接映射到 JSON 键名
  - 嵌套 record：`Canvas(widthMaps, heightMaps, background)`、`History(undoDepth, redoDepth)`
  - Java-side 无前缀 accessor + 显式 mutator (`addElement / replaceElementAt / removeElementAt / moveElement / bumpVersion / canvas(Canvas) / history(History)`)
  - 线程约束：只允许 `SessionManager.synchronized` 段内 mutator，`elements()` 返回 unmodifiable view

**Session 接入：**
- `Session` 新增 `projectState` 字段 + `projectState()` accessor + package-private `projectState(ProjectState)` mutator
- `SessionManager.confirm`：`SELECTING → ISSUED` 转移时实例化 `new ProjectState(wall.width(), wall.height())`，默认背景 `#FFFFFF`
- `WebServer.handleAuth`：`ready` payload 的 `projectState` 字段直接序列化 `session.projectState()` 对象（不再手写 Map）

**关联文件：**
- 6 个新文件（`state/*.java`）
- `plugin/src/main/java/moe/hikari/canvas/session/Session.java`
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T3 Token rotate（断线重连基建）

**范围：** WS auth 成功后立即 rotate 新 token 给前端，供后续 WS 断线重连使用。契约对应 `docs/security.md §2.2`、`docs/protocol.md §11`。

**后端：**
- `TokenService` 新增 `rotate(playerUuid, playerName, sessionId)`：与 `issue` 语义相同但审计事件为 `AUTH_ROTATED`（区分「首次签发 vs rotate 签发」）；两方法共享 `issueInternal` 私有实现
- `WebServer.handleAuth` 在 `markActive` 后立即 `rotate` 并把新 token 随 `ready` payload 回发：`payload.reconnectToken: string`
- 审计日志里 `AUTH_ROTATED` 只记 `token_sha256`，原文不落盘

**前端：**
- `ReadyPayload` 接口扩 `reconnectToken` 字段
- `handleReady` 存 token 到 `sessionStorage["hikari-canvas:reconnect-token"]`（tab-scoped，关闭失效）
- 页面加载：URL `?token=` 优先（新会话走链接打开），回退 sessionStorage（同 tab 刷新 / 重连）
- token 原文绝不进 console.log，仅 log length

**M3-T3 scope 仅基建**：真正的"WS 断线自动重连循环"（5/10/30 秒阶梯，protocol.md §3.4）留给后续 UX 迭代。目前只保证 rotate token 能发、能收、能存。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `web/src/main.ts`

---

## 2026-04-21 · M3-T2 WS 会话超时回收（SessionReaper）

**范围：** 把 M2 T10/T11 记入 journal 的遗留项「没有主动 schedule 的回收 task」补上。契约对应 `docs/architecture.md §3.1`。

**新增：**
- `session/SessionReaper.java`：主线程 `BukkitScheduler.runTaskTimer`，30 秒扫一次
- `SessionManager.ExpiredSession` record + `synchronized collectExpired(now, issuedTimeout, wsGrace, activeIdle)`：只做决策不做副作用，返回待 cancel 列表

**三条超时规则：**

| 状态 | 条件 | reason | 默认阈值 |
|---|---|---|---|
| `ISSUED` | `now - createdAt > issuedTimeout` | `"issued-timeout"` | 15 min（与 token TTL 一致）|
| `ACTIVE` + 断连 | `now - wsDisconnectedAt > wsGrace` | `"ws-reconnect-timeout"` | 5 min |
| `ACTIVE` + 在线 | `now - lastActivityAt > activeIdle` | `"idle-timeout"` | 30 min |
| `SELECTING` / `CLOSING` | 不超时 | — | — |

**回收流程**（复用 `/canvas cancel` 的模式）：
```
1. 先快照 session.wall().world()（cancel 会把 session forget）
2. sessionManager.cancel(id, reason) → 归还池 + 释放锁 + forget
3. frameDeployer.removeForSession(id, world) → 删 preview 物品框
4. log.info 记录 id / reason / 删框数
```

**主线程约束：** `cancel` 触发 `MapPool.returnToPool`，`removeForSession` 扫 world 实体，两者都只能主线程。用 `runTaskTimer` 不用 `runTaskTimerAsynchronously`。

**阈值来源：** 全部硬编码常量，TODO 待 M7 config.yml 接入后让运维可调。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/session/SessionReaper.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`

---

## 2026-04-21 · M3-T1 WallResolver 补 frame-space air 校验

**范围：** 修 M2 残留 "旁边有草/花时 ItemFrame 闪掉" bug。M3 启动第一步，清 M2 残债。

**改动：**
- `WallResolver.FailReason` 新增 `FRAME_SPACE_BLOCKED`（bbox 前一格被非 air 方块占）
- `resolve()` 内循环：检查 `wall.getRelative(face1)` 必须 `isAir()`；否则返回失败
- 顺序在 `BLOCK_NOT_SOLID` 之后、`OCCUPIED` 之前
- 拒绝一切非 air 方块：短草、花、水、熔岩、雪层等；只有纯 `AIR / CAVE_AIR / VOID_AIR` 通过

**理由：** M2 实测发现若墙面前一格是短草，frame `spawn()` 即便成功，客户端判非法并立即 despawn（"闪一下"）。`OCCUPIED` 只查 ItemFrame 碰撞，漏了非 frame 的占位方块。

**下游兼容：** `WandListener` 和 `CanvasCommand` 直接 print `reason().name()`，加新枚举值不破坏现有代码。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/deploy/WallResolver.java`

---

## 2026-04-21 · M2-T12 集成测试 + 运行时调试 + **M2 完成**

**范围：** 端到端实测联调，沿途修 5 个运行时 bug；M2 所有契约要求的功能就位。`PROPOSAL §6` M2 行从"2 周"更新为"已完成（2026-04-21）"。

**实测达成的完整闭环：**
```
MC 客户端 /canvas edit + 左右键两角 + /canvas confirm
  → 聊天栏收到可点击 editor URL
浏览器点击 URL（MC 弹确认 → 打开本机 8877/?token=...）
  → 页面加载 (plugin serve) → main.ts 取 token → WS auth
  → 收到 ready（带 projectState.canvas.WxH）→ 状态条变绿 + 按钮 enable
浏览器点 Paint
  → WS paint op → HikariCanvas.paintAllSessionMaps
  → 对活跃会话的所有 mapIds 填红色像素 → canvasRenderer.update(...)
  → Paper 下一 tick 自动把 canvas sync 给所有 viewer
  → 游戏里整面墙同时变红
```

**沿途修的 5 个运行时 bug（按发现顺序）：**

1. **命令注册但 Brigadier 报 "unknown"**
   - 症状：`/canvas edit` 等命令显示"未知或不完整的命令"
   - 原因：T11 加了 `.requires("canvas.edit")` 权限检查；玩家默认无此权限，Brigadier 在 tab 补全/执行时直接把命令藏起
   - 修：`paper-plugin.yml` 新增 `permissions:` 字段，`canvas.edit / wand / commit / use` `default: true`；`canvas.admin*` `default: op`

2. **ItemFrame 挂上立刻消失（"闪一下就没了"）**
   - 症状：`/canvas confirm` 后 6 个 frame 瞬间出现又瞬间消失；log 里 `valid=true` 看上去正常
   - 原因：`setFixed(true)` + `setVisible(false)` 在 Paper 1.21 的 `world.spawn(..., consumer)` 内部被 apply 时，客户端 entity desync——add packet 和 metadata update 顺序问题
   - 修：M2 阶段**暂不设 INVISIBLE / FIXED**，保护完全交给 `FrameProtectionListener`。M7 polish 时再回来精调（可能需要 scheduled 1-tick-later 设 fixed）

3. **Placeholder 像素"闪一下被空白覆盖"**
   - 症状：frame 留住了，但 Placeholder 图像只显示一瞬间，立即被 MC 自己 tick 成空白
   - 原因：Bukkit `MapView` 有 per-tick 渲染机制——即使清空 `getRenderers()`，Paper 每 tick 仍然把空 canvas sync 给 viewer，覆盖我们直接 push 的 `ClientboundMapItemDataPacket`
   - 修：**不再对抗 Paper tick**，而是**合作**——新增共享 {@link moe.hikari.canvas.render.HikariCanvasRenderer}（`super(false)` non-contextual），`MapPool` 为每张 MapView `addRenderer`；外部像素改动调 `canvasRenderer.update(mapId, pixels)`，Paper tick 时 renderer 会把像素写进 canvas，自然走官方 sync 通道
   - 关联：`MapCanvas.setPixel` 内部有 dirty flag，重复写相同值不产生 packet，CPU 可接受

4. **HikariCanvasRenderer 被 FrameDeployer 清掉**
   - 症状：bug #3 修完后仍然不生效，log 里看 MapView 当前 renderer 数 = 0
   - 原因：`FrameDeployer.deploy` 里残留了一段"清 renderer"的 debug 代码，把 `MapPool.initialize/expand` 刚装上的 `HikariCanvasRenderer` 又清掉了
   - 修：删除 FrameDeployer 里的清 renderer 代码；MapPool 负责唯一的 renderer 生命周期

5. **浏览器 Paint 点了没反应**
   - 症状：所有 frame OK、Placeholder 稳定显示、浏览器 auth 成功，但点 Paint 按钮墙不变红；后端 log 显示 `painted 0 held maps`
   - 原因：paintHandler 仍是 M1 demo 的"遍历在线玩家主手 `filled_map` 涂红"——M2 玩家 confirm 完手里已无 map item
   - 修：改为 `paintAllSessionMaps`：遍历 `SessionManager.liveSessionIds()`、对每个活跃会话的全部 `mapIds` 填红像素 → `canvasRenderer.update`

**运行时 cleanup（commit 里一并做）：**
- 去掉 FrameDeployer 里 T12 期间加的 spawn log / +1 tick 诊断 log
- 恢复 `FrameProtectionListener` 注册（T12 debug 阶段为排除嫌疑临时注释掉了）
- 修 `SessionManager.confirm` 的 `requireState` 抛异常问题：已 ISSUED 状态二次 confirm 现在返回 `ConfirmResult.NotReady` 而不是抛 IllegalStateException

**另两个已知问题暂未修（不阻塞 M2 验收）：**
- **旁边有草/花时 frame 仍然闪掉消失**：WallResolver 只校验墙面方块，没校验**墙面前方一格（frame 将要占据的格子）**是空气。短草/花/雪层等会让 MC 判 frame 不合法 → despawn。修法：WallResolver 遍历 bbox 时顺便 check `getRelative(facing).getType() == AIR`，非空气 → `OCCUPIED` 或新增 `FRAME_SPACE_BLOCKED` 错误码。留给 M2 polish 小修或 M3 集成时补
- **靠侧墙的某格 frame 不显示**：可能是客户端视距 / chunk tracker / 相邻实体碰撞的边缘情况。留 M7 单独调查

**未做（按 M2 契约范围有意留给后续）：**
- **Token rotate**（security.md §2.2）— 断线重连 M3 再做
- **定时回收 task**（WS 断连 5min 宽限 + auth 5s 超时 + idle disconnect）— M7
- **限流**（security.md §2.4）— M7
- **Origin 校验** — M7
- **`/canvas cleanup` 真正数据回收**（现 stub）— M7 `/canvas fsck`

**里程碑总结（M1→M2）：**

| 任务 | 关键点 |
|---|---|
| T1 改名 /hc → /canvas | 代码跟进 契约 |
| T2 SQLite + HikariCP + JDBI | 5 表 schema v1 |
| T3 TokenService | SecureRandom + CAS 原子 consume + SHA-256 审计 |
| T4 MapPool | **核心机制**，SQLite 幂等 upsert + 不变式自愈 |
| T5 WallResolver | 纯算法 + 7 种失败码 sealed 建模 |
| T6 Wand + SELECTING | 命令/物品双入口；PlayerInteract listener |
| T7 SessionManager | 汇合点，状态机 SELECTING→ISSUED→ACTIVE→CLOSING |
| T8 FrameDeployer + 保护 | 挂框 + PDC + HangingBreak/BlockBreak 拦截 |
| T9 PlaceholderRenderer | 手写 5×7 位图字表，浅灰底 + 水印 + "N/M" |
| T10 WS auth/ready | 预握手 peek + 首帧 consume + rotate 留坑 |
| T11 /canvas 命令族 | 5 sealed ConfirmResult pattern-match + sign_records 入库 + 顺手修 staticFiles fat-jar |
| T12 集成测试 | 5 运行时 bug + HikariCanvasRenderer 设计收敛 |

**M2 工期：** 2026-04-21 立项契约修订 + 当日完成 12 个任务 —— 1 天完成（原估 2 周）。

**关联文件：** `PROPOSAL.md`（§6 M2 行状态更新）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java`、`plugin/src/main/java/moe/hikari/canvas/pool/MapPool.java`、`plugin/src/main/java/moe/hikari/canvas/render/HikariCanvasRenderer.java`（新）、`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`、`plugin/src/main/resources/paper-plugin.yml`、`docs/journal.md`

---

## 2026-04-21 · M2-T11 /canvas 命令族完整实装 + 端到端闭环

**范围：** 把 M2 所有模块（TokenService / MapPool / SessionManager / FrameDeployer / PlaceholderRenderer / WebServer）串成完整业务链路；同时做一个顺手的修复：Javalin 7 静态资源 fat-jar discovery bug（T7a 留的 TODO）。

**后端改动：**

`command/CanvasCommand.java` 完整重写：
- 构造签名从 `(plugin, mapPacketSender, sessionManager)` 重新设计成 `(plugin, sessionManager, frameDeployer, tokenService, mapPool, database, editorUrlTemplate)` —— 去掉 `mapPacketSender`，因为 M2 正式命令路径不直接发包
- 正式子命令全部接入权限 `.requires()`（contract `docs/security.md §6`）：
  - `edit` / `wand` / `confirm` / `cancel` → `canvas.edit`
  - `commit` → `canvas.commit`
  - `stats` / `cleanup` → `canvas.admin`
- `/canvas confirm`：调 `SessionManager.confirm` pattern-match 5 种结果（Ok / NotReady / WallFailed / WallOccupied / PoolExhausted），Ok 时走「`FrameDeployer.deploy` → `TokenService.issue` → 发可点击 Adventure Component URL（`ClickEvent.openUrl` + `HoverEvent.showText`）→ 移除 wand」；FrameDeployer 抛异常时立即 `SessionManager.cancel("deploy-failed")` 回滚
- `/canvas commit`：commit 前**快照 `wall / mapIds / ownerUuid / ownerName`** 到局部变量（因为 `SessionManager.commit` 会 forget session）；然后 `SessionManager.commit → FrameDeployer.promote → INSERT sign_records`
- `/canvas cancel` 增强：commit 前 snapshot `wall.world()`，确保 cancel 后 `FrameDeployer.removeForSession` 有 world 参照
- `/canvas stats`：`MapPool.stats() + SessionManager.size() + TokenService.activeCount()` 一行输出
- `/canvas cleanup`：**M2 阶段 stub**，只打印 `soft-deleted sign_records` 数量；实际数据回收 + map 归还留给 M7 的 `/canvas fsck` 实装
- 删除 M1/M2 过渡 DEPRECATED 子命令：`give` / `paint` / `placeholder`（T1 起保留至今）
- 删除 T5 demo 用的 `MapPacketSender` / `PlaceholderRenderer` 字段（FrameDeployer 内部持有，主命令路径不再直接用）

**sign_records 写入：** M2 阶段 `project_json` 存 `"{}"` 占位、`template_id` / `template_version` 为 null。M3 真正的编辑协议族会逐步填充 `project_json`。

**顺手修复 Javalin 7 staticFiles fat-jar bug（T7a TODO）：**
`web/WebServer.java` 用两条显式 `Endpoint` 替代 `cfg.staticFiles.add`：
```java
GET /                → serveClasspath("web/index.html")
GET /assets/{file}   → serveClasspath("web/assets/" + file)   // 防路径穿越检查
```
`serveClasspath` 用 `ClassLoader.getResourceAsStream` 直接流式返回；MIME 按扩展名手动映射（html / js / css / json / woff2 / svg / png）。单 jar 部署在 M2 正式可用；Vite dev 模式也仍然能跑（跨源 WS 到 8877）。

**前端 (`web/src/main.ts`) 改 auth-first 流程：**
- 页面加载时从 `window.location.search` 取 `token`
- 自动 `connect(token)` → `open → sendAuth(token)` → `onmessage` 等 `op=ready`
- `ready` 到来：`handleReady` 从 `payload.projectState.canvas.widthMaps/heightMaps` 显示"wall W×H"，enable ping / paint 按钮
- 没 token 时显示明确提示"Start via /canvas confirm in Minecraft"（而不是静默失败）
- 增加 `#status` 彩色状态条（pending/ready/err 三色）
- **token 原文不进 log**（security.md §2.2 要求）
- TypeScript 类型收窄陷阱：`instanceof` narrowing 在 `throw` 外不会传播到 outer `const`；加一组局部变量 `logEl / statusEl / pingBtn / paintBtn` 做类型假设

**`web/index.html`**：增加 `#status` 状态条，初始 `disabled` 两个按钮

**主类接入（HikariCanvas.java）：** 构造 CanvasCommand 时传 `(this, sessionManager, frameDeployer, tokenService, mapPool, database, "http://127.0.0.1:8877/?token={token}")`

**端到端链路（T12 要实测的完整流程）：**
```
MC 客户端                                       浏览器
   ├─ /canvas edit                                 
   ├─ 左键 / 右键点两角                             
   ├─ /canvas confirm                              
   │   ├─ SessionManager.confirm: SELECTING→ISSUED
   │   ├─ MapPool.reserve N 张
   │   ├─ FrameDeployer.deploy (挂框 + 填 Placeholder)
   │   ├─ TokenService.issue → token
   │   └─ 发可点击聊天消息 [Open editor: http://...?token=...]
   ├─ 点击聊天链接  ──────────────────────► GET / → index.html
   │                                       GET /assets/*.js → 加载
   │                                       JS 从 URL 取 token → WS /ws
   │                                       → send {op:auth, token}
   │                                       ← {op:ready, projectState:{wall W×H}}
   │                                       UI 变绿，按钮 enabled
   ├─ (编辑侧 M3 才做，M2 编辑器只能 ping/paint)
   └─ /canvas commit                              
       ├─ SessionManager.commit: ACTIVE→CLOSING→CLOSED
       ├─ MapPool.promoteToPermanent → refill FREE
       ├─ FrameDeployer.promote (PDC session→sign)
       └─ INSERT sign_records row
```

**未做（按 M2 范围有意留到后续）：**
- 定时回收 task（WS 断连 5min 宽限 / auth 5s 超时 / idle disconnect）—— M7 polish
- token rotate（WS 握手成功后重发新 token）—— M3 / M7
- 限流（security.md §2.4 单玩家 / 全局）—— M7
- Origin 校验（公网部署时开）—— M7
- `/canvas cleanup` 实际数据回收 + `/canvas fsck` —— M7
- `project_json` 真实内容 —— M3

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- 前端 `npm run build` 通过（index.html 2.29 KB / index-CZM-HPIQ.js 3.28 KB）
- 端到端真实闭环测试留到 T12

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`web/index.html`、`web/src/main.ts`、`docs/journal.md`

---

## 2026-04-21 · M2-T10 WS 预握手 + auth/ready 协议

**范围：** 按 `docs/protocol.md §3.1 / §3.2` 落地会话进入协议：HTTP 预握手校验 token 并返回会话元信息；WS /ws 首帧必须是 `op=auth`；`ready` 响应带 `projectState` 占位。

**改动：**
- `WebServer.java` 重大扩展：
  - 构造器签名：`(log, host, port, tokenService, sessionManager, serverVersion, paintHandler)`
  - `GET /api/session/{token}` → `tokenService.peek(token)`（不消耗），200 返回 session 元信息 / 401 AUTH_FAILED / 409 SESSION_CLOSED
  - WS auth-first 状态机：`ctx.attribute("sessionId")` 是否绑定判定阶段；未绑定时只接受 `op=auth`，其它 op → `AUTH_FAILED` + close 4001
  - `handleAuth`：`tokenService.consume(token)` → `sessionManager.markActive(sessionId)` → `ctx.attribute("sessionId", id)` → 发 `{op:"ready", payload:{sessionId, serverVersion, protocolVersion, projectState}}`
  - 已认证路径：每条消息 `sessionManager.touch(sid)` 更新 `lastActivityAt`；`ping`/`pong` 保留；`paint` demo 通道保留待 T11 删
  - `onClose`：若绑定了 sessionId，调 `sessionManager.markDisconnected` 记时间戳（T11/M7 的定时回收 task 会扫这个）
- `HikariCanvas.java`：构造 WebServer 时传入 tokenService / sessionManager / serverVersion（从 `getPluginMeta().getVersion()` 取）

**Javalin 7 API 踩坑：**
- `cfg.routes.addEndpoint(Endpoint endpoint)` 接受 `io.javalin.router.Endpoint`——**直接 `new Endpoint(HandlerType, String, Handler)`**；没有 `Endpoint.create(...).builder().build()` 链式 API
- `WsCloseStatus` 是 **enum**（NORMAL_CLOSURE / POLICY_VIOLATION 等预定义常量），**没有**自定义 4001 的 enum 常量；应该用 `ctx.closeSession(int code, String reason)` 重载直接传 `4001`
- 对应 JavalinException 规避以 `javap -public` 查真实 class 签名最快

**`ready` 响应的 `projectState` 占位结构（`protocol.md §7` 子集）：**
```json
{
  "version": 0,
  "canvas": { "widthMaps": W, "heightMaps": H, "background": "#CCCCCC" },
  "elements": [],
  "history": { "undoDepth": 0, "redoDepth": 0 }
}
```
M3 做增量编辑时会让 `projectState` 真正带元素。

**未做（留给后续）：**
- **Token rotate**（security.md §2.2 要求）：WS 握手成功后重发新 token 给前端用于断线重连。M2 不做——WS 断开后玩家只能重新 `/canvas edit` + confirm 签发新 token。留给 M3 + M7 polish
- **Auth 超时**（protocol.md §3.1 要求 5 秒）：当前用 Javalin/Jetty 默认 30 秒 idle timeout。T11 或 M7 polish 时加自定义 watchdog
- **Origin 校验**（security.md §3.1）：默认关闭；公网部署时再开，M2 只跑本地
- **per-WS-connection 限流**（security.md §2.4 + protocol.md §9）：20 msg/s / 40 突发 / 5 次拒绝 close 1008——延到 M7
- **断线重连 5min 宽限自动回收**：`markDisconnected` 已记时间戳，但定时扫描回收 task 未实装；T11 或 M7 做

**验证：** `./gradlew :plugin:compileJava` 通过。真实端到端验证要等 T11 把 `/canvas confirm` 接到 TokenService.issue 后才能跑。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T8 FrameDeployer + FrameProtectionListener

**范围：** 物品框的批量挂装 / 会话终止时移除 / commit 升级 permanent，以及保护 listener。契约 `docs/architecture.md §7.2` 与 `docs/security.md §5`。T11 命令族实装时把这些 hook 串到 SessionManager 状态转移上。

**文件：**
- `deploy/FrameDeployer.java`（新）：
  - `deploy(Session, wall, mapIds)`：按 bbox 逐格 spawn ItemFrame，`INVISIBLE + FIXED + rotation NONE + mapItem`；PDC 打 `session / slot / role=preview`；slot 编号 = `row × width + col`，`row=0` 为最上排；deploy 后立即 push Placeholder 像素**只发会话玩家**（per-viewer 同步走 M3）
  - `removeForSession(sessionId, world)`：扫世界 ItemFrame 按 PDC session 删除；cancel 路径用
  - `promote(sessionId, signId, world)`：preview → permanent，改 PDC（`sessionKey` 移除 / `signKey` 设 / `roleKey = "permanent"`），**不重建实体** = frame 保持可见
  - `isProtectedFrame(ItemFrame)`：保护 listener 判定入口（PDC 里有 session 或 sign key）
  - 4 个 NamespacedKey（`session / sign / slot / role`）全部在 `hikari_canvas` namespace 下
- `deploy/FrameProtectionListener.java`（新）：
  - `HangingBreakEvent`：实体原因（爆炸/物理失联）一律拒绝；玩家攻击由下一个 handler 处理
  - `HangingBreakByEntityEvent`：拒绝，除非破坏者是持 `canvas.admin.force-break` 权限的玩家
  - `PlayerInteractEntityEvent`：右键改内容拒绝
  - `BlockBreakEvent`：扫 4 个水平相邻格，若有 attached 在本方块的 protected frame 则取消；同样 `canvas.admin.force-break` bypass。M2 只支持垂直墙面，故只扫水平 4 方向；M4+ 放宽后补 UP/DOWN
- `HikariCanvas.java`：`onEnable` 构造 `FrameDeployer(this, new PlaceholderRenderer(), mapPacketSender)` + 注册 `FrameProtectionListener`

**关键取舍：**
- **per-viewer 同步局限**：Minecraft MapData 是 per-player 推送，不是全局广播。T8 只给会话玩家发 Placeholder；其他在线玩家看到的是 MC 客户端本地缓存（新地图会是空白 / 灰）。完整 per-viewer 差分同步是 M3 `protocol.md §state.patch` 的事
- **promote 不重建实体**：仅改 PDC `sessionKey → signKey`，物品框保持原位可见；好处是不会有"空白闪一帧"
- **slot 编号 vs 视觉方向**：按 bbox 坐标最小值递增命名（row=0 为最高、col=0 靠 minZ/minX），不对齐"玩家视角左/右"。T11 + Placeholder 对齐后可能要调；M2 demo 阶段保持一致即可
- **没写 deploy 的回滚**：若某一 slot spawn 失败，之前成功的 frame 不会自动删除；M2 demo 阶段接受这种局部损坏，T12 集成测试时再看

**与其他模块的对接清单（T11 将完成）：**
- SessionManager.confirm 成功后：调 `frameDeployer.deploy(session, wall, mapIds)`
- SessionManager.cancel 成功后：调 `frameDeployer.removeForSession(sessionId, wall.world())`
- SessionManager.commit 成功后：调 `frameDeployer.promote(sessionId, signId, world)`

**验证：**
- `./gradlew :plugin:shadowJar` 通过；Paper 1.21 API 里 `setFacingDirection(face, force=true)` / `ItemFrame#setVisible(false)` / `setFixed(true)` 全部编译通过
- 运行时验证留给 T11（T11 完整命令族串好后自然触发）或 T12 集成

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/FrameProtectionListener.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T6 Canvas Wand + SELECTING 玩家交互入口

**范围：** 玩家侧的墙面选区 UX 全部就绪。命令入口（`/canvas edit`）+ Wand 物品入口双通道、左/右键点击记 pos1/pos2、聊天栏实时 echo 坐标与墙面预览、`/canvas cancel` 放弃。此时 T7 的 SessionManager 状态机第一次有了真正的驱动源。

**文件：**
- `deploy/CanvasWand.java`（新）：
  - `Material.GOLDEN_SHOVEL` + Adventure 名称/lore
  - PDC key `hikari_canvas:wand_owner` 存玩家 UUID 字符串；`isWandFor` 校验防别人捡到误触
  - `removeAllFrom(player, plugin)` 工具方法（confirm/cancel 后把 wand 从背包收回）
- `session/WandListener.java`（新，Bukkit Listener）：
  - 只处理 `LEFT_CLICK_BLOCK` / `RIGHT_CLICK_BLOCK`；用 `getHand() == HAND` 去重避免副手触发
  - 触发条件：**玩家持 wand** 或 **玩家已在 SELECTING 态**。否则不干预，正常建筑行为不受影响
  - 触发时 `event.setCancelled(true)`——阻止左键破坏 / 右键放置
  - Wand 入口但尚未有会话时，隐式调 `SessionManager.beginSelecting` 开会话
  - 已在 ISSUED/ACTIVE/CLOSING 阶段点击 → 红字提示先 `/canvas cancel`
  - 两角设完立即跑 `preview()`，Ok 显示 "Wall: WxH (N maps), facing F. From (x,y,z) to (x',y',z'). Run /canvas confirm."，Failed 显示 "Selection invalid: REASON — detail"
  - `PlayerQuitEvent` 处理：SELECTING 态玩家掉线立即释放；ISSUED/ACTIVE 保留（WS 重连宽限走 T10）
- `command/CanvasCommand.java`：
  - 新增 `edit / wand / cancel` 三个正式子命令
  - `cancel` 调用 `CanvasWand.removeAllFrom` 一并收回 wand
  - 构造参数从 `(mapPacketSender)` 扩展为 `(plugin, mapPacketSender, sessionManager)`
- `HikariCanvas.java`：`onEnable` 注册 `WandListener`（Bukkit `PluginManager.registerEvents`），传入 plugin + sessionManager；`CanvasCommand` 构造同步加参数

**玩家交互规范（对应 architecture.md §7.1）：**

| 入口 | 前提 | 行为 |
|---|---|---|
| `/canvas edit` | 无活跃会话 | 开启 SELECTING 态，actionbar 提示 |
| 持 Canvas Wand 点击 | 任意 | 隐式 beginSelecting；已有会话时忽略 wand 作用 |
| 空手 / 任意方块点击 | 玩家已 SELECTING | 记 pos1（左键）/ pos2（右键），聊天栏 echo + preview |
| `/canvas cancel` | 任意 | 会话 cancel + wand 收回 |

**实测路径（待手动验证）：**

```
/canvas edit                       # 进 SELECTING
左键 点 墙面某方块 (10, 64, -5)    # 聊天栏："First corner (10, 64, -5) facing EAST"
右键 点 墙面另一方块 (13, 65, -5)  # 聊天栏："Second corner (...)" +
                                   #           "Wall: 3x2 (6 maps), facing EAST. From (10,64,-5)
                                   #            to (13,65,-5). Run /canvas confirm."
/canvas cancel                     # "Session cancelled (was SELECTING)."
```

然后 Wand 模式：

```
/canvas wand                       # "Received Canvas Wand."
右键 / 左键 点墙面                 # 自动进 SELECTING + 记角 + 回显
```

**留给 T11：** `/canvas confirm` 命令尚未实装——T6 的聊天栏提示里引导用户敲这条命令，但实际执行会报"Unknown command"。T11 把 SessionManager.confirm() 接到命令上即可。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/CanvasWand.java`、`plugin/src/main/java/moe/hikari/canvas/session/WandListener.java`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T7 SessionManager + 会话状态机

**范围：** 编辑会话的生命周期核心。契约 `docs/architecture.md §3`。汇合点——后续 T6 Wand 提供玩家入口、T10 WS auth 标 ACTIVE、T11 命令族调 confirm / commit / cancel。

**文件：**
- `session/SessionState.java`（新）：四个状态 `SELECTING / ISSUED / ACTIVE / CLOSING`（CLOSED 不用显式枚举——从 `byId` 中移除即 CLOSED）
- `session/WallKey.java`（新）：`(world, originX, originY, originZ, facing)` 墙面排他锁 key
- `session/Session.java`（新）：会话可变 POJO；package-private setter 只允许 SessionManager 在持锁段内修改；字段按状态分阶段生效（SELECTING：pos1/pos2/face；ISSUED+：wall/mapIds/wallKey；ACTIVE：lastActivityAt/wsDisconnectedAt）
- `session/SessionManager.java`（新，核心）：
  - 索引 `byId` / `byPlayer` / `byWall`；所有公共方法 `synchronized(this)`
  - `beginSelecting` 返回 sealed `BeginResult.{Ok, AlreadyHasSession}`；后者封装了"每玩家最多 1 会话"约束
  - `recordPos(sessionId, isFirstCorner, block, face)` + `preview()`：selecting 阶段的聊天栏回显 hook
  - `confirm` 返回 sealed `ConfirmResult.{Ok, NotReady, WallFailed, WallOccupied, PoolExhausted}`——把所有失败路径显式建模，命令层 pattern-match 后对玩家产出对应的友好消息
  - `commit` 返回 sealed `CommitResult.{Ok, NotActive}`；ACTIVE 或 ISSUED 都允许 commit（命令通道 vs WS 通道）
  - `cancel(sessionId, reason)`：幂等、任何非 CLOSING 状态可调；自动归还池 + 释放 wallKey
  - `markActive` / `touch` / `markDisconnected` 是 T10 WS 层 hook
  - `liveSessionIds()` 给 {@code MapPool.detectLeaks} 消费
- `HikariCanvas.java`：`onEnable` 构造 `WallResolver(16)` + `SessionManager(log, mapPool, wallResolver, auditLog)` 并挂在 plugin 上

**设计取舍：**
- **失败路径用 sealed interface + record 显式建模**：不用 exception。调用方（T11 命令族）能编译期穷举所有 case，避免遗漏分支
- **Session 可变 + 外部不该直接改**：Java 17+ sealed + 可见性不支持"只 package 能读"；退而用 package-private setter + 文档约束"只在 SessionManager 持锁下修改"
- **状态机无 CLOSED 枚举**：`forget(s)` 从 map 里移除 = CLOSED；减少状态判定需要
- **wallKey 即排他锁**：`byWall` 本身就是锁表；commit/cancel 时 `byWall.remove(wallKey, sessionId)` 原子释放
- **本 M2 不加定时回收 task**：WS idle 5min、auth 超时这些留给 T10/T11。理由：idle 判定需要 `lastActivityAt`，该字段只在 WS 消息到达时更新，而 T7 阶段还没有 WS 绑定；时机还未到

**未做（留给后续）：**
- 定时回收 task：T10 或 T11 加 Bukkit scheduler 周期扫 `wsDisconnectedAt > 0 && now - wsDisconnectedAt > 5min` 触发 cancel
- 权限校验：T11 在命令层做（`canvas.edit` / `canvas.commit`）；SessionManager 不做权限判断
- SignRecord 写入：T11 commit 流程里 SessionManager.commit 之后由调用方 insert 到 `sign_records` 表

**验证：**
- `./gradlew :plugin:compileJava` 通过
- SessionManager 虽已挂在 HikariCanvas 但无调用入口，runServer 启动表现与 T9 一致（空跑不崩）。真正闭环测试在 T11+T12。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/session/` 4 个新文件、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T9 PlaceholderRenderer + BitmapFont

**范围：** 按 `docs/architecture.md §4.4` 渲染 Placeholder 占位图。128×128 浅灰底 + 顶部 "HIKARICANVAS" 水印 + 底部 "N/M" 位置标签。M4 真字体接入前就靠这套。

**文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`（新）：手工定义的 5×7 ASCII 位图字表
  - 字符集仅覆盖 **H/I/K/A/R/C/N/V/S + 0-9 + "/" + 空格**（共 21 个）—— 刚够拼 "HIKARICANVAS" 水印 + 位置标签 "N/M"
  - 存储：`Map<Character, int[7]>`，每行一个 int 的低 5 位（MSB→LSB 代表从左到右 5 个像素）
  - 未知字符返回 `EMPTY` 空白（不报错），小写自动 `toUpperCase()`
- `plugin/src/main/java/moe/hikari/canvas/render/PlaceholderRenderer.java`（新）：
  - `render(slotIndex, totalSlots) → byte[128*128]`
  - 背景填色 palette 索引 33（浅色）；前景 44（深色）—— M4 调色板 LUT 就位后修正精确值
  - 顶部 "HIKARICANVAS" scale=1（12 字符 × 6 像素 = 71 px 宽，y=12 居中）
  - 底部 "N/M" scale=3（显眼大号，y=97）
  - 无状态，并发安全
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`：新增 `/canvas placeholder <slot> <total>` DEPRECATED 子命令，方便玩家**手动预览渲染效果**（T11 一起删）

**设计取舍：**
- **字体字符集极小**：contract 只说"HikariCanvas 水印"和"坐标文字"。我把"坐标文字"改成更简洁的 "N/M"（如 "2/6"）——实际 128×128 像素空间下世界坐标 `(x,y,z)→(x',y',z')` 太挤，"第几张/共几张" 反而更有用
- **全大写**："HIKARICANVAS" 比"HikariCanvas"省字形。M4 真字体接入后再换大小写混排
- **Palette 索引 33/44 是经验值**：M2 demo 够用；M4 RGB→palette LUT 建好后以 `#CCCCCC` 和 `#3A3A3A` 重选精确索引
- **位图字表用硬编码 int 数组**：不用图片资源 / PNG 解码，启动零 I/O；字符总数 21，代码不到 30 行
- **slot 从 0 开始传入，渲染时显示 +1**：代码层保留 0-based 习惯，UI 层玩家看到的是 1-based

**手动验证方式（测试命令已就绪）：**
```
/canvas give                       # 拿一张空白地图
/canvas placeholder 0 6            # 显示 "1/6"
/canvas placeholder 2 6            # 显示 "3/6"
/canvas placeholder 5 6            # 显示 "6/6"
```

**留给后续任务：**
- T8 `FrameDeployer` 会在 `/canvas confirm` 时调 `render(slot, total)` 给每张物品框填 placeholder
- M4 渲染引擎会用真 TTF 字体替换此处所有逻辑；本包 `render/` 会保留但 `PlaceholderRenderer` / `BitmapFont` 这两个类的实现完全重写

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`、`plugin/src/main/java/moe/hikari/canvas/render/PlaceholderRenderer.java`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T5 WallResolver

**范围：** 纯算法类，将玩家两次点击（pos1/pos2 + BlockFace）解析为墙面矩形 + 合法性校验；输出 `Result.Ok` 或 `Result.Failed(reason, detail)`。T6 Wand + SELECTING 会调用它做选区预览，T7 SessionManager 在 `/canvas confirm` 时复用同一份逻辑。

**设计：**
- 无状态、无副作用，构造只接收 `maxMaps` 上限（对应 `limits.canvas-max-maps` = 16 默认）
- Result 用 sealed interface + 两个 record（`Ok` / `Failed`），调用方 pattern-match
- M2 仅支持**水平四向墙**（N/S/E/W）；UP/DOWN 返回 `VERTICAL_ONLY`，M4+ 再放宽

**失败码清单（返回 `FailReason`）：**
- `NORMAL_MISMATCH` — 两次点击 BlockFace 不同（两面墙）
- `DIFFERENT_WORLDS` — 两 block 跨世界
- `VERTICAL_ONLY` — normal = UP/DOWN
- `NOT_COPLANAR` — 同 normal 但不在同一平面（X 或 Z 轴需一致）
- `TOO_LARGE` — `width × height > maxMaps`
- `BLOCK_NOT_SOLID` — bbox 内某方块非实心 full cube（排除台阶/栅栏/玻璃板）
- `OCCUPIED` — bbox 前方一格已挂 ItemFrame（扫描 `getNearbyEntitiesByType(ItemFrame.class, ...)`）

**算法要点：**
- 同平面判定：`EAST/WEST` 要求 `pos1.x == pos2.x`；`NORTH/SOUTH` 要求 `pos1.z == pos2.z`
- `width` 随法线轴决定（法线 X → width 沿 Z；法线 Z → width 沿 X）
- `isSolid() && isOccluding()` 判实心 full cube（`isSolid` 包含台阶，`isOccluding` 更严）
- ItemFrame 占用检测兼容 Paper 1.21 `getAttachedFace()` 的边界差异：既查 `attachedFace.getOpposite() == face`，又查 frame 位置是否落在 adjacent 方块内

**验证：** `./gradlew :plugin:compileJava` 通过。运行时 smoke test 留到 T6 有真实玩家交互入口后做。

**未包含：**
- 不校验墙面背后是否有"支撑"（物品框挂在固体墙上，MC 不要求背面有支撑；若 T8 发现问题再补）
- 不处理透明方块（glass）——`isOccluding=false` 会被 `BLOCK_NOT_SOLID` 拦，合理
- Maximum 尺寸以地图数计（16 默认），不再单独检查单维上限——若玩家选 1×16，算法允许

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/WallResolver.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T4 MapPool —— PROPOSAL 风险表 #1 核心机制落地

**范围：** 预览地图池状态机（FREE/RESERVED/PERMANENT）+ SQLite 同步 + 启动恢复 + 泄漏检测。**这是 M2 的技术核心**，也是整个项目防止 `idcounts.dat` 膨胀的唯一防线。

**文件：**
- `plugin/src/main/java/moe/hikari/canvas/pool/PoolState.java`（新）：三状态枚举
- `plugin/src/main/java/moe/hikari/canvas/pool/PooledMap.java`（新）：不可变 record，`withFree` / `withReserved` / `withPermanent` 原子 transition
- `plugin/src/main/java/moe/hikari/canvas/pool/PoolExhaustedException.java`（新）：容量耗尽时抛
- `plugin/src/main/java/moe/hikari/canvas/pool/MapPool.java`（新，核心）：
  - 索引：`Map<Integer, PooledMap> byId` + `Deque<Integer> freeQueue`
  - 全部公开方法 `synchronized(this)`
  - `initialize(World)`：读 `pool_maps` 表 → 校验不变式（异常行降级 FREE + 告警）→ `Bukkit.getMap(id)` 检查 MapView 仍在（丢失则 DELETE 行 + `POOL_ORPHAN_ROW` 告警）→ FREE 数量 < `initial-size` 时 expand 补齐
  - `reserve(sessionId, count)`：FREE 不够时自动 expand；expand 超 `max-size` 抛 `PoolExhaustedException`（对应 protocol.md §6.1 错误码 `POOL_EXHAUSTED`）
  - `returnToPool(sessionId)`：cancel 路径；遍历 RESERVED 且 `reservedBy==sessionId` → FREE + 重入 freeQueue
  - `promoteToPermanent(sessionId, signId, world)`：commit 路径；RESERVED → PERMANENT（从"可用"计数中抽走，但保留在 `byId`）；随后 refill FREE 到 `initial-size`
  - `detectLeaks(liveSessions)`：RESERVED 但 sessionId 不在活会话中 → 强制归还 + `POOL_LEAK` 告警
  - 所有状态转移 `persist()` 用 `INSERT ... ON CONFLICT DO UPDATE`（SQLite upsert），幂等
- `HikariCanvas.java`：onEnable 构造 `MapPool(initial=64, max=256)` 并 `initialize(Bukkit.getWorlds().get(0))`

**关键约束与设计取舍：**
- **线程安全 / 主线程约束：** `Bukkit.createMap` 与 `MapView` 相关调用必须在主线程，因此 `initialize`、`reserve`、`promoteToPermanent` 三个"可能触发 createMap"的方法**只能主线程调用**（javadoc 已标）。`detectLeaks` 只读状态和归还（不 createMap），可异步调用——给后台 leak detection 留出空间
- **PDC 标记暂不实装**：data-model.md §3.3 说"SQLite 与 PDC 不一致时以 SQLite 为权威"，M2 阶段只用 SQLite 作为单一 source of truth；MapView PDC 标记（`pool_state` / `owner` / `session_id` 等）留给 M7 打磨期增强韧性——那时再一次性跨所有状态转移打 PDC
- **initial/max 硬编码 64/256**：config.yml 接入延后；contract 已定默认值
- **不变式异常一律降级 FREE**：启动时若发现违反不变式的记录（例如 FREE 但 `reserved_by` 非空），不尝试推断原状态，**直接强制归还 FREE**，让池进入已知干净状态。数据安全 > 便利性
- **missing MapView 处理**：DB 有记录但 `Bukkit.getMap(id)` 返回 null（典型场景：世界文件丢失 / 手动删 idcounts.dat）→ 删 DB 行 + `POOL_ORPHAN_ROW` 审计；让 MapPool 退回干净状态

**实测（首次启动 2026-04-21 16:22）：**
- 日志链：`Database initialized` → `DB schema current version: 1` → `MapPool recovered 0 entries` → `MapPool growing FREE by 64 to reach initial-size=64` → `HikariCanvas enabled`
- 创建 64 张 MapView 用时约 3s
- `sqlite3 data.db "SELECT state, COUNT(*) FROM pool_maps GROUP BY state"` → `FREE|64`
- `audit_log` 里出现两条：`POOL_EXPAND` 和 `POOL_INITIALIZED`
- 下次启动（未测试，T12 集成时验证）应走 "MapPool recovered 64 entries; free=64 ..." 分支而不是再 expand

**留给后续任务：**
- Placeholder 像素填充（T9）：reserve 出来的地图目前 MapView 是空的（客户端会看到旧缓存）；T9 实现 PlaceholderRenderer 后由 T8 FrameDeployer 挂物品框时一并 push 初始像素
- Leak detection 调度（T7）：当前 `detectLeaks` 方法有了但没挂定时任务——需要 SessionManager 提供 `liveSessions: Set<String>`，T7 SessionManager 就位后再串起来
- `/canvas stats` 输出（T11）：`MapPool.stats()` 已准备好 record，等命令族实装

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/pool/`（新 4 个文件）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T3 TokenService + AuditLog 封装

**范围：** 一次性 token 签发 / 校验 / 消耗的核心服务，并带 SHA-256 审计。按 `docs/security.md §2` 落地。限流（§2.4）延后到 M2-T10 WS 握手时一起做（需要 IP 上下文）。

**改动：**
- `plugin/src/main/java/moe/hikari/canvas/storage/AuditLog.java`（新）：`audit_log` 表的薄封装；字段 `event / player_uuid / player_name / session_id / ip_hash / details(JSON)`；`details` 用 Jackson 序列化 `Map<String, Object>`；插入失败 fire-and-forget
- `plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`（新）：
  - `SecureRandom` + 32 字节 + `Base64.getUrlEncoder().withoutPadding()` = 43 字符
  - 内存 `ConcurrentHashMap<String, Record>` 主存
  - `issue(playerUuid, playerName, sessionId)` → 返回原文 token，同步 `AUTH_ISSUED` 事件入 `audit_log`（**只存 token 的 SHA-256**，永不落盘原文）
  - `peek(token)` / `consume(token)` 共用 `evaluate(..., consume=)`：长度 / base64 解码 / 不存在 / 已使用 / 过期**五重校验**，按 security.md §2.3 顺序
  - `consume` 使用 `ConcurrentHashMap.replace(k, oldV, newV)` 做原子 CAS，防并发重复消费
  - `purgeExpired()` 惰性清理，HikariCanvas 每 5 分钟异步调用一次
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：onEnable 构造 AuditLog + TokenService 并挂 5min 周期 purge task；onDisable cancel task

**设计细节与取舍：**
- **TTL 硬编码 15 min**（contract 默认）；config.yml 接入留给 M2-T10 或后续 polish 任务一并做
- **API 分 `peek` 和 `consume` 两个入口**：HTTP 预握手 `GET /api/session/:token` 只 peek 不消耗（供客户端在 WS auth 前确认会话信息）；WS `auth` 帧到达时才 consume（security.md §2.1 的"消耗后立即失效"）
- **rotate**（§2.2 的 WS 握手成功后签发新 token）没独立 API；调用方拿到 Ok 结果后自己 `issue` 一次即可——避免给 TokenService 加隐含状态
- **RejectReason 不向外透露**（security.md §2.3："失败场景统一返回 AUTH_FAILED；不向外透露具体原因，避免枚举攻击"）——内部 log 记具体原因供运维排错，WS/HTTP 响应只返回统一 401/4001
- 限流实现延后：**单玩家 10/5min 封禁 / 全局 100/min 保守模式** 需要 IP 信息，WS 握手前无从获取；T10 实现 `GET /api/session/:token` 时再在入口做限流

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- runServer 启动正常（Done 8.675s）；TokenService + 5min async purge task 挂上无异常
- `audit_log` 表仍为空（还没有业务链路触发 `issue`；M2-T10/T11 会出现真实记录）

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/storage/AuditLog.java`、`plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T2 SQLite + HikariCP + JDBI 接入 + schema v1 建表

**范围：** 按 data-model.md §2 全量建表；持久化基础设施就位，后续 T3/T4/T7 等能直接写 SQL

**依赖（当前稳定版实测）：**
- `org.xerial:sqlite-jdbc:3.53.0.0`（Gradle 冲突解析实际拿到 `3.49.1.0`——paperweight-userdev 的 paperDevBundle 传递依赖强制锁了低版本；3.49.1.0 功能相同，stable。M2 不折腾，待 M7 打磨期看是否要 force 3.53。异常栈里能看到版本号已确认）
- `com.zaxxer:HikariCP:7.0.2`
- `org.jdbi:jdbi3-core:3.52.1`
- `org.jdbi:jdbi3-sqlite:3.52.1`（注意包名是 `org.jdbi.v3.sqlite3`——末尾 `3` 是模块约定，不是版本号；不是 `sqlite`）

**踩坑记录：**
1. **JDBI sqlite 模块包名陷阱**：import 写 `org.jdbi.v3.sqlite.SQLitePlugin` 编译失败；真实路径是 `org.jdbi.v3.sqlite3`。用 `jar tf` 查 jar 内 class 一眼看出
2. **迁移脚本注释处理 bug**（运行时 crash）：
   - `V001__initial.sql` 开头有 3 行文件级注释 `-- ...`，紧接第一个 `CREATE TABLE pool_maps`
   - 原实现按 `;` split 后再 `trimmed.startsWith("--")` 跳过注释——**整个片段以 `--` 开头（实际内含 CREATE TABLE）被误跳过**
   - 结果：`CREATE TABLE pool_maps` 没跑，下一条 `CREATE INDEX ... ON pool_maps(state)` 报 `no such table`
   - 修法：loadResource 阶段**逐行剥注释**再拼接；拆分阶段只跳空串，不再判 `--`
3. **sqlite-jdbc 不支持一次 execute 多条语句**：无论用 `;` 分隔还是批处理都要自己拆。拆分时遇到数据里含 `;` 的情况会有风险（M2 schema 没有，future schema 若有字符串字面量含 `;` 要用更严谨的 SQL tokenizer）

**改动文件：**
- `plugin/build.gradle.kts`：新增 4 个 implementation 依赖（sqlite-jdbc/HikariCP/jdbi3-core/jdbi3-sqlite）
- `plugin/src/main/resources/db-migrations/V001__initial.sql`（新）：按 data-model.md §2.3~§2.6 建 `pool_maps`/`sign_records`/`audit_log`/`template_usage` 四张表 + 全部索引。不含 `schema_version`（由 Java 代码首次确保存在）
- `plugin/src/main/java/moe/hikari/canvas/storage/Database.java`（新）：HikariCP 连接池封装，最大 4 连接；SQLite 打 WAL 模式 + 外键约束；暴露 `Jdbi jdbi()`；`AutoCloseable`
- `plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`（新）：显式列表式迁移（不做 classpath 扫描，shadow jar 下不稳定）；`ensureSchemaVersionTable` 用 IF NOT EXISTS；应用每个脚本后 INSERT schema_version；逐行剥注释 + 按 `;` 拆
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：onEnable 构造 Database 并跑 MigrationRunner；onDisable 关闭

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- runServer 启动成功：`Database initialized` → `DB schema current version: 0` → `Applying migration V001 ...` → `✓ V001 applied` → `WebServer listening` → `HikariCanvas enabled` → `Done (8.165s)!`
- `sqlite3 plugin/run/plugins/HikariCanvas/data.db ".tables"` 输出全部 5 张表：`audit_log pool_maps schema_version sign_records template_usage`
- `SELECT * FROM schema_version` → `1|1776697225399`
- 4 张业务表空（新库）

**M2 剩余任务（M2-T3~T12）从这里开始都可以假设 `database.jdbi()` 可用。**

**关联文件：** `plugin/build.gradle.kts`、`plugin/src/main/resources/db-migrations/V001__initial.sql`、`plugin/src/main/java/moe/hikari/canvas/storage/Database.java`、`plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T1 代码层改名 Hc → Canvas

**范围：** 契约已改，代码层跟进：类名、根 literal、import、用户消息字串

**改动：**
- `git mv` `HcCommand.java` → `CanvasCommand.java`；类名同步；根 literal `"hc"` → `"canvas"`
- `HikariCanvas.java`：import + `new HcCommand(...)` → `new CanvasCommand(...)`
- 内部 javadoc、聊天栏消息里的 `/hc give` / `/hc paint` 全改 `/canvas give` / `/canvas paint`
- 子命令 `give` / `paint` **保留为 DEPRECATED demo**（代码注释明确标记，运行时消息前缀加 `[DEPRECATED demo]`）——M2 实施中间阶段还没有正式命令族，需要它们手动验证发包链路；T11 命令族完整实装时一起删除
- `./gradlew :plugin:shadowJar` 通过

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`（rename from `HcCommand.java`）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2 立项契约修订：命令前缀 /canvas + 墙面交互规范 + Placeholder 地图

**范围：** 进入 M2 前的契约层对齐。三件事：命令前缀从 `/hc` 全局改为 `/canvas`（含权限节点）；细化墙面交互流程（SELECTING 状态 + 两段式确认）；新增 Placeholder 地图规范。纯文档变更，代码留到 M2 任务逐步落实。

**为什么要改：**
- `/hc` 对新玩家意义不明（"hikari canvas" 的缩写不直观）；`/canvas` 一眼见义
- architecture.md 原版「锁定墙面」段只写了射线检测 + 尺寸从模板来，没定玩家如何告诉插件"就是这面墙"——M2 实施前这一空白必须填上
- M1 完成时发现：玩家从命令到真正看到编辑效果中间有一大段纯文字反馈，不直观。`/canvas confirm` 立即挂物品框 + Placeholder 能让玩家在游戏里马上看到"墙被选中了"，UX 更自然

**关键决策（讨论后拍板）：**

1. **命令前缀 /hc → /canvas，权限前缀 hc. → canvas.**
   - PDC namespace `hikari_canvas` **保持不变**（底层数据标记，不与用户直接交互，改动纯属噪声）
   - Java 类名 `HcCommand` → `CanvasCommand`（M2 实现时改）

2. **墙面交互采用命令 + Wand 双入口组合（用户选定）：**
   - `/canvas edit`：进入 SELECTING 状态，玩家空手或任何方块点击均可（零背包负担，偶尔使用者友好）
   - `/canvas wand`：发命名金铲「Canvas Wand」，持 wand 时左/右键直接交互，无需先打命令（频繁使用者友好）
   - 左键 = pos1，右键 = pos2；每次点击聊天栏 echo 坐标与预览
   - 手打 `/canvas confirm` 才真正 commit 选区（刻意保留一步手动动作，避免误点意外创建会话）
   - `/canvas confirm` 后**立即挂物品框 + 借池地图 + 填 Placeholder** → 玩家即刻看到占位网格

3. **新状态 SELECTING 加入会话状态机**（原来只有 CLOSED/ISSUED/ACTIVE/EXPIRED/CLOSING）
   - SELECTING 态**不占池、不挂物品框**——只是个"选区草稿"；任何时候可 `/canvas cancel` 或玩家掉线即释放

4. **Placeholder 地图视觉（用户选 A）：**
   - 浅灰底 + 顶部 "HikariCanvas" 水印 + 底部坐标文字
   - M4 前无字体系统，所以用**预烘焙位图 ASCII 字表**（只覆盖英文字母/数字/标点）
   - 所有会话共享同一只读像素缓冲；每张物品框叠加独立的 "位置标签"（如 "2/6"）以区分

5. **删除 /hc give demo 命令**
   - M1 阶段临时用来让玩家拿 filled_map 测试涂色的快捷命令
   - M2 有 `/canvas edit` + wand 正规流程，give 不再需要；保留会误导用户走错工作流

**改动文件：**
- `CLAUDE.md` 标识表：命令前缀 / 权限前缀
- `PROPOSAL.md`：§4.1 命令清单（补 edit/wand/confirm/cancel/commit/cleanup/stats，删 give/undo）；§5.2 UX 文字；§5.3 项目结构；§7 风险表 `/hc/` → `/canvas/`
- `docs/architecture.md`：§1.3 数据流图两段式重绘；§2.1 组件说明；§3.1 状态机增加 SELECTING 态；§3.2 状态转移表 4 条新增；§7.1 从"锁定墙面"改写为"交互与选区（两段式）"；§7.2 物品框部署补"填 Placeholder"；**新增 §4.4 Placeholder 地图规范**；旧 §4.4 健康指标改号 §4.5；若干 `/hc` 字串替换
- `docs/security.md`：§5 权限节点表全改名（新增 `canvas.wand` / `canvas.admin.force-break`）；§6 校验检查点表全改；nginx 配置示例 `/hc/` → `/canvas/`；审计命令
- `docs/data-model.md`：`/hc remove` / `/hc cleanup` / `/hc fsck` 改名（PDC namespace 保持 `hikari_canvas` 不动）
- `docs/template-spec.md`：`/hc reload templates` 改名

**journal 历史条目里的 /hc 字串**（M1-T5 / T6 / T7 / 改名条目里的 give/paint 等）**不动**——journal 是过程记录，不是契约文档；改动会伪造历史。

**M2 任务拆解（等用户最终 OK 后正式建 Task）：**
1. SQLite + HikariCP + JDBI 接入 + 建表脚本（data-model.md §2）
2. 命令 / 权限 / 类名 `HcCommand` → `CanvasCommand`（文档已改，代码跟进；顺便删 M1 的 give/paint demo 命令）
3. TokenService（内存主 + SHA256 审计；TTL 15min；单次使用 + rotate）
4. MapPool 实现（FREE/RESERVED/PERMANENT 状态机；借/还/refill/leak detection）
5. WallResolver（pos1/pos2 → bounding box → 合法性校验 + BlockFace 法线识别）
6. Canvas Wand 物品 + PlayerInteractEvent listener + SELECTING 状态机
7. SessionManager（每玩家 1 活跃 + 每墙面排他锁 + disconnect 5min 宽限）
8. FrameDeployer（挂物品框 + PDC 标记 + 保护 listener：HangingBreak/BlockBreak/PlayerInteractEntity）
9. PlaceholderRenderer（位图 ASCII 字表 + 预烘焙共享缓冲）
10. WebServer 预握手 `GET /api/session/:token` + WS `auth` 帧 + `ready` + 初步 `state.snapshot`
11. /canvas 命令族完整实装（edit/wand/confirm/cancel/commit/cleanup/stats）
12. 集成测试：完整 SELECTING → ISSUED → ACTIVE → CLOSING(commit) 一轮

**关联文件：** `CLAUDE.md`、`PROPOSAL.md`、`docs/architecture.md`、`docs/security.md`、`docs/data-model.md`、`docs/template-spec.md`、`docs/journal.md`

---

## 2026-04-20 · M1-T7a 端到端联调通过 · **M1 正式完成**

**范围：** M1 最终验收标准实装——浏览器按钮 → WebSocket → 游戏内地图像素变化。

**里程碑：** PROPOSAL §6 M1 行从「1 周」更新为「已完成（2026-04-20）」。从 M0 立项（2026-04-18）到 M1 收尾共 3 个工作日，较原估 1 周缩短。

**端到端链路（实测）：**

```
Minecraft 客户端 ─ /hc give ─▶ 插件发地图 item + MapView 给玩家
浏览器 ─ 点 Paint 按钮 ─▶ ws://127.0.0.1:8877/ws ─ {op:"paint"}
插件 WebServer.handleMessage(paint) ─▶ paintHandler.run()
HikariCanvas.paintAllHeldMapsRed ─▶ Bukkit 主线程 runTask
遍历 online players 主手 filled_map ─▶ MapPacketSender.sendFullMap
PacketEvents 发 ClientboundMapItemDataPacket ─▶ MC 客户端地图变红
```

**改动（代码层）：**

**Gradle ↔ npm 联动（`plugin/build.gradle.kts`）：**
- `installWebDeps`（Exec）：`npm install` in `web/`，`onlyIf` 判断 `node_modules` 不存在（避免每次 CI/clean 重复下载）
- `buildWeb`（Exec）：`npm run build`；声明完整 inputs（`package.json`/`package-lock.json`/`src/`/`index.html`/`vite.config.ts`/`tsconfig.json`）+ outputs `web/dist`，Gradle up-to-date 检查得以生效
- `copyWebToResources`（Copy）：把 `web/dist` 拷到 `build/generated/web-resources/web/`
- `sourceSets.main.resources.srcDir(build/generated/web-resources)` + `processResources.dependsOn(copyWebToResources)`：把前端产物自动并入 plugin 资源，shadowJar 自带包进
- **陷阱：** Gradle 9 的 Exec task `doFirst {}` 里不再可用 `exec { ... }` 闭包（"Too many arguments for 'fun exec(): Unit'"）。改成**独立 Exec task + onlyIf** 解决

**后端（`WebServer.java` / `HikariCanvas.java`）：**
- `WebServer` 构造参数加 `Runnable paintHandler`；`handleMessage` 新增 case `"paint"` → `paintHandler.run() + ack(submitted:true)`
- `HikariCanvas.paintAllHeldMapsRed()`：`Bukkit.getScheduler().runTask(this, ...)` 切主线程；遍历 `Bukkit.getOnlinePlayers()`；对主手 `filled_map` 调 `MapPacketSender.sendFullMap`
- 架构纪律 §5.2.6 继续被遵守：所有 packet 发送仍在 `MapPacketSender` 内部，HikariCanvas 主类只调 sender API

**前端（`web/` 子项目）：**
- `vite.config.ts`：dev server 端口 `5173` → `9173` + `strictPort: true`（用户本地 5173 被占；strictPort 让冲突时直接报错而不是静默降级）
- `index.html`：新增 `Paint held map → red` 按钮（红色主色调）
- `main.ts`：抽出 `send(op)` helper；新增 paint 按钮 handler；`resolveWsUrl()` 改得不依赖具体 dev 端口——只要 origin 不是 `127.0.0.1:8877` 就跨源连插件（未来再换 dev 端口无需改代码）

**TODO 留给 M7 打磨阶段：** `Javalin 7 的 cfg.staticFiles.add("/web", CLASSPATH)` 在 shadow/fat jar setup 下 directory discovery 失败，抛 `JavalinException: "... does not exist. Depending on your setup, empty folders might not get copied to classpath."`。web 资源**实际已进 jar**（`jar tf` 可见 `web/index.html` 和 `web/assets/*`）。M1-T7a 先绕开：开发期走 Vite dev + 跨源 WS。M7 单 jar 部署时改用手写 GET handler 读 classpath 资源，或 `Location.EXTERNAL` + 已知文件系统路径。WebServer 代码里已留 `TODO(M7)` 注释。

**WS idle timeout：** 实测时出现过 `WebSocketTimeoutException: Idle Timeout 30005/30000 ms` —— Jetty 12 默认 30s 无消息就断，属 Javalin/Jetty 默认行为。前端 `ensureConnected()` 已处理重连；M2 做会话/token 时可一并调大或上应用层心跳。

**实测（你自测）：**
- MC 1.21.11 客户端连 127.0.0.1 → `/hc give` 拿到空白 canvas 地图
- 浏览器 `http://127.0.0.1:9173/` → 点 Paint → 游戏内地图可视区**变红**
- server console 出现 `WS paint op: painted 1 held maps`
- 无 exception / Caused by

**里程碑总结（M0→M1）：**

| 任务 | commit | 关键成果 |
| --- | --- | --- |
| T1 Gradle 骨架 | `525ac54` | paperweight-userdev 2.0.0-beta.21 + paper-api 1.21.11 sync 通过 |
| T2 插件主类 | `404a4af` | 最小 `JavaPlugin` + `paper-plugin.yml`，jar 能 load |
| T3 runServer | `4c14fc8` | `xyz.jpenilla.run-paper` 接入；EULA 自动接受；生命周期日志两端跑通 |
| T4 Javalin + WS | `dd8097c` | Javalin 7 + Jetty 12 + Jackson + shadow 胖 jar；ws upgrade 101 握手通过 |
| T5 /hc paint + Packet | `73ce7bc` | **核心风险验证通过**：`WrapperPlayServerMapData` 发包直接改像素，游戏内地图涂红 |
| T6 前端骨架 | `8838ea5` | Vite 8.0.9 + TS 6.0.3；原生 DOM；build 产物 < 2KB |
| T7b 契约修正 | `8d497e7` | snapshot 测试台推迟到 M4 |
| T7a 端到端 | *本次* | 浏览器 → WS → 游戏内地图像素变化（**M1 验收**） |

**关联文件：** `PROPOSAL.md`（§6 M1 行状态更新）、`plugin/build.gradle.kts`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`web/index.html`、`web/src/main.ts`、`web/vite.config.ts`、`docs/journal.md`

---

## 2026-04-20 · M1-T7b 契约修正：snapshot 测试台推迟到 M4

**范围：** 纯文档变更，PROPOSAL 三处表述从"M1 就建 snapshot 测试台"改为"M4 渲染引擎立项期建立"

**触发原因：** T1~T6 完成后回头评估——M1 阶段前后端都不涉及字体/排版/效果渲染（那些在 M4），此时"像素级对比测试台"没有实际可比的内容，搭起来只是空壳基础设施，不如在 M4 两端真实渲染代码就位时一次建好。这是一次**契约回调**：立项期的判断现在看过于超前。

**我之前曾保留 "M1 就建" 的决策（见 2026-04-19 · CLAUDE.md 首版 + M1 技术选型拍板 条目的第 4 条）。**本次根据实施经验调整。

**改动：**
- `PROPOSAL.md` §5.2.1：末尾补充括号注「**M4 渲染引擎立项期同步搭建**；M1 不含」
- `PROPOSAL.md` §6 里程碑表 M4 行：补「**同步建立双端像素级 snapshot 测试台**（见 §5.2.1）」+ 产出列补「+ snapshot 基准集」
- `PROPOSAL.md` §7 风险表「浏览器 Canvas 与 Java Graphics2D 渲染不一致」应对列：`M1` → `M4`，并解释推迟理由
- `docs/rendering.md` 已有的 snapshot 相关表述（§渲染管线 / §发光 / §抖动）**不涉及时点**，无需改

**未动：**
- 其他 snapshot 相关文本（`docs/rendering.md` 里 `rendering-test/glow-*.png` 基准、Floyd-Steinberg 抖动覆盖等）都是**机制说明**而非时点说明，保留
- `docs/protocol.md` 里的 `state.snapshot` 是协议 op，与渲染 snapshot 无关

**这一改动让 M1 最终只剩 T7a（端到端联调），M1 真正的验收标准（浏览器按钮 → WS → 游戏内地图变红）得以聚焦。**

**关联文件：** `PROPOSAL.md`、`docs/journal.md`

---

## 2026-04-20 · M1-T6 前端按钮页面（原生 DOM + Vite + TypeScript 骨架）

**范围：** 在仓库里建 `web/` 子项目；一个按钮点击时打开 WebSocket 到 `ws://127.0.0.1:8877/ws` 并发 `ping`，把服务端响应渲染到页面。构建链路跑通即算完成（真正的端到端 round-trip 留给 T7）。

**立项期决策再重申**（见 PROPOSAL §5.1、CLAUDE.md 技术栈表）：
- M1~M4 **仅用原生 DOM + 原生 WebSocket API + TypeScript**
- Vue 3 / Konva / Pinia **M5 才引入**
- 目的：M1 端到端验证不需要前端框架；M5 再一次性搭 Canva 式编辑器

**技术选型（当前稳定版实测）：**
- Vite **8.0.9**（2026-03-12 release；Rolldown 作为统一 Rust 打包器替代 esbuild+Rollup，构建 10-30x 加速）
- TypeScript **6.0.3**（2026-03-23 release；基于 JS 编译器的最后一个大版本；7.0 年中转 Go）
- Node **25.2.1** / npm **11.6.2**（本机 brew 装的当前版本）
- Vite dev server bind `127.0.0.1:5173`（不监听 `0.0.0.0`——不要无意暴露到公网，同 Paper 插件一个安全默认）

**文件结构：**
- `web/package.json`：`type: module`；scripts `dev / build / preview`；devDeps `vite ^8.0.9 / typescript ^6.0.3`
- `web/vite.config.ts`：dev server 127.0.0.1:5173；`build.outDir = "dist"`；`build.target = "es2022"`
- `web/tsconfig.json`：严格模式全开（`strict / noUnusedLocals / noUnusedParameters / noImplicitReturns / noFallthroughCasesInSwitch / verbatimModuleSyntax`）；lib 包含 `DOM / DOM.Iterable`
- `web/index.html`：页面壳 + `<button id="ping-btn">` + `<div id="log">`；极简 system-ui 样式
- `web/src/main.ts`：
  - `Envelope<P>` TypeScript interface 与 `docs/protocol.md` §2 对齐（`v / op / id? / ts? / payload?`）
  - 按钮点击 → 首次连 `ws://127.0.0.1:8877/ws`（之后复用）→ 发 `{v:1, op:"ping", id:"c-<seq>", ts:Date.now()}` → 收到响应打印到 log
  - `open / message / close / error` 四个事件各有独立样式（sent/recv/err/meta）

**构建验证：**
- `npm install` 成功（16 packages，无 vulnerability）
- `npm run build`（`tsc --noEmit && vite build`）`25ms` 完成；产物：
  - `dist/index.html` 1.60 KB（gzip 0.84 KB）
  - `dist/assets/index-<hash>.js` 1.93 KB（gzip 1.02 KB）
- 这个产物体积反映了「不引任何框架」的初衷——整个前端 2KB 不到

**.gitignore 覆盖验证：**
- `node_modules/` / `dist/` 都被根 `.gitignore` 已有规则排除
- 入库的是 `package.json` / `package-lock.json` / `vite.config.ts` / `tsconfig.json` / `index.html` / `src/main.ts` 共 6 个源文件

**未做（留给 T7）：**
- Gradle ↔ npm 联动（`./gradlew build` 自动触发 `npm run build` + 产物拷到 `plugin/src/main/resources/web/`）——T7 做，届时插件 serve 静态资源 + WS 在同源
- 端到端实测（runServer + Vite dev server 同时跑、点按钮看 `pong`）——T7 做
- Jackson 回填的 `payload` 是 `{}` 空对象，前端 `recv` 只打 log 不解析 payload 结构——足够 T6

**关联文件：** `web/package.json`、`web/package-lock.json`、`web/vite.config.ts`、`web/tsconfig.json`、`web/index.html`、`web/src/main.ts`、`docs/journal.md`

---

## 2026-04-20 · M1-T5 /hc paint + PacketEvents 发包链路打通（M1 核心风险验证通过）

**范围：** 在插件里集成 PacketEvents 2.11.2，注册 Brigadier 命令 `/hc paint`，玩家主手地图被整张涂红——**PROPOSAL 风险表 #1「PacketEvents 版本升级破坏兼容」已实测无问题；#2「预览地图池机制」的前置能力（不走 MapRenderer，直接发包改像素）验证成立**

**依赖与打包调整：**
- `plugin/build.gradle.kts` 新增 codemc 仓库 `https://repo.codemc.io/repository/maven-releases/`（PacketEvents 主发行地）
- `implementation("com.github.retrooper:packetevents-spigot:2.11.2")` — shade 进胖 jar。注：这是 M1 最省事的方案，M2+ 可改为 `compileOnly` + 依赖独立 PacketEvents 插件（避免多插件 shade 冲突），届时同步更新 paper-plugin.yml 的 `dependencies.server`
- 胖 jar 体积上升到（略），新增 `com/github/retrooper/**`、`io/github/retrooper/**`

**PacketEvents 标准初始化流程：**
- `HikariCanvas.onLoad`：`PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))` + `PacketEvents.getAPI().load()`
- `HikariCanvas.onEnable`：`PacketEvents.getAPI().init()` 之后再 start WebServer / 注册命令
- `HikariCanvas.onDisable`：`PacketEvents.getAPI().terminate()`（包在 try/ignore 里，防止 init 失败时 disable 报错）

**架构纪律落地（PROPOSAL §5.2.6 / CLAUDE.md）：**
- 新增 `plugin/src/main/java/moe/hikari/canvas/deploy/MapPacketSender.java`
- 对外只有 `sendFullMap(Player, mapId, byte[128*128])` 一个方法——**所有对 `WrapperPlayServerMapData` 构造与 `sendPacket` 的调用都集中在此**。未来 PacketEvents 2.12.x（for Paper 26.x）的 wrapper 签名变动，只需要改这一个文件
- `WrapperPlayServerMapData` 构造签名（v2.11.2 实测得出）：`(int mapId, byte scale, boolean trackingPosition, boolean locked, @Nullable List<MapDecoration> decorations, int columns, int rows, int x, int z, byte @Nullable [] data)`
- PROPOSAL §5.2.5 文档示例用的是 NMS 类名 `ClientboundMapItemDataPacket(mapId, scale, locked, null, data)` 和 `MapData(...)`——与真实 PacketEvents 2.x API 不一致，待后续单独补 PR 修正（契约/实现一致性）

**命令注册（Paper 1.21 Brigadier，新格式）：**
- `HcCommand` 通过 Paper 的 `LifecycleEvents.COMMANDS` 挂 `/hc` 根节点
- `requires(src -> src.getSender() instanceof Player)` — 允许非 OP 玩家使用（M1 demo 需要）
- `/hc give`：插件代码直接 `Bukkit.createMap(world)` + 清空默认 renderer + `inventory.addItem`，让玩家无需 OP 就能拿到一张空白 canvas
- `/hc paint`：整张 128×128 填 palette index 18（红色）→ `MapPacketSender.sendFullMap`
- 测试流程无摩擦：连 127.0.0.1 → `/hc give` → `/hc paint` → 地图变红

**测试服便利性调整（`plugin/run/server.properties`）：**
- `online-mode=false`（任意用户名登录，本地测试场景）
- `gamemode=creative`（能飞、物品框任意摆）
- `spawn-protection=0`（允许在出生点附近放物品）
- 这些都是 `plugin/run/` 下的 runtime 文件，已被 `run/` gitignore 规则排除，不入仓库

**实测结论（用户自测）：**
- Paper 启动日志看到 PacketEvents banner（"build: 2.11.2"）+ `HikariCanvas enabled` + `Done (7.3s)!`
- 客户端进服、`/hc give`、`/hc paint` 一气呵成，地图**可视区域肉眼变红**
- 全程 server console 无 exception / Caused by
- `Painted map #<id> red (palette=18)` 聊天提示到位

**留给后续任务：**
- PROPOSAL §5.2.5 的代码示例要更新为真实 PacketEvents API（契约/实现一致性，T7 前或单独一次 doc PR）
- 预览地图池本身（pool borrow/return、PERMANENT 标记、SQLite）是 M2 的事；T5 只是验证了「直接发包能改像素」这个底层能力
- 调色板 LUT（RGB → palette index）属于 M4 渲染引擎，T5 用的硬编码 index 18 只是 demo

**关联文件：** `plugin/build.gradle.kts`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/MapPacketSender.java`、`plugin/src/main/java/moe/hikari/canvas/command/HcCommand.java`、`docs/journal.md`

---

## 2026-04-19 · M1-T4 Javalin HTTP + WebSocket 最小实现

**范围：** 在插件里起一个 Javalin 7 服务，`/ws` 端点接受 WebSocket 握手；消息信封遵循 `docs/protocol.md` §2；实现 `ping`→`pong`、未知 op → `error`

**技术选型踩坑与澄清：**
- paperweight-userdev 2.0 不再做 reobf 也不再负责合并 implementation 依赖 → 需要自己引 **shadow 插件**把 Javalin/Jetty/Jackson 打进胖 jar
  - 用 `com.gradleup.shadow` **9.4.1**（旧 `com.github.johnrengelman.shadow` 已 fork 为 GradleUp 新品牌）
  - `tasks.jar.enabled = false` + `shadowJar { archiveClassifier.set("") }`：让 shadowJar 独占输出名，避免与默认 jar 冲突
  - `runServer.pluginJars.from(shadowJar.flatMap { it.archiveFile })`：告诉 run-paper 用胖 jar
- Javalin **6 → 7 API 重大调整**，一路踩坑（以下是实测得出的正确签名）：
  - `app.ws(path, cfg)` 移除，Javalin 主类不再暴露路由方法
  - 配置类分裂为 `cfg.router`（RouterConfig，只含 `contextPath` 等配置字段）和 `cfg.routes`（RoutesConfig，路由注册入口）——名字近似但职责完全不同
  - 正确写法：`cfg.routes.addWsHandler(WsHandlerType.WEBSOCKET, "/ws", wsCfg -> {...})`
  - `cfg.startup.showJavalinBanner = false`（原来在根级）
  - `cfg.jsonMapper(new JavalinJackson().updateMapper(...))` 用于全局 `JsonInclude.Include.NON_NULL`（替代 POJO 上的 `@JsonInclude`）
- **Jackson 不在 Javalin 编译 classpath**（runtime 自带，compile 期不可见），需显式 `implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")`
- 教训：Javalin 7 的用户指南页面和若干博客示例仍在流传 v6 语法；以 **`javap -public` 看真实 class 文件** 是解决这类 API 对齐问题的最快路径

**改动：**
- `plugin/build.gradle.kts`：引入 `com.gradleup.shadow:9.4.1`；`implementation io.javalin:javalin:7.1.0`；`implementation com.fasterxml.jackson.core:jackson-databind:2.18.2`；`jar.enabled=false`；`shadowJar { archiveBaseName="HikariCanvas"; archiveClassifier=""; mergeServiceFiles() }`；`assemble.dependsOn(shadowJar)`；`runServer.pluginJars.from(shadowJar)`
- `plugin/src/main/java/moe/hikari/canvas/web/Envelope.java`：record 实现协议信封 `{v, op, id, ts, payload}`，提供 `of/pong/error` 工厂方法；不用 `@JsonInclude` 注解（改由 mapper 全局 `NON_NULL` 策略）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`：Javalin 7 服务封装；`start(host, port)` / `stop()`；`/ws` onConnect/onClose/onMessage/onError；`ping`→`pong`；未知 op → `error(INVALID_OP, ...)`；payload 反序列化失败 → `error(INVALID_PAYLOAD, ...)`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：`onEnable` 构造并启动 `WebServer`（`127.0.0.1:8877` 硬编码，待 config.yml）；`onDisable` 停

**验证：**
- `./gradlew :plugin:shadowJar` 成功，胖 jar 9.4 MB / 5388 entries，含 `io/javalin/**` `com/fasterxml/jackson/**` `org/eclipse/jetty/**` `moe/hikari/canvas/**` 与 `paper-plugin.yml`
- runServer 启动日志依次出现：`WebServer listening on 127.0.0.1:8877` → `HikariCanvas enabled (skeleton)` → `Done (7.017s)!`
- `curl` 手动发 WS upgrade（`Upgrade: websocket` + `Sec-WebSocket-Key`）→ 服务器响应 `HTTP/1.1 101 Switching Protocols` + 正确的 `Sec-WebSocket-Accept`，说明 Javalin 7 + Jetty 在 Paper classloader 里完整可用
- SIGTERM → `HikariCanvas disabled`；`ClosedChannelException` 是 curl 超时关连接的正常错误，不代表 bug

**留到后续任务：**
- 消息级 round-trip（`{op:"ping"}`→`{op:"pong"}`）的完整验证留给 T6（有 HTML 前端后）/T7（端到端）
- auth 帧 / 会话 token / 预握手 `GET /api/session/:token` 暂未实现——M2 阶段再做
- 依赖包没做 relocate，Paper classloader 里如与其他插件的 Javalin/Jetty 冲突再处理

**关联文件：** `plugin/build.gradle.kts`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/web/Envelope.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`docs/journal.md`

---

## 2026-04-19 · M1-T3 runServer 可用 + 插件生命周期验证

**范围：** 在本地用 `./gradlew :plugin:runServer` 起一个 Paper 测试服，验证我们的 jar 能被正确 load/enable/disable

**关键认识：** paperweight-userdev 2.0 **不再自带 `runServer` task**。通过 PaperMC 官方 test-plugin 的 `build.gradle.kts` 发现他们用 `xyz.jpenilla.run-paper` 3.0.2（同系列还有 `run-velocity` / `run-waterfall`）。引入这个插件即可。

**改动：**
- `plugin/build.gradle.kts`：
  - 新增插件 `id("xyz.jpenilla.run-paper") version "3.0.2"`
  - `runServer { minecraftVersion("1.21.11") }`
  - `doFirst` 自动写 `run/eula.txt` 为 `eula=true`（首次跑会被 Paper 初始化为 `eula=false`，卡住启动；加此 hook 后幂等、下次 `clean` 后也能自动复活）。`logger.lifecycle` 会明示"已接受 Mojang EULA"，不做无声操作。
- `.gitignore` 新增 `.claude/`（Claude Code 产物，不入仓库）

**验证（从 runServer 日志中直接引用）：**
- `[HikariCanvas] HikariCanvas enabled (skeleton)` — `onEnable` 触发
- `Done (7.959s)! For help, type "help"` — Paper 1.21.11 build #130 启动完成
- `SIGTERM` 后 `[HikariCanvas] HikariCanvas disabled` — `onDisable` 也正确触发
- Gradle 的 `BUILD FAILED`（exit 143 = 128 + 15）是我主动 kill 导致，**不代表 Paper 或插件异常**

**路径与 gitignore：**
- run-paper 默认工作目录 `plugin/run/`，已被现有 `run/` 规则 ignored（不带 `/` 前缀匹配任何深度）
- Paper server jar 由 run-paper 缓存在 `~/.gradle/caches/run-task-jars/paper/jars/1.21.11/130.jar`，不进项目目录

**关联文件：** `plugin/build.gradle.kts`、`.gitignore`、`docs/journal.md`

---

## 2026-04-19 · M1-T2 插件主类 + paper-plugin.yml

**范围：** 最小可 load 的 Paper 插件（skeleton），先把 `./gradlew build` 出 jar 的链路跑通；功能逻辑留给后续任务

**改动：**
- 新增 `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：继承 `JavaPlugin` 的骨架主类，`onEnable` / `onDisable` 只打 log
- 新增 `plugin/src/main/resources/paper-plugin.yml`（用新格式，非 `plugin.yml`）：
  - `name: HikariCanvas` / `main: moe.hikari.canvas.HikariCanvas` / `api-version: '1.21'`
  - `authors: [HaruHyacinth]` / 描述 / 仓库 URL
  - 暂不声明 `bootstrapper:` / `loader:` / `dependencies:` / `commands:`——T5 加 `/hc` 命令时回填 commands（也可能用 Brigadier API 运行时注册，不放 yml）
- `./gradlew :plugin:build` 首次 SUCCESSFUL（`3m 1s`，其中 `paperweightUserdevSetup` 约 2m 51s——一次性缓存，后续 incremental 秒级）
- jar 产物 `plugin/build/libs/HikariCanvas-0.1.0-SNAPSHOT.jar`（1.4 KB）内含 `moe/hikari/canvas/HikariCanvas.class` + `paper-plugin.yml`

**验证通过的关键事项：**
- paperweight-userdev 2.0.0-beta.21 在 Gradle 9.4.1 + Java 25 launcher 上能完成 vanilla server download / mapping remap / mache sources / paperclip patch / devBundle patches 全套 setup
- Java 21 toolchain 自动拉下来并用于编译（日志虽未显式 print 但 `options.release = 21` 生效）
- Mojang mappings 输出路径通，未触发任何 reobf 任务（符合 CLAUDE.md 架构纪律 §3）

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/resources/paper-plugin.yml`、`docs/journal.md`

---

## 2026-04-19 · M1-T1 Gradle 多模块骨架 + 本地目录错乱修复

**范围：** 搭起 Gradle 骨架并修复本地目录名与远端不一致的历史遗留

**背景与修复：** 开工前发现本地仓库真实路径是 `/Users/haru/Desktop/项目/HikariBetterText 2/`（改名时未同步目录名；" 2" 后缀疑似 Finder 冲突自动加的），而 Claude Code 环境声明的 primary dir `/Users/haru/Desktop/项目/HikariCanvas/` 是一个空壳。Gradle 骨架一度写到空壳里。已将 Gradle 文件挪回真实仓库，删除空壳，将真实仓库重命名为 `HikariCanvas`。本地路径、远端、CLAUDE.md 声明、包名终于对齐。

**改动：**
- 新增 `settings.gradle.kts`（`rootProject.name = "hikari-canvas"`；`include("plugin")`）
- 新增根 `build.gradle.kts`（`group = "moe.hikari"`；`version = "0.1.0-SNAPSHOT"`）
- 新增 `plugin/build.gradle.kts`：
  - `io.papermc.paperweight.userdev` `2.0.0-beta.21`（官方唯一支持最新版；Mojang mappings 输出）
  - Java 21 toolchain；UTF-8 编码；`options.release = 21`
  - `paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")`
  - `mavenCentral()` 为唯一仓库（PacketEvents / codemc 等仓库待 T4/T5 按需加）
  - jar `archiveBaseName = "HikariCanvas"`
- 生成 `gradlew` / `gradlew.bat` / `gradle/wrapper/`（wrapper 锁 Gradle 9.4.1）
- 本地装了 `gradle` 9.4.1（brew install）用于一次性生成 wrapper；日常使用走 `./gradlew`
- `./gradlew :plugin:dependencies --configuration compileClasspath` sync 成功，`io.papermc:mache:1.21.11+build.1` 与 Paper 完整依赖树（netty 4.2.7、brigadier 1.3.10、datafixerupper 9.0.19、fastutil 8.5.18 等）均解析

**未做（按增量原则留给后续任务）：**
- PacketEvents / Javalin / SQLite / JDBI / HikariCP / SnakeYAML 等依赖——T4/T5 按需加
- `runServer` / configuration cache / JVM 参数优化——T3 处理
- `web/` 子模块——T6 处理
- Java 源码与 `paper-plugin.yml`——T2 处理

**关联文件：** `settings.gradle.kts`、`build.gradle.kts`、`plugin/build.gradle.kts`、`gradle/wrapper/gradle-wrapper.{jar,properties}`、`gradlew`、`gradlew.bat`、`docs/journal.md`

---

## 2026-04-19 · 锁定版本基线 + 新增 26.x 升级纪律 + PROPOSAL 遗留 bug

**范围：** 版本调研确认当前稳定组合；PROPOSAL 与 CLAUDE.md 同步具体版本号与架构纪律

**背景：** 我的知识截止早于当前时间，且 MC 于 2026 起改版本命名（`YY.D.H`）。用户提示最新 26.1 不稳定，故通过 WebSearch/WebFetch 核实：Paper 稳定为 1.21.11；PacketEvents 1.21.x 专属最终版为 2.11.2；Javalin 6 已过时（7.1.0 最新）；paperweight-userdev 官方唯一支持 2.0.0-beta.21；Gradle 9.4.1。

**改动：**
- `PROPOSAL.md` §5.1 后端技术栈表：新增「版本」列并填入 Java 21 / Paper 1.21.11 / Gradle 9.4.1 / userdev 2.0.0-beta.21 / PacketEvents 2.11.2 / **Javalin 6 → 7.1.0**
- `PROPOSAL.md` 新增 §5.2.6「向 Paper 26.x 的平滑升级策略」：说明 26.1 移除 Spigot 重映射的影响；列明三条架构纪律（禁 NMS / PacketEvents 调用集中到 `MapPacketSender` / Mojang mappings 输出）；列明未来升级时需改动的文件清单
- `PROPOSAL.md` §5.3 项目结构遗留 bug：`java/moe/hikari/bettertext/` → `java/moe/hikari/canvas/`（改名那次未清干净）
- `PROPOSAL.md` §7 风险表「Paper API 版本变动」扩展表述，关联 §5.2.6 纪律
- `CLAUDE.md` 技术栈从短列表改为版本锁定表，明确每一项具体版本
- `CLAUDE.md` 新增「架构纪律（26.x 升级保障）」小节，与 PROPOSAL §5.2.6 对应

**关联文件：** `PROPOSAL.md`、`CLAUDE.md`、`docs/journal.md`

---

## 2026-04-19 · CLAUDE.md 首版 + M1 技术选型拍板

**范围：** 为仓库补充工程规范入口文件；固化 M1 前期决策

**改动：**
- 新增根目录 `CLAUDE.md`：项目标识、技术栈、契约文档清单、文档先行规则、Git 提交约定、不可越界的技术决策、里程碑
- 拍板 M1 前的几项技术选型（记录在此以便未来追溯）：
  - 前端 M1~M4 只写原生 DOM + Vite + TypeScript，Vue 3 + Konva + Pinia 推迟到 M5 引入（避免 M1 端到端验证搭冗余骨架）
  - 插件描述文件用 `paper-plugin.yml`，不用旧格式 `plugin.yml`
  - 本地测试服使用 `paperweight-userdev` 的 `./gradlew runServer`
  - M1 的双端 snapshot 测试台**按 PROPOSAL §5.2.1 / §7 原计划保留**——M1 阶段就搭起测试基础设施，即使首轮只比对固定形状/纯色

**关联文件：** `CLAUDE.md`（新）、`docs/journal.md`

---

## 2026-04-19 · 项目改名 HikariBetterText → HikariCanvas

**范围：** 全局重命名 + 新增 journal.md + .gitignore + git 初始化

**改动：**
- 全局替换文档里的项目标识：
  - `HikariBetterText` → `HikariCanvas`
  - `moe.hikari.bettertext` → `moe.hikari.canvas`
  - `hikari_better_text` (PDC 命名空间) → `hikari_canvas`
  - `HbtCommand` → `HcCommand`
  - `/hbt` (游戏内命令) → `/hc`
  - `hbt.` (权限节点前缀) → `hc.`
  - `hbt:` (PDC key 前缀) → `hc:`
  - `.hbt` (工程导出文件扩展名) → `.canvas`
- 新增本文件 `docs/journal.md`
- 新增根目录 `.gitignore`
- 初始化 git 仓库，创建 GitHub `HyacinthHaru/HikariCanvas` 公开仓库并首次推送

**关联文件：** `PROPOSAL.md`、`docs/architecture.md`、`docs/protocol.md`、`docs/rendering.md`、`docs/template-spec.md`、`docs/data-model.md`、`docs/security.md`、`docs/journal.md`（新）、`.gitignore`（新）

---

## 2026-04-19 · 立项期 6 份契约文档完成

**范围：** 所有立项期需要的设计文档一次性写完

**改动：**
- `docs/architecture.md`：系统架构总览、组件分层、编辑会话生命周期状态机、预览地图池机制（核心）、实时投影管线、双端渲染一致性原则、墙面识别、持久化分层、Web 服务层、关键非功能需求、配置骨架
- `docs/protocol.md`：浏览器 ↔ 插件 WebSocket 协议 v1；消息信封格式、连接生命周期、请求/响应模型、所有 op 类型、错误模型（应用层 error + WS close 码）、工程状态模型 TypeScript 定义、完整交互示例、限流、版本化规则
- `docs/rendering.md`：渲染五层管线（Layout / Rasterize / Composite / Quantize / Slice）、字体管理（同 TTF 双端）、排版算法、Graphics2D 与 Canvas 必设项、效果（描边/阴影/发光）实现、调色板 LUT 预生成、CIE76 距离、透明处理、双端 snapshot 测试规范与 CI 要求
- `docs/template-spec.md`：模板 YAML 格式 spec v1；顶层字段、canvas 定义、layout 三种类型、text/rect/line/icon 元素、参数系统类型表、表达式子集、实例化语义、版本兼容规则、完整示例
- `docs/data-model.md`：SQLite schema（schema_version / pool_maps / sign_records / audit_log / template_usage 五表）、PDC key 约定、`.canvas` 工程文件 zip 格式、配置字段约束、迁移策略、一致性不变式与修复
- `docs/security.md`：威胁模型 T1~T13、Token 机制与防暴力、WebSocket 安全、输入校验白名单、权限节点表、部署安全建议（nginx 配置）、审计事件清单、依赖安全、响应渠道

**关联文件：** `docs/*.md`

---

## 2026-04-19 · 网页优先方向确认 + PROPOSAL 大改

**范围：** 核心交互从「命令 + GUI」转为「网页编辑器 + 实时投影」，立项文件随之重写

**改动：**
- `PROPOSAL.md`：
  - 核心创新 3.3 改为「网页编辑器 + 实时投影」
  - v1 MVP 整体替换为「Canva 式完整编辑器 + 实时投影链路 + 预览地图池 + 命令辅助」
  - 新增 5.2 关键机制：双端渲染一致性、预览地图池、帧率策略（静止 0fps / 输入防抖 100ms + 5fps 上限 / 提交全量）、脏矩形差分、WebSocket permessage-deflate
  - 网络绑定默认 `127.0.0.1`，公网部署必须反代 + TLS
  - 技术栈加前端（Vue 3 + Vite + Konva + TypeScript）和 Javalin / PacketEvents / SQLite
  - 项目结构改多模块（`plugin/` + `web/`）
  - 里程碑重排为 M1~M7，总工期 3.5 个月
  - 风险表新增：池机制缺陷、双端一致性、公网暴露、服主端口不足等

**关联文件：** `PROPOSAL.md`

---

## 2026-04-18 · 立项初稿

**范围：** 项目从 0 到 1 的立项讨论与初稿

**改动：**
- 新建 `PROPOSAL.md` 作为立项文件，定义背景、定位、创新点、功能范围、技术栈、里程碑、风险、成功标准
- 讨论并确认定位：避开 `text_display` 的普通文字场景，主打像素风 / 艺术字 / 大招牌
- 确认三大创新点：内置渲染、模板化、分阶段交互
- 初版里程碑：M1~M6，工期约 2 个月（后续因网页优先方向调整）

**关联文件：** `PROPOSAL.md`（新）
