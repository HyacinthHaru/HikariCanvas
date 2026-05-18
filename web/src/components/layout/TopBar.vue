<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { Sun, Moon, PanelLeft, PanelRight, Terminal, Languages, Tag, Lock, Unlock, Pencil, Check, X, RefreshCw, HelpCircle, Bookmark } from 'lucide-vue-next';
import SaveAsTemplateModal from '@/components/template/SaveAsTemplateModal.vue';
import SnapSettingsPopover from '@/components/layout/SnapSettingsPopover.vue';
import ThemeSwitcher from '@/components/layout/ThemeSwitcher.vue';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useI18n } from '@/i18n';
import { getWsClient } from '@/network/wsClient';
import Tooltip from '@/components/ui/Tooltip.vue';

const ui = useUiStore();
const net = useNetworkStore();
const project = useProjectStore();
const { t } = useI18n();
const ws = getWsClient();

// 2026-05-14 lock-state：published 概念砍 → lock 概念。仅 wall owner 可锁/解锁。
const locked = computed(() => project.isLocked);
const isOwner = computed(() => project.isOwner);

const editingAlias = ref(false);
const aliasDraft = ref('');
const aliasInput = ref<HTMLInputElement | null>(null);
const aliasError = ref<string | null>(null);

/** alias 字符集：字母数字 _ - 长度 2-32，与后端校验一致。 */
const ALIAS_RE = /^[A-Za-z0-9_-]{2,32}$/;
const copiedFlash = ref<'wallid' | null>(null);
let copiedFlashTimer: number | null = null;

const saveModalOpen = ref(false);

/**
 * M16 P6.8：lock / unlock 进行中的 promise。pending 时按钮 disabled；防止用户连点
 * 引起 lockedAt 在多次 ack 之间跳变。alias 同款用 aliasInFlight。
 */
const lockInFlight = ref(false);
const aliasInFlight = ref(false);

/**
 * M16 P6.8：optimistic mutation + rollback on server reject。
 *
 * 之前实现只 ws.send 不等 ack；server 拒绝（FORBIDDEN / VALIDATION / NOT_OWNER）时
 * 用户看到 UI 已锁但实际未锁，靠 watch(lastError) log 提示但不还原状态。现在 sendWithAck
 * 走 ack/error rejection → catch 回滚 lockedAt 到 prev 值并提示。
 */
