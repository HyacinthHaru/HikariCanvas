<script setup lang="ts">
/**
 * 递归积木块（一个动作 / if 条件分支）。
 *
 * <p>渲染单个 {@link ScriptAction}：左侧色条（按 def.category 着色）+ 头部 label + 参数槽。
 * 每个非 {@code statements}/{@code condition} 字段由真表单控件（number/select/variable/...）渲染。</p>
 *
 * <p><b>if 块 = C 形</b>：condition 字段单独占位显示条件串；{@code then} / {@code else} 两个
 * 子序列槽用本组件递归渲染 —— 子积木 path 拼 {@code `${path}/then/${i}`} /
 * {@code `${path}/else/${i}`}，<b>与后端 trace blockId 逐字符同构</b>（权威
 * blockTree.ts 头注 / ScriptRunner.java）。空槽显占位提示。</p>
 *
 * <p><b>repeat 块 = C 形（单臂）</b>：count 标量在头部由 BlockParamInput 渲染（number），
 * {@code body} 子序列槽递归渲染 —— 子积木 path 拼 {@code `${path}/body/${i}`}，<b>与后端
 * ScriptRunner 展开 blockId 同构</b>（展开 count 轮但 blockId 不带 round，前端只一份 body）。</p>
 *
 * <p>块根挂 {@code data-block-path}（= props.path）——供测量插槽矩形、按
 * trace blockId 高亮定位。lock 态（project.isLocked）下拖拽 / 交互按 lock 守卫禁用。</p>
 */
import { computed, inject } from 'vue';
import { Crosshair } from 'lucide-vue-next';
import type { ScriptAction } from '@/types/protocol';
import { useI18n } from '@/i18n';
import { useProjectStore } from '@/stores/project';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { ref } from 'vue';
import { defFor, FRIENDLY_ELEMENT_DEFS, type FieldDef } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import { BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES } from './dragInjection';
import { BLOCK_HIGHLIGHT_KEY, type HighlightInject } from './highlightInjection';
import { resultColorVar, type HighlightMap } from './traceHighlight';
import BlockParamInput, { type CommandValue } from '../params/BlockParamInput.vue';
import ConditionBuilder from '../params/ConditionBuilder.vue';
import EasingCurveEditor from '@/components/timeline/EasingCurveEditor.vue';
import type { Easing } from '@/types/protocol';

const props = defineProps<{
    /** 本块对应的动作（含 if）。 */
    action: ScriptAction;
    /** 本块在动作树中的 path（如 {@code 'actions/2'} / {@code 'actions/2/then/0'}）。 */
    path: string;
}>();

const { t } = useI18n();
const project = useProjectStore();
const edit = useScriptEditStore();
/** 拖拽句柄（BlockCanvas provide；单独 mount 时走 no-op 兜底）。 */
const dragHandles = inject(BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES);
/**
 * H 阶段试跑高亮（overlay provide；单独 mount 时走空 map 兜底——查不到高亮，静态渲染）。
 * 用常量空 ref 作默认值，避免每个未注入场景都新建 ref。
 */
const EMPTY_HIGHLIGHT: HighlightInject = {
    results: ref<HighlightMap>(new Map()),
    details: ref<Map<string, string>>(new Map()),
};
const highlight = inject(BLOCK_HIGHLIGHT_KEY, EMPTY_HIGHLIGHT);

/**
 * 块根 pointerdown：启动"拖已有块"。只接管主键（左键）。ruleId 从最近祖先 [data-rule-id] 现取
 * （BlockNode 递归不显式持 ruleId，避免一路透传 prop）。{@code startBlockDrag} 内部会
 * stopPropagation——嵌套块时只有<b>指针正下方最深的那块</b>启动拖动，外层不抢。
 *
 * <p>块内会有参数输入框：表单元素聚焦不应触发拖块（避免点输入框就拖走）。这里
 * 跳过 input/textarea/select/contentEditable 目标（pointerdown 落在它们上时不拖）。</p>
 *
 * <p>变量选择器打开可靠性：pointerdown 落在表单控件上时虽不拖块，
 * 但要<b>在此处先选中本规则</b>：变量「选变量」按钮的 click 会冒泡到 BlockStack 的
 * {@code onStackClick} 触发 {@code selectRule}；若规则原本未选中，这次 selectRule 会在<b>同一次点击</b>
 * 里替换 workingCopy 触发画布重渲，与刚弹出的 picker 抢时序 → 用户感觉"多点几次才开"。提前在
 * pointerdown（早于 click）选好，click 时 onStackClick 命中 guard 不再重选 → 不重渲 → picker 一次到位。</p>
 */
function onBlockPointerDown(e: PointerEvent): void {
    if (e.button !== 0) return;
    if (isFormTarget(e.target)) {
        selectOwningRule(e);
        return;
    }
    const host = (e.currentTarget as HTMLElement).closest('[data-rule-id]');
    const ruleId = host?.getAttribute('data-rule-id');
    if (!ruleId) return;
    dragHandles.startBlockDrag(ruleId, props.path, e);
}

/** 取本块所属规则 id（最近 [data-rule-id] 祖先）并选中它（已是当前编辑规则则 selectRule 内部无副作用）。 */
function selectOwningRule(e: PointerEvent): void {
    const host = (e.currentTarget as HTMLElement).closest('[data-rule-id]');
    const ruleId = host?.getAttribute('data-rule-id');
    if (ruleId && edit.selectedRuleId !== ruleId) edit.selectRule(ruleId);
}

