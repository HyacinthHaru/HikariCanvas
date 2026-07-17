# HikariCanvas 立项文件

| 项目 | HikariCanvas |
| --- | --- |
| 类型 | Minecraft Paper 插件 + 内嵌 Web 编辑器 |
| 目标版本 | Paper 1.21+ |
| 运行环境 | Java 21 |
| 立项日期 | 2026-04-18 |
| 最后更新 | 2026-04-19 |
| 状态 | 立项中（已确认网页优先方向） |

---

## 1. 背景与问题陈述

### 1.1 使用场景
Minecraft 城市建筑、主题服务器中，文字招牌是高频需求：商铺招牌、地铁指示牌、工业园区编号、居民楼号、景区引导、校园牌匾、道路路牌等。玩家希望在建筑上呈现自定义的中文文字，并具备艺术表现力——粗体像素字、竖排、霓虹色、金色牌匾、复古风格。

### 1.2 现有方案的不足

| 方案 | 不足 |
| --- | --- |
| 原版告示牌 | 字号固定、字体单一、尺寸小、无竖排 |
| 原版 `text_display` 实体 | 字体锁死为游戏默认、无法使用自定义字体、生存模式需命令、难以做像素风/霓虹风/艺术字 |
| Photoshop + 现有图片上传插件（ImageOnMap / DrMap 等） | 工作流冗长：建项目→排版→导出→上传→切图→逐块贴框。改一个字要全流程重来 |
| 纯方块摆字 | 占地巨大、建材消耗不现实、不支持复杂字形和小号文字 |

### 1.3 核心痛点
**「在 Minecraft 里放一段像样的文字，太麻烦」**——这是从 beta 时代延续至今仍未被解决的问题。

---

## 2. 项目定位

**一句话：** 给 Minecraft 玩家一个浏览器里的 Canva，在游戏世界里实时渲染成像素招牌。

**目标用户：**
- 城市建筑服服主（最核心）
- 模拟经营/角色扮演服服主
- 大型建筑团队/个人创作者
- 地图作者

**差异化定位：**
- vs 现有图片插件（ImageOnMap/DrMap 等）：它们解决「贴图」，我们解决「从文字直接到成品」
- vs 原版 `text_display`：它做普通 3D 文字，我们做像素风/艺术字/大招牌，不竞争

### 2.1 设计哲学：工具，不是保姆

HikariCanvas 默认**玩家有充足的硬件性能、且知道自己在做什么**——服主是成年的运维者，对自己服务器的 CPU / 内存 / 带宽 / 配置负责。基于此，三条产品哲学贯穿所有功能（尤其 0.5.0 之后的性能 / 动画 / 脚本路线）：

1. **数据透明，不替服主决策。** 系统的职责是把真实成本（渲染耗时 / 内存压力 / 每元素开销）摊开给服主看，并给出换算公式；**不给"推荐配置"这种结论性数字**。服主拿原料自己配方。
2. **不自动降级。** 禁止任何"为玩家好"的自动 throttle / 自动拒绝大负载 / 自动隐藏高开销选项 / 默认压低帧率兜底。理由：自动降级会被玩家反咬"偷偷锁帧率"。`config.yml` 里的上限**仅作安全上限**（防 OOM / 防失控），不是自动调优旋钮——默认取宽松值，由服主按自己机器主动收紧。
3. **不擦屁股。** 不为服主的环境兜底。网络带宽、传输压缩比、RTT、乃至服主自己在 `paper-global.yml` 里有没有开 `network-compression-threshold`——这些是**服主的运维责任**，服务端代码既探测不到、也不该花复杂度去估算。花精力算一个"可能的压缩率"，服主一改配置就失去意义。

> 这条哲学直接决定 Benchmark 的形态（见 §5.2.7）：只测服务端能控制的 CPU/内存成本，绝不碰网络。

---

## 3. 核心创新点

### 3.1 内置文字渲染（技术护城河）
插件内部完成 TTF/OTF 字体加载、排版、渲染、切片、调色板映射，直接输出可使用的地图。用户无需任何外部软件。

### 3.2 模板化招牌系统（产品壁垒）
内置多种场景模板（地铁、商铺、路牌、牌匾……），用户填文字即可生成。模板采用参数化 YAML 定义，支持服主和社区自行扩展。

