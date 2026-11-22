/**
 * 前端脚本规则校验器 —— 后端 `ScriptRuleValidator` 的逐字段复刻。
 *
 * 权威：`plugin/src/main/java/ac/haru/hikaricanvas/script/ScriptRuleValidator.java`。
 * 本文件的常量（数值上下界 / 长度上限 / 白名单集合）必须与后端逐一一致，用于保存前预校验：
 * 拖完即在编辑器里给提示并阻止无效自动保存，而不是等服务端返回 `SCRIPT_INVALID`。
 *
 * 与后端的两点形态差异（语义一致）：
 * 1. 后端 `validate` 是 fail-fast（返第一个错误）；前端 `validateRule` 收集所有错误返回
 *    `ValidationError[]`，每条带 `blockId`（积木树路径，与 trace `blockId` 同构）供 UI 定位。
 * 2. 错误文案走 `messages[locale].script.validation`，中文表与后端逐字一致，同时支持英文界面。
 *
 * 纯逻辑、无 Vue / DOM 依赖。`validateRule` 只读 `ScriptRule`，不修改入参。
 */

import type { ScriptRule, ScriptTrigger, ScriptAction, EasingType } from '@/types/protocol';
import { ACTION_DEFS, TRIGGER_DEFS, TWEENABLE_KINDS, type FieldDef } from './blockDefs';
import { countBlocks } from './blockTree';
import { messages } from '@/i18n/messages';

// ---------- 常量（与 ScriptRuleValidator.java 对齐）----------

