# HikariCanvas

Minecraft Paper 1.21+ 插件 + 内嵌 Web 编辑器。通过 TTF 字体渲染 + 模板系统 + 实时投影，在游戏内生成文字招牌。

## 标识

| 项 | 值 |
|---|---|
| 包名 | `moe.hikari.canvas` |
| 命令前缀 | `/canvas` |
| 权限前缀 | `canvas.` |
| PDC namespace | `hikari_canvas` |
| 工程文件扩展名 | `.canvas` |
| 仓库 | https://github.com/HyacinthHaru/HikariCanvas（MIT） |

## 技术栈（锁定版本）

| 项 | 版本 |
|---|---|
| Java | **21**（不升 25，守住 1.21 LTS） |
| Paper API | **1.21.11**（`1.21.11-R0.1-SNAPSHOT`） |
| Gradle | **9.4.1** |
| `paperweight-userdev` | **2.0.0-beta.21**（官方唯一支持最新版） |
| PacketEvents | **2.11.2**（1.21.x 最终稳定版） |
| Javalin | **7.1.0**（6 已过时，不用） |
| 插件描述文件 | **`paper-plugin.yml`**（不用 `plugin.yml` 旧格式） |
| 本地测试服 | `./gradlew runServer`（paperweight-userdev 提供） |

其余：HikariCP + JDBI + SQLite、**jackson-dataformat-yaml（2.18.2，与 jackson-databind 同版本）**、JUnit 5 + MockBukkit、AWT/Graphics2D。

> **M6 决策（2026-05-11）**：YAML 解析改用 jackson-dataformat-yaml，不用 SnakeYAML。理由：项目已全面 Jackson 化（ProjectState / PatchOp / WallRepo 都靠 Jackson），同 ObjectMapper 配置 + record 自动 mapping 可省 ~300 行手工 YAML→Map 转换 + 校验。安全考量上 jackson-dataformat-yaml 默认即关闭 polymorphic typing，不存在 SnakeYAML SafeConstructor 才解决的 `!!java/*` tag RCE 面（见 `docs/security.md §4.3`）。

**前端**：Vite + TypeScript；Vue 3 + Konva + Pinia 于 **M5 引入**（M1~M4 前端仅原生 DOM）。

## 文档先行

**契约类（已定稿，代码必须与之一致）：**

- `PROPOSAL.md` — 立项总纲
- `docs/architecture.md` — 架构与核心机制
- `docs/protocol.md` — WebSocket v1 协议
- `docs/rendering.md` — 渲染管线与双端一致性
- `docs/template-spec.md` — 模板 YAML v1
- `docs/data-model.md` — SQLite / PDC / `.canvas` 格式
- `docs/security.md` — 威胁模型与安全规范

**操作类（M1 之后按需写，先写易过时）：** `deployment.md` / `development.md` / `api.md` / `troubleshooting.md`

**规则：**
- 写代码前先对照契约文档检查实现意图
- 要改契约 → **先改 `docs/*.md`，再改代码**
- 文档里的「未决问题」清单，实现时回填答案并从列表移除

## Git 提交约定

1. 身份固定：`HaruHyacinth <122684177+HyacinthHaru@users.noreply.github.com>`（本地 `.git/config` 已配，**不动全局**）
2. 所有 commit 必须 SSH 签名：`~/.ssh/id_ed25519.pub`，本地已开 `gpg.format=ssh` + `commit.gpgsign=true` + `tag.gpgsign=true`
3. **禁止** `Co-Authored-By: Claude`（以及任何形式的 Claude 署名）
4. **每次 commit 后立刻 `git push origin main`**——不堆积、不集中推
5. **每次修改必须在 `docs/journal.md` 顶部追加一条**（日期 · 范围 · 改动 · 关联文件）
6. 签名失败**不要用 `--no-gpg-sign` 绕过**，先查原因
7. 签名验证：`gh api /repos/HyacinthHaru/HikariCanvas/commits/<sha> --jq '.commit.verification.verified'` 应返回 `true`

## 架构纪律（26.x 升级保障，不可越界）

Paper 26.1 起移除插件的 Spigot 重映射，任何碰 NMS 的插件 26.x 必崩。为让未来升级只改版本号、不动代码：

