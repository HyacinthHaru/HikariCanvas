/**
 * 0.4.1 chip 编辑器：lexicalChip 序列化 / DecoratorNode 单测。
 *
 * <p>核心保证：text → lexical EditorState → text 字符级 roundtrip 等。任何 chip 插入 /
 * 普通文字编辑 / 序列化 / 反序列化必须等于原 string。否则用户保存 wall 后内容会变。</p>
 *
 * <p>用 lexical headless editor（不需要 DOM）；createDOM 在本测试不执行——chip 的 DOM
 * 行为由 e2e / 手动测试覆盖。本文件只覆盖："数据模型 ↔ 字符串"的纯逻辑。</p>
 */
import { describe, expect, it } from 'vitest';
import { createEditor, type LexicalEditor } from 'lexical';
import {
    VariablePlaceholderNode,
    $createVariablePlaceholderNode,
    $insertVariableChipAtSelection,
    $isVariablePlaceholderNode,
    lexicalRootToText,
    textToLexicalNodes,
} from '../lexicalChip';

/** 创建一个 headless lexical 编辑器，注册 VariablePlaceholderNode；不挂载 root DOM。 */
function makeEditor(): LexicalEditor {
    return createEditor({
        namespace: 'test',
        onError: (err) => {
            throw err;
        },
        nodes: [VariablePlaceholderNode],
    });
}

/** 同步 roundtrip：text → editorState → text。lexical update 是同步执行回调。 */
function roundtrip(text: string): string {
    const editor = makeEditor();
    let result = '';
    editor.update(
        () => {
            textToLexicalNodes(text);
        },
        { discrete: true },
    );
    editor.read(() => {
        result = lexicalRootToText();
    });
    return result;
}

// ---------- roundtrip ----------

describe('lexicalChip roundtrip text ↔ EditorState', () => {
    it('纯文本：单行', () => {
        expect(roundtrip('hello world')).toBe('hello world');
    });

    it('纯文本：空字符串', () => {
        expect(roundtrip('')).toBe('');
    });

    it('纯文本：多行', () => {
        const s = 'line1\nline2\nline3';
        expect(roundtrip(s)).toBe(s);
    });

    it('纯文本：含特殊字符（中文 / emoji）', () => {
        const s = '红队 12 ⚡ vs 蓝队 8';
        expect(roundtrip(s)).toBe(s);
    });

    it('单 chip：纯占位符', () => {
        const s = '${var:schedule/eta_seconds}';
        expect(roundtrip(s)).toBe(s);
    });

    it('单 chip：含 fallback', () => {
        const s = '${var:user/red_score|fallback=0}';
        expect(roundtrip(s)).toBe(s);
    });

    it('单 chip + 前后普通文字', () => {
        const s = 'ETA: ${var:schedule/eta_seconds} 秒';
        expect(roundtrip(s)).toBe(s);
    });

    it('多 chip 同行（连续）', () => {
        const s = '${var:user/a}${var:user/b}';
        expect(roundtrip(s)).toBe(s);
    });

    it('多 chip 同行（有间隔）', () => {
        const s = '红队 ${var:user/red} vs 蓝队 ${var:user/blue}';
        expect(roundtrip(s)).toBe(s);
    });

    it('chip 在开头', () => {
        const s = '${var:server.time} 当前';
        expect(roundtrip(s)).toBe(s);
    });

    it('chip 在结尾', () => {
        const s = '在线: ${var:server.online}';
        expect(roundtrip(s)).toBe(s);
    });

    it('chip + 换行', () => {
        const s = 'line1 ${var:user/x}\nline2 ${var:user/y}';
        expect(roundtrip(s)).toBe(s);
    });

    it('chip + fallback + 换行 + 多 chip', () => {
        const s = 'A ${var:user/a|fallback=0}\nB ${var:schedule/eta_seconds|fallback=?}\n';
        expect(roundtrip(s)).toBe(s);
    });

    it('text 含 ${...} 但不是 var 语法（不识别为 chip）', () => {
        // 我们的 pattern 严格匹配 ${var:...}，普通 ${foo} 不动
        const s = 'price ${100}';
        expect(roundtrip(s)).toBe(s);
    });

    it('空 fallback：${var:X|fallback=}', () => {
        // fallback="" 应保留——与 interpolator 一致
        const s = '${var:user/x|fallback=}';
        expect(roundtrip(s)).toBe(s);
    });

    it('chip 在两行末尾 / 行首边界', () => {
        const s = '${var:a}\n${var:b}\n${var:c}';
        expect(roundtrip(s)).toBe(s);
    });

    it('末尾空行（\\n\\n）', () => {
        const s = 'hello\n\n${var:x}';
        expect(roundtrip(s)).toBe(s);
    });
});

