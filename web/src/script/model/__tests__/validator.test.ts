/**
 * 0.7.0-P5-H：前端 validator 镜像单测。
 *
 * <p><b>核心目标 = 与后端 {@code ScriptRuleValidator.java} 逐字段对齐</b>：常量边界值（wait
 * 50/5000、near 1/32、timer 1/86400、块数 50/51、if 深 4/5、音量 / 音调区间）+ 每类错误触发 +
 * 合法规则返空 + 白名单集合一致。后端是 fail-fast，前端收集全部，但<b>每条判定的临界点必须一致</b>。</p>
 */
import { describe, it, expect } from 'vitest';
import {
    validateRule,
    NAME_MAX,
    MAX_TOTAL_BLOCKS,
    MAX_IF_DEPTH,
    WAIT_MIN,
    WAIT_MAX,
    TIMER_MIN,
    TIMER_MAX,
    NEAR_MIN,
    NEAR_MAX,
    SET_VALUE_MAX,
    SOUND_ID_MAX,
    COMMAND_PARAM_MAX,
    LOG_MESSAGE_MAX,
    CONDITION_MAX,
    VOLUME_MIN,
    VOLUME_MAX,
    PITCH_MIN,
    PITCH_MAX,
    ELEMENT_PROPERTIES,
    TIMELINE_OPS,
    SOUND_SCOPES,
} from '../validator';
import type { ScriptRule, ScriptAction, ScriptTrigger } from '@/types/protocol';

function rule(over: Partial<ScriptRule> = {}): ScriptRule {
    return {
        id: 'sr-1',
        wallId: 'w-abc',
        enabled: true,
        name: '规则',
        trigger: { type: 'wallReady' },
        actions: [{ type: 'log', message: 'hi' }],
        blockLayout: '{}',
        ...over,
    };
}

// ---------- 常量与后端逐一对照（关键临界值）----------

describe('validator 常量与后端 ScriptRuleValidator.java 一致', () => {
    it('数值上下界', () => {
        expect(NAME_MAX).toBe(64);
        expect(MAX_TOTAL_BLOCKS).toBe(50);
        expect(MAX_IF_DEPTH).toBe(4);
        expect(WAIT_MIN).toBe(50);
        expect(WAIT_MAX).toBe(5000);
        expect(TIMER_MIN).toBe(1);
        expect(TIMER_MAX).toBe(86400);
        expect(NEAR_MIN).toBe(1);
        expect(NEAR_MAX).toBe(32);
        expect(SET_VALUE_MAX).toBe(4096);
        expect(SOUND_ID_MAX).toBe(128);
        expect(COMMAND_PARAM_MAX).toBe(256);
        expect(LOG_MESSAGE_MAX).toBe(256);
        expect(CONDITION_MAX).toBe(512);
        expect(VOLUME_MIN).toBe(0.0);
        expect(VOLUME_MAX).toBe(2.0);
        expect(PITCH_MIN).toBe(0.5);
        expect(PITCH_MAX).toBe(2.0);
    });
    it('白名单集合', () => {
        expect([...ELEMENT_PROPERTIES].sort()).toEqual(
            ['fill', 'h', 'opacity', 'rotation', 'text', 'w', 'x', 'y'],
        );
        expect([...TIMELINE_OPS].sort()).toEqual(['pause', 'play', 'seek']);
        expect([...SOUND_SCOPES].sort()).toEqual(['all', 'near']);
    });
});

// ---------- 合法规则 ----------

describe('validateRule — 合法规则返空', () => {
    it('最简合法规则（wallReady + log）', () => {
        expect(validateRule(rule())).toEqual([]);
    });
    it('含各类合法动作 + 嵌套 if 的规则', () => {
        const actions: ScriptAction[] = [
            { type: 'setVariable', fullName: 'user/x', value: '' }, // 空串合法
            { type: 'incrementVariable', fullName: 'user/n', delta: 1.5 },
            { type: 'setElementProperty', elementId: 'el-1', property: 'opacity', value: '0.5' },
            { type: 'playTimeline', timelineId: 'tl-1', op: 'play' },
            { type: 'playTimeline', timelineId: 'tl-1', op: 'seek', seekMs: 0 },
            { type: 'playSound', soundId: 'ui.button.click', volume: 0, pitch: 0.5, scope: 'near' },
            { type: 'wait', ms: 50 },
            { type: 'runCommand', templateId: 'tpl-1', params: { a: 'x' } },
            { type: 'log', message: '' }, // 空串合法
            { type: 'if', condition: 'true', then: [{ type: 'log', message: 'y' }], else: [] },
        ];
        expect(validateRule(rule({ actions }))).toEqual([]);
    });
});

