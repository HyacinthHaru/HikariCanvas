// @vitest-environment happy-dom
/**
 * wsClient 辅助纯函数与状态路由单测：
 * - isTerminalCloseCode 终止重连判定与 VERSION_MISMATCH 文案语义
 * - pickInitialToken / stripTokenFromUrl 初始与重连令牌选择
 * - resolveWsUrl 同源反代与本地开发端口解析
 * - applyScriptPatches / applyScriptTrace 脚本状态与轨迹路由
 */
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import {
    isTerminalCloseCode,
    pickInitialToken,
    stripTokenFromUrl,
    resolveWsUrl,
    applyScriptPatches,
    applyScriptTrace,
} from '../wsClient';
import { messages } from '@/i18n/messages';
import { useScriptStore } from '@/stores/scripts';
import type { PatchOp, ScriptRule, ScriptTracePayload } from '@/types/protocol';

// ---------- 1. isTerminalCloseCode & VERSION_MISMATCH ----------

describe('isTerminalCloseCode', () => {
    it('1000 (normal close) → terminal', () => {
        expect(isTerminalCloseCode(1000)).toBe(true);
    });

    it('4001 (auth failed) → terminal', () => {
        expect(isTerminalCloseCode(4001)).toBe(true);
    });

    it('4002 (protocol version unsupported) → terminal', () => {
        expect(isTerminalCloseCode(4002)).toBe(true);
    });

    it('4429 (token rate limited) → terminal', () => {
        expect(isTerminalCloseCode(4429)).toBe(true);
    });

    it('1008 (repeated rate-limit violations) → terminal', () => {
        expect(isTerminalCloseCode(1008)).toBe(true);
    });

    it('1001 (going away / server reboot) → non-terminal', () => {
        expect(isTerminalCloseCode(1001)).toBe(false);
    });

    it('1006 (abnormal close / server down) → non-terminal', () => {
        expect(isTerminalCloseCode(1006)).toBe(false);
    });

    it('1011 (server internal error) → non-terminal', () => {
        expect(isTerminalCloseCode(1011)).toBe(false);
    });

    it('4008 (未定义码) → non-terminal', () => {
        expect(isTerminalCloseCode(4008)).toBe(false);
    });
});

describe('messages VERSION_MISMATCH', () => {
    it('中文文案表达"协议版本不兼容"（不是"画板被改动"）', () => {
        const zh = messages.zh.errors.VERSION_MISMATCH;
        expect(zh).not.toMatch(/改动/);
        expect(zh).not.toMatch(/同步/);
        expect(zh).toMatch(/版本|升级/);
    });

    it('英文文案表达"协议版本不兼容"（不是"wall was changed"）', () => {
        const en = messages.en.errors.VERSION_MISMATCH;
        expect(en).not.toMatch(/changed/i);
        expect(en).not.toMatch(/syncing/i);
        expect(en).toMatch(/incompatible|version|upgrade/i);
    });
});

// ---------- 2. pickInitialToken & stripTokenFromUrl ----------

const RECONNECT_TOKEN_KEY = 'hikari-canvas:reconnect-token';

