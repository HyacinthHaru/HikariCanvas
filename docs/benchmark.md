# HikariCanvas 性能 Benchmark 运维指南

> HikariCanvas 0.5.0 "纯服务端性能 Benchmark" 完整指南。给服主一套**原料 + 公式**，回答
> "我这台机器能撑多少画布"——但**不替你下结论**。
>
> 配套文档：
> - `docs/dynamic-data.md §13.3`（设计 / phase 分解 / 4 锁定决策）
> - `PROPOSAL.md §2.1`（"工具，不是保姆" 哲学）+ `§5.2.7`（Benchmark 4 原则）

## 目录

- [1 这是什么](#1-这是什么)
- [2 快速开始](#2-快速开始)
  - [2.1 命令族](#21-命令族)
  - [2.2 selector / iterations / warmup](#22-selector--iterations--warmup)
- [3 报告怎么读](#3-报告怎么读)
  - [3.1 三件产物](#31-三件产物)
  - [3.2 逐场景 percentile 表](#32-逐场景-percentile-表)
  - [3.3 per-element 边际成本](#33-per-element-边际成本)
  - [3.4 GC 增量](#34-gc-增量)
  - [3.5 环境卡（数字脱离环境无意义）](#35-环境卡数字脱离环境无意义)
- [4 50mspt 公式与交互计算器](#4-50mspt-公式与交互计算器)
- [5 为什么没有自动门禁 / 自动降级](#5-为什么没有自动门禁--自动降级)
- [6 用实测容量设 config 软上限](#6-用实测容量设-config-软上限)
- [7 Benchmark 4 原则速查](#7-benchmark-4-原则速查)
- [8 已知局限与 P4+ 精化](#8-已知局限与-p4-精化)

---

## 1 这是什么

HikariCanvas 把 `ProjectState` 渲染成地图像素的核心是 `CanvasCompositor.rasterize`（element draw +
text layout + dither）再走 `toPaletteSlice` 量化成 248 色地图调色板字节。这两段是**纯软件、纯 CPU /
内存**的成本——画一面墙要花多少毫秒、分配多少内存、给 GC 多大压力，全在服务端可控范围内。

Benchmark 就是把这两段成本测出来。它在后台线程跑**真实渲染代码路径**，输入是程序生成的假 `ProjectState`
（21 个确定性场景），**不占地图池、不放 ItemFrame、不需要真实玩家、不污染 `idcounts.dat`**——可重复、随时
跑、跑炸也不影响在线玩家。

**给谁用**：想知道"我这台机器能撑多少画布"的服主。

**一句话哲学**：报告只摊开**描述性测量**（环境 / 逐场景分位 / per-element 边际 / GC）+ 一条预算公式，
**不给"你能开 N 个 wall"的结论数字**。50mspt 预算交给你用自己的机器参数代入算——这是"工具，不是保姆"
（`PROPOSAL.md §2.1`）在性能测评上的落实。

> Benchmark **只测服务端能控制的 CPU + 内存**，**绝不碰网络**。带宽 / 压缩比 / RTT / 丢包 / 你有没有开
> zlib——一律不测、不估、不算（见 [§7](#7-benchmark-4-原则速查)）。

---

## 2 快速开始

```
/canvas bench run        # 后台跑全部 21 个场景，结束在聊天栏给彩色摘要
```

跑完控制台 / 聊天栏会打印 `Saved to: .../benchmarks/<时间戳>/`，进该目录用浏览器打开 **`report.html`**——
里面有环境卡、逐场景成本表、两张内联 SVG 条形图，和一个 **50mspt 交互计算器**（填你自己的 mspt / tps /
份额 / fps 实时算每个场景的"可载 wall 数"）。零外链、零 CDN，离线也能看。

### 2.1 命令族

权限节点 `canvas.bench`（默认仅 op）。

| 子命令 | 作用 | 同步 / 异步 |
|---|---|---|
| `/canvas bench list` | 列出全部 21 个场景（id / category / tile 尺寸 / 主元素类型） | 同步，廉价 |
| `/canvas bench run [selector] [iters] [warmup]` | 跑压测，落 `benchmarks/<时间戳>/` 三件产物，回主线程发摘要 | **异步**（后台守护线程） |
| `/canvas bench report [id]` | 读最近（或指定时间戳）的 `report.json` 重新打印摘要 | 同步 |
| `/canvas bench clear` | 清空 `benchmarks/` 目录内容 | 同步 |

几条纪律：

- **同一时刻只允许一个 benchmark**。`run` 进行中再 `run` 会被拒（并发压测互相争 CPU 干扰各自计时）。
- `run` 整段在名为 `hikari-canvas-bench` 的守护线程跑，**只在结束时回主线程**发消息（Bukkit 调度契约要求
  消息 / 调度 API 在主线程调）。压测期间服务器照常运行。
- `clear` 是**手动**清理入口——系统**永远不自动删**报告（"不擦屁股"哲学，见 `PROPOSAL.md §2.1` 原则 3）。
  报告攒多了占盘，由你自己决定何时清。`clear` 只删 `benchmarks/` 目录内部，绝不越界。

### 2.2 selector / iterations / warmup

`run` 三个可选参数：

| 参数 | 取值 | 默认（省略时） |
|---|---|---|
| `selector` | 场景 id（如 `subway-sign-2x1`）/ category 名（`element-isolation` / `effect` / `mixed`）/ `all` | `all` |
| `iters` | 测量轮数（≥1） | 20（`BenchmarkConfig.quick()`） |
| `warmup` | 预热轮数（≥0，触发 C2 JIT 编译，结果丢弃） | 5（`BenchmarkConfig.quick()`） |

例：

```
/canvas bench run                       # 全 21 场景，20 measured + 5 warmup
/canvas bench run subway-sign-2x1       # 只跑地铁屏场景
/canvas bench run element-isolation     # 只跑 9 个单元素隔离场景
/canvas bench run mixed 200 20          # 混合场景，200 measured + 20 warmup（更稳的分位数）
```

`selector` 支持 tab 补全（all + 3 个 category + 21 个场景 id）。selector 无匹配场景时早退提示，不空跑。

**iters / warmup 怎么选**：warmup 不够 → 首轮解释执行的耗时污染均值（C2 还没编译）；measured 太少 → p95 /
p99 抖动大。默认 5+20 够日常冒烟；要稳的分位数（如对比两次改动）用 `200 20` 起步。

---

## 3 报告怎么读

### 3.1 三件产物

`run` 在 `plugins/HikariCanvas/benchmarks/<生成时刻 epoch ms>/` 落三个文件：

| 文件 | 内容 | 给谁 |
|---|---|---|
| `report.json` | 聚合数据（schema v1，Jackson pretty） | 机器可读 + CI baseline 比对 + 你自己写工具的原料 |
| `summary.txt` | CLI 文本摘要（环境 + config + 逐场景 percentile 表 + per-element 表 + GC） | 控制台 / 终端人读 |
| `report.html` | 完全自包含的 HTML5（内联 `<style>` + `<script>` + SVG 图，零外链） | 浏览器可视化 + 交互计算器 |

时间戳目录名就是报告 id，`report [id]` 与 `report.html` 都用它定位。

### 3.2 逐场景 percentile 表

报告主体是每个场景一行，分两段成本（**rasterize 与 palette 不混用一个单位**）：

| 列 | 含义 |
|---|---|
| `raster p50 / p95 / p99` | `rasterize` 单次耗时分位数（ms）——element draw + text layout + dither |
| `palette p50 / p95 / p99` | 全 tile `toPaletteSlice` 量化耗时分位数（ms）——RGB → 248 色 LUT 匹配 |
| `alloc MB/it` | 每次迭代平均分配（MB，`ThreadMXBean`）；平台不支持时 `n/a` |
| `elements` / `tiles` | 该场景元素实例数 / map tile 数 |

p50/p95/p99 用**线性插值法**（R-7，同 Excel `PERCENTILE.INC` / numpy `linear`），小样本下比"最近秩"更平滑。
关注 **p95**——它是预算公式的输入，代表"绝大多数帧的成本上界"。

21 个场景分四类，覆盖全渲染路径：

| 类别 | 场景数 | 内容 |
|---|---|---|
| 单元素隔离 `element-isolation` | 9 | text / variable-text / rect / circle / star / path / image+mask / brush / icon，各铺满一面 2×2 墙 |
| 特效 `effect` | 5 | gradient / dither / blend / opacity / text-effects（描边 + 阴影 + 发光） |
| 真实混合 `mixed` | 3 | subway-sign（地铁屏）/ welcome-wall（欢迎墙）/ dense-dashboard（密集仪表盘 ~50 元素） |
| 尺寸梯度 `mixed` | 4 | 同一密集构图在 1×1 / 2×2 / 4×4 / 8×8 下渲染，让成本随像素数缩放、可拟合回归曲线 |

所有场景**确定性生成**（固定 seed `0x48494B415249` = ASCII "HIKARI"），同一构建多次跑产生结构相同的场景——
故两次报告的差异只来自机器抖动，不来自输入随机，可直接比对。

### 3.3 per-element 边际成本

报告单独给一段 **per-element 边际成本**（仅从 9 个单元素隔离场景推导）：

```
marginal = (隔离场景 rasterize 均值 − 同尺寸空白基线均值) / 元素数
```

空白基线 = 一个 `2×2` 空白画布、用**完全相同的 config** 计时的 rasterize 均值——扣掉 buffer 分配 / clear /
palette 等**固定开销**，剩下的才是"多画一个该元素"的**净成本**。这是 per-element 分解的核心价值：让你看清
**哪类元素贵**。例如一面贴满蒙版图片的墙 vs 纯文字墙，成本可以天差地别；text 有两个场景（普通文字 +
含 `${var:...}` 占位符），分开列出让占位符解析的额外成本可见。

> 边际成本可能因测量噪声**轻微为负**，报告**原样保留不钳零**（钳零会掩盖噪声水平，让你误判精度）。

### 3.4 GC 增量

报告给整段 run 的 GC 增量（次数 + 累计暂停 ms），在 run 前后各采一次 GC bean 快照相减。

为什么单独测 GC：`rasterize` 每帧 new 一个 `widthMaps*128 × heightMaps*128` 的 ARGB `BufferedImage`
（一面 2×2 墙 = 256×256×4 ≈ 256 KB/帧），高 fps 下持续分配会触发频繁 minor GC。光看 wall-clock 时间看不出
这层成本（GC 暂停可能落在别的迭代里），所以分配字节 + GC 计数单独采，让报告能区分**"算得慢"与"GC 压力大"**。

> **范围注意**：GC 增量覆盖**整段 run**（空白基线 + 全场景的 warmup + measured 全部迭代）。warmup 的分配
> 计入 GC 但**不**计入 per-scene 的 `alloc MB/it`，故 GC 数字≠各场景 alloc 之和，勿据此反推。

### 3.5 环境卡（数字脱离环境无意义）

报告顶部一张**环境卡**：Java 版本 / JVM 名 / OS+Arch / 可用处理器数 / 最大堆 (Xmx) / 激活的 GC 收集器。

**为什么必须看它**："数据透明"原则（`PROPOSAL.md §2.1`）的一部分——benchmark 数字脱离硬件 / 堆大小 / GC
算法**毫无意义**。换机器、改 Xmx、切 GC 算法（G1 → ZGC）后，所有数字都得**重新压测**，不可跨机直接套用。
报告把环境摊开，就是逼你"看清楚这组数字是哪台机器跑的"再做判断。

---

## 4 50mspt 公式与交互计算器

报告**不给"你能开 N 个 wall"的结论**，而是给公式 + 让你代入自己的预算自己算。

公式（`BudgetFormula`）：

```
可用主线程预算 (ms/s) = mspt × tps × 主线程份额%
单 wall × fps 成本 (ms/s) = rasterize_p95 (ms) × fps
可载 wall 数 ≈ 可用预算 ÷ 单 wall × fps 成本
```

`report.html` 里这是一个**交互计算器**：填四个输入框（mspt 预算 / 目标 tps / 主线程份额% / 目标 fps），
内联 JS 实时算出每个场景的"可载 wall 数"。默认值取自 §13.3 示例：

| 输入 | 默认 | 含义 |
|---|---|---|
| mspt 预算 | 50 | 每 tick 主线程预算（ms） |
| 目标 tps | 20 | 服务器目标 tick rate |
| 主线程份额 % | 30 | 分给本插件的主线程份额（其余给 world tick / 别的插件） |
| 目标 fps | 5 | 画布刷新率（v1 静态招牌默认值） |

> 例：50 × 20 × 30% = 300 ms/s 可用预算。某场景 rasterize p95 = 0.5 ms、fps = 5 → 单 wall 成本
> 2.5 ms/s → 可载 ≈ 120 面。

### ⚠️ 这是保守下界，不是硬上限

公式把**整个 rasterize 成本算作主线程成本**。但实际渲染管线里，**rasterize 走 async 线程**，主线程只做
schedule + packet handoff——真实主线程成本**更低**。所以算出的"可载 wall 数"是**偏保守的下界，实际能开
更多**。

**别把计算器结果当硬上限**。它是个数量级参考、是原料 + 公式，**不是推荐配置**。真正的主线程成本需要后续
Benchmark 在主线程侧实测标定（留 P4+，见 [§8](#8-已知局限与-p4-精化)）。这条 disclaimer 在 HTML 报告里
随计算器一起常驻展示，就是为了防止它被误当成"系统替你定的天花板"。

---

## 5 为什么没有自动门禁 / 自动降级

这套 Benchmark 刻意**没有**三样东西，全是"工具，不是保姆"（`PROPOSAL.md §2.1`）的直接落实：

**① 不自动降级。** 系统**绝不**因为"测出来开销大"就自动 throttle / 拒绝大负载 / 隐藏高开销选项 / 默默压低
帧率兜底。`config.yml` 里的上限**仅作安全上限**（防 OOM / 防失控），**不是**自动调优旋钮——默认取宽松值，由你
按自己机器**主动收紧**（见 [§6](#6-用实测容量设-config-软上限)）。自动降级会被玩家反咬"偷偷锁帧率"。

**② CI 只断言"能跑通"，不卡性能数值。** CI 上只做功能性 gate（bench 能跑通 + 不崩 + 在 timeout 内），
**没有任何性能数值断言**。理由：0.4.7 / 0.4.8 / 0.4.9 三次 CI flaky 全栽在 perf / 平台敏感断言上——共享
runner 有 ±2-3x 抖动，数值门禁要么松到没用、要么紧到 flaky。

**③ 数字机器特定，不跨机迁移，故不提交 baseline、不做 drift 报警。** 因为数字脱离环境无意义（[§3.5](#35-环境卡数字脱离环境无意义)），
仓库**不**提交某台机器的 baseline，**不**做自动 drift 报警。性能回归靠**你在自己机器上对比 `report.json`
人工复查**——改动前后各跑一次，自己看 p95 有没有劣化。

---

## 6 用实测容量设 config 软上限

Benchmark 的实战用途：把**你自己机器的实测数据**变成 config 软上限。流程：

1. **跑 benchmark**：`/canvas bench run`（建议高 iters 求稳分位，如 `run all 200 20`）。
2. **拿 p95 + 自己的预算代入公式**：打开 `report.html`，填你服务器真实的 mspt / tps / 份额 / fps，看你
   常用画布尺寸对应场景（如地铁屏看 `subway-sign-2x1`、大欢迎墙看 `welcome-wall-4x2` / `size-4x4`）的"可载
   wall 数"。
3. **据此把 config 上限设成你舒服的安全上限**：把 canvas 尺寸上限 / 帧率上限等 `config.yml` 项，设成一个你
   认为安全的值——留足余量（公式是保守下界，但机器还要跑别的东西）。

**强调**：这一步是**你主动决策**，不是系统替你定。Benchmark 给你看清成本，你自己拍板上限多少。`config.yml`
的上限永远是"安全护栏"而非"自动天花板"——系统不会偷偷帮你收紧，也不会因为你设宽了就自动降级。

---

## 7 Benchmark 4 原则速查

完整论述见 `PROPOSAL.md §5.2.7`。四条不可越界：

| # | 原则 | 落实 |
|---|---|---|
| 1 | **后台模拟，不破坏世界** | async 线程跑真实 `rasterize` + `toPaletteSlice`，输入是程序生成的假 `ProjectState`；不占地图池 / 不放 ItemFrame / 不需要真实玩家 / 不污染 `idcounts.dat`，跑炸也不影响在线玩家 |
| 2 | **数据透明，不替服主决策** | 报告给可控成本 + 公式，**不给"你能开 N 个 wall"结论**；环境卡摊开机器 / JVM / 堆 / GC |
| 3 | **测我们能控制的** | rasterize / palette LUT 量化 / GC 分配速率——纯软件、纯 CPU + 内存可测 |
| 4 | **不擦屁股——网络一律不测** | 带宽 / 压缩比 / RTT / 丢包 / 你的 zlib 配置全砍。同一面墙有人做 30fps 动画、有人每秒刷一次 ETA，压缩效果天差地别且取决于你自己的配置，服务端测出的任何"压缩率"都没有迁移意义 |

**关于 viewer / fps**：rasterize 成本**只取决于场景本身**（canvas 尺寸已烘进每个场景），**不依赖 fps / viewer**，
故每个场景**只测一次**——绝不为每个 fps / viewer 组合重复 rasterize（那是重复测同一个东西）。fps / viewer
仅作为 50mspt 公式的**输入参数**随报告记录，不参与任何测量循环。多 viewer 的 packet 序列化是"同一份 16KB 像素
重复 encode"的线性外推乘数，benchmark **不实跑 PacketEvents**（send 是网络边界）。

---

## 8 已知局限与 P4+ 精化

当前实现（P1-P3）有两处**代表性近似**，留待后续精化——报告诚实标注，不假装精确：

| 局限 | 现状 | 留 P4+ |
|---|---|---|
| **IconElement** | 矢量 SVG icon 走"占位"分支渲染（headless compositor 用 3 参构造，未注入 `IconRegistry`）——icon 真实成本被低估 | 需在不拉起插件运行期的前提下离线构造无头 `IconRegistry` |
| **合成图片** | image + mask 场景注入一个**内存合成渐变生成器**（任意 hash 都返回同一张 256×256 RGB 渐变图），让蒙版 clip / dither 路径有真实像素；但真实上传图的尺寸 / 通道 / 解码路径不同——image 成本是"代表性近似"而非"逐文件精确" | 按场景声明的尺寸生成、覆盖更多通道 / 解码路径 |
| **主线程成本标定** | 50mspt 公式把整个 rasterize 当主线程成本（保守下界）；实际 rasterize 走 async，主线程只做 schedule + handoff | 在主线程侧实测标定真实成本，给出公式系数 |

设计细节、phase 分解、4 个锁定决策见 `docs/dynamic-data.md §13.3`。
