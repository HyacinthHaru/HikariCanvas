/**
 * 0.7.0-P4-A：blockTree 树操作单测。
 *
 * <p>纯函数（无 Vue / DOM），vitest node 跑。重点：path 与后端 trace blockId 逐字符同构
 * （权威 ScriptRunner.java：顶层 {@code actions/i}、if 分支 {@code <ifPath>/then|else/i}）。
 * countBlocks / ifDepth 镜像 ScriptRuleValidator。</p>
 */
import { describe, expect, it } from 'vitest';
import type { ScriptAction } from '@/types/protocol';
import {
    parsePath,
    pathToString,
    getAt,
    insertAt,
    removeAt,
    moveNode,
    walk,
    countBlocks,
    ifDepth,
} from '../blockTree';

// ---------- 测试夹具 ----------

const log = (msg: string): ScriptAction => ({ type: 'log', message: msg });
const wait = (ms: number): ScriptAction => ({ type: 'wait', ms });

function ifBlock(
    condition: string,
    thenA: ScriptAction[],
    elseA: ScriptAction[] = [],
): ScriptAction {
    return { type: 'if', condition, then: thenA, else: elseA };
}

/**
 * 构造嵌套树：
 *   actions/0  log A
 *   actions/1  if c1
 *     actions/1/then/0  log T0
 *     actions/1/then/1  if c2
 *       actions/1/then/1/then/0  log deep
 *     actions/1/else/0  log E0
 *   actions/2  wait 100
 */
function nested(): ScriptAction[] {
    return [
        log('A'),
        ifBlock(
            'c1',
            [log('T0'), ifBlock('c2', [log('deep')], [])],
            [log('E0')],
        ),
        wait(100),
    ];
}

describe('blockTree.parsePath / pathToString', () => {
    it('解析顶层动作 path', () => {
        expect(parsePath('actions/0')).toEqual(['actions', '0']);
        expect(parsePath('actions/2')).toEqual(['actions', '2']);
    });

    it('解析 if 分支嵌套 path（与后端 trace 同构）', () => {
        expect(parsePath('actions/2/then/1')).toEqual(['actions', '2', 'then', '1']);
        expect(parsePath('actions/1/else/0')).toEqual(['actions', '1', 'else', '0']);
        expect(parsePath('actions/1/then/1/then/0')).toEqual([
            'actions', '1', 'then', '1', 'then', '0',
        ]);
    });

    it('解析 trigger path', () => {
        expect(parsePath('trigger')).toEqual(['trigger']);
    });

    it('空串 / 多余斜杠鲁棒', () => {
        expect(parsePath('')).toEqual([]);
        expect(parsePath('actions//1')).toEqual(['actions', '1']);
    });

    it('pathToString 是 parsePath 的逆', () => {
        expect(pathToString(['actions', '2', 'then', '1'])).toBe('actions/2/then/1');
        expect(pathToString(parsePath('actions/1/else/0'))).toBe('actions/1/else/0');
    });
});

describe('blockTree.getAt', () => {
    it('取顶层动作', () => {
        const a = nested();
        expect(getAt(a, ['actions', '0'])).toMatchObject({ type: 'log', message: 'A' });
        expect(getAt(a, ['actions', '2'])).toMatchObject({ type: 'wait', ms: 100 });
        expect(getAt(a, ['actions', '1'])).toMatchObject({ type: 'if', condition: 'c1' });
    });

    it('取 if then / else 子项', () => {
        const a = nested();
        expect(getAt(a, ['actions', '1', 'then', '0'])).toMatchObject({ message: 'T0' });
        expect(getAt(a, ['actions', '1', 'else', '0'])).toMatchObject({ message: 'E0' });
    });

    it('取深层嵌套子项', () => {
        const a = nested();
        expect(getAt(a, ['actions', '1', 'then', '1', 'then', '0'])).toMatchObject({
            message: 'deep',
        });
    });

    it('越界 / 非法 path → null', () => {
        const a = nested();
        expect(getAt(a, ['actions', '9'])).toBeNull(); // 顶层越界
        expect(getAt(a, ['actions', '0', 'then', '0'])).toBeNull(); // log 不是 if
        expect(getAt(a, ['actions', '1', 'then', '9'])).toBeNull(); // 分支越界
        expect(getAt(a, ['actions'])).toBeNull(); // 指向容器非动作
        expect(getAt(a, [])).toBeNull();
        expect(getAt(a, ['actions', '1', 'bogus', '0'])).toBeNull(); // 非 then/else
    });
});

