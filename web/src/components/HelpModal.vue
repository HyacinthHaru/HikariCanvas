<script setup lang="ts">
import { X, Keyboard, MousePointer2, Move, Layers, Sparkles, ZoomIn, Minus, MoveRight, Circle, Star } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import { useUiStore } from '@/stores/ui';

const { t } = useI18n();
const ui = useUiStore();
</script>

<template>
  <div
    v-if="ui.helpOpen"
    class="fixed inset-0 z-50 flex items-center justify-center bg-[color:var(--ctp-crust)]/70"
    @click.self="ui.helpOpen = false"
  >
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] w-[min(640px,92vw)] max-h-[88vh] flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center px-4 h-11 border-b border-[color:var(--border)] gap-2">
        <Keyboard class="size-4" />
        <h2 class="text-sm font-medium">{{ t.help.header }}</h2>
        <button
          class="hc-btn ml-auto p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]"
          @click="ui.helpOpen = false"
        >
          <X class="size-4" />
        </button>
      </header>

      <div class="overflow-y-auto p-5 space-y-5 text-xs">
        <!-- 工具切换 -->
        <section>
          <h3 class="text-xs font-semibold uppercase tracking-wider text-[color:var(--muted-foreground)] mb-2 flex items-center gap-1.5">
            <MousePointer2 class="size-3.5" />
            {{ t.help.groupTools }}
          </h3>
          <ul class="space-y-1.5">
            <li class="flex items-center justify-between">
              <span class="flex items-center gap-2">
                <MousePointer2 class="size-3.5" /> {{ t.help.selectTool }}
              </span>
              <kbd class="hc-kbd-help">V</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span class="flex items-center gap-2">
                <Move class="size-3.5" /> {{ t.help.moveTool }}
              </span>
              <kbd class="hc-kbd-help">M</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span class="flex items-center gap-2">
                <Minus class="size-3.5" /> {{ t.help.lineTool }}
              </span>
              <kbd class="hc-kbd-help">L</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span class="flex items-center gap-2">
                <MoveRight class="size-3.5" /> {{ t.help.arrowTool }}
              </span>
              <kbd class="hc-kbd-help">A</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span class="flex items-center gap-2">
                <Circle class="size-3.5" /> {{ t.help.circleTool }}
              </span>
              <kbd class="hc-kbd-help">C</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span class="flex items-center gap-2">
                <Star class="size-3.5" /> {{ t.help.starTool }}
              </span>
              <kbd class="hc-kbd-help">S</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.openTemplates }}</span>
              <Sparkles class="size-3.5 text-[color:var(--muted-foreground)]" />
            </li>
          </ul>
        </section>

        <!-- 选择 / 编辑 -->
        <section>
          <h3 class="text-xs font-semibold uppercase tracking-wider text-[color:var(--muted-foreground)] mb-2 flex items-center gap-1.5">
            <Layers class="size-3.5" />
            {{ t.help.groupEdit }}
          </h3>
          <ul class="space-y-1.5">
            <li class="flex items-center justify-between">
              <span>{{ t.help.selectClick }}</span>
              <kbd class="hc-kbd-help">{{ t.help.mouseLeftClick }}</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.editText }}</span>
              <kbd class="hc-kbd-help">{{ t.help.mouseDoubleClick }}</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.deselect }}</span>
              <kbd class="hc-kbd-help">Esc</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.deselectAll }}</span>
              <kbd class="hc-kbd-help">{{ t.help.dblClickBlank }}</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.deleteElement }}</span>
              <kbd class="hc-kbd-help">Del</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.moveByPixel }}</span>
              <kbd class="hc-kbd-help">←↑↓→</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.moveByTen }}</span>
              <kbd class="hc-kbd-help">⇧ + ←↑↓→</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.undo }}</span>
              <kbd class="hc-kbd-help">Ctrl+Z</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.redo }}</span>
              <kbd class="hc-kbd-help">Ctrl+⇧Z / Y</kbd>
            </li>
          </ul>
        </section>

        <!-- 缩放 / 视图 -->
        <section>
          <h3 class="text-xs font-semibold uppercase tracking-wider text-[color:var(--muted-foreground)] mb-2 flex items-center gap-1.5">
            <ZoomIn class="size-3.5" />
            {{ t.help.groupView }}
          </h3>
          <ul class="space-y-1.5">
            <li class="flex items-center justify-between">
              <span>{{ t.help.zoomCanvas }}</span>
              <kbd class="hc-kbd-help">Ctrl + {{ t.help.scrollWheel }}</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.zoomIn }}</span>
              <kbd class="hc-kbd-help">Ctrl+=</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.zoomOut }}</span>
              <kbd class="hc-kbd-help">Ctrl+-</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.zoomReset }}</span>
              <kbd class="hc-kbd-help">Ctrl+0</kbd>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ t.help.panCanvas }}</span>
              <kbd class="hc-kbd-help">{{ t.help.midMouseOrAlt }}</kbd>
            </li>
          </ul>
        </section>

        <!-- 游戏命令小提示 -->
        <section>
          <h3 class="text-xs font-semibold uppercase tracking-wider text-[color:var(--muted-foreground)] mb-2">
            {{ t.help.groupInGame }}
          </h3>
          <ul class="space-y-1.5 text-xs text-[color:var(--muted-foreground)] font-mono">
            <li>{{ t.help.cmdEdit }}</li>
            <li>{{ t.help.cmdOpen }}</li>
            <li>{{ t.help.cmdList }}</li>
            <li>{{ t.help.cmdDelete }}</li>
          </ul>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-kbd-help {
    padding: 2px 7px;
    border-radius: 4px;
    background: var(--ctp-surface0);
    color: var(--foreground);
    border: 1px solid var(--border);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 10.5px;
    box-shadow: 0 1px 0 var(--border);
}
</style>
