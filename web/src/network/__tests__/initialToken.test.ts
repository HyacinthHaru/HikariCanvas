// @vitest-environment happy-dom
/**
 * pickInitialToken / stripTokenFromUrl 单测。
 *
 * <p>背景：地址栏的 {@code ?token=} 是 {@code /canvas confirm} 发的一次性券，后端
 * TokenService 用 CAS 消费，第二次必拒；sessionStorage 里那枚是握手时 rotate 下来的
 * reconnectToken，配合服务端的宽限期，F5 之后原会话还在、拿它就能直接续上。</p>
 *
 * <p>关键回归：刷新页面不能再优先拿地址栏那枚已消费的券去握手——那条路必然 4001，
 * 而 4001 分支会顺手清掉 sessionStorage 里真正能用的那枚，刷新一次就得回游戏重开。</p>
 *
 * <p>两个函数都只读 window.location / sessionStorage，import 即安全（wsClient 里所有
 * window 访问都在函数体内）。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { pickInitialToken, stripTokenFromUrl } from '../wsClient';

const RECONNECT_TOKEN_KEY = 'hikari-canvas:reconnect-token';

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
