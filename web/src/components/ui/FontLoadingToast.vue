<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { onFontLoadStart, onFontLoadEnd } from '@/render/FontLoader';
import { useI18n } from '@/i18n';

/**
 * 字体加载中的右下角轻提示。
 *
 * <p>换字体走 {@code GET /api/font/file}，中文字体动辄十几 MB。网速慢时画布会先用
 * 系统 fallback 顶着画，用户看到的是"点了没反应 / 字长得不对"，无从判断是卡住了还是
 * 已经好了。这条提示把那段静默窗口显式化。</p>
 *
 * <p>两个时间常量决定它不烦人：</p>
 * <ul>
 *   <li>{@code SHOW_DELAY_MS} —— 命中 HTTP 缓存的加载往往几十毫秒就完事，
 *       直接弹会变成每次切字体都闪一下。等这么久还没好才弹。</li>
 *   <li>{@code MIN_VISIBLE_MS} —— 弹出来之后至少留这么久，否则刚好卡在阈值附近的
 *       加载会"闪现一帧"，比不提示更让人困惑。</li>
 * </ul>
 *
 * <p>挂在 App 根层用 fixed 定位：它不属于画布内容，不该随画布滚动，
 * 也不该被 RightPanel / TimelineDock 的布局变化推走。</p>
 */

const { t } = useI18n();

/** 慢到这个程度才值得提示（毫秒）。 */
const SHOW_DELAY_MS = 200;
/** 一旦提示出来，至少显示这么久（毫秒）——作者要的"一秒左右"。 */
const MIN_VISIBLE_MS = 1000;

const visible = ref(false);

/** 正在真实加载的 fontId。用 Set 而非计数：同一 id 的重复 end 不会把计数减穿。 */
const pending = new Set<string>();
let showTimer: ReturnType<typeof setTimeout> | null = null;
let hideTimer: ReturnType<typeof setTimeout> | null = null;
let shownAt = 0;

function clearShowTimer() {
    if (showTimer !== null) { clearTimeout(showTimer); showTimer = null; }
}
function clearHideTimer() {
    if (hideTimer !== null) { clearTimeout(hideTimer); hideTimer = null; }
}

const stopStart = onFontLoadStart((fontId) => {
    pending.add(fontId);
    // 已经在显示 / 已经在等着显示，就不重复排队（多字体并发只算一次）。
    if (visible.value || showTimer !== null) {
        clearHideTimer();
        return;
    }
    showTimer = setTimeout(() => {
        showTimer = null;
        if (pending.size === 0) return; // 等待期间已全部加载完 → 一次都不弹
        visible.value = true;
        shownAt = Date.now();
    }, SHOW_DELAY_MS);
});

const stopEnd = onFontLoadEnd((fontId) => {
    pending.delete(fontId);
    if (pending.size > 0) return; // 还有别的字体在加载，继续显示

    clearShowTimer(); // 还没弹出来就已经加载完 → 取消这次提示
    if (!visible.value) return;

    const rest = MIN_VISIBLE_MS - (Date.now() - shownAt);
    clearHideTimer();
    if (rest <= 0) {
        visible.value = false;
    } else {
        hideTimer = setTimeout(() => { hideTimer = null; visible.value = false; }, rest);
    }
});

onBeforeUnmount(() => {
    stopStart();
    stopEnd();
    clearShowTimer();
    clearHideTimer();
});
</script>

<template>
  <Transition
    enter-active-class="transition-opacity duration-150"
    leave-active-class="transition-opacity duration-300"
    enter-from-class="opacity-0"
    leave-to-class="opacity-0"
  >
    <div
      v-if="visible"
      class="fixed bottom-8 right-4 z-50 px-2.5 py-1.5 rounded-[var(--radius-sm)] text-xs bg-[color:var(--card)] border border-[color:var(--border)] text-[color:var(--muted-foreground)] shadow-md pointer-events-none flex items-center gap-1.5"
      role="status"
      aria-live="polite"
    >
      <span class="inline-block size-2 rounded-full bg-[color:var(--ctp-blue)] animate-pulse"></span>
      {{ t.canvas.fontLoading }}
    </div>
  </Transition>
</template>
