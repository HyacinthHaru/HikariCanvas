package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.CircleElement;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.PathElement;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.ShapeElement;
import ac.haru.hikaricanvas.state.Stroke;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脏区必须覆盖描边真正画到的像素。
 *
 * <p>圆 / 多边形 / path 用 {@code BasicStroke}，描边以路径为中心两侧各分一半，向 bbox 外溢出
 * width/2。脏区不外扩的话，元素移动 / 删除后溢进相邻 map 的旧描边不会被重画，残影留在游戏内地图上
 * 直到某次全量重绘（前端每帧全量重画看不到，双端于是不一致）。矩形用 fillRect 画在 bbox 内，不扩。</p>
 */
class DirtyRegionOutsetTest {

    private static Stroke stroke(int width) {
        return new Stroke(width, "#000000");
    }

    private static CircleElement circle(int x, int y, int w, int h, Stroke s) {
        return new CircleElement("c-1", x, y, w, h, 0, false, true,
                Fill.solid("#FFFFFF"), s, null, BlendMode.NORMAL, RenderMode.CLEAN);
    }

    private static RectElement rect(int x, int y, int w, int h, Stroke s) {
        return new RectElement("r-1", x, y, w, h, 0, false, true,
                Fill.solid("#FFFFFF"), s, null, BlendMode.NORMAL, RenderMode.CLEAN);
    }

    private static ShapeElement shape(int x, int y, int w, int h, Stroke s) {
        return new ShapeElement("s-1", x, y, w, h, 0, false, true, "polygon", 5, null,
                Fill.solid("#FFFFFF"), s, null, BlendMode.NORMAL, RenderMode.CLEAN);
    }

    private static PathElement path(int x, int y, int w, int h, Stroke s,
                                    String markerStart, String markerEnd) {
        return new PathElement("p-1", x, y, w, h, 0, false, true, "M0 0 L10 10",
                null, s, markerStart, markerEnd, null, BlendMode.NORMAL, RenderMode.CLEAN, null);
    }

    @Test
    void circleStroke_outsetsHalfWidth() {
        DirtyRegion r = DirtyRegion.of(circle(50, 50, 40, 40, stroke(8)));
        assertEquals(new DirtyRegion(46, 46, 48, 48), r, "8px 描边向外溢 4px");
    }

    @Test
    void oddStrokeWidth_roundsUp() {
        DirtyRegion r = DirtyRegion.of(circle(50, 50, 40, 40, stroke(5)));
        assertEquals(new DirtyRegion(47, 47, 46, 46), r, "5px 描边取 ceil(5/2)=3");
    }

    @Test
    void shapeStroke_outsetsHalfWidth() {
        DirtyRegion r = DirtyRegion.of(shape(50, 50, 40, 40, stroke(10)));
        assertEquals(new DirtyRegion(45, 45, 50, 50), r);
    }

    @Test
    void pathStroke_outsetsHalfWidth() {
        DirtyRegion r = DirtyRegion.of(path(50, 50, 40, 40, stroke(6), null, null));
        assertEquals(new DirtyRegion(47, 47, 46, 46), r);
    }

    @Test
    void pathMarker_outsetsMoreThanStrokeAlone() {
        DirtyRegion withMarker = DirtyRegion.of(path(50, 50, 40, 40, stroke(6), null, "arrow"));
        DirtyRegion plain = DirtyRegion.of(path(50, 50, 40, 40, stroke(6), null, null));
        assertTrue(withMarker.w() > plain.w(),
                "箭头画在端点之外，脏区要比只有描边时更大");
        assertTrue(withMarker.x() < plain.x());
    }

    @Test
    void rectStroke_notOutset() {
        DirtyRegion r = DirtyRegion.of(rect(50, 50, 40, 40, stroke(8)));
        assertEquals(new DirtyRegion(50, 50, 40, 40), r,
                "矩形边框是 4 条 fillRect，画在 bbox 内部，不外扩");
    }

    @Test
    void noStroke_notOutset() {
        DirtyRegion r = DirtyRegion.of(circle(50, 50, 40, 40, null));
        assertEquals(new DirtyRegion(50, 50, 40, 40), r);
        assertEquals(new DirtyRegion(50, 50, 40, 40),
                DirtyRegion.of(circle(50, 50, 40, 40, stroke(0))), "width=0 等于没描边");
    }

    /** 本条是这个 bug 的实际后果：溢出的描边跨进右边那张 map，不外扩就漏推、残影不擦。 */
    @Test
    void strokeSpillingAcrossMapBorder_coversNeighbourMap() {
        // 元素右边缘正好贴在第一张 map 的边界（x+w = 128），16px 描边向外溢 8px 进第二张
        CircleElement c = circle(88, 20, 40, 40, stroke(16));
        List<Integer> maps = DirtyRegion.of(c).coveredMapIndices(2, 1);
        assertEquals(List.of(0, 1), maps, "脏区要同时覆盖溢出去的那张 map");

        // 对照：同样位置但没有描边，只碰第一张
        assertEquals(List.of(0),
                DirtyRegion.of(circle(88, 20, 40, 40, null)).coveredMapIndices(2, 1));
    }

    @Test
    void rotationStillAppliesAfterOutset() {
        DirtyRegion r = DirtyRegion.of(new CircleElement("c-2", 50, 50, 40, 20, 90,
                false, true, Fill.solid("#FFFFFF"), stroke(8), null,
                BlendMode.NORMAL, RenderMode.CLEAN));
        // 先外扩成 48×28，再按 90° 求外接 → 宽高对调（宽度上的 +1 是原有旋转公式
        // 用 ceil(w·cos + h·sin) 时的浮点余数，与本次外扩无关）
        assertEquals(48, r.h(), "旋转要作用在外扩后的盒子上");
        assertTrue(r.w() >= 28 && r.w() <= 29);
    }
}
