/**
 * 方向键微移的「本地累积 + 一次提交」队列。
 *
 * <p>画布拖动早就是这个纪律：拖动过程只改本地，落地时才发一帧 op。方向键是漏网的那条路径——
 * 原来每次 keydown 都逐元素发 {@code element.transform}，而按住方向键时 OS 自动重复能打出
 * 几十次 keydown。后端限流是 2 秒 40 帧的固定窗，超出的帧直接丢弃：前端已乐观改过坐标、
 * 服务端却没收下，画面就会走一半被快照拽回去；连续违规攒够次数服务端还会以 1008 关连接，
 * 而 1008 是终止态不自动重连，等于长按方向键把编辑器踢下线。</p>
 *
 * <p>抽成独立模块是为了能直接测「按了 N 次只发 1 帧」，不用把整个 App 挂起来。</p>
 */

/** 队列依赖的两个外部能力：读元素当前坐标、发送落点。 */
export interface NudgeQueueOptions {
    /** 取元素当前坐标；元素不存在返 null（本次微移忽略）。 */
    getElement: (id: string) => { x: number; y: number } | null;
    /** 提交落点（生产实现 = 发 {@code element.transform}）。 */
    send: (id: string, x: number, y: number) => void;
}

export interface NudgeQueue {
    /**
     * 移动一个元素：<b>就地改它的 x/y</b>（乐观本地更新，画面立刻跟手）并登记待提交落点。
     * 不发送任何东西。
     */
    nudge: (id: string, dx: number, dy: number) => void;
    /** 把所有待提交落点各发一帧，然后清空。没有待提交时什么都不做。 */
    flush: () => void;
    /** 当前待提交的元素个数（测试 / 调试用）。 */
    pendingCount: () => number;
}

export function createNudgeQueue(opts: NudgeQueueOptions): NudgeQueue {
    /** elementId → 最新落点。同一元素连按多次只保留最后位置，所以按多少次都只发一帧。 */
    const pending = new Map<string, { x: number; y: number }>();

    function nudge(id: string, dx: number, dy: number): void {
        const el = opts.getElement(id);
        if (!el) return;
        el.x += dx;
        el.y += dy;
        pending.set(id, { x: el.x, y: el.y });
    }

    function flush(): void {
        if (pending.size === 0) return;
        // 先取快照再清空：send 里若同步触发了新的 nudge（理论上不会），也不会漏掉或重发。
        const entries = Array.from(pending.entries());
        pending.clear();
        for (const [id, pos] of entries) opts.send(id, pos.x, pos.y);
    }

    return { nudge, flush, pendingCount: () => pending.size };
}
