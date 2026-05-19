import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { Variable } from '@/types/variable';
import { makeFullName } from '@/types/variable';

/**
 * VariableStore 前端镜像（0.4.0-P1-D）。
 *
 * <p>来源：state.patch 中 path 前缀为 {@code /variables/} 的 op；由 {@code wsClient}
 * 的 {@code applyVariablePatches} 写入。前端不主动 fetch——server-as-truth，UI 只读
 * 本 store。变量是 cross-wall 全局态，所以 store 本身不绑 wallId，wall 切换时由
 * {@link reset} 清空（{@code project.reset()} 会触发）。</p>
 *
 * <p>设计：用 {@code Map<fullName, Variable>} 单一主表 + computed 派生 byNamespace。
 * 没有 add/remove 区分（一律 {@link set}），简化 race 处理；删除走 {@link remove}。</p>
 */
export const useVariableStore = defineStore('variables', () => {
    /** 主表：fullName（如 {@code user:w-3a17b2c1/红队比分}）→ Variable */
    const variables = ref<Map<string, Variable>>(new Map());

    function set(fullName: string, v: Variable): void {
        // ref<Map> 的 mutation Vue 不能自动追踪，赋新 Map 触发响应。性能上 ~1000 条
        // 量级足够，未来量级上去再换成嵌套 reactive object map。
        const next = new Map(variables.value);
        next.set(fullName, v);
        variables.value = next;
    }

    function get(fullName: string): Variable | undefined {
        return variables.value.get(fullName);
    }

    function remove(fullName: string): void {
        if (!variables.value.has(fullName)) return;
        const next = new Map(variables.value);
        next.delete(fullName);
        variables.value = next;
    }

    function clear(): void {
        if (variables.value.size === 0) return;
        variables.value = new Map();
    }

    /** 等同 {@link clear}；显式 alias 让 project / ui store 切 wall 时调用。 */
    function reset(): void {
        clear();
    }

    /** 所有变量数组（按插入顺序）。 */
    const all = computed<Variable[]>(() => Array.from(variables.value.values()));

    /** 按 namespace 分桶；Picker / 管理面板用。 */
    const byNamespace = computed<Map<string, Variable[]>>(() => {
        const map = new Map<string, Variable[]>();
        for (const v of variables.value.values()) {
            let bucket = map.get(v.namespace);
            if (!bucket) {
                bucket = [];
                map.set(v.namespace, bucket);
            }
            bucket.push(v);
        }
        return map;
    });

    /**
     * 0.4.0-P2-G 扩展：批量初始化（用于 wsClient.handleReady 钩 ready payload.variables 列表）。
     * 一次性赋新 Map，避免循环触发 N 次响应。
     */
    function initVariables(list: Variable[]): void {
        const next = new Map<string, Variable>();
        for (const v of list) {
            const fullName = makeFullName(v.namespace, v.key);
            next.set(fullName, v);
        }
        variables.value = next;
    }

    /**
     * 0.4.0-P2-G 扩展：当前 wall 可用的 user 变量 key 集合（给 VariablePicker / TextElementVariableHints 用）。
     * 返 fullName 列表（含 {@code user:<wallId>/} 前缀）。供 H 任务的 picker 引用，免再扫一遍 store。
     */
    const availableUserKeys = computed<string[]>(() => {
        const result: string[] = [];
        for (const [fullName, v] of variables.value.entries()) {
            if (v.namespace.startsWith('user:')) result.push(fullName);
        }
        return result;
    });

    return {
        variables,
        set, get, remove, clear, reset,
        all, byNamespace,
        initVariables, availableUserKeys,
    };
});
