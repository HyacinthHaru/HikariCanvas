// @vitest-environment happy-dom
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { effectScope, ref } from 'vue';
import type { Element } from '@/types/protocol';
import type { LivePaintWorkerRequest, LivePaintWorkerResponse } from '../livePaintWorker';

interface FakeWorkerInstance {
    posted: LivePaintWorkerRequest[];
    terminated: boolean;
    onmessage: ((e: MessageEvent<LivePaintWorkerResponse>) => void) | null;
    onerror: ((e: ErrorEvent) => void) | null;
    onmessageerror: ((e: MessageEvent) => void) | null;
    postMessage(req: LivePaintWorkerRequest): void;
    terminate(): void;
}

const { instances } = vi.hoisted(() => ({
    instances: [] as FakeWorkerInstance[],
}));

vi.mock('../livePaintWorker?worker', () => ({
    default: class MockLivePaintWorker implements FakeWorkerInstance {
        posted: LivePaintWorkerRequest[] = [];
        terminated = false;
        onmessage: ((e: MessageEvent<LivePaintWorkerResponse>) => void) | null = null;
        onerror: ((e: ErrorEvent) => void) | null = null;
        onmessageerror: ((e: MessageEvent) => void) | null = null;

        constructor() {
            instances.push(this);
        }

        postMessage(req: LivePaintWorkerRequest): void {
            this.posted.push(req);
        }

        terminate(): void {
            this.terminated = true;
        }
    },
}));

import { useLivePaint } from '../useLivePaint';

describe('useLivePaint — worker 错误与退化态收敛', () => {
    beforeEach(() => {
        instances.length = 0;
        vi.useFakeTimers();
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        vi.spyOn(console, 'log').mockImplementation(() => {});
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.restoreAllMocks();
    });

    function setupComposable(initialEnabled = true) {
        const elements = ref<Element[]>([]);
        const canvasWidth = ref(256);
        const canvasHeight = ref(128);
        const enabled = ref(initialEnabled);
        const scope = effectScope();
        const api = scope.run(() => useLivePaint({
            elements,
            canvasWidth,
            canvasHeight,
            enabled,
        }))!;
        return { api, elements, canvasWidth, canvasHeight, enabled, scope, worker: instances[0] };
    }

    it('worker 返回 type="err" 时清除 building 并置 degraded 哨兵（而非 null）', () => {
        const { api, worker, scope } = setupComposable(true);
        vi.advanceTimersByTime(100);
        expect(api.isBuilding.value).toBe(true);
        expect(worker.posted).toHaveLength(1);

        const reqId = worker.posted[0].requestId;
        worker.onmessage?.(new MessageEvent<LivePaintWorkerResponse>('message', {
            data: { type: 'err', requestId: reqId, message: 'topology boom' },
        }));

        expect(api.isBuilding.value).toBe(false);
        expect(api.graph.value).toEqual({
            gaps: [],
            canvasWidth: 0,
            canvasHeight: 0,
            degraded: true,
        });
        expect(api.findGapAt(10, 10)).toBeNull();
        scope.stop();
    });

    it('worker.onerror 触发时清除 building、推进 pendingRequestId 丢弃旧响应并置 degraded', () => {
        const { api, worker, scope } = setupComposable(true);
        vi.advanceTimersByTime(100);
        expect(api.isBuilding.value).toBe(true);
        const crashedReqId = worker.posted[0].requestId;

        worker.onerror?.(new ErrorEvent('error', { message: 'chunk load error' }));

        expect(api.isBuilding.value).toBe(false);
        expect(api.graph.value).toEqual({
            gaps: [],
            canvasWidth: 0,
            canvasHeight: 0,
            degraded: true,
        });

        // 旧请求迟到的 ok 响应不得覆盖 degraded 状态
        worker.onmessage?.(new MessageEvent<LivePaintWorkerResponse>('message', {
            data: {
                type: 'ok',
                requestId: crashedReqId,
                graph: { gaps: [{ outer: [[0, 0], [10, 0], [10, 10]], holes: [] }], canvasWidth: 256, canvasHeight: 128 },
            },
        }));
        expect(api.graph.value?.degraded).toBe(true);
        expect(api.graph.value?.gaps).toEqual([]);
        scope.stop();
    });

    it('worker.onmessageerror 触发时同样收敛到 degraded 状态', () => {
        const { api, worker, scope } = setupComposable(true);
        vi.advanceTimersByTime(100);
        expect(api.isBuilding.value).toBe(true);

        worker.onmessageerror?.(new MessageEvent('messageerror'));

        expect(api.isBuilding.value).toBe(false);
        expect(api.graph.value).toEqual({
            gaps: [],
            canvasWidth: 0,
            canvasHeight: 0,
            degraded: true,
        });
        scope.stop();
    });

    it('工具未激活（enabled=false）时发生 worker 错误保持 graph=null', () => {
        const { api, worker, scope } = setupComposable(false);
        vi.advanceTimersByTime(100);

        worker.onerror?.(new ErrorEvent('error', { message: 'background error' }));

        expect(api.isBuilding.value).toBe(false);
        expect(api.graph.value).toBeNull();
        scope.stop();
    });
});
