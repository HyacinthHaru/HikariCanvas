package moe.hikari.canvas.canvasfile;

import moe.hikari.canvas.render.FontRegistry;
import moe.hikari.canvas.render.IconRegistry;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.variable.VariableStore;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.8 Part A review 补缺：导入 {@code .canvas} 工程时扫缺资源（字体 / 图标 / 变量）并提示。
 *
 * <p>contract {@code docs/import-export.md §3.2 step 8}。{@link ProjectImporter#importInto} 在
 * {@link ProjectMaterializer#materialize} 之后、{@code replaceProject} 之前调用，把产出的
 * {@link ImportWarning} 并入导入结果的 warnings。导入照常完成——这些都是<b>非致命提示</b>，
 * 运行期对缺资源自动降级（缺字体→默认字体、缺 userglobal 变量→{@code ???} 占位、缺 user 图标→留空），
 * scanner 只负责在导入时<b>提前告诉用户哪些资源缺了</b>（跨服分享 {@code .canvas} 尤其有用）。</p>
 *
 * <h2>三类扫描</h2>
 * <ul>
 *   <li><b>missing-font</b>：所有 {@link TextElement#fontId()}（非 null）查 {@link FontRegistry#get}；
 *       null 表示用户没指定字体（渲染期走默认），不报。去重。</li>
 *   <li><b>missing-icon</b>：所有 {@link IconElement#source()} 中<b>仅 {@code user/<id>} 形态</b>查
 *       {@link IconRegistry#isRegistered}；内置 {@code fa-solid/*} / {@code fa-regular/*} /
 *       {@code fa-brands/*} 恒在不查（CI 上 jar 可能未装内置图标包，但绝不是「缺」），legacy
 *       不含 {@code /} 的 PNG 形态走 {@code TemplateAssetService}、不在本扫描范围。去重。</li>
 *   <li><b>missing-variable</b>：所有含文本元素提取 {@code ${var:...}} 引用，<b>仅判定
 *       {@code userglobal/<key>} 引用</b>（全局变量表存在性最可靠，且跨服分享最容易缺）查
 *       {@link VariableStore#get}。去重。</li>
 * </ul>
 *
 * <h2>变量判定范围与限制（重要）</h2>
 * <p>变量存在性判定按 namespace 复杂度分级，<b>只报 {@code userglobal}</b>，其余一律不报以<b>避免误报</b>：</p>
 * <ul>
 *   <li>{@code userglobal/X} — 全服共享、内部 fullName 即 {@code userglobal/X}（无 wallId 注入），
 *       直查 store 命中即「有」、否则「缺」。最可靠，且跨服分享时本服没建对应全局变量是真·缺。<b>报</b>。</li>
 *   <li>{@code user/X} — wall-scoped，内部注入成 {@code user:<wallId>/X}；导入到新墙本就需要用户重设
 *       这些值，不算「缺」。<b>不报</b>。</li>
 *   <li>{@code schedule.*} / {@code wall.*} / {@code scoreboard.*} / {@code rail*} / {@code server.*}
 *       等 provider 提供的 namespace — 由本服对应 Provider 在运行期动态 resolve（{@code notifyDynamicLookup}
 *       hook），导入时 store 里多半还没有这些 key，但只要本服装了 provider 就能 resolve；此时报「缺」
 *       属误报。<b>不报</b>。</li>
 * </ul>
 * <p>故 {@code missing-variable} 的语义是<b>「引用了本服没有的全局用户变量」</b>，不覆盖其它 namespace。
 * {@code ${var:...}} 的提取复用 {@link #PLACEHOLDER_RE}（与 {@code VariableInterpolator.PLACEHOLDER}
 * 同一占位符文法：{@code ${var:NAME}} 或 {@code ${var:NAME|fallback=...}}）。</p>
 *
 * <h2>可空降级（best-effort）</h2>
 * <p>三件 registry 任一为 {@code null} → 跳过对应那类扫描（与 {@link ProjectImporter} 现有
 * {@code auditLog} / {@code throttler} 可空范式一致），保持测试可裸装配。</p>
 *
 * <h2>线程</h2>
 * <p>纯读 registry + 遍历不可变 {@link ProjectState} 树，无可变状态；registry 的存在性查询自身
 * 线程安全。</p>
 */
public final class MissingResourceScanner {

    /**
     * {@code ${var:...}} 占位符提取正则。与 {@code VariableInterpolator.PLACEHOLDER} 文法一致：
     * {@code group(1)} = 变量名（不含 {@code |} / {@code }}）；可选 {@code |fallback=...} 段不捕获用于范围。
     * 这里只需名字（group 1）来判 namespace + 查 userglobal 存在性，不做实际插值。
     */
    private static final Pattern PLACEHOLDER_RE = Pattern.compile(
            "\\$\\{var:([^|}]+)(?:\\|fallback=[^}]*)?\\}");

    /** userglobal 引用前缀：{@code ${var:userglobal/<key>}} → 内部 fullName 即 {@code userglobal/<key>}。 */
    private static final String USERGLOBAL_PREFIX = VariableStore.USER_GLOBAL_NAMESPACE + "/";

    @Nullable
    private final FontRegistry fontRegistry;
    @Nullable
    private final IconRegistry iconRegistry;
    @Nullable
    private final VariableStore variableStore;

    /**
     * @param fontRegistry  本服字体注册表；{@code null} 跳过 missing-font 扫描
     * @param iconRegistry  本服图标注册表；{@code null} 跳过 missing-icon 扫描
     * @param variableStore 本服全局变量表；{@code null} 跳过 missing-variable 扫描
     */
    public MissingResourceScanner(@Nullable FontRegistry fontRegistry,
                                  @Nullable IconRegistry iconRegistry,
                                  @Nullable VariableStore variableStore) {
        this.fontRegistry = fontRegistry;
        this.iconRegistry = iconRegistry;
        this.variableStore = variableStore;
    }

    /**
     * 扫描导入工程，返回所有 {@code missing-font} / {@code missing-icon} / {@code missing-variable}
     * 提示（各类内部去重）。三类相互独立——某 registry 为 null 仅跳过那一类。
     *
     * @param imported 已物化校验过的导入工程
     * @return 非致命提示列表（可能为空）；不含其它 kind
     */
    public List<ImportWarning> scan(ProjectState imported) {
        List<ImportWarning> warnings = new ArrayList<>();
        if (imported == null) return warnings;

        // 去重集合（保留首次出现顺序）
        Set<String> seenFonts = new LinkedHashSet<>();
        Set<String> seenIcons = new LinkedHashSet<>();
        Set<String> seenVars = new LinkedHashSet<>();

        for (Layer layer : imported.layers()) {
            if (layer == null) continue;
            for (Element el : layer.elements()) {
                if (el instanceof TextElement t) {
                    scanFont(t, seenFonts);
                    scanVariables(t.text(), seenVars);
                } else if (el instanceof IconElement ic) {
                    scanIcon(ic, seenIcons);
                }
            }
        }

        for (String fontId : seenFonts) warnings.add(new ImportWarning("missing-font", fontId));
        for (String source : seenIcons) warnings.add(new ImportWarning("missing-icon", source));
        for (String fullName : seenVars) warnings.add(new ImportWarning("missing-variable", fullName));
        return warnings;
    }

    /** 字体：fontId 非 null 且本服未注册 → 记一次。 */
    private void scanFont(TextElement t, Set<String> seen) {
        if (fontRegistry == null) return;
        String fontId = t.fontId();
        if (fontId == null || fontId.isEmpty()) return;   // 未指定字体 → 走默认，不算缺
        if (seen.contains(fontId)) return;
        if (fontRegistry.get(fontId) == null) {
            seen.add(fontId);
        }
    }

    /**
     * 图标：仅 {@code user/<id>} 形态查存在性。内置 {@code fa-*} pack 恒在不查；legacy 无 {@code /}
     * 形态走 PNG 兼容路径不在范围。
     */
    private void scanIcon(IconElement ic, Set<String> seen) {
        if (iconRegistry == null) return;
        String source = ic.source();
        if (source == null || source.isEmpty()) return;
        String pack = IconElement.packOf(source);   // legacy（无 '/'）返 null
        if (!"user".equals(pack)) return;            // 只看用户图标
        if (seen.contains(source)) return;
        if (!iconRegistry.isRegistered(source)) {
            seen.add(source);
        }
    }

    /**
     * 变量：从文本提取 {@code ${var:...}} 引用，<b>仅</b>对 {@code userglobal/<key>} 引用查存在性。
     * 其余 namespace（user/* / schedule / scoreboard / wall / rail / system 等）不报，避免误报。
     */
    private void scanVariables(@Nullable String text, Set<String> seen) {
        if (variableStore == null) return;
        if (text == null || text.indexOf("${var:") < 0) return;
        Matcher m = PLACEHOLDER_RE.matcher(text);
        while (m.find()) {
            String rawName = m.group(1).trim();
            // 仅 userglobal/<key> 形态——内部 fullName 与对外占位符同形（无 wallId 注入）。
            if (!rawName.startsWith(USERGLOBAL_PREFIX)) continue;
            String key = rawName.substring(USERGLOBAL_PREFIX.length());
            if (key.isEmpty()) continue;
            if (seen.contains(rawName)) continue;
            if (variableStore.get(rawName).isEmpty()) {
                seen.add(rawName);
            }
        }
    }
}
