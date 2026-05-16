import { ref, type Ref } from 'vue';
import { useEventListener } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { preloadImage } from '@/render/PreviewRenderer';

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
    };
}
