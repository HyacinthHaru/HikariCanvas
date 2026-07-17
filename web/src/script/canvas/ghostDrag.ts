/**
 * 幽灵拖动纯逻辑（虚影元素构造 / 控制点几何 / hit-test / 拖动写回）。
 *
 * <p>无 Vue / DOM 依赖，纯函数，可单测。PreviewPane 用它把"绑定元素 + 积木 patch"转成虚影几何，
 * 把指针墙坐标转成新 patch（写回走 scriptEdit.updateActionField）。</p>
 */
import type { Element } from '@/types/protocol';

/** 支持幽灵拖动的坐标积木 kind（friendly setElementProperties 的 kind 子集）。 */
export type CoordKind = 'moveTo' | 'resize' | 'rotateTo';

/** 幽灵控制点种类：move=拖整块改 x/y；resizeSE=拖右下角改 w/h；rotate=转手柄改 rotation。 */
export type GhostHandle = 'move' | 'resizeSE' | 'rotate';

/** 旋转手柄距元素上沿的外延（墙像素）。 */
export const ROTATE_HANDLE_OFFSET = 24;

const RAD_PER_DEG = Math.PI / 180;

/** 是否支持拖动的坐标 kind。 */
export function isCoordKind(kind: string): kind is CoordKind {
    return kind === 'moveTo' || kind === 'resize' || kind === 'rotateTo';
}

/**
 * 点 (px,py) 绕中心 (cx,cy) 旋转 deg 度。屏幕坐标系（y 向下，正角顺时针）——与
 * PreviewRenderer.drawElement 的 {@code ctx.rotate(deg*PI/180)} 同向，保证虚影 handle 落在
 * 渲染出的虚影边角上。
 */
export function rotatePoint(
    px: number,
    py: number,
    cx: number,
    cy: number,
    deg: number,
): { x: number; y: number } {
    const rad = deg * RAD_PER_DEG;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);
    const dx = px - cx;
    const dy = py - cy;
    return { x: cx + dx * cos - dy * sin, y: cy + dx * sin + dy * cos };
}

/** 规范化角度到 [0,360) 整数。 */
export function normalizeDeg(deg: number): number {
    return ((Math.round(deg) % 360) + 360) % 360;
}

/** 解析 patch 数值，非有限 → fallback。 */
function num(s: string | undefined, fallback: number): number {
    if (s === undefined) return fallback;
    const v = Number(s);
    return Number.isFinite(v) ? v : fallback;
}

/**
 * 构造虚影元素：原元素副本 + patch 按 kind 覆盖目标几何。
 * moveTo→x/y；resize→w/h（钳 ≥1）；rotateTo→rotation。patch 缺 / 非数 → 退回原元素值。
 */
export function buildGhostElement(
    base: Element,
    kind: CoordKind,
    patch: Record<string, string>,
): Element {
    const g = { ...base } as Element;
    if (kind === 'moveTo') {
        g.x = num(patch.x, base.x);
        g.y = num(patch.y, base.y);
    } else if (kind === 'resize') {
        g.w = Math.max(1, num(patch.w, base.w));
        g.h = Math.max(1, num(patch.h, base.h));
    } else {
        g.rotation = num(patch.rotation, base.rotation);
    }
    return g;
}

/** 虚影中心（墙坐标，= 旋转中心）。 */
export function ghostCenter(g: Element): { x: number; y: number } {
    return { x: g.x + g.w / 2, y: g.y + g.h / 2 };
}

/**
 * 该 kind 要显示的独立控制点及其墙坐标位置（含 rotation）。
 * - moveTo：无独立 handle（拖虚影 bbox 整体），返空。
 * - resize：resizeSE = 右下角（随 rotation 转）。
 * - rotateTo：rotate = 中心正上方外延（随 rotation 转）。
 */
export function ghostHandlePos(
    g: Element,
    kind: CoordKind,
): Partial<Record<GhostHandle, { x: number; y: number }>> {
    const c = ghostCenter(g);
    const deg = g.rotation ?? 0;
    if (kind === 'resize') {
        return { resizeSE: rotatePoint(g.x + g.w, g.y + g.h, c.x, c.y, deg) };
    }
    if (kind === 'rotateTo') {
        return { rotate: rotatePoint(c.x, g.y - ROTATE_HANDLE_OFFSET, c.x, c.y, deg) };
    }
    return {};
}

/**
 * hit-test：墙坐标点命中哪个 handle（半径 rWall 墙像素）。先判专属 handle（距离 ≤ rWall），
 * moveTo 判点是否落在虚影 bbox（含 rotation：反旋指针回未转空间判矩形）。无命中 → null。
 */
export function hitGhostHandle(
    g: Element,
    kind: CoordKind,
    wx: number,
    wy: number,
    rWall: number,
): GhostHandle | null {
    const handles = ghostHandlePos(g, kind);
    for (const key of Object.keys(handles) as GhostHandle[]) {
        const p = handles[key];
        if (p && Math.hypot(wx - p.x, wy - p.y) <= rWall) return key;
    }
    if (kind === 'moveTo') {
        const c = ghostCenter(g);
        const local = rotatePoint(wx, wy, c.x, c.y, -(g.rotation ?? 0));
        if (local.x >= g.x && local.x <= g.x + g.w && local.y >= g.y && local.y <= g.y + g.h) {
            return 'move';
        }
    }
    return null;
}

/**
 * 拖动写回：给定 handle + 拖起虚影 startG + 拖起指针墙坐标 + 当前指针墙坐标 → 新 patch（String 化）。
 * - move：平移 delta（startG.x + dx，round）。
 * - resizeSE：左上锚定，新 w/h = 指针到左上的距离（反旋到未转空间，≥1）。
 * - rotate：中心→指针角度 + 90°（手柄正上方时 rotation=0），normalize。
 */
export function applyGhostDrag(
    kind: CoordKind,
    handle: GhostHandle,
    startG: Element,
    startWx: number,
    startWy: number,
    curWx: number,
    curWy: number,
): Record<string, string> {
    if (handle === 'move') {
        const dx = curWx - startWx;
        const dy = curWy - startWy;
        return { x: String(Math.round(startG.x + dx)), y: String(Math.round(startG.y + dy)) };
    }
    if (handle === 'resizeSE') {
        // 反旋指针到未转空间（绕拖起虚影中心），右下角 = 指针，左上 (x,y) 锚定。
        const c = ghostCenter(startG);
        const local = rotatePoint(curWx, curWy, c.x, c.y, -(startG.rotation ?? 0));
        const w = Math.max(1, Math.round(local.x - startG.x));
        const h = Math.max(1, Math.round(local.y - startG.y));
        return { w: String(w), h: String(h) };
    }
    if (handle === 'rotate') {
        // 中心→指针的角度 + 90°：手柄正上方（atan2=-90°）时 rotation=0；正右(0°)→90°，与渲染顺时针一致。
        const c = ghostCenter(startG);
        const deg = normalizeDeg((Math.atan2(curWy - c.y, curWx - c.x) * 180) / Math.PI + 90);
        return { rotation: String(deg) };
    }
    void kind;
    return {};
}
