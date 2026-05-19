/**
 * 0.4.0-P2-G：变量名校验，从 NewVariableDialog 抽出供单测直接覆盖。
 *
 * <p>与后端 P1-A {@code Variable.NAME_REGEX = ^[a-zA-Z0-9_.-]+$} + 长度限制对齐。
 * 前端先校减少无效 op 往返；最终判定以后端 {@code VALIDATION} / {@code VARIABLE_EXISTS}
 * 错误码为准。</p>
 */

export const VARIABLE_NAME_REGEX = /^[a-zA-Z0-9_.-]+$/;
export const VARIABLE_NAME_MAX_LEN = 64;

export type VariableNameError = 'empty' | 'invalid' | 'tooLong' | null;

export interface VariableNameValidation {
    valid: boolean;
    error: VariableNameError;
}

export function validateVariableName(name: string): VariableNameValidation {
    if (name.length === 0) return { valid: false, error: 'empty' };
    if (name.length > VARIABLE_NAME_MAX_LEN) return { valid: false, error: 'tooLong' };
    if (!VARIABLE_NAME_REGEX.test(name)) return { valid: false, error: 'invalid' };
    return { valid: true, error: null };
}
