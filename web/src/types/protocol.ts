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

export type Element = TextElement | RectElement | IconElement;

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
    fill?: string;
    stroke?: Stroke;
}

export interface IconElement extends BaseElement {
    type: 'icon';
    /** 图标资源名，由 /api/template-asset/icons/{source}.png 提供 */
    source: string;
    /** 染色 #RRGGBB[AA]；空 = 原色 */
    tint?: string;
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
    reconnectToken: string;
    projectState: ProjectState;
    // M5.5：wall 元数据
    wallId?: string;
    alias?: string;
    publishedAt?: number;
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
