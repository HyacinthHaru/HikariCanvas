package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StrictNumber} 单测（0.6 P3，rendering.md §9.5）：严格数值文法 + int 钳位的双端
 * 唯一权威。钉住 P3 审查发现的三处双端数字分叉根因——
 * #1 变量 resolve 后私自 {@code parseDouble} 会接受 {@code 0x1p4} / 尾随 {@code d|f}；
 * #2 大数 {@code (int)} 收窄静默回绕；#3 JS {@code trim} 多剥 Unicode 空白。
 */
class StrictNumberTest {

    @Test
    void parsesPlainNumbers() {
        assertEquals(42.0, StrictNumber.parse("42"), 0.0);
        assertEquals(-3.5, StrictNumber.parse("-3.5"), 0.0);
        assertEquals(0.5, StrictNumber.parse(".5"), 0.0);
        assertEquals(1.0, StrictNumber.parse("1."), 0.0);
        assertEquals(1000.0, StrictNumber.parse("1e3"), 0.0);
        assertEquals(0.015, StrictNumber.parse("1.5E-2"), 0.0);
        assertEquals(7.0, StrictNumber.parse("  7  "), 0.0, "ASCII 空白 trim 后解析");
    }

    @Test
    void rejectsHostLenientForms() {
        // #1：Double.parseDouble / parseFloat 会接受这些，严格文法整串匹配 → 拒 → 0
        assertEquals(0.0, StrictNumber.parse("0x1p4"), 0.0, "十六进制浮点");
        assertEquals(0.0, StrictNumber.parse("0x10"), 0.0, "十六进制整数");
        assertEquals(0.0, StrictNumber.parse("5d"), 0.0, "尾随 d");
        assertEquals(0.0, StrictNumber.parse("3.14f"), 0.0, "尾随 f");
        assertEquals(0.0, StrictNumber.parse("12abc"), 0.0, "parseFloat 前缀宽松");
        assertEquals(0.0, StrictNumber.parse("Infinity"), 0.0, "Infinity 文法拒");
        assertEquals(0.0, StrictNumber.parse("NaN"), 0.0, "NaN 文法拒");
        assertEquals(0.0, StrictNumber.parse(""), 0.0, "空串");
        assertEquals(0.0, StrictNumber.parse(null), 0.0, "null");
    }

    @Test
    void trimStripsOnlyAsciiControlAndSpace() {
        // #3：Java trim() 只剥 ≤U+0020；NBSP / 全角空格 / BOM 不剥 → 文法不匹配 → 0
        //（前端 [U+0000..U+0020] strip 对齐此语义，故两端同结果）
        assertEquals(0.0, StrictNumber.parse(" 5"), 0.0, "NBSP+5 不剥 → 0");
        assertEquals(0.0, StrictNumber.parse("　5"), 0.0, "全角空格+5 不剥 → 0");
        assertEquals(0.0, StrictNumber.parse("5﻿"), 0.0, "尾随 BOM 不剥 → 0");
        assertEquals(5.0, StrictNumber.parse("\t5\n"), 0.0, "ASCII 制表/换行剥 → 5");
    }

    @Test
    void clampIntBoundsToIntRange() {
        // #2：大数钳到 int 范围（不回绕）；范围内原样
        assertEquals(100, StrictNumber.clampInt(100L));
        assertEquals(-100, StrictNumber.clampInt(-100L));
        assertEquals(Integer.MAX_VALUE, StrictNumber.clampInt(3_000_000_000L),
                "3e9 → MAX（非 (int) 回绕的 -1294967296）");
        assertEquals(Integer.MIN_VALUE, StrictNumber.clampInt(-3_000_000_000L), "-3e9 → MIN");
        assertEquals(Integer.MAX_VALUE, StrictNumber.clampInt((long) Integer.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, StrictNumber.clampInt((long) Integer.MIN_VALUE));
    }
}
