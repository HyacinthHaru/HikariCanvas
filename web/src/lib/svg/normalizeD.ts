/**
 * normalizeD.ts
 *
 * 把任意 SVG path `d` 字符串归一化为仅含 M/L/Q/C/Z 的绝对命令子集。
 * 展开的命令：H/V → L，S → C，T → Q，A → cubic bezier (逐段)。
 *
 * 中间表示（Cmd）签名固定，供后续 Task 消费。
 */

export interface Cmd {
    type: 'M' | 'L' | 'Q' | 'C' | 'Z';
    pts: number[];   // 绝对坐标；M/L=2个, Q=4, C=6, Z=0
}

// ── 工具函数 ────────────────────────────────────────────────────────────────

/**
 * 格式化数字：整数去小数点，小数保留必要位数（去尾零，最多6位）。
 */
function fmt(n: number): string {
    if (Number.isInteger(n)) return String(n);
    // 保留6位精度，去尾零
    const s = n.toFixed(6).replace(/\.?0+$/, '');
    return s;
}

// ── commandsToD ─────────────────────────────────────────────────────────────

export function commandsToD(cmds: Cmd[]): string {
    return cmds.map(c => {
        if (c.type === 'Z') return 'Z';
        return c.type + c.pts.map(fmt).join(' ');
    }).join(' ');
}

// ── Tokenizer ────────────────────────────────────────────────────────────────
//
// SVG path token 类型：
//   - 命令字母 (M/m/L/l/H/h/V/v/Q/q/C/c/S/s/T/t/A/a/Z/z)
//   - 数字（含负号、小数点、科学计数法）
//
// A 命令特殊处理：第 4、5 参（largeArc/sweep flag）只取单个 0/1 字符，
// 防止贪婪吞噬 "0110" → [0,1,10] 而非 [011,0]。

// 全程用字符级扫描器（scanNumber 取数字、scanFlag 只取单个 0/1），不走「先
// tokenize 再消费」两阶段——因为 A 命令的 largeArc/sweep flag 可紧贴下一个数字
// （如 "0110" = flag 0,1 + 数字 10），两阶段 tokenizer 会贪婪吞掉 flag 位。

// ── A 命令专用字符级参数扫描器 ──────────────────────────────────────────────
//
// 返回 [rx, ry, xAxisRotDeg, largeArc, sweep, x, y, newPos]
// pos 是在原始字符串 d 中的字符位置（A/a 字母之后）。

function skipWsComma(d: string, pos: number): number {
    while (pos < d.length && /[\s,]/.test(d[pos])) pos++;
    return pos;
}

function scanNumber(d: string, pos: number): [number, number] {
    pos = skipWsComma(d, pos);
    const m = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/.exec(d.slice(pos));
    if (!m) throw new Error(`Expected number at pos ${pos}: ...${d.slice(pos, pos + 10)}`);
    return [parseFloat(m[0]), pos + m[0].length];
}

function scanFlag(d: string, pos: number): [boolean, number] {
    pos = skipWsComma(d, pos);
    const ch = d[pos];
    if (ch !== '0' && ch !== '1') throw new Error(`Expected flag (0/1) at pos ${pos}`);
    return [ch === '1', pos + 1];
}

// ── 向量夹角（移植自 PathParser.java L583-592）──────────────────────────────

function angleBetween(ux: number, uy: number, vx: number, vy: number): number {
    const dot = ux * vx + uy * vy;
    const lenU = Math.hypot(ux, uy);
    const lenV = Math.hypot(vx, vy);
    let cos = dot / (lenU * lenV);
    if (cos > 1) cos = 1;
    if (cos < -1) cos = -1;
    const sign = (ux * vy - uy * vx) >= 0 ? 1 : -1;
    return sign * Math.acos(cos);
}

// ── rotX / rotY（移植自 PathParser.java L574-580）──────────────────────────

function rotX(x: number, y: number, cosPhi: number, sinPhi: number): number {
    return cosPhi * x - sinPhi * y;
}

function rotY(x: number, y: number, cosPhi: number, sinPhi: number): number {
    return sinPhi * x + cosPhi * y;
}

