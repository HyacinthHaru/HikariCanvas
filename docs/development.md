# HikariCanvas 构建与二次开发指南

这篇文档面向**二次开发者、开源贡献者以及接手维护代码的 AI 助手**，包含本地编译环境要求、前后端联调流程、模块目录导航、核心设计边界，以及测试与快照更新约定。

> **契约先行原则**：仓库内的架构、WebSocket 协议、数据库表结构、双端渲染一致性与安全边界均有对应的规范文档（完整清单见 [文档导览](README.md)）。如果需要改动协议字段、持久化格式或双端共享算法，请**先更新对应的 `docs/*.md` 契约文档，再修改实现代码**。

---

## 1. 环境要求与工具链版本

构建环境版本已锁定，开发时请对齐以下版本（尤其是 JDK 与 Node.js）：

| 工具 | 要求版本 | 说明 |
| --- | --- | --- |
| **JDK** | **Java 21**（推荐 Temurin） | 生产插件包默认以 Java 21 编译，产出的同一份 jar 可运行在 Paper 1.21.11（Java 21）与 Paper 26.x（Java 25）上 |
| **Node.js** | **22 LTS** | **请勿使用 Node 25**（已知与 `vue-tsc` 存在卡死问题）；CI 固定使用 Node 22 LTS |
| **Gradle** | **9.6.1** | 直接使用仓库自带的 `./gradlew` 包装器，无需全局安装 Gradle |
| **npm** | 随 Node 22 自带 | 前端依赖锁定在 `web/package-lock.json` |

### 1.1 双版本编译守卫与关键依赖对齐

- **跨 Paper 大版本编译验证**：`plugin/build.gradle.kts` 支持通过 `-PpaperApi=` 与 `-PjavaVer=` 切换编译目标。默认产出 Paper `1.21.11-R0.1-SNAPSHOT` + Java 21 的通用包；CI 中的 `compat-26` 任务额外使用 Java 25 执行 `-PpaperApi=26.2.build.+ -PjavaVer=25` 编译同一份源码，确保不会引入在 26.x 中已被移除的旧接口。
- **PacketEvents 仅编译期引用**：PacketEvents（`2.13.0`）采用 GPL-3.0 协议，`build.gradle.kts` 中声明为 `compileOnly` **绝不打包进产物**，由服主作为前置插件单独安装。
- **必须成对保持同版本的依赖组**（升级依赖时请一并同步，单边升级会引发运行期异常）：
  - 后端：`jackson-databind` 与 `jackson-dataformat-yaml`（当前均为 `2.22.1`，项目 YAML 解析统一走 Jackson，不使用 SnakeYAML）；`jdbi3-core` 与 `jdbi3-sqlite`（`3.54.0`）；`javalin` 与 `javalin-testtools`（`7.2.2`）。
  - 前端：`lexical` 与全部 `@lexical/*` 子包（`0.48.0`）。

---

## 2. 快速跑通本地编译与测试

```bash
# 1. 克隆仓库
git clone https://github.com/HyacinthHaru/HikariCanvas.git
cd HikariCanvas

# 2. 安装前端依赖、执行类型检查与前端单元测试
cd web
npm ci
npm run typecheck
npm run test
cd ..

# 3. 执行后端单元测试（首次运行会自动下载 22 款内置字体与图标资源，约需数分钟）
./gradlew :plugin:test

# 4. 构建完整可部署插件包（自动联动前端生产构建、调色板导出、字距生成与依赖重定位）
./gradlew :plugin:shadowJar
# 输出位置：plugin/build/libs/HikariCanvas-<版本>.jar

# 5. 启动本地 Paper 1.21.11 测试服（自动挂载刚打好的 shadowJar）
./gradlew :plugin:runServer
```

### 2.1 首次构建背后的资源生成链

仓库本身**不提交字体和图标等大体积二进制文件**，首次执行 `:plugin:test` 或 `:plugin:shadowJar` 时，Gradle 会自动按序执行以下构建任务并缓存到本地 `build/` 目录：

