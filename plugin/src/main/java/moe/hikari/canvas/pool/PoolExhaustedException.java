package moe.hikari.canvas.pool;

/**
 * 池容量耗尽（FREE 不足 + 无法扩容到 max）时抛出。
 * 见 {@code docs/protocol.md §6.1} 错误码 {@code POOL_EXHAUSTED}。
 */
public final class PoolExhaustedException extends RuntimeException {
    public PoolExhaustedException(String message) {
        super(message);
    }
}
