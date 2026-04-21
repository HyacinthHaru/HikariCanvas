package moe.hikari.canvas.pool;

/**
 * 池中一张地图的内存视图（{@code pool_maps} 表一行的值对象）。
 * 不可变——状态迁移通过 {@link MapPool} 重建新实例并更新索引。
 */
public record PooledMap(
        int mapId,
        PoolState state,
        String reservedBy,   // RESERVED 时的 sessionId，其它状态为 null
        String signId,       // PERMANENT 时的 signRecord id，其它状态为 null
        String world,        // PERMANENT 时冗余所在世界，其它状态为 null
        long createdAt,
        long lastUsedAt
) {
    public PooledMap withFree(long now) {
        return new PooledMap(mapId, PoolState.FREE, null, null, null, createdAt, now);
    }

    public PooledMap withReserved(String sessionId, long now) {
        return new PooledMap(mapId, PoolState.RESERVED, sessionId, null, null, createdAt, now);
    }

    public PooledMap withPermanent(String signId, String world, long now) {
        return new PooledMap(mapId, PoolState.PERMANENT, null, signId, world, createdAt, now);
    }
}
