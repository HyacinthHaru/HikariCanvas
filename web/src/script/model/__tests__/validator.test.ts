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
    MESSAGE_MAX,
    PATCH_MAX_KEYS,
    KIND_MAX,
    MESSAGE_CHANNELS,
    SCALE_OPS,
    PARTICLES,
    PARTICLE_COUNT_MIN,
    PARTICLE_COUNT_MAX,
    WAITUNTIL_TIMEOUT_MIN,
    WAITUNTIL_TIMEOUT_MAX,
    REPEAT_MIN,
    REPEAT_MAX,
    MESSAGE_TARGETS,
    TWEEN_DURATION_MIN,
    TWEEN_DURATION_MAX,
    PROBABILITY_MIN,
    PROBABILITY_MAX,
    ELEMENT_LAYER_MODES,
    ROUND_MODES,
    TITLE_MAX,
    TITLE_FADE_MAX,
    TITLE_STAY_MAX,
} from '../validator';
import type { ScriptRule, ScriptAction, ScriptTrigger, Easing } from '@/types/protocol';

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
        // 0.7.3 D3：ELEMENT_PROPERTIES 加入 'color'（TextElement.color，setColor 友好积木用）。
        // fill 保留（rect/circle/path/shape/brush/icon 仍用 fill；setElementProperty 通用动作
        // 用 fill；仅 setColor 友好积木对 TextElement 用 color）。
        expect([...ELEMENT_PROPERTIES].sort()).toEqual(
            ['color', 'fill', 'h', 'opacity', 'rotation', 'text', 'w', 'x', 'y'],
        );
        expect([...TIMELINE_OPS].sort()).toEqual(['pause', 'play', 'seek']);
        expect([...SOUND_SCOPES].sort()).toEqual(['all', 'near']);
    });
    it('0.7.1 新动作常量与后端一致', () => {
        expect(MESSAGE_MAX).toBe(256);
        expect(PATCH_MAX_KEYS).toBe(8);
        expect(KIND_MAX).toBe(32);
        expect([...MESSAGE_CHANNELS].sort()).toEqual(['actionbar', 'chat', 'title']);
        expect([...SCALE_OPS].sort()).toEqual(['divide', 'multiply']);
    });
    it('0.7.2-P3 常量与后端一致（target 白名单 + repeatUntil 范围复用 repeat）', () => {
        expect([...MESSAGE_TARGETS].sort()).toEqual(['all', 'trigger']);
        // repeatUntil maxIterations 复用 repeat 的 count 硬上限（1..100，不另加 config）。
        expect([REPEAT_MIN, REPEAT_MAX]).toEqual([1, 100]);
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

    // ---- 0.7.1-P2：3 个新触发器 ----
    it('playerLeaveRange 边界 1 / 32 合法，0 / 33 报错（同 playerNear，文案"玩家离开半径"）', () => {
        const mk = (n: number): ScriptTrigger => ({ type: 'playerLeaveRange', rangeBlocks: n });
        expect(validateRule(rule({ trigger: mk(NEAR_MIN) }))).toEqual([]);
        expect(validateRule(rule({ trigger: mk(NEAR_MAX) }))).toEqual([]);
        const lo = validateRule(rule({ trigger: mk(0) }));
        const hi = validateRule(rule({ trigger: mk(NEAR_MAX + 1) }));
        expect(lo.length).toBeGreaterThan(0);
        expect(hi.length).toBeGreaterThan(0);
        // 文案与后端逐字一致 + blockId=trigger
        const e = lo.find((x) => x.message.includes('玩家离开半径'));
        expect(e?.message).toBe(`玩家离开半径需在 ${NEAR_MIN}..${NEAR_MAX} 方块之间`);
        expect(e?.blockId).toBe('trigger');
    });

    it('rightClickWall / playerQuit 无字段：合法不报错', () => {
        expect(validateRule(rule({ trigger: { type: 'rightClickWall' } }))).toEqual([]);
        expect(validateRule(rule({ trigger: { type: 'playerQuit' } }))).toEqual([]);
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

// ---------- 0.7.1-P1 新动作字段（文案与后端逐字一致）----------

describe('validateRule — 0.7.1 新动作字段', () => {
    it('setElementProperties 合法（kind=moveTo + patch{x,y}）', () => {
        const a: ScriptAction = { type: 'setElementProperties', elementId: 'el-1', patch: { x: '1', y: '2' }, kind: 'moveTo' };
        expect(validateRule(rule({ actions: [a] }))).toEqual([]);
    });
    it('setElementProperties text 空串合法（setText 友好积木 defaultPatch={text:\'\'}）', () => {
        const a: ScriptAction = { type: 'setElementProperties', elementId: 'el-1', patch: { text: '' }, kind: 'setText' };
        expect(validateRule(rule({ actions: [a] }))).toEqual([]);
    });
    it('setElementProperties 空 elementId / 空 patch / 非白名单键 / 空值 / 键超 8 / kind 超长', () => {
        const emptyId: ScriptAction = { type: 'setElementProperties', elementId: '', patch: { x: '1' }, kind: 'moveTo' };
        expect(validateRule(rule({ actions: [emptyId] })).some((e) => e.message === '设置元素属性缺少元素 ID')).toBe(true);
        const emptyPatch: ScriptAction = { type: 'setElementProperties', elementId: 'el', patch: {}, kind: 'moveTo' };
        expect(validateRule(rule({ actions: [emptyPatch] })).some((e) => e.message === '批量设属性的 patch 不能为空')).toBe(true);
        const bogusKey: ScriptAction = { type: 'setElementProperties', elementId: 'el', patch: { bogus: '1' }, kind: 'x' };
        expect(validateRule(rule({ actions: [bogusKey] })).some((e) => e.message === '元素属性不在允许范围：bogus')).toBe(true);
        const emptyVal: ScriptAction = { type: 'setElementProperties', elementId: 'el', patch: { x: '  ' }, kind: 'x' };
        expect(validateRule(rule({ actions: [emptyVal] })).some((e) => e.message === '属性 x 的值不能为空')).toBe(true);
        // 9 个键（全合法名但超 8）→ 报超数 + 仍逐键检查。用合法白名单内键凑 9 不可能（仅 8 个），改用重复构造越界检验路径：
        const tooMany: ScriptAction = {
            type: 'setElementProperties', elementId: 'el',
            patch: { x: '1', y: '1', w: '1', h: '1', rotation: '1', opacity: '1', text: 't', fill: '#fff', extra: 'z' },
            kind: 'x',
        };
        expect(validateRule(rule({ actions: [tooMany] })).some((e) => e.message === `patch 属性数超过 ${PATCH_MAX_KEYS}`)).toBe(true);
        const longKind: ScriptAction = { type: 'setElementProperties', elementId: 'el', patch: { x: '1' }, kind: 'k'.repeat(KIND_MAX + 1) };
        expect(validateRule(rule({ actions: [longKind] })).some((e) => e.message === 'kind 超长')).toBe(true);
    });

    it('nudgeElement 合法 / 空 elementId / dx 非有限', () => {
        const ok: ScriptAction = { type: 'nudgeElement', elementId: 'el', dx: 5, dy: -3 };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const emptyId: ScriptAction = { type: 'nudgeElement', elementId: '', dx: 1, dy: 1 };
        expect(validateRule(rule({ actions: [emptyId] })).some((e) => e.message === '相对移动缺少元素 ID')).toBe(true);
        const nan = { type: 'nudgeElement', elementId: 'el', dx: NaN, dy: 0 } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nan] })).some((e) => e.message === '相对移动的 dx/dy 必须是有限数值')).toBe(true);
    });

    it('sendMessage 合法 / text=null / text 超长 / 非法 channel', () => {
        const ok: ScriptAction = { type: 'sendMessage', text: 'hi', channel: 'actionbar', target: 'trigger' };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        // 空串合法（text != null）。
        expect(validateRule(rule({ actions: [{ type: 'sendMessage', text: '', channel: 'chat', target: 'trigger' }] }))).toEqual([]);
        const nul = { type: 'sendMessage', text: null, channel: 'chat', target: 'trigger' } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nul] })).some((e) => e.message === '发消息内容不能为 null')).toBe(true);
        const long: ScriptAction = { type: 'sendMessage', text: 'a'.repeat(MESSAGE_MAX + 1), channel: 'chat', target: 'trigger' };
        expect(validateRule(rule({ actions: [long] })).some((e) => e.message === `发消息内容超长（最多 ${MESSAGE_MAX}）`)).toBe(true);
        const badCh = { type: 'sendMessage', text: 'hi', channel: 'boss', target: 'trigger' } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [badCh] })).some((e) => e.message === '消息渠道不在允许范围：boss')).toBe(true);
        // text 256 合法。
        expect(validateRule(rule({ actions: [{ type: 'sendMessage', text: 'a'.repeat(MESSAGE_MAX), channel: 'title', target: 'trigger' }] }))).toEqual([]);
    });

    // ---- 0.7.2-P3：sendMessage 加 target（trigger 默认 / all 全服），白名单与后端逐字一致 ----
    it('sendMessage target 白名单：trigger / all 合法，其他报错', () => {
        const trigger: ScriptAction = { type: 'sendMessage', text: 'hi', channel: 'chat', target: 'trigger' };
        expect(validateRule(rule({ actions: [trigger] }))).toEqual([]);
        const all: ScriptAction = { type: 'sendMessage', text: 'hi', channel: 'chat', target: 'all' };
        expect(validateRule(rule({ actions: [all] }))).toEqual([]);
        const bad = { type: 'sendMessage', text: 'hi', channel: 'chat', target: 'nope' } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [bad] }))
            .some((e) => e.message === '发送对象不在允许范围：nope' && e.blockId === 'actions/0')).toBe(true);
        // target 缺失（旧 payload）→ 当 trigger 不报错（向后兼容，与后端 Deserializer 默 trigger 同口径）。
        const legacy = { type: 'sendMessage', text: 'hi', channel: 'chat' } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [legacy] }))).toEqual([]);
    });

    it('setRandomVariable 合法 / 空 fullName / min>max / NaN', () => {
        const ok: ScriptAction = { type: 'setRandomVariable', fullName: 'user/roll', min: 1, max: 6 };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const emptyName: ScriptAction = { type: 'setRandomVariable', fullName: '', min: 1, max: 6 };
        expect(validateRule(rule({ actions: [emptyName] })).some((e) => e.message === '随机数变量名不能为空')).toBe(true);
        const minGtMax: ScriptAction = { type: 'setRandomVariable', fullName: 'user/r', min: 9, max: 1 };
        expect(validateRule(rule({ actions: [minGtMax] })).some((e) => e.message === '随机区间 min 不能大于 max')).toBe(true);
        const nan = { type: 'setRandomVariable', fullName: 'user/r', min: NaN, max: 6 } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nan] })).some((e) => e.message === '随机区间必须是有限数值')).toBe(true);
    });

    it('scaleVariable 合法 / 空 fullName / 非法 op / NaN factor / divide by 0', () => {
        const ok: ScriptAction = { type: 'scaleVariable', fullName: 'user/score', op: 'multiply', factor: 2 };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const emptyName: ScriptAction = { type: 'scaleVariable', fullName: '', op: 'multiply', factor: 2 };
        expect(validateRule(rule({ actions: [emptyName] })).some((e) => e.message === '乘除变量名不能为空')).toBe(true);
        const badOp = { type: 'scaleVariable', fullName: 'user/s', op: 'add', factor: 2 } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [badOp] })).some((e) => e.message === '运算不在允许范围：add')).toBe(true);
        const nan = { type: 'scaleVariable', fullName: 'user/s', op: 'multiply', factor: NaN } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nan] })).some((e) => e.message === '乘除系数必须是有限数值')).toBe(true);
        const divZero: ScriptAction = { type: 'scaleVariable', fullName: 'user/s', op: 'divide', factor: 0 };
        expect(validateRule(rule({ actions: [divZero] })).some((e) => e.message === '除数不能为 0')).toBe(true);
        // divide 非零合法。
        expect(validateRule(rule({ actions: [{ type: 'scaleVariable', fullName: 'user/s', op: 'divide', factor: 2 }] }))).toEqual([]);
    });

    it('playTimelineAwait 合法 / 空 timelineId', () => {
        const ok: ScriptAction = { type: 'playTimelineAwait', timelineId: 'tl-1' };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const empty: ScriptAction = { type: 'playTimelineAwait', timelineId: '' };
        expect(validateRule(rule({ actions: [empty] })).some((e) => e.message === '播时间轴缺少时间轴 ID')).toBe(true);
    });
});

