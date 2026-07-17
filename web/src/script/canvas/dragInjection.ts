import type { InjectionKey } from 'vue';

/**
 * 积木拖拽 start 句柄的 provide/inject 契约。
 *
 * <p>{@code BlockCanvas} 持有唯一的 {@code useBlockDrag} 实例（它握 canvasRef + screenToWorld），
 * 把"启动块拖动 / 启动移堆"两个句柄 provide 下去；递归的 {@code BlockNode}（拖块）与
 * {@code BlockStack}（拖帽子移堆）inject 后在各自 pointerdown 调用。palette 源不走这里
 * （palette 在 BlockCanvas 之外的侧栏，经 overlay 转发到 BlockCanvas 暴露的 startPaletteDrag）。</p>
 *
 * <p>未注入（理论上不会——BlockNode/BlockStack 只在 BlockCanvas 内渲染）时 inject 默认值是
 * no-op 占位，组件不崩（smoke 单独 mount BlockNode 时即走默认值）。</p>
 */
export interface BlockDragHandles {
    /** 拖动画布上已有块：stackRuleId + 块 path 字符串 + pointerdown 事件。 */
    startBlockDrag: (stackRuleId: string, blockPath: string, e: PointerEvent) => void;
    /** 拖动帽子移整堆：ruleId + pointerdown 事件。 */
    startStackDrag: (ruleId: string, e: PointerEvent) => void;
}

/** no-op 默认句柄（未在 BlockCanvas 内时的安全兜底）。 */
export const NOOP_DRAG_HANDLES: BlockDragHandles = {
    startBlockDrag: () => { /* no-op */ },
    startStackDrag: () => { /* no-op */ },
};

export const BLOCK_DRAG_KEY: InjectionKey<BlockDragHandles> = Symbol('hc-block-drag');
