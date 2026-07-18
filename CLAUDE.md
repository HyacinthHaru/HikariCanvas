# HikariCanvas

[![CI](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml/badge.svg)](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml)

Minecraft Paper 1.21+ 插件 + 内嵌 Web 编辑器。通过 TTF 字体渲染 + 模板系统 + 实时投影，在游戏内生成文字招牌。

## 标识

| 项 | 值 |
|---|---|
| 包名 | `ac.haru.hikaricanvas` |
| 命令前缀 | `/canvas` |
| 权限前缀 | `canvas.` |
| PDC namespace | `hikaricanvas`（`NamespacedKey(plugin,…)` 取插件名小写） |
| 工程文件扩展名 | `.canvas` |
| 仓库 | https://github.com/HyacinthHaru/HikariCanvas（MIT） |

## 技术栈（锁定版本）

| 项 | 版本 |
|---|---|
| Java | **21** 编译目标（跑 1.21.x 用 Java 21、跑 26.x 用 Java 25；0.9.5 起一份 jar 通吃，见「0.9.5 多版本支持」） |
| Paper API | **1.21.11** 编译目标（`1.21.11-R0.1-SNAPSHOT`；可 `-PpaperApi=`/`-PjavaVer=` 切换，CI `compat-26` job 对 26.2 编译守卫） |
| Gradle | **9.4.1** |
| `paperweight-userdev` | **2.0.0-beta.21**（同一版本即支持 1.21.x 与 26.x dev bundle） |
| PacketEvents | **2.13.0**（多版本：同时支持 1.21.x + 26.1 + 26.2；0.9.5 从 2.11.2→2.12.2 支持 26.1，0.9.10 升 2.13.0 支持 26.2）。**0.9.11 起 `compileOnly` 不打包**——PacketEvents 是 GPL-3.0，打包会污染本项目 MIT；服主单独装 PacketEvents 插件（`paper-plugin.yml` 声明为必装 `required` 依赖，PE 插件自负 init/terminate） |
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
- `docs/dynamic-data.md` — 0.4.0 变量系统 + Push API + 四层数据源（2026-05-19 规划）
- `docs/timeline.md` — 0.6.0 时间轴编辑器设计总纲（2026-06-03 规划；配套 rendering.md §9 / protocol.md v3 / data-model.md §2.4.2 / architecture.md §5.5）

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

## 架构纪律（26.x 升级保障）

Paper 26.1 起移除插件的 Spigot 重映射，任何碰 NMS 的插件 26.x 必崩。为让未来升级只改版本号、不动代码：

1. **禁用 NMS。** 任何 `net.minecraft.*` / 服务端内部类一律禁止；只用公开 Bukkit API + PacketEvents
2. **PacketEvents 调用集中。** 所有 `sendPacket` 走 `plugin/deploy/MapPacketSender.java` 一个类，别的模块不直接碰 PacketEvents
3. **Mojang mappings 输出。** `paperweight-userdev` 默认行为，不开 reobf

见 PROPOSAL.md §5.2.6 完整说明。

## 其他技术决策

- **预览地图池**是技术核心：编辑期间**只刷像素、不新建 MapView**，避免 `idcounts.dat` 膨胀
- **双端渲染一致性**：浏览器 Canvas 与 Java Graphics2D 用同一 TTF 文件、禁抗锯齿；TextLayout 两端走 **`charAdvance(fontId, ch, fontSize)`**（M20 起）—— 构建期 `generateGlyphMetrics` 用 AWT 算每个内置字体 BMP 范围 advance → 紧凑 JSON 双端共享（jar `/fonts/{id}.metrics.json` + `web/public/fonts/{id}.metrics.json`）；运行时 `advance = round(baseAdv × fontSize / baseSize)`。用户字体（`plugins/HikariCanvas/fonts/*`）启动期 `FontMetricsTable.registerRuntime` 现场用同款 AWT 算法计算 + 内存表 + `GET /api/font/metrics?id=...` 给前端。缺字 / 表未到位 fallback 旧 `canonicalCharWidth`（ASCII=0.5×fontSize, CJK=fontSize）。M5-D2 canonical 已被替换为 fallback，仅在首次渲染窗口或缺字时生效
- **帧率策略**：静止 0fps · 输入防抖 100ms + 5fps 上限 · 提交全量。**这是 v1 静态招牌默认值，不是硬上限**；0.6.0 时间轴会参数化到 30fps，但遵守"不自动降级"哲学（服主主动配，系统不偷偷压）
- **性能哲学（"工具不是保姆"，2026-05-25 固化）**：默认服主有充足性能 + 知道自己在做什么。① 数据透明不替服主决策 ② 不自动降级（config 上限仅作安全上限，非自动调优）③ 不擦屁股（网络 / 带宽 / 压缩比 / 服主没开的配置一律不测不估）。详见 `PROPOSAL.md §2.1`（产品哲学）+ `§5.2.7`（Benchmark 4 原则）+ `docs/dynamic-data.md §13`
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

M0 立项 ✅ → M1 端到端验证 ✅（2026-04-20） → M2 会话与地图池 ✅（2026-04-21） → M3 实时投影 ✅（2026-04-21） → M4 渲染引擎 ✅（2026-04-22；竖排合并到 M5-C） → M5 编辑器 UI ✅（2026-04-23） → M5.5 wall 模型重构 ✅（2026-04-27） → M6 模板系统 ✅（2026-05-12） → M7 polish ✅（2026-05-13） → M8 图层 + 协议 v2 ✅（2026-05-13） → M9 PathElement + 工具栏 ✅（2026-05-13） → M10 调色板 ✅（2026-05-13） → M11 渐变 + Dither ✅（2026-05-13） → 2026-05-14 lock-state 重设计 ✅ → M12 笔刷 + 数位板 ✅（2026-05-14） → 2026-05-14 全栈审查 + 3 bug 修复 ✅ → M13 图片导入 + 蒙版 ✅（2026-05-15） → M14 模板创意工坊 ✅（2026-05-15） → M15 ultrareview 大重构 ✅（2026-05-16） → M16 第二轮 ultrareview 28 项 P0/P1 修复 ✅（2026-05-16） → M17 生产级体验组（F1-F5 复制粘贴 / 拖动跟手 / 智能对齐 / 自由拖动画布 / Canvas Fill） ✅（2026-05-17） → M18 Live Paint 油漆桶（B-medium+ 路线 / polygon-clipping / Web Worker / vector-fill 决策 A / vitest 引入） ✅（2026-05-17） → M19 GitHub Actions CI + Release（ci.yml push/PR 触发 / release.yml tag v* 触发 / Java 21 + Node 22 / shadowJar artifact 30d） ✅（2026-05-17）→ M20 双端字体 advance 精确化（构建期 generateGlyphMetrics + 运行时 FontMetricsTable / GlyphMetricsLut + 用户字体 registerRuntime + `/api/font/metrics` 端点 + 3 effects fixture baseline 重建） ✅（2026-05-17） → M21 内置字体扩充 7 字体矩阵（中文黑/宋/像素 + 西文 Inter/Noto Serif/JetBrains Mono/Fira Code；全 OFL 1.1） ✅（2026-05-17） → M22 字体艺术 / 装饰扩充至 20 字体矩阵（中文艺术 6 + 西文装饰 7） ✅（2026-05-18） → M23 字体加载通法（双轨变单轨：删 style.css @font-face + 删 PreviewRenderer.fontFamily KNOWN 白名单 / 新 FontLoader composable + FontFace API / 后端 GET /api/font/file + /api/font/list / TextElementSection 字体下拉动态化） ✅（2026-05-18） → M24 前端 UX 大整修（Catppuccin Latte/Frappé/Macchiato 三 flavor 替代 shadcn 中性灰 / Material 3 扁平化 + radius/elevation/spacing tokens / ThemeStore + ThemeSwitcher 8 accent + 5 radius preset / i18n 全文用户友好化 + 22 错误码翻译 + 40+ tooltip key / 修 FillInput tab 撞色） ✅（2026-05-18） → M25 ThemeSwitcher bug 修复 + i18n 挂载收尾 + 2 字体扩充（22 字体矩阵） ✅（2026-05-18） → **M26 内置图标库（FA Free 2060 icons / IconRegistry + 双源加载 / SVG path 双端渲染 + Fill 联合类型 / IconLibrary 侧边栏 panel + 拖入 + 收藏夹 + 最近使用 / PNG IconElement 完全兼容） + M26-B（修 EditSession.addElement 漏 case "icon" 紧急 bug + FontRegistry.registerRuntime 异步化 N×1-2s onEnable 阻塞 → 0；Material Symbols 留 M27） + M26-C（PathParser 扩展 H/V/A/S/T 完整 SVG 命令集——FA icon MC 内渲染"杂乱像素"根因，A 椭圆弧用 cubic bezier 近似 W3C §F.6 算法） ✅（2026-05-18） → **M27 Shift 等比锁（drag-to-create 时按 Shift 锁正圆 / 正方形 / 45° 线）+ 版本号 0.2.0→0.3.0-SNAPSHOT ✅（2026-05-19） → M28-P1 变量系统底座（VariableStore + V011 user_variables + variable.* WS 协议 5 op + Compositor `${var:X}` 替换 + VariableProvider daemon 框架 + canvas.var.* 7 权限节点 + 前端 TS types + Pinia VariableStore mirror；A/B/C/D/E 五子任务并行实施 / 5 commit / 480 backend test + 28 frontend test 全绿） ✅（2026-05-19） → **M28-P2 编辑器基础 UX**（ready payload 加 variables 字段 + VariableDto 防外泄 / VariablePanel 右侧 drawer + NewVariableDialog + VariableValueEditor + BindDialog + useLongPressIncrement 长按累加 / VariablePicker popover + interpolator.ts 前端镜像 + TextElementVariableHints live preview + 删除警告 banner / TextElementSection 集成（按钮 + `${` 双触发）/ wsClient ready hookup；F/G/H 三子任务并行 / 487 backend + 73 frontend test 全绿） ✅（2026-05-19） → **M28-P3 内置 Provider**（SystemVariableProvider 全局 8 + per-wall 4 / ScoreboardVariableProvider 混合模式动态注册 + dynamic lookup hook / PapiVariableBridge reflection 软依赖 + 编码层 / ManualScheduleProvider 全栈 V012 + 5 schedule.* WS op + Schedule Manager Modal + Train icon TopBar 按钮 / `/api/variable/list-all-namespaces` 端点 + Picker mergeMetadata 接入 / 双端 interpolator wall.* + schedule.* 注入；J 先建基础设施 + K/L/M 并行 + N 收尾 / 600 backend + 93 frontend test 全绿 / shadow jar 152 MB） ✅（2026-05-19） → **M28-P4 Plugin Push API**（公开 `ac.haru.hikaricanvas.api` 包：HikariCanvasAPI 5 方法 + NamespaceInfo + VariableUpdate + 独立 VarType + 2 exception / HikariCanvasAPIImpl 4 args + 三档异常隔离 / PluginNamespaceRegistry 原子 CAS + 5 保留 namespace + spoof 防御 / PluginNamespaceProvider 接 P3-M 端点 / PushRateLimiter 1s 固定窗口 token bucket per-plugin 100/s + 全局 1000/s + 10s circuit break / PluginCleanupListener PluginDisableEvent 立即 unregister + 30s 延迟 purge / ServicesManager 注册 + getAPI() 双入口 / DemoTrainPlugin 定时器范型 + DemoScorePlugin 事件命令范型 / docs/api.md 660 行接入教程；O 单跑 + P/Q/R/S 并行 + T 收尾 / 660 backend + 93 frontend test 全绿） ✅（2026-05-19） → **M28-P5 命令族 + 教程 + smoke**（`/canvas var` 7 子命令 list/get/set/delete/providers/reload/inspect + tab completion 4 分支 + ReloadHook 热替换 PushRateLimiter + VARIABLE_COMMAND_SET/DELETE audit / VariableSubCommand 纯逻辑 + Brigadier 分离 + WallSource 抽象注入 / docs/variables.md 合集教程 玩家+运维+测试 31 步 / EndToEndSmokeTest 6 case + VariableSubCommandTest 29 case / HikariCanvasAPIImpl.limiter volatile + setRateLimiter / PluginCleanupListener.handleDisable public 化让跨 package 测试可调；695+ backend + 93 frontend test 全绿） ✅（2026-05-19）**。**0.4.0 完工**。**0.4.1 chip 编辑器 ✅（2026-05-20 P1+P2 + P3+P4 共 25h 收尾）** → **0.4.2 变量别名 + Picker 3 列表格 ✅（2026-05-20，V014 + 3 op + per-wall alias 全 namespace 通用 / VariablePicker 改 `别名 \| 数值 \| 变量名` 三列表格 + inline ✏ 编辑 / chip 显示优先 alias / NewVariableDialog 可选 alias 字段两步提交 / 775 backend + 155 frontend test 全绿）**。总工期约 6 个月（M0-M28-P5 + 0.4.1 + 0.4.2 累计约 9 周 wall-clock）。**0.4.3 起的版本进度（一路到 0.8）见下方「0.4.x 路线图速览」表 + `docs/journal.md`（权威）。**

