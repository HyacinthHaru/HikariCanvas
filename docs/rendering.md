# 渲染规范

**状态：** 立项稿 v0.1 · 2026-04-19
**适用范围：** 后端 Java Graphics2D 渲染器 + 前端 Canvas 预览渲染器

本文档定义从工程状态 (`ProjectState`) 到像素输出的完整渲染管线。**前后端两套代码必须按相同规则实现**，并通过 CI snapshot 测试保证输出像素级一致。

---

## 1. 渲染目标

输入：`ProjectState`（§ protocol.md §7）
输出：`widthMaps × heightMaps` 张 128×128 的调色板索引位图（`byte[]`，每字节一个 MC 调色板索引）

整个管线由五层组成：

```
ProjectState
   │
   ▼
[Layout]    计算每个元素在大画布上的最终位置、字形字符集
   │
   ▼
[Rasterize] 每个元素栅格化为 RGBA 像素（大画布上合成）
   │
   ▼
[Composite] 背景 + 图层按 z-order 合成为单张 RGBA 画布
   │
   ▼
[Quantize]  RGBA → MC 调色板索引（`byte[W*H]`）
   │
   ▼
[Slice]     按 128×128 切片，每片对应一张 map
   │
   ▼
List<MapBitmap>   输出
```

---

## 2. 字体管理

### 2.1 字体文件

- 后端：`src/main/resources/fonts/*.ttf`（或 `.otf`），经 `processResources` 进入 shadow jar
- 前端：`web/public/fonts/*.woff2`，由 TTF 通过 `woff2_compress` 生成（M5 前端渲染器接入时一起做）
- **必须从同一源 TTF 产出**，构建脚本中校验 hash

### 2.1.1 分发策略（M4 定稿 · 方案 A）

**两类字体，两种分发路径：**

| 字体 | 协议 | 文件 | 大小 | 分发 |
|---|---|---|---|---|
| **Ark Pixel 12px Monospaced zh_cn** | SIL OFL | `ark-pixel-12px-monospaced-zh_cn.ttf` | ~200 KB | 直接入 git `plugin/src/main/resources/fonts/` |
| **Source Han Sans SC Regular**（思源黑体） | SIL OFL | `SourceHanSansSC-Regular.otf` | ~15 MB | **Gradle `downloadFonts` 任务** 从官方 Release 抓到 `build/downloaded-fonts/`；`processResources` 合并到 jar；SHA-256 校验；`.gitignore` 排除 |

**理由：**
- 仓库保持纤瘦（<500 KB），`git clone` 快
- shadow jar 对终端用户仍然一步到位（`./gradlew shadowJar` 后 jar 里字体齐全）
- SHA-256 固定值内嵌 build script，任何篡改都会让 build 失败
- 两字体**均为 SIL OFL 1.1**，可合法 redistribute

**Gradle 任务轮廓**（M4-T3 实现）：
```kotlin
val fontsDir = layout.buildDirectory.dir("downloaded-fonts")
val downloadFonts by tasks.registering {
    outputs.dir(fontsDir)
    doLast {
        download(
            url = "https://github.com/adobe-fonts/source-han-sans/raw/release/OTF/SimplifiedChinese/SourceHanSansSC-Regular.otf",
            dest = fontsDir.get().file("SourceHanSansSC-Regular.otf").asFile,
            sha256 = "..."
        )
    }
}
tasks.processResources { dependsOn(downloadFonts); from(fontsDir) { into("fonts") } }
```

**其他字体：** 服主可放到 `plugins/HikariCanvas/fonts/` 并在 `config.yml` 里注册 `fontId`（见 §2.2）；运行时 `FontRegistry` 会优先找外部目录、fallback 到 jar 内置。

### 2.2 字体 ID 与声明

```yaml
# config.yml
fonts:
  sourcehan:
    file: "fonts/SourceHanSansSC-Regular.otf"
    display-name: "思源黑体"
    pixelated: false    # 标记此字体适合的渲染模式
  ark-pixel-12:
    file: "fonts/ark-pixel-12px-monospaced-zh_cn.ttf"
    display-name: "方舟像素 12px"
    pixelated: true
    native-size: 12     # 该像素字体的设计尺寸
```

