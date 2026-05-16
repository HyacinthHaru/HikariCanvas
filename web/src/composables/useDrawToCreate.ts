import { computed, ref } from 'vue';
import { isDrawTool, useUiStore, type ActiveTool } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';

/**
 * M9-E：drag-to-create（line / arrow / circle / star）+ drawDrag 状态 + commitDraw。
 *
 * - start(pos)：drawTool 激活时在空白处 mousedown 启动
 * - move(pos)：实时更新对角点，驱动预览
 * - end()：mouseup 提交（< 3px 视为误点切回 select）
 * - cancel()：手动取消（拖出窗口 / 工具切换）
 * - drawPreview：暴露给 Konva 的预览 config
 */
export function useDrawToCreate() {
    const ui = useUiStore();
    const ws = getWsClient();

    interface DrawDrag { x1: number; y1: number; x2: number; y2: number; }
    const drawDrag = ref<DrawDrag | null>(null);

    function start(pos: { x: number; y: number }): void {
        drawDrag.value = { x1: pos.x, y1: pos.y, x2: pos.x, y2: pos.y };
    }

    function move(pos: { x: number; y: number }): boolean {
        if (!drawDrag.value) return false;
        drawDrag.value = { ...drawDrag.value, x2: pos.x, y2: pos.y };
        return true;
    }

    /** end 返回 true 表示已 commit（不论是否真正发 ws），false 表示当前无活动 drag。 */
    function end(): boolean {
        if (!drawDrag.value) return false;
        const d = drawDrag.value;
        drawDrag.value = null;
        commitDraw(ui.activeTool, d.x1, d.y1, d.x2, d.y2);
        return true;
    }

    function cancel(): void {
        drawDrag.value = null;
    }

    function isActive(): boolean { return drawDrag.value !== null; }

    type PreviewKind = 'line' | 'arrow' | 'ellipse' | 'star';
    const drawPreview = computed<{ kind: PreviewKind; config: Record<string, unknown> } | null>(() => {
        const d = drawDrag.value;
        if (!d) return null;
        const tool = ui.activeTool;
        const minX = Math.min(d.x1, d.x2);
        const minY = Math.min(d.y1, d.y2);
        const w = Math.abs(d.x2 - d.x1);
        const h = Math.abs(d.y2 - d.y1);

        if (tool === 'line' || tool === 'arrow') {
            return {
                kind: tool === 'arrow' ? 'arrow' : 'line',
                config: {
                    points: [d.x1, d.y1, d.x2, d.y2],
                    stroke: '#000000',
                    strokeWidth: 2,
                    opacity: 0.6,
                    pointerLength: 6,
                    pointerWidth: 6,
                    fill: '#000000',
                    listening: false,
                },
            };
        }
        if (tool === 'circle') {
            return {
                kind: 'ellipse',
                config: {
                    x: minX + w / 2,
                    y: minY + h / 2,
                    radiusX: w / 2,
                    radiusY: h / 2,
                    stroke: '#60a5fa',
                    strokeWidth: 1,
                    fill: 'rgba(96, 165, 250, 0.15)',
                    dash: [4, 3],
                    listening: false,
                },
            };
        }
        if (tool === 'star') {
            const outer = Math.min(w, h) / 2;
            return {
                kind: 'star',
                config: {
                    x: minX + w / 2,
                    y: minY + h / 2,
                    numPoints: 5,
                    innerRadius: outer * 0.5,
                    outerRadius: outer,
                    stroke: '#FFCC00',
                    strokeWidth: 1,
                    fill: 'rgba(255, 204, 0, 0.2)',
                    dash: [4, 3],
                    listening: false,
                },
            };
        }
        return null;
    });

    function commitDraw(tool: ActiveTool, x1: number, y1: number, x2: number, y2: number): void {
        if (!isDrawTool(tool)) return;
        const minX = Math.min(x1, x2);
        const minY = Math.min(y1, y2);
        const dx = Math.abs(x2 - x1);
        const dy = Math.abs(y2 - y1);
        if (dx < 3 && dy < 3) {
            ui.setTool('select');
            return;
        }

        let type = '';
        let props: Record<string, unknown> | null = null;
        switch (tool) {
            case 'line':
            case 'arrow': {
                type = 'path';
                const elX = Math.round(minX);
                const elY = Math.round(minY);
                const sx = Math.round(x1 - minX);
                const sy = Math.round(y1 - minY);
                const ex = Math.round(x2 - minX);
                const ey = Math.round(y2 - minY);
                props = {
                    x: elX,
                    y: elY,
                    w: Math.max(2, Math.round(dx)),
                    h: Math.max(2, Math.round(dy)),
                    d: `M ${sx} ${sy} L ${ex} ${ey}`,
                    stroke: { width: 2, color: '#000000' },
                };
                if (tool === 'arrow') (props as Record<string, unknown>).markerEnd = 'arrow';
                break;
            }
            case 'circle': {
                type = 'circle';
                props = {
                    x: Math.round(minX),
                    y: Math.round(minY),
                    w: Math.max(2, Math.round(dx)),
                    h: Math.max(2, Math.round(dy)),
                    fill: '#3366FF',
                };
                break;
            }
            case 'star': {
                type = 'shape';
                props = {
                    x: Math.round(minX),
                    y: Math.round(minY),
                    w: Math.max(2, Math.round(dx)),
                    h: Math.max(2, Math.round(dy)),
                    kind: 'star',
                    sides: 5,
                    fill: '#FFCC00',
                };
                break;
            }
        }

        if (type && props) {
            ws.send('element.add', { type, props });
        }
        ui.setTool('select');
    }

    return {
        drawDrag,
        drawPreview,
        start,
        move,
        end,
        cancel,
        isActive,
    };
}