## 0.4.0 路线（P1-P5 ✅ 2026-05-19 — 0.4.0 完工；上线后 bugfix 单 commit 合 2026-05-19 ✅）

**目标**：把"静态招牌"升级到"动态信息屏"。**Push 模式 + 玩家自定义变量 + 四层数据源 + Plugin API**。

**详细设计文档**：`docs/dynamic-data.md`（**实施前必读**；所有数据模型 / 协议 / API 决策以此为准）。

**5 个 phase（每 phase 可演示）**：

- **P1（62h）** ✅ **2026-05-19 完成**。变量系统底座：VariableStore（global `Map<fullName, Variable>` + 倒排索引 `Map<wallId, Set<fullName>>` + ConcurrentHashMap 线程安全 + 8 错误码 + 配额校验）+ V011 user_variables 表 + 5 个 `variable.*` WS op（create/update/set/delete/bind）+ state.patch `/variables/<encodedFullName>` 路径 + AuditLog 5 事件 + Compositor 渲染期 `${var:X}` 替换（regex 单次编译 + fallback 4 档 + `user/X` 自动注入 wallId → `user:<wallId>/X` 内部形式 + 倒排索引 markWallReferences）+ VariableProvider daemon 框架（ScheduledExecutorService 2 thread + register/unregister/shutdown + 三层异常隔离，P3 注册 Provider）+ canvas.var.* 7 权限节点 + 前端 TS types + Pinia VariableStore mirror（state.patch 接收侧按 `/variables/` 前缀分拣）。**5 commit**（A `11b2773` / E `74b4f4f` / C `ab765dd` / B `dcffe9f` / D `02be5ca`）/ **480 backend + 28 frontend test 全绿** / shadow jar 161 MB / 0 baseline 漂移
- **P2（30h）** ✅ **2026-05-19 完成**。编辑器基础 UX：ready payload 加 `variables: VariableDto[]` 字段（防 referencedByWalls 外泄）+ VariablePanel 右侧 fixed drawer（380px / 4 分组 / 搜索框 / 折叠组）+ NewVariableDialog（name 实时校验 + 4 type button group + 按 type 切换 defaultValue 控件）+ VariableValueEditor inline 改值 + BindDialog 占位（P4 启用）+ useLongPressIncrement composable（单击 +1 / 300ms 后 50ms 重复累加 / pointercancel + blur + onBeforeUnmount 清理）+ TopBar 触发按钮 + ui store `variablePanelOpen` + VariablePicker popover（按钮 + textarea 输入 `${` 双触发 / 键盘 ↑↓ Enter Esc / 4 分组 + 搜索）+ interpolator.ts 前端镜像（regex 一致 + fallback 4 档 + wallId 注入 + missingFullNames 提取）+ TextElementVariableHints（200ms debounce live preview + 删除红色 banner）+ TextElementSection 集成（按钮 + setRangeText 光标插入 + `${` 自动弹 picker + dispatchEvent 同步 v-model）+ wsClient.handleReady 接 variables 字段调 initVariables。**4 commit**（F `46eb6e9` / H `41c2ba3` / G `c7dd01f` / I `<本 commit>`）/ **487 backend + 73 frontend test 全绿** / bundle 620 kB（gzip 190 kB）
- **P3（20h）** ✅ **2026-05-19 完成**。内置 Provider（4 Provider + HTTP metadata 端点）：
  - **基础设施**：VariableProvider 接口加 `declaredKeys()` / `isDynamic()` + 新 record `DeclaredKey`；VariableStore 加 `registerDynamicLookupHook` + `notifyDynamicLookup`；双端 interpolator 加 `wall.*` 注入（→ `system:<wallId>/wall.X`）+ `schedule.*` 注入（→ `schedule:<wallId>/X`）+ `scoreboard.*` alias
  - **SystemVariableProvider**：全局 8（server.time/online/motd/tps 等）+ per-wall 4（wall.id/alias/owner/owner_uuid），独立 TTL 1s~1h，DataSource 接口注入抽象 Bukkit/WallRepo
  - **ScoreboardVariableProvider**：动态 namespace，混合模式 — interpolator miss → notifyDynamicLookup → handleDynamic 自动注册 + 10s refresh 切主线程读 Bukkit scoreboard
  - **PapiVariableBridge**：reflection 软依赖（未装 PAPI 时 `refreshInterval=ZERO` daemon 不调度，零开销）+ 编码层 `%xxx%` ↔ `pct_xxx_pct` 绕开 VariableStore key 正则限制 + PapiAccessor 接口注入测试友好
  - **ManualScheduleProvider 全栈**：V012 migration（wall_schedules + schedule_entries 双表 FK CASCADE）+ ScheduleDao CRUD + per-wall namespace `schedule:<wallId>` 4 key（next_departure/destination/eta_minutes/is_arriving，30s refresh，过零点 ETA + 5min is_arriving 阈值）+ ScheduleOpDispatcher 5 WS op + canvas.schedule.{own,any} 权限节点 + 前端 ScheduleManagerModal + EntryDialog + Pinia ScheduleStore + TopBar Train 按钮 + i18n 30+ key
  - **`/api/variable/list-all-namespaces`**：VariableMetadataHandler 聚合 daemon.registeredProviders.declaredKeys + 5s server-side cache + sessionId 鉴权；前端 Picker mergeMetadata 把 declared keys 与 store cached value 合并显示
  - **5 commit**（J `8828b2b` / K `219f731` / M `c975996` / L `b0b2e52` / N `<本 commit>`）/ **600 backend + 93 frontend test 全绿** / shadow jar 152 MB / 0 baseline 漂移
