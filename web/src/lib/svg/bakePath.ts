/**
 * bakePath.ts
 *
 * 把仿射矩阵烘焙进归一化后的 path 命令坐标，计算包围盒，以及把坐标平移到
 * bbox 左上角原点。
 *
 * 依赖：
 *   - normalizeD.ts → Cmd
 *   - transform.ts  → Mat, applyPoint
 */

import type { Cmd } from './normalizeD';
import type { Mat } from './transform';
import { applyPoint } from './transform';

/**
 * 对 Cmd 数组中每个命令的所有点对应用仿射矩阵 m，返回新 Cmd 数组（不可变）。
 * Z 命令（pts=[]）原样保留。
 */
export function bakeMatrix(cmds: Cmd[], m: Mat): Cmd[] {
    return cmds.map(cmd => {
        if (cmd.type === 'Z') return { type: 'Z' as const, pts: [] };
        const newPts: number[] = [];
        for (let i = 0; i < cmd.pts.length; i += 2) {
            const [nx, ny] = applyPoint(m, cmd.pts[i], cmd.pts[i + 1]);
            newPts.push(nx, ny);
        }
        return { type: cmd.type, pts: newPts };
    });
}

/**
 * 扫描所有 Cmd 的控制点（pts 两两），求控制点包围盒。
 * 无任何点（全 Z 或空）→ { x:0, y:0, w:0, h:0 }。
 */
export function commandsBBox(cmds: Cmd[]): { x: number; y: number; w: number; h: number } {
    let minX = Infinity, minY = Infinity;
    let maxX = -Infinity, maxY = -Infinity;
    let hasPoint = false;

    for (const cmd of cmds) {
        for (let i = 0; i < cmd.pts.length; i += 2) {
            const x = cmd.pts[i];
            const y = cmd.pts[i + 1];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            hasPoint = true;
        }
    }

    if (!hasPoint) return { x: 0, y: 0, w: 0, h: 0 };
    return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
}

/**
 * 把所有坐标减去 (x, y)，返回新 Cmd 数组（不可变）。
 * Z 命令原样保留。
 */
export function rebaseToOrigin(cmds: Cmd[], x: number, y: number): Cmd[] {
    return cmds.map(cmd => {
        if (cmd.type === 'Z') return { type: 'Z' as const, pts: [] };
        const newPts: number[] = [];
        for (let i = 0; i < cmd.pts.length; i += 2) {
            newPts.push(cmd.pts[i] - x, cmd.pts[i + 1] - y);
        }
        return { type: cmd.type, pts: newPts };
    });
}
