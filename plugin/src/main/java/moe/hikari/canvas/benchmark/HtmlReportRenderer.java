package moe.hikari.canvas.benchmark;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把聚合好的 {@link BenchmarkReport} 渲染成<b>完全自包含</b>的 HTML5 报告（P3 产物）。
 *
 * <p>「给原料 + 公式，不给『你能开 N 个 wall』结论」（{@code PROPOSAL.md §5.2.7} /
 * {@code docs/dynamic-data.md §13.3}）：报告只摊开描述性测量（环境 / per-scene 分位 /
 * per-element 边际 / GC），结论部分交给服主用<b>自己</b>的 mspt 预算在内联 JS 计算器里实时试算。
 * 计算器的算法精确镜像 {@link BudgetFormula#availableMsPerSecond} /
 * {@link BudgetFormula#projectedMaxWalls}，并随身展示 {@link BudgetFormula#DISCLAIMER}（保守下界
 * 口径，避免被当成硬上限）。</p>
 *
 * <p>纯 headless：仅从 report 拼 String，无任何 Bukkit / Player / PacketEvents / NMS，无外部资源
 * （无 CDN script、无远端 link、无 web font）—— 全部内联 {@code <style>} + {@code <script>}。
 * 时间一律由 {@code report.generatedAtMillis()} 推得，<b>不</b>调用任何当前时钟 API。</p>
 */
public final class HtmlReportRenderer {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private HtmlReportRenderer() {
        // 工具类
    }

    /**
     * 渲染完整自包含 HTML 报告文档（以 {@code <!DOCTYPE html>} 开头）。
     *
     * @param report P2 聚合产出的报告（数据源）
     * @return 完整 HTML 文档字符串，可直接落盘成 {@code report.html}
     */
    public static String render(BenchmarkReport report) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"zh-CN\">\n");
        appendHead(sb);
        sb.append("<body>\n<main class=\"wrap\">\n");

        appendHeader(sb, report);
        appendEnvCard(sb, report);
        appendConfigCard(sb, report);
        appendSceneTable(sb, report);
        appendSceneChart(sb, report);
        appendPerElementChart(sb, report);
        appendGcLine(sb, report);
        appendCalculator(sb, report);
        appendFooter(sb);

        sb.append("</main>\n</body>\n</html>\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- head

    private static void appendHead(StringBuilder sb) {
        sb.append("<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>HikariCanvas Benchmark Report</title>\n");
        sb.append("<style>\n").append(css()).append("\n</style>\n");
        sb.append("</head>\n");
    }

    /** Catppuccin Latte 浅色主题；数字表用系统等宽字体栈；蓝 #1e66f5 / mauve #8839ef accent。 */
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

    // ---------------------------------------------------------------- header

    private static void appendHeader(StringBuilder sb, BenchmarkReport report) {
        String when = TIME_FMT.format(Instant.ofEpochMilli(report.generatedAtMillis()));
        sb.append("<h1>HikariCanvas Benchmark Report</h1>\n");
        sb.append("<p class=\"sub\">generated ").append(esc(when))
                .append(" &middot; schema v").append(report.schemaVersion()).append("</p>\n");
    }

    // ---------------------------------------------------------------- env

    private static void appendEnvCard(StringBuilder sb, BenchmarkReport report) {
        EnvInfo env = report.env();
        String xmx = env.maxHeapMb() < 0 ? "?" : String.format(Locale.ROOT, "%d MB", env.maxHeapMb());
        sb.append("<section class=\"card\">\n<h2>运行环境</h2>\n<dl class=\"kv\">\n");
        kv(sb, "Java 版本", env.javaVersion());
        kv(sb, "JVM", env.jvmName());
        kv(sb, "OS / Arch", env.osName() + " / " + env.osArch());
        kv(sb, "可用处理器", String.valueOf(env.availableProcessors()));
        kv(sb, "最大堆 (Xmx)", xmx);
        kv(sb, "GC", env.gcNames().isEmpty() ? "?" : String.join(", ", env.gcNames()));
        sb.append("</dl>\n");
        sb.append("<p class=\"note\">数据透明：这些数字仅代表<b>这台机器、这个 JVM</b> 的测量结果——")
                .append("换硬件 / 堆大小 / GC 算法后须重新压测，不可跨机直接套用。</p>\n");
        sb.append("</section>\n");
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("<dt>").append(esc(k)).append("</dt><dd>").append(esc(v)).append("</dd>\n");
    }

    // ---------------------------------------------------------------- config

    private static void appendConfigCard(StringBuilder sb, BenchmarkReport report) {
        BenchmarkConfig cfg = report.config();
        sb.append("<section class=\"card\">\n<h2>压测配置</h2>\n<dl class=\"kv\">\n");
        kv(sb, "测量轮数", String.valueOf(cfg.measuredIterations()));
        kv(sb, "预热轮数", String.valueOf(cfg.warmupIterations()));
        kv(sb, "fps 档", joinInts(cfg.fpsValues()));
        kv(sb, "viewer 档", joinInts(cfg.viewerCounts()));
        kv(sb, "场景选择", cfg.sceneSelector());
        kv(sb, "空白基线", String.format(Locale.ROOT, "%.3f ms", report.blankBaselineMs()));
        sb.append("</dl>\n");
        sb.append("<p class=\"note\">fps / viewer 是 <b>P3 公式参数</b>，不参与测量——")
                .append("rasterize 成本本身不依赖 fps / viewer（为每个组合重复测量是测同一个东西）。</p>\n");
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

    // ---------------------------------------------------------------- scene table

    private static void appendSceneTable(StringBuilder sb, BenchmarkReport report) {
        sb.append("<section class=\"card\">\n<h2>各场景成本</h2>\n");
        sb.append("<table>\n<thead>\n<tr>")
                .append("<th>scene id</th>")
                .append("<th>raster p50</th><th>p95</th><th>p99</th>")
                .append("<th>palette p50</th><th>p95</th><th>p99</th>")
                .append("<th>alloc MB/it</th><th>elements</th><th>tiles</th>")
                .append("</tr>\n</thead>\n<tbody>\n");
        for (SceneResult s : report.scenes()) {
            Percentiles r = s.rasterizeMs();
            Percentiles p = s.paletteMs();
            String alloc = s.allocSupported()
                    ? num(s.meanAllocMbPerIter())
                    : "n/a";
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

    // ---------------------------------------------------------------- scene chart

    private static void appendSceneChart(StringBuilder sb, BenchmarkReport report) {
        List<SceneResult> sorted = new ArrayList<>(report.scenes());
        sorted.sort(Comparator.comparingDouble((SceneResult s) -> s.rasterizeMs().p95()).reversed());
        List<SvgBarChart.Bar> bars = new ArrayList<>(sorted.size());
        for (SceneResult s : sorted) {
            bars.add(new SvgBarChart.Bar(s.sceneId(), s.rasterizeMs().p95()));
        }
        sb.append("<section class=\"card\">\n");
        sb.append(SvgBarChart.horizontal("rasterize p95 by scene (ms)", bars, "ms"));
        sb.append("\n</section>\n");
    }

    // ---------------------------------------------------------------- per-element chart

    private static void appendPerElementChart(StringBuilder sb, BenchmarkReport report) {
        List<PerElementCost> costs = report.perElement();
        if (costs == null || costs.isEmpty()) {
            return;
        }
        // 统计每个 elementType 出现次数：>1 时在 label 后缀 sceneId 区分（如两个 text 场景）。
        Map<String, Integer> typeCount = new HashMap<>();
        for (PerElementCost c : costs) {
            typeCount.merge(c.elementType(), 1, Integer::sum);
        }
        List<SvgBarChart.Bar> bars = new ArrayList<>(costs.size());
        for (PerElementCost c : costs) {
            String label = typeCount.getOrDefault(c.elementType(), 1) > 1
                    ? c.elementType() + "(" + c.sceneId() + ")"
                    : c.elementType();
            bars.add(new SvgBarChart.Bar(label, c.marginalMsPerElement()));
        }
        sb.append("<section class=\"card\">\n");
        sb.append(SvgBarChart.horizontal("per-element marginal cost (ms/element)", bars, "ms/elem"));
        sb.append("\n</section>\n");
    }

    // ---------------------------------------------------------------- gc

    private static void appendGcLine(StringBuilder sb, BenchmarkReport report) {
        GcSummary gc = report.gc();
        sb.append("<section class=\"card\">\n<h2>GC 增量</h2>\n");
        sb.append("<p><b>").append(gc.collectionCount()).append("</b> 次回收，累计 <b>")
                .append(gc.collectionTimeMs()).append("</b> ms（覆盖整段 run，含 warmup 迭代）。</p>\n");
        sb.append("</section>\n");
    }

    // ---------------------------------------------------------------- calculator

    private static void appendCalculator(StringBuilder sb, BenchmarkReport report) {
        BudgetFormula.Inputs d = BudgetFormula.Inputs.defaults();
        sb.append("<section class=\"card\">\n<h2>50mspt 预算计算器</h2>\n");
        sb.append("<p class=\"sub\" style=\"margin-bottom:14px\">")
                .append("代入<b>你自己</b>的预算参数实时试算每个场景的「可载 wall 数」保守下界。</p>\n");

        // 表单
        sb.append("<div class=\"calc-form\">\n");
        numberInput(sb, "calc-mspt", "mspt 预算 (ms)", d.msptBudgetMs(), "1", "0");
        numberInput(sb, "calc-tps", "目标 tps", d.tps(), "1", "1");
        numberInput(sb, "calc-share", "主线程份额 (%)", d.mainThreadSharePct(), "1", "0");
        numberInput(sb, "calc-fps", "目标 fps", d.fps(), "1", "1");
        sb.append("</div>\n");

        // 公式 + disclaimer
        sb.append("<p class=\"formula\">可用预算 = mspt &times; tps &times; 份额% &nbsp;&nbsp;|&nbsp;&nbsp;")
                .append("可载 wall &asymp; 可用预算 &divide; (p95 &times; fps)</p>\n");
        sb.append("<p class=\"disclaimer\">").append(esc(BudgetFormula.DISCLAIMER)).append("</p>\n");

        // 结果表
        sb.append("<table style=\"margin-top:16px\">\n<thead>\n<tr>")
                .append("<th>scene id</th><th>rasterize p95 (ms)</th><th>可载 wall 数</th>")
                .append("</tr>\n</thead>\n<tbody>\n");
        for (SceneResult s : report.scenes()) {
            sb.append("<tr data-scene=\"").append(esc(s.sceneId())).append("\">")
                    .append("<td>").append(esc(s.sceneId())).append("</td>")
                    .append("<td>").append(num(s.rasterizeMs().p95())).append("</td>")
                    .append("<td class=\"walls\">—</td>")
                    .append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n");

        // 内联脚本：精确镜像 BudgetFormula.availableMsPerSecond + projectedMaxWalls
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
        // 镜像 availableMsPerSecond = mspt * tps * (share/100)
        sb.append("  var availableMs=mspt*tps*(share/100);\n");
        sb.append("  var rows=document.querySelectorAll('tr[data-scene]');\n");
        sb.append("  for(var i=0;i<scenes.length;i++){\n");
        sb.append("    var s=scenes[i];\n");
        // 镜像 projectedMaxWalls：cost<=0 -> Infinity，否则 availableMs/cost
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

    /** 构造 per-scene 的 JS 数组字面量 {@code [{id:"…",p95:<double>}, …]}（id 双重转义）。 */
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

    // ---------------------------------------------------------------- footer

    private static void appendFooter(StringBuilder sb) {
        sb.append("<p class=\"foot\">HikariCanvas Benchmark &middot; 给原料 + 公式，不给结论 ")
                .append("&mdash; 服主用自己的预算自己判断。</p>\n");
    }

    // ---------------------------------------------------------------- helpers

    /** 格式化数值：%.3f，固定 Locale.ROOT（点小数 + 无千分位）。 */
    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** JS 数值字面量：有限值原样（Locale.ROOT 点小数），非有限值降级 0。 */
    private static String jsNum(double v) {
        if (!Double.isFinite(v)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%s", v);
    }

    /**
     * HTML 转义：处理 {@code & < > " '} 五个字符——所有放进 HTML 正文 / 属性 / JS 字符串字面量
     * （配合 {@link #jsStr}）的动态字符串都必须过这个。
     */
    private static String esc(String s) {
        if (s == null) {
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

    /**
     * JS 字符串字面量转义：用于内嵌进 {@code "…"} 的 JS 上下文（计算器数据数组的 id）。
     * 先按 JS 规则转义反斜杠 / 引号 / 控制字符 / {@code <}（防 {@code </script>} 提前闭合），
     * 该结果再被放进 HTML {@code <script>} 块——脚本块内 HTML 实体不解析，故不能用 {@link #esc}。
     */
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