/** 规则名最大长度（后端 `NAME_MAX`）。 */
export const NAME_MAX = 64;
/** 积木总数上限（每个动作计 1，容器块自身计 1 再加子序列递归；后端 `MAX_TOTAL_BLOCKS`）。 */
export const MAX_TOTAL_BLOCKS = 50;
/** 条件分支嵌套深度上限（第 5 层拒绝；后端 `MAX_IF_DEPTH`）。 */
export const MAX_IF_DEPTH = 4;
/** `Wait.ms` 范围（毫秒；后端 `WAIT_MIN` / `WAIT_MAX`）。 */
export const WAIT_MIN = 50;
export const WAIT_MAX = 5000;
/** `Timer.intervalSeconds` 范围（秒；后端 `TIMER_MIN` / `TIMER_MAX`）。 */
export const TIMER_MIN = 1;
export const TIMER_MAX = 86400;
/** `PlayerNear` / `PlayerLeaveRange` 的 `rangeBlocks` 范围（方块；后端 `NEAR_MIN` / `NEAR_MAX`）。 */
export const NEAR_MIN = 1;
export const NEAR_MAX = 32;
/** `blockLayout` JSON 最大长度（null 当 `"{}"` 合法；后端 `BLOCK_LAYOUT_MAX`）。 */
export const BLOCK_LAYOUT_MAX = 65536;
/** `SetVariable.value` / `AppendVariable.text` 最大长度（后端 `SET_VALUE_MAX`）。 */
export const SET_VALUE_MAX = 4096;
/** `PlaySound.soundId` 最大长度（后端 `SOUND_ID_MAX`）。 */
export const SOUND_ID_MAX = 128;
/** `RunCommand.params` 每个值的最大长度（后端 `COMMAND_PARAM_MAX`）。 */
export const COMMAND_PARAM_MAX = 256;
/** `Log.message` 最大长度（后端 `LOG_MESSAGE_MAX`）。 */
export const LOG_MESSAGE_MAX = 256;
/** `If` / `RepeatUntil` / `WaitUntil` 的 `condition` 最大长度（后端 `CONDITION_MAX`）。 */
export const CONDITION_MAX = 512;
/** `PlaySound.volume` 范围（后端 `VOLUME_MIN` / `VOLUME_MAX`）。 */
export const VOLUME_MIN = 0.0;
export const VOLUME_MAX = 2.0;
/** `PlaySound.pitch` 范围（后端 `PITCH_MIN` / `PITCH_MAX`）。 */
export const PITCH_MIN = 0.5;
export const PITCH_MAX = 2.0;
/** `SendMessage.text` 最大长度（后端 `MESSAGE_MAX`）。 */
export const MESSAGE_MAX = 256;
/** `SetElementProperties.patch` 最大键数（后端 `PATCH_MAX_KEYS`）。 */
export const PATCH_MAX_KEYS = 8;
/** `SetElementProperties.kind` 最大长度（后端 `KIND_MAX`）。 */
export const KIND_MAX = 32;
/** `Repeat.count` / `RepeatUntil.maxIterations` 范围（后端 `REPEAT_MIN` / `REPEAT_MAX`）。 */
export const REPEAT_MIN = 1;
export const REPEAT_MAX = 100;
/** `RandomBranch.probability` 范围（百分比整数；后端 `PROBABILITY_MIN` / `PROBABILITY_MAX`）。 */
export const PROBABILITY_MIN = 0;
export const PROBABILITY_MAX = 100;
/** `ShowTitle.title` / `subtitle` 各自最大长度（后端 `TITLE_MAX`）。 */
export const TITLE_MAX = 256;
/** `ShowTitle.fadeInMs` / `fadeOutMs` 上限（毫秒；后端 `TITLE_FADE_MAX`）。 */
export const TITLE_FADE_MAX = 10000;
/** `ShowTitle.stayMs` 上限（毫秒；后端 `TITLE_STAY_MAX`）。 */
export const TITLE_STAY_MAX = 60000;
/** `TweenBlock.durationMs` 范围（毫秒；后端 `TWEEN_DURATION_MIN` / `TWEEN_DURATION_MAX`）。 */
export const TWEEN_DURATION_MIN = 1;
export const TWEEN_DURATION_MAX = 60000;
/** `PlayParticle.count` 范围（后端 `PARTICLE_COUNT_MIN` / `PARTICLE_COUNT_MAX`）。 */
export const PARTICLE_COUNT_MIN = 1;
export const PARTICLE_COUNT_MAX = 1000;
/** `WaitUntil.timeoutMs` 范围（毫秒；后端 `WAIT_UNTIL_TIMEOUT_MIN` / `WAIT_UNTIL_TIMEOUT_MAX`）。 */
export const WAITUNTIL_TIMEOUT_MIN = 50;
export const WAITUNTIL_TIMEOUT_MAX = 60000;

/** `SetElementProperty.property` / `SetElementProperties.patch` 键白名单（后端 `ELEMENT_PROPERTIES`）。 */
export const ELEMENT_PROPERTIES: ReadonlySet<string> = new Set([
    'x', 'y', 'w', 'h', 'rotation', 'opacity', 'text', 'fill', 'color',
]);
/** `PlayTimeline.op` 白名单（后端 `TIMELINE_OPS`）。 */
export const TIMELINE_OPS: ReadonlySet<string> = new Set(['play', 'pause', 'seek']);
/** `PlaySound.scope` 白名单（后端 `SOUND_SCOPES`）。 */
export const SOUND_SCOPES: ReadonlySet<string> = new Set(['near', 'all']);
/** `SendMessage.channel` 白名单（后端 `MESSAGE_CHANNELS`）。 */
export const MESSAGE_CHANNELS: ReadonlySet<string> = new Set(['chat', 'actionbar', 'title']);
/** `ScaleVariable.op` 白名单（后端 `SCALE_OPS`）。 */
export const SCALE_OPS: ReadonlySet<string> = new Set(['multiply', 'divide']);
/** `SendMessage.target` / `ShowTitle.target` 白名单（后端 `MESSAGE_TARGETS`）。 */
export const MESSAGE_TARGETS: ReadonlySet<string> = new Set(['trigger', 'all']);
/** `SetElementLayer.mode` 白名单（后端 `ELEMENT_LAYER_MODES`）。 */
export const ELEMENT_LAYER_MODES: ReadonlySet<string> = new Set(['front', 'back']);
/** `RoundVariable.mode` 白名单（后端 `ROUND_MODES`）。 */
export const ROUND_MODES: ReadonlySet<string> = new Set(['round', 'floor', 'ceil']);
/** `Easing.type` 合法值集合（对应 `EasingType`）。 */
export const VALID_EASING_TYPES: ReadonlySet<EasingType> = new Set<EasingType>([
    'linear', 'easeIn', 'easeOut', 'easeInOut', 'cubicBezier',
]);
/** `PlayParticle.particle` 白名单（14 项，镜像后端 `PARTICLE_WHITELIST`）。 */
export const PARTICLES: ReadonlySet<string> = new Set([
    'minecraft:flame', 'minecraft:smoke', 'minecraft:heart', 'minecraft:happy_villager',
    'minecraft:crit', 'minecraft:enchant', 'minecraft:portal', 'minecraft:firework',
    'minecraft:note', 'minecraft:cloud', 'minecraft:lava', 'minecraft:dripping_water',
    'minecraft:end_rod', 'minecraft:totem_of_undying',
]);

