// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { mapFill } from '../fillMap';

/**
 * 在 SVG 文档中创建一个带渐变定义的完整 DOM。
 * root 是 <svg>，内含 <defs> 以及 shape 元素。
 */
function makeGradientDoc(defsXml: string, shapeAttrs: Record<string, string>) {
    const svgSrc = `<svg xmlns="http://www.w3.org/2000/svg">
        <defs>${defsXml}</defs>
        <rect id="shape" x="0" y="0" width="10" height="10" ${Object.entries(shapeAttrs).map(([k, v]) => `${k}="${v}"`).join(' ')}/>
    </svg>`;
    const doc = new DOMParser().parseFromString(svgSrc, 'image/svg+xml');
    const root = doc.documentElement;
    const shape = root.querySelector('#shape')!;
    return { root, shape };
}

describe('fillMap gradient (Task 14)', () => {
    it('linearGradient x1=0% x2=100% → angle=0 + correct stops', () => {
        const { root, shape } = makeGradientDoc(
            `<linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="0%">
                <stop offset="0%" stop-color="#ff0000"/>
                <stop offset="100%" stop-color="#0000ff"/>
            </linearGradient>`,
            { fill: 'url(#g)' },
        );
        const fill = mapFill(shape, root);
        expect(fill).toMatchObject({
            type: 'linear',
            angle: 0,
            stops: [
                { position: 0, color: '#ff0000' },
                { position: 1, color: '#0000ff' },
            ],
        });
    });

    it('radialGradient cx=50% cy=50% r=50% → cx=0.5 cy=0.5 r=1.0', () => {
        const { root, shape } = makeGradientDoc(
            `<radialGradient id="r" cx="50%" cy="50%" r="50%">
                <stop offset="0" stop-color="#ffffff"/>
                <stop offset="1" stop-color="#000000"/>
            </radialGradient>`,
            { fill: 'url(#r)' },
        );
        const fill = mapFill(shape, root);
        expect(fill).toMatchObject({
            type: 'radial',
            cx: 0.5,
            cy: 0.5,
            r: 1.0,
            stops: [
                { position: 0, color: '#ffffff' },
                { position: 1, color: '#000000' },
            ],
        });
    });

    it('gradient id not found → undefined (graceful degradation)', () => {
        const { root, shape } = makeGradientDoc('', { fill: 'url(#nonexistent)' });
        const fill = mapFill(shape, root);
        expect(fill).toBeUndefined();
    });

    it('no root param → undefined (backward compat)', () => {
        const doc = new DOMParser().parseFromString(
            '<svg xmlns="http://www.w3.org/2000/svg"><rect id="s" fill="url(#g)"/></svg>',
            'image/svg+xml',
        );
        const shape = doc.querySelector('#s')!;
        const fill = mapFill(shape);  // no root
        expect(fill).toBeUndefined();
    });
});
