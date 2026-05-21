/**
 * 变量系统前端类型（0.4.0-P1-D，对齐 {@code docs/dynamic-data.md §2 / §3} +
 * 后端 P1-A `moe.hikari.canvas.variable.Variable` record）。
 *
 * <p>核心 fullName 命名约定：</p>
 * <ul>
 *   <li>用户变量内部 namespace = {@code user:<wallId>}（冒号分隔 wallId），fullName 形如
 *     {@code user:w-3a17b2c1/红队比分}。这是后端 P1-A 固化的隔离方案，避免不同 wall 的
 *     user 变量名相撞。前端常用 {@link makeUserFullName} 拼。</li>
 *   <li>插件 / 系统 / PAPI 变量 namespace = {@code <pluginName>} / {@code system} /
 *     {@code papi}，fullName 用 {@link makeFullName} 直拼。</li>
 *   <li>{@code variable.create} op 的 {@code name} 字段**不含** {@code user:<wallId>/} 前缀，
 *     由后端注入。前端列表 / Picker 展示 fullName 时把 {@code user:<wallId>/} 段单独识别为
 *     "我的变量" 分组。</li>
 * </ul>
 *
 * <p>state.patch path 编码：variables 走 {@code /variables/<encoded>/...} 路径，
 * {@code encoded} 是 fullName 用 RFC 6901 {@code /} → {@code ~1} 转义后的字符串。前端
 * wsClient 解析时反向 unescape。</p>
 */

/** 变量值类型 hint；与后端 `VarType` enum 一一对应。 */
export type VarType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'COLOR';

/**
 * Variable 完整记录（state.patch add value / list 端点返回的形态）。
 *
 * - {@link namespace}：{@code user:<wallId>} / {@code userglobal}（0.4.3） / {@code system} /
 *   插件名 / {@code papi}
 * - {@link ttl}：毫秒；0 = 永久；>0 = TTL（最小 100ms 由后端校验）
 * - {@link source}：human-readable 来源；用户手动改 = {@code "manual"}；插件 push = 插件名；
 *   系统 provider = {@code "system"}；PAPI 桥接 = {@code "papi"}
 * - {@link updatedAt}：epoch ms
 * - {@link ownerUuid} / {@link ownerName}（0.4.3）：仅 {@code userglobal} namespace 有；其他 null
 */
export interface Variable {
    namespace: string;
    key: string;
    type: VarType;
    defaultValue: string | null;
    currentValue: string | null;
    updatedAt: number;
    ttl: number;
    source: string | null;
    ownerUuid?: string | null;
    ownerName?: string | null;
}

/** `variable.update` op 的 patch 字段；type / defaultValue 任一可省。 */
export interface VariablePatch {
    type?: VarType;
    defaultValue?: string | null;
}

/** Plugin Push API 批量 update 形态（P4 用，仅 ts 镜像供后续 typing 一致）。 */
export interface VariableUpdate {
    value: string;
    ttl?: number;
}

// ---------- fullName helpers ----------

/**
 * 给当前 wall 的 user 变量拼 fullName。例如
 * {@code makeUserFullName('w-3a17b2c1', '红队比分') → 'user:w-3a17b2c1/红队比分'}。
 */
export function makeUserFullName(wallId: string, key: string): string {
    return `user:${wallId}/${key}`;
}

/** 一般 fullName 拼接：{@code <namespace>/<key>}（不做转义）。 */
export function makeFullName(namespace: string, key: string): string {
    return `${namespace}/${key}`;
}

/**
 * 反向拆 fullName → namespace + key（按**首个** {@code /} 分割）。
 * 例如 {@code 'user:w-3a17b2c1/红队比分'} → {@code namespace: 'user:w-3a17b2c1'}, {@code key: '红队比分'}。
 * 无 {@code /} 时 namespace 为空、key 为原串。
 */
export function parseFullName(fullName: string): { namespace: string; key: string } {
    const idx = fullName.indexOf('/');
    if (idx < 0) return { namespace: '', key: fullName };
    return { namespace: fullName.substring(0, idx), key: fullName.substring(idx + 1) };
}

/** 判 namespace 是否为某 wall 的 user 变量（前缀 {@code user:}）。 */
export function isUserNamespace(namespace: string): boolean {
    return namespace.startsWith('user:');
}

/** 0.4.3：判 namespace 是否为全局用户变量（{@code userglobal}）。 */
export const USERGLOBAL_NAMESPACE = 'userglobal';
export function isUserGlobalNamespace(namespace: string): boolean {
    return namespace === USERGLOBAL_NAMESPACE;
}

/** 0.4.3：拼全局用户变量 fullName，例如 {@code 'userglobal/red_score'}。 */
export function makeUserGlobalFullName(key: string): string {
    return `${USERGLOBAL_NAMESPACE}/${key}`;
}