// ---------- 返回类型 ----------

/**
 * 单条校验错误。`blockId` 是积木树路径（`'trigger'` / `'actions/2'` / `'actions/2/then/1'`，
 * 与后端 trace `blockId` 同构），供 UI 定位到具体积木；规则级错误无 `blockId`。
 */
export interface ValidationError {
    blockId?: string;
    message: string;
}

// ---------- 主入口 ----------

/**
 * 校验整条规则，收集所有错误（空数组表示合法）。
 *
 * 检查顺序与后端 `ScriptRuleValidator.validate` 一致：
 * 规则名 -> 触发器存在 -> 动作列表非空 -> `blockLayout` 长度 -> 触发器字段 -> 积木总数 -> 逐个动作递归。
 */
export function validateRule(rule: ScriptRule, locale: 'zh' | 'en' = 'zh'): ValidationError[] {
    const v = messages[locale].script.validation;
    const errors: ValidationError[] = [];
    if (rule == null) {
        errors.push({ message: v.ruleNull });
        return errors;
    }

    if (rule.name == null || isBlank(rule.name)) {
        errors.push({ message: v.nameBlank });
    } else if (rule.name.length > NAME_MAX) {
        errors.push({ message: v.nameTooLong(NAME_MAX) });
    }

    if (rule.trigger == null) {
        errors.push({ blockId: 'trigger', message: v.missingTrigger });
    }

    if (!rule.actions || rule.actions.length === 0) {
        errors.push({ message: v.actionsEmpty });
    }

    if (rule.blockLayout != null && rule.blockLayout.length > BLOCK_LAYOUT_MAX) {
        errors.push({ message: v.layoutTooLong(BLOCK_LAYOUT_MAX) });
    }

    if (rule.trigger != null) {
        validateTrigger(rule.trigger, locale, errors);
    }

    if (rule.actions && rule.actions.length > 0) {
        const total = countBlocks(rule.actions);
        if (total > MAX_TOTAL_BLOCKS) {
            errors.push({ message: v.tooManyBlocks(total, MAX_TOTAL_BLOCKS) });
        }
        validateActions(rule.actions, 0, 'actions', locale, errors);
    }

    return errors;
}

// ---------- 触发器 ----------