1. `downloadFonts` —— 下载 22 款 SIL OFL 1.1 开源字体到 `build/downloaded-fonts/`，逐个校验 SHA-256。
2. `generateGlyphMetrics` —— 使用 AWT 扫描每款字体的 BMP 字符宽度，生成双端共享的 `*.metrics.json`。
3. `generatePalette` —— 在独立的 `generator` 源码集中从 Paper `MapPalette` 导出 248 色 `palette.json`。
4. `generateIconLibrary` —— 下载 Font Awesome Free 6.7.2 并提取为 `fa-{solid,regular,brands}.icons.json`。
5. `downloadLicenses` —— 拉取全部内置字体的 OFL 许可证正文与 Font Awesome 许可证，打入 jar 内的 `/licenses/` 目录以满足再分发合规要求（新增内置字体必须在构建脚本中同步登记许可证地址，否则配置期会直接报错拦截）。
6. `buildWeb` → `copyWebToResources` —— 执行前端 `vite build` 并将产物拷入 jar 的 `/web` 静态资源目录（显式排除 `fonts/**`，避免与后端 `/fonts/` 目录重复打包，确保本地构建与 CI 发布包体积稳定在约 86–90 MB）。

### 2.2 前后端分离热重载联调

`:plugin:runServer` 默认托管的是打进 jar 里的静态前端产物。修改前端代码需要实时热更新时，开两个终端并行运行即可：

```bash
# 终端 A：启动本地测试服（后端 HTTP / WebSocket 默认监听 127.0.0.1:8877）
./gradlew :plugin:runServer

# 终端 B：同步一次本地字距文件后启动 Vite 开发服务器（监听 127.0.0.1:9173）
./gradlew :plugin:syncFontsToWeb
cd web && npm run dev
```

前端在非同源开发端口下会自动连接 `ws://127.0.0.1:8877/ws`。在游戏内执行 `/canvas edit` 选墙、`/canvas confirm` 拿到链接后，把链接里的 `token` 参数拼到 `http://127.0.0.1:9173/?token=<你的token>` 打开即可享受热重载。开发环境下还可以在浏览器控制台调用 `window.__hk.send("op", payload)` 直接调试协议帧。

---

## 3. 常用开发命令速查

### 3.1 Gradle 任务（仓库根目录执行 `./gradlew ...`）

| 任务 | 用途 |
| --- | --- |
| `:plugin:shadowJar` | 构建完整可部署插件包（含前端构建与第三方依赖重定位；支持 `-PreleaseVersion=1.0.0` 指定注入版本号） |
| `:plugin:runServer` | 启动本地 Paper 1.21.11 测试服并挂载最新插件包 |
| `:plugin:test` | 运行全部后端 JUnit 6 单元测试与渲染快照测试 |
| `:plugin:compileJava -PpaperApi=26.2.build.+ -PjavaVer=25` | 本地复现 CI 的 Paper 26.2（Java 25）跨版本编译守卫 |
| `:examples:demo-train-plugin:jar` / `:examples:demo-score-plugin:jar` | 编译两个官方 Push API 示例插件 |
| `:plugin:syncFontsToWeb` | 将下载的字体与 `*.metrics.json` 同步到 `web/public/fonts/` 供独立 Vite 开发使用 |
| `:plugin:downloadFonts` / `:plugin:generateGlyphMetrics` / `:plugin:generatePalette` | 单独重跑字体下载、字距表生成或调色板生成 |

### 3.2 前端命令（在 `web/` 目录下执行）

| 命令 | 用途 |
| --- | --- |
| `npm run dev` | 启动 Vite 开发服务器（固定 `127.0.0.1:9173`） |
| `npm run typecheck` | 执行 `vue-tsc --noEmit` 全量类型检查（CI 必过门禁） |
| `npm run test` | 一次性跑完 Vitest 全部测试（`vitest run`） |
| `npm run test:watch` | 以监听模式运行 Vitest |
| `npm run build` | 生产构建输出至 `web/dist/` |

