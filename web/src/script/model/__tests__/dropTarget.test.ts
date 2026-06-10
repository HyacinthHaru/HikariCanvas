/**
 * 0.7.0-P4-A：dropTarget 吸附几何单测（纯逻辑，vitest node）。
 */
import { describe, expect, it } from 'vitest';
import { findDropTarget, type SlotRect } from '../dropTarget';

function slot(
    stackRuleId: string,
    x: number,
    y: number,
    w: number,
    h: number,
    index = 0,
    parentPath: string[] = ['actions'],
): SlotRect {
    return { stackRuleId, parentPath, index, x, y, w, h };
}

describe('dropTarget.findDropTarget', () => {
    it('单插槽：指针落在矩形内 → 命中（距离 0）', () => {
        const s = slot('sr-1', 0, 90, 200, 20);
        const hit = findDropTarget([s], 100, 100, 50);
        expect(hit).toBe(s);
    });

    it('单插槽：指针在阈值内（矩形外）→ 命中', () => {
        const s = slot('sr-1', 0, 110, 200, 10); // 指针在其上方 10px
        expect(findDropTarget([s], 100, 100, 50)).toBe(s);
    });

    it('超阈值 → null', () => {
        const s = slot('sr-1', 0, 500, 200, 10); // 指针离它 ~390px
        expect(findDropTarget([s], 100, 100, 50)).toBeNull();
    });

    it('空 slots → null', () => {
        expect(findDropTarget([], 100, 100, 50)).toBeNull();
    });

    it('多插槽：取加权距离最近者', () => {
        const near = slot('near', 0, 110, 200, 10, 1); // dy=10
        const far = slot('far', 0, 300, 200, 10, 2); // dy~190
        const hit = findDropTarget([far, near], 100, 100, 9999);
        expect(hit).toBe(near);
    });

    it('垂直方向接近优先于水平（H_WEIGHT 生效，翻转 raw 距离的选择）', () => {
        // 指针 (100,100)。
        // V：竖直很近(dy=5)、水平很远(dx=70)。raw≈70.2，weighted≈25.0
        // H：水平很近(dx=0)、竖直较远(dy=40)。raw=40，weighted=40
        // raw 会选 H；加权后应选 V（验证垂直优先）。
        const v = slot('V', 0, 105, 30, 5, 1);
        const h = slot('H', 95, 140, 10, 5, 2);
        const hit = findDropTarget([h, v], 100, 100, 9999);
        expect(hit?.stackRuleId).toBe('V');
    });

    it('平局取 slots 中先出现者（稳定）', () => {
        // 两个完全对称的插槽（指针正中），加权距离相等 → 取数组中先者。
        const first = slot('first', 0, 110, 200, 10, 1);
        const second = slot('second', 0, 110, 200, 10, 2);
        expect(findDropTarget([first, second], 100, 100, 50)?.stackRuleId).toBe('first');
    });

    it('边界：指针恰在阈值距离上 → 命中（≤ threshold）', () => {
        // 插槽在指针正下方 50px（dx=0，dy=50），加权距离=50；threshold=50 → 命中。
        const s = slot('edge', 0, 150, 200, 0);
        expect(findDropTarget([s], 100, 100, 50)).toBe(s);
        // threshold=49 → 不命中
        expect(findDropTarget([s], 100, 100, 49)).toBeNull();
    });

    it('非有限指针 / 阈值 → null', () => {
        const s = slot('sr-1', 0, 90, 200, 20);
        expect(findDropTarget([s], NaN, 100, 50)).toBeNull();
        expect(findDropTarget([s], 100, Infinity, 50)).toBeNull();
        expect(findDropTarget([s], 100, 100, NaN)).toBeNull();
        expect(findDropTarget([s], 100, 100, -1)).toBeNull();
    });

    it('命中插槽携带正确的落点元数据（parentPath / index）', () => {
        const ifSlot = slot('sr-1', 0, 110, 200, 10, 2, ['actions', '1', 'then']);
        const hit = findDropTarget([ifSlot], 100, 100, 50);
        expect(hit?.parentPath).toEqual(['actions', '1', 'then']);
        expect(hit?.index).toBe(2);
    });
});
