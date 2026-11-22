/**
 * 时间轴底部 dock（`TimelineDock`）的可测纯逻辑。
 *
 * 把所有不依赖 Vue 响应式 / DOM / WS 的计算集中到本文件，供单元测试直接覆盖，组件只负责状态绑定与发包。
 * 数据结构与契约依据 `docs/protocol.md §7` 与 `docs/timeline.md §2`，类型对应 `types/protocol.ts` 的 `Timeline` / `Keyframe`。
 *
 * 包含属性可用性、默认值、时间与像素映射、标尺刻度、帧吸附、整体关键帧聚合、拖动期覆盖与框选命中——全为无副作用纯函数。
 */
import type { Easing, Element, Keyframe, LoopMode, ProjectState, Timeline, TriggerConfig, TriggerType } from '@/types/protocol';

/** 关键帧可加的数值属性白名单（与 `docs/timeline.md §4.2` 数值类对齐）。 */
export const KEYFRAMEABLE_PROPERTIES = ['x', 'y', 'w', 'h', 'rotation', 'opacity'] as const;
export type KeyframeProperty = typeof KEYFRAMEABLE_PROPERTIES[number];

/** loopMode 候选集（i18n 文案在 messages.ts，逻辑层只认裸值）。 */
export const LOOP_MODES: LoopMode[] = ['once', 'loop', 'pingPong'];

/**
 * 触发方式候选（i18n 文案在 messages.ts）。
 * - manual：玩家 / 命令 / 编辑器播放（+ wall ready 自动播 LOOP 的默认行为）
 * - variableChange：绑定变量变化时播
 * - schedule：到点播（绑定一个 `schedule:*` 变量的 variableChange 语义标签）
 */
export const TRIGGER_TYPES: TriggerType[] = ['manual', 'variableChange', 'schedule'];

/** 非 manual 触发需要绑定一个变量（trigger.params.fullName）。 */
export function triggerNeedsVariable(type: string): boolean {
    return type === 'variableChange' || type === 'schedule';
}

/** 构造 trigger（manual -> 空 params；variableChange/schedule -> {fullName}）。 */
export function buildTrigger(type: TriggerType, fullName: string | null | undefined): TriggerConfig {
    if (!triggerNeedsVariable(type)) return { type: 'manual', params: {} };
    return { type, params: { fullName: (fullName ?? '').trim() } };
}

/**
 * fill 轨适用的元素类型（与渲染侧 FILLABLE_TYPES / 后端 withAnimated 字段适用性对齐）。
 * image 仅数值轨（mask / source 不做关键帧）。
 */
const FILLABLE_TYPES = new Set(['rect', 'icon', 'path', 'circle', 'shape', 'brush']);

/**
 * 给定选中元素能加关键帧的属性列表（`docs/rendering.md §9.2` 逐类型规则）：
 * - 全类型：数值六属性 x/y/w/h/rotation/opacity
 * - text：追加 color（颜色轨）+ text（离散轨）
 * - rect/icon/path/circle/shape/brush：有填充时追加 fill（Fill 轨）
 * - image：仅数值（无 color/text/fill 轨）
 *
 * @param element 选中元素；null 时仅返回数值六属性
 */
export function keyframeablePropertiesFor(element: Element | null | undefined): string[] {
    const props: string[] = [...KEYFRAMEABLE_PROPERTIES];
    if (!element) return props;
    if (element.type === 'text') {
        props.push('color', 'text');
    }
    if (FILLABLE_TYPES.has(element.type)) {
        // 元素当前无填充（如空心框）时不放行 fill 轨，避免静默注入默认实心填充
        const fill = (element as { fill?: unknown }).fill;
        if (fill != null) props.push('fill');
    }
    return props;
}

/**
 * 取选中元素某数值属性的当前值：
 * - opacity 缺省（element 未显式设）-> 1（与后端 effectiveOpacity 默认一致）
 * - rotation / x / y / w / h 缺省 -> 0
 * - 元素不存在 / 属性非数值 -> 0 兜底
 */
export function defaultValueFor(
    element: Element | null | undefined,
    property: KeyframeProperty,
): number {
    if (property === 'opacity') {
        const v = element?.opacity;
        return typeof v === 'number' && Number.isFinite(v) ? v : 1;
    }
    const raw = element ? (element as unknown as Record<string, unknown>)[property] : undefined;
    return typeof raw === 'number' && Number.isFinite(raw) ? raw : 0;
}

// ──────────────────────────────────────────────────────────
//  时间 <-> 像素映射与标尺刻度
// ──────────────────────────────────────────────────────────

/** 时间(ms) -> 标尺 X 像素。`scrollMs` = 标尺左缘对应时刻（横滚），`pxPerMs` = 缩放。 */
export function msToPx(timeMs: number, pxPerMs: number, scrollMs = 0): number {
    return (timeMs - scrollMs) * pxPerMs;
}

/** 标尺 X 像素 -> 时间(ms)（`msToPx` 逆；pxPerMs <= 0 兜底返 scrollMs）。 */
export function pxToMs(px: number, pxPerMs: number, scrollMs = 0): number {
    return pxPerMs <= 0 ? scrollMs : px / pxPerMs + scrollMs;
}

