import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

/**
 * VariableAliasStore 前端镜像（0.4.2）。
 *
 * <p>来源：ready payload 的 {@code aliases} 字段（{@code Record<fullName, alias>}）+ state.patch
 * 中 path 前缀为 {@code /aliases/} 的 op。前端不主动 fetch——server-as-truth，UI 只读本 store。</p>
 *
 * <p>别名是 wall-scoped 状态；wall 切换时由 {@code project.reset()} 触发 {@link reset}。
 * 单层 {@code Map<fullName, alias>}，简单 in-place 替换避免循环引用：</p>
 *
 * <pre>
 *   const aliasStore = useVariableAliasStore();
 *   aliasStore.get('user:w-abc/red_score');  // → "红队"  或  null
 *   aliasStore.set('user:w-abc/red_score', '红队');  // 仅本地 mutate；网络写需调 wsClient.sendVariableAliasSet
 * </pre>
 */
export const useVariableAliasStore = defineStore('variableAliases', () => {
    /** 主表：fullName → alias 字符串。Map 实例每次 mutation 重建以触发 Vue 响应。 */
    const aliases = ref<Map<string, string>>(new Map());

    function set(fullName: string, alias: string): void {
        const next = new Map(aliases.value);
        next.set(fullName, alias);
        aliases.value = next;
    }

    function remove(fullName: string): void {
        if (!aliases.value.has(fullName)) return;
        const next = new Map(aliases.value);
        next.delete(fullName);
        aliases.value = next;
    }

    /** 取别名；不存在返 null（而非 undefined，让消费方写 `alias ?? fullName` 更直观）。 */
    function get(fullName: string): string | null {
        return aliases.value.get(fullName) ?? null;
    }

    function has(fullName: string): boolean {
        return aliases.value.has(fullName);
    }

    function clear(): void {
        if (aliases.value.size === 0) return;
        aliases.value = new Map();
    }

    /** 等同 {@link clear}；显式 alias 让 project / ui store 切 wall 时调用，与 variables store 对齐。 */
    function reset(): void {
        clear();
    }

    /**
     * 批量初始化：用 ready payload.aliases 一次性赋值。避免循环触发 N 次响应。
     */
    function initAliases(map: Record<string, string> | null | undefined): void {
        if (!map) {
            aliases.value = new Map();
            return;
        }
        aliases.value = new Map(Object.entries(map));
    }

    /** 别名总数；UI badge 用。 */
    const size = computed(() => aliases.value.size);

    return {
        aliases,
        set, get, has, remove, clear, reset,
        initAliases,
        size,
    };
});
