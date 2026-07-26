/**
 * 方向键微移队列：按多少次都只发一帧。
 *
 * <p>修复前每次 keydown 都发一帧 element.transform，按住方向键靠 OS 自动重复就能顶穿
 * 后端 2 秒 40 帧的限流窗（丢帧回跳，连续违规还会被 1008 断连）。这组用例把
 * 「按键期间零发送、提交时每个元素一帧」钉死。</p>
 */
import { describe, it, expect, vi } from 'vitest';
import { createNudgeQueue } from '../nudgeQueue';

function setup(elements: Record<string, { x: number; y: number }>) {
    const send = vi.fn();
    const q = createNudgeQueue({
        getElement: (id) => elements[id] ?? null,
        send,
    });
    return { q, send, elements };
}

describe('createNudgeQueue', () => {
    it('连按 20 次只在提交时发 1 帧，坐标是累计后的落点', () => {
        const { q, send, elements } = setup({ e1: { x: 10, y: 20 } });
        for (let i = 0; i < 20; i++) q.nudge('e1', 1, 0);
        // 按键期间一帧都不发
        expect(send).not.toHaveBeenCalled();
        // 本地已经跟着走（画面即时反馈）
        expect(elements.e1).toEqual({ x: 30, y: 20 });

        q.flush();
        expect(send).toHaveBeenCalledTimes(1);
        expect(send).toHaveBeenCalledWith('e1', 30, 20);
    });

    it('多选：每个元素各一帧，互不串写', () => {
        const { q, send } = setup({ e1: { x: 0, y: 0 }, e2: { x: 100, y: 100 } });
        for (let i = 0; i < 5; i++) {
            q.nudge('e1', 0, -1);
            q.nudge('e2', 0, -1);
        }
        q.flush();
        expect(send).toHaveBeenCalledTimes(2);
        expect(send).toHaveBeenCalledWith('e1', 0, -5);
        expect(send).toHaveBeenCalledWith('e2', 100, 95);
    });

    it('提交后清空：再次 flush 不重发', () => {
        const { q, send } = setup({ e1: { x: 0, y: 0 } });
        q.nudge('e1', 1, 1);
        q.flush();
        q.flush();
        expect(send).toHaveBeenCalledTimes(1);
        expect(q.pendingCount()).toBe(0);
    });

    it('没按过方向键就 flush → 不发任何东西', () => {
        const { q, send } = setup({ e1: { x: 0, y: 0 } });
        q.flush();
        expect(send).not.toHaveBeenCalled();
    });

    it('元素已不存在 → 忽略这次微移，不进待提交队列', () => {
        const { q, send } = setup({ e1: { x: 0, y: 0 } });
        q.nudge('gone', 5, 5);
        expect(q.pendingCount()).toBe(0);
        q.flush();
        expect(send).not.toHaveBeenCalled();
    });
});
