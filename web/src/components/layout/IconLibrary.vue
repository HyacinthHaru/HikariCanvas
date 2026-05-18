<script setup lang="ts">
/**
 * M26.3 IconLibrary：图标库侧边 panel。
 *
 * <p>触发：LeftTools 的 Shapes 按钮 toggle store.open；快捷键 I（详见
 * useCanvasShortcuts）。Panel 风格而非 activeTool——不参与 select/move/brush 工具切换，
 * 关闭时完全卸载（v-if）让 IntersectionObserver / fetch in-flight 不再做无用功。</p>
 *
 * <p>UI 分四段（自上而下）：
 * <ol>
 *   <li>头：标题 + 关闭按钮 + 搜索框（debounce 200ms）</li>
 *   <li>tabs：7 个分类（all / FA solid / FA regular / FA brands / user / favorites / recent）</li>
 *   <li>主体：grid 8 列，可见 icon 走 IntersectionObserver lazy {@code ensureLoaded}，
 *       占位 ? 直到 path d 就绪。无限滚动：触底 loadMore（offset += limit）。</li>
 *   <li>底：拖入提示</li>
 * </ol>
 *
 * <p><b>客户端列表 vs 服务端列表</b>：favorites / recent 走 store；用 ID 列表本地展开，
 * 不调 /api/icon/list。其余分类走后端 search + 分页。</p>
 *
 * <p><b>交互</b>：点击 = 在画布中心新建 IconElement（64×64）；拖动 = setData
 * application/x-hikari-icon，CanvasView 接 drop 解 + 创建。</p>
 *
 * <p><b>风格</b>：M24 Catppuccin + M3 扁平。所有颜色 / 圆角走 CSS token；不用
 * backdrop-blur / 阴影堆叠。</p>
 */

import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { X, Search, Loader2, Star, History, Image as ImageIcon } from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useUiStore } from '@/stores/ui';
import { useIconLibraryStore, type IconCategory } from '@/stores/iconLibrary';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { ensureLoaded, getCached, onIconLoaded, type IconPathData } from '@/render/IconLoader';
import Tooltip from '@/components/ui/Tooltip.vue';

interface IconInfo {
    id: string;            // pack/name
    displayName: string;
    pack: string;
    viewBox: string;
    source: string;
}

interface ListResponse {
    icons: IconInfo[];
    total: number;
    hasMore: boolean;
}

const project = useProjectStore();
const net = useNetworkStore();
const ui = useUiStore();
const lib = useIconLibraryStore();
const ws = getWsClient();
const { t } = useI18n();

const PAGE_SIZE = 60;
const DRAG_MIME = 'application/x-hikari-icon';

const rootRef = ref<HTMLElement | null>(null);
const gridRef = ref<HTMLElement | null>(null);
const sentinelRef = ref<HTMLElement | null>(null);
const searchInput = ref('');
const debouncedSearch = ref('');
const items = shallowRef<IconInfo[]>([]);
const hasMore = ref(false);
const offset = ref(0);
const total = ref(0);
const loading = ref(false);
const loadError = ref<string | null>(null);
const focusedId = ref<string | null>(null);

// 缓存 path d 命中情况触发 grid 重渲染（同 CanvasView 的 onIconLoaded 钩子）
const pathCacheTick = ref(0);

type CategoryLabelKey = 'all' | 'faSolid' | 'faRegular' | 'faBrands' | 'user' | 'favorites' | 'recent';
const categories: { id: IconCategory; labelKey: CategoryLabelKey }[] = [
    { id: 'all', labelKey: 'all' },
    { id: 'fa-solid', labelKey: 'faSolid' },
    { id: 'fa-regular', labelKey: 'faRegular' },
    { id: 'fa-brands', labelKey: 'faBrands' },
    { id: 'user', labelKey: 'user' },
    { id: 'favorites', labelKey: 'favorites' },
    { id: 'recent', labelKey: 'recent' },
];

const isClientCategory = computed(() => lib.activeCategory === 'favorites' || lib.activeCategory === 'recent');

