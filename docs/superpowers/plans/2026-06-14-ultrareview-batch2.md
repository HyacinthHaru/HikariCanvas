# 0.7.3 ultrareview 第二批修复实施计划

> 关联报告 `docs/ultrareview-2026-06-14.md`，承接第一批（commit `ffd7a5c`）。第二批 = 真崩溃 / 数据错乱 / 防御 6 条 + 2 个已定语义项（P2-13 rail arrival/departure 拆分 · 用户确认；P2-16 关编辑器加保存提醒 · 用户确认加）。
> 执行：5 子代理并行（按子系统无冲突），**不 commit、不跑 Gradle**（统一编译测试），硬条目（P2-5 跨线程 / P1-4 / P1-7）对抗审查。

**Goal:** 修第二批 8 条，覆盖整墙加载崩溃、跨元素串写、地铁屏空白、动画/上传 OOM 防御、补间续接时序，外加 rail 发车/到达拆分 + 关编辑器保存提醒。

**Architecture:** 纯逻辑 + 1 处语义拆分（rail）。5 批：rail / render+image / state / 前端 / 补间续接，文件无重叠。

**契约对照：** `docs/dynamic-data.md §18.4`（rail next_arrival/next_departure 语义）、`docs/scripting-tween.md §2.2`（补间落盘→续接顺序）。

---

## 批次划分（子系统无重叠）

| 批次 | 任务 | 改动文件 | 模型 |
|---|---|---|---|
| **A rail** | P2-14 + P2-13 | `web/RailOpDispatcher.java`、`variable/provider/RailScheduleProvider.java`、`docs/dynamic-data.md` | opus |
| **B render+image** | P1-3 + P1-6 | `render/IconRenderer.java`、`render/ImageRenderer.java`、`web/TimelineOperations.java`、`image/UploadHandler.java`、`image/ImageStorage.java` | sonnet |
| **C state** | P1-4 | `state/Timeline.java`（+ KfValueDeserializer 确认） | sonnet |
| **D 前端** | P1-7 + P2-16 | `web/src/components/layout/RightPanel.vue`、`web/src/stores/scriptEdit.ts` | sonnet |
| **E 补间续接** | P2-5 | `script/engine/ScriptRunner.java`、`script/engine/TweenScheduler.java` | opus（跨线程，硬） |

---

## Task A1 — P2-14 · 删除被绑定站点后 RailScheduleProvider 内存绑定快照残留
**File:** `plugin/.../web/RailOpDispatcher.java`（`handleStationDelete`）

**现状（已核验）：** `wall_rail_bindings.station_id` FK 是 `ON DELETE SET NULL`。`handleStationDelete` 删站后既不 `provider.unregisterWall` 也不重注册，`RailScheduleProvider.registeredWalls` 持旧 stationId 快照 → `refresh()` 永查空 → push 空串覆盖 `schedule:*`；`hasWallBinding` 仍 true → `ManualScheduleProvider.skipWallPredicate` 持续跳过 → **该 wall 屏整个运行期空白到重启**。对比 `handleLineDelete` 已正确收集 boundWalls 并 unregister。

**改法：** 照 `handleLineDelete` 范式——`handleStationDelete` 删除前先查所有绑定该 stationId 的 walls，删除后对每个调 `provider.unregisterWall(wallId)`（让 ManualSchedule 接管或下次重绑）。

**测试：** 删绑定站后 provider 不再持该 wall 的旧 stationId 快照（registeredWalls 不含 / 或 unregister 被调）。

## Task A2 — P2-13 · next_departure 暴露到达时刻而非发车时刻（拆成两个独立字段）
**File:** `plugin/.../variable/provider/RailScheduleProvider.java`（`snapshotFields`）+ `docs/dynamic-data.md §18.4`

**现状（已核验）：** `snapshotFields()` 用 `departure = arrivalTime != null ? arrivalTime : departureTime`——`next_departure` 取该站 `arrival_time`，与 `next_arrival` 同值，真正 `departure_time` 永不暴露。`docs/dynamic-data.md §18.4` 规定 `next_arrival`=到达时刻、`next_departure`=发车时刻。**用户已确认：到达与发车应是两个独立字段，一并修正。**

**改法：** 先读 `rail_timetable` 的 record（确认 arrival_time / departure_time 两列）+ `docs §18.4`。改 `snapshotFields`：
- `next_arrival` = 该站 `arrival_time`（到达时刻）
- `next_departure` = 该站 `departure_time`（发车时刻）
- 边界 fallback：首站通常无 arrival（始发）→ `next_arrival` fallback `departure_time`；末站/终到通常无 departure → `next_departure` fallback `arrival_time`（或留空，看 §18.4 既有约定）。两列都缺则空串。
- `next2_*` 系列同步同样的拆分。
同步更新 `docs/dynamic-data.md §18.4` 明确两字段语义（若文档已写清则只改代码）。

**测试：** 构造一个中间站（arrival_time 与 departure_time 不同）→ 断言 `next_arrival ≠ next_departure` 且分别等于两列值；首站/末站 fallback 行为。（报告指出现有测试只断言 key present 未校验数值——补数值断言。）

