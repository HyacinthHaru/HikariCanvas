/**
 * 0.7.0-P5-H（K-UI-9）：前端 validator 镜像 —— 后端 {@code ScriptRuleValidator} 的逐字段复刻。
 *
 * <p><b>权威 = {@code plugin/src/main/java/moe/hikari/canvas/script/ScriptRuleValidator.java}</b>。
 * 本文件的常量（数值上下界 / 长度上限 / 白名单集合）必须与后端<b>逐一一致</b>——任一处漂移都会
 * 让"前端放行、后端打回"（或反之）。后端是唯一执行权威（决策 D5），前端这层只为<b>保存前预校验</b>
 * （K-UI-9）：拖完一拍就在编辑器里给红字提示 + 阻止 send，而不是等 server 回 {@code SCRIPT_INVALID}。</p>
 *
 * <p><b>与后端的两点形态差异（有意为之，语义不变）</b>：</p>
 * <ol>
 *   <li>后端 {@code validate} 是 <b>fail-fast</b>（返第一个错误的 {@code Optional<String>}）；前端
 *       {@link validateRule} <b>收集所有错误</b>返 {@link ValidationError}[]，让 UI 一次列全 + 每条
 *       带 {@code blockId}（积木树路径，与 trace blockId 同构）能点定位。但<b>每条错误的判定逻辑与文案
 *       与后端逐字一致</b>——只是从"短路"改成"全收"。</li>
 *   <li>错误文案直接复制后端中文串（同样的 "最多 N 字符" / "需在 A..B 之间" 措辞），保证用户在
 *       前端看到的提示与万一漏到后端时一致。</li>
 * </ol>
 *
 * <p>纯逻辑、零 Vue / 零 DOM。{@link validateRule} 只读 {@link ScriptRule}，不改入参。</p>
 */

import type { ScriptRule, ScriptTrigger, ScriptAction } from '@/types/protocol';

// ---------- 常量（逐一对照 ScriptRuleValidator.java，数值 / 集合必须一致）----------

/** 规则名最大长度。后端 {@code NAME_MAX}。 */
export const NAME_MAX = 64;
/** 积木总数上限（每个 Action 计 1，If 自身计 1 再加两分支递归）。后端 {@code MAX_TOTAL_BLOCKS}。 */
export const MAX_TOTAL_BLOCKS = 50;
/** if 嵌套深度上限（第 5 层拒）。后端 {@code MAX_IF_DEPTH}。 */
export const MAX_IF_DEPTH = 4;
/** Wait.ms 范围（毫秒）。后端 {@code WAIT_MIN} / {@code WAIT_MAX}。 */
export const WAIT_MIN = 50;
export const WAIT_MAX = 5000;
/** Timer.intervalSeconds 范围（秒）。后端 {@code TIMER_MIN} / {@code TIMER_MAX}。 */
export const TIMER_MIN = 1;
export const TIMER_MAX = 86400;
/** PlayerNear.rangeBlocks 范围（方块）。后端 {@code NEAR_MIN} / {@code NEAR_MAX}。 */
export const NEAR_MIN = 1;
export const NEAR_MAX = 32;
/** blockLayout JSON 最大长度（null 当 "{}" 合法）。后端 {@code BLOCK_LAYOUT_MAX}。 */
export const BLOCK_LAYOUT_MAX = 65536;
/** SetVariable.value 最大长度（空串合法、null 拒）。后端 {@code SET_VALUE_MAX}。 */
export const SET_VALUE_MAX = 4096;
/** PlaySound.soundId 最大长度。后端 {@code SOUND_ID_MAX}。 */
export const SOUND_ID_MAX = 128;
/** RunCommand.params 每个值的最大长度。后端 {@code COMMAND_PARAM_MAX}。 */
export const COMMAND_PARAM_MAX = 256;
/** Log.message 最大长度。后端 {@code LOG_MESSAGE_MAX}。 */
export const LOG_MESSAGE_MAX = 256;
/** If.condition 最大长度。后端 {@code CONDITION_MAX}。 */
export const CONDITION_MAX = 512;
/** PlaySound.volume 范围。后端 {@code VOLUME_MIN} / {@code VOLUME_MAX}。 */
export const VOLUME_MIN = 0.0;
export const VOLUME_MAX = 2.0;
/** PlaySound.pitch 范围。后端 {@code PITCH_MIN} / {@code PITCH_MAX}。 */
export const PITCH_MIN = 0.5;
export const PITCH_MAX = 2.0;

