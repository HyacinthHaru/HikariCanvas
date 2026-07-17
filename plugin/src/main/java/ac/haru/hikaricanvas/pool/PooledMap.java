package ac.haru.hikaricanvas.pool;

/**
 * 池中一张地图的内存视图（{@code pool_maps} 表一行的值对象）。
 * 不可变——状态迁移通过 {@link MapPool} 重建新实例并更新索引。
 *
 * <p>{@code reservedBy} 在 RESERVED 时格式为 {@code wall:<wall_id>}。</p>
 */
public record PooledMap(
        int mapId,
        PoolState state,
        String reservedBy,   // RESERVED 时的 owner（"wall:<id>"），FREE 时 null
        String world,        // 创建时的所在世界
        long createdAt,
        long lastUsedAt
) {
    public PooledMap withFree(long now) {
        return new PooledMap(mapId, PoolState.FREE, null, world, createdAt, now);
    }

    public PooledMap withReserved(String owner, long now) {
        return new PooledMap(mapId, PoolState.RESERVED, owner, world, createdAt, now);
    }
}
