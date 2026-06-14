# 0.7.3 ultrareview 第三批修复实施计划（收官批）

> 承接第一批 `ffd7a5c` / 第二批 `7bd403a`。第三批 = 协议 close 一组(4) + P2 散点(7) = 11 条中低 ROI。
> **多数条目未经独立核验** → 实施子代理**必须先读代码确认报告描述属实,不属实在返回里报告、不硬改**。
> 执行：5 子代理并行(子系统无冲突)，不 commit、不跑 Gradle/vitest(统一测)。

**Goal:** 清掉 ultrareview 剩余中低 ROI 真问题，让审查正式收官。**Architecture:** 纯逻辑/防御散点，5 批：协议 close / 前端渲染一致 / 模板路径 / 前端交互 / MapPool。

**契约对照:** `docs/protocol.md §6.2`(close code 终止态) / `docs/rendering.md`(双端镜像纪律) / `MapPool` javadoc(detectLeaks 不碰 Bukkit API 不变式)。

---

## 批次划分（文件无重叠）
| 批次 | 任务 | 文件 |
|---|---|---|
| **A 协议 close** | P2-17 + P2-18 + P3-12 + P3-13 | `web/src/network/wsClient.ts`、`web/src/i18n/messages.ts`、确认 `web/Protocol.java` |
| **B 前端渲染一致** | P2-7 + P2-19 | `render/GlowRenderer.java`、`web/src/render/PreviewRenderer.ts` |
| **C 模板路径** | P2-11 + P2-12 | `template/TemplateInstantiator.java`、`TemplateLoader.java` |
| **D 前端交互** | P2-15 + P2-20 | `web/src/script/params/BlockParamInput.vue`、`web/src/components/layout/CanvasView.vue` |
| **E MapPool** | P2-10 | `pool/MapPool.java` |

---

## A — 协议 close（已部分核验：P2-18 属实 4429 是死码 4008；P2-17 后端拒绝路径成立，客户端自检路径已有 stopped 保护，不要动那条）

**A1·P2-17** `wsClient.ts` onClose terminal 判定 `code===1000||4001||4008` 未含 **4002**。后端 auth 阶段版本不符 `close(4002)`（ready 未到达、stopped 未置 true）→ 落非 terminal → 用同 CLIENT_V 重连 5 次（必再被拒）。**改:** terminal 集合加 `4002`，并在 4002 分支显示「请升级客户端」而非重连。
**A2·P2-18** 同 terminal 集合写的 `4008` 是后端**从未发出的死码**，真实 token 限流 close 是 **4429**(`Protocol.CLOSE_TOKEN_RATE_LIMITED`)。**改:** `4008` 换成 `4429`，4429 分支显示「请稍后再试」(协议注释语义)。先读 `Protocol.java` 确认 4429/4008 定义。
**A3·P3-12** `messages.ts` 的 `VERSION_MISMATCH` 文案写成「画板被他人改动正在同步」(状态同步语义)，实际唯一来源是协议版本不兼容。**改:** 文案改成「客户端版本与服务器不兼容，请升级」之类(中英)。
**A4·P3-13** `handleReady` 在 `accepted_v!==CLIENT_V` 时先设精确错误 + `close(4002)+stopped=true`，随后 `onClose(4002)` 走 else 把 lastError 覆写成通用「连接断开」。**改:** onClose 在 stopped 已置 + 有精确 lastError 时不覆写(或 4002 分支保留精确提示)。注意与 A1 协调(4002 现进 terminal)。
**测试:** vitest 验证 4002/4429 进 terminal(不重连) + 文案正确 + 精确提示不被覆写。

## B — 前端渲染一致（P2-7 已核验部分属实:非旋转 glyph 路径分叉，旋转路径两端一致；P2-19 未核验）

**B1·P2-7** 后端 `GlowRenderer` 非旋转 glyph 用 AWT FontMetrics(`fm.getAscent/getDescent/charWidth`)算 glow bbox，前端 `PreviewRenderer.renderGlow` 用固定规则(`round(fontSize*0.8)` + `measureText`)。M20 `charAdvance` 本要消除此 AWT-vs-Canvas 分叉。**改:** 让两端走同一度量——优先把后端 glow bbox 也改用与前端一致的固定规则(`ascent=round(fontSize*0.8)`、`descent=fontSize-ascent`、宽度走 `charAdvance`/canonical)，使发光层落位双端一致。读 `rendering.md` + 主字形 fill 怎么对齐的，照同款。**若发现旋转路径已一致只非旋转分叉，只改非旋转分叉。**
**B2·P2-19** `PreviewRenderer.drawDitheredElement` 应用 opacity 只判 `op<1` 直接 `globalAlpha*=op`，不 NaN 兜底不 clamp。对比同文件 `drawElement` 已 `!isFinite?1:clamp(0,1)`、后端 `CanvasCompositor` 也 clamp。负 opacity(可经模板 raw_state 绕过)→ 前端负 globalAlpha vs 后端 clamp 0 双端分叉。**改:** drawDitheredElement 照 drawElement 加 `isFinite + clamp(0,1)`。
**测试:** 负/NaN opacity 的 dither 元素两端一致(clamp 0/1)；glow bbox 双端度量一致。