### 3.3 网页编辑器 + 实时投影（体验差异）
浏览器内 Canva 风格编辑器，所见即所得；每次编辑通过 WebSocket 推送到插件，插件实时更新游戏里墙面上的地图像素——**玩家在游戏里眼看着自己在网页上的修改实时出现**。这是目前 Minecraft 生态内任何文字/招牌类插件都没有的体验。

---

## 4. 功能范围

### 4.1 v1.0 MVP——必须有

**渲染引擎：**
- TTF/OTF 字体加载（打包思源黑体，SIL OFL）
- Java `Graphics2D` 任意文字 → `BufferedImage`
- 颜色、字号、字距、行距、对齐全参数化
- Minecraft 地图调色板映射（~60 色，自研 LUT 比原生 API 快 10x+）
- 像素化模式（关抗锯齿，主力方向）
- 效果：描边、阴影、发光

**网页编辑器（Canva 式完整版）：**
- 自由画布：多图层、拖拽定位、缩放旋转
- 文字元素：多文本框、独立字体字号颜色
- 形状元素：矩形、圆、线条（用于边框、色条、分隔线）
- 模板载入 + 参数化改字改色
- 撤销/重做、剪贴板
- 导入/导出工程文件（`.canvas`）
- 实时预览：浏览器 Canvas 与游戏内地图两边并行显示

**实时投影链路：**
- 玩家游戏内 `/canvas edit` + 点击墙面对角两点 → `/canvas confirm` 锁定 → 物品框立即挂上（填 placeholder 地图）→ 生成一次性 token + URL
- 浏览器打开 URL → WebSocket 绑定会话
- 预览地图池：插件启动时预分配 N 张地图，会话借用，编辑期间**只刷像素不新建 ID**
- 脏矩形差分推送，子区域更新
- 帧率策略：
  - 静止：0 fps（不推送，最后一帧自持）
  - 输入中：防抖 100ms，上限 5 fps
  - session 关闭前：一次全质量完整推送（M5.5 起无显式 commit，cancel/disconnect 时 throttler flush）
- 压缩：WebSocket 开 `permessage-deflate`（JSON 指令）；map packet 走 MC 协议层 zlib

**命令与交互（`/canvas` 前缀）：**

两种选区方式组合使用：
- **命令模式**：`/canvas edit` 进入 `SELECTING` 状态；之后玩家**空手或任何方块**点击墙面两个对角方块即可（左键 pos1 / 右键 pos2）；每次点击聊天栏回显坐标与初步识别结果
- **工具模式**：`/canvas wand` 领取一根命名金铲「Canvas Wand」；持 wand 时左/右键**无需先打命令**即能选区，玩家自主决定是否占背包一格

子命令清单（M5.5 起的 wall 模型）：
- `/canvas edit` — 开启 SELECTING 状态；自动发 wand + chat 三步引导
- `/canvas wand` — 单独发放 Canvas Wand（幂等，已有不重发）
- `/canvas confirm` — 确认当前选区：**新建 wall（挂物品框 + 借池）或打开现有 wall（bind + 不挂框）+ 签发 URL**
- `/canvas open <wall_id\|alias>` — 直接打开已有画继续编辑（不需要先选区）
- `/canvas list` — 列出我的画（按"编辑中 / 已锁定"分组）
- `/canvas alias <name>` — 给当前 session 的画起别名
- `/canvas delete <wall_id> [confirm]` — 删除画（首次提示要 30s 内重复带 confirm）
- `/canvas cancel` — 撤销 selection 或终止活跃会话（**不删画**）
- `/canvas cleanup` — 管理员清理孤立 ItemFrame / walls 行
- `/canvas stats` / `/canvas audit` — 管理员查看池状态与审计

> M5.5 起 `/canvas commit` 命令彻底废止；保存通过 op auto-save 实现。
>
> **锁定/解锁**：2026-05-14 lock-state 重设计起，"published"概念整体砍除；wall 的只读冻结由前端 TopBar 的 Lock 按钮触发 WS op `wall.lock` / `wall.unlock`（owner-only：caller UUID 必须 == wall.owner_uuid）；前端 readonly UI 是 lock 唯一执行者，后端编辑 op 路径与 lock 完全解耦（动态展示用例必需）。