前端测试统一放在各模块的 `src/**/__tests__/*.test.ts` 下：纯算法、状态仓库与解析器测试默认运行在轻量的 `node` 环境；Vue 组件交互测试则在文件头部声明 `@vitest-environment happy-dom`，配合 `@vue/test-utils` 挂载断言。

---

## 4. 核心设计边界与不变量

下面几条是贯穿全仓的底层架构约束——很多地方「为什么特意这样写」在单文件代码里看不出来，改动相关模块前请务必确认不破坏这些前提：

1. **零 NMS 与发包出口集中（[系统架构 §1.3](architecture.md)）**
   - 严禁在任何类中引用 `net.minecraft.*` 或 CraftBukkit 内部实现类，只允许调用公开 Paper / Bukkit API 与 PacketEvents。
   - 所有 PacketEvents 发包操作必须集中在 `deploy/MapPacketSender.java` 一个类内，其他业务模块不得直接操作底层数据包，以此保证未来升级新 Minecraft 版本时改动面最小。
2. **地图池归还总不变式（[系统架构 §4](architecture.md)）**
   - 预览地图池 `MapPool` 按世界 UUID 独立分桶，编辑期间只刷新地图像素、不反复新建 `MapView`，防止存档 `idcounts.dat` 无限膨胀。
   - **总不变式：只要某张地图仍被数据库中存在的 `walls` 行记录引用，就绝不能退回 `FREE` 空闲池。**
     - 删除画板（`SessionManager.deleteWall`）必须**先确认数据库 `walls` 行删除成功，再把地图归还池中**（删库失败则中止，宁可保留画板让玩家重试，也绝不能让两块画板共用同一张地图串台）。
     - 启动恢复（`WallRestorer`）失败时按绑定前后区分：绑定地图前失败可归还空闲池；一旦 `bindToWall` 已成功，后续即使重绘报错也必须**保留绑定**。
3. **画板锁定仅在打开会话与前端界面拦截（[系统架构 §3.4 / §13](architecture.md)）**
   - 数据库 `walls.published_at` 列（保留历史列名）非空即表示画板处于锁定状态。
   - 锁定的权限拦截只放在 `SessionManager.open` 与前端只读守卫；**后端的编辑操作、变量刷新、时间轴与脚本执行全部不读锁状态**，确保作者把画板锁定防误改后，上面的动态数据、动画和交互脚本依然照常运转。
4. **会话级 IP 绑定，而非令牌绑 IP（[安全规范 §2.5](security.md)）**
   - 游戏内签发令牌时没有浏览器网络上下文，因此令牌本身靠「单次消费 + 15 分钟短有效期」保护；首个使用该令牌完成 WebSocket 握手的连接会原子绑定 `Session.boundIp`，后续帧来源 IP 不一致直接以 `4001` 拒绝。
5. **动态数据坚持单向推送，渲染绝不阻塞主线程（[动态数据接入设计](dynamic-data.md)）**
   - 变量值统一为字符串，`user`、`userglobal`、`system`、`papi`、`scoreboard`、`schedule` 为系统保留命名空间，外部插件只允许写入自己注册的命名空间。
   - 画布合成与调色板量化在后台线程运行，变量取值只读内存缓存，禁止在游戏主线程执行重绘或阻塞式外部查询。
