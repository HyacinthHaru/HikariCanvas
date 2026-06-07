/**
 * 0.6 时间轴面板的可测纯逻辑（P2 引入，P4 扩展 AE dock 时间映射）。
 *
 * <p>{@code TimelineDock}（P4，替代 P2 已删的最简 modal）把所有"不依赖 Vue 响应式 / DOM / WS"
 * 的计算抽到本文件，单测（{@code __tests__/timelineLogic.test.ts}）直接 node 跑覆盖，组件只做
 * 绑定 + 发包。形态依据 docs/protocol.md §7 + docs/timeline.md §2，类型镜像
 * {@code types/protocol.ts} 的 {@link Timeline} / {@link Keyframe}。</p>
 *
 * <p>含分组展示 / 表单校验 / 默认值（P2）+ AE dock 的时间↔像素映射 / 二级属性子轨拆分 /
 * 标尺刻度 / 帧吸附（P4）—— 全是无副作用纯函数。</p>
 */
import type { Easing, EasingType, Element, Fill, KfValue, Keyframe, LoopMode, ProjectState, Timeline } from '@/types/protocol';

/** 关键帧可加的属性白名单（数值类，MVP 范围）。与 docs/timeline.md §4.2 数值类对齐。 */
export const KEYFRAMEABLE_PROPERTIES = ['x', 'y', 'w', 'h', 'rotation', 'opacity'] as const;
export type KeyframeProperty = typeof KEYFRAMEABLE_PROPERTIES[number];

/** loopMode 候选集（i18n 文案在 messages.ts，逻辑层只认裸值）。 */
export const LOOP_MODES: LoopMode[] = ['once', 'loop', 'pingPong'];

/**
 * 缓动方式候选集（P3 modal 缓动下拉用；i18n 文案在 messages.ts）。
 * cubicBezier 不在 MVP 下拉（需控制点四元 UI，留 P4）；这 4 个覆盖常用「匀速 / 由慢到快 /
 * 由快到慢 / 两头慢中间快」，与 rendering.md §9.3 预设对齐。
 */
export const EASING_TYPES = ['linear', 'easeIn', 'easeOut', 'easeInOut'] as const;
export type EasingPreset = typeof EASING_TYPES[number];

/**
 * fill 轨适用的元素类型（与渲染侧 FILLABLE_TYPES / 后端 withAnimated 字段适用性对齐）。
 * image 仅数值轨（mask / source 不做关键帧）。
 */
const FILLABLE_TYPES = new Set(['rect', 'icon', 'path', 'circle', 'shape', 'brush']);

/**
 * 给定选中元素能加关键帧的属性列表（P3 扩展，rendering.md §9.2 逐类型规则）：
 * - 全类型：数值六属性 x/y/w/h/rotation/opacity
 * - text：追加 color（颜色轨）+ text（离散轨）
 * - rect/icon/path/circle/shape/brush：追加 fill（Fill 轨）
 * - image：仅数值（无 color/text/fill 轨）
 *
 * @param element 选中元素；null → 仅数值六属性（无元素上下文时的安全默认）
 */
export function keyframeablePropertiesFor(element: Element | null | undefined): string[] {
    const props: string[] = [...KEYFRAMEABLE_PROPERTIES];
    if (!element) return props;
    if (element.type === 'text') {
        props.push('color', 'text');
    }
    if (FILLABLE_TYPES.has(element.type)) {
        // P3 审查 #9：元素当前无填充（空心框等）时不放行 fill 轨——「无填充就不能给
        // 填充加关键帧」，否则会静默注入白色实心填充
        const fill = (element as { fill?: unknown }).fill;
        if (fill != null) props.push('fill');
    }
    return props;
}

/** 新建表单的字段默认值（MVP 决策：durationMs 5000 / fps 20 / loop / 名字空 → 后端补默认）。 */
export const CREATE_FORM_DEFAULTS = {
    name: '',
    durationMs: 5000,
    fps: 20,
    loopMode: 'loop' as LoopMode,
};

/** 一条按 elementId 分组的关键帧轨。{@code keyframes} 已按 (property, timeMs) 排序。 */
export interface ElementTrack {
    elementId: string;
    /** 若能从 project store 反查到元素，则带其 type（如 "text"），否则 null（孤儿轨）。 */
    elementType: string | null;
    keyframes: Keyframe[];
}

