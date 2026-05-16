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
}
