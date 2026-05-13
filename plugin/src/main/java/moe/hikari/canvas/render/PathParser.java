package moe.hikari.canvas.render;

import moe.hikari.canvas.state.PathDValidator;

import java.awt.geom.Path2D;

/**
 * 把 SVG path {@code d} 字符串（M9 子集：M / L / Q / C / Z，大小写绝对/相对）解析为
 * {@link Path2D.Double}，并附带首尾切线 / 端点元数据供 marker 渲染使用。
 *
 * <p>语义参考 W3C SVG 1.1 path data：</p>
 * <ul>
 *   <li>大写字母 = 绝对坐标；小写 = 相对当前点</li>
 *   <li>{@code M} 后跟重复参数 = 第一对 moveto + 后续隐式 lineto（{@code m} 同理隐式相对 l）</li>
 *   <li>{@code L} / {@code Q} / {@code C} 同一命令字母后跟重复参数组 = 多段同类命令</li>
 *   <li>{@code Z} / {@code z} 闭合当前 subpath 并把"当前点"重置为 subpath 起点</li>
 * </ul>
 *
 * <p>双端一致性约定：前端 {@code web/src/render/PathParser.ts}（M9-C）必须用同一文法 +
 * 同样的隐式 lineto / 大小写规则。M9-B 阶段先把后端固定，前端用同一参考公式镜像。</p>
 *
 * <p><b>输入校验：</b> 调用方应先通过 {@link PathDValidator#validate} 词法校验；本类对
 * 非法输入<b>尽力而为</b> —— 遇不识别字符就停止解析返回当前累积结果，不抛异常。</p>
 */
public final class PathParser {

    /**
     * 解析结果。
     *
     * @param path          已构建的 Path2D，可直接 fill / stroke
     * @param startX        path 起点 X（绝对坐标）
     * @param startY        path 起点 Y
     * @param endX          path 终点 X
     * @param endY          path 终点 Y
     * @param startTangentX 起点切线单位向量 X（沿 path 走向；marker 用反向）
     * @param startTangentY 起点切线单位向量 Y
     * @param endTangentX   终点切线单位向量 X（沿 path 走向 = marker 朝向）
     * @param endTangentY   终点切线单位向量 Y
     * @param hasSegments   path 是否含至少一段非零长度 segment（用于 marker 显示判定）
     */
    public record Result(
            Path2D.Double path,
            double startX, double startY,
            double endX, double endY,
            double startTangentX, double startTangentY,
            double endTangentX, double endTangentY,
            boolean hasSegments) {
    }

    private PathParser() {}

