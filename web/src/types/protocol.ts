// WebSocket 协议类型（对齐 docs/protocol.md §2 / §7）。
// 后端 moe.hikari.canvas.state.* records 的 TypeScript 镜像。
//
// M8 v2 形态：ProjectState.layers + activeLayerId + canvas.gridSize + canvas.guides；
// element 加可选 opacity / blendMode / renderMode。

import type { VarType, VariablePatch } from './variable';

export interface Envelope<P = unknown> {
    v: number;
    op: string;
    id?: string;
    ts?: number;
    payload?: P;
}

// ---------- §7 ProjectState（M8 v2 形态）----------

export interface ProjectState {
    version: number;
    /** M8 v2：固定为 2；M8-A 起后端始终输出 */
    protocolVersion?: number;
    canvas: Canvas;
    /** v2 树形：层间 z-order = index（越大越上层） */
    layers: Layer[];
    /** 当前 UI 操作层；element.add 缺 layerId 时落到这里 */
    activeLayerId: string;
    history: { undoDepth: number; redoDepth: number };
    /**
     * M8-A 兼容视图：指向 activeLayer.elements 同一引用，由 project store 在 setSnapshot
     * 时建立。组件仍可读写 {@code state.elements}，M8-C 起新协议路径直接走 {@code layers}。
     */
    elements?: Element[];
    /** v3 新增（0.6）：工程内时间轴；缺省 / null = 静态画板。 */
    timelines?: Timeline[];
    /** v3 新增（0.6）：当前激活时间轴 id；缺省 / null = 无激活时间轴。 */
    activeTimelineId?: string;
}

export interface Canvas {
    widthMaps: number;
    heightMaps: number;
    /**
     * M17 F5：升级为 Fill 联合类型（solid / linear / radial），与 M11 element fill 一致。
     * 后端 {@code FillDeserializer} 兼容 M0-M16 旧字符串形态（自动 wrap 为 SolidFill）；
     * 前端通过 {@code normalizeFill} 在 store 入口归一化为 object 形态。
     */
    background: FillCompat;
    /** 网格像素间距；0 / 缺省 = 不显示（仅前端预览，不入 MC） */
    gridSize?: number;
    /** 用户从标尺拖出的参考线（仅前端预览） */
    guides?: Guide[];
}

export interface Guide {
    axis: 'x' | 'y';
    position: number; // 像素坐标
}

/**
 * 图层颜色标签。PS 风格辅助识别用，仅 UI 显示，不影响渲染。
 * null = 无标签；其余为 Catppuccin 色名 — 与后端 LayerOperations.ALLOWED_COLOR_TAGS 对齐。
 */
export type LayerColorTag =
    | 'red' | 'peach' | 'yellow' | 'green' | 'blue' | 'mauve' | 'overlay0';

export interface Layer {
    id: string; // "l-<8hex>"
    name: string;
    visible: boolean;
    locked: boolean;
    opacity: number; // 0..1
    blendMode: BlendMode;
    /** PS 风格颜色标签；null / undefined = 无 */
    colorTag?: LayerColorTag | null;
    elements: Element[]; // 层内 z-order = index
}

export type BlendMode = 'normal' | 'multiply' | 'screen' | 'overlay';
export type RenderMode = 'clean' | 'dither';

// ---------- §7.X Fill（M11 引入；几何元素 fill 字段类型）----------

/**
 * 渐变停止点（M11 引入）。
 * - position: [0, 1] 单调非递减
 * - color: "#RRGGBB" 或 "#RRGGBBAA"
 */
export interface Stop {
    position: number;
    color: string;
}

export interface SolidFill {
    type: 'solid';
    color: string;
}

/**
 * 线性渐变。angle ∈ [0, 360)，单位度数；0° 沿 +x（左→右），90° 沿 +y（上→下），顺时针为正。
 * 渐变线穿过 element bbox 中心。
 */
export interface LinearGradient {
    type: 'linear';
    angle: number;
    stops: Stop[]; // [2, 8]
}

/**
 * 径向渐变。cx/cy ∈ [0, 1] 归一化到 bbox；r ∈ (0, 2] 归一化到 min(w, h) / 2。
 */
