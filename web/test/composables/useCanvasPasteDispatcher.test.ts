/**
 * @vitest-environment happy-dom
 *
 * 2026-05-25 bugfix：Ctrl+V paste 截杀。
 *
 * <p>根因：useCanvasShortcuts 在 keydown 阶段对 Ctrl+V preventDefault，
 * 浏览器随后不会再 fire `paste` event，useCanvasUpload.onPasteImage
 * 永远收不到。URL 粘贴 + image File 截图粘贴双失效。</p>
 *
 * <p>修法：删 Ctrl+V keydown handler，统一走 native `paste` event；
 * useCanvasUpload.onPasteImage 升级为三路 dispatcher（magic / File / URL）。</p>
 *
 * <p>本测试锁定新分发行为：</p>
 * <ol>
 *   <li>plain text URL → uploadFromUrl 被调用</li>
 *   <li>image File（mock DataTransferItem.kind='file'） → uploadAndPlace 被调用</li>
 *   <li>HikariCanvas magic text → clipboard.paste 被调用</li>
 *   <li>普通 text（非 URL 非 magic）→ 三路皆不调用</li>
 *   <li>editable 焦点下：URL 不接管 / magic 仍接管</li>
 *   <li>wall 锁定：URL / File 路径被拒</li>
 * </ol>
 *
 * <p>实现注：测试调用 api.onPasteImage(e) 直接触发 handler，避开
 * window.dispatchEvent 的跨 test listener 残留问题（happy-dom 下 vueuse
 * useEventListener 在 app.unmount 后偶现 listener 不清理）。</p>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, type App } from 'vue';
import { createPinia, setActivePinia } from 'pinia';

// ---------- WsClient mock：getWsClient 默认抛错，单测下用 vi.mock 替成 stub ----------
const wsSendMock = vi.fn().mockReturnValue('sent-id-1');
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ send: wsSendMock }),
    createWsClient: () => ({ send: wsSendMock }),
}));

// ---------- preloadImage mock：避免在 happy-dom 下创建真实 HTMLImageElement ----------
vi.mock('@/render/PreviewRenderer', () => ({
    preloadImage: vi.fn(),
}));

// 必须在 vi.mock 之后再 import 被测代码（vitest hoist 已保证 vi.mock 先于 import 生效）
import { useCanvasUpload } from '@/composables/useCanvasUpload';
import { CLIPBOARD_MAGIC } from '@/composables/useClipboard';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { ref } from 'vue';
import type { ProjectState } from '@/types/protocol';

// ---------- DataTransfer mock：happy-dom 自带 ClipboardEvent 但 clipboardData 行为简陋 ----------
interface MockItem {
    kind: 'string' | 'file';
    type: string;
    file?: File;
}

function makeClipboardEvent(opts: {
    text?: string;
    files?: MockItem[];
}): ClipboardEvent {
    const items: DataTransferItem[] = [];
    if (opts.text) {
        items.push({
            kind: 'string',
            type: 'text/plain',
            getAsFile() { return null; },
        } as unknown as DataTransferItem);
    }
    for (const it of opts.files ?? []) {
        items.push({
            kind: it.kind,
            type: it.type,
            getAsFile() { return it.file ?? null; },
        } as unknown as DataTransferItem);
    }
    const cd = {
        getData(t: string): string {
            if (t === 'text/plain') return opts.text ?? '';
            return '';
        },
        items,
    } as unknown as DataTransfer;
    const e = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(e, 'clipboardData', { value: cd, configurable: true });
    return e;
}

// ---------- mount harness ----------
function mountUpload() {
    let api: ReturnType<typeof useCanvasUpload> | null = null;
    let projectRef: ReturnType<typeof useProjectStore> | null = null;
    let netRef: ReturnType<typeof useNetworkStore> | null = null;
    const Comp = defineComponent({
        setup() {
            const brushHostRef = ref<HTMLElement | null>(document.createElement('div'));
            const fileInputRef = ref<HTMLInputElement | null>(null);
            api = useCanvasUpload({ brushHostRef, fileInputRef });
            projectRef = useProjectStore();
            netRef = useNetworkStore();
            return () => h('div');
        },
    });
    const container = document.createElement('div');
    const app = createApp(Comp);
    app.mount(container);
    return { api: api!, project: projectRef!, net: netRef!, app, container };
}

function tearDown(handle: { app: App; container: HTMLElement }) {
    handle.app.unmount();
    handle.container.remove();
}

/** 让 wallId / state 就绪到"可粘贴"状态：未锁定、有 sessionId、有 canvas + layer。 */
function setReady(project: ReturnType<typeof useProjectStore>,
                  net: ReturnType<typeof useNetworkStore>) {
    project.setWallMeta('w-test', null, null, 'self', 'self');
    net.sessionId = 'sess-1';
    const state: ProjectState = {
        version: 1,
        canvas: { widthMaps: 4, heightMaps: 4, background: '#FFFFFF' } as ProjectState['canvas'],
        elements: [],
        layers: [
            { id: 'L1', name: 'L1', visible: true, locked: false, opacity: 1,
              blendMode: 'normal', colorTag: null, elements: [] },
        ],
        activeLayerId: 'L1',
    } as unknown as ProjectState;
    project.setSnapshot(state);
}

