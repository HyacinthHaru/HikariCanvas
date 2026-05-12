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

/** alias 字符集：字母数字 _ - 长度 2-32，与后端校验一致。 */
const ALIAS_RE = /^[A-Za-z0-9_-]{2,32}$/;
const copiedFlash = ref<'wallid' | null>(null);
let copiedFlashTimer: number | null = null;

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
    if (!ALIAS_RE.test(trimmed)) {
        aliasError.value = t.value.wall.aliasInvalid;
        return;
    }
    project.alias = trimmed; // optimistic
    ws.send('wall.alias', { alias: trimmed });
    editingAlias.value = false;
    aliasError.value = null;
}

// 监听服务端报错（ALIAS_TAKEN / INVALID_ALIAS_FORMAT）→ 回滚 + 错误提示
watch(() => net.lastError, (err) => {
    if (!err) return;
    if (err.includes('ALIAS_TAKEN')) {
        editingAlias.value = true;
        aliasError.value = t.value.wall.aliasInUse;
        nextTick(() => aliasInput.value?.focus());
    } else if (err.includes('INVALID_ALIAS_FORMAT')) {
        editingAlias.value = true;
        aliasError.value = t.value.wall.aliasInvalid;
        nextTick(() => aliasInput.value?.focus());
    }
});

function copyWallId() {
    if (!project.wallId) return;
    navigator.clipboard?.writeText(project.wallId).catch(() => {});
    if (copiedFlashTimer != null) window.clearTimeout(copiedFlashTimer);
    copiedFlash.value = 'wallid';
    copiedFlashTimer = window.setTimeout(() => {
        copiedFlash.value = null;
        copiedFlashTimer = null;
    }, 800);
}

const refreshing = ref(false);
const refreshFlash = ref<string | null>(null);
let refreshFlashTimer: number | null = null;

async function refreshWall() {
    if (!project.wallId || refreshing.value) return;
    refreshing.value = true;
    net.pushLog('meta', `requested wall.refresh for ${project.wallId}`);
    try {
        const ack = await ws.sendWithAck('wall.refresh', undefined, 8000);
        const p = (ack ?? {}) as { framesRespawned?: number; wallBlocksReplaced?: number };
        const frames = p.framesRespawned ?? 0;
        const blocks = p.wallBlocksReplaced ?? 0;
        showRefreshFlash(t.value.wall.refreshedDetail(frames, blocks));
    } catch (e) {
        const msg = (e as Error).message;
        if (msg === 'ack_timeout') {
            showRefreshFlash(t.value.wall.refreshTimeout);
        } else if (msg === 'send_failed') {
            showRefreshFlash(t.value.wall.refreshSendFailed);
        } else {
            showRefreshFlash(msg);
        }
    } finally {
        refreshing.value = false;
    }
}

function showRefreshFlash(msg: string) {
    refreshFlash.value = msg;
    if (refreshFlashTimer != null) window.clearTimeout(refreshFlashTimer);
    refreshFlashTimer = window.setTimeout(() => {
        refreshFlash.value = null;
        refreshFlashTimer = null;
    }, 3000);
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
          class="px-1.5 py-0.5 rounded font-mono bg-[color:var(--secondary)] hover:bg-[color:var(--accent)] transition-colors relative"
          :title="t.wall.copyId(project.wallId)"
          @click="copyWallId"
        >
          <span v-if="copiedFlash === 'wallid'" class="text-emerald-400">{{ t.wall.copied }}</span>
          <span v-else>{{ project.wallId }}</span>
        </button>
        <!-- alias：默认显示按钮；点击进入内联编辑 -->
        <button
          v-if="!editingAlias"
          class="flex items-center gap-1 px-1.5 py-0.5 rounded hover:bg-[color:var(--accent)] transition-colors text-[color:var(--muted-foreground)]"
          :title="project.alias ? t.wall.aliasSetTip(project.alias) : t.wall.aliasSetTipEmpty"
          @click="startAliasEdit"
        >
          <Tag class="size-3" />
          <span v-if="project.alias">{{ project.alias }}</span>
          <span v-else class="opacity-60">{{ t.wall.aliasEmpty }}</span>
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
            :placeholder="t.wall.aliasPlaceholder"
            @keydown.enter.prevent="commitAliasEdit"
            @keydown.escape.prevent="cancelAliasEdit"
            @blur="commitAliasEdit"
          />
          <button class="p-0.5 rounded hover:bg-emerald-500/20 text-emerald-400" :title="t.wall.aliasSave" @mousedown.prevent="commitAliasEdit">
            <Check class="size-3" />
          </button>
          <button class="p-0.5 rounded hover:bg-red-500/20 text-red-400" :title="t.wall.aliasCancel" @mousedown.prevent="cancelAliasEdit">
            <X class="size-3" />
          </button>
          <span v-if="aliasError" class="text-[10px] text-red-400 ml-1">{{ aliasError }}</span>
        </div>
        <button
          class="flex items-center gap-1 px-2 py-0.5 rounded text-xs transition-colors"
          :class="published
            ? 'bg-emerald-500/20 text-emerald-300 hover:bg-emerald-500/30'
            : 'bg-[color:var(--secondary)] text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)]'"
          :title="published ? t.wall.publishToggleOn : t.wall.publishToggleOff"
          @click="togglePublish"
        >
          <Globe class="size-3" />
          <span>{{ published ? t.wall.publishOn : t.wall.publishOff }}</span>
        </button>
        <button
          class="flex items-center gap-1 px-1.5 py-0.5 rounded text-xs text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)] transition-colors disabled:opacity-50"
          :title="t.wall.refreshTip"
          :disabled="refreshing"
          @click="refreshWall"
        >
          <RefreshCw class="size-3" :class="refreshing ? 'animate-spin' : ''" />
          <span>{{ t.wall.refresh }}</span>
        </button>
        <span
          v-if="refreshFlash"
          class="text-[10px] text-[color:var(--muted-foreground)] tabular-nums"
        >· {{ refreshFlash }}</span>
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