6. **字体动态单轨加载与双端渲染同构（[渲染规范 §8 / §9](rendering.md)）**
   - 所有字体（22 款内置 + 服主放入 `fonts/` 的外部字体）统一经后端 `/api/font/file` 传输、由前端 `FontLoader` 调用浏览器 `FontFace` API 动态注册；**不要**在 CSS 里写死 `@font-face` 或在渲染器里维护字体白名单。
   - 浏览器 Canvas 与后端 Java Graphics2D 均关闭抗锯齿；字符排版宽度统一查共享的 `*.metrics.json`（`TextLayout.charAdvance`，严禁直接读各自平台的原生字体度量），调色板统一查 `palette.json` 生成的 32³ Lab 查找表，时间轴与补间缓动统一走确定性数学实现。
   - **唯一前端独占例外**：油漆桶（`web/src/livepaint/`）的区域布尔分割仅在浏览器 Web Worker 中计算，最终产出标准 `PathElement` 路径字符串交由双端常规路径渲染器绘制。
7. **数据库向前兼容迁移与打包重定位（[数据模型 §6.5](data-model.md)）**
   - SQLite 数据库（HikariCP 连接池保持 `maxPoolSize=4` 以支撑并发只读查询，写冲突靠 `busy_timeout` 兜底）自 `V018` 起严格执行只增不改迁移（由 `MigrationForwardOnlyTest` 守卫），不得篡改已落地的 `V001`–`V017` 脚本。
   - `shadowJar` 将内嵌的 Jackson、Javalin、Jetty、HikariCP、JDBI、Caffeine 等统一重定位到 `ac.haru.hikaricanvas.shaded.*`；但**严禁重定位 `org.sqlite`**（破坏 JNI 本地库加载）与 **`ac.haru.hikaricanvas.api.*`**（破坏外部插件调用路径）。

---

## 5. 代码模块导航

### 5.1 后端模块（`plugin/src/main/java/ac/haru/hikaricanvas/`）

入口类为 `HikariCanvas.java`（生命周期装配与关停回收）与 `HikariCanvasConfig.java`（配置文件解析映射）。

| 子包 | 职责说明 |
| --- | --- |
| `api` | 对外公开的插件推送接口（`HikariCanvasAPI`、`VarType`、`NamespaceInfo`、`VariableUpdate` 及异常类，打包不重定位） |
| `benchmark` | 服务端离线性能压测：21 个渲染基准场景、补间与脚本压测、预算换算公式及自包含 HTML/JSON 报告生成 |
| `canvasfile` | `.canvas` 工程归档导入导出：ZIP 安全解包、清单校验、图片资产内容寻址摄取与跨服缺失资源扫描 |
| `command` | `/canvas` 命令树：`CanvasCommand`（根命令与日常管理）、`DiagnosticsSubCommand`（`stats` / `diagnose`）、`VariableSubCommand`（`var`）、`BenchmarkSubCommand`（`bench`） |
| `deploy` | 游戏世界交互：`MapPacketSender`（唯一地图发包出口）、`FrameDeployer`、`WallResolver`、`CanvasWand`、`FrameProtectionListener` |
| `i18n` | 多语言消息服务 `Messages`：按玩家客户端语言匹配 `lang/*.yml`，支持回退与热重载 |
| `image` | 图片上传与存储：`UploadHandler`（多层格式与尺寸校验）、`ImageDecodeGuard`（解码超时隔离）、`ImageStorage`（内容寻址与 LRU 清理）、`ImageQuotaService` |
| `pool` | 预览地图池 `MapPool`：按世界分桶管理 `FREE` / `RESERVED` 地图生命周期与泄漏巡检 |
| `rail` | 铁路网络领域模型：线路、站点、车次、服务类型与逐站时刻表自动推算器 |
| `render` | 后端渲染管线：`CanvasCompositor`、各元素渲染器、字体注册与字距表、调色板 LUT、抖动与混合模式、时间轴 `AnimationTicker` 与缓动插值、`WallRestorer` |
| `schedule` | 单画布简易列车时刻表数据模型 |
| `script` | 积木脚本系统：触发器与动作密封类模型、`ScriptRuleValidator`、`ScriptPermissions`、`ScriptStore`，以及 `engine/` 下的执行器、事件路由、运行预算与 `TweenScheduler` |
| `session` | 编辑会话管理：`SessionManager`（单画板排他锁）、`Session`、`TokenService`、会话级 IP 绑定、速率限制与 `WandListener` |
| `state` | 画布状态模型：`ProjectState`、密封 `Element` 体系、填充类型 `Fill`、`EditSession`（含撤销/重做栈）与时间轴关键帧定义 |
| `storage` | SQLite 持久化层：`Database`、`MigrationRunner`、`BackupReaper`、各业务 DAO 与 `AuditLog` |
| `template` | 模板系统：`.canvas` 模板包注册表、参数解析替换（`PackParamResolver`）、工坊发布与缩略图缓存 |
| `variable` | 动态变量核心：`VariableStore`、`VariableInterpolator`（`${var:...}` 解析替换）及系统/PAPI/计分板/时刻表/铁路/外部插件数据源 |
| `web` | 内置 Web 与 WebSocket 服务：`WebServer`（Javalin）、协议版本与信封模型，以及各业务 `*OpDispatcher` |