// ---------- 测试 ----------

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
    setActivePinia(createPinia());
    wsSendMock.mockClear();
    fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ source: 'sha256-x', width: 100, height: 100, bytes: 1000 }),
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe('useCanvasUpload paste dispatcher', () => {
    it('路径 c：粘贴 plain text URL → 触发 uploadFromUrl（fetch /api/upload/url）', async () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);

        const e = makeClipboardEvent({ text: 'https://i.imgur.com/abc123' });
        handle.api.onPasteImage(e);

        // 让 microtask 跑完（uploadFromUrl 是 async fetch）
        await Promise.resolve();
        await Promise.resolve();

        expect(fetchMock).toHaveBeenCalledTimes(1);
        const fetchUrl = fetchMock.mock.calls[0][0] as string;
        expect(fetchUrl.startsWith('/api/upload/url')).toBe(true);
        expect(e.defaultPrevented).toBe(true);

        tearDown(handle);
    });

    it('路径 b：粘贴 image File → 触发 uploadAndPlace（fetch /api/upload multipart）', async () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);

        const file = new File([new Uint8Array([0xFF, 0xD8, 0xFF])], 'paste.png', { type: 'image/png' });
        const e = makeClipboardEvent({
            files: [{ kind: 'file', type: 'image/png', file }],
        });
        // e.preventDefault 在命中 image File 时同步调用；fetch 走 readFileAsDataUrl
        // → FileReader.onload（happy-dom 下需 setTimeout(0) 等触发）→ fetch。
        handle.api.onPasteImage(e);

        // 等 e.preventDefault 同步行为先生效（命中文件路径）
        expect(e.defaultPrevented).toBe(true);

        // 等 FileReader macrotask + fetch microtask
        await new Promise((r) => setTimeout(r, 20));

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(fetchMock.mock.calls[0][0]).toBe('/api/upload');

        tearDown(handle);
    });

    it('路径 a：粘贴 HikariCanvas magic text → 触发 clipboard.paste 元素粘贴（ws.send element.add）', () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);

        const payload = {
            elements: [
                { id: 'orig-1', type: 'rect', x: 10, y: 10, w: 50, h: 30, fill: '#FF0000' },
            ],
            timestamp: new Date().toISOString(),
            sourceWallId: 'w-other',
        };
        const text = CLIPBOARD_MAGIC + JSON.stringify(payload);
        const e = makeClipboardEvent({ text });
        handle.api.onPasteImage(e);

        // ws.send 是同步——无需 await
        expect(wsSendMock).toHaveBeenCalledTimes(1);
        const [op, body] = wsSendMock.mock.calls[0];
        expect(op).toBe('element.add');
        expect((body as { type: string }).type).toBe('rect');
        // 偏移 +10,+10
        expect((body as { props: { x: number; y: number } }).props.x).toBe(20);
        expect((body as { props: { x: number; y: number } }).props.y).toBe(20);
        expect(e.defaultPrevented).toBe(true);

        // fetch 不被调用（不走 upload 路径）
        expect(fetchMock).not.toHaveBeenCalled();

        tearDown(handle);
    });

    it('普通文本（非 URL 非 magic）→ 三路皆不动（不 preventDefault / 不 fetch / 不 ws.send）', () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);

        const e = makeClipboardEvent({ text: 'hello world this is just plain text' });
        handle.api.onPasteImage(e);

        expect(fetchMock).not.toHaveBeenCalled();
        expect(wsSendMock).not.toHaveBeenCalled();
        expect(e.defaultPrevented).toBe(false);

        tearDown(handle);
    });

    it('editable 焦点下：普通 URL 不接管（保留默认 paste 行为），但 magic 仍接管', () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);

        // 模拟 input 聚焦
        const input = document.createElement('input');
        document.body.appendChild(input);
        input.focus();
        expect(document.activeElement).toBe(input);

        // 普通 URL：editable 下不接管
        const e1 = makeClipboardEvent({ text: 'https://example.com/image.png' });
        handle.api.onPasteImage(e1);
        expect(fetchMock).not.toHaveBeenCalled();
        expect(e1.defaultPrevented).toBe(false);

        // magic：editable 下仍接管（设计意图——否则 magic 字符串会被塞进 input）
        const payload = {
            elements: [{ id: 'x', type: 'rect', x: 0, y: 0, w: 10, h: 10 }],
            timestamp: '2026-05-25T00:00:00Z',
            sourceWallId: null,
        };
        const e2 = makeClipboardEvent({ text: CLIPBOARD_MAGIC + JSON.stringify(payload) });
        handle.api.onPasteImage(e2);
        expect(wsSendMock).toHaveBeenCalledTimes(1);
        expect(e2.defaultPrevented).toBe(true);

        input.remove();
        tearDown(handle);
    });

    it('wall 锁定时：URL 路径不发 fetch（onPasteImage 早 return）', () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);
        // 切到 locked
        handle.project.setWallMeta('w-test', null, Date.now(), 'self', 'self');

        const e = makeClipboardEvent({ text: 'https://example.com/foo' });
        handle.api.onPasteImage(e);
        expect(fetchMock).not.toHaveBeenCalled();
        expect(e.defaultPrevented).toBe(false);

        tearDown(handle);
    });

    it('wall 锁定 + magic text → clipboard.paste 内部拒（不 ws.send）+ 写 err log', () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);
        handle.project.setWallMeta('w-test', null, Date.now(), 'self', 'self');

        const payload = {
            elements: [{ id: 'x', type: 'rect', x: 0, y: 0, w: 10, h: 10 }],
            timestamp: '2026-05-25T00:00:00Z',
            sourceWallId: null,
        };
        const e = makeClipboardEvent({ text: CLIPBOARD_MAGIC + JSON.stringify(payload) });
        const logsBefore = handle.net.logs.length;
        handle.api.onPasteImage(e);

        // magic 路径会 preventDefault 进入 clipboard.paste；内部 isLocked 判定 → 拒 + 写 err log
        expect(e.defaultPrevented).toBe(true);
        expect(wsSendMock).not.toHaveBeenCalled();
        expect(handle.net.logs.length).toBe(logsBefore + 1);
        expect(handle.net.logs[handle.net.logs.length - 1].level).toBe('err');

        tearDown(handle);
    });
});
