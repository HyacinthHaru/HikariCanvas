package moe.hikari.canvas.benchmark;

import java.util.Arrays;

/**
 * 一组样本的分位数统计（单位：毫秒）。
 *
 * <p>p50/p95/p99 用<b>线性插值法</b>（R-7 / Excel {@code PERCENTILE.INC} 同款）——对
 * benchmark 这种小样本（measuredIterations 数十~数百）比「最近秩」更平滑、更标准。所有字段
 * 都是描述性统计，不含任何「推荐/结论」语义（「工具不是保姆」哲学：给原料不给结论）。</p>
 *
 * @param p50    中位数（ms）
 * @param p95    95 分位（ms）
 * @param p99    99 分位（ms）
 * @param mean   均值（ms）
 * @param min    最小（ms）
 * @param max    最大（ms）
 * @param stddev 样本标准差（ms，n&gt;1 时用 n-1 分母；否则 0）
 * @param count  样本数
 */
public record Percentiles(
        double p50, double p95, double p99,
        double mean, double min, double max, double stddev, int count
) {
    /** 空统计（count=0，其余 0）。 */
    public static final Percentiles EMPTY = new Percentiles(0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * 从一组毫秒样本计算分位数。入参<b>不被修改</b>（内部 clone + sort）。
     *
     * @param valuesMs 毫秒样本；null / 空 → {@link #EMPTY}
     */
    public static Percentiles of(double[] valuesMs) {
        if (valuesMs == null || valuesMs.length == 0) {
            return EMPTY;
        }
        double[] v = valuesMs.clone();
        Arrays.sort(v);
        int n = v.length;
        double sum = 0;
        for (double x : v) {
            sum += x;
        }
        double mean = sum / n;
        double var = 0;
        for (double x : v) {
            double d = x - mean;
            var += d * d;
        }
        double stddev = n > 1 ? Math.sqrt(var / (n - 1)) : 0.0;
        return new Percentiles(
                percentile(v, 50), percentile(v, 95), percentile(v, 99),
                mean, v[0], v[n - 1], stddev, n);
    }

    /**
     * 线性插值分位数（{@code v} 须已升序）。{@code p ∈ [0,100]}。
     *
     * <p>0-based 插值位置 {@code rank = p/100 × (n-1)}；落在两秩间则按小数部分线性插值。
     * 与 numpy {@code linear} / Excel {@code PERCENTILE.INC} 一致。</p>
     */
    private static double percentile(double[] v, double p) {
        int n = v.length;
        if (n == 1) {
            return v[0];
        }
        double rank = (p / 100.0) * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return v[lo];
        }
        double frac = rank - lo;
        return v[lo] + (v[hi] - v[lo]) * frac;
    }
}
