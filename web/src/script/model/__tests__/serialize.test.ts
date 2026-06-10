/**
 * 0.7.0-P4-A：blockLayout 编解码 + autoLayout 单测（纯逻辑，vitest node）。
 */
import { describe, expect, it } from 'vitest';
import {
    parseBlockLayout,
    stringifyBlockLayout,
    autoLayout,
    type BlockLayout,
} from '../serialize';

describe('serialize.parseBlockLayout', () => {
    it('正常解析', () => {
        const json = '{"stacks":{"sr-1":{"x":10,"y":20},"sr-2":{"x":-5,"y":640}}}';
        const layout = parseBlockLayout(json);
        expect(layout.stacks['sr-1']).toEqual({ x: 10, y: 20 });
        expect(layout.stacks['sr-2']).toEqual({ x: -5, y: 640 });
    });

    it('null / undefined / 空串 → 空 stacks', () => {
        expect(parseBlockLayout(null).stacks).toEqual({});
        expect(parseBlockLayout(undefined).stacks).toEqual({});
        expect(parseBlockLayout('').stacks).toEqual({});
    });

    it('坏 JSON → 空 stacks', () => {
        expect(parseBlockLayout('{not json').stacks).toEqual({});
        expect(parseBlockLayout('[]').stacks).toEqual({}); // 顶层非对象
        expect(parseBlockLayout('42').stacks).toEqual({});
    });

    it('缺 stacks / stacks 非对象 → 空 stacks', () => {
        expect(parseBlockLayout('{}').stacks).toEqual({});
        expect(parseBlockLayout('{"stacks":null}').stacks).toEqual({});
        expect(parseBlockLayout('{"stacks":[]}').stacks).toEqual({});
        expect(parseBlockLayout('{"stacks":"x"}').stacks).toEqual({});
    });

    it('非有限坐标 / 缺字段 / 类型错 → 丢弃该条，保留合法条', () => {
        const json = JSON.stringify({
            stacks: {
                good: { x: 1, y: 2 },
                nanX: { x: 'NaN', y: 5 }, // x 非数字字符串
                infY: { x: 0, y: Number.POSITIVE_INFINITY }, // JSON.stringify 会写成 null
                missingY: { x: 3 },
                notObj: 7,
            },
        });
        const layout = parseBlockLayout(json);
        expect(layout.stacks['good']).toEqual({ x: 1, y: 2 });
        expect(layout.stacks['nanX']).toBeUndefined();
        expect(layout.stacks['infY']).toBeUndefined();
        expect(layout.stacks['missingY']).toBeUndefined();
        expect(layout.stacks['notObj']).toBeUndefined();
    });

    it('显式 NaN / Infinity 数值被丢弃', () => {
        // 直接构造对象（不经 JSON.stringify，保留真实 NaN/Infinity）再字符串化亦会变 null，
        // 这里用手写 JSON 字符串确认解析侧守卫：JSON 不支持 NaN，故用 1e999 触发 Infinity。
        const layout = parseBlockLayout('{"stacks":{"a":{"x":1e999,"y":0}}}');
        expect(layout.stacks['a']).toBeUndefined();
    });
});

describe('serialize.stringifyBlockLayout + 往返', () => {
    it('stringify 输出可被 parse 还原', () => {
        const layout: BlockLayout = { stacks: { 'sr-1': { x: 40, y: 360 } } };
        const json = stringifyBlockLayout(layout);
        expect(parseBlockLayout(json)).toEqual(layout);
    });

    it('空 layout 往返', () => {
        const layout: BlockLayout = { stacks: {} };
        expect(parseBlockLayout(stringifyBlockLayout(layout))).toEqual(layout);
    });
});

describe('serialize.autoLayout', () => {
    it('给缺坐标的规则纵向排布，保留已有坐标', () => {
        const existing: BlockLayout = { stacks: { b: { x: 200, y: 50 } } };
        const layout = autoLayout(['a', 'b', 'c'], existing);
        // a 是第 0 个缺坐标 → (40, 40)；b 已有保留；c 是第 1 个缺坐标 → (40, 360)
        expect(layout.stacks['a']).toEqual({ x: 40, y: 40 });
        expect(layout.stacks['b']).toEqual({ x: 200, y: 50 });
        expect(layout.stacks['c']).toEqual({ x: 40, y: 360 });
    });

    it('全部缺坐标：依次 40, 360, 680 ...', () => {
        const layout = autoLayout(['a', 'b', 'c'], { stacks: {} });
        expect(layout.stacks['a']).toEqual({ x: 40, y: 40 });
        expect(layout.stacks['b']).toEqual({ x: 40, y: 360 });
        expect(layout.stacks['c']).toEqual({ x: 40, y: 680 });
    });

    it('不修改入参 existing', () => {
        const existing: BlockLayout = { stacks: { a: { x: 1, y: 1 } } };
        autoLayout(['a', 'b'], existing);
        expect(existing.stacks['b']).toBeUndefined(); // 入参未被改
    });

    it('空 ruleIds → 原样返回已有坐标拷贝', () => {
        const layout = autoLayout([], { stacks: { a: { x: 5, y: 6 } } });
        expect(layout.stacks).toEqual({ a: { x: 5, y: 6 } });
    });
});
