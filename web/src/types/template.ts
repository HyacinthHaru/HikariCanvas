/**
 * 模板元数据前端镜像，对应后端 `ac.haru.hikaricanvas.template.TemplateSpec` 及相关 record
 *（详见 `docs/template-pack.md`）。
 */

export interface TemplateCanvas {
    size?: 'auto' | 'fixed';
    maps?: [number, number];
    min_maps?: [number, number];
    max_maps?: [number, number];
    background?: string;
    padding?: number | [number, number, number, number];
}

export type TemplateParamType =
    | 'string' | 'text' | 'int' | 'float' | 'bool' | 'color' | 'enum' | 'font';

export interface TemplateParamOption {
    label: string;
    value: unknown;
}

export interface TemplateParam {
    type: TemplateParamType;
    label?: string;
    description?: string;
    required?: boolean;
    default?: unknown;
    visible_when?: string;
    group?: string;

    // string / text
    max_length?: number;
    min_length?: number;
    placeholder?: string;
    pattern?: string;

    // int / float
    min?: number;
    max?: number;
    step?: number;

    // enum
    options?: TemplateParamOption[];

    // color
    presets?: TemplateParamOption[];
}

export interface TemplateSpec {
    spec: number;
    id: string;
    name: string;
    description?: string;
    version?: number;
    author?: string;
    tags?: string[];
    preview?: string;
    canvas?: TemplateCanvas;
    params?: Record<string, TemplateParam>;
}
