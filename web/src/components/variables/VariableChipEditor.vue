<script setup lang="ts">
/**
 * 0.4.1 chip 编辑器（M28-0.4.1-P1+P2）。
 *
 * <p>替代 0.4.0 的 textarea：把 {@code ${var:X}} 占位符渲染成 Notion-style chip pill。
 * 数据模型不变——v-model:text 仍是 element.text 字符串字面值；chip 仅 UI 层。</p>
 *
 * <h4>Lexical 路线决策</h4>
 * <p>试验了 {@code lexical-vue}（vue-vine 编译产物，runtime 兼容但 .d.ts 用了
 * {@code vue-vine/internals} 类型，vue-tsc 会报错且无法直接配置 DecoratorNode
 * 渲染管线）→ <b>放弃</b>。最终选 <b>lexical core 直接接 Vue</b>：自己写 reactive bridge
 * （editor.update / editor.registerUpdateListener）+ DecoratorNode createDOM 直接管 chip
 * DOM——零编译依赖，完全可控。详见 {@code src/variable/lexicalChip.ts}。</p>
 *
 * <h4>本组件职责</h4>
 * <ul>
 *   <li>挂载 lexical headless editor + ContentEditable div + PlainTextPlugin + HistoryPlugin</li>
 *   <li>同步 text prop ↔ EditorState（避免 outer ↔ inner 互相打架的环：external watch 改 vs
 *     local update 改各自带 guard）</li>
 *   <li>chip hover：tooltip 显示原始 placeholder + currentValue + source</li>
 *   <li>chip click：emit editVariableRequest，外层弹 VariablePicker 改绑定</li>
 *   <li>{@code ${} 触发：监听 update listener 检测最近输入的 2 字符是否触发 picker</li>
 *   <li>store 变化时更新 chip DOM 文本（currentValue / fallback / "???"）</li>
 * </ul>
 *
 * <h4>props 摘要</h4>
 * <ul>
 *   <li>text - element.text 原文（含 {@code ${var:X}} 字面）；双向 v-model</li>
 *   <li>wallId - 注入 interpolator wallId（{@code user/X → user:wallId/X}）</li>
 *   <li>fontSize / fontFamily - inline editor 用，跟 element 字体一致</li>
 *   <li>multiLine - true → block 容器；false → inline；默认 true</li>
 *   <li>autoFocus - mount 时 focus（inline editor 用）</li>
 *   <li>disabled - 锁定 wall 时只读</li>
 * </ul>
 */
import {
    computed,
    onBeforeUnmount,
    onMounted,
    onUnmounted,
    ref,
    watch,
} from 'vue';
import { createEditor, $getRoot, $getSelection, $isRangeSelection } from 'lexical';
import { registerPlainText } from '@lexical/plain-text';
import { registerHistory, createEmptyHistoryState } from '@lexical/history';
import { mergeRegister } from '@lexical/utils';
import { useVariableStore } from '@/stores/variables';
import { useVariableAliasStore } from '@/stores/variableAliases';
import { UNRESOLVED, resolveFullName } from '@/variable/interpolator';
import { useI18n } from '@/i18n';
import {
    CHIP_CLASS,
    CHIP_DATA_FALLBACK,
    CHIP_DATA_RAW_NAME,
    CHIP_EVENT_CLICK,
    CHIP_EVENT_HOVER,
    CHIP_EVENT_LEAVE,
    VariablePlaceholderNode,
    lexicalRootToText,
    textToLexicalNodes,
    registerVariablePasteTransform,
    $insertVariableChipAtSelection,
    $isVariablePlaceholderNode,
} from '@/variable/lexicalChip';

interface Props {
    text: string;
    wallId: string | null;
    fontSize?: number;
    fontFamily?: string;
    multiLine?: boolean;
    autoFocus?: boolean;
    disabled?: boolean;
    /** 容器额外 class（外层皮肤；不影响 chip 内部样式）。 */
    rootClass?: string;
}
const props = withDefaults(defineProps<Props>(), {
    fontSize: undefined,
    fontFamily: undefined,
    multiLine: true,
    autoFocus: false,
    disabled: false,
    rootClass: '',
});

