/**
 * 0.7.0-P4-A：把 {@link ScriptAction}[] 当成可寻址树的纯逻辑层。
 *
 * <p><b>path 形态与后端 trace blockId 同构</b>（权威：{@code ScriptRunner.java}）：</p>
 * <ul>
 *   <li>顶层动作序列前缀 {@code actions/}，故第 i 个顶层动作 path = {@code actions/i}；</li>
 *   <li>{@code if} 块的两个嵌套子序列前缀为 {@code <ifPath>/then/} 与 {@code <ifPath>/else/}，
 *       故 {@code if}（位于 {@code actions/2}）的 then 第 1 项 path = {@code actions/2/then/1}；</li>
 *   <li>触发器（帽子）path = {@code trigger}（不在动作树内，仅 parsePath 识别）。</li>
 * </ul>
 *
 * <p>path 在内部用字符串数组表示（{@code ['actions','2','then','1']}）；段交替为
 * <b>序列键</b>（字面量 {@code actions} / {@code then} / {@code else}）与 <b>下标</b>（数字字符串）。
 * 指向具体动作的 path 以 {@code [seqKey, idx]} 结尾；指向容器序列的 <b>parentPath</b>
 * 以 {@code seqKey} 结尾（如顶层 {@code ['actions']}，或 if 槽 {@code ['actions','2','then']}）。</p>
 *
 * <p>所有变换函数 <b>immutable</b>：返回新树，不修改入参（结构共享未改动子树）。
 * 这是后端 trace 高亮（H 阶段）定位积木的前端契约——path 必须与后端逐字符同构。</p>
 */

import type { ScriptAction } from '@/types/protocol';

/** if 块两个嵌套子序列的序列键（与 wire 字段名 / 后端 trace 前缀一致）。 */
export const IF_BRANCH_KEYS = ['then', 'else'] as const;
export type IfBranchKey = (typeof IF_BRANCH_KEYS)[number];

/**
 * 解析后端 trace blockId 字符串为 path 段数组。
 *
 * - {@code "actions/2/then/1"} → {@code ['actions','2','then','1']}
 * - {@code "trigger"} → {@code ['trigger']}
 * - 空串 / 仅斜杠 → 过滤空段（{@code "actions//1"} → {@code ['actions','1']}，鲁棒兜底）。
 */
export function parsePath(blockId: string): string[] {
    if (!blockId) return [];
    return blockId.split('/').filter((seg) => seg.length > 0);
}

/** path 段数组 → blockId 字符串（parsePath 的逆，用于 walk / 测量对齐）。 */
export function pathToString(path: string[]): string {
    return path.join('/');
}

/** {@code if} 类型守卫——窄化到带 then/else 的分支。 */
function isIf(
    node: ScriptAction,
): node is Extract<ScriptAction, { type: 'if' }> {
    return node.type === 'if';
}

/**
 * 取 path 指向的动作。path 必须以 {@code [seqKey, idx]} 结尾（指向具体动作）。
 * 越界 / path 形态非法 / 中途遇非 if 却继续下钻 → {@code null}。
 */
export function getAt(actions: ScriptAction[], path: string[]): ScriptAction | null {
    if (path.length < 2 || path.length % 2 !== 0) return null;
    let seq: ScriptAction[] | null = resolveSequenceForLeaf(actions, path);
    if (seq === null) return null;
    const idx = toIndex(path[path.length - 1]);
    if (idx === null || idx < 0 || idx >= seq.length) return null;
    return seq[idx];
}

/**
 * 在 parentPath 指向的容器序列的 index 处插入 node（immutable）。
 *
 * @param parentPath 容器序列路径，以序列键结尾（顶层 {@code ['actions']} / if 槽
 *                   {@code [...,'then']} / {@code [...,'else']}）。
 * @param index      插入下标，clamp 到 {@code [0, seq.length]}。
 * @returns 新的顶层 actions 树；parentPath 非法 → 原样返回（不抛）。
 */
export function insertAt(
    actions: ScriptAction[],
    parentPath: string[],
    index: number,
    node: ScriptAction,
): ScriptAction[] {
    return transformSequence(actions, parentPath, (seq) => {
        const clamped = clampIndex(index, seq.length);
        const next = seq.slice();
        next.splice(clamped, 0, node);
        return next;
    });
}

/**
 * 删除 path 指向的动作（immutable）。path 以 {@code [seqKey, idx]} 结尾。
 * path 非法 / 越界 → 原样返回。
 */
export function removeAt(actions: ScriptAction[], path: string[]): ScriptAction[] {
    if (path.length < 2 || path.length % 2 !== 0) return actions;
    const parentPath = path.slice(0, path.length - 1);
    const idx = toIndex(path[path.length - 1]);
    if (idx === null) return actions;
    return transformSequence(actions, parentPath, (seq) => {
        if (idx < 0 || idx >= seq.length) return seq;
        const next = seq.slice();
        next.splice(idx, 1);
        return next;
    });
}