/**
 * 把 {@code timeline.tracks}（{@code Map<elementId, Keyframe[]>}）整理成按 elementId 升序、
 * 每组关键帧按 (property, timeMs) 升序的列表，供 modal 区块 3 渲染。
 *
 * @param timeline 选中的时间轴；null / 无 tracks → 返空数组
 * @param resolveElementType 反查 elementId → type 的回调（通常包 project.elementById）；
 *        返 null 表示该元素已不存在（孤儿轨，仍展示但 type 标 null）
 */
export function groupKeyframesByElement(
    timeline: Timeline | null | undefined,
    resolveElementType: (elementId: string) => string | null,
): ElementTrack[] {
    if (!timeline || !timeline.tracks) return [];
    const elementIds = Object.keys(timeline.tracks).sort();
    const out: ElementTrack[] = [];
    for (const elementId of elementIds) {
        const list = timeline.tracks[elementId] ?? [];
        // 不原地改 store 数组：复制后排序。先 property 字典序、再 timeMs，稳定可读。
        const keyframes = list.slice().sort((a, b) => {
            if (a.property !== b.property) return a.property < b.property ? -1 : 1;
            return a.timeMs - b.timeMs;
        });
        out.push({
            elementId,
            elementType: resolveElementType(elementId),
            keyframes,
        });
    }
    return out;
}

/**
 * 关键帧 value 的展示文本（modal 区块 3 行内）：
 * - number：原样（去尾随 0，避免 1.5000000001 这类浮点噪声短展示）
 * - string：原样（含 {@code ${var:X}} 模板）
 * - Fill / 其他对象：返该对象的 {@code type} 字段（如 "solid" / "linear"）或 "fill" 兜底
 *
 * 这是只读展示，不参与发包，故容错而非严格。
 */
export function formatKeyframeValue(value: unknown): string {
    if (typeof value === 'number') {
        // 整数直出；小数保留至多 3 位有效尾数后去尾 0。
        if (Number.isInteger(value)) return String(value);
        return String(Number(value.toFixed(3)));
    }
    if (typeof value === 'string') return value;
    if (value && typeof value === 'object') {
        const t = (value as { type?: unknown }).type;
        return typeof t === 'string' ? t : 'fill';
    }
    return String(value ?? '');
}

/**
 * 取选中元素某属性的当前值，作为"加关键帧"表单的默认 value（docs/timeline.md MVP）。
 * - opacity 缺省（element 未显式设）→ 1（与后端 effectiveOpacity 默认一致）
 * - rotation / x / y / w / h 缺省 → 0
 * - 元素不存在 / 属性非数值 → 0 兜底
 *
 * @param element 选中元素；null → 返该属性的协议默认
 * @param property 取值属性（白名单内）
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

/**
 * 取选中元素某属性的当前值作为「加关键帧」表单默认 {@link KfValue}（P3 扩展含非数值轨）：
 * - color：{@code element.color ?? '#000000'}（字符串轨）
 * - text：{@code element.text ?? ''}（离散字符串轨）
 * - fill：{@code element.fill ?? {type:'solid',color:'#FFFFFF'}}；元素 fill 为旧 string
 *   形态时包成 SolidFill（与 protocol FillCompat 归一化一致）
 * - 数值六属性：沿用 {@link defaultValueFor}（返 number）
 *
 * @returns property 类别决定 number / string / Fill；未知属性按数值兜底
 */
export function defaultValueForExtended(
    element: Element | null | undefined,
    property: string,
): KfValue {
    if (property === 'color') {
        const v = element && element.type === 'text' ? element.color : undefined;
        return typeof v === 'string' && v.length > 0 ? v : '#000000';
    }
    if (property === 'text') {
        const v = element && element.type === 'text' ? element.text : undefined;
        return typeof v === 'string' ? v : '';
    }
    if (property === 'fill') {
        const raw = element ? (element as unknown as Record<string, unknown>).fill : undefined;
        if (typeof raw === 'string' && raw.length > 0) {
            return { type: 'solid', color: raw } as Fill;   // 旧 string 形态 → SolidFill
        }
        if (raw && typeof raw === 'object' && 'type' in raw) {
            return raw as Fill;
        }
        return { type: 'solid', color: '#FFFFFF' } as Fill;
    }
    return defaultValueFor(element, property as KeyframeProperty);
}

/** 新建时间轴表单的输入态（modal 区块 2 v-model 绑定）。 */
export interface CreateFormInput {
    name: string;
    durationMs: number;
    fps: number;
    loopMode: LoopMode;
}