export interface RadialGradient {
    type: 'radial';
    cx: number;
    cy: number;
    r: number;
    stops: Stop[]; // [2, 8]
}

export type Fill = SolidFill | LinearGradient | RadialGradient;

/**
 * M0-M10 兼容形态：单 hex 字符串等价于 SolidFill。后端会一并接受，但 M11 起新写出形态统一是 object。
 * 前端 store 侧建议把 string 形态在入口处归一化为 object 形态（见 stores/project.ts）。
 */
export type FillCompat = Fill | string;

export type Element =
    | TextElement
    | RectElement
    | IconElement
    | PathElement              // M9
    | CircleElement            // M9
    | ShapeElement             // M9
    | BrushStrokeElement       // M12
    | ImageElement;            // M13

interface BaseElement {
    id: string;
    x: number;
    y: number;
    w: number;
    h: number;
    rotation: number; // [0, 360)
    locked: boolean;
    visible: boolean;
    // M8 v2 新增（默认值时后端序列化省略字段）
    opacity?: number;
    blendMode?: BlendMode;
    renderMode?: RenderMode;
}

export interface TextElement extends BaseElement {
    type: 'text';
    text: string;
    fontId: string;
    fontSize: number;
    color: string;
    align: 'left' | 'center' | 'right';
    letterSpacing: number;
    lineHeight: number;
    vertical: boolean;
    effects?: Effects;
    /** 0.4.6：加粗（内部自动转 stroke 描边模拟，避免 synthetic bold 双端差异） */
    bold?: boolean | null;
    /** 0.4.6：斜体（双端 shear transform 矩阵线性变换，像素一致） */
    italic?: boolean | null;
}

export interface RectElement extends BaseElement {
    type: 'rect';
    fill?: FillCompat;
    stroke?: Stroke;
}

export interface IconElement extends BaseElement {
    type: 'icon';
    /**
     * 图标资源名。
     *
     * - M26+ 矢量形态：{@code pack/name}（如 {@code fa-solid/heart} / {@code user/foo}）；
     *   path d + viewBox 走 {@code GET /api/icon/paths?id=...} 拉取。
     * - M7 legacy PNG 形态：不含 {@code /}（如 {@code "heart"}）；走 {@code /api/template-asset/icons/{source}.png}。
     */
    source: string;
    /**
     * M26 deprecated：仅供旧 .canvas / 模板向后兼容；新协议改用 {@code fill}。
     * legacy PNG 形态仍走 source-in 染色路径。
     */
    tint?: string;
    /**
     * M26：矢量 path 填充。Fill 联合类型（solid / linear / radial），与 Rect/Circle/Shape 共用。
     * {@code undefined} → pack 默认色（前端 / 后端均退黑色）。仅对 SVG 矢量 source 生效，
     * legacy PNG 仍走 tint 路径。
     */
    fill?: FillCompat;
}

/** M26.2：legacy PNG 形态 = source 不含 `/`。镜像后端 {@code IconElement.isLegacySource}。 */
export function isLegacyIconSource(source: string | undefined | null): boolean {
    return !!source && source.indexOf('/') < 0;
}

/**
 * M9 PathElement：通用 SVG-like 路径（M/L/Q/C/Z 子集 + marker）。
 * d 内坐标相对 element.(x, y)；transform 改 x/y 时 d 不动。
 */
export interface PathElement extends BaseElement {
    type: 'path';
    d: string;
    fill?: FillCompat;
    stroke?: Stroke;
    markerStart?: 'arrow' | 'dot';
    markerEnd?: 'arrow' | 'dot';
}

/** M9 CircleElement：圆 / 椭圆，由 bbox 推 cx/cy/rx/ry。 */
export interface CircleElement extends BaseElement {
    type: 'circle';
    fill?: FillCompat;
    stroke?: Stroke;
}

/** M9 ShapeElement：正多边形 / 星，外接圆由 bbox 决定。 */
export interface ShapeElement extends BaseElement {
    type: 'shape';
    kind: 'polygon' | 'star';
    sides: number;        // 3..32
    innerRatio?: number;  // star 用，0.1..0.95；省略 = 默认 0.5
    fill?: FillCompat;
    stroke?: Stroke;
}