Placeholder 地图样式：**浅灰底 + 顶部 "HikariCanvas" 水印 + 底部方位坐标文字**（M2 使用预烘焙位图 ASCII 字表；中文字体待 M4 渲染引擎接入后再回填）。

**模板系统 v1：**
- YAML 模板格式
- 内置 5 个起步模板：横排路牌、竖排牌匾、方形门牌号、霓虹招牌、地铁站牌
- 编辑器模板面板支持加载服务器/玩家自定义模板

**基础设施：**
- 配置文件（HTTP 端口、绑定地址、字体、模板、权限、池大小）
- 权限节点（`canvas.edit` / `canvas.admin` / 按模板细分；完整节点表见 `docs/security.md` §7）
- SQLite 存储：成品记录、地图 owner、分组、创建时间
- 网络绑定：**默认 `127.0.0.1`**，配置可改；文档提供 nginx/Caddy 反代 + TLS 示例

### 4.2 v1.x——应当有

- 多人协作锁：同一墙面排他占用（先到先得）
- 颜色预设包（霓虹、金色牌匾、黑白报纸、老电影等）
- 竖排 CJK 标点自动旋转
- 经济/消耗集成（Vault，可选成本）
- 图标库：文字中嵌入内置图标（地铁标、箭头等）
- 导入外部图片（同时不打算做成通用图片插件，限定用于图标/logo 嵌入）

### 4.3 v2.0+——可以有

- 模板市场：玩家间分享 `.canvas` 包
- 多语言字体后备链（中文、日文、韩文、emoji）
- 在线协作：多玩家同时编辑同一招牌（类似 Figma）
- **时间轴动画**（0.6.0 规划，设计总纲 `docs/timeline.md`）：keyframe + easing 编排已有内容做非线性动画（After Effects-like）——"对已有内容进行编排"
- **视觉运行时**（0.7.0 规划）：Scratch-like 积木逻辑，事件驱动条件分支编排实时/常更新数据（如地铁到站亮灯、PvP MVP 播特效）——"对未知/实时内容进行编排"，无时间轴
- 3D 排布：多面墙体的整体设计与一次性部署

> **两个编辑器互补，一个画布只能选一种分支**：时间轴编辑器（已有内容的非线性动画）vs 视觉运行时（实时数据的逻辑编排）。
>
> **0.5.0+ 版本路线**（性能 Benchmark → 时间轴 → 视觉运行时）详见 `docs/dynamic-data.md §13`；当前进度速览见 `CLAUDE.md` 路线图表。

### 4.4 不做（Out of Scope）

- 通用图片上传（已有 ImageOnMap 等成熟方案）
- 视频/GIF 播放
- 3D 文字（与 `text_display` 定位冲突）
- 跨服同步
- 非 Paper 分支（Spigot/Bukkit 性能与 API 不足）

---

## 5. 技术方案

### 5.1 技术栈

**后端（插件）：**

| 项 | 选择 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 | Java | **21** | 守 Paper 1.21 LTS；向 26.x 升级时再上 Java 25 |
| 构建 | Gradle (Kotlin DSL)，多模块 | **9.4.1** | 插件 + 前端子模块 |
| Paper 插件开发工具 | `paperweight-userdev` | **2.0.0-beta.21** | 官方唯一支持最新版；Mojang mappings 输出 |
| 平台 | Paper API | **1.21.11**（`1.21.11-R0.1-SNAPSHOT`） | 锁 1.21 LTS 分支，向 26.x 平滑升级 |
| 图形 | Java AWT / Graphics2D | JDK 内置 | |
| HTTP / WS | Javalin | **7.1.0** | 轻量、Kotlin/Java 通吃 |
| 数据包 | PacketEvents | **2.11.2** | 1.21.x 最终稳定版；升 26.x 换 2.12.x |
| 配置 / 模板 | jackson-dataformat-yaml | **2.18.2**（与 jackson-databind 同版本） | M6 决策：与项目 Jackson 主线一致，省手写 mapping；config.yml + 模板 yaml 都走它 |
| 持久化 | PersistentDataContainer + SQLite（HikariCP + JDBI） | — | 地图元数据、成品记录 |
| 命令 | Paper Brigadier API | — | 原生 Tab 补全 |
| 测试 | JUnit 5 + MockBukkit | — | 单元/集成 |

