# 补间动画 P3 实施计划：全属性（颜色/渐变）+ 与时间轴共存

> **For agentic workers:** subagent-driven。P3 后端为主（TweenScheduler 扩颜色/fill + 共存分流），主控深度 review。

**Goal:** ① 补间支持**颜色 / 渐变（fill）**属性（复用 `ColorLerp` + `KeyframeInterpolator` 的 fill 插值）；② 补间与用户预排的**时间轴在同一招牌共存**（有 timeline 的墙走每帧 applyMany 让时间轴叠加）。

**契约:** `docs/scripting-tween.md` §3.4 / §4 / §5（已回填 P2 路径 Z + P3 共存方案）。

**现状（P2）:** `TweenScheduler` 只支持数值属性（`isNumericProperty` = x/y/w/h/rotation/opacity），`PropTarget(elementId, property, double from, double to)`；`tickOne` 只对静态墙 `renderStatic`（有 timeline 的墙 `renderStatic` 内部 `entries` 守卫 no-op → 补间不显示）。

---

## 锚点（子代理先摸）
- **颜色插值**：`ColorLerp.lerpHex(a, b, t)`（`render/ColorLerp.java`，sRGB 线性）。
- **fill 插值**：`KeyframeInterpolator` 怎么插 fill 轨（搜 fill / lerpFill——同类型同 stop 数逐 stop，否则 step）。复用它的 fill 插值逻辑（抽 helper 或照抄）。
- **元素读值**：`Element.color()` 仅 `TextElement`；`Element.fill()` 在 Rect/Icon/Path/Circle/Shape/Brush（`instanceof` 取）。
- **共存分流**：`AnimationTicker.isWallAnimating(wallId)`（已有，调研点 1/5）；`TickerControl` 加 `isWallAnimating` 转发。
- **friendly kind → property**：去 `web/.../blockDefs.ts` FRIENDLY_ELEMENT_DEFS 看 `setColor` 改的是 `color` 还是 `fill`（TextElement=color，其他=fill？确认）；P1 `TWEENABLE_KINDS` 已含 `setColor`。

---

## Task（后端引擎，一个子代理 + 主控深度 review）

### 步骤 1：`PropTarget` 支持三态（numeric / color / fill）
P2 的 `PropTarget(elementId, property, double from, double to)` 只数值。扩展成能装颜色（String `#RRGGBB(AA)`）+ fill（`Fill` 对象）。**你选实现**（类型安全优先）：
- 方案 A：`sealed interface PropTarget` permits `NumericTarget(.., double from, double to)` / `ColorTarget(.., String from, String to)` / `FillTarget(.., Fill from, Fill to)`，插值/构造 patch 时 `instanceof` 分流。
- 方案 B：`PropTarget` record 加 `kind` enum + 按 kind 取对应字段。
- 推荐 A（sealed，干净 + 编译强制分流）。三线程契约不变（PropTarget 仍 final immutable）。

### 步骤 2：插值分流
`tickOne`/`buildInterpolatedFrame` 算插值时按 target 类型：
- numeric：`from + (to - from) * eased`（P2 已有）。
- color：`ColorLerp.lerpHex(fromStr, toStr, eased)`。
- fill：复用 `KeyframeInterpolator` 的 fill 插值（`lerpFill(fromFill, toFill, eased)` 或同款逻辑）。**含 `${var:}` 的颜色/fill 不补间**——enqueue 时若 from/to 含变量占位符 → 该 target 退化为「末帧瞬切」（不加补间，仅末帧落目标值），照 timeline P3 语义。

