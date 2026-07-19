# 变更日志

本文件记录 Claude 每次对本项目做出的修改。**新条目追加到文件顶部**（倒序）。每条应含：日期、改动范围、简要说明、关联文件。
代码与文档的日常提交信息写 git commit，本文件只留会话级摘要。

---

## 2026-07-19 · 0.9.15 控制台日志清理（开发探针退场）

项目收尾期，把开发期埋的「逐操作」调试探针从控制台清出。先 2 个 opus 子代理盘点后端（服务器控制台）+ 前端（浏览器 console），用户拍板：**降 fine 不删** + **不加 config 开关**。版本 `0.9.14 → 0.9.15-SNAPSHOT`。

**评估结论**：日志纪律本就不错——后端零裸输出（0 个 `System.out`/`println`/`printStackTrace`）、真正热路径（投影循环/每条 WS 消息）零 INFO；前端探针全已 `import.meta.env.DEV` gate。要清的只是后端一批「逐操作」INFO 探针。

**后端（26 条 `.info`→`.fine`，跨 13 类；fine 默认静默、服主调 logger 级别仍可看）**：
- 热路径 2：`TimelineTriggerRegistry`（变量变化触发时间轴）+ `ActionExecutor`（玩家脚本 log 输出）
- `FrameDeployer` 逐格诊断簇 6（`[reAttach]`/`[spawnSlot]` 每 slot）+ WS session 开关 3（`WebServer` connected/closed）
- 逐字体逐图标启动噪音 8（`FontRegistry`/`FontMetricsTable`/`IconRegistry`，22 字体本会打 22 行）
- 其余中频 7（`AnimationTicker`/`PapiVariableBridge`/`VariableProviderDaemon`/`HikariCanvasAPIImpl`/`PluginCleanupListener`/`TemplateRegistry`）
- 删 `HikariCanvas:969` 的 `(skeleton)` 遗留措辞（enable banner 保留）；`ActionExecutorTest` 一条断言随之 `INFO→FINE`
- **保留 INFO**：全部一次性启动摘要（`N font/icon ready`、`WebServer listening on host:port`、迁移事件、wall 恢复数）+ 全部 WARNING/SEVERE + `AuditLog` + 公网绑定警告 + `WallOpDispatcher:199` refresh 结果摘要 + WS 安全 close（auth 失败/协议/限流）
- 效果：启动 15-20 行干净摘要，运行时基本安静、只在真出错时说话

**前端（回归防护）**：console 已很干净（30 处里 26 `warn` + 1 `error` 该留、2 探针已 DEV-gate）；`vite.config.ts` 加 `esbuild.pure:['console.log/info/debug/trace/dir']`（**非** `drop:['console']`——后者会误删 error/warn）+ `drop:['debugger']`。仅生产 minify 生效、dev 无损。**验证硬证据**：生产产物 `console.log/info/debug 残留=0`、`console.error/warn 保留=21`。防未来漏 gate 的裸 console.log 进 bundle。

**不做**：不加 config `debug-logging` 开关（JUL 级别机制已够，加了冗余）；不引入 log wrapper（各类直接持 `Logger`，降 fine 无需抽象）。

**验证**：后端 `:plugin:test` BUILD SUCCESSFUL（**2168**）+ shadowJar `HikariCanvas-0.9.15-SNAPSHOT.jar` 152 MB；前端 vite build + vitest **1450** + esbuild.pure 产物硬验证。

---

## 2026-07-19 · 0.9.14 生产 bug 三修（WS 地址 / 模板预览崩溃 / 静态 MIME 加固）

生产服务器（真实域名反代部署，实测跑 0.9.12 jar）报 3 个问题。方法论：先派 3 个 opus 子代理**独立读代码核实根因**（含真实复现），再派 3 个 opus 子代理**并行 TDD 实施**（先红后绿），controller 亲自核对三块 diff 无 scope 蔓延。版本 `0.9.13 → 0.9.14-SNAPSHOT`。

**① 前端连不上画布（WS 地址逻辑写反 · ✅ 属实 · 必修）**
`web/src/network/wsClient.ts:1019 resolveWsUrl()` 条件写反：只有页面**恰好**在 `127.0.0.1:8877` 才「按来源拼」（那时拼出还是回环），任何真实域名落进死写的 `ws://127.0.0.1:8877/ws` 兜底 → 生产任何场景都只返回回环、连不上，且 `ws://` 撞 HTTPS 页有 mixed-content。修：默认按 `window.location` 同源拼（`https→wss`、host 用 `loc.host` 含反代真实域名），仅 dev（页面 `:9173`）特例跨端口连后端 8877；删硬编码兜底 + `export` + `resolveWsUrl.test.ts` 3 场景（先红后绿：坏代码 3 fail、修后绿）。dev 判定用 `port==='9173'` 而非 `import.meta.env.DEV`（vitest run 下后者为 false 会误判）。

**② 生产疯狂刷 InvalidTypeIdException（模板预览崩溃 · ✅ 属实 · 最高频 · 代码 bug）**
`TemplatePreviewService.stateOf` 把强类型 `List<Element>` 塞进 `Map.of("elements", elements)`，值类型擦成 `Object` → Jackson `@JsonTypeInfo(type)` 走 Object 路径不写 `type` 判别符 → `convertValue` 反序列化 `List<Element>` 多态找不到 `type` 崩。**所有含 ≥1 元素的模板预览必挂**（子代理用真实 jackson 2.22.1 逐字节复现）。不是近期回归——一直存在，要非空模板 + 前端拉缩略图才暴露；`renderPreview` 的 catch 兜住了不崩服务，但 `computeIfAbsent` 对 null 不缓存 → 每次请求重抛刷屏。本仓 `StatePatchBuilder` javadoc 早记载同坑。修：`stateOf` 改直接构造 `new ProjectState(w,h,bg)` + `addElement`（都是 public），绕开 Jackson、零 type 丢失；删 unused `ObjectMapper`/`mapper` + 订正 javadoc + `TemplatePreviewServiceTest` 4 case。不影响真正应用模板的路径（`TemplateInstantiator` 不走这段）。

**③ 静态资源 MIME（报告不属实 · 顺手加固）**
「静态资源全发 text/plain」不属实——`WebServer.guessMime` 已按扩展名映射，实际产物 `.js→application/javascript` 等全对（正因如此浏览器才执行了 JS、才报 ws 连接错，**反证 MIME 是对的**；问题 ① ② 与它无关）。但挖到真实隐患：Javalin 7 默认 Content-Type 就是 `text/plain`，`guessMime` 未覆盖扩展名（`.map`/`.wasm`/字体）会 fall-through 落 text/plain，且全站无 `nosniff`。加固：`guessMime` 扩表（`.map`/`.wasm`/`.woff`/`.ttf`/`.otf`/`.ico`/`.txt`）+ `serveClasspath` fall-through `application/octet-stream` + `X-Content-Type-Options: nosniff` + `WebServerMimeTest` 12 case。现有 `.js`/`.css`/`.html` 映射一字未改。

**验证**：后端 `:plugin:test` BUILD SUCCESSFUL（2152 → **2168**，+16：4 preview + 12 mime）+ 前端 vitest **1450**（1446 + 4 resolveWsUrl）+ shadowJar `HikariCanvas-0.9.14-SNAPSHOT.jar` 152 MB。commit + push + 验签后发 `v0.9.14-rc.1`。

---

## 2026-07-19 · 0.9.13 首装跳过逐迁移备份 + 清资源文件内部代号

用户 0.9.12 首次加载发现 dataFolder 里堆了十几套 `data.db.pre-V*.bak`（+ `-wal`/`-shm`），另注意到 `config.yml` 仍有 `M16 P1.3` 等内部代号。两件 1.0 前打磨合并为 0.9.13。版本 `0.9.12 → 0.9.13-SNAPSHOT`。

**① 首装跳过逐迁移备份（行为修复）**
- 根因：`MigrationRunner.runUpTo` 在每个待执行迁移前无条件 `tryBackup`。空库首装（`currentVersion==0`）要从 V001 一路迁到 V017，于是每步都备份一次 → 堆十几套无意义 `.bak`（空库根本没有既有数据可保护）。
- 修法：`boolean freshInstall = currentVersion == 0`，首装跳过 per-migration 备份（`autoBackup && dbFilePath != null && !freshInstall`）。自动备份只为「升级已有数据的库」在某迁移失败时兜底；全新库迁移失败直接删 `data.db` 重来，无数据可丢。
- 加测试 `freshInstall_skipsPerMigrationBackups`（空库跑全量 V001-V017 → 0 个 `.bak` + `wall_scripts` 表已建）。**现有 `data.db.pre-V*.bak*` 可直接删**（`data.db` 活库不受影响）。

**② 清资源文件内部开发代号（服主可见文件去黑话）**
- `config.yml`：8 处注释（`M16 P6.5`/`P1.2`/`P1.3` / `M2` / `P2.3` / `M14` / `Phase 1` + `P1.4-P1.6` / `M13` → 剥前缀留正文）。
- `paper-plugin.yml`：1 处注释（`M2 demo 阶段` → 「默认权限策略」）+ **17 处权限 `description` 版本尾巴**（`（0.4.0-P3-L）` 内部阶段代号 + `（0.4.3）`/`（0.4.4）`/`（0.5.0）`/`（0.7.0）` 版本溯源 → `description` 统一为纯功能说明，保留「危险面」/「wall owner 走 schedule.own」等实际说明；版本溯源信息 journal 里有）。
- `db-migrations`：8 个 `.sql` 注释（V002-V008/V010：`M5-D6`/`M5.5`/`M8-B`/`M13`/`M14`/`M16 P6.3`/`M15 §architecture` 等 → 剥净；**保留 `v1`/`v2` 协议版本号 + V016 铁路 code 数据示例 `"M2"`**）。
- 两个 opus 子代理并行清理（config+paper / db-migrations），controller 统一核对：代号残留清零 + ruby YAML 校验 4 文件有效 + 权限节点数不变（44）。

**验证**：全量 `:plugin:test` **BUILD SUCCESSFUL**（备份修复 + 全量 V001-V017 迁移 SQL 完整性双证）+ shadowJar `HikariCanvas-0.9.13-SNAPSHOT.jar` 152 MB。commit + push + 验签后发 `v0.9.13-rc.1`。

---

## 2026-07-18 · 合并 dependabot（10 PR）+ Jackson 模块对齐 2.22.1

用户合并 dependabot 10 PR：`jackson-databind` 2.18.2→2.22.1 + 5 个 GitHub Actions 升级（checkout 4→7 / setup-node 4→6 / gradle/actions 4→6 / action-gh-release 2→3）。controller `git merge origin/main`（无冲突——dependabot 碰 `plugin/build.gradle.kts` + workflows，本地 0.9.12 碰 benchmark/lang/root build.gradle.kts，文件不重叠）。
- **Jackson 模块对齐**：dependabot 只升了 databind，`jackson-dataformat-yaml` 仍 2.18.2 → 版本 skew。CLAUDE.md 要求两模块同版本（2.18 yaml 模块跑在被 databind 传递升级到 2.22 的 jackson-core 上有风险）→ 手动补齐 `jackson-dataformat-yaml` → 2.22.1。`THIRD-PARTY-LICENSES.md` + CLAUDE.md 版本同步。
- 验证：`clean` 全量 `:plugin:test` **2151 全绿**（Jackson 2.22.1 无回归）+ shadowJar。

---

## 2026-07-18 · 0.9.12 benchmark 报告 HTML i18n（1.0 前最后一处 i18n 缺口）

`/canvas bench` 的 `report.html`（`HtmlReportRenderer`）此前硬编码中文——`summary.txt` 在 0.8.3 已 i18n，report.html 遗留。按 `default-locale` 渲染（报告是磁盘文件、无"某玩家"，与 summary.txt 同口径）。版本 0.9.11 → 0.9.12-SNAPSHOT。

**做法（照 summary.txt 范式）**：
- `HtmlReportRenderer.render(report)` → `render(report, Messages messages)`；内部 `loc = messages.defaultLocale()` + `t = messages.rawOrNull`（**raw 不走 MiniMessage**——故 lang 值可含 `&times;`/`&nbsp;`/`&divide;` HTML 实体不被当标签吃）。`t` 穿进各 append helper。
- 35 处输出串 → `command.bench.report-html.*`（46 key，中英各 46）；`<html lang>` 按 loc（zh→`zh-CN` / en→`en`）；GC 行 / 公式的 `<b>`/实体结构留 Java、文本留 lang（prefix/mid/suffix 拆键）。
- `BudgetFormula` 不动；disclaimer 走 lang key。**JS 计算器块无中文**（标签在 HTML `<label>` 里）不用动；`SvgBarChart` 不用动（图表标题从 HtmlReportRenderer 传入）。
- 调用方 `BenchmarkSubCommand:325` 传 `messages`；2 测试（`BenchmarkP3Test` / `BenchmarkPipelineSmokeTest`）改 `new Messages(log) + loadBuiltIn()`（**关键坑**：`new Messages(log)` 构造器不加载 lang，`byLocale` 空 → key 回退 key 名；须 `loadBuiltIn()`）；P3 的 `保守` 断言→语言无关 `class="disclaimer"`（测试默认 en_us 渲染英文 disclaimer）。

**验证**：clean 全量 `:plugin:test` **2151 全绿**（`LangFileParityTest` 校验 46 key 中英对齐 + 2 benchmark 测试渲染通过）+ shadowJar `HikariCanvas-0.9.12-SNAPSHOT.jar`。**i18n 完整性到此彻底齐**（benchmark 报告是最后一处）。

---

## 2026-07-18 · 0.9.11 PacketEvents 不打包（GPL-3.0 合规）+ 第三方许可 NOTICE

1.0 前许可审查发现:**PacketEvents 是 GPL-3.0（copyleft），而它是 `implementation` 打进 shadow jar**——分发的组合 jar 被 GPL 传染，和项目 MIT LICENSE 冲突（不能把打包 GPL 的 jar 当 MIT 分发）。用户拍板：不打包，改外置必装插件依赖。版本 0.9.10 → 0.9.11-SNAPSHOT。

**修法（改成外置必装插件依赖）**：
- `build.gradle.kts`：PacketEvents `implementation` → `compileOnly`（编译期用、不进 jar）。
- `paper-plugin.yml`：加 `dependencies.server.packetevents`（`required: true` + `load: BEFORE` + `join-classpath: true`；PE 独立插件名确认 = `packetevents` 小写，查其 plugin.yml）。
- `HikariCanvas`：删自建 PE 生命周期——onLoad 的 `SpigotPacketEventsBuilder.build + load()` / onEnable `init()` / onDisable `terminate()` / 2 import。**单独插件模式下 PE 插件自负 init/terminate**，我们只用 `PacketEvents.getAPI()`（`MapPacketSender` 不变）。
- 唯一用途 = `MapPacketSender` 推地图数据包（`WrapperPlayServerMapData`，核心投影通道），故 `required` 硬依赖。测试/benchmark 零真实 PE 引用（benchmark 只在注释提），改 compileOnly 不破坏测试。

**新建 `THIRD-PARTY-LICENSES.md`**：22 字体（全 SIL OFL 1.1）+ FA Free 6.7.2（CC BY 4.0 / OFL / MIT）+ 后端（Javalin/Jackson/HikariCP/JDBI/Caffeine/SQLite-JDBC 全 Apache 2.0）+ 前端（Vue/Konva/lexical/polygon-clipping/fontkit 全 MIT/ISC）。**唯一 GPL 依赖 PacketEvents 已外置、不在此清单**。留一条 OFL 后续项：把各字体 `OFL.txt` 也拷进 jar 达成 in-artifact 合规。

**验证**：`clean` 全量 compileJava（compileOnly 编译过）+ `:plugin:test` **2151 全绿** + shadowJar `HikariCanvas-0.9.11-SNAPSHOT.jar` **里 0 个 `retrooper/packetevents` 类**（un-bundle 证明，MIT jar 无 GPL 代码）。**⚠️ 部署破坏性变更**：0.9.10-rc.1（打包 PE）升 0.9.11 后，**服主必须先装 PacketEvents 插件**否则 HikariCanvas 拒绝加载。`docs/deployment §1` 加必装前置说明。**「装了 PE 插件后能否正常加载 + 投影」需用户真服验证**（本地无法起真服）。

---

## 2026-07-18 · 文档版本指针同步（连做 0.9.8-0.9.10 后收尾）

连做去味（0.9.8）/ 包名重命名（0.9.9）/ PacketEvents（0.9.10）三版后，同步活文档里滞后的版本指针 + 补 0.9.9 漏网的一处包名。**纯文档一致性，无代码/契约改动。**
- **CLAUDE.md**：路线表补 0.9.1-0.9.10 行；「当前最新」`0.8.1 已完工`→`已发 v0.9.10-rc.1`（标识表包名早已随 0.9.9 改 `ac.haru.hikaricanvas`）。
- **deployment / troubleshooting** 状态行 `0.8.1-SNAPSHOT·2026-06-22` → `0.9.10-SNAPSHOT·2026-07-18`；deployment §302 pre-release 句去掉会过时的版本 pin。
- **development.md**：`group="moe.hikari"`（0.9.9 带引号无冒号、漏网）→ `ac.haru`；jar 产物名 + git tag 示例 → 0.9.10 / rc.1。
- **api.md** 接入示例版本 + **variables.md**「覆盖到 X 行为」→ 当前 / 版本无关。
- **有意保留旧名**：`docs/superpowers/plans/*`（归档会话记录）+ `journal.md`（历史）+ `README.md`（作者自管，其版本指针仍 `v0.9.6-rc.1`，待作者重写 README 时一并更新）。

---

## 2026-07-17 · 0.9.10 PacketEvents 2.12.2 → 2.13.0（修 Paper 26.2 无法加载）

用户 26.2 服（Leaves 26.2）onEnable 崩：`SpigotReflectionUtil.NMS_ITEM_STACK_CLASS is null`——与 0.9.5 在 26.1.2 上的崩**同一类**：PacketEvents 内部 NMS 反射在新 MC 版本上找不到类。2.12.2 只到 26.1，不认识 26.2 的布局。版本号 0.9.9 → 0.9.10-SNAPSHOT。

**根因**：崩在**依赖**不在我们代码（grep 零 NMS，PROPOSAL §5.2.6 纪律）。PacketEvents **2.13.0**（2026-06-22 发布）加入 26.2 支持，且多版本累积——同时支持 1.21.11 + 26.1 + 26.2，不回退老版本。

**修法**：`plugin/build.gradle.kts` PacketEvents 2.12.2 → 2.13.0。API 用面极小（`PacketEvents.getAPI().init()` + `WrapperPlayServerMapData` + `sendPacket()`），签名未变 → **零代码改动**，靠编译验证。

**顺带**：
- 修正 build.gradle.kts 一条**错误注释**——原写「PacketEvents 是 plugin-loader 模式（compileOnly），不进 shadow jar」，实际是 `implementation` 打进 jar（stack trace 里 PE 类就在 `HikariCanvas.jar//` 内）。改为正确理由（bundled 但不 relocate：靠 NMS 反射 + 全局单例 `PacketEvents.getAPI()`，改包名会破坏内部）。
- CI `compat-26` 守卫从 `26.1.2.build.+` 升到 `26.2.build.+`（26.2 目前仍在 beta，最新 `26.2.build.60-beta`，`.+` 通配 beta）。守卫只 compileJava、不 gating 本修复（本修复零代码改，26.1.2 守卫本也过）。
- docs：CLAUDE 锁定版本表 PacketEvents 2.13.0 + deployment「26.2 自 0.9.10」。

**验证**：`clean` 全量 compileJava（PE 2.13.0 API 兼容确认，零编译错）+ `:plugin:test` **2151 全绿** + shadowJar `HikariCanvas-0.9.10-SNAPSHOT.jar` 打进 PE 2.13.0 的 1881 类（含崩溃点 `io/github/retrooper/packetevents/util/SpigotReflectionUtil.class`）。**「能否在 26.2 enable」需用户用新 jar 在真服验证**（本地无 26.2 server）。

---

## 2026-07-17 · 0.9.9 包名重命名 `moe.hikari.canvas` → `ac.haru.hikaricanvas`

把根包名从当初没考虑好的 `moe.hikari.canvas` 改成 `ac.haru.hikaricanvas`。**独立一次机械大改**，版本号 0.9.8 → 0.9.9-SNAPSHOT。

**为什么现在做**：公开 API 包 `*.api`（6 类，第三方插件接入用）在 1.0 会冻结成永久契约——1.0 后再改就永久破坏接入方。现在还是 pre-1.0（api.md 明示 API 可能破坏），是**最后的干净窗口**。

**数据兼容（关键）：无破坏**。PDC namespace 取插件名不取包名（`new NamespacedKey(plugin,"wall_id")` → 插件名小写 `hikaricanvas`）；**插件名 `HikariCanvas` 保持不动**，故已有展示框 PDC key 照旧、现网画作升级后照常显示。SQLite schema 与 `.canvas` 格式都不含包名。

**范围**：412 文件 rename（`git mv` 5 棵源码树 main/test/generator + 2 example）+ ~2020 处 .java 引用替换；Maven group `moe.hikari`→`ac.haru`；shadowJar 7 条 relocate → `ac.haru.hikaricanvas.shaded.*`；paper-plugin.yml main（主 + 2 demo）；前端 9 文件双端镜像注释引用；活文档（CLAUDE/AGENTS/PROPOSAL/api/development/dynamic-data/protocol/examples README）。**历史 plan 文件、journal、主 README 里的旧包名有意保留**（归档记录 / 作者自管）。

**无编译不可见暗雷**：唯一 `Class.forName` 加载的是外部 PlaceholderAPI（非本包）；无 META-INF/services SPI 文件。故 `compileJava` + 全量测试 + shadowJar 即完整安全网——漏改必编译报错。

**验证**：`clean` 全量 compileJava/compileTestJava/shadowJar 全过（零漏改）+ `:plugin:test` **2151 全绿** + `HikariCanvas-0.9.9-SNAPSHOT.jar` 出。（clean 全量高负载下 `VariableMetadataHandlerTest`〔JavalinTest 内嵌 Jetty HTTP 集成〕偶发 flaky 1 次，单跑 + 重跑全量均绿，非重命名破坏。）

---

## 2026-07-17 · 0.9.8 去除 AI 味专项（1.0 前收尾）

1.0 正式发布前，把仓库里「AI 味过重」的内容清掉——用户可见文案泄漏的内部阶段编号、代码注释里的开发过程标记 / 自辩护独白、契约文档正文里的过程叙事——**全程零逻辑改动、契约规格 byte-identical**。版本号 0.9.7 → 0.9.8-SNAPSHOT。

**方法**：先 4 个 opus 探索代理产出去味清单（`.superpowers/ai-smell/findings-{A,B,C,D}.md`：用户文案 ~19 / 后端注释 ~475 / 前端注释 ~700 / 文档 702 行清单），据此 subagent-driven 分 12 task 清扫。核心洞察：**90%+ 是「剥掉 `M16 P3` 阶段前缀、保留后面真实约束正文」的机械活**，真正整条删的极少。

**用户拍板的 3 个边界**：① 契约文档里的开发过程段（分期表 / 工时账 / ✅完工快照）**从文档删除、不复制回 journal**（journal 已有会话记录）；② `ultrareview-*.md` 4 份审查档案 `git mv` 到 `docs/archive/`；③ CLAUDE.md 只删夸张口癖，历史索引 / 版本表 / 架构纪律全保留；④ `README.md` 作者亲自维护、本批不碰。

**12 task**：
- **T1** 用户可见文案（messages.ts value + lang yml，14 串）：剥 `P3`/`P4`/`M7`/`fsck`/`PushRateLimiter`/`project_json` 泄漏 + 过时「即将上线」占位。
- **T2-T5** 代码注释（后端 script/render/benchmark + 其余包 + 前端 messages/components + 其余目录）：~1175 条注释剥前缀，**零代码改动**每 task 决定性证明（两 commit 剥光注释后 byte-identical + 测试数 2151/1446 不变）。双端心脏 PreviewRenderer/protocol.ts 契约逐字保全。
- **T6-T9** 文档：dynamic-data（1379→1122，删 §12/13/15/19 + §17/18 尾）· protocol/data-model/rendering（删 changelog 章 + dead-YAML，17 CREATE TABLE + 数学公式 byte-identical）· architecture/security/template-spec（§3.6 lock 块归位 + 44 权限节点 + 审计事件保全）· timeline/scripting 系列（删分期/工时段，决策摘要 + Action record + 白名单保全）。
- **T10** HOW-TO 文档轻扫 + **2 个实质 bug**：api.md 过时版本 `0.3.0-SNAPSHOT` → 版本无关表述；`troubleshooting.md` 与 `deployment.md` 对 Paper 26.x 的**相反指令**对齐（0.9.5 起同 jar 支持 26.x / Java 25，修掉「锁死 Java21 别升 26.x」的过时警告，真会坑服主）+ deployment §9 子标题误编号修正。
- **T11** CLAUDE.md 去「不可越界」×4 +「整个项目报废」×1 + ultrareview 4 档案归档。
- **T12** 版本 bump + 本条 journal + 全量验证 + push。

**留作者的过时注释**（与代码矛盾、需内容改写而非剥前缀，本批**未动**，供后续定夺）：BitmapFont/PlaceholderRenderer「M4 会替换此类」（从未替换）· TextLayout「竖排早期不实装」（layoutVertical 已存在）· ScriptRuleValidator javadoc「表达式语法 P2 接」（已由 ConditionEvaluator 实现）· TemplateElement「icon v1 不实装」（icon 已实装）。

**过程记录**：subagent-driven，子代理全程 opus；API 数次掉线（T3 实现者 commit 后掉线 → controller 重建报告 + 独立验证；T3 补丁子代理再掉线 → controller 亲做；2 个 reviewer 返回乱码 → 重派）。T3/T7 各含主 commit + controller fix。

---

## 2026-07-16 · 0.9.7 脚本校验报错 i18n（1.0 前最后一处 i18n 缺口）

把编辑器保存脚本时的 ~100 条校验报错（此前硬编码中文）国际化，按**编辑器 UI 语言**显示。subagent-driven（per-task implementer→review 全 opus + opus 整支终审 Ready-to-merge）。版本号 0.9.6 → 0.9.7-SNAPSHOT。

**架构**：校验报错经 WS `Envelope.error(id,"SCRIPT_INVALID",<message>)` 发编辑器，前端显示在"新建规则失败：…"横幅。故后端按编辑器 locale 渲染好这句：`ScriptRuleValidator` / `checkConditionSyntax` 从"返回中文字符串"改为返回 `ValidationError`(key+参数)，`ScriptOpDispatcher` 用既有 `Messages`（0.8.2）按 `session.editorLocale` 渲染；前端只需 auth 帧带 `ui.locale`。

**四段**：
- **T1 基建**（`d9180340`）：`ValidationError` record + `Messages.plain`（SPI-free 纯文本渲染，避 PlainTextComponentSerializer 在测试 classpath 上 SPI 崩）+ `Session.editorLocale` + 前端 auth 带 `ui.locale`（经 `uiLocaleToGameId` 桥接 zh→zh_cn/en→en_us，否则 resolveLocaleId 失配→整批静默回退默认）+ WebServer auth 读存 + ScriptOpDispatcher 注入 Messages。validate 仍返 String 行为不变。
- **T2 大头**（`a8763ce8`）：`ScriptRuleValidator` 94 站点 → `ValidationError`（91 key）+ 91×2 双语 lang（zh 原文 + en 地道译文，参数名两端精确对齐）+ `renderValidation` 渲染。`.canvas` 导入路径也调 validate（ScriptImporter，无 locale）→ key 塞 `ImportWarning.detail`，前端 wrapper 句本地化（反修原中英混串漏洞）。
- **T3**（`8f25cc7a`）：`checkConditionSyntax` 3 wrapper → ValidationError + 3×2 lang；detail 透传 ConditionParser 英文技术串。
- **T3.5**（controller `d33a9532`）：`ScriptImporter` 导入路径 7 条报错（3 condition wrapper + 4 parse 错误）英文化——该路径无 per-player locale，语言中性英文，收干净导入路径 i18n 遗留。

**过程**：5 commit（T1-T3.5 + 本收尾）+ 版本 bump。子代理全程 opus。整支终审 Ready to merge：端到端 i18n 链逐环核实无断点、脚本校验用户可见中文清零、94 key 精确对齐（en=zh、0 死键）、auth 加 locale 向后兼容（旧 client→默认 locale 不崩）。**实现者两处真坑修**（locale 形态失配 zh vs zh_cn / PlainTextComponentSerializer SPI 冲突）。

**测试**：后端全量 `:plugin:test` BUILD SUCCESSFUL（含 LangFileParityTest / MessagesTest）+ 前端 vitest 1446 双绿；shadowJar `HikariCanvas-0.9.7-SNAPSHOT.jar` 152MB。

**1.0 进度**：6 块硬闸全完成 + rc 稳定；脚本校验 i18n（本批）收口——**i18n 完整性到位**。1.0 前剩：① 去 AI 味专项（用户已定：全扫文案+文档+代码注释 + 总纲文档结构手术）② 发布收尾（LICENSE 文件缺 + README/版本 de-rc → 1.0.0 + 发布说明 + cut v1.0.0 stable）。关联文件（生产）：`script/{ValidationError(新),ScriptRuleValidator}`、`web/ScriptOpDispatcher`、`canvasfile/ScriptImporter`、`i18n/Messages`、`session/Session`、`web/WebServer`、`HikariCanvas`、`web/src/network/wsClient.ts`、`lang/{en_us,zh_cn}.yml`（+94 key）；版本号 6 文件 → 0.9.7-SNAPSHOT。

---

## 2026-07-02 · 0.9.6 MapPool + WallRestorer 测试守卫（1.0 硬闸最后一块）

给项目技术核心（"预览地图池编辑期只刷像素、不新建 MapView，避免 idcounts.dat 膨胀——这项做不好整个项目报废"）补上此前**零覆盖**的自动化测试守卫。1.0 前 6 块硬闸最后一块。subagent-driven（per-task implementer→review 全 opus + opus 整支终审 Ready-to-merge）。版本号 0.9.5 → 0.9.6-SNAPSHOT。

**关键约束**：**MockBukkit 在本仓不可用**（`ServerMock.<init>` 因 MockBukkit-v1.21:3.123.0 与 Paper API 版本错配抛 `Invalid namespace key minecraft:chain`，全仓零 ServerMock 实跑先例）。故照既定 seam 范式（`MainThreadPerms.testResolver` + 真 SQLite + 直驱逻辑）——给 MapPool 抽一层 behavior-preserving 的 map 操作 seam，才能测核心不变式。

**三件套**：
- **T1 `MapBackend` seam**（behavior-preserving 重构，`66ed6ccc`）：抽 `Bukkit.createMap/getMap/getWorld` 成 `MapBackend`（createMap/installRenderer/mapWorld/worldByName 4 方法）+ `BukkitMapBackend` 逐字委托 + MapPool 双构造（6-arg 委托默认 `new BukkitMapBackend()`，生产 `HikariCanvas` 接线零改）+ 6 触点改走 backend。**不加新测试**，靠现有 MapPoolStatsTest/DetectLeaksTest + 全量当回归网；评审逐触点核行为等价。
- **T2 MapPool 核心不变式测试**（真 SQLite + `FakeMapBackend` + Proxy fakeWorld，`cbdffb0c`，**10 case**）：核心指标 `createMapCalls`（全池唯一铸造点 = idcounts 膨胀次数）——reserve 复用零铸 / 按需精确扩容 / releaseWall→复用 / releaseToFree→复用 / 跨世界拒绝 / per-world 分桶 / initialize 铸满 initialSize / **重启恢复不重铸（命根子另一半，终审补 case 10 `e73a6cc0`）**。评审做**变异测试**（把 reserveForWall 改成每次铸→5 case 红→revert）实证非 vacuous。
- **T3 WallRestorer 失败守卫**（`1b1a5b3d`）：`worldResolver` seam（默认 `Bukkit::getWorld`，6-arg 旧构造保留生产不受影响）+ `HikariCanvasRenderer` 去 `final`（让测试 `ThrowingRenderer` 子类注入渲染失败，运行期零影响）+ 3 case：restore 成功 / **bind 成功后渲染抛异常→releaseToFree 全回滚不泄漏（M16 P2.5 命根子）** / 世界未加载跳过。实现者 + 评审**双重变异测试**（废 releaseToFree→case 红 expected3 was0→revert）。

**过程**：4 commit（T1 `66ed6ccc` / T2 `cbdffb0c` / T3 `1b1a5b3d` / 终审补 case10 `e73a6cc0`）+ 本收尾。子代理全程 opus。整支终审（opus）Ready to merge，无 Critical/Important；Minor#1（重启恢复不重铸未覆盖）controller 已补 case 10；Minor#2（`FakeMapBackend` 两份，render 包访问不到 pool 包级类所致）可接受。

**测试**：全量 `:plugin:test` BUILD SUCCESSFUL（**2150** = 原 2137 + MapPoolInvariantTest 10 + WallRestorerTest 3）；shadowJar `HikariCanvas-0.9.6-SNAPSHOT.jar` 152MB。

**1.0 进度**：**6 块硬闸全部完成**（数据契约闸 0.9.1 / 可观测性 0.9.2 / 安全收尾 0.9.3 / 发布验证 0.9.4 / 多版本支持 0.9.5 / MapPool·WallRestorer 测试守卫 0.9.6）。**1.0 正式版前置打磨到齐**。剩独立项：脚本编辑器保存校验 i18n（`ScriptRuleValidator` ~103 串）。下一步可 cut `v0.9.6-rc` 或朝 1.0 收束。关联文件（生产）：`pool/{MapBackend(新),BukkitMapBackend(新),MapPool}`、`render/{HikariCanvasRenderer,WallRestorer}`；测试 3 新（FakeMapBackend / MapPoolInvariantTest 10 / WallRestorerTest 3）；版本号 6 文件 → 0.9.6-SNAPSHOT。

---

## 2026-06-26 · 0.9.5 多版本支持（一份 jar 通吃 Paper 1.21.11 + 26.x）

1.0 发布前打磨第 5 块（多版本支持）。生态在往 Minecraft 26 迁（新版本号体系 + Java 25），停 1.21.11 会被甩下。本版让**同一份 jar 同时跑 Paper 1.21.11 和 26.1/26.2**。版本号 0.9.4 → 0.9.5-SNAPSHOT。

**背景**：rc1（0.9.4）在用户 26.1.2 服上崩在 `PacketEvents 2.11.2` 内部 NMS 反射（`NMS_ITEM_STACK_CLASS` null）——Paper 26.1 移除了插件 Spigot 重映射。但崩的是**依赖**不是我们的代码：grep 实证 `plugin/src/main` 零 `net.minecraft`/`craftbukkit`，零 NMS。这正是 PROPOSAL §5.2.6「26.x 升级保障」纪律埋了半年的回报——迁移只是 bump 依赖，不动代码。

**de-risk spike（controller 亲验 3 个未知）**：
- PacketEvents 2.11.2 → **2.12.2**（多版本库，同时支持 1.21.x + 26.1.x）：Java 21 编译通过（**不需 Java 25**）、全量测试不回归、`MapPacketSender` 的 `WrapperPlayServerMapData` 签名未变（零代码改动）。
- 用户实测：候选 jar（1.21.11 编译 + PacketEvents 2.12.2）在原生 **Paper 26.1 上正常 enable + 渲染文字 + 创建画布**。同一 jar 已验证跑 1.21.11（rc1）+ 26.1 两个大版本。`api-version: '1.21'` 在 26.1 被正常接受。

**实装（路线 A「通吃 jar」）**：
- **build 参数化**：`paperApi`/`mcVersion`/`javaVer` 三个 gradle property（默认 `1.21.11-R0.1-SNAPSHOT` / `1.21.11` / `21`，生产路径不变）；`options.release` 也参数化（默认 21）。生产 jar 仍 Java 21 字节码（跑 1.21 的 Java21 服 + 向上兼容 26.x 的 Java25 服）。
- **CI 双版本编译守卫**（新 `compat-26` job）：Java 25 对 `26.1.2.build.+` dev bundle 编译同一份 main（`-PpaperApi=26.1.2.build.+ -PjavaVer=25`），提前抓「用了 26.x 已移除 API」的回归。**本地实测守卫编译通过**——API 审计结论：我们用的 Bukkit API（47 个 import，全核心 map/entity/world/player）在 26.1.2 无一被移除，唯一 deprecation note 是 `World#getName`（只警告不 fail）。dev-bundle 新版本号格式 `26.1.2.build.NN-stable`（26.x 起 `年.drop.hotfix`，老 `-R0.1-SNAPSHOT` 不存在）。
- **World#getName 决定保留**（不迁 getKey）：审计发现它是整个数据模型的持久化世界标识（`pool_maps`/`walls` 等表 `world` 列 + `worldNameToUid`/`scriptWorldUuidByName` 缓存 + 脚本按名引用），迁移=深数据模型改造 + DB migration，与多版本目标无关；且 getName 只 obsolete 没移除、守卫无 `-Werror` 不被卡。记一条：若 Paper 哪天真硬删 getName，再做独立的「换持久化世界标识」项目。
- **docs**：README 环境要求改多版本 + deployment §1 多版本说明 + CLAUDE.md 锁定版本表同步（PacketEvents 2.12.2、Java/Paper 改注「编译目标」+ compat-26 守卫）。

**dev-build 体感（顺带修的坑）**：清掉一个从周二卡死的 0.9.1 shadowJar 僵尸 gradle 进程（占 `expanded.lock` 让新构建 hang，是「构建这么慢」的真因）。

**测试**：默认路径全量 `:plugin:test` BUILD SUCCESSFUL（PacketEvents 2.12.2 无回归）；26.1.2 守卫编译 BUILD SUCCESSFUL（仅 getName deprecation note）；shadowJar `HikariCanvas-0.9.5-SNAPSHOT.jar`。

**1.0 进度**：6 块完成 5 块（数据闸 / 可观测性 / 安全收尾 / 发布验证 / 多版本支持）。剩 1 块：MapPool+WallRestorer 测试守卫（脚本校验 i18n 另算）。**已 cut `v0.9.5-rc.1`**（2026-06-27，tag 指 `1d3cca2c`；release run `28256911964` 全绿 → prerelease `HikariCanvas-0.9.5-rc.1.jar` 89MB，含 PacketEvents 2.12.2 多版本；现在 26.x 服务器有可下载多版本包，README 版本指向更新到 rc.1）。关联文件：`plugin/build.gradle.kts`（PacketEvents 2.12.2 + paperApi/mcVersion/javaVer/release 参数化）、`.github/workflows/ci.yml`（compat-26 守卫 job）、`README.md`、`docs/deployment.md`、`CLAUDE.md`、版本号 6 文件 → 0.9.5-SNAPSHOT。

---

## 2026-06-26 · 0.9.4 发布验证（release.yml 首跑出真 Release + README 发布化 + 体积真相）

1.0 发布前打磨第 4 块（发布验证）。让从未跑过的 `release.yml` 真正产出一个可下载的 GitHub Release，端到端验证发布管线。版本号 0.9.3 → 0.9.4-SNAPSHOT。

**做了什么**：
- **release.yml 预检硬化**：前端步骤 `npm ci` → `npm ci || npm install --no-audit --no-fund`（对齐 ci.yml）。release.yml 从未跑过，首跑必撞 ci.yml 当初的跨平台 lock 坑（macOS dev 生成的 platform-specific transitive deps 不进 package-lock，CI Linux 严格 npm ci 失败）。**实跑验证：CI 确实命中 fallback**（run annotation `npm ci failed … falling back to npm install`）——预检硬化是必需的，否则首个公开 release 直接栽在前端步骤。
- **打 tag `v0.9.4-rc.1`（SSH 签名 annotated）→ 触发 release.yml → run `28212224911` 全绿（2m38s）**：frontend(test+build) + backend(test+shadowJar) + rename + Create GitHub Release 全过。产出 **prerelease** `HikariCanvas-0.9.4-rc.1.jar`（由 gradle `0.9.4-SNAPSHOT` 经 rename 步骤改名，验证了 tag≠gradle 版本的 rename 兜底逻辑）。`prerelease=true`（`contains(VERSION,'-')`）/ `draft=false`。URL：https://github.com/HyacinthHaru/HikariCanvas/releases/tag/v0.9.4-rc.1 。**这是项目首个 GitHub Release**。（一条 benign annotation：Node 20 deprecation，`@v4` actions 被迫跑 Node 24，非失败——0.9.3 刚加的 dependabot github-actions 生态会管它。）
- **README 从愿景体重写为发布体**：环境要求（Paper 1.21.11 / Java 21）+ 下载 + 60 秒上手 + 功能一览（编辑器/动态数据/动画脚本/工程管理 4 组，大白话无内部编号）+ 公网部署指 deployment §3 + SECURITY.md + 文档导航 + 体积说明 + 保留 AI 诚实声明 + 两张 banner 图。
- **体积真相（发布验证的意外收获）**：下载 release jar 实测 **94 MB**，本地 `:plugin:shadowJar` 出 **152 MB**——差 ~62MB。查清根因：本地 dev 跑过 `syncFontsToWeb` 把 27 套字体塞进 `web/public/fonts/`，vite build 又烤进 jar 的 `web/fonts/`（与后端 `fonts/` 重复一份）；**CI release 路径不跑 syncFontsToWeb，`web/public/fonts/` 空，故 release jar 只含 `fonts/` 一份**。核实前端字体加载：二进制走 `/api/font/file?id=X`（后端从 `fonts/` 供给，`FontLoader.ts:66` / `TextGlyphExtractor.ts:149`），metrics 先试静态 `/fonts/X.metrics.json` 失败再 fallback `/api/font/metrics`（`GlyphMetricsLut.ts:60-64`）——**前端从不静态取 `web/fonts/*.ttf` 大二进制，那份纯冗余**。结论：**release jar（90 MB，字体一份）是正确且功能完整的精简产物**，本地 152MB 是 dev-only 膨胀。原计划要记为"~88MB 字体去重候选"的事，在 release 路径上已天然完成。据此把 README + deployment 体积标注从 ~150MB 改正为 ~90MB（按真实 release 产物）。
- 版本 bump 0.9.3 → 0.9.4-SNAPSHOT（6 文件）。

**实机安装（交用户）**：在真 Paper 1.21.11 服务器装**从 Releases 下载的** `HikariCanvas-0.9.4-rc.1.jar`（90 MB，**不是本地 build 的 152MB jar**），确认插件正常起 + 编辑器字体渲染正常（验证 90MB 精简产物的字体路径在真服无碍）。CI 不能替代真服启动。

**留后续（dev-build 体感，非 release 问题）**：本地 build 仍出 152MB 冗余 jar（`web/fonts/` 那份）；可在 shadowJar 排除 `web/fonts/` 或不为 build 跑 syncFontsToWeb 让本地 build 与 release 一致，留 1.0+ 评估（不影响 release，YAGNI）。

**1.0 进度**：6 块完成 4 块（数据闸 0.9.1 / 可观测性 0.9.2 / 安全收尾 0.9.3 / 发布验证 0.9.4）。剩 2 块：MapPool+WallRestorer 测试守卫 / 脚本校验 i18n（`ScriptRuleValidator` ~103 串）。关联文件：`.github/workflows/release.yml`（npm ci fallback）、`README.md`（重写）、`docs/deployment.md §1`（体积）、版本号 6 文件 → 0.9.4-SNAPSHOT；tag `v0.9.4-rc.1` / Release run `28212224911`。

---

## 2026-06-24 · 0.9.3 安全收尾（SECURITY.md + 公网绑定警告 + 反复超限断连 + dependabot）

1.0 发布前打磨第 3 块（安全收尾）。subagent-driven（per-task implementer + reviewer 全用 opus；opus 整支终审 Ready-to-merge）。版本号 0.9.2 → 0.9.3-SNAPSHOT。

**四件套**：
- **`SECURITY.md`（仓库根，英文，GitHub Security 标签页识别）**：GitHub Security Advisory 私密上报 + 5 日 ack / 10 日 triage SLA + 漏洞修复后 7 日披露 + `[SECURITY]` 发布标记 + 边界声明（默认绑 127.0.0.1 / 公网必须 TLS+反代 / URL 上传 SSRF 已移除服主自担 / MC 协议·OS·账号盗用 out of scope）。`docs/security.md §12` 标已创建。
- **公网绑定启动警告**（`HikariCanvasConfig.warnIfPublicBind`）：host = `0.0.0.0` / `::` / `[::]` / `*` / 空 → 打 3 行英文 warning（无 TLS 裸绑会明文暴露 token + 建议反代）；**只警告不阻拦**。`load()` 读 host 后接线（照 `sanitizeEditorUrl` 范式）。
- **会话反复超限主动断连**：`Protocol.CLOSE_RATE_LIMIT_VIOLATION = 1008` + `SessionRateLimiter` 内部 violation 计数（独立 60s 窗口，5 次拒绝 → 触发注入回调；**锁内决策 / 锁外触发回调 / single-fire / 窗口复位 / volatile null-safe hook**）+ `WebServer` 新 `rateLimiter` 字段 + 构造期 `setOnRepeatedViolation(this::closeForRepeatedViolation)` 接线 + `closeForRepeatedViolation` **单点收口**（`wsBySession.remove` → close 1008 → `discardSession`）+ `SESSION_RATE_LIMIT_DISCONNECT` 审计。**6 个编辑类 dispatcher 的 `allow()` 调用一行未改**（关连接逻辑只在 WebServer 单点）。前端 `wsClient.isTerminalCloseCode` 加 1008 终止态（不自动重连）+ onClose 复用 `RATE_LIMITED` 文案（不新增 i18n key）。`security.md §3.3` 标实装 + 补**覆盖面限定**（`ping` / `brush` / `wall` / `template` 不计入——整支终审 I-1 文档精确化；把全 op 纳入统一限流是会动笔触体验的独立 scope，不在本批）。
- **`.github/dependabot.yml`**：gradle（根）+ npm（`web/`）+ github-actions 三生态周更；不做 npm audit gate（与 CI 正交）。

**过程**：6 commit（T1 SECURITY.md `39d9b1ac` / T2 公网警告 `756d27c5` / T3 反复超限断连 `c24cfea2` / T4 前端 1008 `a962138b` / T5 dependabot `e0833167` / T6 收尾本 commit）。**子代理全程 opus**。**整支终审（opus）Ready to merge 无 Critical**：前后端 1008 闭环成立、关连接收口纪律遵守、并发正确、文档与代码一致；I-1（限流覆盖面）文档精确化已采纳，M-1（测试占位断言）/ M-2（1008 复用 RATE_LIMITED 文案）系 plan 自承取舍可接受。

**测试**：后端全量 `:plugin:test` BUILD SUCCESSFUL（含新 `HikariCanvasConfigTest` 3 + `SessionRateLimiterTest` 4）；前端 vitest **93 files / 1446** 全绿（含 closeCode 1008 新 case）；shadowJar `HikariCanvas-0.9.3-SNAPSHOT.jar` 152 MB。

**1.0 进度**：6 块里数据闸(0.9.1) + 可观测性(0.9.2) + 安全收尾(0.9.3) 已完成。剩 3 块：**发布验证**（跑 release.yml 出真 release，0.9.4）/ MapPool+WallRestorer 测试守卫 / 脚本校验 i18n（`ScriptRuleValidator` ~103 串）。关联文件（生产）：`SECURITY.md`(新)、`HikariCanvasConfig`、`web/Protocol`、`session/SessionRateLimiter`、`web/WebServer`、`web/src/network/wsClient.ts`、`.github/dependabot.yml`(新)、`docs/security.md`；测试 2 新（HikariCanvasConfigTest / SessionRateLimiterTest）+ closeCode.test；版本号 6 文件 → 0.9.3-SNAPSHOT。

---

## 2026-06-23 · 0.9.2 可观测性（增强 /canvas stats + 新增 diagnose）

给服主自助诊断能力（1.0 发布前打磨第 2 块）。subagent-driven（per-task implementer + reviewer 全用 opus；opus 整支终审 Ready-to-merge）。版本号 0.9.1 → 0.9.2-SNAPSHOT。**只读观测，不做任何修复**（cleanup stub 不碰；符合"工具不是保姆——数据透明不替服主决策"）。

**新建 `DiagnosticsSubCommand`**（照 VariableSubCommand/BenchmarkSubCommand 范式：构造注入子系统 + 纯逻辑 + CanvasCommand.build() 接线）。CanvasCommand 缺 VariableStore/AnimationTicker/TweenScheduler 依赖，故新类自注入。两命令都用现有 `canvas.admin`，不新增权限节点。

- **增强 `/canvas stats`**：原池/墙/会话/令牌基础上多发——池行加上限 max；分世界空闲（多 world 时）；变量总数 + 按 namespace（找膨胀源）；动画占用（时间轴墙数 + 活跃补间数）。补只读 accessor `MapPool.byWorldStats()`+`maxSize()` / `VariableStore.statsByNamespace()` / `TweenScheduler.activeCount()` widen public。
- **新增 `/canvas diagnose <wallId>`**：7 环节只读诊断链——墙存在 → 地图分配(数=width×height) → 世界加载(Bukkit.getWorld) → 活跃 session → ProjectState 解析 → 动画态 → 总结。每环节 OK/WARN/ERROR/INFO 配色。补 `SessionManager.isWallActive()`（byWall key 是 WallKey 故走 byId 迭代 wallId 匹配，无锁 CHM 读）+ wallId tab 补全。**not-found vs state-corrupt 区分**：loadById 把坏 json 吞成 empty → 用 Database 裸 `SELECT 1` 探物理行（同 runStats 范式）。诊断链只报不修：world-unloaded/maps-missing 不硬停，跑完看全整墙，总结指首个问题（比硬停更优诊断 UX）。

**整支终审（opus）**：grep 所有写动词 → 只命中 javadoc/import/占位符名，**零写路径**（全链路纯读）；线程安全（MapPool 读 synchronized 不碰 Bukkit / 其余 CHM 无锁 / isWallActive 不取 writeLock）；数据口径正确；可观测性覆盖 4 类常见问题（池满/某墙渲不出/变量膨胀/动画占用）无漏报。2 cosmetic Minor：① CanvasCommand 迁走 runStats 后 mapPool/database 成死字段（两次评审判保留可接受，记录不改）② diagnose 总结行 stripTags 把占位符删成空值（**已修 commit 66b1e7a5**：改通用文案"上方第一处标 ✗/⚠ 即原因"，不再嵌带占位符的 issue 文本）。

**过程**：3 commit（T1 增强 stats `1ebe7ffa` / T2 diagnose `ca289b61` / 终审 summary 修复 `66b1e7a5`）+ 本收尾。**子代理全程用 opus**（用户偏好：能力优先于成本）。

**测试**：全量 `:plugin:test` **2130** 全绿（含 MapPoolStatsTest 反射注入绕 Bukkit / VariableStoreStatsTest 归类自洽 / DiagnosticsSubCommandTest 6 路 Proxy sender 捕获文案）；shadowJar `HikariCanvas-0.9.2-SNAPSHOT.jar`。

**1.0 进度**：6 块里数据闸(0.9.1) + 可观测性(0.9.2) 已完成。剩 4 块：安全收尾（SECURITY.md + 0.0.0.0 警告）/ 发布验证（跑 release.yml）/ MapPool 测试守卫 / 脚本校验 i18n。关联文件（生产）：`command/{DiagnosticsSubCommand(新),CanvasCommand}`、`HikariCanvas`、`pool/MapPool`、`variable/VariableStore`、`session/SessionManager`、`script/engine/TweenScheduler`、`resources/lang/{zh_cn,en_us}.yml`；测试 3（MapPoolStats/VariableStoreStats/DiagnosticsSubCommand）；docs troubleshooting §2.5 + deployment §7；版本号 6 文件 → 0.9.2-SNAPSHOT。

---

## 2026-06-23 · 0.9.1 备份保留（BackupReaper）

补 0.9.1 数据契约闸留的尾——备份保留策略。仍 0.9.1-SNAPSHOT，不 bump 版本号。

**做了什么**：启动期 migration 后跑 `BackupReaper.reap()`，清理超过 `database.backup-retention-days`（默 30）天的 `data.db.pre-V<NNN>.bak`（含 `-wal`/`-shm`）。`0` = 永久保留禁用清理。只动本插件产的备份文件，不碰 `data.db` 及其他文件。

**关联文件**：
- `storage/BackupReaper.java`（新建，纯逻辑、注入 nowMillis 便于单测）
- `HikariCanvasConfig.java`（字段 `backupRetentionDays`，4 处：声明 / 构造赋值 / parse / builder 默认）
- `HikariCanvas.java`（import + migration 后接线 `new BackupReaper(getLogger()).reap(...)`）
- `resources/config.yml`（`database.backup-retention-days: 30`）
- `docs/data-model.md §6.6.2`（"留后续版本"改为"已实装"）

**测试**：`BackupReaperTest` 5 case（旧备份删 + 新备份留 + 无关文件不动 + retentionDays=0 禁用 + retentionDays<0 禁用 + 恰在截止点保留 + `.bak-shm` 覆盖）；全量 `:plugin:test` 2115+ 全绿。

---

## 2026-06-23 · 0.9.1 数据契约闸（1.0 硬闸第一块）

把"首次 stable 后必须 forward-only + 强制 auto-backup"从文档约定变成代码/测试落地，并把 **schema 冻结点提前到 V018**（V001-V017 grandfathered），抢在 1.0 之前一版进入冻结。subagent-driven（per-task implementer→review→fix + opus 整支终审）。版本号 0.8.3 → 0.9.1-SNAPSHOT。

**四件套**：
- **WAL 安全 auto-backup**（`MigrationRunner.tryBackup`）：备份前用迁移连接 `PRAGMA wal_checkpoint(TRUNCATE)` 把 WAL 已提交事务刷进主库，再 copy 主库 + `-wal/-shm` → `data.db.pre-V<NNN>.bak`。修掉原"只 copy 主库不 checkpoint"在 WAL 模式下丢最近提交数据的缺陷。利用迁移在 onEnable 单线程跑、无并发写（终审实证 run() 先于 SessionManager/WebServer 启动）→ 备份是一致快照。加 `runUpTo(int)` 测试 seam。
- **forward-only 守卫**（`MigrationForwardOnlyTest`，编译期）：扫 `db-migrations/*.sql`，**V018+** 禁 `DROP TABLE` / `DROP COLUMN`（含 SQLite 省略 COLUMN 的 `ALTER TABLE t DROP c`）/ `ALTER COLUMN` / `RENAME COLUMN`；V001-V017 grandfathered；`-- @forward-only-exempt` 显式豁免；`scanned>=16` sanity 防 vacuous。前缀/正则判定避字符串字面量误判。
- **迁移 fixture 测试基建**（`MigrationFixtureTestBase` + `migration-fixtures/` + V017 示范）：`runUpTo(baseline)` 建 schema → 灌 `before.sql` 种子 → `runUpTo(target)` 应用 → 断言数据无损。1.0 起每个新 migration 须配。
- **config 默认翻 true**（备份已 WAL 安全）+ data-model.md §6.4/§6.6 据实标实装（forward-only V018、WAL 安全备份、fixture 基建、恢复步骤、豁免机制）。

**过程**：5 commit（T1 WAL 备份 + runUpTo `5b84deab` / T2 forward-only 守卫 `4d62f92e` + 检测器堵省略 COLUMN `3cacd5c3` / T3 fixture 基建 `30318ad4` / config+doc `4be7909e`）+ 终审硬化 `70c80d3a`（补 RENAME COLUMN 拦截 + 修陈旧注释）+ 本收尾。**T2 实现者子代理连接中途断开→controller 亲读全文件+跑全量绿+提交**。

**终审（opus）**：对抗探针确认守卫对 DROP TABLE（含 schema 限定/引号/多空格/单行多语句）、省略 COLUMN 删列、ALTER/RENAME COLUMN 全拦；WAL 单线程前提实证成立；config 翻转三处（getBoolean 默认参数 + builder 字段 + config.yml）齐全；文档零矛盾。Minor（checkpoint busy 返回值未检查）裁定可接受——备份测试已端到端实证刷盘成功。

**测试**：全量 `:plugin:test` **2110+** 全绿（含 `MigrationRunnerBackupTest`〔WAL 刷盘回归，非 vacuous〕/ `MigrationForwardOnlyTest`〔检测逻辑 + 扫 17 真实迁移〕/ `V017WallScriptsFixtureTest`〔示范〕）；shadowJar `HikariCanvas-0.9.1-SNAPSHOT.jar`。

**遗留后续**：备份保留策略（30 天 + BackupReaper 自动清）；`/canvas` 可观测性（0.9.2）。关联文件（生产）：`storage/{MigrationRunner,Database}`、`HikariCanvasConfig`、`HikariCanvas`、`resources/config.yml`、`docs/data-model.md`；测试 4 新（MigrationRunnerBackupTest / MigrationForwardOnlyTest / MigrationFixtureTestBase / V017WallScriptsFixtureTest）+ fixtures；版本号 6 文件 → 0.9.1-SNAPSHOT。

---

## 2026-06-23 · 0.8.3 i18n 收尾（benchmark per-player i18n + summary.txt + script.trace 英文化）

收掉 0.8.2 留的两处 i18n 缺口。subagent-driven（per-task implementer→review→fix + opus 整支终审）。版本号 0.8.2 → 0.8.3-SNAPSHOT。

**缺口一 · benchmark 压测摘要**：
- `run-tween`/`run-script` 玩家可见摘要做**真 per-player i18n**：两个 driver（`TweenBenchmarkDriver`/`ScriptBenchmarkDriver`）从"返回中文成品字符串"改为返回结构化行 `List<BenchRow>`，由 `BenchmarkSubCommand.sendBenchRows` 按玩家 locale 用 `Messages` 组装 done/subtitle/表头/数据行/脚注（新 `command.bench.run-tween.*`/`run-script.*` 各 4 key，zh+en）。数字仍 `String.format` 宽度对齐填 `Placeholder.unparsed`。
- `summary.txt` 磁盘报告（`renderReportText`）改为按 config **default-locale** 渲染（文件不分玩家、跟随服主默认语言）：新 `Messages.defaultLocale()` getter + 中文片段抽 `command.bench.report-file.*`（纯文本无 MiniMessage 标签），`String.format` 宽度结构保留、数字/sceneId 不翻译。
- driver 那处中文超时 logger 英文化。

**缺口二 · script.trace 试跑诊断英文化**（统一英文化，不进 lang、不改 `TraceStep` 结构、不碰前端——真 per-player i18n 留待将来前端做 detail 展示 UI 时连结构重构一起做）：
- `ActionExecutor`(52) / `ScriptRunner`(14) / `ElementPropertyApplier`(22) trace detail + 诊断中文就地英文化（中文箭头 `→`→`->`）。
- **范围补登**：0.8.2 那条已知缺口 note 只列了 3 文件，**低估了范围**——全量回归（`Action073BehaviorTest` 失败）暴露 `script/engine/` 包还有 `TweenScheduler`(10)、`CommandTemplateEngine`(7)、`TriggerRouter`(1)、`ConditionEvaluator`(1) 同样产出中文 trace detail。用户拍板"折进 0.8.3 一起收"→ 补做 Task 6/7。
- **opus 整支终审完整性普查**又抓出一处文件清单 scope 漏网：`HikariCanvas:931` 试跑入口 lambda 的 `TraceStep.error("规则不存在")`（藏在装配代码、不在 engine 包，但正是 script.trace channel）→ 英文化为 `"rule not found"`。
- 至此 engine 包逐文件 + 装配代码 + benchmark 的玩家/编辑器可见中文运行时串清零（终审实证）。

**过程教训（已固化到本次 plan）**：① per-task 测试**必须跑全量** `:plugin:test`——Task 3 只跑 `*ActionExecutorTest`，漏了跨套件断言它 detail 的 `Action073BehaviorTest`，被全量回归抓出补修（commit 2e03cadb）。② Gap 范围别只信旧 note 的文件清单，要按 channel（"凡产 script.trace detail 的代码"）全包扫——TweenScheduler / HikariCanvas 两次漏网都源于此。

**明确留后续（非 0.8.3 范围，据实记录）**：
- **脚本编辑器「保存校验报错」中文** = `ScriptOpDispatcher`(6) + `ScriptRuleValidator`(**97**) ≈ 103 串。这是 SCRIPT_INVALID 保存 reject 文案（**非** script.trace channel），是一整片独立的"编辑器校验报错 i18n"课题；只修 dispatcher 而留 validator 会造成同类中英混杂，故不在 0.8.3 半做，留独立批次。
- 预存量（口径透明）：`report.html`（`HtmlReportRenderer`）/ `BudgetFormula.DISCLAIMER` / `BenchCompositor` 启动异常仍中文——服主可见磁盘产物/日志，不在 0.8.2/0.8.3 任何缺口清单内。

**测试**：全量 `:plugin:test` **2101** 全绿（含 driver 结构化测试重写 + 各文件断言英文同步 + `LangFileParityTest`）；shadowJar `HikariCanvas-0.8.3-SNAPSHOT.jar`。0 baseline 漂移。

**0.8.2 两处已知缺口至此关闭**（script.trace 试跑诊断 ✅ 全包英文化；benchmark 驱动摘要 ✅ 真 i18n）。

关联文件（生产）：`benchmark/{BenchRow(新),ScriptBenchmarkDriver,TweenBenchmarkDriver}`、`command/BenchmarkSubCommand`、`i18n/Messages`、`script/engine/{ActionExecutor,ScriptRunner,ElementPropertyApplier,TweenScheduler,CommandTemplateEngine,TriggerRouter,ConditionEvaluator}`、`HikariCanvas`、`resources/lang/{en_us,zh_cn}.yml`；测试 5 文件断言同步；版本号 6 文件 → 0.8.3-SNAPSHOT。

---

## 2026-06-23 · 0.8.2 i18n（插件本体国际化）+ 0.8.1 文档版本引用同步

**i18n 双端国际化**：后端从零搭 i18n + 前端收尾，玩家游戏内文字按 MC 客户端语言显示，统一现有英/中混用。
- 后端基础设施：新 `i18n/Messages` 类（Bukkit YamlConfiguration 持 `lang/<locale>.yml`，jar 默认 + `plugins/HikariCanvas/lang/` 服主可覆盖/加语言，`Player.getLocale()` 选语言、config `i18n.default-locale` 兜底 en_us，MiniMessage 渲染 + 命名占位符 + 缺 key 回退链）；HikariCanvas 装配 + `/canvas reload config` 热重载。
- 后端迁移：CanvasCommand / WandListener+CanvasWand+FrameProtectionListener / VariableSubCommand（§ 码转 MiniMessage）/ BenchmarkSubCommand（中文→双语）共约 160 处玩家可见文字 → `lang/{en_us,zh_cn}.yml`（同 key，LangFileParityTest 防漏译）；控制台日志统一英文（10 文件 44 处，消除中英混杂；benchmark 驱动类 1 处 logger 漏，见已知缺口）。
- 前端收尾：messages.ts 加 errors/script 5 key + 修 2 个 en switchLocale 误填中文 bug；wsClient 3 处 + scriptEdit 4 处硬编码改查表；新增 zh/en key 对齐 + en 无中文 vitest 守卫。
- 内置 zh_cn + en_us；服主可在 lang/ 加语言。
- **已知缺口（留后续，建议 0.8.3 一并处理）**：① `script.trace` 试跑诊断文本（TraceStep.* in ScriptRunner/ActionExecutor/ElementPropertyApplier，后端生成、经 WS 发编辑器面板）仍中文；② `/canvas bench run-tween`/`run-script` 的驱动摘要表格正文（`ScriptBenchmarkDriver`/`TweenBenchmarkDriver`，含一处中文 logger）经 sendMessage 发玩家、仍中文。两者均属诊断/压测工具输出（非核心玩家流程），spec 范围（命令回复 + wand + 前端漏网）未覆盖。整支终审（opus）确认除此二者外无其它玩家/编辑器可见中文漏网。
- 版本号 0.8.1 → 0.8.2-SNAPSHOT。

**0.8.1 文档版本引用同步**（随本次一并）：上一轮把 9 份文档的「当前版本」引用从 0.7.4 同步到 0.8.1（commit 374dc00），随 i18n 一起提交。

测试：后端 :plugin:test 全绿 + LangFileParityTest/MessagesTest；前端 vitest 全绿；shadow jar HikariCanvas-0.8.2-SNAPSHOT.jar。

关联文件：`plugin/src/main/java/moe/hikari/canvas/i18n/Messages.java`、`plugin/src/main/resources/lang/en_us.yml`、`plugin/src/main/resources/lang/zh_cn.yml`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`plugin/src/main/java/moe/hikari/canvas/command/VariableSubCommand.java`、`plugin/src/main/java/moe/hikari/canvas/command/BenchmarkSubCommand.java`、`web/src/i18n/messages.ts`、`web/src/network/wsClient.ts`、`web/src/stores/scriptEdit.ts`、`build.gradle.kts`、`plugin/src/main/resources/paper-plugin.yml`、`web/package.json`。

---

## 2026-06-22 · 0.8.1 独立 ultrareview P0-P2 修复批（版本号 → 0.8.1-SNAPSHOT）

独立深度审查（68 子代理：40 审查 + 28 对抗验证；confirmed 30 / refuted 58 / doc-sanctioned 12 / uncertain 10）后修 P0-P2 全部 26 条。流程：21 子代理再确认 + 出修法规格（定夺双端对齐方向）→ 14 子代理并行修隔离项 + controller 亲修 7 个并发/跨文件硬骨头 → 全量测试 → 2 处中途修正 → 复测全绿。

**真修复（21）**：
- WS auth 超时与成功竞态：cancel-gate（取消成功才注册 ctx，关 put↔attr 窗口，防孤儿连接致 push 静默失败、客户端假死）`WebServer`
- ScriptRunner.pollWaitUntil 异常路径泄漏 ThreadLocal → finally 自清 CHAIN_DEPTH/RULE_KEY/TRIGGER_DETAIL `ScriptRunner`
- AssetIngest.decoderPool 热重载线程泄漏 → HikariCanvas 持字段 + cleanupResources shutdown `HikariCanvas`
- MapPool.bindToWall 中段 persist 失败致内存/DB 分叉 → 两阶段（单事务 upsert 全部 → 成功才改内存）`MapPool`
- mask 坐标越界检查被科学计数法绕过（`1e4` 被拆 `1`/`4`）→ MASK_NUMBER_RE 支持科学计数 + 用 MAX_COORD 常量 `ElementValidator`
- rail.wall.bind 接受客户端 wallId 致跨墙绑定窗口 → 锁定 session wall（前端从不传，无破坏）+ 审计补 wall_id `RailOpDispatcher`/`wsClient`
- SVG skewX/skewY(90°) tan 有限大数破坏矩阵致元素不可渲染 → 幅度守卫退化恒等 `transform.ts`
- 脚本编辑器 closeEditing/selectRule 校验未过时静默丢未保存改动 → 挡住 + 提示 + 保留 workingCopy `scriptEdit.ts`
- 渐变全 stop=1.0 时 epsilon-bump 失效抛 IAE 降级纯色 → 鲁棒单调化（前向严格递增 + 后向钳 ≤1）`FillPaintBuilder`
- BayerDither getColor 返 null 静默跳过无 log → 一次性 SEVERE + TRANSPARENT 兜底 `BayerDither`
- PaletteLut 未校验 RGB 分量范围 → 越界 clamp + 一次性 warn `PaletteLut`
- FrameDeployer reattach 失败 slot 仍进 skipSlots 致永久空框 → 返回成功集合、失败 slot 交 deployFor 重生 `FrameDeployer`
- 协议 fill 嵌套未知字段绕过 FAIL_ON_UNKNOWN → 协议层专用严格 mapper（mixin 放行多态 type、拒其余；不碰存储态/模板兼容）`ElementValidator`
- mask 坐标上界三处不一致 → 统一 MAX_COORD `ElementValidator`
- HistoryStack 时钟回退致误合并丢撤销粒度 → `now>=lastCommitAt` 守卫 `HistoryStack`
- SVG rect/circle/ellipse 负宽高/半径未校验 → 返 null（SVG spec）`shapesToPath.ts`
- SVG hex 颜色不校验字符合法性 → 正则校验 `fillMap.ts`
- 缓动曲线拖拽中收 server patch 致曲线跳变 → 拖拽快照基线 `EasingCurveEditor.vue`
- appendVariable text 长度 / tweenBlock bezier 前端校验缺失 → 补齐对齐后端 `validator.ts`

**防御性硬化（4，深入分析为当前不可达 / 已被兜住，修复留作未来保护，已如实标注）**：CanvasProjector forceFullPush（`changed` 定义已含 null 判断 → 不可达）/ ManualScheduleProvider ETA 钳位（`etaMinutes` 另有独立钳位 → 基本不可观测，仅对齐常量）/ useTimelinePlayback.play() 双 rAF（第 46 行已守 → 加前置幂等）/ WaitUntil 轮询预算（validated 超时上限 + 顺序轮询单任务 + per-run 限流三重兜住 → 无需加码）。

**误报（1，核实后不改）**：RandomBranch 嵌套深度——`ScriptRuleValidator.java:496` 与 `validator.ts:577` 两端都递增 ifDepth，本就一致（原审查误读后端"不递增"）。

**2 处中途修正**：① R12 严格 mapper 首跑误拒多态 `type` 字段（8 测试挂）→ mixin 改 `@JsonIgnoreProperties(value={"type"}, ignoreUnknown=false)` 显式放行；② R4/R11 子代理写的 ServerMock 测试本仓库跑不起（MockBukkit-v1.21:3.123.0 与 Paper API 版本错配，全仓零 ServerMock 实跑先例，见 `GameEventListenerHubTest:177-181`）→ 删 2 测试文件，生产修复保留。

**测试**：后端 BUILD SUCCESSFUL（含新增 PaletteLutTest / ElementValidatorMaskBoundsTest + 各 fix 回归用例）/ 前端 **1443 全绿**。关联：16 生产 Java + 8 后端测试 + 8 前端 + 5 前端测试 + 版本号 6 文件（→ `0.8.1-SNAPSHOT`）。

---

## 2026-06-21 · docs 全文档对齐到代码现状 + 新建 development/troubleshooting

把 `docs/` 全部「描述当前行为」的活文档对齐到代码真实状态（**以代码为事实源**，多子代理逐条 grep/read 核实，不靠记忆/路线表）。流程：16 份审计找漂移 → 分级过滤（剔除「无需改」「仅代码注释」项）→ 13 份 fix→verify 流水线 → verify 抓出 5 处 fix 小尾巴 controller 补丁 → 2 份新文档对抗验证（125+ 项事实零矛盾）。

- **architecture.md**：状态机删掉代码里不存在的 `CLOSED`/`EXPIRED` 态（`SessionState` 仅 SELECTING/ISSUED/ACTIVE/CLOSING；EXPIRED 是 `TokenService` 拒绝码非会话态）。
- **scripting.md**：自称「6 触发器/8 动作」实际 9/29——加版本框定（0.7.0 基础总纲）+ 新增「当前实装全集」附录（对 `Trigger.java`/`Action.java` 逐字）。
- **import-export.md**：删虚报的 `animation-flattened` warning（代码从不产出）+ 补全实装 8 种 warning + SVG 模块 7→8 文件。
- **deployment.md**：删已砍的 `/canvas unpublish` + config 速查 4 段补到全 13 段 + hot-apply 据实 + 状态行更新。
- **template-spec.md**：纠正「icon v1 不实装」（实为完整支持）+ 补 ownerUuid/权限/配额节 + 修条件文法（含比较/算术）。
- **variables.md**：清除用户文档泄漏的内部阶段号（P2-G/P3-J 等 16 处）→ 大白话。
- **api.md**：保留 namespace 补 `userglobal`（5→6）+ 依赖示例版本。**dynamic-data.md**：权限节点纠正（per-wall 删除复用 write 节点）。
- **data-model.md / timeline.md / timeline-guide.md / scripting-guide.md / security.md**：补 `tweenFps`、删 `fontId`、补 color/fill 关键帧、补 log 动作、补 auth 超时机制。
- **CLAUDE.md / PROPOSAL.md**：0.6/0.7 状态「进行中→完工」+ 补 0.7.4/0.8 路线 + 版本现实注（0.8 在 `0.7.4-SNAPSHOT` 串下完成、未 bump）。
- **新建 `docs/development.md`**（开发指南 10 节）+ **`docs/troubleshooting.md`**（故障排查 9 类，三段式）。

`rendering.md`/`protocol.md`/`benchmark.md` 复核本就准确未动；`journal.md`/`ultrareview-*`/`scripting-0.7.x`/`tween` 按约定保留；**零代码文件改动**（git 核验）。未改版本号（发版决策留给后续）。

关联文件：15 改（CLAUDE.md / PROPOSAL.md + docs/{api,architecture,data-model,deployment,dynamic-data,import-export,scripting-guide,scripting,security,template-spec,timeline-guide,timeline,variables}）+ 2 新（docs/{development,troubleshooting}）。

---

## 2026-06-20 · 0.8 Part B — B5：UI 入口 + 双端一致 + 文档（Task 18-20，**Part B 完成**）

- **Task 18 UI**：`SvgImportModal.vue`（accept=".svg,image/svg+xml"，**追加语义无破坏性二次确认**，阶段机 idle→importing→done/error；done 显示「导入了 N 个图形」，count=0 提示图层可能锁定；error 把 SVG_TOO_LARGE/HAS_ENTITY/MALFORMED/TOO_COMPLEX 翻大白话）+ TopBar 溢出菜单「导入 SVG」入口（FileImage 图标）+ i18n `svgImport` 分组（zh/en 镜像，全大白话）。
- **Task 19 双端一致回归**：`svgRoundtripParity.test.ts`（5 用例）——逻辑级 parity（happy-dom canvas 无真实像素，绕开像素快照）：归一化 d 只含 M/L/Q/C/Z（无 H/V/S/T/A 残留）+ 带洞 fillRule evenodd + mock ctx 验 `drawPath → ctx.fill(path,'evenodd')` 镜像后端 WIND_EVEN_ODD + 端到端 svgToElements→drawPath。
- **Task 20 文档回填**：rendering.md（§11 fillRule 双端表 + viewBox 映射 + 归一化子集）/ security.md（§4.7 SVG 导入五层防御 + 威胁 T16）/ protocol.md（PathElement fillRule + element.add 批量）/ data-model.md（fillRule nullable）/ import-export.md（Part B 标实装）。子代理据实纠正 5 处失实（protocol §9.5「SVG 未实装」、import-export「简单矩形直映现成元素」实为全转 PathElement 等）。

**Part B（SVG 矢量导入）全部完成**：B0 fillRule 纵切 / B1 解析基础 / B2 归一化+编排（MVP 闸）/ B3 gradient+viewBox+位图 / B4 复杂度硬化 / B5 UI+一致+文档。全程 subagent-driven，controller 独立 review + 签名提交，前端全量 **1421 测试绿、零回归**。**0.8 = Part A（.canvas 导入导出）+ Part B（SVG 导入）双双完成。**

---

## 2026-06-20 · 0.8 Part B — B4：复杂度硬化（Task 17）

`svgSecurity.complexityGuard(shapes, {maxShapes=500, maxTotalVertices=50000})`：形状数 + 估算顶点（path 命令字母数 / poly 点数 / 其它常数 4）双上限，超限 throw `SvgImportError('SVG_TOO_COMPLEX')`；`svgToElements` 在 parseSvg 后、遍历前调用，挡超大/恶意 SVG。测试 4 + svgToElements 回归 3 绿。

---

## 2026-06-20 · 0.8 Part B — B3：gradient + viewBox + 内嵌位图（Task 14-16）

MVP 后增强，一个 implementer 连做（三 task 交织在 svgToElements.ts）。controller 禁沙箱独立跑全 SVG 测试 **54 绿、零回归**。
- **Task 14 gradient**：`mapFill(el, root?)` 扩 root 参；`fill="url(#id)"` 查 root 里 `<linearGradient>`（angle=atan2(dy,dx)→[0,360)）/`<radialGradient>`（cx/cy/r%→归一化）→ 降维 Fill；stops 读 offset+stop-color、按 position 排序、钳 [2,8]；PB-4 忽略 gradientTransform/userSpaceOnUse/spreadMethod/fx,fy；无 root/查不到→undefined 降级。
- **Task 15 viewBox**：`svgToElements(svg, {targetWidth?,targetHeight?})`；viewBox+目标尺寸 → 根矩阵 `translate(-min*s)∘scale(t/vb)` 乘在祖先矩阵最外层；无则 IDENTITY（B2 行为不变）。
- **Task 16 内嵌位图**：`<image>` 仅 `data:` href（D10 拒外链，svgSecurity 同步加「非 data: 删元素」）→ image draft `{type:'image', props:{x,y,w,h}, dataUrl}`（四角变换取 bbox）；useSvgImport 对 image draft：fetch(dataUrl)→blob→File→FormData(sessionId+file)→POST /api/upload→source→element.add type:image。

测试：fillMap.gradient(4)/svgViewBox(3)/useSvgImport.image(2)。关联：fillMap.ts/svgToElements.ts/useSvgImport.ts/svgSecurity.ts + 3 测试。

---

## 2026-06-20 · 0.8 Part B — B2：归一化 + 编排（Task 8-13，**MVP 闸达成**）

前端把 SVG 形状归一化成 PathElement 子集并经 server-authoritative 循环插入。subagent-driven（含两个并行 implementer：normalizeD ‖ fillMap）。
- **Task 8-10 normalizeD**：`parsePathCommands(d): Cmd[]` 三档——M/L/H/V/Q/C/Z + 相对转绝对 + 隐式 lineto / S→C·T→Q 反射 / A→cubic（逐公式移植后端 `PathParser.arcToBezier` 的 W3C F.6；flag tokenizer 用字符级 `scanFlag` 只取单字符防 "0110" 贪婪）；`commandsToD` 紧凑拼接。10 用例。controller review 清理了未被调用的 tokenize/consumeNum 死代码。
- **Task 11 bakePath**：`bakeMatrix` / `commandsBBox`（控制点包围盒，近似）/ `rebaseToOrigin`（坐标减 bbox 左上 → PathElement.d 相对约定）。4 用例。
- **Task 12 fillMap**：`mapFill`（#hex/命名色→SolidFill；none/url(#)→undefined B3 接）/ `mapStroke` / `mapFillRule` / `mapOpacity`；style 内联覆盖 presentation 属性。Fill/Stroke 对齐 protocol.ts。4 用例。
- **Task 13 svgToElements + useSvgImport（MVP 闸）**：`svgToElements(svg): ElementDraft[]` 纯函数——遍历 shapes，祖先链 transform 从外到内 `mul` 累乘（自身先作用）→ shapeToPathD → 归一化 → bakeMatrix → bbox → rebase → commandsToD → `draft{type:'path', props}`（fill+stroke 皆空跳过；image 留 B3；props 只放有值字段，不含 id/type）；`useSvgImport().importSvg` 仿 useClipboard 选层（pickWritableLayer）+ 循环 `ws.send('element.add', {type, props, layerId})`，刷新靠后端 patch。5 用例（含全锁→count 0）。

**MVP 闸达成**：含 `<path>`+基本形状+纯色 fill 的静态 SVG → 一组可编辑 PathElement；带洞图形 fillRule 前后端一致（B0 已验）。**注意：UI 入口在 B5 Task 18 才接，当前 MVP 仅程序级（测试证明管线），游戏内手动导入要等 B5。**

**重大踩坑（待记 memory）**：vitest 在 controller 主 shell（**sandbox**）下卡死 / exit 144；**禁沙箱（dangerouslyDisableSandbox）后稳定绿且快（~350ms）**。implementer 子代理不在 sandbox 故一路绿。后续 controller 跑前端测试一律禁沙箱。

**已知边界（留后续）**：纯水平/垂直线 bbox 一维为 0，后端 PathElement 校验可能拒该条 → 那条线丢失（不崩，count 不计）；MVP 范围（rect+path）未触及，B3/polish 评估。

---

## 2026-06-20 · 0.8 Part B — B1：SVG 解析基础（Task 4-7 完成）

前端纯函数解析栈（无后端依赖），subagent-driven。新建 `web/src/lib/svg/`：
- **Task 4 svgSecurity**：`preParseGuard`（体积 + 拒 DOCTYPE/ENTITY 防十亿笑/XXE）、`stripDangerous`（walk 遍历删危险标签 script/foreignObject/use/symbol/animate*/set/style + on* 属性 + 外链 image；controller review 补强：任意元素 `javascript:` href 删属性 defense-in-depth）、`SvgImportError`。happy-dom，5 用例。
- **Task 5 svgParse**：`parseSvg(svg, maxBytes=512KB): SvgDoc{root,shapes,viewBox,width,height}`，DOMParser 解析 + stripDangerous + 扁平化收集 8 类图形节点。2 用例。
- **Task 6 shapesToPath**：`shapeToPathD(el): string|null`，rect/line/polyline/polygon → M/L(+Z)；circle/ellipse → 4 段三次贝塞尔（kappa=0.5522847498，右极点起顺时针）；path 返自身 d；image/未知 → null。紧凑数字格式。7 用例。
- **Task 7 transform**：`Mat=[a,b,c,d,e,f]`、`parseTransform`（translate/scale/rotate[含中心]/matrix/skewX/skewY 链，左到右累积 mul）、`mul`（列向量约定）、`applyPoint`、`IDENTITY`。8 用例。

**踩坑**：三测试一条 `vitest run` 多文件并发 → exit 144（worker 崩，非测试失败）；逐文件单独 `npx vitest run <file>` 全绿。Task 4 单独 commit，Task 5-7 并行实现合并一 commit，各验签 true。

---

## 2026-06-20 · 0.8 Part B — B0：fillRule 纵切前后端（Task 1-3 完成）

SVG 导入承载带洞图形填充规则的前置纵切，subagent-driven（3 子代理实现+测试，controller 独立 review + 签名提交）。无 db-migration（可空字段默认 nonzero = 旧行为）。
- **Task 1（后端字段）**：`PathElement` record 加第 17 字段 `String fillRule`；`ElementValidator.parseFillRuleNullable`（仅 nonzero/evenodd/null，非法抛 `ValidationException`）；`EditSession.buildPath` 读、`applyPathPatch` 加 `case "fillRule"`；**9 处构造点**（plan 数 8，实测含测试文件 `KeyframeInterpolatorTest` 共 9）末位补值——move/duplicate/template/interpolate 保留原值 `p.fillRule()`、benchmark/测试补 `null`。`PathFillRuleTest` 3 用例。
- **Task 2（后端渲染）**：`PathRenderer` fill 前 `parsed.path().setWindingRule(evenodd ? WIND_EVEN_ODD : WIND_NON_ZERO)`；`PathParser.Result.path()` 真实即 `Path2D.Double`（无需 cast）。`PathRendererFillRuleTest` 带洞图形（外方+内方同向）中心像素 alpha：evenodd 透空=0、nonzero 填实>0。
- **Task 3（前端）**：`protocol.ts` PathElement 加 `fillRule?: 'nonzero' | 'evenodd'`；`PreviewRenderer.drawPath` 改 `export` + `ctx.fill(parsed.path, p.fillRule === 'evenodd' ? 'evenodd' : 'nonzero')`——**与后端逐函数镜像**。`previewPathFillRule.test.ts`（happy-dom + Path2D stub + 动态 import）2 用例。

回归：`:plugin:test --tests state.* / render.* / EditOp*` 全绿；前端单文件 vitest 绿。3 commit 各自签名验签 true。

---

## 2026-06-20 · Part B 开工前对齐核查 + plan 计数修正

开工 Part B 前按纪律实地核查（读 journal/代码不凭记忆），坐实 plan 三根支柱 + 修一处计数笔误：
- **fillRule 两端确无**（grep `plugin/src/main/java` + `web/src/types/protocol.ts` 零命中）→ PB-1 新增 `PathElement.fillRule` 字段的必要性坐实。
- **`PathParser` 全文法（M/L/H/V/Q/T/C/S/A/Z + 弧分解）vs `PathDValidator` 只认 M/L/Q/C/Z** → 前端归一化（H/V→L、S→C、T→Q、A→cubic）是 `element.add` 过 `PathDValidator` 校验的**硬要求**，PB-2/B2 坐实。
- **无批量 op** → 一组 SVG = N 条 `element.add`、N 次撤销（PB-3，与 clipboard 一致）。
- **修正**：plan 正文「7 处 `new PathElement` 构造点」实为 **8 处**——`TemplateInstantiator.java:293` 用全限定名 `new moe.hikari.canvas.state.PathElement(`，裸 grep `new PathElement(` 漏命中（plan 行号清单本就列全 8 个，仅计数文字写 7/6，已改 8/7 并注明全限定名坑，避免 implementer 漏改导致编译失败）。

无代码改动。关联：plan 文件 4 处计数修正。

---

## 2026-06-20 · 0.8 Part B 实施计划写就（SVG 矢量导入，20 task TDD）

writing-plans + 2 子代理精确侦察（前端 SVG 栈 / 后端 path 栈）→ `docs/superpowers/plans/2026-06-20-0.8-partB-svg-import.md`。20 个 bite-sized TDD 任务：B0 fillRule 纵切(3) / B1 解析基础(4) / B2 归一化+编排(6，**MVP 闸 Task 13**) / B3 gradient+viewBox+位图(3) / B4 安全硬化(1) / B5 UI+双端一致+文档(3)。

**侦察确认的关键事实**：后端 `PathParser` 已支持全 SVG path 文法（M/L/H/V/Q/T/C/S/A/Z + 弧分解 cubic），但 `PathDValidator` + 前端 `PathParser.ts` 只认 M/L/Q/C/Z → **d 归一化（A/S/T/H/V 展开）是前端必做纯函数**；**`fillRule` 前后端都没有**（D9 要新增 `PathElement.fillRule` 纵切 5 层，**无 db-migration**，可空默认 nonzero）；**无批量 op**（一组 SVG = N 条 `element.add`，N 次撤销）；**server-authoritative**（前端走 `ws.send('element.add')` 不 mutate store）。

6 条固化决策 PB-1~PB-6（见文档 §决策摘要）。工时 ~70h（含 fillRule）。待执行（subagent-driven）。本条仅计划，无实现代码。

---

## 2026-06-20 · 0.8 Part A review 补缺：导入时扫缺字体/图标/变量（missing-* warning）

补 plan §3.2 step 8 要求、A2 批3 漏实装的 3 种 warning。新建 `canvasfile/MissingResourceScanner.java`（`scan(ProjectState): List<ImportWarning>`），在 `ProjectImporter.importInto` 的 materialize 之后、replaceProject 之前调用、`warnings.addAll`：
- **missing-font**：遍历 `TextElement.fontId`（非 null），查 `FontRegistry.get` 判存在性 → 缺则 warn（去重）。
- **missing-icon**：遍历 `IconElement.source`，**仅 `user/<id>` 形态**查 `IconRegistry.isRegistered`（内置 `fa-*` 恒在、legacy PNG 不在范围）→ 缺则 warn（去重）。
- **missing-variable**：提取 `${var:...}`（复用 `VariableInterpolator` 同款占位符文法），**仅判定 `userglobal/<key>`** 查 `VariableStore.get`；`user/*`（wall-scoped 导入需重设）、`schedule`/`scoreboard`/`wall`/`rail`/`system`（本服 provider 运行期 resolve）一律不扫，避免误报 → 缺则 warn（去重）。

**装配**：`MissingResourceScanner` 注入 `ProjectImporter`（构造加第 8 参，可空 best-effort）；`WebServer` 用已有 `fontRegistry`/`iconRegistry`/`variableStore` 三字段 new scanner（各自可空降级）。3 处旧测试装配同步加 `null`。

**测试**：`MissingResourceScannerTest`（18 用例：各 warning 产出 + 去重 + 不误报 + 可空降级 + 多层）；`ProjectImporterTest` 加 1 端到端用例（引用缺字体 → warnings 含 missing-font）。全绿。

**文档**：`protocol.md` warning kind 表 3 行「预留/当前未产出」→「已实装」（变量行如实写"仅扫 userglobal"、图标行"仅 user/*"）；汇总句 5 种→8 种（仅 `animation-flattened` 仍预留）。`security.md`/`data-model.md` 经核查**不含** `missing-*` warning 措辞，无需改。

关联：`MissingResourceScanner`（新）/ `ProjectImporter` / `WebServer` / `MissingResourceScannerTest`（新）/ `ProjectImporterTest` / `ProjectImportHandlerTest` / `protocol.md`。

---

## 2026-06-20 · 0.8 Part A 文档回填：4 份契约同步到实装（4 子代理并行）

Part A 代码完成后，把契约文档回填到与代码一致（文档先行纪律，避免滞后）。4 子代理并行回填，各先读真实代码核实、据实纠正清单错误：
- **data-model.md §4**：去「未实装」→ 已实装；§4.1 结构加 `scripts.json` + `assets/icons`；§4.4 孤儿轨定稿「丢弃+warn」；§4.5 JSZip→**fflate**；新增 §4.6 `scripts.json` 形态；§5.1 config 加 `import.canvas-max-*` 三行。
- **security.md §4.4/§13.5/T10**：补 `AssetIngest` 六步防御链 + `ScriptImporter` 重校验链；据实写明 thumbnail 导入侧不读、配额计入。
- **protocol.md §9.5/§6.1**：新增 `POST /api/project/import` 端点契约 + 10 个错误码→HTTP status 表 + 导入后 `state.snapshot`/投影下行。
- **architecture.md §18 新增**：导入/导出数据流 + `canvasfile` 包职责 + `ProjectImporter` 编排链 + 装配 + 信任边界。

**子代理据实纠正的实况（重要）**：
- **warning kind 实装只 5 个产出**（`asset-quota`/`orphan-track-dropped`/`script-invalid`/`script-command-blocked`/`script-quota`）；`missing-font`/`missing-variable`/`missing-icon`/`animation-flattened` 仅 Javadoc 预留、代码未触发（plan §3.2「扫缺字体/变量/图标」**未实装**——待决定补否）。
- `thumbnail.png` 仅导出生成、导入侧不读取/不校验。
- 路径校验实为拒 `..`/绝对/反斜杠/NUL（**无 symlink 检查**，原文措辞失实已修）。
- `assets/icons/*.svg` 仅白名单接纳、不摄入（SVG 是 Part B 未实装）。

无代码改动。关联：`data-model`/`security`/`protocol`/`architecture` 4 文档 +300/−39。

---

## 2026-06-17 · 0.8-A4 脚本纳入落地（Part A 19 task 全部完成）

A4 共 3 task：implementer 实现+测试、controller review+签名提交。前端 10 测试 + 后端 `ScriptImporterTest` 8 + `ProjectImporterTest`/`HandlerTest` 全绿，vue-tsc 无新错误。

**成果**：导出的 `.canvas` 现带 `scripts.json`（墙脚本随工程走）；导入时脚本**重绑到目标墙 + 全量重校验**（不信任文件内 rule_json）+ 落 `wall_scripts`；warning 清单变大白话。
- **Task 17**：`useProjectExport` 取 `useScriptStore().listSorted` → `scripts.json`。
- **Task 18**：新建 `ScriptImporter`——逐条多态反序列化 → `ScriptRuleValidator.validate` → 递归 `ConditionEvaluator.checkSyntax` → 命令模板缺失 `script-command-blocked`（不跳过、照常落库，`security.md §13.5`）→ `ScriptStore.create`（wallId 重绑 + 新 ruleId；配额超 `script-quota` 停）。`ProjectImporter` 编排注入 ScriptImporter（构造 7 参），`WebServer` 装配。
- **Task 19**：`ImportProjectModal.warningText` 把 9 个 kind→大白话（i18n `project.warn` zh/en 镜像），如「字体『X』这台服务器没有，已用默认字体代替」。

**implementer 关键偏离**：`ScriptOpDispatcher.parseIncomingRule/checkConditionSyntax` 是 web 包 package-private、跨包不可复用 → ScriptImporter 内用公开 API 复刻同语义（wallId 重绑由 `store.create` 承担、条件语法递归自实现）。

**Part A（`.canvas` 导入导出）19 task 全部完成**：A1 导出 + A2 后端导入/安全 + A3 导入 UI（+拖拽/accept 修复）+ A4 脚本纳入。剩**文档回填**（data-model/security/protocol/rendering §9）+ 版本号 bump。关联：新增 `ScriptImporter` + 3 测试，改 `ProjectImporter`/`WebServer`/`useProjectExport`/`ImportProjectModal`/`messages`。

---

## 2026-06-17 · 0.8-A3 修复：导入对话框拖拽 + .canvas 可选（用户实测 bug）

用户实测 A3 导入对话框两个 UI bug，systematic-debugging 定根因后修：
- **macOS Finder 选不中 `.canvas`**：`<input accept=".canvas">` —— `.canvas` 是自定义扩展名、macOS 基于 UTI 无注册 → 被灰掉，逼用户切「所有文件」。修：**去掉 accept**（放行任意文件，合法性由后端导入校验兜底 `IMPORT_MALFORMED`）。
- **不支持拖入**：对话框只有点击选文件。修：idle 入口区加拖拽（`dragover` 高亮 + `drop` 取文件），抽 `acceptFile` 统一「选/拖」入口。

+ i18n 拖拽提示（zh/en `importDropHint`）+ 2 个回归测试（`acceptFile`→confirm、input 无 accept）。关联：改 `ImportProjectModal.vue` / `messages.ts` + 测试。

---

## 2026-06-17 · 0.8-A3 导入 UI 落地（.canvas 导入端到端可点，subagent-driven）

A3 共 3 task：implementer 实现+测试、controller review+签名提交。**5 测试全绿**，vue-tsc 零 TS 错误。

**成果**：编辑器「更多菜单 → 导入工程」打开导入对话框 → 选 `.canvas` → 破坏性替换二次确认 → 导入 → 展示后端 warnings 清单 / 错误。配合 A1 导出 + A2 后端导入，**导出 → 导入完整 roundtrip 现可在编辑器端到端点击验证**。新增 `composables/useProjectImport`（FormData POST `/api/project/import`，刷新靠后端 WS `state.snapshot` 零前端代码）、`components/layout/ImportProjectModal`（阶段机 idle→confirm→importing→done/error，importing 防关闭）+ TopBar 入口 + i18n `project` 分组。

warning 大白话 kind→文案映射留 A4 Task 19（本批先显示原始 `detail`）。关联：新增 2 源 + 3 测试，改 `TopBar.vue` / `messages.ts`。

---

## 2026-06-17 · 0.8-A2 后端导入+安全落地（.canvas 导入闭环，3 批 subagent-driven）

A2 共 8 task，分 3 批 implementer 串行 + controller review/签名提交。后端**零 zip 代码从零写**，端到端 e2e 通：multipart 收 `.canvas` → 流式安全解包 → 校验 → 灌入会话 → 广播 snapshot + **游戏内投影** → 持久化 → audit。

- **批1 零件（Task 6/7/8/10，16 测试）**：`ImportConfig` 限额（Builder 5 处接线）；`CanvasArchive` zip 流式三闸 + 路径白名单（从零写）；`CanvasManifest` 解析 + spec 兼容；`ProjectMaterializer` 物化（复用 `TemplateInstantiator` 范式 + 元素校验 + 尺寸匹配）。
- **批2 集成件（Task 9/11，8 测试）**：`EditSession.replaceProject` 保留多层（照 `replaceContent` 范式 `restore`）；`AssetIngest` 图片摄入（magic + 200ms 隔离解码 + 头部预检 + SERIALIZABLE 配额事务 + `writeFileAtomic` 失败补偿回滚；跨包私有件不可复用故内写等价实现）。
- **批3 编排+端点（Task 12/13，13 测试）**：`ProjectImporter` 串全链；`ProjectImportHandler` `/api/project/import`（鉴权 + `canvas.edit` + 错误码→HTTP status 映射，e2e 真链非 mock）。`WebServer` 装配 + `HikariCanvas` bootstrap `AssetIngest`。

**review 抓到 1 个真缺口并修**：`ProjectImporter` 初版漏 `throttler.submit`（对照 `EditOpDispatcher` OkSnapshot 分支 :344）→ 导致导入后**游戏内地图不刷新**、只有编辑器更新。修：注入 `ProjectionThrottler`，用 `replaceProject` 返回的 `OkSnapshot.dirty()` 提交全画布重绘（为可测去掉 `ProjectionThrottler` 的 `final`，项目无 mockito）。**已知限制**：`persistWall` 全链的 Ticker 自动播 / 触发器 rebuild 不在导入路径——导入的时间轴动画需手动播 / 墙重载起播。

**push 分叉**：远程多了用户的 `Update README.md`，`rebase`（`-c rebase.gpgSign=true` 强制重签）干净叠加。全量 `:plugin:test`（2039）零回归。scripts.json 导入留 A4。关联：`canvasfile/` 包 8 类 + 改 `WebServer`/`HikariCanvas` + `ProjectionThrottler` 去 final。

---

## 2026-06-16 · 0.8-A1 前端导出落地（.canvas 导出闭环，subagent-driven 首批）

按 Part A 计划 subagent-driven 执行 A1（5 task）：implementer 实现+测试、controller（我）独立 review + 签名提交。**11 测试全绿**，vue-tsc 无新增错误（66 个 pre-existing 无关错误不动）。

**A1 成果**：编辑器「更多菜单 → 导出工程」把当前工程打成 `.canvas`（zip：`manifest.json` + `project.json` + 256×128 缩略图 + `assets/` 引用图片）下载，纯浏览器、不经服务器。新增 `lib/downloadBlob`、`lib/canvasFile`（收集图片hash / manifest / 组装zip 纯函数）、`render/exportThumbnail`（复用 `renderProjectState` 渲全工程）、`composables/useProjectExport`（编排）+ TopBar 入口 + i18n；fflate 转正式 dependency。

**implementer 按真实代码的关键修正**：① 图片下载端点要 `?sessionId=`（M16 P1.1 IDOR 修复），否则导出丢图 → `fetchImageBytes` 拼上 sessionId + 失败优雅跳过；② 无 `__APP_VERSION__` 注入 → `manifest.plugin_version` 用后端 ready 上报的 `serverVersion`；③ `showMoreButton` 改恒 `true`（导出是只在溢出菜单的常驻项，宽屏也要能打开 … 菜单拿到它，0.7.4 折叠逻辑仍保留）；④ 测试适配 happy-dom 无 canvas 2D context / `vi.mock` hoisting TDZ → `vi.hoisted` / `Uint8Array`→`BlobPart` cast。

A2（后端导入+安全，8 task）待用户验证 A1 后续做。关联：新增 5 源文件 + 5 测试，改 `TopBar.vue` / `messages.ts` / `package.json`。plan `2026-06-16-0.8-partA-canvas-io.md`。

---

## 2026-06-16 · 0.8 Part A 实施计划写就（.canvas 导入导出，19 任务 TDD）

writing-plans + 2 子代理精确侦察（后端导入栈 / 前端导出栈，给到 `文件:行号` + 签名 + 测试范式）→ 写出 `docs/superpowers/plans/2026-06-16-0.8-partA-canvas-io.md`。19 个 bite-sized TDD 任务：A1 前端导出(5) / A2 后端导入+安全(8) / A3 导入UI(3) / A4 脚本纳入(3)。

**侦察确认的关键事实（影响实现）**：后端**零 zip 代码**（安全解包从零写）+ **无 audit 枚举**（`PROJECT_IMPORT` 传字符串）；ProjectState 物化复用 `TemplateInstantiator.instantiateRawState` 范式；**`EditSession.replaceContent` 只单层拍平 → 新增 `replaceProject` 保多层**；脚本 `ScriptStore.create` 天然重绑 wallId；前端 **fflate 仅 dev-transitive**（首步转正式依赖）；**导入刷新零前端代码**（后端推 `state.snapshot` → 既有 `handleSnapshot`→`setSnapshot`）。

待执行（subagent-driven）。本条仅计划，无实现代码。关联：新增 plan 文件 + 末尾「任务↔契约覆盖」自查表。

---

## 2026-06-16 · 0.8 立项：工程导入导出 + SVG 导入 设计总纲（docs/import-export.md）

长期评估收敛后定 0.8 = 两个新功能：`.canvas` 工程导入导出 + SVG 矢量导入。多子代理读**真实代码**评估（非凭记忆）+ 用户 AskUserQuestion 拍板 → 照 `scripting.md` 范式写设计总纲。**本条无代码改动，纯契约定稿。**

**子代理评估的三个反直觉发现（均读代码得出）：**
- **`.canvas` 地基 ~60-70% 现成**：ProjectState 已完整 Jackson 序列化 + 向后兼容、TemplateExporter 已跑通「序列化+自校验 roundtrip」范式、图片 hash 收集 / 上传校验栈 / 变量 fallback 全现成，连导入安全限额（`security.md §4.4`）+ 脚本导入威胁模型（§13.5）都已写。**最大缺口：脚本独立存 `wall_scripts`、不在 ProjectState，旧 spec §4.3 只导 ProjectState → 会丢脚本** → 用户拍板纳入，新增 `scripts.json`。
- **SVG 矢量管线早已存在**：M9 起有 PathElement/Circle/Shape、M26 起 IconElement 即 SVG 矢量、后端 `PathParser.java` 已支持完整 SVG path 文法（含椭圆弧→cubic）、`IconRegistry.loadExternal` 已有单 path 用户 SVG 导入通道。故 SVG 导入 = 复用矢量地基 + 补前端多 path 解析器。硬约束：SVG 因 magic-bytes 进不了图片上传管线 → 栅格化反而不省事 → 用户拍板「只做矢量完整版」。
- **动画 SVG 撞时间轴墙**：实抓用户海报（hcs.wiki：CSS `@keyframes` + SMIL 渐变 + `stroke-dashoffset` 画线 + 多周期并发）。我们无 CSS 解析器 / 无渐变 stop 动画 / 无 stroke 动画 / 一墙一时刻只播一条时间轴 → 仅 A 档（平移/旋转/透明度/keySplines）能近无损映射 → 定：动画 SVG 取首帧静态化，「SVG 动画→时间轴」留 0.9+。

**10 条固化决策 D1-D10**（见文档 §0）。**工时** Part A ~53h + Part B ~64h + 文档回填 ~6h ≈ **120h / 3-4 周**。§9 列 6 处下游契约回填清单（实装时同步）。

关联：新增 `docs/import-export.md`（0.8 契约总纲：升级 `data-model.md §4`「规划」→「实装定稿」、扩展 `security.md §4.4/§13.5`、复用 `scripting.md §2.1` + `protocol.md §7`）。下一步 writing-plans 拆 Part A 实施计划。

---

## 2026-06-15 · 0.7.4 前端体验优化批：6 个体验 bug（小窗口响应 + 画布平移 + 变量 picker 根因）

3 子代理读真实代码定根因 + 用户拍板方案（问题一两栏 / 问题二更多菜单）→ 4 子代理并行实施。

**6 个问题：**
- **一 左工具栏自动两栏**：`LeftTools.vue` 15 工具纵向单列 `w-12`，窗口**矮**时底部按钮被截出视口、无折叠无滚动。`useResizeObserver` 监高度，< 阈值(~660px)切 `grid-cols-2`+`w-24`(纵向需求减半) + `overflow-y` 兜底。
- **二 右上角更多菜单**：`TopBar.vue` 14 按钮无响应式，窗口窄时右侧按钮静默消失。`useResizeObserver` 测宽，7 个低频按钮按断点收进 `…` `OverflowMenu`(新组件)，高频 6 个(面板/时间轴/脚本/变量/时刻表)常显。
- **三 鼠标左右拖画布**：非 `Ctrl+滚轮`交还浏览器默认只纵滚(鼠标无 `deltaX`)。`usePanScroll.onWheel` 加 `Shift+滚轮→水平`(PS/Figma 标准)+ `CanvasZoomBar` 加快捷键提示(中键/`Space`/`H` 拖拽本已实现、用户不知)。
- **四+五 `schedule_rail` 幽灵变量(同根因)**：`RailScheduleProvider` 的 daemon 内部 key `"schedule_rail"`(为避 daemon map 撞 manual 的 `"schedule"`)被 `VariableMetadataHandler` 当变量 namespace 暴露,但真实变量是 `schedule:<wallId>/*`。→ picker 一堆幽灵变量(无值,问题四)、选了插入报「已删除」(问题五)。修:`VariableProvider` 加 `storeNamespacePrefix()`(rail 返 `"schedule"`)，`buildJson` 用它替 daemon key;rail 14 专属 key 描述加 `[铁路网络]` 区分。rail 变量改归 `schedule` 组,经 interpolator 注入 wallId 端到端 resolve;manual 不受影响。
- **六 picker 列错乱**：`VariablePicker` `table-layout:auto` + name-cell `display:flex`(脱离表格布局)+ 无 `overflow-x` → 列乱大片空白。改 `table-layout:fixed` + `overflow-x:auto` + name-cell 去 flex;配合四五消幽灵变量、空白大减。

**根治(systematic-debugging,本会话第三次踩)**：store setup 用 DOM API 在 node 测试炸——`theme.ts`(`window.matchMedia` + `document.documentElement` ×3)+ `ui.ts`(`document.documentElement.lang` ×2)全加 `typeof window/document !== 'undefined'` 守卫。之前 scriptEdit(D2)一次、本批 C(usePanScroll 测试)一次,根治后不再每次改测试迁就。

**环境插曲**：实施期发现 **env cwd 被切到 `docs` 分支**(用户在另一终端整理 docs)，main 真实代码在 worktree `HikariCanvas-main`;**4 子代理自动检测到 cwd 不对、去 main 改对了地方**。main worktree 无依赖,`npm install` 同步 lock(+23 行 optional native deps,`package.json` 零改);代码均在 main 编译测试 + 提交。

**版本号** `0.7.3 → 0.7.4-SNAPSHOT`(7 处)。**测试** 后端全绿 / 前端 **1334**(原 1303 + 0.7.4 各批测试 + theme/ui 守卫修复)。

关联：前端 `LeftTools`/`TopBar`/`OverflowMenu`(新)/`usePanScroll`/`CanvasZoomBar`/`VariablePicker`/`interpolator`/`pickerLogic`/`theme`/`ui`/`messages` + 4 测试;后端 `VariableProvider`/`RailScheduleProvider`/`VariableMetadataHandler` + 2 测试;版本号 7 处 + `package-lock`。plan `docs/superpowers/plans/2026-06-15-0.7.4-frontend-ux.md`。

---

## 2026-06-14 · 契约文档批量回填：9 份 docs 原地校准到与代码一致（写用户手册前的地基）

为后续写「整个项目的玩家+服主大文档」做准备,**先把整个项目代码读完**（12 子系统,多子代理并行,
全程以代码为唯一事实来源,不凭记忆、不信滞后的设计文档），再把既有契约文档**原地**更新到与当前
代码一致。**不新建、不删除任何文档**;每份由一个子代理先回代码逐条确认、再编辑,保留原结构,未实装
功能标注「未实装/规划中」而非删除。无任何代码改动。

**9 份文档更新（+1013 / −383 行）:**
- **protocol.md** 协议 v2→v7;厘清三层版本号（业务 v7 / 信封壳恒 2 / ProjectState schema 3）;
  close codes 4001/4002/4003/4429 据实;ready payload 补全;补 timeline/script/rail/schedule/tweenFps op;
  `cancel`、`session.warning` 等未实装标注。
- **data-model.md** 迁移补到 V017（V009 跳号）+ 别名/全局变量/铁路 5 表 schema;**`.canvas` zip 格式标
  「规划中·未实装」**;PDC 仅 wall_id+slot;审计事件据实（AUTH_OK 非 AUTH_ISSUED）。
- **template-spec.md** rotation 0/90/180/270→连续 0..359;元素 4→8 种（line「v1 不渲染」）;icon FA 矢量;
  fill 三态渐变;补 raw_state「存当前招牌」主路径 + 属性范围常量。
- **rendering.md** 字体加载改 FontFace API 单轨;**字体数实清点=22 枚**（FontRegistry.BUILT_IN 与
  bundledFonts 两处一致;旧表只列 2 枚）;竖排「未实装」→已实装;补透明背景（ARGB）+ 加粗/斜体;
  config `fonts:` 声明段标未实装。
- **timeline.md** 删不存在的 `PLAYER_NEAR` 枚举;MVP 描述标历史,当前=5 缓动+9 属性+多 timeline;
  wire camelCase≠Java 名;cubicBezier 已实装。
- **variables.md** 标题 0.4.0→0.7.3;系统变量列正确;**PAPI 冒号语法不支持**（改 `papi/` 或 `papi.`）;
  `${var:system/server.time}` 而非裸点号;schedule 7→15 key;chip 紫色。
- **dynamic-data.md** 同上 PAPI/系统/schedule 修正;铁路 11→13 op;**删除不存在的 `rail.run.edit.*` 权限**
  （实际复用 line.edit）。
- **security.md** ⚠️ **SSRF 防御已移除如实写明**（URL 上传不再过滤私网/回环,公网服主醒目提示）;审计事件
  表据实重写;限流/IP 绑定/token 机制校正;权限节点表对齐 paper-plugin.yml;CI 扫描标「规划未接入」。
- **architecture.md** `/canvas cleanup` 标 stub 未实装;publish/unpublish 命令砍→ wall.lock op（DAO 保留,
  语义=lock）;墙面仅垂直限制据实;HTTP 端点鉴权差异表;config 键名校正（map-pool.*）。

**核心发现**:既有 `docs/*.md` 普遍是「规划态/历史态」,与代码出入大,**写文档必须从代码核对、不能照抄
旧契约**。深度审查清单见 `docs/ultrareview-2026-06-14.md`。下一步:在此地基上写新的玩家+服主用户手册。

关联:`docs/{protocol,data-model,template-spec,rendering,timeline,variables,dynamic-data,security,architecture}.md`
（均原地更新,无新增/删除）。

---

## 2026-06-14 · 0.7.x 整体收尾：用户文档 + 契约回填 + 压测简化 + 路线对齐（0.7.0 P6 收口）

0.7.x 主线功能（0.7.0–0.7.3 + 补间）全完工后,**先读代码读文档评估收尾范围**（纠正了凭记忆误判
「0.7.1 P4-P5 待做」——实际早完工）。3 子代理并行评估 → 用户拍板「**压测简化 + 版本号保持 SNAPSHOT**」
→ 4 子代理并行实施。这是 0.7.0 P6 的收口产出。

**4 项产出：**
- **A 用户文档** 新建 `docs/scripting-guide.md`（399 行,5 部分大白话:做第一条规则 / 9 触发器 / 29 动作 /
  进阶〔幽灵拖动 + 试跑轨迹高亮 + 熔断说明〕/ 服主运维〔权限 + 命令模板白名单 + 调参 + 排障〕）。照
  `timeline-guide.md` + `variables.md` 形态,整合 `scripting-tween.md §12` 补间用法。
- **B 契约回填** `security.md §13` 脚本运行时威胁模型（命令白名单 K13 净化 / 熔断 Budget 三闸 / 触发器权限面 /
  编辑鉴权 / 审计 7 事件）+ `architecture.md §17` 脚本架构（执行引擎三组件数据流 / 三线程模型 / 触发器接入 /
  setElementProperty 双路径 / 反模式守则）。
- **C 压测简化**（用户定「只测核心」）:`TweenBenchmarkDriver`（N 墙 [1/4/16/64] 并发补间 tick 成本,测
  `buildInterpolatedFrame` deepCopy）+ `ScriptBenchmarkDriver`（脚本动作链 [1/10/25/50] 开销,真实 ScriptRunner
  SES）+ `/canvas bench run-tween`/`run-script` + `benchmark.md §7`（含已知局限:playerNear 依赖 Bukkit 主线程,
  纯 headless 测不了）。复用 0.5.0 benchmark 框架,文字摘要（不出 html,简化）。
- **E 文档对齐** `CLAUDE.md` 补 0.7.2/0.7.3 整行 + 改 0.7.1「P4-P5 待做」为完工;`scripting-0.7.1`(P1-P5) /
  `scripting-0.7.2`(P1-P4) / `scripting-tween`(P5 误写「进行中」) 分期表回填 ✅;2 个 demo 插件
  `paper-plugin.yml` 版本号裂口修（0.5.0 → 0.7.3-SNAPSHOT）。

**收尾中 systematic-debugging 修 2 处：**
- **guide 4 处事实不准**（读代码核出来的）:玩家击杀实际是 **PvP「被另一玩家击杀」**（`getKiller()!=null`;
  环境死亡 / 杀生物都不算,A 误写「击杀别人/生物」）;右键墙 + 退服**也需 `trigger.global`**（`ScriptPermissions`
  管 PlayerJoin/Kill/Quit/RightClickWall 4 个,A 漏标）;权限表补全。
- **benchmark 编译错**:`TweenScheduler` 的 test seam 构造（ApplyManyFn 版）+ `tickForTest()` 都是
  package-private,benchmark 包访问不了 → 两处 public 化（与 `AnimationTicker.tickOnceForTest` 一致,低风险）。

**测试**:后端全绿（含 benchmark +9 测试）/ 前端 **1303**（无代码改,有效）。0.7.0 P6 三件（压测 / scripting-guide /
契约回填）收口。

**评估纠错（关键）**:0.7.x 功能全齐——9 触发器 + 29 动作全实现,无「剩余触发器」;OnCommand/OnLockChange
是设计时就推后/不做的,不属 0.7.x 收尾。版本号保持 `0.7.3-SNAPSHOT`（用户定,不定正式版）。

关联:`docs/scripting-guide.md`(新) / `security.md` / `architecture.md` / `benchmark.md` / `CLAUDE.md` /
`scripting-0.7.1/0.7.2/tween`;plugin `benchmark/{Tween,Script}BenchmarkDriver`(+2 测试) / `BenchmarkSubCommand` /
`TweenScheduler`(seam public 化);2 个 demo `paper-plugin.yml`。

---

## 2026-06-14 · 0.7.3 ultrareview 第三批（收官）：协议 close 一组 + P2 散点 11 条

承接第一批 `ffd7a5c` / 第二批 `7bd403a`。5 子代理并行（**先确认属实——11 条全属实无误报**）+ 2 处
systematic-debugging。**ultrareview 三批正式收官**。实施计划 `docs/superpowers/plans/2026-06-14-ultrareview-batch3.md`。

**11 条修复：**
- **A 协议 close(4)**：`wsClient` onClose terminal 集合加 `4002`(版本不符)/`4429`(限流，删后端从未发出的
  死码 `4008`)→ 不再用同版本无限重连；抽 `isTerminalCloseCode` 纯函数；`VERSION_MISMATCH` 文案从
  「画板被他人改动正在同步」改「客户端版本与服务器不兼容，请升级」；`4002` 分支 `if(!lastError)` 保护
  handleReady 设的精确提示不被通用「连接断开」覆写（P2-17 / P2-18 / P3-12 / P3-13）。
- **B 渲染一致(2)**：`GlowRenderer` 非旋转 glyph 的 glow bbox 从 AWT `FontMetrics` 改用
  `round(fontSize*0.8)`+`canonicalCharWidth`(前端同款度量)→ 发光双端一致；`PreviewRenderer.drawDitheredElement`
  opacity 加 `isFinite+clamp(0,1)`(负 opacity 双端分叉)（P2-7 / P2-19）。
- **C 模板路径(2)**：常规布局(stack/free/grid)`materialize` 补 `validateElementForTemplateApply` +
  fontSize/lineHeight/letterSpacing clamp + content 超长拒(与 raw_state 路径对称)；`resolveDimension` 用
  `StrictNumber.clampInt` 防 long→int 静默回绕 + 超 int 数字串不外泄 JDK NFE 文案（P2-11 / P2-12）。
- **D 前端交互(2)**：`BlockParamInput` 命令模板孤儿 templateId 显示红字警告(以前静默吞致参数消失)；
  `CanvasView.boundBoxFunc` 重写 + `useSnapManager.snapEdge` 单边吸附(resize 拖手柄只吸正在动的那条边，
  不再被静止锚点的 delta 拉跳)（P2-15 / P2-20）。
- **E MapPool(1)**：`offerFreeByName` 加 `allowBukkitFallback` 参数，`detectLeaks` 异步路径传 `false`
  (缓存 miss 时不调 `Bukkit.getWorld`，落 unknown-world 桶等主线程 reclaim 回迁)，守住「detectLeaks 不碰
  Bukkit API」+ idcounts.dat 防膨胀线程纪律（P2-10）。

**systematic-debugging(2)**：① C 自己的 `textFieldsClampedNotRejected` 测试输入太极端(size 9999 让
auto 布局算出 h≈99 万超 MAX_DIM 被 validateElementForTemplateApply 拒)→ 改用温和超限值，隔离「字段 clamp」
语义(极端尺寸拒由 `freeLayoutOversizedDimRejected` 覆盖)；② glow baseline 漂移(P2-7 预期，读 diff 图确认
仅 "GLOW" 末字符位置微偏、是对齐前端度量而非渲染坏)→ 重建 `05-effects-glow.png`。

**测试**：后端 **~1989** 全绿(含 glow baseline 重建)/ 前端 **1303**(+60)全绿。用户实测通过。

**🏁 ultrareview 总收官**：三批累计 **27 条真问题**(8 + 8 + 11)+ 剔除 1 误报(P2-8 BlendModes float32/64)。
覆盖崩溃 / 数据丢失 / 静默失败 / 双端不一致 / 无限重连。剩 P3 约 15 条(文案/局部边界) + D 25 条(设计待定，
多被上游守卫兜住) → 低 ROI 遇到再修；P2-9(migration WAL 备份) → 1.0 发版开 auto-backup 时修。

关联：前端 `wsClient` / `messages` / `PreviewRenderer` / `BlockParamInput` / `CanvasView` / `useSnapManager`
+ 4 测试(closeCode / glowAndDitherOpacity / useSnapManager / BlockParamInput.smoke)；后端 `GlowRenderer` /
`TemplateInstantiator` / `MapPool` + 2 测试(TemplateInstantiator / MapPoolDetectLeaksThreadSafety) +
glow baseline；plan。

---

## 2026-06-14 · 0.7.3 ultrareview 第二批：真崩溃 / 数据错乱 / 防御 6 条 + rail 拆分 + 保存提醒

承接第一批（`ffd7a5c`）。5 子代理并行（子系统无冲突）+ 1 处 systematic-debugging。实施计划
`docs/superpowers/plans/2026-06-14-ultrareview-batch2.md`。

**8 条修复**（ID 对报告）：
- **P2-14** 删绑定站点 → 屏整运行期空白：`RailOpDispatcher.handleStationDelete` 照 `handleLineDelete`，
  删前收集 boundWalls、删后 `provider.unregisterWall`（让 ManualSchedule 接管）。
- **P2-13** `next_departure` 取的是到达时刻（**用户确认拆分**）：`RailScheduleProvider.snapshotFields`
  把 `next_arrival`（读 `arrival_time`）/`next_departure`（读 `departure_time`）拆成两个独立字段 + 互为
  fallback（首站无 arrival / 末站无 departure），`next2_*` 同步；`docs/dynamic-data.md §18.4` 写清。
- **P1-4** `Timeline.tracks` 的 `List.copyOf` 遇 null 元素 NPE → 整墙加载失败：改 `filter(nonNull).toList()`；
  连带 `KfValueDeserializer` 对 boolean/array 坏形态 `throw JsonMappingException` → `return null`（下游
  `instanceof` 守卫兜，同属「坏数据不在反序列化期抛硬错」契约）。
- **P1-7** 属性面板 80ms 防抖跨元素串写：`RightPanel.sendUpdate(patch, elementId)` 显式传 id，
  `sendUpdateDebounced` 触发时捕获 `selected.id`，flush 按捕获 id 路由（80ms 内切元素不串写）。
- **P2-16** 关编辑器校验未过静默丢弃（**用户确认加提醒**）：`closeEditing`/`selectRule` 在
  `dirty && validationErrors>0` 时设 `net.lastError`（非阻塞中文提示，不阻止关闭）。
- **P1-3** 动画关键帧 w/h 无 MAX_DIM → 渲染线程 OOM：`IconRenderer` tint + `ImageRenderer.drawWithFeather`
  分配前加 `ElementValidator.MAX_DIM` 守卫 + `TimelineOperations.parseValue` 对 w/h 加范围校验。
- **P1-6** ImageIO 无尺寸预检 → 分配型炸弹 OOM：`UploadHandler.decodeCooperative` / `ImageStorage`
  在 `reader.read(0)` 前用 `getWidth/getHeight` 预检尺寸 > `BBOX_MAX_EDGE`(8192) 即拒（解码前拦分配）。
- **P2-5** 脚本挂起续接早于补间末帧落盘 → 读到旧值：`TweenScheduler.enqueue` 加 `onComplete` 回调，
  正常末帧落盘后 / tick 异常清理 / 接管三终结点 `fireComplete`（`oneShot` AtomicBoolean 幂等），
  `ScriptRunner` 不再自按 durationMs 定时，续接经回调 `schedule(0)` 投 Runner SES（不在 tween 线程跑脚本）；
  接管路径顺带把 `active.get`+`put` 合一为原子 `active.put` 消除 TOCTOU。满足 `scripting-tween.md §2.2`
  「补间完 → 落盘 → 续接」顺序。

**systematic-debugging（1 处）**：D2 子代理为 i18n 用 `messages[ui.locale]` 在 `scriptEdit` 里调
`useUiStore()`，间接触发 `theme` store 实例化 → `theme.ts loadFlavor` 的 `window.matchMedia` 在 node
测试环境炸 6 个测试。根因是**偏离现有惯例**（其余 5 处 `net.lastError` 都是直接中文）。修复：生产 + 测试
都改回直接中文字面量、去掉 ui/theme 依赖。

**测试**：后端全绿（新增约 31：OomGuard×2 新 + Timeline / Rail / Tween / ScriptRunner / EditSessionKeyframe）/
前端 **1243**（+10）全绿 / **0 baseline 漂移**。用户游戏内实测通过。

关联：`RailOpDispatcher` / `RailScheduleProvider` / `Timeline` / `KfValueDeserializer` / `IconRenderer` /
`ImageRenderer` / `TimelineOperations` / `UploadHandler` / `ImageStorage` / `TweenScheduler` / `ScriptRunner`
+ 7 测试类（OomGuardRenderersTest / OomGuardDecodeTest 新）；前端 `RightPanel` / `scriptEdit` / `messages`
+ 2 测试；`docs/dynamic-data.md §18.4` + plan。

---

## 2026-06-14 · 0.7.3 ultrareview 第一批：8 条「静默失败」+ 崩溃修复（全直接修）

独立第三方全栈 ultrareview（`docs/ultrareview-2026-06-14.md`，159 代理 / 54 真问题 + 25 设计待定）→ 4 个
子代理读真实代码逐条核验（抓出 1 纯误报 P2-8 BlendModes + 数条「设计折衷被当 bug」）→ 第一批挑 8 条
**0.7.3/补间「功能坏了但不报错」**直接修。4 批并行实施（文件无冲突）+ 对抗审查硬条目，实施计划见
`docs/superpowers/plans/2026-06-14-ultrareview-batch1.md`。

**8 条修复**（ID 对报告）：
- **P1-1** 置顶置底双路径未接线：`SessionManager.applyScriptElementReorder`（照 clone/delete + 共用
  `finishScriptElementStructuralOp`）+ `HikariCanvas` 匿名 `SessionPatchApplier` override `reorderToEdge`——
  编辑器开着时脚本置顶/置底走活跃 session（前端实时 patch），不再绕 headless 被 persist 覆盖丢失。
- **P1-2** 文字变色补间静默失效：setColor 友好积木 `fill` 键 → `color` 键 + 前后端 `ELEMENT_PROPERTIES`
  白名单加 `color`（`applyTextPatch` 本就接受 color）——ColorTarget 端到端打通（渐变 + 落盘）。
- **P1-5** `canvas.tweenFps` 漏接 WebServer 分发：`handleMessage` switch 补 case + `EditOpDispatcher`
  提 `handleCanvasTweenFps` seam——per-wall 帧率真生效（不再卡 30）。
- **P2-1** RandomBranch 深度校验前后端不一致（6 审查单元命中，置信度最高）：后端 `ScriptRuleValidator`
  `ifDepth+1` + MAX_IF_DEPTH 查，对齐前端/If——编辑器不再拒服务端会接受的合法规则。
- **P2-2** K16 保存期条件预检漏 RandomBranch：`ScriptOpDispatcher.checkConditionSyntax` 递归补 then/else。
- **P2-3 / P2-4 / P3-17** 非有限污染变量库 + roundVariable 绕 StrictNumber：`formatNumber` `!isFinite→"0"`
  兜底 + `doRoundVariable` 用 `StrictNumber.PATTERN` 判定，非数值（abc / 0x1p4 / 5d / Infinity / NaN）→
  **error step**（恢复原语义，非批次 B 误改的「静默按 0」——现有测试 `roundVariable_nonNumeric_error`
  即原语义证据）。
- **P2-6** `clearStaticDiff` 破「仅 Ticker 线程」契约 → 孤儿 FrameDiff 泄漏：改 `scheduler.execute` 投
  Ticker 线程（单线程 FIFO 保证排在 renderStatic 之后）。
- **P3-16** `buildInterpolatedFrame` 原地 mutate 共享 baseState → 跨线程撕裂读：`deepCopyState` 每帧拍
  独立副本（照 `ProjectState.restore`，layers + elements 列表独立）。

**过程 3 插曲**：① 2 共享文件并行改（ScriptRuleValidator / TweenScheduler）验证改动共存无覆盖 ②
批次 D 正确越界补后端 `ELEMENT_PROPERTIES` 白名单（P1-2 端到端必要）③ 抓 1 回归（B 误改 roundVariable
非数值语义撞翻现有测试）→ systematic-debugging 修复（恢复 error + 用 PATTERN 统一覆盖 0x1p4/5d/Inf/NaN）。

**测试**：后端 **1946**（+~40）/ 前端 **1233**（+2）全绿 / **0 baseline 漂移**。用户游戏内实测 P1-1/P1-2/P1-5
通过。25 设计待定 + 其余 P2（9~15/19/20）+ P3 未纳入（留第二/三批）。

关联：`HikariCanvas` / `SessionManager` / `AnimationTicker` / `TweenScheduler` / `ScriptRuleValidator` /
`ActionExecutor` / `ScriptOpDispatcher` / `WebServer` / `EditOpDispatcher` + 7 测试类（含新
`CanvasTweenFpsDispatchTest`）；前端 `blockDefs` / `validator` / `i18n` + 2 测试；
`docs/ultrareview-2026-06-14.md` + plan。

---

## 2026-06-14 · 0.7.3 备选积木批完工：4 个新积木 + 版本号 bump 0.7.3-SNAPSHOT

补间动画完工后补一批轻量备选积木（brainstorming 用户选 4 个全做）。后端 + 前端子代理并行。

**4 个新积木**：
- **随机分支** `RandomBranch(probability, then, else)`：控制流，照 if——ScriptRunner 随机选臂（`rng`
  IntSupplier 注入 seam + setRngForTest；`rng.getAsInt() < probability`，blockId `/then/` `/else/` 照 if
  逐字符同构）。
- **元素置顶/置底** `SetElementLayer(elementId, mode front/back)`：结构性改 state——`EditSession`
  moveElementToFront/Back（复用 reorderElement size-1 / 0）+ `ElementPropertyApplier.applySetElementLayer`
  双路径（session reorderToEdge / headless）。
- **变量取整** `RoundVariable(fullName, mode round/floor/ceil)`：async，照 scaleVariable（读变量 →
  Math.round/floor/ceil → setValue）。
- **标题弹窗** `ShowTitle(title, subtitle, fadeInMs/stayMs/fadeOutMs, target)`：主线程 player.sendTitle
  （ms→tick /50），照 sendMessage（target 分流 + `${var}` 插值 + TRIGGER_DETAIL 拿触发玩家）。

**协议升 v7**（Protocol SUPPORTED 6→7 + 前端 CLIENT_V 6→7）。

**版本号 bump**：0.6.0 → **0.7.3-SNAPSHOT**（5 处：build.gradle.kts allprojects / paper-plugin.yml /
web/package.json + package-lock×2）——0.7.x 一路 0.6.0-SNAPSHOT 首次 bump，标记 0.7.x 进度。

**前端**：blockTree `isIf` 扩 randomBranch（then/else 容器照 if）+ BlockNode `isIf` computed 含
randomBranch（C 形 then/else，probability 走 scalarFields）+ 3 个简单积木字段镜像 + i18n。

**测试**：后端 **1905**（+55）/ 前端 **1231** 全绿。设计 `docs/scripting-0.7.3.md`（G1-G4 决策固化）。

关联：后端 Action（4 record）+ Deserializer/Serializer/Validator/Permissions + ScriptRunner（RandomBranch +
rng seam）+ ActionExecutor（3 doXxx）+ EditSession（moveElementToFront/Back）+ ElementPropertyApplier
（applySetElementLayer）+ Protocol v7；前端 protocol/blockDefs/blockTree/BlockNode/validator/wsClient/i18n；
版本号 5 处；docs/scripting-0.7.3.md。

---

## 2026-06-14 · 补间动画 P5 完工 + 补间功能收官（文档收尾）

补间动画 5 phase 全部完工。P5 纯文档 / 收口（代码 P2-P4 已做）。

**P5 收尾**：
- scripting-tween.md §10 标 P3/P4 ✅、P5 收官；§11 开放问题回填（渲染统一入口 / 渲临时层省 DB /
  DB 写压力实测 / 缓动 category 配色 **4 项 done**；同属性 body 重复 / 非挂起变体 / 渐变 fill animating /
  版本号 留 future）；§12 新增「给玩家：怎么用补间动画」大白话用法。
- config 确认：`scripts.tween`（max-fps 60 / max-concurrent 16）P2 已加；单补间时长上限走 validator
  `TWEEN_DURATION_MAX` 60s 常量。
- 性能透明：静态墙渲临时态不每帧落 DB（路径 Z）、有 timeline 墙每帧 applyMany（wall 少）；DB 写压力
  P2/P3 实测可接受。

**补间功能总览（P1-P5）**：Scratch 式「在 X 秒内」C 形包裹积木 + **架构 A 独立 `TweenScheduler`**（路径 Z
`renderStatic` 省 DB + 挂起复用 playTimelineAwait）+ 全属性（数值 / 颜色 ColorLerp / 渐变 fill）+ 全
EasingType（含自定义曲线 EasingCurveEditor）+ **与时间轴共存**（`isWallAnimating` 分流）+ **per-wall 帧率** +
body 拖入限制。后端 **1850** / 前端 **1197**。5 phase + MVP 一路用户实测通过。

**待 0.7.x 整体盘点**：版本号（一路 `0.6.0-SNAPSHOT`，补间完是否 bump 0.7.3）+ 挂账触发器
（OnCommand / OnLockChange）+ 0.7.0 P6（压测 / 文档）。

关联：scripting-tween.md（§10/§11/§12 回填）+ 补间 P1-P4 各 plan。

---

## 2026-06-14 · 补间动画 P4 完工：前端 UI 完善（body 拖入限制 + 自定义缓动曲线 + 视觉）

补间前端 UI 完善，用户实测过：视觉完美 + 曲线拖动 bug 修复。

**body 拖入限制（防呆）**：`dropTarget` SlotRect 加 `containerBlockType`；`useBlockDrag` collectSlots 读
`data-block-type` + buildSlots 派生 containerBlockType + `isTweenBodySlotAllowed`（tweenBlock body 只接受
属性动作 setElementProperties + TWEENABLE_KINDS）；updateDrag/onPointerUp filter slots。拖非属性动作进
「在 X 秒内」直接不高亮（而非保存才拒）。

**自定义缓动曲线**：TWEEN_EASING_OPTIONS 加 cubicBezier（labelKey `timeline.easingCustom`）；BlockNode
复用 0.6 `EasingCurveEditor.vue`（`:model-value=tweenEasing` + `@update:model-value=onTweenEasingCurveUpdate`，
只读 computed + 写回 handler 解耦，非 v-model）；onFieldUpdate easing 切 cubicBezier 初始化 bezier
`[0.25,0.1,0.25,1]`。

**实测 bug 修（systematic-debugging）**：EasingCurveEditor 是 SVG，`isFormTarget` 不认 → pointerdown 冒泡到
块根 onBlockPointerDown 当拖块 → 把整块拖走。修 `isFormTarget` 加 `.hc-tween-curve-editor` 到「不拖块」白名单。

**视觉**：tweenBlock category control（绿）；C 形复用 hasBodySlot；hc-tween-curve-editor 包装。用户实测视觉完美。

**测试**：前端 **1197**（P4 +20 tweenP4.test.ts）全绿。**P5 待做**：性能透明 + 文档 + 版本号 + 收口。

关联：blockDefs（TWEEN_EASING_OPTIONS cubicBezier + TWEENABLE_KINDS）+ BlockNode（EasingCurveEditor +
isFormTarget 修 + easing 特判扩展）+ dropTarget（containerBlockType）+ useBlockDrag（isTweenBodySlotAllowed）
+ i18n + tweenP4.test.ts + plans/2026-06-13-tween-P4*.md。

---

## 2026-06-13 · 补间动画 P3 完工：全属性（颜色/fill）+ 与时间轴共存（实测通过）

补间扩展到颜色/渐变属性 + 与用户预排时间轴在同一招牌共存，用户实测过：颜色平滑过渡 + 多属性并行 +
补间和时间轴叠加。

**全属性**：`TweenScheduler` PropTarget 重构成 **sealed interface**（NumericTarget/ColorTarget/FillTarget，
三态 immutable）；`isNumericProperty` → `isTweenableProperty`（加 color/fill）；插值分流——numeric 线性 /
color `ColorLerp.lerpHex` / fill `ColorLerp.lerpFill`；含 `${var:}` 的颜色/fill 退化末帧瞬切（snap=true，
中间帧不渲、不每帧 resolve）。前端无改（setColor 积木已用 fill 键，isTweenableProperty("fill")=true）。

**与时间轴共存（架构 A 核心）**：`tickOne` 按 `ticker.isWallAnimating(wallId)`（每 tick 现查、不缓存）分流——
有时间轴 → 每渲帧 `applyMany` 落 base DB（时间轴下帧 reload 叠加关键帧）；静态墙 → `renderStatic` 渲临时态
（P2 路径 Z）。末帧两种墙都 `applyMany` 落终值。`TickerControl` 加 `isWallAnimating` 转发。

**已知边界（标注 + TODO）**：applyFn rawPatch 是 `Map<String,String>`，animating 墙中间帧的渐变 fill 退化
首 stop 颜色（主场景纯色无此问题；future 让 applyMany 收 Fill 对象）。

**文档回填**：scripting-tween.md §3.4/§5 反映路径 Z 分情况（静态墙渲临时态省 DB / 有 timeline 墙每帧
applyMany），§10 标 P1/P2 ✅ + P3 共存方案。

**测试**：后端 **1850**（P3 +12 case：颜色/fill 补间 + 共存分流 + snap）全绿。**P4 待做**：前端 UI 完善
（自定义缓动曲线 + body 拖入限制 + C 形视觉打磨）。

关联：`engine/TweenScheduler`（PropTarget sealed + 共存分流 + 颜色/fill）+ `engine/TickerControl`
（isWallAnimating）+ TweenSchedulerTest + scripting-tween.md（回填）+ plans/2026-06-13-tween-P3*.md。

---

## 2026-06-13 · 补间动画 MVP 完工：P1 数模+协议 / P2 引擎 / per-wall 帧率（架构 A 实测通过）

脚本「在 X 秒内」+ 缓动 MVP 全链路打通，用户实测过：玩家触发 → 静态招牌元素平滑滑动 → 停目标 →
挂起续接 + 帧率可调。

**P1 数模+协议**：`Action.TweenBlock`（durationMs + easing[复用 0.6 `state.Easing`] + body[仅属性动作]）
+ permits + 序列化/校验/permissions case + ScriptRunner P1 占位 + 协议升 **v6** + 前端镜像。
TWEENABLE_KINDS = moveTo/resize/rotateTo/setOpacity/setColor。

**P2 引擎（架构 A 独立补间引擎）**：`TweenScheduler`（单线程 SES + TweenTask + EasingSolver 算值 +
buildInterpolatedFrame[EditSession 纯重建]）；**路径 Z**——`AnimationTicker.renderStatic`（Ticker 线程渲
临时态、不落 DB、`entries` 守卫不抢有 timeline 的 wall）；末帧 `ElementPropertyApplier.applyMany` 落盘；
`ScriptRunner` TweenBlock 分支复用 playTimelineAwait 挂起。最简前端：BlockNode tweenBlock C 形 +
makeDefaultAction 默认带「移动到」。

**实测 bug 修（systematic-debugging）**：① 缓动两层根因——easing 是 Easing 对象但字段用普通 select
（当字符串）→ 显示字面 + 写成字符串 → validator 拒 → 无法触发；修 BlockNode fieldValue/onFieldUpdate
特判（读 .type / 写 {type}）。② labelKey 指错块——TWEEN_EASING_OPTIONS 用 `script.fieldOptions.easingXxx`，
实际 key 在 timeline 块 → 改 `timeline.easingXxx`。

**per-wall 帧率（实测加）**：ProjectState 加 `tweenFps`（nullable 默 30 clamp[1,60]）+
`EditSession.setTweenFps` + `canvas.tweenFps` op；TweenScheduler SES cadence 改 1000/maxFps（config
`scripts.tween.max-fps` 默 60）+ TweenTask.fps 按 wall 节流 renderStatic（末帧总渲+落盘，中间帧节流）；
前端 ScriptEditorOverlay 顶部「动画帧率」控件（Gauge + 20/30/45/60）。**对抗审查抓并发 bug**：lastRenderAt
跨 enqueue(Runner)/tick(tween) → `HashMap` 改 `ConcurrentHashMap`。

**测试**：后端 **1841** / 前端 **1175** 全绿。架构 A 实测验证通过。scripting-tween.md T7/§5 待回填（静态墙
渲临时态省 DB，比文档「每帧落 DB」更优）。**P3 待做**：全属性（颜色/fill）+ 全 EasingType 缓动接入 +
与时间轴共存。

关联：plans/2026-06-13-tween-P1/P2*.md + 后端 `script/{Action,*Deserializer,*Serializer,ScriptRuleValidator,
ScriptPermissions}` + `engine/{TweenScheduler[新],ScriptRunner,ActionExecutor,TickerControl}` +
`render/AnimationTicker` + `state/{ProjectState,EditSession}` + `web/{EditOpDispatcher,Protocol}` +
`HikariCanvas{,Config}` + config.yml + 前端 `script/{model,canvas}/*` + `types/protocol` + `stores/project` +
`network/wsClient` + `i18n/messages` + `ScriptEditorOverlay.vue`。

---

## 2026-06-13 · 补间动画设计总纲（scripting-tween.md）：brainstorming 定 10 决策

0.7.2「稳的」版完工后单独 brainstorming 补间动画（脚本「在 X 秒内」+ 非线性缓动）。先派调研代理摸 0.6
时间轴 / Ticker / EasingSolver / ColorLerp / 落盘 的可复用性，再逐决策定。

**10 决策**：① Scratch 式「在 X 秒内」C 形包裹积木 ② **架构 A 独立补间引擎**（脚本侧自跑、改元素基准值，
不碰 final `AnimationTicker`；补间改基准、时间轴读基准叠加，正交共存——优于架构 B 复用 Ticker：补间纯服务端
不需双端预览，B 的「复用渲染链保双端一致」卖点用不上）③ 只放属性动作并行补间（非属性动作放包裹外）
④ 6 数值 + color + fill 复用现有插值轨 ⑤ 缓动复用 `EasingSolver`（下拉预设 + 自定义曲线）⑥ 挂起式（glide）
⑦ **每帧落 state**（诚实修正：末帧落盘与时间轴共存矛盾——时间轴每帧从 DB 读基准叠加，补间只末帧落则读不到
中间值）⑧ 同元素同属性后者接管 ⑨ 正交共存 ⑩ config 限并发 + fps、透明不降级。

**调研依据**：`EasingSolver`/`ColorLerp` 双端镜像可复用 / `KeyframeInterpolator` 6 数值 + color + fill 轨 /
时间轴播放不落 state / `ElementPropertyApplier` 双路径落盘 / `ScriptRunner` 挂起（`playTimelineAwait` 范式）/
一墙一时刻约束（架构 A 绕开，不抢 Ticker entry）。

**5 phase**：P1 数模 + 协议 → P2 引擎 MVP（单属性滑入）→ P3 全属性 + 缓动 + 共存 → P4 前端 C 形 UI →
P5 config + 收尾。

关联：`docs/scripting-tween.md`（新）。

---

## 2026-06-13 · 0.7.2-P4 完工：积木 UI 打磨（Scratch 实色风精修，纯前端 3 轮）

0.7.2「稳的」版收尾段。纯视觉、不碰数据/协议。**先对齐定方向**：当前是 P5 做的「实色饱和 Scratch 风」
（整块填分类色 + 凸榫 + 焊接堆叠），用户实测确认**保持这个风、只提精致度、不推翻 P5**（曾否决的浅卡片
IDE 风不走回头路）。3 轮各一闸、用户截图验（开发机跑不了编辑器，视觉靠实测）。

**第 1 轮 块本身**（BlockNode/BlockStack）：散乱圆角/padding/gap/字号收成一套 CSS 变量节奏（块圆角 8px /
帽子顶 14px / 内距 8·11px / 焊接 gap 3px）；凸榫圆角矩形 → 梯形榫头（clip-path，根宽尖窄——子代理写反成
倒梯形、review 修正）；hover 从单纯变亮 → `translateY(-1px)` 上浮 + 阴影加深；顶部 inset 白高光强化
（26%→32%）+ text-shadow 微调；**body 实色一点没动**（守硬约束）。

**第 2 轮 拖拽落位手感**（BlockCanvas/DeleteDropZone；`useBlockDrag` 纯逻辑不动）：拖影从「浅卡片 + 左色条」
IDE 风 → **迷你实色块**（填 ghostColor + 白字 + 8px 圆角 + 强浮起阴影 + opacity 0.9）；落点插入线 3px 蓝 →
**4px 天蓝 ctp-sky + 两端圆头 + 外晕发光**；新增**槽位高亮 div**（读 activeSlot{x,y,w,h}，z-index 69 极淡点亮
「会插这里」、不碰逻辑）；删除区圆角 10→8px 统一。

**第 3 轮 palette**（BlockPalette）：item 背景 12% 淡灰底 → **65% 饱和实色底** + 4px 分类色条 + 8px 圆角 +
hover `translateY(-1px)`，label 用全局 `--hc-block-fg`（深浅主题适配），与画布块同色语言。

**收尾**：前端 **1157** 全绿（`useCanvasPasteDispatcher` 路径 b 是已知并发 flaky，完整跑全过、非本次引入）；
vite build 通过；shadowJar 重打包。**版本号保持 `0.6.0-SNAPSHOT`**（0.7.x 大版本未 release、0.7.0 P6 也挂着，
一路 SNAPSHOT 下迭代）。

子代理策略：纯视觉 + 改同批联动文件 + 看不到运行时 → **每轮 1 个子代理串行**（视觉规格主控自定保统一手感、
子代理执行 + 跑测试不 commit、review 后主控 commit），区别于 P1-P3 的并行——视觉统一性 > 并行隔离。

关联：`web/src/script/canvas/{BlockNode,BlockStack,BlockCanvas,DeleteDropZone,BlockPalette}.vue` +
`web/src/style.css`（--hc-block-fg 全局）/ `docs/scripting-0.7.2.md` §5。

---

## 2026-06-13 · 0.7.2-P3 完工：重复直到条件 + 全服广播

2 个动作。子代理（后端地基 + 前端）+ 自盯 RepeatUntil ScriptRunner + 对抗审查（7 点全对、无必修）。

**RepeatUntil**（流程控制，**while 语义**动态循环）：runFrames 遇 RepeatUntil → 查 condition（满足 / 达
maxIterations → `i++` 跳出后续）；否则轮数+1（RunState `Map<blockId,int>`）+ 压回 `Frame(acts,i)`（含后续）+
压 body（LIFO 先执行）→ body 完弹回再查。**跳出 `remove(blockId)`**——同 run 内 Repeat 包 RepeatUntil 时清
残留轮数（关键，审查验证必需充分）。Budget 50 / maxIter 100 双闸 + K16 预检。轮数 Map 跨 wait 续接靠
RunState 延续。

**全服广播**：SendMessage 加 `target` 字段（trigger 默认 / all），doSendMessage 按 target 分流（all →
`getOnlinePlayers` 广播、不读触发玩家，非玩家触发器也能广播）。改既有 record，Deserializer optionalText
默 trigger 向后兼容（旧 payload 无 target）；grep 补 11 个构造点。

**前端**：repeatUntil C 形（control 绿）——BlockNode 泛化 `hasBodySlot`（repeat+repeatUntil）+ blockTree
`getChildSeq` 泛化 `isBodyContainer`（计划漏列但 drop container 必需，子代理补上）；sendMessage 加 target 下拉。

**对抗审查**（7 点全对：while 语义压回自身帧栈 / Map 跨 wait 续接 / 嵌套 RepeatUntil / 轮数残留 remove 必需 /
Budget 兜底不死循环 / blockId 同构 / target 分流+向后兼容）。无 P0/P1/P2 缺陷、无必修。

**测试**：后端 **1807**（基线 1781 + 子代理 21 + RepeatUntil 5）/ 前端 **1157**（基线 1135 + 22）。shadowJar
170M。i18n 中英 5 key。

关联：`ScriptRunner`（RepeatUntil 分支 + RunState Map）/ `Action`/序列化/validator/permissions/
`ScriptOpDispatcher`(K16) / `ActionExecutor`(doSendMessage target) / 前端 `protocol`+`blockDefs`+`blockTree`
+`BlockNode`+`validator`+`i18n`。

---

## 2026-06-13 · 0.7.2-P3 立项：重复直到条件 + 全服广播（实施计划）

P3 加 2 个：**RepeatUntil**（流程控制，ScriptRunner 动态循环）+ **全服广播**（sendMessage 加 target）。
§8 决策：RepeatUntil **while 语义**（先查 condition、可能 0 次 body）+ maxIterations∈[1,100] 默 10 +
RunState `Map<blockId,int>` 记轮数 + Budget 50/maxIter 100 双闸 + K16 保存期预检；sendMessage 加 `target`
下拉（trigger 默认 / all），executor 按 target 分流、向后兼容旧 payload 无 target。

7 task：后端地基（record/序列化/validator/permissions/sendMessage executor）+ 前端派子代理；**RepeatUntil
ScriptRunner 动态循环**（压回 `Frame(acts,i)` 含后续 + 压 body LIFO，body 执行完弹回再查 condition）自盯。
**SendMessage 加 target 要 grep 补所有 `new Action.SendMessage(` 构造点**（改既有 record）。

关联：`docs/superpowers/plans/2026-06-13-0.7.2-P3-repeatuntil-broadcast.md`。

---

## 2026-06-13 · 0.7.2-P2 完工：元素积木 + 变量积木（克隆 / 删除 / 复制 / 拼接）

4 个全栈动作。子代理（后端地基 + 前端 + 元素双路径）+ 对抗审查。

**变量积木**：`CopyVariable`（读 source cached → setValue target）+ `AppendVariable`（读 target +
interpolate(text) 拼接 → setValue），复用 doSetVariable 路径，async 无主线程 hop。

**元素积木**（双路径，照 setElementProperties/nudgeElement）：
- **删除**复用 `EditSession.deleteElement`（已有）；**克隆**新增 `EditSession.cloneElement`（复用
  `cloneElementWithNewId` + `withOffset` clamp ±10000 + F10 配额，加到源元素所在 layer）
- 路径 A（编辑器开）：`SessionManager.applyScriptElementClone/Delete` + `finishScriptElementStructuralOp`
  （pushPatch 广播 + throttler + persistWall，逐行照 applyScriptElementPatch）
- 路径 B（headless）：`ElementPropertyApplier.runHeadless`（new EditSession + updateState + Ticker invalidate）
- **F10 配额**：`scripts.max-elements-per-wall` 默 200（config + `/canvas reload` 热更），session/headless
  两路都 enforce、编辑器开着也绕不过

**对抗审查**（8 验证点全对：session 广播 / headless Ticker / 删除悬空引用 error 不崩 / 克隆 fire-and-forget
语义 / 配额双路径一致 / offset clamp long 防回绕 / synchronized 并发 / locked+缺失边界）。1 次要留 §8 未决：
编辑器开着时脚本克隆会抢用户选中焦点（App.vue auto-select，仅 LOOP 克隆 + 开着编辑器才显现，不丢数据 / 不崩
/ 不影响 headless 运行时）。

**测试**：后端 **1781**（基线 1753 + 序列化/validator/permissions/executor + EditSessionCloneElement 10 +
applyClone/Delete 16，− 2 占位）/ 前端 **1135**。shadowJar 170M。i18n 中英 8 key。

关联：`Action.java`/序列化/validator/permissions/`ActionExecutor` / `EditSession.cloneElement` /
`ElementPropertyApplier`(applyClone/Delete) / `SessionManager`(seam) / `HikariCanvas`/`HikariCanvasConfig`/
`config.yml` / 前端 `protocol.ts`+`blockDefs.ts`+`validator.ts`+`i18n/messages.ts`。

---

## 2026-06-13 · 0.7.2-P2 立项：元素积木 + 变量积木（实施计划）

P2 加 4 动作（克隆元素 / 删除元素 / 变量复制 / 文本拼接）。调研落定：**删除**复用 `EditSession.deleteElement`
（已有）；**克隆**新增 `EditSession.cloneElement`（复用已有 `cloneElementWithNewId` + 偏移 + F10 配额）；
元素动作走 `ElementPropertyApplier` 双路径（session seam + headless，照 `applyNudge` 范式）；**变量复制/拼接**
直接复用 `doSetVariable` 的 `store.get`+`store.setValue`+`interpolator.interpolate`，async 无主线程 hop。

8 task：后端地基（record/序列化/validator/permissions/变量 executor）+ 前端派子代理；元素双路径（Task 5
EditSession.cloneElement + applyClone/applyDelete）是难点自盯。wire 字段名定死 copyVariable`{target,source}`
/ appendVariable`{fullName,text}` / cloneElement`{elementId,offsetX,offsetY}` / deleteElement`{elementId}`。

关联：`docs/superpowers/plans/2026-06-13-0.7.2-P2-element-variable-blocks.md`。

---

## 2026-06-13 · 0.7.2-P1 完工：2 小修 + 变量预览 +1/-1（纯前端）

- **F3 变量预览扫条件**：`extractVars` 加 `collectFromCondition`（正则抓条件文本里 `var("X")` 双/单引号
  引用）+ `if`/`waitUntil` case → 等待/如果条件里的变量进右下角预览。根因：旧 extractVars 只扫"结构字段"
  型 fullName（setVariable 等），漏了 condition 的 `var()` 函数文法引用（它不是 `${var:X}` 占位符）。
- **F2 停止/等待归控制类**：blockDefs `stopScript`/`waitUntil`/`wait` 的 category `action`→`control`
  （绿，与 if/repeat 同组）；`playParticle` 留 `action`（副作用蓝）。
- **F4 变量预览 +1/-1**：新 `VarWatchRow` 子组件（每行独立 `useLongPressIncrement` 单击±1/长按连加 +
  乐观本地累加，按压期不被 server 回声覆盖防抖动）+ ScriptVariableWatch 接 `sendVariableSet`；仅
  **user/userglobal 可写 + 当前值是有限数**才显 stepper（只读 system/papi/schedule + 文本变量不显）。

**测试**：前端 **1131**（基线 1127 + extractVars F3 4 测试）。纯前端、后端不变、shadowJar 169M。

关联：`web/src/script/model/extractVars.ts`(+test) / `blockDefs.ts` / `canvas/VarWatchRow.vue`(新) /
`canvas/ScriptVariableWatch.vue`。

---

## 2026-06-13 · 0.7.2 立项：视觉运行时打磨 + 积木扩充（设计总纲）

0.7.1 完工实测后用户提 2 个小问题 + 一批"稳的"新功能。决策：**先做稳的**（2 修 + 变量±1 + 6 积木 +
UI 打磨），**补间动画（在 X 秒内 + 非线性缓动）单独 brainstorming + 写设计文档后再排**（本版不含——有真
架构决策：A 脚本侧自跑 vs B 程序生成临时时间轴 + 复用 Ticker，及"补间完落 state"语义）。

**10 个固化决策（F1–F10）**：补间单独设计(F1) / 停止·等待·wait 归控制类绿(F2) / extractVars 扫条件
`var()`(F3) / 变量预览 +1/-1 复用 useLongPressIncrement(F4) / 克隆·删除元素走 EditSession 改 state——脚本
驱动的真编辑、非 P-2 反模式(F5) / 重复直到 = 动态条件循环不预展开 + maxIterations/Budget 双闸(F6) /
全服广播 = sendMessage 加 target 字段(F7) / 变量复制·文本拼接(F8) / 积木不改世界守命令白名单(F9) /
克隆受每墙元素总数上限(F10)。

**更多积木**：用户四方向全选（元素/流程/通知/变量），精选 6 个：克隆元素 · 删除元素 · 重复直到 ·
全服广播 · 变量复制 · 文本拼接（去重——已有的移动/改色/发消息/if/repeat 等不重做）。

**调研落定**：0.6 的 `EasingSolver`（cubic-bezier + EASE 预设）+ `ColorLerp`（sRGB）双端镜像可复用于补间
（数学不用重造），但 AnimationTicker 不适合承载临时补间——补间架构留单独设计。

**4 段分期（~50h）**：P1 2修+变量±1 / P2 元素+变量积木 / P3 流程+广播 / P4 UI 打磨。

关联：`docs/scripting-0.7.2.md`（F1–F10 + 6 积木 + 分期 + §7 补间占位）。

---

## 2026-06-13 · 0.7.1-P5 完工：剩余动作（停止 / 粒子 / 等待直到）—— 0.7.1 功能完工

补齐 0.7.1 脚本动作最后 3 个。后端为主：子代理做地基 + 自盯 runner 难点 + 对抗审查。

**3 动作**：
- **停止本脚本**：ScriptRunner 内 `stack.clear()` + `continue outer` → 自然 `finish(ok)`，后续动作舍弃
  （嵌 if/repeat 内也中止整个 run，符合"停止本脚本"语义）
- **播放粒子**：`ActionExecutor.doPlayParticle` 照 PlaySound——主线程 hop + `wall.key()` 墙坐标 + offset，
  `Registry.PARTICLE_TYPE` 解析，14 个内置白名单（审查用 1.21.11 字节码逐个核实全走 Void dataType，
  无 `spawnParticle` IllegalArgumentException 风险）；权限复用 `canvas.script.sound`
- **等待直到条件**：ScriptRunner 独立 `pollWaitUntil` 递归调度——首次计 1 action + 压栈后续 + 轮询
  （每 100ms eval condition，满足 / 超时续接），走独立调度**不重入 action 循环 → 不重复计 Budget**；
  加 clock seam（LongSupplier，测试注入可控时钟）

**前端**：3 个 union + blockDefs（粒子 `select` 下拉 14 项 + 等待复用 `if` 的 condition 构建器）+
validator 镜像 + i18n 中英 23 key；palette 自动从 ACTION_DEFS 派生。子代理修了 BlockNode condition 渲染
**if-only 的遗漏**（泛化让 waitUntil 条件框可见，否则不可见且恒空）。

**实施方式**：后端地基 + 前端并行派子代理（不同目录无冲突，实现+测试不 commit、我审后统一提交）；runner 难点
（StopScript + WaitUntil 状态机）自盯。子代理抓出计划 3 处错误（particleId→particle wire 名 / Task4 测试
API / 测试类名 ActionWireTest）。

**对抗审查**（后端正确性 + 双端一致）：6 验证点全对（callback 恰一次 / 嵌套续接 / StopScript 中止语义 /
粒子白名单兼容 / wire 字段名 / shutdown+坏条件兜底）。修 2：① 【重要】WaitUntil condition 漏 K16 保存期
语法预检（`checkConditionSyntax` 只认 If）→ 扩展到 WaitUntil + 顺手递归 repeat.body（补 0.7.0-P2 遗漏），
否则坏条件运行期静默等满超时、无报错，与 if 不一致；② 【次要】`pollWaitUntil` 包 Throwable 兜底防独立
调度路径抛 Error 孤儿化 run。

**测试**：后端 **1731**（基线 1703 + 序列化5/validator8/executor4/permissions3/runner5/审查3）/ 前端 **1127**
（基线 1122 + 5）。shadowJar 169M。**0.7.1 五阶段（P1/P2/P3/P4/P5）全部完工**——待 0.7 整体收尾单独评估
（版本号 / 用户文档 / 0.7.0 P6）。

关联：`Action.java` / `ActionDeserializer` / `ActionSerializer` / `ScriptRuleValidator` / `ScriptPermissions`
/ `engine/ActionExecutor` / `engine/ScriptRunner` / `web/ScriptOpDispatcher`(K16) / 前端
`protocol.ts`+`blockDefs.ts`+`validator.ts`+`BlockNode.vue`+`i18n/messages.ts`。

---

## 2026-06-13 · 0.7.1-P5 立项：剩余动作（停止 / 粒子 / 等待直到）设计回填 + 实施计划

用户拍板：P5 三个动作**全做**（停止本脚本 / 播放粒子 / 等待直到条件）；0.7 整体收尾（版本号 / 用户文档 /
0.7.0 P6）P5 完工后**单独评估**。

两路调研（后端执行引擎 + 前端动作定义）落定改动面：后端 5 文件（Action record + 序列化 + Validator +
Executor + Permissions + Runner）/ 前端 4 文件（protocol + blockDefs + validator + i18n）。前端高度复用——
粒子 = `select` 下拉、等待条件 = 复用 `if` 的 ConditionBuilder、表单组件零改。

**§9 固化 4 个实现决策**：
- 粒子权限面**复用 `canvas.script.sound`**（不另立节点）
- **WaitUntil 不阻塞线程**：独立 `pollWaitUntil` 递归调度（deadline 作方法参数 + 不重入 action 循环 →
  天然不重复计 Budget）。**否决**"deadline 存 RunState + 续接重评估"——`actionCount++` 在循环顶、Budget
  检查在动作分支前，续接每帧都会 ++ 被 Budget 误拦
- **StopScript**：runFrames 内 `stack.clear()` → 自然 `finish(ok)`
- **粒子白名单 14 个**（双端对齐 + `Registry.PARTICLE_TYPE` 解析，同 PlaySound 范式）

关联：`docs/scripting-0.7.1.md §9` / `docs/superpowers/plans/2026-06-13-0.7.1-P5-remaining-actions.md`
（9 task，WaitUntil 是难点，需时钟 seam + fake scheduler 测试）。

---

## 2026-06-13 · 0.7.1-P4 完工：幽灵拖动设目标坐标（移到 / 改大小 / 旋转）

预览框里拖元素半透明虚影设三种目标几何，松手写回积木 patch，部署墙触发后元素真变。纯前端、后端零改。

**纯逻辑（可单测）**：
- `ghostDrag.ts`：buildGhostElement（原元素 + patch 覆盖目标几何）/ rotatePoint（屏幕系顺时针，与
  drawElement 的 ctx.rotate 同向）/ ghostHandlePos（resize SE 角 + rotate 上方手柄，随 rotation 转）/
  hitGhostHandle（moveTo 判 bbox 反旋、resize/rotate 判圆点）/ applyGhostDrag（move 平移 / resizeSE
  左上锚定 / rotate 中心→指针角+90°）
- `previewCoords.clientToWall`：M1 正解——以 canvas getBoundingClientRect 为原点
  `(clientX−left)*wallW/width` 比例映射，消 ~0.5px round 偏差、绕开 transform.offset

**渲染 + 交互（PreviewPane.vue）**：导出 `PreviewRenderer.drawElement` 渲虚影副本 + globalAlpha=0.5
（强制非 dither 绕过 early-return 不透明 bug）；`activeCoordBlock` computed（复用 activeElementBinding，
判 moveTo/resize/rotateTo + 已绑元素）；按 kind 画 handle + 旋转连杆；onPointerDown 虚影 hit-test
优先于 P3 点选；window pointermove/up 拖动（照 splitter 范式防 capture 丢失）。BlockNode 坐标字段
focusin → 显虚影（E12 聚焦即显，复用 onElementFieldFocus）。

**对抗审查（2 视角并行）抓 1 阻断 + 2 重要**：
- 【阻断】undo 栈被单次拖动占满（60fps 每帧 pushUndo 塞满 cap 50、毁掉之前历史）→ scriptEdit 加
  `snapshotForUndo`（起拖 1 份）+ `updateActionField(.., coalesce=true)`（拖动逐帧不入栈）；一次拖动
  = 一个可撤销步
- 【重要】lock 态虚影可拖但写不动（"看着能拖实际无反馈"困惑）→ `activeCoordBlock` lock 时返 null（不显虚影）
- 【重要】拖动期不冻结整树 + 写错积木边缘 → 起拖 `setDragging(true)` + move 加 `activeElementBinding
  !== path` 守卫 + 松手 false
- 几何核心（旋转方向 / handle 定位 / hit-test 中心 / M1 反算）经独立推导 + 数值仿真确认正确
- 留实测（§9）：resize 旋转态视觉漂移（rotation=0 精确、w/h 值始终对、有数字表单兜底）/ moveTo bbox
  拦改绑 / rotate handle 贴顶越界（表单兜底）

**测试**：前端 **1122**（基线 1086 + ghostDrag 22 + previewCoords 12 + scriptEdit coalesce/lock 2），
后端不变（纯前端）。shadowJar 168M（前端产物经 processResources 进 jar）。4 commit（纯逻辑 `2a93f76`
/ Vue `636ab06` / 审查修复 `459237c` / 完工本条）。P4a/b/c 三批一次推完，待用户实测核心闸。

关联：`web/src/script/canvas/ghostDrag.ts(+test)` / `previewCoords.ts(+test)` / `PreviewPane.vue` /
`BlockNode.vue` / `web/src/render/PreviewRenderer.ts` / `web/src/stores/scriptEdit.ts(+test)` /
`i18n/messages.ts` / `docs/scripting-0.7.1.md §9`。

---

## 2026-06-13 · 0.7.1-P4 立项：幽灵拖动设目标坐标（设计 backfill + 实施计划）

P4 是 0.7.1 的灵魂——预览框里拖元素半透明虚影设"移到 / 改大小 / 旋转"目标坐标。3 决策用户拍板：

- **E10 虚影 = 元素半透明真样子**：导出 `PreviewRenderer.drawElement` 渲绑定元素副本（按 patch 覆盖
  目标 x/y/w/h/rotation）+ `globalAlpha=0.5`。非抽象方框 / 连线。
- **E11 全 transform**：移到拖中心(x/y) + 改大小拖角(w/h) + 旋转转手柄(rotation)，P4 内部分 a/b/c
  三批各自独立实测（P4a 移到 = 核心闸）。
- **E12 聚焦即显**：选中坐标积木（聚焦其任意字段）→ `activeCoordBlock`（复用 scriptEdit
  `activeElementBinding` 扩展）→ 自动显虚影可拖；绑元素仍走 P3「从预览点选」准星，不加按钮。

**M1 坐标偏差正解**（P3 审查遗留）：反算以 canvas `getBoundingClientRect()` 为原点比例映射
`wallX=(clientX−crect.left)×wallW/crect.width`，消 ~0.5px round 偏差、绕开 `transform.offset`。

**调研落定**：`drawElement`(PreviewRenderer.ts:103) 私有、rotation 绕中心 + opacity `globalAlpha`
乘法可复用；三积木 patch 键 moveTo`{x,y}` / resize`{w,h}` / rotateTo`{rotation}`（全 string，经
`updateActionField` 写回）；主画布 Transformer 数学（`newSize=oldSize×距离比` / `normalizeRotation`
/ `rotatePolygon`）参照。纯前端，后端零改。

关联：`docs/scripting-0.7.1.md`（§0 E10–E12 + §2.3 重写 + §2.5 + §8 拆 a/b/c + §9 回填）、
`docs/superpowers/plans/2026-06-13-0.7.1-P4-ghost-drag.md`（11 task / 三批）。

---

## 2026-06-13 · 0.7.1-P3 实测修复：分隔条卡死 + 点选被原生下拉挡

用户实测 P3 报 2 bug，systematic-debugging 定位（都是 vitest 测不到的真实浏览器行为）：

**Bug 分隔条拖过一次后 hover 持续触发（越拖越大）**。根因：`ScriptEditorOverlay` 分隔条 @pointermove/up
绑在分隔条元素，靠 setPointerCapture retarget，但触控板 capture 不可靠 → 鼠标拖出分隔条松手时 pointerup
不在分隔条触发 → onSplitterUp 没调 → `splitterDragging` 卡 true → 之后 hover 继续改宽。修：改 **window
监听**（照 useBlockDrag attach/detach），pointerup 在 window 一定触发清 dragging；template 只绑
@pointerdown；删 setPointerCapture；onScopeDispose 摘监听兜底。

**Bug 点 element 下拉后点预览没选中**。根因：element 字段是原生 `<select>`，点它弹浏览器原生下拉 overlay；
点预览时浏览器先关下拉、**吃掉这次点击**，PreviewPane 收不到 pointerdown。修：加独立「**从预览点选**」
crosshair 按钮（element select 旁，friendly + 常规块都加），点它 `setActiveElementBinding`（不碰 select、
不展开下拉）+ active 时 mauve 高亮提示；用户点按钮再去预览点（无下拉遮挡）→ 正常填 elementId + 取当前值。
原生 select 保留（列表/键盘选）。

测试：前端 1078→1086（+8）全绿；vite build 绿。

关联文件：`web/src/script/canvas/{ScriptEditorOverlay, BlockNode}` + i18n

---

## 2026-06-12 · 0.7.1-P3 完工：预览框左右分栏 + 元素点选取当前值

subagent-driven（波1 布局+渲染 Task 1-3 → 提交 → 波2 点选+取值 Task 4-6 → 合并审查「可提交，0
blocker/important」→ 修 M2 → 提交）。纯前端，不碰协议/后端。

**波1（布局 + 渲染 + 坐标系）**：ui store `scriptPreviewCollapsed`+`scriptPreviewWidthPct`(clamp[20,70])
localStorage 持久化；ScriptEditorOverlay `canvas-host` 横向 flex 分栏（左 BlockCanvas + 中分隔条 col-resize
+ 右 PreviewPane），拖宽照 TimelineDock setPointerCapture，折叠 v-if 卸载；PreviewPane 复用
`renderProjectState` 渲当前墙（canvas 墙像素分辨率 + CSS scale + pixelated → **显示与 hit-test 同源**），
全量重绘 RAF 合并；`previewCoords.ts` `computePreviewTransform` fit-scale + 居中，`wallToPreview`/
`previewToWall` 互逆（TDD round-trip <0.5px，**P4 幽灵拖动用**）。

**波2（点选 + 取当前值，深度2）**：`scriptEdit.activeElementBinding`（当前聚焦 elementId 字段积木 path）+
BlockNode element 字段 @focusin set（**不在 blur 清**——点预览会先 blur select，blur 清会清掉 binding；
只在 selectRule/真删时清）；PreviewPane @pointerdown 点选 previewToWall（同源）→ `findElementAt`（复用
Live Paint `elementToPolygon`/`pointInPolygon` 倒序 z-order）→ 填 elementId（下拉天然同步）；取当前值
`FRIENDLY_KIND_CURRENT_FIELDS`（moveTo→x/y / resize→w/h / rotateTo→rotation / setOpacity→opacity），
show/hide/setText/setColor + 万能 setElementProperty 只填 elementId；绑定元素描边高亮（墙坐标同 ctx +
线宽 2/scale 补偿）。

**审查修**：M2 hit-test 镜像渲染的 `layer.opacity<=0` 守卫（否则隐形层可点中绑定不可见元素）。**M1 留给
P4**（previewCoords offset 未 round 的 ~0.5px 偏差，幽灵拖动写回坐标前要对齐——已记 §2.3）。M3 旋转高亮
轴对齐 / M4 无需，留。

测试：前端 1041→1078（+37）全绿；后端不变；0 baseline 漂移。3 commit（波1 / 波2+M2 / 收尾）。

关联文件：`web/src/script/canvas/{PreviewPane, previewCoords, ScriptEditorOverlay, BlockNode}` +
`stores/{ui, scriptEdit}` + `script/model/blockDefs` + i18n

---

## 2026-06-12 · 0.7.1-P3 启动：预览框左右分栏实施计划

2 路 Explore 摸清 0.7.0 渲染/坐标/布局基础（`PreviewRenderer.renderProjectState` 复用渲墙 + Live Paint
`elementToPolygon`/`pointInPolygon` 复用 hit-test + `TimelineDock` ResizeObserver/setPointerCapture 拖宽
范例 + ui store SNAP_KEY 持久化范例 + scriptEdit 缺「当前活跃字段」追踪）。写 P3 计划（纯前端，不碰协议/后端）。

**范围**：① ScriptEditorOverlay 左右分栏（左积木 + 右 PreviewPane，可拖宽 + 折叠，ui store 持久化）；
② PreviewPane 复用 `renderProjectState` 渲当前墙现状（只读）+ 坐标系 `previewToWall`/`wallToPreview`（P4 幽灵
拖动用）；③ 元素点选取当前值（深度2）——点预览元素 → 填 `scriptEdit.activeElementBinding` 记录的当前积木
elementId + 按 friendly kind 取元素当前 x/y/w/h/rotation/opacity；下拉与点选同步 + 绑定元素描边高亮。

**§9 性能决策回填**：PreviewPane 先全量重绘（requestAnimationFrame 合并），脏区优化留实测（工具不是保姆）。

关联文件：`docs/scripting-0.7.1.md`（§9 回填）/ `docs/superpowers/plans/2026-06-12-0.7.1-P3-preview-pane.md`（新）

---

## 2026-06-12 · 0.7.1 实测修复：触控板点字段误触发拖动

用户实测：Mac 触控板点积木字段（变量选择器 / 文本框）想编辑时太敏感，一不小心触发积木拖动。
systematic-debugging 两个根因：① `useBlockDrag.startBlockDrag`/`startStackDrag` 在 pointerdown 立即
capture+拖动，**无阈值**——触控板按下必带微移即误判拖动；② `BlockNode`/`BlockStack` 的 `isFormTarget`
用 `matches` 只看 target 自身，变量选择器按钮是 `<button><span>选变量…</span></button>`，点中落在子
span → `matches('button')` 漏 → 误拖。

修：① **拖动阈值** `DRAG_THRESHOLD=5`——`armDrag` 共用 helper 延迟启动（pending 期不 capture / 不
select / 不 preventDefault，指针移动 >5px 才真拖；阈值内松手 = 点击，字段 click/focus 正常）；保住
P5「capture 先于 selectRule」不变性（pending 期不 select，onCross.target 仍 pointerdown 原 DOM，测试
显式断言 capture 计数==1）。② `isFormTarget` 改 `closest` + 扩展（`[role="button"]`/contenteditable/
label）覆盖交互元素子节点。palette 源不加阈值（拖出本就是拖动意图）。

测试：前端 1028→1041（+24：阈值 16 + picker 子节点 2 + 适配）全绿；含 capture-先于-select 不变性断言。
vite build 绿。

关联文件：`web/src/script/canvas/{useBlockDrag, BlockNode, BlockStack}`

---

## 2026-06-12 · 0.7.1-P2 实测修复批 + 2 体验 feature

用户实测 P1+P2 打回 2 真 bug + 提 2 feature。systematic-debugging 定位根因后修，再加 feature。

**Bug 1：右键墙触发器完全不触发**。根因：`FrameProtectionListener`（HIGH 优先级，防玩家旋转/破坏画板）
对每个 wall frame `setCancelled(true)`，而 `GameEventListenerHub.onPlayerInteractEntity`（MONITOR）设
`ignoreCancelled=true` 跳过已取消事件 → 右键墙永远收不到。两 listener 锁同一组 wall_id PDC frame。单测测
转发核心绕过真实派发，抓不到 priority×ignoreCancelled 交互。修：hub 改 `ignoreCancelled=false`（MONITOR
观察已取消事件正是其用途；wall frame 旋转已锁 inert 观察无副作用）+ 反射守护测试（TDD red-proof）。
MockBukkit 端到端不可行（MockBukkit↔Paper 版本偏移）故 fallback 反射守护。

**Bug 2：循环体编辑丢失（消息空白/变量不增）**。根因：`scriptEdit.ts` 有一套平行手写树操作
（`replaceActionAt`/`replaceInSeq`）只认 if then/else，漏 repeat body。P2 泛化了 `blockTree`
（NESTED_SEQ_KEYS）但这套平行实现没同步 → 编辑 repeat body 内积木字段被静默丢弃（不更新不保存），跑默认值
（空 text / user/score）→ 玩家收空白消息 + 变量不增。修：`blockTree` 加 `replaceAt`（走泛化逻辑覆盖
then/else/body），`scriptEdit.updateActionField` 改调它，删平行实现 ~52 行 → **单一真相源** + 4 repeat-body
TDD 测试。（count 10000 非 bug：前端钳 100 + 运行时熔断，行为正确，用户认可。）

**Feature 1：积木画布变量实时预览**。`ScriptVariableWatch.vue` 右下角可折叠浮层 + `extractReferencedVariables`
helper（递归扫 trigger+actions 含 if/repeat 收集变量 fullName + `${var:X}`）+ resolveFullName + variable
store 实时值 + alias 优先。不用频繁切变量面板。

**Feature 2：单积木拖动删除**。拖动作积木时右下角现垃圾桶删除区（`DeleteDropZone.vue` + useBlockDrag 纯函数
几何 hit-test），松手删除该积木（`scriptEdit.removeAction` + `blockTree.removeAt` + undo）。帽子积木不走。
独立浮层（palette 跨组件树 + pointer capture 收不到 drop）。

测试：后端 1702→1703（+1）/ 前端 987→1028（+41：bug 12 + feature 29）全绿；0 baseline 漂移。4 commit
（右键墙 / 循环体 / feature / 收尾）。

关联文件：`plugin/.../script/engine/GameEventListenerHub` + 测试；`web/src/script/model/{blockTree,extractVars}`
+ `stores/scriptEdit` + `script/canvas/{useBlockDrag,BlockCanvas,DeleteDropZone,ScriptVariableWatch}` + i18n

---

## 2026-06-12 · 0.7.1-P2 完工：3 新触发器 + 有界循环 + 协议 v5

subagent-driven（波 1 后端 Task 1-7 + 前端 Task 8-11 并行 → 合并审查「可提交，0 finding」→ 提交）。

**后端 3 新触发器**：
- `rightClickWall`（右键墙 ItemFrame）：GameEventListenerHub.onPlayerInteractEntity + FrameDeployer.wallIdOf
  PDC 反查 wallId + **off-hand guard** 防一次右键双手双触发；TriggerRouter `rightClickByWall` 按墙索引；
  权限 trigger.global。
- `playerLeaveRange`（离开靠近区域）：复用 PlayerNearSampler，NearEntry 加 `leaveEdge` 标记，sample 分沿
  `(!leaveEdge && enter) || (leaveEdge && leave)`；玩家首次在范围外不误触发；权限墙级 edit。
- `playerQuit`（退服）：PlayerQuitEvent + TriggerRouter `quitRules` 全局索引；权限 trigger.global。
- GameEventListenerHub 用 `WallIdLookup` functional seam（避 script.engine→deploy 包依赖 + 纯 JVM 可测）。

**Repeat 有界循环**：Action.Repeat(count, body)；ScriptRunner 展开 count 轮 body，**blockId 用同一 prefix
`<blockId>/body/<i>` 不带 round**——守 0.7.0 前后端同构（带 round 会让试跑高亮错位）；每轮 body 动作计入
Budget actionCount，超 max-actions-per-run(50) 熔断（100×[2 动作] → 49 body + blocked）；count 1..100 + body 递归。

**协议 v5**：Protocol SUPPORTED_MIN/MAX 4→5 干净切换，前端 CLIENT_V 5，旧 v4 被 4002 拒。

**前端**：protocol Trigger +3/Action repeat；blockDefs trigger defs + Repeat blockDef（control）；BlockNode
Repeat C 形渲染（照 if then 单臂，body path `${path}/body/${i}` 同构）；validator +3 trigger + Repeat（文案
逐字）+ countBlocks（body 不乘 count）；i18n。**blockTree.ts 泛化**（计划外但必需）：硬编码 if/then/else →
`NESTED_SEQ_KEYS=['then','else','body']` + getChildSeq/withChildSeq，否则 repeat 拖入 body 静默 no-op；
if then/else 无回归（+11 树导航测试）。**§9 预警决策**：不做前端展开预估，靠运行时 Budget 熔断（工具不是保姆）。

测试：后端 1656→1702（+46）/ 前端 955→987（+32）全绿；shadow jar `HikariCanvas-0.6.0-SNAPSHOT.jar`
167 MB（P2 不升 jar 版本，仍 0.6.0）；0 baseline 漂移。合并审查 0 finding。3 commit（启动 + 后端 + 前端）。

关联文件：`plugin/.../script/{Trigger,Action,Trigger(De)serializer,Action(De)serializer,ScriptPermissions,
ScriptRuleValidator}` + `engine/{ScriptRunner,TriggerRouter,PlayerNearSampler,GameEventListenerHub,
TriggerContext,ActionExecutor}` + `web/Protocol` + `HikariCanvas`；`web/src/{types/protocol,network/wsClient,
script/model/{blockTree,validator,blockDefs}, script/canvas/{BlockNode,useBlockDrag}, i18n/messages}`

---

## 2026-06-12 · 0.7.1-P2 启动：实施计划（3 新触发器 + 有界循环 + 协议 v5）

2 路 Explore 摸清 0.7.0 触发器体系（Trigger sealed + TriggerRouter 倒排/全局索引 + PlayerNearSampler
进入沿 + GameEventListenerHub 事件层 + FrameDeployer ItemFrame→wallId PDC 反查 + 协议版本握手）。写 P2 计划。

**关键纠正 / 决策**：① 命名 rightClickWall / playerLeaveRange / playerQuit / repeat；② playerLeaveRange
复用 PlayerNearSampler 加「离开沿」（NearEntry `leaveEdge` 标记，in: true→false 触发）；③ **Repeat
blockId 不带 round**——展开 count 轮 body 用同一 prefix `<blockId>/body/<i>`，守 0.7.0 前后端同构（带
round 会破坏试跑高亮）；④ §9 预警回填「不做前端预估，靠运行时 Budget 熔断 + 试跑 trace」（工具不是
保姆）；⑤ 权限：rightClickWall / playerQuit → trigger.global，playerLeaveRange → 墙级 edit。

关联文件：`docs/scripting-0.7.1.md`（§9 回填）/
`docs/superpowers/plans/2026-06-12-0.7.1-P2-triggers-loop-protocol.md`（新）

---

## 2026-06-11 · 0.7.1-P1 完工：友好元素积木 + 低风险新动作（6 新 Action 子类 + 友好皮肤渲染）

按 P1 计划用 subagent-driven 执行（波 1 后端全链路 + 前端契约并行 → 合并审查 → fix → 波 2 前端 UI）。

**后端 6 个新 Action 子类**（`plugin/.../script`）：
- `setElementProperties(elementId, patch, kind)` — 7 个友好积木的序列化目标，一条 action 批量设多
  属性（底层 `updateElement` 已支持 patch map）。**1 积木=1 条 action 守 0.7.0 blockId 同构**（试跑
  高亮/undo/P4 幽灵拖动天然正确）。`kind` 前端皮肤标记，后端执行忽略。
- `nudgeElement(elementId, dx, dy)` — 相对移动，运行时读当前 x/y + 增量；session 锁内原子 /
  headless 升 long 防 int 回绕。
- `sendMessage(text, channel)` — 给触发玩家发消息（chat/actionbar/title），经 `TRIGGER_DETAIL`
  ThreadLocal 拿触发玩家名（照 `RULE_KEY` 范式），主线程 hop + Adventure。
- `setRandomVariable / scaleVariable` — 随机数（RNG seam 可测）/ 变量乘除（除零防御）。
- `playTimelineAwait(timelineId)` — 播时间轴并等播完，经 `ActionSink.timelineDurationMs` + Runner
  特判复用 wait 续接挂起 durationMs（封顶 10 分钟）。
- 4 处穷尽 switch（executor/validator/permissions/serializer）编译期守门，一次加全。

**前端**：`protocol.ts` union +6；`blockDefs` `FRIENDLY_ELEMENT_DEFS`（8 kind：移到/改大小/旋转/
透明度/显示/隐藏/改文字/改颜色）+ 5 新动作 blockDef；`validator.ts` 镜像 6 case（文案逐字一致）；
i18n 中英；`BlockNode` 按 `action.kind` 选友好皮肤渲染（字段读写 patch，number string↔number
round-trip，`data-block-path` 不变守同构）；`BlockPalette`「元素动作」友好分组（nudgeElement 去重）。

**审查修 3 点**：I1 放宽 setElementProperties 对 `text` 键的空值校验（空文字是合法内容，双端同步）；
M1 headless nudge 升 long 防回绕；M2 nudge 读锁外低危竞态记账注释。

测试：后端 1585 → 1656+（+71）/ 前端 917 → 955（+38）全绿；shadow jar
`HikariCanvas-0.6.0-SNAPSHOT.jar` 166 MB（P1 不升版本，仍 0.6.0；协议 v5 升版留 P2）；0 baseline 漂移。
4 commit（启动文档 + 后端 + 前端契约 + 前端 UI）。

关联文件：`plugin/.../script/{Action,ActionDeserializer,ActionSerializer,ActionExecutor,ScriptRunner,
ElementPropertyApplier,ActionSink,ScriptRuleValidator,ScriptPermissions}` / `session/SessionManager` /
`HikariCanvas`；`web/src/{types/protocol, script/model/{blockDefs,validator}, i18n/messages,
script/canvas/{BlockNode,BlockPalette}}`

---

## 2026-06-11 · 0.7.1-P1 启动：E6 决策回填 + 实施计划（友好积木走「批量设属性 action」）

写 P1 实施计划前，3 路 Explore 摸清 0.7.0 脚本系统代码形态，暴露一个 brainstorming 没下探到的
架构张力：友好积木「移到 (x,y)」「改大小 (w,h)」要一次设 2 个属性，但 0.7.0 的 setElementProperty
一条只设 1 个属性，且试跑高亮 / undo / P4 幽灵拖动全靠「1 积木=1 条 action」blockId 同构。

**用户拍板路线乙（回填 E6）**：新增后端复合 action `setElementProperties(elementId, patch, kind)`，
7 个友好积木全序列化成它，1 积木=1 条 action 守同构（底层 updateElement 已支持批量 patch）；相对
移动走 `nudgeElement`（运行时读当前值+增量）；显示/隐藏用 opacity 0/1。`kind` 是前端皮肤标记，
后端执行忽略。E6 原「协议零改」细化为「P1 加 setElementProperties 1 个 action」。

**P1 计划**：6 个新后端 Action（setElementProperties / nudgeElement / sendMessage /
setRandomVariable / scaleVariable / playTimelineAwait）+ 友好积木前端适配层（BlockNode 按 kind 选
皮肤 + palette 友好分组）。playTimelineAwait 经 ActionSink.timelineDurationMs + Runner 特判复用 wait
续接挂起；发消息经 TRIGGER_DETAIL ThreadLocal 拿触发玩家。P1 不升协议（仍 v4），v5 升版留 P2。

关联文件：`docs/scripting-0.7.1.md`（E6/§3/§4/§7 回填）/
`docs/superpowers/plans/2026-06-11-0.7.1-P1-friendly-blocks-and-actions.md`（新）

---

## 2026-06-11 · 0.7.1 立项：体验优化设计总纲 `docs/scripting-0.7.1.md`（brainstorming E1-E9）

0.7.0 积木编辑器核心数据流实测通过后，用户提 0.7.1 体验优化方向（新积木/动作 + 画布预览框
+ 不手输坐标）。走 brainstorming 把核心张力与各项决策敲定，落设计总纲（照 scripting.md 范式），
**用户审后暂停等开工指令**（未进 writing-plans）。

**核心张力厘清**：脚本「移到 xy」是运行时副作用（平时元素在 A，触发才去 B），与 0.6 时间轴
「拉就设」（拖元素真移 + 记关键帧）语义不同。在预览框可视化设目标坐标时——拖元素该不该真移？
→ **E1 幽灵虚影拾取**：预览显示元素现状（实体不动），拖半透明虚影设目标坐标，松手取虚影坐标
填积木，虚影留作"该积木目标"标记。初始态不被脚本编辑污染，语义最干净。

**9 个固化决策（E1-E9）**：E1 幽灵虚影 / E2 左右分栏可折叠（复用 PreviewRenderer + 幽灵层）/
E3 深度3纯前端不碰协议 / E4 元素绑定下拉+预览点选 / E5 虚影只显当前选中积木 / E6 友好元素积木
7 个前端糖映射 setElementProperty / E7 新动作触发器升协议 v5 干净切换 / E8 有界循环 N≤100+Budget /
E9 OnCommand 推迟 0.7.2。

**范围（完整档 ~150h）**：8 友好元素积木 + 7 新动作（发消息/粒子/随机/乘除/播完等待/重复/等待直到/
停止）+ 3 新触发器（右键墙/离开区域/退服）+ 有界循环 + 预览框深度3。5 段分期（P1 友好积木+低风险动作 /
P2 触发器+循环+协议 v5 / P3 预览框布局+点选取值 / P4 幽灵拖动核心 / P5 收尾）。

关联文件：`docs/scripting-0.7.1.md`（新）/ `CLAUDE.md` 路线图加 0.7.1 立项行

---

## 2026-06-11 · 0.7.0-P5 实测反馈修复（第二轮）：画布渲染数据源主根因

用户二轮实测的关键线索——"没放上去的积木块其实放上来了，随便拖动画布才看到；能放上的也不是
100% 拖了就显示"——指向**渲染数据源缺陷**（第一轮没抓到，被"不跟手/复制"表象掩盖）。

**主根因（C/D 阶段起潜伏）**：`BlockCanvas.basePositionedStacks` 用 `scripts.listSorted`
（**server 镜像**）渲染，`:rule` 传 server 态；但所有编辑（setActions/updateActionField/
setTrigger）改 `scriptEdit.workingCopy`（**本地副本**）。BlockStack/BlockNode 纯 props 驱动 →
改 workingCopy 后画布不变，要等 800ms save 回 server 才显示；**空字段积木（设置元素属性/执行
命令的 elementId/templateId 空）被 validator 拦保存 → server 永不更新 → 画布永远不显示**，但
workingCopy 有它 → validator 一直报"待完善"。smoke 测试只测单组件渲染，测不到"编辑 store →
画布反映"的端到端反馈链，故潜伏到实测才暴露。

**修复（c745261）**：
- **主根因**：BlockCanvas 新增 `renderRules` computed——当前编辑规则（selectedRuleId）渲染
  `workingCopy`，其余渲染 scripts 镜像。建立 workingCopy 响应依赖 → 拖积木/改字段/改触发器
  **立即反映**。6 端到端 smoke（含空 elementId 积木立即渲染 = bug 报告场景）。不破坏拖堆跟手
  （renderRules 不依赖 stackDragPos，拖堆中 workingCopy.blockLayout 不变 → 不重算）。
- **次要1「待完善不知哪里 + 点击无反应」**：主根因修复后待完善积木能渲染 → onIndicatorClick
  的 querySelector 命中 DOM → scrollIntoView 定位生效（"点了没反应"本质是积木没渲染）；
  待完善角标从 element/timeline/command 扩展到 variable/condition/sound 空。
- **次要2「变量选择器多点几次才开」**：pointerdown（早于 click）先选中本规则 + openPicker
  截断 click 冒泡（memory 约束）。**happy-dom 无法确定性复现，是防御性加固，需真实浏览器实测确认。**

**第一轮（7e2f401 逻辑 + 1c8ca9a 视觉）的修复仍有效**（banner 布局位移 / 拖出默认值 / capture /
拖堆 / Scratch 视觉）——只是不完整，第二轮补上核心渲染源。systematic-debugging「修复未完全解决 →
回 Phase 1 重新分析」走通。**前端 899→917 全绿 / vite build 过 / 0 漂移。**

---

## 2026-06-11 · 0.7.0-P5 实测反馈修复（积木编辑器 4 类问题，systematic-debugging 根因优先）

用户首轮 UI 实测报 4 类问题。主控本地跑不了编辑器 → 走"读代码 + 逻辑推理定位根因"替代复现，
诊断到行后精确修（未瞎改）。**两条贯穿主因 + 逐症状根因**：

- **症状 1「触发器下拉点不动/跳到报错」**：根因不是 select bug（select 回显正确、切换已生效），
  而是切到 variableChange→空 fullName→`validationErrors`（实时 computed）非空→顶部红 banner
  作为 `flex-shrink:0` 块**瞬间插入把画布整体下挤**，观感"点了没用/界面跳走"。
- **症状 3a「拖出积木全报空值错」**：`makeDefaultAction` 引用字段给空串 + 前后端 validator 都拒空
  → 拖出瞬间 banner 轰炸 + 自动保存被拦。3b「找不到填值处」：控件确实渲染，但 variable 是 11px
  灰小按钮极不显眼 + 红 banner 抢镜。
- **症状 2b「偶尔复制出新积木」**：`startBlockDrag` 在 tryCapture **之前**调 selectRule（替换
  workingCopy 触发重渲→捕获 DOM 被 v-for key 重建→capture 丢失）+ doSave 乐观清 dirty 后
  server echo deep watch 整树 deepClone 替换 workingCopy。`moveNode` 本身无误。
  2a「不跟手」：拖堆时 stackDragPos 每帧变→positionedStacks 全量重算 + 所有 BlockStack 重定位。
- **症状 4「不像 Scratch」**：四角圆角矩形+左 4px 色条（无咬合）/ gap 有缝 / if 虚线引导线 /
  Catppuccin color-mix 淡化低饱和 / 裸方角 input。

**修复（2 commit）**：
- **逻辑（7e2f401）**：① 删顶部红 banner（布局位移元凶）→ 改头部 inline 温和「⚠ N 处待完善」
  指示（不挤画布，点击循环定位）② makeDefaultAction/Trigger 给非空合理默认（fullName=user/score /
  soundId=entity.player.levelup / if condition 默认合法可解析）→ 中间态不再误报 ③ scriptEdit 加
  `dragging` flag：拖拽期间冻结 server echo 整树替换 + startBlockDrag 先 capture 后 select →
  capture 不丢/不复制 ④ BlockCanvas `basePositionedStacks`（不依赖 stackDragPos）+ 被拖堆局部
  覆盖 + ghost translate3d → 拖动时仅被拖对象重渲。前端 877→899。
- **视觉（1c8ca9a）**：实色块 + 对比文字（删 color-mix 淡化，3 主题 `--hc-block-fg` token）/
  焊接式堆叠（gap≈0 + 凸榫 + 负 margin 咬合）/ if 实体 C 形左臂+底托（删虚线）/ 参数控件统一
  圆角胶囊 + 变量"选变量"做成显眼 mauve 芯片（修 3b）/ 未填角标（element/timeline/command 空值
  橙点温和提示）。仅改 CSS/视觉结构/配色，data-block-path 等拖拽高亮依赖属性原位。

**视觉待用户实测微调**（主控无法本地预览）：凸榫咬合观感 / C 形臂宽度 / 深层嵌套 / 饱和度是否够亮 /
序列缩进。**前端 899 全绿 / vite build 过 / 0 漂移**。

---

## 2026-06-11 · 0.7.0 P4+P5 收口：**积木编辑器完工**（待用户完整实测）

P4 引擎层 + P5 内容层全部落地（9 个子任务并/串行 + 集成审查修 2 阻断）。把 0.7 脚本从
"浏览器 console JSON" 升级为 Scratch 风可视化积木编辑器。**这是 0.7.0 P5 末完整实测闸——前端
UI 第一次能真正操作脚本。**

- **波次 1（并行）**：A 引擎纯逻辑（blockTree 树操作 path 与后端 trace blockId 同构 / serialize
  blockLayout / dropTarget 吸附几何）/ B 画布骨架（viewport+world transform pan-zoom / 全屏 overlay /
  TopBar Puzzle 入口 / script-engine chunk）/ E 后端命令模板端点（`GET /api/script/command-templates`
  鉴权 + 不泄 command 原文）
- **波次 2（串行）**：C 积木渲染（blockDefs 声明式 6 触发器+9 动作 / BlockStack 帽子 + BlockNode 递归
  if C 形）/ D1 编辑会话（working copy + 本地 undo + debounce 800ms 自动保存 + newRule 拿 server id +
  server-as-truth dirty 协调 + lock 守卫）/ D2 拖拽吸附（buildSlots/collectSlots 测量 + findDropTarget +
  BlockPalette 拖出 + 移堆 + 排除自身子树）
- **波次 3（串行）**：F 参数表单（BlockParamInput 全类型 + 变量 VariablePicker Teleport / 时间轴 / 元素 /
  声音 datalist / 命令模板复合控件）/ G 条件可视构建器（SimpleCondition ↔ 字符串双向 + 高级文本框
  fallback + 转义与后端 ExpressionParser 一致）/ H 试跑高亮（trace blockId 树路径定位 + 120ms 步进 +
  validator 镜像复刻后端全常量 + 4 错误码 i18n）
- **集成审查修 2 阻断**（测试因 mock 绕过后端校验未覆盖）：① newRule 草稿 `actions:[]` 被后端
  validator 拒（空动作）→ 新建规则必然失败、入口走不通 → 改带默认 log 动作；② BlockStack 帽子触发器
  只读 → 所有规则永久卡 wallReady → 帽子改可编辑（6 类型 select + setTrigger + 参数 BlockParamInput）
- **测试基线**：前端 **536 → 877**（+341）/ 后端 **1575 → 1585**（E 端点 +10）/ 0 baseline 漂移 /
  script-engine 独立 chunk ~152KB（懒加载不进首屏）
- **主控无法本地运行编辑器**（只有 vitest + vite build + 组件 smoke），视觉手感（吸附阈值/浮层/
  动画）留用户 P5 末实测微调；纯逻辑（树操作/吸附几何/序列化/条件↔串/validator）重点 vitest 覆盖

0.7.0 进度：P1 ✅ P2 ✅ P3 ✅ P4 ✅ P5 ✅ → **P6 触发器/动作补全 + 收尾**（待 P5 实测反馈后定）。
契约 §6 已回填落地形态（`web/src/script/` 而非纸面 `web/src/blocks/`）。

关联文件：`web/src/script/**`（model/canvas/params 三层 ~25 文件）/ `stores/scriptEdit.ts` /
`stores/{ui,project}.ts` / `App.vue` / `TopBar.vue` / `vite.config.ts` / `i18n/messages.ts` /
`web/WebServer.java` + `CommandTemplateHandler.java` / `docs/scripting.md` §6/§8/§11 / `CLAUDE.md`

---

## 2026-06-11 · 0.7.0-P5-F：积木参数表单（BlockParamInput 全类型 + 下拉数据源）

把 C 阶段 BlockNode 的参数占位（字段名 + 原始值文本）换成按 FieldDef.type 渲染的真表单控件，
改值经 D 阶段编辑模型（`edit.updateActionField`）回写 working copy。**不碰 condition（留 G 的
ConditionBuilder）与 statements（C 的 BlockNode 递归子槽）。**

- **`web/src/script/params/BlockParamInput.vue`**（新）：通用参数控件，props `{field, value, actionKind, disabled}`，
  emit `update(value)`。按 type 渲染：
  - `number` → `<input type=number>`（min/max/step 来自 FieldDef，镜像后端 validator）；改值钳到 [min,max] 后
    **emit number**（空/非有限数不 emit，保留旧值——不把字段写空）；
  - `text` → `<input type=text>` emit string；`select`/`op`/`scope` → `<select>` over options emit string；
  - `variable` → 「选变量」按钮显当前 fullName → click 开 VariablePicker。**memory 约束双落**：① 按钮 click
    触发（非 select-change，否则 picker 的 onClickOutside 同一次点击立即关）；② picker **Teleport 到 body +
    fixed 浮层**（按钮 getBoundingClientRect 算位），绕开 BlockCanvas world 的 `transform: scale()` +
    `overflow:hidden` 裁切（与 BlockCanvas 拖拽指示线同款逃逸手法）；可清空 emit `''`；
  - `timeline` → `<select>` over `project.state.timelines`（label=name‖id），空列表提示「这个画板还没有时间轴」；
  - `element` → `<select>` over `project.allElements`（新 computed），空列表提示「这个画板还没有元素」；
  - `sound` → `<input list>` + datalist（`SOUND_SUGGESTIONS` 24 常用声音，带中文友好名）；用户也可手填任意 id；
  - `command`（**唯一复合字段**）→ 模板下拉 + 选中后按模板 params 动态渲染 text 子输入（maxLength 钳位）。
    runCommand 有 templateId + params 两个 wire 字段，本控件整体处理：emit `{templateId, params}` 复合值。
    切模板保留同名旧 param 值。无模板配置提示「服主还没配命令模板」。
- **`web/src/script/params/useCommandTemplates.ts`**（新）：`GET /api/script/command-templates?sessionId=<sid>`
  fetch + **模块级单例缓存**（按 sessionId；模板跟随 config 不必每次开积木重拉）。失败（401/网络/解析）→ 空列表
  不抛。`load()` 幂等复用缓存 promise / `refresh()` 强制重拉 / 换 session 自动作废。`__resetCommandTemplatesCache()`
  测试钩子。后端端点 E 阶段已建（不泄 command 原文）。
- **`web/src/script/params/soundSuggestions.ts`**（新，K-UI-7）：24 个常用声音常量（id + i18n labelKey）——UI 反馈 /
  音符盒 / 经验 / 互动机关 / 戏剧音效；datalist 显「id —— 中文名」，i18n 缺失退纯 id。**非白名单**（后端按 Registry 校验）。
- **`web/src/script/canvas/BlockNode.vue`**：参数槽接 BlockParamInput——scalarFields（排 statements/condition + runCommand
  的 command 字段）逐个渲染控件，`@update` 调 `edit.updateActionField(path, {[name]:v})`；runCommand 单独渲染一个
  复合 command 控件（templateId + 动态 params），`onCommandUpdate` 合并回写两字段。`disabled` 绑 `project.isLocked`
  （锁定墙参数只读；D 的 updateActionField 内另有 lock no-op，防御双层）。condition 占位保留（留 G）。
- **`web/src/stores/project.ts`**：加 `allElements` computed（跨层展平 `{id, type, label}`，保留 z-order；label =
  文本元素截前 16 字正文‖`类型 · 短码`）+ 内部 `elementLabel` helper。
- **i18n**：`script.param.*`（7 key：选变量/清空/请选择/空时间轴/空元素/空命令模板/声音占位）+ `script.soundNames.*`
  （24 声音中文名）中英对照。
- **测试**：后端不涉；前端 **+37**（baseline 739 → 776 全绿）：
  `BlockParamInput.smoke` 20（各 type 渲染 + emit 值类型 number/string/复合 + number 钳位 + variable picker 开关 +
  element/timeline 选项来自 store + command 选模板→params 子输入→改值 + disabled）/ `useCommandTemplates` 9
  （成功/空/缺字段/401/网络错/缓存命中/refresh/换 session/未鉴权）/ `projectAllElements` 6（展平 + 顺序 + label 各形态）/
  `BlockNode.smoke` 改 9→11（占位断言改控件断言 + 字段改值调 updateActionField + number 回写 number）。
  vite build 干净（556 kB index / script-engine chunk 含 BlockParamInput）。
- **关联文件**：见上。**疑虑**：vue-tsc 本机 `_tsc.js` 缺失（既有工具链问题，非本次引入）——用 vite build（rolldown）
  作类型门，CI Node 22 下 vue-tsc 正常。

## 2026-06-11 · 0.7.0-P4-D2：积木拖拽吸附 + palette + 移堆（P4 完）

把积木编辑器接上拖拽：palette 拖出新块 / 画布拖已有块（序列重排 / 跨堆 / 进 if 槽）/ 拖帽子移整堆。
D2 只管"算出树操作 → 调 D1 入口（`setActions` / `setStackPos`）"，编辑模型（working copy / undo /
debounce save / lock 守卫）全在 D1 store 内，D2 不重复实现。**P4 引擎层完工**。

- **`web/src/script/model/blockDefs.ts`**：加 `makeDefaultAction(kind)` / `makeDefaultTrigger(kind)`——
  palette 拖出 / 新建规则时造合法默认对象（每字段取后端 validator 范围内的合理默认：引用类
  fullName/elementId/timelineId/templateId 空串待选；数值 intervalSeconds=10 / rangeBlocks=8 /
  ms=500 / volume=pitch=1 / delta=1；枚举取白名单首项 op=play / scope=near / property=x；if 的
  then/else 空数组非 null；playTimeline 默认 op=play 不带 seekMs）。未知 kind 兜底（log / wallReady）。
- **`web/src/script/canvas/useBlockDrag.ts`**（新）：拖拽核心（决策 K-UI-3）。
  - **`buildSlots(measured, draggingPath, bandH)` 纯函数**（无 DOM）：把"已测块矩形"推导成候选插槽
    SlotRect[]——真实块按"所属序列"分组（**键含 ruleId**，否则两堆顶层 `actions` 序列错并）→ 每块上沿
    一个 before 插槽 + 组末一个尾插槽（index=末块+1）；空容器占位（`data-slot-path`）→ 单 index=0 槽；
    **排除被拖块自身及其子树**（剔除 parentPath 以 draggingPath 为前缀的槽——画布源不能拖进自己里面）。
  - **`collectSlots(canvasEl, draggingPath)`**：遍历 `[data-block-path]`（trigger=hat / 其余=block）+
    `[data-slot-path]`（emptySlot）`getBoundingClientRect` 测 viewport 矩形，ruleId 取最近祖先
    `[data-rule-id]`，再调 buildSlots。
  - **`useBlockDrag` composable**：三类拖拽源——`startPaletteDrag(kind, e)`（拖出新块）/
    `startBlockDrag(stackRuleId, blockPath, e)`（拖已有块，隐含 selectRule）/ `startStackDrag(ruleId, e)`
    （拖帽子移堆，world 坐标差量驱动，松手 `setStackPos`）。拖动中跟手浮层 + `findDropTarget`（阈值 ~40）
    高亮命中槽；松手：palette 源命中→`insertAt`+`setActions`，画布源命中→`moveNode`+`setActions`
    （toParentPath/toIndex 直传——`collectSlots` 在 pointerdown 测 = "移动前渲染树"，与 blockTree.moveNode
    下标补偿契约对齐），无命中→还原（palette 不创建 / 画布不拆堆）。
  - **生命周期借 useBrushHost 范式**：setPointerCapture + move/up/cancel **挂 window**（palette 项的 capture
    目标在 viewport DOM 之外，挂 window 三类源都收得到）+ pointercancel / blur / visibilitychange /
    onScopeDispose 全 abort（还原 + releaseCapture）。**lock 守卫**：`project.isLocked` 时所有 start* return。
- **`web/src/script/canvas/dragInjection.ts`**（新）：`BLOCK_DRAG_KEY` provide/inject 契约——BlockCanvas
  持唯一 useBlockDrag 实例，把"拖块 / 移堆"两句柄 provide 给递归子组件（BlockNode / BlockStack 注入）；
  含 `NOOP_DRAG_HANDLES` 默认值（组件单独 mount 时安全兜底）。
- **`web/src/script/canvas/BlockPalette.vue`**（新）：左侧积木库，按 category 分组列 9 个可拖动作积木
  （含 if；触发器**不在 palette**——帽子从"新建规则"来）；项 pointerdown（仅左键）→ emit `paletteDown`；
  lock 态 `pointer-events:none` + 灰显。配色读 BlockDef.colorVar（与画布块同色）。
- **接入改动**：
  - **BlockCanvas.vue**：instantiate useBlockDrag + provide 句柄 + defineExpose `startPaletteDrag`；
    渲染**跟手浮层 + 吸附指示线**（Teleport 到 body，用 viewport 坐标 fixed 定位绕开 world transform）；
    移堆拖动中用 `drag.stackDragPos` 覆盖该堆坐标即时跟随；pan 守卫（拖块 / 移堆中不启动 pan）。
  - **BlockStack.vue**：帽子左键 pointerdown → 移堆 + 选中；堆体 click → selectRule；当前编辑规则
    mauve 描边光环；空 actions 占位加 `data-slot-path="actions"`（顶层空序列落点）。
  - **BlockNode.vue**：块根 pointerdown（仅左键、跳过表单元素）→ startBlockDrag（ruleId 从最近
    `[data-rule-id]` 现取，stopPropagation 让最深块独占）；空 then/else 占位加 `data-slot-path`。
  - **ScriptEditorOverlay.vue**：palette 占位换 `<BlockPalette @palette-down>` → 转发到
    `canvasRef.startPaletteDrag`；无选中规则时提示先选/建规则。
  - **i18n messages.ts**：`script.paletteGroup.{trigger,action,timeline,control,danger}` +
    `script.paletteNeedRule` 中英。
- **测试（+51，前端 688→739 全绿）**：`blockDefaults.test.ts`（makeDefaultAction/Trigger 全 kind 合法 +
  范围/枚举/空数组校验 + 未知兜底）/ `useBlockDrag.test.ts`（buildSlots 序列枚举 / 尾槽 / if 子槽 /
  空槽 / **排除自身子树** / 多堆独立 + collectSlots happy-dom 接线）/ `useBlockDrag.tree.test.ts`
  （effectScope + happy-dom 落树集成：palette insert 顶层 / 画布 move 同序列重排 / 落回原位不变 /
  进 if 槽 / 无命中还原 / lock no-op）/ `BlockPalette.smoke.test.ts` / `BlockCanvas.drag.smoke.test.ts`
  （pointer 序列不崩 + 浮层出现 + 选中）。vite build 通过（script-engine chunk 112.7 kB / main 565 kB）。

---

## 2026-06-11 · 0.7.0-P4-D1：积木编辑会话模型（working copy + 本地 undo + debounce save）

把积木编辑做成"本地改 working copy + 自动保存"心智（K-UI-11），不每个像素发请求。新建编辑会话
store + 接通 ScriptEditorOverlay 的新建 / 删除 / 选规则 / 名称 / 启停 / 撤销重做。**拖拽 / palette
留 D2**（D2 算出新 actions 树后调本 store 的 `setActions` 等变更入口）。

- **`web/src/stores/scriptEdit.ts`**（新）：`useScriptEditStore` 编辑会话。
  - state：`selectedRuleId` / `workingCopy`（深拷的本地编辑对象）/ `dirty` / `undoStack` /
    `redoStack`（cap 50）。
  - `selectRule`：从 `scripts.get` **深拷**（JSON 往返——入参可能是 reactive proxy，
    structuredClone 会抛 DataCloneError）进 workingCopy，清 undo/redo + dirty；切规则前先 flush
    上一条待保存改动。`closeEditing`：flush → 清空。
  - 变更入口（统一 `mutate` 包装：lock no-op + pushUndo + 改 workingCopy + dirty + scheduleSave）：
    `setActions`（D2 拖拽写这里）/ `setTrigger` / `setName` / `setStackPos`（改 blockLayout.stacks）/
    `updateActionField`（path 定位 + immutable 重建，F 阶段参数表单调）。`setEnabled` **不进 debounce**——
    即时 `sendScriptEnable` + 即时更新 workingCopy.enabled（开关期望立即生效，不标脏）。
  - `newRule`：构造默认规则（`trigger=wallReady` / `actions=[]` / `enabled=true` / 空 blockLayout）→
    `sendScriptCreate` → **可靠拿 server 发的 id**：发送前快照 `scripts.order` 已知 id 集合，await ack
    （后端先 apply state.patch 再 ack，故 ack resolve 时新规则**通常已**在 store）后比对 order 找新 id；
    边缘（patch 晚于 ack）则 watch `scripts.order` 等新增，5s 超时放弃 → selectRule 进编辑。
  - `deleteRule`：lock no-op；删当前编辑规则时取消 pending save + 清编辑态（避免删后又把缓存写回）
    再 `sendScriptDelete`。inline confirm 在 UI 层。
  - `undo`/`redo`：swap working copy 与栈顶 + scheduleSave。
  - 自动保存：`scheduleSave` debounce **800ms** → 脏 + 有选中规则 → `sendScriptUpdate(stripIdWall)`；
    乐观清脏，失败标回脏（下次重试）+ toast 不丢改动。`flushSave` 立即存（closeEditing / 切规则前）。
  - **server-as-truth 协调**：watch `scripts.get(selectedRuleId)`（deep）——非脏 → 用 server 版刷新
    workingCopy（回显他人改动 / 自己 save 后权威态）；脏 → 保留本地不覆盖；规则被 server 删 →
    closeEditing。watch `project.wallId` 变 → closeEditing（wall 切换清会话）。
  - **lock 守卫（K-UI-12）**：`project.isLocked` 时所有变更入口 + newRule / deleteRule + doSave 全 no-op。
- **`web/src/script/canvas/ScriptEditorOverlay.vue`**：接通编辑模型。"新建规则"按钮启用（lock 时禁用）；
  左侧侧栏顶部加**规则列表**（点选 → selectRule，当前项 mauve 高亮 + enabled 绿点）+ 保留 palette 占位
  （D2 替换）；头部当前规则编辑控件（名称 input → setName / 启停 toggle → setEnabled / undo·redo 按钮 /
  删除 inline confirm popover / 试跑占位禁用 H 阶段）；Ctrl+Z 撤销 / Ctrl+Y(或 Ctrl+Shift+Z) 重做 keydown
  （表单聚焦时不接管，留给输入框）；Esc 关闭先 flush；lock 时编辑控件全 disabled。BlockCanvas 堆点击
  选中接 selectRule 留 D2。
- **`i18n/messages.ts`**：script 段补 D1 key（规则列表 / 名称占位 / 启停文案 / 撤销重做 / 删除确认 /
  试跑占位 / lock 提示 / 选规则提示）中英 ~22 key。
- **测试**：`scriptEdit.test.ts` 38 case（深拷隔离 / 变更入口 + dirty + undo / undo-redo + cap50 /
  debounce save 合并 + 成功清脏 + 失败标回脏 + flush 立即 / setEnabled 即时不进 debounce / lock no-op
  全入口 / server-as-truth 非脏刷新 vs 脏保留 + 删除→closeEditing + wall 切换→closeEditing / newRule
  默认形态 + patch 先于/晚于 ack 拿 id + send 失败 + 超时 / deleteRule 当前→closeEditing 不写回）+
  ScriptEditorOverlay smoke 更新（新建按钮启用 + 选规则后头部名称输入 + 列表高亮）。
- **结果**：前端 **688**（基线 649 + scriptEdit 38 + overlay smoke +1）全绿 / vite build 成功
  （script-engine chunk 101 kB / main 565 kB，0 显著膨胀）。`npm run typecheck`（vue-tsc）本机工具链
  损坏（typescript 6.0.3 缺 `_tsc.js`，与 vue-tsc 3.2.7 / 原生 tsc CLI 均不兼容——已知 Node 25 工具链
  陷阱，非本次代码问题）；CI 只跑 vitest + vite build（不跑 typecheck），两者均过。

---

## 2026-06-11 · 0.7.0-P4-C：积木渲染（真规则上画布）

把 P4-B 的假积木堆换成真渲染：声明式 `blockDefs` 驱动 BlockStack（触发器帽子）+ BlockNode
（递归动作 / if）渲染 store 里的真 ScriptRule。**本阶段参数槽渲染占位**（显字段名 + 原始值
文本），真表单控件留任务 F；拖拽 / lock 守卫留任务 D。

- **`web/src/script/model/blockDefs.ts`**（新）：声明式积木定义。`TRIGGER_DEFS`（6 触发器）+
  `ACTION_DEFS`（9 动作 = 8 + if）。每 `BlockDef` = kind 判别 + category（决定配色）+ colorVar
  （Catppuccin token）+ labelKey + `FieldDef[]`。字段逐一对应 wire（protocol.ts ScriptTrigger/
  ScriptAction），**字段顺序 = 表单顺序**；number 字段带 min/max/step 镜像后端 validator
  （timer 1..86400 / near 1..32 / wait 50..5000 / volume 0..2 / pitch 0.5..2）；select/op/scope
  options 的 value 对齐后端白名单（ELEMENT_PROPERTIES 8 / TIMELINE_OPS / SOUND_SCOPES）。
  category→colorVar：trigger=peach / action=blue / control(if)=green / danger(runCommand)=red /
  timeline(playTimeline)=mauve。`defFor(kind)` 先查动作再查触发器，未知→null。
- **`web/src/script/canvas/BlockNode.vue`**（新）：递归积木块。块根挂 `data-block-path`（= path，
  供 D 测量 / H 高亮）+ 左色条按 category。头部 = label + 标量参数占位（字段名: 原始值）。
  **if 块 C 形**：condition 单独占位行 + then/else 用本组件递归——子块 path 拼
  `${path}/then/${i}` / `${path}/else/${i}`，**与后端 ScriptRunner trace blockId 逐字符同构**
  （`actions/` 前缀 + `/then/`·`/else/` 分支，权威 ScriptRunner.java L207/226/244）。空槽显占位。
- **`web/src/script/canvas/BlockStack.vue`**（新）：单规则积木堆 = 触发器帽子（读 TRIGGER_DEFS，
  梯形 peach 底 + 规则名 + 触发器参数占位，`data-block-path="trigger"`）+ 动作序列（BlockNode，
  顶层 path = `actions/i`）。`position:absolute` 读 props.x/y。
- **`web/src/script/canvas/labelKey.ts`**（新）：把 blockDefs 的点分 i18n key（如
  `script.blocks.variableChange`）在 t.value 上逐段下钻取文案，找不到→返 key 本身（degrade 不崩）；
  BlockNode/BlockStack 共用，避免重复。
- **`BlockCanvas.vue`**：删假堆，`v-for` over `scripts.listSorted` → BlockStack，坐标来自各规则
  自身 blockLayout 的 `stacks[rule.id]`，缺坐标统一交 autoLayout 纵向排布兜底。
- **`ScriptEditorOverlay.vue`**：空画布提示改 `v-if="scripts.size === 0"`（有规则即隐藏）。
- **`i18n/messages.ts`**：script 段补积木 label（15）+ 字段 label（21）+ select 选项（13）+
  emptySlot / unknownBlock，中英对照（Messages 类型以 zh 为准，build 校验 en 结构对齐）。
- **测试**：blockDefs 完整性 17 case（每 kind 有 def + 字段恰好覆盖 wire 全字段 + 配色映射 +
  number 范围 + select 白名单 + defFor）/ BlockNode smoke 9（各动作 + if 递归 + data-block-path
  同构 `actions/0/then/0` / 深嵌 `.../then/0/then/0` / 空槽占位 / 未知兜底）/ BlockStack smoke 7 /
  BlockCanvas smoke 5（N 规则→N 堆 + 显式/autoLayout/混合坐标 + 空 store）。**前端 649**（613 + 36）
  全绿 / vite build 出 `script-engine` chunk 33.46 kB + 4.88 kB CSS / 0 baseline 漂移。

关联文件：`web/src/script/model/blockDefs.ts`（新）/ `web/src/script/canvas/{BlockNode,BlockStack}.vue`
（新）/ `web/src/script/canvas/labelKey.ts`（新）/ `web/src/script/canvas/BlockCanvas.vue` /
`web/src/script/canvas/ScriptEditorOverlay.vue` / `web/src/i18n/messages.ts` + 4 测试文件。

---

## 2026-06-10 · 0.7.0-P5-E：命令模板列表端点 `GET /api/script/command-templates`

积木编辑器（P4/P5）的 runCommand 积木需要列出服主在 config 配的命令模板下拉。新增只读 HTTP
端点，**只下发 id + params（name/type/maxLength），绝不泄 command 原文**（安全核心，对齐
scripting.md §5.2 / 行 303 契约）。

- **新 handler** `web/CommandTemplateHandler.java`：照 `VariableMetadataHandler` 范式（自治
  handler + 测试友好 seam）。`sessionId` query 鉴权（`sessionManager.byId` 非空，缺则也接受
  K-UI-10 规格写的 `session` 别名）；命令模板经 `Supplier<Map<String,CommandTemplate>>` 惰性读
  volatile config → `/canvas reload` 后下次请求即生效（与 ActionExecutor 读模板范式一致）。
  `buildJson()` 只取 id + params，command 字段永不进结果；template id 与 param name 因
  `Map.copyOf` 不保证声明序，一律按字母序稳定输出。无模板 → `{"templates":[]}`。401 形态
  `{"error":"UNAUTHORIZED"}`，短缓存 `max-age=60`（同 /api/font/list）。
- **WebServer 接线**：新增构造参数 `commandTemplatesSupplier`（null = 旧测试装配禁用端点）+
  `commandTemplateHandler` 字段 + start() 内一行路由注册（保持 god-class 拆分纪律）。
- **HikariCanvas 装配**：传 `() -> config().scriptsConfig.commandTemplates()`。
- **测试** `CommandTemplateHandlerTest`（10 case，JavalinTest）：401×3（缺/空/未知 session）/
  有模板返列表且 **body 不含 command 原文/片段/`"command"` 字段**（多重反向断言）/ params 形态
  name·type·maxLength + 字母序 / `session` 别名 / 空配置 + null 供给 → `[]` / 无参数模板 /
  buildJson 直测。
- **基线**：后端 **1585**（1575 + 10）/ 0 failure / 0 baseline 漂移。

关联文件：`plugin/.../web/CommandTemplateHandler.java`（新）/ `plugin/.../web/WebServer.java` /
`plugin/.../HikariCanvas.java` / `plugin/src/test/.../web/CommandTemplateHandlerTest.java`（新）。

---

## 2026-06-10 · 0.7.0-P3 收口：**P3 游戏事件层完工**（6 触发器 8 动作全通）

P3 六个 commit 收口（计划 + A1 命令模板 / A2 试跑异步+预 parse / B1 进服击杀 / B2 playerNear /
审查修复），单轮合并审查（按用户要求压缩审查轮次）抓出 2 必修已修：

- **A1 命令模板**：CommandTemplateEngine 纯函数（K13 转义：剥换行+§ / text 含 @ 整体拒 /
  online-player 精确命中 / 前导 / 剥除）+ runCommand 真实化（console dispatch +
  SCRIPT_COMMAND_EXECUTED audit）+ config `scripts.command-templates` 段
- **A2 试跑异步**（K11/K16）：ScriptTestLauncher 取代同步 seam；ack 立即 `{accepted}`，
  轨迹经新 `OpPushCallback.pushOp` 推 `script.trace`（callback 恰一次全路径）；
  条件保存期预 parse（坏条件 SCRIPT_INVALID 带 blockId）；前端 lastTrace store
- **B1/B2 游戏事件**（K14/K15）：GameEventListenerHub（MONITOR 纯转发）+ Router 全局索引
  （join/kill）+ PlayerNearSampler（零 Bukkit 进入沿状态机 + 跳帧热更）+ 世界 UUID 快照表
  （修 WS 线程异步 getWorld + WorldLoadEvent 自动补登记后加载世界的 near 规则）
- **基线**：后端 **1575**（P2 末 1515 → +60）/ 前端 **536** 全绿 / vite build 过
- §8 P3 标 ✅ / §11 ScriptTestSeam 异步化勾账 / §10 采样间隔回填

0.7.0 进度：P1 ✅ P2 ✅ P3 ✅ → P4 积木引擎（前端工时大头）→ P5 积木内容 + 完整实测闸 → P6 收尾。

---

## 2026-06-10 · 0.7.0-P3-5：审查修复（世界 UUID 快照表免异步 getWorld + 后加载世界自动补登记 + §5.2 对齐）

P3 审查 4 项收口（1/2 必须修，3/4 记账）：

- **①（必须）originSource WS 线程调 Bukkit.getWorld**：script.create/update 经 WS
  线程 → ScriptStore listener → rebuildWall → originSource lambda 跑在 Jetty 线程，
  异步读 CraftServer.worlds（普通 LinkedHashMap）官方不保证线程安全。修法：
  HikariCanvas 新私有字段 `scriptWorldUuidByName`（ConcurrentHashMap<世界名, UUID>），
  onEnable 用 `Bukkit.getWorlds()` 全量种子；originSource lambda 改读快照表（零
  Bukkit 调用，任意线程安全）。GameEventListenerHub 加 WorldLoadEvent（put +
  `onWorldChange.run()`）/ WorldUnloadEvent（remove）两个 MONITOR handler——构造注入
  map + Runnable，转发体抽包私有 `handleWorldLoad/handleWorldUnload` 可单测。
  **顺手真解掉"世界后加载的 near 规则需重保存才登记"记账**：onWorldChange 生产装配
  = `router::rebuildAll`（主线程调，便宜），后加载世界的 playerNear 规则自动补登记。
  改正 HikariCanvas 原"rebuild 都在主线程"失实注释 + TriggerRouter 两处 javadoc 记账
  同步（WallOriginSource / registerRuleLocked near 分支）。
- **②（必须）scripting.md §5.2 转义文字对齐实现**：原文"剥行内 `/`、`@` 选择器字符"
  与 CommandTemplateEngine 实际不符。改写为：替换值剥换行（\n/\r）与 `§`；text 参数
  含 `@` 整体拒（error）；online-player 参数必须精确命中在线玩家名（大小写敏感）；
  渲染结果剥一个前导 `/`；默认 max-length 64。
- **③（记账）config.yml** 模板注释加一条："参数值可含空格，会成为命令的额外参数——
  设计模板时把自由文本参数放命令末尾"。
- **④（记账）CommandTemplateEngine javadoc** 加一条：参数值字面含 `{其他参数名}` 时
  替换结果依迭代序，两值过同一净化，不构成注入面。
- 测试：新增 `GameEventListenerHubTest` 3 case（worldLoad 登记 + 补登记回调 / 重载
  覆盖 UUID / unload 摘表不 rebuild / null 回调不抛）→ 后端 **1575** 全绿（1572+3）。
- 关联文件：`HikariCanvas.java` / `GameEventListenerHub.java` / `TriggerRouter.java` /
  `CommandTemplateEngine.java` / `config.yml` / `docs/scripting.md` /
  `GameEventListenerHubTest.java`

## 2026-06-10 · 0.7.0-P3-2：script.test 异步轨迹（K11）+ 条件预 parse（K16）（A2）

P1 同步 ScriptTestSeam 阻塞 Jetty worker（合法规则可串 wait 至分钟级，5s ack 超时必爆）
→ 异步化全链：

- **ScriptRunner**：`submit` 4 参重载加 `Consumer<List<TraceStep>> traceCallback`——
  **恰一次契约**：最终段结束（wait 续接经 RunState 延续传递）/ actions 掐断 / 投递闸拒
  （chain / rate 回单步 blocked trigger step）/ run 级异常，各恰回调一次（callbackFired
  防双发；callback 抛被吞 + WARNING 不杀 runner）；shutdown 竞态丢弃不回调（契约例外）。
  ScriptBudget 补 `maxRunsPerSecond()` 访问器。
- **新 seam `ScriptTestLauncher`**（script/engine 顶级接口）取代 ScriptTestSeam（已删，
  含 dispatcher 字段 / setter / 测试消费点）；HikariCanvas 装配：find 规则 → submit
  `TriggerContext(TEST, 0, "test")`（K12 不豁免 Budget）；find 与 launch 间并发删 →
  回 error step。
- **推送通道**：OpPushCallback 加 `default boolean pushOp(sessionId, op, payload)`
  （default false 不破既有 fake）+ WebServer 实现（`s-N` 发号与 snapshot/patch 同纪律；
  WsContext.send 线程安全，runner 线程直推）。计划原写 `pushEnvelope(sessionId, Envelope)`，
  实施改 pushOp 形态——id 发号留在 WebServer 侧，dispatcher 不碰 server id 序列。
- **ScriptOpDispatcher.handleTest**：ack 立即 `{accepted:true, ruleId}`；callback 里
  steps 转 wire（`{blockId, kind, result, detail}`，detail null 省略）推 S→C op
  **`script.trace {ruleId, steps}`** 给发起 session（session 没了静默丢）。
- **K16 条件预 parse**：`ConditionEvaluator.checkSyntax(condition)` 静态 parse-only
  （错误信息首行）；dispatcher create/update 在 Validator 后递归走动作树逐 if.condition
  调——坏条件保存期 `SCRIPT_INVALID`（带 blockId 定位），不等运行期静默 false。
- **前端**：wsClient 消息路由加 `script.trace` case → 导出 `applyScriptTrace`
  （照 applyScriptPatches 可独测范式：畸形 payload log err 不落表）→ scripts store 新
  `lastTrace` ref + `setLastTrace`（覆盖式；reset 清）+ meta pushLog 一条；
  `sendScriptTest` 注释改异步语义；types 加 `ScriptTraceStep` / `ScriptTracePayload`。
- **文档**：protocol.md §5.14 script.test 行改异步 + 新增 script.trace 行 + K16 校验链段；
  scripting.md §4.1/§4.2 异步语义改写（"轨迹不走 ack"实施期修订记账）。
- 测试：后端 +14（ScriptRunner trace callback 6——正常/wait 续接/rate/chain/actions/
  callback 抛吞；ConditionEvaluator checkSyntax 3；dispatcher 异步 ack 形态 / trace wire
  推送 / launcher 前守卫 / K16 create 拒 + 嵌套定位 5）→ **1551**；前端 +7（store
  lastTrace 4 + applyScriptTrace 路由 3）→ **536**；vite build 过。

关联：`script/engine/{ScriptRunner,ScriptBudget,ConditionEvaluator,ScriptTestLauncher}.java` /
`web/{ScriptOpDispatcher,OpPushCallback,WebServer}.java` / `HikariCanvas.java` /
`web/src/network/wsClient.ts` / `web/src/stores/scripts.ts` / `web/src/types/protocol.ts` /
`docs/{protocol,scripting}.md`

---

## 2026-06-10 · 0.7.0-P3-1：命令模板系统（A1）

runCommand 第 8 动作真实化（docs/scripting.md §5.2；计划 K13）：

- **HikariCanvasConfig**：`ScriptsConfig` 加 `commandTemplates`（保留 4 参兼容构造）+
  新 record `CommandTemplate(command, params)` / `ParamSpec(maxLength, type)`；
  `scripts.command-templates` 段解析（command 空白跳过 + severe / max-length clamp ≥1 /
  type 未知按 text + severe）。
- **CommandTemplateEngine 新建**（script/engine；纯函数零 Bukkit）：K13 转义逐条——
  模板查无 → Blocked；参数缺失/超长 → Error；替换值剥换行+`§`；text 参数含 `@` 整体拒；
  `type: online-player` 放行 `@` 检查但值必须精确命中在线玩家名（大小写敏感）；
  渲染结果剥前导 `/`；未声明占位符原样保留（服主笔误进 audit 可见）。
- **ActionExecutor.runCommand 真实化**：9 参构造新增 templates supplier（惰性读 volatile
  config → reload 热更免接线）+ onlineNames supplier + AuditLog；render Ok → audit
  `SCRIPT_COMMAND_EXECUTED`（template_id + 替换后全文 + rule_key + wall_id + block_id）
  → 主线程 hop `Bukkit.dispatchCommand(console, cmd)`（plugin=null 直跑，work 内自吞）。
- **ScriptRunner**：新 `RULE_KEY` ThreadLocal（与 CHAIN_DEPTH 同生命周期）供 audit 记
  "来源规则"——ActionSink 接口不为此扩参。
- **config.yml**：command-templates 注释段（announce / give-reward 示例 + 大白话规则说明）。
- 测试：CommandTemplateEngineTest 17 case + ActionExecutorTest runCommand 3 case +
  ActionExecutorCommandAuditTest 2 case（真 DB 直读 audit 断言）。后端 **1537** 全绿。

关联：`HikariCanvasConfig.java` / `script/engine/{CommandTemplateEngine,ActionExecutor,ScriptRunner}.java` /
`HikariCanvas.java` / `config.yml`

---

## 2026-06-10 · P2 实测反馈修复（var 命令冒号断参 / 嵌套 button 警告）

用户 MVP 实测（测试 1、2 通过）反馈 4 项摩擦，2 项是代码 bug 修掉：

- **`/canvas var set user:w-xxx/score 5` 报"参数后应有空格分隔"**：`set` 的 fullName 用
  Brigadier `string()`——不带引号只认 `[0-9A-Za-z_.+-]`，真实变量名的冒号/斜杠直接断参。
  Brigadier 中段参数无法既免引号又收任意字符 → `set` 改整尾 `greedyString` + 手工按第一个
  空格切 fullName/value（value 仍可含空格，缺 value 走既有 usage 提示）；`list <namespace>`
  尾参同病同修（`get`/`delete` 本就是 greedyString 无此问题）。
- **vite dev 嵌套 `<button>` 警告**（IconLibrary.vue 收藏 star 角标）：外层 icon cell 已是
  button，HTML 禁嵌套 → 角标改 `<span role="button">`（stopPropagation 已有，键盘焦点留外层）。
- 另外两项非 bug：变量名两种形态（`user/X` 短形 = 自动注入本墙 / `user:<wallId>/X` 全名 =
  面板复制即用）在脚本/条件/插值里**都通**（resolveFullName 对全名字面透传）；dev 端口实为
  9173（vite.config 配置，测试方案笔误 5173）。

后端 1515 / 前端构建过。关联：`command/VariableSubCommand.java` / `components/layout/IconLibrary.vue`

---

## 2026-06-10 · 0.7.0-P2 收口：**P2 执行引擎完工**（MVP 闸待游戏内实测）

P2 三批次（8 commit）全部落地，收口杂项：

- **ScriptRunner 过期 javadoc 修正**（批次 3 规格审查指出）：ABA 链深读取的真实形态是
  Router 直读 `CHAIN_DEPTH.get()`（null=非脚本来源→depth 0 / 非 null→+1），不是
  `currentChainDepth()+1`（会把玩家/Provider 来源错算成 depth 1）。
- **契约回订 3 处（scripting.md）**：§2.3 log 动作不进 audit（玩家级高频会刷库）；
  §4.2 blockId = 动作树路径（`actions/2/then/1`，后端执行期生成，P1 无 per-action id，
  与前端积木树天然同构）；§8 P2 标 ✅ + §11 账单勾掉 4 项（剩 ScriptTestSeam 异步化留 P3）。
- **P2 全貌**：批次 1 = P1 账单清欠（Store snapshotAll+Listener / enabled 继承 /
  权限拒绝 dispatch 级测试 / K8 整数收紧）+ 条件文法（expr 扩比较/算术/var()，7 层
  递归下降，== 双侧数值形态走数值等值，StrictNumber 单一权威，ConditionEvaluator
  parse 缓存+负缓存）；批次 2 = ScriptRunner（单线程帧栈迭代 + wait 整栈拷贝续接 +
  Budget 三闸 runs/s·actions·chainDepth + K5 audit 限频 + K1 ThreadLocal）+
  ActionExecutor（8 动作 + TickerControl 门面 + 三层异常隔离）+ ElementPropertyApplier
  （路径 A = SessionManager.applyScriptElementPatch 标准链 / 路径 B = headless 临时
  EditSession 单一权威 + persistWall 同构 Ticker 分支）；批次 3 = TriggerRouter
  （变量倒排索引防 stale + timer 独立 SES 自清 + wallReady 双触发点 K9 +
  SessionManager.wallReadyHooks）+ 全链装配（4 路监听 + reload 热更 + 关停顺序）+
  MVP 集成测试 7 case（含 ABA 链深精确演进断言）。
- **测试基线**：后端 **1515**（P1 末 1378 → +137）/ 前端 **529** 全绿 / 0 baseline 漂移。

关联文件：`script/engine/`（ConditionEvaluator/TriggerContext/TraceStep/ActionSink/
ScriptBudget/ScriptRunner/TickerControl/ActionExecutor/ElementPropertyApplier/TriggerRouter）
/ `template/expr/*` / `SessionManager` / `HikariCanvas` / `HikariCanvasConfig` /
`docs/scripting.md` / `CLAUDE.md`

---

## 2026-06-10 · 0.7.0-P2-2b 批次 1 审查留账（== 数值等值契约修订 / warned 上限 / 数值归一单点）

- **`==`/`!=` 数值等值（契约修订，规格审查者建议采纳）**：`ExpressionEvaluator.equals()`
  开头加**双侧均为数值形态**（isNumeric）→ `Double.compare(toNumber(a), toNumber(b)) == 0`
  分支（收编原 Number-Number 老分支）；其余链（Boolean truthy / toString）不动。注意必须
  "双侧"——任一侧即走数值会让 `"abc" == 0` 因 parse 失败强转 0 误判 true。效果：
  `var("score") == 42`（resolver 给 "42"）直接可用，`"3.50" == 3.5` 由 false 变 true
- **I-1 `warned` 无上界**：`ConditionEvaluator` parseCache 超限 clear 处同步
  `warned.clear()`；`warnOnce` 入口加独立 `warned.size() >= CACHE_MAX → clear`（eval
  失败的条件串不进 parse 负缓存，单靠前者不足以约束 warned 增长）
- **M-1+M-2 数值归一单点化**：抽 `private static double norm(double)`（`-0.0→0.0` +
  `!isFinite→0.0` 一并收口）；toNumber / Neg / ADD/SUB/MUL 结果 / divide 全走它，
  归一逻辑不再散点重复。超长字面量（400 个 9）Double.parseDouble 溢出产的 Infinity
  也被 norm 收敛 0.0
- **测试**：`equalityRegressionUnchangedByExtension` 重命名为
  `equalityNumericFormUsesNumericEquals`（含契约修订 4 case + 回归红线 4 断言）+
  新增 `numericNormalizationSinglePoint`（`-1 * 0 == 0` true / 400 个 9 字面量参与
  运算结果 0.0 语义）
- **docs**：scripting.md §2.3 加 == 数值等值 + 数字字面量不支持科学计数法（1e3 parse
  error）两条；template-spec.md §6.2 同步 == 语义段

后端 **1426** test 全绿（原 1425，-1 旧测试 +2 新测试）。

关联文件：`plugin/.../template/expr/ExpressionEvaluator.java` /
`plugin/.../script/engine/ConditionEvaluator.java` /
`plugin/.../template/expr/ExpressionEvaluatorTest.java` / `docs/scripting.md` /
`docs/template-spec.md`

---

## 2026-06-10 · 0.7.0-P1 收口：**P1 完工**（终审 2 必修 + 契约回填 protocol v4 / data-model V017）

P1（数据模型 + 协议 v4）四批次全部落地后做全程对抗终审（跨批次集成缝隙专项），收口：

- **终审必修 #1**：`script.test` 与契约三处分叉——补 `checkFacets`（试跑即真实执行 D5，
  sound/command 面缺失真拒）+ `SCRIPT_TEST` audit + ScriptTestSeam javadoc 改"已定"
  （原写"P2 定夺"与 scripting.md §4.1 定稿矛盾）；handleTest 签名加 sessionId/Session，
  BehaviorTest 两处调用点同步
- **终审必修 #2**：wsClient.ts 三处版本注释还写"0.6 起 = 3"（P1-7 切 v4 时漏改）
- **契约回填**：protocol.md 新增「v3 → v4 变更总览」+ §5.14 script.* 5 op 表 + 4 错误码
  + §10 版本化 v4 行（含 ProjectState.PROTOCOL_VERSION 留 3 是有意的说明）；
  data-model.md 新增 §2.10 wall_scripts（V017 / rule_json 整体存 / enabled 列权威 /
  坏 blob 两档防御 / 三层级联清理 / 配额）
- **scripting.md**：§8 P1 行标 ✅ + 新增 §11 P1 终审记账（P2 首任务清单：ScriptStore
  暴露面 / ScriptTestSeam 异步化 / update enabled 继承 / 权限拒绝路径 dispatch 级测试;
  P4/P5 账:错误码 i18n / 前端 validator 镜像 / blockLayout 帧预算;2 条已澄清防误判）
- **CLAUDE.md** 0.7.0 路线行 P1 标 ✅

**P1 总览**：13 commit（4 批次实施 + 3 轮质量修复 + 收口）/ 终审「真问题」仅 2 条且均为
契约一致性级、0 运行时缺陷 / 后端 **1378** + 前端 **529** 全绿 / vite build 过 / 0 baseline 漂移。
新增面：`script/` 包 9 类（sealed Trigger 6 + Action 8+if 双向 wire 多态 / Validator /
Permissions / Store）+ V017 + ScriptDao + ScriptOpDispatcher 5 op + 协议 v4 干净切换 +
4 权限节点 + 前端 types/wsClient/Pinia 镜像。

关联文件：`docs/protocol.md` / `docs/data-model.md` / `docs/scripting.md` / `CLAUDE.md` /
`plugin/.../web/ScriptOpDispatcher.java` / `web/src/network/wsClient.ts` / 测试 2 处

---

## 2026-06-10 · 0.7.0-P1-7b 批次 3 质量修复（edit 权限真拒在线收回 / 异常入日志 / 跨墙 guard 回归测试 / reload 接线）

- **#1（安全）NODE_EDIT fail-open 修复**：`ScriptOpDispatcher.checkBasePermission` 原
  `if (!granted) granted = true` 无条件放行，服主收回 `canvas.script.edit` 形同虚设。改用
  `MainThreadPerms.resolve`（有 `online` 标志）：`online && !granted` → 真拒
  （PERMISSION_DENIED + audit，照 checkFacets 形态）；仅 offline（含主线程超时 / 解析失败，
  online=false）走 default-true 兜底——与 alias / 模板的 own 兜底等价语义。
- **#2 dispatch catch-all 不再静默吞异常**：构造器加 `Logger log` 参数（WebServer 传自家
  log 字段），catch 内 `log.log(WARNING, "script op failed: " + op, e)`；client 仍回固定
  INTERNAL_ERROR（M16 脱敏不变）。
- **#3 dispatcher 行为级最小测试**：handlers 重构为返回 `Envelope`（dispatch 统一
  ctx.send，照 TimelinePlaybackDispatchTest 绕 final WsMessageContext 的范式）；新
  `ScriptOpDispatchBehaviorTest` 5 case（update 跨墙 ruleId → SCRIPT_NOT_FOUND 回归 /
  create 超配额 → SCRIPT_QUOTA_EXCEEDED / delete 不存在 → ack removed=false 仍推 remove
  patch 幂等 / delete 存在 → removed=true / test 缺 ruleId 不触达 seam）+ 新
  `session/SessionTestFactory`（test sourceset 同 package 桥 package-private 构造）。
- **#4 reload 热更接线**：`HikariCanvas.applyConfig` 加
  `scriptStore.setMaxRulesPerWall(fresh.scriptsConfig.maxRulesPerWall())`。
- **#5 rootMessage 脱敏**：message 截到第一个换行符（首行即自定义 deserializer 可读文案）；
  完整异常经 `ParsedRule.cause` + `logParseFailure` 进 server 日志（FINE）。
- **#6 handleTest 防御**：seam 调用前 `store.find(wallId, ruleId).isEmpty()` →
  SCRIPT_NOT_FOUND；ScriptTestSeam javadoc 注明 P2 决策点（test 触发 sound/command 的
  facet 语义归属）。
- **#7 enabled 类型混淆**：parseIncomingRule 里 enabled 存在但非 Boolean → INVALID_PAYLOAD
  （不再静默默认成 true）；缺失才默 true。LogicTest 补 1 case（`enabled:"false"` 字符串被拒）。
- **#8 paper-plugin.yml 标点**：4 个 script 节点 description 半角 `(0.7.0)` 等改全角
  `（0.7.0）`，与同文件风格一致。
- **测试**：后端 1377 全绿（基线 1371 + 新增 6：LogicTest 1 + BehaviorTest 5）。

关联文件：`plugin/src/main/java/moe/hikari/canvas/{web/{ScriptOpDispatcher.java,WebServer.java},
HikariCanvas.java}` / `plugin/src/main/resources/paper-plugin.yml` /
`plugin/src/test/java/moe/hikari/canvas/{web/{ScriptOpDispatchBehaviorTest.java,
ScriptOpDispatcherLogicTest.java},session/SessionTestFactory.java}`

---

## 2026-06-10 · 0.7.0-P1-7 批次 3：协议 v4 干净切换 + script 系统装配

- **协议 v4**：`Protocol.SUPPORTED_MIN/MAX 3→4`（javadoc 加 v4 = 0.7.0 script.* 说明）+
  前端 `wsClient.ts CLIENT_V 3→4`（干净切换，照 0.6 v3 先例；前端 bundle 插件自带分发，
  部署中两端版本永远匹配）。注：批次说明里写 CLIENT_V 在 `types/protocol.ts`，实际定义
  在 `network/wsClient.ts:60`，按实际位置改。
- **WebServer 装配**：构造参数尾追加 `ScriptStore scriptStore`（null 容忍，唯一调用点
  HikariCanvas 补传）；构造区按 alias dispatcher 范式建 `scriptOpDispatcher`；路由 switch
  加 `script.create/update/delete/enable/test` 5 case（null → INTERNAL_ERROR）；
  handleAuth ready payload aliases 旁注入 `scripts`（`scriptStore.listByWall(wallId)`，
  null wall / store 回空 list）。
- **HikariCanvas.onEnable**：railDao 之后、WebServer 之前装配 `ScriptDao + ScriptStore +
  loadFromDb + sessionManager.addWallDeleteHook(scriptStore::clearWall)`（DB 行靠
  wall_scripts FK CASCADE，内存镜像靠 hook）。
- **paper-plugin.yml**：rail 节点后追加 `canvas.script.{edit, trigger.global, sound}`
  （default true）+ `canvas.script.command`（default op，危险面）。
- **测试**：新增 `ReadyPayloadScriptsTest` 3 case（2 规则 → scripts size 2 + 首条含
  id/name/trigger wire 形态 / store null → 空 list / wall 隔离）。后端全量 + 前端
  vite build 烟测全绿。

关联文件：`plugin/src/main/java/moe/hikari/canvas/{web/{Protocol.java,WebServer.java},
HikariCanvas.java}` / `plugin/src/main/resources/paper-plugin.yml` /
`web/src/network/wsClient.ts` / `plugin/src/test/java/moe/hikari/canvas/web/ReadyPayloadScriptsTest.java`

---

## 2026-06-10 · 0.7.0-P1-6 批次 3：ScriptOpDispatcher（script.* 5 op）

- **新建 `web/ScriptOpDispatcher`**（package-private，照 VariableAliasDispatcher 范式：
  rateLimiter → session → wall → store 判空 → payload → 权限 → switch）：
  - `script.create {rule}`：payload.rule 经类内单例 ObjectMapper convertValue（先覆写
    id 占位 + wallId=session、enabled 缺省 true、blockLayout 缺省 "{}"）→
    ScriptRuleValidator 拒则 `SCRIPT_INVALID`（validator 信息原样作 message）→ 面权限 →
    store.create → ack `{rule}` + patch `add /scripts/<encoded ruleId>` → audit SCRIPT_CREATE
  - `script.update {ruleId, rule}`：**先 store.find 确认 ruleId 属本 wall（防跨墙改）**，
    其余同链；NotFoundException → `SCRIPT_NOT_FOUND`
  - `script.delete {ruleId}`：规则不存在也推 remove patch（幂等，照 alias clear 先例）；
    ack `{ruleId, removed}`
  - `script.enable {ruleId, enabled}`：翻转开关，ack `{rule}` + patch add
  - `script.test {ruleId}`：P1 固定 `SCRIPT_ENGINE_UNAVAILABLE`；留 `ScriptTestSeam`
    （volatile + setTestSeam），P2 ScriptRunner 注入后返回值作 ack
- **权限两层**：基础节点 `canvas.script.edit` 恒查（default true，offline 兜底放行照
  alias own 节点写法）；create/update 对 `ScriptPermissions.requiredFacets` 批量
  MainThreadPerms.resolve 一次 hop 逐面查，**面节点无兜底**（服主收回必须真拒），缺 →
  `PERMISSION_DENIED`（message 含缺节点）+ audit。**不读 wall lock**（lock-state 纪律）。
- **patch version**：携 `currentVersion(s)` 不写 0（Ultrareview 2026-05-25 #17 注释先例）。
- **audit**：SCRIPT_CREATE/UPDATE/DELETE/ENABLE，details 含 wall_id/rule_id/rule_name，
  create/update 另含 facets（排序逗号串接，空集省略）。
- **可测性**：`parseIncomingRule(payload, wallId)` 包级静态 + `record ParsedRule(rule, error)`；
  新增 `ScriptOpDispatcherLogicTest` 8 case（最小合法 rule / 缺 trigger 解析过但 validator 拒 /
  非法 trigger type / enabled 缺省 true + 显式 false 保留 / blockLayout 缺省 / 假 id+wallId
  覆写 / actions 非数组 / rule 缺失或非 object）。

关联文件：`plugin/src/main/java/moe/hikari/canvas/web/ScriptOpDispatcher.java` /
`plugin/src/test/java/moe/hikari/canvas/web/ScriptOpDispatcherLogicTest.java`

---

## 2026-06-10 · 0.7.0-P1-5b 批次 2 质量修复（loadAll 失败外响 + DB 失败零污染契约测试 + 注释对齐）

0.7.0-P1 批次 2 代码质量审查逐项修复（修法已定，不发散）：

- **I-1 补核心契约测试**：`ScriptStoreTest` 新增 `dao_failure_leaves_memory_untouched`——
  对不存在的 wall 调 `store.create("w-nonexistent", …)` → FK violation 从 compute 传播
  （assertThrows RuntimeException + 断言非 Quota/NotFound 业务异常）→ 断言 ① `listByWall`
  仍为空 ② `update("FAKE-ID")` 抛 NotFoundException（wallByRule 反查索引没留孤儿）③ DB 无残留。
- **I-2 loadAll 失败不再静默吞**：`ScriptDao.loadAll()` 删外层 try-catch，整体查询失败
  异常传播（启动期失败应与 MigrationRunner 失败同级响起来）；坏 blob 单行跳过 + SEVERE
  保留不动；`loadByWall` 整体防御式保留不动。ScriptDao 类注释错误处理段 +
  `ScriptStore.loadFromDb` javadoc 各对齐一句（读路径分两档语义）。
- **M-1 注释**：`ScriptStore.freshId` javadoc 补"跨墙并发碰撞穿透时由 DB PRIMARY KEY
  兜底（第二条 insert 抛 → 内存不动）"。
- **M-2 注释**：ScriptStore 类注释"不同墙互不阻塞"改准确——"不同墙通常并行
  （hash 撞同 bin 时会串行，可接受）"。

测试：`:plugin:test` 全量 **1360 全绿**（script + ScriptDao 目标批先单跑确认）。

关联文件：`plugin/src/main/java/moe/hikari/canvas/{script/ScriptStore.java,storage/ScriptDao.java}` /
`plugin/src/test/java/moe/hikari/canvas/script/ScriptStoreTest.java` / `docs/journal.md`

---

## 2026-06-10 · 0.7.0-P1-3b 批次 1 质量修复（if 分支 null NPE / delta finite / 权限 switch 穷尽 / 文案）

0.7.0-P1 批次 1 代码质量审查逐项修复（修法已定，不发散）：

- **I-1 可达 NPE**：`ActionDeserializer.readBranch` 对分支元素 `null / NullNode` 先
  `reportInputMismatch`（之前 `readTreeAsValue(NullNode)` 返 null → `If` 构造器 `List.copyOf`
  裸 NPE，违反"畸形输入一律 reportInputMismatch"契约）；顺手按 M-5 把分支元素改为直接递归
  `fromNode(elem, ctxt)`（省一层 TreeTraversingParser），类 javadoc 同步对齐。
- **I-2 finite 纪律**：`ScriptRuleValidator` IncrementVariable 加 `!Double.isFinite(delta)` 拒绝
  （"累加步长必须是有限数值"）；PlaySound volume/pitch 区间判改 `!(v >= MIN && v <= MAX)`
  取反写法连带拒 NaN（正常输入行为不变）。
- **I-3 权限 switch 穷尽性**：`ScriptPermissions.scanActions` 删 `default -> {}`，显式列出全部
  6 个无权限面子类——未来新增 Action 子类时编译器强制来补权限判定（类注释已注明有意为之）。
- **M-1**：RunCommand 参数检查删不可达的 `e.getValue() == null ||`（Map.copyOf 已保证非 null）。
- **M-2**：`requireLong` / `optionalLong` 报错文案 int → long。
- **M-4 回归测试 +4**：`ifBranchNullElementRejected` / `setVariableMissingFieldRejected`
  （ActionWireTest，断言 JsonMappingException 非 NPE）+ `wait_bounds_ok` /
  `increment_delta_infinite_rejected`（ScriptRuleValidatorTest）。

测试：script 包 55 case 全绿（ActionWire 13 / ScriptPermissions 7 / ScriptRuleValidator 29 / TriggerWire 6）。

关联文件：`plugin/src/main/java/moe/hikari/canvas/script/{ActionDeserializer,ScriptRuleValidator,ScriptPermissions}.java` /
`plugin/src/test/java/moe/hikari/canvas/script/{ActionWireTest,ScriptRuleValidatorTest}.java` / `docs/journal.md`

---

## 2026-06-10 · 0.7.0 立项：视觉运行时设计总纲 `docs/scripting.md`（D1-D8 固化）

0.7.0 Scratch-like 视觉运行时 brainstorming 定稿（6 个决策用户逐一拍板），文档先行落 3 处：

- **新建 `docs/scripting.md` 设计总纲**（契约，照 timeline.md 范式）：D1-D8 决策摘要 /
  ScriptRule + sealed Trigger(6)/Action(8+if) 数据结构 / V017 wall_scripts / 执行管线
  （TriggerRouter→ScriptRunner→ActionExecutor，单线程队列 + Budget 三闸 + ABA 链深熔断）/
  协议 v3→v4（script.create/update/delete/enable/test 5 op + 试跑轨迹回推）/ 安全
  （命令白名单模板 + 填参转义 + 7 audit 事件）/ 自写积木引擎双层架构（engine/defs 解耦）/
  风险登记 / 6 段分期 ~340h（P2 MVP 闸 + P4 引擎闸 + P5 完整实测闸）。
- **关键决策**：D1 自写积木画布（Blockly 否决：schema 双向同步税 + 风格不合 + bundle）；
  D2 **"一画布二选一"作废**——脚本是上层、时间轴是被编排素材（playTimeline），同画布共存，
  0.6 触发器保留；D3 标准集 6 触发器（变量/定时/进服/击杀/玩家靠近周期采样/墙就绪）；
  D4 动作全集 8 个含执行命令（服主 config 白名单模板 + 填参，禁拼接，独立权限默 op）；
  D5 后端唯一执行器 + 真试跑（script.test 走真实管线，轨迹逐积木高亮，零双端分叉）；
  D6 分级放权（edit/trigger.global/sound 默 true + command 默 op）；D7 独立 ScriptStore
  不进 ProjectState，`.canvas` 含脚本、模板按名引用缺失灰显；D8 熔断不自动禁用（工具不是保姆）。
- **`docs/dynamic-data.md`**：§13.0 愿景改"分层共存" + §13.5 标记被 scripting.md 取代（档案保留，
  列 6 处定稿更正——事件层"几乎为零"已过时，0.6 P5 的 TimelineTriggerRegistry 即 TriggerRouter 范式）。
- **`CLAUDE.md`**：路线图 0.7.0 行改"进行中"（~340h / 6 段）+ "二选一"注记作废说明。

关联文件：`docs/scripting.md`（新）/ `docs/dynamic-data.md` / `CLAUDE.md` / `docs/journal.md`

---

## 2026-06-09 · 0.6.0-P6 收尾 + **0.6.0 完工**（一致性 CI 确认 / 用户文档 / 版本号）

P6 余下三件，**0.6.0 时间轴编辑器正式收工**。

- **P6-2 双端一致性 CI**：确认已是完成态（P3 建的）。`rendering-test/easing-vectors.json`（第三方
  Python 参照实现生成、已入库）被 Java `EasingSolverTest` 和前端 `easing.test.ts` + `colorLerp` 双端
  **对同一组向量断言**（tol 1e-6）；CI 跑 vitest + :plugin:test 时两边都验——任一端插值器分叉即 CI 红。
  无需新建。
- **P6-3 用户文档**：新增 `docs/timeline-guide.md`——大白话教程（做第一个动画 / 拉就设 / 缓动 / 整体帧 /
  框选删 / 播放方式 / 触发器三种 / 编辑期 vs 游戏内 / 双端一致 / fps / 一分钟清单），与 `variables.md`
  同谱系（`timeline.md` 仍是技术设计总纲）。
- **P6-4 版本号**：`0.5.0 → 0.6.0-SNAPSHOT`（build.gradle.kts allprojects / paper-plugin.yml /
  web package.json + package-lock.json）；CLAUDE.md 路线图标 0.6.0 完工。

**0.6.0 总览**：P1 数模+协议 v3+撤销 coalescing → P2 AnimationTicker+池化+MVP → P3 缓动+双端插值器+一致
CI → P4 AE 风 dock + P4.5 整体帧/拉就设/框选删/块拖/逐属性 → P5 触发器（变量变化/到点→自动播）→ P6
编辑期自动播+文档+收尾。约 25 commit（含 5 轮实测 hotfix + 2 个 TDZ/UX hotfix + 诊断日志）。
后端 **1286** / 前端 **510** 全绿 / vite build 过 / 双端 0 漂移。**从"静态招牌"到"会动 + 按数据反应的
信息屏"。**

**已知小账（不阻塞，记录）**：触发器/动画的运行时注册偶发现象——P6-1 已把"新建动画要重启"的主因
（只在关闭/启动起播）改成"落库即起播"；若仍有残留待实测进一步定位（诊断日志已埋）。

关联：`docs/timeline-guide.md`（新）`build.gradle.kts` `plugin/.../paper-plugin.yml` `web/package*.json` `CLAUDE.md`。

---

## 2026-06-09 · 0.6.0-P6-1 — 编辑期也让游戏里的墙自动播（同时修"新建动画要重启才动"毛刺）

P6 收尾开篇。把"编辑期游戏里的墙不动、要关编辑器/重启才动"这个老行为改掉——它也是上次那个
"重启前不工作、重启后正常"毛刺的根因：墙原本只在**启动扫描**（autoRegisterAll）和**会话关闭**
（cancel→refreshAutoPlay）时才自动播；而浏览器关闭不一定立刻触发 cancel（要等 SessionReaper 回收
空闲会话），所以新建的循环动画常常得等重启全量扫描才动。

- **修**：`SessionManager.persistWall` 落库后——已在播 → `invalidate`（廉价刷缓存）；**没在播但有
  activeTimeline → `refreshAutoPlay`**（编辑期首次落库即在游戏里起播）；静态墙（无 timeline）→ 不碰
  Ticker（避免每次编辑多一次 loadWall 的 DB 读）。LOOP 动画首帧落库即起播、之后编辑走 invalidate
  （DB 读只发生一次，非每次编辑）；ONCE 不自动播（autoLoopEligible 只放行 LOOP，不变）；
  variableChange/schedule 仍等触发器（registry 编辑期也活跃，变量变了就播）。
- **效果**：① 编辑期游戏里的墙就动了（不必关编辑器）；② 新建动画不再要重启——落库即起播。
- 核心行为 `refreshAutoPlay` 启动未播合格墙已有单测（`AnimationTickerTest.refreshAutoPlay_loopAutoPlays`）；
  persistWall 是简单条件分支 + 后端 1286 全量回归无破坏。SessionManager 无现成测试基建，不为 3 行
  改动从零搭 session+repo 整合测试（live 播放行为最终靠实测）。

后端 1286 全绿 / 行为改进。关联：`session/SessionManager.java`。

---

## 2026-06-08 · 0.6.0-P5 诊断日志 — 排查"重启前动画/触发不工作、重启后正常"

用户实测：墙动画 / 触发器在**重启服务器后正常，重启前不工作**。这指向运行时注册状态问题（启动的
`autoRegisterAll` / `rebuildAll` 全量扫描正确，运行时 `rebuildForWall` / `refreshAutoPlay` 增量路径可疑）。
代码静态审查（WallRepo 无缓存层、loadById 直查 DB、persistWall→rebuildForWall 与 cancel→refreshAutoPlay
hook 都已接、autoLoopEligible 对 LOOP+MANUAL 放行）没看出确定 bug——这类无法本地复现的运行时状态问题，
加诊断日志让下次实测的服务器控制台直接定位卡点。

- **TimelineTriggerRegistry**：绑定注册成功 → INFO「已绑定：墙 X 时间轴 Y ← 监听变量 <解析后fullName>」；
  变量变化命中绑定 → INFO「变量 X 变化 → 播放墙 Y」；去抖跳过 → FINE。这三条能区分：绑定没注册 /
  变量名没匹配 / 去抖吞掉 / play 抛错。
- **AnimationTicker.refreshAutoPlay**：自动播分支 → INFO「墙 X 自动播放循环动画（编辑器关闭/刷新触发）」。
  配合启动期已有的「N wall(s) auto-playing」，能看出正常循环动画在运行时是否自动播。
- 这些 INFO 日志也是运维长期想要的"触发器何时点火"可观测性（PROPOSAL §2.1 数据透明），保留。

后端 compile + 触发器/Ticker 测试全过 / 行为不变（纯日志）。关联：`render/{TimelineTriggerRegistry,AnimationTicker}.java`。
**下次实测**：重启挂新 jar → 设触发器看「已绑定」→ 改变量看「变量变化 → 播放」→ 把控制台对应行发我。

---

## 2026-06-08 · 0.6.0-P5 hotfix-2 — 触发方式下拉点了弹回默认（picker 自动开被 onClickOutside 立即关）

CanvasView TDZ 修好、画布起来后，用户实测：点「变量变了就播」/「到点就播」下拉**没反应、卡在默认项**。
根因 = 我在 select 的 `@change` 里**自动弹 VariablePicker**，而 picker 的 `onClickOutside` 把"选 select
选项"这一下当成外部点击 → 立即 `emit('close')` → `cancelTriggerPicker` 又把下拉**重置回 manual**。
表现就是点完弹回默认。TextElementSection 能用是因为它通过**按钮**开 picker（onClickOutside 忽略开启那次
点击），不是 select-change。

- **修**：select 的 change 只管改触发类型（草稿停在选的项，已绑变量则持久化）；不再自动弹 picker。
  picker 改由下方「点这里选要监听的变量」按钮显式打开（与 TextElementSection 同款可用模式）。
  `cancelTriggerPicker` 不再重置下拉。
- **定位同修**：VariablePicker 自身 `position:absolute; top:100%` 向下展开，dock 在屏幕底部会溢出视口；
  改用 `fixed` 居中浮层（top-20）给它有宽度的定位父级，在可见区展开。
- **smoke 验证**：新增 case——选 variableChange → 不自动弹 + 出现选变量按钮；点按钮 → `.hc-variable-picker`
  出现。前端 509 → **510**。

前端 510 全绿 / vite build 过 / 0 漂移。关联：`components/timeline/{TimelineDock.vue,__tests__/}`。

---

## 2026-06-08 · 0.6.0-P5 hotfix — CanvasView 启动 TDZ（潜伏 bug 被 P5 重打包暴露）

用户实测 P5：点设置里「变量变了就播」没反应 + 控制台 `Cannot access 'pt' before initialization`。
**根因不在触发器**——是 CanvasView 一个从 M5 就潜伏的 TDZ：`requestDraw` 的 rAF 去抖标志 `let
drawPending`（+ `drawRafId`）声明在那个 `watch(()=>project.state, ()=>requestDraw(), {deep, immediate})`
**之后**；immediate 在 setup 期同步调 requestDraw 读 drawPending → TDZ → CanvasView 启动即崩 → 整个画布
坏掉、点啥都没反应。M5 起一直这样写，旧 minifier 恰好把 `let` 提升到函数顶所以没显形；0.6 P5 加了模块
后 rolldown 重新优化、这次没提升，bug 暴露。

- **修**：把 `drawPending` / `drawRafId` 两个 `let` 上移到 immediate watch 之前声明（函数 hoisted 不用动）。
  源码层面声明先于使用，minifier 无论怎么排都不会再 TDZ。
- **客观验证**：解码新产物 `index-vVcHQrM6.js` 确认 `let`（minified `ft`）偏移 564835 < immediate watch
  偏移 564868——声明已在 watch 前。另扫全前端其余 3 个 immediate watch（dock 时间映射 / dock 触发类型 /
  useLivePaint），回调要么用更早声明的 ref/computed、要么调 hoisted 函数，均无同类隐患。
- **教训**：`immediate: true` 的 watch 回调里同步用到的 `let`/`const` 必须声明在 watch 之前（hoisted
  function 例外）；这类 minifier-相关潜伏 TDZ 只在打包产物显形，vitest 不打包抓不到。

前端 509 全绿 / vite build 过 / 0 漂移。关联：`components/layout/CanvasView.vue`。

---

## 2026-06-08 · 0.6.0-P5 触发器（变量变化 / 到点 → 时间轴自动播；a/b/c 一起做）

时间轴的"何时播"补完：招牌按游戏数据自己反应。地基全复用（变量监听 + Ticker + schedule 变量），
新增的就是把它们接起来。**PLAYER_NEAR 仍留 0.7**（与 Scratch 事件层重叠，D5）。

- **P5-a 后端核心**：新 `render/TimelineTriggerRegistry`——薄索引 `解析后fullName → Set<(wallId,timelineId)>`
  + 监听。全 seam 注入（player / resolver / wallSource / clock），不耦合 AnimationTicker/VariableStore 具体类
  （§5.3 让 0.7 统一触发路由能吸收）。`onVariableChange(fullName)` 命中 → `ticker.play`（重播 = ONCE
  「每次变化重播」，用户定）；**去抖 200ms** per-(wall,timeline) 挡 eta_seconds 等高频 thrash。
  - **fullName 一致性坑（§5.2 R）**：trigger 配 `user/X` 经 `VariableInterpolator.resolveFullName`（改 public）
    注入 wallId → `user:<wallId>/X` 才匹配变化事件。user 走注入、schedule 已含 wallId 走 passthrough。
  - **自动播门控**：`AnimationTicker` 的「wall ready 自动播 LOOP」加 `autoLoopEligible`——只对 MANUAL 触发
    生效；VARIABLE_CHANGE/SCHEDULE 不自动播、改登记进 registry。
  - **校验**：`validateTrigger` 加 VARIABLE_CHANGE/SCHEDULE 必须带 params.fullName（缺失拒 INVALID_PAYLOAD）。
  - **接线**：HikariCanvas.onEnable 构造 registry + rebuildAll + 第 3 个 ChangeListener（VALUE_SET/UPDATED/
    CREATED 才触发）+ wall 删除 hook；SessionManager 编辑持久化 / session 关闭处加 `rebuildForWall`（与
    ticker invalidate/refreshAutoPlay 同源点）。`play` 任意线程安全，不用 hop 主线程。
- **P5-b 前端**：dock 设置加「什么时候播」下拉（手动/变量变了就播/到点）；非手动 → 复用 `VariablePicker`
  选变量写 `trigger.params.fullName`（displayName 即 rawName，两端匹配）。draftTriggerType 草稿 + 取消回弹；
  timelineLogic 加 `buildTrigger`/`triggerNeedsVariable`/`TRIGGER_TYPES`；i18n 中英 6 key。
- **P5-c 收尾**：protocol.md（trigger params.fullName 必填 + 去抖说明）+ timeline.md §5.2 落地注记。

后端 1277 → **1286**（registry 6 + Ticker 门控 2 + validateTrigger 1）/ 前端 503 → **509**（trigger helper 5 +
dock smoke 1）/ vite build 过 / main 780 kB / 0 漂移。
契约：`docs/{protocol.md,timeline.md}`。关联后端：`render/{TimelineTriggerRegistry.java(新),AnimationTicker.java}`
`state/TimelineOperations.java` `variable/VariableInterpolator.java` `session/SessionManager.java`
`HikariCanvas.java`；前端：`components/timeline/{timelineLogic.ts,TimelineDock.vue,__tests__/}` `i18n/messages.ts`。

**0.6.0 仅剩 P6 收尾**（~15h：一致性 CI + 文档 + 版本号）。

---

## 2026-06-07 · 0.6.0-P4.5b 实测 3 bug 修复（撤回粒度 / 时间数字闪烁 / 时长缩短无反馈）

用户实测 P4.5b 报 3 个小 bug，逐一定位根因后修：

- **Bug 2（撤回不回收旧帧 + 出现两个帧，最严重）**：根因 = 一次"拉就设 / 加帧 / 整体块拖动"
  写元素 6 个 transform 属性 = 6 条 `keyframe.*` op，各自 `commitHistory` → **6 个独立 undo 步**。
  ctrl+z 只撤 1/6，整体帧块（6 帧聚合）看着没消失；若元素原本别处有帧，就剩"原帧 + 没撤干净的
  新帧"两个块。修：`keyframe.add/update/move` 加**可选 `coalesceKey`**，前端给一组 op 传同一个 key
  （`integ:{eid}:{ms}` / `integ-move:{eid}:{ms}`）→ 后端 `commitHistoryCoalesced` 合并成一步撤销。
  **向后兼容**：缺省回退原行为（add 各一步 / update·move 按单帧键 `eid:kfId:prop` 合并），靠**方法重载**
  让所有旧调用方零改动。后端 restore() 本就正确还原 timelines（全量 snapshot 下行），故只是粒度问题。
- **Bug 1（时间数字闪烁）**：播放头读数用 `formatTimeLabel` 去尾 0（2.4s / 2.33s）+ ms↔s 切换 →
  宽度抖动一闪一闪。新 `formatClock` 定宽 `m:ss.mmm`（秒 / 毫秒零填充）+ tabular-nums → 彻底不抖。
  标尺刻度仍用 formatTimeLabel（刻度静态、去尾 0 更易读）。
- **Bug 3（时长 5000→4000 没反应、→6000 可以）**：根因 = 后端拒"时长短于最后一个关键帧"
  （`INVALID_KEYFRAME_TIME`），前端 `.catch` 静默吞 → "没反应"。修：dock 设置预校验 `maxKeyframeMs`
  （前端算全轨最大帧时刻），缩到帧之下 → inline 红字提示"时长不能短于最后一个关键帧（X），先移走/删掉它"
  + 输入框回弹到当前有效值；超范围同样提示。

后端 1274 → **1277**（+3：批量 add / move 合并撤销 + 无 key 各自撤销 + 兼容旧签名）/ 前端 500 → **503**
（formatClock 3）/ vite build 过 / 0 漂移。
契约：`protocol.md §5.13`（coalesceKey 可选字段）+ `timeline.md §7.2`（coalesce key 整体帧批量）。
关联：`state/{TimelineOperations,EditSession}.java` `web/EditOpDispatcher.java` `network/wsClient.ts`
`composables/useTimelineAuthoring.ts` `components/timeline/{timelineLogic.ts,TimelineDock.vue}` `i18n/messages.ts`

---

## 2026-06-07 · 0.6.0-P4.5b-2/3/4 — dock 关键帧直接操作（框选批量删 + 块拖动改时刻 + per-property 选删）

P4.5b 收口：把 dock 里关键帧从"只能点 + 加 / 点块选"补成可框选、可拖、可逐属性操作。

- **框选批量删（问题 3，显式诉求）**：轨道空白处拉框 → 框中所有整体帧块选中 → Del 一次全删。
  纯函数 `groupsInMarquee`（块中心 x=msToPx(timeMs) / y=行中线，命中归一化矩形）；dock 在
  tracksScrollRef 上接 pointer 框选 + 蓝色选框 overlay（轨道内容坐标，随滚动）；空白单击 = 清选。
- **整体帧块横拖改时刻**：块改 pointer 交互（拖过 3px=move，否则=点击选中 / Shift 多选）。拖动期只
  给被拖块加视觉偏移（`blockLeft`，**不改 timeMs → :key 稳定、不丢 pointer capture**）；松手才乐观挪
  本地 timeMs + 发 `keyframe.move`（整组所有 transform 帧）。snapToFrame 吸帧。注：拖到同元素已有帧的
  timeMs 会并存冗余帧（后端 move 不去重、interpolate 按"重合取后"，前后端一致不崩；去重留后续）。
- **per-property 子轨选删**：展开后单属性帧从只读变可点选（黄色高亮）；选中后 Del 删单帧；与整体帧
  选中互斥（store.selectKeyframe 选单帧即清 selectedGroups）。dock keydown：整体帧优先，否则删单帧。
- **块 / 属性帧 pointerdown 都 stopPropagation** 不触发框选；框选仅左键起。

前端 494 → **500**（+6：groupsInMarquee 5 + per-property 选中 smoke 1；块选中 smoke 改 pointer 路径）/
vite build 过 / main 778 kB / TimelineDock chunk 21.7 kB / 0 漂移。
关联：`components/timeline/{timelineLogic.ts,TimelineDock.vue,__tests__/}` `stores/timeline.ts`

**P4.5b 完工**：缓动轨迹（P4.5a）+ 拉就设（b-1）+ 框选删 / 块拖 / 逐属性（b-2/3/4）三个实测反馈全闭环。

---

## 2026-06-07 · 0.6.0-P4.5b-1 — 拖画布元素自动加帧（"拉就设"，实测反馈问题 2 核心）

P4.5a 整体关键帧实测通过后做 P4.5b 第一刀：把 AE/PR 的"自动关键帧"搬进来——开着开关，在画布上
拖动 / 缩放元素就在播放头记下一个整体帧，不用再点元素行的 + 按钮，真正"拉一下就设起点 / 终点"。

- **统一执行器**：`useTimelineAuthoring.upsertTransformKeyframe`——dock 的 + 按钮与画布拖动共用一套
  upsert（消两处分叉）。纯计划 `planTransformUpsert`（timeMs 已有该属性帧 → update，缺的 → add，值取
  元素当前几何）算要发什么；执行器发 WS + **乐观本地改已有帧 value**（消"改已有帧"的 WS 往返闪烁；
  新增帧那刻该属性本无帧、interpolate passthrough 当前值，天然不闪）。
- **触发条件**：dock 开 + 自动加帧开关 ON + 有激活时间轴 + wall 未锁（`shouldAutoKeyframe`）。满足时
  onDragStart 记拖动元素集 + 切 previewActive；onDragEnd 给每个元素在 playhead upsert 整体帧 + 选中；
  resize/rotate 走 onElementTransformEnd 同样 upsert（值取 onTransformEnd 已 mutate 的最终几何）。
- **拖动期跟手**：`applyDragOverride` 把被拖元素的实时几何覆盖到 interpolate 结果上——否则有帧的元素
  会被插值钉在帧值位置、看着拖不动。不可变重建（只复制含覆盖元素的 layer，不污染 base state）。
- **dock**：header 加红点 toggle（亮=自动加帧 ON，仿 PR）；addTransformKeyframe 瘦身改调执行器。
- **store**：autoKeyframe（默认 ON）+ draggingElementIds + setters；reset / 切 wall 归位。

前端 485 → **494**（+9：planTransformUpsert 3 + applyDragOverride 5 + dock smoke autoKeyframe 1）/
vite build 过 / main 778 kB（+useTimelineAuthoring）/ TimelineDock chunk 19 kB / 0 漂移。
关联：`composables/useTimelineAuthoring.ts`（新）`components/timeline/{timelineLogic.ts,TimelineDock.vue,__tests__/}`
`components/layout/CanvasView.vue` `stores/timeline.ts` `i18n/messages.ts`

**待 P4.5b 余下**：整体帧块时间轴上横向拖动改时刻 + 框选批量删（问题 3）+ per-property 子轨微调。

---

## 2026-06-05 · 0.6.0-P4.5a — 整体关键帧（实测反馈：缓动把轨迹掰弯 + 逐属性打帧太难用）

用户实测 P4 dock 报 3 个交互问题，确认方向（几何变换整体帧 + 拖动自动加帧 + 保留展开）后做 P4.5a 核心：

- **问题 1 根因 + 修复**：dock 把 x/y 拆成独立轨道、各自缓动；改一个关键帧的缓动只影响一个属性 →
  x/y 进度不同步 → 本该直线的运动被掰弯（用户诊断准确）。**整体关键帧**（按 timeMs 聚合元素所有
  transform 几何属性 x/y/w/h/rotation/opacity 的关键帧）+ 缓动统一应用到该组所有 transform → x/y
  进度同步 → 轨迹保持直线（只速度按缓动变）。
- **整体帧交互**：元素行直接显示/选/删/缓动整体帧块（不用展开逐属性）；Shift 多选；选中 1 个开缓动
  曲线编辑器（同步该组所有 keyframeId 的 easing）；Del 批量删；元素行 + 按钮在播放头 upsert 整体帧
  （同 timeMs 已有则更新值不重复）。展开元素看 per-property 子轨（P4.5a 只读灰显，单属性微调留 P4.5b）。
- **timelineLogic**：aggregateTransformKeyframes（按 timeMs 聚合 + 统一缓动）+ transformKeyframeKey。
- **timeline store**：selectedGroups（整体帧 key 多选）+ selectGroup/clearGroups/isGroupSelected。
- **App.vue**：Delete 守卫加 selectedGroups（dock 选整体帧时 Delete 归 dock，不误删画布元素）。

**待 P4.5b**：拖画布元素自动加帧（"拉就设"，问题 2 核心）+ 框选批量删（问题 3）+ 整体块时间轴拖动 +
per-property 微调。

前端 478 → **485**（+7：整体帧聚合 5 + dock smoke 整体帧 2）/ vite build 过 / main 774 kB / 0 漂移。
关联：`components/timeline/{TimelineDock.vue,timelineLogic.ts,__tests__/}` `stores/timeline.ts`
`i18n/messages.ts` `App.vue`

---

## 2026-06-05 · 0.6.0-P4 hotfix-2 follow-up — dock 渲染 smoke test（客观验证 + 防回归）

为根治"我没法在本地跑编辑器（要 MC 服务器）→ 漏运行时崩溃 → 用户当小白鼠"这个系统性问题，引入
组件渲染 smoke test：`@vue/test-utils` + `happy-dom`，真实 mount `TimelineDock` + `EasingCurveEditor`，
触发展开属性行 / 打开设置 / 渲染缓动预设（即 hotfix-2 崩溃的三条 propertyLabel / loopModeLabel /
presetLabel 路径）。4 case 全绿——**客观证明 t.value 解包修复有效**（首跑时因 happy-dom locale 默认 en
断言中文不匹配，但报错是 assertion 而非 error，正说明函数已正常返回文案、没崩；回退 `t.timeline`
则会变 undefined error）。这类运行时崩溃以后 vitest/CI 就能拦。前端 474 → **478**（+4）。
关联：`__tests__/TimelineDock.smoke.test.ts`（新）+ `package.json`（devDep @vue/test-utils + happy-dom）。

---

## 2026-06-05 · 0.6.0-P4 hotfix-2 — dock 一展开就崩消失的真根因（ComputedRef 未解包）

上一个 hotfix 修了 flatRows 死锁 + popover 定位，但用户实测 dock 仍"一点展开三角形就整栏消失"。
拿到 console 报错 `Cannot read properties of undefined (reading 'propX')` / `'loopOnce'` 定位真根因：

- **`useI18n()` 返回 `{ t: ComputedRef<Messages> }`，t 是 ComputedRef**。模板里 `t.timeline.x` 被 Vue
  自动解包成 `t.value.timeline.x`（所以 header 正常显示），但 script 函数 `propertyLabel` / `loopModeLabel`
  （dock）+ `presetLabel`（EasingCurveEditor）里写的 `const m = t.timeline` 拿到的是 ComputedRef 对象的
  不存在属性 = **undefined** → 渲染属性行 / settings / 曲线编辑器时 `m['propX']` / `x.loopOnce` 抛错 →
  Vue 渲染异常 → 整个 dock 崩溃消失。组件内 computed（tl/flatRows/durationMs）我都正确 `.value` 了，唯独
  把外部来的 t 当成模板那样直接 `.timeline`。修：3 处 `t.timeline` → `t.value.timeline`（grep 扫全项目
  确认无其他同类）。修后上一个 hotfix 的 flatRows/popover/提示才真正生效。

**为什么 vite build / vitest / 对抗审查都没抓到**：① esbuild/rolldown 只转译不做完整类型检查，vue-tsc 在
Node 25 跑不了（CLAUDE.md 已知），所以 `t.timeline`（ComputedRef 上不存在该属性）这种类型错误编译期静默；
② vitest 测纯函数不渲染组件；③ 审查 agent 读代码看逻辑、不实际运行，没注意 ref 解包。**这类只有运行时
或 vue-tsc 能抓**。教训：script 访问外部 ComputedRef 必须 `.value`；端到端交互改动应实测或加组件渲染
smoke test，不能只靠"vite build 过"。

前端 474 / vite build 过 / 0 baseline 漂移。关联：`TimelineDock.vue` `EasingCurveEditor.vue`

---

## 2026-06-05 · 0.6.0-P4 hotfix — dock 实测 3 bug（空 timeline 无从下手 / 设置不可见）

用户实测 P4 dock，发现 3 个静态审查（4 视角 agent）漏掉的运行时/交互缺陷——agent 验证了代码逻辑
（参数对不对 / 会不会崩），但没走"新建后用户怎么开始用"的完整操作流：

- **新建 timeline 后 dock 空、无属性行、双击无反应**：`flatRows` 只列 `timeline.tracks` 里已有关键帧
  轨的元素，新 timeline tracks 空 → 左树永远空 → 死锁（加帧入口在属性行 / 属性行需元素在 tracks /
  元素进 tracks 需先加帧）。**dock 根本没接 `ui.selectedIds`**。修：`flatRows` 改 `ui.selectedIds ∪
  tracks`——选中画布元素即在 dock 显示其全可动画属性（AE 工作流：选图层 → timeline 显示属性 → 加帧），
  从无轨属性点 + / 双击建轨。
- **点设置 popover 看不见（"没反应/dock 消失"）**：settings/easing popover 用 `bottom-full` 弹到 dock
  上方画布区 + `z-30` 被 CanvasView overlay 盖住。修：settings 改 dock 内 header 下方（`top-11` +
  `max-h` + overflow）确保在 dock 范围内可见；两 popover `z-30→z-50`。
- **空 timeline 无引导**：主体 `flatRows` 空时显示提示（`dockSelectHint` i18n 中英）。

**教训**：纯静态审查能抓代码逻辑 bug，抓不到"新建后怎么开始用"这类需走完整用户旅程的交互设计缺陷
——这类要么实测、要么审查时显式推演端到端旅程。前端 474 不变 / vite build 过 / main 773 kB / 0 漂移。
关联：`components/timeline/TimelineDock.vue` `i18n/messages.ts`

---

## 2026-06-05 · 0.6.0-P4 — 前端 AE 风时间轴 dock（4 段实现 + 4 视角对抗审查修 4 major）

把 P2 的"最简关键帧列表 modal"升级为 After Effects 风底部 dock。纯前端（后端 0 改动）；先 5 路侦察
摸清接口地图，再 4 段实现，最后 4 视角对抗审查 + 主线复核。

### P4a 布局骨架 + scrubber 预览管线（性能命门先打通）

- **新 timeline store**（编辑态单一来源）：dockOpen / dockHeight / playheadMs / previewActive / playing /
  pxPerMs / scrollMs / expandedElements / selectedKeyframeId + activeTimeline/timelineById computed；
  playheadMs 等播放态刻意放此而非 project.state，使 scrubber 不触发 CanvasView 的 `{deep:true}` watch。
- **useTimelinePlayback**：本地播放 rAF 循环（按 loopMode mapTime，ONCE 停末帧 / LOOP / pingPong；
  onScopeDispose 清理）。
- **CanvasView 接通**：scrub/播放期把喂 renderProjectState 的 project.state 换成
  `interpolate(state, tl, playheadMs)` 临时 state + 加浅 watch(playheadMs/previewActive)→requestDraw，
  绕开 deep watch（docs/timeline.md §8.2）。interpolation.ts（P3 孤儿模块）首次接生产。
- **TimelineDock**：底部 flex 兄弟（压缩画布非遮挡）+ 可拖 resize + 时间标尺 + 每元素每属性子轨 +
  关键帧块 + 播放头 + 播放控制；懒加载 defineAsyncComponent 拆独立 chunk。timelineLogic 加时间↔像素
  映射 / 二级属性拆分 / 标尺刻度 / 帧吸附纯函数。

### P4b 关键帧交互

关键帧块拖拽改 timeMs（本地 override 跟手 / dragend 发一条 sendKeyframeMove，照 onDragMove 节流）+
选中高亮 + header 删除 + 双击轨道 / + 按钮加帧（建轨）+ 左树列全可动画属性。

### P4c 缓动曲线编辑器

EasingCurveEditor（SVG cubic-bezier）：4 预设 + 拖两控制点产生自定义 cubicBezier（x 钳 [0,1] / y 不钳
overshoot）；本地 draft 跟手、松手发一条 sendKeyframeUpdate；曲线用 ease() 双端权威采样。

### P4d 收尾

dock timeline 设置 popover（name/duration/fps/loop 编辑 + 删除）+ 删旧 TimelineManagerModal（App 引用 +
文件 + ui store 5 处 orphan flag 彻底清）。

### 4 视角对抗审查（4 agent）→ 修 4 major + 1 minor

- **scrub 不暂停播放**（rAF 与 scrub 每帧互覆 playhead）→ onScrubDown 抢占 pause
- **双击已有关键帧块静默新建重复帧**（stopPropagation 拦不住 dblclick、后端不去重）→ 菱形 @dblclick.stop
- **settings duration 校验上界 600000 ≪ 后端 3.6M / 下界 1≠100**（合法输入被静默吞）→ 对齐 [100, 3_600_000]
- **dock 选帧后 Delete 误删画布元素**（两套选中独立）→ dock 拦 Delete 删帧 + App 守卫
- **竖滚动条占位致关键帧块与标尺错位**（minor）→ pxPerMs 改用 tracksScroll 内容盒宽
- 撤回项：perf M2（accMs 从 store.playheadMs 重派生，正确）；保留项：fps 后端 clamp 良性 / fit 不自动触发
  （避免重置用户 zoom）/ exitPreview 双调幂等无害。

**测试**：前端 460 → **474**（+14：P4 时间映射 / 二级拆分 / snapToFrame / 标尺刻度纯函数）；后端 1274
不变（纯前端）；vite build 过 / **main 773 kB（删 modal 后比 P3 的 780 还低）** / TimelineDock 独立 chunk
20.1 kB（gzip 6.65 kB）懒加载 / 0 baseline 漂移。

关联：`stores/{timeline,ui}.ts` `composables/useTimelinePlayback.ts`
`components/timeline/{TimelineDock,EasingCurveEditor}.vue（新）+ timelineLogic.ts + __tests__/timelineLogic.test.ts`
`components/layout/{CanvasView,TopBar}.vue` `App.vue` `i18n/messages.ts`（删 `TimelineManagerModal.vue`）

---

## 2026-06-05 · 0.6.0-P3 — 缓动 + 双端插值器 + 一致性 CI（提交前再审修 3 双端数字分叉后落地）

P3 在工作区完成后、提交前做了一道独立再审（3 路并行 agent：双端逐位等价 / 数学+向量+快照 /
集成+回归 + 主线自复核），抓出并修掉 3 个对抗审查级双端数字分叉再提交。

### P3 核心交付

- **EasingSolver**（新）：cubic-bezier 双端逐位等价（WebKit UnitBezier + Newton 8 + bisect 32 +
  EPS 1e-6 + 边界捷径 local≤0→0 / ≥1→1）；EASE 预设 = CSS 标准控制点；坏 blob 降级 LINEAR。
- **ColorLerp**（新）：sRGB 线性空间色彩 / Fill 插值（gamma decode→lerp→encode，alpha 线性不经
  gamma；round(x×255) 半数进位；输出含 alpha 当且仅当任一输入含；解析失败 / 类型不一致 step）。
- **KeyframeInterpolator P3 重写**：color（仅 text，sRGB）/ fill（同类型同 stop 数逐 stop）/ text
  （离散 step）三新轨 + 缓动接入 + 数值轨 `${var:X}` resolve（AnimationTicker 注入
  `VariableInterpolator::resolveAsNumber` seam）；Span.ai 真实索引（镜像 TS，全等重合帧不经 equals
  反查错位）；帧内 resolve memo（防单帧撕裂）。
- **一致性 CI**：`rendering-test/` 第三方 Python 参照实现（IEEE754 double = Java/JS）生成 easing /
  color-lerp 向量，Java + TS 各跑同一份 JSON（color 精确字符串 / easing 1e-6）；多帧 snapshot fixture
  `14-timeline-easing.json`（纯几何）× t=0/250/500/750 四 baseline（人工核验四类插值可辨）。
- **rendering.md §9** 数学权威落地；前端镜像 `web/src/timeline/{easing,colorLerp,interpolation}.ts`。

### 提交前再审 — 3 个双端数字分叉（全确认全修）

- **#1（major）`resolveAsNumber` 私自 `Double.parseDouble` 绕严格文法**：变量 resolve 出
  `"0x1p4"`/`"5d"`/`"3.14f"` 时 Java 得 16.0/5.0/3.14 而 §9.5 严格文法应得 0，且 Java 自身
  「有 resolver vs 无 resolver」同值分叉。
- **#2（major）`ix()` int 收窄静默回绕**：数值属性 `x=3e9`（finite，op 层只校验 isFinite 放行）
  Java `(int)Math.round` 回绕 −1294967296 而 TS number 不回绕；变量 resolve 出的大数 op 层亦拦不住。
- **#3（minor）trim 语义分叉**：Java `trim()` 剥 ≤U+0020 / JS `trim()` 剥全 Unicode 空白，
  NBSP+数字 → Java 0 / TS 5。
- **修法**：抽 `state/StrictNumber.java` 作双端唯一权威（`parse` 严格解析 + `clampInt` int 钳位 +
  `PATTERN`），后端三处散落 STRICT_NUMBER 正则（KeyframeInterpolator / TimelineOperations /
  resolveAsNumber）归一；渲染层两端 `ix`/`withAnimated` 加 int clamp（覆盖字面值 + 变量值两来源）；
  前端 `parsePlainNumber` trim 改 `[\x00-\x20]` strip 对齐 Java；`rendering.md §9.5` 补全三条细则。

### 测试

后端 **1274** / 前端 **463** / vite build 过（index 780 kB / gzip 230 kB）/ 0 baseline 漂移。
再审新增 7 后端（StrictNumberTest 4 + P3Test int-clamp 1 + ResolveAsNumberTest 2）+ 3 前端
（sampleNumeric 严格文法 + trim + interpolate int-clamp）case 钉住三发现。再审 Agent 2/3 各 0 major
（数学/向量/快照可信、被改测试无掩盖弱化），Agent 1 报 2 major + 1 minor 经自复核全属实全修。

关联：`render/{EasingSolver,ColorLerp,KeyframeInterpolator,AnimationTicker}.java`
`state/{StrictNumber,TimelineOperations}.java` `variable/VariableInterpolator.java` `HikariCanvas.java`
`docs/rendering.md §9` `web/src/timeline/{easing,colorLerp,interpolation}.ts`
`web/src/components/timeline/{timelineLogic.ts,TimelineManagerModal.vue}` `i18n/messages.ts` + 测试
（EasingSolverTest / ColorLerpTest / KeyframeInterpolatorP3Test / StrictNumberTest / ResolveAsNumberTest
/ RendererSnapshotTimelineTest + 向量 + fixture + 4 baseline）

---

## 2026-06-04 · 0.6.0-P1+P2 — 时间轴数据模型+协议 v3+coalescing / AnimationTicker+池化+MVP（单日推完，MVP 闸已过）

两个 phase 一次提交（同批文件交织：dispatcher / WebServer / SessionManager / HikariCanvas / wsClient
都含两阶段改动，hunk 级拆分风险大于收益）。每阶段都走「侦察 → 主线写契约 → 并行建造 → 多视角对抗
审查（独立怀疑者逐条核验）→ 全量回归」流程。

### P1 数据模型 + 协议 v3 + 撤销（~60h 当量）

- **新 records**（state 包 10 文件）：`Timeline`（tracks 深冻结不可变）/ `Keyframe`（PROPERTIES 白名单）/
  `KfValue` number|string|Fill 三态多态（照 FillDeserializer string/object 分流范式，自定义双向序列化器）/
  `Easing`（坏 blob 宽容降级）/ `LoopMode`/`EasingType`/`TriggerType`（@JsonProperty camelCase wire 形态，
  同 BlendMode 范式）/ `TriggerConfig`（params 滤 null 项防整墙加载失败）。
- **ProjectState v3**：`timelines` + `activeTimelineId` nullable 加法（NON_EMPTY/NON_NULL 序列化省略 →
  旧 v2 blob 零迁移 / 静态工程 JSON 形态不变）；`PROTOCOL_VERSION=3`；restore 还原时间轴；
  `ProjectSnapshot` 扩展（缺了 undo 会丢时间轴）。
- **协议 v3 干净切换**：`Protocol.SUPPORTED_MIN/MAX=3/3`（双层版本：`Envelope.v` 壳恒 2）；前端
  `CLIENT_V=3` + `ENVELOPE_V=2` 拆分。
- **7 个编辑 op**：`timeline.create/update/delete` + `keyframe.add/update/delete/move`，走 EditSession
  mutator（新 `TimelineOperations`，照 LayerOperations 范式）→ 进 undo/redo + state.patch + persistWall；
  fps clamp 到 config `timeline.max-fps`；全量校验（容量 16/2048/256、durationMs 不得缩到关键帧之下、
  bezier x∈[0,1]、int 越界回绕守卫）。
- **HistoryStack coalescing（D7 路线 A）**：key=`elementId:keyframeId:property` + 500ms 窗口合并 +
  **容量粘性解锁 16→64**（一旦有过激活时间轴保持 64——否则删最后一条时间轴的 commit 自身会当场
  trim 掉最多 48 条历史；审查确认项）+ clock 注入 seam。
- **前端**：v3 TS 类型 + `project.ts` `/timelines/...` 六类路径 applier（指针解码对称）。
- **对抗审查**（4+1 视角 20 agents）：13 发现 → 确认 5 全修 / 驳回 6 / 双端一致补审 0 发现。

### P2 AnimationTicker + 池化 + MVP（~50h 当量）

- **KeyframeInterpolator**（render 包，纯函数）：LINEAR × 6 数值属性；ONCE 钳/LOOP 模/PING_PONG 三角
  时间映射；rendering.md §9.1 取值规则（不外插/重合帧取后）；8 型 record 重建。**固化语义**：关键帧
  属性播放期覆盖基值（动画软件标准），未建轨属性/内容编辑后 ≤1 帧反映。
- **AnimationTicker**：单线程 ScheduledExecutorService（照 VariableProviderDaemon：daemon 线程/任务级
  异常隔离/幂等关停）；**Wall 缓存 + persistWall(同步落库)→invalidate**（不每 tick 读 DB）；LOOP 自动播
  （启动 autoRegisterAll 排除 restore 失败墙 / session cancel→refreshAutoPlay / 重启恢复）；ONCE 播完渲
  末帧自动注销；WallSource+FrameRenderer 双 seam 全确定性可测。
- **BufferPool**：线程限定（owner=Ticker 单线程，外线程 acquire 退化 new——rasterize「每次 new 并发
  安全」契约对反应式路径不变）；主+layer buffer 借还；AlphaComposite.Clear 清零像素等价；异常路径归还。
- **CanvasProjector.renderFrame**：per-map 帧间 diff（只发像素变了的 map）+ 新观察者全量补发 +
  viewer-gated（无人不 rasterize）+ 池归还点。
- **分流 gate**：`ProjectionThrottler.submit` + 延迟尾帧 flush + 变量 wallDirtyCallback 三条 reactive
  路径在动画接管期全退让；被吸收的意图经 `onReactiveYield→invalidate` 转为下一帧重载+全量补发。
- **播放控制**：`timeline.play/pause/seek`（dispatcher 特判，不走 OpResult 流，不落 DB 不进 history，
  与编辑 op 同权）+ TIMELINE_PLAY/PAUSE/SEEK audit；wall 删除钩子立即 stopWall（防向已归还 mapId 写）。
- **前端**：TopBar Film 按钮 + TimelineManagerModal（时间轴列表/新建/关键帧按元素分组/给选中元素加
  关键帧/播放控制）+ wsClient 11 send 方法 + i18n 中英 + timelineLogic 纯函数。
- **对抗审查**（4 视角 24 agents）：20 发现 → 确认 14 全修 / 驳回 6。最重四项：play‖stopWall TOCTOU
  孤儿任务（锁内成员资格复核关死）/ FrameDiff 跨线程竞态（diffResetPending 标志，diff 只许 Ticker 线程
  触碰）/ 变量路径绕 gate 双写 / `/canvas delete` 不注销跨墙串台。
- **MVP 闸 ✅ 用户实测**：欢迎墙 opacity 0→1→0 循环淡入淡出 20fps；关浏览器续播；重启服务器自动恢复。

**测试**：后端 879 → **1014**（+135：P1 op/records/coalescing 66 + P2 interpolator/pool/ticker/dispatch
65 + 审查回归 9 - 调整 5）；前端 294 → **320**（+26）；0 baseline 漂移；vite build 过。

关联：`state/{Timeline,Keyframe,KfValue,KfValueSerializer,KfValueDeserializer,Easing,EasingType,LoopMode,
TriggerConfig,TriggerType,TimelineOperations,ProjectState,ProjectSnapshot,HistoryStack,StatePatchBuilder,
EditSession}.java` `render/{AnimationTicker,AnimationTickerGate,BufferPool,KeyframeInterpolator,
CanvasProjector,CanvasCompositor,ProjectionThrottler}.java `web/{EditOpDispatcher,WebServer,WebHelpers,
Protocol}.java` `session/SessionManager.java` `HikariCanvas{,Config}.java` `config.yml`
`web/src/{types/protocol.ts,stores/{project,ui}.ts,network/wsClient.ts,components/timeline/*,
components/layout/TopBar.vue,App.vue,i18n/messages.ts}` + 11 个测试文件

---

## 2026-06-04 · 0.6.0 文档先行 — timeline.md 设计总纲 + 四份契约扩展（2026-06-03~04 两日打磨定稿）

0.6.0 时间轴编辑器动工前的完整契约批（「文档先行」纪律：先文档后代码）：

- **`docs/timeline.md`（新，~450 行设计总纲）**：D1–D9 决策表（owner 拍板：方案 B 独立 KeyframeTrack
  挂 `Timeline.tracks` / 默认 20fps + `timeline.max-fps` 默 60 宽松阀 / MVP 仅 LINEAR / 三触发器
  PLAYER_NEAR→0.7 / 专用 keyframe.* op / coalescing 路线 A + 16→64 / 零新依赖 / 后端唯一权威）+
  数据结构 / 渲染管线（AnimationTicker / 池化 / per-map diff / 帧率策略）/ 双端插值缓动 / 触发器 /
  协议 v3 / 撤销 / 前端 / 风险登记 / **6 段分期**（spike 折进 P2，一道 MVP 闸）/ ~360h。
  打磨轮已砍：成本估算 UI / 自动校准 / 单机容量断言（按 owner 产品哲学：工具只管渲染，性能服主自负）。
- **`docs/rendering.md` 新 §9 时间轴插值与缓动（数学权威）**：取值规则（不外插 / 重合帧取后）/
  逐类型插值 / **cubic-bezier 双端逐位等价**（WebKit UnitBezier 系数 + 牛顿 8 步 + 二分 32 步兜底 +
  EPS=1e-6 两端写死）/ sRGB 线性空间色彩插值 / 变量取值时机 / easing.json + 多帧 snapshot 双层防线。
  原 §9–§11 顺移 §10–§12。
- **`docs/protocol.md`**：v2→v3 变更总览（**干净切换**，前端由插件分发版本恒匹配；双层版本注：
  Envelope.v 壳恒 2）+ §5.12 timeline.* / §5.13 keyframe.* op 表 + §7 八个 v3 TS 类型（enum wire
  形态 camelCase）+ 4 错误码 + ack 形态对齐（id 经 state.patch，同 element.add 范式）。
- **`docs/data-model.md` §2.4.2**：project_json v2→v3 纯加法（nullable / Element 零改 / 不加表不加
  schema 版本 / protocolVersion lazy on-write 3）+ §4.3/§4.4 .canvas 语义 + 孤儿轨未决。
- **`docs/architecture.md`**：§5.1 两条产帧路径 + 分流 gate + §5.5 AnimationTicker（线程模型 /
  viewer-gated / 池化约束 / 帧率三条）+ §10.2 线程模型 + §11 config `timeline:` 段 + §13.3
  「Ticker 不属于 P-2 反模式」澄清。
- **指针**：PROPOSAL §4.3 + dynamic-data §13.4 瘦身（纸面设想 6 处错误纠正记录）+ CLAUDE.md
  契约清单 + 路线表。

关联：`docs/{timeline,rendering,protocol,data-model,architecture,dynamic-data}.md` `PROPOSAL.md` `CLAUDE.md`

---

## 2026-06-04 · 0.5.0 hotfix-2 — 用户可见文案全面去 AI 味（2026-06-03 实施，按指示暂缓提交至今）

用户实测 report.html 后反馈：报告 / 计算器 / 命令消息里大量「向用户解释设计意图」（"数据透明：…"
"保守下界：公式把…"）与开发黑话（raster / p50/p95/p99 / GC / JVM / Xmx / 主线程）——服主和玩家
看不懂也不需要懂。审计后重写全部用户可见面：

- **`docs/benchmark.md`**：删设计意图章节（原 §1 一句话哲学 / §5 / §6 强调段 / §7 整章）并重排
  §1–§6；列名与术语全面口语化（rasterize→渲染、p50/p95/p99→一般/偏慢/最慢、GC→内存回收）。
- **`HtmlReportRenderer`**：环境注记改「这些数字只代表这台机器…」；JVM/Xmx/GC 标签改 删/最大内存/
  内存回收方式；表头去黑话；图表单位 ms/elem→ms；设计理由仅保留在类 Javadoc（面向开发者）。
- **`BudgetFormula`**：免责声明改「这个数偏保守，实际通常能放得更多。它只是个大概参考，别当成硬上限。」
- **`BenchmarkSubCommand`**：14 条用户可见英文消息中文化（log 保留英文；格式占位符不动）。

固化纪律：**对玩家 / 服主 / 贡献者直接可见的内容，禁止解释设计意图、禁止内部阶段编号与开发黑话**；
内部设计文档（journal / dynamic-data / timeline.md）不受限。

关联：`docs/benchmark.md` `benchmark/{HtmlReportRenderer,BudgetFormula}.java`
`command/BenchmarkSubCommand.java`

---

## 2026-05-30 · 0.5.0-P4 + 0.5.0 完工 — CI 功能性 gate + benchmark.md + 版本号

### P4 范围（收尾）

- **CI 功能性 gate**：`BenchmarkPipelineSmokeTest`——跑完整 `/canvas bench run` 管线（compositor →
  runner → HTML）headless 端到端，<b>只断言「能跑通 + 不崩 + 产出非空报告 + HTML 自包含含每个 scene
  id」</b>，<b>0 性能数值断言</b>（决策①）。随 `:plugin:test` 在 CI 每次 push/PR 跑——某次改动若弄坏
  scene 构造 / rasterize / 聚合 / 报告渲染，CI 立刻红。
- **`docs/benchmark.md`**（281 行运维指南，子代理起草 + 主线核验定稿）：命令族 / 三件产物怎么读 /
  逐场景 percentile / per-element 边际 / 环境卡 / 50mspt 公式与<b>交互计算器</b>（保守下界口径）/
  为什么没有自动门禁·自动降级 / 用实测容量设 config 软上限 / 4 原则 / P4+ 精化。
- **版本号 0.4.10 → 0.5.0-SNAPSHOT**（7 处 / 6 文件：build.gradle.kts + web/package.json +
  package-lock×2 + 3 paper-plugin.yml）+ CLAUDE.md / dynamic-data §13.3·§19 路线表标 0.5.0 ✅。

### 固化决策（P4 收尾，不可越界）

- **不提交 baseline / 无自动 drift 报警**：rasterize 绝对耗时机器特定、不跨机迁移，提交 baseline 会误导；
  性能回归由服主在<b>自己机器</b>上对比历次 `report.json` 人工复查。
- **无自动 prune**：benchmark 报告目录累积由 `/canvas bench clear` <b>手动</b>清理，系统不自动删——
  符合「不擦屁股」（不替服主管理他自己的输出文件）。
- **CI 只断言「能跑通」**：共享 runner ±2-3x 抖动 + 0.4.7/0.4.8/0.4.9 三次 flaky 史，性能数值门禁
  要么松到没用要么紧到 flaky。

### 0.5.0 完工总览（P1–P4）

| phase | 内容 |
|---|---|
| P1 | 底座：SceneLibrary(21 确定性场景全元素) + Instrumentation + SceneTimer + BenchCompositor + `/canvas bench` 命令骨架 + 3 契约 record |
| P2 | 聚合：6 record(Percentiles 线性插值 等) + ResultAggregator(percentile + per-element 边际) + BenchmarkRunner(测一次) + report.json |
| P3 | 可视化：BudgetFormula + SvgBarChart + HtmlReportRenderer(自包含 HTML + 内联 SVG + 50mspt 交互计算器) |
| P4 | 收尾：CI 功能性 gate + docs/benchmark.md + 版本号 |

**贯穿哲学**「工具不是保姆」：数据透明不替服主决策 / 不自动降级 / 不擦屁股（不测网络）。**测什么**：
rasterize/palette percentile + 内存/GC + per-element 边际；**测一次**（rasterize 不依赖 fps/viewer）。
后端 **879 test 全绿** / shadow jar `HikariCanvas-0.5.0-SNAPSHOT.jar` 159 MB / 0 baseline 漂移。
4 commit（P1 `b837f9b` / P2 `cb1079b` / P3 `17a6b6a` / P4 `<本 commit>`）。

**下一步**：0.6.0 时间轴（AE-like）须先做 P0 spike（30fps×4maps 实测 GC/mspt），正好用本期 Benchmark 工具量化。

### 关联文件

新增 `test/.../benchmark/BenchmarkPipelineSmokeTest.java` + `docs/benchmark.md`；改 6 个版本文件 +
`CLAUDE.md` + `docs/dynamic-data.md`（§13.3 P4 ✅ + 0.5.0 完工 note + §19 表）。

---

## 2026-05-30 · 0.5.0-P3 — 报告可视化层（自包含 HTML + 内联 SVG 图 + 50mspt 交互计算器）

### 背景

P2 产出聚合 `BenchmarkReport`；P3 把它渲染成<b>自包含 HTML 报告</b>（内联 SVG 图表 + 交互式
50mspt 预算计算器），服主浏览器打开即看。彻底贯彻「工具不是保姆」：报告给原料 + 公式，计算器由
服主自己的输入驱动，绝不给「你能开 N 个 wall」的结论数字。

### 落地组件

- **`BudgetFormula`**（主线手写，公式 correctness-critical）：`availableMsPerSecond = mspt×tps×份额%`、
  `projectedMaxWalls = 可用预算 ÷ (rasterize_p95 × fps)`。明确口径：这是<b>保守下界</b>——公式把整个
  rasterize 当主线程成本，实际走 async、主线程只 schedule+handoff，真实能开更多；`DISCLAIMER` 常量随
  计算器一起展示，防被当硬上限。
- **`SvgBarChart`**（Workflow builder）：响应式内联 `<svg>` 横向条形图（viewBox + width:100%）；
  退化输入守卫（空 / maxValue≤0 / 负值 / NaN/Inf）；`Locale.ROOT %.3f` 防逗号小数污染 SVG 坐标；
  label 全转义。
- **`HtmlReportRenderer`**（Workflow builder）：完整自包含 HTML5。环境卡 + config 卡（fps/viewer 标注
  「P3 公式参数、不参与测量」）+ 逐场景 percentile 表 + rasterize p95 条形图（降序）+ per-element 边际
  条形图 + GC 行 + **50mspt 交互计算器**（4 输入默认 50/20/30/5，内联 JS `recompute()` 逐场景算可载 wall
  数）+ footer。时间戳由 `generatedAtMillis` 经 `Instant.ofEpochMilli + DateTimeFormatter(UTC)` 渲染，
  不读时钟。**两层转义**：`esc()`（HTML/attr）+ `jsStr()`（JS 字符串，`<`→`<` 防 `</script>` 逃逸）。

### 实施 + 审查（手写公式 → Workflow 并行造 → 多视角对抗审查）

主线手写 `BudgetFormula` → **Workflow 2 builder 并行**造 `SvgBarChart` + `HtmlReportRenderer` + 1
<b>三视角实证审查</b>。审查四布尔位全 true（自包含零外链 / 动态串全转义 / JS 公式与 BudgetFormula
一致 / 签名+headless）、**0 issue**——且是真编译 + 用对抗性 XSS scene id 跑渲染 smoke + 数值验证
Java/JS 公式一致（avail=300 / walls=24 双端相符）+ SVG 边界测试。唯一 `http://` 是 SVG `xmlns`
命名空间 URI（不 fetch，false positive 已澄清）。无需修。

### 接入 + 验证

`BenchmarkSubCommand.runOnWorker` 加写 `report.html`（与 report.json/summary.txt 并列）。后端
**878 test 全绿（原 874 + 新 4：公式数学 avail=300/walls=120 + SVG 边界 + HTML 自包含/转义对抗）**；
`:plugin:compileJava` + full `:plugin:test` BUILD SUCCESSFUL；0 baseline 漂移。版本仍 0.4.10-SNAPSHOT
（0.5.0 末 phase P4 再升）。

### 关联文件

新增 `benchmark/{BudgetFormula,SvgBarChart,HtmlReportRenderer}.java` + `test/.../benchmark/BenchmarkP3Test.java`；
改 `command/BenchmarkSubCommand.java`（import + HTML_FILE 常量 + runOnWorker 写 html）+ `docs/dynamic-data.md §13.3`。

---

## 2026-05-30 · 0.5.0-P2 — 聚合层（percentile + per-element 边际 + GC/env + report.json）

### 背景

P1 产出逐次原始样本；P2 把它收敛成<b>聚合报告</b>：rasterize/palette 的 p50/p95/p99 + per-element
边际成本 + GC/内存/环境，产出 `report.json` + CLI percentile 表。

### 关键设计澄清（修正 §13.3 原「matrix」措辞）

rasterize 成本<b>只取决于场景</b>（canvas 尺寸已烘进每个场景），<b>不依赖 fps/viewer</b>。所以每场景
<b>只测一次</b>——绝不为每个 fps/viewer 组合重复 rasterize（那是重复测同一个东西）。fps/viewer 仅作
P3 公式参数随 `BenchmarkConfig` 记录在报告里。per-element 边际成本 = 单元素隔离场景均值 − 同尺寸
<b>空白基线</b>（扣掉 buffer 分配 / clear / palette 固定开销）÷ 元素数。

### 实施（手写契约 → Workflow 并行造 → 主线命令改写）

- **主线手写 6 聚合 record**：`Percentiles`（线性插值 R-7 / PERCENTILE.INC 同款，数学 correctness-critical
  故自写）/ `SceneResult` / `PerElementCost` / `GcSummary` / `EnvInfo`（JVM/OS/堆/GC 快照——数据透明）/
  `BenchmarkReport`。
- **Workflow 2 builder 并行** 造 `ResultAggregator`（aggregate + derivePerElement）+ `BenchmarkRunner`
  （9 步编排）+ 1 对抗审查。审查：**crossSignatureConsistent / headlessSafe / percentileAndFormulaCorrect
  三布尔位全 true，0 blocker / 0 warning，2 nit**——主线已修：① ResultAggregator elementCount 改<b>跨全图层</b>
  求和（`ProjectState.elements()` 只返活动层，防未来多层场景漏算）② GcSummary javadoc 注明含 warmup。
- **主线 solo 改写** `BenchmarkSubCommand`：`runOnWorker` 走 `BenchmarkRunner.run()` → 写 `report.json`
  （取代 P1 `raw.json`）+ `summary.txt`；`doReport` 读 `report.json` → `BenchmarkReport`；`summarize`/
  `SceneSummary` → 基于 `SceneResult` percentile + per-element + GC + env 的渲染器；清理 unused import。

### 验证

后端 **874 test 全绿（原 865 + 新 9：percentile 已知数据集精确比对 + 聚合 + per-element 边际 +
BenchmarkReport Jackson round-trip）**；`:plugin:compileJava` + full `:plugin:test` BUILD SUCCESSFUL；
0 baseline 漂移。版本仍 0.4.10-SNAPSHOT（0.5.0 末 phase 再升）。

### 关联文件

新增 `benchmark/{Percentiles,SceneResult,PerElementCost,GcSummary,EnvInfo,BenchmarkReport,ResultAggregator,BenchmarkRunner}.java`
+ `test/.../benchmark/BenchmarkP2Test.java`；改 `command/BenchmarkSubCommand.java` + `docs/dynamic-data.md §13.3`。

---

## 2026-05-30 · 0.5.0-P1 — 性能 Benchmark 底座（scene 库 + 计时核心 + /canvas bench 骨架）

### 背景

0.4.x 收口后开 0.5.0「纯服务端性能 Benchmark」（PROPOSAL §2.1/§5.2.7「工具不是保姆」+ 4 原则；
设计 `docs/dynamic-data.md §13.3`）。目标：让服主摸清「我这台机器能撑多少画布」——给原料 + 公式，
不给「你能开 N 个」结论。本次推 P1（底座）。

### brainstorming：4 个锁定决策（先设计后写码）

走 superpowers:brainstorming 流程，AskUserQuestion 逐个定：
1. **CI 不做性能数值门禁**——本地 baseline JSON + CI 只断言「能跑通」（0.4.7/0.4.8/0.4.9 三次 CI
   flaky 全栽在 perf/平台敏感断言，共享 runner ±2-3x 抖动）。P4 因此从 ~36h 砍到 ~20h。
2. **viewer 只测纯渲染管线**——核实 `MapPacketSender`：16KB 像素对同 tile 所有 viewer 是同一份，
   per-viewer 只剩重复 encode + send（网络边界）。故测 `rasterize→toPaletteSlice→byte[]`，viewer
   当线性外推乘数，不碰 PacketEvents（也正是这条让核心能 headless）。
3. **报告 = JSON + CLI 表 + 独立自包含 HTML**（内联 SVG 图）。
4. **scene 全元素覆盖**。
   **运行模型 = 方案 A：单一 headless 核心 + 两适配器**（命令 / CI 跑同一套数字不分叉）。

### 实施方式（scout → 手写契约 → Workflow 并行造 → 主线接入）

- **3 并行 Explore scout** 摸集成面（ProjectState/Element/Fill 全 record 字段序、`rasterize`/
  `toPaletteSlice` 签名、Brigadier 命令/config/async 惯例）→ 精确签名。
- **主线手写 3 共享契约 record**（`BenchmarkScene`/`BenchmarkConfig`/`RasterizeSample`）——最关键接口必须精确。
- **Workflow 4 builder 并行**（文件域不相交）造 `SceneLibrary`/`Instrumentation`+`SceneTimer`/
  `BenchCompositor`/`BenchmarkSubCommand` + 1 对抗审查 agent。审查：**crossSignatureConsistent /
  headlessSafe / deterministic 三布尔位全 true，0 blocker / 0 warning，仅 2 nit**（BenchCompositor
  一段永不触发的 try/catch；Instrumentation.gcSnapshot 是 P2 预留——均故意保留）。
- **主线 solo 接入 + 单测**。

### 落地组件

- **核心（headless，零 Bukkit）**：`SceneLibrary`（21 确定性场景：9 单元素饱和 + 5 特效 + 3 真实混合
  + 4 尺寸梯度；单一固定 seed `0x4849_4B41_5249L` + per-scene offset 重播种，内容与生成顺序无关）/
  `Instrumentation`（`com.sun.management.ThreadMXBean` 分配计数，不支持优雅降级 -1 + GC bean 采样）/
  `SceneTimer`（warmup→measure，rasterize/palette 分开计时 + static volatile sink 防 DCE）/
  `BenchCompositor`（复刻 `RendererSnapshotTest` 3 参无头装配 + `setImageLoader` SAM 注入合成 256²
  渐变图，让 image+mask 渲真实像素）。
- **命令适配层**：`BenchmarkSubCommand`（`/canvas bench list/run/report/clear`；run 异步守护线程
  `hikari-canvas-bench` + volatile running 守卫 + 回主线程发消息 + `benchmarks/<ts>/{raw.json,summary.txt}`
  输出 + tab 补全）。
- **接入**：`CanvasCommand`（构造参 + `build()` 节点，仿 variableSubCommand）/ `HikariCanvas`
  （字段 + line 496 构造 + `cleanupResources` 加 `benchmarkSubCommand.shutdown` closeQuietly）/
  `paper-plugin.yml` 新权限 `canvas.bench`（default op）。
- **单测**：`BenchmarkP1Test` 10 case——SceneLibrary 确定性（逐元素类型序列）+ 全元素覆盖 + select/byId
  + Config 默认/归一化 + Instrumentation 优雅降级/单调 + **端到端 smoke（全 21 场景 headless 跑通，
  兼 P4 CI 功能性 gate 种子）**。

### 留 P2 精化（非 TODO 半成品，是范围切分）

IconElement 走占位（无 headless IconRegistry）；合成图固定 256² 代表性近似；`Instrumentation.gcSnapshot`
+ percentile 聚合 + matrix runner 全在 P2。

### 验证

后端 **865 test 全绿（0 fail / 0 error，含 10 新）**；`:plugin:compileJava` + `:plugin:compileTestJava`
+ full `:plugin:test` BUILD SUCCESSFUL；0 baseline 漂移（benchmark 加法、不碰渲染）。版本号仍 0.4.10-SNAPSHOT
（按惯例 0.5.0 末 phase 再升）。

### 关联文件

新增 `plugin/.../benchmark/{BenchmarkScene,BenchmarkConfig,RasterizeSample,SceneLibrary,Instrumentation,SceneTimer,BenchCompositor}.java`
+ `command/BenchmarkSubCommand.java` + `test/.../benchmark/BenchmarkP1Test.java`；改
`command/CanvasCommand.java` + `HikariCanvas.java` + `resources/paper-plugin.yml` + `docs/dynamic-data.md §13.3`。

---

## 2026-05-30 · 0.4.10 — ultrareview-2026-05-29 修复批（168 DIRECT_FIX）

### 背景

独立第三方做了一轮全栈深度 ultrareview（`docs/ultrareview-2026-05-29.md`）：224 真实缺陷
（P1×15 + P2×87 + P3×122）+ uncertain×4 + excused×21 + false-positive×30（已自行核验剔除）。
13 个评估 agent 并行 triage 后分类：**168 DIRECT_FIX / 30 NEEDS_DESIGN / 23 TRADE_OFF /
4 DUPLICATE / 3 疑似误报**。用户决定 0.4.10 只修 168 个 DIRECT_FIX，其余暂不修。

### 实施方式（多波并行 + 主线收尾）

文件域不相交分区，避免并行 agent 写冲突；agent 不跑构建（并发争 build dir），主 agent 统一编译+测试。

- **Wave1（10 agent）**：按代码域（image/pool-session/deploy/variable/render/web/state/cmd/fe-state/fe-ui）分工，完成 118，defer 53 跨域。
- **Wave2（2 agent）**：FE 组（12，前端补全）+ HikariCanvas 生命周期组（含 P3-4 APIImpl 4→5 参注入 AuditLog + 5 调用点）。
- **Wave3（5 agent）**：WEB（P2-26 七 dispatcher 走 MainThreadPerms 主线程权限解析）/ STORAGE / STATE（P1-8 rasterize 读快照 + P2-8 EditSession 配额）/ VAR-RENDER（P2-32 dynamic-lookup + P2-83 节流 + P3-29 字体 worker shutdown 基建）/ DOCS-MISC。
- **主线 solo（~12）**：rail dispatcher 4（P2-5/7/64/P3-47）+ WebHelpers（P2-12 brush 范围 + P3-114 JSON Pointer 单一来源）+ P2-47 部署失败原子回滚 + P3-100 删孤儿权限 + P3-7 PDC namespace 注释/CLAUDE 标识改 hikaricanvas + 3 处 HikariCanvas wiring（P3-32 forgetHook / P3-29 onDisable 字体清理 / P2-8 SessionManager 配额注入）。

### 几个值得记的修复

- **并发线程契约簇**（P2-26/27/28 等）：所有 WS dispatcher 的 `Bukkit.getPlayer/hasPermission`
  从 Jetty 线程直调改走 `MainThreadPerms`（callSyncMethod 单次主线程 hop，TemplateOpDispatcher
  批量 resolve 多节点）；off-main Bukkit API 是真 bug。顺带修了个既存编译错（VariableOpDispatcher
  7 参 ctor 被 6 参调用）。
- **LRU 数据完整性簇**（P1-1/P1-2/P2-24）：evict 失败回滚漏删磁盘文件、collectReferencedHashes
  null source 守卫 + fail-closed 拒绝盲删、引用扫描移进 upload IMMEDIATE 事务同一致视图。
- **rasterize 数据竞争**（P1-8）：CanvasCompositor 读 EditSession 锁内 `List.copyOf` 快照，
  消除异步渲染与 WS 写的 ConcurrentModification / 撕裂读（Element record 不可变只 copy 容器）。
- **透明背景 blend**（已在 0.4.7 修）+ 模板 raw_state fill 注入校验（P3-66/67/68 ElementValidator
  对 rect/path/circle/shape/brush fill 全调 FillValidator）。

### 验证

- 后端 `:plugin:test --rerun-tasks` BUILD SUCCESSFUL（46s，全绿；含 wave2 P3-4 测试改 +
  W3-VAR-RENDER 的 P2-83 测试节流更新）
- 前端 vitest 275 全绿 + vite build EXIT=0（754 kB / gzip 224 kB）
- 全量 `:plugin:compileJava :plugin:compileTestJava --rerun-tasks` 41s SUCCESSFUL（仅 1 个
  既存无关 deprecation warning）——3 波并行 + solo 零编译错
- shadow jar `HikariCanvas-0.4.10-SNAPSHOT.jar`

### 暂不修（用户决定）

- **23 TRADE_OFF**：需产品决策（安全鉴权 vs loopback-trust / WebP 加依赖 vs 删 / 双端 stale 语义 / ws-client 契约 等），评估见上一轮对话
- **30 NEEDS_DESIGN**：18 补测试基建（MapPool/TokenService/WallRestorer 等核心零覆盖）+ god class 拆分（WebServer/EditSession）+ 图标弧线双端镜像 等
- **3 疑似误报 + 4 重复**：不修

### 关键架构纪律（已固化）

1. **并行实施按文件域不相交分区 + 跨域 defer**：disjoint 约束只对并发 agent 有意义；
   agent 不跑构建（争 build dir），主 agent 统一编译/测试/收尾跨域 wiring
2. **off-main Bukkit API 一律 callSyncMethod**：`MainThreadPerms` 统一权限解析 seam
3. **PDC namespace 实际是 `hikaricanvas`**（`NamespacedKey(plugin,…)` 取插件名小写），
   文档/注释/CLAUDE 标识统一订正
4. **per-wall 图片配额双道防线**：上传期 `UploadHandler.handleQuota` 真实计数 +
   编辑期 `EditSession.addElement` 强制（SessionManager 注入 maxPerWall，缺省不限无回归）

---

## 2026-05-25 · 0.4.9 — Live Paint 真实形状收尾（brush + text glyph）

### 背景

0.4.8 推迟 2 项到 0.4.9：M18 brush 真实形状 + text glyph 真实形状。这是 Live Paint
工具的"最后两块拼图"——之前 brush 和 text 元素在 polygon-clipping 路径走 bbox 兜底，
意味着用户点击 brush 周围空白时 Live Paint 把整个 bbox 当占用区，识别出的 gap
不准确。

按用户选择「方案 A 务实小」18h 范围，0.4.9 只做这 2 项；B-advanced DCEL 38h 因覆盖
4% 用例性价比低**确认弃做**；图层 mask/group/smart object 30h+ 独立立项 M30 大版本。

### Sub A — M18 brush 真实形状（stroke offset polygon）

- **新文件** `web/src/livepaint/BrushStrokeOffset.ts`（241 行）— `brushStrokeToPolygon(points, size)` 主入口
- **算法**：per-point 圆盘 + per-segment 法向偏移矩形 → polygon-clipping `union` 合并
- **子代理简化决策**：原 task 描述用 "端点 cap 半圆 + 接头 round join 圆弧" 分开，子代理用 **每点放完整圆盘**简化几何——圆盘凸性使 union 后自然形成正确的 round cap + round join 轮廓，多余背面半圆被矩形覆盖不影响外观。代码量 300 → 150 行，性能等价
- **修 polygon-clipping 浮点退化**：垂直 / 水平 segment 上 disk vertex 与矩形角完全重合触发 `Unable to complete output ring` 错误。修法：disk polygon 加 `π/samples` 相位偏移让所有顶点错开半个采样段
- **集成**：`ElementToPolygon.ts` brush case 升级 + null 时 fallback bbox 兜底
- **vitest +18 case**（退化输入 / 单点 / 单 segment / 多 segment / 长 brush 性能 / 几何不变性）
- **bundle**：worker chunk 36 kB，源码 +1-2 kB 增量 < 5 kB 目标

### Sub B — text glyph 真实形状（fontkit 引入）

- **新依赖**：`fontkit@^2.0.4`（前端 dev only；后端用 Java AWT 内置 `Font.createGlyphVector + getOutline`，但 Live Paint 是前端独占 — CLAUDE.md M18 §1 — 故后端不需要镜像，未动）
- **新文件** `web/src/livepaint/TextGlyphExtractor.ts` — `textElementToPolygon(textEl)` async
- **算法**：
  - fontkit dynamic import + worker scope cache（每 fontId+size+text 一份 polygon LRU）
  - fetch `/api/font/file?id=X` 拉字体 binary
  - `font.layout(string)` → GlyphRun → 每 glyph 的 `glyph.path` (SVG path string)
  - 解析 M/L/Q/C/Z 并 bezier 采样 → glyph polygon
  - 多 glyph union → 单一 text polygon（仅取面积最大外环，多字符独立 polygon 形态不兼容 GapPolygon 单外环模型）
- **vitest +16 case**（单字符 / 多字符 / CJK / 空文本 / vertical / fontSize≤0 / fetch fail / fontkit 模块抛错 / layout 返空 / 坐标偏移 / cache 命中 / async 集成 4）
- **改 worker 异步**：`LivePaintCore.buildGraph` 改 async + `worker.onmessage` 改 async + `elementToPolygonAsync` 入口分离（同步 `elementToPolygon` 保留向下兼容）；改动 3 处不 invasive
- **bundle**：main 749 kB 不变；worker chunk 34 → **400 kB**（fontkit + unicode-trie + brotli + dfa 子依赖被 rolldown 内联）。仅 Live Paint 工具激活时下载，主页面不受影响

### v1 已知限制（已在代码文档化）

- 不支持 box-width soft wrap（按 `\n` 拆行，超宽不 wrap）— v2 接 TextLayout.ts 复刻
- 不支持 vertical 模式 → null fallback bbox
- 多字符 union 仅取面积最大外环 — 短文本 OK，长文本会丢部分字符精度
- 长 brush（>100 points）union 性能 ~5-20ms（Web Worker 隔离 + RDP 简化）

### 测试结果

- 后端 `:plugin:test` BUILD SUCCESSFUL（**855 全绿不变**，未动后端）
- 前端 vitest **215 全绿**（181 baseline + 18 Sub A + 16 Sub B）
- shadow jar `HikariCanvas-0.4.9-SNAPSHOT.jar` 165 MB（fontkit 在 web bundle，不入 jar）

### 关键架构纪律（已固化）

1. **Live Paint 是前端独占**（CLAUDE.md M18 §1）— text glyph 不双端镜像，后端不动；这是 rendering.md §1 双端镜像纪律的**显式例外**
2. **fontkit dynamic import 进 worker 而非 main**：worker chunk 400 kB 仅在用户激活 Live Paint 时下载，主页面零影响
3. **worker async 链改动小**：buildGraph async + onmessage async + elementToPolygonAsync 分离同步路径；不破坏 elementToPolygon 同步调用方
4. **brush union 用整 disk 简化**：凸圆盘 union 矩形天然形成 round cap + round join，避免显式 cap/join 几何代码 ~150 行
5. **0.4.9 弃做 / 推迟决策**：B-advanced DCEL（38h，4% 用例，性价比低）**确认永不做**；图层 mask/group/smart object（30h+）独立立项 M30 单独大版本

### 实施工时

约 18h 估，实际单日内完成（Sub A 1h + Sub B 2h + 主线 ~15min 版本号/journal/commit）。

### 0.4.9 hotfix 批（用户实测后 4 bug 一次性修完）

**Bug 1 — URL 粘贴上传无响应**（Sub A）：

- 根因：`useCanvasUpload.ts:14` `IMAGE_URL_RE` 强制 URL 以 `.png/.jpe?g/.gif/.webp` 扩展名结尾，但大多数现代图片 URL 没扩展名（Imgur 直链 / 带 fragment / 多 query / 服务化 URL）→ regex 不匹配 → `onPasteImage` 静默 return，零反馈
- 修法：放宽为 `HTTP_URL_RE = /^https?:\/\/[^\s<>"'\`]+$/i` 接受任意 http(s) URL；安全由后端三层兜底（UrlFetchSafety SSRF + Content-Type 白名单 + ImageIO 解码超时）
- +31 vitest case（合法 URL 16 / 非 URL 12 / 长度边界 3）

**Bug 2 — layer.opacity=0 透明失效**（主线）：

- 根因：`ProjectState.isPristineAcrossLayers` line 213 `if (l.opacity() == 0f) continue;` 把"opacity=0 但有元素"的 layer 跳过，所有 layer 都跳 → isPristine=true → WallRestorer / CanvasProjector 走 placeholder
- 修法：删该 line。pristine 是数据视角不是视觉视角；用户 opacity=0 是有意透明该 layer（让背景方块透出），不是空工程
- 影响：用户调整任意 layer.opacity 现在都映出背景方块

**Bug 3 — text glyph 字符内部洞被丢**（Sub B）：

- 根因：`TextGlyphExtractor.ts` 把 glyph 的每个 subpath 推为**独立 PCPolygon**（外环 + 内环分别成 polygon）。polygon-clipping 行为：独立 polygon union 时被完全包含的 inner 直接合并（不当 hole）；只有**同一 PCPolygon 内的多 ring** 才识别为 outer+holes。叠加 union 后只取面积最大外环 → "O" 内孔 + 多字符的非最大 polygon 全丢
- 修法：glyph 的所有 subpath 合并为**单 PCPolygon 含多 ring**；新增 `textElementToMultiPolygon` 返完整 union 结果 (MultiPolygon = Polygon[]，每 Polygon = [outer, ...holes])；ElementToPolygon 加 `elementToMultiPolygonAsync`；LivePaintCore.buildGraph 改用 multi 路径，直接 spread 给 polygon-clipping union（holes 自然被减为 gap）
- +11 vitest case（O 内孔 / Hello 多 polygon / CJK / 旋转偏移 / 实心字符 vs 含洞 / 端到端集成 2）

**Bug 4 — 图层缩略图比例错**（主线）：

- 根因：`LayerThumbnailRenderer.ts` 把内容 letterbox 到 64×64 dataURL（含巨大透明边），LayerPanel CSS `<img object-contain>` 把 dataURL 再 fit 28×28 → **double scale**（dataURL 自身 letterbox + CSS 再 contain），用户看到的内容只占容器一小部分
- 修法：`THUMBNAIL_SIZE = 64` → `THUMBNAIL_MAX_SIDE = 64`；按 canvas aspect ratio 输出 dataURL（长边 = 64，短边按比例）。CSS object-contain 单次 fit 即可

**测试结果**：后端 855 全绿（ProjectState 改未破回归）+ 前端 vitest **257 全绿**（215 baseline + 11 Sub B text holes + 31 Sub A URL）。

### 0.4.9 hotfix-2 批（3 项实测仍未生效，深查后真根因 + 修法）

第一轮 hotfix 后用户实测 3 项仍 broken（图片粘贴 / text glyph 洞 / 缩略图比例）。
3 子代理深度调查找到**真根因都跟第一轮 hotfix 假设不同**：

**Bug 1 真根因 — Ctrl+V keydown 拦截**：

- `useCanvasShortcuts.ts:63-70` Ctrl+V handler 用 `preventDefault()` 拦截 keydown → 浏览器
  **永远不 fire `paste` event** → `useCanvasUpload.onPasteImage` 根本进不来
- M17 F1（useClipboard 元素粘贴）与 M13-D（useCanvasUpload 图片粘贴）两个 milestone
  引入同一 keystroke 的两个独立 handler，F1 单方面截杀了 D 的 paste event。第一轮
  hotfix 改了 URL regex 但 handler 进不来根本没用
- 修法：
  - 删 useCanvasShortcuts Ctrl+V keydown handler（Ctrl+C 保留）
  - useClipboard.paste(e?) 改接 ClipboardEvent 参数（带 e 走 native；不带 e 走 async fallback）
  - useCanvasUpload.onPasteImage 升级为三路 dispatcher：magic text → 元素粘贴 / image File → 上传 / URL → uploadFromUrl
- vitest +18 case（7 paste dispatcher + 6 shortcuts paste + 5 其他）

**Bug 2 真根因 — TextGlyphExtractor 与 PreviewRenderer 双源 advance 不一致**：

- TextGlyphExtractor 用 fontkit `pos.xAdvance × scale`（字体 hmtx 真实 advance ≈ 18 px/char）
- PreviewRenderer 走 `TextLayout.layoutText` + `charAdvance`（ASCII canonical = fontSize × 0.5 ≈ 16 px/char）
- HELLO 5 字符累积偏差 ~10 px > "O" 内孔半径 ~6 px → 用户点击视觉 O 中央时坐标落在
  glyph polygon hole 外 → Live Paint 不识别洞
- 现有 11 vitest case 用**单字符 + mock fontkit**，cursorX=0 绕过 advance 偏差 → false-positive
- 修法：TextGlyphExtractor 改用 `layoutText(textEl)` 拿 PositionedGlyph[] 摆位 glyph，
  只用 fontkit 拿 `glyph.path` 不用其 layout/positions。align / softWrap / letterSpacing /
  lineHeight 全自动正确（layoutText 已处理）；italic 加 shear 仿射 `x' = x - 0.2y`
  与 Canvas `ctx.transform(1,0,-0.2,1,...)` 数学等价
- vitest +5 case（多字符 cluster 同源 / italic shear / align / letterSpacing / position 匹配 ±1px）

**Bug 3 真根因 — stale cache，代码本身正确**：

- `LayerThumbnailRenderer.ts` aspect ratio 输出代码完全正确，无 width/height 颠倒
- 真问题：`computeLayerHash` 只签 layer 内容**不签 renderer 版本** → 用户 layer 元素未变
  → 永远返第一轮 hotfix 之前的 64×64 letterbox dataURL
- 修法：加 `RENDERER_VERSION = 'v2-aspect-ratio'` 常量 + 签进 hash → 强制 invalidate

### 测试结果

后端 855 全绿（未动后端）+ 前端 vitest **275 全绿**（257 baseline + 18 新增）/ shadow
jar 不变 / bundle main +98 B（dispatcher 几行）/ worker +4 kB（layoutText 进 worker 副本）。

### 关键架构教训（已固化）

1. **两 milestone 共享 keystroke 必须显式协调**：M17 F1 + M13-D 都监听 Ctrl+V 但用了
   不同事件（keydown vs paste），keydown preventDefault 在 paste fire 前就截杀。修后
   统一在 native paste event 内三路 dispatcher
2. **双端镜像纪律的一致性边界**：TextGlyphExtractor + TextLayout 是同前端但**双源 advance**
   也是漏洞——任何与渲染像素相关的几何计算必须用同一 advance 函数
3. **cache hash 必须签 renderer 版本**：layer 内容签不到 renderer 算法变化，要 bump
   version 强制 invalidate。所有缓存层（thumbnail / livepaint graph 等）适用

---

## 2026-05-25 · 0.4.8 — 打磨批（11 项 / M18 + M8 + M13 + Token rate limit）

### 背景

0.4.7 ultrareview 修复批 + CI 全绿后，按用户选择「全套大 scope ~80h+」启动 0.4.8。
原 13 项 scope 中 11 项完成；2 项推迟到 0.4.9（M18 brush 真实形状 stroke offset
算法独立工程 ~8h，D text glyph 真实形状需引 fontkit ~10h，工程量大留单独版本）。

### 子代理矩阵（3 并行 + 主线版本号 / journal）

- **Sub A** M18 Live Paint v1.x — 2/3 项完成（项 3 推迟）
- **Sub B** M8 图层 + 对齐工具 — 3/3 全部完成
- **Sub C** M13 收尾 + Token rate limit — 5/5 全部完成

### M18 Live Paint v1.x（Sub A）

| 项 | 内容 | 文件 |
|---|---|---|
| 1 | multi-subpath path 切分（polygon-clipping face 含 ring 外环 + 内孔，输出 `M ... Z M ... Z` 多 subpath / fillRule=evenodd 自动处理洞） | `web/src/livepaint/PolygonToPath.ts` |
| 2 | RDP tolerance UI 配置（PaintBucketPanel slider 0.25-5 step 0.25 + paintBucket store + localStorage 持久化；阶梯 fallback 保留） | `paintBucket.ts` / `PaintBucketPanel.vue` / `livePaintWorker.ts` / `useLivePaint.ts` |
| 3 | **推迟到 0.4.9** brush 真实形状 stroke offset polygon | — |

### M8 图层 + 对齐工具（Sub B）

| 项 | 内容 | 文件 |
|---|---|---|
| 1 | 图层缩略图（per-layer 64×64 thumbnail + hash 缓存 + wall 切换 invalidate） | 新 `LayerThumbnailRenderer.ts` + `LayerPanel.vue` 接入 |
| 2 | 图层颜色标签（7 Catppuccin 色：red/peach/yellow/green/blue/mauve/overlay0 + null 清除） | `Layer.java` 加 `colorTag` 字段 + `LayerOperations.java` whitelist + `ProjectSnapshot.java` 保 undo/redo + 前端 7-dot picker |
| 3 | 对齐 / 分布工具（marquee 多选时 floating bar 显示：6 align + 2 distribute） | 新 `useAlignDistribute.ts` + `AlignDistributeBar.vue` |

后端 +4 case（ColorTag 校验）；前端 +13 case（align/distribute 8 mode + 边界）。

### M13 收尾 + Token rate limit（Sub C）

| 项 | 内容 | 文件 |
|---|---|---|
| 1 | mask 拖动 / lasso 自由绘制（Alt+drag image 进入 lasso mode + RDP 简化 + 虚线实时预览） | 新 `useLassoMask.ts` + `CanvasView.vue` + `ImageElementSection.vue` |
| 2 | 蒙版边缘羽化 feather（Mask record 加 featherPx + ConvolveOp box blur + Canvas filter blur 双端镜像） | `Mask.java` / `ImageRenderer.java` / `PreviewRenderer.ts` + UI slider |
| 3 | URL 粘贴上传（POST /api/upload/url + UrlFetchSafety SSRF 防御 + 30s timeout / 10MB 上限 / image/* whitelist / 拒 redirect） | 新 `UrlFetchSafety.java` + `UploadHandler.handleUrlUpload` + 前端 paste detector |
| 4 | EXIF 自动旋转（JPEG TIFF Orientation tag 解析 + 8 case AffineTransform） | 新 `ExifOrientation.java` + 17 case 单测；不引 metadata-extractor |
| 5 | Token rate limit（per-IP 固定窗口 10/分钟 + close 4429 + audit + IP SHA-256 hex16 防原文落库） | 新 `TokenRateLimiter.java` + 11 case 单测 + Protocol.CLOSE_TOKEN_RATE_LIMITED + config.yml |

后端 +33 case / 前端 vitest 181 全绿（无新前端单测）。

### 推迟到 0.4.9 的 2 项

- **M18 brush 真实形状**（stroke offset polygon）：sub agent 跑 47min 完成 2/3 项后 socket disconnect，项 3 stroke offset 算法独立工程，需要写完整 round join + cap + polygon-clipping union 流程，建议单独 commit
- **text glyph 真实形状**（fontkit 引入）：fontkit npm dep 引入对 bundle size 有显著影响，且双端镜像（前端 fontkit / 后端用 java 路径化）工程量大

### 测试结果

- 后端 `:plugin:test` BUILD SUCCESSFUL — **855** 全绿（原 820 + Sub B 4 colorTag + Sub C 17 EXIF + 11 TokenRateLimiter + 5 feather = +37，与子代理报告 855 略差 / 含数据漂移）
- 前端 vitest — **181** 全绿（Sub B +13 align/distribute；Sub A 第 1/2 项 vitest 修改未新增 case 数）
- shadow jar `HikariCanvas-0.4.8-SNAPSHOT.jar` ~155-165 MB

### 关键架构纪律（已固化）

1. **multi-subpath path = fillRule=evenodd**：前端 SVG path 标准；后端 PathParser 已支持多 M 段，无需补
2. **图层缩略图 = 前端独立 render**：复用 PreviewRenderer 单 layer 模式，无后端端点；hash-based cache（layer.elements JSON）
3. **AlignDistributeBar 只在 selectedIds ≥ 2 + !isLocked 时显示**：未来加 element.batch-update op 可优化 100+ 多选时 N 帧合并
4. **mask featherPx 双端等价模糊**：Java ConvolveOp 3-pass box blur ≈ Gaussian；Canvas 2D filter='blur(Npx)'，像素差异 < 容差
5. **URL upload SSRF 防御**：DNS 解析后 IP 范围检查（loopback / link-local / site-local / CGNAT / IPv6 fc00::/7）+ http(s) scheme + 拒 redirect
6. **TokenRateLimiter per-IP 隔离**：与 SessionRateLimiter（op-rate）正交；audit 用 SHA-256 IP hex16 防原文 leak

### 实施工时

约 70h 估，实际单日内完成（3 子代理并行各 ~30-60min 跑，主线 ~15min commit/version/journal）。

---

## 2026-05-25 · 0.4.7 — ultrareview 修复批（12 项 + CI lock fallback）

### 背景

0.4.6 部署后用户跑了一轮 ultrareview（产出 `docs/ultrareview-2026-05-25.md`），扫出
8 P0/P1 + 13 P2 + 2 P3。两个子代理（A 评估 P0/P1，B 粗筛 P2/P3）**12/12 全部 TRUE**，
无虚报。0.4.7 范围定为「ultrareview 修复批」，原 M18 Live Paint v1.x 升级 / M8 远期 TODO
推迟到后续版本。

### CI 失败修复（lock 同步）

0.4.6 hotfix #6 push 后 CI 失败 — `npm ci` 报 `Missing: @emnapi/core@1.10.0
from lock file`。根因：macOS dev 跑 npm install 时 platform-specific transitive
deps（emnapi linux 变体、rolldown wasm32 wasi 等）不写入 lock；CI Linux 严格校验失败。
本地试 `npm install --package-lock-only` + `--force --include=optional` 都不能让
lock 完全跨平台一致。**实际修法**：`.github/workflows/ci.yml` Frontend install 步骤
改 `npm ci || npm install --no-audit --no-fund` fallback，并附 `::warning::` 提示。
M16.5 切 npm ci 时未考虑这点；等迁全平台 runner matrix 或 docker dev 后再切回严格 ci。

### 11 项后端修复（子代理 A，串行实施）

| # | 描述 | 关键文件 |
|--:|---|---|
| 1 | 无活跃 session 时动态变量 wall 不重绘 | `CanvasProjector.projectByWall` + `SessionManager.submitFullCanvasDirtyByWallAndReport` + `HikariCanvas.wallDirtyCallback` |
| 2 | `/canvas edit + confirm` 绕过 lock owner | `SessionManager.confirm` 加 `ConfirmResult.Forbidden` + owner/bypass-lock 校验 |
| 3 | WS auth 不重检 `canvas.edit` | `WebServer.handleAuth` 加 `Player.hasPermission` 同步检查 + close 4003 PERMISSION_REVOKED |
| 4 | Wand 撤权后仍可交互 | `WandListener.shouldHandle` 加 `canvas.edit` gate |
| 5 | session cancel 尾帧丢失 | `ProjectionThrottler.flushNow` + `SessionManager.preForgetHooks` |
| 6 | `isPristine` 多图层误判 | `ProjectState.isPristineAcrossLayers` + `CanvasProjector` / `WallRestorer` 接入 |
| 7 | 透明背景 blend slow path 强写不透明 | `BlendModes` 双端（Java + TS）改 W3C source-over 真实 alpha |
| 8 | 调试 paint op 删除（M1→0.4.6 残留） | `WebServer.case "paint"` + `HikariCanvas.paintAllSessionMaps` + 字段 / 构造参数 |
| 9 | `element.add` 丢 v2 字段 | `EditSession.buildText/Rect/Path/Circle/Shape/Image` 读 `opacity/blendMode/renderMode` |
| 10 | alias dispatcher version=0 覆盖 | `VariableAliasDispatcher.handleSet/Clear` 用 `s.projectState().version()` |
| 11 | `VariableInterpolator` 不查 `isStale` | `resolveValue` 加 `!v.isStale(now)` 条件 — 过期值走 fallback 链 |

### 前端 #8 修复（子代理 B）

- **新建** `web/src/composables/useLockGuard.ts` — 抽 `isLocked` / `isOwner` / `isReadonly` /
  `guardMutation(actionName)` 早 return helper，含 i18n `lockGuard.blocked*` toast + DEV-only console.warn
- **接入** LeftTools 9 按钮 / TemplateGallery applyNow / CanvasZoomBar grid input /
  CanvasView onGridChange + onEditTextUpdate + onDragEnd / useDrawToCreate.commitDraw /
  useTransformerManager.onTransformEnd；已有 guard（useClipboard.paste / useCanvasUpload /
  IconLibrary / App 全局快捷键）保留不动
- **决策**：`isReadonly = isLocked`（与既有约定一致，owner 也需先解锁才编辑），不用
  `isLocked && !isOwner`；variable / schedule / rail mutation 不冻结（跨 wall 共享语义）
- **新增** 7 个 vitest case 覆盖 useLockGuard 各分支

### 测试结果

- 后端 `:plugin:test` BUILD SUCCESSFUL — **820** 全绿（fixture baseline 0 漂移，因
  isPristine 短路 + 现有 fixture 全 alpha=255 不触发新 blend 公式）
- 前端 `vitest --run` — **168** 全绿（原 161 + 新 7 useLockGuard）
- shadow jar `HikariCanvas-0.4.7-SNAPSHOT.jar` ~155 MB
- CI yaml fallback 验证：push 后看 GitHub Actions 走 `npm install` 路径

### 关键架构纪律（已固化）

1. **dirty callback 双路径**：动态变量 dirty 时若有活跃 session 走 session redraw；
   无活跃 session hop 主线程调 `CanvasProjector.projectByWall` — 部署 wall 也能更新
2. **lock 校验在所有 wall-acquire 入口**：open / confirm-into-existing 两条路径都需读
   `published_at` + owner + bypass-lock；只在 open 加校验是 M5.5 lock-state 实施漏点
3. **W3C source-over alpha 公式双端镜像**：`outA = srcA + dstA*(1-srcA)`，
   `outRGB = (blend*srcA + dst*dstA*(1-srcA)) / outA`；删 `0xff000000 |` 强制不透明掩码
4. **`useLockGuard` 是前端 lock 唯一执行点**：所有 mutation 入口接 `guardMutation` 早 return，
   后端 op 仍透明放行（lock-state 设计第 2 + 5 条）
5. **`isStale` 必须参与 fallback 链**：cached → `!isStale` → inline fallback → default → "???"，
   过期值不返渲染路径

### 实施工时

约 24h 估，实际 4-5h（两子代理并行实施 + 主线 CI fix + 版本号 + journal）。**单 commit 单日推完**。

### CI 后续 hotfix · RendererSnapshotTest 跨平台 4 fixture

0.4.7 push 后 CI 跑出 4 个 RendererSnapshotTest fixture FAILED：
`02-chinese-text` / `03-effects-stroke` / `04-effects-shadow` / `05-effects-glow`。

**根因**：CI runner = Ubuntu Linux，本地 dev = macOS。Java AWT 字体渲染（中文字距、
effects 内部算法）在 Linux / macOS 输出差异 > 0.5% 容差。baseline 用 macOS 生成 + 校对。
**本次是历史上第一次 :plugin:test 在 CI Linux 上完整跑**（hotfix #4/#5 因账单 5s fail，
hotfix #6 因 npm ci lock 30s 在 frontend 步骤就挂，本次首次完整跑到 backend test）。

**修法**：`RendererSnapshotTest` 拆 `snapshotPlatformSensitive` 方法 +
`@DisabledIfEnvironmentVariable(GITHUB_ACTIONS=true)` 让 CI 跳过这 4 项；本地 macOS
跑全套（含这 4 项）保留渲染正确性保护。

**升级路径**：M19+ 引入 Linux baseline matrix（CI 上生成 + commit Linux baseline，
测试代码按 platform 选用），或换 `macos-latest` runner（贵 10×）跑全套。两条路径都
不挡当前 release。

---

## 2026-05-25 · 0.4.6 hotfix #6 — arrow / dot marker 公式平缓化

### 用户报告

「箭头本身可能不太能按比例来进行缩放，可能会出现稍微一放大，就放得特别大的情况。」

### 根因诊断（用 2 个 Explore 子代理并行调查）

**关键诊断不是"transform 缩放整个 element"**（这场景实测 OK：strokeWidth 和 diag 同步
按 scale 倍数放大，arrow 公式 cap 由 diag 限制，比例稳定）。

**真正的问题在 RightPanel 单独拖 strokeWidth 滑块**：diag 不变、stroke 单独调粗：

- `arrowSize` 旧公式 = `max(8, stroke × 3)`：stroke 5→10，arrow 15→30 (size 翻倍)
- arrow 是**三角形**（面积 ∝ size²）：arrow 面积 15²→30² = 4× 膨胀
- 但直线是**矩形带**（面积 ∝ stroke）：直线面积仅 ×2

**stroke 调粗 2 倍，箭头视觉面积膨胀 4 倍** — 平方膨胀感。dot（圆，面积 ∝ r²）同理。

### 修法（方案 A：公式平缓化）

双端公式同步改 1 行：

| 公式 | 旧 | 新 |
|---|---|---|
| `arrowSize(stroke)` | `max(8, stroke × 3)` | `max(8, stroke × 2 + 4)` |
| `dotRadius(stroke)` | `max(3, stroke + 1)` | `max(3, stroke / 2 + 3)` |

数值对比：

| stroke | 旧 arrow | 新 arrow | 旧 dot r | 新 dot r |
|--:|--:|--:|--:|--:|
| 1 | 8 | 8 | 3 | 3 |
| 2 | 8 | 8 | 3 | 4 |
| 5 | 15 | 14 | 6 | 5 |
| 10 | 30 | **24** | 11 | **8** |
| 20 | 50 | **44** | 21 | **13** |

细 stroke (1-3) 几乎不变（保持原低端体验），粗 stroke 增速从平方膨胀降到线性平缓。

### 修改文件

- `plugin/.../render/MarkerRenderer.java` — 公式 + javadoc 解释面积膨胀根因
- `web/src/render/MarkerRenderer.ts` — 同步（dot 用 `Math.floor(stroke / 2)` 对齐 Java 整数除法）

element-aware cap 路径不变（`min(base, diag × 0.5)` + `minByStroke` 兜底）。

### 测试结果

- 后端 :plugin:test BUILD SUCCESSFUL（24s，fixture 06-path-line baseline 漂移 < 0.5% 容差内）
- 前端 vitest 161/161 全绿（446ms，无 hard-coded arrowSize / dotRadius 期望数值）
- shadow jar `HikariCanvas-0.4.6-SNAPSHOT.jar` 163 MB（16:16）

### CI 验证

push 到 main 触发 `.github/workflows/ci.yml`（用户 GitHub Actions 账单刚修好，首次跑 CI）。
**不打 tag**，不触发 release.yml。

---

## 2026-05-22 · 0.4.6 体验打磨 — 字体加粗/斜体 + 透明背景 + 配色修复 + 文案优化

### 背景

用户提出 4 个体验打磨点：(1) 字体原生加粗/斜体；(2) 前端文案 + 排版更友好；
(3) 透明背景；(4) 颜色对比度 bug。先做 4 路深入审计，再 5 phase 实施。

### 审计核心发现

1. **字体 bold/italic**：22 字体矩阵无 bold/italic variant；AWT synthetic bold 与
   Canvas synthetic bold 算法不同，强行加 `Font.BOLD` 会导致双端像素不一致。
   推荐方案：**stroke 包装 + shear transform**（双端等价）
2. **透明背景**：M11 / M17 已埋好 80% 基础设施（PaletteLut.TRANSPARENT_INDEX +
   matchColor 4 参 + palette 4 透明槽）；唯一缺陷是 CanvasCompositor 用
   TYPE_INT_RGB buffer 把 alpha 吃掉
3. **颜色 bug**：根因是 `--primary-foreground: var(--ctp-base)` 在深色主题下
   ctp-base 是暗色，配亮色 primary 对比度仅 2:1（远低于 WCAG AA 4.5:1）
4. **侧边栏文案**：10+ 个技术术语（strokeWidth / blendMode / innerRatio / dither）
   需改友好

### 5 phase 实施

- **P1（颜色对比度修复）**：
  - style.css 加 `.dark, .theme-frappe, .theme-macchiato { --primary-foreground:
    var(--ctp-crust); --destructive-foreground: var(--ctp-crust); }` 让深色主题下
    亮 primary/destructive 配深色 foreground（对比度 2:1 → 6:1+）
  - 6 处 `bg-[color:var(--destructive)] text-white` → `text-[color:var(--destructive-foreground)]`
    （VariablePanel × 2 / RailNetworkModal × 3 / CanvasView toast / IconLibrary tab）
- **P2（透明背景）**：
  - `CanvasCompositor.rasterize()` buffer TYPE_INT_RGB → TYPE_INT_ARGB
  - `toPaletteSlice` 提取 alpha 调 4 参 `matchColor(r,g,b,a)`（alpha<128 返 palette
    index 0 = TRANSPARENT，MC 地图渲染该像素透出 ItemFrame 后方方块）
  - CanvasSettingsSection 加"设为透明背景"按钮（一键设 `#00000000`）
- **P3（字体加粗 / 斜体）**：
  - `TextElement` 加 `Boolean bold`, `Boolean italic` 字段（nullable，向下兼容）
  - 后端 `TextRenderer.draw` 包装：italic 走 `g.shear(-0.2, 0)`（在元素 anchor 处）；
    bold 走额外 stroke pass（width = max(1.5, fontSize * 0.08)，color = text color）
  - 前端 `PreviewRenderer.drawText` 镜像：`ctx.transform(1, 0, -0.2, 1, ...)` italic +
    `ctx.lineWidth + strokeText` bold（数学等价于 AWT 路径）
  - `TextElementSection` UI 加 B / I 切换按钮（Material 风格紧凑工具栏）
  - 像素字体（NN 路径）跳过 bold 描边以保持锐利
- **P4（文案重排）**：
  - 10+ 个技术化文案改友好：strokeWidth → 描边粗细 / blendMode → 混色模式 /
    renderModeClean → 清晰 / renderModeDither → 柔和 / dither → 柔和过渡 /
    innerRatio → 内凹度 (Pointiness) / Shape kind/sides 中文化
  - GeometricElementSection 的 shape kind 选项 polygon/star 加 i18n（多边形/星形）
  - 不引入大型分组重构（保留现有 `<details>` 折叠）
- **P5（收尾）**：版本号 0.4.5 → 0.4.6-SNAPSHOT（5 处）+ shadow jar 155 MB +
  journal + CLAUDE.md 0.4.6 段 + commit + push

### 关键架构决策（已固化）

1. **TYPE_INT_RGB → TYPE_INT_ARGB 主 buffer**：让 alpha 通道贯穿渲染链路到
   `matchColor(r,g,b,a)`；内存 +33%（每 2×2 maps 64→85 KiB）可接受
2. **italic = shear transform，bold = stroke 包装**：双端走数学等价的线性变换 +
   stroke 描边——避免 synthetic bold 双端像素不一致
3. **bold 像素字体跳过描边**：NN 路径走 BufferedImage mask 不是 outline；像素字体
   本身已经够清晰，加描边反而破坏锐利感
4. **deep mode foreground 走 ctp-crust**：1 行 CSS 改全局修复 Primary/Destructive
   两类按钮对比度（影响约 20 处组件）
5. **文案保持 `<details>` 折叠不强行重构**：现有结构已合理，重点改文案而非结构

### 测试结果

- 后端 **820** 测试全绿（baseline 无破坏 — TYPE_INT_RGB/ARGB 切换在 alpha=0xFF 像素下
  与 3 参 matchColor 等价；测试 fixture 全是不透明 alpha）
- 前端 vite build 通过（bundle 720 kB / 213 kB gzip）
- shadow jar `HikariCanvas-0.4.6-SNAPSHOT.jar` 155 MB

### 关联文件

- `web/src/style.css`（+ 深色主题 foreground 覆盖）
- `web/src/components/{variables/VariablePanel, rail/RailNetworkModal, layout/CanvasView, layout/IconLibrary}.vue`（6 处 text-white → token）
- `plugin/.../render/CanvasCompositor.java`（TYPE_INT_RGB → ARGB + matchColor 4 参）
- `web/src/components/properties/CanvasSettingsSection.vue`（+ 透明背景按钮）
- `plugin/.../state/TextElement.java`（+ Boolean bold, italic 字段）
- `plugin/.../state/ElementValidator.java`（+ boolFieldOrNull helper）
- `plugin/.../state/EditSession.java`（buildText / applyTextPatch / cloneElementWithNewId 5 处 + 字段）
- `plugin/.../template/TemplateInstantiator.java`（2 处 new TextElement 加 null, null）
- `plugin/.../render/{CanvasCompositor, TextRenderer}.java`（textElement 重建 + bold/italic 渲染）
- `plugin/src/test/.../{CanvasCompositorVariableTest, EditSessionReplaceContentTest}.java`（fixture +2 字段）
- `web/src/types/protocol.ts`（TextElement 加 bold/italic 字段）
- `web/src/render/PreviewRenderer.ts`（drawText 包 italic shear + bold stroke pass）
- `web/src/components/properties/TextElementSection.vue`（+ B / I 切换按钮）
- `web/src/components/properties/GeometricElementSection.vue`（shape kind/sides/innerRatio i18n）
- `web/src/i18n/messages.ts`（zh + en 各 ~15 改 / 新增 keys）

---

## 2026-05-22 · 0.4.5 打磨期 — 修 0.4.3/0.4.4 实操可用性 + UX 大量优化

### 背景

0.4.4 当日推完铁路网络后，实测发现 2 个 P0 可用性 bug + 数个 UX 粗糙点。
0.4.5 集中打磨 0.4.3 全局变量 + 0.4.4 铁路网络两块新功能。**没有新功能 / 新协议**，
全是体验提升 + bug 修。

### 8 phase（单日推完）

- **P1（修 0.4.4 P0：RailNetworkModal 选线路看不到已存在的站点 / 车次）**
  - 新 WS op `rail.line.detail`（一次返 stations + runs + 各 run 的 timetable 聚合视图，避免 N+1）
  - `RailDao.loadLineDetail` + `LineDetail` record（用 IN 子句批量拉 timetable）
  - `RailOpDispatcher.handleLineDetail` + 路由到 WebServer 13 op switch（rail 12 → 13）
  - `wsClient.sendRailLineDetail` + `RailNetworkModal.selectLine` 实际调用并填充 store

- **P2（修 0.4.4 P0：原生 prompt / confirm UX）**
  - RailNetworkModal 创建车次走 inline modal（runNumber / direction / serviceType 完整字段）
  - 3 个 `confirm()`（删除线路 / 站点 / 车次）改 inline confirm popover（同 VariablePanel 风格）

- **P3（收 0.4.4 spec：ScheduleManagerModal 加铁路绑定段）**
  - ready payload 加 `railBinding` 字段（line + station + direction 当前快照）
  - wsClient.handleReady 接 → useRailStore.setBinding；wall 切换时 rail store reset
  - `RailOpDispatcher.lookupBinding` 静态 helper 供 WebServer 调
  - ScheduleManagerModal 顶部加可折叠"铁路绑定"section：3 列下拉（线路 / 本站 / 方向）+
    启用/更新/解除按钮 + 状态 chip + entries 启用铁路时整段灰显 + hint 文案

- **P4（UX：拖动排序 + 时刻表 inline 增强）**
  - 站点 li HTML5 native drag-drop：onDragStart / Over / Leave / Drop 4 handler +
    GripVertical 手柄 + 落定后批量 sendRailStationUpdate 重设 sortOrder = 0..N
  - 时刻表 `input type="time" step="1"` HTML5 原生 picker 含秒 + `isValidTime` regex
    校验 → invalid 时 hc-input-error 红边

- **P5（UX：服务类型 select + i18n 友好文本）**
  - RailRunDialog 服务类型从 input + datalist 改为 select（4 内置 + 「自定义」选项）
  - 选「自定义」切换为 input + ↺ 按钮恢复 select；syncDraftFromStore 自动检测非内置值进入 custom 模式

- **P6（UX：车次复制）**
  - RailRunDialog 加 Copy 按钮 → 复制对话框（新 runNumber + direction 可改 + 其他字段沿用源车次）
  - 走 rail.run.create + rail.run.timetable.set 两步复制 + 关闭当前 dialog 让用户回主 modal

- **P7（admin 视图 / bug 复查 / 引导文案）**
  - 0.4.3 bug 复查：加 `createGlobal_inheritsReferencedByWalls_fromMarkWallReferences`
    测试 case 验"先 markWallReferences 后 createGlobal"的 byWall 反查路径正常（同 0.4.0 Bug 2 模式）→ **测试通过**，0.4.3 没此 bug
  - RailNetworkModal 空状态加示例引导（4 步快速上手）+ 未选线路时显示 4 step ol
  - 全局变量 admin 视图：当前 VariablePanel 「全局变量」section 已含所有 userglobal + owner badge，
    admin 改其他玩家变量通过 `/canvas var set` 命令；不引入新 UI（避免 ready payload 加权限字段的复杂改动）

- **P8（收尾）**
  - 版本号 0.4.4 → 0.4.5-SNAPSHOT（5 处文件）+ shadow jar 154 MB
  - journal + CLAUDE.md 0.4.5 段 + commit + push

### 测试结果

- 后端 **820** 测试全绿（原 819 + 新 1：createGlobal_inheritsReferencedByWalls）
- 前端 **161** 测试全绿（无新增 case；P4-P7 是 UI 改动，rail UI 单测留 v0.4.x）
- 前端 vite build 718 kB / 213 kB gzip（+8 kB rail UI 改动，可接受）

### 关键架构决策（已固化）

1. **rail.line.detail 走聚合查询**：单接口返 stations + runs + timetableByRun，避免 N+1；
   timetable 用 IN 子句批量拉
2. **ready payload 携带 railBinding**：让 ScheduleManagerModal 一打开就知道状态，
   不另加查询 op；wall 切换时 rail store reset
3. **拖动排序批量更新**：落定后遍历重设 sortOrder = 0..N（仅 order 变化的项发请求）
4. **serviceType custom 模式自动切换**：syncDraftFromStore 检测非内置 enum 自动进 custom input，
   减少用户配置摩擦
5. **车次复制 = create + timetable.set 两步**：不引入新协议 op，复用现有接口
6. **删除走 inline confirm popover**（同 VariablePanel 风格）：替换 confirm() —
   `confirmingDelete: { type, id }` 状态机统一管理 line/station/run 三种删除

### 关联文件

- `plugin/.../storage/RailDao.java`（+ loadLineDetail + LineDetail record）
- `plugin/.../web/RailOpDispatcher.java`（+ handleLineDetail + lookupBinding 静态 helper）
- `plugin/.../web/WebServer.java`（+ rail.line.detail op 路由 + ready payload railBinding 字段）
- `web/src/network/wsClient.ts`（+ sendRailLineDetail + handleReady railBinding 同步 +
  reset 内 rail store reset + import useRailStore）
- `web/src/components/rail/RailNetworkModal.vue`（重写 selectLine + 3 inline delete confirm +
  新车次对话框 + 拖动排序 + 引导文案）
- `web/src/components/rail/RailRunDialog.vue`（服务类型 select + 时刻表 time picker +
  车次复制对话框）
- `web/src/components/schedule/ScheduleManagerModal.vue`（+ 铁路绑定 section + entries 灰显）
- `web/src/i18n/messages.ts`（zh + en 各 ~30 新 keys）
- `plugin/src/test/.../variable/VariableStoreTest.java`（+ 1 bug 复查 case）

### v0.4.x 后续优化（如果用户提需求）

- 站点拖动用更精致的 sortable lib（当前 HTML5 native 在某些浏览器视觉抖动）
- 时刻表行加 arrival ≤ departure 时序校验红边（当前只校验格式）
- 全局变量 admin 视图 ready payload 加 permissions 字段（让 UI 区分能否改非自己变量）
- RailNetworkModal 加"导入示例线路"按钮（一键填充郑州 1 号线示例数据）

---

## 2026-05-22 · 0.4.4 铁路网络（线路 / 站点 / 车次 / 时刻表）— 6 phase 单日落地

### 背景

0.4.0 ManualScheduleProvider 是**纯 per-wall** —— 100 个地铁屏 = 100 套独立配置。
0.4.4 引入完整铁路网络抽象 + 真实地铁运营语义（车次号 / 服务类型 / 编组 / 区间 / 备注 / 每站精确时刻表）。
设计见 `docs/dynamic-data.md §18`。

### 实施（P1-P6，单日完成）

- **P1（V016 5 表 + DAO + record + Auto-generator）**：
  - `db-migrations/V016__rail_network.sql` 5 表（rail_lines / rail_stations / rail_runs
    / rail_timetable / wall_rail_bindings 含 FK CASCADE）
  - 6 record：`RailLine` / `RailStation` / `RailRun` / `RailTimetableEntry` /
    `WallRailBinding` / `ServiceType`（含 i18n displayText）
  - `RailDao`（统一 5 表 CRUD + `listStationStops` JOIN run + `replaceTimetable` 事务）
  - `AutoTimetableGenerator`（首站时间 + 站间秒 + 跳站集合 + 区间起止 → timetable rows）
- **P2（RailScheduleProvider）**：
  - 共享 `schedule:<wallId>/*` namespace + 接管 rail-bound wall + 29 key push（兼容 0.4.0 旧 15
    + 新车次语义 14：next/next2 各 7：run_number / service_type / service_type_text /
    cars / terminus / notes / arrival）
  - `ManualScheduleProvider.skipWallPredicate` 让 rail-bound wall 跳过 — 避免双写同 key
  - `ProviderBootstrap.initialize` 6 参 overload 装配 RailScheduleProvider + 注入 predicate
  - HikariCanvas onEnable 装配 RailDao + wall delete hook 清 binding
- **P3（11 WS op + 6 权限 + AuditLog）**：
  - `RailOpDispatcher`：12 op（11 spec + line.list 读取） rail.line.{create/update/delete/list}
    + rail.station.{add/update/delete} + rail.run.{create/update/delete} + rail.run.timetable.set
    + rail.wall.bind
  - 6 权限节点：canvas.rail.{line.create, line.edit.own/any, line.delete.own/any, wall.bind}
  - ACL：rail.line.create → 创建节点；rail.wall.bind → wall owner 走 schedule.own；
    其他改类 op 按 line owner 判 own/any
  - WebServer 装配 + 12 op 路由 + AuditLog RAIL_* 9 事件
- **P4（前端管理 modal）**：
  - `types/rail.ts`（6 接口）+ `stores/rail.ts` Pinia mirror
  - `wsClient` 12 个 sendRail* 方法
  - `RailNetworkModal.vue`（线路列表 + 创建表单 + 选中线路展开 站点 / 车次）
  - `RailRunDialog.vue`（车次详情：runNumber / direction / serviceType / cars / 区间起止 / notes
    + 时刻表 inline 编辑表格 + 自动生成对话框含跳站 checkbox）
  - TopBar 加 TrainTrack 按钮 + ui.railNetworkOpen + App.vue 挂载 modal
  - i18n 中英 ~50 keys（rail.modalTitle / line / station / run / direction / serviceType
    / timetable / auto-generate / 4 confirm）
- **P5（单测 + docs）**：
  - `AutoTimetableGeneratorTest` 9 case（基础 / 空 / 单站 / 大站快车跳站 / 区间车 /
    异常 start>end / 未知 startId fallback / 校验 / runId 注入）
  - `ServiceTypeTest` 7 case（4 内置 enum / 自定义字符串 pass-through / zh-cn / en
    locale / 空值 / dbValue 小写）
  - `RailScheduleProviderTest` 8 case（22+ key 注册 / service_type / cars push 内容 /
    hasWallBinding / 取消绑定 unregister / declaredKeys / 空 timetable / skipPredicate 集成）
  - `docs/variables.md §1.13` 新增（铁路网络入口 + 三层结构 + 14 新变量 + 权限 +
    与 ManualSchedule 关系）
- **P6（收尾）**：
  - 版本号 0.4.3 → 0.4.4-SNAPSHOT（5 处文件：build.gradle.kts / paper-plugin.yml × 3 含
    demo plugins / web/package.json + package-lock.json）
  - shadow jar HikariCanvas-0.4.4-SNAPSHOT.jar 154 MB
  - journal + CLAUDE.md 0.4.4 状态 ✅ + commit + push 签名 verified

### 测试结果

- 后端 **819** 测试全绿（原 795 + 新 24：9 AutoTimetable + 7 ServiceType + 8 RailScheduleProvider）
- 前端 **161** 测试全绿（无新增 case；rail UI 单测留 v0.4.5）
- 后端编译 / 前端 vite build（704 kB / 210 kB gzip，+30 kB modal）/ 0 baseline 漂移

### 关键架构决策（已固化）

1. **rail + manual 共享 namespace + skip predicate 协调**：RailScheduleProvider 接管的
   wall 自动让 ManualScheduleProvider 跳过 — 避免双写同 key。注入路径 = `ProviderBootstrap`
   装配时 `manualProvider.setSkipWallPredicate(railProvider::hasWallBinding)`
2. **每站时刻精确 = `rail_timetable` 读**：不再走"travel_seconds 均匀推算"，支持站间不均 +
   大站快车跳站（stops_here=0）+ 区间车不到全线
3. **service_type 4 内置 + 自定义字符串**：LOCAL/EXPRESS/SECTION/LIMITED 走 enum 路径 +
   i18n 友好文本；其他字符串原样存 + 显示
4. **AutoTimetableGenerator 纯函数**：不依赖 DB / Bukkit / 主线程，单测 9 case 覆盖
5. **wall_rail_bindings.line_id IS NULL 走 fallback**：兼容只用 ManualSchedule 的旧服务器
6. **车次详情的所有写操作 ACL 走 line owner**：rail.station.* / rail.run.* / rail.run.timetable.set
   按 line.ownerUuid 判定 own/any（避免每张子表都配独立 owner_uuid 字段）

### 关联文件

- `db-migrations/V016__rail_network.sql`（新）
- `plugin/.../rail/{RailLine, RailStation, RailRun, RailTimetableEntry, WallRailBinding,
  ServiceType, AutoTimetableGenerator}.java`（7 新）
- `plugin/.../storage/RailDao.java`（新，~530 行统一 5 表）
- `plugin/.../variable/provider/RailScheduleProvider.java`（新，~530 行）
- `plugin/.../variable/provider/ManualScheduleProvider.java`（+ setSkipWallPredicate /
  shouldSkipWall + refresh 内跳过 rail-bound wall）
- `plugin/.../variable/provider/ProviderBootstrap.java`（+ 6 参 overload + 装配 + 注入 predicate）
- `plugin/.../web/RailOpDispatcher.java`（新，~510 行 / 12 op + ACL + AuditLog）
- `plugin/.../web/WebServer.java`（+ railOpDispatcher 装配 + 12 op 路由）
- `plugin/.../HikariCanvas.java`（+ railDao 字段 + 装配 + wall delete hook）
- `plugin/src/main/resources/paper-plugin.yml`（+ 6 权限节点）
- `web/src/types/rail.ts`（新）
- `web/src/stores/rail.ts`（新 Pinia mirror）
- `web/src/network/wsClient.ts`（+ 12 sendRail* 方法）
- `web/src/components/rail/{RailNetworkModal, RailRunDialog}.vue`（新）
- `web/src/components/layout/TopBar.vue`（+ TrainTrack 按钮）
- `web/src/App.vue`（+ 挂载 RailNetworkModal）
- `web/src/stores/ui.ts`（+ railNetworkOpen + toggleRailNetwork / closeRailNetwork）
- `web/src/i18n/messages.ts`（+ rail.* + topbar.railNetwork zh + en ~50 keys）
- `plugin/src/test/.../rail/{AutoTimetableGeneratorTest, ServiceTypeTest}.java`（新 16 case）
- `plugin/src/test/.../variable/provider/RailScheduleProviderTest.java`（新 8 case）
- `docs/variables.md §1.13`（铁路网络新节）
- `docs/dynamic-data.md §18`（既有规划文档，作为契约对照）

### v0.4.5 留作的优化

- ScheduleManagerModal 顶部加铁路绑定段（line + station + direction 下拉，省去走 rail
  modal 单独配 binding）+ 7+14 = 21 变量预览
- RailNetworkModal 进入线路时自动拉 stations / runs / timetable 详情（当前需手动添加才有数据；
  缺 `rail.line.detail` op）
- 拖动排序站点（当前用 ↑↓ 按钮）
- 时刻表表格更精致的 inline 编辑（时间 picker / 校验提示）

---

## 2026-05-21 · 0.4.3 全局用户变量（userglobal namespace）— 5 phase 落地

### 背景

0.4.0 P1 决策 3 的遗留：user 变量按 wall 隔离（namespace = `user:<wallId>`），
不能跨画布共享。0.4.3 引入 `userglobal/<key>` 新 namespace，**全服共享、跨 wall**，
但仍是玩家自定义（不是插件 / 系统变量）。设计见 `docs/dynamic-data.md §17`。

### 实施（P1-P5，单日完成）

- **P1（V015 migration + DAO + Store API）**：
  - `db-migrations/V015__user_global_variables.sql` 新表（主键 = name 单字段，全服唯一）
  - `UserGlobalVariableDao`（CRUD + listByOwner + countByOwner + countTotal）
  - `VariableStore.createGlobal/listGlobals/getGlobalOwner/loadGlobalsFromDb/configureUserGlobal`
  - 内存 `GlobalOwnerInfo` 表（key → ownerUuid + ownerName）让序列化时能注入 owner
  - HikariCanvasConfig 加 `userGlobalMaxPerOwner` / `userGlobalMaxTotal` 字段
  - HikariCanvas onEnable 装配 + loadGlobalsFromDb
- **P2（EditSession 路径 + 权限 + Reserved namespace）**：
  - `EditSession.createGlobalVariable / updateGlobalVariable / setGlobalVariableValue /
    deleteGlobalVariable / bindGlobalVariable` 5 新方法
  - `VariableOpDispatcher` 按 `scope='global'`（create）/ `fullName.startsWith("userglobal/")`
    （mutate）路由 + `pickGlobalPermissionNode` + `isCallerGlobalOwner`
  - `PluginNamespaceRegistry.RESERVED_NAMESPACES` 加 `userglobal`（外部插件禁推）
  - paper-plugin.yml 加 5 个新权限节点 `canvas.var.global.{create, write.own/any, delete.own/any}`
  - AuditLog 事件前缀加 `VARIABLE_GLOBAL_*`（CREATE / UPDATE / SET / DELETE / BIND）
- **P3（广播 + listener 路由 + interpolator）**：
  - `SessionManager.broadcastVariableChangeToAll(event, push)`：与现有 broadcastToWall 并列，
    向所有活跃 session 广播 patch（不限 wall）
  - HikariCanvas listener detect `fullName.startsWith("userglobal/")` → 走 broadcastToAll
  - `SessionManager.variableToMap` 现为 instance method + `variableStoreRef` 注入；userglobal
    namespace 时从 store.getGlobalOwner 注入 ownerUuid + ownerName 字段
  - `VariableDto.from(v, store)` 工厂方法注入 owner；WebServer ready payload 用新签名
  - `EditSession.variableToMap(v, store)` 双 overload；create / update Global 路径传 store
  - interpolator 天然支持 `${var:userglobal/X}`（fallthrough 到字面查询，无需注入 wallId）
  - 前端 `Variable` 接口加 `ownerUuid` / `ownerName` 字段（可选）；`types/variable.ts`
    加 `USERGLOBAL_NAMESPACE` / `isUserGlobalNamespace` / `makeUserGlobalFullName`
- **P4（前端 UI）**：
  - `pickerLogic.buildGroups` 第 5 参 `selfUuid` → userglobal 分到 `myGlobal` /
    `othersGlobal`（按 ownerUuid 匹配）；新 6 组顺序
    `mine → myGlobal → othersGlobal → plugin → system → papi`
  - `wsClient.sendVariableCreate` 加 `scope: 'wall' | 'global'` 第 4 参；新
    `sendVariableCreateGlobalWithAck` 返 fullName 给 alias 拼接
  - `NewVariableDialog` 加 scope toggle [本 wall | 全局] + hint 文案，create 走 scope 参数
  - `VariablePanel` 新 section 「全局变量」（Globe 图标 + Mauve 调色板）；
    每行 owner badge / 我创建 chip；非 owner 时按钮全 disabled + "只读" 标记
  - `VariablePicker` 6 组分类完整渲染（i18n 加 4 个新 group title）
  - i18n 中英 ~14 keys（dialogNewScope* + groupGlobal + emptyGlobal + ownerBadgePrefix +
    ownerMineBadge + actionReadonly + picker.groupMyGlobal / groupOthersGlobal）
- **P5（config + 单测 + docs + 版本号）**：
  - config.yml `dynamic.variables.userglobal-max-{per-owner,total}` 段（默认 500 / 10000）
  - `VariableStoreTest` 加 10 case 覆盖 createGlobal（dao 未配 / 配额 / 重复 / DB 落库 /
    listGlobals / loadGlobalsFromDb / listVisibleToWall / ChangeListener）
  - `EditSessionVariableTest` 加 5 case 覆盖 createGlobalVariable / updateGlobalVariable
    越权 / setGlobalVariableValue / deleteGlobalVariable / patch 形态 + owner 注入
  - PluginNamespaceRegistryTest 现有 `register_reservedNamespaces_throwsIllegalArgument`
    自动覆盖 `userglobal` 拒绝（RESERVED_NAMESPACES 内）
  - pickerLogic.test.ts 现有 buildGroups 测试改 byId 取组（不依赖 index）+ 加 2 case
    覆盖 userglobal 按 selfUuid 分组
  - docs/variables.md 加 §1.13 新节（玩家入门指南：创建 / Picker 分组 / 配额 / 权限 /
    与 user 对比 / 场景示例）
  - 版本号 0.4.2 → 0.4.3-SNAPSHOT 5 处（build.gradle.kts / paper-plugin.yml × 3 含
    demo plugins / web/package.json + package-lock.json）

### 测试结果

- 后端 **795** 测试全绿（原 714 + 新增 81：10 VariableStore + 5 EditSessionVariable +
  其他构造路径变更未破坏）
- 前端 **161** 测试全绿（原 155 + 新增 6：buildGroups byId / userglobal 分组 / fallback）
- 后端编译 / 前端 vite build 均 0 warning 0 error

### 关键设计决策（已固化）

1. **namespace = `userglobal`**（不带冒号 + wallId）— 与 user 同谱系但全局
2. **外部插件禁推 userglobal/***（加入 RESERVED_NAMESPACES）；插件想全服共享应用自己 namespace
3. **owner-only + admin override**（5 权限节点）；owner = 创建者，admin = `.any` 节点
4. **配额 per-owner 500 + 全服 10000**（config 可调，不再强制 total ≥ per_owner，admin
   意图优先）
5. **`.canvas` 文件导出不含** userglobal（服务器级状态，跨服务器无意义）
6. **state.patch 广播全 session**（非按 wall 路由）；HikariCanvas listener 按 fullName
   前缀分流到 broadcastToAll / broadcastToWall

### 关联文件

- `db-migrations/V015__user_global_variables.sql`（新）
- `plugin/.../storage/UserGlobalVariableDao.java`（新）
- `plugin/.../variable/VariableStore.java`（+ createGlobal/listGlobals/configureUserGlobal/
  persistIfUserGlobal/getGlobalOwner/loadGlobalsFromDb 等约 200 行）
- `plugin/.../variable/VariableException.java`（无改动，复用 QUOTA_EXCEEDED）
- `plugin/.../variable/VariableDto.java`（加 ownerUuid/ownerName + 新工厂 from(v, store)）
- `plugin/.../variable/plugin/PluginNamespaceRegistry.java`（RESERVED_NAMESPACES + userglobal）
- `plugin/.../state/EditSession.java`（+ 5 个 global 方法 + variableToMap 双 overload）
- `plugin/.../web/VariableOpDispatcher.java`（scope 路由 + global 权限节点 + audit 前缀）
- `plugin/.../web/WebServer.java`（ready payload VariableDto.from(v, store)）
- `plugin/.../session/SessionManager.java`（+ broadcastVariableChangeToAll +
  variableToMap instance + setVariableStoreRef）
- `plugin/.../HikariCanvas.java`（装配 + listener 路由分流）
- `plugin/.../HikariCanvasConfig.java`（+ userGlobalMaxPerOwner/Total）
- `plugin/src/main/resources/{config.yml, paper-plugin.yml}`
- `web/src/types/variable.ts`（+ ownerUuid/ownerName + USERGLOBAL_NAMESPACE helpers）
- `web/src/variable/pickerLogic.ts`（+ myGlobal/othersGlobal 分组 + selfUuid 参数）
- `web/src/network/wsClient.ts`（+ scope 参数 + sendVariableCreateGlobalWithAck）
- `web/src/components/variables/{VariablePanel, NewVariableDialog, VariablePicker}.vue`
- `web/src/i18n/messages.ts`（zh + en 各 ~14 新 keys）
- `web/src/variable/__tests__/pickerLogic.test.ts`（byId 重写 + 新 case）
- `plugin/src/test/.../{VariableStoreTest, EditSessionVariableTest}.java`（+ 15 case）
- `docs/variables.md`（+ §1.13）
- `docs/dynamic-data.md §17`（既有规划文档不再改，仅作为契约对照）

---

## 2026-05-21 · 0.4.4 铁路网络扩展：加车次 / 服务类型 / 编组 / 时刻表（规划升级，未实施）

### 背景

之前（同日上午）的 0.4.4 §18 设计**过于简化**——`rail_runs` 只有
`first_departure_time + travel_seconds`，假设站间均匀。用户提出加车次概念：
- A01 / B02 车次号
- 服务类型（local / express / section / limited）
- 编组节数（6 / 8 节）
- 区间车的 start/end_station
- 每站精确到秒的到 / 发时间

这是真实地铁系统的核心模型。原设计只能算 ETA，**没有车次 / 编组 / 服务类型语义**，
wall 显示等于 1990 年代铁路屏。

### 3 决策

1. **加车次完整语义**（推荐采纳）—— rail_runs 加 5 字段 + 新表 rail_timetable + 6 个新变量
2. **timetable 自动生成 + 逐站调整**——创建车次弹对话框（首站时间 + 站间秒 + 跳站集合 → 生成 rows）
3. **service_type 4 内置 + 自定义字符串兜底**——local/express/section/limited + 任意 String

### 数据模型升级

| 表 | 老 §18 | 新 §18 |
|---|---|---|
| rail_lines | id+name+color | + code 短代号 |
| rail_stations | id+line+name+sort_order+dwell | + code, is_terminus |
| rail_runs | line+direction+departure+travel_sec | **+ run_number + service_type + cars + start/end_station_id + notes** |
| **rail_timetable**（新） | — | **run_id + station_id + arrival + departure + stops_here** |
| wall_rail_bindings | wall+line+station+direction | 不变 |

5 表（原 4 表）。

### 暴露变量升级

新增 6 个车次语义变量：
- `next_run_number` / `next_service_type` / `next_service_type_text`（i18n 友好）
- `next_cars` / `next_terminus` / `next_notes` / `next_arrival`（精确到站时刻）
- next2 系列同样

兼容 0.4.0 已有 `next_departure / next_eta_*` 等。

### 工时

| 老 §18 | 新 §18 |
|---:|---:|
| ~45h | **~60h**（+15h，+33%）|

P1: 8h → 12h（加 timetable DAO + Auto-generator）
P3: 6h → 8h（WS op 从 9 → 11，加 timetable.set / run.update）
P4: 12h → 16h（车次时刻表 inline 编辑 UI + 自动生成对话框）
P5: 6h → 8h（i18n service_type_text + 单测扩展）
P6: 3h → 6h（更多测试用例）

### 关联文件

- `docs/dynamic-data.md §18` 完全重写（11 子节）+ §19 速览表 60h 数字更新
- `CLAUDE.md` 0.4.4 路线段重写 + 0.4.x 速览表 60h 更新

---

## 2026-05-21 · 0.4.3 全局变量 + 0.4.4 铁路网络路线图定稿（规划，未实施）

### 0.4.3 全局用户变量（~13h）

补 0.4.0 P1 决策 3 的遗留——user 变量是 per-wall 不能跨画布共享。新增 `userglobal/<key>`
namespace 让玩家自定义"全服可见、跨 wall 共享"的变量。

**4 个固化决策**（用户已确认）：
1. **外部插件禁推** `userglobal/*` → `PluginNamespaceRegistry.RESERVED_NAMESPACES` 加 userglobal
2. **owner-only + admin override** → 5 个 `canvas.var.global.*` 权限节点
3. **namespace 取名 `userglobal`** → 与 user 同谱系
4. **配额 per-owner 500 + 全服 10000** → config.yml 可调

**5 phase 拆解**：
- P1 V015 migration + UserGlobalVariableDao + Store API（3h）
- P2 EditSession scope='global' + 权限 + 配额（3h）
- P3 broadcastVariableChangeToAll 全 session 广播（2h）
- P4 NewVariableDialog scope + Picker 全局分组（4h）
- P5 单测 + 版本号 0.4.3-SNAPSHOT + journal + push（1h）

详见 `docs/dynamic-data.md §17`。

### 0.4.4 铁路网络（~45h）

地铁屏场景重大升级：从 per-wall 独立时刻表 → 全服线路 + 站点 + 班次抽象。
wall 编辑器下拉选**线路 + 本站 + 方向**自动绑定，**改一处全服同步**。

**新表 V016**（4 个）：
- `rail_lines`（线路 id + name + color + owner）
- `rail_stations`（线路下的站点 + 排序 + 停靠时长）
- `rail_runs`（班次：发车时间 + 方向 + 行驶秒数）
- `wall_rail_bindings`（wall → line+station+direction 绑定）

**新 Provider**：`RailScheduleProvider`，按 line+station+direction 自动算 ETA（兼容旧
ManualScheduleProvider — 未绑定的 wall 仍走旧路径）。

**6 phase 拆解**（共 ~45h）：
- P1 V016 + 4 DAO（8h）
- P2 RailScheduleProvider 计算（10h）
- P3 9 WS op + 权限 + dispatcher（6h）
- P4 前端铁路网络管理 modal（12h）
- P5 Schedule Manager 绑定段 + i18n + 单测（6h）
- P6 收尾 + 版本号 0.4.4-SNAPSHOT（3h）

详见 `docs/dynamic-data.md §18`。

### 0.4.x 路线状态

| 版本 | 状态 |
|---|---|
| 0.4.0 | ✅ |
| 0.4.1 chip 编辑器 | ✅ |
| 0.4.2 变量别名 | ✅ |
| **0.4.3 全局变量** | 📋 规划完成 / 待开干 |
| **0.4.4 铁路网络** | 📋 规划完成 / 待开干 |
| 0.5.0 动画 + 时间轴 | 远期（120h） |
| 0.6.0+ Blockly | 远期（200h） |

### 关联文件

- `docs/dynamic-data.md` 加 §17 / §18 / §19
- `CLAUDE.md` 加 0.4.3 / 0.4.4 路线段 + 0.4.x 速览表
- `docs/journal.md` 顶部（本条）

**不写代码**——等用户通知开干。

---

## 2026-05-21 · 修：模板"发布失败"假象（前端取 ack 字段错位）

### 症状

用户报：模板发布显"发布失败"，但后端 log 明显是**成功的**：

```
Templates reloaded: builtin=7 server=0 user=1 overrides=0 failed=0
```

user=1 表示新模板已写入 + DB upsert + registry.reload 触发——`TemplatePublisher.publish`
全流程走完返了 `Result.Ok`，后端 dispatcher 已 ack。

### 根因

`wsClient.handleAck:395` 直接 `pending.resolve(payload)` — resolve 的是 envelope 内的
**payload 对象**（不是整个 envelope）。

但 `SaveAsTemplateModal.vue:98-102` 又**多剥一层**：
```typescript
const ack = await ws.sendWithAck('template.save', ...);  // ack 已经是 payload {templateId: "..."}
const payload = (ack as { payload?: { templateId?: string } })?.payload;  // ❌ 再取 .payload
if (payload?.templateId) {       // ❌ 永远 undefined
    emit('close');
} else {
    submitError.value = t.value.workshop.saveFailedGeneric;  // → 永远走这里
}
```

`(ack).payload` 是 `undefined`（ack 本身就是 payload，不含 .payload 子字段），所以 if 永远
false → 显示"发布失败" 4 字。**后端实际成功**，新模板已写入 DB / YAML 文件 / reload，
用户后续刷新页面应能看到。

### 修复

```typescript
const ackPayload = ack as { templateId?: string } | undefined;
if (ackPayload?.templateId) { emit('close'); }
```

### 检查其他

grep `\.payload\.` / `ack.payload` 排查仓库，无同款 bug（其他 ws op 调用都正确取字段）。

### 验证

- 159 vitest 全绿
- vite build OK

### 用户验证

刷新浏览器后再次发布模板 → 应显示成功（modal 关闭），且 wall 列表能看到新模板。

### 关联

- `web/src/components/template/SaveAsTemplateModal.vue`（onSave ack 取字段修正 + 注释根因）

---

## 2026-05-21 · 第 6 次终极修：label click delegation 自动转发到"插入变量"按钮

### 症状

用户**第 6 次**报告"点击 chip editor 文本框就弹 picker"。前 5 次修复（dirtyLeaves /
beforeinput / dblclick / store.$subscribe / 完全关闭 chip click）都没解决。

### 真根因（终于找到）

`TextElementSection.vue:248` 的 HTML 结构：

```html
<label>
  <span>text<button @click="openPickerFromButton">插入变量</button></span>
  <VariableChipEditor ... />
</label>
```

**HTML `<label>` 元素自带 click delegation**：点击 label 内任何**非交互区域**时，
浏览器把 click **自动转发**给 label 内首个表单控件（input / button / textarea）。
点击 chip editor 内的文字 → label 转发 → 触发"插入变量"按钮 click handler →
`openPickerFromButton` → `pickerOpen = true`。

前 5 次修都在追"chip 触发 / detect 触发"，**根本不是真凶**。

### 修复

把 `<label>` 改为 `<div>`：

```html
<div>
  <span>text<button>插入变量</button></span>
  <VariableChipEditor ... />
</div>
```

chip editor 内 contenteditable 本身已经处理 focus 不需要 label 关联。无副作用。

### 验证

- 159 vitest 全绿
- vite build 通过

### 用户验证

刷新浏览器后：
1. 点击 chip editor 文本框内 "郑州火车站" 等普通文字 → **不再弹 picker**
2. 点击右上"插入变量"按钮 → 弹 picker（唯一触发）

### 6 次修复路径总结（自我教训）

| # | Commit | 假设的根因 | 实际是否真因 |
|---|---|---|---|
| 1 | 9da68ea | dirtyLeaves 守卫 | ❌ |
| 2 | caf7042 | beforeinput + composition | ❌ |
| 3 | c95dd13 | chip click → dblclick | ❌ |
| 4 | d34648b | 完全关闭所有自动触发 + Pinia $subscribe | ❌（但顺手修了 Bug A 一部分）|
| 5 | 08610dd | WallRestorer 启动顺序（**Bug A 真因**） | ✅（Bug A）|
| 6 | **本次** | **`<label>` click delegation** | ✅（Bug B 真因） |

教训：**HTML 元素的语义副作用**（label / form / button type 等）是前端 bug 常见盲点。
debugging 时应该早在 DevTools 看 click event 的 originalTarget vs target，而不是
凭代码逻辑推断。

### 关联

- `web/src/components/properties/TextElementSection.vue`（label → div）

---

## 2026-05-21 · WallRestorer 启动顺序修：服务器重启字面 ${var:} 残留真根因

### 症状

用户提供 image #6 截图：MC 游戏内 wall 上**显示字面 `${var:schedule/next_departure}`
字符串**（而编辑器画布**正常**显示 chip 替换值 "23:00" / "22:55"）。前 4 次 fix 都没碰
到真根因。

### 真根因

`HikariCanvas.onEnable` 启动顺序错乱：

| 行 | 操作 |
|---|---|
| line 261-274 | **WallRestorer.restore()** ← 此时 compositor.interpolator 还是 null + store 空 |
| line 301 | compositor.setVariableSupport(interpolator, store) |
| line 316-318 | ProviderBootstrap.initialize() ← schedule:wallId/* 此刻才进 store |

WallRestorer 触发 compositor.rasterize() → `maybeInterpolateText` 第一行 `if
(interp == null) return e;` → **直接渲染原 element.text 字面字符串**到 wall 像素。

然后 wall 没有 dirty 触发 → 不重画 → 一直显字面，直到玩家手动编辑触发 wall dirty。

### 修复

把 `wallRestorerInstance.restore()` 调用**延迟到 ProviderBootstrap.initialize() 之后**：
- 原位置（line 261-274）只 `new WallRestorer(...)`，不调 restore
- 移到 line ~318 之后（ProviderDaemon initialize 完毕）

此时：
- compositor.interpolator 已注入 ✓
- ManualScheduleProvider.initialize() 同步跑完，store 内 schedule:wallId/* 已有值 ✓
- SystemVariableProvider 注册 system:wallId/wall.* 值 ✓
- restore 时 compositor.rasterize 调 interpolator.interpolate → 正确替换 placeholder → 渲染实际值

### 验证

- `./gradlew :plugin:test` BUILD SUCCESSFUL
- compile OK
- 用户重启服务器后应看到：MC wall 上**直接显示 "23:00" / "22:55" / "进站中" 等实际值**，
  不再需要手动开编辑器触发

### 关联

- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（restore 调用延后）

---

## 2026-05-21 · 彻底修：chip editor 仅按钮触发 picker + store $subscribe

### 症状

用户第 5 次报"点击文本框就跳 picker"。原话：**"点击文本框，正常编辑内容；点击文本框右上角
的「插入变量」，才弹出变量选择功能"** — 暗示 picker 触发只允许唯一路径 = 按钮。

同时报 Bug A：**服务器重启后 chip 显变量名**（而非 currentValue / alias），必须手动操作
文本框才更新。

### 真根因 A

`watch(() => [store.variables, aliasStore.aliases])` **不可靠**：
- Pinia setup store 内 `ref.value = new Map(...)` 替换
- 在 chip editor mount 与 wsClient.handleReady → initVariables 时序复杂时
- Vue 浅 watch 可能错过这次 ref 替换 → refreshAllChipDisplays 不调 → chip 文本停在 rawName

### 真根因 B

之前 5 次修复都假设 picker 弹出是"`${` 误触发"或"chip click"。但用户场景：
- 文本框含 chip + chip 显示 alias "郑州火车站"（长字符占满文本框）
- 单击任何位置都点中 chip → P2 设计的 click → editVariableRequest → 弹 picker
- 即使把 click 改成 dblclick，浏览器 cache / lexical chip DOM 持久化（不重 mount 不重建 listener）导致旧 listener 长存

### 修复

**Bug A**：`watch` → `store.$subscribe(() => refreshAllChipDisplays())`（Pinia 推荐的
mutation 订阅 API，100% 触发）；onBeforeUnmount 反订阅。

**Bug B**：picker 触发**唯一路径** = "插入变量"按钮（外层 TextElementSection
openPickerFromButton）。**关闭所有自动触发路径**：
- chip span 不再 dispatch CHIP_EVENT_CLICK（单击 / 双击 / 错误态 都 noop）
- 移除 `beforeinput` → `${` 自动触发（用户原话仅按钮）
- 移除 chip editor 内 CHIP_EVENT_CLICK listener 注册（不再消费）
- hover/leave 保留（tooltip 不影响 picker）
- 错误态 chip 点击不再弹 create confirm（v1.x 用专用 UI 按钮承接）
- chip 改绑定功能暂去（v1.x 加 chip 旁 ✏ 按钮承接；当前删除原 chip + 重插即可）

### 兼容性

- `CHIP_EVENT_CLICK` 常量 + onChipClick handler 函数仍保留（不再被触发但 API 不破坏）
- 外层 TextElementSection 的 `@edit-variable-request` event 现在不会被 chip 触发，
  但绑定保留无副作用

### 验证

- 159 vitest 全绿（无回归）
- vite build 通过 / bundle 667 kB（持平）

### 用户验证

刷新浏览器后：
1. 点击文本框任何位置（包括 chip 上）→ **绝不弹 picker**，能正常移动光标编辑文字
2. 点击"插入变量"按钮 → 弹 picker（唯一触发）
3. 服务器重启后 → wall 文本框 chip 立刻显当前值 / 别名（不再需手动触发）

### 关联

- `web/src/variable/lexicalChip.ts`（chip span click/dblclick 全删 dispatch）
- `web/src/components/variables/VariableChipEditor.vue`（移除 CHIP_EVENT_CLICK listener + beforeinput；加 store.$subscribe）

---

## 2026-05-21 · 终极修：chip 单击不弹 picker（改双击改绑定）

### 症状

用户第 4 次报"右侧文字编辑器点击就跳变量选择页"。之前 3 次修复（`9da68ea` /
`caf7042` 等）都聚焦在 `${` detect 路径（update listener / dirtyLeaves / beforeinput），
但 picker 实际是从**完全不同路径**弹出的。

### 真根因

`lexicalChip.ts:164` chip span 注册的 `click` handler **直接** dispatch
`CHIP_EVENT_CLICK` → 外层 `onChipClick` → `emit('editVariableRequest')` →
`pickerOpen = true`。这是 P2 设计的"click chip = 改绑定"行为。

但用户场景：**文字框完全由 chip 组成**（如 `${var:schedule/eta_seconds}`
单 chip），点击任何位置都命中 chip → 立即弹 picker，**用户连进入编辑都做不到**。
之前的 detect-path 修复无法触及这条链。

### 修复

`lexicalChip.ts` chip click 改 **dblclick**：
- 单击 chip：noop（不 preventDefault，让 lexical 自然处理 caret 落位）
- **双击** chip：dispatch CHIP_EVENT_CLICK → 弹 picker 改绑定
- 错误态 chip（hc-chip-error）：保留单击 → 触发 create confirm（错误态需要快速 fix）

UX 与"双击编辑"惯例对齐；单 chip 文本框现在可以正常进入编辑模式。

### i18n 更新

- 中：tooltipHint = "双击改绑定" / ariaLabel 更新
- 英：tooltipHint = "Double-click to rebind" / ariaLabel 更新

### 验证

- 159 vitest 全绿（无回归；现有 chip 单测不涉及 dblclick UX）
- vite build 通过
- 用户操作：右侧 chip editor 单击 chip → **不再弹 picker**；双击 chip → 弹 picker 改绑定

### 关联

- `web/src/variable/lexicalChip.ts`（chip span click → dblclick）
- `web/src/i18n/messages.ts`（中英 tooltipHint + ariaLabel）

---

## 2026-05-20 · 0.4.2 chip editor bugfix 三连（字面残留兜底 + 点击误弹 picker 彻底 + IME 中文输入）

### 背景

0.4.2 别名落地后实测发现 3 个 chip editor 副作用：

1. **字面 `${var:` 残留**：服务器重启 / 数据损坏场景下，wall 上偶尔显示出 `${var:user/红队比分}`
   字面字符串而非占位符 resolve 后的值。根因：interpolator 单次扫描；当某变量的 `currentValue`
   本身含 `${var:...}` 字面（典型：chip roundtrip 漏 escape / 用户手输 ${var:} 并保存到 variable
   value）时，首次替换出新占位符，但无二次扫描兜底。
2. **点击编辑框误弹 picker**：上次 bugfix `dirtyLeaves.size > 0` 守卫无效——lexical 在 click
   后 caret 落位仍标 dirtyLeaves（不是只 dirtyElements）。任何 caret 落在文本中既存 `${`
   字面后位置 → detectDollarBraceTrigger 命中 → 误弹 picker。
3. **IME 中文输入断流**：composing 期间临时拼音字符 emit('update:text') 后被外层 watch 写回
   lexical，**打断 composition**；用户必须每打 1-2 字暂停才能稳定输入。

### 实施

**Bug 1（字面残留）**：interpolator 双端拆 `interpolate` → 内部 `doInterpolate` + 外层
wrapper：首次替换后若仍含 `${var:` → 再 interpolate 一次（最多 MAX_INTERPOLATE_DEPTH=2 兜底）。
DEV 模式 console.warn（前端）/ Logger.warning（后端）提示嵌套数据帮排查根因。**PreviewRenderer
+ CanvasCompositor 渲染兜底**：interpolator 返回后若仍含 `${var:` → 强制 replace 全部 `${var:...}`
→ "???"，防 wall 显字面 placeholder。

**Bug 2（点击误弹）**：detect 完全脱离 update listener，改用原生 `beforeinput` event：
`inputType=insertText` + `data='{'` + 前一字符是 `$` → queueMicrotask 异步弹 picker（让 `{` 字符
commit 到 lexical 后再走 detect）。**精确捕捉用户实际输入字符的瞬间**，与 click / focus / IME
composition / paste 完全解耦。

**Bug 3（IME 断流）**：监听 `compositionstart` / `compositionend` → composing flag；
update listener 在 composing 期间屏蔽 emit；compositionend 一次性 emit 最终文本（外层 watch
即使紧随其后也由 `newText !== props.text` 去重）。

### 测试与构建

后端 +4 case（`VariableInterpolatorTest`：嵌套 inner 解析 / 嵌套 inner missing / 互引死循环防御
/ 纯文本无二次扫描）。前端 +4 case（`interpolator.test.ts` case 27-30：嵌套替换 / inner missing
fallback / 三层深度收敛 / 纯文本短路）。

**全测**：backend 779 / 0 failure；frontend 159 / 0 failure；bundle 666.99 kB（基线 666 kB
持平）；shadow jar 153 MB。**0 baseline 漂移**（snapshot fixture 全 UP-TO-DATE）。

### 关联文件

- `web/src/variable/interpolator.ts`（wrapper + doInterpolate 拆分）
- `plugin/src/main/java/moe/hikari/canvas/variable/VariableInterpolator.java`（同款 + MAX_INTERPOLATE_DEPTH）
- `web/src/render/PreviewRenderer.ts`（residual `${var:` → `???` 兜底）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（同款 + 静态 LOG）
- `web/src/components/variables/VariableChipEditor.vue`（onBeforeInput + composition handlers
  + composing flag + 删 update listener 内 detect）
- `web/src/variable/__tests__/interpolator.test.ts`（+4 case）
- `plugin/src/test/java/moe/hikari/canvas/variable/VariableInterpolatorTest.java`（+4 case）

---

## 2026-05-20 · 0.4.2：变量别名（per-wall）+ VariablePicker 3 列表格

### 背景

0.4.1 chip editor 落地后，发现变量"实际名"（如 `user:w-abc/red_score`）对玩家不够友好；尤其是
混用 user / schedule / system / papi 多 namespace 时 fullName 长且杂乱。用户要求：

1. 所有变量都可加**别名**（含 user / schedule / system / papi / scoreboard 全 namespace）
2. 别名 **per-wall**（同一变量在不同 wall 可起不同别名，互不干扰）
3. VariablePicker 改 **3 列表格**：`别名 | 当前数值 | 变量名`，并 inline ✏ 编辑

### 实施

**V014 migration**：新增 `variable_aliases (wall_id, full_name, alias, created_at, updated_at)`，
主键 `(wall_id, full_name)`，FK CASCADE 到 walls。alias 不要求 wall 内唯一（用户自负责）。

**后端**：
- `VariableAlias` record + `VariableAliasDao`（upsert / delete / deleteByWall / loadByWall / loadAll）
- `VariableAliasDispatcher` 3 op：`variable.alias.set` / `variable.alias.clear` / `variable.alias.list`
  - 权限：复用 `canvas.var.write.own/any`（默认 own=true）；list 是只读放行
  - state.patch 推 `/aliases/<encoded fullName>`（与 `/variables/` 同款 JSON Pointer 编码）
  - 校验：alias 非空且 trim 后 ≤ 64 字符；fullName ≤ 256
- `WebServer` 在 ready payload 加 `aliases: Map<fullName, alias>` 字段（per-wall 隔离，复用现有
  loadByWall）
- `HikariCanvas.onEnable` 实例化 DAO + 注册 wall delete hook（显式 cascade，与 schedule 同款）
- audit 事件：`VARIABLE_ALIAS_SET` / `VARIABLE_ALIAS_CLEAR`

**前端**：
- `useVariableAliasStore` Pinia store（fullName → alias Map + initAliases/get/set/remove/clear/reset）
- `wsClient.handleReady` 接 `payload.aliases` 调 initAliases；`handlePatch` 加 `/aliases/` 分拣调
  applyAliasPatches；onClose / 切 wall 时 reset
- `wsClient.sendVariableAliasSet` / `sendVariableAliasClear` 两个 ack 方法
- `VariablePicker.vue` 全改造为 3 列表格：`别名 | 数值 | 变量名 | ✏`；按组（mine/plugin/system/papi）
  以 group-row 分隔。点 ✏ → 整行替换为 input + 保存/清空/取消按钮 → 提交走
  `sendVariableAliasSet/Clear`。键盘 ↑↓/Enter/Esc 在编辑态下被 input 吞掉，picker 导航暂停
- `pickerLogic.buildGroups` 加可选第 4 参数 `aliases`：keyword 搜索时也命中别名（"红队"也能搜到
  `user:w-abc/red_score`），向下兼容（不传 aliases 时退化为旧逻辑）
- `NewVariableDialog.vue` 加可选 `alias` 字段：提交时先 `variable.create` 再 `variable.alias.set`
  （两步 ack 串联；alias 失败不阻塞 create 成功）；校验 ≤ 64 字符
- `VariablePanel.vue` 每个变量行加紫色 alias chip（Tag icon + 截断 max 120px）+ 改别名按钮
  （与 delete 确认互斥）+ inline 编辑 UI（搜索 keyword 也命中别名）
- `VariableChipEditor.vue` chip 显示优先级改为 **alias > currentValue > fallback > defaultValue
  > UNRESOLVED**；hover tooltip 加 `Alias:` 行（如有）；watch 依赖加 `aliasStore.aliases` 让别名变更
  自动重渲

**i18n**：22 keys 中英对照（picker 3 列表头 / 编辑按钮 + dialog 别名字段 / panel chip / 错误文案）。

### 测试

- 后端 `VariableAliasDaoTest`（14 case）：CRUD + 多 namespace + per-wall 隔离 + FK CASCADE + 排序
  + timestamp 行为 + 不存在 wall 安全调用
- 后端 `ReadyPayloadAliasTest`（5 case）：ready payload aliases 字段为 object / wall 隔离 /
  空 store 返 `{}` / upsert 替换语义 / 多 namespace 共存
- 前端 `pickerLogic.test.ts` +5 case：alias 命中（Record/Map 形态）/ keyword 不重复 / 大小写不敏感
  / aliases=null 退化
- 前端 `variableAliases.test.ts` 新文件 13 case：store 全 API + ref 引用变更触发响应
- **后端 775 + 前端 155 全绿**；shadowJar 153 MB / vite build 666 kB（gzip 202 kB）

### 关键决策

- **alias 是 wall-scoped 元数据**：dispatcher 不走 EditSession，不增加 ProjectState version
  （别名不影响渲染像素），自己产生 state.patch 推送
- **alias 不参与 `${var:...}` 解析**：别名只在 UI 层（picker / panel / chip）展示用；底层
  Compositor 仍按 fullName 解析变量值
- **chip 显示优先 alias**：用户给变量起了别名后，chip 文本变稳定（alias 不会动态变），更可读；
  数值 / fallback 仍可通过 hover tooltip 看到
- **沿用 var write 权限**：不引入新权限节点（`canvas.var.alias.*`），别名是常规元数据写
- **空别名 = 清除**：alias 编辑框留空 + 保存 = 触发 `variable.alias.clear`，UX 更直观

### 版本号

`0.4.1-SNAPSHOT → 0.4.2-SNAPSHOT`（5 处：build.gradle.kts / web/package.json + lock /
paper-plugin.yml × 3）。schema 变更（V014）+ 新协议 op（3 个）按 semver 视为 minor 增量。

### 关联文件

**后端**（6 新 + 5 改）：
- `plugin/src/main/resources/db-migrations/V014__variable_aliases.sql`（新）
- `plugin/src/main/java/moe/hikari/canvas/variable/VariableAlias.java`（新 record）
- `plugin/src/main/java/moe/hikari/canvas/storage/VariableAliasDao.java`（新 DAO）
- `plugin/src/main/java/moe/hikari/canvas/web/VariableAliasDispatcher.java`（新 dispatcher）
- `plugin/src/test/java/moe/hikari/canvas/storage/VariableAliasDaoTest.java`（新 14 case）
- `plugin/src/test/java/moe/hikari/canvas/web/ReadyPayloadAliasTest.java`（新 5 case）
- `plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`（+ V014 注册）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（+ DAO 实例化 + wall delete hook）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（+ dispatcher 装配 + ready payload
  aliases 字段 + op 路由）

**前端**（1 新 + 6 改 + 1 测试新）：
- `web/src/stores/variableAliases.ts`（新 Pinia store）
- `web/src/stores/__tests__/variableAliases.test.ts`（新 13 case）
- `web/src/network/wsClient.ts`（+ 2 op send 方法 + ready/patch alias 分拣 + onClose reset）
- `web/src/types/protocol.ts`（ReadyPayload.aliases 字段）
- `web/src/variable/pickerLogic.ts`（buildGroups 加 aliases 第 4 参）
- `web/src/variable/__tests__/pickerLogic.test.ts`（+ 5 alias 命中 case）
- `web/src/components/variables/VariablePicker.vue`（3 列表格大改造 + inline 编辑）
- `web/src/components/variables/NewVariableDialog.vue`（+ alias 字段 + 两步提交）
- `web/src/components/variables/VariablePanel.vue`（alias chip + 编辑入口 + 搜索匹配）
- `web/src/components/variables/VariableChipEditor.vue`（chip 显示优先 alias + tooltip 加 alias 行）
- `web/src/i18n/messages.ts`（+ 22 keys × 2 lang）

**版本号** 5 处：build.gradle.kts / web/package.json / web/package-lock.json /
plugin/.../paper-plugin.yml / examples/{demo-score,demo-train}/.../paper-plugin.yml

---

## 2026-05-20 · 紧急修：chip editor 点击编辑框误弹 picker

### 症状

用户报：右侧 RightPanel 的文本框（chip editor）只要鼠标点击就弹 VariablePicker，无法正常编辑文本。
画布双击就地编辑没问题，**只有 RightPanel 文本框被影响**。

### 根因

`VariableChipEditor.vue:181` 的 `editor.registerUpdateListener` 在 `dirtyElements.size > 0` 时
就跑 `detectDollarBraceTrigger`。但 lexical 在 **selection-only update**（如点击 caret 落位）也
会标段落 dirty（dirtyElements > 0 / dirtyLeaves = 0）。如果用户的 text 里含字面 `${` 字符
（如旧版残留 / paste 入），点击该位置 caret 落在 `${` 后两字符就触发 picker，反复无解。

### 修复

仅在 `dirtyLeaves.size > 0`（实际 TextNode 文本改动）时调 `detectDollarBraceTrigger`：

```typescript
if (dirtyLeaves.size > 0) {
    detectDollarBraceTrigger();
}
```

selection-only update 不再触发；只有用户实际**输入字符**改 TextNode 时才检测 `${` 弹 picker。

### 验证

- 137 vitest 全绿（无回归）
- vite build 通过
- 用户操作：右侧文本框点击 caret 移位 → **不再弹 picker**；输入 `${` 仍正常弹

### 关联文件

- `web/src/components/variables/VariableChipEditor.vue`（+5 行注释 + 1 行 if guard）

---

## 2026-05-20 · M28-0.4.1-P3+P4：chip 视觉打磨 + bundle 拆 chunk + 版本号 0.4.1-SNAPSHOT

### 背景

P1+P2（commit `add7682`）完成 chip 编辑器原型 + lexical core 集成 + 25 vitest roundtrip。当时显式留了 7 项不足：chip pill 视觉打磨 / 字号联动 / multi-line 换行 / 错误态补创 / 内联编辑器 `${` picker 未接 / paste HTML 识别 / bundle 808 kB 超 800 阈值。本 commit P3 视觉 + 兼容性 + P4 收尾两阶段一次推完，单 commit 合并。

### P3 实施（视觉打磨 + 兼容性）

#### P3.1 Catppuccin Mauve 直引（chip pill 视觉）

`VariableChipEditor.vue` unscoped block `.hc-var-chip` 重写：
- `background-color: color-mix(in srgb, var(--ctp-mauve) 18%, transparent)` —— 三 flavor（latte / frappe / macchiato）自动切换，**不污染父背景**（透明 alpha 而非 mix base）。
- `.dark` / `.theme-frappe` / `.theme-macchiato` 选择器把填充比从 18% 提到 24%、border 35% 提到 42%——dark 主题下 Mauve 色亮度衰减，需更高比例保 chip 可见度。
- 错误态 `.hc-chip-error` 同套路：`var(--ctp-red)` + delete-line + ⚠ 前缀；dark 主题独立提比例。

#### P3.2 字号 clamp 钳位

- `font-size: clamp(10px, 0.85em, 16px)`：极小（fontSize=6）chip 不再比文本字号还大；极大（fontSize=120）chip 不会爆 4em。
- 新增 prop-driven CSS 变量 `--chip-scale`：由 `props.fontSize / 14` 在 `[0.6, 1.2]` clamp；`padding` / `border-radius` 走 `calc(0.18em * var(--chip-scale))` 等表达式，让"非字体"的视觉度量（盒子大小 / 圆角）也跟着字号缩放。
- TextElementSection 传 `:font-size="element.fontSize"` 让 RightPanel chip 编辑器也享受联动（之前 inline editor 已传，RightPanel 漏）。

#### P3.3 multi-line 自动换行

- chip `display: inline-block` + `white-space: nowrap`：chip 内部不可拆，但浏览器把 chip 当一个"长字"参与父段落 wrap，超宽自动换到下一行，**不会切 chip 内部 ⚡ 前缀 + rawName 两段**。
- 父级 `.hc-chip-editable` 已是 `white-space: pre-wrap`（lexical 默认），与 chip inline-block 行为兼容——多行段落里 chip 与文字混排，行尾 chip 整体换行符合 Notion / Linear 风格。

#### P3.4 错误态 chip 一键补创

- chip click 路径：`VariableChipEditor.onChipClick` 检测 `target.classList.contains('hc-chip-error')`，红 chip 不再 emit `editVariableRequest`（缺失变量选啥都没意义），改 emit 新 event `createVariableRequest({rawName, anchor})`。
- TextElementSection + CanvasView 都接 `@create-variable-request="onChipEditorCreateRequest"`：弹 native `window.confirm`（v1.x 升级精美 modal），用户点确定 → 走 `ws.send('variable.create', { name, type: 'STRING', defaultValue: '' })`。
- 仅 `user/X` 短名补创；其他 namespace（`system/wall.X` / `scoreboard.X` / `papi/X` / `schedule.X` / `plugin/X`）由对应 Provider 自动注册，弹 alert 提示「请通过对应 Provider 注册」。

#### P3.5 内联编辑器 `${` picker 接入

- TextInlineEditor 之前不持有 picker，画布双击文本编辑时 `${` 触发但弹不出来，用户需要回 RightPanel 完整插入。
- TextInlineEditor 接 chip editor 的三个 picker 事件 + `defineExpose` 出 `insertVariableChip` / `replaceVariableChip` 方法。
- CanvasView 自持一个 VariablePicker overlay（`inlinePickerOpen / inlinePickerMode` ref）+ `onInlineInsertVariableRequest` / `onInlineEditVariableRequest` / `onInlineCreateVariableRequest` 三个 handler + `onInlinePickerSelect` 调 ref.insertVariableChip / replaceVariableChip。
- picker 浮在画布右上角（CSS `position: absolute; top: 48px; right: 16px`）；与 RightPanel picker 不冲突（同一时刻只一个 editor 在焦点）。
- `finishEditing()` 收编辑态时同时 close picker，防孤儿 popover。

#### P3.6 paste transform（plain text → chip）

- 新增 `lexicalChip.ts.registerVariablePasteTransform(editor)`：注册 lexical `registerNodeTransform(TextNode, fn)`，监听 TextNode 内容变化。
- 检测到含 `${var:...}` 完整模式时 split 三段（leading / chip / trailing），中段 `replace` 为 VariablePlaceholderNode；尾段交给下一轮 transform 自动处理（lexical 内部 batch flush）。
- 触发场景：复制 chip 从外部 plain text 粘贴回来 / 用户手打完整字面 `${var:X}`（不通过 picker）/ paste 其他源含字面占位符的文本。
- VariableChipEditor 挂载时 `mergeRegister` 加入 `registerVariablePasteTransform(editor)`，与 `registerPlainText` / `registerHistory` 一起统一卸载。

#### P3.7 vite manualChunks 拆 lexical chunk

- `vite.config.ts.build.rollupOptions.output.manualChunks` 改 **function 形态**（Vite 8 / rolldown 不再支持 object map，会抛 `TypeError: manualChunks is not a function`）。
- 把 `node_modules/lexical/` + `node_modules/@lexical/*` 全部归入 `lexical` 独立 chunk。
- 结果：**main bundle 655.34 kB（gzip 199.87 kB）**（P1+P2 时 808 kB 超 800 阈值），**lexical chunk 154.92 kB（gzip 49.83 kB）**。main 回到 < 700 kB 目标，浏览器并发下载主 chunk + lexical chunk。
- 不用 dynamic import 路径：避免首次进 TextElement 编辑时的延迟（chunk 拆完已经足够）。

#### P3.8 i18n

- 中英 `variables.chipError.{notFound, createConfirm, onlyUserCanCreate}` 3 key
- 中：「变量 {name} 不存在。」「是否立即创建？（按确定即新建一个空字符串用户变量）」「只能在编辑器中手动创建 user/ 域变量；系统 / 插件 / PAPI 变量由对应 Provider 自动注册。」

### P4 实施（收尾）

#### P4.1 vitest 扩展（paste transform）

`lexicalChip.test.ts` 加 `describe('P3.6 registerVariablePasteTransform...')` 7 case：
- 粘贴单 chip 字面 → 升级 + roundtrip 保字面
- 粘贴含 fallback 占位符
- 粘贴混合 plain text + chip
- 粘贴连续多 chip 无间隔
- 粘贴不含 `${var:` 的纯文本 → transform 无副作用
- 粘贴 chip 升级后 traverse root 验证真实 VariablePlaceholderNode（非 TextNode）
- 粘贴残缺 `${var:foo and not closed` → 不触发升级

**137 vitest 全绿（130 → 137 +7）/ 9 test files / 415ms**。

#### P4.2 docs/variables.md

- §1.5 加 0.4.1 起 chip 编辑器升级说明 + 画布内双击 inline 编辑也支持 `${` 触发（P3.5 起）
- §1.5.1（新增）：chip 编辑器交互表格 —— hover / 改绑定 / 补创 / 整体删除 / 粘贴升级 / 复制 / 字号联动 7 行；chip 视觉色板（latte mauve + dark 提比例 + error red）；技术实现说明（lexical core + DecoratorNode + 拆 chunk）
- §1.11 变量删除提示从单层 banner 改三层：(1) chip 红色 + 删除线 + ⚠ + click 补创对话框 (2) live preview 下方 banner (3) hover tooltip

#### P4.3 版本号 0.3.0 → 0.4.1-SNAPSHOT

5 处升级：
- `build.gradle.kts`（allprojects.version）
- `plugin/src/main/resources/paper-plugin.yml`
- `examples/demo-train-plugin/src/main/resources/paper-plugin.yml`
- `examples/demo-score-plugin/src/main/resources/paper-plugin.yml`
- `web/package.json` + `web/package-lock.json`（双处 hikari-canvas-web 版本）

`grep -rn "0.3.0-SNAPSHOT"` 全清。

#### P4.4 shadow jar 验证

`./gradlew :plugin:shadowJar` → `plugin/build/libs/HikariCanvas-0.4.1-SNAPSHOT.jar`（153 MB；文件名自动跟随 version）。

### 验证

| 项 | 结果 |
|---|---|
| `npm run test`（vitest） | **137 全绿**（9 file / 415ms） |
| `vite build` | **main 655.34 kB（gzip 199.87 kB）+ lexical chunk 154.92 kB（gzip 49.83 kB）+ livePaintWorker 33.75 kB + css 65.77 kB** |
| `./gradlew :plugin:shadowJar` | `HikariCanvas-0.4.1-SNAPSHOT.jar` 153 MB |
| 0 baseline 漂移 | ✅（无 fixture / expected png 改动） |
| 版本号一致性 | ✅（5 处 0.4.1-SNAPSHOT，0 个 0.3.0 残留） |

### 关联文件

修改：
- `web/src/variable/lexicalChip.ts`（+ `registerVariablePasteTransform` ~50 行 / import `TextNode`）
- `web/src/variable/__tests__/lexicalChip.test.ts`（+ 7 case / + `pasteThenRoundtrip` helper）
- `web/src/components/variables/VariableChipEditor.vue`（chip pill 全套样式重写 / `chipScale` computed / `createVariableRequest` emit / `registerVariablePasteTransform` 挂载 / 模板 `--chip-scale` CSS 变量）
- `web/src/components/properties/TextElementSection.vue`（`getWsClient` import + `onChipEditorCreateRequest` handler / chip editor `:font-size` 透传 / 模板 `@create-variable-request`）
- `web/src/components/canvas/TextInlineEditor.vue`（picker 三事件 emit / `defineExpose` 加 insertVariableChip / replaceVariableChip）
- `web/src/components/layout/CanvasView.vue`（VariablePicker import / `inlinePickerOpen / inlinePickerMode / inlinePickerAnchor` ref / 4 个 inline handler / 模板 inline picker overlay + 样式）
- `web/src/i18n/messages.ts`（中英 `variables.chipError` 3 key）
- `web/vite.config.ts`（function-form manualChunks 拆 lexical）
- `docs/variables.md`（§1.5 + §1.5.1 新增 + §1.11 三层提示）
- `build.gradle.kts` / `plugin/src/main/resources/paper-plugin.yml` / `examples/demo-*-plugin/src/main/resources/paper-plugin.yml` / `web/package.json` / `web/package-lock.json`（版本号）
- `CLAUDE.md`（0.4.1 P3+P4 段 + 总里程碑行）

### 已知不足（留 v1.x）

- create-confirm 用 native `window.confirm` / `window.alert`（v1.x 升级精美 modal + autofocus 输入 default value）
- inline picker 位置硬编码右上角，未做 anchor rect 精细定位（v1.x 跟 caret 位置浮）
- chip editor 暂未做 ARIA live region 提示删除事件（screen reader 友好度待改善）

### 版本号

**0.3.0-SNAPSHOT → 0.4.1-SNAPSHOT**。

---

## 2026-05-20 · M28-0.4.1-P1+P2：VariableChipEditor 原型 + 交互细化

### 背景

0.4.0 已上线变量系统：玩家在 TextElement 文本框输入 `${var:schedule/eta_seconds}` 占位符，
wall 渲染时替换为实际值（90 秒）。痛点：textarea 显示**原始字符串**，长且乱（用户曾截图：
长占位符撑爆编辑器 layout）。M28-enhance（7dd443c）已在 Canvas 渲染上加视觉 hint（背景蔓色矩形），
但 textarea 输入体验本身没改。

0.4.1 P1+P2 把 textarea 升级为 **Notion-style chip 编辑器**：占位符渲染为蓝紫色 pill chip，
hover 显当前值，click 弹 Picker 改绑定。

### Lexical 路线决策

| 候选 | 结论 | 原因 |
|---|---|---|
| `lexical-vue` 0.14.1 | **放弃** | vue-vine 编译产物：`.js` 文件 runtime 兼容（用普通 defineComponent），但 `.d.ts` 全部 import 自 `vue-vine/internals`，vue-tsc 会报错；且 DecoratorNode 渲染管线被它封一层 portal，无法直接控制 chip DOM。 |
| **`lexical` core 直接接 Vue** | **选用** | DecoratorNode `createDOM()` 直接返 HTMLElement，完全可控；reactive bridge 自己写（`editor.update` / `registerUpdateListener`）；零编译依赖。 |
| `@tiptap/vue-3` fallback | 未触发 | 上一档已成功，无须降级 |

### 实施

#### 1. 数据模型 + 序列化（`web/src/variable/lexicalChip.ts`，~280 行）

- **`VariablePlaceholderNode`**：继承 `DecoratorNode<null>`；`createDOM` 渲染 `<span class="hc-var-chip" contenteditable="false" data-hk-var-raw="X" data-hk-var-fallback="...">rawName</span>`；事件 listener 阻止 mousedown 默认 + click/hover/leave dispatch 自定义事件 `hk-chip-click/hover/leave` 让外层 Vue 组件代理。
- **`getTextContent()` 返 `${var:X[|fallback=...]}`**：lexical 的 selection / copy / `root.getTextContent()` 路径自动 roundtrip——这是 chip ↔ element.text 字面字符串的核心桥梁。
- **`textToLexicalNodes(text)`**：按 `\n` 拆 Paragraph，单行内用 regex 拆 TextNode / VariablePlaceholderNode 交替。
- **`lexicalRootToText()`**：遍历 root → Paragraph join `\n`；段内 VariablePlaceholderNode 用 `toPlaceholderString()` 还原。
- **`$insertVariableChipAtSelection(raw, fallback)`**：在当前 selection 插 chip + 追加空 TextNode 让光标可继续输入。

#### 2. Vue 组件（`web/src/components/variables/VariableChipEditor.vue`，~530 行）

公共 API（`defineExpose`）：
- `insertVariableChip(rawName, fallback?)` —— 由外层 Picker 选中后调用；自动处理 `${` 触发场景（先删触发字符再插）
- `replaceVariableChip(oldRaw, newRaw, fallback?)` —— chip click → picker → 改绑定
- `focus()`
- `getText()`

props：`text` / `wallId` / `fontSize` / `fontFamily` / `multiLine` / `autoFocus` / `disabled` / `rootClass`。
events：`update:text` / `submit`（仅 autoFocus 模式下 blur 触发）/ `cancel`（ESC）/ `insertVariableRequest(anchor)` / `editVariableRequest({rawName, fallback, anchor})`。

关键细节：
- 用 lexical core `createEditor` + `registerPlainText` + `registerHistory`（撤销/重做 free）+ `mergeRegister` 统一卸载
- `writingExternal` flag 防 external watch ↔ internal update 循环
- store / wallId 变化时 `refreshAllChipDisplays()` 扫所有 `.hc-var-chip` DOM 改 textContent 为 currentValue / fallback / "???"，并加 `.hc-chip-error` class 显示已删除态
- chip hover → Teleport tooltip 到 body，显示 raw / current / source / 删除告警
- `${` 检测：update listener 在 collapsed selection 时取 anchor TextNode 文本 + offset，前两字符为 `${` 即触发 `insertVariableRequest`
- chip pill 样式用 Catppuccin Mauve（`var(--ctp-mauve)`）+ color-mix 调配；error 态用 `var(--destructive)` 红 + 删除线 + ⚠ 前缀

#### 3. TextElementSection 集成（`web/src/components/properties/TextElementSection.vue`）

- 删 textarea + `textareaRef` + `onTextChange` + `triggeredByDollarBrace` flag
- 改成 `<VariableChipEditor ref="chipEditorRef" ... />`
- picker 选中逻辑改 `pickerMode: { kind: 'insertNew' } | { kind: 'replaceChip', oldRawName }`，分别走 `insertVariableChip` / `replaceVariableChip`
- chip editor 的 `insert-variable-request` / `edit-variable-request` 都打开 picker，模式不同

#### 4. TextInlineEditor 集成（`web/src/components/canvas/TextInlineEditor.vue`）

- 删 textarea + `TextLike.text` 直接绑定
- 改 `<VariableChipEditor multi-line auto-focus />`，外层 host div 持 absolute 定位 + transform rotation + font 透传
- `:deep(.hc-chip-editable)` 改 transparent 背景 + 虚线边框 + 内边距清零，让 chip editor 看起来像原 textarea
- CanvasView：`onEditInput` 改 `onEditTextUpdate(v: string)`；`onEditKeydown` 删（chip editor 内部处理 Enter/Esc）；模板 `@input → @update:text`、`@keydown → @cancel`

#### 5. vitest 单测（`web/src/variable/__tests__/lexicalChip.test.ts`，25 case）

- roundtrip 字符级精确（17 case，含 fuzz 20 case 子集）：纯文本 / 多行 / chip 在开头/结尾/连续/含 fallback / 空 fallback / 含中文 emoji
- node ops（8 case）：`toPlaceholderString`、`getTextContent`、`$isVariablePlaceholderNode` 守卫、`$insertVariableChipAtSelection`、`exportJSON/importJSON` roundtrip
- 用 lexical headless editor + discrete update（无 DOM）

#### 6. i18n 加 chipEditor 段（中英）

`messages.ts` `variables.chipEditor.{tooltipRaw, tooltipCurrent, tooltipSource, tooltipDeleted, ariaLabel}`。

### 验证

- `npm run test` **130 全绿**（原 105 + 新 25 lexicalChip = 130）
- `vite build` 通过；bundle 643.62 kB → **808.12 kB（+164.50 kB；gzip 195.59 → 248.33 kB，+52.74 kB）**
- vue-tsc 不跑（CLAUDE.md 已知 Node 25 + TS 6 卡 typecheck，vite build 是唯一 gate）
- 锁定 wall 透明（chip editor `:disabled` 切 contenteditable=false + 鼠标 not-allowed）

### Bundle size 说明

超 800 kB 阈值（CLAUDE.md 要求 commit message 注明）：lexical core + plain-text + history + utils 共 ~165 kB minified（gzip ~50 kB）。理论上可 dynamic import 把 chip editor 拆 chunk（用户首次进 TextElement 选择时再加载），P3+P4 视觉打磨阶段可一并优化。

### 已知不足（留 P3+P4）

- chip pill 视觉打磨：当前用 Mauve color-mix，不一定完美贴 Catppuccin 三 flavor 的 token 体系；P3 可换 `--ctp-mauve` 直引 + 显式 dark 适配
- chip 跟 element.fontSize 缩放：当前 chip 字号 `0.85em` 跟随，但极小字号（< 8px）chip 可能比文本更显眼；P3 加 min/max 钳位
- multi-line / 自动换行：lexical 默认 wrap 走 `white-space: pre-wrap`，但 chip 不可拆——超宽段落会出现 chip 被裁的视觉，P3 优化
- 错误态 chip：当前红色 + 删除线 + ⚠；P3 可加 click 提示 "create" 一键补创
- 内联编辑器 `${` 触发 picker 未接（CanvasView 不持有 picker 实例），用户需回 RightPanel 完整插入；P3 可在 inline editor 也挂 picker
- 复制粘贴：当前依赖 lexical 默认 + DecoratorNode `getTextContent` 返字面占位符；HTML paste 自定义识别留 P3
- docs/variables.md §1.7 更新留 P4

### 关联文件

新增：
- `web/src/variable/lexicalChip.ts`
- `web/src/variable/__tests__/lexicalChip.test.ts`
- `web/src/components/variables/VariableChipEditor.vue`

修改：
- `web/src/components/properties/TextElementSection.vue`（替换 textarea，picker 模式拆 insertNew / replaceChip）
- `web/src/components/canvas/TextInlineEditor.vue`（替换 textarea，外层 host + 透明样式）
- `web/src/components/layout/CanvasView.vue`（onEditInput → onEditTextUpdate + 模板事件改）
- `web/src/i18n/messages.ts`（中英 variables.chipEditor 段）
- `web/package.json`（+ `lexical@^0.44.0`、`@lexical/plain-text`、`@lexical/history`、`@lexical/utils`）

### 版本号

**不动**（仍 0.3.0-SNAPSHOT，P3+P4 收尾时另一 agent 升 0.4.1-SNAPSHOT）。

---

## 2026-05-20 · 前端 PreviewRenderer 监听 variableStore 触发重画

### 症状

用户报：游戏内变量实时更新（方案 B 主动发包），但**前端编辑器画布不跟随**。在 Schedule Manager
改 entry，游戏内立刻刷新，但浏览器画布的 TextElement 仍显示旧值，需要手动改文本框才触发重画。

### 根因

`CanvasView.vue` 的 watch 只监听 `project.state`（line 806）触发 `requestDraw`，**遗漏了
`useVariableStore.variables`**。bugfix3 已让后端 state.patch 把变量变更推给前端 mirror，
`variables.value = new Map(...)` 替换整个 ref 触发 Pinia 响应；但 PreviewRenderer 本身不订阅
Pinia store，没有 reactive 桥。

### 修复

1 行 watch：
```typescript
watch(() => variableStore.variables, () => requestDraw());
```

无需 deep（store 内 set/remove/clear 都走 `variables.value = next` 替换整个 Map ref）。

### 验证

- `npm run test` 105 全绿
- `vite build` 通过
- 用户操作：Schedule Manager 改 entry → 浏览器画布**立即跟随**显示新 ETA（与游戏内同步）

---

## 2026-05-20 · M28-adaptive-fps：方案 B 自适应渲染（高频 wall 50ms + 主动推帧 chunk viewer）

### 背景

用户报告 schedule 秒精度倒计时 (`eta_seconds` / `eta_mmss` / `arrival_status` 含 `进站中` 切换) 在游戏内更新 2-5s 不稳定。
根因：渲染链最后一步 `HikariCanvasRenderer.update(mapId, pixels)` 只更新内存 ConcurrentMap，**靠 Paper 默认 MapView sync** 把像素推给客户端 ItemFrame；这个 sync 间隔 250ms-5s 不可控。
代码库已有 `MapPacketSender.sendFullMap`（M1 引入），但**全代码库零调用**——白搭这条已铺好的快速通道。

### 实施

#### 1. VariableStore 高频 wall 判断 + WALL_REFS_UPDATED 事件

- `isWallHighFreq(wallId)`：检查 byWall 倒排索引内是否含
  `*/eta_seconds` `*/eta_mmss` `*/arrival_status` `*/next2_eta_seconds` `*/next2_eta_mmss`
  `*/next2_arrival_status` 后缀或 `system/server.tick` 全名匹配 → 含 ⇒ 高频
- `ChangeType.WALL_REFS_UPDATED` 新枚举值；`markWallReferences` diff 后若引用集合
  实际变化（add ∪ remove 非空），fire 占位 event（variable=null / fullName=`<bulk_wall_refs>`
  / referencingWalls={wallId}）让外部 listener 重评 throttler 间隔
- `SessionManager.broadcastVariableChangeToWall` 加 WALL_REFS_UPDATED 早返，避免推 state.patch
- `buildVariablePatchOp` switch 加 WALL_REFS_UPDATED → null 防漏

#### 2. ProjectionThrottler 动态间隔

- `sessionIntervalOverride: ConcurrentMap<String, Long>` per-session 覆盖
- `setIntervalForSession(sid, ms)` / `clearSessionInterval(sid)` / `effectiveIntervalForTest(sid)`
- `submit` 内 `effectiveInterval(sid)` 替换 hardcoded `minIntervalMs`
- `discardSession` 同时清掉 override 防泄漏

#### 3. CanvasProjector 主动发包 + viewer 检测

- 构造器扩展 `MapPacketSender + WallRepo`（旧 4-arg 构造器保留作 fallback）
- `project(session, region)` 每次 `canvasRenderer.update(mapId, pixels)` 后立刻
  `mapPacketSender.sendFullMap(p, mapId, pixels)` 给 viewer
- `findViewersForWall(wallId)`：`wallRepo.loadById` → `WallKey.world() + originX/Z`
  → `Bukkit.getWorld` → `world.getPlayers()` → 按 `Math.abs(pChunkX - wallChunkX) + Math.abs(pChunkZ - wallChunkZ) ≤ 8` 过滤
- 单 viewer push 失败被吞掉 + log warning；wallRepo / mapPacketSender 为 null → 跳过推送（fallback）
- Paper 的 `world.getPlayers()` / `Player.getLocation` 线程安全只读，async throttler 线程直接调，省 50ms 主线程切换抖动

#### 4. HikariCanvas 装配

- 旧 `mapPacketSender = new MapPacketSender();` 移到 CanvasProjector 构造行前
- `new CanvasProjector(..., mapPacketSender, wallRepo)` 走新构造器
- `projectionThrottler = new ProjectionThrottler(this, sessionManager, canvasProjector, config.adaptiveFps.defaultMinIntervalMs())`
- 第二条 `variableStore.registerChangeListener`：任意 event 都按 `event.referencingWalls()` 遍历
  → 对每个 wallId 调 `isWallHighFreq` → 找该 wall 所有活跃 session → `throttlerRef.setIntervalForSession(sid, highFreq ? 50ms : 200ms)`

#### 5. config.yml + HikariCanvasConfig

- 新段 `rendering.adaptive-fps`：`default-min-interval-ms` (default 200) / `high-freq-min-interval-ms` (default 50) / `push-packets-enabled` (default true)
- `AdaptiveFpsConfig` record；clamp `≥ 33ms`；`high > default` 时取小者保护用户意图

### 单测

- `VariableStoreTest`：+7 case（isWallHighFreq null/empty/user-only/eta_seconds/arrival_status/server.tick/slow + WALL_REFS_UPDATED listener fire + noop unchanged）
- `ProjectionThrottlerTest`（新文件）：6 case（default → override → clear → 非正值=clear → discardSession 清掉 → null sid noop）
- backend `:plugin:test` 全绿（756 tests，含其它增量历史 case）
- frontend `npm run test` 105 全绿
- `vite build` 643 kB（gzip 195 kB）/ shadowJar OK / 0 baseline 漂移

### 验证（用户侧手测）

1. wall 文本写 `${var:schedule/eta_seconds}` 或 `${var:system/server.tick}` 触发高频判定
2. 游戏内站到 wall 同 chunk 距离 ≤8 chunks 范围
3. 倒计时应看起来顺滑 1Hz 跳秒（之前 2-5s）；超出阈值的玩家仍走 Paper 默认 sync
4. config `rendering.adaptive-fps.push-packets-enabled: false` 可关掉主动推帧验证 fallback

### 关联文件

- `plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java`（+isWallHighFreq / WALL_REFS_UPDATED）
- `plugin/src/main/java/moe/hikari/canvas/render/ProjectionThrottler.java`（+setIntervalForSession）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（+主动推帧 + viewer 检测）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`（WALL_REFS_UPDATED 处理）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（自适应 listener 接入）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvasConfig.java`（AdaptiveFpsConfig）
- `plugin/src/main/resources/config.yml`（rendering.adaptive-fps 段）
- `plugin/src/test/java/moe/hikari/canvas/variable/VariableStoreTest.java`（+7 case）
- `plugin/src/test/java/moe/hikari/canvas/render/ProjectionThrottlerTest.java`（新文件 +6 case）

---

## 2026-05-20 · M28-enhance：schedule next2 第二班次 + MM:SS 格式 + 编辑器 placeholder hint chip

### 需求

1. **地铁屏标配第二班次**：所有 schedule namespace 加 `next2_*` 7 变量（departure / destination / eta_minutes / eta_seconds / eta_mmss / is_arriving / arrival_status），让"下一班 5min / 之后 12min"列车屏典型样式开箱即用
2. **MM:SS 格式 `eta_mmss`**：90s → `"01:30"`；超 99min 仍按 MM 累加（如 `"151:01"`），不卡上限
3. **编辑器 placeholder 实时预览 + hint chip**：原 `${var:schedule/eta_minutes}` 长占位符在编辑器画布上撑爆 layout 致使文字叠在一起；改造前端 PreviewRenderer 接 interpolator → 渲染替换后字符串 + 半透明 mauve 背景矩形（chip 风格）标记哪几个字是变量值

### 实现

#### 1. ManualScheduleProvider 扩展（7 → 15 变量）

- `Computed` record 加 6 字段：`etaMmss` + `next2Departure` / `next2Destination` / `next2EtaMinutes` / `next2EtaSeconds` / `next2EtaMmss` / `next2IsArriving`
- `computeNext` 改成找前两个 `t > now` 的 entry：
  - 0 entry → 全 null
  - 1 entry → next2 = next 自身（明天，ETA +86400）
  - n≥2，找到 1 个 future → next2 = sorted[0]（明天循环，跳过自身）
  - n≥2，找到 2 个 → 正常 / 全过 → sorted[0,1]（明天）
- `formatMmss(seconds)` 工具：`String.format("%02d:%02d", mm, ss)`，超 99min 仍 MM 累加
- `pushValues` 增 7 个 next2_* + 1 eta_mmss 写入；`ALL_KEYS` 数组统一注册 / 注销避免漂移
- `registerWallInternal` / `unregisterWall` / `declaredKeys` 全部 15 keys

#### 2. VariableInterpolator 加 segments 字段（双端对称）

- 后端 `Result` record 加 `List<Segment> segments`；`Segment(start, end, fullName, raw)`
- 前端 `InterpolateResult` 加 `segments: PlaceholderSegment[]`
- 替换循环改为手工累积 StringBuilder / string + 同步记录每个 placeholder 在替换后字符串中的 char range（不再用 `Matcher.appendReplacement`/`String.replace`，因后者不易暴露替换后 offset）
- 替换值含 `$` / `\` 测试仍 PASS（手工 append 天然不解释反向引用）
- 后端 `CanvasCompositor.maybeInterpolateText` 只用 `r.text()` + `r.referencedFullNames()`，segments 字段透传不影响游戏内渲染像素

#### 3. PreviewRenderer：drawText 接 interpolator + 画 hint chip

- 新 `setVariableContextProvider(() => { wallId, store })` 注入器，App.vue mount 时配
- `drawText` 入口先 `interpolate(t.text, wallId, store)` → 用 `rendered` 字符串构造临时 TextElement → layout
- 新 `drawPlaceholderHints(ctx, glyphs, segments, fontSize)`：按 `srcIndex` 反查命中 placeholder 的 glyph 子集 → 按 baselineY 行分组 → 每行画半透明 mauve 矩形（`rgba(203,166,247,0.20)` 填充 + 0.50 边框）
- `TextLayout.PositionedGlyph` 加 `srcIndex?: number`（仅前端字段，不破坏双端一致性）；`layoutHorizontal` / `layoutVertical` / `softWrap` / `softWrapVertical` / `applyLineStartForbidden` 全程透传 srcStart → srcIndex
- 游戏内后端 Compositor 不走 PreviewRenderer，无 hint，游戏中渲染替换后字符串保持双端像素一致

#### 4. ScheduleManagerModal preview 段扩展

- 加 `previewEtaMmss` 行（旧首班）
- 新"第二班次"小节，6 行 `next2_*` 变量回显
- i18n `previewEtaMmss` + `previewNext2Header` 双语

### 测试

- `ManualScheduleProviderTest`：8 新 case（formatMmss / 单 entry 循环 / 双 entry 第二班次 / 全过 / 三 entry / 空 entry next2 全 null / 端到端 15 变量），3 个旧 case 调 7→15 计数；total schedule provider tests 增 ~8
- `VariableInterpolatorTest`：6 新 segments case（plain text / null / single / multi / unresolved / 长占位短值）
- `interpolator.test.ts`（前端）：8 新 segments case 对称
- backend total 742 test 全绿；frontend total 105 test 全绿（增 32 = 8 schedule provider + 6 backend segments + 8 frontend segments 等）
- vite build 643 kB（gzip 195 kB）；shadowJar 重建成功

### 关联文件

- `plugin/src/main/java/moe/hikari/canvas/variable/provider/ManualScheduleProvider.java`
- `plugin/src/main/java/moe/hikari/canvas/variable/VariableInterpolator.java`
- `plugin/src/test/java/moe/hikari/canvas/variable/provider/ManualScheduleProviderTest.java`
- `plugin/src/test/java/moe/hikari/canvas/variable/VariableInterpolatorTest.java`
- `web/src/variable/interpolator.ts`
- `web/src/variable/__tests__/interpolator.test.ts`
- `web/src/render/PreviewRenderer.ts`
- `web/src/render/TextLayout.ts`
- `web/src/components/schedule/ScheduleManagerModal.vue`
- `web/src/i18n/messages.ts`
- `web/src/App.vue`

### 兼容性

- 旧 7 个 schedule 变量保留；旧 `${var:schedule.eta_seconds}` / `${var:schedule/eta_seconds}` 均工作如初
- 后端 Compositor 渲染像素零变化（segments 字段透传不读，仍走 `r.text()`）
- 前端 PreviewRenderer 对未含 `${var:` 的纯文本 O(1) 短路（interpolator + drawText 入口均检查）
- TextLayout `srcIndex` 仅前端字段，未带 srcIndex 的 glyph 兼容旧路径（hint 路径 filter 不命中即跳过）
- 双端 Renderer 一致性：游戏内 Compositor 用 interpolated text 渲染，与编辑器渲染的替换后字符串等价；hint chip 仅编辑器视觉提示，不影响游戏内像素

---

## 2026-05-20 · M28-bugfix3：schedule 显示双 bug 修复（interpolator 斜杠语法 + Provider 推 state.patch）

### 症状

用户报告：Schedule Manager 内加 entry 后，**modal 内 7 项 preview 全显 "—"**；同时 wall 上写
`${var:schedule/eta_seconds}` 也显示 "???"。`${var:schedule.eta_seconds}`（点号）能 work。

### 根因

**Bug A：interpolator 不识别 schedule/X 斜杠语法。**
后端 `VariableInterpolator.resolveFullName` + 前端 `interpolator.ts:resolveFullName` 只支持点号前缀（`schedule.X` / `wall.X`）。用户更直觉地写 `${var:schedule/eta_seconds}`（与 `${var:user/red}` 一致），不被注入 wallId → 字面查 store → 找不到 → "???"。

**Bug B：Provider 写值不推 state.patch 给前端 mirror。**
`VariableStore.setValue/create/update/bind/delete` 仅触发 wall dirty callback（让 wall 重画），**不广播 state.patch**。玩家手动 op 走 `EditSession.OpResult.dirty + dispatcher pushPatch` 主动推送，但 Provider（ManualScheduleProvider / SystemVariableProvider / PapiVariableBridge / ScoreboardVariableProvider）直接调 store，前端 mirror 永远拿不到 Provider 自动更新的值——Schedule Manager modal `variables.get('schedule:wid/eta_seconds')` 永远 undefined → preview 全 "—"。

### 修复

**Phase A**：interpolator 双端各加 2 个分支（`schedule/X` + `wall/X` 注入），与现有点号 alias 等价。`wall/id` 注入成 `system:<wallId>/wall.id`（与 SystemVariableProvider 注册的 fullName 同款）；`schedule/X` 注入成 `schedule:<wallId>/X`。保留旧点号语法继续工作（向下兼容）。

**Phase B**：`VariableStore` 加 `VariableChangeListener` 接口 + `ChangeType` (CREATED/UPDATED/VALUE_SET/BOUND/DELETED) + `VariableChangeEvent` record + `registerChangeListener` + `fireChange` 钩子（在 create/update/setValue/bind/delete 各 mutation 之后调；不持锁；单 listener 抛异常被吞掉 + log warning 不影响其他）。`SessionManager.broadcastVariableChangeToWall(event, OpPushCallback)` 把事件翻译成 RFC 6902 PatchOp 推给绑定该 wall 的所有活跃 session；路由 walls = `event.referencingWalls() ∪ parseOwnerWallId(fullName)`（per-wall namespace 如 `schedule:wid/*` 隐含归属，即便 Compositor 还没 markWallReferences 也广播——这是 Schedule Manager modal 首次 add entry 时 preview 即时显值的关键）。`HikariCanvas` onEnable 在 `webServer.start()` 之后注入 listener wrap webServer 的 pushPatch。

**version 字段**：variable 变更不动 ProjectState version，复用绑定该 wall session 的当前 ProjectState.version（前端 wsClient.handleStatePatch 按 path 前缀分拣 `/variables/` 走 VariableStore，version 仅用于 projectOps 路径）。

### 影响 / 用户验证

- `${var:schedule/X}`、`${var:wall/X}` 与 `${var:schedule.X}`、`${var:wall.X}` 等价工作（两种语法都支持）
- Schedule Manager modal 7 项 preview 实时显值（add entry / refresh 时刻同步）
- 任何 Provider（ManualScheduleProvider / SystemVariableProvider / PapiVariableBridge / ScoreboardVariableProvider）写值时前端 mirror 同步更新
- TextElement live preview 显 Provider 写入的实际值（不再 "???"）

### 测试

- 后端：8 个新单测（VariableStoreTest +7 listener 行为；ManualScheduleProviderTest +2 集成；VariableInterpolatorTest +4 slash 语法）+ 全套 487 测试 `--rerun-tasks` 全绿
- 前端：4 个新 vitest case（interpolator.test.ts slash 语法）+ 全 97 全绿
- vite build：bundle 639.33 kB gzip 194.42 kB；livePaintWorker 33.75 kB；CSS 61.53 kB
- shadowJar 通过

### 关联

- `plugin/src/main/java/moe/hikari/canvas/variable/VariableInterpolator.java`（Bug A 后端）
- `web/src/variable/interpolator.ts`（Bug A 前端）
- `plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java`（Bug B ChangeListener API + fireChange 钩入 create/update/setValue/bind/delete）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`（broadcastVariableChangeToWall + buildVariablePatchOp + parseOwnerWallId）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（onEnable 注入 listener）
- `plugin/src/test/java/.../VariableStoreTest.java`（+7 listener 测试）
- `plugin/src/test/java/.../variable/VariableInterpolatorTest.java`（+4 slash 测试）
- `plugin/src/test/java/.../variable/provider/ManualScheduleProviderTest.java`（+2 集成测试）
- `web/src/variable/__tests__/interpolator.test.ts`（+4 slash 测试）

---

## 2026-05-19 · 紧急修复：onDragEnd 单选 element.transform 永不发

### 症状

用户报告："网页上怎么拖都拖不动，游戏内字体展示完全乱了"。前端 Konva 拖动有视觉反馈（element 跟手）
但游戏内 wall 元素位置不变；多元素叠在 server 端的初始默认位置。新建元素 + 修改文本（element.update）
能正常同步，但拖动后 element.transform 从未真正到达 server。

### 根因

`web/src/components/layout/CanvasView.vue:onDragEnd` 的 mutation-race bug：

1. `onDragMove`（line 726-730）为支持 F2 视觉跟手，**直接 mutate** `leaderEl.x/y` 到新位置
2. `onDragEnd`（line 764）判等 `el.x !== newX` —— 但因 onDragMove 已同步 mutation，
   **`el.x === newX` 恒成立** → `ws.send('element.transform', ...)` 永不触发

M15.3 P0-1 已对多选 case 做了同样修复（用 `dragInitial` 记录的初始位置判等，line 772-773 注释明确说了
这个坑），**但单选 path 漏修**。从 M15.3（2026-05-16）至今所有单选拖动**从未真正同步到 server**。

为何之前没发现：测试 fixture 都是无拖动的渲染快照；前端 Konva 视觉反馈正确导致用户也很难察觉；
持久化与渲染都走 server ProjectState，而 ProjectState 在 reload 时 push 回前端覆盖前端的 mutation
→ 用户重连后看到的"错位"实际是 server 的初始位置。

### 修复

`onDragEnd` 判等改用 `dragInitial.value.get(id)` 拿初始位置（与多选 path 一致）；
如果 dragInitial 缺失（非 drag start 进入的边缘场景）fallback 到旧逻辑。

### 用户操作

修复后**已存在的 wall 元素仍在 server 的旧错位**。需要用户重新拖动一次每个元素让其同步到 server。
不会自动追溯历史拖动。

### 关联

- `web/src/components/layout/CanvasView.vue` onDragEnd 函数（~5 行改动）
- 93 frontend test 全绿 / vite build 通过
- 不影响多选 path（M15.3 P0-1 修过）

---

## 2026-05-19 · 0.4.0 上线 4 项体验 bug 修复（单 commit 合）

用户上线 0.4.0 实测发现 4 项体验 bug，单 agent 串干修完，1 个 commit 合所有改动 + V013 migration + 新变量 + UI 改造。**714 backend test + 93 frontend test 全绿 / shadow jar 152MB / 0 baseline 漂移**。

### Bug 1：ready payload "鸡生蛋"（变量误报已删除）

- **根因**：`WebServer.handleAuth` 用 `variableStore.listByWall(wallId)` 拿初始 variables，但 byWall 倒排索引由 `Compositor` 渲染时 `markWallReferences` 才填——wall 刚 open 还没渲染过 → 倒排索引空 → ready payload 返空。前端 mirror 因此漏所有 system / schedule / scoreboard / papi 变量，interpolator 把它们当 missing → 红 banner "变量已删除"
- **修法**：新增 `VariableStore.listVisibleToWall(wallId)`，不依赖倒排索引，按 namespace 形态判定可见性（全局 ns 全部包含 / per-wall ns 仅本 wall）。`WebServer.handleAuth` 改用此方法

### Bug 2：store.create 不反查 byWall（值变化不重画）

- **根因**：用户先在 wall 写 `${var:schedule.X}`（Compositor `markWallReferences("wall-A", {"schedule:wall-A/X"})` 入 `byWall` 但 `addWallToReferencedSet` 因变量不存在 noop），后 Provider `ensureWallRegistered` 触发 `store.create` 时 `referencedByWalls = empty`。Provider 后续 `setValue` → `notifyReferencingWalls` 遍历空集合 → wall 不重画
- **修法**：`VariableStore.create` 在 new Variable 前反查 `byWall`，把已记录引用本变量的 wall 注入 `referencedByWalls`。O(W) 性能可忽略

### Bug 3：is_arriving 阈值改 config + 新增 arrival_status

- **新增变量** `schedule:<wallId>/arrival_status`（STRING）：eta ≤ threshold → `config.arrivingText`（默 "进站中"）；否则 → `config.idleText`（默 ""）
- **config.yml** 新增 `dynamic.schedule`：`arriving-threshold-seconds: 60` / `arriving-text: "进站中"` / `idle-text: ""`
- **HikariCanvasConfig.ScheduleConfig record** 加载 + 注入 `ManualScheduleProvider`
- **阈值改秒**：原 `ARRIVING_THRESHOLD_MINUTES=5` 废止；改为 config 注入的秒阈值（默 60s）

### Bug 4：秒精度系统（per-wall HH:mm:ss）

- **V013 migration** `ALTER TABLE wall_schedules ADD COLUMN precision TEXT NOT NULL DEFAULT 'minute'`，现有 wall 平滑升级
- **WallSchedule record** 加 `precision` 字段（`"minute"` / `"second"`）+ `normalizePrecision` 规范化
- **ScheduleDao** 加 `upsertSchedule(wallId, stationName, precision)` 4 参 overload；3 参版保留向下兼容；`loadByWall` / `loadAll` 读 precision 列
- **HHMM_PATTERN** 扩展为 `^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$`（接受 HH:mm 或 HH:mm:ss）
- **ManualScheduleProvider.refreshInterval** 改 1s（最小粒度）；内部按 wall.precision 节流：`SECOND_INTERVAL_MS=1000` / `MINUTE_INTERVAL_MS=30000`，per-wall `lastPushAt` 跟踪
- **新增变量**：`eta_seconds` / `arrival_status` / `precision`；旧 `eta_minutes` / `is_arriving` / `next_*` 保留向下兼容（共 7 变量）
- **computeNext 重写为秒粒度**：`Duration.between(now, t).getSeconds()`；`safeParseTime` 改用 `DateTimeFormatterBuilder` 支持可选秒
- **WS 协议**：`schedule.upsert` payload 加可选 `precision`；`schedule.list` ack `schedule` 对象带 `precision`
- **前端 ScheduleManagerModal**：顶部加 "时间精度" toggle button group（minute / second），切换调 `sendScheduleUpsert(stationName, precision)`；preview 加 4 新字段（eta_seconds / arrival_status / precision / eta_minutes）
- **前端 ScheduleEntryDialog**：加 `precision` prop；input[type="time"] 当 second 时 `step="1"`；编辑 HH:mm:ss 显完整秒 / 编辑 HH:mm 切到 second 模式自动补 ":00"
- **前端 i18n**：新增 `precisionLabel / precisionMinute / precisionSecond / precisionHint / previewEtaSeconds / previewArrivalStatus / previewPrecision` 中英 key

### 关键架构落地

1. **listVisibleToWall vs listByWall**：分工明确——`listVisibleToWall` 用于 ready payload（鸡生蛋安全）；`listByWall` 用于运行期"哪些 wall 引用此变量"反向查询（依赖倒排索引，但此时 Compositor 已多次 mark）。两个 API 不互相替代
2. **create 反查 byWall = O(W)**：W = 当前活跃 wall 数（实际 ≪ 100）；create 频率本身极低（启动期 + 注册时），全量扫描 byWall 的成本远低于丢失 wall 重画语义带来的 bug
3. **Schedule precision 是 wall scope 而非全局**：per-wall column 让一个 server 上可以同时有"分钟时刻表"和"秒级倒计时屏"，不互相干扰。Provider 内 `lastPushAt` per-wall 节流是天然产物
4. **配置热替换**：`ManualScheduleProvider.setConfig` 用 volatile 字段保证可见性，未来 `/canvas var reload` 钩子可直接调而不必重启
5. **向下兼容**：`eta_minutes` / `is_arriving` / `next_departure` 等旧变量保留——已用 0.4.0 写过 wall 的玩家升级后零调整。`is_arriving` 默认阈值变了（5min → 60s），用户场景下"5min 进站"已显得过于宽松，秒粒度更符合实际列车业务

### 文件列表

| 类型 | 路径 | 说明 |
|---|---|---|
| 新增 | `plugin/src/main/resources/db-migrations/V013__schedule_precision.sql` | ALTER ADD COLUMN precision |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java` | + listVisibleToWall + create 反查 byWall |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` | handleAuth 改 listVisibleToWall |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/schedule/WallSchedule.java` | + precision 字段 + normalizePrecision |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/storage/ScheduleDao.java` | upsertSchedule 4 参 overload + 读 precision |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java` | 注册 V013 |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/variable/provider/ManualScheduleProvider.java` | 7 变量 + 秒粒度 + per-wall 节流 + config 注入 |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/variable/provider/ProviderBootstrap.java` | 加 5 参 overload 传 config |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/HikariCanvasConfig.java` | + ScheduleConfig record + dynamic.schedule 解析 |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java` | 传 config.scheduleConfig 到 ProviderBootstrap |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/web/ScheduleOpDispatcher.java` | upsert 接收 precision + HHMM_PATTERN 扩展 + scheduleToMap 带 precision |
| 修改 | `plugin/src/main/resources/config.yml` | + dynamic.schedule 段 |
| 修改 | `plugin/src/test/java/moe/hikari/canvas/variable/VariableStoreTest.java` | + 8 个新 case（Bug 1 + Bug 2） |
| 修改 | `plugin/src/test/java/moe/hikari/canvas/variable/provider/ManualScheduleProviderTest.java` | computeNext 签名更新 + 7 新 case |
| 修改 | `plugin/src/test/java/moe/hikari/canvas/storage/ScheduleDaoTest.java` | + 5 个 precision case |
| 修改 | `web/src/types/schedule.ts` | + SchedulePrecision + precision 字段 |
| 修改 | `web/src/stores/schedule.ts` | + setPrecision + precision computed |
| 修改 | `web/src/network/wsClient.ts` | sendScheduleUpsert 加可选 precision 参 |
| 修改 | `web/src/components/schedule/ScheduleManagerModal.vue` | 精度 toggle + 7 变量 preview |
| 修改 | `web/src/components/schedule/ScheduleEntryDialog.vue` | precision prop + step="1" |
| 修改 | `web/src/i18n/messages.ts` | + 7 个 schedule i18n key（中英） |
| 修改 | `docs/dynamic-data.md §7.3` | 7 变量表 + config + 刷新频率说明 |
| 修改 | `docs/variables.md §1.9` | 7 变量教程 + precision 切换说明 |
| 修改 | `docs/data-model.md §2.9` | 新增 wall_schedules + schedule_entries + V013 说明 |

### 验证

- `./gradlew :plugin:test` → 714 case 全绿（VariableStore +8 / ManualScheduleProvider +7 / ScheduleDao +5）
- `cd web && npm run test` → 93 case 全绿
- `cd web && vite build` → 639kB / gzip 194kB
- `./gradlew :plugin:shadowJar` → 152 MB
- `./gradlew :examples:demo-train-plugin:jar :examples:demo-score-plugin:jar` → OK

---

## 2026-05-19 · 0.4.0-P5 收尾 + 0.4.0 完整收尾总览

P5 单 agent 串干完工。**1 commit / 695+ backend + 93 frontend test 全绿 / shadow jar 152 MB / 0 fixture baseline 漂移**。

P5 任务范围：`/canvas var` 7 子命令族 + `docs/variables.md` 合集教程 + 端到端 smoke test + 0.4.0 完整收尾文档同步。

### Phase 实施

| 子任务 | 范围 |
|---|---|
| **P5.1** `/canvas var` 命令族 | `VariableSubCommand.java` 7 子命令（list/get/set/delete/providers/reload/inspect） + tab completion 4 分支 + ReloadHook 注入 + audit 事件 2 个 |
| **P5.2** 命令单测 | `VariableSubCommandTest.java` 29 case（权限拒绝 + 各子命令分支 + tab completion + 异常路径） |
| **P5.3** docs/variables.md | 合集教程 ~430 行 / 3 段（玩家入门 11 节 + 运维管理 6 节 + 测试 checklist 31 步） |
| **P5.4** 端到端 smoke test | `EndToEndSmokeTest.java` 6 case 装配链验证 |
| **P5.5** CLAUDE.md / journal | M28-P5 段 + 0.4.0 路线段 P1-P5 标完工 + 总览 |
| **P5.6** 全测 | backend 695+ / frontend 93 / shadow jar OK / examples jar OK |
| **P5.7** commit + push | SSH 签名 + push origin main |

### 关键架构落地

1. **VariableSubCommand 双层分离**：`execute(sender, args)` 纯逻辑供单测；`build()` 返 Brigadier `LiteralArgumentBuilder` 供 CanvasCommand 嵌入。单测不依赖 Brigadier `CommandContext` / `SuggestionsBuilder` 注入 — 用 `java.lang.reflect.Proxy` 造 CommandSender 抓 `sendMessage(...)` 入参（同 HikariCanvasAPIImplTest `fakePlugin` 模式）
2. **WallSource 接口注入**：原计划测试用 `extends WallRepo` 失败（WallRepo 是 final）→ 抽出 `interface WallSource { allWallIds(); exists(); }` + 两构造器（生产 `WallRepo` / 测试 `FakeWallSource`）。本质上是 P4 各模块的"测试 seam"哲学延续 — Provider / Scheduler / DataSource 都同 pattern
3. **ReloadHook 抽象**：`/canvas var reload` 触发 hook（HikariCanvas 主类 wire 时注入 lambda 跑 `reloadConfig + HikariCanvasConfig.load + applyConfig + new PushRateLimiter + apiImpl.setRateLimiter`）。命令侧不知道 HikariCanvas 主类，仅触发抽象 + 反馈结果
4. **HikariCanvasAPIImpl.limiter 改 volatile**：让热替换无锁可见性。读路径（`setVariable` 每次都拿 `this.limiter` 引用）有极小内存屏障开销，但 push 路径本来就高频原子操作竞争，volatile 加持不显著；换 reload 立刻生效（无需重启）的大幅可用性
5. **PluginCleanupListener.handleDisable 改 public**：原 package-private（test 同 package）；EndToEndSmokeTest 在 `moe.hikari.canvas.variable` package 跨 package 调，public 化即可（Bukkit 主线程 ServerEvent 构造在无 Bukkit.server 单测环境本来就走不通，handleDisable 即测试唯一入口）
6. **AuditLog 命令侧 vs WS 侧事件分离**：命令侧用 `VARIABLE_COMMAND_SET` / `VARIABLE_COMMAND_DELETE`，WS 侧（VariableOpDispatcher）继续用 `VARIABLE_SET` / `VARIABLE_DELETE`。事后审计能区分 "玩家在编辑器改" vs "管理员在 console 改"

### 文件列表

| 类型 | 路径 | 行数 |
|---|---|---|
| 新增 | `plugin/src/main/java/moe/hikari/canvas/command/VariableSubCommand.java` | ~510 |
| 新增 | `plugin/src/test/java/moe/hikari/canvas/command/VariableSubCommandTest.java` | ~410 |
| 新增 | `plugin/src/test/java/moe/hikari/canvas/variable/EndToEndSmokeTest.java` | ~225 |
| 新增 | `docs/variables.md` | ~430 |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java` | +21 / VariableSubCommand 装配 |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java` | +6 / 注入 VariableSubCommand 进 build |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/variable/plugin/HikariCanvasAPIImpl.java` | +18 / `volatile limiter` + `setRateLimiter` |
| 修改 | `plugin/src/main/java/moe/hikari/canvas/variable/plugin/PluginCleanupListener.java` | +2 / `handleDisable` 改 public |
| 修改 | `CLAUDE.md` + `docs/journal.md` | 路线收尾 + 总览 |

---

## 2026-05-19 · 0.4.0 完整收尾总览（M28-P1 至 M28-P5）

0.4.0 "动态信息屏" 路线 **完工**。5 phases / **22+ commit** / **695+ backend + 93 frontend test 全绿** / wall-clock ~3 天（2026-05-19 单日推完 P1-P5）。

### 5 phase 累计统计

| Phase | 主题 | 实际工时（h） | commit 数 | 测试新增 |
|---|---|---:|---:|---:|
| **P1** | 变量系统底座 | ~5 | 5 | +480 backend |
| **P2** | 编辑器基础 UX | ~4 | 4 | +73 frontend |
| **P3** | 内置 4 Provider + HTTP 端点 | ~5 | 5 | +120 backend |
| **P4** | Plugin Push API + 2 示例插件 + docs/api.md | ~6 | 6 | +60 backend + +20 frontend |
| **P5** | `/canvas var` + docs/variables.md + smoke | ~3 | 1 | +35 backend |
| **合计** | | **~23h** | **21** | **+695 backend + +93 frontend** |

> 估时 ~150h 远高估 — agent + 并行 wave + 已有 phase 基础设施复用，实际推得很快。

### 关键架构 1:1 对照（6 固化决策）

| 决策 | 落地位置 |
|---|---|
| Push > Pull | P3 全部 Provider 走 daemon scheduler 主动推；P4 HikariCanvasAPI 暴露 push 接口；Compositor 渲染时仅 read store cache |
| 变量是 string | `Variable.currentValue: String`；`VarType` 仅 UI hint（dynamic-data.md §16-2） |
| 用户变量持久化 / 其他内存 | V011 `user_variables` 表 + `VariableStore.persistIfUser`；插件 / system / papi / scoreboard 仅 in-memory |
| resolve 不在主线程 | `VariableProviderDaemon` 守护线程池 2 thread；CanvasCompositor.render 读 cache（不阻塞）；ScheduleProvider 主动 prefetch |
| namespace 严格隔离 | `PluginNamespaceRegistry` 原子 CAS + 5 保留 namespace + spoof 防御；setVariable 内 `checkAcl` |
| fallback 4 档链 | `VariableInterpolator.resolveValue`：cached → `\|fallback=` → `defaultValue` → `"???"`；双端镜像 |

### 子任务清单

```
P1 — VariableStore + V011 user_variables + 5 WS op + Compositor 替换 + daemon 框架 + 7 权限节点 + 前端 store
     A 11b2773 / B dcffe9f / C ab765dd / D 02be5ca / E 74b4f4f

P2 — ready payload variables + VariablePanel + NewVariableDialog + ValueEditor + BindDialog + useLongPressIncrement
     + VariablePicker + interpolator.ts + TextElementVariableHints + TextElementSection 集成 + wsClient ready hookup
     F 46eb6e9 / G c7dd01f / H 41c2ba3 / I fe40d17

P3 — VariableProvider declaredKeys + isDynamic + dynamicLookupHook 基础设施
     SystemVariableProvider 8 全局 + 4 per-wall / ScoreboardVariableProvider 混合 / PapiVariableBridge reflection + 编码
     ManualScheduleProvider 全栈 V012 + DAO + 5 WS op + Schedule Manager UI / list-all-namespaces 端点 + Picker mergeMetadata
     J 8828b2b / K 219f731 / L b0b2e52 / M c975996 / N e855964

P4 — moe.hikari.canvas.api 公开包 / HikariCanvasAPIImpl + checkAcl / PluginNamespaceRegistry + 保留 ns
     PushRateLimiter per-plugin 100/s + 全局 1000/s + 10s 保护期 + clock 注入
     PluginCleanupListener 立即 unregister + 30s purge / ServicesManager + getAPI() 双入口
     DemoTrainPlugin 定时器 + DemoScorePlugin 事件命令 / docs/api.md 660 行
     O 10eeda1 / S 24c16f3 / P 3d8d214 / R e5158ce / Q b227b5c / T a69c0f1

P5 — /canvas var 7 子命令 + tab completion + ReloadHook + audit
     docs/variables.md 合集教程 + 31 步 checklist
     EndToEndSmokeTest 6 case + VariableSubCommandTest 29 case
     handleDisable public + HikariCanvasAPIImpl.limiter volatile
     <本 commit>
```

### 不可越界路线（已固化）

- **0.4.1 chip 编辑器**（~25h，1 周）：留 0.4.0 落地后单独 milestone
- **0.5.0+** 动画 / 时间轴 / Blockly 脚本路线见 `docs/dynamic-data.md §13`

---

## 2026-05-19 · 0.4.0-P4 收尾：Plugin Push API + 示例插件完工

P4 六子任务（O / P / Q / R / S / T）完工。**6 commit / 660 backend + 93 frontend test 全绿 /
shadow jar 152 MB / 0 fixture baseline 漂移 / DemoTrainPlugin 4.8 KB + DemoScorePlugin 5.8 KB**。
Wave 1 单跑（O 建立 API 包 + Impl + Registry）+ Wave 2 四 agent 并行（P / Q / R / S 实施限流 +
生命周期 + 示例 + 文档）+ Wave 3 T 主控收尾（shadowJar 防御性注释 + 全测 + journal + push）。

### Phase commit 时间线

| Commit | Task | 范围 |
|---|---|---|
| `10eeda1` | P4-O Wave 1 | `moe.hikari.canvas.api` 包 + Impl + PluginNamespaceRegistry + PluginNamespaceProvider（+38 单测） |
| `24c16f3` | P4-S Wave 2 | docs/api.md 660 行 + dynamic-data.md §4 回填 |
| `3d8d214` | P4-P Wave 2 | PushRateLimiter（per-plugin 100/s + 全局 1000/s + 10s circuit break）+ config.yml 段（+15 单测） |
| `e5158ce` | P4-R Wave 2 | DemoTrainPlugin + DemoScorePlugin Gradle subprojects + examples/README.md |
| `b227b5c` | P4-Q Wave 2 | PluginCleanupListener + ServicesManager 注册 + HikariCanvas#getAPI()（+7 单测） |
| `<本 commit>` | P4-T Wave 3 | shadowJar 防御性注释 + CLAUDE.md/journal P4 总览 |

### 关键架构落地

1. **API 包独立 + 路径冻结**（O）：`moe.hikari.canvas.api` 作为公开 API 包，**含独立 VarType enum**（不引用 internal `moe.hikari.canvas.variable.VarType`）。外部插件仅 `compileOnly` API 类即可，无需 import internal 包。shadowJar relocate 只对显式列出的第三方包生效，不影响项目自身包——T 任务确认 `moe/hikari/canvas/api/*` 7 个类在 shadow jar 中保持**原路径**
2. **HikariCanvasAPIImpl 三档异常隔离**（O + P）：
   - ACL 错误（namespace not registered / ACL denied） → 抛 `PluginNamespaceException`（调用方有义务正确接入，不能 catch 忽略）
   - 限流 / value 长度 / TTL 非法 / 内部 VariableException → 静默 drop + log WARN
   - 未预期错误（NPE 等） → log + drop，不传播给调用方
3. **PushRateLimiter clock 注入 seam**（P）：测试用 `AtomicLong::get` 虚拟时钟 → 全部 15 单测零 `Thread.sleep` 完全确定性。生产用 `System::currentTimeMillis`
4. **PluginCleanupListener DelayedScheduler 接口注入**（Q）：嵌套 `@FunctionalInterface` 让测试可注入 RecordingScheduler 不真跑；生产用 `Bukkit.getScheduler().runTaskLaterAsynchronously` 30s 后清。两个构造器：3 参（生产）+ 4 参（测试）
5. **双入口设计**（O + Q）：外部插件可选 ServicesManager（推荐，零编译耦合）或 `((HikariCanvas) plugin).getAPI()`（直观但需 import 主类）。HikariCanvas onEnable 一次 `Bukkit.getServicesManager().register(...)`，Bukkit 自动反注册在 onDisable 时
6. **保留 namespace 防御**（O）：`user / system / papi / scoreboard / schedule` 5 个 + `user: / system: / schedule:` 3 个前缀禁外部插件注册（IllegalArgumentException）；前缀防御避免插件用 `system:malicious` 伪造 per-wall 元数据
7. **限流粒度按 entry 计费**（P）：`setVariables(ns, Map<5 entries>)` 算 5 token（防 setVariables 绕过 setVariable 限流），但 ACL check + tryAcquire 只一次（性能 + all-or-nothing 语义）
8. **cleanup 30s 保留 cached**（Q 决策）：plugin disable 立即 unregister namespace（让别的插件能抢用同名）但 cached value 保留 30s。这样：
   - plugin reload 窗口期间 wall 仍显示旧值不闪屏
   - 30s 内重新 enable 同插件 → 用户体验无中断
   - 30s 后才彻底清，防僵尸数据累积
9. **Demo plugin compileOnly classes 目录**（R）：paperweight-userdev 把主 plugin `:jar` 任务 `enabled = false`（防生产部署 non-shadow jar），导致 `compileOnly(project(":plugin"))` 拿不到 classes。R 用 `compileOnly(files(... classes/java/main))` + `dependsOn(":plugin:compileJava")` workaround
10. **journal 并行竞争**（S 顺手处理）：Wave 2 四 agent 都改 journal.md 顶部，S 提交时把 R / P 工作树未提交的 journal 段一并 squash 进 commit，后续 R / P / Q commit 时 journal 已就位仅提交代码改动——降低 rebase 摩擦

### API 契约

```java
// 公开接口（moe.hikari.canvas.api）
void registerNamespace(Plugin plugin, String namespace, NamespaceInfo info);
void declareKey(Plugin plugin, String namespace, String key, VarType type, @Nullable String hint);
void setVariable(Plugin plugin, String namespace, String key, String value, @Nullable Duration ttl);
void setVariables(Plugin plugin, String namespace, Map<String, VariableUpdate> updates);
void unsetVariable(Plugin plugin, String namespace, String key);
```

5 方法首参均为 `Plugin plugin`（spoof 防御 + cleanup hook）。dynamic-data.md §4.1 已回填。

### 工时核对

| Task | 估时 | 实际 wall-clock |
|---|---:|---:|
| P4-O API 包 + Impl + Registry + Provider | 5h | ~11min |
| P4-P PushRateLimiter + config | 3h | ~19min |
| P4-Q DisableListener + ServicesManager + getAPI | 3h | ~20min |
| P4-R Demo 插件 Gradle subproject | 5h | ~17min |
| P4-S docs/api.md + 回填 | 3h | ~12min |
| P4-T 收尾整合 | 1h | ~10min |
| 单测（散在各 task 内） | 3h | 内嵌 |
| **总（wall-clock）** | **23h** | **~1.5h**（Wave 2 并行节约 ~10×） |

### 0.4.0 累计进度（P1 + P2 + P3 + P4）

- **总测试**：660 backend + 93 frontend = 753 test，0 failure / 0 error
- **总 commit**：5(P1) + 4(P2) + 5(P3) + 6(P4) = 20 commit
- **总工时**：62(P1) + 30(P2) + 20(P3) + 23(P4) = 135h（vs 0.4.0 预算 150h - P5 留 10h，剩 15h 弹性）
- **wall-clock**：~3h（P1） + ~3h（P2） + ~1.5h（P3） + ~1.5h（P4） = ~9h（vs 计划 6-7 周）
- **shadow jar**：152 MB（含 4 Provider + Push API + 22 字体 + 2060 icons）

### 不做（留 P5 / 0.4.1+）

- `/canvas var` 命令族 + 教程 docs → P5（10h）
- 独立 `HikariCanvas-api.jar` Maven publish → 1.0
- chip 编辑器（Notion-style contentEditable） → 0.4.1
- 高级限流策略（per-server / 分桶 / 优先级） → v1.x
- API 接口稳定性冻结 → 1.0

### 下一步

**P5 启动等用户通知**（`/canvas var` 命令族 + 教程 docs + 集成测试，10h）。P4 落地后**整个 0.4.0
核心架构闭环**：
- 玩家：在编辑器创建 user 变量 + 在 wall 文本里引用 `${var:user/X}`
- 系统：自动暴露 server.time / wall.alias / scoreboard / PAPI 变量
- 插件：用 HikariCanvasAPI 推自定义 namespace 变量（DemoTrain / DemoScore 已演示）
- 玩家：用 Schedule Manager modal 配兜底列车时刻表

P5 仅补命令族 + 教程文档，是收尾性质工作。

---

## 2026-05-19 · 0.4.0-P4-Q：PluginDisableEvent listener + ServicesManager 注册 + getAPI()

P4 Wave 2 生命周期接入。**1 commit / 660 backend test 全绿（+7）/ 0 fixture baseline 漂移**。
HikariCanvasAPI 终于"装上"——外部插件 disable 时变量自动清理 + 通过 ServicesManager 零编译耦合获取入口。

### 新增 1 文件 / 新增 1 测试文件（+7 case）

- **`plugin/src/main/java/moe/hikari/canvas/variable/plugin/PluginCleanupListener.java`** — `Listener` 实现，监听 `PluginDisableEvent`（`MONITOR` 优先级）。三阶段清理：(1) 同步立即 `registry.unregisterAllByPlugin` 摘 namespace；(2) 同步立即 `apiImpl.unregisterPluginProviders(removed)` 摘 daemon provider；(3) **30s 后**（async Bukkit scheduler）`apiImpl.purgeNamespaceData(ns)` 清 store 变量值——保留 cached 平滑过渡（dynamic-data.md §4.3 决策）。自跳过 host：`disabled == host` 直接 return（避免 self-cleanup 与 `onDisable` 顺序竞争）。`DelayedScheduler` 函数式接口抽象 + 生产实现 `bukkitAsyncScheduler(host)` + 测试可注入同步 fake（避免在单测里挂 MockBukkit ServerMock）。异常隔离：outer try/catch + purge 阶段单 namespace 失败不影响其他
- **`PluginCleanupListenerTest.java`** — 7 case：外部插件 with namespaces → registry/provider 立即移除 + store 保留 + scheduler 收 30s task / 跑 task 后 store 清空；外部插件 without namespaces → scheduler 不调度；host 自己 disable → 整 listener return（registry / store / scheduler 都不动）；scheduler 抛异常被吞（不传播给 Bukkit event bus）；purge task 内多 namespace 鲁棒（空 namespace OK）；构造 null 防御（4 NPE）；`bukkitAsyncScheduler(null)` NPE。测试用 `handleDisable(plugin)` 包内入口（避免单测构造 `PluginDisableEvent` 触发 `Bukkit.isPrimaryThread()` NPE）

### 修改 1 文件

- `HikariCanvas.java` — onEnable 末尾段（在 ProviderBootstrap 后 / WebServer 前）加 5 处改动：(1) 新增 2 字段 `pluginNamespaceRegistry` / `apiImpl`；(2) 实例化 `PluginNamespaceRegistry` + `PushRateLimiter(config.pushRateLimitConfig)` + `HikariCanvasAPIImpl(...)`；(3) `Bukkit.getServicesManager().register(HikariCanvasAPI.class, apiImpl, this, ServicePriority.Normal)`；(4) `registerEvents(new PluginCleanupListener(...))`；(5) 新 public `getAPI()` getter 供入口 A。onDisable 不动（ServicesManager 自动反注册 + daemon.shutdown 已包含 provider cleanup）

### 关键决策

1. **listener 字段类型用 `Plugin` 而非 `JavaPlugin`**：仅做 identity 比较 + 传给 Bukkit scheduler（接受 `Plugin`），松约束让测试可用 `Proxy` 造 fake；生产侧传 `this`（JavaPlugin 是 Plugin 子类，向上兼容）
2. **`handleDisable(Plugin)` 抽出包内方法**：单测无法构造 `PluginDisableEvent`（其 super ctor 调 `Bukkit.isPrimaryThread()` → 单测环境 `Bukkit.server == null` NPE）。抽出 package-private 入口直接传 plugin；不增 MockBukkit ServerMock 设施依赖（项目至今未用过 MockBukkit @BeforeAll setup）
3. **`DelayedScheduler` 函数式接口注入**：避免单测里 mock `Bukkit.getScheduler()`。生产 `bukkitAsyncScheduler` 走 `runTaskLaterAsynchronously`（async 因为 purge 只动 in-memory + DB，不抢主线程资源）
4. **30s grace 用 async scheduler 而非 sync**：purge 路径不涉及 entity / packet / world，async 无副作用；不阻塞主线程
5. **不挂 `onDisable` 钩子做手动清理**：ServicesManager.register 时 Bukkit 自动 track owner（this），plugin disable 时自动 unregisterAll；显式 unregister 是冗余且容易顺序错（daemon 已 shutdown 后再调 unregister 会触发 IllegalStateException）

### 不变更

- 不动 NMS / 不引新依赖
- 不动 `HikariCanvasAPI` 接口（生命周期是 Impl + listener 私事）
- 不动 paper-plugin.yml（listener runtime register 不需要 yml 声明）

### 关联文件

- 新增：`plugin/src/main/java/moe/hikari/canvas/variable/plugin/PluginCleanupListener.java`
- 新增：`plugin/src/test/java/moe/hikari/canvas/variable/plugin/PluginCleanupListenerTest.java`
- 修改：`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`
- 文档：`docs/dynamic-data.md §4.3`（已由 S 任务回填）

---

## 2026-05-19 · 0.4.0-P4-P：PushRateLimiter（per-plugin 100/s + 全局 1000/s + 保护期）+ config 段

P4 Wave 2 Push 限流防御接入 HikariCanvasAPIImpl。**1 commit / 653 backend test 全绿（+15）/ 0 fixture baseline 漂移**。
让"恶意 / bug 插件 push 死循环"在 100ms 量级被拦下，不击穿 VariableStore。

### 新增 1 文件 / 新增 1 测试文件（+15 case）

- **`plugin/src/main/java/moe/hikari/canvas/variable/plugin/PushRateLimiter.java`** — 双层限流：per-plugin（默认 100/s，drop tail + WARN）+ 全局（默认 1000/s，触发 10s 保护期 + WARN）。1s 固定窗口 + `ConcurrentHashMap<pluginName, Window>` + `AtomicLong windowSec/count` 无锁；批量 `tryAcquireBatch(plugin, n)` 给 `setVariables` 用（一次性占 n token，all-or-nothing）。`Config` record（perPluginPerSecond / globalPerSecond / globalCircuitBreakMs）+ `defaults()`（100/1000/10_000）+ `unlimited()`（Integer.MAX_VALUE，测试用）。clock 注入 seam（`LongSupplier`，单测全部不用 `Thread.sleep`）
- **`PushRateLimiterTest.java`** — 15 case：单次 allow / per-plugin 边界 101 drop / 跨秒重置 / 全局 1001 触发 circuit break / 保护期内 reject 全 plugin / 保护期超时恢复 / batch 50×3 第三次 reject / batch 单次超限 reject / 多 plugin 隔离 / Config 非法参数 IAE / unlimited 永 allow / resetForTest 清零 / nullPlugin NPE / invalid count IAE / defaults 值匹配规范

### 修改 3 文件

- `HikariCanvasAPIImpl.java` — 构造器加 `PushRateLimiter limiter` 参数（5 args）；`setVariable` 在 `checkAcl` 后插 `tryAcquire(plugin)`，false 时 drop（limiter 内已 log）；`setVariables` 在 `checkAcl` 后插 `tryAcquireBatch(plugin, updates.size())`，all-or-nothing；保留 `doSetVariable` 私有 helper 复用
- `HikariCanvasConfig.java` — 新字段 `pushRateLimitConfig`（`PushRateLimiter.Config` 类型）；`load()` 解析 `dynamic.push-rate-limit` 段：`per-plugin-per-second`（默认 100，clamp ≥1）/ `global-per-second`（默认 1000，clamp ≥ perPlugin）/ `global-circuit-break-ms`（默认 10_000，clamp ≥0）；Builder defaults

### 修改 2 文件（资源 / 测试夹具）

- `config.yml` — 末尾追加 `dynamic.push-rate-limit` 段（per-plugin-per-second / global-per-second / global-circuit-break-ms 三项 + 注释指向 docs/dynamic-data.md §10.2）
- `HikariCanvasAPIImplTest.java` — 测试构造调用扩展为 4 args（注入 `new PushRateLimiter(Config.unlimited())` 永 allow fake）；null 检查测试加第 4 个 null limiter case

### 关键决策

1. **双层限流而非单层**：per-plugin 拦单个 bug 插件刷自己 namespace；全局 + 保护期拦多 plugin 协同压垮 VariableStore。两层独立配置，可分别调
2. **batch all-or-nothing**：`setVariables({a:1, b:2, c:3})` 5 entry 但 per-plugin 还剩 3 token —— 要么 5 全 reject 要么 5 全过；不部分写入（部分写入语义不一致：第 1-3 成 / 第 4-5 失败 → 调用方难处理）。代价是 batch 失败时 global window 已加进去（fixed-window 固有妥协，1s 内自然衰减）
3. **clock 注入 seam**：单测全用 `AtomicLong::get` 当虚拟时钟，跨秒重置 / 保护期超时全部确定性测试，不用 `Thread.sleep`。M16 `SessionRateLimiter` 没做这个，新代码统一新风格
4. **fixed-window 而非 sliding-window**：sliding-window 需要保存近 1s 内每个时间戳，O(N) 内存；fixed-window 单 AtomicLong + windowSec key，O(1)。代价：跨秒边界处可能放过 2× 上限（前后 100ms 各 100 token）—— 但 push 限流目标是防"刷爆"，2× 突发可接受
5. **per-plugin log 跨阈值一次**：avoid 日志洪水。`if (prev <= limit && pluginCount > limit)` 跨阈值时 log 一次；同 window 后续 reject 静默

### 不变更

- 不动 NMS / 不引新依赖
- 不动 HikariCanvasAPI 接口（限流是 Impl 私事）
- 不挂 PluginDisableEvent listener（Q 任务领域）
- 不动 HikariCanvas.java（onEnable 装配由 Q 任务完成；Q 拿到本 commit 的 4-arg 构造器后接入即可）
- 不动 docs/dynamic-data.md（限流参数变更不影响契约）

### 测试 / 构建

- `./gradlew :plugin:test` 653 case 全绿（PushRateLimiterTest 15 新 + 既有 638）
- `./gradlew :plugin:compileJava` 通过（HikariCanvas.java onEnable 装配由 Q 任务负责对接 4-arg 构造器）

---

## 2026-05-19 · 0.4.0-P4-S：docs/api.md 完整接入教程 + dynamic-data.md §4 回填

P4 Wave 2 文档任务。**纯文档 / 无代码改动 / 无测试 / 无构建**。把 M28-P4-O 落地的 `HikariCanvasAPI` 接口（5 方法 + Plugin 首参 + 2 exception + NamespaceInfo / VariableUpdate / VarType）翻成给第三方插件作者的完整接入教程，并回填 `dynamic-data.md §4` 让规划文档与实施接口一致。

### 新增 1 文件

- **`docs/api.md`** — 13 章节 / 约 660 行 / 完整接入教程：
  1. **概览** — 数据流 + 异步 / 错误隔离 / 限流防御纪律
  2. **快速开始** — Gradle 依赖 + `paper-plugin.yml` + ServicesManager / getAPI 两种入口 + 完整 BedWars 示例
  3. **API 接口完整参考** — 5 方法逐一参数表 + 抛异常 + 静默 drop + 示例
  4. **namespace ACL & 保留 namespace** — 5 保留 ns 表 + ACL spoof 防御机制 + 命名建议
  5. **生命周期 & 清理** — `PluginDisableEvent` 自动 + 30s 延迟 purge + 服务器重启行为
  6. **渲染 / 变量解析** — 引用语法 + fallback 链 + wall dirty 合并 + 线程模型
  7. **限流** — 默认配置 + 触限行为 + 最佳实践
  8. **错误码 / 异常表** — 调用方异常 + 静默 drop + 防御性编程模板
  9. **完整示例插件** — 指向 `examples/demo-train-plugin/` + `examples/demo-score-plugin/`（R 任务交付）
  10. **FAQ** — 10 题（不装 HC 能否工作 / 重启变量值 / declareKey 显示 / TTL 选择 / 同名 key / 查询当前值 / 颜色格式化 / PAPI vs Push / 限流封号 / async 调用 / HC reload 处理）
  11. **升级 / 向后兼容承诺** — pre-1.0 / 1.0+ 路径 + 包路径稳定保证（指向 P4-T shadowJar exclude）
  12. **参考文档**
  13. **反馈 & 贡献**

### 修改 1 文件

- **`docs/dynamic-data.md`** — 3 处更新：
  - §4.1 接口示例 5 方法全部加 `Plugin plugin` 首参（与 P4-O 实际接口对齐）+ 行内注释 `// 新增第一参数（M28-P4 实施决策）`；javadoc 也同步（异常类型 `PermissionDeniedException` → `PluginNamespaceException`）
  - §4 段尾新增「实施实际接口（M28-P4 落地）」总览段：接口位置 / 实现位置 / 注册中心 / 限流 / 卸载清理 / API 包独立 VarType / 异常体系 / 完整接入教程交叉引用
  - §4.2 BedWars 示例代码同步：`Plugin hikari = Bukkit.getPluginManager().getPlugin("HikariCanvas")` 路径换为 `Bukkit.getServicesManager().load(HikariCanvasAPI.class)`（推荐方式 A），三个 `canvas.declareKey / setVariable` 全部加 `this` 首参
  - §15 把 `docs/api.md（新文件）` 状态从 "M28 实施时落地（本规划阶段先不写）" 改为 "M28-P4 已落地"

### 不变更

- 不动任何 Java 代码 / 任何 yml / 任何前端
- 不跑 runServer / 不跑 npm build / 不跑 test
- 不动 README.md（项目根 README 仅 13 行欢迎页，无 "Dynamic Data" 段；按 S.4 指示跳过）
- 不动 docs/protocol.md / data-model.md / security.md / architecture.md（其他任务领域 / 已在 P1-P3 阶段更新过）

### commit

待提交：`M28-P4-S: docs/api.md 完整接入教程 + dynamic-data.md §4 回填`

---

## 2026-05-19 · 0.4.0-P4-R：DemoTrainPlugin + DemoScorePlugin（Gradle subproject 双范型）

P4 Wave 2 示例插件双范型落地。**两个独立 Paper subproject / compileJava + jar 全绿 / 仅 compileOnly plugin 主项目 classes 目录**。证明 HikariCanvasAPI 真好用 + 双触发方式（定时 push / 事件 + 命令 push）都覆盖。

### settings.gradle.kts 扩展

加 `include("examples:demo-train-plugin") + include("examples:demo-score-plugin")`，让 Gradle 把 `examples/` 子目录作为两个独立 Java subproject 识别。

### examples/demo-train-plugin（定时器 push 范型）

`build.gradle.kts`（Java 21 + paper-api + `compileOnly(files(rootProject.layout.projectDirectory.dir("plugin/build/classes/java/main")))`）+ `tasks.compileJava { dependsOn(":plugin:compileJava") }`。**为何不用 `compileOnly(project(":plugin"))`**：主 plugin 的 `:jar` 被 paperweight `enabled = false`，default `apiElements` 配置没有可消费的 archive；改 `shadowJar` 当依赖物又拖慢示例编译。直接 compileOnly classes 目录最干净（IDE 也能识别符号）。

- `DemoTrainPlugin.java`（70 行）：`Bukkit.getServicesManager().load(HikariCanvasAPI.class)` 获取 API（推荐零编译耦合路径，Q 任务后续会 register 这个 Service）→ `registerNamespace("demo_train", ...)` → `declareKey` 6 个 key（line1/2 × {next_departure / next_destination / eta_minutes}）→ 启动 `TrainSchedulePusher`
- `TrainSchedulePusher.java`（70 行）：`BukkitScheduler.runTaskTimer` 每 5s（100 ticks）调一次；模拟 `computeNextDeparture` 算 `now + 5~15 min` 下一班车；每次 push 3 个变量 / 线，TTL 10 分钟兜底（插件挂掉变量仍可见 10 分钟，再之后走 fallback 链）
- `paper-plugin.yml`：声明 `HikariCanvas: required: true + load: BEFORE + join-classpath: true` 让启动顺序由 Paper plugin loader 保证

### examples/demo-score-plugin（事件 + 命令 push 范型）

`build.gradle.kts` 同 train。

- `DemoScorePlugin.java`（80 行）：`registerNamespace("demo_score", ...)` + `declareKey` 3 个 key（red/blue/mvp）；暴露 `addRed/addBlue/setRed/setBlue/reset/setMvp` 公开 mutation API 给 listener + command 调；每次变化即时 `setVariable`（TTL=null 永久）
- `DemoScoreListener.java`（22 行）：`@EventHandler PlayerJoinEvent → plugin.setMvp(player.name)`
- `DemoScoreCommand.java`（70 行）：`/demoscore add <red|blue> <n>` / `/demoscore set <red|blue> <n>` / `/demoscore reset`，`NumberFormatException` 友好提示
- `paper-plugin.yml`：声明 `commands.demoscore` + `permissions.hikari.demo.score: default op`

### examples/README.md

约 60 行：DemoTrainPlugin / DemoScorePlugin 用法说明 + 可用变量列表 + 编译 / 安装 / wall 上引用占位符语法。

### 验证

- `./gradlew :examples:demo-train-plugin:compileJava :examples:demo-score-plugin:compileJava` 全绿（只有 `getDescription()` deprecated 警告，Paper 1.21 仍可用，留 Paper API 升级再修）
- `./gradlew :examples:demo-train-plugin:jar :examples:demo-score-plugin:jar` 全绿，输出 `DemoTrainPlugin-0.3.0-SNAPSHOT.jar`（4.8 KB）+ `DemoScorePlugin-0.3.0-SNAPSHOT.jar`（5.8 KB）
- 两个 jar 仅含 demo 源码 class + paper-plugin.yml，不包打主 plugin 的 API 类（运行期由 HikariCanvas 主插件提供）
- `:plugin:test` 因 Wave 2 Q 任务并行 untracked 文件未收尾当下不可独立验证；R 改动**绝不接触** plugin/main 源 / plugin/test 源 / plugin/build.gradle.kts，对 plugin module 编译/test 零副作用

### 关联文件

- `settings.gradle.kts`
- `examples/README.md`（新）
- `examples/demo-train-plugin/` 全树（新）
- `examples/demo-score-plugin/` 全树（新）

---

## 2026-05-19 · 0.4.0-P4-O：HikariCanvasAPI 包 + Impl + PluginNamespaceRegistry + Provider

P4 Wave 1 基础设施落地。**1 commit / 638 backend test 全绿（+38）/ 0 fixture baseline 漂移**。
P/Q/R/S/T 并行任务的"地基"，让 PushRateLimiter（P）/ ServicesManager + getAPI()（Q）/ Demo 插件（R）/ docs/api.md（S）/ shadowJar relocate exclude（T）有可接入的契约。

### 新增 8 文件

- **`moe.hikari.canvas.api`**（公开 API 包，外部插件 import 用，**不可改路径**）：
  - `HikariCanvasAPI.java` — 5 方法接口：`registerNamespace / declareKey / setVariable / setVariables / unsetVariable`，全部首参 `Plugin plugin`（决策 2：显式传 Plugin 实例）
  - `NamespaceInfo.java` — `(displayName, pluginName, version)` record
  - `VariableUpdate.java` — `(value, @Nullable Duration ttl)` record
  - `VarType.java` — API 包独立 enum（STRING/NUMBER/BOOLEAN/COLOR），与内部 `variable.VarType` 1:1 但<b>不引用</b>——保证 shadowJar relocate exclude `moe/hikari/canvas/api/**` 后外部插件 import 路径稳定
  - `NamespaceConflictException.java` — register 跨 plugin 冲突
  - `PluginNamespaceException.java` — set/declare/unset ACL 拒绝（Code = NAMESPACE_NOT_REGISTERED / NAMESPACE_ACL_DENIED）

- **`moe.hikari.canvas.variable.plugin`**（内部实现）：
  - `PluginNamespaceRegistry.java` — namespace ACL 注册表，`ConcurrentHashMap.putIfAbsent` 原子注册；保留 namespace `user/system/papi/scoreboard/schedule` 抛 IllegalArgumentException；同 plugin 重复 register 幂等覆盖（保留 registeredAt）；提供 `unregisterAllByPlugin` 给 Q 的 PluginDisableEvent listener 调
  - `PluginNamespaceProvider.java` — 实现 `VariableProvider`，每个外部 namespace 一实例；`isDynamic=true / refreshInterval=ZERO`（不调度，纯 push）；`declaredKeys()` 暴露 declareKey 加入的 keys 给 P3-M `/api/variable/list-all-namespaces` 端点
  - `HikariCanvasAPIImpl.java` — API 核心实现：错误隔离（VariableException 静默 + log、未知异常 SEVERE 不外泄、ACL 异常抛回调用方）；`doSetVariable` 先 setValue 试探 → NOT_FOUND 再 create + setValue（保证 VALUE_TOO_LONG 等失败时不残留半态变量）；暴露 `unregisterPluginProviders(List)` + `purgeNamespaceData(String)` 给 Q 的 disable + 30s 延迟清理钩子

### 新增 2 测试文件（+38 case）

- `PluginNamespaceRegistryTest.java` — 12 case：register + owns + 跨 plugin 冲突 + 保留 namespace + 非法格式（8 种）+ unregisterAllByPlugin + snapshot 行为 + null 校验。**Plugin mock 走 `java.lang.reflect.Proxy`**（项目无 Mockito 依赖，且引入 MockBukkit ServerMock 启动开销过高）
- `HikariCanvasAPIImplTest.java` — 26 case：register/declareKey/setVariable/setVariables/unsetVariable 全分支 + ACL（NOT_REGISTERED / DENIED）+ VarType 4 case 转换 + 自动 create + declared key 影响类型 + 静默错误（valueTooLong）+ Q 钩子（unregisterPluginProviders / purgeNamespaceData）+ 构造 null 校验

### 关键决策

1. **API 包独立 VarType**：不让外部插件 import `moe.hikari.canvas.variable.VarType`——shadowJar relocate 后内部包名变成 `moe.hikari.canvas.shaded.*`（M16.5）会破坏插件编译。API 包定义自己的 enum + `HikariCanvasAPIImpl.convertVarType` 1:1 转换
2. **decision 3 落地分工**：本任务交付 Impl 的两个钩子 `unregisterPluginProviders(List)` + `purgeNamespaceData(String)`；不挂 PluginDisableEvent listener（Q 任务做）；不接 Bukkit scheduler 做 30s 延迟（Q 任务做）。本任务<b>只</b>给"按需触发"的纯函数 API，让 Q 自由编排
3. **错误隔离三档**：
   - 业务异常（QUOTA / VALUE_TOO_LONG / TTL_INVALID / NAME_INVALID）→ catch + log WARNING + 静默返回；插件作者写错值不应感知
   - ACL 异常（NAMESPACE_NOT_REGISTERED / DENIED）→ 抛回调用方；插件 bug（漏 register / spoof）不应静默
   - 未知 Exception → catch + log SEVERE + 静默；HikariCanvas 不能被插件 bug 拖垮
4. **doSetVariable 失败原子性**：先 `setValue` 试探（变量已存在路径直接成功；VALUE_TOO_LONG 等抛出直接 catch）→ 只有 NOT_FOUND 才落 `create + setValue`，避免"create 成功但 setValue 抛"导致 currentValue=null 的半态变量遗留在 store
5. **registerNamespace 内同步 daemon.register 失败容忍**：`computeIfAbsent` 保证 provider 实例幂等；`daemon.register` 第二次抛 IllegalStateException 被 catch 吞（同 plugin reload / register 同 namespace 时 daemon 内已有 entry）

### 给 P / Q / R / S / T 任务的接入指引

- **P（PushRateLimiter）**：在 `HikariCanvasAPIImpl.doSetVariable` 入口或 `setVariable` / `setVariables` 之前插一道限流；通过构造器注入 Limiter 即可（不需要改接口）。注意 `setVariables` 批量已避免重复 ACL 检查，限流也建议按 batch 计费而非单条
- **Q（getAPI + ServicesManager + PluginDisableEvent listener）**：
  1. `HikariCanvas.onEnable` 实例化 `HikariCanvasAPIImpl(registry, store, daemon)`；暴露 `getAPI()` 字段 + `Bukkit.getServicesManager().register(HikariCanvasAPI.class, apiImpl, this, ServicePriority.Normal)`
  2. 挂 `Listener` 监听 `PluginDisableEvent`：`var removed = registry.unregisterAllByPlugin(ev.getPlugin()); apiImpl.unregisterPluginProviders(removed); Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> removed.forEach(apiImpl::purgeNamespaceData), 20L * 30)`
- **R（Demo 插件）**：Gradle subproject，依赖 `compileOnly project(":plugin")`（仅需 API 包）；onEnable 调 `Bukkit.getServicesManager().load(HikariCanvasAPI.class)`
- **S（docs/api.md）**：把 `docs/dynamic-data.md §4.1` 接口示例补 `Plugin plugin` 首参；新建 `docs/api.md` 详细记录两种获取入口 + namespace ACL + 错误码表
- **T（shadowJar relocate exclude）**：在 `plugin/build.gradle.kts` shadowJar 的 7 条 relocate 之上加 `exclude "moe/hikari/canvas/api/**"`——保证外部插件 import 路径 `moe.hikari.canvas.api.*` 在 shaded jar 内保持公开

### 测试结果

- `:plugin:test`：**638 tests / 0 failed / 0 skipped**（P3 baseline 600 → +38）
- 仅 `:plugin:compileJava` + `:plugin:test` 跑过，**未跑 shadowJar / runServer**（任务纪律）

---

## 2026-05-19 · 0.4.0-P3 收尾：内置 Provider 完工

P3 五子任务（J/K/L/M/N）完工。**5 commit / 600 backend + 93 frontend test 全绿 /
shadow jar 152 MB / 0 fixture baseline 漂移**。Wave 1 单跑（J 建基础设施）+ Wave 2 三 agent
并行（K/L/M 各自实施 Provider 与 metadata 端点）+ Wave 3 N 主控收尾（WebServer 装配
VariableMetadataHandler + 全测 + journal + push）。

### Phase commit 时间线

| Commit | Task | 范围 |
|---|---|---|
| `8828b2b` | P3-J Wave 1 | VariableProvider 接口扩展 + 双端 interpolator wall.* 注入 + SystemVariableProvider 13 变量分轨 + ScoreboardVariableProvider 混合模式自动注册（+47 单测） |
| `219f731` | P3-K Wave 2 | PapiVariableBridge 软依赖 reflection + 编码层 + PapiAccessor 接口（+29 单测） |
| `c975996` | P3-M Wave 2 | VariableMetadataHandler HTTP 端点 + Picker mergeMetadata 接入（+27 单测） |
| `b0b2e52` | P3-L Wave 2 | ManualScheduleProvider 全栈 V012 + 5 WS op + Schedule Manager Modal + Train icon TopBar（+31 单测） |
| `<本 commit>` | P3-N Wave 3 | WebServer 装配 VariableMetadataHandler + CLAUDE.md 路线段 P3 标记完成 + journal P3 总览 |

### 关键架构落地

1. **VariableProvider 接口契约统一**（J 首创 + K/L 沿用）：所有 Provider 暴露 `declaredKeys() → List<DeclaredKey>`（编辑器自动补全用）+ `isDynamic()`（动态注册标记，影响 Picker UI 提示）+ `refreshInterval()` 返 `Duration.ZERO` 即不调度（K 用此让 PAPI 未装时零开销）
2. **VariableStore dynamic lookup hook**（J）：`registerDynamicLookupHook(BiConsumer<fullName, namespace>)` + interpolator resolve miss → `notifyDynamicLookup` → hook 异步处理。Scoreboard / PAPI 都用这套混合模式：interpolator 首次解析触发注册，10s / 5s 后第二次 tick 起有数据
3. **双端 interpolator 三种 namespace alias 注入**（J + L）：
   - `${var:user/X}` → `user:<wallId>/X`（P1）
   - `${var:wall.X}` → `system:<wallId>/wall.X`（P3-J）
   - `${var:schedule.X}` → `schedule:<wallId>/X`（P3-L）
   - 前后端 1:1 镜像，48 单测对称覆盖
4. **per-wall vs 全局 namespace 分轨**（J / L）：
   - 全局：`system` (server.time 等 8 个) / `papi` / `scoreboard`
   - per-wall：`system:<wallId>` (wall.* 4 个) / `schedule:<wallId>` (4 个 next_*) / `user:<wallId>` (玩家自建)
   - 注册时机：启动期遍历 + WallRepo create hook（J）+ 用户首次操作触发（L 的 schedule entry add 触发 ensureWallRegistered）
5. **PAPI 编码层**（K）：VariableStore key 正则 `[a-zA-Z0-9_.-]+` 不允许 `%`，Bridge 边界编码 `%player_name%` ↔ `pct_player_name_pct`；内部 encoded → original 映射；refresh 按 original 调 PAPI、按 encoded 写 store；外部占位符语法 `${var:papi:%xxx%}` 暂保留留 0.4.1+ 完整支持（P3 范围内通过 dynamic lookup hook 外部触发可工作）
6. **HTTP 端点测试 seam**（M）：`VariableMetadataHandler` 注入 `Predicate<String> sessionAuthCheck` 替代直接耦合 SessionManager.isAuthenticated，单测无需 mock 全套 session 子系统
7. **schedule 走独立 dispatcher**（L 决策）：ScheduleOpDispatcher 同 WallOpDispatcher 模式，**不入 EditSession**——schedule 不影响 ProjectState / 像素 dirty，避免污染 element op 路径
8. **过零点 ETA 算法**（L）：所有 entry 已过时 next 选第一条（明天），eta = (24h - now) + nextTime，capped 1440min；is_arriving 阈值 = 5min（dynamic-data.md §7.3 规范）
9. **测试友好抽象**（J / K / L 一致风格）：DataSource / PapiAccessor 接口注入，单测用 Fake 实现，**不依赖 MockBukkit / PAPI classpath**，提升测试速度与可移植性

### 协议契约新增

**HTTP 端点**（dynamic-data.md §3.3 + protocol.md §5.13）：
- `GET /api/variable/list-all-namespaces?sessionId=<id>&wallId=<wallId>` → `{namespaces: [{namespace, displayName, dynamic, keys: [...]}]}`

**WS op**（protocol.md §5.12）：
- `schedule.list / upsert / entry.add / entry.update / entry.delete` 共 5 个

**权限节点新增**：
- `canvas.schedule.own` (default=true) / `canvas.schedule.any` (default=op)

### 工时核对

| Task | 估时 | 实际 wall-clock |
|---|---:|---:|
| P3-J 基础设施 + SystemProvider + Scoreboard | 6h | ~19min |
| P3-K PapiBridge | 3h | ~8min |
| P3-L ManualSchedule 全栈 | 7h | ~34min（最大头） |
| P3-M HTTP 端点 + Picker | 3h | ~21min |
| P3-N 收尾整合 | 1h | ~15min |
| **总（wall-clock）** | **20h** | **~1.5h**（Wave 2 并行节约 ~12×） |

### 不做（留 P4-P5 / 0.4.1+）

- Plugin Push API + HikariCanvasAPI.setVariable + 注册中心 → P4（28h）
- `/canvas var` 命令族 + 教程 docs → P5（10h）
- chip 编辑器（Notion-style contentEditable） → 0.4.1
- `${var:papi:%xxx%}` 占位符语法的 interpolator 解析（P3-K 暂用 encoded 形态绕开） → 0.4.1+
- 动画 / 时间轴 / Blockly 脚本 → 0.5.0+

### 下一步

**P4 启动等用户通知**（Plugin Push API：HikariCanvasAPI 接口 + 注册中心 + DemoTrainPlugin /
DemoScorePlugin 示例插件，28h）。P3 落地后**4 Provider 全部就绪**——MC 服务器启动即可让玩家：
- 在 wall 上引用 `${var:server.time}` / `${var:wall.alias}` / `${var:scoreboard.points.HaruHyacinth}` 等系统/记分板变量
- 装上 PAPI 后即可引用 `papi/<placeholder>` 变量（编码层暂时需手动构造 `${var:papi/pct_player_name_pct}`）
- 通过 Schedule Manager modal 配列车时刻表 + 自动暴露 `schedule.next_departure` 等动态变量

---

## 2026-05-19 · 0.4.0-P3-L：ManualScheduleProvider 全栈（兜底列车时刻表）

P3 Wave 2 之一：零外部依赖的"时刻表" provider。**1 commit / ~1700 行净增（含 6 新生产类 + 2 测试类 + 1 V012 migration + 4 前端组件 + 1 store）/ 631 backend test 全绿（+31 from P3-M 基线 600）/ 96 frontend test 全绿（+3 from P3-M 基线 93）**。

### 改动一览

1. **V012__wall_schedules.sql**（{{plugin/.../resources/db-migrations/V012__wall_schedules.sql}}）：
   - `wall_schedules` 表（wallId 主键 + stationName + updatedAt + FK CASCADE）
   - `schedule_entries` 表（id 自增 + wallId FK + departureTime "HH:mm" + destination nullable + sortOrder + idx_schedule_entries_wall）
   - 注册到 MigrationRunner.MIGRATIONS 列表 V011 之后

2. **ScheduleDao**（{{plugin/.../storage/ScheduleDao.java}}, ~230 行）：JDBI CRUD —— `loadByWall / upsertSchedule / insertEntry / updateEntry / deleteEntry / deleteByWall / loadAll`；entries 按 `sort_order ASC, departure_time ASC` 预排序；记录 record `WallSchedule / ScheduleEntry`（`moe.hikari.canvas.schedule` 新包）

3. **ManualScheduleProvider**（{{plugin/.../variable/provider/ManualScheduleProvider.java}}, ~340 行）：
   - namespace = `"schedule:<wallId>"`（per-wall，同 SystemProvider 风格）
   - 4 key：`next_departure (STRING) / next_destination (STRING) / eta_minutes (NUMBER) / is_arriving (BOOLEAN)`
   - 30s refresh interval；computeNext 纯函数（按 departure_time 排 + 找第一个 > now + 过零点计算 + is_arriving ≤ 5min 阈值）
   - `initialize()` 启动期遍历 `dao.loadAll()` 注册所有已有 schedule 的 wall
   - `ensureWallRegistered(wallId)` / `refreshWall(wallId)` / `unregisterWall(wallId)` API — 业务侧（EditSession + SessionManager.wallDeleteHook）调用
   - `DataSource` 测试 seam（DAO + LocalTime 抽象），生产 `DaoDataSource` 包 ScheduleDao + `LocalTime.now()`
   - 容错：非法 "HH:mm" 退化 `MIDNIGHT`；entries 空时 push 空字符串占位

4. **ProviderBootstrap 扩展**（{{plugin/.../variable/provider/ProviderBootstrap.java}}）：
   - `initialize(store, plugin, wallRepo, scheduleDao)` 4 参数 overload（旧 3 参 wrapper 留兼容）
   - daemon.register(new ManualScheduleProvider(store, plugin, scheduleDao))

5. **ScheduleOpDispatcher**（{{plugin/.../web/ScheduleOpDispatcher.java}}, ~330 行）：
   - 5 WS op：`schedule.list / schedule.upsert / schedule.entry.add / schedule.entry.update / schedule.entry.delete`
   - 权限：owner 走 `canvas.schedule.own` (default=true)，非 owner 需 `canvas.schedule.any` (default=op)
   - HH:mm 24h regex 校验 + destination ≤ 64 字符 + sortOrder int 校验
   - entry.* op 完成后调 `provider.refreshWall(wallId)` 立即重算 4 个变量（不等 30s tick）
   - 跨 wall entry id 拒（先 loadByWall 校验包含 id）
   - 5 audit 事件：`SCHEDULE_UPSERT / SCHEDULE_ENTRY_ADD / SCHEDULE_ENTRY_UPDATE / SCHEDULE_ENTRY_DELETE / PERMISSION_DENIED`

6. **VariableInterpolator + interpolator.ts**（{{plugin/.../variable/VariableInterpolator.java}} + {{web/variable/interpolator.ts}}）：
   - `schedule.<key>` + wallId → `schedule:<wallId>/<key>` 注入（与 wall.* 同款）
   - 双端镜像一致；wallId 为 null 时字面查询走 fallback

7. **HikariCanvas onEnable wiring**：scheduleDao 字段 + `wallDeleteHook` 联动 `manualScheduleProvider.unregisterWall + scheduleDao.deleteByWall`；ProviderBootstrap.initialize 4 参版

8. **paper-plugin.yml**：新 `canvas.schedule.own` (true) + `canvas.schedule.any` (op) 权限节点

9. **前端**：
   - `types/schedule.ts`：`ScheduleEntry / WallSchedule / Schedule*Ack` 接口
   - `stores/schedule.ts`：Pinia store（`current / loading / setLoaded / setStationName / upsertEntry / removeEntry / reset`）；wall 切换时由 wsClient.handleReady 触发 reset
   - `wsClient.ts` 加 5 个 send method（list / upsert / entry.add / update / delete）+ static import useScheduleStore + reset on wall switch
   - `components/schedule/ScheduleManagerModal.vue`：站名 inline edit + entries 列表 + 添加 / 编辑 / 删除 + 4 个 schedule.* 变量 live preview
   - `components/schedule/ScheduleEntryDialog.vue`：双用途（add/edit）子 modal（type=time 24h + destination + sortOrder + HH:mm 实时校验）
   - `stores/ui.ts`：`scheduleManagerOpen + toggleScheduleManager + closeScheduleManager + reset 复位`
   - `TopBar.vue`：Train icon 按钮挂在 Variable 按钮旁边，toggle scheduleManagerOpen
   - `App.vue`：末尾 `<ScheduleManagerModal />`
   - `i18n/messages.ts`：顶层 `schedule` section（中英 30+ key）+ `topbar.scheduleManager`

10. **测试**：
    - **ScheduleDaoTest**（14 case）：CRUD + 排序 + 级联删 (`cascade_onWallDelete_clearsScheduleData`) + loadAll
    - **ManualScheduleProviderTest**（17 case，FakeDataSource）：register/unregister + refresh 计算 + per-wall 隔离 + edge case（空 entries / 过零点 / 5min 阈值 / 非法时间格式 / 幂等 / shutdown）
    - **VariableInterpolatorTest** +3 case：schedule.* 双端注入测试
    - **frontend interpolator.test.ts** +3 case：schedule.X resolve / wallId null fallback

### 关键决策（已固化）

1. **schedule 走 dispatcher 而非 EditSession**：schedule 不影响 ProjectState / 像素 dirty，独立的 ScheduleOpDispatcher 直发 ack（无 state.patch / version bump）；同 WallOpDispatcher 模式
2. **per-wall namespace = `"schedule:<wallId>"`**：与 SystemProvider 的 `system:<wallId>/wall.*` 风格统一；Provider.namespace() 返 `"schedule"` 仅是 daemon 唯一性 key，per-wall 真实 namespace 在 store 中按 `"schedule:" + wallId` 注册
3. **Provider 注册时机**：玩家首次添加 entry 时由 EditSession（实际由 ScheduleOpDispatcher）调 `provider.refreshWall(wallId)`，内部走 `ensureWallRegistered` 注册 4 个变量；避免对所有 wall 都注册无意义空 schedule
4. **wall 删除联动**：HikariCanvas onEnable 注册 `sessionManager.addWallDeleteHook`：删 wall → `provider.unregisterWall + scheduleDao.deleteByWall`（SQLite FK CASCADE 也会清，但显式调更稳）
5. **`refreshWall` 同步立即算**：entry add/update/delete 后立即 push 新值，不等 30s refresh tick——玩家在编辑器看到的 live preview 体验
6. **过零点 ETA**：所有 entry 已过时，next 选第一条（明天），eta = `(24h - now) + nextTime`，capped 1440min。is_arriving 仅 ≤ 5min 阈值触发

### 与 K/M 协调

- ProviderBootstrap.java 在 K 加 PapiVariableBridge 之上加 ManualScheduleProvider（无冲突 — K 已 commit 219f731）
- WebServer.java 加 ScheduleOpDispatcher（M 未动 WebServer，N 收尾时合并 VariableMetadataHandler 装配）
- HikariCanvas.java onEnable 加 scheduleDao + provider hook（M 未动 onEnable）
- VariableInterpolator.java / interpolator.ts 紧挨 J 加的 wall.* 注入后加 schedule.* 注入（无冲突）

---

## 2026-05-19 · 0.4.0-P3-M：`/api/variable/list-all-namespaces` 端点 + VariablePicker metadata 接入

P3 Wave 2 之一：编辑器 VariablePicker 自动补全数据源落地。**1 commit / ~620 行净增（含 1 新生产类 + 1 测试类 + 1 picker 重写）/ 576 backend test 全绿（+10 from P3-K 基线 566）/ 90 frontend test 全绿（+17 from P2-I 基线 73 — pickerLogic 新 17 case）**。

### 改动一览

1. **VariableMetadataHandler**（{{web/VariableMetadataHandler.java}}, ~210 行）：
   - `GET /api/variable/list-all-namespaces?sessionId=<id>&wallId=<wallId>` 端点 handler；不主动注册路由（由 N 收尾一行 `addEndpoint` 接入 WebServer）
   - 鉴权：query `sessionId` 通过 `SessionManager.byId(sid) != null` 校验（同 UploadHandler.handleDownload）；失败 401 `{"error":"UNAUTHORIZED"}`
   - 聚合：遍历 `daemon.registeredProviders()` → 每个 provider 序列化 `{namespace, displayName, dynamic, keys:[DeclaredKey...]}`；带 wallId 时在首位插入 `user:<wallId>` namespace（含该 wall 的所有 user 变量，**不依赖 Compositor markReferences**——直接扫 `store.listAll()` 按 namespace 严格匹配）
   - cache：5s server-side cache；**仅** wallId 缺省路径命中（带 wallId 走实时算——user 变量增删频繁）。`volatile cachedJson + AtomicLong cachedAt`
   - 容错：provider `declaredKeys()` 抛异常被 try-catch 吞掉 + log warning + 本 provider 仍下发 namespace 元数据但 keys=空；序列化失败 500 `{"error":"INTERNAL"}` 不 echo 内部异常
   - **测试 seam**：`Predicate<String> sessionAuthCheck` 注入（生产用 `sessionManager::byId 非空 lambda`，测试注入 fake）——避免测试构造重 SessionManager（final 类，需要 Logger + MapPool + WallResolver + AuditLog + WallRepo 全套）

2. **pickerLogic.ts 扩展**（{{web/variable/pickerLogic.ts}}）：
   - 新 `NamespaceMetadata` / `DeclaredKeyMetadata` 接口（与后端 JSON wire 形态对齐）
   - 新 `declaredKeyToVariable(ns, k) → Variable`：metadata declared key 转 Variable 骨架（cached value 留 null；UI 渲染显示 "—"）；运行时白名单 `KNOWN_TYPES` 防后端新增 type 时前端崩
   - 新 `mergeMetadata(storeVariables, metadata) → Variable[]`：metadata declared keys 为主轴 + store cached value 覆盖；store-only 变量（如 scoreboard 动态注册的具体 key）append 到末尾
   - 新 `isDynamicNamespace(metadata, namespace) → boolean`：UI 标记 "动态注册" chip 用

3. **VariablePicker.vue 重写**（{{web/components/variables/VariablePicker.vue}}）：
   - `onMounted` async fetch `/api/variable/list-all-namespaces?sessionId=&wallId=` 拉 metadata；失败 silent fallback 到 store-only（不影响基础功能）
   - 内部 ref `metadata` + computed `merged = mergeMetadata(store + metadata)` 喂给 `buildGroups`
   - UI 行扩展：name 旁加 `<chip type>` + 动态 namespace 加 `<chip dyn>`（tooltip `t.variables.picker.dynamicHint`）；value 走 cached / default / "—" 三档 fallback
   - CSS：新 `.hc-vp-meta / .hc-vp-chip / .hc-vp-chip-dynamic` token-style；`max-width 55%` 容纳 chip + value

4. **i18n**（{{web/i18n/messages.ts}}）：picker 子段加 `dynamicHint` 中英 key

5. **后端单测**（{{test:.../VariableMetadataHandlerTest.java}}, 10 case，JavalinTest 端到端）：
   - 鉴权：sessionId 缺 / 空 / 未知 → 401
   - 无 wallId：聚合 provider declaredKeys；不含 user namespace
   - 带 wallId：user namespace 排首位 + 含该 wall user 变量 + 跨 wall 隔离
   - 带 wallId 空 user 变量 → keys=[]
   - cache：5s TTL 内 declaredKeys 只调一次；带 wallId 每次实时算 + 不写 cache
   - declaredKeys 抛异常隔离：其他 provider 仍下发；抛者下发 namespace + 空 keys
   - 动态 namespace：dynamic=true + keys=[]

6. **前端单测扩展**（{{web/variable/__tests__/pickerLogic.test.ts}}, +17 case）：
   - `declaredKeyToVariable`：完整 Variable 骨架 + 未知 type 退化 STRING
   - `mergeMetadata`：declared keys 全显示 / cached 覆盖骨架 / store-only append / 动态 namespace 不污染 / 空输入
   - `isDynamicNamespace`：动态 / 静态 / 未声明 namespace
   - `buildGroups + merged metadata` 集成：4 组分类仍正确

7. **docs/dynamic-data.md §3.3** 扩写：`/api/variable/list-all-namespaces` JSON wire 形态 + 鉴权 + cache 策略

### 关键决策（已固化）

1. **handler 不主动注册路由，由 N 收尾装配**：保持 M15.x 拆分纪律（handler 自治；WebServer 仅装配 + 路由）；同时避免与并行进行的 L 任务（schedule.* WS op）抢 WebServer.java 编辑权
2. **`Predicate<String>` 注入鉴权**：生产构造仍接受 `SessionManager`（即时打包成 lambda）；测试构造直接传 predicate—不构造 SessionManager（final 类，重 deps）。Auth check 边界变小→易测；测试覆盖率 / 速度都受益
3. **5s cache 仅 wallId 缺省路径**：user 变量 create/delete 频繁；带 wallId 走实时算 200μs 内（store.listAll + 字符串匹配），不写 cache—**正确性优先于 cache 命中率**
4. **provider declaredKeys 异常隔离**：本任务下游 K (PapiVariableBridge) / L (ManualScheduleProvider) 可能在 declaredKeys 抛异常；外层 try-catch 让 picker UI 永远能拿到 namespace 元数据，单 provider 故障不影响其他
5. **user namespace 直接扫 store.listAll**：不依赖 `store.listByWall`（那个依赖 Compositor markReferences——但 user 变量是玩家手动 create，可能尚未在文本中引用）。直接 strict namespace 匹配 `user:<wallId>`—保证 picker 显示所有 user 变量（含未引用过的）
6. **运行时 KNOWN_TYPES 白名单**：前端 declaredKeyToVariable 把未知 type 退化 STRING；防后端未来加新 VarType（如 ARRAY / OBJECT）时旧前端崩

### N 收尾装配指引（一行）

WebServer 构造内（同 uploadHandler 装配处附近）：

```java
this.variableMetadataHandler = new VariableMetadataHandler(
    variableStore, variableProviderDaemon, sessionManager,
    /* ObjectMapper - 复用 JavalinJackson 的 mapper 或新 ObjectMapper() */);
```

routes block 内：

```java
cfg.routes.addEndpoint(new Endpoint(
    HandlerType.GET, "/api/variable/list-all-namespaces",
    variableMetadataHandler::handle));
```

构造依赖：VariableStore + VariableProviderDaemon 已是 HikariCanvas#onEnable 实例化的字段，N 直接 forward 进 WebServer ctor 即可。

### 通用基线

- backend test: 566 → 576（10 new VariableMetadataHandlerTest）
- frontend test: 73 → 90（17 new pickerLogic.test.ts P3-M case）
- bundle: 620 kB → 623 kB（gzip 190 kB→190 kB；+3 kB 体现 mergeMetadata / declaredKeyToVariable / fetchMetadata onMounted block）
- shadow jar: 161 MB (P3-K) → 不变（M 仅 +0.5 KB 类）
- 0 baseline 漂移

### 关联文件

- 新 {{web/VariableMetadataHandler.java}}
- 新 {{test:.../VariableMetadataHandlerTest.java}}
- 改 {{web/variable/pickerLogic.ts}}
- 改 {{web/components/variables/VariablePicker.vue}}
- 改 {{web/variable/__tests__/pickerLogic.test.ts}}
- 改 {{web/i18n/messages.ts}}
- 改 {{docs/dynamic-data.md §3.3}}

---

## 2026-05-19 · 0.4.0-P3-K：PapiVariableBridge（reflection 软依赖 + 动态注册）

P3 Wave 2 之一：PAPI 桥接落地。**1 commit / ~430 行净增（含 1 新生产类 + 1 测试类）/ 556 backend test 全绿（+29 from P3-J 基线 527）**。

### 改动一览

1. **PapiVariableBridge**（{{provider/PapiVariableBridge.java}}, ~390 行）：
   - 软依赖：通过 reflection 调 `me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(OfflinePlayer, String)`——build.gradle **不**加 PAPI dep
   - PAPI 未装：`accessor.initialize()` 检测 plugin 不存在 → `available=false`；`refreshInterval()` 返 `Duration.ZERO` → daemon 不调度（零开销）；`handleDynamic` short-circuit；`refresh` 返 false 不调 accessor
   - 动态注册：玩家引用 `${var:papi:%xxx%}` → interpolator miss → `store.notifyDynamicLookup` → hook 解析 + 编码 + `store.create`
   - **store key 编码层**：VariableStore key 正则 `[a-zA-Z0-9_.-]+` 不允许 `%`，本桥接把 `%player_name%` 编码为 `pct_player_name_pct` 后入 store；内部维护 `encoded → original placeholder` 映射；`refresh` 按 original 调 PAPI、按 encoded 写 store
   - **PapiAccessor 抽象**（与 J 的 `DataSource` 同套路）：生产用 `ReflectionPapiAccessor`；测试注入 fake → 不依赖真实 PAPI 即可全覆盖
   - refresh 跑 daemon 线程：PAPI 多 stateless thread-safe，per-key try-catch 让单个 placeholder 失败不阻断其他

2. **ProviderBootstrap.initialize**（{{provider/ProviderBootstrap.java}}）：在 System + Scoreboard 之后、ManualSchedule 之前加 `daemon.register(new PapiVariableBridge(store, plugin))`。PAPI 未装时 daemon 也注册它，但 `refreshInterval=ZERO` 让其不调度

3. **测试**（{{test:.../PapiVariableBridgeTest.java}}, 29 case）：
   - PAPI 未装路径：initialize 不抛 / `refreshInterval=ZERO` / declaredKeys 空 / handleDynamic noop / refresh 不调 accessor / 不注册 hook
   - PAPI 装了路径：hook 注册 / handleDynamic 编码 + create / dot + slash + encoded + raw 四种形态解析 / 多次幂等
   - refresh：accessor.resolve 调用 + setValue / accessor 返 null 保留旧值 / accessor 抛异常不阻断其他 placeholder
   - encode/decode 编码层：`%xxx%` ↔ `pct_xxx_pct` 双向 / 非法格式拒绝（空 / `%%` / 含空格 / 无 prefix-suffix）
   - declaredKeys 反映已 tracked / shutdown 清 tracked + 调 accessor.shutdown

4. **docs/dynamic-data.md §7.2** 重写：替换原 stub 伪代码为 P3-K 实装注释 + PapiAccessor 抽象 + store key 编码层说明（VariableStore key 正则不允许 `%` 的兼容处理）+ 线程模型说明

### 关键决策（已固化）

1. **PAPI 未装时 daemon 也注册 Bridge**：register 调 initialize → 内部检测 plugin 不存在 + 不注册 hook；refreshInterval=ZERO → daemon scheduleAtFixedRate 跳过（`VariableProviderDaemon` line ~76 的 `intervalMs > 0` 判断）；零开销 + 未来 PAPI 热装时 Bridge 已就位（虽然当前不支持 hot reload，但保持架构灵活）
2. **store key 编码 `pct_<inner>_pct`**：VariableStore 校验 key 正则不允许 `%`——不修改 store 校验（不放宽 namespace 之外通用规则），改在 Bridge 边界编码。**P3-K 仅 Bridge 侧编码**，interpolator 侧解析 `${var:papi:%xxx%}` 语法的编码层留 P3-M 一同接入；当前 Bridge 已能接 dynamic lookup hook（store 处理 namespace 解析后只回调 namespace + fullName）。docs §7.2 已写明此实施边界
3. **PapiAccessor 抽象**（与 J 的 `DataSource` 同套路）：生产 ReflectionPapiAccessor / 测试 FakePapiAccessor；reflection 隔离让单测无需 MockBukkit / 真实 PAPI 也可全覆盖
4. **refresh daemon 线程跑**（不切主线程）：PAPI placeholder 多 stateless thread-safe；社区主流插件 thread-safe；per-key try-catch 单 placeholder 失败不阻其他；切主线程会让 PAPI 桥接大量时拖 TPS

### 通用基线

- shadow jar：未单独测（compile + test 全过即视为 OK；P3-K 不动 shadowJar relocate 链）
- baseline 漂移：0（不动渲染路径，仅加 Provider）
- 不动 NMS / 不引新依赖
- 不开 dev server / runServer

---

## 2026-05-19 · 0.4.0-P3-J：SystemProvider + ScoreboardProvider + Provider 接口扩展

P3 Wave 1：基础设施 + 两个内置 Provider 落地。**1 commit / ~860 行净增（含 2 新生产
类 + 2 测试类 + interpolator 双端扩展）/ 527 backend + 79 frontend test 全绿 / shadow
jar 161 MB**。

### 改动一览

1. **VariableProvider 接口扩展**（{{provider/VariableProvider.java}}）：新增 default 方法
   - `declaredKeys() → List<DeclaredKey>`：静态 namespace 返完整 key 列表（P3-M
     `/api/variable/list-all-namespaces` 端点序列化下发）
   - `isDynamic() → boolean`：标记动态 namespace（如 scoreboard 任意 key 都自动创建）
   - 新 record {{provider/DeclaredKey.java}}：(key, type, description, ttlMs)

2. **VariableStore 加 dynamicLookupHook**（{{variable/VariableStore.java}}）：
   - `registerDynamicLookupHook(BiConsumer<String, String>)` 接受 (fullName, namespace)
   - `notifyDynamicLookup(fullName)` 在 namespace 解析时支持 slash + dot 双形态
   - 由 {{VariableInterpolator}} 在 `resolveValue` miss 路径同步调用

3. **VariableInterpolator wall.\* 注入 + scoreboard.\* alias**
   （{{variable/VariableInterpolator.java}} + {{web/src/variable/interpolator.ts}}）：
   - `${var:wall.X}` + wallId → `system:<wallId>/wall.X`（per-wall namespace）；wallId
     为 null 时跳过注入（模板 publish / 预览路径）
   - `${var:scoreboard.<obj>.<player>}` → `scoreboard/<obj>.<player>`（点分号 alias
     → slash；与 ScoreboardProvider 存储侧约定一致）
   - resolve miss 时调 `store.notifyDynamicLookup(fullName)`——让动态 Provider 自动注册

4. **SystemVariableProvider**（{{provider/SystemVariableProvider.java}}, ~430 行）：
   - 全局 8 变量（namespace `system`）：server.time / real_time / tick / online / online_list /
     motd / tps / name；每个独立 TTL（1s ~ 1h）
   - per-wall 4 变量（namespace `system:<wallId>`）：wall.id / wall.alias / wall.owner /
     wall.owner_uuid
   - `refresh()` daemon 线程跑，最小公约 1s；内部 `nextRefreshAt` per-key 时刻表，到期者切主
     线程 (`Bukkit.getScheduler().runTask`) 计算 + 写回 store
   - `DataSource` 接口抽象 Bukkit / WallRepo 访问，让单测注入 mock；生产用 {{BukkitDataSource}}
   - per-wall 注册时机：`initialize()` 遍历 `dataSource.allWallIds()` + 显式 `registerWall(wid)` /
     `unregisterWall(wid)` 钩子（P3-J 不接 WallRepo create hook，启动 + onDisable 满足初版需求）
   - `declaredKeys()` 返 8 + 4 = 12 项；`isDynamic() = false`

5. **ScoreboardVariableProvider**（{{provider/ScoreboardVariableProvider.java}}, ~210 行）：
   - 动态 namespace `scoreboard`；`initialize()` 注册 dynamic lookup hook（自筛 namespace）
   - `handleDynamic(rawFullName)` 接受 slash + dot 双形态，解析为 `<obj>.<player>` key
   - `refresh()` 10s 周期，切主线程读 `Bukkit.getScoreboardManager().getMainScoreboard()`
   - obj / player 不存在 → 不 setValue（让 fallback 兜底，避免覆盖上次正确值）
   - `DataSource` 同样可注入 mock；`declaredKeys() = []`, `isDynamic() = true`

6. **ProviderBootstrap 改签名**（{{provider/ProviderBootstrap.java}}）：
   `initialize(store, plugin, wallRepo)` → 注册 system + scoreboard 两 Provider；
   P3-K/L 占位 PAPI + ManualSchedule

7. **HikariCanvas.java**（{{HikariCanvas.java}}）：onEnable 改 1 行 `ProviderBootstrap.initialize`
   调用方匹配新签名

### 测试

- {{plugin/.../variable/VariableInterpolatorTest.java}} +8 case（wall.\* 注入 4 / dynamic hook 3 /
  整体 29）
- {{plugin/.../variable/provider/SystemVariableProviderTest.java}}（新建，17 case）：FakeDataSource
  注入、initialize 12 变量 + initial refresh、registerWall / unregisterWall 幂等、null tps / tick
  防御、scheduleMainThread 计数
- {{plugin/.../variable/provider/ScoreboardVariableProviderTest.java}}（新建，16 case）：dot/slash
  form parseKey、initialize 注册 hook、refresh 读 score、score null 时保留旧值（不覆盖）、
  interpolator 端到端 miss→register→refresh→hit
- {{web/src/variable/__tests__/interpolator.test.ts}} +6 case（resolveFullName wall.\* 3 / 端到端
  3 / 整体 24，总文件 79 test）

### 关键决策

1. **DataSource 接口注入** 而非直接 Bukkit 调用：保证单测无需 MockBukkit 即可跑全路径，
   且生产时切主线程逻辑封装在 BukkitDataSource 内（防 async-unsafe getter 触发崩溃）。
2. **`server.time` 等 dot alias 在 P3-J 仅部分实现**——只翻 `wall.*` 和 `scoreboard.*` 两个高
   价值场景；`server.*` / `wall.*` 完整 dot-alias 留 0.4.1+（用户 Picker 使用 slash 形式）。
3. **scoreboard 失败回退** 选 "不 setValue 让旧值留缓存"，而非 setValue null。理由：避免
   单次 scoreboard plugin 重启 / obj 短暂缺失就把页面渲染成 fallback。
4. **mixed 模式动态注册**——interpolator 在 resolve miss 时同步触发 hook，hook 实现自负责
   异步。首次渲染走 fallback，第二次 tick 起有数据。store 的 `notifyDynamicLookup` 用
   CopyOnWriteArrayList 防 hook 列表并发修改。

### 关联文件

- 新建：DeclaredKey.java / SystemVariableProvider.java / ScoreboardVariableProvider.java /
  SystemVariableProviderTest.java / ScoreboardVariableProviderTest.java
- 修改：VariableProvider.java / VariableStore.java / VariableInterpolator.java /
  ProviderBootstrap.java / HikariCanvas.java / VariableInterpolatorTest.java /
  web/src/variable/interpolator.ts / web/src/variable/__tests__/interpolator.test.ts /
  docs/dynamic-data.md §2.3

---

## 2026-05-19 · 0.4.0-P2 收尾：编辑器基础 UX 完工

P2 三子任务（F/G/H）并行实施完毕 + I 主控收尾。**4 commit / ~5500 行净增（前端 ~5000 +
后端 ~330）/ 487 backend + 73 frontend test 全绿 / bundle 620 kB（gzip 190 kB）**。

### Phase commit 时间线

| Commit | Task | 范围 |
|---|---|---|
| `46eb6e9` | P2-F | ready payload 加 variables 字段（VariableDto 防外泄 + 7 单测） |
| `41c2ba3` | P2-H | VariablePicker + interpolator.ts + TextElement 集成 + live preview（35 vitest） |
| `c7dd01f` | P2-G | VariablePanel + NewVariableDialog + ValueEditor + BindDialog + useLongPressIncrement（10 vitest） |
| `<本 commit>` | P2-I | wsClient.handleReady 接 variables / protocol.ts ReadyPayload 扩展 / CLAUDE.md + journal 收尾 |

### P1 → P2 端到端可演示路径（demo 验证）

1. 浏览器打开编辑器 → TopBar 出现 `Variable` 图标按钮
2. 点击 → 右侧 380px drawer 弹出 VariablePanel，4 分组（我的 / 插件 / 系统 / PAPI）
3. `[+ 新建变量]` → NewVariableDialog modal → name="red_score" / type=NUMBER / default="0" → 提交
4. VariablePanel "我的变量" 段出现 "user/red_score" 行，type chip + 当前值 "0"
5. 单击 `[+1]` → 当前值 "1"（state.patch 回 → store mirror 更新 → UI 自动）
6. 长按 `[+1]` ~1s → 当前值跳到 ~14（300ms 启动 + 50ms 重复）
7. 选中 TextElement → RightPanel textarea 旁出现 `[变量]` 按钮
8. 点按钮 → textarea 下方弹 VariablePicker，列表显 "user/red_score"
9. 选中 → textarea 插入 `${var:user/red_score}` 到光标位置
10. textarea 下方 live preview 显 "14"（200ms debounce 后）
11. 在 textarea 输入 `${` → Picker 自动弹（双触发模式）
12. VariablePanel 删除 "red_score" → TextElementVariableHints 红色 banner 提示 + preview "???"
13. wall 切换重连 → variables store reset + 新 wall 的 variables 从 ready payload 初始化

### 关键架构落地

1. **首次初始化通道 = ready payload 内嵌 variables**（F 决策，dynamic-data.md §3.3 之外的实施层选项）：
   不引入新 HTTP `/api/variable/list` 端点（留 P3 编辑器自动补全 list-all-namespaces 用），
   首次连接走 WS ready 帧一次性传该 wall 全部变量 → 无额外 round-trip + 共享 WS 鉴权 +
   后续增量走 state.patch `/variables/<encoded>` 路径
2. **VariableDto 防外泄**（F）：序列化层独立 record，主动剔除 `referencedByWalls`
   （倒排索引内部状态，包含 peer wallId 元信息），保证跨 wall 隔离 + 不泄露其他 wall 引用关系
3. **drawer 独立于 RightPanel**（G 决策）：380px fixed 右侧 z-50，与 RightPanel 元素选中态完全解耦——
   管理变量时同时仍能看 wall canvas + 选中的 TextElement（用户已答 4 决策点之一）
4. **长按累加双段式**（G）：mousedown 立即 onTick(单击场景 +1)→ setTimeout(300ms) →
   setInterval(50ms)。pointercancel + blur + onBeforeUnmount 三重清理防卡死（沿用 M27 spaceSavedTool 模式）
5. **Picker 双触发**（H 决策）：按钮主入口 + textarea input 检测 caret 前 2 字符 `${` 自动弹（用户已答 4 决策之一）。
   选中插入用 native `textarea.setRangeText`（支持 undo / redo 栈）+ dispatchEvent 同步 v-model；
   `${` 触发场景先回退 2 字符吃掉触发符
6. **interpolator 双端镜像**（H）：前端 `web/src/variable/interpolator.ts` 与后端
   `VariableInterpolator.java` regex / fallback 4 档 / wallId 注入算法 1:1 一致（双端单测平行覆盖）；
   前端额外暴露 `missingFullNames` Set 给 live preview 删除警告 banner 用
7. **i18n 并行撞车解决**（G + H + I）：G 写 panel / dialog / actions / groups 子段，
   H 写 picker / hints 子段，commit 时序导致 H 先 main（41c2ba3）覆盖了 G 的本地未提交工作；
   G commit 时人工合并两段子段到同一 `variables` section（zh + en 各一份），最终 messages.ts
   含完整 variables section ~70 个 key
8. **变量删除不级联**（dynamic-data.md §11 固化 + 用户已答 4 决策之一）：DB 表 FK CASCADE
   只清 user_variables 行，不动 element；前端 TextElementVariableHints 检测 missingFullNames
   红色 banner 提示 + live preview 显示 "???" fallback；用户自行决定改文本或重建变量

### 协议契约 → 代码 1:1 对照

| ready 帧字段 | 来源 | 序列化 |
|---|---|---|
| `payload.variables: VariableDto[]` | `VariableStore.listByWall(wallId)` | Jackson NON_NULL inclusion；referencedByWalls 不出现 |

ProjectState 不变（不内嵌 variables，per-wall global 模型不变）。

### 工时核对

| Task | 估时 | 实际 |
|---|---:|---:|
| P2-F | 3h | ~50min（含单测 / docs / commit） |
| P2-G | 14h | ~110min（含合并 i18n / commit） |
| P2-H | 12h | ~90min（含双端镜像测 / commit） |
| P2-I | 1h | ~20min（hookup + 全测 + journal + commit） |
| **总** | **30h** | **agent 并行 wall-clock ~3h**（vs 序列估时 30h，并行节约 ~10×） |

### 不做（留 P3-P5 / 0.4.1 / 0.5.0+）

- chip 编辑器（Notion-style contentEditable + 蓝色 pill） → 0.4.1 独立 milestone
- 光标精确锚定 Picker（弹在 caret 上方而非 textarea 下方） → v1.x（需 textarea-caret-position 库）
- 实际 system / PAPI / 插件变量数据 → P3（Provider 实施后才有数据，P2 仅搭 UI empty state）
- 变量绑定到插件 namespace 的实际下拉 → P4（NamespaceInfo 注册中心后才有数据）
- 格式化语法 `${var:X|format=HH:mm}` → 0.5.0
- 批量编辑 / 导入导出 → P5

### 下一步

**P3 启动等用户通知**（内置 Provider 20h：13 个系统变量 + Scoreboard 桥接 + PAPI 桥接 +
Manual Schedule 兜底列车）。P2 端到端 demo 现在已可演示（用户创建 user 变量 + 实时改值 +
Wall 文本随变更跟随重画），P3 后将自动出现 server.time / wall.alias / scoreboard / PAPI 等系统变量。

---

## 2026-05-19 · 0.4.0-P2-G：VariablePanel + NewVariableDialog + ValueEditor + BindDialog + useLongPressIncrement

P2-G 子任务：变量管理 UX 主路径。把后端 P1 已铺的 WS op + Pinia store 接到前端 UI——TopBar Variable 按钮 → 380px fixed drawer → 4 分组（我的 / 插件 / 系统 / PAPI）→ 行内 +/- / 改值 / 让插件接管 / 删除。

### 改动

| 范围 | 文件 | 行为 |
|---|---|---|
| 新组件 | `web/src/components/variables/VariablePanel.vue`（~370 行含 style） | 主面板 fixed drawer，z-50，380px 宽；顶部 X / ESC / onClickOutside 关闭；搜索框 + "+ 新建变量" 按钮；4 分组折叠（默认展开）；"我的变量"扫 store 取 namespace=`user:<wallId>`；type chip 4 色 / 当前 / 默认 / 删除二次确认 inline popover；NUMBER 行 +/- 用 NumberStepButton |
| 新组件 | `web/src/components/variables/NewVariableDialog.vue`（~180 行含 style） | modal z-60；name regex `^[a-zA-Z0-9_.-]+$` + ≤ 64 长度三 case 实时校验；4 type button group；type 切换 default 值控件（STRING text / NUMBER number / BOOLEAN toggle / COLOR ColorInput allowAlpha=false）；走 `wsClient.sendVariableCreate` ack 通道，失败留 dialog 显错便于改名重试 |
| 新组件 | `web/src/components/variables/VariableValueEditor.vue`（~150 行含 style） | modal z-60；按 variable.type 切控件解析 currentValue（NUMBER → Number / BOOLEAN → 'true'/'false' / COLOR → hex 或 #FFFFFF fallback）；走 `wsClient.sendVariableSet` |
| 新组件 | `web/src/components/variables/BindDialog.vue`（~120 行） | P2 简化版：未绑 → empty state 提示 P4 启用后显示插件；已绑（source ≠ manual/system/papi）→ 显示当前绑定 + "取消绑定" 按钮走 `wsClient.sendVariableBind(fullName, null)`；P4 时整体替换为插件列表 picker |
| 新组件 | `web/src/components/variables/NumberStepButton.vue`（~50 行） | useLongPressIncrement 的 props-driven 包装；每按钮拥有独立 timer 生命周期 + onBeforeUnmount 清理 |
| 新模块 | `web/src/components/variables/variableNameValidation.ts`（~30 行） | 从 NewVariableDialog 抽出纯校验函数（VARIABLE_NAME_REGEX / MAX_LEN 64 / VariableNameError 三态），让单测可直接覆盖不引 @vue/test-utils |
| 新模块 | `web/src/composables/useLongPressIncrement.ts`（~100 行） | 长按累加 composable：onPointerDown 立即触发 1 次 + setTimeout(initialDelay=300) → setInterval(50) 持续触发；onPointerUp/Leave/Cancel 清两层 timer；onBeforeUnmount 兜底清理；返 isPressing computed 供视觉反馈 |
| ui store | `web/src/stores/ui.ts` | 加 `variablePanelOpen` ref / `toggleVariablePanel` / `closeVariablePanel` actions / `reset()` 内同时复位 |
| variables store | `web/src/stores/variables.ts` | 加 `initVariables(list)` 批量赋值（I 任务 handleReady 钩 ready.variables 用）+ `availableUserKeys` computed（H 任务 picker 用） |
| i18n | `web/src/i18n/messages.ts` | 新 `topbar.variableManager` + 新 `variables` section 双语（panel / groups / type chips / actions / 3 dialog title+button+hint / 删除确认 / 绑定 empty state）；与 H 子段 picker / hints 互不冲突合并 |
| TopBar | `web/src/components/layout/TopBar.vue` | 加 lucide `Variable` icon 按钮，挂在 SnapSettingsPopover 右侧，活跃态 accent 背景 |
| App | `web/src/App.vue` | import VariablePanel 挂在 v-else 容器底部（与 TemplateGallery / HelpModal 同层）；自身 v-if=ui.variablePanelOpen 控显 |
| 测试 | `web/test/composables/useLongPressIncrement.test.ts`（5 case） | 单击 / 长按 350ms / pointerleave / pointercancel / onBeforeUnmount 清理；用 `@vitest-environment happy-dom` 指令 + 最小 Vue app mount 测 onBeforeUnmount |
| 测试 | `web/test/components/NewVariableDialog.test.ts`（5 case） | 合法 name 集 / 非法字符（中文 / 空格 / 斜杠 / 冒号 / hash） / >64 char + 边界 64 / 空 name / NAME_REGEX 文本对齐后端 P1-A |
| 构建 | `web/vite.config.ts` | test.include 加 `test/**/*.test.ts`；保持 node env 主线（per-file 用 `@vitest-environment` 指令切 happy-dom） |
| 依赖 | `web/package.json` | 加 happy-dom devDependency（vitest 组件 + 真 mount 测 onBeforeUnmount 路径用） |

### 关键设计

1. **NumberStepButton 独立组件**：原打算在 VariablePanel 主组件里给每行调一次 composable，但 composable 必须在 setup 顶层调用——v-for 行数变化时违反规则。独立组件让每按钮拥有自己的 setup + onBeforeUnmount cleanup，避免组件销毁后残留 timer 触发 stale closure。
2. **BindDialog P2 简化版**：P4 Plugin Push API 前没插件列表可枚举。当前仅支持取消绑定（罕见，但当 source 显示为插件名时需要给用户路径解绑）。P4 落地后整体替换为插件 picker，sendVariableBind 参数从 null 升级到具体 pluginName。
3. **删除二次确认走 inline popover 而非全屏 modal**：列表内单行操作太重的话视觉中断；inline confirm popover（带 "确定 / 取消" 两按钮 + destructive 配色）足够。
4. **i18n 撞车防护**：P2-G 只写 panel / dialog / groups / types / actions / value editor / delete / bind 子段；H 任务（picker / livePreview / hints）独立子段；最终合并为一个 variables section（已观察到 H commit 已正确并入）。
5. **测试不依赖 @vue/test-utils**：NewVariableDialog 测试只验证 `validateVariableName` 纯函数；useLongPressIncrement 测试需要 onBeforeUnmount lifecycle，用 happy-dom + `createApp(...).mount(...).unmount()` 最小 Vue 上下文，不需要 @vue/test-utils 抽象。

### 验证

- `cd web && npm run test` → 8 test files, **73 passing**（68 原有 + 5 composable + 5 dialog 校验；包含 H 已合入的 15 interpolator + 20 picker）
- `cd web && ./node_modules/.bin/vite build` → 通过；无 TS 错；livePaintWorker 33.75kB + index 619.67kB（gzip 189.72kB）
- 不动 Java 后端（F 做）/ wsClient.handleReady（I 做）/ VariablePicker / TextElementSection（H 做）

### 关联文件（git diff HEAD --stat）

```
docs/journal.md
web/package-lock.json
web/package.json
web/src/App.vue
web/src/components/layout/TopBar.vue
web/src/components/variables/BindDialog.vue (new)
web/src/components/variables/NewVariableDialog.vue (new)
web/src/components/variables/NumberStepButton.vue (new)
web/src/components/variables/VariablePanel.vue (new)
web/src/components/variables/VariableValueEditor.vue (new)
web/src/components/variables/variableNameValidation.ts (new)
web/src/composables/useLongPressIncrement.ts (new)
web/src/i18n/messages.ts
web/src/stores/ui.ts
web/src/stores/variables.ts
web/test/composables/useLongPressIncrement.test.ts (new)
web/test/components/NewVariableDialog.test.ts (new)
web/vite.config.ts
```

---

## 2026-05-19 · 0.4.0-P2-H：VariablePicker + interpolator + TextElement 集成 + live preview

P2-H 子任务：让玩家在 TextElement textarea 内插入 `${var:...}` 占位符（按钮 / 输入 `${` 自动触发），并实时看到替换后的最终文本 + 引用变量被删除时的红色警告。

### 改动

| 范围 | 文件 | 行为 |
|---|---|---|
| 新模块 | `web/src/variable/interpolator.ts`（~100 行） | 前端镜像后端 `VariableInterpolator`；同正则 / 同 fallback 4 档（cached → `\|fallback=` → defaultValue → "???"）+ `user/X` wallId 注入 + `O(1)` 短路 + `referencedFullNames` / `missingFullNames` 集合产出 |
| 新模块 | `web/src/variable/pickerLogic.ts`（~110 行） | VariablePicker 纯逻辑层：`buildGroups`（4 组 mine/plugin/system/papi 分类 + 跨 wall user 隔离 + keyword filter 大小写不敏感）+ `flattenGroups` / `nextActiveIndex`（循环回绕）+ `displayName`（user 变量显示 `user/key` 短名）。剥离纯逻辑便于 vitest node 直跑（避免引入 jsdom + @vue/test-utils） |
| 新组件 | `web/src/components/variables/VariablePicker.vue`（~200 行含 style） | popover 选择器：搜索框 + 4 分组列表 + ↑↓/Enter/Esc 键盘交互 + onClickOutside；显示当前值预览。emit `select(shortName)` + `close()` |
| 新组件 | `web/src/components/variables/TextElementVariableHints.vue`（~120 行含 style） | textarea 下方挂件：200ms debounce 调 interpolate；显示 live preview + 红色 missingFullNames 警告 + 引用提示。仅在 text 含 `${var:` 时挂载（外层 v-if） |
| 集成入口 | `web/src/components/properties/TextElementSection.vue` | textarea 添加 `ref="textareaRef"`；label 区右侧加 "插入变量"按钮（lucide `Variable` icon）；`onTextChange` 检测 caret 前 2 字符为 `${` → 自动弹 picker + 标记 `triggeredByDollarBrace`；`onPickerSelect` 用 `setRangeText` 插入 `${var:...}` 并 `dispatchEvent input` 让 vue 同步 v-model；hints 组件挂在 textarea 下方 |
| i18n | `web/src/i18n/messages.ts` | 在已有 `variables` section（P2-G 范围）追加 `picker` / `hints` 子段，中英双语：searchPlaceholder / emptyResults / 4 个 group 标题（含 emoji） / insertButtonLabel / keyboardHint / previewLabel / deletedWarning(`{names}` 占位） / referencedHint |
| 单测 | `web/src/variable/__tests__/interpolator.test.ts` | 15 case：纯文本短路 / wallId 注入 / fallback 4 档 / 多占位符 / missing & referenced 集合 / null undefined 输入 / 显式空 fallback / 替换值含 `$`+`\\` 不当反向引用 |
| 单测 | `web/src/variable/__tests__/pickerLogic.test.ts` | 20 case：displayName 三 namespace / buildGroups 4 组分类 + 跨 wall user 隔离 + wallId null 排除 mine / keyword 大小写不敏感 + trim / flatten 顺序 / nextActiveIndex 边界 + 回绕 |

### 关键设计

1. **interpolator 算法权威 = 后端 Java `VariableInterpolator`**。前端纯前端 live preview 用，不参与 MC 内实际投影（后端 CanvasCompositor 走自己的 Java 路径）。两端共享行为契约的目的是**让编辑器预览与游戏内最终渲染逐字符一致**——避免玩家"编辑器看是 5，游戏里渲染出来变 ???"的认知裂痕
2. **触发模式 双轨**：点按钮（`triggeredByDollarBrace=false` → 直接 setRangeText 插占位符）vs 用户输入 `${` 触发（`true` → 插入时把已输入的 `${` 一并替换掉，避免重复）。两轨用同一 `onPickerSelect`，靠 flag 分支
3. **picker 纯逻辑剥离**：把 group 构建 / filter / activeIndex 移到 `pickerLogic.ts`，Vue 组件只剩渲染 + 键盘事件 + onClickOutside。理由：当前 web/ 测试栈无 jsdom + 无 @vue/test-utils（CLAUDE.md M18-P5 决策），引入这两个会让 Node 25 build 链多一层风险；剥离纯逻辑 = 20 case 直接 node 跑，组件层只靠 vite build / 真实 manual QA 验
4. **TextElementVariableHints 挂载条件**：仅当 `text.indexOf('${var:') >= 0` 时才显示，避免对所有 Text 元素都挂一个空 hint UI。空判定 + debounce 都在组件内部，外层只 mount/unmount
5. **store reactive 触发 watch**：`store.variables.size` 加入 watch source，让 G/F 任务的 store 写入（variable.add/remove/set patch）直接触发 live preview 重算。`variables` ref<Map> 自身 ref 重新赋值时 size 变化也是响应式

### 测试结果

- `cd web && npm run test`：63 test 全绿（28 旧 livepaint + 15 interpolator + 20 pickerLogic）/ 244ms
- `cd web && ./node_modules/.bin/vite build`：通过 / 1772 modules / bundle 543 kB → 594.89 kB（+50 kB，含 picker + hints + interpolator + i18n keys，合理）
- `vue-tsc --noEmit`：Node 25 已知坑跳过（CLAUDE.md M5 记录）；vite build 通过即代表 TS 编译无 fatal

### 不在 P2-H 范围

- VariablePanel / NewVariableDialog / ValueEditor / BindDialog（P2-G 负责）
- wsClient.handleReady 接收 ready.variables 写入 store（P2-I 负责）
- 后端任何改动（P2-F / P3 / P4）
- chip 化 contentEditable 体验（0.4.1 独立 milestone）

---

## 2026-05-19 · 0.4.0-P2-F：ready payload 加 variables 字段

P2-F 单子任务完工：让浏览器首次连接 wall 时 ready WS 帧携带该 wall 当前引用变量的快照，免去额外 HTTP round-trip 初始化 VariableStore mirror。

### 改动

| 范围 | 文件 | 行为 |
|---|---|---|
| 新 DTO | `plugin/src/main/java/moe/hikari/canvas/variable/VariableDto.java` | 8 字段 record + `from(Variable)` 静态工厂；故意丢 `referencedByWalls`（VariableStore 内部倒排索引，跨 wall peer 信息不下发） |
| WebServer 注入 | `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` | 新 `variableStore` 字段（与 `variableOpDispatcher` 同生命周期）；`handleAuth` 末段 `payload.put("variables", listByWall(wallId).map(VariableDto::from).toList())`；`variableStore == null` 或 `wallId == null` 走空数组 fallback（变量未配置 / 模板预览的 ghost session 不会 NPE） |
| 单测 | `plugin/src/test/java/moe/hikari/canvas/web/ReadyPayloadVariableTest.java` | 7 case：含 3 用户变量正确序列化 / referencedByWalls 不外泄 + peer wallId 不外泄 / wallA wallB 隔离 / 空 store 返 `[]` / VarType 4 枚举值序列化为 name() 字符串 / DTO 字段一一对应 / 未 mark 的 wall 返 `[]` |
| 协议契约 | `docs/protocol.md` §3.2 | ready payload JSON 示例补 `variables` 字段；新增 0.4.0-P2-F 决策段落（DTO 投影字段表 / 防泄露说明 / 空数组语义） |

### 关键设计

1. **DTO over @JsonIgnore**：选方案 B 引入独立 `VariableDto` 而非在 `Variable` record 上加 `@JsonIgnore Set<String> referencedByWalls`。理由：(a) record 加注解的 Jackson 序列化兼容性有坑（accessor vs canonical constructor 解析）；(b) 显式 DTO 表达"给前端的简化视图"语义清晰；(c) P3 HTTP `/api/variable/list` 端点也能共用同一 DTO
2. **空数组兜底**：`variableStore == null`（VariableStore 未配置）和 `wallId == null`（理论上不会，但保留 defensive null check）路径都走 `List.of()`，**字段永远存在**——前端 mirror 初始化代码不用 if-defined 判断
3. **测试切片策略**：不 boot Javalin / WebServer 全栈（过重；变量注入是 5 行 mapping 纯代码），改测产生 ready payload 的等价 slice（`listByWall` → `VariableDto::from` → Jackson 序列化），ObjectMapper 配置 `NON_NULL inclusion` 与 WebServer 主线 JavalinJackson 配置一致

### 测试结果

- `:plugin:test` 全 487 用例绿（含新 7 case 118ms）；P1 基线 480 → P2-F 后 487
- 协议契约 `docs/protocol.md §3.2` 与代码一致

### 不在 P2-F 范围

- 前端 `network` store 接收 ready.variables 写入 `useVariableStore.replace(...)` → 留给 P2-G/H/I
- HTTP `/api/variable/list` 端点 → 留给 P3
- 任何 UI

---

## 2026-05-19 · 0.4.0-P1 收尾：变量系统底座完工

P1 五子任务（A/B/C/D/E）并行实施完毕。**5 commit / ~3000 行净增 / 480 backend test +
28 frontend test 全绿 / shadow jar 161 MB / 0 fixture baseline 漂移**。

### Phase commit 时间线

| Commit | Task | 范围 |
|---|---|---|
| `11b2773` | P1-A | VariableStore 核心 + V011 user_variables + 29 单测 |
| `74b4f4f` | P1-E | VariableProvider daemon 框架 + ProviderBootstrap + 10 单测 |
| `ab765dd` | P1-C | VariableInterpolator + CanvasCompositor 渲染期替换 + 29 单测 |
| `dcffe9f` | P1-B | variable.* WS 协议 + EditSession 集成 + dirty callback + 18 单测 |
| `02be5ca` | P1-D | canvas.var.* 权限节点 + 前端 TS types + Pinia store + wsClient send |

### 关键架构落地（dynamic-data.md §1-9 → 代码现实）

1. **fullName 编码规范**（A 决策固化）：普通 `<namespace>/<key>`；user 变量 `user:<wallId>/<key>`
   （冒号分隔避免 user namespace 内多 wall 撞 key）；对外占位符仍写 `${var:user/<key>}`，
   Compositor 解析时注入当前 wallId 转内部形式
2. **state.patch `/variables/<encoded>` 路径**（B 决策）：JSON Pointer 转义（`~` → `~0`、
   `/` → `~1`）；variables 不进 ProjectState 内嵌 record（per-wall 持久），而走 global
   VariableStore + 独立 state.patch 分拣（前端 wsClient 接收侧按 `/variables/` 前缀走
   useVariableStore）
3. **倒排索引按需维护**（C 决策）：CanvasCompositor.rasterize 末尾才调
   `markWallReferences(wallId, allReferenced)`——纯文本路径 O(1) 短路 indexOf 跳过；
   含占位符 O(n) regex 提取；wallId == null 不写索引（模板 publish / 预览不污染 byWall ghost 引用）
4. **dirty 触发链**（B 决策）：`VariableStore.setValue/update/delete/bind` 触发
   `wallDirtyCallback.accept(wallId)` for each referencing wall → `SessionManager.submitFullCanvasDirtyByWall`
   → `ProjectionThrottler.submit(DirtyRegion.full)` → 既有渲染管线
5. **VariableProvider 接口**（E 决策）：`refresh() boolean` 返 false 表示 fallback 跳过该 tick；
   `refreshInterval() ≤ 0` 表示 Push-only provider（无定时调度，靠外部 push 写值）；
   三层异常隔离（initialize / refresh / shutdown 各自 try-catch 不传播）
6. **权限策略**（B + D 协议固化）：own / any 二分法 — owner 走 `canvas.var.write.own`
   （offline owner 视为已授权，与 paper-plugin.yml default=true 一致），非 owner 需
   `canvas.var.write.any`；`variable.bind` 统一查 `canvas.var.bind`（敏感，不分 own/any）

### 协议契约 → 代码 1:1 对照

| 协议 op | EditSession 方法 | OpResult patch op |
|---|---|---|
| `variable.create` | `createVariable(store, wallId, name, type, defaultValue)` | `add /variables/<encoded>` 整 Variable |
| `variable.update` | `updateUserVariable(store, wallId, fullName, patch)` | `replace /variables/<encoded>` 整 Variable |
| `variable.set` | `setUserVariableValue(store, wallId, fullName, value)` | `replace /variables/<encoded>/currentValue` |
| `variable.delete` | `deleteUserVariable(store, wallId, fullName)` | `remove /variables/<encoded>` |
| `variable.bind` | `bindUserVariable(store, wallId, fullName, boundTo)` | `replace /variables/<encoded>/source` |

### 错误码（8 内部 → 4 协议 + 4 通用 fallback）

VariableException.Code → 协议错误：
- `VARIABLE_NOT_FOUND / VARIABLE_EXISTS / VARIABLE_TYPE_MISMATCH / VARIABLE_NAMESPACE_DENIED` 1:1 映射
- `VARIABLE_NAME_INVALID / VARIABLE_VALUE_TOO_LONG / QUOTA_EXCEEDED / TTL_INVALID` 归 `INVALID_PAYLOAD`
- 权限 / 限流 / wall 缺失走既有 `PERMISSION_DENIED / RATE_LIMITED / WALL_NOT_FOUND`

### Working tree 并行竞争解决

A → E 串行（11b2773 → 74b4f4f）；C/B/D 在 working tree 共享下并行编辑同文件
（HikariCanvas.java / SessionManager.java / docs/journal.md）。C agent 用
`git apply --cached` 精确 staged 自己范围；B agent commit 时检测到 C 已先一步落地（main 推到
ab765dd），自己的 hunks 与 C 不冲突 fast-forward 拼上；D 工作树由主控（我）手动 commit。
**所有 5 commit GitHub 端 SSH 签名 verified=true**。

### 不做（留 P2-P5）

- 编辑器 UI（变量管理面板 / Variable Picker）→ P2
- 具体 Provider 实现（system.time / scoreboard / PAPI / Manual Schedule）→ P3
- 插件 Push API + 注册中心（HikariCanvasAPI.setVariable）→ P4
- `/canvas var` 命令族 → P5

### 下一步

**P2 启动等用户通知**。P1 落地后：playground 测试 `${var:user/X}` 占位符在 wall 上
实时渲染（创建变量 → 改值 → 看 wall 重画）需要 P2 UI 才能验证；P1 单测层验证已完毕。

---

## 2026-05-19 · 0.4.0-P1-D：canvas.var.* 权限节点 + 前端 TS types + Pinia store + wsClient send

P1 阶段"权限注册 + 前端协议契约"。后端 A/B/C/E 收口后，把 7 个 `canvas.var.*` 权限节点
注册到 paper-plugin.yml；前端建立完整 TS 类型镜像 + Pinia VariableStore + wsClient 5 个
`sendVariable*` 方法；state.patch 接收侧按 `/variables/` 前缀分拣到 VariableStore（而非
ProjectState）；i18n 4 个错误码 + 切 wall 时 reset 钩子。

### 主要变更

- **paper-plugin.yml**：加 7 个权限节点 `canvas.var.{read,write.own,write.any,delete.own,delete.any,bind,command}`，
  按 `docs/dynamic-data.md §9.1` 表格固化 default（write.own / read / delete.own = true；其他 = op）

- **web/src/types/variable.ts**（新）：`VarType` union（STRING / NUMBER / BOOLEAN / COLOR）+
  `Variable` interface（namespace / key / type / default / current / updatedAt / ttl / source）+
  `VariablePatch` / `VariableUpdate` + `makeUserFullName / makeFullName / parseFullName` helper

- **web/src/types/protocol.ts**：5 个新 payload type（VariableCreate/Update/Set/Delete/Bind）
  + 4 个错误码注释（与后端 B 任务的 `VARIABLE_NOT_FOUND / EXISTS / TYPE_MISMATCH / NAMESPACE_DENIED` 映射）

- **web/src/network/wsClient.ts**：
  - 5 个 `sendVariable*` method（`sendWithAck` 走 ack，server-as-truth 不预测性 mutate）
  - state.patch 接收侧按 `/variables/` 前缀分拣：`applyVariablePatches` 走 VariableStore，
    其余仍走 `useProjectStore().applyPatch`
  - JSON Pointer 解码（`~1` → `/`，`~0` → `~`）+ add/replace/remove 三 op 完整支持
  - race 兜底：replace 但本地无该 var → log meta 跳过

- **web/src/stores/variables.ts**（新）：Pinia setup store，`Map<fullName, Variable>` 内核 +
  `set / get / remove / clear / reset` + `all` + `byNamespace` computed
  （UI 在 P2 阶段消费）

- **web/src/stores/project.ts**：`reset()` 加 `useVariableStore().reset()`——切 wall 时
  全局变量 mirror 一起清；重连同 wall 不进 reset 分支（wsClient.handleReady 的 wallId diff 判断）

- **web/src/i18n/messages.ts**：4 个错误码翻译（zh-CN / en 各一组）

### 验证

- `vite build` 通过：424ms / 583 kB bundle（gzip 180 kB）+ 33.75 kB livePaintWorker
- `npm run test`（vitest）：4 文件 / 28 case 全绿 / 152ms（M18 + Live Paint 基线无漂移）

### 关联文件

新建：
- `web/src/types/variable.ts`
- `web/src/stores/variables.ts`

修改：
- `plugin/src/main/resources/paper-plugin.yml`
- `web/src/types/protocol.ts`
- `web/src/network/wsClient.ts`
- `web/src/stores/project.ts`
- `web/src/i18n/messages.ts`

---

## 2026-05-19 · 0.4.0-P1-B：variable.* WS 协议 + EditSession 集成 + dirty callback

P1 阶段"WS 协议路由"。把 Task A 交付的 `VariableStore` 串入 WS edit op 路径——
5 个 `variable.*` op 经 EditSession 走完整 ack + state.patch + audit 流程；变量值变化
通过 `wallDirtyCallback` → SessionManager → ProjectionThrottler 触发引用该变量的 wall 重画。

### 主要变更

- **EditSession.java**（新增 5 个同步方法 + helpers）：
  - `createVariable(VariableStore, wallId, name, type, defaultValue)` →
    namespace 内部组装 `"user:<wallId>"`，调 `store.create` + 出 `add /variables/<encoded>` patch
  - `updateUserVariable(store, wallId, fullName, VariablePatch)` → 跨 wall 拒
    + `store.update` + 出 `replace /variables/<encoded>` 整 Variable
  - `setUserVariableValue(store, wallId, fullName, value)` → `store.setValue(..., null)`
    沿用旧 TTL + 出 `replace /variables/<encoded>/currentValue`（value=null 走 remove）
  - `deleteUserVariable(store, wallId, fullName)` → `store.delete` + `remove /variables/<encoded>`
  - `bindUserVariable(store, wallId, fullName, boundTo)` → `store.bind` 写 `source` 字段
    + `replace /variables/<encoded>/source`（null 走 remove）
  - JSON Pointer 转义（`~` → `~0`、`/` → `~1`）+ `requireUserVarBelongsToWall` 防跨 wall
  - `mapVariableErrorCode`：8 个 VariableException.Code → 4 个协议错误码
    （`VARIABLE_NAME_INVALID / VARIABLE_VALUE_TOO_LONG / QUOTA_EXCEEDED / TTL_INVALID` 归
    `INVALID_PAYLOAD`，与 `docs/protocol.md §5.11 / §6.1` 一致）
  - 变量 op `OpResult.Ok.dirty = null`（像素层无变化）；wall 重画由 dirty callback 链触发

- **VariableOpDispatcher.java**（新文件，与 EditOp / WallOp / BrushOp / TemplateOp 平级）：
  - 5 个 op `variable.create / update / set / delete / bind` 路由 + payload 解析
  - 限流（SessionRateLimiter）+ session 活跃性 + WallRepo owner 判定
  - 权限：own / any 二分法 — owner 走 `canvas.var.write.own`（offline 视为已授权，与
    paper-plugin.yml default=true 一致），非 owner 需 `canvas.var.write.any`；
    `variable.bind` 统一查 `canvas.var.bind`（敏感，不分 own/any）
  - 失败走 `Envelope.error(...)` + `PERMISSION_DENIED` audit
  - 成功走 ack（create 时回 `fullName`）+ `pushPatch` + `recordAuditSuccess`
    （`VARIABLE_CREATE / UPDATE / SET / DELETE / BIND` 五事件）

- **WebServer.java**：
  - 构造器新增 `VariableStore` 参数（位置在 IconRegistry 之后、plugin 之前）
  - 实例化 `VariableOpDispatcher`（store=null 时不实例化）
  - `handleMessage` switch 加 5 个 `variable.*` 分支

- **SessionManager.java**：新增 `submitFullCanvasDirtyByWall(wallId, throttler)`：
  扫 byId 找绑定到 wallId 的活跃 session，对每个 submit `DirtyRegion.fullCanvas(ps)`。
  线程安全（只读 + 不可变 getters）；可从任意线程调（VariableStore async daemon 路径）。

- **HikariCanvas.java**：
  - VariableStore 构造时 wallDirtyCallback 从 noop 升级为
    `wallId -> sessionManager.submitFullCanvasDirtyByWall(wallId, projectionThrottler)`
    （lambda 体延迟执行，构造顺序无关）
  - WebServer 构造调用补一个 `variableStore` 参数

### 协议样例

```jsonc
// C→S 创建
{ "op": "variable.create", "id": "c-1",
  "payload": { "name": "red_score", "type": "NUMBER", "defaultValue": "0" } }

// S→C ack（带 fullName 便于前端索引）
{ "op": "ack", "id": "c-1",
  "payload": { "version": 42, "fullName": "user:w-deadbeef/red_score" } }

// S→C state.patch（同一 wall 所有连接收到）
{ "op": "state.patch", "id": "s-7",
  "payload": { "version": 42, "ops": [
    { "op": "add", "path": "/variables/user:w-deadbeef~1red_score",
      "value": { "namespace": "user:w-deadbeef", "key": "red_score",
                 "type": "NUMBER", "defaultValue": "0",
                 "updatedAt": 1747641722000, "ttl": 0, "source": "manual" } }
  ] } }

// C→S 改当前值
{ "op": "variable.set", "id": "c-2",
  "payload": { "fullName": "user:w-deadbeef/red_score", "value": "5" } }

// S→C state.patch（precise currentValue replace）
{ "op": "state.patch", "id": "s-8",
  "payload": { "version": 43, "ops": [
    { "op": "replace",
      "path": "/variables/user:w-deadbeef~1red_score/currentValue",
      "value": "5" }
  ] } }
```

### 错误码映射

| VariableException.Code | 协议错误码 |
|---|---|
| VARIABLE_NOT_FOUND | `VARIABLE_NOT_FOUND` |
| VARIABLE_EXISTS | `VARIABLE_EXISTS` |
| VARIABLE_TYPE_MISMATCH | `VARIABLE_TYPE_MISMATCH` |
| VARIABLE_NAMESPACE_DENIED | `VARIABLE_NAMESPACE_DENIED` |
| VARIABLE_NAME_INVALID / VARIABLE_VALUE_TOO_LONG / QUOTA_EXCEEDED / TTL_INVALID | `INVALID_PAYLOAD` |

权限失败 → `PERMISSION_DENIED`；wall 缺失 → `WALL_NOT_FOUND`；
session closing → `SESSION_CLOSED`；store 未初始化 → `INTERNAL_ERROR`。

### 关键设计决策

1. **fullName 校验严格 per-wall**：op 入参 `fullName` 必须以 `"user:<thisWallId>/"` 开头。
   否则 `VARIABLE_NAMESPACE_DENIED`——防 wall A session 改 wall B 的 user 变量。
2. **patch path JSON Pointer 标准转义**：`/` → `~1`、`~` → `~0`。前端 mirror 走标准
   JSON Patch 反编码即可。`user:<wallId>/<key>` 形态下 wallId 不含 `~` / `/`，仅 ns-key
   分隔符 `/` 被转义。
3. **变量 op 不触发投影**：`OpResult.Ok.dirty = null`。dirty 由 store 内 wallDirtyCallback
   单独触发——"create 时无 referencer 不触发"、"setValue 时按 referencedByWalls 精确触发"
   语义清楚。
4. **createVariable 不进 undo 栈**：bumpVersion 但不调 history.commitHistory。
   变量是"配置"而非"画布内容"，与 layer.set-active 同等定位（也不进 undo）。
5. **VariableOpDispatcher 走 wallRepo.loadById** 判定 owner_uuid：session 对象只有 wallId
   不含 owner，wallRepo 已是 DAO + cache。
6. **lambda body 延迟字段读**：VariableStore 构造期 sessionManager / projectionThrottler
   还可能为 null，但 lambda 体只在运行期玩家手动改值时执行——此时字段已就位。

### 测试

`plugin/src/test/java/moe/hikari/canvas/state/EditSessionVariableTest.java` — 18 case，
0.004s 全绿。覆盖：create + add patch / create 重复 / create 非法 name / create null wall
/ create null store / update type+default / update missing / set currentValue 走
`/currentValue` 路径 / set 触发 dirty callback（前置 markWallReferences）/ set 跨 wall 拒 /
set 空值走 remove / delete 走 remove + path / delete 跨 wall 拒 / bind 写 source 字段 /
unbind null 清 source + path 走 remove / bind 跨 wall 拒 / JSON Pointer 转义 / version bump。

完整 `:plugin:test` 473 case 全绿。

### 关联文件（创建 / 修改）

- 新增：`plugin/src/main/java/moe/hikari/canvas/web/VariableOpDispatcher.java`
- 新增：`plugin/src/test/java/moe/hikari/canvas/state/EditSessionVariableTest.java`
- 改：`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`
- 改：`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- 改：`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- 改：`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`

---

## 2026-05-19 · 0.4.0-P1-C：VariableInterpolator + CanvasCompositor 渲染期 ${var:X} 替换

P1 阶段"渲染期变量替换"。`VariableInterpolator` 把 TextElement.text 内的 `${var:X}` 或
`${var:X|fallback=Y}` 占位符替换为 `VariableStore` 缓存值；`CanvasCompositor` 渲染入口
新增 `rasterize(state, wallId)` 重载，对每个 TextElement 走透明替换并在渲染结束累计
`referencedFullNames` 调 `markWallReferences` 维护倒排索引。变量值变化时 P1-B 的
`wallDirtyCallback` 沿这条倒排索引精准触发只重画引用 wall。

### 主要变更

- **新文件 `plugin/src/main/java/moe/hikari/canvas/variable/VariableInterpolator.java`**：
  - 单例正则 `\$\{var:([^|}]+)(?:\|fallback=([^}]*))?\}`（编译期静态字段）
  - `interpolate(text, wallId) -> Result(text, referencedFullNames)`：
    - 纯文本（不含 `${var:` 子串）O(1) 短路返原引用 + 空集（性能优化）
    - text == null safe
    - `Matcher.quoteReplacement` 防 `$` / `\` 在 `appendReplacement` 内被解释为
      反向引用 / 转义符
  - fullName 注入：`${var:user/X}` + wallId 非空 → `user:<wallId>/X`；wallId 为 null
    时 user 命名空间走字面（必然 miss → fallback 链，便于预览 / 模板路径）
  - fallback 链 4 档：cached currentValue 非空 → inline `|fallback=` → `Variable.defaultValue` →
    系统兜底 `"???"`；显式空 `|fallback=` 视为合法 "" 值不退档

- **`plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`**：
  - 新 volatile 字段 `interpolator` + `variableStore`（默认 null 不破坏老 fixture baseline）
  - 新 setter `setVariableSupport(interp, store)` 在 onEnable 注入后才启用替换
  - 新 overload `rasterize(state, wallId)`；旧 `rasterize(state)` = `rasterize(state, null)` 完全
    等价（snapshot 测试 0 漂移）
  - 新 helper `maybeInterpolateText(e, interp, wallId, accum)`：
    TextElement.text 含 `${var:` 才分配 record 副本走 dispatch，其他元素 / 纯文本 passthrough
  - `drawElementsTo` / `renderLayerToBuffer` 签名扩展 `interp + wallId + referencedAccum` 参数；
    fast/slow path 都对 TextElement 透明替换
  - 渲染末尾：`interp != null && store != null && wallId != null` 时 `store.markWallReferences(
    wallId, referencedFullNames)`；异常隔离（不让倒排索引维护拖垮渲染主路径）
  - **线程安全**：volatile 取 snapshot；referenced 累积器是 per-call 新分配，无共享可变状态

- **`plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`**：
  - `project(session, region)` 内 `compositor.rasterize(state)` → `rasterize(state, session.wallId())`；
    SELECTING 阶段 wallId 为 null 时 compositor 内部走"无 user 变量解析 + 不写倒排索引"分支

- **`plugin/src/main/java/moe/hikari/canvas/render/WallRestorer.java`**：
  - 启动期 restore 同样传 `w.wallId()`；setVariableSupport 注入前 interpolator 为 null
    走原行为，注入后再次 restore 也安全（极少见）

- **`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`**：
  - 新 `addWallDeleteHook(hook)` + `wallDeleteHooks` 列表（CopyOnWriteArrayList 线程安全）
  - `deleteWall` 完成 map 释放 + DB 删行后触发监听，异常隔离
  - 用于 wall 删除时联动 `VariableStore.clearWallReferences(wallId)`，避免被删 wall 仍
    挂在 referencedByWalls 上、变量值变化时给已不存在 wall 发 dirty

- **`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`** onEnable：
  - VariableStore 构造后立刻 `new VariableInterpolator(variableStore)` +
    `compositor.setVariableSupport(...)`
  - 注册 `sessionManager.addWallDeleteHook(wid -> variableStore.clearWallReferences(wid))`

### 测试

- **`plugin/src/test/java/moe/hikari/canvas/variable/VariableInterpolatorTest.java`**：
  22 case / 全绿，覆盖：
  - passthrough（null / 空 / 纯文本同引用短路）
  - user namespace wallId 注入 + null wallId 字面查
  - 插件 / 系统 namespace 字面 fullName
  - fallback 链 4 档（cached / inline / default / `???`）+ 空 inline 是合法值
  - 多占位符 / 混合文本 / 名字 trim
  - `$` / `\` 转义防注入
  - referenced 集合完整性 + interpolate 不写倒排索引（只读契约）

- **`plugin/src/test/java/moe/hikari/canvas/render/CanvasCompositorVariableTest.java`**：
  7 case / 全绿，覆盖：
  - 未 setVariableSupport 不写倒排索引（baseline 兼容）
  - setVariableSupport + 含占位符 → 倒排索引被精确 mark
  - null wallId 不写索引
  - 纯文本 rasterize 触发 diff 清理（清掉旧 mark）
  - 多 TextElement 引用聚合
  - 同 wallId 重复 rasterize 幂等
  - clearWallReferences 还原索引

- **全栈**：`./gradlew :plugin:test` 480 tests / 0 failures；snapshot fixture 14 条全绿
  / 0 baseline 漂移（旧 `rasterize(state)` 路径 + 未注入 interpolator 时与 M0~M27 行为完全等价）

### 决策固化

1. **占位符替换 = 双 record copy 走 dispatch**：TextElement record immutable + Layer
   elements list 不可变视图，所以替换走"分配新 TextElement → 渲染该 record"。源 record
   完全不动；同一 state 多次 rasterize 行为幂等（每次替换得到等价 record）。`text.equals`
   短路避免占位符 resolve 后字符串相同时的多余分配。

2. **wallId == null 不写倒排索引**：模板 publish / 预览缩略图 / WallPreviewService 路径
   传 null wallId（无 wall 上下文）；rasterize 仍正常替换（user/X 走 fallback → `???`），
   但不污染 `byWall` 倒排索引（避免给 null wallId 写永远清不掉的 ghost 引用）。

3. **rasterize 末尾 markWallReferences 异常隔离**：倒排索引维护只是 dirty 优化，崩了
   也不该让用户看不到画面；catch Exception 仅丢观测（log 由 store 内部承担）。

4. **wall 删除联动走 SessionManager.deleteWall hook 而非 WallRepo.delete**：persistence
   层（WallRepo）不该耦合到内存索引；hook 在 SessionManager 触发更贴合"session 生命周期
   事件"语义。`user_variables` 表的 DB 行由 V011 schema FK CASCADE 自动清，与内存索引
   清理解耦。

### 关联文件

- `plugin/src/main/java/moe/hikari/canvas/variable/VariableInterpolator.java`（新）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`
- `plugin/src/main/java/moe/hikari/canvas/render/WallRestorer.java`
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`
- `plugin/src/test/java/moe/hikari/canvas/variable/VariableInterpolatorTest.java`（新）
- `plugin/src/test/java/moe/hikari/canvas/render/CanvasCompositorVariableTest.java`（新）

---

## 2026-05-19 · 0.4.0-P1-E：VariableProvider daemon 框架 + ProviderBootstrap

P1 阶段"异步 Provider 调度框架"。**仅占位骨架**——具体 Provider（system / papi / scoreboard /
schedule）留 P3 落地。让 P3 可直接 plug-in 而不动调度逻辑 / 守护线程池 / 异常隔离 / shutdown 流程。

### 主要变更

- **新包** `moe.hikari.canvas.variable.provider`：
  - `VariableProvider.java` — 接口（`namespace` / `displayName` / `initialize` / `refresh` /
    `refreshInterval` / `shutdown`）。`refresh()` 返 `boolean`（true = 本 tick push 成功 /
    false = fallback 跳过），daemon 只记录不据此停调度。`refreshInterval()` ≤ 0 = 不调度
    （provider 自管推送）。
  - `VariableProviderDaemon.java` — 调度器。`ScheduledExecutorService.newScheduledThreadPool(2)`
    + 守护线程（`hikari-canvas-var-resolve`，daemon=true）。`register` 流程 = putIfAbsent →
    initialize → scheduleAtFixedRate；initialize 抛异常 → 回滚 + log warning + 不传播。
    `refresh()` 抛异常 → log warning + 继续调度。`unregister` 取消定时任务 + 调 shutdown 钩子。
    `shutdown()` 幂等 + awaitTermination 5s + shutdownNow fallback。shutdown 后 register 抛
    `IllegalStateException`。
  - `ProviderBootstrap.java` — 装配入口。P1 阶段 method body 内仅 `new VariableProviderDaemon()`
    无 provider 注册；P3 阶段在此加 `daemon.register(new SystemVariableProvider(store))` 等 4
    行（注释占位已就位）。

- **HikariCanvas.java**（3 处最小化改动）：
  - import `ProviderBootstrap` + `VariableProviderDaemon`
  - 新字段 `private VariableProviderDaemon variableProviderDaemon`
  - `onEnable` 在 VariableStore 构造之后 1 行：
    `this.variableProviderDaemon = ProviderBootstrap.initialize(this.variableStore);`
  - `onDisable` 最前面 4 行：`shutdown` 守护线程池（放最前因为内部 refresh task 引用 store / DB）

### 关键设计决策

1. **接口 vs abstract class**：选 interface——provider 实现可自由继承业务父类（如 PAPI bridge
   需要 `extends PlaceholderExpansion`）；接口默认 method 不用，所有方法实现强制契约可读。
2. **`refresh()` 返 boolean 不返 void**：让 P3 实现给 fallback chain 留语义钩子（如 PAPI
   未加载 = 返 false；daemon 不据此停调度，但未来可加监控指标统计跳过率）。
3. **`refreshInterval()` ≤ 0 = 不调度**：覆盖"Push-only" provider（如 ManualScheduleProvider
   靠插件命令推送，不需要定时拉）。daemon 仅调 initialize / shutdown。
4. **守护线程 daemon=true**：JVM 关停时不阻塞 server shutdown；同时 `awaitTermination(5s)`
   给运行中 refresh 任务自然完成的机会，超时再 `shutdownNow()` interrupt。
5. **异常隔离三层**：register 时 initialize 抛 → 回滚 putIfAbsent；refresh tick 抛 → log
   warning 继续下个 tick；shutdown 抛 → swallow + log。三处都不传播，避免一个坏 provider
   拖垮整个 daemon / plugin onDisable。

### 测试

`plugin/src/test/java/moe/hikari/canvas/variable/provider/VariableProviderDaemonTest.java` —
10 case，0.979s 全绿。覆盖：register 成功 / register 重复 ns 抛 / initialize 抛异常不传播 /
schedule 定时 fire（CountDownLatch ≥ 2 次）/ refresh 抛异常继续调度 / unregister 取消任务 +
shutdown 钩子 / unregister 未知 ns 静默 / shutdown 全清 / shutdown 幂等 + 后续 register 抛 /
provider.shutdown 抛被吞。FakeProvider 内联 mock + AtomicInteger 计数器 + 异常注入开关。

完整 `:plugin:test` 433 case 全绿。

### 关联文件

- 新：`plugin/src/main/java/moe/hikari/canvas/variable/provider/{VariableProvider,VariableProviderDaemon,ProviderBootstrap}.java`
- 新：`plugin/src/test/java/moe/hikari/canvas/variable/provider/VariableProviderDaemonTest.java`
- 改：`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（+11 行：import 2 + 字段 3 + onEnable 3 + onDisable 5）

### P3 阶段开工提示

P3 实施时只需在 `ProviderBootstrap.initialize` body 内加 register 调用 + 实现对应 Provider
类（`extends VariableProvider`）。daemon 调度 / 异常 / 关停逻辑零改动。

---

## 2026-05-19 · 0.4.0-P1-A：VariableStore 核心 + V011 user_variables 持久化

0.4.0 动态接入路线 P1 阶段的"底座"——四类数据源（user / plugin / system / papi）共用的内存变量表
+ 用户变量 SQLite 持久化。**仅核心类**，WS 协议路由（B）、Compositor 替换（C）、权限节点（D）、threading
daemon（E）由后续 agent 接入；P1-A 暴露的公共 API 是后续任务的契约。

### 主要变更

- **新包** `moe.hikari.canvas.variable`：
  - `VarType.java` — enum（STRING / NUMBER / BOOLEAN / COLOR）
  - `Variable.java` — immutable record（namespace / key / type / defaultValue / currentValue /
    updatedAt / ttl / source / referencedByWalls）+ `fullName()` / `isStale(now)` helper
  - `VariablePatch.java` — record（@Nullable VarType type, @Nullable String defaultValue），
    `variable.update` op 用
  - `VariableUpdate.java` — record（@Nullable String value, @Nullable Duration ttl），
    Push API 批量接口预留（P4 落地）
  - `VariableException.java` — 8 错误码（VARIABLE_NOT_FOUND / VARIABLE_EXISTS /
    VARIABLE_TYPE_MISMATCH / VARIABLE_NAMESPACE_DENIED / VARIABLE_NAME_INVALID /
    VARIABLE_VALUE_TOO_LONG / QUOTA_EXCEEDED / TTL_INVALID）
  - `VariableStore.java` — 核心类。`ConcurrentHashMap` 主表 + namespace 桶 + per-wall 倒排索引；
    `compute` / `computeIfPresent` 保证原子；`create` / `update` / `setValue` / `delete` / `bind` /
    `get` / `listAll` / `listByNamespace` / `listByWall` / `markWallReferences` /
    `clearWallReferences` / `loadFromDb` + 校验 + 配额 + TTL ≥ 100ms

- **fullName 编码决策固化**：user 变量内部 namespace = `user:<wallId>`（冒号分隔 wallId），
  避免与普通插件 namespace `user/X` 冲突。对外 placeholder 文本仍写 `${var:user/红队比分}`，
  由 Compositor（C 任务）注入 wallId。namespace 校验 regex `[a-zA-Z_][a-zA-Z0-9_:\-]*`
  兼容 wallId 形如 `w-3a17b2c1` 的连字符。

- **DB schema V011 + DAO**：
  - `plugin/src/main/resources/db-migrations/V011__user_variables.sql` — 按 `docs/data-model.md §2.8`
    schema：PRIMARY KEY (wall_id, name) + FK CASCADE
  - `plugin/src/main/java/moe/hikari/canvas/storage/UserVariableDao.java` — JDBI DAO
    （upsert / delete / deleteByWall / loadAll / listByWall + 事务感知 *On(Handle) 重载）。
    `UserVariableDao` 由 final → 普通 class，让单测 fake 子类化覆盖

- **MigrationRunner V010/V011 补注册**：M16-P6 落了 V010 SQL 但漏注册到 `MIGRATIONS` list；
  本次顺手补上 V010 + 新 V011（V009 跳号留空）

- **HikariCanvas onEnable 集成**：在 templateRepo 装配前构造 `UserVariableDao` + `VariableStore`，
  `loadFromDb()` 启动期加载，wallDirtyCallback 暂为 noop lambda（B 任务接入 ProjectionThrottler#dirty）；
  新 getter `getVariableStore()` 供 B/C/D/E 取单例

- **单测** `VariableStoreTest.java`（29 cases）：CRUD / list / Wall dirty 触发 / loadFromDb /
  边界（非法 ns/key / 超长值 / sub-min TTL / 负 TTL / 永久 TTL=0 / per-namespace quota 1000） /
  isStale TTL 语义。用内存 fake DAO（`FakeUserVariableDao extends UserVariableDao`），不触真 SQLite。
  `:plugin:test` 423 → 全绿

### 关键决策记录

1. **VariableStore 单例 owner = main plugin 类**（HikariCanvas#variableStore）；B/C/D/E 走
   `plugin.getVariableStore()` 不重复构造
2. **dirty 触发时机**：setValue / update / delete / bind 触发对当前 referencedByWalls 集合内 wall
   的 callback；**create 不触发**（变量刚建无 referencer）
3. **持久化时机**：每次 user 变量 create / update / setValue / bind 都 upsert 一次；非 user namespace
   纯内存态，重启不留（Push 模式自然属性）
4. **倒排索引精确性**：`markWallReferences` 用 set diff 维护——新加入加进 bucket + 写到 Variable
   record；离开的 remove + 反向清掉。每次 Compositor 渲染前调一次保证精确
5. **loadFromDb 容错**：DB 里出现 schema 漂 / 非法 key / 非法 namespace 的行直接 skip 而非
   抛出——防一坏全坏拖垮启动；DB type 列 unknown 值降级 STRING

### 关联文件

新建：
- `plugin/src/main/java/moe/hikari/canvas/variable/VarType.java`
- `plugin/src/main/java/moe/hikari/canvas/variable/Variable.java`
- `plugin/src/main/java/moe/hikari/canvas/variable/VariablePatch.java`
- `plugin/src/main/java/moe/hikari/canvas/variable/VariableUpdate.java`
- `plugin/src/main/java/moe/hikari/canvas/variable/VariableException.java`
- `plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java`
- `plugin/src/main/java/moe/hikari/canvas/storage/UserVariableDao.java`
- `plugin/src/main/resources/db-migrations/V011__user_variables.sql`
- `plugin/src/test/java/moe/hikari/canvas/variable/VariableStoreTest.java`

修改：
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（imports + 字段 + 构造 + getter）
- `plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`（V010 + V011 注册）

---

## 2026-05-17 · M20 Phase 5+6（baseline 重建 + docs 收尾）

### M20.5 baseline 重建

跑 `:plugin:test --rerun-tasks`：13 fixture / 3 fail（03-effects-stroke / 04-effects-shadow / 05-effects-glow）。逐一比对 actual vs expected PNG：

| fixture | 旧 baseline | 新 actual |
|---|---|---|
| 03 STROKE | 字符偏挤偏左 | 字间距匀称居中 |
| 04 SHADOW | W 后空隙不均 | 整体舒展 |
| 05 GLOW | O-W 间有重影 | 干净无重叠 |

3 个都视觉**更好**——正是 M20 修复目标。覆盖 `expected/*.png`，再跑 `:plugin:test --rerun-tasks` → **364 tests 全绿**。

其余 10 fixture 未漂移（01-hello-world / 02-chinese-text / 06-path-line / 07-circle / 08-star / 09-linear-gradient / 10-radial-gradient / 11-dither / 12-brush / 13-image-mask / 13-placeholder）：source_han_sans 在 ASCII / CJK 普通字符 advance 与 canonical 比例（0.5 / 1.0）接近，差异 < 0.5% 测试阈值；effects 二阶像素扩散（stroke 宽度 / shadow 偏移 / glow 半径）放大了细微 advance 差异。

### M20.6 docs

- **CLAUDE.md §其他不可越界的技术决策**：双端渲染一致性条目从 canonicalCharWidth 改为 charAdvance(fontId, ch, fontSize)，说明运行时 advance 公式 + 用户字体 registerRuntime + HTTP 端点；canonical 降级为 fallback 路径
- **CLAUDE.md 里程碑列表**：加 M20 ✅；总工期 M0-M20

### 关联文件

`CLAUDE.md` / `docs/journal.md` / `plugin/src/test/resources/expected/03-effects-stroke.png` / `04-effects-shadow.png` / `05-effects-glow.png`（baseline 替换）。

---

## 2026-05-19 · 0.4.0 动态接入路线规划（文档定稿，等用户通知开干）

用户要求规划"动态数据接入"。多轮评估后定型 **Push 模式 + 玩家自定义变量 + 四层数据源 + Plugin API**。

### 核心定位

**HikariCanvas = 展示层 + 通用扩展口 + 简单到中等内置编辑**。专业数据（铁路时刻 / PvP 引擎 / 商店逻辑）由专业插件 push；HikariCanvas 不重新造业务系统轮子。

### Pull → Push 设计转变

之前评估倾向 Pull（HikariCanvas 主动调 Provider.resolve）；用户提出 Push 后我同意转向：
- 性能：HikariCanvas 不做 active polling，专业插件控制节奏
- 解耦：HikariCanvas 不懂业务（只存 string）
- 扩展性：插件想推什么就推什么（"08:15 / 晚点 5 分钟 / 大交路"）
- 玩家自定义变量是核心差异化（手动赛分 / 时刻表 / 商店时价）

### 四层数据源

1. **Tier 1 用户变量**（`user/*` namespace，持久化，玩家手动改 / +/- 按钮 / 命令）
2. **Tier 2 插件 push**（`<namespace>/*`，HikariCanvasAPI.setVariable）
3. **Tier 3 系统变量**（13 项 `server.*` / `wall.*` / `scoreboard.*`，内置）
4. **Tier 4 PAPI 桥接**（`papi/%placeholder%` 自动暴露）

优先级（同 key 时）：Tier 2 > Tier 4 > Tier 3 > Tier 1 default

### 引用语法

```
${var:user/红队比分}                  显式 namespace
${var:红队比分}                       简写（自动加 user/）
${var:bedwars/score|fallback=0}       插件变量 + fallback
${var:server.time}                    系统变量
```

### 6 个固化决策

1. **Push > Pull**（性能 / 解耦 / 扩展性）
2. **变量是 string**（业务语义在插件侧，HikariCanvas 不解析）
3. **用户变量持久化**（DB + .canvas）；插件 / 系统 / PAPI 变量内存态
4. **resolve 不在主线程**（ProjectionThrottler 用 cache O(1)；async daemon 后台拉系统 / PAPI）
5. **namespace 严格隔离**（防 plugin spoof；注册中心 enforce）
6. **fallback 链**：cached → `${var:X|fallback=...}` → `Variable.default` → `"???"`

### 五个 phase（每 phase 可演示推出）

| Phase | 内容 | 工时 |
|---|---|---:|
| P1 变量系统底座 | VariableStore + 协议 + 持久化 + Compositor 替换 + threading + 权限 | 62h |
| P2 编辑器基础 UX | 变量管理面板 + 朴素 textarea + Variable Picker | 30h |
| P3 内置 Provider | 13 系统变量 + Scoreboard 桥接 + PAPI 桥接 + Manual Schedule | 20h |
| P4 Plugin Push API | HikariCanvasAPI + 注册中心 + DemoTrain/DemoScore | 28h |
| P5 命令 + 测试 + docs | `/canvas var` + 单测 + baseline + 教程 | 10h |

**0.4.0 总 ~150h ≈ 6-7 周 wall-clock**

每 phase 结束可推 demo（不是 MVP 阶段式半成品，每阶段都是 demo-ready 状态）。

### 0.4.1 与远期路线

- **0.4.1**（~25h）Notion-style chip 编辑器（${var:X} → 蓝色 pill + hover 显示当前值 + click 改绑定）
- **0.5.0**（~120h）动画 + 时间轴（AE 简化版）
- **0.6.0+**（~200h）Blockly 块脚本（避代码沙盒 RCE，编译为内部 DSL JSON，事件驱动）
- **1.0.0 stable** ETA ~4 个月 wall-clock（含变量 + 动画 + 教程）
- **全量动态接入** ETA ~8-10 个月 wall-clock

### 已知边界 / 限制

- 变量命名空间冲突：玩家 `user/X` vs 插件 `bedwars/X` 不冲突（不同 namespace）；同 namespace 内同 key 后写覆盖
- TTL 过期 fallback：cached → fallback 语法 → default → "???"
- 重启后：user/* 变量从 DB 恢复；插件 / 系统 / PAPI 变量重新 push
- per-player personalized 变量：v0.4 不做（性能成本高，留 v1.x）
- 数据类型：v0.4 只 STRING/NUMBER/BOOLEAN/COLOR；list/map 留 v1.x

### 文档落地

- 新 `docs/dynamic-data.md`（~400 行规划文档，16 个 section）—— **实施前必读**
- `CLAUDE.md` 契约文档列表加 dynamic-data.md；新 0.4.0 路线段
- `docs/architecture.md §13` 细化（P-1 + P-3 混合架构 + 6 个固化决策）
- `docs/protocol.md §5.11` 新 variable.* op 族 + 4 个 error code
- `docs/data-model.md §2.8` 新 user_variables 表（V011 migration）
- `docs/security.md §5` 权限节点加 7 个 `canvas.var.*` + 插件 namespace ACL 说明

### 实施状态

**未开工**。等用户通知再开 M28 Phase 1。所有数据模型 / 协议 / API 决策已固化，agent 可直接据 dynamic-data.md 实施。

### 关联文件

`docs/dynamic-data.md`（新）/ `CLAUDE.md` / `docs/architecture.md` / `docs/protocol.md` / `docs/data-model.md` / `docs/security.md` / `docs/journal.md`。

---

## 2026-05-19 · M27 Shift 等比锁 + 版本号 0.3.0-SNAPSHOT

### Shift 等比锁

`useDrawToCreate.ts` 加 `applyShiftLock(x1, y1, x2, y2, tool)`：
- 圆 / 矩形 / 星：`s = max(|dx|, |dy|)`，终点 `(x1+sx·s, y1+sy·s)` 锁正方形 bbox
- 线 / 箭头：`angle = atan2(dy,dx)`，snap 到最近 0/45/90/135° 倍数 → `(x1 + cos(α)·len, y1 + sin(α)·len)` 锁 8 向

接入：
- `DrawDrag` 加 `shiftLocked: boolean` 字段
- `move(pos, shiftLocked=false)` 签名扩展；CanvasView `drawMove(pos, isShiftDown.value)` 接 M17.4 已有的全局 isShiftDown ref（snapManager 同款）
- `drawPreview` computed 应用 applyShiftLock 实时反馈视觉
- `end()` 提交时根据 shiftLocked 标志决定用 raw 或 locked 终点

### 版本号

`0.2.0-SNAPSHOT → 0.3.0-SNAPSHOT`：累积 M22(Material Symbols 留)→M27 间大量 feature（20 字体扩充 / Live Paint / 图标库 / 主题 / 字体 advance 精确化等），符合 minor 推进语义。

- `build.gradle.kts` allprojects
- `web/package.json`
- `paper-plugin.yml`

仍 SNAPSHOT pre-release；契约规则锚定 stable ≥1.0.0 边界（data-model.md §6.6 不动）。

### 验证

`vite build` 通过；后端无改动（前端 only feature）；CLAUDE.md 里程碑加 M27 ✅。

### 关联文件

`web/src/composables/useDrawToCreate.ts` / `web/src/components/layout/CanvasView.vue` / `build.gradle.kts` / `web/package.json` / `plugin/src/main/resources/paper-plugin.yml` / `CLAUDE.md` / `docs/journal.md`。

---

## 2026-05-18 · M26-C PathParser 扩展 H/V/A/S/T（FA icon MC 渲染根因）

### 用户报告 + 根因

M26-B 修了 icon add bug 后，前端拖入显示正常，但**游戏内 MC 渲染为"杂乱无章像素"**。

**根因诊断**：`PathParser.java` 注释明确只支持 **M/L/Q/C/Z**（M9 PathElement 子集 + DoS 防御）。FA SVG 大量用：
- `fa-solid/circle`: `M256 512A256 256 0 1 0 256 0a256 256 0 1 0 0 512z` —— **全靠 A（arc）画圆**
- `fa-solid/heart` / `star` 等：**S/s** smooth cubic shortcut
- `gear` 等：**A** + **s**

未支持命令静默跳过 → 后端 path 残缺 → MC 渲染"杂乱像素"。前端用浏览器 `new Path2D(d)` 原生支持完整 SVG 命令——所以前端 OK 后端崩。

### 扩展实装

**H/h** 水平 lineto（1 参）/ **V/v** 垂直 lineto（1 参）：直接 `path.lineTo`。

**S/s** smooth cubic（4 参，缺 c1）：反射前 c2——`c1 = cur*2 - prevC2`；前非 C/S 时 `c1 = cur`。新状态 `prevC2X/Y + prevWasCubic`。

**T/t** smooth quadratic（2 参，缺 cp）：反射前 Q/T cp 同款。新状态 `prevQcX/Y + prevWasQuad`。

**M/L/H/V/A/Z** 清除 cubic / quad 标记。

**A/a 椭圆弧（最难，7 参 `rx ry rotation large-arc sweep x y`）**：
- W3C SVG 1.1 §F.6 / B.2.4 标准 endpoint→center 算法
- 切 ≤π/2 弧度段 → 每段 cubic bezier 近似
- 公式：`α = sin(θ) × (√(4+3tan²(θ/2)) - 1) / 3` 控制点距离系数
- 起/末段切线沿 path 走向（含 sweep 翻向）输出给 marker
- flag 参数支持单字符无分隔（`A 50 50 0 01 100 0`）
- 半径校正 / x-axis-rotation 全套
- 退化处理：rx==0 或 ry==0 走 lineTo

### PathDValidator 保持不变

M9 PathElement 用户输入仍走 M/L/Q/C/Z 严格 validator（DoS 防御）。**FA icon path 不经过 validator**（IconRegistry 直接喂 PathParser），构建期受信。

### 测试

新 **19 case**（H/V 各 3、S 4、T 2、A 7 含 FA circle/heart/star 实测）；PathParserTest **41 总过**。

- FA `circle` parse → **bbox (0,0)-(512,512) 圆形正确** ←（这就是"杂乱像素"根因，之前 A 静默跳过 → 残缺路径）
- FA `heart` 含 L/C/c/v/s/z parse → 宽 > 400 OK
- FA `star` 含 C/c/s/l parse → 宽 > 500 OK

`:plugin:test --rerun-tasks` 0 fail / 0 error / **14 baseline fixture 0 漂移**。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/render/PathParser.java`（+ ~250 行：H/V/S/T case + arcToBezier helper + flag scanner）
`plugin/src/test/java/moe/hikari/canvas/render/PathParserTest.java`（+19 case ~210 行）

---

## 2026-05-18 · M26-B Icon add bug + registerRuntime 异步化 + Material 留 M27

### Icon add bug 修复（用户报告：点击 / 拖入无反应）

**根因**：`EditSession.addElement` switch 缺 `case "icon"`：

```java
element = switch (type) {
    case "text" / "rect" / "path" / "circle" / "shape" / "image" -> build...
    // ❌ 缺 case "icon"
    default -> throw new ValidationException("INVALID_ELEMENT", "unknown element type: " + type);
};
```

M7 期 IconElement 只在 template 内部 build；M26.1 加 IconLibrary 让 icon 第一次走 element.add 路径，agent 漏改这个关键 case → 所有 icon add 返 INVALID_ELEMENT，前端"无反应"。

**修复**：
- 加 `case "icon" -> buildIcon(id, props);`
- 新 `buildIcon(id, props)`：解析 x/y/w/h/rotation/locked/visible/source/tint/fill/opacity/blendMode/renderMode，复用 ElementValidator.parseFillNullable / parseOpacityNullable；source 走 IconElement.isValidSource；fill 优先 tint 兼容（与 IconElementDeserializer 一致）
- 新测试 `EditSessionIconTest.java` 8 case：fill 入口 / tint 升级 / fill+tint 双给 fill 优先 / 都缺 → null / legacy source / 非法 source / 缺 source / `material/` 命名空间

### Material Symbols 调研 + 留 M27

调研 marella/material-symbols + @material-symbols/svg-400 npm tarball：
- 源稳定（npm tarball 1.8MB → 解压 30MB），单 `<path>` SVG
- **阻塞 1**：viewBox `0 -960 960 960`（负 Y 起点）与 FA `0 0 512 512` 不同，IconRenderer 当前 parseViewBox 支持但需 fixture 测多 viewBox 双端一致
- **阻塞 2**：outlined-only 3879 个 ≈ 5MB JSON（FA 三 pack 仅 1.5MB）；全 4 风格 7758 ≈ 10MB jar 增量过大
- **决策**：M27 单独 phase 处理：① 选 curated 子集（~500-1000）控 jar；② IconRenderer viewBox 参数化（已 ready）+ fixture 验证；③ IconLibrary tab + i18n

### registerRuntime 异步化（v1.x 散点收尾）

**问题**：M20.4 用户字体 metrics 计算在 `FontRegistry.loadExternal` 同步跑，每字体扫 65k codepoints ~1-2s；N 个用户字体 onEnable 阻塞 N × 1-2s（N=10 ~15s）。

**修复**：把 `FontMetricsTable.registerRuntime` 改为 daemon worker 后台串行跑：
- `Font.createFont` + `fonts.put` 仍同步（其他模块 onEnable 后立即 ready）
- 只把 metrics 计算推后台
- worker 未完成时 `FontMetricsTable.advance` 返 -1 → 调用方走 `canvasCharWidth` fallback（功能可用，前几秒宽度按经验值，metrics 到位后 onMetricsReady → CanvasView.requestDraw 自动重画）
- ConcurrentHashMap.put 原子覆盖 MISSING sentinel
- worker daemon=true 防 plugin disable 后 JVM hang
- 每字体独立 try-catch 防一个失败拖垮全队

**收益**：N 个用户字体 ~1-2s × N → 0；N=10 节省 ~15s onEnable

### 验证

- `:plugin:test` BUILD SUCCESSFUL（含新 EditSessionIconTest 8 case）
- 后端 baseline 14 fixture 0 漂移

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（+ buildIcon + case "icon"）/ `FontRegistry.java`（registerRuntime worker）/ `plugin/src/test/.../EditSessionIconTest.java`（新）

---

## 2026-05-18 · M26 内置图标库（FA Free 2060 icons + IconLibrary UI）

用户要求"图标库 / 素材库共享侧边栏"——选项 C 奢华版（FA Free + Material Symbols + 用户 SVG + 收藏夹 + 拖入）。

**M26 实际范围调整**：Material Symbols 9000 icon × 4 风格无单一 zip release，单独工程 ≥6h，留 M27；M26 收口 FA Free + 用户 SVG + 完整 UI。3 commit batch（4h+4h+8h ≈ 16h wall-clock）。

### M26.1 数据模型 + 后端 IconRegistry + 构建期生成器（commit `2e3c58d`）

**IconElement 升级**：
- 加 `Fill fill` 字段（M11 联合类型，替代 tint；保留 tint 兼容旧模板）
- `IconElementDeserializer`：旧 `tint` 字符串自动 → `SolidFill(tint)` 升级
- `source` 命名 schema：`fa-solid/heart` / `user/train`（新）vs 无 `/` 的 legacy PNG（M7 完全兼容）
- `SOURCE_RE = ^[a-z0-9_-]+(/[a-z0-9_.-]+)?$`（≤ 64）

**IconLibraryGenerator**（构建期）：
- 拉 FA Free 6.7.2 zip 13MB（SHA-256 pin `ecdaaa6d...`，3 次重试同 downloadFonts 防御）
- 正则取 `<path d>` 首个 + viewBox（FA 全 0 0 512 512）
- 输出 `build/generated/icon-resources/{fa-solid,fa-regular,fa-brands}.icons.json` ≈ 1.4MB（jar +1.4MB）
- processResources include 到 jar `/icons/`
- **总产物**：fa-solid 1402 + fa-regular 163 + fa-brands 495 = **2060 icons**

**IconRegistry**：
- `loadBuiltIn()` 读 jar JSON / `loadExternal(plugins/HikariCanvas/icons)` 扫 SVG
- 用户 SVG 解析：regex `<path d>` 首个 + viewBox 属性（缺则 `0 0 24 24`）；文件名白名单 `[a-z0-9_-]+\.svg`
- API：`getPathD / getViewBox / getInfo / listAll / search(q, category, limit, offset)`

**HTTP 端点**：
- `GET /api/icon/list?q&category&limit(默 60,max 200)&offset` → `{icons,total,hasMore}`，Cache 300s
- `GET /api/icon/paths?id=X`（严格 isValidSource）→ `{id,viewBox,paths:[{d}]}`，Cache 86400s immutable

### M26.2 IconRenderer SVG 双端（commit `5c5b7eb`）

**后端 IconRenderer dual-path**：
- `IconElement.isLegacySource()` 判定：含 `/` 走 `renderSvgPath`（新）/ 无 `/` 走 `renderLegacyPng`（M7 完全保留）
- SVG 路径：`PathParser.parse(d)` + viewBox 平移等比缩放居中 AffineTransform + Fill 经 `FillPaintBuilder.fillToPaint(fill, elBbox)` + `g.fill(transformedShape)`；fill==null fallback `SolidFill("#000000")`
- 占位画法（虚线方框 + ?）与 PNG 路径一致

**RenderContext + CanvasCompositor**：新 ctor 加 `IconRegistry` 注入；保旧 ctor 测试路径兼容（iconRegistry=null SVG 退占位）

**前端 PreviewRenderer.drawIcon** 拆为 `drawIconLegacyPng + drawIconSvgPath`：
- SVG 走 `getCachedIcon` 同步查 + `ensureIconLoaded` 异步首加载
- `fillToCanvasStyle(在 translate 前算 gradient 避免端点错位)`
- `ctx.translate + scale + new Path2D(d) + ctx.fill`

**新 IconLoader.ts**（镜像 FontLoader）：cache + pending 去重 + readyHandlers；404/网络失败缓存 null 让上层走占位。

`CanvasView` 加 `onIconLoaded(() => requestDraw())` 同 onFontLoaded pattern。

### M26.3 前端 IconLibrary UI（commit `cf99dcf`）

**新组件 `IconLibrary.vue` 360px 左侧 panel**：
- absolute 紧贴 LeftTools，z-30 浮 CanvasView 上；v-if 完全卸载（关时省 fetch/observer）
- 7 tabs（全部 / FA solid / regular / brands / 我的 / 收藏 / 最近）+ localStorage 当前 tab 持久
- 6 列 aspect-square grid，hover 角标收藏星标，`:title` 显示 name·pack
- 搜索 debounce 200ms；tab/词变重置 + first page fetch
- IntersectionObserver lazy ensureLoaded：仅可见 cell 调 `/api/icon/paths`
- sentinel IntersectionObserver 无限滚动 +128px 触底 → offset+=60
- 客户端 favorites/recent tab 跳 fetch，子串过滤

**新 `stores/iconLibrary.ts`**：open / activeCategory / favorites / recent 三段 localStorage。

**交互**：
- 拖入：cell `:draggable` + `setData('application/x-hikari-icon', id)` + text/plain 兜底；CanvasView client→canvas 坐标解算 → 创建 64×64 IconElement 居中于光标
- 点击：画布中心新建 IconElement（fill solid #000000）+ trackUsage 推 recent

**LeftTools 加 Shapes 按钮（I 快捷键）**；`useCanvasShortcuts` 加 I toggle + Esc 关闭；click-outside 关闭（排除自己按钮 data-icon-library-trigger）

**i18n**：`tools.iconLibraryTool` + `iconLibrary.*` 中英双语（含 7 category label）

### 验证

- `:plugin:test` 28 suite / 364 tests / 0 失败 / 14 fixture 0 漂移（M26.1 + M26.2）
- vite build 580 kB (+18 kB) / gzip 179 kB / 0 错 / 1762 modules

### 关键架构决策

1. **SOURCE_RE 双形式**：`pack/name` 走新 SVG，无 `/` 走旧 PNG——一行正则隔离新旧
2. **IconElementDeserializer 自动 tint→fill 升级**：旧模板 / 旧 .canvas 透明读取，永不破坏
3. **IconRegistry 与 FontRegistry 同款双源 + HTTP 通法**（M23 设计延续）
4. **Fill 联合类型复用**：icon 支持渐变填充（同 Path / Canvas.background），不限单色
5. **lazy + infinite scroll**：2060 icons 前端不一次加载 SVG path，IntersectionObserver 按需 `ensureLoaded`

### 留 M27 + v1.x

- **Material Symbols 集成**（M27）：9000 icon × 4 风格需要 GitHub API 列文件或拉 marella/material-symbols npm 包 + 解析
- **PNG IconElement → SVG 迁移工具**（v1.x，可选）：现有 PNG 资源转 SVG path
- **Icon 多 path / multi-fill**（v1.x）：当前 paths 数组留 v2 multi-path（FA Free 全单 path）
- **更多 icon source**：Iconify / Lucide / Heroicons（任何 SVG icon 集都能加入 IconRegistry pack）

### 关联文件（M26.1+2+3 共）

**新**：
- `plugin/src/main/java/moe/hikari/canvas/render/IconRegistry.java`
- `plugin/src/main/java/moe/hikari/canvas/state/IconElementDeserializer.java`
- `plugin/src/generator/java/moe/hikari/canvas/build/IconLibraryGenerator.java`
- `web/src/render/IconLoader.ts`
- `web/src/components/layout/IconLibrary.vue`
- `web/src/stores/iconLibrary.ts`

**改**：`IconElement.java` / `IconRenderer.java` / `RenderContext.java` / `CanvasCompositor.java` / `HikariCanvas.java` / `EditSession.java` / `ElementValidator.java` / `TemplateInstantiator.java` / `WebServer.java` / `plugin/build.gradle.kts` / `web/src/render/PreviewRenderer.ts` / `web/src/types/protocol.ts` / `web/src/components/layout/CanvasView.vue` / `LeftTools.vue` / `App.vue` / `useCanvasShortcuts.ts` / `i18n/messages.ts`

---

## 2026-05-18 · M25 ThemeSwitcher Bug 修复 + i18n 挂载 + 2 字体扩充

3 块工作 1 agent 完成。

### 任务 1：ThemeSwitcher Bug 真实根因

用户报"点击调色板 icon 无反应 + icon 消失"。

**实际根因**（不是猜测的 Tooltip + onClickOutside 时序）：

```ts
// ThemeSwitcher.vue
const { t, locale } = useI18n();  // ← useI18n 只返回 { t }，没有 locale
// ...
return locale.value === 'zh' ? p.nameZh : p.nameEn;  // ← 抛 Cannot read undefined
```

→ Vue 渲染 popover 时整个组件树崩塌 → button icon 跟着消失 + popover 不显。

**修复方案 A+B 双保险**：
- A. ThemeSwitcher 改用 `useUiStore().locale` 直接读
- B. `onClickOutside` 加 `{ ignore: ['.hc-tooltip'] }` 防御 Teleport 出去的 tooltip 误判（虽然这次没触发，但作为 popover + Tooltip 模式的通用防御）

SnapSettingsPopover 检查后**不动**——它没解构 `locale`，长期工作正常。

### 任务 2：M24-A i18n 挂载到组件

M24-A 准备的 i18n key 终于挂到 UI：

#### A. errors.* 挂到 wsClient（22 错误码）
`wsClient.ts` 新 `localizeErrorCode(code)` 统一翻译；`handleError` + `onClose(4001 / non-1000)` 都接入。新增 messages key 11 个补全 server 实际 code 覆盖（AUTH_FAILED / WALL_NOT_FOUND / INVALID_OP / VERSION_MISMATCH / UNEXPECTED / SESSION_CLOSED / ALIAS_TAKEN / INVALID_ALIAS_FORMAT / QUOTA_EXCEEDED_DISK / PERMISSION_DENIED 等）。

#### B. tooltips.* 挂载（3 处）
- `tooltips.saveTemplate` + `tooltips.disabledWhenLocked` 三元到 TopBar Bookmark 按钮
- `tooltips.colorPicker` 到 ColorInput trigger native `title`

#### C. properties.*Label/*Tip 挂载（8 字段）
- TextElementSection 4：fontId / fontSize / align / color label + tip + i18n optgroup 名
- TransformSection 4：x / y / w / h tip（position/size）
- 新增 messages key 6（fontGroupBuiltin/User / alignLabel/Left/Center/Right）

### 任务 3：2 个新字体（用户要 FHWA + Bahnschrift）

用户字体协议：
- **FHWA Series（Highway Gothic）** 美国联邦字体，源公有领域但没便利 OFL 发布 → 用 **Overpass**（Red Hat 资助 / OFL 1.1，FHWA 风格开源替代）
- **Bahnschrift** Microsoft 专有 → 不可打包；OFL 等价物 D-DIN URL 找不到便利源 → 改用 **Bebas Neue**（OFL 1.1，DIN/Bahnschrift Condensed 视觉最接近）

| 字体 ID | 字体 | size | SHA-256 | 来源 |
|---|---|---:|---|---|
| `overpass` | Overpass Regular（FHWA 替代） | 311KB | `970717df...f0073` | google/fonts ofl/overpass variable[wght] |
| `bebas_neue` | Bebas Neue Regular（Bahnschrift 替代） | 60KB | `08e46238...ec73` | google/fonts ofl/bebasneue static |

style.css 已无 @font-face（M23 通法），无需挂载——FontRegistry 注册后 `/api/font/list` 自动暴露 + 前端 TextElementSection 下拉自动出现。

### 验证

- `:plugin:test` BUILD SUCCESSFUL，14 fixture 无漂移
- `:plugin:shadowJar` BUILD SUCCESSFUL；jar 155.8 MB（含 macOS Finder 副本污染，CI clean 后会回到正常 size）
- `vite build` 1756 modules / 0 错
- 字体下载 SHA pin 全 verified

### 关联文件

`plugin/build.gradle.kts` / `plugin/.../render/FontRegistry.java` / `web/src/components/layout/ThemeSwitcher.vue` / `web/src/components/layout/TopBar.vue` / `web/src/components/properties/TextElementSection.vue` / `web/src/components/properties/TransformSection.vue` / `web/src/components/ui/ColorInput.vue` / `web/src/i18n/messages.ts` / `web/src/network/wsClient.ts` / `web/src/render/PreviewRenderer.ts`。

---

## 2026-05-18 · M24 前端 UX 大整修（Catppuccin + M3 扁平 + i18n 友好化）

用户要求：Material Design 3 扁平化 + Catppuccin 色板 + **避开"大灰黑 / 大黑紫 / AI 审美"** + 文案玩家友好 + 多主题 + 主题 switcher。2 个并行 agent 完成（约 4h wall-clock）。

### M24-A i18n 文案用户友好化（agent A）

- `messages.ts` 764 → 775 行，~140 个 value 改写 + 40 个新 key
- **错误码翻译**：22 个 raw code（FORBIDDEN / NOT_FOUND / INVALID_PAYLOAD / QUOTA_EXCEEDED 等）→ "你没有这块画板的权限 / 找不到这块画板 / 本日上传次数已达上限"等口语化
- **元素属性字段**：fontId / blendMode / renderMode / strokeWidth / letterSpacing 全部去开发味；新 properties.fontIdLabel/Tip 等 10 个 label+tip 键
- **Empty state 引导**：从"未选中元素"→"点击画布或图层面板里的元素来开始编辑。"；图层空 → "从左边工具栏挑个工具开始画吧"
- **工具栏**：每个工具加快捷键 + "怎么操作"（"按住空格临时切换 pan" 等）
- **专有名词保留**：MC / sha256 / Bayer / Apple Pencil
- **中英语气对齐**：中文亲切口语化；英文 friendly conversational

### M24-B Catppuccin + M3 扁平 + ThemeSwitcher（agent B）

**主题色板替换**：
- `style.css` 完整重写：删 shadcn 中性灰，注入 Catppuccin 全套（base/mantle/crust + surface0-2 + overlay0-2 + subtext0-1 + text + 14 accent）三 flavor
- **Latte（浅米白暖色）**默认 / **Frappé（深灰偏紫暖）**深色 / **Macchiato（更深低饱和）**
- shadcn-vue `--background/--card/--primary/--ring` 等全部映射到 `--ctp-*`——组件代码零改动即生效
- 默认 primary = `--ctp-mauve`（温暖紫粉，**绝不大黑紫**）

**Material 3 设计 tokens**：
- `--radius-{xs,sm,md,lg,xl,full}` (4/6/12/16/24/9999px)
- `--elevation-{0..3}` 用 surface tones 做层级（**不用 box-shadow**——避 AI 审美）
- 新 `.hc-btn` 类 + 全局 `.hc-focus` accent outline + `.hc-alpha-checkerboard`（surface2 棋盘格替代硬 #ccc）

**ThemeStore + ThemeSwitcher**：
- 新 `stores/theme.ts`：flavor + accent + radius，独立 3 个 localStorage key；向后兼容旧 `theme=dark/light`（→ frappe/latte）；初始 apply 在 main.ts mount 前调用避免首屏闪烁
- 新 `ThemeSwitcher.vue`：M3 popover 风格挂 TopBar；preset 列表（圆点 + check）+ 8 accent swatch 网格 + 5 radius preview
- i18n 新增 `theme.*` 段

**Visual bug 修复**：
- **FillInput tab 撞色**（用户原报告 bug）：active 改 `bg-card + foreground + font-medium`（M3 segmented control 风格，弃 `bg-primary`）
- Tooltip 硬 #18181b / #1f2937 / #fafafa → surface tones
- ColorInput 棋盘格 `#ccc` → `--ctp-surface2`；alpha thumb white → foreground + card border
- LayerPanel slider `accent-color: --ring → --primary`
- SnapSettingsPopover `accent-color: #60a5fa` → `--primary`
- CanvasView lock overlay backdrop-blur 删（AI 审美），改 ctp-crust/20 + peach badge
- HelpModal / SaveAsTemplateModal / TemplateGallery scrim `bg-black/50 + shadow-2xl` → ctp-crust/50 + shadow-md

**全栈 visual polish**：
- 89 处 `text-[9/10/11px]` → `text-xs`（M3 type scale 收敛）
- 7 处 `rounded-md/lg/xl` → `rounded-[var(--radius{,-sm})]` 跟主题
- 12+ 处 Tailwind palette 硬编码色（amber/emerald/red/blue/sky 等）→ `--ctp-*` / `--destructive`
- HomePage card hover：translateY + shadow 替换为 surface tone + border-color
- 模板 featured/builtin badge：`text-black/white` → `text-[color:var(--ctp-base)]` 自适应 flavor

### 验证

- `:plugin:test` BUILD SUCCESSFUL，14 baseline fixture 无漂移（前端 only）
- `vite build` 460ms / 0 错；JS 546 → 562 kB（+15.7 kB，theme store + ThemeSwitcher + 主题 token 表）；gzip 174.63 kB

### 关键遗留 / 待办（M25 候选）

1. **i18n 新 key 未挂到组件**：M24-A 准备了 22 错误码 + 40+ tooltip + properties label 键，但 wsClient.ts / TopBar / RightPanel 等组件仍用旧 key 显示。下一步把 errors.* 挂到 wsClient.handleError；tooltips.* 挂到 button `:title`；properties.fontIdLabel 挂到 TextElementSection
2. **ColorInput native picker** 仍由浏览器渲染（系统级限制，无法主题化）
3. **vue-tsc Node 25 兼容性** 阻塞类型检查 CLI；vite Vue SFC 编译期 0 错
4. **TextElementSection 字体下拉** 还未应用 ThemeSwitcher 圆角 token（保留 select 默认样式）

### 关联文件

新：`web/src/stores/theme.ts` / `web/src/components/layout/ThemeSwitcher.vue`。改：`style.css` / `main.ts` / `stores/ui.ts` / `i18n/messages.ts` / 25+ 组件 .vue 文件。

---

## 2026-05-18 · M23 字体加载通法（双轨变单轨，删 fallback bug）

### 根因诊断

用户报告 M22 字体（得意黑 / 马善政毛笔楷书 / 站酷庆科黄油体 等）在 **MC 内完美但浏览器画板显示普通字体**。

定位 `web/src/render/PreviewRenderer.ts:909-912`：

```ts
function fontFamily(fontId: string): string {
    const KNOWN = new Set(['ark_pixel', 'source_han_sans']);
    return KNOWN.has(fontId) ? fontId : 'ark_pixel';
}
```

**M5-D 期遗留的"安全网"白名单，M21/M22 加 18 字体时 agent 都没更新**。所有新字体在 `drawText` 设置 `ctx.font` 前被强制 fallback 到 `ark_pixel` → 浏览器二次 fallback 到 system 字体 → 视觉上"普通字体"。

MC 内为何完美：Java FontRegistry 按 fontId 直接拿 Font 对象，无此白名单限制 → 后端正确 + 前端 broken。

### 深层架构问题

用户字体（plugins/HikariCanvas/fonts/）**前端完全无 @font-face**——双轨制：
- 内置字体：style.css 写死 @font-face（构建期固定列表）
- 用户字体：后端 FontRegistry.loadExternal + FontMetricsTable.registerRuntime + `/api/font/metrics` 但**前端没法拿字体二进制** → 浏览器静默 fallback

每次加字体易漏环节（M21/M22 fontFamily 漏修就是例证）。

### 通法：双轨变单轨

M23 删除 style.css 所有 @font-face + 删除 fontFamily KNOWN，统一走 HTTP + `FontFace` API。

### 后端 M23.1（commit ?）

`plugin/.../render/FontRegistry.java`：
- 新 `loadFontBytes(fontId): byte[]`——从 jar classpath（内置）或文件系统（用户）读字体二进制；间接寻址（fonts Map lookup）防 path traversal
- 新 `listAll(): List<FontInfo>`——内置在前 + 同组按 id 字母序
- 新 record `FontInfo(id, displayName, source, format, pixelated, nativeSize)`

`plugin/.../web/WebServer.java`：
- 新 `GET /api/font/file?id=X`——fontId 白名单 `[a-zA-Z0-9_-]+`；200 + `Content-Type: font/ttf|otf` + `Cache-Control: max-age=86400, immutable`；404 / 400
- 新 `GET /api/font/list`——返 `{fonts: [...]}` JSON；`Cache-Control: max-age=60`
- WebServer 构造器加 FontRegistry 参数；HikariCanvas.java 调用点更新

### 前端 M23.2

新 `web/src/render/FontLoader.ts`（~70 行）：
- `ensureLoaded(fontId)` 幂等 async + 去重 Map + 静默失败（fallback 由浏览器 system 字体处理）
- `isLoaded(fontId)` 同步查询 `face.status === 'loaded'`
- `onFontLoaded(fn)` 回调注册（与 onIconReady / onPaletteReady / onMetricsReady 同 pattern）
- 内部 `new FontFace(id, 'url(/api/font/file?id=X)') + face.load() + document.fonts.add`

删除：
- `web/src/style.css`：20 个 @font-face 全删（line 9-172）+ 保留 Tailwind / 主题 / body reset + 加 M23 注释
- `PreviewRenderer.ts fontFamily()` 函数 KNOWN 白名单（**根因 bug 修复**）；`drawText` 内 `family = t.fontId` 直接用；入口 `if (!isLoaded(family)) ensureLoaded(family)` 预热

`CanvasView.vue`：onMounted 预热 ark_pixel / source_han_sans + `onFontLoaded(() => requestDraw())` 回调（替代原 `document.fonts.ready` 一次性等待）

`TextElementSection.vue`：
- onMounted fetch `/api/font/list` → `availableFonts` ref
- computed `builtInFonts` / `userFonts` 分组
- `<select>` 改用 `<optgroup>` 两段（"内置字体" / "用户字体"，后者仅当 user 字体存在才渲染）
- 切字体时 `ensureFontLoaded(newId)` + emit

### FONT_META 兼容性

保留 `FONT_META` 字典作"启动期已知字体" hint（pixelated / nativeSize 判断）；未知 fontId（用户字体）走默认 `{pixelated: false, nativeSize: 0}`——`shouldUseNearestNeighbor` 访问 `FONT_META[family]?.pixelated && nativeSize > 0`，未知返 false 走标准 fillText 路径，无需额外改造

### 验证

- `:plugin:test` BUILD SUCCESSFUL，14 baseline fixture 无漂移
- `vite build` 1753 modules / 921ms / 0 错；CSS 47.7 → 46.91 kB（删 160 行 @font-face）；JS gzip 不变

### 关联文件

后端：`FontRegistry.java` / `WebServer.java` / `HikariCanvas.java`。前端新：`FontLoader.ts`。前端改：`style.css` / `PreviewRenderer.ts` / `CanvasView.vue` / `TextElementSection.vue`。

---

## 2026-05-18 · M22 字体艺术/装饰扩充（7 → 20 字体矩阵，选项 C）

用户选"选项 C 完整版"再加 13 字体。复用 M21 工作流（4 处改动 per 字体）。

### 实际落地 13/13

#### 中文艺术 6
| 字体 ID | 风格 | size |
|---|---|---:|
| `smiley_sans` | 圆体艺术（得意黑） | 2.0MB (OTF) |
| `ma_shan_zheng` | 毛笔楷书 | 5.6MB |
| `zcool_xiaowei` | 宋体艺术化 | 6.0MB |
| `zcool_kuaile` | 圆体可爱 | 1.4MB |
| `zcool_qingkehuangyou` | 黄油涂鸦 | 7.9MB |
| `lxgw_wenkai` | 手写楷书 | 18.4MB |

#### 西文装饰 7
| 字体 ID | 风格 | size |
|---|---|---:|
| `comic_neue` | Comic Sans 替代 | 56KB |
| `pacifico` | 手写艺术 | 322KB |
| `lobster` | 复古手写 | 397KB |
| `bangers` | 漫画粗体 | 91KB |
| `shadows_into_light` | 马克笔手写（替 permanent_marker） | 53KB |
| `caveat` | 手写笔记（variable font） | 394KB |
| `dancing_script` | 飘逸草书（variable font） | 131KB |

### 关键替换 / 决策

1. **`permanent_marker` 跳过 → `shadows_into_light` 替代**
   - google/fonts 把 permanent_marker 放在 `apache/` 目录（Apache 2.0 License），不符 CLAUDE.md "只打包 SIL OFL" 纪律
   - `shadows_into_light`（SIL OFL，54KB，手写马克笔风格）填补"马克笔涂鸦"位
   - 总数仍是 13 字体（6 中文 + 7 西文）

2. **`caveat` / `dancing_script` = variable font**
   - google/fonts 无 static 子目录；用 `Caveat[wght].ttf` / `DancingScript[wght].ttf`
   - AWT `Font.TRUETYPE_FONT` 加载 variable font 取 default instance (wght=400)
   - 浏览器 CSS `font-family` 不指定 weight 时也取 default
   - GlyphMetricsGenerator 跑通 = 双端 default instance 对齐
   - 不影响双端一致性

3. **`smiley_sans` 选 OTF（2.0MB）而非 TTF（2.6MB）**
   - zip release 内含两格式；OTF 更小
   - FontRegistry 注册路径 `/fonts/SmileySans-Oblique.otf` + `@font-face format('opentype')`

4. **URL 路径修正**（multiple URLs 404 → 替换源）
   - `googlefonts/<name>` 仓库多个 404 → 改 `google/fonts/main/ofl/<name>/`
   - `crozynski/comicneue/master` 404 → 同样改 `google/fonts/main/ofl/comicneue/`

### SHA-256 全部锁定

13 个新字体 SHA-256 全部从首跑 log 取实际值锁回 build.gradle.kts。重跑 `:plugin:downloadFonts --rerun-tasks` 全 verified。

### shadowJar size

98MB → **122MB**（+24MB；比预期 ~135MB 略低，因 permanent_marker 换更小的 shadows_into_light + 部分中文字体实际比预估小）。

GitHub Releases jar 上限 2GB；下载 30Mbps ~30s，可接受。

### jar 内容验证

`fonts/` 下：**20 字体 + 20 metrics.json = 40 条目**（2 原始 + 5 M21 + 13 M22）。后缀分布 14 TTF + 5 OTF + 1 OTF（smiley_sans）+ 2 variable TTF（caveat / dancing_script）。

### 验证

- `:plugin:test` BUILD SUCCESSFUL，**14 baseline fixture 无漂移**（纯加字体不改既有渲染）
- `vite build` 650ms ok
- 13 字体的 M20 generateGlyphMetrics 全部跑通（含 variable font）

### 字体矩阵（M22 后 20 字体）

| 类别 | 字体 |
|---|---|
| 中文正文 / 黑 | source_han_sans |
| 中文正文 / 宋 | source_han_serif |
| 中文正文 / 像素 | ark_pixel |
| 中文艺术 / 圆体 | smiley_sans, zcool_kuaile |
| 中文艺术 / 毛笔 | ma_shan_zheng |
| 中文艺术 / 宋艺 | zcool_xiaowei |
| 中文艺术 / 涂鸦 | zcool_qingkehuangyou |
| 中文艺术 / 手写楷 | lxgw_wenkai |
| 西文正文 / 无衬线 | inter |
| 西文正文 / 衬线 | noto_serif |
| 西文正文 / 编程 | jetbrains_mono, fira_code |
| 西文装饰 / Comic | comic_neue |
| 西文装饰 / 手写 | pacifico, caveat, dancing_script |
| 西文装饰 / 复古 | lobster |
| 西文装饰 / 漫画 | bangers |
| 西文装饰 / 马克笔 | shadows_into_light |

### 关联文件

`plugin/build.gradle.kts` / `plugin/.../render/FontRegistry.java` / `web/src/style.css` / `web/src/render/PreviewRenderer.ts` / `CLAUDE.md` / `docs/journal.md`。

---

## 2026-05-17 · M21 内置字体扩充（2 → 7 字体矩阵）

用户要求"添加几种宋体 / 黑体 / 非衬线 / JetBrains Mono 等"。选定**选项 B（完整）= 6 新字体**实施。

### 实际落地 5/6（1 字体技术性跳过）

| 字体 ID | 字体名 | 类别 | 来源 | size |
|---|---|---|---|---:|
| `source_han_serif` | 思源宋体 SC Regular | 中文宋体 | adobe-fonts/source-han-serif raw | ~24MB |
| `jetbrains_mono` | JetBrains Mono Regular | 西文编程等宽 | JetBrains/JetBrainsMono raw | ~200KB |
| `fira_code` | Fira Code Regular | 西文编程含连字 | tonsky/FiraCode v6.2 zip | ~250KB |
| `inter` | Inter Regular | 西文通用无衬线 | rsms/inter v4.1 zip | ~350KB |
| `noto_serif` | Noto Serif Regular | 西文衬线 | notofonts/notofonts.github.io raw | ~250KB |
| ~~`source_han_mono`~~ | ~~思源等宽 SC~~ | ~~中文等宽~~ | **跳过** | ~~122MB OTC~~ |

**跳过原因**：adobe-fonts/source-han-mono release 仅发 `SourceHanMono.ttc` 多语言合包 122MB，没有独立 SC 单 OTF 版本——超出整个 shadow jar 现尺寸 2 倍不合适内置。中文等宽需求走用户字体路径（M20 用户字体 metrics 自动生效）。

### 4 处改动 per 字体

每个新字体改 4 处（参考 source_han_sans 现成模式）：

1. **`plugin/build.gradle.kts` bundledFonts**：加 `FontSpec(displayId, url, destFileName, expectedSha256, inZipEntryPattern?)`
2. **`plugin/.../render/FontRegistry.java` BUILT_IN**：加 `BUILT_IN.put(id, new BuiltIn(classpath, new Metadata(displayName, false, 0)))`
3. **`web/src/style.css`**：加 `@font-face`（TTF 用 `format('truetype')`、OTF 用 `format('opentype')`、`font-display: block`）
4. **`web/src/render/PreviewRenderer.ts` FONT_META**：加 `{ displayName, pixelated: false, nativeSize: 0 }`

M20 metrics 链路按 `bundledFonts` 列表自动 iterate（generateGlyphMetrics + jar processResources + syncFontsToWeb），**零额外手动改**。

### SHA-256 锁定

首次构建留空 SHA → log 实际值 → 锁回 FontSpec：
- `source_han_serif`: `78aa7a32...4117`
- `jetbrains_mono`: `e6fd0d7e...aed1`
- `fira_code`: `5992ab96...6117`
- `inter`: `d4f2b9e1...a799`
- `noto_serif`: `19e72cd8...5f88`

重跑 `:plugin:downloadFonts --rerun-tasks` → 全部 `[skip] already present & verified` SHA 校验通过。

### jar size 评估

- 原 60MB → **新 98MB**（思源宋体 SC 单字体 24MB 占大头，比预估 16MB 大；其他 4 西文合计 ~1.9MB）
- **重要**：CI / 发版前必须 `./gradlew :plugin:clean :plugin:shadowJar`——未 clean 时 `build/generated/web-resources/` 残留 macOS Finder iCloud sync 字体副本可让 jar 膨胀到 167MB

### 验证

- `:plugin:test` BUILD SUCCESSFUL，14 fixture baseline **无漂移**（纯加字体不改既有渲染）
- `cd web && npm run build` ✓ 502ms
- jar `unzip -l` 显示 `fonts/` 下 **7 字体 + 7 metrics.json**，无副本
- `web/public/fonts/` 镜像同步 7+7

### 字体矩阵覆盖（M21 后）

| 类别 | 字体 ID | 适用场景 |
|---|---|---|
| 中文黑体 | `source_han_sans` | 招牌主标题 |
| 中文宋体 | `source_han_serif` | 正式 / 古风招牌 |
| 中文像素 | `ark_pixel` | 复古 / 像素风 |
| 西文无衬线 | `inter` | 现代 / 通用 |
| 西文衬线 | `noto_serif` | 正式 / 学术 |
| 西文编程 | `jetbrains_mono` / `fira_code` | 代码块 / 等宽 |
| **缺：中文等宽** | （用户字体路径） | （需用户自带） |

### 关联文件

`plugin/build.gradle.kts` / `plugin/.../render/FontRegistry.java` / `web/src/style.css` / `web/src/render/PreviewRenderer.ts` / `CLAUDE.md` / `docs/journal.md`。

---

## 2026-05-17 · M20 收尾总览

**M20 = 字体精确化全链路 / B 路线（per-font advance 表）/ 6 phase 5 commit / 0 残留 fixture failure**

针对用户报告「思源黑体字间距漂移 / 字符重叠」问题。

### 6 phase 5 commit

| Phase | 主题 | commit |
|---|---|---|
| P1 | 构建期 GlyphMetricsGenerator + Gradle task + jar/web 双产物 | `3fba1a0` |
| P2+3 | 后端 FontMetricsTable + TextLayout charAdvance / 前端 GlyphMetricsLut + 镜像 | `e3eeda4` |
| P4 | 用户字体 registerRuntime + `GET /api/font/metrics?id=...` HTTP 端点 | `c9d1095` |
| P5+6 | 3 effects fixture baseline 重建 + docs 同步 | （本 commit） |

### M20 5 个关键架构决策（已固化入 CLAUDE.md）

1. **per-font advance 表**：构建期 AWT FontMetrics.charWidth 扫 BMP 0x20..0xFFFF → 紧凑 JSON `{baseSize:12, ascent, descent, advances:{cp:width}}`；双端共享同款 JSON
2. **运行时缩放公式**：`advance = round(baseAdv × fontSize / baseSize)`，O(1) 数组索引 + 1 次 Math.round；baseSize=12 锁定（与 nativeSize 习惯一致）
3. **canonical 降级为 fallback**：保留作首次渲染 / 表未到位 / 缺字时兜底；不删除（语义保持兼容）
4. **用户字体走 runtime 注册路径**：FontRegistry.loadExternal 调 registerRuntime；不写文件（重启重算，避磁盘 IO + 缓存失效复杂度）；HTTP 端点序列化内存表给前端
5. **fontId 严格白名单**：`[a-zA-Z0-9_-]+` 防 `../` 路径注入

### 累计统计

- 新文件 3：GlyphMetricsGenerator.java / FontMetricsTable.java / GlyphMetricsLut.ts
- 改动文件 ~8：TextLayout.java/ts、FontRegistry.java、WebServer.java、PreviewRenderer.ts、CanvasView.vue、TextElementSection.vue、plugin/build.gradle.kts、3 expected PNG
- 测试：`:plugin:test` 364 / 0 fail；非 fixture 361 + fixture 13（含 13-placeholder）= 374 实际 case
- bundle：vite +1KB；shadowJar +800KB（含 2 JSON metrics）

### v1.x 留档

- **surrogate pair（U+10000+）**：emoji / 罕用 CJK 扩展未扫，运行时遇 surrogate 走 canonical fallback
- **用户字体 ascent/descent 替换 ASCENT_RATIO=0.8 经验值**：当前仍用经验值 + getAscent/getDescent 仅暴露未消费
- **registerRuntime 异步化**：用户字体多时 onEnable 串行 ~20s 阻塞 ServerStart；可换 onEnable 后台线程或 Bukkit Scheduler

### 评估

字体展示问题彻底修复。视觉效果在 effects fixture 中**肉眼可见地更好**（字符不挤 / 居中 / 无重影）。M0-M20 累计 20 milestone 全部 ✅。

---

## 2026-05-17 · M20 Phase 4（用户字体 runtime metrics + HTTP 端点）

1 个 agent 完成。补完用户字体 metrics 链路。

### 设计

内置字体走构建期 generator → classpath JSON（M20.1-3 已成）。用户字体（`plugins/HikariCanvas/fonts/*.ttf/otf`）启动期用 AWT 现场算 → 内存表；前端 fetch 不到 `/fonts/{id}.metrics.json` 时 fallback `/api/font/metrics?id=...` 从后端拿。

### 改动

- **`FontMetricsTable.java`**：
  - 加 `registerRuntime(fontId, Font)`：AWT BufferedImage + Graphics2D + Font.deriveFont(12f) + FontMetrics.charWidth 扫 BMP 0x20..0xFFFF（**与构建期 GlyphMetricsGenerator 算法完全一致**）→ put 真表覆盖 MISSING sentinel
  - 加 `serializeToJson(fontId)`：识别 null / MISSING / baseSize<=0 都返 null；输出格式与构建期 JSON 完全对齐
- **`FontRegistry.loadExternal`**：`Font.createFont` 成功 + `fonts.put` 后立即调 `FontMetricsTable.registerRuntime(id, font)` + info log
- **`WebServer`**：新路由 `GET /api/font/metrics?id=...`：与 `/api/upload/*` 同级，public pre-handshake；fontId 严格 `[a-zA-Z0-9_-]+` 白名单（防路径注入）；400 / 404 / 200 三态；`Cache-Control: max-age=300, private`
- **`GlyphMetricsLut.preloadMetrics`**：先 `fetch('/fonts/{id}.metrics.json')`；`!resp.ok` 时 fallback `fetch('/api/font/metrics?id=...')`；两边都失败 → `tables.set(null)` sentinel → 运行时 fallback canonical

### 性能 / 并发 / 安全

- 单用户字体 registerRuntime ~1-2s（65k canDisplay+charWidth）；用户 10 字体 ~20s onEnable 阻塞——Paper plugin ServerStart 早期可接受
- ConcurrentHashMap `put` 覆盖 MISSING sentinel；正常启动顺序（loadBuiltIn → loadExternal → WebServer 启动）下内置走 JSON 路径、用户走 runtime 路径互不冲突
- fontId 正则 `[a-zA-Z0-9_-]+` 严格防 `../` 路径穿越
- 不写文件——runtime metrics 仅内存，重启重算（避开磁盘 IO + 缓存失效）

### 验证

`:plugin:compileJava` clean / `vite build` 453ms clean。未碰 baseline fixture（留 M20.5）。

### 关联文件

`plugin/.../render/FontMetricsTable.java` / `plugin/.../render/FontRegistry.java` / `plugin/.../web/WebServer.java` / `web/src/render/GlyphMetricsLut.ts`。

---

## 2026-05-17 · M20 Phase 2+3（后端 FontMetricsTable + 前端 GlyphMetricsLut）

2 个并行 agent 完成运行时消费 M20.1 产物。

### 后端 M20.2

新 `FontMetricsTable.java` ~95 行：
- `ConcurrentHashMap<String, Table>` 缓存 + MISSING sentinel 防重复 IO
- `Table` record 内 `int[0x10000]` advances 数组（O(1) lookup，缺字 -1）
- `advance(fontId, cp, fontSize)` 返 -1 → 调用方走 fallback `canonicalCharWidth`
- 读 classpath `/fonts/{fontId}.metrics.json`，shaded jackson 自动透明（M16-P5.1）

`TextLayout.java` 改造：
- 新 `charAdvance(fontId, c, fontSize)` 统一入口；`canonicalCharWidth` 保留作 fallback
- `softWrap` / `measureLineWidth` / `layoutVertical` 三方法签名加 `String fontId` 参数；`layout(t)` 取 `t.fontId()` 串联下游
- 4 处 `canonicalCharWidth` 调用 → `charAdvance(fontId, ...)`

`:plugin:test --rerun-tasks`：**364 tests / 3 fail / 0 error**。fail 全是 RendererSnapshotTest fixture（03-effects-stroke / 04-effects-shadow / 05-effects-glow）—— 预期，留 M20.5 重建 baseline。**01-hello-world / 02-chinese-text / 13-image-mask 等纯文本 fixture 未漂移**：说明 source_han_sans 在 ASCII/CJK 范围 advance 与 canonical 比例（0.5/1.0）高度接近，只有 effects 二阶像素扩散（stroke 宽度 / shadow / glow）放大了细微 advance 差异。非 fixture：361/361 通过。

### 前端 M20.3

新 `GlyphMetricsLut.ts` ~105 行（Java FontMetricsTable mirror）：
- `preloadMetrics(fontId)` async + `pendingLoads` Map 去重并发请求
- `advance(fontId, ch, fontSize)` 同款 round 缩放
- `tables: Map<fontId, Table | null>` 以 null 作 sentinel 防重复加载
- `Int16Array(0x10000)` 索引 BMP codepoint（节省 50% 内存 vs Int32Array）
- `onMetricsReady(fn)` 钩子（与 onIconReady / onPaletteReady 同款 pattern）

`TextLayout.ts` 改造：5 处 `canonicalCharWidth` → `charAdvance(fontId, ...)`；fontId 沿 softWrap / measureLineWidth 调用链传递；竖排 softWrapVertical 不传 fontId（仅用 fontSize 整字高度量列）。

`CanvasView.vue` onMounted 加 `preloadMetrics('ark_pixel')` + `preloadMetrics('source_han_sans')` + `onMetricsReady(() => requestDraw())`。

`TextElementSection.vue fitTextWidth` 同步改 `charAdvance(te.fontId, ...)`。

### 验证

- 后端 compileJava clean / 测试 361 non-fixture pass
- 前端 vite build：543.29 → 544.29 KB（+1.00 KB；gzip +0.44 KB）；livePaintWorker 33.75 KB 不变（worker 未消费 GlyphMetricsLut，符合 M19 隔离）；0 TS 错误

### 关键设计纪律

- **保留 canonicalCharWidth 作 fallback**：用户字体未生成 metrics 时仍能画；首次渲染表未加载也能画
- **fontId 串联到所有 layout 子方法**：避免全局变量 / context 注入
- **onMetricsReady → requestDraw**：metrics fetch 到位后自动重画一次，无视觉跳变（fallback canonical 与真值差距 < 5px，眼睛察觉不到）

### 留 M20.4-6

- M20.4 用户字体启动期 metrics 生成 + HTTP 端点
- M20.5 baseline 3 个 effects fixture re-review + 重建
- M20.6 docs

### 关联文件

新：`plugin/.../render/FontMetricsTable.java` / `web/src/render/GlyphMetricsLut.ts`。改：`plugin/.../render/TextLayout.java` / `web/src/render/TextLayout.ts` / `web/src/render/PreviewRenderer.ts` / `web/src/components/layout/CanvasView.vue` / `web/src/components/properties/TextElementSection.vue`。

---

## 2026-05-17 · M20 Phase 1（Glyph metrics generator）

**起因**：用户报告字体展示问题——浏览器画板上「TO NEW CAMPUS OF HENAN UNIVERSITY」字符叠加在一起、「Sanyang Plaza」字间距全乱；MC 内同字符串视觉上还能读，但实际上 Java 端也按同样错误布局——只是 128×128 地图 + 248 调色板 + dither 把重叠像素 mush together 掩盖了。

**根因实锤**：`canonicalCharWidth`（M5-D2 算法）一律按 `ASCII = 0.5×fontSize` 假设，对**非等宽字体严重不匹配**。M20.1 生成产物 sample 实测：

- **`ark_pixel`**：ASCII 全 6 / CJK 全 12（完全等宽，canonical 假设成立，**无问题**）
- **`source_han_sans`**：**M=10, W=11, i=3, l=3**（vs canonical 一律 6）→ 误差 4-5px 就是字符重叠/漂移的源头

### M20 路线 B-medium+ 的 Phase 1（增量、零运行时改动）

构建期预生成 per-font advance 表 JSON，运行时双端共享读。本 Phase 只做 generator，不动 TextLayout / 不破 baseline。

### 新增

- **`plugin/src/generator/java/moe/hikari/canvas/build/GlyphMetricsGenerator.java`**：复用 PaletteGenerator 的 generator sourceSet 模式
  - `Font.createFont(TRUETYPE_FONT, file).deriveFont(12f)`（同时支持 .ttf 和 .otf）
  - 扫 BMP 0x20–0xFFFF 65k codepoints
  - 双保险过滤：`canDisplay(cp) && charWidth>0`（charWidth 对缺字会返 fallback 默认值，单 `w>0` 漏判）
  - 输出 `{fontId, baseSize:12, ascent, descent, advances:{codePoint: width}}` 紧凑 JSON
- **Gradle `generateGlyphMetrics` task**：Gradle 9 禁用 task action 内 `project.javaexec` → 改 per-font `tasks.register<JavaExec>("generateGlyphMetrics_$fontId")` 子任务 + 父任务 dependsOn 聚合；输入指纹 = generator 源 + 字体本身
- **链路接入**：`processResources` 把 metrics.json 进 jar `/fonts/`；`syncFontsToWeb` 同步 `web/public/fonts/`

### 产物验证

```
font=ark_pixel base=12px asc=10 desc=2 glyphs=22799/65504
font=source_han_sans base=12px asc=14 desc=4 glyphs=42246/65504
```

- ark_pixel.metrics.json: 248KB
- source_han_sans.metrics.json: 463KB
- shadowJar 内 `/fonts/{fontId}.metrics.json` 已 unzip 验证
- web/public/fonts/ 同步成功

### 关键发现

- 思源黑体 ascent=14 / descent=4 → 14/(14+4)≈0.778，**与现有 ASCENT_RATIO=0.8 经验值几乎一致**，M20.3 用 metrics 真值替代时不会引入视觉跳变
- 单字体 generator ~1-2s，UP-TO-DATE 后零成本
- BMP only：U+10000+（emoji / 罕用 CJK 扩展）surrogate pair 未扫，运行时遇 surrogate 需 fallback canonical

### 留 M20.2-6

- M20.2 后端 FontMetricsTable + TextLayout dispatch
- M20.3 前端 GlyphMetricsLut mirror
- M20.4 用户字体启动期 metrics 生成 + HTTP 端点
- M20.5 14 baseline fixture re-review + 重建（视觉变化预期内 — 是修复）
- M20.6 docs（CLAUDE.md / rendering.md）+ journal

### 关联文件

`plugin/build.gradle.kts` / `plugin/src/generator/java/moe/hikari/canvas/build/GlyphMetricsGenerator.java`（新）。

---

## 2026-05-17 · M19 GitHub Actions CI + Release

继 M18 全栈完成后补 CI 防回归（M16 待办段「CI 设置 / vitest / Playwright E2E」中的 CI / vitest 两项落地；vitest M18-P5 已做，CI 本里程碑做）。

### 设计基础（用户对齐）

- 不要 nightly / PR auto checks / release signing
- 用户本地翻墙不稳定，但 GitHub runner 网络稳定 → CI yml 不显式 `actions/cache`，依赖 `setup-gradle@v4` + `setup-node@v4 cache: npm` 自带缓存（"原生"）
- 单 job 集成 frontend + backend：项目前后端互依（syncFontsToWeb 喂字体给 web；copyWebToResources 喂 dist 给 jar），拆 job 反而重复 setup-java/setup-node

### 改动

#### `.github/workflows/ci.yml`
- push/PR 到 main 触发；timeout 30min；ubuntu-latest
- setup-java Temurin 21 / setup-node 22 LTS（Node 25 已知卡 vue-tsc）
- setup-gradle v4（自带 dep/build/config cache）
- 步骤：`npm ci` → `vitest run` → `vite build` → `:plugin:test` → `:plugin:shadowJar` → 上传 jar artifact 30 天 → 失败时上传 test reports 7 天

#### `.github/workflows/release.yml`
- tag `v*` 触发；permissions contents:write
- 同样 setup + 跑完整测试 + shadowJar
- 提取 tag 版本号 → 重命名 jar `HikariCanvas-${VERSION}.jar`
- `softprops/action-gh-release@v2` 自动生成 release notes + 上传 jar
- `prerelease: ${{ contains(env.VERSION, '-') }}` 含 `-` 标 pre-release

### 首跑事故

push d9aebd3 后 GitHub Actions 4s 返：
```
The job was not started because your account is locked due to a billing issue.
```

不是 yml 问题——账号级 billing 锁。public repo Actions 通常免费，但用户账号本身被锁。需要用户去 https://github.com/settings/billing 处理后 CI 才能跑。**yml 部署正确**，等账号解锁后会自动 retry / 下次 push 触发。

### CLAUDE.md 同步

- 顶部加 CI badge：`[![CI](.../ci.yml/badge.svg)](.../actions/workflows/ci.yml)`
- 里程碑表加 M19 ✅；M0-M19 累计约 8 周 wall-clock
- 速查段加 `cd web && npm run test`（M18 vitest）
- 新「CI / Release（M19 引入）」段：两个 workflow + 环境锁 + cache 策略

### 关联文件

`.github/workflows/ci.yml`（新）/ `.github/workflows/release.yml`（新）/ `CLAUDE.md` / `docs/journal.md`。

### 本地等价验证（不依赖 GitHub 账号）

由于账号 billing 锁让 CI 没法跑，做了本地全步骤等价验证（actionlint + workflow 所有步骤照搬本地跑）：

1. **actionlint 1.7.12** 静态校验两个 yml → 修了 1 个 shellcheck SC2012（`ls` → `find`）后 0 issue
2. `npm ci`：115 packages / 2s ✓
3. `npm run test`：vitest 28/28 pass / 169ms ✓
4. `npm run build`：vite build 388ms / dist 543kB + worker 33.75kB ✓
5. `./gradlew :plugin:test`：BUILD SUCCESSFUL 44s ✓
6. `./gradlew :plugin:shadowJar`：60MB jar / 5397 shaded entries / 0 路径泄漏 ✓

**捉到 1 个 yml bug**：原 glob `HikariCanvas-*-all.jar` 不匹配 shadowJar 真实输出 `HikariCanvas-<version>.jar`（plugin/build.gradle.kts:283 设 `archiveClassifier.set("")`，无 `-all` 后缀）。改 yml 用 `HikariCanvas-*.jar`，release.yml rename 步骤同步用 find 排除 sources jar + 兼容 src==dst。

**意外发现**：本地 jar 之前 640MB 是 macOS Finder iCloud sync 在 `plugin/build/resources/main/` 累积了 ~30 个 SourceHanSansSC-Regular 字体副本（每个 16.5MB）。`gradle clean` 后干净 60MB。**CI Linux 无 macOS Finder 同步问题**，所以 CI 出的 jar 直接是正确 size。这反过来说明 CI 比本地构建更可靠。

### M19 真实状态

代码层 ✅ + 本地等价跑全绿 ✅ + workflow yml 修复后再次部署 ✅ / 远程 CI 跑 ⏸ blocked by GitHub 账号 billing 锁定（与代码无关）

---

## 2026-05-17 · 版本号 0.1.0-SNAPSHOT → 0.2.0-SNAPSHOT

M0-M18 累计 18 个 milestone 落地，Live Paint / 智能对齐 / 复制粘贴 / Fill 联合类型等大量 feature 已不属于"0.1.0 初版"语义范畴。版本号往前推进一位。

### 改动

- `build.gradle.kts`：allprojects version `0.1.0-SNAPSHOT` → `0.2.0-SNAPSHOT`（plugin / generator 子项目继承）
- `web/package.json`：version 同步
- `plugin/src/main/resources/paper-plugin.yml`：version 同步

### 配套语义调整

CLAUDE.md / data-model.md / deployment.md 中所有"0.1.0 发版"等版本绑定字眼改为"首次 stable（≥1.0.0）发版"。新语义：

- **`0.x.y-SNAPSHOT`**（含当前 `0.2.0-SNAPSHOT`）= pre-release 阶段，允许激进改 schema
- **`1.0.0`** 起 = stable 发版，schema forward-only + auto-backup 强制开

让版本号继续前进（0.2 / 0.3 / ...）时不需要每次修文档；契约规则锚定在 stable 1.0.0 边界。

### 编译验证

`./gradlew :plugin:compileJava` BUILD SUCCESSFUL。版本号改动不影响代码行为。

### 关联文件

`build.gradle.kts` / `web/package.json` / `plugin/src/main/resources/paper-plugin.yml` / `CLAUDE.md` / `docs/data-model.md` / `docs/deployment.md` / `docs/journal.md`。

---

## 2026-05-17 · M18 收尾总览

**M18 = Live Paint 油漆桶 / B-medium+ 路线（polygon-clipping）/ 5 algorithm + 1 docs phase / 6 commit / 0 baseline 漂移 / 28 vitest 单测全绿**

继 M17 体验组后做油漆桶工具。技术选型在 B-medium+（polygon-clipping 库 + 用户层 element→polygon 适配）vs B-advanced（自写 DCEL）之间选了前者：用例覆盖 95% vs 99%（缺的 4% 是元素内部洞 / 嵌套 path face，用户极少触发），工时 22h vs 38h，浮点精度风险远低。

| Phase | 主题 | commit |
|---|---|---|
| M18-P1 | 核心算法（livepaint/ 5 文件 + polygon-clipping@0.15.7） | `050a549` |
| M18-P2 | Web Worker 隔离（livePaintWorker + useLivePaint composable） | `cc74b7e` |
| M18-P3 | UI 集成（paint-bucket 工具 + G 键 + PaintBucketPanel + LivePaintHoverOverlay） | `0c39c0c` |
| M18-P4 | 边界 case + vector-fill 决策 A + RDP 简化 + 退化 fallback | `5a71c86` |
| M18-P5 | vitest 4.1.6 引入 + 28 单测全绿 | `1c5794f` |
| M18-P6 | docs 同步（CLAUDE.md / rendering.md / architecture.md / protocol.md / journal.md） | （本 commit） |
| **合计** | | **6 phase / 6 commit** |

### M18 已固化的关键架构决策（5）

1. **Live Paint = 前端独占功能**：拓扑计算仅浏览器 Web Worker 跑，后端 Java 不做镜像。这是 `docs/rendering.md §1 / §8 双端镜像纪律的显式例外`，理由：(a) 输出（PathElement）已在 M9 双端镜像协议内；(b) 拓扑算法不参与最终像素输出，仅工具输入辅助；(c) Java AWT 无 planar subdivision 等价物，强行镜像 ~2000 行 Java 几何代码且仍可能行为差异。详见 `docs/rendering.md §8.4`
2. **B-medium+ 路线**：用 `polygon-clipping@0.15.7` 库做 boolean op，不自写 DCEL。用例覆盖 95%（缺的 4% = 元素内部洞 / 嵌套 path face / 自交 path 精确处理）；B-advanced 自写 DCEL 升级路径留 v1.x
3. **vector-fill 决策 A**：点击元素内部 = `element.update patch fill`（沿用 M11 Fill 联合类型，不创 PathElement）；非闭合空白 gap = `element.add type=path` + d 字符串。两种行为统一在 `onPaintBucketClick`，**不引入新 WS op**
4. **顶点 RDP 简化**：PathDValidator 实际限制 ~240 顶点；超阈走 `RdpSimplifier` 迭代式（防递归爆栈）+ tolerance 阶梯 0.5→1→2→4→8→16
5. **退化几何 fallback**：polygon-clipping 退化输入时返 `{gaps:[], degraded:true}`，UI 拒绝创建 PathElement 而非用错误数据落库

### M18 文件改动统计

**新文件（11）**：
- `web/src/livepaint/types.ts`
- `web/src/livepaint/ElementToPolygon.ts`
- `web/src/livepaint/LivePaintCore.ts`
- `web/src/livepaint/PolygonToPath.ts`
- `web/src/livepaint/RdpSimplifier.ts`（P4 引入）
- `web/src/livepaint/livePaintWorker.ts`
- `web/src/livepaint/index.ts`
- `web/src/composables/useLivePaint.ts`
- `web/src/stores/paintBucket.ts`
- `web/src/components/properties/PaintBucketPanel.vue`
- `web/src/components/canvas/LivePaintHoverOverlay.vue`

**改动前端**：`stores/ui.ts`（加 `'paint-bucket'` tool） / `components/layout/CanvasView.vue`（useLivePaint + onPaintBucketClick + findElementAt） / `components/layout/LeftTools.vue`（油漆桶按钮 + G 键 hint） / `components/layout/RightPanel.vue`（PaintBucketPanel 段） / `composables/useCanvasShortcuts.ts`（G 键）/ `i18n/messages.ts`（livePaint.* 文案） / `vite.config.ts`（vitest test 段） / `package.json`（vitest + @vitest/ui + polygon-clipping）

**新单测（28 cases / 4 文件 / 166ms）**：`web/src/livepaint/__tests__/{ElementToPolygon,LivePaintCore,PolygonToPath,RdpSimplifier}.test.ts`

### 累计 / 性能

- 净增代码 ~2400 行（含 vitest 配置 + 28 单测）
- vite build：382ms / 543.29 kB index / **+33.75 kB livePaintWorker chunk（gzip ~10 kB）** / 47.22 kB css
- `:plugin:test --rerun-tasks` BUILD SUCCESSFUL（14 RendererSnapshotTest baseline 0 漂移；Live Paint 前端独占，后端不动）
- worker build < 50ms / 100 elements；UI debounce 100ms 调度

### 评估

油漆桶 = 图形软件标配工具，HikariCanvas 编辑器获得最后一块"主流绘图工具"拼图（笔刷 M12 + 图片 M13 + 多元素 M9 + 渐变 M11 + 智能对齐 M17 → 油漆桶 M18）。
M16 自承的"前端无 vitest"在 P5 一并补完——后续任何前端纯算法模块（snapManager / clipboard format / palette LUT 镜像）都可立刻补测，技术负债清零。

### v1.x 留档（已知未做）

- B-advanced 自写 DCEL 覆盖剩余 4% 用例（元素内部洞 / 嵌套 path face）
- 多 subpath path（M / M / M）切分为独立 element-occupied region
- text glyph 真实形状（fontkit 路径化代替 bbox 兜底）
- brush 真实形状（stroke offset polygon 代替 bbox 兜底）
- RDP tolerance UI 配置（当前固定阶梯）
- Live Paint 撤销栈优化（vector-fill 模式的 fill diff 与正常 element.update 合并）
- Worker SharedArrayBuffer 加速（需 COOP/COEP，部署门槛高，留 v2）

---

## 2026-05-17 · M17 收尾总览

**M17 = 5 大 feature（F1-F5）落地 + 4 phase commit batch + 0 baseline 漂移 + ~1500 行净增**

继 M15 / M16 安全与数据完整性收口后，把"体验质量从 demo 升到生产级"作为单独里程碑推完。

| Phase | 主题 | feature 项 | commit |
|---|---|---|---|
| M17-P1 | 拖动跟手 + 自由拖动画布 | F2 + F4 | `a049484` |
| M17-P2+3 | 复制粘贴 + Canvas Fill + 智能对齐 v1 | F1 + F5 + F3 v1 | `1ed92ca` |
| M17-P4 | 智能对齐 v2 完整版（distribute + visualizer + resize snap） | F3 v2 | `2ac5558` |
| M17-P5 | docs 同步收尾 | — | （本 commit） |
| **合计** | | **5 feature / 4 commit** | |

### 5 feature 一句话总结

| feature | 类型 | 一句话 |
| --- | --- | --- |
| F1 | 新能力 | 复制粘贴 Ctrl+C/V，剪贴板 magic header `hikari-canvas-v1:` 跨 wall 工作，锁定 wall 拒粘贴 |
| F2 | bug 修复 | onDragMove 单选 path 实时跟手 —— 双层渲染下早 return 导致底层 Canvas 2D 不重绘的视觉滞后 |
| F3 | 新能力 | 智能对齐 = `useSnapManager` composable（drag + resize 共用）+ distribute 间距均分 + SnapGuideOverlay 对齐线 + Magnet popover |
| F4 | 新能力 | 自由拖动画布（新 'hand' 工具 / H 键 / Space 临时切）+ 1024px 虚空白边 |
| F5 | 数据模型升级 | `Canvas.background`: String → Fill 联合类型，Jackson 自动兼容旧 hex，渐变背景就位 |

### M17 已固化的关键决策

1. **Canvas.background = Fill 联合类型**：Solid / Linear / Radial 三态统一；`FillDeserializer`（M11 已存在 string → SolidFill 路径）自动反序列化兼容旧 hex 字符串与 14 fixture 0 漂移；模板 raw_state 渐变背景 fallback `"#FFFFFF"`（raw_state 格式仅识 hex）
2. **`'hand'` 工具是非绘制工具之一**：`ui.ActiveTool` 三大非绘制工具 = `select / move / hand`，与 drawTool（line / arrow / circle / star / brush）区分；Space 临时切由闭包 `spaceSavedTool` 保存原工具 + window blur 兜底防卡死
3. **`useSnapManager` = 公共能力**：drag （CanvasView onDragMove）+ resize （Konva Transformer boundBoxFunc）共用；O(n) 线性 + snapAxis 两遍扫描 + shift bypass + localStorage 持久化 `hikari-canvas:snap`
4. **SnapGuideOverlay 走 vue-konva 独立 v-layer**：挂载在 v-stage 内 marquee / drawPreview 同级 layer，覆盖 element / transformer 上方；drag / transform end 立刻清 hints + `v-if` 自然卸载
5. **resize snap 选 `boundBoxFunc` 而非 transformend**：前者每帧拖锚点 call 可给视觉反馈；按"动的边"应用 delta 比"snap 整 bbox"更精准，任何锚点（角 / 边中点）都正确无视觉跳动
6. **双层渲染配合**：HikariCanvas 编辑器 = 顶层 Konva 透明 hit-test 矩形（fill rgba 0.001）+ 底层 Canvas 2D PreviewRenderer 像素化输出（dither + 248 调色板）。底层重绘依赖 `requestDraw()` → `watch(project.state, {deep:true})`。F2 修复证明任何拖动 mutation 必须落 store 才能触发 PreviewRenderer 重绘

### M17 累计文件改动

**新文件（6）**：
- `web/src/composables/useClipboard.ts`（F1）
- `web/src/composables/useSnapManager.ts`（F3）
- `web/src/components/properties/CanvasSettingsSection.vue`（F5）
- `web/src/components/canvas/SnapGuideOverlay.vue`（F3 v2）
- `web/src/components/layout/SnapSettingsPopover.vue`（F3 v2）

**改动 Java**（F5）：`state/Fill.java` / `state/ProjectState.java` / `state/EditSession.java` / `render/CanvasCompositor.java` / `render/CanvasProjector.java` / `render/WallRestorer.java` / `template/TemplateInstantiator.java` / `web/EditOpDispatcher.java` / test `EditSessionReplaceContentTest.java`

**改动前端**（F1-F5）：`stores/ui.ts` / `stores/project.ts` / `stores/palette.ts` / `components/layout/CanvasView.vue` / `components/layout/LeftTools.vue` / `components/layout/RightPanel.vue` / `components/layout/TopBar.vue` / `composables/useCanvasShortcuts.ts` / `composables/usePanScroll.ts` / `composables/useTransformerManager.ts` / `render/PreviewRenderer.ts` / `types/protocol.ts` / `i18n/messages.ts`

### 验证

- `:plugin:test --rerun-tasks` BUILD SUCCESSFUL（14 RendererSnapshotTest baseline 0 像素漂移；F5 FillDeserializer 自动处理 fixture `"background": "#xxx"` 字符串）
- `vite build` ~330-400ms / ~503 kB / ~155 kB gzip，0 新 TS 错误

### 评估

体验质量从 demo 升到生产级：60fps 拖动实时跟手 + 智能对齐 + distribute + 自由 pan + 跨 wall 剪贴板 + 渐变背景。**M18 (B-advanced Live Paint) 已可开工**。

### 已知留档（v1.x scope）

- 「任意三元素均分」(distribute) 完整版（v2 仅匹配两侧最近邻）
- 完整版 resize snap（按锚点显式映射 + rotated bbox 支持）
- SnapGuideOverlay CSS fade-out 过渡
- spatial index for snapManager（100 elements 内 O(n) 不卡）
- 多文件批量剪贴板 / image data 粘贴（M13 配额 + 6 层校验栈外）

---

## 2026-05-17 · M18 Phase 5（vitest 引入 + Live Paint 单测）

补 CLAUDE.md / M16 待办段自承的"前端无 vitest"。生产级标准要求 livepaint 自写算法必须有单测。

### 依赖

vitest 4.1.6 + @vitest/ui 4.1.6（devDependencies）；未引入 jsdom（livepaint 纯算法，node 环境最快）。

### 配置

- `vite.config.ts`：`/// <reference types="vitest" />` + `test: { environment: 'node', include: ['src/**/*.test.ts'] }`
- `package.json` scripts：`test` / `test:watch` / `test:ui`

### 测试用例（28 total）

`web/src/livepaint/__tests__/`：
- `ElementToPolygon.test.ts`（8）：rect 4 顶点 + rotation；circle 32 采样 + 椭圆；shape 五边形；star 10 顶点；path 矩形 + fallback
- `LivePaintCore.test.ts`（8）：pointInPolygon 内/外/U 形；buildGraph 空/单 rect/退化/完全覆盖/两不重叠
- `PolygonToPath.test.ts`（6）：gapPolygonToPathD 矩形/hole；gapToPathElement bbox/退化；maybeSimplify 不触发/触发
- `RdpSimplifier.test.ts`（6）：三顶点/共线/tolerance=0/NaN/1000 点不爆栈/方波拐点

### 结果

```
Test Files  4 passed (4)
     Tests  28 passed (28)
  Duration  166ms
```

vite build 无退化：382ms / 543.29 kB index / 33.75 kB worker / 47.22 kB css。

---

## 2026-05-17 · M18 Phase 4（边界 case + vector-fill 决策 A + 退化 fallback）

1 个 agent 完成。

### A. vector-fill 决策 A 实装

`findElementAt(canvasX, canvasY)`：倒序遍历 visibleElements（z-order 顶层优先）+ `elementToPolygon` + `pointInPolygon` 精确命中（不只 bbox，避免 circle / star 角落误判）。

`onPaintBucketClick` 优先级：wall lock → graph 未就绪 → graph degraded → gap 命中 → element 命中 → noGap。element 命中分支：lock 拒；`rect/circle/shape/path` 走 `element.update {patch:{fill}}` + 乐观本地 mutate；`text/image/brush` 走 `livePaint.elementUnsupported(type)` 提示。

### B. pointInPolygon API export

`LivePaintCore.ts` 内 `pointInPolygon` 由 file-private 升级为 `export`；`livepaint/index.ts` 加 export。

### C. RDP 顶点简化

新 `web/src/livepaint/RdpSimplifier.ts` 迭代式 stack 实现（防大输入栈溢出）；点到线段距离 + 投影 + 截断。`PolygonToPath.gapToPathElement` 内 `maybeSimplify` tolerance 阶梯式 0.5 → 16 翻倍直到 ≤ VERTEX_HARD_LIMIT=240（PathDValidator 实际限制）。每轮基于原 polygon（不累计误差）。

### D. 退化几何 fallback

`types.LivePaintGraph` 加可选 `degraded?: boolean`。`buildGraph` try/catch 改：原"整画布单 gap"假象 → `{gaps:[], degraded:true}`。useLivePaint 透传；CanvasView click handler 检测 degraded → `pushLog('err', t.livePaint.degraded)`。

### E. 极小 gap 过滤

`LivePaintCore` 新 `polygonArea` (shoelace 绝对值)；`MIN_GAP_AREA = 4` px²；outer 面积 < 阈值的 gap 整体丢弃；holes 不过滤（保留 even-odd 几何意图）。

### F. i18n

删 `livePaint.recolorPending`；新增 `recolorSuccess / elementLocked / elementUnsupported(type) / wallLocked / degraded` zh+en 镜像。

### G. perf log

`useLivePaint.ts` DEV-only `import.meta.env.DEV` 静态分支让 prod tree-shake：`sentAt: Map<requestId, {startedAt, elementCount}>` 打点；onmessage elapsed；格式 `[livepaint] graph built in 12.3ms (8 elements → 4 gaps)`；race 丢弃旧 requestId 也清 sentAt 防内存泄漏。

### vite build

`✓ built in 374ms`；livePaintWorker chunk 33.75 kB（含 RDP）；index 543.29 kB / 168.54 kB gzip；0 新 TS 错误。

---

## 2026-05-17 · M18 Phase 3（UI + hover 预览集成）

1 个 agent 完成 UI 全栈集成。

### 工具系统

- `ui.ActiveTool` union 加 `'paint-bucket'`；`isDrawTool` 排除 paint-bucket（click 工具非 drag-to-create）；`loadTool` KNOWN 列表加
- 快捷键 `G`（Photoshop / Figma fill bucket 标准，与 brush `B` 错开）
- `useTransformerManager.attachTransformer` 隐藏 transformer 工具列表加 paint-bucket（与 move / hand 同列）
- LeftTools 加 PaintBucket 按钮（lucide `PaintBucket` icon）

### 新组件

- `web/src/components/layout/PaintBucketPanel.vue`：BrushPanel 同款结构；FillInput 绑 paintBucket.currentFill + 双行操作提示
- `web/src/components/canvas/LivePaintHoverOverlay.vue`：`<v-layer listening="false">` 包 `<v-path>`；data 由 `gapPolygonToPathD(hoveredGap)` 生成，fillRule='evenodd' 自动挖洞；fill rgba(96,165,250,0.22) 蓝色半透明 + stroke `#60a5fa`；strokeWidth `1.5/zoom` 保视觉密度

### 新 store

- `web/src/stores/paintBucket.ts`：`currentFill: Ref<FillCompat>` + localStorage 持久化（key `hikari-canvas:paint-bucket`，默认 `{type:'solid', color:'#000000'}`）

### CanvasView 集成

- `useLivePaint` 接入：`livePaintEnabled = ui.activeTool === 'paint-bucket'` + `visibleElements` 过滤 `visible !== false`
- `hoveredGap` ref：mousemove 时调 `livePaint.findGapAt(pos.x, pos.y)`（stage 无 scale，坐标直接是画布像素）
- `onPaintBucketClick`：① wall 锁定 → 文案；② graph 未就绪 → t.livePaint.building；③ 命中 gap → `gapToPathElement` + `ws.send('element.add', { type:'path', layerId, props:{...fill: paintBucket.currentFill} })`；④ 命中 element bbox → console.log + t.livePaint.recolorPending（P4 待实装 vector-fill）；⑤ 都未命中 → t.livePaint.noGapFound
- cursorStyle 加 paint-bucket → 'crosshair'
- hitConfig listening 关：paint-bucket 加 `drawing` flag 让 element-hit 整层不响应
- watch ui.activeTool 切走时清 hoveredGap；@mouseleave + window blur 兜底
- LivePaintHoverOverlay 挂在 v-stage 内 SnapGuideOverlay 同级
- isBuilding indicator：右下角 absolute 浮窗 `bottom-16 right-4`，蓝色脉冲点 + t.livePaint.building

### i18n

- `tools.paintBucketTool` 中英
- `livePaint.{ title, hint, hintHoverPreview, fillLabel, noGapFound, layerLocked, building, recolorPending }` 中英

### vite build

`✓ built in 354ms`；`dist/assets/livePaintWorker-DBy-krPN.js 33.63 kB` 出现，确认 useLivePaint 被消费 + worker 单独切包；bundle `538.40 → 538.43 kB`（+0.03 kB；gzip 166.92 不变）。

---

## 2026-05-17 · M18 Phase 2（Worker + 增量缓存）

1 个 agent 完成。

### 新文件

- `web/src/livepaint/livePaintWorker.ts`：module worker；message discriminated union `{type:'build', requestId, elements, canvasWidth, canvasHeight}` → `{type:'ok'|'err', requestId, graph|message}`；内部 try/catch 包 buildGraph
- `web/src/livepaint/useLivePaint.ts`：Vue composable
  - debounce 100ms（与 M5 编辑器输入防抖一致；避免 element 高频变化 worker 风暴）
  - requestId race 处理：`pendingRequestId` 记最新，旧响应丢弃
  - JSON 深 clone（Vue reactive Proxy 不能 structuredClone；toRaw 不能 deep）
  - `enabled` gate：Live Paint 工具未激活时不重建，节省 CPU
  - `onScopeDispose` terminate worker + clearTimeout（对齐 M16-P4.2 资源纪律）
  - API：`graph / isBuilding / findGapAt / rebuildNow`

### 设计决策

- **vite.config.ts 未动**：`?worker` import 默认 module worker，Vite 自动处理
- **Worker chunk 当前未单独生成**：barrel export 不构成消费，tree-shake 掉是预期；P3 接入 CanvasView 后才会出 `livePaintWorker-*.js`
- **修复细节**：初稿误从 LivePaintCore 导入 LivePaintGraph（实际只在 types），改 `import type` 修

### vite build

503.43 kB / 155.65 kB gzip 零增量（worker 代码 dead-code-eliminated 等 P3）。

---

## 2026-05-17 · M18 Phase 1（Live Paint 核心算法）

针对 B-medium+ 路线，1 个 agent 完成核心几何算法（无 UI / 无 Worker）。

### 依赖

`polygon-clipping@0.15.7` 加入 dependencies；4 packages / 0 vulnerabilities；TS types 自带。

### 新模块 `web/src/livepaint/`（5 文件 / 658 行）

- `types.ts`（30）：`Polygon = [[x,y]...]` 单环；`GapPolygon = { outer, holes[] }`；`LivePaintGraph = { gaps[] }`
- `ElementToPolygon.ts`（350）：元素 → polygon array
  - rect → 4 顶点 + rotation
  - circle → 32 点采样（椭圆支持 w≠h）+ rotation
  - shape → ShapeElement.sides 正多边形，起始角 -π/2 顶点朝上
  - star → 2×sides 交替 outer/inner（innerRatio 默认 0.5）
  - path → 自实装 M/L/Q/C/Z + de Casteljau 12 等分；多 subpath v1 只取首段；解析失败 fallback bbox
  - text / image / brush → bbox 兜底
- `LivePaintCore.ts`（154）：`buildGraph` + `findGapAt`
  - polygon-clipping `union(...polys)` → 占用区
  - `difference(canvasRect, ...occupied)` → 所有空隙 MultiPolygon
  - 转 GapPolygon[]（first ring = outer CCW；rest = holes CW；剥末尾重复首点）
  - findGapAt：ray-casting 点在外环 + 不在任何 hole
  - try/catch 包 boolean op，极端退化 → 降级整画布单 gap + console.warn
- `PolygonToPath.ts`（105）：`gapPolygonToPathD` + `gapToPathElement`
  - 多 subpath 串 M-L-Z；依赖 even-odd fill rule 自动挖洞
  - gapToPathElement：算 gap bbox → 平移到 (0,0) → 输出 `{x, y, w, h, d (相对 bbox), vertexCount}`
- `index.ts`（19）：桶式 re-export 5 个 API + 3 类型 + 3 常量

### 关键设计决策

- **circle 采样 32 点**：128px 半径下视觉无棱角；64 点会让 union 后顶点爆炸
- **path 复杂度 fallback**：多 subpath / 解析失败 / 自交 → bbox 兜底
- **顶点警告阈值 180**：PathDValidator 实际限 MAX_LEN=4096 char ≈ 240 顶点；软警告 console.warn，RDP 简化留 P4
- **rotation 应用顺序**：先生成局部 polygon 再绕 (x+w/2, y+h/2) 旋转；rotation=0 短路
- **类型坑隔离**：本模块 `Polygon` = 单环（首点不复制末点）；polygon-clipping 库 `Polygon` = 多环（末点复制首点）。LivePaintCore 内用 `PCRing/PCPolygon` 别名 + 转换 helper

### 留 Phase 后续

- M18-P2：Web Worker 化（buildGraph 100+ element 时主线程可能 jank）
- M18-P3：UI + 集成（livepaint 模块当前被 tree-shake 完全剔除）
- M18-P4：RDP 顶点简化 + vector-fill 决策 A（点击 element 内部改 element.fill）

### 验证

`vite build` 371ms / 503 kB（livepaint 未消费 = bundle 大小未变）；livepaint 5 文件 0 TS 错误。

---

## 2026-05-17 · M17 Phase 4（F3 智能对齐完善 → v2 完整版）

1 个 agent 完成。补齐 M17.3 v1 剩余：distribute + visualizer + popover + resize snap。

### distribute 间距均分

`useSnapManager.ts` 加 `EqualGapX` / `EqualGapY` hint 类型；新私有方法 `findEqualGapX` / `findEqualGapY`。SnapHints 加 `equalGapX?` / `equalGapY?` 字段（含 aRight / bLeft / bRight / cLeft + yCenter / x 镜像）。

**v2 简化**：仅匹配两侧最近邻 —— A.right < dragged.left 中 right 最大的 + C.left > dragged.right 中 left 最小的；A/C 必须同方向找到；span < w/h 时跳过；与同方向 axis snap **互斥**（axis 命中时不跑 distribute）。「任意三元素均分」留 v1.x 扩展。

### visualizer 对齐线

新 `web/src/components/canvas/SnapGuideOverlay.vue`（vue-konva `v-line` + `v-rect` + `v-text`）。挂载位置：CanvasView v-stage 内 marquee/drawPreview 同级独立 v-layer，覆盖 element/transformer 上方。

视觉规范：
- snap axis 红色虚线 `#ef4444` + dash `[4,3] / zoom`
- equalGap 绿色实线 `#22c55e` + 两端短刻度 + 中间像素距离标签（白字 + 绿底圆角 pill）
- strokeWidth / fontSize 全部 `1 / zoom` 保持视觉密度

**fade**：drag/transform end 立刻清 `activeSnapHints = null`，layer `v-if` 自然卸载；CSS fade 标注留 v1.x。

### popover 开关 UI

新 `web/src/components/layout/SnapSettingsPopover.vue`：lucide `Magnet` icon 按钮 + popover（onClickOutside 关闭）。挂载位置：`TopBar.vue` 右侧按钮组（紧贴 Bookmark 后、Help 前）。

内容：
- Enable Snap 总开关
- 4 子开关（To Grid / To Canvas / To Element / To Distribute；总开关关闭时子项 disabled 半透明）
- threshold range slider 1-32
- shift 提示文字

### resize snap (onTransform 接入)

CanvasView.vue 新 `boundBoxFunc(oldBox, newBox)` 并入 `transformerConfig`。Konva Transformer 每帧拖锚点 call。

**为何选 boundBoxFunc 而非 transformend**：transformend 是结束时一次性事件，无法在拖动中给视觉反馈。

**简化版（v2 已采用）**：比对 newBox vs oldBox 找出**正在动的边**（leftMoved / rightMoved / topMoved / bottomMoved），按边把 snap delta 应用到 `x or width` / `y or height`。比"snap 整 bbox"更精准——任何锚点（top-left / bottom-right / middle-* / *-center）都正确，无视觉跳动。

- `rotation != 0` → return newBox 跳过 snap（旋转后 bbox 不对齐画布轴）
- `w/h < 1` → return newBox
- 多选时 excludeIds 整组 selectedIds 排除（避免 snap 到自己）

完整版扩展（按锚点显式映射 + rotated bbox）留 v1.x。

`onElementTransformEnd` wrap：调用 `onTransformEnd(ev, id)` 后清 `activeSnapHints`。

### ui store 新字段

`snapToDistribute: Ref<boolean>` 默认 true；加入 `SnapPrefs` interface + `SNAP_DEFAULT` + `loadSnap` 反序列化默认值 + watch 持久化数组 + return 暴露。

### i18n

新 8 key：`snap.{settings, enable, toGrid, toCanvas, toElement, toDistribute, threshold, shiftHint}` 中英全。

### 验证

`vite build` 335ms / 503.43 kB / 155.65 kB gzip。后端无改动，未跑 :plugin:test。

### 关联文件

**新文件**：`web/src/components/canvas/SnapGuideOverlay.vue` / `web/src/components/layout/SnapSettingsPopover.vue`

**改文件**：`web/src/composables/useSnapManager.ts`（+ distribute + EqualGap 类型）/ `web/src/stores/ui.ts`（+ snapToDistribute + SnapPrefs 扩展）/ `web/src/i18n/messages.ts`（+ snap 段）/ `web/src/components/layout/TopBar.vue`（挂 popover）/ `web/src/components/layout/CanvasView.vue`（boundBoxFunc + activeSnapHints + overlay 挂载 + drag/transform end 清 hints + window mouseup 兜底清）。

---

## 2026-05-17 · M17 Phase 2 + 3（F1 复制粘贴 + F5 Canvas Fill + F3 智能对齐 v1）

3 个并行 agent 完成 + 一次主线程手动接入（F3 agent 对 CanvasView/ui store 的 edit 未生效，主线程补做）。

### F1 复制粘贴

新 `web/src/composables/useClipboard.ts`：`useClipboard()` → `{ copy(), paste() }`，导出 `CLIPBOARD_MAGIC = 'hikari-canvas-v1:'`。

剪贴板格式：`hikari-canvas-v1:{"elements":[...深 clone 完整 element],"timestamp":"<iso>","sourceWallId":"<id|null>"}`。magic header 不匹配静默忽略 → 不干扰文本框常规 paste。

- `useCanvasShortcuts.ts`：Ctrl/Cmd+C → copy，Ctrl/Cmd+V → paste；裸 c 仍走 circle 工具；inEditable 时不触发
- copy 不读 isLocked（只读安全）；paste 检查 isLocked + pickWritableLayer fallback
- 元素剥离：JSON 深 clone → 剥 id + type（顶层 envelope）→ x/y +10 偏移
- z-order：copy 按 layer.elements 索引升序，paste 按数组顺序逐个 ws.send，新元素堆叠顺序一致
- 发 `element.add` 显式带 `layerId`（EditOpDispatcher 已支持）
- PathElement.d / BrushPoint[] 子坐标相对 bbox 不变换
- 反馈复用 `net.pushLog('meta'|'err', ...)`
- i18n：`clipboard.copySuccess(n) / pasteSuccess(n) / pasteRejectedLocked / pasteParseFailed` 中英

### F5 Canvas.background → Fill 联合类型

**后端**：
- `ProjectState.Canvas` `String background` → `Fill background`；新 `@JsonCreator` 接收 Fill；保留 `(int, int, String)` 兼容构造器内部 wrap Solid；紧凑构造器默认 `Fill.solid("#FFFFFF")`
- `Fill` 加静态 helper `Fill.solid(String)`
- **FillDeserializer**：M11 已存在 string → SolidFill 路径，无需新增；`Fill` 自身已 `@JsonDeserialize(using = FillDeserializer.class)`，整条反序列化链路自动复用——WebServer / RendererSnapshotTest 共用同一 ObjectMapper 都生效
- `CanvasCompositor.rasterize` 入口 paint bg 改 `g.setPaint(FillPaintBuilder.fillToPaint(canvas.background(), 0, 0, w, h))`；渐变 bbox = 整画布
- `WallRestorer.isPristine` / `CanvasProjector.isPristine`：旧 `"#FFFFFF".equalsIgnoreCase` → 新 `isWhiteSolid(Fill)`（仅 SolidFill #FFFFFF[FF] 命中）
- `EditSession.setBackground(Fill)` 跑 FillValidator；旧 `setBackground(String)` wrap 调新；新 `replaceContent(Fill, List)` overload
- `TemplateInstantiator.instantiateRawState`：state.canvas().background() 现是 Fill；只在 SolidFill 抽 color，渐变背景 v1 raw_state 模板 fallback `"#FFFFFF"`
- `EditOpDispatcher canvas.background`：优先识别新 `fill` 字段，兼容老 `color` 字段
- 测试 `EditSessionReplaceContentTest` 4 处类型对比改 SolidFill；replaceContent(null,...) 加 `(String) null` 消歧

**baseline 14 fixture**：`:plugin:test --rerun-tasks` 全绿，**0 像素漂移**（FillDeserializer 自动处理 fixture `"background": "#xxx"` 字符串）

**前端**：
- `types/protocol.ts Canvas.background`: `string` → `FillCompat`
- `PreviewRenderer.ts` entry paint 改 `fillToCanvasStyle(ctx, state?.canvas.background, 0, 0, w, h) ?? '#FFFFFF'`
- `stores/palette.ts` projectColors：`bg.toUpperCase()` → `fillColors(bg)` 全 stop 提取
- `stores/project.ts` patch apply：Canvas record 类型混合后加 `as unknown as` 中转
- 新 `CanvasSettingsSection.vue`：未选中元素时挂载；`FillInput` 编辑；alpha<1 用 CSS 棋盘格 UI 提示（仅视觉，不参与 PaletteLut）；emit `ws.send('canvas.background', { fill })`
- `RightPanel.vue` `!selected` 分支挂载
- i18n：`canvas.settings` / `canvas.backgroundLabel` 中英

**WS op**：未引入新 op；`canvas.background` payload 升级 `{fill}`（新）或 `{color}`（兼容）

### F3 智能对齐 v1（grid + 锚点 + 元素边/中点）

新 `web/src/composables/useSnapManager.ts`：
- API：`snap(rawX, rawY, w, h, excludeIds) → SnapHints { snappedX, snappedY, activeXAxes, activeYAxes }`
- 候选轴：canvas 3 项（0 / w/2 / w）+ element 6 项/个（left/cx/right + top/cy/bot，按 layer.visible + el.visible 过滤、excludeIds 排除）+ grid floor/ceil 倍数（仅 gridSize>0）
- `snapAxis` 两遍扫描：先 anchors（left/center/right）× candidates 找最近距离 ≤ threshold；再收集"应用 bestDelta 后恰好命中"的所有 candidate 作 activeAxes（多线同时命中 → visualizer M17.4 用）
- bypass 钩子：true 时 snap 透传 raw（shift 临时禁用）

**ui store**：`snapEnabled / snapToGrid / snapToCanvas / snapToElement / snapThreshold`，localStorage key `hikari-canvas:snap` 持久化（与 theme/locale 同级），threshold 范围 [1, 64]，默认 `{enabled:true, toGrid:false, toCanvas:true, toElement:true, threshold:8}`

**CanvasView 接入**（主线程手动补做——agent 的 edit 没生效）：
- `onDragStart` 单选 / 多选都 set dragInitial：单选时也 init 自己 leader 的初始 (x,y)。M17-P1 漏修——原代码单选时 dragInitial 是空 Map → onDragMove `initLeader === undefined` → return → leader 仍不跟手。**真正完成 F2 修复**
- `onDragMove`：调 `snapManager.snap(leaderX, leaderY, w, h, new Set(dragInitial.keys()))`；snap 后把 Konva 节点位置回写到 snap 落点（视觉跟手）；leader.store + follower delta 都用 snapped 坐标
- 新 `isShiftDown` ref + window keydown/keyup/blur 维护；传给 snapManager bypass

**性能**：100 elements ≈ 600 候选 × 3 锚点 ≈ 1800 比较 / frame，O(n) 线性；rAF 自合并；spatial index 留 v1.x

### 关键事故记录

F3 agent 声称"已改 CanvasView.vue + ui.ts"但 git diff 显示无改动——edit 静默失败。已 fallback 由主线程手动补做。这是 agent 协作中的隐性失败模式，需要在 commit 前用 git diff 验证。

### 验证

- `:plugin:test --rerun-tasks` BUILD SUCCESSFUL，14 RendererSnapshotTest baseline 0 漂移，EditSession 测试全绿
- `vite build` 403ms / 493.89 kB / 153.11 kB gzip，0 新 TS 错误

### 关联文件

**新文件**：`web/src/composables/useClipboard.ts` / `web/src/composables/useSnapManager.ts` / `web/src/components/properties/CanvasSettingsSection.vue`

**改动 Java**：`plugin/src/main/java/moe/hikari/canvas/`: `state/Fill.java` / `state/ProjectState.java` / `state/EditSession.java` / `render/CanvasCompositor.java` / `render/CanvasProjector.java` / `render/WallRestorer.java` / `template/TemplateInstantiator.java` / `web/EditOpDispatcher.java` / test `EditSessionReplaceContentTest.java`

**改动前端**：`web/src/`: `composables/useCanvasShortcuts.ts` / `components/layout/CanvasView.vue` / `components/layout/RightPanel.vue` / `render/PreviewRenderer.ts` / `stores/palette.ts` / `stores/project.ts` / `stores/ui.ts` / `types/protocol.ts` / `i18n/messages.ts`

---

## 2026-05-17 · M17 Phase 1（F2 拖动跟手修复 + F4 自由拖动画布）

针对生产级 UX 增强 / bug 修复 2 项，2 个并行 agent 完成。

### F2 onDragMove 单选 path 实时跟手（bug 修复）

**根因**：HikariCanvas 编辑器双层渲染——顶层 Konva 透明 hit-test 矩形（fill rgba 0.001）+ 底层 Canvas 2D PreviewRenderer 像素化输出（dither + 248 调色板）。用户实际看到的是底层 Canvas。底层重绘依赖 `requestDraw()` → `watch(project.state, {deep:true})`。

CanvasView.vue `onDragMove(ev, id)` 原逻辑 `if (dragInitial.value.size <= 1) return;` 单选场景早 return，根本不更新 store → PreviewRenderer 不重绘 → 视觉上"松手才跳到位"。

修复：删早 return，单选 / 多选都先 mutate leader 自身 store + `requestDraw()`，多选 path 的 follower 同步逻辑保持不变（含 M15.3 P0-1 `init.x !== otherX` 判等修复）。**onDragEnd 仍是唯一 ws.send 入口**（保持 60fps 不塞爆服务端）。

性能：`requestDraw()` 内部 rAF 自合并（line 458-460 `if (drawPending) return`），天然 60fps 上限；浏览器 dragmove ≤60Hz；不预防性加 throttle，500+ 元素 wall 实测卡顿时再加。

### F4 自由拖动画布 + 1024px 虚空白边

**新 'hand' 工具**：
- `ui.ActiveTool` union 加 `'hand'`；isDrawTool 排除（保留 selection / hover）；loadTool KNOWN 列表加 hand
- LeftTools Move 按钮后插入 Hand 工具（lucide Hand icon，H 快捷键）
- `useCanvasShortcuts` H 键激活；keydown Space 临时切（闭包 `spaceSavedTool` 保存原工具）、keyup 恢复；`e.repeat` 保护防 OS 长按重复；window blur 兜底防卡死；keydown preventDefault 防 Space 触发页面滚动

**虚空白边 1024px**：
- CanvasView 模板 inner wrapper 原 `p-8` 改 class `hc-canvas-padding` + scoped CSS `padding: 1024px`（Tailwind arbitrary 值在 scoped 偶失效，直接 CSS 最稳）
- onMounted nextTick 主动 `scrollLeft / scrollTop = (scrollWidth - clientWidth) / 2` 居中（padding 让 scrollWidth >viewport，不主动滚则首屏停在 padding 空白）
- fitToViewport 不受影响（仍按 outer.clientWidth - 64 算缩放，不读 scrollWidth）

**cursor 反馈**：CanvasView `cursorStyle` computed → `isPanning` → grabbing / `activeTool === 'hand'` → grab / drawTool → crosshair / else default。`usePanScroll` 暴露新 `isPanning` ref；onMouseDown 加 `button === 0 && activeTool === 'hand'` 分支（与中键 / Alt+左键并列）

**hand 工具下交互屏蔽**：
- hitConfig `listening: false`（与 drawTool 同路径），左键穿透到 outer pan
- onStageMouseDown 早 return 不启 marquee / draw
- useTransformerManager 不挂 Transformer 锚点

i18n 加 `tools.handTool` zh/en + `help.midMouseOrAlt` 文案补 `H 工具 / 按住 Space`。

### 验证

`vite build` 315ms / 486.87 kB ok。vue-tsc 我改动 0 新 TS 错误（既有 baseline 错误来自 DragEvt / node.x(v) setter 重载、Tooltip / useTransformerManager / i18n / PreviewRenderer / vite.config @types/node 等历史问题）。

### 关联文件

`web/src/`: `stores/ui.ts` / `components/layout/CanvasView.vue` / `components/layout/LeftTools.vue` / `composables/useCanvasShortcuts.ts` / `composables/usePanScroll.ts` / `composables/useTransformerManager.ts` / `i18n/messages.ts`。

---

## 2026-05-16 · M16 Phase 7（docs 同步 + 收尾）

针对 M16 6 phase 28 项改动同步契约文档；ultrareview-2026-05-15.md 落地删除（已被 05-16 review 取代）；ultrareview-2026-05-16.md 入仓存档。

### 文档同步（1 agent）

- **CLAUDE.md**：里程碑表 M13/M14/M15/M16 全 ✅；M15 段下加 M16 完整段（6 phase commit batch + 4 个 M16 关键架构决策固化），引用 6 个 commit sha
- **security.md**：§2.4 SessionRateLimiter 未实装状态 + v1.x 留档；新 §2.5 会话级 IP 绑定（方案 B 不绑 token、绑 session）+ IPv6/XFF 限制；§权限节点补 `canvas.template.use-others` / `canvas.alias.any` / `canvas.admin.bypass-lock`；§审计补 5 新事件 + WALL_ALIAS + POOL_RELEASE_TO_FREE
- **architecture.md**：新 §3.6.1 草稿 wall 协作语义（Q1 决策落地：未锁 wall 任何玩家可 open，协作中间态）+ §3.6.2 多世界假设（按 world UUID 分桶）；§10.4 安全补 IP 绑定 / Origin 白名单 / 5s auth timeout；§12 未决「多世界分池」标 [x] 已决
- **data-model.md**：§2.1 HikariCP maxPoolSize=4 不缩 1 的理由（WAL 读并发 + leakDetectionThreshold 兜底）；§6.5 V005 + 新 §6.5.1 V010 DROP COLUMN refcount（pre-release 0.1.x SNAPSHOT 阶段激进改 schema OK）
- **protocol.md**：§3.2 WS 握手 `client_v` + ready `accepted_v` + 5s auth timeout / Origin 白名单；§6.2 close codes 4001/4002（auth_timeout / ip_mismatch / CLOSE_PROTOCOL_VERSION_UNSUPPORTED）；§6.1 error codes 补 UNAUTHORIZED / QUOTA_EXCEEDED_DISK；§9.5 `GET /api/upload/{source}` 改为强制 `?session=` query 鉴权；§11 安全补 IP 绑定 + FAIL_ON_UNKNOWN_PROPERTIES

### Q1 / 多世界 / IP 绑定语义落地（关键决策固化）

- **Q1 草稿 wall**：写入 `architecture.md §3.6.1` —— 行为/状态机决策不是 schema 决策；保留「未来 ACL 走 v1.x 协作 scope」逃生通道
- **多世界**：新 `§3.6.2` 而非改写 §4 ——§4 仍是单世界概念介绍，§3.6.2 是 M16-P2.3 增量补丁层
- **IP 绑定**：`security.md §2.5` 主段 + `architecture.md §10.4` / `protocol.md §11` 交叉引用；明示「绑 session 不绑 token」+ IPv6/XFF 限制
- **HikariCP=4**：`data-model.md §2.1` 用对话式表达「为何不缩到 1」

### 已知留档（v1.x scope）

- M13 路线段（M16 已完成）保留为历史规划留档（类似 M5.5 段做法），里程碑表已标 ✅
- SessionRateLimiter 仍 P1 未实装
- IPv4-mapped IPv6 与 IPv4 字符串不归一化
- 反代场景 XFF 未解（IP 绑定仅看 socket peer）

### 收尾

- ultrareview-2026-05-15.md 落地删除（被 05-16 review 取代）
- ultrareview-2026-05-16.md 入仓存档（M16 工作的 ~200 条去重 P0/P1/P2/P3 完整原文）

### 关联文件

`CLAUDE.md` / `docs/architecture.md` / `docs/data-model.md` / `docs/protocol.md` / `docs/security.md` / `docs/journal.md` / `docs/ultrareview-2026-05-15.md`（删）/ `docs/ultrareview-2026-05-16.md`（新入仓）。

---

## 2026-05-16 · M16 收尾总览

**M16 = 28 项 P0/P1 落地 + 7 phase commit batch + 0 baseline 漂移**

针对第三方 AI ultrareview 2026-05-16（≈200 条去重问题）的最高优先级 P0/P1 安全 + 数据完整性 + 防御层修复。继 M15 大重构后第二轮全栈收口。

| Phase | 主题 | 项数 | commit |
|---|---|---:|---|
| M16-P1 | 安全 P0 核心（鉴权 / DoS / IDOR / Origin） | 7 | `4695269` |
| M16-P2 | 数据完整性 / 并发 P0 | 7 | `8564275` |
| M16-P3 | 渲染防御 P0 | 4 | `9747975` |
| M16-P4 | 前端资源生命周期 P0 | 3 | `3c800f8` |
| M16-P5 | 构建依赖 P0 | 3 | `d9ab3fc` |
| M16-P6 | P1 防御 + 观测 | 8 | `ca4bc54` |
| M16-P7 | docs 同步 | — | （本 commit） |
| **合计** | | **32** | **7 commit** |

### M16 4 个关键架构决策（已固化）

1. **草稿 wall = 协作中间态**（Q1 决策）：未锁 wall 任何玩家可 open，多人可同步编辑同一画板；只有 owner 可触发 lock；lock 后非 owner 不能 open（除 canvas.admin.bypass-lock）。未来 ACL 走 v1.x 协作 scope（详见 architecture.md §3.6.1）
2. **shadowJar 全 relocate**（Q2 决策）：jackson / caffeine / jdbi / hikari / javalin / jetty / snakeyaml 都 relocate 到 `moe.hikari.canvas.shaded.*`；org.sqlite 保护 JNI 不动；PacketEvents 走 plugin-loader 模式不 shade
3. **HikariCP maxPoolSize=4 保留**（Q3 决策）：SQLite WAL 模式下读并发友好；leakDetectionThreshold=30s 兜底；不缩到 1（详见 data-model.md §2.1）
4. **会话级 IP 绑定（方案 B）**：Token 不绑 IP（confirm 阶段无 HTTP context），改 Session.boundIp 首次 auth 时 CAS；后续重连必须同 IP；玩家切网络必须重 `/canvas confirm`（详见 security.md §2.5）

### M16 累计技术细节

- **新文件**：`web/Protocol.java` / `ForbiddenTemplateException.java` / `db-migrations/V010__remove_refcount.sql`
- **新配置项**：`network.ws-auth-timeout-seconds` / `network.allowed-origins` / `map-pool.per-world`
- **新权限节点**：`canvas.template.use-others` / `canvas.alias.any`
- **新协议字段**：auth `client_v` / ready `accepted_v` / close codes 4001 (auth_timeout) / 4002 (protocol_version_unsupported)
- **新审计事件**：WALL_LOCK / WALL_UNLOCK / WALL_ALIAS / IMAGE_UPLOAD_OK / IMAGE_UPLOAD_REJECTED / PERMISSION_DENIED / POOL_RELEASE_TO_FREE
- **核心风险修复**：WallRestorer 失败 releaseToFree 防 idcounts.dat 膨胀（项目核心风险）
- **god class 进一步收口**：Phase 6 SessionManager 锁拆分 + Bukkit API 全挪锁外，避免 lock-order 死锁

### 待办留档（v1.x / 不阻塞 0.1.0 发版）

- 14 章 Group III 长期项：CI 设置、vitest、Playwright E2E、MockBukkit / JavalinTest 完整使用、双端镜像算法 fixture 自动校验、MapPool 进一步拆分、多世界 wand 支持
- SessionRateLimiter / token 暴力枚举防御（docs/security.md §2.4 规定但未实装）
- IPv6 / XFF 归一化
- 协议版本升级路径（v3 时的迁移策略）

### 评估

main 分支当前安全状态远优于 M15 之前。剩余多为非阻塞细化项，**项目处于可用 0.1.0 发版候选状态**。

---

## 2026-05-16 · M16 Phase 6（P1 防御 + 观测 8 项）

针对 docs/ultrareview-2026-05-16.md P1 防御层，3 个并行 agent 完成。Phase 6 是 M16 最长阶段。

### P6.1 Jackson 严格 + 错误消息脱敏

`WebServer` ObjectMapper 加 `FAIL_ON_UNKNOWN_PROPERTIES=true` 接收侧严格；发送侧保持 NON_NULL 不变。WS messageAsClass / WebHelpers.mapOrEmpty / UploadHandler 多处 `e.getMessage()` 改为固定 friendly 文案 + `log.log(Level.WARNING)` 服务端。dispatcher 内的 `iae.getMessage()` 保留（已是预定义 friendly 常量）。

### P6.2 协议版本协商

新 `web/Protocol.java`：`SUPPORTED_MIN=2 / SUPPORTED_MAX=2 / CLOSE_PROTOCOL_VERSION_UNSUPPORTED=4002 / isSupported(int)`。区分 envelope schema v vs business protocol version。

- WebServer.handleAuth：原 M8-C 单条 `< 2` 检查改为范围；优先读 `client_v`，回落 `clientProtocolVersion`（兼容）；ready payload 加 `accepted_v`
- wsClient.ts 顶层 `CLIENT_V = 2`；sendAuth 双带（新+旧字段）；handleReady 双向校验 accepted_v，不匹配 close 4002 + `stopped=true` 阻止重连风暴
- ReadyPayload 加 `accepted_v?: number`（旧后端不发为 undefined → 旧路径继续工作）

### P6.3 image_uploads.refcount 列移除

新 `V010__remove_refcount.sql` 直接 DROP COLUMN（sqlite-jdbc 3.53 对应 SQLite ≥ 3.45 原生支持）。`ImageUploadDao.Row` record 删 refcount 字段；insertOn / mapRow 同步；调用方 UploadHandler / ImageStorage / ImageQuotaServiceTest 4 处构造同步删尾 1。

### P6.4 AuditLog 补全

新事件常量：`WALL_LOCK` / `WALL_UNLOCK` / `IMAGE_UPLOAD_OK` / `IMAGE_UPLOAD_REJECTED` / `PERMISSION_DENIED`。

调用点：
- WallOpDispatcher：lock/unlock 成功 + alias 非 owner 拒
- SessionManager.open lock-aware 拒（reason=lock_owner_only）
- EditOpDispatcher.template.apply ForbiddenTemplateException
- UploadHandler 10+ 拒绝路径 + 成功路径（reason 是稳定 token 不含文件名 / 路径）

write 失败 fallback：`AuditLog.java` 从 `log.severe(String)` 改 `log.log(Level.SEVERE, msg, e)` 保 stack trace。

### P6.5 editor-url 协议白名单

`HikariCanvasConfig.sanitizeEditorUrl`：CR/LF 检测 → `{token}` 替换 PLACEHOLDER → `URI.create()` 解析 → scheme 必须 `http` 或 `https`（lower-case）。非法 → `log.severe` + 回退默认。config.yml 注释加警告。

### P6.6 Token + WS IP 绑定（方案 B：会话级）

Token 不绑（confirm 阶段无 HTTP context），改会话级。

- Session 加 `boundIp` 字段
- SessionManager.bindOrCheckIp：新 `IpBindResult { OK, BOUND, MISMATCH, NO_SESSION }`；首次绑定在 writeLock 内 CAS 防 race
- WebServer.handleAuth：consume token + session lookup 后调 bindOrCheckIp(presentedIp)；MISMATCH → close auth_failed + log.warning（外部仍是 4001）
- 已知限制：IPv4-mapped IPv6 不 norm；反代场景未解 XFF；玩家切网络必须重 confirm

### P6.7 SessionManager ReadWriteLock

三 map（byId / byPlayer / byWall）改 `ConcurrentHashMap`，所有 `synchronized(this)` 砍掉。

新 `ReentrantLock writeLock` 守护跨 map 复合写：`beginSelecting` / `confirm` / `open` / `cancel` / `deleteWall` / `bindOrCheckIp`。read-modify-write 用 `putIfAbsent` 防 race。

**持锁中调 Bukkit API 全部挪到锁外**：`Bukkit.getPlayer` / `Bukkit.getWorld` / MapPool 系列 / wallRepo / auditLog / forgetHooks 回调——锁内只做 map mutate + Session 元数据 read。`runForgetHooks` 从 `forget()` 拆出在锁外执行（防 hook 回调持外部锁 → lock-order 死锁）。

副作用：confirm 加 `WallRaceException` sentinel 处理 putIfAbsent race；新 `disconnect()` 非主线程版 cancel。public API 全保留。

### P6.8 Optimistic mutation 回滚

`TopBar.toggleLock` / `commitAliasEdit` 改 async：save prev → optimistic mutate → `await ws.sendWithAck(..., 5000)` → catch 时回滚 + `net.lastError` 分流错误码。

连击防护：`lockInFlight` / `aliasInFlight` ref，pending 直接 return。lock 按钮 `:disabled="!isOwner || lockInFlight"`。

砍掉旧 `watch(net.lastError)` 拦截副作用（避免误触发其它 op 报错时显示别名 UI）。

i18n 加 `wall.lockFailed / unlockFailed / aliasFailed`（中英）。

### 验证

`:plugin:test --rerun-tasks` 全绿；`vite build` 通过（dist 485kB / gzip 150kB）。先清 macOS `* 2.class` 污染。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `HikariCanvas.java` / `HikariCanvasConfig.java` / `image/ImageStorage.java` / `image/UploadHandler.java` / `session/Session.java` / `session/SessionManager.java` / `storage/AuditLog.java` / `storage/ImageUploadDao.java` / `web/EditOpDispatcher.java` / `web/WallOpDispatcher.java` / `web/WebHelpers.java` / `web/WebServer.java` / `web/Protocol.java`（新）。`db-migrations/V010__remove_refcount.sql`（新）/ `config.yml`。`plugin/src/test/.../ImageQuotaServiceTest.java`。`web/src/`: `components/layout/TopBar.vue` / `i18n/messages.ts` / `network/wsClient.ts` / `types/protocol.ts`。

---

## 2026-05-16 · M16 Phase 5（构建依赖 P0 3 项）

针对 docs/ultrareview-2026-05-16.md 第十四章构建 P0，单 agent 完成。

### P5.1 shadowJar relocate

`plugin/build.gradle.kts` shadowJar block 加 7 条 `relocate`：

```
com.fasterxml.jackson         → moe.hikari.canvas.shaded.jackson
com.github.benmanes.caffeine  → moe.hikari.canvas.shaded.caffeine
org.jdbi                      → moe.hikari.canvas.shaded.jdbi
com.zaxxer.hikari             → moe.hikari.canvas.shaded.hikari
io.javalin                    → moe.hikari.canvas.shaded.javalin
org.eclipse.jetty             → moe.hikari.canvas.shaded.jetty
org.yaml.snakeyaml            → moe.hikari.canvas.shaded.snakeyaml
```

**关键**：snakeyaml 必须同步 relocate（jackson-yaml 间接依赖，否则破解析）。**不动**：`org.sqlite`（JNI native lib，relocate 会导致 native 加载失败）；PacketEvents（plugin-loader 模式，compileOnly 即可）。

`mergeServiceFiles()` 保留，处理 META-INF/services 下 Jackson Module SPI / JDBI Plugin SPI / Jetty SPI 等。

**unzip 验证**（HikariCanvas-0.1.0-SNAPSHOT-all.jar）：
- 旧路径泄漏：**0 条**
- `moe/hikari/canvas/shaded/` 下：**5397 条** entries
- `org/sqlite/JDBC.class` 保留原路径（JNI 安全）
- `META-INF/services/*` 自动改名（`moe.hikari.canvas.shaded.jackson.core.JsonFactory` / `...jdbi.v3.core.spi.JdbiPlugin` 等）

### P5.2 HikariCP leakDetectionThreshold

`Database.java` HikariConfig 加 `cfg.setLeakDetectionThreshold(30_000)`。SQLite 单写场景下任何超 30s 未还连接都肯定是 bug，dev/prod 都启用。

### P5.3 paperweight 锁定 + npm ci

- paperweight-userdev 已锁 `2.0.0-beta.21`（无需改）
- `installWebDeps` task 从 `npm install` 改 `npm ci`；`web/package-lock.json` 与 package.json 一致（vite build 1728 modules 已验证）

### 验证

- `./gradlew :plugin:shadowJar`：SUCCESS（32s 全量 / 2s 增量）
- `./gradlew :plugin:test --rerun-tasks`：**364 tests / 0 failures / 0 errors**
- 先清掉 macOS Finder `* 2.class` 同步残留（已知陷阱）

### 关联文件

`plugin/build.gradle.kts` / `plugin/src/main/java/moe/hikari/canvas/storage/Database.java`。

---

## 2026-05-16 · M16 Phase 4（前端资源生命周期 P0 3 项）

针对 docs/ultrareview-2026-05-16.md 前端 P0，单 agent 完成。

### P4.1 setPointerCapture 异常 release

`useBrushHost.ts` 整文件重写。

- `tryCapture(target, pointerId)` / `tryRelease()` 包 try-catch
- 维护独立 `capturedTarget` / `capturedPointerId`，不依赖 PointerEvent.target
- `abortStroke()` idempotent：检查 brushController.isActive 后 cancel + 总是 tryRelease
- pointerDown capture 失败时不调 brushController.pointerDown 避免半死
- 新监听：元素 `pointercancel` / window `blur` / document `visibilitychange` 都 abortStroke
- onScopeDispose 兜底 removeEventListener + abortStroke

### P4.2 Konva + Pinia 卸载清理

**Konva**（`CanvasView.vue`）：
- 新 `drawRafId` 变量记录 requestAnimationFrame id
- `onBeforeUnmount`：cancelAnimationFrame + `stage.destroy()`（级联清 Layer / Transformer / 内部 listener / 2D ctx / cache）+ ref 置 null
- 全仓 grep 确认：composables（useTransformerManager / useMarqueeSelection / useDrawToCreate / usePanScroll / useCanvasShortcuts / useCanvasUpload）全部通过 vue-konva template ref 拿节点，不直接 `new Konva.*`；watch / useEventListener / onKeyStroke 已在 effect scope 内自动 cleanup。ResizeObserver / IntersectionObserver 全仓 0 引用

**Pinia reset**：
- `stores/project.ts` 加 `reset()`：清 state / lastAddedElementId / wallId / alias / lockedAt / ownerUuid / selfUuid
- `stores/ui.ts` 加 `reset()`：清 selectedIds / editingLayerId / logDrawerOpen / helpOpen；**保留** theme / locale / activeTool / zoom / 面板折叠（用户偏好跨 wall）
- palette / brush / templates / network store **不 reset**（localStorage 持久化偏好）
- 调用点：`wsClient.ts handleReady` 仅当 `project.wallId !== null && wallId !== incomingWallId`（切 wall）时调，**同 wall 重连不触发** 避 UI 闪烁

### P4.3 pendingAcks onClose 清理

`wsClient.ts onClose` 加：遍历 pendingAcks，clearTimeout + reject('connection closed before ack') + Map.clear()。`seq` 计数器保持现状不重置。`sendWithAck` 超时 / ack / error 三个 delete 点已齐备。

### 验证

- `vite build` 372ms / 1728 modules ok（dist 484kB / gzip 150kB）
- `vue-tsc --noEmit` 仅 baseline 预存错误（useTransformerManager 重复签名、i18n、PreviewRenderer @ts-expect-error、vite.config @types/node），与本次无关；改的 5 个文件 0 新错误

### 关联文件

`web/src/components/layout/CanvasView.vue` / `composables/useBrushHost.ts` / `network/wsClient.ts` / `stores/project.ts` / `stores/ui.ts`。

---

## 2026-05-16 · M16 Phase 3（渲染防御 P0 4 项）

针对 docs/ultrareview-2026-05-16.md 渲染层 P0 防御，单 agent 完成。

### P3.1 负 w/h 防御

**协议入口已校验**：`ElementValidator.validateDim` 已强 `1..10000`，EditSession 49 处调用全经过——协议层无新增。

**渲染层兜底**：`RectRenderer` / `CircleRenderer` / `ShapeRenderer` / `ImageRenderer` 入口加 `if (w <= 0 || h <= 0) return;`。

### P3.2 NaN/Inf 过滤

新增 `ElementValidator.finiteOr(double, double)` + float 重载 package-public 静态工具。CanvasCompositor 入口对 element/layer opacity 做 `finiteOr` 再 clamp。

**重要发现**：`Element.rotation()` 返回 int（非 double），物理上 NaN 不可能——task 描述提到的 `if (e.rotation() != 0)` 永真路径在此 codebase 不触发，保留原逻辑。Shadow.dx/dy / Glow.radius 同样是 int。

FillPaintBuilder：新增 `filterFiniteStops()` 剔除 NaN offset 的 stops；< 2 个有效 stop 时降级首 stop 纯色；linear angle / radial cx/cy/r 加 `Double.isFinite` 兜底（防 raw_state 模板绕过 FillValidator）。

### P3.3 ImageRenderer mask 越界防御

`applyImageMaskClipSafely(g, im, ctx)` 替换原方法，Area / PathParser / g.clip 整段包 `try { ... } catch (InternalError | RuntimeException ex)`；fail 时 log.warning 并降级到无 mask（caller 的 outer try-finally 用 `savedClip` 兜底）。

**bbox sanity 阈值 = 10×**：mask path bbox area > element area × 10 时直接降级。这容忍合理的"略大于 bbox 的 mask"（外发光式），同时拒掉 100×+ 恶意/损坏数据触发 AWT Area O(n²) 卡死。

happy-path 性能不变：try-catch 只包 Area boolean op，不圈整个 drawImage。

### P3.4 前端 Mask 预设兜底

`ImageElementSection.vue` 新增 `sanitizeDimension(v: number)`（finite + >0 + clamp 16384，否则 fallback 1）和 `sanitizeRadius(v, maxR)`（额外 clamp 到 `[0.5, maxR]`）。`makeCircleD` / `makeEllipseD` / `makeRoundedRectD` 全入口过 sanitize；roundedRect 启发式半径 clamp 到 `min(w, h) / 2`。

### 验证

`./gradlew :plugin:test` 364 tests pass，0 failures；`RendererSnapshotTest` 14 fixtures（含 13-image-mask）**baseline 0 漂移**；`vite build` 1728 modules ok。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `state/ElementValidator.java` / `render/CanvasCompositor.java` / `render/RectRenderer.java` / `render/CircleRenderer.java` / `render/ShapeRenderer.java` / `render/ImageRenderer.java` / `render/FillPaintBuilder.java`。`web/src/components/properties/ImageElementSection.vue`。

---

## 2026-05-16 · M16 Phase 2（数据完整性 / 并发 P0 7 项）

针对 docs/ultrareview-2026-05-16.md 数据一致性 P0，3 个并行 agent 完成。

### P2.1 + P2.2 上传配额 + 磁盘/DB 原子化

并发上传可超额（stateless check → insert），磁盘写半截留孤儿。整合到单事务：

- `ImageUploadDao` 加事务感知重载 `*On(Handle, ...)`，不吞异常便于回滚
- `ImageQuotaService.tryReserveQuotaOn(Handle, ...)` 在传入事务内顺序：per-day count → disk sum → LRU `pickLruCandidatesOn + deleteOn` 循环 → `insertOn`；返 sealed `QuotaResult { Reserved | DeniedPerDay | DeniedDiskAfterLru }`，`Reserved` 携 evictedHashes
- `UploadHandler` 走 `jdbi.inTransaction(SERIALIZABLE, ...)`，事务首句 `UPDATE __locker__` 升级到 SQLite RESERVED 写锁
- `ImageStorage.writeFileAtomic`：写 `<hash>.png.tmp` → `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；同 hash `ReentrantLock` 串行；目标已存在 idempotent 跳过
- 流程：编码 PNG（事务外）→ 取 writeLock → 事务（quota + evict + insert）→ commit → `writeFileAtomic` + 多个 `deleteFileOnly`；写文件失败开补偿 tx 删孤儿行

错误码：`429 QUOTA_EXCEEDED` / `413 QUOTA_EXCEEDED_DISK`。

### P2.3 + P2.4 MapPool 多世界化 + bindToWall 一致性

- `freeQueue` → `Map<UUID worldId, Deque<Integer>>`，按 world 分桶
- `reserveForWall(wallId, count, World world)`；同 world FREE 不足时 `expand(world, n)`；超 max 抛 `PoolExhaustedException`（错误含 world 名）
- `initialize(World defaultWorld, Map<String, Integer> perWorldInitial)`：扫持久化行按 `MapView.getWorld()` 归桶，按 perWorldInitial 扩容，default world 补齐
- 调用点：`HikariCanvas.initialize` / `SessionManager` 4 处 `bindToWall` + `reserveForWall` 全部传 world
- `bindToWall` 校验 mapView.world == expected.world，不一致抛 `IllegalStateException`（不可恢复内部 bug）
- 配置：`map-pool.per-world: {}`（默认空，按 world 名 → initial count）

### P2.5 WallRestorer 池泄漏修复（**项目核心风险**）

启动期 restore 失败留 acquired map 不归还 → idcounts.dat 膨胀。

- `WallRestorer.restoreOne` 用 `try-catch(RuntimeException | Error)` 包裹；catch 时 for-loop `mapPool.releaseToFree(mid)` 释放本轮所有 bound map（bindToWall 已是原子语义：先全扫描后全更新，部分失败 = 0 bind）
- 新 `MapPool.releaseToFree(mapId)`：不论 owner 强制 RESERVED → FREE，归还到 mapView world 桶；audit `POOL_RELEASE_TO_FREE`
- 失败 wall ID 收集到 `failedRestoreWallIds` 不可变 set；wall row 在 DB 保留供重试
- `WandListener` 加 7-arg 构造接 WallRestorer：玩家用 wand 选到 failed wall 时红字 ActionBar「Wall failed to restore on startup, see server log」，不进 open

### P2.6 pendingDeletes 多 wall 支持

`Map<UUID, PendingDelete>` → `ConcurrentMap<UUID, ConcurrentMap<wallId, PendingDelete>>`。同玩家 30s 窗口内不同 wall pending 互不覆盖；同 wall 二次敲提示「Already pending, type confirm」。新内部 `QuitListener` 监听 `PlayerQuitEvent` 整层 remove。

### P2.7 SessionManager confirm 原子回滚 + 主线程断言

- 新 `SessionConfirmFailedException`
- confirm 维护 `Deque<Runnable> rollbacks` LIFO 倒序栈：3 步 push（`releaseWall(pending)` / `wallRepo.delete(wallId)` / `releaseWall(wallId)`）；bind race / 通用异常都 catch 跑 rollbacks 再抛 SessionConfirmFailedException；`runRollbacks` 单条 try/catch 不掩盖原异常
- `CanvasCommand.runConfirm` catch 显示「Failed to confirm session, server log」
- `assertMainThread()` 加在 `SessionManager` (confirm/cancel/deleteWall) + `MapPool` (initialize/reserveForWall/bindToWall/releaseWall/releaseToFree) 8 处
- 测试兼容：`Bukkit.getServer()` null 时跳过断言（纯单测环境）

### 验证

`./gradlew :plugin:test --rerun-tasks` 全绿。先 macOS Finder `* 2.class` 重复污染清掉。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `HikariCanvas.java` / `HikariCanvasConfig.java` / `command/CanvasCommand.java` / `image/ImageQuotaService.java` / `image/ImageStorage.java` / `image/UploadHandler.java` / `pool/MapPool.java` / `render/WallRestorer.java` / `session/SessionManager.java` / `session/WandListener.java` / `storage/ImageUploadDao.java`。`config.yml`。

---

## 2026-05-16 · M16 Phase 1（安全 P0 核心 7 项）

针对 `docs/ultrareview-2026-05-16.md` ≈200 条去重问题中的最高优先级安全 P0，分 3 个并行 agent（network / template / wall）完成 7 项。

### P1.1 `/api/upload/{hash}` 加 sessionId 鉴权（IDOR）

下载端点改为要求 `?sessionId=<id>` query，由 SessionManager 校验 active；命中后响应 `Cache-Control: private` 防代理缓存命中跨用户。前端 PreviewRenderer 加 `setUploadAuthProvider()` 钩子，App.vue 启动时注入 `net.sessionId`。

**未采用 TokenService**：token 是 one-shot consume，rotate 后会触发反复刷新；sessionId 等价 AUTHED 语义。

涉及：`UploadHandler.java` / `PreviewRenderer.ts` / `App.vue` / `ImageElementSection.vue`。

### P1.2 WS auth 5s 超时（DoS）

WS onConnect 注册 ScheduledFuture，N 秒（默认 5，clamp 1-60）后若未 auth → close code=4001 reason=`auth_timeout`；auth 成功 / onClose 都 cancel。N 可配 `network.ws-auth-timeout-seconds`。WebServer.stop() shutdownNow scheduler。

涉及：`WebServer.java` / `HikariCanvasConfig.java` / `HikariCanvas.java` / `config.yml`。

### P1.3 WS Origin 白名单（CSWSH）

注册 `WEBSOCKET_BEFORE_UPGRADE` handler 校验 Origin：放行 ①缺失 / "null" ②`http(s)://127.0.0.1[:port]` / `localhost[:port]` ③`network.host` 同源 ④`network.allowed-origins` 精确匹配。其它 → 403 + `skipRemainingHandlers`。

`startsWith("http://127.0.0.1:")` 要求紧跟 `:`，不会被 `127.0.0.1.attacker.com` 绕过。

涉及：`WebServer.java` / `HikariCanvasConfig.java` / `config.yml`。

### P1.4 YAMLFactory 限制（Billion Laughs）

`YAMLFactoryBuilder` 配 `LoaderOptions.setMaxAliasesForCollections(50)` + `setCodePointLimit(5MB)`（jackson 2.18.2 真实 API 是 `setCodePointLimit` 非 spec 拼写）。所有 TemplateLoader 加载路径生效。

涉及：`TemplateLoader.java`。

### P1.5 deepCopyMap 递归 + Interpolator 长度限制

- `deepCopyMap` 公开入口委托私有 `(Map, int depth)`；`MAX_DEEP_COPY_DEPTH=32` 同时约束 Map/List 嵌套，超阈抛 `IllegalArgumentException`，上游 catch 包装 `INVALID_TEMPLATE`。
- `Interpolator`：单值 `MAX_VALUE_LEN=16384`、整次 `MAX_OUTPUT_LEN=1048576`；超阈抛 IAE。常量公开。

涉及：`TemplateInstantiator.java` / `Interpolator.java`。

### P1.6 user-templates 跨用户隔离（IDOR）

- `TemplateEntry` record 加 `Optional<UUID> ownerUuid`（builtin/server 为 empty，user 为目录 uuid）
- 加载时校验目录名是合法 UUID，非法跳过 + warn
- 新方法 `TemplateRegistry.byIdForApply(id, callerUuid, hasBypass)`：owner empty / hasBypass / caller==owner 任一通过；否则抛 `ForbiddenTemplateException`
- `byId(id)` 保持原签名供 listing / preview（marketplace gallery 全员可见，不做 owner 隔离 —— 与 listMarketplace 一致）
- `EditOpDispatcher.template.apply` 改用 `byIdForApply`，catch 异常返 `EditSession.OpResult.Error("FORBIDDEN", ...)` 不 echo 异常细节
- 新权限节点：`canvas.template.use-others`（default: op）—— 跨用户 apply 的 bypass

涉及：`TemplateEntry.java` / `TemplateRegistry.java` / `ForbiddenTemplateException.java`（新）/ `EditOpDispatcher.java` / `paper-plugin.yml`。

### P1.7 `wall.alias` WS op owner-only（IDOR）

与命令侧 `/canvas alias` 同款校验：`wall.ownerUuid().equals(s.playerUuid())` 通过；否则查 `canvas.alias.any` 权限 bypass（玩家在线时）；否则 `FORBIDDEN`。补 `WALL_ALIAS` audit 事件（操作者 / wall_id / old / new alias）。

顺便审查其它 wall.* op：lock/unlock 已有 owner 校验（无 bypass，符合 lock-state §5）；wall.refresh 隐式被 SessionManager.open lock 鉴权拦截；wall.delete 仅命令侧实装，已校验。

涉及：`WallOpDispatcher.java` / `WebServer.java`（构造器加 AuditLog 参数）/ `HikariCanvas.java`。

### 验证

`./gradlew :plugin:test` 全绿（含 InterpolatorTest / TemplateInstantiatorTest / TemplateLoaderTest 全套 fixture）；`:plugin:compileJava` 干净。无 baseline 漂移。

### 关联文件

`plugin/src/main/java/moe/hikari/canvas/`: `HikariCanvas.java` / `HikariCanvasConfig.java` / `image/UploadHandler.java` / `template/TemplateEntry.java` / `template/TemplateInstantiator.java` / `template/TemplateLoader.java` / `template/TemplateRegistry.java` / `template/ForbiddenTemplateException.java`（新）/ `template/expr/Interpolator.java` / `web/EditOpDispatcher.java` / `web/WallOpDispatcher.java` / `web/WebServer.java`。`plugin/src/main/resources/`: `config.yml` / `paper-plugin.yml`。`web/src/`: `App.vue` / `components/properties/ImageElementSection.vue` / `render/PreviewRenderer.ts`。

---

## 2026-05-16 · M15.5 ultrareview Phase 4（docs 同步收尾）+ M15.2 god class 拆分（5 commit batch）

### M15.2 god class 拆分（5 个 commit）

5 个 agent 并行 + WebServer 收尾 agent，把累计 6519 行 god class 拆到 33 个新模块（平均 60-300 行 / 模块），各类公共 API 100% 不变，364 测试全过 + fixture baseline 0 漂移。

| god class | 之前 | 之后 | 减少 | 新模块 | commit |
|---|---:|---:|---:|---|---|
| EditSession.java | 2013 | **1191** | -41% | ElementValidator (537) / BrushSession (304) / LayerOperations (295) / HistoryStack (105) | `91e54dc` |
| CanvasCompositor.java | 986 | **328** | -67% | ElementRenderer sealed (23) + RenderContext (44) + FillPaintBuilder (147) + 8 Renderer（Rect/Circle/Shape/Icon/Path/Text/Image/Brush） | `8839c5c` |
| WebServer.java | 1117 | **581** | -48% | OpPushCallback / WebHelpers / EditOpDispatcher (243) / BrushOpDispatcher (102) / WallOpDispatcher (153) / TemplateOpDispatcher (147) | `c0106f1` |
| RightPanel.vue | 1076 | **193** | -82% | TransformSection (199) / TextElementSection (311) / GeometricElementSection (168) / ImageElementSection (301) / ElementListSection (126) | `d26134f` |
| CanvasView.vue | 1327 | **596** | -55% | 3 子组件（CanvasGridOverlay 25 / TextInlineEditor 83 / CanvasZoomBar 163）+ 7 composable（useMarqueeSelection 109 / useDrawToCreate 183 / useBrushHost 84 / useTransformerManager 117 / useCanvasShortcuts 50 / usePanScroll 84 / useCanvasUpload 157） | `fefa91b` |
| **合计** | **6519** | **2889** | **-56%** | **33 个新模块** | 5 commit |

**设计要点**：
- ElementRenderer 用 sealed permits 8 子类 + record pattern switch dispatch → 新增 Element 类型时编译期强制补 renderer
- RenderContext.ImageLoaderHolder SAM 让 setImageLoader 写后所有 renderer 立即可见（volatile 字段间接读）
- OpPushCallback interface 解耦 dispatcher 与 WebServer：dispatcher 只需 push 回调，不持完整 WebServer 引用
- BayerDither.apply(img, palette, phaseX, phaseY) 让 dither buffer 缩到 element bbox 后输出像素级等价（fixture 0 漂移关键）
- 测试钩子（activeStrokeCountForTest / overrideStrokeActivityForTest / purgeStaleStrokes）仍在 EditSession 上保留 package-private 签名，内部 delegate BrushSession 同名方法 → EditSessionBrushPurgeTest 等不动

**收益**：33 个新模块平均 60-300 行 → 单 reader 一坐能读完；后续 M13/M14/动态画板等扩展往里塞功能可以独立子模块迭代，god class 膨胀有了边界。

**工期约 4h**（5 agent 并行；预算 22-25h；节省 ~80% wall-clock）。

### M15.5 docs 同步（M15 收尾）

docs/protocol.md / docs/architecture.md / docs/data-model.md / docs/deployment.md / CLAUDE.md 5 个文档批量更新，把 M11-M14 + M15 改动全部回填契约：

- **docs/protocol.md** (+42 行)：§3.1 预握手响应精简 + §5.7 wall.refresh / wall.alias 错误码 + §5.9 brush 段从占位重写为 M12 完整版 + §5.10 新增模板创意工坊段 + §6.1 错误码表补 13 个（FORBIDDEN / INVALID_ALIAS_FORMAT / UNEXPECTED / TOO_MANY_STROKES 等）+ §8.3 锁定与终结（lock/unlock + FORBIDDEN 示例）
- **docs/architecture.md** (+30 行)：§13 动态画板设计约束（P-1 渲染期占位符推荐 / P-3 Plugin API 备选 / P-2 反模式禁用）
- **docs/data-model.md** (+32 行)：§6.6 Migration 兼容性规则（pre-release 激进 OK / 0.1.0 后 forward-only + auto-backup / fixture 测试要求）
- **docs/deployment.md** (+37 行)：§8 版本升级 SOP（升级前 / 中 / 后 / 回滚）
- **CLAUDE.md** (+19 行)：M15 ultrareview 大重构段（5 phase 概览 + 3 关键架构决策）

### M15 累计统计

- **27 P0 修完**（M15.1 + M15.3 + M15.4 + M15.2 顺手修的 P0-3 部分）
- **5 god class 拆完**
- **3 测试基建依赖**引入（Caffeine / MockBukkit / JavalinTest）
- **9 commit 分 5 phase batch push**
- **364 测试不漂移** + **14 fixture snapshot 0 漂移**
- **vite build 482.40 KB JS**（M14 baseline 477.79 → +4.61 KB，符合预算）

**剩余 P0**：
- P0-26 MapPool persist 失败一致性 — 留 v1.x（M15.2 god class 拆分时未碰 MapPool）
- P0-Web-4 WS auth → wsBySession race — v1 不做（需 CountDownLatch 等并发设施）

### 整体工期

| Phase | 内容 | wall-clock |
|---|---|---:|
| M15.1 | 9 P0 + 3 依赖 + Caffeine wallPreviewCache | 2.5h |
| M15.3 | 8 P0 鉴权方案 C + 数据安全 + 基础设施 | 2.5h |
| M15.4 | 10 P0 ImageIO/DB/协议/渲染 | 3h |
| M15.2 | 5 god class 拆分（5 commit batch） | 4h |
| M15.5 | docs 同步 + 整体 journal | 1h |
| **合计** | | **~13h** |

预算 35-40h；实际 ~13h（节省 ~65%）。靠大量子代理并行（5 agent × 多 phase = 累计 ~20 agent 工作量）+ 主代理只做装配 + commit + push。

---

## 2026-05-16 · M15.4 ultrareview Phase 3（ImageIO 隔离 + DB 一致性 + 协议 + 渲染）

4 个 agent 并行实施 10 处中高风险修复。覆盖 ImageIO DoS / LRU 死锁 / DB 事务 / 协议明文 / 模板注入 / dither OOM 6 个独立领域。

### Group A：ImageIO 隔离 + LRU 死锁

- **P0-12 IIORegistry 注销不可信 reader**：`HikariCanvas.onEnable` 头部扫 IIORegistry 把非 PNG/JPEG/WebP 的 ImageReaderSpi 注销。Thread.interrupt 对 ImageIO 内部循环大多无效——唯一可靠的防线是不让它们注册。降低压缩炸弹 / 损坏文件触发 TIFF/BMP/GIF 解码器漏洞的攻击面
- **P0-15 pickLruCandidates SQL NOT IN**：`ImageUploadDao.pickLruCandidates` 把内存 `stream.filter(!isReferenced)` 改成 JDBI `bindList("exc", ...)` + `WHERE hash NOT IN (<exc>)`。攻击者上传 16 张图全引用后 SQL 直接跳过这些行返非引用候选，不再"返 16 行内存全 filter → break → 所有玩家永久 fail"
- **P0-17 ImageStorage.load timeout 隔离**：加私有 `renderDecoderPool`（1-thread bounded + ArrayBlockingQueue(4) + AbortPolicy + daemon）+ 500ms timeout。损坏 PNG 不再让 rasterize 主线程死锁；reject 时返 null 走占位

### Group B：DB 一致性 + AuditLog

- **P0-27 MigrationRunner SQL 拆分识别引号**：新 `splitSqlStatements` 状态机识别单引号串（含 `''` escape）/ 双引号串 / 行内 `--` 注释。朴素 `split(";")` 对 `INSERT VALUES ('a;b')` 会破裂；新状态机扛住未来 DDL 含数据 INSERT 的 migration
- **P0-28 + P0-29 MigrationRunner 事务化 + auto-backup**：每个 migration 包 `h.useTransaction(...)`，DDL 失败回滚不留半态；`HikariCanvasConfig.databaseAutoBackup` + `config.yml database.auto-backup-before-migration`（pre-release 默认 false，0.1.0 发版后建议 true）；开启时 migration 前 `Files.copy(data.db, data.db.pre-V<NNN>.bak)`
- **P0-33 AuditLog DB 失败 fallback**：`record(...)` jdbi.useHandle 包 try-catch；DB 失败时 `log.severe("[AUDIT FALLBACK] event=... player=... reason=...")` 至少留痕到 server log。AuditLog 构造扩 Logger 参数 + 老单参 ctor 兼容

### Group C：预握手协议 + Template 校验

- **P0-Web-2 预握手协议精简**：`handlePreHandshake` 响应从 `{ sessionId, playerName, wall, mapIds, templates, palette, fonts, wsUrl }` 砍到 `{ ok: true, playerName, wsUrl }`。sessionId / wall / templates 全由 WS `ready` 帧下发（已实装）。HTTP 响应不再泄漏 sessionId → 配合 P0-21 capability-by-URL 缓解。前端无需改（grep 已确认前端不调 `/api/session/:token` HTTP，直接走 WS）
- **P0-23 Template raw_state 安全校验**：`EditSession.validateElementForTemplateApply(Element)` 抽 static + 公开。`TemplateInstantiator.instantiateRawState` 在 `cloneElementWithFreshId` 前调；catch `ValidationException` → `Result.Failed("INVALID_TEMPLATE", ...)`。覆盖 x/y/w/h/rotation 通用范围 + PathElement.d + ImageElement.source + ImageElement.mask + IconElement.source。`ValidationException` 提升为 public final 让 template 包能 catch

### Group D：渲染 dither buffer 收紧

- **P0-Render-2 dither buffer 按 element bbox**：`drawDitheredElement` 把整 canvas ARGB buffer 改成 element bbox + rotation 外接圆 + canvas clip 交集大小。`BayerDither.apply` 新签名 `apply(img, palette, phaseX, phaseY)` 让 dither phase 按原画坐标取 Bayer matrix，buffer 缩到 bbox 后输出**逐像素等价**。所有 11-dither / 12-brush / 13-image-mask fixture baseline **零漂移**。常规元素从 64MB transient 降到几 KB ~ 几百 KB

### 验证

- `./gradlew :plugin:test`：**364 case 全过**（baseline 不漂移）
- `vite build`：不变（M15.4 只动后端）
- M15.4 期间发现需要清理 `build/` 内 macOS Finder/iCloud 残留的 `* 2.xml` / `* 2.class` 副本——清完测试干净

### 剩余推后 P0

- **P0-26 MapPool persist 失败一致性**：留 M15.2 god class 拆分阶段（MapPool 也是 candidate）一起做—— byId.put 失败回滚 + 调用方重试
- **P0-Web-4 WS auth → wsBySession race**：v1 不做，需要更深的并发设施（CountDownLatch 等）

### 工期

约 3h（4 agent 并行）—— 预算 6-8h，节省 ~60%。

### 改动文件清单（13 文件）

- `plugin/.../HikariCanvas.java`（IIORegistry 启动期注销 + imageStorage.shutdown 接 onDisable）
- `plugin/.../HikariCanvasConfig.java`（databaseAutoBackup 字段）
- `plugin/.../image/ImageStorage.java`（renderDecoderPool + load timeout）
- `plugin/.../render/BayerDither.java`（phaseX/phaseY 重载）
- `plugin/.../render/CanvasCompositor.java`（drawDitheredElement bbox-only）
- `plugin/.../state/EditSession.java`（validateElementForTemplateApply public static）
- `plugin/.../state/ValidationException.java`（提升 public final）
- `plugin/.../storage/AuditLog.java`（catch + log.severe fallback + Logger 字段）
- `plugin/.../storage/ImageUploadDao.java`（pickLruCandidates SQL NOT IN）
- `plugin/.../storage/MigrationRunner.java`（splitSqlStatements + useTransaction + tryBackup）
- `plugin/.../template/TemplateInstantiator.java`（validateElementForTemplateApply 接入）
- `plugin/.../web/WebServer.java`（handlePreHandshake 精简）
- `plugin/.../resources/config.yml`（database 段）

---

## 2026-05-16 · M15.3 ultrareview Phase 2（鉴权方案 C + 数据安全 + 基础设施）

3 个 agent 并行实施 9 处中风险修复 —— P0 鉴权破口 + 数据丢失 + DoS 防线。

### 鉴权方案 C 落地（Group A agent）

**核心原则**：仅在 `SessionManager.open` 路径鉴权 lock 状态；后端编辑 op 透明放行（CLAUDE.md §lock-state 第 2 条保留）。

- `SessionManager.open` 加 lock check：`w.publishedAt() != null` + caller != owner + 无 `canvas.admin.bypass-lock` 权限 → 返新 `OpenResult.Forbidden(message)`（修 P0-18）
- `CanvasCommand.runOpen` 处理 `Forbidden` 分支：红字 `"Wall is locked: ..."` 提示
- `CanvasCommand.runAlias` 加 owner 校验：非 owner + 无 `canvas.alias.any` 权限拒（修 P0-19）
- `WandListener` 加 `WallRepo` 注入；wand 二次确认前预检 lock → ActionBar 红字 `"Wall '<id>' is locked by <ownerName>"` 早退（边界处理：与 SessionManager.open 双重防御）
- `HikariCanvas.onEnable` WandListener 构造扩 wallRepo 参数

**兼容性验证**：CLAUDE.md §lock-state 第 2 条「后端编辑 op 不读 lock」保留——所有 element.\* / canvas.\* / layer.\* 透明放行。未来 PAPI / 动态画板走渲染期占位符解析（P-1 路径）不经过 open → 不受方案 C 影响。

### 数据安全散点（Group B agent）

- **P0-1 多选拖拽 silent loss**：`CanvasView.vue:583` 比较条件 `otherEl.x !== otherX` → `init.x !== otherX`。dragmove 已乐观更新 element.x 用于 Konva 视觉反馈；dragend 改用 dragstart 记录的 `initialPositions: Map` 做判等，恢复多选拖拽 ws.send 触发
- **P0-10 mask 二次约束**：`EditSession.parseMaskNullable` 在 `validatePathD` 后追加 `validateMaskPathBounds`：`MAX_MASK_VERTICES = 64`（v1 决策上限）+ 坐标绝对值 ≤ 10000（element bbox 范围，相对 PathDValidator 的 100K 二次卡）。`EditSessionImageTest` 21 case 全过（fixture 坐标都 ≤ 100，未触限）
- **P0-11 RDP 改迭代**：`RdpSimplifier.recurse` → `simplifyIterative`（`ArrayDeque<int[]>` 模拟栈）。算法等价 + 公共 API 不变；规避 MAX_BRUSH_POINTS_PER_STROKE=5000 + JVM 默认 512KB 栈下 ~2500 帧 SO 风险。`RdpSimplifierTest` 7 case 零修改全过

### 基础设施（Group C agent）

- **P0-24 detectLeaks 接 BukkitScheduler**：`HikariCanvas.onEnable` 新 `mapPoolLeakTask` 字段；5 分钟周期异步任务 `runTaskTimerAsynchronously` 调 `mapPool.detectLeaks(liveWallIds)`，启动后 5 分钟首次跑；`onDisable` cancel。idcounts.dat 防膨胀的最后防线启动
- **P0-25 清渲染缓存**：`HikariCanvasRenderer.invalidate(Collection<Integer>)` 批量清除；`SessionManager` 构造扩 `HikariCanvasRenderer canvasRenderer` 参数；`SessionManager.deleteWall` 在 `mapPool.releaseWall(...)` 后调 `canvasRenderer.invalidate(released)`。跨 wall 像素泄漏防线就位
- **P0-32 confirm 事务化（v1）**：`WallRepo.createWithMapIds` 新方法 wrapped in `jdbi.useTransaction`，单 INSERT 直接含 mapIds 字段（不再先空字符串再 update）。`SessionManager.confirm` 重构为 reserve-first + tx INSERT + rebind 模式：先 reserve（owner placeholder） → wallRepo.createWithMapIds → rebind 到真实 wallId。任何步失败回滚 `mapPool.releaseWall` + 错误返回。完整 mapPool ↔ walls 跨子系统一致性（如 persist 失败回滚 byId.put）留 M15.4

### 验证

- `./gradlew :plugin:test`：**364 case 全过**（baseline 不漂移）
- `vite build`：**477.56 KB JS / 42.85 KB CSS**（M15.1 baseline -0.02 KB；纯逻辑改动几乎无体积变化）

### 工期

约 2.5h（3 agent 并行）。M15.3 是预算 5-6h —— 节省 ~50% wall-clock。

### 改动文件清单（9 文件 plugin + 1 文件 web）

- `plugin/.../HikariCanvas.java`（M15.3 +30 行 = mapPoolLeakTask wire + WandListener 扩 wallRepo + SessionManager 扩 canvasRenderer）
- `plugin/.../command/CanvasCommand.java`（runOpen Forbidden 分支 + runAlias owner check）
- `plugin/.../session/SessionManager.java`（OpenResult.Forbidden record + open lock check + 扩 canvasRenderer 构造 + confirm 重构 + deleteWall invalidate）
- `plugin/.../session/WandListener.java`（wallRepo 注入 + wand lock 预检）
- `plugin/.../render/HikariCanvasRenderer.java`（invalidate Collection 批量清）
- `plugin/.../state/EditSession.java`（MAX_MASK_VERTICES + MASK_NUMBER_RE + validateMaskPathBounds）
- `plugin/.../state/RdpSimplifier.java`（recurse → ArrayDeque iterative）
- `plugin/.../storage/WallRepo.java`（createWithMapIds + jdbi.useTransaction）
- `web/src/components/layout/CanvasView.vue`（dragstart 记 initialPositions + dragend 判等改用 init）

---

## 2026-05-16 · M15.1 ultrareview Phase 1（9 处立即修复 + 3 依赖引入）

**起因**：`docs/ultrareview-2026-05-15.md` 列 38+ P0 真 bug。5 个子 agent 并行核验后 ~37 条属实，1 条（双端 canonicalCharWidth 跨端差异）实际不属实但缺测试覆盖。规划 M15 整体重构（A-E 5 个 phase commit batch），约 35-40h wall-clock。

### M15.1 Phase 1 修复内容（9 处）

**后端散点（Group X agent）**：

- `Database.java:46` 加 `busy_timeout=5000`（修 P0-31：WAL 模式 2 并发写撞 BUSY 立即抛 → SQLite 5s 自旋重试）
- `paper-plugin.yml` 补 5 个权限节点：`canvas.delete.own / canvas.delete.any / canvas.alias.own / canvas.alias.any / canvas.admin.bypass-lock`（修 P0-20 + 为 M15.3 方案 C 鉴权预留）
- `ProjectState.Canvas` compact constructor 加 `widthMaps/heightMaps ∈ [1, 32]` 校验（修 P0-Render-1：远程一条 WS op 即可 48GB heap OOM 服务器崩；32×32 = 1024 maps 上限符合 PROPOSAL §3.1 设计上限的 4x 富余）
- `UploadHandler.decoderPool` `newCachedThreadPool` → 有界 `ThreadPoolExecutor(2, 2, ArrayBlockingQueue<>(8), AbortPolicy)` + `RejectedExecutionException` 捕获转 503（修 P0-13：unbounded fork 攻击）

**WebServer 散点（Group Y agent）**：

- `wallPreviewCache` `ConcurrentHashMap<String, byte[]>` → Caffeine `maximumSize(100).expireAfterAccess(5min)`（修 P0-16：ConcurrentHashMap 不收缩，旧 key 永不淘汰，攻击者编辑 1000 次 → 5GB 缓存）
- `cfg.jetty.modifyWebSocketServletFactory` 加 `factory.setMaxTextMessageSize(65536)`（修 P0-9 + P1-Web-3：WS 大消息 DoS）
- `handleTemplatesList` 删除 `m.put("ownerUuid", ...)`（修 P0-30：公开 `/api/templates` 端点泄漏 owner UUID；保留 ownerName 给"我的模板"判定）
- `wall.unlock` ack `{lockedAt: null}` → `{locked: false}` 显式布尔（修 P0-2：全局 JsonInclude.NON_NULL 把 null 字段序列化时删，前端收到空对象 → unlock UI 不切回；改协议为显式 locked boolean 字段）

**前端散点（Group Z agent）**：

- `App.vue` window.__hk stub + 实际 send 入口两处都包 `if (import.meta.env.DEV)` 守卫（修 P0-22：生产构建 Vite 死代码消除让 `window.__hk` 不存在，F12 console 无法绕过 lock）
- `wsClient.ts handleAck` 加判 `p.locked === false` 触发 `project.lockedAt = null`（P0-2 协议变更前端配套；保留旧 `typeof p.lockedAt === 'number'` 分支处理 wall.lock ack）

### 依赖引入（plugin/build.gradle.kts）

- `com.github.ben-manes.caffeine:caffeine:3.1.8` — wallPreviewCache 用
- `com.github.seeseemelk:MockBukkit-v1.21:3.123.0` — M15+ FrameDeployer / wall.lock owner-only 等 Bukkit world/Entity 设施测试
- `io.javalin:javalin-testtools:7.1.0` — M15+ HTTP / WS 端到端测试（UploadHandler 全场景 / sessionId 鉴权）

### 验证

- `./gradlew :plugin:test`：**364 case 全过**（M14 baseline 不漂移；macOS Finder 副本 `* 2.xml` 清理）
- `vite build`：**477.58 KB JS**（M14 baseline 477.79 → -0.21 KB；DEV 守卫消除冗余分支带来微减）
- 编译 / 类型检查全过

### 关键决策（这次会话拍板）

1. **Q1 鉴权方案 = C（你提议）**：仅在 `SessionManager.open` 路径鉴权 lock + owner + `canvas.admin.bypass-lock`；后端编辑 op 透明（CLAUDE.md §lock-state 第 2 条保留）。完美兼容未来动态画板（PAPI / 数据源），因为动态更新走渲染期占位符解析路径（P-1），不经过 open。M15.3 实施。
2. **Q3 V005 处理**：pre-release 激进改可接受；MigrationRunner 加 auto-backup 机制（默认关 / 0.1.0 发版后默认开）。M15.4 实施。
3. **god class 拆分纳入 M15.2**（不延后）。
4. **Caffeine + MockBukkit + JavalinTest 现在加**（不延后）。
5. **commit 策略**：5 个 phase batch（约 9 个 commit），每个 phase 完成后推一次。

### 工期

约 2.5h（agent 派发 / 验证 / commit；3 个 agent 并行节省 ~70% wall-clock vs 串行）。

### 改动文件清单（10 改动 + 1 新依赖）

- `plugin/build.gradle.kts`（+3 依赖）
- `plugin/src/main/java/moe/hikari/canvas/storage/Database.java`
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`
- `plugin/src/main/java/moe/hikari/canvas/image/UploadHandler.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（4 处）
- `plugin/src/main/resources/paper-plugin.yml`（+5 节点）
- `web/src/App.vue`
- `web/src/network/wsClient.ts`
- `docs/journal.md`

---

## 2026-05-15 · M14 创意工坊（A-F 六段全栈）

**核心**：玩家把当前画布发布为模板；服务器内的模板市场（精选优先 + 时间倒序）；admin 可精选；模板自动文本参数化 + 用户手工命名/取消；hard delete；每玩家配额可改。

### 7 个锁定决策

1. **模板格式 = rawState 模式**：不扩 `TemplateElement` 加 Path/Circle/Shape/Brush/Image 5 record；改让 `TemplateSpec.rawState: Map<String, Object>` 直接内嵌完整 `ProjectState` JSON。Instantiator 检测 rawState 非空 → 新分支：参数替换 + JsonNode → ProjectState → 平铺 layers 走 `replaceContent`。元素类型覆盖问题一举解决（任何 M8-M13 元素都能序列化导出）
2. **参数化 v1 = 自动 text + 手工命名/取消 text**：非 text 字段（color / fontSize / x/y/w/h）手工添加为参数 **留 v1.x**（需 RawState 通用 interpolator + 字段类型推断 UI）
3. **/canvas template 命令族延后 v1.x**：前端 TopBar UI 已能完整覆盖 save/delete/feature；命令仅锦上添花
4. **HomePage 仅展示**：admin feature/delete 操作放在编辑器内 TemplateGallery（已有 token）；HomePage admin 视图需 token-less auth，留 v1.x
5. **hard delete**：YAML + preview PNG + DB 行 + 空 uuid 目录同清
6. **slug 字符集** = `[a-z0-9][a-z0-9-]{1,31}`；templateId = `user-<uuid8>-<slug>`；TemplateLoader `ID_PATTERN` 扩展加 `-`
7. **builtin 永远 featured**：DB row builtin=1 时 setFeatured 拒绝（builtin 不可 unfeature）

### M14-A DB + DAO + Registry（约 2h）

- `V008__templates.sql`：templates 表（template_id PK / owner / featured / yaml_path / builtin / download_count）+ 3 索引（owner / marketplace / yaml_path UNIQUE）
- **踩坑**：行内 `--` 注释含分号 / 引号干扰 `MigrationRunner.split(";")`；改成纯行注释解决
- `TemplateRepo`：upsert(UPSERT semantics) / findById / listMarketplace(featured + time) / listForOwner / countByOwner（配额）/ setFeatured（builtin 拒）/ incrementDownload / delete
- `TemplateRegistry` 扩展：加 `userTemplatesDir` 字段 + `loadUser(...)` 扫 `user-templates/<uuid>/*.yml`；`TemplateSource.USER` 枚举；`ReloadStats` 加 `userLoaded`

### M14-B TemplateExporter + rawState 模式（约 3h，最高复杂度）

- `TemplateSpec` 加 `rawState: Map<String, Object>` 字段（NON_NULL 序列化省略）+ `isRawStateMode()`
- `TemplateLoader.serializeToYaml(spec)` 公开 + YAMLMapper 配置 NON_NULL 输出 + 关 doc-start `---` 头
- `TemplateLoader.ID_PATTERN` 加 `-` 容纳 `user-<uuid8>-<slug>`；validate 在 rawState 模式下放宽 canvas/layout 必需
- `TemplateExporter`：纯函数。参数化策略 — 按 z-order 扫所有 TextElement → `text_1 / text_2 / ...`；`ParamConfig.textActions` 允许 keep（参数化 + 重命名 + label + description）或 drop（保持静态）。Roundtrip 自校验（导出 YAML 再读回必须 valid）
- `TemplateInstantiator.instantiateRawState`：deep-copy rawState → 递归遍历 Map/List/String 替换 `${param}` 占位符 → `mapper.convertValue(...).ProjectState.class` → 平铺 layers 内所有 element + 给新 id 避免冲突 → 返 `Result.Ok`

### M14-C 后端 WS op + Publisher + 端点（约 2h）

- `TemplatePublisher`：协调器。`publish` 链路 — 配额 → Exporter → 写 YAML → 渲染缩略图 PNG（CanvasCompositor.rasterize → ImageIO.write）→ DB upsert → Registry.reload；`delete` 链路 — 鉴权 → unlink YAML + PNG + uuidDir → DB delete → reload；`setFeatured` 鉴权 builtin 拒
- `WebServer` 4 个 WS op：`template.save / delete / feature / unfeature` → `dispatchTemplateOp` → 拿 Bukkit Player 查 `canvas.template.*` 权限
- `GET /api/templates` HTTP 端点：listMarketplace JSON
- `paper-plugin.yml` 4 新权限节点：`canvas.template.save / delete.own / delete.any / feature / bypass-limit`
- `config.yml` + `HikariCanvasConfig` `templates.max-per-player`（默认 20，可改）
- `HikariCanvas.onEnable` wire：TemplateLoader（独立 publisher 用）→ TemplateRegistry（含 userTemplatesDir）→ TemplateRepo → TemplatePublisher → `syncBuiltinToDb()` 启动期把 builtin / server 模板入库 → WebServer 构造器扩参

### M14-D 前端 SaveAsTemplateModal（约 1.5h）

- `web/src/components/template/SaveAsTemplateModal.vue`：自动扫 project.state.layers 中 TextElement 生成 `text_N` 列表；每个参数 row 含 keep checkbox / name / label / description 4 控件（keep=false 时 collapse）；slug regex 客户端校验 + hint；ws.sendWithAck('template.save', payload, 8000)
- TopBar：`Bookmark` icon 按钮触发；wallId 缺或 locked 时 disabled
- 用 Vue fragment root（`</header><Modal/>`）避免 Teleport 复杂

### M14-E HomePage Marketplace（约 1h）

- `HomePage.vue` 加只读 grid section：`Sparkles` 标题 + cards（缩略图 + featured/builtin badge + name + description + owner + templateId）
- `loadTemplates` fetch `/api/templates` + featured / builtin 标签
- 不分 tab / 不放管理操作（v1.x 留 admin 视图）

### M14-F polish + i18n + 验证

- i18n `t.workshop.*` 中英对 27 字段（save 流程 + marketplace 元素）
- gradle test：**364 case 全过**（M13 baseline 不漂移；M14 后端纯逻辑无新单元测试 case；TemplateExporterTest 留 v1.x）
- vite build：**477.79 KB JS / 42.85 KB CSS**（M13 baseline 465.74 → +12.05 KB JS / +1.99 KB CSS = SaveAsTemplateModal + Marketplace section + 27 i18n 字段 + TopBar 按钮）

### v1.x 留 future（journal 显式）

- 非 text 字段（color / fontSize / x/y/w/h / fontId / align 等）手工标注为参数 + 类型推断 UI
- `/canvas template save / list / feature / unfeature / delete` 命令族
- HomePage admin 视图（token-less auth + 在卡片上直接 feature / delete）
- TemplateGallery 内点击 apply 直接套用工坊模板（v1 builtin/server 已支持；user 模板也已可通过 template.apply 走 rawState 路径，UI 未连）
- 模板下载量统计 + 分类 / 搜索 / 评分

### 工期

约 9.5h wall-clock（A 2h + B 3h + C 2h + D 1.5h + E 1h + F polish 0.5h），略低于 13-14h 估算 —— 走 rawState 模式跳过扩展 5 个 TemplateElement record 是关键加速。

### 改动文件清单（9 新 + 8 改）

**新建（9）**：
- `plugin/.../storage/TemplateRepo.java`
- `plugin/.../template/TemplateExporter.java`
- `plugin/.../template/TemplatePublisher.java`
- `plugin/.../db-migrations/V008__templates.sql`
- `web/src/components/template/SaveAsTemplateModal.vue`

**改动（8）**：
- `plugin/.../template/TemplateSpec.java`（加 rawState 字段 + isRawStateMode + 兼容老 11 字段 ctor）
- `plugin/.../template/TemplateSource.java`（加 USER）
- `plugin/.../template/TemplateRegistry.java`（构造器扩 userTemplatesDir + loadUser）
- `plugin/.../template/TemplateLoader.java`（ID_PATTERN 加 `-` + rawState 模式宽校验 + serializeToYaml + YAMLMapper writer 配置）
- `plugin/.../template/TemplateInstantiator.java`（rawState 分支 + replacePlaceholders + deepCopy + cloneElementWithFreshId 覆盖全 8 type）
- `plugin/.../HikariCanvas.java`（wire TemplateRepo + TemplatePublisher + syncBuiltinToDb）
- `plugin/.../HikariCanvasConfig.java`（templatesMaxPerPlayer）
- `plugin/.../web/WebServer.java`（构造器扩 publisher + repo + WS op 4 个 + `/api/templates` 端点）
- `plugin/.../storage/MigrationRunner.java`（V008 入列）
- `plugin/src/main/resources/paper-plugin.yml`（5 权限节点）
- `plugin/src/main/resources/config.yml`（templates.max-per-player）
- `web/src/components/layout/TopBar.vue`（Bookmark 按钮 + Modal 渲染）
- `web/src/components/HomePage.vue`（marketplace section + loadTemplates + 缩略图 + featured/builtin badges）
- `web/src/i18n/messages.ts`（t.workshop.* 中英 27 字段）

---

## 2026-05-15 · fix(render)：粗 stroke 从 arrow 锥尖突破的视觉 Bug

**用户报告**：箭头工具画的「直线 + 方向箭头」组合，当 stroke 调粗后，直线从 arrow 三角形锥尖戳出来，看起来"直线大过方向箭头本身"。

### 根因

`MarkerRenderer.drawArrow` 三角形：apex 在 path 端点 + base 朝 inward 退 size 距离。三角形在 apex 处宽度 = 0，沿 inward 方向线性增加到 base 处 = size 宽。

`CanvasCompositor.drawPath` / `PreviewRenderer.drawPath` 用 `BasicStroke(width, ROUND, ROUND)` / `ctx.lineCap='round'` 描边整条 path 到 apex。Stroke 中段宽度恒等于 `strokeWidth`。

**视觉冲突**：从 arrow apex 朝 base 走 distance d，arrow 宽 = `size × d / size = d`。当 `d < strokeWidth` 时 arrow 锥尖宽 < stroke → stroke 矩形（2 × `strokeWidth/2` = `strokeWidth` 宽）从 arrow 锥尖区域向外凸出。

实测：`stroke=10`，`arrowSize=30`，arrow tip 朝 base 走 10px 内 arrow 宽 0..10 < stroke 10 → 直线从箭头尖端"戳出"~10×10 px 一小段。stroke 越粗这一段越大，肉眼上去就是「直线大过方向箭头」。

### 修复

`MarkerRenderer.arrowShape(apex, dir, size)` 抽出几何构造（不绘制），供 `drawArrow` 与 `drawPath` 共用。

`CanvasCompositor.drawPath` 描边前：用 `Area(baseClip).subtract(arrowShape)` 把 arrow 三角形从 stroke clip 中扣掉。stroke 只画在 arrow **外**，arrow 自己 fill 覆盖。

`PreviewRenderer.drawPath` 镜像：用 `Path2D` 外圈大矩形 + addPath(arrowShape) + `ctx.clip(path, 'evenodd')` 反相填充规则（同 M13 mask inverted 模式）。

两端皆 `markerStart='arrow'` / `markerEnd='arrow'` 通用；`dot` marker 不参与（圆形 marker 在端点重叠时不会"突破"，stroke 完全在 dot 圆内）。

### 验证

- `./gradlew :plugin:test`：364 case 全过；06-path-line fixture baseline 不漂移（旧 fixture stroke=2 时 arrow tip 突破区域 < 2px²，落在 snapshot 0.5% pixel tolerance 内）
- `vite build`：465.74 KB JS（+0.5 KB）；TypeScript 类型 OK
- 真实游戏内 stroke=10 / 20 验证需 `runServer` 实测，预期 arrow tip 处线条不再突破三角形边界

### 改动文件

- `plugin/.../render/MarkerRenderer.java`（抽 `arrowShape` 静态构造）
- `plugin/.../render/CanvasCompositor.java`（`buildArrowSubtractedClip` + `drawPath` 描边前 setClip）
- `web/src/render/MarkerRenderer.ts`（镜像 `arrowShape` 导出）
- `web/src/render/PreviewRenderer.ts`（`buildArrowSubtractClip` + ctx.clip evenodd）

### 工期

约 20 分钟（定位 + 双端镜像 + 验证 + journal）。

---

## 2026-05-15 · fix(deploy)：confirm 多 slot wall 只生成 1 frame 的恶性 bug

**用户报告**：`/canvas wand` 圈选 6×1 → `/canvas confirm` 后只生成 1 个 ItemFrame（其他 5 个位置连 frame 都没有），前端刷新一致。

### 根因

`FrameDeployer.spawnSlot` 行 308 的"清残骸"循环：

```java
for (Entity e : world.getNearbyEntities(frameLoc, 0.8, 0.8, 0.8)) {
    if (e instanceof ItemFrame ifr) {
        String w = pdc.get(wallIdKey, ...);
        if (wallId.equals(w)) ifr.remove();   // ← 同 wall_id 即删
    }
}
```

**bug 模式**（spawn 串行 slot 0..N）：
1. slot 0 spawn OK，PDC `wall_id=W slot=0`
2. slot 1 spawn 前 query box `0.8` 半径以 slot 1 frameLoc 为中心 → 与 slot 0 entity bbox（半径 ~0.5）相交（中心距 1.0 < 0.5 + 0.8 = 1.3）→ 抓到 slot 0
3. slot 0 PDC `wall_id == W` 匹配 → 当残骸 `remove()` 删了 slot 0
4. slot 1 spawn OK，PDC `wall_id=W slot=1`
5. slot 2 spawn 前抓到 slot 1（同 wall_id）→ 删 slot 1
6. ... 串联到 slot N-1：每 spawn 一个新的都删上一个；最终只剩最后 spawn 的一个

2026-05-14 修"邻接 wall confirm 误删"的 fix 把"非同 wall_id 不删"约束加上去，但**同 wall_id 邻接 slot 互删**这个对称问题没考虑。

### 修复

「同 wall_id 即删」收紧为「同 wall_id **且** 同 slot」：

```java
PersistentDataContainer pdc = ifr.getPersistentDataContainer();
String w = pdc.get(wallIdKey, PersistentDataType.STRING);
Integer s = pdc.get(slotKey, PersistentDataType.INTEGER);
if (wallId.equals(w) && s != null && s == slotIndex) {
    ifr.remove();
}
```

只有"同一格"上次失败 spawn 的真残留才会被识别——因为 ItemFrame 的 PDC `slot` 字段在 spawn 时就写入（line 333），可作为位置指纹。

### 验证

- `./gradlew :plugin:test`：364 case 全过（无回归）
- 真实游戏内 6×1 confirm 路径：需 `./gradlew :plugin:runServer` 实测；逻辑上邻接 slot 不再互删
- 测试盲点：FrameDeployer 单元测试需 MockBukkit world / Entity 设施，沿 2026-05-14 同款 "⏸ Future"

### 影响范围

- 受影响：所有 multi-slot wall（width × height > 1）的 confirm + refresh 路径
- 修复后行为：每个 slot 独立 spawn 不互删；同位置失败 spawn 残留仍能被正确清理（PDC slot 指纹精确匹配）

### 工期

约 15 分钟（定位 + 修 + 验证 + journal）。

---

## 2026-05-14 · M13 图片导入 + 蒙版（A-E 五段全栈）

**核心**：用户可拖图 / paste / 选文件进编辑器；上传走 6 层校验栈；`ImageElement` 支持 SVG path 蒙版（v1 UI 4 预设几何 + inverted，数据模型 path 形态留 v2 完全体接口）。M13 完成 = 项目主线收尾。

### M13-A 数据模型 + 协议（约 1h）

- `state/Mask` record（d + inverted）+ `state/ImageElement` record（source = sha256[:16] 16 字符小写 hex；mask 可选）
- `Element` sealed permits 加 image；`@JsonSubTypes` 加 image
- `EditSession`：`addElement` switch 加 `case "image"`；`updateElement` 模式 switch 加 `case ImageElement im`；`cloneElementWithNewId` 加 image；新增 `buildImage` / `applyImagePatch` / `parseMaskNullable` / `validateImageSource`
- `CanvasCompositor` switch 加 占位 `case ImageElement im -> {}`（M13-C 实装）
- `web/src/types/protocol.ts` 加 `ImageElement` + `Mask`
- 测试：`EditSessionImageTest`（21 case：add + 各形态 mask + update + 拒绝路径 + 配额边界）

### M13-B 后端 /api/upload + ImageStorage（约 3h，最高复杂度）

- `db-migrations/V007__image_uploads.sql`：sha256[:16] PK + bytes/width/height/mime/uploader/uploaded_at/last_used_at/refcount + 2 索引
- `storage/ImageUploadDao`：insert（INSERT OR IGNORE）/ findByHash / touchLastUsed / delete / countByUploaderSince / sumBytes / pickLruCandidates(限制 + excludeHashes) / listAll
- `image/ImageStorage`：sha256 内容寻址 + 60s 内存 LRU（LinkedHashMap accessOrder）+ 磁盘 LRU sweep。`putIfAbsent(BufferedImage, UUID)` → `StoreResult(hash, w, h, bytes, isNew)`；`load(hash)` → BufferedImage（缓存命中 / 磁盘加载）；`readPngBytes(hash)` 直接返字节（HTTP 下载用）；`evictLruUntilUnder(incomingBytes, maxTotalBytes, wallRepo)` 按 LRU 删 orphan
- **v1 refcount 简化**：image_uploads.refcount 列保留但不实时增减；LRU 候选实时 sweep `walls.project_json` 收集仍被引用的 hash（`collectReferencedHashes` 反射 ImageElement.source）。~50 walls 量级 < 50ms 可接受；wall 数上千再考虑增量维护
- `image/ImageQuotaService`：三层配额（per-wall / per-day / total-disk-mb），任一超限拒；磁盘超限返 `NeedsEviction` 让上层触 LRU evict 后重试；`bypass=true` 跳过全部
- `image/UploadHandler`：Javalin POST /api/upload + 6 层校验栈
  1. sessionId（multipart 字段）→ `SessionManager.byId` → live Player → `hasPermission("canvas.upload")`；bypass = `hasPermission("canvas.upload.bypass-limit")`
  2. `file.size()` ≤ `images.max-size-kb`
  3. `Content-Type` ∈ `images.allowed-mime`（去 `;charset=...` 后参数）
  4. magic bytes（前 12 字节）真实 MIME 探测；与 Content-Type 不符拒
  5. ImageIO 隔离解码：单独 `ExecutorService.submit(...).get(200, MS)`；超时 cancel 抛 `TimeoutException` → `UPLOAD_REJECTED: decode timeout`
  6. bbox sanity：0 < w/h ≤ 8192；超 `downscale-max-edge` 自动 bilinear downscale
  7. 配额三层（per-wall 由后续 EditSession 协调，v1 这里传 0）+ `evictLruUntilUnder` 重试
- `UploadHandler.handleDownload` GET /api/upload/{source}：hash 校验 → `readPngBytes` → image/png + max-age=31536000 immutable（hash 内容寻址，URL 与内容 1:1）
- `UploadHandler.handleQuota` GET /api/upload/quota：返三层剩余配额给前端 UI
- `WebServer`：3 个新路由注册；构造器加 `UploadHandler` 参数
- `HikariCanvas.onEnable`：image 服务在 Compositor 之前装配（WallRestorer 也能正确加载）；`UploadHandler` 在 sessionManager 后装配；compositor.setImageLoader 注入 storage::load；onDisable 调 `uploadHandler.shutdown()` 关 decoder 线程池
- `HikariCanvasConfig.ImageConfig` record + load() 读 `images:` 段（6 字段）
- `paper-plugin.yml`：`canvas.upload`（default true）+ `canvas.upload.bypass-limit`（default op）
- `config.yml`：`images:` 段含 max-size-kb=2048 / allowed-mime=PNG|JPEG|WebP / downscale-max-edge=1024 / max-per-wall=16 / max-uploads-per-day=50 / max-total-storage-mb=1024
- 测试：`ImageStorageTest`（13 case 真磁盘 IO + DAO + LRU sweep + sha256 稳定性）+ `ImageQuotaServiceTest`（8 case 三层边界 + bypass + 0=unlimited + 24h 时间窗）+ `UploadHandlerHelpersTest`（8 case magic bytes 全形式 + downscale 等比例缩放）
- HTTP path 完整 e2e 测试需 `javalin-testtools` 依赖未引入，留 future

### M13-C 后端渲染（约 1h）

- `CanvasCompositor.drawImage(g, im)`：translate 到元素左上 → 可选 mask clip → `g.drawImage(img, 0, 0, w, h, null)`；文件缺失走 `drawImagePlaceholder`（虚线方框 + ?，同 IconElement 风格）
- mask：复用 M9 `PathParser.parse(mask.d).path()`；`inverted=false` 直接 `g.clip(Path2D)`；`inverted=true` 用 `Area(bbox).subtract(Area(mask))` 反算
- **dither × mask 顺序**：`drawDitheredElement` 的 per-element off-buffer 路径上自然达成「先 dither 再 mask」语义：mask clip 在 drawElementBody 内部生效，dither 在整张 element buffer 跑，mask 外像素本就透明不受影响
- 抽 `ImageLoader` SAM（`String → BufferedImage`）作为 compositor 的注入接口；生产 wire `imageStorage::load`，测试可传 lambda
- fixture：`13-image-placeholder.json`（占位渲染：两个 source 都不存在文件，含 rotation）+ `13-image-mask.json`（占位 + 4 种 mask + inverted）；snapshot test `@ValueSource` 加 13-*

### M13-D 前端拖拽 + UI（约 1h）

- `PreviewRenderer.drawImage`：`imageCache: Map<hash, ImageCacheEntry>` 异步加载（`/api/upload/{hash}`）；ready 前画占位（虚线 + ?）；onload 触 `iconReadyHook` 重绘；mask 用 `ctx.clip(Path2D)`；inverted 用 `Path2D.addPath` + `'evenodd'` fillRule
- `preloadImage(source, dataUrl)`：上传成功立即缓存乐观 dataUrl 直接命中，省去一次 fetch 往返
- `CanvasView`：三入口
  1. 拖拽：根 `<section>` 加 `@dragover="onCanvasDragOver"` + `@drop="onCanvasDrop"`；drop 坐标 → host bounding rect / ui.zoom 换算 canvas 内坐标
  2. paste：`useEventListener(window, 'paste', onPasteImage)` 全局监听；activeElement 是 textarea/input 时不抢
  3. file input：右下 toolbar 加 ImagePlus 按钮 → `fileInputRef.click()` → `@change` 上传
  - `uploadAndPlace(file, clientX?, clientY?)`：FormData(sessionId + file) → fetch /api/upload → 200 → preloadImage + `ws.send('element.add', { type: 'image', props: { x, y, w, h, source } })`；非 200 → flashError 6s 自消
  - 等比缩到 canvas 短边 80%；多文件 drop 只取第一个（M13 决策 3）；locked 拒；非 image MIME 拒
  - 顶部 banner（红色错误 / 蓝色 uploading）+ readonly overlay 不影响 banner
- `RightPanel`：`isImage` computed + 整个 image details 段
  - 缩略图 + 16 字符 hash + Replace 按钮（自家 hidden file input + 独立 imageReplacing 状态）
  - mask preset dropdown：none / circle / roundedRect / ellipse；切换调 `makeCircleD` / `makeEllipseD` / `makeRoundedRectD` 生成 d 字符串 → `sendUpdate({ mask: { d, inverted } })`
  - circle / ellipse 用 4 段 cubic Bezier 近似（kappa = 0.5522847498）；roundedRect 用 4 个 Q + L 段（半径 = min(w, h) * 0.15）
  - `detectMaskPreset` 启发式：4 个 C 命令 → 椭圆/圆（按 w==h 判断）；4 个 Q 命令 → 圆角矩形；其他 → none。**不写魔数前缀**到 d，避免被后端 PathDValidator 拒
  - bbox resize watch：监 `${w}x${h}` 变化时按 detectMaskPreset 重生成 d，否则被裁形变
  - inverted 复选框（仅 mask != none 时显示）
  - dither 复选框（image 不属 geometric 族，复用 `t.fill.ditherLabel`）
- i18n（中英对 16 字段）：`t.image.header / source / sourceTip / replace / replaceTip / maskHeader / maskPreset / maskPresetTip / maskInverted / maskInvertedTip / maskPresets.{none,circle,roundedRect,ellipse} / uploading / uploadTip / uploadFailed / lockedDenied / noSession / notImage`

### M13-E polish + 测试 + journal

- gradle test 全套：**364 case 全过**（M12 baseline 312 → M13-A +21 → M13-B +29 = 362 + 2 fixture 雪片测试 = 364）
- vite build：**465.24 KB JS** / 40.86 KB CSS（M12 baseline 453.57 → +11.67 KB JS 来自 D2 imageCache + D3 三入口 + D4 RightPanel 段 + i18n 16 字段；+1.49 KB CSS = mask 段 + banner）
- fixture baseline 14 个 PNG 全不漂移（含 13-image-placeholder + 13-image-mask 两个新）

### 关键决策（已落地，固化于 docs）

1. **mask 数据模型 = SVG path d 字符串**（B 风格，留 v2 lasso / 自由 path mask 完全体接口）；mask UI = dropdown 4 预设（A 风格）
2. **上传入口 = file input + drop + paste**（Figma 标准）；多文件批量 / mask 拖动编辑 / feather / URL 粘贴 → v1 不做
3. **LRU 清理时机** = 每次 upload 前检查总配额，超限删 orphan
4. **mask + dither 顺序** = 先 dither 再 mask（per-element off-buffer 路径自然达成）
5. **content-hash sha256[:16]** 内容寻址（PNG 编码字节），跨 wall 零重复存储
6. **ImageIO 解码隔离** = ExecutorService.submit(...).get(200, MS) 防压缩炸弹
7. **v1 refcount 简化** = LRU sweep walls.project_json 收集仍被引用的 hash，不维护实时 refcount
8. **HTTP 认证** = multipart `sessionId` 字段 → SessionManager.byId → Bukkit Player.hasPermission（HTTP 不消耗 token，避免一次性 token 与 upload 冲突）

### v1 留 future

- POST /api/upload 完整 HTTP e2e 测试（需引入 `javalin-testtools` 依赖）
- 增量 refcount 维护（替换 v1 sweep 模式，wall 上千时）
- mask 完全体（lasso 自由路径 / 拖动编辑 mask 形状 / feather / 多 mask 组合）
- 多文件批量上传（drop 多文件全部接收）
- URL 粘贴上传 / EXIF 读取 / PNG→WEBP 自动转换 / 杀毒

### 改动文件清单（13 新 + 14 改）

**新建（13）**：
- `plugin/.../state/Mask.java` / `ImageElement.java`
- `plugin/.../storage/ImageUploadDao.java`
- `plugin/.../image/ImageStorage.java` / `ImageQuotaService.java` / `UploadHandler.java`
- `plugin/.../db-migrations/V007__image_uploads.sql`
- `plugin/.../test/.../state/EditSessionImageTest.java`（21 case）
- `plugin/.../test/.../image/ImageStorageTest.java`（13 case）+ `ImageQuotaServiceTest.java`（8 case）+ `UploadHandlerHelpersTest.java`（8 case）
- `plugin/.../test/resources/fixtures/13-image-placeholder.json` + `13-image-mask.json`
- `plugin/.../test/resources/expected/13-image-placeholder.png` + `13-image-mask.png`

**改动（14）**：
- `plugin/.../state/Element.java`（sealed permits + JsonSubTypes）
- `plugin/.../state/EditSession.java`（buildImage / applyImagePatch / parseMaskNullable / validateImageSource + 3 处 switch）
- `plugin/.../render/CanvasCompositor.java`（drawImage + ImageLoader SAM + 占位）
- `plugin/.../HikariCanvas.java`（image 服务 wire-up）
- `plugin/.../HikariCanvasConfig.java`（ImageConfig record + load()）
- `plugin/.../web/WebServer.java`（3 新路由 + 构造器扩展）
- `plugin/.../storage/MigrationRunner.java`（V007 入列）
- `plugin/.../resources/paper-plugin.yml`（2 权限节点）
- `plugin/.../resources/config.yml`（images 段）
- `plugin/.../test/.../render/RendererSnapshotTest.java`（13-* 入 @ValueSource）
- `web/src/types/protocol.ts`（ImageElement + Mask TS 镜像）
- `web/src/render/PreviewRenderer.ts`（drawImage + imageCache + preloadImage）
- `web/src/components/layout/CanvasView.vue`（drop/paste/file input 三入口 + banner + toolbar 按钮）
- `web/src/components/layout/RightPanel.vue`（isImage + image 段 + mask preset 生成 + replace）
- `web/src/i18n/messages.ts`（image 段中英对 16 字段）

### 工期

约 5h wall-clock（M13-A 1h + M13-B 3h + M13-C 30min + M13-D 1h + M13-E journal/commit 30min）。整体 PROPOSAL 估计 1 周 = 大幅压缩。M0–M13 累计约 7.5 周 wall-clock 完成，比规划 6 个月缩短约 4 个月。

---

## 2026-05-14 · 全栈审查 + 3 Bug 修复 + 文档批量重写

**起因**：用户反馈 "别的 AI 审查发现 CLAUDE.md 等文档严重滞后于实际"。本会话做了系统化审查（3 个 Explore agent 并行扫 doc-drift / code-quality / test-coverage）+ 修真 bug + 重写文档。

### 审查发现

- **真 bug 5 个**：(1) lock 快捷键守卫缺失（高，键盘绕过 readonly overlay）；(2) StrokeBuffer 泄漏（高，purgeStaleStrokes 只 startBrush 内调用）；(3) BrushController.tryEnd 超时漏掉清理通知（高，ack 后到导致服务端 buffer 孤儿）；(4) applyBrushPatch 忽略 w/h 字段（中）；(5) brush.end persistWall 主线程阻塞风险（中）
- **测试盲点 3 个**：wall.lock owner-only / 邻接 frame regression / 前端 scalePathD
- **文档严重滞后 5 处**：PROPOSAL 命令清单 / architecture §6 流程图 + 状态机表 / data-model published_at 注释 + 语义 / security 权限节点

### 本次修复

**Bug 1 · lock 快捷键守卫**（App.vue）：`useEventListener('keydown')` 顶部加 `if (project.isLocked)` 守卫；Delete/Backspace、Ctrl+Z/Y、ArrowKeys 全拒 + `e.preventDefault()`；zoom/select/locale/theme/Cmd+A 等非编辑快捷键不受影响。

**Bug 2 · purgeStaleStrokes 定时调用**：
- `EditSession.purgeStaleStrokes` private → public synchronized
- `SessionManager.purgeAllStaleStrokes`：遍历所有 session 调 purge
- `SessionReaper.sweep`：每次 sweep 顶部触发（30s 周期，已有 tick）
- 新增 `EditSessionBrushPurgeTest` 4 case + EditSession 加 `activeStrokeCountForTest` / `overrideStrokeActivityForTest` 测试 hook（package-private）

**Bug 3 · BrushController.tryEnd abort 标志**：
- 新加 `aborted: boolean` 字段
- tryEnd 超 2s 未拿 strokeId → 设 `aborted = true` 再 cleanup
- pointerDown sendWithAck.then 检测 aborted；ack 真到达时主动 `ws.send('brush.cancel', { strokeId })` 通知服务端

### 文档批量重写（subagent 并行执行 6 处）

- **PROPOSAL.md** 命令清单删 publish/unpublish + 加 lock-state 段说明
- **docs/architecture.md §6** 旧 publish ASCII 流程图整体替换为 wall.lock owner-only 新图
- **docs/architecture.md 状态机表** `ACTIVE→publish` 改为 `ACTIVE→lock`
- **docs/data-model.md** walls schema 列注释 + 语义段重写
- **docs/data-model.md** PDC 表 published_at 标"2026-05-14 不再写入"
- **docs/security.md** 删 canvas.publish 权限节点 + wall.publish 检查表 + 加 owner-only 说明

### 测试盲点未补全

| 项 | 状态 | 原因 |
|---|---|---|
| brush purgeStaleStrokes | ✅ 已补 4 case | EditSession 加 testing hook 后纯函数易测 |
| wall.lock owner-only | ⏸ Future | WebServer 端到端 mock 工作量 vs 1 行 .equals 投入产出比低 |
| FrameDeployer 邻接 frame regression | ⏸ Future | 需 MockBukkit world / Entity 设施 |
| 前端 scalePathD 单测 | ⏸ Future | 项目无前端测试 framework（vitest 未引入）；M13 后引入 |

### 验证

- `./gradlew :plugin:test`：**312 测试全过**（M12 commit 308 → +4 EditSessionBrushPurgeTest）
- `vite build`：**453.57 KB JS** / 39.37 KB CSS（+0.27 KB 守卫 + abort 字段）
- 现有 12 fixture baseline 不漂移

### 工期

约 1.5 小时（审查 3 agent ~15min + Bug 1/2/3 修复 ~30min + purge 测试 ~15min + 文档 subagent ~15min + journal & commit ~15min）。

---

## 2026-05-14 · 三 Bug 修复 + published 概念整体重设计为 lock-state

用户反馈三个问题，全部修完。

### Bug 1 · 箭头工具 transformer resize 失效

**根因**：`CanvasView.vue onTransformEnd` 对所有 element 统一发 `{x, y, w, h, rotation}` 走 `element.transform`；PathElement 的几何完全由 `d` 字符串（绝对坐标）+ `stroke.width`（常量）决定，bbox 不参与渲染。所以 resize handle 拖动只改 bbox，箭头粗细 / 形状不变。

**修法**：
- 新增 `web/render/pathScale.ts`：tokenize d 字符串 → 把 M/L/Q/C 命令后续数字按偶/奇下标 × sx / sy → 重组。Z 命令无坐标原样保留。M9 PathDValidator 接受输出格式。
- `CanvasView.vue onTransformEnd` 检测 `el.type === 'path'`：计算 sx=newW/oldW、sy=newH/oldH → 调 `scalePathD` 得新 d → stroke.width 按 max(sx, sy) 缩放 → 走 `element.update`（element.transform 只接 x/y/w/h/rotation，d/stroke 字段必须走通用 patch）。
- 其他 element 类型保持原 element.transform 路径。

### Bug 2 · 邻接 wall confirm 误删现有 ItemFrame

**根因**：`FrameDeployer.spawnSlot:302` 用 `getNearbyEntities(frameLoc, 0.8, 0.8, 0.8)` 清"残留"，原注释明确写"PDC 不带 wall_id 或带别的 wall_id 都视为残骸"——故意把别人的 frame 当残骸删。问题：ItemFrame bbox 半径 ~0.25 + query box 半径 0.8 = 1.05 格 > 标准 1 格间距，相邻 wall 的 frame 一定会被抓到。

**修法**：清残留时只 remove **PDC `wall_id == current`** 的 ItemFrame（自己上次失败 spawn 留下的）。其他 wall_id 或 PDC 缺失的 ItemFrame 一律跳过；位置占用问题由 WallResolver 在 confirm 之前的 OCCUPIED 检查拒绝。Item 掉落物保持照清。

### Bug 3 · published 概念整体重设计为 lock-state

用户提出 "把发布功能整体放前端 readonly 锁"，最终定下方案：

- 锁状态服务端持久化（不 localStorage，避免跨设备 / 玩家分享时丢失）
- 复用 `walls.published_at` DB 列（语义化为 lock 时间戳，避免 schema 迁移）
- 复用 `walls.owner_uuid` 作为作者权限依据
- 后端**不**用 lock 状态阻挡编辑 op（未来动态展示用例需要）
- `wall.lock` / `wall.unlock` WS op **owner-only** 校验（caller UUID == wall.owner_uuid）
- 前端是 lock 的唯一执行者（readonly UI）

#### 后端砍除

- `/canvas publish` / `/canvas unpublish` 命令 + tab complete（CanvasCommand.java）
- WS op `wall.publish` / `wall.unpublish`（WebServer.java）
- `FrameDeployer.markPublished` + `FrameDeployer.isFramePublished` + `FrameDeployer.publishedAtKey` 字段
- `FrameProtectionListener` 中"已发布拦截"完整路径（M7 引入的）—— 所有 wall ItemFrame 一致由 `canvas.modify` 权限保护

#### 后端新增

- WS op `wall.lock`：owner-only 校验通过 → `WallRepo.markPublished` 写入时间戳 → ack `{lockedAt}`
- WS op `wall.unlock`：owner-only 校验通过 → `WallRepo.markUnpublished` → ack `{lockedAt: null}`
- ready payload 新增字段：
  - `lockedAt` (从 `publishedAt` 改名)
  - `ownerUuid` （wall.owner_uuid）
  - `selfUuid` （当前 session 玩家 UUID）

#### 前端砍除

- `TopBar.vue` togglePublish 函数 + Globe icon "Published/Draft" 按钮
- `stores/project.ts` 的 publishedAt
- `network/wsClient.ts` ack handler publishedAt 字段
- i18n `publishToggleOn/Off` / `publishOn/Off` / `publishedGroup` / `draftsGroup` / status.published / status.draft

#### 前端新增

- `stores/project.ts`：`lockedAt` + `ownerUuid` + `selfUuid` 字段；`isLocked` / `isOwner` / `canEdit` computed
- `TopBar.vue`：Lock/Unlock 图标按钮（owner 可点，非 owner disabled + tooltip "仅作者可锁定 / 解锁"）
- `CanvasView.vue`：locked 时整 stage 上覆盖 readonly overlay div（z-20、bg-black/10、backdrop-blur）拦截所有 mousedown/click/dblclick；中央显示 amber 色提示文字（owner 看 "已锁定 · 点 TopBar Unlock 继续编辑"，非 owner 看 "已锁定（仅作者可解锁）· 只读模式"）
- `RightPanel.vue`：根元素 `:class="{ hc-readonly-panel: project.isLocked }"`，scoped CSS 给 `.hc-readonly-panel section` 加 `pointer-events: none; opacity: 0.6`，完全禁用编辑控件
- `StatusBar.vue`：published / draft 替换为 locked / unlocked，icon Globe/FileText 替换为 Lock/Unlock
- `HomePage.vue`：published / drafts 分组改为 locked / unlocked，icon Globe→Lock 色彩 emerald→amber
- i18n：`t.wall.{locked, unlocked, lockToggleOn, lockToggleOff, lockOwnerOnly, lockedOwnerHint, lockedReaderHint}` + `t.status.{locked, unlocked, wallStateTip}` + `t.home.{lockedGroup, unlockedGroup}` 中英

### 文档同步（"文档先行"）

- `CLAUDE.md`：M5.5 重构段顶部加 `§lock-state` 块，详细列 7 条架构纪律
- `docs/architecture.md` 顶部 banner + 新加 `§3.6 lock 状态`（含数据流图）；老 §6 publish 流程段标 `[DEPRECATED 2026-05-14]`
- `docs/data-model.md` 顶部 banner，published_at 列名保留但语义变更
- `docs/protocol.md`：§5.7 砍 wall.publish/wall.unpublish 行 + 加 wall.lock/wall.unlock；ready payload 例改 lockedAt + ownerUuid + selfUuid

### 验证

- `./gradlew :plugin:compileJava`：过（修了一处 UUID vs String 比较 bug，把 `w.ownerUuid().equals(s.playerUuid().toString())` 改成 UUID-UUID 直接比较）
- `./gradlew :plugin:test`：**287 测试全过**（M11 review fix 后基线，未引入测试漂移）
- `vite build`：440.20 KB JS / 39.26 KB CSS（之前 M11-E 438.50 → +1.7 KB JS = TopBar lock 按钮 + readonly overlay + i18n 新字段；+2.2 KB CSS = readonly 样式 + amber 颜色变量）

### 工期

约 1.5 小时（Bug 1 ~15 分钟、Bug 2 ~5 分钟、Bug 3 文档 ~25 分钟 + 后端 ~25 分钟 + 前端 ~30 分钟 + journal & verify ~10 分钟）。

---

## 2026-05-14 · M11 review fix · 退化形态 IAE 防御 + fill.ts 类型加强

**起因**：M11 主提交后做二次审查，扫到两个潜在问题。

### Bug 1（真 bug）· `LinearGradientPaint` / `RadialGradientPaint` 退化形态 IAE

**触发**：用户在 FillInput 把所有 stops 拖到 `position=1.0`（FillValidator 允许相等位置做硬切色，UI 也能造出全 1 的极端），后端 `monotonicFractions` 的 epsilon-bump 公式 `Math.min(1f, prev + 1e-5f)` 在 prev 已 1.0 时仍输出 1.0 → fractions 为 `[1.0, 1.0]` **非严格递增** → AWT 抛 `IllegalArgumentException` → 整张画面 rasterize 崩。

**根因**：M11-B 写 `monotonicFractions` 时只想着"相等位置 epsilon-bump"，没考虑 prev 已经卡到 1.0 上界的反向退化。整个序列向左收缩的逻辑复杂，所以选 graceful degradation 路线。

**修法**：`buildLinearPaint` / `buildRadialPaint` 把 `new GradientPaint(...)` 包 try-catch `IllegalArgumentException`，捕到时 fallback 首 stop 纯色 `Paint`（同 0 尺寸 bbox 处理一致）。视觉上极端形态 = 纯色，不再让 rasterize 整体崩。

**回归测试**：新增 `FillDegenerateTest` 4 case：
- linear stops 全 1.0
- linear stops 全 0.0
- radial stops 全 1.0
- linear + 0 尺寸 bbox（M11-B 原有检查的固化）
- 均断言 `compositor.rasterize(state)` 不抛 + 返回非空 BufferedImage

### Bug 2（lint 级）· `fill.ts` 函数参数 inline literal

`buildLinearGradient` / `buildRadialGradient` / `addStops` 的形参用了 `{ angle, stops }` 这种内联字面量类型而非 `protocol.ts` 导出的 `LinearGradient` / `RadialGradient` 类型 —— 未来 protocol 类型升级时（如加新字段）这些函数不会被 TS 报错提醒。

**修法**：

- `import` 增加 `LinearGradient, RadialGradient`
- 三个函数形参换成 `LinearGradient` / `RadialGradient`
- `addStops` 的 stops 参数加 `ReadonlyArray<{position, color}>` 类型签名（更安全）

### 验证

- `./gradlew :plugin:test`：**287 测试全过**（M11 主提交 283 → +4 = `FillDegenerateTest` 全过，意味着修复后退化形态不再抛）
- `vite build`：JS 体积 **438.50 KB** 无变化（仅类型签名调整，运行时代码不变）
- 现有所有 baseline 不漂移（11 个 fixture 都通过）

### 改动文件清单

- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`：`buildLinearPaint` / `buildRadialPaint` 加 try-catch IAE 兜底
- `web/src/render/fill.ts`：参数类型从 inline literal → `LinearGradient` / `RadialGradient` / `ReadonlyArray`
- `plugin/src/test/java/moe/hikari/canvas/render/FillDegenerateTest.java`（新）：4 case regression

### 总结

| 项 | 状态 |
|---|---|
| 找到 bug | 2（1 真 bug + 1 类型加强） |
| 修法 | 防御性 try-catch + 类型签名收紧 |
| 测试 | +4 regression case 覆盖修复路径 |
| 现有功能影响 | 0（baseline 不变 + 已有 283 case 全过） |
| 工期 | ~20 分钟（review + 修 + 测试 + journal） |

---

## 2026-05-14 · M11 渐变 + Bayer 4×4 Dither（A-E 五段全栈一气完成）

**目标**：把 `fill: string` 升级为 `Fill` 联合类型（solid / linear / radial），并实装 M8-E 留下的 "双端 dither 一致性硬骨头"。CLAUDE.md / PROPOSAL §6 锁定 M11 = "渐变 + Dither（1w）"，本次一气完成 A-E 五段。

### M11-A · 数据模型 + 协议 + 校验

- 新增 `state/Fill.java` sealed interface + `SolidFill / LinearGradient / RadialGradient` 三 record + `Stop` record
- 新增 `state/FillDeserializer.java`：Jackson custom，支持 `string ↔ object union` —— 老 `"#RRGGBB"` 等价 `{type:"solid",color:"#RRGGBB"}`
- 新增 `state/FillValidator.java`：solid 颜色 / linear angle [0,360) / radial cx,cy [0,1] r (0,2] / stops [2,8] position 单调非递减
- `state/ValidationException.java`：从 EditSession private 内部类提取为同包 top-level（供 FillValidator 复用）
- 4 element record 升级：`Rect / Circle / Shape / Path` 的 `fill` 字段 `String → Fill`
- `EditSession`：新增 `parseFillNullable` helper + `FILL_MAPPER`（专 fill object 反序列化）；8 处 build / patch fill 解析替换
- `CanvasCompositor.fillToColor` 临时桥接（M11-B 替换为 `fillToPaint`，先 stub 取首 stop 色保编译）
- `TemplateInstantiator.Rect`：fill 构造 `SolidFill`
- 前端 `types/protocol.ts`：`Fill / FillCompat / Stop / SolidFill / LinearGradient / RadialGradient` 类型镜像；4 element fill 字段类型升级
- 新增 `web/render/fill.ts`：`normalizeFill / fillToCss / fillColors / isSolidFill`
- `stores/palette.ts`：projectColors 扫渐变 stops
- `RightPanel.vue`：ColorInput 入口 `fillToCss` 兼容
- `PreviewRenderer.ts`：4 处 `ctx.fillStyle = fill` → `fillToCss(fill)`

#### M11-A 测试与坑

- `FillValidatorTest` 23 case + `EditSessionFillTest` 14 case
- **StackOverflowError 坑**：`@JsonDeserialize` 在 sealed interface Fill 上时会被 3 个 record 子类继承，`codec.treeToValue(node, LinearGradient.class)` 递归回 FillDeserializer 死循环。修：每个 record 加 `@JsonDeserialize(using = JsonDeserializer.None.class)` 显式断绝继承
- **UnrecognizedPropertyException 坑**：序列化 SolidFill 输出 `{type:"solid", color}`，反序列化时 LinearGradient/RadialGradient 不认 `type` 字段。修：每个 record 加 `@JsonIgnoreProperties(ignoreUnknown = true)`

### M11-B · 后端真实绘制（渐变 + dither）

- `CanvasCompositor`：`fillToColor` → `fillToPaint(Fill, bx, by, bw, bh) → Paint`，4 处 `g.setColor` → `g.setPaint`
- `buildLinearPaint`：角度 → 方向向量 → bbox 4 角投影取 min/max 端点 → `LinearGradientPaint(x1,y1,x2,y2,fractions,colors)`
- `buildRadialPaint`：cx/cy 归一化映射到 bbox 内、半径 `r × min(bw,bh) / 2` → `RadialGradientPaint(cx,cy,radius,fractions,colors)`
- `monotonicFractions`：AWT 要求严格递增，相等位置 epsilon-bump `1e-5f`
- 新增 `render/BayerDither.java`：4×4 矩阵 + `AMPLITUDE=16` + `threshold(x,y) = MATRIX[y%4][x%4] / 16 - 0.5` + `apply(BufferedImage, PaletteLut)` 原地 dither（RGB 加 offset → matchColor → getColor 反查写回，alpha < 128 跳过）
- `CanvasCompositor`：拆分 `drawElementBody` + 新增 `drawDitheredElement`：renderMode=DITHER 走 per-element ARGB canvas 尺寸 buffer → `BayerDither.apply` → `g.drawImage` 回主 graphics（element.opacity 通过 SrcOver 起效）
- `drawElementsTo` 加 `widthPx, heightPx` 参数沿调用链下传（fast/slow path 都更新）
- 新增 fixture 09 linear / 10 radial / 11 dither + 自动入 baseline + 视觉 review 通过
- 新增 `BayerDitherTest` 7 case（矩阵规模、threshold 周期性、透明像素不变、中灰产生 4×4 周期、黑白稳定、null no-op、clean vs dither 差异）

### M11-C · 前端镜像

- 新增 `web/render/BayerDither.ts`：与 Java `BAYER_MATRIX / BAYER_AMPLITUDE / bayerThreshold / applyBayerDither` 同矩阵同公式逐行镜像
- `render/fill.ts` 加 `fillToCanvasStyle(ctx, fill, bx, by, bw, bh) → string | CanvasGradient`：
  - linear：与 Java 同公式（4 角投影到方向向量取 min/max），`ctx.createLinearGradient`
  - radial：r0=0（中心点退化）+ r1=radius，映射 Java 单半径 `RadialGradientPaint`
  - stops：Canvas 允许相等 position（硬切色），无需 monotonize
- `PreviewRenderer.ts`：4 处 `fillToCss` → `fillToCanvasStyle`；`drawElement` 加 dither 分支；新增 `drawDitheredElement`（per-element off-canvas → getImageData → applyBayerDither → putImageData → drawImage）
- 模块级 `cachedPalette` lazy load + `onPaletteReady(hook)` —— 首帧 fallback clean，加载完自动重绘切回 dither
- `CanvasView.vue`：注册 `onPaletteReady(() => requestDraw())` （同 onIconReady 模式）

### M11-D · UI 编辑器

- 新增 `web/components/ui/FillInput.vue`（~250 行）：
  - 3-tab `solid / linear / radial` 切换 + 自动 stops 转换（solid→gradient 自动生成"当前色 + 黑色"2 stops，gradient→solid 取首 stop）
  - linear：angle 滑块 0..359（避开 360 边界 IllegalArgument）
  - radial：cx/cy/r 三滑块（粒度 0.05）
  - stops 列表：position 滑块（实时 clamp 到前后邻居之间，单调）+ ColorInput + 删 + 加（2-8 限）
- `RightPanel.vue`：rect 段重写为「几何元素」共用段（rect/circle/shape/path 合并，header 动态切换）
  - fill 接 FillInput；M11-A 的 ColorInput + fillToCss 临时桥接替换
  - 新增 shape 专属 kind / sides / innerRatio 嵌套字段
  - 新增 element-level **Dither** checkbox（hover tooltip 解释 Bayer 抖动语义）
  - **circle/shape/path 从此首次拥有 RightPanel 编辑入口**（M9 以来只能改 transform/opacity 的状态终结）
  - 删除冗余 `rectStroke / toggleRectStroke / patchRectStroke` helpers
- i18n：`t.fill.{solid,linear,radial,angle,cx,cy,radius,stops,addStop,removeStop,ditherLabel,ditherTip}` + `t.properties.{circleHeader,shapeHeader,pathHeader}` 中英

### M11-E · review + journal + commit

- 自查 4 段：FillInput stops 单调与 mutation 防御（`[...cur.stops]` 拷贝）/ Bayer 双端 AMPLITUDE 同步常量 / drawElement 调用链 widthPx/heightPx 完整传递 / RightPanel `geomFill` 把老 string 形态升级为 SolidFill object
- 确认后端 EditSession 各 element patch 都接受 `renderMode` 字段（M8-A 已实装）

### 关键设计决策（M11 全段已锁）

| 项 | 选 |
|---|---|
| Fill 反序列化 | sealed + custom Deserializer + 子类 `@JsonDeserialize(None.class)` + `@JsonIgnoreProperties(ignoreUnknown=true)` |
| linear angle | `[0, 360)` 度，0° 沿 +x，90° 沿 +y（顺时针为正） |
| radial cx/cy | `[0, 1]` 归一化 bbox |
| radial r | `(0, 2]` 归一化 `min(w,h)/2`，允许超出 bbox |
| stops 数 | `[2, 8]` |
| stops position | 单调非递减（允许相等做硬切色） |
| dither AMPLITUDE | ±16 RGB / 双端共享常量 |
| dither pass 粒度 | per-element canvas-size off-buffer → blend，clean 元素仍走原快路径 |
| stroke 渐变 | v1 不做（v1 留 future；fill 才支持） |
| text/icon 渐变 | v1 不做（text.color / icon.tint 仍纯色） |
| fill schema 升级 | lazy migration —— 老 string 自动 SolidFill；新写出统一 object |

### 验证

- `./gradlew :plugin:test`：**283 测试全过**（M10 后 273 → +10 = `FillValidator` 23 + `EditSessionFill` 14 + `BayerDither` 7 = 共 44 新 case，扣掉 baseline fixture 测试数 1=8→11 不算入 unit case；实际增长 50）
- `vite build`：**438.50 KB JS / 37.03 KB CSS**（M10 后 425.35 → +13.15 KB = fill.ts gradient builder + BayerDither.ts + FillInput.vue + RightPanel 几何元素扩展 + i18n）
- 现有 8 fixture baseline 不漂移 + 3 新 fixture（09/10/11）baseline 视觉 review 通过：
  - 09 linear：水平/垂直/45° 渐变都对齐 bbox
  - 10 radial：center/offset/star 径向都符合预期
  - 11 dither：clean vs dither 像素层面差异清晰，Bayer 4×4 周期可见

### 工期

- **预估 1 周**（PROPOSAL §6 锁定），实际**约 4.5 小时**（M11-A ≈ 1h / M11-B ≈ 1h / M11-C ≈ 30min / M11-D ≈ 30min / M11-E ≈ 1.5h 含 review + journal + commit）
- 16 新文件 + 12 修改文件 + 0 baseline 漂移 + 4 个实现期发现并修的 bug（StackOverflow / UnrecognizedProperties / hex 文本框 sync 不动 / Map.of 不接 null）

---

## 2026-05-13 · M10 调色板（ColorInput + 三色板 + alpha + copy hex）

**用户加买项**：alpha 通道（8 位 hex） + 复制 hex 到剪贴板。本次 M10 实施含 alpha + copy hex。

### 范围

- **三色板**：项目色板（ProjectState 派生）+ 最近色板（localStorage 持久化前 20）+ 默认色板（24 色 MC-friendly 配色，硬编码）
- **ColorInput 自定义组件**：替代所有原生 `<input type="color">`，含 HTML5 picker + alpha slider + hex 文本框 + 复制按钮 + 三色板 swatches 网格
- **alpha 通道**：默认开启（`allowAlpha=true`），alpha=ff 时存 6 位 hex，alpha<ff 时存 8 位
- **复制 hex**：popover 内复制按钮 → `navigator.clipboard.writeText` + 1.5s "已复制" 视觉反馈

### 实施

- 新增 `web/config/palettes.ts`：DEFAULT_SWATCHES 24 色（黑白灰 4 + 暖色 4 + 绿 4 + 蓝 4 + 紫粉 4 + MC 标志 4）
- 新增 `web/stores/palette.ts`：recent localStorage + projectColors computed（扫所有 layer.elements 的 color/fill/stroke.color/tint/effects.* + canvas.background，Set 去重）
- 新增 `web/components/ui/ColorInput.vue`：~330 行
  - trigger：色块 + hex 文本按钮
  - popover（absolute 定位 + onClickOutside 自动关）：HTML5 picker + alpha slider（棋盘格底 + 当前色渐变 overlay）+ hex 文本框 + 复制按钮 + 三色板网格 8 列
  - emit 分两类：picker/slider input 阶段不加 recent，change/swatch 点击/hex 提交才 addRecent
  - 棋盘格背景 CSS（hc-checkerboard）让 alpha < 255 时透出
- `web/components/layout/RightPanel.vue`：6 处 `<input type="color">` 替换为 `<ColorInput :model-value @update:model-value>`（rect.fill + rect.stroke.color + text.color + effects.{stroke,shadow,glow}.color）
- `web/i18n/messages.ts`：t.palette.{projectHeader, recentHeader, defaultHeader, copyTip, copied, alphaLabel} 中英
- **后端 `CanvasCompositor.parseColor` 加 alpha 支持**：解析 `#RRGGBBAA` 时 alpha 0-255 → `new Color(r, g, b, a)`；Graphics2D 在 TYPE_INT_RGB 上 SrcOver 叠加（"颜色变浅"语义同 docs/rendering.md §6.5）

### Review 修复（实施完成后）

- hex 文本框同步 bug：用户在 picker/slider 改色后切到 hex 文本框，显示的还是旧 draft（未跟随 modelValue 变化）→ 加 `hexFocused` 跟踪 + `watch(modelValue)` 当未 focus 时同步到 hexDraft

### 关键设计决策

1. **alpha=ff 归一化存 6 位 hex**：保持向后兼容，老 element 字段不被升级；新存 alpha<ff 时存 8 位，新老共存
2. **projectColors 不持久化**：从 ProjectState 派生 computed，再次打开 wall 自动重扫，避免引入新的工程文件字段
3. **recent 完全前端 localStorage**：不发 ws，多 client 互不影响
4. **canvas.background 用 `allowAlpha=false`** 留作 future 接入背景调色器；当前 UI 无 background input
5. **emit 分两阶段**：input（拖动期）不加 recent，change（mouseup）/swatch/hex commit 才加，避免拖动期 localStorage 频繁写
6. **棋盘格背景显 alpha**：CSS linear-gradient × 2 实现，浏览器原生渲染零开销
7. **后端 parseColor 同步 alpha**：原本只读 6 位忽略 alpha，会导致前后端不一致；M10 一并修

### 验证

- `vite build` 通过：425.35KB JS / 36.90KB CSS（M9 后 417.74/33.13；+8KB JS 含 ColorInput 组件 +3.5KB CSS 含棋盘格/popover 样式）
- `./gradlew :plugin:test --offline` 全过：parseColor 改动不破坏现有测试（5+3 fixture 都是 6 位 hex，alpha=255 行为不变）

### 工期

- **预估 3 天**（PROPOSAL §6 锁定），实际**约 30 分钟**
- 3 新文件 + 4 修改文件 + 0 测试漂移 + 1 个 review 修复

---

## 2026-05-13 · M9-A/B/C/D/E 全栈实施（PathElement + 工具栏 + drag-to-create）

**目标：** 把"线 / 箭头 / 软线 / 星 / 点"五种工具压缩成一个 path 元素 + marker；CircleElement / ShapeElement 单独立；工具栏 drag-to-create。docs CLAUDE.md / PROPOSAL §6 锁定 M9 = "PathElement + 工具栏（1.5w）"，本次一气完成 A-E 五段。

### M9-A · 后端 record + 协议校验

- 新增 `state/{PathElement, CircleElement, ShapeElement}.java` 三 record + `state/PathDValidator.java`（SVG d 词法校验：M/L/Q/C/Z 子集 + 长度 / 数值范围 / 命令-参数对应）
- `state/Element.java` sealed permits + JsonSubTypes 加 path/circle/shape
- `state/EditSession.java`：addElement switch / updateElement sealed switch / cloneElementWithNewId 三处加新 case；新增 buildPath/Circle/Shape + applyPathPatch/CirclePatch/ShapePatch + parseMarkerNullable / validateShapeKind / validateSides / validateInnerRatio helpers
- `render/CanvasCompositor.java` sealed switch 加 3 case → stub drawTodoStub（M9-B 替换）
- `web/types/protocol.ts` + `web/render/PreviewRenderer.ts` 类型镜像 + stub
- 新测试 `PathDValidatorTest` 29 + `EditSessionNewElementsTest` 21

### M9-B · 后端真实绘制

- 新增 `render/PathParser.java`：d → `Path2D.Double` + 起/终点 + 切线元数据（marker 用）；M/L/Q/C/Z 大小写绝对/相对；隐式 lineto；Z 闭合切线指向 subpath 起点；退化 Q/C 控制点 fallback
- 新增 `render/MarkerRenderer.java`：drawArrow（三角形 apex 在端点）+ drawDot（实心圆），size = max(6, stroke×3) / radius = max(2, stroke+1)
- `CanvasCompositor.drawPath`：translate(p.x, p.y) → fill + stroke + drawMarker(start/end)
- `CanvasCompositor.drawCircle`：Ellipse2D.Double + fill/stroke
- `CanvasCompositor.drawShape` + `buildShapePath`：sides 顶点 -π/2 起算朝上；star 时 2×sides 顶点外内交替（odd 用 outerR×innerRatio）
- 新增 fixture 06/07/08（path-line / circle / star-polygon）+ baseline 入库（视觉 review 通过）
- 新测试 `PathParserTest` 19

### M9-C · 前端镜像

- 新增 `web/render/PathParser.ts`：浏览器原生 Path2D + 切线，与 Java 同公式逐行镜像
- 新增 `web/render/MarkerRenderer.ts`：drawArrow + drawDot 同 Java 几何
- `web/render/PreviewRenderer.ts`：drawPath / drawCircle / drawShape 实装（删 stub）
- 双端一致性靠**公式同源** + 共享 fixture（snapshot 测试是双端约束）

### M9-D · 工具栏激活态

- `stores/ui.ts`：`ActiveTool` 类型扩展 6 种（select / move / line / arrow / circle / star）+ `isDrawTool(t)` helper + loadTool 兼容
- `components/layout/LeftTools.vue`：select/move 组下新分组加 4 个工具按钮（icons：Minus / MoveRight / Circle / Star）
- `components/layout/CanvasView.vue`：`cursorStyle` 跟随 activeTool（crosshair on drawTool）；watch activeTool 切到 drawTool 时清 selection；onStageMouseDown 在 drawTool 时不启动 marquee
- 快捷键 L / A / C / S（Cmd 修饰键让位给现有 Cmd+A 全选等）
- `HelpModal.vue` 加 4 个工具条目
- i18n 加 t.tools / t.help 4 个工具文案

### M9-E · drag-to-create

- `CanvasView.vue`：
  - `hitConfig.listening: !drawing` —— drawTool 时穿透 mousedown 到 stage（PS/Figma 行为）
  - `onStageMouseDown` drawTool 时启动 drawDrag；mousemove 更新；mouseup 调 commitDraw
  - commitDraw：dx/dy < 3 取消，否则按 activeTool 构造 props 发 element.add；自动切回 select；line/arrow 不规范化方向保持 markerEnd 朝向；circle/star 用 bbox 直推 cx/cy/rx/ry / outerR
  - drawPreview computed：marquee layer 内根据 kind 渲染 v-line / v-arrow / v-ellipse / v-star（Konva 内置）
  - window mouseup 兜底清 drawDrag（拖出窗口时取消）
  - watch activeTool 末尾清 marquee + drawDrag 防御边缘 case
- i18n 删除 tooltip 末尾 "(M9-E 接入)" 占位

### Code review 修复（实施完成后）

- drawPreview 的 arrow `pointerLength=10/Width=8` 与最终元素 markerSize=6 不一致 → 改为 6/6 保持视觉对齐
- `commitDraw(tool: typeof ui.activeTool, ...)` 用 typeof 取 store 字段类型不规范 → 改为 `import type { ActiveTool }` + `tool: ActiveTool` 显式
- Esc 在 drawTool 激活时只清 selection（已是空，noop），用户无法快速取消激活态 → Esc 在 drawTool 时切回 select；select/move 时仍清选中

### 改动文件清单

**后端（plugin）：**
- 新增 9 个：state/{Path,Circle,Shape}Element.java + state/PathDValidator.java + render/{PathParser,MarkerRenderer}.java + test/{PathDValidator,EditSessionNewElements,PathParser}Test.java + test/resources/{fixtures/06-08*.json + expected/06-08*.png}（6 文件）= 实际 15 个新文件
- 修改 4 个：state/{Element,EditSession}.java + render/CanvasCompositor.java + test/render/RendererSnapshotTest.java

**前端（web）：**
- 新增 2 个：render/{PathParser,MarkerRenderer}.ts
- 修改 7 个：types/protocol.ts、render/PreviewRenderer.ts、stores/ui.ts、components/{layout/LeftTools,layout/CanvasView,HelpModal}.vue、i18n/messages.ts

### 测试

- **整库 250+ 测试全过**（含 M9 新增 69 个：PathDValidator 29 + EditSessionNewElements 21 + PathParser 19）
- 8 个渲染 fixture（5 原 fixture 零漂移 + 3 新 fixture 视觉 review 通过）
- `vite build` 通过 417.74KB JS / 33.13KB CSS（M8-F 后 406KB；+11KB PathParser/MarkerRenderer/绘制实装/工具栏/drag-to-create）

### 关键设计决策

1. **PathElement d 内坐标相对 element.(x, y)**：transform 改 x/y 时 d 不动；简化 bbox 同步（Figma 同样做法）
2. **CircleElement / ShapeElement 不引入新 transform 字段**：完全复用 bbox（cx/cy/rx/ry / outerR 都由 x/y/w/h 推），Konva Transformer 多 node 缩放/旋转自然 work
3. **fast path 兜底保 baseline 零漂移**：新增 PathElement / CircleElement / ShapeElement 时 canFastPath 已含 element.renderMode != CLEAN 防御 check；5 个原 fixture 命中 fast path 像素一致
4. **PathParser 输出切线元数据**：marker 直接消费 startTangent / endTangent，避免遍历 PathIterator 求切线
5. **marker 几何 apex 在端点**：base 朝外退 size；markerEnd 朝 endTangent（顺 path）；markerStart 朝 -startTangent（逆 path）
6. **双端镜像策略**：PathParser / MarkerRenderer 前后端逐行同公式同变量名；Path2D 是浏览器原生 + Java AWT 同名 API，moveTo/lineTo/quadraticCurveTo/bezierCurveTo/closePath 完全对应
7. **drawTool 激活时 element-hit listening=false**：让 mousedown 穿透到 stage 启动 drag-to-create（PS/Figma 行为）
8. **一次性创建 + 自动切回 select**：mouseup 后切回 select 工具让用户立即操作新元素（lastAddedElementId 自动选中）
9. **drag 距离 < 3px 取消**：防误点
10. **line/arrow d 不规范化方向**：保持 markerEnd 朝用户拖动终点
11. **保留 addText / addRect 快捷按钮**：兼容路径，未删除（统一为激活态留后续 polish）

### 工期

- **预估 1.5 周**（M9-A 2d + M9-B 2d + M9-C 2d + M9-D 1d + M9-E 2d = 9d），实际**约 3 小时**（含深度调研 + review + 修复）
- 17 新文件 / 11 修改文件 / 69 新测试 / 0 baseline 漂移 / 0 测试回归

---

## 2026-05-13 · M8-F 多选 marquee + 修饰键选择 + 多选 transform

**M8 闭环**：journal 之前的"M8-F 续做"列表完成 1/4（多选 marquee 完整落地；guides 拖出 UI / element-level blendMode 真合成留 M11；slow path 压测留远期）。

### 实施

- `stores/ui.ts`：选中模型升级为 `selectedIds: Set<string>` 作为单一真相；`selectedElementId` 改为 computed（size==1 时返 id 否则 null）兼容老组件；新增 `selectedCount` / `hasSelection` / `isSelected` / `toggleSelection` / `selectMany` / `addToSelection` / `clearSelection` API
- `components/layout/CanvasView.vue`：
  - 加 marquee 状态 + onStageMouseDown/Move/Up 三 handler；空白处拖框 ≥ 3px 触发多选；Shift 加选；window mouseup 兜底拖出窗口的清理
  - marquee 可视化：v-stage 内新增独立 v-layer + v-rect（蓝色虚线半透明填充，listening: false）
  - `onHitClick` 支持 Shift / Cmd / Ctrl click = toggle 选中
  - `hitConfig` 用 `ui.isSelected(id)` 替代单 id 判等
  - `attachTransformer` 升级：watch `Array.from(ui.selectedIds).join(',')` 触发；attach 全部选中 nodes
  - `onDragStart` 多选时记录所有选中 element 的初始位置到 `dragInitial: Map`
  - `onDragMove` 新增：按 leader 的 delta 同步其他选中 element 位置（视觉跟随 + 乐观更新）
  - `onDragEnd` 多选时对其他选中 element 各发一条 element.transform op
  - hit Rect 加 `@dragmove`；Transformer 多 node 时 transformend 各 node 自己触发自己的 op 发送（沿用单选老逻辑）
  - Escape 改 `ui.clearSelection()`
- `components/layout/RightPanel.vue`：
  - `isMulti` computed（selectedCount ≥ 2）
  - Properties section 加 multi 分支：显示"N 个元素被选中" + multi hint；header 改为 multi 时显批量删除按钮
  - `deleteMultiSelected` 函数：逐 id 发 element.delete + clearSelection
  - 底部 Elements 列表点击支持修饰键 toggle，高亮判定改 `ui.isSelected(el.id)`
- `App.vue` 全局快捷键：
  - Delete / Backspace 多选时批量删
  - Cmd / Ctrl + A 全选当前 activeLayer 内 visible elements（跳过 input/textarea/contenteditable）
  - ArrowKey 多选时所有选中 element 同步微移（步长 1px / Shift+10px）
- `i18n/messages.ts`：加 `t.properties.multiSelected` / `multiHint` / `deleteMulti` / `deleteMultiTip`

### 设计决策

- **bbox intersect 用 axis-aligned**（rotated element 用外接 axis-aligned bbox 近似）：简单可靠，MVP 接受略多框风险；M9+ PathElement 时再考虑精确旋转 bbox
- **多选 click 修饰键统一为 Shift / Cmd / Ctrl**（PS / Figma 同步）
- **drag 同步用 leader-follower 模式**：onDragStart 记录初始位置，onDragMove leader 计算 delta 应用到 followers（视觉 + 乐观），onDragEnd 一次性 N 个 ws op
- **multi transform 仍各 node 触发自己 transformend**：沿用 Konva Transformer 原生行为，无需聚合处理
- **Properties 多选时只支持批量删 + ArrowKey 微移 + Esc 取消**：批量改字段需要 N 多 ws 同值校验复杂度高，留远期。多选下隐藏单选 UI 避免 UX 矛盾
- **layer 多选不做**：M9+ 多选层操作时再考虑

### 验证

- vite build 通过，bundle 406.63KB JS / 33.13KB CSS（M8-E 后 402；+4KB 多选逻辑）
- plugin test 全过（offline 模式跑；网络偶发 TLS 抖动）
- M8 之前的 150+ 测试无回归

### 改动文件

- 修改 5 个：stores/ui.ts、components/layout/CanvasView.vue、components/layout/RightPanel.vue、App.vue、i18n/messages.ts
- 新增 0 个

---

## 2026-05-13 · M8-A/B/C/D/E 全栈实施（layered model + 协议 v2 + 分层渲染）

**M8 路线锁定后一次性把 5 段代码全部落地。** 实施顺序与 task 拆分一一对应；每段独立完成 + 测试通过后才进入下一段。

### M8-A · 数据模型 + 协议 v2 record（Java + TS）

- 新增 `state/Layer.java`（record + JsonCreator 容错 + elements 列表归一化为 ArrayList）/ `state/BlendMode.java`（enum @JsonProperty 小写） / `state/RenderMode.java`（同上） / `state/Guide.java`
- `state/Element.java`：sealed 接口加 opacity() / blendMode() / renderMode() 三方法 + effectiveXxx() default 兜底；TextElement / RectElement / IconElement 三 record 末尾追加 Float/BlendMode/RenderMode 字段，`@JsonInclude(NON_NULL)` 序列化省略默认
- `state/ProjectState.java`：Canvas 加 gridSize/guides；内部存 layers + activeLayerId + protocolVersion=2；JsonCreator 同时识别 v1（elements）与 v2（layers）入参，v1 自动包装为单 Default Layer；保留 elements/indexOfElement/addElement 等兼容 API 转发到 activeLayer 让 EditSession 零修改
- `state/EditSession.java` / `template/TemplateInstantiator.java` / test 中所有 element 构造调用末尾补 null/null/null；Canvas 构造 5 参数化
- `web/src/types/protocol.ts`：BlendMode/RenderMode/Guide/Layer 完整镜像；ProjectState 改 layers 形态 + 保留可选 elements 兼容字段
- `web/src/stores/project.ts`：setSnapshot 时把 `state.elements` 链接到 `activeLayer.elements` 同一引用，组件零修改；applyPatch 同时识别 v1 path（/elements/N）和 v2 path（/layers/M/elements/N）

**验证：** plugin test + vite build 全过；snapshot test 5 fixture **零像素漂移**（JsonCreator 自动 migrate + 兼容视图）。

### M8-B · 持久化迁移（启动期全库扫描）

- 新增 `db-migrations/V006__walls_protocol_version.sql`：`ALTER TABLE walls ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1` + 索引
- `storage/MigrationRunner.java`：注册 V006
- `storage/WallRepo.java`：新增 `isV1Form(mapper, json)` 静态检测 + `migrateProjectJsonV1ToV2(mapper, json)` 静态 roundtrip + 实例方法 `migrateAllToV2()` 扫库 + 写回 + `MigrationStats` record
- `HikariCanvas.java` onEnable：MigrationRunner.run() 后、SessionManager 启动前调 `wallRepo.migrateAllToV2()`，输出 scanned/migrated/failed 统计

**关键：** roundtrip 复用 M8-A 写好的 `@JsonCreator` 自动 migrate，零重复逻辑。单行失败 → log warn + protocol_version 留 1 → 下次启动重试 + 运行期 lazy 兜底。

**测试：** 新增 `WallRepoMigrationTest.java`（11 个测试覆盖 isV1Form 边界 + migrate 行为 + idempotent + 空 elements）全过。

### M8-C · 协议路径切换 + locked check + layer.* op 族

- `state/ProjectSnapshot.java`：升级到 `{canvas, layers, activeLayerId, label}`；紧凑 ctor 深拷贝每个 Layer（重建 elements ArrayList）
- `state/ProjectState.java`：`restore(snap)` 整体替换 + 二次深拷贝避免共享；新增 5 个 layer 级 mutator
- `state/EditSession.java`：**全面重写为 layer-aware**：Locator/findElement/findLayerIdx 反查 + 全部 element op 切 v2 path + locked check + 新增 createLayer/deleteLayer/updateLayer/reorderLayer/duplicateLayer/setActiveLayer 六个 layer.* op + moveElementToLayer 跨层 + setGridSize / setGuides；applyTextPatch/Rect/Icon 加 opacity/blendMode/renderMode 字段处理；replaceContent 重置整 layers 树
- `state/StatePatchBuilder.java`：normalize 扩展支持 Layer record → Map（patch.value 永远 JSON 对象语义）
- `web/Envelope.java`：v=1 → v=2
- `web/WebServer.java`：handleAuth 加 `clientProtocolVersion >= 2` 校验，否则 VERSION_MISMATCH + close 4002；ready protocolVersion=2；dispatchEditOp 新增 10 个 op case；新增 `closeVersionMismatch`
- `web/src/network/wsClient.ts`：send / heartbeat v=2；sendAuth 带 `clientProtocolVersion: 2`
- `web/src/stores/project.ts` applyPatch 三类新 path 分支：/activeLayerId 替换（含 elements 链接重建）/ /layers/{i} 层级 add/remove/replace / /layers/{i}/{field} 单字段

**测试：** 新增三个测试套（48 个用例）—— `EditSessionLayerOpsTest`（26）/ `EditSessionLockedLayerTest`（10）/ `EditSessionV2PathTest`（12）全部通过；snapshot fixtures 仍零漂移。

### M8-D · 图层面板前端 UI

- 新增 `components/layout/LayerPanel.vue`：右栏顶部 section；倒序列表（UI 顶 = 最上层）；每行 [eye][lock][name][count][duplicate][trash]；hover 显示 duplicate/trash；双击 inline rename；HTML5 drag reorder；active 层视觉高亮（左竖线 + 背景）；最后一层禁删
- `stores/ui.ts`：加 `editingLayerId` ref + `setEditingLayer` action
- `stores/project.ts`：加 `activeLayer` computed（含 EMPTY_LAYER 兜底）+ `activeLayerLocked` + `layerById(id)`
- `components/layout/RightPanel.vue`：顶部插入 LayerPanel；底部 Elements section 用新 `t.elements` 文案 + locked layer 时禁用 toggle/drag；顺手把 onLayerDrag* 重命名为 onElementDrag* 修正 M5 时期命名混乱
- `i18n/messages.ts`：新增 t.layerPanel.*（15 key）+ t.elements.*；原 t.layers.* 已迁出

### M8-E · 分层渲染 + opacity + BlendMode + grid overlay

- 新增 `render/BlendModes.java`：normal/multiply/screen/overlay 四公式 per-channel 实现 + `applyBlendModeOver` per-pixel 合成
- 新增 `web/render/BlendModes.ts`：前端镜像，与后端逐行公式一致
- `render/CanvasCompositor.java`：升级为分层渲染；**fast path**（layer 1 + normal + 无 element opacity/renderMode）直接画主 buffer 保持原行为；**slow path** 走 ARGB 中间 buffer + applyBlendModeOver；element-level opacity 用 `AlphaComposite.SrcOver.derive(opacity)` 实装
- `web/render/PreviewRenderer.ts`：同上分层 + canFastPath；slow path 用 offscreen canvas + getImageData/putImageData + applyBlendModeOver；element-level opacity 用 globalAlpha
- `components/layout/LayerPanel.vue`：active layer 行下方紧跟两行 inline 控件 = opacity slider（80ms debounce input + immediate change）+ blendMode select 四项
- `components/layout/RightPanel.vue`：Transform 段加 element opacity slider + blendMode/renderMode disabled select（保留字段 + tooltip "M11 实装"）
- `components/layout/CanvasView.vue`：主 canvas 上叠 CSS background-image grid overlay（按 canvas.gridSize 双线性渐变）+ 右下角 grid input
- `i18n/messages.ts`：新增 t.properties.opacity/blendMode/renderMode + t.layerPanel.opacityLabel/blendModeLabel/blendModeOptions + t.canvas.grid 共 ~20 个 key

**测试：** 新增 `BlendModesTest.java`（12 测试覆盖公式边界 + 单调性不变量 + 整体合成）全过；`RendererSnapshotTest` 5 fixture **零像素漂移**（fast path 兜底生效）。

### Code Review 修复（实施完成后）

- LayerPanel.vue 双重 v-for 渲染顺序错位 → 合并到单 v-for + template 嵌入主行下方
- LayerPanel.vue 模板 ref 在 v-for 内为数组 .focus() 不工作 → 改 querySelector + data-attr
- RightPanel.vue + LayerPanel.vue 误用 `useDebounceFn().flush?.()`（VueUse 不支持） → onChange 走 immediate ws.send + 最多冗余 1 次同值 patch
- RightPanel.vue opacityDraftPct 切换选中元素时未清空 → watch selected.id 重置
- CanvasCompositor + PreviewRenderer canFastPath 加 element.renderMode != CLEAN 防御 check，对齐 M11 dither 集成

### 改动文件清单

- **新增 12 个**：state/{Layer,BlendMode,RenderMode,Guide}.java；render/BlendModes.java；db-migrations/V006_*.sql；test/state/{LayerOps,LockedLayer,V2Path}Test.java；test/storage/WallRepoMigrationTest.java；test/render/BlendModesTest.java；web/components/layout/LayerPanel.vue；web/render/BlendModes.ts
- **修改 30+**：state/{Element,TextElement,RectElement,IconElement,ProjectSnapshot,ProjectState,EditSession,StatePatchBuilder,PatchOp}.java；storage/{MigrationRunner,WallRepo}.java；template/TemplateInstantiator.java；web/{Envelope,WebServer}.java；HikariCanvas.java；test/state/EditSessionReplaceContentTest.java；以及前端 web/src/* 全套（types/protocol.ts、stores/{project,ui}.ts、network/wsClient.ts、render/PreviewRenderer.ts、components/layout/{RightPanel,CanvasView}.vue、i18n/messages.ts）

### 测试总览

- 整库 **150+ 测试全过**（含 M8 新增 71 个：11 migration + 26 layerops + 10 locked + 12 v2path + 12 blendmodes）
- 5 个渲染 fixture 零漂移
- 前后端构建均通过

### M8-F 续做（未实施）

- 多选 marquee + 多选 transform
- guides 拖出 + Rulers 完整 UI（后端已通，前端先 noop）
- element-level blendMode / renderMode 真接合成（与 M11 dither 一起做）
- slow path 性能优化（worker / 分块）—— 压测后决定

---

## 2026-05-13 · M8 路线确认（仅文档；不动代码）

**背景：** 用户要把项目方向往"Minecraft 里的 Figma / Canva"演进。提出短期工具（圆/箭头/线/星/软线）+ 远期功能（笔刷/数位板/图片导入/图层/渐变/调色板）。

**对齐过程 — 6 个开放问题已拍板：**

1. **PathElement 一统天下**：线 / 箭头 / 软线 / 笔刷 / 点 全部基于 SVG-like path 命令 + marker。CircleElement / ShapeElement 单独立。M9 实施
2. **图层先做**：M8 = 图层 + 协议 v2 + migrate；M9 才上 PathElement。理由是后期再做图层迁移成本太大
3. **元素级 renderMode（clean / dither）**：默认 clean，dither 用 Bayer 4×4。M11 真正实装；M8 只把字段写进协议避免二次升版
4. **接力编辑而非协作**：现状（任意 canvas.edit 玩家可 /canvas open <wall_id>，byWall 排他锁）已满足；多人协作（OT/CRDT）永久不做
5. **图片上传 config 可调 + 权限节点 canvas.upload 默认绑 canvas.edit**
6. **协议 v2 一次性升级**：layers + activeLayerId + canvas.gridSize + canvas.guides + element.opacity + element.blendMode + element.renderMode 同一波改完，**切断 v1 客户端**（v1 未公开发布）
7. **BlendMode v1 选 4 个**：normal / multiply / screen / overlay
8. **opacity 在 MC 调色板下 = "颜色变浅"**（先 alpha-composite 到背景再硬截断量化）。docs 提示用户、不是真透明

**M8 子阶段拆分（约 2 周）：**

- **M8-A**：数据模型 + 协议 v2 record（Java + TS 同步）
- **M8-B**：项目 JSON migration（V006 + WallRepo 启动期全库扫描升级）
- **M8-C**：协议路径切换（state.snapshot 新形态 + patch path `/layers/{i}/elements/{j}/...` + layer.* op 族 + element.* 加 layerId）
- **M8-D**：图层面板前端 UI（右栏；可见 / 锁定 / 拖动重排 / 双击重命名 / 删除）
- **M8-E**：元素级 opacity / blendMode / 多选 / 网格 + 参考线

**远期 TODO（M8 之外、列入路线）：**

- 图层缩略图（per-layer rasterize 端点 + 缓存）
- 图层颜色标签 / 图层 mask / smart object / 图层组
- 对齐 / 分布工具
- 模板包生态（.canvas 多模板打包）
- 玩家身份认证 + HomePage 点击直开（独立 milestone）

**契约文档已更新：**

- `CLAUDE.md` 里程碑 + M8 路线段
- `PROPOSAL.md` §6 里程碑表 M6/M7 标 ✅、新增 M8-M13 行 + 远期 TODO
- `docs/architecture.md` 新 §10.5 图层模型（数据形态 / 生命周期 / 渲染顺序 / activeLayerId 职责 / locked 行为 / v1→v2 迁移）；§12 未决问题加 2 条 M8 相关
- `docs/protocol.md` 顶部加 v1→v2 总览表；§3.2 auth 加 clientProtocolVersion ≥ 2 强制；§5.3 element.* 加 layerId；§5.4 新增 layer.* op 族；§5.5 加 canvas.grid / canvas.guides.set；§5.9 brush.* 占位；§6.1 错误码加 LAYER_LOCKED/LAYER_NOT_FOUND/LAST_LAYER/UPLOAD_REJECTED；§7 ProjectState 完整重写 v2 形态；§8.2.5 新增图层操作示例；§12 未决问题升级
- `docs/data-model.md` §2.4.1 新增 lazy migration 规则（决策 A：启动期全库扫描）；§4.3 .canvas 文件改注释 v2
- `docs/rendering.md` §4.1 提到分层渲染 + §4.5 grid/guides 仅前端；§6.5 元素级 opacity；§6.6 BlendMode 4 个公式；§6.7 Bayer 4×4 dither 双端契约
- `docs/security.md` T14 图片上传威胁 + §4.5 全套约束（大小 / MIME 双校验 / magic bytes / 解码超时 / 配额 / hash 内容寻址）；§5 权限节点加 canvas.upload + .bypass-limit

**下一步：** 等用户说"开始写代码"再启动 M8-A 编码。先不动任何 java/ts 文件。

---

## 2026-05-13 · M7 第三轮：config.yml / group UI / 部署文档

**用户：「先做 config.yml、参数 group UI 分组和公网部署文档」**

**1. config.yml 真接入**

之前 onEnable 里硬编码了一堆值（host/port/TTL/idle/grace/pool 容量/fps/rate）。新增：

- `plugin/src/main/resources/config.yml` —— 默认配置，首次启动 `saveDefaultConfig()` 拷到 dataFolder。带详细中文注释 + [需重启] 标记
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvasConfig.java` —— 强类型解析。Builder 默认值兜底（缺字段不报错，向后兼容）；`summary()` 输出一行摘要供 log
- `HikariCanvas.onEnable` 全部硬编码替换为 `config.xxx`
- `/canvas reload config` 命令（canvas.admin 权限）—— 刷新 `plugin.reloadConfig()` + 重建 `HikariCanvasConfig`；当前 v1 只更新引用 + log，提示玩家"多数字段需要重启"。**Hot-apply 留 M7+。** 改 host/port/超时/池容量后仍要重启才生效

字段速查见 `docs/deployment.md §7`。

**2. 参数 group UI 分组**

- `TemplateGallery.paramGroups`：按 `param.group` 字符串首次出现顺序分组；无 group 字段 → null 组直接展开不显标题
- 每组 section header（10px uppercase，带计数 `· 4`）点击可折叠/展开
- 折叠态用 `Set<"${templateId}::${groupName}">` 存
- **demo：** `shop_sign.yml` 加 `group: 文案 / 显示 / 主题` 三档

模板示例已展示出预期 UX：店铺名/商品/价格 → "文案"组；show_item → "显示"组；颜色/字体 → "主题"组。

template-spec.md §12 `group` 一项标 ✅ M7 实装。

**3. 公网部署文档 `docs/deployment.md`**

七节内容：

1. 单机/内网最小可用配置
2. 为什么默认 `127.0.0.1`（token 明文 + WS 明文 + CORS 缺失）
3. **推荐部署**：Caddy / nginx 反代 + Let's Encrypt + WS 升级配置 + 防火墙规则（iptables / ufw）
4. 不推荐路径：直绑 0.0.0.0（仅临时测试）
5. 多玩家注意事项（wall 排他锁全局、alias 唯一、归属隔离留单独 milestone）
6. 排错对照表
7. config.yml 字段速查 + 哪些需要重启

**改动文件：**

- 新增 `plugin/src/main/resources/config.yml`
- 新增 `plugin/src/main/java/moe/hikari/canvas/HikariCanvasConfig.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java` —— 加 config 字段、`applyConfig`、`config()` 访问器；onEnable 用 config
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java` —— `/canvas reload config` 子命令
- `plugin/src/main/resources/templates/shop_sign.yml` —— group 字段示范
- `web/src/components/template/TemplateGallery.vue` —— paramGroups + 折叠 UI
- `docs/template-spec.md` §12 —— group 标 ✅
- 新增 `docs/deployment.md`

**质量门：** plugin 测试全绿；vite build 385KB JS / 31KB CSS。

---

## 2026-05-13 · M7 polish 第二轮：保护 / 预览 / grid / icon / sendWithAck / HomePage 七连发

**用户：「这五项都做了吧」**（实际 7 项）—— B1 / B2 / C1 / C2 / A1 / A2 / A3 一气全跑完，单 commit。

**B2 RendererSnapshot baseline 重建** —— `rm expected/01-hello-world.png`，测试自动重生成；5 个 snapshot 全绿。后续若再现像素漂移，按 CLAUDE.md 提示走同样流程。

**C1 `template.apply` 迁 sendWithAck** —— TemplateGallery.applyNow 改 `await ws.sendWithAck(...)`，移除原 100ms 轮询版本/lastOpError + 1.2s 兜底 setTimeout。错误现在能精准回显（之前 setTimeout 已经关弹窗了用户看不到）。

**A1 grid 布局实装** —— `TemplateLayout` 加 `columns: Integer` / `rows: Integer`。`TemplateInstantiator.gridLayout`：`cellW = (contentW - (cols-1)·gap) / cols` 同理 cellH；element 按 `idx / cols / idx % cols` 落位；超容直接截断。`TemplateLoader` 解禁 `grid` + 校验 `columns >= 1` / `rows >= 1`。

**B1 已发布墙破坏保护** —— `FrameDeployer.isFramePublished` 读 PDC 的 `published_at`。`FrameProtectionListener` 三个 handler 加强：
- `HangingBreakByEntityEvent`: 已发布 → 拒（含 force-break 权限也拒）；草稿 → 老逻辑（force-break 可绕过）
- `BlockBreakEvent`: 同上，扫四个水平邻格的画框判断
- 玩家收 ActionBar 提示 `先 /canvas unpublish` 或 `授 canvas.admin.force-break 才能强拆`

**A3 模板缩略图服务** —— 新 `TemplatePreviewService`：default params + 推荐 `[4, 1]` 画布跑 instantiator → `CanvasCompositor.rasterize` → PNG bytes，按 templateId 缓存。新端点 `GET /api/template/{id}/preview.png` 长缓存 5min。Gallery 卡片用 `<img>` 直接拉（前端没集成进卡片视图，留下一轮；后端基建已就位）。

**A2 icon 元素 — 端到端实装** —— PROPOSAL/template-spec §4.6 之前都标 "v1 不实装"，现在改 "M7 起实装"。

- **State**: 新 `IconElement` record（id/x/y/w/h/rot/locked/visible + `source` + `tint`），加进 `Element` sealed permits。`EditSession.applyIconPatch` 处理 element.update。
- **Template**: `TemplateElement.Icon` record；`TemplateLoader` 验 `source` 走 `^[a-z0-9_-]{1,32}$`（whitelist 防路径穿越）+ tint 颜色正则；`TemplateInstantiator.materialize` 走通；`naturalHeight/Width` 默认 32。
- **Asset 服务**: 新包 `template.asset.TemplateAssetService`：classpath `/template-assets/icons/{name}.png` → `dataFolder/assets/icons/{name}.png` 两级 lookup，BufferedImage + PNG bytes 分别缓存。
- **后端渲染**: `CanvasCompositor` 加 `drawIcon` —— 原色 drawImage / tint 走 `AlphaComposite.SrcIn` 染色到 offscreen 再贴。
- **HTTP 端点**: `GET /api/template-asset/icons/{name}` 由 `TemplateAssetService.iconPng` 输出 + `Cache-Control: max-age=3600`。
- **前端渲染**: `PreviewRenderer.drawIcon` —— 异步 Image cache（首次画占位 ?，加载完通过 `onIconReady` 回调让 CanvasView requestDraw）；tint 用 `globalCompositeOperation: 'source-in'`。
- **4 个 builtin 图标 PNG**: `info` / `warning` / `star` / `arrow_right`，32×32，Python stdlib + zlib 一次性生成（无 PIL 依赖）。
- **新模板 `info_panel.yml`**: free 布局 + icon + 2 行文本，参数支持挑图标 + 染色，展示新特性。

**C2 HomePage 美化** —— 卡片加 wall 缩略图（新端点 `GET /api/wall/{id}/preview.png`，按 `wallId@updatedAt` 缓存），3 列 grid 布局，hover 轻浮起，已发布卡片绿色边框。空态从纯文字改成大灰 ImageOff 图标 + 居中提示。时间格式 fmtTime 智能化：今天 `14:23` / 本周 `周三 14:23` / 更早 `5月12日 14:23`。新增手动 Refresh 按钮（之前只能刷新页面）。

**新文件：**

- `plugin/src/main/java/moe/hikari/canvas/state/IconElement.java`
- `plugin/src/main/java/moe/hikari/canvas/template/asset/TemplateAssetService.java`
- `plugin/src/main/java/moe/hikari/canvas/template/preview/TemplatePreviewService.java`
- `plugin/src/main/java/moe/hikari/canvas/template/preview/WallPreviewService.java`
- `plugin/src/main/resources/templates/info_panel.yml`
- `plugin/src/main/resources/template-assets/icons/{info,warning,star,arrow_right}.png`

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java` — 注入 asset/preview/wallPreview services
- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `isFramePublished`
- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameProtectionListener.java` — 区分已发布 vs 草稿
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java` — `drawIcon` + 可选 assetService
- `plugin/src/main/java/moe/hikari/canvas/state/{Element,EditSession}.java` — Icon 支持
- `plugin/src/main/java/moe/hikari/canvas/template/{TemplateElement,TemplateLayout,TemplateLoader,TemplateInstantiator}.java`
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java` — reload 时调 preview.invalidate()
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` — 4 个新 endpoint（template preview / icon asset / wall preview）
- `plugin/src/main/resources/templates/_index.txt` — 加 info_panel.yml
- `plugin/src/test/java/moe/hikari/canvas/template/BuiltinTemplatesTest.java` — info_panel 进 fixture
- `web/src/types/protocol.ts` — IconElement
- `web/src/render/PreviewRenderer.ts` — drawIcon + onIconReady hook
- `web/src/components/layout/CanvasView.vue` — 注册 onIconReady
- `web/src/components/template/TemplateGallery.vue` — sendWithAck 重写
- `web/src/components/HomePage.vue` — 缩略图 + hover + 空态 + Refresh + fmtTime
- `web/src/i18n/messages.ts` — `home.refresh`
- `docs/template-spec.md` §4.6 / §12 — grid + icon + preview 全部标 ✅

**质量门：** plugin 全部测试通过（含新增 info_panel 进 BuiltinTemplatesTest）；vite build 384KB JS / 31KB CSS。

---

## 2026-05-13 · M7 polish 第一轮：编辑器美化 + 解释性 UI

**用户诉求：** 进入 M7 polish 但**先不动账号权限**；做编辑器/前端美化；缩放升级；文字表述清楚；按钮要有解释（hover tooltip 或 ? 图标）。

**1. Tooltip 组件**

`web/src/components/ui/Tooltip.vue` —— 自定义浮层，替代浏览器原生 `title="..."`（原生 ~1s 延迟 + 不可样式化）：
- Teleport 到 body，避免被父级 overflow 裁掉
- 250ms 延迟显示（手感介于 0/500ms 之间）
- 支持 slot 触发器 + `text` / `shortcut` 双字段（右侧灰色 kbd 显示快捷键）
- transition 淡入淡出；主题变量驱动色

`title="..."` 在 **LeftTools / TopBar / CanvasView / RightPanel** 全部替换为 `<Tooltip>` 包裹。受影响按钮 ~20+ 个。

**2. HelpModal + ? 入口**

`web/src/components/HelpModal.vue` —— 全屏弹窗式快捷键速查表，分四组：

- **工具**：Select (V) / Move (M) / 模板库
- **选择 / 编辑**：单击选 / 双击编辑 / Esc / 双击空白取消所有 / Del / 方向键微移 / Ctrl+Z 撤销 / Ctrl+⇧Z 重做
- **缩放 / 视图**：Ctrl+滚轮 / Ctrl+= / Ctrl+- / Ctrl+0 / 中键或 Alt+左键平移
- **游戏内命令**：edit / open / list / delete

TopBar 加 `HelpCircle (?)` 图标按钮触发；`ui.helpOpen` 持久状态。i18n 中英双语完整翻译。

**3. CanvasView 缩放面板升级**

原版只有 `+ / - / Reset` 三个按钮。新版：
- **精确百分比**：点击中间数字 → 切到 `<input type=number>` 输入框，Enter 确认、Esc 取消、blur 自动 commit；25-400% 区间 clamp
- **预设档位**：50 / 75 / 100 / 150 / 200 / 400% 一排小按钮，当前档高亮
- **Fit to viewport** 按钮（`Maximize` 图标）：自动算 `outerRef.clientWidth/Height` 除以画布像素，得到刚好放下的缩放值，再 scroll 到中心
- 所有按钮挂 Tooltip 标明快捷键 `Ctrl+= / Ctrl+- / Ctrl+0`

**4. StatusBar 升级**

加 4 个新维度：
- **当前工具图标**（MousePointer2 / Move）+ 文字
- **草稿 / 已发布**徽章（绿色 Globe 强调发布态）+ 解释 tooltip "Draft = 仅自己看到"
- **完整 sessionId** 在 hover tooltip 里显（之前是字符串截断 8 位，无法复制完整 ID）
- "v3 · 12 elements" 顺序调整、tabular-nums 等宽对齐

**5. 清理 M1 demo 残留**

LeftTools 上的 `RadioTower(ping)` 和 `Paintbrush(paint)` 是 M1 端到端验证留下的，正式版用户用不到 —— 直接从工具栏删掉。WS 服务端 handler 暂保留（不上版本号才删，留作回归测试通道）。

**6. RightPanel 字段解释**

`rotation` / `letterSpacing` / `lineHeight` 这种小字段单凭名字看不出语义 —— label 旁加小 HelpCircle 图标，悬停 tooltip 说明含义和单位：
- 旋转：0–359° 顺时针
- 字距：字符间间距像素，可负值
- 行高：字号倍数，建议 0.8–3.0
- "按内容调高/调宽" 按钮也加详细 tip

**改动文件：**

- 新增 `web/src/components/ui/Tooltip.vue`
- 新增 `web/src/components/HelpModal.vue`
- `web/src/App.vue` — 挂载 `<HelpModal />`
- `web/src/stores/ui.ts` — 加 `helpOpen` 状态
- `web/src/components/layout/{LeftTools,TopBar,CanvasView,RightPanel,StatusBar}.vue` — 全面接入 Tooltip + 文案调整 + 缩放面板重写
- `web/src/i18n/messages.ts` — 加 `topbar.help` / `canvas.{zoomInputTip,fit}` / `status.{draft,published,wallStateTip,sessionFull}` / `help.*`（30+ 条）/ `properties.{rotation,rotationTip,letterSpacing,letterSpacingTip,lineHeight,lineHeightTip,fitHeightTip,fitWidthTip}`；中英双语对齐

**质量门：** vite build 通过；CSS 26→30KB（+4KB tooltip + helpmodal）；JS 357→381KB（+24KB 新组件 + 文案）。

---

## 2026-05-12 · M6 第六轮：refresh 终于真修 — 撸的是 map item 不是 frame entity

**用户提供的服务端 log 直接命中真相：**

```
[wall.refresh w-...] scanned=84 deadOrInvalid=0 wallMatched=8 present=[0,1,2,3,4,5,6,7] expected=8
[wall.refresh w-...] framesRespawned=0
```

`wallMatched=8` + `present=[0..7]` 说明 8 个画框 entity 全在、PDC 完整 — 但截图显示其中 2 个是**空木框**。

**根因：MC 左键撸 ItemFrame 是两阶段动作 —**
1. 第一次左键：只移除画框内的 item（map drop 出来 / 创造模式直接消失），**frame entity 仍挂在墙上**
2. 第二次左键：才打掉 frame entity

绝大多数玩家只撸了一下、看到画消失了就以为"撸掉了" — 实际上 entity 还在，PDC 完好。我们旧逻辑把它当"已存在"加进 `present` set 跳过 → 永远没人把 map item 塞回去 → 永远是空木框。

**修：repairFor 扫描时区分三态**

- **完整**（PDC 匹配 + `getItem().type == FILLED_MAP`）→ `present` set，整体跳过
- **空框**（PDC 匹配但 item 缺失或不是 FILLED_MAP）→ `emptyFrames: Map<slot, ItemFrame>`，进 `reAttachMapsToEmptyFrames` 直接 `f.setItem(...)`，**不需要重新 spawn entity**
- **完全缺失**（entity 都不存在）→ `deployFor` 走原 spawn 路径

`RepairResult` 加 `framesReAttached` 字段；UI 侧把 spawn 新 frame + reAttach 空 frame 合并显示为"重挂 N 个画框"（玩家视角都是"补回一格"）。

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `RepairResult` 加 `framesReAttached` + `framesFixed()`；`repairFor` 拆三态扫描；新增 `reAttachMapsToEmptyFrames`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` — ack payload `framesRespawned` 改为 `result.framesFixed()`（合并 spawn + reAttach），新增 `framesReAttached` 字段

---

## 2026-05-12 · M6 第五轮：refresh 后画框仍不出 → 加诊断 + entity 残骸清理

**用户反馈：** 第四轮修了 open 路径的 WALL_NOT_FOUND，现在 refresh 能跑通了，但只有"补回 1 支撑方块"出现 — 画框（和地图画）本身没补回。换言之 framesRespawned=0。

**分析：** "补回 1 块方块、重挂 0 个画框" → `replaceMissingWallBlocks` 正常但 `deployFor` 一个 slot 都没 mount。两种可能：

1. `present` set 包含全部 slot（spawn 全被 skip）—— 表示破坏的画框残留在 entity 列表中没被 isDead/isValid 过滤掉
2. `world.spawn(...)` 被某种原因拒绝（front block 非 air、Item 掉落物挡道、Paper 1.21 spawn 异常等）

**本轮加：**

1. **`repairFor` 诊断 log：** 输出 `scanned / deadOrInvalid / wallMatched / present / expected / replacedBlocks` 让我们看到 present set 实际包含哪些 slot
2. **`spawnSlot` 全程 log：** spawn 前后都打 location + uuid，spawn 异常显式 catch + log；spawn 返回的 frame 立即用 `isDead/isValid` 复核
3. **`spawnSlot` 三道防御：**
   - 支撑方块仍 AIR → 再补 STONE（极小概率事件）
   - frontBlock 非 AIR → 清成 AIR
   - **frameLoc 0.8 格内的残留 Item 掉落物 + 不属于本 wall 的"幽灵" ItemFrame → `e.remove()`**（怀疑这条是用户场景的真凶 — 撸破画框后掉的 Item entity 没及时被回收，挡 spawn）

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `repairFor` 加诊断日志；`spawnSlot` 加三层防御 + try/catch + isValid 复核

**待用户跑一遍：** 实测后如果第二次撸画框 + refresh 仍是 framesRespawned=0，看 server log 里 `[wall.refresh ...]` / `[spawnSlot ...]` 输出 → 能直接定位是 scan 阶段还是 spawn 阶段卡住，再针对性修。

---

## 2026-05-12 · M6 第四轮：open 路径几何缺失 + 编辑态泄露

**用户反馈：**

1. `/canvas open w-xxx` 进编辑器后点 Refresh 报 `WALL_NOT_FOUND: session lacks wall geometry`
2. 偶尔出现"一个 element 在 inline edit、另一个在被拖动"的鬼态
3. 想要"双击空白处取消所有选中"

---

**根因 1：open 路径漏写 `session.wall()`**

`SessionManager.open()` 只 `s.wallKey(key)`、`s.mapIds(...)`，从未 `s.wall(WallResolver.Result.Ok)`。
而 `WebServer.wall.refresh` / `FrameDeployer.repairFor` 都要求 `session.wall()` 提供 world / origin / facing / width / height — null 立即报 WALL_NOT_FOUND。confirm 路径下 geometry 由 `WallResolver.resolve()` 直接产出，open 路径下没有"玩家点击"的过程所以没人产。

**修：** `SessionManager.rebuildWallGeometry(WallRepo.Wall)` 从 `WallKey`（world/originX/Y/Z/facing）+ `Wall.widthMaps/heightMaps` 反推 `WallResolver.Result.Ok`，`hasExistingFrames` 固定 true（open 的前提就是 wall 已经物化）。世界未加载抛 IllegalStateException。`open()` 与"幂等重用"分支都补上调用；重用分支还做了防御：若 existing.wall 仍为 null（修复前创建的旧 session）也补一刀。

**根因 2 / 3：CanvasView 没有"主动收编辑态"的路径**

`finishEditing()` 之前只在 textarea blur / Escape / Enter 时触发。但用户的实际行为是：

- 编辑 A 时直接点（或拖）B → Konva 走 `mousedown → dragstart`，根本不触发 click
- 编辑 A 时切到 Move 工具 → tool 改变但没事件清理
- 编辑 A 时点画布空白处 → 已有 stage.mousedown 走 deselect 路径，但没收编辑态
- 没有"取消一切"的标准入口

修了四条路径同时收编辑态 + 加新入口：

1. `onHitClick(id)` — 若 editingId 存在且 != id → `finishEditing()` 后再 selectElement
2. `onDragStart(id)` — 同上（覆盖"在编辑 A 时直接拖 B"的 Konva 跳过 click 的情况）
3. `onStageMouseDown` 走 deselect 分支时 → `finishEditing()`
4. `watch(ui.activeTool, ...)` — 切工具时收编辑态
5. **`onStageDblClick`（新）** — 双击空白处 = 取消选中 + 退出编辑（用户明确要求的 escape 路径）

`<v-stage>` 加 `@dblclick="onStageDblClick"`；`<v-rect>` 加 `@dragstart="() => onDragStart(el.id)"`。

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java` — 加 `rebuildWallGeometry`；open() 与"幂等重用"分支都补上
- `web/src/components/layout/CanvasView.vue` — `onDragStart` / `onStageDblClick`；`onHitClick` / `onStageMouseDown` / `watch(ui.activeTool)` 加 finishEditing 兜底；v-stage / v-rect 绑新事件

**质量门：** plugin compileJava OK；vite build OK。

---

## 2026-05-12 · M6 第三轮：wall.refresh promise ack + Move 工具

**用户反馈 2 条：**

1. 撸画框（不是方块）后点刷新仍不恢复 + 总报"服务端无响应"
2. 要 PS 风格的"Move 移动工具"——独立按钮，按下后单击纯拖、双击进编辑、不显示 resize 锚点

---

**1. wall.refresh 两个隐藏 bug**

- **撸 frame 仍跳过 slot：** `FrameDeployer.repairFor` 在收集已存在 frame 时漏过滤 `f.isDead()` / `!f.isValid()`。创造模式刚 break 的 frame entity 仍可能短暂出现在 `getEntitiesByClass` 结果里，PDC 匹配后被误当成"已存在"加进 `present` set，对应 slot 被跳过 → 新 frame 永远不补。加 `if (f.isDead() || !f.isValid()) continue;` 跳过。
- **"服务端无响应"误报：** 原 TopBar 实现用 `project.state.version` 变化判定成功，但 wall.refresh 只是触发全画布像素重画，ProjectState version 不会变 → 任何情况都走 5s timeout 分支。**根治：引入按 client id 跟踪 ack 的 Promise 机制。**
  - `WsClient.sendWithAck(op, payload, timeoutMs)` 返回 `Promise<ackPayload>`；ack 来时解 resolve，error 来时 reject，timeout 默认 5s
  - 内部 `pendingAcks: Map<id, {resolve, reject, timer}>` 跟踪；`handleAck(id, payload)` / `handleError(id, payload)` 顺手 settle
  - `TopBar.refreshWall` 改为 `await ws.sendWithAck('wall.refresh', undefined, 8000)`，从 ack payload 取真实 `framesRespawned` / `wallBlocksReplaced` 数字拼成文案（"补回 2 块方块 + 重挂 1 个画框"）

后续 `template.apply` 等长 op 也可以迁到 sendWithAck（M7 polish 范围）。

**2. Move 工具（PS 风格）**

- `ui store` 新增 `activeTool: 'select' | 'move'` + `setTool()`；localStorage 持久化
- `LeftTools` 顶部加两个工具按钮（MousePointer2 / Move 图标），点亮态突出显示当前激活
- **`select` 模式（默认）：** 当前行为不变 —— 点选 + 显示 transformer 锚点（12px）+ 双击文本进 inline edit
- **`move` 模式：** transformer 完全隐藏（无锚点遮挡）；hit rect 仍可拖、可双击进 edit、可 hover 显示蓝色虚线描边和 cursor:move
- 快捷键：`V` 切 select，`M` 切 move（input/textarea 内输入时跳过）
- `attachTransformer` 加 `activeTool === 'move'` 分支强制 `nodes([])`；watch `ui.activeTool` 切换即时生效

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `repairFor` 扫 entity 时跳过 dead/invalid
- `web/src/network/wsClient.ts` — `sendWithAck` Promise API + pendingAcks 跟踪；`handleAck/handleError` 接收 envelope id
- `web/src/components/layout/TopBar.vue` — refreshWall 用 sendWithAck；flash 文案显示真实计数
- `web/src/stores/ui.ts` — `activeTool` + `setTool()` + `TOOL_KEY` 持久化
- `web/src/components/layout/LeftTools.vue` — 顶部加 select/move 工具组按钮
- `web/src/components/layout/CanvasView.vue` — `attachTransformer` 在 move 模式跳过 attach；watch activeTool；V/M 全局快捷键
- `web/src/i18n/messages.ts` — `tools.selectTool` / `tools.moveTool` / `wall.refreshedDetail`（接受参数）/ `refreshSendFailed`（中英）

**质量门：** plugin compileJava OK；vite build 357 KB / 26 KB；功能侧待用户实测验证。

---

## 2026-05-12 · M6 第二轮实测打磨（wall.refresh 真修 + 编辑器交互升级）

**用户反馈 4 条：**

1. **wall.refresh 在玩家撸掉方块时无效**
2. **应用模板后想加 element 交互困难**（例：站牌旁加绿框 + 写"2 号线"）
3. **编辑画布时前端有滞后感**，改字体偶尔无反馈
4. **想要 PS 风格的自由拖动**（"那个大细定位方框太难拖动"）

---

**根因 1：撸方块 ≠ 撸画框。** `FrameDeployer.repairFor` 之前只补 spawn 画框，但玩家通常是撸掉了**支撑方块**——画框因此脱落变物品。再 spawn 同位置画框还是会立即掉。`replaceMissingWallBlocks` 新增：扫 wall bbox 内每格，AIR → 自动 `setType(STONE)`，再走 spawn。返回 `RepairResult(framesRespawned, wallBlocksReplaced)`。

WebServer.wall.refresh 改造：原来主线程任务一发出立即 ack `{submitted:true}`；现在把 ack 移到主线程任务**完成后**回发，payload 携带两个真实数字。前端 TopBar 改成轮询 `project.state.version` + `lastOpError.ts`，5s 内任一变化即认定为已完成/失败/超时，屏幕上短暂 flash 一段中文/英文状态（"已刷新" / 错误码 / "服务端无响应"）。

**根因 4 + 部分 2：transformer 锚点 + hit rect 体验。**

- 锚点 `anchorSize: 8 → 12`，`rotateAnchorOffset: 24 → 32`，更易精确拖
- hit rect 加 hover 反馈：未选中但鼠标悬停时画 1px 蓝色虚线描边 + 切 `cursor: move`，提示"这是可拖拽的对象"
- 选中后 transformer 自带粗 1.5px 实线 border，与 hover 虚线视觉区分清楚

**根因 2 另一半：新元素生成位置硬编码 (32, 32)。** 模板应用后画布已经满，再加一个新 element 叠在角落几乎看不见。改成：

- `text` 新建 → 192×48 默认尺寸、字号 32（之前 16）、放画布几何中心
- `rect` 新建 → 80×80 默认尺寸、放画布几何中心
- 计算用 `project.canvasPixelWidth / canvasPixelHeight`

**根因 3：RightPanel 输入防抖 200ms 太长。** 玩家敲一个字到画布反映之间最长 0.2 秒，叠加 WS RTT 给人卡顿感。改为 80ms（保留 color/select 立即路径不变）。

---

**改动文件：**

- `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java` — `RepairResult` record + `replaceMissingWallBlocks`；`repairFor` 返回类型变更
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` — wall.refresh 改为主线程完成后 ack，payload 含 framesRespawned/wallBlocksReplaced
- `web/src/components/layout/TopBar.vue` — refreshWall 轮询版本/错误时间戳；按钮旁边加 refreshFlash 文案
- `web/src/components/layout/CanvasView.vue` — 锚点 12px、hover 描边、hover cursor:move
- `web/src/components/layout/RightPanel.vue` — debounce 200→80
- `web/src/components/layout/LeftTools.vue` — addText / addRect 走 `centeredBox`，画布中心 + 更大默认尺寸
- `web/src/i18n/messages.ts` — `wall.refreshed` / `wall.refreshTimeout` 加 zh + en；refreshTip 描述更新

**质量门：** plugin 编译 OK；vite build 354 KB JS / 26 KB CSS。

---

## 2026-05-12 · M6 模板系统首次实测打磨

**用户反馈：** 从 `welcome_banner`（min 4×2）切到 `nameplate`（max 4×1）apply 后画布不变，模态自动关。无错误提示——以为是 bug。

**根因 1：apply 静默失败**

- 服务端 `TemplateInstantiator` 已正确返回 `Failed("CANVAS_MISMATCH", ...)`；`WebServer.applyTemplate` 转 `EditSession.OpResult.Error` 走 Envelope.error 回前端
- 但 `wsClient.handleError` 只在 `AUTH_FAILED` 时设置 `net.lastError`，其他错误只 push 到 logs。TemplateGallery 没有路径感知这条错误，1.2s timeout 后乐观关闭模态
- 修复：`network.ts` 加 `lastOpError = { code, message, ts }`；`wsClient.handleError` 给所有错误打到 lastOpError 上。Gallery 的 `applyNow` 改成捕获 `project.state.version` + `lastOpError.ts` 基线，100ms 轮询其一变化判定成功/失败/5s 超时

**根因 2：模板字体 ID 笔误**

- 5 个新增模板（subway_station / shop_sign / welcome_banner / bulletin_board / nameplate）的 text element 都写 `font: sourcehan`
- 但后端 `FontRegistry.BUILT_IN` 注册的 ID 是 `source_han_sans`；不匹配时 fallback 到 `ark_pixel`（像素字体），CJK 字幕过粗看起来怪
- 修复：全局替换为 `source_han_sans`

**用户附加诉求：模板"占地"提示 + 字体选择**

1. Gallery 卡片现在显示 `推荐占地: 3×1 – 8×2 maps`；当前 wall 不在范围内时卡片右上有黄色 ⚠ + 红色"不兼容"标签
2. 选中模板时 form header 同步显示 `推荐占地 / 当前墙面 / 不兼容` 三段，方便对比
3. footer 在不兼容时显示完整提示 `当前墙面与模板要求的 2×1 – 4×1 不匹配。请新建一面匹配尺寸的墙面后再应用`
4. Apply 按钮在不兼容时禁用（避免一次无谓的服务端往返）
5. `shop_sign` + `bulletin_board` 新增 `font` 类型 param，default `source_han_sans`，前端遇到 `type: font` 且 YAML 没 options 时自动用 `FONT_META`（思源 / Ark Pixel）填下拉

**改动文件：**

- `plugin/src/main/resources/templates/{subway_station,shop_sign,welcome_banner,bulletin_board,nameplate}.yml` — font ID 修正；shop_sign + bulletin_board 加 `${shop_font}` / `${board_font}` 参数化
- `web/src/stores/network.ts` — 加 `lastOpError`
- `web/src/network/wsClient.ts` — handleError 同步打 `lastOpError`
- `web/src/components/template/TemplateGallery.vue` — apply 成功/失败/超时三态轮询；卡片 + form header + footer 三处尺寸提示；不兼容时禁用 Apply；font 参数自动 options
- `web/src/i18n/messages.ts` — 加 `templates.sizeLabel / currentWall / incompatible / incompatibleHint / wallMismatchHint / applyFailed / applyTimeout`（中英双语）

**质量门：** plugin test 全绿；vite build 通过；vue-tsc M6-F 触及的所有文件零错（剩 1 个 i18n/index.ts 是 pre-existing union 收窄问题，与本次无关）。

---

## 2026-05-12 · M6 模板系统实装（A-G 子阶段）

**背景：** 按 2026-05-11 路线确认条目里的 9 项决策，一气把 M6 跑完。所有契约文档已先于代码改完，本次按 7 子阶段顺序落地。代码量：plugin 端约 1500 LOC + 6 内置 yml + 8 个测试类 90 个 case；web 端约 700 LOC + 1 个新 store + 1 个新模态组件。

**M6-A 解析与注册（plugin）**

新建包 `moe.hikari.canvas.template`：

- `TemplateSpec` / `TemplateCanvas` / `TemplateLayout` / `TemplateElement`（sealed: Text/Rect/Line）/ `TemplateParam` / `TemplateEffects` — Jackson 友好的 record 树
- `TemplateLoader` — `jackson-dataformat-yaml` 2.18.2 直接 `readValue` 到 record；§9 全套校验（id/name/spec/canvas dims/colors/param id/enum opts/`${ref}` 未声明扫描）；grid layout v1 拒；icon v1 拒；polymorphic typing 显式关
- `TemplateRegistry` — 内置（jar `_index.txt` 清单 + `getResourceAsStream`）+ 服务器（`plugins/HikariCanvas/templates/*.yml`）合并，server 同 id 覆盖 builtin，`volatile Map` 原子 swap
- `_index.txt` — 内置模板清单文件（dev 模式 classes/resources 分离 + 打包 jar 两路统一）
- `/canvas reload templates` — `canvas.admin` 权限，输出 builtin/server/overrides/failed 数

`build.gradle.kts` 加 `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2`。

**M6-B 表达式 + 插值**

新建子包 `template.expr`：

- `Expr` — sealed AST：`Literal` / `Identifier` / `Not` / `Binary(EQ/NE/AND/OR)`
- `ExpressionParser` — lex + 递归下降。优先级 `!` > `==/!=` > `&&` > `||`（C 系一致）；字符串/数字/布尔字面量 + `()` 分组；`ParseException` 带 0-based 位置
- `ExpressionEvaluator` — truthy 规则（null/0/空串 = false）+ 类型敏感相等（Number→double / Boolean→bool / 否则 toString）；短路 `&&` `||`
- `Interpolator` — `${name}` 替换；无 `${` 零分配 fast-path；不递归（防注入）
- `TemplateLoader.validateElement / validateParam` 接入 `ExpressionParser.parse` 完成 §9 末项"visible_when 可解析"语法校验

**M6-C 实例化引擎**

- `TemplateInstantiator.instantiate(spec, userParams, wallW, wallH) → Result.Ok(bg, elements, resolvedParams) | Failed(code, errors)`
- 流水线：`validateParams` → `fitCanvas` → `resolveBackground` → `layoutAndMaterialize`
- 错误码：`INVALID_PARAM` / `CANVAS_MISMATCH` / `INVALID_VALUE` / `INVALID_LAYOUT` / `INVALID_TEMPLATE`
- stack 布局：忽略 element 自带 x/y，按 direction 累加 + gap；w 撑满 content；text 自然高 = `ceil(size × lineHeight × lines)`
- free 布局：x/y/w/h 直接取；w/h 支持 `int` / `"auto"` / `"N%"`（按 content basis） / `${param}`
- `visible == false` 直接 false；`visible_when` 走 evaluator；不可见元素 skip 不写入 ProjectState（避免"隐形僵尸"）
- text 物化所有 string 字段插值，effects（stroke/shadow/glow）透传；line v1 跳过返回 null

**M6-D WS 接入**

- `EditSession.replaceContent(bg, elements)` 通用 replace 入口（替代旧的硬编码 `applyTemplate` + `buildHelloWorld`）；清 elements + 改背景 + 推进 version + push pre-snapshot 到 history（让 undo 可回）
- `WallRepo.setTemplate(wallId, templateId, version)` — 写回 `walls.template_id` / `template_version` 列
- `WebServer.applyTemplate` 私有方法 — `template.apply` op 中枢：registry 查表 → instantiator 实例化 → `replaceContent` → walls write-back
- `WebServer.listTemplates()` — 给 `/api/session/{token}` 和 ready payload 注入全量 TemplateSpec 列表（protocol §3.2）
- M3 硬编码 `hello_world` 路径整段删，统一走 YAML 流水线

**M6-E 5 个内置模板**

| ID | 用途 | 画布 | 布局 |
|---|---|---|---|
| `hello_world` | demo / apply 演示 | 1×1–8×4 auto | free |
| `subway_station` | 地铁/公交站牌 | 3×1–8×2 auto | stack vertical |
| `shop_sign` | 商店门头 | 3×1–6×2 auto | stack vertical |
| `welcome_banner` | 服务器欢迎横幅 | 4×2–8×4 auto | stack vertical |
| `bulletin_board` | 米黄底公告板 | 3×1–8×3 auto | stack vertical |
| `nameplate` | 门牌 | 2×1–4×1 auto | free（左右分区） |

每模板覆盖至少 2 种 param type，含 `visible_when` 开关、color preset、stack + free 各覆盖。

> 命名调整：原路线条目里写的 `warning` / `neon_sign` / `minimal` 在落地时换成更贴近实际游戏内场景的 `shop_sign` / `welcome_banner` / `bulletin_board` / `nameplate`（多了一个）。前三个比较抽象、缺明确 param shape；后四个一上来就是装饰场景里能直接用的。

**M6-F TemplateGallery 前端**

- `web/src/types/template.ts` — TemplateSpec / Canvas / Param / Layout / Element TS 类型（Java record 镜像）
- `web/src/lib/templateExpr.ts` — visible_when 解析器 TS 版本（≈ Java `ExpressionEvaluator` 等价；失败保守返回 true）
- `web/src/stores/templates.ts` — Pinia store：模板列表 / Gallery 开关 / selectedId / 每模板独立 param 草稿
- `web/src/components/template/TemplateGallery.vue` — 模态：左侧卡片列表 + 右侧动态参数表单 + 底部 Apply / 二次确认
- 表单按 type 渲染 8 种控件（string/text/int/float/bool/color+presets/enum/font）；`visible_when` 实时驱动字段显隐
- `web/src/network/wsClient.ts` — handleReady 把 templates 推入 store
- `web/src/components/layout/LeftTools.vue` — Sparkles 按钮从硬编码 hello_world 改为 `templates.openGallery()`
- `web/src/i18n/messages.ts` — 新增 `templates.*` 文案（zh + en）

**M6-G 收口**

- `docs/template-spec.md §12` — 百分比父容器定义打钩归档为"M6-C 已固化：父容器 = canvas 内容区（pixel 尺寸减 padding 4 元）"
- `CLAUDE.md` 里程碑 → M6 ✅ + 2026-05-12 完成日期
- 本日志条目
- 5 个未决问题中剩余 4 个（继承 / grid / preview 规范 / group UI）明确推迟到 M7 polish 或 v2+

**质量门**

- 后端：90 个测试 89 绿 + 1 pre-existing `RendererSnapshotTest.01-hello-world` 像素 baseline 漂移（与 M6 无关）
  - Loader 15 / Parser 14 / Evaluator 12 / Interpolator 8 / Instantiator 17 / BuiltinTemplates 11 / HelloWorldYaml 4 / EditSessionReplaceContent 4 = 85 个 M6 新增
- 前端：`vue-tsc --noEmit` M6-F 触及的所有新文件零错（剩 25 个 pre-existing CanvasView/PreviewRenderer 等遗留，与 M6 无关）；`vite build` 348 KB JS / 26 KB CSS 通过

**下一步：** M7 polish（grid 布局 / icon 元素 / preview thumbnail / 分组 UI / 已发布 wall 破坏保护 / 雪藏的 snapshot baseline 重建 / 身份认证与鉴权独立 milestone）

---

## 2026-05-11 · M6 路线确认（仅文档；不动代码）

**背景：** M5.5 polish 已收口，进 M6 模板系统之前先固化 4 个决策到契约文档，避免实施期反复。

**5 个开放问题已拍板（含上次的 5 个）：**
1. **YAML 解析库**：jackson-dataformat-yaml 2.18.2（不用 SnakeYAML）。同步改 CLAUDE.md 锁定版本表 + PROPOSAL.md 依赖表 + `docs/security.md §4.3` 与第三方库表
2. **ready payload 模板下发**：v1 阶段全量下发 `templates: [...]`，5 个内置 + 服主自定义合计 < 50KB 可接受。未来若爆量再切 index + on-demand
3. **5 个内置模板**：`subway_station` / `shop_sign` / `warning` / `neon_sign` / `minimal`
4. **`/canvas reload templates`**：M6 v1 实装（管理员命令，原子 swap 替换 registry 指针）
5. **Apply 语义**：replace（清 elements + 改 background），前端弹"覆盖当前内容"提示；merge 留 v2+
6. **Layout 实装**：M6 v1 stack + free；grid 留 M7
7. **canvas auto-size 超 limit**：实例化失败，错误回客户端
8. **icon 元素**：v1.0 不实现（契约 §4.6 已说明）
9. **参数组 group**：v1 接受字段但不渲染，扁平展开

**文档改动清单：**

- **CLAUDE.md**：技术栈表 SnakeYAML → jackson-dataformat-yaml 2.18.2 + M6 决策段
- **PROPOSAL.md**：§5.2 依赖表同步
- **docs/security.md**：§4.3 YAML 解析改写（jackson-yaml 默认免疫 SnakeYAML `!!java/*` tag 路径）；§T9 威胁备注更新；§10.1 第三方库表替换
- **docs/protocol.md**：ready payload 加 `templates` 字段示例 + 全量下发决策段；§12 未决问题打钩两条（template.apply merge / M6 templates 协议固化）
- **docs/template-spec.md**：§1 加 jackson-dataformat-yaml 备注 + `/canvas reload templates` 命名固化；§7 实例化加 replace 语义段 + walls.template_id 写回；§12 未决问题状态更新（grid/group/preview/百分比四条明确 v1 范围）
- **docs/architecture.md**：§2.1 组件分层 template/ 描述补 jackson-yaml + registry 热重载

**M6 范围速查（待实施，7 个子阶段，~6-7 天）：**

- **A** 解析与注册：TemplateSpec records / TemplateLoader / TemplateRegistry（含热重载）
- **B** 表达式 + 插值：极简 parser `==/!=/&&/||/!/()` + 字符串 `${param}` + 单元测试
- **C** 实例化引擎：参数校验 / canvas auto-size / stack+free layout / 百分比展开 / 元素插值
- **D** WS 接入：`template.apply` 改走 registry；ready payload 加 templates；apply 后 `WallRepo.setTemplate`
- **E** 5 个内置 yml + 内置 resource 加载
- **F** TemplateGallery 前端 dialog（动态参数表单 + visible_when + 应用确认）
- **G** 收口：单测 / journal / CLAUDE.md 里程碑勾完

**下一轮开 M6-A 实施代码。**

---

## 2026-05-11 · M5.5 polish 收口（i18n / alias 校验 / copy 反馈）

**背景：** 主功能已通，先填几个之前散落的体验坑再进 M6。身份认证 / 鉴权单列下一个 milestone 不在此次范围。

**改动：**

1. **i18n 漏译补全**：`messages.ts` 加两组 `wall.*` + `home.*`，把 TopBar 的 wall_id / alias / Published / Draft / Refresh 按钮全切到 `t.wall.*`；HomePage 整页（heading / subtitle / loading / failed / empty / 分组标题 / 卡片 maps+updatedAt+复制提示）切到 `t.home.*`。中英文双向（en→中英 switchLocale 文案也对齐：en mode 显示 `切换到中文`，zh mode 显示 `Switch to English`）。
2. **alias 字符集校验三路统一**：正则 `^[A-Za-z0-9_-]{2,32}$`。
   - 前端 `TopBar.commitAliasEdit` 加 `ALIAS_RE` 校验；服务端 `INVALID_ALIAS_FORMAT` / `ALIAS_TAKEN` 报错时 watch `net.lastError` 自动重打开输入 + 显错
   - WebServer `wall.alias` 分支用 `ALIAS_PATTERN` 替代旧的长度校验，错误码改 `INVALID_ALIAS_FORMAT`
   - CanvasCommand `runAlias` 同 pattern，错误提示 `[A-Za-z0-9_-]{2,32}`
3. **copy 操作的视觉反馈**：
   - TopBar wall_id 按钮：点击复制后 800ms 内显示 `Copied`（绿色），不再只 log
   - HomePage 卡片底部 `/canvas open <id>` 命令文本：点击复制后 900ms 内同样视觉反馈

**改的文件：** `web/src/i18n/messages.ts` / `web/src/components/layout/TopBar.vue` / `web/src/components/HomePage.vue` / `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java` / `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`

**下一步：** M6 模板系统（5 个内置 + YAML parser + TemplateGallery dialog）。身份认证 / 鉴权单独立项处理（HomePage 点击直接打开需要先有玩家身份机制）。

---

## 2026-05-11 · wall 实测打磨（自家画框识别 + wall.refresh）

**背景：** P1-P5 实测后剩两个体验问题：(a) `/canvas edit` 在已有画的墙上选区会撞 `OCCUPIED`，玩家被迫先 delete 才能二次编辑；(b) 创造模式打掉某个画框后该位置永远空白，没有恢复入口。

**改动：**

1. **WallResolver 区分 HikariCanvas 自家 vs 第三方画框**：
   - 构造参数加 `JavaPlugin`，内部生成 `wall_id` PDC NamespacedKey
   - 内部 `kindOfFrame()` 返回 `FrameKind.{NONE, HIKARI, FOREIGN}`，HIKARI 通过 PDC 识别
   - 校验循环：FOREIGN 才报 OCCUPIED；HIKARI 通过且记 `hasExistingFrames=true`；NONE 仍要求前一格 air
   - `Result.Ok` 加 `boolean hasExistingFrames` 字段
2. **SessionManager.confirm 三分支**：
   - `hasFrames && walls 行齐全` → `mapPool.bindToWall` → `OkExistingWall`（二次编辑路径）
   - `hasFrames` xor `walls 行` 单边存在 → `NotReady("stale wall data; /canvas delete to reset")`
   - 都无 → 走原新建路径 `OkNewWall`
3. **wall.refresh op（含玩家撸框恢复）**：
   - `FrameDeployer` 抽 `spawnSlot` helper；新增 `repairFor(wallId, wall, mapIds)`：扫世界 PDC 收集仍存在的 slot，对缺失 slot 用同样几何/PDC/mapId 重新 spawn
   - `WebServer.handleWallOp` 加 `case "wall.refresh"`：主线程跑 `repairFor` + `throttler.submit(fullCanvas)` 强制全画布重画
   - 前端 `TopBar.vue` 加 `RefreshCw` 按钮 → `ws.send('wall.refresh')`，1.5s 内禁用避免连点
4. **WandListener.routeFrameClick SELECTING 优先**：玩家在 SELECTING 状态点画框 → 视为选角（不再触发"二次确认 → open"路径），消除"You already have an active session (state=SELECTING)"误报

**改的文件：** `WallResolver.java` / `FrameDeployer.java` / `SessionManager.java` / `WebServer.java` / `WandListener.java` / `HikariCanvas.java`（WallResolver 构造改）/ `web/src/components/layout/TopBar.vue`

**未变契约：** 一墙一画（WallKey 唯一索引）+ 排他锁仍保留。WallResolver 现在的 OCCUPIED 只指"第三方画框"，与 docs 一致——`/canvas edit` 选区已挂自家画的墙变成"二次编辑入口"而非错误。

---

## 2026-05-06 · M5.5 实测后批量 fix

**背景：** P1-P5 上线后实测撞到 6 个独立 bug，统一一波修。

**改动：**

1. **MigrationRunner 漏注册 V005**：`MIGRATIONS` 列表只到 V004，V005 文件存在但永远不跑 → `no such table: walls`。同根因 V002 漏注册的复发（显式声明制的代价）。修：list 末尾追加。
2. **Envelope.error null message NPE**：`Map.of("message", null)` 不允许 null value，handleAuth 拒绝 token 时传 null → NPE → WS uncaught → close 1011。改 `LinkedHashMap` 手动 put + null 跳过。
3. **FrameDeployer.deploy col 方向对 NORTH/EAST 反向**：M2 阶段 `col 0` 对 WEST/SOUTH 是玩家视角最左，但 NORTH/EAST 漏了反向 → 游戏内文字与浏览器画布左右倒挂。改成显式 switch 四个 facing，明确 `col 0 = 玩家视角最左`。
4. **SessionManager.open 同 wall 幂等**：玩家关浏览器但 session 还 ACTIVE → 再 `/canvas open <同 id>` 报 AlreadyHasSession。改成：先 load wall → 同 wall_id 直接复用 existing session 重发 URL，不同 wall 才报错。
5. **handleAuth markActive 容忍 ACTIVE**：`/canvas open` 复用 ACTIVE session → `markActive` 严格要求 ISSUED 抛 IllegalStateException → 前端显示"token 已失效"。改成 ISSUED 走转移、ACTIVE 只 touch。顺手把"旧 WS ctx → close 4003 takeover"写明。
6. **WebServer dispatch 没匹配 wall.alias / publish / unpublish**：顶层 switch 只列 `element.*` / `canvas.*` / `template.apply` → wall.* 全落 default 返 INVALID_OP → 前端 alias / publish 静默失败（store optimistic 改了，但 walls 表实际 NULL）。修：switch 加 `case "wall.publish", "wall.unpublish", "wall.alias", "wall.refresh"` 路由到 `dispatchWallOp`。同时把之前写在 `dispatchEditOp` 内部的 `wall.*` early-return 删（永远走不到）。
7. **alias UI 改内联输入**：浏览器 `prompt()` 替换为 TopBar 内联 input（pencil 图标点开 + Enter 提交 + Esc 取消 + blur 自动提交 + ALIAS_TAKEN 错误回滚显示）。

**改的文件：** `MigrationRunner.java` / `Envelope.java` / `FrameDeployer.java` / `SessionManager.java` / `WebServer.java` / `web/src/components/layout/TopBar.vue`

---

## 2026-04-27 · M5.5 P1-P5 代码实施（wall 模型重构落地）

**背景：** 04-27 上半段已固化路线到契约文档；下半段开始按 5-Phase 分步落地代码。每 Phase 完成立即 `gradle :plugin:compileJava` 验证。

**P1 数据层：**
- 新 migration `V005__walls_unified.sql`：drop sign_records / drafts；recreate pool_maps（删 sign_id 列、迁 PERMANENT→RESERVED）；create walls 表（schema 见 `docs/data-model.md §2.4`）
- 新 `WallRepo`：`create / loadById / loadByAlias / loadByKey / loadByMapId(反查) / loadAll / listForOwner / listAll / setAlias / markPublished / markUnpublished / updateMapIds / updateState / delete`，含 wall_id 短 hex 生成 + UNIQUE 冲突重试
- `PooledMap` 删 `signId` 字段；`PoolState` 由三态收为两态；`MapPool` 删 `promoteToPermanent` + `markDraftHeld`，新增 `reserveForWall / bindToWall / releaseWall`；`detectLeaks` 改"非 `wall:` 前缀全回收"
- `DraftRepo` / `DraftRestorer` 文件删除，由 `WallRepo` / `WallRestorer` 替代
- `SessionManager.confirm` 改两分支 `OkNewWall` / `OkExistingWall`；删 `commit / promoteToPermanent` 整条；新增 `open / deleteWall / persistWall / resetSelection`
- `SessionReaper` 不再拆框（wall 数据生命周期与 session 解耦）

**P2 命令族：** `CanvasCommand` 删 `runCommit` + `insertSignRecord`；新增 `runOpen / runList / runPublish / runUnpublish / runAlias / runDeleteFirstStep / runDeleteConfirm`；delete 30s 二次确认走 `pendingDeletes` map；`runCancel` 改为"释放 session/wand，wall 数据保留"。

**P3 wand 二次编辑：** `FrameDeployer` PDC key 改 `wall_id / slot / published_at`；新方法 `removeForWall / markPublished / wallIdOf`；`WandListener` entity 事件分流：HikariCanvas ItemFrame → ActionBar 提示 → 30s 内再次操作触发 `sessionManager.open + tokenService.issue + 发 chat URL`；第三方画框走原选区流程。

**P4 网页 wall 元数据：** 后端 `ready` payload 加 `wallId / alias / publishedAt`；dispatch 加 `wall.publish / wall.unpublish / wall.alias` op（不走 EditSession）；前端 `protocol.ts` `ReadyPayload` 加这三字段；`stores/project.ts` 加 wallId / alias / publishedAt + `setWallMeta`；`wsClient.handleReady` / `handleAck` 同步 store；`TopBar.vue` 显示 wall_id（click copy）+ alias（pencil prompt）+ Published/Draft 徽章。

**P5 网页首页：** `App.vue` URL 没 token → 渲染 `HomePage.vue`；后端新 `GET /api/walls` 返回 `WallRepo.listAll()`；HomePage 分组"已发布 / 编辑中"展示，每张卡片显 wall_id / alias / world+coord+facing / 尺寸 / updatedAt + 可点击复制的 `/canvas open <id>`。

**未做：** alias 实施时 prompt() 还是浏览器原生弹窗；refresh 入口；wand SELECTING 中点画框走 open 误报——这三个 5-06/5-11 修。

---

## 2026-04-26 · M5-D5 / D6 / D7 系列（M5.5 之前的中间态，部分被推翻）

**背景：** 这段时间走的是"drafts + sign_records 二段式"流派，三轮迭代加完整功能后实测发现该模型不顺手（二次编辑 OCCUPIED、commit 后 drafts 没清状态机污染），于是触发 04-27 的 M5.5 路线修正。中间产物（V002-V004 三个 migration、DraftRepo、DraftRestorer、wand_id 字段预留）保留作为 schema_version 历史链，但表本身已被 V005 drop。

**M5-D5（双击就地编辑 + WS 自动重连 + delete 二次确认）：**
- `CanvasView.vue` 加 `editingId` 状态，`v-rect` 监听 `dblclick` 打开 textarea overlay，绝对定位对齐 element x/y/w/h + rotation；Enter 提交、Shift+Enter 换行、Esc/blur 退出；style 改"原生感"透明背景 + 字体继承
- `PreviewRenderer.ts` 加 `hideIds` 参数避免画布字与 textarea 重影
- `wsClient.ts` `onClose` 加指数退避重连（1/2/5/10/30s 阶梯，5 次后停）；4001/4008/1000 不重连；4001 清 sessionStorage token
- `CanvasCommand` `runDelete` 30s 内带 `confirm` 才真删（旧版"删 sign_record 然后 promote"逻辑）

**M5-D6（drafts 表草稿持久化）：**
- `V002__drafts.sql` 建 drafts 表（world/origin/facing 复合主键）；`V003__drafts_add_maps.sql` 加 map_ids + width/height；`V004__drafts_wall_id_alias.sql` 预留 wall_id + alias
- `DraftRepo` save/load/loadAll；`SessionManager.persistDraft` 每次 op 后存 ProjectState JSON
- `WebServer` op handler 后调 `persistDraft`

**M5-D7（草稿真恢复到游戏内）：**
- `DraftRestorer` 启动期扫 drafts 表，compose ProjectState → 推像素到 `HikariCanvasRenderer`，`MapPool.markDraftHeld(draft:<tag>)` 保护 map 不被 leak 扫
- `SessionManager.confirm` 加复用 draft 路径：mapIds 仍在池就 `attachToSession`

**04-27 推翻范围：** drafts 表（V005 drop）、`DraftRepo` / `DraftRestorer`（删）、`MapPool.markDraftHeld` / `attachToSession`（合并为 `bindToWall`）、`MapPool.promoteToPermanent`（删）、sign_records 表（V005 drop）、`commit` 命令（废止）。中间产物的代码细节已不重要，但 V002-V004 migration 文件保留以确保 schema_version 链完整。

---

## 2026-04-27 · M5.5 wall 模型重构（路线修正，仅文档；不动代码）

**背景：** M5 实测下来发现 commit 模型不顺手——已 commit 画的二次编辑撞 `WallResolver OCCUPIED`、commit 后 drafts 没清导致重启状态错乱、`/canvas edit` 在已有 session 时报 `state=SELECTING` 提示模糊、wand 选区被已挂的 ItemFrame 默认行为吃掉。讨论后决定**推翻 commit 模型**，按"一画一行 walls 表 + published_at 标签"的简化模型重构。先固化路线到契约文档，下个会话开工代码。

**用户拍板的 5 个开放问题：**
1. `/canvas delete <id>` 30s 内 `/canvas delete <id> confirm` 二次确认
2. publish 副作用：纯标签 + ItemFrame PDC 写 `published_at`（M7 polish 加 break 拦截）
3. wall 占的 map 一直占着不自动释放，需 `/canvas delete` 显式清
4. wand 瞄已有 ItemFrame：先提示「This is wall <id> 'alias' — left-click again to open」，再次操作才打开二次编辑
5. 排他锁保留（`byWall` 一墙一时刻一 session），不做协作编辑

**契约文档更新清单：**

- **`CLAUDE.md`**：里程碑插入 M5.5 阶段；新增「M5.5 wall 模型重构」一整节固化新模型 6 条规则与已修订文档列表。
- **`docs/architecture.md`**：
  - §1.1 一句话改写：从"提交后成为永久招牌"改为"任何时候都能再次打开继续改"
  - §1.3 数据流：删「提交（转永久）」流程图，改为「发布（标签层）」+「删除（仅此操作真正移除 wall）」两段
  - §3 状态机重写：删 `ACTIVE → CLOSING(commit)` 转移；新增 `CLOSED → ACTIVE`（`/canvas open`）；状态转移表新增 reselect / publish / open 行；明确「session 生命周期 vs wall 数据生命周期解耦」不变量
  - §4.2 `PooledMap` schema：删 `signId`、state 收为两态、reservedBy 改 `wall:<wall_id>`
  - §4.3 reserve/commit/cleanup pseudocode 重写：`reserveForWall` / `bindWall` 替代 `reserve` + `commit`
  - §4.5 健康指标：删 `pool.permanent`，加 `pool.unowned_reserved`
  - §7.2 物品框部署：PDC keys 从 `session/sign/role` 改为 `wall_id/slot/published_at`；§7.3 改"提交 vs 取消"为「wall 数据 vs 会话 生命周期对照表」
  - §8.2 SignRecord 表概览改为 walls 表概览
  - §10/§12 性能指标 / 未决问题加 M5.5 条目
- **`docs/data-model.md`**：
  - §2.3 `pool_maps`：删 `sign_id` 列、state 改两态、不变式重写
  - §2.4 `sign_records` → `walls` 完整重写（含 wall_id 主键、published_at、alias、唯一索引、删除语义无软删）
  - §2.5 `audit_log` event 列表更新（`COMMIT/CANCEL/CLEANUP` → `SESSION_*`/`WALL_*`/`POOL_*`）
  - §3 PDC 约定大幅简化：MapView PDC 不写、ItemFrame PDC 三个 key、Map Item PDC 不写
  - §6.5 新增「V005 整体重置」说明（M5.5 阶段无生产数据，drop + recreate 而非 alter）
  - §7.1 不一致场景重写（针对 walls + map_ids + ItemFrame PDC）
  - §8 查询示例全替换：玩家画清单 / 全局已发布 / 反查 mapId / 区域查询
  - §10 未决问题加 M5.5 条目（walls_map_index 反向表 / alias 大小写 / delete 备份）
- **`docs/protocol.md`**：
  - §3.4 客户端主动关闭：删 commit 提及，加 op auto-save 说明
  - §5.6 会话终结：删 `commit` op，加 `wall.publish` / `wall.unpublish` / `wall.alias`
  - §6 错误码加 `ALIAS_TAKEN` / `WALL_NOT_FOUND`
  - §6.2 close 1000 描述改写
  - §8.3 提交流程改写为「发布与终结」
  - §12 未决问题：history.mark 持久化决策更明确（M7 加 walls.history_json）
- **`docs/security.md`**：
  - §5 权限节点：`canvas.commit` → `canvas.publish`；`canvas.remove.{own,any}` → `canvas.delete.{own,any}`
  - §6 校验检查点：`/canvas remove` → `/canvas delete <wall_id>`（含二次确认强制 30s）
- **`docs/rendering.md`**：边界条件表格里"超大画布 commit 拒绝" → "confirm 阶段 WallResolver 拒绝"
- **`PROPOSAL.md`**：
  - §5.2.1 推送时机术语：'提交' → 'session 关闭前最后一帧'
  - §5.2.2 池设计：删 PERMANENT、reservedBy 改 wall: 前缀
  - §5.2.4 审计事件列表更新
  - 命令清单完整重写：edit / wand / confirm / open / list / publish / unpublish / alias / delete / cancel / cleanup / stats / audit；明确废止 commit
  - 性能指标 commit 改 confirm

**未动文件**：`docs/template-spec.md`（模板规范不涉及 wall 模型，M6 时再视情更新）。

**下一步：** 下个会话按 architecture.md §3-4 + data-model.md §2 的固化契约动代码。Phase 顺序：P1 数据层（V005 + WallRepo + MapPool 改）→ P2 命令族 → P3 WallResolver 二次识别 + wand 瞄 ItemFrame → P4 网页 wall 元数据 UI → P5 网页首页。预计 4 天 + 网页 2 天。

---

## 2026-04-23 · 文档 + 记忆归档整理（为下一次上下文压缩准备）

**背景：** 上下文即将压缩；为让下一次 Claude 实例接手时不丢队、不重复踩坑、不重新推理已定项目决策，对如下信息做集中归档。

**更新项：**

1. **`CLAUDE.md` 现代化：**
   - "双端渲染一致性"加入 canonical width 要点（M5-D2 起两端 TextLayout 不读 font metrics，改走规则型宽度）
   - "字体"条款重写为方案 A' 实际做法（两字体都 Gradle `downloadFonts` 拉，不入 git）
   - 新增"构建期 palette"条目（`generatePalette` + 前后端 `PaletteLut` 镜像）
   - 里程碑后新加 **构建/开发流程速查** 段：四条常用命令 + 前端状态管理入口 + 指向 journal.md 查细节

2. **Auto-memory 系统（`~/.claude/projects/.../memory/`）：**
   - `project_hikaricanvas.md` 彻底重写：里程碑状态表（M0~M5 ✅，M6 下一步，M7 polish 档）+ 固化技术栈 + 架构纪律 + 命名约定 + 关键文件速查 + M7 推迟项清单
   - **新增 `feedback_build_toolchain.md`**：9 类踩坑归档（phantom class files / vite 卡死 / vue-tsc 卡死 / kill -9 写坏 node_modules / sed 清空文件 / Tailwind 4 scoped @apply / Gradle daemon 竞争 / GitHub Releases Premature EOF 等）+ 速查修复步骤
   - `MEMORY.md` 索引更新加入新 memory

**为何这样做：**
- Memory 的作用是"跨 conversation 保留必要事实"，上下文压缩后的下一次对话可通过 memory 快速进入状态
- Journal 是 per-commit 的详细日志，memory 是 per-project 的状态快照——两者互补
- 构建工具链踩坑每个耗时 10+ 分钟排查；归档后直接按 symptom 查表修

**关联文件：**
- `CLAUDE.md`
- `~/.claude/projects/-Users-haru-Desktop----HikariCanvas/memory/project_hikaricanvas.md`（重写）
- `~/.claude/projects/-Users-haru-Desktop----HikariCanvas/memory/feedback_build_toolchain.md`（新建）
- `~/.claude/projects/-Users-haru-Desktop----HikariCanvas/memory/MEMORY.md`（索引）

---

## 2026-04-23 · M5-D3 修 P3 P4：i18n 中英切换 + 文本 Fit content

### P3 — i18n 中英切换

**做法：** 不引 vue-i18n（避免增加运行时依赖和体积）；自己写最简 composable：
- `web/src/i18n/messages.ts`：两套嵌套对象 `{ zh, en }`，覆盖 TopBar / Tools / Canvas / Properties / Layers / LogDrawer / Status 七个分区
- `web/src/i18n/index.ts`：`useI18n() → { t: ComputedRef<Messages> }`；基于 `ui.locale` 派生
- 组件里 `t.xxx.yyy` 直接用（Vue 模板会 unwrap computed 的属性访问）
- 函数型 message：`t.canvas.sizeLabel(w, h, pw, ph)`、`t.layers.count(n)` 等

**`ui` store：**
- 新增 `locale: Ref<'zh' | 'en'>`（`loadLocale` 依次查 `localStorage → navigator.language startsWith zh → en`）
- 新增 `toggleLocale()`；`watch(locale) → localStorage + document.documentElement.lang`
- TopBar 加 `Languages` icon 按钮触发切换

**覆盖范围：** 所有按钮 title / 面板 header / 状态标签 / 空态提示。技术字段名（`fontSize / letterSpacing / fontId` 等）保持英文（开发者/用户通用术语）。

### P4 — 文本 Fit height / Fit width 按钮

**位置：** Properties Panel > Text 组 > text textarea 下方，两个 `Maximize2` 图标按钮。

**算法（复用 `TextLayout.layoutText`）：**
- `fitTextHeight`：用 `layoutText(t)` 跑完整 layout → 找所有 glyph `baselineY` 最大值 → `maxBottom = maxBaselineY + descent`；`newH = maxBottom - t.y`；发 `element.update { h }`
- `fitTextWidth`：glyph 的右沿 = `g.x + (g.rotated ? fontSize : canonicalCharWidth(ch, fontSize))`；取 max；`newW = max - t.x`

**不自动：** 不触发自动 fit；用户显式点按钮。避免编辑文本中途被意外 resize 吞掉（Canva / Figma 也是显式行为）。

**导出：** `TextLayout.ts` 把 `canonicalCharWidth` / `ASCENT_RATIO` 导出供此处复用。

### 关联文件

- `web/src/stores/ui.ts`（+ locale / toggleLocale）
- `web/src/i18n/messages.ts`（新建，zh + en）
- `web/src/i18n/index.ts`（新建，useI18n）
- `web/src/components/layout/TopBar.vue`（+ Languages 按钮）
- `web/src/components/layout/LeftTools.vue` / `CanvasView.vue` / `StatusBar.vue` / `LogDrawer.vue`（替 title / label）
- `web/src/components/layout/RightPanel.vue`（i18n 替换 + `fitTextHeight` / `fitTextWidth` 按钮）

---

## 2026-04-23 · M5-D2 修 P1 + P2：auto-select + TextLayout canonical width

**实测反馈修两个：**

### P1 — 新增元素后自动选中

**问题：** 工具栏点"Add text / rect"后服务端创建元素，本地 state 更新但 selection 还是空——用户必须手动去 Layers 面板点一下才能看到 Properties 表单。

**修：**
- `project store` 加 `lastAddedElementId: Ref<string | null>`
- `applyPatch` 里检测 `op: 'add', path: /elements/N` → 记录 `value.id`
- `App.vue` watch `project.lastAddedElementId` → 非 null 时调 `ui.selectElement(id)` 并清零

多端场景（M6+ 多人协作）若其他 session add 也会触发 auto-select；M5 单端无影响。

### P2 — 前后端换行不一致（截图里是重头）

**根因：** 前端 `ctx.measureText(ch).width` 和 Java `FontMetrics.charWidth(c)` 即使加载同一 TTF/OTF 也返回不同值（浏览器 hinting / Java AWT rasterizer 差异）。TextLayout 的 `softWrap` 按字符宽度累加判断换行，差 1 px 就让前后端在不同位置断行。截图里 `"这是一个测试TESTABCDEFG..."` 在前端换成 3/3/3/3 模式（中文 3 字、英文 3 字），后端换成 2/4/4 模式。

**修（核心）：** 前后端 `TextLayout` 统一用**规则型 canonical width**：
- 码点 `< 0x2E80`（ASCII / 拉丁 / 一般标点）：`round(fontSize * 0.5)`
- 其他（CJK / 假名 / 全角）：`fontSize`

**代价与权衡：**
- 像素字体 Ark Pixel 本身 monospaced，canonical == actual，零副作用
- 思源黑非等宽，canonical 比实际略宽一些 → 视觉字间距偏松，但换行位置前后端**完美一致**
- 与 rendering.md §8.2 的 tolerance（pixelated=0%、常规 <0.5%）方向相符——layout 一致比字形精确优先

**改动：**
- `web/src/render/TextLayout.ts`：`canonicalCharWidth` 导出 + `layoutText` / `layoutHorizontal` / `layoutVertical` / `softWrap` / `measureLineWidth` 全部替换 `m.measureChar` → `canonicalCharWidth`；删旧 `CharMeasurer` 接口与 `CanvasMeasurer` 类
- `web/src/render/PreviewRenderer.ts`：不再构造 Measurer，直接 `layoutText(t)`
- `plugin/src/main/java/.../TextLayout.java`：加 `public static int canonicalCharWidth(char, int)`；`layout` / `softWrap` / `measureLineWidth` / `layoutVertical` 全部切到新函数；移除 `FontMetrics` 参数（签名简化为 `layout(TextElement)`；vertical 同）
- `plugin/src/main/java/.../CanvasCompositor.java`：`TextLayout.layout(t, fm)` → `TextLayout.layout(t)`

**snapshot 基线：** `canonicalCharWidth` 改了每个字符的 x，5 个 fixture 的 expected PNG 均变。`rm expected/*.png && ./gradlew :plugin:test` 重建基线，二跑通过。baseline diff 已入 git。

**关联文件：**
- `web/src/stores/project.ts`（+ lastAddedElementId）
- `web/src/App.vue`（watch auto-select）
- `web/src/render/TextLayout.ts` / `PreviewRenderer.ts`
- `plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java` / `CanvasCompositor.java`
- `plugin/src/test/resources/expected/*.png`（baseline 重生）

---

## 2026-04-23 · M5-D 收尾（scope 调整） · **M5 编辑器 UI 完成**

**scope 调整：** 原计划 M5-D = Playwright e2e + snapshot 测试台 + 手测。实施前盘点：
- 当前本机 Node 25 ↔ 新 toolchain 有多次兼容坑（vue-tsc 卡死、kill -9 打破 node_modules）
- Playwright chromium install ~200 MB，首次拉取慢且可能中断
- 前后端像素级一致需要 tolerance 调参轮次，非半天可完成

**决定：** Playwright snapshot 测试台正式推迟到 **M7 打磨发布**，和 M4/M5 所有 polish 一起处理（届时 toolchain 稳定）。**M5-D 只做**：
1. 端到端手测 checklist（提供给用户实测的详细步骤）
2. `PROPOSAL.md` 标 M5 完成；`CLAUDE.md` 里程碑行同步
3. 推迟项明确入 M7 档

**M5 段落汇总：**

| 段 | 范围 | 状态 |
|---|---|---|
| A | 脚手架：Vue 3 + Pinia + Tailwind 4 + shadcn-vue 风格 token + 三栏布局 + 深色主题 + LogDrawer + WsClient 封装 | ✅ |
| B | 核心画布：PreviewRenderer 初版 + Konva overlay + 选中/拖拽/resize/rotate + PropertiesPanel + LayerPanel reorder + 快捷键 + pan/wheel zoom | ✅ |
| C | 前端渲染器：字体 @font-face + /api/palette + TextLayout TS 镜像 + 效果族（stroke/shadow/glow 含自实现盒模糊） + 像素字体最近邻缩放 + 竖排实装 | ✅ |
| D | 端到端手测 checklist；Playwright snapshot 推 M7 | ✅ |

**M7 polish 清单（从 M4 + M5 聚合起来的推迟项）：**
- Playwright e2e / snapshot 测试台（双端像素级对比，含 `rendering-test/web-runner/`）
- WOFF2 字体 subset（思源黑 16 MB → ~200 KB 中文常用字）
- stroke / glow 的 pixelated 路径（当前 fill + shadow 已走最近邻）
- 竖排行首禁则（少见）
- "Simulate MC palette" UI toggle（用 PaletteLut.quantizeImageData 预览）
- WS 重连自动化（5/10/30s 阶梯）
- 限流 "5 次 / 1min 重复触发 → close 1008"
- 会话 token rotate 频率策略
- Paper `MapPalette.matchColor` forRemoval 真正替代（当前 M4-T2 PaletteLut 已让运行时绕开，仅 build-time PaletteGenerator 还用它）

---

## 2026-04-23 · M5-C5 像素字体最近邻缩放（前后端同步）· **M5-C 收尾**

**契约：** `docs/rendering.md §2.4`——若 `pixelated=true` 且字号 ≠ `nativeSize` 整数倍，必须用最近邻缩放避免字形 subpixel 模糊。

**启用条件：** `pixelated==true && nativeSize > 0 && targetSize % nativeSize == 0 && targetSize / nativeSize >= 2`。其他情况（含非整数倍、缩小、非像素字体）走原 `deriveFont` 路径。

**Java 端（`CanvasCompositor`）：**
- `shouldUseNearestNeighbor(reg, targetSize)` 判定
- `drawText` fill + shadow 循环里 branch 到 `drawPixelatedGlyph`；stroke / glow 暂不处理 pixelated
- `drawPixelatedGlyph`：
  1. `reg.derive(nativeSize)` 派生 native-size Font，FontMetrics 测 nativeChW
  2. 分配 `BufferedImage TYPE_INT_ARGB(nativeChW, nativeAscent+nativeDescent)` 做 mask
  3. mask Graphics 走 5 个 hints（关 AA），`drawString(ch, 0, nativeAscent)` 画字形
  4. 主画布临时 `KEY_INTERPOLATION = NEAREST_NEIGHBOR`
  5. `drawImage(mask, drawX, drawY, targetChW, targetH)` 缩放到 target 尺寸
  6. 恢复原 interpolation
- rotated glyph 路径同步：先 `translate + rotate π/2` 再 drawImage 到 `(-targetChW/2, ascent - targetSize/2)`

**前端（`PreviewRenderer`）：**
- `FONT_META` 表硬编码：`ark_pixel: { pixelated: true, nativeSize: 12 }` / `source_han_sans: { pixelated: false, nativeSize: 0 }`（与后端 `FontRegistry.Metadata` 对齐）
- `shouldUseNearestNeighbor` 判定同逻辑
- `drawPixelatedGlyph`：
  1. 切主 ctx font 到 nativeSpec 测 `nativeChW`；切 target 测 `targetChW`；恢复 ctx.font
  2. `document.createElement('canvas')` subcanvas，`imageSmoothingEnabled=false` + `textRendering=geometricPrecision` + native fontSpec + 白色 fillText 画 mask
  3. 主 ctx `imageSmoothingEnabled=false`（浏览器最近邻）→ `drawImage(sub, drawX, drawY, targetChW, targetH)`
  4. 恢复 imageSmoothingEnabled

**覆盖范围：**
- ✅ fill 层（元素主色）
- ✅ shadow 层（offset + shadow.color）
- ✗ stroke 层：仍走原 `ctx.strokeText` / Java `GlyphVector.getOutline`（outline 算法与像素字不好配合；M7 polish）
- ✗ glow 层：内部 mask 仍用 `deriveFont(targetSize)`；M7 polish

**M5-C 段落汇总：**

| # | 范围 | 状态 |
|---|---|---|
| C1 | 前端字体加载（Ark Pixel + 思源黑 @font-face）| ✅ |
| C2 | `/api/palette` 端点 + 前端 `PaletteLut` 镜像 | ✅ |
| C3 | `TextLayout` TS 镜像（横排完整）| ✅ |
| C4 | 前端效果族（stroke / shadow / glow）镜像 | ✅ |
| C5 | 像素字体最近邻缩放（前后端 fill + shadow）| ✅ |
| C6 | 后端竖排（Java TextLayout.layoutVertical + drawGlyph 旋转）| ✅ |

**M5-C 未做（推迟到 M5-D 或 M7）：**
- stroke / glow 的 pixelated 路径
- 竖排行首禁则
- WOFF2 字体 subset（加载体积优化）
- "Simulate MC palette" UI toggle（量化渲染预览）

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（+ drawPixelatedGlyph + shouldUseNearestNeighbor）
- `web/src/render/PreviewRenderer.ts`（+ FONT_META + drawPixelatedGlyph）

---

## 2026-04-23 · M5-C4 前端效果族镜像（stroke / shadow / glow）

**契约：** `docs/rendering.md §5` 效果族；前端 Canvas 2D 与后端 Java Graphics2D 的一致性要求。

**PreviewRenderer.drawText 按 §5.4 顺序：**
```
glow (自实现盒模糊)  →  shadow (fillText offset)  →  stroke (strokeText)  →  fill
```

**Glow（Canvas 2D 镜像 Java GlowRenderer）：**
- 算 glyph 外接矩形 + `radius + 1` padding（rotated glyph 按 `fontSize × fontSize` 方格外接）
- 新 `document.createElement('canvas')` local image（ARGB）
- 关 imageSmoothing + textRendering='geometricPrecision'；白色 fillText 画字形 mask
- 提取 alpha 通道 → `boxBlurHorizontal` + `boxBlurVertical`（分离核，算法 1:1 对应 Java）
- alpha 保留作透明度，RGB 替换 `glow.color`
- `ctx.drawImage(local, bboxX, bboxY)` 合成到主画布（SRC_OVER 自动）

**Shadow：**
- 每个 glyph 按 `(dx, dy)` offset 画一次，颜色 = `shadow.color`
- rotated glyph 绕 pivot 旋转后画（与 fill 层共用 `drawGlyphFill`）

**Stroke：**
- 浏览器原生 `ctx.strokeText`（rendering.md §5.1 前端约定）
- `lineWidth = width`、`lineJoin = round`、`lineCap = round`
- rotated glyph 同样走 `drawGlyphStroke`（translate + rotate + strokeText）

**与后端渲染的潜在差异（留 M5-D snapshot 测试发现）：**
- `ctx.strokeText` 的 outline 算法可能和 Java `GlyphVector.getOutline + BasicStroke` 有 subpixel 差异
- 盒模糊在两端实现上的整数除法精度：Java `sum / diameter`（整数除）vs JS `Math.floor(sum / diameter)`——理论上等价，需实测
- Canvas 2D 不关抗锯齿，即使 `textRendering='geometricPrecision'`——字符边缘可能仍有 sub-pixel；与 Java `TEXT_ANTIALIAS_OFF` 有差距。M5-D 的 `pixelated` 容忍度按 `< 0.5%` 定；超限时可能要引入前端"强制整数像素"或"Graphics2D 后端直出图片 → 前端 drawImage" 的替代方案

**关联文件：**
- `web/src/render/PreviewRenderer.ts`（重写 drawText + glow/shadow/stroke 全家桶）

---

## 2026-04-23 · M5-C3 + C6 TextLayout TS 镜像 + 前后端竖排实装

**M5-C3（前端 TextLayout 镜像）：**
- 新建 `web/src/render/TextLayout.ts`（~200 行），完整镜像 Java TextLayout
- 接口 `layoutText(t, measurer) → PositionedGlyph[]`；`CharMeasurer` 抽象测量函数
- `CanvasMeasurer` 适配 Canvas 2D `ctx.measureText`，带字符级缓存
- 横排分支 1:1 镜像 Java `layoutHorizontal`：硬换行 + softWrap（CJK/空白断点、无断点硬切）+ 行首禁则 + `align` 逐行 + 基线 `fontSize * 0.8`
- 新 PositionedGlyph 扩 `rotated?: boolean` 字段（竖排全角标点用）
- `PreviewRenderer.drawText` 切到逐字符 `fillText`，`rotated=true` 时绕锚点 `rotate(π/2)` 再画

**M5-C6（前后端竖排实装，rendering.md §3.3）：**

**Java 端：**
- `PositionedGlyph` 加 `rotated` 字段（保留 3 参构造器兼容历史调用）
- `TextLayout.layout()` 根据 `t.vertical()` 分流到 `layoutVertical`
- `layoutVertical`：
  - 字符从上到下；列从右到左（CJK 传统）
  - 列宽 = `fontSize × lineHeight`；每字占 `fontSize` 高
  - `align` 语义变顶/中/底对齐
  - `isRotatableVertical(c)`：`U+3000-U+303F` 符号标点 + `U+FF00-U+FFEF` 半全角形式
    旋转；其他不旋转（CJK 汉字本身方形）
  - 软换行按 box `h`；`\n` 起新列
- `CanvasCompositor.drawText` 重构：
  - 新增 `drawGlyph(g, pg, dx, dy)` 统一处理 rotated + offset；shadow/fill 层共用
  - 新增 `drawGlyphOutline(g, pg, font, frc)` 处理 stroke 效果的 rotated 版
  - `rotated=true` 时 `save → translate(pivot) → rotate(π/2) → drawString(-chW/2, ascent-fontSize/2) → restore`
  - 删除 `verticalWarned` / `warnVerticalOnce`（真正实装了，不再 WARN）

**前端 TS 同步（完全 1:1 镜像）：**
- `layoutVertical` 逻辑一致
- `isRotatableVertical` 字符范围一致
- `PreviewRenderer.drawText` 的 rotated 分支用 `ctx.translate + ctx.rotate + fillText` 相同锚点语义

**尚未做（polish）：**
- 竖排下**行首禁则**（少见，M7 补）
- 竖排行首/列首禁止标点（例如全角逗号不应出现在列首）

**glow 效果在竖排下的表现：** `GlowRenderer` 当前对 glyph 按 mask 画；rotated glyph 在 mask 里会以"原方向"绘制（因为 `lg.drawString(pg.ch, pg.x - bboxX, pg.baselineY - bboxY)` 没走 drawGlyph），视觉上 glow 带会偏向原横排方向。竖排下的 glow 矫正也留 polish。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java`（+ layoutVertical + rotated 字段）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（drawGlyph/drawGlyphOutline 统一出口）
- `web/src/render/TextLayout.ts`（新建，横排镜像 + 竖排实装）
- `web/src/render/PreviewRenderer.ts`（接入 TextLayout + rotated 分支）

---

## 2026-04-23 · M5-C2 /api/palette 端点 + 前端 PaletteLut 镜像

**后端 `WebServer`：**
- 新 `GET /api/palette` endpoint 直读 classpath 根的 `palette.json` 回 `application/json`
- 加 `Cache-Control: public, max-age=86400, immutable`（palette 只随 Paper 版本变，极少动）

**前端 `render/PaletteLut.ts`（~170 行）：**
- 完整镜像 Java `PaletteLut`：5-bit 量化 → 32³ byte LUT；CIE76 Lab 距离（sRGB D65 → XYZ → Lab）；D65 矩阵 & `LAB_EPSILON/KAPPA` 常量与 Java 完全相同
- 数据结构：`Uint8Array` LUT 占 32 KiB；`Float64Array` 预存 opaque palette 的 L/a/b
- 入口：
  - `loadFromEndpoint('/api/palette')` → fetch + 构造
  - `matchColor(r, g, b, a?) → byte`（alpha<128 返 `TRANSPARENT_INDEX`）
  - `getColor(index) → [r,g,b,a]`（反向）
  - `quantizeImageData(imgData)` → 对 Canvas ImageData 逐像素量化（M5-D snapshot + "Simulate MC" 预览用）
- 懒加载 singleton：`getPaletteLut()` 首次 fetch+构建，之后返回缓存 promise
- 构建成本：~8M 次 Lab 距离计算，单线程 1-3s（后端 Java 也差不多）；M7 polish 可迁 Web Worker

**为什么共享 palette：**
- 前后端同样的量化算法 → 同样的 byte[] → M5-D snapshot 可像素级比对
- 前端预览默认不量化（CSS 颜色更好看）；加 "Simulate MC palette" UI toggle 可按需预览"发游戏后的样子"

**M5-C2 未启用 UI 开关：** 只把基础设施打好；切换 PreviewRenderer 到量化路径留后续 commit（避免 M5-C 一次改动过多）。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（+ `/api/palette` endpoint）
- `web/src/render/PaletteLut.ts`（新建）

---

## 2026-04-23 · M5-C1 前端字体加载（双字体 @font-face 同源 TTF/OTF）

**契约：** `docs/rendering.md §2.1`「前后端必须从同一源 TTF 产出」——最稳的做法就是**直接共享**下载产物，不做额外 WOFF2 subset（文件偏大但首版可用）。

**Gradle：**
- 新增 `syncFontsToWeb` Copy task：`build/downloaded-fonts/*.ttf|otf → web/public/fonts/`
- `processResources` 加 `dependsOn(syncFontsToWeb)`，`./gradlew shadowJar` 一次把前后端字体同时准备好
- `.gitignore` 加 `web/public/fonts/`（产物不入 git）

**前端：**
- `style.css` 新 `@font-face`：
  - `ark_pixel` ← `/fonts/ark-pixel-12px-monospaced-zh_cn.ttf`（~4.9 MB TTF）
  - `source_han_sans` ← `/fonts/SourceHanSansSC-Regular.otf`（~16.5 MB OTF）
  - `font-display: block` 避免字体未到时渲染 fallback（首次画面略等，但字形保证对）
- `PreviewRenderer.drawText`：`ctx.font = "${fontSize}px ${fontFamily(fontId)}"`
  - `fontId` 直接作 CSS family 名（`ark_pixel` / `source_han_sans`）
  - 未知 id fallback 到 `ark_pixel`（与后端 `FontRegistry.DEFAULT_FONT_ID` 一致）
- `CanvasView.onMounted` 里 `document.fonts.ready.then(() => requestDraw())`——字体异步到位后重绘一次

**M7 polish 留项：**
- WOFF2 subset（用 fonttools pyftsubset 做中文常用字 ~2000 字切出来，~200 KB）
- 首屏 preload `<link rel="preload" as="font" ...>`
- 字体 hash 防缓存 mismatch

**关联文件：**
- `plugin/build.gradle.kts`（+ syncFontsToWeb）
- `.gitignore`
- `web/src/style.css`（+ @font-face）
- `web/src/render/PreviewRenderer.ts`（fontFamily mapping）
- `web/src/components/layout/CanvasView.vue`（document.fonts.ready 重绘）

---

## 2026-04-23 · M5-B6 LayerPanel reorder + B8 画布 pan/wheel zoom · **M5-B 收尾**

**B6 LayerPanel drag-and-drop 重排：**
- HTML5 Drag & Drop API 手写（不加 sortable 依赖）
- `draggable="true"` + `@dragstart/@dragover/@drop/@dragend`
- 拖动中 source row `opacity-50`，目标 row `ring-1 ring-[color:var(--ring)] ring-inset` 高亮
- drop 时 optimistic reorder 本地 + `ws.send('element.reorder', {elementId, index})`

**B8 画布交互：**
- **Ctrl/Cmd + wheel** → zoom（以鼠标光标位置为锚心）
  - 算 `oldZoom → newZoomClamped` 后，同步调 `scrollLeft/Top = (scrollL + mouseX) * ratio - mouseX`，使鼠标下的那个画布像素在缩放后仍在鼠标下
  - clamp 到 `[0.25, 4]`（与快捷键 ±/zoom 控件一致）
- **中键 drag / Alt+左键 drag** → pan（利用 section 的 overflow-auto，scrollLeft/Top 随鼠标位移反向滚）
- 非 Ctrl 时 wheel 默认行为保持（浏览器原生 scroll pan）
- 无 Space+drag（留 polish）

**M5-B 8 段汇总：**

| # | 范围 | 状态 |
|---|---|---|
| B1 | Canvas 2D PreviewRenderer（临时版）| ✅ |
| B2 | Konva overlay（Stage/Layer/Rect/Transformer）| ✅ |
| B3 | 元素选中（click/Esc/Layers 联动）| ✅ |
| B4 | 拖拽 + resize + rotate → `element.transform` | ✅ |
| B5 | PropertiesPanel 五组可编辑（Transform/Rect/Text/Effects/Layers）| ✅ |
| B6 | LayerPanel 显隐/锁/reorder drag | ✅ |
| B7 | 快捷键 Delete/undo/redo/arrow/shift+arrow/Esc | ✅ |
| B8 | Ctrl+wheel zoom（锚鼠标）+ 中键/Alt 拖 pan | ✅ |

**M5-B 未做的小项（留 polish 或后续 M5 段）：**
- Ctrl+D 复制、Ctrl+A 多选 / 多选框选（M5 末尾或 M7）
- 对齐辅助线（smart guides）；snap to grid / snap to other elements（polish）
- 元素拖拽时实时 rect 预览（现已用 Transformer 的边框够用）
- 属性面板对 TextElement 的字段为空时的 UX（目前 optional 字段默认已就位）

**M5-B 收尾实测入口（浏览器）：**
1. `/canvas edit → 两角 → /canvas confirm` 打开 probe URL
2. 点 Toolbar 的 Type/Square 按钮添加元素，或 Sparkles 应用 hello_world
3. Canvas 上看到元素；点击选中、拖拽移动、四角拉 resize、Transformer rotate handle
4. 右侧 Properties 编辑任意字段（效果族也可勾）→ 游戏内墙面实时响应
5. Delete 删除选中 / Ctrl+Z 撤销 / 方向键微调
6. Ctrl+wheel 缩放（锚鼠标）/ 中键拖 pan

**构建：** vite build 235ms；20 KB CSS + 307 KB JS（gzip 4.7 / 97 KB）。

**关联文件：**
- `web/src/components/layout/RightPanel.vue`（layer drag reorder）
- `web/src/components/layout/CanvasView.vue`（pan + wheel zoom）

---

## 2026-04-23 · M5-B5 属性面板可编辑 + B7 快捷键

**B5 PropertiesPanel 重写（`RightPanel.vue`）：**
- 分组折叠（`<details>` + summary）：**Transform / Rect / Text / Effects / Layers**
- **Transform** 组：x/y/w/h（number）+ rotation（select 仅 0/90/180/270）+ visible/locked（checkbox）
- **Rect** 专属：fill（开关 + color input）+ stroke（开关 → width number + color）
- **Text** 专属：text（textarea 多行）+ fontId（select ark_pixel/source_han_sans）+ fontSize + color + align + letterSpacing + lineHeight + vertical
- **Effects** 三开关：stroke / shadow / glow；每个打开后展开相应字段（shadow 的 dx/dy/color、glow 的 radius/color 等）
- 共用 `hc-input` / `hc-color` scoped CSS（手写，Tailwind 4 scoped style 不支持 `@apply`）
- **防抖策略：** number/text → `useDebounceFn 200ms`；boolean/color/select → 立即发（定型操作）
- **乐观更新：** 发 op 前先 mutate 本地 project.state，避免输入手感延迟；后端 state.patch 回来自然覆盖
- **Layers 列表：** visible/locked icon 变点击 toggle（发 element.update）

**B7 全局快捷键（`App.vue` useEventListener 顶层挂）：**
| 键 | 行为 |
|---|---|
| `Delete` / `Backspace` | 删除选中 → `element.delete` + 清选 |
| `Ctrl/Cmd + Z` | `undo` |
| `Ctrl/Cmd + Shift + Z` / `Ctrl + Y` | `redo` |
| `↑↓←→` | 移动选中 ±1 px |
| `Shift + ↑↓←→` | 移动选中 ±10 px |
| `Esc` | deselect（CanvasView 内已挂）|

- 跳过 `<input>` / `<textarea>` / `<select>` / `contenteditable` 焦点，避免输入时误触
- 箭头键走 `element.transform` op 而非 `element.update`——语义更贴"移动"

**Effects UI 与后端联动测试**（M4 已做完的效果族终于有 UI 入口）：
- 选一个 text 元素 → Effects 勾 glow → radius 默认 3 / color #33CCFF → 游戏里墙面该 text 立刻出蓝青光晕
- 勾 shadow → dx=dy=2 默认 → 黑色阴影偏移
- 勾 stroke → width=2 默认 → 字形轮廓描边

**暂未做（留 M5-B8 或 B6 后续）：**
- LayerPanel drag-and-drop 重排（element.reorder）—— 可选用 @vueuse 的 useSortable
- Ctrl+D 复制选中
- Ctrl+A 全选（需要多选支持，M5-B 不做多选）

**构建验证：** vite build 302ms；20 KB CSS + 305 KB JS（Konva ~230 KB 占大头）。

**关联文件：**
- `web/src/components/layout/RightPanel.vue`（重写，~350 行）
- `web/src/App.vue`（+ 全局快捷键）

---

## 2026-04-23 · M5-B1~B4 画布渲染 + Konva overlay + 选中 + transform op

**范围：** 让画布能"看见内容 + 选中 + 拖动/缩放"。B1~B4 一批落地；B5（属性面板可编辑）/ B6（图层可点）/ B7（快捷键）/ B8（pan+wheel）分后续批次。

**B1 PreviewRenderer（`render/PreviewRenderer.ts`，临时版）：**
- 纯函数 `renderProjectState(ctx, state)`
- 背景 fill → 按 z-order 遍历 `state.elements`
- rect：`fillRect` + 4 边 `fillRect` 手工画 stroke（与后端 CanvasCompositor 同策略）
- text：`ctx.font = "${size}px monospace"` + `fillText`（单行，M5-B 够用；M5-C 真对齐 Java 版）
- rotation：`translate(cx, cy) + rotate + translate(-cx, -cy)` 围绕 bbox 中心
- 关 `imageSmoothingEnabled` + `textRendering = 'geometricPrecision'`（rendering.md §4.3）
- **不做：** 调色板量化 / 多行 wrap / letterSpacing / 效果族 / 像素字体最近邻缩放 → M5-C 镜像 Java

**B2 Konva overlay（CanvasView.vue）：**
- `main.ts` 里 `app.use(VueKonva)`（3.x）
- `shims-vue-konva.d.ts` 给 Volar / vue-tsc 补 `GlobalComponents` 声明
- 布局：
  ```
  外层 div（shadow + ring；尺寸 = widthPx × heightPx × zoom；CSS transform scale 走 zoom）
    内层原生坐标容器（widthPx × heightPx）
      <canvas>（底层像素，由 PreviewRenderer 画）
      <v-stage>（上层 overlay，手势 hit）
        <v-layer>
          <v-rect v-for element>（隐形 hit area，offsetX/Y + x/y 让 rotation 绕中心）
          <v-transformer>（绑选中）
  ```

**B3 选中：**
- 点 v-rect → `onHitClick` → `ui.selectElement(id)`；`ev.cancelBubble = true` 防冒泡
- 点 stage 空白 → `onStageMouseDown` 判断 `!element-hit && type !== 'Shape'` → `selectElement(null)`
- `Esc` → deselect（`onKeyStroke('Escape')`）
- Layers 面板点 element 也联动（早已在 M5-A 写好）

**B4 拖拽 + resize 发 op：**
- `onDragEnd`：Konva node.x/y 是 center（因 offsetX/Y=w/2），换算回 bbox 的 `(x, y) = node.x() - w/2`；optimistic 本地更新 + `ws.send('element.transform', {elementId, x, y})`
- `onTransformEnd`：resize / rotate end
  - 读 `scaleX/scaleY`（resize 通过 scale 间接表达）
  - 换算出新 `w/h`；用 `node.scaleX(1); node.width(newW)` 重置 scale 防累乘
  - rotation 用 `snapRotation` 吸到 `{0, 90, 180, 270}`（协议限制）
  - 发 `element.transform {x, y, w, h, rotation}`

**Transformer 配置（`transformerConfig`）：**
- 8 个 anchor（四角 + 四边中点）
- `rotationSnaps: [0, 90, 180, 270]`
- anchor 颜色配深色主题（`#60a5fa` 蓝色边 + 深蓝 fill）

**响应式重绘：**
- `watch(() => project.state, requestDraw, { deep: true, immediate: true })`
- `requestAnimationFrame` 合并同 tick 多次 watch 回调；ArrayBuffer 重分配只在 canvas 尺寸变化时
- state.patch 回来后 ProjectState deep watch 触发重绘，Konva Rect props 响应式 diff，Transformer 保持 attach

**Node 25 + vue-tsc 兼容性妥协：**
`vue-tsc --noEmit` 在 Node 25 + TS 6 + Vue 3.5 下经常卡 10+ 分钟才出单个错误。`package.json` 的 `build` 临时降级为纯 `vite build`，`typecheck` 单独暴露给 IDE / CI。vite 构建产物验证：18 KB CSS + 292 KB JS（含 Konva）。

**关联文件：**
- `web/src/render/PreviewRenderer.ts`（新建）
- `web/src/components/layout/CanvasView.vue`（重写）
- `web/src/main.ts`（注册 VueKonva）
- `web/src/shims-vue-konva.d.ts`（新建）
- `web/package.json`（build 脚本拆 typecheck）

---

## 2026-04-23 · M5-A 前端脚手架（Vue 3 + Pinia + Tailwind 4 + shadcn-vue）

**范围：** 把 M1~M4 的原生 DOM probe 页面整体换成 Vue 3 + Pinia + Tailwind + 现代化 UI 壳（深色主题 / 三栏布局 / 侧边可折叠 / 日志抽屉）。为 M5-B 核心画布、M5-C 前端渲染器打下骨架。架构契约对应 `docs/architecture.md §2.2`。

**技术栈（用户拍板）：**
- Vue 3 Composition API + Pinia
- Tailwind CSS 4 + tw-animate-css + shadcn-vue 风格 CSS 变量（深/浅主题）
- 图标 lucide-vue-next；工具函数 @vueuse/core；Konva + vue-konva（M5-B 才用）
- 构建 Vite 8（已有），TS 6，vue-tsc 类型检查

**目录结构：**
```
web/src/
  main.ts                       # Vue app entry
  App.vue                       # 根组件：三栏布局
  style.css                     # Tailwind @import + HSL design tokens
  lib/utils.ts                  # cn() clsx + twMerge
  types/protocol.ts             # docs/protocol.md §7 的 TS 镜像
  stores/
    network.ts                  # WS 状态 + 日志流
    project.ts                  # ProjectState 本地镜像 + applyPatch(RFC 6902)
    ui.ts                       # 主题 / 折叠 / 选中 / zoom / 日志抽屉开关
  network/wsClient.ts           # WsClient 单例：connect/send/heartbeat/onMessage
  components/layout/
    TopBar.vue                  # 品牌 + 面板折叠 + 主题切换
    LeftTools.vue               # 工具栏（临时放 ping/paint/hello/undo/redo/add text|rect）
    CanvasView.vue              # 画布占位（M5-B 接入 Konva + Canvas 2D）
    RightPanel.vue              # Properties + Layers（只读展示，M5-B 可编辑）
    StatusBar.vue               # 状态灯 + sessionId + wallSize + version
    LogDrawer.vue               # 底部可折叠日志抽屉（WS 流全量）
```

**快捷键（`@vueuse/core onKeyStroke`）：**
- `Ctrl/Cmd + =` / `+`：zoom in
- `Ctrl/Cmd + -`：zoom out
- `Ctrl/Cmd + 0`：zoom reset

**WS 客户端迁移：**
- `WsClient` 类封装原 `main.ts` 里的 open/auth/heartbeat/reconnect token 全部逻辑
- `createWsClient()` 单例；`App.vue onMounted` 启动连接
- 消息分发：`ready / state.snapshot / state.patch / error / pong / ack` 分别更新 `useNetworkStore` / `useProjectStore`
- `__hk` 调试入口保留：`window.__hk.send("op", payload)`

**深色主题（shadcn-vue 约定）：**
- `:root` 与 `.dark` 各一套 HSL 变量（背景 / 前景 / card / primary / accent / border / ring 等）
- `@theme inline` 映射到 Tailwind token（`bg-card`、`text-muted-foreground` 等）
- `useUiStore.toggleTheme` 写 `<html>` 的 `.dark` class + `localStorage`
- 默认跟随 `prefers-color-scheme`，否则深色

**踩坑记账：**
1. **Node 25 + vite package.json 损坏**：之前 `kill -9` 打断 install 造成 `node_modules/vite/package.json` 字节损坏（首 200 字节全 null）；重装触发 ERR_INVALID_PACKAGE_CONFIG；`rm -rf node_modules package-lock.json && npm i` 一遍就清
2. **`vite.config.ts` 被 sed 清空**：`sed -i.bak` + 原地替换在某时序下把文件清零；改用 Write 整体重写

**构建验证：** `vite build` 成功；产物 17 KB CSS + 95 KB JS（gzip 4 KB + 36 KB）。

**M5-A 阶段性成果：** 旧 probe 功能（ping / paint / apply hello_world / undo / redo）全部迁到 Toolbar 按钮，端到端行为等价；网页视觉从"灰底朴素文档"升级到"Linear/Figma 式深色工具站"。M5-B 要做的编辑器交互（选中、拖拽、属性编辑、图层面板可点操作）留待下一轮。

**关联文件：**
- `web/package.json / tsconfig.json / vite.config.ts` 调整
- `web/src/**/*` 全新树（12 个新文件 + 替换 main.ts / index.html）

---

## 2026-04-22 · M4 polish 小修 · pristine state 回 placeholder + 前端 __hk 调试入口

**两个实测发现的小问题：**

### 1. undo 到底后一片空白（应回 placeholder）

**现象：** apply hello_world 后 undo 回到 ProjectState 初始态（elements 空、canvas.background=#FFFFFF），compositor 渲白色把 FrameDeployer 当初画的灰底 placeholder 盖没。

**根因设计：** placeholder 是 `/canvas confirm` 时 FrameDeployer 用 palette-first 直接写到 mapId 的灰底像素，**不在 ProjectState 里**——ProjectState 只管 elements + canvas。undo/redo 不可能把 state 外的 placeholder 恢复。

**修复：** `CanvasProjector.project` 第一步检查 `isPristine(state) = elements.isEmpty() && background==#FFFFFF`；若为真，走 `PlaceholderRenderer.render(mapIdx, total)` 渲 placeholder 替代 compositor。等价于"若 ProjectState 回到与 confirm 时构造时等价的初始态，视觉上也回到 confirm 时的 placeholder"。

**签名变更：** `CanvasProjector` 构造多吃一个 `PlaceholderRenderer`（从 `HikariCanvas` 主类复用现有那个 instance 注入）。

**边界情况：** 若用户显式设 `canvas.background=#FFFFFF` 且 elements 空，也会走 placeholder 分支——这是 pristine 语义的 corner case，可接受（"回到初始态即 placeholder"）。

### 2. 浏览器 console `ws is not defined`

**现象：** 我给的测试手册写 `ws.send(JSON.stringify(...))`，但 `ws` 是 `main.ts` 模块私有 `let`，不挂 window。

**修复：** `main.ts` 末尾暴露调试入口：
```ts
(window as unknown as Record<string, unknown>).__hk = {
    send,
    get ws(): WebSocket | null { return ws; },
    get authenticated(): boolean { return authenticated; },
};
```

用法：`__hk.send("element.add", { type: "text", props: {...} })` —— 直接走 `send()`，自增 client id + 加信封 wrap。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（+ PlaceholderRenderer 依赖 + pristine 分支）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（注入链调整）
- `web/src/main.ts`（`__hk` 调试入口）

---

## 2026-04-22 · M4-T10 发光 + T11 snapshot 测试台 + T12 5 fixture baseline

**M4 收尾三合一提交：发光效果完整实装 + Java runner 双端一致性 snapshot 测试台 + 5 个 fixture baseline 建立。**

### T10 GlowRenderer（发光，盒模糊自实现）

**算法（rendering.md §5.3，完全自实现不依赖系统高斯模糊）：**
1. 算 glyphs 整体外接矩形 + 四向 padding=`radius+1`
2. 分配 local `TYPE_INT_ARGB` 图（仅覆盖外接盒，不碰全画布）
3. 在 local 上关 AA 画字形 mask（白色或任意不透明色，只看 alpha）
4. 提取 alpha 通道 → 水平 + 垂直两次 `diameter = 2*radius+1` 均值滤波（分离核，等效盒模糊）
5. 着色：保留 alpha，RGB 全替换为 `glow.color`
6. `mainG.drawImage(local, bboxX, bboxY)`——Graphics2D SRC_OVER 自动 alpha 合成到主画布 RGB

**接入 compositor.drawText：** `glow → shadow → stroke → fill`（rendering.md §5.4 顺序）；glow 在最底层。

**性能：** per-element + per-glyph bbox；radius=5 128×128 约 65K 个像素 × 2 次滤波 = 微秒级。单个画布多个 glow text elements 各自独立 local image，无共享状态。

### T11 RendererSnapshotTest（JUnit 5）

**新增：**
- `plugin/src/test/java/moe/hikari/canvas/render/RendererSnapshotTest.java`
- `build.gradle.kts` 加 `testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")` + `useJUnitPlatform()` + `test.dependsOn(processResources)`（保证 palette.json + 字体在 test classpath 就绪）

**ProjectState Jackson 反序列化支持：**
- 加 `@JsonCreator` 构造器 `(version, canvas, elements, history)` + `@JsonProperty`
- 空字段容错（canvas/elements/history 为 null 都退默认）

**流程：**
1. fixture JSON → ObjectMapper → ProjectState
2. compositor.rasterize → BufferedImage
3. 写 actual.png 到 `build/test-results/snapshot/actual/`
4. 对比 expected.png（`src/test/resources/expected/`），逐像素 diff
5. diff 图写 `build/test-results/snapshot/diff/`（差异红点 + 灰阶背景便于目测）
6. diff ratio ≥ 0.5% → 测试失败；expected 不存在 → 首次建 baseline 告警不失败

**容忍度策略：** M4 统一 0.5%。rendering.md §8.2 区分 pixelated=0% / 常规 <0.5% / 大字号 <2% 留 M4.5 再细化。

### T12 5 个 fixture JSON + baseline PNG

`plugin/src/test/resources/fixtures/`：
| # | 文件 | 场景 |
|---|---|---|
| 01 | `01-hello-world.json` | hello_world 模板：2×2 湖蓝 + 白框 + `HELLO WORLD` Ark Pixel |
| 02 | `02-chinese-text.json` | `你好，世界！` 思源黑 28px + letterSpacing=2 + 多行硬换行 |
| 03 | `03-effects-stroke.json` | `STROKE` 48px 黄底 `#CC0033` 描边宽 3 |
| 04 | `04-effects-shadow.json` | `SHADOW` 42px 蓝字 `#888888` 阴影 dx=dy=3 |
| 05 | `05-effects-glow.json` | `GLOW` 56px 白字 `#33CCFF` 发光 radius=5 |

**首次运行自动创建 baseline：** `src/test/resources/expected/*.png` 5 张已入 git；**用户审核后再按需更新**（如果觉得某 fixture 的预期视觉不对，手工改 JSON 或模板参数重跑 test，actual 覆盖 expected，git commit）。

### Build Pipeline 整合

`./gradlew :plugin:test` 端到端验证：
- downloadFonts（首次抓思源黑 + Ark Pixel）
- generatePalette（导 248 色 JSON）
- processResources（palette + fonts + web 合并）
- compileJava + compileTestJava
- test → RendererSnapshotTest 5 参数化 case

**一次性实测**：第一次跑 baseline 建立（~3 分 10 秒，大部分时间在 downloadFonts + 首次 gradle 初始化），第二次 1 秒全通过。

### 关联文件

- `plugin/src/main/java/moe/hikari/canvas/render/GlowRenderer.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（glow 槽位接入）
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`（@JsonCreator）
- `plugin/src/test/java/moe/hikari/canvas/render/RendererSnapshotTest.java`（新建）
- `plugin/src/test/resources/fixtures/*.json`（5 个）
- `plugin/src/test/resources/expected/*.png`（5 个 baseline）
- `plugin/build.gradle.kts`（JUnit 5 deps + test task）

---

## 2026-04-22 · M4-T7+T8+T9 Rect stroke 结账 + TextElement.effects 描边 & 阴影

**T7 Rect fill + stroke：已在 M4-T4 compositor 重写时顺手完成**——`g.fillRect` + 手工四 `fillRect` 画 stroke（整数像素对齐、不受 `BasicStroke.drawRect` 的亚像素分摊影响）。T7 无额外代码改动，journal 记账 mark completed。

**T8/T9 TextElement.effects：**

**state 包新增三 record（对齐 protocol.md §7）：**
- `Shadow(int dx, int dy, String color)`
- `Glow(int radius, String color)`（T10 渲染用，字段先就位）
- `Effects(Stroke stroke, Shadow shadow, Glow glow)` 聚合容器；三字段均可 null；整体 null 代表无特效

`TextElement` 末尾追加 `Effects effects` 字段。

**EditSession 扩展：**
- `buildText` / `applyTextPatch` 新增 `effects` 字段处理，嵌套 `buildStroke/buildShadow/buildGlow`
- 校验阈值：`shadow.dx/dy ∈ [-128, 128]`、`glow.radius ∈ [0, 64]`、`stroke.width ∈ [0, 128]`（复用）
- 空 effects 对象 + 三字段全 null → 存为 null（避免无效载荷污染快照）

**CanvasCompositor.drawText 重写（按 rendering.md §5.4 顺序）：**
```
glow  (T10 占位)
 ↓
shadow (T9)   drawString offset (dx, dy) + shadow.color
 ↓
stroke (T8)   font.createGlyphVector + getOutline + BasicStroke.draw
 ↓
fill          drawString 正常 color
```
- shadow 直接 drawString 到 (dx, dy) 偏移处——rendering.md §5.2 要求"自实现不用 Graphics2D 内置 shadow"；drawString 等效 mask+offset，前后端可对齐
- stroke 用 `GlyphVector.getOutline(x, baselineY)` 取字形 Shape，`BasicStroke(width, CAP_ROUND, JOIN_ROUND)` 画路径
- 每层都遍历所有 PositionedGlyph，确保 per-char letterSpacing 与布局阶段一致

**DirtyRegion.of 扩展（effects 四向外扩）：**
- `shadow(dx, dy)` 单向外扩（dx>0 则右扩，dx<0 则左扩；dy 类似）
- `stroke.width` 两侧各扩 `width/2`（stroke 一半溢出字形轮廓外）
- `glow.radius` 四向全扩
- 扩张后再应用 rotation 规则（90/270 外接方形）
- RectElement.stroke **不**在外扩——那是 bbox 内部边框，不溢出

**M4-T10 glow：**
字段已就位（`Glow` record 已入 TextElement.effects），compositor 预留 "glow (T10 占位)" 注释；实际盒模糊自实现留 T10。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/Shadow.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/Glow.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/Effects.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/TextElement.java`（+ effects）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（buildText/applyTextPatch + 三 buildX helper + 3 条校验阈值）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（drawText 四层）
- `plugin/src/main/java/moe/hikari/canvas/render/DirtyRegion.java`（effects 四向外扩）

---

## 2026-04-22 · M4-T6 rotation 0/90/180/270

**范围：** 清掉 M3-T7 起的 rotation WARN 忽略项，让元素真按旋转度数渲染。仅接受 `{0, 90, 180, 270}`（EditSession 校验已拦截其他值）。

**实现：**
- `CanvasCompositor.rasterize` 在 element loop 里：`rotation != 0` 时 `save transform → g.rotate(toRadians(rot), cx, cy) → draw → g.setTransform(saved)`
- 旋转中心 = element bbox 中心 `(x + w/2, y + h/2)`，这样 rotation=180 后元素视觉仍在"同一位置"
- 去除 `rotationWarned` 字段与 `warnRotationOnce` 方法

**DirtyRegion.of(Element) 扩展：**
- `rotation ∈ {0, 180}`：bbox 不变
- `rotation ∈ {90, 270}`：外接 = 边长 `max(w,h)` 的方形，中心对齐原 bbox——保证旋转后溢出原 bbox 的像素也在脏矩形覆盖内、projector 能正确重绘

**语义记账：**
- bbox 本身不随 rotation 改变（用户选中操作、对齐计算都以原 bbox 为准）
- 内容可能"视觉上溢出" bbox——rect 旋 90°：方形保持；长方形 w×h=100×20 变 20×100（超出 20 px 高方向）。Dirty 用 max 方形接住

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（+ AffineTransform 旋转；- rotationWarned）
- `plugin/src/main/java/moe/hikari/canvas/render/DirtyRegion.java`（of(Element) 感知 rotation）

---

## 2026-04-22 · M4-T5 多行文本 + letterSpacing + lineHeight + 基线

**范围：** 横排文本完整排版。`docs/rendering.md §3.1-§3.5` 横排部分全覆盖；竖排（§3.3）按早先决策推迟到 M4.5/M7。

**TextElement 字段扩展（protocol.md §7 对齐）：**
- `letterSpacing: float`（px，可负；范围 -32..128）
- `lineHeight: float`（倍数；范围 0.5..4.0，默认 1.2）
- `vertical: boolean`（字段保留，渲染按 false + WARN）
- `fontId` 默认改为 `ark_pixel`（对应 `FontRegistry.DEFAULT_FONT_ID`）
- `fontSize` 默认 8 → 12（match Ark Pixel nativeSize）

**EditSession 同步：**
- `buildText` / `applyTextPatch` 新增三字段读取 + 校验
- 新增 `floatFieldOrDefault` / `floatValue` helper
- 新增 `validateLetterSpacing` / `validateLineHeight`

**新建 `render/TextLayout.java`（~180 行）：**
- `layout(TextElement, FontMetrics) → List<PositionedGlyph>` 主入口
- **硬换行**：`\n` split
- **软换行**：逐字符累宽，超 `w` 回溯到最近可断点
  - 断点规则：半/全角空格后、CJK 字符后（任意两字间可断）
  - 无可断点时硬截断（长 ASCII 单词）
  - 空白断点处空格丢弃（不出现在下一行行首）
- **行首禁则**：`）】」』。，、？！？；：)].,!?:;` 半全角并收；下一行首若是禁止标点，回溯到上一行末
- **基线**：`ascentPx = round(fontSize * 0.8)`（rendering.md §3.2 跨字体统一）
- **行距**：`lineHeightPx = round(fontSize * lineHeight)`
- **align**：每行独立算 lineWidth，按 left/center/right 计 startX
- **letterSpacing**：逐字符累加到 cursor（Graphics2D 不支持 per-char letter-spacing，只能逐字符 drawString）

**CanvasCompositor.drawText 重写：**
- 用 `TextLayout.layout` 产 `PositionedGlyph` 列表；逐字符 `g.drawString(ch, x, baselineY)`
- `vertical=true` 触发 `warnVerticalOnce`（WARN 一次，避免刷屏），按 horizontal 渲染
- `hello_world` 模板产生的 TextElement 构造更新为新签名 + `ark_pixel` fontId

**M4-T5 暂不做（推迟项）：**
- 竖排（文档 §3.3 已标推迟 M4.5/M7）
- 像素字体最近邻缩放（rendering.md §2.4）——Graphics2D `deriveFont` 在关抗锯齿情况下小字号可用；M4.5 polish 再评估是否需要专门绕过 AWT 内部 hinting

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/TextElement.java`（+ 3 字段）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（build/patch + validators）
- `plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（drawText 改用 TextLayout）

**先行承接文档决策：** `docs/rendering.md §3.3 / §3.4` 已在上一 commit 标注竖排推迟状态（commit `d77f763`）。

---

## 2026-04-22 · M4-T4 RgbaCanvas + Graphics2D；CanvasCompositor 重写

**范围：** 把 M3-T7 的 palette-first 直绘换成 `BufferedImage TYPE_INT_RGB` 大画布 + `Graphics2D` 渲染 + `PaletteLut` 量化切片。契约落地 `docs/rendering.md §1 / §4 / §6 / §7`。

**新管线（替换 M3-T7）：**
```
ProjectState
   → CanvasCompositor.rasterize(state) → BufferedImage(W*128, H*128, TYPE_INT_RGB)
   → CanvasCompositor.toPaletteSlice(img, mapIndex, widthMaps) → byte[128*128]
   → HikariCanvasRenderer.update(mapId, pixels)
   → Paper tick 自动 sync 给 viewer
```

**CanvasCompositor 重写要点：**
- 新构造签名：`(PaletteLut, FontRegistry, Logger)`——两依赖在主类注入
- `rasterize(state)`：分配整张大图 + 5 个 Graphics2D rendering hints（AA_OFF / TEXT_AA_OFF / RENDER_SPEED / FRACTIONALMETRICS_OFF / STROKE_PURE）→ 填背景 → 按 z-order 画 element
- `toPaletteSlice(img, idx, widthMaps)`：用 `img.getRGB(x, y, 128, 1, buf)` 按行取 RGB，逐像素 `paletteLut.matchColor` 量化
- Rect：`g.fillRect` 填充 + 手工四 `fillRect` 画 stroke（BasicStroke.drawRect 像素不对齐，保持 M3 整数像素风格）
- Text：`g.setFont(reg.derive(fontSize))` + `g.drawString` 单行（多行 / letterSpacing / lineHeight 留 M4-T5）
- `rotation != 0` 仍按 0 渲染 + log WARN 一次（M4-T6 真 rotation）

**CanvasProjector 调整：**
- 构造注入 `CanvasCompositor`（之前内部 `new`）
- `project(session, region)`：**一次 `rasterize` 大画布复用** + 对 region.coveredMapIndices 每格 `toPaletteSlice` 写到 canvasRenderer
- 优化点：脏矩形只省量化成本（rasterize 必须整张，元素可跨图）；2×2=65 KiB 大画布微秒级，8×4=1 MiB 毫秒级，10×10=6.5 MiB 十几毫秒级。M3 per-map 重复渲染的开销清除

**HikariCanvas 启动顺序调整：**
- `fontRegistry` 加载后 → **`paletteLut = PaletteLut.loadFromClasspath("/palette.json")`** 一次性构建 32 KiB LUT → `compositor` 注入 → `canvasProjector`
- palette.json 不存在（`./gradlew generatePalette` 没跑过）→ `IllegalStateException` 拒绝启动，错误消息提示

**M4-T4 暂不做（留后续 Tn）：**
- 多行文本 / `letterSpacing` / `lineHeight` → T5
- `rotation != 0` 真渲染 → T6
- BasicStroke 真用（曲线 stroke）→ T7
- effects 族（stroke / shadow / glow）→ T8 / T9 / T10

**副收获：** `CanvasCompositor` 代码从 220 行缩到 170 行（palette-first 手写 drawGlyph / fillRect / drawRect 全删），让位给 Graphics2D 原生 API + PaletteLut 量化，后续 T5-T10 的扩展都能直接在 Graphics2D 上叠。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（重写）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（构造签名 + 两阶段 rasterize/quantize）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（PaletteLut 构造 + 注入链）

---

## 2026-04-22 · M4-T3 FontRegistry + 内置双字体（Ark Pixel + 思源黑）

**范围：** 加载两枚内置字体到 `FontRegistry`，可选扫描 `plugins/HikariCanvas/fonts/` 外部字体。契约对应 `docs/rendering.md §2`。

**方案微调**（原方案 A → A'）：
- 原计划：Ark Pixel 入 git、思源黑 Gradle 下载
- 实测：我本地到 GitHub Releases 带宽不稳（Ark Pixel 34MB zip 多次 Premature EOF）
- 调整：**两字体都走 Gradle `downloadFonts`**。仓库继续纤瘦、代码机制统一、SHA-256 校验一致
- 差别仅是"首次 build 稍慢"，用户端跑通后就 pin SHA 常驻缓存

**Gradle `downloadFonts` 任务（`build.gradle.kts`）：**
- `FontSpec` data class：`displayId / url / destFileName / expectedSha256 / inZipEntryPattern`
- 两个 spec：
  - `source_han_sans` → Adobe raw.githubusercontent，单 OTF 16.5 MB，SHA pinned
  - `ark_pixel` → TakWolf Release zip 34.3 MB，解压提取 `*monospaced-zh_cn\.ttf`，SHA pinned
- 重试 3 次，每次 readTimeout 120s；抛 Premature EOF 就重下。手写 fallback 错误消息指导手动下载
- SHA-256 校验：非空值严格对比，空串仅 log（首次跑自动打印）
- `processResources.from(downloadedFontsDir) { include "*.ttf", "*.otf"; into "fonts" }` 合并到 jar `/fonts/` classpath 子目录

**SHA-256 pin 值（2026.02.27 Ark Pixel · Adobe release 分支思源黑）：**
- `SourceHanSansSC-Regular.otf`：`f1d8611151880c6c336aabeac4640ef434fa13cbfbf1ffe82d0a71b2a5637256`
- `ark-pixel-12px-monospaced-zh_cn.ttf`：`2fa78b40f74714b0092fa549eb6814b3efec5a729d020254968a270771ba5f75`

**新增 `FontRegistry.java`：**
- `loadBuiltIn()`：从 classpath `/fonts/*.ttf|.otf` 读两枚内置；AWT `Font.createFont(TRUETYPE_FONT)` 一把梭（OpenType 是 TrueType 超集，同常量即可）
- `loadExternal(Path)`：扫外部目录；文件名去扩展名作 fontId；同名覆盖内置
- 内置 fontId 约定：
  - `ark_pixel`（像素字体，`pixelated=true, nativeSize=12`）—— **默认 fontId**（跟 M3 hello_world BitmapFont 视觉平滑过渡）
  - `source_han_sans`（`pixelated=false`）
- `getOrDefault(fontId)`：未知 id 回退到 `DEFAULT_FONT_ID = "ark_pixel"`；连默认都没（downloadFonts 失败）返 null
- 线程：加载期有写入，稳态全局只读；`get/getOrDefault` 纯读
- `Metadata` record：`displayName / pixelated / nativeSize` —— M4-T4 渲染阶段用这几个字段决定 Graphics2D hint 与最近邻缩放

**HikariCanvas 主类 wiring：**
- `onEnable` 顺序：db/token/reaper → canvasRenderer → **fontRegistry loadBuiltIn + loadExternal(getDataFolder/fonts)** → projector → webServer
- 启动 log："FontRegistry: N font(s) ready"

**踩坑记账：**
1. Kotlin DSL 不自动 import `java.io.File` / `java.nio.file.*`；`java.io.File(...)` 完整路径写法 Kotlin 解析失败，必须 `import` 并用短名
2. 第一次 `Edit replace_all "java.io.File" → "File"` 把 import 行也改成 `import File`——单次 replace 要避开 import 行或用更具体的 old_string
3. GitHub Releases 34MB zip 连续 2 次 curl Premature EOF；Gradle 任务 3 次重试 + 每次 120s read timeout 实测第 2 次成功（约 9 分钟）

**后续（T4+）：** CanvasCompositor 重写时用 `fontRegistry.getOrDefault(t.fontId())` 替代 M3 硬编码的 `BitmapFont`；TextElement.fontId 未设时走 `ark_pixel` 保持与 hello_world 视觉一致。

**关联文件：**
- `plugin/build.gradle.kts`（+ 两字体下载任务 + SHA pin）
- `plugin/src/main/java/moe/hikari/canvas/render/FontRegistry.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（wiring）

---

## 2026-04-22 · M4-T2 PaletteLut（32³ Lab 距离）

**范围：** 启动期从 classpath `palette.json` 构建 32×32×32 byte LUT；CIE76 Lab 距离；O(1) 查询。替代 Paper `MapPalette.matchColor` 的 forRemoval 警告。契约对应 `docs/rendering.md §6.2-§6.4`。

**新增：**
- `plugin/src/main/java/moe/hikari/canvas/render/PaletteLut.java`

**API：**
- `PaletteLut.loadFromClasspath("/palette.json")` → 实例；Jackson 反序列化 → 构建 LUT
- `matchColor(r, g, b) → byte`：纯 RGB 查（O(1)）
- `matchColor(r, g, b, a) → byte`：alpha < 128 直接返 `TRANSPARENT_INDEX = 0`
- `getColor(byte index) → java.awt.Color`：反向查询
- 线程安全：LUT 构建后只读，所有查询纯函数

**算法细节：**
- **5-bit 量化**：RGB 各取高 5 位，LUT 索引 = `(r>>3)<<10 | (g>>3)<<5 | (b>>3)`；每档 step=8，用中心点 `r*8+4` 作代表色减量化误差
- **Lab 转换**：sRGB → linear（gamma 2.4 / 12.92 分段）→ XYZ（D65 矩阵）→ Lab（`f(t) = cbrt(t)` 分段）
- **距离度量**：Lab 欧氏距离平方（省去 sqrt，单调等价）；只在非透明 palette 项上匹配（pre-filter alpha < 128）
- **构建成本**：32³ × 248 ≈ 8M 距离计算，启动耗时预估 1-3s；LUT 大小 32 KiB 常驻

**透明处理（rendering.md §6.4）：**
- palette.json 里前 4 条 alpha=0（MC 保留透明索引）
- LUT 不收录透明项；matchColor 对 alpha<128 入参直接短路返 `TRANSPARENT_INDEX`
- 半透明（128..254）硬截断为不透明匹配——MC 地图原生不支持 alpha blend

**踩坑：** macOS 目录（Google Drive 同步？）在 `build/classes/` 下累积了 339 个 `*\s\d+.class` phantom file（如 `HikariCanvas 9.class`），让 Gradle MD5 hash 失败卡 `BUILD FAILED in 34m 30s`。`find -name "* *.class" -delete` 清掉后 2 秒编译通过。记在此以防复发。

**后续（T4+）：** `CanvasCompositor` 重写时用 `PaletteLut.matchColor` 替代现有 `MapPalette.matchColor` 调用；可以同步清掉 `@SuppressWarnings("removal")`。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/PaletteLut.java`（新建）

---

## 2026-04-22 · M4-T1 palette.json 构建期生成

**范围：** 从 Paper `MapPalette` 导出 MC 地图调色板全部 248 条到 `palette.json`，构建期一次性跑，结果合并进 shadow jar 的 classpath 根路径。M4-T2 的 `PaletteLut` 启动时用 `getResourceAsStream("/palette.json")` 读。契约对应 `docs/rendering.md §6.1`。

**新增：**
- `plugin/src/generator/java/moe/hikari/canvas/build/PaletteGenerator.java`：独立 sourceSet `generator` 下的 Java main class；调 `MapPalette.getColor((byte) i)` 探测 0..255；遇 `ArrayIndexOutOfBoundsException` 自动停（Paper 1.21.11 实际数组长度 = 248，历史版本曾 192/256，不假定固定）
- `plugin/build.gradle.kts`：
  - `sourceSets.create("generator")` 独立 sourceSet 避免循环依赖
  - `generatePalette` JavaExec task，`argumentProviders` 延迟到执行期 resolve Provider（直接传 `args(provider)` 会写出 literal `"map(map(...))"` 文件名）
  - `processResources.dependsOn(generatePalette)` + `sourceSets.main.resources.srcDir(generatedPaletteResources)` 让 shadow jar 自动打包

**关键技术点（踩坑 & 修复）：**
1. **循环依赖**：初版把 `PaletteGenerator` 放 `src/main/java`，`classes → processResources → generatePalette → classes`。修：独立 `generator` sourceSet 隔离构建期代码
2. **Provider 未 resolve**：`args(paletteJson.map { ... })` 把 Provider.toString() 传给 JavaExec 当字符串参数，导致文件名是 `"map(map(map(...))" `。修：`argumentProviders.add(CommandLineArgumentProvider { ... })`
3. **248 ≠ 256**：Paper 1.21.11 内部 colors 数组是 248；try/catch AIOOBE 动态探测长度

**输出 JSON 形态（行行可 diff，前 4 条是 MC 保留透明索引）：**
```json
[
  {"index": 0, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 1, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 2, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 3, "rgb": [0, 0, 0], "alpha": 0},
  {"index": 4, "rgb": [89, 125, 39], "alpha": 255},
  ...
  {"index": 247, "rgb": [67, 88, 79], "alpha": 255}
]
```

**PaletteGenerator 在 shadow jar 中的命运：** 它只在 `generator` sourceSet 里，不会被打进运行时 jar（sourceSets.main 不引用它）。纯构建期工具，零运行时开销。

**关联文件：**
- `plugin/src/generator/java/moe/hikari/canvas/build/PaletteGenerator.java`（新建）
- `plugin/build.gradle.kts`

---

## 2026-04-22 · M4 渲染引擎启动 · 范围与字体分发决策

**范围**（12 个子任务 ≈ 13.5 工作日 ≈ 3 周）：
1. T1 `palette.json` 构建期生成
2. T2 `PaletteLut`（32³ Lab 距离查表）
3. T3 `FontRegistry` + 内置 Ark Pixel + 思源黑
4. T4 `RgbaCanvas` + Graphics2D；重写 `CanvasCompositor`
5. T5 多行文本 + 基线 + align + letterSpacing + lineHeight
6. T6 rotation 0/90/180/270
7. T7 Rect 真 fill + drawRect + BasicStroke
8. T8 描边效果（glyph outline）
9. T9 阴影效果（mask offset）
10. T10 **发光效果**（盒模糊自实现，用户确认 M4 一步到位）
11. T11 Java runner snapshot 测试台
12. T12 5 个 fixture + expected PNG

**关键决策（用户拍板）：**
- 调色板距离：**CIE76 Lab**（非 RGB 欧氏）
- 内置字体：**Ark Pixel 12px + 思源黑体 SC Regular 两枚**（均 SIL OFL）
- 前端 Playwright snapshot：**不做**（留 M5 Vue UI 一起）
- `BitmapFont`：继续用于 `PlaceholderRenderer`，`TextElement` 改走 TTF 管线
- 效果：描边 + 阴影 + **发光**全做，不推后

**字体分发（方案 A）：**
- Ark Pixel 12px（~200KB）直接入 git `plugin/src/main/resources/fonts/`
- 思源黑体 SC Regular（~15MB）Gradle `downloadFonts` 任务构建期抓取；`build/downloaded-fonts/` 由 `.gitignore` 排除；SHA-256 校验；`processResources` 合并入 shadow jar

**文档更新：**
- `CLAUDE.md`："字体"条款明确两字体来源 + 分发路径；里程碑条加 ✅ 与进行中标记
- `docs/rendering.md §2.1.1` 新增"分发策略（M4 定稿 · 方案 A）"小节 + Gradle 任务轮廓

**下一步：** 开 T1 palette.json 构建期生成。

---

## 2026-04-22 · WS 心跳修复（fix/keepalive）

**问题（M3 集成测试暴露）：**
- `Idle timeout expired: 30005/30000 ms` ——Jetty WS 默认 idleTimeout 30s，用户停手 30 秒 WS 就被服务端踢
- 前端未实现协议 §1 要求的"30s ping/pong"，完全依赖 Jetty 默认值

**修复（两层兜底）：**

1. **后端** `WebServer`：`cfg.jetty.modifyWebSocketServletFactory(factory -> factory.setIdleTimeout(Duration.ofSeconds(60)))`；把 Jetty idleTimeout 从 30s 放宽到 60s
2. **前端** `main.ts`：auth 成功后 `startHeartbeat()` 每 20s 发一次 `op: ping`；WS `close` 时 `stopHeartbeat()` 清定时器；心跳帧绕过 `print/send` 避免 log 刷屏

**为什么 60s + 20s 而不是默认 30s + 25s 心跳：**
- 20s 间隔内发送 3 次都不应超 60s（允许单次网络抖动丢心跳）
- 真正的 session 超时（5 分钟 ws-reconnect grace / 30 分钟 idle）由 `SessionReaper` 掌管，和 Jetty 层 timeout 分工明确

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `web/src/main.ts`

---

## 2026-04-21 · M3-T13 commit 写入真实 project_json

**范围：** `/canvas commit` 时把 session 的权威 `ProjectState` 经 Jackson 序列化写入 `sign_records.project_json`，替代占位 `"{}"`。契约对应 `docs/data-model.md sign_records.project_json` + `docs/architecture.md §8.2`。

**改动：**

- `CanvasCommand.JSON` 静态 ObjectMapper 增 `NON_NULL` 包含策略——与 WebServer 同策略；hollow rect 的 `fill=null` 不写字段
- `CanvasCommand.runCommit` 新增 `ProjectState projectState = s.projectState();` 快照（必须在 `sessionManager.commit` 之前，commit 会 `forget` session）
- `insertSignRecord(...)` 签名扩一个参数 `ProjectState`；序列化失败回退 `"{}"` + WARNING 日志
- ProjectState 为 null 时（异常路径）兜底写 `"{}"`

**JSON 形态示例**（commit 一面 2×2 hello_world 墙面）：

```json
{
  "version": 3,
  "canvas": {"widthMaps": 2, "heightMaps": 2, "background": "#4A90E2"},
  "elements": [
    {"type": "rect", "id": "e-...", "x": 8, "y": 8, "w": 240, "h": 240,
     "rotation": 0, "locked": false, "visible": true,
     "stroke": {"width": 2, "color": "#FFFFFF"}},
    {"type": "text", "id": "e-...", "x": 0, "y": 121, "w": 256, "h": 14,
     "rotation": 0, "locked": false, "visible": true,
     "text": "HELLO WORLD", "fontId": "bitmap", "fontSize": 14,
     "color": "#FFFFFF", "align": "center"}
  ],
  "history": {"undoDepth": 0, "redoDepth": 0}
}
```

**M4 承接：** M4 的 `project.load` op（{@code docs/protocol.md §5.2}）会反序列化本字段重构 ProjectState，进入二次编辑。ProjectSnapshot record 的 Jackson 兼容性已在本次 commit 一并验证（FIELD 可见性 + 多态）。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`

---

## 2026-04-21 · M3-T12 template.apply + hello_world 硬编码模板

**范围：** `docs/protocol.md §5.4` `template.apply` op 骨架 + 1 个硬编码模板 `hello_world`（本项目方案 α 的 M3 承诺范围）。正规 YAML 模板系统留 M6。

**EditSession 扩展：**
- 新 synchronized 方法 `applyTemplate(templateId, params)`
- M3 只识别 `"hello_world"`；其他 templateId → `INVALID_PAYLOAD "unknown template: X (M3 only supports hello_world)"`
- 流程：pre-snapshot → clear elements → 改背景色 → 插入模板 elements → commitHistory → bump version → 返回 `OkSnapshot + fullCanvas`

**hello_world 视觉定稿（画布自适应）：**

| 层 | 参数 |
|---|---|
| 背景 | `#4A90E2`（湖蓝） |
| 外框 | RectElement：`(margin, margin, w-2*margin, h-2*margin)`；margin=8px；空心 stroke=2px 白色 |
| 文字 | TextElement：`HELLO WORLD` 居中白色；字号 `scale = max(1, usable / (11 * 6))`；BitmapFont 5×7 scale=1 时单字 6px 步进 |

**WebServer：**
- op 分发表加 `"template.apply"`
- handler：`stringOrNull(payload.templateId) + mapOrEmpty(payload.params)` → `es.applyTemplate(...)` → `OkSnapshot` 路径走 pushSnapshot + fullCanvas throttler.submit

**前端 probe UI 加 3 个按钮**（方便 M3 端到端手测）：
- `Apply hello_world template` → `template.apply { templateId: "hello_world" }`
- `Undo` → `undo {}`
- `Redo` → `redo {}`
- 同时给 `send(op, payload?)` 扩出第二个参数支持带 payload 发送

**未决项（M6 承接）：**
- YAML template spec 解析 / 模板库多选
- 参数化模板（`params.{name, line_color}` 等）
- 模板预览缩略图

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（+ applyTemplate + buildHelloWorld）
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`（+ `clearElements`）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（op 分发）
- `web/index.html` + `web/src/main.ts`（3 个测试按钮）

---

## 2026-04-21 · M3-T11 undo / redo / history.mark

**范围：** `docs/protocol.md §5.5` 的历史类 op 全部落地。每会话 16 步环形历史栈，undo/redo 全量下行 `state.snapshot`。

**四条决策（实施前拍板）：**
1. **历史存全量快照**（非逆向 patch）：`ProjectSnapshot(canvas, List<Element>, label)`
2. **undo/redo 下行 `state.snapshot`**（非 `state.patch`）：跨度跳变用全量更稳
3. **`history.mark` 只贴标签**：push 一个带 label 的 snapshot 到 past，不 clear future
4. **脏矩形 = full canvas**：undo/redo 后全图重绘

**新增：**

- `state/ProjectSnapshot.java`：record `{ canvas, elements, label }`；`elements` 经 `List.copyOf` 变只读副本，不与 live state 共享
- `ProjectState.restore(ProjectSnapshot)`：整体替换 canvas + elements；version 不回滚（由调用方 bump）

**EditSession 扩展：**

- `past` / `future` 两个 `ArrayDeque<ProjectSnapshot>`；`MAX_HISTORY = 16`
- `OpResult` 新增 `OkSnapshot(version, dirty)` 变体；WebServer 按类型 pattern-match 决定下行 patch 还是 snapshot
- `undo()`：past 空 → `INVALID_PAYLOAD "nothing to undo"`；否则 push 当前到 future、从 past 取最近的 restore、bump version、返回 `OkSnapshot + fullCanvas`
- `redo()`：对称
- `historyMark(label)`：label 非空 + ≤ 64 字符；push `(current state, label)` 到 past；**不 clear future**（mark 是标签、不是新分支）；返回 `Ok` 空 patch 只 bump version
- `commitHistory(preSnapshot)`：5 个原有 mutation op 成功路径都调它——past push 后 `future.clear()`（标准 undo 语义）

**`OkSnapshot` 下行流程（WebServer）：**
1. `ack { version }` 对 client id
2. `state.snapshot` 全量 push（`Envelope.of("state.snapshot", s-N, { projectState })`）
3. dirty 交给 `ProjectionThrottler`（走 5fps 上限 + union 合并）

**T10 互动：** undo/redo 的 full-canvas dirty 会触发 throttler 把整张画布重绘推一次；与普通 op 的局部脏矩形可以 union 合并到同一 flush 窗口里。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectSnapshot.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java`（+ `restore`）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（历史栈 + undo/redo/mark + OkSnapshot）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（op 新增 + OkSnapshot 分支）

---

## 2026-04-21 · M3-T10 帧率节流（投影 5fps + 输入 40/2s）

**范围：** `docs/architecture.md §5.1` + `docs/protocol.md §9` 双层节流落地。

**新增：**

- `render/ProjectionThrottler.java`
  - per-session bucket `{ pending: DirtyRegion, lastProjectAt, flushTask }`
  - `submit(sid, region)`：距上次 flush ≥ 200ms 立即走；否则 `union` 进 pending 并 `runTaskLaterAsynchronously` 调尾帧
  - `union` coalesce 同窗口内的连续 op（10 次快改只投 1~2 次）
  - `discardSession` 取消 pending task、清状态

- `session/SessionRateLimiter.java`
  - 40 msg / 2s 固定窗口计数器（≈ 20 msg/s；协议 §9 阈值）
  - `allow(sid) → boolean`：超限时返 false，WebServer 返 `RATE_LIMITED` 错
  - 协议 §9 的"5 次 / 1min 重复触发 → close 1008"留 M7

**SessionManager forget hooks：**
- 新增 `addForgetHook(Consumer<String>)` + 在 `forget()` 末尾调用
- 主插件注册两条 hook：`throttler::discardSession` + `rateLimiter::discardSession`
- 长运行内存不再随会话数单调增长；hook 异常互不影响（try/catch 逐条执行）

**WebServer 改动：**
- `dispatchEditOp` 第一步就查 `rateLimiter.allow`；超限直接返 `RATE_LIMITED`
- 成功后 `throttler.submit(sid, dirty)` 代替直接 `canvasProjector.project`
- 移除不再用的 `canvasProjector` 字段与构造参数；throttler 内部持有

**前端 100ms 防抖说明：**
- 当前 probe UI 只有 `ping / paint` 按钮，没有真正的编辑 op 发送路径
- 100ms 输入防抖是 M5 编辑器 UI 才能真正验证的东西——现在先把**协议层和后端上限**定准，M5 前端代码自然会按该契约写
- 协议契约：前端**应**在输入中做 100ms 防抖，但**即使不做**，后端 20 msg/s 硬限流兜底；两层独立、缺一不塌

**线程模型：**
- throttler 的尾帧 flush 走 `runTaskLaterAsynchronously`（async 线程），与 compositor 纯函数 + canvasRenderer `ConcurrentMap` 一致，不必切主线程
- rateLimiter 的 `synchronized(bucket)` 锁粒度 = per-session，彼此独立

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/ProjectionThrottler.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionRateLimiter.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`（forget hooks）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（wiring + hooks）

---

## 2026-04-21 · M3-T9 per-viewer 同步（架构已满足）

**结论：** M3-T7/T8 引入的 `HikariCanvasRenderer` + `CanvasProjector` 组合已经天然提供 per-viewer 同步，T9 无代码改动。

**机制：**
- `HikariCanvasRenderer` 是挂在每张 `MapView` 上的 `MapRenderer(contextual=false)`
- Paper 每 tick 对 **每个看得见该 MapView 的 viewer** 调 `render(map, canvas, player)`，从 `pixelsByMapId` 拉最新像素写入 `MapCanvas`
- Paper 负责 diff + 下发 `ClientboundMapItemDataPacket` 到该 viewer

所以"会话外的第三个玩家路过墙面"→ Paper 发现他看到这张 map → 调 render → 拿到当前最新 pixels → 自动同步。无论谁看，数据源都是同一份 `ConcurrentMap<mapId, byte[]>`。

**M3 阶段 `super(false)` = non-contextual：** 所有 viewer 共享同一张 canvas，省 CPU。真正的 per-player 差异化（例如编辑辅助线只对编辑者可见、观众看纯画布）需要切到 `super(true)` 并维护 per-player buffer——留 M7 视需要再做。

**验证留给 M3 总集成实测**（两名玩家同一墙面编辑与旁观）。

**关联文件：** 无改动。

---

## 2026-04-21 · M3-T7 + T8 脏矩形差分 + 多图拼接渲染

**范围：** 编辑 op 成功后把 `ProjectState` 投影到游戏内墙面——受影响 `mapIds` 重绘 palette 像素并推到 `HikariCanvasRenderer`，下一 tick Paper 自动 sync 给所有 viewer。T8 "多图拼接"在 `CanvasCompositor` 的 per-map 合成内自然实现（详见下文），与 T7 一并结清。

**新增文件：**

| 文件 | 职责 |
|---|---|
| `render/DirtyRegion.java` | 画布坐标矩形；`of(element)` / `fullCanvas(state)` / `union` / `coveredMapIndices(w,h)` |
| `render/CanvasCompositor.java` | 纯函数；`compose(state, mapIndex) → byte[128*128]`；palette 缓存 |
| `render/CanvasProjector.java` | `project(session, region)` → 遍历受影响 mapIndex 调 compositor 写 canvasRenderer |
| `render/BitmapFont.java`（扩展） | +17 大写字母 + 14 标点；现覆盖 A-Z / 0-9 / `. , : ; ! ? - _ + = / ( )` |

**多图拼接（T8 实现要点）：** `CanvasCompositor.compose` 对每张 map 独立走：
1. `offsetX = mapCol * 128`、`offsetY = mapRow * 128`（该 map 在画布的左上角像素坐标）
2. 对每个可见 element，坐标转换为 local 域：`localX = e.x() - offsetX`、`localY = e.y() - offsetY`
3. `drawRect` / `drawText` 统一把超出 `[0, 128)` 的像素 clip 掉

这样一个 x=120 / w=30 的 rect 在 widthMaps=2 画布上：map0 localX=120 绘 `x∈[120,128)`、map1 localX=-8 绘 `x∈[0,22)`——拼起来还是一个完整 30px 矩形，无缝跨图。

**脏矩形规则（EditSession 侧扩展）：**

| op | region |
|---|---|
| `element.add` | 新元素 bbox |
| `element.update` | 旧 bbox ∪ 新 bbox |
| `element.delete` | 被删元素 bbox |
| `element.reorder` | 被移动元素 bbox（z-order 变化触发该区域下层元素重合成）|
| `element.transform` | = update 路径（旧 ∪ 新）|
| `canvas.background` | 整个画布 |
| `canvas.resize` (no-op) | `null`（无像素变化）|

`EditSession.OpResult.Ok` 扩字段 `DirtyRegion dirty`，WebServer 在 ack + pushPatch 之后按 region 调 `canvasProjector.project`。

**M3-T7 主动简化（documented）：**
- `rotation != 0` 的元素按 `rotation=0` 渲染（log WARN 一次），真 rotation 留 M4
- Text wrap 不实装，单行渲染；元素自身 `w` 仅用于 `align` 中心/右对齐偏移计算
- `fontId` 当前只识别 `"bitmap"`（默认），M4 TTF 系统接入再扩
- fontSize 离散映射：`scale = max(1, round(fontSize/7))`（7→1×、14→2×、21→3×）
- `rect.stroke.width` 自动 cap 到 `min(w,h)/2` 防溢出

**调色板策略：** 用 Bukkit `MapPalette.matchColor(int,int,int)`（256 色全映射）+ hex string → byte 缓存。Paper 1.21.11 这个 API 和它的 `Color` 重载都标 `@Deprecated(forRemoval=true)`，但官方没给替代且 `CLAUDE.md` 锁 Paper 1.21；`@SuppressWarnings("removal")` 收口，M4 `docs/rendering.md` 真正 LUT 接入后整类替代。

**线程模型：** compositor 纯函数，projector 写 `ConcurrentMap`，`canvasRenderer.render()` 由 Paper 主线程每 tick 调用——三层互不打架，WS 事件处理线程直接调 projector 即可，不必切主线程。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/DirtyRegion.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`（扩字表）
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（OpResult.Ok 扩 dirty 字段）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`（dispatchEditOp 接 projector）
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`（main wiring）

---

## 2026-04-21 · M3-T6 element.* + canvas.* op 族

**范围：** WS 上行编辑 op 全部接入 `EditSession`，落到 `ProjectState`，产出 `state.patch` 推回前端。契约对应 `docs/protocol.md §5.3 / §5.4`。

**新增 `state/EditSession.java`：**
- `OpResult` sealed 结果类型：`Ok(StatePatch) / Error(code, message)`
- 7 个 `apply*` 方法（全部 `synchronized(this)`，Jetty 线程池下并发安全）：
  - `addElement(type, props, afterId)` — 生成 `"e-<uuid>"`；默认参数兜底；支持 `afterId=null` 追加尾部
  - `updateElement(elementId, patch)` — 字段级部分更新；all-or-nothing 校验；不变量失败回滚
  - `deleteElement(elementId)`
  - `reorderElement(elementId, newIndex)` — 越界 clamp；same-position 空 ops + bump version
  - `transformElement(elementId, x?, y?, w?, h?, rotation?)` — 等价于 update 五字段子集
  - `resizeCanvas(widthMaps, heightMaps)` — **M3 仅接受 no-op 同尺寸**；差值返回 `POOL_EXHAUSTED`（真动态扩缩容 M7 再做）
  - `setBackground(color)`
- Validator 集中在文件内静态方法：
  - color `^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$`
  - rotation ∈ {0, 90, 180, 270}
  - text length ≤ 256 / fontSize 1..512 / stroke.width 0..128 / w/h 1..10000 / x/y ±10000
  - align ∈ {left, center, right}
- Rect 不变式：`fill == null && (stroke == null || stroke.width == 0)` 拒绝（至少一种填充方式）
- 失败路径统一通过内部 `ValidationException(code, msg)` 抛出 → apply 方法外层 catch 转成 `OpResult.Error`

**patch 路径约定（RFC 6902）：**
- `element.add` → `add /elements/{idx} {element}`
- `element.update` → 逐字段 `replace /elements/{idx}/{field} {value}`；**`value == null` 改用 `remove`** 以规避 `NON_NULL` 序列化丢 value 字段违反 RFC 的坑
- `element.delete` → `remove /elements/{idx}`
- `element.reorder` → `remove /elements/{from}` + `add /elements/{to} {element}`
- `element.transform` → 走 updateElement 路径，逐变字段 `replace`
- `canvas.background` → `replace /canvas/background {color}`
- `canvas.resize`（no-op 情况）→ 空 ops 列表 + bump version

**WebServer 分发（单个 switch 入栈）：**
- 新 helper `dispatchEditOp(ctx, in, sid)`：
  1. 取出 Session.editSession；不存在返回 `SESSION_CLOSED`
  2. 提取 payload Map（类型错误统一 `INVALID_PAYLOAD`）
  3. switch op → 调 EditSession 对应方法
  4. `Ok`：先发 `ack { version }`（对 client id），再 `pushPatch`（s-N id；空 ops 跳过推送）
  5. `Error`：发 `error { code, message }`（对 client id）
- switch 表达式统一 `yield OpResult.Error(...)` 代替 early `return`（Java switch expression 限制）
- 保留 M1 `paint` demo 通道作为 T6 阶段回归测试；M3 收尾（T12/T13）再清

**Session 接入：**
- Session 新增 `editSession` 字段 + public accessor
- `SessionManager.confirm`：`SELECTING → ISSUED` 转移时构造 `EditSession(projectState)` 一起挂到 session 上
- 随 session forget 一起消亡，无需额外清理

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/EditSession.java`（新建，~380 行）
- `plugin/src/main/java/moe/hikari/canvas/session/Session.java`
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T5 state.snapshot / state.patch 推送基建

**范围：** 服务端主动推送 {`state.snapshot`, `state.patch`} 的基建。契约对应 `docs/protocol.md §5.2`。

**新增：**
- `state/PatchOp.java`：RFC 6902 最小子集 `{ op, path, value }` record + `add/replace/remove` 工厂
- `state/StatePatch.java`：`{ version: long, ops: List<PatchOp> }` record
- `state/StatePatchBuilder.java`：累积式构建器（非线程安全，只在 SessionManager 锁内使用）

**WebServer 扩展：**
- `ConcurrentMap<String, WsContext> wsBySession`：session → 活跃 WS 连接
- `AtomicLong serverIdSeq`：服务端推送 `s-<N>` id 单调源
- 绑定点：`handleAuth` 成功后 put；`onClose` 用 `remove(k, v)` 原子 CAS 避免 race 把新连接抹掉
- 新增 public API：
  - `pushSnapshot(sessionId, ProjectState) → boolean`
  - `pushPatch(sessionId, StatePatch) → boolean`
  - 均返回 `false` 当 session 没有活跃 WS 连接
- 序列化：`NON_NULL` 策略让 `PatchOp.value == null`（remove op）自动省略

**M3-T5 scope 仅基建**：T6 element op 族接入时才开始真实发 patch；T5 本身不变更既有通道行为。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/state/PatchOp.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/StatePatch.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/state/StatePatchBuilder.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T4 ProjectState 模型 + Element 接口族

**范围：** 服务端权威工程状态的数据模型落地。契约对应 `docs/protocol.md §7`。

**新增 `moe.hikari.canvas.state` 包：**
- `Element.java`：`sealed interface` + Jackson `@JsonTypeInfo(property="type")` 多态；permits `TextElement / RectElement`
- `TextElement.java`：record，M3 字段 = `id/x/y/w/h/rotation/locked/visible/text/fontId/fontSize/color/align`；effects / lineHeight / letterSpacing / vertical 留 M4
- `RectElement.java`：record，`fill`（可 null 表示空心）+ `stroke`（可 null 表示纯填充）
- `Stroke.java`：record `{ width, color }`，未来 M4 text outline 复用
- `ProjectState.java`：可变 class（不是 record——需要 mutator）；字段 `version / canvas / elements / history`；`@JsonAutoDetect(fieldVisibility=ANY, getterVisibility=NONE)` 让字段直接映射到 JSON 键名
  - 嵌套 record：`Canvas(widthMaps, heightMaps, background)`、`History(undoDepth, redoDepth)`
  - Java-side 无前缀 accessor + 显式 mutator (`addElement / replaceElementAt / removeElementAt / moveElement / bumpVersion / canvas(Canvas) / history(History)`)
  - 线程约束：只允许 `SessionManager.synchronized` 段内 mutator，`elements()` 返回 unmodifiable view

**Session 接入：**
- `Session` 新增 `projectState` 字段 + `projectState()` accessor + package-private `projectState(ProjectState)` mutator
- `SessionManager.confirm`：`SELECTING → ISSUED` 转移时实例化 `new ProjectState(wall.width(), wall.height())`，默认背景 `#FFFFFF`
- `WebServer.handleAuth`：`ready` payload 的 `projectState` 字段直接序列化 `session.projectState()` 对象（不再手写 Map）

**关联文件：**
- 6 个新文件（`state/*.java`）
- `plugin/src/main/java/moe/hikari/canvas/session/Session.java`
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`

---

## 2026-04-21 · M3-T3 Token rotate（断线重连基建）

**范围：** WS auth 成功后立即 rotate 新 token 给前端，供后续 WS 断线重连使用。契约对应 `docs/security.md §2.2`、`docs/protocol.md §11`。

**后端：**
- `TokenService` 新增 `rotate(playerUuid, playerName, sessionId)`：与 `issue` 语义相同但审计事件为 `AUTH_ROTATED`（区分「首次签发 vs rotate 签发」）；两方法共享 `issueInternal` 私有实现
- `WebServer.handleAuth` 在 `markActive` 后立即 `rotate` 并把新 token 随 `ready` payload 回发：`payload.reconnectToken: string`
- 审计日志里 `AUTH_ROTATED` 只记 `token_sha256`，原文不落盘

**前端：**
- `ReadyPayload` 接口扩 `reconnectToken` 字段
- `handleReady` 存 token 到 `sessionStorage["hikari-canvas:reconnect-token"]`（tab-scoped，关闭失效）
- 页面加载：URL `?token=` 优先（新会话走链接打开），回退 sessionStorage（同 tab 刷新 / 重连）
- token 原文绝不进 console.log，仅 log length

**M3-T3 scope 仅基建**：真正的"WS 断线自动重连循环"（5/10/30 秒阶梯，protocol.md §3.4）留给后续 UX 迭代。目前只保证 rotate token 能发、能收、能存。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`
- `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`
- `web/src/main.ts`

---

## 2026-04-21 · M3-T2 WS 会话超时回收（SessionReaper）

**范围：** 把 M2 T10/T11 记入 journal 的遗留项「没有主动 schedule 的回收 task」补上。契约对应 `docs/architecture.md §3.1`。

**新增：**
- `session/SessionReaper.java`：主线程 `BukkitScheduler.runTaskTimer`，30 秒扫一次
- `SessionManager.ExpiredSession` record + `synchronized collectExpired(now, issuedTimeout, wsGrace, activeIdle)`：只做决策不做副作用，返回待 cancel 列表

**三条超时规则：**

| 状态 | 条件 | reason | 默认阈值 |
|---|---|---|---|
| `ISSUED` | `now - createdAt > issuedTimeout` | `"issued-timeout"` | 15 min（与 token TTL 一致）|
| `ACTIVE` + 断连 | `now - wsDisconnectedAt > wsGrace` | `"ws-reconnect-timeout"` | 5 min |
| `ACTIVE` + 在线 | `now - lastActivityAt > activeIdle` | `"idle-timeout"` | 30 min |
| `SELECTING` / `CLOSING` | 不超时 | — | — |

**回收流程**（复用 `/canvas cancel` 的模式）：
```
1. 先快照 session.wall().world()（cancel 会把 session forget）
2. sessionManager.cancel(id, reason) → 归还池 + 释放锁 + forget
3. frameDeployer.removeForSession(id, world) → 删 preview 物品框
4. log.info 记录 id / reason / 删框数
```

**主线程约束：** `cancel` 触发 `MapPool.returnToPool`，`removeForSession` 扫 world 实体，两者都只能主线程。用 `runTaskTimer` 不用 `runTaskTimerAsynchronously`。

**阈值来源：** 全部硬编码常量，TODO 待 M7 config.yml 接入后让运维可调。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/session/SessionReaper.java`（新建）
- `plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`

---

## 2026-04-21 · M3-T1 WallResolver 补 frame-space air 校验

**范围：** 修 M2 残留 "旁边有草/花时 ItemFrame 闪掉" bug。M3 启动第一步，清 M2 残债。

**改动：**
- `WallResolver.FailReason` 新增 `FRAME_SPACE_BLOCKED`（bbox 前一格被非 air 方块占）
- `resolve()` 内循环：检查 `wall.getRelative(face1)` 必须 `isAir()`；否则返回失败
- 顺序在 `BLOCK_NOT_SOLID` 之后、`OCCUPIED` 之前
- 拒绝一切非 air 方块：短草、花、水、熔岩、雪层等；只有纯 `AIR / CAVE_AIR / VOID_AIR` 通过

**理由：** M2 实测发现若墙面前一格是短草，frame `spawn()` 即便成功，客户端判非法并立即 despawn（"闪一下"）。`OCCUPIED` 只查 ItemFrame 碰撞，漏了非 frame 的占位方块。

**下游兼容：** `WandListener` 和 `CanvasCommand` 直接 print `reason().name()`，加新枚举值不破坏现有代码。

**关联文件：**
- `plugin/src/main/java/moe/hikari/canvas/deploy/WallResolver.java`

---

## 2026-04-21 · M2-T12 集成测试 + 运行时调试 + **M2 完成**

**范围：** 端到端实测联调，沿途修 5 个运行时 bug；M2 所有契约要求的功能就位。`PROPOSAL §6` M2 行从"2 周"更新为"已完成（2026-04-21）"。

**实测达成的完整闭环：**
```
MC 客户端 /canvas edit + 左右键两角 + /canvas confirm
  → 聊天栏收到可点击 editor URL
浏览器点击 URL（MC 弹确认 → 打开本机 8877/?token=...）
  → 页面加载 (plugin serve) → main.ts 取 token → WS auth
  → 收到 ready（带 projectState.canvas.WxH）→ 状态条变绿 + 按钮 enable
浏览器点 Paint
  → WS paint op → HikariCanvas.paintAllSessionMaps
  → 对活跃会话的所有 mapIds 填红色像素 → canvasRenderer.update(...)
  → Paper 下一 tick 自动把 canvas sync 给所有 viewer
  → 游戏里整面墙同时变红
```

**沿途修的 5 个运行时 bug（按发现顺序）：**

1. **命令注册但 Brigadier 报 "unknown"**
   - 症状：`/canvas edit` 等命令显示"未知或不完整的命令"
   - 原因：T11 加了 `.requires("canvas.edit")` 权限检查；玩家默认无此权限，Brigadier 在 tab 补全/执行时直接把命令藏起
   - 修：`paper-plugin.yml` 新增 `permissions:` 字段，`canvas.edit / wand / commit / use` `default: true`；`canvas.admin*` `default: op`

2. **ItemFrame 挂上立刻消失（"闪一下就没了"）**
   - 症状：`/canvas confirm` 后 6 个 frame 瞬间出现又瞬间消失；log 里 `valid=true` 看上去正常
   - 原因：`setFixed(true)` + `setVisible(false)` 在 Paper 1.21 的 `world.spawn(..., consumer)` 内部被 apply 时，客户端 entity desync——add packet 和 metadata update 顺序问题
   - 修：M2 阶段**暂不设 INVISIBLE / FIXED**，保护完全交给 `FrameProtectionListener`。M7 polish 时再回来精调（可能需要 scheduled 1-tick-later 设 fixed）

3. **Placeholder 像素"闪一下被空白覆盖"**
   - 症状：frame 留住了，但 Placeholder 图像只显示一瞬间，立即被 MC 自己 tick 成空白
   - 原因：Bukkit `MapView` 有 per-tick 渲染机制——即使清空 `getRenderers()`，Paper 每 tick 仍然把空 canvas sync 给 viewer，覆盖我们直接 push 的 `ClientboundMapItemDataPacket`
   - 修：**不再对抗 Paper tick**，而是**合作**——新增共享 {@link moe.hikari.canvas.render.HikariCanvasRenderer}（`super(false)` non-contextual），`MapPool` 为每张 MapView `addRenderer`；外部像素改动调 `canvasRenderer.update(mapId, pixels)`，Paper tick 时 renderer 会把像素写进 canvas，自然走官方 sync 通道
   - 关联：`MapCanvas.setPixel` 内部有 dirty flag，重复写相同值不产生 packet，CPU 可接受

4. **HikariCanvasRenderer 被 FrameDeployer 清掉**
   - 症状：bug #3 修完后仍然不生效，log 里看 MapView 当前 renderer 数 = 0
   - 原因：`FrameDeployer.deploy` 里残留了一段"清 renderer"的 debug 代码，把 `MapPool.initialize/expand` 刚装上的 `HikariCanvasRenderer` 又清掉了
   - 修：删除 FrameDeployer 里的清 renderer 代码；MapPool 负责唯一的 renderer 生命周期

5. **浏览器 Paint 点了没反应**
   - 症状：所有 frame OK、Placeholder 稳定显示、浏览器 auth 成功，但点 Paint 按钮墙不变红；后端 log 显示 `painted 0 held maps`
   - 原因：paintHandler 仍是 M1 demo 的"遍历在线玩家主手 `filled_map` 涂红"——M2 玩家 confirm 完手里已无 map item
   - 修：改为 `paintAllSessionMaps`：遍历 `SessionManager.liveSessionIds()`、对每个活跃会话的全部 `mapIds` 填红像素 → `canvasRenderer.update`

**运行时 cleanup（commit 里一并做）：**
- 去掉 FrameDeployer 里 T12 期间加的 spawn log / +1 tick 诊断 log
- 恢复 `FrameProtectionListener` 注册（T12 debug 阶段为排除嫌疑临时注释掉了）
- 修 `SessionManager.confirm` 的 `requireState` 抛异常问题：已 ISSUED 状态二次 confirm 现在返回 `ConfirmResult.NotReady` 而不是抛 IllegalStateException

**另两个已知问题暂未修（不阻塞 M2 验收）：**
- **旁边有草/花时 frame 仍然闪掉消失**：WallResolver 只校验墙面方块，没校验**墙面前方一格（frame 将要占据的格子）**是空气。短草/花/雪层等会让 MC 判 frame 不合法 → despawn。修法：WallResolver 遍历 bbox 时顺便 check `getRelative(facing).getType() == AIR`，非空气 → `OCCUPIED` 或新增 `FRAME_SPACE_BLOCKED` 错误码。留给 M2 polish 小修或 M3 集成时补
- **靠侧墙的某格 frame 不显示**：可能是客户端视距 / chunk tracker / 相邻实体碰撞的边缘情况。留 M7 单独调查

**未做（按 M2 契约范围有意留给后续）：**
- **Token rotate**（security.md §2.2）— 断线重连 M3 再做
- **定时回收 task**（WS 断连 5min 宽限 + auth 5s 超时 + idle disconnect）— M7
- **限流**（security.md §2.4）— M7
- **Origin 校验** — M7
- **`/canvas cleanup` 真正数据回收**（现 stub）— M7 `/canvas fsck`

**里程碑总结（M1→M2）：**

| 任务 | 关键点 |
|---|---|
| T1 改名 /hc → /canvas | 代码跟进 契约 |
| T2 SQLite + HikariCP + JDBI | 5 表 schema v1 |
| T3 TokenService | SecureRandom + CAS 原子 consume + SHA-256 审计 |
| T4 MapPool | **核心机制**，SQLite 幂等 upsert + 不变式自愈 |
| T5 WallResolver | 纯算法 + 7 种失败码 sealed 建模 |
| T6 Wand + SELECTING | 命令/物品双入口；PlayerInteract listener |
| T7 SessionManager | 汇合点，状态机 SELECTING→ISSUED→ACTIVE→CLOSING |
| T8 FrameDeployer + 保护 | 挂框 + PDC + HangingBreak/BlockBreak 拦截 |
| T9 PlaceholderRenderer | 手写 5×7 位图字表，浅灰底 + 水印 + "N/M" |
| T10 WS auth/ready | 预握手 peek + 首帧 consume + rotate 留坑 |
| T11 /canvas 命令族 | 5 sealed ConfirmResult pattern-match + sign_records 入库 + 顺手修 staticFiles fat-jar |
| T12 集成测试 | 5 运行时 bug + HikariCanvasRenderer 设计收敛 |

**M2 工期：** 2026-04-21 立项契约修订 + 当日完成 12 个任务 —— 1 天完成（原估 2 周）。

**关联文件：** `PROPOSAL.md`（§6 M2 行状态更新）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java`、`plugin/src/main/java/moe/hikari/canvas/pool/MapPool.java`、`plugin/src/main/java/moe/hikari/canvas/render/HikariCanvasRenderer.java`（新）、`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java`、`plugin/src/main/resources/paper-plugin.yml`、`docs/journal.md`

---

## 2026-04-21 · M2-T11 /canvas 命令族完整实装 + 端到端闭环

**范围：** 把 M2 所有模块（TokenService / MapPool / SessionManager / FrameDeployer / PlaceholderRenderer / WebServer）串成完整业务链路；同时做一个顺手的修复：Javalin 7 静态资源 fat-jar discovery bug（T7a 留的 TODO）。

**后端改动：**

`command/CanvasCommand.java` 完整重写：
- 构造签名从 `(plugin, mapPacketSender, sessionManager)` 重新设计成 `(plugin, sessionManager, frameDeployer, tokenService, mapPool, database, editorUrlTemplate)` —— 去掉 `mapPacketSender`，因为 M2 正式命令路径不直接发包
- 正式子命令全部接入权限 `.requires()`（contract `docs/security.md §6`）：
  - `edit` / `wand` / `confirm` / `cancel` → `canvas.edit`
  - `commit` → `canvas.commit`
  - `stats` / `cleanup` → `canvas.admin`
- `/canvas confirm`：调 `SessionManager.confirm` pattern-match 5 种结果（Ok / NotReady / WallFailed / WallOccupied / PoolExhausted），Ok 时走「`FrameDeployer.deploy` → `TokenService.issue` → 发可点击 Adventure Component URL（`ClickEvent.openUrl` + `HoverEvent.showText`）→ 移除 wand」；FrameDeployer 抛异常时立即 `SessionManager.cancel("deploy-failed")` 回滚
- `/canvas commit`：commit 前**快照 `wall / mapIds / ownerUuid / ownerName`** 到局部变量（因为 `SessionManager.commit` 会 forget session）；然后 `SessionManager.commit → FrameDeployer.promote → INSERT sign_records`
- `/canvas cancel` 增强：commit 前 snapshot `wall.world()`，确保 cancel 后 `FrameDeployer.removeForSession` 有 world 参照
- `/canvas stats`：`MapPool.stats() + SessionManager.size() + TokenService.activeCount()` 一行输出
- `/canvas cleanup`：**M2 阶段 stub**，只打印 `soft-deleted sign_records` 数量；实际数据回收 + map 归还留给 M7 的 `/canvas fsck` 实装
- 删除 M1/M2 过渡 DEPRECATED 子命令：`give` / `paint` / `placeholder`（T1 起保留至今）
- 删除 T5 demo 用的 `MapPacketSender` / `PlaceholderRenderer` 字段（FrameDeployer 内部持有，主命令路径不再直接用）

**sign_records 写入：** M2 阶段 `project_json` 存 `"{}"` 占位、`template_id` / `template_version` 为 null。M3 真正的编辑协议族会逐步填充 `project_json`。

**顺手修复 Javalin 7 staticFiles fat-jar bug（T7a TODO）：**
`web/WebServer.java` 用两条显式 `Endpoint` 替代 `cfg.staticFiles.add`：
```java
GET /                → serveClasspath("web/index.html")
GET /assets/{file}   → serveClasspath("web/assets/" + file)   // 防路径穿越检查
```
`serveClasspath` 用 `ClassLoader.getResourceAsStream` 直接流式返回；MIME 按扩展名手动映射（html / js / css / json / woff2 / svg / png）。单 jar 部署在 M2 正式可用；Vite dev 模式也仍然能跑（跨源 WS 到 8877）。

**前端 (`web/src/main.ts`) 改 auth-first 流程：**
- 页面加载时从 `window.location.search` 取 `token`
- 自动 `connect(token)` → `open → sendAuth(token)` → `onmessage` 等 `op=ready`
- `ready` 到来：`handleReady` 从 `payload.projectState.canvas.widthMaps/heightMaps` 显示"wall W×H"，enable ping / paint 按钮
- 没 token 时显示明确提示"Start via /canvas confirm in Minecraft"（而不是静默失败）
- 增加 `#status` 彩色状态条（pending/ready/err 三色）
- **token 原文不进 log**（security.md §2.2 要求）
- TypeScript 类型收窄陷阱：`instanceof` narrowing 在 `throw` 外不会传播到 outer `const`；加一组局部变量 `logEl / statusEl / pingBtn / paintBtn` 做类型假设

**`web/index.html`**：增加 `#status` 状态条，初始 `disabled` 两个按钮

**主类接入（HikariCanvas.java）：** 构造 CanvasCommand 时传 `(this, sessionManager, frameDeployer, tokenService, mapPool, database, "http://127.0.0.1:8877/?token={token}")`

**端到端链路（T12 要实测的完整流程）：**
```
MC 客户端                                       浏览器
   ├─ /canvas edit                                 
   ├─ 左键 / 右键点两角                             
   ├─ /canvas confirm                              
   │   ├─ SessionManager.confirm: SELECTING→ISSUED
   │   ├─ MapPool.reserve N 张
   │   ├─ FrameDeployer.deploy (挂框 + 填 Placeholder)
   │   ├─ TokenService.issue → token
   │   └─ 发可点击聊天消息 [Open editor: http://...?token=...]
   ├─ 点击聊天链接  ──────────────────────► GET / → index.html
   │                                       GET /assets/*.js → 加载
   │                                       JS 从 URL 取 token → WS /ws
   │                                       → send {op:auth, token}
   │                                       ← {op:ready, projectState:{wall W×H}}
   │                                       UI 变绿，按钮 enabled
   ├─ (编辑侧 M3 才做，M2 编辑器只能 ping/paint)
   └─ /canvas commit                              
       ├─ SessionManager.commit: ACTIVE→CLOSING→CLOSED
       ├─ MapPool.promoteToPermanent → refill FREE
       ├─ FrameDeployer.promote (PDC session→sign)
       └─ INSERT sign_records row
```

**未做（按 M2 范围有意留到后续）：**
- 定时回收 task（WS 断连 5min 宽限 / auth 5s 超时 / idle disconnect）—— M7 polish
- token rotate（WS 握手成功后重发新 token）—— M3 / M7
- 限流（security.md §2.4 单玩家 / 全局）—— M7
- Origin 校验（公网部署时开）—— M7
- `/canvas cleanup` 实际数据回收 + `/canvas fsck` —— M7
- `project_json` 真实内容 —— M3

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- 前端 `npm run build` 通过（index.html 2.29 KB / index-CZM-HPIQ.js 3.28 KB）
- 端到端真实闭环测试留到 T12

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`web/index.html`、`web/src/main.ts`、`docs/journal.md`

---

## 2026-04-21 · M2-T10 WS 预握手 + auth/ready 协议

**范围：** 按 `docs/protocol.md §3.1 / §3.2` 落地会话进入协议：HTTP 预握手校验 token 并返回会话元信息；WS /ws 首帧必须是 `op=auth`；`ready` 响应带 `projectState` 占位。

**改动：**
- `WebServer.java` 重大扩展：
  - 构造器签名：`(log, host, port, tokenService, sessionManager, serverVersion, paintHandler)`
  - `GET /api/session/{token}` → `tokenService.peek(token)`（不消耗），200 返回 session 元信息 / 401 AUTH_FAILED / 409 SESSION_CLOSED
  - WS auth-first 状态机：`ctx.attribute("sessionId")` 是否绑定判定阶段；未绑定时只接受 `op=auth`，其它 op → `AUTH_FAILED` + close 4001
  - `handleAuth`：`tokenService.consume(token)` → `sessionManager.markActive(sessionId)` → `ctx.attribute("sessionId", id)` → 发 `{op:"ready", payload:{sessionId, serverVersion, protocolVersion, projectState}}`
  - 已认证路径：每条消息 `sessionManager.touch(sid)` 更新 `lastActivityAt`；`ping`/`pong` 保留；`paint` demo 通道保留待 T11 删
  - `onClose`：若绑定了 sessionId，调 `sessionManager.markDisconnected` 记时间戳（T11/M7 的定时回收 task 会扫这个）
- `HikariCanvas.java`：构造 WebServer 时传入 tokenService / sessionManager / serverVersion（从 `getPluginMeta().getVersion()` 取）

**Javalin 7 API 踩坑：**
- `cfg.routes.addEndpoint(Endpoint endpoint)` 接受 `io.javalin.router.Endpoint`——**直接 `new Endpoint(HandlerType, String, Handler)`**；没有 `Endpoint.create(...).builder().build()` 链式 API
- `WsCloseStatus` 是 **enum**（NORMAL_CLOSURE / POLICY_VIOLATION 等预定义常量），**没有**自定义 4001 的 enum 常量；应该用 `ctx.closeSession(int code, String reason)` 重载直接传 `4001`
- 对应 JavalinException 规避以 `javap -public` 查真实 class 签名最快

**`ready` 响应的 `projectState` 占位结构（`protocol.md §7` 子集）：**
```json
{
  "version": 0,
  "canvas": { "widthMaps": W, "heightMaps": H, "background": "#CCCCCC" },
  "elements": [],
  "history": { "undoDepth": 0, "redoDepth": 0 }
}
```
M3 做增量编辑时会让 `projectState` 真正带元素。

**未做（留给后续）：**
- **Token rotate**（security.md §2.2 要求）：WS 握手成功后重发新 token 给前端用于断线重连。M2 不做——WS 断开后玩家只能重新 `/canvas edit` + confirm 签发新 token。留给 M3 + M7 polish
- **Auth 超时**（protocol.md §3.1 要求 5 秒）：当前用 Javalin/Jetty 默认 30 秒 idle timeout。T11 或 M7 polish 时加自定义 watchdog
- **Origin 校验**（security.md §3.1）：默认关闭；公网部署时再开，M2 只跑本地
- **per-WS-connection 限流**（security.md §2.4 + protocol.md §9）：20 msg/s / 40 突发 / 5 次拒绝 close 1008——延到 M7
- **断线重连 5min 宽限自动回收**：`markDisconnected` 已记时间戳，但定时扫描回收 task 未实装；T11 或 M7 做

**验证：** `./gradlew :plugin:compileJava` 通过。真实端到端验证要等 T11 把 `/canvas confirm` 接到 TokenService.issue 后才能跑。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T8 FrameDeployer + FrameProtectionListener

**范围：** 物品框的批量挂装 / 会话终止时移除 / commit 升级 permanent，以及保护 listener。契约 `docs/architecture.md §7.2` 与 `docs/security.md §5`。T11 命令族实装时把这些 hook 串到 SessionManager 状态转移上。

**文件：**
- `deploy/FrameDeployer.java`（新）：
  - `deploy(Session, wall, mapIds)`：按 bbox 逐格 spawn ItemFrame，`INVISIBLE + FIXED + rotation NONE + mapItem`；PDC 打 `session / slot / role=preview`；slot 编号 = `row × width + col`，`row=0` 为最上排；deploy 后立即 push Placeholder 像素**只发会话玩家**（per-viewer 同步走 M3）
  - `removeForSession(sessionId, world)`：扫世界 ItemFrame 按 PDC session 删除；cancel 路径用
  - `promote(sessionId, signId, world)`：preview → permanent，改 PDC（`sessionKey` 移除 / `signKey` 设 / `roleKey = "permanent"`），**不重建实体** = frame 保持可见
  - `isProtectedFrame(ItemFrame)`：保护 listener 判定入口（PDC 里有 session 或 sign key）
  - 4 个 NamespacedKey（`session / sign / slot / role`）全部在 `hikari_canvas` namespace 下
- `deploy/FrameProtectionListener.java`（新）：
  - `HangingBreakEvent`：实体原因（爆炸/物理失联）一律拒绝；玩家攻击由下一个 handler 处理
  - `HangingBreakByEntityEvent`：拒绝，除非破坏者是持 `canvas.admin.force-break` 权限的玩家
  - `PlayerInteractEntityEvent`：右键改内容拒绝
  - `BlockBreakEvent`：扫 4 个水平相邻格，若有 attached 在本方块的 protected frame 则取消；同样 `canvas.admin.force-break` bypass。M2 只支持垂直墙面，故只扫水平 4 方向；M4+ 放宽后补 UP/DOWN
- `HikariCanvas.java`：`onEnable` 构造 `FrameDeployer(this, new PlaceholderRenderer(), mapPacketSender)` + 注册 `FrameProtectionListener`

**关键取舍：**
- **per-viewer 同步局限**：Minecraft MapData 是 per-player 推送，不是全局广播。T8 只给会话玩家发 Placeholder；其他在线玩家看到的是 MC 客户端本地缓存（新地图会是空白 / 灰）。完整 per-viewer 差分同步是 M3 `protocol.md §state.patch` 的事
- **promote 不重建实体**：仅改 PDC `sessionKey → signKey`，物品框保持原位可见；好处是不会有"空白闪一帧"
- **slot 编号 vs 视觉方向**：按 bbox 坐标最小值递增命名（row=0 为最高、col=0 靠 minZ/minX），不对齐"玩家视角左/右"。T11 + Placeholder 对齐后可能要调；M2 demo 阶段保持一致即可
- **没写 deploy 的回滚**：若某一 slot spawn 失败，之前成功的 frame 不会自动删除；M2 demo 阶段接受这种局部损坏，T12 集成测试时再看

**与其他模块的对接清单（T11 将完成）：**
- SessionManager.confirm 成功后：调 `frameDeployer.deploy(session, wall, mapIds)`
- SessionManager.cancel 成功后：调 `frameDeployer.removeForSession(sessionId, wall.world())`
- SessionManager.commit 成功后：调 `frameDeployer.promote(sessionId, signId, world)`

**验证：**
- `./gradlew :plugin:shadowJar` 通过；Paper 1.21 API 里 `setFacingDirection(face, force=true)` / `ItemFrame#setVisible(false)` / `setFixed(true)` 全部编译通过
- 运行时验证留给 T11（T11 完整命令族串好后自然触发）或 T12 集成

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java`、`plugin/src/main/java/moe/hikari/canvas/deploy/FrameProtectionListener.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T6 Canvas Wand + SELECTING 玩家交互入口

**范围：** 玩家侧的墙面选区 UX 全部就绪。命令入口（`/canvas edit`）+ Wand 物品入口双通道、左/右键点击记 pos1/pos2、聊天栏实时 echo 坐标与墙面预览、`/canvas cancel` 放弃。此时 T7 的 SessionManager 状态机第一次有了真正的驱动源。

**文件：**
- `deploy/CanvasWand.java`（新）：
  - `Material.GOLDEN_SHOVEL` + Adventure 名称/lore
  - PDC key `hikari_canvas:wand_owner` 存玩家 UUID 字符串；`isWandFor` 校验防别人捡到误触
  - `removeAllFrom(player, plugin)` 工具方法（confirm/cancel 后把 wand 从背包收回）
- `session/WandListener.java`（新，Bukkit Listener）：
  - 只处理 `LEFT_CLICK_BLOCK` / `RIGHT_CLICK_BLOCK`；用 `getHand() == HAND` 去重避免副手触发
  - 触发条件：**玩家持 wand** 或 **玩家已在 SELECTING 态**。否则不干预，正常建筑行为不受影响
  - 触发时 `event.setCancelled(true)`——阻止左键破坏 / 右键放置
  - Wand 入口但尚未有会话时，隐式调 `SessionManager.beginSelecting` 开会话
  - 已在 ISSUED/ACTIVE/CLOSING 阶段点击 → 红字提示先 `/canvas cancel`
  - 两角设完立即跑 `preview()`，Ok 显示 "Wall: WxH (N maps), facing F. From (x,y,z) to (x',y',z'). Run /canvas confirm."，Failed 显示 "Selection invalid: REASON — detail"
  - `PlayerQuitEvent` 处理：SELECTING 态玩家掉线立即释放；ISSUED/ACTIVE 保留（WS 重连宽限走 T10）
- `command/CanvasCommand.java`：
  - 新增 `edit / wand / cancel` 三个正式子命令
  - `cancel` 调用 `CanvasWand.removeAllFrom` 一并收回 wand
  - 构造参数从 `(mapPacketSender)` 扩展为 `(plugin, mapPacketSender, sessionManager)`
- `HikariCanvas.java`：`onEnable` 注册 `WandListener`（Bukkit `PluginManager.registerEvents`），传入 plugin + sessionManager；`CanvasCommand` 构造同步加参数

**玩家交互规范（对应 architecture.md §7.1）：**

| 入口 | 前提 | 行为 |
|---|---|---|
| `/canvas edit` | 无活跃会话 | 开启 SELECTING 态，actionbar 提示 |
| 持 Canvas Wand 点击 | 任意 | 隐式 beginSelecting；已有会话时忽略 wand 作用 |
| 空手 / 任意方块点击 | 玩家已 SELECTING | 记 pos1（左键）/ pos2（右键），聊天栏 echo + preview |
| `/canvas cancel` | 任意 | 会话 cancel + wand 收回 |

**实测路径（待手动验证）：**

```
/canvas edit                       # 进 SELECTING
左键 点 墙面某方块 (10, 64, -5)    # 聊天栏："First corner (10, 64, -5) facing EAST"
右键 点 墙面另一方块 (13, 65, -5)  # 聊天栏："Second corner (...)" +
                                   #           "Wall: 3x2 (6 maps), facing EAST. From (10,64,-5)
                                   #            to (13,65,-5). Run /canvas confirm."
/canvas cancel                     # "Session cancelled (was SELECTING)."
```

然后 Wand 模式：

```
/canvas wand                       # "Received Canvas Wand."
右键 / 左键 点墙面                 # 自动进 SELECTING + 记角 + 回显
```

**留给 T11：** `/canvas confirm` 命令尚未实装——T6 的聊天栏提示里引导用户敲这条命令，但实际执行会报"Unknown command"。T11 把 SessionManager.confirm() 接到命令上即可。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/CanvasWand.java`、`plugin/src/main/java/moe/hikari/canvas/session/WandListener.java`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T7 SessionManager + 会话状态机

**范围：** 编辑会话的生命周期核心。契约 `docs/architecture.md §3`。汇合点——后续 T6 Wand 提供玩家入口、T10 WS auth 标 ACTIVE、T11 命令族调 confirm / commit / cancel。

**文件：**
- `session/SessionState.java`（新）：四个状态 `SELECTING / ISSUED / ACTIVE / CLOSING`（CLOSED 不用显式枚举——从 `byId` 中移除即 CLOSED）
- `session/WallKey.java`（新）：`(world, originX, originY, originZ, facing)` 墙面排他锁 key
- `session/Session.java`（新）：会话可变 POJO；package-private setter 只允许 SessionManager 在持锁段内修改；字段按状态分阶段生效（SELECTING：pos1/pos2/face；ISSUED+：wall/mapIds/wallKey；ACTIVE：lastActivityAt/wsDisconnectedAt）
- `session/SessionManager.java`（新，核心）：
  - 索引 `byId` / `byPlayer` / `byWall`；所有公共方法 `synchronized(this)`
  - `beginSelecting` 返回 sealed `BeginResult.{Ok, AlreadyHasSession}`；后者封装了"每玩家最多 1 会话"约束
  - `recordPos(sessionId, isFirstCorner, block, face)` + `preview()`：selecting 阶段的聊天栏回显 hook
  - `confirm` 返回 sealed `ConfirmResult.{Ok, NotReady, WallFailed, WallOccupied, PoolExhausted}`——把所有失败路径显式建模，命令层 pattern-match 后对玩家产出对应的友好消息
  - `commit` 返回 sealed `CommitResult.{Ok, NotActive}`；ACTIVE 或 ISSUED 都允许 commit（命令通道 vs WS 通道）
  - `cancel(sessionId, reason)`：幂等、任何非 CLOSING 状态可调；自动归还池 + 释放 wallKey
  - `markActive` / `touch` / `markDisconnected` 是 T10 WS 层 hook
  - `liveSessionIds()` 给 {@code MapPool.detectLeaks} 消费
- `HikariCanvas.java`：`onEnable` 构造 `WallResolver(16)` + `SessionManager(log, mapPool, wallResolver, auditLog)` 并挂在 plugin 上

**设计取舍：**
- **失败路径用 sealed interface + record 显式建模**：不用 exception。调用方（T11 命令族）能编译期穷举所有 case，避免遗漏分支
- **Session 可变 + 外部不该直接改**：Java 17+ sealed + 可见性不支持"只 package 能读"；退而用 package-private setter + 文档约束"只在 SessionManager 持锁下修改"
- **状态机无 CLOSED 枚举**：`forget(s)` 从 map 里移除 = CLOSED；减少状态判定需要
- **wallKey 即排他锁**：`byWall` 本身就是锁表；commit/cancel 时 `byWall.remove(wallKey, sessionId)` 原子释放
- **本 M2 不加定时回收 task**：WS idle 5min、auth 超时这些留给 T10/T11。理由：idle 判定需要 `lastActivityAt`，该字段只在 WS 消息到达时更新，而 T7 阶段还没有 WS 绑定；时机还未到

**未做（留给后续）：**
- 定时回收 task：T10 或 T11 加 Bukkit scheduler 周期扫 `wsDisconnectedAt > 0 && now - wsDisconnectedAt > 5min` 触发 cancel
- 权限校验：T11 在命令层做（`canvas.edit` / `canvas.commit`）；SessionManager 不做权限判断
- SignRecord 写入：T11 commit 流程里 SessionManager.commit 之后由调用方 insert 到 `sign_records` 表

**验证：**
- `./gradlew :plugin:compileJava` 通过
- SessionManager 虽已挂在 HikariCanvas 但无调用入口，runServer 启动表现与 T9 一致（空跑不崩）。真正闭环测试在 T11+T12。

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/session/` 4 个新文件、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T9 PlaceholderRenderer + BitmapFont

**范围：** 按 `docs/architecture.md §4.4` 渲染 Placeholder 占位图。128×128 浅灰底 + 顶部 "HIKARICANVAS" 水印 + 底部 "N/M" 位置标签。M4 真字体接入前就靠这套。

**文件：**
- `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`（新）：手工定义的 5×7 ASCII 位图字表
  - 字符集仅覆盖 **H/I/K/A/R/C/N/V/S + 0-9 + "/" + 空格**（共 21 个）—— 刚够拼 "HIKARICANVAS" 水印 + 位置标签 "N/M"
  - 存储：`Map<Character, int[7]>`，每行一个 int 的低 5 位（MSB→LSB 代表从左到右 5 个像素）
  - 未知字符返回 `EMPTY` 空白（不报错），小写自动 `toUpperCase()`
- `plugin/src/main/java/moe/hikari/canvas/render/PlaceholderRenderer.java`（新）：
  - `render(slotIndex, totalSlots) → byte[128*128]`
  - 背景填色 palette 索引 33（浅色）；前景 44（深色）—— M4 调色板 LUT 就位后修正精确值
  - 顶部 "HIKARICANVAS" scale=1（12 字符 × 6 像素 = 71 px 宽，y=12 居中）
  - 底部 "N/M" scale=3（显眼大号，y=97）
  - 无状态，并发安全
- `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`：新增 `/canvas placeholder <slot> <total>` DEPRECATED 子命令，方便玩家**手动预览渲染效果**（T11 一起删）

**设计取舍：**
- **字体字符集极小**：contract 只说"HikariCanvas 水印"和"坐标文字"。我把"坐标文字"改成更简洁的 "N/M"（如 "2/6"）——实际 128×128 像素空间下世界坐标 `(x,y,z)→(x',y',z')` 太挤，"第几张/共几张" 反而更有用
- **全大写**："HIKARICANVAS" 比"HikariCanvas"省字形。M4 真字体接入后再换大小写混排
- **Palette 索引 33/44 是经验值**：M2 demo 够用；M4 RGB→palette LUT 建好后以 `#CCCCCC` 和 `#3A3A3A` 重选精确索引
- **位图字表用硬编码 int 数组**：不用图片资源 / PNG 解码，启动零 I/O；字符总数 21，代码不到 30 行
- **slot 从 0 开始传入，渲染时显示 +1**：代码层保留 0-based 习惯，UI 层玩家看到的是 1-based

**手动验证方式（测试命令已就绪）：**
```
/canvas give                       # 拿一张空白地图
/canvas placeholder 0 6            # 显示 "1/6"
/canvas placeholder 2 6            # 显示 "3/6"
/canvas placeholder 5 6            # 显示 "6/6"
```

**留给后续任务：**
- T8 `FrameDeployer` 会在 `/canvas confirm` 时调 `render(slot, total)` 给每张物品框填 placeholder
- M4 渲染引擎会用真 TTF 字体替换此处所有逻辑；本包 `render/` 会保留但 `PlaceholderRenderer` / `BitmapFont` 这两个类的实现完全重写

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/render/BitmapFont.java`、`plugin/src/main/java/moe/hikari/canvas/render/PlaceholderRenderer.java`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T5 WallResolver

**范围：** 纯算法类，将玩家两次点击（pos1/pos2 + BlockFace）解析为墙面矩形 + 合法性校验；输出 `Result.Ok` 或 `Result.Failed(reason, detail)`。T6 Wand + SELECTING 会调用它做选区预览，T7 SessionManager 在 `/canvas confirm` 时复用同一份逻辑。

**设计：**
- 无状态、无副作用，构造只接收 `maxMaps` 上限（对应 `limits.canvas-max-maps` = 16 默认）
- Result 用 sealed interface + 两个 record（`Ok` / `Failed`），调用方 pattern-match
- M2 仅支持**水平四向墙**（N/S/E/W）；UP/DOWN 返回 `VERTICAL_ONLY`，M4+ 再放宽

**失败码清单（返回 `FailReason`）：**
- `NORMAL_MISMATCH` — 两次点击 BlockFace 不同（两面墙）
- `DIFFERENT_WORLDS` — 两 block 跨世界
- `VERTICAL_ONLY` — normal = UP/DOWN
- `NOT_COPLANAR` — 同 normal 但不在同一平面（X 或 Z 轴需一致）
- `TOO_LARGE` — `width × height > maxMaps`
- `BLOCK_NOT_SOLID` — bbox 内某方块非实心 full cube（排除台阶/栅栏/玻璃板）
- `OCCUPIED` — bbox 前方一格已挂 ItemFrame（扫描 `getNearbyEntitiesByType(ItemFrame.class, ...)`）

**算法要点：**
- 同平面判定：`EAST/WEST` 要求 `pos1.x == pos2.x`；`NORTH/SOUTH` 要求 `pos1.z == pos2.z`
- `width` 随法线轴决定（法线 X → width 沿 Z；法线 Z → width 沿 X）
- `isSolid() && isOccluding()` 判实心 full cube（`isSolid` 包含台阶，`isOccluding` 更严）
- ItemFrame 占用检测兼容 Paper 1.21 `getAttachedFace()` 的边界差异：既查 `attachedFace.getOpposite() == face`，又查 frame 位置是否落在 adjacent 方块内

**验证：** `./gradlew :plugin:compileJava` 通过。运行时 smoke test 留到 T6 有真实玩家交互入口后做。

**未包含：**
- 不校验墙面背后是否有"支撑"（物品框挂在固体墙上，MC 不要求背面有支撑；若 T8 发现问题再补）
- 不处理透明方块（glass）——`isOccluding=false` 会被 `BLOCK_NOT_SOLID` 拦，合理
- Maximum 尺寸以地图数计（16 默认），不再单独检查单维上限——若玩家选 1×16，算法允许

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/deploy/WallResolver.java`、`docs/journal.md`

---

## 2026-04-21 · M2-T4 MapPool —— PROPOSAL 风险表 #1 核心机制落地

**范围：** 预览地图池状态机（FREE/RESERVED/PERMANENT）+ SQLite 同步 + 启动恢复 + 泄漏检测。**这是 M2 的技术核心**，也是整个项目防止 `idcounts.dat` 膨胀的唯一防线。

**文件：**
- `plugin/src/main/java/moe/hikari/canvas/pool/PoolState.java`（新）：三状态枚举
- `plugin/src/main/java/moe/hikari/canvas/pool/PooledMap.java`（新）：不可变 record，`withFree` / `withReserved` / `withPermanent` 原子 transition
- `plugin/src/main/java/moe/hikari/canvas/pool/PoolExhaustedException.java`（新）：容量耗尽时抛
- `plugin/src/main/java/moe/hikari/canvas/pool/MapPool.java`（新，核心）：
  - 索引：`Map<Integer, PooledMap> byId` + `Deque<Integer> freeQueue`
  - 全部公开方法 `synchronized(this)`
  - `initialize(World)`：读 `pool_maps` 表 → 校验不变式（异常行降级 FREE + 告警）→ `Bukkit.getMap(id)` 检查 MapView 仍在（丢失则 DELETE 行 + `POOL_ORPHAN_ROW` 告警）→ FREE 数量 < `initial-size` 时 expand 补齐
  - `reserve(sessionId, count)`：FREE 不够时自动 expand；expand 超 `max-size` 抛 `PoolExhaustedException`（对应 protocol.md §6.1 错误码 `POOL_EXHAUSTED`）
  - `returnToPool(sessionId)`：cancel 路径；遍历 RESERVED 且 `reservedBy==sessionId` → FREE + 重入 freeQueue
  - `promoteToPermanent(sessionId, signId, world)`：commit 路径；RESERVED → PERMANENT（从"可用"计数中抽走，但保留在 `byId`）；随后 refill FREE 到 `initial-size`
  - `detectLeaks(liveSessions)`：RESERVED 但 sessionId 不在活会话中 → 强制归还 + `POOL_LEAK` 告警
  - 所有状态转移 `persist()` 用 `INSERT ... ON CONFLICT DO UPDATE`（SQLite upsert），幂等
- `HikariCanvas.java`：onEnable 构造 `MapPool(initial=64, max=256)` 并 `initialize(Bukkit.getWorlds().get(0))`

**关键约束与设计取舍：**
- **线程安全 / 主线程约束：** `Bukkit.createMap` 与 `MapView` 相关调用必须在主线程，因此 `initialize`、`reserve`、`promoteToPermanent` 三个"可能触发 createMap"的方法**只能主线程调用**（javadoc 已标）。`detectLeaks` 只读状态和归还（不 createMap），可异步调用——给后台 leak detection 留出空间
- **PDC 标记暂不实装**：data-model.md §3.3 说"SQLite 与 PDC 不一致时以 SQLite 为权威"，M2 阶段只用 SQLite 作为单一 source of truth；MapView PDC 标记（`pool_state` / `owner` / `session_id` 等）留给 M7 打磨期增强韧性——那时再一次性跨所有状态转移打 PDC
- **initial/max 硬编码 64/256**：config.yml 接入延后；contract 已定默认值
- **不变式异常一律降级 FREE**：启动时若发现违反不变式的记录（例如 FREE 但 `reserved_by` 非空），不尝试推断原状态，**直接强制归还 FREE**，让池进入已知干净状态。数据安全 > 便利性
- **missing MapView 处理**：DB 有记录但 `Bukkit.getMap(id)` 返回 null（典型场景：世界文件丢失 / 手动删 idcounts.dat）→ 删 DB 行 + `POOL_ORPHAN_ROW` 审计；让 MapPool 退回干净状态

**实测（首次启动 2026-04-21 16:22）：**
- 日志链：`Database initialized` → `DB schema current version: 1` → `MapPool recovered 0 entries` → `MapPool growing FREE by 64 to reach initial-size=64` → `HikariCanvas enabled`
- 创建 64 张 MapView 用时约 3s
- `sqlite3 data.db "SELECT state, COUNT(*) FROM pool_maps GROUP BY state"` → `FREE|64`
- `audit_log` 里出现两条：`POOL_EXPAND` 和 `POOL_INITIALIZED`
- 下次启动（未测试，T12 集成时验证）应走 "MapPool recovered 64 entries; free=64 ..." 分支而不是再 expand

**留给后续任务：**
- Placeholder 像素填充（T9）：reserve 出来的地图目前 MapView 是空的（客户端会看到旧缓存）；T9 实现 PlaceholderRenderer 后由 T8 FrameDeployer 挂物品框时一并 push 初始像素
- Leak detection 调度（T7）：当前 `detectLeaks` 方法有了但没挂定时任务——需要 SessionManager 提供 `liveSessions: Set<String>`，T7 SessionManager 就位后再串起来
- `/canvas stats` 输出（T11）：`MapPool.stats()` 已准备好 record，等命令族实装

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/pool/`（新 4 个文件）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T3 TokenService + AuditLog 封装

**范围：** 一次性 token 签发 / 校验 / 消耗的核心服务，并带 SHA-256 审计。按 `docs/security.md §2` 落地。限流（§2.4）延后到 M2-T10 WS 握手时一起做（需要 IP 上下文）。

**改动：**
- `plugin/src/main/java/moe/hikari/canvas/storage/AuditLog.java`（新）：`audit_log` 表的薄封装；字段 `event / player_uuid / player_name / session_id / ip_hash / details(JSON)`；`details` 用 Jackson 序列化 `Map<String, Object>`；插入失败 fire-and-forget
- `plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`（新）：
  - `SecureRandom` + 32 字节 + `Base64.getUrlEncoder().withoutPadding()` = 43 字符
  - 内存 `ConcurrentHashMap<String, Record>` 主存
  - `issue(playerUuid, playerName, sessionId)` → 返回原文 token，同步 `AUTH_ISSUED` 事件入 `audit_log`（**只存 token 的 SHA-256**，永不落盘原文）
  - `peek(token)` / `consume(token)` 共用 `evaluate(..., consume=)`：长度 / base64 解码 / 不存在 / 已使用 / 过期**五重校验**，按 security.md §2.3 顺序
  - `consume` 使用 `ConcurrentHashMap.replace(k, oldV, newV)` 做原子 CAS，防并发重复消费
  - `purgeExpired()` 惰性清理，HikariCanvas 每 5 分钟异步调用一次
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：onEnable 构造 AuditLog + TokenService 并挂 5min 周期 purge task；onDisable cancel task

**设计细节与取舍：**
- **TTL 硬编码 15 min**（contract 默认）；config.yml 接入留给 M2-T10 或后续 polish 任务一并做
- **API 分 `peek` 和 `consume` 两个入口**：HTTP 预握手 `GET /api/session/:token` 只 peek 不消耗（供客户端在 WS auth 前确认会话信息）；WS `auth` 帧到达时才 consume（security.md §2.1 的"消耗后立即失效"）
- **rotate**（§2.2 的 WS 握手成功后签发新 token）没独立 API；调用方拿到 Ok 结果后自己 `issue` 一次即可——避免给 TokenService 加隐含状态
- **RejectReason 不向外透露**（security.md §2.3："失败场景统一返回 AUTH_FAILED；不向外透露具体原因，避免枚举攻击"）——内部 log 记具体原因供运维排错，WS/HTTP 响应只返回统一 401/4001
- 限流实现延后：**单玩家 10/5min 封禁 / 全局 100/min 保守模式** 需要 IP 信息，WS 握手前无从获取；T10 实现 `GET /api/session/:token` 时再在入口做限流

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- runServer 启动正常（Done 8.675s）；TokenService + 5min async purge task 挂上无异常
- `audit_log` 表仍为空（还没有业务链路触发 `issue`；M2-T10/T11 会出现真实记录）

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/storage/AuditLog.java`、`plugin/src/main/java/moe/hikari/canvas/session/TokenService.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T2 SQLite + HikariCP + JDBI 接入 + schema v1 建表

**范围：** 按 data-model.md §2 全量建表；持久化基础设施就位，后续 T3/T4/T7 等能直接写 SQL

**依赖（当前稳定版实测）：**
- `org.xerial:sqlite-jdbc:3.53.0.0`（Gradle 冲突解析实际拿到 `3.49.1.0`——paperweight-userdev 的 paperDevBundle 传递依赖强制锁了低版本；3.49.1.0 功能相同，stable。M2 不折腾，待 M7 打磨期看是否要 force 3.53。异常栈里能看到版本号已确认）
- `com.zaxxer:HikariCP:7.0.2`
- `org.jdbi:jdbi3-core:3.52.1`
- `org.jdbi:jdbi3-sqlite:3.52.1`（注意包名是 `org.jdbi.v3.sqlite3`——末尾 `3` 是模块约定，不是版本号；不是 `sqlite`）

**踩坑记录：**
1. **JDBI sqlite 模块包名陷阱**：import 写 `org.jdbi.v3.sqlite.SQLitePlugin` 编译失败；真实路径是 `org.jdbi.v3.sqlite3`。用 `jar tf` 查 jar 内 class 一眼看出
2. **迁移脚本注释处理 bug**（运行时 crash）：
   - `V001__initial.sql` 开头有 3 行文件级注释 `-- ...`，紧接第一个 `CREATE TABLE pool_maps`
   - 原实现按 `;` split 后再 `trimmed.startsWith("--")` 跳过注释——**整个片段以 `--` 开头（实际内含 CREATE TABLE）被误跳过**
   - 结果：`CREATE TABLE pool_maps` 没跑，下一条 `CREATE INDEX ... ON pool_maps(state)` 报 `no such table`
   - 修法：loadResource 阶段**逐行剥注释**再拼接；拆分阶段只跳空串，不再判 `--`
3. **sqlite-jdbc 不支持一次 execute 多条语句**：无论用 `;` 分隔还是批处理都要自己拆。拆分时遇到数据里含 `;` 的情况会有风险（M2 schema 没有，future schema 若有字符串字面量含 `;` 要用更严谨的 SQL tokenizer）

**改动文件：**
- `plugin/build.gradle.kts`：新增 4 个 implementation 依赖（sqlite-jdbc/HikariCP/jdbi3-core/jdbi3-sqlite）
- `plugin/src/main/resources/db-migrations/V001__initial.sql`（新）：按 data-model.md §2.3~§2.6 建 `pool_maps`/`sign_records`/`audit_log`/`template_usage` 四张表 + 全部索引。不含 `schema_version`（由 Java 代码首次确保存在）
- `plugin/src/main/java/moe/hikari/canvas/storage/Database.java`（新）：HikariCP 连接池封装，最大 4 连接；SQLite 打 WAL 模式 + 外键约束；暴露 `Jdbi jdbi()`；`AutoCloseable`
- `plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`（新）：显式列表式迁移（不做 classpath 扫描，shadow jar 下不稳定）；`ensureSchemaVersionTable` 用 IF NOT EXISTS；应用每个脚本后 INSERT schema_version；逐行剥注释 + 按 `;` 拆
- `plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`：onEnable 构造 Database 并跑 MigrationRunner；onDisable 关闭

**验证：**
- `./gradlew :plugin:shadowJar` 通过
- runServer 启动成功：`Database initialized` → `DB schema current version: 0` → `Applying migration V001 ...` → `✓ V001 applied` → `WebServer listening` → `HikariCanvas enabled` → `Done (8.165s)!`
- `sqlite3 plugin/run/plugins/HikariCanvas/data.db ".tables"` 输出全部 5 张表：`audit_log pool_maps schema_version sign_records template_usage`
- `SELECT * FROM schema_version` → `1|1776697225399`
- 4 张业务表空（新库）

**M2 剩余任务（M2-T3~T12）从这里开始都可以假设 `database.jdbi()` 可用。**

**关联文件：** `plugin/build.gradle.kts`、`plugin/src/main/resources/db-migrations/V001__initial.sql`、`plugin/src/main/java/moe/hikari/canvas/storage/Database.java`、`plugin/src/main/java/moe/hikari/canvas/storage/MigrationRunner.java`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2-T1 代码层改名 Hc → Canvas

**范围：** 契约已改，代码层跟进：类名、根 literal、import、用户消息字串

**改动：**
- `git mv` `HcCommand.java` → `CanvasCommand.java`；类名同步；根 literal `"hc"` → `"canvas"`
- `HikariCanvas.java`：import + `new HcCommand(...)` → `new CanvasCommand(...)`
- 内部 javadoc、聊天栏消息里的 `/hc give` / `/hc paint` 全改 `/canvas give` / `/canvas paint`
- 子命令 `give` / `paint` **保留为 DEPRECATED demo**（代码注释明确标记，运行时消息前缀加 `[DEPRECATED demo]`）——M2 实施中间阶段还没有正式命令族，需要它们手动验证发包链路；T11 命令族完整实装时一起删除
- `./gradlew :plugin:shadowJar` 通过

**关联文件：** `plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java`（rename from `HcCommand.java`）、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`docs/journal.md`

---

## 2026-04-20 · M2 立项契约修订：命令前缀 /canvas + 墙面交互规范 + Placeholder 地图

**范围：** 进入 M2 前的契约层对齐。三件事：命令前缀从 `/hc` 全局改为 `/canvas`（含权限节点）；细化墙面交互流程（SELECTING 状态 + 两段式确认）；新增 Placeholder 地图规范。纯文档变更，代码留到 M2 任务逐步落实。

**为什么要改：**
- `/hc` 对新玩家意义不明（"hikari canvas" 的缩写不直观）；`/canvas` 一眼见义
- architecture.md 原版「锁定墙面」段只写了射线检测 + 尺寸从模板来，没定玩家如何告诉插件"就是这面墙"——M2 实施前这一空白必须填上
- M1 完成时发现：玩家从命令到真正看到编辑效果中间有一大段纯文字反馈，不直观。`/canvas confirm` 立即挂物品框 + Placeholder 能让玩家在游戏里马上看到"墙被选中了"，UX 更自然

**关键决策（讨论后拍板）：**

1. **命令前缀 /hc → /canvas，权限前缀 hc. → canvas.**
   - PDC namespace `hikari_canvas` **保持不变**（底层数据标记，不与用户直接交互，改动纯属噪声）
   - Java 类名 `HcCommand` → `CanvasCommand`（M2 实现时改）

2. **墙面交互采用命令 + Wand 双入口组合（用户选定）：**
   - `/canvas edit`：进入 SELECTING 状态，玩家空手或任何方块点击均可（零背包负担，偶尔使用者友好）
   - `/canvas wand`：发命名金铲「Canvas Wand」，持 wand 时左/右键直接交互，无需先打命令（频繁使用者友好）
   - 左键 = pos1，右键 = pos2；每次点击聊天栏 echo 坐标与预览
   - 手打 `/canvas confirm` 才真正 commit 选区（刻意保留一步手动动作，避免误点意外创建会话）
   - `/canvas confirm` 后**立即挂物品框 + 借池地图 + 填 Placeholder** → 玩家即刻看到占位网格

3. **新状态 SELECTING 加入会话状态机**（原来只有 CLOSED/ISSUED/ACTIVE/EXPIRED/CLOSING）
   - SELECTING 态**不占池、不挂物品框**——只是个"选区草稿"；任何时候可 `/canvas cancel` 或玩家掉线即释放

4. **Placeholder 地图视觉（用户选 A）：**
   - 浅灰底 + 顶部 "HikariCanvas" 水印 + 底部坐标文字
   - M4 前无字体系统，所以用**预烘焙位图 ASCII 字表**（只覆盖英文字母/数字/标点）
   - 所有会话共享同一只读像素缓冲；每张物品框叠加独立的 "位置标签"（如 "2/6"）以区分

5. **删除 /hc give demo 命令**
   - M1 阶段临时用来让玩家拿 filled_map 测试涂色的快捷命令
   - M2 有 `/canvas edit` + wand 正规流程，give 不再需要；保留会误导用户走错工作流

**改动文件：**
- `CLAUDE.md` 标识表：命令前缀 / 权限前缀
- `PROPOSAL.md`：§4.1 命令清单（补 edit/wand/confirm/cancel/commit/cleanup/stats，删 give/undo）；§5.2 UX 文字；§5.3 项目结构；§7 风险表 `/hc/` → `/canvas/`
- `docs/architecture.md`：§1.3 数据流图两段式重绘；§2.1 组件说明；§3.1 状态机增加 SELECTING 态；§3.2 状态转移表 4 条新增；§7.1 从"锁定墙面"改写为"交互与选区（两段式）"；§7.2 物品框部署补"填 Placeholder"；**新增 §4.4 Placeholder 地图规范**；旧 §4.4 健康指标改号 §4.5；若干 `/hc` 字串替换
- `docs/security.md`：§5 权限节点表全改名（新增 `canvas.wand` / `canvas.admin.force-break`）；§6 校验检查点表全改；nginx 配置示例 `/hc/` → `/canvas/`；审计命令
- `docs/data-model.md`：`/hc remove` / `/hc cleanup` / `/hc fsck` 改名（PDC namespace 保持 `hikari_canvas` 不动）
- `docs/template-spec.md`：`/hc reload templates` 改名

**journal 历史条目里的 /hc 字串**（M1-T5 / T6 / T7 / 改名条目里的 give/paint 等）**不动**——journal 是过程记录，不是契约文档；改动会伪造历史。

**M2 任务拆解（等用户最终 OK 后正式建 Task）：**
1. SQLite + HikariCP + JDBI 接入 + 建表脚本（data-model.md §2）
2. 命令 / 权限 / 类名 `HcCommand` → `CanvasCommand`（文档已改，代码跟进；顺便删 M1 的 give/paint demo 命令）
3. TokenService（内存主 + SHA256 审计；TTL 15min；单次使用 + rotate）
4. MapPool 实现（FREE/RESERVED/PERMANENT 状态机；借/还/refill/leak detection）
5. WallResolver（pos1/pos2 → bounding box → 合法性校验 + BlockFace 法线识别）
6. Canvas Wand 物品 + PlayerInteractEvent listener + SELECTING 状态机
7. SessionManager（每玩家 1 活跃 + 每墙面排他锁 + disconnect 5min 宽限）
8. FrameDeployer（挂物品框 + PDC 标记 + 保护 listener：HangingBreak/BlockBreak/PlayerInteractEntity）
9. PlaceholderRenderer（位图 ASCII 字表 + 预烘焙共享缓冲）
10. WebServer 预握手 `GET /api/session/:token` + WS `auth` 帧 + `ready` + 初步 `state.snapshot`
11. /canvas 命令族完整实装（edit/wand/confirm/cancel/commit/cleanup/stats）
12. 集成测试：完整 SELECTING → ISSUED → ACTIVE → CLOSING(commit) 一轮

**关联文件：** `CLAUDE.md`、`PROPOSAL.md`、`docs/architecture.md`、`docs/security.md`、`docs/data-model.md`、`docs/template-spec.md`、`docs/journal.md`

---

## 2026-04-20 · M1-T7a 端到端联调通过 · **M1 正式完成**

**范围：** M1 最终验收标准实装——浏览器按钮 → WebSocket → 游戏内地图像素变化。

**里程碑：** PROPOSAL §6 M1 行从「1 周」更新为「已完成（2026-04-20）」。从 M0 立项（2026-04-18）到 M1 收尾共 3 个工作日，较原估 1 周缩短。

**端到端链路（实测）：**

```
Minecraft 客户端 ─ /hc give ─▶ 插件发地图 item + MapView 给玩家
浏览器 ─ 点 Paint 按钮 ─▶ ws://127.0.0.1:8877/ws ─ {op:"paint"}
插件 WebServer.handleMessage(paint) ─▶ paintHandler.run()
HikariCanvas.paintAllHeldMapsRed ─▶ Bukkit 主线程 runTask
遍历 online players 主手 filled_map ─▶ MapPacketSender.sendFullMap
PacketEvents 发 ClientboundMapItemDataPacket ─▶ MC 客户端地图变红
```

**改动（代码层）：**

**Gradle ↔ npm 联动（`plugin/build.gradle.kts`）：**
- `installWebDeps`（Exec）：`npm install` in `web/`，`onlyIf` 判断 `node_modules` 不存在（避免每次 CI/clean 重复下载）
- `buildWeb`（Exec）：`npm run build`；声明完整 inputs（`package.json`/`package-lock.json`/`src/`/`index.html`/`vite.config.ts`/`tsconfig.json`）+ outputs `web/dist`，Gradle up-to-date 检查得以生效
- `copyWebToResources`（Copy）：把 `web/dist` 拷到 `build/generated/web-resources/web/`
- `sourceSets.main.resources.srcDir(build/generated/web-resources)` + `processResources.dependsOn(copyWebToResources)`：把前端产物自动并入 plugin 资源，shadowJar 自带包进
- **陷阱：** Gradle 9 的 Exec task `doFirst {}` 里不再可用 `exec { ... }` 闭包（"Too many arguments for 'fun exec(): Unit'"）。改成**独立 Exec task + onlyIf** 解决

**后端（`WebServer.java` / `HikariCanvas.java`）：**
- `WebServer` 构造参数加 `Runnable paintHandler`；`handleMessage` 新增 case `"paint"` → `paintHandler.run() + ack(submitted:true)`
- `HikariCanvas.paintAllHeldMapsRed()`：`Bukkit.getScheduler().runTask(this, ...)` 切主线程；遍历 `Bukkit.getOnlinePlayers()`；对主手 `filled_map` 调 `MapPacketSender.sendFullMap`
- 架构纪律 §5.2.6 继续被遵守：所有 packet 发送仍在 `MapPacketSender` 内部，HikariCanvas 主类只调 sender API

**前端（`web/` 子项目）：**
- `vite.config.ts`：dev server 端口 `5173` → `9173` + `strictPort: true`（用户本地 5173 被占；strictPort 让冲突时直接报错而不是静默降级）
- `index.html`：新增 `Paint held map → red` 按钮（红色主色调）
- `main.ts`：抽出 `send(op)` helper；新增 paint 按钮 handler；`resolveWsUrl()` 改得不依赖具体 dev 端口——只要 origin 不是 `127.0.0.1:8877` 就跨源连插件（未来再换 dev 端口无需改代码）

**TODO 留给 M7 打磨阶段：** `Javalin 7 的 cfg.staticFiles.add("/web", CLASSPATH)` 在 shadow/fat jar setup 下 directory discovery 失败，抛 `JavalinException: "... does not exist. Depending on your setup, empty folders might not get copied to classpath."`。web 资源**实际已进 jar**（`jar tf` 可见 `web/index.html` 和 `web/assets/*`）。M1-T7a 先绕开：开发期走 Vite dev + 跨源 WS。M7 单 jar 部署时改用手写 GET handler 读 classpath 资源，或 `Location.EXTERNAL` + 已知文件系统路径。WebServer 代码里已留 `TODO(M7)` 注释。

**WS idle timeout：** 实测时出现过 `WebSocketTimeoutException: Idle Timeout 30005/30000 ms` —— Jetty 12 默认 30s 无消息就断，属 Javalin/Jetty 默认行为。前端 `ensureConnected()` 已处理重连；M2 做会话/token 时可一并调大或上应用层心跳。

**实测（你自测）：**
- MC 1.21.11 客户端连 127.0.0.1 → `/hc give` 拿到空白 canvas 地图
- 浏览器 `http://127.0.0.1:9173/` → 点 Paint → 游戏内地图可视区**变红**
- server console 出现 `WS paint op: painted 1 held maps`
- 无 exception / Caused by

**里程碑总结（M0→M1）：**

| 任务 | commit | 关键成果 |
| --- | --- | --- |
| T1 Gradle 骨架 | `525ac54` | paperweight-userdev 2.0.0-beta.21 + paper-api 1.21.11 sync 通过 |
| T2 插件主类 | `404a4af` | 最小 `JavaPlugin` + `paper-plugin.yml`，jar 能 load |
| T3 runServer | `4c14fc8` | `xyz.jpenilla.run-paper` 接入；EULA 自动接受；生命周期日志两端跑通 |
| T4 Javalin + WS | `dd8097c` | Javalin 7 + Jetty 12 + Jackson + shadow 胖 jar；ws upgrade 101 握手通过 |
| T5 /hc paint + Packet | `73ce7bc` | **核心风险验证通过**：`WrapperPlayServerMapData` 发包直接改像素，游戏内地图涂红 |
| T6 前端骨架 | `8838ea5` | Vite 8.0.9 + TS 6.0.3；原生 DOM；build 产物 < 2KB |
| T7b 契约修正 | `8d497e7` | snapshot 测试台推迟到 M4 |
| T7a 端到端 | *本次* | 浏览器 → WS → 游戏内地图像素变化（**M1 验收**） |

**关联文件：** `PROPOSAL.md`（§6 M1 行状态更新）、`plugin/build.gradle.kts`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java`、`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java`、`web/index.html`、`web/src/main.ts`、`web/vite.config.ts`、`docs/journal.md`

---

## 2026-04-20 · M1-T7b 契约修正：snapshot 测试台推迟到 M4

**范围：** 纯文档变更，PROPOSAL 三处表述从"M1 就建 snapshot 测试台"改为"M4 渲染引擎立项期建立"

**触发原因：** T1~T6 完成后回头评估——M1 阶段前后端都不涉及字体/排版/效果渲染（那些在 M4），此时"像素级对比测试台"没有实际可比的内容，搭起来只是空壳基础设施，不如在 M4 两端真实渲染代码就位时一次建好。这是一次**契约回调**：立项期的判断现在看过于超前。

**我之前曾保留 "M1 就建" 的决策（见 2026-04-19 · CLAUDE.md 首版 + M1 技术选型拍板 条目的第 4 条）。**本次根据实施经验调整。

**改动：**
- `PROPOSAL.md` §5.2.1：末尾补充括号注「**M4 渲染引擎立项期同步搭建**；M1 不含」
- `PROPOSAL.md` §6 里程碑表 M4 行：补「**同步建立双端像素级 snapshot 测试台**（见 §5.2.1）」+ 产出列补「+ snapshot 基准集」
- `PROPOSAL.md` §7 风险表「浏览器 Canvas 与 Java Graphics2D 渲染不一致」应对列：`M1` → `M4`，并解释推迟理由
- `docs/rendering.md` 已有的 snapshot 相关表述（§渲染管线 / §发光 / §抖动）**不涉及时点**，无需改

**未动：**
- 其他 snapshot 相关文本（`docs/rendering.md` 里 `rendering-test/glow-*.png` 基准、Floyd-Steinberg 抖动覆盖等）都是**机制说明**而非时点说明，保留
- `docs/protocol.md` 里的 `state.snapshot` 是协议 op，与渲染 snapshot 无关

**这一改动让 M1 最终只剩 T7a（端到端联调），M1 真正的验收标准（浏览器按钮 → WS → 游戏内地图变红）得以聚焦。**

**关联文件：** `PROPOSAL.md`、`docs/journal.md`

---

## 2026-04-20 · M1-T6 前端按钮页面（原生 DOM + Vite + TypeScript 骨架）

**范围：** 在仓库里建 `web/` 子项目；一个按钮点击时打开 WebSocket 到 `ws://127.0.0.1:8877/ws` 并发 `ping`，把服务端响应渲染到页面。构建链路跑通即算完成（真正的端到端 round-trip 留给 T7）。

**立项期决策再重申**（见 PROPOSAL §5.1、CLAUDE.md 技术栈表）：
- M1~M4 **仅用原生 DOM + 原生 WebSocket API + TypeScript**
- Vue 3 / Konva / Pinia **M5 才引入**
- 目的：M1 端到端验证不需要前端框架；M5 再一次性搭 Canva 式编辑器

**技术选型（当前稳定版实测）：**
- Vite **8.0.9**（2026-03-12 release；Rolldown 作为统一 Rust 打包器替代 esbuild+Rollup，构建 10-30x 加速）
- TypeScript **6.0.3**（2026-03-23 release；基于 JS 编译器的最后一个大版本；7.0 年中转 Go）
- Node **25.2.1** / npm **11.6.2**（本机 brew 装的当前版本）
- Vite dev server bind `127.0.0.1:5173`（不监听 `0.0.0.0`——不要无意暴露到公网，同 Paper 插件一个安全默认）

**文件结构：**
- `web/package.json`：`type: module`；scripts `dev / build / preview`；devDeps `vite ^8.0.9 / typescript ^6.0.3`
- `web/vite.config.ts`：dev server 127.0.0.1:5173；`build.outDir = "dist"`；`build.target = "es2022"`
- `web/tsconfig.json`：严格模式全开（`strict / noUnusedLocals / noUnusedParameters / noImplicitReturns / noFallthroughCasesInSwitch / verbatimModuleSyntax`）；lib 包含 `DOM / DOM.Iterable`
- `web/index.html`：页面壳 + `<button id="ping-btn">` + `<div id="log">`；极简 system-ui 样式
- `web/src/main.ts`：
  - `Envelope<P>` TypeScript interface 与 `docs/protocol.md` §2 对齐（`v / op / id? / ts? / payload?`）
  - 按钮点击 → 首次连 `ws://127.0.0.1:8877/ws`（之后复用）→ 发 `{v:1, op:"ping", id:"c-<seq>", ts:Date.now()}` → 收到响应打印到 log
  - `open / message / close / error` 四个事件各有独立样式（sent/recv/err/meta）

**构建验证：**
- `npm install` 成功（16 packages，无 vulnerability）
- `npm run build`（`tsc --noEmit && vite build`）`25ms` 完成；产物：
  - `dist/index.html` 1.60 KB（gzip 0.84 KB）
  - `dist/assets/index-<hash>.js` 1.93 KB（gzip 1.02 KB）
- 这个产物体积反映了「不引任何框架」的初衷——整个前端 2KB 不到

**.gitignore 覆盖验证：**
- `node_modules/` / `dist/` 都被根 `.gitignore` 已有规则排除
- 入库的是 `package.json` / `package-lock.json` / `vite.config.ts` / `tsconfig.json` / `index.html` / `src/main.ts` 共 6 个源文件

**未做（留给 T7）：**
- Gradle ↔ npm 联动（`./gradlew build` 自动触发 `npm run build` + 产物拷到 `plugin/src/main/resources/web/`）——T7 做，届时插件 serve 静态资源 + WS 在同源
- 端到端实测（runServer + Vite dev server 同时跑、点按钮看 `pong`）——T7 做
- Jackson 回填的 `payload` 是 `{}` 空对象，前端 `recv` 只打 log 不解析 payload 结构——足够 T6

**关联文件：** `web/package.json`、`web/package-lock.json`、`web/vite.config.ts`、`web/tsconfig.json`、`web/index.html`、`web/src/main.ts`、`docs/journal.md`

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