**前端（编辑器）：**

| 项 | 选择 | 说明 |
| --- | --- | --- |
| 框架 | Vue 3 + TypeScript | 生态成熟、学习曲线友好 |
| 构建 | Vite | 热更新、产物小 |
| UI 组件 | 自建 + Tailwind CSS | 编辑器风格特殊，组件库限制较多 |
| 画布引擎 | Konva.js | 2D 画布库，层、变换、事件成熟 |
| 字体 | 同后端 TTF 转 WOFF2 打包 | 保证双端一致 |
| 状态管理 | Pinia | |
| 通信 | 原生 WebSocket + JSON 协议 | |

**集成：** 前端子模块 `web/` 由 Vite 构建输出到 `src/main/resources/web/`，插件 jar 打包时一并包含。单 jar 部署。

### 5.2 关键技术机制

#### 5.2.1 双端渲染一致性

浏览器与 Java 必须出同一张图，否则预览与游戏内不一致。
- 两端加载**同一个 TTF 文件**（前端转 WOFF2）
- 浏览器 Canvas：`ctx.imageSmoothingEnabled = false`、`font-smooth: never`
- Java Graphics2D：`KEY_TEXT_ANTIALIASING = OFF`、`KEY_RENDERING = SPEED`
- 建立像素级对比测试台（snapshot test），CI 里跑多组文字比对（**M4 渲染引擎立项期同步搭建**；M1 不含）

#### 5.2.2 预览地图池

**整个项目的技术核心。** 没做好就会刷爆 `idcounts.dat`。

- 插件启动时从配置读取池大小（默认 64 张），一次性 `Bukkit.createMap()` 或从 SQLite 恢复现有池
- 池状态：`FREE` / `RESERVED(reservedBy)`，其中 reservedBy 在 M5.5 wall 模型下统一格式 `wall:<wall_id>`
- 创建新画：按需借出 N×M 张 FREE → RESERVED（owner = `wall:<wall_id>`）
- 打开已有画：bind 同 owner 接管 RESERVED 的 maps（不再借新）
- 编辑过程：**不新建 MapView**，只通过 `PacketEvents` 发 `ClientboundMapItemDataPacket` 更新像素
- 删除画（`/canvas delete`）：RESERVED → FREE
- 池枯竭：提示用户稍后/按配置自动扩容（上限防失控）

> M5.5 起废除原 `PERMANENT` 状态：wall 占的 map 一直 RESERVED 直到 wall 被删除；publish 不动池。

#### 5.2.3 帧率与压缩策略

- **静止：0 fps**（不推送，最后一帧显示持续）
- **输入中：** 前端防抖 100ms；服务端再做 5 fps 节流
- **session 关闭前最后一帧：** 一次完整推送（M5.5 起改由 cancel/disconnect 时 throttler flush，无显式 commit 触发）
- **脏矩形：** 局部改字只推改动块（map packet 原生支持 `x/y/columns/rows` 子区域）
- **压缩：**
  - 浏览器 ↔ 插件：WebSocket `permessage-deflate`（JSON 指令压缩率高）
  - 插件 ↔ MC 客户端：MC 协议层 zlib（阈值 256B 自动）
- 不自行加第三层压缩——收益不抵复杂度

#### 5.2.4 网络绑定与安全

- **默认 `bind: 127.0.0.1`**，配置可改 `0.0.0.0` 并强制警告
- 服主公网场景：文档给 nginx 反代 + Let's Encrypt TLS 模板；WSS 在反代层终止；插件本体只说 HTTP
- Token：玩家游戏内 `/canvas confirm` 或 `/canvas open` 生成，单次使用，15 分钟过期，绑定 UUID
- 限流：每玩家 WS 消息 20 msg/s；握手失败 N 次拉黑 IP
- 审计：所有 session/wall 关键事件记 SQLite 日志（SESSION_BEGIN/CONFIRM/CANCEL，WALL_PUBLISH/UNPUBLISH/DELETE，POOL_*）

#### 5.2.5 直接发包绕开 MapRenderer

Paper 的 `MapRenderer` 是 per-tick 调度，主动推送需要自己构造 `ClientboundMapItemDataPacket`：

