// @vitest-environment happy-dom
/**
 * resolveWsUrl 单测。
 *
 * <p>resolveWsUrl 只读 window.location，import 即安全（wsClient 内所有 window 访问都在方法内）。
 * happy-dom 提供 window；每个 case 用 Object.defineProperty 换掉 location 快照。</p>
 *
 * 覆盖三场景（对应生产反代 / 生产明文域名 / dev 跨端口）：
 * - 生产 HTTPS 反代域名 → wss://<域名>/ws（同源，scheme 跟随页面）
 * - 生产 HTTP 域名（带端口） → ws://<域名:端口>/ws（真实域名走同源拼，不是回环）
 * - dev（页面在 :9173） → ws://<hostname>:8877/ws（后端明文本机 8877，跨端口）
 *
 * 关键回归：任何真实部署域名都不得落回硬编码的 ws://127.0.0.1:8877/ws
 * （那是原 bug 的字面来源——反代后连不上 + ws:// 撞 HTTPS 页面 mixed-content）。
 */
import { describe, expect, it, afterEach } from 'vitest';
import { resolveWsUrl } from '../wsClient';

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

describe('resolveWsUrl', () => {
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
        // 关键：port 恰好是 8877 但 hostname 不是 127.0.0.1，必须按同源拼 example.com，
        // 绝不能退化成 ws://127.0.0.1:8877/ws。
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

    it('回归：生产任意域名都不得落回硬编码回环地址', () => {
        stubLocation({
            protocol: 'https:',
            host: 'signs.myserver.net',
            hostname: 'signs.myserver.net',
            port: '',
        });
        expect(resolveWsUrl()).not.toContain('127.0.0.1');
    });
});
