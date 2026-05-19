/**
 * 渲染期文本占位符替换（前端镜像）。0.4.0-P2-H 核心模块。
 *
 * <p>与后端 {@code moe.hikari.canvas.variable.VariableInterpolator} 一致的算法：
 * <ul>
 *   <li>正则 {@code \$\{var:([^|}]+)(?:\|fallback=([^}]*))?\}}</li>
 *   <li>fullName 注入：{@code ${var:user/X}} + wallId → 内部 {@code user:<wallId>/X}</li>
 *   <li>fallback 链：cached → {@code |fallback=...} → {@code defaultValue} → {@value UNRESOLVED}</li>
 * </ul></p>
 *
 * <p>用途：TextElement live preview（编辑器实时展示替换后的最终文本）。运行时渲染走后端
 * {@link VariableInterpolator}，本前端版本仅用于编辑器即时反馈，不参与 MC 内的实际投影。</p>
 *
 * @see /Users/haru/.../plugin/.../variable/VariableInterpolator.java 算法权威
 * @see docs/dynamic-data.md §2.3 / §5.3
 */
import type { useVariableStore } from '@/stores/variables';

/** 占位符正则（g 标志，多占位符同 text 都要替换）。 */
export const VARIABLE_PATTERN = /\$\{var:([^|}]+)(?:\|fallback=([^}]*))?\}/g;

/** 系统兜底文案（fallback 链最后一档）。 */
export const UNRESOLVED = '???';

/** 用户命名空间前缀（与后端 {@code VariableStore.USER_NAMESPACE_PREFIX} 一致）。 */
const USER_NAMESPACE_PREFIX = 'user';

/**
 * 单个 placeholder 在<b>替换后</b>文本中的位置标记（M28-enhance 引入）。
 *
 * <p>由 {@link interpolate} 输出；用于编辑器 PreviewRenderer 在 Canvas 上画 "chip 风格"背景 hint
 * 把替换后的字符按段着色，让用户区分"哪几个字是变量值"。游戏内 Compositor 不画 hint，所以这只是
 * 前端编辑器层的额外信息。后端 {@code VariableInterpolator.Segment} 对称。</p>
 */
export interface PlaceholderSegment {
    /** 在替换后 text 中的起始 char index（inclusive）。 */
    start: number;
    /** 在替换后 text 中的结束 char index（exclusive）。 */
    end: number;
    /** 内部 fullName（store 编码后；如 {@code user:w-1/X}）；hover tooltip 可用。 */
    fullName: string;
    /** 原始 placeholder 字符串（如 {@code "${var:user/X}"}）；调试用。 */
    raw: string;
}

/**
 * 单次插值结果。
 *
 * - {@link text}：替换完成的最终文本（纯文本路径短路返原引用，未匹配则原样）
 * - {@link referencedFullNames}：本次解析引用到的所有内部 fullName 集合
 * - {@link missingFullNames}：referenced 中 store 查不到的子集（删除联动 / 警告用）
 * - {@link segments}：每个 placeholder 在替换后 text 中的 char range；纯文本时为空数组
 */
export interface InterpolateResult {
    text: string;
    referencedFullNames: Set<string>;
    missingFullNames: Set<string>;
    segments: PlaceholderSegment[];
}

/**
 * 把对外 placeholder 内的 rawName 转成 {@code VariableStore} 的内部 fullName：
 * <ul>
 *   <li>{@code user/X} + wallId 非空 → {@code user:<wallId>/X}</li>
 *   <li>{@code user/X} + wallId 为空 → 字面 {@code user/X}（必然 miss，便于无 wall 上下文预览）</li>
 *   <li>{@code wall.X} + wallId 非空 → {@code system:<wallId>/wall.X}（0.4.0-P3-J；
 *       后端 SystemVariableProvider 按 per-wall namespace 注册 wall.id / wall.alias 等）</li>
 *   <li>{@code wall.X} + wallId 为空 → 字面 {@code wall.X}（必然 miss，便于模板 publish / 预览）</li>
 *   <li>{@code bedwars/score} / {@code server.time} 等 → 字面不变</li>
 * </ul>
 */