- **P4（28h）** ✅ **2026-05-19 完成**。Plugin Push API + 示例插件：
  - **公开 API 包 `ac.haru.hikaricanvas.api`**：HikariCanvasAPI 5 方法（registerNamespace / declareKey / setVariable / setVariables / unsetVariable）+ NamespaceInfo + VariableUpdate + 独立 VarType + NamespaceConflictException + PluginNamespaceException（NAMESPACE_NOT_REGISTERED / NAMESPACE_ACL_DENIED 2 码）；shadow jar 保持原路径 `ac/haru/hikaricanvas/api/*` 不 relocate
  - **HikariCanvasAPIImpl + 异常隔离**：4 args 构造（registry + store + daemon + limiter），setVariable/setVariables 三档错误隔离（ACL 抛异常 / 限流静默 drop / VariableException log + drop）；checkAcl + 先 setValue 试探 → NOT_FOUND fallback create + setValue 避免半态变量
  - **PluginNamespaceRegistry**：原子 putIfAbsent CAS 注册 + 5 保留 namespace（user / system / papi / scoreboard / schedule）+ 同 plugin 重复注册幂等 + `unregisterAllByPlugin` 返 namespace 列表
  - **PluginNamespaceProvider**：每个 plugin namespace 对应一个 VariableProvider 实例（refreshInterval=ZERO 不调度，declareKey 加入 declared map 接 P3-M `/api/variable/list-all-namespaces` 端点）
  - **PushRateLimiter**：1s 固定窗口 token bucket（per-plugin 100/s drop tail + 全局 1000/s 触发 10s circuit break），clock 注入 seam 单测确定性；setVariables 走 tryAcquireBatch all-or-nothing；config.yml `dynamic.push-rate-limit` 段
  - **PluginCleanupListener**：PluginDisableEvent MONITOR 优先级，立即 unregister namespace + provider，**30s 延迟 purge** store 数据（保留 cached 让 wall reload 不闪屏）；自我跳过 host plugin；DelayedScheduler 接口注入测试友好
  - **入口双轨**：`Bukkit.getServicesManager().load(HikariCanvasAPI.class)`（推荐，零编译耦合）+ `((HikariCanvas) plugin).getAPI()`（dynamic-data.md §4.2 示例方式）；HikariCanvas onEnable 自动 register service
  - **示例插件**（Gradle subproject）：`examples/demo-train-plugin`（定时器范型 — runTaskTimer 5s push 6 个 train 变量）+ `examples/demo-score-plugin`（事件 + 命令范型 — PlayerJoinEvent setMvp / `/demoscore add red 1`）；compileOnly project(":plugin") classes 输出 workaround paperweight 主 jar 禁用
  - **docs/api.md**：660 行完整接入教程（快速开始 + API 参考 + ACL + 生命周期 + 限流 + 错误表 + FAQ + 升级承诺）
  - **6 commit**（O `10eeda1` / S `24c16f3` / P `3d8d214` / R `e5158ce` / Q `b227b5c` / T `a69c0f1`）/ **660 backend + 93 frontend test 全绿** / shadow jar 152 MB / 0 baseline 漂移
- **P5（10h）** ✅ **2026-05-19 完成**。`/canvas var` 命令族 + 教程 docs + 端到端 smoke + 0.4.0 完整收尾：
  - **`/canvas var` 7 子命令**：`list [namespace] / get <fullName> / set <fullName> <value...> / delete <fullName> / providers / reload / inspect <wallId>`；统一权限 `canvas.var.command`（默认 op）；tab completion 4 分支（子命令 / namespace / fullName / wallId）；reload 通过 ReloadHook 注入热替换 `PushRateLimiter` 引用（HikariCanvasAPIImpl.limiter 改 volatile + `setRateLimiter` 方法）；set / delete 写 `VARIABLE_COMMAND_SET` / `VARIABLE_COMMAND_DELETE` audit 事件
  - **VariableSubCommand 架构**：纯逻辑（`execute(sender, args)`）+ Brigadier 注册（`build()`）分离，单测无 Brigadier infra；WallSource 接口抽出 WallRepo 依赖（生产 fromWallRepo / 测试 FakeWallSource）；ReloadHook 抽 PushRateLimiter config 重建逻辑出 HikariCanvas 主类
  - **docs/variables.md**：合集教程 ~430 行 / 3 大段（玩家入门 11 节 + 运维管理 6 节 + 测试 checklist 31 步）；列出 13 个系统变量 + 4 个 schedule 变量 + 4 档 fallback 链 + PAPI 编码层 + scoreboard 动态注册流程；指向姊妹篇 `docs/api.md`
  - **端到端 smoke test**：`EndToEndSmokeTest` 6 case（user CRUD / plugin namespace push / plugin disable cleanup + 30s grace / rate limit drop tail / ACL spoof denied / 4 档 fallback chain）—— 不引 Bukkit 主线程，全 fake 装配链测；防回归"装配链"——某个 phase 改了 API 形态后所有调用方依然 compile + run 通畅
  - **VariableSubCommandTest 29 case**：权限拒绝 + list / get / set / delete / providers / reload / inspect 各分支 + tab completion 4 + 未知 sub + reload 异常路径
  - **0.4.0 总览**：5 phases / **22 commit（M28-P1: 5 + P2: 4 + P3: 5 + P4: 6 + P5: 1 + 中间收尾 1）** / **695+ backend + 93 frontend test 全绿** / shadow jar 152 MB / 0 baseline 漂移 / wall-clock ~3 天（5-19 单日 P1-P5 一次性推完）
  - **1 commit**（`<本 commit>`）
- **0.4.0 上线 bugfix（4 项）** ✅ **2026-05-19 完成（1 commit 合）**。用户实测 4 项体验 bug 一次性修完：
  - **Bug 1**：ready payload 漏 system / schedule / scoreboard / papi 变量（误报"已删除"）→ 新增 `VariableStore.listVisibleToWall(wallId)`，不依赖 byWall 倒排索引，按 namespace 形态判定可见性；`WebServer.handleAuth` 改用此方法
  - **Bug 2**：`store.create` 时 `referencedByWalls = empty`（先 markWallReferences 后 create 场景下值变化不重画）→ create 内反查 `byWall` 注入 initialRefs，O(W) 性能可忽略
  - **Bug 3**：`is_arriving` 阈值改 config（默 60s，原 5min 已废）+ 新增 `arrival_status` STRING 变量（进站中文案 / 空闲文案，config 可改），`HikariCanvasConfig.ScheduleConfig` record 注入 Provider
  - **Bug 4**：per-wall 秒精度系统。V013 migration `ALTER TABLE wall_schedules ADD COLUMN precision`（默 'minute'，现有 wall 平滑升级）；`WallSchedule` record 加 precision；`HHMM_PATTERN` 扩展 `(:[0-5][0-9])?`；`ManualScheduleProvider` refresh 改 1s 粒度 + per-wall `lastPushAt` 节流（minute=30s / second=1s）；新增 `eta_seconds` / `arrival_status` / `precision` 共 3 个变量（旧 4 个保留向下兼容）；前端 ScheduleManagerModal 加 "时间精度" toggle + 7 变量 preview；ScheduleEntryDialog 加 precision prop（second 时 step="1"）
  - **714 backend + 93 frontend test 全绿 / shadow jar 152 MB / 0 baseline 漂移**

**0.4.0 总 ~150h ≈ 6-7 周 wall-clock**。

**6 个固化决策**（避技术债）：
1. Push > Pull（性能 / 解耦 / 扩展性）
2. 变量是 string（业务在插件侧）
3. 用户变量持久化（DB）；插件 / 系统 / PAPI 变量内存态
4. resolve 不在主线程（ProjectionThrottler 用 cache）
5. namespace 严格隔离（防 plugin spoof）
6. fallback 链：cached → `${var:X\|fallback=...}` → `Variable.default` → `"???"`

## 0.4.1 路线（chip 编辑器；P1-P2 ✅ 2026-05-20；P3-P4 ✅ 2026-05-20）

**目标**：把 0.4.0 的 TextElement textarea（占位符显示字面字符串，长且乱）升级为 **Notion 风格 chip 编辑器**——`${var:X}` 渲染为 Catppuccin Mauve 胶囊 pill，hover 看当前值 / 来源，click 改绑定，错误态红色 + 一键补创。

**4 个 phase（M28-0.4.1-P1 至 P4 共 ~25h）**：

