import type {
    Envelope,
    ReadyPayload,
    ErrorPayload,
    StatePatchPayload,
    StateSnapshotPayload,
    PatchOp,
    ScriptTracePayload,
} from '@/types/protocol';
import type { Variable, VariablePatch, VarType } from '@/types/variable';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useTemplatesStore } from '@/stores/templates';
import { useUiStore } from '@/stores/ui';
import type { Locale } from '@/stores/ui';
import { useVariableStore } from '@/stores/variables';
import { useVariableAliasStore } from '@/stores/variableAliases';
import { useRailStore } from '@/stores/rail';
import { useScriptStore } from '@/stores/scripts';
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
        // 兜底文案 + 附原 code，便于排查 / 反馈。P3-86：未映射 code 时优先展示
        // 服务端人类可读说明（fallbackMessage），而非把它丢弃。
        return fallbackMessage
            ? `${fallbackMessage}（${code}）`
            : `${errs.UNKNOWN}（${code}）`;
    }
    return fallbackMessage ?? code;
}

/**
 * 0.9.7：编辑器 UI 语言（2 字母 {@link Locale}）→ game locale id（如 {@code zh_cn} / {@code en_us}）。
 * 后端 auth 读取后走 {@code Messages.resolveLocaleId} 规范化 + 兜底；与命令路径
 * （{@code player.getLocale()} 返回 game locale id）统一，让脚本校验报错按编辑器语言渲染。
 */
function uiLocaleToGameId(locale: Locale): string {
    return locale === 'zh' ? 'zh_cn' : 'en_us';
}

const RECONNECT_TOKEN_KEY = 'hikari-canvas:reconnect-token';
const HEARTBEAT_INTERVAL_MS = 20_000;  // 协议 §1 要求 30s；20s 留一次丢包容错
/** P2-69：open → ready 看门狗超时。超过仍未 authenticated 则强制断开走重连。 */
const READY_TIMEOUT_MS = 10_000;
/** 重连退避阶梯（秒）。超过最后一档就停。 */
const RECONNECT_BACKOFF_S = [1, 2, 5, 10, 30];
/**
 * 双层版本说明（M16 P6.2 引入双层；0.6 升 3、0.7 升 4）：
 *
 * <ul>
 *   <li><b>信封壳 {@code Envelope.v}</b>：消息容器格式版本，恒为 {@link ENVELOPE_V}（=2，
 *       后端 {@code Protocol.Envelope.of} 固定 2 不随业务升级，见
 *       {@code plugin/.../web/Protocol.java} javadoc）。auth / ping 等所有出帧用它做 {@code v}。</li>
 *   <li><b>业务协议版本 {@code CLIENT_V}</b>：ProjectState schema / op 族契约版本。auth 帧 payload
 *       携 {@code client_v} 发给 server，server 在 [SUPPORTED_MIN, SUPPORTED_MAX] 范围内则 ready
 *       回 {@code accepted_v}；client 收到后校验 {@code accepted_v === CLIENT_V}，不匹配则断开。
 *       0.7.1 起 = 5（v5 新触发器 rightClickWall/playerLeaveRange/playerQuit + 有界循环 repeat；
 *       v4 墙脚本 script.* / v3 时间轴均为干净切换，见 docs/scripting-0.7.1.md §7）。</li>
 * </ul>
 *
 * 两者解耦：升业务版本只动 {@link CLIENT_V}，不动 {@link ENVELOPE_V}。
 * {@link CLIENT_V} 升级时与 {@code plugin/.../Protocol.java SUPPORTED_MIN/MAX} 同步改。
 */
const CLIENT_V = 7; // 0.7.3：协议 v7（后端 Protocol.java SUPPORTED_MIN/MAX 同步改）

/**
 * 信封壳版本（消息容器格式）。所有出帧的 {@code Envelope.v} 用它；与业务协议版本
 * {@link CLIENT_V} 解耦，业务升级时此值不动。
 */
