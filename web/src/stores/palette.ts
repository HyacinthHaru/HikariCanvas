import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { useProjectStore } from './project';
import type { Element } from '@/types/protocol';

const RECENT_KEY = 'hikari-canvas:recent-colors';
const MAX_RECENT = 20;

/**
 * M10 调色板状态：
 * - {@code recent}：最近使用色（localStorage 持久化，跨 session）
 * - {@code projectColors}：从 ProjectState 派生（扫所有 layer.elements + canvas.background）
 *
 * 默认色板见 {@code @/config/palettes}，永久内置。
 *
 * <p>颜色 hex 不区分大小写比较，但存储统一 toUpperCase；alpha = FF 时存 6 位 hex。</p>
 */
export const usePaletteStore = defineStore('palette', () => {
    const recent = ref<string[]>(loadRecent());

    function addRecent(color: string): void {
        if (!color) return;
        const c = color.toUpperCase();
        const filtered = recent.value.filter((x) => x.toUpperCase() !== c);
        recent.value = [c, ...filtered].slice(0, MAX_RECENT);
        try {
            localStorage.setItem(RECENT_KEY, JSON.stringify(recent.value));
        } catch { /* localStorage may fail in private mode */ }
    }

    const projectColors = computed<string[]>(() => {
        const project = useProjectStore();
        if (!project.state) return [];
        const set = new Set<string>();
        for (const layer of project.state.layers) {
            for (const el of layer.elements) {
                collectElementColors(el, set);
            }
        }
        const bg = project.state.canvas.background;
        if (bg) set.add(bg.toUpperCase());
        return Array.from(set);
    });

    return { recent, addRecent, projectColors };
});

function loadRecent(): string[] {
    try {
        const raw = localStorage.getItem(RECENT_KEY);
        if (!raw) return [];
        const arr = JSON.parse(raw);
        if (!Array.isArray(arr)) return [];
        return arr
            .filter((c): c is string => typeof c === 'string')
            .slice(0, MAX_RECENT);
    } catch {
        return [];
    }
}

function collectElementColors(el: Element, set: Set<string>): void {
    const add = (c: string | undefined | null) => {
        if (c) set.add(c.toUpperCase());
    };
    // 共通字段（M8/M9 各 element 类型）
    if ('color' in el) add((el as { color?: string }).color);
    if ('fill' in el) add((el as { fill?: string }).fill);
    if ('tint' in el) add((el as { tint?: string }).tint);
    const stroke = (el as { stroke?: { color?: string } }).stroke;
    if (stroke?.color) add(stroke.color);
    // text effects
    const effects = (el as { effects?: { stroke?: { color?: string }; shadow?: { color?: string }; glow?: { color?: string } } }).effects;
    if (effects) {
        if (effects.stroke?.color) add(effects.stroke.color);
        if (effects.shadow?.color) add(effects.shadow.color);
        if (effects.glow?.color) add(effects.glow.color);
    }
}
