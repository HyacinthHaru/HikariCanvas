<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { Sun, Moon, PanelLeft, PanelRight, Terminal, Languages, Tag, Globe, Pencil, Check, X, RefreshCw } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useI18n } from '@/i18n';
import { getWsClient } from '@/network/wsClient';

const ui = useUiStore();
const net = useNetworkStore();
const project = useProjectStore();
const { t } = useI18n();
const ws = getWsClient();

const published = computed(() => project.publishedAt != null);

const editingAlias = ref(false);
const aliasDraft = ref('');
const aliasInput = ref<HTMLInputElement | null>(null);
const aliasError = ref<string | null>(null);

function togglePublish() {
    if (!project.wallId) return;
    if (published.value) {
        project.publishedAt = null; // optimistic
        ws.send('wall.unpublish');
    } else {
        // optimistic：用本地时间戳，server ack 会用真实值覆盖
        project.publishedAt = Date.now();
        ws.send('wall.publish');
    }
}

function startAliasEdit() {
    if (!project.wallId) return;
    aliasDraft.value = project.alias ?? '';
    aliasError.value = null;
    editingAlias.value = true;
    nextTick(() => aliasInput.value?.focus());
}

function cancelAliasEdit() {
    editingAlias.value = false;
    aliasDraft.value = '';
    aliasError.value = null;
}

function commitAliasEdit() {
    if (!project.wallId) return;
    const trimmed = aliasDraft.value.trim();
    const cur = project.alias ?? '';
    if (trimmed === cur || trimmed === '') {
        cancelAliasEdit();
        return;
    }
    if (trimmed.length < 2 || trimmed.length > 32) {
        aliasError.value = 'alias must be 2-32 chars';
        return;
    }
    project.alias = trimmed; // optimistic
    ws.send('wall.alias', { alias: trimmed });
    editingAlias.value = false;
    aliasError.value = null;
}

// 监听服务端报错（ALIAS_TAKEN 等）→ 回滚 + 错误提示
watch(() => net.lastError, (err) => {
    if (err && err.includes('ALIAS_TAKEN')) {
        // 回滚 optimistic：从 ready payload 重新拉是最稳，这里只重打开输入并显示错误
        editingAlias.value = true;
        aliasError.value = 'alias is already in use';
        nextTick(() => aliasInput.value?.focus());
    }
});

function copyWallId() {
    if (!project.wallId) return;
    navigator.clipboard?.writeText(project.wallId).catch(() => {});
    net.pushLog('meta', `copied wall_id: ${project.wallId}`);
}

const refreshing = ref(false);
function refreshWall() {
    if (!project.wallId || refreshing.value) return;
    refreshing.value = true;
    ws.send('wall.refresh');
    net.pushLog('meta', `requested wall.refresh for ${project.wallId}`);
    // 1.5s 后解锁按钮（服务端是 async 处理，ack 立返）
    window.setTimeout(() => { refreshing.value = false; }, 1500);
}
</script>

<template>
  <header class="flex items-center justify-between h-10 px-3 border-b border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none">
    <div class="flex items-center gap-3">
      <span class="text-sm font-semibold tracking-tight">{{ t.brand }}</span>
      <span class="text-xs text-[color:var(--muted-foreground)]">
        {{ net.serverVersion ? `server ${net.serverVersion}` : '' }}
      </span>
      <!-- M5.5: wall 元数据 -->
      <div v-if="project.wallId" class="flex items-center gap-2 ml-2 text-xs">
        <button
          class="px-1.5 py-0.5 rounded font-mono bg-[color:var(--secondary)] hover:bg-[color:var(--accent)] transition-colors"
          :title="`Copy ${project.wallId}`"
          @click="copyWallId"
        >
          {{ project.wallId }}
        </button>
        <!-- alias：默认显示按钮；点击进入内联编辑 -->
        <button
          v-if="!editingAlias"
          class="flex items-center gap-1 px-1.5 py-0.5 rounded hover:bg-[color:var(--accent)] transition-colors text-[color:var(--muted-foreground)]"
          :title="project.alias ? `alias: ${project.alias} (click to change)` : 'set alias'"
          @click="startAliasEdit"
        >
          <Tag class="size-3" />
          <span v-if="project.alias">{{ project.alias }}</span>
          <span v-else class="opacity-60">no alias</span>
          <Pencil class="size-2.5 opacity-50" />
        </button>
        <div v-else class="flex items-center gap-1 px-1.5 py-0.5 rounded bg-[color:var(--secondary)]">
          <Tag class="size-3 text-[color:var(--muted-foreground)]" />
          <input
            ref="aliasInput"
            v-model="aliasDraft"
            type="text"
            maxlength="32"
            class="hc-alias-input"
            placeholder="2-32 chars"
            @keydown.enter.prevent="commitAliasEdit"
            @keydown.escape.prevent="cancelAliasEdit"
            @blur="commitAliasEdit"
          />
          <button class="p-0.5 rounded hover:bg-emerald-500/20 text-emerald-400" title="Save (Enter)" @mousedown.prevent="commitAliasEdit">
            <Check class="size-3" />
          </button>
          <button class="p-0.5 rounded hover:bg-red-500/20 text-red-400" title="Cancel (Esc)" @mousedown.prevent="cancelAliasEdit">
            <X class="size-3" />
          </button>
          <span v-if="aliasError" class="text-[10px] text-red-400 ml-1">{{ aliasError }}</span>
        </div>
        <button
          class="flex items-center gap-1 px-2 py-0.5 rounded text-xs transition-colors"
          :class="published
            ? 'bg-emerald-500/20 text-emerald-300 hover:bg-emerald-500/30'
            : 'bg-[color:var(--secondary)] text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)]'"
          :title="published ? 'Unpublish' : 'Publish'"
          @click="togglePublish"
        >
          <Globe class="size-3" />
          <span>{{ published ? 'Published' : 'Draft' }}</span>
        </button>
        <button
          class="flex items-center gap-1 px-1.5 py-0.5 rounded text-xs text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)] transition-colors disabled:opacity-50"
          title="Re-spawn missing ItemFrames in-game and force a full re-render"
          :disabled="refreshing"
          @click="refreshWall"
        >
          <RefreshCw class="size-3" :class="refreshing ? 'animate-spin' : ''" />
          <span>Refresh</span>
        </button>
      </div>
    </div>

    <div class="flex items-center gap-1">
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="t.topbar.toggleLeft"
        @click="ui.toggleLeft()"
      >
        <PanelLeft class="size-4" />
      </button>
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="t.topbar.toggleRight"
        @click="ui.toggleRight()"
      >
        <PanelRight class="size-4" />
      </button>
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="t.topbar.toggleLog"
        @click="ui.toggleLogDrawer()"
      >
        <Terminal class="size-4" />
      </button>
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="t.topbar.switchLocale"
        @click="ui.toggleLocale()"
      >
        <Languages class="size-4" />
      </button>
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="t.topbar.toggleTheme"
        @click="ui.toggleTheme()"
      >
        <Sun v-if="ui.theme === 'dark'" class="size-4" />
        <Moon v-else class="size-4" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.hc-alias-input {
    width: 9rem;
    background: transparent;
    border: none;
    outline: none;
    font-size: 0.75rem;
    color: var(--foreground);
    font-family: ui-sans-serif, system-ui, sans-serif;
    padding: 0;
}
</style>
