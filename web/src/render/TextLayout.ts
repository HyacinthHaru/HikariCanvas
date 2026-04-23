// TextLayout TS 版，镜像 plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java
// 契约 docs/rendering.md §3。M5-C3 第一批：横排；竖排分支留下一个 commit 前后端一起做。

import type { TextElement } from '@/types/protocol';

/** 单字 glyph 在画布坐标下的绘制点。x + baselineY 与后端 Java 含义一致。 */
export interface PositionedGlyph {
    ch: string;
    x: number;
    baselineY: number;
    /** M5-C3 Part 2 竖排用：true 表示该字符应绕自己的绘制点旋转 90°（CJK 全角标点）。 */
    rotated?: boolean;
}

/** 注入测量函数，让 layout 不耦合具体 Canvas 实例。 */
export interface CharMeasurer {
    measureChar(ch: string): number;
}

/** 跨字体统一的 ascent 比例（rendering.md §3.2）。 */
export const ASCENT_RATIO = 0.8;

/** 行首禁则：半全角标点不许出现在行首，回溯到上一行末。 */
const LINE_START_FORBIDDEN = '）】」』。，、？！：；）】」』。，、？！：；)].,!?:;';

export function layoutText(t: TextElement, m: CharMeasurer): PositionedGlyph[] {
    if (!t.text) return [];
    if (t.vertical) return layoutVertical(t, m);
    return layoutHorizontal(t, m);
}

// ---------- 横排 ----------

function layoutHorizontal(t: TextElement, m: CharMeasurer): PositionedGlyph[] {
    const fontSize = t.fontSize;
    const boxW = t.w;
    const letterSpacing = t.letterSpacing;
    const lineHeightMul = t.lineHeight <= 0 ? 1.2 : t.lineHeight;
    const ascentPx = Math.round(fontSize * ASCENT_RATIO);
    const lineHeightPx = Math.max(1, Math.round(fontSize * lineHeightMul));

    // 1) 硬换行
    const paragraphs = t.text.split('\n');

    // 2) 软换行
    const lines: string[] = [];
    for (const para of paragraphs) {
        if (para === '') {
            lines.push('');
        } else {
            softWrap(para, m, boxW, letterSpacing, lines);
        }
    }

    // 3) 行首禁则
    applyLineStartForbidden(lines);

    // 4) align + 基线定位 + letterSpacing 逐字符累加
    const out: PositionedGlyph[] = [];
    for (let li = 0; li < lines.length; li++) {
        const line = lines[li];
        const lineTopY = t.y + li * lineHeightPx;
        const baselineY = lineTopY + ascentPx;
        const lineWidthPx = measureLineWidth(line, m, letterSpacing);
        let startX = t.x;
        if (t.align === 'center') startX = t.x + Math.floor((boxW - lineWidthPx) / 2);
        else if (t.align === 'right') startX = t.x + boxW - lineWidthPx;

        let cursorX = startX;
        for (let i = 0; i < line.length; i++) {
            const ch = line[i];
            out.push({ ch, x: cursorX, baselineY });
            cursorX += m.measureChar(ch);
            if (i < line.length - 1) cursorX += Math.round(letterSpacing);
        }
    }
    return out;
}

function softWrap(text: string, m: CharMeasurer, maxW: number,
                  letterSpacing: number, out: string[]): void {
    const n = text.length;
    let cursor = 0;
    while (cursor < n) {
        let accW = 0;
        let lastBreakEnd = -1;
        let breakAtWhitespace = false;
        let i = cursor;
        let breakOut = -1;

        while (i < n) {
            const ch = text[i];
            const cw = m.measureChar(ch);
            const step = cw + (i > cursor ? Math.round(letterSpacing) : 0);
            if (accW + step > maxW && i > cursor) {
                breakOut = i;
                break;
            }
            accW += step;
            if (ch === ' ' || ch === '\u3000') {
                lastBreakEnd = i + 1;
                breakAtWhitespace = true;
            } else if (isCjk(ch)) {
                lastBreakEnd = i + 1;
                breakAtWhitespace = false;
            }
            i++;
        }

        if (breakOut < 0) {
            out.push(text.substring(cursor));
            return;
        }

        let nextStart: number;
        if (lastBreakEnd >= cursor && lastBreakEnd <= breakOut) {
            const endOfLine = breakAtWhitespace ? lastBreakEnd - 1 : lastBreakEnd;
            out.push(text.substring(cursor, endOfLine));
            nextStart = lastBreakEnd;
            while (nextStart < n && text[nextStart] === ' ') nextStart++;
        } else {
            out.push(text.substring(cursor, breakOut));
            nextStart = breakOut;
        }
        cursor = nextStart;
    }
}

