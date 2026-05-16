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

M0 立项 ✅ → M1 端到端验证 ✅（2026-04-20） → M2 会话与地图池 ✅（2026-04-21） → M3 实时投影 ✅（2026-04-21） → M4 渲染引擎 ✅（2026-04-22；竖排合并到 M5-C） → M5 编辑器 UI ✅（2026-04-23） → M5.5 wall 模型重构 ✅（2026-04-27） → M6 模板系统 ✅（2026-05-12） → M7 polish ✅（2026-05-13） → M8 图层 + 协议 v2 ✅（2026-05-13） → M9 PathElement + 工具栏 ✅（2026-05-13） → M10 调色板 ✅（2026-05-13） → M11 渐变 + Dither ✅（2026-05-13） → 2026-05-14 lock-state 重设计 ✅ → M12 笔刷 + 数位板 ✅（2026-05-14） → 2026-05-14 全栈审查 + 3 bug 修复 ✅ → **M13 图片导入 + 蒙版（1w，最后一站）**。总工期约 6 个月（M0-M12 累计约 7 周 wall-clock）。

## M8-M12 已完成（详见 docs/journal.md）

- **M8 图层 + 协议 v2**：layers + activeLayerId + gridSize + guides + element.opacity/blendMode/renderMode + marquee 多选
- **M9 PathElement + 工具栏**：path/circle/shape 三新元素 + drag-to-create + line/arrow/circle/star 4 工具
- **M10 调色板**：ColorInput + 三色板（project/recent/default）+ alpha 通道
- **M11 渐变 + Dither**：Fill 联合类型（solid/linear/radial）+ Bayer 4×4 dither + FillInput
- **M12 笔刷 + 数位板**：BrushStrokeElement + RDP + Catmull-Rom + PointerEvent 接管 + floating preview + BrushPanel

## M13 路线（2026-05-14 定稿，未实施）

**核心：用户能拖图进编辑器；上传严格 6 层校验栈；ImageElement 支持 SVG path 蒙版（v1 UI 仅暴露预设几何，数据模型 path 形态预留 v2 完全体接口）。**

### 7 个锁定决策

1. **mask 数据模型 = SVG path d 字符串**（B 风格，留 v2 lasso / 自由 path mask 完全体接口）；mask 字段 `Mask { d: string, inverted: boolean }`，d 复用 M9 PathDValidator（M/L/Q/C/Z 子集，相对 element bbox `0..w / 0..h`）
2. **mask UI = RightPanel dropdown + 参数滑块**（4 个预设：none / circle / roundedRect / ellipse），内部把 dropdown 选择转 d 字符串；完全体 "mask shape over image" 拖动编辑 / lasso 自由绘制 留 v2
3. **上传入口 = file input + drop + paste 三种**（Figma 标准；多文件批量 v1 不做，单次 drop 多文件只接第一个）
4. **LRU 清理时机 = 每次 upload 前检查总配额**，超限时删最老 last-used 文件直到腾出空间；不做周期 scheduler
5. **mask + dither 顺序 = 先 dither 再 mask**（dither 在 mask 内部像素，避免 dither 边缘羽化错位）
6. **content-hash 内容寻址** `sha256[:16]`：跨 wall 引用同一文件不重复存；删 wall 不立即清文件，靠 LRU
7. **ImageIO 解码隔离** = `ExecutorService.submit(...).get(200, MS)` 防压缩炸弹 / 死循环；超时拒 `UPLOAD_REJECTED`

### M13 子阶段（约 1 周；实际估 ~8h）

