/**
 * 0.7.1：从一条积木脚本规则里收集它"涉及的变量"（fullName 引用集合）。
 *
 * <p>用途：积木画布右下角的「本脚本变量实时预览」面板（{@link ScriptVariableWatch.vue}）—— 不用切到
 * 右侧变量面板，就能看当前选中规则用到了哪些变量 + 实时值。这里只负责<b>提取引用</b>（纯逻辑、无 DOM /
 * 无 store），实时值由面板组件读 VariableStore mirror 渲染。</p>
 *
 * <p><b>扫描范围</b>（递归全树，含 if 的 then/else 与 repeat 的 body —— 复用 {@link walk}，它已按
 * {@code NESTED_SEQ_KEYS} 下钻所有容器子序列）：</p>
 * <ul>
 *   <li><b>触发器</b>：{@code variableChange.fullName}；</li>
 *   <li><b>动作的变量名字段</b>：{@code setVariable / incrementVariable / scaleVariable /
 *       setRandomVariable} 的 {@code fullName}；</li>
 *   <li><b>文本里的 {@code ${var:X}}</b>：{@code setVariable.value} / {@code sendMessage.text} /
 *       友好积木「改文字」（{@code setElementProperties} kind=setText）的 {@code patch.text}——
 *       用与渲染期一致的 {@link VARIABLE_PATTERN} 抓占位符内 rawName。</li>
 * </ul>
 *
 * <p><b>fullName 形态</b>：本模块只抓"占位符里写的 rawName"（如 {@code user/X} / {@code server.time}），
 * <b>不</b>在这里注入 wallId —— 注入（{@code user/X} → {@code user:<wallId>/X}）由面板组件用
 * {@link resolveFullName} 做，与渲染期插值口径一致。这样本 helper 不依赖 wall 上下文、纯可测。
 * variableChange / setVariable 等<b>结构字段</b>里的 {@code fullName} 直接是 rawName 形态（积木 UI 存
 * {@code user/X} 这种"对外名"，与 {@code ${var:...}} 占位符同源），同样不在此注入。</p>
 *
 * <p>返回<b>去重 + 保持首次出现顺序</b>的 rawName 数组（用 Set 去重再回数组，稳定可测）。</p>
 */

import type { ScriptAction, ScriptRule, ScriptTrigger } from '@/types/protocol';
import { walk } from './blockTree';
import { VARIABLE_PATTERN } from '@/variable/interpolator';

/**
 * 从一段文本里抓出所有 {@code ${var:rawName}} 的 rawName（trim 后），追加进 {@code out}（去重）。
 * 复用渲染期同一条正则（{@link VARIABLE_PATTERN}）保证"画布提示的变量"与"真正会被替换的变量"一致。
 * 非字符串 / 空串 / 无占位符 → 无副作用。
 */
function collectFromText(text: unknown, out: Set<string>): void {
    if (typeof text !== 'string' || text.length === 0) return;
    if (text.indexOf('${var:') < 0) return;
    // 每次新建 RegExp 实例：VARIABLE_PATTERN 带 g 标志有 lastIndex 状态，共享会串话。
    const re = new RegExp(VARIABLE_PATTERN.source, 'g');
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
        const raw = m[1]?.trim();
        if (raw) out.add(raw);
        if (m[0].length === 0) re.lastIndex++; // 防御 0 长匹配死循环（正常占位符不会）
    }
}

/**
 * 0.7.2-F3：从条件文本（ConditionEvaluator 文法）抓出所有 {@code var("rawName")} 引用的 rawName。
 * 条件里的变量是函数调用形态（{@code var("user/x") > 0}），不是 {@code ${var:X}} 占位符，故单独一条正则。
 */
function collectFromCondition(condition: unknown, out: Set<string>): void {
    if (typeof condition !== 'string' || condition.indexOf('var(') < 0) return;
    const re = /var\(\s*["']([^"']+)["']\s*\)/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(condition)) !== null) {
        collectName(m[1], out);
    }
}

/** 把一个值当作"变量名字段"加入（trim 后非空才加）。 */
function collectName(name: unknown, out: Set<string>): void {
    if (typeof name === 'string') {
        const t = name.trim();
        if (t) out.add(t);
    }
}

/** 扫触发器：仅 variableChange 带 fullName。 */
function collectFromTrigger(trigger: ScriptTrigger, out: Set<string>): void {
    if (trigger.type === 'variableChange') collectName(trigger.fullName, out);
}

/** 扫单个动作的变量名字段 + 文本占位符。容器块（if/repeat）的子序列由 {@link walk} 负责下钻。 */
function collectFromAction(action: ScriptAction, out: Set<string>): void {
    switch (action.type) {
        case 'setVariable':
            collectName(action.fullName, out);
            collectFromText(action.value, out); // value 可含 ${var:X}
            break;
        case 'incrementVariable':
        case 'scaleVariable':
        case 'setRandomVariable':
            collectName(action.fullName, out);
            break;
        case 'sendMessage':
            collectFromText(action.text, out);
            break;
        case 'if':
        case 'waitUntil':
            // 0.7.2-F3：条件文本（var("X") 文法）里的变量引用也进预览。if 的 then/else 子序列由
            // walk 下钻；这里只扫 condition 字段本身。
            collectFromCondition((action as { condition?: string }).condition, out);
            break;
        case 'setElementProperties':
            // 友好积木「改文字」(kind=setText) 的目标文本可含 ${var:X}（其余 kind 的 patch 是数值 / 颜色，
            // 无变量占位符）。稳妥起见对 patch 里所有字符串值扫一遍——多扫无害（非占位符不产出）。
            if (action.patch && typeof action.patch === 'object') {
                for (const v of Object.values(action.patch)) collectFromText(v, out);
            }
            break;
        default:
            break;
    }
}

/**
 * 收集一条规则涉及的所有变量 rawName（去重 + 首次出现顺序）。
 *
 * @param rule 当前编辑的规则（{@code scriptEdit.workingCopy}）。
 * @returns rawName 数组（如 {@code ['user/红队比分', 'server.time']}）；规则无变量 → 空数组。
 *          rawName 是"对外占位符名"，注入 wallId 成内部 fullName 由调用方用 {@code resolveFullName} 做。
 */
export function extractReferencedVariables(rule: ScriptRule): string[] {
    const out = new Set<string>();
    collectFromTrigger(rule.trigger, out);
    // walk 已递归 if then/else + repeat body（NESTED_SEQ_KEYS），每个动作回调一次。
    walk(rule.actions, (action) => collectFromAction(action, out));
    return Array.from(out);
}
