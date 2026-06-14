/**
 * 0.7.3 ultrareview 批 A：onClose terminal 集合 + VERSION_MISMATCH 文案单测。
 *
 * <p>isTerminalCloseCode 是纯函数，node 环境即可跑（不依赖 Pinia / WebSocket）。
 * i18n 文案测试直接读 messages 表（同 localizeErrorCode 的 wsClient 内部逻辑）。</p>
 *
 * 覆盖项：
 * - A1：4002 进 terminal（不触发 scheduleReconnect）
 * - A2：4429 进 terminal（4008 不在 terminal）
 * - A3：VERSION_MISMATCH 文案表达"协议版本不兼容"而非状态同步
 * - 回归：1000 / 4001 仍在 terminal；1001 / 1006 / 1011 不在 terminal
 */
import { describe, expect, it } from 'vitest';
import { isTerminalCloseCode } from '../wsClient';
import { messages } from '@/i18n/messages';

// ---------- A1 + A2：terminal 集合 ----------

describe('isTerminalCloseCode', () => {
    // 终止态：重连无意义的 code
    it('1000 (normal close) → terminal', () => {
        expect(isTerminalCloseCode(1000)).toBe(true);
    });

    it('4001 (auth failed) → terminal', () => {
        expect(isTerminalCloseCode(4001)).toBe(true);
    });

    it('4002 (protocol version unsupported) → terminal [A1]', () => {
        // 后端 close(4002) 说明 client_v 不在 SUPPORTED_MIN..MAX 范围内；
        // 用同 CLIENT_V 重连只会再被拒——必须升级客户端，不应退避重连。
        expect(isTerminalCloseCode(4002)).toBe(true);
    });

    it('4429 (token rate limited) → terminal [A2]', () => {
        // Protocol.CLOSE_TOKEN_RATE_LIMITED = 4429（TokenRateLimiter 触发）；
        // 重连只会继续消耗限流窗口，等窗口复位后用户刷新即可，不自动重连。
        expect(isTerminalCloseCode(4429)).toBe(true);
    });

    // 非终止态：应走 scheduleReconnect 退避
    it('1001 (going away / server reboot) → non-terminal', () => {
        expect(isTerminalCloseCode(1001)).toBe(false);
    });

    it('1006 (abnormal close / server down) → non-terminal', () => {
        expect(isTerminalCloseCode(1006)).toBe(false);
    });

    it('1011 (server internal error) → non-terminal', () => {
        expect(isTerminalCloseCode(1011)).toBe(false);
    });

    it('4008 (死码，后端从未发出) → non-terminal [A2 回归]', () => {
        // 原代码误将 4008 列入 terminal；4008 后端无对应分支，永远不会收到。
        // 修后 4008 不在 terminal——即使收到也走重连（比永久卡死更安全）。
        expect(isTerminalCloseCode(4008)).toBe(false);
    });
});

// ---------- A3：VERSION_MISMATCH 文案语义 ----------

describe('messages VERSION_MISMATCH', () => {
    it('中文文案表达"协议版本不兼容"（不是"画板被改动"）[A3]', () => {
        const zh = (messages.zh.errors as Record<string, string>).VERSION_MISMATCH;
        // 不应含"状态同步"语义词
        expect(zh).not.toMatch(/改动/);
        expect(zh).not.toMatch(/同步/);
        // 应含"版本"或"升级"语义
        expect(zh).toMatch(/版本|升级/);
    });

    it('英文文案表达"协议版本不兼容"（不是"wall was changed"）[A3]', () => {
        const en = (messages.en.errors as Record<string, string>).VERSION_MISMATCH;
        // 不应含 wall 改动语义
        expect(en).not.toMatch(/changed/i);
        expect(en).not.toMatch(/syncing/i);
        // 应含 incompatible / version / upgrade 语义
        expect(en).toMatch(/incompatible|version|upgrade/i);
    });
});
