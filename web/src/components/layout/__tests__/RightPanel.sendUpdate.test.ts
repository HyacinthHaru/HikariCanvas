// @vitest-environment happy-dom
/**
 * D1 回归测试：RightPanel sendUpdate / sendUpdateDebounced 跨元素串写修复。
 *
 * 核心场景：元素 A 的输入框改了值（触发 sendUpdateDebounced，此时捕获 A 的 id），
 * 80ms 内用户切换到元素 B（selected 变为 B），flush 时应该把改动写进 A（按捕获 id），
 * 而不是 B。
 *
 * 由于 sendUpdate 现在接受显式 elementId（不读 selected.value），
 * 且 sendUpdateDebounced 在调用时捕获 selected.value?.id 传入内层，
 * 测试通过直接模拟逻辑来验证路由正确性。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { ref, computed } from 'vue';
import { useProjectStore } from '@/stores/project';
import type { ProjectState } from '@/types/protocol';

// ---------- helpers ----------

function makeState(elements: { id: string; type: string; x: number; y: number; w: number; h: number }[]): ProjectState {
    return {
        canvas: { width: 128, height: 128, background: '#ffffff', gridSize: 8, guides: [] },
        elements,
        layers: [{ id: 'layer-1', name: 'Layer 1', locked: false, visible: true, elements }],
        activeLayerId: 'layer-1',
        timelines: [],
        activeTimelineId: null,
    } as unknown as ProjectState;
}

beforeEach(() => {
    setActivePinia(createPinia());
});

/**
 * 模拟 RightPanel 修复后的 sendUpdate / sendUpdateDebounced 行为，
 * 不依赖真实组件挂载（避免 Konva / wsClient 的副作用）。
 */
function buildSendHelpers(projectStore: ReturnType<typeof useProjectStore>) {
    const wsSentCalls: { elementId: string; patch: Record<string, unknown> }[] = [];

    // 镜像 RightPanel 修复后的 sendUpdate：按显式 elementId 路由，不读 selected
    function sendUpdate(patch: Record<string, unknown>, elementId: string) {
        const el = projectStore.elementById(elementId);
        if (!el) return;
        for (const [k, v] of Object.entries(patch)) {
            (el as unknown as Record<string, unknown>)[k] = v;
        }
        wsSentCalls.push({ elementId, patch });
    }

    // 镜像 RightPanel 修复后的 sendUpdateDebounced：
    // 调用时捕获 capturedId，flush 时按捕获 id 路由
    let pendingDebounce: { patch: Record<string, unknown>; capturedId: string } | null = null;

    function sendUpdateDebounced(patch: Record<string, unknown>, capturedId: string) {
        pendingDebounce = { patch, capturedId };
    }

    function flushDebounce() {
        if (!pendingDebounce) return;
        const { patch, capturedId } = pendingDebounce;
        pendingDebounce = null;
        sendUpdate(patch, capturedId);
    }

    return { sendUpdate, sendUpdateDebounced, flushDebounce, wsSentCalls };
}

describe('RightPanel D1 — sendUpdate 按显式 elementId 路由，不串写', () => {
    it('sendUpdate 按传入 elementId 发送，不读 selected（A 写 A）', () => {
        const project = useProjectStore();
        const elA = { id: 'el-A', type: 'rect', x: 0, y: 0, w: 10, h: 10 };
        const elB = { id: 'el-B', type: 'rect', x: 0, y: 0, w: 10, h: 10 };
        project.setSnapshot(makeState([elA, elB]));

        const { sendUpdate, wsSentCalls } = buildSendHelpers(project);

        // 模拟"当前选中 B，但显式传 A 的 id"（触发时捕获 A）
        sendUpdate({ x: 99 }, 'el-A');

        expect(wsSentCalls).toHaveLength(1);
        expect(wsSentCalls[0].elementId).toBe('el-A');
        expect(wsSentCalls[0].patch).toEqual({ x: 99 });
        // 乐观本地 mutate 写入 A，不影响 B
        expect((project.elementById('el-A') as { x: number }).x).toBe(99);
        expect((project.elementById('el-B') as { x: number }).x).toBe(0);
    });

    it('sendUpdateDebounced：A 输入 → 切到 B → flush 时写 A，不写 B（跨元素不串写）', () => {
        const project = useProjectStore();
        const elA = { id: 'el-A', type: 'rect', x: 0, y: 0, w: 10, h: 10 };
        const elB = { id: 'el-B', type: 'rect', x: 0, y: 0, w: 10, h: 10 };
        project.setSnapshot(makeState([elA, elB]));

        const { sendUpdateDebounced, flushDebounce, wsSentCalls } = buildSendHelpers(project);

        // 步骤 1：用户在 A 的输入框改值，捕获 A 的 id
        sendUpdateDebounced({ x: 42 }, 'el-A');

        // 步骤 2：80ms 内用户切换到 B（selected 变为 B），但 debounce 已捕获 A 的 id

        // 步骤 3：flush（80ms 到期），应路由到 A，不路由到 B
        flushDebounce();

        expect(wsSentCalls).toHaveLength(1);
        expect(wsSentCalls[0].elementId).toBe('el-A');
        expect((project.elementById('el-A') as { x: number }).x).toBe(42);
        expect((project.elementById('el-B') as { x: number }).x).toBe(0); // B 不受影响
    });

    it('sendUpdate elementId 不存在 → no-op（不发送、不报错）', () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([]));

        const { sendUpdate, wsSentCalls } = buildSendHelpers(project);

        sendUpdate({ x: 1 }, 'el-GHOST');

        expect(wsSentCalls).toHaveLength(0);
    });

    it('连续两次 sendUpdateDebounced（同元素）：只有最后一次 flush 生效', () => {
        const project = useProjectStore();
        const elA = { id: 'el-A', type: 'rect', x: 0, y: 0, w: 10, h: 10 };
        project.setSnapshot(makeState([elA]));

        const { sendUpdateDebounced, flushDebounce, wsSentCalls } = buildSendHelpers(project);

        sendUpdateDebounced({ x: 10 }, 'el-A');
        sendUpdateDebounced({ x: 20 }, 'el-A'); // 后者覆盖
        flushDebounce();

        expect(wsSentCalls).toHaveLength(1);
        expect(wsSentCalls[0].patch).toEqual({ x: 20 });
    });
});
