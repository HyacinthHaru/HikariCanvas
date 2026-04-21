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
  - 提交：一次全质量完整推送
- 压缩：WebSocket 开 `permessage-deflate`（JSON 指令）；map packet 走 MC 协议层 zlib

**命令与交互（`/canvas` 前缀）：**

两种选区方式组合使用：
- **命令模式**：`/canvas edit` 进入 `SELECTING` 状态；之后玩家**空手或任何方块**点击墙面两个对角方块即可（左键 pos1 / 右键 pos2）；每次点击聊天栏回显坐标与初步识别结果
- **工具模式**：`/canvas wand` 领取一根命名金铲「Canvas Wand」；持 wand 时左/右键**无需先打命令**即能选区，玩家自主决定是否占背包一格

子命令清单：
- `/canvas edit` — 开启 SELECTING 状态
- `/canvas wand` — 发放 Canvas Wand（幂等，已有不重发）
- `/canvas confirm` — 确认当前选区：**立即挂物品框 + 借池地图（填 placeholder）+ 签发 URL**
- `/canvas cancel` — 撤销 selection 或终止活跃会话
- `/canvas commit` — 与浏览器 `{op: "commit"}` 等价的命令回退通道
- `/canvas cleanup` — 管理员清理废弃成品地图
- `/canvas stats` / `/canvas audit` — 管理员查看池状态与审计

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
- 动画招牌：多帧地图做 LED 滚动屏（性能需谨慎评估）
- 3D 排布：多面墙体的整体设计与一次性部署

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
| 配置 | SnakeYAML | — | 模板 + 主配置 |
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
- 池状态：`FREE` / `RESERVED(sessionId)` / `PERMANENT(signId)`
- 编辑会话开启：按需借出 N×M 张 FREE → RESERVED
- 编辑过程：**不新建 MapView**，只通过 `PacketEvents` 发 `ClientboundMapItemDataPacket` 更新像素
- 提交：RESERVED → PERMANENT，写入 SQLite，打 PDC owner 标记；从池中抽走，自动补新 FREE
- 取消：内容清空 → 归还 FREE
- 池枯竭：提示用户稍后/按配置自动扩容（上限防失控）

#### 5.2.3 帧率与压缩策略

- **静止：0 fps**（不推送，最后一帧显示持续）
- **输入中：** 前端防抖 100ms；服务端再做 5 fps 节流
- **提交：** 一次完整推送
- **脏矩形：** 局部改字只推改动块（map packet 原生支持 `x/y/columns/rows` 子区域）
- **压缩：**
  - 浏览器 ↔ 插件：WebSocket `permessage-deflate`（JSON 指令压缩率高）
  - 插件 ↔ MC 客户端：MC 协议层 zlib（阈值 256B 自动）
- 不自行加第三层压缩——收益不抵复杂度

#### 5.2.4 网络绑定与安全

- **默认 `bind: 127.0.0.1`**，配置可改 `0.0.0.0` 并强制警告
- 服主公网场景：文档给 nginx 反代 + Let's Encrypt TLS 模板；WSS 在反代层终止；插件本体只说 HTTP
- Token：玩家游戏内 `/canvas confirm` 生成（`/canvas edit` 只是开启 selecting 状态），单次使用，15 分钟过期，绑定 UUID
- 限流：每玩家 WS 消息 20 msg/s；握手失败 N 次拉黑 IP
- 审计：所有 commit/cancel/cleanup 记 SQLite 日志

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
| M4 渲染引擎 | 字体、调色板 LUT、效果（描边/阴影/发光）；**同步建立双端像素级 snapshot 测试台**（见 §5.2.1） | 2 周 | 单元测试覆盖 + snapshot 基准集 |
| M5 编辑器 UI | Canva 式完整编辑器（画布、图层、工具栏、属性面板、撤销） | 5 周 | 功能完整前端 |
| M6 模板系统 | YAML 解析 + 5 个内置模板 + 编辑器集成 | 1 周 | 模板规范文档 |
| M7 打磨发布 | 多玩家测试、性能、bug、部署文档 | 2 周 | v1.0 release |

**总工期估算：约 3.5 个月**（单人兼职开发节奏）。

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
- 单次 commit 生成 8×4 地图招牌 < 500ms（主线程不卡顿）
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
