<script setup lang="ts">
/**
 * M13-D：ImageElement 段（hash 缩略图 + replace + mask preset + inverted + dither）。
 *
 * mask 数据模型 = SVG path d 字符串（M13 决策 1）。v1 UI 仅暴露 4 个预设 kind：
 * none / circle / roundedRect / ellipse；UI 选择 → 生成 d 字符串发到后端。
 *
 * watch image bbox 变化 → 预设 mask 按新 bbox 重生成（用 detectMaskPreset 反推 kind，
 * 自由 path 落到 'none' 不动）。
 */
import { computed, ref, watch } from 'vue';
import { ImagePlus, RefreshCw } from 'lucide-vue-next';
import Tooltip from '@/components/ui/Tooltip.vue';
import { preloadImage } from '@/render/PreviewRenderer';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';
import type { ImageElement, Mask } from '@/types/protocol';

interface Props {
    element: ImageElement;
    locked: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{
    update: [patch: Record<string, unknown>];
}>();

const net = useNetworkStore();
const { t } = useI18n();

type MaskPresetKind = 'none' | 'circle' | 'roundedRect' | 'ellipse' | 'lasso';

// M16 P1.1：缩略图 URL 也需带 sessionId，否则 401
const thumbnailSrc = computed(() => {
    const base = `/api/upload/${encodeURIComponent(props.element.source)}`;
    return net.sessionId ? `${base}?sessionId=${encodeURIComponent(net.sessionId)}` : base;
});

const imageMaskKind = computed<MaskPresetKind>(() => detectMaskPreset(props.element));
const imageMaskInverted = computed<boolean>(() => props.element.mask?.inverted ?? false);
const imageMaskFeather = computed<number>(() => props.element.mask?.featherPx ?? 0);
const isDither = computed(() => props.element.renderMode === 'dither');

/**
 * 从已存在的 mask.d + bbox 反推出最匹配的预设 kind。v1：纯启发式
 * （路径首字符 + 命令计数；v2 lasso 自由 path 会落到 'none' 显示）。
 */
function detectMaskPreset(im: ImageElement | null): MaskPresetKind {
    if (!im || !im.mask) return 'none';
    const d = im.mask.d;
    const cCount = (d.match(/ C /g) ?? []).length;
    const qCount = (d.match(/ Q /g) ?? []).length;
    if (cCount === 4 && qCount === 0) {
        return im.w === im.h ? 'circle' : 'ellipse';
    }
    if (qCount === 4 && cCount === 0) return 'roundedRect';
    // 2026-05-25 项 1：4 预设之外的任意 d 一律视作 lasso（自由 path / 拖动编辑）
    return 'lasso';
}

/**
 * M16 P3.4：尺寸 sanitize —— NaN / Infinity / 负数 / 0 → 1；上限 16384（同后端 validateDim 范围）。
 * 防御性：上游 vue model 若被外部输入污染（如 v-model 输入框 invalid），不让 d 生成 `M NaN NaN`。
 */
function sanitizeDimension(v: number): number {
    return Number.isFinite(v) && v > 0 ? Math.min(v, 16384) : 1;
}

/**
 * M16 P3.4：radius sanitize —— 同 sanitizeDimension 但额外 clamp 到 maxR（一般是 min(w, h)/2）。
 */
function sanitizeRadius(v: number, maxR: number): number {
    const safe = Number.isFinite(v) && v > 0 ? v : 1;
    return Math.max(0.5, Math.min(safe, Math.max(0.5, maxR)));
}

/**
 * 用 cubic Bezier 4 段近似画椭圆（外接 bbox 0..w/0..h）。kappa ≈ 0.5523 是
 * 圆弧 → 三阶 Bezier 控制点的标准近似系数（误差 < 0.027%）。
 */
function makeEllipseD(wIn: number, hIn: number): string {
    const w = sanitizeDimension(wIn);
    const h = sanitizeDimension(hIn);
    const cx = w / 2;
    const cy = h / 2;
    const rx = w / 2;
    const ry = h / 2;
    const k = 0.5522847498;
    const cxk = rx * k;
    const cyk = ry * k;
    return [
        `M ${cx} 0`,
        `C ${cx + cxk} 0 ${w} ${cy - cyk} ${w} ${cy}`,
        `C ${w} ${cy + cyk} ${cx + cxk} ${h} ${cx} ${h}`,
        `C ${cx - cxk} ${h} 0 ${cy + cyk} 0 ${cy}`,
        `C 0 ${cy - cyk} ${cx - cxk} 0 ${cx} 0`,
        'Z',
    ].join(' ');
}

function makeCircleD(wIn: number, hIn: number): string {
    const w = sanitizeDimension(wIn);
    const h = sanitizeDimension(hIn);
    const r = Math.min(w, h) / 2;
    const cx = w / 2;
    const cy = h / 2;
    const k = 0.5522847498 * r;
    return [
        `M ${cx} ${cy - r}`,
        `C ${cx + k} ${cy - r} ${cx + r} ${cy - k} ${cx + r} ${cy}`,
        `C ${cx + r} ${cy + k} ${cx + k} ${cy + r} ${cx} ${cy + r}`,
        `C ${cx - k} ${cy + r} ${cx - r} ${cy + k} ${cx - r} ${cy}`,
        `C ${cx - r} ${cy - k} ${cx - k} ${cy - r} ${cx} ${cy - r}`,
        'Z',
    ].join(' ');
}

function makeRoundedRectD(wIn: number, hIn: number): string {
    const w = sanitizeDimension(wIn);
    const h = sanitizeDimension(hIn);
    // 圆角半径用启发式 15%，但 sanitize + clamp 上限 min(w, h) / 2
    const r = sanitizeRadius(Math.min(w, h) * 0.15, Math.min(w, h) / 2);
    return [
        `M ${r} 0`,
        `L ${w - r} 0`,
        `Q ${w} 0 ${w} ${r}`,
        `L ${w} ${h - r}`,
        `Q ${w} ${h} ${w - r} ${h}`,
        `L ${r} ${h}`,
        `Q 0 ${h} 0 ${h - r}`,
        `L 0 ${r}`,
        `Q 0 0 ${r} 0`,
        'Z',
    ].join(' ');
}

function onMaskKindChange(ev: Event) {
    const kind = (ev.target as HTMLSelectElement).value as MaskPresetKind;
    const im = props.element;
    if (kind === 'none') {
        emit('update', { mask: null });
        return;
    }
    if (kind === 'lasso') {
        // 仅切换标识 — 实际 path 等用户 Alt + 拖动绘制。若当前已是非预设 d 保留；
        // 否则提示用户去画布上拖
        return;
    }
    let d = '';
    if (kind === 'circle') d = makeCircleD(im.w, im.h);
    else if (kind === 'roundedRect') d = makeRoundedRectD(im.w, im.h);
    else if (kind === 'ellipse') d = makeEllipseD(im.w, im.h);
    const inverted = im.mask?.inverted ?? false;
    const featherPx = im.mask?.featherPx ?? null;
    emit('update', { mask: { d, inverted, featherPx } as Mask });
}

function onMaskInvertedToggle(ev: Event) {
    const im = props.element;
    if (!im.mask) return;
    const inverted = (ev.target as HTMLInputElement).checked;
    emit('update', { mask: { d: im.mask.d, inverted, featherPx: im.mask.featherPx ?? null } });
}

/** 2026-05-25 项 2：羽化滑块。0 = 无羽化（featherPx=null）；1..32 = 渐变像素数。 */
function onMaskFeatherChange(ev: Event) {
    const im = props.element;
    if (!im.mask) return;
    const raw = parseInt((ev.target as HTMLInputElement).value, 10);
    const v = Number.isFinite(raw) ? Math.max(0, Math.min(32, raw)) : 0;
    emit('update', {
        mask: {
            d: im.mask.d,
            inverted: im.mask.inverted,
            featherPx: v === 0 ? null : v,
        } as Mask,
    });
}

/** 2026-05-25 项 1：清除蒙版 — emit mask=null 让后端落 ImageElement.mask=null。 */
function onMaskClear() {
    emit('update', { mask: null });
}

/**
 * 2026-05-25 项 1：触发 lasso 编辑模式。仅是 UI hint —— 实际拖动绘制由 CanvasView /
 * useLassoMask 监听全局 Alt + drag 事件捕获，不需要 RightPanel 改 store state。
 * 这里只做用户教育（toast / hint）；按钮 click 主要为可发现性。
 */
function onMaskEditLasso() {
    // 简化实装：alert 提示用户操作方式。生产可接 toast / hint overlay；
    // 关键的实际编辑逻辑都在 useLassoMask composable 里，按钮非必需。
    if (typeof window !== 'undefined') {
        window.alert(t.value.image.maskEditLassoTip);
    }
}

function toggleImageDither(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    emit('update', { renderMode: on ? 'dither' : 'clean' });
}

/** 当 image bbox 变化（resize）时，把 mask 预设按新 bbox 重生成 —— 否则会被裁形变。
 *  仅当 d 是预设产生的才重生成，自由 path 不动。 */
watch(
    () => `${props.element.w}x${props.element.h}`,
    () => {
        const im = props.element;
        if (!im.mask) return;
        const kind = detectMaskPreset(im);
        // 2026-05-25 项 1：lasso 是用户自由 path，resize 时不重置（保持原 d）
        if (kind === 'lasso') return;
        let regenerated = '';
        if (kind === 'circle') regenerated = makeCircleD(im.w, im.h);
        else if (kind === 'roundedRect') regenerated = makeRoundedRectD(im.w, im.h);
        else if (kind === 'ellipse') regenerated = makeEllipseD(im.w, im.h);
        if (regenerated && regenerated !== im.mask.d) {
            emit('update', {
                mask: {
                    d: regenerated,
                    inverted: im.mask.inverted,
                    featherPx: im.mask.featherPx ?? null,
                } as Mask,
            });
        }
    },
);

// 替换图片：自己的 hidden file input
const imageReplaceInputRef = ref<HTMLInputElement | null>(null);
const imageReplaceError = ref<string | null>(null);
const imageReplacing = ref(false);

function triggerReplaceImage() {
    imageReplaceInputRef.value?.click();
}

async function onReplaceFileChange(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) { imageReplaceError.value = t.value.image.notImage; return; }
    if (!net.sessionId) { imageReplaceError.value = t.value.image.noSession; return; }
    imageReplacing.value = true;
    imageReplaceError.value = null;
    try {
        const dataUrl = await new Promise<string | null>((resolve) => {
            const r = new FileReader();
            r.onload = () => resolve(typeof r.result === 'string' ? r.result : null);
            r.onerror = () => resolve(null);
            r.readAsDataURL(file);
        });
        const fd = new FormData();
        fd.append('sessionId', net.sessionId);
        fd.append('file', file);
        const resp = await fetch('/api/upload', { method: 'POST', body: fd });
        if (!resp.ok) {
            const body = await resp.json().catch(() => ({} as Record<string, string>));
            imageReplaceError.value = t.value.image.uploadFailed(resp.status, body.message || body.error || '');
            return;
        }
        const result = await resp.json() as { source: string; width: number; height: number };
        if (dataUrl) preloadImage(result.source, dataUrl);
        emit('update', { source: result.source });
    } catch (err) {
        imageReplaceError.value = t.value.image.uploadFailed(0, (err as Error).message);
    } finally {
        imageReplacing.value = false;
        if (imageReplaceError.value) {
            const msg = imageReplaceError.value;
            window.setTimeout(() => {
                if (imageReplaceError.value === msg) imageReplaceError.value = null;
            }, 6000);
        }
    }
}
</script>

