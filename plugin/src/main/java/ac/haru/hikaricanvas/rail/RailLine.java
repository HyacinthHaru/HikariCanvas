package ac.haru.hikaricanvas.rail;

import org.jetbrains.annotations.Nullable;

/**
 * 铁路线路。一条线路含多个站点（{@link RailStation}）+ 多个车次（{@link RailRun}）。
 *
 * <p>schema 见 {@code db-migrations/V016__rail_network.sql}；ACL 走
 * {@code canvas.rail.line.edit.{own,any}}（owner = {@link #ownerUuid}）。</p>
 *
 * @param id          线路 id（形如 {@code "line-<8hex>"}）
 * @param name        线路名（"1 号线"）
 * @param code        短代号（"L1"）；可空
 * @param color       主题色 "#RRGGBB"；可空
 * @param ownerUuid   创建者 UUID
 * @param ownerName   创建时玩家名（UI 显示，不强一致）
 * @param createdAt   epoch ms
 * @param updatedAt   epoch ms
 */
public record RailLine(
        String id,
        String name,
        @Nullable String code,
        @Nullable String color,
        java.util.UUID ownerUuid,
        String ownerName,
        long createdAt,
        long updatedAt
) {
}
