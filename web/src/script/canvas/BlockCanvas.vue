<script setup lang="ts">
/**
 * 0.7.0-P4-C/D2：积木画布（无限画布 viewport + world）。
 *
 * <p>viewport（overflow hidden）内一个 world div，用 {@link useBlockCanvas} 的 worldStyle
 * 做 translate+scale。积木堆 `position:absolute` 定位在 world 坐标系。每条 ScriptRule →
 * 一个 {@link BlockStack}，坐标来自各规则 {@code blockLayout}（{@link parseBlockLayout}），
 * 缺坐标走 {@link autoLayout} 纵向排布兜底。</p>
 *
 * <p><b>D2 拖拽接入</b>：本组件持唯一的 {@link useBlockDrag} 实例（它握 canvasRef +
 * screenToWorld），把"拖块 / 移堆"两句柄 {@code provide} 给递归子组件（BlockNode / BlockStack
 * 注入后在 pointerdown 调）；palette 源经父级 overlay 转发到本组件 {@code defineExpose} 的
 * {@code startPaletteDrag}。移堆拖动中用 {@code drag.stackDragPos} 覆盖该堆坐标即时跟随；
 * 拖块 / palette 拖动中渲染<b>跟手浮层 + 吸附指示线</b>（Teleport 到 body，用 viewport 坐标
 * fixed 定位，绕开 world transform）。</p>
 *
 * <p>pan 触发：空格按住拖 / 中键拖 / 拖空白处；拖块 / 移堆进行中不启动 pan。缩放 = ctrl/meta +
 * wheel 以光标为锚。</p>
 */
import { computed, provide, ref } from 'vue';
import { useBlockCanvas } from './useBlockCanvas';
import { useBlockDrag } from './useBlockDrag';
import { BLOCK_DRAG_KEY } from './dragInjection';
import { BLOCK_HIGHLIGHT_KEY, type HighlightInject } from './highlightInjection';
import { useScriptStore } from '@/stores/scripts';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useI18n } from '@/i18n';
import { defFor } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import { parseBlockLayout, autoLayout, type BlockLayout } from '../model/serialize';
import BlockStack from './BlockStack.vue';
import ScriptVariableWatch from './ScriptVariableWatch.vue';
import DeleteDropZone from './DeleteDropZone.vue';

const props = defineProps<{
    /**
     * H：试跑高亮（result + detail map ref）。由 overlay 持有 stepper 更新后传入；本组件
     * provide 给递归子组件。缺省（其它挂载点 / smoke 不传）时不 provide，子组件走空 map 兜底。
     */
    highlight?: HighlightInject;
}>();

const viewportRef = ref<HTMLElement | null>(null);
const scripts = useScriptStore();
const editStore = useScriptEditStore();
const { t } = useI18n();

/** 空格按住态（pan 触发条件之一）。表单聚焦时不接管空格（留给输入）。 */
const spaceDown = ref(false);

const canvas = useBlockCanvas({
    viewportRef,
    isSpaceDown: () => spaceDown.value,
});

// D2：唯一拖拽实例（canvasRef = viewport 测量根；screenToWorld 给移堆换算 world 位移）。
const drag = useBlockDrag({
    canvasRef: viewportRef,
    screenToWorld: canvas.screenToWorld,
});

// 把"拖块 / 移堆"句柄 provide 给递归子组件（BlockNode / BlockStack 注入）。
provide(BLOCK_DRAG_KEY, {
    startBlockDrag: drag.startBlockDrag,
    startStackDrag: drag.startStackDrag,
});

// H：把试跑高亮 map provide 给递归子组件（缺省时不传，子组件走 inject 默认空 map）。
if (props.highlight) {
    provide(BLOCK_HIGHLIGHT_KEY, props.highlight);
}

// 暴露给父级：zoom% + reset 视图 + palette 源拖出入口。
defineExpose({
    zoom: canvas.zoom,
    resetView: canvas.resetView,
    startPaletteDrag: drag.startPaletteDrag,
});

