# HikariCanvas 开发指南

**状态：** 操作类文档 · 2026-06-21 首版
**适用范围：** 贡献者 / 二次开发者
**面向读者：** 想克隆仓库、构建、跑测试、起本地 dev server、改后端 Java 或前端 Vue 的人

本文是「怎么把代码跑起来 + 在哪改」的实操指南。架构与契约决策不在这里——那些在
`PROPOSAL.md` 与 `docs/architecture.md` / `docs/protocol.md` / `docs/rendering.md` 等
契约文档里。**改契约要先改对应 `docs/*.md` 再改代码**（见 `CLAUDE.md` 「文档先行」）。

---

## 1. 环境要求与工具链锁定

这些版本是**锁死的**，不要随手升级（尤其 Java 与 Node）。

| 工具 | 版本 | 说明 |
|---|---|---|
| **Java** | **21**（Temurin / 任意 21 LTS JDK） | 生产 jar 的编译目标，Gradle toolchain 默认 `JavaLanguageVersion.of(21)`。跑 Paper 26.x 的服务器需要 Java 25，但那是**运行**要求；一份 jar 通吃两版，编译目标不变（见 §1.1） |
| **Node.js** | **22 LTS** | **不要用 Node 25**——已知卡 `vue-tsc`（见 §8）。CI 锁 Node 22 |
| **Gradle** | **9.4.1** | 走 `./gradlew` wrapper（`gradle/wrapper/gradle-wrapper.properties` 已 pin），不要装全局 Gradle |
| npm | 随 Node 22 自带 | 前端依赖严格按 `web/package-lock.json` |

### 1.1 多版本编译

`plugin/build.gradle.kts` 把编译目标参数化：`-PpaperApi=` / `-PjavaVer=` / `-PmcVersion=`。默认
1.21.11 + Java 21 出生产 jar；CI 另有一个 `compat-26` job 用 `-PpaperApi=26.2.build.+ -PjavaVer=25`
对同一份 main 源码编译，提前抓「用了 26.x 已移除 API」的回归。本地起 26.x dev server 同理传参。

平台依赖：

- **AWT / Graphics2D** 是后端渲染核心，依赖 JDK 自带的 `java.awt`。无头环境（CI Linux）能跑，
  但 AWT 字体度量跨平台有微差——这就是部分快照测试在 CI 上跳过的原因（见 §7）。
- 本地开发推荐 **macOS**：快照测试的 baseline 是 macOS 生成的，本地 macOS 能跑全套快照保护。
  CI（Linux）用 `@DisabledIfEnvironmentVariable(GITHUB_ACTIONS=true)` 跳过平台敏感的那批。
  **在 Windows / Linux 本地跑会看到文字类 fixture 失败**（`02-chinese-text` /
  `03-effects-stroke` / `04-effects-shadow` / `05-effects-glow`），这是环境差异不是回归——
  判定方法是把改动 `git stash` 掉在干净树复跑，对比失败集合是否一致。

锁定版本的权威来源（构建脚本真实现状）：

- 根 `build.gradle.kts`：`group = "ac.haru"` / `version = "0.9.16-SNAPSHOT"`
- `settings.gradle.kts`：`rootProject.name = "hikari-canvas"`，子项目 `plugin` +
  `examples:demo-train-plugin` + `examples:demo-score-plugin`
- `plugin/build.gradle.kts` 关键依赖：Paper `1.21.11-R0.1-SNAPSHOT`（`paperweight-userdev`
  2.0.0-beta.21，shadow 9.4.3）、Javalin 7.1.0、Jackson 2.22.1（databind 与 dataformat-yaml
  **必须同版本**）、SQLite JDBC 3.53.0.0、HikariCP 7.1.0、JDBI 3.54.0（core 与 sqlite
  **必须同版本**）、Caffeine 3.1.8；测试侧 JUnit 5.11.3 + MockBukkit-v1.21 3.123.0 +
  javalin-testtools 7.1.0（须与 Javalin 本体同版本）
