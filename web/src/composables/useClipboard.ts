import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import type { Element, Layer } from '@/types/protocol';

/**
 * M17 F1：复制 / 粘贴。Figma 风格：选中元素 → Ctrl+C 写 system clipboard；
 * Ctrl+V 解析 clipboard → 偏移 +10,+10 → 逐个发 element.add 让 server 生成新 id。
 *
 * 剪贴板格式：
 *   hikari-canvas-v1:{"elements":[...],"timestamp":"<iso>","sourceWallId":"<id>"}
 *
 * magic header `hikari-canvas-v1:` 用于区分普通文本——不带 prefix 的 clipboard 内容
 * 一律不处理（保持用户在文本框中常规 paste 的行为）。
 *
 * 跨 wall：支持。粘贴时只看当前 wall 的 activeLayer / 锁定状态，sourceWallId 仅做来源记录。
 */
export const CLIPBOARD_MAGIC = 'hikari-canvas-v1:';
const PASTE_OFFSET = 10;

interface ClipboardPayload {
    elements: Element[];
    timestamp: string;
    sourceWallId: string | null;
}

export function useClipboard(): {
    copy: () => Promise<void>;
    paste: () => Promise<void>;
} {
    const project = useProjectStore();
    const ui = useUiStore();
    const net = useNetworkStore();
    const ws = getWsClient();
    const { t } = useI18n();

    async function copy(): Promise<void> {
        const ids = Array.from(ui.selectedIds);
        if (ids.length === 0) return;

        // 按 z-order 排序：找到原 element 在 activeLayer.elements 中的索引
        const layerEls = project.activeLayer.elements;
        const indexOf = new Map<string, number>();
        layerEls.forEach((el, i) => indexOf.set(el.id, i));

        // 选中元素可能跨 layer（多选一般在 activeLayer 内，但保险起见用 elementById）
        const items: Array<{ el: Element; zIndex: number }> = [];
        for (const id of ids) {
            const el = project.elementById(id);
            if (!el) continue;
            const zIndex = indexOf.get(id);
            items.push({ el, zIndex: zIndex == null ? Number.MAX_SAFE_INTEGER : zIndex });
        }
        if (items.length === 0) return;
        items.sort((a, b) => a.zIndex - b.zIndex);

        const payload: ClipboardPayload = {
            // 深 clone：避免后续编辑动到 clipboard 镜像 + 剥离响应式 proxy
            elements: items.map(({ el }) => JSON.parse(JSON.stringify(el)) as Element),
            timestamp: new Date().toISOString(),
            sourceWallId: project.wallId,
        };

        const text = CLIPBOARD_MAGIC + JSON.stringify(payload);
        try {
            await navigator.clipboard.writeText(text);
            net.pushLog('meta', t.value.clipboard.copySuccess(payload.elements.length));
        } catch (e) {
            net.pushLog('err', `clipboard.copy failed: ${(e as Error).message}`);
        }
    }

    async function paste(): Promise<void> {
        // 锁定 wall 拒绝粘贴
        if (project.isLocked) {
            net.pushLog('err', t.value.clipboard.pasteRejectedLocked);
            return;
        }

        let raw: string;
        try {
            raw = await navigator.clipboard.readText();
        } catch (e) {
            net.pushLog('err', `clipboard.read failed: ${(e as Error).message}`);
            return;
        }

        if (!raw || !raw.startsWith(CLIPBOARD_MAGIC)) {
            // 不是 HikariCanvas 数据 —— 静默忽略，让用户在文本框中的常规 paste 不受干扰。
            // 这里到达说明 useCanvasShortcuts 已确认不在 editable 焦点；纯无操作即可。
            return;
        }

        const jsonText = raw.slice(CLIPBOARD_MAGIC.length);
        let payload: ClipboardPayload;
        try {
            payload = JSON.parse(jsonText) as ClipboardPayload;
        } catch {
            net.pushLog('err', t.value.clipboard.pasteParseFailed);
            return;
        }
        if (!payload || !Array.isArray(payload.elements) || payload.elements.length === 0) {
            net.pushLog('err', t.value.clipboard.pasteParseFailed);
            return;
        }

        // 找可写 layer：activeLayer 优先；锁定时找第一个非锁定 layer
        const targetLayer = pickWritableLayer(project.activeLayer, project.state?.layers ?? []);
        if (!targetLayer) {
            net.pushLog('err', t.value.clipboard.pasteRejectedLocked);
            return;
        }

        // 按数组顺序粘贴（copy 已经按 z-order 排好；保证新元素堆叠顺序与原一致）
        const newIds: string[] = [];
        for (const original of payload.elements) {
            const props = elementToProps(original, PASTE_OFFSET, PASTE_OFFSET);
            const sentId = ws.send('element.add', {
                type: original.type,
                props,
                layerId: targetLayer.id,
            });
            if (sentId) newIds.push(sentId);
        }

        net.pushLog('meta', t.value.clipboard.pasteSuccess(payload.elements.length));
    }

    return { copy, paste };
}

/** activeLayer 不锁直接用；否则扫所有 layer 取第一个非锁定的；都没有返 null。 */
function pickWritableLayer(active: Layer, layers: Layer[]): Layer | null {
    if (active && active.id && !active.locked) return active;
    for (const l of layers) {
        if (!l.locked) return l;
    }
    return null;
}

/**
 * 拆出 server 接受的 props：把 element 上除 id / type 之外的所有字段拷出来，
 * 再叠上 dx/dy 偏移。Element 内嵌字段（如 PathElement.d / BrushPoint[]）相对
 * element bbox，不随 element 平移变换，直接保留即可。
 */
function elementToProps(el: Element, dx: number, dy: number): Record<string, unknown> {
    const cloned = JSON.parse(JSON.stringify(el)) as Record<string, unknown>;
    // id / type 不传给 server；id 由 server 生成，type 在 envelope 顶层
    delete cloned.id;
    delete cloned.type;
    if (typeof cloned.x === 'number') cloned.x = cloned.x + dx;
    if (typeof cloned.y === 'number') cloned.y = cloned.y + dy;
    return cloned;
}