```
MapData data = new MapData(
    startX, startY,   // 脏矩形起点
    width, height,    // 脏矩形尺寸
    paletteBytes      // 每像素 1 字节调色板索引
);
packet = new ClientboundMapItemDataPacket(mapId, scale, locked, null, data);
PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
```

#### 5.2.6 向 Paper 26.x 的平滑升级策略

Paper 26.1（2026-03 起）移除对插件的 Spigot 重映射：任何直接触碰 NMS / 混淆类名的插件在 26.x 必崩。为让未来服主升级只需改几行版本号、不动代码，开发期严守以下三条纪律：

1. **禁用 NMS。** 任何 `net.minecraft.*` / 服务端内部类的直接调用一律禁止；只用公开 Bukkit API + PacketEvents。
2. **PacketEvents 调用集中。** 所有 `sendPacket` 走 `plugin/deploy/MapPacketSender.java` 一个类，其他模块不直接碰 PacketEvents；未来 PacketEvents API 破坏时修改面仅此一处。
3. **Mojang mappings 输出。** `paperweight-userdev` 默认产出即 Mojang mapping；不做 reobf；plugin jar 直接兼容 26.x 的非混淆 server jar。

**未来升级 26.x 时，实际改动点清单：**

```
build.gradle.kts    paper-api 1.21.11 → 26.x.x / PacketEvents 2.11.2 → 2.12.x
                    Java toolchain 21 → 25 / userdev 版本号
paper-plugin.yml    api-version 字段
```

Java 代码本身无需改动（除非 PacketEvents 2.12 对 `ClientboundMapItemDataPacket` 构造有破坏，届时只改 `MapPacketSender` 一个类）。

#### 5.2.7 性能测评原则（0.5.0 Benchmark 设计前提）

§2.1 设计哲学在"性能测评"上的具体落实。0.5.0 将做一套 production-grade 性能 Benchmark，遵守四条：

1. **后台模拟，不破坏世界。** Benchmark 在 async 线程跑**真实渲染代码路径**（`CanvasCompositor.rasterize` + `toPaletteSlice`），输入是程序生成的 fake `ProjectState`（招牌 / 渐变 / dither / 混合等 scene）。**不占地图池、不放 ItemFrame、不需要真实玩家 viewer、不污染 `idcounts.dat`**——可重复、随时跑、跑炸也不影响在线玩家。渲染管线是纯软件 idempotent 函数（`ProjectState → BufferedImage`），无须真实环境触发。
2. **数据透明，不替服主决策。** 报告给"服务端可控成本"（rasterize p50/p95/p99、GC 压力、per-element-type 耗时分解、模拟 viewer 数的序列化成本）+ 一条"主线程预算 ÷ 单 wall 开销"换算公式。**不给"你能开 N 个 wall"的结论数字**——把 50 mspt 预算公式交给服主自己代入。
3. **测我们能控制的。** rasterize / palette LUT 量化 / GC 分配速率 / 模拟多 viewer 的 packet 序列化成本——这些都是服务端 CPU/内存范畴、纯软件可测。
4. **不擦屁股——网络一律不测。** 带宽、传输压缩比、RTT、丢包、服主有没有开 zlib 压缩——全部不测、不估、不算（理由见 §2.1 原则 3）。压缩效果取决于服主自己的配置和具体用法，服务端测出来的"压缩率"没有迁移意义。

> 性能测评的基本单位推敲、`tile-refresh` 换算的局限、50 mspt 预算公式，详见 `docs/dynamic-data.md §13.3`。

### 5.3 项目结构

