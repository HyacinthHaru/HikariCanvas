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
});
