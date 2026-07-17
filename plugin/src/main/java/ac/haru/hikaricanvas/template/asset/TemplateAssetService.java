package ac.haru.hikaricanvas.template.asset;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 模板图标资源服务。把 {@code IconElement.source} 字符串解析到 BufferedImage / PNG bytes，
 * 并做防注入校验 + 内存缓存。
 *
 * <p><b>命名规则：</b> source 必须匹配 {@link #SAFE_NAME}（小写字母数字下划线短横，长度
 * 1-32），不允许任何 {@code /} {@code \} {@code ..}，杜绝路径穿越。</p>
 *
 * <p><b>查找顺序：</b></p>
 * <ol>
 *   <li>classpath {@code /template-assets/icons/&lt;source&gt;.png}（jar 内 builtin）</li>
 *   <li>{@code &lt;dataFolder&gt;/assets/icons/&lt;source&gt;.png}（服主自定义）</li>
 * </ol>
 *
 * <p>找不到返回 null（调用方画占位 / 跳过）。</p>
 */
public final class TemplateAssetService {

    public static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private final Logger log;
    private final Path serverIconsDir;

    /** key = source name；value = lazy-loaded BufferedImage（null = 已尝试但找不到） */
    private final Map<String, BufferedImage> imageCache = new ConcurrentHashMap<>();
    /** key = source name；value = PNG bytes（供 HTTP 直接 serve；从 imageCache 同步生成） */
    private final Map<String, byte[]> pngCache = new ConcurrentHashMap<>();

    public TemplateAssetService(Logger log, Path serverDataFolder) {
        this.log = log;
        this.serverIconsDir = serverDataFolder.resolve("assets").resolve("icons");
    }

    public static boolean isValidName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    /** 后端渲染用 BufferedImage（含 alpha）。 */
    public BufferedImage loadIcon(String source) {
        if (!isValidName(source)) return null;
        return imageCache.computeIfAbsent(source, this::loadIconUncached);
    }

    /** HTTP 端点用 PNG bytes。 */
    public byte[] iconPng(String source) {
        if (!isValidName(source)) return null;
        return pngCache.computeIfAbsent(source, name -> {
            BufferedImage img = loadIcon(name);
            if (img == null) return null;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                ImageIO.write(img, "PNG", baos);
                return baos.toByteArray();
            } catch (IOException e) {
                log.log(Level.WARNING, "[asset] PNG encode failed: " + name, e);
                return null;
            }
        });
    }

    private BufferedImage loadIconUncached(String source) {
        // 1) classpath builtin
        String classpathName = "/template-assets/icons/" + source + ".png";
        try (InputStream in = getClass().getResourceAsStream(classpathName)) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) return img;
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "[asset] read failed for builtin " + source, e);
        }
        // 2) server data folder
        Path p = serverIconsDir.resolve(source + ".png");
        if (Files.isRegularFile(p)) {
            try (InputStream in = Files.newInputStream(p)) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) return img;
            } catch (IOException e) {
                log.log(Level.WARNING, "[asset] read failed for server " + source, e);
            }
        }
        log.warning("[asset] icon not found: " + source
                + " (looked classpath + " + p + ")");
        return null;
    }

    public void invalidate() {
        imageCache.clear();
        pngCache.clear();
    }
}
