<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { X, Sparkles, RotateCcw, AlertTriangle, ImageOff } from 'lucide-vue-next';
import { useTemplatesStore } from '@/stores/templates';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useLockGuard } from '@/composables/useLockGuard';
import { useI18n } from '@/i18n';
import { evalBoolean } from '@/lib/templateExpr';
import { FONT_META } from '@/render/PreviewRenderer';
import type { TemplateParam, TemplateParamOption, TemplateSpec } from '@/types/template';

const templates = useTemplatesStore();
const project = useProjectStore();
const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();
// lock 时 apply 必须拒 — TemplateGallery 可被 LeftTools / TopBar 打开，
// 但 apply 才是真正的 mutation（replaceContent + state.snapshot）。
const lockGuard = useLockGuard();

const selected = computed<TemplateSpec | null>(() =>
    templates.selectedId ? templates.byId.get(templates.selectedId) ?? null : null);

const draftParams = computed(() => templates.currentDraft());

const visibleParams = computed(() => {
    const tpl = selected.value;
    if (!tpl?.params) return [] as Array<[string, TemplateParam]>;
    return Object.entries(tpl.params).filter(
        ([, p]) => evalBoolean(p.visible_when, draftParams.value),
    ) as Array<[string, TemplateParam]>;
});

/**
 * 按 param.group 分组。无 group 字段的归入 null 组（无标题、直接展开）；
 * 有 group 的按 YAML 中首次出现顺序排列分组（不按字母序）。
 */
const paramGroups = computed<Array<{ name: string | null; entries: Array<[string, TemplateParam]> }>>(() => {
    const groups: Array<{ name: string | null; entries: Array<[string, TemplateParam]> }> = [];
    const idx = new Map<string | null, number>();
    for (const [key, p] of visibleParams.value) {
        const g = p.group ?? null;
        let pos = idx.get(g);
        if (pos === undefined) {
            pos = groups.length;
            idx.set(g, pos);
            groups.push({ name: g, entries: [] });
        }
        groups[pos].entries.push([key, p]);
    }
    return groups;
});

/** 折叠态：key = `${templateId}::${groupName}`；默认全展开。 */
const collapsedGroups = ref<Set<string>>(new Set());
function toggleGroup(groupName: string | null) {
    if (!templates.selectedId || !groupName) return;
    const key = `${templates.selectedId}::${groupName}`;
    if (collapsedGroups.value.has(key)) collapsedGroups.value.delete(key);
    else collapsedGroups.value.add(key);
}
function isCollapsed(groupName: string | null): boolean {
    if (!templates.selectedId || !groupName) return false;
    return collapsedGroups.value.has(`${templates.selectedId}::${groupName}`);
}

/**
 * 当前画布上是否已经有东西——决定套用模板前要不要再问一遍。
 *
 * <p>必须看<b>所有图层</b>：套用模板是整个工程被换掉，不是只换当前图层。只看当前图层的话，
 * 站在一个空图层上套模板就一句话不问，直接把别的图层辛苦画的全清了。</p>
 */
const hasExistingContent = computed(() => {
    const s = project.state;
    if (!s) return false;
    if (s.layers && s.layers.length > 0) {
        return s.layers.some((l) => (l.elements?.length ?? 0) > 0);
    }
    // 没有 layers 字段（老快照）时退回兼容视图
    return (s.elements?.length ?? 0) > 0;
});

// ---------- 预览缩略图 ----------
// 后端 GET /api/template/{id}/preview.png 返回 PNG（无预览则 404）。同源相对 URL，
// 缓存交给端点自己的 Cache-Control。
// 必须带 sessionId：缩略图是模板画布内容的完整渲染图，后端按调用方身份过滤——不带身份
// 就只能拿到 builtin / server 模板，自己存的模板会 404 显占位。

/** 拉取失败（404 / 未渲染）的模板 id 集合 —— 命中则改渲占位符，不露破图。 */
const failedPreviews = ref<Set<string>>(new Set());

