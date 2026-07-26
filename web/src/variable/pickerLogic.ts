/**
 * VariablePicker 纯逻辑层。
 *
 * <p>把 picker 的 group 划分 / 搜索过滤 / activeIndex 平铺 / Enter onSelect 行为从
 * Vue 组件抽出，便于 vitest node 环境直接测（不需 jsdom + @vue/test-utils）。</p>
 *
 * <p>组件 {@code VariablePicker.vue} 是 thin wrapper：拿 store + wallId + keyword
 * → 调本模块 {@link buildGroups} / {@link mergeMetadata} 得分组 + flat index 模型 → 渲染 + 键盘 / 鼠标交互。</p>
 *
 * <p>Picker 不再只显示已 cached 的变量；通过
 * {@code GET /api/variable/list-all-namespaces} 拉到所有 Provider declared keys + 当前 wall 的
 * user 变量 metadata 后，合并 store cached value 得完整可用列表（即便 cached value 未来即时也显示
 * "—" 占位）。{@link mergeMetadata} 完成这一合并。</p>
 */
import type { Variable } from '@/types/variable';
import { resolveFullName } from '@/variable/interpolator';

/**
 * 一行变量在 store / 别名表里的<b>绝对</b> fullName（{@code namespace/key}，namespace 已含 wallId）。
 *
 * <p>为什么不能直接拼 {@code namespace/key}：metadata 端点下发的骨架行 namespace 是<b>相对</b>
 * 形态（{@code schedule} / {@code system}），而同一个变量在 store 里叫
 * {@code schedule:<wallId>/next_departure} / {@code system:<wallId>/wall.id}。别名表、变量面板、
 * 文本里的 chip 用的都是绝对形态；picker 若按相对形态存别名，存完看着像成功，chip 和面板却永远
 * 查不到。user 变量之所以没这个问题，是后端下发的 namespace 本来就带 wallId。</p>
 *
 * <p>归一化复用 {@link resolveFullName}（与 chip 同一个函数，口径天然一致）：先按
 * {@code namespace/key} 试，再按裸 key 试（{@code system} 下的 {@code wall.id}，用户写法就是
 * {@code ${var:wall.id}}）。两次都补不出 {@code namespace:wallId} 前缀，说明这个 namespace 本来
 * 就是全服一份（{@code system/server.time} / {@code scoreboard/…} / {@code userglobal/…}），
 * 字面形态即绝对形态。</p>
 *
 * @param wallId 当前 wall id；为空（无 wall 上下文）时一律退字面形态。
 */
export function absoluteFullName(v: Variable, wallId: string | null): string {
    const literal = `${v.namespace}/${v.key}`;
    // namespace 自带 :wallId（store 下发的 user:<wallId> 等）→ 已是绝对形态
    if (v.namespace.includes(':')) return literal;
    const byPair = resolveFullName(literal, wallId);
    if (byPair.startsWith(`${v.namespace}:`)) return byPair;
    const byKey = resolveFullName(v.key, wallId);
    if (byKey.startsWith(`${v.namespace}:`)) return byKey;
    return literal;
}

/**
 * 一组分类显示的变量。
 *
 * - {@link id}：稳定 id（{@code mine} / {@code plugin} / {@code system} / {@code papi}），i18n key 关联
 * - {@link items}：组内变量列表（已 filter 过 keyword）
 */
export interface PickerGroup {
    /** 新增 {@code myGlobal} / {@code othersGlobal} 区分 userglobal 我的 / 其他玩家创建的。 */
    id: 'mine' | 'myGlobal' | 'othersGlobal' | 'plugin' | 'system' | 'papi';
    items: Variable[];
}

// ---------- metadata 接入 ----------

/**
 * 一个 namespace 在 metadata response 中的描述（对应后端
 * {@code GET /api/variable/list-all-namespaces} JSON）。
 */
export interface NamespaceMetadata {
    namespace: string;
    displayName: string;
    /** 动态 namespace（如 scoreboard / papi）；keys 为空，编辑器应提示模板形态。 */
    dynamic: boolean;
    keys: DeclaredKeyMetadata[];
}

export interface DeclaredKeyMetadata {
    key: string;
    /** 与 {@link Variable.type} 一致；后端枚举名 STRING/NUMBER/BOOLEAN/COLOR。 */
    type: string;
    description?: string;
    /** 0 = 永久；>0 = TTL（ms）。 */
    ttlMs: number;
}

