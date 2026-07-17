/**
 * 积木堆坐标（blockLayout）的编解码 + 自动布局 fallback。
 *
 * <p>每条 {@code ScriptRule} 在画布上是一个"积木堆"，堆的左上角坐标存在 wire 字段
 * {@code ScriptRule.blockLayout}（JSON 字符串）。形态：</p>
 * <pre>{ "stacks": { "&lt;ruleId&gt;": { "x": N, "y": N } } }</pre>
 *
 * <p><b>后端不解析 blockLayout</b>——纯前端 UI 坐标。坏 / 缺坐标时由
 * {@link autoLayout} 纵向排布兜底。本文件只管坐标编解码，不碰 actions 树（树操作见
 * blockTree.ts）；"编辑模型"即 ScriptRule 本身 + 此处解析出的坐标。</p>
 */

/** 单个积木堆的画布坐标（world 坐标系，像素）。 */
export interface StackLayout {
    x: number;
    y: number;
}

/** blockLayout 解析结果：ruleId → 坐标。 */
export interface BlockLayout {
    stacks: Record<string, StackLayout>;
}

/** 自动纵向排布的常量（autoLayout 用）。 */
const AUTO_LAYOUT_X = 40;
const AUTO_LAYOUT_Y0 = 40;
const AUTO_LAYOUT_GAP_Y = 320;

/**
 * 解析 blockLayout JSON 字符串为 {@link BlockLayout}。
 *
 * <p>鲁棒容错（坏数据绝不抛）：</p>
 * <ul>
 *   <li>{@code null} / {@code undefined} / 空串 / 坏 JSON → {@code {stacks:{}}}；</li>
 *   <li>顶层非对象、或 {@code stacks} 缺失 / 非对象 → {@code {stacks:{}}}；</li>
 *   <li>逐条校验 stack：必须是对象且 {@code x} / {@code y} 均为有限数，否则丢弃该条
 *       （保留其余合法条目）。</li>
 * </ul>
 */
export function parseBlockLayout(json: string | null | undefined): BlockLayout {
    const empty: BlockLayout = { stacks: {} };
    if (json === null || json === undefined || json === '') return empty;

    let raw: unknown;
    try {
        raw = JSON.parse(json);
    } catch {
        return empty;
    }
    if (!isPlainObject(raw)) return empty;

    const stacksRaw = (raw as Record<string, unknown>).stacks;
    if (!isPlainObject(stacksRaw)) return empty;

    const stacks: Record<string, StackLayout> = {};
    for (const [ruleId, val] of Object.entries(stacksRaw as Record<string, unknown>)) {
        if (!isPlainObject(val)) continue;
        const x = (val as Record<string, unknown>).x;
        const y = (val as Record<string, unknown>).y;
        if (typeof x === 'number' && typeof y === 'number' && isFiniteNum(x) && isFiniteNum(y)) {
            stacks[ruleId] = { x, y };
        }
    }
    return { stacks };
}

/** 序列化 {@link BlockLayout} 为 JSON 字符串（写回 wire blockLayout 字段）。 */
export function stringifyBlockLayout(layout: BlockLayout): string {
    return JSON.stringify({ stacks: layout.stacks });
}

/**
 * 给 {@code ruleIds} 里<b>缺坐标</b>的规则补一个纵向排布坐标，保留 existing 已有坐标。
 *
 * <p>新坐标：{@code x = 40}，{@code y = 40 + i * 320}（i 为缺坐标规则在 ruleIds 中的
 * 相对序号，从 0 起）。返回新 {@link BlockLayout}（不修改入参 existing）。</p>
 *
 * @param ruleIds  当前需要展示的全部规则 id（决定补坐标的目标集合）。
 * @param existing 已有坐标（来自 parseBlockLayout）。
 */
export function autoLayout(ruleIds: string[], existing: BlockLayout): BlockLayout {
    const stacks: Record<string, StackLayout> = { ...existing.stacks };
    let missingSeq = 0;
    for (const ruleId of ruleIds) {
        if (stacks[ruleId]) continue;
        stacks[ruleId] = {
            x: AUTO_LAYOUT_X,
            y: AUTO_LAYOUT_Y0 + missingSeq * AUTO_LAYOUT_GAP_Y,
        };
        missingSeq++;
    }
    return { stacks };
}

// ---------- 内部辅助 ----------

function isPlainObject(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function isFiniteNum(n: number): boolean {
    return Number.isFinite(n);
}
