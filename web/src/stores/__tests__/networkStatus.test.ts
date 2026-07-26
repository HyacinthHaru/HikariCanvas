/**
 * network store 的 status 语义守卫。
 *
 * <p>{@code lastError} 是全站统一的错误显示通道，写入方包含大量业务路径
 * （scriptEdit / TopBar / ScriptVariableWatch / ConditionBuilder）。早先 status 直接判
 * {@code lastError}，于是一次业务失败（改变量失败 / 规则校验不过）就让连接状态栏持续显示
 * error 直到重连——而连接本身完全正常。现在只有连接层错误（{@link setConnectionError}）
 * 才染红 status。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useNetworkStore } from '../network';

beforeEach(() => {
    setActivePinia(createPinia());
});

describe('network status', () => {
    it('业务错误写 lastError 不把 status 打成 error', () => {
        const net = useNetworkStore();
        net.connected = true;
        net.authenticated = true;
        expect(net.status).toBe('ready');

        net.lastError = '改变量失败：quota exceeded';
        expect(net.status).toBe('ready');
        expect(net.lastError).not.toBeNull();
    });

    it('连接层错误走 setConnectionError → status 变 error，且同步进显示通道', () => {
        const net = useNetworkStore();
        net.connected = true;
        net.authenticated = true;

        net.setConnectionError('连接断开，1s 后重试');
        expect(net.status).toBe('error');
        expect(net.connectionError).toBe('连接断开，1s 后重试');
        expect(net.lastError).toBe('连接断开，1s 后重试');
    });

    it('clearErrors 同时清两个位', () => {
        const net = useNetworkStore();
        net.setConnectionError('boom');
        net.lastError = '业务提示';
        net.clearErrors();
        expect(net.connectionError).toBeNull();
        expect(net.lastError).toBeNull();
    });

    it('残留的业务 lastError 不阻碍重连后回到 ready', () => {
        const net = useNetworkStore();
        net.lastError = '删除规则失败';
        net.connected = true;
        net.authenticated = true;
        expect(net.status).toBe('ready');
    });
});