/** 校验结果。{@code ok=false} 时 {@code field} 指向第一个出错字段，{@code reason} 是 i18n key。 */
export type CreateFormValidation =
    | { ok: true }
    | { ok: false; field: 'durationMs' | 'fps' | 'loopMode'; reason: string };

/**
 * 校验新建时间轴表单。name 允许空（后端补默认名，见 protocol.md §5.12），故不校验 name。
 * 数值边界与后端 TimelineOperations 对齐的客户端预检（最终以后端为权威）：
 * - durationMs：正整数，[1, 600000]（10 分钟上限，防误填天文数字撑爆 UI 标尺）
 * - fps：正整数，[1, 240]（后端再按 config max-fps 钳，此处只挡明显非法）
 * - loopMode：必须在 {@link LOOP_MODES} 内
 *
 * @returns reason 是 i18n key 名（如 'durationPositive'），调用方查 t.timeline[reason]
 */
export function validateCreateForm(input: CreateFormInput): CreateFormValidation {
    if (!Number.isInteger(input.durationMs) || input.durationMs < 1) {
        return { ok: false, field: 'durationMs', reason: 'errDurationPositive' };
    }
    if (input.durationMs > 600000) {
        return { ok: false, field: 'durationMs', reason: 'errDurationTooLong' };
    }
    if (!Number.isInteger(input.fps) || input.fps < 1) {
        return { ok: false, field: 'fps', reason: 'errFpsPositive' };
    }
    if (input.fps > 240) {
        return { ok: false, field: 'fps', reason: 'errFpsTooHigh' };
    }
    if (!LOOP_MODES.includes(input.loopMode)) {
        return { ok: false, field: 'loopMode', reason: 'errLoopMode' };
    }
    return { ok: true };
}

/** 校验"加关键帧"表单的 timeMs 是否落在 [0, durationMs] 内（INVALID_KEYFRAME_TIME 客户端预检）。 */
export function isValidKeyframeTime(timeMs: number, durationMs: number): boolean {
    return Number.isInteger(timeMs) && timeMs >= 0 && timeMs <= durationMs;
}

/** elementId 短形态（"e-1a2b3c4d" → "1a2b3c4d"；无前缀则原样），用于分组标题省空间。 */
export function shortElementId(elementId: string): string {
    return elementId.startsWith('e-') ? elementId.slice(2) : elementId;
}

// ──────────────────────────────────────────────────────────
//  P4：AE 风 dock 的时间↔像素映射 + 二级 (elementId, property) 属性子轨拆分（纯函数，可测）
// ──────────────────────────────────────────────────────────

/** 一条按 (elementId, property) 拆出的属性子轨。{@code keyframes} 已按 timeMs 升序。 */
export interface PropertyTrack {
    property: string;
    keyframes: Keyframe[];
}

/** 一个元素的轨道组：主行 + 其下每属性一条子轨（AE 风左侧属性树）。 */
export interface ElementTrackGroup {
    elementId: string;
    elementType: string | null;
    /** 该元素所有属性子轨，按 property 字典序。 */
    properties: PropertyTrack[];
}

/**
 * 把 {@code timeline.tracks}（每元素所有属性混排）拆成 (elementId, property) 二级结构，供 AE dock
 * 左侧属性树 + 右侧轨道行渲染（docs/timeline.md §2.2 方案 B）。elementId 升序、property 字典序、
 * 子轨内 timeMs 升序。{@link groupKeyframesByElement} 的二级版（旧 modal 用一级，dock 用二级）。
 */
export function splitTracksByProperty(
    timeline: Timeline | null | undefined,
    resolveElementType: (elementId: string) => string | null,
): ElementTrackGroup[] {
    if (!timeline || !timeline.tracks) return [];
    const out: ElementTrackGroup[] = [];
    for (const elementId of Object.keys(timeline.tracks).sort()) {
        const list = timeline.tracks[elementId] ?? [];
        const byProp = new Map<string, Keyframe[]>();
        for (const kf of list) {
            if (!byProp.has(kf.property)) byProp.set(kf.property, []);
            byProp.get(kf.property)!.push(kf);
        }
        const properties: PropertyTrack[] = [];
        for (const property of [...byProp.keys()].sort()) {
            const keyframes = byProp.get(property)!.slice().sort((a, b) => a.timeMs - b.timeMs);
            properties.push({ property, keyframes });
        }
        out.push({ elementId, elementType: resolveElementType(elementId), properties });
    }
    return out;
}

