package moe.hikari.canvas.render;

import java.util.HashMap;
import java.util.Map;

/**
 * 5×7 像素位图字表。M2 阶段用来画 Placeholder 地图上的水印和位置标签。
 * M4 渲染引擎接入真 TTF 字体后此类会被替换/废弃。
 *
 * <p>字符集只覆盖：
 * <ul>
 *   <li>拼 "HIKARICANVAS" 需要的大写字母：H I K A R C N V S</li>
 *   <li>位置标签需要的数字 0-9 与 "/"</li>
 * </ul>
 * 未知字符返回空白矩形（不报错）。小写字母自动 toUpperCase。</p>
 *
 * <p>每行一个 int，低 5 位从 MSB 到 LSB 代表从左到右的 5 个像素（1 = 前景，0 = 背景）。</p>
 */
public final class BitmapFont {

    public static final int CHAR_WIDTH = 5;
    public static final int CHAR_HEIGHT = 7;

    private static final int[] EMPTY = new int[CHAR_HEIGHT];
    private static final Map<Character, int[]> GLYPHS;

    static {
        Map<Character, int[]> m = new HashMap<>();
        m.put('H', row(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001));
        m.put('I', row(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111));
        m.put('K', row(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001));
        m.put('A', row(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001));
        m.put('R', row(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001));
        m.put('C', row(0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111));
        m.put('N', row(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001));
        m.put('V', row(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100));
        m.put('S', row(0b01111, 0b10000, 0b01110, 0b00001, 0b00001, 0b10001, 0b01110));

        m.put('0', row(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110));
        m.put('1', row(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110));
        m.put('2', row(0b01110, 0b10001, 0b00001, 0b00110, 0b01000, 0b10000, 0b11111));
        m.put('3', row(0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110));
        m.put('4', row(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010));
        m.put('5', row(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110));
        m.put('6', row(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110));
        m.put('7', row(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000));
        m.put('8', row(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110));
        m.put('9', row(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100));

        m.put('/', row(0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000));
        m.put(' ', EMPTY);
        GLYPHS = Map.copyOf(m);
    }

    private BitmapFont() {}

    /** 返回字符 7 行掩码（每行低 5 位有效）；未知字符返回空白。 */
    public static int[] glyph(char c) {
        return GLYPHS.getOrDefault(Character.toUpperCase(c), EMPTY);
    }

    private static int[] row(int... rows) {
        if (rows.length != CHAR_HEIGHT) {
            throw new IllegalArgumentException("glyph must have " + CHAR_HEIGHT + " rows");
        }
        return rows;
    }
}
