/**
 * M18 Live Paint — 公共 API 入口。
 *
 * 算法核心（Phase 1）：
 *   1. {@link elementToPolygon}：Element → 单 ring 多边形
 *   2. {@link buildGraph}：所有 element polygon union → 占用区域 → 画布减占用 = gap graph
 *   3. {@link findGapAt}：在 gap graph 内点查包含 (px, py) 的 gap
 *   4. {@link gapToPathElement}：gap → 可 element.create 的 PathElement payload
 *
 * 后续 Phase（不在此模块）：
 *   - P2：Worker 化（避免阻塞主线程）
 *   - P3：CanvasView 集成 + 工具栏 + hover preview
 *   - P4：vector-fill 决策 A（点击 element 内部 → modify fill）+ 顶点 RDP 简化
 */

export { elementToPolygon, CIRCLE_SAMPLE_POINTS, PATH_CURVE_SAMPLES } from './ElementToPolygon';
export { buildGraph, findGapAt } from './LivePaintCore';
export { gapPolygonToPathD, gapToPathElement, VERTEX_WARN_THRESHOLD } from './PolygonToPath';
export type { Polygon, GapPolygon, LivePaintGraph } from './types';
