/**
 * 渲染期文本占位符替换（前端镜像）。核心模块。
 *
 * <p>与后端 {@code ac.haru.hikaricanvas.variable.VariableInterpolator} 一致的算法：
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
 * 与 Java {@code String.trim()} 等价的剥空白：只剥码位 ≤ U+0020 的字符。
 *
 * <p>JS 原生 {@code String.prototype.trim()} 剥的是全部 Unicode 空白（含 U+00A0 不换行
 * 空格、U+3000 全角空格），比 Java 多剥一批——占位符里混进全角空格时两端会解析出不同的
 * 变量名，编辑器预览显示值、游戏内显示 {@code ???}。口径以后端为准（游戏内才是最终输出），
 * 与 {@code timeline/interpolation.ts} 的数值解析同源（docs/rendering.md §9.5）。</p>
 */
function asciiTrim(s: string): string {
    return s.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '');
}

/**
 * TTL 过期判定，镜像后端 {@code Variable.isStale}：{@code ttl > 0 && (now - updatedAt) > ttl}。
 *
 * <p>之前前端只判 {@code currentValue != null && length > 0} 就用 cached，从不判过期；
 * 后端 {@code VariableInterpolator.resolveValue} 则 stale 时跳过 cached 走 fallback 链。
 * 二者在 TTL 过期窗口对同一占位符产出不同文本（双端不一致）。此 helper 让前端对齐后端。</p>
 *
 * <p>注：staleness 时钟基准依赖 {@code updatedAt}（由 wsClient 接 state.patch 写入）。</p>
 *
 * <p>导出供 VariableChipEditor 的 chip 显示 / tooltip 复用同一判定，保证三处
 * （interpolate / chip 文本 / tooltip）的 stale 语义不分叉。</p>
 */
export function isStale(ttl: number | undefined, updatedAt: number | undefined, now: number): boolean {
    if (typeof ttl !== 'number' || ttl <= 0) return false;
    if (typeof updatedAt !== 'number') return false;
    return now - updatedAt > ttl;
}

/**
 * 单个 placeholder 在<b>替换后</b>文本中的位置标记。
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
 *   <li>{@code wall.X} + wallId 非空 → {@code system:<wallId>/wall.X}（
 *       后端 SystemVariableProvider 按 per-wall namespace 注册 wall.id / wall.alias 等）</li>
 *   <li>{@code wall.X} + wallId 为空 → 字面 {@code wall.X}（必然 miss，便于模板 publish / 预览）</li>
 *   <li>{@code bedwars/score} / {@code server.time} 等 → 字面不变</li>
 * </ul>
 */
export function resolveFullName(rawName: string, wallId: string | null): string {
    // 剥空白的口径必须与后端 String.trim() 一致（只剥 ≤U+0020）。JS 的 trim() 会连
    // 不换行空格 / 全角空格一起剥掉，于是占位符里打了个全角空格时，编辑器预览能取到值、
    // 游戏内却显示 ???。同一条口径见 timeline/interpolation.ts 的数值解析（rendering.md §9.5）。
    const trimmed = asciiTrim(rawName);
    if (wallId && wallId.length > 0
            && trimmed.startsWith(USER_NAMESPACE_PREFIX + '/')) {
        const key = trimmed.substring(USER_NAMESPACE_PREFIX.length + 1);
        return `${USER_NAMESPACE_PREFIX}:${wallId}/${key}`;
    }
    // wall.* 系统变量按 per-wall namespace 注入
    if (wallId && wallId.length > 0 && trimmed.startsWith('wall.')) {
        return `system:${wallId}/${trimmed}`;
    }
    // schedule.* per-wall namespace 注入（与 wall.* 同款）
    // ${var:schedule.next_departure} + wallId → schedule:<wallId>/next_departure
    if (wallId && wallId.length > 0 && trimmed.startsWith('schedule.')) {
        const tail = trimmed.substring('schedule.'.length);
        return `schedule:${wallId}/${tail}`;
    }
    // 用户直觉的 namespace/key 斜杠语法 — 与 ${var:user/X} 同款风格。
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
    // scoreboard.<obj>.<player> 点分号 alias → scoreboard/<obj>.<player>
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
 * <p>服务器重启后字面 {@code ${var:} 残留场景：把 {@link #doInterpolate}
 * 拆成内部实现 + 外层 {@link interpolate} 做二次扫描 wrapper。当首次替换后输出仍含 {@code ${var:}
 * 字面（典型场景：chip editor roundtrip 漏 escape / 数据写入了双重嵌套 {@code ${${var:X}}}），最多
 * 再 interpolate 2 次兜底。DEV 模式下若触发二次解析 console.warn 帮排查根因。</p>
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
    let result = doInterpolate(text, wallId, store);
    let depth = 0;
    while (result.text.indexOf('${var:') >= 0 && depth < 2) {
        // 嵌套或损坏数据：再 interpolate 一次，accumulate referenced/missing/segments
        const next = doInterpolate(result.text, wallId, store);
        // 合并集合（segments 来自后一轮，更接近最终输出 char range；前轮 segments 已失效）
        next.referencedFullNames.forEach((fn) => result.referencedFullNames.add(fn));
        next.missingFullNames.forEach((fn) => result.missingFullNames.add(fn));
        result = {
            text: next.text,
            referencedFullNames: result.referencedFullNames,
            missingFullNames: result.missingFullNames,
            segments: next.segments,
        };
        depth++;
    }
    if (depth > 0 && import.meta.env.DEV) {
        // eslint-disable-next-line no-console
        console.warn(
            '[interpolator] nested placeholder detected; depth=', depth,
            'text=', (text ?? '').substring(0, 100),
        );
    }
    return result;
}

/** 单次扫描实现；调用方（外层 {@link interpolate}）负责二次扫描兜底。 */
function doInterpolate(
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
    // 单次扫描内用同一 now，避免一段文本内多个占位符 staleness 判定时钟漂移。
    const now = Date.now();
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
            // cached 非空且未过期才用；stale（ttl 过期）则跳过 cached 走 fallback 链，与后端一致。
            if (cur != null && cur.length > 0 && !isStale(v.ttl, v.updatedAt, now)) resolved = cur;
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
