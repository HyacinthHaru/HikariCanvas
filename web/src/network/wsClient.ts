import type {
    Envelope,
    ReadyPayload,
    ErrorPayload,
    StatePatchPayload,
    StateSnapshotPayload,
} from '@/types/protocol';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';

const RECONNECT_TOKEN_KEY = 'hikari-canvas:reconnect-token';
const HEARTBEAT_INTERVAL_MS = 20_000;  // 协议 §1 要求 30s；20s 留一次丢包容错

/**
 * WS 协议客户端单例封装（M5-A3）。
 * 直接操作 {@link useNetworkStore} / {@link useProjectStore} 的响应式状态，
 * UI 组件只需订阅 store 即可。
 *
 * <p>设计：一个 Pinia app 内只会创建一个 WsClient 实例（见 {@link createWsClient}），
 * main.ts 启动时调 {@link connect}；重连 / 自动重试逻辑 M5-A 阶段先保留手动 reconnect，
 * 完整的 5s/10s/30s 阶梯重连留 M5-B 或 M7 polish。</p>
 */
export class WsClient {
    private ws: WebSocket | null = null;
    private seq = 0;
    private heartbeatTimer: number | null = null;

    constructor(private readonly url: string) {}

    get raw(): WebSocket | null { return this.ws; }

    connect(token: string | null): void {
        const net = useNetworkStore();
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            net.pushLog('meta', 'connect ignored: already open');
            return;
        }
        if (!token) {
            net.lastError = 'no token; open editor via /canvas confirm in-game';
            net.pushLog('err', net.lastError);
            return;
        }
        net.connecting = true;
        net.lastError = null;
        net.pushLog('meta', `connecting ${this.url}`);
        const sock = new WebSocket(this.url);

