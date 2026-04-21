package moe.hikari.canvas.state;

import java.util.List;

/**
 * {@link ProjectState} 的不可变快照，用于 M3-T11 undo/redo 历史栈。
 *
 * <p>{@code canvas} / {@code elements} 内的 record 均不可变；{@code elements}
 * 由 {@link List#copyOf} 产出只读副本，保证快照与 live state 不再共享可变数据。</p>
 *
 * @param label {@code null} = 常规 op 产生的匿名快照；非 null = {@code history.mark}
 *              产生的命名检查点（保留给 T11 后续 UI 展示"跳到 mark X"时用）
 */
public record ProjectSnapshot(
        ProjectState.Canvas canvas,
        List<Element> elements,
        String label
) {
}
