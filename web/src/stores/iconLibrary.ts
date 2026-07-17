import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

/**
 * IconLibrary store：图标库 panel 的 open 状态 + 收藏夹 + 最近使用列表。
 *
 * <p>三段 localStorage 持久化（与 ui store 的 theme / locale / snap 同级）：
 * <ul>
 *   <li>{@code hikari-canvas:icon-favorites} — string[]，收藏的完整 icon id（如 fa-solid/heart）</li>
 *   <li>{@code hikari-canvas:icon-recent} — string[]，最近用过的 icon id，最多 20 个，新的 prepend</li>
 *   <li>{@code hikari-canvas:icon-category} — 上次激活的 tab，下次打开 panel 时恢复</li>
 * </ul>
 *
 * <p>open 不持久化——panel 默认关闭，每次会话用户主动 toggle。</p>
 *
 * <p>cap 收藏夹无硬上限（用户行为自决），但最近使用强制 20 个滑动窗口防 localStorage 膨胀。</p>
 */

const FAVORITES_KEY = 'hikari-canvas:icon-favorites';
const RECENT_KEY = 'hikari-canvas:icon-recent';
const CATEGORY_KEY = 'hikari-canvas:icon-category';
const RECENT_MAX = 20;

export type IconCategory = 'all' | 'fa-solid' | 'fa-regular' | 'fa-brands' | 'user' | 'favorites' | 'recent';

const VALID_CATEGORIES: IconCategory[] = ['all', 'fa-solid', 'fa-regular', 'fa-brands', 'user', 'favorites', 'recent'];

function loadStringArray(key: string): string[] {
    try {
        const raw = localStorage.getItem(key);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) return [];
        // 防御：localStorage 数据可能被外部污染
        return parsed.filter((x): x is string => typeof x === 'string' && x.length > 0 && x.length <= 64);
    } catch { return []; }
}

function loadCategory(): IconCategory {
    try {
        const v = localStorage.getItem(CATEGORY_KEY);
        if (v && (VALID_CATEGORIES as string[]).includes(v)) return v as IconCategory;
    } catch { /* ignore */ }
    return 'all';
}

export const useIconLibraryStore = defineStore('icon-library', () => {
    const favorites = ref<string[]>(loadStringArray(FAVORITES_KEY));
    const recent = ref<string[]>(loadStringArray(RECENT_KEY));
    const open = ref(false);
    const activeCategory = ref<IconCategory>(loadCategory());

    watch(favorites, (v) => {
        try { localStorage.setItem(FAVORITES_KEY, JSON.stringify(v)); } catch { /* ignore */ }
    }, { deep: true });
    watch(recent, (v) => {
        try { localStorage.setItem(RECENT_KEY, JSON.stringify(v)); } catch { /* ignore */ }
    }, { deep: true });
    watch(activeCategory, (v) => {
        try { localStorage.setItem(CATEGORY_KEY, v); } catch { /* ignore */ }
    });

    function toggleOpen(): void {
        open.value = !open.value;
    }

    function setOpen(v: boolean): void {
        open.value = v;
    }

    function setCategory(c: IconCategory): void {
        activeCategory.value = c;
    }

    function isFavorite(id: string): boolean {
        return favorites.value.includes(id);
    }

    function toggleFavorite(id: string): void {
        const idx = favorites.value.indexOf(id);
        if (idx >= 0) favorites.value = favorites.value.filter((x) => x !== id);
        else favorites.value = [...favorites.value, id];
    }

    /** 把 id prepend 到 recent，去重；超过 RECENT_MAX 时丢最尾。 */
    function trackUsage(id: string): void {
        recent.value = [id, ...recent.value.filter((x) => x !== id)].slice(0, RECENT_MAX);
    }

    return {
        favorites, recent, open, activeCategory,
        toggleOpen, setOpen, setCategory,
        isFavorite, toggleFavorite, trackUsage,
    };
});
