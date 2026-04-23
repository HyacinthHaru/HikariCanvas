<script setup lang="ts">
import { Sun, Moon, PanelLeft, PanelRight, Terminal, Languages } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';

const ui = useUiStore();
const net = useNetworkStore();
const { t } = useI18n();
</script>

<template>
  <header class="flex items-center justify-between h-10 px-3 border-b border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none">
    <div class="flex items-center gap-3">
      <span class="text-sm font-semibold tracking-tight">{{ t.brand }}</span>
      <span class="text-xs text-[color:var(--muted-foreground)]">
        {{ net.serverVersion ? `server ${net.serverVersion}` : '' }}
      </span>
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
