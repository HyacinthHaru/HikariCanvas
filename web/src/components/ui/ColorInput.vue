<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { Check, Copy } from 'lucide-vue-next';
import { usePaletteStore } from '@/stores/palette';
import { DEFAULT_SWATCHES } from '@/config/palettes';
import { useI18n } from '@/i18n';

/**
 * M10 ColorInput：色块 + hex 文本 + 弹出 popover（HTML5 picker + alpha slider + 三色板 + 复制）。
 *
 * 与原生 {@code <input type="color">} 的差异：
 * - 支持 alpha（{@code allowAlpha} 默认 true，alpha=ff 时存 6 位 hex，否则 8 位）
 * - 项目色板 / 最近色板 / 默认色板三组 swatches
 * - 复制 hex 到剪贴板
 *
 * <p>颜色实时 emit：picker / slider input 阶段 emit 但不加最近；用户"确认"（picker change / swatch
 * click / hex 输入 / change）才 addRecent，避免拖动期间最近列表频繁刷新。</p>
 */
const props = withDefaults(defineProps<{
    modelValue: string;
    /** 是否允许 alpha 通道。默认 true。canvas.background 应传 false（背景永远不透明）。 */
    allowAlpha?: boolean;
}>(), {
    allowAlpha: true,
});

const emit = defineEmits<{
    (e: 'update:modelValue', value: string): void;
}>();

const palette = usePaletteStore();
const { t } = useI18n();

const rootRef = ref<HTMLElement | null>(null);
const open = ref(false);
const justCopied = ref(false);

/** 解析 #RRGGBB 或 #RRGGBBAA → { rgb: '#RRGGBB', alpha: 0-255 }。非法时退到 #000000/255 */
const parsed = computed(() => {
    const m = /^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$/.exec(props.modelValue ?? '');
    if (!m) return { rgb: '#000000', alpha: 255, ok: false };
    const rgb = '#' + m[1].toUpperCase();
    const alpha = m[2] ? parseInt(m[2], 16) : 255;
    return { rgb, alpha, ok: true };
});

const alphaPct = computed(() => Math.round((parsed.value.alpha / 255) * 100));

function buildHex(rgb: string, alpha: number): string {
    const rgbUp = rgb.toUpperCase();
    if (!props.allowAlpha || alpha >= 255) return rgbUp;
    const a = Math.max(0, Math.min(255, Math.round(alpha)))
        .toString(16).padStart(2, '0').toUpperCase();
    return `${rgbUp}${a}`;
}

function setColor(hex: string, recent: boolean): void {
    emit('update:modelValue', hex);
    if (recent) palette.addRecent(hex);
}

// ---------- HTML5 picker ----------

function onPickerInput(ev: Event) {
    const rgb = (ev.target as HTMLInputElement).value;
    setColor(buildHex(rgb, parsed.value.alpha), false);
}
function onPickerChange(ev: Event) {
    const rgb = (ev.target as HTMLInputElement).value;
    setColor(buildHex(rgb, parsed.value.alpha), true);
}

// ---------- alpha slider ----------

function onAlphaInput(ev: Event) {
    const a = parseInt((ev.target as HTMLInputElement).value, 10);
    if (!Number.isFinite(a)) return;
    setColor(buildHex(parsed.value.rgb, a), false);
}
function onAlphaChange(ev: Event) {
    const a = parseInt((ev.target as HTMLInputElement).value, 10);
    if (!Number.isFinite(a)) return;
    setColor(buildHex(parsed.value.rgb, a), true);
}

// ---------- hex 文本 ----------

const hexDraft = ref('');
const hexFocused = ref(false);

// modelValue 变（picker/slider/swatch）时同步 hexDraft，但用户正在 type 时不打断
watch(() => props.modelValue, (v) => {
    if (!hexFocused.value) hexDraft.value = v;
});

function onHexFocus() {
    hexFocused.value = true;
    hexDraft.value = props.modelValue;
}
function commitHexEdit() {
    hexFocused.value = false;
    const v = hexDraft.value.trim();
    const m = /^#?([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$/.exec(v);
    if (!m) {
        // 无效输入，回退到原值
        hexDraft.value = props.modelValue;
        return;
    }
    const rgb = '#' + m[1].toUpperCase();
    const alpha = m[2] ? parseInt(m[2], 16) : 255;
    const finalHex = buildHex(rgb, alpha);
    hexDraft.value = finalHex;
    setColor(finalHex, true);
}

// ---------- swatch 选择 ----------

