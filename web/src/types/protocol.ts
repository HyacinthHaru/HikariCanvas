// WebSocket 协议类型（对齐 docs/protocol.md §2 / §7）。
// 后端 moe.hikari.canvas.state.* records 的 TypeScript 镜像。

export interface Envelope<P = unknown> {
    v: number;
    op: string;
    id?: string;
    ts?: number;
    payload?: P;
}

// ---------- §7 ProjectState ----------

export interface ProjectState {
    version: number;
    canvas: Canvas;
    elements: Element[];
    history: { undoDepth: number; redoDepth: number };
}

export interface Canvas {
    widthMaps: number;
    heightMaps: number;
    background: string; // "#RRGGBB"
}

export type Element = TextElement | RectElement;

interface BaseElement {
    id: string;
    x: number;
    y: number;
    w: number;
    h: number;
    rotation: number;  // 0 / 90 / 180 / 270
    locked: boolean;
    visible: boolean;
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
}

// ---------- §6.1 error ----------

export interface ErrorPayload {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>;
}
