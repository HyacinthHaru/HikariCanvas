/** .canvas manifest（与后端 CanvasManifest 镜像，见 docs/import-export.md §2.2）。 */
export interface CanvasManifest {
    spec: number;
    kind: 'project';
    created_at: number;
    created_by?: string;
    server?: string;
    plugin_version?: string;
    name?: string;
    wall: { width: number; height: number };
    template_origin?: string;
}

/** 后端导入响应（docs/import-export.md §4）。 */
export interface ImportWarningDto { kind: string; detail: string; }
export interface ImportResultDto { ok: boolean; warnings: ImportWarningDto[]; }
