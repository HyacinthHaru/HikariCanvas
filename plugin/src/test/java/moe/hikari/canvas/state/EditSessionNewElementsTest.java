package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M9-A：通过 EditSession 创建 / 更新 PathElement / CircleElement / ShapeElement 行为校验。
 *
 * <p>核心断言：</p>
 * <ul>
 *   <li>合法 element.add 成功 → 落到 activeLayer + 类型字段正确</li>
 *   <li>非法字段（path.d / shape.kind / shape.sides / marker / innerRatio）拒</li>
 *   <li>element.update 字段白名单</li>
 *   <li>fill + stroke 兜底（至少一个非空非零）</li>
 * </ul>
 */
class EditSessionNewElementsTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static String addAndGetId(EditSession es, String type, Map<String, Object> props) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement(type, props, null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    // ---------- path ----------

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
        Element e = es.state().elements().get(0);
        assertInstanceOf(PathElement.class, e);
        PathElement p = (PathElement) e;
        assertEquals("M 0 0 L 100 0", p.d());
        assertEquals(2, p.stroke().width());
        assertNotNull(ok.patch());
    }

    @Test
    void addPathWithArrowMarker() {
        EditSession es = newSession();
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.addElement("path", Map.of(
                "d", "M 0 0 L 50 50",
                "stroke", Map.of("width", 2, "color", "#000000"),
                "markerEnd", "arrow"
        ), null, null);
        PathElement p = (PathElement) es.state().elements().get(0);
        assertEquals("arrow", p.markerEnd());
        assertNull(p.markerStart());
    }

    @Test
    void addPathInvalidDRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("path", Map.of(
                "d", "L 10 10",  // 不以 M 开头
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
                "markerEnd", "triangle"  // 非 'arrow' / 'dot'
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void pathNeedsFillOrStroke() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("path", Map.of(
                "d", "M 0 0 L 10 10"  // 既无 fill 也无 stroke
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

    // ---------- circle ----------

    @Test
    void addCircleWithFill() {
        EditSession es = newSession();
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.addElement("circle", Map.of(
                "x", 10, "y", 20, "w", 50, "h", 50,
                "fill", "#3366FF"
        ), null, null);
        CircleElement c = (CircleElement) es.state().elements().get(0);
        assertEquals(10, c.x());
        assertEquals(50, c.w());
        assertEquals("#3366FF", c.fill());
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

    // ---------- shape ----------

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
        assertEquals(6, s.sides());  // polygon 默认 6
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
        assertEquals(5, s.sides());  // star 默认 5
        assertNull(s.innerRatio());  // null = 用默认 0.5
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

    // ---------- v2 fields cross-element ----------

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

    // ---------- layer.duplicate clone ----------

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
        // 新 id
        assert !copied.id().equals(pathId);
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