/** 每帧时长(ms)。fps <= 0 兜底 20fps。 */
export function frameMs(fps: number): number {
    return 1000 / (fps > 0 ? fps : 20);
}

/** 钳时刻到 [0, durationMs]（取整）。 */
export function clampTimeMs(timeMs: number, durationMs: number): number {
    const t = Math.round(timeMs);
    if (t < 0) return 0;
    if (t > durationMs) return durationMs;
    return t;
}

/** 把时刻吸附到最近整帧边界（按 fps），再钳到 [0, durationMs]。 */
export function snapToFrame(timeMs: number, fps: number, durationMs: number): number {
    const fm = frameMs(fps);
    return clampTimeMs(Math.round(timeMs / fm) * fm, durationMs);
}

/** 标尺刻度。`major` = 主刻度（带标签）。 */
export interface RulerTick {
    timeMs: number;
    x: number;
    label: string;
    major: boolean;
}

/** 取 >= raw 的整洁步长：1/2/5 * 10^n（避免难读刻度）。 */
function niceStep(raw: number): number {
    if (raw <= 0) return 1;
    const pow = Math.pow(10, Math.floor(Math.log10(raw)));
    const norm = raw / pow;                          // [1, 10)
    const nice = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
    return Math.max(1, nice * pow);
}

/** 时刻标签：>= 1000ms 用 s（去尾 0），否则 ms。标尺刻度位置静态，去尾 0 更易读。 */
export function formatTimeLabel(timeMs: number): string {
    if (timeMs >= 1000) {
        const s = timeMs / 1000;
        return (Number.isInteger(s) ? String(s) : String(Number(s.toFixed(2)))) + 's';
    }
    return Math.round(timeMs) + 'ms';
}

/**
 * 固定宽度时钟 `m:ss.mmm`（播放头读数用）。播放期每帧变化，秒与毫秒一律零填充定宽，
 * 配合 `tabular-nums` 避免去尾 0 或单位切换导致数字抖动。
 */
