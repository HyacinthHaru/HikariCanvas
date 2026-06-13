/**
 * 0.7.0-P4-A：拖拽吸附几何（决策 K-UI-3）—— 纯逻辑，零 DOM。
 *
 * <p>积木序列是<b>垂直堆叠</b>的：每个可插入位置（"插槽"）= 某序列某 index 处的
 * 间隙，由 BlockCanvas 测量出屏幕矩形后喂给本模块。拖动一个积木时，按指针（拖动块的
 * 连接点）到各插槽的几何距离，选"最近且在阈值内"的插槽吸附。</p>
 *
 * <p><b>命中策略（确定性）</b>：因序列垂直堆叠、插槽是窄横条，主轴是 <b>y</b>。算法：</p>
 * <ol>
 *   <li>对每个插槽算指针到其矩形的"加权 clamp 距离"——先把指针 clamp 进矩形 [x,x+w]×[y,y+h]
 *       得最近点，水平偏差按 {@link H_WEIGHT} 缩小权重（让"指针在插槽正上/正下方一点偏左右"
 *       仍优先于"水平很近但竖直差很远"的另一插槽）；</li>
 *   <li>取加权距离最小者；该最小值 ≤ {@code threshold} 才命中，否则返回 {@code null}；</li>
 *   <li>平局（加权距离相等，浮点意义上）取 {@code slots} 中靠前者——稳定、可预测。</li>
 * </ol>
 */

/**
 * 一个可插入位置的屏幕矩形快照 + 它在树里的落点。
 *
 * - {@code stackRuleId}：该插槽所属积木堆（规则）id；
 * - {@code parentPath}：容纳序列的 path（{@code blockTree} 的 parentPath，如顶层
 *   {@code ['actions']} 或 if 槽 {@code ['actions','2','then']}）；
 * - {@code index}：在该序列中的插入下标；
 * - {@code x/y/w/h}：插槽间隙的屏幕矩形（像素）；
 * - {@code containerBlockType}：该插槽所在容器块的 type（仅 body/then/else 子序列槽有值；
 *   顶层 actions 无容器块，值为 undefined）。供外部做拖入类型限制（如 tweenBlock body 只
 *   允许放 setElementProperties + TWEENABLE_KINDS）。
 */
export interface SlotRect {
    stackRuleId: string;
    parentPath: string[];
    index: number;
    x: number;
    y: number;
    w: number;
    h: number;
    /** 该插槽所在容器块 type（仅子序列槽有值，顶层为 undefined）。 */
    containerBlockType?: string;
}

/**
 * 水平偏差权重（< 1）。把指针到插槽的水平距离按此系数缩小，使竖直（y）方向接近优先。
 * 0.35 ≈ "水平差 100px 仅相当于竖直差 35px"，吻合"序列垂直堆叠、主要按 y 命中"的直觉。
 */
const H_WEIGHT = 0.35;

/**
 * 在候选插槽中找命中的那个。
 *
 * @param slots     全部候选插槽（屏幕矩形快照）。
 * @param pointerX  指针 / 拖动块连接点的屏幕 x。
 * @param pointerY  指针 / 拖动块连接点的屏幕 y。
 * @param threshold 加权距离阈值（像素）；超过则不吸附。
 * @returns 命中的 {@link SlotRect}；无候选 / 全部超阈值 / 参数非有限 → {@code null}。
 */
export function findDropTarget(
    slots: SlotRect[],
    pointerX: number,
    pointerY: number,
    threshold: number,
): SlotRect | null {
    if (!slots || slots.length === 0) return null;
    if (!Number.isFinite(pointerX) || !Number.isFinite(pointerY)) return null;
    if (!Number.isFinite(threshold) || threshold < 0) return null;

    let best: SlotRect | null = null;
    let bestDist = Infinity;

    for (const slot of slots) {
        const dist = weightedDistance(slot, pointerX, pointerY);
        // 严格小于：平局保留先出现者（稳定命中）。
        if (dist < bestDist) {
            bestDist = dist;
            best = slot;
        }
    }

    if (best === null || bestDist > threshold) return null;
    return best;
}

// ---------- 内部辅助 ----------

/**
 * 指针到插槽矩形的"加权 clamp 距离"。
 *
 * <p>先把指针 clamp 进矩形得最近点，分别取水平 / 竖直分量；水平分量乘 {@link H_WEIGHT}
 * 削权后与竖直分量求欧氏距离。指针落在矩形内则距离 0。</p>
 */
function weightedDistance(slot: SlotRect, px: number, py: number): number {
    const left = slot.x;
    const right = slot.x + slot.w;
    const top = slot.y;
    const bottom = slot.y + slot.h;

    // 指针到矩形的水平 / 竖直越界量（在矩形内则该分量为 0）。
    const dx = px < left ? left - px : px > right ? px - right : 0;
    const dy = py < top ? top - py : py > bottom ? py - bottom : 0;

    const wx = dx * H_WEIGHT;
    return Math.sqrt(wx * wx + dy * dy);
}
