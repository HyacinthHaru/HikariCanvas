package ac.haru.hikaricanvas.rail;

import java.util.Locale;

/**
 * 车次服务类型。4 个内置 enum 值 + 自定义字符串兜底。
 *
 * <p>{@link #LOCAL}（站站停）/ {@link #EXPRESS}（大站快车）/ {@link #SECTION}（区间车）/
 * {@link #LIMITED}（特快）。</p>
 *
 * <p>DB 存 String（可能是非 enum 名的自定义值，如 {@code "通勤特急"}）；
 * {@link #parseOrCustom(String)} 把 string 翻成 enum 或返 null（自定义）。</p>
 *
 * <p>i18n：{@link #displayText(String, Locale)} 把 type string 翻成 owner locale 友好文本
 * （内置 enum 查表；自定义字符串原样输出）。详见
 * {@code docs/dynamic-data.md §18.10}。</p>
 */
public enum ServiceType {
    LOCAL, EXPRESS, SECTION, LIMITED;

    /** 4 个内置 enum 的 DB 字符串形式（小写）。 */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析 DB 字符串到 enum；不匹配任何内置值时返 null（调用方按"自定义字符串"处理）。
     */
    public static ServiceType parseOrCustom(String raw) {
        if (raw == null) return null;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        for (ServiceType st : values()) {
            if (st.name().equals(upper)) return st;
        }
        return null;
    }

    /**
     * i18n 友好显示文本：
     * <ul>
     *   <li>内置 enum → 按 locale 查表（zh-CN: 站站停 / 大站快车 / 区间车 / 特快）</li>
     *   <li>非内置 → 原样返回（玩家自定义服务类型，如"通勤特急"）</li>
     * </ul>
     *
     * @param raw     DB 中的 service_type 字段值（可能是 enum 或自定义字符串）
     * @param locale  owner locale；null / 非中文 → 走英文
     */
    public static String displayText(String raw, Locale locale) {
        if (raw == null || raw.isEmpty()) return "";
        ServiceType st = parseOrCustom(raw);
        if (st == null) {
            return raw;  // 自定义字符串原样
        }
        boolean zh = locale != null
                && (Locale.SIMPLIFIED_CHINESE.equals(locale)
                        || Locale.CHINESE.equals(locale)
                        || (locale.getLanguage() != null
                                && locale.getLanguage().equalsIgnoreCase("zh")));
        return switch (st) {
            case LOCAL -> zh ? "站站停" : "Local";
            case EXPRESS -> zh ? "大站快车" : "Express";
            case SECTION -> zh ? "区间车" : "Section";
            case LIMITED -> zh ? "特快" : "Limited";
        };
    }
}
