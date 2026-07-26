// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { complexityGuard, SvgImportError } from '../svgSecurity';

function makeShapes(n: number): Element[] {
    const doc = new DOMParser().parseFromString('<svg xmlns="http://www.w3.org/2000/svg"/>', 'image/svg+xml');
    const arr: Element[] = [];
    for (let i = 0; i < n; i++) {
        const e = doc.createElementNS('http://www.w3.org/2000/svg', 'rect');
        arr.push(e);
    }
    return arr;
}

describe('complexityGuard', () => {
    it('throws when shape count exceeds maxShapes', () => {
        expect(() => complexityGuard(makeShapes(501))).toThrow(SvgImportError);
    });
    it('passes for a normal small shape set', () => {
        expect(() => complexityGuard(makeShapes(10))).not.toThrow();
    });
    it('throws when total vertices exceed maxTotalVertices', () => {
        // 一个 path 带超长 d（命令字母数累加超 50000）
        const doc = new DOMParser().parseFromString('<svg xmlns="http://www.w3.org/2000/svg"/>', 'image/svg+xml');
        const p = doc.createElementNS('http://www.w3.org/2000/svg', 'path');
        p.setAttribute('d', 'L0 0 '.repeat(50001));   // 5万+ 个 L 命令
        expect(() => complexityGuard([p])).toThrow(SvgImportError);
    });
    it('respects custom limits', () => {
        expect(() => complexityGuard(makeShapes(5), { maxShapes: 3 })).toThrow(SvgImportError);
    });

    it('一个命令字母后跟成千上万组参数也算数（隐式重复不再绕过上限）', () => {
        // SVG 允许 "M0 0 L1 1 2 2 3 3 …" 这样一个 L 带无数组坐标。按命令字母数估
        // 顶点时这只算 2 个顶点，五万的上限形同虚设，解析阶段却要展开出等量的命令对象。
        const doc = new DOMParser().parseFromString('<svg xmlns="http://www.w3.org/2000/svg"/>', 'image/svg+xml');
        const p = doc.createElementNS('http://www.w3.org/2000/svg', 'path');
        p.setAttribute('d', 'M0 0 L' + '1 1 '.repeat(60000));
        expect(() => complexityGuard([p])).toThrow(SvgImportError);
    });

    it('常规图标不误伤', () => {
        const doc = new DOMParser().parseFromString('<svg xmlns="http://www.w3.org/2000/svg"/>', 'image/svg+xml');
        const p = doc.createElementNS('http://www.w3.org/2000/svg', 'path');
        // 200 条三次贝塞尔（每条 6 个数）= 600 顶点估值，远在上限内
        p.setAttribute('d', 'M0 0 ' + 'C1 1 2 2 3 3 '.repeat(200) + 'Z');
        expect(() => complexityGuard([p])).not.toThrow();
    });

    it('科学记数与负号紧挨的写法不会数错到误报', () => {
        const doc = new DOMParser().parseFromString('<svg xmlns="http://www.w3.org/2000/svg"/>', 'image/svg+xml');
        const p = doc.createElementNS('http://www.w3.org/2000/svg', 'path');
        p.setAttribute('d', 'M0 0L1e-3-2.5.5-.5Z');   // 6 个数字 → 3 个顶点
        expect(() => complexityGuard([p], { maxTotalVertices: 3 })).not.toThrow();
        expect(() => complexityGuard([p], { maxTotalVertices: 2 })).toThrow(SvgImportError);
    });
});