- **PacketEvents 2.13.0 是 `compileOnly`，不打进 jar**：它是 GPL-3.0，打包会污染本项目 MIT。
  服主须单独安装 PacketEvents 插件（`paper-plugin.yml` 声明为必装依赖）
- `web/package.json` 关键依赖：Vue 3.5、Pinia 2.3、Konva 9.3 + vue-konva 3.4、Lexical 0.44
  （`lexical` 与 `@lexical/*` **必须同版本**）、fontkit 2.0、polygon-clipping 0.15、
  Tailwind 4 + Vite 8.1 + Vitest 4 + vue-tsc 3.2

> **模块版本对齐**：上面标了「必须同版本」的几组，dependabot 只会单独升其中一个模块，合并前
> 要手动补齐另一个。Jackson（2026-07-18）与 JDBI（2026-07-21）都踩过这个坑。

---

## 2. 快速开始

```bash
# 1. 克隆
git clone https://github.com/HyacinthHaru/HikariCanvas.git
cd HikariCanvas

# 2.（首次）下载内置字体 + 同步到前端 public（~10min，见 §8）
./gradlew :plugin:syncFontsToWeb

# 3. 装前端依赖
cd web && npm ci && cd ..

# 4. 后端单测（含快照测试）
./gradlew :plugin:test

# 5. 前端单测
cd web && npm run test && cd ..

# 6. 打包 shadow jar（自动跑 palette / fonts / web 产物链）
./gradlew :plugin:shadowJar
# 产物：plugin/build/libs/HikariCanvas-0.9.16-SNAPSHOT.jar

# 7. 起本地 MC 1.21.11 dev server（自动挂上一步的 jar）
./gradlew :plugin:runServer
```

### 2.1 第一次构建会发生什么

`shadowJar` / `processResources` 会自动触发一串构建期任务（无需手动跑）：

1. `downloadFonts`——从各 GitHub Release / raw 抓 20+ 枚内置字体（OFL 1.1）到
   `build/downloaded-fonts/`，每枚 SHA-256 pin 校验。**首跑慢（~10min），后续命中缓存秒过**。
2. `generateGlyphMetrics`——对每枚字体用 AWT 算 BMP advance 表，输出 `*.metrics.json`。
3. `generatePalette`——从 Paper `MapPalette` 导 248 色到 `palette.json`（独立 `generator`
   sourceSet，避免循环依赖）。
4. `generateIconLibrary`——下 Font Awesome Free 6.7.2 zip，解出 `fa-{solid,regular,brands}.icons.json`。
5. `downloadLicenses`——抓每枚字体的 OFL 1.1 正文 + Font Awesome 的 LICENSE（同样 SHA-256 pin），
   进 jar `/licenses/`。SIL OFL 1.1 要求再分发字体时随附许可证正文。
6. `buildWeb` → `copyWebToResources`——`npm run build`（Vite）出 `web/dist/`，拷进 jar 的
   `/web` 资源前缀（Javalin 静态托管）。
7. `processResources` 把字体 / palette / icons / licenses / web 产物全合进 shadow jar。

仓库**不入字体 / 图标二进制**（`.gitignore` 排除），它们都靠构建期下载。

> 新增内置字体时必须同时在 `fontLicenses` 里登记它的许可证 URL，否则 Gradle 配置期直接
> `require` 失败——这是刻意的合规闸。

### 2.2 本地 dev server 工作流（前端热重载）

`runServer` 用的是 shadow jar 里**已打包好的前端产物**，改前端不会自动刷新。要热重载前端：

```bash
# 终端 A：起 MC dev server（后端 + WebServer 监听 127.0.0.1:8877）
./gradlew :plugin:runServer

# 终端 B：起 Vite dev server（127.0.0.1:9173，热重载）
cd web && npm run dev
```

前端在非同源时固定连 `ws://127.0.0.1:8877/ws`（见 `web/src/network/wsClient.ts`），
所以 9173 的 Vite 页面能直接和 8877 的后端 WebSocket 通讯。游戏里 `/canvas edit` 锁墙拿到
token 后，把 token 拼到 Vite 地址用：`http://127.0.0.1:9173/?token=<token>`。

