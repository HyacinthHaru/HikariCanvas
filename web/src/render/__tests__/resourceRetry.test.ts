// @vitest-environment happy-dom
/**
 * 渲染资源加载失败之后能不能自愈。
 *
 * <p>三块共同的老毛病：第一次拿不到就把「失败」这件事永久记在模块级缓存里，之后再问永远
 * 拿同一份失败结果，不会再发第二次请求——只有整页刷新才恢复。而这些 404 本来大多是<b>窗口性</b>的
 * （后端启动、字体后台注册、网络抖动）。</p>
 *
 * <ul>
 *   <li>palette：失败的 promise 被缓存 → dither 元素整页降级成 clean 渲染</li>
 *   <li>字体文件：失败条目永不移除 → 整场会话用浏览器 system fallback 画字，与游戏内对不上</li>
 *   <li>字形 metrics：null sentinel 永久占位 → 整页退回 canonical 排版，双端字距 / 换行点不一致</li>
 * </ul>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';

const PALETTE_ENTRIES = [
    { index: 0, rgb: [0, 0, 0] as [number, number, number], alpha: 0 },
    { index: 4, rgb: [255, 0, 0] as [number, number, number], alpha: 255 },
];

/** 在任何人 spy 之前抓住真正的 Date.now——推进"时钟"的用例都基于它算偏移。 */
const REAL_NOW = Date.now;

/** 把时钟往后推，越过失败重试窗口（TTL 10s）。 */
function advanceClockPastRetryWindow(): void {
    vi.spyOn(Date, 'now').mockImplementation(() => REAL_NOW() + 11_000);
}

beforeEach(() => {
    vi.resetModules();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

describe('PaletteLut.getPaletteLut', () => {
    it('第一次失败之后，下一次调用会真的重新发 fetch（不是拿同一个已 reject 的 promise）', async () => {
        const fetchMock = vi.fn()
            .mockRejectedValueOnce(new Error('backend not up yet'))
            .mockResolvedValueOnce({ ok: true, json: async () => PALETTE_ENTRIES });
        vi.stubGlobal('fetch', fetchMock);

        const { getPaletteLut } = await import('../PaletteLut');
        await expect(getPaletteLut()).rejects.toThrow('backend not up yet');
        const lut = await getPaletteLut();

        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(lut.matchColor(255, 0, 0)).toBe(4);
    });

    it('成功之后仍然只 fetch 一次（缓存照常生效）', async () => {
        const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => PALETTE_ENTRIES });
        vi.stubGlobal('fetch', fetchMock);

        const { getPaletteLut } = await import('../PaletteLut');
        await getPaletteLut();
        await getPaletteLut();
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });
});

/** 一个必定 load 失败的 FontFace 替身（jsdom / happy-dom 都没有真的 FontFace）。 */
function stubFailingFontFace(): void {
    class FailingFontFace {
        status = 'error';
        constructor(readonly family: string, readonly source: string) {}
        load(): Promise<never> {
            return Promise.reject(new Error('font fetch failed'));
        }
    }
    vi.stubGlobal('FontFace', FailingFontFace);
}

describe('FontLoader.ensureLoaded', () => {
    it('加载失败的字体过了重试窗口会重新拉一次', async () => {
        stubFailingFontFace();
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        const { ensureLoaded } = await import('../FontLoader');

        const loadSpy = vi.spyOn(
            (globalThis as unknown as { FontFace: { prototype: { load(): Promise<never> } } })
                .FontFace.prototype,
            'load',
        );

        await ensureLoaded('inter');
        expect(loadSpy).toHaveBeenCalledTimes(1);

        // 窗口内再问 → 直接吃缓存，不重复拉
        await ensureLoaded('inter');
        expect(loadSpy).toHaveBeenCalledTimes(1);

        // 把时钟推过重试窗口（10s）
        advanceClockPastRetryWindow();
        await ensureLoaded('inter');
        expect(loadSpy).toHaveBeenCalledTimes(2);
    });

    it('clearFailedFontCache 立刻放行重试（切 wall / 重连时用）', async () => {
        stubFailingFontFace();
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        const { ensureLoaded, clearFailedFontCache } = await import('../FontLoader');
        const loadSpy = vi.spyOn(
            (globalThis as unknown as { FontFace: { prototype: { load(): Promise<never> } } })
                .FontFace.prototype,
            'load',
        );

        await ensureLoaded('inter');
        await ensureLoaded('inter');
        expect(loadSpy).toHaveBeenCalledTimes(1);

        clearFailedFontCache();
        await ensureLoaded('inter');
        expect(loadSpy).toHaveBeenCalledTimes(2);
    });
});

describe('GlyphMetricsLut', () => {
    /** 两条通道（vite public 静态文件 + 后端 API）都 404 = 后端还没把这枚字体的表准备好。 */
    function stubMetricsFetch(responses: Array<{ ok: boolean; body?: unknown }>): ReturnType<typeof vi.fn> {
        let i = 0;
        const fetchMock = vi.fn(async () => {
            const r = responses[Math.min(i++, responses.length - 1)];
            return { ok: r.ok, json: async () => r.body };
        });
        vi.stubGlobal('fetch', fetchMock);
        return fetchMock;
    }

    it('两条通道都 404 之后，过了重试窗口 advance 会重新触发加载', async () => {
        // 前两次（静态 + API）都 404；之后返回一张真表
        const table = { fontId: 'inter', baseSize: 32, ascent: 26, descent: 6, advances: { 65: 20 } };
        const fetchMock = stubMetricsFetch([
            { ok: false }, { ok: false },
            { ok: true, body: table },
        ]);
        const { advance, preloadMetrics } = await import('../GlyphMetricsLut');

        await preloadMetrics('inter');
        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(advance('inter', 'A', 32)).toBe(-1);
        // 窗口内不重试
        expect(fetchMock).toHaveBeenCalledTimes(2);

        advanceClockPastRetryWindow();
        // 过了窗口：advance 自己就会重新发起加载（本次仍返 -1，加载是异步的）
        expect(advance('inter', 'A', 32)).toBe(-1);
        await preloadMetrics('inter');
        expect(advance('inter', 'A', 32)).toBe(20);
    });

    it('clearFailedMetricsCache 立刻放行重试', async () => {
        const table = { fontId: 'inter', baseSize: 32, ascent: 26, descent: 6, advances: { 65: 20 } };
        stubMetricsFetch([{ ok: false }, { ok: false }, { ok: true, body: table }]);
        const { advance, preloadMetrics, clearFailedMetricsCache } = await import('../GlyphMetricsLut');

        await preloadMetrics('inter');
        expect(advance('inter', 'A', 32)).toBe(-1);

        clearFailedMetricsCache();
        await preloadMetrics('inter');
        expect(advance('inter', 'A', 32)).toBe(20);
    });

    it('表拿到手之后不再重复请求', async () => {
        const table = { fontId: 'inter', baseSize: 32, ascent: 26, descent: 6, advances: { 65: 20 } };
        const fetchMock = stubMetricsFetch([{ ok: true, body: table }]);
        const { advance, preloadMetrics } = await import('../GlyphMetricsLut');

        await preloadMetrics('inter');
        expect(advance('inter', 'A', 32)).toBe(20);
        await preloadMetrics('inter');
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });
});
