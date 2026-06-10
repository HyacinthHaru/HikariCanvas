import type { InjectionKey, Ref } from 'vue';
import type { HighlightMap } from './traceHighlight';

/**
 * 0.7.0-P5-H（K-UI-8）：试跑高亮的 provide/inject 契约。
 *
 * <p>{@code ScriptEditorOverlay} 持有唯一的高亮局部 ref（{@code Ref<HighlightMap>} =
 * {@code Map<blockId, result>}，由 {@link createHighlightStepper} 步进更新）+ 一份
 * {@code Ref<Map<blockId, detail>>}（step.detail 作积木 title），经 {@code BlockCanvas} 一路
 * provide 给递归的 {@code BlockStack}（帽子按 {@code 'trigger'} 查）与 {@code BlockNode}
 * （按自身 {@code path} 查）。子组件用自己的 blockId 查 result map 取边框色 +
 * 查 detail map 取 title。</p>
 *
 * <p>高亮是<b>纯展示态</b>（不入 store / 不上 wire），故走 inject 而非 store——它只在 overlay
 * 打开期间、且只针对当前正在试跑的规则生效。未注入（BlockNode 单独 smoke mount）时 inject 默认
 * 值是<b>常量空 map ref</b>，子组件查不到任何高亮，正常静态渲染、不崩。</p>
 */

/** 注入值聚合：result map（边框色）+ detail map（title）。组件只读不写。 */
export interface HighlightInject {
    /** blockId → 结果态（驱动边框色）。 */
    results: Ref<HighlightMap>;
    /** blockId → step.detail 文案（驱动积木 title 提示）；无 detail 的 step 不入。 */
    details: Ref<Map<string, string>>;
}

export const BLOCK_HIGHLIGHT_KEY: InjectionKey<HighlightInject> = Symbol('hc-block-highlight');
