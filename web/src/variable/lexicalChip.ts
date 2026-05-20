/**
 * 0.4.1 Chip 编辑器：Lexical DecoratorNode + 序列化 helper（前端 only，不影响协议）。
 *
 * <p>用 lexical core 直接接入（不依赖 lexical-vue / vue-vine）。`VariablePlaceholderNode`
 * 继承 {@code DecoratorNode}，{@link createDOM} 渲染一个 contenteditable=false 的 chip
 * pill，{@link getTextContent} 返还 {@code ${var:rawName[|fallback=...]}} 让 Lexical 的
 * 内置 {@code TextContent}/{@code copy} 路径自动得到字符串形态——roundtrip 由此 free。</p>
 *
 * <p>序列化策略：</p>
 * <ul>
 *   <li>{@link textToLexicalNodes}：把 {@code element.text}（含 {@code ${var:X}} 字面）拆成
 *     ParagraphNode 树；普通段为 TextNode，placeholder 段为 VariablePlaceholderNode。换行
 *     拆 ParagraphNode（lexical 内部 Paragraph 之间 = \n）。</li>
 *   <li>{@link lexicalRootToText}：遍历 root → 段落用 \n join；TextNode 取 text；
 *     VariablePlaceholderNode 用 {@link VariablePlaceholderNode.toPlaceholderString} 还原。
 *     字符级精确 —— 这是 chip 编辑器和 element.text 后端协议的唯一桥梁。</li>
 * </ul>
 *
 * <p>占位符正则与 {@code interpolator.VARIABLE_PATTERN} 一致；本模块单独复制一份是为了
 * 把"chip 解析"与"渲染时插值"职责分离（前者写入 EditorState，后者读 Variable 当前值）。</p>
 */
import {
    DecoratorNode,
    $createParagraphNode,
    $createTextNode,
    $getRoot,
    $getSelection,
    $isParagraphNode,
    $isRangeSelection,
    $isTextNode,
    type EditorConfig,
    type LexicalEditor,
    type LexicalNode,
    type NodeKey,
    type SerializedLexicalNode,
    type Spread,
    type DOMConversionMap,
    type DOMExportOutput,
} from 'lexical';

/** 序列化到 JSON 时 type 字段。 */
export const VAR_NODE_TYPE = 'variable-placeholder';

/** 与 {@code interpolator.VARIABLE_PATTERN} 镜像，但本模块独立 owns。 */
export const CHIP_PARSE_PATTERN = /\$\{var:([^|}]+)(?:\|fallback=([^}]*))?\}/g;

/** chip DOM data attribute（外部可监听 click / hover）。 */
export const CHIP_DATA_RAW_NAME = 'data-hk-var-raw';
export const CHIP_DATA_FALLBACK = 'data-hk-var-fallback';
export const CHIP_CLASS = 'hc-var-chip';

/** chip 接收外部事件用的自定义事件名。 */
export const CHIP_EVENT_CLICK = 'hk-chip-click';
export const CHIP_EVENT_HOVER = 'hk-chip-hover';
export const CHIP_EVENT_LEAVE = 'hk-chip-leave';

/** JSON 序列化形态（lexical EditorState 持久化时用；本项目不持久化 lexical state，但实现
 * 是为了让 default import/export 路径完整）。 */
export type SerializedVariablePlaceholder = Spread<
    {
        rawName: string;
        fallback: string | null;
    },
    SerializedLexicalNode
>;

/**
 * VariablePlaceholderNode —— 一个不可编辑的 inline DecoratorNode，对应 element.text
 * 字符串里的一段 {@code ${var:X[|fallback=...]}}。
 *
 * <p>渲染细节：</p>
 * <ul>
 *   <li>{@link createDOM}：返回 {@code <span class="hc-var-chip" contenteditable="false"
 *     data-hk-var-raw="X" data-hk-var-fallback="...">${var:X}</span>}。初始内容是 chip
 *     文本（rawName）——live 当前值由外层 Vue 组件 mount 后扫描 chip 节点动态填充
 *     （避免在 Lexical 节点里耦合 Pinia store 实例）。</li>
 *   <li>{@link getTextContent}：Lexical 的 selection 取 text / copy 等路径会走这里。
 *     返还字面 {@code ${var:rawName[|fallback=...]}}——这让 {@code root.getTextContent()}
 *     一次性就拿到完整 element.text。</li>
 *   <li>{@link isInline} = true：与同段 TextNode 共用一行 flow。</li>
 *   <li>{@link isIsolated} = false / {@link isKeyboardSelectable} = true：光标在 chip
 *     边界按 Backspace/Delete 会整体删除（lexical 默认行为）。</li>
 * </ul>
 */
export class VariablePlaceholderNode extends DecoratorNode<null> {
    /** 原占位符里的 X（如 {@code schedule/eta_seconds} / {@code user/red_score}）。 */
    __rawName: string;
    /** {@code |fallback=...} 的备用值；无则 null。 */
    __fallback: string | null;

