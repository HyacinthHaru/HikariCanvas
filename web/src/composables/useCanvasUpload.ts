import { ref, type Ref } from 'vue';
import { useEventListener } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { preloadImage } from '@/render/PreviewRenderer';

/**
 * 2026-05-25 项 3：粘贴板纯文本是否为可下载的 URL。
 *
 * <p>历史：v1 强制要求 URL 以 .png/.jpg/.gif/.webp 扩展名结尾，结果绝大多数现代图片
 * URL（CDN / 签名 URL / 带 token query / 形如 `https://cdn.x/abc123?sig=...&exp=...`）
 * 全部不命中，paste 静默 no-op，用户报"URL 粘贴上传无响应"。</p>
 *
 * <p>修正：放宽为"任何 single-token 的 http(s) URL"。安全保证来自后端：</p>
 * <ol>
 *   <li>{@code UrlFetchSafety} 做 SSRF 防御（拒内网 / 回环 / link-local）</li>
 *   <li>{@code UploadHandler.handleUrlUpload} 校验 Content-Type ∈ {png, jpeg, webp}</li>
 *   <li>magic-bytes 校验 + ImageIO 隔离解码 + 200ms 超时</li>
 *   <li>10MB fetch 上限 + 30s 总超时</li>
 * </ol>
 *
 * <p>因此前端只需识别"看起来像 URL"——精确的图片类型判断交给后端。</p>
 *
 * <p>正则要点：</p>
 * <ul>
 *   <li>必须 http(s):// 开头</li>
 *   <li>host 至少一个字符（不允许 `http://`/`https://` 这种空 URL）</li>
 *   <li>整体不能含空白 / 引号 / 尖括号（粘贴单个 token 才算 URL，多行文本不算）</li>
 *   <li>长度上限 2048（后端硬上限同款）防止 spam paste 把巨型字符串当 URL 发</li>
 * </ul>
 */
