package moe.hikari.canvas.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 矢量图标注册表。统一管理 jar 内置矢量图标包（Font Awesome Free 6.x）
 * + 用户自定义 SVG（{@code plugins/HikariCanvas/icons/*.svg}）。
 *
 * <p><b>启动期：</b></p>
 * <ol>
 *   <li>{@link #loadBuiltIn()} — 扫 jar classpath {@code /icons/*.icons.json}（构建期由
 *       {@code IconLibraryGenerator} 生成）。包内 path d 已规范化。</li>
 *   <li>{@link #loadExternal(Path)} — 扫 {@code plugins/HikariCanvas/icons/*.svg}。
 *       简单 regex 取首个 {@code <path d>} + {@code viewBox} 属性。注册为 {@code user/<filename>}。</li>
 * </ol>
 *
 * <p><b>线程模型：</b> 启动期单线程装载 → 之后只读（{@code ConcurrentHashMap}）。reload 流程
 * 走 {@link #invalidate()} 后重新 {@link #loadBuiltIn()}/{@link #loadExternal(Path)}。</p>
 *
 * <p><b>边界：</b></p>
 * <ul>
 *   <li>SVG 解析仅支持简单单一 path 形态（FA / Material 都这样）。复杂多 path / transform /
 *       stroke 用户 SVG → 仅取首 path 并 log.warning。</li>
 *   <li>viewBox 缺失 → 退 {@code "0 0 24 24"}（Material Symbols 默认）。</li>
 *   <li>用户 SVG 命名：文件名 {@code [a-z0-9_-]+\.svg}，去 .svg 后作 user/&lt;id&gt;。</li>
 * </ul>
 */
public final class IconRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 用户文件名（去 .svg 后部分）合法字符。 */
    private static final Pattern USER_FILENAME_RE =
            Pattern.compile("^[a-z0-9_-]+\\.svg$", Pattern.CASE_INSENSITIVE);

    /** 简单 regex 从 SVG 字符串里取首个 path d。捕获 {@code d="..."} 或 {@code d='...'}。 */
    private static final Pattern SVG_PATH_D_RE =
            Pattern.compile("<path\\b[^>]*?\\sd=([\"'])(.*?)\\1", Pattern.DOTALL);

    private static final Pattern SVG_VIEWBOX_RE =
            Pattern.compile("<svg\\b[^>]*?\\sviewBox=([\"'])(.*?)\\1", Pattern.DOTALL);

    /** classpath 资源根；jar 内 /icons/<pack>.icons.json。 */
    private static final String[] BUILTIN_PACKS = {
            "fa-solid", "fa-regular", "fa-brands"
            // material 待 IconLibraryGenerator 增 material pipeline 后追加
    };

    private final Logger log;

    /** pack id → IconPack（含全部 icon 的 path d 表）。 */
    private final Map<String, IconPack> packs = new ConcurrentHashMap<>();

    /** fullId (e.g. "fa-solid/heart") → IconInfo；listAll / search 用。LinkedHashMap 保稳定序。 */
    private final Map<String, IconInfo> index = Collections.synchronizedMap(new LinkedHashMap<>());

    public IconRegistry(Logger log) {
        this.log = log;
    }

    /** 装载 jar 内置矢量包。每个 pack 一个 JSON 文件。 */
    public void loadBuiltIn() {
        for (String packId : BUILTIN_PACKS) {
            String resource = "/icons/" + packId + ".icons.json";
            try (InputStream in = IconRegistry.class.getResourceAsStream(resource)) {
                if (in == null) {
                    log.fine("[icons] builtin pack missing: " + resource
                            + " (run ./gradlew generateIconLibrary)");
                    continue;
                }
                JsonNode root = JSON.readTree(in);
                String pack = root.path("pack").asText(packId);
                String version = root.path("version").asText("unknown");
                String license = root.path("license").asText("unknown");
                String viewBox = root.path("viewBox").asText("0 0 512 512");
                JsonNode icons = root.path("icons");
                if (!icons.isObject()) {
                    log.warning("[icons] " + packId + " has no icons object; skipping");
                    continue;
                }
                Map<String, String> entries = new LinkedHashMap<>(icons.size());
                icons.fieldNames().forEachRemaining(name -> {
                    JsonNode v = icons.get(name);
                    String d = v.isObject() ? v.path("d").asText(null) : (v.isTextual() ? v.asText() : null);
                    if (d != null && !d.isBlank()) entries.put(name, d);
                });
                IconPack ip = new IconPack(pack, version, license, viewBox, entries);
                packs.put(pack, ip);
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    String fullId = pack + "/" + e.getKey();
                    index.put(fullId, new IconInfo(fullId, e.getKey(), pack, viewBox, "builtin"));
                }
                log.info("[icons] loaded pack " + pack + " v" + version
                        + " (" + entries.size() + " icons)");
            } catch (IOException e) {
                log.log(Level.WARNING, "[icons] failed to load builtin pack " + packId, e);
            }
        }
    }

    /**
     * 扫用户自定义 SVG 目录。文件名 {@code <id>.svg} → 注册为 {@code user/<id>}。
     * 不存在的目录 → no-op（首次启动玩家未放文件正常）。
     */
    public void loadExternal(Path iconsDir) {
        if (iconsDir == null) return;
        if (!Files.isDirectory(iconsDir)) {
            log.fine("[icons] user dir missing: " + iconsDir + " (no user icons loaded)");
            return;
        }
        Map<String, String> userEntries = new LinkedHashMap<>();
        // 多 svg 可能 viewBox 各异，用户 pack 不复用单一 viewBox；故 IconInfo 各自携带
        Map<String, String> userViewBoxes = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(iconsDir)) {
            for (Path p : stream) {
                String filename = p.getFileName().toString();
                if (!USER_FILENAME_RE.matcher(filename).matches()) {
                    log.warning("[icons] skipping invalid user filename: " + filename
                            + " (must be [a-z0-9_-]+.svg, case-insensitive)");
                    continue;
                }
                String id = filename.substring(0, filename.length() - 4).toLowerCase(Locale.ROOT);
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    String d = extractFirstPathD(content);
                    if (d == null) {
                        log.warning("[icons] user SVG has no <path d>: " + filename + " (skipped)");
                        continue;
                    }
                    String viewBox = extractViewBox(content);
                    if (viewBox == null) viewBox = "0 0 24 24";
                    userEntries.put(id, d);
                    userViewBoxes.put(id, viewBox);
                } catch (IOException e) {
                    log.log(Level.WARNING, "[icons] failed to read user SVG: " + filename, e);
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "[icons] failed to scan user dir: " + iconsDir, e);
            return;
        }
        if (userEntries.isEmpty()) return;

        // user pack 用 mixed viewBox：getPathD 仍按 fullId 查；listAll 每个 IconInfo 各自带 viewBox
        IconPack userPack = new IconPack("user", "local", "user-supplied", "0 0 24 24", userEntries);
        packs.put("user", userPack);
        for (Map.Entry<String, String> e : userEntries.entrySet()) {
            String fullId = "user/" + e.getKey();
            index.put(fullId, new IconInfo(fullId, e.getKey(), "user",
                    userViewBoxes.getOrDefault(e.getKey(), "0 0 24 24"), "user"));
        }
        log.info("[icons] loaded " + userEntries.size() + " user icon(s) from " + iconsDir);
    }

    /** 清空（reload 用）。 */
    public void invalidate() {
        packs.clear();
        index.clear();
    }

    /**
     * 查 path d。{@code fullId} 形如 {@code fa-solid/heart}。
     *
     * @return path d 字符串；未注册返 null
     */
    public String getPathD(String fullId) {
        if (fullId == null) return null;
        int slash = fullId.indexOf('/');
        if (slash < 0) return null;  // legacy PNG 形态不走 IconRegistry
        String pack = fullId.substring(0, slash);
        String name = fullId.substring(slash + 1);
        IconPack ip = packs.get(pack);
        if (ip == null) return null;
        return ip.icons.get(name);
    }

    /** 查 viewBox。fullId 同 {@link #getPathD(String)}。 */
    public String getViewBox(String fullId) {
        IconInfo info = index.get(fullId);
        return info == null ? null : info.viewBox();
    }

    public IconInfo getInfo(String fullId) {
        return index.get(fullId);
    }

    public boolean isRegistered(String fullId) {
        return index.containsKey(fullId);
    }

    public int size() {
        return index.size();
    }

    /** 全量列表（pack 内按注册序；pack 间按 BUILTIN_PACKS 序 + user 末尾）。 */
    public List<IconInfo> listAll() {
        return new ArrayList<>(index.values());
    }

    /**
     * 搜索 / 过滤 + 分页。返结果 + 是否 hasMore 由调用方按 limit/offset 推断。
     *
     * @param query    可空；空字符串等价于全匹配。case-insensitive substring 匹配 name
     * @param category 可空；非空时要求 pack 完全相等（或前缀匹配如 {@code "fa-"} 用 {@code "fa-*"}）
     * @param limit    最大返回（必填 ≥ 1）
     * @param offset   起始 offset（≥ 0）
     */
    public SearchResult search(String query, String category, int limit, int offset) {
        if (limit < 1) limit = 1;
        if (offset < 0) offset = 0;
        String q = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);
        String cat = (category == null || category.isBlank()) ? null : category.trim();
        // 支持 "fa-*" 通配前缀
        boolean catPrefix = cat != null && cat.endsWith("*");
        String catPrefixVal = catPrefix ? cat.substring(0, cat.length() - 1) : null;

        List<IconInfo> matched = new ArrayList<>();
        for (IconInfo info : index.values()) {
            if (cat != null) {
                if (catPrefix) {
                    if (!info.pack().startsWith(catPrefixVal)) continue;
                } else {
                    if (!info.pack().equals(cat)) continue;
                }
            }
            if (!q.isEmpty()) {
                String nameLow = info.displayName().toLowerCase(Locale.ROOT);
                if (!nameLow.contains(q)) continue;
            }
            matched.add(info);
        }
        int total = matched.size();
        int end = Math.min(offset + limit, total);
        List<IconInfo> page = (offset >= total) ? List.of() : matched.subList(offset, end);
        return new SearchResult(List.copyOf(page), total, offset + page.size() < total);
    }

    // ---------- SVG 解析 helpers ----------

    private static String extractFirstPathD(String svgContent) {
        Matcher m = SVG_PATH_D_RE.matcher(svgContent);
        if (!m.find()) return null;
        return m.group(2);
    }

    private static String extractViewBox(String svgContent) {
        Matcher m = SVG_VIEWBOX_RE.matcher(svgContent);
        if (!m.find()) return null;
        return m.group(2).trim();
    }

    // ---------- 数据类型 ----------

    /**
     * 单个 icon pack 的全量信息。{@code icons} 是 {@code id → path d} 不可变映射。
     * pack 内统一 {@code viewBox}（FA 全 {@code 0 0 512 512}）；user pack 用占位值，真实
     * viewBox 在 {@link IconInfo#viewBox()} 上。
     */
    public record IconPack(String pack, String version, String license, String viewBox,
                           Map<String, String> icons) {
        public IconPack {
            Objects.requireNonNull(pack, "pack");
            icons = Map.copyOf(icons);
        }
    }

    /**
     * 单 icon 的展示元数据。
     *
     * @param id          完整 id（如 {@code fa-solid/heart}）；与 {@link moe.hikari.canvas.state.IconElement#source()} 一致
     * @param displayName name 部分（如 {@code heart}）
     * @param pack        pack id（如 {@code fa-solid}）
     * @param viewBox     SVG viewBox（5 SP 分隔的 4 数字字符串）
     * @param source      {@code "builtin"} / {@code "user"}
     */
    public record IconInfo(String id, String displayName, String pack, String viewBox, String source) {}

    public record SearchResult(List<IconInfo> icons, int total, boolean hasMore) {}
}
