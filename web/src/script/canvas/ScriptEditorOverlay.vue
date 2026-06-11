<script setup lang="ts">
/**
 * 0.7.0-P4-B / D1：积木脚本编辑器全屏 overlay 容器。
 *
 * <p>fixed 全屏（z-index 60，高于时间轴 dock）。头部工具条 = 标题 + "新建规则" + 当前规则
 * 编辑控件（名称 / 启停 / 撤销重做 / 删除 / 试跑占位）+ zoom 显示 % + reset + 关闭。主体 =
 * 左侧侧栏（<b>D1 规则列表</b> + palette 占位空壳，真 palette 任务 D2）+ 右侧 {@link BlockCanvas}。</p>
 *
 * <p><b>D1 接通编辑模型（{@link useScriptEditStore}）</b>：点列表项 → selectRule；新建 →
 * newRule（拿 server 发的 id 再进编辑）；删除走 inline confirm；名称 input / 启停 toggle 改
 * working copy（debounce 自动保存）；Ctrl+Z/Y 撤销重做。拖拽 / palette 留 D2，故 BlockCanvas
 * 的堆点击选中也留 D2。lock（project.isLocked）→ 编辑控件全禁用（K-UI-12）。</p>
 *
 * <p>挂 {@code ui.scriptEditorOpen}（App.vue 末尾 v-if 懒加载挂载）。Esc 关闭（先 flush 保存）。
 * 配色走 Catppuccin token。i18n 在 script setup 里用 {@code t.value.xxx}。</p>
 */
import { computed, ref, watch, onScopeDispose } from 'vue';
import { useEventListener } from '@vueuse/core';
import { X, Puzzle, Plus, RotateCcw, Undo2, Redo2, Trash2, Play, Power, AlertTriangle } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useScriptStore } from '@/stores/scripts';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import {
    createHighlightStepper,
    resultColorVar,
    type HighlightMap,
    type StepResult,
} from './traceHighlight';
import type { HighlightInject } from './highlightInjection';
import BlockCanvas from './BlockCanvas.vue';
import BlockPalette from './BlockPalette.vue';

const ui = useUiStore();
const scripts = useScriptStore();
const edit = useScriptEditStore();
const project = useProjectStore();
const net = useNetworkStore();
const { t } = useI18n();

const canvasRef = ref<InstanceType<typeof BlockCanvas> | null>(null);

/** 删除 inline confirm 目标 ruleId（null = 未在确认）。照 0.4.5 VariablePanel 范式。 */
const confirmingDelete = ref<string | null>(null);

/** 锁定态：编辑控件全禁用（K-UI-12）。 */
const locked = computed(() => project.isLocked);

// ---------- H：保存前校验（K-UI-9）----------

/**
 * 当前 workingCopy 的校验错误列表（空 = 合法）。直接读 scriptEdit store 的 {@code validationErrors}
 * computed（单一权威——store 的 doSave 也读同一份阻止 send），UI 这里只负责展示与试跑前判。
 */
const validationErrors = computed(() => edit.validationErrors);
/** 是否有校验错误（试跑 disabled / 头部温和指示显隐用）。 */
const hasErrors = computed(() => validationErrors.value.length > 0);
/** 头部温和指示文案：「⚠ N 处待完善」（N = 错误条数）。 */
const validationHintText = computed(() => t.value.script.validationHint.replace('{n}', String(validationErrors.value.length)));

// ---------- H：试跑高亮（K-UI-8）----------

/** 高亮 result map（blockId → result）；overlay 局部态，provide 给画布递归子组件。 */
const highlightResults = ref<HighlightMap>(new Map());
/** 高亮 detail map（blockId → step.detail）；作积木 title。 */
const highlightDetails = ref<Map<string, string>>(new Map());
/** 传给 BlockCanvas 的高亮聚合（result + detail 两个 ref）。 */
const highlightInject: HighlightInject = {
    results: highlightResults,
    details: highlightDetails,
};

/**
 * 步进高亮控制器：每帧把 result map 赋给 highlightResults。detail map 在 start 时一次性建好
 * （随 result 同步亮——detail 是静态附注，不必逐帧）。
 */
