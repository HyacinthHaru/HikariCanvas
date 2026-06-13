# 补间动画 P1 实施计划：数据模型 + 协议

> **For agentic workers:** 用 subagent-driven-development 执行。后端（BE）+ 前端（FE）两任务并行（不同目录、不冲突）。

**Goal:** 加 `Action.TweenBlock`（挂起式包裹 Action）+ 序列化/校验/权限 + 协议升版 v6 + 前端类型镜像。**P1 只数模 + 协议 + 校验，不实现补间引擎执行**（引擎在 P2）。

**Architecture:** 照「带 body 的 Action」范式（`RepeatUntil` Action.java:184）。`TweenBlock` 含 `durationMs` + `easing`（复用 0.6 `state.Easing`）+ `body: List<Action>`（仅属性动作）。ScriptRunner P1 加占位分支（trace「补间引擎 P2 就绪」+ 跳过，不崩），P2 替换真引擎。

**契约:** `docs/scripting-tween.md`（T1-T10）。

---

## 锚点（已摸过）
- `Action` sealed permits：`plugin/.../script/Action.java:19-28`（TweenBlock 加进 permits）
- 带 body 范式 `RepeatUntil`：`Action.java:184-190`（record + wireType + compact ctor `body = copyOf`）
- Easing：`plugin/.../state/Easing.java` + `state/EasingType.java`（0.6 现成，import 复用）
- 协议版本后端：`plugin/.../web/Protocol.java:32,35`（SUPPORTED_MIN/MAX `5→6`）
- 协议版本前端：`web/src/network/wsClient.ts:63`（CLIENT_V `5→6`）
- 带 body 的序列化/校验范式：搜 `RepeatUntil` 在 `ActionDeserializer` / `ActionSerializer` / `ScriptRuleValidator` / `ScriptPermissions` 的处理，TweenBlock 照葫芦画瓢

---

## Task BE（后端，一个子代理）

**Files（照 RepeatUntil 范式找到对应位置改）:**
- `plugin/.../script/Action.java`：加 `TweenBlock` record + permits
- `plugin/.../script/ActionDeserializer.java`：`tweenBlock` case
- `plugin/.../script/ActionSerializer.java`：`tweenBlock` case
- `plugin/.../script/ScriptRuleValidator.java`：`TweenBlock` case
- `plugin/.../script/ScriptPermissions.java`：`tweenBlock` → edit 面
- `plugin/.../script/engine/ScriptRunner.java`：P1 占位分支
- `plugin/.../web/Protocol.java`：SUPPORTED_MIN/MAX `5→6`
- 单测：`ActionSerdeTest` / `ScriptRuleValidatorTest`（或对应现有测试类）

**步骤:**
1. **`Action.TweenBlock` record**：
   ```java
   record TweenBlock(long durationMs, moe.hikari.canvas.state.Easing easing,
                     java.util.List<Action> body) implements Action {
       @Override public String wireType() { return "tweenBlock"; }
       public TweenBlock {
           body = body == null ? java.util.List.of() : java.util.List.copyOf(body);
       }
   }
   ```
   加进 permits 列表（Action.java:19-28 末尾）。

2. **`ActionDeserializer`**：`tweenBlock` case，读 `durationMs`（long）+ `easing`（**复用 0.6 Easing 反序列化**——搜 timeline/keyframe 怎么反序列化 Easing，同款）+ `body`（用现有 `readBranch` 读 List<Action>，照 RepeatUntil.body）。

3. **`ActionSerializer`**：`tweenBlock` case，写 `type`/`durationMs`/`easing`（同 Easing 序列化）/`body`（照 RepeatUntil）。

4. **`ScriptRuleValidator`**：`TweenBlock` case：
   - `durationMs ∈ [1, max]`（max 用常量，默如 60_000ms = 60s）。
   - `easing` 非 null 且合法（EasingType 合法 + CUBIC_BEZIER 时 bezier 4 参）。
   - `body` 非空。
   - **body 每条必须是「属性动作」**：定义白名单 `TWEENABLE`——`SetElementProperties` 且其 `kind` ∈ 补间属性集（去 `web/.../model/blockDefs.ts` FRIENDLY_ELEMENT_DEFS 确认 kind 名，应含 移动/缩放/转动/透明度/变色 对应的 kind，如 `moveTo`/`resize`/`rotateTo`/`setOpacity`/`setColor`）。非白名单 → 校验失败，错误码/消息「补间里只能放移动/缩放/转动/透明度/变色」。
   - body 递归校验（每条属性动作本身合法）。
   - （同属性重复：P1 可先警告或放行，§11 开放——P1 先放行，记 TODO。）

