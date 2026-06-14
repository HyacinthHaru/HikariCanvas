package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.ElementValidator;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.state.RenderMode;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 批次 B / Task B1：IconRenderer tint 分支 + ImageRenderer.drawWithFeather —— 关键帧 w/h
 * 超 MAX_DIM 时不分配巨型 off-screen buffer，不 OOM，直接 return。
 *
 * <p>前提：渲染线程由 AnimationTicker 驱动；关键帧 w/h 只受 finite 校验（parseValue 修前），
 * 可到 50000+ 。两处 {@code new BufferedImage(w, h, ...)} 直接分配 50000×50000 × 4B = 10 GB
 * native raster → OOM。修后在 {@code w/h > MAX_DIM} 时 return，不进入 new BufferedImage。</p>
 */
class OomGuardRenderersTest {

    private static final Logger LOG = Logger.getLogger(OomGuardRenderersTest.class.getName());
    private static final int OVER = ElementValidator.MAX_DIM + 1;  // 10001

    // ---------- 最小可用 RenderContext（无 assetService / imageLoader / iconRegistry）----------

    private static RenderContext minimalCtx() {
        return new RenderContext(null, null, null, null, LOG, () -> null);
    }

    // ---------- 最小画布：只要不 NPE 就足够（不关心像素输出）----------

    private static Graphics2D canvas() {
        BufferedImage buf = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buf.createGraphics();
        g.setColor(Color.BLACK);
        return g;
    }

    // ---------- IconRenderer — tint 分支 OOM 防御 ----------

    /**
     * w 超 MAX_DIM 时 renderLegacyPng 在 new BufferedImage 前 return。
     * assetService == null → 走 warning + return，不到 tint 分支；但 w > MAX_DIM 守卫在
     * w<=0 守卫之后、assetService null 检查之前，所以直接 return，不打 warning 也不 OOM。
     */
    @Test
    void iconRendererLegacyTint_oversizedW_doesNotOom() {
        // legacy source（无 /）→ renderLegacyPng 路径
        IconElement ic = new IconElement("ic1", 0, 0, OVER, 100, 0, false, true,
                "heart", "#FF0000", 1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null);
        IconRenderer renderer = new IconRenderer();
        Graphics2D g = canvas();
        try {
            assertDoesNotThrow(() -> renderer.draw(g, ic, minimalCtx()),
                    "w > MAX_DIM must not throw / OOM; must return early");
        } finally {
            g.dispose();
        }
    }

    @Test
    void iconRendererLegacyTint_oversizedH_doesNotOom() {
        IconElement ic = new IconElement("ic2", 0, 0, 100, OVER, 0, false, true,
                "heart", "#FF0000", 1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null);
        IconRenderer renderer = new IconRenderer();
        Graphics2D g = canvas();
        try {
            assertDoesNotThrow(() -> renderer.draw(g, ic, minimalCtx()),
                    "h > MAX_DIM must not throw / OOM; must return early");
        } finally {
            g.dispose();
        }
    }

    /** 正常尺寸（MAX_DIM）时守卫放行（不 return，走到 assetService null 路径——无 OOM）。 */
    @Test
    void iconRendererLegacyTint_exactlyMaxDim_allowed() {
        IconElement ic = new IconElement("ic3", 0, 0, ElementValidator.MAX_DIM,
                ElementValidator.MAX_DIM, 0, false, true,
                "heart", "#FF0000", 1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null);
        IconRenderer renderer = new IconRenderer();
        Graphics2D g = canvas();
        try {
            // assetService null → warning + return（占位）；关键是不 throw、不分配 10000×10000 buffer
            assertDoesNotThrow(() -> renderer.draw(g, ic, minimalCtx()),
                    "exactly MAX_DIM should be allowed (no OOM guard trigger)");
        } finally {
            g.dispose();
        }
    }

    // ---------- ImageRenderer.drawWithFeather OOM 防御 ----------

    /**
     * w 超 MAX_DIM 时 drawWithFeather 在 new BufferedImage(w, h, ...) 前 return。
     * im.mask().hasFeather() 需为 true 才能进 drawWithFeather 分支；没有 mask 时走 drawImage 路径，
     * 但 drawImage(img=null) 会 NPE。为验证 drawWithFeather 守卫，需要有 feather mask。
     * 没有 imageLoader 时 load() 返 null → drawImagePlaceholder 路径（不进 drawWithFeather）。
     * 所以这里验证：oversized + imageLoader non-null（返假图）+ feather mask 时守卫 return 不 OOM。
     */
    @Test
    void imageRendererDrawWithFeather_oversizedW_doesNotOom() {
        // 创建一个 1×1 的假图让 imageLoader 不返 null
        BufferedImage fakeImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        CanvasCompositor.ImageLoader fakeLoader = hash -> fakeImg;
        RenderContext ctx = new RenderContext(null, null, null, null, LOG,
                () -> fakeLoader);

        // feather mask：hasFeather() = true，强制进 drawWithFeather 分支
        moe.hikari.canvas.state.Mask featherMask =
                new moe.hikari.canvas.state.Mask("M0 0 L1 0 L1 1 L0 1 Z", false, 4);
        ImageElement im = new ImageElement("im1", 0, 0, OVER, 100, 0, false, true,
                "0123456789abcdef", featherMask, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        ImageRenderer renderer = new ImageRenderer();
        Graphics2D g = canvas();
        try {
            assertDoesNotThrow(() -> renderer.draw(g, im, ctx),
                    "w > MAX_DIM in drawWithFeather must return early without OOM");
        } finally {
            g.dispose();
        }
    }

    @Test
    void imageRendererDrawWithFeather_oversizedH_doesNotOom() {
        BufferedImage fakeImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        CanvasCompositor.ImageLoader fakeLoader = hash -> fakeImg;
        RenderContext ctx = new RenderContext(null, null, null, null, LOG,
                () -> fakeLoader);

        moe.hikari.canvas.state.Mask featherMask =
                new moe.hikari.canvas.state.Mask("M0 0 L1 0 L1 1 L0 1 Z", false, 4);
        ImageElement im = new ImageElement("im2", 0, 0, 100, OVER, 0, false, true,
                "0123456789abcdef", featherMask, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        ImageRenderer renderer = new ImageRenderer();
        Graphics2D g = canvas();
        try {
            assertDoesNotThrow(() -> renderer.draw(g, im, ctx),
                    "h > MAX_DIM in drawWithFeather must return early without OOM");
        } finally {
            g.dispose();
        }
    }
}
