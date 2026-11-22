/**
 * 变量名校验（对齐后端 `Variable.NAME_REGEX = ^[a-zA-Z0-9_.-]+$` 与长度上限）。
 * 前端预先拦截以减少无效请求往返；最终权威判定在服务端。
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
