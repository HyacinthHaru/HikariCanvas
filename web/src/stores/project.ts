import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { ProjectState, Element, Layer, PatchOp, Timeline, Keyframe } from '@/types/protocol';
import { useVariableStore } from './variables';
import { useScheduleStore } from './schedule';
import { useVariableAliasStore } from './variableAliases';
import { useRailStore } from './rail';
import { useScriptStore } from './scripts';
import { clearLayerThumbnailCache } from '@/render/LayerThumbnailRenderer';
import { resetImageCaches } from '@/render/PreviewRenderer';

/**
 * 「同一批新建」的时间窗（毫秒）。粘贴 / 批量导入是逐条发 op、逐条回 patch，
 * 相邻两条之间只隔几毫秒；而人手画两个图形至少隔几百毫秒，不会被误并成一批。
 */
export const ADD_BURST_MS = 200;

/** 兜底默认层（state 尚未到达时供 UI 渲染避免 null check 散落组件里）。 */
const EMPTY_LAYER: Layer = {
    id: '',
    name: '',
    visible: true,
    locked: false,
    opacity: 1,
    blendMode: 'normal',
    colorTag: null,
    elements: [],
};

/**
 * ProjectState 本地镜像，随 state.snapshot / state.patch 更新。
 * 服务端仍是权威；本地只做 UI 响应式快速更新。
 *
 * 内部按 v2 layers 形态存储；通过把 activeLayer.elements 同步赋值给
 * state.elements 同一引用，组件可以继续通过 state.elements 读写。新协议路径
 * 会改成直接走 layers。
 */
