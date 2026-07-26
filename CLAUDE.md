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
| Java | **21** 编译目标（跑 1.21.x 用 Java 21、跑 26.x 用 Java 25；0.9.5 起一份 jar 通吃） |
| Paper API | **1.21.11** 编译目标（`1.21.11-R0.1-SNAPSHOT`；可 `-PpaperApi=`/`-PjavaVer=` 切换，CI `compat-26` job 对 26.2 编译守卫） |
| Gradle | **9.4.1** |
| `paperweight-userdev` | **2.0.0-beta.21**（同一版本即支持 1.21.x 与 26.x dev bundle） |
| PacketEvents | **2.13.0**（多版本：同时支持 1.21.x + 26.1 + 26.2）。**0.9.11 起 `compileOnly` 不打包**——PacketEvents 是 GPL-3.0，打包会污染本项目 MIT；服主单独装 PacketEvents 插件（`paper-plugin.yml` 声明为必装 `required` 依赖，PE 插件自负 init/terminate） |
| Javalin | **7.2.2**（6 已过时，不用；0.9.16 rc3 从 7.1.0 升 7.2.2，含 Jetty bugfix，本体 + testtools 同步） |
| 插件描述文件 | **`paper-plugin.yml`**（不用 `plugin.yml` 旧格式） |
| 本地测试服 | `./gradlew runServer`（paperweight-userdev 提供） |

其余：HikariCP + JDBI + SQLite、**jackson-dataformat-yaml（2.22.1，与 jackson-databind 同版本；两模块必须对齐——dependabot 曾只升 databind、须手动补 yaml）**、JUnit 5 + MockBukkit、AWT/Graphics2D。

> **YAML 解析用 jackson-dataformat-yaml，不用 SnakeYAML。** 项目已全面 Jackson 化（ProjectState / PatchOp / WallRepo 都靠 Jackson），同 ObjectMapper 配置 + record 自动 mapping 可省 ~300 行手工 YAML→Map 转换 + 校验。安全上 jackson-dataformat-yaml 默认即关闭 polymorphic typing，不存在 SnakeYAML SafeConstructor 才解决的 `!!java/*` tag RCE 面（见 `docs/security.md §4.3`）。

**前端**：Vite + TypeScript + Vue 3 + Konva + Pinia。

## 文档先行

**契约类（已定稿，代码必须与之一致）：**

- `PROPOSAL.md` — 立项总纲
- `docs/architecture.md` — 架构与核心机制
- `docs/protocol.md` — WebSocket 协议
- `docs/rendering.md` — 渲染管线与双端一致性
- `docs/template-pack.md` — 模板系统（`.canvas` pack；旧 YAML DSL 已退役，见 `archive/template-spec.md`）
- `docs/data-model.md` — SQLite / PDC / `.canvas` 格式
- `docs/security.md` — 威胁模型与安全规范
- `docs/dynamic-data.md` — 变量系统 + Push API + 四层数据源
- `docs/timeline.md` — 时间轴编辑器设计总纲（配套 rendering.md §9 / protocol.md / data-model.md §2.4.2 / architecture.md §5.5）
- `docs/scripting.md` — 视觉运行时（积木脚本）总纲；`docs/scripting-tween.md` 补间动画
- `docs/import-export.md` — `.canvas` 工程导入导出 + SVG 矢量导入

**操作类：** `deployment.md` / `development.md` / `api.md` / `troubleshooting.md`

**规则：**
- 写代码前先对照契约文档检查实现意图
- 要改契约 → **先改 `docs/*.md`，再改代码**
- 文档里的「未决问题」清单，实现时回填答案并从列表移除

## Git 提交约定

1. 所有 commit 必须 SSH 签名（身份与密钥由本地 `.git/config` 持有，**不动全局**）
2. **禁止** `Co-Authored-By: Claude`（以及任何形式的 Claude 署名）
3. **每次 commit 后立刻 `git push origin main`**——不堆积、不集中推
4. **每次修改必须在 `docs/journal.md` 顶部追加一条**（日期 · 范围 · 改动 · 关联文件）
5. 签名失败**不要用 `--no-gpg-sign` 绕过**，先查原因
6. 签名验证：`gh api /repos/HyacinthHaru/HikariCanvas/commits/<sha> --jq '.commit.verification.verified'` 应返回 `true`

## 架构纪律（26.x 升级保障）

Paper 26.1 起移除插件的 Spigot 重映射，任何碰 NMS 的插件 26.x 必崩。为让未来升级只改版本号、不动代码：