/** M12 BrushPoint：单个笔触采样点，相对 BrushStrokeElement 左上原点。 */
export interface BrushPoint {
    x: number;
    y: number;
    /** PointerEvent.pressure，[0, 1]；鼠标默认 0.5，数位板真实压感 */
    pressure: number;
}

/**
 * M12 BrushStrokeElement：用户用笔刷工具画出的连续笔迹。
 *
 * 与 path 元素的关键区别：保留原始采样点 + 压感（Q1=B 决策），不退化为 d 字符串。
 * 渲染时用 Catmull-Rom 拟合 + 变宽（pressureSize）+ 变 alpha（pressureOpacity）。
 */
export interface BrushStrokeElement extends BaseElement {
    type: 'brush';
    /** RDP 简化后的采样点（M12-B 起做简化）；相对 (element.x, element.y) */
    points: BrushPoint[];
    /** 基础大小（px），[1, 64] */
    size: number;
    /** 笔刷填充：SolidFill / LinearGradient / RadialGradient（Q7） */
    fill: FillCompat;
    /** 压感→大小：true 时实际宽度 = size × pressure */
    pressureSize: boolean;
    /** 压感→opacity：true 时每段 alpha = base × pressure */
    pressureOpacity: boolean;
}

/**
 * M13 Mask：ImageElement 的可选 SVG path 蒙版。
 * - d：M9 PathDValidator 子集（M/L/Q/C/Z），坐标相对 element bbox (0, 0)..(w, h)；4096 字符长度上限
 * - inverted：false=显示 mask 内（默认），true=显示 mask 外（用 bbox 减去 mask 形状）
 * - featherPx：2026-05-25 引入。羽化半径（像素）；null / 缺省 / 0 = 无羽化（硬边），
 *   [1, 32] = 边缘渐变过渡。详见 docs/rendering.md §4.4。
 *
 * v1 RightPanel dropdown 暴露 4 预设（none / circle / roundedRect / ellipse）；
 * 2026-05-25 新增 lasso 自由路径（Alt + 拖动 image element 在画布上直接画 mask）。
 */
export interface Mask {
    d: string;
    inverted: boolean;
    featherPx?: number | null;
}

/**
 * M13 ImageElement：用户上传的位图。source 是 sha256[:16] 内容寻址 hash；
 * 服务端从 plugins/HikariCanvas/uploads/<source>.png 加载（GET /api/upload/{source}）。
 * mask 非空时按几何裁切；dither 与 mask 复合走 per-element off-buffer 路径，先 dither 再 mask。
 */
export interface ImageElement extends BaseElement {
    type: 'image';
    /** sha256[:16] 小写 hex 16 字符 */
    source: string;
    mask?: Mask;
}

export interface Effects {
    stroke?: Stroke;
    shadow?: Shadow;
    glow?: Glow;
}

export interface Stroke {
    width: number;
    color: string;
}

export interface Shadow {
    dx: number;
    dy: number;
    color: string;
}

export interface Glow {
    radius: number;
    color: string;
}

// ---------- §5.2 state.snapshot / state.patch ----------

export interface StateSnapshotPayload {
    projectState: ProjectState;
}

export interface StatePatchPayload {
    version: number;
    ops: PatchOp[];
}

export interface PatchOp {
    op: 'add' | 'replace' | 'remove';
    path: string;
    value?: unknown;
}

// ---------- §3.2 ready ----------