> WebServer 默认 `host: 127.0.0.1` / `port: 8877`（`plugin/src/main/resources/config.yml`）。
> 公网部署必须 nginx/Caddy 反代 + TLS（见 `docs/deployment.md`）。

浏览器 console 调试入口：`window.__hk.send("op", payload)`。

---

## 3. 常用命令速查

### 3.1 Gradle（在仓库根跑 `./gradlew ...`）

| 命令 | 作用 |
|---|---|
| `:plugin:shadowJar` | 打可部署 jar（自动跑 palette/fonts/icons/web 链 + relocate 第三方依赖） |
| `:plugin:runServer` | 起本地 MC 1.21.11 dev server，自动挂最新 shadow jar + 自动写 `eula=true` |
| `:plugin:test` | 后端单测（JUnit 5 + MockBukkit + JavalinTest，含渲染快照） |
| `:plugin:assemble` | = `shadowJar` |
| `:plugin:syncFontsToWeb` | 下字体 + 生成 metrics + 拷到 `web/public/fonts/`（首次 / 字体规格变更跑一次） |
| `:plugin:downloadFonts` | 仅下载内置字体到 `build/downloaded-fonts/` |
| `:plugin:generateGlyphMetrics` | 仅生成所有内置字体的 advance 表 JSON |
| `:plugin:generatePalette` | 仅导出 `palette.json` |
| `:plugin:generateIconLibrary` | 仅生成 Font Awesome icons JSON |
| `:plugin:buildWeb` | 仅跑前端 `npm run build`（含按需 `npm ci`） |
| `:plugin:copyWebToResources` | 把 `web/dist/` 拷成 jar 资源 |
| `:plugin:downloadLicenses` | 抓 22 枚内置字体的 OFL 正文 + Font Awesome LICENSE，进 jar `/licenses/` |

> 注意：自定义任务名都不带 `:plugin:` 也能跑（它们定义在 `plugin/build.gradle.kts`），
> 但加 `:plugin:` 前缀最稳妥。`runServer` / `shadowJar` 等来自 `run-paper` /
> `paperweight-userdev` / shadow 插件。

### 3.2 npm（在 `web/` 跑）

| 命令 | 作用 |
|---|---|
| `npm run dev` | Vite dev server（127.0.0.1:9173，热重载） |
| `npm run build` | 生产构建到 `web/dist/`（拆 lexical / i18n / script-engine chunk） |
| `npm run test` | Vitest 单测（一次性跑，`vitest run`） |
| `npm run test:watch` | Vitest watch 模式 |
| `npm run test:ui` | Vitest UI（浏览器面板） |
| `npm run typecheck` | `vue-tsc --noEmit` 类型检查（Node 25 下会卡，用 22） |
| `npm run preview` | 预览 `dist/` 产物 |
| `npm ci` | 严格按 `package-lock.json` 装依赖（可重现，不静默升级） |

前端单测是**纯算法 / composable / 校验逻辑**测试，跑在 node 环境（`vite.config.ts` `test`
段，`environment: 'node'`），不引 jsdom、不做组件渲染、不做像素级双端对比。测试文件分布在各
模块的 `__tests__/*.test.ts`（livepaint / interpolator / pickerLogic / lexicalChip /
timeline / script 等）。

---

## 4. 后端模块导航

代码在 `plugin/src/main/java/ac/haru/hikaricanvas/`。主类 `HikariCanvas.java`（`extends
JavaPlugin`，`onEnable` 装配所有单例 / `onDisable` 关停）+ `HikariCanvasConfig.java`（config.yml 映射）。

