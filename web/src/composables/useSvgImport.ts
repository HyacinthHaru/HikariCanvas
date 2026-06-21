/**
 * useSvgImport.ts — Task 13 MVP 闸 / Task 16 image 支持
 *
 * SVG 文本 → 一组 element.add WS 指令，插入当前工程的可写图层。
 * 刷新靠后端 state.patch 推回，不手动改 store。
 *
 * Task 16：嵌入 <image> 的 data URL 先 fetch→blob→File，POST /api/upload，
 * 取 source hash，再发 element.add { type:'image', props:{ x,y,w,h,source } }。
 */

import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
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
    const net = useNetworkStore();
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
            if (draft.type === 'image' && draft.dataUrl) {
                // Task 16: data URL → blob → FormData → POST /api/upload → source → element.add
                const sent = await uploadImageDraft(draft.dataUrl, draft.props, targetLayer.id);
                if (sent) count++;
            } else {
                const sent = ws.send('element.add', {
                    type: draft.type,
                    props: draft.props,
                    layerId: targetLayer.id,
                });
                if (sent) count++;
            }
        }

        return { count };
    }

    /**
     * Task 16: 将嵌入 <image> 的 data URL 上传至 /api/upload，
     * 取 source hash 后发送 element.add { type:'image', props:{ x,y,w,h,source } }。
     */
    async function uploadImageDraft(
        dataUrl: string,
        props: Record<string, unknown>,
        layerId: string,
    ): Promise<boolean> {
        try {
            // 1. data URL → blob
            const resp = await fetch(dataUrl);
            const blob = await resp.blob();

            // 2. blob → File（给 FormData 提供 filename）
            const file = new File([blob], 'embedded.png', { type: blob.type || 'image/png' });

            // 3. POST /api/upload
            const fd = new FormData();
            if (net.sessionId) fd.append('sessionId', net.sessionId);
            fd.append('file', file);
            const uploadResp = await fetch('/api/upload', { method: 'POST', body: fd });
            if (!uploadResp.ok) return false;
            const result = await uploadResp.json() as { source: string };

            // 4. element.add
            const sent = ws.send('element.add', {
                type: 'image',
                props: { ...props, source: result.source },
                layerId,
            });
            return !!sent;
        } catch (e) {
            console.warn('[useSvgImport] image upload failed:', (e as Error).message);
            return false;
        }
    }

    return { importSvg };
}
