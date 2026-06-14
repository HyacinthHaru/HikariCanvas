/**
 * 0.7.0-P4-C：blockDefs 完整性 + 字段覆盖 wire 单测（纯逻辑，vitest node）。
 *
 * <p>核心契约：每种触发器 / 动作都有 def；每个 def 的 fields <b>覆盖该 wire 类型的全部
 * 数据字段</b>（对照 protocol.ts 的 ScriptTrigger / ScriptAction 手抄的期望字段集）。
 * 字段集若漂移（后端加字段忘了补 def）→ 测试红，逼回填。</p>
 */
import { describe, expect, it } from 'vitest';
import {
    TRIGGER_DEFS,
    ACTION_DEFS,
    defFor,
    CATEGORY_COLOR_VAR,
    FRIENDLY_ELEMENT_DEFS,
    FRIENDLY_PALETTE_KINDS,
    makeDefaultAction,
    type BlockDef,
} from '../blockDefs';
import { ELEMENT_PROPERTIES, MESSAGE_CHANNELS, SCALE_OPS, MESSAGE_TARGETS } from '../validator';

/** 期望的触发器字段集（手抄自 protocol.ts ScriptTrigger，含无数据字段的空集）。 */
const EXPECTED_TRIGGER_FIELDS: Record<string, string[]> = {
    variableChange: ['fullName'],
    timer: ['intervalSeconds'],
    playerJoin: [],
    playerKill: [],
    playerNear: ['rangeBlocks'],
    wallReady: [],
    // 0.7.1-P2：3 个新触发器。
    rightClickWall: [],
    playerLeaveRange: ['rangeBlocks'],
    playerQuit: [],
};

/** 期望的动作字段集（手抄自 protocol.ts ScriptAction，含 if 的 condition/then/else）。 */
const EXPECTED_ACTION_FIELDS: Record<string, string[]> = {
    setVariable: ['fullName', 'value'],
    incrementVariable: ['fullName', 'delta'],
    setElementProperty: ['elementId', 'property', 'value'],
    playTimeline: ['timelineId', 'op', 'seekMs'],
    playSound: ['soundId', 'volume', 'pitch', 'scope'],
    wait: ['ms'],
    runCommand: ['templateId', 'params'],
    log: ['message'],
    if: ['condition', 'then', 'else'],
    // 0.7.1-P1：4 个低风险新动作 + 相对移动（setElementProperties 走 FRIENDLY_ELEMENT_DEFS，不进 ACTION_DEFS）。
    nudgeElement: ['elementId', 'dx', 'dy'],
    // 0.7.2-P3：sendMessage 加 target（trigger 默认 / all 全服）。
    sendMessage: ['text', 'channel', 'target'],
    setRandomVariable: ['fullName', 'min', 'max'],
    scaleVariable: ['fullName', 'op', 'factor'],
    playTimelineAwait: ['timelineId'],
    // 0.7.1-P2：有界循环（control category，count + body statements 子序列槽）。
    repeat: ['count', 'body'],
    // 0.7.2-P3：重复直到条件（control category，condition + maxIterations + body statements 子序列槽）。
    repeatUntil: ['condition', 'maxIterations', 'body'],
    // 0.7.1-P5：3 个剩余动作（停止 / 粒子 / 等待直到）。stopScript 无字段；
    // playParticle wire 字段名 particle（与后端 record 同名）+ count + 3 offset；
    // waitUntil 复用 condition 字段（type:'condition'）+ timeoutMs。
    stopScript: [],
    playParticle: ['particle', 'count', 'offsetX', 'offsetY', 'offsetZ'],
    waitUntil: ['condition', 'timeoutMs'],
    // 0.7.2-P2：变量积木（复制 / 拼接）+ 元素积木（克隆 / 删除）。
    copyVariable: ['source', 'target'],
    appendVariable: ['fullName', 'text'],
    cloneElement: ['elementId', 'offsetX', 'offsetY'],
    deleteElement: ['elementId'],
    // tween-P1：补间包裹积木（control category，durationMs + easing select + body statements）。
    tweenBlock: ['durationMs', 'easing', 'body'],
    // 0.7.3：4 个新积木。
    randomBranch: ['probability', 'then', 'else'],
    setElementLayer: ['elementId', 'mode'],
    roundVariable: ['fullName', 'mode'],
    showTitle: ['title', 'subtitle', 'fadeInMs', 'stayMs', 'fadeOutMs', 'target'],
};