// ---------- 0.7.1-P5：停止 / 粒子 / 等待直到（文案与后端逐字一致）----------

describe('validateRule — 0.7.1-P5 新动作字段', () => {
    it('PARTICLES 白名单 14 个与后端逐字一致', () => {
        expect([...PARTICLES].sort()).toEqual([
            'minecraft:cloud', 'minecraft:crit', 'minecraft:dripping_water', 'minecraft:enchant',
            'minecraft:end_rod', 'minecraft:firework', 'minecraft:flame', 'minecraft:happy_villager',
            'minecraft:heart', 'minecraft:lava', 'minecraft:note', 'minecraft:portal',
            'minecraft:smoke', 'minecraft:totem_of_undying',
        ]);
    });
    it('P5 范围常量与后端一致', () => {
        expect(PARTICLE_COUNT_MIN).toBe(1);
        expect(PARTICLE_COUNT_MAX).toBe(1000);
        expect(WAITUNTIL_TIMEOUT_MIN).toBe(50);
        expect(WAITUNTIL_TIMEOUT_MAX).toBe(60000);
    });

    it('stopScript 永远合法', () => {
        expect(validateRule(rule({ actions: [{ type: 'stopScript' }] }))).toEqual([]);
    });

    it('playParticle 合法（火焰 ×10）/ 非白名单粒子 / count 越界 / offset 非有限', () => {
        const ok: ScriptAction = { type: 'playParticle', particle: 'minecraft:flame', count: 10, offsetX: 0.5, offsetY: 0.5, offsetZ: 0.5 };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        // count 边界 1 / 1000 合法。
        expect(validateRule(rule({ actions: [{ type: 'playParticle', particle: 'minecraft:heart', count: PARTICLE_COUNT_MIN, offsetX: 0, offsetY: 0, offsetZ: 0 }] }))).toEqual([]);
        expect(validateRule(rule({ actions: [{ type: 'playParticle', particle: 'minecraft:heart', count: PARTICLE_COUNT_MAX, offsetX: 0, offsetY: 0, offsetZ: 0 }] }))).toEqual([]);
        // 非白名单粒子。
        const badParticle = { type: 'playParticle', particle: 'minecraft:nope', count: 10, offsetX: 0, offsetY: 0, offsetZ: 0 } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [badParticle] })).some((e) => e.message === '粒子种类不在允许范围: minecraft:nope')).toBe(true);
        // count 越界（0 / 2000）。
        expect(validateRule(rule({ actions: [{ type: 'playParticle', particle: 'minecraft:flame', count: 0, offsetX: 0, offsetY: 0, offsetZ: 0 }] }))
            .some((e) => e.message === `粒子数量需在 ${PARTICLE_COUNT_MIN}..${PARTICLE_COUNT_MAX} 之间`)).toBe(true);
        expect(validateRule(rule({ actions: [{ type: 'playParticle', particle: 'minecraft:flame', count: 2000, offsetX: 0, offsetY: 0, offsetZ: 0 }] }))
            .some((e) => e.message.includes('粒子数量需在'))).toBe(true);
        // offset 非有限。
        const nanOffset = { type: 'playParticle', particle: 'minecraft:flame', count: 10, offsetX: NaN, offsetY: 0, offsetZ: 0 } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nanOffset] })).some((e) => e.message === '粒子偏移必须是有限数值')).toBe(true);
    });

    it('waitUntil 合法 / 空条件 / timeout 越界', () => {
        const ok: ScriptAction = { type: 'waitUntil', condition: 'var("user/x") > 0', timeoutMs: 5000 };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        // timeout 边界 50 / 60000 合法。
        expect(validateRule(rule({ actions: [{ type: 'waitUntil', condition: 'var("user/x")>0', timeoutMs: WAITUNTIL_TIMEOUT_MIN }] }))).toEqual([]);
        expect(validateRule(rule({ actions: [{ type: 'waitUntil', condition: 'var("user/x")>0', timeoutMs: WAITUNTIL_TIMEOUT_MAX }] }))).toEqual([]);
        // 空条件。
        expect(validateRule(rule({ actions: [{ type: 'waitUntil', condition: '', timeoutMs: 5000 }] }))
            .some((e) => e.message === `等待条件不能为空且最多 ${CONDITION_MAX} 字符`)).toBe(true);
        // timeout 越界（10 / 999999）。
        expect(validateRule(rule({ actions: [{ type: 'waitUntil', condition: 'var("user/x")>0', timeoutMs: 10 }] }))
            .some((e) => e.message === `超时时长需在 ${WAITUNTIL_TIMEOUT_MIN}..${WAITUNTIL_TIMEOUT_MAX} 毫秒之间`)).toBe(true);
        expect(validateRule(rule({ actions: [{ type: 'waitUntil', condition: 'var("user/x")>0', timeoutMs: 999999 }] }))
            .some((e) => e.message.includes('超时时长需在'))).toBe(true);
    });
});

