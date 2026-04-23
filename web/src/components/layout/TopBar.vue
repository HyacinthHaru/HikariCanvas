<script setup lang="ts">
import { Sun, Moon, PanelLeft, PanelRight, Terminal } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';

const ui = useUiStore();
const net = useNetworkStore();
</script>

<template>
  <header class="flex items-center justify-between h-10 px-3 border-b border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none">
    <div class="flex items-center gap-3">
      <span class="text-sm font-semibold tracking-tight">HikariCanvas</span>
      <span class="text-xs text-[color:var(--muted-foreground)]">
        {{ net.serverVersion ? `server ${net.serverVersion}` : '' }}
      </span>
    </div>

    <div class="flex items-center gap-1">
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="ui.leftCollapsed ? '展开左侧工具栏' : '折叠左侧工具栏'"
        @click="ui.toggleLeft()"
      >
        <PanelLeft class="size-4" />
      </button>
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="ui.rightCollapsed ? '展开右侧属性栏' : '折叠右侧属性栏'"
        @click="ui.toggleRight()"
      >
        <PanelRight class="size-4" />
      </button>
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="ui.logDrawerOpen ? '关闭日志抽屉' : '打开日志抽屉'"
        @click="ui.toggleLogDrawer()"
      >
        <Terminal class="size-4" />
      </button>
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] transition-colors"
        :title="ui.theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
        @click="ui.toggleTheme()"
      >
        <Sun v-if="ui.theme === 'dark'" class="size-4" />
        <Moon v-else class="size-4" />
      </button>
    </div>
  </header>
</template>