/** 校验触发器各分支字段范围（`blockId` 恒为 `'trigger'`）。 */
function validateTrigger(
    trigger: ScriptTrigger,
    locale: 'zh' | 'en',
    errors: ValidationError[],
): void {
    const v = messages[locale].script.validation;
    validateIntegerFields(
        TRIGGER_DEFS[trigger.type]?.fields,
        trigger as unknown as Record<string, unknown>,
        'trigger',
        locale,
        errors,
    );
    switch (trigger.type) {
        case 'variableChange':
            if (isBlank(trigger.fullName)) {
                errors.push({ blockId: 'trigger', message: v.varChangeBlank });
            }
            break;
        case 'timer':
            if (trigger.intervalSeconds < TIMER_MIN || trigger.intervalSeconds > TIMER_MAX) {
                errors.push({
                    blockId: 'trigger',
                    message: v.timerRange(TIMER_MIN, TIMER_MAX),
                });
            }
            break;
        case 'playerNear':
            if (trigger.rangeBlocks < NEAR_MIN || trigger.rangeBlocks > NEAR_MAX) {
                errors.push({
                    blockId: 'trigger',
                    message: v.nearRange(NEAR_MIN, NEAR_MAX),
                });
            }
            break;
        case 'playerLeaveRange':
            if (trigger.rangeBlocks < NEAR_MIN || trigger.rangeBlocks > NEAR_MAX) {
                errors.push({
                    blockId: 'trigger',
                    message: v.leaveRange(NEAR_MIN, NEAR_MAX),
                });
            }
            break;
        case 'playerJoin':
        case 'playerKill':
        case 'wallReady':
        case 'rightClickWall':
        case 'playerQuit':
            break;
    }
}

// ---------- 动作（递归）----------

/**
 * 逐个动作校验并递归进子序列。`ifDepth` 为进入本层前已有的条件分支嵌套层数，
 * `seqPrefix` 为当前序列的 path 前缀（顶层 `'actions'` / 分支 `'actions/2/then'`）。
 */
function validateActions(
    actions: ScriptAction[],
    ifDepth: number,
    seqPrefix: string,
    locale: 'zh' | 'en',
    errors: ValidationError[],
): void {
    for (let i = 0; i < actions.length; i++) {
        const path = `${seqPrefix}/${i}`;
        validateAction(actions[i], ifDepth, path, locale, errors);
    }
}