### 5.2 前端模块（`web/src/`）

入口为 `main.ts` 与 `App.vue`，使用 Vue 3 + TypeScript + Pinia + Konva 构建。编辑器画布采用**双层架构**：上层 Konva 负责透明命中检测与缩放旋转手柄交互，下层原生 Canvas 2D（`PreviewRenderer`）负责执行与后端一致的调色板量化和像素绘制。

| 目录 | 职责说明 |
| --- | --- |
| `stores/` | Pinia 状态仓库：`network`、`project`（画布状态镜像）、`ui`、`theme`、`palette`、`brush`、`paintBucket`、`templates`、`variables`、`variableAliases`、`schedule`、`rail`、`timeline`、`scripts`、`scriptEdit`、`iconLibrary` |
| `network/` | 单例 `WsClient`：管理 WebSocket 握手认证、操作指令发送、回执匹配与服务端补丁分发 |
| `render/` | 前端渲染镜像：`PreviewRenderer`、`PaletteLut`、`TextLayout`、`FontLoader`、`GlyphMetricsLut`、`PathParser`、`BayerDither`、`BlendModes` 与渐变填充 |
| `components/` | Vue 界面组件：按 `layout/`、`canvas/`、`properties/`、`toolbar/`、`ui/`、`variables/`、`timeline/`、`schedule/`、`rail/`、`template/` 划分子目录 |
| `composables/` | 交互逻辑复用：智能对齐吸附（`useSnapManager`）、拖拽创建、剪贴板、画笔宿主、套索蒙版、只读锁守卫、SVG 导入、工程导入导出等 |
| `livepaint/` | 油漆桶拓扑计算（Web Worker 异步执行 `polygon-clipping` 布尔运算与迭代式 RDP 顶点简化） |
| `timeline/` | 时间轴前端插值与色彩过渡镜像（与后端 `render` 包缓动数学保持逐位一致） |
| `script/` | 可视化积木编辑器：`model/`（积木定义、树结构操作、校验与序列化）、`canvas/`（无限画布与拖拽吸附）、`params/`（参数输入与条件构建器） |
| `variable/` | 前端变量解析镜像、变量选择器过滤排序与基于 Lexical 的紫色胶囊标签（chip）编辑器 |
| `lib/` | 通用工具库：`.canvas` 归档读写（`canvasFile.ts`）、静态 SVG 解析转换（`svg/`）、模板条件表达式与文件下载 |
| `constants/` | 前后端对齐的元素尺寸与数量边界常量（`elementLimits.ts`） |
| `config/` | 编辑器色板预设 |
| `i18n/` | 前端中英双语词条表（`messages.ts`）与语言切换逻辑 |
| `types/` | WebSocket 协议载荷与各业务实体的 TypeScript 类型定义 |

---

## 6. 渲染快照测试与基准图更新