describe('blockTree.insertAt', () => {
    it('顶层插入（immutable，不改原树）', () => {
        const a = nested();
        const next = insertAt(a, ['actions'], 1, log('NEW'));
        expect(next).not.toBe(a);
        expect(a.length).toBe(3); // 原树未变
        expect(next.length).toBe(4);
        expect(getAt(next, ['actions', '1'])).toMatchObject({ message: 'NEW' });
        expect(getAt(next, ['actions', '2'])).toMatchObject({ type: 'if' });
    });

    it('插入 if then 槽', () => {
        const a = nested();
        const next = insertAt(a, ['actions', '1', 'then'], 0, log('INS'));
        expect(getAt(next, ['actions', '1', 'then', '0'])).toMatchObject({ message: 'INS' });
        expect(getAt(next, ['actions', '1', 'then', '1'])).toMatchObject({ message: 'T0' });
        // 原 if 节点未被原地改动
        const origIf = getAt(a, ['actions', '1']) as Extract<ScriptAction, { type: 'if' }>;
        expect(origIf.then.length).toBe(2);
    });

    it('插入空 else 槽', () => {
        const a: ScriptAction[] = [ifBlock('c', [log('t')], [])];
        const next = insertAt(a, ['actions', '0', 'else'], 0, log('E'));
        expect(getAt(next, ['actions', '0', 'else', '0'])).toMatchObject({ message: 'E' });
    });

    it('index 越界 clamp 到序列末尾', () => {
        const a = nested();
        const next = insertAt(a, ['actions'], 999, log('TAIL'));
        expect(getAt(next, ['actions', '3'])).toMatchObject({ message: 'TAIL' });
    });

    it('非法 parentPath → 原样返回', () => {
        const a = nested();
        expect(insertAt(a, ['bogus'], 0, log('X'))).toBe(a);
        expect(insertAt(a, ['actions', '0', 'then'], 0, log('X'))).toBe(a); // log 不是 if
    });
});

describe('blockTree.removeAt', () => {
    it('删顶层动作（immutable）', () => {
        const a = nested();
        const next = removeAt(a, ['actions', '0']);
        expect(next).not.toBe(a);
        expect(a.length).toBe(3);
        expect(next.length).toBe(2);
        expect(getAt(next, ['actions', '0'])).toMatchObject({ type: 'if' });
    });

    it('删 if 分支子项', () => {
        const a = nested();
        const next = removeAt(a, ['actions', '1', 'then', '0']);
        expect(getAt(next, ['actions', '1', 'then', '0'])).toMatchObject({ type: 'if' });
        const origIf = getAt(a, ['actions', '1']) as Extract<ScriptAction, { type: 'if' }>;
        expect(origIf.then.length).toBe(2); // 原树不变
    });

    it('越界 / 非法 path → 原样返回', () => {
        const a = nested();
        expect(removeAt(a, ['actions', '9'])).toBe(a);
        expect(removeAt(a, ['actions'])).toBe(a);
    });
});

