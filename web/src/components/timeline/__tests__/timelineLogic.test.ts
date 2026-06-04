/**
 * 0.6 P2（B3）：timelineLogic 纯函数单测。
 *
 * 无 Vue / DOM / pinia（task 要求：纯函数测试，pinia 不进测试）。node 环境直接跑。
 *
 * 覆盖：
 * - groupKeyframesByElement：分组 + 排序 + 孤儿轨 type=null + 空输入
 * - formatKeyframeValue：number / string / Fill 对象 / 小数去尾
 * - defaultValueFor：opacity 缺省 → 1、其余缺省 → 0、显式值原样
 * - validateCreateForm：合法 + durationMs / fps / loopMode 各错误分支
 * - isValidKeyframeTime：边界
 * - shortElementId
 */
import { describe, expect, it } from 'vitest';
import {
    CREATE_FORM_DEFAULTS,
    defaultValueFor,
    formatKeyframeValue,
    groupKeyframesByElement,
    isValidKeyframeTime,
    shortElementId,
    validateCreateForm,
    type CreateFormInput,
} from '../timelineLogic';
import type { Element, Keyframe, Timeline } from '@/types/protocol';

function mkKf(property: string, timeMs: number, value: number = 0): Keyframe {
    return {
        id: `kf-${property}-${timeMs}`,
        property,
        timeMs,
        value,
        easing: { type: 'linear' },
    };
}

function mkTimeline(tracks: Record<string, Keyframe[]>): Timeline {
    return {
        id: 'tl-deadbeef',
        name: 'demo',
        durationMs: 5000,
        fps: 20,
        loopMode: 'loop',
        trigger: { type: 'manual', params: {} },
        tracks,
    };
}

function mkRect(over: Partial<Element> = {}): Element {
    return {
        id: 'e-1a2b3c4d',
        type: 'rect',
        x: 10,
        y: 20,
        w: 30,
        h: 40,
        rotation: 0,
        locked: false,
        visible: true,
        ...over,
    } as Element;
}

describe('groupKeyframesByElement', () => {
    it('returns [] for null timeline', () => {
        expect(groupKeyframesByElement(null, () => null)).toEqual([]);
    });

    it('returns [] for timeline without tracks', () => {
        const tl = mkTimeline({});
        expect(groupKeyframesByElement(tl, () => 'rect')).toEqual([]);
    });

    it('groups by elementId ascending and sorts keyframes by (property, timeMs)', () => {
        const tl = mkTimeline({
            'e-bbb': [mkKf('x', 500), mkKf('opacity', 0), mkKf('x', 0)],
            'e-aaa': [mkKf('y', 100)],
        });
        const groups = groupKeyframesByElement(tl, (id) => (id === 'e-aaa' ? 'text' : 'circle'));
        // elementId 升序：e-aaa 在前
        expect(groups.map((g) => g.elementId)).toEqual(['e-aaa', 'e-bbb']);
        expect(groups[0].elementType).toBe('text');
        // e-bbb：opacity 先（字典序 o < x），x 内部按 timeMs 升序
        const bbb = groups[1];
        expect(bbb.keyframes.map((k) => `${k.property}@${k.timeMs}`)).toEqual([
            'opacity@0', 'x@0', 'x@500',
        ]);
    });

    it('marks orphan track type as null when element resolver returns null', () => {
        const tl = mkTimeline({ 'e-gone': [mkKf('x', 0)] });
        const groups = groupKeyframesByElement(tl, () => null);
        expect(groups[0].elementType).toBeNull();
    });

    it('does not mutate the source track array', () => {
        const original = [mkKf('x', 500), mkKf('x', 0)];
        const tl = mkTimeline({ 'e-a': original });
        groupKeyframesByElement(tl, () => 'rect');
        // 原数组顺序未被排序改动
        expect(original.map((k) => k.timeMs)).toEqual([500, 0]);
    });
});

