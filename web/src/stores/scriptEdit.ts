import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import type { ScriptRule, ScriptTrigger, ScriptAction } from '@/types/protocol';
import { useScriptStore } from './scripts';
import { useProjectStore } from './project';
import { useNetworkStore } from './network';
import { getWsClient } from '@/network/wsClient';
import { getAt, parsePath, removeAt, replaceAt } from '@/script/model/blockTree';
import { parseBlockLayout, stringifyBlockLayout } from '@/script/model/serialize';
import { validateRule, type ValidationError } from '@/script/model/validator';
import { makeDefaultAction } from '@/script/model/blockDefs';

/**
 * 0.7.0-P4-D1：积木编辑会话 store（决策 K-UI-11 / K-UI-12）。
 *
 * <p><b>心智 = 「本地改 working copy + 自动保存」</b>，没有"保存按钮"。打开一条规则 →
 * 从 {@link useScriptStore} 深拷一份 {@code workingCopy}（与 server 镜像隔离），之后拖拽 /
 * 改参 / 增删块全部改 working copy，debounce 800ms 自动 {@code sendScriptUpdate} 回写
 * server。本地 undo 栈（Ctrl+Z/Y，cap 50）只对 working copy 的结构 / 参数变更生效。</p>
 *
 * <p><b>server-as-truth 协调</b>：server 经 state.patch 把改后的规则推回 ScriptStore；本 store
 * watch {@code scripts.get(selectedRuleId)}——若本地<b>非脏</b>（无未保存改动）则用 server 版
 * 刷新 workingCopy（回显别人的改动 / 自己 save 成功后的权威态）；若<b>脏</b>则保留本地不覆盖
 * （用户正在编辑，server 回声不能踩掉手上的活）。wall 切换（scripts.reset）→ {@link closeEditing}。</p>
 *
 * <p><b>lock 守卫（K-UI-12）</b>：{@link useProjectStore} 的 {@code isLocked} 为真时，
 * 所有变更入口 + newRule / deleteRule 直接 no-op——锁定的墙只读，不发任何写 op。</p>
 *
 * <p>D1 只建编辑模型 + 接 overlay 的新建 / 删除 / 选规则；拖拽（算出新 actions 树后调
 * {@link setActions}）留 D2。</p>
 */

/** undo / redo 栈容量（K-UI-11：cap 50）。 */
const UNDO_CAP = 50;
/** 自动保存防抖窗口（K-UI-11：800ms）。 */
const SAVE_DEBOUNCE_MS = 800;
/** newRule 等待 server 回 patch（新规则落 store）的超时——超时仍拿不到 id 则放弃选中。 */
const NEW_RULE_PATCH_TIMEOUT_MS = 5000;
/** 默认新规则名（后端 NAME_MAX=64，留空也合法但给个有意义的名）。 */
const DEFAULT_RULE_NAME = '新规则';

/** 把带 id/wallId 的 ScriptRule 剥成 create/update payload 形态（server 权威这两字段）。 */
function stripIdWall(rule: ScriptRule): Omit<ScriptRule, 'id' | 'wallId'> {
    const { id: _id, wallId: _wallId, ...rest } = rule;
    void _id;
    void _wallId;
    return rest;
}

/**
 * 深拷一份 ScriptRule（结构隔离，改 workingCopy 不触及 server 镜像）。
 *
 * <p>用 JSON 往返而非 {@code structuredClone}：本函数的入参可能是 Vue 响应式 proxy
 * （server-as-truth watch 回调里 {@code scripts.get(...)} 返回的是 reactive 包装），
 * structuredClone 不能克隆 proxy（抛 DataCloneError）。ScriptRule 是纯 JSON 形态
 * （无函数 / Date / undefined 关键字段），JSON 往返完全胜任且对 proxy 安全（读值即可）。</p>
 */