export const useProjectStore = defineStore('project', () => {
    const state = ref<ProjectState | null>(null);

    /**
     * 最近<b>一批</b>新建元素的 id，顶层组件 watch 它实现"新加即选中"。
     *
     * <p>为什么是一批而不是一个：粘贴 5 个元素会逐个发 {@code element.add}，服务端也逐条回
     * patch。以前每来一条就把选中整个换成它，5 条走完只剩最后一个被选中——用户接着的整体拖动
     * 或删除只作用于 1 个。现在连着到达（间隔 ≤ {@link ADD_BURST_MS}）的算同一批，
     * 数组随每条到达追加并整体重新赋值，UI 每次按整批选中，最终 5 个都在选中里。</p>
     */
    const lastAddedElementIds = ref<string[]>([]);
    /** 当前这批的累积缓冲（非响应式；每次追加后整体赋给 {@link lastAddedElementIds} 触发 watch）。 */
    let addBatch: string[] = [];
    /** 上一条新建到达的时刻，用来判断是不是同一批。 */
    let lastAddAt = 0;

    // wall 元数据（来自 ready payload + wall.* op 的 ack）
    // lockedAt: null = 可编辑，非 null = 锁定；ownerUuid + selfUuid
    // 供前端 computed isOwner = (selfUuid === ownerUuid)，仅 owner 能 lock/unlock。
    const wallId = ref<string | null>(null);
    const alias = ref<string | null>(null);
    const lockedAt = ref<number | null>(null);
    const ownerUuid = ref<string | null>(null);
    const selfUuid = ref<string | null>(null);

    const isLocked = computed(() => lockedAt.value != null);
    const isOwner = computed(() => !!ownerUuid.value && ownerUuid.value === selfUuid.value);
    /** locked + 非 owner = 完全 readonly 无解锁路径；locked + owner = 锁定状态但可解锁 */
    const canEdit = computed(() => !isLocked.value);

    const canvasPixelWidth = computed(() =>
        state.value ? state.value.canvas.widthMaps * 128 : 0);
    const canvasPixelHeight = computed(() =>
        state.value ? state.value.canvas.heightMaps * 128 : 0);

    /** 当前活动层；state 未就绪时返一个空 placeholder（UI 渲染容错）。 */
    const activeLayer = computed<Layer>(() => {
        const s = state.value;
        if (!s || !s.layers || s.layers.length === 0) return EMPTY_LAYER;
        return s.layers.find((l) => l.id === s.activeLayerId) ?? s.layers[0];
    });

    /** 是否活动层被锁；UI 用它禁用 element 列表的 element op 按钮 + element 在 canvas 上的拖拽。 */
    const activeLayerLocked = computed(() => activeLayer.value.locked);

    /**
     * 把所有图层的元素展平成 {@code {id, type, label}} 列表，供积木编辑器
     * （setElementProperty 的 elementId 下拉）选元素用。label 是人类可读名——文本元素截
     * 前 16 字正文，其余用 {@code 类型 · id 短码}（id 取 {@code e-} 后的短 hash 显示）。
     *
     * <p>跨层 z-order 顺序（图层 index + 层内 index）保留，让下拉里的顺序与画布一致。state
     * 未就绪 → 空数组。</p>
     */
    const allElements = computed<{ id: string; type: string; label: string }[]>(() => {
        const s = state.value;
        if (!s || !s.layers) return [];
        const out: { id: string; type: string; label: string }[] = [];
        for (const layer of s.layers) {
            for (const el of layer.elements ?? []) {
                out.push({ id: el.id, type: el.type, label: elementLabel(el) });
            }
        }
        return out;
    });

    function setSnapshot(snapshot: ProjectState) {
        state.value = snapshot;
        // 兼容视图：state.elements 指向 activeLayer.elements 同一引用
        linkActiveLayerElements(state.value);
    }

    function setWallMeta(id: string | null, a: string | null, lock: number | null,
                         owner: string | null, self: string | null) {
        wallId.value = id;
        alias.value = a;
        lockedAt.value = lock;
        ownerUuid.value = owner;
        selfUuid.value = self;
    }

    /**
     * 应用 RFC 6902 子集 patch（add / replace / remove）。
     *
     * <p>后端 EditSession 仍发 v1 path（{@code /elements/N/...} / {@code /canvas/...}）；
     * 也可切换到 v2 path（{@code /layers/{i}/elements/{j}/...}）。两种 path 都处理：
     * v1 path 落到 activeLayer 上，v2 path 按显式层索引落位。</p>
     */
    function applyPatch(version: number, ops: PatchOp[]) {
        if (!state.value) return;
        claimNewElements(state.value, ops);
        for (const op of ops) {
            applyOne(state.value, op);
        }
        state.value.version = version;
    }

    /**
     * 从一帧 patch 里挑出<b>真·新建</b>的元素 id，喂给"新加即选中"。
     *
     * <p>必须在应用这帧之前判断：调整叠放顺序 / 换图层发的也是「先 remove 再 add 同一个元素」，
     * 光看 add 会把移动认成新建，于是拖一下图层列表就把选中抢走了。所以先照着<b>改动前</b>的
     * 状态记下已有 id，add 的元素已经在里面就说明它只是换了位置，不认。</p>
     */
    function claimNewElements(s: ProjectState, ops: PatchOp[]): void {
        const addedIds: string[] = [];
        for (const op of ops) {
            // element.add 的 patch 形态：v1 /elements/N 或 v2 /layers/M/elements/N
            if (op.op !== 'add' || !op.value) continue;
            if (!/^\/elements\/\d+$/.test(op.path) && !/^\/layers\/\d+\/elements\/\d+$/.test(op.path)) continue;
            const elId = (op.value as { id?: unknown }).id;
            if (typeof elId === 'string') addedIds.push(elId);
        }
        if (addedIds.length === 0) return;
        const existing = new Set<string>();
        for (const layer of s.layers ?? []) {
            for (const el of layer.elements ?? []) existing.add(el.id);
        }
        for (const id of addedIds) {
            if (!existing.has(id)) claimAdded(id);
        }
    }

    /** 登记一个新建元素，按时间窗归批（见 {@link lastAddedElementIds}）。 */
    function claimAdded(id: string): void {
        const now = Date.now();
        if (now - lastAddAt > ADD_BURST_MS) addBatch = [];
        lastAddAt = now;
        addBatch = [...addBatch, id];
        lastAddedElementIds.value = addBatch;
    }

    /**
     * 按 id 找元素，<b>扫全部图层</b>。
     *
     * <p>不能只查 {@code state.elements}（那是 activeLayer.elements 的兼容视图）：切图层
     * 不会清空选中态，Konva 的命中层也只铺活动层，于是「选中的元素在别的层」是常态。
     * 只查活动层的话，属性面板会突然变空、方向键微移静默 no-op、复制悄悄漏掉元素、
     * 时间轴加帧（useTimelineAuthoring 里 {@code if (!el) return}）静默失败。后端
     * {@code elementExists} 本来就是扫全层的，两边对齐。</p>
     */
    function elementById(id: string): Element | null {
        const s = state.value;
        if (!s || !s.layers) return null;
        for (const layer of s.layers) {
            const hit = (layer.elements ?? []).find((e) => e.id === id);
            if (hit) return hit;
        }
        return null;
    }

    function layerById(id: string): Layer | null {
        if (!state.value) return null;
        return state.value.layers.find((l) => l.id === id) ?? null;
    }

    /**
     * 元素所在的图层（扫全部图层）；找不到返 null。
     *
     * <p>判"这个元素能不能改"要看它<b>自己所在</b>的图层锁没锁，不是看当前激活的那层——
     * 切图层不清选中，选中的元素在别的层是常态。后端 {@code EditSession} 拒 LAYER_LOCKED
     * 就是按元素所属层判的，两边对齐。</p>
     */
    function layerOfElement(id: string): Layer | null {
        const s = state.value;
        if (!s || !s.layers) return null;
        for (const layer of s.layers) {
            if ((layer.elements ?? []).some((e) => e.id === id)) return layer;
        }
        return null;
    }

    /**
     * 这个元素现在能不能改：元素自己没上锁 + 所在图层没上锁。
     *
     * <p>图层锁后端会拒（LAYER_LOCKED），元素锁后端不管——所以元素锁只有前端拦得住，
     * 删除 / 方向键微移 / 变换锚点都得先问这一句，否则"锁定"就是个摆设。</p>
     */
    function isElementEditable(id: string): boolean {
        const el = elementById(id);
        if (!el || el.locked) return false;
        const layer = layerOfElement(id);
        return !layer || !layer.locked;
    }

    /** 从一组 id 里挑出现在能改的那些，顺序保持不变。删除 / 微移 / 拖动共用。 */
    function editableIds(ids: Iterable<string>): string[] {
        const out: string[] = [];
        for (const id of ids) {
            if (isElementEditable(id)) out.push(id);
        }
        return out;
    }

    /**
     * setup store 没有自动 $reset，手动把所有 wall-scoped ref 重置到初始值。
     * 调用点：wall 切换 / disconnect 后；palette / brush store 等跨 wall 用户偏好不在此处理。
     */
    function reset(): void {
        state.value = null;
        lastAddedElementIds.value = [];
        addBatch = [];
        lastAddAt = 0;
        wallId.value = null;
        alias.value = null;
        lockedAt.value = null;
        ownerUuid.value = null;
        selfUuid.value = null;
        // variables 是 cross-wall 全局 store，切 wall 时一起清；
        // 重连同 wall 不会进 reset 分支（见 wsClient.handleReady 的 wallId diff 判断）
        useVariableStore().reset();
        // wall-scoped store 的 reset 统一收进 project.reset()，单一调用点，
        // 避免散在 wsClient.handleReady 里靠手动并列调用维护（漏一个就泄漏旧 wall 状态）。
        // schedule 是 wall-scoped 元数据。
        useScheduleStore().reset();
        // alias 也是 wall-scoped。
        useVariableAliasStore().reset();
        // rail store 含 lines / stations / runs / timetables 内存缓存。
        useRailStore().reset();
        // 脚本规则是 wall-scoped 镜像。
        useScriptStore().reset();
        // 图层缩略图缓存（按 layerId 索引）跨 wall 不可复用，切 wall 时清。
        clearLayerThumbnailCache();
        // 丢弃 PreviewRenderer 的图片 / 图标失败缓存条目，让切 wall / 重连后
        // 瞬时失败（404 / session 过期）的资源重新加载，不再永久占位 ?。
        resetImageCaches();
    }

    return {
        state,
        lastAddedElementIds,
        wallId, alias, lockedAt, ownerUuid, selfUuid,
        isLocked, isOwner, canEdit,
        canvasPixelWidth, canvasPixelHeight,
        activeLayer, activeLayerLocked, allElements,
        setSnapshot, setWallMeta, applyPatch,
        elementById, layerById, layerOfElement, isElementEditable, editableIds,
        reset,
    };
});

