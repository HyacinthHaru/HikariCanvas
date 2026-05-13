import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';

const THEME_KEY = 'hikari-canvas:theme';
const LOCALE_KEY = 'hikari-canvas:locale';
const TOOL_KEY = 'hikari-canvas:active-tool';

export type Theme = 'dark' | 'light';
export type Locale = 'zh' | 'en';
/**
 * 当前激活工具。
 * - {@code 'select'}：默认。点击元素 = 选中 + 显示 transformer（resize/rotate 锚点）。双击文本进 inline edit。
 * - {@code 'move'}：PS 移动工具风格。点击元素 = 选中 + 立即可拖；transformer 不显示（避免大元素时锚点遮挡）；双击仍允许进 edit。
 * - {@code 'line' / 'arrow' / 'circle' / 'star'}（M9-D）：进入"待绘制"状态，cursor = crosshair；
 *   M9-E 接 canvas mousedown/move/up 实现 drag-to-create。M9-D 期间画布上 drag 暂无效果。
 */
export type ActiveTool = 'select' | 'move' | 'line' | 'arrow' | 'circle' | 'star';

/** 是否是绘制工具（拖出新元素）。select/move 之外均为绘制工具。 */
export function isDrawTool(tool: ActiveTool): boolean {
    return tool !== 'select' && tool !== 'move';
}

/**
 * UI 本地偏好：主题 / 侧边折叠 / 选中 / 缩放 / 底部日志抽屉。
 * 仅前端状态，不与 WS 协议交互。
 *
 * <p>M8-F：选中模型升级为多选。{@link #selectedIds} 是单一真相；{@link #selectedElementId}
 * 仍保留为 computed（size==1 时返该 id），让 M5/M7 期间的单选代码可以零修改继续工作。</p>
 */
export const useUiStore = defineStore('ui', () => {
    const theme = ref<Theme>(loadTheme());
    const locale = ref<Locale>(loadLocale());
    const activeTool = ref<ActiveTool>(loadTool());
    const leftCollapsed = ref(false);
    const rightCollapsed = ref(false);
    const logDrawerOpen = ref(false);
    const helpOpen = ref(false);

    /** M8-F：所有当前选中的元素 id。size > 1 时为多选。 */
    const selectedIds = ref<Set<string>>(new Set());

    /**
     * 兼容视图：size==1 时返第一个 id，否则 null。
     * 旧组件 `ui.selectedElementId === el.id` 判等在多选时返 false（id ≠ null），但
     * 多选行为应改用 {@link isSelected} —— 高亮 / 拖动判断需切到新 API。
     */
    const selectedElementId = computed<string | null>(() => {
        if (selectedIds.value.size === 1) {
            return selectedIds.value.values().next().value as string;
        }
        return null;
    });

    /** 当前选中数量。0 = 无选中；>= 2 = 多选。 */
    const selectedCount = computed(() => selectedIds.value.size);

    /** 是否至少有 1 个选中。 */
    const hasSelection = computed(() => selectedIds.value.size > 0);

    /** M8-D：当前正在 inline 重命名的图层 id。LayerPanel 双击 layer name 设；保存或 ESC 清。 */
    const editingLayerId = ref<string | null>(null);

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

    // ---------- M8-F 选中操作 ----------

    /** 单选或清空。`null` = 清空所有选中。 */
    function selectElement(id: string | null): void {
        if (id === null) {
            if (selectedIds.value.size === 0) return;
            selectedIds.value = new Set();
        } else {
            if (selectedIds.value.size === 1 && selectedIds.value.has(id)) return;
            selectedIds.value = new Set([id]);
        }
    }

    /** Shift / Cmd+click 用：在已有选中里切换该 id。 */
    function toggleSelection(id: string): void {
        const next = new Set(selectedIds.value);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        selectedIds.value = next;
    }

    /** marquee 拖框完成 / Cmd+A 全选用：整组替换。 */
    function selectMany(ids: string[]): void {
        selectedIds.value = new Set(ids);
    }

    function addToSelection(id: string): void {
        if (selectedIds.value.has(id)) return;
        const next = new Set(selectedIds.value);
        next.add(id);
        selectedIds.value = next;
    }

    function clearSelection(): void {
        selectElement(null);
    }

    function isSelected(id: string): boolean {
        return selectedIds.value.has(id);
    }

    function setTool(tool: ActiveTool) {
        activeTool.value = tool;
    }

    function setEditingLayer(id: string | null) {
        editingLayerId.value = id;
    }

    return {
        theme, locale, activeTool, leftCollapsed, rightCollapsed, logDrawerOpen, helpOpen,
        selectedIds, selectedElementId, selectedCount, hasSelection,
        editingLayerId, zoom,
        toggleTheme, toggleLocale, toggleLeft, toggleRight, toggleLogDrawer,
        setZoom, zoomIn, zoomOut, zoomReset,
        selectElement, toggleSelection, selectMany, addToSelection, clearSelection,
        isSelected,
        setTool, setEditingLayer,
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
    const KNOWN: ActiveTool[] = ['select', 'move', 'line', 'arrow', 'circle', 'star'];
    try {
        const v = localStorage.getItem(TOOL_KEY) as ActiveTool | null;
        if (v && KNOWN.includes(v)) return v;
    } catch { /* ignore */ }
    return 'select';
}
