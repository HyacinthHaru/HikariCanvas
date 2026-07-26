package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ElementValidator#validateElementForTemplateApply} 的导入路径校验守卫。
 *
 * <p>该方法是 <b>.canvas 导入与模板套用的唯一元素校验点</b>（{@code ProjectMaterializer}
 * 自述「不信任任何元素数值」）。此前它的 if-chain 覆盖 Path / Brush / Shape / Rect / Circle /
 * Image / Icon 七个分支，<b>唯独缺 TextElement</b>，Brush 也只查 {@code points != null}，
 * effects 完全不校验。于是 WS 实时编辑路径校验齐全（text≤256、fontSize 1..512、
 * brush size 1..64、glow radius 0..64）而导入路径全部豁免——导入成了绕过校验的后门。</p>
 *
 * <p>可达后果：{@code glow.radius = 2000 万} → GlowRenderer 按 bbox 申请数 GB buffer →
 * OutOfMemoryError；{@code brush.size} 为负 → BasicStroke 构造抛异常 → 该 wall 渲染
 * 永久失败循环。</p>
 */
class ImportPathElementValidationTest {

    private static TextElement text(String content, int fontSize, String color, Effects fx) {
        return new TextElement("e-1", 10, 20, 100, 50, 0, false, true,
                content, "inter", fontSize, color, "left", 0f, 1.2f, false, fx,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
    }

    private static BrushStrokeElement brush(int size, int pointCount) {
        List<BrushPoint> pts = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) pts.add(new BrushPoint(i, i, 1.0));
        return new BrushStrokeElement("b-1", 0, 0, 50, 50, 0, false, true,
                pts, size, null, false, false, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
    }

    // ---------- TextElement 分支（此前整个缺席） ----------

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

    // ---------- effects（此前完全不校验） ----------

    /** 本批修复的招牌用例：超大 glow radius 会让渲染器申请数 GB buffer。 */
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

    // ---------- Brush 内容（此前只查 points != null） ----------

    @Test
    void brush_validStroke_passes() {
        assertDoesNotThrow(() -> ElementValidator.validateElementForTemplateApply(brush(8, 10)));
    }

    @Test
    void brush_negativeSize_rejected() {
        assertThrows(ValidationException.class,
                () -> ElementValidator.validateElementForTemplateApply(brush(-5, 10)),
                "size 为负会让 BasicStroke 构造抛异常 → 该 wall 渲染永久失败循环");
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
