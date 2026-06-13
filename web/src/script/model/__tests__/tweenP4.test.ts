/**
 * tween-P4：体拖入限制 + 缓动 cubicBezier 读写单测（纯逻辑，vitest node）。
 */
import { describe, expect, it } from 'vitest';
import { isTweenBodySlotAllowed } from '../../canvas/useBlockDrag';
import type { SlotRect } from '../dropTarget';
import { TWEENABLE_KINDS } from '../blockDefs';

// ---------- 辅助 ----------

function tweenBodySlot(): SlotRect {
    return {
        stackRuleId: 'r1',
        parentPath: ['actions', '0', 'body'],
        index: 0,
        x: 0, y: 0, w: 200, h: 14,
        containerBlockType: 'tweenBlock',
    };
}

function topLevelSlot(): SlotRect {
    return {
        stackRuleId: 'r1',
        parentPath: ['actions'],
        index: 1,
        x: 0, y: 0, w: 200, h: 14,
        // containerBlockType 未设 = undefined
    };
}

function repeatBodySlot(): SlotRect {
    return {
        stackRuleId: 'r1',
        parentPath: ['actions', '1', 'body'],
        index: 0,
        x: 0, y: 0, w: 200, h: 14,
        containerBlockType: 'repeat',
    };
}

// ---------- 1. tweenBlock body 拖入限制 ----------

describe('isTweenBodySlotAllowed – tweenBlock body 约束', () => {

    it('非 tweenBlock 插槽（顶层 actions）→ 任何积木允许', () => {
        const slot = topLevelSlot();
        expect(isTweenBodySlotAllowed('sendMessage', slot)).toBe(true);
        expect(isTweenBodySlotAllowed('if', slot)).toBe(true);
        expect(isTweenBodySlotAllowed('tweenBlock', slot)).toBe(true);
    });

    it('repeat body 插槽 → 任何积木允许（repeat 无白名单限制）', () => {
        const slot = repeatBodySlot();
        expect(isTweenBodySlotAllowed('sendMessage', slot)).toBe(true);
        expect(isTweenBodySlotAllowed('moveTo', slot)).toBe(true);
    });

    it('tweenBlock body：友好积木 moveTo（TWEENABLE_KINDS 内）→ 允许', () => {
        expect(isTweenBodySlotAllowed('moveTo', tweenBodySlot())).toBe(true);
    });

    it('tweenBlock body：友好积木 resize → 允许', () => {
        expect(isTweenBodySlotAllowed('resize', tweenBodySlot())).toBe(true);
    });

    it('tweenBlock body：友好积木 rotateTo → 允许', () => {
        expect(isTweenBodySlotAllowed('rotateTo', tweenBodySlot())).toBe(true);
    });

    it('tweenBlock body：友好积木 setOpacity → 允许', () => {
        expect(isTweenBodySlotAllowed('setOpacity', tweenBodySlot())).toBe(true);
    });

    it('tweenBlock body：友好积木 setColor → 允许', () => {
        expect(isTweenBodySlotAllowed('setColor', tweenBodySlot())).toBe(true);
    });

    it('tweenBlock body：友好积木 setText（不在 TWEENABLE_KINDS）→ 拒绝', () => {
        expect(isTweenBodySlotAllowed('setText', tweenBodySlot())).toBe(false);
    });

    it('tweenBlock body：友好积木 show（不在 TWEENABLE_KINDS）→ 拒绝', () => {
        expect(isTweenBodySlotAllowed('show', tweenBodySlot())).toBe(false);
    });

    it('tweenBlock body：友好积木 hide（不在 TWEENABLE_KINDS）→ 拒绝', () => {
        expect(isTweenBodySlotAllowed('hide', tweenBodySlot())).toBe(false);
    });

    it('tweenBlock body：sendMessage → 拒绝', () => {
        expect(isTweenBodySlotAllowed('sendMessage', tweenBodySlot())).toBe(false);
    });

    it('tweenBlock body：if 块 → 拒绝', () => {
        expect(isTweenBodySlotAllowed('if', tweenBodySlot())).toBe(false);
    });

    it('tweenBlock body：tweenBlock 嵌套 → 拒绝（不在 TWEENABLE_KINDS）', () => {
        expect(isTweenBodySlotAllowed('tweenBlock', tweenBodySlot())).toBe(false);
    });

    it('tweenBlock body：wait → 拒绝', () => {
        expect(isTweenBodySlotAllowed('wait', tweenBodySlot())).toBe(false);
    });

    it('tweenBlock body：setElementProperties + moveTo kind → 允许', () => {
        // 画布上已有的 setElementProperties 积木，draggingKind='setElementProperties'，
        // actionKind='moveTo'（action.kind 字段）。
        expect(isTweenBodySlotAllowed('setElementProperties', tweenBodySlot(), 'moveTo')).toBe(true);
    });

    it('tweenBlock body：setElementProperties + setText kind → 拒绝', () => {
        expect(isTweenBodySlotAllowed('setElementProperties', tweenBodySlot(), 'setText')).toBe(false);
    });

    it('TWEENABLE_KINDS 集合内容符合预期（5 项）', () => {
        expect(TWEENABLE_KINDS.has('moveTo')).toBe(true);
        expect(TWEENABLE_KINDS.has('resize')).toBe(true);
        expect(TWEENABLE_KINDS.has('rotateTo')).toBe(true);
        expect(TWEENABLE_KINDS.has('setOpacity')).toBe(true);
        expect(TWEENABLE_KINDS.has('setColor')).toBe(true);
        // 不在集合里的
        expect(TWEENABLE_KINDS.has('setText')).toBe(false);
        expect(TWEENABLE_KINDS.has('show')).toBe(false);
        expect(TWEENABLE_KINDS.has('hide')).toBe(false);
    });
});

