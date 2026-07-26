<script setup lang="ts">
/**
 * 右栏总装配器。本身只管：
 * - 顶部 LayerPanel（不动）
 * - 笔刷工具激活时切到 BrushPanel（替代 Properties，不动）
 * - Properties 头部 + 多选 / 空选提示
 * - 按 element.type dispatch 到对应 properties/*Section 子组件
 * - 子组件 emit('update' | 'updateDebounced') → 这里统一走 sendUpdate / sendUpdateDebounced
 *   做 optimistic mutation + ws.send；保证对外 op 流与拆分前一致
 * - 底部 ElementListSection（不动）
 *
 * 拆分前 1076 行 god component。后续段还会继续往里塞东西，
 * 留薄壳让子组件迭代不影响其他段。
 */
import { computed, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { Sliders, Trash2 } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';
import LayerPanel from '@/components/layout/LayerPanel.vue';
import BrushPanel from '@/components/layout/BrushPanel.vue';
import PaintBucketPanel from '@/components/layout/PaintBucketPanel.vue';
import TransformSection from '@/components/properties/TransformSection.vue';
import TextElementSection from '@/components/properties/TextElementSection.vue';
import GeometricElementSection from '@/components/properties/GeometricElementSection.vue';
import ImageElementSection from '@/components/properties/ImageElementSection.vue';
import CanvasSettingsSection from '@/components/properties/CanvasSettingsSection.vue';
import ElementListSection from '@/components/properties/ElementListSection.vue';
import type { Element, TextElement, RectElement, CircleElement, ShapeElement, PathElement, ImageElement } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();

const selected = computed<Element | null>(() => {
    if (!ui.selectedElementId) return null;
    return project.elementById(ui.selectedElementId);
});
const isText = computed(() => selected.value?.type === 'text');
const isRect = computed(() => selected.value?.type === 'rect');
const isCircle = computed(() => selected.value?.type === 'circle');
const isShape = computed(() => selected.value?.type === 'shape');
const isPath = computed(() => selected.value?.type === 'path');
const isImage = computed(() => selected.value?.type === 'image');
/** M11-D：几何元素族（支持 fill / dither）。text / icon / image 不在内。 */
const isGeometric = computed(() => isRect.value || isCircle.value || isShape.value || isPath.value);

/** M8-F：是否多选（>= 2）。multi 时隐藏单选 UI，显示批量操作提示。 */
const isMulti = computed(() => ui.selectedCount >= 2);

/**
 * 待回滚的乐观更新：帧 id → 改之前的字段原值。
 *
 * <p>面板是"先改本地再发帧"。服务端拒收（{@code INVALID_PAYLOAD}，比如值超出
 * ElementValidator 的范围）时，本地那份改动此前<b>既不回滚也不重拉</b>：浏览器显示新值、
 * 游戏里还是旧值，双端一直分叉到下次全量快照，而错误只进了日志，用户全程无感。</p>
 *
 * <p>现在每帧记一份原值，收到同 id 的 INVALID_PAYLOAD 就把那几个字段还原回去，并把
 * 服务端的说明写进状态栏的提示位。上限之类的越界值已经在各 Section 里夹过一道，这里兜的是
 * 夹不住的情况（新字段、前后端范围不同步、后端加了新校验）。</p>
 */
const rollbackByOpId = new Map<string, { elementId: string; before: Record<string, unknown> }>();

/**
 * 画板锁定时把编辑控件整块设成 inert（点不动、Tab 也聚焦不到），滚动照旧。
 *
 * <p>以前是整栏 {@code pointer-events: none}：滚轮事件直接穿过去，锁定状态下图层面板和属性
 * 面板都滚不动——想只读看看反而看不全；而且 Tab 仍能聚焦到输入框，方向键改数值是<b>真发 op
 * 落库</b>的（按架构约定后端编辑 op 不看锁状态）。</p>
 *
 * <p>写成 {@code || undefined} 是为了未锁定时把整个属性删掉：inert 只要出现就生效，
 * {@code inert="false"} 一样是 inert。</p>
 */
const lockedInert = computed(() => project.isLocked || undefined);
/** 最多留这么多帧的原值：够覆盖"发出去到错误回来"的窗口，又不至于无限涨。 */
const MAX_ROLLBACK_ENTRIES = 32;

/**
 * 立即发送（用于 boolean / color / select 之类"定型"变更）。
 *
 * <p>接受显式 elementId 以防止跨元素串写：当子组件 emit 触发时由外层捕获当前 id，
 * 不在执行时重读 selected.value——避免 80ms 防抖 flush 时用户已切换到另一元素的竞态。</p>
 *
 * <p>发出前先把要改的字段原值记进 {@link rollbackByOpId}，被拒时能原样还原。</p>
 */
function sendUpdate(patch: Record<string, unknown>, elementId: string) {
    const el = project.elementById(elementId);
    if (!el) return;
    const record = el as unknown as Record<string, unknown>;
    // 先留一份原值，再做乐观更新
    const before: Record<string, unknown> = {};
    for (const k of Object.keys(patch)) before[k] = record[k];
    for (const [k, v] of Object.entries(patch)) record[k] = v;
    const opId = ws.send('element.update', { elementId, patch });
    if (opId === null) return; // socket 没开，压根没发出去
    if (rollbackByOpId.size >= MAX_ROLLBACK_ENTRIES) {
        const oldest = rollbackByOpId.keys().next();
        if (!oldest.done) rollbackByOpId.delete(oldest.value);
    }
    rollbackByOpId.set(opId, { elementId, before });
}

/**
 * 服务端拒了某一帧 → 把那一帧的乐观更新还原。只认自己发的帧 id，不会误撤别人的改动。
 * 非 INVALID_PAYLOAD（限流、会话关闭等）不回滚：那些是"没送到"，本地值本身并不违规，
 * 等服务端下一次快照对齐即可。
 */
watch(() => net.lastOpError, (err) => {
    if (!err || !err.opId) return;
    const entry = rollbackByOpId.get(err.opId);
    if (!entry) return;
    rollbackByOpId.delete(err.opId);
    if (err.code !== 'INVALID_PAYLOAD') return;
    const el = project.elementById(entry.elementId);
    if (!el) return;
    const record = el as unknown as Record<string, unknown>;
    for (const [k, v] of Object.entries(entry.before)) record[k] = v;
    // 让用户看见"这次改动没生效"，而不是默默地两边不一样
    net.lastError = err.message;
});

/**
 * 防抖包装：调用时捕获当前 selected 的 id，flush 时按该捕获 id 路由，
 * 即便 80ms 内用户切换了元素，改动也归属于触发时的元素（A 的输入写 A，不写 B）。
 */
const _sendUpdateDebouncedInner = useDebounceFn(
    (patch: Record<string, unknown>, capturedId: string) => sendUpdate(patch, capturedId),
    80,
);
function sendUpdateDebounced(patch: Record<string, unknown>) {
    const capturedId = selected.value?.id;
    if (!capturedId) return;
    _sendUpdateDebouncedInner(patch, capturedId);
}

/** 选中的这个元素现在能不能改（它自己没锁 + 所在图层没锁）。删除按钮据此禁用。 */
const selectedEditable = computed(() => !!selected.value && project.isElementEditable(selected.value.id));

function deleteSelected() {
    const el = selected.value;
    if (!el) return;
    // 锁定的元素不删。元素锁后端根本不看，前端不拦就等于没锁。
    if (!project.isElementEditable(el.id)) {
        net.lastError = t.value.elements.lockedSkipped(1);
        return;
    }
    ws.send('element.delete', { elementId: el.id });
    ui.selectElement(null);
}

/** M8-F：批量删除多选元素，逐个发 element.delete op。锁定的跳过并说明跳了几个。 */
function deleteMultiSelected(): void {
    if (ui.selectedCount === 0) return;
    const ids = Array.from(ui.selectedIds);
    const deletable = project.editableIds(ids);
    for (const id of deletable) {
        ws.send('element.delete', { elementId: id });
    }
    if (deletable.length < ids.length) {
        net.lastError = t.value.elements.lockedSkipped(ids.length - deletable.length);
    }
    ui.clearSelection();
}
</script>

<template>
  <aside class="w-72 bg-[color:var(--card)] border-l border-[color:var(--border)] flex flex-col"
         :class="{ 'hc-readonly-panel': project.isLocked }">
    <!-- lock-state：locked 时编辑控件整块 inert（点不动 / Tab 聚焦不到）+ opacity 60%，
         但仍可滚动查看；解锁路径只能走 TopBar Lock 按钮（owner 才可见）。 -->
    <!-- 图层面板（顶端，自身控制 max-h 40%）。 -->
    <LayerPanel :inert="lockedInert" />

    <!-- 笔刷工具激活时，下半 BrushPanel 替代 Properties；其他工具走 Properties 原路径 -->
    <BrushPanel v-if="ui.activeTool === 'brush'" :inert="lockedInert" />
    <!-- Live Paint：油漆桶工具激活时，下半 PaintBucketPanel 替代 Properties -->
    <PaintBucketPanel v-else-if="ui.activeTool === 'paint-bucket'" :inert="lockedInert" />

    <!-- Properties。滚动容器自己不设 inert，否则锁定时连滚都滚不了 -->
    <template v-else>
    <section class="flex-1 overflow-y-auto min-h-0">
      <header :inert="lockedInert" class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
        <Sliders class="size-3.5" />
        <span>{{ t.properties.header }}</span>
        <Tooltip v-if="selected" :text="t.properties.deleteTitle" shortcut="Del">
          <button
            class="ml-auto p-1 rounded hover:bg-[color:var(--destructive)] hover:text-[color:var(--destructive-foreground)] text-[color:var(--muted-foreground)] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent"
            :disabled="!selectedEditable"
            @click="deleteSelected"
          >
            <Trash2 class="size-3.5" />
          </button>
        </Tooltip>
        <Tooltip v-else-if="isMulti" :text="t.properties.deleteMultiTip(ui.selectedCount)" shortcut="Del">
          <button
            class="ml-auto p-1 rounded hover:bg-[color:var(--destructive)] hover:text-[color:var(--destructive-foreground)] text-[color:var(--muted-foreground)]"
            @click="deleteMultiSelected"
          >
            <Trash2 class="size-3.5" />
          </button>
        </Tooltip>
      </header>

      <div v-if="isMulti" :inert="lockedInert" class="p-3 space-y-2 text-xs">
        <div class="font-medium">{{ t.properties.multiSelected(ui.selectedCount) }}</div>
        <div class="text-xs text-[color:var(--muted-foreground)]">{{ t.properties.multiHint }}</div>
      </div>

      <div v-else-if="!selected" :inert="lockedInert" class="text-xs">
        <!-- 未选中元素时空 hint + 画板设置段 -->
        <div class="p-3 text-[color:var(--muted-foreground)]">
          {{ t.properties.empty }}
        </div>
        <CanvasSettingsSection />
      </div>

      <div v-else :inert="lockedInert" class="p-3 space-y-3 text-xs">
        <!-- 基本信息 -->
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">{{ t.properties.type }}</span>
          <span class="font-mono">{{ selected.type }}</span>
        </div>
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">{{ t.properties.id }}</span>
          <span class="font-mono text-xs truncate max-w-[140px]" :title="selected.id">
            {{ selected.id }}
          </span>
        </div>

        <!-- 位置 & 尺寸 + opacity + blendMode / renderMode（共通段） -->
        <TransformSection
          :element="selected"
          :locked="project.isLocked"
          @update="(patch) => sendUpdate(patch, selected!.id)"
          @update-debounced="sendUpdateDebounced"
        />

        <!-- 几何元素（rect / circle / shape / path）公用 fill + stroke + dither -->
        <GeometricElementSection
          v-if="isGeometric"
          :element="selected as RectElement | CircleElement | ShapeElement | PathElement"
          :locked="project.isLocked"
          @update="(patch) => sendUpdate(patch, selected!.id)"
          @update-debounced="sendUpdateDebounced"
        />

        <!-- Text 主段 + Effects 段 -->
        <TextElementSection
          v-if="isText"
          :element="selected as TextElement"
          :locked="project.isLocked"
          @update="(patch) => sendUpdate(patch, selected!.id)"
          @update-debounced="sendUpdateDebounced"
        />

        <!-- ImageElement 段 -->
        <ImageElementSection
          v-if="isImage"
          :element="selected as ImageElement"
          :locked="project.isLocked"
          @update="(patch) => sendUpdate(patch, selected!.id)"
        />
      </div>
    </section>

    <!-- Elements（当前活动层内的元素列表）-->
    <ElementListSection :inert="lockedInert" />
    </template> <!-- v-else 结束（Properties 块只在非 brush 工具显示） -->
  </aside>
</template>

<style scoped>
/* 锁定时给编辑控件降透明度，提示"现在改不了"。真正的拦截靠 inert 属性（见 lockedInert）：
   点不动、Tab 也聚焦不到，但仍然能滚动查看。
   以前这里写的是 pointer-events: none —— 滚轮事件会穿过去，锁定状态下面板根本滚不动，
   而且 Tab 依旧能聚焦，方向键改数值是真发 op 落库的。 */
.hc-readonly-panel [inert] {
    opacity: 0.6;
}
</style>
