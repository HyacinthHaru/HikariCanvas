// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { svgToElements } from '../svgToElements';

describe('svgToElements viewBox mapping (Task 15)', () => {
    it('viewBox="0 0 10 10" + targetWidth=100 targetHeight=100 → element coordinates scaled 10×', () => {
        // rect at x=1,y=1 width=2 height=3 in viewBox space
        // after 10× scale: x=10, y=10, w=20, h=30
        const drafts = svgToElements(
            `<svg viewBox="0 0 10 10" xmlns="http://www.w3.org/2000/svg">
                <rect x="1" y="1" width="2" height="3" fill="#ff0000"/>
            </svg>`,
            { targetWidth: 100, targetHeight: 100 },
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.x).toBeCloseTo(10);
        expect(drafts[0].props.y).toBeCloseTo(10);
        expect(drafts[0].props.w).toBeCloseTo(20);
        expect(drafts[0].props.h).toBeCloseTo(30);
    });

    it('no viewBox → coordinates unchanged (regression)', () => {
        // existing behavior: rect at x=10,y=10,w=20,h=20 → same coords
        const drafts = svgToElements(
            `<svg xmlns="http://www.w3.org/2000/svg">
                <rect x="10" y="10" width="20" height="20" fill="#000000"/>
            </svg>`,
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.x).toBeCloseTo(10);
        expect(drafts[0].props.y).toBeCloseTo(10);
        expect(drafts[0].props.w).toBeCloseTo(20);
        expect(drafts[0].props.h).toBeCloseTo(20);
    });

    it('viewBox present but no targetWidth/targetHeight → coordinates unchanged', () => {
        // Without target dimensions, viewBox should not scale
        const drafts = svgToElements(
            `<svg viewBox="0 0 10 10" xmlns="http://www.w3.org/2000/svg">
                <rect x="1" y="1" width="2" height="3" fill="#ff0000"/>
            </svg>`,
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.x).toBeCloseTo(1);
        expect(drafts[0].props.y).toBeCloseTo(1);
        expect(drafts[0].props.w).toBeCloseTo(2);
        expect(drafts[0].props.h).toBeCloseTo(3);
    });
});
