import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

const THEME_KEY = 'hikari-canvas:theme';
const LOCALE_KEY = 'hikari-canvas:locale';
const TOOL_KEY = 'hikari-canvas:active-tool';

export type Theme = 'dark' | 'light';
export type Locale = 'zh' | 'en';
/**
 * 当前激活工具。
 * - {@code 'select'}：默认。点击元素 = 选中 + 显示 transformer（resize/rotate 锚点）。双击文本进 inline edit。
 * - {@code 'move'}：PS 移动工具风格。点击元素 = 选中 + 立即可拖；transformer 不显示（避免大元素时锚点遮挡）；双击仍允许进 edit。
 */
export type ActiveTool = 'select' | 'move';

/**
 * UI 本地偏好：主题 / 侧边折叠 / 选中 / 缩放 / 底部日志抽屉。
 * 仅前端状态，不与 WS 协议交互。
 */
export const useUiStore = defineStore('ui', () => {
    const theme = ref<Theme>(loadTheme());
    const locale = ref<Locale>(loadLocale());
    const activeTool = ref<ActiveTool>(loadTool());
    const leftCollapsed = ref(false);
    const rightCollapsed = ref(false);
    const logDrawerOpen = ref(false);

    /** 选中元素 id；null = 无选中。 */
    const selectedElementId = ref<string | null>(null);

    /** 画布缩放系数（0.25 .. 4）。 */
    const zoom = ref(1);

    watch(activeTool, (v) => {
        try { localStorage.setItem(TOOL_KEY, v); } catch { /* ignore */ }
    });

    // 初始应用 theme 到 <html>
    applyThemeToDom(theme.value);

    watch(theme, (v) => {
        applyThemeToDom(v);
        try { localStorage.setItem(THEME_KEY, v); } catch { /* localStorage may fail in private mode */ }
    });

    watch(locale, (v) => {
        try { localStorage.setItem(LOCALE_KEY, v); } catch { /* ignore */ }
        document.documentElement.lang = v === 'zh' ? 'zh-CN' : 'en';
    });
    document.documentElement.lang = locale.value === 'zh' ? 'zh-CN' : 'en';

    function toggleTheme() {
        theme.value = theme.value === 'dark' ? 'light' : 'dark';
    }

    function toggleLocale() {
        locale.value = locale.value === 'zh' ? 'en' : 'zh';
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

    function setTool(tool: ActiveTool) {
        activeTool.value = tool;
    }

    return {
        theme, locale, activeTool, leftCollapsed, rightCollapsed, logDrawerOpen,
        selectedElementId, zoom,
        toggleTheme, toggleLocale, toggleLeft, toggleRight, toggleLogDrawer,
        setZoom, zoomIn, zoomOut, zoomReset,
        selectElement, setTool,
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

function loadLocale(): Locale {
    try {
        const v = localStorage.getItem(LOCALE_KEY);
        if (v === 'zh' || v === 'en') return v;
    } catch { /* ignore */ }
    // 默认按 navigator.language 的首个语言段：zh-* → zh；其余 → en
    const lang = navigator.language?.toLowerCase() ?? '';
    return lang.startsWith('zh') ? 'zh' : 'en';
}

function applyThemeToDom(theme: Theme) {
    document.documentElement.classList.toggle('dark', theme === 'dark');
}

function loadTool(): ActiveTool {
    try {
        const v = localStorage.getItem(TOOL_KEY);
        if (v === 'select' || v === 'move') return v;
    } catch { /* ignore */ }
    return 'select';
}