1. **禁用 NMS。** 任何 `net.minecraft.*` / 服务端内部类一律禁止；只用公开 Bukkit API + PacketEvents
2. **PacketEvents 调用集中。** 所有 `sendPacket` 走 `plugin/deploy/MapPacketSender.java` 一个类，别的模块不直接碰 PacketEvents
3. **Mojang mappings 输出。** `paperweight-userdev` 默认行为，不开 reobf

见 PROPOSAL.md §5.2.6 完整说明。

## 其他技术决策

- **预览地图池**是技术核心：编辑期间**只刷像素、不新建 MapView**，避免 `idcounts.dat` 膨胀
- **双端渲染一致性**：浏览器 Canvas 与 Java Graphics2D 用同一 TTF 文件、禁抗锯齿；TextLayout 两端走 **`charAdvance(fontId, ch, fontSize)`** —— 构建期 `generateGlyphMetrics` 用 AWT 算每个内置字体 BMP 范围 advance → 紧凑 JSON 双端共享（jar `/fonts/{id}.metrics.json` + `web/public/fonts/{id}.metrics.json`）；运行时 `advance = round(baseAdv × fontSize / baseSize)`。用户字体（`plugins/HikariCanvas/fonts/*`）启动期 `FontMetricsTable.registerRuntime` 现场用同款 AWT 算法计算 + 内存表 + `GET /api/font/metrics?id=...` 给前端。缺字 / 表未到位 fallback 旧 `canonicalCharWidth`（ASCII=0.5×fontSize, CJK=fontSize），仅在首次渲染窗口或缺字时生效
- **帧率策略**：静止 0fps · 输入防抖 100ms + 5fps 上限 · 提交全量。**这是静态招牌默认值，不是硬上限**；时间轴会参数化到 30fps，但遵守"不自动降级"哲学（服主主动配，系统不偷偷压）
- **性能哲学（"工具不是保姆"）**：默认服主有充足性能 + 知道自己在做什么。① 数据透明不替服主决策 ② 不自动降级（config 上限仅作安全上限，非自动调优）③ 不擦屁股（网络 / 带宽 / 压缩比 / 服主没开的配置一律不测不估）。详见 `PROPOSAL.md §2.1`（产品哲学）+ `§5.2.7`（Benchmark 4 原则）+ `docs/dynamic-data.md §13`
- **网络默认绑 `127.0.0.1`**；公网部署必须 nginx/Caddy 反代 + TLS
- **字体**：只打包 SIL OFL 1.1 协议字体；内置 **22 枚字体矩阵**：
  - 中文正文：`source_han_sans`（黑体）/ `source_han_serif`（宋体）/ `ark_pixel`（12px 像素）
  - 中文艺术：`smiley_sans`（得意黑）/ `ma_shan_zheng`（马善政毛笔楷书）/ `zcool_xiaowei` / `zcool_kuaile` / `zcool_qingkehuangyou` / `lxgw_wenkai`（霞鹜文楷手写）
  - 西文正文：`inter` / `noto_serif` / `jetbrains_mono` / `fira_code`
  - 西文装饰：`comic_neue` / `pacifico` / `lobster` / `bangers` / `shadows_into_light` / `caveat` / `dancing_script` / `overpass` / `bebas_neue`
  - 缺口：中文等宽（source_han_mono 仅 122MB OTC 合包），走 `plugins/HikariCanvas/fonts/`（用户字体 metrics 自动生效）
  - Gradle `downloadFonts` 构建期抓到 `build/downloaded-fonts/`（SHA-256 pin + **URL 固定到 commit SHA 防上游漂移**）→ `processResources` 合并进 shadow jar 供后端 `FontRegistry` 使用 + `generateGlyphMetrics` 自动按 bundledFonts iterate；仓库不入字体文件，`.gitignore` 排除
- **字体加载单轨**：所有字体（内置 + 用户）统一走 HTTP + FontFace API 动态加载。后端 `GET /api/font/file?id=X` 返字体二进制 + `GET /api/font/list` 返 metadata 数组。前端 `FontLoader.ensureLoaded(fontId)` 用 `new FontFace + document.fonts.add` 注册；`onFontLoaded` 回调触发 `requestDraw`。**不要**再引入 `style.css` @font-face 或 `PreviewRenderer.fontFamily` 白名单（那是加字体时漏修的 bug 根因）
- **构建期 palette**：Gradle `generatePalette`（独立 `generator` sourceSet）从 Paper `MapPalette` 导 248 色 JSON 到 classpath 根；后端 `PaletteLut` + 前端 `PaletteLut`（镜像）都读它，32³ Lab LUT，O(1) 匹配

