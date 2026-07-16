package moe.hikari.canvas.web;

import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.StatePatch;

/**
 * Dispatcher → WebServer 的服务端主动推送回调。
 * <p>{@link WebServer} 是唯一持有 {@code wsBySession} 映射的类；dispatcher 通过此接口
 * 触发 {@code state.snapshot} / {@code state.patch} 下行帧，避免直接耦合 WS 连接管理。</p>
 *
 * <p>契约见 {@code docs/protocol.md §5}。</p>
 */
public interface OpPushCallback {
    boolean pushSnapshot(String sessionId, ProjectState state);
    boolean pushPatch(String sessionId, StatePatch patch);

    /**
     * 推任意服务端主动 op（首个消费者 {@code script.trace}）。
     * Envelope id 由 WebServer 侧统一发号（{@code s-N}，与 snapshot / patch 同纪律）；
     * dispatcher 只给 op 名 + payload。
     *
     * <p>default false：既有测试 fake / 不支持主动推送的装配不必实现；
     * 调用方按"session 没了静默丢"语义对待 false。</p>
     */
    default boolean pushOp(String sessionId, String op, Object payload) {
        return false;
    }
}