function fieldNames(def: BlockDef): string[] {
    return def.fields.map((f) => f.name);
}

describe('blockDefs.TRIGGER_DEFS', () => {
    it('恰好 9 个触发器（0.7.0 6 个 + 0.7.1-P2 3 个，与 protocol.ts ScriptTrigger 同数）', () => {
        expect(Object.keys(TRIGGER_DEFS).sort()).toEqual(
            Object.keys(EXPECTED_TRIGGER_FIELDS).sort(),
        );
    });

    it('每个触发器 kind 与 map key 一致 + category=trigger + 配色=peach', () => {
        for (const [key, def] of Object.entries(TRIGGER_DEFS)) {
            expect(def.kind).toBe(key);
            expect(def.category).toBe('trigger');
            expect(def.colorVar).toBe(CATEGORY_COLOR_VAR.trigger);
            expect(def.colorVar).toBe('--ctp-peach');
            expect(def.labelKey).toMatch(/^script\.blocks\./);
        }
    });

    it('字段恰好覆盖各触发器 wire 数据字段（顺序无关）', () => {
        for (const [kind, expected] of Object.entries(EXPECTED_TRIGGER_FIELDS)) {
            expect(fieldNames(TRIGGER_DEFS[kind]).sort()).toEqual([...expected].sort());
        }
    });
});