// ---------- 内部辅助 ----------

/**
 * 单个元素的人类可读名（积木 elementId 下拉显示用）。
 *
 * <ul>
 *   <li>文本元素：有正文 → 取前 16 字（超出加省略号）；空正文 → 退 {@code 文本 · 短码}；</li>
 *   <li>其他元素：{@code 类型 · 短码}（短码 = id 去掉 {@code e-} 前缀的头 6 位，避免太长）。</li>
 * </ul>
 *
 * <p>注意：返回的是<b>展示文案</b>（含类型词），写回 wire 的始终是 {@code element.id}。</p>
 */
function elementLabel(el: Element): string {
    const shortId = el.id.replace(/^e-/, '').slice(0, 6);
    if (el.type === 'text') {
        const text = (el as { text?: string }).text;
        if (typeof text === 'string' && text.trim().length > 0) {
            const trimmed = text.trim();
            return trimmed.length > 16 ? `${trimmed.slice(0, 16)}…` : trimmed;
        }
    }
    return `${el.type} · ${shortId}`;
}

function activeLayerOf(state: ProjectState): Layer | null {
    if (!state.layers || state.layers.length === 0) return null;
    const found = state.layers.find((l) => l.id === state.activeLayerId);
    return found ?? state.layers[0];
}

/** 把 state.elements 指针重定向到 activeLayer.elements；后续 splice 直接落地。 */
function linkActiveLayerElements(state: ProjectState): void {
    const active = activeLayerOf(state);
    if (active) {
        if (!active.elements) active.elements = [];
        state.elements = active.elements;
    } else {
        state.elements = [];
    }
}