后端通过 `RendererSnapshotTest` 与 `RendererSnapshotTimelineTest` 守护渲染输出不发生意外像素退化：

- **输入样例**：`plugin/src/test/resources/fixtures/*.json`
- **期望基准图**：`plugin/src/test/resources/expected/*.png`
- **判定标准**：每次测试将实际渲染结果输出至 `plugin/build/test-results/snapshot/actual/*.png`，与基准图逐像素比对；差异像素占比 **≥ 0.5%** 即判定失败，并在 `diff/` 目录生成标红差异图。

> **跨平台字体栅格化说明**：基准图在 macOS 环境下生成。其中 4 个涉及中文与文字特效的场景（`02-chinese-text`、`03-effects-stroke`、`04-effects-shadow`、`05-effects-glow`）因 Linux 与 macOS 的底层 AWT 字形栅格化存在细微差异，在 GitHub Actions Linux 环境通过 `@DisabledIfEnvironmentVariable` 自动跳过。如果你在本地 Windows 或 Linux 机器上跑测试看到这 4 项失败，可先 `git stash` 在干净分支对比失败集合是否一致，确认并非自己改动引入的回归即可。

当你**有意修改了渲染效果**需要更新基准图时：
1. 运行 `./gradlew :plugin:test` 生成最新的 `actual/*.png` 与 `diff/*.png`；
2. 人工打开图片逐张核对，确认像素变化完全符合预期；
3. 将确认无误的 `actual/*.png` 覆盖回 `plugin/src/test/resources/expected/*.png` 并随代码一并提交。

---

## 7. 常见开发环境问题速查

| 现象 | 原因与解决办法 |
| --- | --- |
| 执行 `npm run typecheck` 长时间卡住不动 | 当前 Node 版本过高（如 Node 25）；请切换到 **Node 22 LTS** |
| 本地 macOS/Windows 执行 `npm ci` 报可选平台依赖不匹配 | 跨平台构建工具的可选平台二进制未命中当前系统；本地直接运行 `npm install` 即可（CI 已配置自动回退） |
| 修改了前端代码，但进 `runServer` 打开网页还是旧界面 | `runServer` 读取的是上次 `shadowJar` 打包进去的静态文件；日常改前端请按 [2.2 节](#22-前后端分离热重载联调) 另开 `npm run dev`，或重跑一次 `./gradlew :plugin:shadowJar` |
| 新增或调整了内置字体后，前端预览字距不对 | 执行 `./gradlew :plugin:syncFontsToWeb` 重新生成并同步 `*.metrics.json` |

---

## 8. CI 流水线与提交前自检

仓库在 `.github/workflows/` 下包含两条自动化流水线：
- **`ci.yml`**（推送或提交 PR 到 `main` 时触发）：
  - `build` 任务（Java 21 + Node 22）：依次执行前端依赖安装、`npm run typecheck`、`npm run test`、`npm run build`，以及后端 `./gradlew :plugin:test` 与 `./gradlew :plugin:shadowJar`，并归档构建产物；
  - `compat-26` 任务（Java 25）：针对 Paper 26.2 API 执行编译检查。
- **`release.yml`**（推送 `v*` 标签如 `v1.0.0` 时触发）：自动从 Git tag 提取版本号并通过 `-PreleaseVersion` 注入构建，在通过 Paper 26.2 编译守卫、前后端全量类型检查与测试、并校验 jar 内 `paper-plugin.yml` 版本一致后，自动创建 GitHub Release 并上传 `HikariCanvas-<版本>.jar`（标签含 `-` 如 `v1.0.0-rc.1` 时自动标记为预发布）。

**提交代码前请确认以下检查全部通过**：

```bash
cd web && npm run typecheck && npm run test && cd ..
./gradlew :plugin:test
```

仓库要求 Git 提交附带有效的 SSH / GPG 签名；若涉及协议、数据模型、渲染规则或安全边界变更，请确认对应的 `docs/*.md` 契约文档已同步更新。