/**
 * pointerdown 目标是否落在交互控件（命中则不启动拖块，留给控件自身的 click / focus）。
 *
 * <p>触控板敏感度修复：用 {@code closest} 而非 {@code matches}。变量「选变量」按钮
 * 内有子元素（{@code <button><span>选变量…</span></button>}），点击实际落在子 {@code span} 上——
 * {@code span.matches('button')} 为 false 会漏判成"可拖" → 点不到字段就被拖走。{@code closest} 会
 * 沿祖先链找到那个 {@code button}/{@code input}，凡落在交互元素内（含其子节点）一律视为表单目标。
 * 同时扩展覆盖 {@code [role="button"]} / {@code label} / {@code contenteditable}。</p>
 */
function isFormTarget(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    if (!el) return false;
    if (el.isContentEditable) return true;
    // 自定义缓动曲线编辑器（.hc-tween-curve-editor 内的 SVG 拖控制点）也算交互区——
    // pointerdown 落它上时不启动拖块，否则拖曲线会被块根 onBlockPointerDown 当成拖积木、把整块拖走。
    return !!el.closest?.('input, textarea, select, button, [role="button"], [contenteditable], label, .hc-tween-curve-editor');
}

/** 本块的声明定义；未知 kind → null（兜底显示 unknownBlock）。 */
const def = computed(() => defFor(props.action.type));

// ---------- 友好元素积木皮肤（setElementProperties）----------

/**
 * 是否友好元素积木（{@code setElementProperties}）。这类块在 ACTION_DEFS 里没有 def
 * （走 FRIENDLY_ELEMENT_DEFS 皮肤），故 {@code def} 为 null——本分支接管标题 / 字段渲染。
 */
const isFriendly = computed(() => props.action.type === 'setElementProperties');

/**
 * 友好皮肤声明（按 {@code action.kind} 查 FRIENDLY_ELEMENT_DEFS）。kind 未知 / 缺失 → null
 * （标题退通用名、字段空——不崩）。仅 friendly 块有值。
 */
const friendlyDef = computed(() =>
    isFriendly.value
        ? (FRIENDLY_ELEMENT_DEFS[(props.action as { kind?: string }).kind ?? ''] ?? null)
        : null,
);

/** friendly 块当前 patch（读 action.patch；非 friendly 返空对象）。 */
const friendlyPatch = computed<Record<string, string>>(() =>
    isFriendly.value
        ? ((props.action as { patch?: Record<string, string> }).patch ?? {})
        : {},
);

/** friendly 块的可编辑字段（皮肤的 fields；show/hide 为空数组——只渲染元素下拉）。 */
const friendlyFields = computed<FieldDef[]>(() => friendlyDef.value?.fields ?? []);

/**
 * friendly 块的「元素」字段（所有友好积木都先选元素）。复用 ACTION_DEFS 里 element 字段
 * 标签 key（{@code script.fields.elementId}），交给现有 element 控件渲染。
 */
const FRIENDLY_ELEMENT_FIELD: FieldDef = {
    name: 'elementId',
    type: 'element',
    labelKey: 'script.fields.elementId',
};

/** friendly 块标题：皮肤 labelKey；kind 未知 → 通用名（已存在的"设置元素属性"，不崩）。 */
const friendlyTitle = computed(() =>
    resolveLabelKey(t.value, friendlyDef.value?.labelKey ?? 'script.blocks.setElementProperty'),
);

/** 块标题文案（friendly → 皮肤名；def.labelKey 解析；无 def 时显示未知积木兜底）。 */
const title = computed(() => {
    if (isFriendly.value) return friendlyTitle.value;
    return def.value
        ? resolveLabelKey(t.value, def.value.labelKey)
        : `${resolveLabelKey(t.value, 'script.unknownBlock')}: ${props.action.type}`;
});

/** 色条颜色（friendly 块走动作蓝；无 def → 用边框灰）。 */
const colorVar = computed(() => {
    if (def.value) return `var(${def.value.colorVar})`;
    if (isFriendly.value) return 'var(--ctp-blue)';
    return 'var(--border)';
});

/**
 * 是否「带 then/else 双臂」块（C 形布局：if 条件分支 + randomBranch 随机分支）。
 * 两者渲染路径完全相同（条件/概率行 + then/else 子槽 + C 形底托），共用模板。
 * if 的条件行走 ConditionBuilder（conditionField 存在）；randomBranch 的 probability 走 scalarFields。
 */
const isIf = computed(() => props.action.type === 'if' || props.action.type === 'randomBranch');

/**
 * 是否 repeat 块（C 形单臂布局：count 标量 + body 子序列槽）。count 字段走
 * scalarFields 在头部由 BlockParamInput 渲染（number），body 是 statements 子槽（C 臂）。
 */
const isRepeat = computed(() => props.action.type === 'repeat');

/**
 * 是否 repeatUntil 块（C 形单臂布局：condition + maxIterations 标量 + body 子序列槽）。
 * condition 走下方 {@code conditionField && !isIf} 行的 ConditionBuilder（同 waitUntil）；
 * maxIterations 走 scalarFields 在头部由 BlockParamInput 渲染（number）；body 是 statements 子槽（C 臂）。
 */
const isRepeatUntil = computed(() => props.action.type === 'repeatUntil');

/**
 * 是否 tweenBlock（C 形单臂布局：durationMs + easing 标量 + body 子序列槽）。
 * durationMs / easing 字段走 scalarFields 由 BlockParamInput 渲染（number / select），
 * body 是 statements 子槽（C 臂），与 repeat / repeatUntil 共用 {@link hasBodySlot} 分支。
 */
const isTweenBlock = computed(() => props.action.type === 'tweenBlock');

/**
 * 是否带 {@code body} C 臂的循环块（repeat / repeatUntil / tweenBlock）——决定要不要渲染
 * body 子序列槽 + C 形底托。统一三者的 C 形渲染分支，避免模板里写多段近乎一致的 body 槽。
 */
const hasBodySlot = computed(() => isRepeat.value || isRepeatUntil.value || isTweenBlock.value);

/** 锁定态：true 时所有参数控件 disabled（透传给 BlockParamInput）。 */
const locked = computed(() => project.isLocked);