    public static Result parse(String d) {
        Path2D.Double path = new Path2D.Double();
        if (d == null || d.isEmpty()) {
            return empty(path);
        }

        // 解析状态
        double curX = 0, curY = 0;             // 当前点
        double subStartX = 0, subStartY = 0;   // 当前 subpath 起点
        double firstX = 0, firstY = 0;         // 整体 path 起点
        double firstTanX = 0, firstTanY = 0;   // 首段切线
        double lastTanX = 0, lastTanY = 0;     // 末段切线
        boolean hasFirstTangent = false;
        boolean hasSegments = false;
        boolean firstMoveDone = false;

        char curCmd = 0;
        int paramsPerCmd = 0;
        double[] paramBuf = new double[6];
        int paramCount = 0;
        // M 之后第一组用 moveto，后续隐式 lineto（同一 M 命令字母下）
        boolean firstGroupForCmd = true;

        int i = 0;
        int n = d.length();
        while (i < n) {
            char c = d.charAt(i);
            if (isSep(c)) {
                i++;
                continue;
            }
            if (isCmd(c)) {
                curCmd = c;
                paramsPerCmd = paramsForCommand(c);
                paramCount = 0;
                firstGroupForCmd = true;
                i++;
                if (paramsPerCmd == 0) {
                    // Z / z：闭合
                    if (firstMoveDone) {
                        path.closePath();
                        // 切线 = 从当前点指向 subpath 起点
                        double tx = subStartX - curX;
                        double ty = subStartY - curY;
                        double len = Math.hypot(tx, ty);
                        if (len > 1e-9) {
                            lastTanX = tx / len;
                            lastTanY = ty / len;
                            hasSegments = true;
                        }
                        curX = subStartX;
                        curY = subStartY;
                    }
                    curCmd = 0;
                }
                continue;
            }
            // 解析一个数字
            int[] endIdx = new int[1];
            double v = scanNumber(d, i, endIdx);
            if (endIdx[0] == i) {
                // 不识别字符，停止
                break;
            }
            i = endIdx[0];
            if (curCmd == 0) continue;
            paramBuf[paramCount++] = v;
            if (paramCount < paramsPerCmd) continue;

            // 收齐一组参数，执行命令
            boolean relative = Character.isLowerCase(curCmd);
            char op = Character.toUpperCase(curCmd);
            switch (op) {
                case 'M' -> {
                    double x = relative ? curX + paramBuf[0] : paramBuf[0];
                    double y = relative ? curY + paramBuf[1] : paramBuf[1];
                    if (firstGroupForCmd) {
                        path.moveTo(x, y);
                        subStartX = x;
                        subStartY = y;
                        if (!firstMoveDone) {
                            firstX = x;
                            firstY = y;
                            firstMoveDone = true;
                        }
                        firstGroupForCmd = false;
                    } else {
                        // 隐式 lineto
                        path.lineTo(x, y);
                        double tx = x - curX;
                        double ty = y - curY;
                        double len = Math.hypot(tx, ty);
                        if (len > 1e-9) {
                            if (!hasFirstTangent) {
                                firstTanX = tx / len;
                                firstTanY = ty / len;
                                hasFirstTangent = true;
                            }
                            lastTanX = tx / len;
                            lastTanY = ty / len;
                            hasSegments = true;
                        }
                    }
                    curX = x; curY = y;
                }
                case 'L' -> {
                    double x = relative ? curX + paramBuf[0] : paramBuf[0];
                    double y = relative ? curY + paramBuf[1] : paramBuf[1];
                    path.lineTo(x, y);
                    double tx = x - curX;
                    double ty = y - curY;
                    double len = Math.hypot(tx, ty);
                    if (len > 1e-9) {
                        if (!hasFirstTangent) {
                            firstTanX = tx / len;
                            firstTanY = ty / len;
                            hasFirstTangent = true;
                        }
                        lastTanX = tx / len;
                        lastTanY = ty / len;
                        hasSegments = true;
                    }
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                }
                case 'Q' -> {
                    double cx = relative ? curX + paramBuf[0] : paramBuf[0];
                    double cy = relative ? curY + paramBuf[1] : paramBuf[1];
                    double x = relative ? curX + paramBuf[2] : paramBuf[2];
                    double y = relative ? curY + paramBuf[3] : paramBuf[3];
                    path.quadTo(cx, cy, x, y);
                    // Q 切线 at t=1：方向 = (P2 - P1)；at t=0 方向 = (P1 - P0)
                    double tx0 = cx - curX, ty0 = cy - curY;
                    double tx1 = x - cx, ty1 = y - cy;
                    double len0 = Math.hypot(tx0, ty0);
                    double len1 = Math.hypot(tx1, ty1);
                    if (!hasFirstTangent) {
                        // 起点切线优先 P1-P0；若控制点与起点重合（退化），用 P2-P0
                        if (len0 > 1e-9) {
                            firstTanX = tx0 / len0; firstTanY = ty0 / len0;
                            hasFirstTangent = true;
                        } else if (len1 > 1e-9) {
                            firstTanX = tx1 / len1; firstTanY = ty1 / len1;
                            hasFirstTangent = true;
                        }
                    }
                    if (len1 > 1e-9) {
                        lastTanX = tx1 / len1; lastTanY = ty1 / len1;
                        hasSegments = true;
                    } else if (len0 > 1e-9) {
                        // 退化：控制点与终点重合，用 P1-P0
                        lastTanX = tx0 / len0; lastTanY = ty0 / len0;
                        hasSegments = true;
                    }
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                }
                case 'C' -> {
                    double c1x = relative ? curX + paramBuf[0] : paramBuf[0];
                    double c1y = relative ? curY + paramBuf[1] : paramBuf[1];
                    double c2x = relative ? curX + paramBuf[2] : paramBuf[2];
                    double c2y = relative ? curY + paramBuf[3] : paramBuf[3];
                    double x = relative ? curX + paramBuf[4] : paramBuf[4];
                    double y = relative ? curY + paramBuf[5] : paramBuf[5];
                    path.curveTo(c1x, c1y, c2x, c2y, x, y);
                    // C 切线 at t=0：(C1 - P0)；at t=1：(P3 - C2)
                    double tx0 = c1x - curX, ty0 = c1y - curY;
                    double tx1 = x - c2x, ty1 = y - c2y;
                    double len0 = Math.hypot(tx0, ty0);
                    double len1 = Math.hypot(tx1, ty1);
                    if (!hasFirstTangent && len0 > 1e-9) {
                        firstTanX = tx0 / len0; firstTanY = ty0 / len0;
                        hasFirstTangent = true;
                    }
                    if (len1 > 1e-9) {
                        lastTanX = tx1 / len1; lastTanY = ty1 / len1;
                        hasSegments = true;
                    }
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                }
                default -> { /* unreachable */ }
            }
            paramCount = 0;
        }

        if (!firstMoveDone) {
            return empty(path);
        }
        return new Result(path,
                firstX, firstY,
                curX, curY,
                firstTanX, firstTanY,
                lastTanX, lastTanY,
                hasSegments);
    }

    private static Result empty(Path2D.Double path) {
        return new Result(path, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    private static int paramsForCommand(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'm', 'l' -> 2;
            case 'q' -> 4;
            case 'c' -> 6;
            case 'z' -> 0;
            default -> -1;
        };
    }

    private static boolean isCmd(char c) {
        char lc = Character.toLowerCase(c);
        return lc == 'm' || lc == 'l' || lc == 'q' || lc == 'c' || lc == 'z';
    }

    private static boolean isSep(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',';
    }

    /**
     * 扫描一个数字 token，把结束 index 写入 {@code outEndIdx[0]}；若不像数字返回 0 且
     * outEndIdx[0] 不变（== i）。语义同 {@link PathDValidator#scanNumber} 但这里返值。
     */
    private static double scanNumber(String s, int i, int[] outEndIdx) {
        int n = s.length();
        int j = i;
        if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
        boolean hasDigit = false;
        while (j < n && Character.isDigit(s.charAt(j))) { j++; hasDigit = true; }
        if (j < n && s.charAt(j) == '.') {
            j++;
            while (j < n && Character.isDigit(s.charAt(j))) { j++; hasDigit = true; }
        }
        if (!hasDigit) {
            outEndIdx[0] = i;
            return 0;
        }
        if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
            int eIdx = j;
            j++;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
            boolean hasExpDigit = false;
            while (j < n && Character.isDigit(s.charAt(j))) { j++; hasExpDigit = true; }
            if (!hasExpDigit) {
                outEndIdx[0] = eIdx;
                return Double.parseDouble(s.substring(i, eIdx));
            }
        }
        outEndIdx[0] = j;
        try {
            return Double.parseDouble(s.substring(i, j));
        } catch (NumberFormatException e) {
            outEndIdx[0] = i;
            return 0;
        }
    }
}
