package moe.hikari.canvas.render;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * MC 地图调色板查找表，契约见 {@code docs/rendering.md §6}。
 *
 * <h2>算法</h2>
 * <ul>
 *   <li><b>输入</b>：classpath 下的 {@code palette.json}（由 M4-T1 构建期生成）</li>
 *   <li><b>距离度量</b>：CIE76 Lab 欧氏距离（sRGB D65 → XYZ → Lab）</li>
 *   <li><b>数据结构</b>：{@code byte[32*32*32]} 扁平数组，索引 = {@code (r>>3)<<10 | (g>>3)<<5 | (b>>3)}
 *       （RGB 各取高 5 位）</li>
 *   <li><b>查询</b>：{@link #matchColor(int, int, int)} O(1)；{@link #matchColor(int,int,int,int)}
 *       对 alpha&lt;128 直接返回 {@link #TRANSPARENT_INDEX}（{@code rendering.md §6.4}）</li>
 * </ul>
 *
 * <h2>构建成本</h2>
 * 启动期一次性构建，32³ × (palette 大小) ≈ 8 M 距离比较；单线程耗时 ~1-3 秒（24 核并行更快，
 * 但 M4 阶段不优化）。构建后 LUT 大小 32 KB，整个 JVM 生命周期常驻。
 *
 * <h2>替代 {@link org.bukkit.map.MapPalette#matchColor} 的动机</h2>
 * Paper 1.21 把 {@code matchColor(int,int,int)} 标 {@code @Deprecated(forRemoval=true)} 但没给替代；
 * 自建 LUT 既能去 deprecation，又能用 CIE76 Lab 比 RGB 欧氏更视觉准确。
 *
 * <h2>线程安全</h2>
 * 构建完后 {@link #lut} 只读；所有 {@code matchColor} 都是纯函数，任意线程并发安全。
 */
public final class PaletteLut {

    /** MC 地图调色板中 alpha=0 的透明索引（M4-T1 实测 index 0-3 均透明，约定用 0）。 */
    public static final byte TRANSPARENT_INDEX = 0;

    /** alpha &lt; 该阈值视为透明，直接返回 {@link #TRANSPARENT_INDEX}。 */
    public static final int ALPHA_THRESHOLD = 128;

    /** 每轴量化位数（5-bit → 32 档）。LUT 尺寸 = {@code 1 << (3*LUT_BITS)} = 32768 = 32 KiB。 */
    private static final int LUT_BITS = 5;
    private static final int LUT_DIM = 1 << LUT_BITS;           // 32
    private static final int LUT_SIZE = LUT_DIM * LUT_DIM * LUT_DIM; // 32768

    /** D65 标准白点 (Observer 2°)。 */
    private static final double D65_X = 0.95047;
    private static final double D65_Y = 1.00000;
    private static final double D65_Z = 1.08883;

    /** CIE 标准常量。 */
    private static final double LAB_EPSILON = 216.0 / 24389.0; // 0.008856
    private static final double LAB_KAPPA = 24389.0 / 27.0;    // 903.3

    private final PaletteEntry[] entries;       // 原始 256 项（含透明）
    private final int[] opaqueIndices;          // 非透明 palette 索引表，供匹配循环用
    private final double[] opaqueLabL;
    private final double[] opaqueLabA;
    private final double[] opaqueLabB;
    private final byte[] lut;                   // 32×32×32 预计算结果

    /**
     * 从 classpath 加载 {@code palette.json} 并构建 LUT。
     *
     * @param resourcePath 例如 {@code "/palette.json"}
     */
    public static PaletteLut loadFromClasspath(String resourcePath) throws IOException {
        try (InputStream in = PaletteLut.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("palette resource not found: " + resourcePath);
            }
            ObjectMapper mapper = new ObjectMapper();
            List<PaletteEntry> list = mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, PaletteEntry.class));
            return new PaletteLut(list);
        }
    }

    public PaletteLut(List<PaletteEntry> paletteEntries) {
        this.entries = paletteEntries.toArray(new PaletteEntry[0]);
        // 预筛非透明 palette 项；只有它们参与 RGB → index 匹配
        int opaqueCount = 0;
        for (PaletteEntry e : entries) if (e.alpha >= ALPHA_THRESHOLD) opaqueCount++;
        this.opaqueIndices = new int[opaqueCount];
        this.opaqueLabL = new double[opaqueCount];
        this.opaqueLabA = new double[opaqueCount];
        this.opaqueLabB = new double[opaqueCount];
        int k = 0;
        for (PaletteEntry e : entries) {
            if (e.alpha < ALPHA_THRESHOLD) continue;
            opaqueIndices[k] = e.index;
            double[] lab = rgbToLab(e.rgb[0], e.rgb[1], e.rgb[2]);
            opaqueLabL[k] = lab[0];
            opaqueLabA[k] = lab[1];
            opaqueLabB[k] = lab[2];
            k++;
        }
        this.lut = buildLut();
    }

    /**
     * 查找最近的 palette 索引。忽略 alpha（即当作不透明查）；
     * 如果你想考虑透明，用 {@link #matchColor(int, int, int, int)}。
     */
    public byte matchColor(int r, int g, int b) {
        int rq = clamp8(r) >> (8 - LUT_BITS);
        int gq = clamp8(g) >> (8 - LUT_BITS);
        int bq = clamp8(b) >> (8 - LUT_BITS);
        return lut[(rq << (2 * LUT_BITS)) | (gq << LUT_BITS) | bq];
    }

    /** 带 alpha 的快速入口：{@code alpha < 128} 直接返回 {@link #TRANSPARENT_INDEX}。 */
    public byte matchColor(int r, int g, int b, int a) {
        if (a < ALPHA_THRESHOLD) return TRANSPARENT_INDEX;
        return matchColor(r, g, b);
    }

    /** 调色板总项数（含透明）。 */
    public int size() {
        return entries.length;
    }

    /** 反向查询：从索引取 AWT Color。越界返回 {@code null}。 */
    public Color getColor(byte index) {
        int idx = Byte.toUnsignedInt(index);
        if (idx >= entries.length) return null;
        PaletteEntry e = entries[idx];
        return new Color(e.rgb[0], e.rgb[1], e.rgb[2], e.alpha);
    }

    // ---------- LUT 构建 ----------

    private byte[] buildLut() {
        byte[] out = new byte[LUT_SIZE];
        final int step = 1 << (8 - LUT_BITS); // 每档跨度 = 8
        // 取 grid cell 的**中心点**作为代表色（减少边界量化误差）
        final int midOffset = step / 2;        // 4
        for (int rq = 0; rq < LUT_DIM; rq++) {
            int r = rq * step + midOffset;
            for (int gq = 0; gq < LUT_DIM; gq++) {
                int g = gq * step + midOffset;
                for (int bq = 0; bq < LUT_DIM; bq++) {
                    int b = bq * step + midOffset;
                    double[] lab = rgbToLab(r, g, b);
                    int best = findNearestOpaque(lab[0], lab[1], lab[2]);
                    out[(rq << (2 * LUT_BITS)) | (gq << LUT_BITS) | bq] = (byte) best;
                }
            }
        }
        return out;
    }

    private int findNearestOpaque(double L, double a, double b) {
        double bestDist = Double.POSITIVE_INFINITY;
        int bestIdx = 0;
        for (int i = 0; i < opaqueIndices.length; i++) {
            double dL = L - opaqueLabL[i];
            double dA = a - opaqueLabA[i];
            double dB = b - opaqueLabB[i];
            double d = dL * dL + dA * dA + dB * dB;
            if (d < bestDist) {
                bestDist = d;
                bestIdx = opaqueIndices[i];
            }
        }
        return bestIdx;
    }

    // ---------- RGB → Lab（D65，sRGB companding） ----------

    private static double[] rgbToLab(int r, int g, int b) {
        double rn = srgbToLinear(r / 255.0);
        double gn = srgbToLinear(g / 255.0);
        double bn = srgbToLinear(b / 255.0);
        // sRGB → XYZ (D65)
        double x = 0.4124564 * rn + 0.3575761 * gn + 0.1804375 * bn;
        double y = 0.2126729 * rn + 0.7151522 * gn + 0.0721750 * bn;
        double z = 0.0193339 * rn + 0.1191920 * gn + 0.9503041 * bn;
        // XYZ → Lab
        double fx = labF(x / D65_X);
        double fy = labF(y / D65_Y);
        double fz = labF(z / D65_Z);
        double L = 116.0 * fy - 16.0;
        double A = 500.0 * (fx - fy);
        double B = 200.0 * (fy - fz);
        return new double[]{L, A, B};
    }

    private static double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > LAB_EPSILON
                ? Math.cbrt(t)
                : (LAB_KAPPA * t + 16.0) / 116.0;
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---------- palette.json DTO ----------

    /** palette.json 里每个对象反序列化成此结构。 */
    public static final class PaletteEntry {
        public int index;
        public int[] rgb;     // 长 3
        public int alpha;

        public PaletteEntry() {} // Jackson

        public PaletteEntry(int index, int[] rgb, int alpha) {
            this.index = index;
            this.rgb = rgb;
            this.alpha = alpha;
        }
    }
}
