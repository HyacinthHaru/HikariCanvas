import type { InjectionKey, Ref } from 'vue';
import type { HighlightMap } from './traceHighlight';

/**
 * 试跑高亮的 provide/inject 契约。
 *
 * <p>{@code ScriptEditorOverlay} 持有唯一的高亮局部 ref（{@code Ref<HighlightMap>}）+ 一份
 * {@code Ref<Map<key, detail>>}（step.detail 作积木 title），经 {@code BlockCanvas} 一路
 * provide 给递归的 {@code BlockStack} 与 {@code BlockNode}。</p>
 *
 * <p><b>两张 map 的 key 都是 {@code highlightKey(ruleId, blockId)} 复合键</b>，不是裸 blockId。
 * 画布上同时挂着<b>所有</b>规则的积木堆，而它们共用这一份 map：每条规则都有自己的
 * {@code 'trigger'} 帽子和 {@code 'actions/0'}，只按 blockId 查的话，试跑一条规则会把画布上
 * 每一堆的帽子和同路径积木一起点亮、还都挂上这条规则的 detail 提示。所以查表前必须先知道
 * "我属于哪条规则"——{@link BLOCK_STACK_RULE_KEY} 由 {@code BlockStack} 往下 provide，
 * 递归的 {@code BlockNode} 不必层层透传 prop。</p>
 *
 * <p>高亮是<b>纯展示态</b>（不入 store / 不上 wire），故走 inject 而非 store——它只在 overlay
 * 打开期间、且只针对当前正在试跑的规则生效。未注入（BlockNode 单独 smoke mount）时 inject 默认
 * 值是<b>常量空 map ref</b>，子组件查不到任何高亮，正常静态渲染、不崩。</p>
 */

/** 注入值聚合：result map（边框色）+ detail map（title）。组件只读不写。 */
export interface HighlightInject {
    /** {@code highlightKey(ruleId, blockId)} → 结果态（驱动边框色）。 */
    results: Ref<HighlightMap>;
    /** {@code highlightKey(ruleId, blockId)} → step.detail 文案（驱动积木 title 提示）；无 detail 的 step 不入。 */
    details: Ref<Map<string, string>>;
}

export const BLOCK_HIGHLIGHT_KEY: InjectionKey<HighlightInject> = Symbol('hc-block-highlight');

/**
 * 当前积木堆所属规则 id（{@code BlockStack} provide，堆内所有 {@code BlockNode} 递归可 inject）。
 * 拿它 + 自身 blockId 拼 {@link HighlightInject} 的复合 key。未注入 → {@code null}（查不到高亮）。
 */
export const BLOCK_STACK_RULE_KEY: InjectionKey<Ref<string>> = Symbol('hc-block-stack-rule');
