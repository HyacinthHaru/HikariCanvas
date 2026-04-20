# 变更日志

本文件记录 Claude 每次对本项目做出的修改。**新条目追加到文件顶部**（倒序）。每条应含：日期、改动范围、简要说明、关联文件。
代码与文档的日常提交信息写 git commit，本文件只留会话级摘要。

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
