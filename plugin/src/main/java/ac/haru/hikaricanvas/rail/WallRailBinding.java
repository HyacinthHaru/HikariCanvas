package ac.haru.hikaricanvas.rail;

import org.jetbrains.annotations.Nullable;

/**
 * wall → 铁路网络的绑定（线路 + 本站 + 方向）。
 *
 * <p>{@link #lineId} 非空时 {@code RailScheduleProvider} 走铁路路径（按 timetable 精确查）；
 * 为空时 fallback 到 {@code ManualScheduleProvider}（per-wall 时刻表）。</p>
 *
 * <p>direction 取值：{@code "up"} / {@code "down"} / {@code "both"}（双向都列出）。</p>
 *
 * @param wallId      所属 wall（PK + FK CASCADE）
 * @param lineId      绑定到的线路；null = 未绑定
 * @param stationId   绑定到的"本站"（wall 屏所在的站）；line 非空时应同步给值
 * @param direction   方向过滤；null 视同 "both"
 * @param updatedAt   epoch ms
 */
public record WallRailBinding(
        String wallId,
        @Nullable String lineId,
        @Nullable String stationId,
        @Nullable String direction,
        long updatedAt
) {
    /** 同时显示上下行 + 双向。 */
    public static final String DIRECTION_BOTH = "both";

    /** 规范化 direction：null / 非法值 → "both"。 */
    public static String normalizeDirection(@Nullable String raw) {
        if (raw == null) return DIRECTION_BOTH;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (RailRun.DIRECTION_UP.equals(s) || RailRun.DIRECTION_DOWN.equals(s)
                || DIRECTION_BOTH.equals(s)) {
            return s;
        }
        return DIRECTION_BOTH;
    }
}