describe('blockTree.moveNode', () => {
    it('同容器内向后移：index 校正（toIndex 按移动前坐标系）', () => {
        // [A, B, C]，toIndex=2 指原树中 B|C 之间的间隙。把 A(0) 拖到该间隙：
        // remove A → [B,C]，校正后插入下标 1 → [B, A, C]（A 落在原 B|C 间隙，几何正确）。
        const a: ScriptAction[] = [log('A'), log('B'), log('C')];
        const next = moveNode(a, ['actions', '0'], ['actions'], 2);
        expect(next.map((n) => (n as { message: string }).message)).toEqual(['B', 'A', 'C']);
    });

    it('同容器内向后移到末尾间隙（toIndex = 原长度）', () => {
        // [A, B, C]，toIndex=3 指原树末尾间隙（C 之后）。把 A(0) 拖到末尾 → [B, C, A]。
        const a: ScriptAction[] = [log('A'), log('B'), log('C')];
        const next = moveNode(a, ['actions', '0'], ['actions'], 3);
        expect(next.map((n) => (n as { message: string }).message)).toEqual(['B', 'C', 'A']);
    });

    it('同容器内向前移：不校正', () => {
        // [A, B, C] 把 C(2) 移到 index 0 → [C, A, B]
        const a: ScriptAction[] = [log('A'), log('B'), log('C')];
        const next = moveNode(a, ['actions', '2'], ['actions'], 0);
        expect(next.map((n) => (n as { message: string }).message)).toEqual(['C', 'A', 'B']);
    });

    it('跨容器移动：顶层 → if then 槽', () => {
        const a: ScriptAction[] = [log('A'), ifBlock('c', [log('T')], [])];
        const next = moveNode(a, ['actions', '0'], ['actions', '1', 'then'], 0);
        // A 被移走，顶层只剩 if（现在在 index 0）
        expect(next.length).toBe(1);
        expect(getAt(next, ['actions', '0'])).toMatchObject({ type: 'if' });
        expect(getAt(next, ['actions', '0', 'then', '0'])).toMatchObject({ message: 'A' });
        expect(getAt(next, ['actions', '0', 'then', '1'])).toMatchObject({ message: 'T' });
    });

    it('跨容器移动：if then 槽 → 顶层', () => {
        const a: ScriptAction[] = [ifBlock('c', [log('T0'), log('T1')], [])];
        const next = moveNode(a, ['actions', '0', 'then', '0'], ['actions'], 1);
        expect(getAt(next, ['actions', '1'])).toMatchObject({ message: 'T0' });
        const ifNode = getAt(next, ['actions', '0']) as Extract<ScriptAction, { type: 'if' }>;
        expect(ifNode.then.map((n) => (n as { message: string }).message)).toEqual(['T1']);
    });

    it('源 path 非法 → 原样返回', () => {
        const a = nested();
        expect(moveNode(a, ['actions', '9'], ['actions'], 0)).toBe(a);
    });
});

describe('blockTree.walk', () => {
    it('前序遍历产出 path 与后端 trace 同构', () => {
        const a = nested();
        const visited: string[] = [];
        walk(a, (_node, path) => visited.push(path));
        expect(visited).toEqual([
            'actions/0',
            'actions/1',
            'actions/1/then/0',
            'actions/1/then/1',
            'actions/1/then/1/then/0',
            'actions/1/else/0',
            'actions/2',
        ]);
    });

    it('walk 产出的 path 可被 getAt 取回原节点', () => {
        const a = nested();
        walk(a, (node, path) => {
            expect(getAt(a, parsePath(path))).toBe(node);
        });
    });
});

describe('blockTree.countBlocks（镜像后端 ScriptRuleValidator）', () => {
    it('扁平序列 = 长度', () => {
        expect(countBlocks([log('a'), log('b'), wait(50)])).toBe(3);
    });

    it('if 自身计 1 + then + else 递归', () => {
        // if(1) + then[log,log](2) + else[log](1) = 4
        const a: ScriptAction[] = [ifBlock('c', [log('t0'), log('t1')], [log('e0')])];
        expect(countBlocks(a)).toBe(4);
    });

    it('嵌套树整体计数', () => {
        // nested(): A(1) + if(1) + [T0(1) + if(1) + deep(1)] + [E0(1)] + wait(1) = 7
        expect(countBlocks(nested())).toBe(7);
    });

    it('空序列 = 0', () => {
        expect(countBlocks([])).toBe(0);
    });
});

describe('blockTree.ifDepth', () => {
    it('无 if = 0', () => {
        expect(ifDepth([log('a'), wait(50)])).toBe(0);
    });

    it('单层 if = 1', () => {
        expect(ifDepth([ifBlock('c', [log('t')], [])])).toBe(1);
    });

    it('嵌套树最深层数', () => {
        // nested() 最深：actions/1 (if) → then/1 (if) = 2 层
        expect(ifDepth(nested())).toBe(2);
    });

    it('else 分支里的更深嵌套也算', () => {
        const a: ScriptAction[] = [
            ifBlock('c1', [], [ifBlock('c2', [ifBlock('c3', [log('x')], [])], [])]),
        ];
        expect(ifDepth(a)).toBe(3);
    });
});
