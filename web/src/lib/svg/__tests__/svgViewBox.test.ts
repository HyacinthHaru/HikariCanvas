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

    it('没指定目标尺寸时，用 SVG 自己声明的 width/height', () => {
        // viewBox 单位与声明尺寸不一致的图标（Material / Font Awesome 全是这形态）：
        // viewBox 是 24 格、实际要画 96 像素。以前只按 viewBox 单位出 24px，图小得看不清。
        const drafts = svgToElements(
            `<svg viewBox="0 0 24 24" width="96" height="96" xmlns="http://www.w3.org/2000/svg">
                <rect x="0" y="0" width="24" height="12" fill="#ff0000"/>
            </svg>`,
        );
        expect(drafts[0].props.w).toBe(96);
        expect(drafts[0].props.h).toBe(48);
    });

    it('width="100%" 不当像素用（没有外层视口可参照）', () => {
        const drafts = svgToElements(
            `<svg viewBox="0 0 24 24" width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">
                <rect x="0" y="0" width="24" height="12" fill="#ff0000"/>
            </svg>`,
        );
        expect(drafts[0].props.w).toBe(24);
        expect(drafts[0].props.h).toBe(12);
    });

    it('viewBox 原点为负时平移归零，元素不会落到画布外', () => {
        const drafts = svgToElements(
            `<svg viewBox="-50 -50 100 100" xmlns="http://www.w3.org/2000/svg">
                <rect x="-50" y="-50" width="20" height="20" fill="#ff0000"/>
            </svg>`,
        );
        expect(drafts[0].props.x).toBe(0);
        expect(drafts[0].props.y).toBe(0);
    });

    it('viewBox 宽高为 0 时当没写，不产出 Infinity 坐标', () => {
        const drafts = svgToElements(
            `<svg viewBox="0 0 0 0" width="100" height="100" xmlns="http://www.w3.org/2000/svg">
                <rect x="1" y="1" width="2" height="3" fill="#ff0000"/>
            </svg>`,
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.x).toBe(1);
        expect(drafts[0].props.w).toBe(2);
    });
});
