package ac.haru.hikaricanvas.template.preview;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.state.ProjectState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把单个 wall 的 {@link ProjectState} 渲染成 PNG bytes，用于 HomePage 卡片预览。
 * 缓存策略由调用方（{@link ac.haru.hikaricanvas.web.WebServer}）按 {@code "wallId@updatedAt"} 做。
 */
public final class WallPreviewService {

    private final Logger log;
    private final CanvasCompositor compositor;

    public WallPreviewService(Logger log, CanvasCompositor compositor) {
        this.log = log;
        this.compositor = compositor;
    }

    public byte[] renderPng(ProjectState state) {
        if (state == null) return null;
        try {
            BufferedImage rgba = compositor.rasterize(state);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            ImageIO.write(rgba, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.log(Level.WARNING, "[wallPreview] PNG encode failed", e);
            return null;
        } catch (Exception e) {
            log.log(Level.WARNING, "[wallPreview] render failed", e);
            return null;
        }
    }
}