| 子包 | 一句话职责 |
|---|---|
| `api` | 公开 Plugin Push API（`ac.haru.hikaricanvas.api.*`，**shadowJar 不 relocate**）：`HikariCanvasAPI` + `VarType` + `NamespaceInfo` + 2 异常 |
| `benchmark` | 0.5.0 纯服务端性能 Benchmark：scene 生成 + rasterize/palette/GC 模拟 + report.json/html + 50mspt 计算器 |
| `canvasfile` | `.canvas` 工程文件导入导出：archive 打包 / manifest / asset 摄取 / 物化 / 脚本导入 |
| `command` | `/canvas` 命令树：`CanvasCommand`（根）+ `VariableSubCommand`（`var` 族）+ `BenchmarkSubCommand`（`bench` 族） |
| `deploy` | 游戏世界侧：`MapPacketSender`（**唯一发包入口**，PacketEvents 调用集中）+ `FrameDeployer` + `WallResolver` + `CanvasWand` + `FrameProtectionListener` |
| `image` | 图片上传：`UploadHandler`（6 层校验栈）+ `ImageStorage`（hash 内容寻址 + LRU）+ `ImageQuotaService` + EXIF 旋转 + URL 拉取安全 |
| `pool` | 预览地图池（**项目技术核心**）：`MapPool` 按 world UUID 分桶，FREE/RESERVED 两态，编辑期只刷像素不新建 MapView |
| `rail` | 铁路网络模型：线路 / 站点 / 车次 / 时刻表 / 服务类型 + `AutoTimetableGenerator` 纯函数 |
| `render` | 渲染引擎（最大子包）：`CanvasCompositor` rasterize + 各元素 renderer + 字体 / palette / dither / blend + 时间轴 `AnimationTicker` / 插值 / 缓动 + `BufferPool`（详见 §6） |
| `schedule` | 手动时刻表 record（`WallSchedule` / `ScheduleEntry`） |
| `script` | 0.7.0 视觉脚本数据模型：`ScriptRule` / `Trigger` / `Action`（含序列化器）+ `ScriptRuleValidator` + `ScriptStore` + `ScriptPermissions` |
| `session` | 编辑会话：`SessionManager`（byWall 排他锁）+ `Session` + `TokenService` + IP 绑定 + 限流 + `WandListener` |
| `state` | 工程状态与元素模型：`ProjectState` + sealed `Element`（text/path/circle/shape/image/icon/brush）+ `Fill` + `EditSession`（编辑 op 入口 + undo/redo）+ 时间轴 `Timeline`/`Keyframe`/`Easing` |
| `storage` | 持久化：`Database`（HikariCP + SQLite）+ `MigrationRunner`（V001..）+ 各 DAO（Wall / Template / Variable / Schedule / Rail / Script / ImageUpload）+ `AuditLog` |
| `template` | 模板系统：YAML v1 加载 / 实例化 / 校验 / 注册表 / 导出 / 发布 |
| `variable` | 0.4.0 变量系统：`VariableStore` + `VariableInterpolator`（`${var:X}` 替换）+ `VarType` + DTO + provider/plugin 子目录 |
| `web` | WebSocket / HTTP 端点：`Protocol`（版本协商）+ `Envelope` + 各 `*OpDispatcher`（Edit / Brush / Rail / Schedule / Script / Template）+ `WebServer`（Javalin） |

架构纪律（**不可越界**，详见 `CLAUDE.md`「架构纪律」+ `PROPOSAL.md §5.2.6`）：

1. **禁用 NMS**——任何 `net.minecraft.*` / 服务端内部类禁止；只用公开 Bukkit API + PacketEvents。
2. **PacketEvents 调用集中**——所有发包走 `deploy/MapPacketSender.java` 一个类，别处不直接碰。
3. **shadowJar relocate**——除 `org.sqlite`（JNI native lib 不能动）外，所有内嵌第三方依赖
   relocate 到 `ac.haru.hikaricanvas.shaded.*`，防服内插件 classpath 冲突。`ac.haru.hikaricanvas.api`
   是公开 API 包**不 relocate**（外部插件 import 路径不可变）。

---

## 5. 前端模块导航

代码在 `web/src/`。入口 `main.ts` + `App.vue`。状态用 Pinia setup stores，WS 通讯封装在
单例 `WsClient`。

