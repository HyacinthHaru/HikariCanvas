package moe.hikari.canvas.pool;

/**
 * 池中一张地图的三种状态，契约见 {@code docs/data-model.md §2.3}。
 *
 * <p>不变式：</p>
 * <ul>
 *   <li>{@link #FREE}：{@code reserved_by} NULL、{@code sign_id} NULL</li>
 *   <li>{@link #RESERVED}：{@code reserved_by} 非 NULL、{@code sign_id} NULL</li>
 *   <li>{@link #PERMANENT}：{@code sign_id} 非 NULL、{@code reserved_by} NULL</li>
 * </ul>
 */
public enum PoolState {
    FREE,
    RESERVED,
    PERMANENT
}