    static getType(): string {
        return VAR_NODE_TYPE;
    }

    static clone(node: VariablePlaceholderNode): VariablePlaceholderNode {
        return new VariablePlaceholderNode(node.__rawName, node.__fallback, node.__key);
    }

    constructor(rawName: string, fallback: string | null = null, key?: NodeKey) {
        super(key);
        this.__rawName = rawName;
        this.__fallback = fallback;
    }

    isInline(): boolean {
        return true;
    }

    isIsolated(): boolean {
        return false;
    }

    isKeyboardSelectable(): boolean {
        return true;
    }

    /** 与 {@code ${var:X[|fallback=...]}} 严格一致；roundtrip 不可破坏。 */
    toPlaceholderString(): string {
        return this.__fallback != null
            ? `\${var:${this.__rawName}|fallback=${this.__fallback}}`
            : `\${var:${this.__rawName}}`;
    }

    getRawName(): string {
        return this.__rawName;
    }

    getFallback(): string | null {
        return this.__fallback;
    }

    setRawName(rawName: string): void {
        const w = this.getWritable() as VariablePlaceholderNode;
        w.__rawName = rawName;
    }

    setFallback(fallback: string | null): void {
        const w = this.getWritable() as VariablePlaceholderNode;
        w.__fallback = fallback;
    }

    /** lexical 主轨：复制 / selection 取文本时回到字面 placeholder——roundtrip free。 */
    getTextContent(): string {
        return this.toPlaceholderString();
    }

    /** chip DOM 形态；scoped CSS 在 {@code VariableChipEditor.vue} 提供。 */
    createDOM(_config: EditorConfig, _editor: LexicalEditor): HTMLElement {
        const span = document.createElement('span');
        span.className = CHIP_CLASS;
        span.setAttribute('contenteditable', 'false');
        span.setAttribute('spellcheck', 'false');
        span.setAttribute(CHIP_DATA_RAW_NAME, this.__rawName);
        if (this.__fallback != null) {
            span.setAttribute(CHIP_DATA_FALLBACK, this.__fallback);
        }
        // 初始展示：rawName（外层 Vue 拿到 store 后会改文本为 currentValue）
        span.textContent = this.__rawName;
        // 阻止 chip 内的点击冒泡为 Lexical 的 selection 改变；点击 dispatch 给外层处理
        span.addEventListener('mousedown', (ev) => {
            ev.preventDefault();
        });
        span.addEventListener('click', (ev) => {
            ev.preventDefault();
            ev.stopPropagation();
            span.dispatchEvent(
                new CustomEvent(CHIP_EVENT_CLICK, {
                    bubbles: true,
                    detail: { rawName: this.__rawName, fallback: this.__fallback },
                }),
            );
        });
        span.addEventListener('mouseenter', () => {
            span.dispatchEvent(
                new CustomEvent(CHIP_EVENT_HOVER, {
                    bubbles: true,
                    detail: { rawName: this.__rawName, fallback: this.__fallback },
                }),
            );
        });
        span.addEventListener('mouseleave', () => {
            span.dispatchEvent(new CustomEvent(CHIP_EVENT_LEAVE, { bubbles: true }));
        });
        return span;
    }

    /** Lexical 仅在 rawName / fallback 变化时重渲染 DOM（不变则 keep）。 */
    updateDOM(prevNode: VariablePlaceholderNode, dom: HTMLElement, _config: EditorConfig): boolean {
        if (prevNode.__rawName !== this.__rawName) {
            dom.setAttribute(CHIP_DATA_RAW_NAME, this.__rawName);
            dom.textContent = this.__rawName;
        }
        if (prevNode.__fallback !== this.__fallback) {
            if (this.__fallback != null) dom.setAttribute(CHIP_DATA_FALLBACK, this.__fallback);
            else dom.removeAttribute(CHIP_DATA_FALLBACK);
        }
        return false; // DOM 复用
    }

    /** DecoratorNode 主轨：本节点 DOM 由 createDOM 完整管，不需要 portal。 */
    decorate(): null {
        return null;
    }

    // ---------- 持久化（JSON）形态；本项目暂不用，但 Lexical 要求实现 ----------

    static importJSON(json: SerializedVariablePlaceholder): VariablePlaceholderNode {
        return new VariablePlaceholderNode(json.rawName, json.fallback ?? null);
    }

    exportJSON(): SerializedVariablePlaceholder {
        return {
            type: VAR_NODE_TYPE,
            version: 1,
            rawName: this.__rawName,
            fallback: this.__fallback,
        };
    }

    /** copy / paste 时 lexical 用 DOMConversion 识别 HTML → chip；本项目把 HTML serialize
     * 成 plain placeholder，paste 时由 {@link textToLexicalNodes} 再拆——简化路径。 */
    exportDOM(): DOMExportOutput {
        const span = document.createElement('span');
        span.className = CHIP_CLASS;
        span.setAttribute(CHIP_DATA_RAW_NAME, this.__rawName);
        if (this.__fallback != null) span.setAttribute(CHIP_DATA_FALLBACK, this.__fallback);
        span.textContent = this.toPlaceholderString();
        return { element: span };
    }

