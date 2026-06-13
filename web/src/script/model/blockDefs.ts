/**
 * 0.7.0-P4-C：积木声明式定义（K-UI 配色 + 字段 schema）。
 *
 * <p>每种触发器 / 动作对应一个 {@link BlockDef}：kind 判别 + category（决定配色）+
 * label i18n key + {@link FieldDef}[] 字段表。<b>字段顺序 = 积木内表单顺序</b>；字段逐一
 * 对应 wire 数据字段（权威：{@code web/src/types/protocol.ts} 的 ScriptTrigger / ScriptAction）。</p>
 *
 * <p><b>本阶段（C）参数槽渲染占位</b>——只显字段名 + 原始值文本；真表单控件（按 type 渲染
 * number/select/variable/...）留任务 F。故这里只声明 {@code type} + 取值范围 / select options
 * 等元数据，{@link BlockNode.vue} 暂只用 {@code name}/{@code labelKey} 读原始值。</p>
 *
 * <p><b>category → 配色（colorVar，Catppuccin token 名）</b>：</p>
 * <ul>
 *   <li>{@code trigger}（触发器帽子）= {@code --ctp-peach}；</li>
 *   <li>{@code action}（普通动作）= {@code --ctp-blue}；</li>
 *   <li>{@code control}（{@code if} 条件分支）= {@code --ctp-green}；</li>
 *   <li>{@code danger}（{@code runCommand} 执行命令）= {@code --ctp-red}；</li>
 *   <li>{@code timeline}（{@code playTimeline} 播时间轴）= {@code --ctp-mauve}。</li>
 * </ul>
 */

import type { ScriptAction, ScriptTrigger } from '@/types/protocol';

/**
 * 参数字段类型。{@code statements} 是 if 的 then/else 子序列槽（不是表单控件，由
 * BlockNode 递归渲染子积木）；{@code condition} 是条件构建器（任务 G）。其余为
 * 任务 F 的真表单控件类型。
 */
export type FieldType =
    | 'number'
    | 'text'
    | 'select'
    | 'variable'
    | 'timeline'
    | 'element'
    | 'sound'
    | 'command'
    | 'scope'
    | 'op'
    | 'condition'
    | 'statements';

/** select 选项（value 上 wire，labelKey 指向 i18n 友好文案）。 */
export interface FieldOption {
    value: string;
    labelKey: string;
}

/**
 * 单个参数字段定义。{@code name} = wire 字段名（写回 ScriptAction/Trigger 用）；
 * {@code labelKey} 指向 i18n（积木内字段标签）。number 用 {@code min}/{@code max}/
 * {@code step}（镜像后端 validator 范围）；select 用 {@code options}；{@code optional}
 * 标记可省字段（如 {@code playTimeline.seekMs} 仅 op=seek 携带）。
 */
export interface FieldDef {
    name: string;
    type: FieldType;
    labelKey: string;
    options?: FieldOption[];
    min?: number;
    max?: number;
    step?: number;
    optional?: boolean;
}

/** 积木块分类——决定配色（colorVar）与语义分组（palette 也按此分组）。 */
export type BlockCategory = 'trigger' | 'action' | 'control' | 'danger' | 'timeline';

/** 一种积木的完整声明（kind 判别 + 配色 + label + 字段表）。 */
export interface BlockDef {
    kind: string;
    category: BlockCategory;
    /** Catppuccin token 名（CSS 变量名，含 {@code --} 前缀），由 category 决定。 */
    colorVar: string;
    labelKey: string;
    fields: FieldDef[];
}

/** category → colorVar 单一映射（BlockDef.colorVar 由此派生，保证一致）。 */
export const CATEGORY_COLOR_VAR: Record<BlockCategory, string> = {
    trigger: '--ctp-peach',
    action: '--ctp-blue',
    control: '--ctp-green',
    danger: '--ctp-red',
    timeline: '--ctp-mauve',
};

// ---------- select / toggle 选项（与后端 validator 白名单同口径）----------

/** PlayTimeline.op 白名单（镜像后端 TIMELINE_OPS）。 */
const TIMELINE_OP_OPTIONS: FieldOption[] = [
    { value: 'play', labelKey: 'script.fieldOptions.opPlay' },
    { value: 'pause', labelKey: 'script.fieldOptions.opPause' },
    { value: 'seek', labelKey: 'script.fieldOptions.opSeek' },
];