- **P1+P2（~16h）** ✅ **2026-05-20 完成**（commit `add7682`）。VariableChipEditor 原型 + 交互细化：lexical core 直接接 Vue（弃 lexical-vue，vue-tsc + DecoratorNode 控制权问题）/ VariablePlaceholderNode `DecoratorNode<null>` + `getTextContent()` 返字面占位符 → roundtrip free / `textToLexicalNodes` + `lexicalRootToText` 序列化双向 / `$insertVariableChipAtSelection` + `${` 检测 / hover Teleport tooltip / 25 vitest roundtrip case 字符级精确 / TextElementSection + TextInlineEditor 集成
- **P3（~5h）** ✅ **2026-05-20 完成**。视觉打磨 + 兼容性：
  - **P3.1 Catppuccin Mauve 直引**：`color-mix(in srgb, var(--ctp-mauve) 18%, transparent)` 替代 oklab + transparent 不污染父背景；dark 主题（frappe / macchiato）提高填充比补偿色亮度衰减
  - **P3.2 字号 clamp 钳位**：`font-size: clamp(10px, 0.85em, 16px)` + `--chip-scale` CSS 变量（由 props.fontSize 计算 0.6..1.2）联动 padding / border-radius
  - **P3.3 multi-line 整体换行**：chip `display: inline-block` + `white-space: nowrap` → 浏览器把 chip 当不可拆单位参与父段落 wrap，超宽换行不切 chip 内部
  - **P3.4 错误态 chip 一键补创**：红 chip click 不弹 picker（变量缺失选啥），改 emit `createVariableRequest` → 外层弹 native confirm「是否立即创建？」→ user/X 走 `variable.create`；非 user 域提示「请通过 Provider 注册」
  - **P3.5 内联编辑器 picker 接入**：CanvasView 自持一个 VariablePicker overlay 实例 + 监听 TextInlineEditor 的 `insert-variable-request` / `edit-variable-request` / `create-variable-request`，画布双击编辑路径无需回 RightPanel 也能完整 chip 工作流
  - **P3.6 paste transform**：`registerVariablePasteTransform(editor)` 注册 lexical `registerNodeTransform(TextNode)` → 检测 `${var:...}` 完整模式 → splitText + replace 升级为 VariablePlaceholderNode；外部 plain text 粘贴 / 手打字面字符串自动升级 chip
  - **P3.7 vite manualChunks 拆 lexical chunk**：function 形态 `manualChunks(id)` 把 `node_modules/lexical/` + `@lexical/*` 全部独立成 `lexical-bZWspQby.js`（154.92 kB / gzip 49.83 kB）→ main bundle 从 808 kB 降到 **655.34 kB（gzip 199.87 kB）**，回到 < 700 kB 目标
  - **P3.8 i18n**：中英 `variables.chipError.{notFound, createConfirm, onlyUserCanCreate}` 3 key
- **P4（~4h）** ✅ **2026-05-20 完成**。收尾：
  - **vitest 单测扩展**：lexicalChip.test.ts 加 P3.6 paste transform 7 case（单 chip / 含 fallback / 混合 plain text + chip / 连续多 chip / 纯文本无副作用 / 升级真实 VariablePlaceholderNode / 残缺模式不触发）→ **137 全绿（130 + 7）**
  - **docs/variables.md §1.5 + §1.5.1 + §1.11**：§1.5 加 0.4.1 chip 编辑器说明 + §1.5.1 新增完整 chip 交互表格（hover / 改绑定 / 补创 / 整体删除 / 粘贴升级 / 复制 / 字号联动）+ §1.11 三层删除提示（chip 红色 + banner + tooltip）
  - **版本号升 0.4.1-SNAPSHOT**：4 处（`build.gradle.kts` allprojects / `plugin/src/main/resources/paper-plugin.yml` + 2 demo example plugin / `web/package.json` + `package-lock.json` / shadow jar 文件名自动跟随 `HikariCanvas-0.4.1-SNAPSHOT.jar` 153 MB）
  - **shadow jar 验证**：`./gradlew :plugin:shadowJar` 出 `HikariCanvas-0.4.1-SNAPSHOT.jar`（153 MB）/ vite build 出 lexical 拆 chunk / 137 vitest 全绿 / 0 baseline 漂移
  - **1 commit**（P3+P4 合并）

**0.4.1 总 ~25h ≈ 1 周 wall-clock**（实际 2026-05-20 单日推完）。

## 0.4.2 路线（变量别名 + Picker 3 列表格 ✅ 2026-05-20）

**目标**：让玩家给所有变量起短易记别名（per-wall），并把 VariablePicker 从单列改为 3 列表格化展示。

**4 个固化决策**：
1. **所有 namespace 都可加别名**（含 user / schedule / system / papi / scoreboard / plugin）——别名仅在 UI 层（picker / panel / chip）展示用，**不参与 `${var:...}` 解析**
2. **alias per-wall**：同一变量在不同 wall 可起不同别名（个性化）；同一 wall 内一个 fullName 只一个别名（主键 `(wall_id, full_name)`）
3. **Picker 表格列顺序**：`别名 | 数值 | 变量名`，行末 ✏ 按钮 inline 编辑
4. **chip 显示优先级**：alias > currentValue > fallback > defaultValue > UNRESOLVED——别名是稳定的用户命名，比动态数值更可读

**实施**：
- **V014 migration**：`variable_aliases (wall_id, full_name, alias, created_at, updated_at)` + FK CASCADE
- **后端**：`VariableAlias` record + `VariableAliasDao`（upsert/delete/loadByWall/loadAll）+ `VariableAliasDispatcher` 3 op（`variable.alias.set/clear/list`，复用 `canvas.var.write.own/any` 权限，list 只读放行）；state.patch 推 `/aliases/<encoded fullName>`（JSON Pointer 编码）；ready payload 加 `aliases: Map<fullName, alias>` 字段；`HikariCanvas.onEnable` 注册 wall delete hook；新 audit 事件 `VARIABLE_ALIAS_SET/CLEAR`
- **前端**：`useVariableAliasStore` Pinia mirror（initAliases/get/set/remove/clear/reset/has/size）+ `wsClient.handleReady` 接 + `handlePatch` 加 `/aliases/` 分拣 + wall 切换 reset + 2 个 ack send 方法；`pickerLogic.buildGroups` 加可选 aliases 第 4 参（keyword 也命中别名）；`VariablePicker.vue` 大改造 3 列表格 + 4 个 inline 编辑按钮（保存 / 清空 / 取消 / 编辑触发）；`NewVariableDialog.vue` 加可选 alias 字段 + 两步提交（create → alias.set，alias 失败不阻塞 create 成功）；`VariablePanel.vue` 行加紫色 alias chip + 编辑按钮（与 delete 确认互斥） + 搜索匹配别名；`VariableChipEditor.vue` chip 显示优先 alias + tooltip 加 Alias 行 + watch alias store 自动重渲
- **i18n**：22 keys 中英对照
- **版本号**：`0.4.1-SNAPSHOT → 0.4.2-SNAPSHOT`（5 处文件）

**测试结果**：后端 **775** test（新增 14 DAO + 5 ready payload = 19）+ 前端 **155** test（新增 13 store + 5 picker alias = 18）全绿；shadow jar 153 MB / vite build 666 kB（gzip 202 kB）。

**0.4.2 总 ~6h**（单 commit 单日推完）。

## 0.4.3 路线（全局用户变量 ✅ 2026-05-21 实施完成）

**目标达成**：补 0.4.0 P1 决策 3 的遗留 — user 变量 per-wall 不能跨画布共享。
新增 `userglobal/<key>` namespace 让玩家自定义"全服可见、跨 wall 共享"的变量。

**详细设计**：`docs/dynamic-data.md §17`。详细落地日志见 `docs/journal.md` 2026-05-21 条。

**实施总览**（5 phase / 1 commit / 单日完成）：
- **P1** V015 migration `user_global_variables` 表（PK = name 单字段全服唯一）+
  UserGlobalVariableDao（CRUD + countByOwner + countTotal）+
  `VariableStore.createGlobal/listGlobals/getGlobalOwner/loadGlobalsFromDb/configureUserGlobal`
  + 内存 `GlobalOwnerInfo` 表（让序列化路径能注入 owner）
- **P2** EditSession 5 个 `createGlobalVariable / updateGlobalVariable / setGlobalVariableValue
  / deleteGlobalVariable / bindGlobalVariable` 方法 + VariableOpDispatcher 按
  `scope='global'`（create）/ `fullName.startsWith("userglobal/")`（mutate）路由 +
  `pickGlobalPermissionNode` / `isCallerGlobalOwner` + RESERVED_NAMESPACES 加 `userglobal`
  + paper-plugin.yml 5 新权限（canvas.var.global.{create, write.own/any, delete.own/any}）+
  AuditLog `VARIABLE_GLOBAL_*` 前缀
- **P3** `SessionManager.broadcastVariableChangeToAll`（全 session 广播）+
  HikariCanvas listener 按 fullName 前缀分流（userglobal → broadcastToAll）+
  `VariableDto.from(v, store)` / `SessionManager.variableToMap` / `EditSession.variableToMap`
  注入 ownerUuid + ownerName + 前端 `Variable` 接口扩 ownerUuid/ownerName +
  `types/variable.ts` 加 USERGLOBAL_NAMESPACE / isUserGlobalNamespace /
  makeUserGlobalFullName + interpolator `${var:userglobal/X}` 天然 fallthrough
- **P4** `pickerLogic.buildGroups` 第 5 参 selfUuid → myGlobal / othersGlobal 分组 +
  6 组新顺序（mine → myGlobal → othersGlobal → plugin → system → papi）+
  `NewVariableDialog` scope toggle [本 wall \| 全局] + `wsClient.sendVariableCreate`
  4 参 scope + `VariablePanel` 新「全局变量」section（Globe 图标 / owner badge / 我创建 chip /
  非 owner 控件 disabled + "只读"标）+ i18n 中英 ~14 keys（dialogNewScope* / groupGlobal /
  emptyGlobal / ownerBadgePrefix / ownerMineBadge / actionReadonly / picker.groupMyGlobal /
  groupOthersGlobal）
- **P5** config.yml `dynamic.variables.userglobal-max-{per-owner,total}` 段（默 500 / 10000）+
  后端 15 新 case（10 VariableStore + 5 EditSession）+ 前端 6 新 case +
  docs/variables.md §1.13 + 版本号 0.4.2→0.4.3-SNAPSHOT（5 处文件）

