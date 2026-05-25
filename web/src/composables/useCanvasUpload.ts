import { ref, type Ref } from 'vue';
import { useEventListener } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { preloadImage } from '@/render/PreviewRenderer';

/**
 * 2026-05-25 项 3：粘贴板纯文本是否为可下载的图片 URL。
 * 限定 http(s) + 常见图片扩展（png/jpg/jpeg/gif/webp）；query string 允许。
 */
const IMAGE_URL_RE = /^https?:\/\/[^\s<>"]+?\.(png|jpe?g|gif|webp)(\?[^\s]*)?$/i;

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
        const text = e.clipboardData?.getData('text/plain');
        if (text && IMAGE_URL_RE.test(text.trim())) {
            uploadFromUrl(text.trim());
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