/** 已知 Variable['type'] 集合（运行时白名单 — declaredKeyToVariable 防 wild type 字段污染 store）。 */
const KNOWN_TYPES: ReadonlyArray<Variable['type']> = ['STRING', 'NUMBER', 'BOOLEAN', 'COLOR'];

/**
 * 把 metadata 描述的 declared key 转成 {@link Variable} 形态（供 buildGroups 复用）；
 * cached value 与 defaultValue 留空（{@code null}），UI 渲染时显示占位符 "—"。
 *
 * <p>非已知 type 字符串退化为 {@code STRING}（防后端未来加新 type 时前端旧版本崩溃）。</p>
 */
export function declaredKeyToVariable(
    namespace: string,
    k: DeclaredKeyMetadata,
): Variable {
    const type = (KNOWN_TYPES as ReadonlyArray<string>).includes(k.type)
        ? (k.type as Variable['type'])
        : 'STRING';
    return {
        namespace,
        key: k.key,
        type,
        defaultValue: null,
        currentValue: null,
        updatedAt: 0,
        ttl: k.ttlMs,
        source: null,
    };
}

/**
 * 合并 store cached 变量 + metadata 声明 keys。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>遍历 metadata 每个 namespace 的 declared keys → 构造 Variable 骨架（cached value 留 null）</li>
 *   <li>同 fullName（{@code namespace/key}）若 store 已有 cached → 覆盖骨架，让 currentValue 走 store 真值</li>
 *   <li>store 中存在但 metadata 不含的变量（如插件 push 出来但 declaredKeys 未列；scoreboard / papi 动态注册的具体 key）→ append 到末尾，保持显示</li>
 * </ul>
 *
 * <p><b>动态 namespace 的 declared 部分</b>：metadata 返 {@code dynamic: true} 时 keys 为空——
 * 不产生骨架；但已动态注册的具体 key 会从 store 那边走 append 路径出现在列表内（如玩家已引用过的
 * scoreboard.kill.Steve）。</p>
 *
 * @returns 合并后的 Variable 数组（顺序：metadata 顺序 + store-only 追加）
 */
export function mergeMetadata(
    storeVariables: Iterable<Variable>,
    metadata: NamespaceMetadata[],
    wallId: string | null = null,
): Variable[] {
    const byFullName = new Map<string, Variable>();
    for (const v of storeVariables) {
        byFullName.set(`${v.namespace}/${v.key}`, v);
    }
    const out: Variable[] = [];
    const seen = new Set<string>();
    for (const ns of metadata) {
        for (const k of ns.keys) {
            const skeleton = declaredKeyToVariable(ns.namespace, k);
            const fullName = `${ns.namespace}/${k.key}`;
            // 骨架行的 namespace 是裸的（schedule / system），同一个变量在 store 里带着
            // wallId（schedule:<wallId>）。两个形态都查一遍，否则列表里同一个变量会出现
            // 两条：一条显示"—"、一条有值，用户挑哪条全靠运气。
            const absolute = absoluteFullName(skeleton, wallId);
            const cached = byFullName.get(fullName) ?? byFullName.get(absolute);
            out.push(cached ?? skeleton);
            seen.add(fullName);
            seen.add(absolute);
        }
    }
    // store-only：metadata 没声明、但 store 有的（动态注册 + 老插件未实现 declaredKeys 兜底）
    for (const [fullName, v] of byFullName.entries()) {
        if (!seen.has(fullName)) out.push(v);
    }
    return out;
}

/**
 * 取 metadata 内特定 namespace 的 dynamic flag。给 UI 做 "动态注册" 标记用。
 * 找不到 namespace 时返 {@code false}。
 */
export function isDynamicNamespace(
    metadata: NamespaceMetadata[],
    namespace: string,
): boolean {
    for (const ns of metadata) {
        if (ns.namespace === namespace) return ns.dynamic;
    }
    return false;
}

/**
 * 选中变量时传给 caller 的 displayName（编辑器 placeholder 内出现的"短名"）：
 *
 * <ul>
 *   <li>user 变量：{@code user/<key>}（隐藏 wallId 部分，与文本中 {@code ${var:user/<key>}} 一致）</li>
 *   <li>userglobal：{@code userglobal/<key>}（保留前缀，文本写法一致）</li>
 *   <li>其他带 wallId 的 per-wall 变量（{@code schedule:<wallId>} / {@code system:<wallId>}）：
 *       去掉 wallId 写成可移植形态（{@code schedule/next_departure} / {@code wall.id}）</li>
 *   <li>其他：{@code <namespace>/<key>}</li>
 * </ul>
 *
 * <p><b>为什么要去 wallId</b>：写进文本的是这个短名，把 {@code w-abc} 焊进去之后，这份工程
 * 导出再套到别的画布上就永远解析不出值。去掉之后由渲染期的 {@link resolveFullName} 按当前
 * 画布现补——两边是同一个函数，所以这里只在"补回去能一字不差还原"时才敢缩写，还原不了的
 * namespace 保持原样（宁可不可移植，也不能解析不出来）。</p>
 *
 * @param wallId 当前 wall id；不传（或为空）时一律退回字面形态
 */
