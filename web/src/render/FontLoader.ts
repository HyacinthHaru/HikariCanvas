/**
 * 统一字体加载层。
 *
 * <p>浏览器 FontFace API 让任意字体运行时动态注册到 document.fonts。
 * 内置字体 + 用户字体走同一条加载路径（{@code GET /api/font/file?id=X}），删除
 * style.css 静态 @font-face 双轨。删除 PreviewRenderer.fontFamily() 的 KNOWN
 * 白名单——fontId 直接当 ctx.font 的 family。</p>
 *
 * <p>ensureLoaded(fontId) 幂等 async：首次 fetch → new FontFace → document.fonts.add；
 * 已加载或正在加载直接返现有 Promise（去重）。加载完触发 onFontLoaded(fontId)，
 * CanvasView 接收回调走 requestDraw（与 onIconReady / onPaletteReady / onMetricsReady
 * 同款 pattern）。失败静默——浏览器走 system fallback。</p>
 *
 * <p>注意：FontFace API 加载流程</p>
 * <ol>
 *   <li>new FontFace(id, "url(...)") → 创建未加载的 face</li>
 *   <li>await face.load() → 触发 HTTP fetch + 解析；face.status 'unloaded' → 'loading' → 'loaded' / 'error'</li>
 *   <li>document.fonts.add(face) → 注册到全局，ctx.font 后续可用</li>
 * </ol>
 */

interface LoadState {
    promise: Promise<void>;
    face?: FontFace;
    /** 加载失败的时间戳；0 = 没失败过。过了 TTL 再问就重新拉一次。 */
    failedAt: number;
}

/**
 * 失败条目多久之后允许重试（与 PreviewRenderer 的图片 / 图标缓存同一档）。
 *
 * <p>没有它的话，一次 fetch 抖动 / 后端字体注册窗口期的 404 就会把该字体钉死到整页刷新
 * 为止：{@link ensureLoaded} 第二次调用直接命中已 resolve 的旧 promise 立刻返回，
 * PreviewRenderer 每帧那句 {@code if (!isLoaded) ensureLoaded()} 变成纯空转，
 * 编辑器整场会话都用浏览器 system fallback 画字——与游戏里后端拿真 TTF 渲染的结果对不上。</p>
 */
const FAILED_RETRY_TTL_MS = 10_000;

const loaded = new Map<string, LoadState>();
const readyHandlers: ((fontId: string) => void)[] = [];

/**
 * 一次真实网络加载的开始 / 结束信号（UI 用来提示"字体正在加载"）。
 *
 * <p>与 {@link onFontLoaded} 的区别：`onFontLoaded` 只在**成功**时触发，语义是
 * "可以重绘了"；这里的 `end` 无论成功还是失败都触发。少了失败那条路，加载挂掉时
 * 提示条会永远转下去。</p>
 *
 * <p>只有真正发起 fetch 才会触发——命中缓存 / 正在加载的重复调用直接返现有 promise，
 * 不再报一次 start。</p>
 */
const loadStartHandlers: ((fontId: string) => void)[] = [];
const loadEndHandlers: ((fontId: string) => void)[] = [];

/**
 * 注册回调，加载完任意字体后触发（CanvasView 接到后 requestDraw）。
 * 返回 unsubscribe 闭包；CanvasView onBeforeUnmount 调用以注销，避免
 * 组件多次挂载/卸载时 readyHandlers 数组只增不减（旧闭包持组件引用泄漏 + 重复 requestDraw）。
 */
export function onFontLoaded(fn: (fontId: string) => void): () => void {
    readyHandlers.push(fn);
    return () => offFontLoaded(fn);
}

/** 注销 onFontLoaded 注册的回调。 */
/** 订阅"某字体开始真实加载"。返回 unsubscribe 闭包（用法同 {@link onFontLoaded}）。 */
export function onFontLoadStart(fn: (fontId: string) => void): () => void {
    loadStartHandlers.push(fn);
    return () => {
        const i = loadStartHandlers.indexOf(fn);
        if (i >= 0) loadStartHandlers.splice(i, 1);
    };
}

/** 订阅"某字体加载结束"——**成功与失败都会触发**。返回 unsubscribe 闭包。 */
export function onFontLoadEnd(fn: (fontId: string) => void): () => void {
    loadEndHandlers.push(fn);
    return () => {
        const i = loadEndHandlers.indexOf(fn);
        if (i >= 0) loadEndHandlers.splice(i, 1);
    };
}

export function offFontLoaded(fn: (fontId: string) => void): void {
    const i = readyHandlers.indexOf(fn);
    if (i >= 0) readyHandlers.splice(i, 1);
}

/** 同步查询是否已加载完成（status==='loaded'）。 */
export function isLoaded(fontId: string): boolean {
    const state = loaded.get(fontId);
    return !!(state?.face && state.face.status === 'loaded');
}

/**
 * 丢弃加载失败的条目，让下次 {@link ensureLoaded} 立刻重新拉（不等 TTL）。
 * 切 wall / 重连后调，语义同 IconLoader.clearFailedIconCache。
 */
export function clearFailedFontCache(): void {
    for (const [fontId, state] of loaded) {
        if (state.failedAt !== 0) loaded.delete(fontId);
    }
}

/**
 * 幂等加载某字体。首次调用发起 fetch + FontFace 注册；已加载 / 正在加载返现有 Promise。
 * 失败静默——浏览器 ctx.font 走 system fallback，不抛；过 {@link FAILED_RETRY_TTL_MS}
 * 后下一次调用会重新拉一遍（瞬时 404 / 网络抖动自愈，不必整页刷新）。
 */
export function ensureLoaded(fontId: string): Promise<void> {
    const existing = loaded.get(fontId);
    if (existing) {
        if (existing.failedAt === 0 || Date.now() - existing.failedAt <= FAILED_RETRY_TTL_MS) {
            return existing.promise;
        }
        // 失败条目过了 TTL：摘掉后走下面的正常加载路径重来一次。
        loaded.delete(fontId);
    }

    // 先占位 state 防并发竞争；promise 后挂上
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
            // 不抛 — 调用方继续走 system fallback
        } finally {
            // finally 而非 try 尾：失败路径也必须报结束，否则 UI 的加载提示永远不消。
            for (const fn of loadEndHandlers) fn(fontId);
        }
    })();

    return state.promise;
}
