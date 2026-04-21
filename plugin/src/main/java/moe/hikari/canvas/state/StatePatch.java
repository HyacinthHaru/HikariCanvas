package moe.hikari.canvas.state;

import java.util.List;

/**
 * WebSocket 下行 {@code op: "state.patch"} 的 payload。
 *
 * <pre>
 * { "v":1, "op":"state.patch", "id":"s-N",
 *   "payload": { "version": 42, "ops": [ { "op":"add", "path":"/elements/3", "value":{...} } ] } }
 * </pre>
 *
 * @param version 应用 {@code ops} 后的新 {@link ProjectState#version()}
 * @param ops     有序操作列表；空列表语义上等同"无变化"，调用方应避免发送空 patch
 */
public record StatePatch(long version, List<PatchOp> ops) {
}
