/**
 * 把 `ScriptAction[]` 当成可寻址树的纯逻辑层。
 *
 * path 形态与后端 trace `blockId` 同构（权威：`ScriptRunner.java`）：
 * - 顶层动作序列前缀 `actions/`，故第 i 个顶层动作 path = `actions/i`；
 * - `if` / `randomBranch` 块的两个嵌套子序列前缀为 `<path>/then/` 与 `<path>/else/`；
 * - `repeat` / `repeatUntil` / `tweenBlock` 块的嵌套子序列前缀为 `<path>/body/`；
 * - 触发器（帽子）path = `trigger`（不在动作树内，仅 `parsePath` 识别）。
 *
 * path 在内部用字符串数组表示（`['actions', '2', 'then', '1']`）；段交替为序列键
 * （`actions` / `then` / `else` / `body`）与下标（数字字符串）。
 * 指向具体动作的 path 以 `[seqKey, idx]` 结尾；指向容器序列的 `parentPath`
 * 以 `seqKey` 结尾（如顶层 `['actions']`，或子槽 `['actions', '2', 'then']`）。
 *
 * 所有变换函数不可变（immutable）：返回新树，不修改入参，结构共享未改动子树。
 */

import type { ScriptAction } from '@/types/protocol';

/** 双臂分支块两个嵌套子序列的序列键（与 wire 字段名 / 后端 trace 前缀一致）。 */
export const IF_BRANCH_KEYS = ['then', 'else'] as const;
export type IfBranchKey = (typeof IF_BRANCH_KEYS)[number];

/**
 * 所有带嵌套子序列的容器块的序列键全集（`then` / `else` / `body`）。
 * 与后端 `ScriptRunner` 展开 `blockId` 前缀逐字符同构。
 */
export const NESTED_SEQ_KEYS = ['then', 'else', 'body'] as const;
export type NestedSeqKey = (typeof NESTED_SEQ_KEYS)[number];

/**
 * 解析后端 trace `blockId` 字符串为 path 段数组。
 *
 * - `"actions/2/then/1"` -> `['actions', '2', 'then', '1']`
 * - `"trigger"` -> `['trigger']`
 * - 空串 / 仅斜杠 -> 过滤空段（`"actions//1"` -> `['actions', '1']`）。
 */
export function parsePath(blockId: string): string[] {
    if (!blockId) return [];
    return blockId.split('/').filter((seg) => seg.length > 0);
}

/** path 段数组 -> `blockId` 字符串（`parsePath` 的逆，用于 walk / 测量对齐）。 */
export function pathToString(path: string[]): string {
    return path.join('/');
}

/**
 * 带 `then/else` 子序列的容器块类型守卫（`if` 与 `randomBranch`）。
 */
function isIf(
    node: ScriptAction,
): node is Extract<ScriptAction, { type: 'if' }> | Extract<ScriptAction, { type: 'randomBranch' }> {
    return node.type === 'if' || node.type === 'randomBranch';
}

/**
 * 带 `body` 子序列的容器块类型守卫（`repeat`、`repeatUntil` 与 `tweenBlock`）。
 */
function isBodyContainer(
    node: ScriptAction,
): node is Extract<ScriptAction, { type: 'repeat' } | { type: 'repeatUntil' } | { type: 'tweenBlock' }> {
    return node.type === 'repeat' || node.type === 'repeatUntil' || node.type === 'tweenBlock';
}

/**
 * 取容器块 `node` 在序列键 `key` 下的子序列引用（只读，不复制）。
 * 非容器块 / 键不属该块 / 子序列缺失时返回 `null`。
 */
function getChildSeq(node: ScriptAction, key: string): ScriptAction[] | null {
    if (isIf(node)) {
        if (key === 'then') return Array.isArray(node.then) ? node.then : null;
        if (key === 'else') return Array.isArray(node.else) ? node.else : null;
        return null;
    }
    if (isBodyContainer(node)) {
        if (key === 'body') return Array.isArray(node.body) ? node.body : null;
        return null;
    }
    return null;
}

/**
 * 不可变地重写容器块 `node` 在序列键 `key` 下的子序列为 `newSeq`，返回克隆后的新节点。
 * 键不属该块时原样返回 `node`。
 */
function withChildSeq(node: ScriptAction, key: string, newSeq: ScriptAction[]): ScriptAction {
    if (isIf(node)) {
        if (key === 'then') return { ...node, then: newSeq };
        if (key === 'else') return { ...node, else: newSeq };
        return node;
    }
    if (isBodyContainer(node)) {
        if (key === 'body') return { ...node, body: newSeq };
        return node;
    }
    return node;
}

