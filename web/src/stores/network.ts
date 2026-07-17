import { defineStore } from 'pinia';
import { ref, computed } from 'vue';

/**
 * 模块级单调自增日志序号。同毫秒多条日志的 ts 会碰撞，用作 v-for key 时
 * 内容相同的两条会撞 key（Vue 复用错 DOM）。每条日志带唯一 id 作稳定 key。
 */
let logSeq = 0;

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

    type LogLine = { id: number; ts: number; level: 'sent' | 'recv' | 'meta' | 'err'; text: string };
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
        // 每条带唯一 id（模块级单调自增）作为 LogDrawer v-for 的稳定 key，
        // 避免同毫秒同内容日志 ts+text 派生 key 碰撞。
        logs.value.push({ id: logSeq++, ts: Date.now(), level, text });
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