5. **`ScriptPermissions`**：`tweenBlock` → `edit` 面（同其他元素动作）。

6. **`ScriptRunner` P1 占位**：instanceof 链加 `TweenBlock` 分支——P1 暂 `st.trace.add(TraceStep.ok(blockId, "action", "补间引擎 P2 就绪，本帧跳过"))` + `i++; continue`（不执行 body、不崩）。**P2 替换为真引擎调用**。加注释标 P2。

7. **协议升版**：`Protocol.java` SUPPORTED_MIN = 6 / SUPPORTED_MAX = 6。

8. **单测**：
   - Deser/Ser roundtrip：`TweenBlock(1500, EASE_OUT, [moveTo...])` 序列化→反序列化 逐字段等。
   - Validator：合法 tweenBlock 过；durationMs=0 拒；body 空拒；body 放非属性动作（如 sendMessage）拒；easing 非法拒。

**验证:** `./gradlew :plugin:test`（对应测试类全绿）+ `./gradlew :plugin:compileJava`（sealed exhaustive switch 全加 case 才编译）。**不要 commit。**

---

## Task FE（前端，一个子代理）

**Files:**
- `web/src/types/protocol.ts`：`TweenBlock` 类型
- `web/src/script/model/blockDefs.ts`：`tweenBlock` 定义
- `web/src/script/model/blockTree.ts`：`isBodyContainer` 泛化含 tweenBlock
- `web/src/script/model/validator.ts`：镜像校验
- `web/src/network/wsClient.ts`：CLIENT_V `5→6`
- `web/src/i18n/messages.ts`：基础 key（中英）
- 单测：`blockTree.test.ts` / `validator.test.ts`（或对应）

**步骤:**
1. **`protocol.ts`**：`TweenBlock` 类型 = `{ type: 'tweenBlock'; durationMs: number; easing: Easing; body: ScriptAction[] }`（`Easing` 复用 0.6 timeline 前端类型——搜 `web/src/timeline/` 的 Easing 类型，import 复用）。加进 `ScriptAction` 联合类型。

2. **`blockDefs.ts`**：`tweenBlock` BlockDef——category 暂 `control`（绿；P4 可改专属）；字段：`durationMs`（number）+ `easing`（缓动选择，P1 先占位 select/默认）+ body（statements 容器）。**P1 基础定义，UI 细化留 P4。**

3. **`blockTree.ts`**：`isBodyContainer`（或等价的 body 容器判定）泛化含 `tweenBlock`（照 repeat/repeatUntil），让 body 能 drop。getChildSeq/withChildSeq 走 body。

4. **`validator.ts`**：镜像后端——durationMs ∈ [1, max] / body 非空 / body 每条属性动作白名单（setElementProperties + tweenable kind）。

5. **`wsClient.ts`**：CLIENT_V `5→6`（与后端 SUPPORTED 同步）。

6. **`i18n`**：`script.blocks.tweenBlock`（「在 X 秒内」）+ duration/easing label + body 限制提示，中英。

7. **单测**：blockTree body 容器 drop（tweenBlock body 能放属性动作）+ validator（合法/durationMs=0/body 空/body 放非属性动作）+ protocol roundtrip。

**验证:** `cd web && ./node_modules/.bin/vitest run`（单次前台不并发）+ `./node_modules/.bin/vite build --clearScreen false`（编译通过）。**不要 commit。**

---

## 收尾（主控做）
- BE/FE 都回来 → review（协议版本两边一致 v6 / Easing 复用对 / 白名单一致）→ 对抗审查 → 不单独 commit（积攒到 P2 MVP 一起或按 phase commit，按主控节奏）。
