// @vitest-environment happy-dom
/**
 * WsClient 会话行为守卫（用假 socket 驱动真客户端，不打真网络）。
 *
 * <p>覆盖三件事：</p>
 * <ul>
 *   <li><b>回执副作用不串台</b>：给变量起别名的回执里也有一个顶层 alias 字段，不能被当成
 *       整块画布的名字写进去——那会顺着顶栏的输入框预填一路写进数据库。</li>
 *   <li><b>半开连接要能被发现</b>：只发心跳不看回音时，WiFi 切换 / 休眠唤醒之后画的东西
 *       会全部无声丢失。连续两个心跳周期没有任何回音就要主动断开走重连。</li>
 *   <li><b>变量时间戳统一到本机时钟</b>：TTL 过期判定拿本机 Date.now() 去减，服务端时间
 *       原样落表的话，两台机器时钟差多少就误判多少。</li>
 * </ul>
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import { WsClient, serverTsToLocal, variableWithLocalClock } from '../wsClient';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';
import type { Variable } from '@/types/variable';

// ---------- 假 socket ----------

type Listener = (ev: unknown) => void;

class FakeSocket {
    static last: FakeSocket | null = null;
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    static readonly CLOSING = 2;
    static readonly CLOSED = 3;

    readyState = FakeSocket.OPEN;
    sent: string[] = [];
    closedWith: { code: number; reason: string } | null = null;
    private listeners: Record<string, Listener[]> = {};

    constructor(public readonly url: string) {
        FakeSocket.last = this;
    }

    addEventListener(type: string, fn: Listener): void {
        (this.listeners[type] ??= []).push(fn);
    }

    send(text: string): void {
        this.sent.push(text);
    }

    close(code = 1000, reason = ''): void {
        if (this.readyState === FakeSocket.CLOSED) return;
        this.readyState = FakeSocket.CLOSED;
        this.closedWith = { code, reason };
        this.emit('close', { code, reason });
    }

    emit(type: string, ev: unknown): void {
        for (const fn of this.listeners[type] ?? []) fn(ev);
    }

    /** 模拟服务端发来一帧。 */
    deliver(frame: Record<string, unknown>): void {
        this.emit('message', { data: JSON.stringify(frame) });
    }
}

/** 最小可用的 ready 帧 payload。 */
function readyPayload(extra: Record<string, unknown> = {}) {
    return {
        sessionId: 's-1',
        serverVersion: 'test',
        wallId: 'w-abc',
        alias: 'my-wall',
        projectState: {
            version: 1,
            canvas: { widthMaps: 1, heightMaps: 1 },
            layers: [{ id: 'l-1', name: 'L', visible: true, locked: false, opacity: 1, elements: [] }],
            activeLayerId: 'l-1',
        },
        ...extra,
    };
}

let client: WsClient;

beforeEach(() => {
    setActivePinia(createPinia());
    FakeSocket.last = null;
    (globalThis as unknown as { WebSocket: unknown }).WebSocket = FakeSocket;
    client = new WsClient('ws://test/ws');
});

afterEach(() => {
    vi.useRealTimers();
});

/** 建连 + 握手到 ready，返回假 socket。 */
function handshake(readyExtra: Record<string, unknown> = {}, serverTs?: number): FakeSocket {
    client.connect('tok-1');
    const sock = FakeSocket.last!;
    sock.emit('open', {});
    sock.deliver({
        v: 2, op: 'ready', id: null, ts: serverTs ?? Date.now(), payload: readyPayload(readyExtra),
    });
    return sock;
}

/** 取最近一次发出的信封 id（sendWithAck 要用它拼回执）。 */
function lastSentId(sock: FakeSocket): string {
    const env = JSON.parse(sock.sent[sock.sent.length - 1]) as { id: string };
    return env.id;
}