export interface ReadyPayload {
    sessionId: string;
    serverVersion: string;
    protocolVersion: number;
    /**
     * M16 P6.2：server 同意的 business protocol version；为 undefined 表示对端是
     * 旧后端（M15-）。client 收到后双向校验 acceptedV === CLIENT_V 才继续。
     */
    accepted_v?: number;
    reconnectToken: string;
    projectState: ProjectState;
    // M5.5：wall 元数据
    wallId?: string;
    alias?: string;
    /** lock 时间戳；null = 可编辑，非 null = 锁定（前端 readonly UI）。2026-05-14 rename from publishedAt */
    lockedAt?: number;
    /** wall 创建者 UUID（M5.5 walls.owner_uuid） */
    ownerUuid?: string;
    /** 当前 session 玩家 UUID，供前端判 isOwner = (selfUuid === ownerUuid) */
    selfUuid?: string;
    // M6-D：全量 TemplateSpec 列表（builtin + server-side templates）
    templates?: import('./template').TemplateSpec[];
    // 0.4.0-P2-F：该 wall 当前所有变量快照（VariableDto 形式，referencedByWalls 已剔除）
    variables?: import('./variable').Variable[];
    // 0.4.2：该 wall 的变量别名映射（fullName → alias）；前端 VariableAliasStore 初始化用。
    aliases?: Record<string, string>;
    // 0.7.0 P1：该 wall 当前所有脚本规则快照（服务端顺序）；前端 ScriptStore 初始化用。
    scripts?: ScriptRule[];
}

// ---------- §6.1 error ----------

export interface ErrorPayload {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>;
}

// ---------- §5.11 变量系统 op payloads（0.4.0-P1）----------
//
// 协议契约见 docs/protocol.md §5.11 + docs/dynamic-data.md §3。
// 后端 op 路由 (B 任务) 解析以下 payload；ack 形态另见 docs/dynamic-data.md §3.1。
// 类型 import 已在文件顶部声明：`VarType` / `VariablePatch`。

/**
 * `variable.create`：玩家在当前 wall 上创建用户变量。
 * server 自动加 {@code user:<wallId>/} 前缀，ack 回 {@code { fullName }}。
 *
 * 错误：{@code VARIABLE_EXISTS} / {@code INVALID_PAYLOAD} / {@code PERMISSION_DENIED}
 * （缺 {@code canvas.var.write.own}）
 */
export interface VariableCreatePayload {
    /** 不含 {@code user:<wallId>/} 前缀；regex 由后端校验 */
    name: string;
    type: VarType;
    defaultValue?: string | null;
}

/**
 * `variable.update`：改类型 / 改 default。仅 user/* 变量；插件 / 系统 / PAPI 变量不可改。
 * 错误：{@code VARIABLE_NOT_FOUND} / {@code PERMISSION_DENIED}
 */
export interface VariableUpdatePayload {
    fullName: string;
    patch: VariablePatch;
}

/**
 * `variable.set`：玩家手动改 user/* 变量当前值。
 * 错误：{@code VARIABLE_NOT_FOUND} / {@code VARIABLE_TYPE_MISMATCH} / {@code PERMISSION_DENIED}
 */
export interface VariableSetPayload {
    fullName: string;
    value: string;
}

/**
 * `variable.delete`：删除 user/* 变量。引用该变量的 element 渲染时走 fallback。
 */
export interface VariableDeletePayload {
    fullName: string;
}

/**
 * `variable.bind`：把 user/* 变量绑给插件 push 接管。{@code boundTo = null} 取消接管。
 * 错误：{@code VARIABLE_NOT_FOUND} / {@code PERMISSION_DENIED}（缺 {@code canvas.var.bind}）
 */
export interface VariableBindPayload {
    fullName: string;
    /** 插件名（与 NamespaceInfo.pluginName 对齐）；null = 解绑 */
    boundTo: string | null;
}

// ---------- §7 时间轴（v3 新增）----------
//
// 形态对应后端 record（docs/timeline.md §2 / docs/protocol.md §7）；enum 字段（loopMode /
// easing.type / trigger.type）的 wire 形态由后端 record 的 @JsonProperty 显式映射为 camelCase
// （同 BlendMode 范式：Java enum 常量 + @JsonProperty 注解），双端对齐，非 Java enum name 直出。

/** 时间轴播放模式（docs/protocol.md §7）。 */
export type LoopMode = 'once' | 'loop' | 'pingPong';

/**
 * 缓动函数类型（docs/protocol.md §7 / docs/rendering.md §9.3）。
 * {@code easeIn / easeOut / easeInOut} 是 {@code cubicBezier} 的 CSS 同名预设控制点。
 */
export type EasingType = 'linear' | 'easeIn' | 'easeOut' | 'easeInOut' | 'cubicBezier';

