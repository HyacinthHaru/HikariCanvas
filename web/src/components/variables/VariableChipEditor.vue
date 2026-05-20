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
}>();

const store = useVariableStore();
const { t } = useI18n();

const rootRef = ref<HTMLDivElement | null>(null);
const editableRef = ref<HTMLDivElement | null>(null);

// ----------- tooltip 状态（hover chip 时显示） -----------
const tooltipVisible = ref(false);
const tooltipX = ref(0);
const tooltipY = ref(0);
const tooltipRawName = ref('');
const tooltipFallback = ref<string | null>(null);

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

// ----------- lexical editor 实例 -----------
// 不用 ref（避免响应式遍历内部 Map/Set 触发 Vue 警告）；用普通变量。
let editor: ReturnType<typeof createEditor> | null = null;
let unregisterAll: (() => void) | null = null;
/** flag：external watch 在改 editor 内容，避免触发 onUpdate 回写 props.text 引起循环。 */
let writingExternal = false;
/** 最近的 ${ 触发 anchor：textarea -> contenteditable 转换后由 caret 找最近 chip 容器。 */
let lastDollarBraceAnchor: HTMLElement | null = null;

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

    // plain-text + history 插件
    unregisterAll = mergeRegister(
        registerPlainText(editor),
        registerHistory(editor, createEmptyHistoryState(), 1000),
        editor.registerUpdateListener(({ editorState, dirtyElements, dirtyLeaves }) => {
            // 忽略外部 write 触发的更新
            if (writingExternal) return;
            // 没有真改时跳过（selection only 也会触发 update，但 dirty 集合空）
            if (dirtyElements.size === 0 && dirtyLeaves.size === 0) return;
            editorState.read(() => {
                const newText = lexicalRootToText();
                if (newText !== props.text) {
                    emit('update:text', newText);
                }
                detectDollarBraceTrigger();
            });
            // 文本变了 → store 仍然可能没变；刷一遍 chip 显示
            refreshAllChipDisplays();
        }),
    );

    // chip 事件代理（mount 时挂在 editable root，捕获冒泡上来的自定义事件）
    editableRef.value.addEventListener(CHIP_EVENT_CLICK, onChipClick as EventListener);
    editableRef.value.addEventListener(CHIP_EVENT_HOVER, onChipHover as EventListener);
    editableRef.value.addEventListener(CHIP_EVENT_LEAVE, onChipLeave as EventListener);
    editableRef.value.addEventListener('keydown', onEditableKeydown);
    editableRef.value.addEventListener('blur', onEditableBlur);

    // 首次刷 chip 显示
    refreshAllChipDisplays();

    if (props.autoFocus) {
        // setRootElement 完后下一 microtask focus 才稳
        queueMicrotask(() => focus());
    }
});

onBeforeUnmount(() => {
    if (editableRef.value) {
        editableRef.value.removeEventListener(CHIP_EVENT_CLICK, onChipClick as EventListener);
        editableRef.value.removeEventListener(CHIP_EVENT_HOVER, onChipHover as EventListener);
        editableRef.value.removeEventListener(CHIP_EVENT_LEAVE, onChipLeave as EventListener);
        editableRef.value.removeEventListener('keydown', onEditableKeydown);
        editableRef.value.removeEventListener('blur', onEditableBlur);
    }
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
    () => [props.wallId, store.variables] as const,
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
        let displayValue: string;
        let deleted = false;
        if (v) {
            if (v.currentValue != null && v.currentValue.length > 0) displayValue = v.currentValue;
            else if (fallback != null) displayValue = fallback;
            else if (v.defaultValue != null) displayValue = v.defaultValue;
            else displayValue = UNRESOLVED;
        } else {
            // 变量不存在 → 用 fallback 或 "???"，并加 deleted class
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
/* 全局样式（unscoped）：chip 是 lexical 渲染的 DOM，scoped 选择器无法命中（lexical 不
   带 data-v-xxx hash）。把样式挂在 :global 等价位置——直接 .hc-var-chip。 */
.hc-var-chip {
    display: inline-flex;
    align-items: center;
    height: 1.4em;
    line-height: 1;
    padding: 0 0.4em;
    margin: 0 1px;
    border-radius: 999px;
    background: color-mix(in oklab, var(--ctp-mauve, #8839ef) 18%, var(--background));
    color: color-mix(in oklab, var(--ctp-mauve, #8839ef) 88%, var(--foreground));
    border: 1px solid color-mix(in oklab, var(--ctp-mauve, #8839ef) 40%, var(--border));
    font-size: 0.85em;
    font-family: ui-sans-serif, system-ui, sans-serif;
    font-weight: 500;
    cursor: pointer;
    user-select: none;
    -webkit-user-select: none;
    vertical-align: middle;
    transition: background 120ms, border-color 120ms, transform 80ms;
    white-space: nowrap;
    max-width: 16em;
    overflow: hidden;
    text-overflow: ellipsis;
}
.hc-var-chip::before {
    content: '⚡';
    font-size: 0.8em;
    margin-right: 3px;
    opacity: 0.7;
}
.hc-var-chip:hover {
    background: color-mix(in oklab, var(--ctp-mauve, #8839ef) 30%, var(--background));
    border-color: color-mix(in oklab, var(--ctp-mauve, #8839ef) 55%, var(--border));
}
.hc-var-chip:active {
    transform: scale(0.96);
}
.hc-var-chip.hc-chip-error {
    background: color-mix(in oklab, var(--destructive, #d20f39) 15%, var(--background));
    color: var(--destructive, #d20f39);
    border-color: color-mix(in oklab, var(--destructive, #d20f39) 45%, var(--border));
    text-decoration: line-through;
    text-decoration-thickness: 1px;
}
.hc-var-chip.hc-chip-error::before {
    content: '⚠';
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