/** SetElementProperty.property 白名单（后端 {@code ELEMENT_PROPERTIES}，8 项）。 */
export const ELEMENT_PROPERTIES: ReadonlySet<string> = new Set([
    'x', 'y', 'w', 'h', 'rotation', 'opacity', 'text', 'fill',
]);
/** PlayTimeline.op 白名单（后端 {@code TIMELINE_OPS}）。 */
export const TIMELINE_OPS: ReadonlySet<string> = new Set(['play', 'pause', 'seek']);
/** PlaySound.scope 白名单（后端 {@code SOUND_SCOPES}）。 */
export const SOUND_SCOPES: ReadonlySet<string> = new Set(['near', 'all']);

// ---------- 0.7.1-P1 新动作常量（逐一对照 ScriptRuleValidator.java）----------

/** SendMessage.text 最大长度。后端 {@code MESSAGE_MAX}。 */
export const MESSAGE_MAX = 256;
/** SetElementProperties.patch 最大键数。后端 {@code PATCH_MAX_KEYS}。 */
export const PATCH_MAX_KEYS = 8;
/** SetElementProperties.kind 最大长度。后端 {@code KIND_MAX}。 */
export const KIND_MAX = 32;
/** SendMessage.channel 白名单。后端 {@code MESSAGE_CHANNELS}。 */
export const MESSAGE_CHANNELS: ReadonlySet<string> = new Set(['chat', 'actionbar', 'title']);
/** ScaleVariable.op 白名单。后端 {@code SCALE_OPS}。 */
export const SCALE_OPS: ReadonlySet<string> = new Set(['multiply', 'divide']);

// ---------- 0.7.1-P2 repeat 常量（逐一对照 ScriptRuleValidator.java）----------

/** Repeat.count 范围（重复次数）。后端 {@code REPEAT_MIN} / {@code REPEAT_MAX}。 */
export const REPEAT_MIN = 1;
export const REPEAT_MAX = 100;

// ---------- 0.7.1-P5 常量（停止 / 粒子 / 等待直到，逐一对照 ScriptRuleValidator.java）----------

/** PlayParticle.count 范围。后端 {@code PARTICLE_COUNT_MIN} / {@code PARTICLE_COUNT_MAX}。 */
export const PARTICLE_COUNT_MIN = 1;
export const PARTICLE_COUNT_MAX = 1000;
/** WaitUntil.timeoutMs 范围（毫秒）。后端 {@code WAIT_UNTIL_TIMEOUT_MIN} / {@code WAIT_UNTIL_TIMEOUT_MAX}。 */
export const WAITUNTIL_TIMEOUT_MIN = 50;
export const WAITUNTIL_TIMEOUT_MAX = 60000;
/**
 * PlayParticle.particle 白名单（14 个，逐字镜像后端 {@code PARTICLE_WHITELIST}）。
 * wire 字段名是 {@code particle}（与后端 record 字段同名）。
 */
export const PARTICLES: ReadonlySet<string> = new Set([
    'minecraft:flame', 'minecraft:smoke', 'minecraft:heart', 'minecraft:happy_villager',
    'minecraft:crit', 'minecraft:enchant', 'minecraft:portal', 'minecraft:firework',
    'minecraft:note', 'minecraft:cloud', 'minecraft:lava', 'minecraft:dripping_water',
    'minecraft:end_rod', 'minecraft:totem_of_undying',
]);

// ---------- 返回类型 ----------

/**
 * 单条校验错误。{@code blockId} 是积木树路径（{@code 'trigger'} / {@code 'actions/2'} /
 * {@code 'actions/2/then/1'}，与后端 trace blockId 同构）——UI 用它点定位到具体积木。
 * 规则级错误（名称 / 动作列表空 / 总块数超 / blockLayout 超长）无 blockId（{@code undefined}）。
 * {@code message} 是人读中文（与后端文案逐字一致）。
 */
export interface ValidationError {
    blockId?: string;
    message: string;
}

// ---------- 主入口 ----------

