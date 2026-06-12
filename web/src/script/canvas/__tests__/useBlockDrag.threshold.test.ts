// @vitest-environment happy-dom
/**
 * 0.7.1 触控板敏感度修复：拖动阈值（DRAG_THRESHOLD）。
 *
 * <p>背景：Mac 触控板 pointerdown 必带微移，旧实现 pointerdown 当帧即进拖动态 → 点积木里的
 * 字段（变量按钮 / 文本框）想编辑时一不小心就把整块拖走。修复：pointerdown 时只记 pending +
 * 挂临时监听，<b>指针移动超 {@link DRAG_THRESHOLD} 像素才真正启动拖动</b>；阈值内松手 = 点击
 * （不进拖动态、不 capture、不 selectRule、不 setDragging）。</p>
 *
 * <p>关键不变性（P5 修复，绝不能破坏）：{@code tryCapture} 仍先于 {@code selectRule}——阈值模式下
 * pending 阶段不 select，超阈值时先 capture pending.target（pointerdown 时的原 DOM）再 selectRule，
 * capture 目标不被 v-for 重建打断。本测验证"小移动 = 点击不拖" + "大移动 = 拖动落树" + "select 仅在
 * 超阈值后发生"。</p>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { effectScope } from 'vue';

import { useBlockDrag, DRAG_THRESHOLD } from '../useBlockDrag';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useScriptStore } from '@/stores/scripts';
import type { ScriptRule, ScriptAction } from '@/types/protocol';

// mock wsClient（scriptEdit store 内部用）
const sendScriptUpdate = vi.fn(() => Promise.resolve());
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptCreate: vi.fn(() => Promise.resolve()),
        sendScriptUpdate,
        sendScriptDelete: vi.fn(() => Promise.resolve()),
        sendScriptEnable: vi.fn(() => Promise.resolve()),
    }),
}));

function makeRule(id: string, actions: ScriptAction[]): ScriptRule {
    return {
        id, wallId: 'w-1', enabled: true, name: id, trigger: { type: 'wallReady' },
        actions, blockLayout: '{}',
    };
}

/** 造带 stub 矩形 + stub capture 的元素，并记 capture 调用次数。 */
function el(
    attrs: Record<string, string>,
    rect: { left: number; top: number; width: number; height: number },
): HTMLElement & { __captured: number } {
    const e = document.createElement('div') as HTMLElement & { __captured: number };
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    e.getBoundingClientRect = () => ({
        left: rect.left, top: rect.top, width: rect.width, height: rect.height,
        right: rect.left + rect.width, bottom: rect.top + rect.height, x: rect.left, y: rect.top, toJSON: () => ({}),
    }) as DOMRect;
    e.__captured = 0;
    e.setPointerCapture = () => { e.__captured += 1; };
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
    sendScriptUpdate.mockClear();
    canvasEl = document.createElement('div');
    document.body.appendChild(canvasEl);
});

afterEach(() => {
    scope?.stop();
    canvasEl.remove();
});

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

/** 标准两块堆 DOM（actions/0 在 top 100..130，actions/1 在 140..170）。返回首块 DOM。 */
function buildTwoBlockStack(): HTMLElement & { __captured: number } {
    const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 80 });
    const a = el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 });
    stack.appendChild(a);
    stack.appendChild(el({ 'data-block-path': 'actions/1' }, { left: 10, top: 140, width: 260, height: 30 }));
    canvasEl.appendChild(stack);
    return a;
}

describe('DRAG_THRESHOLD 常量', () => {
    it('导出一个正的小像素阈值', () => {
        expect(typeof DRAG_THRESHOLD).toBe('number');
        expect(DRAG_THRESHOLD).toBeGreaterThan(0);
        expect(DRAG_THRESHOLD).toBeLessThanOrEqual(12);
    });
});

