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

/**
 * 把传输层哨兵异常（`ack_timeout` / `send_failed` / `connection closed before ack`）
 * 转为当前界面语言的用户友好提示；已是业务文案的 Error 原样返回其 message。
 */
export function formatUiError(err: unknown): string {
    const t = messages[useUiStore().locale];
    const raw = err instanceof Error ? err.message : String(err ?? '');
    if (raw === 'ack_timeout') {
        return t.errors.TIMEOUT;
    }
    if (raw === 'send_failed' || raw === 'connection closed before ack') {
        return t.errors.CONNECTION_CLOSED;
    }
    return raw || t.errors.UNKNOWN;
}