/**
 * 触发方式（docs/protocol.md §7 / docs/timeline.md §5）。0.6 范围 = 三种；
 * {@code playerNear} 留 0.7（需从零建事件层）。
 */
export type TriggerType = 'manual' | 'variableChange' | 'schedule';

/**
 * 关键帧缓动。{@code bezier} 仅 {@code cubicBezier} 用：{@code [x1, y1, x2, y2]} 四元，
 * 约束 {@code x1, x2 ∈ [0, 1]}（与 CSS 一致）。非 cubicBezier 类型 {@code bezier} 省略。
 * 缺省缓动 wire 形态为 {@code { type: 'linear' }}。
 */
export interface Easing {
    type: EasingType;
    bezier?: [number, number, number, number];
}

/**
 * 关键帧值的多态联合：数值 / 字符串 / Fill。复用 element fill 的多态范式
 * （string → Solid / object 按 type 分流），亦可为 {@code ${var:X}} 字符串。
 * - number：数值类属性（x/y/w/h/rotation/opacity）
 * - string：离散类 text、颜色 hex、或变量模板
 * - Fill：fill 属性（solid / linear / radial）
 */
export type KfValue = number | string | Fill;

/**
 * 关键帧（docs/protocol.md §7 / docs/timeline.md §2.3）。
 * {@code easing} 是到<b>下一个</b>关键帧的缓动 —— 末帧的 easing 无意义（rendering.md §9.1）。
 */
export interface Keyframe {
    id: string; // "kf-<8hex>"，coalesce / patch 定位用
    property: string; // "x"/"y"/"w"/"h"/"rotation"/"opacity"/"color"/"fill"/"text" 等
    timeMs: number; // 在 timeline 内的时刻
    value: KfValue;
    easing: Easing;
}

/** 触发配置（docs/protocol.md §7）。{@code params} 按 trigger 类型携带参数。 */
export interface TriggerConfig {
    type: TriggerType;
    params: Record<string, string>; // 如 variableChange 的 fullName
}

/**
 * 时间轴（docs/protocol.md §7 / docs/timeline.md §2.1）。
 *
 * <p>{@code tracks} 的 key 是 elementId，值是该元素<b>所有属性混在一起</b>、按 {@code timeMs}
 * 升序的关键帧列表。前端按 {@code (elementId, property)} 二级分组渲染成多条属性子轨
 * （方案 B，见 docs/timeline.md §2.2）。</p>
 */
export interface Timeline {
    id: string; // "tl-<8hex>"
    name: string; // 用户可读名
    durationMs: number; // 总时长
    fps: number; // 该条时间轴帧率（默认 20，受 config timeline.max-fps 钳）
    loopMode: LoopMode;
    trigger: TriggerConfig; // 触发方式
    tracks: Record<string, Keyframe[]>; // key = elementId
}

// ---------- §8 墙脚本（0.7.0 P1，协议 v4 新增）----------
//
// 形态对应后端 record（docs/scripting.md §2.1 / §2.2）；wire 为扁平字段 + {@code type}
// 判别（camelCase），由后端 Trigger/Action 自定义 Serializer/Deserializer 双向映射，
// 与 KfValue / Timeline.trigger 同范式。本文件只镜像 wire 形态，结构校验在后端
// ScriptRuleValidator；前端镜像层（store）不做语义校验。

/**
 * 脚本触发器多态联合（docs/scripting.md §2.2 + scripting-0.7.1.md §6，0.7.0 六种 + 0.7.1 三种）。
 * {@code playerJoin / playerKill / playerQuit / rightClickWall} 是全局事件面
 * （需 {@code canvas.script.trigger.global}）；{@code playerNear / playerLeaveRange} 是墙级
 * （仅基础编辑权限，同 PlayerNearSampler）。
 */
export type ScriptTrigger =
    | { type: 'variableChange'; fullName: string }
    | { type: 'timer'; intervalSeconds: number }
    | { type: 'playerJoin' }
    | { type: 'playerKill' }
    | { type: 'playerNear'; rangeBlocks: number }
    | { type: 'wallReady' }
    // 0.7.1-P2：3 个新触发器（右键墙 / 玩家离开靠近区域 / 玩家退服）。
    | { type: 'rightClickWall' }
    | { type: 'playerLeaveRange'; rangeBlocks: number }
    | { type: 'playerQuit' };