### 2.3 加载规则

- **后端**：启动时 `Font.createFont(TRUETYPE_FONT, stream)`，缓存 `Map<String, Font>`
- **前端**：通过 CSS `@font-face` + `document.fonts.load()` 预加载，页面就绪前不渲染

### 2.4 字号语义

- 字号单位统一为**像素（px）**，不使用磅（pt）
- 字号数值即 `Font.deriveFont(size)` 的参数
- 浏览器：`ctx.font = \`${size}px \"${fontFamily}\"\``

**像素字体警告：** 若 `pixelated=true` 且用户字号 ≠ `native-size` 的整数倍，后端必须用**最近邻缩放**而非字体自缩放；前端同理 `image-rendering: pixelated`。

---

## 3. 排版（Layout）

### 3.1 文本行切分

输入：`text`、`fontSize`、`w`（文本框宽度）、`letterSpacing`

算法：
1. 按 `\n` 切为硬换行段
2. 每段按字符逐个累加宽度（`Font.getStringBounds().getWidth()`）
3. 超出 `w` 时回溯到最近的**软换行点**插入换行
4. 软换行点定义：
   - 空白字符前（含全角空格 U+3000）
   - CJK 字符之间任意位置可换行
   - 禁则：行首不允许 `）】」』。，、？！：；` 等标点
5. 无软换行点时强制在当前位置截断

### 3.2 基线与行高

- 每行高度 = `fontSize × lineHeight`（`lineHeight` 默认 1.2）
- 基线位于每行顶 + `fontSize × ascentRatio`，`ascentRatio` 固定 0.8（跨字体统一，牺牲精确性换一致性）
- 首行顶贴文本框顶部（`y = 0`）

### 3.3 竖排

**状态：M4-T5 未实装，推迟到 M4.5 / M7**
（理由：旋转标点规则 + 换列方向 + 与横排 layout 复用的成本在 M4 本身就偏重；单独做更稳。M3 / M4 期间 `vertical: true` 在 EditSession 字段保留但渲染按 `false` 处理并 log WARN 一次。）

`vertical: true` 时（实装后）：
1. 字符从上到下排列
2. 全角标点（`。` `，` `！` `？` `：` `；` `“` `”` `（` `）` 等）**旋转 90°** 或替换为竖排对应字符
3. 半角字符不旋转，保持横向
4. 换列方向：右 → 左（CJK 传统）或 左 → 右（现代）可配置，默认右 → 左

### 3.4 对齐

`align: "left" | "center" | "right"`：文本框内水平对齐（对每一行分别应用）。
竖排下 `align` 语义变为顶部/中部/底部对齐（随 §3.3 一并推迟到 M4.5 / M7）。

### 3.5 letterSpacing

字符间距 = `letterSpacing`（px），可为负。应用于字符**之间**，首尾不加。

---

## 4. 栅格化（Rasterize）

### 4.1 RGBA 临时画布

- 整个工程渲染到单张大画布：`widthMaps × 128 × heightMaps × 128` 像素的 `BufferedImage TYPE_INT_ARGB`（Java）/ `ImageData`（JS）
- 背景先填充 `canvas.background`
- **v2 起：分层渲染。** 对每个 visible+unlocked 的 layer：
  1. 分配同尺寸 ARGB layer-buffer
  2. 在 layer-buffer 上按层内 z-order 绘制每个 visible element（element 自己的 opacity + blendMode 在此层 buffer 内生效）
  3. 用 `layer.opacity` + `layer.blendMode` 把 layer-buffer 合成到主 buffer
- 最终主 buffer 走 §6 量化

详细 opacity / blendMode 公式见 §6.5 / §6.6。grid / guides **不参与栅格化**（只在前端预览叠加层画，详见 §4.5）。

### 4.5 网格与参考线（v2 引入，仅前端）