/** 是否 runCommand 块（command 复合字段特殊处理：templateId + params 合一控件）。 */
const isRunCommand = computed(() => props.action.type === 'runCommand');

// ---------- H：试跑高亮（按本块 path 查高亮 map）----------

/** 本块的试跑结果态（未命中 → undefined，不高亮）。 */
const highlightResult = computed(() => highlight.results.value.get(props.path));
/** 本块的 trace detail 文案（作 title 提示；无则 undefined）。 */
const highlightDetail = computed(() => highlight.details.value.get(props.path));
/**
 * 高亮态的边框色：命中 step 时用结果色（ok=green/skipped=overlay/blocked=yellow/error=red），
 * 否则用 def 的 category 色条色（与原静态渲染一致）。
 */
const effectiveBorderColor = computed(() =>
    highlightResult.value ? `var(${resultColorVar(highlightResult.value)})` : colorVar.value,
);
/** 高亮态时整块加一圈外发光描边（让"正在执行 / 命中"更醒目）。 */
const highlightStyle = computed(() => {
    const r = highlightResult.value;
    if (!r) return {};
    const v = `var(${resultColorVar(r)})`;
    return {
        boxShadow: `0 0 0 2px color-mix(in srgb, ${v} 60%, transparent), 0 1px 2px rgba(0,0,0,0.12)`,
    };
});

/**
 * 头部要渲染的“标量参数槽”字段（由 {@link BlockParamInput} 真控件渲染）：排除 statements
 * （then/else 由 C 形子槽渲染）与 condition（if 的条件单独行，走 ConditionBuilder）。
 *
 * <p><b>runCommand 特殊</b>：它有 templateId + params 两个 {@code command} 字段，但二者由<b>同一
 * 个</b> command 控件整体处理（见模板 {@code commandField} 分支）。故这里把 runCommand 的所有
 * command 字段从 scalarFields 排掉，避免重复渲染。</p>
 */
const scalarFields = computed<FieldDef[]>(() =>
    (def.value?.fields ?? []).filter((f) => {
        if (f.type === 'statements' || f.type === 'condition') return false;
        // runCommand 的 command 字段交给专用复合控件，不在通用 scalarFields 里渲染。
        if (isRunCommand.value && f.type === 'command') return false;
        return true;
    }),
);

/** runCommand 的复合 command 字段（取首个 command 字段作为控件锚——templateId）。 */
const commandField = computed<FieldDef | null>(() =>
    isRunCommand.value ? (def.value?.fields.find((f) => f.type === 'command') ?? null) : null,
);

/** if 块的 condition 字段（单独占位行）。 */
const conditionField = computed<FieldDef | null>(
    () => def.value?.fields.find((f) => f.type === 'condition') ?? null,
);

/** 字段 label 文案。 */
function fieldLabel(field: FieldDef): string {
    return resolveLabelKey(t.value, field.labelKey);
}

/**
 * 取某字段在当前 action 上的原始值（喂给 BlockParamInput 的 {@code value}）。
 * 标量字段返原值（string / number）；缺失 → undefined（控件按类型退默认）。
 */
function fieldValue(field: FieldDef): unknown {
    // tweenBlock 的 easing 字段：数据是 Easing 对象 {type, bezier?}，用 select 渲染 type 部分。
    // select 受控值仍是 type 字符串（含 'cubicBezier'）；bezier 由 tweenEasingBezier 单独暴露。
    if (field.name === 'easing') {
        const e = (props.action as unknown as { easing?: { type?: string } }).easing;
        return e?.type ?? 'linear';
    }
    return (props.action as unknown as Record<string, unknown>)[field.name];
}

/**
 * 当前 tweenBlock easing 对象（完整，含 bezier；非 tweenBlock 时为 linear）。
 * 供 EasingCurveEditor v-model 读取完整 Easing，不只是 type 字符串。
 */
const tweenEasing = computed<Easing>(() => {
    if (!isTweenBlock.value) return { type: 'linear' };
    const e = (props.action as unknown as { easing?: Easing }).easing;
    return e ?? { type: 'easeInOut' };
});

/** 当前 easing 是否为 cubicBezier（控制曲线编辑器显隐）。 */
const isTweenEasingCustom = computed(() => tweenEasing.value.type === 'cubicBezier');

/**
 * 标量字段改值回写：调 {@code edit.updateActionField(path, {[name]: value})}。
 * command 类型不走这（runCommand 用 {@link onCommandUpdate}），但函数对 command 也安全
 * （不会被调用到）。
 */
function onFieldUpdate(field: FieldDef, value: unknown): void {
    // easing：select emit 字符串 type，包回 Easing 对象。
    // 切到 cubicBezier 时保留/初始化 bezier（默 ease [0.25,0.1,0.25,1]）；
    // 切到其他预设时丢掉 bezier（非 cubicBezier 类型无 bezier 字段）。
    if (field.name === 'easing') {
        const newType = value as string;
        if (newType === 'cubicBezier') {
            const existingBezier = tweenEasing.value.bezier ?? [0.25, 0.1, 0.25, 1] as [number, number, number, number];
            edit.updateActionField(props.path, { easing: { type: 'cubicBezier', bezier: existingBezier } } as unknown as Partial<ScriptAction>);
        } else {
            edit.updateActionField(props.path, { easing: { type: newType } } as unknown as Partial<ScriptAction>);
        }
        return;
    }
    edit.updateActionField(props.path, { [field.name]: value } as Partial<ScriptAction>);
}

/**
 * EasingCurveEditor emit update:modelValue 时回写完整 Easing 对象。
 * 仅 tweenBlock 的 easing 字段用到。
 */
function onTweenEasingCurveUpdate(newEasing: Easing): void {
    edit.updateActionField(props.path, { easing: newEasing } as unknown as Partial<ScriptAction>);
}

