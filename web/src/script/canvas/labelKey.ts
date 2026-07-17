/**
 * 把 {@link BlockDef} / {@link FieldDef} 里的点分 i18n key
 * （如 {@code 'script.blocks.variableChange'}）解析为当前 locale 文案。
 *
 * <p>blockDefs 是声明式静态表，labelKey 存的是完整点分路径；组件渲染时用本 helper 在
 * {@code t.value}（Messages 对象）上逐段下钻取字符串。<b>找不到 / 中途非对象 → 返回 key
 * 本身</b>（degrade 为可见的 key 字符串，绝不崩）。</p>
 */

/**
 * 在 messages 根对象上按点分 key 下钻取字符串。
 *
 * @param root  当前 locale 的 messages 对象（{@code t.value}）。
 * @param key   点分路径（如 {@code 'script.fields.fullName'}）。
 * @returns 命中的字符串；任一段缺失 / 非对象 / 末值非字符串 → 返回 {@code key} 原样。
 */
export function resolveLabelKey(root: unknown, key: string): string {
    if (!key) return key;
    let cur: unknown = root;
    for (const seg of key.split('.')) {
        if (cur === null || typeof cur !== 'object') return key;
        cur = (cur as Record<string, unknown>)[seg];
    }
    return typeof cur === 'string' ? cur : key;
}
