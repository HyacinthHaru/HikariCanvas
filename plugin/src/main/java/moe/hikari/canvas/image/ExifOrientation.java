package moe.hikari.canvas.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 2026-05-25 项 4：JPEG EXIF Orientation 解析 + 自动旋转。
 *
 * <p>手机拍照 JPEG 含 EXIF Orientation tag（值 1-8），上传时若不读会导致图像方向错误
 * （横屏照片 90° / 倒置等）。本类用 ImageIO 内置 jpeg-metadata 路径解析 EXIF，
 * 不引入外部依赖（如 metadata-extractor）。</p>
 *
 * <p><b>实现路径</b>：</p>
 * <ol>
 *   <li>ImageIO 标准 metadata 树 `javax_imageio_jpeg_image_1.0` 下找
 *       App1 / unknown / markerSequence 节点</li>
 *   <li>遍历 markerSequence 找 EXIF App1 marker（{@code unknown MarkerTag=225}）</li>
 *   <li>该 marker 的 user 数据是 EXIF TIFF binary；手工解析 TIFF header（II/MM byte
 *       order + IFD + Orientation tag 0x0112）</li>
 * </ol>
 *
 * <p><b>性能</b>：仅在 JPEG 上传路径调；PNG / WebP 无 EXIF 直接 fast-path return null。
 * 用 byte[] 作输入避免额外 IO。</p>
 *
 * <p><b>Orientation 值含义</b>（EXIF 标准）：</p>
 * <pre>
 *   1 = 正常
 *   2 = 水平翻转
 *   3 = 旋转 180°
 *   4 = 垂直翻转
 *   5 = 顺时针 90° + 水平翻转
 *   6 = 顺时针 90°
 *   7 = 顺时针 270° + 水平翻转
 *   8 = 顺时针 270°
 * </pre>
 */
public final class ExifOrientation {

    private ExifOrientation() {}