// ---------- node ops ----------

describe('VariablePlaceholderNode 基础 op', () => {
    it('toPlaceholderString 还原占位符（无 fallback）', () => {
        const editor = makeEditor();
        editor.update(
            () => {
                const node = $createVariablePlaceholderNode('user/x');
                expect(node.toPlaceholderString()).toBe('${var:user/x}');
            },
            { discrete: true },
        );
    });

    it('toPlaceholderString 还原占位符（含 fallback）', () => {
        const editor = makeEditor();
        editor.update(
            () => {
                const node = $createVariablePlaceholderNode('schedule/eta_seconds', '?');
                expect(node.toPlaceholderString()).toBe('${var:schedule/eta_seconds|fallback=?}');
            },
            { discrete: true },
        );
    });

    it('getTextContent 与 toPlaceholderString 等价（copy / selection 路径靠它）', () => {
        const editor = makeEditor();
        editor.update(
            () => {
                const a = $createVariablePlaceholderNode('user/a');
                const b = $createVariablePlaceholderNode('user/b', 'fb');
                expect(a.getTextContent()).toBe(a.toPlaceholderString());
                expect(b.getTextContent()).toBe(b.toPlaceholderString());
            },
            { discrete: true },
        );
    });

    it('$isVariablePlaceholderNode 类型守卫', () => {
        const editor = makeEditor();
        editor.update(
            () => {
                const chip = $createVariablePlaceholderNode('x');
                expect($isVariablePlaceholderNode(chip)).toBe(true);
                expect($isVariablePlaceholderNode(null)).toBe(false);
                expect($isVariablePlaceholderNode(undefined)).toBe(false);
            },
            { discrete: true },
        );
    });

    it('setRawName / setFallback 改值后 roundtrip 同步', () => {
        const editor = makeEditor();
        editor.update(
            () => {
                textToLexicalNodes('A ${var:user/old} B');
            },
            { discrete: true },
        );
        editor.update(
            () => {
                const all = lexicalRootToText();
                expect(all).toBe('A ${var:user/old} B');
            },
            { discrete: true },
        );
    });

    it('$insertVariableChipAtSelection 在空文档末尾追加 chip', () => {
        const editor = makeEditor();
        editor.update(
            () => {
                textToLexicalNodes('');
                $insertVariableChipAtSelection('user/new', null);
            },
            { discrete: true },
        );
        let out = '';
        editor.read(() => {
            out = lexicalRootToText();
        });
        expect(out).toBe('${var:user/new}');
    });

    it('exportJSON / importJSON roundtrip', () => {
        const editor = makeEditor();
        editor.update(
            () => {
                const a = $createVariablePlaceholderNode('schedule/eta', 'fb');
                const j = a.exportJSON();
                expect(j.type).toBe('variable-placeholder');
                expect(j.rawName).toBe('schedule/eta');
                expect(j.fallback).toBe('fb');
                const a2 = VariablePlaceholderNode.importJSON(j);
                expect(a2.getRawName()).toBe('schedule/eta');
                expect(a2.getFallback()).toBe('fb');
                expect(a2.toPlaceholderString()).toBe('${var:schedule/eta|fallback=fb}');
            },
            { discrete: true },
        );
    });
});

// ---------- 字符级精确（保护用户保存） ----------

describe('roundtrip 字符级精确（保护后端 .canvas / DB 不被 chip editor 篡改）', () => {
    it('20 case fuzz：text → state → text bit-identical', () => {
        const cases = [
            'a',
            'A b C',
            '\n',
            ' \n ',
            '${var:x}',
            '${var:x}\n${var:y}',
            'pre ${var:user/red_score} mid ${var:user/blue_score} post',
            '${var:schedule/eta_seconds|fallback=??} 秒后到站',
            'X\nY\nZ',
            '${var:a}${var:b}${var:c}',
            'normal text without variables',
            'mix: 123 ${var:x} !@#',
            '中文测试 ${var:user/红队比分} 中文',
            'fallback empty: ${var:x|fallback=}',
            'tabs:\t${var:x}\there',
            '   leading spaces ${var:x}',
            '${var:x}   trailing spaces',
            'multi\n\n\nlines',
            '${var:server.time}',
            'long: ${var:schedule/eta_seconds|fallback=N/A} — long: ${var:user/score|fallback=0}',
        ];
        for (const s of cases) {
            expect(roundtrip(s)).toBe(s);
        }
    });
});