// ---------- friendly 块字段读写（值落 action.patch，elementId 落顶层）----------

/**
 * friendly 块某 patch 字段的当前值（喂 BlockParamInput）。patch 存的是 string
 * （{@code Record<string,string>}），但 BlockParamInput 的 number 控件按 {@code typeof===number}
 * 受控显示——故 number 字段在此<b>转成 number</b>（非有限 → undefined 让控件显空）；其余字段原样
 * 返 string。缺失 → undefined（控件退默认）。写回时 {@link onFriendlyFieldUpdate} 再 String() 回 patch。
 */
function friendlyFieldValue(field: FieldDef): unknown {
    const v = friendlyPatch.value[field.name];
    if (v === undefined) return undefined;
    if (field.type === 'number') {
        const n = Number(v);
        return Number.isFinite(n) ? n : undefined;
    }
    return v;
}

/**
 * friendly 块 patch 字段改值回写：<b>整体替换 patch</b>（{@code { ...action.patch, [name]: String(v) }}），
 * 调 {@code updateActionField(path, { patch })}。复用现有 updateActionField immutable 重建链路——
 * patch 作为 action 的普通字段被 merge，blockId（path）不变（1 积木 1 path，同构守住）。
 */
function onFriendlyFieldUpdate(field: FieldDef, value: unknown): void {
    const nextPatch = { ...friendlyPatch.value, [field.name]: String(value) };
    edit.updateActionField(props.path, { patch: nextPatch } as Partial<ScriptAction>);
}

/** friendly 块「元素」字段当前值（action.elementId）。 */
const friendlyElementValue = computed(() =>
    isFriendly.value ? ((props.action as { elementId?: string }).elementId ?? '') : '',
);

/** friendly 块改元素：elementId 落顶层（非 patch），复用 updateActionField。 */
function onFriendlyElementUpdate(value: unknown): void {
    edit.updateActionField(props.path, { elementId: String(value) } as Partial<ScriptAction>);
}

/**
 * 本块的「元素」字段获得焦点 → 记下本积木 path 到 {@code activeElementBinding}，
 * 让预览框（PreviewPane）点选元素时知道把命中的 elementId（及当前坐标值）填到哪条积木。
 *
 * <p>挂在元素字段控件外层 {@code @focusin}（focus 会冒泡，故 focusin 而非 focus）：用户点元素
 * 下拉 / 给它 tab 焦点即标记。纯 UI 焦点态，不发 op、不受 lock 影响。常规块的 element 字段
 * （setElementProperty / nudgeElement 的 elementId）与友好块的 elementId 字段共用此入口。</p>
 */
function onElementFieldFocus(): void {
    edit.setActiveElementBinding(props.path);
}

/**
 * 「从预览点选」按钮的点击处理。
 *
 * <p>根因：element 字段是原生 {@code <select>}，展开浏览器原生下拉时去点预览元素，浏览器会
 * <b>先关下拉、吃掉这次点击</b>，PreviewPane 的 {@code onPointerDown} 收不到 → 没填。修复是在
 * 元素字段旁加一个独立小按钮：点它直接 {@code setActiveElementBinding(本积木 path)} 进入"预览
 * 点选态"（不碰 select、不展开原生下拉），用户随后去预览点元素时<b>没有下拉遮挡</b>，
 * PreviewPane.onPointerDown 正常触发回填 elementId + 当前值。原生 select 仍保留（下拉列表 /
 * 键盘选）。</p>
 *
 * <p>{@code stopPropagation}：拦住这次 click 冒泡到 BlockStack.onStackClick（避免重渲与
 * 进入点选态抢时序，与变量 picker 打开同理）。纯 UI 焦点态，不发 op、不受 lock 影响。</p>
 */
function onPickFromPreview(e: Event): void {
    e.stopPropagation();
    edit.setActiveElementBinding(props.path);
}

/**
 * 本积木是否处于"预览点选态"（activeElementBinding 指向本 path）——给「从预览点选」按钮高亮。
 * 点选填完（用户去预览点了元素 → 下拉与点选同步改 elementId，焦点态可能仍在）这里只反映绑定指向。
 */
const isPickingForThis = computed(() => edit.activeElementBinding === props.path);

/** 某字段是否「元素」类型（决定要不要给它挂 element-binding 焦点钩子）。 */
function isElementField(field: FieldDef): boolean {
    return field.type === 'element';
}

/**
 * runCommand 的复合 command 控件改值：BlockParamInput emit {@link CommandValue}
 * （templateId + params 一起），整体回写到 action 的两个字段。
 */
function onCommandUpdate(value: unknown): void {
    const v = value as CommandValue;
    if (!v || typeof v !== 'object') return;
    edit.updateActionField(props.path, {
        templateId: v.templateId,
        params: v.params,
    } as Partial<ScriptAction>);
}

/**
 * runCommand 当前的复合值（{ templateId, params }）喂给 command 控件。
 * 非 runCommand 块返空壳（控件不会被渲染，仅类型安全）。
 */
const commandValue = computed<CommandValue>(() => {
    if (props.action.type !== 'runCommand') return { templateId: '', params: {} };
    return { templateId: props.action.templateId, params: props.action.params };
});

/** if / randomBranch 的 then 子序列（非双臂块为空，不渲染子槽）。 */
const thenActions = computed<ScriptAction[]>(() =>
    (props.action.type === 'if' || props.action.type === 'randomBranch') ? props.action.then : [],
);
/** if / randomBranch 的 else 子序列。 */
const elseActions = computed<ScriptAction[]>(() =>
    (props.action.type === 'if' || props.action.type === 'randomBranch') ? props.action.else : [],
);
/**
 * repeat / repeatUntil 的 body 子序列（无 body 块为空）。子块 path 拼
 * {@code `${path}/body/${i}`}——<b>与后端 ScriptRunner 展开 / 动态压栈 blockId 逐字符同构</b>
 * （blockId 不带轮数，前端只一份 body）。
 */
