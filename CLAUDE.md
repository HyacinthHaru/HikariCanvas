# HikariCanvas

[![CI](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml/badge.svg)](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml)

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
- **双端渲染一致性**：浏览器 Canvas 与 Java Graphics2D 用同一 TTF 文件、禁抗锯齿；TextLayout 两端走 **`charAdvance(fontId, ch, fontSize)`**（M20 起）—— 构建期 `generateGlyphMetrics` 用 AWT 算每个内置字体 BMP 范围 advance → 紧凑 JSON 双端共享（jar `/fonts/{id}.metrics.json` + `web/public/fonts/{id}.metrics.json`）；运行时 `advance = round(baseAdv × fontSize / baseSize)`。用户字体（`plugins/HikariCanvas/fonts/*`）启动期 `FontMetricsTable.registerRuntime` 现场用同款 AWT 算法计算 + 内存表 + `GET /api/font/metrics?id=...` 给前端。缺字 / 表未到位 fallback 旧 `canonicalCharWidth`（ASCII=0.5×fontSize, CJK=fontSize）。M5-D2 canonical 已被替换为 fallback，仅在首次渲染窗口或缺字时生效
- **帧率策略**：静止 0fps · 输入防抖 100ms + 5fps 上限 · 提交全量
- **网络默认绑 `127.0.0.1`**；公网部署必须 nginx/Caddy 反代 + TLS
- **字体**：只打包 SIL OFL 1.1 协议字体；M22 起内置 **20 枚字体矩阵**：
  - 中文正文：`source_han_sans`（黑体）/ `source_han_serif`（宋体）/ `ark_pixel`（12px 像素）
  - 中文艺术：`smiley_sans`（得意黑）/ `ma_shan_zheng`（马善政毛笔楷书）/ `zcool_xiaowei`（站酷小薇）/ `zcool_kuaile`（站酷快乐体）/ `zcool_qingkehuangyou`（站酷庆科黄油体）/ `lxgw_wenkai`（霞鹜文楷手写）
  - 西文正文：`inter`（无衬线）/ `noto_serif`（衬线）/ `jetbrains_mono`（编程等宽）/ `fira_code`（编程含连字）
  - 西文装饰：`comic_neue`（Comic Sans 替代）/ `pacifico` / `lobster` / `bangers` / `shadows_into_light`（马克笔）/ `caveat`（手写笔记）/ `dancing_script`（飘逸草书）
  - 缺口：中文等宽（source_han_mono 仅 122MB OTC 合包），走 `plugins/HikariCanvas/fonts/`（M20 用户字体 metrics 自动生效）
  - Gradle `downloadFonts` 构建期抓到 `build/downloaded-fonts/`（SHA-256 pin）→ `processResources` 合并进 shadow jar 供后端 `FontRegistry` 使用 + M20 `generateGlyphMetrics` 自动按 bundledFonts iterate；仓库不入字体文件，`.gitignore` 排除
- **字体加载（M23 起单轨）**：所有字体（内置 + 用户）统一走 HTTP + FontFace API 动态加载。后端暴露 `GET /api/font/file?id=X` 返字体二进制 + `GET /api/font/list` 返 metadata 数组。前端 `FontLoader.ensureLoaded(fontId)` 用 `new FontFace + document.fonts.add` 注册；`onFontLoaded` 回调触发 `requestDraw`。删除 `style.css` @font-face + 删除 `PreviewRenderer.fontFamily` KNOWN 白名单（这是 M21/M22 加字体时漏修的 bug 根因）。`TextElementSection` 字体下拉动态从 `/api/font/list` 拉，按 source 分组（builtin / user）
- **构建期 palette**：Gradle `generatePalette`（独立 `generator` sourceSet）从 Paper `MapPalette` 导 248 色 JSON 到 classpath 根；后端 `PaletteLut` + 前端 `PaletteLut`（镜像）都读它，32³ Lab LUT，O(1) 匹配

## 里程碑