`canvas.gridSize > 0` 时在编辑器 Canvas 上叠加网格（每 N 像素一条浅灰线）。`canvas.guides[]` 渲染为绿/紫细线。两者：

- **不**进入 §4.1 主 buffer
- **不**参与 §6 量化
- **不**经过 §7 切片
- 游戏内 MC 地图看不到这两样

实现：CanvasView 在主 `<canvas>`（渲染主 buffer）之上加一个独立的 `<canvas>` overlay，每帧重画网格 + guides 线条。fly-out 标尺供拖出新 guide，guides[] 改动经 `canvas.guides.set` op 同步到服务端持久化。

### 4.2 Graphics2D 必设项（Java）

```java
Graphics2D g = image.createGraphics();
g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_OFF);
g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_OFF);
g.setRenderingHint(KEY_RENDERING, VALUE_RENDER_SPEED);
g.setRenderingHint(KEY_FRACTIONALMETRICS, VALUE_FRACTIONALMETRICS_OFF);
g.setRenderingHint(KEY_STROKE_CONTROL, VALUE_STROKE_PURE);
```

### 4.3 Canvas 必设项（浏览器）

```typescript
ctx.imageSmoothingEnabled = false;
ctx.textRendering = "geometricPrecision";
// 禁用 subpixel：CSS
// font-smooth: never; -webkit-font-smoothing: none;
```

### 4.4 绘制元素

**TextElement：**
```
for each line:
    for each char:
        g.setColor(color)
        g.drawString(char, x + offset, baselineY)
        offset += charWidth + letterSpacing
```

**RectElement：**
```
g.setColor(fill)
g.fillRect(x, y, w, h)
if stroke:
    g.setColor(stroke.color)
    g.setStroke(new BasicStroke(stroke.width))
    g.drawRect(x, y, w, h)
```

**ImageElement（M13）：**
```
// 1. 加载缓存 BufferedImage（按 hash 从 plugins/HikariCanvas/uploads/<hash>.png）
BufferedImage img = imageStorage.load(e.source);
if (img == null) {
    drawIconStylePlaceholder(g, e);  // 同 IconElement 文件缺失占位（虚线框 + ?）
    return;
}

// 2. 旋转（同其他元素，已在 drawElementsTo 外层 translate-rotate）

// 3. mask 处理（M13 锁定决策：mask 是 SVG path d，相对 (0, 0)..(w, h)）
Shape originalClip = g.getClip();
if (e.mask != null) {
    Path2D maskPath = PathParser.parse(e.mask.d).path();   // 复用 M9 PathParser
    // mask 坐标相对 element bbox → 绝对坐标变换
    AffineTransform tx = new AffineTransform();
    tx.translate(e.x, e.y);
    Shape maskShape = tx.createTransformedShape(maskPath);
    if (e.mask.inverted) {
        // 反相 mask：用整张元素 bbox 减去 mask 形状
        Area area = new Area(new Rectangle2D.Double(e.x, e.y, e.w, e.h));
        area.subtract(new Area(maskShape));
        g.clip(area);
    } else {
        g.clip(maskShape);
    }
}

// 4. 真正 drawImage（bbox 内拉伸到 e.w × e.h）
g.drawImage(img, e.x, e.y, e.w, e.h, null);

g.setClip(originalClip);
```

**M13 mask × dither 顺序（已锁）：**

如 element.renderMode === 'dither'：drawElementsTo 走的是 per-element off-buffer 路径（M11-B）→ `drawElementBody` 完整跑（含 mask clip）→ 整个 element buffer 跑 `BayerDither.apply` → blend 回主 graphics。所以 dither 在 mask **内部** 像素，mask 外像素本就透明，dither 不影响（"先 dither 再 mask"语义实际由 per-element buffer 结构自然达成）。

---

## 5. 效果

### 5.1 描边（Stroke）

对每个文字字形：
1. 计算字形路径（`Font.createGlyphVector().getOutline()`）
2. 用 `BasicStroke(width)` 画该路径（描边色）
3. 再用 `fill` 画该路径（字形色）