/**
 * 渲染用的规则列表：<b>当前正在编辑的那条规则用 {@code editStore.workingCopy}（本地副本），
 * 其余规则用 {@code scripts.listSorted}（server 镜像）。</b>
 *
 * <p>0.7.0-P5 实测修复（渲染数据源主根因）：所有编辑（拖积木 setActions / 改字段 updateActionField /
 * 改触发器 setTrigger）都改 workingCopy，但要 800ms debounce 后才 save 回 server；空字段积木还会被
 * validator 拦着不 save → server 永不更新。若画布只读 server 镜像，新拖的积木 / 刚改的字段就要等
 * save 成功才显示，空字段积木则<b>永远不显示</b>（出现"拖了没反应 / 永远停在待完善"）。让被编辑的
 * 那条规则直读 workingCopy，编辑即时反映；workingCopy 为 null（没选规则）或非当前规则一律读 server。</p>
 *
 * <p><b>reactive 依赖</b>：computed 读 {@code editStore.workingCopy} + {@code selectedRuleId} 建立依赖
 * → setActions 等 immutable 替换 workingCopy 时本 computed 重算 → 对应 BlockStack 收到新 rule → 新积木 /
 * 改动立即渲染。</p>
 *
 * <p><b>不破坏拖堆跟手（根因 4 仍成立）</b>：本 computed 仍<b>不依赖 {@code drag.stackDragPos}</b>——移堆
 * 拖动<b>过程中</b>只改 stackDragPos（由 {@link liveStackPos} 在绑定级覆盖被拖堆 :x/:y），不改 workingCopy
 * → 本 computed 不重算 → 其余堆静止；松手才 setStackPos（改 workingCopy.blockLayout）触发一次重算。</p>
 */
const renderRules = computed(() => {
    const wc = editStore.workingCopy;
    const editingId = editStore.selectedRuleId;
    return scripts.listSorted.map((r) =>
        wc && editingId === r.id ? wc : r,
    );
});

/**
 * 每条规则在画布上的<b>静态基坐标</b>：先各自解析 {@code rule.blockLayout} 取 {@code stacks[rule.id]}，
 * 缺坐标的规则统一交给 {@link autoLayout} 纵向排布兜底（按渲染顺序）。规则对象来自 {@link renderRules}
 * （被编辑规则 = workingCopy / 其余 = server 镜像）。
 */
const basePositionedStacks = computed(() => {
    const rules = renderRules.value;
    const explicit: BlockLayout = { stacks: {} };
    for (const rule of rules) {
        const layout = parseBlockLayout(rule.blockLayout);
        const coord = layout.stacks[rule.id];
        if (coord) explicit.stacks[rule.id] = coord;
    }
    const merged = autoLayout(rules.map((r) => r.id), explicit);
    return rules.map((rule) => {
        const c = merged.stacks[rule.id] ?? { x: 40, y: 40 };
        return { rule, x: c.x, y: c.y };
    });
});

/**
 * 取某条规则当前应渲染的坐标：移堆拖动中且就是被拖堆 → 用 {@code drag.stackDragPos} 实时坐标
 * （让它即时跟随指针，松手才提交 setStackPos）；否则用静态基坐标。
 *
 * <p>在模板 :x/:y 绑定里调用——它只读 {@code drag.stackDragPos.value}（reactive），故拖动中仅
 * "被拖堆的绑定结果"会变化，其余堆返回与上帧相同的基坐标对象值 → Vue 不 patch（根因 4 核心）。</p>
 */
function liveStackPos(entry: { rule: { id: string }; x: number; y: number }): { x: number; y: number } {
    const live = drag.stackDragPos.value;
    if (live && live.ruleId === entry.rule.id) {
        return { x: live.x, y: live.y };
    }
    return { x: entry.x, y: entry.y };
}

/** 跟手浮层显示的块标题（palette / block 源的 kind → label）。 */
const ghostLabel = computed(() => {
    const g = drag.ghost.value;
    if (!g) return '';
    const def = defFor(g.kind);
    return def ? resolveLabelKey(t.value, def.labelKey) : g.kind;
});

/** 跟手浮层色条颜色。 */
const ghostColor = computed(() => {
    const g = drag.ghost.value;
    if (!g) return 'var(--border)';
    const def = defFor(g.kind);
    return def ? `var(${def.colorVar})` : 'var(--border)';
});

