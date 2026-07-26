// @vitest-environment happy-dom
/**
 * SVG 规范里 fill 的初始值是黑色、且 fill/stroke 是可继承属性。
 * 这两点以前都没实现：只读叶子节点自身的 fill，读不到就当"没样式"整个形状丢掉，
 * 于是 Font Awesome 实心图标（path 上什么属性都不写）、样式写在父 <g> 上的图标、
 * fill="currentColor" 三类常见输入导进来都是 0 个元素。本文件守这条回归。
 */
import { describe, it, expect } from 'vitest';
import { svgToElements } from '../svgToElements';

describe('svgToElements — fill 缺省与继承', () => {
    it('什么样式都不写的 path（Font Awesome 实心图标形态）按规范填黑色，不丢弃', () => {
        const drafts = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">'
            + '<path d="M0 0 L100 0 L100 100 L0 100 Z"/></svg>',
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.fill).toEqual({ type: 'solid', color: '#000000' });
    });

    it('fill 写在父 <g> 上时被子形状继承', () => {
        const drafts = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg">'
            + '<g fill="#ff0000"><rect x="0" y="0" width="10" height="10"/></g></svg>',
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.fill).toEqual({ type: 'solid', color: '#ff0000' });
    });

    it('stroke / stroke-width 同样沿祖先链继承', () => {
        const drafts = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg">'
            + '<g stroke="#00ff00" stroke-width="3" fill="none">'
            + '<path d="M0 0 L10 10"/></g></svg>',
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.stroke).toEqual({ color: '#00ff00', width: 3 });
        expect(drafts[0].props.fill).toBeUndefined();
    });

    it('fill="currentColor" 解析为黑色；祖先写了 color 时用 color 的值', () => {
        const plain = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg">'
            + '<path d="M0 0 L10 0 L10 10 Z" fill="currentColor"/></svg>',
        );
        expect(plain[0].props.fill).toEqual({ type: 'solid', color: '#000000' });

        const inherited = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg" color="#3366ff">'
            + '<path d="M0 0 L10 0 L10 10 Z" fill="currentColor"/></svg>',
        );
        expect(inherited[0].props.fill).toEqual({ type: 'solid', color: '#3366ff' });
    });

    it('fill 值读不懂时回落黑色而不是丢形状', () => {
        const drafts = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg">'
            + '<rect x="0" y="0" width="10" height="10" fill="url(#missing)"/></svg>',
        );
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.fill).toEqual({ type: 'solid', color: '#000000' });
    });

    it('显式 fill="none" 且无描边仍然跳过（用户真的要一个看不见的形状）', () => {
        const drafts = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg">'
            + '<rect x="0" y="0" width="5" height="5" fill="none"/></svg>',
        );
        expect(drafts).toHaveLength(0);
    });

    it('子元素的 fill 覆盖父 <g> 的 fill', () => {
        const drafts = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg">'
            + '<g fill="#ff0000"><rect x="0" y="0" width="10" height="10" fill="#0000ff"/></g></svg>',
        );
        expect(drafts[0].props.fill).toEqual({ type: 'solid', color: '#0000ff' });
    });

    it('父 <g> 的 fill-rule 也被继承', () => {
        const drafts = svgToElements(
            '<svg xmlns="http://www.w3.org/2000/svg">'
            + '<g fill-rule="evenodd"><path d="M0 0 L10 0 L10 10 Z"/></g></svg>',
        );
        expect(drafts[0].props.fillRule).toBe('evenodd');
    });
});