/** PlaySound.scope 白名单（镜像后端 SOUND_SCOPES）。 */
const SOUND_SCOPE_OPTIONS: FieldOption[] = [
    { value: 'near', labelKey: 'script.fieldOptions.scopeNear' },
    { value: 'all', labelKey: 'script.fieldOptions.scopeAll' },
];

/** SetElementProperty.property 白名单（镜像后端 ELEMENT_PROPERTIES，8 项）。 */
const ELEMENT_PROPERTY_OPTIONS: FieldOption[] = [
    { value: 'x', labelKey: 'script.fieldOptions.propX' },
    { value: 'y', labelKey: 'script.fieldOptions.propY' },
    { value: 'w', labelKey: 'script.fieldOptions.propW' },
    { value: 'h', labelKey: 'script.fieldOptions.propH' },
    { value: 'rotation', labelKey: 'script.fieldOptions.propRotation' },
    { value: 'opacity', labelKey: 'script.fieldOptions.propOpacity' },
    { value: 'text', labelKey: 'script.fieldOptions.propText' },
    { value: 'fill', labelKey: 'script.fieldOptions.propFill' },
];

/** 0.7.1：SendMessage.channel 白名单（镜像后端 MESSAGE_CHANNELS）。 */
const MESSAGE_CHANNEL_OPTIONS: FieldOption[] = [
    { value: 'chat', labelKey: 'script.fieldOptions.channelChat' },
    { value: 'actionbar', labelKey: 'script.fieldOptions.channelActionbar' },
    { value: 'title', labelKey: 'script.fieldOptions.channelTitle' },
];

/** 0.7.1：ScaleVariable.op 白名单（镜像后端 SCALE_OPS）。 */
const SCALE_OP_OPTIONS: FieldOption[] = [
    { value: 'multiply', labelKey: 'script.fieldOptions.opMultiply' },
    { value: 'divide', labelKey: 'script.fieldOptions.opDivide' },
];

/**
 * 0.7.1-P5：PlayParticle.particle 白名单（14 个，逐字镜像后端 ScriptRuleValidator 的
 * {@code PARTICLE_WHITELIST}）。value 上 wire（{@code minecraft:xxx}），与后端集合<b>逐字符一致</b>。
 */
const PARTICLE_OPTIONS: FieldOption[] = [
    { value: 'minecraft:flame', labelKey: 'script.fieldOptions.particleFlame' },
    { value: 'minecraft:smoke', labelKey: 'script.fieldOptions.particleSmoke' },
    { value: 'minecraft:heart', labelKey: 'script.fieldOptions.particleHeart' },
    { value: 'minecraft:happy_villager', labelKey: 'script.fieldOptions.particleHappy' },
    { value: 'minecraft:crit', labelKey: 'script.fieldOptions.particleCrit' },
    { value: 'minecraft:enchant', labelKey: 'script.fieldOptions.particleEnchant' },
    { value: 'minecraft:portal', labelKey: 'script.fieldOptions.particlePortal' },
    { value: 'minecraft:firework', labelKey: 'script.fieldOptions.particleFirework' },
    { value: 'minecraft:note', labelKey: 'script.fieldOptions.particleNote' },
    { value: 'minecraft:cloud', labelKey: 'script.fieldOptions.particleCloud' },
    { value: 'minecraft:lava', labelKey: 'script.fieldOptions.particleLava' },
    { value: 'minecraft:dripping_water', labelKey: 'script.fieldOptions.particleDripWater' },
    { value: 'minecraft:end_rod', labelKey: 'script.fieldOptions.particleEndRod' },
    { value: 'minecraft:totem_of_undying', labelKey: 'script.fieldOptions.particleTotem' },
];

/**
 * 九种触发器定义（kind ∈ ScriptTrigger.type，0.7.0 六种 + 0.7.1-P2 三种）。
 * 字段覆盖各 wire 数据字段：variableChange→fullName / timer→intervalSeconds /
 * playerNear→rangeBlocks / playerLeaveRange→rangeBlocks；playerJoin / playerKill /
 * wallReady / rightClickWall / playerQuit 无数据字段。
 */
