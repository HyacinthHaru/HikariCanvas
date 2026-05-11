<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { Globe, Pencil, MapPin, Clock, Tag } from 'lucide-vue-next';

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

const walls = ref<WallSummary[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

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
    const d = new Date(ts);
    return d.toLocaleString();
}
</script>

<template>
  <div class="h-screen w-screen flex flex-col bg-[color:var(--background)] text-[color:var(--foreground)] overflow-auto">
    <header class="px-6 py-5 border-b border-[color:var(--border)]">
      <h1 class="text-xl font-semibold tracking-tight">HikariCanvas</h1>
      <p class="text-sm text-[color:var(--muted-foreground)] mt-1">
        Recent walls. Use <code class="px-1 py-0.5 rounded bg-[color:var(--secondary)] font-mono text-xs">/canvas open &lt;wall_id&gt;</code> in-game to start editing.
      </p>
    </header>

    <main class="flex-1 px-6 py-6 max-w-5xl w-full mx-auto">
      <div v-if="loading" class="text-sm text-[color:var(--muted-foreground)]">Loading…</div>
      <div v-else-if="error" class="text-sm text-red-400">Failed to load: {{ error }}</div>
      <div v-else-if="walls.length === 0" class="text-sm text-[color:var(--muted-foreground)]">
        No walls yet. Run <code class="px-1 py-0.5 rounded bg-[color:var(--secondary)] font-mono text-xs">/canvas edit</code> in-game to create one.
      </div>
      <div v-else class="space-y-8">
        <section v-if="published.length > 0">
          <h2 class="flex items-center gap-2 text-sm font-medium uppercase tracking-wider text-[color:var(--muted-foreground)] mb-3">
            <Globe class="size-4 text-emerald-400" /> Published ({{ published.length }})
          </h2>
          <ul class="grid gap-3 grid-cols-1 md:grid-cols-2">
            <li v-for="w in published" :key="w.wallId" class="hc-wall-card hc-wall-published">
              <WallCard :wall="w" :format-time="fmtTime" />
            </li>
          </ul>
        </section>
        <section v-if="drafts.length > 0">
          <h2 class="flex items-center gap-2 text-sm font-medium uppercase tracking-wider text-[color:var(--muted-foreground)] mb-3">
            <Pencil class="size-4" /> Editing ({{ drafts.length }})
          </h2>
          <ul class="grid gap-3 grid-cols-1 md:grid-cols-2">
            <li v-for="w in drafts" :key="w.wallId" class="hc-wall-card">
              <WallCard :wall="w" :format-time="fmtTime" />
            </li>
          </ul>
        </section>
      </div>
    </main>
  </div>
</template>

<script lang="ts">
import { defineComponent, h } from 'vue';
import { Globe as Glob, Pencil as Pen, MapPin as Pin, Clock as Clk, Tag as Tg } from 'lucide-vue-next';
export const WallCard = defineComponent({
    name: 'WallCard',
    props: {
        wall: { type: Object as () => WallSummary, required: true },
        formatTime: { type: Function as unknown as () => (ts: number) => string, required: true },
    },
    setup(props) {
        return () => {
            const w = props.wall;
            return h('div', { class: 'flex flex-col gap-1.5 p-3' }, [
                h('div', { class: 'flex items-center gap-2 text-base font-mono' }, [
                    h('span', { class: 'select-all' }, w.wallId),
                    w.alias ? h('span', { class: 'flex items-center gap-1 text-sm font-sans text-[color:var(--muted-foreground)]' }, [
                        h(Tg, { class: 'size-3' }),
                        w.alias,
                    ]) : null,
                ]),
                h('div', { class: 'flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]' }, [
                    h(Pin, { class: 'size-3' }),
                    h('span', {}, `${w.world} (${w.originX},${w.originY},${w.originZ}) ${w.facing}`),
                ]),
                h('div', { class: 'flex items-center gap-1.5 text-xs text-[color:var(--muted-foreground)]' }, [
                    h('span', {}, `${w.widthMaps}×${w.heightMaps} maps`),
                    h('span', { class: 'opacity-50' }, '·'),
                    h(Clk, { class: 'size-3' }),
                    h('span', {}, `updated ${(props.formatTime as (ts: number) => string)(w.updatedAt)}`),
                ]),
                h('code', {
                    class: 'mt-1 text-xs px-2 py-1 rounded bg-[color:var(--secondary)] cursor-pointer font-mono',
                    title: 'Click to copy',
                    onClick: () => navigator.clipboard?.writeText(`/canvas open ${w.wallId}`),
                }, `/canvas open ${w.wallId}`),
            ]);
        };
    },
});
</script>

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