**前端实现：** `ctx.strokeText` → `ctx.fillText`，`lineJoin = "round"`，`lineWidth = width`。

### 5.2 阴影（Shadow）

不用 Graphics2D/Canvas 内置 shadow，自实现保持双端一致：
1. 渲染一份**只有字形的 mask**（纯黑 RGBA）
2. 将 mask 偏移 `(dx, dy)`、着色 `shadow.color` 画到主画布上
3. 再画正常字形于主画布

### 5.3 发光（Glow）

1. 渲染字形 mask
2. 对 mask 做半径 `glow.radius` 的盒式模糊（**自实现**，不用系统高斯模糊以避双端差异）
3. 着色 `glow.color` 画到主画布
4. 画正常字形于主画布

**盒式模糊算法：** 水平 + 垂直两次 radius 长的均值滤波，两端固定实现，`rendering-test/glow-*.png` 作为 snapshot 基准。

### 5.4 效果顺序

每个字形的渲染顺序（从后到前）：
1. 发光
2. 阴影
3. 描边
4. 字形填充

---

## 6. 调色板量化（Quantize）

### 6.1 MC 地图调色板

Minecraft 地图使用固定调色板：64 种基础颜色 × 4 个明度 = 256 索引（部分未使用）。定义在 `net.minecraft.world.level.material.MaterialColor` 或 Paper 的 `MapPalette`。

本项目**不依赖运行时 API 反射**，而是在构建期从 Mojang 映射表生成静态 `palette.json`：

```json
[
  { "index": 0,   "rgb": [0, 0, 0],       "alpha": 0 },
  { "index": 8,   "rgb": [89, 125, 39],   "alpha": 255 },
  { "index": 9,   "rgb": [109, 153, 48],  "alpha": 255 },
  ...
]
```

后端打包时内嵌，前端同文件通过 `/api/palette` 下发。

### 6.2 查找表（LUT）

朴素做法：对每个像素遍历 256 索引算欧氏距离 → O(W·H·256)。太慢。

**改进：** 预生成三维 LUT
```
byte[32][32][32] lut;   // RGB 各 5-bit 量化
for r in 0..31: for g in 0..31: for b in 0..31:
    lut[r][g][b] = findNearest(expandToRGB(r, g, b))
```

查询 O(1)，LUT 大小 32 KB，启动期构建一次。

### 6.3 距离度量

使用 **CIE76 近似（Lab）** 比 RGB 欧氏距离更符合视觉。Lab 转换公式内嵌常量，禁止引入 OpenCV 等重依赖。

若 `render.palette-strategy: "fast"`（配置）则退回 RGB 欧氏，性能优先场景使用。

### 6.4 透明处理

- `alpha < 128` 的像素 → 调色板索引 `0`（完全透明，MC 地图 transparent）
- `alpha ≥ 128` 的像素 → 按 RGB 查 LUT；忽略半透明

不支持半透明像素（MC 地图原生不支持 alpha）。文字的半透明抗锯齿边缘也会被硬截断——这是必须关抗锯齿的另一原因。

### 6.5 元素级 opacity（M8 协议 v2 引入）

`element.opacity ∈ [0, 1]`、`layer.opacity ∈ [0, 1]`。MC 调色板**不支持半透明**，所以语义不是"真透明"而是**"颜色变浅"**：

- 渲染流程：layer 内每个 element 用其 `opacity` 与 layer buffer 做 alpha-composite；layer 自身再用 `layer.opacity` 与下一层 buffer 做 alpha-composite
- 最终主 buffer 走 §6.4 硬截断量化
- 结果：`opacity = 0.5` 的红色 `(255, 0, 0)` 落在白底上变成 `(255, 128, 128)` → 量化后是某个粉色调色板索引

**双端契约：** Java `AlphaComposite.SrcOver` + 前端 `globalAlpha`。两边算出来的 RGB **应该位级一致**（线性 alpha 公式相同），量化后必然像素一致。

**用户视角：** opacity 是"褪色"工具，不是"半透明"工具。docs/deployment.md / 编辑器 UI 都要明确告知。