function deepCloneRule(rule: ScriptRule): ScriptRule {
    return JSON.parse(JSON.stringify(rule)) as ScriptRule;
}

export const useScriptEditStore = defineStore('scriptEdit', () => {
    /** 当前编辑的规则 id；null = 未在编辑任何规则。 */
    const selectedRuleId = ref<string | null>(null);
    /** 本地编辑对象（深拷自 server 镜像）；null = 未在编辑。 */
    const workingCopy = ref<ScriptRule | null>(null);
    /** 有未保存本地改动（脏）；脏时拒绝 server 回声覆盖 workingCopy。 */
    const dirty = ref(false);
    /**
     * 正在拖拽（拖块 / palette 拖出 / 移堆）；为真时 server 回声<b>不替换整树</b> workingCopy。
     *
     * <p>P5 实测修复（根因 3）：拖拽进行中若 server.patch 回来（如刚 selectRule 触发的回声 / 别处
     * 改动），server-as-truth 的 deep watch 会 deepClone 整棵 server 树替换 workingCopy → v-for
     * key 重建被拖块的 DOM → pointer capture 丢失（拖不动 / 偶尔变"复制"）。像 dirty 保护那样，
     * 拖拽期跳过整树替换；松手（setDragging(false)）后恢复 server-as-truth。</p>
     */
    const dragging = ref(false);
    /** undo 栈：workingCopy 的历史快照（push 改动前态）。 */
    const undoStack = ref<ScriptRule[]>([]);
    /** redo 栈：undo 后存被撤销的态。 */
    const redoStack = ref<ScriptRule[]>([]);

    /**
     * 0.7.1-P3 波2：当前聚焦的「元素」字段所属积木的 path（如 {@code 'actions/0'} /
     * {@code 'actions/2/then/1'}）；null = 当前没有任何元素字段在聚焦。
     *
     * <p>用途：让预览框（PreviewPane）的"点选元素 = 绑定"知道把命中的 {@code elementId}（及当前
     * 坐标值）填到<b>哪条积木</b>。BlockNode 的元素下拉获得焦点（{@code @focusin}）时
     * {@link setActiveElementBinding} 记下该积木 path；切规则 / 清焦点时清 null。纯 UI 焦点态，
     * 不发任何 op、不受 lock 影响。</p>
     */
    const activeElementBinding = ref<string | null>(null);

    /** 记下当前活跃的元素字段所属积木 path（null = 清除）。BlockNode 元素字段 focusin 调。 */
    function setActiveElementBinding(path: string | null): void {
        activeElementBinding.value = path;
    }

    /** 清除当前活跃元素字段绑定（元素下拉失焦 / 退出编辑时调）。 */
    function clearActiveElementBinding(): void {
        activeElementBinding.value = null;
    }

    /**
     * H（K-UI-9）：对当前 workingCopy 跑前端 validator 镜像得所有错误（空数组 = 合法）。
     * 无 workingCopy → 空数组。UI（ScriptEditorOverlay）读它显红字 banner；{@link doSave}
     * 在 send 前也读它——有错则<b>不 send 且保留 dirty</b>（防"拖完保存才被后端打回"）。
     */
    const validationErrors = computed<ValidationError[]>(() =>
        workingCopy.value ? validateRule(workingCopy.value) : [],
    );

    /** 防抖 save 的 timer 句柄（自实现，便于 flushSave 立即触发 + 测试 fake timer 控制）。 */
    let saveTimer: ReturnType<typeof setTimeout> | null = null;

    const scripts = useScriptStore();
    const project = useProjectStore();
    const net = useNetworkStore();

    // ---------- 选规则 / 关闭 ----------

    /**
     * 选中一条规则进入编辑：从 ScriptStore 深拷进 workingCopy，清 undo/redo + dirty。
     * 切规则前先 flush 上一条的待保存改动（避免丢更新）。不存在的 ruleId → no-op。
     */
    function selectRule(ruleId: string): void {
        const rule = scripts.get(ruleId);
        if (!rule) return;
        // 切到另一条规则前，先把上一条手上的脏改动落盘。
        if (selectedRuleId.value !== null && selectedRuleId.value !== ruleId) {
            flushSave();
        }
        selectedRuleId.value = ruleId;
        workingCopy.value = deepCloneRule(rule);
        undoStack.value = [];
        redoStack.value = [];
        dirty.value = false;
        // 0.7.1-P3 波2：切规则 → 清元素字段绑定（上一规则的积木 path 在新规则里无意义）。
        activeElementBinding.value = null;
    }

    /**
     * 退出编辑：先 flush 待保存改动 → 清空 workingCopy / selectedRuleId / undo 栈。
     * 切 wall（scripts.reset）/ 关闭 overlay / 删除当前规则时调。
     */
    function closeEditing(): void {
        flushSave();
        selectedRuleId.value = null;
        workingCopy.value = null;
        undoStack.value = [];
        redoStack.value = [];
        dirty.value = false;
        // 0.7.1-P3 波2：退出编辑也清元素字段绑定。
        activeElementBinding.value = null;
    }

    // ---------- 新建 / 删除规则 ----------

    /**
     * 新建一条默认规则。lock 时 no-op 返 null。
     *
     * <p><b>拿新 id 的可靠方案</b>（create 是 server 发 id，ack 不含 id——id 经 state.patch
     * 的 {@code add /scripts/<id>} 落 ScriptStore.upsert 追加到 order 末尾）：</p>
     * <ol>
     *   <li>调 sendScriptCreate 前快照当前 {@code scripts.order} 的 id 集合（known set）；</li>
     *   <li>await ack 成功（后端先 apply patch 再 ack，故 ack resolve 时新规则<b>通常已</b>在
     *       store）；</li>
     *   <li>用 {@link findNewRuleId} 比对 order 找 known set 外的新 id；若 ack 时 patch 尚未
     *       到达（边缘），watch order 等下一个新增 id，{@link NEW_RULE_PATCH_TIMEOUT_MS} 超时放弃。</li>
     * </ol>
     * <p>拿到新 id → selectRule 进入编辑并返回该 id；失败（send reject / 超时）→ toast + 返 null。</p>
     */
    async function newRule(name: string = DEFAULT_RULE_NAME): Promise<string | null> {
        if (project.isLocked) return null;
        const draft: Omit<ScriptRule, 'id' | 'wallId'> = {
            enabled: true,
            name,
            trigger: { type: 'wallReady' },
            // 必须带至少一个动作：后端 ScriptRuleValidator 拒空 actions（SCRIPT_INVALID
            // 「动作列表不能为空」），空数组会让 create 直接报错、走不通编辑器入口。默认给
            // 一个空 message 的 log 动作——合法过校验、对游戏无害，用户进编辑器后再改 / 拖别的。
            actions: [makeDefaultAction('log')],
            // 坐标先空——BlockCanvas 的 autoLayout 会给缺坐标规则纵向排布兜底（D2 拖帽子再写实坐标）。
            blockLayout: stringifyBlockLayout({ stacks: {} }),
        };
        const known = new Set(scripts.order);
        try {
            const newId = await createAndAwaitId(draft, known);
            if (newId === null) {
                net.lastError = '新建规则失败：没收到服务器回执';
                return null;
            }
            selectRule(newId);
            return newId;
        } catch (e) {
            net.lastError = `新建规则失败：${e instanceof Error ? e.message : String(e)}`;
            return null;
        }
    }

    /**
     * 发 create + 等新规则 id 落 store。known = 发送前已知的 ruleId 集合。
     * 先 await ack（顺带验证 send 成功 / server 没拒）；ack 后 store 通常已含新规则，
     * 直接比对；否则 watch order 等新增。
     */
    async function createAndAwaitId(
        draft: Omit<ScriptRule, 'id' | 'wallId'>,
        known: Set<string>,
    ): Promise<string | null> {
        const ws = getWsClient();
        // 先挂 order watcher（在 send 之前挂，防 patch 早于本函数 await 点到达而漏掉）。
        const waitNewId = waitForNewOrderId(known);
        try {
            await ws.sendScriptCreate(draft);
        } catch (e) {
            waitNewId.cancel();
            throw e;
        }
        // ack 成功：多数情况下 patch 已先到，store 里已有新规则——立即取。
        const immediate = findNewRuleId(known);
        if (immediate !== null) {
            waitNewId.cancel();
            return immediate;
        }
        // 边缘：patch 尚未到，等 order 新增（超时放弃）。
        return waitNewId.promise;
    }

    /** 在当前 scripts.order 中找一个不在 known 集合里的 id（新建出来的规则）；无则 null。 */
    function findNewRuleId(known: Set<string>): string | null {
        for (const id of scripts.order) {
            if (!known.has(id)) return id;
        }
        return null;
    }

    /**
     * watch scripts.order，等出现 known 之外的新 id（resolve 该 id），超时 resolve null。
     * 返回 {promise, cancel}——拿到 id / send 失败时 cancel 停 watch。
     */
    function waitForNewOrderId(known: Set<string>): {
        promise: Promise<string | null>;
        cancel: () => void;
    } {
        let stop: (() => void) | null = null;
        let timer: ReturnType<typeof setTimeout> | null = null;
        let settled = false;
        const promise = new Promise<string | null>((resolve) => {
            const finish = (id: string | null) => {
                if (settled) return;
                settled = true;
                if (stop) stop();
                if (timer) clearTimeout(timer);
                resolve(id);
            };
            stop = watch(
                () => scripts.order,
                () => {
                    const id = findNewRuleId(known);
                    if (id !== null) finish(id);
                },
            );
            timer = setTimeout(() => finish(null), NEW_RULE_PATCH_TIMEOUT_MS);
        });
        return {
            promise,
            cancel: () => {
                if (settled) return;
                settled = true;
                if (stop) stop();
                if (timer) clearTimeout(timer);
            },
        };
    }

    /**
     * 删除规则。lock 时 no-op。inline confirm 在 UI 层，本方法只管执行 send。
     * 若删的是当前正在编辑的规则 → closeEditing（先 flush 再清，避免删后又把缓存写回）。
     */
    function deleteRule(ruleId: string): void {
        if (project.isLocked) return;
        const editingThis = selectedRuleId.value === ruleId;
        if (editingThis) {
            // 关编辑会 flushSave；但删除场景不该把 workingCopy 写回（规则要没了）。
            // 先取消待保存 timer + 清编辑态，再发 delete。
            cancelPendingSave();
            selectedRuleId.value = null;
            workingCopy.value = null;
            undoStack.value = [];
            redoStack.value = [];
            dirty.value = false;
        }
        getWsClient().sendScriptDelete(ruleId).catch((e) => {
            net.lastError = `删除规则失败：${e instanceof Error ? e.message : String(e)}`;
        });
    }

    // ---------- 变更入口（lock 守卫 + pushUndo + 改 workingCopy + dirty + scheduleSave）----------

    /**
     * 统一变更包装：lock no-op；否则 push 当前 workingCopy 快照入 undo（清 redo）+ 应用
     * mutator + 标脏 + 调度保存。mutator 收当前 working copy（保证非 null 才进），返新规则。
     */
    function mutate(mutator: (rule: ScriptRule) => ScriptRule): void {
        if (project.isLocked) return;
        const cur = workingCopy.value;
        if (!cur) return;
        pushUndo(cur);
        workingCopy.value = mutator(deepCloneRule(cur));
        dirty.value = true;
        scheduleSave();
    }

    /** D2 拖拽结果写这里：整棵 actions 树替换。 */
    function setActions(newActions: ScriptAction[]): void {
        mutate((rule) => ({ ...rule, actions: newActions }));
    }

    /** 改触发器（整个 trigger 多态对象替换，含 type 与其参数）。 */
    function setTrigger(trigger: ScriptTrigger): void {
        mutate((rule) => ({ ...rule, trigger }));
    }

    /** 改规则名。 */
    function setName(name: string): void {
        mutate((rule) => ({ ...rule, name }));
    }

    /**
     * 改 enabled 开关。enabled 是即时开关——直接 {@code sendScriptEnable}（不进 debounce
     * 队列，开关期望立即生效），同时更新 workingCopy.enabled 让 UI 即时反映。
     *
     * <p>不走 mutate（那条路是给 debounce save 的结构 / 参数改动）：enabled 切换本身入 undo
     * 栈意义不大且会与 sendScriptEnable 的 server 回声打架。lock 时 no-op。</p>
     */
    function setEnabled(enabled: boolean): void {
        if (project.isLocked) return;
        const cur = workingCopy.value;
        if (!cur) return;
        if (cur.enabled === enabled) return;
        // 即时回写本地（UI 立刻反映）；不标 dirty（enable 单独走 server，不参与 debounce save）。
        workingCopy.value = { ...cur, enabled };
        const ruleId = selectedRuleId.value;
        if (ruleId === null) return;
        getWsClient().sendScriptEnable(ruleId, enabled).catch((e) => {
            net.lastError = `切换开关失败：${e instanceof Error ? e.message : String(e)}`;
        });
    }

    /**
     * 改某条规则的积木堆坐标（blockLayout.stacks[ruleId]）。D2 拖帽子调；低频但走 debounce
     * save（与其它结构改动一致，不另发协议）。注意 blockLayout 是<b>整条规则的字段</b>，
     * 这里只改 stacks 里该 ruleId 的项（保留其它 ruleId 坐标——虽然实践中一条规则的
     * blockLayout 通常只存自己）。
     */
    function setStackPos(ruleId: string, x: number, y: number): void {
        mutate((rule) => {
            const layout = parseBlockLayout(rule.blockLayout);
            const stacks = { ...layout.stacks, [ruleId]: { x, y } };
            return { ...rule, blockLayout: stringifyBlockLayout({ stacks }) };
        });
    }

    /**
     * 改某个 action 的字段值（F 阶段参数表单调）。{@code path} 是 blockTree path 字符串
     * （如 {@code 'actions/2'} / {@code 'actions/2/then/1'} / {@code 'actions/0/body/0'}）；
     * 复用 {@link replaceAt} 定位 + immutable 重建整棵树（沿途容器节点克隆，未改子树结构
     * 共享）。replaceAt 走 blockTree 已泛化的 transformSequence，<b>统一支持</b> if 的 then/else
     * 与 repeat 的 body——避免 scriptEdit 维护平行实现漏掉新容器键（0.7.1 body 编辑被丢弃的根因）。
     *
     * <p>定位失败（path 非法 / 越界）→ 整体 no-op（不标脏、不 push undo），避免脏写。</p>
     */
    function updateActionField(path: string, patch: Partial<ScriptAction>): void {
        if (project.isLocked) return;
        const cur = workingCopy.value;
        if (!cur) return;
        const segs = parsePath(path);
        const target = getAt(cur.actions, segs);
        if (target === null) return;
        // 先算出新 actions（无变化则返回原引用 → 跳过，避免无意义快照 / 保存）。
        const nextActions = replaceAt(cur.actions, segs, patch);
        if (nextActions === cur.actions) return;
        pushUndo(cur);
        workingCopy.value = { ...deepCloneRule(cur), actions: nextActions };
        dirty.value = true;
        scheduleSave();
    }

    /**
     * 0.7.1：删除 {@code path}（如 {@code 'actions/2'} / {@code 'actions/2/then/1'} /
     * {@code 'actions/0/body/0'}）指向的<b>单个动作积木</b>（含其整棵子树）。用于「拖到删除区删积木」
     * —— 搭错一步不必删整堆重搭。复用 {@link removeAt} immutable 重建（统一支持 if then/else +
     * repeat body 容器）。lock no-op。
     *
     * <p>定位失败（path 非法 / 越界）或删除后 actions 引用未变（无该节点）→ 整体 no-op
     * （不标脏、不 push undo）。注意：删空 actions 后 server 端 ScriptRuleValidator 会拒"动作列表
     * 为空"——这与现有 setActions 删到空一致，doSave 会被 validationErrors 挡住保留 dirty，用户补一个
     * 动作即可保存（画布即时反映删除，不阻塞编辑）。</p>
     */
    function removeAction(path: string): void {
        if (project.isLocked) return;
        const cur = workingCopy.value;
        if (!cur) return;
        const segs = parsePath(path);
        const nextActions = removeAt(cur.actions, segs);
        if (nextActions === cur.actions) return; // path 非法 / 越界 / 无变化
        pushUndo(cur);
        workingCopy.value = { ...deepCloneRule(cur), actions: nextActions };
        dirty.value = true;
        scheduleSave();
    }

    // ---------- 拖拽期冻结（根因 3）----------

    /**
     * 标记拖拽进行中 / 结束。useBlockDrag 的 start*（拖块 / palette / 移堆）调
     * {@code setDragging(true)}，松手 / abort（pointerup / cancel / blur / dispose）调
     * {@code setDragging(false)}。为真时 server-as-truth 的 deep watch 跳过整树替换 workingCopy
     * （防 v-for 重建打断 pointer capture）。不受 lock 影响——这是纯 UI 状态标记，不发任何 op。
     */
    function setDragging(value: boolean): void {
        dragging.value = value;
    }

    // ---------- undo / redo ----------

    /** push 一份快照进 undo 栈（cap 50，超出丢最旧）；清 redo（新分支）。 */
    function pushUndo(snapshot: ScriptRule): void {
        const next = undoStack.value.slice();
        next.push(deepCloneRule(snapshot));
        if (next.length > UNDO_CAP) next.shift();
        undoStack.value = next;
        if (redoStack.value.length > 0) redoStack.value = [];
    }

    /** 撤销：当前 working copy 入 redo，弹 undo 栈顶为新 working copy；标脏 + 保存。 */
    function undo(): void {
        if (project.isLocked) return;
        const cur = workingCopy.value;
        if (!cur || undoStack.value.length === 0) return;
        const undoNext = undoStack.value.slice();
        const prev = undoNext.pop()!;
        undoStack.value = undoNext;
        redoStack.value = [...redoStack.value, deepCloneRule(cur)];
        workingCopy.value = prev;
        dirty.value = true;
        scheduleSave();
    }

    /** 重做：当前 working copy 入 undo，弹 redo 栈顶为新 working copy；标脏 + 保存。 */
    function redo(): void {
        if (project.isLocked) return;
        const cur = workingCopy.value;
        if (!cur || redoStack.value.length === 0) return;
        const redoNext = redoStack.value.slice();
        const next = redoNext.pop()!;
        redoStack.value = redoNext;
        undoStack.value = [...undoStack.value, deepCloneRule(cur)];
        workingCopy.value = next;
        dirty.value = true;
        scheduleSave();
    }

    // ---------- 自动保存 ----------

    /** 调度 debounce 800ms 保存（已有 timer 则重置窗口）。 */
    function scheduleSave(): void {
        cancelPendingSave();
        saveTimer = setTimeout(() => {
            saveTimer = null;
            doSave();
        }, SAVE_DEBOUNCE_MS);
    }

    /** 取消待执行的 save timer（不触发保存）。 */
    function cancelPendingSave(): void {
        if (saveTimer !== null) {
            clearTimeout(saveTimer);
            saveTimer = null;
        }
    }

    /**
     * 立即保存（closeEditing / 切规则前调）：取消 pending timer 后同步触发一次保存。
     * 非脏 / 无选中规则时 doSave 自身会早退。
     */
    function flushSave(): void {
        cancelPendingSave();
        doSave();
    }

    /**
     * 真正发 sendScriptUpdate。仅当脏 + 有选中规则 + 有 workingCopy 时发。
     * 成功后清 dirty（让 server 回声能刷新 workingCopy）；失败<b>不清</b> dirty（下次改动 /
     * flush 时重试，不丢用户改动）+ toast。lock 时不发（理论上 lock 时不会进到这）。
     */
    function doSave(): void {
        if (!dirty.value) return;
        const ruleId = selectedRuleId.value;
        const cur = workingCopy.value;
        if (ruleId === null || cur === null) return;
        if (project.isLocked) return;
        // H（K-UI-9）：有校验错误 → 不 send，保留 dirty（让 UI 红字提示用户修；改完再次
        // scheduleSave / flush 时 validationErrors 变空即可发出）。防"拖完保存才被后端打回"。
        if (validationErrors.value.length > 0) return;
        // 乐观清脏：发出去就当本地无未保存改动；失败回调里再标回脏。
        dirty.value = false;
        getWsClient().sendScriptUpdate(ruleId, stripIdWall(cur)).catch((e) => {
            // 失败：标回脏（下次 scheduleSave / flush 重试），提示用户。
            dirty.value = true;
            net.lastError = `自动保存失败：${e instanceof Error ? e.message : String(e)}`;
        });
    }

    // ---------- server-as-truth 协调 ----------

    /**
     * watch server 镜像里当前编辑规则的变化：
     * - 拖拽中 → 跳过整树替换（根因 3：v-for 重建会打断 pointer capture）；
     * - 非脏 → 用 server 版刷新 workingCopy（回显他人改动 / 自己 save 成功后的权威态）；
     * - 脏   → 保留本地不覆盖（用户正在编辑，server 回声不能踩掉手上的活）。
     * 当前编辑规则被 server 删掉（get 返 null）→ closeEditing（无可编辑对象）。
     *
     * <p>注意 closeEditing（规则被删）<b>不受 dragging 守卫</b>：规则真没了就该退出编辑，
     * 拖一个不存在的规则没有意义；松手时 useBlockDrag 取 workingCopy 为 null 会安全早退。</p>
     */
    watch(
        () => (selectedRuleId.value !== null ? scripts.get(selectedRuleId.value) : null),
        (serverRule) => {
            if (selectedRuleId.value === null) return;
            if (serverRule === null) {
                // 规则在 server 侧没了（别人删了 / 自己 deleteRule 已先清这里就不会再进）。
                closeEditing();
                return;
            }
            if (dragging.value) return; // 拖拽中：保留本地（防整树替换打断 capture）
            if (dirty.value) return; // 脏：保留本地
            workingCopy.value = deepCloneRule(serverRule);
        },
        { deep: true },
    );

    /**
     * wall 切换：project.wallId 变 → scripts 已 reset（project.reset 调），编辑会话必须清空，
     * 否则残留上一面墙的 workingCopy。直接 closeEditing。
     */
    watch(
        () => project.wallId,
        () => {
            closeEditing();
        },
    );

    return {
        selectedRuleId, workingCopy, dirty, dragging, undoStack, redoStack,
        activeElementBinding,
        validationErrors,
        selectRule, closeEditing,
        newRule, deleteRule,
        setActions, setTrigger, setName, setEnabled, setStackPos, updateActionField, removeAction,
        setActiveElementBinding, clearActiveElementBinding,
        setDragging,
        undo, redo,
        scheduleSave, flushSave,
    };
});
