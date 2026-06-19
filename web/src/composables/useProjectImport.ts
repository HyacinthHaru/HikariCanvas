import { useNetworkStore } from '@/stores/network';
import type { ImportWarningDto } from '@/types/canvasFile';

export interface ImportOutcome {
    ok: boolean;
    warnings?: ImportWarningDto[];
    errorCode?: string;
    errorMessage?: string;
}

/**
 * 把选好的 .canvas 文件 POST 到后端 /api/project/import（multipart：sessionId + file）。
 *
 * <p>成功返回后端 warnings 清单；失败返回后端错误码。<b>导入成功后由后端经 WS
 * state.snapshot 推全量工程（wsClient.ts handleSnapshot → setSnapshot），本 composable
 * 不手动刷新工程。</b></p>
 */
export function useProjectImport() {
    async function importProject(file: File): Promise<ImportOutcome> {
        const net = useNetworkStore();
        const fd = new FormData();
        fd.append('sessionId', net.sessionId ?? '');
        fd.append('file', file);
        let resp: Response;
        try {
            resp = await fetch('/api/project/import', { method: 'POST', body: fd });
        } catch {
            return { ok: false, errorCode: 'NETWORK', errorMessage: 'network error' };
        }
        const body = await resp.json().catch(() => ({} as Record<string, unknown>));
        if (!resp.ok) {
            return {
                ok: false,
                errorCode: (body.error as string) ?? 'UNKNOWN',
                errorMessage: body.message as string,
            };
        }
        return { ok: true, warnings: (body.warnings as ImportWarningDto[]) ?? [] };
    }
    return { importProject };
}