function selectSwatch(c: string) {
    const m = /^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$/.exec(c);
    if (!m) return;
    const rgb = '#' + m[1].toUpperCase();
    const alpha = m[2] ? parseInt(m[2], 16) : 255;
    setColor(buildHex(rgb, alpha), true);
}

// ---------- 复制 hex ----------

async function copyHex() {
    try {
        await navigator.clipboard.writeText(props.modelValue);
        justCopied.value = true;
        setTimeout(() => { justCopied.value = false; }, 1500);
    } catch { /* clipboard may be unavailable */ }
}

// ---------- popover close ----------

onClickOutside(rootRef, () => { open.value = false; });

function toggle() {
    open.value = !open.value;
    if (open.value) {
        hexDraft.value = props.modelValue;
        hexFocused.value = false;
    }
}
</script>

<template>
  <div ref="rootRef" class="hc-color-input">
    <button
      type="button"
      class="hc-color-trigger"
      :title="`${t.tooltips.colorPicker}（${modelValue}）`"
      @click="toggle"
    >
      <span class="hc-swatch-outer hc-checkerboard">
        <span class="hc-swatch-inner" :style="{ background: modelValue }"></span>
      </span>
      <span class="hc-color-hex">{{ modelValue }}</span>
    </button>

    <div v-if="open" class="hc-color-popover">
      <!-- HTML5 picker + alpha slider -->
      <div class="hc-pp-section">
        <input
          type="color"
          class="hc-native-picker"
          :value="parsed.rgb"
          @input="onPickerInput"
          @change="onPickerChange"
        >
        <div v-if="allowAlpha" class="hc-alpha-row">
          <span class="hc-alpha-label">α</span>
          <span class="hc-alpha-track hc-checkerboard">
            <span class="hc-alpha-overlay" :style="{
              background: `linear-gradient(to right, ${parsed.rgb}00 0%, ${parsed.rgb}FF 100%)`,
            }"></span>
            <input
              type="range"
              min="0"
              max="255"
              :value="parsed.alpha"
              class="hc-alpha-input"
              @input="onAlphaInput"
              @change="onAlphaChange"
            >
          </span>
          <span class="hc-alpha-value">{{ alphaPct }}%</span>
        </div>
      </div>

      <!-- hex 文本框 + 复制 -->
      <div class="hc-hex-row">
        <input
          type="text"
          class="hc-hex-input"
          :value="hexDraft"
          maxlength="9"
          @focus="onHexFocus"
          @input="(e) => hexDraft = (e.target as HTMLInputElement).value"
          @keydown.enter.prevent="commitHexEdit"
          @blur="commitHexEdit"
        >
        <button
          type="button"
          class="hc-copy-btn"
          :title="justCopied ? t.palette.copied : t.palette.copyTip"
          @click="copyHex"
        >
          <component :is="justCopied ? Check : Copy" class="size-3" />
        </button>
      </div>

      <!-- 项目色板 -->
      <div v-if="palette.projectColors.length > 0" class="hc-pp-section">
        <h6 class="hc-pp-title">
          {{ t.palette.projectHeader }}
          <span class="hc-pp-count">{{ palette.projectColors.length }}</span>
        </h6>
        <div class="hc-swatch-grid">
          <button
            v-for="c in palette.projectColors"
            :key="`p-${c}`"
            type="button"
            class="hc-swatch-outer hc-checkerboard hc-swatch-btn"
            :title="c"
            @click="selectSwatch(c)"
          >
            <span class="hc-swatch-inner" :style="{ background: c }"></span>
          </button>
        </div>
      </div>

      <!-- 最近色板 -->
      <div v-if="palette.recent.length > 0" class="hc-pp-section">
        <h6 class="hc-pp-title">
          {{ t.palette.recentHeader }}
          <span class="hc-pp-count">{{ palette.recent.length }}</span>
        </h6>
        <div class="hc-swatch-grid">
          <button
            v-for="c in palette.recent"
            :key="`r-${c}`"
            type="button"
            class="hc-swatch-outer hc-checkerboard hc-swatch-btn"
            :title="c"
            @click="selectSwatch(c)"
          >
            <span class="hc-swatch-inner" :style="{ background: c }"></span>
          </button>
        </div>
      </div>

      <!-- 默认色板 -->
      <div class="hc-pp-section">
        <h6 class="hc-pp-title">{{ t.palette.defaultHeader }}</h6>
        <div class="hc-swatch-grid">
          <button
            v-for="c in DEFAULT_SWATCHES"
            :key="`d-${c}`"
            type="button"
            class="hc-swatch-outer hc-checkerboard hc-swatch-btn"
            :title="c"
            @click="selectSwatch(c)"
          >
            <span class="hc-swatch-inner" :style="{ background: c }"></span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-color-input {
    position: relative;
    display: inline-block;
    width: 100%;
}

