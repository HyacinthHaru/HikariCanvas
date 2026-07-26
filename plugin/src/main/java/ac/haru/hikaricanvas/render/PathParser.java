package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.PathDValidator;

import java.awt.geom.Path2D;

/**
 * 把 SVG path {@code d} 字符串解析为 {@link Path2D.Double}，并附带首尾切线 / 端点元数据
 * 供 marker 渲染使用。
 *
 * <p>支持命令（大写绝对、小写相对）：</p>
 * <ul>
 *   <li>{@code M / m}：moveto（多组隐式 lineto）</li>
 *   <li>{@code L / l}：lineto</li>
 *   <li>{@code H / h}：水平 lineto</li>
 *   <li>{@code V / v}：垂直 lineto</li>
 *   <li>{@code Q / q}：quadratic bezier</li>
 *   <li>{@code T / t}：smooth quadratic shortcut</li>
 *   <li>{@code C / c}：cubic bezier</li>
 *   <li>{@code S / s}：smooth cubic shortcut</li>
 *   <li>{@code A / a}：椭圆弧，用 ≤π/2 段 cubic bezier 近似</li>
 *   <li>{@code Z / z}：闭合 subpath</li>
 * </ul>
 *
 * <p>语义参考 W3C SVG 1.1 path data：</p>
 * <ul>
 *   <li>大写字母 = 绝对坐标；小写 = 相对当前点</li>
 *   <li>{@code M} 后跟重复参数 = 第一对 moveto + 后续隐式 lineto（{@code m} 同理隐式相对 l）</li>
 *   <li>{@code L} / {@code Q} / {@code C} 同一命令字母后跟重复参数组 = 多段同类命令</li>
 *   <li>{@code Z} / {@code z} 闭合当前 subpath 并把"当前点"重置为 subpath 起点</li>
 * </ul>
 *
 * <p>双端一致性约定：前端 {@code web/src/render/PathParser.ts} 必须用同一文法 +
 * 同样的隐式 lineto / 大小写规则。前端用浏览器原生 {@code new Path2D(d)} 渲染
 * IconLibrary path（含 A/S/T/H/V），后端这里手实装与之对齐。</p>
 *
 * <p><b>输入校验：</b> PathElement 输入应先通过 {@link PathDValidator#validate} 词法校验
 * IconLibrary path 直接从可信 IconRegistry 加载，不走 validator。本类对
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

        // S 反射用：前一 C / S 命令的 c2（绝对坐标）；未设 → 与 cur 重合
        double prevC2X = 0, prevC2Y = 0;
        boolean prevWasCubic = false;
        // T 反射用：前一 Q / T 命令的 c（绝对坐标）；未设 → 与 cur 重合
        double prevQcX = 0, prevQcY = 0;
        boolean prevWasQuad = false;

        char curCmd = 0;
        int paramsPerCmd = 0;
        double[] paramBuf = new double[7];  // A 命令 7 个参数最多
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
                    prevWasCubic = false;
                    prevWasQuad = false;
                    curCmd = 0;
                }
                continue;
            }
            // 解析一个数字。flag 参数（A 的第 4、5 个：large-arc-flag / sweep-flag）允许单字符 0/1
            int[] endIdx = new int[1];
            boolean isFlag = Character.toUpperCase(curCmd) == 'A'
                    && (paramCount == 3 || paramCount == 4);
            double v;
            if (isFlag) {
                v = scanFlag(d, i, endIdx);
            } else {
                v = scanNumber(d, i, endIdx);
            }
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
            // 首个绘制命令前没有 M：Path2D 没有当前点，lineTo / quadTo / curveTo 会抛
            // IllegalPathStateException，把整墙 rasterize 打断（IconRegistry 的用户 SVG
            // 不过 PathDValidator，这条路是敞开的）。本类契约是「尽力而为、绝不抛异常」，
            // 按 SVG 规范「路径数据出错就渲染到出错点为止」停止解析，返回已累积结果 ——
            // 与前端 new Path2D(d) 对同款畸形串给空 path 的行为一致。
            if (op != 'M' && !firstMoveDone) break;
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
                    prevWasCubic = false;
                    prevWasQuad = false;
                }
                case 'L' -> {
                    double x = relative ? curX + paramBuf[0] : paramBuf[0];
                    double y = relative ? curY + paramBuf[1] : paramBuf[1];
                    path.lineTo(x, y);
                    double tx = x - curX, ty = y - curY;
                    double len = Math.hypot(tx, ty);
                    if (len > 1e-9) {
                        if (!hasFirstTangent) {
                            firstTanX = tx / len; firstTanY = ty / len;
                            hasFirstTangent = true;
                        }
                        lastTanX = tx / len; lastTanY = ty / len;
                        hasSegments = true;
                    }
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                    prevWasCubic = false;
                    prevWasQuad = false;
                }
                case 'H' -> {
                    double x = relative ? curX + paramBuf[0] : paramBuf[0];
                    double y = curY;
                    path.lineTo(x, y);
                    double tx = x - curX, ty = y - curY;
                    double len = Math.hypot(tx, ty);
                    if (len > 1e-9) {
                        if (!hasFirstTangent) {
                            firstTanX = tx / len; firstTanY = ty / len;
                            hasFirstTangent = true;
                        }
                        lastTanX = tx / len; lastTanY = ty / len;
                        hasSegments = true;
                    }
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                    prevWasCubic = false;
                    prevWasQuad = false;
                }
                case 'V' -> {
                    double x = curX;
                    double y = relative ? curY + paramBuf[0] : paramBuf[0];
                    path.lineTo(x, y);
                    double tx = x - curX, ty = y - curY;
                    double len = Math.hypot(tx, ty);
                    if (len > 1e-9) {
                        if (!hasFirstTangent) {
                            firstTanX = tx / len; firstTanY = ty / len;
                            hasFirstTangent = true;
                        }
                        lastTanX = tx / len; lastTanY = ty / len;
                        hasSegments = true;
                    }
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                    prevWasCubic = false;
                    prevWasQuad = false;
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
                        lastTanX = tx0 / len0; lastTanY = ty0 / len0;
                        hasSegments = true;
                    }
                    prevQcX = cx; prevQcY = cy;
                    prevWasQuad = true;
                    prevWasCubic = false;
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                }
                case 'T' -> {
                    // smooth quadratic：c = 反射前一 Q/T 的 c；否则 c = cur
                    double cx, cy;
                    if (prevWasQuad) {
                        cx = 2 * curX - prevQcX;
                        cy = 2 * curY - prevQcY;
                    } else {
                        cx = curX;
                        cy = curY;
                    }
                    double x = relative ? curX + paramBuf[0] : paramBuf[0];
                    double y = relative ? curY + paramBuf[1] : paramBuf[1];
                    path.quadTo(cx, cy, x, y);
                    double tx0 = cx - curX, ty0 = cy - curY;
                    double tx1 = x - cx, ty1 = y - cy;
                    double len0 = Math.hypot(tx0, ty0);
                    double len1 = Math.hypot(tx1, ty1);
                    if (!hasFirstTangent) {
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
                        lastTanX = tx0 / len0; lastTanY = ty0 / len0;
                        hasSegments = true;
                    }
                    prevQcX = cx; prevQcY = cy;
                    prevWasQuad = true;
                    prevWasCubic = false;
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
                    prevC2X = c2x; prevC2Y = c2y;
                    prevWasCubic = true;
                    prevWasQuad = false;
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                }
                case 'S' -> {
                    // smooth cubic：c1 = 反射前一 C/S 的 c2；否则 c1 = cur
                    double c1x, c1y;
                    if (prevWasCubic) {
                        c1x = 2 * curX - prevC2X;
                        c1y = 2 * curY - prevC2Y;
                    } else {
                        c1x = curX;
                        c1y = curY;
                    }
                    double c2x = relative ? curX + paramBuf[0] : paramBuf[0];
                    double c2y = relative ? curY + paramBuf[1] : paramBuf[1];
                    double x = relative ? curX + paramBuf[2] : paramBuf[2];
                    double y = relative ? curY + paramBuf[3] : paramBuf[3];
                    path.curveTo(c1x, c1y, c2x, c2y, x, y);
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
                    prevC2X = c2x; prevC2Y = c2y;
                    prevWasCubic = true;
                    prevWasQuad = false;
                    curX = x; curY = y;
                    firstGroupForCmd = false;
                }
                case 'A' -> {
                    // 椭圆弧 → cubic bezier 近似
                    double rx = Math.abs(paramBuf[0]);
                    double ry = Math.abs(paramBuf[1]);
                    double xAxisRotDeg = paramBuf[2];
                    boolean largeArc = paramBuf[3] != 0;
                    boolean sweep = paramBuf[4] != 0;
                    double x = relative ? curX + paramBuf[5] : paramBuf[5];
                    double y = relative ? curY + paramBuf[6] : paramBuf[6];

                    if (Math.hypot(x - curX, y - curY) < 1e-9) {
                        // SVG spec：起点 == 终点，arc 整段无效，跳过
                        firstGroupForCmd = false;
                        prevWasCubic = false;
                        prevWasQuad = false;
                    } else if (rx < 1e-9 || ry < 1e-9) {
                        // 退化为直线
                        path.lineTo(x, y);
                        double tx = x - curX, ty = y - curY;
                        double len = Math.hypot(tx, ty);
                        if (len > 1e-9) {
                            if (!hasFirstTangent) {
                                firstTanX = tx / len; firstTanY = ty / len;
                                hasFirstTangent = true;
                            }
                            lastTanX = tx / len; lastTanY = ty / len;
                            hasSegments = true;
                        }
                        curX = x; curY = y;
                        firstGroupForCmd = false;
                        prevWasCubic = false;
                        prevWasQuad = false;
                    } else {
                        // 起点切线（用 arc 起点切线方向）
                        double[] startTan = new double[2];
                        double[] endTan = new double[2];
                        arcToBezier(path, curX, curY, rx, ry, xAxisRotDeg, largeArc, sweep, x, y,
                                startTan, endTan);
                        double slen = Math.hypot(startTan[0], startTan[1]);
                        double elen = Math.hypot(endTan[0], endTan[1]);
                        if (!hasFirstTangent && slen > 1e-9) {
                            firstTanX = startTan[0] / slen;
                            firstTanY = startTan[1] / slen;
                            hasFirstTangent = true;
                        }
                        if (elen > 1e-9) {
                            lastTanX = endTan[0] / elen;
                            lastTanY = endTan[1] / elen;
                            hasSegments = true;
                        }
                        curX = x; curY = y;
                        firstGroupForCmd = false;
                        prevWasCubic = false;
                        prevWasQuad = false;
                    }
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

    // ---------- A 椭圆弧 → cubic bezier 近似 ----------

    /**
     * 把 SVG endpoint-form 椭圆弧分解为 1-4 段 cubic bezier，写入 {@code path}。
     * 算法参考 W3C SVG 1.1 Implementation Notes §F.6 + B.2.4。
     *
     * @param startTanOut 输出弧起点切线方向（未归一化）
     * @param endTanOut   输出弧终点切线方向（未归一化）
     */
    private static void arcToBezier(Path2D.Double path,
                                    double x1, double y1,
                                    double rx, double ry,
                                    double xAxisRotDeg,
                                    boolean largeArc, boolean sweep,
                                    double x2, double y2,
                                    double[] startTanOut, double[] endTanOut) {
        double phi = Math.toRadians(xAxisRotDeg);
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);

        // Step 1: 转换到 prime 坐标系（中点平移 + 反向 rotation）
        double dx = (x1 - x2) / 2.0;
        double dy = (y1 - y2) / 2.0;
        double x1p =  cosPhi * dx + sinPhi * dy;
        double y1p = -sinPhi * dx + cosPhi * dy;

        // Step 2: 半径校正（如果半径太小，按比例放大 —— SVG spec §F.6.6）
        double rxSq = rx * rx;
        double rySq = ry * ry;
        double x1pSq = x1p * x1p;
        double y1pSq = y1p * y1p;
        double lambda = x1pSq / rxSq + y1pSq / rySq;
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
            rxSq = rx * rx;
            rySq = ry * ry;
        }

        // Step 3: 求 prime 坐标系下的中心
        double sign = (largeArc == sweep) ? -1 : 1;
        double sq = (rxSq * rySq - rxSq * y1pSq - rySq * x1pSq)
                / (rxSq * y1pSq + rySq * x1pSq);
        if (sq < 0) sq = 0;  // 数值误差兜底
        double coef = sign * Math.sqrt(sq);
        double cxp = coef * (rx * y1p) / ry;
        double cyp = coef * -(ry * x1p) / rx;

        // Step 4: 回到原坐标系的中心
        double cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0;
        double cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0;

        // Step 5: 起 / 末 angle + delta
        double ux = (x1p - cxp) / rx;
        double uy = (y1p - cyp) / ry;
        double vx = (-x1p - cxp) / rx;
        double vy = (-y1p - cyp) / ry;
        double theta1 = angleBetween(1, 0, ux, uy);
        double deltaTheta = angleBetween(ux, uy, vx, vy);
        if (!sweep && deltaTheta > 0) {
            deltaTheta -= 2 * Math.PI;
        } else if (sweep && deltaTheta < 0) {
            deltaTheta += 2 * Math.PI;
        }

        // Step 6: 切成 ≤ π/2 段，逐段近似为 cubic bezier
        int segments = (int) Math.ceil(Math.abs(deltaTheta) / (Math.PI / 2.0));
        if (segments < 1) segments = 1;
        double segAngle = deltaTheta / segments;
        // 单位圆 → ellipse 的 cubic bezier 控制点距离系数
        // 标准公式：α = sin(θ) * (sqrt(4 + 3 tan²(θ/2)) - 1) / 3
        double t = Math.tan(segAngle / 2.0);
        double alpha = Math.sin(segAngle) * (Math.sqrt(4 + 3 * t * t) - 1) / 3.0;

        double segStartAngle = theta1;

        // 标志 path 走向（marker 用）
        double signDir = deltaTheta >= 0 ? 1 : -1;
        // 起点切线（沿 path 走向，已乘 signDir）
        {
            double dx0 = -rx * Math.sin(theta1) * signDir;
            double dy0 =  ry * Math.cos(theta1) * signDir;
            startTanOut[0] = rotX(dx0, dy0, cosPhi, sinPhi);
            startTanOut[1] = rotY(dx0, dy0, cosPhi, sinPhi);
        }

        for (int s = 0; s < segments; s++) {
            double segEndAngle = segStartAngle + segAngle;
            // 段终点（ellipse 上 + rotation + center 平移）
            double e1x = rx * Math.cos(segEndAngle);
            double e1y = ry * Math.sin(segEndAngle);
            double segEndX = rotX(e1x, e1y, cosPhi, sinPhi) + cx;
            double segEndY = rotY(e1x, e1y, cosPhi, sinPhi) + cy;

            // 参数化导数 P'(θ) = (-rx sin θ, ry cos θ)
            double dStartXp = -rx * Math.sin(segStartAngle);
            double dStartYp =  ry * Math.cos(segStartAngle);
            double dEndXp   = -rx * Math.sin(segEndAngle);
            double dEndYp   =  ry * Math.cos(segEndAngle);
            // 控制点（alpha 已与 segAngle 同号，自动处理 sweep 反向）
            double c1xp = rx * Math.cos(segStartAngle) + alpha * dStartXp;
            double c1yp = ry * Math.sin(segStartAngle) + alpha * dStartYp;
            double c2xp = rx * Math.cos(segEndAngle)   - alpha * dEndXp;
            double c2yp = ry * Math.sin(segEndAngle)   - alpha * dEndYp;
            // 应用 rotation + center 平移
            double c1x = rotX(c1xp, c1yp, cosPhi, sinPhi) + cx;
            double c1y = rotY(c1xp, c1yp, cosPhi, sinPhi) + cy;
            double c2x = rotX(c2xp, c2yp, cosPhi, sinPhi) + cx;
            double c2y = rotY(c2xp, c2yp, cosPhi, sinPhi) + cy;

            path.curveTo(c1x, c1y, c2x, c2y, segEndX, segEndY);

            // 最后一段：输出 end tangent（沿 path 走向）
            if (s == segments - 1) {
                endTanOut[0] = rotX(dEndXp * signDir, dEndYp * signDir, cosPhi, sinPhi);
                endTanOut[1] = rotY(dEndXp * signDir, dEndYp * signDir, cosPhi, sinPhi);
            }

            segStartAngle = segEndAngle;
        }
    }

    private static double rotX(double x, double y, double cosPhi, double sinPhi) {
        return cosPhi * x - sinPhi * y;
    }

    private static double rotY(double x, double y, double cosPhi, double sinPhi) {
        return sinPhi * x + cosPhi * y;
    }

    /** 求向量 (ux,uy) → (vx,vy) 的有向夹角 ∈ (-π, π]。 */
    private static double angleBetween(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double lenU = Math.hypot(ux, uy);
        double lenV = Math.hypot(vx, vy);
        double cos = dot / (lenU * lenV);
        if (cos > 1) cos = 1;
        if (cos < -1) cos = -1;
        double sign = (ux * vy - uy * vx) >= 0 ? 1 : -1;
        return sign * Math.acos(cos);
    }

    // ---------- 解析辅助 ----------

    private static Result empty(Path2D.Double path) {
        return new Result(path, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    private static int paramsForCommand(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'm', 'l', 't' -> 2;
            case 'h', 'v' -> 1;
            case 'q', 's' -> 4;
            case 'c' -> 6;
            case 'a' -> 7;
            case 'z' -> 0;
            default -> -1;
        };
    }

    private static boolean isCmd(char c) {
        char lc = Character.toLowerCase(c);
        return lc == 'm' || lc == 'l' || lc == 'h' || lc == 'v'
                || lc == 'q' || lc == 't'
                || lc == 'c' || lc == 's'
                || lc == 'a' || lc == 'z';
    }

    private static boolean isSep(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',';
    }

    /**
     * 扫描一个数字 token，把结束 index 写入 {@code outEndIdx[0]}；若不像数字返回 0 且
     * outEndIdx[0] 不变（== i）。
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

    /**
     * 扫描 A 命令的 flag（0 或 1，单字符即一个 flag——SVG 允许无分隔写法 {@code 00} = 两个 flag）。
     */
    private static double scanFlag(String s, int i, int[] outEndIdx) {
        if (i >= s.length()) { outEndIdx[0] = i; return 0; }
        char c = s.charAt(i);
        if (c == '0') { outEndIdx[0] = i + 1; return 0; }
        if (c == '1') { outEndIdx[0] = i + 1; return 1; }
        // 非 '0'/'1' 字符不是合法 flag——不 fall-through 到 scanNumber
        // （否则会把后续坐标数字误吞为 flag、错位整段弧参数）。返回 outEndIdx 不前进
        // （== i），让调用方 line 144 的 `endIdx[0] == i` 守卫停止解析，与 scanNumber
        // 遇非数字时的行为一致。
        outEndIdx[0] = i;
        return 0;
    }
}
