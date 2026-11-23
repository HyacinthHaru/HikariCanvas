/**
 * 中英文案表完整性校验：键集合一一对应，英文不含中文字符，中文无开发黑话泄漏。
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { messages } from '../messages';
import { formatUiError } from '../index';
import { useUiStore } from '@/stores/ui';

/** 递归展开成 'a.b.c' 形式的叶子路径集合（函数与字符串都算叶子）。 */
function leafKeys(node: unknown, prefix = ''): string[] {
    if (node === null || typeof node !== 'object') return [prefix];
    return Object.entries(node as Record<string, unknown>)
        .flatMap(([k, v]) => leafKeys(v, prefix ? `${prefix}.${k}` : k));
}

describe('i18n/messages', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
    });

    it('zh 与 en 的键集合完全一致', () => {
        const zh = new Set(leafKeys(messages.zh));
        const en = new Set(leafKeys(messages.en));
        expect({
            英文缺的键: [...zh].filter((k) => !en.has(k)),
            中文缺的键: [...en].filter((k) => !zh.has(k)),
        }).toEqual({ 英文缺的键: [], 中文缺的键: [] });
    });

    it('油漆桶的滑块标签与不支持元素提示均自然本地化', () => {
        expect(messages.zh.livePaint.toleranceLabel).toBeTruthy();
        expect(messages.zh.livePaint.toleranceTip).toBeTruthy();
        expect(messages.en.livePaint.toleranceLabel).toBeTruthy();
        expect(messages.en.livePaint.toleranceTip).toBeTruthy();
        expect(messages.zh.livePaint.elementUnsupported('brush')).toContain('笔触');
        expect(messages.zh.livePaint.elementUnsupported('brush')).not.toContain('brush');
        expect(messages.en.livePaint.elementUnsupported('brush')).toContain('Brush');
    });

    it('no Chinese chars in en string leaves', () => {
        const cjk = /[\u4e00-\u9fff]/;
        const bad: string[] = [];
        const walk = (obj: Record<string, unknown>, prefix = ''): void => {
            for (const [k, v] of Object.entries(obj)) {
                const path = prefix ? `${prefix}.${k}` : k;
                if (typeof v === 'string' && cjk.test(v)) {
                    bad.push(`${path}: ${v}`);
                } else if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
                    walk(v as Record<string, unknown>, path);
                }
            }
        };
        walk(messages.en as unknown as Record<string, unknown>);
        expect(bad, `en has Chinese: ${bad.join(' | ')}`).toEqual([]);
    });

    it('工程导入与 SVG 导入兜底错误不直接暴露 UNKNOWN 或原始协议码', () => {
        expect(messages.zh.project.importFailed('UNKNOWN')).not.toContain('UNKNOWN');
        expect(messages.en.project.importFailed('UNKNOWN')).not.toContain('UNKNOWN');
        expect(messages.zh.project.importFailed('IMPORT_SIZE_MISMATCH')).not.toContain('IMPORT_SIZE_MISMATCH');
        expect(messages.en.project.importFailed('IMPORT_SIZE_MISMATCH')).not.toContain('IMPORT_SIZE_MISMATCH');
        expect(messages.zh.svgImport.failed('UNKNOWN')).not.toContain('UNKNOWN');
        expect(messages.en.svgImport.failed('UNKNOWN')).not.toContain('UNKNOWN');
    });

    it('zh 静态文案不含裸英文 wall 或开发术语', () => {
        const forbidden = /\bwall\b|accepted_v|sha256|字形剪切倾斜变换|容差 4 像素|服务端|指令模板/i;
        const bad: string[] = [];
        const walk = (obj: Record<string, unknown>, prefix = ''): void => {
            for (const [k, v] of Object.entries(obj)) {
                const path = prefix ? `${prefix}.${k}` : k;
                if (typeof v === 'string') {
                    // 命令示例里的 <wall_id> / <wall_id|alias> 参数占位符合法保留
                    const stripped = v.replace(/<wall_id(?:\|alias)?>/g, '');
                    if (forbidden.test(stripped)) {
                        bad.push(`${path}: ${v}`);
                    }
                } else if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
                    walk(v as Record<string, unknown>, path);
                }
            }
        };
        walk(messages.zh as unknown as Record<string, unknown>);
        expect(bad).toEqual([]);
    });

    it('formatUiError 将底层传输哨兵转换为友好本地化提示', () => {
        useUiStore().locale = 'zh';
        expect(formatUiError(new Error('ack_timeout'))).toBe(messages.zh.errors.TIMEOUT);
        expect(formatUiError(new Error('send_failed'))).toBe(messages.zh.errors.CONNECTION_CLOSED);
        expect(formatUiError(new Error('connection closed before ack'))).toBe(messages.zh.errors.CONNECTION_CLOSED);
        expect(formatUiError(new Error('自定义业务提示'))).toBe('自定义业务提示');

        useUiStore().locale = 'en';
        expect(formatUiError(new Error('ack_timeout'))).toBe(messages.en.errors.TIMEOUT);
        expect(formatUiError(new Error('send_failed'))).toBe(messages.en.errors.CONNECTION_CLOSED);
    });
});