const bodyActions = computed<ScriptAction[]>(() =>
    (props.action.type === 'repeat' || props.action.type === 'repeatUntil' || props.action.type === 'tweenBlock')
        ? props.action.body
        : [],
);

/**
 * condition 字段的原始串（喂给 ConditionBuilder）。从 if-only 泛化为「任何带
 * condition 字段的动作」——if 与 waitUntil 都有 type:'condition' 字段（{@link conditionField}
 * 已按 type 自动识别）；读值时按 action 上的 {@code condition} 属性取，二者同名故统一读。
 * 写回走 {@link onConditionUpdate} 的 {@code updateActionField}（本就泛化，不限 if）。
 */
const conditionText = computed(() => {
    const a = props.action as unknown as { condition?: string };
    return typeof a.condition === 'string' ? a.condition : '';
});

/** if 的 condition 改值回写（ConditionBuilder emit 出 build 后的 / 高级模式原串）。 */
function onConditionUpdate(value: string): void {
    edit.updateActionField(props.path, { condition: value } as Partial<ScriptAction>);
}

// ---------- 视觉：必填引用字段未填的"待选择"角标（不改逻辑，只读判定）----------

/**
 * 本块里"必填但还没填"的字段（一眼提示用户哪个积木哪个字段要填）。
 * 从原来仅 element / timeline / command 三类，扩展到也覆盖 <b>variable（变量名）/ condition（if 条件）/
 * sound（声音）</b>——这些字段虽有合理默认（拖出即合法），但用户可清空 / 删空，空了就该提示。
 *
 * <p>判定（空 = 空串 / 缺失，与 validator 的 blank 同口径）：</p>
 * <ul>
 *   <li>{@code element} / {@code timeline}：依赖墙上有什么，必填——空则标；</li>
 *   <li>{@code variable}：变量名必填（setVariable / incrementVariable / variableChange 的 fullName）——空则标；</li>
 *   <li>{@code sound}：playSound 的 soundId 必填——空则标；</li>
 *   <li>{@code condition}：if 的条件必填——空则标（condition 字段不走 scalarFields，单独判）；</li>
 *   <li>{@code command}（runCommand 复合）：只看 templateId 是否空。</li>
 * </ul>
 * <p>纯读 def.fields + action 当前值，不碰 validator / 默认值逻辑。返回字段友好名数组喂角标 hover
 * 提示（"还需填写：变量名 / 条件"）。</p>
 */
const missingRefFields = computed<string[]>(() => {
    const out: string[] = [];
    const rec = props.action as unknown as Record<string, unknown>;
    const blank = (name: string): boolean => {
        const v = rec[name];
        return typeof v !== 'string' || v.trim() === '';
    };
    // friendly 块（setElementProperties）：def 为 null，必填引用字段是 elementId——空则标。
    if (isFriendly.value) {
        if (blank('elementId')) {
            out.push(resolveLabelKey(t.value, FRIENDLY_ELEMENT_FIELD.labelKey));
        }
        return out;
    }
    for (const f of def.value?.fields ?? []) {
        if (f.type === 'element' || f.type === 'timeline' || f.type === 'variable'
            || f.type === 'sound' || f.type === 'condition') {
            if (blank(f.name)) out.push(resolveLabelKey(t.value, f.labelKey));
        } else if (f.type === 'command' && isRunCommand.value && f.name === 'templateId') {
            if (blank('templateId')) out.push(resolveLabelKey(t.value, f.labelKey));
        }
    }
    return out;
});

/** 是否显示"待完善"角标（有空的必填字段时）。 */
const showNeedSelect = computed(() => missingRefFields.value.length > 0);

/** 角标 hover 提示文案（"还需填写：变量名 / 条件"）。 */
const needSelectTitle = computed(() =>
    t.value.script.param.needSelect.replace('{field}', missingRefFields.value.join(' / ')),
);
</script>

