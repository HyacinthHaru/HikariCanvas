import { describe, it, expect } from 'vitest';
import { parsePathCommands, commandsToD } from '../normalizeD';

// ── 第一档（Task 8）：M/L/H/V/Q/C/Z + 相对转绝对 + 隐式 lineto ──────────────

describe('parsePathCommands (M/L/H/V/Q/C/Z)', () => {
    it('absolute M/L', () => {
        expect(parsePathCommands('M0 0 L10 10')).toEqual([
            { type: 'M', pts: [0, 0] }, { type: 'L', pts: [10, 10] }]);
    });
    it('relative m/l → absolute', () => {
        expect(parsePathCommands('m5 5 l10 0')).toEqual([
            { type: 'M', pts: [5, 5] }, { type: 'L', pts: [15, 5] }]);
    });
    it('H/V → L (absolute)', () => {
        expect(parsePathCommands('M0 0 H10 V5')).toEqual([
            { type: 'M', pts: [0, 0] }, { type: 'L', pts: [10, 0] }, { type: 'L', pts: [10, 5] }]);
    });
    it('implicit lineto after M', () => {
        expect(parsePathCommands('M0 0 1 1 2 2')).toEqual([
            { type: 'M', pts: [0, 0] }, { type: 'L', pts: [1, 1] }, { type: 'L', pts: [2, 2] }]);
    });
    it('roundtrip via commandsToD', () => {
        expect(commandsToD(parsePathCommands('M0 0 L10 10 Z'))).toBe('M0 0 L10 10 Z');
    });
});

// ── 第二档（Task 9）：S→C / T→Q（反射前控制点）─────────────────────────────

describe('parsePathCommands (S/T reflection)', () => {
    it('S reflects previous C control point → C', () => {
        const cmds = parsePathCommands('M0 0 C0 5 5 5 5 0 S10 -5 10 0');
        const s = cmds[2];
        expect(s.type).toBe('C');
        expect(s.pts.slice(0, 2)).toEqual([5, -5]);   // 反射 (5,5) about (5,0)
    });
    it('T reflects previous Q control → Q', () => {
        const cmds = parsePathCommands('M0 0 Q5 5 10 0 T20 0');
        const t = cmds[2];
        expect(t.type).toBe('Q');
        expect(t.pts.slice(0, 2)).toEqual([15, -5]);   // 反射 (5,5) about (10,0)
    });
});

// ── 第三档（Task 10）：A → cubic（移植后端 arcToBezier）────────────────────

describe('parsePathCommands (A → cubic)', () => {
    it('A quarter circle → cubic(s) ending at endpoint', () => {
        const cmds = parsePathCommands('M10 0 A10 10 0 0 1 0 10');
        const last = cmds[cmds.length - 1];
        expect(last.type).toBe('C');
        expect(last.pts[4]).toBeCloseTo(0);   // 终点 x
        expect(last.pts[5]).toBeCloseTo(10);  // 终点 y
    });
    it('degenerate A (rx=0) falls back to L', () => {
        const cmds = parsePathCommands('M0 0 A0 0 0 0 0 10 10');
        expect(cmds[1]).toEqual({ type: 'L', pts: [10, 10] });   // 零半径 → 直线
    });
    it('compact flags: A10 10 0 0110 10 parsed correctly', () => {
        // "0110 10" => largeArc=0 sweep=1 x=10 y=10
        const cmds = parsePathCommands('M0 0 A10 10 0 0110 10');
        const last = cmds[cmds.length - 1];
        expect(last.type).toBe('C');
        expect(last.pts[4]).toBeCloseTo(10);
        expect(last.pts[5]).toBeCloseTo(10);
    });
});