1. **禁用 NMS。** 任何 `net.minecraft.*` / 服务端内部类一律禁止；只用公开 Bukkit API + PacketEvents
2. **PacketEvents 调用集中。** 所有 `sendPacket` 走 `plugin/deploy/MapPacketSender.java` 一个类，别的模块不直接碰 PacketEvents
3. **Mojang mappings 输出。** `paperweight-userdev` 默认行为，不开 reobf

见 PROPOSAL.md §5.2.6 完整说明。

## 其他不可越界的技术决策

- **预览地图池**是技术核心：编辑期间**只刷像素、不新建 MapView**，避免 `idcounts.dat` 膨胀——这一项做不好整个项目报废
- **双端渲染一致性**：浏览器 Canvas 与 Java Graphics2D 用同一 TTF 文件、禁抗锯齿；TextLayout 两端**统一 `canonicalCharWidth`**（M5-D2 起，ASCII=0.5×fontSize，CJK=fontSize；**不读 font metrics** 以避 hinting 差异）
- **帧率策略**：静止 0fps · 输入防抖 100ms + 5fps 上限 · 提交全量
- **网络默认绑 `127.0.0.1`**；公网部署必须 nginx/Caddy 反代 + TLS
- **字体**：只打包 SIL OFL 协议字体；M4 内置 **Ark Pixel 12px Monospaced zh_cn** + **思源黑体 SC Regular** 两枚。Gradle `downloadFonts` 构建期抓到 `build/downloaded-fonts/`（SHA-256 pin）→ `syncFontsToWeb` 同步到 `web/public/fonts/` 供前端 `@font-face` 使用 + `processResources` 合并进 shadow jar 供后端 `FontRegistry` 使用。仓库不入字体文件，`.gitignore` 排除。其他字体让用户自己放到 `plugins/HikariCanvas/fonts/`
- **构建期 palette**：Gradle `generatePalette`（独立 `generator` sourceSet）从 Paper `MapPalette` 导 248 色 JSON 到 classpath 根；后端 `PaletteLut` + 前端 `PaletteLut`（镜像）都读它，32³ Lab LUT，O(1) 匹配

## 里程碑

M0 立项 ✅ → M1 端到端验证 ✅（2026-04-20） → M2 会话与地图池 ✅（2026-04-21） → M3 实时投影 ✅（2026-04-21） → M4 渲染引擎 ✅（2026-04-22；竖排合并到 M5-C） → M5 编辑器 UI ✅（2026-04-23） → M5.5 wall 模型重构 ✅（2026-04-27） → M6 模板系统 ✅（2026-05-12；6 内置模板 + TemplateGallery） → M7 polish ✅（2026-05-13；保护/缩略图/grid/icon/Move 工具/HelpModal/config.yml/group UI/部署文档） → **M8 图层 + 协议 v2（2w）** → M9 PathElement + 工具栏（1.5w） → M10 调色板（3d） → M11 渐变 + Dither（1w） → M12 笔刷 + 数位板（1.5w） → M13 图片导入 + 蒙版（1w）。总工期约 6 个月（含 M8-M13 共约 8 周）。

## M8 路线（2026-05-13 定稿，未实施）

**核心：把"扁平 element list"升级为"layer 树"，同时升协议到 v2 顺便加几个早该有的字段。**

### 8 个锁定决策

1. PathElement 一统线/箭头/软线/笔刷/点（M9 实施；M8 不动新元素类型）
2. **图层先做** —— M8 做完图层 + 协议 v2 + 迁移，再上 PathElement
3. 元素级 `renderMode: 'clean' | 'dither'`，默认 clean
4. wall 接力编辑（任意 canvas.edit 玩家 /canvas open <wall_id>）已是现状，无需新做
5. 图片导入 config 可调（max-size-kb / max-per-wall / max-uploads-per-day / max-total-storage-mb / allowed-mime / downscale-max-edge）；权限节点 `canvas.upload` 默认绑 `canvas.edit`
6. **协议 v2 一次性升级**：layers + activeLayerId + canvas.gridSize + canvas.guides + element.opacity + element.blendMode；切断 v1 客户端（auth 时拒 `clientProtocolVersion < 2`）
7. BlendMode v1 集合：`normal / multiply / screen / overlay` 4 个
8. opacity 在 MC 调色板上 = "先 alpha-composite 到背景再量化"，不会真透明，颜色会变浅；用户能接受

### M8 子阶段（约 2 周）