**关键架构纪律（已固化）**：
1. **namespace = `userglobal`**（不带冒号 + wallId）— 与 user 同谱系但全局
2. **外部插件禁推**：`userglobal` 加入 `PluginNamespaceRegistry.RESERVED_NAMESPACES`；
   插件想全服共享应用自己 namespace（如 `bedwars/*`），不允许抢用
3. **owner-only + admin override** 5 权限节点（own 默 true / any 默 op）
4. **配额 per-owner 500 + 全服 10000**（config 可调；管理员意图优先 — 不再强制
   total ≥ per_owner）
5. **`.canvas` 工程文件不含 userglobal**（服务器级状态，跨服务器无意义；引用全局变量
   的 wall 导入后该占位符走 fallback "???"）
6. **state.patch 广播全 session**（HikariCanvas listener 按 fullName 前缀分流到
   broadcastToAll / broadcastToWall；前端 mirror 不需感知 namespace 形态）

**测试结果**：后端 **795**（原 714 + 新 81）/ 前端 **161**（原 155 + 新 6） 全绿；
shadow jar 153 MB / 0 baseline 漂移。**0.4.3 总 ~13h（单日推完）**。

## 0.4.4 路线（铁路网络 ✅ 2026-05-22 实施完成）

**目标达成**：完整铁路网络抽象 + 真实地铁运营语义（车次号 / 服务类型 / 编组 / 区间 / 备注 /
每站精确时刻表）。100 个地铁屏定义一次"1 号线 + 车次 A01"，N wall 都绑同一网络自动同步。

**详细设计**：`docs/dynamic-data.md §18`。详细落地日志见 `docs/journal.md` 2026-05-22 条。

**实施总览**（6 phase / 1 commit / 单日完成）：
- **P1** V016 5 表 + 6 record + ServiceType i18n + RailDao 统一 CRUD + AutoTimetableGenerator
  纯函数 helper
- **P2** RailScheduleProvider（共享 schedule namespace 接管 rail-bound wall + push 29 key 兼容 0.4.0
  + 新 14 车次语义）+ ManualSchedule.skipWallPredicate 避免双写
- **P3** RailOpDispatcher 12 op（11 spec + line.list） + 6 权限节点（canvas.rail.{line.create,
  line.edit.own/any, line.delete.own/any, wall.bind}）+ ACL 按 line owner 判定 + AuditLog RAIL_* 9 事件
- **P4** RailNetworkModal + RailRunDialog（车次详情 + 时刻表 inline + 自动生成对话框含跳站 checkbox）
  + Pinia rail store + wsClient 12 sendRail* + TopBar TrainTrack 按钮 + i18n ~50 keys
- **P5** 后端单测 24 case（9 AutoTimetable + 7 ServiceType + 8 RailScheduleProvider）+
  docs/variables.md §1.13 新节
- **P6** 版本号 5 处升级 + shadow jar 154 MB + journal + commit + push

**关键架构纪律（已固化）**：
1. **rail + manual 共享 `schedule:*` namespace + skip predicate 协调**：RailScheduleProvider 接管的
   wall 自动让 ManualSchedule 跳过 push — 避免双写同 key
2. **每站时刻 = `rail_timetable` 精确读**（不再走 travel_seconds 均匀推算）支持站间不均 +
   大站快车跳站 + 区间车不到全线
3. **service_type 4 内置 + 自定义字符串**：LOCAL/EXPRESS/SECTION/LIMITED 走 enum + i18n
   友好文本；其他字符串原样存 + 显示
4. **AutoTimetableGenerator 纯函数**：不依赖 DB / Bukkit / 主线程；单测 9 case 全覆盖
5. **`wall_rail_bindings.line_id IS NULL` 走 fallback**：兼容只用 ManualSchedule 的旧 server
6. **车次详情所有写操作 ACL 走 line owner**：rail.station.* / rail.run.* / rail.run.timetable.set
   按 line.ownerUuid 判 own/any（不为每张子表配独立 owner 字段）

**测试结果**：后端 **819** 测试全绿（原 795 + 新 24）/ 前端 **161** 测试全绿 / shadow jar
HikariCanvas-0.4.4-SNAPSHOT.jar 154 MB / 0 baseline 漂移。**0.4.4 总 ~60h 估，实际单日推完**。

**v0.4.5 优化项**：已在 2026-05-22 单日推完，见下方 0.4.5 段。

## 0.4.6 路线（体验打磨 ✅ 2026-05-22 实施完成）

**目标达成**：4 项用户提的体验打磨 — 字体加粗/斜体 + 透明背景 + 颜色对比度修复 + 文案优化。
先做 4 路深入审计，再 5 phase 实施。

**详细落地**：见 `docs/journal.md` 2026-05-22 0.4.6 段。

**关键审计发现**：
1. 字体 bold/italic 走 **stroke + shear transform**（双端等价；synthetic bold AWT vs Canvas
   像素不一致是 nogo）
2. 透明背景 80% 基础设施已埋好（M11 PaletteLut.TRANSPARENT_INDEX + matchColor 4 参）；
   只缺 TYPE_INT_RGB → ARGB
3. 颜色根因：`--primary-foreground: var(--ctp-base)` 在深色主题 ctp-base 是暗色，配亮
   primary 对比度仅 2:1（远 < WCAG AA 4.5:1）— **1 行 CSS 修全局**

**实施总览**（5 phase / 1 commit / 单日完成）：
- **P1** 颜色对比度：style.css 加深色主题 `--primary-foreground: var(--ctp-crust)` +
  `--destructive-foreground: var(--ctp-crust)` 覆盖；6 处 `text-white` → token
- **P2** 透明背景：CanvasCompositor TYPE_INT_RGB→ARGB + toPaletteSlice 提 alpha
  + CanvasSettingsSection 加"设为透明背景"快捷按钮
- **P3** bold/italic：TextElement 加 Boolean bold/italic 字段（nullable）；
  bold = stroke pass（color=text color, width=max(1.5, size*0.08)）；
  italic = AWT shear(-0.2, 0) / Canvas transform(1, 0, -0.2, 1, ...)（数学等价双端一致）；
  TextElementSection UI 加 B / I 切换按钮
- **P4** 文案：10+ 技术术语改友好（strokeWidth→描边粗细 / blendMode→混色模式 /
  innerRatio→内凹度 / dither→柔和过渡 / renderModeClean→清晰 / renderModeDither→柔和）
- **P5** 版本号 0.4.5 → 0.4.6-SNAPSHOT + shadow jar 155 MB + journal + push

**关键架构决策（已固化）**：
1. **主 buffer TYPE_INT_ARGB**：让 alpha 通道贯穿到 toPaletteSlice 的 4 参 matchColor；
   内存 +33% 可接受
2. **italic = shear transform，bold = stroke 包装**：双端走数学等价的线性变换 +
   stroke 描边路径——避免 synthetic bold 双端像素不一致
3. **bold 像素字体跳过描边**：NN 路径走 BufferedImage mask 不是 outline；像素字体
   本身已够清晰
4. **深色主题 foreground 走 ctp-crust**：1 行 CSS token 修全局对比度（影响约 20 处按钮）
5. **`<details>` 折叠保留不重构**：现有结构合理，重点改文案

**测试结果**：后端 **820**（baseline 无破坏；alpha=0xFF 像素下 4 参 matchColor 与 3 参等价）/
前端 vite build 720 kB / 213 kB gzip。shadow jar `HikariCanvas-0.4.6-SNAPSHOT.jar` 155 MB。
**0.4.6 总 ~15h 估，实际单日推完**。

## 0.4.5 路线（打磨期 ✅ 2026-05-22 实施完成）

**目标达成**：0.4.4 当日推完后实测发现 2 个 P0 可用性 bug + 数个 UX 粗糙点，
0.4.5 集中打磨 0.4.3 全局变量 + 0.4.4 铁路网络两块新功能。**没有新功能 / 新协议**，
全是体验提升 + bug 修。

**实施总览**（8 phase / 1 commit / 单日完成）：
- **P1** 修 0.4.4 P0：rail.line.detail op + RailNetworkModal selectLine 实际拉数据
  （之前选线路看不到已存在 stations / runs / timetable）
- **P2** 修 0.4.4 P0：替换 prompt/confirm 为 inline modal — 新车次内嵌对话框 +
  3 个 inline 删除 confirm popover（同 VariablePanel 风格）
- **P3** 收 0.4.4 spec §18.5：ScheduleManagerModal 加可折叠"铁路绑定"section
  （线路 / 本站 / 方向 3 列下拉）+ ready payload 加 railBinding 字段 + 绑定时 entries 灰显
- **P4** UX 打磨：HTML5 native drag-drop 拖动排序站点（替换 ↑↓ 按钮）+ 时刻表
  `type="time" step="1"` 原生 picker + isValidTime regex 校验红边
- **P5** UX 打磨：服务类型从 datalist 改 select（4 内置 + 「自定义」切换 input）+ i18n
  友好文本；syncDraftFromStore 自动检测非内置值进入 custom 模式
- **P6** 新功能：车次复制 — RailRunDialog 加 Copy 按钮 → 复制对话框（新 runNumber +
  direction 可改）→ create + timetable.set 两步复制
- **P7** RailNetworkModal 空状态 + 未选线路时显示 4 step 引导文案；加 0.4.3 bug 复查
  测试 case（createGlobal byWall 反查路径正常，无 Bug 2 模式风险）