const ENVELOPE_V = 2;

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
    /**
     * P2-69：open 后等待 ready 的一次性看门狗。服务端 accept + auth 成功但 ready 构建/发送
     * 挂起（DAO / 序列化故障 / 半开 TCP）时，客户端会永久卡在 'authenticating' 且无重连
     * （重连只从 onClose 触发）。到时仍 !authenticated 则主动 close（非 1000 码）让
     * onClose→scheduleReconnect 接管。handleReady 成功与任何失败 close 路径都清除它。
     */
    private readyTimer: number | null = null;
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
            net.pushLog('meta', 'ws open');
            this.sendAuth(token);
            // P2-69：启动 ready 看门狗。注意 reconnectAttempt 不在 open 清零——若 ready 永不
            // 到达而靠看门狗断开重连，过早清零会丢失退避进度；改在 handleReady 成功后清零。
            this.startReadyWatchdog();
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
        this.clearReadyWatchdog();
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
        // 信封壳 v 恒为 ENVELOPE_V（=2，与业务协议版本 CLIENT_V 解耦，见常量 javadoc）；
        // 业务版本（client_v / accepted_v）走 auth payload，0.7.1 起 = 5。
        const env: Envelope = { v: ENVELOPE_V, op, id, ts: Date.now(), payload };
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
     *
     * <p>0.4.3：可选 {@code scope} 参数 — {@code 'wall'}（默认，per-wall user 变量）/
     * {@code 'global'}（{@code userglobal/*}，跨 wall 全服共享）。后者由调用方走
     * NewVariableDialog 的 scope toggle 决定。返 promise resolve 后可读 ack 拿
     * {@code fullName}（前端 NewVariableDialog 已沿用名字字段拼 alias，需要 fullName
     * 时调 {@link sendVariableCreateGlobal} 拿到带 ack）。</p>
     */
    sendVariableCreate(name: string, type: VarType, defaultValue?: string | null,
                       scope: 'wall' | 'global' = 'wall'): Promise<void> {
        return this.sendWithAck('variable.create',
            { name, type, defaultValue, scope }).then(() => undefined);
    }

    /**
     * 0.4.3：创建全局用户变量（{@code userglobal/<name>}）并返 ack payload 含
     * {@code fullName}（{@code 'userglobal/<name>'}）。调用方需要立即给变量起别名时用。
     */
    sendVariableCreateGlobalWithAck(
        name: string, type: VarType, defaultValue?: string | null,
    ): Promise<{ fullName?: string } | undefined> {
        return this.sendWithAck('variable.create',
            { name, type, defaultValue, scope: 'global' }) as Promise<{ fullName?: string } | undefined>;
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

    // ---------- 铁路网络（0.4.4，协议见 docs/dynamic-data.md §18.7）----------

    /** 列所有线路（含 owner / color 等元数据）。 */
    sendRailLineList(): Promise<{ lines: import('@/types/rail').RailLine[] }> {
        return this.sendWithAck('rail.line.list', {}, 8000)
                .then((p) => p as { lines: import('@/types/rail').RailLine[] });
    }

    /**
     * 0.4.5 P1：聚合查询某线路的 stations + runs + 各 run 的 timetable。
     * 前端 RailNetworkModal.selectLine 用此避免 N+1 请求。
     */
    sendRailLineDetail(lineId: string): Promise<{
        line: import('@/types/rail').RailLine;
        stations: import('@/types/rail').RailStation[];
        runs: import('@/types/rail').RailRun[];
        timetableByRun: Record<string, Array<{
            stationId: string;
            arrival?: string;
            departure?: string;
            stopsHere: boolean;
        }>>;
    }> {
        return this.sendWithAck('rail.line.detail', { lineId }, 8000)
                .then((p) => p as {
                    line: import('@/types/rail').RailLine;
                    stations: import('@/types/rail').RailStation[];
                    runs: import('@/types/rail').RailRun[];
                    timetableByRun: Record<string, Array<{
                        stationId: string; arrival?: string;
                        departure?: string; stopsHere: boolean;
                    }>>;
                });
    }

    sendRailLineCreate(name: string, code: string | null, color: string | null):
            Promise<{ lineId: string; line: import('@/types/rail').RailLine }> {
        return this.sendWithAck('rail.line.create', { name, code, color })
                .then((p) => p as { lineId: string; line: import('@/types/rail').RailLine });
    }

    sendRailLineUpdate(lineId: string, patch: Partial<{ name: string; code: string | null;
                                                        color: string | null }>):
            Promise<{ line: import('@/types/rail').RailLine }> {
        return this.sendWithAck('rail.line.update', { lineId, ...patch })
                .then((p) => p as { line: import('@/types/rail').RailLine });
    }

    sendRailLineDelete(lineId: string): Promise<{ deleted: number }> {
        return this.sendWithAck('rail.line.delete', { lineId })
                .then((p) => p as { deleted: number });
    }

    sendRailStationAdd(lineId: string, name: string, code: string | null,
                       sortOrder: number | null, isTerminus: boolean):
            Promise<{ stationId: string; station: import('@/types/rail').RailStation }> {
        return this.sendWithAck('rail.station.add',
                { lineId, name, code, sortOrder, isTerminus })
                .then((p) => p as { stationId: string;
                                    station: import('@/types/rail').RailStation });
    }

    sendRailStationUpdate(stationId: string, patch: Partial<{
        name: string; code: string | null; sortOrder: number; isTerminus: boolean;
    }>): Promise<{ station: import('@/types/rail').RailStation }> {
        return this.sendWithAck('rail.station.update', { stationId, ...patch })
                .then((p) => p as { station: import('@/types/rail').RailStation });
    }

    sendRailStationDelete(stationId: string): Promise<{ deleted: number }> {
        return this.sendWithAck('rail.station.delete', { stationId })
                .then((p) => p as { deleted: number });
    }

    sendRailRunCreate(payload: {
        lineId: string;
        runNumber: string;
        direction: 'up' | 'down';
        serviceType: string;
        cars?: number | null;
        startStationId?: string | null;
        endStationId?: string | null;
        notes?: string | null;
        generateOptions?: import('@/types/rail').AutoTimetableOptions;
    }): Promise<{ runId: string; run: import('@/types/rail').RailRun }> {
        return this.sendWithAck('rail.run.create', payload)
                .then((p) => p as { runId: string; run: import('@/types/rail').RailRun });
    }

    sendRailRunUpdate(runId: string, patch: Partial<{
        runNumber: string; direction: 'up' | 'down'; serviceType: string;
        cars: number | null; startStationId: string | null; endStationId: string | null;
        notes: string | null;
    }>): Promise<{ run: import('@/types/rail').RailRun }> {
        return this.sendWithAck('rail.run.update', { runId, ...patch })
                .then((p) => p as { run: import('@/types/rail').RailRun });
    }

    sendRailRunDelete(runId: string): Promise<{ deleted: number }> {
        return this.sendWithAck('rail.run.delete', { runId })
                .then((p) => p as { deleted: number });
    }

    sendRailTimetableSet(runId: string,
                         entries: Array<{ stationId: string; arrival?: string | null;
                                          departure?: string | null;
                                          stopsHere: boolean }>):
            Promise<{ rows: number }> {
        return this.sendWithAck('rail.run.timetable.set', { runId, entries })
                .then((p) => p as { rows: number });
    }

    /** 绑定当前 session 的 wall 到铁路网络（R7：后端只认 session wall，不接受 wallId）。 */
    sendRailWallBind(payload: {
        lineId?: string | null;
        stationId?: string | null;
        direction?: 'up' | 'down' | 'both';
    }): Promise<{ binding: import('@/types/rail').WallRailBinding }> {
        return this.sendWithAck('rail.wall.bind', payload)
                .then((p) => p as { binding: import('@/types/rail').WallRailBinding });
    }

    // ---------- 时间轴 / 关键帧（0.6，协议契约见 docs/protocol.md §5.12 / §5.13）----------
    //
    // 两族 op 都走 ack 通道。timeline.create / keyframe.add 的 ack 只含 {version}——新生成的
    // id（tl-* / kf-*）由后端经 state.patch 的 add /timelines/<i>（整 Timeline）/
    // add /timelines/<i>/tracks/<eid>/<k>（整 Keyframe）回下发（同 element.add 范式，
    // protocol.md §5.12 "新 timeline（含 id）经 state.patch 下发"）。本地 mirror 由
    // project.applyPatch 的 applyTimelineMutation 落地（P1 已实装），故这些 send 方法不预测性
    // mutate store，保持 server-as-truth。
    //
    // play / pause / seek 不落 DB / 不进 history（protocol.md §5.12）；ack 形态为空 {}，
    // 这三个方法 resolve 为 void。

    /**
     * {@code timeline.create}：新建时间轴。{@code name} 留空时后端补默认名；
     * {@code fps} 受 config {@code timeline.max-fps} 钳。{@code trigger} MVP 固定
     * {@code { type: 'manual', params: {} }}（触发器 UI 留 P5）。ack 只含 {@code version}，
     * 新 timeline（含 id）经 state.patch 回下发。
     */
    sendTimelineCreate(
        name: string,
        durationMs: number,
        fps: number,
        loopMode: import('@/types/protocol').LoopMode,
        trigger: import('@/types/protocol').TriggerConfig,
    ): Promise<void> {
        return this.sendWithAck('timeline.create',
            { name, durationMs, fps, loopMode, trigger }).then(() => undefined);
    }

    /**
     * {@code timeline.update}：部分更新时间轴字段。{@code patch} 仅含要改的字段
     * （name / durationMs / fps / loopMode / trigger）；显式传 {@code null} 后端拒
     * {@code INVALID_PAYLOAD}（不支持 "null = 清字段"，见 §5.12）。
     */
    sendTimelineUpdate(
        timelineId: string,
        patch: Partial<{
            name: string;
            durationMs: number;
            fps: number;
            loopMode: import('@/types/protocol').LoopMode;
            trigger: import('@/types/protocol').TriggerConfig;
        }>,
    ): Promise<void> {
        return this.sendWithAck('timeline.update', { timelineId, patch }).then(() => undefined);
    }

    /** {@code timeline.delete}：删除时间轴及其全部 tracks。 */
    sendTimelineDelete(timelineId: string): Promise<void> {
        return this.sendWithAck('timeline.delete', { timelineId }).then(() => undefined);
    }

    /**
     * {@code timeline.play}：在部署 wall 上启动后端 AnimationTicker（§5.12）。
     * 不落 DB / 不进 history。{@code timelineId} 缺省时由后端用 activeTimelineId（payload 不带该键）。
     */
    sendTimelinePlay(timelineId?: string): Promise<void> {
        const payload = timelineId != null ? { timelineId } : {};
        return this.sendWithAck('timeline.play', payload).then(() => undefined);
    }

    /** {@code timeline.pause}：停止后端 Ticker。payload 无参 {@code {}}（§5.12 op 表）。 */
    sendTimelinePause(): Promise<void> {
        return this.sendWithAck('timeline.pause', {}).then(() => undefined);
    }

    /**
     * {@code timeline.seek}：定位后端 Ticker 到 {@code atMs}（§5.12）。
     * {@code timelineId} 缺省时由后端用 activeTimelineId。
     */
    sendTimelineSeek(atMs: number, timelineId?: string): Promise<void> {
        const payload: { atMs: number; timelineId?: string } = { atMs };
        if (timelineId != null) payload.timelineId = timelineId;
        return this.sendWithAck('timeline.seek', payload).then(() => undefined);
    }

    /**
     * {@code keyframe.add}：在指定元素属性轨上加关键帧（§5.13）。ack 只含 {@code version}，
     * 新 keyframe（含 id）经 state.patch 回下发。{@code value} 形态因 property 而异
     * （number / string / Fill）；{@code easing} MVP 固定 {@code { type: 'linear' }}。
     */
    sendKeyframeAdd(
        timelineId: string,
        elementId: string,
        property: string,
        timeMs: number,
        value: import('@/types/protocol').KfValue,
        easing: import('@/types/protocol').Easing,
        coalesceKey?: string,
    ): Promise<void> {
        const payload: Record<string, unknown> = { timelineId, elementId, property, timeMs, value, easing };
        if (coalesceKey != null) payload.coalesceKey = coalesceKey;
        return this.sendWithAck('keyframe.add', payload).then(() => undefined);
    }

    /**
     * {@code keyframe.update}：部分更新关键帧（timeMs / value / easing，§5.13）。
     * {@code coalesceKey} 可选——整体帧批量改值（拉就设 update 分支）共享一步撤销（P4.5b）。
     */
    sendKeyframeUpdate(
        timelineId: string,
        keyframeId: string,
        patch: Partial<{
            timeMs: number;
            value: import('@/types/protocol').KfValue;
            easing: import('@/types/protocol').Easing;
        }>,
        coalesceKey?: string,
    ): Promise<void> {
        const payload: Record<string, unknown> = { timelineId, keyframeId, patch };
        if (coalesceKey != null) payload.coalesceKey = coalesceKey;
        return this.sendWithAck('keyframe.update', payload).then(() => undefined);
    }

    /** {@code keyframe.delete}：删除关键帧（§5.13）。 */
    sendKeyframeDelete(timelineId: string, keyframeId: string): Promise<void> {
        return this.sendWithAck('keyframe.delete',
            { timelineId, keyframeId }).then(() => undefined);
    }

    /**
     * {@code keyframe.move}：仅挪时刻的高频快捷（§5.13）；拖动期前端本地 mutate，dragend 发一条。
     * {@code coalesceKey} 可选——整体块拖动的 N 帧共享一步撤销（P4.5b）。
     */
    sendKeyframeMove(timelineId: string, keyframeId: string, timeMs: number,
                     coalesceKey?: string): Promise<void> {
        const payload: Record<string, unknown> = { timelineId, keyframeId, timeMs };
        if (coalesceKey != null) payload.coalesceKey = coalesceKey;
        return this.sendWithAck('keyframe.move', payload).then(() => undefined);
    }

    // ---------- 墙脚本（0.7.0 P1，协议契约见 docs/scripting.md §2）----------
    //
    // 5 op 都走 ack 通道。create / update / enable 的新 / 改后 rule（含服务端权威的 id /
    // wallId）经 state.patch 的 add /scripts/<encoded ruleId> 回下发（replace 语义统一发
    // add，见 ScriptOpDispatcher javadoc）；delete 推 remove。本地 mirror 由
    // applyScriptPatches 落 ScriptStore，故这些 send 方法不预测性 mutate store，
    // 保持 server-as-truth。

    /**
     * {@code script.create}：新建脚本规则。{@code id / wallId} 服务端权威，payload 的
     * rule 不带这两个字段；新 rule（含 id）经 state.patch 回下发。
     */
    sendScriptCreate(rule: Omit<import('@/types/protocol').ScriptRule, 'id' | 'wallId'>): Promise<void> {
        return this.sendWithAck('script.create', { rule }).then(() => undefined);
    }

    /**
     * {@code script.update}：全量替换规则（非逐字段 patch；后端先 find 确认属本 wall，
     * 防跨墙改）。改后 rule 经 state.patch 回下发。
     */
    sendScriptUpdate(
        ruleId: string,
        rule: Omit<import('@/types/protocol').ScriptRule, 'id' | 'wallId'>,
    ): Promise<void> {
        return this.sendWithAck('script.update', { ruleId, rule }).then(() => undefined);
    }

    /** {@code script.delete}：删除规则。不存在也推 remove patch（幂等，照 alias clear 先例）。 */
    sendScriptDelete(ruleId: string): Promise<void> {
        return this.sendWithAck('script.delete', { ruleId }).then(() => undefined);
    }

    /** {@code script.enable}：翻转规则开关。改后 rule 经 state.patch 回下发。 */
    sendScriptEnable(ruleId: string, enabled: boolean): Promise<void> {
        return this.sendWithAck('script.enable', { ruleId, enabled }).then(() => undefined);
    }

    /**
     * {@code script.test}：试运行规则（0.7.0-P3 A2 起异步——K11）。ack 立即 resolve
     * {@code {accepted: true, ruleId}}（受理回执，不等执行：合法规则可串 wait 至分钟级）；
     * 执行轨迹在 run 结束后由服务端主动推 S→C op {@code script.trace}，本类路由进
     * scripts store 的 {@code lastTrace}（P5 积木高亮消费）。
     */
    sendScriptTest(ruleId: string): Promise<unknown> {
        return this.sendWithAck('script.test', { ruleId });
    }

    // ---------- 内部 ----------

    private sendAuth(token: string): void {
        // M16 P6.2：发新字段 client_v；旧字段 clientProtocolVersion 同步发以兼容回滚
        // 到旧服务端 jar 的情形（旧后端不识别 client_v，识别 clientProtocolVersion）。
        // 新后端（M16+）优先读 client_v；范围检查通过 → ready 回 accepted_v。
        //
        // 0.9.7：携带编辑器 UI 语言（用于后端按编辑器语言渲染脚本校验报错等外发文案）。
        // ui.locale 是 2 字母（'zh' / 'en'），这里映射成 game locale id 形态（'zh_cn' / 'en_us'），
        // 与命令路径（player.getLocale() 返回 'zh_cn'）对齐 → 后端 Messages.resolveLocaleId 统一处理。
        // auth payload 后端按 Object → Map 松散解析（非严格 record 字段），旧后端多这个键也静默忽略。
        this.send('auth', {
            token,
            client_v: CLIENT_V,
            clientProtocolVersion: CLIENT_V,
            locale: uiLocaleToGameId(useUiStore().locale),
        });
    }

    private startHeartbeat(): void {
        this.stopHeartbeat();
        this.heartbeatTimer = window.setInterval(() => {
            const net = useNetworkStore();
            if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !net.authenticated) return;
            const env: Envelope = { v: ENVELOPE_V, op: 'ping', id: `c-hb-${this.seq++}`, ts: Date.now() };
            this.ws.send(JSON.stringify(env));
        }, HEARTBEAT_INTERVAL_MS);
    }

    private stopHeartbeat(): void {
        if (this.heartbeatTimer !== null) {
            window.clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    }

    /**
     * P2-69：启动一次性 ready 看门狗。到时仍未 authenticated（ready 未到达）则强制断开，
     * 让 onClose→scheduleReconnect 接管退避重连，避免永久卡在 'authenticating'。
     */
    private startReadyWatchdog(): void {
        this.clearReadyWatchdog();
        this.readyTimer = window.setTimeout(() => {
            this.readyTimer = null;
            const net = useNetworkStore();
            if (this.stopped || net.authenticated) return;
            net.pushLog('err', `ready not received within ${READY_TIMEOUT_MS}ms; forcing reconnect`);
            // 非 1000 码 → onClose 判定非 terminal → scheduleReconnect 接管。
            try { this.ws?.close(4000, 'ready_timeout'); } catch { /* ignore */ }
        }, READY_TIMEOUT_MS);
    }

    private clearReadyWatchdog(): void {
        if (this.readyTimer !== null) {
            window.clearTimeout(this.readyTimer);
            this.readyTimer = null;
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

        // P2-68：handler dispatch 外层包 try/catch，隔离单帧异常。TS 类型断言运行时擦除，
        // 服务端结构漂移帧（forward-compat / 后端 bug）可让 handler 中途抛 TypeError，
        // 不捕获会逃逸出浏览器 'message' 回调（无 window.onerror 降级），污染后续帧。
        // 捕获后记录并安全 return——后续帧与 pending ack 不受影响。
        try {
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
                case 'script.trace':
                    this.handleScriptTrace(env.payload as ScriptTracePayload);
                    break;
                default:
                    net.pushLog('meta', `unhandled op: ${env.op}`);
            }
        } catch (e) {
            net.pushLog('err', `frame handler threw for op "${env.op}": ${e instanceof Error ? e.message : String(e)}`);
        }
    }

    private handleReady(payload: ReadyPayload): void {
        const net = useNetworkStore();
        const project = useProjectStore();
        const templates = useTemplatesStore();
        const ui = useUiStore();
        // P2-69：ready 到达（无论成功或下方的协议错误 close 路径）都清掉看门狗。
        this.clearReadyWatchdog();
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
        // P2-68：ready payload 运行时空值守卫。TS 类型断言不防 server 结构漂移，
        // payload.projectState.canvas 缺失会让下方 widthMaps 读取抛 TypeError（且已设
        // authenticated=true / 未启心跳的半态卡死）。缺失视为协议错误：记日志 + 主动 close
        // （非 1000 码）让 onClose→scheduleReconnect 接管，而非半态继续。
        if (!payload || !payload.projectState || !payload.projectState.canvas) {
            net.lastError = localizeErrorCode('MALFORMED_READY');
            net.pushLog('err', 'ready payload missing projectState/canvas; closing connection');
            try { this.ws?.close(4000, 'malformed_ready'); } catch { /* ignore */ }
            return;
        }
        // M16 P4.2：切到新 wall 时清掉旧 wall 残留状态（selectedIds / lockedAt / state...）。
        // 同 wall 重连（wallId 不变）保留 UI 上下文，避免重连闪烁。
        const incomingWallId = payload.wallId ?? null;
        if (project.wallId !== null && project.wallId !== incomingWallId) {
            // P3-103：schedule / alias / rail 等 wall-scoped store 的 reset 已收进
            // project.reset() 单一调用点（见 stores/project.ts），此处不再并列手动调用。
            project.reset();
            ui.reset();
        }
        // P2-69：握手真正完成才清零退避计数（open 不再清零，见 connect open 回调注释）。
        this.reconnectAttempt = 0;
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
        // 0.7.0 P1：脚本规则快照（per-wall，服务端顺序）；后续变更走 state.patch /scripts/<encoded>。
        useScriptStore().initScripts(payload.scripts ?? []);
        // 0.4.5 P3：铁路绑定快照（当前 wall 是否绑了线路）；用于 ScheduleManagerModal /
        // RailNetworkModal 一打开就显示绑定状态。null = 未绑定。
        const railBinding = (payload as { railBinding?: import('@/types/rail').WallRailBinding | null })
            .railBinding;
        useRailStore().setBinding(railBinding ?? null);
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
        // 0.7.0 P1：scripts 同款分拣到 ScriptStore。
        const variableOps: PatchOp[] = [];
        const aliasOps: PatchOp[] = [];
        const scriptOps: PatchOp[] = [];
        const projectOps: PatchOp[] = [];
        for (const op of payload.ops) {
            if (op.path.startsWith('/variables/')) variableOps.push(op);
            else if (op.path.startsWith('/aliases/')) aliasOps.push(op);
            else if (op.path.startsWith('/scripts/')) scriptOps.push(op);
            else projectOps.push(op);
        }
        if (variableOps.length > 0) {
            applyVariablePatches(variableOps);
        }
        if (aliasOps.length > 0) {
            applyAliasPatches(aliasOps);
        }
        if (scriptOps.length > 0) {
            applyScriptPatches(scriptOps);
        }
        // 即便 projectOps 为空也要更新 version 号（version 是 wall-scoped 单调递增）
        useProjectStore().applyPatch(payload.version, projectOps);
    }

    /**
     * 0.7.0-P3 A2（K11）：S→C op {@code script.trace}——{@code script.test} 的异步轨迹
     * （ack 只回受理；run 真正结束后才推这条）。逻辑在独立导出 {@link applyScriptTrace}
     * （照 applyScriptPatches 可独测范式）。
     */
    private handleScriptTrace(payload: ScriptTracePayload): void {
        applyScriptTrace(payload);
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
        this.clearReadyWatchdog();
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

        // M5-D7 + A1/A2（ultrareview 0.7.3 批 A）：按 close code 判断是否重连。
        // terminal = 重连无意义的终止态，停止退避；否则 scheduleReconnect。
        // 集合定义见导出函数 isTerminalCloseCode（可独测）。
        const terminal = isTerminalCloseCode(ev.code);
        if (this.stopped || terminal) {
            if (ev.code === 4001) {
                // M25 任务 2A：友好提示走 i18n（AUTH_FAILED 中英文都覆盖到 token 提示）
                net.lastError = localizeErrorCode('AUTH_FAILED');
                this.clearStoredToken();
            } else if (ev.code === 4002) {
                // A4：handleReady 已设精确 lastError（「协议版本不兼容 server=X client=Y」）；
                // 若 stopped 为 true 且 lastError 已有精确内容，不覆写（防止通用提示冲掉精确提示）。
                // 若 stopped 为 false（理论上 handleReady 分支不会走这里，防御性保留兜底）。
                if (!net.lastError) {
                    net.lastError = localizeErrorCode('PROTOCOL_VERSION_UNSUPPORTED');
                }
            } else if (ev.code === 4429) {
                net.lastError = localizeErrorCode('RATE_LIMITED');
            } else if (ev.code === 1008) {
                net.lastError = localizeErrorCode('RATE_LIMITED');
            } else if (ev.code === 1000) {
                // 主动关闭，不刷红
            } else {
                // stopped=true 但 code 不在以上特殊 code 中（如 close() 默认 1000 以外的情形）；
                // 若 handleReady 等已设精确 lastError，不覆写。
                if (!net.lastError) {
                    net.lastError = `${localizeErrorCode('CONNECTION_CLOSED')}（code ${ev.code}）`;
                }
            }
            return;
        }

        this.scheduleReconnect();
    }

    private scheduleReconnect(): void {
        const net = useNetworkStore();
        if (this.reconnectAttempt >= RECONNECT_BACKOFF_S.length) {
            net.lastError = localizeErrorCode('RECONNECT_EXHAUSTED');
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
                net.lastError = localizeErrorCode('TOKEN_MISSING');
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
//   - add     /variables/<encoded>              value = 完整 Variable JSON
//   - replace /variables/<encoded>              value = 完整 Variable JSON（整节点 UPDATED）
//   - replace /variables/<encoded>/currentValue value = 新值（string | null）
//   - replace /variables/<encoded>/<field>      value = 单字段新值（type / defaultValue 等）
//   - remove  /variables/<encoded>
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
        } else if (op.op === 'replace' && sub === '') {
            // P1-3：整 Variable JSON 节点 replace（UPDATED 事件，type / defaultValue 改动）。
            // 与 add 分支同款落表逻辑——后端 SessionManager / EditSession 对 variable.update
            // 发的是 replace 整节点（而非逐字段 patch），漏接会让 type/defaultValue 不同步。
            if (op.value && typeof op.value === 'object') {
                store.set(fullName, op.value as Variable);
            } else {
                net.pushLog('err', `variable patch replace: missing value for ${fullName}`);
            }
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

// ---------- 脚本 state.patch 路由（0.7.0 P1）----------
//
// 后端 ScriptOpDispatcher 发 path 形如 {@code /scripts/<encoded ruleId>}（RFC 6901 段编码，
// 同变量 / 别名通道）。支持：add / replace 携完整 ScriptRule 对象（后端 create / update /
// enable 统一发 add，replace 收下兼容）；remove 不携 value。其他形态不支持，
// 收到时静默忽略并 log。导出供单测独立验证（applyAliasPatches 无独立测试的教训）。

export function applyScriptPatches(ops: PatchOp[]): void {
    const store = useScriptStore();
    const net = useNetworkStore();
    for (const op of ops) {
        const rest = op.path.substring('/scripts/'.length);
        if (rest.length === 0) {
            net.pushLog('err', `script patch: empty path ${op.path}`);
            continue;
        }
        // script 路径无子路径（仅 ruleId 编码段），直接整段 decode
        const ruleId = decodeJsonPointerToken(rest);
        // value 必须是携 string id 的普通对象；畸形对象 / 数组 / 缺 id 落 else 分支 log err
        const v = op.value as Partial<import('@/types/protocol').ScriptRule> | null;
        if ((op.op === 'add' || op.op === 'replace')
                && v && typeof v === 'object' && !Array.isArray(v) && typeof v.id === 'string') {
            // id 失配只可能是后端 bug：log 后仍按 value.id 收（可观测优先，不静默丢数据）
            if (v.id !== ruleId) net.pushLog('err', `script patch: id mismatch ${op.path} vs ${v.id}`);
            store.upsert(v as import('@/types/protocol').ScriptRule);
        } else if (op.op === 'remove') {
            store.removeRule(ruleId);
        } else {
            net.pushLog('err', `script patch: unsupported ${op.op} ${op.path}`);
        }
    }
}

// ---------- script.trace 路由（0.7.0-P3 A2，K11）----------
//
// S→C op {@code script.trace {ruleId, steps}}：script.test 的异步轨迹（ack 只回受理）。
// 落 scripts store 的 lastTrace（覆盖式）+ meta log 一条；畸形 payload（后端结构漂移 /
// forward-compat 帧）log err 不落表。导出供单测独立验证（同 applyScriptPatches 范式）。

export function applyScriptTrace(payload: ScriptTracePayload | null | undefined): void {
    const net = useNetworkStore();
    if (!payload || typeof payload.ruleId !== 'string' || !Array.isArray(payload.steps)) {
        net.pushLog('err', 'script.trace: malformed payload');
        return;
    }
    useScriptStore().setLastTrace(payload);
    net.pushLog('meta', `script.trace: rule=${payload.ruleId} steps=${payload.steps.length}`);
}

// ---------- close code 终止态判定（A1/A2 ultrareview 0.7.3 批 A）----------
//
// terminal close code = 重连无意义的终止态；非 terminal 走 scheduleReconnect 退避重连。
// 导出为纯函数，可独立单测（不依赖 Pinia / WebSocket，node 环境即可跑）。
//
// ■ 1000  正常关闭（client.close() 主动）
// ■ 1008  反复超限断连（Protocol.CLOSE_RATE_LIMIT_VIOLATION）→ 等待后手动刷新，不自动重连
// ■ 4001  auth 失败（token 无效 / 超时）→ 换 token 才有用，重连必再失败
// ■ 4002  协议版本不兼容（Protocol.CLOSE_PROTOCOL_VERSION_UNSUPPORTED）→ 升级客户端才有用
// ■ 4429  token 暴力枚举限流（Protocol.CLOSE_TOKEN_RATE_LIMITED）→ 等待窗口复位
//         原 4008 是后端从未发出的死码，已替换为 4429（见 Protocol.java）
export function isTerminalCloseCode(code: number): boolean {
    return code === 1000
        || code === 1008
        || code === 4001
        || code === 4002
        || code === 4429;
}