// ---------- 规则级错误 ----------

describe('validateRule — 规则级错误', () => {
    it('名称空 → 报错', () => {
        const errs = validateRule(rule({ name: '  ' }));
        expect(errs.some((e) => e.message === '规则名称不能为空')).toBe(true);
    });
    it('名称 64 合法 / 65 超长', () => {
        expect(validateRule(rule({ name: 'a'.repeat(NAME_MAX) }))).toEqual([]);
        const errs = validateRule(rule({ name: 'a'.repeat(NAME_MAX + 1) }));
        expect(errs.some((e) => e.message.includes('规则名称超长'))).toBe(true);
    });
    it('动作列表空 → 报错', () => {
        const errs = validateRule(rule({ actions: [] }));
        expect(errs.some((e) => e.message === '动作列表不能为空')).toBe(true);
    });
    it('blockLayout 超长 → 报错', () => {
        const errs = validateRule(rule({ blockLayout: 'x'.repeat(65536 + 1) }));
        expect(errs.some((e) => e.message.includes('积木布局数据超长'))).toBe(true);
    });
    it('blockLayout 65536 合法', () => {
        expect(validateRule(rule({ blockLayout: 'x'.repeat(65536) }))).toEqual([]);
    });
});

// ---------- 触发器字段 ----------

describe('validateRule — 触发器字段（blockId=trigger）', () => {
    it('variableChange 空变量名 → 报错（blockId=trigger）', () => {
        const errs = validateRule(rule({ trigger: { type: 'variableChange', fullName: '' } }));
        const e = errs.find((x) => x.message.includes('变量变化触发器'));
        expect(e?.blockId).toBe('trigger');
    });
    it('timer 边界 1 / 86400 合法，0 / 86401 报错', () => {
        const mk = (s: number): ScriptTrigger => ({ type: 'timer', intervalSeconds: s });
        expect(validateRule(rule({ trigger: mk(TIMER_MIN) }))).toEqual([]);
        expect(validateRule(rule({ trigger: mk(TIMER_MAX) }))).toEqual([]);
        expect(validateRule(rule({ trigger: mk(0) })).length).toBeGreaterThan(0);
        expect(validateRule(rule({ trigger: mk(TIMER_MAX + 1) })).length).toBeGreaterThan(0);
    });
    it('playerNear 边界 1 / 32 合法，0 / 33 报错', () => {
        const mk = (n: number): ScriptTrigger => ({ type: 'playerNear', rangeBlocks: n });
        expect(validateRule(rule({ trigger: mk(NEAR_MIN) }))).toEqual([]);
        expect(validateRule(rule({ trigger: mk(NEAR_MAX) }))).toEqual([]);
        expect(validateRule(rule({ trigger: mk(0) })).length).toBeGreaterThan(0);
        expect(validateRule(rule({ trigger: mk(NEAR_MAX + 1) })).length).toBeGreaterThan(0);
    });
});

// ---------- 动作字段 ----------

