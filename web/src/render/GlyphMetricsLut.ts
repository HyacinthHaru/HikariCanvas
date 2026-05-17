/**
 * M20-P3：前端 per-font 字符 advance 查找表（Java FontMetricsTable mirror）。
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

export function onMetricsReady(fn: () => void): void {
    readyHandlers.push(fn);
}

function emitReady(): void {
    for (const fn of readyHandlers) fn();
}

/** 显式预加载某字体的表（PreviewRenderer 启动时 + fontId 出现时调）。 */
export function preloadMetrics(fontId: string): Promise<void> {
    if (tables.has(fontId)) return Promise.resolve();
    const existing = pendingLoads.get(fontId);
    if (existing) return existing;

    const p = (async () => {
        try {
            // 先试 vite public dir（内置字体走构建期产物 web/public/fonts/{id}.metrics.json）
            let resp = await fetch(`/fonts/${encodeURIComponent(fontId)}.metrics.json`);
            if (!resp.ok) {
                // M20-P4：fallback 后端 API（用户字体由 FontRegistry.registerRuntime 注册到内存）
                resp = await fetch(`/api/font/metrics?id=${encodeURIComponent(fontId)}`);
                if (!resp.ok) {
                    tables.set(fontId, null);
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
            tables.set(fontId, null);
        } finally {
            pendingLoads.delete(fontId);
        }
    })();
    pendingLoads.set(fontId, p);
    return p;
}

/**
 * advance(fontId, ch, fontSize)：
 * - 表存在且含字符：返回 round(baseAdv × fontSize / baseSize)
 * - 表不存在 / 字符缺：返回 -1（调用方走 canonicalCharWidth fallback）
 */
export function advance(fontId: string, ch: string, fontSize: number): number {
    const t = tables.get(fontId);
    if (!t) {
        // 首次访问触发异步加载（不阻塞）；本次返 -1 走 fallback
        if (!tables.has(fontId)) preloadMetrics(fontId);
        return -1;
    }
    const cp = ch.codePointAt(0);
    if (cp === undefined || cp < 0 || cp >= t.advances.length) return -1;
    const base = t.advances[cp];
    if (base <= 0) return -1;
    return Math.round(base * fontSize / t.baseSize);
}

/** 字体 ascent（像素）@ baseSize；调用方按 fontSize 自己缩放。-1 = 表不存在。 */
export function getAscent(fontId: string): number {
    return tables.get(fontId)?.ascent ?? -1;
}

export function getDescent(fontId: string): number {
    return tables.get(fontId)?.descent ?? -1;
}