function applyOne(state: ProjectState, op: PatchOp): void {
    const tokens = parsePath(op.path);
    if (tokens.length === 0) return;

    // ---------- /activeLayerId（layer.set-active）----------
    if (tokens.length === 1 && tokens[0] === 'activeLayerId' && op.op === 'replace') {
        state.activeLayerId = op.value as string;
        // 切活动层 → state.elements 重新指向新 activeLayer.elements 同一引用
        linkActiveLayerElements(state);
        return;
    }

    // ---------- /canvas/<field> ----------
    if (tokens[0] === 'canvas' && tokens.length === 2) {
        const field = tokens[1] as keyof ProjectState['canvas'];
        // replace 的 value 缺席 = 后端把该字段归一化成 null（Javalin 全局
        // JsonInclude.NON_NULL 会整个省掉 value 字段），不是"没带值所以别动"。
        // 早先要求 value !== undefined 才应用，导致 setGridSize(0) → null 的
        // 关网格 patch 被静默丢弃，UI 网格关不掉、snap 仍按旧 gridSize 吸附。
        // 同族的 /tweenFps 与 /activeTimelineId 分支一直有 undefined 兜底。
        if (op.op === 'replace') {
            // canvas.background 升级 FillCompat 后 Canvas 字段类型异构，
            // 显式 unknown 中转避免 TS 严格 cast 报错。
            (state.canvas as unknown as Record<string, unknown>)[field] = op.value;
        } else if (op.op === 'remove') {
            (state.canvas as unknown as Record<string, unknown>)[field] = undefined;
        }
        return;
    }

    // ---------- v1 path: /elements/<idx>[/<field>] ----------
    if (tokens[0] === 'elements') {
        const idx = parseInt(tokens[1], 10);
        if (Number.isNaN(idx)) return;
        const elements = ensureActiveElements(state);
        applyElementMutation(elements, idx, tokens.slice(2), op);
        return;
    }

    // ---------- v2 path: /layers/<i>/elements/<j>[/<field>] ----------
    if (tokens[0] === 'layers' && tokens.length >= 4 && tokens[2] === 'elements') {
        const layerIdx = parseInt(tokens[1], 10);
        const elIdx = parseInt(tokens[3], 10);
        if (Number.isNaN(layerIdx) || Number.isNaN(elIdx)) return;
        const layer = state.layers[layerIdx];
        if (!layer) return;
        applyElementMutation(layer.elements, elIdx, tokens.slice(4), op);
        // 若改的是 activeLayer，state.elements 指针已自动同步（同引用）
        return;
    }

    // ---------- /layers/<i> 层级操作（layer.create/delete/reorder/duplicate）----------
    if (tokens[0] === 'layers' && tokens.length === 2) {
        const idx = parseInt(tokens[1], 10);
        // 完整边界校验。负索引让 splice 反向定位（静默错位），越界插稀疏空洞。
        if (!Number.isInteger(idx) || idx < 0) return;
        if (op.op === 'add' && op.value) {
            if (idx > state.layers.length) return;  // add 允许追加到 length
            state.layers.splice(idx, 0, op.value as Layer);
        } else if (op.op === 'remove') {
            if (idx >= state.layers.length) return;
            state.layers.splice(idx, 1);
        } else if (op.op === 'replace' && op.value) {
            if (idx >= state.layers.length) return;
            state.layers.splice(idx, 1, op.value as Layer);
        }
        // 任何层数组突变都可能让 activeLayer 索引变化 → 重新链接 elements 引用
        linkActiveLayerElements(state);
        return;
    }

    // ---------- /layers/<i>/<field> 单字段（layer.update）----------
    if (tokens[0] === 'layers' && tokens.length === 3) {
        const idx = parseInt(tokens[1], 10);
        if (Number.isNaN(idx)) return;
        const layer = state.layers[idx];
        if (!layer) return;
        const field = tokens[2];
        if (op.op === 'replace') {
            (layer as unknown as Record<string, unknown>)[field] = op.value;
        } else if (op.op === 'remove') {
            delete (layer as unknown as Record<string, unknown>)[field];
        }
        return;
    }

    // ---------- /tweenFps（canvas.tweenFps op 的 state.patch 回推）----------
    // 顶层标量，与 /activeLayerId / /activeTimelineId 同构。缺省时前端回退 30fps。
    if (tokens.length === 1 && tokens[0] === 'tweenFps' && op.op === 'replace') {
        state.tweenFps = typeof op.value === 'number' ? op.value : undefined;
        return;
    }
    if (tokens.length === 1 && tokens[0] === 'tweenFps' && op.op === 'remove') {
        state.tweenFps = undefined;
        return;
    }

    // ---------- /activeTimelineId（timeline.create / delete 的副带 replace）----------
    // v3 新增：与 /activeLayerId 同构的单 token 顶层标量。timeline.delete 后可能为 null。
    if (tokens.length === 1 && tokens[0] === 'activeTimelineId' && op.op === 'replace') {
        state.activeTimelineId = (op.value as string | null) ?? undefined;
        return;
    }
    if (tokens.length === 1 && tokens[0] === 'activeTimelineId' && op.op === 'remove') {
        state.activeTimelineId = undefined;
        return;
    }

    // ---------- /timelines/...（v3 新增）----------
    // 后端 patch 形态见 docs/protocol.md §5.2 / §5.12 / §5.13：
    //   timeline.create → add /timelines/<i>（整 Timeline）+ replace /activeTimelineId
    //   timeline.update → replace /timelines/<i>/<field>
    //   timeline.delete → remove /timelines/<i> + replace /activeTimelineId
    //   keyframe.*     → /timelines/<i>/tracks/<eid>[/<k>[/<field>]]（eid 段 JSON Pointer 编码）
    // 全部分支对缺失对象 / 越界索引做无 throw 的静默 return（与上方 applyOne 容错风格一致）。
    if (tokens[0] === 'timelines') {
        applyTimelineMutation(state, tokens.slice(1), op);
        return;
    }
}

