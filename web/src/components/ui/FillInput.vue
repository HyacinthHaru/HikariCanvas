<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Plus, X } from 'lucide-vue-next';
import ColorInput from './ColorInput.vue';
import { useI18n } from '@/i18n';
import { normalizeFill } from '@/render/fill';
import type { Fill, FillCompat, Stop, LinearGradient, RadialGradient } from '@/types/protocol';

/**
 * M11-D：fill 编辑控件。承载 solid / linear / radial 三态联合编辑。
 *
 * <p><b>数据流：</b> {@code modelValue} 接 {@link FillCompat}（兼容 string）；emit 时统一输出
 * {@link Fill} object 形态。父组件不必担心字符串兼容路径。</p>
 *
 * <p><b>tab 切换语义：</b></p>
 * <ul>
 *   <li>{@code solid → linear}：自动生成 2 stops（当前色 + 黑色），angle = 0</li>
 *   <li>{@code solid → radial}：自动生成 2 stops（中心 当前色 → 边缘 黑色），中心 0.5,0.5，r=1</li>
 *   <li>{@code linear ↔ radial}：保留 stops + 默认参数</li>
 *   <li>{@code gradient → solid}：取首 stop 颜色</li>
 * </ul>
 */

const props = defineProps<{
    modelValue: FillCompat | null | undefined;
}>();

const emit = defineEmits<{
    (e: 'update:modelValue', value: Fill): void;
}>();

const { t } = useI18n();

const MAX_STOPS = 8;
const MIN_STOPS = 2;

const current = computed<Fill>(() => {
    const f = normalizeFill(props.modelValue);
    if (f) return f;
    return { type: 'solid', color: '#FFFFFF' };
});

type FillType = 'solid' | 'linear' | 'radial';

function changeType(next: FillType): void {
    const cur = current.value;
    if (cur.type === next) return;
    if (next === 'solid') {
        const color = cur.type === 'solid' ? cur.color : (cur.stops[0]?.color ?? '#FFFFFF');
        emit('update:modelValue', { type: 'solid', color });
    } else if (next === 'linear') {
        const stops = cur.type === 'solid'
            ? [{ position: 0, color: cur.color }, { position: 1, color: '#000000' }]
            : [...cur.stops];
        emit('update:modelValue', { type: 'linear', angle: 0, stops });
    } else {
        const stops = cur.type === 'solid'
            ? [{ position: 0, color: cur.color }, { position: 1, color: '#000000' }]
            : [...cur.stops];
        emit('update:modelValue', { type: 'radial', cx: 0.5, cy: 0.5, r: 1, stops });
    }
}

// ---------- Solid ----------

function onSolidColor(color: string): void {
    emit('update:modelValue', { type: 'solid', color });
}

// ---------- Linear ----------

const angleDraft = ref<number | null>(null);
watch(() => current.value, () => { angleDraft.value = null; });

const angleDisplay = computed(() => {
    if (angleDraft.value !== null) return angleDraft.value;
    return current.value.type === 'linear' ? Math.round(current.value.angle) : 0;
});

function onAngleInput(ev: Event): void {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    if (!Number.isFinite(v)) return;
    angleDraft.value = v;
    if (current.value.type !== 'linear') return;
    const next: LinearGradient = { ...current.value, angle: clampAngle(v) };
    emit('update:modelValue', next);
}

function onAngleChange(): void {
    angleDraft.value = null;
}

function clampAngle(v: number): number {
    let a = ((v % 360) + 360) % 360;
    if (a >= 360) a = 359.999;
    return a;
}

// ---------- Radial ----------

function patchRadial(partial: Partial<RadialGradient>): void {
    if (current.value.type !== 'radial') return;
    emit('update:modelValue', { ...current.value, ...partial });
}

function onUnitInput(field: 'cx' | 'cy' | 'r', ev: Event): void {
    const v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    if (field === 'r') patchRadial({ r: clampRange(v, 0.05, 2) });
    else patchRadial({ [field]: clampRange(v, 0, 1) } as Partial<RadialGradient>);
}

function clampRange(v: number, lo: number, hi: number): number {
    return v < lo ? lo : (v > hi ? hi : v);
}

// ---------- Stops（linear + radial 共用） ----------

const stops = computed<Stop[]>(() => {
    if (current.value.type === 'solid') return [];
    return current.value.stops;
});

function emitStops(next: Stop[]): void {
    const cur = current.value;
    if (cur.type === 'solid') return;
    emit('update:modelValue', { ...cur, stops: next });
}

function onStopColor(i: number, color: string): void {
    const next = stops.value.map((s, idx) => idx === i ? { ...s, color } : s);
    emitStops(next);
}

function onStopPosition(i: number, ev: Event): void {
    const v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    // clamp 到 [0, 1]，再保证单调（夹到前后邻居之间）
    let p = clampRange(v, 0, 1);
    const arr = stops.value;
    const prev = i > 0 ? arr[i - 1].position : 0;
    const nxt = i < arr.length - 1 ? arr[i + 1].position : 1;
    if (p < prev) p = prev;
    if (p > nxt) p = nxt;
    const next = arr.map((s, idx) => idx === i ? { ...s, position: p } : s);
    emitStops(next);
}

function addStop(): void {
    const arr = stops.value;
    if (arr.length >= MAX_STOPS) return;
    // 在尾部加：取 last 与 1 之间的中点；尾部已是 1 则取 last 与前面邻居中点
    const last = arr[arr.length - 1];
    const prev = arr.length >= 2 ? arr[arr.length - 2] : { position: 0 } as Stop;
    const pos = last.position >= 1 ? (prev.position + last.position) / 2 : (last.position + 1) / 2;
    const newStop: Stop = { position: pos, color: last.color };
    const next = [...arr, newStop].sort((a, b) => a.position - b.position);
    emitStops(next);
}

