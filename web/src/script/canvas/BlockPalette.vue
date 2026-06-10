<script setup lang="ts">
/**
 * 0.7.0-P4-D2：积木库侧栏（决策 K-UI 配色 + palette 拖出新块）。
 *
 * <p>按 category 分组列出<b>可拖出的 9 个动作积木</b>（含 if）；每项 = 色点 + label，
 * pointerdown 触发 {@code emit('paletteDown', kind, event)} → 上层（ScriptEditorOverlay /
 * BlockCanvas 持有的单一 useBlockDrag）调 {@code startPaletteDrag}。</p>
 *
 * <p><b>触发器不在 palette</b>（决策：每条规则一个帽子，帽子从「新建规则」来，不从 palette 拖）。
 * lock 态（{@code project.isLocked}）→ 全部项禁拖（{@code pointer-events:none} + 视觉灰显），
 * 与 RightPanel lock 守卫同款（K-UI-12）。</p>
 *
 * <p>分组顺序：动作（blue）→ 时间轴（mauve playTimeline）→ 控制（green if）→ 危险（red
 * runCommand）。同 category 内按 ACTION_DEFS 声明顺序。配色读 BlockDef.colorVar（Catppuccin
 * token），与画布上块同色，拖出去"颜色不变"心智一致。</p>
 */
import { computed } from 'vue';
import { ACTION_DEFS, type BlockDef, type BlockCategory } from '../model/blockDefs';
import { useProjectStore } from '@/stores/project';
import { useI18n } from '@/i18n';
import { resolveLabelKey } from './labelKey';

const emit = defineEmits<{
    /** palette 项 pointerdown：拖出 kind 对应的新块。 */
    (e: 'paletteDown', kind: string, ev: PointerEvent): void;
}>();

const project = useProjectStore();
const { t } = useI18n();

/** lock 态：palette 禁拖（K-UI-12）。 */
const locked = computed(() => project.isLocked);

/**
 * palette 分组：category → 该组的积木定义列表，按固定展示顺序。
 * 只列动作（action / timeline / control / danger）——trigger 不在 palette。
 */
const PALETTE_GROUP_ORDER: BlockCategory[] = ['action', 'timeline', 'control', 'danger'];

/** 每个 category 的标题 i18n key。 */
const GROUP_LABEL_KEY: Record<BlockCategory, string> = {
    trigger: 'script.paletteGroup.trigger',
    action: 'script.paletteGroup.action',
    timeline: 'script.paletteGroup.timeline',
    control: 'script.paletteGroup.control',
    danger: 'script.paletteGroup.danger',
};

interface PaletteGroup {
    category: BlockCategory;
    titleKey: string;
    defs: BlockDef[];
}

/** 按 PALETTE_GROUP_ORDER 把 ACTION_DEFS 分组（空组不显示）。 */
const groups = computed<PaletteGroup[]>(() => {
    const all = Object.values(ACTION_DEFS);
    const result: PaletteGroup[] = [];
    for (const cat of PALETTE_GROUP_ORDER) {
        const defs = all.filter((d) => d.category === cat);
        if (defs.length === 0) continue;
        result.push({ category: cat, titleKey: GROUP_LABEL_KEY[cat], defs });
    }
    return result;
});

function blockLabel(def: BlockDef): string {
    return resolveLabelKey(t.value, def.labelKey);
}
function groupTitle(g: PaletteGroup): string {
    return resolveLabelKey(t.value, g.titleKey);
}

function onItemPointerDown(kind: string, e: PointerEvent): void {
    if (locked.value) return;
    // 只接管主键（左键 / 触摸 / 笔）；中键 / 右键不拖。
    if (e.button !== 0) return;
    emit('paletteDown', kind, e);
}
</script>

<template>
  <div class="hc-palette" :class="locked ? 'hc-palette-locked' : ''">
    <div v-for="g in groups" :key="g.category" class="hc-palette-group">
      <div class="hc-palette-group-title">{{ groupTitle(g) }}</div>
      <div class="hc-palette-items">
        <div
          v-for="def in g.defs"
          :key="def.kind"
          class="hc-palette-item"
          :data-block-kind="def.kind"
          :style="{
            borderLeftColor: `var(${def.colorVar})`,
            background: `color-mix(in srgb, var(${def.colorVar}) 12%, var(--card))`,
          }"
          :title="locked ? t.script.lockedHint : blockLabel(def)"
          @pointerdown="onItemPointerDown(def.kind, $event)"
        >
          <span class="hc-palette-dot" :style="{ background: `var(${def.colorVar})` }" />
          <span class="hc-palette-label">{{ blockLabel(def) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-palette {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
}
.hc-palette-locked {
    /* lock：整库禁拖 + 灰显（与 RightPanel lock 守卫观感一致） */
    opacity: 0.55;
    pointer-events: none;
}
.hc-palette-group {
    display: flex;
    flex-direction: column;
}
.hc-palette-group-title {
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted-foreground);
    padding: 0 0.25rem 0.25rem;
}
.hc-palette-items {
    display: flex;
    flex-direction: column;
    gap: 4px;
}
.hc-palette-item {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.3125rem 0.5rem;
    border-left: 3px solid var(--border);
    border-radius: var(--radius-sm);
    cursor: grab;
    font-size: 0.8125rem;
    user-select: none;
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}
.hc-palette-item:active {
    cursor: grabbing;
}
.hc-palette-item:hover {
    filter: brightness(1.04);
}
.hc-palette-dot {
    width: 8px;
    height: 8px;
    border-radius: 2px;
    flex-shrink: 0;
}
.hc-palette-label {
    color: var(--foreground);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
</style>