describe('validateRule — 动作字段（blockId=actions/i）', () => {
    it('setVariable 空名 + value=null', () => {
        const a = { type: 'setVariable', fullName: '', value: null } as unknown as ScriptAction;
        const errs = validateRule(rule({ actions: [a] }));
        expect(errs.some((e) => e.message === '设置变量的变量名不能为空' && e.blockId === 'actions/0')).toBe(true);
        expect(errs.some((e) => e.message.includes('不能为 null'))).toBe(true);
    });
    it('setVariable value 4096 合法 / 4097 超长', () => {
        const ok: ScriptAction = { type: 'setVariable', fullName: 'user/x', value: 'a'.repeat(SET_VALUE_MAX) };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const bad: ScriptAction = { type: 'setVariable', fullName: 'user/x', value: 'a'.repeat(SET_VALUE_MAX + 1) };
        expect(validateRule(rule({ actions: [bad] })).some((e) => e.message.includes('值超长'))).toBe(true);
    });
    it('incrementVariable delta 非有限 → 报错', () => {
        const a = { type: 'incrementVariable', fullName: 'user/n', delta: Infinity } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [a] })).some((e) => e.message.includes('有限数值'))).toBe(true);
        const nan = { type: 'incrementVariable', fullName: 'user/n', delta: NaN } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nan] })).some((e) => e.message.includes('有限数值'))).toBe(true);
    });
    it('setElementProperty 非法属性 + 空值', () => {
        const a = { type: 'setElementProperty', elementId: 'el', property: 'bogus', value: '' } as unknown as ScriptAction;
        const errs = validateRule(rule({ actions: [a] }));
        expect(errs.some((e) => e.message.includes('元素属性不在允许范围'))).toBe(true);
        expect(errs.some((e) => e.message === '设置元素属性的值不能为空')).toBe(true);
    });
    it('playTimeline seek 缺 seekMs → 报错；seekMs 负数 → 报错', () => {
        const missing: ScriptAction = { type: 'playTimeline', timelineId: 't', op: 'seek' };
        expect(validateRule(rule({ actions: [missing] })).some((e) => e.message.includes('必须提供 seekMs'))).toBe(true);
        const neg: ScriptAction = { type: 'playTimeline', timelineId: 't', op: 'seek', seekMs: -1 };
        expect(validateRule(rule({ actions: [neg] })).some((e) => e.message.includes('不能为负数'))).toBe(true);
    });
    it('playTimeline 非法 op → 报错', () => {
        const a = { type: 'playTimeline', timelineId: 't', op: 'rewind' } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [a] })).some((e) => e.message.includes('时间轴操作不在允许范围'))).toBe(true);
    });
    it('playSound 音量 / 音调边界', () => {
        const mk = (volume: number, pitch: number): ScriptAction =>
            ({ type: 'playSound', soundId: 's', volume, pitch, scope: 'near' });
        // 边界合法
        expect(validateRule(rule({ actions: [mk(VOLUME_MIN, PITCH_MIN)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mk(VOLUME_MAX, PITCH_MAX)] }))).toEqual([]);
        // 越界
        expect(validateRule(rule({ actions: [mk(-0.1, 1)] })).some((e) => e.message.includes('音量'))).toBe(true);
        expect(validateRule(rule({ actions: [mk(2.1, 1)] })).some((e) => e.message.includes('音量'))).toBe(true);
        expect(validateRule(rule({ actions: [mk(1, 0.4)] })).some((e) => e.message.includes('音调'))).toBe(true);
        expect(validateRule(rule({ actions: [mk(1, 2.1)] })).some((e) => e.message.includes('音调'))).toBe(true);
    });
    it('playSound NaN 音量被拒（finite 纪律）', () => {
        const a = { type: 'playSound', soundId: 's', volume: NaN, pitch: 1, scope: 'near' } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [a] })).some((e) => e.message.includes('音量'))).toBe(true);
    });
    it('playSound 音量 / 音调文案保留 .0 尾缀（与后端 Double.toString 一致）', () => {
        const a = { type: 'playSound', soundId: 's', volume: 3, pitch: 1, scope: 'near' } as unknown as ScriptAction;
        const errs = validateRule(rule({ actions: [a] }));
        expect(errs.some((e) => e.message === '音量需在 0.0..2.0 之间')).toBe(true);
    });
    it('playSound soundId 128 合法 / 129 超长', () => {
        const ok: ScriptAction = { type: 'playSound', soundId: 'a'.repeat(SOUND_ID_MAX), volume: 1, pitch: 1, scope: 'near' };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const bad: ScriptAction = { type: 'playSound', soundId: 'a'.repeat(SOUND_ID_MAX + 1), volume: 1, pitch: 1, scope: 'near' };
        expect(validateRule(rule({ actions: [bad] })).some((e) => e.message.includes('声音 ID'))).toBe(true);
    });
    it('wait 边界 50 / 5000 合法，49 / 5001 报错', () => {
        const mk = (ms: number): ScriptAction => ({ type: 'wait', ms });
        expect(validateRule(rule({ actions: [mk(WAIT_MIN)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mk(WAIT_MAX)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mk(WAIT_MIN - 1)] })).some((e) => e.message.includes('等待时长'))).toBe(true);
        expect(validateRule(rule({ actions: [mk(WAIT_MAX + 1)] })).some((e) => e.message.includes('等待时长'))).toBe(true);
    });
    it('runCommand 空模板 + 参数超长', () => {
        const a: ScriptAction = { type: 'runCommand', templateId: '', params: { k: 'x'.repeat(COMMAND_PARAM_MAX + 1) } };
        const errs = validateRule(rule({ actions: [a] }));
        expect(errs.some((e) => e.message === '执行命令缺少模板 ID')).toBe(true);
        expect(errs.some((e) => e.message.includes("命令参数 'k' 超长"))).toBe(true);
    });
    it('log message=null + 超长', () => {
        const nul = { type: 'log', message: null } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nul] })).some((e) => e.message === '日志内容不能为 null')).toBe(true);
        const long: ScriptAction = { type: 'log', message: 'a'.repeat(LOG_MESSAGE_MAX + 1) };
        expect(validateRule(rule({ actions: [long] })).some((e) => e.message.includes('日志内容超长'))).toBe(true);
    });
    it('log message 256 合法', () => {
        expect(validateRule(rule({ actions: [{ type: 'log', message: 'a'.repeat(LOG_MESSAGE_MAX) }] }))).toEqual([]);
    });
});

