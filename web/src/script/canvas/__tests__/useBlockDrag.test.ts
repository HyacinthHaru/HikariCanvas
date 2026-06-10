// @vitest-environment happy-dom
/**
 * 0.7.0-P4-D2：buildSlots 纯逻辑 + collectSlots（happy-dom）单测。
 *
 * <p>{@link buildSlots} 是"已测块矩形 → SlotRect[]"的纯推导（无 DOM）：验证序列 before 插槽
 * 枚举 / 尾插槽 / if 子序列槽 / 空容器占位槽 / <b>排除被拖块自身及其子树</b>。{@link collectSlots}
 * 用 happy-dom 构造带 data-block-path / data-slot-path / data-rule-id 的 DOM + stub
 * getBoundingClientRect，验证 DOM → buildSlots 接线正确。</p>
 */
import { describe, expect, it } from 'vitest';
import { buildSlots, collectSlots, type MeasuredBlock } from '../useBlockDrag';
import type { SlotRect } from '@/script/model/dropTarget';

/** 造一个真实块测量项（矩形随便给，纯逻辑只看 path/kind/ruleId 推导落点）。 */
function block(path: string, ruleId = 'r1', top = 0): MeasuredBlock {
    return { path, ruleId, kind: 'block', rect: { left: 10, top, width: 200, height: 30 } };
}
function emptySlot(path: string, ruleId = 'r1'): MeasuredBlock {
    return { path, ruleId, kind: 'emptySlot', rect: { left: 12, top: 50, width: 180, height: 20 } };
}
function hat(ruleId = 'r1'): MeasuredBlock {
    return { path: 'trigger', ruleId, kind: 'hat', rect: { left: 8, top: -40, width: 220, height: 40 } };
}

/** 取插槽的 (parentPath 串, index) 二元组集合，便于断言。 */
function slotKeys(slots: SlotRect[]): string[] {
    return slots.map((s) => `${s.parentPath.join('/')}#${s.index}`).sort();
}

describe('buildSlots — 序列插槽枚举', () => {
    it('单序列 3 块 → 3 before 槽 + 1 尾槽（index 0..3）', () => {
        const measured = [
            hat(),
            block('actions/0', 'r1', 0),
            block('actions/1', 'r1', 40),
            block('actions/2', 'r1', 80),
        ];
        const slots = buildSlots(measured, null);
        expect(slotKeys(slots)).toEqual([
            'actions#0', 'actions#1', 'actions#2', 'actions#3',
        ]);
    });

    it('尾槽 index = 末块 index + 1', () => {
        const slots = buildSlots([block('actions/0'), block('actions/1')], null);
        const tail = slots.find((s) => s.index === 2);
        expect(tail).toBeTruthy();
        expect(tail!.parentPath).toEqual(['actions']);
    });

    it('乱序测量项也按 index 升序生成（before + tail 正确）', () => {
        // 故意倒序喂入
        const slots = buildSlots([block('actions/2'), block('actions/0'), block('actions/1')], null);
        expect(slotKeys(slots)).toEqual(['actions#0', 'actions#1', 'actions#2', 'actions#3']);
    });
});

describe('buildSlots — if 子序列槽', () => {
    it('if 的 then 子序列生成独立 parentPath 槽', () => {
        const measured = [
            block('actions/0'),                 // 顶层 log
            block('actions/1'),                 // 顶层 if
            block('actions/1/then/0'),          // if.then 内一块
        ];
        const slots = buildSlots(measured, null);
        expect(slotKeys(slots)).toEqual([
            'actions#0', 'actions#1', 'actions#2',           // 顶层 before×2 + tail
            'actions/1/then#0', 'actions/1/then#1',          // then before + tail
        ]);
    });

    it('空 then/else 槽（emptySlot）→ 单 index=0 槽', () => {
        const measured = [
            block('actions/0'),
            emptySlot('actions/0/then'),
            emptySlot('actions/0/else'),
        ];
        const slots = buildSlots(measured, null);
        expect(slotKeys(slots)).toEqual([
            'actions#0', 'actions#1',               // 顶层 before + tail
            'actions/0/else#0', 'actions/0/then#0', // 两个空子槽各一
        ]);
    });

    it('空顶层 actions 占位（data-slot-path="actions"）→ actions#0 单槽', () => {
        const slots = buildSlots([hat(), emptySlot('actions')], null);
        expect(slotKeys(slots)).toEqual(['actions#0']);
    });
});

describe('buildSlots — 排除被拖块自身及其子树（关键正确性）', () => {
    it('拖 if 块（actions/1）→ 剔除其 then/else 内所有槽', () => {
        const measured = [
            block('actions/0'),
            block('actions/1'),                 // 被拖的 if
            block('actions/1/then/0'),
            block('actions/1/else/0'),
        ];
        const dragging = ['actions', '1'];
        const slots = buildSlots(measured, dragging);
        // then/else 子序列槽（parentPath 以 actions/1 为前缀）全剔除；顶层槽保留
        expect(slotKeys(slots)).toEqual(['actions#0', 'actions#1', 'actions#2']);
        // 确认没有任何 parentPath 以 actions/1 为前缀
        const leaked = slots.filter((s) => s.parentPath[0] === 'actions' && s.parentPath[1] === '1');
        expect(leaked).toHaveLength(0);
    });

    it('拖深层 if（actions/0/then/1）→ 仅剔其更深子树，兄弟与外层保留', () => {
        const measured = [
            block('actions/0'),                       // 外层 if
            block('actions/0/then/0'),                // then 内块 A
            block('actions/0/then/1'),                // then 内 if（被拖）
            block('actions/0/then/1/then/0'),         // 被拖 if 的子块（应剔除）
        ];
        const dragging = ['actions', '0', 'then', '1'];
        const slots = buildSlots(measured, dragging);
        const keys = slotKeys(slots);
        // 被拖块的子序列 actions/0/then/1/then 应消失
        expect(keys).not.toContain('actions/0/then/1/then#0');
        expect(keys).not.toContain('actions/0/then/1/then#1');
        // 外层 + 同级 then 序列槽保留（actions/0/then 的 before/tail）
        expect(keys).toContain('actions/0/then#0');
        expect(keys).toContain('actions/0/then#1');
        expect(keys).toContain('actions/0/then#2');
    });

    it('palette 源（draggingPath=null）→ 不剔除任何槽', () => {
        const measured = [
            block('actions/0'),
            block('actions/0/then/0'),
        ];
        const all = buildSlots(measured, null);
        // 不传 dragging 时全留
        expect(slotKeys(all)).toEqual(['actions#0', 'actions#1', 'actions/0/then#0', 'actions/0/then#1']);
    });
});

