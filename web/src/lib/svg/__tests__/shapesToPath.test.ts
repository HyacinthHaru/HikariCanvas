// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { shapeToPathD } from '../shapesToPath';

function el(tag: string, attrs: Record<string, string>): Element {
    const doc = new DOMParser().parseFromString('<svg xmlns="http://www.w3.org/2000/svg"/>', 'image/svg+xml');
    const e = doc.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    return e;
}

describe('shapeToPathD', () => {
    it('rect → closed M/L path', () => {
        expect(shapeToPathD(el('rect', { x: '0', y: '0', width: '10', height: '5' })))
            .toBe('M0 0 L10 0 L10 5 L0 5 Z');
    });
    it('line → M..L', () => {
        expect(shapeToPathD(el('line', { x1: '1', y1: '2', x2: '3', y2: '4' }))).toBe('M1 2 L3 4');
    });
    it('polygon → closed', () => {
        expect(shapeToPathD(el('polygon', { points: '0,0 10,0 5,8' }))).toBe('M0 0 L10 0 L5 8 Z');
    });
    it('polyline → open', () => {
        expect(shapeToPathD(el('polyline', { points: '0,0 10,0 5,8' }))).toBe('M0 0 L10 0 L5 8');
    });
    it('path → its own d', () => {
        expect(shapeToPathD(el('path', { d: 'M1 1 L2 2' }))).toBe('M1 1 L2 2');
    });
    it('circle → 4 cubic arcs (starts at right pole, closed)', () => {
        const d = shapeToPathD(el('circle', { cx: '10', cy: '10', r: '5' }));
        expect(d?.startsWith('M15 10')).toBe(true);   // 右极点起笔
        expect(d?.endsWith('Z')).toBe(true);
        expect(d?.includes('C')).toBe(true);          // 三次贝塞尔
    });
    it('image → null (not a path)', () => {
        expect(shapeToPathD(el('image', { href: 'data:...' }))).toBeNull();
    });
});
