// TextLayout TS 版，镜像 plugin/src/main/java/moe/hikari/canvas/render/TextLayout.java
// 契约 docs/rendering.md §3。横排与竖排前后端一起做。

import type { TextElement } from '@/types/protocol';
import { advance } from './GlyphMetricsLut';

/** 单字 glyph 在画布坐标下的绘制点。x + baselineY 与后端 Java 含义一致。 */
export interface PositionedGlyph {
    ch: string;
    x: number;
    baselineY: number;
    /** 竖排用：true 表示该字符应绕自己的绘制点旋转 90°（CJK 全角标点）。 */
    rotated?: boolean;
    /**
     * 该 glyph 在 {@link TextElement#text} 中的原 char index（输入字符串）。
     * <p>仅前端字段——双端一致性不受影响（后端 Java {@code TextLayout.PositionedGlyph} 不带；
     * 仅 PreviewRenderer placeholder hint 用 srcIndex 与 interpolator segments 做 char range
     * 映射）。emoji surrogate pair / combining mark 暂按单 char index 处理。</p>
     */
    srcIndex?: number;
}

/** 跨字体统一的 ascent 比例（rendering.md §3.2）。 */
export const ASCENT_RATIO = 0.8;

/**
 * 前后端一致的"标准"字符宽度函数。
 *
 * <p>问题背景：浏览器 {@code ctx.measureText} 和 Java {@code FontMetrics.charWidth}
 * 即使加载同一 TTF/OTF 也会返回不同值（hinting / subpixel 策略不同）。layout
 * 的 softWrap 依赖 char width，差 1 px 就可能让前后端换行点不同。</p>
 *
 * <p>方案：两端不再读 font metrics，改用规则型宽度：</p>
 * <ul>
 *   <li>码点 {@code < 0x2E80}（ASCII + 拉丁 + 一般标点）：{@code round(fontSize * 0.5)}</li>
 *   <li>其他（CJK / Hiragana / Katakana / 全角）：{@code fontSize}</li>
 * </ul>
 *
 * <p>代价：非等宽字体（如思源黑）的视觉字间距可能略宽，但换行一致性优先。
 * 像素字体 Ark Pixel 本身 monospaced，canonical ≈ actual，无副作用。</p>
 */
export function canonicalCharWidth(ch: string, fontSize: number): number {
    const cp = ch.charCodeAt(0);
    if (cp < 0x2E80) return Math.round(fontSize * 0.5);
    return fontSize;
}

/**
 * 优先查 per-font GlyphMetricsLut（与后端 FontMetricsTable mirror），
 * 表缺 / 字符缺则 fallback 到 {@link canonicalCharWidth}。fontId 沿调用链从 TextElement 透传。
 */
export function charAdvance(fontId: string, ch: string, fontSize: number): number {
    const real = advance(fontId, ch, fontSize);
    if (real > 0) return real;
    return canonicalCharWidth(ch, fontSize);
}

/**
 * 行首禁则：半全角标点不许出现在行首，回溯到上一行末。
 * 去重（原把 11 个全角标点重复写两遍，indexOf 判定对重复不敏感，仅冗余）。
 * 保留一组全角 + 一组半角。顺序：） 】 」 』 。 ， 、 ？ ！ ： ；。
 * 与后端 TextLayout.java 的 LINE_START_FORBIDDEN 逐字节一致（双端镜像硬约束）。
 */
const LINE_START_FORBIDDEN = '）】」』。，、？！：；)].,!?:;';

export function layoutText(t: TextElement): PositionedGlyph[] {
    if (!t.text) return [];
    if (t.vertical) return layoutVertical(t);
    return layoutHorizontal(t);
}

// ---------- 横排 ----------

