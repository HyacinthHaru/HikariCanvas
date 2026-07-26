package ac.haru.hikaricanvas.template.asset;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
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

    /**
     * key = source name；value = 懒加载的图（{@link Optional#empty()} = 已经找过了，确实没有）。
     *
     * <p>用 Optional 而不是 null 值：{@link ConcurrentHashMap#computeIfAbsent} 的映射函数返回
     * null 时**根本不写表**，于是"找不到"这件事没被记住——渲染器每帧都会重新查一遍 classpath +
     * 磁盘并刷一行 WARNING。而元素校验只管 source 的字符格式、不管图标存不存在，随便一个不存在的
     * 名字就能进画布，这条路是走得通的。</p>
     */
    private final Map<String, Optional<BufferedImage>> imageCache = new ConcurrentHashMap<>();
    /** key = source name；value = PNG bytes（供 HTTP 直接 serve；从 imageCache 同步生成）。同样用 Optional 记住"没有"。 */
    private final Map<String, Optional<byte[]>> pngCache = new ConcurrentHashMap<>();

    public TemplateAssetService(Logger log, Path serverDataFolder) {
        this.log = log;
        this.serverIconsDir = serverDataFolder.resolve("assets").resolve("icons");
    }

    public static boolean isValidName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    /** 后端渲染用 BufferedImage（含 alpha）。找不到返回 null，且这个"找不到"会被记住。 */
    public BufferedImage loadIcon(String source) {
        if (!isValidName(source)) return null;
        return imageCache.computeIfAbsent(source,
                name -> Optional.ofNullable(loadIconUncached(name))).orElse(null);
    }

    /** HTTP 端点用 PNG bytes。找不到 / 编码失败返回 null，同样会被记住（端点可被反复打）。 */
    public byte[] iconPng(String source) {
        if (!isValidName(source)) return null;
        return pngCache.computeIfAbsent(source, name -> {
            BufferedImage img = loadIcon(name);
            if (img == null) return Optional.empty();
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                ImageIO.write(img, "PNG", baos);
                return Optional.of(baos.toByteArray());
            } catch (IOException e) {
                log.log(Level.WARNING, "[asset] PNG encode failed: " + name, e);
                return Optional.empty();
            }
        }).orElse(null);
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

    /**
     * 清空两级缓存（{@code /canvas reload} 调）。服主往 {@code assets/icons/} 里补了缺失的图标后，
     * 靠这个把"找不到"的记录也一起清掉，不用重启。
     */
    public void invalidate() {
        imageCache.clear();
        pngCache.clear();
    }
}
