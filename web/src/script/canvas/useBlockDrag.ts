import { computed, onScopeDispose, ref, type Ref } from 'vue';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useProjectStore } from '@/stores/project';
import { insertAt, moveNode, parsePath, pathToString } from '@/script/model/blockTree';
import { findDropTarget, type SlotRect } from '@/script/model/dropTarget';
import { makeDefaultAction, TWEENABLE_KINDS } from '@/script/model/blockDefs';

/**
 * 积木拖拽吸附核心。
 *
 * <p>两种拖拽源：① 从 palette 拖出<b>新块</b>（{@link startPaletteDrag}）；② 在画布上拖
 * <b>已有块</b>（{@link startBlockDrag}）。拖动中实时测量所有可插入位置（SlotRect），用
 * {@link findDropTarget} 找命中插槽并高亮；松手对 working copy 做树操作（palette 源
 * {@code insertAt} 新建 / 画布源 {@code moveNode} 重排）后经 {@code edit.setActions} 提交
 * （进 undo + debounce save）。另有<b>移堆</b>（{@link startStackDrag}）改 blockLayout 坐标。</p>
 *
 * <p><b>测量纯逻辑可测</b>：DOM 测量（{@code getBoundingClientRect}）与 SlotRect 推导分离——
 * {@link buildSlots} 是纯函数（喂"已测块矩形数组"返 SlotRect[]，无 DOM），{@link collectSlots}
 * 只负责遍历 DOM 取矩形再调 buildSlots。这样吸附几何可在 vitest 里不靠浏览器精确验证
 * （尤其"排除被拖块自身及其子树"这一关键正确性）。</p>
 *
 * <p><b>PointerEvent 生命周期借 {@code useBrushHost} 范式</b>：setPointerCapture +
 * pointercancel / window blur / document visibilitychange / onScopeDispose 全兜底，
 * 触发即 abort（还原拖拽态 + releaseCapture），杜绝"用户切走 → pointerup 永不到达 →
 * 卡在拖拽态"。</p>
 *
 * <p><b>lock 守卫</b>：{@code project.isLocked} 时所有 {@code start*} 直接 return
 * 不启动拖拽；setActions / setStackPos 本身也带 lock no-op（双保险）。</p>
 */

/** 吸附命中阈值（加权像素距离，传给 findDropTarget）。 */
export const DROP_THRESHOLD = 40;

/**
 * 触控板敏感度修复：<b>拖动启动阈值</b>（像素）。pointerdown 后指针位移超过此距离才算
 * "拖动意图"，真正启动拖块 / 移堆；阈值内松手视为"点击"（让积木里的字段 / 变量按钮正常聚焦）。
 *
 * <p>Mac 触控板 pointerdown 必带几像素微移，没有阈值时旧实现 pointerdown 当帧即进拖动态 →
 * 点不到字段就被拖走。5px 是图形软件常用的"误触不算拖、明确移动才算拖"分界。</p>
 *
 * <p><b>不施于 palette 源</b>：从 palette 拖出新块本就是明确拖动意图（按下即想拖出），且其 capture
 * 目标是侧栏 palette 项、不涉及 selectRule 重渲，故 {@link useBlockDrag} 的 {@code startPaletteDrag}
 * 仍按下即启动，不走阈值。</p>
 */
export const DRAG_THRESHOLD = 5;

/** 插槽吸附带的高度（像素，buildSlots 给每个序列间隙的矩形高度）。 */
const SLOT_BAND_H = 14;

/**
 * 「拖到删除区删积木」删除区几何（屏幕坐标，相对 viewport）。
 *
 * <p>删除区固定贴在 viewport <b>底部居中</b>（Scratch 垃圾桶范式），尺寸固定。仅在拖<b>动作积木</b>
 * （非 palette 源 / 非移堆）时显示。命中判定与渲染同源——{@link computeDeleteZoneRect} 由 composable
 * 算 client 矩形供 pointerup 命中；{@link DeleteDropZone} 浮层用相同的 CSS 定位渲染，二者视觉/几何对齐。</p>
 */
export const DELETE_ZONE_W = 220;
export const DELETE_ZONE_H = 64;
/** 删除区底边距 viewport 底部的间距（与浮层 CSS 的 bottom 对齐）。 */
export const DELETE_ZONE_BOTTOM = 24;

/**
 * 纯函数：由 viewport 的 client 矩形算删除区 client 矩形（底部居中）。
 * 抽出便于 vitest 验证命中几何（"指针落在区内 / 区外"）。
 */
export function computeDeleteZoneRect(vpRect: {
    left: number; top: number; width: number; height: number;
}): { left: number; top: number; width: number; height: number } {
    const width = Math.min(DELETE_ZONE_W, Math.max(0, vpRect.width - 24));
    const left = vpRect.left + (vpRect.width - width) / 2;
    const top = vpRect.top + vpRect.height - DELETE_ZONE_BOTTOM - DELETE_ZONE_H;
    return { left, top, width, height: DELETE_ZONE_H };
}

