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

export interface Layer {
    id: string; // "l-<8hex>"
    name: string;
    visible: boolean;
    locked: boolean;
    opacity: number; // 0..1
    blendMode: BlendMode;
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
 *
 * v1 仅 RightPanel dropdown 暴露 4 预设（none / circle / roundedRect / ellipse）；
 * lasso / 拖动编辑 mask 形状 v2 再做（数据模型已留接口）。
 */
export interface Mask {
    d: string;
    inverted: boolean;
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
