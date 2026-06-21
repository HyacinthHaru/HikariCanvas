// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { mapFill, mapStroke, mapFillRule, mapOpacity } from '../fillMap';

function makeEl(tag: string, attrs: Record<string, string>): Element {
    const doc = new DOMParser().parseFromString('<svg xmlns="http://www.w3.org/2000/svg"/>', 'image/svg+xml');
    const e = doc.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    return e;
}

describe('fillMap', () => {
    it('maps fill hex / none / fill-rule / stroke', () => {
        expect(mapFill(makeEl('path', { fill: '#ff0000' }))).toEqual({ type: 'solid', color: '#ff0000' });
        expect(mapFill(makeEl('path', { fill: 'none' }))).toBeUndefined();
        expect(mapFillRule(makeEl('path', { 'fill-rule': 'evenodd' }))).toBe('evenodd');
        expect(mapStroke(makeEl('path', { stroke: '#00ff00', 'stroke-width': '2' }))).toEqual({ color: '#00ff00', width: 2 });
    });
    it('gradient url(#id) fill → undefined (B3 will handle)', () => {
        expect(mapFill(makeEl('path', { fill: 'url(#g)' }))).toBeUndefined();
    });
    it('named color → hex', () => {
        expect(mapFill(makeEl('path', { fill: 'red' }))).toEqual({ type: 'solid', color: '#ff0000' });
    });
    it('style inline overrides presentation attr', () => {
        expect(mapFill(makeEl('path', { fill: '#000000', style: 'fill:#ff0000' }))).toEqual({ type: 'solid', color: '#ff0000' });
    });
});
