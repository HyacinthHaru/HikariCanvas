import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import { useProjectStore } from './project';
import type { Timeline } from '@/types/protocol';

/**
 * AE 风时间轴 dock 的编辑态（dock 开关 / playhead / 缩放 / 展开 / 选中）。
 *
 * <p>数据本身（timelines / activeTimelineId）仍是 {@code project.state} 的 mirror（patch 自动
 * 落地，见 stores/project.ts applyTimelineMutation）；本 store 只持"编辑器视图态"——不发 WS、
 * 不进 undo。{@code playheadMs} 等播放态刻意放这里而非 project.state，使 scrubber 拖动不触发
 * CanvasView 对 project.state 的 {@code {deep:true}} watch（docs/timeline.md §8.2 绕 deep watch）。</p>
 */
export const useTimelineStore = defineStore('timeline', () => {
    const project = useProjectStore();

    /** dock 是否展开（TopBar Film 按钮切；切 wall reset 关）。 */
    const dockOpen = ref(false);
    /** dock 高度（px，可拖 resize）。 */
    const dockHeight = ref(260);
    /** 播放头时刻（ms，钳 [0, activeTimeline.durationMs]）。scrubber / 本地播放写。 */
    const playheadMs = ref(0);
    /** 是否走本地 interpolate 预览（scrub 或本地播放期 true → CanvasView 喂插值临时 state）。 */
    const previewActive = ref(false);
    /** 本地预览是否自动播放（rAF 循环，useTimelinePlayback 驱动）。 */
    const playing = ref(false);
    /** 缩放：每 ms 多少像素。0 = 待 dock 按可见宽度 fit 计算。 */
    const pxPerMs = ref(0);
    /** 横滚：标尺左缘对应时刻（ms）。恒 0（fit 铺满），缩放/横滚留后续。 */
    const scrollMs = ref(0);
    /** 展开了属性子轨的元素 id 集合。 */
    const expandedElements = ref<Set<string>>(new Set());
    /** 当前选中的关键帧 id（单选；展开子轨微调单帧用）。 */
    const selectedKeyframeId = ref<string | null>(null);
    /** 选中的整体关键帧 key（elementId:timeMs）集合，多选。缓动 / 删除 / 拖动主操作单位。 */
    const selectedGroups = ref<Set<string>>(new Set());
    /** 自动加帧开关（PR 风"自动关键帧"）。ON + dock 开 + 有 timeline 时，画布拖元素在 playhead 自动打整体帧。 */
    const autoKeyframe = ref(true);
    /** 正在拖动且会自动加帧的元素 id 集（渲染期跟手覆盖 + dragend upsert 用；非自动加帧拖动时恒空）。 */
    const draggingElementIds = ref<Set<string>>(new Set());

    /** 所有时间轴（project.state mirror）。 */
    const timelines = computed<Timeline[]>(() => project.state?.timelines ?? []);

    /** 当前激活时间轴（project.state mirror；无激活 → null）。 */
    const activeTimeline = computed<Timeline | null>(() => {
        const st = project.state;
        if (!st || !st.timelines || !st.activeTimelineId) return null;
        return st.timelines.find(t => t.id === st.activeTimelineId) ?? null;
    });

    function timelineById(id: string): Timeline | null {
        return timelines.value.find(t => t.id === id) ?? null;
    }

    function openDock() { dockOpen.value = true; }
    function closeDock() { dockOpen.value = false; }
    function toggleDock() { dockOpen.value = !dockOpen.value; }
    function setDockHeight(px: number) { dockHeight.value = Math.max(140, Math.min(560, Math.round(px))); }

    function setPlayhead(ms: number) {
        const dur = activeTimeline.value?.durationMs ?? 0;
        playheadMs.value = Math.max(0, Math.min(dur, Math.round(ms)));
    }
    function setPreviewActive(on: boolean) { previewActive.value = on; }
    function setPlaying(on: boolean) { playing.value = on; }
    function setPxPerMs(v: number) { pxPerMs.value = v > 0 ? v : 0; }
    function setScrollMs(ms: number) { scrollMs.value = Math.max(0, Math.round(ms)); }

    function toggleExpanded(elementId: string) {
        const next = new Set(expandedElements.value);
        if (next.has(elementId)) next.delete(elementId);
        else next.add(elementId);
        expandedElements.value = next;
    }
    function expandAll(ids: string[]) { expandedElements.value = new Set(ids); }
    function isExpanded(elementId: string): boolean { return expandedElements.value.has(elementId); }

    function selectKeyframe(id: string | null) {
        selectedKeyframeId.value = id;
        if (id) selectedGroups.value = new Set();   // 单属性帧与整体帧选中互斥
    }

    /** 整体关键帧选中。additive（Shift）= 切换；否则单选替换。同时清单帧选中。 */
    function selectGroup(key: string, additive = false) {
        if (additive) {
            const next = new Set(selectedGroups.value);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            selectedGroups.value = next;
        } else {
            selectedGroups.value = new Set([key]);
        }
        selectedKeyframeId.value = null;
    }
    function selectGroups(keys: string[]) { selectedGroups.value = new Set(keys); }
    function clearGroups() { selectedGroups.value = new Set(); }
    function isGroupSelected(key: string): boolean { return selectedGroups.value.has(key); }

    function setAutoKeyframe(on: boolean) { autoKeyframe.value = on; }
    function toggleAutoKeyframe() { autoKeyframe.value = !autoKeyframe.value; }
    function setDraggingElementIds(ids: Set<string>) { draggingElementIds.value = ids; }

    /** 清时间轴编辑态（dock 开关 + 播放态 + 缩放 + 选中 + 展开）。 */
    function reset() {
        dockOpen.value = false;
        playheadMs.value = 0;
        previewActive.value = false;
        playing.value = false;
        pxPerMs.value = 0;
        scrollMs.value = 0;
        expandedElements.value = new Set();
        selectedKeyframeId.value = null;
        selectedGroups.value = new Set();
        autoKeyframe.value = true;
        draggingElementIds.value = new Set();
    }

    // 切 wall：project.reset() 置 state=null（project.reset 本身不碰时间轴编辑态）→ 自动清。
    // 自包含，不依赖外部 reset 链路记得调本 store。
    watch(() => project.state === null, (isNull) => { if (isNull) reset(); });

    // 切换激活时间轴 / 时长缩短：playhead 越界则钳回。
    watch(activeTimeline, (tl) => {
        if (!tl) { playheadMs.value = 0; return; }
        if (playheadMs.value > tl.durationMs) playheadMs.value = tl.durationMs;
    });

    return {
        dockOpen, dockHeight, playheadMs, previewActive, playing, pxPerMs, scrollMs,
        expandedElements, selectedKeyframeId, selectedGroups, autoKeyframe, draggingElementIds,
        timelines, activeTimeline,
        timelineById,
        openDock, closeDock, toggleDock, setDockHeight,
        setPlayhead, setPreviewActive, setPlaying, setPxPerMs, setScrollMs,
        toggleExpanded, expandAll, isExpanded, selectKeyframe,
        selectGroup, selectGroups, clearGroups, isGroupSelected,
        setAutoKeyframe, toggleAutoKeyframe, setDraggingElementIds,
        reset,
    };
});
