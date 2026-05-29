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

/**
 * 注册回调，加载完任意图标后触发（CanvasView 接到后 requestDraw）。
 * P3-40：返回 unsubscribe 闭包；CanvasView onBeforeUnmount 调用注销，避免 readyHandlers
 * 数组只增不减（旧闭包泄漏 + 重复 requestDraw）。
 */
export function onIconLoaded(fn: (id: string) => void): () => void {
    readyHandlers.push(fn);
    return () => offIconLoaded(fn);
}

/** P3-40：注销 onIconLoaded 注册的回调。 */
export function offIconLoaded(fn: (id: string) => void): void {
    const i = readyHandlers.indexOf(fn);
    if (i >= 0) readyHandlers.splice(i, 1);
}

/** 同步查询；已加载（成功 / 失败均算"完成"）返 entry（成功）或 null（失败 / 不存在）。 */
export function getCached(id: string): IconPathData | null | undefined {
    return cache.get(id);
}

/**
 * P2-70：丢弃失败（cache 值为 null）条目，让 wall 切换 / 重连后能对瞬时 404 / 网络抖动
 * 失败的图标重新发起加载。成功加载的图标（内容寻址，跨 wall 可共享）保留不动，避免无谓重拉。
 */
export function clearFailedIconCache(): void {
    for (const [id, v] of cache) {
        if (v === null) cache.delete(id);
    }
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