M0 立项 ✅ → M1 端到端验证 ✅（2026-04-20） → M2 会话与地图池 ✅（2026-04-21） → M3 实时投影 ✅（2026-04-21） → M4 渲染引擎 ✅（2026-04-22；竖排合并到 M5-C） → M5 编辑器 UI ✅（2026-04-23） → M5.5 wall 模型重构 ✅（2026-04-27） → M6 模板系统 ✅（2026-05-12） → M7 polish ✅（2026-05-13） → M8 图层 + 协议 v2 ✅（2026-05-13） → M9 PathElement + 工具栏 ✅（2026-05-13） → M10 调色板 ✅（2026-05-13） → M11 渐变 + Dither ✅（2026-05-13） → 2026-05-14 lock-state 重设计 ✅ → M12 笔刷 + 数位板 ✅（2026-05-14） → 2026-05-14 全栈审查 + 3 bug 修复 ✅ → M13 图片导入 + 蒙版 ✅（2026-05-15） → M14 模板创意工坊 ✅（2026-05-15） → M15 ultrareview 大重构 ✅（2026-05-16） → M16 第二轮 ultrareview 28 项 P0/P1 修复 ✅（2026-05-16） → M17 生产级体验组（F1-F5 复制粘贴 / 拖动跟手 / 智能对齐 / 自由拖动画布 / Canvas Fill） ✅（2026-05-17） → M18 Live Paint 油漆桶（B-medium+ 路线 / polygon-clipping / Web Worker / vector-fill 决策 A / vitest 引入） ✅（2026-05-17） → M19 GitHub Actions CI + Release（ci.yml push/PR 触发 / release.yml tag v* 触发 / Java 21 + Node 22 / shadowJar artifact 30d） ✅（2026-05-17）→ M20 双端字体 advance 精确化（构建期 generateGlyphMetrics + 运行时 FontMetricsTable / GlyphMetricsLut + 用户字体 registerRuntime + `/api/font/metrics` 端点 + 3 effects fixture baseline 重建） ✅（2026-05-17） → M21 内置字体扩充 7 字体矩阵（中文黑/宋/像素 + 西文 Inter/Noto Serif/JetBrains Mono/Fira Code；全 OFL 1.1） ✅（2026-05-17） → M22 字体艺术 / 装饰扩充至 20 字体矩阵（中文艺术 6 + 西文装饰 7） ✅（2026-05-18） → M23 字体加载通法（双轨变单轨：删 style.css @font-face + 删 PreviewRenderer.fontFamily KNOWN 白名单 / 新 FontLoader composable + FontFace API / 后端 GET /api/font/file + /api/font/list / TextElementSection 字体下拉动态化） ✅（2026-05-18） → M24 前端 UX 大整修（Catppuccin Latte/Frappé/Macchiato 三 flavor 替代 shadcn 中性灰 / Material 3 扁平化 + radius/elevation/spacing tokens / ThemeStore + ThemeSwitcher 8 accent + 5 radius preset / i18n 全文用户友好化 + 22 错误码翻译 + 40+ tooltip key / 修 FillInput tab 撞色） ✅（2026-05-18） → M25 ThemeSwitcher bug 修复 + i18n 挂载收尾 + 2 字体扩充（22 字体矩阵） ✅（2026-05-18） → **M26 内置图标库（FA Free 2060 icons / IconRegistry + 双源加载 / SVG path 双端渲染 + Fill 联合类型 / IconLibrary 侧边栏 panel + 拖入 + 收藏夹 + 最近使用 / PNG IconElement 完全兼容） + M26-B（修 EditSession.addElement 漏 case "icon" 紧急 bug + FontRegistry.registerRuntime 异步化 N×1-2s onEnable 阻塞 → 0；Material Symbols 留 M27） ✅（2026-05-18）**。总工期约 6 个月（M0-M26 累计约 8 周 wall-clock）。

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
cd web && npm run test              # M18 vitest（28 case，166ms）
```

## CI / Release（M19 引入）

GitHub Actions 2 workflow：

- **`.github/workflows/ci.yml`**：push/PR 到 main 触发。单 job 跑 frontend（npm ci + vitest 28 + vite build）+ backend（:plugin:test 364 + :plugin:shadowJar）+ 上传 jar artifact 30 天。首跑 ~5min，后续 ~2min（setup-gradle 缓存生效）
- **`.github/workflows/release.yml`**：tag `v*` 触发。`git tag v0.2.0 && git push origin v0.2.0` → 自动跑测试 + shadowJar + 创建 GitHub Release + 附 jar。含 `-`（如 v0.2.0-SNAPSHOT）自动标 prerelease
- **环境锁**：Java 21 Temurin + Node 22 LTS（不用 Node 25，CLAUDE.md 已知卡 vue-tsc）
- **cache**：`gradle/actions/setup-gradle@v4` 自带 Gradle dep/build cache；`setup-node@v4` 自带 npm cache（cache-dependency-path: `web/package-lock.json`）。不显式配 `actions/cache`，保持"原生"

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
2. Pre-release（`0.x.y-SNAPSHOT`，含当前 `0.2.0-SNAPSHOT`）激进改 schema OK；首次 stable（≥1.0.0）发版后 forward-only + 强制 auto-backup（详见 `docs/data-model.md §6.6`）
3. 动态画板必须走 P-1（渲染期占位符）或 P-3（Plugin API + Provider）；反模式 P-2（定时 patch ProjectState）禁用（详见 `docs/architecture.md §13`）

累计 27 P0 修完 + 5 god class 拆完 + 3 commit batch（5 个 phase）。

## M16 第二轮 ultrareview（2026-05-16）

M15 落地当晚跑第二轮全栈 ultrareview，又扫出 28 项 P0/P1。分 6 phase commit batch 修完：

- **M16.1 安全 P0 7 项**（commit 4695269）/api/upload/{hash} GET 加 sessionId query 鉴权；WS 未认证 5s 超时 close 4001 + WS upgrade Origin 白名单；YAMLFactory `maxAliasesForCollections=50` + `codePointLimit=5MB`；TemplateInstantiator deepCopy 递归 ≤32 + Interpolator 单值 16KB / 总输出 1MB；TemplateEntry.ownerUuid + `canvas.template.use-others` 跨用户隔离；wall.alias owner-only + `canvas.alias.any` bypass + WALL_ALIAS audit
- **M16.2 数据完整性 P0 7 项**（commit 8564275）配额 + 磁盘/DB 走 `jdbi.inTransaction(SERIALIZABLE)` + `BEGIN IMMEDIATE` 写锁；`ImageStorage.writeFileAtomic` 用 `.tmp` + `Files.move(ATOMIC_MOVE)`；**MapPool 按 world UUID 分桶**（`acquireForWall(World, ...)` / `bindToWall` 强校验 mapView.world 一致；跨世界绑定抛 IllegalStateException）；**WallRestorer 失败 `releaseToFree`** 防 idcounts.dat 膨胀（项目核心风险修复）；新 `MapPool.releaseToFree` + `POOL_RELEASE_TO_FREE` audit；`pendingDeletes` 改 `Map<UUID, Map<wallId, PendingDelete>>` 多 wall 支持；`SessionManager.confirm` rollback stack + assertMainThread 8 处；config `map-pool.per-world: {}`
- **M16.3 渲染防御 P0 4 项**（commit 9747975）Rect/Circle/Shape/ImageRenderer 入口 w/h<=0 return；`ElementValidator.finiteOr`（double/float）+ CanvasCompositor opacity finite clamp；FillPaintBuilder `filterFiniteStops` + <2 个降级纯色；ImageRenderer mask Area 包 try-catch + bbox 10× sanity 降级；前端 `sanitizeDimension` / `sanitizeRadius`
- **M16.4 前端资源 P0 3 项**（commit 3c800f8）useBrushHost tryCapture/tryRelease + `pointercancel/blur/visibilitychange`；CanvasView onBeforeUnmount `stage.destroy` + cancelAnimationFrame；`stores/project.reset` + `ui.reset`（切 wall 触发，同 wall 重连不动）；wsClient.onClose pendingAcks reject all + clearTimeout
- **M16.5 构建依赖 P0 3 项**（commit d9ab3fc）shadowJar 7 条 relocate（jackson/caffeine/jdbi/hikari/javalin/jetty/snakeyaml）→ `moe.hikari.canvas.shaded.*`；mergeServiceFiles 处理 SPI；**org.sqlite 不 relocate（JNI 保护）**；HikariCP `setLeakDetectionThreshold(30_000)`；npm ci 替 npm install
- **M16.6 P1 防御 + 观测 8 项**（commit ca4bc54）Jackson FAIL_ON_UNKNOWN_PROPERTIES 接收侧严格 + 错误消息脱敏；新 `web/Protocol.java`：SUPPORTED_MIN/MAX/`CLOSE_PROTOCOL_VERSION_UNSUPPORTED=4002`；auth payload 新 `client_v`；ready payload 新 `accepted_v`；双向校验；V010 DROP COLUMN refcount（pre-release 激进改 schema OK）；AuditLog 5 新事件 `WALL_LOCK / WALL_UNLOCK / IMAGE_UPLOAD_OK / IMAGE_UPLOAD_REJECTED / PERMISSION_DENIED` + write 失败 SEVERE stack trace；`HikariCanvasConfig.sanitizeEditorUrl` URI 解析 + http/https 白名单；**会话级 IP 绑定**（Session.boundIp + bindOrCheckIp，**不绑 token 绑 session**）；SessionManager 三 map → ConcurrentHashMap + ReentrantLock；TopBar.toggleLock/commitAliasEdit optimistic 回滚 + 连击防护

**关键架构决策（M16 已固化）**：

1. **草稿 wall 协作语义**：未锁定 wall（lockedAt=null）默认任何 `canvas.edit` 玩家可 `/canvas open`——这是协作中间态语义（多人接力 / 同步编辑）。只 owner 可触发 lock；lock 后非 owner 拒 open（除 `canvas.admin.bypass-lock`）。未来 ACL（owner-only 草稿）走 v1.x 协作 scope，详见 `docs/architecture.md §13`
2. **多世界分桶**：MapPool 按 world UUID 分桶，wall 与 map 必须 world 一致（强校验）；config `map-pool.per-world: {}` 配每世界 size
3. **会话级 IP 绑定**：首次 auth 时 CAS 绑定 caller IP 到 Session.boundIp；后续帧不一致拒 4001。**绑 session 不绑 token**——token 已单次使用 + 短 TTL，再绑 token 是冗余且阻塞合法重连
4. **shadowJar relocate**：所有第三方依赖（除 org.sqlite JNI）relocate 到 `moe.hikari.canvas.shaded.*` 防服内插件 classpath 冲突
5. **HikariCP maxPoolSize=4 保持**：SQLite 单写但允许并发读；4 池让 read-heavy 路径（preview / quota check）不阻塞主线程；写靠 SQLite `busy_timeout=5000` + `leakDetectionThreshold=30s` 兜底；缩到 1 会让任何长查询阻塞所有后续连接获取

累计 M15+M16 = 55 项 P0/P1 修完。**Token 暴力枚举防御（SessionRateLimiter）未实装**——M16 范围外，留 v1.x；详见 `docs/security.md §2.4`。

## M17 生产级体验组（2026-05-17）

M16 安全 / 数据完整性收口后，把"体验质量从 demo 升到生产级"作为单独里程碑推一组 5 大 feature。4 phase commit batch 完成：

- **M17.1 F2 + F4**（commit `a049484`）F2 onDragMove 单选 path 实时跟手 bug 修复（双层渲染——顶层 Konva 透明 hit-test + 底层 Canvas 2D PreviewRenderer——单选时早 return 导致底层不重绘，删早 return 后视觉跟手）；F4 自由拖动画布 + 1024px 虚空白边 + 新 `'hand'` 工具（H 键 / Space 临时切 / cursor grab|grabbing）
- **M17.2+3 F1 + F5 + F3 v1**（commit `1ed92ca`）F1 复制粘贴：`useClipboard` composable + Ctrl+C/V 快捷键 + 剪贴板格式 `hikari-canvas-v1:{...}` magic header + 跨 wall 工作 + 锁定 wall 拒粘贴；F5 `ProjectState.Canvas.background` String → Fill 联合类型，Jackson 自动兼容旧 hex 字符串，CanvasCompositor 走 FillPaintBuilder，alpha<1 编辑器 UI CSS 棋盘格提示，新 `CanvasSettingsSection.vue`，WS op `canvas.background` payload 升级 `{fill}`（新）+ `{color}`（兼容）；F3 v1 `useSnapManager` composable（canvas / element / grid 候选轴 + `snapAxis` 两遍扫描 + bypass 钩子）+ ui store snap 偏好 + localStorage + CanvasView onDragMove 接 snap + shift 临时禁用
- **M17.4 F3 v2 完整版**（commit `2ac5558`）distribute 间距均分（`EqualGapX/Y` hints，仅两侧最近邻，与 axis snap 互斥）+ `SnapGuideOverlay.vue` 对齐线（红虚线 axis + 绿实线 gap + 距离标签）+ `SnapSettingsPopover.vue`（Magnet 按钮挂 TopBar）+ resize snap 走 Konva `boundBoxFunc` 按"动的边"应用 delta + rotation ≠ 0 跳过

**关键架构决策（M17 已固化）**：

1. **Canvas.background = Fill 联合类型**：`Solid / Linear / Radial` 三态统一，Jackson 自动兼容旧 hex 字符串（`FillDeserializer` 在 M11 已存在 string → SolidFill 路径，反序列化链路自动复用）。模板 raw_state fallback：渐变背景 v1 退 `"#FFFFFF"`，因 raw_state 模板格式仅识别 hex
2. **`'hand'` 工具是非绘制工具之一**（M17 引入）：`ui.ActiveTool` 三大非绘制工具 = `select / move / hand`，与 line / arrow / circle / star / brush 等绘制工具区分；Space 临时切由闭包 `spaceSavedTool` 保存原工具 + window blur 兜底防卡死
3. **`useSnapManager` = 前端公共能力**（drag + resize 共用）：单候选轴扫描算法（canvas 3 / element 6/个 / grid floor+ceil 倍数）+ `snapAxis` 两遍扫描；O(n) 线性，100 elements ≈ 1800 比较 / frame，spatial index 留 v1.x
4. **SnapHints 视觉反馈走 vue-konva 独立 layer**：`SnapGuideOverlay.vue` 挂载在 v-stage 内 marquee / drawPreview 同级的独立 v-layer，覆盖 element / transformer 上方；drag / transform end 立刻清 `activeSnapHints`，layer `v-if` 自然卸载
5. **resize snap 选 `boundBoxFunc` 而非 transformend**：前者每帧拖锚点 call 可给视觉反馈，后者是结束时一次性事件；比对 newBox vs oldBox 找"动的边"按边应用 snap delta，比"snap 整 bbox"更精准，任何锚点（角 / 边中点）都正确无视觉跳动

**评估**：5 feature 累计 ~1500 行净增、6 新文件、0 baseline 漂移；编辑器体验从 demo 质量升到生产级（拖动 60fps 实时跟手 / 智能对齐 + distribute / 自由 pan / 跨 wall 剪贴板 / 渐变背景）。**M18 (B-advanced Live Paint) 已可开工**。

## M18 Live Paint 油漆桶（2026-05-17）

M17 体验组收口后接做「油漆桶」工具：用户在编辑器画布上点击，自动识别该点所在的"空白闭合 gap"并创建对应 PathElement 填充，或点击元素内部直接修改其 fill（vector-fill 快捷）。5 phase commit batch 完成：

- **M18-P1 核心算法**（commit `050a549`）引入 `polygon-clipping@0.15.7`；新 `web/src/livepaint/` 5 文件（`types.ts` / `ElementToPolygon.ts` / `LivePaintCore.ts` / `PolygonToPath.ts` / `index.ts`）；算法管线 = element → polygon array → polygon-clipping union (占用) → canvas rect difference 占用 = gaps → point-in-polygon 找用户点击命中的 gap → polygon ring 转 SVG path d；element→polygon：rect 4 顶点 / circle 32 采样 / shape 正多边形 / star outer-inner 交替 / path M/L/Q/C/Z 自实装 de Casteljau / text/image/brush bbox 兜底；rotation 应用；自交 path fallback bbox
- **M18-P2 Web Worker 隔离**（commit `cc74b7e`）`livePaintWorker.ts` module worker + discriminated union message；`useLivePaint.ts` Vue composable：debounce 100ms + requestId race（最新请求胜出，弃旧 response）+ JSON 深 clone + `enabled` gate（仅 paint-bucket 工具激活时跑）+ `onScopeDispose` cleanup
- **M18-P3 UI 集成**（commit `0c39c0c`）`ui.ActiveTool` 加 `'paint-bucket'`；快捷键 G；新 `paintBucket` store（FillCompat + localStorage 持久化最近 fill）；新 `PaintBucketPanel.vue`（复用 FillInput）；新 `LivePaintHoverOverlay.vue`（v-path + `fillRule='evenodd'` 处理 hole + 蓝色半透明 hover hint）；CanvasView 集成：`useLivePaint(enabled = paint-bucket)` + `onPaintBucketClick`
- **M18-P4 边界 + vector-fill 决策 A**（commit `5a71c86`）`findElementAt` 倒序 z-order + `elementToPolygon` + `pointInPolygon` 精确命中（非 bbox）；element 命中 vector-fill：`rect/circle/shape/path` → `element.update {patch:{fill}}` + 乐观本地 mutate；`text/image/brush` → `livePaint.elementUnsupported(type)` 提示；新 `RdpSimplifier.ts` 迭代式（防爆栈）+ tolerance 阶梯 0.5→16 简化到 ≤ 240 顶点；退化几何 fallback `{gaps:[], degraded:true}`；极小 gap 过滤 `MIN_GAP_AREA=4 px²`；DEV-only perf log（tree-shake prod）
- **M18-P5 vitest 引入 + 单测**（commit `1c5794f`）引入 `vitest@4.1.6` + `@vitest/ui`（CLAUDE.md / M16 待办自承的"前端无 vitest"已补）；28 test cases 4 文件 / 166ms 全绿；node 环境（不引 jsdom）；`ElementToPolygon` 8 / `LivePaintCore` 8 / `PolygonToPath` 6 / `RdpSimplifier` 6
- **M18-P6 docs 同步**（本 commit）CLAUDE.md / rendering.md / architecture.md / protocol.md / journal.md

**关键架构决策（M18 已固化）**：

1. **Live Paint = 前端独占功能**：拓扑计算仅浏览器 Web Worker 跑；输出 PathElement.d 由后端常规 PathRenderer 渲染。**这是 `docs/rendering.md §1 / §8 双端镜像纪律的显式例外**——理由：(a) 输出（PathElement）已经在双端镜像协议内（M9 引入）；(b) 拓扑算法不参与最终像素输出，仅作为工具输入辅助；(c) Java AWT 无 planar subdivision 等价物，强行镜像会引入 ~2000 行 Java 几何代码且仍可能与 TS 实现行为差异
2. **B-medium+ 路线**：用 `polygon-clipping` 库做 boolean op，不自写 DCEL。理由：用例覆盖 95%（vs B-advanced 99% 差 4% 是元素内部洞 / 嵌套 path face 等用户极少触发场景），工时 22h vs 38h，浮点精度风险远低；B-advanced 升级路径留 v1.x
3. **vector-fill 决策 A**：点击元素内部 = `element.update patch fill`（不创建 PathElement，沿用 M11 Fill 联合类型）；非闭合空白 gap = `element.add type=path` + d 字符串（gap polygon ring 转 M/L/Z 路径）。两种行为统一在 `onPaintBucketClick`，不引入新协议 op
4. **顶点 RDP 简化**：`PathDValidator` 实际限制 ~240 顶点；超阈走 `RdpSimplifier` 迭代式（防递归爆栈）+ tolerance 阶梯 0.5→1→2→4→8→16，直到 ≤ 240
5. **退化几何 fallback**：polygon-clipping 在退化输入（如自交 / 共线 / 浮点累计误差）下抛 / 返空时，core 不假装可用，返 `{gaps:[], degraded:true}`；UI 检测 `degraded` 显示「无法识别此区域」提示而非创建错误 PathElement

**评估**：5 algorithm + 1 docs phase / 6 commit / ~2400 行净增（含 vitest 配置 + 28 单测）/ 11 新文件 / 0 baseline 漂移；vite bundle +33.75 kB worker chunk（gzip ~10 kB），index 543 kB 内；编辑器获得"图形软件标配"工具。**v1.x 升级**：B-advanced 自写 DCEL 覆盖剩余 4% 用例 / 多 subpath path 切分 / text glyph 真实形状（fontkit 路径化）/ brush 真实形状（stroke offset polygon）/ RDP tolerance UI 配置