/** 点是否落在 client 矩形内（闭区间）。 */
export function pointInRect(
    rect: { left: number; top: number; width: number; height: number },
    px: number, py: number,
): boolean {
    return px >= rect.left && px <= rect.left + rect.width
        && py >= rect.top && py <= rect.top + rect.height;
}

/**
 * 一个已测量的画布元素（DOM 测量结果，喂给纯函数 {@link buildSlots}）。
 *
 * - {@code path}：{@code data-block-path}（真实块，如 {@code 'actions/2'} / {@code 'trigger'}）
 *   或 {@code data-slot-path}（空容器序列占位，如 {@code 'actions'} / {@code 'actions/2/then'}）；
 * - {@code ruleId}：所属积木堆（规则）id；
 * - {@code kind}：{@code 'block'}（真实动作块）/ {@code 'hat'}（触发器帽子，不在 actions 序列内）/
 *   {@code 'emptySlot'}（空容器占位，path 即该容器序列的 parentPath）；
 * - {@code blockType}：{@code data-block-type} 属性值（真实块的 action.type，如 'tweenBlock' /
 *   'if' / 'repeat'）；hat / emptySlot 无此值；
 * - {@code rect}：viewport（屏幕）坐标矩形。
 */
export interface MeasuredBlock {
    path: string;
    ruleId: string;
    kind: 'block' | 'hat' | 'emptySlot';
    /** 块的 action.type（由 data-block-type 读入；非 block 类型为 undefined）。 */
    blockType?: string;
    rect: { left: number; top: number; width: number; height: number };
}

/**
 * 纯函数：把已测块矩形推导为候选插槽 SlotRect[]。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>真实动作块按"所属序列"（parentPath 字符串）分组，每组按 index 升序后：每个块上沿生成
 *       一个"插到它前面"的插槽（parentPath = 去掉末段, index = 末段数字）；组末再加一个尾插槽
 *       （最后一块下沿, index = 末块 index + 1）；</li>
 *   <li>{@code emptySlot}（空容器占位）生成一个 index=0 的插槽，矩形即占位元素自身；</li>
 *   <li>{@code hat}（帽子）不直接产插槽——它只是堆的锚（ruleId）；顶层 actions 的插入由该序列的
 *       块插槽或空 actions 占位覆盖。</li>
 * </ul>
 *
 * <p><b>排除被拖块自身及其子树</b>（画布源不能拖进自己里面 → 死循环）：传入 {@code draggingPath}
 * （拖动块 path 段数组，palette 源传 {@code null}）时，剔除一切 parentPath 以 draggingPath 为
 * <b>前缀</b>的插槽（含拖动块 then/else 内所有层）。注意"拖动块前面/后面"的同级插槽不剔除——
 * 那是合法的同序列重排目标（moveNode 自带下标补偿，落回原位也只是无害空操作）。</p>
 *
 * @param measured     已测画布元素。
 * @param draggingPath 画布源拖动块 path 段（如 {@code ['actions','2']}）；palette 源传 {@code null}。
 * @param bandH        插槽吸附带高度（默认 {@link SLOT_BAND_H}）。
 */