## 版本进度

**权威来源 = `docs/journal.md`**（倒序，每会话一条）。当前版本串见 `build.gradle.kts`。

里程碑叙事 / 各版本分期日志 / 路线图表格已从本文件移除——它们在 journal 和 git 历史里有完整记录，写在这里只会滞后失真。要查「某功能什么时候做的、怎么做的、踩过什么坑」→ grep `docs/journal.md`；要查「现在做到哪、还剩什么」→ 读 journal 顶部几条 + `git log`。

本文件只保留**代码和 git 历史里读不出来的固化决策与契约**（见下）。远期设计见 `docs/dynamic-data.md §13`（版本顺序依赖、Benchmark 4 原则）+ `docs/scripting.md`。

> **一条长期结论**：脚本是上层（条件分支 + 副作用），时间轴是被编排的素材（脚本可 playTimeline），同画布共存；早期"一画布二选一"的设想已作废（见 `docs/scripting.md` D2）。

## 已固化的架构决策

按子系统归并。**这些是代码里读不出来的"为什么"，改动前先读。**

### 会话 / 鉴权 / 数据安全

1. **后端编辑 op 不读 lock**：lock-aware 鉴权只在 `SessionManager.open` 路径（方案 C），后端编辑 op 一律透明放行——为动态画板（PAPI / 数据源 P-1/P-3 路径）留通路。详见 `docs/architecture.md §13`
2. **草稿 wall = 协作中间态**：未锁 wall（`lockedAt=null`）任何有 `canvas.edit` 的玩家可 open，多人可接力编辑；只有 owner 可触发 lock；lock 后非 owner 不能 open（除 `canvas.admin.bypass-lock`）。owner-only 草稿 ACL 属未来协作 scope。详见 `architecture.md §3.6.1`
3. **会话级 IP 绑定（方案 B）**：Token **不**绑 IP（confirm 阶段无 HTTP context）；`Session.boundIp` 首次 auth 时 CAS 绑定，后续帧不一致拒 4001。绑 session 不绑 token——token 已单次使用 + 短 TTL，再绑 token 是冗余且阻塞合法重连。详见 `security.md §2.5`
4. **动态画板必须走 P-1（渲染期占位符）或 P-3（Plugin API + Provider）**；反模式 P-2（定时 patch ProjectState）禁用。详见 `architecture.md §13`

### 持久化 / 构建

5. **Schema forward-only**：首次 stable（≥1.0.0）发版后禁破坏性 DDL + 强制 auto-backup（`MigrationForwardOnlyTest` 守卫，V018 起冻结，V001-V017 grandfather）。详见 `data-model.md §6.6`
6. **shadowJar 全 relocate**：jackson / caffeine / jdbi / hikari / javalin / jetty / snakeyaml → `ac.haru.hikaricanvas.shaded.*`；**`org.sqlite` 不 relocate**（JNI 保护）
7. **HikariCP maxPoolSize=4 保持**：SQLite 单写但允许并发读；4 池让 read-heavy 路径（preview / quota check）不阻塞主线程；写靠 `busy_timeout=5000` + `leakDetectionThreshold=30s` 兜底。缩到 1 会让任何长查询阻塞所有后续连接获取
8. **MapPool 按 world UUID 分桶**：wall 与 map 必须 world 一致（强校验，跨世界绑定抛异常）；config `map-pool.per-world: {}` 配每世界 size。
   **地图归还的总不变式：已被某个存在的 walls 行认领的地图，绝不能回 FREE。** 由此派生两条：
   - `WallRestorer` 失败按 bind 前后分处置（0.9.17 细化；原表述「失败必须 `releaseToFree`」过粗，对 bind 之后的情形是错的）——**bind 尚未成功**（world 解析不到 / `bindToWall` 抛）→ 借到手的 mapId 全部 `releaseToFree`，不留半态预留；**bind 已成功、后续渲染炸了** → **保留绑定**（判据 `bindCommitted` 标志）。理由：walls 行还在、`detectLeaks` 认得它不会回收，本就不存在泄漏；放回 FREE 反而会让下次 confirm 把同一张图借给别的 wall（两墙共用互相覆盖像素），而原 wall 的 `map_ids` 指向已被抢走的地图，下次启动 `bindToWall` 直接被拒，这面墙永久恢复不了
   - `SessionManager.deleteWall` **先删 walls 行、再放地图**（0.9.17 修）。原来反着来，而 `WallRepo.delete` 曾是吞异常的 void——删行失败时地图已进池子、walls 行还指着它们，同样落进跨墙串台。删行失败即整个中止（宁可留一面删不掉的墙让玩家重试）
   - 原「防 `idcounts.dat` 膨胀」的意图由这条总不变式 + `detectLeaks` 的防呆共同覆盖

