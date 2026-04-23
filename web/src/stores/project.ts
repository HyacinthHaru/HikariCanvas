import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { ProjectState, Element, PatchOp } from '@/types/protocol';

/**
 * ProjectState 本地镜像，随 state.snapshot / state.patch 更新。
 * 服务端仍是权威；本地只做 UI 响应式快速更新。
 */
export const useProjectStore = defineStore('project', () => {
    const state = ref<ProjectState | null>(null);

    /**
     * 最近一次 applyPatch 里被 {@code add} 创建的 element.id。
     * 顶层组件 watch 它实现"新加即选中"。读后由 UI 侧自行清零（赋 null）。
     */
    const lastAddedElementId = ref<string | null>(null);

    const canvasPixelWidth = computed(() =>
        state.value ? state.value.canvas.widthMaps * 128 : 0);
    const canvasPixelHeight = computed(() =>
        state.value ? state.value.canvas.heightMaps * 128 : 0);

    function setSnapshot(snapshot: ProjectState) {
        state.value = snapshot;
    }

    /**
     * 应用 RFC 6902 子集 patch（add / replace / remove）。
     * 只处理 /elements/* 与 /canvas/* 两棵路径；未知 path 忽略（log 出来由 caller 决定）。
     */
    function applyPatch(version: number, ops: PatchOp[]) {
        if (!state.value) return;
        for (const op of ops) {
            // 检测 element.add：/elements/N（不含更深路径）
            if (op.op === 'add' && /^\/elements\/\d+$/.test(op.path) && op.value) {
                const elId = (op.value as { id?: unknown }).id;
                if (typeof elId === 'string') lastAddedElementId.value = elId;
            }
            applyOne(state.value, op);
        }
        state.value.version = version;
    }

    function elementById(id: string): Element | null {
        if (!state.value) return null;
        return state.value.elements.find((e) => e.id === id) ?? null;
    }

    return {
        state,
        lastAddedElementId,
        canvasPixelWidth, canvasPixelHeight,
        setSnapshot, applyPatch,
        elementById,
    };
});

// ---------- 内部辅助 ----------

function applyOne(state: ProjectState, op: PatchOp): void {
    const tokens = parsePath(op.path);
    if (tokens.length === 0) return;

    if (tokens[0] === 'canvas' && tokens.length === 2) {
        const field = tokens[1] as keyof ProjectState['canvas'];
        if (op.op === 'replace' && op.value !== undefined) {
            (state.canvas as Record<string, unknown>)[field] = op.value;
        }
        return;
    }

    if (tokens[0] === 'elements') {
        const idx = parseInt(tokens[1], 10);
        if (Number.isNaN(idx)) return;
        if (op.op === 'add' && tokens.length === 2) {
            state.elements.splice(idx, 0, op.value as Element);
        } else if (op.op === 'remove' && tokens.length === 2) {
            state.elements.splice(idx, 1);
        } else if (op.op === 'replace' && tokens.length >= 3) {
            const el = state.elements[idx];
            if (!el) return;
            const field = tokens[2];
            (el as unknown as Record<string, unknown>)[field] = op.value;
        } else if (op.op === 'remove' && tokens.length >= 3) {
            const el = state.elements[idx];
            if (!el) return;
            const field = tokens[2];
            delete (el as unknown as Record<string, unknown>)[field];
        }
    }
}

/** `/elements/3/x` → ['elements', '3', 'x']。RFC 6901 转义略（协议约定不出现 `~` / `/`）。 */
function parsePath(path: string): string[] {
    if (!path.startsWith('/')) return [];
    return path.slice(1).split('/');
}