const emit = defineEmits<{
    'update:text': [value: string];
    submit: [];
    cancel: [];
    /** 用户输入了 ${ 或点了"插入变量"按钮 → 外层弹 VariablePicker。 */
    insertVariableRequest: [anchor: HTMLElement];
    /** 点击已有 chip → 外层弹 VariablePicker 改绑定。 */
    editVariableRequest: [payload: { rawName: string; fallback: string | null; anchor: HTMLElement }];
    /** P3.4：点击红色 (error) chip → 外层弹 "create" 确认对话框（变量缺失补创）。 */
    createVariableRequest: [payload: { rawName: string; anchor: HTMLElement }];
}>();

const store = useVariableStore();
const aliasStore = useVariableAliasStore();
const { t } = useI18n();

const rootRef = ref<HTMLDivElement | null>(null);
const editableRef = ref<HTMLDivElement | null>(null);

// ----------- tooltip 状态（hover chip 时显示） -----------
const tooltipVisible = ref(false);
const tooltipX = ref(0);
const tooltipY = ref(0);
const tooltipRawName = ref('');
const tooltipFallback = ref<string | null>(null);

/**
 * P3.2：chip 缩放因子。根据 props.fontSize 在 [0.6, 1.2] 范围 clamp，让 chip
 * pill 在极小（fontSize=6 → 0.6×）/ 极大（fontSize=120 → 1.2×）字号下都保持
 * 视觉协调。CSS 通过 `--chip-scale` 变量消费（padding / border-radius 跟着缩）。
 */
const chipScale = computed(() => {
    const f = props.fontSize;
    if (!f || !Number.isFinite(f)) return 1;
    const ratio = f / 14;
    return Math.max(0.6, Math.min(1.2, ratio));
});

const tooltipFullName = computed(() =>
    resolveFullName(tooltipRawName.value, props.wallId),
);
const tooltipVariable = computed(() => {
    if (!tooltipRawName.value) return null;
    return store.get(tooltipFullName.value) ?? null;
});
const tooltipDisplayValue = computed(() => {
    const v = tooltipVariable.value;
    if (v && v.currentValue != null && v.currentValue.length > 0) return v.currentValue;
    if (tooltipFallback.value != null) return tooltipFallback.value;
    if (v && v.defaultValue != null) return v.defaultValue;
    return UNRESOLVED;
});
const tooltipSource = computed(() => tooltipVariable.value?.source ?? null);
const tooltipDeleted = computed(() => tooltipVariable.value == null);
/** 0.4.2：tooltip 也展示别名（如有），让用户在 chip 上 hover 就能确认这块占位实际指向。 */
const tooltipAlias = computed(() => aliasStore.get(tooltipFullName.value));

// ----------- lexical editor 实例 -----------
// 不用 ref（避免响应式遍历内部 Map/Set 触发 Vue 警告）；用普通变量。
let editor: ReturnType<typeof createEditor> | null = null;
let unregisterAll: (() => void) | null = null;
/** flag：external watch 在改 editor 内容，避免触发 onUpdate 回写 props.text 引起循环。 */
let writingExternal = false;
/** 最近的 ${ 触发 anchor：textarea -> contenteditable 转换后由 caret 找最近 chip 容器。 */
let lastDollarBraceAnchor: HTMLElement | null = null;
/**
 * 0.4.2 bugfix（Bug 3）：IME composition flag。中文 / 日文 / 韩文输入法在 compose 期间
 * 会持续触发 update listener（每按一键 dirtyLeaves > 0），把临时拼音字符 emit('update:text')
 * 后外层 watch 把它写回 lexical，**打断 composition** → 中文输入断流。
 *
 * <p>修法：监听 {@code compositionstart} / {@code compositionend}：composition 期间屏蔽
 * emit；composition 结束后一次性 emit 最新文本。</p>
 */
let composing = false;

/** 0.4.2 bugfix（Bug A）：Pinia store.$subscribe 返回的反订阅函数，onBeforeUnmount 调。 */
const storeUnsubscribers: Array<() => void> = [];

// ============================================================================
// 初始化 / 销毁
// ============================================================================