- **M8-A 数据模型 + 协议 v2 类型** — ProjectState.layers / Layer record / Element.opacity+blendMode / Canvas.gridSize+guides；前后端 TS+Java 同步
- **M8-B 持久化迁移** — `walls.project_json` lazy upgrade：读到 v1 形态 → 包成 Default Layer 写回；M8 启动时一次性 migrate 整库
- **M8-C 协议路径** — state.snapshot 新形态；state.patch 用 `/layers/{i}/elements/{j}/...` 路径；新增 layer.* op 族
- **M8-D 图层面板 UI** — 右栏新增 LayerPanel（缩略图列 v1 不做，列文字 + 可见/锁定/删除按钮 + 拖动重排）
- **M8-E 元素级 opacity / blendMode / 多选 / 网格 + 参考线** — RightPanel 加属性；CanvasView 加 marquee 多选 + grid overlay + guides 拖出标尺

### M8 远期 TODO（不做但记下）

- 图层缩略图（per-layer rasterize 端点 + 缓存）
- 图层颜色标签（用户给图层染色辅助识别）
- 图层 mask / group / smart object
- 多人协作（OT/CRDT）
- 对齐 / 分布工具（M9+ 多选基础上）

## M5.5 wall 模型重构（路线修正，2026-04-27 定稿）

**背景**：M2-M5 走的是「编辑 → commit 永久固化」二段式模型（drafts + sign_records 两表 / RESERVED + PERMANENT 两态 / `/canvas commit` 升级）。M5 实测下来「二次编辑已发布画」死路（WallResolver 把自己挂的 ItemFrame 当 OCCUPIED 拒），且 commit 后 drafts 没清导致状态机污染。

**新模型（已固化，不再讨论）**：

1. **一画一行 walls 表**：取代 `drafts` + `sign_records`。每行有稳定 `wall_id`（`w-<8hex>`，玩家可见）+ 可选 `alias`。`published_at` 是 nullable timestamp 标签——纯 UI/命令前置语义，**不影响底层行为**（始终可改）。
2. **MapPool 两态**：`FREE` / `RESERVED`。owner 统一 `wall:<wall_id>`，wall 占的 map 一直占着直到 `/canvas delete`，不自动释放。`PERMANENT` 状态废止。
3. **命令族**：`edit / confirm / cancel / open <id\|alias> / list / publish / unpublish / alias <name> / delete <id> [confirm]`。`commit` 命令**废止**（不是改名）。`/canvas delete` 需 30s 内 `/canvas delete <id> confirm` 二次确认。
4. **wand 瞄已有 ItemFrame**：HikariCanvas 自己挂的 → 不当 OCCUPIED；左/右键先 ActionBar 提示「This is wall <id> 'alias' — left-click again to open」，再次操作才打开二次编辑。第三方 ItemFrame 仍 OCCUPIED 拒绝。
5. **published 副作用**（Q2=a+b）：标签 + ItemFrame PDC 写 `published_at` 时间戳；M7 加 break 拦截让已发布画更难误删。除此之外游戏内零行为差。
6. **排他锁保留**：`byWall` 一墙一时刻一个活跃 session。多人协作（OT/CRDT）超 scope，不做。

**契约文档已更新**：`docs/architecture.md` §3 状态机 / §6 commit pseudocode / §7 PDC 标记 / `docs/data-model.md` §2 schema / `docs/protocol.md` §8.3 / `docs/security.md` 权限名 / PROPOSAL.md 命令族。具体 commit hash 见 `docs/journal.md` 2026-04-27 条目。

**写代码前重新对照契约**——这次重构涉及多模块协调，必须文档先行。

## 构建 / 开发流程速查

```bash
./gradlew :plugin:syncFontsToWeb    # 首次或字体规格变更时跑一次（~10min 下载）
./gradlew :plugin:runServer         # 本地 MC 1.21.11 dev server，挂新 shadow jar
cd web && npm install               # 首次
cd web && ./node_modules/.bin/vite build --clearScreen false   # 前端产物（Node 25 下偶卡，重跑即过）
./gradlew :plugin:test              # snapshot 测试（5 个 fixture）；baseline 变时 rm expected/*.png 重建
```

前端状态管理：`web/src/stores/{network,project,ui}.ts`（Pinia setup stores）。
WS 通讯封装：`web/src/network/wsClient.ts`（单例 `WsClient`）。
浏览器 console 调试：`window.__hk.send("op", payload)`。

**详细里程碑日志、每个 commit 的含义、踩坑归档**：`docs/journal.md`（日期倒序）。**细节与决策**查该文件优先于重新推理。