describe('ack 副作用只认画布元信息那三个 op', () => {
    it('设变量别名的回执不能改画布名字', async () => {
        const sock = handshake();
        const project = useProjectStore();
        expect(project.alias).toBe('my-wall');

        const p = client.sendVariableAliasSet('user:w-abc/score', '红队得分');
        const id = lastSentId(sock);
        // 后端 VariableAliasDispatcher 的回执带顶层 alias 字段
        sock.deliver({
            v: 2, op: 'ack', id, ts: Date.now(),
            payload: { fullName: 'user:w-abc/score', alias: '红队得分' },
        });
        await p;

        expect(project.alias).toBe('my-wall');
    });

    it('wall.alias 的回执照常写进画布名字', async () => {
        const sock = handshake();
        const project = useProjectStore();

        const p = client.sendWithAck('wall.alias', { alias: 'shop' });
        const id = lastSentId(sock);
        sock.deliver({ v: 2, op: 'ack', id, ts: Date.now(), payload: { alias: 'shop' } });
        await p;

        expect(project.alias).toBe('shop');
    });

    it('wall.lock / wall.unlock 的回执照常写进锁定时间', async () => {
        const sock = handshake();
        const project = useProjectStore();

        const lock = client.sendWithAck('wall.lock');
        sock.deliver({
            v: 2, op: 'ack', id: lastSentId(sock), ts: Date.now(), payload: { lockedAt: 12345 },
        });
        await lock;
        expect(project.lockedAt).toBe(12345);

        const unlock = client.sendWithAck('wall.unlock');
        sock.deliver({
            v: 2, op: 'ack', id: lastSentId(sock), ts: Date.now(), payload: { locked: false },
        });
        await unlock;
        expect(project.lockedAt).toBeNull();
    });

    it('别的 op 回执里带 lockedAt 也不会误锁画布', async () => {
        const sock = handshake();
        const project = useProjectStore();

        const p = client.sendVariableSet('user:w-abc/n', '1');
        sock.deliver({
            v: 2, op: 'ack', id: lastSentId(sock), ts: Date.now(), payload: { lockedAt: 999 },
        });
        await p;

        expect(project.lockedAt).toBeNull();
    });
});

describe('心跳看门狗：半开连接要被发现', () => {
    it('两个心跳周期内没有任何回音 → 主动断开（非终止码，交给退避重连）', () => {
        vi.useFakeTimers();
        const sock = handshake();
        expect(sock.closedWith).toBeNull();

        // 第 1、2 个周期：只发 ping，没到判定线
        vi.advanceTimersByTime(20_000);
        vi.advanceTimersByTime(20_000);
        expect(sock.closedWith).toBeNull();
        expect(sock.sent.filter((s) => s.includes('"ping"')).length).toBe(2);

        // 第 3 个周期：静默已超两个周期 → 断开
        vi.advanceTimersByTime(20_000);
        expect(sock.closedWith).not.toBeNull();
        expect(sock.closedWith!.code).toBe(4000);
    });

    it('服务端有回 pong 就不断开', () => {
        vi.useFakeTimers();
        const sock = handshake();

        for (let i = 0; i < 5; i++) {
            vi.advanceTimersByTime(20_000);
            sock.deliver({ v: 2, op: 'pong', id: null, ts: Date.now(), payload: {} });
        }
        expect(sock.closedWith).toBeNull();
    });
});

describe('变量时间戳换算到本机时钟', () => {
    function mkVar(updatedAt: number): Variable {
        return {
            namespace: 'plugin', key: 'k', type: 'STRING',
            defaultValue: null, currentValue: 'v', updatedAt, ttl: 10_000, source: 'plugin',
        };
    }

    it('serverTsToLocal：按时钟差平移；0 / 无偏移原样返回', () => {
        expect(serverTsToLocal(1_000_000, 60_000)).toBe(940_000);
        expect(serverTsToLocal(1_000_000, -60_000)).toBe(1_060_000);
        expect(serverTsToLocal(1_000_000, 0)).toBe(1_000_000);
        expect(serverTsToLocal(0, 60_000)).toBe(0);
    });

    it('variableWithLocalClock：不需要换算时返回原引用', () => {
        const v = mkVar(1_000_000);
        expect(variableWithLocalClock(v, 0)).toBe(v);
        expect(variableWithLocalClock(v, 5_000).updatedAt).toBe(995_000);
    });

    it('ready 快照里的 updatedAt 按服务端时钟差平移后落表', () => {
        const now = Date.now();
        // 服务器时钟比本机快 1 分钟
        const serverNow = now + 60_000;
        handshake({ variables: [mkVar(serverNow)] }, serverNow);

        const store = useVariableStore();
        const v = store.get('plugin/k');
        expect(v).toBeTruthy();
        // 换算回本机时钟 → 约等于本机此刻，不该看起来"来自一分钟后"
        expect(Math.abs(v!.updatedAt - now)).toBeLessThan(2_000);
    });

    it('整节点 patch 的 updatedAt 同样换算', () => {
        const now = Date.now();
        const serverNow = now + 60_000;
        const sock = handshake({}, serverNow);

        sock.deliver({
            v: 2, op: 'state.patch', id: null, ts: serverNow,
            payload: {
                version: 2,
                ops: [{ op: 'add', path: '/variables/plugin~1k', value: mkVar(serverNow) }],
            },
        });

        const v = useVariableStore().get('plugin/k');
        expect(v).toBeTruthy();
        expect(Math.abs(v!.updatedAt - now)).toBeLessThan(2_000);
    });
});