```
hikari-canvas/
├── settings.gradle.kts
├── build.gradle.kts
├── plugin/                                    # 插件子模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/moe/hikari/canvas/
│       │   ├── HikariCanvas.java          # 主类
│       │   ├── render/
│       │   │   ├── FontRegistry.java
│       │   │   ├── TextRenderer.java
│       │   │   ├── PaletteMapper.java         # RGB → map palette LUT
│       │   │   └── effect/ (Stroke, Shadow, Glow)
│       │   ├── template/
│       │   │   ├── Template.java
│       │   │   ├── TemplateLoader.java
│       │   │   └── TemplateRegistry.java
│       │   ├── session/
│       │   │   ├── EditSession.java           # 编辑会话
│       │   │   ├── SessionManager.java
│       │   │   └── TokenService.java          # 登录 token
│       │   ├── pool/
│       │   │   ├── MapPool.java               # 预览地图池（核心）
│       │   │   └── PooledMap.java
│       │   ├── deploy/
│       │   │   ├── FrameDeployer.java         # 物品框部署
│       │   │   ├── WallResolver.java          # 墙面识别
│       │   │   └── MapPacketSender.java       # PacketEvents 发包
│       │   ├── web/
│       │   │   ├── WebServer.java             # Javalin
│       │   │   ├── WebSocketHandler.java
│       │   │   ├── protocol/                  # WS 消息模型
│       │   │   └── auth/
│       │   ├── storage/
│       │   │   ├── SignRecord.java
│       │   │   └── SignDatabase.java          # SQLite
│       │   ├── command/
│       │   │   └── CanvasCommand.java
│       │   └── config/
│       │       └── PluginConfig.java
│       └── resources/
│           ├── plugin.yml
│           ├── config.yml
│           ├── fonts/SourceHanSansSC-Regular.otf
│           ├── templates/*.yml                # 5 个起步模板
│           └── web/                           # Vite 构建产物拷贝到这里
├── web/                                       # 前端子模块
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.ts
│       ├── App.vue
│       ├── components/
│       │   ├── Canvas/                        # Konva 画布
│       │   ├── Toolbar/
│       │   ├── LayerPanel/
│       │   ├── PropertiesPanel/
│       │   └── TemplateGallery/
│       ├── stores/                            # Pinia
│       ├── network/WebSocketClient.ts
│       ├── render/PreviewRenderer.ts          # 与后端一致的渲染逻辑
│       └── assets/fonts/*.woff2
├── docs/
│   ├── architecture.md                        # 实时投影、地图池详述
│   ├── deployment.md                          # 服主部署 + 反代配置
│   └── template-spec.md                       # 模板 YAML 规范
└── README.md
```

### 5.4 模板 YAML 格式（初版）

```yaml
id: subway_station
name: 地铁站牌
description: 标准地铁站风格，横排，白底黑字，上方线路色条
version: 1

canvas:
  size: auto          # auto | fixed
  min_maps: [3, 1]
  max_maps: [8, 2]
  background: "#FFFFFF"
  padding: [8, 8, 8, 8]

layout:
  type: stack         # stack | grid | free
  direction: vertical
  elements:
    - type: rect
      height: 16
      color: "${line_color}"
    - type: text
      content: "${name}"
      font: "sourcehan"
      size: 48
      color: "#000000"
      align: center
      pixelated: true

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
```

---

## 6. 里程碑