// ---------- if 嵌套深度 / 条件 ----------

describe('validateRule — if 嵌套深度 + 条件', () => {
    /** 构造 depth 层嵌套 if（最内层放一个 log），condition 都填 'true'。 */
    function nestedIf(depth: number): ScriptAction {
        let inner: ScriptAction[] = [{ type: 'log', message: 'x' }];
        for (let i = 0; i < depth; i++) {
            inner = [{ type: 'if', condition: 'true', then: inner, else: [] }];
        }
        return inner[0];
    }
    it('if 深 4 合法 / 5 报错（depth=5）', () => {
        expect(validateRule(rule({ actions: [nestedIf(MAX_IF_DEPTH)] }))).toEqual([]);
        const errs = validateRule(rule({ actions: [nestedIf(MAX_IF_DEPTH + 1)] }));
        expect(errs.some((e) => e.message.includes('if 嵌套超过'))).toBe(true);
    });
    it('if 空条件 → 报错', () => {
        const a: ScriptAction = { type: 'if', condition: '  ', then: [], else: [] };
        expect(validateRule(rule({ actions: [a] })).some((e) => e.message === 'if 条件不能为空')).toBe(true);
    });
    it('if 条件 512 合法 / 513 超长', () => {
        const ok: ScriptAction = { type: 'if', condition: 'a'.repeat(CONDITION_MAX), then: [], else: [] };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const bad: ScriptAction = { type: 'if', condition: 'a'.repeat(CONDITION_MAX + 1), then: [], else: [] };
        expect(validateRule(rule({ actions: [bad] })).some((e) => e.message.includes('if 条件超长'))).toBe(true);
    });
    it('嵌套 if 内动作错误带正确 blockId 路径', () => {
        const a: ScriptAction = {
            type: 'if',
            condition: 'true',
            then: [{ type: 'wait', ms: 1 }], // 越界 wait
            else: [],
        };
        const errs = validateRule(rule({ actions: [a] }));
        const waitErr = errs.find((e) => e.message.includes('等待时长'));
        expect(waitErr?.blockId).toBe('actions/0/then/0');
    });
});

// ---------- 积木总数 ----------

describe('validateRule — 积木总数上限', () => {
    it('50 块合法 / 51 块报错', () => {
        const make = (n: number): ScriptAction[] =>
            Array.from({ length: n }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        expect(validateRule(rule({ actions: make(MAX_TOTAL_BLOCKS) }))).toEqual([]);
        const errs = validateRule(rule({ actions: make(MAX_TOTAL_BLOCKS + 1) }));
        expect(errs.some((e) => e.message.includes('积木总数') && e.message.includes('超过上限'))).toBe(true);
    });
    it('if 自身 + 分支计入总数', () => {
        // 1 个 if（计 1）+ then 内 49 个 log = 50，合法；再加 1 → 51 超。
        const then50: ScriptAction[] = Array.from({ length: 49 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        const ok: ScriptAction = { type: 'if', condition: 'true', then: then50, else: [] };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]); // 1 + 49 = 50
        const then51: ScriptAction[] = Array.from({ length: 50 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        const over: ScriptAction = { type: 'if', condition: 'true', then: then51, else: [] };
        expect(validateRule(rule({ actions: [over] })).some((e) => e.message.includes('积木总数'))).toBe(true); // 51
    });
});

// ---------- 多错误一次性收集 ----------

describe('validateRule — 收集全部错误（非 fail-fast）', () => {
    it('多处错误一次性全报', () => {
        const errs = validateRule(rule({
            name: '',
            actions: [
                { type: 'wait', ms: 1 },
                { type: 'log', message: 'x' },
            ],
        }));
        // 名称空 + wait 越界 两条都在
        expect(errs.some((e) => e.message === '规则名称不能为空')).toBe(true);
        expect(errs.some((e) => e.message.includes('等待时长'))).toBe(true);
        expect(errs.length).toBeGreaterThanOrEqual(2);
    });
});