/**
 * 脚本动作多态联合（docs/scripting.md §2.2，8 种动作 + {@code if} 条件分支可递归嵌套）。
 * {@code if} 的两个分支 wire 字段名为 {@code then} / {@code else}（后端 Java 侧
 * {@code elseActions} 仅为避开关键字，wire 上仍是 {@code else}）；空分支 = 空数组（非 null）。
 * {@code playTimeline.seekMs} 仅 {@code op = 'seek'} 时携带，其余省略不上 wire。
 */
export type ScriptAction =
    | { type: 'setVariable'; fullName: string; value: string }
    | { type: 'incrementVariable'; fullName: string; delta: number }
    | { type: 'setElementProperty'; elementId: string; property: string; value: string }
    | { type: 'playTimeline'; timelineId: string; op: 'play' | 'pause' | 'seek'; seekMs?: number }
    | { type: 'playSound'; soundId: string; volume: number; pitch: number; scope: 'near' | 'all' }
    | { type: 'wait'; ms: number }
    | { type: 'runCommand'; templateId: string; params: Record<string, string> }
    | { type: 'log'; message: string }
    | { type: 'if'; condition: string; then: ScriptAction[]; else: ScriptAction[] }
    // 0.7.1-P1：友好元素积木的序列化目标 + 4 个低风险新动作。
    // setElementProperties = 批量设元素属性（友好积木皮肤 kind 透传，后端执行忽略）；
    // nudgeElement = 相对移动；sendMessage = 给触发玩家发消息；setRandomVariable = 设随机数；
    // scaleVariable = 变量乘/除；playTimelineAwait = 播时间轴并等播完（Runner 挂起续接）。
    | { type: 'setElementProperties'; elementId: string; patch: Record<string, string>; kind: string }
    | { type: 'nudgeElement'; elementId: string; dx: number; dy: number }
    | { type: 'sendMessage'; text: string; channel: 'chat' | 'actionbar' | 'title' }
    | { type: 'setRandomVariable'; fullName: string; min: number; max: number }
    | { type: 'scaleVariable'; fullName: string; op: 'multiply' | 'divide'; factor: number }
    | { type: 'playTimelineAwait'; timelineId: string }
    // 0.7.1-P2：有界循环「重复 N 次」。count ∈ [1,100]；body 为每轮执行的动作（由后端
    // ScriptRunner 展开 count 轮，blockId 用 `${path}/body/${i}` 不带 round——前后端同构）。
    | { type: 'repeat'; count: number; body: ScriptAction[] };

/**
 * 脚本规则（docs/scripting.md §2.1）。{@code id / wallId} 服务端权威（create 时不带）。
 * {@code blockLayout} 是前端积木 UI 的坐标 JSON 字符串——镜像层与后端都不解析，
 * 仅 P4/P5 积木编辑器读写。
 */
export interface ScriptRule {
    id: string; // "sr-<8hex>"
    wallId: string;
    enabled: boolean;
    name: string;
    trigger: ScriptTrigger;
    actions: ScriptAction[];
    blockLayout: string;
}

/**
 * 0.7.0-P3 A2（K11）：试跑单步轨迹（S→C 推送 op {@code script.trace} 的 steps 项）。
 * {@code blockId} 是动作树路径（{@code trigger} / {@code actions/0} /
 * {@code actions/2/then/1}，与积木树同构——P5 积木高亮按此定位）；
 * {@code detail} 为 null 时后端省略不上 wire。
 */
export interface ScriptTraceStep {
    blockId: string;
    kind: 'trigger' | 'condition' | 'action';
    result: 'ok' | 'skipped' | 'blocked' | 'error';
    detail?: string;
}

/**
 * S→C op {@code script.trace} 的 payload（{@code script.test} ack 只回
 * {@code {accepted, ruleId}}；轨迹在 run 真正结束后推这条——合法规则可串 wait
 * 至分钟级，同步等待不可行）。
 */
export interface ScriptTracePayload {
    ruleId: string;
    steps: ScriptTraceStep[];
}
