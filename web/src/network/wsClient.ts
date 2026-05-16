import type {
    Envelope,
    ReadyPayload,
    ErrorPayload,
    StatePatchPayload,
    StateSnapshotPayload,
} from '@/types/protocol';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useTemplatesStore } from '@/stores/templates';

const RECONNECT_TOKEN_KEY = 'hikari-canvas:reconnect-token';
const HEARTBEAT_INTERVAL_MS = 20_000;  // 协议 §1 要求 30s；20s 留一次丢包容错
/** 重连退避阶梯（秒）。超过最后一档就停。 */
const RECONNECT_BACKOFF_S = [1, 2, 5, 10, 30];

/**
 * WS 协议客户端单例封装（M5-A3）。
 * 直接操作 {@link useNetworkStore} / {@link useProjectStore} 的响应式状态，
 * UI 组件只需订阅 store 即可。
 *
 * <p>设计：一个 Pinia app 内只会创建一个 WsClient 实例（见 {@link createWsClient}），
 * main.ts 启动时调 {@link connect}；重连 / 自动重试逻辑 M5-A 阶段先保留手动 reconnect，
 * 完整的 5s/10s/30s 阶梯重连留 M5-B 或 M7 polish。</p>
 */
type PendingAck = {
    resolve: (payload: unknown) => void;
    reject: (err: Error) => void;
    timer: number;
};

export class WsClient {
    private ws: WebSocket | null = null;
    private seq = 0;
    private heartbeatTimer: number | null = null;
    private reconnectTimer: number | null = null;
    private reconnectAttempt = 0;
    /** 最近一次成功连接时用的 token；onClose 触发重连时复用。 */
    private lastToken: string | null = null;
    /** 用户主动关闭或 auth 已判死时置 true，阻止自动重连。 */
    private stopped = false;
    /** 按 client id 跟踪等待 ack 的 promise（sendWithAck 用）。 */
    private pendingAcks = new Map<string, PendingAck>();

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
        this.lastToken = token;
        this.stopped = false;
        this.clearReconnect();
        net.connecting = true;
        net.lastError = null;
        net.pushLog('meta', `connecting ${this.url}`);
        const sock = new WebSocket(this.url);