    static importDOM(): DOMConversionMap | null {
        // 不主动注册——paste 走 plain text 路径，由 textToLexicalNodes 解析 ${var:...} 字面
        return null;
    }
}

export function $createVariablePlaceholderNode(
    rawName: string,
    fallback: string | null = null,
): VariablePlaceholderNode {
    return new VariablePlaceholderNode(rawName, fallback);
}

export function $isVariablePlaceholderNode(
    node: LexicalNode | null | undefined,
): node is VariablePlaceholderNode {
    return node instanceof VariablePlaceholderNode;
}

// ===========================================================================
// 序列化：text 字符串 ↔ Lexical EditorState
// ===========================================================================

/**
 * 把 {@code element.text} 字符串拆为 ParagraphNode → (TextNode | VariablePlaceholderNode)
 * 序列。在 lexical update() 闭包内调用：会清空 root 然后写入新树。
 *
 * <p>换行处理：text 按 {@code \n} 拆为多个段落（lexical 内部 Paragraph 之间 = \n，
 * {@link lexicalRootToText} 反向 join \n）。</p>
 */
export function textToLexicalNodes(text: string): void {
    const root = $getRoot();
    root.clear();
    if (text.length === 0) {
        root.append($createParagraphNode());
        return;
    }
    const lines = text.split('\n');
    for (const line of lines) {
        const p = $createParagraphNode();
        // 单行内：扫描 ${var:...} 拆 TextNode / VariablePlaceholderNode 交替
        const pattern = new RegExp(CHIP_PARSE_PATTERN.source, 'g');
        let lastIdx = 0;
        let m: RegExpExecArray | null;
        while ((m = pattern.exec(line)) !== null) {
            if (m.index > lastIdx) {
                p.append($createTextNode(line.substring(lastIdx, m.index)));
            }
            const rawName = m[1];
            const fallback: string | null = m[2] !== undefined ? m[2] : null;
            p.append($createVariablePlaceholderNode(rawName, fallback));
            lastIdx = m.index + m[0].length;
            if (m[0].length === 0) pattern.lastIndex += 1; // 防御性
        }
        if (lastIdx < line.length) {
            p.append($createTextNode(line.substring(lastIdx)));
        }
        root.append(p);
    }
}

/**
 * 把 lexical root 还原为 {@code element.text} 字符串。
 *
 * <p>段落之间 join \n；段落内遍历 children：</p>
 * <ul>
 *   <li>TextNode → 取 text</li>
 *   <li>VariablePlaceholderNode → 取 {@link VariablePlaceholderNode.toPlaceholderString}</li>
 *   <li>其他节点（理论不存在）→ {@link LexicalNode.getTextContent} 兜底</li>
 * </ul>
 *
 * <p>必须在 {@code editor.read(() => ...)} 或 {@code editor.update(() => ...)} 内调用。</p>
 */
export function lexicalRootToText(): string {
    const root = $getRoot();
    const paragraphs: string[] = [];
    for (const para of root.getChildren()) {
        if (!$isParagraphNode(para)) {
            // 不应发生；保险走 getTextContent
            paragraphs.push(para.getTextContent());
            continue;
        }
        let acc = '';
        for (const child of para.getChildren()) {
            if ($isVariablePlaceholderNode(child)) {
                acc += child.toPlaceholderString();
            } else if ($isTextNode(child)) {
                acc += child.getTextContent();
            } else {
                acc += child.getTextContent();
            }
        }
        paragraphs.push(acc);
    }
    return paragraphs.join('\n');
}

/**
 * 在当前 selection 处插入 chip。需在 editor.update 闭包内调用。
 *
 * <p>由 caller 已经处理"先删除 trigger 字符（{@code ${}}）"再调本函数。本函数只做插入
 * 动作 + 在 chip 后追加一个空 TextNode（确保光标继续可输入而不"卡"在 chip 末尾）。</p>
 */
export function $insertVariableChipAtSelection(
    rawName: string,
    fallback: string | null = null,
): void {
    const sel = $getSelection();
    const chip = $createVariablePlaceholderNode(rawName, fallback);
    if ($isRangeSelection(sel)) {
        sel.insertNodes([chip]);
        // 在 chip 之后插一个 zero-width TextNode 以便光标继续输入
        const trailing = $createTextNode('');
        chip.insertAfter(trailing);
        trailing.select();
    } else {
        // 无 selection：直接 append 到末尾
        const root = $getRoot();
        let last = root.getLastChild();
        if (!last || !$isParagraphNode(last)) {
            last = $createParagraphNode();
            root.append(last);
        }
        last.append(chip);
    }
}
