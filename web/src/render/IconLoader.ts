/**
 * M26.2：矢量图标加载器（前端镜像后端 IconRegistry 查询路径）。
 *
 * <p>FA / Material / user SVG 矢量图标的 path d + viewBox 走
 * {@code GET /api/icon/paths?id=<pack>/<name>} 拉取。同 FontLoader / 图片上传 cache 模式：
 * <ol>
 *   <li>{@link ensureLoaded} 幂等 async：首次发 fetch；已加载 / 正在加载返现有 Promise</li>
 *   <li>cache 内 null 表示"加载失败 / 不存在"，让 PreviewRenderer 走占位 ? 渲染</li>
 *   <li>加载完触发 {@link onIconLoaded} 回调，CanvasView 接到后 requestDraw（与
 *       onFontLoaded / onPaletteReady / onIconReady 同款 pattern）</li>
 * </ol></p>
 *
 * <p><b>边界</b>：legacy PNG 形态（source 不含 {@code /}）不走本模块——PreviewRenderer
 * 自己在 drawIcon 入口分流到原 {@code getIconImage} PNG 缓存。</p>
 */

export interface IconPathData {
    viewBox: string;        // "minX minY w h"
    paths: { d: string }[]; // M26 v1 单元素；v2 多 path / per-path fill 颜色预留
}

const cache = new Map<string, IconPathData | null>();
const pending = new Map<string, Promise<void>>();
const readyHandlers: ((id: string) => void)[] = [];

/** 注册回调，加载完任意图标后触发（CanvasView 接到后 requestDraw）。 */
export function onIconLoaded(fn: (id: string) => void): void {
    readyHandlers.push(fn);
}

/** 同步查询；已加载（成功 / 失败均算"完成"）返 entry（成功）或 null（失败 / 不存在）。 */
export function getCached(id: string): IconPathData | null | undefined {
    return cache.get(id);
}

/**
 * 幂等加载 path d + viewBox。首次发 fetch；已加载 / 正在加载返现有 Promise。
 * 失败静默——上层 drawIcon 走占位。
 *
 * @return 加载完成后的数据；null = 404 / 网络失败 / 解析错
 */
export async function ensureLoaded(id: string): Promise<IconPathData | null> {
    if (cache.has(id)) return cache.get(id) ?? null;
    const inflight = pending.get(id);
    if (inflight) {
        await inflight;
        return cache.get(id) ?? null;
    }

    const p = (async () => {
        try {
            const resp = await fetch(`/api/icon/paths?id=${encodeURIComponent(id)}`);
            if (!resp.ok) {
                cache.set(id, null);
                return;
            }
            const data = await resp.json();
            if (typeof data?.viewBox !== 'string' || !Array.isArray(data?.paths)) {
                cache.set(id, null);
                return;
            }
            const paths = (data.paths as unknown[])
                .filter((p): p is { d: string } => !!p && typeof (p as { d?: unknown }).d === 'string')
                .map((p) => ({ d: p.d }));
            cache.set(id, { viewBox: data.viewBox, paths });
            for (const fn of readyHandlers) fn(id);
        } catch (e) {
            console.warn(`[IconLoader] failed to load ${id}:`, e);
            cache.set(id, null);
        } finally {
            pending.delete(id);
        }
    })();
    pending.set(id, p);
    await p;
    return cache.get(id) ?? null;
}