<template>
  <details class="group" open>
    <summary class="flex items-center justify-between cursor-pointer py-1.5">
      <span class="font-medium">{{ t.image.header }}</span>
      <span class="text-xs text-[color:var(--muted-foreground)] group-open:rotate-90 transition-transform">›</span>
    </summary>
    <div class="space-y-2 pt-1.5">
      <!-- 缩略图 + hash + replace -->
      <div class="flex gap-2 items-start">
        <div class="w-14 h-14 shrink-0 rounded border border-[color:var(--border)] bg-[color:var(--background)] overflow-hidden flex items-center justify-center">
          <img
            :src="thumbnailSrc"
            class="max-w-full max-h-full object-contain"
            alt=""
          />
        </div>
        <div class="flex-1 min-w-0 space-y-1">
          <Tooltip :text="t.image.sourceTip">
            <span class="block text-xs font-mono text-[color:var(--muted-foreground)] truncate" :title="element.source">
              {{ element.source }}
            </span>
          </Tooltip>
          <Tooltip :text="t.image.replaceTip">
            <button
              class="w-full inline-flex items-center gap-1 px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
              :disabled="imageReplacing"
              @click="triggerReplaceImage"
            >
              <component :is="imageReplacing ? RefreshCw : ImagePlus"
                         class="size-3" :class="{ 'animate-spin': imageReplacing }" />
              <span>{{ t.image.replace }}</span>
            </button>
          </Tooltip>
          <input
            ref="imageReplaceInputRef"
            type="file"
            accept="image/png,image/jpeg,image/webp"
            class="hidden"
            @change="onReplaceFileChange"
          />
          <div v-if="imageReplaceError" class="text-xs text-[color:var(--destructive)]">{{ imageReplaceError }}</div>
        </div>
      </div>

      <!-- 蒙版 -->
      <div class="space-y-1.5 pt-1 border-t border-[color:var(--border)]">
        <div class="flex items-center justify-between">
          <Tooltip :text="t.image.maskPresetTip">
            <span class="hc-field-label">{{ t.image.maskHeader }}</span>
          </Tooltip>
          <select class="hc-input w-32" :value="imageMaskKind" @change="onMaskKindChange">
            <option value="none">{{ t.image.maskPresets.none }}</option>
            <option value="circle">{{ t.image.maskPresets.circle }}</option>
            <option value="roundedRect">{{ t.image.maskPresets.roundedRect }}</option>
            <option value="ellipse">{{ t.image.maskPresets.ellipse }}</option>
            <option value="lasso">{{ t.image.maskPresets.lasso }}</option>
          </select>
        </div>
        <Tooltip v-if="imageMaskKind !== 'none'" :text="t.image.maskInvertedTip">
          <label class="flex items-center justify-between pl-1">
            <span class="hc-field-label">{{ t.image.maskInverted }}</span>
            <input type="checkbox" :checked="imageMaskInverted" @change="onMaskInvertedToggle" />
          </label>
        </Tooltip>
        <!-- 2026-05-25 项 2：羽化滑块 -->
        <Tooltip v-if="imageMaskKind !== 'none'" :text="t.image.maskFeatherTip">
          <label class="flex items-center gap-2 pl-1">
            <span class="hc-field-label flex-shrink-0">{{ t.image.maskFeather }}</span>
            <input
              type="range"
              min="0"
              max="32"
              step="1"
              :value="imageMaskFeather"
              :disabled="locked"
              class="flex-1"
              @input="onMaskFeatherChange"
            />
            <span class="hc-field-label tabular-nums w-10 text-right">{{ imageMaskFeather }} {{ t.image.maskFeatherUnit }}</span>
          </label>
        </Tooltip>
        <!-- 2026-05-25 项 1：lasso 编辑触发 + 清除蒙版 -->
        <div class="flex gap-1 pl-1">
          <Tooltip :text="t.image.maskEditLassoTip">
            <button
              class="flex-1 px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
              :disabled="locked"
              @click="onMaskEditLasso"
            >
              {{ t.image.maskEditLasso }}
            </button>
          </Tooltip>
          <button
            v-if="imageMaskKind !== 'none'"
            class="px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--destructive)] hover:text-white disabled:opacity-40"
            :disabled="locked"
            @click="onMaskClear"
          >
            {{ t.image.maskClear }}
          </button>
        </div>
      </div>

      <!-- Dither -->
      <Tooltip :text="t.fill.ditherTip">
        <label class="flex items-center justify-between pl-1 pt-1 border-t border-[color:var(--border)]">
          <span class="hc-field-label">{{ t.fill.ditherLabel }}</span>
          <input type="checkbox" :checked="isDither" @change="toggleImageDither" />
        </label>
      </Tooltip>
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
</style>