export const TRIGGER_DEFS: Record<string, BlockDef> = {
    variableChange: {
        kind: 'variableChange',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.variableChange',
        fields: [
            { name: 'fullName', type: 'variable', labelKey: 'script.fields.fullName' },
        ],
    },
    timer: {
        kind: 'timer',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.timer',
        fields: [
            {
                name: 'intervalSeconds',
                type: 'number',
                labelKey: 'script.fields.intervalSeconds',
                min: 1,
                max: 86400,
                step: 1,
            },
        ],
    },
    playerJoin: {
        kind: 'playerJoin',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.playerJoin',
        fields: [],
    },
    playerKill: {
        kind: 'playerKill',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.playerKill',
        fields: [],
    },
    playerNear: {
        kind: 'playerNear',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.playerNear',
        fields: [
            {
                name: 'rangeBlocks',
                type: 'number',
                labelKey: 'script.fields.rangeBlocks',
                min: 1,
                max: 32,
                step: 1,
            },
        ],
    },
    wallReady: {
        kind: 'wallReady',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.wallReady',
        fields: [],
    },
    // ---- 0.7.1-P2：3 个新触发器（右键墙 / 玩家离开靠近区域 / 玩家退服）----
    rightClickWall: {
        kind: 'rightClickWall',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.rightClickWall',
        fields: [],
    },
    playerLeaveRange: {
        kind: 'playerLeaveRange',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.playerLeaveRange',
        fields: [
            {
                name: 'rangeBlocks',
                type: 'number',
                labelKey: 'script.fields.rangeBlocks',
                min: 1,
                max: 32,
                step: 1,
            },
        ],
    },
    playerQuit: {
        kind: 'playerQuit',
        category: 'trigger',
        colorVar: CATEGORY_COLOR_VAR.trigger,
        labelKey: 'script.blocks.playerQuit',
        fields: [],
    },
};

/**
 * 动作定义（kind ∈ ScriptAction.type）：0.7.0 8 动作 + if，0.7.1-P1 5 新动作，0.7.1-P2 repeat。
 * 字段逐一对应 wire 数据字段；if 用 condition + then/else、repeat 用 count + body（statements 子序列槽）。
 * playTimeline.seekMs 标 optional（仅 op=seek 携带）；runCommand.params 是动态键值
 * （type=command，由所选模板的 params 驱动子输入，任务 F）。
 */