describe('formatKeyframeValue', () => {
    it('formats integers as-is', () => {
        expect(formatKeyframeValue(42)).toBe('42');
        expect(formatKeyframeValue(0)).toBe('0');
    });

    it('trims trailing zeros on decimals', () => {
        expect(formatKeyframeValue(1.5)).toBe('1.5');
        expect(formatKeyframeValue(0.10000001)).toBe('0.1');
    });

    it('returns strings (incl. variable templates) as-is', () => {
        expect(formatKeyframeValue('hello')).toBe('hello');
        expect(formatKeyframeValue('${var:user/x}')).toBe('${var:user/x}');
    });

    it('returns object type for Fill values', () => {
        expect(formatKeyframeValue({ type: 'solid', color: '#fff' })).toBe('solid');
        expect(formatKeyframeValue({ type: 'linear', stops: [], angle: 0 })).toBe('linear');
    });

    it('falls back to "fill" for typeless objects', () => {
        expect(formatKeyframeValue({ foo: 1 })).toBe('fill');
    });
});

describe('defaultValueFor', () => {
    it('reads explicit numeric props', () => {
        const el = mkRect({ x: 10, y: 20, w: 30, h: 40, rotation: 15 });
        expect(defaultValueFor(el, 'x')).toBe(10);
        expect(defaultValueFor(el, 'rotation')).toBe(15);
        expect(defaultValueFor(el, 'h')).toBe(40);
    });

    it('defaults opacity to 1 when unset', () => {
        expect(defaultValueFor(mkRect(), 'opacity')).toBe(1);
    });

    it('reads explicit opacity', () => {
        expect(defaultValueFor(mkRect({ opacity: 0.3 }), 'opacity')).toBe(0.3);
    });

    it('defaults to 0 for null element (non-opacity)', () => {
        expect(defaultValueFor(null, 'x')).toBe(0);
        expect(defaultValueFor(null, 'rotation')).toBe(0);
    });

    it('defaults opacity to 1 for null element', () => {
        expect(defaultValueFor(null, 'opacity')).toBe(1);
    });
});

describe('validateCreateForm', () => {
    const base: CreateFormInput = { ...CREATE_FORM_DEFAULTS };

    it('accepts the defaults', () => {
        expect(validateCreateForm(base)).toEqual({ ok: true });
    });

    it('rejects non-positive durationMs', () => {
        const r = validateCreateForm({ ...base, durationMs: 0 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('durationMs');
    });

    it('rejects non-integer durationMs', () => {
        const r = validateCreateForm({ ...base, durationMs: 1.5 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('durationMs');
    });

    it('rejects absurdly long durationMs', () => {
        const r = validateCreateForm({ ...base, durationMs: 9_999_999 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.reason).toBe('errDurationTooLong');
    });

    it('rejects non-positive fps', () => {
        const r = validateCreateForm({ ...base, fps: 0 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('fps');
    });

    it('rejects fps above 240', () => {
        const r = validateCreateForm({ ...base, fps: 999 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('fps');
    });

    it('rejects unknown loopMode', () => {
        // 强制非法值绕过类型检查模拟 UI 漂移
        const r = validateCreateForm({ ...base, loopMode: 'bogus' as never });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('loopMode');
    });
});

describe('isValidKeyframeTime', () => {
    it('accepts boundaries 0 and durationMs', () => {
        expect(isValidKeyframeTime(0, 5000)).toBe(true);
        expect(isValidKeyframeTime(5000, 5000)).toBe(true);
        expect(isValidKeyframeTime(2500, 5000)).toBe(true);
    });

    it('rejects negative / overflow / non-integer', () => {
        expect(isValidKeyframeTime(-1, 5000)).toBe(false);
        expect(isValidKeyframeTime(5001, 5000)).toBe(false);
        expect(isValidKeyframeTime(1.5, 5000)).toBe(false);
    });
});

describe('shortElementId', () => {
    it('strips the e- prefix', () => {
        expect(shortElementId('e-1a2b3c4d')).toBe('1a2b3c4d');
    });
    it('returns id without prefix unchanged', () => {
        expect(shortElementId('abcdef')).toBe('abcdef');
    });
});