        sock.addEventListener('open', () => {
            this.ws = sock;
            net.connected = true;
            net.connecting = false;
            net.pushLog('meta', 'ws open');
            this.sendAuth(token);
        });
        sock.addEventListener('message', (ev) => this.onMessage(ev.data as string));
        sock.addEventListener('close', (ev) => this.onClose(ev));
        sock.addEventListener('error', () => {
            net.pushLog('err', 'socket error');
        });
    }

    close(reason = 'client close'): void {
        this.stopHeartbeat();
        this.ws?.close(1000, reason);
    }

    /** 发送带信封的 op。未连接时 log err 并 drop，不抛异常——UI 轻量级体验。 */
    send(op: string, payload?: unknown): string | null {
        const net = useNetworkStore();
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            net.pushLog('err', `send "${op}" dropped: socket not open`);
            return null;
        }
        const id = `c-${this.seq++}`;
        const env: Envelope = { v: 1, op, id, ts: Date.now(), payload };
        const text = JSON.stringify(env);
        this.ws.send(text);
        // 不记 auth 原文（token 敏感）
        if (op === 'auth') {
            net.pushLog('sent', '→ {"op":"auth", ...}');
        } else {
            net.pushLog('sent', `→ ${text}`);
        }
        return id;
    }

    // ---------- 内部 ----------

    private sendAuth(token: string): void {
        this.send('auth', { token });
    }

    private startHeartbeat(): void {
        this.stopHeartbeat();
        this.heartbeatTimer = window.setInterval(() => {
            const net = useNetworkStore();
            if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !net.authenticated) return;
            const env: Envelope = { v: 1, op: 'ping', id: `c-hb-${this.seq++}`, ts: Date.now() };
            this.ws.send(JSON.stringify(env));
        }, HEARTBEAT_INTERVAL_MS);
    }

    private stopHeartbeat(): void {
        if (this.heartbeatTimer !== null) {
            window.clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    }

    private onMessage(text: string): void {
        const net = useNetworkStore();
        net.pushLog('recv', `← ${text}`);

        let env: Envelope;
        try {
            env = JSON.parse(text) as Envelope;
        } catch {
            net.pushLog('err', 'malformed frame');
            return;
        }

        switch (env.op) {
            case 'ready':
                this.handleReady(env.payload as ReadyPayload);
                break;
            case 'state.snapshot':
                this.handleSnapshot(env.payload as StateSnapshotPayload);
                break;
            case 'state.patch':
                this.handlePatch(env.payload as StatePatchPayload);
                break;
            case 'error':
                this.handleError(env.payload as ErrorPayload);
                break;
            case 'pong':
            case 'ack':
                // 无特殊动作；已在日志里
                break;
            default:
                net.pushLog('meta', `unhandled op: ${env.op}`);
        }
    }

    private handleReady(payload: ReadyPayload): void {
        const net = useNetworkStore();
        const project = useProjectStore();
        net.authenticated = true;
        net.sessionId = payload.sessionId;
        net.serverVersion = payload.serverVersion;
        net.wallSize = {
            w: payload.projectState.canvas.widthMaps,
            h: payload.projectState.canvas.heightMaps,
        };
        project.setSnapshot(payload.projectState);
        // rotate 过来的新 token 存 sessionStorage 供断线重连
        if (payload.reconnectToken) {
            try {
                sessionStorage.setItem(RECONNECT_TOKEN_KEY, payload.reconnectToken);
                net.pushLog('meta', `reconnect token stored (len ${payload.reconnectToken.length})`);
            } catch {
                net.pushLog('err', 'sessionStorage unavailable; reconnect disabled');
            }
        }
        this.startHeartbeat();
    }

    private handleSnapshot(payload: StateSnapshotPayload): void {
        useProjectStore().setSnapshot(payload.projectState);
    }

    private handlePatch(payload: StatePatchPayload): void {
        useProjectStore().applyPatch(payload.version, payload.ops);
    }

    private handleError(payload: ErrorPayload): void {
        const net = useNetworkStore();
        if (payload.code === 'AUTH_FAILED') {
            net.lastError = 'auth failed — token may be consumed or expired';
        }
        net.pushLog('err', `${payload.code}: ${payload.message}`);
    }

    private onClose(ev: CloseEvent): void {
        const net = useNetworkStore();
        this.stopHeartbeat();
        net.closeCode = ev.code;
        net.reset();
        if (this.ws === null || !this.wasActive()) {
            this.ws = null;
        }
        net.pushLog('meta', `ws closed code=${ev.code}${ev.reason ? ` reason="${ev.reason}"` : ''}`);
        if (ev.code === 4001) {
            net.lastError = 'auth failed (WS close 4001)';
        } else if (!net.lastError) {
            net.lastError = `disconnected (code ${ev.code})`;
        }
    }

    private wasActive(): boolean {
        return this.ws !== null && this.ws.readyState !== WebSocket.CLOSED;
    }
}

// ---------- singleton ----------

let singleton: WsClient | null = null;

export function createWsClient(): WsClient {
    if (!singleton) singleton = new WsClient(resolveWsUrl());
    return singleton;
}

export function getWsClient(): WsClient {
    if (!singleton) throw new Error('WsClient not initialized; call createWsClient() first');
    return singleton;
}

export function pickInitialToken(): { token: string | null; source: 'url' | 'session-storage' | 'none' } {
    const url = new URLSearchParams(location.search).get('token');
    if (url) return { token: url, source: 'url' };
    try {
        const stored = sessionStorage.getItem(RECONNECT_TOKEN_KEY);
        if (stored) return { token: stored, source: 'session-storage' };
    } catch { /* ignore */ }
    return { token: null, source: 'none' };
}

function resolveWsUrl(): string {
    // 同源（被 WebServer 自己 serve）→ ws://host/ws；否则固定连本机 8877
    const loc = window.location;
    if (loc.hostname === '127.0.0.1' && loc.port === '8877') {
        const scheme = loc.protocol === 'https:' ? 'wss:' : 'ws:';
        return `${scheme}//${loc.host}/ws`;
    }
    return 'ws://127.0.0.1:8877/ws';
}
