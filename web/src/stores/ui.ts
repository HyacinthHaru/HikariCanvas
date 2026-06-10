import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import { useThemeStore } from './theme';

const LOCALE_KEY = 'hikari-canvas:locale';
const TOOL_KEY = 'hikari-canvas:active-tool';
const SNAP_KEY = 'hikari-canvas:snap';

interface SnapPrefs {
    enabled: boolean;
    toGrid: boolean;
    toCanvas: boolean;
    toElement: boolean;
    toDistribute: boolean;
    threshold: number;
}
const SNAP_DEFAULT: SnapPrefs = { enabled: true, toGrid: false, toCanvas: true, toElement: true, toDistribute: true, threshold: 8 };

export type Theme = 'dark' | 'light';
export type Locale = 'zh' | 'en';
/**
 * 当前激活工具。
 * - {@code 'select'}：默认。点击元素 = 选中 + 显示 transformer（resize/rotate 锚点）。双击文本进 inline edit。
 * - {@code 'move'}：PS 移动工具风格。点击元素 = 选中 + 立即可拖；transformer 不显示（避免大元素时锚点遮挡）；双击仍允许进 edit。
 * - {@code 'hand'}（M17 F4）：Figma 风格手型工具。任何位置 mousedown+drag = pan 画布；按住 Space 临时切到此工具。
 * - {@code 'line' / 'arrow' / 'circle' / 'star'}（M9-D）：进入"待绘制"状态，cursor = crosshair；
 *   M9-E 接 canvas mousedown/move/up 实现 drag-to-create。M9-D 期间画布上 drag 暂无效果。
 */
export type ActiveTool = 'select' | 'move' | 'hand' | 'line' | 'arrow' | 'circle' | 'star' | 'brush' | 'paint-bucket';

/** 是否是绘制工具（拖出新元素 / 笔触）。select/move/hand/paint-bucket 之外均为绘制工具。
 *  paint-bucket 是 click 工具（M18 Live Paint）：单击空白处填充 gap、单击元素内部 recolor（P4 实装），
 *  不走 drag-to-create 路径。 */
export function isDrawTool(tool: ActiveTool): boolean {
    return tool !== 'select' && tool !== 'move' && tool !== 'hand' && tool !== 'paint-bucket';
}

/** 是否是笔刷工具（M12：走 PointerEvent + BrushController 而非 drag-to-create）。 */
export function isBrushTool(tool: ActiveTool): boolean {
    return tool === 'brush';
}

/**
 * UI 本地偏好：主题 / 侧边折叠 / 选中 / 缩放 / 底部日志抽屉。
 * 仅前端状态，不与 WS 协议交互。
 *
 * <p>M8-F：选中模型升级为多选。{@link #selectedIds} 是单一真相；{@link #selectedElementId}
 * 仍保留为 computed（size==1 时返该 id），让 M5/M7 期间的单选代码可以零修改继续工作。</p>
 */
