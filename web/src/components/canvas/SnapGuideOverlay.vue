<script setup lang="ts">
import { computed } from 'vue';
import { useUiStore } from '@/stores/ui';
import type { SnapHints } from '@/composables/useSnapManager';

/**
 * M17.4 F3 visualizer：在画布坐标系内画 snap 对齐线 + 间距均分标注。
 *
 * 父组件传入 hints / 画布尺寸；本组件渲染：
 *   - 红色虚线（snap axis）：竖向通线 / 横向通线
 *   - 绿色实线段 + 距离数字（distribute equalGap）
 *
 * 设计取舍：
 *   - strokeWidth 用 1/zoom，让线宽视觉上保持 1px 不随缩放变化
 *   - dash 模式也用 [4/zoom, 3/zoom] 保持视觉密度
 *   - fade-out 走 onDragEnd 立刻清 hints（父组件控制）；CSS fade 留 v1.x
 */
const props = defineProps<{
    hints: SnapHints | null;
    widthPx: number;
    heightPx: number;
}>();

const ui = useUiStore();

const zoom = computed(() => Math.max(0.0001, ui.zoom));
const sw = computed(() => 1 / zoom.value);
const dashAxis = computed(() => [4 / zoom.value, 3 / zoom.value]);

const SNAP_COLOR = '#ef4444';     // red-500
const GAP_COLOR = '#22c55e';      // green-500

interface LineConfig {
    points: number[];
    stroke: string;
    strokeWidth: number;
    dash?: number[];
    listening: false;
    lineCap?: string;
    perfectDrawEnabled?: boolean;
}

interface TextConfig {
    x: number;
    y: number;
    text: string;
    fontSize: number;
    fill: string;
    listening: false;
    align?: string;
    fontStyle?: string;
}

interface RectConfig {
    x: number;
    y: number;
    width: number;
    height: number;
    fill: string;
    cornerRadius: number;
    listening: false;
}

const vLines = computed<LineConfig[]>(() => {
    const h = props.hints;
    if (!h) return [];
    return h.activeXAxes.map((x) => ({
        points: [x, 0, x, props.heightPx],
        stroke: SNAP_COLOR,
        strokeWidth: sw.value,
        dash: dashAxis.value,
        listening: false as const,
        perfectDrawEnabled: false,
    }));
});

const hLines = computed<LineConfig[]>(() => {
    const h = props.hints;
    if (!h) return [];
    return h.activeYAxes.map((y) => ({
        points: [0, y, props.widthPx, y],
        stroke: SNAP_COLOR,
        strokeWidth: sw.value,
        dash: dashAxis.value,
        listening: false as const,
        perfectDrawEnabled: false,
    }));
});

interface GapMark {
    seg: LineConfig;
    tickStart: LineConfig;
    tickEnd: LineConfig;
    labelBg: RectConfig;
    label: TextConfig;
}

/** 把一段「A-B」的距离段画成：横线 + 两端短竖刻度 + 中间距离标签。 */
function buildHorizontalGapMark(x0: number, x1: number, y: number, distance: number): GapMark {
    const tickHalf = 4 / zoom.value;
    const labelText = `${Math.round(distance)}`;
    const labelFontSize = 10 / zoom.value;
    const labelPadX = 3 / zoom.value;
    const labelPadY = 1 / zoom.value;
    const charW = labelFontSize * 0.6;
    const labelW = labelText.length * charW + labelPadX * 2;
    const labelH = labelFontSize + labelPadY * 2;
    const cx = (x0 + x1) / 2;
    return {
        seg: {
            points: [x0, y, x1, y],
            stroke: GAP_COLOR,
            strokeWidth: sw.value,
            listening: false as const,
            perfectDrawEnabled: false,
        },
        tickStart: {
            points: [x0, y - tickHalf, x0, y + tickHalf],
            stroke: GAP_COLOR,
            strokeWidth: sw.value,
            listening: false as const,
            perfectDrawEnabled: false,
        },
        tickEnd: {
            points: [x1, y - tickHalf, x1, y + tickHalf],
            stroke: GAP_COLOR,
            strokeWidth: sw.value,
            listening: false as const,
            perfectDrawEnabled: false,
        },
        labelBg: {
            x: cx - labelW / 2,
            y: y - labelH - tickHalf,
            width: labelW,
            height: labelH,
            fill: GAP_COLOR,
            cornerRadius: 2 / zoom.value,
            listening: false as const,
        },
        label: {
            x: cx - labelW / 2 + labelPadX,
            y: y - labelH - tickHalf + labelPadY,
            text: labelText,
            fontSize: labelFontSize,
            fill: '#ffffff',
            listening: false as const,
            fontStyle: 'bold',
        },
    };
}

