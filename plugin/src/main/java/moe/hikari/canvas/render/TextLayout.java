package moe.hikari.canvas.render;

import moe.hikari.canvas.state.TextElement;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * 横排文本排版算法，契约对应 {@code docs/rendering.md §3}。
 *
 * <h2>步骤</h2>
 * <ol>
 *   <li>按 {@code \n} 切硬换行段</li>
 *   <li>按 {@link TextElement#w()} 做软换行（CJK 任意位置、空白字符前、拉丁文空白 break）</li>
 *   <li>应用行首禁则：{@code ）】」』。，、？！：；} 等标点不允许独占行首，回溯到上一行末尾</li>
 *   <li>对每行应用 {@link TextElement#align()} 对齐</li>
 *   <li>计算基线：每行高 = {@code fontSize × lineHeight}；基线 = 行顶 + {@code fontSize × ascentRatio}（rendering.md §3.2 固定 0.8）</li>
 * </ol>
 *
 * <h2>不处理</h2>
 * 竖排（{@code vertical=true}）：M4-T5 不实装，由 {@link CanvasCompositor} 在调用前检测并 WARN；
 * 真竖排排版推迟到 M4.5 / M7，见 {@code docs/rendering.md §3.3}。
 *
 * <h2>纯函数</h2>
 * 静态方法，无状态，线程安全。
 */
public final class TextLayout {

    /** 跨字体统一的 ascent 比例，见 rendering.md §3.2。 */
    public static final double ASCENT_RATIO = 0.8;

    /** 行首禁则：出现在行首的标点会被回溯到上一行末尾。半全角都收录。 */
    private static final String LINE_START_FORBIDDEN = "）】」』。，、？！：；）】」』。，、？！：；)].,!?:;";

    private TextLayout() {}

    /**
     * 单个字符 glyph 在画布坐标系下的绘制位置。
     *
     * <p>语义按 {@code rotated}：</p>
     * <ul>
     *   <li>{@code rotated == false}：{@code (x, baselineY)} 是 Graphics2D.drawString 的标准
     *       锚点——字符 baseline 起点；调用方直接 {@code g.drawString(ch, x, baselineY)}</li>
     *   <li>{@code rotated == true}（M5-C3 竖排全角标点）：{@code (x, baselineY)} 是字符
     *       <b>方格中心</b>；调用方应 translate 到该点 → rotate(π/2) → drawString 在字符
     *       自身坐标系中心偏移处（参见 CanvasCompositor.drawRotatedGlyph）</li>
     * </ul>
     */
    public record PositionedGlyph(String ch, int x, int baselineY, boolean rotated) {
        public PositionedGlyph(String ch, int x, int baselineY) {
            this(ch, x, baselineY, false);
        }
    }

    /**
     * 给定 TextElement + FontMetrics（来自目标 fontSize 下派生的 Font），
     * 返回每个字符的绘制位置列表。{@code letterSpacing} 已累计进 x。
     *
     * <p>调用方用 {@code Graphics2D.drawString(glyph.ch(), glyph.x(), glyph.baselineY())}
     * 逐字符绘制。之所以不直接返回整行 + 一次 drawString，是为了支持 letterSpacing
     * 对整数像素的独立控制（Graphics2D 本身不支持 per-char letterSpacing）。</p>
     */
    public static List<PositionedGlyph> layout(TextElement t, FontMetrics fm) {
        if (t.text() == null || t.text().isEmpty()) {
            return List.of();
        }
        if (t.vertical()) {
            return layoutVertical(t, fm);
        }
        int fontSize = t.fontSize();
        int boxWidth = t.w();
        float letterSpacing = t.letterSpacing();
        float lineHeightMul = t.lineHeight() <= 0 ? 1.2f : t.lineHeight();
        int ascentPx = (int) Math.round(fontSize * ASCENT_RATIO);
        int lineHeightPx = Math.max(1, Math.round(fontSize * lineHeightMul));

        // 1) 硬换行
        String[] paragraphs = t.text().split("\n", -1);

        // 2) 软换行（逐段）
        List<String> lines = new ArrayList<>();
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                lines.add("");
            } else {
                softWrap(para, fm, boxWidth, letterSpacing, lines);
            }
        }

        // 3) 行首禁则：回溯下一行首禁止标点到上一行末
        applyLineStartForbidden(lines);

        // 4) 按对齐 + 基线定位
        List<PositionedGlyph> out = new ArrayList<>(t.text().length());
        for (int li = 0; li < lines.size(); li++) {
            String line = lines.get(li);
            int lineTopY = t.y() + li * lineHeightPx;
            int baselineY = lineTopY + ascentPx;
            int lineWidthPx = measureLineWidth(line, fm, letterSpacing);
            int startX = switch (t.align()) {
                case "center" -> t.x() + (boxWidth - lineWidthPx) / 2;
                case "right" -> t.x() + boxWidth - lineWidthPx;
                default -> t.x(); // "left" + 未知值 fallback
            };
            int cursorX = startX;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                out.add(new PositionedGlyph(String.valueOf(c), cursorX, baselineY));
                cursorX += fm.charWidth(c);
                if (i < line.length() - 1) {
                    cursorX += Math.round(letterSpacing);
                }
            }
        }
        return out;
    }

    /**
     * 软换行：在 {@code maxWidth} 内逐字符累计宽度，触发换行时回溯到最近可断点。
     *
     * <p>断点规则：</p>
     * <ul>
     *   <li>{@code ' '}（半角空格）/ {@code '\u3000'}（全角空格）：其 <b>后</b> 可断</li>
     *   <li>CJK 字符：其 <b>后</b> 随时可断（任意两字之间）</li>
     *   <li>若整段无可断点（全 ASCII 长词），强制在当前位置截断</li>
     * </ul>
     *
     * <p>换行时：若断点为空白字符，空白字符 <b>丢弃</b>（不出现在下一行行首）。</p>
     */
    private static void softWrap(String text, FontMetrics fm, int maxWidth,
                                 float letterSpacing, List<String> out) {
        int n = text.length();
        int cursor = 0;
        while (cursor < n) {
            int accWidth = 0;
            int lastBreakEnd = -1; // 最近可断点之后的位置（包含断点字符本身，下一行从这里开始）
            boolean breakAtWhitespace = false;
            int i = cursor;
            int breakOut = -1;

            while (i < n) {
                char c = text.charAt(i);
                int cw = fm.charWidth(c);
                int step = cw + (i > cursor ? Math.round(letterSpacing) : 0);
                if (accWidth + step > maxWidth && i > cursor) {
                    breakOut = i;
                    break;
                }
                accWidth += step;
                if (c == ' ' || c == '\u3000') {
                    lastBreakEnd = i + 1;
                    breakAtWhitespace = true;
                } else if (isCjk(c)) {
                    lastBreakEnd = i + 1;
                    breakAtWhitespace = false;
                }
                i++;
            }

            if (breakOut < 0) {
                // 整段装下
                out.add(text.substring(cursor));
                return;
            }

            int nextStart;
            if (lastBreakEnd >= cursor && lastBreakEnd <= breakOut) {
                // 有可断点
                int endOfLine = breakAtWhitespace ? lastBreakEnd - 1 : lastBreakEnd; // 末尾去空白
                out.add(text.substring(cursor, endOfLine));
                nextStart = lastBreakEnd;
                // 连续空白全部吞掉（避免下一行以空白开头）
                while (nextStart < n && text.charAt(nextStart) == ' ') {
                    nextStart++;
                }
            } else {
                // 无断点，硬截断
                out.add(text.substring(cursor, breakOut));
                nextStart = breakOut;
            }
            cursor = nextStart;
        }
    }

    /** 行首禁则：若下一行首字符属禁止集合，把它挪到上一行末尾。 */
    private static void applyLineStartForbidden(List<String> lines) {
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            char first = line.charAt(0);
            if (LINE_START_FORBIDDEN.indexOf(first) >= 0) {
                lines.set(i - 1, lines.get(i - 1) + first);
                lines.set(i, line.substring(1));
            }
        }
    }

    private static int measureLineWidth(String line, FontMetrics fm, float letterSpacing) {
        if (line.isEmpty()) return 0;
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            width += fm.charWidth(line.charAt(i));
            if (i < line.length() - 1) {
                width += Math.round(letterSpacing);
            }
        }
        return width;
    }

    /**
     * 粗略判断 CJK 统一汉字 + 日文假名 + 全角符号范围。
     * 够用级判断，不覆盖所有 Unicode CJK 扩展。
     */
    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')   // CJK Unified Ideographs
                || (c >= '\u3400' && c <= '\u4DBF') // CJK Extension A
                || (c >= '\u3040' && c <= '\u309F') // Hiragana
                || (c >= '\u30A0' && c <= '\u30FF') // Katakana
                || (c >= '\uFF00' && c <= '\uFFEF') // Halfwidth/Fullwidth Forms
                || (c >= '\u3000' && c <= '\u303F'); // CJK Symbols & Punctuation
    }

    // ---------- M5-C6 竖排（rendering.md §3.3） ----------

    /**
     * 竖排布局。与横排不同：
     * <ul>
     *   <li>字符从上到下、列从右到左（CJK 传统）</li>
     *   <li>每个字符占 {@code fontSize × fontSize} 方格；列宽 = {@code fontSize * lineHeight}</li>
     *   <li>全角标点（{@code U+3000-U+303F} / {@code U+FF00-U+FFEF}）旋转 90° → {@code PositionedGlyph.rotated = true}</li>
     *   <li>半角字符不旋转（直立）</li>
     *   <li>{@code align} 在竖排下语义 = 列内 <b>顶/中/底</b> 对齐</li>
     *   <li>软换行：按 box {@code h}；硬换行 {@code \n} 起新列</li>
     * </ul>
     *
     * <p>行首禁则在竖排下未实装（M7 polish）——相对少见。</p>
     */
    private static List<PositionedGlyph> layoutVertical(TextElement t, FontMetrics fm) {
        int fontSize = t.fontSize();
        int letterSpacing = Math.round(t.letterSpacing());
        float lineHeightMul = t.lineHeight() <= 0 ? 1.2f : t.lineHeight();
        int colStep = Math.max(1, Math.round(fontSize * lineHeightMul));
        int ascentPx = (int) Math.round(fontSize * ASCENT_RATIO);
        int boxH = t.h();

        String[] paragraphs = t.text().split("\n", -1);
        List<List<String>> columns = new ArrayList<>();
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                columns.add(List.of());
            } else {
                softWrapVertical(para, fontSize, letterSpacing, boxH, columns);
            }
        }

        List<PositionedGlyph> out = new ArrayList<>(t.text().length());
        int totalCols = columns.size();
        for (int ci = 0; ci < totalCols; ci++) {
            List<String> col = columns.get(ci);
            if (col.isEmpty()) continue;
            int colCenterX = t.x() + t.w() - ci * colStep - colStep / 2;
            int cellsH = col.size();
            int totalH = cellsH * fontSize + Math.max(0, cellsH - 1) * letterSpacing;
            int startTopY;
            if ("center".equals(t.align())) startTopY = t.y() + (boxH - totalH) / 2;
            else if ("right".equals(t.align())) startTopY = t.y() + boxH - totalH;
            else startTopY = t.y();

            int cellTopY = startTopY;
            for (String s : col) {
                char c = s.charAt(0);
                if (isRotatableVertical(c)) {
                    // pivot = 方格中心；CanvasCompositor 绕此点 rotate 90° 后 drawString
                    out.add(new PositionedGlyph(s, colCenterX, cellTopY + fontSize / 2, true));
                } else {
                    int chW = fm.charWidth(c);
                    out.add(new PositionedGlyph(s, colCenterX - chW / 2, cellTopY + ascentPx, false));
                }
                cellTopY += fontSize + letterSpacing;
            }
        }
        return out;
    }

    private static void softWrapVertical(String text, int fontSize, int letterSpacing,
                                         int maxH, List<List<String>> cols) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            List<String> col = new ArrayList<>();
            int accH = 0;
            while (i < n) {
                int step = fontSize + (col.isEmpty() ? 0 : letterSpacing);
                if (accH + step > maxH && !col.isEmpty()) break;
                col.add(String.valueOf(text.charAt(i)));
                accH += step;
                i++;
            }
            cols.add(col);
        }
    }

    /**
     * 判断字符是否应在竖排下旋转 90°。
     * 覆盖 CJK 标点与符号（U+3000-U+303F）+ 半宽全宽形式（U+FF00-U+FFEF，含全角括号等）。
     * 全角汉字（U+4E00+）本身方形，不旋转。
     */
    private static boolean isRotatableVertical(char c) {
        return (c >= '\u3000' && c <= '\u303F')
                || (c >= '\uFF00' && c <= '\uFFEF');
    }
}