- **M13-A 数据模型 + 协议**（~1h）— `ImageElement` record（source=sha256 hash + Mask 可选）；`Mask` record（d + inverted）；Element sealed permits 加 image；EditSession.buildImage / applyImagePatch；前后端 TS+Java 类型镜像；mask.d 复用 PathDValidator 校验
- **M13-B 后端 /api/upload + ImageStorage**（~2.5-3h，最高复杂度）— Javalin HTTP POST + multipart 解析；6 层校验栈（大小 / Content-Type / magic bytes / ImageIO 隔离解码 200ms / bbox sanity / downscale 1024）；`ImageStorage` 类（hash 内容寻址 + LRU）；DB 表 `image_uploads`（玩家 / 时间戳 / 字节数 / hash）；配额三层（per-wall / per-player 24h / total disk）；`GET /api/upload/quota`；`config.yml` images 段；权限节点 `canvas.upload` + `canvas.upload.bypass-limit`；UploadHandlerTest 全场景
- **M13-C 后端渲染**（~1.5h）— `CanvasCompositor.drawImage`：按 hash 加载缓存 BufferedImage + rotation / opacity / dither；mask 用 `Graphics2D.setClip(Path2D)`（复用 M9 PathParser 将 mask.d 转 Path2D）；inverted 用 `Area.subtract` 反算；文件缺失占位（同 IconElement 风格）；fixture 13-image + baseline
- **M13-D 前端拖拽 + UI**（~2h）— `types/protocol.ts ImageElement`；`PreviewRenderer.drawImage` 异步加载 fetch `/api/upload/{hash}`（同 IconElement async pattern）；CanvasView 拖拽 drop + 文件 input + Clipboard paste 三入口；上传进度 + 错误提示 + 配额 UI；RightPanel ImageElement 段（hash 缩略图 + mask dropdown + 参数滑块 + opacity / dither）；i18n 中英
- **M13-E polish + 测试 + journal**（~1h）— UploadHandlerTest 各拒绝路径 / 配额边界；fixture 13 baseline review；journal + commit + push

### 复杂度新增（前面里程碑没出现过）

- **真磁盘 IO**：之前都是内存 + DB；现在管 `plugins/HikariCanvas/uploads/` 目录 + LRU + 磁盘配额追踪
- **HTTP multipart**：之前全 WS；Javalin multipart 解析 + 流式读取避大文件 OOM
- **ImageIO 解码隔离**：压缩炸弹防御 = 单独 ExecutorService + 200ms get timeout + 失败回收资源
- **配额系统**：新 SQLite 表 + 每次 upload 前 3 查询（per-player 24h / per-wall / total disk）

### M13 v1 不做（留 future / v2）

- mask 拖动编辑模式（mask shape over image / lasso 自由绘制）
- 多文件批量上传（drop 多个）
- 蒙版边缘羽化（feather）/ 多 mask 组合
- URL 粘贴上传
- 图片 EXIF 信息读取 / 自动旋转
- 图片格式转换（如自动 PNG → WEBP 节省存储）

### M8 远期 TODO（不做但记下）

- 图层缩略图（per-layer rasterize 端点 + 缓存）
- 图层颜色标签（用户给图层染色辅助识别）
- 图层 mask / group / smart object
- 多人协作（OT/CRDT）
- 对齐 / 分布工具（M9+ 多选基础上）

## M5.5 wall 模型重构（路线修正，2026-04-27 定稿）

**背景**：M2-M5 走的是「编辑 → commit 永久固化」二段式模型（drafts + sign_records 两表 / RESERVED + PERMANENT 两态 / `/canvas commit` 升级）。M5 实测下来「二次编辑已发布画」死路（WallResolver 把自己挂的 ItemFrame 当 OCCUPIED 拒），且 commit 后 drafts 没清导致状态机污染。

**新模型（已固化，不再讨论）**：

1. **一画一行 walls 表**：取代 `drafts` + `sign_records`。每行有稳定 `wall_id`（`w-<8hex>`，玩家可见）+ 可选 `alias`。DB 列 `published_at`（M5.5 引入）**2026-05-14 起语义化为 lock 时间戳**：`null` = 可编辑，非 `null` = 已锁定（前端 readonly）。`owner_uuid` 为作者权限依据。
2. **MapPool 两态**：`FREE` / `RESERVED`。owner 统一 `wall:<wall_id>`，wall 占的 map 一直占着直到 `/canvas delete`，不自动释放。`PERMANENT` 状态废止。
3. **命令族**：`edit / confirm / cancel / open <id\|alias> / list / alias <name> / delete <id> [confirm]`。`commit` 命令**废止**（不是改名）。`/canvas publish` / `/canvas unpublish` 2026-05-14 砍（lock 状态由前端 TopBar UI 触发 WS op）。`/canvas delete` 需 30s 内 `/canvas delete <id> confirm` 二次确认。
4. **wand 瞄已有 ItemFrame**：HikariCanvas 自己挂的 → 不当 OCCUPIED；左/右键先 ActionBar 提示「This is wall <id> 'alias' — left-click again to open」，再次操作才打开二次编辑。第三方 ItemFrame 仍 OCCUPIED 拒绝。
5. ~~published 副作用（M5.5 决策已废 2026-05-14）~~ → **lock 状态重设计**：纯前端 readonly UI，后端只持元数据（owner_uuid + locked_at），不影响编辑 op 路径。游戏内零行为差。ItemFrame PDC 不再写 published_at；FrameProtectionListener "已发布拦截" 砍。详见下方 §lock-state。
6. **排他锁保留**：`byWall` 一墙一时刻一个活跃 session。多人协作（OT/CRDT）超 scope，不做。