describe('blockDefs.ACTION_DEFS', () => {
    it('动作集与 EXPECTED_ACTION_FIELDS 一致（0.7.0 9 个 + 0.7.1-P1 5 个 + 0.7.1-P2 repeat + 0.7.1-P5 3 个 + 0.7.2-P2 4 个 + 0.7.2-P3 repeatUntil + tween-P1 tweenBlock + 0.7.3 4 个 = 28）', () => {
        expect(Object.keys(ACTION_DEFS).sort()).toEqual(
            Object.keys(EXPECTED_ACTION_FIELDS).sort(),
        );
    });

    it('每个动作 kind 与 map key 一致 + labelKey 指向 script.blocks.*', () => {
        for (const [key, def] of Object.entries(ACTION_DEFS)) {
            expect(def.kind).toBe(key);
            expect(def.labelKey).toBe(`script.blocks.${key}`);
        }
    });

    it('字段恰好覆盖各动作 wire 数据字段（顺序无关）', () => {
        for (const [kind, expected] of Object.entries(EXPECTED_ACTION_FIELDS)) {
            expect(fieldNames(ACTION_DEFS[kind]).sort()).toEqual([...expected].sort());
        }
    });

    it('字段顺序 = 表单顺序（精确序列，非仅集合）', () => {
        // 抽查几个多字段块的精确顺序（与 protocol.ts 字段声明序一致）。
        expect(fieldNames(ACTION_DEFS.setElementProperty)).toEqual(['elementId', 'property', 'value']);
        expect(fieldNames(ACTION_DEFS.playSound)).toEqual(['soundId', 'volume', 'pitch', 'scope']);
        expect(fieldNames(ACTION_DEFS.if)).toEqual(['condition', 'then', 'else']);
    });

    it('category → 配色映射正确（action=blue / control=green / danger=red / timeline=mauve）', () => {
        expect(ACTION_DEFS.setVariable.category).toBe('action');
        expect(ACTION_DEFS.setVariable.colorVar).toBe('--ctp-blue');
        expect(ACTION_DEFS.if.category).toBe('control');
        expect(ACTION_DEFS.if.colorVar).toBe('--ctp-green');
        expect(ACTION_DEFS.runCommand.category).toBe('danger');
        expect(ACTION_DEFS.runCommand.colorVar).toBe('--ctp-red');
        expect(ACTION_DEFS.playTimeline.category).toBe('timeline');
        expect(ACTION_DEFS.playTimeline.colorVar).toBe('--ctp-mauve');
    });

    it('if 的 then/else 是 statements 类型，condition 是 condition 类型', () => {
        const byName = Object.fromEntries(ACTION_DEFS.if.fields.map((f) => [f.name, f]));
        expect(byName['condition'].type).toBe('condition');
        expect(byName['then'].type).toBe('statements');
        expect(byName['else'].type).toBe('statements');
    });

    it('number 字段范围镜像后端 validator（timer 1..86400 / near 1..32 / wait 50..5000 / volume 0..2 / pitch 0.5..2）', () => {
        const timer = TRIGGER_DEFS.timer.fields[0];
        expect([timer.min, timer.max]).toEqual([1, 86400]);
        const near = TRIGGER_DEFS.playerNear.fields[0];
        expect([near.min, near.max]).toEqual([1, 32]);
        const wait = ACTION_DEFS.wait.fields[0];
        expect([wait.min, wait.max]).toEqual([50, 5000]);
        const vol = ACTION_DEFS.playSound.fields.find((f) => f.name === 'volume')!;
        expect([vol.min, vol.max]).toEqual([0, 2]);
        const pitch = ACTION_DEFS.playSound.fields.find((f) => f.name === 'pitch')!;
        expect([pitch.min, pitch.max]).toEqual([0.5, 2]);
    });

    it('select/op/scope 字段携带 options，且 value 与后端白名单一致', () => {
        const prop = ACTION_DEFS.setElementProperty.fields.find((f) => f.name === 'property')!;
        expect(prop.options?.map((o) => o.value).sort()).toEqual(
            ['fill', 'h', 'opacity', 'rotation', 'text', 'w', 'x', 'y'],
        );
        const op = ACTION_DEFS.playTimeline.fields.find((f) => f.name === 'op')!;
        expect(op.options?.map((o) => o.value)).toEqual(['play', 'pause', 'seek']);
        const scope = ACTION_DEFS.playSound.fields.find((f) => f.name === 'scope')!;
        expect(scope.options?.map((o) => o.value)).toEqual(['near', 'all']);
    });

    it('playTimeline.seekMs 标记 optional（仅 op=seek 携带）', () => {
        const seek = ACTION_DEFS.playTimeline.fields.find((f) => f.name === 'seekMs')!;
        expect(seek.optional).toBe(true);
    });

    // ---- 0.7.1-P1：5 个新动作的 def 形态 + select 选项与后端白名单一致 ----
    it('5 个新动作 def 存在 + kind/category/colorVar 正确', () => {
        expect(ACTION_DEFS.nudgeElement.category).toBe('action');
        expect(ACTION_DEFS.sendMessage.category).toBe('action');
        expect(ACTION_DEFS.setRandomVariable.category).toBe('action');
        expect(ACTION_DEFS.scaleVariable.category).toBe('action');
        // playTimelineAwait 归 timeline 分类（与 playTimeline 同配色 mauve）。
        expect(ACTION_DEFS.playTimelineAwait.category).toBe('timeline');
        expect(ACTION_DEFS.playTimelineAwait.colorVar).toBe('--ctp-mauve');
    });

    it('sendMessage.channel 选项与后端 MESSAGE_CHANNELS 一致', () => {
        const channel = ACTION_DEFS.sendMessage.fields.find((f) => f.name === 'channel')!;
        expect(channel.options?.map((o) => o.value)).toEqual(['chat', 'actionbar', 'title']);
        expect(channel.options?.map((o) => o.value).sort()).toEqual([...MESSAGE_CHANNELS].sort());
    });

    it('scaleVariable.op 选项与后端 SCALE_OPS 一致', () => {
        const op = ACTION_DEFS.scaleVariable.fields.find((f) => f.name === 'op')!;
        expect(op.options?.map((o) => o.value)).toEqual(['multiply', 'divide']);
        expect(op.options?.map((o) => o.value).sort()).toEqual([...SCALE_OPS].sort());
    });

    // ---- 0.7.1-P2：repeat（有界循环，control category，count + body 子序列槽）----
    it('repeat def：category=control / colorVar=green / 字段 count(number 1..100) + body(statements)', () => {
        const def = ACTION_DEFS.repeat;
        expect(def.kind).toBe('repeat');
        expect(def.category).toBe('control');
        expect(def.colorVar).toBe('--ctp-green');
        expect(def.fields.map((f) => f.name)).toEqual(['count', 'body']);
        const count = def.fields.find((f) => f.name === 'count')!;
        expect(count.type).toBe('number');
        expect([count.min, count.max]).toEqual([1, 100]);
        const body = def.fields.find((f) => f.name === 'body')!;
        expect(body.type).toBe('statements');
    });

    // ---- 0.7.2-P3：repeatUntil（重复直到，C 形 control，condition + maxIterations + body）----
    it('repeatUntil def：category=control / colorVar=green / 字段 condition(condition) + maxIterations(number 1..100) + body(statements)', () => {
        const def = ACTION_DEFS.repeatUntil;
        expect(def.kind).toBe('repeatUntil');
        expect(def.category).toBe('control');
        expect(def.colorVar).toBe('--ctp-green');
        expect(def.fields.map((f) => f.name)).toEqual(['condition', 'maxIterations', 'body']);
        const cond = def.fields.find((f) => f.name === 'condition')!;
        expect(cond.type).toBe('condition');
        const maxIter = def.fields.find((f) => f.name === 'maxIterations')!;
        expect(maxIter.type).toBe('number');
        expect([maxIter.min, maxIter.max]).toEqual([1, 100]);
        const body = def.fields.find((f) => f.name === 'body')!;
        expect(body.type).toBe('statements');
    });

    // ---- 0.7.2-P3：sendMessage 加 target 下拉（trigger / all，与后端 MESSAGE_TARGETS 一致）----
    it('sendMessage.target 选项与后端 MESSAGE_TARGETS 一致（trigger / all）', () => {
        const target = ACTION_DEFS.sendMessage.fields.find((f) => f.name === 'target')!;
        expect(target.type).toBe('scope');
        expect(target.options?.map((o) => o.value)).toEqual(['trigger', 'all']);
        expect(target.options?.map((o) => o.value).sort()).toEqual([...MESSAGE_TARGETS].sort());
    });
});

