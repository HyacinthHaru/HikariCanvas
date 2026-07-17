// 笔刷参数 Pinia store。localStorage 持久（跨 session 保留用户设置）。
//
// 字段对应 BrushController.BrushProps；BrushPanel 组件读写此 store。

import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import type { Fill } from '@/types/protocol';

const STORAGE_KEY = 'hikari-canvas:brush';

interface Persisted {
    size: number;
    fill: Fill;
    opacity: number;
    pressureSize: boolean;
    pressureOpacity: boolean;
    smoothing: number;
    dither: boolean;
}

const DEFAULTS: Persisted = {
    size: 6,
    fill: { type: 'solid', color: '#000000' },
    opacity: 1,
    pressureSize: true,
    pressureOpacity: false,
    smoothing: 0.5,
    dither: false,
};

function load(): Persisted {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (!raw) return { ...DEFAULTS };
        const parsed = JSON.parse(raw) as Partial<Persisted>;
        return {
            size: typeof parsed.size === 'number' && parsed.size >= 1 && parsed.size <= 64
                ? parsed.size : DEFAULTS.size,
            fill: parsed.fill && typeof parsed.fill === 'object' ? parsed.fill : DEFAULTS.fill,
            opacity: typeof parsed.opacity === 'number' && parsed.opacity >= 0 && parsed.opacity <= 1
                ? parsed.opacity : DEFAULTS.opacity,
            pressureSize: typeof parsed.pressureSize === 'boolean'
                ? parsed.pressureSize : DEFAULTS.pressureSize,
            pressureOpacity: typeof parsed.pressureOpacity === 'boolean'
                ? parsed.pressureOpacity : DEFAULTS.pressureOpacity,
            smoothing: typeof parsed.smoothing === 'number' && parsed.smoothing >= 0 && parsed.smoothing <= 1
                ? parsed.smoothing : DEFAULTS.smoothing,
            dither: typeof parsed.dither === 'boolean' ? parsed.dither : DEFAULTS.dither,
        };
    } catch {
        return { ...DEFAULTS };
    }
}

export const useBrushStore = defineStore('brush', () => {
    const initial = load();
    const size = ref(initial.size);
    const fill = ref<Fill>(initial.fill);
    const opacity = ref(initial.opacity);
    const pressureSize = ref(initial.pressureSize);
    const pressureOpacity = ref(initial.pressureOpacity);
    const smoothing = ref(initial.smoothing);
    const dither = ref(initial.dither);

    /** 当前完整 props 快照（BrushController.pointerDown 用） */
    const snapshot = computed(() => ({
        size: size.value,
        fill: fill.value,
        opacity: opacity.value,
        pressureSize: pressureSize.value,
        pressureOpacity: pressureOpacity.value,
        smoothing: smoothing.value,
        dither: dither.value,
    }));

    // 任何字段变更 → 持久化（轻量字段无需 debounce）
    watch([size, fill, opacity, pressureSize, pressureOpacity, smoothing, dither], () => {
        try {
            const payload: Persisted = {
                size: size.value,
                fill: fill.value,
                opacity: opacity.value,
                pressureSize: pressureSize.value,
                pressureOpacity: pressureOpacity.value,
                smoothing: smoothing.value,
                dither: dither.value,
            };
            localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
        } catch { /* private mode / quota */ }
    }, { deep: true });

    return {
        size, fill, opacity, pressureSize, pressureOpacity, smoothing, dither,
        snapshot,
    };
});