/**
 * /timelines/<i>... 子树的突变（v3）。{@code rest} 已剥去首段 'timelines'（applyOne 传入
 * {@code tokens.slice(1)}），故下方索引比 docs/protocol.md 用全路径计的 len 少 1：
 *
 * <ul>
 *   <li>rest.length===1：/timelines/&lt;i&gt;（整 Timeline 数组项 add / remove / replace）</li>
 *   <li>rest.length===2：/timelines/&lt;i&gt;/&lt;field&gt;（timeline.update 单字段 replace）</li>
 *   <li>rest.length===3：/timelines/&lt;i&gt;/tracks/&lt;eid&gt;（整轨 add / replace / remove）</li>
 *   <li>rest.length===4：/timelines/&lt;i&gt;/tracks/&lt;eid&gt;/&lt;k&gt;（关键帧项）</li>
 *   <li>rest.length&gt;=5：/timelines/&lt;i&gt;/tracks/&lt;eid&gt;/&lt;k&gt;/&lt;field&gt;（关键帧单字段）</li>
 * </ul>
 */
function applyTimelineMutation(state: ProjectState, rest: string[], op: PatchOp): void {
    if (rest.length === 0) return;

    // /timelines/<i>：整 Timeline 数组项
    const idx = parseInt(rest[0], 10);
    if (!Number.isInteger(idx) || idx < 0) return;

    if (rest.length === 1) {
        // 列表懒初始化；首条 timeline add 时 timelines 可能为 undefined。
        if (op.op === 'add' && op.value) {
            if (idx > (state.timelines?.length ?? 0)) return; // add 允许追加到 length
            if (!state.timelines) state.timelines = [];
            state.timelines.splice(idx, 0, op.value as Timeline);
        } else if (op.op === 'remove') {
            if (!state.timelines || idx >= state.timelines.length) return;
            state.timelines.splice(idx, 1);
        } else if (op.op === 'replace' && op.value) {
            if (!state.timelines || idx >= state.timelines.length) return;
            state.timelines.splice(idx, 1, op.value as Timeline);
        }
        return;
    }

    // 以下分支都需要定位到已存在的 timeline 对象。
    const timeline = state.timelines?.[idx];
    if (!timeline) return;

    // /timelines/<i>/<field>：timeline.update 单字段（name / durationMs / fps / loopMode / trigger）
    if (rest.length === 2) {
        const field = rest[1];
        if (field === 'tracks') return; // tracks 整替走不到这里（后端按轨 / 帧粒度发）
        if (op.op === 'replace') {
            (timeline as unknown as Record<string, unknown>)[field] = op.value;
        }
        return;
    }

    // /timelines/<i>/tracks/...
    if (rest[1] !== 'tracks') return;

    // tracks ??= {} 防御（理论上 canonical Timeline 必有，但 forward-compat 守一手）。
    if (!timeline.tracks) timeline.tracks = {};
    // elementId 段须 JSON Pointer 解码（~1 → / 、~0 → ~）；轨道 key 是 elementId，可能含转义。
    const eid = decodeJsonPointerToken(rest[2]);

    // /timelines/<i>/tracks/<eid>：整轨增删（keyframe.add 新轨 / keyframe.move 整轨重排 / 轨空 remove）
    if (rest.length === 3) {
        if (op.op === 'add' || op.op === 'replace') {
            timeline.tracks[eid] = op.value as Keyframe[];
        } else if (op.op === 'remove') {
            delete timeline.tracks[eid];
        }
        return;
    }

    // /timelines/<i>/tracks/<eid>/<k>[/<field>]：单关键帧项
    const k = parseInt(rest[3], 10);
    if (!Number.isInteger(k) || k < 0) return;

    if (rest.length === 4) {
        // 关键帧项的 add / remove / replace。add 允许 k === length（追加）。
        if (op.op === 'add') {
            // track ??= [] 防御：新轨可能先发整轨 add 也可能逐项 add。
            if (!timeline.tracks[eid]) timeline.tracks[eid] = [];
            const arr = timeline.tracks[eid];
            if (k > arr.length) return;
            arr.splice(k, 0, op.value as Keyframe);
        } else if (op.op === 'remove') {
            const arr = timeline.tracks[eid];
            if (!arr || k >= arr.length) return;
            arr.splice(k, 1);
        } else if (op.op === 'replace' && op.value) {
            const arr = timeline.tracks[eid];
            if (!arr || k >= arr.length) return;
            arr.splice(k, 1, op.value as Keyframe);
        }
        return;
    }

    // /timelines/<i>/tracks/<eid>/<k>/<field>：关键帧单字段 replace（timeMs / value / easing 等）
    if (op.op === 'replace') {
        const arr = timeline.tracks[eid];
        if (!arr) return;
        const kf = arr[k];
        if (!kf) return;
        const field = rest[4];
        (kf as unknown as Record<string, unknown>)[field] = op.value;
    }
}