| 阶段 | 目标 | 工期 | 产出 |
| --- | --- | --- | --- |
| M0 立项 | 需求确认、架构定稿 | 已完成 | PROPOSAL.md |
| M1 端到端验证 | 浏览器按钮 → WS → 更新游戏内一张地图像素 | 已完成（2026-04-20） | 实测通过；`docs/journal.md` 记录所有 T1~T7 过程 |
| M2 会话与地图池 | token 登录、墙面锁定、预览地图池借还 | 已完成（2026-04-21） | 实测通过；`docs/journal.md` 记录 T1~T12 全过程 |
| M3 实时投影 | 差分推送、防抖节流、多图拼接、双端一致性测试 | 已完成（2026-04-21，方案 α：op 骨架 + `hello_world` 硬编码模板，正规模板系统 M6） | `docs/journal.md` 记录 T1~T13；13/13 任务完成 |
| M4 渲染引擎 | 字体、调色板 LUT、效果（描边/阴影/发光）；**同步建立双端像素级 snapshot 测试台**（见 §5.2.1） | 已完成（2026-04-22；竖排文本 + 像素字体最近邻缩放 + 前端 Playwright snapshot 推迟到 M4.5 / M7） | `docs/journal.md` 记录 T1~T12；12/12 任务完成；5 fixture baseline 入 git |
| M5 编辑器 UI | Canva 式完整编辑器（画布、图层、工具栏、属性面板、撤销） | 已完成（2026-04-23；Playwright snapshot 测试台推迟到 M7） | `docs/journal.md` M5-A/B/C/D 全部段落；Vue 3 + Pinia + Tailwind 4 + Konva overlay + 字体 / 调色板 / TextLayout / 效果族 前后端镜像 |
| M6 模板系统 | YAML 解析 + 6 个内置模板 + 编辑器集成 | 已完成（2026-05-12；jackson-yaml + TemplateRegistry 热重载 + 6 模板 + TemplateGallery 前端） | `docs/template-spec.md` 完整契约 |
| M7 打磨发布 | 工具栏 / Tooltip / HelpModal / config.yml / 部署文档 / 已发布墙保护 / 缩略图 / grid / icon / Move 工具 / HomePage 美化 / param group UI | 已完成（2026-05-13） | `docs/deployment.md` v1 |
| M8 图层 + 协议 v2 ✅ | layers + activeLayerId + opacity + blendMode + gridSize + guides；一次性 migrate；图层面板 + 多选 | 2 周（实际 1 天） | 协议 v2 + data v2 + 客户端拒 v1 |
| M9 PathElement + 工具栏 ✅ | 通用 path (M/L/Q/C/Z + marker)；CircleElement、ShapeElement；4 个工具切换器 | 1.5 周（实际 1 天） | "线/箭头/软线/星/点/圆" 全部统一通过 path/circle/shape |
| M10 调色板 ✅ | 项目色板 + 最近色板 + MC-friendly 默认色板 + swatches UI + alpha 通道 | 3 天（实际 30min） | 类 Figma 色板面板 |
| M11 渐变 + Dither ✅ | fill 升 union（solid/linear-gradient/radial-gradient）；Bayer 4×4 dither 双端实装 | 1 周（实际 4.5h） | 第一个支持 dither 的元素类型上线 |
| **2026-05-14 lock-state 重设计 ✅** | published 概念整体砍除；wall.lock/unlock owner-only WS op；后端编辑路径与 lock 解耦 | 当天插入 | 为未来动态化展示用例（视频 / 轮播）让出后端编辑路径 |
| M12 笔刷 + 数位板 ✅ | brush.\* WS 通道；BrushStrokeElement + 原始 points + pressure；RDP + Catmull-Rom；floating preview；BrushPanel | 1.5 周（实际 3h） | 类 Procreate 的笔触体验 |
| **M13 图片导入 + 蒙版** | /api/upload + 6 层校验栈；ImageElement（hash 内容寻址 sha256[:16]）；SVG path mask（B 风格数据模型，A 风格 dropdown UI） | 1 周（估 ~8h） | 用户可拖图进编辑器；mask 完全体接口预留 |

**M13 决策摘要**：
1. mask 数据模型 = SVG path d 字符串（留 v2 lasso / 自由 mask 完全体接口）；v1 UI dropdown 4 预设
2. 上传入口 = file input + drop + paste（Figma 标准）
3. 多文件批量、mask shape over image 拖动、feather 边缘羽化、URL 粘贴均 **v1 不做**
4. LRU 清理 = 每次 upload 前检查总配额超限删最老
5. mask + dither 顺序 = 先 dither 再 mask
6. ImageIO 解码隔离 = ExecutorService.submit 200ms timeout 防压缩炸弹

**总工期估算：约 6 个月**（单人兼职节奏；实际 M0-M12 累计 wall-clock 约 7 周；M13 估完 8 周 = 2 个月，比规划缩短约 4 个月）。

> 上表为立项期（M0-M13）规划快照。项目已远超此表，推进到变量系统 + Plugin API、铁路网络、时间轴编辑器、积木脚本 + 补间动画、工程导入导出（.canvas）+ SVG 矢量导入等。**完整里程碑见 `CLAUDE.md` 路线图表；逐条进度以 `docs/journal.md` 为准。**

**长远 TODO（立项期登记；部分已落地，逐条进度见 `docs/journal.md`）：**
- ✅ 图层缩略图 · 图层颜色标签；图层 mask · smart object · 图层组仍属远期
- mask：✅ lasso 工具 · feather 羽化；mask shape over image 拖动编辑 · 多 mask 组合仍待
- ✅ 对齐 / 分布 / 分布间距工具（Photoshop "align" 工具栏）
- 多人协作 OT/CRDT（明确不做）
- 模板包生态：✅ `.canvas` 工程导入导出（单工程）；多模板打包 + 资产仍待
- 玩家身份认证 + HomePage 点击直开 + 权限隔离（独立路线）—— 仍待
- ✅ 动态化展示（实时数据：变量系统 + 时间轴 + 积木脚本）；视频仍不做