- **P8** 版本号 0.4.4 → 0.4.5-SNAPSHOT（5 处文件）+ shadow jar 154 MB + journal + push

**关键架构纪律（已固化）**：
1. **rail.line.detail 走聚合查询**：单接口返 stations + runs + timetableByRun，timetable
   用 IN 子句批量拉，避免 N+1
2. **ready payload 携带 railBinding**：让 ScheduleManagerModal 一打开就知道状态，
   不另加查询 op；wall 切换时 rail store reset
3. **拖动排序批量更新**：落定后遍历重设 sortOrder = 0..N（仅 order 变化的项发请求）
4. **serviceType custom 模式自动切换**：syncDraftFromStore 检测非内置 enum 自动进 custom input
5. **车次复制 = create + timetable.set 两步**：不引入新协议 op
6. **删除走 inline confirm popover**：`confirmingDelete: { type, id }` 状态机统一管理 3 种删除

**测试结果**：后端 **820**（原 819 + 新 1 bug 复查 case）/ 前端 **161** 全绿；
shadow jar `HikariCanvas-0.4.5-SNAPSHOT.jar` 154 MB / 0 baseline 漂移。**0.4.5 总
8 phase ~20h，实际单日推完**。

## 0.4.4 旧路线（已实施，记录为档案）

**目标**：0.4.0 P3-L ManualScheduleProvider 是纯 per-wall，100 个地铁屏 = 100 套独立配置。
0.4.4 引入完整铁路网络抽象 + **真实地铁运营语义**：玩家定义线路 + 站点 + **车次（含
服务类型 / 编组 / 区间 / 备注）** + **每站详细时刻表**，wall 编辑器下拉**选线路 + 本站 +
方向**自动绑定该站时刻，**改一处全服同步**。wall 上展示真实地铁屏标准信息：
"A01 次 → 郑州东（6 节 大站快车）ETA 02:30"。

**详细设计**：`docs/dynamic-data.md §18`（**实施前必读**）。

**已锁定 3 决策**（2026-05-21 用户确认）：
1. **加车次概念**——含 run_number + service_type + cars + start/end_station + notes 全语义
2. **timetable 自动生成 + 逐站调整**——创建车次时弹对话框（首站时间 + 站间秒 + 跳站集合 → 生成 rows）
3. **service_type 4 内置 + custom 字符串兜底**——local/express/section/limited + 任意自定义

**新表（V016 migration，5 个）**：
- `rail_lines`（线路 + code + color + owner）
- `rail_stations`（站点 + code + sort_order + is_terminus）
- `rail_runs`（车次 + run_number + service_type + cars + start/end + notes）
- **`rail_timetable`**（每车次每站精确到秒的到 / 发时间 + stops_here）
- `wall_rail_bindings`（wall → line+station+direction）

**新 Provider**：`RailScheduleProvider` 接替 / 补充 `ManualScheduleProvider`。从 timetable 精确查站时刻
（非估算）。`wall_rail_bindings.line_id IS NULL` 时 fallback 旧路径。

**新暴露变量**（地铁屏语义）：
- 兼容 0.4.0 `next_departure / next_terminus / eta_*`
- 新增 `next_run_number / next_service_type / next_service_type_text / next_cars / next_notes / next_arrival`
- next2 系列同样

**新 11 WS op**：rail.line.{create,update,delete} + rail.station.{add,update,delete} + rail.run.{create,update,delete} + rail.run.timetable.set + rail.wall.bind

**6 个 phase（共 ~60h）**：
- **P1（12h）** V016 5 表 + 5 DAO + record + Auto-generator helper（首站时间 + 站间秒 + 跳站集合 → timetable rows）
- **P2（10h）** RailScheduleProvider 计算（按 timetable 精确查 + ManualSchedule fallback）
- **P3（8h）** 11 WS op + 6 个 `canvas.rail.*` 权限节点 + RailOpDispatcher + AuditLog
- **P4（16h）** 前端铁路网络管理 modal（线路 + 站点 + **车次** + 时刻表 + 自动生成对话框 + 拖动排序）
- **P5（8h）** Schedule Manager modal 加铁路绑定段 + 车次语义变量预览 + i18n + 单测 + docs
- **P6（6h）** 收尾 + 版本号 0.4.3 → 0.4.4-SNAPSHOT + journal + push

**0.4.4 总 ~60h ≈ 1.5-2.5 周 wall-clock**。

## 0.6.0 路线（时间轴编辑器 · ✅ 完工 2026-06-09）

**契约**：`docs/timeline.md`（总纲 / D1-D9 固化决策 / 6 段分期）+ `rendering.md §9`（双端插值缓动数学权威）+
`protocol.md` v3 + `data-model.md §2.4.2` + `architecture.md §5.1/§5.5`。**实施前必读总纲。**

- **P1 ✅（2026-06-04）数据模型+协议 v3+撤销**：Timeline/Keyframe/KfValue（number|string|Fill 三态多态，照
  FillDeserializer 范式）/Easing/三枚举（wire camelCase）records；ProjectState 加 timelines+activeTimelineId
  （nullable 加法，Element 零改，不加表）；协议 v3 干净切换（SUPPORTED=3/3，Envelope.v 壳恒 2）；
  `timeline.create/update/delete` + `keyframe.add/update/delete/move` 7 op 走 EditSession（进 undo/redo）；
  HistoryStack coalescing（key=elementId:keyframeId:property + 500ms 窗 + 容量粘性解锁 16→64）；前端 v3
  类型 + `/timelines/` applier + CLIENT_V=3。对抗审查 5 真问题修（容量骤降 trim/trigger:null/int 回绕/坏
  blob NPE×2）。
- **P2 ✅（2026-06-04）Ticker+池化+MVP**：KeyframeInterpolator（LINEAR 6 数值属性 + ONCE/LOOP/PING_PONG
  时间映射；关键帧属性覆盖基值，未建轨属性编辑 ≤1 帧反映）；AnimationTicker（单线程 SES，Wall 缓存 +
  persistWall→invalidate，LOOP 自动播 = 启动扫描/编辑器关闭/重启恢复，ONCE 播完渲末帧自动注销，幂等关停）；
  BufferPool（线程限定 owner=Ticker，外线程退化 new 不破 rasterize 并发契约）；renderFrame（per-map 帧间
  diff + 新观察者全量补发 + viewer-gated 不 rasterize）；分流 gate（reactive 三路退让 + onReactiveYield→
  invalidate 全量补发）；`timeline.play/pause/seek` + TIMELINE_* audit；前端 TimelineManagerModal（最简
  关键帧列表，非 AE panel）。对抗审查 14 真问题修（孤儿任务 TOCTOU/FrameDiff 竞态/变量路径绕 gate/删墙
  不注销等）。**MVP 闸已过**（用户实测：循环淡入淡出 + 关浏览器/重启自动续播）。
- **P3 ✅（2026-06-05）缓动+双端插值器+一致性 CI**：EasingSolver（cubic-bezier 双端逐位等价 + EASE
  预设）/ ColorLerp（sRGB 线性空间）/ KeyframeInterpolator 补 color/fill/text 三轨 + 缓动接入 + 数值轨
  `${var:X}` resolve；第三方 Python 参照向量（Java/TS 同跑）+ 多帧 snapshot（14-timeline-easing ×
  t=0/250/500/750）；rendering.md §9 数学权威落地 + 前端镜像 web/src/timeline/。提交前再审（3 路 agent
  + 自复核）修 3 双端数字分叉：resolveAsNumber 绕严格文法 / int 收窄回绕 / trim 语义——抽 StrictNumber
  单一权威（三处后端正则归一）+ 两端 int clamp + §9.5 补全。
- **P4 ✅（2026-06-05）前端 AE 风 dock**：底部 dock（压缩画布 + 可拖 resize）+ 时间标尺 + 每元素每属性
  子轨 + 关键帧块拖拽/选中/删除/加帧/建轨 + scrubber 60fps 本地预览（useTimelinePlayback 持局部 playheadMs
  绕 project.state deep watch）+ 本地播放（按 loopMode）+ 缓动曲线编辑器（SVG cubic-bezier）+ timeline 设置 +
  懒加载拆 chunk；删旧 modal。4 视角对抗审查修 4 major（scrub 不暂停播放 / 双击重复帧 / duration 校验越界 /
  Delete 误删元素）+ 1 minor（滚动条错位）。
- **P5 ✅ 触发器 + P6 ✅ 编辑期自动播 + 文档 + 收尾**（2026-06-09 升 `0.6.0-SNAPSHOT`，**0.6.0 完工**；后端 1286 / 前端 510）。详见下方路线表 + `docs/journal.md`。

## 0.4.x 路线图速览（2026-05-21）