## Task B1 — P1-3 · 动画关键帧 w/h 不受 MAX_DIM 约束 → 渲染线程 OOM
**Files:** `plugin/.../render/IconRenderer.java`（tint 分支 `new BufferedImage(ic.w(), ic.h(), …)`）、`plugin/.../render/ImageRenderer.java`（`drawWithFeather` 的 `new BufferedImage(w, h, …)`）、`plugin/.../web/TimelineOperations.java`（`parseValue`）

**现状（已核验）：** 关键帧数值 w/h 在 `parseValue` 只校验有限性，不做 MAX_DIM(10000) 上限；`StrictNumber.clampInt` 只钳 Integer 范围。`IconRenderer` tint 分支与 `ImageRenderer.drawWithFeather` 按**原始 element w×h** 分配离屏 buffer，无画布裁剪、无 MAX_DIM 上限（dither 路径按 `clip∩canvas` 分配是安全的）。w/h 关键帧到约 50000（finite 放行）→ 单帧分配数 GB → AnimationTicker owner 线程 OOM。

**改法：** ① `IconRenderer` tint 分支 + `ImageRenderer.drawWithFeather` 的 `new BufferedImage` 分配前加 `if (w > MAX_DIM || h > MAX_DIM) return;`（`MAX_DIM` 用 `ElementValidator.MAX_DIM`，与 element.add 同一闸）。② `TimelineOperations.parseValue` 对 `w/h` 属性在有限性校验后加 MAX_DIM 范围校验（与 element 入口对齐）。

**测试：** w/h 关键帧超 MAX_DIM 时渲染器不分配巨 buffer（守卫 return）/ parseValue 拒超 MAX_DIM 的 w/h。

## Task B2 — P1-6 · ImageIO 解码无尺寸预检 → 分配型炸弹 OOM
**Files:** `plugin/.../image/UploadHandler.java`（`decodeCooperative`）、`plugin/.../image/ImageStorage.java`（`decodeFileCooperative`）

**现状（已核验）：** `decodeCooperative()` 拿 `ImageReader` 后直接 `reader.read(0)` 一次性解码，bbox sanity（w/h ≤ 8192）在解码**之后**才校验。一张体积很小但头部声明巨大尺寸（如 30000×30000）的合法 PNG/JPEG/WebP 会在 `reader.read(0)` 内分配约 GB 级 raster，200ms 超时 + abort 拦不住单次原生分配。（注：现有 `reader + abort` 协作式中止对 CPU 型炸弹有效，缺的是解码前的尺寸预检。）

**改法：** `reader.read(0)` 之前插入 `int pw = reader.getWidth(0); int ph = reader.getHeight(0); if (pw > BBOX_MAX_EDGE || ph > BBOX_MAX_EDGE) throw …（映射 UPLOAD_REJECTED）`——在分配前拒掉声明型炸弹。`ImageStorage.decodeFileCooperative` 同样处理。`getWidth/getHeight` 只读头部不解码全图，开销小。

**测试：** 声明尺寸 > 8192 的图在解码前被预检拒（UPLOAD_REJECTED），不进入 `read(0)`。

## Task C1 — P1-4 · Timeline.tracks 的 List.copyOf 对含 null 元素的轨道 NPE → 整墙加载失败
**File:** `plugin/.../state/Timeline.java`（canonical 构造器）+ 确认 `KfValueDeserializer`

**现状（已核验）：** `Timeline` canonical 构造器对每条轨道 `List.copyOf(...)`，而 `List.copyOf` 对含 null 元素的列表抛 NPE。`Timeline` 走默认 Jackson record 反序列化，`WallRepo.readValue(project_json, ProjectState.class)` 加载持久 blob；若某轨道 JSON 为 `[null]`（数据损坏/历史 bug），反序列化在构造器即 NPE → **整面墙加载失败**。对比 `Easing` record 已专门加 null 防御。

**改法：** `List.copyOf(e.getValue())` 改为先过滤 null 再构造不可变列表（如 `e.getValue().stream().filter(Objects::nonNull).toList()`，保持不可变语义）。顺带读 `KfValueDeserializer`：核验提示它对 boolean/array 形态 value 抛 `JsonMappingException` 会冒泡致整个 ProjectState 加载失败——若属实，让它对非预期形态返回安全默认（null → 下游守卫兜）而非抛硬错，与「坏数据不在反序列化期抛硬错」承诺一致。

**测试：** 含 null 元素的轨道 JSON 反序列化不 NPE（null 被过滤）；坏形态 KfValue 不致整墙加载失败。

## Task D1 — P1-7 · 属性面板 80ms 防抖更新跨元素串写
**File:** `web/src/components/layout/RightPanel.vue`（`sendUpdate` / `sendUpdateDebounced`）