### 6.6 BlendMode（M8 协议 v2 引入）

v1 选 4 个最常用：`normal / multiply / screen / overlay`。计算（对 normalized [0,1] RGB 各通道独立做）：

| 模式 | 公式 |
|---|---|
| normal | `src` |
| multiply | `base × src` |
| screen | `1 − (1 − base) × (1 − src)` |
| overlay | `base < 0.5 ? 2·base·src : 1 − 2·(1−base)·(1−src)` |

应用顺序：先 element.blendMode（与 layer buffer 合成）、再 layer.blendMode（与下一层 buffer 合成）。`normal` 是 baseline，不做任何额外计算。

**双端契约：** Canvas2D `globalCompositeOperation = 'multiply' | 'screen' | 'overlay'` 直接支持；Java Graphics2D 不直接支持，需要在 ARGB 缓冲上**逐像素**做合成（性能 ok，反正画布只有 8 张 map = 1024×512 像素以下）。

### 6.7 Dithering（M8 引入 renderMode 字段，M11 完整实施）

v2 起每个 element 有 `renderMode: 'clean' | 'dither'`，默认 `clean`。

**clean 模式**（现状）：直接走 §6.4 LUT 硬截断。文字 / 矩形 / 图标这类硬边几何，clean 看起来"干净像素艺术"。

**dither 模式**（M11 实施）：用 **Bayer 4×4 ordered dither** 在量化前对每个像素加一个空间相关的小扰动，让渐变 / 软笔锋的色阶过渡看起来连续。

Bayer 4×4 阈值矩阵：

```
 0  8  2 10
12  4 14  6
 3 11  1  9
15  7 13  5
```

对每个像素 `(x, y, r, g, b)`：

```
threshold = (BAYER[y%4][x%4] / 16 - 0.5) * stepSize
// stepSize ≈ 调色板平均色阶间距，~16 / 255
adjusted = (r + threshold, g + threshold, b + threshold)
// adjusted 走 LUT → 同一区域多次量化结果不同 → 视觉上是 ordered dither
```

**为什么 Bayer 而不是 Floyd-Steinberg**：
- Bayer 是**纯函数**：每像素独立 + (x,y) → 同一图任意区域多次渲染像素一致。FS 是误差扩散，要从左上角顺序扫，与浏览器/Java 并行优化冲突
- Bayer 双端 trivially 一致（4×4 矩阵 + 同 LUT 即可）；FS 容易在边界出现微小漂移
- 取舍：Bayer 有"网纹感"，FS 更"自然"。对像素艺术目标，网纹反而契合 MC 像素美学

**M8 阶段：** 只把 `renderMode` 字段存到协议 + state，渲染时 `clean / dither` 都走 §6.4 硬截断；M11 才真正接入 dither LUT。早做字段是为了避开协议二次升版。

---

## 7. 切片（Slice）

量化后的 `byte[W*H]` 按 `128×128` 网格切片：

```
for row in 0..heightMaps:
  for col in 0..widthMaps:
    sliceData = extract(quantized, col*128, row*128, 128, 128)
    mapId = mapIds[row * widthMaps + col]
    output.add(new MapBitmap(mapId, sliceData))
```

切片在 Java 端由 `Arrays.copyOfRange` 配合 stride 完成；在浏览器端只切到预览用的 Canvas，不输出像素（像素由服务器发到客户端）。

---

## 8. 双端一致性验证

### 8.1 Snapshot 测试台

`rendering-test/` 目录：

```
rendering-test/
├── fixtures/                      # 测试工程 JSON
│   ├── 001-hello-world.json
│   ├── 002-chinese-signs.json
│   ├── 003-effects.json
│   └── ...
├── expected/                      # 参考位图（人工审核确认过的）
│   ├── 001-hello-world.png
│   └── ...
├── java-runner/                   # 后端渲染测试入口
└── web-runner/                    # 前端渲染测试入口（Playwright）
```