/** 单个动作字段校验（镜像后端 `validateAction`）。 */
function validateAction(
    action: ScriptAction,
    ifDepth: number,
    path: string,
    locale: 'zh' | 'en',
    errors: ValidationError[],
): void {
    const v = messages[locale].script.validation;
    validateIntegerFields(
        ACTION_DEFS[action.type]?.fields,
        action as unknown as Record<string, unknown>,
        path,
        locale,
        errors,
    );
    switch (action.type) {
        case 'setVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: v.setVarNameBlank });
            }
            if (action.value == null) {
                errors.push({ blockId: path, message: v.setVarValueNull });
            } else if (action.value.length > SET_VALUE_MAX) {
                errors.push({ blockId: path, message: v.setVarValueTooLong(SET_VALUE_MAX) });
            }
            break;
        }
        case 'incrementVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: v.incVarNameBlank });
            }
            if (!Number.isFinite(action.delta)) {
                errors.push({ blockId: path, message: v.incDeltaFinite });
            }
            break;
        }
        case 'setElementProperty': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: v.setPropMissingEl });
            }
            if (action.property == null || !ELEMENT_PROPERTIES.has(action.property)) {
                errors.push({ blockId: path, message: v.setPropInvalid(action.property) });
            }
            if (isBlank(action.value)) {
                errors.push({ blockId: path, message: v.setPropValueBlank });
            }
            break;
        }
        case 'playTimeline': {
            if (isBlank(action.timelineId)) {
                errors.push({ blockId: path, message: v.timelineMissingId });
            }
            if (action.op == null || !TIMELINE_OPS.has(action.op)) {
                errors.push({ blockId: path, message: v.timelineInvalidOp(action.op) });
            }
            if (action.op === 'seek' && action.seekMs == null) {
                errors.push({ blockId: path, message: v.seekMissingMs });
            }
            if (action.seekMs != null && action.seekMs < 0) {
                errors.push({ blockId: path, message: v.seekNegative });
            }
            break;
        }
        case 'playSound': {
            if (isBlank(action.soundId) || action.soundId.length > SOUND_ID_MAX) {
                errors.push({ blockId: path, message: v.soundIdInvalid(SOUND_ID_MAX) });
            }
            // 取反区间写法，NaN 比较为 false 连带被拒（与后端一致）。
            if (!(action.volume >= VOLUME_MIN && action.volume <= VOLUME_MAX)) {
                errors.push({ blockId: path, message: v.soundVolumeRange(fmt(VOLUME_MIN), fmt(VOLUME_MAX)) });
            }
            if (!(action.pitch >= PITCH_MIN && action.pitch <= PITCH_MAX)) {
                errors.push({ blockId: path, message: v.soundPitchRange(fmt(PITCH_MIN), fmt(PITCH_MAX)) });
            }
            if (action.scope == null || !SOUND_SCOPES.has(action.scope)) {
                errors.push({ blockId: path, message: v.soundInvalidScope(action.scope) });
            }
            break;
        }
        case 'wait': {
            if (action.ms < WAIT_MIN || action.ms > WAIT_MAX) {
                errors.push({ blockId: path, message: v.waitRange(WAIT_MIN, WAIT_MAX) });
            }
            break;
        }
        case 'runCommand': {
            if (isBlank(action.templateId)) {
                errors.push({ blockId: path, message: v.cmdMissingTemplate });
            }
            const params = action.params ?? {};
            for (const key of Object.keys(params)) {
                const paramVal = params[key];
                if (typeof paramVal === 'string' && paramVal.length > COMMAND_PARAM_MAX) {
                    errors.push({
                        blockId: path,
                        message: v.cmdParamTooLong(key, COMMAND_PARAM_MAX),
                    });
                }
            }
            break;
        }
        case 'log': {
            if (action.message == null) {
                errors.push({ blockId: path, message: v.logNull });
            } else if (action.message.length > LOG_MESSAGE_MAX) {
                errors.push({ blockId: path, message: v.logTooLong(LOG_MESSAGE_MAX) });
            }
            break;
        }
        case 'if': {
            const depth = ifDepth + 1;
            if (depth > MAX_IF_DEPTH) {
                errors.push({
                    blockId: path,
                    message: v.ifDepthExceeded(MAX_IF_DEPTH, depth),
                });
            }
            if (isBlank(action.condition)) {
                errors.push({ blockId: path, message: v.ifConditionBlank });
            } else if (action.condition.length > CONDITION_MAX) {
                errors.push({ blockId: path, message: v.ifConditionTooLong(CONDITION_MAX) });
            }
            validateActions(action.then ?? [], depth, `${path}/then`, locale, errors);
            validateActions(action.else ?? [], depth, `${path}/else`, locale, errors);
            break;
        }
        case 'setElementProperties': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: v.setPropMissingEl });
            }
            const keys = Object.keys(action.patch ?? {});
            if (keys.length === 0) {
                errors.push({ blockId: path, message: v.patchEmpty });
            }
            if (keys.length > PATCH_MAX_KEYS) {
                errors.push({ blockId: path, message: v.patchTooManyKeys(PATCH_MAX_KEYS) });
            }
            if (action.kind != null && action.kind.length > KIND_MAX) {
                errors.push({ blockId: path, message: v.kindTooLong });
            }
            for (const [k, val] of Object.entries(action.patch ?? {})) {
                if (!ELEMENT_PROPERTIES.has(k)) {
                    errors.push({ blockId: path, message: v.setPropInvalid(k) });
                }
                // text 允许空串（后端 ElementPropertyApplier.buildPatch 接受空文字）；其余键空串拒绝。
                if (k !== 'text' && isBlank(val)) {
                    errors.push({ blockId: path, message: v.patchPropBlank(k) });
                }
            }
            break;
        }
        case 'nudgeElement': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: v.nudgeMissingEl });
            }
            if (!Number.isFinite(action.dx) || !Number.isFinite(action.dy)) {
                errors.push({ blockId: path, message: v.nudgeFinite });
            }
            break;
        }
        case 'sendMessage': {
            if (action.text == null) {
                errors.push({ blockId: path, message: v.msgNull });
            } else if (action.text.length > MESSAGE_MAX) {
                errors.push({ blockId: path, message: v.msgTooLong(MESSAGE_MAX) });
            }
            if (action.channel == null || !MESSAGE_CHANNELS.has(action.channel)) {
                errors.push({ blockId: path, message: v.msgInvalidChannel(action.channel) });
            }
            // 缺失（旧 payload）按默认 trigger 放行；非空且不在白名单才拒绝。
            if (action.target != null && !MESSAGE_TARGETS.has(action.target)) {
                errors.push({ blockId: path, message: v.msgInvalidTarget(action.target) });
            }
            break;
        }
        case 'setRandomVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: v.randVarNameBlank });
            }
            if (!Number.isFinite(action.min) || !Number.isFinite(action.max)) {
                errors.push({ blockId: path, message: v.randBoundsFinite });
            } else if (action.min > action.max) {
                errors.push({ blockId: path, message: v.randMinGtMax });
            }
            break;
        }
        case 'scaleVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: v.scaleVarNameBlank });
            }
            if (action.op == null || !SCALE_OPS.has(action.op)) {
                errors.push({ blockId: path, message: v.scaleInvalidOp(action.op) });
            }
            if (!Number.isFinite(action.factor)) {
                errors.push({ blockId: path, message: v.scaleFactorFinite });
            } else if (action.op === 'divide' && action.factor === 0) {
                errors.push({ blockId: path, message: v.scaleDivZero });
            }
            break;
        }
        case 'playTimelineAwait': {
            if (isBlank(action.timelineId)) {
                errors.push({ blockId: path, message: v.awaitTimelineMissingId });
            }
            break;
        }
        case 'repeat': {
            if (typeof action.count !== 'number' || action.count < REPEAT_MIN || action.count > REPEAT_MAX) {
                errors.push({ blockId: path, message: v.repeatCountRange(REPEAT_MIN, REPEAT_MAX) });
            }
            if (!action.body || action.body.length === 0) {
                errors.push({ blockId: path, message: v.repeatBodyEmpty });
            }
            validateActions(action.body ?? [], ifDepth, `${path}/body`, locale, errors);
            break;
        }
        case 'repeatUntil': {
            if (isBlank(action.condition) || action.condition.length > CONDITION_MAX) {
                errors.push({ blockId: path, message: v.repeatUntilConditionInvalid(CONDITION_MAX) });
            }
            if (typeof action.maxIterations !== 'number' || action.maxIterations < REPEAT_MIN || action.maxIterations > REPEAT_MAX) {
                errors.push({ blockId: path, message: v.repeatUntilMaxRange(REPEAT_MIN, REPEAT_MAX) });
            }
            if (!action.body || action.body.length === 0) {
                errors.push({ blockId: path, message: v.repeatBodyEmpty });
            }
            validateActions(action.body ?? [], ifDepth, `${path}/body`, locale, errors);
            break;
        }
        case 'stopScript':
            break;
        case 'playParticle': {
            if (action.particle == null || !PARTICLES.has(action.particle)) {
                errors.push({ blockId: path, message: v.particleInvalid(action.particle) });
            }
            if (typeof action.count !== 'number' || action.count < PARTICLE_COUNT_MIN || action.count > PARTICLE_COUNT_MAX) {
                errors.push({ blockId: path, message: v.particleCountRange(PARTICLE_COUNT_MIN, PARTICLE_COUNT_MAX) });
            }
            if (!Number.isFinite(action.offsetX) || !Number.isFinite(action.offsetY) || !Number.isFinite(action.offsetZ)) {
                errors.push({ blockId: path, message: v.particleOffsetFinite });
            }
            break;
        }
        case 'waitUntil': {
            if (isBlank(action.condition) || action.condition.length > CONDITION_MAX) {
                errors.push({ blockId: path, message: v.waitUntilConditionInvalid(CONDITION_MAX) });
            }
            if (typeof action.timeoutMs !== 'number' || action.timeoutMs < WAITUNTIL_TIMEOUT_MIN || action.timeoutMs > WAITUNTIL_TIMEOUT_MAX) {
                errors.push({ blockId: path, message: v.waitUntilTimeoutRange(WAITUNTIL_TIMEOUT_MIN, WAITUNTIL_TIMEOUT_MAX) });
            }
            break;
        }
        case 'copyVariable': {
            if (isBlank(action.target) || isBlank(action.source)) {
                errors.push({ blockId: path, message: v.copyVarBlank });
            }
            break;
        }
        case 'appendVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: v.appendVarBlank });
            }
            if (action.text != null && action.text.length > SET_VALUE_MAX) {
                errors.push({ blockId: path, message: v.appendTextTooLong(SET_VALUE_MAX) });
            }
            break;
        }
        case 'cloneElement': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: v.cloneMissingEl });
            }
            if (!Number.isFinite(action.offsetX) || !Number.isFinite(action.offsetY)) {
                errors.push({ blockId: path, message: v.cloneOffsetFinite });
            }
            break;
        }
        case 'deleteElement': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: v.deleteMissingEl });
            }
            break;
        }
        case 'randomBranch': {
            if (typeof action.probability !== 'number' || action.probability < PROBABILITY_MIN || action.probability > PROBABILITY_MAX) {
                errors.push({ blockId: path, message: v.probRange(PROBABILITY_MIN, PROBABILITY_MAX) });
            }
            const depth = ifDepth + 1;
            if (depth > MAX_IF_DEPTH) {
                errors.push({ blockId: path, message: v.branchDepthExceeded(MAX_IF_DEPTH, depth) });
            }
            validateActions(action.then ?? [], depth, `${path}/then`, locale, errors);
            validateActions(action.else ?? [], depth, `${path}/else`, locale, errors);
            break;
        }
        case 'setElementLayer': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: v.layerMissingEl });
            }
            if (action.mode == null || !ELEMENT_LAYER_MODES.has(action.mode)) {
                errors.push({ blockId: path, message: v.layerInvalidMode(action.mode) });
            }
            break;
        }
        case 'roundVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: v.roundVarBlank });
            }
            if (action.mode == null || !ROUND_MODES.has(action.mode)) {
                errors.push({ blockId: path, message: v.roundInvalidMode(action.mode) });
            }
            break;
        }
        case 'showTitle': {
            const titleBlank = isBlank(action.title);
            const subtitleBlank = isBlank(action.subtitle);
            if (titleBlank && subtitleBlank) {
                errors.push({ blockId: path, message: v.titleBothBlank });
            }
            if (!titleBlank && action.title.length > TITLE_MAX) {
                errors.push({ blockId: path, message: v.titleTooLong(TITLE_MAX) });
            }
            if (!subtitleBlank && action.subtitle.length > TITLE_MAX) {
                errors.push({ blockId: path, message: v.subtitleTooLong(TITLE_MAX) });
            }
            if (typeof action.fadeInMs !== 'number' || action.fadeInMs < 0 || action.fadeInMs > TITLE_FADE_MAX) {
                errors.push({ blockId: path, message: v.fadeInRange(TITLE_FADE_MAX) });
            }
            if (typeof action.stayMs !== 'number' || action.stayMs < 0 || action.stayMs > TITLE_STAY_MAX) {
                errors.push({ blockId: path, message: v.stayRange(TITLE_STAY_MAX) });
            }
            if (typeof action.fadeOutMs !== 'number' || action.fadeOutMs < 0 || action.fadeOutMs > TITLE_FADE_MAX) {
                errors.push({ blockId: path, message: v.fadeOutRange(TITLE_FADE_MAX) });
            }
            if (action.target == null || !MESSAGE_TARGETS.has(action.target)) {
                errors.push({ blockId: path, message: v.titleInvalidTarget(action.target) });
            }
            break;
        }
        case 'tweenBlock': {
            if (typeof action.durationMs !== 'number' || action.durationMs < TWEEN_DURATION_MIN || action.durationMs > TWEEN_DURATION_MAX) {
                errors.push({ blockId: path, message: v.tweenDurationRange(TWEEN_DURATION_MIN, TWEEN_DURATION_MAX) });
            }
            const easing = action.easing;
            if (easing == null || !VALID_EASING_TYPES.has(easing.type as EasingType)) {
                errors.push({ blockId: path, message: v.tweenInvalidEasing });
            } else if (easing.type === 'cubicBezier') {
                const b = easing.bezier;
                if (!b || b.length !== 4 || !(b[0] >= 0 && b[0] <= 1) || !(b[2] >= 0 && b[2] <= 1)) {
                    errors.push({ blockId: path, message: v.tweenInvalidBezier });
                }
            } else if (easing.bezier != null) {
                errors.push({ blockId: path, message: v.tweenUnexpectedBezier });
            }
            if (!action.body || action.body.length === 0) {
                errors.push({ blockId: path, message: v.tweenBodyEmpty });
            }
            for (let bi = 0; bi < (action.body ?? []).length; bi++) {
                const bAction = action.body[bi];
                const bPath = `${path}/body/${bi}`;
                if (bAction.type !== 'setElementProperties') {
                    errors.push({ blockId: bPath, message: v.tweenBodyInvalidAction });
                } else if (!TWEENABLE_KINDS.has(bAction.kind)) {
                    errors.push({ blockId: bPath, message: v.tweenBodyInvalidKind(bAction.kind) });
                }
            }
            validateActions(action.body ?? [], ifDepth, `${path}/body`, locale, errors);
            break;
        }
    }
}

