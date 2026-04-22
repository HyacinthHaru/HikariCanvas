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

`vertical: true` 时：
1. 字符从上到下排列
2. 全角标点（`。` `，` `！` `？` `：` `；` `“` `”` `（` `）` 等）**旋转 90°** 或替换为竖排对应字符
3. 半角字符不旋转，保持横向
4. 换列方向：右 → 左（CJK 传统）或 左 → 右（现代）可配置，默认右 → 左

### 3.4 对齐

`align: "left" | "center" | "right"`：文本框内水平对齐。
竖排下 `align` 语义变为顶部/中部/底部对齐。

### 3.5 letterSpacing

字符间距 = `letterSpacing`（px），可为负。应用于字符**之间**，首尾不加。

---

## 4. 栅格化（Rasterize）

### 4.1 RGBA 临时画布

- 整个工程渲染到单张大画布：`widthMaps × 128 × heightMaps × 128` 像素的 `BufferedImage TYPE_INT_ARGB`（Java）/ `ImageData`（JS）
- 背景先填充 `canvas.background`
- 每个元素按其 `x, y, w, h` 栅格化到此画布

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

### 6.5 可选：Dithering

默认 **不做 dithering**（像素风不需要）。
可配置 `render.dither: "none" | "floyd-steinberg"`，双端都实现，snapshot 测试覆盖。v1.0 仅实现 `none`。

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
| 超大画布（> `limits.canvas-max-maps`） | commit 拒绝 |
| 元素超出画布边界 | 只绘制画布内部分，越界部分裁剪 |
| 字体不存在 | fallback 到默认字体并产生 `session.warning` |
| 调色板 LUT 未加载 | 渲染拒绝并 `INTERNAL_ERROR` |

---

## 11. 未决问题

- [ ] 非像素字体是否需要提供「强制像素化」选项（字号任意 → 量化到 1px 网格）
- [ ] Dithering 是否值得做（v1.0 不做，但预留配置）
- [ ] 效果组合（描边+阴影+发光）的性能 budget
- [ ] 中文字体的 emoji / 符号缺字处理（fallback chain）
