import type {
    Envelope,
    ReadyPayload,
    ErrorPayload,
    StatePatchPayload,
    StateSnapshotPayload,
    PatchOp,
} from '@/types/protocol';
import type { Variable, VariablePatch, VarType } from '@/types/variable';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useTemplatesStore } from '@/stores/templates';
import { useUiStore } from '@/stores/ui';
import { useVariableStore } from '@/stores/variables';
import { useVariableAliasStore } from '@/stores/variableAliases';
import { useScheduleStore } from '@/stores/schedule';
import { messages } from '@/i18n/messages';

/**
 * M25 任务 2A：把 server 端 error code 翻译成对用户友好的 i18n 文案。
 * 找不到对应 key 时回退 UNKNOWN，最后回退原 code。直接读 messages 表（非 useI18n composable），
 * 因为 wsClient 是单例 ts class 不是 Vue setup。locale 从 useUiStore() 拿。
 */
function localizeErrorCode(code: string, fallbackMessage?: string): string {
    const ui = useUiStore();
    const errs = messages[ui.locale]?.errors as Record<string, string> | undefined;
    if (errs && typeof errs[code] === 'string') return errs[code];
    if (errs && typeof errs.UNKNOWN === 'string') {
        // 兜底文案 + 附原 code，便于排查 / 反馈
        return fallbackMessage ? `${errs.UNKNOWN}（${code}）` : `${errs.UNKNOWN}（${code}）`;
    }
    return fallbackMessage ?? code;
}

const RECONNECT_TOKEN_KEY = 'hikari-canvas:reconnect-token';
const HEARTBEAT_INTERVAL_MS = 20_000;  // 协议 §1 要求 30s；20s 留一次丢包容错
/** 重连退避阶梯（秒）。超过最后一档就停。 */
const RECONNECT_BACKOFF_S = [1, 2, 5, 10, 30];
/**
 * M16 P6.2：business protocol version（区别于 envelope.v 消息壳版本）。
 * auth 帧携这个值发给 server，server 在范围内则 ready 回 {@code accepted_v}；
 * client 收到 ready 后再次校验 {@code accepted_v === CLIENT_V}，不匹配则断开。
 * 升级时与 {@code plugin/.../Protocol.java SUPPORTED_MIN/MAX} 同步改。
 */