// ---------- 内部辅助 ----------

/**
 * 数字字段整数性检查 —— 镜像后端 `ActionDeserializer.requireInt / requireLong / optionalLong`。
 *
 * 按 `blockDefs` 中 `FieldDef.integer` 声明检查，仅对已经是 `number` 的值判整（缺失 / null
 * 交由各字段必填校验；`NaN` 在此一并拦截）。
 */
function validateIntegerFields(
    fields: FieldDef[] | undefined,
    holder: Record<string, unknown>,
    blockId: string,
    locale: 'zh' | 'en',
    errors: ValidationError[],
): void {
    if (!fields) return;
    const v = messages[locale].script.validation;
    for (const f of fields) {
        if (f.integer !== true) continue;
        const val = holder[f.name];
        if (typeof val !== 'number' || Number.isInteger(val)) continue;
        errors.push({ blockId, message: v.fieldMustBeInteger(fieldLabel(f.labelKey, locale)) });
    }
}

/**
 * 取字段在指定语言下的显示标签（如 `'script.fields.ms'` -> `'毫秒'` / `'ms'`）。
 * 查不到时退回 `labelKey` 本身。
 */
function fieldLabel(labelKey: string, locale: 'zh' | 'en'): string {
    let cur: unknown = messages[locale];
    for (const seg of labelKey.split('.')) {
        if (cur === null || typeof cur !== 'object') return labelKey;
        cur = (cur as Record<string, unknown>)[seg];
    }
    return typeof cur === 'string' ? cur : labelKey;
}

/** 镜像后端 `blank(String)`：null / 全空白 -> true。 */
function isBlank(s: string | null | undefined): boolean {
    return s == null || s.trim().length === 0;
}

/**
 * 数值格式化：与 Java `Double.toString` 对齐（`0.0` -> `"0.0"`，`2.0` -> `"2.0"`，`0.5` -> `"0.5"`）。
 */
function fmt(n: number): string {
    return Number.isInteger(n) ? `${n}.0` : String(n);
}
