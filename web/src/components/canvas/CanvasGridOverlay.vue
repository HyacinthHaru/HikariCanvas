<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{ gridSize: number }>();

const gridOverlayStyle = computed(() => {
    const s = props.gridSize;
    if (!s) return {};
    return {
        backgroundImage:
            'linear-gradient(to right, color-mix(in srgb, currentColor 20%, transparent) 1px, transparent 1px),' +
            'linear-gradient(to bottom, color-mix(in srgb, currentColor 20%, transparent) 1px, transparent 1px)',
        backgroundSize: `${s}px ${s}px`,
        color: 'rgb(120, 120, 130)',
    };
});
</script>

<template>
  <div
    v-if="gridSize > 0"
    class="absolute inset-0 pointer-events-none"
    :style="gridOverlayStyle"
  />
</template>