const stepper = createHighlightStepper({
    apply: (map) => {
        highlightResults.value = map;
        // result 清空（末帧 hold 结束）时一并清 detail。
        if (map.size === 0) highlightDetails.value = new Map();
    },
});

/** 正在试跑（ack 受理后到 trace 到达 / 步进结束的窗口）；UI 给"试跑中…"反馈。 */
const testing = ref(false);

/**
 * 监听 scripts.lastTrace：仅当 trace 的 ruleId === 当前编辑规则时启动步进高亮。
 * 别的规则的 trace（理论上不会，但 store 是全局）不影响当前编辑器。
 */
watch(
    () => scripts.lastTrace,
    (trace) => {
        if (!trace) return;
        if (trace.ruleId !== edit.selectedRuleId) return;
        // 先建 detail map（一次性），再启动 result 步进。
        const details = new Map<string, string>();
        for (const step of trace.steps) {
            if (step.blockId && step.detail) details.set(step.blockId, step.detail);
        }
        highlightDetails.value = details;
        stepper.start(trace.steps);
        testing.value = false;
    },
);

/**
 * 切换编辑规则 → 清高亮（上一条规则的试跑结果不该残留到新规则）。
 * 关闭 overlay（组件卸载）→ onScopeDispose 清。
 */
watch(
    () => edit.selectedRuleId,
    () => {
        stepper.clear();
        testing.value = false;
    },
);

onScopeDispose(() => {
    stepper.clear();
});

/**
 * 点"试跑"：先跑校验（有错不试跑——拖完没填全就别浪费一次 server run），通过则
 * sendScriptTest（ack 仅受理；轨迹另走 script.trace 推送，由上面的 watch 消费启动高亮）。
 */
function onTest(): void {
    // lock 守卫（与其它入口双层守卫一致）：按钮已 disabled，这里是冗余保险，锁定的墙不试跑。
    if (locked.value) return;
    const ruleId = edit.selectedRuleId;
    if (ruleId === null || !edit.workingCopy) return;
    if (hasErrors.value) {
        net.lastError = t.value.script.testBlockedByErrors;
        return;
    }
    // 清旧高亮，进入"试跑中"。
    stepper.clear();
    testing.value = true;
    getWsClient()
        .sendScriptTest(ruleId)
        .catch((e) => {
            testing.value = false;
            net.lastError = `${t.value.script.testFailed}：${e instanceof Error ? e.message : String(e)}`;
        });
}

/**
 * P5 实测修复（根因 1）：点头部「待完善」温和指示 → 定位到<b>第一个带 blockId 的错误积木</b>
 * （best-effort——滚动到 data-block-path 元素居中）。循环找首个有 blockId 的错误（规则级错误如
 * 名称空 / 动作列表空无 blockId，跳过它们找到第一个能定位的积木）。无任何可定位错误 → 静默。
 */
function onIndicatorClick(): void {
    for (const err of validationErrors.value) {
        if (!err.blockId) continue;
        const el = document.querySelector(`[data-block-path="${cssEscape(err.blockId)}"]`);
        if (el && 'scrollIntoView' in el) {
            (el as HTMLElement).scrollIntoView({ behavior: 'smooth', block: 'center' });
            return;
        }
    }
}

/** CSS 属性选择器转义（blockId 含 '/'，需转义才能用在 querySelector）。 */
function cssEscape(s: string): string {
    const c = (window as unknown as { CSS?: { escape?: (v: string) => string } }).CSS;
    return c?.escape ? c.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, (ch) => `\\${ch}`);
}

/** 高亮图例项（试跑结果色 → 文案），步进时底部小图例用。 */
const legendItems = computed<{ result: StepResult; label: string; color: string }[]>(() => [
    { result: 'ok', label: t.value.script.legendOk, color: `var(${resultColorVar('ok')})` },
    { result: 'skipped', label: t.value.script.legendSkipped, color: `var(${resultColorVar('skipped')})` },
    { result: 'blocked', label: t.value.script.legendBlocked, color: `var(${resultColorVar('blocked')})` },
    { result: 'error', label: t.value.script.legendError, color: `var(${resultColorVar('error')})` },
]);
/** 是否有任意高亮在显示（控制图例显隐）。 */
const showLegend = computed(() => highlightResults.value.size > 0 || testing.value);

