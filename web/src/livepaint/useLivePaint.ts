/**
 * M18-P2 Live Paint 主线程封装 composable。
 *
 * 职责：
 *   - 持有一个 LivePaintWorker 实例
 *   - watch elements / canvasW / canvasH / enabled 变化 → debounce 100ms 后给 worker 发 build 请求
 *   - 维护 pendingRequestId，丢弃旧响应（race 保护）
 *   - findGapAt：主线程 sync 查询 cached graph（不走 worker，命中点查 < 1ms）
 *   - onScopeDispose: terminate worker + clear debounce timer
 *
 * 关键决策：
 *   1. debounce 100ms ── 与 M5 编辑器 5fps 输入防抖一致；用户拖动 element 高频时避免 worker 风暴
 *   2. requestId race ── nextRequestId 单调递增；onmessage 比对 pendingRequestId 只接最新
 *   3. JSON 深 clone ── Vue reactive Proxy 不能 structuredClone；JSON.parse(JSON.stringify) 简单可靠
 *   4. enabled gate ── Live Paint 工具未激活时短路 send()，节省 worker CPU
 *   5. immediate=true ── 组件挂载即一次性触发首次构建（若 enabled）
 */

import { onScopeDispose, ref, watch, type Ref } from 'vue';
import LivePaintWorker from './livePaintWorker?worker';
import type {
    LivePaintWorkerRequest,
    LivePaintWorkerResponse,
} from './livePaintWorker';
import type { Element } from '@/types/protocol';
import { findGapAt } from './LivePaintCore';
import type { GapPolygon, LivePaintGraph } from './types';

export interface UseLivePaintOpts {
    /** 当前所有 visible elements（reactive）。 */
    elements: Ref<Element[]>;
    /** 画布像素宽 / 高（reactive）。 */
    canvasWidth: Ref<number>;
    canvasHeight: Ref<number>;
    /** Live Paint 工具是否激活——非激活时跳过 rebuild 节省 CPU。 */
    enabled: Ref<boolean>;
}

export interface UseLivePaintReturn {
    /** 当前 graph，null = 还在构建 / 无 element / 未激活。 */
    graph: Ref<LivePaintGraph | null>;
    /** 是否正在构建（debounce 中 OR worker 处理中）。UI 可显示 loading。 */
    isBuilding: Ref<boolean>;
    /** 主线程同步 query gap at (x,y)。结果基于 cached graph。 */
    findGapAt(px: number, py: number): GapPolygon | null;
    /** 强制立即重建（跳过 debounce）。 */
    rebuildNow(): void;
}

const DEBOUNCE_MS = 100;

export function useLivePaint(opts: UseLivePaintOpts): UseLivePaintReturn {
    const worker = new LivePaintWorker();
    const graph = ref<LivePaintGraph | null>(null);
    const isBuilding = ref(false);
    let nextRequestId = 0;
    /** 当前正在处理的请求 ID；旧请求回来时丢弃。 */
    let pendingRequestId = -1;
    /** debounce timer handle。 */
    let debounceTimer: number | null = null;

    worker.onmessage = (e: MessageEvent<LivePaintWorkerResponse>) => {
        const resp = e.data;
        // race 保护：只接受最新 request 的响应
        if (resp.requestId !== pendingRequestId) return;
        isBuilding.value = false;
        if (resp.type === 'ok') {
            graph.value = resp.graph;
        } else {
            console.warn('[livepaint] worker error:', resp.message);
            graph.value = null;
        }
    };

    function send(): void {
        if (!opts.enabled.value) {
            graph.value = null;
            isBuilding.value = false;
            return;
        }
        const requestId = ++nextRequestId;
        pendingRequestId = requestId;
        isBuilding.value = true;
        const req: LivePaintWorkerRequest = {
            type: 'build',
            requestId,
            // 深 clone：避免 Vue Proxy / reactive ref 不能 structured-clone
            elements: JSON.parse(JSON.stringify(opts.elements.value)),
            canvasWidth: opts.canvasWidth.value,
            canvasHeight: opts.canvasHeight.value,
        };
        worker.postMessage(req);
    }

    function scheduleRebuild(): void {
        if (debounceTimer !== null) clearTimeout(debounceTimer);
        debounceTimer = window.setTimeout(() => {
            debounceTimer = null;
            send();
        }, DEBOUNCE_MS);
    }

    function rebuildNow(): void {
        if (debounceTimer !== null) {
            clearTimeout(debounceTimer);
            debounceTimer = null;
        }
        send();
    }

    // 监听 elements / canvas size / enabled 变化 → debounce 重建
    // deep:true 让 element.x/y 等深字段变化也触发；immediate:true 挂载即构建
    watch(
        [
            () => opts.elements.value,
            () => opts.canvasWidth.value,
            () => opts.canvasHeight.value,
            () => opts.enabled.value,
        ],
        () => scheduleRebuild(),
        { deep: true, immediate: true },
    );

    onScopeDispose(() => {
        if (debounceTimer !== null) clearTimeout(debounceTimer);
        worker.terminate();
    });

    return {
        graph,
        isBuilding,
        findGapAt(px: number, py: number): GapPolygon | null {
            return graph.value ? findGapAt(graph.value, px, py) : null;
        },
        rebuildNow,
    };
}
