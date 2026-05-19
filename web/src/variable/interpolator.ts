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
 * 单次插值结果。
 *
 * - {@link text}：替换完成的最终文本（纯文本路径短路返原引用，未匹配则原样）
 * - {@link referencedFullNames}：本次解析引用到的所有内部 fullName 集合
 * - {@link missingFullNames}：referenced 中 store 查不到的子集（删除联动 / 警告用）
 */
export interface InterpolateResult {
    text: string;
    referencedFullNames: Set<string>;
    missingFullNames: Set<string>;
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
        return { text: '', referencedFullNames: new Set(), missingFullNames: new Set() };
    }
    if (text.indexOf('${var:') < 0) {
        return { text, referencedFullNames: new Set(), missingFullNames: new Set() };
    }
    const referenced = new Set<string>();
    const missing = new Set<string>();
    // 用 matchAll 单趟扫描；不复用 PATTERN 实例避免 lastIndex 残留态影响多调用
    const pattern = new RegExp(VARIABLE_PATTERN.source, 'g');
    const out = text.replace(pattern, (_full, rawName: string, fallback: string | undefined) => {
        const fullName = resolveFullName(rawName, wallId);
        referenced.add(fullName);
        const v = store.get(fullName);
        if (v) {
            const cur = v.currentValue;
            if (cur != null && cur.length > 0) return cur;
            if (fallback !== undefined) return fallback;
            const def = v.defaultValue;
            if (def != null) return def;
            return UNRESOLVED;
        }
        // 变量不存在（被删 / 未声明 / 命名不匹配）
        missing.add(fullName);
        if (fallback !== undefined) return fallback;
        return UNRESOLVED;
    });
    return { text: out, referencedFullNames: referenced, missingFullNames: missing };
}
