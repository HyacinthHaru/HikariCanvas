/**
 * 统一字体加载与元数据查询层。
 *
 * 浏览器 FontFace API 将内置字体与用户字体（`GET /api/font/file?id=X`）动态注册到
 * `document.fonts`，`fontId` 直接作为 `ctx.font` 的 family 名。
 *
 * `ensureLoaded(fontId)` 幂等异步：首次调用发起 fetch 并注册 `FontFace`，
 * 已加载或正在加载时复用现有 Promise；加载完成后触发 `onFontLoaded(fontId)`
 * 通知画布重绘，失败时静默退回浏览器默认字体并在 TTL 后允许自动重试。
 */

export interface FontInfo {
    id: string;
    displayName: string;
    source: 'builtin' | 'user';
    format: 'ttf' | 'otf';
    pixelated: boolean;
    nativeSize: number;
}

interface LoadState {
    promise: Promise<void>;
    face?: FontFace;
    /** 加载失败的时间戳；0 = 未失败。超过 TTL 后允许重试。 */
    failedAt: number;
}

/** 失败条目允许重试的冷却时间，避免瞬时网络抖动或注册窗口期 404 将字体锁死到整页刷新。 */
const FAILED_RETRY_TTL_MS = 10_000;

const loaded = new Map<string, LoadState>();
const readyHandlers: ((fontId: string) => void)[] = [];
const loadStartHandlers: ((fontId: string) => void)[] = [];
const loadEndHandlers: ((fontId: string) => void)[] = [];

let fontListPromise: Promise<FontInfo[]> | null = null;

/**
 * 拉取并缓存 `/api/font/list` 字体元数据列表（内置 + 用户字体）。
 * 请求失败或非 2xx 时不留缓存，允许后续挂载重试。
 */
export function loadFontList(): Promise<FontInfo[]> {
    if (fontListPromise) return fontListPromise;
    fontListPromise = (async () => {
        try {
            const resp = await fetch('/api/font/list');
            if (!resp.ok) {
                fontListPromise = null;
                return [];
            }
            const data = (await resp.json()) as { fonts?: FontInfo[] };
            return Array.isArray(data.fonts) ? data.fonts : [];
        } catch (e) {
            console.warn('[FontLoader] /api/font/list failed:', e);
            fontListPromise = null;
            return [];
        }
    })();
    return fontListPromise;
}

/**
 * 注册字体加载成功回调（供画布触发重绘），返回取消订阅闭包。
 */
export function onFontLoaded(fn: (fontId: string) => void): () => void {
    readyHandlers.push(fn);
    return () => offFontLoaded(fn);
}

/** 注销 `onFontLoaded` 注册的回调。 */
export function offFontLoaded(fn: (fontId: string) => void): void {
    const i = readyHandlers.indexOf(fn);
    if (i >= 0) readyHandlers.splice(i, 1);
}

/** 订阅某字体开始真实网络加载，返回取消订阅闭包。 */
export function onFontLoadStart(fn: (fontId: string) => void): () => void {
    loadStartHandlers.push(fn);
    return () => {
        const i = loadStartHandlers.indexOf(fn);
        if (i >= 0) loadStartHandlers.splice(i, 1);
    };
}

/** 订阅某字体加载结束（无论成功或失败都会触发），返回取消订阅闭包。 */
export function onFontLoadEnd(fn: (fontId: string) => void): () => void {
    loadEndHandlers.push(fn);
    return () => {
        const i = loadEndHandlers.indexOf(fn);
        if (i >= 0) loadEndHandlers.splice(i, 1);
    };
}

/** 同步查询字体是否已完成加载（`status === 'loaded'`）。 */
export function isLoaded(fontId: string): boolean {
    const state = loaded.get(fontId);
    return !!(state?.face && state.face.status === 'loaded');
}

/** 丢弃加载失败的缓存条目（切画板或重连后调用，使下次 `ensureLoaded` 立即重试）。 */
export function clearFailedFontCache(): void {
    for (const [fontId, state] of loaded) {
        if (state.failedAt !== 0) loaded.delete(fontId);
    }
    fontListPromise = null;
}

/**
 * 幂等加载指定字体。首次调用发起 fetch 与 `FontFace` 注册；已加载或加载中复用现有 Promise。
 */
export function ensureLoaded(fontId: string): Promise<void> {
    const existing = loaded.get(fontId);
    if (existing) {
        if (existing.failedAt === 0 || Date.now() - existing.failedAt <= FAILED_RETRY_TTL_MS) {
            return existing.promise;
        }
        loaded.delete(fontId);
    }

    const state: LoadState = { promise: Promise.resolve(), failedAt: 0 };
    loaded.set(fontId, state);

    for (const fn of loadStartHandlers) fn(fontId);

    state.promise = (async () => {
        try {
            const url = `/api/font/file?id=${encodeURIComponent(fontId)}`;
            const face = new FontFace(fontId, `url(${url})`);
            state.face = face;
            await face.load();
            document.fonts.add(face);
            for (const fn of readyHandlers) fn(fontId);
        } catch (e) {
            console.warn(`[FontLoader] failed to load ${fontId}:`, e);
            state.failedAt = Date.now();
        } finally {
            for (const fn of loadEndHandlers) fn(fontId);
        }
    })();

    return state.promise;
}