/** 客户端列表（favorites / recent）：根据 store 数组拼 IconInfo 占位（displayName 取 name 段）。 */
function buildClientList(ids: string[]): IconInfo[] {
    return ids.map((id) => {
        const slash = id.indexOf('/');
        const pack = slash >= 0 ? id.slice(0, slash) : '';
        const name = slash >= 0 ? id.slice(slash + 1) : id;
        const cached = getCached(id);
        return {
            id,
            displayName: name,
            pack,
            // viewBox 可能未加载，但 ensureLoaded 后会拿到真实值，UI 用 getCached
            viewBox: cached?.viewBox ?? '0 0 512 512',
            source: id,
        };
    });
}

// ---------- search / 分页 / fetch ----------

let searchDebounceTimer: number | null = null;
watch(searchInput, (v) => {
    if (searchDebounceTimer !== null) window.clearTimeout(searchDebounceTimer);
    searchDebounceTimer = window.setTimeout(() => {
        debouncedSearch.value = v.trim();
    }, 200);
});

// 切分类 / debouncedSearch 变化 → 重置 + fetch first page
watch([debouncedSearch, () => lib.activeCategory], () => {
    resetAndFetch();
}, { immediate: false });

function resetAndFetch(): void {
    offset.value = 0;
    items.value = [];
    hasMore.value = false;
    total.value = 0;
    loadError.value = null;
    if (isClientCategory.value) {
        // 客户端列表：直接用 store；不发 fetch。debouncedSearch 在客户端列表上做子串过滤
        const source = lib.activeCategory === 'favorites' ? lib.favorites : lib.recent;
        const q = debouncedSearch.value.toLowerCase();
        const all = buildClientList(source);
        const filtered = q ? all.filter((it) => it.id.toLowerCase().includes(q)
            || it.displayName.toLowerCase().includes(q)) : all;
        items.value = filtered;
        total.value = filtered.length;
        hasMore.value = false;
        // 仍要懒加载 path d（buildClientList 用 getCached 时可能 null）
        return;
    }
    void fetchPage(true);
}

async function fetchPage(initial: boolean): Promise<void> {
    if (loading.value) return;
    if (!initial && !hasMore.value) return;
    loading.value = true;
    loadError.value = null;
    try {
        const params = new URLSearchParams();
        if (debouncedSearch.value) params.set('q', debouncedSearch.value);
        if (lib.activeCategory !== 'all') params.set('category', lib.activeCategory);
        params.set('limit', String(PAGE_SIZE));
        params.set('offset', String(initial ? 0 : offset.value));
        const resp = await fetch(`/api/icon/list?${params.toString()}`);
        if (!resp.ok) {
            loadError.value = `HTTP ${resp.status}`;
            return;
        }
        const data = await resp.json() as ListResponse;
        if (!Array.isArray(data?.icons)) {
            loadError.value = 'malformed';
            return;
        }
        if (initial) {
            items.value = data.icons;
            offset.value = data.icons.length;
        } else {
            items.value = items.value.concat(data.icons);
            offset.value += data.icons.length;
        }
        total.value = data.total | 0;
        hasMore.value = !!data.hasMore;
    } catch (e) {
        loadError.value = (e as Error).message || 'fetch failed';
    } finally {
        loading.value = false;
    }
}

// ---------- IntersectionObserver: lazy 加载可见 icon path d ----------

let visibilityObserver: IntersectionObserver | null = null;
let sentinelObserver: IntersectionObserver | null = null;

function attachVisibilityObserver(): void {
    if (visibilityObserver) return;
    if (typeof IntersectionObserver === 'undefined') return;
    visibilityObserver = new IntersectionObserver((entries) => {
        for (const entry of entries) {
            if (!entry.isIntersecting) continue;
            const el = entry.target as HTMLElement;
            const id = el.dataset.iconId;
            if (id && !getCached(id)) {
                void ensureLoaded(id);
            }
        }
    }, { root: gridRef.value, rootMargin: '64px', threshold: 0.01 });
}