## lock 状态（2026-05-14 引入，替代 M5.5 published 概念）

**目标**：让 wall 作者能"只读冻结"自己的画防误编辑；其他玩家拿到 `/canvas open <wall_id>` 也无法解锁。但保持后端编辑路径与 lock 状态完全解耦——未来动态化展示（视频 / 时间轮播）想在锁定的 wall 上更新数据时不被卡。

**架构纪律**：
1. **锁状态 = 元数据**：DB 列 `walls.published_at`（保留原列名）非 null 即锁定，时间戳 = lock 时间。`walls.owner_uuid` 为作者权限依据。
2. **后端编辑 op 不读 lock**：element.* / canvas.* / layer.* 所有编辑 op 透明，不因 lock 拒绝。
3. **WS op**：`wall.lock` / `wall.unlock`，**owner-only**（caller UUID == wall.owner_uuid，非 owner 拒 `FORBIDDEN`）。
4. **前端是 lock 的唯一执行者**：locked 时 RightPanel 编辑控件 disabled、Konva Transformer 隐藏、拖动失效、删除快捷键失效、drawTool 失效。
5. **isOwner 判定**：ready payload 携带 `ownerUuid` + `selfUuid`，前端 computed `isOwner = selfUuid === ownerUuid`。非 owner 看不到解锁按钮，无路径绕过。
6. **`/canvas publish` / `/canvas unpublish` 命令砍**：玩家通过浏览器编辑器 TopBar 的 Lock 按钮触发；MC 命令族不再包含 lock 相关。
7. **ItemFrame PDC 不再写 published_at**：FrameDeployer.markPublished 砍；M7 的"已发布破坏拦截"砍，所有 wall ItemFrame 一致由 `canvas.modify` 权限保护。

**契约文档已更新**：`docs/architecture.md` §3 状态机 / §6 commit pseudocode / §7 PDC 标记 / `docs/data-model.md` §2 schema / `docs/protocol.md` §8.3 / `docs/security.md` 权限名 / PROPOSAL.md 命令族。具体 commit hash 见 `docs/journal.md` 2026-04-27 / 2026-05-14 条目。

**写代码前重新对照契约**——重构涉及多模块协调，必须文档先行。

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

## M15 ultrareview 大重构（2026-05-16）

第三方 AI 全栈 ultrareview 列 38 P0；5 agent 并行核验 ~37 条属实。
M15 分 5 phase commit batch 修完：

- **M15.1** P0 9 处低风险散点 + 3 测试基建依赖（Caffeine / MockBukkit / JavalinTest）
- **M15.2** 5 god class 拆分（EditSession / CanvasView / RightPanel / WebServer / CanvasCompositor）→ 33 个新模块平均 60-300 行
- **M15.3** 鉴权方案 C（仅 open 路径鉴权 lock，后端编辑 op 透明放行；兼容未来动态画板 PAPI / 数据源 P-1/P-3 路径，见 `docs/architecture.md §13`）+ 8 P0 数据安全 / 基础设施
- **M15.4** ImageIO 防御 + DB 事务 + 协议精简 + dither 优化 10 P0
- **M15.5** docs 同步

**关键架构决策（已固化）**：

1. CLAUDE.md `§lock-state` 第 2 条「后端编辑 op 不读 lock」保留（方案 C 只动 open 路径）
2. Pre-release（0.1.x SNAPSHOT）激进改 schema OK；0.1.0 发版后 forward-only + 强制 auto-backup（详见 `docs/data-model.md §6.6`）
3. 动态画板必须走 P-1（渲染期占位符）或 P-3（Plugin API + Provider）；反模式 P-2（定时 patch ProjectState）禁用（详见 `docs/architecture.md §13`）

累计 27 P0 修完 + 5 god class 拆完 + 3 commit batch（5 个 phase）。
