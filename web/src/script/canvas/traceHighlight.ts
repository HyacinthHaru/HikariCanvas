/**
 * 0.7.0-P5-H（K-UI-8）：试跑高亮的纯逻辑层。
 *
 * <p>后端 {@code script.test} 异步跑完后推 {@code script.trace}（{@code {ruleId, steps}}，
 * steps 每项 {@code {blockId, kind, result, detail?}}）。编辑器拿到后<b>按 steps 顺序逐个点亮</b>
 * 对应积木（120ms 步进），高亮态存编辑器<b>局部 ref</b>（非 store——纯展示态，不入 server）。</p>
 *
 * <p>本文件把"步进点亮"抽成纯函数 + 时序常量，便于单测（给 steps → 校验每步累积高亮 map），
 * 组件只负责挂定时器 + 在卸载 / 切规则 / 重新试跑时清理（onScopeDispose / watch）。</p>
 *
 * <p><b>blockId 树路径</b>：{@code 'trigger'}（对应 BlockStack 帽子）/ {@code 'actions/2'} /
 * {@code 'actions/2/then/1'}——与后端 trace blockId、blockTree path、积木 {@code data-block-path}
 * 逐字符同构。BlockNode / BlockStack 用自身 path 查 highlightMap 取 result → 边框色。</p>
 */

import type { ScriptTraceStep } from '@/types/protocol';

/** 单步点亮间隔（ms）。K-UI-8：120ms 步进动画。 */
export const STEP_INTERVAL_MS = 120;
/** 全部点完后保留高亮的时长（ms），随后自动清（除非期间又触发新试跑 / 编辑）。 */
export const HOLD_AFTER_MS = 2000;

/** trace step 的结果态（与 {@link ScriptTraceStep.result} 同）。 */
export type StepResult = ScriptTraceStep['result'];

/** blockId → 结果态的高亮映射（组件局部 ref 持有；BlockNode/BlockStack 查它取边框色）。 */
export type HighlightMap = Map<string, StepResult>;

/**
 * 结果态 → Catppuccin CSS 变量名（含 {@code --} 前缀）。K-UI-8 配色：
 * ok=green / skipped=overlay0 / blocked=yellow / error=red。未知态退边框灰（防御）。
 */
export function resultColorVar(result: StepResult): string {
    switch (result) {
        case 'ok':
            return '--ctp-green';
        case 'skipped':
            return '--ctp-overlay0';
        case 'blocked':
            return '--ctp-yellow';
        case 'error':
            return '--ctp-red';
        default:
            return '--border';
    }
}

/**
 * 把 trace steps 展开成"逐步累积高亮 map"序列：第 i 帧（0-based）= 已点亮前 i+1 个 step 的
 * map 快照。<b>纯函数</b>——给定 steps 返回 N 个 Map（N = steps.length），第 i 个含 steps[0..i]
 * 的 {@code blockId → result}。同一 blockId 被多个 step 命中时<b>后者覆盖前者</b>（后端 trace
 * 顺序即执行顺序，最后一次结果是该块的最终态）。
 *
 * <p>组件按 {@link STEP_INTERVAL_MS} 定时把第 0、1、2… 帧赋给局部 ref 即得到步进动画；测试直接
 * 断言每帧 map 内容，无需 fake timer。空 steps → 空数组（无可点亮）。</p>
 */
export function buildHighlightFrames(steps: ScriptTraceStep[]): HighlightMap[] {
    const frames: HighlightMap[] = [];
    const acc: HighlightMap = new Map();
    for (const step of steps) {
        // 防御：blockId 缺失 / 空 → 跳过（不产生可点亮帧，但不影响后续 step 的帧序）。
        if (!step.blockId) {
            // 仍推一帧（保持帧数 = 有效 step 数的语义稳定？）——不：跳过空 blockId，
            // 帧数 = 有效 step 数。这样 UI 停在最后一个有效高亮上。
            continue;
        }
        acc.set(step.blockId, step.result);
        frames.push(new Map(acc));
    }
    return frames;
}

/**
 * 驱动步进高亮的轻量控制器（组件用）。{@code apply} 回调把当前帧 map 赋给组件局部 ref；
 * {@code start(steps)} 重置并按 {@link STEP_INTERVAL_MS} 逐帧推进，末帧后 {@link HOLD_AFTER_MS}
 * 自动清空（{@code apply(empty)}）。{@code stop()} 立即停定时器（不清当前高亮）；{@code clear()}
 * 停 + 清空。组件在 onScopeDispose / 切规则 / 关闭 overlay 时调 {@code clear()}。
 *
 * <p>定时器用注入的 {@code setTimer} / {@code clearTimer}（默认 window）——测试可注入 fake。
 * 所有 timer 句柄集中管理，{@code stop}/{@code clear}/重新 {@code start} 都先清旧句柄，杜绝泄漏。</p>
 */
export interface HighlightStepper {
    /** 开始一轮步进（先停旧轮）。空 steps → 直接清空高亮。 */
    start(steps: ScriptTraceStep[]): void;
    /** 停定时器（保留当前高亮）。 */
    stop(): void;
    /** 停 + 清空高亮。 */
    clear(): void;
}

export interface StepperOptions {
    /** 每帧把 map 应用到组件局部 ref（空 map = 清高亮）。 */
    apply: (map: HighlightMap) => void;
    /** 注入定时器（测试用 fake）；默认 {@code window.setTimeout}。 */
    setTimer?: (cb: () => void, ms: number) => number;
    /** 注入清定时器；默认 {@code window.clearTimeout}。 */
    clearTimer?: (handle: number) => void;
}

export function createHighlightStepper(opts: StepperOptions): HighlightStepper {
    const setTimer = opts.setTimer ?? ((cb, ms) => window.setTimeout(cb, ms) as unknown as number);
    const clearTimer = opts.clearTimer ?? ((h) => window.clearTimeout(h));
    let timer: number | null = null;

    function killTimer(): void {
        if (timer !== null) {
            clearTimer(timer);
            timer = null;
        }
    }

    function start(steps: ScriptTraceStep[]): void {
        killTimer();
        const frames = buildHighlightFrames(steps);
        if (frames.length === 0) {
            opts.apply(new Map());
            return;
        }
        let i = 0;
        const tick = (): void => {
            opts.apply(frames[i]);
            i++;
            if (i < frames.length) {
                timer = setTimer(tick, STEP_INTERVAL_MS);
            } else {
                // 末帧已应用：保留 HOLD_AFTER_MS 后清空。
                timer = setTimer(() => {
                    timer = null;
                    opts.apply(new Map());
                }, HOLD_AFTER_MS);
            }
        };
        tick();
    }

    function stop(): void {
        killTimer();
    }

    function clear(): void {
        killTimer();
        opts.apply(new Map());
    }

    return { start, stop, clear };
}
