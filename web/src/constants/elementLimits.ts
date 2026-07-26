/**
 * 元素字段取值范围 —— 后端 {@code ElementValidator} 的前端镜像。
 *
 * <p>属性面板是"先改本地再发帧"的乐观更新：本地已经按新值画了，服务端却因为超界拒收，
 * 于是浏览器里是新尺寸、游戏里还是旧尺寸，双端一直分叉到重新拉快照为止；而被拒的错误此前
 * 只写进日志，用户完全无感。所以超界的值<b>在发出去之前</b>就该被夹回范围内。</p>
 *
 * <p><b>改这里必须同步改后端</b>（{@code ElementValidator} 的同名常量），两边是一份契约。
 * 前端夹的是"用户能手输的字段"，不是全部校验——后端仍是唯一权威。</p>
 */

/** 文本内容最大字符数（后端 {@code MAX_TEXT_LEN}）。 */
export const MAX_TEXT_LEN = 256;
/** 坐标绝对值上限（后端 {@code MAX_COORD}）：x / y ∈ [-10000, 10000]。 */
export const MAX_COORD = 10_000;
/** 宽高上限（后端 {@code MAX_DIM}）：w / h ∈ [1, 10000]，0 与负数会让画布 API 直接抛错。 */
export const MAX_DIM = 10_000;
/** 字号范围（后端 {@code MAX_FONT_SIZE}）：[1, 512]。 */
export const MAX_FONT_SIZE = 512;
/** 描边宽度范围（后端 {@code MAX_STROKE_WIDTH}）：[0, 128]。 */
export const MAX_STROKE_WIDTH = 128;
/** 字距范围（后端 {@code MIN/MAX_LETTER_SPACING}）。 */
export const MIN_LETTER_SPACING = -32;
export const MAX_LETTER_SPACING = 128;
/** 行高倍数范围（后端 {@code MIN/MAX_LINE_HEIGHT}）。 */
export const MIN_LINE_HEIGHT = 0.5;
export const MAX_LINE_HEIGHT = 4;
/** 阴影偏移绝对值上限（后端 {@code MAX_SHADOW_OFFSET}）。 */
export const MAX_SHADOW_OFFSET = 128;
/** 光晕半径上限（后端 {@code MAX_GLOW_RADIUS}）。 */
export const MAX_GLOW_RADIUS = 64;

/** 把数值夹到 [min, max]；非有限数按 min 处理（输入框清空 / 乱输时不至于把 NaN 发出去）。 */
export function clampNumber(v: number, min: number, max: number): number {
    if (!Number.isFinite(v)) return min;
    return Math.min(max, Math.max(min, v));
}

/** 文本超长截断到 {@link MAX_TEXT_LEN}（按 JS 字符数，与后端 {@code String.length} 同口径）。 */
export function clampText(s: string): string {
    return s.length > MAX_TEXT_LEN ? s.slice(0, MAX_TEXT_LEN) : s;
}
