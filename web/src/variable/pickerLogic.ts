/**
 * VariablePicker 纯逻辑层（0.4.0-P2-H）。
 *
 * <p>把 picker 的 group 划分 / 搜索过滤 / activeIndex 平铺 / Enter onSelect 行为从
 * Vue 组件抽出，便于 vitest node 环境直接测（不需 jsdom + @vue/test-utils）。</p>
 *
 * <p>组件 {@code VariablePicker.vue} 是 thin wrapper：拿 store + wallId + keyword
 * → 调本模块 {@link buildGroups} 得分组 + flat index 模型 → 渲染 + 键盘 / 鼠标交互。</p>
 */
import type { Variable } from '@/types/variable';

/**
 * 一组分类显示的变量。
 *
 * - {@link id}：稳定 id（{@code mine} / {@code plugin} / {@code system} / {@code papi}），i18n key 关联
 * - {@link items}：组内变量列表（已 filter 过 keyword）
 */
export interface PickerGroup {
    id: 'mine' | 'plugin' | 'system' | 'papi';
    items: Variable[];
}

/**
 * 选中变量时传给 caller 的 displayName（编辑器 placeholder 内出现的"短名"）：
 *
 * <ul>
 *   <li>user 变量：{@code user/<key>}（隐藏 wallId 部分，与文本中 {@code ${var:user/<key>}} 一致）</li>
 *   <li>其他：{@code <namespace>/<key>}</li>
 * </ul>
 */
export function displayName(v: Variable): string {
    if (v.namespace.startsWith('user:')) {
        return `user/${v.key}`;
    }
    return `${v.namespace}/${v.key}`;
}

/** 把 namespace 归类到 4 个组之一。 */
function groupOf(v: Variable): PickerGroup['id'] {
    if (v.namespace.startsWith('user:')) return 'mine';
    if (v.namespace === 'system') return 'system';
    if (v.namespace === 'papi') return 'papi';
    return 'plugin';
}

/**
 * 把 store 的所有变量按当前 wallId + keyword 分到 4 组。
 *
 * <p>分组规则：</p>
 * <ul>
 *   <li>👤 mine：namespace = {@code user:<wallId>}（wallId 非空时；空时该组空）</li>
 *   <li>📦 plugin：namespace 不属于 user/system/papi</li>
 *   <li>🌐 system：namespace = {@code system}</li>
 *   <li>🔌 papi：namespace = {@code papi}</li>
 * </ul>
 *
 * <p>搜索：keyword（lowercase）模糊匹配 {@link Variable#key} 或 {@link Variable#namespace} 任一。</p>
 *
 * <p>返回顺序固定为 mine → plugin → system → papi。空组保留（UI 用 v-if 决定是否渲染标题）。</p>
 */
export function buildGroups(
    variables: Iterable<Variable>,
    wallId: string | null,
    keyword: string,
): PickerGroup[] {
    const userNs = wallId ? `user:${wallId}` : null;
    const kw = keyword.trim().toLowerCase();
    const mine: Variable[] = [];
    const plugin: Variable[] = [];
    const system: Variable[] = [];
    const papi: Variable[] = [];

    for (const v of variables) {
        // 跨 wall 的 user 变量不显示（其他 wall 的私有变量）
        if (v.namespace.startsWith('user:') && userNs !== null && v.namespace !== userNs) continue;
        if (v.namespace.startsWith('user:') && userNs === null) continue;

        if (kw.length > 0) {
            const hay = `${v.namespace}/${v.key}`.toLowerCase();
            if (!hay.includes(kw)) continue;
        }

        switch (groupOf(v)) {
            case 'mine': mine.push(v); break;
            case 'plugin': plugin.push(v); break;
            case 'system': system.push(v); break;
            case 'papi': papi.push(v); break;
        }
    }

    return [
        { id: 'mine', items: mine },
        { id: 'plugin', items: plugin },
        { id: 'system', items: system },
        { id: 'papi', items: papi },
    ];
}

/**
 * 把分组 list flatten 成线性数组（用于 ↑↓ 键盘 activeIndex 切换）。
 * 顺序与 {@link buildGroups} 输出顺序一致；跳过空组的占位。
 */
export function flattenGroups(groups: PickerGroup[]): Variable[] {
    const out: Variable[] = [];
    for (const g of groups) {
        for (const v of g.items) out.push(v);
    }
    return out;
}

/**
 * 给定分组列表 + 组 idx + 组内 idx → 全局平铺 index（用于 activeIndex 高亮判定）。
 * 越界 / 找不到时返 -1。
 */
export function absoluteIndex(groups: PickerGroup[], groupIdx: number, innerIdx: number): number {
    if (groupIdx < 0 || groupIdx >= groups.length) return -1;
    let acc = 0;
    for (let i = 0; i < groupIdx; i += 1) acc += groups[i].items.length;
    if (innerIdx < 0 || innerIdx >= groups[groupIdx].items.length) return -1;
    return acc + innerIdx;
}

/** 全部组内变量总数（flat list 长度）。 */
export function totalCount(groups: PickerGroup[]): number {
    let n = 0;
    for (const g of groups) n += g.items.length;
    return n;
}

/**
 * 计算 ↑↓ 键盘移动后的新 activeIndex（带循环回绕）。
 * total = 0 时返 -1。
 */
export function nextActiveIndex(current: number, delta: 1 | -1, total: number): number {
    if (total <= 0) return -1;
    if (current < 0) return delta === 1 ? 0 : total - 1;
    return (current + delta + total) % total;
}