export const ACTION_DEFS: Record<string, BlockDef> = {
    setVariable: {
        kind: 'setVariable',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.setVariable',
        fields: [
            { name: 'fullName', type: 'variable', labelKey: 'script.fields.fullName' },
            { name: 'value', type: 'text', labelKey: 'script.fields.value' },
        ],
    },
    incrementVariable: {
        kind: 'incrementVariable',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.incrementVariable',
        fields: [
            { name: 'fullName', type: 'variable', labelKey: 'script.fields.fullName' },
            { name: 'delta', type: 'number', labelKey: 'script.fields.delta', step: 1 },
        ],
    },
    setElementProperty: {
        kind: 'setElementProperty',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.setElementProperty',
        fields: [
            { name: 'elementId', type: 'element', labelKey: 'script.fields.elementId' },
            {
                name: 'property',
                type: 'select',
                labelKey: 'script.fields.property',
                options: ELEMENT_PROPERTY_OPTIONS,
            },
            { name: 'value', type: 'text', labelKey: 'script.fields.value' },
        ],
    },
    playTimeline: {
        kind: 'playTimeline',
        category: 'timeline',
        colorVar: CATEGORY_COLOR_VAR.timeline,
        labelKey: 'script.blocks.playTimeline',
        fields: [
            { name: 'timelineId', type: 'timeline', labelKey: 'script.fields.timelineId' },
            { name: 'op', type: 'op', labelKey: 'script.fields.op', options: TIMELINE_OP_OPTIONS },
            {
                name: 'seekMs',
                type: 'number',
                labelKey: 'script.fields.seekMs',
                min: 0,
                step: 1,
                optional: true,
            },
        ],
    },
    playSound: {
        kind: 'playSound',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.playSound',
        fields: [
            { name: 'soundId', type: 'sound', labelKey: 'script.fields.soundId' },
            { name: 'volume', type: 'number', labelKey: 'script.fields.volume', min: 0, max: 2, step: 0.1 },
            { name: 'pitch', type: 'number', labelKey: 'script.fields.pitch', min: 0.5, max: 2, step: 0.1 },
            { name: 'scope', type: 'scope', labelKey: 'script.fields.scope', options: SOUND_SCOPE_OPTIONS },
        ],
    },
    wait: {
        kind: 'wait',
        category: 'control',
        colorVar: CATEGORY_COLOR_VAR.control,
        labelKey: 'script.blocks.wait',
        fields: [
            { name: 'ms', type: 'number', labelKey: 'script.fields.ms', min: 50, max: 5000, step: 50 },
        ],
    },
    runCommand: {
        kind: 'runCommand',
        category: 'danger',
        colorVar: CATEGORY_COLOR_VAR.danger,
        labelKey: 'script.blocks.runCommand',
        fields: [
            { name: 'templateId', type: 'command', labelKey: 'script.fields.templateId' },
            { name: 'params', type: 'command', labelKey: 'script.fields.params' },
        ],
    },
    log: {
        kind: 'log',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.log',
        fields: [
            { name: 'message', type: 'text', labelKey: 'script.fields.message' },
        ],
    },
    // ---- 0.7.1-P1：4 个低风险新动作 + 相对移动（友好元素积木走 FRIENDLY_ELEMENT_DEFS，不在此）----
    nudgeElement: {
        kind: 'nudgeElement',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.nudgeElement',
        fields: [
            { name: 'elementId', type: 'element', labelKey: 'script.fields.elementId' },
            { name: 'dx', type: 'number', labelKey: 'script.fields.dx', step: 1 },
            { name: 'dy', type: 'number', labelKey: 'script.fields.dy', step: 1 },
        ],
    },
    sendMessage: {
        kind: 'sendMessage',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.sendMessage',
        fields: [
            { name: 'text', type: 'text', labelKey: 'script.fields.messageText' },
            { name: 'channel', type: 'select', labelKey: 'script.fields.channel', options: MESSAGE_CHANNEL_OPTIONS },
        ],
    },
    setRandomVariable: {
        kind: 'setRandomVariable',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.setRandomVariable',
        fields: [
            { name: 'fullName', type: 'variable', labelKey: 'script.fields.fullName' },
            { name: 'min', type: 'number', labelKey: 'script.fields.min', step: 1 },
            { name: 'max', type: 'number', labelKey: 'script.fields.max', step: 1 },
        ],
    },
    scaleVariable: {
        kind: 'scaleVariable',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.scaleVariable',
        fields: [
            { name: 'fullName', type: 'variable', labelKey: 'script.fields.fullName' },
            { name: 'op', type: 'select', labelKey: 'script.fields.scaleOp', options: SCALE_OP_OPTIONS },
            { name: 'factor', type: 'number', labelKey: 'script.fields.factor', step: 1 },
        ],
    },
    playTimelineAwait: {
        kind: 'playTimelineAwait',
        category: 'timeline',
        colorVar: CATEGORY_COLOR_VAR.timeline,
        labelKey: 'script.blocks.playTimelineAwait',
        fields: [
            { name: 'timelineId', type: 'timeline', labelKey: 'script.fields.timelineId' },
        ],
    },
    if: {
        kind: 'if',
        category: 'control',
        colorVar: CATEGORY_COLOR_VAR.control,
        labelKey: 'script.blocks.if',
        fields: [
            { name: 'condition', type: 'condition', labelKey: 'script.fields.condition' },
            { name: 'then', type: 'statements', labelKey: 'script.fields.then' },
            { name: 'else', type: 'statements', labelKey: 'script.fields.else' },
        ],
    },
    // ---- 0.7.1-P2：有界循环「重复 N 次」（control category，count + body 子序列槽）----
    repeat: {
        kind: 'repeat',
        category: 'control',
        colorVar: CATEGORY_COLOR_VAR.control,
        labelKey: 'script.blocks.repeat',
        fields: [
            { name: 'count', type: 'number', labelKey: 'script.fields.repeatCount', min: 1, max: 100, step: 1 },
            { name: 'body', type: 'statements', labelKey: 'script.fields.body' },
        ],
    },
    // ---- 0.7.1-P5：3 个剩余动作（停止脚本 / 播放粒子 / 等待直到）----
    // palette 由 ACTION_DEFS 按 category 自动分组。0.7.2-F2：停止脚本 / 等待直到是控制流 → category
    // 'control'（绿，与 if/repeat/wait 同组）；播放粒子是副作用 → 留 'action'（蓝）。
    // waitUntil 的条件字段用 type:'condition' 复用 ConditionBuilder。
    stopScript: {
        kind: 'stopScript',
        category: 'control',
        colorVar: CATEGORY_COLOR_VAR.control,
        labelKey: 'script.blocks.stopScript',
        fields: [],
    },
    playParticle: {
        kind: 'playParticle',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.playParticle',
        fields: [
            { name: 'particle', type: 'select', labelKey: 'script.fields.particle', options: PARTICLE_OPTIONS },
            { name: 'count', type: 'number', labelKey: 'script.fields.particleCount', min: 1, max: 1000, step: 1 },
            { name: 'offsetX', type: 'number', labelKey: 'script.fields.offsetX', step: 0.5 },
            { name: 'offsetY', type: 'number', labelKey: 'script.fields.offsetY', step: 0.5 },
            { name: 'offsetZ', type: 'number', labelKey: 'script.fields.offsetZ', step: 0.5 },
        ],
    },
    waitUntil: {
        kind: 'waitUntil',
        category: 'control',
        colorVar: CATEGORY_COLOR_VAR.control,
        labelKey: 'script.blocks.waitUntil',
        fields: [
            { name: 'condition', type: 'condition', labelKey: 'script.fields.condition' },
            { name: 'timeoutMs', type: 'number', labelKey: 'script.fields.timeoutMs', min: 50, max: 60000, step: 50 },
        ],
    },
    // ---- 0.7.2-P2：4 个新动作（变量复制 / 文本拼接 / 克隆元素 / 删除元素，都是副作用 → 'action' 蓝）----
    // 变量字段走 type:'variable'（现有 VariablePicker）；元素字段走 type:'element'（现有元素下拉）；
    // offsetX/Y 走 type:'number'。palette 由 ACTION_DEFS 按 category 自动分组（蓝组），无需另登记。
    copyVariable: {
        kind: 'copyVariable',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.copyVariable',
        fields: [
            { name: 'source', type: 'variable', labelKey: 'script.fields.copySource' },
            { name: 'target', type: 'variable', labelKey: 'script.fields.copyTarget' },
        ],
    },
    appendVariable: {
        kind: 'appendVariable',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.appendVariable',
        fields: [
            { name: 'fullName', type: 'variable', labelKey: 'script.fields.appendTarget' },
            { name: 'text', type: 'text', labelKey: 'script.fields.appendText' },
        ],
    },
    cloneElement: {
        kind: 'cloneElement',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.cloneElement',
        fields: [
            { name: 'elementId', type: 'element', labelKey: 'script.fields.elementId' },
            { name: 'offsetX', type: 'number', labelKey: 'script.fields.offsetX', step: 1 },
            { name: 'offsetY', type: 'number', labelKey: 'script.fields.offsetY', step: 1 },
        ],
    },
    deleteElement: {
        kind: 'deleteElement',
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
        labelKey: 'script.blocks.deleteElement',
        fields: [
            { name: 'elementId', type: 'element', labelKey: 'script.fields.elementId' },
        ],
    },
};

