import { onKeyStroke, useEventListener } from '@vueuse/core';
import type { ActiveTool } from '@/stores/ui';
import { isDrawTool, useUiStore } from '@/stores/ui';
import { useClipboard } from '@/composables/useClipboard';

/**
 * 全部 V/M/H/L/A/C/S/B 工具快捷键 + Esc + Ctrl+0/+/- zoom 快捷键 + Space-hold 临时手型。
 * input/textarea 内输入时跳过单字符工具快捷键。
 *
 * M17 F4：
 *   - H 键激活 hand 工具
 *   - 按住 Space 临时切到 hand（Figma 标准）；松开恢复原工具。重复 keydown
 *     （OS 按住自动重复）只在首次触发时切换，避免覆盖用户中途的工具切换。
 */
export function useCanvasShortcuts() {
    const ui = useUiStore();
    const clipboard = useClipboard();

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
    onKeyStroke(['v', 'V'], (e) => {
        // Ctrl/Cmd+V 由下方 paste 处理；这里只接裸 V → select 工具
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('select');
    });
    onKeyStroke(['m', 'M'], () => { if (!inEditable()) ui.setTool('move'); });
    onKeyStroke(['h', 'H'], () => { if (!inEditable()) ui.setTool('hand'); });
    onKeyStroke(['l', 'L'], () => { if (!inEditable()) ui.setTool('line'); });
    onKeyStroke(['a', 'A'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('arrow');
    });
    onKeyStroke(['c', 'C'], (e) => {
        if (e.ctrlKey || e.metaKey) {
            // F1 Ctrl/Cmd+C：复制选中元素到 system clipboard
            if (inEditable()) return;
            e.preventDefault();
            void clipboard.copy();
            return;
        }
        if (!inEditable()) ui.setTool('circle');
    });
    onKeyStroke(['v', 'V'], (e) => {
        if (!(e.ctrlKey || e.metaKey)) return;
        // F1 Ctrl/Cmd+V：粘贴 clipboard 内的 HikariCanvas 数据
        // 不在 editable 焦点（input/textarea/contenteditable）才接管；否则让浏览器走常规 paste
        if (inEditable()) return;
        e.preventDefault();
        void clipboard.paste();
    });
    onKeyStroke(['s', 'S'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('star');
    });
    onKeyStroke(['b', 'B'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('brush');
    });
    // M18 Live Paint：G 油漆桶（Photoshop / Figma 行业标准 fill bucket 键位）
    onKeyStroke(['g', 'G'], (e) => {
        if (e.ctrlKey || e.metaKey) return;
        if (!inEditable()) ui.setTool('paint-bucket');
    });

    // M17 F4：Space-hold 临时手型工具。保存原工具到 spaceSavedTool，松开 Space 恢复。
    // 用 useEventListener 而非 onKeyStroke：keydown 的 repeat 标志 + keyup 配对要原始事件。
    let spaceSavedTool: ActiveTool | null = null;
    useEventListener(window, 'keydown', (e: KeyboardEvent) => {
        if (e.code !== 'Space') return;
        if (inEditable()) return;
        // 阻止页面滚动（即使没 overflow，也避免视口偏移）
        e.preventDefault();
        // OS 按住自动重复：repeat=true 时不再覆盖原工具
        if (e.repeat || spaceSavedTool !== null) return;
        spaceSavedTool = ui.activeTool;
        if (ui.activeTool !== 'hand') ui.setTool('hand');
    });
    useEventListener(window, 'keyup', (e: KeyboardEvent) => {
        if (e.code !== 'Space') return;
        if (spaceSavedTool === null) return;
        const restore = spaceSavedTool;
        spaceSavedTool = null;
        // 仅当当前仍是 hand 时恢复——用户中途手动切到别的工具就尊重新选择
        if (ui.activeTool === 'hand' && restore !== 'hand') ui.setTool(restore);
    });
    // 失焦时若 space 仍在"按住"假象中（用户 Alt+Tab 切走）→ 恢复防卡死
    useEventListener(window, 'blur', () => {
        if (spaceSavedTool === null) return;
        const restore = spaceSavedTool;
        spaceSavedTool = null;
        if (ui.activeTool === 'hand' && restore !== 'hand') ui.setTool(restore);
    });
}
