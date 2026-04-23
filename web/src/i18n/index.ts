import { computed, type ComputedRef } from 'vue';
import { useUiStore } from '@/stores/ui';
import { messages, type Messages } from './messages';

/**
 * 组件里取当前 locale 的 messages 表。
 * 用法：
 *   const { t } = useI18n();
 *   // template: {{ t.topbar.toggleLeft }}
 *   // 函数型：{{ t.status.session(net.sessionId) }}
 *
 * Vue 自动在 template 里 unwrap ComputedRef，访问属性无需 `.value`。
 */
export function useI18n(): { t: ComputedRef<Messages> } {
    const ui = useUiStore();
    const t = computed<Messages>(() => messages[ui.locale]);
    return { t };
}