## C — 模板路径（未核验，先确认）

**C1·P2-11** `TemplateInstantiator` 的 stack/free/grid 常规布局路径完全跳过元素级校验(raw_state 路径在 P0-23 已加 `validateElementForTemplateApply`)。`materialize` 直接用插值值构造元素：文本可达 16KiB(超 MAX_TEXT_LEN=256)、坐标/尺寸超 MAX_COORD/MAX_DIM 或负、rotation/lineHeight 无 clamp。**改:** 常规布局 materialize 后(或 replaceContent 前)对元素跑与 raw_state 路径同款的 `validateElementForTemplateApply`(或等价 clamp/拒)。先读 raw_state 路径怎么校验的，照同款。
**C2·P2-12** `resolveDimension`/`resolveDimensionWithAuto`：`INT_NUMERIC=^-?\d+$` 匹配任意长度，`${param}` 插值出超 int 数字串 → `Integer.parseInt` 抛(被上层捕成 INVALID_TEMPLATE 但外泄 JDK 文案)；百分比分支 `Double.parseDouble` 得 Infinity/巨值 → `Math.round` 得 Long.MAX → `(int)` 收窄**静默回绕**，且不经 `StrictNumber.clampInt`。**改:** 用 `StrictNumber.clampInt` 钳位 + 数字串溢出走友好 INVALID_TEMPLATE。
**测试:** 超长文本/越界尺寸模板被校验拒/clamp；超 int 数字串不回绕不外泄 JDK 文案。

## D — 前端交互（未核验，先确认）

**D1·P2-15** `BlockParamInput.vue` runCommand 复合控件:模板下拉只渲染已加载模板成 option，`:value="commandValue.templateId"`。保存的 templateId 在配置删/改名后不在列表 → 原生 select 显示空占位但底层仍是孤儿 id 不触发 change、`selectedTemplate=null` 致 params 子输入消失、前端 validator 只判 templateId 非空放行 → 看似空实际引用无效模板的积木悄悄过校验。**改:** 检测 templateId 不在已加载列表时显示孤儿警告(红字/提示「模板已失效:<id>」)，让用户能发现并改;可保留原 id 显示。i18n key。
**D2·P2-20** `CanvasView.boundBoxFunc` 调 `snapManager.snap` 拿方向 delta，`snapAxis` 在 left/center/right 取「离任意 candidate 最近」的 bestDelta，不区分本次 resize 实际动哪条边 → 拖右手柄时若 left 边恰好近某轴，bestDelta 来自未移动的 left 锚点却加到 width 上(右边跳)。**改:** resize 吸附只用「正在动的那条边」对应锚点算 delta，不取其它锚点。读 boundBoxFunc 怎么判断动的是哪条边(对比 newBox vs oldBox)。
**测试:** vitest——resize 右手柄时 left 近轴不误吸右边。

## E — MapPool（未核验，先确认 + 线程纪律）

**E1·P2-10** `detectLeaks`(异步线程每 5min 跑)对泄漏 RESERVED map 调 `offerFreeByName`，后者查 `worldNameToUid` 缓存 miss 时 fall-through `Bukkit.getWorld(worldName)`——**Bukkit API 只允许主线程**。缓存只在 offerFree/reclaim 填充，initialize 对 RESERVED 行只 byId.put 不填缓存 → 某 world 启动后从没 FREE/reserve/reclaim 过且其 RESERVED map 的 owner wall 被删时 → detectLeaks 标记 leaked → `offerFreeByName` 缓存 miss → **异步线程调 Bukkit API**，破坏「idcounts.dat 防膨胀」核心防线。**改:** `offerFreeByName` 缓存 miss 时**不调 Bukkit**——要么跳过(留待下次)、要么用已有的 world UUID 信息(从 byId 的 mapView.getWorld 等不碰主线程的途径)，保持 detectLeaks 走纯内存。读 MapPool javadoc 的不变式 + offerFree 怎么填缓存。
**测试:** detectLeaks 路径缓存 miss 不调 Bukkit.getWorld(用 fake/spy 验证)。

---

## 收尾（分配者统一）
1. `rm -rf web/dist plugin/build/generated/web-resources` 后 `./gradlew :plugin:test`(正确捕获退出码)。
2. 前端 `vitest run` + `vite build`。
3. 检查 messages.ts 是否被 A+D 并行改(验证共存)。
4. 重建 jar，汇报哪些需游戏内验。**不 commit**，等用户实测。
