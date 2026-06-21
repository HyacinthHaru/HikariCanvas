import { describe, it, expect } from 'vitest';
import { bakeMatrix, commandsBBox, rebaseToOrigin } from '../bakePath';
import { parsePathCommands } from '../normalizeD';

describe('bakePath', () => {
    it('bakes translate into coordinates', () => {
        const out = bakeMatrix(parsePathCommands('M0 0 L10 10'), [1, 0, 0, 1, 5, 3]);
        expect(out).toEqual([{ type: 'M', pts: [5, 3] }, { type: 'L', pts: [15, 13] }]);
    });
    it('bakes scale into all control points of a C', () => {
        const out = bakeMatrix(parsePathCommands('M0 0 C1 1 2 2 3 3'), [2, 0, 0, 2, 0, 0]);
        expect(out[1]).toEqual({ type: 'C', pts: [2, 2, 4, 4, 6, 6] });
    });
    it('bbox of control points', () => {
        expect(commandsBBox(parsePathCommands('M0 0 L10 4'))).toEqual({ x: 0, y: 0, w: 10, h: 4 });
    });
    it('rebase shifts to origin', () => {
        expect(rebaseToOrigin(parsePathCommands('M5 5 L15 5'), 5, 5))
            .toEqual([{ type: 'M', pts: [0, 0] }, { type: 'L', pts: [10, 0] }]);
    });
});