/**
 * 校验整条规则，收集<b>所有</b>错误（空数组 = 合法）。
 *
 * <p>检查顺序与后端 {@code ScriptRuleValidator.validate} 一致（只是不短路、全收集）：
 * 规则名 → 触发器存在 → 动作列表非空 → blockLayout 长度 → 触发器字段 → 积木总数 →
 * 逐个动作递归。每条动作 / 触发器的字段判定与后端文案逐字相同。</p>
 */
export function validateRule(rule: ScriptRule): ValidationError[] {
    const errors: ValidationError[] = [];
    if (rule == null) {
        errors.push({ message: '规则不能为空' });
        return errors;
    }

    // 规则名（后端：blank → "规则名称不能为空"；超长 → "规则名称超长（最多 N 字符）"）。
    if (rule.name == null || isBlank(rule.name)) {
        errors.push({ message: '规则名称不能为空' });
    } else if (rule.name.length > NAME_MAX) {
        errors.push({ message: `规则名称超长（最多 ${NAME_MAX} 字符）` });
    }

    // 触发器存在性（后端：trigger == null → "缺少触发器"）。
    if (rule.trigger == null) {
        errors.push({ blockId: 'trigger', message: '缺少触发器' });
    }

    // 动作列表非空（后端：actions.isEmpty → "动作列表不能为空"）。
    if (!rule.actions || rule.actions.length === 0) {
        errors.push({ message: '动作列表不能为空' });
    }

    // blockLayout 长度（后端：null 当 "{}" 合法）。
    if (rule.blockLayout != null && rule.blockLayout.length > BLOCK_LAYOUT_MAX) {
        errors.push({ message: `积木布局数据超长（最多 ${BLOCK_LAYOUT_MAX} 字符）` });
    }

    // 触发器字段范围。
    if (rule.trigger != null) {
        validateTrigger(rule.trigger, errors);
    }

    // 积木总数（后端：countBlocks > MAX → "积木总数 N 超过上限 M"）。
    if (rule.actions && rule.actions.length > 0) {
        const total = countBlocks(rule.actions);
        if (total > MAX_TOTAL_BLOCKS) {
            errors.push({ message: `积木总数 ${total} 超过上限 ${MAX_TOTAL_BLOCKS}` });
        }
        // 逐个动作递归（ifDepth 从 0 起，与后端一致）。
        validateActions(rule.actions, 0, 'actions', errors);
    }

    return errors;
}

// ---------- 触发器 ----------

