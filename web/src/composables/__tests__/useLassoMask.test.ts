// @vitest-environment happy-dom
/**
 * 蒙版顶点上限双端一致性守卫。
 *
 * 服务端 ElementValidator.MAX_MASK_VERTICES = 64（数 d 里的 M/L/Q/C 命令个数），
 * 前端以前写 200，简化完还剩七八十个点照发不误 → element.update 100% 被拒且无提示。
 */
import { describe, it, expect, vi } from 'vitest';
import { effectScope } from 'vue';
import { useLassoMask, MAX_VERTICES } from '../useLassoMask';
import type { ImageElement, Mask } from '@/types/protocol';

const img = { id: 'e-img', type: 'image', x: 0, y: 0, w: 400, h: 400, rotation: 0 } as unknown as ImageElement;

/** 在 effect scope 里建 composable（它内部用 onScopeDispose）。 */
function makeLasso(onUpdateMask: (m: Mask) => void, onError: (k: string) => void) {
    const scope = effectScope();
    const lasso = scope.run(() => useLassoMask({
        selectedImageGetter: () => img,
        activeToolGetter: () => 'select',
        clientToElementLocal: (x, y) => ({ lx: x, ly: y }),
        onUpdateMask,
        onError: onError as never,
    }))!;
    return { lasso, scope };
}

/** 造一串"点数很多、且互相都离得很远"的点，让 RDP 压不下去。 */
function noisyRing(n: number): Array<[number, number]> {
    const pts: Array<[number, number]> = [];
    for (let i = 0; i < n; i++) {
        const t = (i / n) * Math.PI * 2;
        // 半径在 60 / 190 之间来回跳 → 相邻点连线的偏离远超最大容差，RDP 删不掉
        const r = i % 2 === 0 ? 60 : 190;
        pts.push([200 + Math.cos(t) * r, 200 + Math.sin(t) * r]);
    }
    return pts;
}

function countVertices(d: string): number {
    return (d.match(/[MLQCmlqc]/g) ?? []).length;
}

function drawAndEnd(lasso: ReturnType<typeof makeLasso>['lasso'], pts: Array<[number, number]>) {
    lasso.isAltDown.value = true;
    lasso.start({ clientX: pts[0][0], clientY: pts[0][1] } as PointerEvent);
    for (let i = 1; i < pts.length; i++) {
        lasso.move({ clientX: pts[i][0], clientY: pts[i][1] } as PointerEvent);
    }
    lasso.end();
}

describe('useLassoMask 顶点上限', () => {
    it('上限常量与服务端 ElementValidator.MAX_MASK_VERTICES 一致', () => {
        expect(MAX_VERTICES).toBe(64);
    });

    it('复杂套索被压到 64 个顶点以内，并给出可见提示', () => {
        const onUpdateMask = vi.fn();
        const onError = vi.fn();
        const { lasso, scope } = makeLasso(onUpdateMask, onError);
        drawAndEnd(lasso, noisyRing(400));
        scope.stop();

        expect(onUpdateMask).toHaveBeenCalledTimes(1);
        const mask = onUpdateMask.mock.calls[0][0] as Mask;
        expect(countVertices(mask.d)).toBeLessThanOrEqual(64);
        expect(onError).toHaveBeenCalledWith('simplified');
    });

    it('简单套索原样保留，不触发提示', () => {
        const onUpdateMask = vi.fn();
        const onError = vi.fn();
        const { lasso, scope } = makeLasso(onUpdateMask, onError);
        drawAndEnd(lasso, [[0, 0], [100, 0], [100, 100], [0, 100]]);
        scope.stop();

        const mask = onUpdateMask.mock.calls[0][0] as Mask;
        expect(countVertices(mask.d)).toBe(4);
        expect(onError).not.toHaveBeenCalled();
    });

    it('点数不足时报 too-few-points 且不发蒙版', () => {
        const onUpdateMask = vi.fn();
        const onError = vi.fn();
        const { lasso, scope } = makeLasso(onUpdateMask, onError);
        drawAndEnd(lasso, [[0, 0], [50, 50]]);
        scope.stop();

        expect(onUpdateMask).not.toHaveBeenCalled();
        expect(onError).toHaveBeenCalledWith('too-few-points');
    });
});