function isFormTarget(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    return !!el && (el.matches?.('input, textarea, select') || el.isContentEditable);
}

function onKeyDown(e: KeyboardEvent): void {
    if (e.code === 'Space' && !isFormTarget(e.target)) {
        spaceDown.value = true;
    }
}
function onKeyUp(e: KeyboardEvent): void {
    if (e.code === 'Space') spaceDown.value = false;
}

function onPointerDown(e: PointerEvent): void {
    // 拖块 / 移堆进行中不启动 pan（理论上 capture 已握住 pointer，这里再加一道守卫）。
    if (drag.isDraggingBlock.value || drag.isDraggingStack.value) return;
    // viewport 空白处都可 pan（积木堆 / 帽子 / 块的 pointerdown 已 stopPropagation，不冒泡到这）。
    canvas.onPanPointerDown(e, true);
}
</script>

<template>
  <div
    ref="viewportRef"
    class="hc-block-viewport"
    tabindex="0"
    :class="canvas.isPanning.value ? 'cursor-grabbing' : 'cursor-grab'"
    @wheel="canvas.onWheel"
    @pointerdown="onPointerDown"
    @pointermove="canvas.onPanPointerMove"
    @pointerup="canvas.onPanPointerUp"
    @pointercancel="canvas.onPanPointerCancel"
    @keydown="onKeyDown"
    @keyup="onKeyUp"
  >
    <div class="hc-block-world" :style="canvas.worldStyle.value">
      <!-- 每条 ScriptRule → 一个积木堆。基坐标稳定（不依赖拖动），:x/:y 经 liveStackPos
           只对被拖堆做实时覆盖 → 拖动时仅被拖堆 patch，其余堆静止（根因 4）。 -->
      <BlockStack
        v-for="entry in basePositionedStacks"
        :key="entry.rule.id"
        :rule="entry.rule"
        :x="liveStackPos(entry).x"
        :y="liveStackPos(entry).y"
        @pointerdown.stop
      />
    </div>

    <!-- 0.7.1：拖动作积木时的「删除区」垃圾桶（viewport 内 absolute，不随缩放/平移）。
         几何与 useBlockDrag.computeDeleteZoneRect 同源对齐。 -->
    <DeleteDropZone :active="drag.deleteZoneActive.value" :hot="drag.deleteZoneHot.value" />

    <!-- 0.7.1：右下角「本脚本变量实时预览」常驻面板（读当前编辑规则涉及的变量 + 实时值）。 -->
    <ScriptVariableWatch />
  </div>

  <!-- 吸附指示线（命中插槽时，viewport 坐标 fixed 定位）。Teleport 到 body 绕开 world transform。 -->
  <Teleport to="body">
    <!-- 槽位高亮：极淡半透明矩形点亮「会插这里」，z-index 介于块和拖影之间。 -->
    <div
      v-if="drag.activeSlot.value"
      class="hc-drop-slot-highlight"
      :style="{
        left: `${drag.activeSlot.value.x}px`,
        top: `${drag.activeSlot.value.y}px`,
        width: `${drag.activeSlot.value.w}px`,
        height: `${drag.activeSlot.value.h}px`,
      }"
    />
    <!-- 插入线：渲染在槽位中线，带端点圆头伪元素（Scratch 风醒目插入线）。 -->
    <div
      v-if="drag.activeSlot.value"
      class="hc-drop-indicator"
      :style="{
        left: `${drag.activeSlot.value.x}px`,
        top: `${drag.activeSlot.value.y + drag.activeSlot.value.h / 2}px`,
        width: `${drag.activeSlot.value.w}px`,
      }"
    />
    <!-- 跟手浮层（palette / block 源拖动中）。用 transform 定位（GPU 合成，拖动中不触发 layout
         reflow）；left/top 固定 0，每帧只改 transform（根因 4：让浮层跟手不卡）。
         实色块语言：background 填 ghostColor（分类色），文字用 --hc-block-fg（白系）。 -->
    <div
      v-if="drag.ghost.value"
      class="hc-drag-ghost"
      :style="{
        transform: `translate3d(${drag.ghost.value.x}px, ${drag.ghost.value.y}px, 0)`,
        background: ghostColor,
      }"
    >
      <span class="hc-drag-ghost-label">{{ ghostLabel }}</span>
    </div>
  </Teleport>
