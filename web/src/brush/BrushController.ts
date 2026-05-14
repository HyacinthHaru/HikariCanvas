// M12-C：笔刷控制器。接管 PointerEvent → ws.send brush.start/point/end/cancel
// + floating preview off-canvas 实时反馈（绕开 WS round-trip 延迟）。
//
// 工作流：
//   1. pointerDown(props) → ws.sendWithAck('brush.start')，等 ack 拿 strokeId
//   2. pointerMove → 累积 raw point + 增量画 floating canvas + 50ms 节流批量 brush.point
//   3. pointerUp → flush + ws.send('brush.end')；floating 100ms 后清除
//   4. pointerCancel → ws.send('brush.cancel') + 清 floating
//
// strokeId 异步获取的特殊处理：pointerDown 立刻起 floating + 累积 points，
// 但要等 brush.start ack 后才能 ws.send('brush.point')。pendingPoints 内缓冲；
// ack 到达时 flushBuffer。

import { getWsClient } from '@/network/wsClient';
import { fillToCss } from '@/render/fill';
import type { Fill } from '@/types/protocol';

export interface BrushProps {
    size: number;
    fill: Fill;
    opacity: number;
    pressureSize: boolean;
    pressureOpacity: boolean;
    smoothing: number;
    dither: boolean;
}

interface RawPoint {
    x: number;
    y: number;
    pressure: number;
}

export interface BrushControllerConfig {
    container: () => HTMLElement | null;
    canvasWidth: () => number;
    canvasHeight: () => number;
    getLayerId: () => string | null;
    onStrokeFinished?: () => void;
}

const BATCH_INTERVAL_MS = 50;
const FALLBACK_MOUSE_PRESSURE = 0.5;

export class BrushController {
    private readonly cfg: BrushControllerConfig;
    private readonly ws = getWsClient();

    private isDrawing = false;
    private strokeId: string | null = null;
    private currentProps: BrushProps | null = null;
    private pendingPoints: RawPoint[] = [];
    private batchTimer: number | null = null;

    private floating: HTMLCanvasElement | null = null;
    private floatingCtx: CanvasRenderingContext2D | null = null;
    private drawnPoints: RawPoint[] = [];

    constructor(cfg: BrushControllerConfig) {
        this.cfg = cfg;
    }

    isActive(): boolean { return this.isDrawing; }

    pointerDown(stagePt: RawPoint, props: BrushProps): void {
        if (this.isDrawing) return;
        this.isDrawing = true;
        this.strokeId = null;
        this.currentProps = props;
        this.pendingPoints = [];
        this.drawnPoints = [];

        this.setupFloating();

        this.ws.sendWithAck('brush.start', {
            props: this.serializeProps(props),
            layerId: this.cfg.getLayerId(),
        }, 5000).then((ack) => {
            const ackObj = ack as { strokeId?: string };
            if (!this.isDrawing && this.strokeId === null) {
                if (ackObj.strokeId) {
                    this.ws.send('brush.cancel', { strokeId: ackObj.strokeId });
                }
                return;
            }
            if (typeof ackObj.strokeId !== 'string') {
                this.cleanup();
                return;
            }
            this.strokeId = ackObj.strokeId;
            this.flushPendingPoints();
        }).catch(() => {
            this.cleanup();
        });

        this.appendPoint(stagePt);
    }

    pointerMove(stagePt: RawPoint): void {
        if (!this.isDrawing) return;
        this.appendPoint(stagePt);
    }

    pointerUp(stagePt: RawPoint): void {
        if (!this.isDrawing) return;
        this.appendPoint(stagePt);
        this.isDrawing = false;
        this.tryEnd();
    }

    pointerCancel(): void {
        if (!this.isDrawing && this.strokeId === null) return;
        this.isDrawing = false;
        if (this.strokeId) {
            this.ws.send('brush.cancel', { strokeId: this.strokeId });
        }
        this.cleanup();
    }

