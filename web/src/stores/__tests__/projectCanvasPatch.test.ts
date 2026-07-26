/**
 * /canvas/&lt;field&gt; 的 state.patch 分支单测。
 *
 * <p>核心守卫：Javalin 全局 {@code JsonInclude.NON_NULL} 会把 {@code value: null} 整个字段
 * 省掉，于是「关网格」（setGridSize(0) → 归一化 null → replace /canvas/gridSize）到前端时
 * {@code op.value === undefined}。早先前端要求 {@code value !== undefined} 才应用，导致该 op
 * 被静默丢弃：UI 网格关不掉、useSnapManager 仍按旧 gridSize 吸附，直到重连拿 snapshot。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useProjectStore } from '../project';
import type { ProjectState, PatchOp } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function stateWithGrid(gridSize?: number): ProjectState {
    return {
        version: 0,
        protocolVersion: 3,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF', gridSize },
        layers: [{
            id: 'l-0', name: 'Layer 0', visible: true, locked: false,
            opacity: 1, blendMode: 'normal', colorTag: null, elements: [],
        }],
        activeLayerId: 'l-0',
        history: { undoDepth: 0, redoDepth: 0 },
    };
}

describe('/canvas/<field> patch', () => {
    it('replace 带值 → 正常写入', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        project.applyPatch(1, [
            { op: 'replace', path: '/canvas/gridSize', value: 16 } as PatchOp,
        ]);
        expect(project.state?.canvas.gridSize).toBe(16);
    });

    it('replace 的 value 缺席（NON_NULL 省掉）→ 字段清空，网格真的关得掉', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        // 后端 setGridSize(0) 归一化为 null；序列化后 value 字段整个不存在
        project.applyPatch(1, [
            { op: 'replace', path: '/canvas/gridSize' } as unknown as PatchOp,
        ]);
        expect(project.state?.canvas.gridSize).toBeUndefined();
    });

    it('remove → 字段清空', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        project.applyPatch(1, [
            { op: 'remove', path: '/canvas/gridSize' } as PatchOp,
        ]);
        expect(project.state?.canvas.gridSize).toBeUndefined();
    });

    it('background 等非空字段不受影响', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        project.applyPatch(1, [
            { op: 'replace', path: '/canvas/background', value: '#101010' } as PatchOp,
        ]);
        expect(project.state?.canvas.background).toBe('#101010');
        expect(project.state?.canvas.gridSize).toBe(8);
    });
});