export function buildSlots(
    measured: MeasuredBlock[],
    draggingPath: string[] | null,
    bandH: number = SLOT_BAND_H,
): SlotRect[] {
    const slots: SlotRect[] = [];

    // 0) 建 blockType 查表：block path → blockType（供子序列插槽标 containerBlockType）。
    const blockTypeMap = new Map<string, string>();
    for (const m of measured) {
        if (m.kind === 'block' && m.blockType) {
            blockTypeMap.set(m.path, m.blockType);
        }
    }

    /**
     * 从 parentPath 推断容器块 type：末段若为 body/then/else，则去掉末段得到容器块 path，查 blockTypeMap。
     * 顶层 actions 序列无容器块，返 undefined。
     */
    function containerTypeOf(parentPath: string[]): string | undefined {
        const lastSeg = parentPath[parentPath.length - 1];
        if (lastSeg !== 'body' && lastSeg !== 'then' && lastSeg !== 'else') return undefined;
        const containerPath = pathToString(parentPath.slice(0, parentPath.length - 1));
        return blockTypeMap.get(containerPath);
    }

    // 1) 空容器占位 → 单 index=0 插槽。
    for (const m of measured) {
        if (m.kind !== 'emptySlot') continue;
        const parentPath = parsePath(m.path);
        if (parentPath.length === 0) continue;
        slots.push({
            stackRuleId: m.ruleId,
            parentPath,
            index: 0,
            x: m.rect.left,
            y: m.rect.top,
            w: m.rect.width,
            h: m.rect.height,
            containerBlockType: containerTypeOf(parentPath),
        });
    }

    // 2) 真实块按所属序列分组。键 = ruleId + parentPath 串——<b>必须含 ruleId</b>：不同规则的
    //    顶层序列 parentPath 都是 ['actions']，只按 parentPath 串分组会把两堆的顶层序列错并成一组。
    interface Entry { index: number; m: MeasuredBlock; }
    const bySeq = new Map<string, { parentPath: string[]; ruleId: string; entries: Entry[] }>();
    for (const m of measured) {
        if (m.kind !== 'block') continue;
        const segs = parsePath(m.path);
        if (segs.length < 2) continue; // 块 path 至少 [seqKey, idx]
        const idx = lastIndexOf(segs);
        if (idx === null) continue;
        const parentPath = segs.slice(0, segs.length - 1);
        const key = `${m.ruleId} ${pathToString(parentPath)}`;
        let g = bySeq.get(key);
        if (!g) {
            g = { parentPath, ruleId: m.ruleId, entries: [] };
            bySeq.set(key, g);
        }
        g.entries.push({ index: idx, m });
    }

    // 3) 每组：before 插槽（块上沿）+ 尾插槽（末块下沿）。
    for (const g of bySeq.values()) {
        const contType = containerTypeOf(g.parentPath);
        g.entries.sort((a, b) => a.index - b.index);
        for (const e of g.entries) {
            slots.push({
                stackRuleId: g.ruleId,
                parentPath: g.parentPath,
                index: e.index,
                x: e.m.rect.left,
                y: e.m.rect.top,
                w: e.m.rect.width,
                h: bandH,
                containerBlockType: contType,
            });
        }
        // 尾插槽：末块下沿，index = 末块 index + 1。
        const last = g.entries[g.entries.length - 1];
        slots.push({
            stackRuleId: g.ruleId,
            parentPath: g.parentPath,
            index: last.index + 1,
            x: last.m.rect.left,
            y: last.m.rect.top + last.m.rect.height - bandH,
            w: last.m.rect.width,
            h: bandH,
            containerBlockType: contType,
        });
    }

    // 4) 排除被拖块自身及其子树：剔除 parentPath 以 draggingPath 为前缀的插槽。
    if (draggingPath !== null) {
        return slots.filter((s) => !isPrefix(draggingPath, s.parentPath));
    }
    return slots;
}

/**
 * 遍历 canvasEl 内的 {@code [data-block-path]} / {@code [data-slot-path]} 元素，
 * 用 {@code getBoundingClientRect} 测 viewport 矩形，再调 {@link buildSlots} 推导插槽。
 *
 * <p>{@code data-block-path="trigger"} 视为 hat；其余 data-block-path 视为真实块；
 * {@code data-slot-path} 视为空容器占位。ruleId 取最近祖先 {@code [data-rule-id]}。</p>
 */
export function collectSlots(canvasEl: HTMLElement, draggingPath: string[] | null): SlotRect[] {
    const measured: MeasuredBlock[] = [];

    const blockEls = canvasEl.querySelectorAll<HTMLElement>('[data-block-path]');
    blockEls.forEach((el) => {
        const path = el.getAttribute('data-block-path');
        if (!path) return;
        const ruleId = ruleIdOf(el);
        if (ruleId === null) return;
        const rect = el.getBoundingClientRect();
        measured.push({
            path,
            ruleId,
            kind: path === 'trigger' ? 'hat' : 'block',
            blockType: el.getAttribute('data-block-type') ?? undefined,
            rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
        });
    });

    const slotEls = canvasEl.querySelectorAll<HTMLElement>('[data-slot-path]');
    slotEls.forEach((el) => {
        const path = el.getAttribute('data-slot-path');
        if (!path) return;
        const ruleId = ruleIdOf(el);
        if (ruleId === null) return;
        const rect = el.getBoundingClientRect();
        measured.push({
            path,
            ruleId,
            kind: 'emptySlot',
            rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
        });
    });

    return buildSlots(measured, draggingPath);
}

// ---------- 内部纯辅助 ----------

/** 取 path 段数组末段为非负整数下标；非法 null。 */
function lastIndexOf(segs: string[]): number | null {
    const last = segs[segs.length - 1];
    if (!/^\d+$/.test(last)) return null;
    const n = Number(last);
    return Number.isSafeInteger(n) ? n : null;
}

/** a 是否为 b 的前缀（逐段相等，a.length ≤ b.length）。 */
function isPrefix(a: string[], b: string[]): boolean {
    if (a.length > b.length) return false;
    for (let i = 0; i < a.length; i++) {
        if (a[i] !== b[i]) return false;
    }
    return true;
}

/** 取元素最近祖先 [data-rule-id] 的 ruleId；无则 null。 */
function ruleIdOf(el: HTMLElement): string | null {
    const host = el.closest('[data-rule-id]');
    return host ? host.getAttribute('data-rule-id') : null;
}

// ---------- 拖拽 composable ----------

/** 跟手浮层的内容（决定浮层显示什么块预览）。 */
export interface DragGhost {
    /** palette 源 = 新块 kind；画布源 = 被拖块 kind（取其 action.type）。 */
    kind: string;
    /** 浮层当前 viewport 坐标（跟随指针，已减去抓取点偏移）。 */
    x: number;
    y: number;
}

