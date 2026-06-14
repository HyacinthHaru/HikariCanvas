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
 * 0.7.1-P2：所有「带嵌套子序列」的容器块的序列键全集（if 的 then/else + repeat 的 body）。
 * path 段在 (idx, seqKey) 二元组里出现的 seqKey 必属此集；与后端 ScriptRunner 展开 blockId
 * 前缀逐字符同构（repeat body 不带 round）。新增带子序列的块（如未来 forEach）在此扩集 +
 * {@link getChildSeq} / {@link withChildSeq} 加分支即可，导航 / 变换 / 遍历自动覆盖。
 */
export const NESTED_SEQ_KEYS = ['then', 'else', 'body'] as const;
export type NestedSeqKey = (typeof NESTED_SEQ_KEYS)[number];

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

/**
 * 「带 {@code then/else} 子序列」容器块类型守卫——窄化到 if（条件分支）与 randomBranch（随机分支，
 * 0.7.3）。两者的 then/else 序列键完全同名，路径 blockId 形态也完全一致（`/then/i` / `/else/i`）。
 * getChildSeq / withChildSeq / BlockNode C 形渲染共用此一条分支，不为 randomBranch 单独再写分支。
 */
function isIf(
    node: ScriptAction,
): node is Extract<ScriptAction, { type: 'if' }> | Extract<ScriptAction, { type: 'randomBranch' }> {
    return node.type === 'if' || node.type === 'randomBranch';
}

/**
 * 「带 {@code body} 子序列」容器块类型守卫——窄化到 repeat（有界循环，0.7.1-P2）、
 * repeatUntil（动态循环，0.7.2-P3）与 tweenBlock（补间包裹，tween-P1）。
 * 三者的 body 序列键同名（{@code body}），故下钻 / 回写共用一条分支（getChildSeq /
 * withChildSeq）。新增带 {@code body} 的块在此并入判别即可，导航 / 变换 / 遍历自动覆盖。
 */
function isBodyContainer(
    node: ScriptAction,
): node is Extract<ScriptAction, { type: 'repeat' } | { type: 'repeatUntil' } | { type: 'tweenBlock' }> {
    return node.type === 'repeat' || node.type === 'repeatUntil' || node.type === 'tweenBlock';
}

/**
 * 0.7.1-P2：取容器块 {@code node} 在序列键 {@code key} 下的子序列引用（只读，不复制）。
 * if → then/else；repeat / repeatUntil → body。非容器块 / 键不属该块 → {@code null}。这是 blockTree
 * 所有"下钻嵌套序列"路径的<b>唯一</b>分支点（resolveSequence / transformSequence 共用）。
 */
function getChildSeq(node: ScriptAction, key: string): ScriptAction[] | null {
    if (isIf(node)) {
        if (key === 'then') return node.then;
        if (key === 'else') return node.else;
        return null;
    }
    if (isBodyContainer(node)) {
        if (key === 'body') return node.body;
        return null;
    }
    return null;
}

