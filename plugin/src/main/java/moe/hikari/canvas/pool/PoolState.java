package moe.hikari.canvas.pool;

/**
 * 池中一张地图的状态（两态）。契约见 {@code docs/data-model.md §2.3}。
 *
 * <p>不变式：</p>
 * <ul>
 *   <li>{@link #FREE}：{@code reserved_by} NULL</li>
 *   <li>{@link #RESERVED}：{@code reserved_by} 非 NULL，格式 {@code wall:<wall_id>}</li>
 * </ul>
 */
public enum PoolState {
    FREE,
    RESERVED
}