// ---------- 0.7.2-P2：变量积木 + 元素积木（文案与后端逐字一致）----------

describe('validateRule — 0.7.2-P2 新动作字段（copy / append / clone / delete）', () => {
    it('copyVariable 合法 / 空 target / 空 source', () => {
        const ok: ScriptAction = { type: 'copyVariable', target: 'user/dst', source: 'user/src' };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const emptyTarget: ScriptAction = { type: 'copyVariable', target: '', source: 'user/src' };
        expect(validateRule(rule({ actions: [emptyTarget] }))
            .some((e) => e.message === '变量复制的目标 / 来源不能为空' && e.blockId === 'actions/0')).toBe(true);
        const emptySource: ScriptAction = { type: 'copyVariable', target: 'user/dst', source: '' };
        expect(validateRule(rule({ actions: [emptySource] }))
            .some((e) => e.message === '变量复制的目标 / 来源不能为空')).toBe(true);
    });

    it('appendVariable 合法 / 空 fullName', () => {
        const ok: ScriptAction = { type: 'appendVariable', fullName: 'user/log', text: 'x=${var:user/x}' };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        // text 空串合法（仅校验目标变量名非空）。
        expect(validateRule(rule({ actions: [{ type: 'appendVariable', fullName: 'user/log', text: '' }] }))).toEqual([]);
        const emptyName: ScriptAction = { type: 'appendVariable', fullName: '', text: 'x' };
        expect(validateRule(rule({ actions: [emptyName] }))
            .some((e) => e.message === '文本拼接的目标变量不能为空' && e.blockId === 'actions/0')).toBe(true);
    });

    it('cloneElement 合法 / 空 elementId / offset 非有限', () => {
        const ok: ScriptAction = { type: 'cloneElement', elementId: 'e-1', offsetX: 5, offsetY: 5 };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const emptyId: ScriptAction = { type: 'cloneElement', elementId: '', offsetX: 0, offsetY: 0 };
        expect(validateRule(rule({ actions: [emptyId] }))
            .some((e) => e.message === '克隆元素的目标不能为空' && e.blockId === 'actions/0')).toBe(true);
        const nan = { type: 'cloneElement', elementId: 'e-1', offsetX: NaN, offsetY: 0 } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [nan] }))
            .some((e) => e.message === '克隆偏移必须是有限数值')).toBe(true);
        const inf = { type: 'cloneElement', elementId: 'e-1', offsetX: 0, offsetY: Infinity } as unknown as ScriptAction;
        expect(validateRule(rule({ actions: [inf] }))
            .some((e) => e.message === '克隆偏移必须是有限数值')).toBe(true);
    });

    it('deleteElement 合法 / 空 elementId', () => {
        const ok: ScriptAction = { type: 'deleteElement', elementId: 'e-xyz' };
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        const emptyId: ScriptAction = { type: 'deleteElement', elementId: '' };
        expect(validateRule(rule({ actions: [emptyId] }))
            .some((e) => e.message === '删除元素的目标不能为空' && e.blockId === 'actions/0')).toBe(true);
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

// ---------- 0.7.1-P2：repeat 有界循环 ----------

describe('validateRule — repeat 有界循环（count 1..100 + body 非空 + 递归）', () => {
    /** 造一个 repeat（count + body）。 */
    function mk(count: number, body: ScriptAction[]): ScriptAction {
        return { type: 'repeat', count, body };
    }
    const oneLog: ScriptAction[] = [{ type: 'log', message: 'x' }];

    it('count 边界 1 / 100 合法（body 非空），文案与后端逐字一致', () => {
        expect(validateRule(rule({ actions: [mk(1, oneLog)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mk(100, oneLog)] }))).toEqual([]);
    });

    it('count 0 / 101 报错（文案"重复次数需在 1..100 之间"）', () => {
        const lo = validateRule(rule({ actions: [mk(0, oneLog)] }));
        const hi = validateRule(rule({ actions: [mk(101, oneLog)] }));
        expect(lo.some((e) => e.message === '重复次数需在 1..100 之间' && e.blockId === 'actions/0')).toBe(true);
        expect(hi.some((e) => e.message === '重复次数需在 1..100 之间')).toBe(true);
    });

    it('body 空 → 报错（文案"重复循环体不能为空"）', () => {
        const errs = validateRule(rule({ actions: [mk(3, [])] }));
        expect(errs.some((e) => e.message === '重复循环体不能为空' && e.blockId === 'actions/0')).toBe(true);
    });

    it('body 内动作错误带正确 blockId 路径 actions/0/body/0（与后端 ScriptRunner 展开同构）', () => {
        const errs = validateRule(rule({ actions: [mk(3, [{ type: 'wait', ms: 1 }])] }));
        const waitErr = errs.find((e) => e.message.includes('等待时长'));
        expect(waitErr?.blockId).toBe('actions/0/body/0');
    });

    it('repeat 不增 if 深度：body 内放深 4 的 if 合法（不因外层 repeat 撞 if 深度上限）', () => {
        let inner: ScriptAction[] = [{ type: 'log', message: 'x' }];
        for (let i = 0; i < MAX_IF_DEPTH; i++) {
            inner = [{ type: 'if', condition: 'true', then: inner, else: [] }];
        }
        expect(validateRule(rule({ actions: [mk(2, inner)] }))).toEqual([]);
    });

    it('countBlocks 计 repeat 自身 + body 节点但不乘 count（硬限 50 是树节点数）', () => {
        // repeat（计 1）+ body 内 49 个 log = 50，合法（不因 count=100 乘成 4900）。
        const body49: ScriptAction[] = Array.from({ length: 49 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        expect(validateRule(rule({ actions: [mk(100, body49)] }))).toEqual([]); // 1 + 49 = 50
        // 再加 1 → 51 超上限。
        const body50: ScriptAction[] = Array.from({ length: 50 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        expect(validateRule(rule({ actions: [mk(100, body50)] }))
            .some((e) => e.message.includes('积木总数'))).toBe(true); // 51
    });
});

// ---------- 0.7.2-P3：repeatUntil 动态循环（while 语义；condition + maxIterations + body）----------

describe('validateRule — repeatUntil 重复直到（condition 非空 + maxIterations 1..100 + body 非空 + 递归）', () => {
    /** 造一个 repeatUntil（condition + maxIterations + body）。 */
    function mk(condition: string, maxIterations: number, body: ScriptAction[]): ScriptAction {
        return { type: 'repeatUntil', condition, maxIterations, body };
    }
    const cond = 'var("user/x") > 0';
    const oneLog: ScriptAction[] = [{ type: 'log', message: 'x' }];

    it('合法（condition 非空 + maxIterations 落界 + body 非空）返空', () => {
        expect(validateRule(rule({ actions: [mk(cond, 10, oneLog)] }))).toEqual([]);
    });

    it('maxIterations 边界 1 / 100 合法', () => {
        expect(validateRule(rule({ actions: [mk(cond, REPEAT_MIN, oneLog)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mk(cond, REPEAT_MAX, oneLog)] }))).toEqual([]);
    });

    it('空 condition → 报错（文案"重复条件不能为空且最多 512 字符"）', () => {
        const errs = validateRule(rule({ actions: [mk('  ', 10, oneLog)] }));
        expect(errs.some((e) => e.message === `重复条件不能为空且最多 ${CONDITION_MAX} 字符` && e.blockId === 'actions/0')).toBe(true);
    });

    it('condition 512 合法 / 513 超长', () => {
        expect(validateRule(rule({ actions: [mk('a'.repeat(CONDITION_MAX), 10, oneLog)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mk('a'.repeat(CONDITION_MAX + 1), 10, oneLog)] }))
            .some((e) => e.message === `重复条件不能为空且最多 ${CONDITION_MAX} 字符`)).toBe(true);
    });

    it('maxIterations 0 / 101 报错（文案"重复次数上限需在 1..100 之间"）', () => {
        const lo = validateRule(rule({ actions: [mk(cond, 0, oneLog)] }));
        const hi = validateRule(rule({ actions: [mk(cond, 101, oneLog)] }));
        expect(lo.some((e) => e.message === `重复次数上限需在 ${REPEAT_MIN}..${REPEAT_MAX} 之间` && e.blockId === 'actions/0')).toBe(true);
        expect(hi.some((e) => e.message === `重复次数上限需在 ${REPEAT_MIN}..${REPEAT_MAX} 之间`)).toBe(true);
    });

    it('body 空 → 报错（文案"重复循环体不能为空"，与 repeat 同口径）', () => {
        const errs = validateRule(rule({ actions: [mk(cond, 10, [])] }));
        expect(errs.some((e) => e.message === '重复循环体不能为空' && e.blockId === 'actions/0')).toBe(true);
    });

    it('body 内动作错误带正确 blockId 路径 actions/0/body/0（与后端 ScriptRunner 同构）', () => {
        const errs = validateRule(rule({ actions: [mk(cond, 10, [{ type: 'wait', ms: 1 }])] }));
        const waitErr = errs.find((e) => e.message.includes('等待时长'));
        expect(waitErr?.blockId).toBe('actions/0/body/0');
    });

    it('repeatUntil 不增 if 深度：body 内放深 4 的 if 合法', () => {
        let inner: ScriptAction[] = [{ type: 'log', message: 'x' }];
        for (let i = 0; i < MAX_IF_DEPTH; i++) {
            inner = [{ type: 'if', condition: 'true', then: inner, else: [] }];
        }
        expect(validateRule(rule({ actions: [mk(cond, 5, inner)] }))).toEqual([]);
    });

    it('countBlocks 计 repeatUntil 自身 + body 节点（硬限 50 是树节点数）', () => {
        const body49: ScriptAction[] = Array.from({ length: 49 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        expect(validateRule(rule({ actions: [mk(cond, 100, body49)] }))).toEqual([]); // 1 + 49 = 50
        const body50: ScriptAction[] = Array.from({ length: 50 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        expect(validateRule(rule({ actions: [mk(cond, 100, body50)] }))
            .some((e) => e.message.includes('积木总数'))).toBe(true); // 51
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

// ---------- tween-P1：tweenBlock 补间包裹积木 ----------

describe('validateRule — tweenBlock 补间包裹（durationMs + easing + body 属性动作白名单）', () => {
    const easeInOut: Easing = { type: 'easeInOut' };
    /** 合法的 setElementProperties body 条目（moveTo kind，白名单内）。 */
    const moveToAction: ScriptAction = {
        type: 'setElementProperties',
        elementId: 'el-abc',
        patch: { x: '10', y: '20' },
        kind: 'moveTo',
    };

    function mkTween(durationMs: number, body: ScriptAction[], easing: Easing = easeInOut): ScriptAction {
        return { type: 'tweenBlock', durationMs, easing, body };
    }

    it('合法 tweenBlock（1ms..60000ms，easeInOut，moveTo body）返空', () => {
        expect(validateRule(rule({ actions: [mkTween(1000, [moveToAction])] }))).toEqual([]);
    });

    it('durationMs 边界 1 / 60000 合法', () => {
        expect(validateRule(rule({ actions: [mkTween(TWEEN_DURATION_MIN, [moveToAction])] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mkTween(TWEEN_DURATION_MAX, [moveToAction])] }))).toEqual([]);
    });

    it('durationMs = 0 报错（"补间时长需在 1..60000 毫秒之间"）', () => {
        const errs = validateRule(rule({ actions: [mkTween(0, [moveToAction])] }));
        expect(errs.some((e) => e.message.includes('补间时长') && e.blockId === 'actions/0')).toBe(true);
    });

    it('body 空 → 报错（"补间里至少要放一个动作"）', () => {
        const errs = validateRule(rule({ actions: [mkTween(1000, [])] }));
        expect(errs.some((e) => e.message === '补间里至少要放一个动作' && e.blockId === 'actions/0')).toBe(true);
    });

    it('body 放非属性动作（sendMessage）→ 报错（"补间里只能放移动/缩放/转动/透明度/变色"）', () => {
        const badAction: ScriptAction = { type: 'sendMessage', text: 'hi', channel: 'chat', target: 'trigger' };
        const errs = validateRule(rule({ actions: [mkTween(1000, [badAction])] }));
        expect(errs.some((e) => e.message === '补间里只能放移动/缩放/转动/透明度/变色' && e.blockId === 'actions/0/body/0')).toBe(true);
    });

    it('body 放 setElementProperties 但 kind 不在 TWEENABLE（setText）→ 报错', () => {
        const textAction: ScriptAction = {
            type: 'setElementProperties',
            elementId: 'el-abc',
            patch: { text: 'hi' },
            kind: 'setText',  // setText 不可补间（离散跳变）
        };
        const errs = validateRule(rule({ actions: [mkTween(1000, [textAction])] }));
        expect(errs.some((e) => e.message.includes('补间里只能放移动/缩放/转动/透明度/变色') && e.blockId === 'actions/0/body/0')).toBe(true);
    });

    it('easing linear / easeIn / easeOut / easeInOut 都合法', () => {
        for (const t of ['linear', 'easeIn', 'easeOut', 'easeInOut'] as const) {
            expect(validateRule(rule({ actions: [mkTween(500, [moveToAction], { type: t })] }))).toEqual([]);
        }
    });

    it('cubicBezier 合法（4 参 + x ∈ [0,1]）', () => {
        const bezierEasing: Easing = { type: 'cubicBezier', bezier: [0.42, 0, 0.58, 1] };
        expect(validateRule(rule({ actions: [mkTween(500, [moveToAction], bezierEasing)] }))).toEqual([]);
    });

    it('cubicBezier x 越界（x1=1.5）→ 报错', () => {
        const bad: Easing = { type: 'cubicBezier', bezier: [1.5, 0, 0.58, 1] };
        const errs = validateRule(rule({ actions: [mkTween(500, [moveToAction], bad)] }));
        expect(errs.some((e) => e.message.includes('控制点不合法') && e.blockId === 'actions/0')).toBe(true);
    });

    it('tweenBlock body blockId 路径正确（actions/0/body/0）', () => {
        const badAction: ScriptAction = { type: 'sendMessage', text: 'hi', channel: 'chat', target: 'trigger' };
        const errs = validateRule(rule({ actions: [mkTween(1000, [badAction])] }));
        expect(errs.some((e) => e.blockId === 'actions/0/body/0')).toBe(true);
    });

    it('tweenBlock 不增 if 深度：body 内放深 4 的 if 合法', () => {
        let inner: ScriptAction[] = [moveToAction];
        for (let i = 0; i < MAX_IF_DEPTH; i++) {
            // tweenBlock body 只允许属性动作；if 放在 tweenBlock body 会被 body 白名单拒
            // → 实际用例是 if 在 tweenBlock 外层，tweenBlock 在 if 分支内。
            // 测 tweenBlock 不增 if 深度：外层 4 层 if 嵌套，最内层有 tweenBlock（合法）。
            inner = [{ type: 'if', condition: 'true', then: inner, else: [] }];
        }
        // 4 层 if 嵌套内包 tweenBlock（body=moveToAction）应合法（tweenBlock 不计 if 深度）。
        const tweenInner: ScriptAction = mkTween(500, [moveToAction]);
        let outerActions: ScriptAction[] = [tweenInner];
        for (let i = 0; i < MAX_IF_DEPTH; i++) {
            outerActions = [{ type: 'if', condition: 'true', then: outerActions, else: [] }];
        }
        expect(validateRule(rule({ actions: outerActions }))).toEqual([]);
    });
});

// ---------- 0.7.3：G1 randomBranch ----------

describe('validateRule — randomBranch 随机分支', () => {
    function mkRb(probability: number, thenA: ScriptAction[] = [], elseA: ScriptAction[] = []): ScriptAction {
        return { type: 'randomBranch', probability, then: thenA, else: elseA };
    }

    it('合法 probability 边界（0 / 50 / 100）', () => {
        expect(validateRule(rule({ actions: [mkRb(PROBABILITY_MIN)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mkRb(50)] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mkRb(PROBABILITY_MAX)] }))).toEqual([]);
    });

    it('probability -1 / 101 报错（文案"随机概率需在 0..100 之间"）', () => {
        const lo = validateRule(rule({ actions: [mkRb(-1)] }));
        const hi = validateRule(rule({ actions: [mkRb(101)] }));
        expect(lo.some((e) => e.message === `随机概率需在 ${PROBABILITY_MIN}..${PROBABILITY_MAX} 之间` && e.blockId === 'actions/0')).toBe(true);
        expect(hi.some((e) => e.message.includes('随机概率'))).toBe(true);
    });

    it('then/else 内子动作错误带正确 blockId 路径（与后端 ScriptRunner 同构）', () => {
        const errs = validateRule(rule({ actions: [mkRb(50, [{ type: 'wait', ms: 1 }], [{ type: 'log', message: 'ok' }])] }));
        const waitErr = errs.find((e) => e.message.includes('等待时长'));
        expect(waitErr?.blockId).toBe('actions/0/then/0');
    });

    it('randomBranch 计入 if 深度：MAX_IF_DEPTH 层合法，MAX_IF_DEPTH+1 层报错', () => {
        // MAX_IF_DEPTH=4 层合法（最深节点 depth=4，不超）。
        let inner4: ScriptAction[] = [{ type: 'log', message: 'x' }];
        for (let i = 0; i < MAX_IF_DEPTH; i++) {
            inner4 = [mkRb(50, inner4, [])];
        }
        expect(validateRule(rule({ actions: inner4 }))).toEqual([]);
        // MAX_IF_DEPTH+1=5 层报错（最深节点 depth=5 > 4）。
        let inner5: ScriptAction[] = [{ type: 'log', message: 'x' }];
        for (let i = 0; i < MAX_IF_DEPTH + 1; i++) {
            inner5 = [mkRb(50, inner5, [])];
        }
        expect(validateRule(rule({ actions: inner5 })).some((e) => e.message.includes('分支嵌套超过'))).toBe(true);
    });

    it('countBlocks 计 randomBranch 自身 + then/else 节点', () => {
        const then48: ScriptAction[] = Array.from({ length: 48 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        // 1 randomBranch + 48 then + 1 else = 50，合法。
        const ok = mkRb(50, then48, [{ type: 'log', message: 'e' }]);
        expect(validateRule(rule({ actions: [ok] }))).toEqual([]);
        // 1 + 49 + 1 = 51，超。
        const then49: ScriptAction[] = Array.from({ length: 49 }, () => ({ type: 'log', message: 'x' }) as ScriptAction);
        const over = mkRb(50, then49, [{ type: 'log', message: 'e' }]);
        expect(validateRule(rule({ actions: [over] })).some((e) => e.message.includes('积木总数'))).toBe(true);
    });
});

// ---------- 0.7.3：G2 setElementLayer ----------

describe('validateRule — setElementLayer 元素置层', () => {
    it('合法（elementId 非空 + mode ∈ {front,back}）', () => {
        expect(validateRule(rule({ actions: [{ type: 'setElementLayer', elementId: 'el-1', mode: 'front' }] }))).toEqual([]);
        expect(validateRule(rule({ actions: [{ type: 'setElementLayer', elementId: 'el-2', mode: 'back' }] }))).toEqual([]);
    });

    it('elementId 空 → 报错', () => {
        const errs = validateRule(rule({ actions: [{ type: 'setElementLayer', elementId: '', mode: 'front' }] }));
        expect(errs.some((e) => e.message === '元素置层缺少元素 ID' && e.blockId === 'actions/0')).toBe(true);
    });

    it('mode 非法 → 报错', () => {
        const errs = validateRule(rule({ actions: [{ type: 'setElementLayer', elementId: 'el-1', mode: 'middle' as never }] }));
        expect(errs.some((e) => e.message.includes('置层方向不在允许范围') && e.blockId === 'actions/0')).toBe(true);
    });

    it('ELEMENT_LAYER_MODES 白名单与 def 一致', () => {
        expect(ELEMENT_LAYER_MODES.has('front')).toBe(true);
        expect(ELEMENT_LAYER_MODES.has('back')).toBe(true);
        expect(ELEMENT_LAYER_MODES.size).toBe(2);
    });
});

// ---------- 0.7.3：G3 roundVariable ----------

describe('validateRule — roundVariable 变量取整', () => {
    it('合法（fullName 非空 + mode ∈ {round,floor,ceil}）', () => {
        expect(validateRule(rule({ actions: [{ type: 'roundVariable', fullName: 'user/score', mode: 'round' }] }))).toEqual([]);
        expect(validateRule(rule({ actions: [{ type: 'roundVariable', fullName: 'user/x', mode: 'floor' }] }))).toEqual([]);
        expect(validateRule(rule({ actions: [{ type: 'roundVariable', fullName: 'user/y', mode: 'ceil' }] }))).toEqual([]);
    });

    it('fullName 空 → 报错', () => {
        const errs = validateRule(rule({ actions: [{ type: 'roundVariable', fullName: '', mode: 'round' }] }));
        expect(errs.some((e) => e.message === '取整变量名不能为空' && e.blockId === 'actions/0')).toBe(true);
    });

    it('mode 非法 → 报错', () => {
        const errs = validateRule(rule({ actions: [{ type: 'roundVariable', fullName: 'user/x', mode: 'truncate' as never }] }));
        expect(errs.some((e) => e.message.includes('取整方式不在允许范围') && e.blockId === 'actions/0')).toBe(true);
    });

    it('ROUND_MODES 白名单覆盖 3 种', () => {
        expect(ROUND_MODES.has('round')).toBe(true);
        expect(ROUND_MODES.has('floor')).toBe(true);
        expect(ROUND_MODES.has('ceil')).toBe(true);
        expect(ROUND_MODES.size).toBe(3);
    });
});

// ---------- 0.7.3：G4 showTitle ----------

describe('validateRule — showTitle 标题弹窗', () => {
    function mkSt(over: Partial<{ title: string; subtitle: string; fadeInMs: number; stayMs: number; fadeOutMs: number; target: string }>): ScriptAction {
        return {
            type: 'showTitle',
            title: '标题',
            subtitle: '',
            fadeInMs: 500,
            stayMs: 2000,
            fadeOutMs: 500,
            target: 'trigger',
            ...over,
        } as ScriptAction;
    }

    it('合法（title 非空 + 时长在范围内 + target=trigger/all）', () => {
        expect(validateRule(rule({ actions: [mkSt({})] }))).toEqual([]);
        expect(validateRule(rule({ actions: [mkSt({ target: 'all', subtitle: '副' })] }))).toEqual([]);
        // subtitle 非空 title 空也合法（至少一个非空）
        expect(validateRule(rule({ actions: [mkSt({ title: '', subtitle: '副标题' })] }))).toEqual([]);
    });

    it('title 和 subtitle 同时为空 → 报错', () => {
        const errs = validateRule(rule({ actions: [mkSt({ title: '', subtitle: '' })] }));
        expect(errs.some((e) => e.message === '标题和副标题不能同时为空' && e.blockId === 'actions/0')).toBe(true);
    });

    it(`title 超长（> ${TITLE_MAX}）→ 报错`, () => {
        const errs = validateRule(rule({ actions: [mkSt({ title: 'a'.repeat(TITLE_MAX + 1) })] }));
        expect(errs.some((e) => e.message.includes('标题超长'))).toBe(true);
    });

    it(`fadeInMs / fadeOutMs 超限（> ${TITLE_FADE_MAX}）→ 报错`, () => {
        const e1 = validateRule(rule({ actions: [mkSt({ fadeInMs: TITLE_FADE_MAX + 1 })] }));
        expect(e1.some((e) => e.message.includes('淡入时长'))).toBe(true);
        const e2 = validateRule(rule({ actions: [mkSt({ fadeOutMs: TITLE_FADE_MAX + 1 })] }));
        expect(e2.some((e) => e.message.includes('淡出时长'))).toBe(true);
    });

    it(`stayMs 超限（> ${TITLE_STAY_MAX}）→ 报错`, () => {
        const errs = validateRule(rule({ actions: [mkSt({ stayMs: TITLE_STAY_MAX + 1 })] }));
        expect(errs.some((e) => e.message.includes('停留时长'))).toBe(true);
    });

    it('target 非法 → 报错', () => {
        const errs = validateRule(rule({ actions: [mkSt({ target: 'server' })] }));
        expect(errs.some((e) => e.message.includes('弹窗发送对象不在允许范围') && e.blockId === 'actions/0')).toBe(true);
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
