package ac.haru.hikaricanvas.rail;

import org.jetbrains.annotations.Nullable;

/**
 * 铁路站点。属于一条 {@link RailLine}，按 {@link #sortOrder} 排序展示。
 *
 * @param id          站点 id（形如 {@code "stn-<8hex>"}）
 * @param lineId      所属线路
 * @param name        站名（"郑州火车站"）
 * @param code        短代号（"ZHF"）；可空
 * @param sortOrder   在线路上的顺序（0..N，up 方向递增）
 * @param isTerminus  {@code true} = 物理终点（首站 / 末站）；区间车的中间终点也可标
 * @param createdAt   epoch ms
 */
public record RailStation(
        String id,
        String lineId,
        String name,
        @Nullable String code,
        int sortOrder,
        boolean isTerminus,
        long createdAt
) {
}