<template>
  <div
    class="hc-block-node"
    :class="[(isIf || hasBodySlot) ? 'hc-block-node-if' : '', highlightResult ? 'hc-block-node-hl' : '']"
    :data-block-path="path"
    :data-block-type="action.type"
    :data-hl-result="highlightResult || null"
    :title="highlightDetail || undefined"
    :style="{ '--hc-block-color': effectiveBorderColor, ...highlightStyle }"
    @pointerdown="onBlockPointerDown"
  >
    <!-- 视觉：必填引用字段（元素 / 时间轴 / 命令）还没填时的温和橙色待选角标 -->
    <span
      v-if="showNeedSelect"
      class="hc-block-need-select"
      :title="needSelectTitle"
      aria-hidden="true"
    />

    <!-- 头部：标题 + 标量参数槽（F：真表单控件 BlockParamInput） -->
    <div class="hc-block-head">
      <span class="hc-block-title">{{ title }}</span>

      <!-- 友好元素积木：先元素下拉，再皮肤字段（值落 action.patch）。
           show/hide 的 friendlyFields 为空 → 只渲染元素下拉。 -->
      <template v-if="isFriendly">
        <!-- 元素字段外层 @focusin → 记 activeElementBinding（预览点选填到本积木） -->
        <span class="hc-block-param-input" @focusin="onElementFieldFocus">
          <BlockParamInput
            :field="FRIENDLY_ELEMENT_FIELD"
            :value="friendlyElementValue"
            :action-kind="action.type"
            :disabled="locked"
            @update="onFriendlyElementUpdate"
          />
          <!-- 独立「从预览点选」按钮——点它进入预览点选态（不展开原生下拉，绕开"点预览被
               下拉吃掉"）。 -->
          <button
            type="button"
            class="hc-pick-from-preview"
            :class="{ 'hc-pick-active': isPickingForThis }"
            :disabled="locked"
            :title="t.script.param.pickFromPreview"
            :aria-label="t.script.param.pickFromPreview"
            @click="onPickFromPreview"
          >
            <Crosshair class="size-3" />
          </button>
        </span>
        <!-- 皮肤字段(moveTo/resize/rotateTo 的 x/y/w/h/rotation) focusin →
             setActiveElementBinding(本积木)，让预览自动显该坐标积木虚影可拖（复用元素字段同 setter）。 -->
        <span
          v-for="f in friendlyFields"
          :key="f.name"
          class="hc-block-param-input"
          @focusin="onElementFieldFocus"
        >
          <BlockParamInput
            :field="f"
            :value="friendlyFieldValue(f)"
            :action-kind="action.type"
            :disabled="locked"
            @update="(v: unknown) => onFriendlyFieldUpdate(f, v)"
          />
        </span>
      </template>

      <!-- 常规块：按 def.fields 渲染标量参数槽。元素字段（setElementProperty / nudgeElement 的
           elementId）外层 @focusin → 记 activeElementBinding（预览点选填到本积木）。 -->
      <template v-else v-for="f in scalarFields" :key="f.name">
        <span v-if="isElementField(f)" class="hc-block-param-input" @focusin="onElementFieldFocus">
          <BlockParamInput
            :field="f"
            :value="fieldValue(f)"
            :action-kind="action.type"
            :disabled="locked"
            @update="(v: unknown) => onFieldUpdate(f, v)"
          />
          <!-- 独立「从预览点选」按钮（同友好块路径，绕开原生下拉吃点击）。 -->
          <button
            type="button"
            class="hc-pick-from-preview"
            :class="{ 'hc-pick-active': isPickingForThis }"
            :disabled="locked"
            :title="t.script.param.pickFromPreview"
            :aria-label="t.script.param.pickFromPreview"
            @click="onPickFromPreview"
          >
            <Crosshair class="size-3" />
          </button>
        </span>
        <BlockParamInput
          v-else
          class="hc-block-param-input"
          :field="f"
          :value="fieldValue(f)"
          :action-kind="action.type"
          :disabled="locked"
          @update="(v: unknown) => onFieldUpdate(f, v)"
        />
        <!-- 缓动字段选到「自定义曲线」时额外渲染 EasingCurveEditor（复用 SVG 编辑器）。
             data 是完整 Easing 对象（含 bezier），emit 也写完整 Easing 对象。 -->
        <div
          v-if="f.name === 'easing' && isTweenBlock && isTweenEasingCustom"
          class="hc-tween-curve-editor"
        >
          <EasingCurveEditor
            :model-value="tweenEasing"
            @update:model-value="onTweenEasingCurveUpdate"
          />
        </div>
      </template>
    </div>

    <!-- runCommand 复合 command 控件（templateId + 动态 params 子输入） -->
    <div v-if="commandField" class="hc-block-command">
      <BlockParamInput
        :field="commandField"
        :value="commandValue"
        :action-kind="action.type"
        :disabled="locked"
        @update="onCommandUpdate"
      />
    </div>

    <!-- 非 C 形动作（waitUntil）的条件行——复用 ConditionBuilder（同 if 的条件控件）。
         if 的条件行在下方 C 形块里渲染，故这里仅当有 conditionField 且非 if 时渲染，避免重复。 -->
    <div v-if="conditionField && !isIf" class="hc-block-condition">
      <span class="hc-param-label">{{ fieldLabel(conditionField) }}</span>
      <ConditionBuilder
        :condition="conditionText"
        :wall-id="project.wallId"
        @update="onConditionUpdate"
      />
    </div>

    <!-- if 块 C 形：条件 + then/else 子序列槽（实色左臂实体包裹，非虚线引导线） -->
    <template v-if="isIf">
      <div v-if="conditionField" class="hc-block-condition">
        <span class="hc-param-label">{{ fieldLabel(conditionField) }}</span>
        <ConditionBuilder
          :condition="conditionText"
          :wall-id="project.wallId"
          @update="onConditionUpdate"
        />
      </div>

      <!-- then 子槽（实色 C 臂 + 实体托底，子积木嵌在臂内） -->
      <div class="hc-block-branch">
        <span class="hc-branch-label">{{ resolveLabelKey(t, 'script.fields.then') }}</span>
        <div class="hc-branch-slot">
          <BlockNode
            v-for="(child, i) in thenActions"
            :key="`${path}/then/${i}`"
            :action="child"
            :path="`${path}/then/${i}`"
          />
          <!-- 空 then 槽：data-slot-path 让 collectSlots 当 index=0 落点 -->
          <div
            v-if="thenActions.length === 0"
            class="hc-empty-slot"
            :data-slot-path="`${path}/then`"
          >
            {{ resolveLabelKey(t, 'script.emptySlot') }}
          </div>
        </div>
      </div>

      <!-- else 子槽 -->
      <div class="hc-block-branch">
        <span class="hc-branch-label">{{ resolveLabelKey(t, 'script.fields.else') }}</span>
        <div class="hc-branch-slot">
          <BlockNode
            v-for="(child, i) in elseActions"
            :key="`${path}/else/${i}`"
            :action="child"
            :path="`${path}/else/${i}`"
          />
          <!-- 空 else 槽：data-slot-path 让 collectSlots 当 index=0 落点 -->
          <div
            v-if="elseActions.length === 0"
            class="hc-empty-slot"
            :data-slot-path="`${path}/else`"
          >
            {{ resolveLabelKey(t, 'script.emptySlot') }}
          </div>
        </div>
      </div>

      <!-- C 形底托：闭合左臂，视觉上把 then/else 包成 C（实体托底条） -->
      <div class="hc-block-if-foot" aria-hidden="true" />
    </template>

    <!-- repeat / repeatUntil 块 C 形（单臂）：标量（count / maxIterations）+
         repeatUntil 的 condition 行均在上方已渲染，这里只画 body 子序列槽。
         子块 path = `${path}/body/${i}`，与后端 ScriptRunner 展开 / 动态压栈 blockId 同构（不带轮数）。 -->
    <template v-if="hasBodySlot">
      <div class="hc-block-branch">
        <span class="hc-branch-label">{{ resolveLabelKey(t, 'script.fields.body') }}</span>
        <div class="hc-branch-slot">
          <BlockNode
            v-for="(child, i) in bodyActions"
            :key="`${path}/body/${i}`"
            :action="child"
            :path="`${path}/body/${i}`"
          />
          <!-- 空 body 槽：data-slot-path 让 collectSlots 当 index=0 落点 -->
          <div
            v-if="bodyActions.length === 0"
            class="hc-empty-slot"
            :data-slot-path="`${path}/body`"
          >
            {{ resolveLabelKey(t, 'script.emptySlot') }}
          </div>
        </div>
      </div>

      <!-- C 形底托：闭合左臂，视觉上把 body 包成 C（实体托底条） -->
      <div class="hc-block-if-foot" aria-hidden="true" />
    </template>
  </div>