### 步骤 3：`enqueue` 读 from + 解析 to（按属性类型）
- 扩 `isNumericProperty` → `isTweenableProperty`（含 color/fill）；非补间属性仍返 error。
- 读 from：numeric 同 P2；color → 元素当前 `color()`（仅 TextElement，其他报 error）；fill → 元素当前 `fill()`（instanceof）。
- 解析 to：从 `SetElementProperties.patch` 取（color 是 `#RRGGBB` 字符串；fill 是序列化的 Fill——确认 patch 里 fill 的形态，可能是 JSON 字符串，参照 `ElementPropertyApplier.buildPatch` 怎么收 fill）。
- 按类型构造对应 `PropTarget` 子类。

### 步骤 4：`buildInterpolatedFrame` 按类型出 patch
每 target 算插值值 → patch（numeric → int；color → `#RRGGBB`；fill → Fill 对象/序列化），喂 `EditSession.updateElement`。末帧 `formatFinalValue` 同样分类型。

### 步骤 5：`tickOne` 共存分流（核心）
按 `ticker.isWallAnimating(wallId)` 分两路（§5）：
```
boolean animating = ticker.isWallAnimating(wallId);
// 节流判断（按 task.fps）照旧
if (该渲帧) {
    if (animating) {
        // 有时间轴：改 base 落 DB，让时间轴下帧 reload 叠加
        applyFn.apply(wallId, blockId, elementId, 当前插值 patch);   // 每渲帧 applyMany
    } else {
        // 静态墙：渲临时态不落 DB（P2 路径 Z）
        ticker.renderStatic(wallId, frame);
    }
}
if (末帧) {
    // 末帧总是 applyMany 落终值（两种墙都要永久落盘）
    applyFn.apply(...终值...);
    if (!animating) ticker.clearStaticDiff(wallId);
}
```
- **注意**：animating 墙不调 renderStatic（避免和 Ticker 抢）；静态墙不每帧 applyMany（省 DB）。
- `TickerControl` 加 `isWallAnimating(wallId)` 转发 `AnimationTicker.isWallAnimating`。
- enqueue 时也可记 animating 快照？**不要**——animating 状态可能补间期间变（用户开/关时间轴），每 tick 现查最稳。

### 步骤 6：前端（若需要）
- 确认 `TWEENABLE_KINDS`：P1 已含 `setColor`。若「变色到」对非文字元素改的是 `fill` 而非 `color`，确认补间能覆盖（步骤 3 按元素类型读 color 或 fill）。若需要单独 `setFill` kind，前端 blockDefs/validator 加（与后端 isTweenableProperty 对齐）。**P3 先保证 setColor（颜色）能补间**；fill 若 friendly 没有独立入口可记 TODO。

### 步骤 7：测试
- `TweenSchedulerTest` 加：① color 补间（from/to hex → 中间帧 ColorLerp 值）② fill 补间（若实现）③ 共存分流（isWallAnimating=true → 每渲帧 applyMany 不 renderStatic；false → renderStatic）④ 末帧两种墙都 applyMany ⑤ `${var:}` 颜色退化末帧瞬切。
- 用 fake ticker（加 isWallAnimating 开关）+ fake applier 计数。

## 验证
- `./gradlew :plugin:compileJava` + `:plugin:test`（全绿）。前端若改 `cd web && vitest run` + `vite build`。

## 主控深度 review 重点
1. **共存分流正确**：animating 墙每渲帧 applyMany（不 renderStatic）；静态墙 renderStatic（不每帧 applyMany）；末帧两者都 applyMany 落终值。
2. **animating 状态每 tick 现查**（不缓存——补间期间用户可能开/关时间轴）。
3. **三线程契约**：PropTarget 三态仍 immutable；ColorLerp/fill 插值是纯函数无副作用。
4. **fill patch 形态**：确认 fill 在 patch / updateElement 里的序列化形态（别和 buildPatch 不一致）。
5. **`${var:}` 颜色退化**：含变量的颜色不做中间插值（避免每帧 resolve），末帧瞬切。

**不要 commit。** 回报：PropTarget 三态实现选哪个、fill 插值复用哪个、共存分流逻辑、`${var}` 处理、前端是否动、compileJava + test 结果。