// ---------- 0.7.1-P1：友好元素积木皮肤（路线乙——8 个友好积木都序列化成一条 setElementProperties）----------

/**
 * 友好元素积木皮肤声明。{@code kind} → 标题（{@code labelKey}）+ 该 kind 对应的可编辑字段
 * （{@code fields}，name = patch 键）+ 拖出时的默认 patch（{@code defaultPatch}）。
 *
 * <p>BlockNode 渲染 {@code setElementProperties} 动作时按 {@code action.kind} 查此表选皮肤：
 * 字段值读写 {@code action.patch[field.name]}。{@code fields} 为空数组（如 show / hide）= 无可编辑
 * 字段，拖出即定值（show = opacity 1 / hide = opacity 0）。</p>
 *
 * <p>这是 0.7.0「1 积木 = 1 条 action」blockId 同构纪律的延续——一个友好积木只产一条 action，
 * 试跑高亮 / undo / 幽灵拖动天然正确。{@code kind} 仅前端皮肤标记，后端执行忽略。</p>
 */
export interface FriendlyElementDef {
    kind: string;
    labelKey: string;
    /** 可编辑字段（name = patch 键）。show / hide 为空数组（拖出即定值）。 */
    fields: FieldDef[];
    /** 默认 patch（拖出时写入 action.patch）。 */
    defaultPatch: Record<string, string>;
}

/**
 * 友好元素积木皮肤表（kind → 皮肤）。8 个 kind：moveTo / resize / rotateTo / setOpacity /
 * show / hide / setText / setColor。字段标签复用现有 {@code script.fieldOptions.prop*} key
 * （横坐标 X / 纵坐标 Y / 宽度 / 高度 / 旋转 / 不透明度 / 文字 / 填充）。
 *
 * <p>{@code setColor} 用 {@code text} 字段输 hex（P1 从简，颜色选择器留后续）；{@code setText}
 * 用 {@code text} 字段（支持手打 {@code ${var:X}}）。每个 patch 键都在后端 {@code ELEMENT_PROPERTIES}
 * 白名单内（x/y/w/h/rotation/opacity/text/fill），保证「拖出即合法」。</p>
 */
