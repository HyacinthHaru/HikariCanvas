// WebSocket 协议类型（对齐 docs/protocol.md §2 / §7）。
// 后端 moe.hikari.canvas.state.* records 的 TypeScript 镜像。
//
// M8 v2 形态：ProjectState.layers + activeLayerId + canvas.gridSize + canvas.guides；
// element 加可选 opacity / blendMode / renderMode。

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
    background: string; // "#RRGGBB"
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
    /** 图标资源名，由 /api/template-asset/icons/{source}.png 提供 */
    source: string;
    /** 染色 #RRGGBB[AA]；空 = 原色 */
    tint?: string;
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
}

// ---------- §6.1 error ----------

export interface ErrorPayload {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>;
}
