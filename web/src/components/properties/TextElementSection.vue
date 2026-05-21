<script setup lang="ts">
/**
 * Text 元素专属段：content / fontId / fontSize / align / color / letterSpacing /
 * lineHeight / vertical + Effects（stroke / shadow / glow）+ fit-content 按钮。
 *
 * 父组件传入 element + locked，子组件直接 mutate element 做 optimistic（与原 RightPanel
 * 行为一致），通过 emit('update' | 'updateDebounced') 让父去发 ws op。
 */
import { computed, onMounted, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { HelpCircle, Maximize2, Variable as VariableIcon } from 'lucide-vue-next';
import Tooltip from '@/components/ui/Tooltip.vue';
import ColorInput from '@/components/ui/ColorInput.vue';
import VariablePicker from '@/components/variables/VariablePicker.vue';
import VariableChipEditor from '@/components/variables/VariableChipEditor.vue';
import TextElementVariableHints from '@/components/variables/TextElementVariableHints.vue';
import { layoutText, charAdvance, ASCENT_RATIO } from '@/render/TextLayout';
import { ensureLoaded as ensureFontLoaded } from '@/render/FontLoader';
import { useI18n } from '@/i18n';
import { useProjectStore } from '@/stores/project';
import { getWsClient } from '@/network/wsClient';
import type { TextElement, Effects, Stroke, Shadow, Glow } from '@/types/protocol';

// M23：字体下拉动态化 —— fetch /api/font/list 而非读硬编码 FONT_META 白名单。
// 内置 + 用户字体都来自后端，displayName / source 分组在 UI 区分。
interface FontInfo {
    id: string;
    displayName: string;
    source: 'builtin' | 'user';
    format: 'ttf' | 'otf';
    pixelated: boolean;
    nativeSize: number;
}

const availableFonts = ref<FontInfo[]>([]);

onMounted(async () => {
    try {
        const resp = await fetch('/api/font/list');
        if (resp.ok) {
            const data = await resp.json();
            availableFonts.value = data.fonts ?? [];
        }
    } catch (e) {
        console.warn('[TextElementSection] /api/font/list failed:', e);
    }
});

const builtInFonts = computed(() => availableFonts.value.filter((f) => f.source === 'builtin'));
const userFonts = computed(() => availableFonts.value.filter((f) => f.source === 'user'));

interface Props {
    element: TextElement;
    locked: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{
    update: [patch: Record<string, unknown>];
    updateDebounced: [patch: Record<string, unknown>];
}>();

const { t } = useI18n();
const project = useProjectStore();
const ws = getWsClient();

// ---------- 0.4.1：chip 编辑器 + 变量 picker 集成 ----------
// Notion-style chip 编辑器替代了 0.4.0 的 textarea：占位符 ${var:X} 渲染为 pill 而非字面字符串。
// chip editor 内部以 lexical core 自包装；本组件作为 caller 只负责 picker 弹出 / chip 改绑定。
const chipEditorRef = ref<InstanceType<typeof VariableChipEditor> | null>(null);
const pickerOpen = ref(false);
/** picker 触发上下文：插入新 chip / 改已有 chip 的 rawName。 */
type PickerMode =
    | { kind: 'insertNew' }
    | { kind: 'replaceChip'; oldRawName: string };
const pickerMode = ref<PickerMode>({ kind: 'insertNew' });

function openPickerFromButton() {
    pickerMode.value = { kind: 'insertNew' };
    pickerOpen.value = true;
}

function onChipEditorInsertRequest() {
    pickerMode.value = { kind: 'insertNew' };
    pickerOpen.value = true;
}

function onChipEditorEditRequest(payload: { rawName: string; fallback: string | null }) {
    pickerMode.value = { kind: 'replaceChip', oldRawName: payload.rawName };
    pickerOpen.value = true;
}

/**
 * P3.4：点击红色 (error) chip → 弹 native confirm "是否立即创建？"。
 * 仅支持 user 域补创（短名无 / 或 user/X）；其他 namespace（system / scoreboard /
 * papi / plugin / schedule）由对应 Provider 自动注册，不在编辑器手动路径。
 */
function onChipEditorCreateRequest(payload: { rawName: string }) {
    if (typeof window === 'undefined') return;
    const ok = window.confirm(
        `${t.value.variables.chipError.notFound.replace('{name}', payload.rawName)}\n` +
        `${t.value.variables.chipError.createConfirm}`,
    );
    if (!ok) return;
    const trimmed = payload.rawName.trim();
    let userKey: string;
    if (trimmed.startsWith('user/')) userKey = trimmed.substring('user/'.length);
    else if (trimmed.indexOf('/') < 0 && trimmed.indexOf('.') < 0) userKey = trimmed;
    else {
        window.alert(t.value.variables.chipError.onlyUserCanCreate);
        return;
    }
    ws.send('variable.create', {
        name: userKey,
        type: 'STRING',
        defaultValue: '',
    });
}

function onPickerSelect(shortName: string) {
    const editor = chipEditorRef.value;
    if (!editor) {
        pickerOpen.value = false;
        pickerMode.value = { kind: 'insertNew' };
        return;
    }
    if (pickerMode.value.kind === 'replaceChip') {
        editor.replaceVariableChip(pickerMode.value.oldRawName, shortName, null);
    } else {
        editor.insertVariableChip(shortName, null);
    }
    pickerOpen.value = false;
    pickerMode.value = { kind: 'insertNew' };
    editor.focus();
}

function onPickerClose() {
    pickerOpen.value = false;
    pickerMode.value = { kind: 'insertNew' };
}

/** chip editor 把整段最新 text 通过 v-model:text 回传。 */
function onTextChipUpdate(v: string) {
    (props.element as unknown as Record<string, unknown>).text = v;
    emit('updateDebounced', { text: v });
    autoFitHeightDebounced();
}

const autoFitHeightDebounced = useDebounceFn(() => {
    fitTextHeight();
}, 250);

function onNumberChange(field: string, ev: Event) {
    const v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    emit('updateDebounced', { [field]: v });
}

function onSelectChange(field: string, ev: Event) {
    const v = (ev.target as HTMLSelectElement).value;
    emit('update', { [field]: v });
}

// M23：切字体时主动 ensureLoaded（FontLoader 内部幂等 + 去重），加载完触发
//     CanvasView 注册的 onFontLoaded 回调 requestDraw；首帧 ctx.font 走 system fallback。
function onFontIdChange(ev: Event) {
    const v = (ev.target as HTMLSelectElement).value;
    ensureFontLoaded(v);
    emit('update', { fontId: v });
}

// ---------- M5-D3 P4：文本 fit-content ----------

function fitTextHeight() {
    const te = props.element;
    const glyphs = layoutText(te);
    if (glyphs.length === 0) return;
    let maxBaselineY = te.y;
    for (const g of glyphs) {
        if (g.baselineY > maxBaselineY) maxBaselineY = g.baselineY;
    }
    const ascent = Math.round(te.fontSize * ASCENT_RATIO);
    const descent = Math.max(1, te.fontSize - ascent);
    const newH = Math.max(1, (maxBaselineY + descent) - te.y);
    if (newH !== te.h) emit('update', { h: newH });
}

function fitTextWidth() {
    const te = props.element;
    const glyphs = layoutText(te);
    if (glyphs.length === 0) return;
    let maxRight = te.x;
    for (const g of glyphs) {
        const w = g.rotated ? te.fontSize : charAdvance(te.fontId, g.ch, te.fontSize);
        const right = g.x + w;
        if (right > maxRight) maxRight = right;
    }
    const newW = Math.max(1, maxRight - te.x);
    if (newW !== te.w) emit('update', { w: newW });
}

// ---------- Effects ----------

function textEffects(): Effects {
    return props.element.effects ?? {};
}

function updateEffects(patch: Partial<Effects>) {
    const merged: Effects = { ...(props.element.effects ?? {}), ...patch };
    const clean: Effects | null = (merged.stroke || merged.shadow || merged.glow) ? merged : null;
    emit('update', { effects: clean });
}

function toggleStroke(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    updateEffects({ stroke: on ? { width: 2, color: '#000000' } : undefined });
}
function toggleShadow(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    updateEffects({ shadow: on ? { dx: 2, dy: 2, color: '#000000' } : undefined });
}
function toggleGlow(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    updateEffects({ glow: on ? { radius: 3, color: '#33CCFF' } : undefined });
}

function patchStroke(partial: Partial<Stroke>) {
    const cur = textEffects().stroke ?? { width: 2, color: '#000000' };
    updateEffects({ stroke: { ...cur, ...partial } });
}
function patchShadow(partial: Partial<Shadow>) {
    const cur = textEffects().shadow ?? { dx: 2, dy: 2, color: '#000000' };
    updateEffects({ shadow: { ...cur, ...partial } });
}
function patchGlow(partial: Partial<Glow>) {
    const cur = textEffects().glow ?? { radius: 3, color: '#33CCFF' };
    updateEffects({ glow: { ...cur, ...partial } });
}
</script>

<template>
  <!-- Text 主段 -->
  <details class="group" open>
    <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-xs py-1 hover:text-[color:var(--foreground)]">
      {{ t.properties.textHeader }}
    </summary>
    <div class="pt-1.5 space-y-2">
      <div class="hc-text-row">
        <!--
          0.4.2 bugfix（Bug B 第 6 次修，2026-05-21）：把外层 <label> 改为 <div>。
          HTML <label> 的 click delegation：点击 label 内任何位置 → 浏览器自动转发 click
          给 label 内首个 control（"插入变量"按钮也在 label 内）→ 直接触发 openPickerFromButton
          → 弹 picker。这是用户报"点击文本框就弹 picker"的真根因——之前 5 次都查错路径
          （chip click / detect / dirtyLeaves / dblclick 全无关）。
          chip editor 内 contenteditable 本身已经处理 focus，不需要 label 关联。
        -->
        <div class="flex flex-col gap-0.5">
          <span class="text-xs text-[color:var(--muted-foreground)] flex items-center justify-between">
            <span>text</span>
            <Tooltip :text="t.variables.picker.insertButtonTooltip">
              <button
                type="button"
                class="hc-var-btn"
                :aria-label="t.variables.picker.insertButtonLabel"
                @click="openPickerFromButton"
              >
                <VariableIcon class="size-3" />
                <span>{{ t.variables.picker.insertButtonLabel }}</span>
              </button>
            </Tooltip>
          </span>
          <!-- 0.4.1：chip 编辑器替代 textarea；占位符以 pill 显示。 -->
          <VariableChipEditor
            ref="chipEditorRef"
            :text="element.text"
            :wall-id="project.wallId"
            :font-size="element.fontSize"
            :multi-line="true"
            :disabled="locked"
            @update:text="onTextChipUpdate"
            @insert-variable-request="onChipEditorInsertRequest"
            @edit-variable-request="onChipEditorEditRequest"
            @create-variable-request="onChipEditorCreateRequest"
          />
        </div>
        <!-- VariablePicker popover -->
        <VariablePicker
          v-if="pickerOpen"
          :wall-id="project.wallId"
          @select="onPickerSelect"
          @close="onPickerClose"
        />
        <!-- live preview + 警告 -->
        <TextElementVariableHints :text="element.text" :wall-id="project.wallId" />
      </div>
      <!-- Fit content -->
      <div class="flex gap-2 pt-1">
        <Tooltip :text="t.properties.fitHeightTip">
          <button
            type="button"
            class="flex-1 px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] flex items-center justify-center gap-1"
            @click="fitTextHeight"
          >
            <Maximize2 class="size-3 rotate-90" />
            <span>{{ t.properties.fitHeight }}</span>
          </button>
        </Tooltip>
        <Tooltip :text="t.properties.fitWidthTip">
          <button
            type="button"
            class="flex-1 px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] flex items-center justify-center gap-1"
            @click="fitTextWidth"
          >
            <Maximize2 class="size-3" />
            <span>{{ t.properties.fitWidth }}</span>
          </button>
        </Tooltip>
      </div>
      <div class="grid grid-cols-2 gap-2">
        <label class="flex flex-col gap-0.5">
          <span class="hc-field-label">
            {{ t.properties.fontIdLabel }}
            <Tooltip :text="t.properties.fontIdTip">
              <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
            </Tooltip>
          </span>
          <select class="hc-input" :value="element.fontId" @change="onFontIdChange">
            <optgroup :label="t.properties.fontGroupBuiltin">
              <option v-for="f in builtInFonts" :key="f.id" :value="f.id">{{ f.displayName }}</option>
            </optgroup>
            <optgroup v-if="userFonts.length > 0" :label="t.properties.fontGroupUser">
              <option v-for="f in userFonts" :key="f.id" :value="f.id">{{ f.displayName }}</option>
            </optgroup>
          </select>
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="hc-field-label">
            {{ t.properties.fontSizeLabel }}
            <Tooltip :text="t.properties.fontSizeTip">
              <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
            </Tooltip>
          </span>
          <input type="number" min="1" class="hc-input" :value="element.fontSize"
                 @input="(e) => onNumberChange('fontSize', e)">
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.properties.alignLabel }}</span>
          <select class="hc-input" :value="element.align" @change="(e) => onSelectChange('align', e)">
            <option value="left">{{ t.properties.alignLeft }}</option>
            <option value="center">{{ t.properties.alignCenter }}</option>
            <option value="right">{{ t.properties.alignRight }}</option>
          </select>
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="hc-field-label">
            {{ t.properties.colorLabel }}
            <Tooltip :text="t.properties.colorTip">
              <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
            </Tooltip>
          </span>
          <ColorInput :model-value="element.color"
                      @update:model-value="(v) => emit('update', { color: v })" />
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="hc-field-label">
            {{ t.properties.letterSpacing }}
            <Tooltip :text="t.properties.letterSpacingTip">
              <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
            </Tooltip>
          </span>
          <input type="number" step="0.5" class="hc-input" :value="element.letterSpacing"
                 @input="(e) => onNumberChange('letterSpacing', e)">
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="hc-field-label">
            {{ t.properties.lineHeight }}
            <Tooltip :text="t.properties.lineHeightTip">
              <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
            </Tooltip>
          </span>
          <input type="number" step="0.1" class="hc-input" :value="element.lineHeight"
                 @input="(e) => onNumberChange('lineHeight', e)">
        </label>
      </div>
      <label class="flex items-center gap-1.5 pt-1">
        <input type="checkbox" :checked="element.vertical"
               @change="(e) => emit('update', { vertical: (e.target as HTMLInputElement).checked })">
        <span>{{ t.properties.verticalHelp }}</span>
      </label>
    </div>
  </details>

  <!-- Effects 段（stroke / shadow / glow） -->
  <details class="group">
    <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-xs py-1 hover:text-[color:var(--foreground)]">
      {{ t.properties.effectsHeader }}
    </summary>
    <div class="pt-1.5 space-y-3">
      <!-- stroke -->
      <div>
        <label class="flex items-center justify-between">
          <span>stroke</span>
          <input type="checkbox" :checked="!!textEffects().stroke" @change="toggleStroke">
        </label>
        <div v-if="textEffects().stroke" class="grid grid-cols-2 gap-2 pt-1.5">
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">width</span>
            <input type="number" min="0" class="hc-input" :value="textEffects().stroke!.width"
                   @input="(e) => patchStroke({ width: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
          </label>
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">color</span>
            <ColorInput :model-value="textEffects().stroke!.color"
                        @update:model-value="(v) => patchStroke({ color: v })" />
          </label>
        </div>
      </div>
      <!-- shadow -->
      <div>
        <label class="flex items-center justify-between">
          <span>shadow</span>
          <input type="checkbox" :checked="!!textEffects().shadow" @change="toggleShadow">
        </label>
        <div v-if="textEffects().shadow" class="grid grid-cols-3 gap-2 pt-1.5">
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">dx</span>
            <input type="number" class="hc-input" :value="textEffects().shadow!.dx"
                   @input="(e) => patchShadow({ dx: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
          </label>
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">dy</span>
            <input type="number" class="hc-input" :value="textEffects().shadow!.dy"
                   @input="(e) => patchShadow({ dy: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
          </label>
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">color</span>
            <ColorInput :model-value="textEffects().shadow!.color"
                        @update:model-value="(v) => patchShadow({ color: v })" />
          </label>
        </div>
      </div>
      <!-- glow -->
      <div>
        <label class="flex items-center justify-between">
          <span>glow</span>
          <input type="checkbox" :checked="!!textEffects().glow" @change="toggleGlow">
        </label>
        <div v-if="textEffects().glow" class="grid grid-cols-2 gap-2 pt-1.5">
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">radius</span>
            <input type="number" min="0" max="64" class="hc-input" :value="textEffects().glow!.radius"
                   @input="(e) => patchGlow({ radius: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
          </label>
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">color</span>
            <ColorInput :model-value="textEffects().glow!.color"
                        @update:model-value="(v) => patchGlow({ color: v })" />
          </label>
        </div>
      </div>
    </div>
  </details>
</template>

<style scoped>
.hc-field-label {
    font-size: 10px;
    color: var(--muted-foreground);
    display: inline-flex;
    align-items: center;
    gap: 3px;
}
.hc-input {
    width: 100%;
    padding: 0.25rem 0.375rem;
    font-size: 0.75rem;
    line-height: 1rem;
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
}
.hc-input:focus {
    outline: none;
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
textarea.hc-input {
    min-height: 2.5rem;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    resize: none;
}

/* P2-H：text 区 + VariablePicker popover 包装 */
.hc-text-row {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 4px;
}
.hc-var-btn {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 1px 6px;
    font-size: 10px;
    color: var(--muted-foreground);
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 4px;
    cursor: pointer;
    transition: background 120ms, color 120ms;
}
.hc-var-btn:hover {
    background: var(--accent);
    color: var(--accent-foreground);
}
</style>
