/**
 * useSvgImport.ts — SVG 导入 + 内嵌 image 支持
 *
 * SVG 文本 → 一组 element.add WS 指令，插入当前工程的可写图层。
 * 刷新靠后端 state.patch 推回，不手动改 store。
 *
 * 嵌入 <image> 的 data URL 先 fetch→blob→File，POST /api/upload，
 * 取 source hash，再发 element.add { type:'image', props:{ x,y,w,h,source } }。
 *
 * 计数按服务端回执统计：以前只看"这一帧发出去了没有"，服务端把元素判为非法丢掉时，
 * 对话框照样显示"成功导入 N 个"，用户对着少了一半的画面找不到北。
 */

import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';
import { getWsClient } from '@/network/wsClient';
import { svgToElementsDetailed, type DropCounts } from '@/lib/svg/svgToElements';
import { SvgImportError } from '@/lib/svg/svgSecurity';
import type { Layer } from '@/types/protocol';

/**
 * 一批最多同时发多少条 element.add，以及批与批之间的间隔。
 *
 * <p>服务端单会话限流是 40 条 / 2 秒，超了直接拒，一分钟内被拒 5 次还会踢断连接。
 * 一个几十个形状的图标集一次性全发出去，后半截必被丢、人还可能被踢下线。
 * 按批发就都不会撞上。</p>
 */
const BATCH_SIZE = 16;
const BATCH_INTERVAL_MS = 1100;

/** 单条 element.add 等回执的时限。批量导入时服务端是排队处理的，给得比默认 5s 宽一点。 */
const ADD_ACK_TIMEOUT_MS = 15000;

/** 导入结果。count = 服务端确认收下的元素数；failed = 发出去被拒 / 没等到回执的数量。 */
export interface SvgImportResult {
    count: number;
    /** 服务端拒收或没回执的元素数。 */
    failed: number;
    /** 出口预检阶段就没发出去的形状（按原因分类）。 */
    dropped: DropCounts;
}

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

const sleep = (ms: number): Promise<void> => new Promise(r => setTimeout(r, ms));

export function useSvgImport(): {
    importSvg(svg: string): Promise<SvgImportResult>;
} {
    const project = useProjectStore();
    const net = useNetworkStore();
    const { t } = useI18n();
    const ws = getWsClient();

    async function importSvg(svg: string): Promise<SvgImportResult> {
        // 0. 画板锁定就别导：SVG 导入是一串 element.add，后端按纪律不看 lock 会照单全收，
        //    前端是锁的唯一执行者。抛错走对话框的失败分支，用户能看到原因（早期这里没守卫，
        //    锁定的作品可以被塞进任意矢量图形）。
        if (project.isLocked) {
            throw new SvgImportError('WALL_LOCKED', 'wall is locked');
        }
        // 1. 转换 SVG → ElementDraft[]（出口预检已在里面收敛 / 剔除服务端必拒的形状）
        const { drafts, dropped } = svgToElementsDetailed(svg);
        if (drafts.length === 0) {
            reportDropped(0, dropped);
            return { count: 0, failed: 0, dropped };
        }

        // 2. 选目标层
        const targetLayer = pickWritableLayer(
            project.activeLayer,
            project.state?.layers ?? [],
        );
        if (!targetLayer) {
            console.warn('[useSvgImport] no writable layer — import skipped');
            return { count: 0, failed: 0, dropped };
        }

        // 3. 分批发送 element.add，按回执统计
        let count = 0;
        let failed = 0;
        for (let i = 0; i < drafts.length; i += BATCH_SIZE) {
            if (i > 0) await sleep(BATCH_INTERVAL_MS);
            const batch = drafts.slice(i, i + BATCH_SIZE);
            const results = await Promise.all(batch.map(async (draft) => {
                if (draft.type === 'image' && draft.dataUrl) {
                    return uploadImageDraft(draft.dataUrl, draft.props, targetLayer.id);
                }
                try {
                    await ws.sendWithAck('element.add', {
                        type: draft.type,
                        props: draft.props,
                        layerId: targetLayer.id,
                    }, ADD_ACK_TIMEOUT_MS);
                    return true;
                } catch (e) {
                    console.warn('[useSvgImport] element.add rejected:', (e as Error).message);
                    return false;
                }
            }));
            for (const ok of results) {
                if (ok) count++; else failed++;
            }
        }

        reportDropped(failed, dropped);
        return { count, failed, dropped };
    }

    /** 有元素没进去就在状态栏 / 日志里说一声，别让用户对着"成功 N 个"猜。 */
    function reportDropped(failed: number, dropped: DropCounts): void {
        const droppedTotal = Object.values(dropped).reduce((a, b) => a + (b ?? 0), 0);
        const missed = failed + droppedTotal;
        if (missed === 0) return;
        const msg = t.value.svgImport.partial(missed);
        net.lastError = msg;
        // 日志里带上分类计数，排查时有据可依（这些代号不进用户可见文案）
        const detail = Object.entries(dropped).map(([k, v]) => `${k}=${v}`).join(' ');
        net.pushLog('err', `${msg} [rejected=${failed}${detail ? ' ' + detail : ''}]`);
    }

    /**
     * 将嵌入 <image> 的 data URL 上传至 /api/upload，
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
            const result = await uploadResp.json() as { source: string; width?: number; height?: number };

            // 4. element.add。<image> 没写 width/height 时 bbox 是 0，服务端要求宽高 ≥1，
            //    用上传回来的真实像素尺寸补上（原来这类图片一律被服务端拒掉）。
            const w = sizeOr(props.w, result.width);
            const h = sizeOr(props.h, result.height);
            await ws.sendWithAck('element.add', {
                type: 'image',
                props: { ...props, w, h, source: result.source },
                layerId,
            }, ADD_ACK_TIMEOUT_MS);
            return true;
        } catch (e) {
            console.warn('[useSvgImport] image import failed:', (e as Error).message);
            return false;
        }
    }

    return { importSvg };
}

/** 取 draft 的尺寸；没有就用上传回来的真实像素尺寸，再没有就 1。 */
function sizeOr(drafted: unknown, natural: number | undefined): number {
    const d = typeof drafted === 'number' && Number.isFinite(drafted) ? Math.round(drafted) : 0;
    if (d >= 1) return d;
    const n = typeof natural === 'number' && Number.isFinite(natural) ? Math.round(natural) : 0;
    return n >= 1 ? n : 1;
}