export function displayName(v: Variable, wallId: string | null = null): string {
    if (v.namespace.startsWith('user:')) {
        return `user/${v.key}`;
    }
    const literal = `${v.namespace}/${v.key}`;
    if (wallId && wallId.length > 0 && v.namespace.endsWith(`:${wallId}`)) {
        const bare = v.namespace.slice(0, -(wallId.length + 1));
        // 候选短名：先试 <namespace>/<key>（schedule/next_departure），
        // 再试裸 key（system 下的 wall.id，用户写法本来就是 ${var:wall.id}）
        for (const candidate of [`${bare}/${v.key}`, v.key]) {
            if (resolveFullName(candidate, wallId) === literal) return candidate;
        }
    }
    return literal;
}

/**
 * 把 namespace 归类到 6 个组之一（加 myGlobal / othersGlobal）。
 *
 * @param v       变量
 * @param selfUuid 当前玩家 UUID（用于判 userglobal 是不是自己创建的）；可空时默认走 othersGlobal
 */
function groupOf(v: Variable, selfUuid: string | null): PickerGroup['id'] {
    if (v.namespace.startsWith('user:')) return 'mine';
    if (v.namespace === 'userglobal') {
        // owner = self → myGlobal；其他 / 缺 owner 字段 → othersGlobal
        return v.ownerUuid && selfUuid && v.ownerUuid === selfUuid
            ? 'myGlobal' : 'othersGlobal';
    }
    // per-wall 变量在 store 里的 namespace 带 :<wallId>（system:w-abc / schedule:w-abc），
    // 分组按冒号前那截判——否则 wall.id 会掉进"由插件提供"组里，用户翻半天找不到。
    const bare = v.namespace.split(':')[0];
    if (bare === 'system') return 'system';
    if (bare === 'papi') return 'papi';
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
    aliases?: ReadonlyMap<string, string> | Record<string, string> | null,
    selfUuid: string | null = null,
): PickerGroup[] {
    const userNs = wallId ? `user:${wallId}` : null;
    const kw = keyword.trim().toLowerCase();
    const mine: Variable[] = [];
    const myGlobal: Variable[] = [];
    const othersGlobal: Variable[] = [];
    const plugin: Variable[] = [];
    const system: Variable[] = [];
    const papi: Variable[] = [];

    // 把 aliases（Map 或 plain Record）规范成 readonly fn，避免每行 lookup 重复 instanceof
    const aliasLookup: (fullName: string) => string | null =
        aliases == null
            ? () => null
            : aliases instanceof Map
                ? (fn) => (aliases as ReadonlyMap<string, string>).get(fn) ?? null
                : (fn) => (aliases as Record<string, string>)[fn] ?? null;

    for (const v of variables) {
        // 跨 wall 的 user 变量不显示（其他 wall 的私有变量）
        if (v.namespace.startsWith('user:') && userNs !== null && v.namespace !== userNs) continue;
        if (v.namespace.startsWith('user:') && userNs === null) continue;

        if (kw.length > 0) {
            const fullName = `${v.namespace}/${v.key}`;
            const hay = fullName.toLowerCase();
            // alias 也参与 keyword 命中——让"红队"也能搜到 user:w-abc/red_score
            const alias = aliasLookup(fullName);
            const aliasHay = alias ? alias.toLowerCase() : '';
            if (!hay.includes(kw) && !aliasHay.includes(kw)) continue;
        }

        switch (groupOf(v, selfUuid)) {
            case 'mine': mine.push(v); break;
            case 'myGlobal': myGlobal.push(v); break;
            case 'othersGlobal': othersGlobal.push(v); break;
            case 'plugin': plugin.push(v); break;
            case 'system': system.push(v); break;
            case 'papi': papi.push(v); break;
        }
    }

    // 顺序：mine → myGlobal → othersGlobal → plugin → system → papi
    return [
        { id: 'mine', items: mine },
        { id: 'myGlobal', items: myGlobal },
        { id: 'othersGlobal', items: othersGlobal },
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
