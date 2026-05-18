<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { Lock, Pencil, MapPin, Clock, Tag, ImageOff, RefreshCw, Sparkles, Star, User } from 'lucide-vue-next';
import { useI18n } from '@/i18n';

interface WallSummary {
    wallId: string;
    alias: string | null;
    ownerName: string;
    world: string;
    originX: number;
    originY: number;
    originZ: number;
    facing: string;
    widthMaps: number;
    heightMaps: number;
    /** 锁定时间戳（2026-05-14 起 publishedAt 字段语义化为 lock 时间戳） */
    publishedAt: number | null;
    updatedAt: number;
}

const { t } = useI18n();
const walls = ref<WallSummary[]>([]);
const loading = ref(true);
const refreshing = ref(false);
const error = ref<string | null>(null);
const copiedId = ref<string | null>(null);
let copiedTimer: number | null = null;

// 2026-05-14 lock-state：published 分组改为 locked / unlocked
const locked = computed(() => walls.value.filter(w => w.publishedAt != null));
const unlocked = computed(() => walls.value.filter(w => w.publishedAt == null));

async function loadWalls(isManualRefresh = false) {
    if (isManualRefresh) refreshing.value = true;
    try {
        const r = await fetch('/api/walls');
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        walls.value = await r.json();
        error.value = null;
    } catch (e) {
        error.value = (e as Error).message;
    } finally {
        loading.value = false;
        refreshing.value = false;
    }
}

// ---------- M14 创意工坊：模板市场 ----------
interface TemplateRow {
    templateId: string;
    ownerUuid: string | null;
    ownerName: string | null;
    displayName: string;
    description: string | null;
    builtin: boolean;
    featured: boolean;
    downloadCount: number;
    createdAt: number;
    updatedAt: number;
}

const templates = ref<TemplateRow[]>([]);
const templatesLoading = ref(true);
const templatesError = ref<string | null>(null);

async function loadTemplates() {
    try {
        const r = await fetch('/api/templates');
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        templates.value = await r.json();
        templatesError.value = null;
    } catch (e) {
        templatesError.value = (e as Error).message;
    } finally {
        templatesLoading.value = false;
    }
}

function templatePreview(t: TemplateRow): string {
    return `/api/template/${encodeURIComponent(t.templateId)}/preview.png?t=${t.updatedAt}`;
}

onMounted(() => {
    loadWalls(false);
    loadTemplates();
});

function fmtTime(ts: number): string {
    const d = new Date(ts);
    // 简短：今天 → 14:23；本周 → 周三 14:23；更早 → 5月12日 14:23
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    const hh = d.getHours().toString().padStart(2, '0');
    const mm = d.getMinutes().toString().padStart(2, '0');
    if (sameDay) return `${hh}:${mm}`;
    const diffDays = Math.floor((now.getTime() - d.getTime()) / 86400000);
    if (diffDays < 7) {
        const weekdays = ['日','一','二','三','四','五','六'];
        return `周${weekdays[d.getDay()]} ${hh}:${mm}`;
    }
    return `${d.getMonth() + 1}月${d.getDate()}日 ${hh}:${mm}`;
}

function copyOpenCmd(wallId: string) {
    navigator.clipboard?.writeText(`/canvas open ${wallId}`).catch(() => {});
    if (copiedTimer != null) window.clearTimeout(copiedTimer);
    copiedId.value = wallId;
    copiedTimer = window.setTimeout(() => {
        copiedId.value = null;
        copiedTimer = null;
    }, 900);
}

function previewUrl(w: WallSummary): string {
    return `/api/wall/${encodeURIComponent(w.wallId)}/preview.png?t=${w.updatedAt}`;
}
</script>