/**
 * 把 fromPath 的动作移动到 toParentPath 的 toIndex 处（immutable，先 remove 后 insert）。
 *
 * <p><b>下标 / 路径校正</b>：toParentPath 与 toIndex 由调用方按<b>移动前</b>渲染树测量
 * （DropTarget 插槽即如此）。先 remove 源节点会让"与源同容器、且位于源下标之后"的一切
 * 引用左移一位，故须在 insert 前对目标坐标补偿：</p>
 * <ul>
 *   <li><b>目标路径段</b>：若 fromParentPath 是 toParentPath 的前缀（跨容器、目标在源容器
 *       更深处），且该容器内进入分支的下标 &gt; fromIndex → 对该路径段 -1；</li>
 *   <li><b>插入下标</b>：若 toParentPath 与 fromParentPath 同序列且 {@code toIndex &gt;
 *       fromIndex} → 插入下标 -1（"移到第 N 位"语义稳定）。</li>
 * </ul>
 * <p>两者互斥：路径相等时只校正插入下标；严格前缀延伸时只校正路径段。同容器前移 / 完全
 * 无关容器不校正。</p>
 *
 * @returns 新树；任一端 path 非法或源节点取不到 → 原样返回。
 */
export function moveNode(
    actions: ScriptAction[],
    fromPath: string[],
    toParentPath: string[],
    toIndex: number,
): ScriptAction[] {
    const node = getAt(actions, fromPath);
    if (node === null) return actions;

    const fromParentPath = fromPath.slice(0, fromPath.length - 1);
    const fromIndex = parseLeafIndex(fromPath);
    if (fromIndex === null) return actions;

    const removed = removeAt(actions, fromPath);

    // 校正目标路径段：跨容器且目标路径经过源容器、进入下标在源下标之后 → -1。
    const adjustedParent = adjustParentPathAfterRemoval(
        toParentPath,
        fromParentPath,
        fromIndex,
    );

    // 校正插入下标：同容器内向后移。
    let insertIndex = toIndex;
    if (samePath(fromParentPath, toParentPath) && toIndex > fromIndex) {
        insertIndex = toIndex - 1;
    }

    return insertAt(removed, adjustedParent, insertIndex, node);
}

/**
 * 在 {@code removedParent}/{@code removedIndex} 处删除一个节点后，补偿 {@code targetParent}
 * 路径里受影响的下标段。仅当 removedParent 是 targetParent 的<b>严格前缀</b>（即目标在被删
 * 容器的更深处）、且 targetParent 进入该容器的下一段下标 &gt; removedIndex 时，对那一段 -1。
 * 否则原样返回（含两者相等的情形——相等由插入下标侧单独校正）。
 */
function adjustParentPathAfterRemoval(
    targetParent: string[],
    removedParent: string[],
    removedIndex: number,
): string[] {
    // 必须严格更深：targetParent 长于 removedParent 且以其为前缀。
    if (targetParent.length <= removedParent.length) return targetParent;
    for (let i = 0; i < removedParent.length; i++) {
        if (targetParent[i] !== removedParent[i]) return targetParent;
    }
    // removedParent 以序列键结尾，其后第一段即"在该容器内的进入下标"。
    const branchIndexPos = removedParent.length;
    const branchIndex = toIndex(targetParent[branchIndexPos]);
    if (branchIndex === null || branchIndex <= removedIndex) return targetParent;
    const next = targetParent.slice();
    next[branchIndexPos] = String(branchIndex - 1);
    return next;
}

/**
 * 前序遍历整棵树，对每个动作回调 {@code visitor(node, path)}。
 * path 是字符串路径（如 {@code "actions/2/then/1"}，与后端 trace 同构）。
 * if 块先回调自身，再依次下钻 then、else 子序列。
 */
export function walk(
    actions: ScriptAction[],
    visitor: (node: ScriptAction, path: string) => void,
): void {
    walkSeq(actions, 'actions', visitor);
}

function walkSeq(
    seq: ScriptAction[],
    prefix: string,
    visitor: (node: ScriptAction, path: string) => void,
): void {
    for (let i = 0; i < seq.length; i++) {
        const node = seq[i];
        const path = `${prefix}/${i}`;
        visitor(node, path);
        if (isIf(node)) {
            walkSeq(node.then, `${path}/then`, visitor);
            walkSeq(node.else, `${path}/else`, visitor);
        }
    }
}

/**
 * 镜像后端 {@code ScriptRuleValidator.countBlocks}：每个动作计 1；
 * {@code if} 自身计 1（含在通用 +1）再加 then + else 递归。
 * 用于前端 MAX_TOTAL_BLOCKS 预校验。
 */
export function countBlocks(actions: ScriptAction[]): number {
    let count = 0;
    for (const node of actions) {
        count++;
        if (isIf(node)) {
            count += countBlocks(node.then) + countBlocks(node.else);
        }
    }
    return count;
}

