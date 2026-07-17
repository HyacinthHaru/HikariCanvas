<script setup lang="ts">
/**
 * 积木库侧栏（配色 + palette 拖出新块）。
 *
 * <p>按 category 分组列出可拖出的动作积木（含 if）+ 「友好元素」分组（移到 / 改大小 /
 * 显示 / 隐藏 等）；每项 = 色点 + label，pointerdown 触发 {@code emit('paletteDown', kind, event)}
 * → 上层（ScriptEditorOverlay / BlockCanvas 持有的单一 useBlockDrag）调 {@code startPaletteDrag}。
 * 落画布时按 kind 调 {@code makeDefaultAction(kind)} 生成对应 action（friendly kind →
 * setElementProperties；nudgeElement → 独立 action）。</p>
 *
 * <p><b>触发器不在 palette</b>：每条规则一个帽子，帽子从「新建规则」来，不从 palette 拖。
 * lock 态（{@code project.isLocked}）→ 全部项禁拖（{@code pointer-events:none} + 视觉灰显），
 * 与 RightPanel lock 守卫同款。</p>
 *
 * <p>分组顺序：动作（blue）→ 时间轴（mauve playTimeline）→ 控制（green if）→ 危险（red
 * runCommand）→ 友好元素（blue）。配色读 BlockDef.colorVar（Catppuccin token），与画布上
 * 块同色，拖出去"颜色不变"心智一致。</p>
 *
 * <p><b>nudgeElement 去重</b>：它在 ACTION_DEFS 里 category=action，但<b>只在「友好元素」分组</b>
 * 出现（对用户是「移动一点」的元素操作）——故从「动作」分组里过滤掉，避免一个积木在 palette 出现两次。</p>
 */
import { computed } from 'vue';
import {
    ACTION_DEFS,
    FRIENDLY_ELEMENT_DEFS,
    FRIENDLY_PALETTE_KINDS,
    CATEGORY_COLOR_VAR,
    type BlockCategory,
} from '../model/blockDefs';
import { useProjectStore } from '@/stores/project';
import { useI18n } from '@/i18n';
import { resolveLabelKey } from './labelKey';

const emit = defineEmits<{
    /** palette 项 pointerdown：拖出 kind 对应的新块。 */
    (e: 'paletteDown', kind: string, ev: PointerEvent): void;
}>();

const project = useProjectStore();
const { t } = useI18n();

/** lock 态：palette 禁拖。 */
const locked = computed(() => project.isLocked);

/**
 * palette 分组：category → 该组的积木定义列表，按固定展示顺序。
 * 只列动作（action / timeline / control / danger）——trigger 不在 palette。
 * 「友好元素」（friendly）单独追加在常规分组之后（不在 category 枚举里，见下方 groups）。
 */
const PALETTE_GROUP_ORDER: BlockCategory[] = ['action', 'timeline', 'control', 'danger'];

/** 每个 category 的标题 i18n key。 */
const GROUP_LABEL_KEY: Record<BlockCategory, string> = {
    trigger: 'script.paletteGroup.trigger',
    action: 'script.paletteGroup.action',
    timeline: 'script.paletteGroup.timeline',
    control: 'script.paletteGroup.control',
    danger: 'script.paletteGroup.danger',
};

/**
 * 「友好元素」分组里通过 ACTION_DEFS 拖出但<b>不在常规 category 分组重复</b>的 kind。
 * {@code nudgeElement} 是 category=action 的 BlockDef，却只该在友好元素分组出现——
 * 从「动作」分组过滤掉它，避免一个积木在 palette 出现两次。
 */
const FRIENDLY_ONLY_ACTION_KINDS = new Set(['nudgeElement']);

/** 单个 palette 可拖项（统一模型：BlockDef 项与友好皮肤项都归一成此形）。 */
interface PaletteItem {
    kind: string;
    labelKey: string;
    /** CSS 变量名（含 {@code --} 前缀），决定色点 / 左条颜色。 */
    colorVar: string;
}

interface PaletteGroup {
    /** 分组键（category 名或 'friendly'）。 */
    key: string;
    titleKey: string;
    items: PaletteItem[];
}

/**
 * 按 PALETTE_GROUP_ORDER 把 ACTION_DEFS 分组（空组不显示）+ 末尾追加「友好元素」分组。
 * 友好元素分组遍历 {@link FRIENDLY_PALETTE_KINDS}：friendly kind 用 FRIENDLY_ELEMENT_DEFS
 * 的 labelKey（皮肤标题）；nudgeElement 用 ACTION_DEFS 的 labelKey（「移动一点」）。
 * 所有友好项统一着动作蓝（与画布上 friendly 块同色）。
 */
const groups = computed<PaletteGroup[]>(() => {
    const all = Object.values(ACTION_DEFS);
    const result: PaletteGroup[] = [];
    // 常规分组（动作里过滤掉 friendly-only 的 nudgeElement，防重复）。
    for (const cat of PALETTE_GROUP_ORDER) {
        const items = all
            .filter((d) => d.category === cat && !FRIENDLY_ONLY_ACTION_KINDS.has(d.kind))
            .map<PaletteItem>((d) => ({ kind: d.kind, labelKey: d.labelKey, colorVar: d.colorVar }));
        if (items.length === 0) continue;
        result.push({ key: cat, titleKey: GROUP_LABEL_KEY[cat], items });
    }
    // 友好元素分组：按 FRIENDLY_PALETTE_KINDS 顺序，统一动作蓝。
    const friendlyItems: PaletteItem[] = FRIENDLY_PALETTE_KINDS.map((kind) => ({
        kind,
        labelKey: friendlyLabelKey(kind),
        colorVar: CATEGORY_COLOR_VAR.action,
    }));
    if (friendlyItems.length > 0) {
        result.push({ key: 'friendly', titleKey: 'script.paletteGroup.friendly', items: friendlyItems });
    }
    return result;
});