async function toggleLock() {
    if (!project.wallId) return;
    if (!isOwner.value) return;  // 非 owner 按钮 disabled，理论上不应触发
    if (lockInFlight.value) return;  // 连击防护：pending 期间忽略
    const prev = project.lockedAt;
    const wasLocked = prev != null;
    // optimistic：先 mutate，server ack 会用真实值（含权威 lockedAt 时间戳）覆盖
    project.lockedAt = wasLocked ? null : Date.now();
    lockInFlight.value = true;
    try {
        await ws.sendWithAck(wasLocked ? 'wall.unlock' : 'wall.lock', undefined, 5000);
        // server ack 已通过 wsClient.handleAck 写入权威 lockedAt（见 wsClient.ts handleAck）
    } catch (err) {
        // rollback：恢复 prev
        project.lockedAt = prev;
        const msg = (err as Error).message;
        // 用 net.lastError 作为统一错误显示通道（已被 NetworkStatus / 日志面板订阅）
        net.lastError = wasLocked
            ? `${t.value.wall.unlockFailed}: ${msg}`
            : `${t.value.wall.lockFailed}: ${msg}`;
        net.pushLog('err', `lock toggle rejected: ${msg}`);
    } finally {
        lockInFlight.value = false;
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

async function commitAliasEdit() {
    if (!project.wallId) return;
    if (aliasInFlight.value) return;
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
    // M16 P6.8：optimistic + ack-driven rollback
    const prev = project.alias;
    project.alias = trimmed;
    editingAlias.value = false;
    aliasError.value = null;
    aliasInFlight.value = true;
    try {
        await ws.sendWithAck('wall.alias', { alias: trimmed }, 5000);
        // server ack 已通过 wsClient.handleAck 写入权威 alias
    } catch (err) {
        // rollback to prev
        project.alias = prev;
        const msg = (err as Error).message;
        // 按 server code 决定 UI：被占用 / 格式 → 回到 inline edit；其它（FORBIDDEN/timeout）
        // → 统一 lastError 提示
        if (msg.includes('ALIAS_TAKEN')) {
            aliasDraft.value = trimmed;
            editingAlias.value = true;
            aliasError.value = t.value.wall.aliasInUse;
            nextTick(() => aliasInput.value?.focus());
        } else if (msg.includes('INVALID_ALIAS_FORMAT')) {
            aliasDraft.value = trimmed;
            editingAlias.value = true;
            aliasError.value = t.value.wall.aliasInvalid;
            nextTick(() => aliasInput.value?.focus());
        } else {
            net.lastError = `${t.value.wall.aliasFailed}: ${msg}`;
            net.pushLog('err', `alias commit rejected: ${msg}`);
        }
    } finally {
        aliasInFlight.value = false;
    }
}

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
  <header class="flex items-center justify-between h-11 px-3 border-b border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none">
    <div class="flex items-center gap-3">
      <span class="text-sm font-semibold tracking-tight">{{ t.brand }}</span>
      <span class="text-xs text-[color:var(--muted-foreground)]">
        {{ net.serverVersion ? `server ${net.serverVersion}` : '' }}
      </span>
      <!-- M5.5: wall 元数据 -->
      <div v-if="project.wallId" class="flex items-center gap-2 ml-2 text-xs">
        <Tooltip :text="t.wall.copyId(project.wallId)">
          <button
            class="hc-btn px-2 py-1 rounded-[var(--radius-sm)] font-mono bg-[color:var(--secondary)] hover:bg-[color:var(--accent)] transition-colors relative"
            @click="copyWallId"
          >
            <span v-if="copiedFlash === 'wallid'" class="text-[color:var(--ctp-green)]">{{ t.wall.copied }}</span>
            <span v-else>{{ project.wallId }}</span>
          </button>
        </Tooltip>
        <!-- alias：默认显示按钮；点击进入内联编辑 -->
        <Tooltip v-if="!editingAlias" :text="project.alias ? t.wall.aliasSetTip(project.alias) : t.wall.aliasSetTipEmpty">
          <button
            class="hc-btn flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors text-[color:var(--muted-foreground)]"
            @click="startAliasEdit"
          >
            <Tag class="size-3" />
            <span v-if="project.alias">{{ project.alias }}</span>
            <span v-else class="opacity-60">{{ t.wall.aliasEmpty }}</span>
            <Pencil class="size-3 opacity-50" />
          </button>
        </Tooltip>
        <div v-else class="flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] bg-[color:var(--secondary)]">
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
          <button class="hc-btn p-0.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--ctp-green)]/20 text-[color:var(--ctp-green)]" :title="t.wall.aliasSave" @mousedown.prevent="commitAliasEdit">
            <Check class="size-3" />
          </button>
          <button class="hc-btn p-0.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--destructive)]/20 text-[color:var(--destructive)]" :title="t.wall.aliasCancel" @mousedown.prevent="cancelAliasEdit">
            <X class="size-3" />
          </button>
          <span v-if="aliasError" class="text-xs text-[color:var(--destructive)] ml-1">{{ aliasError }}</span>
        </div>
        <!-- 2026-05-14 lock 按钮：替换原 publish 按钮。owner 可点；非 owner disabled。 -->
        <Tooltip :text="locked
          ? (isOwner ? t.wall.lockToggleOff : t.wall.lockOwnerOnly)
          : (isOwner ? t.wall.lockToggleOn : t.wall.lockOwnerOnly)">
          <button
            class="hc-btn flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] text-xs transition-colors disabled:cursor-not-allowed disabled:opacity-60"
            :class="locked
              ? 'bg-[color:var(--ctp-peach)]/20 text-[color:var(--ctp-peach)] hover:bg-[color:var(--ctp-peach)]/30'
              : 'bg-[color:var(--secondary)] text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)]'"
            :disabled="!isOwner || lockInFlight"
            @click="toggleLock"
          >
            <Lock v-if="locked" class="size-3" />
            <Unlock v-else class="size-3" />
            <span>{{ locked ? t.wall.locked : t.wall.unlocked }}</span>
          </button>
        </Tooltip>
        <Tooltip :text="t.wall.refreshTip">
          <button
            class="hc-btn flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] text-xs text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)] transition-colors disabled:opacity-50"
            :disabled="refreshing"
            @click="refreshWall"
          >
            <RefreshCw class="size-3" :class="refreshing ? 'animate-spin' : ''" />
            <span>{{ t.wall.refresh }}</span>
          </button>
        </Tooltip>
        <span
          v-if="refreshFlash"
          class="text-xs text-[color:var(--muted-foreground)] tabular-nums"
        >· {{ refreshFlash }}</span>
      </div>
    </div>

    <div class="flex items-center gap-1">
      <Tooltip :text="t.topbar.toggleLeft">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.toggleLeft()"
        >
          <PanelLeft class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.topbar.toggleRight">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.toggleRight()"
        >
          <PanelRight class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.topbar.toggleLog">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.toggleLogDrawer()"
        >
          <Terminal class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.workshop.saveTip">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors disabled:opacity-40"
          :disabled="!project.wallId || project.isLocked"
          @click="saveModalOpen = true"
        >
          <Bookmark class="size-4" />
        </button>
      </Tooltip>
      <!-- M17.4 F3：Snap 设置 -->
      <SnapSettingsPopover />
      <Tooltip :text="t.topbar.help" shortcut="?">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.helpOpen = true"
        >
          <HelpCircle class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.topbar.switchLocale">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.toggleLocale()"
        >
          <Languages class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.topbar.toggleTheme">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.toggleTheme()"
        >
          <Sun v-if="ui.theme === 'dark'" class="size-4" />
          <Moon v-else class="size-4" />
        </button>
      </Tooltip>
      <!-- M24-B：主题切换器（preset / accent / radius） -->
      <ThemeSwitcher />
    </div>
  </header>

  <SaveAsTemplateModal v-if="saveModalOpen" @close="saveModalOpen = false" />
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
