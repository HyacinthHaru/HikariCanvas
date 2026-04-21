package moe.hikari.canvas.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 累积 {@link PatchOp} 并最终 {@link #build(long)} 成 {@link StatePatch}。
 * 用于 M3-T6 element op handler 在一次调用里批量产出多个 JSON Patch 子句。
 *
 * <p>非线程安全——只在 {@code SessionManager.synchronized} 段内使用即可。</p>
 */
public final class StatePatchBuilder {

    private final List<PatchOp> ops = new ArrayList<>();

    public StatePatchBuilder add(String path, Object value) {
        ops.add(PatchOp.add(path, value));
        return this;
    }

    public StatePatchBuilder replace(String path, Object value) {
        ops.add(PatchOp.replace(path, value));
        return this;
    }

    public StatePatchBuilder remove(String path) {
        ops.add(PatchOp.remove(path));
        return this;
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    public int size() {
        return ops.size();
    }

    /**
     * 冻结成不可变 {@link StatePatch}。调用方应传入
     * {@link ProjectState#bumpVersion()} 的返回值作为 {@code version}，保证与服务端权威状态一致。
     */
    public StatePatch build(long version) {
        return new StatePatch(version, Collections.unmodifiableList(new ArrayList<>(ops)));
    }
}
