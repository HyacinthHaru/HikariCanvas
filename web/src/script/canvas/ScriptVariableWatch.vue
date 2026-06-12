<script setup lang="ts">
/**
 * 0.7.1：积木画布右下角「本脚本变量实时预览」常驻面板。
 *
 * <p>不用切到右侧变量面板，就能在积木画布里看<b>当前选中规则</b>涉及哪些变量 + 它们的实时值。
 * 数据来源：{@code scriptEdit.workingCopy}（当前编辑规则的本地副本）经
 * {@link extractReferencedVariables} 抽出 rawName 列表 → {@link resolveFullName} 注入 wallId 成内部
 * fullName → 读 {@link useVariableStore} mirror 的 currentValue（响应 state.patch 实时更新）+
 * {@link useVariableAliasStore} 的别名（优先显示别名，与 chip 编辑器同口径）。</p>
 *
 * <p><b>常驻面板，非 popover</b>（记忆 reference_variablepicker_reuse 的教训）：absolute 浮在画布右下角，
 * 不挂 onClickOutside，点画布 / 拖积木都不会误关。只有用户点折叠按钮才收起；折叠态记 localStorage。
 * 未选规则 / 规则无变量 → 各自空状态文案。无 workingCopy 时面板仍显示（提示先选规则），不闪烁。</p>
 *
 * <p>挂载点：{@link BlockCanvas} 模板内（viewport 之外、与跟手浮层同级的画布 host 层），
 * absolute 定位不随 world transform 缩放/平移。配色走 Catppuccin token + 项目卡片样式。</p>
 */
import { computed, ref } from 'vue';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';
import { useVariableAliasStore } from '@/stores/variableAliases';
import { useI18n } from '@/i18n';
import { extractReferencedVariables } from '../model/extractVars';
import { resolveFullName, isStale, UNRESOLVED } from '@/variable/interpolator';
import { Eye, ChevronDown, ChevronUp } from 'lucide-vue-next';

const edit = useScriptEditStore();
const project = useProjectStore();
const variables = useVariableStore();
const aliases = useVariableAliasStore();
const { t } = useI18n();

/** localStorage 键：折叠态跨会话记忆（UI-only，不入 store）。 */
const COLLAPSE_KEY = 'hikari-canvas:script-var-watch-collapsed';

/** 折叠态（true = 只显示标题条）。初值读 localStorage（默认展开）。 */
const collapsed = ref<boolean>(readCollapsed());

function readCollapsed(): boolean {
    try {
        return localStorage.getItem(COLLAPSE_KEY) === '1';
    } catch {
        return false;
    }
}

function toggleCollapsed(): void {
    collapsed.value = !collapsed.value;
    try {
        localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0');
    } catch {
        /* private mode etc：忽略 */
    }
}

/** 当前编辑的规则（本地副本）；null = 未选规则。 */
const rule = computed(() => edit.workingCopy);

/**
 * 本规则涉及的变量行（每行：rawName / 内部 fullName / 显示名（优先别名）/ 实时值 / 是否缺失）。
 * 响应式依赖：rule（结构变化）+ variables.variables（值更新）+ aliases.aliases（别名更新）+
 * project.wallId（注入前缀）—— 任一变化即重算，面板实时刷新。
 */
interface WatchRow {
    raw: string;
    fullName: string;
    display: string;
    value: string;
    missing: boolean;
}

const rows = computed<WatchRow[]>(() => {
    const r = rule.value;
    if (!r) return [];
    const wallId = project.wallId;
    const now = Date.now();
    return extractReferencedVariables(r).map<WatchRow>((raw) => {
        const fullName = resolveFullName(raw, wallId);
        const v = variables.get(fullName);
        const alias = aliases.get(fullName);
        // 显示名：别名优先（稳定的用户命名，比 fullName 可读）；否则用对外 rawName。
        const display = alias ?? raw;
        let value: string;
        let missing = false;
        if (v) {
            const cur = v.currentValue;
            // 与渲染期一致：cached 非空且未过期才算"有值"，否则退默认值 / 未解析占位。
            if (cur != null && cur.length > 0 && !isStale(v.ttl, v.updatedAt, now)) value = cur;
            else if (v.defaultValue != null && v.defaultValue.length > 0) value = v.defaultValue;
            else value = UNRESOLVED;
        } else {
            // store 里查不到：可能是 provider 变量尚未推送 / user 变量还没建。
            value = UNRESOLVED;
            missing = true;
        }
        return { raw, fullName, display, value, missing };
    });
});

/** 是否有变量行（控制空状态 vs 列表）。 */
const hasVars = computed(() => rows.value.length > 0);
</script>

