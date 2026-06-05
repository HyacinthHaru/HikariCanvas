/**
 * 0.6 P2（B3）：时间轴最简面板的可测纯逻辑。
 *
 * <p>{@link TimelineManagerModal} 把所有"不依赖 Vue 响应式 / DOM / WS"的计算抽到本文件，
 * 单测（{@code __tests__/timelineLogic.test.ts}）直接 node 跑覆盖，组件只做绑定 + 发包。
 * 形态依据 docs/protocol.md §7 + docs/timeline.md §2，类型镜像 {@code types/protocol.ts}
 * 的 {@link Timeline} / {@link Keyframe}。</p>
 *
 * <p>本面板是 MVP 关键帧列表（非 AE 风 panel —— 那是 P4）：分组展示 + 表单校验 +
 * 选中元素当前属性值取默认 —— 全是无副作用纯函数。</p>
 */
import type { EasingType, Element, Fill, KfValue, Keyframe, LoopMode, Timeline } from '@/types/protocol';

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
