// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { svgToElements } from '../svgToElements';

describe('svgToElements', () => {
    it('a rect+path SVG → 2 path drafts with baked coords + fill', () => {
        const drafts = svgToElements(`<svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
            <rect x="10" y="10" width="20" height="20" fill="#ff0000"/>
            <path d="M0 0 L50 50" stroke="#00ff00" stroke-width="2"/>
        </svg>`);
        expect(drafts).toHaveLength(2);
        expect(drafts[0].type).toBe('path');
        expect(drafts[0].props.fill).toEqual({ type: 'solid', color: '#ff0000' });
        expect(typeof drafts[0].props.d).toBe('string');
        expect(drafts[0].props.w).toBe(20);   // rect bbox 宽
    });
    it('bakes ancestor group transform into coords', () => {
        const drafts = svgToElements(`<svg xmlns="http://www.w3.org/2000/svg">
            <g transform="translate(100,0)"><rect x="0" y="0" width="10" height="10" fill="#000000"/></g>
        </svg>`);
        expect(drafts[0].props.x).toBe(100);   // group translate 烘焙进元素 x
    });
    it('skips shapes with neither fill nor stroke', () => {
        const drafts = svgToElements(`<svg xmlns="http://www.w3.org/2000/svg"><rect x="0" y="0" width="5" height="5" fill="none"/></svg>`);
        expect(drafts).toHaveLength(0);
    });
});