export function resolveFullName(rawName: string, wallId: string | null): string {
    const trimmed = rawName.trim();
    if (wallId && wallId.length > 0
            && trimmed.startsWith(USER_NAMESPACE_PREFIX + '/')) {
        const key = trimmed.substring(USER_NAMESPACE_PREFIX.length + 1);
        return `${USER_NAMESPACE_PREFIX}:${wallId}/${key}`;
    }
    // 0.4.0-P3-J：wall.* 系统变量按 per-wall namespace 注入
    if (wallId && wallId.length > 0 && trimmed.startsWith('wall.')) {
        return `system:${wallId}/${trimmed}`;
    }
    // 0.4.0-P3-L：schedule.* per-wall namespace 注入（与 wall.* 同款）
    // ${var:schedule.next_departure} + wallId → schedule:<wallId>/next_departure
    if (wallId && wallId.length > 0 && trimmed.startsWith('schedule.')) {
        const tail = trimmed.substring('schedule.'.length);
        return `schedule:${wallId}/${tail}`;
    }
    // 0.4.0 bugfix3（Bug A）：用户直觉的 namespace/key 斜杠语法 — 与 ${var:user/X} 同款风格。
    // 不破坏 schedule.X / wall.X 点号语法，仅作为 fallback；wallId 为空时跳过。
    // ${var:schedule/eta_seconds} + wallId → schedule:<wallId>/eta_seconds
    // ${var:wall/id} + wallId → system:<wallId>/wall.id（注入到 SystemVariableProvider 同款 fullName）
    if (wallId && wallId.length > 0 && trimmed.startsWith('wall/')) {
        const tail = trimmed.substring('wall/'.length);
        return `system:${wallId}/wall.${tail}`;
    }
    if (wallId && wallId.length > 0 && trimmed.startsWith('schedule/')) {
        const tail = trimmed.substring('schedule/'.length);
        return `schedule:${wallId}/${tail}`;
    }
    // 0.4.0-P3-J：scoreboard.<obj>.<player> 点分号 alias → scoreboard/<obj>.<player>
    if (trimmed.startsWith('scoreboard.')) {
        const tail = trimmed.substring('scoreboard.'.length);
        if (tail.length > 0 && tail.indexOf('/') < 0) {
            return `scoreboard/${tail}`;
        }
    }
    return trimmed;
}

/**
 * 解析文本中的 {@code ${var:X}} 占位符。
 *
 * <p>性能：text 不含 {@code ${var:} 子串时 O(1) 短路返原引用；否则 O(n) regex 单趟扫描。</p>
 *
 * @param text   原始文本；null / undefined / '' 视为空字符串返空集合
 * @param wallId 当前 wall ID；非空时把 {@code ${var:user/X}} 注入成 {@code user:<wallId>/X}
 * @param store  Pinia VariableStore 响应式实例（组件内 useVariableStore() 拿）
 */
export function interpolate(
    text: string | null | undefined,
    wallId: string | null,
    store: ReturnType<typeof useVariableStore>,
): InterpolateResult {
    if (text == null || text.length === 0) {
        return { text: '', referencedFullNames: new Set(), missingFullNames: new Set(), segments: [] };
    }
    if (text.indexOf('${var:') < 0) {
        return { text, referencedFullNames: new Set(), missingFullNames: new Set(), segments: [] };
    }
    const referenced = new Set<string>();
    const missing = new Set<string>();
    const segments: PlaceholderSegment[] = [];
    // 单趟扫描；手工累积输出 + 同步记录每个 placeholder 在替换后 text 中的 char range。
    // 不用 String.replace 因为它不易暴露每段 placeholder 替换后的 char index（只有原字符串 offset）。
    const pattern = new RegExp(VARIABLE_PATTERN.source, 'g');
    let result = '';
    let lastEnd = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(text)) !== null) {
        const matchStart = m.index;
        const matchEnd = matchStart + m[0].length;
        // 1. 复制非 placeholder 段
        result += text.substring(lastEnd, matchStart);
        const rawName = m[1];
        const fallback: string | undefined = m[2];
        const fullName = resolveFullName(rawName, wallId);
        referenced.add(fullName);
        const v = store.get(fullName);
        let resolved: string;
        if (v) {
            const cur = v.currentValue;
            if (cur != null && cur.length > 0) resolved = cur;
            else if (fallback !== undefined) resolved = fallback;
            else if (v.defaultValue != null) resolved = v.defaultValue;
            else resolved = UNRESOLVED;
        } else {
            missing.add(fullName);
            resolved = fallback !== undefined ? fallback : UNRESOLVED;
        }
        const segStart = result.length;
        result += resolved;
        const segEnd = result.length;
        segments.push({ start: segStart, end: segEnd, fullName, raw: m[0] });
        lastEnd = matchEnd;
        // 防御性：m[0].length === 0 会死循环（正常 placeholder 不会，但保险）
        if (m[0].length === 0) pattern.lastIndex++;
    }
    // 复制末尾非 placeholder 段
    if (lastEnd < text.length) {
        result += text.substring(lastEnd);
    }
    return { text: result, referencedFullNames: referenced, missingFullNames: missing, segments };
}