/**
 * 当前拖拽的内部状态机。{@code mode} 区分三类拖拽；{@code none} = 未拖拽。
 */
type DragState =
    | { mode: 'none' }
    | { mode: 'palette'; kind: string; grabDx: number; grabDy: number }
    | { mode: 'block'; ruleId: string; path: string[]; kind: string; grabDx: number; grabDy: number }
    | { mode: 'stack'; ruleId: string; startX: number; startY: number; x0: number; y0: number };

/**
 * 判断「被拖积木是否允许落入目标插槽」。
 *
 * tweenBlock body 只允许 setElementProperties（kind ∈ TWEENABLE_KINDS），其他积木一律不可落。
 * 顶层序列 / if / repeat / repeatUntil 无此限制，直接返 true。
 *
 * @param draggingKind  被拖积木 kind（palette 源从 kind 直接取；block 源从 action.type 取）。
 * @param slot          目标插槽。
 * @param draggingActionKind  palette 源友好积木的 kind（如 'moveTo'）；非 setElementProperties 时同 draggingKind。
 */
export function isTweenBodySlotAllowed(
    draggingKind: string,
    slot: SlotRect,
    draggingActionKind?: string,
): boolean {
    if (slot.containerBlockType !== 'tweenBlock') return true;
    // tweenBlock body 要求：必须是 setElementProperties 且 kind ∈ TWEENABLE_KINDS。
    // setElementProperties（友好积木）：draggingKind = 'setElementProperties'，actionKind = friendly kind。
    // palette 拖出时 draggingKind 是 friendly kind（如 'moveTo'），需用 FRIENDLY_ELEMENT_DEFS 推断。
    const effectiveKind = draggingActionKind ?? draggingKind;
    if (draggingKind === 'setElementProperties') {
        return TWEENABLE_KINDS.has(effectiveKind);
    }
    // 友好积木 kind（moveTo/resize/rotateTo/setOpacity/setColor）直接在 TWEENABLE_KINDS 里。
    // setText/show/hide 不在 TWEENABLE_KINDS 里，会被排除。
    return TWEENABLE_KINDS.has(draggingKind);
}