export function formatClock(timeMs: number): string {
    const t = Math.max(0, Math.round(timeMs));
    const totalSec = Math.floor(t / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    const millis = t % 1000;
    return `${min}:${String(sec).padStart(2, '0')}.${String(millis).padStart(3, '0')}`;
}

/**
 * 计算时间标尺主刻度：在可见宽度内按整洁间隔（1/2/5*10^n ms，目标间距约 80px）布主刻度（带标签），
 * 不超过 `durationMs`。
 */
export function computeRulerTicks(
    durationMs: number,
    pxPerMs: number,
    widthPx: number,
    scrollMs = 0,
): RulerTick[] {
    if (pxPerMs <= 0 || widthPx <= 0 || durationMs <= 0) return [];
    const step = niceStep(80 / pxPerMs);
    const ticks: RulerTick[] = [];
    const startMs = Math.max(0, Math.floor(scrollMs / step) * step);
    const endMs = Math.min(durationMs, scrollMs + widthPx / pxPerMs);
    for (let t = startMs; t <= endMs + 0.5 && t <= durationMs; t += step) {
        const tm = Math.round(t);
        ticks.push({ timeMs: tm, x: msToPx(tm, pxPerMs, scrollMs), label: formatTimeLabel(tm), major: true });
    }
    return ticks;
}

// ──────────────────────────────────────────────────────────
//  整体关键帧（transform 几何变换聚合）
// ──────────────────────────────────────────────────────────

/** 整体关键帧：某元素某 timeMs 的一组 transform 属性关键帧 + 统一缓动。 */
export interface TransformKeyframe {
    elementId: string;
    timeMs: number;
    /** 该 timeMs 该元素的 transform 关键帧 id 列表（批量移动 / 删除 / 缓动同步用）。 */
    keyframeIds: string[];
    /** 统一缓动（整体帧缓动同步到所有 transform 属性；取该组第一个关键帧的）。 */
    easing: Easing;
    /** 该 timeMs 实际有关键帧的 transform 属性（< 6 表示部分属性尚未建轨）。 */
    properties: string[];
}

/** 整体关键帧的稳定 key（选中 / 比较用）。 */
export function transformKeyframeKey(elementId: string, timeMs: number): string {
    return `${elementId}:${timeMs}`;
}

/**
 * 把某元素的 transform（`KEYFRAMEABLE_PROPERTIES` 六属性）关键帧按 timeMs 聚合成整体关键帧列表（timeMs 升序）。
 * 元素行直接操作整体帧，缓动统一应用到该时刻所有 transform 属性，使 x/y 进度同步、运动轨迹保持直线。
 * color/text/fill 不计入整体帧（在展开的子轨中展示）。
 */
export function aggregateTransformKeyframes(
    timeline: Timeline | null | undefined,
    elementId: string,
): TransformKeyframe[] {
    const kfs = timeline?.tracks?.[elementId] ?? [];
    const transformSet = new Set<string>(KEYFRAMEABLE_PROPERTIES);
    const byTime = new Map<number, Keyframe[]>();
    for (const k of kfs) {
        if (!transformSet.has(k.property)) continue;
        if (!byTime.has(k.timeMs)) byTime.set(k.timeMs, []);
        byTime.get(k.timeMs)!.push(k);
    }
    return [...byTime.keys()].sort((a, b) => a - b).map(timeMs => {
        const group = byTime.get(timeMs)!;
        return {
            elementId,
            timeMs,
            keyframeIds: group.map(k => k.id),
            easing: group[0].easing,
            properties: group.map(k => k.property),
        };
    });
}

// ──────────────────────────────────────────────────────────
//  拖画布元素自动加帧：加帧计划 + 拖动期跟手覆盖
// ──────────────────────────────────────────────────────────

/** 元素一组 transform 几何值快照（拖动期跟手覆盖用）。 */
export interface TransformSnapshot {
    x: number;
    y: number;
    w: number;
    h: number;
    rotation: number;
    opacity: number;
}

/**
 * 拖动期把正在拖的元素的实时几何值覆盖到插值结果上，保证预览态下被拖元素跟手。
 * 不可变重建：只复制含覆盖元素的 layer，其余 layer / element 保持原引用，不污染传入的 base state。
 */
export function applyDragOverride(
    state: ProjectState | null,
    overrides: Map<string, TransformSnapshot>,
): ProjectState | null {
    if (!state || overrides.size === 0) return state;
    let any = false;
    const layers = state.layers.map(layer => {
        let replaced: Element[] | null = null;
        layer.elements.forEach((el, i) => {
            const ov = overrides.get(el.id);
            if (!ov) return;
            if (!replaced) replaced = [...layer.elements];
            replaced[i] = { ...el, x: ov.x, y: ov.y, w: ov.w, h: ov.h, rotation: ov.rotation, opacity: ov.opacity } as Element;
            any = true;
        });
        return replaced ? { ...layer, elements: replaced } : layer;
    });
    return any ? { ...state, layers } : state;
}

/** 整体帧 upsert 计划：timeMs 已有该属性帧 -> update（带 keyframeId）；缺的属性 -> add 新帧。 */
export interface TransformUpsertPlan {
    timeMs: number;
    adds: { property: string; value: number }[];
    updates: { keyframeId: string; property: string; value: number }[];
}

/**
 * 计算在 `timeMs` 给元素打一个整体关键帧的操作计划：遍历 6 个 transform 属性，取元素当前值
 * （`defaultValueFor`）；该属性在 `timeMs` 已有帧则更新其值，否则新增关键帧。
 *
 * 同一时刻若有重复帧，取最后一个匹配项（与 `timeline/interpolation.ts` 的 `spanAt` 取值规则一致）。
 */
export function planTransformUpsert(
    timeline: Timeline | null | undefined,
    element: Element,
    timeMs: number,
): TransformUpsertPlan {
    const existing = timeline?.tracks?.[element.id] ?? [];
    const adds: { property: string; value: number }[] = [];
    const updates: { keyframeId: string; property: string; value: number }[] = [];
    for (const property of KEYFRAMEABLE_PROPERTIES) {
        const value = defaultValueFor(element, property);
        let ex: Keyframe | undefined;
        for (const k of existing) {
            if (k.property === property && k.timeMs === timeMs) ex = k;   // 取最后一个
        }
        if (ex) updates.push({ keyframeId: ex.id, property, value });
        else adds.push({ property, value });
    }
    return { timeMs, adds, updates };
}

/** 框选矩形（轨道内容坐标系，px；含 scrollTop，未归一化——x0/y0 起点、x1/y1 当前点）。 */
export interface MarqueeRect {
    x0: number;
    y0: number;
    x1: number;
    y1: number;
}

/** dock 框选用的最简行视图（只需 kind / elementId / 整体帧块 timeMs）。 */
export interface MarqueeRowView {
    kind: string;
    elementId: string;
    groups?: { timeMs: number }[];
}

/**
 * 计算框选矩形命中的整体帧 key 列表（拉框选中后支持批量删除）。rows 按渲染顺序、每行高 `rowH`；
 * 只考虑 element 行的整体帧块，块中心 `x = msToPx(timeMs)`、`y = 行中线`；中心落在归一化矩形内即命中。
 */
export function groupsInMarquee(
    rows: MarqueeRowView[],
    rect: MarqueeRect,
    pxPerMs: number,
    scrollMs: number,
    rowH: number,
): string[] {
    const xMin = Math.min(rect.x0, rect.x1);
    const xMax = Math.max(rect.x0, rect.x1);
    const yMin = Math.min(rect.y0, rect.y1);
    const yMax = Math.max(rect.y0, rect.y1);
    const keys: string[] = [];
    rows.forEach((row, i) => {
        if (row.kind !== 'element' || !row.groups) return;
        const yc = i * rowH + rowH / 2;
        if (yc < yMin || yc > yMax) return;
        for (const g of row.groups) {
            const xc = msToPx(g.timeMs, pxPerMs, scrollMs);
            if (xc >= xMin && xc <= xMax) keys.push(transformKeyframeKey(row.elementId, g.timeMs));
        }
    });
    return keys;
}