function attachSentinelObserver(): void {
    if (sentinelObserver || !sentinelRef.value) return;
    if (typeof IntersectionObserver === 'undefined') return;
    sentinelObserver = new IntersectionObserver((entries) => {
        for (const entry of entries) {
            if (entry.isIntersecting && hasMore.value && !loading.value && !isClientCategory.value) {
                void fetchPage(false);
            }
        }
    }, { root: gridRef.value, rootMargin: '128px' });
    sentinelObserver.observe(sentinelRef.value);
}

function observeCell(el: Element | null): void {
    if (!el || !visibilityObserver) return;
    visibilityObserver.observe(el);
}

function unobserveCell(el: Element | null): void {
    if (!el || !visibilityObserver) return;
    visibilityObserver.unobserve(el);
}

// IconLoader 全局回调：任何 id 加载完都 tick 一下让 grid 重渲染
const onLoaderHook = (_id: string) => { pathCacheTick.value++; };
onMounted(() => {
    onIconLoaded(onLoaderHook);
    attachVisibilityObserver();
    // sentinelRef 在初次渲染后才挂载
    requestAnimationFrame(() => attachSentinelObserver());
    resetAndFetch();
});

onBeforeUnmount(() => {
    if (visibilityObserver) {
        visibilityObserver.disconnect();
        visibilityObserver = null;
    }
    if (sentinelObserver) {
        sentinelObserver.disconnect();
        sentinelObserver = null;
    }
    if (searchDebounceTimer !== null) {
        window.clearTimeout(searchDebounceTimer);
        searchDebounceTimer = null;
    }
});

// 在 panel 内点击外侧不算关闭——通过显式关闭按钮 / Esc / Shapes 按钮 toggle 控制
// 但需求里没明确说 click-outside 关；保持显式控制更符合 panel 习惯。

// Esc 关闭
function onPanelKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
        e.stopPropagation();
        lib.setOpen(false);
    }
}

// ---------- 点击 / 拖动 → 元素创建 ----------

const ELEMENT_SIZE = 64;

function centerCoords(): { x: number; y: number } {
    const cw = project.canvasPixelWidth || 256;
    const ch = project.canvasPixelHeight || 128;
    return {
        x: Math.max(0, Math.round((cw - ELEMENT_SIZE) / 2)),
        y: Math.max(0, Math.round((ch - ELEMENT_SIZE) / 2)),
    };
}

function onPick(icon: IconInfo): void {
    if (project.isLocked) {
        net.pushLog('meta', t.value.iconLibrary.lockedDenied);
        return;
    }
    if (!net.authenticated) return;
    const activeLayer = project.activeLayer;
    if (!activeLayer || activeLayer.locked) {
        net.pushLog('meta', t.value.iconLibrary.layerLocked);
        return;
    }
    const { x, y } = centerCoords();
    ws.send('element.add', {
        type: 'icon',
        layerId: activeLayer.id,
        props: {
            x, y,
            w: ELEMENT_SIZE,
            h: ELEMENT_SIZE,
            rotation: 0,
            source: icon.id,
            fill: { type: 'solid', color: '#000000' },
            locked: false,
            visible: true,
        },
    });
    lib.trackUsage(icon.id);
}

function onCellDragStart(ev: DragEvent, icon: IconInfo): void {
    if (!ev.dataTransfer) return;
    if (project.isLocked) {
        ev.preventDefault();
        return;
    }
    ev.dataTransfer.effectAllowed = 'copy';
    ev.dataTransfer.setData(DRAG_MIME, icon.id);
    // 同时 setData text/plain 作为兜底（某些环境只读 text/plain）
    ev.dataTransfer.setData('text/plain', icon.id);
    lib.trackUsage(icon.id);
}

function onToggleFavorite(ev: MouseEvent, icon: IconInfo): void {
    ev.stopPropagation();
    lib.toggleFavorite(icon.id);
}

// ---------- 渲染辅助 ----------

interface CellData {
    icon: IconInfo;
    pathData: IconPathData | null | undefined;  // undefined = 未加载；null = 加载失败
}

