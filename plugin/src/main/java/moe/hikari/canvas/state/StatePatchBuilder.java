package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 累积 {@link PatchOp} 并最终 {@link #build(long)} 成 {@link StatePatch}。
 * 用于 M3-T6 element op handler 在一次调用里批量产出多个 JSON Patch 子句。
 *
 * <p>非线程安全——只在 {@code SessionManager.synchronized} 段内使用即可。</p>
 */
public final class StatePatchBuilder {

    /**
     * M5-D4 修 Bug 1：{@link PatchOp#value()} 声明为 {@code Object}，Javalin/Jackson 在
     * Object 字段路径下 <b>不会</b> 触发 {@link com.fasterxml.jackson.annotation.JsonTypeInfo}
     * 的多态写入，导致 {@code element.add} 发出的 patch 丢 {@code "type"} 字段；而
     * {@code template.apply} 走 {@code OkSnapshot} 时 {@code ProjectState.elements} 静态类型
     * {@code List<Element>} 多态正常。
     *
     * <p>修法：builder 接到 {@link Element} 时先用 {@link #ELEMENT_WRITER}（静态类型
     * {@code Element.class}）序列化为 JSON，再 {@link ObjectMapper#readValue} 成 Map，这样
     * {@code type} 已内嵌；后续 Javalin 写 PatchOp.value 时直接看到 Map，字段齐全。</p>
     */
    private static final ObjectMapper ELEMENT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final ObjectWriter ELEMENT_WRITER = ELEMENT_MAPPER.writerFor(Element.class);

    private final List<PatchOp> ops = new ArrayList<>();

    public StatePatchBuilder add(String path, Object value) {
        ops.add(PatchOp.add(path, normalize(value)));
        return this;
    }

    public StatePatchBuilder replace(String path, Object value) {
        ops.add(PatchOp.replace(path, normalize(value)));
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

    /**
     * 如果 {@code value} 是 {@link Element}，用 {@link #ELEMENT_WRITER} 转成带 {@code type} 的 Map；
     * 其他类型原样返回。
     */
    private static Object normalize(Object value) {
        if (!(value instanceof Element e)) return value;
        try {
            String json = ELEMENT_WRITER.writeValueAsString(e);
            return ELEMENT_MAPPER.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to polymorphically serialize Element", ex);
        }
    }
}