**关键决策点：** M1 完成后评估双端渲染一致性与 packet 推送稳定性，若任一项存在根本性障碍则重新评估方案。

---

## 7. 风险评估

| 风险 | 等级 | 应对 |
| --- | --- | --- |
| **预览地图池机制缺陷导致 `idcounts.dat` 膨胀** | **高** | M2 阶段重点设计与压测；提供 `cleanup` + 池健康指标监控 |
| **浏览器 Canvas 与 Java Graphics2D 渲染不一致** | **高** | **M4 渲染引擎立项期建像素级 snapshot 测试台**（M1 阶段两端均无字体/排版逻辑，snapshot 无的放矢；待 M4 两端真实渲染代码就位再同步搭测试台）；像素字体为主、禁抗锯齿、同一 TTF |
| **`PacketEvents` 版本升级破坏兼容** | 中 | 锁版本 + CI 集成测试 + 订阅上游 release |
| **服主缺乏独立 HTTP 端口** | 中 | 文档给 nginx/Caddy 反代方案、支持路径前缀（`/canvas/` 挂载） |
| **公网暴露安全风险** | **高** | 默认绑 `127.0.0.1`；公网必须反代 + TLS；token 单次+过期+UUID 绑定；限流 + 审计日志 |
| **调色板色彩限制导致效果不佳** | 中 | 像素风本身偏好低饱和高对比，起步模板围绕此设计；提供推荐色盘 |
| **中文字体版权** | 中 | 仅打包 SIL OFL 字体；商业字体用户自备 |
| **物品框被玩家破坏** | 中 | 成品框 `INVISIBLE + FIXED`，PDC 加保护标记 |
| **多玩家并发编辑资源竞争** | 中 | 墙面排他锁、每玩家限 1 活跃会话、池有配额 |
| **编辑器一步到位工期过长** | 中 | M5 内部分两周期：先画布 + 文字 + 模板（3 周），再图层 + 形状 + 导入导出（2 周），中间可提前发 alpha |
| **Paper API 版本变动（特别是 26.x 去重映射）** | 中 | 锁定 1.21.11 LTS；严守三条架构纪律（§5.2.6）：禁 NMS、PacketEvents 调用集中到单一 sender、Mojang mappings 输出 |
| **`text_display` 未来支持自定义字体** | 低 | 像素风 + 模板生态定位仍独立 |

---

## 8. 成功标准

### 8.1 技术指标
- 编辑时端到端延迟（浏览器按键 → 游戏内显示）< 300ms
- 单次 confirm 部署 8×4 物品框 + 首帧 < 500ms（主线程不卡顿）
- 插件内存稳态 < 100MB（含预览池 64 张地图）
- 预览池 1 周满负载运行，地图 ID 净增量 = 0（会话结束正确归还）
- 10 玩家并发编辑无明显延迟

### 8.2 产品指标（v1.0 发布后 3 个月）
- 下载量：Modrinth / SpigotMC 累计 1000+
- 社区反馈：至少 3 个社区成员贡献的模板
- 实际使用：有服务器在宣传中展示使用本插件制作的招牌
- 至少 1 个 YouTube / B 站视频演示（非官方）

### 8.3 主观标准
- 一个新玩家打开编辑器 30 秒内能做出第一块招牌
- 做一块招牌的时间从「PS 方案 10~30 分钟」降到「1 分钟以内」
- 实时投影体验让人「第一次见就想截视频发出去」

---

## 9. 开源与协议

- 代码协议：MIT
- 打包字体协议：SIL OFL 1.1（思源黑体）
- 仓库：GitHub（公开）
- 发布渠道：Modrinth（主）、SpigotMC、Hangar

---

## 10. 下一步行动

1. 创建 Gradle 多模块骨架（`plugin/` + `web/`）与 `plugin.yml`
2. 写 `docs/architecture.md` — 实时投影与地图池详细设计
3. 完成 M1 技术验证：
   - 浏览器一个按钮
   - WebSocket 连接到插件
   - 玩家游戏内预设一张 map → 按钮点击触发插件发 `ClientboundMapItemDataPacket` 把这张地图涂成红色
4. M1 录屏评估，决定进入 M2
