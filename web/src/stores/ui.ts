import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

const THEME_KEY = 'hikari-canvas:theme';

export type Theme = 'dark' | 'light';

/**
 * UI 本地偏好：主题 / 侧边折叠 / 选中 / 缩放 / 底部日志抽屉。
 * 仅前端状态，不与 WS 协议交互。
 */
export const useUiStore = defineStore('ui', () => {
    const theme = ref<Theme>(loadTheme());
    const leftCollapsed = ref(false);
    const rightCollapsed = ref(false);
    const logDrawerOpen = ref(false);

    /** 选中元素 id；null = 无选中。 */
    const selectedElementId = ref<string | null>(null);

    /** 画布缩放系数（0.25 .. 4）。 */
    const zoom = ref(1);

    // 初始应用 theme 到 <html>
    applyThemeToDom(theme.value);

    watch(theme, (v) => {
        applyThemeToDom(v);
        try { localStorage.setItem(THEME_KEY, v); } catch { /* localStorage may fail in private mode */ }
    });

    function toggleTheme() {
        theme.value = theme.value === 'dark' ? 'light' : 'dark';
    }

    function toggleLeft() { leftCollapsed.value = !leftCollapsed.value; }
    function toggleRight() { rightCollapsed.value = !rightCollapsed.value; }
    function toggleLogDrawer() { logDrawerOpen.value = !logDrawerOpen.value; }

    function setZoom(z: number) {
        zoom.value = Math.max(0.25, Math.min(4, z));
    }
    function zoomIn() { setZoom(zoom.value * 1.25); }
    function zoomOut() { setZoom(zoom.value / 1.25); }
    function zoomReset() { setZoom(1); }

    function selectElement(id: string | null) {
        selectedElementId.value = id;
    }

    return {
        theme, leftCollapsed, rightCollapsed, logDrawerOpen,
        selectedElementId, zoom,
        toggleTheme, toggleLeft, toggleRight, toggleLogDrawer,
        setZoom, zoomIn, zoomOut, zoomReset,
        selectElement,
    };
});

function loadTheme(): Theme {
    try {
        const v = localStorage.getItem(THEME_KEY);
        if (v === 'light' || v === 'dark') return v;
    } catch { /* ignore */ }
    // 默认跟随系统，但兜底深色（符合"Photoshop 网页"期望）
    if (window.matchMedia?.('(prefers-color-scheme: light)').matches) return 'light';
    return 'dark';
}

function applyThemeToDom(theme: Theme) {
    document.documentElement.classList.toggle('dark', theme === 'dark');
}
