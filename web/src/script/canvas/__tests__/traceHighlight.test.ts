/**
 * 0.7.0-P5-H：试跑高亮纯逻辑单测（buildHighlightFrames 逐帧累积 + stepper 步进时序 +
 * resultColorVar 配色 + 清理）。组件挂定时器的部分抽成纯函数 / 注入定时器测，无需真 timer。
 */
import { describe, it, expect, vi } from 'vitest';
import {
    buildHighlightFrames,
    createHighlightStepper,
    resultColorVar,
    STEP_INTERVAL_MS,
    HOLD_AFTER_MS,
    type HighlightMap,
} from '../traceHighlight';
import type { ScriptTraceStep } from '@/types/protocol';

function step(blockId: string, result: ScriptTraceStep['result'], detail?: string): ScriptTraceStep {
    return { blockId, kind: 'action', result, ...(detail ? { detail } : {}) };
}

describe('resultColorVar — 结果态配色（K-UI-8）', () => {
    it('ok=green / skipped=overlay0 / blocked=yellow / error=red', () => {
        expect(resultColorVar('ok')).toBe('--ctp-green');
        expect(resultColorVar('skipped')).toBe('--ctp-overlay0');
        expect(resultColorVar('blocked')).toBe('--ctp-yellow');
        expect(resultColorVar('error')).toBe('--ctp-red');
    });
});

describe('buildHighlightFrames — 逐帧累积', () => {
    it('空 steps → 空帧', () => {
        expect(buildHighlightFrames([])).toEqual([]);
    });
    it('N step → N 帧，第 i 帧含 steps[0..i]', () => {
        const steps = [step('trigger', 'ok'), step('actions/0', 'ok'), step('actions/1', 'skipped')];
        const frames = buildHighlightFrames(steps);
        expect(frames).toHaveLength(3);
        // 帧 0：只有 trigger
        expect([...frames[0]]).toEqual([['trigger', 'ok']]);
        // 帧 1：trigger + actions/0
        expect(frames[1].get('trigger')).toBe('ok');
        expect(frames[1].get('actions/0')).toBe('ok');
        expect(frames[1].size).toBe(2);
        // 帧 2：全部三个
        expect(frames[2].size).toBe(3);
        expect(frames[2].get('actions/1')).toBe('skipped');
    });
    it('同一 blockId 被多步命中 → 后者覆盖', () => {
        const steps = [step('actions/0', 'ok'), step('actions/0', 'error')];
        const frames = buildHighlightFrames(steps);
        // 第 2 帧该 block 取 error（最后一次结果）
        expect(frames[1].get('actions/0')).toBe('error');
        expect(frames[1].size).toBe(1);
    });
    it('空 blockId 的 step 被跳过（不产帧）', () => {
        const steps = [step('', 'ok'), step('actions/0', 'ok')];
        const frames = buildHighlightFrames(steps);
        expect(frames).toHaveLength(1);
        expect(frames[0].get('actions/0')).toBe('ok');
    });
    it('每帧是独立 Map 快照（改后帧不互相污染）', () => {
        const steps = [step('a', 'ok'), step('b', 'ok')];
        const frames = buildHighlightFrames(steps);
        frames[0].set('zzz', 'error'); // 改帧 0
        expect(frames[1].has('zzz')).toBe(false); // 帧 1 不受影响
    });
});

