/**
 * Live Paint Worker — buildGraph 移出主线程。
 *
 * 协议：主线程 postMessage({ type: 'build', requestId, elements, canvasWidth, canvasHeight })，
 *       Worker 回 { type: 'ok', requestId, graph } 或 { type: 'err', requestId, message }。
 *
 * 设计：
 *   - Vite `?worker` 后缀自动以 module worker 形式打包；
 *     内部 import `LivePaintCore` / element types 会跟随入 worker bundle。
 *   - requestId 由主线程 caller 单调递增；Worker 不做去重，由主线程 onmessage 校对 pendingRequestId 丢旧响应。
 *   - 失败时不抛——序列化错误回主线程，主线程 console.warn 并清空 graph。
 */

import { buildGraph } from './LivePaintCore';
import type { LivePaintGraph } from './types';
import type { Element } from '@/types/protocol';

export interface LivePaintWorkerRequest {
    type: 'build';
    /** 请求 ID，回应时带回供主线程匹配。 */
    requestId: number;
    elements: Element[];
    canvasWidth: number;
    canvasHeight: number;
}

export interface LivePaintWorkerResponseOk {
    type: 'ok';
    requestId: number;
    graph: LivePaintGraph;
}

export interface LivePaintWorkerResponseErr {
    type: 'err';
    requestId: number;
    message: string;
}

export type LivePaintWorkerResponse = LivePaintWorkerResponseOk | LivePaintWorkerResponseErr;

self.onmessage = async (e: MessageEvent<LivePaintWorkerRequest>) => {
    const req = e.data;
    if (req.type !== 'build') return;
    try {
        // buildGraph 是 async（text 元素走 fontkit dynamic import + fetch
        // 字体二进制）。Worker 内 async 不阻塞主线程；race 保护由主线程 requestId 校对。
        const graph = await buildGraph(req.elements, req.canvasWidth, req.canvasHeight);
        const resp: LivePaintWorkerResponseOk = { type: 'ok', requestId: req.requestId, graph };
        (self as unknown as Worker).postMessage(resp);
    } catch (err) {
        const resp: LivePaintWorkerResponseErr = {
            type: 'err',
            requestId: req.requestId,
            message: err instanceof Error ? err.message : String(err),
        };
        (self as unknown as Worker).postMessage(resp);
    }
};