</template>

<style scoped>
.hc-block-viewport {
    position: relative;
    width: 100%;
    height: 100%;
    overflow: hidden;
    outline: none;
    /* 棋盘点阵背景提示"无限画布"，缩放时不跟随（始终对齐 viewport）。 */
    background-color: var(--background);
    background-image: radial-gradient(circle, color-mix(in srgb, var(--border) 60%, transparent) 1px, transparent 1px);
    background-size: 20px 20px;
}
.hc-block-world {
    position: absolute;
    top: 0;
    left: 0;
    width: 0;
    height: 0;
    will-change: transform;
}
/* Teleport 到 body 的浮层用全局类（scoped 下需 :deep 或全局；这里用 fixed + 独立类名避免冲突）。 */

/* 槽位高亮：z-index 69（块之上、插入线之下），极淡不喧宾夺主。 */
.hc-drop-slot-highlight {
    position: fixed;
    border-radius: 8px;
    background: color-mix(in srgb, var(--ctp-blue, var(--primary)) 12%, transparent);
    border: 1px solid color-mix(in srgb, var(--ctp-blue, var(--primary)) 30%, transparent);
    pointer-events: none;
    z-index: 69;
}

/* 插入线：4px 主线 + 端点圆头（::before / ::after 各一个圆点）+ 更强发光。 */
.hc-drop-indicator {
    position: fixed;
    height: 4px;
    border-radius: 2px;
    background: var(--ctp-sky, var(--ctp-blue, var(--primary)));
    box-shadow:
        0 0 0 1px color-mix(in srgb, var(--ctp-sky, var(--ctp-blue, var(--primary))) 40%, transparent),
        0 0 10px 2px color-mix(in srgb, var(--ctp-sky, var(--ctp-blue, var(--primary))) 55%, transparent);
    pointer-events: none;
    z-index: 70;
    transform: translateY(-50%);
}
/* 端点圆头：左圆点 */
.hc-drop-indicator::before,
.hc-drop-indicator::after {
    content: '';
    position: absolute;
    top: 50%;
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--ctp-sky, var(--ctp-blue, var(--primary)));
    transform: translateY(-50%);
    box-shadow: 0 0 6px 1px color-mix(in srgb, var(--ctp-sky, var(--ctp-blue, var(--primary))) 60%, transparent);
}
.hc-drop-indicator::before { left: -3px; }
.hc-drop-indicator::after  { right: -3px; }

/* 拖影：实色积木语言——background 填分类色（ghostColor），圆角/阴影与块同手感。 */
.hc-drag-ghost {
    position: fixed;
    /* transform 定位的原点：固定在视口左上，由 :style 的 translate3d 偏移到指针处。 */
    left: 0;
    top: 0;
    display: inline-flex;
    align-items: center;
    padding: 0.3rem 0.65rem;
    border-radius: 8px;
    /* background 由 :style 的 ghostColor 填充（分类色），此处为初始占位防闪。 */
    background: var(--border);
    /* 顶部 inset 白高光（与块同语言）。 */
    box-shadow:
        inset 0 1px 0 rgba(255, 255, 255, 0.22),
        0 6px 16px rgba(0, 0, 0, 0.32),
        0 2px 6px rgba(0, 0, 0, 0.18);
    pointer-events: none;
    z-index: 71;
    /* 略透表示「拖动中」，不是完全实体。 */
    opacity: 0.9;
    font-size: 0.8125rem;
    font-weight: 600;
    white-space: nowrap;
    will-change: transform;
}
.hc-drag-ghost-label {
    /* 实色饱和底上用白系文字 + 轻暗描边，与块标题同手感（复用 --hc-block-fg）。 */
    color: var(--hc-block-fg, #fff);
    text-shadow: 0 1px 2px rgba(0, 0, 0, 0.35);
}
</style>
