package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R6：mask path 坐标越界检查（含科学计数法防绕过）。
 *
 * <p>{@code MASK_NUMBER_RE} 修复前 {@code -?\d+(?:\.\d+)?} 会把 "1e5" 拆成 "1"/"5" 两个 token，
 * 二者都 ≤ {@code MAX_COORD} 从而绕过越界检查；修复后科学计数被当作单个数字解析。</p>
 */
class ElementValidatorMaskBoundsTest {

    @Test
    void acceptsCoordAtMaxBoundary() {
        // 10000 = MAX_COORD，恰在边界（> 才拒）。
        assertDoesNotThrow(() -> ElementValidator.validateMaskPathBounds("M 0 0 L 10000 0"));
    }

    @Test
    void rejectsCoordOverMax() {
        assertThrows(RuntimeException.class,
                () -> ElementValidator.validateMaskPathBounds("M 0 0 L 10001 0"));
    }

    @Test
    void scientificNotationParsedAsSingleNumberAtBoundary() {
        // 1e4 = 10000，应作为单个数字解析并通过（不被拆成 1 和 4）。
        assertDoesNotThrow(() -> ElementValidator.validateMaskPathBounds("M 0 0 L 1e4 0"));
    }

    @Test
    void scientificNotationOverMaxRejected() {
        // 修复前 bug：1e5 被拆成 "1"/"5"（都 ≤10000）绕过检查；修复后 = 100000 > MAX_COORD 应拒。
        assertThrows(RuntimeException.class,
                () -> ElementValidator.validateMaskPathBounds("M 0 0 L 1e5 0"));
    }

    @Test
    void negativeScientificNotationOverMaxRejected() {
        // -1.5e4 = -15000，abs > MAX_COORD 应拒。
        assertThrows(RuntimeException.class,
                () -> ElementValidator.validateMaskPathBounds("M 0 0 L -1.5e4 0"));
    }
}
