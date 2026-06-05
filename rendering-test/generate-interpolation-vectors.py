#!/usr/bin/env python3
"""0.6 P3：插值共享测试向量生成器（docs/rendering.md §9.3/§9.4）。

本脚本是规格的**第三方参照实现**（Python float = IEEE754 double，与 Java/JS 同精度）：
按 rendering.md §9.3 伪码逐行实现 cubic-bezier 求解、按 §9.4 细则实现 sRGB 线性空间
色彩插值，生成 easing-vectors.json + color-lerp-vectors.json。Java 端
（EasingSolverVectorsTest / ColorLerpVectorsTest）与 TS 端（vitest）各自加载同一份
向量比对——两端若有一端漂移即红。

再生：python3 rendering-test/generate-interpolation-vectors.py
（输出确定性：无随机、无时间戳；重跑产物逐字节一致。）
"""
import json
import os

NEWTON_ITER = 8
BISECT_MAX = 32
EPS = 1e-6

PRESETS = {
    "easeIn": (0.42, 0.0, 1.0, 1.0),
    "easeOut": (0.0, 0.0, 0.58, 1.0),
    "easeInOut": (0.42, 0.0, 0.58, 1.0),
}


def sample_x(ax, bx, cx, u):
    return ((ax * u + bx) * u + cx) * u


def solve_u(ax, bx, cx, x):
    u = x
    for _ in range(NEWTON_ITER):
        d = sample_x(ax, bx, cx, u) - x
        if abs(d) <= EPS:
            return u
        dx = (3.0 * ax * u + 2.0 * bx) * u + cx
        if abs(dx) < 1e-6:
            break
        u = u - d / dx
    lo, hi, u = 0.0, 1.0, x
    for _ in range(BISECT_MAX):
        s = sample_x(ax, bx, cx, u)
        if abs(s - x) <= EPS:
            return u
        if s < x:
            lo = u
        else:
            hi = u
        u = (lo + hi) / 2.0
    return u


def cubic_bezier(x1, y1, x2, y2, local):
    if local <= 0.0:
        return 0.0
    if local >= 1.0:
        return 1.0
    cx = 3.0 * x1
    bx = 3.0 * (x2 - x1) - cx
    ax = 1.0 - cx - bx
    cy = 3.0 * y1
    by = 3.0 * (y2 - y1) - cy
    ay = 1.0 - cy - by
    u = solve_u(ax, bx, cx, local)
    return ((ay * u + by) * u + cy) * u


T_SWEEP = [0.0, 0.01, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.99, 1.0]

# 自定义控制点：css ease / 回弹过冲（y 超 [0,1]）/ 极端 S / 陡峭 / 近线性 / 反向 x
CUSTOM_BEZIERS = [
    (0.25, 0.1, 0.25, 1.0),
    (0.34, 1.56, 0.64, 1.0),
    (1.0, 0.0, 0.0, 1.0),
    (0.9, 0.05, 0.1, 0.95),
    (0.5, 0.5, 0.5, 0.5),
    (1.0, 1.0, 0.0, 0.0),
    # P3 审查 #1：强制牛顿不收敛→二分兜底的区域（x1=x2=0 / x1=x2=1），
    # 保护 BISECT_MAX / EPS / 区间更新式不被任一端静默改动
    (0.0, 0.0, 0.0, 1.0),
    (1.0, 0.0, 1.0, 1.0),
]


def easing_cases():
    cases = []
    cases.append({
        "easing": {"type": "linear"},
        "samples": [[t, t] for t in T_SWEEP],
    })
    for name, (x1, y1, x2, y2) in PRESETS.items():
        cases.append({
            "easing": {"type": name},
            "samples": [[t, cubic_bezier(x1, y1, x2, y2, t)] for t in T_SWEEP],
        })
    for (x1, y1, x2, y2) in CUSTOM_BEZIERS:
        cases.append({
            "easing": {"type": "cubicBezier", "bezier": [x1, y1, x2, y2]},
            "samples": [[t, cubic_bezier(x1, y1, x2, y2, t)] for t in T_SWEEP],
        })
    return cases


# ---------- 色彩插值（rendering.md §9.4 细则） ----------

def srgb_decode(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def srgb_encode(l):
    return 12.92 * l if l <= 0.0031308 else 1.055 * (l ** (1.0 / 2.4)) - 0.055


def round_half_up(x):
    """Java Math.round / JS Math.round 对正数的语义：floor(x + 0.5)。"""
    import math
    return math.floor(x + 0.5)


def parse_hex(s):
    s = s.lstrip("#")
    if len(s) == 6:
        return int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255, False
    if len(s) == 8:
        return int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), int(s[6:8], 16), True
    raise ValueError(s)


def lerp_channel_gamma(a8, b8, t):
    a = srgb_decode(a8 / 255.0)
    b = srgb_decode(b8 / 255.0)
    v = a + (b - a) * t          # §9.4：一律 a + (b−a)×t 形式
    return max(0, min(255, round_half_up(srgb_encode(v) * 255.0)))


def lerp_hex(a, b, t):
    ar, ag, ab_, aa, a_has = parse_hex(a)
    br, bg, bb, ba, b_has = parse_hex(b)
    r = lerp_channel_gamma(ar, br, t)
    g = lerp_channel_gamma(ag, bg, t)
    bl = lerp_channel_gamma(ab_, bb, t)
    al = max(0, min(255, round_half_up(aa + (ba - aa) * t)))   # alpha 线性，不经 gamma
    if a_has or b_has:
        return "#{:02X}{:02X}{:02X}{:02X}".format(r, g, bl, al)
    return "#{:02X}{:02X}{:02X}".format(r, g, bl)


COLOR_PAIRS = [
    ("#000000", "#FFFFFF"),
    ("#FF0000", "#0000FF"),
    ("#123456", "#FEDCBA"),
    ("#808080", "#7F7F7F"),
    ("#FF000000", "#FF0000FF"),
    ("#00FF0080", "#0000FF80"),
    ("#FFFFFF", "#00000000"),
]

# 含过冲 eased（cubicBezier y 超 [0,1] 时 lerp 的 t 可越界——验证 clamp 行为，审查 #10）
COLOR_T = [-0.5, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5]


def color_cases():
    return [
        {"a": a, "b": b, "t": t, "expected": lerp_hex(a, b, t)}
        for (a, b) in COLOR_PAIRS for t in COLOR_T
    ]


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "easing-vectors.json"), "w") as f:
        json.dump({
            "comment": "rendering.md §9.3 共享测试向量；由 generate-interpolation-vectors.py（第三方参照实现）生成",
            "tolerance": 1e-6,
            "cases": easing_cases(),
        }, f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(os.path.join(here, "color-lerp-vectors.json"), "w") as f:
        json.dump({
            "comment": "rendering.md §9.4 共享测试向量（expected 为精确字符串匹配）；同上生成",
            "cases": color_cases(),
        }, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("easing cases:", len(easing_cases()), "color cases:", len(color_cases()))


if __name__ == "__main__":
    main()
