<script setup lang="ts">
/**
 * 对齐 / 分布工具栏。
 *
 * <p>仅在多选（selectedIds.size ≥ 2）+ 非锁定 wall 时挂载；显示在 CanvasView 顶部居中。
 * 按下按钮 → 算出新 (x, y) → 乐观本地 mutate + 批量发 element.update WS op（每个一条）。</p>
 *
 * <p><b>不与现有 element.transform op 冲突</b>：align/distribute 只改 x / y，
 * 走 element.update 更轻量（element.transform 期望 x/y/w/h/rotation 五字段）。</p>
 */
import { computed } from 'vue';
import {
    AlignStartHorizontal, AlignCenterHorizontal, AlignEndHorizontal,
    AlignStartVertical, AlignCenterVertical, AlignEndVertical,
    AlignHorizontalDistributeCenter, AlignVerticalDistributeCenter,
} from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';
import {
    computeAlignDistributePatches,
    countLockedSkipped,
    type AlignAxis,
    type AlignMode,
} from '@/composables/useAlignDistribute';
import type { Element } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();

/** 当前所有选中的 element（从 active layer 内按 selectedIds 过滤）。 */
const selectedElements = computed<Element[]>(() => {
    const ids = ui.selectedIds;
    if (ids.size === 0) return [];
    const elements = project.state?.elements ?? [];
    return elements.filter((e) => ids.has(e.id));
});

/** 工具栏可见性：2+ 选中 + wall 未锁定。 */
const visible = computed(() => selectedElements.value.length >= 2 && !project.isLocked);

/** distribute 至少 3 元素（去 locked 后还得 ≥ 3）。 */
const distributeEnabled = computed(() => {
    const writable = selectedElements.value.filter((e) => !e.locked);
    return writable.length >= 3;
});

function applyAlignDistribute(axis: AlignAxis, mode: AlignMode): void {
    const els = selectedElements.value;
    if (els.length < 2) return;
    const patches = computeAlignDistributePatches(els, axis, mode);
    if (patches.length === 0) return;

    // 乐观本地 mutate + 逐个 element.update 上行。批量 ack 数量不大（最常用 ≤ 10 选中）。
    for (const p of patches) {
        const el = project.elementById(p.id);
        if (!el) continue;
        const patch: Record<string, number> = {};
        if (p.x !== undefined) {
            (el as unknown as Record<string, number>).x = p.x;
            patch.x = p.x;
        }
        if (p.y !== undefined) {
            (el as unknown as Record<string, number>).y = p.y;
            patch.y = p.y;
        }
        if (Object.keys(patch).length === 0) continue;
        ws.send('element.update', { elementId: p.id, patch });
    }

    // UX：如果跳过了 locked，提示一下（走 network store lastOpError 通道复用浮动 banner？
    // 这里走 console log + 后续可挂 toast；保持轻量）
    const skipped = countLockedSkipped(els);
    if (skipped > 0) {
        net.pushLog('meta', t.value.alignDistribute.lockedSkipped(skipped));
    }
}
</script>

<template>
  <Transition name="hc-fade">
    <div
      v-if="visible"
      class="hc-align-bar"
      :title="t.alignDistribute.barTitle(selectedElements.length)"
    >
      <!-- 水平对齐 3 个 -->
      <Tooltip :text="t.alignDistribute.alignLeft">
        <button class="hc-align-btn" @click="applyAlignDistribute('x', 'start')">
          <AlignStartVertical class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.alignDistribute.alignCenterH">
        <button class="hc-align-btn" @click="applyAlignDistribute('x', 'center')">
          <AlignCenterVertical class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.alignDistribute.alignRight">
        <button class="hc-align-btn" @click="applyAlignDistribute('x', 'end')">
          <AlignEndVertical class="size-4" />
        </button>
      </Tooltip>

      <div class="hc-align-divider"></div>

      <!-- 垂直对齐 3 个 -->
      <Tooltip :text="t.alignDistribute.alignTop">
        <button class="hc-align-btn" @click="applyAlignDistribute('y', 'start')">
          <AlignStartHorizontal class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.alignDistribute.alignCenterV">
        <button class="hc-align-btn" @click="applyAlignDistribute('y', 'center')">
          <AlignCenterHorizontal class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.alignDistribute.alignBottom">
        <button class="hc-align-btn" @click="applyAlignDistribute('y', 'end')">
          <AlignEndHorizontal class="size-4" />
        </button>
      </Tooltip>

      <div class="hc-align-divider"></div>

      <!-- 分布 2 个 -->
      <Tooltip :text="distributeEnabled ? t.alignDistribute.distributeH : t.alignDistribute.distributeDisabled">
        <button
          class="hc-align-btn"
          :disabled="!distributeEnabled"
          @click="applyAlignDistribute('x', 'distribute-equal')"
        >
          <AlignHorizontalDistributeCenter class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="distributeEnabled ? t.alignDistribute.distributeV : t.alignDistribute.distributeDisabled">
        <button
          class="hc-align-btn"
          :disabled="!distributeEnabled"
          @click="applyAlignDistribute('y', 'distribute-equal')"
        >
          <AlignVerticalDistributeCenter class="size-4" />
        </button>
      </Tooltip>
    </div>
  </Transition>
</template>

<style scoped>
.hc-align-bar {
    position: absolute;
    top: 12px;
    left: 50%;
    transform: translateX(-50%);
    z-index: 30;
    display: flex;
    align-items: center;
    gap: 2px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 4px;
    box-shadow: 0 2px 8px color-mix(in srgb, var(--ctp-crust) 30%, transparent);
    color: var(--foreground);
}
.hc-align-btn {
    padding: 6px;
    border-radius: var(--radius-sm);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: background 80ms ease;
}
.hc-align-btn:not(:disabled):hover {
    background: var(--accent);
}
.hc-align-btn:disabled {
    opacity: 0.35;
    cursor: not-allowed;
}
.hc-align-divider {
    width: 1px;
    height: 18px;
    background: var(--border);
    margin: 0 2px;
}

/* Transition：进出场轻渐显 */
.hc-fade-enter-active,
.hc-fade-leave-active {
    transition: opacity 120ms ease, transform 120ms ease;
}
.hc-fade-enter-from,
.hc-fade-leave-to {
    opacity: 0;
    transform: translate(-50%, -4px);
}
</style>