describe('blockDefs.FRIENDLY_ELEMENT_DEFS', () => {
    const EXPECTED_FRIENDLY = ['moveTo', 'resize', 'rotateTo', 'setOpacity', 'show', 'hide', 'setText', 'setColor'];

    it('恰好 8 个友好元素 kind', () => {
        expect(Object.keys(FRIENDLY_ELEMENT_DEFS).sort()).toEqual([...EXPECTED_FRIENDLY].sort());
    });

    it('每个 def 的 kind 与 map key 一致 + labelKey 指向 script.friendly.*', () => {
        for (const [key, def] of Object.entries(FRIENDLY_ELEMENT_DEFS)) {
            expect(def.kind).toBe(key);
            expect(def.labelKey).toBe(`script.friendly.${key}`);
        }
    });

    it('每个 def 的 defaultPatch 键都在后端 ELEMENT_PROPERTIES 白名单内（拖出即合法）', () => {
        for (const def of Object.values(FRIENDLY_ELEMENT_DEFS)) {
            for (const k of Object.keys(def.defaultPatch)) {
                expect(ELEMENT_PROPERTIES.has(k)).toBe(true);
            }
            // 可编辑字段名也必须落白名单（字段值 = patch 键）。
            for (const f of def.fields) {
                expect(ELEMENT_PROPERTIES.has(f.name)).toBe(true);
            }
        }
    });

    it('show / hide 无可编辑字段（拖出即定值 opacity 1 / 0）', () => {
        expect(FRIENDLY_ELEMENT_DEFS.show.fields).toEqual([]);
        expect(FRIENDLY_ELEMENT_DEFS.show.defaultPatch).toEqual({ opacity: '1' });
        expect(FRIENDLY_ELEMENT_DEFS.hide.fields).toEqual([]);
        expect(FRIENDLY_ELEMENT_DEFS.hide.defaultPatch).toEqual({ opacity: '0' });
    });

    it('moveTo 字段 = x / y（patch 键），默认 0 / 0', () => {
        expect(FRIENDLY_ELEMENT_DEFS.moveTo.fields.map((f) => f.name)).toEqual(['x', 'y']);
        expect(FRIENDLY_ELEMENT_DEFS.moveTo.defaultPatch).toEqual({ x: '0', y: '0' });
    });

    // ---- 0.7.3 D3：setColor 产出 color 键（不是 fill）修复 ----
    it('setColor 字段 = color（不是 fill），defaultPatch.color = #FFFFFF', () => {
        // 回归：旧版 setColor 的 fields[0].name='fill' + defaultPatch={fill:'#FFFFFF'}，
        // 导致补间引擎把 fill 键发给 TextElement → applyTextPatch 抛"unknown text field: fill"
        // + from==to（fill 兜底成目标色）→ 文字变色补间既不动画也不落盘。
        // 修复后 patch 键为 color，后端 color 分支（TweenScheduler L434）端到端可达。
        const def = FRIENDLY_ELEMENT_DEFS.setColor;
        expect(def.fields.map((f) => f.name)).toEqual(['color']);
        expect(def.defaultPatch).toEqual({ color: '#FFFFFF' });
    });

    it('setColor defaultPatch 键在 ELEMENT_PROPERTIES 白名单内（color 已加入）', () => {
        // 双重确认：setColor 产出 color → ELEMENT_PROPERTIES 需含 color → validator 放行
        expect(ELEMENT_PROPERTIES.has('color')).toBe(true);
        const k = Object.keys(FRIENDLY_ELEMENT_DEFS.setColor.defaultPatch)[0];
        expect(ELEMENT_PROPERTIES.has(k)).toBe(true);
    });
});

