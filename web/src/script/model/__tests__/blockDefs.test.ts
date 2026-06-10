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
    type BlockDef,
} from '../blockDefs';

/** 期望的触发器字段集（手抄自 protocol.ts ScriptTrigger，含无数据字段的空集）。 */
const EXPECTED_TRIGGER_FIELDS: Record<string, string[]> = {
    variableChange: ['fullName'],
    timer: ['intervalSeconds'],
    playerJoin: [],
    playerKill: [],
    playerNear: ['rangeBlocks'],
    wallReady: [],
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
};

function fieldNames(def: BlockDef): string[] {
    return def.fields.map((f) => f.name);
}

describe('blockDefs.TRIGGER_DEFS', () => {
    it('恰好 6 个触发器（与 protocol.ts ScriptTrigger 同数）', () => {
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
    it('恰好 9 个动作（8 动作 + if，与 protocol.ts ScriptAction 同数）', () => {
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
