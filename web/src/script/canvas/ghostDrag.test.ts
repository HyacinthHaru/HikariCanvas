import { describe, it, expect } from 'vitest';
import {
    rotatePoint, normalizeDeg, buildGhostElement, ghostCenter,
    ghostHandlePos, hitGhostHandle, applyGhostDrag,
} from './ghostDrag';
import type { Element } from '@/types/protocol';

function baseEl(over: Partial<Element> = {}): Element {
    return {
        id: 'e1', type: 'rect', x: 10, y: 20, w: 40, h: 60, rotation: 0,
        locked: false, visible: true, fill: { type: 'solid', color: '#fff' },
        ...over,
    } as unknown as Element;
}

describe('rotatePoint', () => {
    it('转 0 度 = 原点', () => {
        const p = rotatePoint(5, 7, 0, 0, 0);
        expect(p.x).toBeCloseTo(5, 6);
        expect(p.y).toBeCloseTo(7, 6);
    });
    it('绕原点顺时针 90 度(屏幕 y 向下):(1,0)→(0,1)', () => {
        const p = rotatePoint(1, 0, 0, 0, 90);
        expect(p.x).toBeCloseTo(0, 6);
        expect(p.y).toBeCloseTo(1, 6);
    });
    it('绕中心转后再反转回原点(round-trip)', () => {
        const f = rotatePoint(13, 29, 5, 5, 37);
        const b = rotatePoint(f.x, f.y, 5, 5, -37);
        expect(b.x).toBeCloseTo(13, 6);
        expect(b.y).toBeCloseTo(29, 6);
    });
});

describe('normalizeDeg', () => {
    it('负角 → [0,360)', () => expect(normalizeDeg(-90)).toBe(270));
    it('超 360 → 取模', () => expect(normalizeDeg(450)).toBe(90));
    it('四舍五入', () => expect(normalizeDeg(44.6)).toBe(45));
});

describe('buildGhostElement', () => {
    it('moveTo: patch x/y 覆盖,w/h/rotation 保留', () => {
        const g = buildGhostElement(baseEl(), 'moveTo', { x: '100', y: '200' });
        expect([g.x, g.y, g.w, g.h]).toEqual([100, 200, 40, 60]);
    });
    it('resize: patch w/h 覆盖(≥1),x/y 保留', () => {
        const g = buildGhostElement(baseEl(), 'resize', { w: '80', h: '0' });
        expect([g.x, g.y, g.w, g.h]).toEqual([10, 20, 80, 1]); // h=0 钳到 1
    });
    it('rotateTo: patch rotation 覆盖', () => {
        const g = buildGhostElement(baseEl(), 'rotateTo', { rotation: '45' });
        expect(g.rotation).toBe(45);
    });
    it('patch 缺/非数 → 退回原元素值', () => {
        const g = buildGhostElement(baseEl(), 'moveTo', { x: 'abc' });
        expect([g.x, g.y]).toEqual([10, 20]);
    });
});

describe('ghostCenter', () => {
    it('= (x+w/2, y+h/2)', () => {
        expect(ghostCenter(baseEl())).toEqual({ x: 30, y: 50 });
    });
});

// ---------- P4a move ----------

describe('ghostHandlePos — moveTo 无独立 handle', () => {
    it('moveTo 返回空对象(拖 bbox 整体,不画角点)', () => {
        expect(ghostHandlePos(baseEl(), 'moveTo')).toEqual({});
    });
});

describe('hitGhostHandle — moveTo', () => {
    const g = buildGhostElement(baseEl(), 'moveTo', { x: '10', y: '20' }); // bbox [10,50]×[20,80]
    it('点落虚影 bbox 内 → move', () => {
        expect(hitGhostHandle(g, 'moveTo', 30, 50, 8)).toBe('move');
    });
    it('点落 bbox 外 → null', () => {
        expect(hitGhostHandle(g, 'moveTo', 5, 5, 8)).toBeNull();
    });
    it('旋转 90 度后,中心一定命中(反旋判定)', () => {
        const gr = { ...g, rotation: 90 } as Element;
        expect(hitGhostHandle(gr, 'moveTo', 30, 50, 8)).toBe('move');
    });
});