function ensureActiveElements(state: ProjectState): Element[] {
    if (!state.elements) {
        linkActiveLayerElements(state);
    }
    return state.elements!;
}

function applyElementMutation(
    elements: Element[],
    idx: number,
    fieldPath: string[],
    op: PatchOp,
): void {
    // 完整边界校验，不只防 NaN。负索引会让 splice 从尾部反向定位（静默错位插入/删除），
    // 越界会插到稀疏空洞。add 允许 idx == length（追加）；remove/replace 须 idx < length。
    if (!Number.isInteger(idx) || idx < 0) return;
    if (op.op === 'add' && fieldPath.length === 0) {
        if (idx > elements.length) return;
        elements.splice(idx, 0, op.value as Element);
    } else if (op.op === 'remove' && fieldPath.length === 0) {
        if (idx >= elements.length) return;
        elements.splice(idx, 1);
    } else if (op.op === 'replace' && fieldPath.length >= 1) {
        const el = elements[idx];
        if (!el) return;
        const field = fieldPath[0];
        (el as unknown as Record<string, unknown>)[field] = op.value;
    } else if (op.op === 'remove' && fieldPath.length >= 1) {
        const el = elements[idx];
        if (!el) return;
        const field = fieldPath[0];
        delete (el as unknown as Record<string, unknown>)[field];
    }
}

/** `/elements/3/x` → ['elements', '3', 'x']。RFC 6901 转义略（协议约定不出现 `~` / `/`）。 */
function parsePath(path: string): string[] {
    if (!path.startsWith('/')) return [];
    return path.slice(1).split('/');
}

/**
 * RFC 6901 单 token 解码：{@code ~1} → {@code /}，{@code ~0} → {@code ~}（顺序必须先 ~1 再 ~0）。
 *
 * <p>v3 timeline tracks 的 key 是 elementId（"e-&lt;8hex&gt;" 形态，实践中不含转义字符），
 * 但后端 StatePatchBuilder 走 JSON Pointer 编码出 path，前端镜像保持解码对称。
 * wsClient.ts 内有同名 module-private 实现（用于 /variables/ 与 /aliases/ 分拣），
 * 此处本地复制一份避免跨文件 export 耦合。</p>
 */
function decodeJsonPointerToken(token: string): string {
    return token.replace(/~1/g, '/').replace(/~0/g, '~');
}
