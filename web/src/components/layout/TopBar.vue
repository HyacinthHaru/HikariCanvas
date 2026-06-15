<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { useResizeObserver } from '@vueuse/core';
import { Sun, Moon, PanelLeft, PanelRight, Terminal, Languages, Tag, Lock, Unlock, Pencil, Check, X, RefreshCw, HelpCircle, Bookmark, Variable, Train, TrainTrack, Film, Puzzle, Magnet } from 'lucide-vue-next';
import SaveAsTemplateModal from '@/components/template/SaveAsTemplateModal.vue';
import SnapSettingsPopover from '@/components/layout/SnapSettingsPopover.vue';
import ThemeSwitcher from '@/components/layout/ThemeSwitcher.vue';
import OverflowMenu from '@/components/layout/OverflowMenu.vue';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useTimelineStore } from '@/stores/timeline';
import { useI18n } from '@/i18n';
import { getWsClient } from '@/network/wsClient';
import Tooltip from '@/components/ui/Tooltip.vue';

const ui = useUiStore();
const net = useNetworkStore();
const project = useProjectStore();
const timeline = useTimelineStore();
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

// ---------------------------------------------------------------------------
// 0.7.4 响应式溢出菜单
// ---------------------------------------------------------------------------

/**
 * 右侧按钮区容器的 ref。useResizeObserver 监测其宽度，动态决定折叠几个低频按钮。
 *
 * 分级（按使用频率从高到低）：
 *   高频（永远常显）：PanelLeft / PanelRight / Film / Puzzle / Variable / Train
 *   低频（空间不足时依次折叠）：
 *     TrainTrack → Bookmark → SnapSettingsPopover → Terminal → HelpCircle → Languages → Sun/Moon + ThemeSwitcher
 *
 * 宽度断点（每个按钮约 32px，含 gap 1 = 4px，实测每按钮约 36px）：
 *   ≥ 420px：全部展开（14 项，含 ThemeSwitcher 宽约 36px，Languages 36px，Sun/Moon 36px，Help 36px，Terminal 36px，Snap 36px，Bookmark 36px，TrainTrack 36px，Train 36px，Variable 36px，Puzzle 36px，Film 36px，PanelRight 36px，PanelLeft 36px）
 *   < 420px：TrainTrack 折叠（13 项）
 *   < 384px：+ Bookmark 折叠（12 项）
 *   < 348px：+ SnapSettings 折叠（11 项）
 *   < 312px：+ Terminal 折叠（10 项）
 *   < 276px：+ Help 折叠（9 项）
 *   < 240px：+ Languages 折叠（8 项）
 *   < 204px：+ Sun/Moon + ThemeSwitcher 折叠（6 项，只剩高频 6 个）
 *
 * 每个低频项在 breakpoint 以下被折叠进 … 菜单；如有任何折叠则显示 … 按钮（约 36px）。
 */

const rightBarRef = ref<HTMLElement | null>(null);
const rightBarWidth = ref(9999); // 初始值大，保证首屏不提前折叠

// 阈值（px）：每条以下时对应低频项折叠
const BREAKPOINTS = {
    trainTrack: 420,
    bookmark:   380,
    snap:       340,
    terminal:   300,
    help:       260,
    languages:  220,
    theme:      180,  // Sun/Moon + ThemeSwitcher 同时折叠
} as const;

useResizeObserver(rightBarRef, (entries) => {
    const entry = entries[0];
    if (entry) rightBarWidth.value = entry.contentRect.width;
});

const showTrainTrack  = computed(() => rightBarWidth.value >= BREAKPOINTS.trainTrack);
const showBookmark    = computed(() => rightBarWidth.value >= BREAKPOINTS.bookmark);
const showSnap        = computed(() => rightBarWidth.value >= BREAKPOINTS.snap);
const showTerminal    = computed(() => rightBarWidth.value >= BREAKPOINTS.terminal);
const showHelp        = computed(() => rightBarWidth.value >= BREAKPOINTS.help);
const showLanguages   = computed(() => rightBarWidth.value >= BREAKPOINTS.languages);
const showTheme       = computed(() => rightBarWidth.value >= BREAKPOINTS.theme);
// … 按钮只在至少有一项被折叠时才显示
const showMoreButton  = computed(() =>
    !showTrainTrack.value ||
    !showBookmark.value ||
    !showSnap.value ||
    !showTerminal.value ||
    !showHelp.value ||
    !showLanguages.value ||
    !showTheme.value,
);
</script>