describe('applyGhostDrag — move', () => {
    const g = buildGhostElement(baseEl(), 'moveTo', { x: '10', y: '20' });
    it('平移 delta 写回 x/y(round)', () => {
        const p = applyGhostDrag('moveTo', 'move', g, 30, 50, 130, 250); // 指针从中心移 (+100,+200)
        expect(p).toEqual({ x: '110', y: '220' });
    });
    it('负向平移', () => {
        const p = applyGhostDrag('moveTo', 'move', g, 30, 50, 20, 40);
        expect(p).toEqual({ x: '0', y: '10' });
    });
});

// ---------- P4b resize ----------

describe('ghostHandlePos — resize', () => {
    it('未旋转: resizeSE = 右下角', () => {
        const g = buildGhostElement(baseEl(), 'resize', { w: '40', h: '60' }); // x10 y20 w40 h60
        expect(ghostHandlePos(g, 'resize').resizeSE).toEqual({ x: 50, y: 80 });
    });
});

describe('hitGhostHandle — resize', () => {
    const g = buildGhostElement(baseEl(), 'resize', { w: '40', h: '60' });
    it('点近右下角 → resizeSE', () => {
        expect(hitGhostHandle(g, 'resize', 50, 80, 8)).toBe('resizeSE');
    });
    it('点远离 handle → null（resize 无 move 整体拖）', () => {
        expect(hitGhostHandle(g, 'resize', 30, 50, 8)).toBeNull();
    });
});

describe('applyGhostDrag — resizeSE', () => {
    const g = buildGhostElement(baseEl(), 'resize', { w: '40', h: '60' }); // 左上 (10,20)
    it('拖右下角到 (110,220) → w=100 h=200(左上锚定)', () => {
        const p = applyGhostDrag('resize', 'resizeSE', g, 50, 80, 110, 220);
        expect(p).toEqual({ w: '100', h: '200' });
    });
    it('拖到左上内侧 → w/h 钳 ≥1', () => {
        const p = applyGhostDrag('resize', 'resizeSE', g, 50, 80, 5, 5);
        expect(p).toEqual({ w: '1', h: '1' });
    });
});

// ---------- P4c rotate ----------

describe('ghostHandlePos — rotateTo', () => {
    it('未旋转: rotate handle 在中心正上方外延', () => {
        const g = buildGhostElement(baseEl(), 'rotateTo', { rotation: '0' }); // 中心 (30,50)
        const p = ghostHandlePos(g, 'rotateTo').rotate!;
        expect(p.x).toBeCloseTo(30, 6);
        expect(p.y).toBeCloseTo(20 - 24, 6); // g.y(20) - ROTATE_HANDLE_OFFSET(24)
    });
});

describe('applyGhostDrag — rotate', () => {
    const g = buildGhostElement(baseEl(), 'rotateTo', { rotation: '0' }); // 中心 (30,50)
    it('指针在正上方 → rotation 0', () => {
        const p = applyGhostDrag('rotateTo', 'rotate', g, 30, 0, 30, -10);
        expect(p).toEqual({ rotation: '0' });
    });
    it('指针在正右方 → rotation 90', () => {
        const p = applyGhostDrag('rotateTo', 'rotate', g, 30, 0, 60, 50);
        expect(p).toEqual({ rotation: '90' });
    });
    it('指针在正下方 → rotation 180', () => {
        const p = applyGhostDrag('rotateTo', 'rotate', g, 30, 0, 30, 100);
        expect(p).toEqual({ rotation: '180' });
    });
    it('指针在正左方 → rotation 270', () => {
        const p = applyGhostDrag('rotateTo', 'rotate', g, 30, 0, 0, 50);
        expect(p).toEqual({ rotation: '270' });
    });
});