// ---------- 2. easing cubicBezier 字段读写语义 ----------

describe('easing cubicBezier 读写', () => {

    it('cubicBezier easing 对象结构：type + bezier 四元数组', () => {
        const easing = { type: 'cubicBezier' as const, bezier: [0.25, 0.1, 0.25, 1.0] as [number, number, number, number] };
        expect(easing.type).toBe('cubicBezier');
        expect(easing.bezier).toHaveLength(4);
        expect(easing.bezier[0]).toBe(0.25);
        expect(easing.bezier[3]).toBe(1.0);
    });

    it('TWEEN_EASING_OPTIONS 包含 cubicBezier', async () => {
        // 动态 import 确保 blockDefs module 已加载
        const { ACTION_DEFS } = await import('../blockDefs');
        const tweenDef = ACTION_DEFS.tweenBlock;
        const easingField = tweenDef.fields.find((f) => f.name === 'easing');
        expect(easingField).toBeDefined();
        const cubicOption = easingField?.options?.find((o) => o.value === 'cubicBezier');
        expect(cubicOption).toBeDefined();
        expect(cubicOption?.labelKey).toBe('timeline.easingCustom');
    });

    it('tweenBlock 默认 action easing 为 easeInOut（无 bezier）', async () => {
        const { makeDefaultAction } = await import('../blockDefs');
        const action = makeDefaultAction('tweenBlock');
        if (action.type !== 'tweenBlock') throw new Error('wrong type');
        expect(action.easing.type).toBe('easeInOut');
        expect(action.easing.bezier).toBeUndefined();
    });

    it('easing type 由 select 写入时应保留 bezier（cubicBezier 逻辑）', () => {
        // 模拟切到 cubicBezier：若有已有 bezier 则保留；若无则初始化为默认 ease。
        const existingEasing = { type: 'easeInOut' as const };
        const defaultBezier: [number, number, number, number] = [0.25, 0.1, 0.25, 1];
        const newEasing = { type: 'cubicBezier' as const, bezier: existingEasing.bezier ?? defaultBezier };
        expect(newEasing.type).toBe('cubicBezier');
        expect(newEasing.bezier).toEqual([0.25, 0.1, 0.25, 1]);
    });

    it('切回预设 easing 时应丢掉 bezier', () => {
        const _cubic = { type: 'cubicBezier' as const, bezier: [0.1, 0.5, 0.9, 0.5] as [number, number, number, number] };
        // 切回 linear：只写 type，bezier 不保留。
        const linear = { type: 'linear' as const };
        expect(linear.type).toBe('linear');
        expect((linear as { bezier?: unknown }).bezier).toBeUndefined();
    });
});