export const FRIENDLY_ELEMENT_DEFS: Record<string, FriendlyElementDef> = {
    moveTo: {
        kind: 'moveTo',
        labelKey: 'script.friendly.moveTo',
        fields: [
            { name: 'x', type: 'number', labelKey: 'script.fieldOptions.propX' },
            { name: 'y', type: 'number', labelKey: 'script.fieldOptions.propY' },
        ],
        defaultPatch: { x: '0', y: '0' },
    },
    resize: {
        kind: 'resize',
        labelKey: 'script.friendly.resize',
        fields: [
            { name: 'w', type: 'number', labelKey: 'script.fieldOptions.propW', min: 1 },
            { name: 'h', type: 'number', labelKey: 'script.fieldOptions.propH', min: 1 },
        ],
        defaultPatch: { w: '64', h: '64' },
    },
    rotateTo: {
        kind: 'rotateTo',
        labelKey: 'script.friendly.rotateTo',
        fields: [
            { name: 'rotation', type: 'number', labelKey: 'script.fieldOptions.propRotation', min: 0, max: 359 },
        ],
        defaultPatch: { rotation: '0' },
    },
    setOpacity: {
        kind: 'setOpacity',
        labelKey: 'script.friendly.setOpacity',
        fields: [
            { name: 'opacity', type: 'number', labelKey: 'script.fieldOptions.propOpacity', min: 0, max: 1, step: 0.1 },
        ],
        defaultPatch: { opacity: '1' },
    },
    show: {
        kind: 'show',
        labelKey: 'script.friendly.show',
        fields: [],
        defaultPatch: { opacity: '1' },
    },
    hide: {
        kind: 'hide',
        labelKey: 'script.friendly.hide',
        fields: [],
        defaultPatch: { opacity: '0' },
    },
    setText: {
        kind: 'setText',
        labelKey: 'script.friendly.setText',
        fields: [
            { name: 'text', type: 'text', labelKey: 'script.fieldOptions.propText' },
        ],
        defaultPatch: { text: '' },
    },
    setColor: {
        kind: 'setColor',
        labelKey: 'script.friendly.setColor',
        fields: [
            { name: 'fill', type: 'text', labelKey: 'script.fieldOptions.propFill' },
        ],
        defaultPatch: { fill: '#FFFFFF' },
    },
};

/**
 * 0.7.1-P3 波2：友好元素积木 {@code kind} → 「点选元素时该从元素当前态取哪些值填进 patch」。
 *
 * <p>预览框点选命中元素后（深度2），除回填 {@code elementId}，对<b>坐标/尺寸/旋转/透明度</b>类
 * 友好积木顺带把元素的当前几何填进 {@code patch}——免去用户手抄坐标。映射的字段名（{@code x/y/
 * w/h/rotation/opacity}）<b>与后端 ELEMENT_PROPERTIES 白名单 + FRIENDLY_ELEMENT_DEFS 的 patch
 * 键逐字符一致</b>（moveTo→x/y，resize→w/h，rotateTo→rotation，setOpacity→opacity）。</p>
 *
 * <p>不在表内的 kind（{@code show} / {@code hide} / {@code setText} / {@code setColor}）= 只回填
 * elementId 不取几何（显隐 / 文字 / 颜色与元素当前坐标无关）。值取自 Element 的同名属性
 * （{@code rotation} / {@code opacity} 可能缺省，取值方按需兜底）。</p>
 */
export const FRIENDLY_KIND_CURRENT_FIELDS: Record<string, string[]> = {
    moveTo: ['x', 'y'],
    resize: ['w', 'h'],
    rotateTo: ['rotation'],
    setOpacity: ['opacity'],
};

/**
 * palette「友好元素」分组的可拖项（kind 即拖出标识，{@code makeDefaultAction(kind)} 生成 action）。
 *
 * <p>{@code nudgeElement} 混在友好元素分组里——它对用户是「移动一点」的元素操作，虽走独立
 * {@code nudgeElement} action（不是 setElementProperties）。其余 8 项是 FRIENDLY_ELEMENT_DEFS 的 kind。</p>
 */
export const FRIENDLY_PALETTE_KINDS: string[] = [
    'moveTo', 'nudgeElement', 'resize', 'rotateTo', 'setOpacity', 'show', 'hide', 'setText', 'setColor',
];

/**
 * 按 kind 取积木定义（先查动作再查触发器）。未知 kind → {@code null}。
 * BlockNode（动作）与 BlockStack（帽子读 TRIGGER_DEFS）都经此查。
 */
export function defFor(kind: string): BlockDef | null {
    return ACTION_DEFS[kind] ?? TRIGGER_DEFS[kind] ?? null;
}

