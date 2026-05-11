<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { Globe, Pencil, MapPin, Clock, Tag } from 'lucide-vue-next';
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
    publishedAt: number | null;
    updatedAt: number;
}

const { t } = useI18n();
const walls = ref<WallSummary[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const copiedId = ref<string | null>(null);
let copiedTimer: number | null = null;

const published = computed(() => walls.value.filter(w => w.publishedAt != null));
const drafts = computed(() => walls.value.filter(w => w.publishedAt == null));

onMounted(async () => {
    try {
        const r = await fetch('/api/walls');
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        walls.value = await r.json();
    } catch (e) {
        error.value = (e as Error).message;
    } finally {
        loading.value = false;
    }
});

function fmtTime(ts: number): string {
    return new Date(ts).toLocaleString();
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
</script>

<template>
  <div class="h-screen w-screen flex flex-col bg-[color:var(--background)] text-[color:var(--foreground)] overflow-auto">
    <header class="px-6 py-5 border-b border-[color:var(--border)]">
      <h1 class="text-xl font-semibold tracking-tight">HikariCanvas</h1>
      <p class="text-sm text-[color:var(--muted-foreground)] mt-1">
        {{ t.home.heading }} · {{ t.home.subtitle }}<code class="px-1 py-0.5 rounded bg-[color:var(--secondary)] font-mono text-xs">{{ t.home.subtitleCmd }}</code>{{ t.home.subtitleSuffix }}
      </p>
    </header>

    <main class="flex-1 px-6 py-6 max-w-5xl w-full mx-auto">
      <div v-if="loading" class="text-sm text-[color:var(--muted-foreground)]">{{ t.home.loading }}</div>
      <div v-else-if="error" class="text-sm text-red-400">{{ t.home.failed(error) }}</div>
      <div v-else-if="walls.length === 0" class="text-sm text-[color:var(--muted-foreground)]">
        {{ t.home.empty }}
      </div>
      <div v-else class="space-y-8">
        <section v-if="published.length > 0">
          <h2 class="flex items-center gap-2 text-sm font-medium uppercase tracking-wider text-[color:var(--muted-foreground)] mb-3">
            <Globe class="size-4 text-emerald-400" /> {{ t.home.publishedGroup(published.length) }}
          </h2>
          <ul class="grid gap-3 grid-cols-1 md:grid-cols-2">
            <li v-for="w in published" :key="w.wallId" class="hc-wall-card hc-wall-published">
              <div class="flex flex-col gap-1.5 p-3">
                <div class="flex items-center gap-2 text-base font-mono">
                  <span class="select-all">{{ w.wallId }}</span>
                  <span v-if="w.alias" class="flex items-center gap-1 text-sm font-sans text-[color:var(--muted-foreground)]">
                    <Tag class="size-3" />{{ w.alias }}
                  </span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]">
                  <MapPin class="size-3" />
                  <span>{{ w.world }} ({{ w.originX }},{{ w.originY }},{{ w.originZ }}) {{ w.facing }}</span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]">
                  <span>{{ t.home.mapsLabel(w.widthMaps, w.heightMaps) }}</span>
                  <span class="opacity-50">·</span>
                  <Clock class="size-3" />
                  <span>{{ t.home.updatedAt(fmtTime(w.updatedAt)) }}</span>
                </div>
                <code
                  class="mt-1 text-xs px-2 py-1 rounded bg-[color:var(--secondary)] cursor-pointer font-mono hover:bg-[color:var(--accent)] transition-colors"
                  :title="t.home.copyHint"
                  @click="copyOpenCmd(w.wallId)"
                >
                  <span v-if="copiedId === w.wallId" class="text-emerald-400">{{ t.wall.copied }}</span>
                  <span v-else>/canvas open {{ w.wallId }}</span>
                </code>
              </div>
            </li>
          </ul>
        </section>
        <section v-if="drafts.length > 0">
          <h2 class="flex items-center gap-2 text-sm font-medium uppercase tracking-wider text-[color:var(--muted-foreground)] mb-3">
            <Pencil class="size-4" /> {{ t.home.draftsGroup(drafts.length) }}
          </h2>
          <ul class="grid gap-3 grid-cols-1 md:grid-cols-2">
            <li v-for="w in drafts" :key="w.wallId" class="hc-wall-card">
              <div class="flex flex-col gap-1.5 p-3">
                <div class="flex items-center gap-2 text-base font-mono">
                  <span class="select-all">{{ w.wallId }}</span>
                  <span v-if="w.alias" class="flex items-center gap-1 text-sm font-sans text-[color:var(--muted-foreground)]">
                    <Tag class="size-3" />{{ w.alias }}
                  </span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]">
                  <MapPin class="size-3" />
                  <span>{{ w.world }} ({{ w.originX }},{{ w.originY }},{{ w.originZ }}) {{ w.facing }}</span>
                </div>
                <div class="flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]">
                  <span>{{ t.home.mapsLabel(w.widthMaps, w.heightMaps) }}</span>
                  <span class="opacity-50">·</span>
                  <Clock class="size-3" />
                  <span>{{ t.home.updatedAt(fmtTime(w.updatedAt)) }}</span>
                </div>
                <code
                  class="mt-1 text-xs px-2 py-1 rounded bg-[color:var(--secondary)] cursor-pointer font-mono hover:bg-[color:var(--accent)] transition-colors"
                  :title="t.home.copyHint"
                  @click="copyOpenCmd(w.wallId)"
                >
                  <span v-if="copiedId === w.wallId" class="text-emerald-400">{{ t.wall.copied }}</span>
                  <span v-else>/canvas open {{ w.wallId }}</span>
                </code>
              </div>
            </li>
          </ul>
        </section>
      </div>
    </main>
  </div>
</template>

<style scoped>
.hc-wall-card {
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--card);
}
.hc-wall-published {
    border-color: rgb(16 185 129 / 0.3);
}
</style>