describe('blockDefs.FRIENDLY_PALETTE_KINDS', () => {
    it('含 9 项（8 友好 kind + nudgeElement）', () => {
        expect(FRIENDLY_PALETTE_KINDS).toEqual(
            ['moveTo', 'nudgeElement', 'resize', 'rotateTo', 'setOpacity', 'show', 'hide', 'setText', 'setColor'],
        );
    });

    it('除 nudgeElement 外，其余项都在 FRIENDLY_ELEMENT_DEFS 里', () => {
        for (const kind of FRIENDLY_PALETTE_KINDS) {
            if (kind === 'nudgeElement') continue;
            expect(FRIENDLY_ELEMENT_DEFS[kind]).toBeDefined();
        }
    });
});

describe('blockDefs.defFor', () => {
    it('命中动作 kind', () => {
        expect(defFor('setVariable')?.kind).toBe('setVariable');
        expect(defFor('if')?.category).toBe('control');
    });

    it('命中触发器 kind', () => {
        expect(defFor('variableChange')?.kind).toBe('variableChange');
        expect(defFor('wallReady')?.category).toBe('trigger');
    });

    it('未知 kind → null', () => {
        expect(defFor('nope')).toBeNull();
        expect(defFor('')).toBeNull();
    });
});

// ---- 0.7.3：4 个新积木 def 形态 ----