function removeStop(i: number): void {
    const arr = stops.value;
    if (arr.length <= MIN_STOPS) return;
    const next = arr.filter((_, idx) => idx !== i);
    emitStops(next);
}
</script>

<template>
  <div class="hc-fill-input flex flex-col gap-2">
    <!-- type tab：M24-B 修撞色 bug——原 active tab 用 bg-primary + text-primary-foreground
         在 mauve / blue accent 下文字辨识度低；改用 surface1 底 + foreground 文字 + accent ring。 -->
    <div class="flex items-center rounded-[var(--radius-sm)] border border-[color:var(--border)] overflow-hidden text-xs bg-[color:var(--secondary)]">
      <button
          v-for="opt in (['solid', 'linear', 'radial'] as const)"
          :key="opt"
          type="button"
          class="hc-btn flex-1 px-2 py-1 transition-colors capitalize"
          :class="current.type === opt
            ? 'bg-[color:var(--card)] text-[color:var(--foreground)] font-medium'
            : 'text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)] hover:text-[color:var(--foreground)]'"
          @click="changeType(opt)">
        {{ t.fill[opt] }}
      </button>
    </div>

    <!-- solid -->
    <template v-if="current.type === 'solid'">
      <ColorInput :model-value="current.color" @update:model-value="onSolidColor" />
    </template>

    <!-- linear -->
    <template v-else-if="current.type === 'linear'">
      <label class="flex items-center gap-1.5 text-xs">
        <span class="text-[color:var(--muted-foreground)] w-10 shrink-0">{{ t.fill.angle }}</span>
        <input type="range" min="0" max="359" :value="angleDisplay"
               class="flex-1" @input="onAngleInput" @change="onAngleChange" />
        <span class="font-mono w-9 text-right tabular-nums">{{ angleDisplay }}°</span>
      </label>

      <div class="flex flex-col gap-1.5 pt-1">
        <div class="flex items-center justify-between text-xs text-[color:var(--muted-foreground)] uppercase tracking-wider">
          <span>{{ t.fill.stops }}</span>
          <button
              type="button"
              class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
              :disabled="stops.length >= MAX_STOPS"
              @click="addStop"
              :title="t.fill.addStop">
            <Plus class="size-3" />
          </button>
        </div>
        <!-- P3-113：用 position+color 复合 key 替代数组下标——addStop 排序后下标 key
             会让 ColorInput 内部编辑态（popover open / hexDraft）串到相邻 stop。
             复合 key 按内容身份复用，重排后编辑态跟随正确的 stop。 -->
        <div v-for="(s, i) in stops" :key="`${s.position}:${s.color}`" class="flex items-center gap-1">
          <input type="range" min="0" max="1" step="0.01" :value="s.position"
                 class="flex-1" @input="(e) => onStopPosition(i, e)" />
          <ColorInput :model-value="s.color" @update:model-value="(c) => onStopColor(i, c)" />
          <button
              type="button"
              class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--destructive)] hover:text-[color:var(--destructive-foreground)] disabled:opacity-40 disabled:cursor-not-allowed"
              :disabled="stops.length <= MIN_STOPS"
              @click="removeStop(i)"
              :title="t.fill.removeStop">
            <X class="size-3" />
          </button>
        </div>
      </div>
    </template>

    <!-- radial -->
    <template v-else>
      <div class="grid grid-cols-3 gap-1.5 text-xs">
        <label class="flex flex-col gap-0.5">
          <span class="text-[color:var(--muted-foreground)]">{{ t.fill.cx }}</span>
          <input type="range" min="0" max="1" step="0.05" :value="current.cx"
                 @input="(e) => onUnitInput('cx', e)" />
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="text-[color:var(--muted-foreground)]">{{ t.fill.cy }}</span>
          <input type="range" min="0" max="1" step="0.05" :value="current.cy"
                 @input="(e) => onUnitInput('cy', e)" />
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="text-[color:var(--muted-foreground)]">{{ t.fill.radius }}</span>
          <input type="range" min="0.05" max="2" step="0.05" :value="current.r"
                 @input="(e) => onUnitInput('r', e)" />
        </label>
      </div>

      <div class="flex flex-col gap-1.5 pt-1">
        <div class="flex items-center justify-between text-xs text-[color:var(--muted-foreground)] uppercase tracking-wider">
          <span>{{ t.fill.stops }}</span>
          <button
              type="button"
              class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
              :disabled="stops.length >= MAX_STOPS"
              @click="addStop"
              :title="t.fill.addStop">
            <Plus class="size-3" />
          </button>
        </div>
        <!-- P3-113：用 position+color 复合 key 替代数组下标——addStop 排序后下标 key
             会让 ColorInput 内部编辑态（popover open / hexDraft）串到相邻 stop。
             复合 key 按内容身份复用，重排后编辑态跟随正确的 stop。 -->
        <div v-for="(s, i) in stops" :key="`${s.position}:${s.color}`" class="flex items-center gap-1">
          <input type="range" min="0" max="1" step="0.01" :value="s.position"
                 class="flex-1" @input="(e) => onStopPosition(i, e)" />
          <ColorInput :model-value="s.color" @update:model-value="(c) => onStopColor(i, c)" />
          <button
              type="button"
              class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--destructive)] hover:text-[color:var(--destructive-foreground)] disabled:opacity-40 disabled:cursor-not-allowed"
              :disabled="stops.length <= MIN_STOPS"
              @click="removeStop(i)"
              :title="t.fill.removeStop">
            <X class="size-3" />
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