/** 触发器各子类字段范围（镜像后端 validateTrigger，文案逐字一致）。blockId 恒为 'trigger'。 */
function validateTrigger(trigger: ScriptTrigger, errors: ValidationError[]): void {
    switch (trigger.type) {
        case 'variableChange':
            if (isBlank(trigger.fullName)) {
                errors.push({ blockId: 'trigger', message: '变量变化触发器的变量名不能为空' });
            }
            break;
        case 'timer':
            if (trigger.intervalSeconds < TIMER_MIN || trigger.intervalSeconds > TIMER_MAX) {
                errors.push({
                    blockId: 'trigger',
                    message: `定时器间隔需在 ${TIMER_MIN}..${TIMER_MAX} 秒之间`,
                });
            }
            break;
        case 'playerNear':
            if (trigger.rangeBlocks < NEAR_MIN || trigger.rangeBlocks > NEAR_MAX) {
                errors.push({
                    blockId: 'trigger',
                    message: `玩家靠近半径需在 ${NEAR_MIN}..${NEAR_MAX} 方块之间`,
                });
            }
            break;
        // 0.7.1-P2：playerLeaveRange 范围校验同 playerNear（复用 NEAR_MIN/MAX），文案"玩家离开半径"。
        case 'playerLeaveRange':
            if (trigger.rangeBlocks < NEAR_MIN || trigger.rangeBlocks > NEAR_MAX) {
                errors.push({
                    blockId: 'trigger',
                    message: `玩家离开半径需在 ${NEAR_MIN}..${NEAR_MAX} 方块之间`,
                });
            }
            break;
        // 无字段触发器（后端 Optional.empty）。0.7.1-P2 加 rightClickWall / playerQuit。
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
 * 逐个动作校验 + 递归进 if 分支。{@code ifDepth} = 进入本层前已有的 if 嵌套层数（镜像后端）。
 * {@code seqPrefix} 是当前序列的 path 前缀（顶层 {@code 'actions'} / if 分支
 * {@code 'actions/2/then'}），与后端 trace blockId 同构——拼下标即每块的 blockId。
 */
function validateActions(
    actions: ScriptAction[],
    ifDepth: number,
    seqPrefix: string,
    errors: ValidationError[],
): void {
    for (let i = 0; i < actions.length; i++) {
        const path = `${seqPrefix}/${i}`;
        validateAction(actions[i], ifDepth, path, errors);
    }
}

/** 单个动作字段校验（镜像后端 validateAction switch，逐分支文案一致）。 */
function validateAction(
    action: ScriptAction,
    ifDepth: number,
    path: string,
    errors: ValidationError[],
): void {
    switch (action.type) {
        case 'setVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: '设置变量的变量名不能为空' });
            }
            // 后端：value == null → "...不能为 null（空串合法）"；空串放行。
            if (action.value == null) {
                errors.push({ blockId: path, message: '设置变量的值不能为 null（空串合法）' });
            } else if (action.value.length > SET_VALUE_MAX) {
                errors.push({ blockId: path, message: `设置变量的值超长（最多 ${SET_VALUE_MAX} 字符）` });
            }
            break;
        }
        case 'incrementVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: '累加变量的变量名不能为空' });
            }
            // 后端：!Double.isFinite(delta) → "累加步长必须是有限数值"。
            if (!Number.isFinite(action.delta)) {
                errors.push({ blockId: path, message: '累加步长必须是有限数值' });
            }
            break;
        }
        case 'setElementProperty': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: '设置元素属性缺少元素 ID' });
            }
            if (action.property == null || !ELEMENT_PROPERTIES.has(action.property)) {
                errors.push({ blockId: path, message: `元素属性不在允许范围：${action.property}` });
            }
            if (isBlank(action.value)) {
                errors.push({ blockId: path, message: '设置元素属性的值不能为空' });
            }
            break;
        }
        case 'playTimeline': {
            if (isBlank(action.timelineId)) {
                errors.push({ blockId: path, message: '时间轴控制缺少时间轴 ID' });
            }
            if (action.op == null || !TIMELINE_OPS.has(action.op)) {
                errors.push({ blockId: path, message: `时间轴操作不在允许范围：${action.op}` });
            }
            if (action.op === 'seek' && action.seekMs == null) {
                errors.push({ blockId: path, message: 'seek 操作必须提供 seekMs' });
            }
            if (action.seekMs != null && action.seekMs < 0) {
                errors.push({ blockId: path, message: 'seekMs 不能为负数' });
            }
            break;
        }
        case 'playSound': {
            if (isBlank(action.soundId) || action.soundId.length > SOUND_ID_MAX) {
                errors.push({ blockId: path, message: `声音 ID 不能为空且最多 ${SOUND_ID_MAX} 字符` });
            }
            // 后端取反区间写法：NaN 任何比较都 false 连带被拒（finite 纪律）——!(v>=min && v<=max)。
            if (!(action.volume >= VOLUME_MIN && action.volume <= VOLUME_MAX)) {
                errors.push({ blockId: path, message: `音量需在 ${fmt(VOLUME_MIN)}..${fmt(VOLUME_MAX)} 之间` });
            }
            if (!(action.pitch >= PITCH_MIN && action.pitch <= PITCH_MAX)) {
                errors.push({ blockId: path, message: `音调需在 ${fmt(PITCH_MIN)}..${fmt(PITCH_MAX)} 之间` });
            }
            if (action.scope == null || !SOUND_SCOPES.has(action.scope)) {
                errors.push({ blockId: path, message: `声音范围不在允许范围：${action.scope}` });
            }
            break;
        }
        case 'wait': {
            if (action.ms < WAIT_MIN || action.ms > WAIT_MAX) {
                errors.push({ blockId: path, message: `等待时长需在 ${WAIT_MIN}..${WAIT_MAX} 毫秒之间` });
            }
            break;
        }
        case 'runCommand': {
            if (isBlank(action.templateId)) {
                errors.push({ blockId: path, message: '执行命令缺少模板 ID' });
            }
            // 后端：Map.copyOf 保证 value 非 null，只查长度。
            const params = action.params ?? {};
            for (const key of Object.keys(params)) {
                const v = params[key];
                if (typeof v === 'string' && v.length > COMMAND_PARAM_MAX) {
                    errors.push({
                        blockId: path,
                        message: `命令参数 '${key}' 超长（最多 ${COMMAND_PARAM_MAX} 字符）`,
                    });
                }
            }
            break;
        }
        case 'log': {
            // 后端：message == null → "日志内容不能为 null"；超长另报。空串合法。
            if (action.message == null) {
                errors.push({ blockId: path, message: '日志内容不能为 null' });
            } else if (action.message.length > LOG_MESSAGE_MAX) {
                errors.push({ blockId: path, message: `日志内容超长（最多 ${LOG_MESSAGE_MAX} 字符）` });
            }
            break;
        }
        case 'if': {
            const depth = ifDepth + 1;
            if (depth > MAX_IF_DEPTH) {
                errors.push({
                    blockId: path,
                    message: `if 嵌套超过 ${MAX_IF_DEPTH} 层（depth=${depth}）`,
                });
            }
            if (isBlank(action.condition)) {
                errors.push({ blockId: path, message: 'if 条件不能为空' });
            } else if (action.condition.length > CONDITION_MAX) {
                errors.push({ blockId: path, message: `if 条件超长（最多 ${CONDITION_MAX} 字符）` });
            }
            // 递归两分支（depth 已 +1，与后端一致）。
            validateActions(action.then ?? [], depth, `${path}/then`, errors);
            validateActions(action.else ?? [], depth, `${path}/else`, errors);
            break;
        }
        // ---- 0.7.1-P1：6 个新 action（文案与后端 ScriptRuleValidator 逐字一致）----
        case 'setElementProperties': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: '设置元素属性缺少元素 ID' });
            }
            const keys = Object.keys(action.patch ?? {});
            if (keys.length === 0) {
                errors.push({ blockId: path, message: '批量设属性的 patch 不能为空' });
            }
            if (keys.length > PATCH_MAX_KEYS) {
                errors.push({ blockId: path, message: `patch 属性数超过 ${PATCH_MAX_KEYS}` });
            }
            if (action.kind != null && action.kind.length > KIND_MAX) {
                errors.push({ blockId: path, message: 'kind 超长' });
            }
            for (const [k, v] of Object.entries(action.patch ?? {})) {
                if (!ELEMENT_PROPERTIES.has(k)) {
                    errors.push({ blockId: path, message: `元素属性不在允许范围：${k}` });
                }
                // text 空串是合法内容（后端 ElementPropertyApplier.buildPatch 接受空文字）；
                // 其余键空串会静默变 0 或 hex 失败，仍查空——与后端逐字一致。
                if (k !== 'text' && isBlank(v)) {
                    errors.push({ blockId: path, message: `属性 ${k} 的值不能为空` });
                }
            }
            break;
        }
        case 'nudgeElement': {
            if (isBlank(action.elementId)) {
                errors.push({ blockId: path, message: '相对移动缺少元素 ID' });
            }
            if (!Number.isFinite(action.dx) || !Number.isFinite(action.dy)) {
                errors.push({ blockId: path, message: '相对移动的 dx/dy 必须是有限数值' });
            }
            break;
        }
        case 'sendMessage': {
            if (action.text == null) {
                errors.push({ blockId: path, message: '发消息内容不能为 null' });
            } else if (action.text.length > MESSAGE_MAX) {
                errors.push({ blockId: path, message: `发消息内容超长（最多 ${MESSAGE_MAX}）` });
            }
            if (action.channel == null || !MESSAGE_CHANNELS.has(action.channel)) {
                errors.push({ blockId: path, message: `消息渠道不在允许范围：${action.channel}` });
            }
            break;
        }
        case 'setRandomVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: '随机数变量名不能为空' });
            }
            if (!Number.isFinite(action.min) || !Number.isFinite(action.max)) {
                errors.push({ blockId: path, message: '随机区间必须是有限数值' });
            } else if (action.min > action.max) {
                errors.push({ blockId: path, message: '随机区间 min 不能大于 max' });
            }
            break;
        }
        case 'scaleVariable': {
            if (isBlank(action.fullName)) {
                errors.push({ blockId: path, message: '乘除变量名不能为空' });
            }
            if (action.op == null || !SCALE_OPS.has(action.op)) {
                errors.push({ blockId: path, message: `运算不在允许范围：${action.op}` });
            }
            if (!Number.isFinite(action.factor)) {
                errors.push({ blockId: path, message: '乘除系数必须是有限数值' });
            } else if (action.op === 'divide' && action.factor === 0) {
                errors.push({ blockId: path, message: '除数不能为 0' });
            }
            break;
        }
        case 'playTimelineAwait': {
            if (isBlank(action.timelineId)) {
                errors.push({ blockId: path, message: '播时间轴缺少时间轴 ID' });
            }
            break;
        }
        // ---- 0.7.1-P2：repeat 有界循环（文案与后端 ScriptRuleValidator 逐字一致）----
        case 'repeat': {
            if (typeof action.count !== 'number' || action.count < REPEAT_MIN || action.count > REPEAT_MAX) {
                errors.push({ blockId: path, message: `重复次数需在 ${REPEAT_MIN}..${REPEAT_MAX} 之间` });
            }
            if (!action.body || action.body.length === 0) {
                errors.push({ blockId: path, message: '重复循环体不能为空' });
            }
            // body 递归（ifDepth 不变——repeat 不增 if 深度）；blockId 路径 `${path}/body`
            // 与后端 ScriptRunner 展开同构（不带 round，前端只一份 body）。
            validateActions(action.body ?? [], ifDepth, `${path}/body`, errors);
            break;
        }
        // ---- 0.7.1-P5：停止 / 粒子 / 等待直到（文案与后端 ScriptRuleValidator 逐字一致）----
        case 'stopScript':
            // 无字段——永远合法（后端 Optional.empty）。
            break;
        case 'playParticle': {
            if (action.particle == null || !PARTICLES.has(action.particle)) {
                errors.push({ blockId: path, message: `粒子种类不在允许范围: ${action.particle}` });
            }
            if (typeof action.count !== 'number' || action.count < PARTICLE_COUNT_MIN || action.count > PARTICLE_COUNT_MAX) {
                errors.push({ blockId: path, message: `粒子数量需在 ${PARTICLE_COUNT_MIN}..${PARTICLE_COUNT_MAX} 之间` });
            }
            if (!Number.isFinite(action.offsetX) || !Number.isFinite(action.offsetY) || !Number.isFinite(action.offsetZ)) {
                errors.push({ blockId: path, message: '粒子偏移必须是有限数值' });
            }
            break;
        }
        case 'waitUntil': {
            if (isBlank(action.condition) || action.condition.length > CONDITION_MAX) {
                errors.push({ blockId: path, message: `等待条件不能为空且最多 ${CONDITION_MAX} 字符` });
            }
            if (typeof action.timeoutMs !== 'number' || action.timeoutMs < WAITUNTIL_TIMEOUT_MIN || action.timeoutMs > WAITUNTIL_TIMEOUT_MAX) {
                errors.push({ blockId: path, message: `超时时长需在 ${WAITUNTIL_TIMEOUT_MIN}..${WAITUNTIL_TIMEOUT_MAX} 毫秒之间` });
            }
            break;
        }
    }
}