### 变量系统

9. **Push > Pull**（性能 / 解耦 / 扩展性）；**变量是 string**（业务在插件侧）
10. **用户变量持久化（DB）；插件 / 系统 / PAPI 变量内存态**
11. **resolve 不在主线程**（ProjectionThrottler 用 cache）
12. **namespace 严格隔离**（防 plugin spoof）；`user` / `system` / `papi` / `scoreboard` / `schedule` / `userglobal` 为保留 namespace，外部插件禁推——想全服共享应用自己的 namespace
13. **fallback 链**：cached → `${var:X|fallback=...}` → `Variable.default` → `"???"`
14. **`userglobal` = 全局用户变量**：不带冒号 + wallId，与 `user` 同谱系但全服唯一；owner-only + admin override 5 权限节点；配额 per-owner 500 + 全服 10000（config 可调）；**`.canvas` 工程文件不含 userglobal**（服务器级状态，跨服务器无意义）；state.patch 广播全 session
15. **变量别名仅 UI 层**：per-wall，全 namespace 通用，**不参与 `${var:...}` 解析**；chip 显示优先级 alias > currentValue > fallback > defaultValue > UNRESOLVED

### 铁路 / 时刻表

16. **rail 与 manual 共享 `schedule:*` namespace + skip predicate 协调**：RailScheduleProvider 接管的 wall 自动让 ManualSchedule 跳过 push，避免双写同 key
17. **每站时刻 = `rail_timetable` 精确读**（不走 travel_seconds 均匀推算），支持站间不均 + 大站快车跳站 + 区间车不到全线
18. **`wall_rail_bindings.line_id IS NULL` 走 fallback**（兼容只用 ManualSchedule 的旧 server）；车次详情所有写操作 ACL 按 line owner 判 own/any

### 渲染 / 编辑器

19. **双层渲染**：顶层 Konva（透明 hit-test + Transformer，纯交互层）+ 底层 Canvas 2D `PreviewRenderer`（dither + 248 调色板的实际像素输出）。**底层重绘依赖 `requestDraw()` → `watch(project.state, {deep:true})`**——修任何"拖动时视觉不动"的 bug 必须先确认 store 是否实时更新
20. **`Canvas.background` 是 Fill 联合类型**（Solid / Linear / Radial）；Jackson 自动兼容旧 hex 字符串；alpha<1 编辑器用 CSS 棋盘格提示
21. **主 buffer `TYPE_INT_ARGB`**：让 alpha 贯穿到 `toPaletteSlice` 的 4 参 `matchColor`（透明背景），内存 +33% 可接受
22. **italic = shear transform，bold = stroke 包装**：双端走数学等价的线性变换 + 描边路径，避免 synthetic bold 双端像素不一致；**bold 像素字体跳过描边**（NN 路径走 mask 不是 outline）
23. **`'hand'` 是非绘制工具**（与 `select` / `move` 并列）；Space 按住临时切（闭包保存原工具 + window blur 兜底防卡死）
24. **`useSnapManager` 是公共 snap 能力**（drag + resize 共用）：候选轴 = canvas 6 锚点 + element 4 边 + 4 中点 + grid 倍数；distribute 仅两侧最近邻，与 axis snap 互斥；shift 临时 bypass
25. **resize snap 走 `boundBoxFunc`**（不是 transformend）：每帧比对 newBox vs oldBox 找"动的边"分别 apply delta，任何锚点都不视觉跳动；rotation≠0 跳过
26. **深色主题 foreground 走 `--ctp-crust`**：1 行 CSS token 修全局对比度（影响约 20 处按钮）

### Live Paint（油漆桶）

