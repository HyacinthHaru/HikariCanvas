import { describe, it, expect } from 'vitest';
import { parseTransform, mul, applyPoint, IDENTITY } from '../transform';

describe('transform', () => {
    it('translate', () => { expect(applyPoint(parseTransform('translate(5,3)'), 1, 1)).toEqual([6, 4]); });
    it('translate single arg (ty defaults 0)', () => { expect(applyPoint(parseTransform('translate(5)'), 1, 1)).toEqual([6, 1]); });
    it('scale', () => { expect(applyPoint(parseTransform('scale(2,3)'), 2, 2)).toEqual([4, 6]); });
    it('scale single arg (uniform)', () => { expect(applyPoint(parseTransform('scale(2)'), 2, 2)).toEqual([4, 4]); });
    it('rotate 90 about origin', () => {
        const [x, y] = applyPoint(parseTransform('rotate(90)'), 1, 0);
        expect(x).toBeCloseTo(0); expect(y).toBeCloseTo(1);
    });
    it('matrix passthrough', () => {
        expect(applyPoint(parseTransform('matrix(1,0,0,1,5,6)'), 0, 0)).toEqual([5, 6]);
    });
    it('chained translate then scale composes left-to-right', () => {
        // SVG: 先 translate 后 scale → 点先被 scale 再 translate(矩阵右乘)
        const m = parseTransform('translate(10,0) scale(2)');
        expect(applyPoint(m, 1, 0)).toEqual([12, 0]);
    });
    it('empty / null → identity', () => {
        expect(parseTransform('')).toEqual(IDENTITY);
        expect(parseTransform(null)).toEqual(IDENTITY);
    });
});
