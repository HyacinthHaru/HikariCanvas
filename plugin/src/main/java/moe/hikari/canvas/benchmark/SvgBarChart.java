package moe.hikari.canvas.benchmark;

import java.util.List;
import java.util.Locale;

/**
 * 纯字符串 SVG 横向条形图生成器（{@code 0.5.0 Benchmark P3} 自包含 HTML 报告专用）。
 *
 * <p>无任何外部依赖、无 Bukkit / NMS、headless——只把 {@link Bar} 列表拼成一段
 * {@code <svg>…</svg>} 片段（<b>不是</b>整页 HTML 文档），由 {@link HtmlReportRenderer}
 * 内联进报告。响应式 viewBox + {@code width:100%}，随页面缩放。</p>
 *
 * <h2>配色</h2>
 * Catppuccin Latte：条 {@code #1e66f5}（蓝）/ 主文字 {@code #4c4f69} / 次文字 {@code #6c6f85}
 * / 轨道 {@code #ccd0da}。
 *
 * <h2>安全</h2>
 * 标题与每个 label 都做 HTML 转义（{@code & < > " '}），数值用 {@link Locale#ROOT} 定区域格式化，
 * 避免逗号小数破坏 SVG / 页面。空列表返回一个 {@code (no data)} 的小 SVG，<b>绝不抛异常</b>。
 */
public final class SvgBarChart {

    /** 单行条目：左侧标签 + 数值。 */
    public record Bar(String label, double value) {}

    // ---- 配色（Catppuccin Latte）----
    private static final String COLOR_BAR = "#1e66f5";
    private static final String COLOR_TEXT = "#4c4f69";
    private static final String COLOR_SUBTEXT = "#6c6f85";
    private static final String COLOR_TRACK = "#ccd0da";
    private static final String COLOR_ROW_ALT = "#eff1f5";

    // ---- 布局常量（用户坐标系，viewBox 内）----
    private static final int VIEW_WIDTH = 640;
    private static final int ROW_HEIGHT = 22;
    private static final int TITLE_HEIGHT = 30;
    private static final int PADDING_TOP = 6;
    private static final int PADDING_BOTTOM = 8;
    private static final int LABEL_X = 8;
    private static final int LABEL_WIDTH = 180;   // 左侧标签列宽
    private static final int VALUE_WIDTH = 96;     // 右侧数值列宽
    private static final int BAR_X = LABEL_X + LABEL_WIDTH;
    private static final int BAR_MAX_WIDTH = VIEW_WIDTH - BAR_X - VALUE_WIDTH;
    private static final int BAR_STUB_WIDTH = 2;   // 0 / 负值时的最小可见残桩
    private static final int BAR_INSET = 4;        // 条上下留白

    private SvgBarChart() {
        // 工具类
    }