describe('createHighlightStepper — 注入 fake timer 步进', () => {
    /** 简易 fake timer：手动 flush 下一个排队回调。 */
    function fakeTimers() {
        const queue: { cb: () => void; ms: number; handle: number }[] = [];
        let next = 1;
        const setTimer = (cb: () => void, ms: number): number => {
            const handle = next++;
            queue.push({ cb, ms, handle });
            return handle;
        };
        const clearTimer = (handle: number): void => {
            const i = queue.findIndex((q) => q.handle === handle);
            if (i >= 0) queue.splice(i, 1);
        };
        /** 触发并移除队首回调，返回它的 ms（断言间隔用）。 */
        const flush = (): number | null => {
            const item = queue.shift();
            if (!item) return null;
            item.cb();
            return item.ms;
        };
        return { setTimer, clearTimer, flush, pending: () => queue.length };
    }

    it('按 STEP_INTERVAL_MS 逐帧应用 + 末帧后 HOLD_AFTER_MS 清空', () => {
        const ft = fakeTimers();
        const applied: HighlightMap[] = [];
        const stepper = createHighlightStepper({
            apply: (m) => applied.push(new Map(m)),
            setTimer: ft.setTimer,
            clearTimer: ft.clearTimer,
        });
        const steps = [step('trigger', 'ok'), step('actions/0', 'blocked')];
        stepper.start(steps);
        // 第一帧立即应用（tick 同步执行一次）
        expect(applied).toHaveLength(1);
        expect(applied[0].size).toBe(1);
        // 排了第 2 帧的 timer，间隔 = STEP_INTERVAL_MS
        expect(ft.flush()).toBe(STEP_INTERVAL_MS);
        expect(applied).toHaveLength(2);
        expect(applied[1].size).toBe(2);
        // 末帧后排了 HOLD timer
        expect(ft.flush()).toBe(HOLD_AFTER_MS);
        // HOLD 到点 → 清空（apply 空 map）
        expect(applied[applied.length - 1].size).toBe(0);
    });

    it('空 steps → 直接 apply 空 map（无 timer）', () => {
        const ft = fakeTimers();
        const applied: HighlightMap[] = [];
        const stepper = createHighlightStepper({
            apply: (m) => applied.push(new Map(m)),
            setTimer: ft.setTimer,
            clearTimer: ft.clearTimer,
        });
        stepper.start([]);
        expect(applied).toHaveLength(1);
        expect(applied[0].size).toBe(0);
        expect(ft.pending()).toBe(0);
    });

    it('重新 start 先清旧 timer（不残留旧轮步进）', () => {
        const ft = fakeTimers();
        const apply = vi.fn();
        const stepper = createHighlightStepper({ apply, setTimer: ft.setTimer, clearTimer: ft.clearTimer });
        stepper.start([step('a', 'ok'), step('b', 'ok'), step('c', 'ok')]);
        // 第一帧已 apply，队里有第 2 帧 timer
        expect(ft.pending()).toBe(1);
        // 重新 start 另一组：应清掉旧 timer，只留新轮的
        stepper.start([step('x', 'ok'), step('y', 'ok')]);
        expect(ft.pending()).toBe(1); // 仍只有 1 个（旧的被清，新的排上）
    });

    it('clear() 停 timer + apply 空 map', () => {
        const ft = fakeTimers();
        const applied: HighlightMap[] = [];
        const stepper = createHighlightStepper({
            apply: (m) => applied.push(new Map(m)),
            setTimer: ft.setTimer,
            clearTimer: ft.clearTimer,
        });
        stepper.start([step('a', 'ok'), step('b', 'ok')]);
        stepper.clear();
        // clear 后队列空（timer 被清）+ 最后一次 apply 是空 map
        expect(ft.pending()).toBe(0);
        expect(applied[applied.length - 1].size).toBe(0);
    });

    it('stop() 停 timer 但保留当前高亮（不 apply 空）', () => {
        const ft = fakeTimers();
        const applied: HighlightMap[] = [];
        const stepper = createHighlightStepper({
            apply: (m) => applied.push(new Map(m)),
            setTimer: ft.setTimer,
            clearTimer: ft.clearTimer,
        });
        stepper.start([step('a', 'ok'), step('b', 'ok')]);
        const before = applied.length;
        stepper.stop();
        expect(ft.pending()).toBe(0); // timer 停了
        expect(applied.length).toBe(before); // 没额外 apply（高亮保留）
    });
});