// ---------- 新积木默认值（D2：palette 拖出 / 新建规则用）----------

/**
 * 造一个指定 kind 的合法默认动作（palette 拖出新块时用）。
 *
 * <p>每个字段取一个<b>合法且无害</b>的默认（在后端 validator 范围内）。分两类对待引用类字段：</p>
 * <ul>
 *   <li><b>不依赖墙状态</b>的引用（变量名 {@code fullName} / 声音 {@code soundId} / if 条件
 *       {@code condition}）：给一个<b>非空合理默认</b>（{@code user/score} / {@code entity.player.levelup}
 *       / {@code var("user/score") > 0}）。理由：拖出新块的瞬间属于"中间态"，若引用字段给空串则
 *       前端 validator + 后端 ScriptRuleValidator 都立刻判非法 → 红字轰炸 + 自动保存被卡。给一个
 *       合法占位让积木"拖出即合法"，用户再按需改成自己的变量 / 声音；</li>
 *   <li><b>依赖墙上有什么</b>的引用（元素 {@code elementId} / 时间轴 {@code timelineId} / 命令模板
 *       {@code templateId}）：仍给空串——这些没有通用合法默认（墙上有哪些元素 / 时间轴是动态的），
 *       用户必须从下拉里选；视觉层会把空引用标"请选择"（不在本任务范围）。</li>
 * </ul>
 * <p>数值默认取范围内常用值（timer 间隔 10s / 靠近 8 格 / 等待 500ms）；枚举取首项（op=play /
 * scope=near / property=x）。if 的 then/else 默认空数组（非 null，wire 契约）。</p>
 *
 * <p>未知 kind → 兜底返一个 {@code log} 动作（绝不抛，调用方拿到的总是合法 ScriptAction）。
 * 返回值的 {@code type} 是窄化好的字面量联合分支，可直接喂 {@code insertAt} / setActions。</p>
 */
