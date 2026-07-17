/**
 * Live Paint — 公共 API 入口。
 *
 * 算法核心：
 *   1. {@link elementToPolygon}：Element → 单 ring 多边形
 *   2. {@link buildGraph}：所有 element polygon union → 占用区域 → 画布减占用 = gap graph
 *   3. {@link findGapAt}：在 gap graph 内点查包含 (px, py) 的 gap
 *   4. {@link gapToPathElement}：gap → 可 element.create 的 PathElement payload
 */

export {
    elementToPolygon,
    elementToPolygonAsync,
    elementToMultiPolygonAsync,
    CIRCLE_SAMPLE_POINTS,
    PATH_CURVE_SAMPLES,
} from './ElementToPolygon';
export { textElementToPolygon, textElementToMultiPolygon, GLYPH_CURVE_SAMPLES } from './TextGlyphExtractor';
export { brushStrokeToPolygon, RDP_THRESHOLD, RDP_TOLERANCE, CAP_SAMPLES, JOIN_SAMPLES } from './BrushStrokeOffset';
export { buildGraph, findGapAt, pointInPolygon } from './LivePaintCore';
export {
    gapPolygonToPathD,
    gapToPathElement,
    VERTEX_WARN_THRESHOLD,
    VERTEX_HARD_LIMIT,
} from './PolygonToPath';
export { rdpSimplify } from './RdpSimplifier';
export type { Polygon, GapPolygon, LivePaintGraph } from './types';

// Worker 化 composable
export { useLivePaint } from './useLivePaint';
export type { UseLivePaintOpts, UseLivePaintReturn } from './useLivePaint';