    /**
     * 解析 EXIF Orientation。
     *
     * @param bytes 原始 JPEG 字节（不限定 MIME；非 JPEG 直接 return 1）
     * @return Orientation 值 1-8；解析失败 / 未找到时返回 1（正常方向）
     */
    public static int read(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return 1;
        // 快速判断 JPEG magic
        if (!((bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xD8)) return 1;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) return 1;
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
            if (!readers.hasNext()) return 1;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, false);  // ignoreMetadata=false
                IIOMetadata meta = reader.getImageMetadata(0);
                if (meta == null) return 1;
                Node root = meta.getAsTree("javax_imageio_jpeg_image_1.0");
                return findOrientationInTree(root);
            } finally {
                reader.dispose();
            }
        } catch (Throwable t) {
            // 任何异常（损坏 metadata / I/O / 解析）都 fail-soft 当作正常方向
            return 1;
        }
    }

    /** 在 jpeg metadata 树里找 markerSequence → unknown(225) → 解析其 user object 内嵌的 TIFF。 */
    private static int findOrientationInTree(Node root) {
        if (root == null) return 1;
        // markerSequence 通常 root.JPEGvariety + root.markerSequence
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if ("markerSequence".equalsIgnoreCase(c.getNodeName())) {
                NodeList ms = c.getChildNodes();
                for (int j = 0; j < ms.getLength(); j++) {
                    Node m = ms.item(j);
                    if (!"unknown".equalsIgnoreCase(m.getNodeName())) continue;
                    if (!(m instanceof Element me)) continue;
                    String tagAttr = me.getAttribute("MarkerTag");
                    if (!"225".equals(tagAttr)) continue;  // 0xE1 = App1（EXIF）
                    Object userObj = ((javax.imageio.metadata.IIOMetadataNode) m).getUserObject();
                    if (!(userObj instanceof byte[] app1Bytes)) continue;
                    int o = parseExifOrientation(app1Bytes);
                    if (o >= 1 && o <= 8) return o;
                }
            }
        }
        return 1;
    }

    /**
     * 解析 EXIF App1 payload。结构：
     * <ul>
     *   <li>前 6 字节："Exif\0\0"</li>
     *   <li>之后是 TIFF header：II / MM byte order + magic 0x002A + offset to first IFD</li>
     *   <li>IFD：2 字节 entry count + 每 entry 12 字节（tag 2 + type 2 + count 4 + value 4）</li>
     *   <li>找 tag = 0x0112 (Orientation)，type SHORT (3)，value 是低 2 字节</li>
     * </ul>
     */
    static int parseExifOrientation(byte[] data) {
        if (data == null || data.length < 14) return 1;
        // "Exif\0\0" 前缀
        int p = 0;
        if (data[0] == 'E' && data[1] == 'x' && data[2] == 'i' && data[3] == 'f'
                && data[4] == 0 && data[5] == 0) {
            p = 6;
        }
        if (data.length < p + 8) return 1;
        boolean littleEndian;
        if (data[p] == 'I' && data[p + 1] == 'I') {
            littleEndian = true;
        } else if (data[p] == 'M' && data[p + 1] == 'M') {
            littleEndian = false;
        } else {
            return 1;
        }
        int tiffStart = p;
        // magic 0x002A at p+2..p+3
        int magic = readU16(data, p + 2, littleEndian);
        if (magic != 0x002A) return 1;
        int ifd0Offset = (int) readU32(data, p + 4, littleEndian);
        int ifd0Abs = tiffStart + ifd0Offset;
        if (ifd0Abs < 0 || ifd0Abs + 2 > data.length) return 1;
        int entries = readU16(data, ifd0Abs, littleEndian);
        if (entries < 0 || entries > 1000) return 1;
        int base = ifd0Abs + 2;
        for (int i = 0; i < entries; i++) {
            int eOff = base + i * 12;
            if (eOff + 12 > data.length) return 1;
            int tag = readU16(data, eOff, littleEndian);
            if (tag == 0x0112) {
                // type 在 eOff+2..eOff+3; count eOff+4..+7; value/offset eOff+8..+11
                // Orientation 是 SHORT(3) count=1, value 直接在低 2 字节
                int v = readU16(data, eOff + 8, littleEndian);
                if (v >= 1 && v <= 8) return v;
                return 1;
            }
        }
        return 1;
    }

    private static int readU16(byte[] d, int off, boolean le) {
        int b0 = d[off] & 0xff;
        int b1 = d[off + 1] & 0xff;
        return le ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private static long readU32(byte[] d, int off, boolean le) {
        long b0 = d[off] & 0xffL;
        long b1 = d[off + 1] & 0xffL;
        long b2 = d[off + 2] & 0xffL;
        long b3 = d[off + 3] & 0xffL;
        return le
                ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
                : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    /**
     * 根据 EXIF Orientation 旋转 / 翻转 {@link BufferedImage}。
     * <p>Orientation=1 时直接返回原图（不复制）；其他值返回新 ARGB BufferedImage。</p>
     */
    public static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (src == null || orientation <= 1 || orientation > 8) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        // 5-8 是旋转 90°/270° 组合，输出宽高互换
        boolean swap = orientation >= 5 && orientation <= 8;
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            // 用 AffineTransform 一次性表达任意 orientation。
            // 推导：先平移到目标中心，转，flip，再平移回左上。
            // 这里用简化的 case 分发 — 8 种值都是离散整数变换，AWT 调度足够清晰。
            java.awt.geom.AffineTransform tx = new java.awt.geom.AffineTransform();
            switch (orientation) {
                case 2 -> {  // 水平翻转
                    tx.translate(dw, 0);
                    tx.scale(-1, 1);
                }
                case 3 -> {  // 180°
                    tx.translate(dw, dh);
                    tx.rotate(Math.PI);
                }
                case 4 -> {  // 垂直翻转
                    tx.translate(0, dh);
                    tx.scale(1, -1);
                }
                case 5 -> {  // 90° + 水平翻转
                    tx.rotate(Math.PI / 2);
                    tx.scale(1, -1);
                }
                case 6 -> {  // 顺时针 90°
                    tx.translate(dw, 0);
                    tx.rotate(Math.PI / 2);
                }
                case 7 -> {  // 270° + 水平翻转
                    tx.scale(-1, 1);
                    tx.translate(-dw, 0);
                    tx.translate(0, dh);
                    tx.rotate(-Math.PI / 2);
                }
                case 8 -> {  // 顺时针 270°（等价于逆时针 90°）
                    tx.translate(0, dh);
                    tx.rotate(-Math.PI / 2);
                }
                default -> { /* 1 已早返 */ }
            }
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** logger-aware 包装版：读 + 应用一次性完成；失败时 warn 并返回原图。 */
    public static BufferedImage autoRotateJpeg(byte[] jpegBytes, BufferedImage decoded, Logger log) {
        try {
            int o = read(jpegBytes);
            if (o <= 1) return decoded;
            BufferedImage rotated = applyOrientation(decoded, o);
            if (rotated != decoded && log != null) {
                log.log(Level.FINE, "EXIF orientation=" + o + " applied; "
                        + decoded.getWidth() + "x" + decoded.getHeight()
                        + " → " + rotated.getWidth() + "x" + rotated.getHeight());
            }
            return rotated;
        } catch (Throwable t) {
            if (log != null) log.log(Level.WARNING, "EXIF auto-rotate failed; using original orientation", t);
            return decoded;
        }
    }
}
