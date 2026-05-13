package moe.hikari.canvas.state;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ProjectState} 的不可变快照（M8-C 起含完整 layers 树），用于 undo/redo 历史栈。
 *
 * <p><b>M8-C 形态升级：</b> v1 只存 {@code (canvas, elements, label)}（活动层视图）；
 * 引入 layer.* op 后单层快照不够，必须存整棵 layers 树 + activeLayerId 才能完整恢复
 * 跨层操作的状态。</p>
 *
 * <p><b>深拷贝语义：</b> 紧凑构造器把传入的 layers 全部克隆一份（每个 Layer 重建 +
 * elements list 重建为新 ArrayList），保证快照与 live state 不再共享任何可变集合。
 * 这一步是 undo 正确性的关键 —— 缺了它，后续对 live state 的 mutation 会污染历史栈。</p>
 *
 * <p>Element 自身是 record，不可变，无需深拷贝。</p>
 *
 * @param canvas        快照时刻的 canvas（含 gridSize / guides；record 本身不可变）
 * @param layers        深拷贝后的层树；外层 {@link List#copyOf} 锁住引用
 * @param activeLayerId 快照时刻的活动层 id
 * @param label         {@code null} = 常规 op 产生的匿名快照；非 null = {@code history.mark}
 *                      产生的命名检查点
 */
public record ProjectSnapshot(
        ProjectState.Canvas canvas,
        List<Layer> layers,
        String activeLayerId,
        String label
) {
    public ProjectSnapshot {
        if (layers == null) {
            layers = List.of();
        } else {
            List<Layer> copied = new ArrayList<>(layers.size());
            for (Layer l : layers) {
                copied.add(new Layer(
                        l.id(), l.name(), l.visible(), l.locked(),
                        l.opacity(), l.blendMode(),
                        new ArrayList<>(l.elements())));
            }
            layers = List.copyOf(copied);
        }
    }

    /**
     * 兼容视图：返回 activeLayer 的 elements 副本（M8-A/M5 老调用方仍用）。
     * 找不到 activeLayer 时退回第一层；空 layers 返空列表。
     */
    public List<Element> elements() {
        for (Layer l : layers) {
            if (l.id().equals(activeLayerId)) return l.elements();
        }
        return layers.isEmpty() ? List.of() : layers.get(0).elements();
    }
}
