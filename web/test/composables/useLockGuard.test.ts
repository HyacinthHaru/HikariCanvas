/**
 * @vitest-environment happy-dom
 *
 * 2026-05-25 ultrareview #8：useLockGuard 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>未锁定：guardMutation 返 true、不写 log</li>
 *   <li>已锁定：guardMutation 返 false、写一条 err log（带 / 不带 actionName）</li>
 *   <li>readonly / isReadonly computed 跟随 lockedAt 变化</li>
 *   <li>isOwner 跟随 selfUuid / ownerUuid 变化（但不影响 isReadonly —— 设计上 owner 也要解锁才能编辑）</li>
 *   <li>guardMutation 多次调用每次都写一条 log（不去重）</li>
 * </ul>
 *
 * <p>需要 happy-dom：composable 内部 useI18n 触发 document.documentElement.lang 写入。</p>
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { createApp, defineComponent, h } from 'vue';
import { createPinia, setActivePinia } from 'pinia';
import { useLockGuard } from '@/composables/useLockGuard';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';

/**
 * 把 useLockGuard mount 在一个最小 Vue app 里，让 reactive computed 真正在
 * Pinia + Vue 上下文内运转。返 composable 句柄 + store handles。
 */
function mountGuard() {
    let guard: ReturnType<typeof useLockGuard> | null = null;
    let projectRef: ReturnType<typeof useProjectStore> | null = null;
    let netRef: ReturnType<typeof useNetworkStore> | null = null;
    const Comp = defineComponent({
        setup() {
            guard = useLockGuard();
            projectRef = useProjectStore();
            netRef = useNetworkStore();
            return () => h('div');
        },
    });
    const container = document.createElement('div');
    const app = createApp(Comp);
    app.mount(container);
    return {
        guard: guard!,
        project: projectRef!,
        net: netRef!,
        unmount: () => app.unmount(),
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
});

describe('useLockGuard', () => {
    it('未锁定时 guardMutation 返 true 且不写 log', () => {
        const { guard, net, unmount } = mountGuard();
        const logsBefore = net.logs.length;
        expect(guard.isLocked.value).toBe(false);
        expect(guard.isReadonly.value).toBe(false);
        expect(guard.readonly.value).toBe(false);
        const ok = guard.guardMutation('添加文本');
        expect(ok).toBe(true);
        expect(net.logs.length).toBe(logsBefore);
        unmount();
    });

    it('已锁定时 guardMutation 返 false 且写一条 err log（带 actionName）', () => {
        const { guard, project, net, unmount } = mountGuard();
        project.setWallMeta('w-1', null, Date.now(), 'owner-uuid', 'self-uuid');
        const logsBefore = net.logs.length;
        expect(guard.isLocked.value).toBe(true);
        expect(guard.isReadonly.value).toBe(true);
        const ok = guard.guardMutation('添加文本');
        expect(ok).toBe(false);
        expect(net.logs.length).toBe(logsBefore + 1);
        const last = net.logs[net.logs.length - 1];
        expect(last.level).toBe('err');
        expect(last.text).toContain('添加文本');
        unmount();
    });

    it('已锁定且不传 actionName 时也写一条 err log（fallback 通用文案）', () => {
        const { guard, project, net, unmount } = mountGuard();
        project.setWallMeta('w-1', null, Date.now(), 'owner-uuid', 'other-uuid');
        const logsBefore = net.logs.length;
        const ok = guard.guardMutation();
        expect(ok).toBe(false);
        expect(net.logs.length).toBe(logsBefore + 1);
        expect(net.logs[net.logs.length - 1].level).toBe('err');
        unmount();
    });

    it('lockedAt 变化时 readonly 反应（响应式）', () => {
        const { guard, project, unmount } = mountGuard();
        expect(guard.readonly.value).toBe(false);
        project.setWallMeta('w-1', null, 12345, 'owner-uuid', 'self-uuid');
        expect(guard.readonly.value).toBe(true);
        project.setWallMeta('w-1', null, null, 'owner-uuid', 'self-uuid');
        expect(guard.readonly.value).toBe(false);
        unmount();
    });

    it('isOwner 跟随 selfUuid / ownerUuid 但不影响 isReadonly', () => {
        // 设计意图：lock 状态下 owner 也要先解锁；这与 CanvasView overlay / RightPanel
        // hc-readonly-panel 现有行为一致——owner 看到 "click Unlock to keep editing" 提示。
        const { guard, project, unmount } = mountGuard();
        project.setWallMeta('w-1', null, Date.now(), 'same-uuid', 'same-uuid');
        expect(guard.isOwner.value).toBe(true);
        expect(guard.isLocked.value).toBe(true);
        expect(guard.isReadonly.value).toBe(true);  // owner 也被 guard 挡（owner 必须先解锁）
        unmount();
    });

    it('多次调用 guardMutation 都写 log（不去重）', () => {
        const { guard, project, net, unmount } = mountGuard();
        project.setWallMeta('w-1', null, Date.now(), 'a', 'b');
        const logsBefore = net.logs.length;
        guard.guardMutation('A');
        guard.guardMutation('B');
        guard.guardMutation('C');
        expect(net.logs.length).toBe(logsBefore + 3);
        unmount();
    });

    it('guard 返回的 readonly 与 isReadonly 是同一个 computed', () => {
        const { guard, unmount } = mountGuard();
        // 同一引用：方便组件 :disabled="guard.readonly.value" 与 v-if="guard.isReadonly.value" 不歧义
        expect(guard.readonly).toBe(guard.isReadonly);
        unmount();
    });
});
