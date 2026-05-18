import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

/**
 * M24-B 主题 store：preset flavor + accent + radius scale。
 *
 * 拆分自 {@link useUiStore}，因为：
 *   - theme 偏好独立于 wall / element 状态，跨 wall 持久；
 *   - 三维（flavor / accent / radius）独立 localStorage key，互不污染；
 *   - ThemeSwitcher.vue 单一消费者，避免 ui store 进一步膨胀。
 *
 * 旧 `useUiStore.theme: 'dark' | 'light'` 兼容保留：仍由 ThemeSwitcher 在 setFlavor 时
 * 同步设置 `.dark` class（Latte = 浅色，Frappé/Macchiato = 深色），确保现存 `.dark` 选择器
 * 走的 shadcn-vue 组件代码不破。
 */

export type Flavor = 'latte' | 'frappe' | 'macchiato';
export type Accent = 'mauve' | 'blue' | 'teal' | 'green' | 'peach' | 'pink' | 'lavender' | 'sapphire';
export type RadiusScale = 'sm' | 'md' | 'lg' | 'xl' | 'full';

export interface ThemePreset {
    id: Flavor;
    /** i18n key 提示用：避免硬编码中文 */
    nameZh: string;
    nameEn: string;
    flavor: Flavor;
    isDark: boolean;
    /** 预览圆点用：base 主色 */
    previewBase: string;
    /** 预览圆点用：accent (mauve) */
    previewAccent: string;
}

export const PRESETS: ThemePreset[] = [
    {
        id: 'latte', flavor: 'latte', isDark: false,
        nameZh: 'Catppuccin Latte', nameEn: 'Catppuccin Latte',
        previewBase: 'hsl(220 23% 95%)', previewAccent: 'hsl(266 85% 58%)',
    },
    {
        id: 'frappe', flavor: 'frappe', isDark: true,
        nameZh: 'Catppuccin Frappé', nameEn: 'Catppuccin Frappé',
        previewBase: 'hsl(229 19% 23%)', previewAccent: 'hsl(277 59% 76%)',
    },
    {
        id: 'macchiato', flavor: 'macchiato', isDark: true,
        nameZh: 'Catppuccin Macchiato', nameEn: 'Catppuccin Macchiato',
        previewBase: 'hsl(232 23% 18%)', previewAccent: 'hsl(267 83% 80%)',
    },
];

export const ACCENTS: { id: Accent; color: string }[] = [
    { id: 'mauve',    color: 'hsl(266 85% 58%)' },
    { id: 'blue',     color: 'hsl(220 91% 54%)' },
    { id: 'teal',     color: 'hsl(183 74% 35%)' },
    { id: 'green',    color: 'hsl(109 58% 40%)' },
    { id: 'peach',    color: 'hsl(22 99% 52%)'  },
    { id: 'pink',     color: 'hsl(316 73% 69%)' },
    { id: 'lavender', color: 'hsl(231 97% 72%)' },
    { id: 'sapphire', color: 'hsl(189 70% 42%)' },
];

export const RADIUS_OPTIONS: { id: RadiusScale; px: number }[] = [
    { id: 'sm',   px: 6  },
    { id: 'md',   px: 12 },
    { id: 'lg',   px: 16 },
    { id: 'xl',   px: 24 },
    { id: 'full', px: 9999 },
];

const FLAVOR_KEY = 'hikari-canvas:theme.flavor';
const ACCENT_KEY = 'hikari-canvas:theme.accent';
const RADIUS_KEY = 'hikari-canvas:theme.radius';
/** 兼容旧 useUiStore.theme localStorage key（仍写以便回滚不丢） */
const LEGACY_THEME_KEY = 'hikari-canvas:theme';

function loadFlavor(): Flavor {
    try {
        const v = localStorage.getItem(FLAVOR_KEY);
        if (v === 'latte' || v === 'frappe' || v === 'macchiato') return v;
        // 兼容旧 'dark' / 'light' → 转换：dark → frappe，light → latte
        const legacy = localStorage.getItem(LEGACY_THEME_KEY);
        if (legacy === 'dark') return 'frappe';
        if (legacy === 'light') return 'latte';
    } catch { /* ignore */ }
    // 跟随系统：暗色 → frappe，浅色 → latte
    if (window.matchMedia?.('(prefers-color-scheme: light)').matches) return 'latte';
    return 'frappe';
}

function loadAccent(): Accent {
    try {
        const v = localStorage.getItem(ACCENT_KEY) as Accent | null;
        if (v && ACCENTS.some(a => a.id === v)) return v;
    } catch { /* ignore */ }
    return 'mauve';
}

function loadRadius(): RadiusScale {
    try {
        const v = localStorage.getItem(RADIUS_KEY) as RadiusScale | null;
        if (v && RADIUS_OPTIONS.some(r => r.id === v)) return v;
    } catch { /* ignore */ }
    return 'md';
}

function applyFlavor(flavor: Flavor): void {
    const html = document.documentElement;
    // 清旧 theme-* class
    html.classList.remove('theme-latte', 'theme-frappe', 'theme-macchiato');
    html.classList.add(`theme-${flavor}`);
    // 兼容 shadcn-vue 的 .dark 选择器：Frappé / Macchiato 为深色
    const isDark = flavor !== 'latte';
    html.classList.toggle('dark', isDark);
    // 兼容旧 useUiStore.theme localStorage（避免新-旧版本切换时丢失偏好）
    try { localStorage.setItem(LEGACY_THEME_KEY, isDark ? 'dark' : 'light'); } catch { /* ignore */ }
}

function applyAccent(accent: Accent): void {
    document.documentElement.dataset.accent = accent;
}

function applyRadius(radius: RadiusScale): void {
    document.documentElement.dataset.radius = radius;
}

export const useThemeStore = defineStore('theme', () => {
    const flavor = ref<Flavor>(loadFlavor());
    const accent = ref<Accent>(loadAccent());
    const radius = ref<RadiusScale>(loadRadius());

    // 初始应用
    applyFlavor(flavor.value);
    applyAccent(accent.value);
    applyRadius(radius.value);

    watch(flavor, (v) => {
        applyFlavor(v);
        try { localStorage.setItem(FLAVOR_KEY, v); } catch { /* ignore */ }
    });
    watch(accent, (v) => {
        applyAccent(v);
        try { localStorage.setItem(ACCENT_KEY, v); } catch { /* ignore */ }
    });
    watch(radius, (v) => {
        applyRadius(v);
        try { localStorage.setItem(RADIUS_KEY, v); } catch { /* ignore */ }
    });

    function setFlavor(v: Flavor): void { flavor.value = v; }
    function setAccent(v: Accent): void { accent.value = v; }
    function setRadius(v: RadiusScale): void { radius.value = v; }

    /** 兼容旧 ui.toggleTheme：在 Latte ↔ Frappé 间切。 */
    function toggleLightDark(): void {
        flavor.value = flavor.value === 'latte' ? 'frappe' : 'latte';
    }

    return {
        flavor, accent, radius,
        setFlavor, setAccent, setRadius, toggleLightDark,
    };
});