    private tryEnd(attempts = 0): void {
        if (this.strokeId !== null) {
            this.flushPendingPoints();
            this.ws.send('brush.end', { strokeId: this.strokeId });
            this.strokeId = null;
            window.setTimeout(() => this.cleanupFloating(), 100);
            this.cfg.onStrokeFinished?.();
            return;
        }
        if (attempts > 40) {
            this.cleanup();
            return;
        }
        window.setTimeout(() => this.tryEnd(attempts + 1), 50);
    }

    private appendPoint(pt: RawPoint): void {
        this.pendingPoints.push(pt);
        this.paintToFloating(pt);
        if (this.batchTimer === null && this.strokeId !== null) {
            this.batchTimer = window.setTimeout(() => {
                this.batchTimer = null;
                this.flushPendingPoints();
            }, BATCH_INTERVAL_MS);
        }
    }

    private flushPendingPoints(): void {
        if (this.batchTimer !== null) {
            window.clearTimeout(this.batchTimer);
            this.batchTimer = null;
        }
        if (this.strokeId === null || this.pendingPoints.length === 0) return;
        const wire = this.pendingPoints.map((p) => [p.x, p.y, p.pressure]);
        this.ws.send('brush.point', { strokeId: this.strokeId, points: wire });
        this.pendingPoints = [];
    }

    private paintToFloating(pt: RawPoint): void {
        const ctx = this.floatingCtx;
        if (!ctx || !this.currentProps) return;
        this.drawnPoints.push(pt);
        if (this.drawnPoints.length < 2) return;
        const prev = this.drawnPoints[this.drawnPoints.length - 2];
        const cur = pt;
        const avgP = (prev.pressure + cur.pressure) / 2;
        const size = this.currentProps.pressureSize
            ? Math.max(1, this.currentProps.size * Math.max(0.05, avgP))
            : Math.max(1, this.currentProps.size);
        let alpha = this.currentProps.opacity;
        if (this.currentProps.pressureOpacity) alpha *= Math.max(0.05, avgP);
        ctx.globalAlpha = Math.min(1, alpha);
        ctx.strokeStyle = fillToCss(this.currentProps.fill) ?? '#000000';
        ctx.lineWidth = size;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.beginPath();
        ctx.moveTo(prev.x, prev.y);
        ctx.lineTo(cur.x, cur.y);
        ctx.stroke();
    }

    private setupFloating(): void {
        const container = this.cfg.container();
        if (!container) return;
        const c = document.createElement('canvas');
        c.width = this.cfg.canvasWidth();
        c.height = this.cfg.canvasHeight();
        c.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:15;';
        container.appendChild(c);
        const ctx = c.getContext('2d');
        if (!ctx) {
            c.remove();
            return;
        }
        ctx.imageSmoothingEnabled = false;
        this.floating = c;
        this.floatingCtx = ctx;
    }

    private cleanupFloating(): void {
        if (this.floating) {
            this.floating.remove();
            this.floating = null;
            this.floatingCtx = null;
        }
        this.drawnPoints = [];
    }

    private cleanup(): void {
        this.isDrawing = false;
        this.strokeId = null;
        this.pendingPoints = [];
        if (this.batchTimer !== null) {
            window.clearTimeout(this.batchTimer);
            this.batchTimer = null;
        }
        this.cleanupFloating();
        this.currentProps = null;
    }

    private serializeProps(p: BrushProps): Record<string, unknown> {
        return {
            size: p.size,
            fill: p.fill,
            opacity: p.opacity,
            pressureSize: p.pressureSize,
            pressureOpacity: p.pressureOpacity,
            smoothing: p.smoothing,
            renderMode: p.dither ? 'dither' : 'clean',
        };
    }
}

/** 从 PointerEvent 提取压感（鼠标默认 0.5，触屏/数位板用 spec 值）。 */
export function extractPressure(e: PointerEvent): number {
    if (e.pointerType === 'mouse') return FALLBACK_MOUSE_PRESSURE;
    return e.pressure > 0 ? e.pressure : FALLBACK_MOUSE_PRESSURE;
}
