package moe.hikari.canvas.state;

import java.util.Locale;

/**
 * SVG path {@code d} 字符串词法校验（M9 引入）。
 *
 * <p><b>仅校验</b>命令字母合法 + 数字-命令配对正确 + 数值有限 + 总长上限；
 * <b>不</b>构造 {@code Path2D}。真正绘制时由 {@code render/PathParser}（M9-B）执行。</p>
 *
 * <p>支持命令：{@code M / L / Q / C / Z}（大写绝对、小写相对）。每命令参数数：</p>
 * <ul>
 *   <li>M / m, L / l：2（x y）；M 后续重复参数自动隐式 L</li>
 *   <li>Q / q：4（cx cy x y）</li>
 *   <li>C / c：6（c1x c1y c2x c2y x y）</li>
 *   <li>Z / z：0</li>
 * </ul>
 *
 * <p><b>约束：</b></p>
 * <ul>
 *   <li>整串长度 ≤ {@link #MAX_LEN}</li>
 *   <li>首命令必须是 M / m</li>
 *   <li>所有数值 finite + |v| ≤ {@link #MAX_COORD}（避免溢出/失控）</li>
 *   <li>每条命令的参数数量必须是其要求的整数倍（M 例外：第一组 = moveto，后续 = 隐式 lineto）</li>
 * </ul>
 */
public final class PathDValidator {

    public static final int MAX_LEN = 4096;
    public static final int MAX_COORD = 100_000;

    /** 校验结果。 */
    public record Result(boolean ok, String reason) {
        public static final Result OK = new Result(true, null);
        public static Result fail(String reason) { return new Result(false, reason); }
    }

    private PathDValidator() {}

    public static Result validate(String d) {
        if (d == null) return Result.fail("d is null");
        if (d.isEmpty()) return Result.fail("d is empty");
        if (d.length() > MAX_LEN) {
            return Result.fail("d length " + d.length() + " > " + MAX_LEN);
        }

        int i = 0;
        int n = d.length();
        // 跳过前导空白
        while (i < n && isSep(d.charAt(i))) i++;
        if (i >= n) return Result.fail("d has no command");

        // 首命令必须是 M / m
        char first = d.charAt(i);
        if (first != 'M' && first != 'm') {
            return Result.fail("d must start with M or m, got '" + first + "'");
        }

        // 状态机：当前命令字母 + 已读参数数
        char curCmd = 0;
        int paramsRead = 0;
        int paramsNeeded = 0;

        while (i < n) {
            char c = d.charAt(i);
            if (isSep(c)) {
                i++;
                continue;
            }
            if (isCmd(c)) {
                // 上一个命令应已收齐参数
                if (curCmd != 0 && !cmdParamsComplete(curCmd, paramsRead)) {
                    return Result.fail("incomplete params for '" + curCmd
                            + "' (got " + paramsRead + ", expected multiple of " + paramsNeeded + ")");
                }
                curCmd = c;
                paramsNeeded = paramsPerCommand(c);
                paramsRead = 0;
                i++;
                if (paramsNeeded == 0) {
                    // Z / z 即刻完成；不切 curCmd（下一个命令应当是新命令字母）
                    curCmd = 0;
                }
                continue;
            }
            if (isNumStart(c)) {
                if (curCmd == 0) {
                    return Result.fail("number before any command at index " + i);
                }
                int j = scanNumber(d, i);
                if (j == i) {
                    return Result.fail("malformed number at index " + i);
                }
                String token = d.substring(i, j);
                double v;
                try {
                    v = Double.parseDouble(token);
                } catch (NumberFormatException e) {
                    return Result.fail("not a number '" + token + "' at index " + i);
                }
                if (!Double.isFinite(v)) {
                    return Result.fail("non-finite number '" + token + "'");
                }
                if (Math.abs(v) > MAX_COORD) {
                    return Result.fail("number '" + token + "' out of range ±" + MAX_COORD);
                }
                paramsRead++;
                i = j;
                continue;
            }
            return Result.fail("unexpected character '" + c + "' at index " + i);
        }

        // 末尾收尾：curCmd 应已配齐参数
        if (curCmd != 0 && !cmdParamsComplete(curCmd, paramsRead)) {
            return Result.fail("incomplete params for last command '" + curCmd
                    + "' (got " + paramsRead + ", expected multiple of " + paramsNeeded + ")");
        }
        return Result.OK;
    }

    /** 每个命令需要的参数数（Z 为 0）。 */
    private static int paramsPerCommand(char c) {
        char lower = Character.toLowerCase(c);
        return switch (lower) {
            case 'm', 'l' -> 2;
            case 'q' -> 4;
            case 'c' -> 6;
            case 'z' -> 0;
            default -> -1;  // unreachable, isCmd 已守
        };
    }

    /**
     * 校验某命令的参数数是否凑齐。M 的第一组后允许"隐式 L"，参数数可以是 2k（k ≥ 1）；
     * L / Q / C 同样允许重复多组；Z / z 必须 0。
     */
    private static boolean cmdParamsComplete(char c, int paramsRead) {
        int need = paramsPerCommand(c);
        if (need == 0) return paramsRead == 0;
        // 必须 ≥ need 且是 need 的整数倍
        if (paramsRead < need) return false;
        return paramsRead % need == 0;
    }

    private static boolean isCmd(char c) {
        char lc = Character.toLowerCase(c);
        return lc == 'm' || lc == 'l' || lc == 'q' || lc == 'c' || lc == 'z';
    }

    /** path d 分隔符：空白 / 逗号。 */
    private static boolean isSep(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',';
    }

    /** 数字开头字符：digit / + / - / .。 */
    private static boolean isNumStart(char c) {
        return Character.isDigit(c) || c == '+' || c == '-' || c == '.';
    }

    /**
     * 扫描一个完整数字 token（整数 / 小数 / 科学记数 / 带正负号）。
     * 返回数字结束后的下一个 index；若 i 处不像数字，返 i 不变。
     */
    private static int scanNumber(String s, int i) {
        int n = s.length();
        int j = i;
        // 符号
        if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
        boolean hasDigit = false;
        // 整数部分
        while (j < n && Character.isDigit(s.charAt(j))) { j++; hasDigit = true; }
        // 小数部分
        if (j < n && s.charAt(j) == '.') {
            j++;
            while (j < n && Character.isDigit(s.charAt(j))) { j++; hasDigit = true; }
        }
        if (!hasDigit) return i;  // 没有数字主体（如孤立的 + - .）
        // 科学记数
        if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
            int eIdx = j;
            j++;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
            boolean hasExpDigit = false;
            while (j < n && Character.isDigit(s.charAt(j))) { j++; hasExpDigit = true; }
            if (!hasExpDigit) return eIdx;  // 退回 e 之前作为分隔
        }
        return j;
    }

    /** 给 debug / error message 用：归一化 d 字符串（去多余空白）。 */
    public static String normalize(String d) {
        if (d == null) return "";
        return d.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