        sock.addEventListener('open', () => {
            this.ws = sock;
            net.connected = true;
            net.connecting = false;
            this.reconnectAttempt = 0;
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
        this.stopped = true;
        this.clearReconnect();
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
        // M8-C：协议 v2，envelope.v=2；auth 帧负载需带 clientProtocolVersion=2
        const env: Envelope = { v: 2, op, id, ts: Date.now(), payload };
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

    /**
     * 像 {@link send} 一样发送，但返回一个 Promise，在服务端 ack（resolve payload）或
     * error（reject Error）回到来时落定。带 {@code timeoutMs} 超时（默认 5s，0 = 无限）。
     *
     * <p>当 socket 未开 / 发送失败时 reject 一个 'send_failed' Error。组件只关心 promise 状态，
     * 不需要自己轮询 state/lastOpError 时间戳。</p>
     */
    sendWithAck(op: string, payload?: unknown, timeoutMs = 5000): Promise<unknown> {
        const id = this.send(op, payload);
        if (!id) return Promise.reject(new Error('send_failed'));
        return new Promise<unknown>((resolve, reject) => {
            const timer = timeoutMs > 0
                ? window.setTimeout(() => {
                    this.pendingAcks.delete(id);
                    reject(new Error('ack_timeout'));
                }, timeoutMs)
                : 0;
            this.pendingAcks.set(id, { resolve, reject, timer });
        });
    }

    // ---------- 内部 ----------

    private sendAuth(token: string): void {
        // M8-C：v2 协议强制声明 clientProtocolVersion；后端 < 2 直接 close 4002
        this.send('auth', { token, clientProtocolVersion: 2 });
    }

    private startHeartbeat(): void {
        this.stopHeartbeat();
        this.heartbeatTimer = window.setInterval(() => {
            const net = useNetworkStore();
            if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !net.authenticated) return;
            const env: Envelope = { v: 2, op: 'ping', id: `c-hb-${this.seq++}`, ts: Date.now() };
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
                this.handleError(env.id, env.payload as ErrorPayload);
                break;
            case 'pong':
                break;
            case 'ack':
                this.handleAck(env.id, env.payload);
                break;
            default:
                net.pushLog('meta', `unhandled op: ${env.op}`);
        }
    }

    private handleReady(payload: ReadyPayload): void {
        const net = useNetworkStore();
        const project = useProjectStore();
        const templates = useTemplatesStore();
        net.authenticated = true;
        net.sessionId = payload.sessionId;
        net.serverVersion = payload.serverVersion;
        net.wallSize = {
            w: payload.projectState.canvas.widthMaps,
            h: payload.projectState.canvas.heightMaps,
        };
        project.setSnapshot(payload.projectState);
        project.setWallMeta(
            payload.wallId ?? null,
            payload.alias ?? null,
            payload.lockedAt ?? null,
            payload.ownerUuid ?? null,
            payload.selfUuid ?? null,
        );
        // M6-D：缓存全量 TemplateSpec 列表，供 TemplateGallery 使用
        templates.setTemplates(payload.templates ?? []);
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

    /** ack payload 可能含 wall.* op 的副作用（lockedAt / alias）；同步进 store。
     *  2026-05-14：publishedAt → lockedAt 重命名。 */
    private handleAck(ackId: string | undefined, payload: unknown): void {
        // 1) sendWithAck 的 Promise resolve（与 store 副作用解耦）
        if (ackId && this.pendingAcks.has(ackId)) {
            const pending = this.pendingAcks.get(ackId)!;
            if (pending.timer) window.clearTimeout(pending.timer);
            this.pendingAcks.delete(ackId);
            pending.resolve(payload);
        }
        // 2) store 副作用
        if (!payload || typeof payload !== 'object') return;
        const project = useProjectStore();
        const p = payload as { lockedAt?: unknown; locked?: unknown; alias?: unknown };
        if (typeof p.lockedAt === 'number') {
            project.lockedAt = p.lockedAt;  // wall.lock ack
        } else if (p.locked === false) {
            project.lockedAt = null;          // wall.unlock ack（M15.1 改协议）
        }
        if (typeof p.alias === 'string') {
            project.alias = p.alias;
        }
    }

    private handlePatch(payload: StatePatchPayload): void {
        useProjectStore().applyPatch(payload.version, payload.ops);
    }

    private handleError(errId: string | undefined, payload: ErrorPayload): void {
        const net = useNetworkStore();
        if (payload.code === 'AUTH_FAILED') {
            net.lastError = 'auth failed — token may be consumed or expired';
        }
        net.lastOpError = {
            code: payload.code,
            message: payload.message ?? payload.code,
            ts: Date.now(),
        };
        net.pushLog('err', `${payload.code}: ${payload.message}`);
        // sendWithAck 等的 promise → reject
        if (errId && this.pendingAcks.has(errId)) {
            const pending = this.pendingAcks.get(errId)!;
            if (pending.timer) window.clearTimeout(pending.timer);
            this.pendingAcks.delete(errId);
            pending.reject(new Error(`${payload.code}: ${payload.message ?? ''}`));
        }
    }

    private onClose(ev: CloseEvent): void {
        const net = useNetworkStore();
        this.stopHeartbeat();
        this.ws = null;
        net.closeCode = ev.code;
        net.reset();
        net.pushLog('meta', `ws closed code=${ev.code}${ev.reason ? ` reason="${ev.reason}"` : ''}`);

        // M5-D7：按 close code 判断是否重连
        // - 1000 (normal) / 4001 (auth failed) / 4008 (rate limit) → 不重连
        // - 1006 (server down) / 1011 (server error) / 1001 (going away) → 退避重连
        const terminal = ev.code === 1000 || ev.code === 4001 || ev.code === 4008;
        if (this.stopped || terminal) {
            if (ev.code === 4001) {
                net.lastError = '认证失败 — token 已失效，请在游戏里重新 /canvas edit';
                this.clearStoredToken();
            } else if (ev.code === 1000) {
                // 主动关闭，不刷红
            } else {
                net.lastError = `连接关闭 (code ${ev.code})`;
            }
            return;
        }

        this.scheduleReconnect();
    }

    private scheduleReconnect(): void {
        const net = useNetworkStore();
        if (this.reconnectAttempt >= RECONNECT_BACKOFF_S.length) {
            net.lastError = '服务器长时间不可达，请刷新页面或在游戏里重新 /canvas edit';
            return;
        }
        const delay = RECONNECT_BACKOFF_S[this.reconnectAttempt] * 1000;
        this.reconnectAttempt += 1;
        net.lastError = `连接断开，${delay / 1000}s 后重试（第 ${this.reconnectAttempt} 次）`;
        net.pushLog('meta', `reconnect scheduled in ${delay}ms`);
        this.reconnectTimer = window.setTimeout(() => {
            this.reconnectTimer = null;
            if (this.stopped) return;
            const token = this.pickTokenForReconnect();
            if (!token) {
                net.lastError = 'token 丢失，请刷新页面或重新 /canvas edit';
                return;
            }
            this.connect(token);
        }, delay);
    }

    private clearReconnect(): void {
        if (this.reconnectTimer !== null) {
            window.clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    private pickTokenForReconnect(): string | null {
        // 优先 sessionStorage（服务器 rotate 后的 reconnect token）；退回当前 lastToken
        try {
            const stored = sessionStorage.getItem(RECONNECT_TOKEN_KEY);
            if (stored) return stored;
        } catch { /* ignore */ }
        return this.lastToken;
    }

    private clearStoredToken(): void {
        try { sessionStorage.removeItem(RECONNECT_TOKEN_KEY); } catch { /* ignore */ }
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
