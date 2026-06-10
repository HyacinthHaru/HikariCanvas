// @vitest-environment happy-dom
/**
 * 0.7.0-P4-D2：拖拽落树集成测（happy-dom）—— palette insert / 画布 move 写入 working copy。
 *
 * <p>用真实 scriptEdit store（mock wsClient）+ 手搭画布 DOM（带 data-block-path / data-slot-path
 * / data-rule-id + stub getBoundingClientRect + stub setPointerCapture）+ effectScope 跑
 * useBlockDrag。驱动 start* → 模拟松手（pointerup 在目标插槽中心）→ 断言 {@code edit.workingCopy.actions}
 * 是正确新树（同序列重排 / 跨堆—本测单堆 / 进 if 槽 / palette 顶层插入）。</p>
 *
 * <p>验证 D2 与 blockTree.moveNode 契约对齐："toIndex 按移动前渲染树测量" = collectSlots 在
 * pointerdown 时刻测，故下标补偿由 moveNode 自理，集成层无需再算。</p>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { effectScope } from 'vue';

import { useBlockDrag } from '../useBlockDrag';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useScriptStore } from '@/stores/scripts';
import type { ScriptRule, ScriptAction } from '@/types/protocol';

// mock wsClient（scriptEdit store 内部用）
const sendScriptCreate = vi.fn(() => Promise.resolve());
const sendScriptUpdate = vi.fn(() => Promise.resolve());
const sendScriptDelete = vi.fn(() => Promise.resolve());
const sendScriptEnable = vi.fn(() => Promise.resolve());
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ sendScriptCreate, sendScriptUpdate, sendScriptDelete, sendScriptEnable }),
}));

function makeRule(actions: ScriptAction[]): ScriptRule {
    return {
        id: 'sr-1', wallId: 'w-1', enabled: true, name: 'r', trigger: { type: 'wallReady' },
        actions, blockLayout: '{}',
    };
}

/** 造带 stub 矩形 + stub capture 的元素。 */
function el(
    attrs: Record<string, string>,
    rect: { left: number; top: number; width: number; height: number },
): HTMLElement {
    const e = document.createElement('div');
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    e.getBoundingClientRect = () => ({
        left: rect.left, top: rect.top, width: rect.width, height: rect.height,
        right: rect.left + rect.width, bottom: rect.top + rect.height, x: rect.left, y: rect.top, toJSON: () => ({}),
    }) as DOMRect;
    e.setPointerCapture = () => { /* stub */ };
    e.releasePointerCapture = () => { /* stub */ };
    return e;
}

function pointerEvt(type: string, clientX: number, clientY: number, target?: HTMLElement): PointerEvent {
    const ev = new Event(type, { bubbles: true }) as unknown as PointerEvent;
    Object.defineProperty(ev, 'clientX', { value: clientX });
    Object.defineProperty(ev, 'clientY', { value: clientY });
    Object.defineProperty(ev, 'button', { value: 0 });
    Object.defineProperty(ev, 'pointerId', { value: 1 });
    if (target) {
        Object.defineProperty(ev, 'currentTarget', { value: target });
        Object.defineProperty(ev, 'target', { value: target });
    }
    return ev;
}

let scope: ReturnType<typeof effectScope>;
let canvasEl: HTMLElement;

beforeEach(() => {
    setActivePinia(createPinia());
    sendScriptCreate.mockClear();
    sendScriptUpdate.mockClear();
    canvasEl = document.createElement('div');
    document.body.appendChild(canvasEl);
});

afterEach(() => {
    scope?.stop();
    canvasEl.remove();
});

/** 在 effectScope 内造 drag 实例（canvasRef 指 canvasEl；screenToWorld 用恒等——测里坐标即 world）。 */
function makeDrag() {
    const ref = { value: canvasEl };
    let drag!: ReturnType<typeof useBlockDrag>;
    scope = effectScope();
    scope.run(() => {
        drag = useBlockDrag({
            canvasRef: ref as unknown as { value: HTMLElement | null },
            screenToWorld: (x, y) => ({ x, y }),
        });
    });
    return drag;
}

describe('palette 源 → insertAt 顶层', () => {
    it('拖 log 落到顶层尾插槽 → actions 追加 log', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([{ type: 'wait', ms: 100 }]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');

        // DOM：一个块 actions/0 在 top 100..130，尾插槽在其下沿（top+h-bandH ≈ 116..130）。
        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 40 });
        stack.appendChild(el({ 'data-block-path': 'trigger' }, { left: 0, top: 60, width: 280, height: 36 }));
        stack.appendChild(el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 }));
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        // palette 源无 currentTarget DOM 时用 canvasRef 兜底（startPaletteDrag 取 e.currentTarget ?? canvas）
        const down = pointerEvt('pointerdown', 50, 50);
        Object.defineProperty(down, 'currentTarget', { value: canvasEl });
        drag.startPaletteDrag('log', down);

        // 松手落在尾插槽中心：块 actions/0 底部 index=1 槽 y ≈ 100+30-14=116，中心 ≈ 123；x 在 [10,270]
        window.dispatchEvent(pointerEvt('pointerup', 140, 124));

        const acts = edit.workingCopy!.actions;
        expect(acts).toHaveLength(2);
        expect(acts[0]).toEqual({ type: 'wait', ms: 100 });
        expect(acts[1]).toEqual({ type: 'log', message: '' });
    });

    it('拖 if 落到顶层 before-0 插槽 → actions 头部插入 if', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([{ type: 'wait', ms: 100 }]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');

        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 40 });
        stack.appendChild(el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 }));
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        const down = pointerEvt('pointerdown', 50, 50);
        Object.defineProperty(down, 'currentTarget', { value: canvasEl });
        drag.startPaletteDrag('if', down);
        // before-0 槽在块顶（top 100, h=14, 中心 107）
        window.dispatchEvent(pointerEvt('pointerup', 140, 107));

        const acts = edit.workingCopy!.actions;
        expect(acts).toHaveLength(2);
        expect(acts[0]).toEqual({ type: 'if', condition: '', then: [], else: [] });
        expect(acts[1]).toEqual({ type: 'wait', ms: 100 });
    });

    it('落空白处（无命中）→ palette 源不创建', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([{ type: 'wait', ms: 100 }]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 40 });
        stack.appendChild(el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 }));
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        const down = pointerEvt('pointerdown', 50, 50);
        Object.defineProperty(down, 'currentTarget', { value: canvasEl });
        drag.startPaletteDrag('log', down);
        // 远离所有插槽（y=900 超阈值）
        window.dispatchEvent(pointerEvt('pointerup', 140, 900));

        expect(edit.workingCopy!.actions).toHaveLength(1);
    });
});