| 目录 | 一句话职责 |
|---|---|
| `stores/` | Pinia setup stores：`network` / `project`（ProjectState mirror）/ `ui` / `theme` / `palette` / `brush` / `paintBucket` / `templates` / `variables` / `variableAliases` / `schedule` / `rail` / `timeline` / `scripts` / `scriptEdit` / `iconLibrary` |
| `network/` | `wsClient.ts`——单例 `WsClient`，WS 连接 / op 发送 / ack / ready & patch 分拣 |
| `types/` | 协议 / 数据 TS 类型镜像：`protocol.ts` / `variable.ts` / `schedule.ts` / `rail.ts` / `template.ts` / `canvasFile.ts` |
| `render/` | 前端渲染镜像（与后端 `render` 包对镜）：`PreviewRenderer` + `PaletteLut` + `PathParser` + `TextLayout` + `FontLoader`（FontFace API）+ `GlyphMetricsLut` + `BayerDither` / `BlendModes` / `fill` + 缩略图 |
| `components/` | Vue 组件，按区域分子目录：`layout`（CanvasView / TopBar / RightPanel / LayerPanel …）/ `canvas`（overlay / 内联文本编辑）/ `properties`（各元素属性面板）/ `toolbar` / `ui`（ColorInput / FillInput / Tooltip）/ `variables` / `timeline` / `schedule` / `rail` / `template` |
| `composables/` | 可复用交互逻辑：`useSnapManager` / `useDrawToCreate` / `useClipboard` / `useBrushHost` / `useLassoMask` / `useLockGuard` / `usePanScroll` / `useTimelinePlayback` / `useSvgImport` / `useProjectImport`/`Export` 等 |
| `livepaint/` | 油漆桶（**前端独占功能**）：`LivePaintCore` + `ElementToPolygon` + `PolygonToPath` + `RdpSimplifier` + Web Worker（`livePaintWorker.ts`） |
| `timeline/` | 时间轴双端插值镜像：`easing` / `colorLerp` / `interpolation`（与后端 `render` 缓动逻辑对镜，数值需逐位一致） |
| `script/` | 0.7.0 积木脚本编辑器：`model`（blockDefs / blockTree / dropTarget / validator / serialize）+ `canvas`（无限画布 + 拖拽 + 渲染）+ `params`（参数表单 / 条件构建器） |
| `variable/` | 变量前端逻辑：`interpolator`（`${var:X}` 镜像）+ `pickerLogic` + `lexicalChip`（Notion 风 chip 编辑器） |
| `brush/` | `BrushController`——笔刷绘制控制 |
| `i18n/` | 中英文案（`messages.ts` ~1900 行 + `index.ts`） |
| `lib/` | 工具：`canvasFile` / `svg` / `templateExpr` / `downloadBlob` / `utils` |
| `config/` | 调色板预设 |

---

## 6. 双端渲染一致性纪律（开发时注意点）

这是项目最容易踩的坑。**权威契约在 `docs/rendering.md`**（§8 双端一致性 / §9 缓动插值数学），
这里只列开发实操要点。

1. **同 TTF + 禁抗锯齿**。浏览器 Canvas 与 Java Graphics2D 用**同一字体文件**，两端都禁抗锯齿。
2. **文字 advance 不读各自的 font metrics**——两端即便加载同一 TTF 也返不同值。统一走
   `TextLayout.charAdvance(fontId, ch, fontSize)`，查构建期 / 运行时生成的**共享 advance 表**
   （`*.metrics.json`，由 `generateGlyphMetrics` 算 + `syncFontsToWeb` 同步给前端）。缺字 fallback
   `canonicalCharWidth`（ASCII = 0.5×fontSize，CJK = fontSize）。
3. **palette 量化两端读同一 `palette.json`**（构建期 `generatePalette` 生成），各自 32³ Lab LUT。
4. **blend / dither / glow 自实现，不用系统内置**——避免 AWT vs Canvas 内部实现差异。Bayer 4×4
   dither 两端 trivially 一致。
5. **改了任何影响像素的渲染逻辑**：后端改 `render/`，前端必须改 `render/` 对应镜像，再跑快照
   测试确认（§7）。时间轴缓动插值改 `render` 缓动逻辑时，前端 `timeline/` 镜像要逐位一致。
