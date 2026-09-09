// @vitest-environment happy-dom
/**
 * FontLoader 的「开始 / 结束」加载信号。
 *
 * <p>这两个信号是右下角"字体加载中"提示条的唯一数据源。最要紧的一条不是成功路径，
 * 而是<b>失败路径也必须报结束</b>：{@code onFontLoaded} 只在成功时触发，如果结束信号
 * 也只挂在成功分支上，一次 404 / 网络抖动就会让提示条永远转下去，而字体其实早已放弃、
 * 画布正安静地用系统 fallback 画字。</p>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';

/** 必定 load 成功的 FontFace 替身（happy-dom 没有真的 FontFace）。 */
function stubOkFontFace(): void {
    class OkFontFace {
        status = 'loaded';
        constructor(readonly family: string, readonly source: string) {}
        load(): Promise<this> { return Promise.resolve(this); }
    }
    vi.stubGlobal('FontFace', OkFontFace);
    vi.stubGlobal('document', {
        ...globalThis.document,
        fonts: { add: () => {} },
    });
}

/** 必定 load 失败的 FontFace 替身。 */
function stubFailingFontFace(): void {
    class FailingFontFace {
        status = 'error';
        constructor(readonly family: string, readonly source: string) {}
        load(): Promise<never> { return Promise.reject(new Error('font fetch failed')); }
    }
    vi.stubGlobal('FontFace', FailingFontFace);
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

describe('FontLoader 加载信号', () => {
    it('成功加载：start 与 end 各触发一次，且带上 fontId', async () => {
        stubOkFontFace();
        const { ensureLoaded, onFontLoadStart, onFontLoadEnd } = await import('../FontLoader');

        const starts: string[] = [];
        const ends: string[] = [];
        onFontLoadStart(id => starts.push(id));
        onFontLoadEnd(id => ends.push(id));

        await ensureLoaded('inter');

        expect(starts).toEqual(['inter']);
        expect(ends).toEqual(['inter']);
    });

    it('加载失败时 end 依然触发 —— 否则提示条永远消不掉', async () => {
        stubFailingFontFace();
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        const { ensureLoaded, onFontLoadStart, onFontLoadEnd } = await import('../FontLoader');

        const starts: string[] = [];
        const ends: string[] = [];
        onFontLoadStart(id => starts.push(id));
        onFontLoadEnd(id => ends.push(id));

        await ensureLoaded('inter');

        expect(starts).toEqual(['inter']);
        expect(ends).toEqual(['inter']);
    });

    it('命中缓存的重复调用不再报 start（否则每帧的 ensureLoaded 会刷屏）', async () => {
        stubOkFontFace();
        const { ensureLoaded, onFontLoadStart } = await import('../FontLoader');

        const starts: string[] = [];
        onFontLoadStart(id => starts.push(id));

        await ensureLoaded('inter');
        await ensureLoaded('inter');
        await ensureLoaded('inter');

        expect(starts).toEqual(['inter']);
    });

    it('多字体并发：每个 id 各报一次，start / end 成对', async () => {
        stubOkFontFace();
        const { ensureLoaded, onFontLoadStart, onFontLoadEnd } = await import('../FontLoader');

        const starts: string[] = [];
        const ends: string[] = [];
        onFontLoadStart(id => starts.push(id));
        onFontLoadEnd(id => ends.push(id));

        await Promise.all([ensureLoaded('inter'), ensureLoaded('lobster')]);

        expect(starts.sort()).toEqual(['inter', 'lobster']);
        expect(ends.sort()).toEqual(['inter', 'lobster']);
    });

    it('unsubscribe 之后不再收到信号', async () => {
        stubOkFontFace();
        const { ensureLoaded, onFontLoadStart, onFontLoadEnd } = await import('../FontLoader');

        const starts: string[] = [];
        const ends: string[] = [];
        const offStart = onFontLoadStart(id => starts.push(id));
        const offEnd = onFontLoadEnd(id => ends.push(id));

        await ensureLoaded('inter');
        offStart();
        offEnd();
        await ensureLoaded('lobster');

        expect(starts).toEqual(['inter']);
        expect(ends).toEqual(['inter']);
    });
});
