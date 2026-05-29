// SVG path d 字符串缩放工具（M9 PathDValidator 子集：M/L/Q/C/Z 大小写）。
//
// 用途：CanvasView 的 onTransformEnd 在 PathElement 缩放时，把 d 内坐标按
// (sx, sy) 缩放后一并 patch 回后端，否则 d 不变 = 几何形状不变，
// resize handle 拖动失效（2026-05-14 Bug 修复）。
//
// 算法：tokenize d → 按命令字母分组 → 把后续连续数字按 (x, y) 配对乘缩放系数。
// Z/z 无坐标，原样保留。

const CMD_RE = /[MLmlQqCcZz]/;
// P3-26：数字部分扩展为可选整数位 + 可选小数 + 可选科学计数，与后端 PathDValidator.scanNumber
// 及前端 PathParser.ts 的词法一致。裸 `-?\d+(?:\.\d+)?` 无法匹配 .5 / -.5 / 1e2 等合法 SVG
// 数字（导入工程 / 模板 raw_state / 外部工具写入的 d 串会出现），导致缩放时坐标量级跳变 / 配对错位。
const TOKEN_RE = /[MLmlQqCcZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?/g;

/**
 * 缩放 d 字符串内所有坐标。
 *
 * @param d  原始 d 字符串（M9 子集：M/L/Q/C/Z 大小写均接受）
 * @param sx X 方向缩放系数
 * @param sy Y 方向缩放系数
 * @returns  缩放后的 d；输入空或 sx==sy==1 时返回原样
 */
export function scalePathD(d: string, sx: number, sy: number): string {
    if (!d) return d;
    if (sx === 1 && sy === 1) return d;
    const tokens = d.match(TOKEN_RE);
    if (!tokens || tokens.length === 0) return d;

    const out: string[] = [];
    let i = 0;
    while (i < tokens.length) {
        const t = tokens[i++];
        if (CMD_RE.test(t)) {
            out.push(t);
            if (t === 'Z' || t === 'z') continue;
            // 收集后续连续数字直到下一个命令
            const args: number[] = [];
            while (i < tokens.length && !CMD_RE.test(tokens[i])) {
                args.push(parseFloat(tokens[i++]));
            }
            // 按偶/奇下标 × sx / sy（M/L/Q/C 命令的坐标都是成对 (x, y)）
            for (let j = 0; j < args.length; j++) {
                const v = args[j] * (j % 2 === 0 ? sx : sy);
                out.push(formatNum(v));
            }
        } else {
            // 异常：d 开头应该是命令；原样保留（PathDValidator 后端会拒）
            out.push(t);
        }
    }
    return out.join(' ');
}

/**
 * 4 位小数 + 整数不加 .0；保证 PathDValidator 正则接受（M9 PathDValidator 允许浮点）。
 */
function formatNum(v: number): string {
    const rounded = Math.round(v * 10000) / 10000;
    // 修复 -0 → 0
    if (rounded === 0) return '0';
    return String(rounded);
}