| 版本 | 范围 | 工时 | 状态 |
|---|---|---:|---|
| 0.4.0 | 变量系统底座 + 4 Provider + Plugin API + 命令族 | 150h | ✅ |
| 0.4.1 | chip 编辑器（Lexical / Notion 风格） | 25h | ✅ |
| 0.4.2 | 变量别名（per-wall） + Picker 表格 | 10h | ✅ |
| **0.4.3** | **全局用户变量**（userglobal namespace） | **13h** | ✅ |
| **0.4.4** | **铁路网络**（线路 + 站点 + 车次 + 时刻表 + 服务类型） | **60h** | ✅ |
| **0.4.5** | **打磨期**（修 0.4.3/0.4.4 P0 + UX 优化 + 车次复制 + 引导文案） | **20h** | ✅ |
| **0.4.6** | **体验打磨**（字体加粗/斜体 + 透明背景 + 颜色对比度 + 文案优化） | **15h** | ✅ |
| **0.4.7** | **ultrareview 修复批**（动态变量重绘 + lock confirm 绕过 + 透明背景 blend 真实 alpha + 前端 lock readonly 多入口 + 8 项更多 + CI lock fallback） | **24h** | ✅ |
| **0.4.8** | **打磨批**（M18 multi-subpath + RDP UI / M8 图层缩略图 + 颜色标签 + 对齐分布 / M13 mask lasso + 羽化 + URL 粘贴 + EXIF / Token rate limit） | **70h** | ✅ |
| **0.4.9** | **Live Paint 收尾**（M18 brush 真实形状 stroke offset polygon + text glyph 真实形状 fontkit 引入） | **18h** | ✅ |
| **0.4.10** | **ultrareview-2026-05-29 修复批**（独立深度审查 224 缺陷 → 修全部 168 个 DIRECT_FIX：data-integrity / concurrency 线程契约 / boundary 守卫 / 异常 ack / 双端一致 / 模板校验 等；TRADE_OFF 23 + NEEDS_DESIGN 30 暂不修）+ 设计哲学固化（"工具不是保姆" PROPOSAL §2.1/§5.2.7） | **~40h** | ✅ |
| **0.5.0** | **纯服务端性能 Benchmark**（后台模拟 rasterize/palette/GC + 21 程序生成 scene + `/canvas bench` 命令族 + report.json/summary.txt/report.html 三件 + 50mspt 交互计算器 + CI 功能性 gate；**不测网络**，见 PROPOSAL §2.1/§5.2.7 + docs/benchmark.md） | ~150h | ✅ |
| 0.6.0 | 时间轴编辑器（AE-like：keyframe + easing + AnimationTicker；默认 20fps + config max-fps 默 60 安全阀，不做成本估算/自动校准/自动降级；设计总纲 `docs/timeline.md` + 用户教程 `docs/timeline-guide.md`） | ~360h | ✅ **完工**（2026-06-09 升 0.6.0-SNAPSHOT；P1 数模+协议 v3 / P2 Ticker+MVP / P3 缓动+双端一致 CI / P4 AE dock + P4.5 整体帧+拉就设 / P5 触发器 / P6 编辑期自动播+文档+收尾。后端 1286 / 前端 510） |
| 0.7.0 | Scratch-like 视觉运行时（**自写积木画布**，Blockly 否决；6 触发器 + 8 动作 + 条件分支；后端唯一执行器 + 真试跑轨迹高亮；命令走服主白名单模板；设计总纲 `docs/scripting.md`，D1-D8 已固化 2026-06-10） | ~340h | ✅ **完工**（P1 ✅ 数模+V017+协议 v4+前端镜像 / P2 ✅ 2026-06-10 执行引擎——TriggerRouter 3 触发器 + ScriptRunner 帧栈+Budget 三闸+ABA 链深 + ActionExecutor 8 动作双路径 + ConditionEvaluator 条件文法(比较/算术/var() + == 数值等值)，MVP 闸用户实测过(测试1/2) / P3 ✅ 2026-06-10 游戏事件层——进服/击杀/玩家靠近触发器 + 命令模板系统(K13 白名单转义) + script.test 异步轨迹(script.trace 推送) + 条件预 parse，后端 1575/前端 536 / **P4 积木引擎 ✅ 2026-06-11**——A 引擎纯逻辑(blockTree 树操作/blockLayout 序列化/dropTarget 吸附几何) + B 画布骨架(无限画布 pan/zoom + 全屏 overlay + TopBar 入口 + chunk) + C 积木渲染(blockDefs 声明式 + BlockStack/BlockNode 递归) + D1 编辑会话(working copy + 本地 undo + debounce save) + D2 拖拽吸附(buildSlots/collectSlots + findDropTarget + BlockPalette + 移堆)，前端 739 / **P5 积木内容 ✅ 2026-06-11**——E 命令模板端点 + F 参数表单(BlockParamInput 全类型 + 变量/时间轴/元素/声音/命令模板下拉) + G 条件可视构建器(↔ 字符串双向 + 高级文本框) + H 试跑高亮(trace blockId 树路径定位 + 步进)+ validator 镜像 + i18n + 集成审查修 2 阻断(新建带默认动作 / 帽子触发器可编辑)，前端 877/后端 1585。**实测两轮 + systematic-debugging 修复批**(banner 布局位移/拖出默认值/拖拽 capture/Scratch 视觉重做/**画布渲染数据源主根因——当前编辑规则改渲 workingCopy 拖积木立即显示**)，前端 917。**核心数据流用户实测通过**。P6 收尾(压测/文档/剩余触发器)并入「0.7.x 整体收尾」✅ 2026-06-14） |
| 0.7.1 | **体验优化**(预览框「幽灵拖动设目标坐标」深度3 + 友好元素积木 8 个 + 新动作 7 + 新触发器 3〔右键墙/离开区域/退服〕+ 有界循环「重复N次」+ 协议 v5；OnCommand 推迟 0.7.2；设计总纲 `docs/scripting-0.7.1.md`，E1-E9 已固化 2026-06-11) | ~150h | ✅ **完工（2026-06-13）**(P1 ✅ 2026-06-11 友好积木 8 + 低风险新动作 5〔nudge/发消息/随机/乘除/播完等待〕——后端 6 新 Action 子类〔setElementProperties 复合 action 守 blockId 同构 + nudgeElement 读改写 + playTimelineAwait 挂起续接 + sendMessage 经 TRIGGER_DETAIL 拿触发玩家〕+ 前端友好皮肤渲染〔BlockNode 按 kind 选皮肤〕+ palette 友好分组；后端 1656/前端 955。**P2 ✅ 2026-06-12** 3 新触发器〔右键墙/离开区域/退服——TriggerRouter quit 全局/rightClick 按墙/PlayerNearSampler 离开沿 + GameEventListenerHub off-hand guard〕+ 有界循环「重复N次」〔ScriptRunner 展开 count 轮 body，blockId 不带 round 守同构〕+ 协议 v5 干净升版；blockTree 泛化 NESTED_SEQ_KEYS；后端 1702/前端 987。**实测修复批**(右键墙〔画框保护取消事件→hub ignoreCancelled=false〕/循环体编辑丢失〔scriptEdit 平行树操作漏 repeat body→复用 blockTree〕/触控板误拖〔加拖动阈值 + isFormTarget closest〕3 bug 根因修 + 变量实时预览/单积木拖删 2 feature)。**P3 ✅ 2026-06-12** 预览框左右分栏(复用 renderProjectState 渲当前墙 + previewCoords 坐标系互逆 + 拖宽/折叠)+ 元素点选取当前值(深度2: 点预览元素填 elementId + 按 friendly kind 取当前 x/y/w/h/rotation/opacity + 描边高亮)；前端 1078。**P4 ✅ 2026-06-13** 幽灵拖动设目标坐标（全 transform P4a 移到 + P4b 改大小 + P4c 旋转，元素半透明真样子虚影 + 坐标反算 + activeCoordBlock）；前端 1078 → 完工。**P5 ✅ 2026-06-13** 剩余动作（停止本脚本 / 播放粒子〔14 内置白名单〕/ 等待直到条件〔pollWaitUntil 独立调度不重入 Budget〕+ validator 镜像 + i18n）；后端 1731/前端 1127。**五阶段全部完工**） |
| **0.7.2** | **视觉运行时打磨 + 积木扩充**（2 小修 + 变量±1 + 6 积木〔克隆元素/删除元素/重复直到/全服广播/变量复制/文本拼接〕+ Scratch 实色风 UI 精修；OnCommand 推迟；设计总纲 `docs/scripting-0.7.2.md`，F1-F10 已固化 2026-06-13） | ~50h | ✅ **完工（2026-06-13）**（P1 ✅ 2 小修〔blockDefs category + extractVars 扫条件 var()〕+ 变量预览 +1/-1〔VarWatchRow useLongPressIncrement + user/userglobal 可写判定〕；前端 1131。**P2 ✅** 元素积木〔克隆 EditSession.cloneElement 双路径 + 删除〕+ 变量积木〔CopyVariable/AppendVariable〕+ F10 配额 `scripts.max-elements-per-wall` 默 200；后端 1781/前端 1135。**P3 ✅** RepeatUntil while 语义〔RunState Map + Budget/maxIter 双闸 + K16 预检〕+ 全服广播 sendMessage 加 target；后端 1807/前端 1157。**P4 ✅** 积木 UI 精修 3 轮〔块 CSS 变量节奏/梯形榫头/拖影迷你实色/槽位高亮/palette 饱和实色〕；前端 1157。shadowJar 170M） |
| **0.7.3** | **补间动画（在 X 秒内）+ 备选积木批 + ultrareview 三批收官**（Scratch 式 C 形包裹积木 + 架构 A 独立 TweenScheduler + 全属性〔数值/颜色/fill〕+ 全 EasingType + 自定义曲线 + per-wall 帧率 + 与时间轴共存；备选 4 积木〔随机分支/元素置顶置底/变量取整/标题弹窗〕+ 协议 v7；ultrareview 三批 27 条真问题收官；设计 `docs/scripting-tween.md`/`docs/scripting-0.7.3.md`，G1-G4 已固化） | ~80h | ✅ **完工（2026-06-14）**（补间 P1 ✅ 数模+协议 v6+前端镜像；P2 ✅ 引擎 MVP 路径 Z `renderStatic` 省 DB + 挂起 + per-wall 帧率；P3 ✅ 全属性 color/fill + 与时间轴共存 `isWallAnimating` 分流；P4 ✅ C 形 UI + 自定义缓动曲线 EasingCurveEditor + body 拖入限制；P5 ✅ 文档 + §12 玩家用法。备选积木 4 个 + 协议 v7 + 版本号 bump 0.7.3-SNAPSHOT；ultrareview 第一批 8 条〔静默失败+崩溃〕+ 第二批 8 条〔真崩溃/数据错乱〕+ 第三批 11 条〔协议 close 一组+P2 散点〕收官；后端 ~1989/前端 1303） |
| **0.7.4** | **前端体验优化批**（6 个体验 bug：小窗口响应式 + 画布平移 + 变量 Picker 根因等 + 新 OverflowMenu 组件；设计 `docs/superpowers/plans/2026-06-15-0.7.4-frontend-ux.md`） | ~10h | ✅ **完工（2026-06-15）**（前端 LeftTools/TopBar/OverflowMenu/usePanScroll/CanvasZoomBar/VariablePicker/interpolator/pickerLogic + 后端 VariableProvider/RailScheduleProvider/VariableMetadataHandler；版本号 0.7.3→**0.7.4-SNAPSHOT**；后端全绿 / 前端 1334） |
| **0.8** | **工程导入导出（.canvas）+ SVG 矢量导入**（Part A：`.canvas` 导出/导入 + 6 层安全栈 + 脚本纳入；Part B：SVG→可编辑元素，fillRule 双端 / viewBox 映射 / d 归一化(M/L/Q/C/Z) / gradient 降维 / 内嵌位图 / 复杂度上限；设计总纲 `docs/import-export.md`，D1-D10 已固化 2026-06-16） | ~120h | ✅ **完工（2026-06-20）**（Part A 19 task ✅ 2026-06-17 / Part B 20 task ✅ 2026-06-20，全程 subagent-driven + controller 独立 review 签名；前端 1421 全绿 / 后端全绿。**注：0.8 功能在 `0.7.4-SNAPSHOT` 版本串下完成，2026-06-22 的 0.8.1 修复批时已 bump 到 `0.8.1-SNAPSHOT`（见下方 0.8.1 行）**） |
| **0.8.1** | **独立 ultrareview P0-P2 修复批**（26 项：21 真修 + 4 防御性硬化 + 1 误报）：WS auth 竞态 / ScriptRunner ThreadLocal 泄漏 / AssetIngest 线程泄漏 / MapPool 绑定原子化 / mask 越界(科学计数法绕过) / 协议 fill 严格校验 / SVG skew·负值·hex 颜色 / 渐变 stop=1.0 / Bayer·Palette 兜底 / FrameDeployer 空框 / rail 跨墙绑定 / HistoryStack 时钟回退 等；**首次把版本串从 `0.7.4-SNAPSHOT` bump 到 `0.8.1-SNAPSHOT`**） | — | ✅ **完工（2026-06-22）**（commit `65a0218`；后端 BUILD SUCCESSFUL + 2 新测试 PaletteLut/ElementValidatorMaskBounds / 前端 1443 全绿） |
| **0.9.1-0.9.6** | **1.0 前 6 块硬闸**（0.9.1 数据契约闸 WAL 备份+forward-only 守卫 / 0.9.2 可观测性 `/canvas diagnose` / 0.9.3 安全收尾 SECURITY.md+限流断连+dependabot / 0.9.4 发布验证 release.yml 首个 GitHub Release / 0.9.5 多版本 Paper 26.x〔PacketEvents 2.12.2〕 / 0.9.6 MapPool·WallRestorer 测试守卫） | — | ✅ 2026-06-23~07-02 |
| **0.9.7** | **脚本校验报错 i18n**（~100 条 ScriptRuleValidator→ValidationError，按编辑器 locale 渲染） | — | ✅ 2026-07-16 |
| **0.9.8** | **去 AI 味专项**（用户可见文案+全部代码注释+全部 docs 的内部阶段编号/自辩护/过程叙事，零逻辑改动；总纲文档结构手术删过程段；ultrareview 4 档案→docs/archive/；12 task subagent-driven） | — | ✅ 2026-07-17 |
| **0.9.9** | **包名重命名** `moe.hikari.canvas`→`ac.haru.hikaricanvas`（趁 pre-1.0 最后窗口；插件名 HikariCanvas 不变→PDC/数据兼容；412 rename + group ac.haru + relocate） | — | ✅ 2026-07-17 |
| **0.9.10** | **PacketEvents 2.12.2→2.13.0**（修 Paper 26.2 无法加载；已发 `v0.9.10-rc.1`，用户实测 26.2 起服正常） | — | ✅ 2026-07-18 |
| M30 | 图层 mask / group / smart object（PS-style）— 独立大版本 | 30h+ | 远期 |
| 弃 | B-advanced DCEL 覆盖 4% Live Paint 用例 — 38h+ 性价比低 | — | 不做 |

> **当前最新（2026-07-18）**：**已发 `v0.9.10-rc.1`**（prerelease；PacketEvents 2.13.0 修 Paper 26.2 加载，用户实测 26.2 起服正常）。版本串 `0.9.10-SNAPSHOT`，包名 `ac.haru.hikaricanvas`（0.9.9 从 `moe.hikari.canvas` 改）。**0.9.x = 1.0 前打磨**：6 块硬闸（0.9.1-0.9.6）+ 脚本校验 i18n（0.9.7）+ 去 AI 味（0.9.8）+ 包名重命名（0.9.9）+ 26.2 支持（0.9.10）。1.0 前剩：README/文档作者亲写 + de-rc → `1.0.0` + 发布说明 + cut `v1.0.0` stable。**逐条进度一律以 `docs/journal.md` 为准（倒序，每会话一条），本表 / 里程碑叙事 / 各设计文档分期表可能滞后。**

> **0.5.0+ 详细设计** 见 `docs/dynamic-data.md §13`（版本顺序依赖、Benchmark 4 原则）+ `docs/scripting.md`（0.7.0 总纲）。**原"一画布二选一"已作废（2026-06-10，scripting.md D2）**：脚本是上层（条件分支 + 副作用），时间轴是被编排的素材（脚本可 playTimeline），同画布共存；0.6 三种触发器原样保留给简单场景。

**0.5.0+** 动画 / 时间轴 / Blockly 脚本路线见 `docs/dynamic-data.md §13`。

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
- **M16.5 构建依赖 P0 3 项**（commit d9ab3fc）shadowJar 7 条 relocate（jackson/caffeine/jdbi/hikari/javalin/jetty/snakeyaml）→ `ac.haru.hikaricanvas.shaded.*`；mergeServiceFiles 处理 SPI；**org.sqlite 不 relocate（JNI 保护）**；HikariCP `setLeakDetectionThreshold(30_000)`；npm ci 替 npm install
- **M16.6 P1 防御 + 观测 8 项**（commit ca4bc54）Jackson FAIL_ON_UNKNOWN_PROPERTIES 接收侧严格 + 错误消息脱敏；新 `web/Protocol.java`：SUPPORTED_MIN/MAX/`CLOSE_PROTOCOL_VERSION_UNSUPPORTED=4002`；auth payload 新 `client_v`；ready payload 新 `accepted_v`；双向校验；V010 DROP COLUMN refcount（pre-release 激进改 schema OK）；AuditLog 5 新事件 `WALL_LOCK / WALL_UNLOCK / IMAGE_UPLOAD_OK / IMAGE_UPLOAD_REJECTED / PERMISSION_DENIED` + write 失败 SEVERE stack trace；`HikariCanvasConfig.sanitizeEditorUrl` URI 解析 + http/https 白名单；**会话级 IP 绑定**（Session.boundIp + bindOrCheckIp，**不绑 token 绑 session**）；SessionManager 三 map → ConcurrentHashMap + ReentrantLock；TopBar.toggleLock/commitAliasEdit optimistic 回滚 + 连击防护

**关键架构决策（M16 已固化）**：

1. **草稿 wall 协作语义**：未锁定 wall（lockedAt=null）默认任何 `canvas.edit` 玩家可 `/canvas open`——这是协作中间态语义（多人接力 / 同步编辑）。只 owner 可触发 lock；lock 后非 owner 拒 open（除 `canvas.admin.bypass-lock`）。未来 ACL（owner-only 草稿）走 v1.x 协作 scope，详见 `docs/architecture.md §13`
2. **多世界分桶**：MapPool 按 world UUID 分桶，wall 与 map 必须 world 一致（强校验）；config `map-pool.per-world: {}` 配每世界 size
3. **会话级 IP 绑定**：首次 auth 时 CAS 绑定 caller IP 到 Session.boundIp；后续帧不一致拒 4001。**绑 session 不绑 token**——token 已单次使用 + 短 TTL，再绑 token 是冗余且阻塞合法重连
4. **shadowJar relocate**：所有第三方依赖（除 org.sqlite JNI）relocate 到 `ac.haru.hikaricanvas.shaded.*` 防服内插件 classpath 冲突
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