describe('画布源块拖动 — 阈值内松手 = 点击（不拖）', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', [
            { type: 'log', message: 'A' },
            { type: 'log', message: 'B' },
        ]));
        const edit = useScriptEditStore();
        // 关键：当前未选中本规则——验证"阈值内点击不会触发 selectRule"。
        const a = buildTwoBlockStack();
        const drag = makeDrag();
        return { edit, a, drag };
    }

    it('pointerdown 当帧不进拖动态：无 ghost / 无 dragging / 未 capture / 未 select', () => {
        const { edit, a, drag } = setup();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        // pending 阶段：一切按兵不动
        expect(edit.dragging).toBe(false);
        expect(drag.ghost.value).toBeNull();
        expect(drag.deleteZoneActive.value).toBe(false);
        expect(a.__captured).toBe(0);
        expect(edit.selectedRuleId).toBeNull();
    });

    it('微移 < 阈值 后松手 → 仍不进拖动态、不改树、不 select（= 可点字段）', () => {
        const { edit, a, drag } = setup();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        // 触控板微移：2px（< 5）
        window.dispatchEvent(pointerEvt('pointermove', 121, 112));
        expect(edit.dragging).toBe(false);
        expect(drag.ghost.value).toBeNull();
        // 松手
        window.dispatchEvent(pointerEvt('pointerup', 121, 112));
        // 树不变、未选规则、未 capture
        expect(edit.workingCopy).toBeNull(); // 没选规则 → 无 working copy（未误触 selectRule）
        expect(edit.selectedRuleId).toBeNull();
        expect(a.__captured).toBe(0);
        expect(edit.dragging).toBe(false);
    });

    it('阈值内松手后临时监听已清：后续 window pointermove 不再误启动拖动', () => {
        const { edit, a, drag } = setup();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        window.dispatchEvent(pointerEvt('pointerup', 120, 110)); // 原地松手
        // 之后随便来个大位移的 move（模拟用户移动指针）——不应触发任何拖动
        window.dispatchEvent(pointerEvt('pointermove', 400, 400));
        expect(edit.dragging).toBe(false);
        expect(drag.ghost.value).toBeNull();
        expect(a.__captured).toBe(0);
    });
});

describe('画布源块拖动 — 超阈值 = 真拖动（落树 + 不变性）', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', [
            { type: 'log', message: 'A' },
            { type: 'log', message: 'B' },
        ]));
        const edit = useScriptEditStore();
        const a = buildTwoBlockStack();
        const drag = makeDrag();
        return { edit, a, drag };
    }

    it('移动 > 阈值 → 进入拖动态（ghost + dragging + capture + select），并落树重排', () => {
        const { edit, a, drag } = setup();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        // 跨过阈值（向下移 20px）
        window.dispatchEvent(pointerEvt('pointermove', 120, 130));
        // 真拖动态
        expect(edit.dragging).toBe(true);
        expect(drag.ghost.value).not.toBeNull();
        expect(drag.deleteZoneActive.value).toBe(true);
        expect(a.__captured).toBe(1);          // capture 发生
        expect(edit.selectedRuleId).toBe('sr-1'); // select 在超阈值后发生

        // 落到顶层尾插槽 index=2（块 actions/1 下沿 y≈140+30-14=156，中心≈163）
        window.dispatchEvent(pointerEvt('pointerup', 140, 163));
        const acts = edit.workingCopy!.actions;
        expect(acts.map((x) => (x.type === 'log' ? x.message : x.type))).toEqual(['B', 'A']);
        expect(edit.dragging).toBe(false);
    });

    it('不变性：capture 先于 selectRule（capture 计数在 select 发生前已 +1）', () => {
        const { edit, a, drag } = setup();
        // 用 selectRule spy 抓取调用瞬间的 capture 计数
        let capturedAtSelect = -1;
        const orig = edit.selectRule.bind(edit);
        vi.spyOn(edit, 'selectRule').mockImplementation((id: string) => {
            capturedAtSelect = a.__captured;
            return orig(id);
        });
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        window.dispatchEvent(pointerEvt('pointermove', 120, 130)); // 超阈值
        // selectRule 触发时，capture 必已发生（>=1）
        expect(capturedAtSelect).toBe(1);
        window.dispatchEvent(pointerEvt('pointerup', 140, 900)); // 清理
    });

    it('单帧大跳（pointerdown 后第一个 move 就远超阈值）→ 直接拖动', () => {
        const { edit, a, drag } = setup();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        window.dispatchEvent(pointerEvt('pointermove', 300, 300)); // 一步到位
        expect(edit.dragging).toBe(true);
        expect(drag.ghost.value).not.toBeNull();
        window.dispatchEvent(pointerEvt('pointerup', 140, 900));
        expect(edit.dragging).toBe(false);
    });
});