/* trigger：色块 + hex 文本，整体可点 */
.hc-color-trigger {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    padding: 3px 6px;
    border-radius: 4px;
    background: var(--background);
    border: 1px solid var(--border);
    cursor: pointer;
    transition: border-color 120ms;
}
.hc-color-trigger:hover { border-color: var(--ring); }
.hc-color-hex {
    font-size: 10px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    color: var(--foreground);
    flex: 1;
    text-align: left;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* M24-B：棋盘格背景从硬编码 #ccc 改为 Catppuccin surface2（在 Latte / Frappé / Macchiato
   三个 flavor 下都能与背景区分；alpha 透明时露出）。 */
.hc-checkerboard {
    background-image:
        linear-gradient(45deg, var(--ctp-surface2) 25%, transparent 25%, transparent 75%, var(--ctp-surface2) 75%),
        linear-gradient(45deg, var(--ctp-surface2) 25%, transparent 25%, transparent 75%, var(--ctp-surface2) 75%);
    background-size: 8px 8px;
    background-position: 0 0, 4px 4px;
}

.hc-swatch-outer {
    position: relative;
    display: inline-block;
    width: 16px;
    height: 16px;
    border-radius: 3px;
    overflow: hidden;
    border: 1px solid var(--border);
}
.hc-swatch-inner {
    display: block;
    width: 100%;
    height: 100%;
}

/* popover */
.hc-color-popover {
    position: absolute;
    top: calc(100% + 4px);
    left: 0;
    right: 0;
    z-index: 50;
    max-width: 260px;
    padding: 10px;
    border-radius: var(--radius);
    background: var(--card);
    color: var(--foreground);
    border: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    gap: 10px;
}

.hc-pp-section { display: flex; flex-direction: column; gap: 4px; }

.hc-native-picker {
    width: 100%;
    height: 28px;
    border-radius: 4px;
    border: 1px solid var(--border);
    background: transparent;
    padding: 0;
    cursor: pointer;
}

.hc-alpha-row {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 16px;
}
.hc-alpha-label {
    font-size: 10px;
    color: var(--muted-foreground);
    width: 10px;
}
.hc-alpha-value {
    font-size: 10px;
    font-variant-numeric: tabular-nums;
    color: var(--muted-foreground);
    width: 30px;
    text-align: right;
}
.hc-alpha-track {
    position: relative;
    flex: 1;
    height: 14px;
    border-radius: 7px;
    overflow: hidden;
    border: 1px solid var(--border);
}
.hc-alpha-overlay {
    position: absolute;
    inset: 0;
    pointer-events: none;
}
.hc-alpha-input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    appearance: none;
    background: transparent;
    cursor: ew-resize;
}
.hc-alpha-input::-webkit-slider-thumb {
    appearance: none;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: var(--foreground);
    border: 2px solid var(--card);
    cursor: ew-resize;
    box-shadow: 0 0 0 1px var(--border);
}
.hc-alpha-input::-moz-range-thumb {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--foreground);
    border: 2px solid var(--card);
    cursor: ew-resize;
    box-shadow: 0 0 0 1px var(--border);
}

.hc-hex-row { display: flex; gap: 4px; align-items: stretch; }
.hc-hex-input {
    flex: 1;
    padding: 3px 6px;
    font-size: 10px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    border-radius: 4px;
    background: var(--background);
    border: 1px solid var(--border);
    color: var(--foreground);
}
.hc-hex-input:focus {
    outline: none;
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
.hc-copy-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0 6px;
    border-radius: 4px;
    background: var(--background);
    border: 1px solid var(--border);
    color: var(--muted-foreground);
    cursor: pointer;
}
.hc-copy-btn:hover {
    background: var(--accent);
    color: var(--foreground);
}

.hc-pp-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 9px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--muted-foreground);
    margin: 2px 0;
}
.hc-pp-count {
    font-weight: 400;
    text-transform: none;
    opacity: 0.7;
}

.hc-swatch-grid {
    display: grid;
    grid-template-columns: repeat(8, 1fr);
    gap: 4px;
}
.hc-swatch-btn {
    width: 100%;
    height: 22px;
    padding: 0;
    cursor: pointer;
}
.hc-swatch-btn:hover {
    transform: scale(1.1);
    transition: transform 80ms;
}
</style>