27. **Live Paint = 前端独占功能**（`rendering.md §1/§8` 双端镜像纪律的显式例外）：拓扑计算仅浏览器 Web Worker 跑，输出的 PathElement 由后端常规 PathRenderer 渲染。理由：输出已在双端镜像协议内；拓扑算法不参与最终像素；Java AWT 无 planar subdivision 等价物
28. **用 `polygon-clipping` 库做 boolean op，不自写 DCEL**（B-medium+ 路线）：覆盖 95% 用例，浮点精度风险远低。**B-advanced 自写 DCEL 覆盖剩余 4% —— 性价比低，不做**
29. **vector-fill 决策 A**：点击元素内部 = 改该元素 fill 字段（rect/circle/shape/path 支持；text/image/brush 提示不支持）；点击非闭合空白 = 创建新 PathElement。不引入新协议 op
30. **顶点 RDP 简化**：`PathDValidator` 实际限制 ~240 顶点；超阈走迭代式 RDP（防爆栈）+ tolerance 阶梯 0.5→16
31. **退化几何 fallback**：polygon-clipping 失败时返 `{gaps:[], degraded:true}` 不假装可用，UI 提示"无法识别此区域"

### 模板系统

32. **模板 = `.canvas` pack 单轨**（旧 YAML DSL 已退役）：pack = `manifest.kind="pack"` + `params.json` + `project.json`；套用复用 `ProjectImporter`，模板天然带 timeline / script / asset / 安全栈
33. **参数替换是纯文本级**：数值字段以字符串形态写占位符（`"x":"${off}"`），替换后靠 Jackson scalar coercion 解回数值——故替换不挑字段类型。替换值按 JSON 字符串转义（多行 / 带引号文案不破坏结构）
34. **`params.json` type 体系**：string / text / int / float / bool / color / enum / font，校验与 coerce 在 `PackParamResolver`。**这是 1.0 后冻结的契约，扩形态会破坏已导出的用户 pack**
35. **DB 列名 `yaml_path` 沿用**（现存 `.canvas` 路径，Java 侧用中性 `filePath` 映射）——forward-only 守卫禁止改名

### 脚本 / 时间轴

36. **自写积木画布，Blockly 否决**；后端是唯一执行器 + 真试跑轨迹高亮；命令走服主白名单模板。D1-D8 见 `docs/scripting.md`
37. **补间架构 A：独立 `TweenScheduler`**，与时间轴共存靠 `isWallAnimating` 分流
38. **缓动数学权威在 `rendering.md §9`**，双端逐位等价（第三方参照向量 + 多帧 snapshot 在 CI 守卫）；数值走 `StrictNumber` 单一权威 + 两端 int clamp

### 图片上传 / 蒙版

39. **mask 数据模型 = SVG path d 字符串**（`Mask { d, inverted }`），复用 `PathDValidator`（M/L/Q/C/Z 子集 + 顶点 ≤64 + 坐标 ≤10000），相对 element bbox `0..w / 0..h`
40. **先 dither 再 mask**（dither 在 mask 内部像素，避免边缘羽化错位）
41. **content-hash 内容寻址** `sha256[:16]`：跨 wall 引用同一文件不重复存；删 wall 不立即清文件，靠 LRU（每次 upload 前检查总配额，不做周期 scheduler）
42. **ImageIO 解码隔离**：`ExecutorService.submit(...).get(200ms)` 防压缩炸弹 / 死循环；超时拒 `UPLOAD_REJECTED`。上传 6 层校验栈 + 配额三层（per-wall / per-player 24h / total disk）走事务原子

## wall 模型（2026-04-27 定稿）

早期"编辑 → commit 永久固化"的二段式模型已废止（二次编辑已发布画死路 + commit 后状态机污染）。现行模型：

1. **一画一行 `walls` 表**：每行有稳定 `wall_id`（`w-<8hex>`，玩家可见）+ 可选 `alias`。DB 列 `published_at` **语义 = lock 时间戳**：`null` = 可编辑，非 `null` = 已锁定。`owner_uuid` 为作者权限依据
2. **MapPool 两态** `FREE` / `RESERVED`：owner 统一 `wall:<wall_id>`，wall 占的 map 一直占着直到 `/canvas delete`，不自动释放
3. **命令族**：`edit / confirm / cancel / open <id|alias> / list / alias <name> / delete <id> [confirm]`。`commit` / `publish` / `unpublish` **均已废止**（lock 由前端 TopBar 触发 WS op）。`delete` 需 30s 内二次确认
4. **wand 瞄已有 ItemFrame**：本插件挂的 → 不当 OCCUPIED，先 ActionBar 提示再次操作才打开二次编辑；第三方 ItemFrame 仍 OCCUPIED 拒绝
5. **排他锁**：一墙一时刻一个活跃 session。多人协作（OT/CRDT）超 scope，不做

