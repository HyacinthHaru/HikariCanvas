import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { ProjectState, Element, Layer, PatchOp, Timeline, Keyframe } from '@/types/protocol';
import { useVariableStore } from './variables';
import { useScheduleStore } from './schedule';
import { useVariableAliasStore } from './variableAliases';
import { useRailStore } from './rail';
import { clearLayerThumbnailCache } from '@/render/LayerThumbnailRenderer';
import { resetImageCaches } from '@/render/PreviewRenderer';

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
 * M8-A：内部按 v2 layers 形态存储；通过把 activeLayer.elements 同步赋值给
 * state.elements 同一引用，组件可以继续通过 state.elements 读写。M8-C 起新协议路径
 * 会改成直接走 layers。
 */
export const useProjectStore = defineStore('project', () => {
    const state = ref<ProjectState | null>(null);

    /**
     * 最近一次 applyPatch 里被 {@code add} 创建的 element.id。
     * 顶层组件 watch 它实现"新加即选中"。读后由 UI 侧自行清零（赋 null）。
     */
    const lastAddedElementId = ref<string | null>(null);

    // M5.5：wall 元数据（来自 ready payload + wall.* op 的 ack）
    // 2026-05-14 lock-state 重设计：publishedAt → lockedAt；新增 ownerUuid + selfUuid
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

    /** 是否活动层被锁；M8-D：UI 用它禁用 element 列表的 element op 按钮 + element 在 canvas 上的拖拽。 */
    const activeLayerLocked = computed(() => activeLayer.value.locked);

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
     * <p>M8-A 阶段后端 EditSession 仍发 v1 path（{@code /elements/N/...} / {@code /canvas/...}）；
     * M8-C 起将切换到 v2 path（{@code /layers/{i}/elements/{j}/...}）。两种 path 都处理：
     * v1 path 落到 activeLayer 上，v2 path 按显式层索引落位。</p>
     */
    function applyPatch(version: number, ops: PatchOp[]) {
        if (!state.value) return;
        for (const op of ops) {
            // 检测 element.add：v1 /elements/N 或 v2 /layers/M/elements/N
            if (op.op === 'add' && op.value
                && (/^\/elements\/\d+$/.test(op.path)
                    || /^\/layers\/\d+\/elements\/\d+$/.test(op.path))) {
                const elId = (op.value as { id?: unknown }).id;
                if (typeof elId === 'string') lastAddedElementId.value = elId;
            }
            applyOne(state.value, op);
        }
        state.value.version = version;
    }

    function elementById(id: string): Element | null {
        if (!state.value) return null;
        const elements = state.value.elements;
        if (!elements) return null;
        return elements.find((e) => e.id === id) ?? null;
    }

    function layerById(id: string): Layer | null {
        if (!state.value) return null;
        return state.value.layers.find((l) => l.id === id) ?? null;
    }

    /**
     * M16 P4.2：setup store 没有自动 $reset，手动把所有 wall-scoped ref 重置到初始值。
     * 调用点：wall 切换 / disconnect 后；palette / brush store 等跨 wall 用户偏好不在此处理。
     */
    function reset(): void {
        state.value = null;
        lastAddedElementId.value = null;
        wallId.value = null;
        alias.value = null;
        lockedAt.value = null;
        ownerUuid.value = null;
        selfUuid.value = null;
        // 0.4.0-P1-D：variables 是 cross-wall 全局 store，切 wall 时一起清；
        // 重连同 wall 不会进 reset 分支（见 wsClient.handleReady 的 wallId diff 判断）
        useVariableStore().reset();
        // P3-103：wall-scoped store 的 reset 统一收进 project.reset()，单一调用点，
        // 避免散在 wsClient.handleReady 里靠手动并列调用维护（漏一个就泄漏旧 wall 状态）。
        // 0.4.0-P3-L：schedule 是 wall-scoped 元数据。
        useScheduleStore().reset();
        // 0.4.2：alias 也是 wall-scoped。
        useVariableAliasStore().reset();
        // 0.4.5 P3：rail store 含 lines / stations / runs / timetables 内存缓存。
        useRailStore().reset();
        // 图层缩略图缓存（按 layerId 索引）跨 wall 不可复用，切 wall 时清。
        clearLayerThumbnailCache();
        // P2-70：丢弃 PreviewRenderer 的图片 / 图标失败缓存条目，让切 wall / 重连后
        // 瞬时失败（404 / session 过期）的资源重新加载，不再永久占位 ?。
        resetImageCaches();
    }

    return {
        state,
        lastAddedElementId,
        wallId, alias, lockedAt, ownerUuid, selfUuid,
        isLocked, isOwner, canEdit,
        canvasPixelWidth, canvasPixelHeight,
        activeLayer, activeLayerLocked,
        setSnapshot, setWallMeta, applyPatch,
        elementById, layerById,
        reset,
    };
});

// ---------- 内部辅助 ----------

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
        if (op.op === 'replace' && op.value !== undefined) {
            // M17 F5：canvas.background 升级 FillCompat 后 Canvas 字段类型异构，
            // 显式 unknown 中转避免 TS 严格 cast 报错。
            (state.canvas as unknown as Record<string, unknown>)[field] = op.value;
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
        // P3-57：完整边界校验。负索引让 splice 反向定位（静默错位），越界插稀疏空洞。
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
    // P3-57：完整边界校验，不只防 NaN。负索引会让 splice 从尾部反向定位（静默错位插入/删除），
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