function applyLineStartForbidden(lines: string[]): void {
    for (let i = 1; i < lines.length; i++) {
        const line = lines[i];
        if (line.length === 0) continue;
        const first = line[0];
        if (LINE_START_FORBIDDEN.indexOf(first) >= 0) {
            lines[i - 1] = lines[i - 1] + first;
            lines[i] = line.substring(1);
        }
    }
}

function measureLineWidth(line: string, m: CharMeasurer, letterSpacing: number): number {
    if (line.length === 0) return 0;
    let w = 0;
    for (let i = 0; i < line.length; i++) {
        w += m.measureChar(line[i]);
        if (i < line.length - 1) w += Math.round(letterSpacing);
    }
    return w;
}

// ---------- 竖排（rendering.md §3.3，与 Java TextLayout.layoutVertical 镜像） ----------

function layoutVertical(t: TextElement, m: CharMeasurer): PositionedGlyph[] {
    const fontSize = t.fontSize;
    const letterSpacing = Math.round(t.letterSpacing);
    const lineHeightMul = t.lineHeight <= 0 ? 1.2 : t.lineHeight;
    const colStep = Math.max(1, Math.round(fontSize * lineHeightMul));
    const ascentPx = Math.round(fontSize * ASCENT_RATIO);
    const boxH = t.h;

    const paragraphs = t.text.split('\n');
    const columns: string[][] = [];
    for (const para of paragraphs) {
        if (para === '') {
            columns.push([]);
        } else {
            softWrapVertical(para, fontSize, letterSpacing, boxH, columns);
        }
    }

    const out: PositionedGlyph[] = [];
    const totalCols = columns.length;
    for (let ci = 0; ci < totalCols; ci++) {
        const col = columns[ci];
        if (col.length === 0) continue;
        const colCenterX = t.x + t.w - ci * colStep - Math.floor(colStep / 2);
        const cellsH = col.length;
        const totalH = cellsH * fontSize + Math.max(0, cellsH - 1) * letterSpacing;
        let startTopY: number;
        if (t.align === 'center') startTopY = t.y + Math.floor((boxH - totalH) / 2);
        else if (t.align === 'right') startTopY = t.y + boxH - totalH;
        else startTopY = t.y;

        let cellTopY = startTopY;
        for (const ch of col) {
            if (isRotatableVertical(ch)) {
                out.push({ ch, x: colCenterX, baselineY: cellTopY + Math.floor(fontSize / 2), rotated: true });
            } else {
                const chW = m.measureChar(ch);
                out.push({ ch, x: colCenterX - Math.floor(chW / 2), baselineY: cellTopY + ascentPx });
            }
            cellTopY += fontSize + letterSpacing;
        }
    }
    return out;
}

function softWrapVertical(text: string, fontSize: number, letterSpacing: number,
                          maxH: number, cols: string[][]): void {
    const n = text.length;
    let i = 0;
    while (i < n) {
        const col: string[] = [];
        let accH = 0;
        while (i < n) {
            const step = fontSize + (col.length === 0 ? 0 : letterSpacing);
            if (accH + step > maxH && col.length > 0) break;
            col.push(text[i]);
            accH += step;
            i++;
        }
        cols.push(col);
    }
}

/** 竖排下应旋转 90° 的字符：全角标点与半宽全宽形式（与 Java isRotatableVertical 一致）。 */
function isRotatableVertical(ch: string): boolean {
    const cp = ch.charCodeAt(0);
    return (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
}

// ---------- CJK 判断（与 Java 版相同范围）----------

function isCjk(c: string): boolean {
    const cp = c.charCodeAt(0);
    return (cp >= 0x4E00 && cp <= 0x9FFF)   // CJK Unified
        || (cp >= 0x3400 && cp <= 0x4DBF) // Ext A
        || (cp >= 0x3040 && cp <= 0x309F) // Hiragana
        || (cp >= 0x30A0 && cp <= 0x30FF) // Katakana
        || (cp >= 0xFF00 && cp <= 0xFFEF) // Halfwidth/Fullwidth
        || (cp >= 0x3000 && cp <= 0x303F); // CJK Punct
}

// ---------- Canvas 2D 测量适配 ----------

/**
 * 用 {@link CanvasRenderingContext2D} 的 measureText 测量字符宽度，带字符级缓存。
 * 调用方必须在 measure 前设好 `ctx.font`，否则缓存会以错误字号记项。
 */
export class CanvasMeasurer implements CharMeasurer {
    private cache = new Map<string, number>();

    constructor(private readonly ctx: CanvasRenderingContext2D, private readonly fontKey: string) {}

    measureChar(ch: string): number {
        const key = this.fontKey + '|' + ch;
        const cached = this.cache.get(key);
        if (cached !== undefined) return cached;
        const w = this.ctx.measureText(ch).width;
        this.cache.set(key, w);
        return w;
    }
}