onMounted(() => {
    if (!editableRef.value) return;
    editor = createEditor({
        namespace: 'hc-chip-editor',
        onError: (err) => {
            // eslint-disable-next-line no-console
            console.error('[VariableChipEditor]', err);
        },
        nodes: [VariablePlaceholderNode],
        editable: !props.disabled,
    });
    editor.setRootElement(editableRef.value);

    // 初始内容
    writingExternal = true;
    editor.update(
        () => {
            textToLexicalNodes(props.text ?? '');
        },
        { discrete: true },
    );
    writingExternal = false;

    // plain-text + history 插件 + P3.6 paste transform（plain text `${var:...}` → chip）
    unregisterAll = mergeRegister(
        registerPlainText(editor),
        registerHistory(editor, createEmptyHistoryState(), 1000),
        registerVariablePasteTransform(editor),
        editor.registerUpdateListener(({ editorState, dirtyElements, dirtyLeaves }) => {
            // 忽略外部 write 触发的更新
            if (writingExternal) return;
            // 0.4.2 bugfix（Bug 3）：IME composition 期间屏蔽 emit，避免临时拼音字符 commit 后
            // 被外层 watch 写回 lexical 打断 composition；composition 结束在 onCompositionEnd
            // 里一次性 emit 最终文本。
            if (composing) return;
            // 没有真改时跳过（selection only 也会触发 update，但 dirty 集合空）
            if (dirtyElements.size === 0 && dirtyLeaves.size === 0) return;
            editorState.read(() => {
                const newText = lexicalRootToText();
                if (newText !== props.text) {
                    emit('update:text', newText);
                }
                // 0.4.2 bugfix（Bug 2 彻底版）：${ 触发检测从 update listener 内移除，改用原生
                // beforeinput event 在 onBeforeInput 内精确捕捉（inputType=insertText + data='{'
                // 且前一字符是 $）。原 dirtyLeaves 守卫无效——lexical click 后 caret 落位仍标
                // dirtyLeaves，会把"用户只是点击编辑框现有 ${ 字面后位置"误判为输入。
            });
            // 文本变了 → store 仍然可能没变；刷一遍 chip 显示
            refreshAllChipDisplays();
        }),
    );

    // chip 事件代理（mount 时挂在 editable root，捕获冒泡上来的自定义事件）
    // 0.4.2 bugfix（Bug 2 彻底版）：chip span 不再 dispatch CHIP_EVENT_CLICK，
    // 但 listener 仍保留作 no-op（不破坏 ParentChild contract）；hover/leave 是 tooltip 必需。
    editableRef.value.addEventListener(CHIP_EVENT_HOVER, onChipHover as EventListener);
    editableRef.value.addEventListener(CHIP_EVENT_LEAVE, onChipLeave as EventListener);
    editableRef.value.addEventListener('keydown', onEditableKeydown);
    editableRef.value.addEventListener('blur', onEditableBlur);
    // 0.4.2 bugfix（Bug 3 保留）：composition 守 IME 输入；
    // 0.4.2 bugfix（Bug 2 彻底版）：移除 beforeinput → ${} 自动触发 picker 路径——
    // 用户只想 "插入变量" 按钮触发（外层 TextElementSection openPickerFromButton）。
    editableRef.value.addEventListener('compositionstart', onCompositionStart);
    editableRef.value.addEventListener('compositionend', onCompositionEnd);

    // 0.4.2 bugfix（Bug A 重启后 chip 显变量名）：Pinia store $subscribe 监听 mutation 立即
    // refresh chip 显示。原 watch(() => [store.variables, aliasStore.aliases]) 不可靠：
    // Pinia setup store 内 ref.value=new Map(...) 替换在 mount 顺序复杂时浅 watch 不触发。
    // $subscribe 是 Pinia 官方推荐的 mutation 订阅 API，100% 触发。
    storeUnsubscribers.push(store.$subscribe(() => refreshAllChipDisplays()));
    storeUnsubscribers.push(aliasStore.$subscribe(() => refreshAllChipDisplays()));

    // 首次刷 chip 显示
    refreshAllChipDisplays();

    if (props.autoFocus) {
        // setRootElement 完后下一 microtask focus 才稳
        queueMicrotask(() => focus());
    }
});

onBeforeUnmount(() => {
    if (editableRef.value) {
        editableRef.value.removeEventListener(CHIP_EVENT_HOVER, onChipHover as EventListener);
        editableRef.value.removeEventListener(CHIP_EVENT_LEAVE, onChipLeave as EventListener);
        editableRef.value.removeEventListener('keydown', onEditableKeydown);
        editableRef.value.removeEventListener('blur', onEditableBlur);
        editableRef.value.removeEventListener('compositionstart', onCompositionStart);
        editableRef.value.removeEventListener('compositionend', onCompositionEnd);
    }
    // 反订阅 store
    for (const unsub of storeUnsubscribers) {
        try { unsub(); } catch { /* ignore */ }
    }
    storeUnsubscribers.length = 0;
});