describe('画布源 → moveNode', () => {
    it('同序列重排：把 actions/0 移到尾部', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([
            { type: 'log', message: 'A' },
            { type: 'log', message: 'B' },
        ]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');

        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 80 });
        const a = el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 });
        stack.appendChild(a);
        stack.appendChild(el({ 'data-block-path': 'actions/1' }, { left: 10, top: 140, width: 260, height: 30 }));
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        // 拖 actions/0（块 A）
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        // 落到顶层尾插槽 index=2（块 actions/1 下沿 y≈140+30-14=156，中心≈163）
        window.dispatchEvent(pointerEvt('pointerup', 140, 163));

        const acts = edit.workingCopy!.actions;
        expect(acts.map((x) => (x.type === 'log' ? x.message : x.type))).toEqual(['B', 'A']);
    });

    it('落回原位（同 index）→ 不变（无 setActions 实质改动）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([
            { type: 'log', message: 'A' },
            { type: 'log', message: 'B' },
        ]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 80 });
        const a = el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 });
        stack.appendChild(a);
        stack.appendChild(el({ 'data-block-path': 'actions/1' }, { left: 10, top: 140, width: 260, height: 30 }));
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        // 落到 before-0 槽（块 A 顶，中心≈107）= 自身原位
        window.dispatchEvent(pointerEvt('pointerup', 140, 107));

        const acts = edit.workingCopy!.actions;
        expect(acts.map((x) => (x.type === 'log' ? x.message : x.type))).toEqual(['A', 'B']);
    });

    it('进 if 槽：把 actions/0 移进 actions/1 的空 then 槽', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([
            { type: 'log', message: 'A' },
            { type: 'if', condition: '', then: [], else: [] },
        ]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');

        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 200 });
        const a = el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 });
        stack.appendChild(a);
        stack.appendChild(el({ 'data-block-path': 'actions/1' }, { left: 10, top: 140, width: 260, height: 90 }));
        // if 的空 then 槽 data-slot-path（在 if 块内更深处）
        stack.appendChild(el({ 'data-slot-path': 'actions/1/then' }, { left: 30, top: 170, width: 230, height: 22 }));
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        // 落到 then 空槽中心（top 170, h 22, 中心 181；x 在 [30,260]）
        window.dispatchEvent(pointerEvt('pointerup', 140, 181));

        const acts = edit.workingCopy!.actions;
        // actions/0 被移走 → 顶层只剩 if；if.then 里现在有 A
        expect(acts).toHaveLength(1);
        expect(acts[0].type).toBe('if');
        if (acts[0].type === 'if') {
            expect(acts[0].then).toHaveLength(1);
            expect(acts[0].then[0]).toEqual({ type: 'log', message: 'A' });
        }
    });

    it('拖块落空白处（无命中）→ 还原不变', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([
            { type: 'log', message: 'A' },
            { type: 'log', message: 'B' },
        ]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 80 });
        const a = el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 });
        stack.appendChild(a);
        stack.appendChild(el({ 'data-block-path': 'actions/1' }, { left: 10, top: 140, width: 260, height: 30 }));
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        window.dispatchEvent(pointerEvt('pointerup', 140, 900));

        expect(edit.workingCopy!.actions.map((x) => (x.type === 'log' ? x.message : x.type))).toEqual(['A', 'B']);
    });
});

describe('lock 守卫', () => {
    it('locked 时 startPaletteDrag / startBlockDrag no-op（不改 working copy）', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule([{ type: 'log', message: 'A' }]));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        // 锁墙（lockedAt 非 null 即锁定）
        const { useProjectStore } = await import('@/stores/project');
        useProjectStore().lockedAt = Date.now();

        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 40 });
        const a = el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 });
        stack.appendChild(a);
        canvasEl.appendChild(stack);

        const drag = makeDrag();
        const down = pointerEvt('pointerdown', 50, 50);
        Object.defineProperty(down, 'currentTarget', { value: canvasEl });
        drag.startPaletteDrag('log', down);
        window.dispatchEvent(pointerEvt('pointerup', 140, 124));
        // 拖块同样 no-op
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        window.dispatchEvent(pointerEvt('pointerup', 140, 163));

        expect(edit.workingCopy!.actions).toHaveLength(1);
        expect(edit.workingCopy!.actions[0]).toEqual({ type: 'log', message: 'A' });
    });
});