const HTTP_URL_RE = /^https?:\/\/[^\s<>"'`]+$/i;
const URL_MAX_LEN = 2048;

/** 粘贴文本是否看起来是个可下载的 URL（loose detection，详见 {@link HTTP_URL_RE}）。导出供单测使用。 */
export function looksLikeHttpUrl(text: string): boolean {
    if (!text) return false;
    if (text.length > URL_MAX_LEN) return false;
    return HTTP_URL_RE.test(text);
}

/**
 * M13-D：图片上传三入口（drop / paste / file input）。
 * - 调用方需提供 brushHostRef（用于 dropToCanvas 坐标换算）+ fileInputRef（隐藏 file input 元素的模板 ref）。
 * - useEventListener(window, 'paste', ...) 由本 composable 自动挂载。
 */
export function useCanvasUpload(opts: {
    brushHostRef: Ref<HTMLElement | null>;
    fileInputRef: Ref<HTMLInputElement | null>;
}) {
    const project = useProjectStore();
    const ui = useUiStore();
    const net = useNetworkStore();
    const ws = getWsClient();
    const { t } = useI18n();

    const uploadError = ref<string | null>(null);
    const uploading = ref(false);

    function flashError(msg: string) {
        uploadError.value = msg;
        window.setTimeout(() => {
            if (uploadError.value === msg) uploadError.value = null;
        }, 6000);
    }

    async function uploadAndPlace(file: File, dropClientX?: number, dropClientY?: number) {
        if (project.isLocked) { flashError(t.value.image.lockedDenied); return; }
        if (!net.sessionId) { flashError(t.value.image.noSession); return; }
        if (!file.type.startsWith('image/')) { flashError(t.value.image.notImage); return; }

        const dataUrl = await readFileAsDataUrl(file);

        uploading.value = true;
        try {
            const fd = new FormData();
            fd.append('sessionId', net.sessionId);
            fd.append('file', file);
            const resp = await fetch('/api/upload', { method: 'POST', body: fd });
            if (!resp.ok) {
                const body = await resp.json().catch(() => ({} as Record<string, string>));
                flashError(t.value.image.uploadFailed(resp.status, body.message || body.error || ''));
                return;
            }
            const result = await resp.json() as { source: string; width: number; height: number; bytes: number };
            if (dataUrl) preloadImage(result.source, dataUrl);

            const cv = project.state?.canvas;
            if (!cv) return;
            const cvW = cv.widthMaps * 128;
            const cvH = cv.heightMaps * 128;
            const limit = Math.floor(Math.min(cvW, cvH) * 0.8);
            let w = result.width;
            let h = result.height;
            if (Math.max(w, h) > limit) {
                const s = limit / Math.max(w, h);
                w = Math.max(8, Math.round(w * s));
                h = Math.max(8, Math.round(h * s));
            }
            const center = dropToCanvas(dropClientX, dropClientY) ?? { x: cvW / 2, y: cvH / 2 };
            const x = Math.max(0, Math.min(cvW - w, Math.round(center.x - w / 2)));
            const y = Math.max(0, Math.min(cvH - h, Math.round(center.y - h / 2)));
            ws.send('element.add', {
                type: 'image',
                props: { x, y, w, h, source: result.source },
            });
        } catch (e) {
            flashError(t.value.image.uploadFailed(0, (e as Error).message));
        } finally {
            uploading.value = false;
        }
    }

    function readFileAsDataUrl(file: File): Promise<string | null> {
        return new Promise((resolve) => {
            const reader = new FileReader();
            reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : null);
            reader.onerror = () => resolve(null);
            reader.readAsDataURL(file);
        });
    }

    function dropToCanvas(clientX?: number, clientY?: number): { x: number; y: number } | null {
        if (clientX == null || clientY == null) return null;
        const host = opts.brushHostRef.value;
        if (!host) return null;
        const rect = host.getBoundingClientRect();
        return {
            x: (clientX - rect.left) / ui.zoom,
            y: (clientY - rect.top) / ui.zoom,
        };
    }

    function onCanvasDragOver(e: DragEvent) {
        if (project.isLocked) return;
        if (e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')) {
            e.preventDefault();
        }
    }

    function onCanvasDrop(e: DragEvent) {
        if (project.isLocked) return;
        const files = e.dataTransfer?.files;
        if (!files || files.length === 0) return;
        e.preventDefault();
        const file = files[0];
        uploadAndPlace(file, e.clientX, e.clientY);
    }

    function onPasteImage(e: ClipboardEvent) {
        if (project.isLocked) return;
        if (document.activeElement instanceof HTMLTextAreaElement) return;
        if (document.activeElement instanceof HTMLInputElement) return;
        if (document.activeElement instanceof HTMLElement
            && (document.activeElement.isContentEditable
                || document.activeElement.getAttribute('contenteditable') === 'true')) {
            return;
        }
        const items = e.clipboardData?.items;
        if (!items) return;
        for (const item of items) {
            if (item.kind === 'file' && item.type.startsWith('image/')) {
                const file = item.getAsFile();
                if (file) {
                    uploadAndPlace(file);
                    e.preventDefault();
                    return;
                }
            }
        }
        // 2026-05-25 项 3：URL 粘贴。文本剪贴板内容是图片 URL 时自动下载并走上传管线。
        // 仅在没有 file 命中时尝试 URL 路径。
        // 2026-05-25 bugfix：放宽匹配条件——任何 http(s):// URL 都尝试下载，不再要求
        // 必须以 .png/.jpg 扩展名结尾（CDN / 签名 URL 几乎从不带扩展）。后端 Content-Type
        // + magic-bytes + ImageIO 校验已足够 reject 非图片资源。
        const text = e.clipboardData?.getData('text/plain');
        const trimmed = text ? text.trim() : '';
        if (looksLikeHttpUrl(trimmed)) {
            uploadFromUrl(trimmed);
            e.preventDefault();
        }
    }

    /** 2026-05-25 项 3：从 URL 上传。后端 SSRF 校验 + 6 层校验栈复用。 */
    async function uploadFromUrl(url: string, dropClientX?: number, dropClientY?: number) {
        if (project.isLocked) { flashError(t.value.image.lockedDenied); return; }
        if (!net.sessionId) { flashError(t.value.image.noSession); return; }
        uploading.value = true;
        try {
            const resp = await fetch(`/api/upload/url?sessionId=${encodeURIComponent(net.sessionId)}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url }),
            });
            if (!resp.ok) {
                const body = await resp.json().catch(() => ({} as Record<string, string>));
                flashError(t.value.image.uploadFailed(resp.status, body.message || body.error || t.value.image.uploadUrlFailed));
                return;
            }
            const result = await resp.json() as { source: string; width: number; height: number; bytes: number };
            const cv = project.state?.canvas;
            if (!cv) return;
            const cvW = cv.widthMaps * 128;
            const cvH = cv.heightMaps * 128;
            const limit = Math.floor(Math.min(cvW, cvH) * 0.8);
            let w = result.width;
            let h = result.height;
            if (Math.max(w, h) > limit) {
                const s = limit / Math.max(w, h);
                w = Math.max(8, Math.round(w * s));
                h = Math.max(8, Math.round(h * s));
            }
            const center = dropToCanvas(dropClientX, dropClientY) ?? { x: cvW / 2, y: cvH / 2 };
            const x = Math.max(0, Math.min(cvW - w, Math.round(center.x - w / 2)));
            const y = Math.max(0, Math.min(cvH - h, Math.round(center.y - h / 2)));
            ws.send('element.add', {
                type: 'image',
                props: { x, y, w, h, source: result.source },
            });
        } catch (e) {
            flashError(t.value.image.uploadFailed(0, (e as Error).message));
        } finally {
            uploading.value = false;
        }
    }

    function onFileInputChange(e: Event) {
        const input = e.target as HTMLInputElement;
        const file = input.files?.[0];
        if (file) uploadAndPlace(file);
        input.value = '';
    }

    function triggerFileInput() {
        opts.fileInputRef.value?.click();
    }

    useEventListener(window, 'paste', onPasteImage);

    return {
        uploadError,
        uploading,
        onCanvasDragOver,
        onCanvasDrop,
        onFileInputChange,
        triggerFileInput,
        uploadFromUrl,
    };
}