export function makeDefaultAction(kind: string): ScriptAction {
    switch (kind) {
        case 'setVariable':
            // fullName 给非空默认（拖出即合法）；value 空串后端合法，保持空。
            return { type: 'setVariable', fullName: 'user/score', value: '' };
        case 'incrementVariable':
            return { type: 'incrementVariable', fullName: 'user/score', delta: 1 };
        case 'setElementProperty':
            // elementId 依赖墙上元素，无通用默认 → 留空让用户选。
            return { type: 'setElementProperty', elementId: '', property: 'x', value: '' };
        case 'playTimeline':
            // timelineId 依赖墙上时间轴 → 留空让用户选。seekMs 仅 op=seek 携带——默认 op=play 故省略。
            return { type: 'playTimeline', timelineId: '', op: 'play' };
        case 'playSound':
            // soundId 给一个内置音效默认（拖出即合法），用户可改。
            return { type: 'playSound', soundId: 'entity.player.levelup', volume: 1, pitch: 1, scope: 'near' };
        case 'wait':
            return { type: 'wait', ms: 500 };
        case 'runCommand':
            // templateId 依赖已配命令模板 → 留空让用户选。
            return { type: 'runCommand', templateId: '', params: {} };
        case 'log':
            return { type: 'log', message: '' };
        // 友好元素积木：kind 在 FRIENDLY_ELEMENT_DEFS 里 → 序列化成一条 setElementProperties，
        // patch 取该 kind 的默认（白名单内，拖出即合法）；elementId 依赖墙上元素 → 留空让用户选。
        case 'moveTo':
        case 'resize':
        case 'rotateTo':
        case 'setOpacity':
        case 'show':
        case 'hide':
        case 'setText':
        case 'setColor': {
            const def = FRIENDLY_ELEMENT_DEFS[kind];
            return { type: 'setElementProperties', elementId: '', patch: { ...def.defaultPatch }, kind };
        }
        // 0.7.1 4 个低风险新动作 + nudge。引用字段给非空合理默认（拖出即合法）：
        // nudge / 随机 / 乘除给变量名或 dx/dy；sendMessage text 空串后端合法（非引用字段）。
        case 'nudgeElement':
            // elementId 依赖墙上元素 → 留空让用户选；dx/dy 默认 0（合法，验证只查有限）。
            return { type: 'nudgeElement', elementId: '', dx: 0, dy: 0 };
        case 'sendMessage':
            // text 空串后端合法（非引用字段，不触发"待完善"角标）；channel 取首项 chat。
            return { type: 'sendMessage', text: '', channel: 'chat' };
        case 'setRandomVariable':
            // fullName 给非空默认（user/roll），区间 1..6（骰子体验）。
            return { type: 'setRandomVariable', fullName: 'user/roll', min: 1, max: 6 };
        case 'scaleVariable':
            // fullName 给非空默认；op 取首项 multiply；factor 默认 2。
            return { type: 'scaleVariable', fullName: 'user/score', op: 'multiply', factor: 2 };
        case 'playTimelineAwait':
            // timelineId 依赖墙上时间轴 → 留空让用户选。
            return { type: 'playTimelineAwait', timelineId: '' };
        case 'if':
            // condition 给一个能被 tryParseCondition 解析回可视模式的合法默认（var 比较），拖出即合法。
            return { type: 'if', condition: 'var("user/score") > 0', then: [], else: [] };
        case 'repeat':
            // 0.7.1-P2：count 默认 3（落 1..100）；body 空数组——由用户往循环体拖块填，
            // 拖出态会提示"循环体不能为空"（与 if 的空分支不同：repeat 要求 body 非空）。
            return { type: 'repeat', count: 3, body: [] };
        // ---- 0.7.1-P5：3 个剩余动作 ----
        case 'stopScript':
            // 无字段——拖出即合法（清栈中止本次 run）。
            return { type: 'stopScript' };
        case 'playParticle':
            // particle 取首项火焰（白名单内，拖出即合法）；count 10；偏移 0（合法，validator 只查有限）。
            return { type: 'playParticle', particle: 'minecraft:flame', count: 10, offsetX: 0, offsetY: 0, offsetZ: 0 };
        case 'waitUntil':
            // condition 给能被 tryParseCondition 解析回可视模式的合法默认（同 if）；timeoutMs 5000（落 50..60000）。
            return { type: 'waitUntil', condition: 'var("user/score") > 0', timeoutMs: 5000 };
        // ---- 0.7.2-P2：变量积木默认（source/target 留空——拖出态提示"目标 / 来源不能为空"，用户从 picker 选）。
        // 与 setVariable 不同：copy/append 两端都是用户要点选的具体变量，没有通用合理默认，故空串。
        case 'copyVariable':
            return { type: 'copyVariable', source: '', target: '' };
        case 'appendVariable':
            // fullName 留空（用户选追加目标）；text 空串后端合法（拼接内容由用户填）。
            return { type: 'appendVariable', fullName: '', text: '' };
        // ---- 0.7.2-P2：元素积木默认（elementId 依赖墙上元素 → 留空让用户选；偏移默认 10,10 错开可见）。
        case 'cloneElement':
            return { type: 'cloneElement', elementId: '', offsetX: 10, offsetY: 10 };
        case 'deleteElement':
            return { type: 'deleteElement', elementId: '' };
        default:
            // 未知 kind 兜底：给个最简单的 log 动作（保证返回合法 ScriptAction）。
            return { type: 'log', message: '' };
    }
}

/**
 * 造一个指定 kind 的合法默认触发器（新建规则 / 切触发器类型时用）。
 *
 * <p>无数据字段的触发器（{@code playerJoin} / {@code playerKill} / {@code wallReady} /
 * {@code rightClickWall} / {@code playerQuit}）只带 type；带字段的给范围内默认（timer 间隔 10s /
 * playerNear / playerLeaveRange 8 格 / variableChange 给非空变量名 {@code user/score} 让切换/拖出
 * 即合法，用户再改）。未知 kind → 兜底 {@code wallReady}（最无害的"画板就绪即触发"）。</p>
 */
export function makeDefaultTrigger(kind: string): ScriptTrigger {
    switch (kind) {
        case 'variableChange':
            // fullName 给非空默认（切到此触发器即合法），用户可改成自己关注的变量。
            return { type: 'variableChange', fullName: 'user/score' };
        case 'timer':
            return { type: 'timer', intervalSeconds: 10 };
        case 'playerJoin':
            return { type: 'playerJoin' };
        case 'playerKill':
            return { type: 'playerKill' };
        case 'playerNear':
            return { type: 'playerNear', rangeBlocks: 8 };
        case 'wallReady':
            return { type: 'wallReady' };
        // ---- 0.7.1-P2：3 个新触发器 ----
        case 'rightClickWall':
            return { type: 'rightClickWall' };
        case 'playerLeaveRange':
            // rangeBlocks 默认 8 格（同 playerNear，落 1..32）。
            return { type: 'playerLeaveRange', rangeBlocks: 8 };
        case 'playerQuit':
            return { type: 'playerQuit' };
        default:
            return { type: 'wallReady' };
    }
}