describe('pickInitialToken & stripTokenFromUrl', () => {
    beforeEach(() => {
        sessionStorage.clear();
        window.history.replaceState(null, '', '/');
    });

    describe('pickInitialToken', () => {
        it('地址栏与 sessionStorage 都有时，优先用 sessionStorage 的 reconnectToken', () => {
            window.history.replaceState(null, '', '/?token=url-one-shot');
            sessionStorage.setItem(RECONNECT_TOKEN_KEY, 'stored-reconnect');
            expect(pickInitialToken()).toEqual({
                token: 'stored-reconnect',
                source: 'session-storage',
            });
        });

        it('没有存量时才回落到地址栏那枚（首次从游戏里点链接进来的场景）', () => {
            window.history.replaceState(null, '', '/?token=url-one-shot');
            expect(pickInitialToken()).toEqual({ token: 'url-one-shot', source: 'url' });
        });

        it('两边都没有 → none', () => {
            expect(pickInitialToken()).toEqual({ token: null, source: 'none' });
        });
    });

    describe('stripTokenFromUrl', () => {
        it('抹掉 token 且不留光秃秃的问号', () => {
            window.history.replaceState(null, '', '/?token=abc');
            stripTokenFromUrl();
            expect(window.location.search).toBe('');
            expect(window.location.pathname).toBe('/');
        });

        it('保留其他查询参数与 hash', () => {
            window.history.replaceState(null, '', '/editor?debug=1&token=abc#panel');
            stripTokenFromUrl();
            expect(window.location.pathname).toBe('/editor');
            expect(new URLSearchParams(window.location.search).get('token')).toBeNull();
            expect(new URLSearchParams(window.location.search).get('debug')).toBe('1');
            expect(window.location.hash).toBe('#panel');
        });

        it('地址栏本来就没有 token → 什么都不改', () => {
            window.history.replaceState(null, '', '/editor?debug=1');
            stripTokenFromUrl();
            expect(window.location.search).toBe('?debug=1');
        });

        it('抹过之后 pickInitialToken 只剩 sessionStorage 一条路（模拟握手成功后再刷新）', () => {
            window.history.replaceState(null, '', '/?token=url-one-shot');
            sessionStorage.setItem(RECONNECT_TOKEN_KEY, 'stored-reconnect');
            stripTokenFromUrl();
            expect(pickInitialToken()).toEqual({
                token: 'stored-reconnect',
                source: 'session-storage',
            });
            sessionStorage.clear();
            expect(pickInitialToken()).toEqual({ token: null, source: 'none' });
        });
    });
});

// ---------- 3. resolveWsUrl ----------

describe('resolveWsUrl', () => {
    const realLocation = window.location;

    function stubLocation(loc: Partial<Location>): void {
        Object.defineProperty(window, 'location', {
            value: loc,
            writable: true,
            configurable: true,
        });
    }

    afterEach(() => {
        Object.defineProperty(window, 'location', {
            value: realLocation,
            writable: true,
            configurable: true,
        });
    });

    it('生产 HTTPS 反代域名 → wss://<域名>/ws（同源 + scheme 跟随）', () => {
        stubLocation({
            protocol: 'https:',
            host: 'canvas.example.com',
            hostname: 'canvas.example.com',
            port: '',
        });
        expect(resolveWsUrl()).toBe('wss://canvas.example.com/ws');
    });

    it('生产 HTTP 域名（带端口） → ws://<域名:端口>/ws（真实域名走同源，非回环）', () => {
        stubLocation({
            protocol: 'http:',
            host: 'example.com:8877',
            hostname: 'example.com',
            port: '8877',
        });
        expect(resolveWsUrl()).toBe('ws://example.com:8877/ws');
    });

    it('dev（页面在 :9173） → ws://<hostname>:8877/ws（跨端口连后端明文 8877）', () => {
        stubLocation({
            protocol: 'http:',
            host: '127.0.0.1:9173',
            hostname: '127.0.0.1',
            port: '9173',
        });
        expect(resolveWsUrl()).toBe('ws://127.0.0.1:8877/ws');
    });

    it('生产任意域名都不得落回硬编码回环地址', () => {
        stubLocation({
            protocol: 'https:',
            host: 'signs.myserver.net',
            hostname: 'signs.myserver.net',
            port: '',
        });
        expect(resolveWsUrl()).not.toContain('127.0.0.1');
    });
});

// ---------- 4. applyScriptPatches & applyScriptTrace ----------

function makeRule(id: string, name = `rule-${id}`): ScriptRule {
    return {
        id,
        wallId: 'w-abc12345',
        enabled: true,
        name,
        trigger: { type: 'wallReady' },
        actions: [{ type: 'log', message: 'hi' }],
        blockLayout: '{}',
    };
}