    /**
     * 生成横向条形图 SVG 片段。
     *
     * @param title 图表标题（会被 HTML 转义）
     * @param bars  条目列表；{@code null} 或空都安全返回 {@code (no data)} 占位
     * @param unit  数值单位（追加在格式化数字后，如 {@code "ms"}；{@code null} 视为空串）
     * @return 一段独立可内联的 {@code <svg>…</svg>} 字符串
     */
    public static String horizontal(String title, List<Bar> bars, String unit) {
        String safeUnit = unit == null ? "" : unit;
        String safeTitle = escape(title == null ? "" : title);

        if (bars == null || bars.isEmpty()) {
            return emptyChart(safeTitle);
        }

        // 求最大值用于归一化；guard maxValue<=0 防除零（届时全部按残桩画）。
        double maxValue = 0.0;
        for (Bar b : bars) {
            double v = b == null ? 0.0 : b.value();
            if (Double.isFinite(v) && v > maxValue) {
                maxValue = v;
            }
        }
        boolean degenerate = !(maxValue > 0.0); // maxValue<=0 或非有限

        int rows = bars.size();
        int totalHeight = TITLE_HEIGHT + PADDING_TOP + rows * ROW_HEIGHT + PADDING_BOTTOM;

        StringBuilder sb = new StringBuilder(512 + rows * 256);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(VIEW_WIDTH).append(' ').append(totalHeight)
                .append("\" style=\"width:100%;height:auto;font-family:system-ui,-apple-system,sans-serif\" ")
                .append("role=\"img\">");

        // 标题
        sb.append("<text x=\"").append(LABEL_X).append("\" y=\"20\" ")
                .append("font-size=\"14\" font-weight=\"600\" fill=\"").append(COLOR_TEXT)
                .append("\">").append(safeTitle).append("</text>");

        int baseY = TITLE_HEIGHT + PADDING_TOP;
        for (int i = 0; i < rows; i++) {
            Bar b = bars.get(i);
            String label = escape(b == null ? "" : b.label());
            double value = b == null ? 0.0 : b.value();
            if (!Double.isFinite(value)) {
                value = 0.0;
            }

            int rowY = baseY + i * ROW_HEIGHT;
            int textY = rowY + ROW_HEIGHT - 7;          // 文字基线
            int barY = rowY + BAR_INSET;
            int barH = ROW_HEIGHT - BAR_INSET * 2;

            // 交替行背景
            if ((i & 1) == 1) {
                sb.append("<rect x=\"0\" y=\"").append(rowY).append("\" width=\"")
                        .append(VIEW_WIDTH).append("\" height=\"").append(ROW_HEIGHT)
                        .append("\" fill=\"").append(COLOR_ROW_ALT).append("\"/>");
            }

            // 轨道（整条灰底）
            sb.append("<rect x=\"").append(BAR_X).append("\" y=\"").append(barY)
                    .append("\" width=\"").append(BAR_MAX_WIDTH).append("\" height=\"").append(barH)
                    .append("\" rx=\"2\" fill=\"").append(COLOR_TRACK).append("\"/>");

            // 条宽：clamp >=0；归一化到 BAR_MAX_WIDTH；退化或负/零值给残桩。
            int barW;
            if (degenerate || value <= 0.0) {
                barW = BAR_STUB_WIDTH;
            } else {
                double ratio = value / maxValue;
                if (ratio > 1.0) {
                    ratio = 1.0;
                }
                barW = (int) Math.round(ratio * BAR_MAX_WIDTH);
                if (barW < BAR_STUB_WIDTH) {
                    barW = BAR_STUB_WIDTH;
                }
            }
            sb.append("<rect x=\"").append(BAR_X).append("\" y=\"").append(barY)
                    .append("\" width=\"").append(barW).append("\" height=\"").append(barH)
                    .append("\" rx=\"2\" fill=\"").append(COLOR_BAR).append("\"/>");

            // 左侧标签
            sb.append("<text x=\"").append(LABEL_X).append("\" y=\"").append(textY)
                    .append("\" font-size=\"12\" fill=\"").append(COLOR_TEXT)
                    .append("\">").append(label).append("</text>");

            // 右侧数值（始终打印真实数字，即便条是残桩）
            String valueText = escape(formatNumber(value) + (safeUnit.isEmpty() ? "" : " " + safeUnit));
            sb.append("<text x=\"").append(VIEW_WIDTH - 6).append("\" y=\"").append(textY)
                    .append("\" font-size=\"12\" text-anchor=\"end\" fill=\"").append(COLOR_SUBTEXT)
                    .append("\">").append(valueText).append("</text>");
        }

        sb.append("</svg>");
        return sb.toString();
    }

    /** 空数据占位 SVG。 */
    private static String emptyChart(String safeTitle) {
        int totalHeight = TITLE_HEIGHT + ROW_HEIGHT + PADDING_BOTTOM;
        StringBuilder sb = new StringBuilder(256);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(VIEW_WIDTH).append(' ').append(totalHeight)
                .append("\" style=\"width:100%;height:auto;font-family:system-ui,-apple-system,sans-serif\" ")
                .append("role=\"img\">");
        if (!safeTitle.isEmpty()) {
            sb.append("<text x=\"").append(LABEL_X).append("\" y=\"20\" font-size=\"14\" ")
                    .append("font-weight=\"600\" fill=\"").append(COLOR_TEXT)
                    .append("\">").append(safeTitle).append("</text>");
        }
        sb.append("<text x=\"").append(LABEL_X).append("\" y=\"")
                .append(TITLE_HEIGHT + 16).append("\" font-size=\"12\" fill=\"")
                .append(COLOR_SUBTEXT).append("\">(no data)</text>");
        sb.append("</svg>");
        return sb.toString();
    }

    /** 定区域三位小数格式化，避免逗号小数。 */
    private static String formatNumber(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** HTML / XML 转义：{@code & < > " '} 全覆盖，防 label 破坏 SVG / 页面。 */
    private static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
