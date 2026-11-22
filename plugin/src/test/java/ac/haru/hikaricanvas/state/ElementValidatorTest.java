package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ElementValidator} 校验规则单测（fillRule 解析、mask 坐标边界、导入与模板套用元素校验）。
 */
class ElementValidatorTest {

    @Nested
    class FillRuleParsing {

        @Test
        void parseFillRule_acceptsNonzeroEvenoddNull() {
            assertEquals("nonzero", ElementValidator.parseFillRuleNullable("nonzero"));
            assertEquals("evenodd", ElementValidator.parseFillRuleNullable("evenodd"));
            assertNull(ElementValidator.parseFillRuleNullable(null));
        }

        @Test
        void parseFillRule_rejectsGarbage() {
            assertThrows(RuntimeException.class, () -> ElementValidator.parseFillRuleNullable("winding"));
        }
    }

    /**
     * mask path 坐标越界检查（含科学计数法防绕过）：{@code MASK_NUMBER_RE} 将科学计数法当作单个数字解析，
     * 防止 {@code 1e5} 被拆成两个短整数 token 绕过 {@code MAX_COORD}。
     */
    @Nested
    class MaskPathBounds {

        @Test
        void acceptsCoordAtMaxBoundary() {
            assertDoesNotThrow(() -> ElementValidator.validateMaskPathBounds("M 0 0 L 10000 0"));
        }

        @Test
        void rejectsCoordOverMax() {
            assertThrows(RuntimeException.class,
                    () -> ElementValidator.validateMaskPathBounds("M 0 0 L 10001 0"));
        }

        @Test
        void scientificNotationParsedAsSingleNumberAtBoundary() {
            assertDoesNotThrow(() -> ElementValidator.validateMaskPathBounds("M 0 0 L 1e4 0"));
        }

        @Test
        void scientificNotationOverMaxRejected() {
            assertThrows(RuntimeException.class,
                    () -> ElementValidator.validateMaskPathBounds("M 0 0 L 1e5 0"));
        }

        @Test
        void negativeScientificNotationOverMaxRejected() {
            assertThrows(RuntimeException.class,
                    () -> ElementValidator.validateMaskPathBounds("M 0 0 L -1.5e4 0"));
        }
    }

    /**
     * {@link ElementValidator#validateElementForTemplateApply} 的导入路径校验守卫（覆盖 TextElement、
     * Effects 与 BrushStrokeElement 数值及配额边界）。
     */
    @Nested
    class ImportAndTemplateApplyValidation {

        private TextElement text(String content, int fontSize, String color, Effects fx) {
            return new TextElement("e-1", 10, 20, 100, 50, 0, false, true,
                    content, "inter", fontSize, color, "left", 0f, 1.2f, false, fx,
                    1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        }

        private BrushStrokeElement brush(int size, int pointCount) {
            List<BrushPoint> pts = new ArrayList<>(pointCount);
            for (int i = 0; i < pointCount; i++) pts.add(new BrushPoint(i, i, 1.0));
            return new BrushStrokeElement("b-1", 0, 0, 50, 50, 0, false, true,
                    pts, size, null, false, false, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        }

        @Test
        void text_validElement_passes() {
            assertDoesNotThrow(() ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "#000000", null)));
        }

        @Test
        void text_overlongText_rejected() {
            String tooLong = "x".repeat(ElementValidator.MAX_TEXT_LEN + 1);
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text(tooLong, 24, "#000000", null)));
        }

        @Test
        void text_fontSizeOutOfRange_rejected() {
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(
                            text("hi", ElementValidator.MAX_FONT_SIZE + 1, "#000000", null)));
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 0, "#000000", null)));
        }

        @Test
        void text_badColor_rejected() {
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "red", null)));
        }

        @Test
        void effects_absurdGlowRadius_rejected() {
            Effects fx = new Effects(null, null, new Glow(20_000_000, "#FFFFFF"));
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "#000000", fx)),
                    "glow.radius 必须钳在 0.." + ElementValidator.MAX_GLOW_RADIUS);
        }

        @Test
        void effects_negativeGlowRadius_rejected() {
            Effects fx = new Effects(null, null, new Glow(-1, "#FFFFFF"));
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "#000000", fx)));
        }

        @Test
        void effects_strokeWidthOutOfRange_rejected() {
            Effects fx = new Effects(
                    new Stroke(ElementValidator.MAX_STROKE_WIDTH + 1, "#FFFFFF"), null, null);
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "#000000", fx)));
        }

        @Test
        void effects_shadowOffsetOutOfRange_rejected() {
            Effects fx = new Effects(null,
                    new Shadow(ElementValidator.MAX_SHADOW_OFFSET + 1, 0, "#FFFFFF"), null);
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "#000000", fx)));
        }

        @Test
        void effects_badEffectColor_rejected() {
            Effects fx = new Effects(null, null, new Glow(8, "not-a-color"));
            assertThrows(ValidationException.class, () ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "#000000", fx)));
        }

        @Test
        void effects_validEffects_pass() {
            Effects fx = new Effects(new Stroke(2, "#FFFFFF"),
                    new Shadow(2, 2, "#333333"), new Glow(8, "#FF0000"));
            assertDoesNotThrow(() ->
                    ElementValidator.validateElementForTemplateApply(text("hi", 24, "#000000", fx)));
        }

        @Test
        void brush_validStroke_passes() {
            assertDoesNotThrow(() -> ElementValidator.validateElementForTemplateApply(brush(8, 10)));
        }

        @Test
        void brush_negativeSize_rejected() {
            assertThrows(ValidationException.class,
                    () -> ElementValidator.validateElementForTemplateApply(brush(-5, 10)),
                    "size 为负会让 BasicStroke 构造抛异常");
        }

        @Test
        void brush_oversizeSize_rejected() {
            assertThrows(ValidationException.class, () -> ElementValidator
                    .validateElementForTemplateApply(brush(ElementValidator.MAX_BRUSH_SIZE + 1, 10)));
        }

        @Test
        void brush_tooManyPoints_rejected() {
            assertThrows(ValidationException.class, () -> ElementValidator
                    .validateElementForTemplateApply(
                            brush(8, BrushSession.MAX_BRUSH_POINTS_PER_STROKE + 1)));
        }

        @Test
        void brush_nullPoints_rejected() {
            BrushStrokeElement b = new BrushStrokeElement("b-1", 0, 0, 50, 50, 0, false, true,
                    null, 8, null, false, false, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
            assertThrows(ValidationException.class,
                    () -> ElementValidator.validateElementForTemplateApply(b));
        }
    }
}
