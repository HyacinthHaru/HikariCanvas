<script setup lang="ts">
/**
 * 时刻表 entry 添加 / 编辑对话框。
 *
 * <p>双用途：传 entry={null} 时作"添加"；传 entry={existing} 时作"编辑"。submit emit
 * 由父组件 ScheduleManagerModal 接管，调对应的 sendScheduleEntryAdd / Update。</p>
 *
 * <p>增 precision prop。precision="second" 时时间输入接受
 * HH:mm:ss + step="1" 让浏览器显秒；precision="minute" 时仅 HH:mm + step="60"。
 * 编辑现有 HH:mm:ss entry 时显示完整秒；编辑 HH:mm entry 自动补 ":00"。</p>
 */
import { computed, ref, watchEffect } from 'vue';
import { X, Train } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import type { ScheduleEntry, SchedulePrecision } from '@/types/schedule';

const props = defineProps<{
    entry: ScheduleEntry | null;        // null = 新增；非空 = 编辑
    precision: SchedulePrecision;
    submitting: boolean;
    errorMessage: string | null;
}>();

const emit = defineEmits<{
    (e: 'close'): void;
    (e: 'submit', payload: {
        id: number | null;
        departureTime: string;
        destination: string | null;
        sortOrder: number;
    }): void;
}>();

const { t } = useI18n();

const departureTime = ref('08:00');
const destination = ref('');
const sortOrder = ref(0);

// HH:mm 或 HH:mm:ss 24h 校验
const HHMM_PATTERN = /^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$/;
const timeValid = computed(() => HHMM_PATTERN.test(departureTime.value));
const formValid = computed(() => timeValid.value && !props.submitting);

/** 让 HTML5 <input type="time"> 显秒（步长 1s）；分钟模式 60s。 */
const timeStep = computed(() => props.precision === 'second' ? '1' : '60');

/** 提交前规范化：second 模式给 HH:mm 自动补 ":00"。 */
function normalizeForSubmit(raw: string): string {
    if (props.precision === 'second' && /^\d{2}:\d{2}$/.test(raw)) {
        return raw + ':00';
    }
    return raw;
}

watchEffect(() => {
    if (props.entry) {
        let initial = props.entry.departureTime;
        // 切到 minute 模式编辑 HH:mm:ss entry → 截断显示前 5 字符（HH:mm）
        if (props.precision === 'minute' && /^\d{2}:\d{2}:\d{2}$/.test(initial)) {
            initial = initial.substring(0, 5);
        }
        // 切到 second 模式编辑 HH:mm entry → 补 :00（让浏览器显秒）
        if (props.precision === 'second' && /^\d{2}:\d{2}$/.test(initial)) {
            initial = initial + ':00';
        }
        departureTime.value = initial;
        destination.value = props.entry.destination ?? '';
        sortOrder.value = props.entry.sortOrder;
    } else {
        departureTime.value = props.precision === 'second' ? '08:00:00' : '08:00';
        destination.value = '';
        sortOrder.value = 0;
    }
});

function onSubmit(): void {
    if (!formValid.value) return;
    const trimmedDest = destination.value.trim();
    emit('submit', {
        id: props.entry?.id ?? null,
        departureTime: normalizeForSubmit(departureTime.value),
        destination: trimmedDest.length === 0 ? null : trimmedDest,
        sortOrder: sortOrder.value,
    });
}
</script>

<template>
  <div class="fixed inset-0 z-[60] bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="emit('close')">
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md max-w-md w-full border border-[color:var(--border)] flex flex-col">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Train class="size-4 text-[color:var(--ctp-blue)]" />
        <h2 class="text-sm font-semibold">{{ t.schedule.entryDialogTitle }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]"
                @click="emit('close')" :disabled="submitting">
          <X class="size-4" />
        </button>
      </header>

      <form class="p-4 space-y-3 text-xs" @submit.prevent="onSubmit">
        <label class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">
            {{ t.schedule.entryDepartureLabel }}
            <span class="ml-1 font-mono opacity-60">
              ({{ precision === 'second' ? 'HH:mm:ss' : 'HH:mm' }})
            </span>
          </span>
          <input type="time"
                 class="hc-input mt-0.5 font-mono"
                 v-model="departureTime"
                 :step="timeStep" />
          <span v-if="!timeValid"
                class="text-xs text-[color:var(--destructive)] mt-0.5 block">
            {{ t.schedule.errorBadTimeFormat }}
          </span>
        </label>

        <label class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">
            {{ t.schedule.entryDestinationLabel }}
          </span>
          <input type="text"
                 class="hc-input mt-0.5"
                 maxlength="64"
                 v-model="destination" />
        </label>

        <label class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">
            {{ t.schedule.entrySortOrderLabel }}
          </span>
          <input type="number"
                 class="hc-input mt-0.5 w-24 font-mono"
                 v-model.number="sortOrder"
                 min="0"
                 max="999" />
        </label>

        <div v-if="errorMessage"
             class="px-2 py-1 rounded bg-[color:var(--destructive)]/10 text-[color:var(--destructive)] text-xs">
          {{ errorMessage }}
        </div>

        <div class="flex items-center gap-2 justify-end pt-2">
          <button type="button"
                  class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
                  @click="emit('close')"
                  :disabled="submitting">
            {{ t.schedule.entryCancel }}
          </button>
          <button type="submit"
                  class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed"
                  :disabled="!formValid">
            {{ t.schedule.entrySubmit }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
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