/**
 * 最深 {@code if} 嵌套层数（镜像后端 ifDepth 语义：顶层 if = 1，if-in-if = 2 ...）。
 * 无 if → 0。用于前端 MAX_IF_DEPTH 预校验。
 */
export function ifDepth(actions: ScriptAction[]): number {
    let max = 0;
    for (const node of actions) {
        if (isIf(node)) {
            const childDepth = Math.max(ifDepth(node.then), ifDepth(node.else));
            max = Math.max(max, 1 + childDepth);
        }
    }
    return max;
}

// ---------- 内部辅助 ----------

/** 把单段字符串解析为非负整数下标；非法返回 null。 */
function toIndex(seg: string | undefined): number | null {
    if (seg === undefined) return null;
    if (!/^\d+$/.test(seg)) return null;
    const n = Number(seg);
    return Number.isSafeInteger(n) ? n : null;
}

/** 取叶子 path（以 [seqKey, idx] 结尾）的末段下标。 */
function parseLeafIndex(path: string[]): number | null {
    if (path.length < 2) return null;
    return toIndex(path[path.length - 1]);
}

function clampIndex(index: number, length: number): number {
    if (!Number.isFinite(index)) return length;
    const i = Math.trunc(index);
    if (i < 0) return 0;
    if (i > length) return length;
    return i;
}

function samePath(a: string[], b: string[]): boolean {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
        if (a[i] !== b[i]) return false;
    }
    return true;
}

/**
 * 解析叶子 path（{@code [seqKey, idx, seqKey, idx]}）所在的<b>容器序列</b>引用
 * （只下钻到末段 idx 的前一层），用于 getAt。中途遇非 if / 越界 → null。
 */
function resolveSequenceForLeaf(
    actions: ScriptAction[],
    path: string[],
): ScriptAction[] | null {
    const parentPath = path.slice(0, path.length - 1); // 去掉末段 idx
    return resolveSequence(actions, parentPath);
}

/**
 * 解析 parentPath（以序列键结尾）指向的序列引用（只读，不复制）。
 *
 * <p>形态校验：第 0 段必须是 {@code 'actions'}；之后每个 {@code (idx, seqKey)} 二元组
 * 表示"进入 actions[idx] 这个 if 的 seqKey 分支"。任一步不是 if / 越界 / 键非
 * then|else → null。</p>
 */
function resolveSequence(actions: ScriptAction[], parentPath: string[]): ScriptAction[] | null {
    if (parentPath.length === 0 || parentPath[0] !== 'actions') return null;
    // parentPath 形如 ['actions', idx, branchKey, idx, branchKey, ...]，长度必为奇数。
    if (parentPath.length % 2 === 0) return null;

    let seq: ScriptAction[] = actions;
    let cursor = 1;
    while (cursor < parentPath.length) {
        const idx = toIndex(parentPath[cursor]);
        const branchKey = parentPath[cursor + 1];
        if (idx === null || idx < 0 || idx >= seq.length) return null;
        const node = seq[idx];
        if (!isIf(node)) return null;
        if (branchKey === 'then') {
            seq = node.then;
        } else if (branchKey === 'else') {
            seq = node.else;
        } else {
            return null;
        }
        cursor += 2;
    }
    return seq;
}

/**
 * immutable 地用 {@code fn} 重写 parentPath 指向的序列，回写整棵树。
 * parentPath 非法 → 原样返回 actions。沿途路径结构共享 + 仅克隆改动链上的 if 节点。
 */
function transformSequence(
    actions: ScriptAction[],
    parentPath: string[],
    fn: (seq: ScriptAction[]) => ScriptAction[],
): ScriptAction[] {
    if (parentPath.length === 0 || parentPath[0] !== 'actions') return actions;
    if (parentPath.length % 2 === 0) return actions;

    // 顶层序列直接重写。
    if (parentPath.length === 1) {
        return fn(actions);
    }

    // 进入 actions[idx] 的 if，递归改其某分支后克隆回写。
    const idx = toIndex(parentPath[1]);
    const branchKey = parentPath[2];
    if (idx === null || idx < 0 || idx >= actions.length) return actions;
    const node = actions[idx];
    if (!isIf(node)) return actions;
    if (branchKey !== 'then' && branchKey !== 'else') return actions;

    const innerParent = ['actions', ...parentPath.slice(3)];
    const branchSeq = branchKey === 'then' ? node.then : node.else;
    const newBranch = transformSequence(branchSeq, innerParent, fn);
    if (newBranch === branchSeq) return actions; // 无改动，结构共享

    const newIf: ScriptAction =
        branchKey === 'then'
            ? { ...node, then: newBranch }
            : { ...node, else: newBranch };
    const next = actions.slice();
    next[idx] = newIf;
    return next;
}
