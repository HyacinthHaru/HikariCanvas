import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { ScriptRule, ScriptTracePayload } from '@/types/protocol';

/**
 * ScriptStore 前端镜像（0.7.0 P1，契约 docs/scripting.md §2）。
 *
 * <p>来源：ready payload 的 {@code scripts} 字段（{@code ScriptRule[]}，服务端顺序）+
 * state.patch 中 path 前缀为 {@code /scripts/} 的 op（add = 完整 rule 对象，replace 语义
 * 后端统一发 add；remove）。前端不主动 fetch——server-as-truth，UI 只读本 store；
 * 网络写走 wsClient.sendScript*。</p>
 *
 * <p>脚本是 wall-scoped 状态；wall 切换时 {@link reset} 统一由 {@code project.reset()}
 * 调用（P3-103 单一调用点纪律）。主表 {@code Map<ruleId, ScriptRule>} + {@code order}
 * 数组保持服务端顺序；Map 实例每次 mutation 重建（照 variableAliases 的
 * {@code new Map(...)} 范式）以触发 Vue 响应。{@code blockLayout} 仅透传不解析
 * （积木 UI 是 P4/P5）。</p>
 */
export const useScriptStore = defineStore('scripts', () => {
    /** 主表：ruleId → ScriptRule。Map 实例每次 mutation 重建以触发 Vue 响应。 */
    const rules = ref<Map<string, ScriptRule>>(new Map());
    /** ruleId 顺序表（保持服务端下发顺序；upsert 新增追加，已存在原位不动）。 */
    const order = ref<string[]>([]);
    /**
     * 0.7.0-P3 A2（K11）：最近一次 {@code script.trace} 推送（试跑轨迹）。
     * wsClient 收到 S→C op 时写入；P5 积木高亮消费。wall 切换 reset 清。
     */
    const lastTrace = ref<ScriptTracePayload | null>(null);

    /**
     * 批量初始化：用 ready payload.scripts 一次性赋值（全量替换旧内容）。
     * null / undefined 视为空列表。
     */
    function initScripts(list: ScriptRule[] | null | undefined): void {
        if (!list || list.length === 0) {
            rules.value = new Map();
            order.value = [];
            return;
        }
        const next = new Map<string, ScriptRule>();
        const nextOrder: string[] = [];
        for (const rule of list) {
            if (!next.has(rule.id)) nextOrder.push(rule.id);
            next.set(rule.id, rule);
        }
        rules.value = next;
        order.value = nextOrder;
    }

    /**
     * 单条落表：已存在则原位替换（order 不动），新增则追加到 order 末尾。
     * state.patch 的 add（含 replace 语义）走这里。
     */
    function upsert(rule: ScriptRule): void {
        const next = new Map(rules.value);
        const existed = next.has(rule.id);
        next.set(rule.id, rule);
        rules.value = next;
        if (!existed) order.value = [...order.value, rule.id];
    }

    /** 删除规则；不存在时 noop（保持 ref 引用不重建）。 */
    function removeRule(ruleId: string): void {
        if (!rules.value.has(ruleId)) return;
        const next = new Map(rules.value);
        next.delete(ruleId);
        rules.value = next;
        order.value = order.value.filter((id) => id !== ruleId);
    }

    /** 取规则；不存在返 null（与 aliases store 的 get 语义对齐）。 */
    function get(ruleId: string): ScriptRule | null {
        return rules.value.get(ruleId) ?? null;
    }

    /** script.trace 推送落表（覆盖式——只留最近一次试跑轨迹）。 */
    function setLastTrace(payload: ScriptTracePayload | null): void {
        lastTrace.value = payload;
    }

    /** wall 切换时由 project.reset() 调用。 */
    function reset(): void {
        lastTrace.value = null;
        if (rules.value.size === 0 && order.value.length === 0) return;
        rules.value = new Map();
        order.value = [];
    }

    /** 按服务端顺序的规则列表；防御性跳过 order 中已不在主表的 id。 */
    const listSorted = computed<ScriptRule[]>(() =>
        order.value
            .map((id) => rules.value.get(id))
            .filter((r): r is ScriptRule => r != null));

    /** 规则总数；UI badge 用。与 listSorted 同口径，防 order/rules desync 时 badge 数与列表数不一。 */
    const size = computed(() => listSorted.value.length);

    return {
        rules, order, lastTrace,
        initScripts, upsert, removeRule, get, setLastTrace, reset,
        listSorted, size,
    };
});