<template>
  <header class="flex items-center justify-between h-11 px-3 border-b border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none">
    <div class="flex items-center gap-3 min-w-0 flex-1 overflow-hidden">
      <span class="text-sm font-semibold tracking-tight shrink-0">{{ t.brand }}</span>
      <span class="text-xs text-[color:var(--muted-foreground)] shrink-0">
        {{ net.serverVersion ? `server ${net.serverVersion}` : '' }}
      </span>
      <!-- M5.5: wall 元数据 -->
      <div v-if="project.wallId" class="flex items-center gap-2 ml-2 text-xs min-w-0 overflow-hidden">
        <Tooltip :text="t.wall.copyId(project.wallId)">
          <button
            class="hc-btn px-2 py-1 rounded-[var(--radius-sm)] font-mono bg-[color:var(--secondary)] hover:bg-[color:var(--accent)] transition-colors relative shrink-0"
            @click="copyWallId"
          >
            <span v-if="copiedFlash === 'wallid'" class="text-[color:var(--ctp-green)]">{{ t.wall.copied }}</span>
            <span v-else>{{ project.wallId }}</span>
          </button>
        </Tooltip>
        <!-- alias：默认显示按钮；点击进入内联编辑 -->
        <Tooltip v-if="!editingAlias" :text="project.alias ? t.wall.aliasSetTip(project.alias) : t.wall.aliasSetTipEmpty">
          <button
            class="hc-btn flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors text-[color:var(--muted-foreground)] shrink-0"
            @click="startAliasEdit"
          >
            <Tag class="size-3" />
            <span v-if="project.alias">{{ project.alias }}</span>
            <span v-else class="opacity-60">{{ t.wall.aliasEmpty }}</span>
            <Pencil class="size-3 opacity-50" />
          </button>
        </Tooltip>
        <div v-else class="flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] bg-[color:var(--secondary)] shrink-0">
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
            class="hc-btn flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] text-xs transition-colors disabled:cursor-not-allowed disabled:opacity-60 shrink-0"
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
            class="hc-btn flex items-center gap-1 px-2 py-1 rounded-[var(--radius-sm)] text-xs text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)] transition-colors disabled:opacity-50 shrink-0"
            :disabled="refreshing"
            @click="refreshWall"
          >
            <RefreshCw class="size-3" :class="refreshing ? 'animate-spin' : ''" />
            <span>{{ t.wall.refresh }}</span>
          </button>
        </Tooltip>
        <span
          v-if="refreshFlash"
          class="text-xs text-[color:var(--muted-foreground)] tabular-nums shrink-0"
        >· {{ refreshFlash }}</span>
      </div>
    </div>

    <!-- 右侧按钮区：高频按钮常显，低频按钮在空间不足时折入 … 菜单 -->
    <div ref="rightBarRef" class="flex items-center gap-1 shrink-0 ml-2">
      <!-- ── 高频（永远显示）── -->
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
      <!-- 0.6 P4：时间轴（关键帧动画）AE 风底部 dock -->
      <Tooltip :text="t.topbar.timelineManager">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] transition-colors"
          :class="timeline.dockOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          @click="timeline.toggleDock()"
        >
          <Film class="size-4" />
        </button>
      </Tooltip>
      <!-- 0.7.0 P4：积木脚本编辑器（按条件做事）全屏 overlay -->
      <Tooltip :text="t.topbar.scriptEditor">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] transition-colors"
          :class="ui.scriptEditorOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          @click="ui.toggleScriptEditor()"
        >
          <Puzzle class="size-4" />
        </button>
      </Tooltip>
      <!-- 0.4.0-P2-G：变量管理面板触发 -->
      <Tooltip :text="t.topbar.variableManager">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] transition-colors"
          :class="ui.variablePanelOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          @click="ui.toggleVariablePanel()"
        >
          <Variable class="size-4" />
        </button>
      </Tooltip>
      <!-- 0.4.0-P3-L：列车时刻表管理 -->
      <Tooltip :text="t.topbar.scheduleManager">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] transition-colors"
          :class="ui.scheduleManagerOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          @click="ui.toggleScheduleManager()"
        >
          <Train class="size-4" />
        </button>
      </Tooltip>

      <!-- ── 低频（空间不足时折叠进 … 菜单）── -->

      <!-- 0.4.4：铁路网络管理（低频：建好线路后很少再打开） -->
      <Tooltip v-if="showTrainTrack" :text="t.topbar.railNetwork">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] transition-colors"
          :class="ui.railNetworkOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          @click="ui.toggleRailNetwork()"
        >
          <TrainTrack class="size-4" />
        </button>
      </Tooltip>
      <!-- 存为模板（低频：完成设计后偶尔用） -->
      <Tooltip v-if="showBookmark" :text="project.isLocked ? t.tooltips.disabledWhenLocked : t.tooltips.saveTemplate">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors disabled:opacity-40"
          :disabled="!project.wallId || project.isLocked"
          @click="saveModalOpen = true"
        >
          <Bookmark class="size-4" />
        </button>
      </Tooltip>
      <!-- M17.4 F3：Snap 设置（低频：配一次就不常动） -->
      <SnapSettingsPopover v-if="showSnap" />
      <!-- Terminal 日志（低频：调试用） -->
      <Tooltip v-if="showTerminal" :text="t.topbar.toggleLog">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.toggleLogDrawer()"
        >
          <Terminal class="size-4" />
        </button>
      </Tooltip>
      <!-- 帮助（低频：看完快捷键就关） -->
      <Tooltip v-if="showHelp" :text="t.topbar.help" shortcut="?">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.helpOpen = true"
        >
          <HelpCircle class="size-4" />
        </button>
      </Tooltip>
      <!-- 语言切换（低频：偶尔切一次） -->
      <Tooltip v-if="showLanguages" :text="t.topbar.switchLocale">
        <button
          class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="ui.toggleLocale()"
        >
          <Languages class="size-4" />
        </button>
      </Tooltip>
      <!-- 主题（低频：配好后很少改） -->
      <template v-if="showTheme">
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
      </template>

      <!-- ── 溢出菜单 … ── -->
      <OverflowMenu v-if="showMoreButton">
        <!-- 铁路网络 -->
        <button
          v-if="!showTrainTrack"
          class="hc-btn w-full flex items-center gap-2.5 px-3 py-2 hover:bg-[color:var(--accent)] transition-colors text-left"
          :class="ui.railNetworkOpen ? 'text-[color:var(--foreground)]' : 'text-[color:var(--card-foreground)]'"
          @click="ui.toggleRailNetwork()"
        >
          <TrainTrack class="size-4 shrink-0" />
          <span>{{ t.topbar.moreRailNetwork }}</span>
        </button>
        <!-- 存为模板 -->
        <button
          v-if="!showBookmark"
          class="hc-btn w-full flex items-center gap-2.5 px-3 py-2 hover:bg-[color:var(--accent)] transition-colors text-left disabled:opacity-40"
          :disabled="!project.wallId || project.isLocked"
          @click="saveModalOpen = true"
        >
          <Bookmark class="size-4 shrink-0" />
          <span>{{ t.topbar.moreTemplate }}</span>
        </button>
        <!-- 智能对齐设置（折叠时只提供开/关快捷切换；完整设置仍需空间充足时通过常显按钮打开） -->
        <button
          v-if="!showSnap"
          class="hc-btn w-full flex items-center gap-2.5 px-3 py-2 hover:bg-[color:var(--accent)] transition-colors text-left"
          :class="ui.snapEnabled ? 'text-[color:var(--foreground)]' : ''"
          @click="ui.snapEnabled = !ui.snapEnabled"
        >
          <Magnet class="size-4 shrink-0" />
          <span>{{ t.topbar.moreSnap }}</span>
          <span class="ml-auto text-[color:var(--muted-foreground)]">{{ ui.snapEnabled ? t.templates.on : t.templates.off }}</span>
        </button>
        <!-- 日志 -->
        <button
          v-if="!showTerminal"
          class="hc-btn w-full flex items-center gap-2.5 px-3 py-2 hover:bg-[color:var(--accent)] transition-colors text-left"
          @click="ui.toggleLogDrawer()"
        >
          <Terminal class="size-4 shrink-0" />
          <span>{{ t.topbar.moreLog }}</span>
        </button>
        <!-- 帮助 -->
        <button
          v-if="!showHelp"
          class="hc-btn w-full flex items-center gap-2.5 px-3 py-2 hover:bg-[color:var(--accent)] transition-colors text-left"
          @click="ui.helpOpen = true"
        >
          <HelpCircle class="size-4 shrink-0" />
          <span>{{ t.topbar.moreHelp }}</span>
        </button>
        <!-- 语言 -->
        <button
          v-if="!showLanguages"
          class="hc-btn w-full flex items-center gap-2.5 px-3 py-2 hover:bg-[color:var(--accent)] transition-colors text-left"
          @click="ui.toggleLocale()"
        >
          <Languages class="size-4 shrink-0" />
          <span>{{ t.topbar.moreSwitchLocale }}</span>
        </button>
        <!-- 主题（Sun/Moon + 调色盘 → 合并为一行切换深浅色；主题 picker 太复杂不内嵌，只放快捷切换） -->
        <button
          v-if="!showTheme"
          class="hc-btn w-full flex items-center gap-2.5 px-3 py-2 hover:bg-[color:var(--accent)] transition-colors text-left"
          @click="ui.toggleTheme()"
        >
          <Sun v-if="ui.theme === 'dark'" class="size-4 shrink-0" />
          <Moon v-else class="size-4 shrink-0" />
          <span>{{ t.topbar.moreTheme }}</span>
        </button>
      </OverflowMenu>
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
