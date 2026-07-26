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
    /**
     * 统一错误显示通道：连接层与业务层都往这里写（TopBar / scriptEdit /
     * ScriptVariableWatch / ConditionBuilder 等），StatusBar 与日志面板订阅它。
     */
    const lastError = ref<string | null>(null);
    /**
     * 连接层错误，<b>只</b>由 {@link '@/network/wsClient'} 写（走 {@link setConnectionError}）。
     *
     * <p>{@link status} 据此判 'error'。此前 status 直接判 lastError，而 lastError
     * 的写入方包含大量业务路径，于是一次业务失败（改变量失败 / 规则校验不过）就让连接
     * 状态栏持续显示 error 直到重连——纯误导。业务错误现在只走 lastError，不染红连接指示。</p>
     */
    const connectionError = ref<string | null>(null);
    /**
     * 最近一次服务端 op 错误。每次 handleError 都更新，用于组件 watch ts 判定"我发的那一帧失败了"。
     *
     * <p>{@code opId} = 失败那一帧的信封 id（服务端把请求 id 原样回在 error 信封里）。发过 op 的
     * 组件据此精确认领：只有 id 对得上才回滚自己那次乐观更新，不会把别人的改动一起撤了。</p>
     */
    const lastOpError = ref<{ code: string; message: string; ts: number; opId?: string } | null>(null);
    const closeCode = ref<number | null>(null);

    type LogLine = { id: number; ts: number; level: 'sent' | 'recv' | 'meta' | 'err'; text: string };
    const logs = ref<LogLine[]>([]);
    const MAX_LOG = 200;

    const status = computed<'disconnected' | 'connecting' | 'authenticating' | 'ready' | 'error'>(() => {
        if (connectionError.value) return 'error';
        if (authenticated.value) return 'ready';
        if (connecting.value) return 'connecting';
        if (connected.value) return 'authenticating';
        return 'disconnected';
    });

    /** 连接层错误：同时写 connectionError（染红状态栏）与 lastError（统一显示通道）。 */
    function setConnectionError(msg: string) {
        connectionError.value = msg;
        lastError.value = msg;
    }

    /** 连接成功 / 重新 connect 时清两个错误位。 */
    function clearErrors() {
        connectionError.value = null;
        lastError.value = null;
    }

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
        lastError, connectionError, lastOpError, closeCode,
        logs, status,
        pushLog, clearLogs, reset,
        setConnectionError, clearErrors,
    };
});