/**
 * 取 path 指向的动作。path 必须以 `[seqKey, idx]` 结尾。
 * 越界 / path 形态非法 / 中途遇非容器块继续下钻时返回 `null`。
 */
export function getAt(actions: ScriptAction[], path: string[]): ScriptAction | null {
    if (path.length < 2 || path.length % 2 !== 0) return null;
    const seq: ScriptAction[] | null = resolveSequenceForLeaf(actions, path);
    if (seq === null) return null;
    const idx = toIndex(path[path.length - 1]);
    if (idx === null || idx < 0 || idx >= seq.length) return null;
    return seq[idx];
}

/**
 * 在 `parentPath` 指向的容器序列的 `index` 处插入 `node`（不可变）。
 * `parentPath` 非法时原样返回。
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
 * 删除 path 指向的动作（不可变）。path 以 `[seqKey, idx]` 结尾。
 * path 非法 / 越界时原样返回。
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
 * 不可变地把 `leafPath` 指向的动作用 `{...old, ...patch}` 合并替换，回写整棵树。
 * 合并后 `type` 以原节点为准（防破坏多态联合）。
 * path 非法 / 越界 / 中途遇非容器块下钻时原样返回 `actions`。
 */
export function replaceAt(
    actions: ScriptAction[],
    leafPath: string[],
    patch: Partial<ScriptAction>,
): ScriptAction[] {
    if (leafPath.length < 2 || leafPath.length % 2 !== 0) return actions;
    const parentPath = leafPath.slice(0, leafPath.length - 1);
    const idx = toIndex(leafPath[leafPath.length - 1]);
    if (idx === null) return actions;
    return transformSequence(actions, parentPath, (seq) => {
        if (idx < 0 || idx >= seq.length) return seq;
        const old = seq[idx];
        // 保留 type：patch 不应改判别符，即便带了也以 old.type 兜底防破坏多态。
        const merged = { ...old, ...patch, type: old.type } as ScriptAction;
        const next = seq.slice();
        next[idx] = merged;
        return next;
    });
}

/**
 * 把 `fromPath` 的动作移动到 `toParentPath` 的 `toIndex` 处（不可变，先 remove 后 insert）。
 *
 * `toParentPath` 与 `toIndex` 由调用方按移动前渲染树测量；先 remove 源节点后会对同序列后续下标
 * 或严格前缀延伸路径做 -1 补偿。落回原位时返回原引用；目标容器路径在本树中不存在时抛错，避免丢失子树。
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

    // 校正插入下标：同容器内向后移。
    const sameSeq = samePath(fromParentPath, toParentPath);
    let insertIndex = toIndex;
    if (sameSeq && toIndex > fromIndex) {
        insertIndex = toIndex - 1;
    }

    // 落回原位（同一序列、补偿后下标没变）= 空操作，返回原引用。
    if (sameSeq && insertIndex === fromIndex) return actions;

    const removed = removeAt(actions, fromPath);

    // 校正目标路径段：跨容器且目标路径经过源容器、进入下标在源下标之后 -> -1。
    const adjustedParent = adjustParentPathAfterRemoval(
        toParentPath,
        fromParentPath,
        fromIndex,
    );

    const inserted = insertAt(removed, adjustedParent, insertIndex, node);
    if (inserted === removed) {
        throw new Error(
            `moveNode: 目标容器路径无效 "${pathToString(adjustedParent)}"，移动已取消`,
        );
    }
    return inserted;
}

/**
 * 在 `removedParent`/`removedIndex` 处删除一个节点后，补偿 `targetParent` 路径里受影响的下标段。
 * 仅当 `removedParent` 是 `targetParent` 的严格前缀且进入该容器的下一段下标 > `removedIndex` 时减 1。
 */
function adjustParentPathAfterRemoval(
    targetParent: string[],
    removedParent: string[],
    removedIndex: number,
): string[] {
    if (targetParent.length <= removedParent.length) return targetParent;
    for (let i = 0; i < removedParent.length; i++) {
        if (targetParent[i] !== removedParent[i]) return targetParent;
    }
    const branchIndexPos = removedParent.length;
    const branchIndex = toIndex(targetParent[branchIndexPos]);
    if (branchIndex === null || branchIndex <= removedIndex) return targetParent;
    const next = targetParent.slice();
    next[branchIndexPos] = String(branchIndex - 1);
    return next;
}

