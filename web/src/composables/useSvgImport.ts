/**
 * useSvgImport.ts — Task 13 MVP 闸
 *
 * SVG 文本 → 一组 element.add WS 指令，插入当前工程的可写图层。
 * 刷新靠后端 state.patch 推回，不手动改 store。
 */

import { useProjectStore } from '@/stores/project';
import { getWsClient } from '@/network/wsClient';
import { svgToElements } from '@/lib/svg/svgToElements';
import type { Layer } from '@/types/protocol';

/**
 * activeLayer 不锁直接用；否则扫所有 layer 取第一个非锁定的；都没有返 null。
 * 与 useClipboard.ts 内的私有 pickWritableLayer 等价（各自独立实现，不相互 import）。
 */
function pickWritableLayer(active: Layer, layers: Layer[]): Layer | null {
    if (active && active.id && !active.locked) return active;
    for (const l of layers) {
        if (!l.locked) return l;
    }
    return null;
}

export function useSvgImport(): {
    importSvg(svg: string): Promise<{ count: number }>;
} {
    const project = useProjectStore();
    const ws = getWsClient();

    async function importSvg(svg: string): Promise<{ count: number }> {
        // 1. 转换 SVG → ElementDraft[]
        const drafts = svgToElements(svg);
        if (drafts.length === 0) return { count: 0 };

        // 2. 选目标层
        const targetLayer = pickWritableLayer(
            project.activeLayer,
            project.state?.layers ?? [],
        );
        if (!targetLayer) {
            console.warn('[useSvgImport] no writable layer — import skipped');
            return { count: 0 };
        }

        // 3. 逐个发送 element.add
        let count = 0;
        for (const draft of drafts) {
            const sent = ws.send('element.add', {
                type: draft.type,
                props: draft.props,
                layerId: targetLayer.id,
            });
            if (sent) count++;
        }

        return { count };
    }

    return { importSvg };
}
