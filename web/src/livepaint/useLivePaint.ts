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
    /**
     * M18-P4：性能跟踪——pendingRequestId → { startedAt, elementCount } 映射。
     * onmessage 内换算 elapsed 后立刻 delete。仅 DEV 启用，prod build 走 import.meta.env.DEV
     * 静态分支被 Vite tree-shake 完整消除（含 Map 实例分配也不发生）。
     */
    const sentAt = import.meta.env.DEV
        ? new Map<number, { startedAt: number; elementCount: number }>()
        : null;

    worker.onmessage = (e: MessageEvent<LivePaintWorkerResponse>) => {
        const resp = e.data;
        // race 保护：只接受最新 request 的响应
        if (resp.requestId !== pendingRequestId) {
            // 旧请求的 perf 记录也要清，避免内存泄漏（高频 dragmove 下可能积一堆）
            if (sentAt) sentAt.delete(resp.requestId);
            return;
        }
        isBuilding.value = false;
        if (sentAt) {
            const ctx = sentAt.get(resp.requestId);
            sentAt.delete(resp.requestId);
            if (ctx) {
                const elapsed = performance.now() - ctx.startedAt;
                const status = resp.type === 'ok'
                    ? `${resp.graph.gaps.length} gaps${resp.graph.degraded ? ' (degraded)' : ''}`
                    : `err: ${resp.message}`;
                // eslint-disable-next-line no-console
                console.log(
                    `[livepaint] graph built in ${elapsed.toFixed(1)}ms`
                    + ` (${ctx.elementCount} elements → ${status})`,
                );
            }
        }
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
            // P2-30：让任何在途请求失效——递增 pendingRequestId 使其响应在 onmessage(73)
            // 的 ID 比对被丢弃，永不把已清空的 graph 回填成陈旧拓扑（worker 升 async 后
            // text+fontkit 路径慢请求可在禁用后才返回）。
            pendingRequestId = ++nextRequestId;
            return;
        }
        const requestId = ++nextRequestId;
        pendingRequestId = requestId;
        isBuilding.value = true;
        const elements: import('@/types/protocol').Element[] = JSON.parse(JSON.stringify(opts.elements.value));
        if (sentAt) sentAt.set(requestId, { startedAt: performance.now(), elementCount: elements.length });
        const req: LivePaintWorkerRequest = {
            type: 'build',
            requestId,
            // 深 clone：避免 Vue Proxy / reactive ref 不能 structured-clone
            elements,
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