export const useUiStore = defineStore('ui', () => {
    const themeStore = useThemeStore();
    /**
     * M24-B：theme 已迁到 {@link useThemeStore}（preset flavor + accent + radius）。
     * 这里保留 `theme` computed 兼容旧组件——返 `'dark'`/`'light'` 二态：
     *   - Latte → `'light'`
     *   - Frappé / Macchiato → `'dark'`
     * 写入仍走 themeStore.toggleLightDark。
     */
    const theme = computed<Theme>(() => themeStore.flavor === 'latte' ? 'light' : 'dark');
    const locale = ref<Locale>(loadLocale());
    const activeTool = ref<ActiveTool>(loadTool());
    const leftCollapsed = ref(false);
    const rightCollapsed = ref(false);
    const logDrawerOpen = ref(false);
    const helpOpen = ref(false);
    /** M28-P2-G：变量管理面板（VariablePanel）开关。TopBar 按钮 / ESC 触发。 */
    const variablePanelOpen = ref(false);
    /** 0.4.0-P3-L：列车时刻表管理 modal 开关。TopBar Calendar 按钮触发。 */
    const scheduleManagerOpen = ref(false);
    /** 0.4.4：铁路网络管理 modal 开关。TopBar Train 按钮 → 二级菜单触发。 */
    const railNetworkOpen = ref(false);
    /** 0.7.0-P4：积木脚本编辑器全屏 overlay 开关。TopBar Puzzle 按钮触发。 */
    const scriptEditorOpen = ref(false);
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

    // ---------- M17 F3 智能对齐 ----------
    // localStorage 持久化，与 theme / locale 等用户偏好同级。shift 临时禁用走 CanvasView 的 bypass 钩子。
    const snapPrefs = loadSnap();
    const snapEnabled = ref(snapPrefs.enabled);
    const snapToGrid = ref(snapPrefs.toGrid);
    const snapToCanvas = ref(snapPrefs.toCanvas);
    const snapToElement = ref(snapPrefs.toElement);
    const snapToDistribute = ref(snapPrefs.toDistribute);
    const snapThreshold = ref(snapPrefs.threshold);
    watch([snapEnabled, snapToGrid, snapToCanvas, snapToElement, snapToDistribute, snapThreshold], () => {
        try {
            localStorage.setItem(SNAP_KEY, JSON.stringify({
                enabled: snapEnabled.value,
                toGrid: snapToGrid.value,
                toCanvas: snapToCanvas.value,
                toElement: snapToElement.value,
                toDistribute: snapToDistribute.value,
                threshold: Math.max(1, Math.min(64, snapThreshold.value)),
            } satisfies SnapPrefs));
        } catch { /* ignore */ }
    });

    watch(activeTool, (v) => {
        try { localStorage.setItem(TOOL_KEY, v); } catch { /* ignore */ }
    });

    // M24-B：theme DOM 应用已迁到 themeStore（applyFlavor/Accent/Radius）。
    // 此处不再单独写 .dark class，避免双写竞态。

    watch(locale, (v) => {
        try { localStorage.setItem(LOCALE_KEY, v); } catch { /* ignore */ }
        document.documentElement.lang = v === 'zh' ? 'zh-CN' : 'en';
    });
    document.documentElement.lang = locale.value === 'zh' ? 'zh-CN' : 'en';

    function toggleTheme() {
        // M24-B：委托给 themeStore。Latte ↔ Frappé 二态切；其他 flavor（macchiato）
        // 走 ThemeSwitcher 完整 UI。
        themeStore.toggleLightDark();
    }

    function toggleLocale() {
        locale.value = locale.value === 'zh' ? 'en' : 'zh';
    }

    function toggleLeft() { leftCollapsed.value = !leftCollapsed.value; }
    function toggleRight() { rightCollapsed.value = !rightCollapsed.value; }
    function toggleLogDrawer() { logDrawerOpen.value = !logDrawerOpen.value; }
    function toggleVariablePanel() { variablePanelOpen.value = !variablePanelOpen.value; }
    function closeVariablePanel() { variablePanelOpen.value = false; }
    function toggleScheduleManager() { scheduleManagerOpen.value = !scheduleManagerOpen.value; }
    function closeScheduleManager() { scheduleManagerOpen.value = false; }
    function toggleRailNetwork() { railNetworkOpen.value = !railNetworkOpen.value; }
    function closeRailNetwork() { railNetworkOpen.value = false; }
    function toggleScriptEditor() { scriptEditorOpen.value = !scriptEditorOpen.value; }
    function closeScriptEditor() { scriptEditorOpen.value = false; }

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

    /**
     * M16 P4.2：wall 切换时重置 wall-scoped 状态；用户偏好（theme / locale / activeTool / zoom /
     * 面板折叠态）保留，因为它们应跨 wall 持久。
     */
    function reset(): void {
        selectedIds.value = new Set();
        editingLayerId.value = null;
        logDrawerOpen.value = false;
        helpOpen.value = false;
        variablePanelOpen.value = false;
        scheduleManagerOpen.value = false;
        railNetworkOpen.value = false;
        scriptEditorOpen.value = false;
    }

    return {
        theme, locale, activeTool, leftCollapsed, rightCollapsed, logDrawerOpen, helpOpen,
        variablePanelOpen, scheduleManagerOpen, railNetworkOpen, scriptEditorOpen,
        selectedIds, selectedElementId, selectedCount, hasSelection,
        editingLayerId, zoom,
        snapEnabled, snapToGrid, snapToCanvas, snapToElement, snapToDistribute, snapThreshold,
        toggleTheme, toggleLocale, toggleLeft, toggleRight, toggleLogDrawer,
        toggleVariablePanel, closeVariablePanel,
        toggleScheduleManager, closeScheduleManager,
        toggleRailNetwork, closeRailNetwork,
        toggleScriptEditor, closeScriptEditor,
        setZoom, zoomIn, zoomOut, zoomReset,
        selectElement, toggleSelection, selectMany, addToSelection, clearSelection,
        isSelected,
        setTool, setEditingLayer,
        reset,
    };
});

function loadSnap(): SnapPrefs {
    try {
        const raw = localStorage.getItem(SNAP_KEY);
        if (!raw) return { ...SNAP_DEFAULT };
        const parsed = JSON.parse(raw) as Partial<SnapPrefs>;
        return {
            enabled: typeof parsed.enabled === 'boolean' ? parsed.enabled : SNAP_DEFAULT.enabled,
            toGrid: typeof parsed.toGrid === 'boolean' ? parsed.toGrid : SNAP_DEFAULT.toGrid,
            toCanvas: typeof parsed.toCanvas === 'boolean' ? parsed.toCanvas : SNAP_DEFAULT.toCanvas,
            toElement: typeof parsed.toElement === 'boolean' ? parsed.toElement : SNAP_DEFAULT.toElement,
            toDistribute: typeof parsed.toDistribute === 'boolean' ? parsed.toDistribute : SNAP_DEFAULT.toDistribute,
            threshold: typeof parsed.threshold === 'number' && parsed.threshold >= 1 && parsed.threshold <= 64
                ? parsed.threshold : SNAP_DEFAULT.threshold,
        };
    } catch { return { ...SNAP_DEFAULT }; }
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

function loadTool(): ActiveTool {
    const KNOWN: ActiveTool[] = ['select', 'move', 'hand', 'line', 'arrow', 'circle', 'star', 'brush', 'paint-bucket'];
    try {
        const v = localStorage.getItem(TOOL_KEY) as ActiveTool | null;
        if (v && KNOWN.includes(v)) return v;
    } catch { /* ignore */ }
    return 'select';
}