onUnmounted(() => {
    if (unregisterAll) unregisterAll();
    unregisterAll = null;
    if (editor) {
        // 清空 root 避免 lexical 留 listener；setRootElement(null) 是官方推荐 teardown
        editor.setRootElement(null);
    }
    editor = null;
});

// ============================================================================
// 外部 props.text 变 → 同步到 editor（避 caret jumping：只在真不一致时改）
// ============================================================================

watch(
    () => props.text,
    (newText) => {
        if (!editor) return;
        let currentInner = '';
        editor.read(() => {
            currentInner = lexicalRootToText();
        });
        if (currentInner === newText) return; // 已同步（多半是 emit('update:text') 反弹回来）
        writingExternal = true;
        editor.update(
            () => {
                textToLexicalNodes(newText ?? '');
            },
            { discrete: true },
        );
        writingExternal = false;
        refreshAllChipDisplays();
    },
);

watch(
    () => props.disabled,
    (d) => {
        if (editor) editor.setEditable(!d);
    },
);

// ============================================================================
// chip 显示：watch store 变化 → 更新所有 chip 的文本（chip DOM 内只是占位 rawName，
// 真实 currentValue 由本组件以 store 数据填充）
// ============================================================================

watch(
    () => [props.wallId, store.variables, aliasStore.aliases] as const,
    () => {
        refreshAllChipDisplays();
    },
    { deep: false },
);

function refreshAllChipDisplays(): void {
    const root = editableRef.value;
    if (!root) return;
    const chips = root.querySelectorAll<HTMLElement>(`.${CHIP_CLASS}`);
    chips.forEach((chip) => {
        const rawName = chip.getAttribute(CHIP_DATA_RAW_NAME) ?? '';
        if (!rawName) return;
        const fallback = chip.getAttribute(CHIP_DATA_FALLBACK);
        const fullName = resolveFullName(rawName, props.wallId);
        const v = store.get(fullName);
        // 0.4.2：chip 显示优先级 alias > currentValue > fallback > defaultValue > UNRESOLVED。
        // 别名是用户自定义的"短名"，肉眼一致性优先 currentValue（数值可能动态变化，别名稳定）。
        const alias = aliasStore.get(fullName);
        let displayValue: string;
        let deleted = false;
        if (v) {
            if (alias != null && alias.length > 0) displayValue = alias;
            else if (v.currentValue != null && v.currentValue.length > 0) displayValue = v.currentValue;
            else if (fallback != null) displayValue = fallback;
            else if (v.defaultValue != null) displayValue = v.defaultValue;
            else displayValue = UNRESOLVED;
        } else {
            // 变量不存在 → 用 fallback 或 "???"，并加 deleted class（即便有 alias 也按缺失处理）
            displayValue = fallback != null ? fallback : UNRESOLVED;
            deleted = true;
        }
        chip.textContent = displayValue;
        chip.classList.toggle('hc-chip-error', deleted);
    });
}

// ============================================================================
// chip 事件处理（hover / click）
// ============================================================================

function onChipClick(ev: CustomEvent<{ rawName: string; fallback: string | null }>) {
    if (props.disabled) return;
    const target = ev.target as HTMLElement;
    // P3.4：点击红 chip（error 态 = 变量缺失）→ 走 create 路径而非 editVariableRequest。
    // edit picker 在缺失变量时帮不上忙（变量都不存在选啥），改弹"是否立即创建"。
    const isError = target.classList.contains('hc-chip-error');
    if (isError) {
        emit('createVariableRequest', {
            rawName: ev.detail.rawName,
            anchor: target,
        });
        return;
    }
    emit('editVariableRequest', {
        rawName: ev.detail.rawName,
        fallback: ev.detail.fallback,
        anchor: target,
    });
}

function onChipHover(ev: CustomEvent<{ rawName: string; fallback: string | null }>) {
    const target = ev.target as HTMLElement;
    if (!target) return;
    const rect = target.getBoundingClientRect();
    tooltipRawName.value = ev.detail.rawName;
    tooltipFallback.value = ev.detail.fallback;
    tooltipX.value = rect.left + rect.width / 2;
    tooltipY.value = rect.top - 6;
    tooltipVisible.value = true;
}

