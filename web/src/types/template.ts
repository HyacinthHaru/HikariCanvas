/**
 * Template schema 前端镜像。结构对照 Java {@code ac.haru.hikaricanvas.template.*}
 * record 树 + {@code docs/template-spec.md §2-§5}。
 *
 * <p>字段宽松：{@code x/y/w/h/visible/padding/size/letterSpacing} 等可能是
 * number 或带 {@code ${param}} 占位符的 string —— Jackson 序列化是 polymorphic，
 * 这里用 {@link Dim} / {@link Vis} 别名描述。后端实例化阶段会归一化，
 * 前端在 ParamForm / preview 时遇到 placeholder 字符串视作"由参数决定"，不直接解析。</p>
 */

export type Dim = number | string;
export type Vis = boolean | string;

// ---------- §3 canvas ----------

export interface TemplateCanvas {
    size?: 'auto' | 'fixed';
    maps?: [number, number];
    min_maps?: [number, number];
    max_maps?: [number, number];
    background?: string;
    padding?: number | [number, number, number, number];
}

// ---------- §5 params ----------

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

// ---------- §4 elements ----------

export interface TemplateElementBase {
    type: 'text' | 'rect' | 'line';
    id?: string;
    x?: Dim;
    y?: Dim;
    w?: Dim;
    h?: Dim;
    rotation?: number;
    visible?: Vis;
    z_order?: number;
    visible_when?: string;
}

export interface TemplateTextElement extends TemplateElementBase {
    type: 'text';
    content?: string;
    font?: string;
    size?: number | string;
    color?: string;
    align?: 'left' | 'center' | 'right';
    line_height?: number;
    letter_spacing?: number;
    vertical?: boolean;
    effects?: TemplateEffects;
}

export interface TemplateRectElement extends TemplateElementBase {
    type: 'rect';
    fill?: string;
    stroke?: TemplateStroke;
}

export interface TemplateLineElement extends TemplateElementBase {
    type: 'line';
    from?: [number, number];
    to?: [number, number];
    width?: number;
    color?: string;
}

export type TemplateElement =
    | TemplateTextElement
    | TemplateRectElement
    | TemplateLineElement;

export interface TemplateStroke {
    width?: Dim;
    color?: string;
}

export interface TemplateShadow {
    dx?: Dim;
    dy?: Dim;
    color?: string;
}

export interface TemplateGlow {
    radius?: Dim;
    color?: string;
}

export interface TemplateEffects {
    stroke?: TemplateStroke;
    shadow?: TemplateShadow;
    glow?: TemplateGlow;
}

// ---------- §4.1 layout ----------

export interface TemplateLayout {
    type: 'stack' | 'free' | 'grid';
    direction?: 'vertical' | 'horizontal';
    gap?: number;
    elements: TemplateElement[];
}

// ---------- §2 top-level ----------

export interface TemplateSpec {
    spec: number;
    id: string;
    name: string;
    description?: string;
    version?: number;
    author?: string;
    tags?: string[];
    preview?: string;
    // .canvas pack 形态可能整体缺省 canvas / layout（size 由预览端点体现，
    // layout 是旧 YAML 模板独有）；旧 YAML 模板仍会带上。故两者皆可选。
    canvas?: TemplateCanvas;
    params?: Record<string, TemplateParam>;
    layout?: TemplateLayout;
}
