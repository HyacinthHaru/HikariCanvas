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
    EASING_TYPES,
    defaultValueFor,
    defaultValueForExtended,
    formatKeyframeValue,
    groupKeyframesByElement,
    isValidKeyframeTime,
    keyframeablePropertiesFor,
    shortElementId,
    validateCreateForm,
    type CreateFormInput,
} from '../timelineLogic';
import type { Element, Fill, Keyframe, Timeline } from '@/types/protocol';

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

// ---------- P3 扩展：keyframeablePropertiesFor ----------

/** 给定 type 造一个最简元素（只需 type + base 字段够分类）。 */
function mkEl(type: string, over: Partial<Element> = {}): Element {
    return {
        id: `e-${type}`,
        type,
        x: 0, y: 0, w: 10, h: 10, rotation: 0,
        locked: false, visible: true,
        ...over,
    } as Element;
}

const NUMERIC_SIX = ['x', 'y', 'w', 'h', 'rotation', 'opacity'];

describe('keyframeablePropertiesFor', () => {
    it('returns the six numeric props for null element', () => {
        expect(keyframeablePropertiesFor(null)).toEqual(NUMERIC_SIX);
    });

    it('text adds color + text on top of numerics', () => {
        expect(keyframeablePropertiesFor(mkEl('text')))
            .toEqual([...NUMERIC_SIX, 'color', 'text']);
    });

    it('rect adds fill (有填充时)', () => {
        expect(keyframeablePropertiesFor(mkEl('rect', { fill: { type: 'solid', color: '#FFFFFF' } } as Partial<Element>)))
            .toEqual([...NUMERIC_SIX, 'fill']);
    });

    it.each(['rect', 'icon', 'path', 'circle', 'shape', 'brush'])(
        '%s is fillable (adds fill, no color/text)',
        (type) => {
            const props = keyframeablePropertiesFor(
                mkEl(type, { fill: { type: 'solid', color: '#FFFFFF' } } as Partial<Element>));
            expect(props).toEqual([...NUMERIC_SIX, 'fill']);
            expect(props).not.toContain('color');
            expect(props).not.toContain('text');
        },
    );

    it('P3 审查 #9：元素当前 fill 为 null/缺失时不放行 fill 轨（空心框不能加填充关键帧）', () => {
        expect(keyframeablePropertiesFor(mkEl('rect'))).toEqual(NUMERIC_SIX);
        expect(keyframeablePropertiesFor(mkEl('circle', { fill: null } as unknown as Partial<Element>)))
            .toEqual(NUMERIC_SIX);
    });

    it('image gets only the numeric six (no fill/color/text)', () => {
        expect(keyframeablePropertiesFor(mkEl('image', { source: 'abc' } as Partial<Element>)))
            .toEqual(NUMERIC_SIX);
    });

    it('covers all 8 element types deterministically', () => {
        // text(8) / rect 带填充(7) / image(6)
        expect(keyframeablePropertiesFor(mkEl('text')).length).toBe(8);
        expect(keyframeablePropertiesFor(
            mkEl('rect', { fill: { type: 'solid', color: '#FFFFFF' } } as Partial<Element>)).length).toBe(7);
        expect(keyframeablePropertiesFor(mkEl('image', { source: 'a' } as Partial<Element>)).length).toBe(6);
    });
});

// ---------- P3 扩展：defaultValueForExtended ----------

describe('defaultValueForExtended', () => {
    it('numeric props delegate to defaultValueFor (number)', () => {
        const el = mkEl('rect', { x: 12, opacity: 0.4 } as Partial<Element>);
        expect(defaultValueForExtended(el, 'x')).toBe(12);
        expect(defaultValueForExtended(el, 'opacity')).toBe(0.4);
        expect(defaultValueForExtended(mkEl('rect'), 'opacity')).toBe(1);
    });

    it('color reads element.color, defaulting to #000000', () => {
        expect(defaultValueForExtended(mkEl('text', { color: '#FF8800' } as Partial<Element>), 'color'))
            .toBe('#FF8800');
        expect(defaultValueForExtended(mkEl('text'), 'color')).toBe('#000000');
        // 非 text 元素无 color → 默认黑
        expect(defaultValueForExtended(mkEl('rect'), 'color')).toBe('#000000');
    });

    it('text reads element.text, defaulting to empty string', () => {
        expect(defaultValueForExtended(mkEl('text', { text: 'hi' } as Partial<Element>), 'text'))
            .toBe('hi');
        expect(defaultValueForExtended(mkEl('text'), 'text')).toBe('');
        // 空字符串元素 text 原样返回（不退默认）
        expect(defaultValueForExtended(mkEl('text', { text: '' } as Partial<Element>), 'text')).toBe('');
    });

    it('fill reads element.fill object, defaulting to white solid', () => {
        const el = mkEl('rect', { fill: { type: 'solid', color: '#123456' } } as Partial<Element>);
        expect(defaultValueForExtended(el, 'fill')).toEqual({ type: 'solid', color: '#123456' });
        expect(defaultValueForExtended(mkEl('rect'), 'fill')).toEqual({ type: 'solid', color: '#FFFFFF' });
    });

    it('fill wraps a legacy string fill into a SolidFill', () => {
        const el = mkEl('rect', { fill: '#abcdef' as unknown as Fill } as Partial<Element>);
        expect(defaultValueForExtended(el, 'fill')).toEqual({ type: 'solid', color: '#abcdef' });
    });

    it('fill preserves a linear gradient object', () => {
        const grad: Fill = {
            type: 'linear', angle: 45,
            stops: [{ position: 0, color: '#000' }, { position: 1, color: '#fff' }],
        };
        const el = mkEl('rect', { fill: grad } as Partial<Element>);
        expect(defaultValueForExtended(el, 'fill')).toBe(grad);
    });
});

// ---------- P3：EASING_TYPES 常量完整性 ----------

describe('EASING_TYPES', () => {
    it('lists the four MVP presets in order (no cubicBezier in the dropdown)', () => {
        expect(EASING_TYPES).toEqual(['linear', 'easeIn', 'easeOut', 'easeInOut']);
    });

    it('starts with linear (default)', () => {
        expect(EASING_TYPES[0]).toBe('linear');
    });
});