const cells = computed<CellData[]>(() => {
    // 引用 pathCacheTick 让 onIconLoaded 触发重渲染
    void pathCacheTick.value;
    return items.value.map((icon) => ({ icon, pathData: getCached(icon.id) }));
});

const isEmpty = computed(() => !loading.value && items.value.length === 0 && !loadError.value);

function iconTooltip(icon: IconInfo): string {
    return `${icon.displayName} · ${icon.pack}`;
}

// click-outside 触发关闭（点击 LeftTools 按钮自己 toggle，不会双关）
onClickOutside(rootRef, (e) => {
    // 排除 LeftTools 上的 IconLibrary 按钮——避免按钮 toggle 与 click-outside 同时触发立即关回
    const target = e.target as HTMLElement | null;
    if (target?.closest('[data-icon-library-trigger]')) return;
    lib.setOpen(false);
});
</script>

<template>
  <aside
    v-if="lib.open"
    ref="rootRef"
    class="absolute top-0 left-0 bottom-0 z-30 w-[360px] flex flex-col bg-[color:var(--card)] border-r border-[color:var(--border)] shadow-md"
    role="dialog"
    aria-label="Icon Library"
    @keydown="onPanelKeydown"
  >
    <!-- 头部：标题 + 关闭 -->
    <header class="flex items-center justify-between px-3 h-10 border-b border-[color:var(--border)] flex-shrink-0">
      <div class="flex items-center gap-2">
        <ImageIcon class="size-4 text-[color:var(--primary)]" />
        <span class="text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
          {{ t.iconLibrary.title }}
        </span>
        <span v-if="total > 0" class="text-[10px] text-[color:var(--muted-foreground)] font-mono tabular-nums">
          {{ items.length }}/{{ total }}
        </span>
      </div>
      <Tooltip text="Esc">
        <button
          class="p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors text-[color:var(--muted-foreground)]"
          aria-label="Close"
          @click="lib.setOpen(false)"
        >
          <X class="size-4" />
        </button>
      </Tooltip>
    </header>

    <!-- 搜索框 -->
    <div class="px-3 pt-3 pb-2 flex-shrink-0">
      <div class="relative">
        <Search class="absolute left-2 top-1/2 -translate-y-1/2 size-3.5 text-[color:var(--muted-foreground)] pointer-events-none" />
        <input
          v-model="searchInput"
          type="text"
          :placeholder="t.iconLibrary.search"
          class="w-full pl-7 pr-2 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] bg-[color:var(--background)] text-[color:var(--foreground)] placeholder:text-[color:var(--muted-foreground)] focus:outline-none focus:ring-1 focus:ring-[color:var(--primary)]"
        />
      </div>
    </div>

    <!-- 分类 tabs：横向滚动条 -->
    <nav class="px-3 pb-2 flex-shrink-0 overflow-x-auto hc-tabs-scroll">
      <div class="flex gap-1 whitespace-nowrap">
        <button
          v-for="cat in categories"
          :key="cat.id"
          class="px-2 py-1 text-[11px] rounded-[var(--radius-sm)] transition-colors border"
          :class="lib.activeCategory === cat.id
            ? 'bg-[color:var(--primary)] text-white border-[color:var(--primary)]'
            : 'bg-transparent text-[color:var(--muted-foreground)] border-[color:var(--border)] hover:bg-[color:var(--accent)]'"
          @click="lib.setCategory(cat.id)"
        >
          <Star v-if="cat.id === 'favorites'" class="size-3 inline -mt-0.5 mr-0.5" />
          <History v-else-if="cat.id === 'recent'" class="size-3 inline -mt-0.5 mr-0.5" />
          {{ t.iconLibrary.category[cat.labelKey] }}
        </button>
      </div>
    </nav>

    <!-- 主体：grid -->
    <div ref="gridRef" class="flex-1 overflow-y-auto px-3 pb-2 min-h-0">
      <!-- 错误 -->
      <p v-if="loadError" class="text-xs text-[color:var(--destructive)] py-3">
        {{ loadError }}
      </p>

      <!-- 空状态 -->
      <p v-else-if="isEmpty" class="text-xs text-[color:var(--muted-foreground)] py-6 text-center">
        {{ t.iconLibrary.empty }}
      </p>

      <!-- icon grid（8 列；窄屏自动减少） -->
      <div v-else class="grid grid-cols-6 gap-1 pt-1">
        <button
          v-for="cell in cells"
          :key="cell.icon.id"
          :ref="(el) => observeCell(el as Element | null)"
          :data-icon-id="cell.icon.id"
          :draggable="!project.isLocked"
          :title="iconTooltip(cell.icon)"
          class="hc-icon-cell group relative aspect-square flex items-center justify-center rounded-[var(--radius-sm)] border border-transparent hover:border-[color:var(--border)] hover:bg-[color:var(--accent)] focus:outline-none focus:ring-1 focus:ring-[color:var(--primary)] transition-colors"
          @click="onPick(cell.icon)"
          @dragstart="(ev) => onCellDragStart(ev, cell.icon)"
          @focus="focusedId = cell.icon.id"
          @blur="focusedId = focusedId === cell.icon.id ? null : focusedId"
        >
          <!-- icon SVG -->
          <svg
            v-if="cell.pathData"
            :viewBox="cell.pathData.viewBox"
            class="size-6 text-[color:var(--foreground)]"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <path
              v-for="(p, i) in cell.pathData.paths"
              :key="i"
              :d="p.d"
              fill="currentColor"
            />
          </svg>
          <!-- 占位（path d 未加载 / 加载失败） -->
          <span
            v-else
            class="text-[10px] text-[color:var(--muted-foreground)] font-mono"
          >
            {{ cell.pathData === null ? '!' : '·' }}
          </span>

          <!-- 收藏 star 角标（hover / 已收藏时显示） -->
          <button
            class="absolute top-0.5 right-0.5 p-0.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--card)] transition-opacity"
            :class="lib.isFavorite(cell.icon.id)
              ? 'opacity-100 text-[color:var(--ctp-yellow)]'
              : 'opacity-0 group-hover:opacity-100 text-[color:var(--muted-foreground)]'"
            :title="lib.isFavorite(cell.icon.id) ? t.iconLibrary.favoriteRemove : t.iconLibrary.favoriteAdd"
            @click="(ev) => onToggleFavorite(ev, cell.icon)"
          >
            <Star
              class="size-3"
              :fill="lib.isFavorite(cell.icon.id) ? 'currentColor' : 'none'"
            />
          </button>
        </button>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="flex items-center justify-center py-3 gap-2 text-xs text-[color:var(--muted-foreground)]">
        <Loader2 class="size-3 animate-spin" />
        <span>{{ t.iconLibrary.loading }}</span>
      </div>

      <!-- 无限滚动哨兵 -->
      <div ref="sentinelRef" class="h-2"></div>

      <!-- 手动 loadMore（兜底：IntersectionObserver 不可用环境） -->
      <button
        v-if="!isClientCategory && hasMore && !loading"
        class="w-full mt-2 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)] transition-colors"
        @click="fetchPage(false)"
      >
        {{ t.iconLibrary.loadMore }}
      </button>
    </div>

    <!-- 底栏 -->
    <footer class="px-3 py-2 border-t border-[color:var(--border)] text-[10px] text-[color:var(--muted-foreground)] leading-tight flex-shrink-0">
      {{ t.iconLibrary.dragHint }}
    </footer>
  </aside>
</template>

<style scoped>
.hc-icon-cell {
    /* 内边距让 SVG 不贴边 */
    padding: 4px;
}

/* tabs 横向滚动：隐藏丑陋滚动条 */
.hc-tabs-scroll {
    scrollbar-width: thin;
    scrollbar-color: var(--border) transparent;
}
.hc-tabs-scroll::-webkit-scrollbar {
    height: 4px;
}
.hc-tabs-scroll::-webkit-scrollbar-thumb {
    background: var(--border);
    border-radius: 2px;
}
</style>