<template>
  <div class="h-screen w-screen flex flex-col bg-[color:var(--background)] text-[color:var(--foreground)] overflow-auto">
    <header class="px-6 py-5 border-b border-[color:var(--border)] flex items-center gap-3">
      <div class="flex-1 min-w-0">
        <h1 class="text-xl font-semibold tracking-tight">HikariCanvas</h1>
        <p class="text-sm text-[color:var(--muted-foreground)] mt-1">
          {{ t.home.heading }} · {{ t.home.subtitle }}<code class="px-1 py-0.5 rounded bg-[color:var(--secondary)] font-mono text-xs">{{ t.home.subtitleCmd }}</code>{{ t.home.subtitleSuffix }}
        </p>
      </div>
      <button
        class="p-2 rounded hover:bg-[color:var(--accent)] text-[color:var(--muted-foreground)] disabled:opacity-40"
        :disabled="refreshing"
        :title="t.home.refresh"
        @click="loadWalls(true)"
      >
        <RefreshCw class="size-4" :class="refreshing ? 'animate-spin' : ''" />
      </button>
    </header>

    <main class="flex-1 px-6 py-6 max-w-6xl w-full mx-auto">
      <div v-if="loading" class="text-sm text-[color:var(--muted-foreground)]">{{ t.home.loading }}</div>
      <div v-else-if="error" class="text-sm text-[color:var(--destructive)]">{{ t.home.failed(error) }}</div>
      <div v-else-if="walls.length === 0" class="flex flex-col items-center justify-center py-20 gap-3 text-center">
        <ImageOff class="size-12 text-[color:var(--muted-foreground)] opacity-40" />
        <div class="text-sm text-[color:var(--muted-foreground)] max-w-md">
          {{ t.home.empty }}
        </div>
      </div>
      <div v-else class="space-y-8">
        <section v-if="locked.length > 0">
          <h2 class="flex items-center gap-2 text-sm font-medium uppercase tracking-wider text-[color:var(--muted-foreground)] mb-3">
            <Lock class="size-4 text-[color:var(--ctp-peach)]" /> {{ t.home.lockedGroup(locked.length) }}
          </h2>
          <ul class="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3">
            <li v-for="w in locked" :key="w.wallId" class="hc-wall-card hc-wall-locked">
              <div class="hc-wall-thumb" :style="{ aspectRatio: `${w.widthMaps} / ${w.heightMaps}` }">
                <img :src="previewUrl(w)" loading="lazy" alt="" />
              </div>
              <div class="flex flex-col gap-1.5 p-3">
                <div class="flex items-center gap-2 text-sm font-mono truncate">
                  <span class="select-all truncate">{{ w.wallId }}</span>
                  <span v-if="w.alias" class="flex items-center gap-1 text-xs font-sans text-[color:var(--muted-foreground)] truncate">
                    <Tag class="size-3 shrink-0" /><span class="truncate">{{ w.alias }}</span>
                  </span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)] truncate">
                  <MapPin class="size-3 shrink-0" />
                  <span class="truncate">{{ w.world }} ({{ w.originX }},{{ w.originY }},{{ w.originZ }}) {{ w.facing }}</span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]">
                  <span class="tabular-nums">{{ t.home.mapsLabel(w.widthMaps, w.heightMaps) }}</span>
                  <span class="opacity-50">·</span>
                  <Clock class="size-3" />
                  <span>{{ fmtTime(w.updatedAt) }}</span>
                </div>
                <code
                  class="mt-1 text-xs px-2 py-1 rounded bg-[color:var(--secondary)] cursor-pointer font-mono hover:bg-[color:var(--accent)] transition-colors truncate"
                  :title="t.home.copyHint"
                  @click="copyOpenCmd(w.wallId)"
                >
                  <span v-if="copiedId === w.wallId" class="text-[color:var(--ctp-green)]">{{ t.wall.copied }}</span>
                  <span v-else>/canvas open {{ w.wallId }}</span>
                </code>
              </div>
            </li>
          </ul>
        </section>
        <section v-if="unlocked.length > 0">
          <h2 class="flex items-center gap-2 text-sm font-medium uppercase tracking-wider text-[color:var(--muted-foreground)] mb-3">
            <Pencil class="size-4" /> {{ t.home.unlockedGroup(unlocked.length) }}
          </h2>
          <ul class="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3">
            <li v-for="w in unlocked" :key="w.wallId" class="hc-wall-card">
              <div class="hc-wall-thumb" :style="{ aspectRatio: `${w.widthMaps} / ${w.heightMaps}` }">
                <img :src="previewUrl(w)" loading="lazy" alt="" />
              </div>
              <div class="flex flex-col gap-1.5 p-3">
                <div class="flex items-center gap-2 text-sm font-mono truncate">
                  <span class="select-all truncate">{{ w.wallId }}</span>
                  <span v-if="w.alias" class="flex items-center gap-1 text-xs font-sans text-[color:var(--muted-foreground)] truncate">
                    <Tag class="size-3 shrink-0" /><span class="truncate">{{ w.alias }}</span>
                  </span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)] truncate">
                  <MapPin class="size-3 shrink-0" />
                  <span class="truncate">{{ w.world }} ({{ w.originX }},{{ w.originY }},{{ w.originZ }}) {{ w.facing }}</span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]">
                  <span class="tabular-nums">{{ t.home.mapsLabel(w.widthMaps, w.heightMaps) }}</span>
                  <span class="opacity-50">·</span>
                  <Clock class="size-3" />
                  <span>{{ fmtTime(w.updatedAt) }}</span>
                </div>
                <code
                  class="mt-1 text-xs px-2 py-1 rounded bg-[color:var(--secondary)] cursor-pointer font-mono hover:bg-[color:var(--accent)] transition-colors truncate"
                  :title="t.home.copyHint"
                  @click="copyOpenCmd(w.wallId)"
                >
                  <span v-if="copiedId === w.wallId" class="text-[color:var(--ctp-green)]">{{ t.wall.copied }}</span>
                  <span v-else>/canvas open {{ w.wallId }}</span>
                </code>
              </div>
            </li>
          </ul>
        </section>

        <!-- M14 创意工坊：模板市场（只读卡片网格；精选优先 + 时间倒序） -->
        <section class="mt-10">
          <header class="flex items-baseline gap-2 mb-3">
            <Sparkles class="size-4 text-[color:var(--ctp-peach)] translate-y-0.5" />
            <h2 class="text-lg font-semibold">{{ t.workshop.marketplaceHeader }}</h2>
            <span class="text-xs text-[color:var(--muted-foreground)]">
              {{ t.workshop.marketplaceCount(templates.length) }}
            </span>
          </header>
          <p v-if="templatesLoading" class="text-xs text-[color:var(--muted-foreground)]">
            {{ t.workshop.loading }}
          </p>
          <p v-else-if="templatesError" class="text-xs text-[color:var(--destructive)]">
            {{ t.workshop.loadFailed(templatesError) }}
          </p>
          <p v-else-if="templates.length === 0" class="text-xs text-[color:var(--muted-foreground)]">
            {{ t.workshop.empty }}
          </p>
          <ul v-else class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
            <li v-for="tpl in templates" :key="tpl.templateId"
                class="bg-[color:var(--card)] border border-[color:var(--border)] rounded-[var(--radius)] overflow-hidden flex flex-col">
              <div class="aspect-[4/3] bg-[color:var(--muted)] relative">
                <img :src="templatePreview(tpl)" :alt="tpl.displayName"
                     class="w-full h-full object-contain"
                     loading="lazy"
                     @error="(e) => ((e.target as HTMLImageElement).style.display = 'none')" />
                <span v-if="tpl.featured"
                      class="absolute top-1 left-1 px-1.5 py-0.5 rounded-[var(--radius-sm)] text-xs font-medium
                             bg-[color:var(--ctp-peach)] text-[color:var(--ctp-base)] inline-flex items-center gap-0.5">
                  <Star class="size-2.5 fill-current" />
                  {{ t.workshop.featuredBadge }}
                </span>
                <span v-if="tpl.builtin"
                      class="absolute top-1 right-1 px-1.5 py-0.5 rounded-[var(--radius-sm)] text-xs font-medium
                             bg-[color:var(--ctp-blue)] text-[color:var(--ctp-base)]">
                  {{ t.workshop.builtinBadge }}
                </span>
              </div>
              <div class="p-2 flex-1 min-h-0 flex flex-col gap-0.5">
                <div class="text-xs font-medium truncate" :title="tpl.displayName">{{ tpl.displayName }}</div>
                <div v-if="tpl.description"
                     class="text-xs text-[color:var(--muted-foreground)] line-clamp-2">{{ tpl.description }}</div>
                <div class="mt-auto flex items-center justify-between pt-1 text-xs text-[color:var(--muted-foreground)]">
                  <span v-if="tpl.ownerName" class="inline-flex items-center gap-0.5">
                    <User class="size-2.5" />
                    {{ tpl.ownerName }}
                  </span>
                  <span v-else class="opacity-60">{{ t.workshop.builtinAuthor }}</span>
                  <span class="font-mono">{{ tpl.templateId }}</span>
                </div>
              </div>
            </li>
          </ul>
        </section>
      </div>
    </main>
  </div>
</template>

<style scoped>
/* M24-B：M3 surface elevation——hover 不再用 box-shadow + translateY 营造浮动
   AI 审美，而是改用 surface 色调加深（var(--accent) ≈ surface1）+ border 高亮。 */
.hc-wall-card {
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--card);
    overflow: hidden;
    transition: background-color 140ms ease, border-color 140ms ease;
    display: flex;
    flex-direction: column;
}
.hc-wall-card:hover {
    background: var(--accent);
    border-color: var(--ring);
}
.hc-wall-locked {
    border-color: color-mix(in srgb, var(--ctp-peach) 50%, var(--border));
}
.hc-wall-locked:hover {
    border-color: var(--ctp-peach);
}
/* 缩略图 */
.hc-wall-thumb {
    width: 100%;
    background:
        repeating-linear-gradient(
            45deg,
            color-mix(in srgb, var(--border) 30%, transparent),
            color-mix(in srgb, var(--border) 30%, transparent) 6px,
            transparent 6px,
            transparent 12px
        ),
        var(--secondary);
    border-bottom: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
}
.hc-wall-thumb img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    image-rendering: pixelated;
    display: block;
}
</style>