</template>

<style scoped>
/*
 * Scratch 风积木块。
 *
 * 设计要点：
 *   - 实色块：背景直填分类色 --hc-block-color（由模板内联给，= trace 高亮色或分类色），
 *     文字走 --hc-block-fg（浅主题白 / 深主题深），对比清晰；不再 color-mix 淡化成 IDE 风。
 *   - 咬合感：::before 在块顶画梯形榫头（Scratch 块顶的凸起），配合 BlockStack 里 gap≈0
 *     的焊接堆叠，凸榫"插进"上一块底部，连成一摞。
 *   - 立体感：轻微 box-shadow（块在画布上浮起一点）+ 顶部高光内描边（强化白高光）。
 *   - 节奏统一：CSS 变量控制圆角 / 内距 / 凸榫尺寸，消除散乱硬编码。
 *   - hover 上浮：translateY(-1px) + 阴影加深，「积木被拿起」手感；transition 含 transform。
 */

/* ===== 节奏变量（块级本地；BlockStack 各自定义一套同名变量保持一致）===== */
.hc-block-node {
    /* 圆角节奏 */
    --bn-radius: 8px;
    --bn-radius-sm: 5px;
    /* 凸榫尺寸 */
    --hc-notch-w: 20px;
    --hc-notch-h: 5px;
    --hc-notch-x: 14px;
    /* 内距节奏 */
    --bn-px: 11px;
    --bn-py-t: 8px;
    --bn-py-b: 9px;
    /* 焊接间隙（与 BlockStack.hc-stack-actions gap 一致） */
    --bn-gap: 7px;

    position: relative;
    border-radius: var(--bn-radius);
    /* 实色块：分类色直填（模板给 --hc-block-color）；缺省退边框灰 */
    background: var(--hc-block-color, var(--border));
    color: var(--hc-block-fg);
    /* 立体：底部投影 + 顶部内高光（强化白高光至 32%） */
    box-shadow:
        0 2px 0 color-mix(in srgb, var(--hc-block-color, var(--border)) 62%, #000),
        0 3px 6px rgba(0, 0, 0, 0.22),
        inset 0 1px 0 color-mix(in srgb, #fff 32%, transparent);
    padding: var(--bn-py-t) var(--bn-px) var(--bn-py-b) var(--bn-px);
    user-select: none;
    cursor: grab;
    /* H：试跑高亮 + hover 上浮一起过渡 */
    transition: box-shadow 0.12s ease, background-color 0.12s ease, filter 0.12s ease, transform 0.1s ease;
}

/* 块顶凸榫：梯形榫头（clip-path 剪成 Scratch 经典梯形，下宽 20px / 上宽 13px / 高 5px）。
 * 实色同 --hc-block-color，高光内描边与块面协调。
 * top 偏移：把榫头大部分压进块顶之上（-h+2px 留 2px 可见），对接上一块底部。 */
.hc-block-node::before {
    content: "";
    position: absolute;
    top: calc(var(--hc-notch-h) * -1 + 1px);
    left: var(--hc-notch-x);
    width: var(--hc-notch-w);
    height: var(--hc-notch-h);
    background: var(--hc-block-color, var(--border));
    /* 梯形榫头：根部（贴块的下边 y=100%）全宽 20、尖端（凸出的上边 y=0%）两侧各收 3.5px → 上宽 13。
     * 注：::before top 为负、向上凸，故 y=100% 是贴块根部、y=0% 是凸出尖端 → 根宽尖窄的正榫头。 */
    clip-path: polygon(0% 100%, 100% 100%, calc(100% - 3.5px) 0%, 3.5px 0%);
    box-shadow: inset 0 1px 0 color-mix(in srgb, #fff 28%, transparent);
}

/* hover 上浮：translateY(-1px) + 底部阴影加深。
 * 拖拽系统不给块加 class（setPointerCapture + ghost overlay），:active 处理 grabbing cursor，
 * :hover 在 pointer 已被 capture 时通常不匹配，故无需额外排除选择器。 */
.hc-block-node:hover {
    filter: brightness(1.06);
    transform: translateY(-1px);
    box-shadow:
        0 3px 0 color-mix(in srgb, var(--hc-block-color, var(--border)) 62%, #000),
        0 5px 10px rgba(0, 0, 0, 0.28),
        inset 0 1px 0 color-mix(in srgb, #fff 32%, transparent);
}
.hc-block-node:active {
    cursor: grabbing;
}
.hc-block-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--bn-gap);
}
.hc-block-title {
    font-size: 12.5px;
    font-weight: 700;
    white-space: nowrap;
    color: var(--hc-block-fg);
    /* 饱和块面上文字立体感：暗描边（底部 1px 暗 + 轻微 Y 方向扩散），不过重不发糊 */
    text-shadow:
        0 1px 0 color-mix(in srgb, var(--hc-block-color, var(--border)) 60%, #000),
        0 0 3px color-mix(in srgb, #000 12%, transparent);
}
/* F：参数控件（BlockParamInput）外壳——不再加浅底块，胶囊样式由控件自身承载；
 * 这里只保证内联排版 + 不撑破块宽。元素字段壳里还含一个「从预览点选」小按钮，留点间距。 */
.hc-block-param-input {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    max-width: 100%;
}

/* 「从预览点选」靶心小按钮（紧贴元素下拉右侧）。圆形胶囊，与参数控件同视觉语言。
 * 点它进入"预览点选态"（不展开原生下拉），高亮态（hc-pick-active）= 当前积木正等着去预览点元素。 */
.hc-pick-from-preview {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: 0 0 auto;
    width: 22px;
    height: 22px;
    border: 1px solid color-mix(in srgb, #000 16%, transparent);
    border-radius: 50%;
    background: var(--card);
    color: var(--foreground);
    cursor: pointer;
    box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.1);
    transition: filter 0.1s ease, border-color 0.1s ease, box-shadow 0.1s ease, background-color 0.1s ease;
}
.hc-pick-from-preview:hover:not(:disabled) {
    border-color: var(--ring);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--ring) 35%, transparent);
}
.hc-pick-from-preview:disabled {
    opacity: 0.55;
    cursor: not-allowed;
}
/* 高亮态：当前积木正处于预览点选态（activeElementBinding 指向本块）——靶心按钮点亮 mauve，
 * 提示"现在去右侧预览点元素就会填到这条积木"。 */
.hc-pick-active {
    background: var(--ctp-mauve, var(--primary));
    color: var(--card);
    border-color: var(--ctp-mauve, var(--primary));
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--ctp-mauve, var(--primary)) 40%, transparent);
}
/* runCommand 复合 command 控件：占整行（模板下拉 + 动态 params 子表单纵向铺开）。 */
.hc-block-command {
    margin-top: 6px;
    width: 100%;
}
/* 自定义缓动曲线编辑器容器（tweenBlock 选 cubicBezier 时展开）。
 * 占块整宽，与积木头部 flex-wrap 同行后换行展开。背景用半透明深底适配积木实色面。 */
.hc-tween-curve-editor {
    width: 100%;
    margin-top: 6px;
    background: color-mix(in srgb, #000 18%, transparent);
    border-radius: var(--bn-radius-sm);
    padding: 2px;
}
/* 块面上的字段标签（如 if 条件 / then / else 前缀）走块前景色的柔和版 */
.hc-param-label {
    color: var(--hc-block-fg-soft);
    white-space: nowrap;
}
.hc-param-value {
    color: var(--hc-block-fg);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* ---------- 待选择角标（必填引用字段未填）---------- */
.hc-block-need-select {
    position: absolute;
    top: -4px;
    right: -4px;
    width: 11px;
    height: 11px;
    border-radius: 50%;
    background: var(--ctp-peach, #f59e0b);
    border: 1.5px solid var(--card);
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
    z-index: 3;
    cursor: help;
}

/* ---------- if = 实体 C 形 ---------- */
/* if 块整体：底部不收圆角（由底托闭合），padding 左侧让出实色臂宽 */
.hc-block-node-if {
    padding-bottom: 0;
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
}
.hc-block-condition {
    margin-top: 6px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--bn-radius-sm);
    font-size: 11px;
}
.hc-block-branch {
    margin-top: 6px;
}
.hc-branch-label {
    display: inline-block;
    font-size: 10px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--hc-block-fg-soft);
    margin-bottom: 4px;
}
/* C 臂：实色竖条把 then/else 子序列实体包进去（非虚线引导线）。
 * 左侧负 margin 把臂铺到块左边缘（抵消块的 --bn-px 左 padding），右侧同；
 * 子积木相对臂内缩 18px，左边露出的实色即 C 的左臂。 */
.hc-branch-slot {
    position: relative;
    display: flex;
    flex-direction: column;
    /* 子积木焊接堆叠：小缝 + 块顶凸榫(::before)桥接，读作"咬在一起" */
    gap: 3px;
    margin-left: calc(var(--bn-px) * -1);
    margin-right: calc(var(--bn-px) * -1);
    padding: 4px 8px 6px 18px;
    /* 实色左臂 + 顶部内阴影，形成 C 的内拐角 */
    background: var(--hc-block-color, var(--border));
    box-shadow: inset 0 1px 0 color-mix(in srgb, #000 14%, transparent);
    min-height: 26px;
}
/* C 形底托：实色横条闭合左臂，整体收下圆角（使用节奏变量） */
.hc-block-if-foot {
    height: 12px;
    margin-left: calc(var(--bn-px) * -1);
    margin-right: calc(var(--bn-px) * -1);
    background: var(--hc-block-color, var(--border));
    border-radius: 0 0 var(--bn-radius) var(--bn-radius);
    box-shadow:
        0 2px 0 color-mix(in srgb, var(--hc-block-color, var(--border)) 62%, #000),
        0 3px 6px rgba(0, 0, 0, 0.22);
}
.hc-empty-slot {
    font-size: 11px;
    color: var(--hc-block-fg-soft);
    font-style: italic;
    padding: 5px 8px;
    /* 浅色凹槽提示空槽（半透明深底 + 内阴影，像块里"挖空"了一格） */
    background: var(--hc-block-slot);
    border: 1px dashed color-mix(in srgb, var(--hc-block-fg) 35%, transparent);
    border-radius: 5px;
    box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.25);
}
</style>
