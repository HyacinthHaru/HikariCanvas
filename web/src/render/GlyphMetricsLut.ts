/**
 * 前端 per-font 字符 advance 查找表（Java FontMetricsTable mirror）。
 *
 * fetch /fonts/{fontId}.metrics.json + cache + advance(fontId, codePoint, fontSize) →
 * round(baseAdv × fontSize / baseSize)。缺字 / 未加载字体返 -1，调用方 fallback
 * canonicalCharWidth。
 *
 * 加载是 async，但 first paint 时表可能未到——onMetricsReady 通知 PreviewRenderer 重画一次。
 * 与现有 onIconReady / onPaletteReady 同款 pattern。
 */

interface RawTable {
    fontId: string;
    baseSize: number;
    ascent: number;
    descent: number;
    advances: Record<string, number>;
}

interface Table {
    baseSize: number;
    ascent: number;
    descent: number;
    /** advances[cp] 是 BMP codepoint 索引（0x0..0xFFFF），缺字 -1。Int16Array 节省 50% 内存。 */
    advances: Int16Array;
}

const tables = new Map<string, Table | null>();  // null = 加载失败 / 不存在 sentinel
const pendingLoads = new Map<string, Promise<void>>();
const readyHandlers: (() => void)[] = [];

/**
 * 失败条目的时间戳（fontId → Date.now()）。过了 {@link FAILED_RETRY_TTL_MS} 再来问就重新加载。
 *
 * <p>后端两条 metrics 通道的 404 都是<b>窗口性</b>的：内置字体表在后端首次渲染该字体前
 * 才惰性加载，用户字体由后台 worker 每枚 1-2 秒逐个注册。没有 TTL 时，前端在窗口期撞上
 * 一次 404 就把 null sentinel 永久钉进 {@link tables}，该字体整页退回 canonical 排版
 * （ASCII 半角、CJK 全角的粗略估算），而后端 TextLayout 用真实 advance——字距与换行点
 * 双端系统性不一致，只能整页刷新才恢复。</p>
 */
const failedAt = new Map<string, number>();

/** 失败条目多久之后允许重试（与 PreviewRenderer 的图片 / 图标缓存同一档）。 */
const FAILED_RETRY_TTL_MS = 10_000;

/**
 * 注册回调，metrics 表加载完成后触发（CanvasView 接到后 requestDraw）。
 * 返回 unsubscribe 闭包；CanvasView onBeforeUnmount 调用注销，避免 readyHandlers
 * 数组只增不减（旧闭包泄漏 + 重复 requestDraw）。
 */
export function onMetricsReady(fn: () => void): () => void {
    readyHandlers.push(fn);
    return () => offMetricsReady(fn);
}

/** 注销 onMetricsReady 注册的回调。 */
export function offMetricsReady(fn: () => void): void {
    const i = readyHandlers.indexOf(fn);
    if (i >= 0) readyHandlers.splice(i, 1);
}

function emitReady(): void {
    for (const fn of readyHandlers) fn();
}

/**
 * 该字体现在该不该发起（或重新发起）一次加载。
 * 同步、无分配——{@link advance} 每字每帧都会问它。
 */
function shouldAttemptLoad(fontId: string): boolean {
    if (pendingLoads.has(fontId)) return false;   // 已经在路上
    if (!tables.has(fontId)) return true;         // 从没加载过
    if (tables.get(fontId) !== null) return false; // 表已在手
    return Date.now() - (failedAt.get(fontId) ?? 0) > FAILED_RETRY_TTL_MS;
}

/**
 * 丢弃所有失败条目，让下次访问立刻重新加载（不等 TTL）。
 * 切 wall / 重连后调，语义同 IconLoader.clearFailedIconCache。
 */
export function clearFailedMetricsCache(): void {
    for (const [fontId, t] of tables) {
        if (t === null) tables.delete(fontId);
    }
    failedAt.clear();
}

/** 显式预加载某字体的表（PreviewRenderer 启动时 + fontId 出现时调）。 */
export function preloadMetrics(fontId: string): Promise<void> {
    if (!shouldAttemptLoad(fontId)) {
        return pendingLoads.get(fontId) ?? Promise.resolve();
    }
    // 过了 TTL 的失败条目必须先摘掉：留着 null sentinel 的话，本次加载还没回来之前
    // advance() 依旧读到「已加载但没表」，而重试成功后又会被覆盖——中间态没意义。
    tables.delete(fontId);
    failedAt.delete(fontId);

    const p = (async () => {
        try {
            // 先试 vite public dir（内置字体走构建期产物 web/public/fonts/{id}.metrics.json）
            let resp = await fetch(`/fonts/${encodeURIComponent(fontId)}.metrics.json`);
            if (!resp.ok) {
                // fallback 后端 API（用户字体由 FontRegistry.registerRuntime 注册到内存）
                resp = await fetch(`/api/font/metrics?id=${encodeURIComponent(fontId)}`);
                if (!resp.ok) {
                    markFailed(fontId);
                    return;
                }
            }
            const raw: RawTable = await resp.json();
            const advances = new Int16Array(0x10000);
            advances.fill(-1);
            for (const [cpStr, w] of Object.entries(raw.advances)) {
                const cp = parseInt(cpStr, 10);
                if (cp >= 0 && cp < advances.length) advances[cp] = w;
            }
            tables.set(fontId, {
                baseSize: raw.baseSize,
                ascent: raw.ascent,
                descent: raw.descent,
                advances,
            });
            emitReady();
        } catch (e) {
            console.warn(`[metrics] load failed for ${fontId}:`, e);
            markFailed(fontId);
        } finally {
            pendingLoads.delete(fontId);
        }
    })();
    pendingLoads.set(fontId, p);
    return p;
}

/** 记一条失败：null sentinel + 时间戳，{@link FAILED_RETRY_TTL_MS} 之后自动允许重试。 */
function markFailed(fontId: string): void {
    tables.set(fontId, null);
    failedAt.set(fontId, Date.now());
}

/**
 * advance(fontId, ch, fontSize)：
 * - 表存在且含字符：返回 round(baseAdv × fontSize / baseSize)
 * - 表不存在 / 字符缺：返回 -1（调用方走 canonicalCharWidth fallback）
 */
export function advance(fontId: string, ch: string, fontSize: number): number {
    const t = tables.get(fontId);
    if (!t) {
        // 首次访问触发异步加载（不阻塞）；失败条目过了 TTL 也从这里重新发起。
        // 本次一律返 -1 走 fallback。
        if (shouldAttemptLoad(fontId)) preloadMetrics(fontId);
        return -1;
    }
    const cp = ch.codePointAt(0);
    if (cp === undefined || cp < 0 || cp >= t.advances.length) return -1;
    const base = t.advances[cp];
    if (base <= 0) return -1;
    return Math.round(base * fontSize / t.baseSize);
}

// 删除 getAscent/getDescent 两个导出——它们零调用方（死代码导出且误导）。
// 当前双端基线固定 0.8 倍 fontSize（见 rendering.md §3.2），不按逐字体 ascent/descent 计算。
// RawTable / Table 仍保留 ascent/descent 字段以记录 .metrics.json 的实际形态（后端
// serializeToJson 仍输出），但前端不提供读法，避免被误用于基线计算。
