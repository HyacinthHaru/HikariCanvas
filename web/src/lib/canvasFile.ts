import { zipSync, strToU8 } from 'fflate';
import type { ProjectState } from '@/types/protocol';
import type { CanvasManifest } from '@/types/canvasFile';

export const CANVAS_SPEC = 1;

/** 扫所有图层的 image 元素，返回去重后的 source hash 列表。 */
export function collectImageHashes(state: ProjectState): string[] {
    const seen = new Set<string>();
    for (const layer of state.layers ?? []) {
        for (const el of layer.elements ?? []) {
            if ((el as { type?: string }).type === 'image') {
                const src = (el as { source?: string }).source;
                if (src) seen.add(src);
            }
        }
    }
    return [...seen];
}

export interface ManifestMeta {
    createdAt: number;
    createdBy?: string;
    server?: string;
    pluginVersion?: string;
    name?: string;
    templateOrigin?: string;
}

export function buildManifest(state: ProjectState, meta: ManifestMeta): CanvasManifest {
    return {
        spec: CANVAS_SPEC,
        kind: 'project',
        created_at: meta.createdAt,
        created_by: meta.createdBy,
        server: meta.server,
        plugin_version: meta.pluginVersion,
        name: meta.name,
        wall: { width: state.canvas.widthMaps, height: state.canvas.heightMaps },
        template_origin: meta.templateOrigin,
    };
}

export interface CanvasZipParts {
    manifest: CanvasManifest;
    projectJson: string;
    scriptsJson?: string;
    thumbnailPng?: Uint8Array;
    assets?: Record<string, Uint8Array>;   // key = hash（不含 .png）
}

/** 组装 .canvas zip 字节。条目路径与 docs/import-export.md §2.1 一致。 */
export function assembleCanvasZip(parts: CanvasZipParts): Uint8Array {
    const files: Record<string, Uint8Array> = {
        'manifest.json': strToU8(JSON.stringify(parts.manifest)),
        'project.json': strToU8(parts.projectJson),
    };
    if (parts.scriptsJson !== undefined) files['scripts.json'] = strToU8(parts.scriptsJson);
    if (parts.thumbnailPng) files['thumbnail.png'] = parts.thumbnailPng;
    for (const [hash, bytes] of Object.entries(parts.assets ?? {})) {
        files[`assets/${hash}.png`] = bytes;
    }
    return zipSync(files, { level: 6 });
}
