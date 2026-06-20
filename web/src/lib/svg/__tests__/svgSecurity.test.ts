// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { preParseGuard, stripDangerous, SvgImportError } from '../svgSecurity';

describe('preParseGuard', () => {
    it('rejects oversize', () => {
        expect(() => preParseGuard('x'.repeat(2000), 1000)).toThrow(SvgImportError);
    });
    it('rejects DOCTYPE/ENTITY (billion laughs surface)', () => {
        expect(() => preParseGuard('<!DOCTYPE svg [<!ENTITY a "x">]><svg/>', 1_000_000)).toThrow(SvgImportError);
    });
    it('accepts a normal small svg', () => {
        expect(() => preParseGuard('<svg><path d="M0 0"/></svg>', 1_000_000)).not.toThrow();
    });
});

describe('stripDangerous', () => {
    it('removes script / foreignObject / on* / external image href', () => {
        const doc = new DOMParser().parseFromString(
            `<svg xmlns="http://www.w3.org/2000/svg">
               <script>alert(1)</script>
               <foreignObject><div/></foreignObject>
               <rect onload="x()" />
               <image href="http://evil/x.png"/>
               <path d="M0 0"/>
             </svg>`, 'image/svg+xml');
        stripDangerous(doc.documentElement);
        expect(doc.querySelector('script')).toBeNull();
        expect(doc.querySelector('foreignObject')).toBeNull();
        expect(doc.querySelector('rect')?.getAttribute('onload')).toBeNull();
        expect(doc.querySelector('image[href^="http"]')).toBeNull();
        expect(doc.querySelector('path')).not.toBeNull();  // 正常节点保留
    });

    it('strips javascript: href from any element (keeps the element, drops the attr)', () => {
        const doc = new DOMParser().parseFromString(
            `<svg xmlns="http://www.w3.org/2000/svg">
               <rect href="javascript:alert(1)" width="10" height="10"/>
               <path xlink:href="javascript:steal()" d="M0 0"/>
             </svg>`, 'image/svg+xml');
        stripDangerous(doc.documentElement);
        expect(doc.querySelector('rect')?.getAttribute('href')).toBeNull();
        expect(doc.querySelector('rect')).not.toBeNull();   // 元素保留，只删危险属性
        expect(doc.querySelector('path')?.getAttribute('xlink:href')).toBeNull();
        expect(doc.querySelector('path')).not.toBeNull();
    });
});