function previewUrl(id: string): string {
    const base = `/api/template/${encodeURIComponent(id)}/preview.png`;
    const sid = net.sessionId;
    return sid ? `${base}?sessionId=${encodeURIComponent(sid)}` : base;
}

function markPreviewFailed(id: string) {
    // ref(new Set) 在 Vue 3 下是响应式集合，原地 add 会触发依赖 has() 的重渲。
    failedPreviews.value.add(id);
}

// 模板列表刷新（如重新保存模板）后清空失败缓存，让预览重新拉取。
watch(() => templates.list, () => {
    failedPreviews.value.clear();
});

// ---------- 画布尺寸约束 ----------

interface SizeRange { min: [number, number]; max: [number, number]; fixed: boolean; }

function sizeRange(tpl: TemplateSpec): SizeRange {
    const c = tpl.canvas ?? {};
    if (c.size === 'fixed' && c.maps) {
        return { min: c.maps, max: c.maps, fixed: true };
    }
    return {
        min: c.min_maps ?? [1, 1],
        max: c.max_maps ?? [8, 4],
        fixed: false,
    };
}

function currentWallSize(): [number, number] | null {
    const cv = project.state?.canvas;
    if (!cv) return null;
    return [cv.widthMaps, cv.heightMaps];
}

function fitsCurrentWall(tpl: TemplateSpec): boolean {
    const wall = currentWallSize();
    if (!wall) return true;
    const r = sizeRange(tpl);
    return wall[0] >= r.min[0] && wall[0] <= r.max[0]
        && wall[1] >= r.min[1] && wall[1] <= r.max[1];
}

function formatRange(r: SizeRange): string {
    if (r.fixed) return `${r.min[0]}×${r.min[1]}`;
    if (r.min[0] === r.max[0] && r.min[1] === r.max[1]) return `${r.min[0]}×${r.min[1]}`;
    return `${r.min[0]}×${r.min[1]} – ${r.max[0]}×${r.max[1]}`;
}

// ---------- apply 流（成功/失败/超时三态） ----------

/** confirm 阶段：null = 未触发；'pending' = 已点 apply 等二次确认；'applying' = 已发包等 ack。 */
const confirmStage = ref<null | 'pending' | 'applying'>(null);
const lastError = ref<string | null>(null);

function close() {
    confirmStage.value = null;
    lastError.value = null;
    templates.closeGallery();
}

function pick(id: string) {
    templates.selectTemplate(id);
    confirmStage.value = null;
    lastError.value = null;
}

function setParam(key: string, value: unknown) {
    if (!templates.selectedId) return;
    templates.setParam(templates.selectedId, key, value);
}

function resetParams() {
    if (!templates.selectedId) return;
    templates.resetParams(templates.selectedId);
}

async function applyNow() {
    const tpl = selected.value;
    if (!tpl) return;
    // lock 状态下拒绝（同时把 confirmStage 清掉，避免按钮卡在 applying）
    if (!lockGuard.guardMutation(t.value.templates.apply)) {
        confirmStage.value = null;
        return;
    }
    if (hasExistingContent.value && confirmStage.value !== 'pending') {
        confirmStage.value = 'pending';
        return;
    }
    confirmStage.value = 'applying';
    lastError.value = null;

    try {
        await ws.sendWithAck('template.apply', {
            templateId: tpl.id,
            params: draftParams.value,
        }, 8000);
        // ack 落地 = 服务端 replaceContent 已成功 + state.snapshot 已下行
        confirmStage.value = null;
        close();
    } catch (e) {
        const msg = (e as Error).message;
        confirmStage.value = null;
        if (msg === 'ack_timeout') {
            lastError.value = t.value.templates.applyTimeout;
        } else if (msg === 'send_failed') {
            lastError.value = t.value.templates.applyFailed;
        } else {
            // 服务端错误 envelope 的 code/message 拼接在 Error.message 里
            lastError.value = msg || t.value.templates.applyFailed;
        }
    }
}

