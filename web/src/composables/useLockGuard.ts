import { computed, type ComputedRef } from 'vue';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useI18n } from '@/i18n';

/**
 * wall lock readonly 多入口 guard。
 *
 * <p>CLAUDE.md §lock-state 第 4 条规定"前端是 lock 的唯一执行者"，后端编辑 op 透明放行。
 * 但是 wall locked 时，前端如果漏了某个 mutation 入口（按钮 / 快捷键 / 拖入），
 * 用户能成功改 wall —— 因为后端 lock 不拦编辑 op。所以多入口失防 = 实质上 lock 失效。</p>
 *
 * <p>CanvasView 顶部已有一个 stage 内 overlay（pointer-events 吃掉所有 stage 内 click），
 * RightPanel 的编辑区用 `inert` 属性屏蔽（0.9.17 从 `pointer-events: none` 换过来——那个
 * 只挡鼠标，Tab 仍能聚焦进去改值并真的落库，还会连带把面板滚动一起吃掉）。但 LeftTools
 * 按钮 / CanvasZoomBar grid input / TemplateGallery apply / SVG 与工程导入 / 时间轴 dock /
 * 快捷键（Ctrl+Z 等）/ Paint Bucket 点击都是独立入口 —— 必须在每个发 op 的函数开头加一道
 * 防线。<b>漏一个入口 = lock 实质失效</b>，0.9.17 就在导入与时间轴两处补过缺口。</p>
 *
 * <p><b>语义约定</b>（与代码库其他点一致，与 CLAUDE.md §lock-state "owner 仅有解锁权限"对齐）：</p>
 * <ul>
 *   <li>lock 状态下任何人都不能编辑（owner 也要先按 TopBar 的 Unlock 才能改）；</li>
 *   <li>所以 {@code isReadonly = isLocked}（不区分 owner）；</li>
 *   <li>与 {@code project.canEdit = !isLocked} 等价 — 只是名字更贴近 UI 语义。</li>
 * </ul>
 *
 * <p><b>不包含</b>：variable.* / schedule.* / rail.* 等跨 wall 共享语义的 op 不应被 wall
 * lock 冻结 —— wall lock 是"视觉冻结"而非"业务暂停"。如需冻结请在调用方显式判断。</p>
 *
 * <p><b>典型用法</b>：</p>
 * <pre>{@code
 *   const guard = useLockGuard();
 *   function addText() {
 *       if (!guard.guardMutation(t.value.tools.addText)) return;
 *       ws.send('element.add', { ... });
 *   }
 *   // template 里按钮：
 *   <button :disabled="guard.readonly.value" @click="addText">...</button>
 * }</pre>
 */
export function useLockGuard(): {
    isLocked: ComputedRef<boolean>;
    isOwner: ComputedRef<boolean>;
    isReadonly: ComputedRef<boolean>;
    readonly: ComputedRef<boolean>;
    guardMutation: (actionName?: string) => boolean;
} {
    const project = useProjectStore();
    const net = useNetworkStore();
    const { t } = useI18n();

    const isLocked = computed(() => project.isLocked);
    const isOwner = computed(() => project.isOwner);
    /**
     * lock 状态下任何人都不能改 wall（owner 需先解锁）—— 与代码库现有路径
     * （useClipboard.paste / useCanvasUpload / IconLibrary.onPick / CanvasView.lockedOverlay）
     * 行为一致。
     */
    const isReadonly = computed(() => isLocked.value);
    /** 模板里 :disabled / :class binding 用的别名，语义同 isReadonly。 */
    const readonly = isReadonly;

    /**
     * mutation 入口"早 return"工具：
     * 1. 若画板未锁定 → 返 true（调用方继续走 ws.send）；
     * 2. 若已锁定 → push 一条 err log + 返 false，阻止 ws.send。
     *
     * <p>用 {@code net.pushLog('err', ...)} 复用既有日志抽屉通知机制（与 useClipboard.paste /
     * useCanvasUpload.flashError / onPaintBucketClick 三个已有路径同款）。</p>
     */
    function guardMutation(actionName?: string): boolean {
        if (!isReadonly.value) return true;
        const msg = actionName
            ? t.value.lockGuard.blockedWithAction(actionName)
            : t.value.lockGuard.blocked;
        net.pushLog('err', msg);
        // DEV-only console 警告，帮 future contributor 发现新入口忘了 guard
        if (typeof import.meta !== 'undefined' && (import.meta as { env?: { DEV?: boolean } }).env?.DEV) {
            // eslint-disable-next-line no-console
            console.warn(`[useLockGuard] mutation blocked (wall locked): ${actionName ?? '(unknown action)'}`);
        }
        return false;
    }

    return { isLocked, isOwner, isReadonly, readonly, guardMutation };
}