const CLIENT_V = 2;

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

    // ---------- 变量系统（0.4.0-P1-D，协议契约见 docs/protocol.md §5.11）----------
    //
    // 5 个 op 都走 ack 通道，副作用（VariableStore mirror）由后端发 state.patch 推回；
    // send 方法本身仅负责发送，不预测性 mutate 本地 store——保持 server-as-truth。

    /**
     * `variable.create`：在当前 wall 上创建用户变量。
     * 后端自动加 {@code user:<wallId>/} 前缀。
     */
    sendVariableCreate(name: string, type: VarType, defaultValue?: string | null): Promise<void> {
        return this.sendWithAck('variable.create', { name, type, defaultValue }).then(() => undefined);
    }

    /** `variable.update`：改 user/* 变量的 type / defaultValue。 */
    sendVariableUpdate(fullName: string, patch: VariablePatch): Promise<void> {
        return this.sendWithAck('variable.update', { fullName, patch }).then(() => undefined);
    }

    /** `variable.set`：玩家手动改 user/* 变量当前值。 */
    sendVariableSet(fullName: string, value: string): Promise<void> {
        return this.sendWithAck('variable.set', { fullName, value }).then(() => undefined);
    }

    /** `variable.delete`：删除 user/* 变量；引用该变量的 element 渲染时走 fallback。 */
    sendVariableDelete(fullName: string): Promise<void> {
        return this.sendWithAck('variable.delete', { fullName }).then(() => undefined);
    }

    /** `variable.bind`：让 user/* 变量被插件 push 接管；{@code boundTo = null} 解绑。 */
    sendVariableBind(fullName: string, boundTo: string | null): Promise<void> {
        return this.sendWithAck('variable.bind', { fullName, boundTo }).then(() => undefined);
    }

    // ---------- 变量别名（0.4.2，全 namespace 通用，per-wall 隔离）----------
    //
    // 2 个写 op；ack 通道 + 服务端推 /aliases/<encoded> state.patch 更新 VariableAliasStore mirror。

    /** {@code variable.alias.set}：给 fullName 起别名（覆盖已有）。alias 1..64 字符。 */
    sendVariableAliasSet(fullName: string, alias: string): Promise<void> {
        return this.sendWithAck('variable.alias.set', { fullName, alias })
            .then(() => undefined);
    }

    /** {@code variable.alias.clear}：清掉 fullName 的别名（即使不存在也幂等）。 */
    sendVariableAliasClear(fullName: string): Promise<void> {
        return this.sendWithAck('variable.alias.clear', { fullName })
            .then(() => undefined);
    }

    // ---------- 列车时刻表（0.4.0-P3-L，协议契约见 docs/protocol.md §5.12）----------
    //
    // 5 个 op 都走 ack 通道；ScheduleStore mirror 由各方法返回的 payload 自己更新。

    /** {@code schedule.list}：查当前 wall 的完整时刻表。返 ack payload {schedule: WallSchedule|null}。 */
    sendScheduleList(): Promise<{ schedule: import('@/types/schedule').WallSchedule | null }> {
        return this.sendWithAck('schedule.list', {}, 8000)
                .then((p) => p as { schedule: import('@/types/schedule').WallSchedule | null });
    }

    /**
     * {@code schedule.upsert}：创建 / 更新 schedule 元数据（站名 + 0.4.0 bugfix Bug 4 precision）。
     * precision 可选；不传时 server 保留现有值（首次 upsert 默认 minute）。
     */
    sendScheduleUpsert(
        stationName: string | null,
        precision?: import('@/types/schedule').SchedulePrecision,
    ): Promise<{ stationName: string | null; precision?: import('@/types/schedule').SchedulePrecision }> {
        const payload: { stationName: string | null; precision?: string } = { stationName };
        if (precision != null) payload.precision = precision;
        return this.sendWithAck('schedule.upsert', payload)
                .then((p) => p as {
                    stationName: string | null;
                    precision?: import('@/types/schedule').SchedulePrecision;
                });
    }

    /** {@code schedule.entry.add}：添加时刻表条目；返新生成的 id + 字段回填。 */
    sendScheduleEntryAdd(
        departureTime: string, destination: string | null, sortOrder: number,
    ): Promise<import('@/types/schedule').ScheduleEntryAck> {
        return this.sendWithAck('schedule.entry.add', { departureTime, destination, sortOrder })
                .then((p) => p as import('@/types/schedule').ScheduleEntryAck);
    }

    /** {@code schedule.entry.update}：按 id 改条目。 */
    sendScheduleEntryUpdate(
        id: number, departureTime: string, destination: string | null, sortOrder: number,
    ): Promise<import('@/types/schedule').ScheduleEntryAck> {
        return this.sendWithAck('schedule.entry.update', {
            id, departureTime, destination, sortOrder,
        }).then((p) => p as import('@/types/schedule').ScheduleEntryAck);
    }

    /** {@code schedule.entry.delete}：按 id 删条目。 */
    sendScheduleEntryDelete(id: number): Promise<{ id: number }> {
        return this.sendWithAck('schedule.entry.delete', { id })
                .then((p) => p as { id: number });
    }

    // ---------- 内部 ----------

    private sendAuth(token: string): void {
        // M16 P6.2：发新字段 client_v；旧字段 clientProtocolVersion 同步发以兼容回滚
        // 到旧服务端 jar 的情形（旧后端不识别 client_v，识别 clientProtocolVersion）。
        // 新后端（M16+）优先读 client_v；范围检查通过 → ready 回 accepted_v。
        this.send('auth', { token, client_v: CLIENT_V, clientProtocolVersion: CLIENT_V });
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
        const ui = useUiStore();
        // M16 P6.2：双向校验业务协议版本——server 同意了 accepted_v 但若与 CLIENT_V
        // 不一致（如运维误装错版本），客户端主动断开避免后续 op 行为漂移。
        // 旧后端不发 accepted_v → undefined → 沿用 M8-C 的 v2 默认（信任 server）。
        const acceptedV = payload.accepted_v;
        if (typeof acceptedV === 'number' && acceptedV !== CLIENT_V) {
            net.lastError = `协议版本不兼容 (server accepted_v=${acceptedV}, client=${CLIENT_V})；请升级`;
            net.pushLog('err', `protocol version mismatch: accepted_v=${acceptedV} client_v=${CLIENT_V}`);
            this.stopped = true;
            try { this.ws?.close(4002, 'protocol_version_unsupported'); } catch { /* ignore */ }
            return;
        }
        // M16 P4.2：切到新 wall 时清掉旧 wall 残留状态（selectedIds / lockedAt / state...）。
        // 同 wall 重连（wallId 不变）保留 UI 上下文，避免重连闪烁。
        const incomingWallId = payload.wallId ?? null;
        if (project.wallId !== null && project.wallId !== incomingWallId) {
            project.reset();
            ui.reset();
            // 0.4.0-P3-L：schedule 是 wall-scoped 元数据；wall 切换时清旧 wall 缓存
            useScheduleStore().reset();
            // 0.4.2：alias 也是 wall-scoped；与 schedule 同款 reset
            useVariableAliasStore().reset();
        }
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
        // 0.4.0-P2-I：用 ready payload 携带的 variables 快照一次性初始化 VariableStore mirror；
        // 后续变更走 state.patch /variables/<encoded> 路径（见 applyVariablePatches）。
        useVariableStore().initVariables(payload.variables ?? []);
        // 0.4.2：变量别名快照（per-wall）；后续变更走 state.patch /aliases/<encoded>。
        useVariableAliasStore().initAliases(payload.aliases ?? {});
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
        // 0.4.0-P1-D：variables 走 global VariableStore 而非 ProjectState；按 patch.path
        // 前缀分拣后再分别落 store。剩余 patch 仍走 project.applyPatch（既有路径不变）。
        // 0.4.2：aliases 同款分拣到 VariableAliasStore。
        const variableOps: PatchOp[] = [];
        const aliasOps: PatchOp[] = [];
        const projectOps: PatchOp[] = [];
        for (const op of payload.ops) {
            if (op.path.startsWith('/variables/')) variableOps.push(op);
            else if (op.path.startsWith('/aliases/')) aliasOps.push(op);
            else projectOps.push(op);
        }
        if (variableOps.length > 0) {
            applyVariablePatches(variableOps);
        }
        if (aliasOps.length > 0) {
            applyAliasPatches(aliasOps);
        }
        // 即便 projectOps 为空也要更新 version 号（version 是 wall-scoped 单调递增）
        useProjectStore().applyPatch(payload.version, projectOps);
    }

    private handleError(errId: string | undefined, payload: ErrorPayload): void {
        const net = useNetworkStore();
        // M25 任务 2A：把后端 code 翻译成 i18n friendly message；保留 raw code 进 lastOpError + log
        // （方便 LogDrawer 排查、客户端 i18n key 缺失时回退）。
        const friendly = localizeErrorCode(payload.code, payload.message);
        if (payload.code === 'AUTH_FAILED') {
            net.lastError = friendly;
        }
        net.lastOpError = {
            code: payload.code,
            message: friendly,
            ts: Date.now(),
        };
        // log 仍保留原文（含 server side detail），LogDrawer 显示给开发者；UI 状态走 friendly。
        net.pushLog('err', `${payload.code}: ${payload.message ?? friendly}`);
        // sendWithAck 等的 promise → reject（携 friendly 给消费者展示）
        if (errId && this.pendingAcks.has(errId)) {
            const pending = this.pendingAcks.get(errId)!;
            if (pending.timer) window.clearTimeout(pending.timer);
            this.pendingAcks.delete(errId);
            pending.reject(new Error(`${payload.code}: ${friendly}`));
        }
    }

    private onClose(ev: CloseEvent): void {
        const net = useNetworkStore();
        this.stopHeartbeat();
        this.ws = null;
        net.closeCode = ev.code;
        net.reset();
        net.pushLog('meta', `ws closed code=${ev.code}${ev.reason ? ` reason="${ev.reason}"` : ''}`);

        // M16 P4.3：清空所有未完成 ack，让 await sendWithAck 的消费者收到 rejection
        // 而非永远 pending。重连后是干净的 Map，不混前后两次连接的 ack 序号。
        // 注意 seq 计数器不重置——保留现有行为（id 全局递增）。
        if (this.pendingAcks.size > 0) {
            for (const [, pending] of this.pendingAcks) {
                if (pending.timer) window.clearTimeout(pending.timer);
                pending.reject(new Error('connection closed before ack'));
            }
            this.pendingAcks.clear();
        }

        // M5-D7：按 close code 判断是否重连
        // - 1000 (normal) / 4001 (auth failed) / 4008 (rate limit) → 不重连
        // - 1006 (server down) / 1011 (server error) / 1001 (going away) → 退避重连
        const terminal = ev.code === 1000 || ev.code === 4001 || ev.code === 4008;
        if (this.stopped || terminal) {
            if (ev.code === 4001) {
                // M25 任务 2A：友好提示走 i18n（AUTH_FAILED 中英文都覆盖到 token 提示）
                net.lastError = localizeErrorCode('AUTH_FAILED');
                this.clearStoredToken();
            } else if (ev.code === 1000) {
                // 主动关闭，不刷红
            } else {
                net.lastError = `${localizeErrorCode('CONNECTION_CLOSED')}（code ${ev.code}）`;
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

// ---------- 变量 state.patch 路由（0.4.0-P1-D）----------
//
// 后端发 path 形如 {@code /variables/<encoded>/currentValue}；encoded 是 fullName 用
// RFC 6901 {@code ~1} 转义 {@code /} 后的字符串。本路由解码后落到 VariableStore。
//
// 支持的形态：
//   - add    /variables/<encoded>              value = 完整 Variable JSON
//   - replace /variables/<encoded>/currentValue value = 新值（string | null）
//   - remove /variables/<encoded>
// 其他 path 形态不支持（B 任务限定 patch 形态），收到时静默忽略并 log。

function applyVariablePatches(ops: PatchOp[]): void {
    const store = useVariableStore();
    const net = useNetworkStore();
    for (const op of ops) {
        // strip leading "/variables/"；按首个 "/" 切 encoded fullName + 子路径
        const rest = op.path.substring('/variables/'.length);
        if (rest.length === 0) {
            net.pushLog('err', `variable patch: empty path ${op.path}`);
            continue;
        }
        const slashIdx = rest.indexOf('/');
        const encoded = slashIdx < 0 ? rest : rest.substring(0, slashIdx);
        const sub = slashIdx < 0 ? '' : rest.substring(slashIdx + 1);
        const fullName = decodeJsonPointerToken(encoded);

        if (op.op === 'add' && sub === '') {
            // 整 Variable JSON 落表
            if (op.value && typeof op.value === 'object') {
                store.set(fullName, op.value as Variable);
            } else {
                net.pushLog('err', `variable patch add: missing value for ${fullName}`);
            }
        } else if (op.op === 'remove' && sub === '') {
            store.remove(fullName);
        } else if (op.op === 'replace' && sub === 'currentValue') {
            const v = store.get(fullName);
            if (v) {
                const next: Variable = {
                    ...v,
                    currentValue: (op.value as string | null) ?? null,
                    updatedAt: Date.now(),
                };
                store.set(fullName, next);
            } else {
                // 后端推 replace 但本地无该 var——通常是 race（刚 remove 后又收 replace）
                net.pushLog('meta', `variable patch replace skipped: ${fullName} not in store`);
            }
        } else if (op.op === 'replace' && sub !== '') {
            // 兜底：其他字段 replace（如 type / defaultValue）——B 任务暂未要求，但支持也无害
            const v = store.get(fullName);
            if (v) {
                const next: Variable = { ...v };
                (next as unknown as Record<string, unknown>)[sub] = op.value;
                next.updatedAt = Date.now();
                store.set(fullName, next);
            }
        } else {
            net.pushLog('err', `variable patch: unsupported ${op.op} ${op.path}`);
        }
    }
}

/** RFC 6901：{@code ~1} → {@code /}，{@code ~0} → {@code ~}（顺序必须先 ~1 再 ~0）。 */
function decodeJsonPointerToken(token: string): string {
    return token.replace(/~1/g, '/').replace(/~0/g, '~');
}

// ---------- 别名 state.patch 路由（0.4.2）----------
//
// 后端 dispatcher 发 path 形如 {@code /aliases/<encoded fullName>}（同变量通道 RFC 6901
// 编码 ~ → ~0, / → ~1）。支持：add / replace 携 alias 字符串；remove 不携 value。
// 其他形态不支持，收到时静默忽略并 log。

function applyAliasPatches(ops: PatchOp[]): void {
    const store = useVariableAliasStore();
    const net = useNetworkStore();
    for (const op of ops) {
        const rest = op.path.substring('/aliases/'.length);
        if (rest.length === 0) {
            net.pushLog('err', `alias patch: empty path ${op.path}`);
            continue;
        }
        // alias 路径无子路径（最多就是 fullName 编码段），直接整段 decode
        const fullName = decodeJsonPointerToken(rest);
        if ((op.op === 'add' || op.op === 'replace') && typeof op.value === 'string') {
            store.set(fullName, op.value);
        } else if (op.op === 'remove') {
            store.remove(fullName);
        } else {
            net.pushLog('err', `alias patch: unsupported ${op.op} ${op.path}`);
        }
    }
}
