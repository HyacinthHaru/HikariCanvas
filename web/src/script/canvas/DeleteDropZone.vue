<script setup lang="ts">
/**
 * 0.7.1「拖到删除区删积木」垃圾桶浮层。
 *
 * <p>仅在拖<b>动作积木</b>时显示（{@code active}）；悬停其上时高亮红（{@code hot}）；松手在区内 →
 * {@link useBlockDrag} 的 pointerup 短路调 {@code edit.removeAction} 删该积木。帽子（触发器）/ palette
 * 拖出新块 / 移堆都<b>不</b>显示删除区（规则删除有头部现成入口；拖新块松手在区内只是丢弃）。</p>
 *
 * <p><b>定位</b>：作为 BlockCanvas viewport 的直接子元素（{@code position:absolute}，<b>不在</b>
 * {@code .hc-block-world} 内 → 不随缩放/平移），底部居中。尺寸 / 位置与 {@code computeDeleteZoneRect}
 * 在 useBlockDrag 里的几何<b>同源对齐</b>（同一 DELETE_ZONE_W / DELETE_ZONE_H / DELETE_ZONE_BOTTOM），
 * 故"看到的红框"== "命中的判定区"。{@code pointer-events:none}——它只是视觉指示，命中由 composable
 * 用 client 坐标算，不靠浮层自身的 hit-test（拖动中 pointer 被 capture，事件不会派到它）。</p>
 */
import { computed } from 'vue';
import { Trash2 } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import { DELETE_ZONE_W, DELETE_ZONE_H, DELETE_ZONE_BOTTOM } from './useBlockDrag';

const props = defineProps<{
    /** 是否显示（拖动作积木时 true）。 */
    active: boolean;
    /** 指针是否悬停其上（高亮红）。 */
    hot: boolean;
}>();

const { t } = useI18n();

/** 浮层定位/尺寸：用 composable 同款常量，保证"红框"== 命中区。 */
const zoneStyle = computed(() => ({
    width: `${DELETE_ZONE_W}px`,
    height: `${DELETE_ZONE_H}px`,
    bottom: `${DELETE_ZONE_BOTTOM}px`,
}));

/** hot 时文案切到"松手删除"，更明确。 */
const label = computed(() =>
    props.hot ? t.value.script.deleteZone.release : t.value.script.deleteZone.hint,
);
</script>

<template>
  <div
    v-if="active"
    class="hc-delete-zone"
    :class="hot ? 'hc-delete-zone-hot' : ''"
    :style="zoneStyle"
    aria-hidden="true"
  >
    <Trash2 class="hc-delete-zone-icon" :class="hot ? 'size-6' : 'size-5'" />
    <span class="hc-delete-zone-label">{{ label }}</span>
  </div>
</template>

<style scoped>
/* viewport 内 absolute 浮层（不在 .hc-block-world，故不随缩放/平移）；底部居中。 */
.hc-delete-zone {
    position: absolute;
    left: 50%;
    transform: translateX(-50%);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 0.25rem;
    /* 只作视觉指示，命中由 composable 用 client 坐标算（拖动 pointer 已 capture）。 */
    pointer-events: none;
    border-radius: 8px;
    border: 2px dashed color-mix(in srgb, var(--destructive) 55%, transparent);
    background: color-mix(in srgb, var(--destructive) 10%, var(--card));
    color: var(--destructive);
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.22);
    z-index: 8;
    transition: background-color 0.1s ease, border-color 0.1s ease, transform 0.1s ease, color 0.1s ease;
}
/* 悬停其上：实线红 + 实底 + 轻微放大，明确"松手就删"。 */
.hc-delete-zone-hot {
    border-style: solid;
    border-color: var(--destructive);
    background: color-mix(in srgb, var(--destructive) 22%, var(--card));
    color: var(--destructive-foreground);
    transform: translateX(-50%) scale(1.05);
    box-shadow: 0 6px 18px color-mix(in srgb, var(--destructive) 40%, transparent);
}
.hc-delete-zone-icon {
    color: inherit;
    transition: width 0.1s ease, height 0.1s ease;
}
.hc-delete-zone-label {
    font-size: 0.75rem;
    font-weight: 600;
    white-space: nowrap;
}
</style>