describe('blockDefs.ACTION_DEFS — 0.7.3 新积木', () => {
    it('randomBranch def：category=control / colorVar=green / 字段 probability(number 0..100) + then/else(statements)', () => {
        const def = ACTION_DEFS.randomBranch;
        expect(def.kind).toBe('randomBranch');
        expect(def.category).toBe('control');
        expect(def.colorVar).toBe('--ctp-green');
        expect(def.labelKey).toBe('script.blocks.randomBranch');
        const prob = def.fields.find((f) => f.name === 'probability')!;
        expect(prob.type).toBe('number');
        expect([prob.min, prob.max]).toEqual([0, 100]);
        const then = def.fields.find((f) => f.name === 'then')!;
        expect(then.type).toBe('statements');
        const els = def.fields.find((f) => f.name === 'else')!;
        expect(els.type).toBe('statements');
    });

    it('setElementLayer def：category=action / colorVar=blue / 字段 elementId(element) + mode(select front/back)', () => {
        const def = ACTION_DEFS.setElementLayer;
        expect(def.kind).toBe('setElementLayer');
        expect(def.category).toBe('action');
        expect(def.colorVar).toBe('--ctp-blue');
        const mode = def.fields.find((f) => f.name === 'mode')!;
        expect(mode.type).toBe('select');
        expect(mode.options?.map((o) => o.value)).toEqual(['front', 'back']);
    });

    it('roundVariable def：category=action / colorVar=blue / 字段 fullName(variable) + mode(select round/floor/ceil)', () => {
        const def = ACTION_DEFS.roundVariable;
        expect(def.kind).toBe('roundVariable');
        expect(def.category).toBe('action');
        expect(def.colorVar).toBe('--ctp-blue');
        const mode = def.fields.find((f) => f.name === 'mode')!;
        expect(mode.type).toBe('select');
        expect(mode.options?.map((o) => o.value)).toEqual(['round', 'floor', 'ceil']);
    });

    it('showTitle def：category=action / colorVar=blue / 字段 title+subtitle+3时长(number)+target(scope,复用MESSAGE_TARGET_OPTIONS)', () => {
        const def = ACTION_DEFS.showTitle;
        expect(def.kind).toBe('showTitle');
        expect(def.category).toBe('action');
        expect(def.colorVar).toBe('--ctp-blue');
        const target = def.fields.find((f) => f.name === 'target')!;
        expect(target.type).toBe('scope');
        expect(target.options?.map((o) => o.value)).toEqual(['trigger', 'all']);
        const fadeIn = def.fields.find((f) => f.name === 'fadeInMs')!;
        expect(fadeIn.type).toBe('number');
        expect([fadeIn.min, fadeIn.max]).toEqual([0, 10000]);
        const stay = def.fields.find((f) => f.name === 'stayMs')!;
        expect([stay.min, stay.max]).toEqual([0, 60000]);
    });
});

// ---- 0.7.3：makeDefaultAction 默认值 ----

describe('blockDefs.makeDefaultAction — 0.7.3 新积木默认值', () => {
    it('randomBranch 默认 probability=50 + then/else 空数组', () => {
        const a = makeDefaultAction('randomBranch');
        expect(a.type).toBe('randomBranch');
        if (a.type === 'randomBranch') {
            expect(a.probability).toBe(50);
            expect(a.then).toEqual([]);
            expect(a.else).toEqual([]);
        }
    });

    it('setElementLayer 默认 elementId 空 + mode=front', () => {
        const a = makeDefaultAction('setElementLayer');
        expect(a.type).toBe('setElementLayer');
        if (a.type === 'setElementLayer') {
            expect(a.elementId).toBe('');
            expect(a.mode).toBe('front');
        }
    });

    it('roundVariable 默认 fullName=user/score + mode=round', () => {
        const a = makeDefaultAction('roundVariable');
        expect(a.type).toBe('roundVariable');
        if (a.type === 'roundVariable') {
            expect(a.fullName).toBe('user/score');
            expect(a.mode).toBe('round');
        }
    });

    it('showTitle 默认 title/subtitle 空串 + 标准时长 + target=trigger', () => {
        const a = makeDefaultAction('showTitle');
        expect(a.type).toBe('showTitle');
        if (a.type === 'showTitle') {
            expect(a.title).toBe('');
            expect(a.subtitle).toBe('');
            expect(a.fadeInMs).toBe(500);
            expect(a.stayMs).toBe(2000);
            expect(a.fadeOutMs).toBe(500);
            expect(a.target).toBe('trigger');
        }
    });
});