<template>
  <div class="hc-var-watch" :class="collapsed ? 'hc-var-watch-collapsed' : ''">
    <!-- 标题条（点折叠按钮收起 / 展开；常驻，不随画布交互关闭） -->
    <button type="button" class="hc-var-watch-head" @click="toggleCollapsed">
      <Eye class="size-3.5 hc-var-watch-eye" />
      <span class="hc-var-watch-title">{{ t.script.varWatch.title }}</span>
      <span v-if="hasVars" class="hc-var-watch-count">{{ rows.length }}</span>
      <component :is="collapsed ? ChevronUp : ChevronDown" class="size-3.5 hc-var-watch-chevron" />
    </button>

    <!-- 主体（展开时）：变量行 / 空状态 -->
    <div v-if="!collapsed" class="hc-var-watch-body">
      <!-- 未选规则 -->
      <div v-if="!rule" class="hc-var-watch-empty">{{ t.script.varWatch.noRule }}</div>
      <!-- 规则无变量 -->
      <div v-else-if="!hasVars" class="hc-var-watch-empty">{{ t.script.varWatch.noVars }}</div>
      <!-- 变量行 -->
      <ul v-else class="hc-var-watch-list">
        <li
          v-for="row in rows"
          :key="row.fullName"
          class="hc-var-watch-row"
          :class="row.missing ? 'hc-var-watch-row-missing' : ''"
          :title="row.fullName"
        >
          <span class="hc-var-watch-name">{{ row.display }}</span>
          <span class="hc-var-watch-eq">=</span>
          <span class="hc-var-watch-val">{{ row.value }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
/* 右下角常驻浮层（不随 world transform；与 BlockCanvas 跟手浮层同 host 层级）。 */
.hc-var-watch {
    position: absolute;
    right: 0.75rem;
    bottom: 0.75rem;
    width: 230px;
    max-width: calc(100% - 1.5rem);
    display: flex;
    flex-direction: column;
    border-radius: var(--radius-md, 10px);
    border: 1px solid var(--border);
    background: color-mix(in srgb, var(--card) 94%, transparent);
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.22);
    color: var(--card-foreground);
    /* 高于画布堆 / 吸附指示线之下；不挡跟手浮层（那是 fixed teleport 到 body）。 */
    z-index: 6;
    overflow: hidden;
    backdrop-filter: blur(2px);
}
.hc-var-watch-collapsed {
    width: auto;
}
.hc-var-watch-head {
    display: flex;
    align-items: center;
    gap: 0.375rem;
    width: 100%;
    padding: 0.375rem 0.5rem;
    background: transparent;
    color: var(--card-foreground);
    cursor: pointer;
    user-select: none;
}
.hc-var-watch-head:hover {
    background: var(--accent);
}
.hc-var-watch-eye {
    color: var(--ctp-mauve, var(--primary));
    flex-shrink: 0;
}
.hc-var-watch-title {
    font-size: 0.75rem;
    font-weight: 600;
    white-space: nowrap;
}
.hc-var-watch-count {
    font-size: 0.6875rem;
    font-weight: 600;
    padding: 0 0.3125rem;
    border-radius: var(--radius-full, 9999px);
    background: color-mix(in srgb, var(--ctp-mauve, var(--primary)) 22%, transparent);
    color: var(--foreground);
    line-height: 1.4;
}
.hc-var-watch-chevron {
    margin-left: auto;
    color: var(--muted-foreground);
    flex-shrink: 0;
}
.hc-var-watch-body {
    border-top: 1px solid var(--border);
    max-height: 260px;
    overflow-y: auto;
}
.hc-var-watch-empty {
    padding: 0.5rem 0.625rem;
    font-size: 0.6875rem;
    color: var(--muted-foreground);
    font-style: italic;
}
.hc-var-watch-list {
    list-style: none;
    margin: 0;
    padding: 0.25rem;
    display: flex;
    flex-direction: column;
    gap: 1px;
}
.hc-var-watch-row {
    display: flex;
    align-items: baseline;
    gap: 0.3125rem;
    padding: 0.1875rem 0.375rem;
    border-radius: var(--radius-sm, 5px);
    font-size: 0.75rem;
    line-height: 1.3;
}
.hc-var-watch-row:hover {
    background: var(--accent);
}
.hc-var-watch-name {
    color: var(--ctp-mauve, var(--primary));
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 55%;
    flex-shrink: 0;
}
.hc-var-watch-eq {
    color: var(--muted-foreground);
    flex-shrink: 0;
}
.hc-var-watch-val {
    color: var(--foreground);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
}
/* 缺失（store 查不到）：值用柔和橙提示"还没有这个变量 / 尚未推送"。 */
.hc-var-watch-row-missing .hc-var-watch-val {
    color: var(--ctp-peach, var(--muted-foreground));
    font-style: italic;
}
</style>