6. **显式例外**：Live Paint（油漆桶）拓扑计算**仅前端 Web Worker 跑**，后端不镜像——它输出
   `PathElement.d` 走常规后端 `PathRenderer`，拓扑算法不参与最终像素（`rendering.md §8.4`）。
   前端也**没有独立的像素级快照渲染台**，只有 vitest 算法单测；双端一致靠「同算法 + 同常量 +
   同 metrics JSON」的代码镜像纪律保证。

---

## 7. 快照测试怎么跑 / baseline 怎么更新

后端渲染快照测试在 `plugin/src/test/java/ac/haru/hikaricanvas/render/RendererSnapshotTest.java`
（+ 时间轴帧快照 `RendererSnapshotTimelineTest.java`），契约对应 `docs/rendering.md §8`。

### 7.1 机制

- **fixture**：`plugin/src/test/resources/fixtures/*.json`，每个是一份 `ProjectState`。
- **expected baseline**：`plugin/src/test/resources/expected/*.png`。
- 流程：读 fixture → `CanvasCompositor.rasterize` 出 `BufferedImage` → 写到
  `build/test-results/snapshot/actual/*.png` → 与 expected 逐像素比 → 差异比 **≥ 0.5%
  （`TOLERANCE = 0.005`）则失败**，diff 图（红点标差异）写到 `build/test-results/snapshot/diff/`。
- **首次运行**（expected 不存在）：自动把 actual 复制为 baseline 并告警，本次 pass；之后
  `git add` 把它 pin 住。

### 7.2 跑

```bash
./gradlew :plugin:test                              # 全部后端测试（含快照）
./gradlew :plugin:test --tests '*RendererSnapshotTest'   # 只跑快照
```

### 7.3 平台脆弱 fixture（CI 会跳过）

4 个 fixture——`02-chinese-text` / `03-effects-stroke` / `04-effects-shadow` /
`05-effects-glow`——在 Linux 与 macOS AWT 渲染下差异 > 0.5%（中文字体度量微差 + effects
内部 AWT 实现差异）。它们拆到 `snapshotPlatformSensitive` 方法，带
`@DisabledIfEnvironmentVariable(GITHUB_ACTIONS=true)`：

- **CI（GitHub Actions Linux）跳过**这 4 项；
- **本地 macOS 跑全套**（含这 4 项），baseline 也是 macOS 生成 + 校对的。

所以本地用 macOS 跑能拿到最强保护；CI 上这 4 项不会因跨平台 noise 误报。

### 7.4 渲染行为有意变更时更新 baseline

1. 跑 `./gradlew :plugin:test`，让它失败并产出 `build/test-results/snapshot/actual/*.png` +
   `diff/*.png`。
2. **人工对比** actual 与 diff（红点标差异），确认变化是预期的（不是 bug）。
3. 把确认无误的 `actual/*.png` 覆盖到 `src/test/resources/expected/*.png`。
4. `git add` + commit。**0 baseline 漂移**是仓库惯例——非预期的 baseline 变更要查清根因再改。

> 重建整套 baseline：`rm src/test/resources/expected/*.png` 后跑测试，让它重新生成（会全部
> 告警 pass），再人工逐张校对后 commit。

---

## 8. 已知工具链坑

> 这些坑长期反复出现，遇到先对照本节，多半不是你的代码问题。