describe('画布源块拖动 — pending 期被中断（pointercancel / blur）干净清理', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', [{ type: 'log', message: 'A' }]));
        const a = (() => {
            const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 40 });
            const blk = el({ 'data-block-path': 'actions/0' }, { left: 10, top: 100, width: 260, height: 30 });
            stack.appendChild(blk);
            canvasEl.appendChild(stack);
            return blk;
        })();
        const edit = useScriptEditStore();
        const drag = makeDrag();
        return { edit, a, drag };
    }

    it('pending 期 window pointercancel → 清 pending，后续 move 不启动拖动', () => {
        const { edit, a, drag } = setup();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        window.dispatchEvent(pointerEvt('pointercancel', 120, 110));
        // 后续大位移 move 不应启动
        window.dispatchEvent(pointerEvt('pointermove', 400, 400));
        expect(edit.dragging).toBe(false);
        expect(drag.ghost.value).toBeNull();
        expect(a.__captured).toBe(0);
    });

    it('pending 期 window blur → 清 pending，后续 move 不启动拖动', () => {
        const { edit, a, drag } = setup();
        drag.startBlockDrag('sr-1', 'actions/0', pointerEvt('pointerdown', 120, 110, a));
        window.dispatchEvent(new Event('blur'));
        window.dispatchEvent(pointerEvt('pointermove', 400, 400));
        expect(edit.dragging).toBe(false);
        expect(drag.ghost.value).toBeNull();
        expect(a.__captured).toBe(0);
    });
});

describe('移堆（帽子）拖动 — 同样有阈值', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', [{ type: 'log', message: 'A' }]));
        const stack = el({ 'data-rule-id': 'sr-1' }, { left: 0, top: 100, width: 280, height: 40 });
        const hat = el({ 'data-block-path': 'trigger' }, { left: 0, top: 60, width: 280, height: 36 });
        stack.style.left = '0px';
        stack.style.top = '100px';
        // 让 hat.closest('[data-rule-id]') 命中 stack
        stack.appendChild(hat);
        canvasEl.appendChild(stack);
        const edit = useScriptEditStore();
        const drag = makeDrag();
        return { edit, hat, drag };
    }

    it('阈值内松手 = 点击：不进移堆态、不 select、不 capture', () => {
        const { edit, hat, drag } = setup();
        drag.startStackDrag('sr-1', pointerEvt('pointerdown', 100, 80, hat));
        expect(drag.stackDragPos.value).toBeNull();
        window.dispatchEvent(pointerEvt('pointermove', 101, 82)); // 微移
        expect(drag.stackDragPos.value).toBeNull();
        expect(edit.dragging).toBe(false);
        window.dispatchEvent(pointerEvt('pointerup', 101, 82));
        expect(edit.selectedRuleId).toBeNull();
        expect(hat.__captured).toBe(0);
    });

    it('超阈值 → 进入移堆态（stackDragPos 跟随 + capture + select + dragging）', () => {
        const { edit, hat, drag } = setup();
        drag.startStackDrag('sr-1', pointerEvt('pointerdown', 100, 80, hat));
        window.dispatchEvent(pointerEvt('pointermove', 100, 110)); // 下移 30 > 阈值
        expect(drag.stackDragPos.value).not.toBeNull();
        expect(edit.dragging).toBe(true);
        expect(hat.__captured).toBe(1);
        expect(edit.selectedRuleId).toBe('sr-1');
        window.dispatchEvent(pointerEvt('pointerup', 100, 110));
        expect(edit.dragging).toBe(false);
    });
});
