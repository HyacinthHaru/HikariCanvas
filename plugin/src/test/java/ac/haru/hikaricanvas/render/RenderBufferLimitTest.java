package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.Glow;
import ac.haru.hikaricanvas.state.ImageElement;
import ac.haru.hikaricanvas.state.Mask;
import ac.haru.hikaricanvas.state.RenderMode;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染中间缓冲区的分配闸门（rendering.md §4.4 / §5.3）。
 *
 * <p>发光外接盒与羽化 off-buffer 都只按元素尺寸算、不看画布，而元素尺寸与文本参数的**合法**
 * 上限组合能顶到几亿像素（发光 3.5 GB / 羽化两张 400 MB），且这段渲染跑在编辑会话的锁里 ——
 * 有 canvas.edit 权限的玩家用纯合法 op 就能打挂服务器。这里守两件事：</p>
 * <ol>
 *   <li>与画布求交后再分配，且交集向外扩够模糊半径 —— <b>可见像素不变</b></li>
 *   <li>交完仍超上限（或压根拿不到画布范围）时走各自的降级路径，<b>不尝试分配</b></li>
 * </ol>
 */
class RenderBufferLimitTest {

    private static final Logger LOG = Logger.getLogger(RenderBufferLimitTest.class.getName());

    /** 带 clip 的画布 Graphics2D（生产路径由 CanvasCompositor 显式 setClip 到整块画布）。 */
    private static Graphics2D clippedCanvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setClip(0, 0, img.getWidth(), img.getHeight());
        return g;
    }

    // ---------- GlowRenderer：外接盒裁剪 + 面积上限 ----------

    @Test
    void glow_bboxInsideCanvas_isNotClipped() {
        BufferedImage canvas = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedCanvas(canvas);
        try {
            Rectangle box = GlowRenderer.clipToCanvas(g, 10, 10, 50, 40, 5);
            assertNotNull(box);
            assertEquals(new Rectangle(10, 10, 50, 40), box, "完全在画布内的外接盒不该被动");
        } finally {
            g.dispose();
        }
    }

    @Test
    void glow_hugeBbox_isClippedToCanvasPlusPadding() {
        BufferedImage canvas = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedCanvas(canvas);
        try {
            int pad = 65;   // radius=64 时的 padding
            // 文本参数全合法就能长出来的怪物外接盒
            Rectangle box = GlowRenderer.clipToCanvas(g, -5000, -5000, 20000, 40000, pad);
            assertNotNull(box, "裁剪后应远小于上限，不该被拒");
            assertEquals(new Rectangle(-pad, -pad, 128 + pad * 2, 128 + pad * 2), box,
                    "应裁到「画布向外扩 padding」");
            assertTrue((long) box.width * box.height < GlowRenderer.MAX_GLOW_PIXELS);
        } finally {
            g.dispose();
        }
    }

    @Test
    void glow_bboxFullyOutsideCanvas_isRejected() {
        BufferedImage canvas = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedCanvas(canvas);
        try {
            assertNull(GlowRenderer.clipToCanvas(g, 100_000, 100_000, 50, 50, 5),
                    "整个外接盒都在画布外 → 不画");
        } finally {
            g.dispose();
        }
    }

    @Test
    void glow_noClipAndOversizedBbox_hitsAreaCap() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();   // 故意不 setClip：读不到画布范围
        try {
            assertNull(GlowRenderer.clipToCanvas(g, 0, 0, 10_000, 35_000, 5),
                    "拿不到画布范围时只剩面积上限兜底，3.5 亿像素必须被拒");
        } finally {
            g.dispose();
        }
    }

    @Test
    void glow_noClipAndModerateBbox_isAllowed() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            // 32×32 maps 满画布 = 4096×4096，必须放行（这是合法上限，不是攻击）
            Rectangle box = GlowRenderer.clipToCanvas(g, 0, 0, 4096, 4096, 5);
            assertNotNull(box, "满画布尺寸的发光是合法用法，不能被上限误伤");
        } finally {
            g.dispose();
        }
    }

    /** 端到端：怪物 bbox + 小画布，render 不该抛、不该 OOM，画布内的字形光晕照常出现。 */
    @Test
    void glow_render_withHugeGlyphSpread_staysCheapAndStillDraws() {
        BufferedImage canvas = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedCanvas(canvas);
        List<TextLayout.PositionedGlyph> glyphs = new ArrayList<>();
        glyphs.add(new TextLayout.PositionedGlyph("A", 20, 60));
        // 远在画布之外的字形（合法参数就能排到这么远）——不裁剪的话外接盒要 2 万像素宽
        glyphs.add(new TextLayout.PositionedGlyph("A", 20_000, 60));
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
        try {
            assertDoesNotThrow(() -> GlowRenderer.render(g, glyphs, font, null,
                    new Glow(8, "#FF0000")));
        } finally {
            g.dispose();
        }
        assertTrue(anyNonTransparent(canvas), "画布内那个字形的光晕仍要画出来");
    }

    private static boolean anyNonTransparent(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    // ---------- ImageRenderer：羽化 off-buffer 裁剪 + 降级 ----------

    @Test
    void feather_elementInsideCanvas_usesFullElementRect() {
        BufferedImage canvas = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedCanvas(canvas);
        try {
            Rectangle crop = ImageRenderer.featherBufferRect(g, 100, 80, 8);
            assertEquals(new Rectangle(0, 0, 100, 80), crop, "元素完全在画布内 → 不裁");
        } finally {
            g.dispose();
        }
    }

    @Test
    void feather_oversizedElement_isClippedToCanvasPlusBlurReach() {
        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedCanvas(canvas);
        try {
            int feather = 32;                 // 校验层允许的最大值
            int reach = 3 * feather + 1;      // 盒滤波 3 遍，每遍影响半径 feather
            Rectangle crop = ImageRenderer.featherBufferRect(g, 10_000, 10_000, feather);
            assertNotNull(crop);
            assertEquals(new Rectangle(0, 0, 64 + reach, 64 + reach), crop,
                    "应裁到「画布 ∩ 元素」再向外扩 3×featherPx+1");
            assertTrue((long) crop.width * crop.height < ImageRenderer.MAX_FEATHER_PIXELS);
        } finally {
            g.dispose();
        }
    }

    @Test
    void feather_elementFullyOffCanvas_isEmpty() {
        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedCanvas(canvas);
        try {
            g.translate(5000, 5000);   // 元素本地坐标整体挪到画布外
            Rectangle crop = ImageRenderer.featherBufferRect(g, 100, 100, 4);
            assertTrue(crop.isEmpty(), "元素完全在画布外 → 不分配也不画");
        } finally {
            g.dispose();
        }
    }

    @Test
    void feather_noClipAndOversizedElement_returnsNullForDegrade() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();   // 无 clip
        try {
            assertNull(ImageRenderer.featherBufferRect(g, 10_000, 10_000, 32),
                    "读不到画布范围 + 1 亿像素 → 交给调用方降级，不许分配 400 MB");
        } finally {
            g.dispose();
        }
    }

    /**
     * #80 回归：关键帧把 w/h 插值到超过 MAX_DIM 的带羽化图片，以前后端直接 return（整张图消失，
     * 前端照常显示），现在必须降级成硬边直接画。
     */
    @Test
    void feather_oversizedElement_stillDrawsViaHardEdgeFallback() {
        BufferedImage src = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = src.createGraphics();
        sg.setColor(Color.RED);
        sg.fillRect(0, 0, 4, 4);
        sg.dispose();

        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img2d(canvas);
        RenderContext ctx = new RenderContext(null, null, null, null, LOG, () -> hash -> src);
        // w/h 远超 MAX_DIM（10000）：只有关键帧插值能造出来
        ImageElement im = new ImageElement("im-huge", 0, 0, 50_000, 50_000, 0, false, true,
                "0123456789abcdef", new Mask("M0 0 L1 0 L1 1 L0 1 Z", false, 4),
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        try {
            assertDoesNotThrow(() -> new ImageRenderer().draw(g, im, ctx));
        } finally {
            g.dispose();
        }
        assertTrue(anyNonTransparent(canvas), "超尺寸也要画出来（降级硬边），不能整张消失");
    }

    private static Graphics2D img2d(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setClip(0, 0, img.getWidth(), img.getHeight());
        return g;
    }

    // ---------- 可分离盒滤波 ----------

    /** 分离核只是把二维均值核拆成两个一维核，仍然要真的把 alpha 按距离铺开。 */
    @Test
    void boxBlurAlpha_separableKernel_stillSpreadsAlpha() {
        // 128×128 画布上一块 32..96 的实心方块；r=8 跑 3 遍 → 影响半径 24
        BufferedImage mask = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        mg.setColor(Color.WHITE);
        mg.fillRect(32, 32, 64, 64);
        mg.dispose();

        BufferedImage blurred = ImageRenderer.boxBlurAlpha(mask, 8);
        assertNotNull(blurred);
        int center = alphaAt(blurred, 64, 64);        // 距边 32 > 影响半径 → 仍满不透明
        int near = alphaAt(blurred, 28, 64);          // 边外 4px
        int far = alphaAt(blurred, 20, 64);           // 边外 12px
        int beyond = alphaAt(blurred, 4, 64);         // 边外 28px，超出影响半径
        assertEquals(255, center, "影响半径之内的实心区仍应满不透明");
        assertTrue(near > far, "羽化应随距离衰减：near=" + near + " far=" + far);
        assertTrue(far > 0, "影响半径内应有羽化过渡，实测 " + far);
        assertEquals(0, beyond, "影响半径之外不该被染上");
    }

    private static int alphaAt(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) >>> 24;
    }

    /** 最大半径 + 满画布尺寸下不该慢到卡死（二维核在这个规模是几十亿次乘加）。 */
    @Test
    void boxBlurAlpha_maxRadiusOnLargeBuffer_completesQuickly() {
        BufferedImage mask = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        long t0 = System.nanoTime();
        BufferedImage out = ImageRenderer.boxBlurAlpha(mask, 32);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertNotNull(out);
        assertTrue(elapsedMs < 5_000, "512×512 + r=32 三遍不该超过 5s，实测 " + elapsedMs + "ms");
    }
}