| 症状 | 原因 / 修复 |
|---|---|
| **`vue-tsc` / `npm run typecheck` 卡死或极慢** | Node 25 与 vue-tsc 不兼容。**换 Node 22 LTS**。CI 已锁 22。 |
| **首次构建卡在下字体很久（~10min）** | `downloadFonts` 从多个 GitHub Release 抓 20+ 枚字体（OFL 1.1）+ FA 图标 zip + SHA-256 校验。**只有首次**；之后命中缓存秒过。网络抖动会自动重试 3 次；彻底失败时报错里有手动下载放置路径。 |
| **`npm ci` 在本地（macOS）失败，提示 platform-specific dep mismatch** | 跨平台 optional dep（rolldown / emnapi 等 linux 变体）没写进 lock。本地可改用 `npm install`；CI 已 fallback（`npm ci \|\| npm install`）。 |
| **快照测试在你机器上挂了 4 个（中文 / effects）** | 你不在 macOS。这 4 个是平台脆弱 fixture，baseline 是 macOS 的（§7.3）。本地非 macOS 时这几项的失败可忽略（CI 也跳过）。 |
| **Vite dev / vite build 偶尔卡住** | Node 高版本 + Vite 偶发；重跑一般即过。dev server 端口固定 9173（`strictPort`），被占用会直接报错。 |
| **改了前端但 `runServer` 没变化** | `runServer` 用 shadow jar 里打包好的旧前端产物。前端热重载要单独起 `npm run dev`（§2.2），或重新 `shadowJar`。 |
| **改了字体规格但前端 metrics 不对** | 重跑 `./gradlew :plugin:syncFontsToWeb` 把新 metrics 同步到 `web/public/fonts/`。 |
| **shadowJar 里第三方类与服内其它插件冲突** | 检查是否漏了 relocate。除 `org.sqlite`（JNI）外都要 relocate 到 `ac.haru.hikaricanvas.shaded.*`；**绝不**对 `ac.haru.hikaricanvas` 自身或 `ac.haru.hikaricanvas.api` 加 relocate（会让 `ServicesManager.load(HikariCanvasAPI.class)` 在外部插件侧崩，见 `docs/api.md`）。 |

---

## 9. CI / Release

GitHub Actions 两个 workflow（`.github/workflows/`）：

### 9.1 `ci.yml`——push / PR 到 `main` 触发

单 job（前后端互相依赖，拆 job 反而要重复 setup）：

1. `actions/checkout`
2. setup Java 21 Temurin + Node 22 LTS（npm cache，key = `web/package-lock.json`）
3. setup Gradle（`gradle/actions/setup-gradle@v4`，自带 dep / build / config cache，不额外配
   `actions/cache`）
4. 前端：装依赖（`npm ci`，失败 fallback `npm install`）→ `npm run test` → `npm run build`
5. 后端：`./gradlew :plugin:test --no-daemon --stacktrace` → `./gradlew :plugin:shadowJar ...`
6. 上传 jar artifact（`HikariCanvas-<sha>`，留 30 天）；失败时上传 test reports（留 7 天）

首跑 ~5min（paperweight dev bundle 首装），后续 ~2min（缓存生效）。

### 9.2 `release.yml`——tag `v*` 触发

```bash
git tag v0.9.16-rc.1 && git push origin v0.9.16-rc.1
```

跑 test + shadowJar → 从 tag 解版本号 → 把产物重命名为 `HikariCanvas-<version>.jar` →
`softprops/action-gh-release` 创建 Release 并附 jar。tag 含 `-`（如 `-SNAPSHOT` / `-beta.1`）
自动标 **prerelease**；纯 stable（`v1.0.0`）不标。

---

## 10. Git 提交约定（贡献者须知）

完整规则见 `CLAUDE.md`「Git 提交约定」。要点：

- 所有 commit **SSH 签名**（`gpg.format=ssh` + `commit.gpgsign=true`）；身份与密钥由本地
  `.git/config` 持有，不动全局。签名失败先查原因，
  **不要用 `--no-gpg-sign` 绕过**。
- **禁止任何形式的 Claude / AI 署名**（含 `Co-Authored-By`）。
- 每次修改在 `docs/journal.md` 顶部追加一条（日期 · 范围 · 改动 · 关联文件）。
- 评估「做到哪了 / 还剩什么」**优先读 `docs/journal.md` + 代码**，不要只信 `CLAUDE.md`
  路线表 / 设计文档分期表（它们会滞后）。

---

## 相关文档

- `PROPOSAL.md` — 立项总纲
- `docs/architecture.md` — 架构与核心机制
- `docs/protocol.md` — WebSocket 协议
- `docs/rendering.md` — 渲染管线与双端一致性（§8 快照 / §9 缓动数学）
- `docs/data-model.md` — SQLite / PDC / `.canvas` 格式
- `docs/security.md` — 威胁模型与安全规范
- `docs/api.md` — Plugin Push API 接入教程
- `docs/deployment.md` — 生产部署
- `docs/journal.md` — 每个 commit / 里程碑的真实落地日志（日期倒序）