function layoutHorizontal(t: TextElement): PositionedGlyph[] {
    const fontSize = t.fontSize;
    const fontId = t.fontId;
    const boxW = t.w;
    const letterSpacing = t.letterSpacing;
    const lineHeightMul = t.lineHeight <= 0 ? 1.2 : t.lineHeight;
    const ascentPx = Math.round(fontSize * ASCENT_RATIO);
    const lineHeightPx = Math.max(1, Math.round(fontSize * lineHeightMul));

    // 1) 硬换行（保留 srcIndex 起点，便于 step 2 / 4 透传到 PositionedGlyph.srcIndex）
    type LineWithIndex = { text: string; srcStart: number };
    const paragraphs: LineWithIndex[] = [];
    {
        let cursor = 0;
        const parts = t.text.split('\n');
        for (let pi = 0; pi < parts.length; pi++) {
            paragraphs.push({ text: parts[pi], srcStart: cursor });
            cursor += parts[pi].length + 1; // +1 for \n
        }
    }

    // 2) 软换行
    const lines: LineWithIndex[] = [];
    for (const para of paragraphs) {
        if (para.text === '') {
            lines.push({ text: '', srcStart: para.srcStart });
        } else {
            softWrap(para.text, para.srcStart, fontId, fontSize, boxW, letterSpacing, lines);
        }
    }

    // 3) 行首禁则
    applyLineStartForbidden(lines);

    // 4) align + 基线定位 + letterSpacing 逐字符累加（同步携带 srcIndex）
    const out: PositionedGlyph[] = [];
    for (let li = 0; li < lines.length; li++) {
        const line = lines[li];
        const lineTopY = t.y + li * lineHeightPx;
        const baselineY = lineTopY + ascentPx;
        const lineWidthPx = measureLineWidth(line.text, fontId, fontSize, letterSpacing);
        let startX = t.x;
        // 用 Math.trunc（向零截断）匹配后端 Java 整数除法 `/2` 语义，
        // 避免行宽溢出文本框（负奇数分子）时双端 startX 差 1px。
        if (t.align === 'center') startX = t.x + Math.trunc((boxW - lineWidthPx) / 2);
        else if (t.align === 'right') startX = t.x + boxW - lineWidthPx;

        let cursorX = startX;
        for (let i = 0; i < line.text.length; i++) {
            const ch = line.text[i];
            out.push({ ch, x: cursorX, baselineY, srcIndex: line.srcStart + i });
            cursorX += charAdvance(fontId, ch, fontSize);
            if (i < line.text.length - 1) cursorX += Math.round(letterSpacing);
        }
    }
    return out;
}

function softWrap(text: string, srcStart: number, fontId: string, fontSize: number, maxW: number,
                  letterSpacing: number, out: { text: string; srcStart: number }[]): void {
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
            const cw = charAdvance(fontId, ch, fontSize);
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
            out.push({ text: text.substring(cursor), srcStart: srcStart + cursor });
            return;
        }

        let nextStart: number;
        if (lastBreakEnd >= cursor && lastBreakEnd <= breakOut) {
            const endOfLine = breakAtWhitespace ? lastBreakEnd - 1 : lastBreakEnd;
            out.push({ text: text.substring(cursor, endOfLine), srcStart: srcStart + cursor });
            nextStart = lastBreakEnd;
            while (nextStart < n && text[nextStart] === ' ') nextStart++;
        } else {
            out.push({ text: text.substring(cursor, breakOut), srcStart: srcStart + cursor });
            nextStart = breakOut;
        }
        cursor = nextStart;
    }
}

function applyLineStartForbidden(lines: { text: string; srcStart: number }[]): void {
    for (let i = 1; i < lines.length; i++) {
        const line = lines[i];
        if (line.text.length === 0) continue;
        const first = line.text[0];
        if (LINE_START_FORBIDDEN.indexOf(first) >= 0) {
            // \u628a\u9996\u5b57\u7b26\u56de\u632a\u5230\u4e0a\u4e00\u884c\u672b\u5c3e\u2014\u2014\u4e0a\u4e00\u884c\u4e0d\u52a8 srcStart\uff0c\u672c\u884c srcStart +1
            lines[i - 1] = { text: lines[i - 1].text + first, srcStart: lines[i - 1].srcStart };
            lines[i] = { text: line.text.substring(1), srcStart: line.srcStart + 1 };
        }
    }
}

