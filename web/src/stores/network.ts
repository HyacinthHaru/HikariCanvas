import { defineStore } from 'pinia';
import { ref, computed } from 'vue';

/**
 * 网络层状态：WS 连接 / auth / token / 日志流。
 * 具体 WS 操作由 {@link '@/network/wsClient'} 封装；本 store 只存可响应式 UI 状态。
 */
export const useNetworkStore = defineStore('network', () => {
    const connected = ref(false);
    const authenticated = ref(false);
    const connecting = ref(false);
    const sessionId = ref<string | null>(null);
    const serverVersion = ref<string | null>(null);
    const wallSize = ref<{ w: number; h: number } | null>(null);
    const lastError = ref<string | null>(null);
    /** 最近一次服务端 op 错误。每次 handleError 都更新，用于组件 watch ts 判定"我发的那一帧失败了"。 */
    const lastOpError = ref<{ code: string; message: string; ts: number } | null>(null);
    const closeCode = ref<number | null>(null);

    type LogLine = { ts: number; level: 'sent' | 'recv' | 'meta' | 'err'; text: string };
    const logs = ref<LogLine[]>([]);
    const MAX_LOG = 200;

    const status = computed<'disconnected' | 'connecting' | 'authenticating' | 'ready' | 'error'>(() => {
        if (lastError.value) return 'error';
        if (authenticated.value) return 'ready';
        if (connecting.value) return 'connecting';
        if (connected.value) return 'authenticating';
        return 'disconnected';
    });

    function pushLog(level: LogLine['level'], text: string) {
        logs.value.push({ ts: Date.now(), level, text });
        if (logs.value.length > MAX_LOG) logs.value.splice(0, logs.value.length - MAX_LOG);
    }

    function clearLogs() {
        logs.value = [];
    }

    function reset() {
        connected.value = false;
        authenticated.value = false;
        connecting.value = false;
    }

    return {
        connected, authenticated, connecting,
        sessionId, serverVersion, wallSize,
        lastError, lastOpError, closeCode,
        logs, status,
        pushLog, clearLogs, reset,
    };
});