describe('buildSlots — 健壮性', () => {
    it('空测量 → 空槽', () => {
        expect(buildSlots([], null)).toEqual([]);
    });
    it('只有 hat（无动作 + 无空占位）→ 无槽', () => {
        expect(buildSlots([hat()], null)).toEqual([]);
    });
    it('坏 path（非数字末段）被跳过不崩', () => {
        const bad: MeasuredBlock = { path: 'actions/x', ruleId: 'r1', kind: 'block', rect: { left: 0, top: 0, width: 10, height: 10 } };
        expect(buildSlots([bad], null)).toEqual([]);
    });
    it('多堆：各堆 ruleId 独立分组', () => {
        const slots = buildSlots([
            block('actions/0', 'rA'),
            block('actions/0', 'rB'),
        ], null);
        const byRule = slots.map((s) => `${s.stackRuleId}:${s.index}`).sort();
        // 每堆顶层 1 before + 1 tail
        expect(byRule).toEqual(['rA:0', 'rA:1', 'rB:0', 'rB:1']);
    });
});

// ---------- collectSlots（happy-dom）----------

/** 在 happy-dom 里造一个带 data-* 的元素 + stub getBoundingClientRect。 */
function makeEl(
    doc: Document,
    attrs: Record<string, string>,
    rect: { left: number; top: number; width: number; height: number },
): HTMLElement {
    const el = doc.createElement('div');
    for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
    el.getBoundingClientRect = () => ({
        left: rect.left, top: rect.top, width: rect.width, height: rect.height,
        right: rect.left + rect.width, bottom: rect.top + rect.height, x: rect.left, y: rect.top,
        toJSON: () => ({}),
    }) as DOMRect;
    return el;
}

describe('collectSlots（happy-dom）— DOM 测量接线', () => {
    it('从 data-block-path / data-rule-id 推导出顶层序列槽', () => {
        const doc = document;
        const canvas = doc.createElement('div');
        const stack = makeEl(doc, { 'data-rule-id': 'r1' }, { left: 0, top: 0, width: 280, height: 200 });
        stack.appendChild(makeEl(doc, { 'data-block-path': 'trigger' }, { left: 0, top: 0, width: 280, height: 40 }));
        stack.appendChild(makeEl(doc, { 'data-block-path': 'actions/0' }, { left: 4, top: 45, width: 270, height: 30 }));
        stack.appendChild(makeEl(doc, { 'data-block-path': 'actions/1' }, { left: 4, top: 80, width: 270, height: 30 }));
        canvas.appendChild(stack);

        const slots = collectSlots(canvas, null);
        expect(slotKeys(slots)).toEqual(['actions#0', 'actions#1', 'actions#2']);
        // ruleId 经 closest([data-rule-id]) 解析
        expect(slots.every((s) => s.stackRuleId === 'r1')).toBe(true);
        // before-slot 矩形 left 来自块 rect
        const first = slots.find((s) => s.index === 0)!;
        expect(first.x).toBe(4);
    });

    it('data-slot-path（空容器）→ index=0 槽', () => {
        const canvas = document.createElement('div');
        const stack = makeEl(document, { 'data-rule-id': 'r9' }, { left: 0, top: 0, width: 280, height: 100 });
        stack.appendChild(makeEl(document, { 'data-block-path': 'trigger' }, { left: 0, top: 0, width: 280, height: 40 }));
        stack.appendChild(makeEl(document, { 'data-slot-path': 'actions' }, { left: 6, top: 45, width: 260, height: 20 }));
        canvas.appendChild(stack);

        const slots = collectSlots(canvas, null);
        expect(slotKeys(slots)).toEqual(['actions#0']);
        expect(slots[0].stackRuleId).toBe('r9');
    });

    it('拖动 path 传入 → collectSlots 剔除自身子树', () => {
        const canvas = document.createElement('div');
        const stack = makeEl(document, { 'data-rule-id': 'r1' }, { left: 0, top: 0, width: 280, height: 300 });
        stack.appendChild(makeEl(document, { 'data-block-path': 'actions/0' }, { left: 4, top: 0, width: 270, height: 30 }));
        stack.appendChild(makeEl(document, { 'data-block-path': 'actions/1' }, { left: 4, top: 40, width: 270, height: 30 }));
        stack.appendChild(makeEl(document, { 'data-block-path': 'actions/1/then/0' }, { left: 14, top: 75, width: 250, height: 30 }));
        canvas.appendChild(stack);

        const slots = collectSlots(canvas, ['actions', '1']);
        const keys = slotKeys(slots);
        expect(keys).not.toContain('actions/1/then#0');
        expect(keys).not.toContain('actions/1/then#1');
        expect(keys).toContain('actions#0');
    });
});