describe('applyScriptPatches & applyScriptTrace', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
    });

    describe('applyScriptPatches', () => {
        it('add 完整 rule → upsert 落表', () => {
            const store = useScriptStore();
            const rule = makeRule('sr-1');
            const ops: PatchOp[] = [{ op: 'add', path: '/scripts/sr-1', value: rule }];
            applyScriptPatches(ops);
            expect(store.get('sr-1')).toEqual(rule);
            expect(store.size).toBe(1);
        });

        it('add 已存在 ruleId → 替换（replace 语义统一用 add）', () => {
            const store = useScriptStore();
            applyScriptPatches([{ op: 'add', path: '/scripts/sr-1', value: makeRule('sr-1', '旧') }]);
            applyScriptPatches([{ op: 'add', path: '/scripts/sr-1', value: makeRule('sr-1', '新') }]);
            expect(store.get('sr-1')?.name).toBe('新');
            expect(store.size).toBe(1);
        });

        it('replace 也收下（兼容形态）', () => {
            const store = useScriptStore();
            const rule = makeRule('sr-2');
            applyScriptPatches([{ op: 'replace', path: '/scripts/sr-2', value: rule }]);
            expect(store.get('sr-2')).toEqual(rule);
        });

        it('remove → 删除规则', () => {
            const store = useScriptStore();
            applyScriptPatches([{ op: 'add', path: '/scripts/sr-1', value: makeRule('sr-1') }]);
            applyScriptPatches([{ op: 'remove', path: '/scripts/sr-1' }]);
            expect(store.get('sr-1')).toBe(null);
            expect(store.size).toBe(0);
        });

        it('空 ruleId 段 → 忽略不抛（log err）', () => {
            const store = useScriptStore();
            applyScriptPatches([{ op: 'add', path: '/scripts/', value: makeRule('sr-x') }]);
            expect(store.size).toBe(0);
        });

        it('add 缺 value / value 非对象 → 忽略不抛', () => {
            const store = useScriptStore();
            applyScriptPatches([
                { op: 'add', path: '/scripts/sr-1' },
                { op: 'add', path: '/scripts/sr-2', value: 'not-an-object' },
            ]);
            expect(store.size).toBe(0);
        });

        it('RFC 6901 编码的 ruleId 段正确解码（~1 → / ，~0 → ~）', () => {
            const store = useScriptStore();
            const rule = makeRule('sr~odd/id');
            applyScriptPatches([{ op: 'add', path: '/scripts/sr~0odd~1id', value: rule }]);
            expect(store.get('sr~odd/id')).not.toBe(null);
            applyScriptPatches([{ op: 'remove', path: '/scripts/sr~0odd~1id' }]);
            expect(store.size).toBe(0);
        });

        it('value 缺 id → store 不变 + 不抛', () => {
            const store = useScriptStore();
            const { id: _drop, ...noId } = makeRule('sr-1');
            applyScriptPatches([{ op: 'add', path: '/scripts/sr-1', value: noId }]);
            expect(store.size).toBe(0);
            expect(store.get('sr-1')).toBe(null);
        });

        it('value.id 与 path 段失配 → 按 value.id 落表（且无 undefined key）', () => {
            const store = useScriptStore();
            const rule = makeRule('sr-real');
            applyScriptPatches([{ op: 'add', path: '/scripts/sr-path-id', value: rule }]);
            expect(store.get('sr-real')).toEqual(rule);
            expect(store.get('sr-path-id')).toBe(null);
            expect(store.size).toBe(1);
            expect(store.order).toEqual(['sr-real']);
        });
    });

    describe('applyScriptTrace', () => {
        it('合法 payload → lastTrace 落表', () => {
            const store = useScriptStore();
            const payload: ScriptTracePayload = {
                ruleId: 'sr-1',
                steps: [
                    { blockId: 'trigger', kind: 'trigger', result: 'ok', detail: 'TEST test' },
                    { blockId: 'actions/0', kind: 'action', result: 'ok' },
                ],
            };
            applyScriptTrace(payload);
            expect(store.lastTrace).toEqual(payload);
        });

        it('畸形 payload（缺 ruleId / steps 非数组 / null）→ 不落表不抛', () => {
            const store = useScriptStore();
            applyScriptTrace(null);
            applyScriptTrace(undefined);
            applyScriptTrace({ steps: [] } as unknown as ScriptTracePayload);
            applyScriptTrace({ ruleId: 'sr-1', steps: 'oops' } as unknown as ScriptTracePayload);
            expect(store.lastTrace).toBe(null);
        });

        it('连续推送覆盖式——只留最近一次', () => {
            const store = useScriptStore();
            applyScriptTrace({ ruleId: 'sr-old', steps: [] });
            applyScriptTrace({
                ruleId: 'sr-new',
                steps: [{ blockId: 'trigger', kind: 'trigger', result: 'blocked', detail: '链深熔断' }],
            });
            expect(store.lastTrace!.ruleId).toBe('sr-new');
        });
    });
});
