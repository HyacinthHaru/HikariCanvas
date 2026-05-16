import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { ProjectState, Element, Layer, PatchOp } from '@/types/protocol';

/** 兜底默认层（state 尚未到达时供 UI 渲染避免 null check 散落组件里）。 */
const EMPTY_LAYER: Layer = {
    id: '',
    name: '',
    visible: true,
    locked: false,
    opacity: 1,
    blendMode: 'normal',
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
            (state.canvas as Record<string, unknown>)[field] = op.value;
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
        if (Number.isNaN(idx)) return;
        if (op.op === 'add' && op.value) {
            state.layers.splice(idx, 0, op.value as Layer);
        } else if (op.op === 'remove') {
            state.layers.splice(idx, 1);
        } else if (op.op === 'replace' && op.value) {
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
    if (op.op === 'add' && fieldPath.length === 0) {
        elements.splice(idx, 0, op.value as Element);
    } else if (op.op === 'remove' && fieldPath.length === 0) {
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