/** 时间(ms) → 标尺 X 像素。{@code scrollMs} = 标尺左缘对应时刻（横滚），{@code pxPerMs} = 缩放。 */
export function msToPx(timeMs: number, pxPerMs: number, scrollMs = 0): number {
    return (timeMs - scrollMs) * pxPerMs;
}

/** 标尺 X 像素 → 时间(ms)（{@link msToPx} 逆；pxPerMs≤0 兜底返 scrollMs）。 */
export function pxToMs(px: number, pxPerMs: number, scrollMs = 0): number {
    return pxPerMs <= 0 ? scrollMs : px / pxPerMs + scrollMs;
}

/** 每帧时长(ms)。fps≤0 兜底 20fps。 */
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

/** 标尺刻度。{@code major} = 主刻度（带标签）。 */
export interface RulerTick {
    timeMs: number;
    x: number;
    label: string;
    major: boolean;
}

/** 取 ≥ raw 的"好看"步长：1/2/5 × 10^n（避免 37ms 这类难读刻度）。 */
function niceStep(raw: number): number {
    if (raw <= 0) return 1;
    const pow = Math.pow(10, Math.floor(Math.log10(raw)));
    const norm = raw / pow;                          // [1, 10)
    const nice = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
    return Math.max(1, nice * pow);
}

/** 时刻标签：≥1000ms 用 s（去尾0），否则 ms。 */
export function formatTimeLabel(timeMs: number): string {
    if (timeMs >= 1000) {
        const s = timeMs / 1000;
        return (Number.isInteger(s) ? String(s) : String(Number(s.toFixed(2)))) + 's';
    }
    return Math.round(timeMs) + 'ms';
}

/**
 * 算时间标尺主刻度：在可见宽度内按"好看间隔"（1/2/5×10^n ms，目标间距 ≈80px）布主刻度（带标签）。
 * 不超过 {@code durationMs}。
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
//  P4.5：整体关键帧（transform 几何变换聚合，AE/PR 风——一个关键帧记录元素整组变换）
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
 * 把某元素的 transform（{@link KEYFRAMEABLE_PROPERTIES} 六属性）关键帧按 timeMs 聚合成"整体关键帧"
 * 列表（timeMs 升序）。AE/PR 风：元素行直接操作整体帧（加 / 删 / 拖 / 缓动），缓动统一应用到该时刻
 * 所有 transform 属性 → x/y 进度同步 → 运动轨迹保持直线（只速度按缓动变，解决"调缓动把轨迹掰弯"）。
 * color/text/fill 不计入整体帧（展开子轨单独打）。
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
//  P4.5b：拖画布元素自动加帧（"拉就设"）—— 加帧计划 + 拖动期跟手覆盖（纯函数，可测）
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
 * 拖动期把"正在拖的元素"的实时几何值覆盖到插值结果上，保证 previewActive 时被拖元素跟手——
 * 否则有帧的元素会被 {@link interpolate} 钉在帧值位置、看起来拖不动。不可变重建：只复制含覆盖
 * 元素的 layer，其余 layer / element 保持原引用（与 interpolate 同范式，绝不污染传入的 base state）。
 *
 * @param state     interpolate 产出的临时渲染 state（可能与 base 同引用）
 * @param overrides elementId → 实时几何快照（来自 onDragMove 已乐观 mutate 的 store 元素）
 * @returns 有覆盖命中则返回新 state，否则原样返回（含 overrides 为空 / 无匹配元素）
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

/** 整体帧 upsert 计划：timeMs 已有该属性帧 → update（带 keyframeId）；缺的属性 → add 新帧。 */
export interface TransformUpsertPlan {
    timeMs: number;
    adds: { property: string; value: number }[];
    updates: { keyframeId: string; property: string; value: number }[];
}

/**
 * 算"在 timeMs 给元素打一个整体关键帧"要发的操作：遍历 6 个 transform 属性，取元素当前值
 * （{@link defaultValueFor}）；该属性在 timeMs 已有帧 → update 其 value，否则 add 新帧。
 * dock 的 + 按钮（{@code addTransformKeyframe}）与画布拖动自动加帧共用此计划，消两处逻辑分叉。
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
        const ex = existing.find(k => k.property === property && k.timeMs === timeMs);
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
 * 算框选矩形命中的整体帧 key 列表（问题 3：拉框选中后 Del 批量删）。rows 按渲染顺序、每行高
 * rowH；只考虑 element 行的整体帧块，块中心 x = {@link msToPx}(timeMs)、y = 行中线；命中 = 块中心
 * 落在归一化后的矩形内。纯函数，不依赖 DOM。
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