function cancelConfirm() {
    confirmStage.value = null;
}

// ---------- param 控件 helpers ----------

function isParamType(p: TemplateParam, t: TemplateParam['type']): boolean {
    return p.type === t;
}

function paramValue(key: string): unknown {
    return draftParams.value[key];
}

function asString(v: unknown, fallback = ''): string {
    return typeof v === 'string' ? v : v == null ? fallback : String(v);
}

function asNumber(v: unknown, fallback = 0): number {
    if (typeof v === 'number') return v;
    if (typeof v === 'string') {
        const n = parseFloat(v);
        return Number.isFinite(n) ? n : fallback;
    }
    return fallback;
}

function asBool(v: unknown): boolean {
    if (typeof v === 'boolean') return v;
    if (typeof v === 'string') return v === 'true';
    return false;
}

/** type: font 的 param 没声明 options 时回退到 FONT_META（ark_pixel + source_han_sans）。 */
function fontOptions(p: TemplateParam): TemplateParamOption[] {
    if (p.options && p.options.length > 0) return p.options;
    return Object.entries(FONT_META).map(([id, meta]) => ({
        label: meta.displayName,
        value: id,
    }));
}
</script>

<template>
  <div
    v-if="templates.galleryOpen"
    class="fixed inset-0 z-50 flex items-center justify-center bg-[color:var(--ctp-crust)]/50"
    @click.self="close"
  >
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md w-[min(900px,95vw)] h-[min(640px,90vh)] flex flex-col border border-[color:var(--border)]">
      <!-- header -->
      <header class="flex items-center px-4 h-10 border-b border-[color:var(--border)] gap-2">
        <Sparkles class="size-4" />
        <h2 class="text-sm font-medium">{{ t.templates.header }}</h2>
        <span class="text-xs text-[color:var(--muted-foreground)] ml-2">
          {{ t.templates.count(templates.list.length) }}
        </span>
        <button
          class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]"
          :title="t.templates.close"
          @click="close"
        >
          <X class="size-4" />
        </button>
      </header>

      <div class="flex-1 flex min-h-0">
        <!-- gallery list -->
        <aside class="w-72 border-r border-[color:var(--border)] overflow-y-auto">
          <ul v-if="templates.list.length > 0">
            <li
              v-for="tpl in templates.list"
              :key="tpl.id"
              class="px-3 py-2 cursor-pointer border-b border-[color:var(--border)] hover:bg-[color:var(--accent)] transition-colors"
              :class="{ 'bg-[color:var(--accent)]': templates.selectedId === tpl.id }"
              @click="pick(tpl.id)"
            >
              <!-- 预览缩略图；宽招牌用 object-contain 居中留白，无预览时占位符不露破图 -->
              <span class="hc-preview-box block w-full h-16 mb-1.5">
                <img
                  v-if="!failedPreviews.has(tpl.id)"
                  :src="previewUrl(tpl.id)"
                  alt=""
                  class="block w-full h-full object-contain"
                  loading="lazy"
                  draggable="false"
                  @error="markPreviewFailed(tpl.id)"
                >
                <span v-else class="w-full h-full flex items-center justify-center text-[color:var(--muted-foreground)]">
                  <ImageOff class="size-4 opacity-60" aria-hidden="true" />
                </span>
              </span>
              <div class="flex items-center gap-1.5">
                <div class="text-sm font-medium truncate flex-1">{{ tpl.name }}</div>
                <AlertTriangle
                  v-if="!fitsCurrentWall(tpl)"
                  class="size-3.5 text-[color:var(--destructive)] shrink-0"
                  :title="t.templates.incompatibleHint"
                />
              </div>
              <div v-if="tpl.description" class="text-xs text-[color:var(--muted-foreground)] line-clamp-2 mt-0.5">
                {{ tpl.description }}
              </div>
              <div class="flex items-center gap-1.5 mt-1 text-xs text-[color:var(--muted-foreground)] tabular-nums">
                <span>{{ t.templates.sizeLabel }}: {{ formatRange(sizeRange(tpl)) }}</span>
                <span v-if="!fitsCurrentWall(tpl)" class="text-[color:var(--destructive)] font-medium">
                  {{ t.templates.incompatible }}
                </span>
              </div>
              <div v-if="tpl.tags && tpl.tags.length > 0" class="flex gap-1 mt-1 flex-wrap">
                <span
                  v-for="tag in tpl.tags"
                  :key="tag"
                  class="text-xs px-1.5 py-0.5 rounded bg-[color:var(--background)] text-[color:var(--muted-foreground)] uppercase tracking-wider"
                >{{ tag }}</span>
              </div>
            </li>
          </ul>
          <div v-else class="p-3 text-xs text-[color:var(--muted-foreground)]">
            {{ t.templates.empty }}
          </div>
        </aside>

        <!-- param form -->
        <section class="flex-1 flex flex-col min-w-0">
          <div v-if="!selected" class="flex-1 flex items-center justify-center text-xs text-[color:var(--muted-foreground)]">
            {{ t.templates.pickHint }}
          </div>
          <template v-else>
            <!-- 详情大预览：占满右栏宽度，比列表缩略图更大更宽 -->
            <div class="px-4 pt-4 pb-1">
              <span class="hc-preview-box block w-full h-28">
                <img
                  v-if="!failedPreviews.has(selected.id)"
                  :src="previewUrl(selected.id)"
                  alt=""
                  class="block w-full h-full object-contain"
                  draggable="false"
                  @error="markPreviewFailed(selected.id)"
                >
                <span v-else class="w-full h-full flex items-center justify-center text-[color:var(--muted-foreground)]">
                  <ImageOff class="size-6 opacity-60" aria-hidden="true" />
                </span>
              </span>
            </div>
            <header class="px-4 py-3 border-b border-[color:var(--border)] flex items-center gap-3">
              <div class="min-w-0">
                <div class="text-base font-medium truncate">{{ selected.name }}</div>
                <div class="text-xs text-[color:var(--muted-foreground)] truncate">
                  {{ t.templates.idLabel }}: <span class="font-mono">{{ selected.id }}</span>
                  <span v-if="selected.author"> · {{ selected.author }}</span>
                  <span v-if="selected.version"> · v{{ selected.version }}</span>
                </div>
                <div class="text-xs mt-0.5 flex items-center gap-2 tabular-nums">
                  <span class="text-[color:var(--muted-foreground)]">
                    {{ t.templates.sizeLabel }}: {{ formatRange(sizeRange(selected)) }}
                  </span>
                  <span class="text-[color:var(--muted-foreground)]">·</span>
                  <span class="text-[color:var(--muted-foreground)]">
                    {{ t.templates.currentWall }}:
                    <span v-if="currentWallSize()">{{ currentWallSize()![0] }}×{{ currentWallSize()![1] }}</span>
                    <span v-else>—</span>
                  </span>
                  <span
                    v-if="!fitsCurrentWall(selected)"
                    class="px-1.5 py-0.5 rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] text-xs font-medium uppercase tracking-wider"
                  >
                    {{ t.templates.incompatible }}
                  </span>
                </div>
              </div>
              <button
                class="ml-auto text-xs px-2 py-1 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] flex items-center gap-1"
                :title="t.templates.resetParams"
                @click="resetParams"
              >
                <RotateCcw class="size-3" />
                {{ t.templates.resetParams }}
              </button>
            </header>

            <div class="flex-1 overflow-y-auto p-4 space-y-5">
              <div
                v-if="visibleParams.length === 0"
                class="text-xs text-[color:var(--muted-foreground)] italic"
              >
                {{ t.templates.noParams }}
              </div>
              <!-- 按 group 分节渲染；无 group 的归 null 组直接展开 -->
              <section
                v-for="group in paramGroups"
                :key="group.name ?? '__default__'"
                class="space-y-3"
              >
                <header
                  v-if="group.name"
                  class="flex items-center gap-1.5 text-xs uppercase tracking-wider text-[color:var(--muted-foreground)] font-medium cursor-pointer hover:text-[color:var(--foreground)] transition-colors select-none"
                  @click="toggleGroup(group.name)"
                >
                  <span class="opacity-60">{{ isCollapsed(group.name) ? '▸' : '▾' }}</span>
                  <span>{{ group.name }}</span>
                  <span class="opacity-50">· {{ group.entries.length }}</span>
                </header>
                <div v-show="!isCollapsed(group.name)" class="space-y-3">
                  <label
                    v-for="[key, p] in group.entries"
                    :key="key"
                    class="block"
                  >
                <span class="text-xs text-[color:var(--muted-foreground)] flex items-center gap-1.5">
                  {{ p.label ?? key }}
                  <span v-if="p.required" class="text-[color:var(--destructive)]">*</span>
                  <span v-if="p.description" class="text-xs opacity-70">— {{ p.description }}</span>
                </span>

                <!-- string -->
                <input
                  v-if="isParamType(p, 'string')"
                  type="text"
                  class="hc-input mt-1"
                  :value="asString(paramValue(key))"
                  :placeholder="p.placeholder ?? ''"
                  :maxlength="p.max_length"
                  @input="(e) => setParam(key, (e.target as HTMLInputElement).value)"
                />
                <!-- text (multi-line) -->
                <textarea
                  v-else-if="isParamType(p, 'text')"
                  rows="3"
                  class="hc-input mt-1 font-mono resize-none"
                  :value="asString(paramValue(key))"
                  :placeholder="p.placeholder ?? ''"
                  :maxlength="p.max_length"
                  @input="(e) => setParam(key, (e.target as HTMLTextAreaElement).value)"
                ></textarea>
                <!-- int / float -->
                <input
                  v-else-if="isParamType(p, 'int') || isParamType(p, 'float')"
                  type="number"
                  class="hc-input mt-1"
                  :value="asNumber(paramValue(key))"
                  :min="p.min"
                  :max="p.max"
                  :step="p.step ?? (isParamType(p, 'int') ? 1 : 0.1)"
                  @input="(e) => setParam(key, parseFloat((e.target as HTMLInputElement).value))"
                />
                <!-- bool -->
                <label v-else-if="isParamType(p, 'bool')" class="flex items-center gap-2 mt-1 cursor-pointer">
                  <input
                    type="checkbox"
                    :checked="asBool(paramValue(key))"
                    @change="(e) => setParam(key, (e.target as HTMLInputElement).checked)"
                  />
                  <span class="text-xs">{{ asBool(paramValue(key)) ? t.templates.on : t.templates.off }}</span>
                </label>
                <!-- color -->
                <div v-else-if="isParamType(p, 'color')" class="mt-1 flex items-center gap-2">
                  <input
                    type="color"
                    class="hc-color w-12 h-8"
                    :value="asString(paramValue(key), '#000000')"
                    @input="(e) => setParam(key, (e.target as HTMLInputElement).value.toUpperCase())"
                  />
                  <input
                    type="text"
                    class="hc-input flex-1 font-mono"
                    :value="asString(paramValue(key))"
                    @input="(e) => setParam(key, (e.target as HTMLInputElement).value.toUpperCase())"
                  />
                  <div v-if="p.presets && p.presets.length > 0" class="flex gap-1">
                    <button
                      v-for="preset in p.presets"
                      :key="String(preset.value)"
                      type="button"
                      class="w-5 h-5 rounded border border-[color:var(--border)]"
                      :style="{ background: String(preset.value) }"
                      :title="preset.label"
                      @click="setParam(key, preset.value)"
                    ></button>
                  </div>
                </div>
                <!-- enum (下拉) -->
                <select
                  v-else-if="isParamType(p, 'enum')"
                  class="hc-input mt-1"
                  :value="asString(paramValue(key))"
                  @change="(e) => setParam(key, (e.target as HTMLSelectElement).value)"
                >
                  <option
                    v-for="opt in p.options ?? []"
                    :key="String(opt.value)"
                    :value="String(opt.value)"
                  >{{ opt.label }}</option>
                </select>
                <!-- font (下拉，没 options 自动用 FONT_META) -->
                <select
                  v-else-if="isParamType(p, 'font')"
                  class="hc-input mt-1"
                  :value="asString(paramValue(key))"
                  @change="(e) => setParam(key, (e.target as HTMLSelectElement).value)"
                >
                  <option
                    v-for="opt in fontOptions(p)"
                    :key="String(opt.value)"
                    :value="String(opt.value)"
                  >{{ opt.label }}</option>
                </select>
                  </label>
                </div>
              </section>
            </div>

            <!-- footer + apply -->
            <footer class="border-t border-[color:var(--border)] p-3 flex items-center gap-2">
              <div
                v-if="lastError"
                class="text-xs text-[color:var(--destructive)] flex-1 flex items-start gap-1.5"
              >
                <AlertTriangle class="size-3.5 shrink-0 mt-0.5" />
                <span class="break-words">{{ lastError }}</span>
              </div>
              <div v-else-if="confirmStage === 'pending'" class="text-xs text-[color:var(--destructive)] flex-1">
                {{ t.templates.overwriteWarning }}
              </div>
              <div v-else-if="!fitsCurrentWall(selected)" class="text-xs text-[color:var(--destructive)] flex-1 flex items-start gap-1.5">
                <AlertTriangle class="size-3.5 shrink-0 mt-0.5" />
                <span>{{ t.templates.wallMismatchHint(formatRange(sizeRange(selected))) }}</span>
              </div>
              <div v-else class="flex-1"></div>

              <template v-if="confirmStage === 'pending'">
                <button
                  type="button"
                  class="text-xs px-3 py-1.5 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                  @click="cancelConfirm"
                >{{ t.templates.cancel }}</button>
                <button
                  type="button"
                  class="text-xs px-3 py-1.5 rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] hover:opacity-90"
                  @click="applyNow"
                >{{ t.templates.applyConfirm }}</button>
              </template>
              <template v-else>
                <!-- lock 状态下禁用 apply 按钮（hover tooltip 通过 :title 提示） -->
                <button
                  type="button"
                  class="text-xs px-3 py-1.5 rounded bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed flex items-center gap-1"
                  :disabled="!net.authenticated || confirmStage === 'applying' || !fitsCurrentWall(selected) || lockGuard.readonly.value"
                  :title="lockGuard.readonly.value ? t.lockGuard.blocked : undefined"
                  @click="applyNow"
                >
                  <Sparkles class="size-3.5" />
                  {{ confirmStage === 'applying' ? t.templates.applying : t.templates.apply }}
                </button>
              </template>
            </footer>
          </template>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-input {
    width: 100%;
    padding: 0.375rem 0.5rem;
    font-size: 0.8125rem;
    line-height: 1.25rem;
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
.hc-color {
    border-radius: 4px;
    border: 1px solid var(--border);
    padding: 0;
    cursor: pointer;
    background: transparent;
}
/* 模板预览框：与图层缩略图同款 8px 棋盘格，透明像素 / 招牌留白处可辨。 */
.hc-preview-box {
    overflow: hidden;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background-color: var(--card);
    background-image:
        linear-gradient(45deg, var(--muted) 25%, transparent 25%),
        linear-gradient(-45deg, var(--muted) 25%, transparent 25%),
        linear-gradient(45deg, transparent 75%, var(--muted) 75%),
        linear-gradient(-45deg, transparent 75%, var(--muted) 75%);
    background-size: 8px 8px;
    background-position: 0 0, 0 4px, 4px -4px, -4px 0;
}
</style>