/**
 * 前序遍历整棵树，对每个动作回调 `visitor(node, path)`。
 * 容器块先回调自身，再按 `NESTED_SEQ_KEYS` 顺序下钻各子序列。
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
        for (const key of NESTED_SEQ_KEYS) {
            const child = getChildSeq(node, key);
            if (child !== null) walkSeq(child, `${path}/${key}`, visitor);
        }
    }
}

/**
 * 镜像后端 `ScriptRuleValidator.countBlocks`：每个动作计 1；容器块自身计 1 再加所有子序列递归
 * （`if` / `randomBranch` 的 `then` + `else`，`repeat` / `repeatUntil` / `tweenBlock` 的 `body`）。
 * 循环块不乘轮数——硬限 50 是积木树节点数，不是展开后执行动作数。
 */
export function countBlocks(actions: ScriptAction[]): number {
    let count = 0;
    for (const node of actions) {
        count++;
        for (const key of NESTED_SEQ_KEYS) {
            const child = getChildSeq(node, key);
            if (child !== null) count += countBlocks(child);
        }
    }
    return count;
}

/**
 * 最深条件分支嵌套层数（镜像后端分支深度语义：顶层 `if` / `randomBranch` = 1，嵌套累加；无分支 = 0）。
 * `repeat` / `repeatUntil` / `tweenBlock` 自身不增加条件分支深度，但仍下钻 `body` 统计内部的条件分支。
 */
export function ifDepth(actions: ScriptAction[]): number {
    let max = 0;
    for (const node of actions) {
        let childMax = 0;
        for (const key of NESTED_SEQ_KEYS) {
            const child = getChildSeq(node, key);
            if (child !== null) childMax = Math.max(childMax, ifDepth(child));
        }
        max = Math.max(max, isIf(node) ? 1 + childMax : childMax);
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
 * 解析叶子 path（`[seqKey, idx, ...]`）所在的容器序列引用（只下钻到末段 idx 的前一层）。
 */
function resolveSequenceForLeaf(
    actions: ScriptAction[],
    path: string[],
): ScriptAction[] | null {
    const parentPath = path.slice(0, path.length - 1);
    return resolveSequence(actions, parentPath);
}

/**
 * 解析 `parentPath`（以序列键结尾）指向的序列引用（只读，不复制）。
 * 第 0 段必须是 `'actions'`；之后每个 `(idx, seqKey)` 二元组进入对应容器块的子序列。
 */
function resolveSequence(actions: ScriptAction[], parentPath: string[]): ScriptAction[] | null {
    if (parentPath.length === 0 || parentPath[0] !== 'actions') return null;
    if (parentPath.length % 2 === 0) return null;

    let seq: ScriptAction[] = actions;
    let cursor = 1;
    while (cursor < parentPath.length) {
        const idx = toIndex(parentPath[cursor]);
        const branchKey = parentPath[cursor + 1];
        if (idx === null || idx < 0 || idx >= seq.length) return null;
        const child = getChildSeq(seq[idx], branchKey);
        if (child === null) return null;
        seq = child;
        cursor += 2;
    }
    return seq;
}

/**
 * 不可变地用 `fn` 重写 `parentPath` 指向的序列，回写整棵树。
 * `parentPath` 非法时原样返回 `actions`；沿途路径结构共享，仅克隆改动链上的容器块。
 */
function transformSequence(
    actions: ScriptAction[],
    parentPath: string[],
    fn: (seq: ScriptAction[]) => ScriptAction[],
): ScriptAction[] {
    if (parentPath.length === 0 || parentPath[0] !== 'actions') return actions;
    if (parentPath.length % 2 === 0) return actions;

    if (parentPath.length === 1) {
        return fn(actions);
    }

    const idx = toIndex(parentPath[1]);
    const branchKey = parentPath[2];
    if (idx === null || idx < 0 || idx >= actions.length) return actions;
    const node = actions[idx];
    const branchSeq = getChildSeq(node, branchKey);
    if (branchSeq === null) return actions;

    const innerParent = ['actions', ...parentPath.slice(3)];
    const newBranch = transformSequence(branchSeq, innerParent, fn);
    if (newBranch === branchSeq) return actions;

    const next = actions.slice();
    next[idx] = withChildSeq(node, branchKey, newBranch);
    return next;
}