CI 流程：
1. Java runner 渲染所有 fixture → `actual-java/*.png`
2. Web runner（Headless Chrome）渲染 → `actual-web/*.png`
3. 对比 `actual-java` vs `expected` 与 `actual-web` vs `expected`
4. 像素差异 > 0.5% → fail
5. 差异图输出到 CI artifact 方便人工审查

### 8.2 容忍度

- 像素字体（`pixelated=true`）：**0% 差异**
- 非像素字体在低字号（≤16px）：< 0.5%
- 非像素字体大字号：< 2%（仅限 hinting 差异）

### 8.3 修复流程

不一致问题修复的优先顺序：
1. 字体选择问题（确认同一 TTF）
2. 渲染提示未关抗锯齿
3. 基线/度量实现差异
4. 调色板 LUT 差异
5. 效果算法差异

每次修复后**更新 expected** 并提交 PR 审查。

### 8.4 Live Paint 例外（M18，2026-05-17）

Live Paint（油漆桶工具）的拓扑计算**仅在浏览器 Web Worker 跑**，后端 Java 不做任何镜像。
这是 §1 / §8 双端镜像纪律的**显式例外**，理由：

- 输出是 `PathElement.d`（SVG path），已经在 M9 双端镜像协议内；后端走常规 `PathRenderer` 渲染，与用户手画 / 工具栏画的 path 完全同路径
- 拓扑算法（element → polygon → polygon-clipping union/difference → gap polygons → SVG path d）**不参与最终像素输出**，仅作"工具输入辅助"——它把用户的鼠标点击位置解释成一条 PathElement.d 字符串，之后的渲染管线与该 element 是工具生成还是手画无区别
- Java AWT 无 planar subdivision / boolean polygon op 等价物（`Area` API 性能与精度都达不到 `polygon-clipping` 同等级）；强行镜像会引入 ~2000 行 Java 几何代码且仍可能与 TS 实现行为差异，**得不偿失**

实装位置 `web/src/livepaint/`（5 算法文件 + 1 worker + 1 composable + 1 hover overlay）；后端无对应代码。
退化几何 fallback：worker 返 `{gaps:[], degraded:true}` 时 UI 拒绝创建 PathElement 而非用错误数据落库。

---

## 9. 性能

### 9.1 目标

- 渲染 8×4 招牌（1024×512 像素）< 100ms
- 局部重渲染（脏矩形 128×128）< 10ms
- 调色板量化 1024×512 < 30ms

### 9.2 优化

- **增量重渲染**：EditSession 记录每个元素的前后包围盒，只重渲染受影响的区域
- **元素级缓存**：静态元素（未改动）缓存其 RGBA bitmap，改动时失效
- **调色板 LUT 复用**：进程内单例
- **多线程**：元素栅格化可并行（元素间无依赖），合成阶段串行

### 9.3 线程

- **渲染全部在异步线程**（插件专属 `ExecutorService`）
- 不访问 Bukkit API
- 完成后通过 `MapPacketSender` 发送，PacketEvents 内部线程安全

---

## 10. 边界条件

| 情形 | 行为 |
| --- | --- |
| 空文本 | 元素不渲染（但占位包围盒仍用于 layout） |
| 字号 ≤ 0 | 元素不渲染，校验阶段应已拦截 |
| 超大画布（> `limits.canvas-max-maps`） | confirm 阶段 WallResolver 拒绝（M5.5 起，校验在新建路径一次性做；现有 wall 即使配置改小也允许打开） |
| 元素超出画布边界 | 只绘制画布内部分，越界部分裁剪 |
| 字体不存在 | fallback 到默认字体并产生 `session.warning` |
| 调色板 LUT 未加载 | 渲染拒绝并 `INTERNAL_ERROR` |

---

## 11. 未决问题

- [ ] 非像素字体是否需要提供「强制像素化」选项（字号任意 → 量化到 1px 网格）
- [ ] Dithering 是否值得做（v1.0 不做，但预留配置）
- [ ] 效果组合（描边+阴影+发光）的性能 budget
- [ ] 中文字体的 emoji / 符号缺字处理（fallback chain）
