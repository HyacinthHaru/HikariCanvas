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

/**
 * 六种触发器定义（kind ∈ ScriptTrigger.type）。
 * 字段覆盖各 wire 数据字段：variableChange→fullName / timer→intervalSeconds /
 * playerNear→rangeBlocks；playerJoin / playerKill / wallReady 无数据字段。
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
};

/**
 * 九种动作定义（kind ∈ ScriptAction.type，8 动作 + if）。
 * 字段逐一对应 wire 数据字段；if 用 condition + then/else（statements 子序列槽）。
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
        category: 'action',
        colorVar: CATEGORY_COLOR_VAR.action,
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
};

/**
 * 按 kind 取积木定义（先查动作再查触发器）。未知 kind → {@code null}。
 * BlockNode（动作）与 BlockStack（帽子读 TRIGGER_DEFS）都经此查。
 */
export function defFor(kind: string): BlockDef | null {
    return ACTION_DEFS[kind] ?? TRIGGER_DEFS[kind] ?? null;
}
