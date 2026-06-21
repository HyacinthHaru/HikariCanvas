// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { parseSvg } from '../svgParse';

describe('parseSvg', () => {
    it('collects graphic shapes (flattened across groups) and reads viewBox', () => {
        const doc = parseSvg(`<svg viewBox="0 0 100 50" xmlns="http://www.w3.org/2000/svg">
            <g><path d="M0 0"/><rect x="1" y="1" width="2" height="3"/></g>
            <circle cx="5" cy="5" r="2"/>
        </svg>`);
        expect(doc.viewBox).toEqual([0, 0, 100, 50]);
        expect(doc.shapes.map((s) => s.tagName.toLowerCase())).toEqual(['path', 'rect', 'circle']);
    });
    it('drops <script>/<animate> before collecting', () => {
        const doc = parseSvg(`<svg xmlns="http://www.w3.org/2000/svg"><script/><path d="M0 0"><animate/></path></svg>`);
        expect(doc.shapes.map((s) => s.tagName.toLowerCase())).toEqual(['path']);
    });
});
