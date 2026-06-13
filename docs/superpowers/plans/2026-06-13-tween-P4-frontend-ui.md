# 补间动画 P4 实施计划：前端 UI 完善

> **For agentic workers:** subagent-driven。纯前端，逻辑部分（body 限制 + 缓动曲线）子代理做 + 单测；视觉打磨我看不到运行时，子代理做初版 + 用户截图迭代。

**Goal:** ① **body 拖入限制**（拖非属性动作进「在 X 秒内」直接拒绝）；② **自定义缓动曲线**（复用 0.6 `EasingCurveEditor.vue`，easing 加 cubicBezier）；③ **C 形 tweenBlock 视觉打磨**（与现有 Scratch 实色风统一）。

**契约:** `docs/scripting-tween.md` §8。

---

## 锚点（已摸）
- **缓动曲线编辑器**：`web/src/components/timeline/EasingCurveEditor.vue`（0.6 时间轴的 SVG cubic-bezier 编辑器，直接复用——先读它的 props/emit 接口）。
- **drop 校验**：`web/src/script/model/dropTarget.ts` `findDropTarget`（L52，**目前无 child 类型校验**）+ `useBlockDrag.ts` 怎么用 findDropTarget。
- **tweenBlock def**：`blockDefs.ts` L557（ACTION_DEFS.tweenBlock，category `control`）+ `TWEENABLE_KINDS`（move/resize/rotate/opacity/color 对应 friendly kind）+ `makeDefaultAction` L814。
- **easing 字段读写**：`BlockNode.vue` 的 `fieldValue`/`onFieldUpdate` 已对 easing 特判（读 `.type` / 写 `{type}`）——P4 扩展支持 `{type:'cubicBezier', bezier:[...]}`。
- **TWEEN_EASING_OPTIONS**：blockDefs L163（4 预设，labelKey `timeline.easingXxx`）。

---

## Task（前端，一个子代理 + 用户截图迭代视觉）

### 1. body 拖入限制（防呆）
tweenBlock 的 body 只能放**属性动作**（`setElementProperties` 且 kind ∈ TWEENABLE_KINDS）。现在拖任何积木进 body 都接受，保存时才被 validator 拒。改成**拖动时就拒绝**：
- 在 `dropTarget.findDropTarget`（或 useBlockDrag 调它的地方）加 child 类型校验：若目标 slot 是 **tweenBlock 的 body**（slot path 以某 tweenBlock 的 `/body` 结尾 + 该 block.type==='tweenBlock'）且拖动的积木**不是** `setElementProperties`（或 kind 不在 TWEENABLE_KINDS）→ 该 drop target **不可落**（findDropTarget 跳过它 / 返回 invalid）。
- 视觉：拖到非法 body 上时不显示「可落」高亮（或显示红色「这里只能放移动/缩放/转动/透明度/变色」提示）。复用现有 drop 高亮机制。
- 注意：嵌套——tweenBlock body 内不允许再放 tweenBlock / if / repeat（只属性动作）。白名单即 setElementProperties。

### 2. 自定义缓动曲线（复用 EasingCurveEditor）
- `TWEEN_EASING_OPTIONS` 加第 5 项 `{ value: 'cubicBezier', labelKey: 'timeline.easingCustom' }`（i18n key 在 timeline 块，已有 `easingCustom`='自定义曲线'）。
- `BlockNode` 的 easing 字段：select 选到 `cubicBezier` 时，**额外渲染** `EasingCurveEditor.vue`（读 `easing.bezier`，emit 更新 → `easing = {type:'cubicBezier', bezier:[x1,y1,x2,y2]}`）。先读 EasingCurveEditor 的 props/emit 接口对接。
- 扩展 `BlockNode.fieldValue`/`onFieldUpdate` 的 easing 特判：fieldValue 对 cubicBezier 仍返 `.type='cubicBezier'`（给 select）+ 单独把 `easing.bezier` 传给曲线编辑器；onFieldUpdate 选 cubicBezier 时保留/初始化 bezier（默认 `[0.25,0.1,0.25,1]` ease 或时间轴默认）；曲线编辑器改 → 写 `{type:'cubicBezier', bezier}`。
- 后端 P1/P3 已支持 cubicBezier（EasingSolver + validator bezier 4 参校验），前端补 UI 即可。

### 3. C 形 tweenBlock 视觉打磨
- tweenBlock 现在 category `control`（绿）。**视觉方向待定**——补间是「动画」，可考虑跟时间轴同色系（mauve 紫）以示「动画类」，或保持 control 绿。**先按 control 绿做**（最小改动），视觉细节用户截图后定。
- C 形渲染已有（P2 复用 repeatUntil 的 hasBodySlot）。打磨：确认 tweenBlock 的头部（durationMs + easing + 可选曲线编辑器）布局不挤、C 形 body 槽清晰、与现有积木实色 Scratch 风一致。
- **不引入新依赖**；照现有 BlockNode/blockDefs 样式。

### 4. i18n + 单测
- i18n：body 限制提示（中英）。缓动 cubicBezier 复用 timeline.easingCustom。
- 单测：① dropTarget 校验（拖 setElementProperties 进 tweenBlock body → 可落；拖 sendMessage → 不可落）② easing cubicBezier 读写（select cubicBezier → easing.type + bezier）。

## 验证
- `cd web && ./node_modules/.bin/vitest run`（单次前台不并发）+ `./node_modules/.bin/vite build --clearScreen false`。

## 交付
**不要 commit。** 回报：EasingCurveEditor 接口怎么对接、body 限制加在 findDropTarget 哪、tweenBlock 视觉动了什么、i18n、vitest + vite build 结果。**视觉是初版**，用户会截图后再迭代。不贴整文件。