function onChipLeave() {
    tooltipVisible.value = false;
}

// ============================================================================
// 键盘 / blur 处理
// ============================================================================

function onEditableKeydown(ev: KeyboardEvent) {
    if (ev.key === 'Escape') {
        ev.preventDefault();
        emit('cancel');
        return;
    }
    // Enter 单行模式 → 提交；多行模式走默认（plain-text 插换行）
    if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) {
        if (!props.multiLine) {
            ev.preventDefault();
            emit('submit');
        }
    }
}

function onEditableBlur() {
    // 仅 autoFocus 模式（inline editor）当 blur 视为 finish；RightPanel 长驻不触发，
    // 不然每次切焦点都会 emit('submit') 让父组件意外收编辑态。
    if (props.autoFocus) {
        emit('submit');
    }
}

// ============================================================================
// ${ 触发检测：用户在 contenteditable 内打字 → 检查 caret 前两字符
// ============================================================================

/**
 * 0.4.2 bugfix（Bug 2）：原生 beforeinput 监听精确捕捉 ${ 触发。
 *
 * <p>之前 detect 挂在 lexical update listener 内 + dirtyLeaves > 0 守卫，仍会在
 * 用户点击编辑框（caret 落到现有 ${ 字面后位置）时误弹 picker——lexical 把 click
 * 后 caret 落位标 dirtyLeaves。改用 {@code beforeinput} 只对实际"插入字符"event
 * 触发：inputType=insertText + data='{' + 前一字符是 '$' 才弹 picker。</p>
 *
 * <p>注意：beforeinput 在浏览器把字符 commit 到 lexical **之前**触发，所以"前一字符"
 * 直接读 lexical 当前 text 即可（{ 还没插入），用 {@code queueMicrotask} 延后实际
 * picker 弹出确保 lexical 已 commit。</p>
 */
function onBeforeInput(ev: InputEvent) {
    if (ev.inputType !== 'insertText') return;
    if (ev.data !== '{') return;
    if (!editor || props.disabled) return;
    let prevIsDollar = false;
    editor.read(() => {
        const sel = $getSelection();
        if (!$isRangeSelection(sel) || !sel.isCollapsed()) return;
        const anchor = sel.anchor;
        const node = anchor.getNode();
        if (node.getType() !== 'text') return;
        const text = node.getTextContent();
        const offset = anchor.offset;
        if (offset >= 1 && text.substring(offset - 1, offset) === '$') {
            prevIsDollar = true;
        }
    });
    if (!prevIsDollar) return;
    // 异步弹 picker：让 `{` 已经被 lexical commit 到 EditorState 后，
    // detectDollarBraceTrigger 内 caret offset 检测能命中 "${" 两字符。
    queueMicrotask(() => detectDollarBraceTrigger());
}

/** 0.4.2 bugfix（Bug 3）：IME composition 开始 → 屏蔽 emit 防中断。 */
function onCompositionStart() {
    composing = true;
}

/**
 * 0.4.2 bugfix（Bug 3）：IME composition 结束 → 一次性 emit 最新文本。
 *
 * <p>compositionend 在 lexical update listener 之前 / 之后触发顺序不确定，
 * 为保险这里主动读 lexical 当前文本 emit；update listener 即使紧随其后也由
 * {@code newText !== props.text} 去重。</p>
 */
function onCompositionEnd() {
    composing = false;
    if (!editor || writingExternal) return;
    editor.read(() => {
        const newText = lexicalRootToText();
        if (newText !== props.text) {
            emit('update:text', newText);
        }
    });
    refreshAllChipDisplays();
}

function detectDollarBraceTrigger(): void {
    if (!editor) return;
    let triggered = false;
    let anchorEl: HTMLElement | null = null;
    editor.read(() => {
        const sel = $getSelection();
        if (!$isRangeSelection(sel) || !sel.isCollapsed()) return;
        const anchor = sel.anchor;
        const node = anchor.getNode();
        if (node.getType() !== 'text') return;
        const text = node.getTextContent();
        const offset = anchor.offset;
        if (offset >= 2 && text.substring(offset - 2, offset) === '${') {
            triggered = true;
        }
    });
    if (triggered && editableRef.value) {
        // 取当前 caret 的 client rect 作 anchor
        const domSel = window.getSelection();
        if (domSel && domSel.rangeCount > 0) {
            const range = domSel.getRangeAt(0);
            const rects = range.getClientRects();
            if (rects.length > 0) {
                const rect = rects[0];
                // 制造一个隐藏 anchor 元素，但实际我们直接 emit editable 容器
                anchorEl = editableRef.value;
                lastDollarBraceAnchor = anchorEl;
                // 同时更新 anchor 内的位置（外层 picker 自行决定怎么 position）
                anchorEl.dataset.caretX = String(rect.left);
                anchorEl.dataset.caretY = String(rect.bottom);
            } else {
                anchorEl = editableRef.value;
            }
        } else {
            anchorEl = editableRef.value;
        }
        emit('insertVariableRequest', anchorEl);
    }
}

