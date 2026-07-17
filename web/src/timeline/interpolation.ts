/**
 * 关键帧插值器——后端 `render/KeyframeInterpolator.java` 的前端镜像，
 * 编辑器本地预览（scrubber）用。数学权威定义见 `docs/rendering.md §9`；游戏内
 * 最终输出永远以后端为权威（timeline.md D9）。
 *
 * 取值规则（§9.1：首帧前/末帧后不外插、重合帧取后）、缓动（`easing.ts`）、色彩
 * （`colorLerp.ts`）、数值舍入（x/y/w/h/rotation `Math.round` 回整数——与 Java record
 * int 字段对齐）两端一致。
 */
import type { Element, Fill, Keyframe, LoopMode, ProjectState, Timeline } from '../types/protocol';
import { ease } from './easing';
import { lerpFill, lerpHex } from './colorLerp';

/** 数值轨 ${var:X} 求值 seam（P4 接前端变量 store 镜像）；缺省 = 无变量支持。 */
export type NumberResolver = (raw: string) => number;

const NUMERIC_PROPERTIES = new Set(['x', 'y', 'w', 'h', 'rotation', 'opacity']);

/** fill 轨适用的元素类型（按 record 字段适用性，错配的轨静默忽略——与后端一致）。 */
const FILLABLE_TYPES = new Set(['rect', 'icon', 'path', 'circle', 'shape', 'brush']);

function floorMod(a: number, n: number): number {
    return ((a % n) + n) % n;
}

/** loopMode 时间映射（§9 / Java mapTime 镜像）。 */
export function mapTime(posMs: number, durationMs: number, mode: LoopMode | undefined): number {
    if (durationMs <= 0) return 0;
    const m = mode ?? 'loop';
    switch (m) {
        case 'once':
            return Math.min(Math.max(posMs, 0), durationMs);
        case 'loop':
            return floorMod(posMs, durationMs);
        case 'pingPong': {
            const period = 2 * durationMs;
            const r = floorMod(posMs, period);
            return r <= durationMs ? r : period - r;
        }
        default:
            return floorMod(posMs, durationMs);
    }
}

interface Span {
    a: Keyframe;
    ai: number;
    b: Keyframe | null;
    eased: number;
}

/** §9.1 取值规则共享实现（Java spanAt 镜像）。 */
function spanAt(kfs: Keyframe[], t: number): Span {
    const first = kfs[0];
    const last = kfs[kfs.length - 1];
    if (t <= first.timeMs) return { a: first, ai: 0, b: null, eased: 0 };
    if (t >= last.timeMs) return { a: last, ai: kfs.length - 1, b: null, eased: 0 };
    let i = 0;
    for (let j = 1; j < kfs.length; j++) {
        if (kfs[j].timeMs <= t) i = j;
        else break;
    }
    const a = kfs[i];
    const b = kfs[i + 1];
    const local = (t - a.timeMs) / (b.timeMs - a.timeMs);
    return { a, ai: i, b, eased: ease(a.easing, local) };
}

/** 严格数值文法（rendering.md §9.5：两端同一文法，排除 parseFloat 前缀宽松/parseDouble 0x1p4 语义）。 */
const STRICT_NUMBER = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

function parsePlainNumber(raw: string | null | undefined): number {
    if (raw == null) return 0;
    // trim 对齐 Java String.trim()（剥 ≤U+0020）——JS trim() 会多剥 NBSP / 全角空格等全
    // Unicode 空白，导致 " 5" 在两端解析分叉（§9.5）
    const trimmed = raw.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '');
    if (!STRICT_NUMBER.test(trimmed)) return 0;
    const v = Number.parseFloat(trimmed);
    return Number.isFinite(v) ? v : 0;
}

function numericValueOf(v: Keyframe['value'], resolver: NumberResolver | undefined): number | null {
    if (typeof v === 'number') return v;
    if (typeof v === 'string') {
        if (resolver) return resolver(v);
        if (v.includes('${var:')) return 0;   // 无变量支持 → fallback 链终点 0（§9.5）
        return parsePlainNumber(v);
    }
    return null;
}

/** 数值轨采样；含错型值 → 整轨跳过（返 null）。 */
export function sampleNumeric(
    kfs: Keyframe[],
    t: number,
    resolver?: NumberResolver,
): number | null {
    if (!kfs || kfs.length === 0) return null;
    const values: number[] = [];
    for (const k of kfs) {
        const v = numericValueOf(k.value, resolver);
        if (v == null) return null;
        values.push(v);
    }
    const s = spanAt(kfs, t);
    if (s.b == null) return values[s.ai];
    const va = values[s.ai];
    const vb = values[s.ai + 1];
    return va + (vb - va) * s.eased;
}

/** 颜色轨采样（仅 text 元素 color）；含 ${var:} / 错型 → 整轨跳过。 */
export function sampleColor(kfs: Keyframe[], t: number): string | null {
    if (!kfs || kfs.length === 0) return null;
    for (const k of kfs) {
        if (typeof k.value !== 'string' || k.value.includes('${var:')) return null;
    }
    const s = spanAt(kfs, t);
    const a = kfs[s.ai].value as string;
    if (s.b == null) return a;
    const b = kfs[s.ai + 1].value as string;
    return lerpHex(a, b, s.eased);
}

function fillValueOf(v: Keyframe['value']): Fill | null {
    if (typeof v === 'string') {
        if (v.includes('${var:')) return null;
        return { type: 'solid', color: v };
    }
    if (v != null && typeof v === 'object' && 'type' in v) return v as Fill;
    return null;
}

