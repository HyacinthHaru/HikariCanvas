package moe.hikari.canvas.state;

/**
 * RFC 6902 JSON Patch 的最小子集：{@code add / replace / remove}。
 * 契约见 {@code docs/protocol.md §5.2}。
 *
 * <p>{@code move / copy / test} 暂不使用。{@code move} 用 {@code remove + add} 表达；
 * 数组 reorder 用多步 remove + add，简单直白。</p>
 *
 * @param op    {@code "add" | "replace" | "remove"}
 * @param path  JSON Pointer（RFC 6901），如 {@code "/elements/3"} 或 {@code "/canvas/background"}
 * @param value {@code op == "remove"} 时为 {@code null}；序列化由 {@code NON_NULL} 策略省略
 */
public record PatchOp(String op, String path, Object value) {

    public static PatchOp add(String path, Object value) {
        return new PatchOp("add", path, value);
    }

    public static PatchOp replace(String path, Object value) {
        return new PatchOp("replace", path, value);
    }

    public static PatchOp remove(String path) {
        return new PatchOp("remove", path, null);
    }
}