// ============================================================================
// 暴露 API：插入 chip / 替换 chip / focus / 获取 text
// ============================================================================

/**
 * 由外层 Picker 选中后调：插入 chip。
 *
 * <p>如果是 {@code ${} 触发的（用户刚 type 出 ${），先把 caret 前的 {@code ${} 删掉再插。
 * 普通点按钮触发则直接在 caret 插入。</p>
 */
function insertVariableChip(rawName: string, fallback: string | null = null): void {
    if (!editor) return;
    editor.update(() => {
        const sel = $getSelection();
        if ($isRangeSelection(sel) && sel.isCollapsed()) {
            const anchor = sel.anchor;
            const node = anchor.getNode();
            if (node.getType() === 'text') {
                const text = node.getTextContent();
                const offset = anchor.offset;
                if (offset >= 2 && text.substring(offset - 2, offset) === '${') {
                    // 删掉 caret 前的 ${
                    const newText = text.substring(0, offset - 2) + text.substring(offset);
                    // 用 lexical TextNode API 改文本（保持 selection）
                    // 先 setSelection 到 [offset-2, offset]，然后 sel.removeText()
                    sel.anchor.set(node.getKey(), offset - 2, 'text');
                    sel.focus.set(node.getKey(), offset, 'text');
                    sel.removeText();
                    // newText var unused; lexical 内部已删
                }
            }
        }
        $insertVariableChipAtSelection(rawName, fallback);
    });
    // 同步 chip display
    refreshAllChipDisplays();
    focus();
}

/** 把指定 chip（按 rawName + 最近 anchor）替换为新 rawName。简化：替换第一个匹配 chip。 */
function replaceVariableChip(oldRawName: string, newRawName: string, newFallback: string | null = null): void {
    if (!editor) return;
    editor.update(() => {
        const root = $getRoot();
        const walk = (n: import('lexical').LexicalNode): boolean => {
            if ($isVariablePlaceholderNode(n) && n.getRawName() === oldRawName) {
                n.setRawName(newRawName);
                n.setFallback(newFallback);
                return true;
            }
            if ('getChildren' in n && typeof (n as { getChildren?: unknown }).getChildren === 'function') {
                const kids = (n as { getChildren: () => import('lexical').LexicalNode[] }).getChildren();
                for (const k of kids) if (walk(k)) return true;
            }
            return false;
        };
        walk(root);
    });
    refreshAllChipDisplays();
}

function focus(): void {
    editableRef.value?.focus();
}

/** 显式取当前 lexical 文本（caller 用 prop.text 一般够）。 */
function getText(): string {
    if (!editor) return props.text;
    let out = '';
    editor.read(() => {
        out = lexicalRootToText();
    });
    return out;
}

defineExpose({
    insertVariableChip,
    replaceVariableChip,
    focus,
    getText,
});
</script>

