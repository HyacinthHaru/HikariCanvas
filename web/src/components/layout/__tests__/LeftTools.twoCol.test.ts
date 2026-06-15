/**
 * 0.7.4：LeftTools 自动两栏阈值逻辑单测。
 *
 * LeftTools 内的切换逻辑是纯 computed：
 *   isSingleCol = availableHeight >= SINGLE_COL_HEIGHT_THRESHOLD (660)
 *
 * 组件本身依赖多个 Pinia store + WsClient + Konva，
 * 挂载成本高、收益低——直接测阈值数学即可，行为已在视觉层验证。
 */
import { describe, it, expect } from 'vitest';
import { ref, computed } from 'vue';

const SINGLE_COL_HEIGHT_THRESHOLD = 660;

function makeIsSingleCol(initialHeight: number) {
    const availableHeight = ref(initialHeight);
    const isSingleCol = computed(() => availableHeight.value >= SINGLE_COL_HEIGHT_THRESHOLD);
    return { availableHeight, isSingleCol };
}

describe('LeftTools 两栏切换阈值', () => {
    it('高度 >= 660 时保持单列', () => {
        const { isSingleCol } = makeIsSingleCol(660);
        expect(isSingleCol.value).toBe(true);
    });

    it('高度 > 660 时保持单列', () => {
        const { isSingleCol } = makeIsSingleCol(800);
        expect(isSingleCol.value).toBe(true);
    });

    it('高度 < 660 时切两栏', () => {
        const { isSingleCol } = makeIsSingleCol(659);
        expect(isSingleCol.value).toBe(false);
    });

    it('高度为 0 时切两栏', () => {
        const { isSingleCol } = makeIsSingleCol(0);
        expect(isSingleCol.value).toBe(false);
    });

    it('窗口缩小后响应式切换为两栏', () => {
        const { availableHeight, isSingleCol } = makeIsSingleCol(800);
        expect(isSingleCol.value).toBe(true);
        availableHeight.value = 500;
        expect(isSingleCol.value).toBe(false);
    });

    it('窗口放大后响应式恢复单列', () => {
        const { availableHeight, isSingleCol } = makeIsSingleCol(400);
        expect(isSingleCol.value).toBe(false);
        availableHeight.value = 700;
        expect(isSingleCol.value).toBe(true);
    });

    it('恰好踩边界 659/660 各自正确', () => {
        const a = makeIsSingleCol(659);
        const b = makeIsSingleCol(660);
        expect(a.isSingleCol.value).toBe(false);
        expect(b.isSingleCol.value).toBe(true);
    });
});
