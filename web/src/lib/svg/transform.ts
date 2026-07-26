/**
 * SVG transform attribute parser → 2×3 affine matrix.
 *
 * Mat = [a, b, c, d, e, f] mirrors the SVG matrix(a,b,c,d,e,f) convention.
 *
 * Column-vector convention (same as SVG / CSS):
 *   | a  c  e |   | x |
 *   | b  d  f | × | y |
 *   | 0  0  1 |   | 1 |
 *
 * applyPoint([a,b,c,d,e,f], x, y) = [a*x + c*y + e,  b*x + d*y + f]
 *
 * mul(m1, m2) = m1 · m2  (m2 is applied first to the point, then m1)
 * So parseTransform('translate(10,0) scale(2)') accumulates as:
 *   acc = mul(T, S)   meaning: scale first, translate second → point (1,0) → (12,0) ✓
 */

export type Mat = [number, number, number, number, number, number];

/** Identity matrix */
export const IDENTITY: Mat = [1, 0, 0, 1, 0, 0];

/**
 * Apply 2×3 affine matrix to a point.
 * result = [a*x + c*y + e,  b*x + d*y + f]
 */
export function applyPoint(m: Mat, x: number, y: number): [number, number] {
    const [a, b, c, d, e, f] = m;
    return [a * x + c * y + e, b * x + d * y + f];
}

/**
 * Multiply two 2×3 affine matrices (standard 3×3 homogeneous, drop last row).
 *
 * Full 3×3 of m:
 *   | a  c  e |
 *   | b  d  f |
 *   | 0  0  1 |
 *
 * Result r = m1 · m2:
 *   r.a = m1.a*m2.a + m1.c*m2.b
 *   r.b = m1.b*m2.a + m1.d*m2.b
 *   r.c = m1.a*m2.c + m1.c*m2.d
 *   r.d = m1.b*m2.c + m1.d*m2.d
 *   r.e = m1.a*m2.e + m1.c*m2.f + m1.e
 *   r.f = m1.b*m2.e + m1.d*m2.f + m1.f
 */
export function mul(m1: Mat, m2: Mat): Mat {
    const [a1, b1, c1, d1, e1, f1] = m1;
    const [a2, b2, c2, d2, e2, f2] = m2;
    return [
        a1 * a2 + c1 * b2,          // a
        b1 * a2 + d1 * b2,          // b
        a1 * c2 + c1 * d2,          // c
        b1 * c2 + d1 * d2,          // d
        a1 * e2 + c1 * f2 + e1,     // e
        b1 * e2 + d1 * f2 + f1,     // f
    ];
}

/** Build a translate matrix */
function matTranslate(tx: number, ty: number): Mat {
    return [1, 0, 0, 1, tx, ty];
}

/** Build a scale matrix */
function matScale(sx: number, sy: number): Mat {
    return [sx, 0, 0, sy, 0, 0];
}

/** Build a rotation matrix (degrees) about the origin */
function matRotate(deg: number): Mat {
    const rad = (deg * Math.PI) / 180;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);
    return [cos, sin, -sin, cos, 0, 0];
}

/** Build a rotation matrix (degrees) about (cx, cy) */
function matRotateAround(deg: number, cx: number, cy: number): Mat {
    // translate(cx,cy) · rotate(deg) · translate(-cx,-cy)
    return mul(mul(matTranslate(cx, cy), matRotate(deg)), matTranslate(-cx, -cy));
}

/**
 * 六个分量都得是有限数，否则退回单位矩阵。
 *
 * transform="translate(abc)" / "scale(1e999)" 这类写法经 map(Number) 会产出 NaN 或
 * Infinity，烘焙进坐标后 d 串里会出现字面的 "NaN"，服务端直接拒收整个元素——
 * 一个笔误就让整张图少一块。读不懂就当没写这个 transform。
 */
function finiteOrIdentity(m: Mat): Mat {
    for (const v of m) {
        if (!Number.isFinite(v)) return [...IDENTITY] as Mat;
    }
    return m;
}

/** Parse a single transform function into a Mat */
function parseSingle(name: string, rawArgs: string): Mat {
    const args = rawArgs
        .trim()
        .split(/[\s,]+/)
        .filter(Boolean)
        .map(Number);

    switch (name) {
        case 'translate': {
            const tx = args[0] ?? 0;
            const ty = args[1] ?? 0;
            return matTranslate(tx, ty);
        }
        case 'scale': {
            const sx = args[0] ?? 1;
            const sy = args[1] ?? sx;
            return matScale(sx, sy);
        }
        case 'rotate': {
            const deg = args[0] ?? 0;
            if (args.length >= 3) {
                return matRotateAround(deg, args[1], args[2]);
            }
            return matRotate(deg);
        }
        case 'matrix': {
            if (args.length < 6) return [...IDENTITY] as Mat;
            return [args[0], args[1], args[2], args[3], args[4], args[5]];
        }
        case 'skewX': {
            const rad = ((args[0] ?? 0) * Math.PI) / 180;
            const tanVal = Math.tan(rad);
            const safeTan = Number.isFinite(tanVal) && Math.abs(tanVal) < 1e6 ? tanVal : 0;
            return [1, 0, safeTan, 1, 0, 0];
        }
        case 'skewY': {
            const rad = ((args[0] ?? 0) * Math.PI) / 180;
            const tanVal = Math.tan(rad);
            const safeTan = Number.isFinite(tanVal) && Math.abs(tanVal) < 1e6 ? tanVal : 0;
            return [1, safeTan, 0, 1, 0, 0];
        }
        default:
            return [...IDENTITY] as Mat;
    }
}

/**
 * Parse an SVG transform attribute string into a single composite Mat.
 *
 * Transforms are applied left-to-right by accumulating with mul:
 *   acc = mul(acc, current)
 * so that a point is first transformed by the rightmost function and last by
 * the leftmost — matching the SVG specification.
 *
 * Null / empty / unrecognised input → IDENTITY.
 */
export function parseTransform(s: string | null): Mat {
    if (!s) return [...IDENTITY] as Mat;

    const FUNC_RE = /(\w+)\(([^)]*)\)/g;
    let acc: Mat = [...IDENTITY] as Mat;
    let matched = false;
    let m: RegExpExecArray | null;

    while ((m = FUNC_RE.exec(s)) !== null) {
        matched = true;
        const mat = finiteOrIdentity(parseSingle(m[1], m[2]));
        acc = finiteOrIdentity(mul(acc, mat));
    }

    return matched ? acc : ([...IDENTITY] as Mat);
}