/** 友好分组某 kind 的标题 key：friendly 皮肤优先（FRIENDLY_ELEMENT_DEFS），否则取 ACTION_DEFS（nudge）。 */
function friendlyLabelKey(kind: string): string {
    return FRIENDLY_ELEMENT_DEFS[kind]?.labelKey ?? ACTION_DEFS[kind]?.labelKey ?? kind;
}

function itemLabel(item: PaletteItem): string {
    return resolveLabelKey(t.value, item.labelKey);
}
function groupTitle(g: PaletteGroup): string {
    return resolveLabelKey(t.value, g.titleKey);
}

function onItemPointerDown(kind: string, e: PointerEvent): void {
    if (locked.value) return;
    // 只接管主键（左键 / 触摸 / 笔）；中键 / 右键不拖。
    if (e.button !== 0) return;
    emit('paletteDown', kind, e);
}
</script>

<template>
  <div class="hc-palette" :class="locked ? 'hc-palette-locked' : ''">
    <div v-for="g in groups" :key="g.key" class="hc-palette-group">
      <div class="hc-palette-group-title">{{ groupTitle(g) }}</div>
      <div class="hc-palette-items">
        <div
          v-for="item in g.items"
          :key="item.kind"
          class="hc-palette-item"
          :data-block-kind="item.kind"
          :style="{
            '--hc-pi-color': `var(${item.colorVar})`,
          }"
          :title="locked ? t.script.lockedHint : itemLabel(item)"
          @pointerdown="onItemPointerDown(item.kind, $event)"
        >
          <span class="hc-palette-dot" />
          <span class="hc-palette-label">{{ itemLabel(item) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * palette 条目与画布积木视觉语言统一。
 *
 * 设计：
 *   - 实色饱和底（~65% 分类色 mix card），与画布块同色语言；不再是 12% 淡灰底的 IDE 卡片。
 *   - 圆角 8px 与画布块 --bn-radius 一致。
 *   - 左侧色条改为宽色条（4px），与 border-radius 配合给"积木皮肤"感。
 *   - depth 阴影 + 顶部白高光 inset，与画布块立体感同节奏（轻化版，palette 更小）。
 *   - hover：translateY(-1px) 上浮 + brightness(1.06)，"积木被轻轻拿起"手感。
 *   - 文字：--hc-block-fg（全局 style.css 定义：浅主题 ctp-base 近白 / 深主题 ctp-crust），
 *     在饱和背景上对比清晰，与画布块标题同色系。
 *   - 色点：改为稍大 10px 方块 + 顶部白内描边，让"分类色 → 积木"的关联更直觉。
 */

.hc-palette {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
}
.hc-palette-locked {
    /* lock：整库禁拖 + 灰显（与 RightPanel lock 守卫观感一致） */
    opacity: 0.55;
    pointer-events: none;
}
.hc-palette-group {
    display: flex;
    flex-direction: column;
}
.hc-palette-group-title {
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted-foreground);
    padding: 0 0.25rem 0.25rem;
}
.hc-palette-items {
    display: flex;
    flex-direction: column;
    gap: 4px;
}
.hc-palette-item {
    /* 局部变量：分类色（模板内联 --hc-pi-color；缺省退边框灰） */
    --_c: var(--hc-pi-color, var(--border));

    display: flex;
    align-items: center;
    gap: 0.4rem;
    padding: 5px 8px 5px 10px;
    /* 实色饱和底：~65% 分类色 + card 中性底，在 palette 侧栏里既有 Scratch 颜色感又不刺眼 */
    background: color-mix(in srgb, var(--_c) 65%, var(--card));
    /* 左侧宽色条：4px 实色，与画布块 border-left 精神一致，皮肤感 */
    border-left: 4px solid var(--_c);
    /* 圆角 8px = 画布块 --bn-radius，统一节奏 */
    border-radius: 8px;
    cursor: grab;
    font-size: 0.8125rem;
    user-select: none;
    /* 轻立体：底部窄投影 + 顶部白高光 inset（轻化版，palette 条目比画布块小） */
    box-shadow:
        0 1px 0 color-mix(in srgb, var(--_c) 55%, #000),
        0 2px 5px rgba(0, 0, 0, 0.16),
        inset 0 1px 0 color-mix(in srgb, #fff 22%, transparent);
    /* hover 上浮 + 高亮一起过渡 */
    transition: filter 0.1s ease, transform 0.1s ease, box-shadow 0.1s ease;
}
.hc-palette-item:active {
    cursor: grabbing;
}
/* hover：上浮 1px + 亮度微增 + 阴影加深，「拿起积木」手感 */
.hc-palette-item:hover {
    filter: brightness(1.07);
    transform: translateY(-1px);
    box-shadow:
        0 2px 0 color-mix(in srgb, var(--_c) 55%, #000),
        0 4px 8px rgba(0, 0, 0, 0.22),
        inset 0 1px 0 color-mix(in srgb, #fff 24%, transparent);
}
/* 分类色色点：稍大 10px 方块 + 顶部白内描边（与块面高光同语言） */
.hc-palette-dot {
    width: 10px;
    height: 10px;
    border-radius: 3px;
    flex-shrink: 0;
    background: var(--_c);
    box-shadow: inset 0 1px 0 color-mix(in srgb, #fff 35%, transparent);
}
/* 文字用 --hc-block-fg（全局定义：浅主题 ≈ 白系 / 深主题 ≈ crust 深），
 * 在饱和彩色背景上对比清晰，与画布块标题同色系；缺省退 foreground 兜底 */
.hc-palette-label {
    color: var(--hc-block-fg, var(--foreground));
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
</style>
