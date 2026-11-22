package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EditSession} 元素域综合测试套件：
 * 路径 / 圆形 / 多边形与星形、{@link Fill} 联合类型、矢量图标、图片与蒙版（含单墙去重配额）、
 * 元素克隆、以及 StatePatch JSON Pointer 路径与广播规范化。
 */
class EditSessionElementTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static String addAndGetId(EditSession es, String type, Map<String, Object> props) {
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                es.addElement(type, props, null, null));
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    private static String addText(EditSession es, String content) {
        return addAndGetId(es, "text", Map.of("text", content));
    }

    // ──────────────────────────────────────────────────────────
    //  Path / Circle / Shape 元素创建与更新
    // ──────────────────────────────────────────────────────────

    @Nested
    class PathCircleShapeOps {

        @Test
        void addPathStraightLine() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("path", Map.of(
                    "x", 0, "y", 0, "w", 100, "h", 1,
                    "d", "M 0 0 L 100 0",
                    "stroke", Map.of("width", 2, "color", "#FF0000")
            ), null, null);
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertEquals(1, es.state().elements().size());
            PathElement p = assertInstanceOf(PathElement.class, es.state().elements().get(0));
            assertEquals("M 0 0 L 100 0", p.d());
            assertEquals(2, p.stroke().width());
            assertNotNull(ok.patch());
        }

        @Test
        void addPathWithArrowMarker() {
            EditSession es = newSession();
            assertInstanceOf(EditSession.OpResult.Ok.class, es.addElement("path", Map.of(
                    "d", "M 0 0 L 50 50",
                    "stroke", Map.of("width", 2, "color", "#000000"),
                    "markerEnd", "arrow"
            ), null, null));
            PathElement p = (PathElement) es.state().elements().get(0);
            assertEquals("arrow", p.markerEnd());
            assertNull(p.markerStart());
        }

        @Test
        void addPathInvalidDRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("path", Map.of(
                    "d", "L 10 10",
                    "stroke", Map.of("width", 1, "color", "#000000")
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addPathInvalidMarkerRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("path", Map.of(
                    "d", "M 0 0 L 10 10",
                    "stroke", Map.of("width", 1, "color", "#000000"),
                    "markerEnd", "triangle"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void pathNeedsFillOrStroke() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("path", Map.of(
                    "d", "M 0 0 L 10 10"
            ), null, null);
            assertEquals("INVALID_ELEMENT", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updatePathD() {
            EditSession es = newSession();
            String id = addAndGetId(es, "path", Map.of(
                    "d", "M 0 0 L 50 50",
                    "stroke", Map.of("width", 2, "color", "#000000")
            ));
            EditSession.OpResult r = es.updateElement(id, Map.of("d", "M 0 0 L 100 100"));
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            PathElement p = (PathElement) es.state().elements().get(0);
            assertEquals("M 0 0 L 100 100", p.d());
        }

        @Test
        void updatePathInvalidDRejected() {
            EditSession es = newSession();
            String id = addAndGetId(es, "path", Map.of(
                    "d", "M 0 0",
                    "stroke", Map.of("width", 1, "color", "#000000")
            ));
            EditSession.OpResult r = es.updateElement(id, Map.of("d", "L 10 10"));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addCircleWithFill() {
            EditSession es = newSession();
            assertInstanceOf(EditSession.OpResult.Ok.class, es.addElement("circle", Map.of(
                    "x", 10, "y", 20, "w", 50, "h", 50,
                    "fill", "#3366FF"
            ), null, null));
            CircleElement c = (CircleElement) es.state().elements().get(0);
            assertEquals(10, c.x());
            assertEquals(50, c.w());
            assertEquals(new SolidFill("#3366FF"), c.fill());
            assertNull(c.stroke());
        }

        @Test
        void addCircleStrokeOnly() {
            EditSession es = newSession();
            es.addElement("circle", Map.of(
                    "w", 32, "h", 32,
                    "stroke", Map.of("width", 1, "color", "#000000")
            ), null, null);
            CircleElement c = (CircleElement) es.state().elements().get(0);
            assertNull(c.fill());
            assertEquals(1, c.stroke().width());
        }

        @Test
        void circleNeedsFillOrStroke() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("circle", Map.of(
                    "w", 10, "h", 10
            ), null, null);
            assertEquals("INVALID_ELEMENT", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addPolygonDefaultSides() {
            EditSession es = newSession();
            es.addElement("shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "polygon",
                    "fill", "#22AA22"
            ), null, null);
            ShapeElement s = (ShapeElement) es.state().elements().get(0);
            assertEquals("polygon", s.kind());
            assertEquals(6, s.sides());
        }

        @Test
        void addStarDefaultSidesAndRatio() {
            EditSession es = newSession();
            es.addElement("shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "star",
                    "fill", "#FFAA00"
            ), null, null);
            ShapeElement s = (ShapeElement) es.state().elements().get(0);
            assertEquals("star", s.kind());
            assertEquals(5, s.sides());
            assertNull(s.innerRatio());
        }

        @Test
        void shapeKindMustBePolygonOrStar() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "ellipse",
                    "fill", "#000000"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void shapeSidesOutOfRange() {
            EditSession es = newSession();
            EditSession.OpResult r1 = es.addElement("shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "polygon", "sides", 2,
                    "fill", "#000000"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r1).code());

            EditSession.OpResult r2 = es.addElement("shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "polygon", "sides", 100,
                    "fill", "#000000"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r2).code());
        }

        @Test
        void shapeInnerRatioOutOfRange() {
            EditSession es = newSession();
            EditSession.OpResult r1 = es.addElement("shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "star", "sides", 5, "innerRatio", 0.0,
                    "fill", "#000000"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r1).code());

            EditSession.OpResult r2 = es.addElement("shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "star", "sides", 5, "innerRatio", 1.5,
                    "fill", "#000000"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r2).code());
        }

        @Test
        void updateShapeSides() {
            EditSession es = newSession();
            String id = addAndGetId(es, "shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "polygon", "sides", 6,
                    "fill", "#000000"
            ));
            es.updateElement(id, Map.of("sides", 8));
            ShapeElement s = (ShapeElement) es.state().elements().get(0);
            assertEquals(8, s.sides());
        }

        @Test
        void updateShapeUnknownFieldRejected() {
            EditSession es = newSession();
            String id = addAndGetId(es, "shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "polygon",
                    "fill", "#000000"
            ));
            EditSession.OpResult r = es.updateElement(id, Map.of("foo", "bar"));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void pathSupportsOpacityPatch() {
            EditSession es = newSession();
            String id = addAndGetId(es, "path", Map.of(
                    "d", "M 0 0 L 10 10",
                    "stroke", Map.of("width", 1, "color", "#000000")
            ));
            es.updateElement(id, Map.of("opacity", 0.5));
            PathElement p = (PathElement) es.state().elements().get(0);
            assertEquals(0.5f, p.opacity());
        }

        @Test
        void circleSupportsBlendModePatch() {
            EditSession es = newSession();
            String id = addAndGetId(es, "circle", Map.of(
                    "w", 20, "h", 20,
                    "fill", "#ff0000"
            ));
            es.updateElement(id, Map.of("blendMode", "multiply"));
            CircleElement c = (CircleElement) es.state().elements().get(0);
            assertEquals(BlendMode.MULTIPLY, c.blendMode());
        }

        @Test
        void duplicateLayerClonesPathWithNewId() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            String pathId = addAndGetId(es, "path", Map.of(
                    "d", "M 0 0 L 10 10",
                    "stroke", Map.of("width", 1, "color", "#000000")
            ));
            es.duplicateLayer(l0);
            assertEquals(2, es.state().layers().size());
            Layer copy = es.state().layers().get(1);
            assertEquals(1, copy.elements().size());
            PathElement copied = (PathElement) copy.elements().get(0);
            assertEquals("M 0 0 L 10 10", copied.d());
            assertNotEquals(pathId, copied.id());
        }

        @Test
        void duplicateLayerClonesShapeWithNewId() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            addAndGetId(es, "shape", Map.of(
                    "w", 80, "h", 80,
                    "kind", "star", "sides", 6, "innerRatio", 0.4,
                    "fill", "#FFAA00"
            ));
            es.duplicateLayer(l0);
            ShapeElement copied = (ShapeElement) es.state().layers().get(1).elements().get(0);
            assertEquals("star", copied.kind());
            assertEquals(6, copied.sides());
            assertEquals(0.4f, copied.innerRatio());
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Fill 联合类型（纯色 / 线性渐变 / 径向渐变）
    // ──────────────────────────────────────────────────────────

    @Nested
    class FillOps {

        private static String addRect(EditSession es, Object fill) {
            return addAndGetId(es, "rect", Map.of(
                    "x", 10, "y", 20, "w", 50, "h", 50,
                    "fill", fill
            ));
        }

        @Test
        void addRectWithStringFill() {
            EditSession es = newSession();
            addRect(es, "#FF6688");
            RectElement r = (RectElement) es.state().elements().get(0);
            assertNotNull(r);
            assertEquals(new SolidFill("#FF6688"), r.fill());
        }

        @Test
        void addRectWithSolidObjectFill() {
            EditSession es = newSession();
            addRect(es, Map.of("type", "solid", "color", "#22AA66"));
            RectElement r = (RectElement) es.state().elements().get(0);
            assertEquals(new SolidFill("#22AA66"), r.fill());
        }

        @Test
        void addRectWithLinearGradient() {
            EditSession es = newSession();
            addRect(es, Map.of(
                    "type", "linear",
                    "angle", 45.0,
                    "stops", List.of(
                            Map.of("position", 0.0, "color", "#FF0000"),
                            Map.of("position", 1.0, "color", "#0000FF")
                    )
            ));
            RectElement r = (RectElement) es.state().elements().get(0);
            LinearGradient lg = assertInstanceOf(LinearGradient.class, r.fill());
            assertEquals(45.0, lg.angle());
            assertEquals(2, lg.stops().size());
            assertEquals("#FF0000", lg.stops().get(0).color());
        }

        @Test
        void addRectWithRadialGradient() {
            EditSession es = newSession();
            addRect(es, Map.of(
                    "type", "radial",
                    "cx", 0.5, "cy", 0.5, "r", 1.0,
                    "stops", List.of(
                            Map.of("position", 0.0, "color", "#FFFFFF"),
                            Map.of("position", 1.0, "color", "#000000")
                    )
            ));
            RectElement r = (RectElement) es.state().elements().get(0);
            RadialGradient rg = assertInstanceOf(RadialGradient.class, r.fill());
            assertEquals(0.5, rg.cx());
            assertEquals(1.0, rg.r());
        }

        @Test
        void rejectUnknownFillType() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("rect", Map.of(
                    "x", 0, "y", 0, "w", 50, "h", 50,
                    "fill", Map.of("type", "conic", "stops", List.of(
                            Map.of("position", 0.0, "color", "#FF0000"),
                            Map.of("position", 1.0, "color", "#0000FF")))
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void rejectLinearAngleOutOfRange() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("rect", Map.of(
                    "x", 0, "y", 0, "w", 50, "h", 50,
                    "fill", Map.of("type", "linear", "angle", 400.0, "stops", List.of(
                            Map.of("position", 0.0, "color", "#FF0000"),
                            Map.of("position", 1.0, "color", "#0000FF")))
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void rejectStopsCountTooFew() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("rect", Map.of(
                    "x", 0, "y", 0, "w", 50, "h", 50,
                    "fill", Map.of("type", "linear", "angle", 0.0, "stops", List.of(
                            Map.of("position", 0.5, "color", "#FF0000")))
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateFillStringToGradient() {
            EditSession es = newSession();
            String id = addRect(es, "#FF0000");
            EditSession.OpResult r = es.updateElement(id, Map.of(
                    "fill", Map.of("type", "linear", "angle", 90.0, "stops", List.of(
                            Map.of("position", 0.0, "color", "#FF0000"),
                            Map.of("position", 1.0, "color", "#0000FF")))
            ));
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            RectElement rect = (RectElement) es.state().elements().get(0);
            assertInstanceOf(LinearGradient.class, rect.fill());
        }

        @Test
        void updateFillToNullRequiresStroke() {
            EditSession es = newSession();
            String id = addRect(es, "#FF0000");
            Map<String, Object> patch = new HashMap<>();
            patch.put("fill", null);
            EditSession.OpResult r = es.updateElement(id, patch);
            assertEquals("INVALID_ELEMENT",
                    assertInstanceOf(EditSession.OpResult.Error.class, r).code());
        }

        @Test
        void updateFillExplicitNullClearsWhenStrokeExists() {
            EditSession es = newSession();
            String id = addAndGetId(es, "rect", Map.of(
                    "x", 0, "y", 0, "w", 50, "h", 50,
                    "fill", "#FF0000",
                    "stroke", Map.of("width", 2, "color", "#000000")
            ));

            Map<String, Object> patch = new HashMap<>();
            patch.put("fill", null);
            EditSession.OpResult r = es.updateElement(id, patch);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            RectElement rect = (RectElement) es.state().elements().get(0);
            assertNull(rect.fill());
            assertNotNull(rect.stroke());
        }

        @Test
        void addCircleWithGradient() {
            EditSession es = newSession();
            es.addElement("circle", Map.of(
                    "x", 0, "y", 0, "w", 64, "h", 64,
                    "fill", Map.of("type", "linear", "angle", 90.0, "stops", List.of(
                            Map.of("position", 0.0, "color", "#FFFFFF"),
                            Map.of("position", 1.0, "color", "#000000")))
            ), null, null);
            CircleElement c = (CircleElement) es.state().elements().get(0);
            assertInstanceOf(LinearGradient.class, c.fill());
        }

        @Test
        void addShapeWithRadialGradient() {
            EditSession es = newSession();
            es.addElement("shape", Map.of(
                    "x", 0, "y", 0, "w", 80, "h", 80,
                    "kind", "star",
                    "sides", 5,
                    "fill", Map.of("type", "radial", "cx", 0.5, "cy", 0.5, "r", 1.0, "stops", List.of(
                            Map.of("position", 0.0, "color", "#FFFF00"),
                            Map.of("position", 1.0, "color", "#FF0000")))
            ), null, null);
            ShapeElement s = (ShapeElement) es.state().elements().get(0);
            assertInstanceOf(RadialGradient.class, s.fill());
        }

        @Test
        void addPathWithLinearGradient() {
            EditSession es = newSession();
            es.addElement("path", Map.of(
                    "x", 10, "y", 10, "w", 100, "h", 100,
                    "d", "M 0 0 L 100 100 Z",
                    "fill", Map.of("type", "linear", "angle", 0.0, "stops", List.of(
                            Map.of("position", 0.0, "color", "#FF0000"),
                            Map.of("position", 1.0, "color", "#00FF00")))
            ), null, null);
            PathElement p = (PathElement) es.state().elements().get(0);
            assertInstanceOf(LinearGradient.class, p.fill());
        }

        @Test
        void serializationRoundtripLinear() throws Exception {
            ObjectMapper mapper = new ObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

            LinearGradient orig = new LinearGradient(45.0,
                    List.of(new Stop(0.0, "#FF0000"), new Stop(1.0, "#0000FF")));
            String json = mapper.writeValueAsString(orig);
            assertTrue(json.contains("\"type\":\"linear\""));
            assertTrue(json.contains("\"angle\":45"));
            LinearGradient lg = assertInstanceOf(LinearGradient.class, mapper.readValue(json, Fill.class));
            assertEquals(45.0, lg.angle());
            assertEquals(2, lg.stops().size());
        }

        @Test
        void deserializationStringYieldsSolid() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            SolidFill back = assertInstanceOf(SolidFill.class, mapper.readValue("\"#FF6688\"", Fill.class));
            assertEquals("#FF6688", back.color());
        }
    }

    // ──────────────────────────────────────────────────────────
    //  IconElement 矢量图标创建与 tint 兼容升级
    // ──────────────────────────────────────────────────────────

    @Nested
    class IconOps {

        @Test
        void addIconWithFill() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("icon", Map.of(
                    "x", 10, "y", 20, "w", 32, "h", 32,
                    "source", "fa-solid/heart",
                    "fill", Map.of("type", "solid", "color", "#FF0000")
            ), null, null);
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertNotNull(ok.patch());
            assertEquals(1, es.state().elements().size());
            IconElement ic = (IconElement) es.state().elements().get(0);
            assertEquals("fa-solid/heart", ic.source());
            assertNull(ic.tint());
            assertInstanceOf(SolidFill.class, ic.fill());
            assertEquals("#FF0000", ((SolidFill) ic.fill()).color());
        }

        @Test
        void addIconWithLegacyTintUpgradesToSolidFill() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("icon", Map.of(
                    "source", "fa-solid/star",
                    "tint", "#00FF00"
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            IconElement ic = (IconElement) es.state().elements().get(0);
            assertEquals("#00FF00", ic.tint());
            assertInstanceOf(SolidFill.class, ic.fill());
            assertEquals("#00FF00", ((SolidFill) ic.fill()).color());
        }

        @Test
        void addIconFillBeatsTintWhenBothPresent() {
            EditSession es = newSession();
            Map<String, Object> p = new HashMap<>();
            p.put("source", "fa-solid/heart");
            p.put("tint", "#000000");
            p.put("fill", Map.of("type", "solid", "color", "#ABCDEF"));
            EditSession.OpResult r = es.addElement("icon", p, null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            IconElement ic = (IconElement) es.state().elements().get(0);
            assertEquals("#000000", ic.tint());
            assertEquals("#ABCDEF", ((SolidFill) ic.fill()).color());
        }

        @Test
        void addIconWithoutFillOrTintHasNullFill() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("icon", Map.of(
                    "source", "fa-solid/cog"
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            IconElement ic = (IconElement) es.state().elements().get(0);
            assertNull(ic.fill());
            assertNull(ic.tint());
        }

        @Test
        void addIconBareSourceWithoutPackRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("icon", Map.of(
                    "source", "heart"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addIconInvalidSourceRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("icon", Map.of(
                    "source", "FA-Solid/Heart"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addIconMissingSourceRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("icon", Map.of(
                    "x", 0, "y", 0
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addIconWithDottedIconNameAccepted() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("icon", Map.of(
                    "source", "user/star.fill"
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            IconElement ic = (IconElement) es.state().elements().get(0);
            assertEquals("user/star.fill", ic.source());
        }
    }

    // ──────────────────────────────────────────────────────────
    //  ImageElement + Mask + 单墙图片配额
    // ──────────────────────────────────────────────────────────

    @Nested
    class ImageAndMaskOps {

        private static final String VALID_SOURCE = "9f3a2b7e4c1d0a5f";

        private static String addImage(EditSession es, Map<String, Object> props) {
            return addAndGetId(es, "image", props);
        }

        private static String addImageWithSource(EditSession es, String source) {
            return addImage(es, Map.of("source", source, "w", 32, "h", 32));
        }

        @Test
        void addImageMinimal() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "x", 10, "y", 20, "w", 64, "h", 48,
                    "source", VALID_SOURCE
            ), null, null);
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
            ImageElement im = (ImageElement) es.state().elements().get(0);
            assertEquals(10, im.x());
            assertEquals(48, im.h());
            assertEquals(VALID_SOURCE, im.source());
            assertNull(im.mask());
            assertTrue(im.visible());
            assertNotNull(ok.patch());
        }

        @Test
        void addImageMissingSourceRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of("w", 64, "h", 64), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageSourceTooShortRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", "9f3a2b7e"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageSourceUppercaseRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", "9F3A2B7E4C1D0A5F"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageSourceNonHexRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", "9f3a2b7e4c1d0a5g"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageWithMask() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "w", 100, "h", 100,
                    "mask", Map.of(
                            "d", "M 0 0 L 100 0 L 100 100 L 0 100 Z",
                            "inverted", false
                    )
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            Mask m = ((ImageElement) es.state().elements().get(0)).mask();
            assertNotNull(m);
            assertEquals("M 0 0 L 100 0 L 100 100 L 0 100 Z", m.d());
            assertFalse(m.inverted());
        }

        @Test
        void addImageMaskInvertedDefaultsFalseWhenOmitted() {
            EditSession es = newSession();
            Map<String, Object> mask = new HashMap<>();
            mask.put("d", "M 0 0 L 50 50");
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE, "mask", mask
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertFalse(((ImageElement) es.state().elements().get(0)).mask().inverted());
        }

        @Test
        void addImageMaskInvertedTrue() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "M 0 0 L 10 0", "inverted", true)
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertTrue(((ImageElement) es.state().elements().get(0)).mask().inverted());
        }

        @Test
        void addImageMaskNotObjectRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", "circle"
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageMaskDNotStringRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", 123, "inverted", false)
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageMaskDInvalidPathRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "L 10 10", "inverted", false)
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageMaskDTooLongRejected() {
            EditSession es = newSession();
            StringBuilder sb = new StringBuilder("M 0 0 ");
            while (sb.length() <= 4096) sb.append("L 1 1 ");
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", sb.toString(), "inverted", false)
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageMaskInvertedNotBooleanRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "M 0 0 L 10 10", "inverted", "yes")
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageMaskWithFeatherPx() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "w", 64, "h", 64,
                    "mask", Map.of(
                            "d", "M 0 0 L 64 0 L 64 64 L 0 64 Z",
                            "inverted", false,
                            "featherPx", 8
                    )
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            Mask m = ((ImageElement) es.state().elements().get(0)).mask();
            assertNotNull(m);
            assertEquals(Integer.valueOf(8), m.featherPx());
            assertTrue(m.hasFeather());
            assertEquals(8, m.featherPxOrZero());
        }

        @Test
        void addImageMaskFeatherPxZeroNormalizedToNull() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", 0)
            ), null, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            Mask m = ((ImageElement) es.state().elements().get(0)).mask();
            assertNotNull(m);
            assertNull(m.featherPx());
            assertFalse(m.hasFeather());
        }

        @Test
        void addImageMaskFeatherPxOutOfRangeRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", 100)
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageMaskFeatherPxNegativeRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", -5)
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void addImageMaskFeatherPxNonNumberRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("image", Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", "soft")
            ), null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateImageSource() {
            EditSession es = newSession();
            String id = addImage(es, Map.of("source", VALID_SOURCE));
            String newHash = "ffffffffffffffff";
            EditSession.OpResult r = es.updateElement(id, Map.of("source", newHash));
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertEquals(newHash, ((ImageElement) es.state().elements().get(0)).source());
        }

        @Test
        void updateImageSourceInvalidRejected() {
            EditSession es = newSession();
            String id = addImage(es, Map.of("source", VALID_SOURCE));
            EditSession.OpResult r = es.updateElement(id, Map.of("source", "nope"));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateImageSetMask() {
            EditSession es = newSession();
            String id = addImage(es, Map.of("source", VALID_SOURCE, "w", 64, "h", 64));
            EditSession.OpResult r = es.updateElement(id, Map.of(
                    "mask", Map.of("d", "M 0 0 L 64 64", "inverted", true)
            ));
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            Mask m = ((ImageElement) es.state().elements().get(0)).mask();
            assertNotNull(m);
            assertTrue(m.inverted());
        }

        @Test
        void updateImageClearMask() {
            EditSession es = newSession();
            String id = addImage(es, Map.of(
                    "source", VALID_SOURCE,
                    "mask", Map.of("d", "M 0 0 L 10 0", "inverted", false)
            ));
            Map<String, Object> patch = new HashMap<>();
            patch.put("mask", null);
            EditSession.OpResult r = es.updateElement(id, patch);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertNull(((ImageElement) es.state().elements().get(0)).mask());
        }

        @Test
        void updateImageMaskInvalidPathRejected() {
            EditSession es = newSession();
            String id = addImage(es, Map.of("source", VALID_SOURCE));
            EditSession.OpResult r = es.updateElement(id, Map.of(
                    "mask", Map.of("d", "garbage", "inverted", false)
            ));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateImageUnknownFieldRejected() {
            EditSession es = newSession();
            String id = addImage(es, Map.of("source", VALID_SOURCE));
            EditSession.OpResult r = es.updateElement(id, Map.of("foo", "bar"));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateImageOpacityBlendRenderMode() {
            EditSession es = newSession();
            String id = addImage(es, Map.of("source", VALID_SOURCE));
            EditSession.OpResult r = es.updateElement(id, Map.of(
                    "opacity", 0.5,
                    "blendMode", "multiply",
                    "renderMode", "dither"
            ));
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            ImageElement im = (ImageElement) es.state().elements().get(0);
            assertEquals(0.5f, im.opacity(), 1e-6f);
            assertEquals(BlendMode.MULTIPLY, im.blendMode());
            assertEquals(RenderMode.DITHER, im.renderMode());
        }

        @Test
        void updateImageGeometry() {
            EditSession es = newSession();
            String id = addImage(es, Map.of("source", VALID_SOURCE));
            EditSession.OpResult r = es.updateElement(id, Map.of(
                    "x", 100, "y", 200, "w", 256, "h", 128, "rotation", 45
            ));
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            ImageElement im = (ImageElement) es.state().elements().get(0);
            assertEquals(100, im.x());
            assertEquals(256, im.w());
            assertEquals(45, im.rotation());
        }

        @Test
        void elementUpdate_changingImageSource_isCountedAgainstPerWallQuota() {
            EditSession es = newSession();
            es.setMaxImagesPerWall(2);
            addImageWithSource(es, "a".repeat(16));
            addImageWithSource(es, "b".repeat(16));
            String third = addImageWithSource(es, "a".repeat(16));

            EditSession.OpResult r = es.updateElement(third, Map.of("source", "c".repeat(16)));
            EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r,
                    "换成第三个 hash 会让去重数超过上限，应被单墙配额拒绝，实际 " + r);
            assertEquals("QUOTA_PER_WALL", err.code());
        }

        @Test
        void elementUpdate_reusingExistingSource_isAllowedAtQuota() {
            EditSession es = newSession();
            es.setMaxImagesPerWall(2);
            String first = addImageWithSource(es, "a".repeat(16));
            addImageWithSource(es, "b".repeat(16));

            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.updateElement(first, Map.of("source", "b".repeat(16))),
                    "复用已引用文件不增加去重数，不该被拒");
        }

        @Test
        void elementUpdate_swappingSoleReference_isAllowedAtQuota() {
            EditSession es = newSession();
            es.setMaxImagesPerWall(1);
            String only = addImageWithSource(es, "a".repeat(16));
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.updateElement(only, Map.of("source", "d".repeat(16))),
                    "旧 source 换掉后无人引用，去重总数不变，不该被拒");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  元素克隆（cloneElement + 单墙总元素配额）
    // ──────────────────────────────────────────────────────────

    @Nested
    class CloneElementOps {

        private static String addTextAt(EditSession es, int x, int y) {
            return addAndGetId(es, "text", Map.of("text", "hi", "x", x, "y", y));
        }

        private static String addedId(EditSession.OpResult.Ok ok) {
            return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
        }

        private static Element find(EditSession es, String id) {
            for (Layer l : es.state().layers()) {
                for (Element e : l.elements()) {
                    if (e.id().equals(id)) return e;
                }
            }
            return null;
        }

        @Test
        void clone_addsElement_withNewId_andOffset() {
            EditSession es = newSession();
            String srcId = addTextAt(es, 10, 20);
            int before = es.state().layers().get(0).elements().size();

            EditSession.OpResult r = es.cloneElement(srcId, 5, 7);
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
            String newId = addedId(ok);

            assertEquals(before + 1, es.state().layers().get(0).elements().size(), "元素 +1");
            assertNotEquals(srcId, newId, "副本 id 不同");
            Element copy = find(es, newId);
            assertEquals(15, copy.x(), "x 偏移生效");
            assertEquals(27, copy.y(), "y 偏移生效");
            assertEquals(10, find(es, srcId).x());
            assertEquals(20, find(es, srcId).y());
        }

        @Test
        void clone_zeroOffset_copiesAtSamePosition() {
            EditSession es = newSession();
            String srcId = addTextAt(es, 30, 40);
            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.cloneElement(srcId, 0, 0);
            Element copy = find(es, addedId(ok));
            assertEquals(30, copy.x());
            assertEquals(40, copy.y());
        }

        @Test
        void clone_appendsToOwningLayer_notActiveLayer() {
            EditSession es = newSession();
            String srcId = addTextAt(es, 0, 0);
            es.createLayer("Top", null);
            es.cloneElement(srcId, 1, 1);
            assertEquals(2, es.state().layers().get(0).elements().size(), "副本进底层");
            assertEquals(0, es.state().layers().get(1).elements().size(), "Top 层不受影响");
        }

        @Test
        void clone_missingElement_error() {
            EditSession es = newSession();
            EditSession.OpResult r = es.cloneElement("e-nope", 1, 1);
            assertEquals("INVALID_ELEMENT", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void clone_nullElement_error() {
            EditSession es = newSession();
            EditSession.OpResult r = es.cloneElement(null, 1, 1);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void clone_lockedLayer_error() {
            EditSession es = newSession();
            String srcId = addTextAt(es, 0, 0);
            String layerId = es.state().layers().get(0).id();
            es.updateLayer(layerId, Map.of("locked", true));
            EditSession.OpResult r = es.cloneElement(srcId, 1, 1);
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void clone_quotaExceeded_error() {
            EditSession es = newSession();
            es.setMaxElementsPerWall(2);
            addTextAt(es, 0, 0);
            String srcId = addTextAt(es, 1, 1);
            EditSession.OpResult r = es.cloneElement(srcId, 1, 1);
            assertEquals("QUOTA_EXCEEDED", ((EditSession.OpResult.Error) r).code());
            assertEquals(2, es.state().layers().get(0).elements().size(), "拒绝后元素数不变");
        }

        @Test
        void clone_quotaNotSet_allowsUnbounded() {
            EditSession es = newSession();
            String srcId = addTextAt(es, 0, 0);
            for (int i = 0; i < 5; i++) {
                assertInstanceOf(EditSession.OpResult.Ok.class, es.cloneElement(srcId, i, i));
            }
            assertEquals(6, es.state().layers().get(0).elements().size());
        }

        @Test
        void clone_offsetOverflow_clampsToBounds() {
            EditSession es = newSession();
            String srcId = addTextAt(es, 9999, 0);
            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.cloneElement(srcId, 100, 0);
            assertEquals(10_000, find(es, addedId(ok)).x(), "x 钳到 MAX_COORD");
        }

        @Test
        void clone_undo_restoresPreClone() {
            EditSession es = newSession();
            String srcId = addTextAt(es, 0, 0);
            es.cloneElement(srcId, 1, 1);
            assertEquals(2, es.state().layers().get(0).elements().size());
            es.undo();
            assertEquals(1, es.state().layers().get(0).elements().size(), "undo 回到克隆前");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  StatePatch 路径契约、规范化广播与 fontId 白名单
    // ──────────────────────────────────────────────────────────

    @Nested
    class PatchPathsAndNormalization {

        private static String activeLayerId(EditSession es) {
            return es.state().activeLayerId();
        }

        @Test
        void elementAddEmitsV2Path() {
            EditSession es = newSession();
            EditSession.OpResult r = es.addElement("text", Map.of("text", "hi"), null, null);
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);

            assertEquals(1, ok.patch().ops().size());
            PatchOp p = ok.patch().ops().get(0);
            assertEquals("add", p.op());
            assertEquals("/layers/0/elements/0", p.path());
        }

        @Test
        void elementUpdateEmitsV2Path() {
            EditSession es = newSession();
            String eid = addText(es, "hi");

            EditSession.OpResult r = es.updateElement(eid, Map.of("text", "ho", "x", 30));
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertEquals(2, ok.patch().ops().size());
            for (PatchOp p : ok.patch().ops()) {
                assertTrue(p.path().startsWith("/layers/0/elements/0/"),
                        "unexpected path: " + p.path());
            }
        }

        @Test
        void elementDeleteEmitsV2Path() {
            EditSession es = newSession();
            String eid = addText(es, "hi");

            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.deleteElement(eid);
            assertEquals("remove", r.patch().ops().get(0).op());
            assertEquals("/layers/0/elements/0", r.patch().ops().get(0).path());
        }

        @Test
        void elementReorderEmitsV2RemoveAddPath() {
            EditSession es = newSession();
            addText(es, "a");
            String bid = addText(es, "b");

            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.reorderElement(bid, 0);
            assertEquals(2, r.patch().ops().size());
            assertEquals("/layers/0/elements/1", r.patch().ops().get(0).path());
            assertEquals("/layers/0/elements/0", r.patch().ops().get(1).path());
        }

        @Test
        void canvasBackgroundPathStaysV2() {
            EditSession es = newSession();
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setBackground("#123456");
            assertEquals("/canvas/background", r.patch().ops().get(0).path());
        }

        @Test
        void canvasGridEmitsCanvasGridSizePath() {
            EditSession es = newSession();
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setGridSize(16);
            assertEquals("/canvas/gridSize", r.patch().ops().get(0).path());
            assertEquals(16, r.patch().ops().get(0).value());
        }

        @Test
        void canvasGridZeroNormalizesToNull() {
            EditSession es = newSession();
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setGridSize(0);
            assertEquals("/canvas/gridSize", r.patch().ops().get(0).path());
            assertNull(r.patch().ops().get(0).value());
        }

        @Test
        void canvasGuidesEmitsCanvasGuidesPath() {
            EditSession es = newSession();
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setGuides(List.of(
                    Map.of("axis", "x", "position", 100),
                    Map.of("axis", "y", "position", 50)));
            assertEquals("/canvas/guides", r.patch().ops().get(0).path());
            Object v = r.patch().ops().get(0).value();
            assertInstanceOf(List.class, v);
            assertEquals(2, ((List<?>) v).size());
        }

        @Test
        void layerCreateEmitsLayersPath() {
            EditSession es = newSession();
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.createLayer("UI", null);
            assertEquals(1, r.patch().ops().size());
            PatchOp p = r.patch().ops().get(0);
            assertEquals("add", p.op());
            assertEquals("/layers/1", p.path());
            assertNotNull(p.value());
        }

        @Test
        void layerUpdateEmitsLayerFieldPath() {
            EditSession es = newSession();
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok)
                    es.updateLayer(activeLayerId(es), Map.of("name", "Background", "visible", false));
            assertEquals(2, r.patch().ops().size());
            for (PatchOp p : r.patch().ops()) {
                assertTrue(p.path().startsWith("/layers/0/"),
                        "unexpected path: " + p.path());
            }
        }

        @Test
        void layerSetActiveEmitsActiveLayerIdPath() {
            EditSession es = newSession();
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            Map<?, ?> newLayerVal = (Map<?, ?>) created.patch().ops().get(0).value();
            String newLid = newLayerVal.get("id").toString();

            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setActiveLayer(newLid);
            assertEquals(1, r.patch().ops().size());
            PatchOp p = r.patch().ops().get(0);
            assertEquals("replace", p.op());
            assertEquals("/activeLayerId", p.path());
            assertEquals(newLid, p.value());
        }

        @Test
        void elementMoveToLayerEmitsCrossLayerRemoveAdd() {
            EditSession es = newSession();
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topLid = ((Map<?, ?>) created.patch().ops().get(0).value()).get("id").toString();

            String eid = addText(es, "hi");

            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok)
                    es.moveElementToLayer(eid, topLid, null);
            assertEquals(2, r.patch().ops().size());
            assertEquals("remove", r.patch().ops().get(0).op());
            assertEquals("/layers/0/elements/0", r.patch().ops().get(0).path());
            assertEquals("add", r.patch().ops().get(1).op());
            assertEquals("/layers/1/elements/0", r.patch().ops().get(1).path());
        }

        @Test
        void elementUpdate_broadcastsNormalizedValues_notClientRaw() {
            EditSession es = newSession();
            String id = addText(es, "hi");

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("x", 12.9);
            patch.put("blendMode", "MULTIPLY");
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.updateElement(id, patch));

            Map<String, Object> byField = new LinkedHashMap<>();
            for (PatchOp op : ok.patch().ops()) {
                byField.put(op.path().substring(op.path().lastIndexOf('/') + 1), op.value());
            }
            assertEquals(12, ((Number) byField.get("x")).intValue(),
                    "x 应广播服务端截断后的 12，而非客户端原值 12.9");
            assertEquals("multiply", byField.get("blendMode"),
                    "blendMode 应广播小写归一后的 wire 值");
        }

        @Test
        void fontId_rejectsIllegalCharset_onCreateAndUpdate() {
            EditSession es = newSession();

            EditSession.OpResult bad = es.addElement("text",
                    Map.of("text", "hi", "fontId", "../../etc/passwd"), null, null);
            assertEquals("INVALID_PAYLOAD",
                    assertInstanceOf(EditSession.OpResult.Error.class, bad).code());

            String id = addText(es, "hi");
            EditSession.OpResult badPatch = es.updateElement(id, Map.of("fontId", "字体 name!"));
            assertEquals("INVALID_PAYLOAD",
                    assertInstanceOf(EditSession.OpResult.Error.class, badPatch).code());

            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.updateElement(id, Map.of("fontId", "source_han_sans")),
                    "合法 id 应照常通过");
        }

        @Test
        void fontId_rejectsOverlongId() {
            EditSession es = newSession();
            EditSession.OpResult bad = es.addElement("text",
                    Map.of("text", "hi", "fontId", "a".repeat(65)), null, null);
            assertEquals("INVALID_PAYLOAD",
                    assertInstanceOf(EditSession.OpResult.Error.class, bad).code());
        }
    }
}