/** Fill 轨采样；错型 / 变量引用 → 整轨跳过。 */
export function sampleFill(kfs: Keyframe[], t: number): Fill | null {
    if (!kfs || kfs.length === 0) return null;
    const fills: Fill[] = [];
    for (const k of kfs) {
        const f = fillValueOf(k.value);
        if (f == null) return null;
        fills.push(f);
    }
    const s = spanAt(kfs, t);
    if (s.b == null) return fills[s.ai];
    return lerpFill(fills[s.ai], fills[s.ai + 1], s.eased);
}

/** 离散轨采样（text）：step 取 timeMs ≤ t 最近帧；首帧前取首帧（§9.2 边界）。 */
export function sampleText(kfs: Keyframe[], t: number): string | null {
    if (!kfs || kfs.length === 0) return null;
    for (const k of kfs) {
        if (typeof k.value !== 'string') return null;
    }
    let pick = kfs[0];
    for (const k of kfs) {
        if (k.timeMs <= t) pick = k;
        else break;
    }
    return pick.value as string;
}

interface AnimatedValues {
    numbers: Map<string, number> | null;
    color: string | null;
    fill: Fill | null;
    text: string | null;
}

function clamp01(v: number): number {
    return Math.min(1, Math.max(0, v));
}

/** int 范围钳位（对齐 Java StrictNumber.clampInt）——x/y/w/h/rotation 写回 record int 字段前，
 *  防 Java (int) 收窄回绕而 JS number 不回绕的双端分叉（§9.5）。 */
function clampInt(v: number): number {
    return Math.max(-2147483648, Math.min(2147483647, v));
}

/** 用插值结果重建元素（spread 复制；数值属性 Math.round 回整数对齐 Java int 字段）。 */
function withAnimated(e: Element, v: AnimatedValues): Element {
    const n = v.numbers;
    const out: Record<string, unknown> = { ...e };
    if (n) {
        if (n.has('x')) out.x = clampInt(Math.round(n.get('x')!));
        if (n.has('y')) out.y = clampInt(Math.round(n.get('y')!));
        if (n.has('w')) out.w = clampInt(Math.round(n.get('w')!));
        if (n.has('h')) out.h = clampInt(Math.round(n.get('h')!));
        if (n.has('rotation')) out.rotation = clampInt(Math.round(n.get('rotation')!));
        if (n.has('opacity')) out.opacity = clamp01(n.get('opacity')!);
    }
    if (e.type === 'text') {
        if (v.color != null) out.color = v.color;
        if (v.text != null) out.text = v.text;
    }
    if (v.fill != null && FILLABLE_TYPES.has(e.type)) {
        out.fill = v.fill;
    }
    return out as unknown as Element;
}

/**
 * 入口（Java interpolate 镜像）：算 timeline 在 timeMs 的临时 ProjectState。
 * 只重建被动画的 layer / element；无可插值轨直接返回 base 同一引用。
 * 返回值仅供本地预览渲染（喂 renderProjectState），不得写回 store / 发 WS。
 */
export function interpolate(
    base: ProjectState,
    timeline: Timeline,
    timeMs: number,
    resolver?: NumberResolver,
): ProjectState {
    if (!base || !timeline || !timeline.tracks) return base;
    const trackEntries = Object.entries(timeline.tracks);
    if (trackEntries.length === 0) return base;

    // 帧内同一 raw 只 resolve 一次（memo）——与 Java 端一致，防变量 push 落在两次读
    // 之间造成单帧撕裂
    if (resolver) {
        const delegate = resolver;
        const memo = new Map<string, number>();
        resolver = (raw: string) => {
            if (memo.has(raw)) return memo.get(raw)!;
            const v = delegate(raw);
            memo.set(raw, v);
            return v;
        };
    }

    const animated = new Map<string, AnimatedValues>();
    for (const [elementId, kfList] of trackEntries) {
        const byProp = new Map<string, Keyframe[]>();
        for (const k of kfList) {
            if (!byProp.has(k.property)) byProp.set(k.property, []);
            byProp.get(k.property)!.push(k);
        }
        const out: AnimatedValues = { numbers: null, color: null, fill: null, text: null };
        for (const [prop, kfs] of byProp) {
            if (NUMERIC_PROPERTIES.has(prop)) {
                const v = sampleNumeric(kfs, timeMs, resolver);
                if (v == null || !Number.isFinite(v)) continue;
                if (!out.numbers) out.numbers = new Map();
                out.numbers.set(prop, v);
            } else if (prop === 'color') {
                out.color = sampleColor(kfs, timeMs);
            } else if (prop === 'fill') {
                out.fill = sampleFill(kfs, timeMs);
            } else if (prop === 'text') {
                out.text = sampleText(kfs, timeMs);
            }
        }
        if ((out.numbers && out.numbers.size > 0) || out.color != null
            || out.fill != null || out.text != null) {
            animated.set(elementId, out);
        }
    }
    if (animated.size === 0) return base;

    let any = false;
    const outLayers = base.layers.map(layer => {
        let replaced: Element[] | null = null;
        layer.elements.forEach((el, i) => {
            const v = animated.get(el.id);
            if (!v) return;
            if (!replaced) replaced = [...layer.elements];
            replaced[i] = withAnimated(el, v);
            any = true;
        });
        return replaced ? { ...layer, elements: replaced } : layer;
    });
    if (!any) return base;

    return { ...base, layers: outLayers, timelines: undefined, activeTimelineId: undefined };
}