function buildVerticalGapMark(y0: number, y1: number, x: number, distance: number): GapMark {
    const tickHalf = 4 / zoom.value;
    const labelText = `${Math.round(distance)}`;
    const labelFontSize = 10 / zoom.value;
    const labelPadX = 3 / zoom.value;
    const labelPadY = 1 / zoom.value;
    const charW = labelFontSize * 0.6;
    const labelW = labelText.length * charW + labelPadX * 2;
    const labelH = labelFontSize + labelPadY * 2;
    const cy = (y0 + y1) / 2;
    return {
        seg: {
            points: [x, y0, x, y1],
            stroke: GAP_COLOR,
            strokeWidth: sw.value,
            listening: false as const,
            perfectDrawEnabled: false,
        },
        tickStart: {
            points: [x - tickHalf, y0, x + tickHalf, y0],
            stroke: GAP_COLOR,
            strokeWidth: sw.value,
            listening: false as const,
            perfectDrawEnabled: false,
        },
        tickEnd: {
            points: [x - tickHalf, y1, x + tickHalf, y1],
            stroke: GAP_COLOR,
            strokeWidth: sw.value,
            listening: false as const,
            perfectDrawEnabled: false,
        },
        labelBg: {
            x: x + tickHalf,
            y: cy - labelH / 2,
            width: labelW,
            height: labelH,
            fill: GAP_COLOR,
            cornerRadius: 2 / zoom.value,
            listening: false as const,
        },
        label: {
            x: x + tickHalf + labelPadX,
            y: cy - labelH / 2 + labelPadY,
            text: labelText,
            fontSize: labelFontSize,
            fill: '#ffffff',
            listening: false as const,
            fontStyle: 'bold',
        },
    };
}

const gapMarks = computed<GapMark[]>(() => {
    const h = props.hints;
    if (!h) return [];
    const out: GapMark[] = [];
    if (h.equalGapX) {
        const g = h.equalGapX;
        // 两段：A.right → B.left，B.right → C.left
        const d1 = g.bLeft - g.aRight;
        const d2 = g.cLeft - g.bRight;
        out.push(buildHorizontalGapMark(g.aRight, g.bLeft, g.yCenter, d1));
        out.push(buildHorizontalGapMark(g.bRight, g.cLeft, g.yCenter, d2));
    }
    if (h.equalGapY) {
        const g = h.equalGapY;
        const d1 = g.bTop - g.aBottom;
        const d2 = g.cTop - g.bBottom;
        out.push(buildVerticalGapMark(g.aBottom, g.bTop, g.xCenter, d1));
        out.push(buildVerticalGapMark(g.bBottom, g.cTop, g.xCenter, d2));
    }
    return out;
});

const hasAnything = computed(() => {
    return vLines.value.length > 0 || hLines.value.length > 0 || gapMarks.value.length > 0;
});
</script>

<template>
  <v-layer v-if="hasAnything" :listening="false">
    <v-line v-for="(cfg, i) in vLines" :key="`vx-${i}`" :config="cfg" />
    <v-line v-for="(cfg, i) in hLines" :key="`vy-${i}`" :config="cfg" />
    <template v-for="(mark, i) in gapMarks" :key="`gap-${i}`">
      <v-line :config="mark.seg" />
      <v-line :config="mark.tickStart" />
      <v-line :config="mark.tickEnd" />
      <v-rect :config="mark.labelBg" />
      <v-text :config="mark.label" />
    </template>
  </v-layer>
</template>