/** 头部 zoom 百分比显示（读 BlockCanvas 暴露的 zoom ref）。 */
const zoomPct = computed(() => {
    const z = canvasRef.value?.zoom ?? 1;
    return Math.round((typeof z === 'number' ? z : z.value ?? 1) * 100);
});

function resetView(): void {
    canvasRef.value?.resetView();
}

/**
 * palette 项 pointerdown 转发到 BlockCanvas 的拖拽实例（palette 在侧栏、拖拽实例在画布——
 * 经此一跳把"拖出新块"接到画布的 useBlockDrag）。无选中规则时也允许拖（落下时 insertAt 需有
 * workingCopy，故 useBlockDrag 在松手时若 workingCopy 为空会安全早退——但更友好是先建/选规则）。
 */
function onPaletteDown(kind: string, e: PointerEvent): void {
    canvasRef.value?.startPaletteDrag(kind, e);
}

function close(): void {
    // 关编辑器前 flush 待保存改动 + 清编辑会话（不影响 server 镜像 / 不切 wall）。
    edit.closeEditing();
    ui.closeScriptEditor();
}

// ---------- 规则列表 / 新建 / 删除 ----------

function onSelectRule(ruleId: string): void {
    confirmingDelete.value = null;
    edit.selectRule(ruleId);
}

async function onNewRule(): Promise<void> {
    if (locked.value) return;
    confirmingDelete.value = null;
    await edit.newRule(t.value.script.defaultRuleName);
}

function askDelete(ruleId: string): void {
    confirmingDelete.value = ruleId;
}
function cancelDelete(): void {
    confirmingDelete.value = null;
}
function confirmDelete(ruleId: string): void {
    edit.deleteRule(ruleId);
    confirmingDelete.value = null;
}

// ---------- 当前规则编辑控件 ----------

/** 名称 input：用 :value + @input 走 setName（而非 v-model，保证经 undo/dirty 逻辑）。 */
function onNameInput(e: Event): void {
    edit.setName((e.target as HTMLInputElement).value);
}

function onToggleEnabled(): void {
    const wc = edit.workingCopy;
    if (!wc) return;
    edit.setEnabled(!wc.enabled);
}

/** 列表项显示名（空名兜底友好文案）。 */
function ruleDisplayName(name: string): string {
    return name.trim().length > 0 ? name : t.value.script.ruleUnnamed;
}

// ---------- 键盘：Esc 关 / Ctrl+Z 撤销 / Ctrl+Y(或 Ctrl+Shift+Z) 重做 ----------

function isFormTarget(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    // 名称 input 自身允许浏览器原生撤销，但本编辑器的 undo 针对积木结构——
    // 表单聚焦时不接管 Ctrl+Z（留给输入框文本撤销）。
    return !!el && (el.matches?.('input, textarea, select') || el.isContentEditable);
}

useEventListener(document, 'keydown', (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
        e.preventDefault();
        close();
        return;
    }
    // 撤销 / 重做仅在编辑器开启且非表单聚焦时接管。
    if (!(e.ctrlKey || e.metaKey)) return;
    if (isFormTarget(e.target)) return;
    const key = e.key.toLowerCase();
    if (key === 'z' && !e.shiftKey) {
        e.preventDefault();
        edit.undo();
    } else if (key === 'y' || (key === 'z' && e.shiftKey)) {
        e.preventDefault();
        edit.redo();
    }
});
</script>

