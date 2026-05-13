/**
 * 前端 SVG path 解析器（M9-C，镜像后端 {@code render/PathParser.java}）。
 *
 * 解析 SVG path {@code d} 字符串子集（M / L / Q / C / Z，大小写绝对/相对）→
 * 浏览器原生 {@link Path2D} + 起/终点 + 切线元数据（marker 用）。
 *
 * <p>双端一致性约定：本文件必须与 PathParser.java 逐行同公式。任何修改需同步两端。</p>
 *
 * <p>调用方应先用与后端等效的词法校验过 d（M9-A {@code PathDValidator}），但本 parser
 * 对非法输入<b>尽力而为</b>：遇不识别字符即停止解析，返回当前累积结果，不抛异常。</p>
 */

export interface PathParseResult {
    path: Path2D;
    startX: number;
    startY: number;
    endX: number;
    endY: number;
    startTangentX: number;
    startTangentY: number;
    endTangentX: number;
    endTangentY: number;
    hasSegments: boolean;
}

const EMPTY = (): PathParseResult => ({
    path: new Path2D(),
    startX: 0, startY: 0,
    endX: 0, endY: 0,
    startTangentX: 0, startTangentY: 0,
    endTangentX: 0, endTangentY: 0,
    hasSegments: false,
});

export function parsePathD(d: string | null | undefined): PathParseResult {
    if (!d) return EMPTY();
    const path = new Path2D();

    let curX = 0, curY = 0;
    let subStartX = 0, subStartY = 0;
    let firstX = 0, firstY = 0;
    let firstTanX = 0, firstTanY = 0;
    let lastTanX = 0, lastTanY = 0;
    let hasFirstTangent = false;
    let hasSegments = false;
    let firstMoveDone = false;

    let curCmd = '';
    let paramsPerCmd = 0;
    const paramBuf: number[] = [];
    let firstGroupForCmd = true;

    let i = 0;
    const n = d.length;
    while (i < n) {
        const c = d[i];
        if (isSep(c)) { i++; continue; }
        if (isCmd(c)) {
            curCmd = c;
            paramsPerCmd = paramsForCommand(c);
            paramBuf.length = 0;
            firstGroupForCmd = true;
            i++;
            if (paramsPerCmd === 0) {
                // Z / z
                if (firstMoveDone) {
                    path.closePath();
                    const tx = subStartX - curX;
                    const ty = subStartY - curY;
                    const len = Math.hypot(tx, ty);
                    if (len > 1e-9) {
                        lastTanX = tx / len;
                        lastTanY = ty / len;
                        hasSegments = true;
                    }
                    curX = subStartX;
                    curY = subStartY;
                }
                curCmd = '';
            }
            continue;
        }
        const scanned = scanNumber(d, i);
        if (scanned.endIdx === i) break;  // 不识别字符
        i = scanned.endIdx;
        if (!curCmd) continue;
        paramBuf.push(scanned.value);
        if (paramBuf.length < paramsPerCmd) continue;

        const relative = curCmd.toLowerCase() === curCmd;
        const op = curCmd.toUpperCase();
        switch (op) {
            case 'M': {
                const x = relative ? curX + paramBuf[0] : paramBuf[0];
                const y = relative ? curY + paramBuf[1] : paramBuf[1];
                if (firstGroupForCmd) {
                    path.moveTo(x, y);
                    subStartX = x; subStartY = y;
                    if (!firstMoveDone) {
                        firstX = x; firstY = y;
                        firstMoveDone = true;
                    }
                    firstGroupForCmd = false;
                } else {
                    // 隐式 lineto
                    path.lineTo(x, y);
                    const tx = x - curX, ty = y - curY;
                    const len = Math.hypot(tx, ty);
                    if (len > 1e-9) {
                        if (!hasFirstTangent) {
                            firstTanX = tx / len; firstTanY = ty / len;
                            hasFirstTangent = true;
                        }
                        lastTanX = tx / len; lastTanY = ty / len;
                        hasSegments = true;
                    }
                }
                curX = x; curY = y;
                break;
            }
            case 'L': {
                const x = relative ? curX + paramBuf[0] : paramBuf[0];
                const y = relative ? curY + paramBuf[1] : paramBuf[1];
                path.lineTo(x, y);
                const tx = x - curX, ty = y - curY;
                const len = Math.hypot(tx, ty);
                if (len > 1e-9) {
                    if (!hasFirstTangent) {
                        firstTanX = tx / len; firstTanY = ty / len;
                        hasFirstTangent = true;
                    }
                    lastTanX = tx / len; lastTanY = ty / len;
                    hasSegments = true;
                }
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
            case 'Q': {
                const cx = relative ? curX + paramBuf[0] : paramBuf[0];
                const cy = relative ? curY + paramBuf[1] : paramBuf[1];
                const x = relative ? curX + paramBuf[2] : paramBuf[2];
                const y = relative ? curY + paramBuf[3] : paramBuf[3];
                path.quadraticCurveTo(cx, cy, x, y);
                const tx0 = cx - curX, ty0 = cy - curY;
                const tx1 = x - cx, ty1 = y - cy;
                const len0 = Math.hypot(tx0, ty0);
                const len1 = Math.hypot(tx1, ty1);
                if (!hasFirstTangent) {
                    if (len0 > 1e-9) {
                        firstTanX = tx0 / len0; firstTanY = ty0 / len0;
                        hasFirstTangent = true;
                    } else if (len1 > 1e-9) {
                        firstTanX = tx1 / len1; firstTanY = ty1 / len1;
                        hasFirstTangent = true;
                    }
                }
                if (len1 > 1e-9) {
                    lastTanX = tx1 / len1; lastTanY = ty1 / len1;
                    hasSegments = true;
                } else if (len0 > 1e-9) {
                    lastTanX = tx0 / len0; lastTanY = ty0 / len0;
                    hasSegments = true;
                }
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
            case 'C': {
                const c1x = relative ? curX + paramBuf[0] : paramBuf[0];
                const c1y = relative ? curY + paramBuf[1] : paramBuf[1];
                const c2x = relative ? curX + paramBuf[2] : paramBuf[2];
                const c2y = relative ? curY + paramBuf[3] : paramBuf[3];
                const x = relative ? curX + paramBuf[4] : paramBuf[4];
                const y = relative ? curY + paramBuf[5] : paramBuf[5];
                path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
                const tx0 = c1x - curX, ty0 = c1y - curY;
                const tx1 = x - c2x, ty1 = y - c2y;
                const len0 = Math.hypot(tx0, ty0);
                const len1 = Math.hypot(tx1, ty1);
                if (!hasFirstTangent && len0 > 1e-9) {
                    firstTanX = tx0 / len0; firstTanY = ty0 / len0;
                    hasFirstTangent = true;
                }
                if (len1 > 1e-9) {
                    lastTanX = tx1 / len1; lastTanY = ty1 / len1;
                    hasSegments = true;
                }
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
        }
        paramBuf.length = 0;
    }

    if (!firstMoveDone) return EMPTY();
    return {
        path,
        startX: firstX, startY: firstY,
        endX: curX, endY: curY,
        startTangentX: firstTanX, startTangentY: firstTanY,
        endTangentX: lastTanX, endTangentY: lastTanY,
        hasSegments,
    };
}

function paramsForCommand(c: string): number {
    switch (c.toLowerCase()) {
        case 'm':
        case 'l': return 2;
        case 'q': return 4;
        case 'c': return 6;
        case 'z': return 0;
        default: return -1;
    }
}

function isCmd(c: string): boolean {
    const lc = c.toLowerCase();
    return lc === 'm' || lc === 'l' || lc === 'q' || lc === 'c' || lc === 'z';
}

function isSep(c: string): boolean {
    return c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',';
}

/** 与 Java {@code PathParser.scanNumber} 同语义：扫一个完整 number token。 */
function scanNumber(s: string, i: number): { value: number; endIdx: number } {
    const n = s.length;
    let j = i;
    if (j < n && (s[j] === '+' || s[j] === '-')) j++;
    let hasDigit = false;
    while (j < n && isDigit(s[j])) { j++; hasDigit = true; }
    if (j < n && s[j] === '.') {
        j++;
        while (j < n && isDigit(s[j])) { j++; hasDigit = true; }
    }
    if (!hasDigit) return { value: 0, endIdx: i };
    if (j < n && (s[j] === 'e' || s[j] === 'E')) {
        const eIdx = j;
        j++;
        if (j < n && (s[j] === '+' || s[j] === '-')) j++;
        let hasExpDigit = false;
        while (j < n && isDigit(s[j])) { j++; hasExpDigit = true; }
        if (!hasExpDigit) {
            const v = parseFloat(s.substring(i, eIdx));
            return { value: Number.isFinite(v) ? v : 0, endIdx: eIdx };
        }
    }
    const v = parseFloat(s.substring(i, j));
    return { value: Number.isFinite(v) ? v : 0, endIdx: Number.isFinite(v) ? j : i };
}

function isDigit(c: string): boolean {
    return c >= '0' && c <= '9';
}