## lock 状态

**目标**：作者能"只读冻结"自己的画防误编辑；其他玩家拿到 `/canvas open <wall_id>` 也无法解锁。但保持后端编辑路径与 lock 完全解耦——未来动态化展示想在锁定 wall 上更新数据时不被卡。

1. **锁状态 = 元数据**：`walls.published_at` 非 null 即锁定（时间戳 = lock 时间）
2. **后端编辑 op 不读 lock**：element.* / canvas.* / layer.* 全部透明，不因 lock 拒绝
3. **WS op** `wall.lock` / `wall.unlock`，**owner-only**（非 owner 拒 `FORBIDDEN`）
4. **前端是 lock 的唯一执行者**：locked 时 RightPanel 控件 disabled、Transformer 隐藏、拖动 / 删除快捷键 / drawTool 失效
5. **isOwner 判定**：ready payload 携带 `ownerUuid` + `selfUuid`，前端 computed；非 owner 看不到解锁按钮，无路径绕过
6. **ItemFrame PDC 不写 published_at**；所有 wall 的 ItemFrame 一致由 `canvas.modify` 权限保护

## 构建 / 开发流程速查

```bash
./gradlew :plugin:shadowJar         # 完整产物；前端构建已联动。首次要下 22 个字体，约 10min
./gradlew :plugin:runServer         # 本地 MC dev server，挂新 shadow jar
./gradlew :plugin:test              # 后端测试；渲染 snapshot baseline 变时 rm expected/*.png 重建
cd web && npm run test              # 前端 vitest
```

三个平台通用（`npm` 调用按平台取 `npm` / `npm.cmd`）。渲染 snapshot 的 baseline 与生成它的 AWT
字形栅格化环境绑定，换机器可能出现文字类 fixture 失败；要判断是不是自己引入的回归，把改动
`git stash` 掉在干净树复跑，对比失败集合是否一致。

前端状态管理：`web/src/stores/{network,project,ui}.ts`（Pinia setup stores）。
WS 通讯封装：`web/src/network/wsClient.ts`（单例 `WsClient`）。
浏览器 console 调试：`window.__hk.send("op", payload)`（仅 DEV）。

## CI / Release

GitHub Actions 2 workflow：

- **`.github/workflows/ci.yml`**：push/PR 到 main 触发。单 job 跑 frontend（npm ci + vitest + vite build）+ backend（`:plugin:test` + `:plugin:shadowJar`）+ 上传 jar artifact 30 天；另有 `compat-26` job 用 Java 25 对 Paper 26.2 API 编译守卫
- **`.github/workflows/release.yml`**：tag `v*` 触发 → 跑测试 + shadowJar + 创建 GitHub Release + 附 jar。含 `-`（如 `v1.0.0-rc.1`）自动标 prerelease
- **环境锁**：Java 21 Temurin + Node 22 LTS（不用 Node 25，已知卡 vue-tsc）
- **cache**：`gradle/actions/setup-gradle` + `setup-node` 自带，不显式配 `actions/cache`
- **`npm ci || npm install` fallback**：macOS 生成的 lock 缺 Linux 平台传递依赖时，严格 `npm ci` 会失败——fallback 保证 CI/release 跑通（实跑中确实常命中）
- **jar ≈ 86MB，本地构建 = release 构建**（0.9.17 起）：`copyWebToResources` 显式 `exclude("fonts/**")`，jar 内容与任务执行顺序彻底解耦。此前本地跑过 `syncFontsToWeb` 会把字体多塞一份进 `web/fonts` 被 vite 烤进 jar（本地 ≈152MB vs release ≈90MB），而 CI release 路径不跑 `syncFontsToWeb` 所以躲过了——那是**任务序决定 jar 内容**，本身就是不可复现构建。字体二进制走后端 `/api/font/file` 端点，`web/fonts/*.ttf` 纯冗余

## 远期 TODO（不做但记下）

- 图层缩略图（per-layer rasterize 端点 + 缓存）、图层颜色标签
- 图层 mask / group / smart object（PS-style，独立大版本）
- 多人协作（OT/CRDT）
- mask 拖动编辑 / lasso 自由绘制、蒙版羽化、多 mask 组合
- 参数标记完全体（全字段勾选 / 画布点选 / 渐变 stop 嵌套）
- 各字体 `OFL.txt` 拷进 jar 达成 in-artifact 合规