<template>
  <div class="hc-script-overlay" role="dialog" aria-modal="true">
    <header class="hc-script-header">
      <Puzzle class="size-4 text-[color:var(--ctp-mauve)]" />
      <h2 class="text-sm font-semibold">{{ t.script.editorTitle }}</h2>

      <!-- 新建规则（D1 接通；lock 时禁用） -->
      <button
        class="hc-script-btn ml-3"
        :class="locked ? 'opacity-50 cursor-not-allowed' : ''"
        :disabled="locked"
        :title="locked ? t.script.lockedHint : t.script.newRule"
        @click="onNewRule"
      >
        <Plus class="size-3.5" />
        <span>{{ t.script.newRule }}</span>
      </button>

      <!-- 当前选中规则的编辑控件（有 workingCopy 才显示） -->
      <div v-if="edit.workingCopy" class="hc-rule-controls">
        <input
          class="hc-rule-name"
          :value="edit.workingCopy.name"
          :placeholder="t.script.ruleNamePlaceholder"
          :disabled="locked"
          maxlength="64"
          @input="onNameInput"
        />
        <button
          class="hc-rule-ctrl"
          :class="edit.workingCopy.enabled ? 'hc-rule-ctrl-on' : 'hc-rule-ctrl-off'"
          :disabled="locked"
          :title="t.script.toggleEnabled"
          @click="onToggleEnabled"
        >
          <Power class="size-3.5" />
          <span class="text-xs">{{ edit.workingCopy.enabled ? t.script.enabledOn : t.script.enabledOff }}</span>
        </button>
        <button
          class="hc-script-icon-btn"
          :disabled="locked || edit.undoStack.length === 0"
          :title="t.script.undo"
          @click="edit.undo()"
        >
          <Undo2 class="size-4" />
        </button>
        <button
          class="hc-script-icon-btn"
          :disabled="locked || edit.redoStack.length === 0"
          :title="t.script.redo"
          @click="edit.redo()"
        >
          <Redo2 class="size-4" />
        </button>
        <!-- 试跑：H 阶段接真逻辑。有校验错误 / 锁定 / 试跑中 → 禁用。 -->
        <button
          class="hc-script-icon-btn hc-rule-test"
          :class="testing ? 'hc-rule-test-busy' : ''"
          :disabled="locked || hasErrors || testing"
          :title="hasErrors ? t.script.testBlockedByErrors : (testing ? t.script.testing : t.script.test)"
          @click="onTest"
        >
          <Play class="size-4" />
        </button>
        <!-- 删除（inline confirm） -->
        <template v-if="confirmingDelete !== edit.selectedRuleId">
          <button
            class="hc-script-icon-btn hc-rule-del"
            :disabled="locked"
            :title="t.script.deleteRule"
            @click="askDelete(edit.selectedRuleId!)"
          >
            <Trash2 class="size-4" />
          </button>
        </template>
        <template v-else>
          <span class="hc-del-confirm">
            <span class="text-xs text-[color:var(--destructive)]">{{ t.script.deleteRuleConfirm }}</span>
            <button class="hc-del-no" @click="cancelDelete">{{ t.script.deleteConfirmNo }}</button>
            <button class="hc-del-yes" @click="confirmDelete(edit.selectedRuleId!)">{{ t.script.deleteConfirmYes }}</button>
          </span>
        </template>

        <!-- P5 实测修复（根因 1）：校验问题的温和 inline 指示（橙色小字，不占新行 / 不挤画布）。
             点它滚动定位到第一个可定位的错误积木。validationErrors 为空时不显示。 -->
        <button
          v-if="hasErrors"
          type="button"
          class="hc-validation-hint"
          :title="t.script.validationTitle"
          @click="onIndicatorClick"
        >
          <AlertTriangle class="size-3.5" />
          <span>{{ validationHintText }}</span>
        </button>
      </div>

      <div class="ml-auto flex items-center gap-2">
        <span class="text-xs text-[color:var(--muted-foreground)] tabular-nums w-12 text-right">
          {{ zoomPct }}%
        </span>
        <button class="hc-script-icon-btn" :title="t.script.resetView" @click="resetView">
          <RotateCcw class="size-4" />
        </button>
        <button class="hc-script-icon-btn" :title="t.script.close" @click="close">
          <X class="size-4" />
        </button>
      </div>
    </header>

    <div class="hc-script-body">
      <aside class="hc-script-palette">
        <!-- D1：规则列表（点选进入编辑） -->
        <div class="hc-side-section-title">{{ t.script.rulesTitle }}</div>
        <div v-if="scripts.size === 0" class="hc-side-empty">{{ t.script.rulesEmpty }}</div>
        <ul v-else class="hc-rule-list">
          <li
            v-for="rule in scripts.listSorted"
            :key="rule.id"
            class="hc-rule-item"
            :class="rule.id === edit.selectedRuleId ? 'hc-rule-item-active' : ''"
            @click="onSelectRule(rule.id)"
          >
            <span class="hc-rule-dot" :class="rule.enabled ? 'hc-dot-on' : 'hc-dot-off'" />
            <span class="hc-rule-item-name">{{ ruleDisplayName(rule.name) }}</span>
          </li>
        </ul>

        <!-- 积木库（D2：拖出新块）。无选中规则时提示先选/建一条。 -->
        <div class="hc-side-section-title mt-2">{{ t.script.paletteTitle }}</div>
        <div v-if="!edit.workingCopy" class="px-3 py-1 text-xs text-[color:var(--muted-foreground)]">
          {{ t.script.paletteNeedRule }}
        </div>
        <div class="px-2 pb-3">
          <BlockPalette @palette-down="onPaletteDown" />
        </div>
      </aside>

      <!-- 主体画布 -->
      <main class="hc-script-canvas-host">
        <BlockCanvas ref="canvasRef" :highlight="highlightInject" />
        <!-- 空画布提示：无规则时显示 -->
        <div v-if="scripts.size === 0" class="hc-script-empty-hint">
          {{ t.script.empty }}
        </div>
        <!-- 有规则但未选中编辑：提示选一条 -->
        <div v-else-if="!edit.workingCopy" class="hc-script-empty-hint">
          {{ t.script.selectRuleHint }}
        </div>

        <!-- H：试跑高亮图例（试跑中 / 有高亮时显示，底部居中） -->
        <div v-if="showLegend" class="hc-script-legend">
          <span v-if="testing" class="hc-legend-testing">{{ t.script.testing }}</span>
          <span v-for="item in legendItems" :key="item.result" class="hc-legend-item">
            <span class="hc-legend-dot" :style="{ background: item.color }" />
            {{ item.label }}
          </span>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.hc-script-overlay {
    position: fixed;
    inset: 0;
    z-index: 60;
    display: flex;
    flex-direction: column;
    background: var(--background);
    color: var(--foreground);
}
.hc-script-header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    height: 2.75rem;
    padding: 0 0.75rem;
    border-bottom: 1px solid var(--border);
    background: var(--card);
    color: var(--card-foreground);
    flex-shrink: 0;
}
.hc-rule-controls {
    display: flex;
    align-items: center;
    gap: 0.375rem;
    margin-left: 0.75rem;
    padding-left: 0.75rem;
    border-left: 1px solid var(--border);
}
.hc-rule-name {
    width: 10rem;
    padding: 0.25rem 0.5rem;
    font-size: 0.8125rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    background: var(--background);
    color: var(--foreground);
}
.hc-rule-name:disabled {
    opacity: 0.5;
    cursor: not-allowed;
}
.hc-rule-ctrl {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.25rem 0.5rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
}
.hc-rule-ctrl:disabled {
    opacity: 0.5;
    cursor: not-allowed;
}
.hc-rule-ctrl-on {
    color: var(--ctp-green, var(--foreground));
    border-color: color-mix(in srgb, var(--ctp-green, var(--border)) 50%, var(--border));
}
.hc-rule-ctrl-off {
    color: var(--muted-foreground);
}
.hc-rule-del {
    color: var(--destructive);
}
.hc-del-confirm {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.125rem 0.5rem;
    border-radius: var(--radius-sm);
    background: color-mix(in srgb, var(--destructive) 10%, transparent);
    border: 1px solid color-mix(in srgb, var(--destructive) 30%, transparent);
}
.hc-del-no {
    padding: 0.0625rem 0.375rem;
    font-size: 0.75rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
}
.hc-del-no:hover {
    background: var(--accent);
}
.hc-del-yes {
    padding: 0.0625rem 0.375rem;
    font-size: 0.75rem;
    border-radius: var(--radius-sm);
    background: var(--destructive);
    color: var(--destructive-foreground);
}
.hc-del-yes:hover {
    opacity: 0.9;
}
/* P5 实测修复（根因 1）：头部 inline 温和指示（橙色小字，不占新行 / 不挤画布）。
   点它定位到第一个可定位的错误积木。替代原来的整行红 banner（布局位移元凶）。 */
