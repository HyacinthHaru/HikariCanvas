package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.benchmark.BenchmarkReport.EnvInfo;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.GcSummary;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.PerElementCost;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.Percentiles;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.SceneResult;
import ac.haru.hikaricanvas.i18n.Messages;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 将 {@link BenchmarkReport} 渲染为完全自包含的 HTML5 报告（内联样式、SVG 横向条形图与交互式性能预算计算器，
 * 零外部脚本/样式/字体依赖；见 {@code docs/benchmark.md §3-§4}）。
 *
 * <p>文案按 {@code messages.defaultLocale()} 从 {@code command.bench.report-html.*} 读取；
 * 生成时间完全取自 {@code report.generatedAtMillis()}，不读取系统时钟。</p>
 */
public final class HtmlReportRenderer {

    /**
     * 交互式性能预算计算器的默认参数载体（对应 {@code docs/benchmark.md §4}：
     * {@code 可用预算 = mspt × tps × share%}，{@code 可载墙数 = 可用预算 / (p95 × fps)}）。
     *
     * @param msptBudgetMs       每 tick 预算毫秒（≤0 时归一为 50）
     * @param tps                目标 tps（≤0 时归一为 20）
     * @param mainThreadSharePct 分给本插件的预算份额百分比（越界时归一为 30）
     * @param fps                目标刷新帧率（≤0 时归一为 1）
     */
    public record Inputs(double msptBudgetMs, int tps, double mainThreadSharePct, int fps) {
        public Inputs {
            if (msptBudgetMs <= 0) msptBudgetMs = 50.0;
            if (tps <= 0) tps = 20;
            if (mainThreadSharePct <= 0 || mainThreadSharePct > 100) mainThreadSharePct = 30.0;
            if (fps <= 0) fps = 1;
        }

        /** 默认预算输入：50 mspt / 20 tps / 30% 份额 / 5 fps。 */
        public static Inputs defaults() {
            return new Inputs(50.0, 20, 30.0, 5);
        }
    }

    /** 内联 SVG 横向条形图的单行数据项。 */
    record Bar(String label, double value) {}

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    // SVG 条形图配色（Catppuccin Latte）与 viewBox 布局常量
    private static final String COLOR_BAR = "#1e66f5";
    private static final String COLOR_TEXT = "#4c4f69";
    private static final String COLOR_SUBTEXT = "#6c6f85";
    private static final String COLOR_TRACK = "#ccd0da";
    private static final String COLOR_ROW_ALT = "#eff1f5";

    private static final int VIEW_WIDTH = 640;
    private static final int ROW_HEIGHT = 22;
    private static final int TITLE_HEIGHT = 30;
    private static final int PADDING_TOP = 6;
    private static final int PADDING_BOTTOM = 8;
    private static final int LABEL_X = 8;
    private static final int LABEL_WIDTH = 180;
    private static final int VALUE_WIDTH = 96;
    private static final int BAR_X = LABEL_X + LABEL_WIDTH;
    private static final int BAR_MAX_WIDTH = VIEW_WIDTH - BAR_X - VALUE_WIDTH;
    private static final int BAR_STUB_WIDTH = 2;
    private static final int BAR_INSET = 4;

    private HtmlReportRenderer() {
    }

    /**
     * 渲染完整自包含 HTML5 报告文档。
     *
     * @param report   聚合报告数据源
     * @param messages 国际化文案中枢（按 {@code messages.defaultLocale()} 取文案）
     * @return 完整 HTML 字符串
     */
    public static String render(BenchmarkReport report, Messages messages) {
        String loc = messages.defaultLocale();
        Function<String, String> t = k -> {
            String s = messages.rawOrNull(loc, "command.bench.report-html." + k);
            return s == null ? k : s;
        };

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"").append(htmlLang(loc)).append("\">\n");
        appendHead(sb, t);
        sb.append("<body>\n<main class=\"wrap\">\n");

