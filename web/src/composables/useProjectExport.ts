import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useScriptStore } from '@/stores/scripts';
import { collectImageHashes, buildManifest, assembleCanvasZip } from '@/lib/canvasFile';
import { renderExportThumbnail } from '@/render/exportThumbnail';
import { downloadBlob } from '@/lib/downloadBlob';

export interface ExportOutcome { ok: boolean; reason?: string; }

/**
 * 拉单张图片字节（复用现有图片下载端点 GET /api/upload/{source}）。
 *
 * <p>该端点要求 {@code ?sessionId=<id>} 鉴权（见后端 UploadHandler.handleDownload，
 * M16 P1.1 IDOR 修复）；缺 sessionId 会 401。这里把当前会话的 sessionId 拼进 URL。
 * 任何失败（无会话 / 401 / 404 / 网络错误）都优雅返回 null —— 导出不因缺图中断。</p>
 */
async function fetchImageBytes(hash: string, sessionId: string | null): Promise<Uint8Array | null> {
    try {
        const base = `/api/upload/${encodeURIComponent(hash)}`;
        const url = sessionId ? `${base}?sessionId=${encodeURIComponent(sessionId)}` : base;
        const resp = await fetch(url);
        if (!resp.ok) return null;
        return new Uint8Array(await resp.arrayBuffer());
    } catch {
        return null;
    }
}

export function useProjectExport() {
    async function exportProject(): Promise<ExportOutcome> {
        const project = useProjectStore();
        const net = useNetworkStore();
        const state = project.state;
        if (!state) return { ok: false, reason: 'no-project' };

        const sessionId = net.sessionId ?? null;
        const assets: Record<string, Uint8Array> = {};
        for (const hash of collectImageHashes(state)) {
            const bytes = await fetchImageBytes(hash, sessionId);
            if (bytes) assets[hash] = bytes;
        }

        const thumbnailPng = (await renderExportThumbnail(state)) ?? undefined;
        // plugin_version：项目无 __APP_VERSION__ 注入（vite.config.ts 无 define），用 ready
        // 时后端上报的 serverVersion；缺省则省略（manifest.plugin_version 可选）。
        const manifest = buildManifest(state, {
            createdAt: Date.now(),
            pluginVersion: net.serverVersion ?? undefined,
        });
        // 0.8-A4：带上当前墙的脚本（listSorted 是服务端顺序的只读快照）。无脚本则省略
        // scripts.json（assembleCanvasZip 的 scriptsJson 可选，undefined 时不打进 zip）。
        const scripts = useScriptStore().listSorted;
        const zip = assembleCanvasZip({
            manifest,
            projectJson: JSON.stringify(state),
            scriptsJson: scripts.length ? JSON.stringify(scripts) : undefined,
            thumbnailPng,
            assets,
        });
        const name = (manifest.name?.trim() || 'project').replace(/[^\w一-龥-]+/g, '_');
        downloadBlob(zip, `${name}.canvas`, 'application/octet-stream');
        return { ok: true };
    }
    return { exportProject };
}