<template>
  <div
    ref="rootRef"
    class="hc-chip-editor"
    :class="[rootClass, { 'hc-chip-editor-multi': multiLine, 'hc-chip-editor-disabled': disabled }]"
  >
    <div
      ref="editableRef"
      class="hc-chip-editable"
      :contenteditable="!disabled"
      role="textbox"
      :aria-multiline="multiLine"
      :aria-disabled="disabled"
      :spellcheck="false"
      :style="{
        fontSize: fontSize ? `${fontSize}px` : undefined,
        fontFamily: fontFamily,
        '--chip-scale': chipScale,
      }"
    ></div>

    <!-- chip hover tooltip：fixed 定位到 chip 上方 -->
    <Teleport to="body">
      <div
        v-if="tooltipVisible && tooltipRawName"
        class="hc-chip-tooltip"
        :style="{
          left: `${tooltipX}px`,
          top: `${tooltipY}px`,
        }"
        role="tooltip"
      >
        <div class="hc-tt-row">
          <span class="hc-tt-label">{{ t.variables.chipEditor?.tooltipRaw ?? 'Raw' }}:</span>
          <code class="hc-tt-code">${'${var:'}{{ tooltipRawName }}{{ tooltipFallback != null ? `|fallback=${tooltipFallback}` : '' }}{{ '}' }}</code>
        </div>
        <div class="hc-tt-row">
          <span class="hc-tt-label">{{ t.variables.chipEditor?.tooltipCurrent ?? 'Current' }}:</span>
          <span class="hc-tt-value" :class="{ 'hc-tt-deleted': tooltipDeleted }">
            {{ tooltipDisplayValue }}
          </span>
        </div>
        <div v-if="tooltipAlias" class="hc-tt-row">
          <span class="hc-tt-label">{{ t.variables.aliasChipPrefix }}</span>
          <span class="hc-tt-value">{{ tooltipAlias }}</span>
        </div>
        <div v-if="tooltipSource" class="hc-tt-row">
          <span class="hc-tt-label">{{ t.variables.chipEditor?.tooltipSource ?? 'Source' }}:</span>
          <span class="hc-tt-value">{{ tooltipSource }}</span>
        </div>
        <div v-if="tooltipDeleted" class="hc-tt-warning">
          {{ t.variables.chipEditor?.tooltipDeleted ?? 'Variable not found — will render as "???"' }}
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style>
/* ============================================================================
   chip pill 全局样式（unscoped）。
   chip 由 lexical DecoratorNode createDOM 渲染，scoped 选择器无法命中（lexical
   不带 data-v-xxx hash），所以挂在 unscoped block。

   设计要点（P3.1 + P3.2 + P3.3）：
   - **Catppuccin Mauve 直引**：`var(--ctp-mauve)` 通过 latte/frappe/macchiato
     三 flavor 自动切换；color-mix 仅做透明度调配（in srgb 与 token 体系一致）。
   - **字号 clamp 钳位**：`font-size` 走 `clamp(10px, 0.85em, 16px)` 防极小 /
     极大字号失控。padding / border-radius 同时跟 `--chip-scale`（由 props.fontSize
     计算的 0.6..1.2 比例）联动。
   - **multi-line 整体换行**：`display: inline-block` + `white-space: nowrap` →
     chip 自身不拆，但浏览器把它当一个单位参与父段落换行（line-break 不会切 chip 内部）。
   - **dark 主题适配**：`.dark &` 提高 mauve 透明度，让 frappe / macchiato 下
     pill 不至于太黯淡（mauve dark 色比 light 浅，需要更高 fill ratio）。
   ============================================================================ */
.hc-var-chip {
    display: inline-block;
    line-height: 1;
    padding: calc(0.18em * var(--chip-scale, 1)) calc(0.55em * var(--chip-scale, 1));
    margin: 0 1px;
    border-radius: calc(999px * var(--chip-scale, 1));
    background-color: color-mix(in srgb, var(--ctp-mauve) 18%, transparent);
    color: var(--ctp-mauve);
    border: 1px solid color-mix(in srgb, var(--ctp-mauve) 35%, transparent);
    font-size: clamp(10px, 0.85em, 16px);
    font-family: ui-sans-serif, system-ui, sans-serif;
    font-weight: 500;
    cursor: pointer;
    user-select: none;
    -webkit-user-select: none;
    vertical-align: baseline;
    transition: background-color 120ms ease, border-color 120ms ease, transform 80ms ease;
    /* P3.3：chip 内部不换行（整体不可拆），但允许参与父段落的换行流：父段 white-space
       是 pre-wrap，chip 作为 inline-block 是不可分单位 → 浏览器把 chip 看作一个"长字"，
       超宽时换到下一行而不切 chip。 */
    white-space: nowrap;
    max-width: 16em;
    overflow: hidden;
    text-overflow: ellipsis;
}
.hc-var-chip::before {
    content: '⚡';
    font-size: 0.8em;
    margin-right: 3px;
    opacity: 0.85;
}
.hc-var-chip:hover {
    background-color: color-mix(in srgb, var(--ctp-mauve) 30%, transparent);
    border-color: color-mix(in srgb, var(--ctp-mauve) 50%, transparent);
}
.hc-var-chip:active {
    transform: scale(0.96);
}
/* dark 主题（Frappé / Macchiato）：mauve 本身偏浅，提高填充比例让 pill 更显著 */
.dark .hc-var-chip,
.theme-frappe .hc-var-chip,
.theme-macchiato .hc-var-chip {
    background-color: color-mix(in srgb, var(--ctp-mauve) 24%, transparent);
    border-color: color-mix(in srgb, var(--ctp-mauve) 42%, transparent);
}
.dark .hc-var-chip:hover,
.theme-frappe .hc-var-chip:hover,
.theme-macchiato .hc-var-chip:hover {
    background-color: color-mix(in srgb, var(--ctp-mauve) 36%, transparent);
    border-color: color-mix(in srgb, var(--ctp-mauve) 58%, transparent);
}
/* P3.4：error 态（变量缺失）→ destructive red + 删除线 + ⚠ 前缀；click 走
   createVariableRequest 路径（外层弹"是否立即创建"确认） */
