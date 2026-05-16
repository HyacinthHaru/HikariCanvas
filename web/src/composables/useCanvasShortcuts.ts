import { onKeyStroke } from '@vueuse/core';
import { isDrawTool, useUiStore } from '@/stores/ui';

/**
 * 全部 V/M/L/A/C/S/B 工具快捷键 + Esc + Ctrl+0/+/- zoom 快捷键。
 * input/textarea 内输入时跳过单字符工具快捷键。
 */
export function useCanvasShortcuts() {
    const ui = useUiStore();

    function inEditable(): boolean {
        const a = document.activeElement as HTMLElement | null;
        return !!a && (a.matches?.('input, textarea, select') || a.isContentEditable);
    }

    // zoom
    onKeyStroke(['=', '+'], (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomIn(); } });
    onKeyStroke('-', (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomOut(); } });
    onKeyStroke('0', (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomReset(); } });

    onKeyStroke('Escape', () => {
        // M9-E：绘制工具激活时按 Esc 切回 select；select / move 工具下按 Esc 等同清空选中
        if (isDrawTool(ui.activeTool)) {
            ui.setTool('select');
            return;
        }
        ui.clearSelection();
    });

    // PS 风格快捷键
    onKeyStroke(['v', 'V'], () => { if (!inEditable()) ui.setTool('select'); });
    onKeyStroke(['m', 'M'], () => { if (!inEditable()) ui.setTool('move'); });
    onKeyStroke(['l', 'L'], () => { if (!inEditable()) ui.setTool('line'); });
    onKeyStroke(['a', 'A'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('arrow');
    });
    onKeyStroke(['c', 'C'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('circle');
    });
    onKeyStroke(['s', 'S'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('star');
    });
    onKeyStroke(['b', 'B'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('brush');
    });
}