// ── arcToBezier（移植自 PathParser.java L460-572）──────────────────────────
//
// 返回若干 Cmd（type='C'），每段 6 个绝对坐标。
// 退化情况（rx=0 或 ry=0 或起终点重合）返回单个 L。

function arcToBezier(
    x1: number, y1: number,
    rx: number, ry: number,
    xAxisRotDeg: number,
    largeArc: boolean, sweep: boolean,
    x2: number, y2: number,
): Cmd[] {
    // 退化：零半径 → 直线
    if (rx === 0 || ry === 0) {
        return [{ type: 'L', pts: [x2, y2] }];
    }
    // 退化：起终点重合 → 直线（零弧）
    if (x1 === x2 && y1 === y2) {
        return [{ type: 'L', pts: [x2, y2] }];
    }

    // 半径取绝对值（SVG spec）
    rx = Math.abs(rx);
    ry = Math.abs(ry);

    const phi = (xAxisRotDeg * Math.PI) / 180;
    const cosPhi = Math.cos(phi);
    const sinPhi = Math.sin(phi);

    // Step 1: prime 坐标系（L460-475）
    const dx = (x1 - x2) / 2;
    const dy = (y1 - y2) / 2;
    const x1p =  cosPhi * dx + sinPhi * dy;
    const y1p = -sinPhi * dx + cosPhi * dy;

    // Step 2: 半径校正（L477-489）
    let rxSq = rx * rx;
    let rySq = ry * ry;
    const x1pSq = x1p * x1p;
    const y1pSq = y1p * y1p;
    const lambda = x1pSq / rxSq + y1pSq / rySq;
    if (lambda > 1) {
        const s = Math.sqrt(lambda);
        rx *= s;
        ry *= s;
        rxSq = rx * rx;
        rySq = ry * ry;
    }

    // Step 3: prime 坐标系中心（L491-498）
    const sign = (largeArc === sweep) ? -1 : 1;
    let sq = (rxSq * rySq - rxSq * y1pSq - rySq * x1pSq)
           / (rxSq * y1pSq + rySq * x1pSq);
    if (sq < 0) sq = 0;
    const coef = sign * Math.sqrt(sq);
    const cxp = coef * (rx * y1p) / ry;
    const cyp = coef * -(ry * x1p) / rx;

    // Step 4: 回原坐标系（L500-502）
    const cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2;
    const cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2;

    // Step 5: 起 / 末 angle + delta（L504-515）
    const ux = (x1p - cxp) / rx;
    const uy = (y1p - cyp) / ry;
    const vx = (-x1p - cxp) / rx;
    const vy = (-y1p - cyp) / ry;
    const theta1 = angleBetween(1, 0, ux, uy);
    let deltaTheta = angleBetween(ux, uy, vx, vy);
    if (!sweep && deltaTheta > 0) {
        deltaTheta -= 2 * Math.PI;
    } else if (sweep && deltaTheta < 0) {
        deltaTheta += 2 * Math.PI;
    }

    // Step 6: 切 ≤ π/2 段，逐段 cubic bezier（L517-571）
    const segments = Math.max(1, Math.ceil(Math.abs(deltaTheta) / (Math.PI / 2)));
    const segAngle = deltaTheta / segments;
    const t = Math.tan(segAngle / 2);
    const alpha = Math.sin(segAngle) * (Math.sqrt(4 + 3 * t * t) - 1) / 3;

    const result: Cmd[] = [];
    let segStartAngle = theta1;

    for (let s = 0; s < segments; s++) {
        const segEndAngle = segStartAngle + segAngle;

        // 段终点
        const e1x = rx * Math.cos(segEndAngle);
        const e1y = ry * Math.sin(segEndAngle);
        const segEndX = rotX(e1x, e1y, cosPhi, sinPhi) + cx;
        const segEndY = rotY(e1x, e1y, cosPhi, sinPhi) + cy;

        // 参数化导数 P'(θ) = (-rx sinθ, ry cosθ)
        const dStartXp = -rx * Math.sin(segStartAngle);
        const dStartYp =  ry * Math.cos(segStartAngle);
        const dEndXp   = -rx * Math.sin(segEndAngle);
        const dEndYp   =  ry * Math.cos(segEndAngle);

        // 控制点（prime 坐标系）
        const c1xp = rx * Math.cos(segStartAngle) + alpha * dStartXp;
        const c1yp = ry * Math.sin(segStartAngle) + alpha * dStartYp;
        const c2xp = rx * Math.cos(segEndAngle)   - alpha * dEndXp;
        const c2yp = ry * Math.sin(segEndAngle)   - alpha * dEndYp;

        // 应用 rotation + center 平移
        const c1x = rotX(c1xp, c1yp, cosPhi, sinPhi) + cx;
        const c1y = rotY(c1xp, c1yp, cosPhi, sinPhi) + cy;
        const c2x = rotX(c2xp, c2yp, cosPhi, sinPhi) + cx;
        const c2y = rotY(c2xp, c2yp, cosPhi, sinPhi) + cy;

        result.push({ type: 'C', pts: [c1x, c1y, c2x, c2y, segEndX, segEndY] });
        segStartAngle = segEndAngle;
    }

    return result;
}

