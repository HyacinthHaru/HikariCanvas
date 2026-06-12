/**
 * 0.7.0-P4-D1 scriptEditStore 单测。
 *
 * <p>编辑会话核心逻辑：working copy 深拷隔离 / 本地 undo-redo / dirty 标记 / debounce
 * auto-save / lock no-op / server-as-truth dirty 协调 / newRule 拿 server 发的 id /
 * deleteRule 当前规则 → closeEditing。</p>
 *
 * <p>用 vitest fake timers 控 debounce；mock {@code @/network/wsClient} 的 getWsClient，
 * sendScript* 方法默认 resolve；按需让 sendScriptCreate 模拟后端 state.patch（往 ScriptStore
 * upsert 新规则）以验证 newRule 拿 id 路径。</p>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import { useScriptEditStore } from '../scriptEdit';
import { useScriptStore } from '../scripts';
import { useProjectStore } from '../project';
import type { ScriptRule, ScriptAction } from '@/types/protocol';

// ---------- mock wsClient ----------

const sendScriptCreate = vi.fn<(rule: unknown) => Promise<void>>(() => Promise.resolve());
const sendScriptUpdate = vi.fn<(ruleId: string, rule: unknown) => Promise<void>>(() => Promise.resolve());
const sendScriptDelete = vi.fn<(ruleId: string) => Promise<void>>(() => Promise.resolve());
const sendScriptEnable = vi.fn<(ruleId: string, enabled: boolean) => Promise<void>>(() => Promise.resolve());

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptCreate,
        sendScriptUpdate,
        sendScriptDelete,
        sendScriptEnable,
    }),
}));

function makeRule(id: string, over: Partial<ScriptRule> = {}): ScriptRule {
    return {
        id,
        wallId: 'w-abc12345',
        enabled: true,
        name: `rule-${id}`,
        trigger: { type: 'wallReady' },
        actions: [{ type: 'log', message: 'hi' }],
        blockLayout: '{}',
        ...over,
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    vi.useFakeTimers();
    sendScriptCreate.mockReset();
    sendScriptCreate.mockResolvedValue(undefined);
    sendScriptUpdate.mockReset();
    sendScriptUpdate.mockResolvedValue(undefined);
    sendScriptDelete.mockReset();
    sendScriptDelete.mockResolvedValue(undefined);
    sendScriptEnable.mockReset();
    sendScriptEnable.mockResolvedValue(undefined);
});

afterEach(() => {
    vi.useRealTimers();
});

describe('scriptEditStore — selectRule / 深拷隔离', () => {
    it('selectRule 深拷进 workingCopy（改 workingCopy 不影响 server 镜像）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { name: '原名' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        expect(edit.selectedRuleId).toBe('sr-1');
        expect(edit.workingCopy?.name).toBe('原名');
        // 改 workingCopy.actions（深层）不应反映到 store
        edit.workingCopy!.actions.push({ type: 'log', message: 'mutated' });
        expect(scripts.get('sr-1')!.actions).toHaveLength(1);
    });

    it('selectRule 不存在 ruleId → no-op', () => {
        const edit = useScriptEditStore();
        edit.selectRule('sr-nope');
        expect(edit.selectedRuleId).toBe(null);
        expect(edit.workingCopy).toBe(null);
    });

    it('selectRule 清 undo/redo + dirty', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        scripts.upsert(makeRule('sr-2'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setName('改了');
        expect(edit.dirty).toBe(true);
        expect(edit.undoStack.length).toBe(1);
        edit.selectRule('sr-2');
        expect(edit.dirty).toBe(false);
        expect(edit.undoStack.length).toBe(0);
        expect(edit.redoStack.length).toBe(0);
    });
});

describe('scriptEditStore — 变更入口 + dirty + undo', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        return { scripts, edit };
    }

    it('setName 标脏 + push undo', () => {
        const { edit } = setup();
        edit.setName('新名');
        expect(edit.workingCopy?.name).toBe('新名');
        expect(edit.dirty).toBe(true);
        expect(edit.undoStack.length).toBe(1);
    });

    it('setActions 进 undo（D2 拖拽结果写这里）', () => {
        const { edit } = setup();
        const newActions: ScriptAction[] = [
            { type: 'wait', ms: 100 },
            { type: 'log', message: 'a' },
        ];
        edit.setActions(newActions);
        expect(edit.workingCopy?.actions).toEqual(newActions);
        expect(edit.dirty).toBe(true);
        expect(edit.undoStack.length).toBe(1);
    });

    it('setTrigger 替换整个触发器多态对象', () => {
        const { edit } = setup();
        edit.setTrigger({ type: 'timer', intervalSeconds: 30 });
        expect(edit.workingCopy?.trigger).toEqual({ type: 'timer', intervalSeconds: 30 });
        expect(edit.dirty).toBe(true);
    });

    it('setStackPos 改 blockLayout.stacks[ruleId]', () => {
        const { edit } = setup();
        edit.setStackPos('sr-1', 120, 240);
        const layout = JSON.parse(edit.workingCopy!.blockLayout);
        expect(layout.stacks['sr-1']).toEqual({ x: 120, y: 240 });
        expect(edit.dirty).toBe(true);
    });

    it('updateActionField 改顶层 action 字段（immutable 重建）', () => {
        const { edit } = setup();
        edit.updateActionField('actions/0', { message: '改了' } as Partial<ScriptAction>);
        const a0 = edit.workingCopy!.actions[0];
        expect(a0).toEqual({ type: 'log', message: '改了' });
        expect(edit.dirty).toBe(true);
        expect(edit.undoStack.length).toBe(1);
    });

    it('updateActionField 改嵌套 if 分支内的 action', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-if', {
            actions: [
                {
                    type: 'if', condition: 'true',
                    then: [{ type: 'log', message: 'old' }],
                    else: [],
                },
            ],
        }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-if');
        edit.updateActionField('actions/0/then/0', { message: 'new' } as Partial<ScriptAction>);
        const inner = (edit.workingCopy!.actions[0] as Extract<ScriptAction, { type: 'if' }>).then[0];
        expect(inner).toEqual({ type: 'log', message: 'new' });
    });

    it('updateActionField 改嵌套 if else 分支内的 action（回归保护：else 路径）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-if2', {
            actions: [
                {
                    type: 'if', condition: 'true',
                    then: [],
                    else: [{ type: 'sendMessage', text: '', channel: 'chat' }],
                },
            ],
        }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-if2');
        edit.updateActionField('actions/0/else/0', { text: 'hi' } as Partial<ScriptAction>);
        const inner = (edit.workingCopy!.actions[0] as Extract<ScriptAction, { type: 'if' }>).else[0];
        expect(inner).toEqual({ type: 'sendMessage', text: 'hi', channel: 'chat' });
    });

    // ---- 0.7.1 bug 修复：repeat body 里的积木编辑被静默丢弃（根因：scriptEdit 平行实现只认 if）----

    it('updateActionField 改 repeat body 内 incrementVariable 的 fullName（不被丢弃）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-rep', {
            actions: [
                {
                    type: 'repeat', count: 3,
                    body: [{ type: 'incrementVariable', fullName: 'user/score', delta: 1 }],
                },
            ],
        }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-rep');
        edit.updateActionField('actions/0/body/0', { fullName: 'user/gold' } as Partial<ScriptAction>);
        const inner = (edit.workingCopy!.actions[0] as Extract<ScriptAction, { type: 'repeat' }>).body[0];
        expect(inner).toEqual({ type: 'incrementVariable', fullName: 'user/gold', delta: 1 });
        expect(edit.dirty).toBe(true);
        expect(edit.undoStack.length).toBe(1);
    });

    it('updateActionField 改 repeat body 内 incrementVariable 的 delta（不被丢弃）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-rep2', {
            actions: [
                {
                    type: 'repeat', count: 5,
                    body: [{ type: 'incrementVariable', fullName: 'user/score', delta: 1 }],
                },
            ],
        }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-rep2');
        edit.updateActionField('actions/0/body/0', { delta: 10 } as Partial<ScriptAction>);
        const inner = (edit.workingCopy!.actions[0] as Extract<ScriptAction, { type: 'repeat' }>).body[0];
        expect(inner).toEqual({ type: 'incrementVariable', fullName: 'user/score', delta: 10 });
        expect(edit.dirty).toBe(true);
    });

    it('updateActionField 改 repeat body 内 sendMessage 的 text（不被丢弃）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-rep3', {
            actions: [
                {
                    type: 'repeat', count: 2,
                    body: [{ type: 'sendMessage', text: '', channel: 'chat' }],
                },
            ],
        }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-rep3');
        edit.updateActionField('actions/0/body/0', { text: '你好' } as Partial<ScriptAction>);
        const inner = (edit.workingCopy!.actions[0] as Extract<ScriptAction, { type: 'repeat' }>).body[0];
        expect(inner).toEqual({ type: 'sendMessage', text: '你好', channel: 'chat' });
        expect(edit.dirty).toBe(true);
    });

    it('updateActionField 改 repeat body 内 if 的 then 子项（深层嵌套 body → then）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-rep4', {
            actions: [
                {
                    type: 'repeat', count: 2,
                    body: [
                        {
                            type: 'if', condition: 'true',
                            then: [{ type: 'log', message: 'old' }],
                            else: [],
                        },
                    ],
                },
            ],
        }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-rep4');
        edit.updateActionField('actions/0/body/0/then/0', { message: 'new' } as Partial<ScriptAction>);
        const repeat = edit.workingCopy!.actions[0] as Extract<ScriptAction, { type: 'repeat' }>;
        const ifNode = repeat.body[0] as Extract<ScriptAction, { type: 'if' }>;
        expect(ifNode.then[0]).toEqual({ type: 'log', message: 'new' });
        expect(edit.dirty).toBe(true);
    });

    it('updateActionField path 非法 → no-op（不标脏）', () => {
        const { edit } = setup();
        edit.updateActionField('actions/9', { message: 'x' } as Partial<ScriptAction>);
        expect(edit.dirty).toBe(false);
        expect(edit.undoStack.length).toBe(0);
    });
});

describe('scriptEditStore — undo / redo', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { name: 'v0' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        return { edit };
    }

    it('undo 回退到上一态；redo 恢复', () => {
        const { edit } = setup();
        edit.setName('v1');
        edit.setName('v2');
        expect(edit.workingCopy?.name).toBe('v2');
        expect(edit.undoStack.length).toBe(2);
        edit.undo();
        expect(edit.workingCopy?.name).toBe('v1');
        edit.undo();
        expect(edit.workingCopy?.name).toBe('v0');
        edit.redo();
        expect(edit.workingCopy?.name).toBe('v1');
        edit.redo();
        expect(edit.workingCopy?.name).toBe('v2');
    });

    it('新变更清空 redo 栈（开新分支）', () => {
        const { edit } = setup();
        edit.setName('v1');
        edit.undo();
        expect(edit.redoStack.length).toBe(1);
        edit.setName('v1b');
        expect(edit.redoStack.length).toBe(0);
    });

    it('undo 栈空 → no-op', () => {
        const { edit } = setup();
        edit.undo();
        expect(edit.workingCopy?.name).toBe('v0');
    });

    it('undo 栈 cap 50（超出丢最旧）', () => {
        const { edit } = setup();
        for (let i = 1; i <= 60; i++) edit.setName(`v${i}`);
        expect(edit.undoStack.length).toBe(50);
    });
});

describe('scriptEditStore — debounce auto-save', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        return { edit };
    }

    it('多次改动 debounce 合并为一次 sendScriptUpdate', () => {
        const { edit } = setup();
        edit.setName('a');
        edit.setName('ab');
        edit.setName('abc');
        expect(sendScriptUpdate).not.toHaveBeenCalled();
        vi.advanceTimersByTime(800);
        expect(sendScriptUpdate).toHaveBeenCalledTimes(1);
        // 发的是去掉 id/wallId 的最终态
        const [ruleId, payload] = sendScriptUpdate.mock.calls[0];
        expect(ruleId).toBe('sr-1');
        expect((payload as { name: string }).name).toBe('abc');
        expect(payload).not.toHaveProperty('id');
        expect(payload).not.toHaveProperty('wallId');
    });

    it('save 成功后清 dirty', async () => {
        const { edit } = setup();
        edit.setName('x');
        vi.advanceTimersByTime(800);
        // doSave 乐观清脏
        expect(edit.dirty).toBe(false);
        await Promise.resolve();
        expect(sendScriptUpdate).toHaveBeenCalledTimes(1);
    });

    it('save 失败 → 标回脏（下次重试）', async () => {
        const { edit } = setup();
        sendScriptUpdate.mockRejectedValueOnce(new Error('boom'));
        edit.setName('x');
        vi.advanceTimersByTime(800);
        await Promise.resolve();
        await Promise.resolve();
        // 失败回调标回脏
        expect(edit.dirty).toBe(true);
    });

    it('flushSave 立即发（不等 debounce 窗）', () => {
        const { edit } = setup();
        edit.setName('x');
        expect(sendScriptUpdate).not.toHaveBeenCalled();
        edit.flushSave();
        expect(sendScriptUpdate).toHaveBeenCalledTimes(1);
    });

    it('flushSave 非脏 → 不发', () => {
        const { edit } = setup();
        edit.flushSave();
        expect(sendScriptUpdate).not.toHaveBeenCalled();
    });

    it('closeEditing 先 flush 再清', () => {
        const { edit } = setup();
        edit.setName('x');
        edit.closeEditing();
        expect(sendScriptUpdate).toHaveBeenCalledTimes(1);
        expect(edit.selectedRuleId).toBe(null);
        expect(edit.workingCopy).toBe(null);
    });

    it('切规则前 flush 上一条的待保存改动', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        scripts.upsert(makeRule('sr-2'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setName('改了-1');
        edit.selectRule('sr-2');
        // 切走时 flush 了 sr-1
        expect(sendScriptUpdate).toHaveBeenCalledTimes(1);
        expect(sendScriptUpdate.mock.calls[0][0]).toBe('sr-1');
    });
});

describe('scriptEditStore — setEnabled（即时，不进 debounce）', () => {
    it('setEnabled 即时 sendScriptEnable + 即时更新 workingCopy', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { enabled: true }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setEnabled(false);
        expect(edit.workingCopy?.enabled).toBe(false);
        expect(sendScriptEnable).toHaveBeenCalledWith('sr-1', false);
        // enable 不走 debounce save 队列
        expect(sendScriptUpdate).not.toHaveBeenCalled();
        // 不标脏（enable 单独走 server）
        expect(edit.dirty).toBe(false);
    });

    it('setEnabled 值未变 → no-op', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { enabled: true }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setEnabled(true);
        expect(sendScriptEnable).not.toHaveBeenCalled();
    });
});

describe('scriptEditStore — lock 守卫（K-UI-12）', () => {
    function lockedSetup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { name: 'v0' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1'); // 选规则本身不受 lock 限制（只读查看）
        const project = useProjectStore();
        // 模拟锁定：lockedAt 非 null → isLocked
        project.setWallMeta('w-abc12345', null, Date.now(), 'owner', 'owner');
        return { edit, project };
    }

    it('lock 时所有变更入口 no-op', () => {
        const { edit } = lockedSetup();
        edit.setName('改');
        edit.setActions([{ type: 'wait', ms: 100 }]);
        edit.setTrigger({ type: 'timer', intervalSeconds: 5 });
        edit.setStackPos('sr-1', 1, 1);
        edit.updateActionField('actions/0', { message: 'x' } as Partial<ScriptAction>);
        edit.setEnabled(false);
        edit.undo();
        edit.redo();
        expect(edit.workingCopy?.name).toBe('v0');
        expect(edit.dirty).toBe(false);
        expect(edit.undoStack.length).toBe(0);
        expect(sendScriptUpdate).not.toHaveBeenCalled();
        expect(sendScriptEnable).not.toHaveBeenCalled();
    });

    it('lock 时 newRule no-op 返 null', async () => {
        const { project } = lockedSetup();
        void project;
        const edit = useScriptEditStore();
        const id = await edit.newRule();
        expect(id).toBe(null);
        expect(sendScriptCreate).not.toHaveBeenCalled();
    });

    it('lock 时 deleteRule no-op', () => {
        const { edit } = lockedSetup();
        edit.deleteRule('sr-1');
        expect(sendScriptDelete).not.toHaveBeenCalled();
    });

    it('lock 时 doSave 不发（即便 dirty 被外部置真也守一手）', () => {
        const { edit } = lockedSetup();
        // 直接 flush（doSave 内 isLocked 守卫）
        edit.flushSave();
        expect(sendScriptUpdate).not.toHaveBeenCalled();
    });
});

describe('scriptEditStore — server-as-truth dirty 协调', () => {
    it('非脏时 server 回声刷新 workingCopy', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { name: 'v0' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        expect(edit.dirty).toBe(false);
        // 模拟 server 推回改后的规则
        scripts.upsert(makeRule('sr-1', { name: 'server改了' }));
        await nextTick();
        expect(edit.workingCopy?.name).toBe('server改了');
    });

    it('脏时 server 回声不覆盖本地 workingCopy', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { name: 'v0' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setName('本地改了'); // 标脏
        expect(edit.dirty).toBe(true);
        // server 推回别的值
        scripts.upsert(makeRule('sr-1', { name: 'server值' }));
        await nextTick();
        // 脏 → 保留本地
        expect(edit.workingCopy?.name).toBe('本地改了');
    });

    it('当前编辑规则被 server 删除 → closeEditing', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        scripts.removeRule('sr-1');
        await nextTick();
        expect(edit.selectedRuleId).toBe(null);
        expect(edit.workingCopy).toBe(null);
    });

    // ---- P5 实测修复（根因 3）：拖拽中冻结整树替换 ----

    it('dragging=true 时 server 回声不替换 workingCopy（防 v-for 重建打断 capture）', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { name: 'v0' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        expect(edit.dirty).toBe(false); // 非脏：正常情况下回声会替换
        // 进入拖拽：冻结整树替换
        edit.setDragging(true);
        expect(edit.dragging).toBe(true);
        scripts.upsert(makeRule('sr-1', { name: 'server拖拽中改了' }));
        await nextTick();
        // 拖拽中：即便非脏也保留本地（不被整树替换）
        expect(edit.workingCopy?.name).toBe('v0');
    });

    it('dragging=false 后恢复 server-as-truth（回声重新替换 workingCopy）', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1', { name: 'v0' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setDragging(true);
        scripts.upsert(makeRule('sr-1', { name: '拖拽中(被冻结)' }));
        await nextTick();
        expect(edit.workingCopy?.name).toBe('v0'); // 冻结
        // 松手：恢复 server-as-truth
        edit.setDragging(false);
        expect(edit.dragging).toBe(false);
        scripts.upsert(makeRule('sr-1', { name: '松手后server权威' }));
        await nextTick();
        expect(edit.workingCopy?.name).toBe('松手后server权威');
    });

    it('dragging=true 但规则被 server 删除 → 仍 closeEditing（删不受 dragging 守卫）', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setDragging(true);
        scripts.removeRule('sr-1');
        await nextTick();
        // 规则真没了：退出编辑（松手时 useBlockDrag 取 workingCopy=null 会安全早退）
        expect(edit.selectedRuleId).toBe(null);
        expect(edit.workingCopy).toBe(null);
    });

    it('wall 切换（wallId 变）→ closeEditing', async () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        const project = useProjectStore();
        project.setWallMeta('w-aaa', null, null, null, null);
        edit.selectRule('sr-1');
        // 切到另一面墙
        project.setWallMeta('w-bbb', null, null, null, null);
        await nextTick();
        expect(edit.selectedRuleId).toBe(null);
        expect(edit.workingCopy).toBe(null);
    });
});

describe('scriptEditStore — newRule 拿 server 发的 id', () => {
    it('newRule 默认形态 + patch 先于 ack 到达时拿到 id', async () => {
        const scripts = useScriptStore();
        const edit = useScriptEditStore();
        // 模拟真实顺序：后端先 apply patch（往 store upsert 新规则）再 ack。
        sendScriptCreate.mockImplementationOnce((rule) => {
            // 校验默认形态
            const r = rule as Omit<ScriptRule, 'id' | 'wallId'>;
            expect(r.enabled).toBe(true);
            expect(r.trigger).toEqual({ type: 'wallReady' });
            // 草稿必须带一个默认 log 动作（后端拒空 actions）——空 message 合法过校验。
            expect(r.actions).toEqual([{ type: 'log', message: '' }]);
            // 模拟 server state.patch：新规则落 store
            scripts.upsert(makeRule('sr-NEW', { name: r.name }));
            return Promise.resolve();
        });
        const promise = edit.newRule('我的新规则');
        await vi.runAllTimersAsync();
        const id = await promise;
        expect(id).toBe('sr-NEW');
        // 拿到 id 后自动进入编辑
        expect(edit.selectedRuleId).toBe('sr-NEW');
        expect(edit.workingCopy?.name).toBe('我的新规则');
    });

    it('newRule patch 晚于 ack 到达时经 order watch 拿到 id', async () => {
        const scripts = useScriptStore();
        const edit = useScriptEditStore();
        // ack resolve 时 store 还没有新规则（patch 还没到）
        sendScriptCreate.mockResolvedValueOnce(undefined);
        const promise = edit.newRule();
        // 让 ack microtask 跑（此时 findNewRuleId 命不中，进 order watch 等待）
        await Promise.resolve();
        await Promise.resolve();
        // 模拟 patch 稍后到达
        scripts.upsert(makeRule('sr-LATE'));
        await vi.runAllTimersAsync();
        const id = await promise;
        expect(id).toBe('sr-LATE');
        expect(edit.selectedRuleId).toBe('sr-LATE');
    });

    it('newRule send 失败 → 返 null 不进编辑', async () => {
        const edit = useScriptEditStore();
        sendScriptCreate.mockRejectedValueOnce(new Error('send_failed'));
        const promise = edit.newRule();
        await vi.runAllTimersAsync();
        const id = await promise;
        expect(id).toBe(null);
        expect(edit.selectedRuleId).toBe(null);
    });

    it('newRule patch 始终不到（超时）→ 返 null', async () => {
        const edit = useScriptEditStore();
        sendScriptCreate.mockResolvedValueOnce(undefined); // ack 成功但永不 upsert
        const promise = edit.newRule();
        await vi.runAllTimersAsync(); // 推进到超时
        const id = await promise;
        expect(id).toBe(null);
    });
});

describe('scriptEditStore — deleteRule', () => {
    it('deleteRule 发 sendScriptDelete', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        edit.deleteRule('sr-1');
        expect(sendScriptDelete).toHaveBeenCalledWith('sr-1');
    });

    it('deleteRule 当前编辑的规则 → closeEditing（不把 workingCopy 写回）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setName('改了'); // 标脏 + 排了 save
        edit.deleteRule('sr-1');
        // 删除当前规则：清编辑态，且不发 update（取消了 pending save）
        expect(edit.selectedRuleId).toBe(null);
        expect(edit.workingCopy).toBe(null);
        expect(sendScriptDelete).toHaveBeenCalledWith('sr-1');
        // 关键：删除不能把缓存写回（规则要没了）
        vi.advanceTimersByTime(800);
        expect(sendScriptUpdate).not.toHaveBeenCalled();
    });

    it('deleteRule 非当前编辑的规则 → 不动编辑态', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        scripts.upsert(makeRule('sr-2'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.deleteRule('sr-2');
        expect(edit.selectedRuleId).toBe('sr-1');
        expect(sendScriptDelete).toHaveBeenCalledWith('sr-2');
    });
});

// ---------- 0.7.0-P5-H：validationErrors 阻止 save（K-UI-9）----------

describe('scriptEditStore — validationErrors 预校验阻止 save', () => {
    function setup() {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        return { edit };
    }

    it('合法规则 → validationErrors 空', () => {
        const { edit } = setup();
        expect(edit.validationErrors).toEqual([]);
    });

    it('改成非法（名称空）→ validationErrors 非空 + 不 send（保留 dirty）', () => {
        const { edit } = setup();
        edit.setName('   '); // 名称空 = 非法
        expect(edit.validationErrors.length).toBeGreaterThan(0);
        expect(edit.dirty).toBe(true);
        // debounce 到点也不发（有校验错误）
        vi.advanceTimersByTime(800);
        expect(sendScriptUpdate).not.toHaveBeenCalled();
        // 仍脏（等用户修）
        expect(edit.dirty).toBe(true);
    });

    it('改成非法动作（wait 越界）→ 不 send', () => {
        const { edit } = setup();
        edit.setActions([{ type: 'wait', ms: 1 } as ScriptAction]);
        expect(edit.validationErrors.some((e) => e.message.includes('等待时长'))).toBe(true);
        vi.advanceTimersByTime(800);
        expect(sendScriptUpdate).not.toHaveBeenCalled();
    });

    it('修好后再次 schedule → 正常 send', () => {
        const { edit } = setup();
        edit.setName(''); // 先非法
        vi.advanceTimersByTime(800);
        expect(sendScriptUpdate).not.toHaveBeenCalled();
        // 修好名称 → 合法
        edit.setName('好名字');
        expect(edit.validationErrors).toEqual([]);
        vi.advanceTimersByTime(800);
        expect(sendScriptUpdate).toHaveBeenCalledTimes(1);
    });

    it('非法时 flushSave 也不发', () => {
        const { edit } = setup();
        edit.setActions([]); // 动作列表空 = 非法
        expect(edit.validationErrors.length).toBeGreaterThan(0);
        edit.flushSave();
        expect(sendScriptUpdate).not.toHaveBeenCalled();
    });
});

describe('scriptEditStore — activeElementBinding（0.7.1-P3 波2 T4）', () => {
    it('默认 null', () => {
        const edit = useScriptEditStore();
        expect(edit.activeElementBinding).toBe(null);
    });

    it('setActiveElementBinding 设值 / 清空', () => {
        const edit = useScriptEditStore();
        edit.setActiveElementBinding('actions/0');
        expect(edit.activeElementBinding).toBe('actions/0');
        edit.setActiveElementBinding(null);
        expect(edit.activeElementBinding).toBe(null);
    });

    it('clearActiveElementBinding 清空', () => {
        const edit = useScriptEditStore();
        edit.setActiveElementBinding('actions/2/then/0');
        expect(edit.activeElementBinding).toBe('actions/2/then/0');
        edit.clearActiveElementBinding();
        expect(edit.activeElementBinding).toBe(null);
    });

    it('selectRule 切规则时清 null（避免把上一规则的元素字段绑定带过去）', () => {
        const scripts = useScriptStore();
        scripts.upsert(makeRule('sr-1'));
        scripts.upsert(makeRule('sr-2'));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setActiveElementBinding('actions/0');
        expect(edit.activeElementBinding).toBe('actions/0');
        edit.selectRule('sr-2');
        expect(edit.activeElementBinding).toBe(null);
    });
});