/**
 * 0.7.1-P2：immutable 重写容器块 {@code node} 在序列键 {@code key} 下的子序列为
 * {@code newSeq}，返回克隆后的新节点。键不属该块 → 原样返回 node（防御）。
 * 与 {@link getChildSeq} 对偶，是 transformSequence 唯一回写点。
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
 * immutable 地把 leafPath 指向的动作用 {@code {...old, ...patch}} 合并替换，回写整棵树。
 *
 * <p>leafPath 以 {@code [seqKey, idx]} 结尾（指向具体动作）。沿 {@link transformSequence}
 * 下钻（已正确支持 if 的 then/else 与 repeat 的 body），定位到末端节点所在的父序列后在该
 * 下标处 merge patch。<b>type 保护</b>：合并后 type 以原节点为准（patch 即便带了 type 也不
 * 改判别符，防破坏多态联合）。</p>
 *
 * <p>path 非法 / 越界 / 中途遇非容器块下钻 → 原样返回 actions（无变化也返回原引用，调用方
 * 可用 {@code === } 判跳过）。这是参数表单（F 阶段）改字段的<b>唯一</b>树写入点，取代
 * scriptEdit 旧有的平行手写递归。</p>
 *
 * @param actions  顶层动作树。
 * @param leafPath 指向被改动作的 path 段数组（如 {@code ['actions','0','body','0']}）。
 * @param patch    要合并进该动作的部分字段。
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
 * 容器块先回调自身，再按 {@link NESTED_SEQ_KEYS} 顺序下钻各子序列
 * （if → then、else；repeat / repeatUntil → body）。
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
        // 按固定键序下钻所有子序列（if then/else、repeat body）；非容器块 getChildSeq 返 null。
        for (const key of NESTED_SEQ_KEYS) {
            const child = getChildSeq(node, key);
            if (child !== null) walkSeq(child, `${path}/${key}`, visitor);
        }
    }
}

/**
 * 镜像后端 {@code ScriptRuleValidator.countBlocks}：每个动作计 1；容器块自身计 1
 * （含在通用 +1）再加所有子序列递归（if then/else + 0.7.1-P2 repeat body）。
 * <b>repeat 不乘 count</b>——硬限 50 是积木树节点数，不是展开后执行动作数。
 * 用于前端 MAX_TOTAL_BLOCKS 预校验。
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
 * 最深 {@code if} 嵌套层数（镜像后端 ifDepth 语义：顶层 if = 1，if-in-if = 2 ...）。
 * 无 if → 0。用于前端 MAX_IF_DEPTH 预校验。
 *
 * <p>0.7.1-P2：{@code repeat} 自身<b>不增</b> if 深度（与后端 validateActions 一致——repeat
 * 递归 body 时 ifDepth 不变），但仍下钻 body 找其中的 if。故对每个容器块的所有子序列取
 * 子深度，只在节点是 if 时 +1。</p>
 */
export function ifDepth(actions: ScriptAction[]): number {
    let max = 0;
    for (const node of actions) {
        // 子序列里的最深 if 层数（if 与 repeat 都下钻；非容器块无子序列贡献 0）。
        let childMax = 0;
        for (const key of NESTED_SEQ_KEYS) {
            const child = getChildSeq(node, key);
            if (child !== null) childMax = Math.max(childMax, ifDepth(child));
        }
        // 仅 if 节点本身让深度 +1；repeat 把 body 的深度原样上传（不 +1）。
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
 * 表示"进入 actions[idx] 这个容器块的 seqKey 子序列"（if → then/else；0.7.1-P2
 * repeat → body）。任一步该块没有此子序列（非容器 / 键不匹配）/ 越界 → null。</p>
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
        const child = getChildSeq(seq[idx], branchKey);
        if (child === null) return null; // 非容器块 / 键不属该块（如 log 下钻 body、repeat 下钻 then）
        seq = child;
        cursor += 2;
    }
    return seq;
}

/**
 * immutable 地用 {@code fn} 重写 parentPath 指向的序列，回写整棵树。
 * parentPath 非法 → 原样返回 actions。沿途路径结构共享 + 仅克隆改动链上的容器块
 * （if / 0.7.1-P2 repeat）。
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

    // 进入 actions[idx] 的容器块，递归改其某子序列后克隆回写。
    const idx = toIndex(parentPath[1]);
    const branchKey = parentPath[2];
    if (idx === null || idx < 0 || idx >= actions.length) return actions;
    const node = actions[idx];
    const branchSeq = getChildSeq(node, branchKey);
    if (branchSeq === null) return actions; // 非容器块 / 键不属该块

    const innerParent = ['actions', ...parentPath.slice(3)];
    const newBranch = transformSequence(branchSeq, innerParent, fn);
    if (newBranch === branchSeq) return actions; // 无改动，结构共享

    const next = actions.slice();
    next[idx] = withChildSeq(node, branchKey, newBranch);
    return next;
}
