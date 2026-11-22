// @vitest-environment happy-dom
/**
 * useCanvasUpload 单元测试：URL 识别与三路粘贴分发（magic / File / URL）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, ref, type App } from 'vue';
import { createPinia, setActivePinia } from 'pinia';

const wsSendMock = vi.fn().mockReturnValue('sent-id-1');
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ send: wsSendMock }),
    createWsClient: () => ({ send: wsSendMock }),
}));

vi.mock('@/render/PreviewRenderer', () => ({
    preloadImage: vi.fn(),
}));

import { looksLikeHttpUrl, useCanvasUpload } from '../useCanvasUpload';
import { CLIPBOARD_MAGIC } from '@/composables/useClipboard';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import type { ProjectState } from '@/types/protocol';

describe('looksLikeHttpUrl', () => {
    describe('应该匹配（合法 URL）', () => {
        it.each([
            'https://example.com/image.png',
            'https://example.com/image.jpg',
            'https://example.com/image.jpeg',
            'https://example.com/image.gif',
            'https://example.com/image.webp',
            'HTTPS://example.com/foo',
            'HTTP://example.com/foo',
            'https://i.imgur.com/abc123',
            'https://cdn.discordapp.com/attachments/123/456/img.png?ex=abc&hm=def',
            'https://example.com/api/avatar/userid',
            'https://example.com/image.png#fragment',
            'https://example.com/api/img?format=png&id=123&token=xyz',
            'http://example.com',
            'https://example.com:8080/path/to/resource',
            'https://example.com/p%20path/img%20file',
        ])('匹配: %s', (url) => {
            expect(looksLikeHttpUrl(url)).toBe(true);
        });
    });

    describe('不应该匹配（非 URL / 不合法）', () => {
        it.each([
            ['空字符串', ''],
            ['纯文本', 'hello world'],
            ['无 scheme', 'example.com/image.png'],
            ['file scheme', 'file:///etc/passwd'],
            ['javascript scheme', 'javascript:alert(1)'],
            ['data scheme', 'data:image/png;base64,iVBOR...'],
            ['ftp scheme', 'ftp://example.com/file'],
            ['仅 scheme', 'https://'],
            ['含空白（多 token）', 'https://example.com/foo bar.png'],
            ['多行', 'https://example.com\nhttps://other.com'],
            ['含尖括号 HTML', '<https://example.com/img.png>'],
            ['含双引号', '"https://example.com/img.png"'],
            ['含单引号', "'https://example.com/img.png'"],
        ])('不匹配 (%s): %s', (_label, url) => {
            expect(looksLikeHttpUrl(url)).toBe(false);
        });
    });

    describe('长度上限', () => {
        it('正常长度 URL 通过', () => {
            const url = 'https://example.com/' + 'a'.repeat(100);
            expect(looksLikeHttpUrl(url)).toBe(true);
        });

        it('超过 2048 字符的 URL 拒绝（防 spam paste）', () => {
            const url = 'https://example.com/' + 'a'.repeat(2050);
            expect(looksLikeHttpUrl(url)).toBe(false);
        });

        it('恰好 2048 字符通过（边界）', () => {
            const prefix = 'https://example.com/';
            const url = prefix + 'a'.repeat(2048 - prefix.length);
            expect(url.length).toBe(2048);
            expect(looksLikeHttpUrl(url)).toBe(true);
        });
    });
});

// ---------- paste dispatcher ----------

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

function setReady(
    project: ReturnType<typeof useProjectStore>,
    net: ReturnType<typeof useNetworkStore>,
) {
    project.setWallMeta('w-test', null, null, 'self', 'self');
    net.sessionId = 'sess-1';
    const state: ProjectState = {
        version: 1,
        canvas: { widthMaps: 4, heightMaps: 4, background: '#FFFFFF' } as ProjectState['canvas'],
        elements: [],
        layers: [
            {
                id: 'L1', name: 'L1', visible: true, locked: false, opacity: 1,
                blendMode: 'normal', colorTag: null, elements: [],
            },
        ],
        activeLayerId: 'L1',
    } as unknown as ProjectState;
    project.setSnapshot(state);
}

describe('useCanvasUpload paste dispatcher', () => {
    let fetchMock: ReturnType<typeof vi.fn>;
    const originalFetch = globalThis.fetch;

    beforeEach(() => {
        setActivePinia(createPinia());
        wsSendMock.mockClear();
        wsSendMock.mockReturnValue('sent-id-1');
        fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ source: 'sha256-x', width: 100, height: 100, bytes: 1000 }),
        });
        globalThis.fetch = fetchMock as unknown as typeof fetch;
    });

    afterEach(() => {
        globalThis.fetch = originalFetch;
    });

    it('粘贴 plain text URL → 触发 uploadFromUrl（fetch /api/upload/url）', async () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);

        const e = makeClipboardEvent({ text: 'https://i.imgur.com/abc123' });
        handle.api.onPasteImage(e);

        await Promise.resolve();
        await Promise.resolve();

        expect(fetchMock).toHaveBeenCalledTimes(1);
        const fetchUrl = fetchMock.mock.calls[0][0] as string;
        expect(fetchUrl.startsWith('/api/upload/url')).toBe(true);
        expect(e.defaultPrevented).toBe(true);

        tearDown(handle);
    });

    it('粘贴 image File → 触发 uploadAndPlace（fetch /api/upload multipart）', async () => {
        const handle = mountUpload();
        setReady(handle.project, handle.net);

        const file = new File([new Uint8Array([0xFF, 0xD8, 0xFF])], 'paste.png', { type: 'image/png' });
        const e = makeClipboardEvent({
            files: [{ kind: 'file', type: 'image/png', file }],
        });
        handle.api.onPasteImage(e);

        expect(e.defaultPrevented).toBe(true);

        await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        expect(fetchMock.mock.calls[0][0]).toBe('/api/upload');

        tearDown(handle);
    });

    it('粘贴 HikariCanvas magic text → 触发 clipboard.paste 元素粘贴（ws.send element.add）', () => {
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

        expect(wsSendMock).toHaveBeenCalledTimes(1);
        const [op, body] = wsSendMock.mock.calls[0];
        expect(op).toBe('element.add');
        expect((body as { type: string }).type).toBe('rect');
        expect((body as { props: { x: number; y: number } }).props.x).toBe(20);
        expect((body as { props: { x: number; y: number } }).props.y).toBe(20);
        expect(e.defaultPrevented).toBe(true);
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

        const input = document.createElement('input');
        document.body.appendChild(input);
        input.focus();
        expect(document.activeElement).toBe(input);

        const e1 = makeClipboardEvent({ text: 'https://example.com/image.png' });
        handle.api.onPasteImage(e1);
        expect(fetchMock).not.toHaveBeenCalled();
        expect(e1.defaultPrevented).toBe(false);

        const payload = {
            elements: [{ id: 'x', type: 'rect', x: 0, y: 0, w: 10, h: 10 }],
            timestamp: '2026-01-01T00:00:00Z',
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
            timestamp: '2026-01-01T00:00:00Z',
            sourceWallId: null,
        };
        const e = makeClipboardEvent({ text: CLIPBOARD_MAGIC + JSON.stringify(payload) });
        const logsBefore = handle.net.logs.length;
        handle.api.onPasteImage(e);

        expect(e.defaultPrevented).toBe(true);
        expect(wsSendMock).not.toHaveBeenCalled();
        expect(handle.net.logs.length).toBe(logsBefore + 1);
        expect(handle.net.logs[handle.net.logs.length - 1].level).toBe('err');

        tearDown(handle);
    });
});