// ---------- 内部辅助 ----------

/**
 * 镜像后端 {@code countBlocks}：每个动作计 1；if 自身计 1 再加 then + else 递归；
 * 0.7.1-P2 repeat 自身计 1 再加 body 递归——<b>不乘 count</b>（硬限 50 是积木树节点数，
 * 不是展开后的执行动作数；展开数超 50 靠运行时 Budget 熔断，§9 决策）。
 */
function countBlocks(actions: ScriptAction[]): number {
    let count = 0;
    for (const action of actions) {
        count++;
        if (action.type === 'if') {
            count += countBlocks(action.then ?? []) + countBlocks(action.else ?? []);
        } else if (action.type === 'repeat') {
            count += countBlocks(action.body ?? []);
        }
    }
    return count;
}

/** 镜像后端 {@code blank(String)}：null / 全空白 → true。 */
function isBlank(s: string | null | undefined): boolean {
    return s == null || s.trim().length === 0;
}

/**
 * 数值格式化：与 Java {@code Double.toString} 的整数 / 小数呈现对齐
 * （{@code 0.0} → "0.0"，{@code 2.0} → "2.0"，{@code 0.5} → "0.5"）。
 * 后端文案是 "音量需在 0.0..2.0 之间" / "音调需在 0.5..2.0 之间"，需保留 ".0" 尾缀。
 */
function fmt(n: number): string {
    return Number.isInteger(n) ? `${n}.0` : String(n);
}