.hc-var-chip.hc-chip-error {
    background-color: color-mix(in srgb, var(--ctp-red) 18%, transparent);
    color: var(--ctp-red);
    border-color: color-mix(in srgb, var(--ctp-red) 45%, transparent);
    text-decoration: line-through;
    text-decoration-thickness: 1px;
}
.hc-var-chip.hc-chip-error::before {
    content: '⚠';
}
.hc-var-chip.hc-chip-error:hover {
    background-color: color-mix(in srgb, var(--ctp-red) 30%, transparent);
    border-color: color-mix(in srgb, var(--ctp-red) 58%, transparent);
}
.dark .hc-var-chip.hc-chip-error,
.theme-frappe .hc-var-chip.hc-chip-error,
.theme-macchiato .hc-var-chip.hc-chip-error {
    background-color: color-mix(in srgb, var(--ctp-red) 24%, transparent);
    border-color: color-mix(in srgb, var(--ctp-red) 50%, transparent);
}
</style>

<style scoped>
.hc-chip-editor {
    position: relative;
    display: block;
    width: 100%;
}
.hc-chip-editable {
    width: 100%;
    min-height: 2.5rem;
    padding: 0.3rem 0.45rem;
    font-size: 0.75rem;
    line-height: 1.4;
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
    outline: none;
    white-space: pre-wrap;
    word-break: break-word;
    cursor: text;
    overflow-y: auto;
}
.hc-chip-editable:focus {
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
.hc-chip-editor-disabled .hc-chip-editable {
    background: color-mix(in oklab, var(--muted) 40%, var(--background));
    color: var(--muted-foreground);
    cursor: not-allowed;
}
/* lexical 段落之间不要有 default margin */
.hc-chip-editable :deep(p) {
    margin: 0;
    padding: 0;
}

/* tooltip（Teleport 到 body，所以 scoped 命中不到——挂全局选择器） */
</style>

<style>
.hc-chip-tooltip {
    position: fixed;
    z-index: 9999;
    transform: translate(-50%, -100%);
    min-width: 180px;
    max-width: 320px;
    padding: 6px 8px;
    background: var(--popover, var(--card));
    color: var(--popover-foreground, var(--card-foreground));
    border: 1px solid var(--border);
    border-radius: var(--radius, 6px);
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.18);
    font-size: 11px;
    line-height: 1.4;
    pointer-events: none;
    user-select: none;
    display: flex;
    flex-direction: column;
    gap: 2px;
}
.hc-tt-row {
    display: flex;
    align-items: baseline;
    gap: 5px;
}
.hc-tt-label {
    flex-shrink: 0;
    color: var(--muted-foreground);
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
}
.hc-tt-code {
    flex: 1;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 10px;
    color: var(--foreground);
    word-break: break-all;
    background: var(--muted);
    padding: 1px 4px;
    border-radius: 3px;
}
.hc-tt-value {
    flex: 1;
    color: var(--foreground);
    word-break: break-all;
    font-variant-numeric: tabular-nums;
}
.hc-tt-deleted {
    color: var(--destructive, #d20f39);
    text-decoration: line-through;
}
.hc-tt-warning {
    margin-top: 2px;
    padding-top: 4px;
    border-top: 1px dashed var(--border);
    color: var(--destructive, #d20f39);
    font-size: 10px;
}
</style>