        appendHeader(sb, report, t);
        appendEnvCard(sb, report, t);
        appendConfigCard(sb, report, t);
        appendSceneTable(sb, report, t);
        appendSceneChart(sb, report, t);
        appendPerElementChart(sb, report, t);
        appendGcLine(sb, report, t);
        appendCalculator(sb, report, t);
        appendFooter(sb, t);

        sb.append("</main>\n</body>\n</html>\n");
        return sb.toString();
    }

    /** 生成响应式内联横向条形图 {@code <svg>…</svg>} 片段（空列表或非正极值安全降级）。 */
    static String horizontalBarChart(String title, List<Bar> bars, String unit) {
        String safeUnit = unit == null ? "" : unit;
        String safeTitle = esc(title == null ? "" : title);

        if (bars == null || bars.isEmpty()) {
            return emptyChart(safeTitle);
        }

        double maxValue = 0.0;
        for (Bar b : bars) {
            double v = b == null ? 0.0 : b.value();
            if (Double.isFinite(v) && v > maxValue) {
                maxValue = v;
            }
        }
        boolean degenerate = !(maxValue > 0.0);

        int rows = bars.size();
        int totalHeight = TITLE_HEIGHT + PADDING_TOP + rows * ROW_HEIGHT + PADDING_BOTTOM;

        StringBuilder sb = new StringBuilder(512 + rows * 256);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(VIEW_WIDTH).append(' ').append(totalHeight)
                .append("\" style=\"width:100%;height:auto;font-family:system-ui,-apple-system,sans-serif\" ")
                .append("role=\"img\">");

        sb.append("<text x=\"").append(LABEL_X).append("\" y=\"20\" ")
                .append("font-size=\"14\" font-weight=\"600\" fill=\"").append(COLOR_TEXT)
                .append("\">").append(safeTitle).append("</text>");

        int baseY = TITLE_HEIGHT + PADDING_TOP;
        for (int i = 0; i < rows; i++) {
            Bar b = bars.get(i);
            String label = esc(b == null ? "" : b.label());
            double value = b == null ? 0.0 : b.value();
            if (!Double.isFinite(value)) {
                value = 0.0;
            }

            int rowY = baseY + i * ROW_HEIGHT;
            int textY = rowY + ROW_HEIGHT - 7;
            int barY = rowY + BAR_INSET;
            int barH = ROW_HEIGHT - BAR_INSET * 2;

            if ((i & 1) == 1) {
                sb.append("<rect x=\"0\" y=\"").append(rowY).append("\" width=\"")
                        .append(VIEW_WIDTH).append("\" height=\"").append(ROW_HEIGHT)
                        .append("\" fill=\"").append(COLOR_ROW_ALT).append("\"/>");
            }

            sb.append("<rect x=\"").append(BAR_X).append("\" y=\"").append(barY)
                    .append("\" width=\"").append(BAR_MAX_WIDTH).append("\" height=\"").append(barH)
                    .append("\" rx=\"2\" fill=\"").append(COLOR_TRACK).append("\"/>");

            int barW;
            if (degenerate || value <= 0.0) {
                barW = BAR_STUB_WIDTH;
            } else {
                double ratio = Math.min(1.0, value / maxValue);
                barW = Math.max(BAR_STUB_WIDTH, (int) Math.round(ratio * BAR_MAX_WIDTH));
            }
            sb.append("<rect x=\"").append(BAR_X).append("\" y=\"").append(barY)
                    .append("\" width=\"").append(barW).append("\" height=\"").append(barH)
                    .append("\" rx=\"2\" fill=\"").append(COLOR_BAR).append("\"/>");

            sb.append("<text x=\"").append(LABEL_X).append("\" y=\"").append(textY)
                    .append("\" font-size=\"12\" fill=\"").append(COLOR_TEXT)
                    .append("\">").append(label).append("</text>");

            String valueText = esc(num(value) + (safeUnit.isEmpty() ? "" : " " + safeUnit));
            sb.append("<text x=\"").append(VIEW_WIDTH - 6).append("\" y=\"").append(textY)
                    .append("\" font-size=\"12\" text-anchor=\"end\" fill=\"").append(COLOR_SUBTEXT)
                    .append("\">").append(valueText).append("</text>");
        }

        sb.append("</svg>");
        return sb.toString();
    }

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

    private static String htmlLang(String loc) {
        return (loc != null && loc.toLowerCase(Locale.ROOT).startsWith("zh")) ? "zh-CN" : "en";
    }

    private static void appendHead(StringBuilder sb, Function<String, String> t) {
        sb.append("<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>").append(esc(t.apply("title"))).append("</title>\n");
        sb.append("<style>\n").append(css()).append("\n</style>\n");
        sb.append("</head>\n");
    }

    private static String css() {
        return ":root{--bg:#eff1f5;--text:#4c4f69;--subtext:#6c6f85;--card:#ffffff;"
                + "--border:#ccd0da;--blue:#1e66f5;--mauve:#8839ef;--zebra:#f5f6f8;--crust:#dce0e8;}"
                + "*{box-sizing:border-box;}"
                + "body{margin:0;background:var(--bg);color:var(--text);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "line-height:1.5;}"
                + ".wrap{max-width:1040px;margin:0 auto;padding:32px 20px 64px;}"
                + "h1{font-size:1.7rem;margin:0 0 4px;color:var(--text);}"
                + "h2{font-size:1.2rem;margin:0 0 12px;color:var(--mauve);}"
                + ".sub{color:var(--subtext);font-size:.9rem;margin:0 0 24px;}"
                + ".card{background:var(--card);border:1px solid var(--border);border-radius:10px;"
                + "padding:18px 20px;margin:0 0 22px;}"
                + ".note{color:var(--subtext);font-size:.85rem;margin:10px 0 0;"
                + "border-left:3px solid var(--blue);padding-left:10px;}"
                + ".disclaimer{color:var(--text);font-size:.9rem;margin:14px 0 0;"
                + "background:var(--zebra);border:1px solid var(--border);border-left:4px solid var(--mauve);"
                + "border-radius:8px;padding:12px 14px;}"
                + ".formula{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace;"
                + "font-size:.88rem;color:var(--blue);margin:10px 0 0;}"
                + "dl.kv{display:grid;grid-template-columns:max-content 1fr;gap:6px 18px;margin:0;}"
                + "dl.kv dt{color:var(--subtext);}"
                + "dl.kv dd{margin:0;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}"
                + "table{width:100%;border-collapse:collapse;font-size:.85rem;"
                + "font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace;}"
                + "th,td{padding:6px 9px;border-bottom:1px solid var(--crust);text-align:right;white-space:nowrap;}"
                + "th{color:var(--subtext);font-weight:600;border-bottom:2px solid var(--border);}"
                + "th:first-child,td:first-child{text-align:left;}"
                + "tbody tr:nth-child(odd){background:var(--zebra);}"
                + ".num{text-align:right;}"
                + "svg{display:block;max-width:100%;height:auto;margin-top:6px;}"
                + ".calc-form{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));"
                + "gap:14px;margin:0 0 16px;}"
                + ".calc-form label{display:flex;flex-direction:column;gap:4px;font-size:.85rem;color:var(--subtext);}"
                + ".calc-form input{padding:7px 9px;border:1px solid var(--border);border-radius:6px;"
                + "background:#fff;color:var(--text);font-size:.95rem;"
                + "font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}"
                + ".calc-form input:focus{outline:2px solid var(--blue);outline-offset:-1px;}"
                + ".foot{color:var(--subtext);font-size:.85rem;text-align:center;margin:36px 0 0;}";
    }

    private static void appendHeader(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        String when = TIME_FMT.format(Instant.ofEpochMilli(report.generatedAtMillis()));
        sb.append("<h1>").append(esc(t.apply("title"))).append("</h1>\n");
        sb.append("<p class=\"sub\">").append(esc(t.apply("header-generated"))).append(' ').append(esc(when))
                .append(" &middot; ").append(esc(t.apply("header-format"))).append(report.schemaVersion())
                .append("</p>\n");
    }

    private static void appendEnvCard(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        EnvInfo env = report.env();
        String xmx = env.maxHeapMb() < 0 ? "?" : String.format(Locale.ROOT, "%d MB", env.maxHeapMb());
        sb.append("<section class=\"card\">\n<h2>").append(esc(t.apply("env-title"))).append("</h2>\n<dl class=\"kv\">\n");
        kv(sb, t.apply("env-java"), env.javaVersion());
        kv(sb, t.apply("env-os"), env.osName() + " / " + env.osArch());
        kv(sb, t.apply("env-cpu"), String.valueOf(env.availableProcessors()));
        kv(sb, t.apply("env-mem"), xmx);
        kv(sb, t.apply("env-gc"), env.gcNames().isEmpty() ? "?" : String.join(", ", env.gcNames()));
        sb.append("</dl>\n");
        sb.append("<p class=\"note\">").append(esc(t.apply("env-note"))).append("</p>\n");
        sb.append("</section>\n");
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("<dt>").append(esc(k)).append("</dt><dd>").append(esc(v)).append("</dd>\n");
    }

    private static void appendConfigCard(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        BenchmarkConfig cfg = report.config();
        sb.append("<section class=\"card\">\n<h2>").append(esc(t.apply("config-title"))).append("</h2>\n<dl class=\"kv\">\n");
        kv(sb, t.apply("config-measured"), String.valueOf(cfg.measuredIterations()));
        kv(sb, t.apply("config-warmup"), String.valueOf(cfg.warmupIterations()));
        kv(sb, t.apply("config-fps"), joinInts(cfg.fpsValues()));
        kv(sb, t.apply("config-viewers"), joinInts(cfg.viewerCounts()));
        kv(sb, t.apply("config-selector"), cfg.sceneSelector());
        kv(sb, t.apply("config-blank"), String.format(Locale.ROOT, "%.3f ms", report.blankBaselineMs()));
        sb.append("</dl>\n");
        sb.append("<p class=\"note\">").append(esc(t.apply("config-note"))).append("</p>\n");
        sb.append("</section>\n");
    }

    private static String joinInts(List<Integer> xs) {
        if (xs == null || xs.isEmpty()) {
            return "—";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(xs.get(i));
        }
        return b.toString();
    }

    private static void appendSceneTable(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        sb.append("<section class=\"card\">\n<h2>").append(esc(t.apply("scenes-title"))).append("</h2>\n");
        sb.append("<table>\n<thead>\n<tr>")
                .append("<th>").append(esc(t.apply("th-scene"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-render-typical"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-slower"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-slowest"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-palette-typical"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-slower"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-slowest"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-mem"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-elements"))).append("</th>")
                .append("<th>").append(esc(t.apply("th-tiles"))).append("</th>")
                .append("</tr>\n</thead>\n<tbody>\n");
        for (SceneResult s : report.scenes()) {
            Percentiles r = s.rasterizeMs();
            Percentiles p = s.paletteMs();
            String alloc = s.allocSupported() ? num(s.meanAllocMbPerIter()) : "n/a";
            sb.append("<tr>")
                    .append("<td>").append(esc(s.sceneId())).append("</td>")
                    .append("<td>").append(num(r.p50())).append("</td>")
                    .append("<td>").append(num(r.p95())).append("</td>")
                    .append("<td>").append(num(r.p99())).append("</td>")
                    .append("<td>").append(num(p.p50())).append("</td>")
                    .append("<td>").append(num(p.p95())).append("</td>")
                    .append("<td>").append(num(p.p99())).append("</td>")
                    .append("<td>").append(alloc).append("</td>")
                    .append("<td>").append(s.elementCount()).append("</td>")
                    .append("<td>").append(s.tileCount()).append("</td>")
                    .append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n</section>\n");
    }

    private static void appendSceneChart(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        List<SceneResult> sorted = new ArrayList<>(report.scenes());
        sorted.sort(Comparator.comparingDouble((SceneResult s) -> s.rasterizeMs().p95()).reversed());
        List<Bar> bars = new ArrayList<>(sorted.size());
        for (SceneResult s : sorted) {
            bars.add(new Bar(s.sceneId(), s.rasterizeMs().p95()));
        }
        sb.append("<section class=\"card\">\n");
        sb.append(horizontalBarChart(t.apply("chart-scene"), bars, "ms"));
        sb.append("\n</section>\n");
    }

    private static void appendPerElementChart(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        List<PerElementCost> costs = report.perElement();
        if (costs == null || costs.isEmpty()) {
            return;
        }
        Map<String, Integer> typeCount = new HashMap<>();
        for (PerElementCost c : costs) {
            typeCount.merge(c.elementType(), 1, Integer::sum);
        }
        List<Bar> bars = new ArrayList<>(costs.size());
        for (PerElementCost c : costs) {
            String label = typeCount.getOrDefault(c.elementType(), 1) > 1
                    ? c.elementType() + "(" + c.sceneId() + ")"
                    : c.elementType();
            bars.add(new Bar(label, c.marginalMsPerElement()));
        }
        sb.append("<section class=\"card\">\n");
        sb.append(horizontalBarChart(t.apply("chart-element"), bars, "ms"));
        sb.append("\n</section>\n");
    }

    private static void appendGcLine(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        GcSummary gc = report.gc();
        sb.append("<section class=\"card\">\n<h2>").append(esc(t.apply("gc-title"))).append("</h2>\n");
        sb.append("<p>").append(esc(t.apply("gc-prefix"))).append("<b>").append(gc.collectionCount())
                .append("</b>").append(esc(t.apply("gc-mid"))).append("<b>").append(gc.collectionTimeMs())
                .append("</b>").append(esc(t.apply("gc-suffix"))).append("</p>\n");
        sb.append("</section>\n");
    }

    private static void appendCalculator(StringBuilder sb, BenchmarkReport report, Function<String, String> t) {
        Inputs d = Inputs.defaults();
        sb.append("<section class=\"card\">\n<h2>").append(esc(t.apply("calc-title"))).append("</h2>\n");
        sb.append("<p class=\"sub\" style=\"margin-bottom:14px\">")
                .append(esc(t.apply("calc-intro"))).append("</p>\n");

        sb.append("<div class=\"calc-form\">\n");
        numberInput(sb, "calc-mspt", t.apply("calc-mspt"), d.msptBudgetMs(), "1", "0");
        numberInput(sb, "calc-tps", t.apply("calc-tps"), d.tps(), "1", "1");
        numberInput(sb, "calc-share", t.apply("calc-share"), d.mainThreadSharePct(), "1", "0");
        numberInput(sb, "calc-fps", t.apply("calc-fps"), d.fps(), "1", "1");
        sb.append("</div>\n");

        sb.append("<p class=\"formula\">").append(t.apply("calc-formula")).append("</p>\n");
        sb.append("<p class=\"disclaimer\">").append(esc(t.apply("disclaimer"))).append("</p>\n");

        sb.append("<table style=\"margin-top:16px\">\n<thead>\n<tr>")
                .append("<th>").append(esc(t.apply("th-scene"))).append("</th>")
                .append("<th>").append(esc(t.apply("calc-th-cost"))).append("</th>")
                .append("<th>").append(esc(t.apply("calc-th-walls"))).append("</th>")
                .append("</tr>\n</thead>\n<tbody>\n");
        for (SceneResult s : report.scenes()) {
            sb.append("<tr data-scene=\"").append(esc(s.sceneId())).append("\">")
                    .append("<td>").append(esc(s.sceneId())).append("</td>")
                    .append("<td>").append(num(s.rasterizeMs().p95())).append("</td>")
                    .append("<td class=\"walls\">—</td>")
                    .append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n");

        sb.append("<script>\n");
        sb.append("(function(){\n");
        sb.append("var scenes=").append(scenesJsLiteral(report)).append(";\n");
        sb.append("var inputs=['calc-mspt','calc-tps','calc-share','calc-fps'].map(function(id){return document.getElementById(id);});\n");
        sb.append("function val(el,def){var n=parseFloat(el.value);return (isFinite(n))?n:def;}\n");
        sb.append("function recompute(){\n");
        sb.append("  var mspt=val(inputs[0],").append(jsNum(d.msptBudgetMs())).append(");\n");
        sb.append("  var tps=val(inputs[1],").append(jsNum(d.tps())).append(");\n");
        sb.append("  var share=val(inputs[2],").append(jsNum(d.mainThreadSharePct())).append(");\n");
        sb.append("  var fps=val(inputs[3],").append(jsNum(d.fps())).append(");\n");
        sb.append("  var availableMs=mspt*tps*(share/100);\n");
        sb.append("  var rows=document.querySelectorAll('tr[data-scene]');\n");
        sb.append("  for(var i=0;i<scenes.length;i++){\n");
        sb.append("    var s=scenes[i];\n");
        sb.append("    var cost=s.p95*fps;\n");
        sb.append("    var walls=(cost<=0)?Infinity:availableMs/cost;\n");
        sb.append("    var cell=rows[i]?rows[i].querySelector('.walls'):null;\n");
        sb.append("    if(cell){cell.textContent=(walls===Infinity)?'\\u221e':String(Math.floor(walls));}\n");
        sb.append("  }\n");
        sb.append("}\n");
        sb.append("inputs.forEach(function(el){el.addEventListener('input',recompute);});\n");
        sb.append("recompute();\n");
        sb.append("})();\n");
        sb.append("</script>\n");
        sb.append("</section>\n");
    }

    private static void numberInput(StringBuilder sb, String id, String label,
                                    double value, String step, String min) {
        sb.append("<label>").append(esc(label))
                .append("<input type=\"number\" id=\"").append(esc(id)).append("\" value=\"")
                .append(jsNum(value)).append("\" step=\"").append(esc(step))
                .append("\" min=\"").append(esc(min)).append("\"></label>\n");
    }

    private static String scenesJsLiteral(BenchmarkReport report) {
        StringBuilder b = new StringBuilder();
        b.append('[');
        List<SceneResult> scenes = report.scenes();
        for (int i = 0; i < scenes.size(); i++) {
            SceneResult s = scenes.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{id:\"").append(jsStr(s.sceneId())).append("\",p95:")
                    .append(jsNum(s.rasterizeMs().p95())).append('}');
        }
        b.append(']');
        return b.toString();
    }

    private static void appendFooter(StringBuilder sb, Function<String, String> t) {
        sb.append("<p class=\"foot\">").append(esc(t.apply("footer"))).append("</p>\n");
    }

    /** 固定 {@link Locale#ROOT} 三位小数格式化（避免逗号小数破坏表格或 SVG）。 */
    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String jsNum(double v) {
        if (!Double.isFinite(v)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%s", v);
    }

    /** HTML / XML 实体转义（覆盖 {@code & < > " '}），供表格、属性与内联 SVG 共享。 */
    private static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** JS 双引号字符串字面量转义（含 {@code \u003c} 防 {@code </script>} 闭合逃逸）。 */
    private static String jsStr(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\'' -> b.append("\\'");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '<' -> b.append("\\u003c");
                case '>' -> b.append("\\u003e");
                case '&' -> b.append("\\u0026");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