.hc-validation-hint {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.125rem 0.5rem;
    font-size: 0.75rem;
    line-height: 1.2;
    white-space: nowrap;
    border-radius: var(--radius-sm);
    color: var(--ctp-peach, var(--foreground));
    border: 1px solid color-mix(in srgb, var(--ctp-peach, var(--border)) 40%, transparent);
    background: color-mix(in srgb, var(--ctp-peach, var(--muted)) 12%, transparent);
    cursor: pointer;
}
.hc-validation-hint:hover {
    background: color-mix(in srgb, var(--ctp-peach, var(--muted)) 22%, transparent);
}
.hc-rule-test {
    color: var(--ctp-green, var(--primary));
}
.hc-rule-test-busy {
    color: var(--muted-foreground);
    animation: hc-test-pulse 1s ease-in-out infinite;
}
@keyframes hc-test-pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.4; }
}
/* H：试跑高亮图例（底部居中 pill） */
.hc-script-legend {
    position: absolute;
    bottom: 0.75rem;
    left: 50%;
    transform: translateX(-50%);
    display: flex;
    align-items: center;
    gap: 0.625rem;
    padding: 0.3125rem 0.75rem;
    border-radius: 999px;
    background: color-mix(in srgb, var(--card) 92%, transparent);
    border: 1px solid var(--border);
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
    font-size: 0.6875rem;
    color: var(--muted-foreground);
    pointer-events: none;
    z-index: 5;
}
.hc-legend-testing {
    font-weight: 600;
    color: var(--ctp-green, var(--foreground));
}
.hc-legend-item {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
}
.hc-legend-dot {
    width: 8px;
    height: 8px;
    border-radius: 999px;
}
.hc-script-body {
    flex: 1;
    display: flex;
    min-height: 0;
}
.hc-script-palette {
    width: 220px;
    flex-shrink: 0;
    border-right: 1px solid var(--border);
    background: var(--card);
    overflow-y: auto;
}
.hc-side-section-title {
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted-foreground);
    padding: 0.75rem 0.75rem 0.25rem;
}
.hc-side-empty {
    padding: 0.25rem 0.75rem 0.5rem;
    font-size: 0.75rem;
    color: var(--muted-foreground);
}
.hc-rule-list {
    list-style: none;
    margin: 0;
    padding: 0 0.375rem;
    display: flex;
    flex-direction: column;
    gap: 2px;
}
.hc-rule-item {
    display: flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.3125rem 0.5rem;
    border-radius: var(--radius-sm);
    cursor: pointer;
    font-size: 0.8125rem;
}
.hc-rule-item:hover {
    background: var(--accent);
}
.hc-rule-item-active {
    background: color-mix(in srgb, var(--ctp-mauve, var(--primary)) 18%, transparent);
}
.hc-rule-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    flex-shrink: 0;
}
.hc-dot-on {
    background: var(--ctp-green, var(--primary));
}
.hc-dot-off {
    background: var(--muted-foreground);
}
.hc-rule-item-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.hc-script-canvas-host {
    flex: 1;
    position: relative;
    min-width: 0;
}
.hc-script-empty-hint {
    position: absolute;
    top: 1rem;
    left: 50%;
    transform: translateX(-50%);
    padding: 0.375rem 0.75rem;
    border-radius: var(--radius-sm);
    background: color-mix(in srgb, var(--card) 80%, transparent);
    border: 1px solid var(--border);
    font-size: 0.75rem;
    color: var(--muted-foreground);
    pointer-events: none;
    max-width: 80%;
    text-align: center;
}
.hc-script-btn {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.25rem 0.625rem;
    font-size: 0.75rem;
    border-radius: var(--radius-sm);
    background: var(--primary);
    color: var(--primary-foreground);
}
.hc-script-icon-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 0.375rem;
    border-radius: var(--radius-sm);
    color: var(--muted-foreground);
}
.hc-script-icon-btn:hover:not(:disabled) {
    background: var(--accent);
    color: var(--foreground);
}
.hc-script-icon-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
}
</style>