function measureLineWidth(line: string, fontId: string, fontSize: number, letterSpacing: number): number {
    if (line.length === 0) return 0;
    let w = 0;
    for (let i = 0; i < line.length; i++) {
        w += charAdvance(fontId, line[i], fontSize);
        if (i < line.length - 1) w += Math.round(letterSpacing);
    }
    return w;
}

// ---------- 竖排（rendering.md §3.3，与 Java TextLayout.layoutVertical 镜像） ----------

function layoutVertical(t: TextElement): PositionedGlyph[] {
    const fontSize = t.fontSize;
    const fontId = t.fontId;
    const letterSpacing = Math.round(t.letterSpacing);
    const lineHeightMul = t.lineHeight <= 0 ? 1.2 : t.lineHeight;
    const colStep = Math.max(1, Math.round(fontSize * lineHeightMul));
    const ascentPx = Math.round(fontSize * ASCENT_RATIO);
    const boxH = t.h;

    // 同 horizontal：携带 srcStart（每个 col 的起始 char 在原 t.text 中 index）
    type Col = { chars: string[]; srcStart: number };
    const columns: Col[] = [];
    {
        let cursor = 0;
        const parts = t.text.split('\n');
        for (let pi = 0; pi < parts.length; pi++) {
            const para = parts[pi];
            if (para === '') {
                columns.push({ chars: [], srcStart: cursor });
            } else {
                softWrapVertical(para, cursor, fontSize, letterSpacing, boxH, columns);
            }
            cursor += para.length + 1;
        }
    }

    const out: PositionedGlyph[] = [];
    const totalCols = columns.length;
    for (let ci = 0; ci < totalCols; ci++) {
        const col = columns[ci];
        if (col.chars.length === 0) continue;
        const colCenterX = t.x + t.w - ci * colStep - Math.floor(colStep / 2);
        const cellsH = col.chars.length;
        const totalH = cellsH * fontSize + Math.max(0, cellsH - 1) * letterSpacing;
        let startTopY: number;
        // 同横排——竖排 center 起点用 Math.trunc 匹配后端 `/2` 截断语义。
        if (t.align === 'center') startTopY = t.y + Math.trunc((boxH - totalH) / 2);
        else if (t.align === 'right') startTopY = t.y + boxH - totalH;
        else startTopY = t.y;

        let cellTopY = startTopY;
        for (let i = 0; i < col.chars.length; i++) {
            const ch = col.chars[i];
            const srcIndex = col.srcStart + i;
            if (isRotatableVertical(ch)) {
                out.push({ ch, x: colCenterX, baselineY: cellTopY + Math.floor(fontSize / 2), rotated: true, srcIndex });
            } else {
                const chW = charAdvance(fontId, ch, fontSize);
                out.push({ ch, x: colCenterX - Math.floor(chW / 2), baselineY: cellTopY + ascentPx, srcIndex });
            }
            cellTopY += fontSize + letterSpacing;
        }
    }
    return out;
}

function softWrapVertical(text: string, srcStart: number, fontSize: number, letterSpacing: number,
                          maxH: number, cols: { chars: string[]; srcStart: number }[]): void {
    const n = text.length;
    let i = 0;
    while (i < n) {
        const chars: string[] = [];
        const colStartChar = i;
        let accH = 0;
        while (i < n) {
            const step = fontSize + (chars.length === 0 ? 0 : letterSpacing);
            if (accH + step > maxH && chars.length > 0) break;
            chars.push(text[i]);
            accH += step;
            i++;
        }
        cols.push({ chars, srcStart: srcStart + colStartChar });
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

// ---------- CanvasMeasurer 已废弃（切到 canonicalCharWidth） ----------
// 原文件导出的 CanvasMeasurer 类与 CharMeasurer 接口此轮移除，调用方已不再传 measurer。