**现状（已核验）：** `sendUpdate(patch)` 执行时实时读 `selected.value` 且 patch 不含 elementId；`sendUpdateDebounced = useDebounceFn(sendUpdate, 80)` 只捕获 patch。在元素 A 输入框敲值后 80ms 内点选元素 B → flush 时 `selected.value` 已是 B → **把 A 的值写进 B**（乐观本地 mutate + `ws.send element.update` 到 B.id）。

**改法：** 让防抖回调在**触发时**绑定当时的 elementId，flush 时校验仍是同一元素。建议：`sendUpdateDebounced` 改为闭包捕获 `const id = selected.value?.id` 后再 debounce，回调内 `if (selected.value?.id !== id) return;`；或给 `sendUpdate(patch, elementId)` 显式传 id，乐观 mutate + send 都用该 id（按 id 找元素，不读 `selected`）。读 TransformSection/TextElementSection 怎么 emit，确保不破坏现有调用。

**测试：** vitest 模拟「A 输入 → 切到 B → flush」断言不写到 B（按捕获 id 校验/路由）。

## Task D2 — P2-16 · 关闭脚本编辑器时校验未过的改动被静默丢弃（加提醒）
**File:** `web/src/stores/scriptEdit.ts`（`closeEditing` / `doSave`）+ 找现有 toast 机制

**现状（已核验）：** `closeEditing()` 先 `flushSave()→doSave()`，`doSave()` 在 `validationErrors.length>0` 时 return（按设计保留 dirty），随后 `closeEditing` 无条件 `workingCopy=null; dirty=false` → 静默丢弃。`selectRule` 切规则同样命中。**用户已确认：加一个提醒（不阻止关闭，保留「拖拽中途瞬时非法是正常过渡」的设计权衡）。**

**改法：** `closeEditing`（及 selectRule 切换路径）在执行 null 化之前，若 `dirty && validationErrors.length > 0`，调用现有 toast/通知机制提示「部分改动因校验未通过，未能保存」（找项目现有 toast composable/store，照同款）。不弹确认、不阻止关闭。i18n 中英 key。

**测试：** vitest 验证「dirty + 有校验错误时 closeEditing 触发提醒」。

## Task E1 — P2-5 · 脚本挂起续接早于补间末帧落盘
**Files:** `plugin/.../script/engine/ScriptRunner.java`（TweenBlock continuation 调度）、`plugin/.../script/engine/TweenScheduler.java`（`enqueue` + 末帧 `applyFn`）

**现状（已核验）：** `ScriptRunner` 处理 `TweenBlock` 时按 `tb.durationMs()` 在 Runner SES 调度续接；但补间末帧落盘在 `TweenScheduler` 独立 SES 的 tick（固定 cadence 最高 60fps≈16ms），末帧落盘最晚比续接晚约一个 cadence。静态墙中间帧不落 DB，故续接先跑、后续动作（headless 读元素 x/y / waitUntil 读几何）会读到**补间前的旧值**。`docs/scripting-tween.md §2.2` 本是「补间完→落盘→续接」顺序，当前实现把续接和落盘解耦成两个独立定时器。

**改法（先读透两处现有结构再动手，这是本批最硬）：** 改为**末帧落盘完成后回调触发续接**，而非 ScriptRunner 自己按 durationMs 定时。
1. 读 `TweenScheduler.enqueue` 签名 + 末帧 `applyFn.apply` 落盘点 + `tickOne` 的接管/异常路径；读 `ScriptRunner` 的 TweenBlock 分支怎么 `schedule(runFrames, durationMs)`；参考 `playTimelineAwait` 是怎么做挂起续接的（核验提示它是同款挂起范式）。
2. `enqueue` 增加一个「补间终结回调」参数（末帧 applyFn 落盘后、或补间被接管/异常清理后调用一次），回调内部 **投递到 Runner 的 SES** 执行续接（绝不在 tween 线程跑脚本续接）。
3. `ScriptRunner` 不再 `schedule(durationMs)`，改为把续接逻辑作为该回调传入 enqueue。
4. 处理异常/中断/接管路径：补间失败也要让脚本以合适状态续接或终止（不能让脚本永久挂起）；被新补间接管时旧续接的语义（按设计应仍续接一次）。
5. 注意幂等：回调只触发一次续接。

**测试：** 静态墙补间后续接的动作读到**补间后的目标值**（构造一个 TweenBlock 移动元素 x，body 后接一个读 x 的动作，断言读到目标 x 而非起始 x）；补间被异常/接管时脚本不永久挂起。

---

## 收尾（分配者统一）
1. `rm -rf web/dist plugin/build/generated/web-resources` 后 `./gradlew :plugin:test`（正确捕获 gradle 退出码，勿被管道吞）。
2. 前端 `vitest run` + `vite build`。
3. 对抗审查：E1（跨线程续接回调正确性 + 不永久挂起）/ C1（null 过滤不破坏正常轨道）/ D1（捕获 id 不破坏现有 emit）。
4. 重建 jar，汇报：哪些单测已验、哪些需游戏内验（P2-14 地铁屏 / P2-13 发车到达 / P1-7 串写 / P2-16 提醒）。
5. **不 commit**，等用户实测后 commit + journal + push。