// ── parsePathCommands ────────────────────────────────────────────────────────

export function parsePathCommands(d: string): Cmd[] {
    const result: Cmd[] = [];

    // 当前点
    let cx = 0, cy = 0;
    // 子路径起点（M 后记录，Z 后回归）
    let subX = 0, subY = 0;
    // 前一命令类型 + 控制点（S/T 反射用）
    let prevCmd = '';
    let prevCx2 = 0, prevCy2 = 0;  // 上一 C 的第二控制点（for S）
    let prevQx = 0, prevQy = 0;    // 上一 Q 的控制点（for T）

    let pos = 0;  // 在 d 字符串中的当前位置

    // 跳过开头空白
    pos = skipWsComma(d, pos);

    // 当前命令字母（小写=相对，大写=绝对）
    let cmdChar = '';
    // 命令消费模式下参数位数计数
    // 我们逐命令字母驱动，每次取足够参数。

    while (pos < d.length) {
        pos = skipWsComma(d, pos);
        if (pos >= d.length) break;

        const ch = d[pos];
        // 如果是命令字母，切换当前命令
        if (/[MmLlHhVvQqCcSsTtAaZz]/.test(ch)) {
            cmdChar = ch;
            pos++;
            // Z/z 立即处理，不需要参数
            if (cmdChar === 'Z' || cmdChar === 'z') {
                result.push({ type: 'Z', pts: [] });
                cx = subX;
                cy = subY;
                prevCmd = 'Z';
                continue;
            }
        } else {
            // 隐式重复：M 后续 → L，m 后续 → l
            if (cmdChar === 'M') cmdChar = 'L';
            else if (cmdChar === 'm') cmdChar = 'l';
            // 其他命令直接重用 cmdChar
        }

        const rel = cmdChar === cmdChar.toLowerCase() && cmdChar !== 'Z' && cmdChar !== 'z';
        const upper = cmdChar.toUpperCase();

        try {
            if (upper === 'M') {
                let x: number, y: number;
                [x, pos] = scanNumber(d, pos);
                [y, pos] = scanNumber(d, pos);
                if (rel && result.length > 0) { x += cx; y += cy; }
                result.push({ type: 'M', pts: [x, y] });
                cx = x; cy = y;
                subX = x; subY = y;
                prevCmd = 'M';

            } else if (upper === 'L') {
                let x: number, y: number;
                [x, pos] = scanNumber(d, pos);
                [y, pos] = scanNumber(d, pos);
                if (rel) { x += cx; y += cy; }
                result.push({ type: 'L', pts: [x, y] });
                cx = x; cy = y;
                prevCmd = 'L';

            } else if (upper === 'H') {
                let x: number;
                [x, pos] = scanNumber(d, pos);
                if (rel) x += cx;
                result.push({ type: 'L', pts: [x, cy] });
                cx = x;
                prevCmd = 'H';

            } else if (upper === 'V') {
                let y: number;
                [y, pos] = scanNumber(d, pos);
                if (rel) y += cy;
                result.push({ type: 'L', pts: [cx, y] });
                cy = y;
                prevCmd = 'V';

            } else if (upper === 'Q') {
                let qx1: number, qy1: number, qx: number, qy: number;
                [qx1, pos] = scanNumber(d, pos);
                [qy1, pos] = scanNumber(d, pos);
                [qx, pos] = scanNumber(d, pos);
                [qy, pos] = scanNumber(d, pos);
                if (rel) {
                    qx1 += cx; qy1 += cy;
                    qx  += cx; qy  += cy;
                }
                result.push({ type: 'Q', pts: [qx1, qy1, qx, qy] });
                prevQx = qx1; prevQy = qy1;
                cx = qx; cy = qy;
                prevCmd = 'Q';

            } else if (upper === 'C') {
                let c1x: number, c1y: number, c2x: number, c2y: number, ex: number, ey: number;
                [c1x, pos] = scanNumber(d, pos);
                [c1y, pos] = scanNumber(d, pos);
                [c2x, pos] = scanNumber(d, pos);
                [c2y, pos] = scanNumber(d, pos);
                [ex, pos] = scanNumber(d, pos);
                [ey, pos] = scanNumber(d, pos);
                if (rel) {
                    c1x += cx; c1y += cy;
                    c2x += cx; c2y += cy;
                    ex  += cx; ey  += cy;
                }
                result.push({ type: 'C', pts: [c1x, c1y, c2x, c2y, ex, ey] });
                prevCx2 = c2x; prevCy2 = c2y;
                cx = ex; cy = ey;
                prevCmd = 'C';

            } else if (upper === 'S') {
                // 第一控制点 = 反射前一 C/S 的第二控制点，否则用当前点
                let c2x: number, c2y: number, ex: number, ey: number;
                [c2x, pos] = scanNumber(d, pos);
                [c2y, pos] = scanNumber(d, pos);
                [ex, pos] = scanNumber(d, pos);
                [ey, pos] = scanNumber(d, pos);
                if (rel) {
                    c2x += cx; c2y += cy;
                    ex  += cx; ey  += cy;
                }
                const c1x = (prevCmd === 'C' || prevCmd === 'S')
                    ? 2 * cx - prevCx2
                    : cx;
                const c1y = (prevCmd === 'C' || prevCmd === 'S')
                    ? 2 * cy - prevCy2
                    : cy;
                result.push({ type: 'C', pts: [c1x, c1y, c2x, c2y, ex, ey] });
                prevCx2 = c2x; prevCy2 = c2y;
                cx = ex; cy = ey;
                prevCmd = 'S';

            } else if (upper === 'T') {
                let ex: number, ey: number;
                [ex, pos] = scanNumber(d, pos);
                [ey, pos] = scanNumber(d, pos);
                if (rel) { ex += cx; ey += cy; }
                const qx1 = (prevCmd === 'Q' || prevCmd === 'T')
                    ? 2 * cx - prevQx
                    : cx;
                const qy1 = (prevCmd === 'Q' || prevCmd === 'T')
                    ? 2 * cy - prevQy
                    : cy;
                result.push({ type: 'Q', pts: [qx1, qy1, ex, ey] });
                prevQx = qx1; prevQy = qy1;
                cx = ex; cy = ey;
                prevCmd = 'T';

            } else if (upper === 'A') {
                // A 命令：rx ry xAxisRotDeg largeArc sweep x y
                // flag (largeArc, sweep) 只取单个 0/1 字符（见 scanFlag）
                let rx: number, ry: number, xRot: number;
                let largeArc: boolean, sweep: boolean;
                let ex: number, ey: number;
                [rx, pos] = scanNumber(d, pos);
                [ry, pos] = scanNumber(d, pos);
                [xRot, pos] = scanNumber(d, pos);
                [largeArc, pos] = scanFlag(d, pos);
                [sweep, pos] = scanFlag(d, pos);
                [ex, pos] = scanNumber(d, pos);
                [ey, pos] = scanNumber(d, pos);
                if (rel) { ex += cx; ey += cy; }
                const arcCmds = arcToBezier(cx, cy, rx, ry, xRot, largeArc, sweep, ex, ey);
                for (const ac of arcCmds) result.push(ac);
                // 更新当前点为终点
                cx = ex; cy = ey;
                prevCmd = 'A';

            } else {
                // 未知命令，跳过
                break;
            }
        } catch {
            // 参数不足，结束解析
            break;
        }
    }

    return result;
}
