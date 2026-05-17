/**
 * M18 Live Paint — 共享类型。
 *
 * 与 polygon-clipping 库的类型区分约定：
 * - 本模块 {@link Polygon} = 单一闭合多边形顶点数组（一个 ring）；首点不复制为末点
 * - polygon-clipping {@code Polygon} = 多 ring（外环 + 多个 hole 内环）；末点复制首点
 *
 * 调用 polygon-clipping 前后需手动转换（参考 LivePaintCore）。
 */

/** 单 ring 顶点数组（[x, y] tuple）；首点不复制为末点。 */
export type Polygon = Array<[number, number]>;

/** Gap 多边形：一个外环 + N 个 hole 内环。坐标系为画布全局像素。 */
export interface GapPolygon {
    /** 外环（CCW 约定但本模块不强制方向，渲染走 even-odd） */
    outer: Polygon;
    /** 内孔（CW 约定但本模块不强制方向） */
    holes: Polygon[];
}

/** buildGraph 输出：当前画布所有空隙集合。 */
export interface LivePaintGraph {
    /** 所有 gap（可能 0 个：element 完全覆盖画布） */
    gaps: GapPolygon[];
    /** 画布像素宽（即 widthMaps × 128） */
    canvasWidth: number;
    /** 画布像素高 */
    canvasHeight: number;
}