export function useBlockDrag(opts: {
    /** 画布 viewport DOM（collectSlots 测量根 + setPointerCapture 目标兜底）。 */
    canvasRef: Ref<HTMLElement | null>;
    /** 屏幕（client）坐标 → world 坐标（移堆要把指针位移换算成 world 位移）。 */
    screenToWorld: (clientX: number, clientY: number) => { x: number; y: number };
}) {
    const edit = useScriptEditStore();
    const project = useProjectStore();

    /** 当前拖拽状态（内部）。 */
    let state: DragState = { mode: 'none' };

    /** 跟手浮层（palette / block 源拖动中显示）；null = 不显示。 */
    const ghost = ref<DragGhost | null>(null);
    /** 当前命中的吸附插槽（high­light 指示线用）；null = 无命中。 */
    const activeSlot = ref<SlotRect | null>(null);
    /** 移堆拖动中的实时坐标（让被拖堆即时跟随；松手提交 setStackPos）。 */
    const stackDragPos = ref<{ ruleId: string; x: number; y: number } | null>(null);
    /**
     * 删除区是否激活（仅拖<b>动作积木</b>时为真；palette 源 / 移堆不显示删除区）。
     * 浮层 {@link DeleteDropZone} 据此显隐。
     */
    const deleteZoneActive = ref(false);
    /** 指针当前是否悬停在删除区上（浮层据此高亮红；pointerup 时据此短路成删除）。 */
    const deleteZoneHot = ref(false);

    /** 拖动中缓存的候选插槽（pointerdown 时测一次，move 时复用——避免每帧 reflow）。 */
    let cachedSlots: SlotRect[] = [];

    // ----- PointerEvent capture（借 useBrushHost 范式）-----
    let capturedTarget: HTMLElement | null = null;
    let capturedPointerId: number | null = null;

    // ----- 拖动启动阈值（pending arm）-----
    //
    // block / stack 两源 pointerdown 时<b>不</b>立即启动拖动，而是记一个 pending + 挂临时 window
    // pointermove/up/cancel 监听。指针位移超 DRAG_THRESHOLD → 真正启动（onCross）；阈值内松手 =
    // 点击（清 pending，全程没 preventDefault / 没 capture / 没 selectRule，字段 click/focus 正常）。
    //
    // 不变性：pending 期间<b>不</b> selectRule（不重渲、不重建 DOM），故 pending.target 始终是
    // pointerdown 时刻的原始 DOM；onCross 里先 tryCapture(pending.target) 再让上层 selectRule，
    // 保证 "capture 先于 selectRule"。
    interface PendingArm {
        startX: number;
        startY: number;
        /** 跨阈值时启动真正拖动；收到跨越点 client 坐标（用于初始化浮层 / 移堆现位）。 */
        onCross: (clientX: number, clientY: number) => void;
    }
    let pending: PendingArm | null = null;
    let pendingListenersAttached = false;

    function onPendingMove(e: PointerEvent): void {
        const p = pending;
        if (!p) return;
        const dist = Math.hypot(e.clientX - p.startX, e.clientY - p.startY);
        if (dist <= DRAG_THRESHOLD) return;
        // 跨过阈值：先摘临时监听 + 清 pending，再启动真正拖动（onCross 会挂正式 move/up 监听）。
        detachPendingListeners();
        const cross = p.onCross;
        pending = null;
        cross(e.clientX, e.clientY);
    }
    function onPendingUp(): void {
        // 阈值内松手 = 点击：清 pending，不做任何拖动副作用（字段 click/focus 自然发生）。
        clearPending();
    }
    function attachPendingListeners(): void {
        if (pendingListenersAttached) return;
        window.addEventListener('pointermove', onPendingMove);
        window.addEventListener('pointerup', onPendingUp);
        window.addEventListener('pointercancel', onPendingUp);
        pendingListenersAttached = true;
    }
    function detachPendingListeners(): void {
        if (!pendingListenersAttached) return;
        window.removeEventListener('pointermove', onPendingMove);
        window.removeEventListener('pointerup', onPendingUp);
        window.removeEventListener('pointercancel', onPendingUp);
        pendingListenersAttached = false;
    }
    /** 清 pending（摘临时监听 + 丢 pending），不启动拖动。idempotent。 */
    function clearPending(): void {
        detachPendingListeners();
        pending = null;
    }
    /**
     * 武装一个延迟拖动：记起点 + onCross 回调，挂临时监听。block / stack 两源复用，避免两套临时
     * 监听逻辑。pending 已存在时先清旧的（理论上不会——pointerdown 之间总有 up/cancel 清）。
     */
    function armDrag(
        startX: number,
        startY: number,
        onCross: (clientX: number, clientY: number) => void,
    ): void {
        clearPending();
        pending = { startX, startY, onCross };
        attachPendingListeners();
    }

    function tryCapture(target: HTMLElement, pointerId: number): boolean {
        try {
            target.setPointerCapture(pointerId);
            capturedTarget = target;
            capturedPointerId = pointerId;
            return true;
        } catch {
            capturedTarget = null;
            capturedPointerId = null;
            return false;
        }
    }

    function tryRelease(): void {
        if (!capturedTarget || capturedPointerId === null) return;
        try {
            capturedTarget.releasePointerCapture(capturedPointerId);
        } catch { /* capture 已被 OS 收走时 release 会抛；safe 吞掉 */ }
        capturedTarget = null;
        capturedPointerId = null;
    }

    /**
     * idempotent 终止拖拽（不提交任何改动 = 还原）。pointercancel / blur / visibility / dispose 调；
     * onPointerUp 各分支提交前也先调它清拖拽态。
     *
     * <p>统一在这里 {@code setDragging(false)}——它是所有拖拽结束 / abort 路径
     * 的唯一汇合点（pointerup 各分支 / pointercancel / blur / visibility / dispose 都经此），保证拖完
     * 一定恢复 server-as-truth（server 回声可再替换整树）。idempotent，重复调安全。</p>
     */
    function abortDrag(): void {
        state = { mode: 'none' };
        ghost.value = null;
        activeSlot.value = null;
        stackDragPos.value = null;
        deleteZoneActive.value = false;
        deleteZoneHot.value = false;
        cachedSlots = [];
        // 若 pending（pointerdown 后未跨阈值就被 cancel/blur/dispose 中断）也一并清掉。
        clearPending();
        detachMoveListeners();
        tryRelease();
        edit.setDragging(false);
    }

    // window 级 move/up 监听：拖拽期间挂、结束摘。
    //
    // 为何挂 window 而非 canvas viewport：palette 源的 capture 目标是<b>侧栏里的 palette 项</b>
    // （在 viewport DOM 子树之外），其 pointermove/up 会冒泡到 window 但<b>不会</b>到 viewport；
    // 块 / 帽子 / 空白处 capture 目标虽在 viewport 内，挂 window 同样收得到（冒泡链更长）。
    // 故统一挂 window，三类源都覆盖。setPointerCapture 后事件 retarget 到 capture 元素再冒泡，
    // window 必在冒泡链终点。
    let moveListenersAttached = false;
    function attachMoveListeners(): void {
        if (moveListenersAttached) return;
        window.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', onPointerUp);
        window.addEventListener('pointercancel', onPointerCancelWindow);
        moveListenersAttached = true;
    }
    function detachMoveListeners(): void {
        if (!moveListenersAttached) return;
        window.removeEventListener('pointermove', onPointerMove);
        window.removeEventListener('pointerup', onPointerUp);
        window.removeEventListener('pointercancel', onPointerCancelWindow);
        moveListenersAttached = false;
    }
    function onPointerCancelWindow(): void { abortDrag(); }

    /**
     * 测量候选插槽（pointerdown 时调）。画布源传自身 path 段以排除自身子树；palette 源传 null。
     */
    function measure(draggingPath: string[] | null): void {
        const el = opts.canvasRef.value;
        cachedSlots = el ? collectSlots(el, draggingPath) : [];
    }

    /** 拖动中更新跟手浮层 + 吸附命中（palette / block 源共用）。 */
    function updateDrag(clientX: number, clientY: number, grabDx: number, grabDy: number, kind: string): void {
        ghost.value = { kind, x: clientX - grabDx, y: clientY - grabDy };
        // 过滤不允许落入 tweenBlock body 的插槽（isTweenBodySlotAllowed 语义校验）。
        const allowedSlots = cachedSlots.filter((s) => isTweenBodySlotAllowed(kind, s));
        activeSlot.value = findDropTarget(allowedSlots, clientX, clientY, DROP_THRESHOLD);
    }

    // ----- palette 源：拖出新块 -----

    /**
     * 从 palette 开始拖出一个新动作块。lock no-op。
     * @param kind 动作 kind（ACTION_DEFS 键，含 if）。
     * @param e    palette 项上的 pointerdown 事件。
     */
    function startPaletteDrag(kind: string, e: PointerEvent): void {
        if (project.isLocked) return;
        const target = (e.currentTarget as HTMLElement) ?? opts.canvasRef.value;
        if (!target) return;
        if (!tryCapture(target, e.pointerId)) return;
        e.preventDefault();
        // 标记拖拽中：拖出期间 server 回声不替换整树，capture（在 palette 项上）不被打断。
        edit.setDragging(true);
        // 抓取点：浮层左上角对齐指针（palette 块无固定尺寸，简单取 0 偏移让块跟在指针右下）。
        const grabDx = 8;
        const grabDy = 8;
        state = { mode: 'palette', kind, grabDx, grabDy };
        measure(null);
        updateDrag(e.clientX, e.clientY, grabDx, grabDy, kind);
        attachMoveListeners();
    }

    // ----- 画布源：拖已有块 -----

    /**
     * 从画布开始拖动一个已有动作块。lock no-op。同时把该块所属规则选中（进入编辑）。
     * @param stackRuleId 该块所属规则 id。
     * @param blockPath   该块 path 字符串（如 {@code 'actions/2/then/1'}）。
     * @param e           块根上的 pointerdown 事件。
     */
    function startBlockDrag(stackRuleId: string, blockPath: string, e: PointerEvent): void {
        if (project.isLocked) return;
        const path = parsePath(blockPath);
        if (path.length < 2) return;
        const target = (e.currentTarget as HTMLElement) ?? (e.target as HTMLElement);
        if (!target) return;
        // 触控板敏感度修复：pointerdown <b>不</b>立即启动拖动——先 arm pending（记起点 + 几何 +
        // 启动回调），指针位移超 DRAG_THRESHOLD 才真正拖（onCross）。pending 期间不 capture / 不
        // preventDefault / 不 selectRule / 不 setDragging → 阈值内松手时字段 click/focus 正常发生。
        //
        // 抓取点几何（grabDx/grabDy）<b>在此处 pointerdown 当下用 target rect 算好</b>并闭包进 onCross：
        // ① 此刻 e.client* 可读（事件对象未来会被回收）；② onCross 时若先 selectRule 再测 rect 会拿到
        //    重渲后的新 DOM 矩形（不准），故几何必须 pointerdown 时定。
        const rect = target.getBoundingClientRect();
        const grabDx = e.clientX - rect.left;
        const grabDy = e.clientY - rect.top;
        const pointerId = e.pointerId;
        // pointerdown 当下不能 preventDefault（否则阈值内松手时这是个 tap，preventDefault 会吞掉
        // 该元素后续的 click/focus）。但要 stopPropagation 防 pointerdown 冒泡到外层（嵌套块只让最深
        // 那块 arm；BlockStack 的 @pointerdown.stop 另有把关）——stopPropagation 不影响 click 派发。
        e.stopPropagation();

        armDrag(e.clientX, e.clientY, (crossX, crossY) => {
            // ===== 超阈值：真正启动拖块 =====
            // 不变性：<b>先 tryCapture 再 selectRule</b>。pending 期没 selectRule，故
            // target 仍是 pointerdown 时刻的原始 DOM——先在它上面抓住 pointer，再 selectRule（其重渲
            // 会 v-for 重建被拖块 DOM，但 capture 已落在旧 DOM 上不被顶替）。
            if (!tryCapture(target, pointerId)) return;
            // 标记拖拽中：server 回声不再替换整树（capture 已握住的 DOM 不被 v-for 重建打断）。
            edit.setDragging(true);
            // 选中该规则（拖动隐含编辑它；若不是当前规则则切过去）。dragging 守卫已防其回声替换整树。
            if (edit.selectedRuleId !== stackRuleId) edit.selectRule(stackRuleId);
            const kind = blockKindAt(stackRuleId, path);
            state = { mode: 'block', ruleId: stackRuleId, path, kind, grabDx, grabDy };
            measure(path);
            // 拖动作积木时亮出删除区（仅 block 源；松手在区内 = 删该积木）。
            deleteZoneActive.value = true;
            // 用阈值跨越点坐标初始化浮层（首个正式 onPointerMove 会立刻校正到指针处）。
            updateDrag(crossX, crossY, grabDx, grabDy, kind);
            attachMoveListeners();
        });
    }

    /** 取删除区当前 client 矩形（无 viewport → null）。move/up 命中判定共用。 */
    function deleteZoneRect(): { left: number; top: number; width: number; height: number } | null {
        const el = opts.canvasRef.value;
        if (!el) return null;
        return computeDeleteZoneRect(el.getBoundingClientRect());
    }

    /**
     * 更新删除区悬停态（仅 block 拖动时有意义）。返回是否命中删除区。
     * 命中时 composable 让吸附指示线让位（在 onPointerMove 里清 activeSlot）—— 用户意图明确是"删"。
     */
    function updateDeleteHot(clientX: number, clientY: number): boolean {
        if (!deleteZoneActive.value) {
            deleteZoneHot.value = false;
            return false;
        }
        const rect = deleteZoneRect();
        const hot = rect !== null && pointInRect(rect, clientX, clientY);
        deleteZoneHot.value = hot;
        return hot;
    }

    /** 取规则 working copy 里 path 处块的 kind（用于浮层显示）；取不到 → 'log' 兜底。 */
    function blockKindAt(ruleId: string, path: string[]): string {
        const wc = edit.workingCopy;
        if (!wc || edit.selectedRuleId !== ruleId) return 'log';
        // 浮层只需 kind，简单从 path 末段定位（深拷在 store 内，这里只读类型字段）。
        // 直接用 blockTree.getAt 需引入额外依赖；这里轻量复用 store 已选规则的 actions。
        let seq: unknown = wc.actions;
        for (let i = 0; i < path.length; i += 2) {
            const idx = Number(path[i + 1]);
            if (!Array.isArray(seq) || !Number.isInteger(idx) || idx < 0 || idx >= seq.length) return 'log';
            const node = seq[idx] as { type?: string; then?: unknown; else?: unknown; body?: unknown };
            if (i + 2 >= path.length) return node?.type ?? 'log';
            const branch = path[i + 2];
            // if → then/else；repeat → body（浮层 kind 标签深入循环体也正确）。
            seq = branch === 'then' ? node?.then
                : branch === 'else' ? node?.else
                : branch === 'body' ? node?.body
                : null;
        }
        return 'log';
    }

    // ----- 移堆：拖帽子改坐标 -----

    /**
     * 从帽子开始拖动整堆（改 blockLayout 坐标）。lock no-op。同时选中该规则。
     * @param ruleId 规则 id。
     * @param e      帽子上的 pointerdown 事件。
     */
    function startStackDrag(ruleId: string, e: PointerEvent): void {
        if (project.isLocked) return;
        const target = (e.currentTarget as HTMLElement) ?? (e.target as HTMLElement);
        if (!target) return;
        // 触控板敏感度修复：与 startBlockDrag 同——pointerdown 只 arm pending，超阈值才真启动。
        // 堆现坐标（x0/y0）在 pointerdown 当下从旧 DOM 的 style.left/top 读好闭包进 onCross：pending
        // 期不 selectRule 不重渲，旧 DOM 一直在；该坐标不因 selectRule 变化，pointerdown 时读即准。
        const downX = e.clientX;
        const downY = e.clientY;
        const pointerId = e.pointerId;
        // 起始 world 坐标 = 当前帽子所在堆的左上；以指针 world 坐标差量驱动移堆，初值取堆现坐标。
        const startWorld = opts.screenToWorld(downX, downY);
        const stackEl = target.closest('[data-rule-id]') as HTMLElement | null;
        let x0 = startWorld.x;
        let y0 = startWorld.y;
        if (stackEl) {
            // 堆左上的 world 坐标 = parse 自 style.left/top（BlockCanvas 设的 world px）。
            const left = parseFloat(stackEl.style.left);
            const top = parseFloat(stackEl.style.top);
            if (Number.isFinite(left)) x0 = left;
            if (Number.isFinite(top)) y0 = top;
        }
        // pointerdown 当下不 preventDefault（阈值内松手是 tap，留给帽子 click/selectRule）；stopPropagation
        // 防冒泡（BlockStack 容器另有 @pointerdown.stop），不影响 click 派发。
        e.stopPropagation();

        armDrag(downX, downY, () => {
            // ===== 超阈值：真正启动移堆 =====
            // 不变性：先 tryCapture（旧 DOM 帽子上）再 selectRule。pending 期未 select，
            // target 仍是原始帽子 DOM，capture 落在它上不被重渲顶替。
            if (!tryCapture(target, pointerId)) return;
            edit.setDragging(true);
            if (edit.selectedRuleId !== ruleId) edit.selectRule(ruleId);
            // 移堆用指针屏幕位移差量驱动（onPointerMove 里 startX/startY → 当前指针换算 world delta）。
            state = { mode: 'stack', ruleId, startX: downX, startY: downY, x0, y0 };
            stackDragPos.value = { ruleId, x: x0, y: y0 };
            attachMoveListeners();
        });
    }

    // ----- pointer move / up（拖拽期间挂在 window，见 attachMoveListeners）-----

    function onPointerMove(e: PointerEvent): void {
        if (state.mode === 'none') return;
        if (state.mode === 'stack') {
            // 指针屏幕位移 → world 位移（除以 zoom）。用 screenToWorld 差量保证缩放正确。
            const from = opts.screenToWorld(state.startX, state.startY);
            const to = opts.screenToWorld(e.clientX, e.clientY);
            const nx = state.x0 + (to.x - from.x);
            const ny = state.y0 + (to.y - from.y);
            stackDragPos.value = { ruleId: state.ruleId, x: nx, y: ny };
            return;
        }
        if (state.mode === 'palette') {
            updateDrag(e.clientX, e.clientY, state.grabDx, state.grabDy, state.kind);
            return;
        }
        // block：先更新浮层 + 吸附命中，再判删除区——命中删除区则清吸附指示线（意图是删，不是重排）。
        updateDrag(e.clientX, e.clientY, state.grabDx, state.grabDy, state.kind);
        if (updateDeleteHot(e.clientX, e.clientY)) {
            activeSlot.value = null;
        }
    }

    function onPointerUp(e: PointerEvent): void {
        const s = state;
        if (s.mode === 'none') {
            tryRelease();
            return;
        }
        if (s.mode === 'stack') {
            const pos = stackDragPos.value;
            // 提交坐标（lock 守卫在 setStackPos 内；abort 前先取值）。
            abortDrag();
            if (pos) edit.setStackPos(pos.ruleId, pos.x, pos.y);
            return;
        }

        // block 源松手在删除区内 → 删除该积木（短路掉吸附落树）。palette 源不走删除
        //（拖出新块没必要拖回去删，松手在区内只是丢弃 = abort）。
        if (s.mode === 'block' && updateDeleteHot(e.clientX, e.clientY)) {
            const fromPath = s.path;
            abortDrag();
            const wc = edit.workingCopy;
            if (!wc) return;
            edit.removeAction(pathToString(fromPath));
            return;
        }

        // palette / block：按命中插槽落树。同 updateDrag，过滤 tweenBlock body 约束。
        const dragKind = s.mode === 'palette' ? s.kind : s.kind;
        const allowedSlotsUp = cachedSlots.filter((slot) => isTweenBodySlotAllowed(dragKind, slot));
        const hit = findDropTarget(allowedSlotsUp, e.clientX, e.clientY, DROP_THRESHOLD);
        if (hit === null) {
            // 无命中：palette 源丢弃（不创建）；画布源还原（不拆堆）。两者都只是 abort。
            abortDrag();
            return;
        }

        if (s.mode === 'palette') {
            const wc = edit.workingCopy;
            abortDrag();
            if (!wc) return;
            const newActions = insertAt(wc.actions, hit.parentPath, hit.index, makeDefaultAction(s.kind));
            edit.setActions(newActions);
            return;
        }

        // block 源：moveNode（toParentPath / toIndex 按"移动前渲染树测量" = collectSlots 测量时机，
        // 与 blockTree.moveNode "toIndex 按移动前渲染树测量"契约对齐）。
        const wc = edit.workingCopy;
        const fromPath = s.path;
        abortDrag();
        if (!wc) return;
        const newActions = moveNode(wc.actions, fromPath, hit.parentPath, hit.index);
        if (newActions === wc.actions) return; // 无变化（落回原位）→ 不提交
        edit.setActions(newActions);
    }

    function onWindowBlur(): void { abortDrag(); }
    function onVisibilityChange(): void {
        if (document.visibilityState === 'hidden') abortDrag();
    }

    window.addEventListener('blur', onWindowBlur);
    document.addEventListener('visibilitychange', onVisibilityChange);
    onScopeDispose(() => {
        window.removeEventListener('blur', onWindowBlur);
        document.removeEventListener('visibilitychange', onVisibilityChange);
        abortDrag();
    });

    /** 是否正在拖块 / palette（移堆不算——移堆有独立视觉）。供画布切 cursor / 阻断 pan。 */
    const isDraggingBlock = computed(() => ghost.value !== null);
    /** 是否正在移堆。 */
    const isDraggingStack = computed(() => stackDragPos.value !== null);

    return {
        // 状态（模板消费）
        ghost,
        activeSlot,
        stackDragPos,
        deleteZoneActive,
        deleteZoneHot,
        isDraggingBlock,
        isDraggingStack,
        // 三种拖拽源启动（move / up 由各 start* 自挂 window 监听，模板无需绑）
        startPaletteDrag,
        startBlockDrag,
        startStackDrag,
        // 兜底：viewport 可选绑 @pointercancel（window pointercancel 已覆盖，这是额外保险）
        onPointerCancel: abortDrag,
    };
}
