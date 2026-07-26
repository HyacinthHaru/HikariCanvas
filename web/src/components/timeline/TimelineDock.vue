<script setup lang="ts">
/**
 * AE 风时间轴底部 dock。
 *
 * <p>dock 布局 + scrubber 60fps 本地预览 + 本地播放 + 缓动曲线编辑器 + timeline 设置。
 * AE/PR 风整体关键帧：元素行直接显示/选/删/缓动一个"整体关键帧"——该时刻所有 transform
 * 几何属性（x/y/w/h/rotation/opacity）的关键帧聚合成一个块；缓动统一应用到所有 transform → x/y
 * 进度同步 → 运动轨迹保持直线（只速度按缓动变，修"调缓动把轨迹掰弯"）。展开元素看 per-property
 * 子轨（只读灰显，单属性微调留后续）。</p>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useEventListener } from '@vueuse/core';
import {
    Play, Pause, SkipBack, Plus, ChevronRight, ChevronDown, Film, X, Trash2, Spline, Settings, CircleDot,
} from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useUiStore } from '@/stores/ui';
import { useTimelineStore } from '@/stores/timeline';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { useTimelinePlayback } from '@/composables/useTimelinePlayback';
import {
    keyframeablePropertiesFor,
    computeRulerTicks,
    msToPx,
    pxToMs,
    formatTimeLabel,
    clampTimeMs,
    LOOP_MODES,
    snapToFrame,
    aggregateTransformKeyframes,
    transformKeyframeKey,
    groupsInMarquee,
    formatClock,
    TRIGGER_TYPES,
    buildTrigger,
    triggerNeedsVariable,
    type TransformKeyframe,
} from './timelineLogic';
import { useTimelineAuthoring } from '@/composables/useTimelineAuthoring';
import { useLockGuard } from '@/composables/useLockGuard';
import EasingCurveEditor from './EasingCurveEditor.vue';
import VariablePicker from '@/components/variables/VariablePicker.vue';
import type { Easing, LoopMode, TriggerConfig, TriggerType } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
const store = useTimelineStore();
const ws = getWsClient();
const { t } = useI18n();
const playback = useTimelinePlayback();
const { upsertTransformKeyframe } = useTimelineAuthoring();
/**
 * 画板锁定守卫。后端按纪律不看 lock、编辑 op 一律放行，前端是锁的唯一执行者 ——
 * 时间轴这一整块以前一处都没接，锁定的作品照样能拖帧 / 删帧 / 改时长，等于锁没锁上。
 * 播放、拖播放头、展开轨道、框选这类只读操作不受影响。
 */
const lockGuard = useLockGuard();
const locked = lockGuard.readonly;

const net = useNetworkStore();

/**
 * 时间轴的一条 WS 操作没成功时，给用户一句提示。
 *
 * <p>以前这些 catch 全是空的，断线 / 服务端拒绝 / 5 秒等不到回执时界面一声不吭，
 * 用户看到的还是"改好了"的样子，实际服务端根本没这回事。业务失败走 lastError 通道
 * （状态栏有独立提示位），不染红连接指示灯。</p>
 */
function reportTimelineOpFailed(err: unknown): void {
    const reason = err instanceof Error ? err.message : String(err);
    const msg = t.value.timeline.opFailed;
    net.lastError = msg;
    net.pushLog('err', `${msg} (${reason})`);
}

const MANUAL_TRIGGER: TriggerConfig = { type: 'manual', params: {} };
const ROW_H = 28;
const RULER_H = 24;
const LABEL_W = 220;

const tl = computed(() => store.activeTimeline);
const durationMs = computed(() => tl.value?.durationMs ?? 0);
const playing = computed(() => store.playing);

// ---------- 轨道展平：element 主行（整体帧）+ 展开后 per-property 子行（只读，后续微调） ----------
interface FlatRow {
    kind: 'element' | 'property';
    elementId: string;
    elementType: string | null;
    groups?: TransformKeyframe[];
    property?: string;
    keyframes?: { id: string; timeMs: number }[];
}
const flatRows = computed<FlatRow[]>(() => {
    const t0 = tl.value;
    if (!t0) return [];
    // 选中元素 ∪ 已有轨道元素——空 timeline 也能从选中元素加首帧（AE 工作流）
    const ids = new Set<string>([...ui.selectedIds, ...Object.keys(t0.tracks ?? {})]);
    const rows: FlatRow[] = [];
    for (const elementId of [...ids].sort()) {
        const el = project.elementById(elementId);
        const elementType = el?.type ?? null;
        rows.push({ kind: 'element', elementId, elementType, groups: aggregateTransformKeyframes(t0, elementId) });
        if (store.isExpanded(elementId)) {
            const trackKfs = t0.tracks?.[elementId] ?? [];
            const props = el ? keyframeablePropertiesFor(el) : [...new Set(trackKfs.map(k => k.property))];
            for (const property of props) {
                const keyframes = trackKfs.filter(k => k.property === property)
                    .map(k => ({ id: k.id, timeMs: k.timeMs })).sort((a, b) => a.timeMs - b.timeMs);
                rows.push({ kind: 'property', elementId, elementType, property, keyframes });
            }
        }
    }
    return rows;
});

// ---------- 时间↔像素 ----------
const timeAreaRef = ref<HTMLElement | null>(null);
const timeAreaWidth = ref(0);
let resizeObs: ResizeObserver | null = null;
watch([timeAreaWidth, durationMs], () => {
    const w = timeAreaWidth.value, dur = durationMs.value;
    store.setPxPerMs(dur > 0 && w > 0 ? w / dur : 0);
}, { immediate: true });
const ticks = computed(() => computeRulerTicks(durationMs.value, store.pxPerMs, timeAreaWidth.value, store.scrollMs));
const playheadX = computed(() => msToPx(store.playheadMs, store.pxPerMs, store.scrollMs));
function kfX(timeMs: number): number { return msToPx(timeMs, store.pxPerMs, store.scrollMs); }
function timeFromClientX(clientX: number): number {
    const el = timeAreaRef.value;
    if (!el) return store.playheadMs;
    return pxToMs(clientX - el.getBoundingClientRect().left, store.pxPerMs, store.scrollMs);
}

// ---------- scrubber ----------
const scrubbing = ref(false);
let scrubPointerId = -1;
function onScrubDown(e: PointerEvent): void {
    if (!tl.value || store.pxPerMs <= 0) return;
    if (store.playing) playback.pause();
    scrubbing.value = true; scrubPointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    store.setPreviewActive(true);
    store.setPlayhead(clampTimeMs(timeFromClientX(e.clientX), durationMs.value));
    e.preventDefault();
}
function onScrubMove(e: PointerEvent): void {
    if (!scrubbing.value) return;
    store.setPlayhead(clampTimeMs(timeFromClientX(e.clientX), durationMs.value));
}
function onScrubUp(e: PointerEvent): void {
    if (!scrubbing.value) return;
    scrubbing.value = false;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(scrubPointerId); } catch { /* ignore */ }
    scrubPointerId = -1;
}

// ---------- 整体关键帧：选中 / 拖动改时刻 / 缓动同步 / 删除 / 加帧 ----------
function isGroupSel(elementId: string, timeMs: number): boolean {
    return store.isGroupSelected(transformKeyframeKey(elementId, timeMs));
}
const hasSelectedGroup = computed(() => store.selectedGroups.size > 0);

function groupKeyframeIds(elementId: string, timeMs: number): string[] {
    if (!tl.value) return [];
    return aggregateTransformKeyframes(tl.value, elementId).find(g => g.timeMs === timeMs)?.keyframeIds ?? [];
}

// 整体帧块 pointer 拖动改时刻：拖过阈值 = move，否则 = 点击选中。拖动期只给被拖块加视觉偏移
// （blockLeft，不改 timeMs → :key 稳定、不丢 pointer capture）；松手才乐观挪 + 发 keyframe.move。
const blockDrag = ref<{ elementId: string; origTimeMs: number; startX: number; curTimeMs: number; moved: boolean; keyframeIds: string[] } | null>(null);
let blockPointerId = -1;
function onBlockPointerDown(e: PointerEvent, elementId: string, timeMs: number): void {
    if (!tl.value || store.pxPerMs <= 0) return;
    e.stopPropagation();   // 不触发轨道区框选
    blockDrag.value = {
        elementId, origTimeMs: timeMs, startX: e.clientX, curTimeMs: timeMs,
        moved: false, keyframeIds: groupKeyframeIds(elementId, timeMs),
    };
    blockPointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
}
function onBlockPointerMove(e: PointerEvent): void {
    const d = blockDrag.value;
    if (!d) return;
    const dxPx = e.clientX - d.startX;
    if (!d.moved && Math.abs(dxPx) < 3) return;
    d.moved = true;
    d.curTimeMs = snapToFrame(d.origTimeMs + dxPx / store.pxPerMs, tl.value?.fps ?? 20, durationMs.value);
}
function onBlockPointerUp(e: PointerEvent): void {
    const d = blockDrag.value;
    if (!d) return;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(blockPointerId); } catch { /* ignore */ }
    blockPointerId = -1;
    if (d.moved && d.curTimeMs !== d.origTimeMs && tl.value && lockGuard.guardMutation(t.value.timeline.dockTitle)) {
        const track = tl.value.tracks?.[d.elementId];
        // 整组帧共享 coalesceKey → 后端合并成一步撤销（与拉就设同理）
        const coalesceKey = `integ-move:${d.elementId}:${d.origTimeMs}`;
        const movedTo = d.curTimeMs;
        const movedFrom = d.origTimeMs;
        const elementId = d.elementId;
        for (const kfId of d.keyframeIds) {
            const kf = track?.find(k => k.id === kfId);
            if (kf) (kf as { timeMs: number }).timeMs = movedTo;   // 乐观本地挪，消 keyframe.move 往返闪烁
            ws.sendKeyframeMove(tl.value.id, kfId, movedTo, coalesceKey)
                .catch((e: unknown) => {
                    // 服务端没收下这一挪就把本地挪回去，否则画面上帧停在新位置、
                    // 服务端还在老位置，之后所有操作都打在错的时刻上。
                    // 只有本地值仍是我们刚写进去的那个才回滚——期间要是有新 patch 落地，
                    // 那是更新的真相，别覆盖掉。
                    if (kf && (kf as { timeMs: number }).timeMs === movedTo) {
                        (kf as { timeMs: number }).timeMs = movedFrom;
                        store.selectGroup(transformKeyframeKey(elementId, movedFrom));
                    }
                    reportTimelineOpFailed(e);
                });
        }
        store.selectGroup(transformKeyframeKey(d.elementId, movedTo));
    } else if (!d.moved) {
        store.selectGroup(transformKeyframeKey(d.elementId, d.origTimeMs), e.shiftKey);
    }
    blockDrag.value = null;
}
/** 整体帧块的 left：被拖块用拖动期临时时刻（视觉跟手，不改 timeMs 保 key 稳定），其余用真实 timeMs。 */
function blockLeft(elementId: string, timeMs: number): number {
    const d = blockDrag.value;
    if (d && d.elementId === elementId && d.origTimeMs === timeMs) return kfX(d.curTimeMs);
    return kfX(timeMs);
}

// ---------- 框选批量选中整体帧（问题 3：拉框 → Del 批量删） ----------
const marquee = ref<{ x0: number; y0: number; x1: number; y1: number } | null>(null);
let marqueePointerId = -1;
function tracksContentXY(e: PointerEvent): { x: number; y: number } | null {
    const el = tracksScrollRef.value;
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top + el.scrollTop };
}
function onTracksPointerDown(e: PointerEvent): void {
    if (e.button !== 0 || !tl.value) return;   // 仅左键；块 / 属性帧 pointerdown 已 stopPropagation
    const p = tracksContentXY(e);
    if (!p) return;
    marquee.value = { x0: p.x, y0: p.y, x1: p.x, y1: p.y };
    marqueePointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
}
function onTracksPointerMove(e: PointerEvent): void {
    if (!marquee.value) return;
    const p = tracksContentXY(e);
    if (!p) return;
    marquee.value = { ...marquee.value, x1: p.x, y1: p.y };
}
function onTracksPointerUp(e: PointerEvent): void {
    const m = marquee.value;
    if (!m) return;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(marqueePointerId); } catch { /* ignore */ }
    marqueePointerId = -1;
    const moved = Math.abs(m.x1 - m.x0) > 3 || Math.abs(m.y1 - m.y0) > 3;
    if (moved) {
        store.selectGroups(groupsInMarquee(flatRows.value, m, store.pxPerMs, store.scrollMs, ROW_H));
    } else {
        store.clearGroups();
        store.selectKeyframe(null);
    }
    marquee.value = null;
}
const marqueeStyle = computed(() => {
    const m = marquee.value;
    if (!m) return null;
    return {
        left: Math.min(m.x0, m.x1) + 'px',
        top: Math.min(m.y0, m.y1) + 'px',
        width: Math.abs(m.x1 - m.x0) + 'px',
        height: Math.abs(m.y1 - m.y0) + 'px',
    };
});

/** 恰好选中一个整体帧时返回它（缓动编辑用；多选时 null）。 */
const selectedGroupKf = computed<TransformKeyframe | null>(() => {
    if (store.selectedGroups.size !== 1 || !tl.value) return null;
    const key = [...store.selectedGroups][0];
    const sep = key.lastIndexOf(':');
    const elementId = key.slice(0, sep);
    const timeMs = Number(key.slice(sep + 1));
    return aggregateTransformKeyframes(tl.value, elementId).find(g => g.timeMs === timeMs) ?? null;
});
const selectedEasing = computed<Easing>(() => selectedGroupKf.value?.easing ?? { type: 'linear' });

function onEasingUpdate(easing: Easing): void {
    const g = selectedGroupKf.value;
    if (!g || !tl.value) return;
    if (!lockGuard.guardMutation(t.value.timeline.addEasing)) return;
    // 同步整体帧所有 transform 关键帧的缓动 → x/y 进度一致 → 轨迹直线。
    // 6 条 op 必须共享同一个 coalesceKey：不传的话后端按「每帧一个 key」回退，一次调整
    // 占 6 步撤销，而且撤一步只回滚一个属性的缓动 —— 正好造出上面这行想避免的
    // 「x/y 进度不一致 → 轨迹被掰弯」。
    const coalesceKey = `integ-easing:${g.elementId}:${g.timeMs}`;
    for (const kfId of g.keyframeIds) {
        ws.sendKeyframeUpdate(tl.value.id, kfId, { easing }, coalesceKey)
            .catch((e: unknown) => reportTimelineOpFailed(e));
    }
}
function deleteSelectedGroups(): void {
    if (!tl.value) return;
    if (!lockGuard.guardMutation(t.value.timeline.kfDeleteAria)) return;
    for (const key of store.selectedGroups) {
        const sep = key.lastIndexOf(':');
        const elementId = key.slice(0, sep);
        const timeMs = Number(key.slice(sep + 1));
        const g = aggregateTransformKeyframes(tl.value, elementId).find(x => x.timeMs === timeMs);
        if (g) for (const kfId of g.keyframeIds) {
            ws.sendKeyframeDelete(tl.value.id, kfId).catch((e: unknown) => reportTimelineOpFailed(e));
        }
    }
    store.clearGroups();
}
/**
 * 在播放头给元素所有 transform 属性各加/更新一帧（值=元素当前值），组成一个整体关键帧。
 * 与画布拖动自动加帧共用 {@code useTimelineAuthoring}：同 timeMs 已有该属性帧 → 更新值（不重复加）；否则新增。
 */
function addTransformKeyframe(elementId: string): void {
    if (!tl.value) return;
    if (!lockGuard.guardMutation(t.value.timeline.dockAddKeyframe)) return;
    const timeMs = store.playheadMs;
    upsertTransformKeyframe(tl.value, elementId, timeMs);
    store.selectGroup(transformKeyframeKey(elementId, timeMs));
}

// ---------- 缓动编辑器开关 ----------
const easingEditorOpen = ref(false);
watch(() => store.selectedGroups.size, (n) => { if (n === 0) easingEditorOpen.value = false; });

// ---------- Delete 删选中整体帧（dock 打开 + 有选中 + 焦点不在输入框）----------
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
    if (e.key !== 'Delete' && e.key !== 'Backspace') return;
    if (!store.dockOpen) return;
    // 光标在可输入的地方就别抢这一下退格。只判 tagName 会漏掉画布内联文本编辑器和右栏
    // 文本框——它们是 Lexical 的 contenteditable，根节点是 div，tagName 永远不是 INPUT，
    // 于是"在文本框里按退格删字" = 字没删掉、选中的关键帧全没了。判法与 App.vue 对齐。
    const target = e.target as HTMLElement | null;
    if (target && (target.matches?.('input, textarea, select') || target.isContentEditable)) return;
    if (store.selectedGroups.size > 0) {
        deleteSelectedGroups();
        e.preventDefault();
    } else if (store.selectedKeyframeId && tl.value) {
        // per-property 子轨选中的单个关键帧
        if (!lockGuard.guardMutation(t.value.timeline.kfDeleteAria)) return;
        ws.sendKeyframeDelete(tl.value.id, store.selectedKeyframeId).catch((e: unknown) => reportTimelineOpFailed(e));
        store.selectKeyframe(null);
        e.preventDefault();
    }
});

// ---------- dock 高度 resize ----------
const resizing = ref(false);
let resizeStartY = 0, resizeStartH = 0, resizePointerId = -1;
function onResizeDown(e: PointerEvent): void {
    resizing.value = true; resizeStartY = e.clientY; resizeStartH = store.dockHeight; resizePointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    e.preventDefault();
}
function onResizeMove(e: PointerEvent): void {
    if (!resizing.value) return;
    store.setDockHeight(resizeStartH + (resizeStartY - e.clientY));
}
function onResizeUp(e: PointerEvent): void {
    if (!resizing.value) return;
    resizing.value = false;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(resizePointerId); } catch { /* ignore */ }
    resizePointerId = -1;
}

// ---------- 左标签 ↔ 右轨道 滚动同步 ----------
const labelsScrollRef = ref<HTMLElement | null>(null);
const tracksScrollRef = ref<HTMLElement | null>(null);
function onTracksScroll(): void {
    if (labelsScrollRef.value && tracksScrollRef.value) labelsScrollRef.value.scrollTop = tracksScrollRef.value.scrollTop;
}

// ---------- 播放控制 ----------
function togglePlay(): void { if (store.playing) playback.pause(); else playback.play(); }
function rewind(): void { playback.pause(); store.setPreviewActive(true); store.setPlayhead(0); }

// ---------- 新建 timeline ----------
const creating = ref(false);
async function createTimeline(): Promise<void> {
    if (creating.value) return;
    if (!lockGuard.guardMutation(t.value.timeline.dockNew)) return;
    creating.value = true;
    try { await ws.sendTimelineCreate('', 5000, 20, 'loop', MANUAL_TRIGGER); }
    catch (e) { reportTimelineOpFailed(e); }
    finally { creating.value = false; }
}

// ---------- timeline 设置 ----------
const settingsOpen = ref(false);
const confirmDeleteTimeline = ref(false);
const settingsError = ref<string | null>(null);   // duration 等校验失败的 inline 提示
/** 该 timeline 全轨最大关键帧时刻——时长不能缩到它以下（与后端 maxKeyframeTime 校验对齐，提前给反馈）。 */
const maxKeyframeMs = computed(() => {
    const t0 = tl.value;
    if (!t0?.tracks) return 0;
    let max = 0;
    for (const track of Object.values(t0.tracks)) {
        for (const kf of track) if (kf.timeMs > max) max = kf.timeMs;
    }
    return max;
});
function updateTimeline(patch: Partial<{ name: string; durationMs: number; fps: number; loopMode: LoopMode; trigger: TriggerConfig }>): void {
    if (!tl.value) return;
    // 名称 / 时长 / 帧率 / 循环 / 触发方式共用这一个出口，锁定守卫放这里一处就全覆盖
    if (!lockGuard.guardMutation(t.value.timeline.dockSettings)) return;
    ws.sendTimelineUpdate(tl.value.id, patch).catch((e: unknown) => reportTimelineOpFailed(e));
}
function onNameChange(e: Event): void { updateTimeline({ name: (e.target as HTMLInputElement).value }); }
function onDurationChange(e: Event): void {
    const input = e.target as HTMLInputElement;
    const v = parseInt(input.value, 10);
    settingsError.value = null;
    const m = t.value.timeline as unknown as Record<string, string>;
    if (!Number.isInteger(v) || v < 100 || v > 3_600_000) {
        settingsError.value = m.errDurationRange;
        input.value = String(tl.value?.durationMs ?? '');   // 回弹到当前有效值
        return;
    }
    // 时长不能短于最后一个关键帧（后端会拒 INVALID_KEYFRAME_TIME，这里提前给清楚的原因）
    if (v < maxKeyframeMs.value) {
        settingsError.value = m.errDurationBelowKeyframe.replace('{t}', formatTimeLabel(maxKeyframeMs.value));
        input.value = String(tl.value?.durationMs ?? '');
        return;
    }
    updateTimeline({ durationMs: v });
}
function onFpsChange(e: Event): void {
    const v = parseInt((e.target as HTMLInputElement).value, 10);
    if (Number.isInteger(v) && v >= 1 && v <= 240) updateTimeline({ fps: v });
}
function onLoopChange(e: Event): void { updateTimeline({ loopMode: (e.target as HTMLSelectElement).value as LoopMode }); }
function deleteTimeline(): void {
    if (!tl.value) return;
    if (!lockGuard.guardMutation(t.value.timeline.dockDelete)) return;
    ws.sendTimelineDelete(tl.value.id).catch((e: unknown) => reportTimelineOpFailed(e));
    confirmDeleteTimeline.value = false; settingsOpen.value = false;
}
function loopModeLabel(m: string): string {
    // t 是 ComputedRef，script 内须 .value 解包
    const x = t.value.timeline as unknown as Record<string, string>;
    const map: Record<string, string> = { once: x.loopOnce, loop: x.loopLoop, pingPong: x.loopPingPong };
    return map[m] ?? m;
}

// ---------- 触发方式：手动 / 变量变化 / 到点 ----------
const triggerFullName = computed(() => tl.value?.trigger?.params?.fullName ?? '');
const draftTriggerType = ref<TriggerType>('manual');
watch(() => tl.value?.trigger?.type, (ty) => { draftTriggerType.value = (ty ?? 'manual') as TriggerType; }, { immediate: true });
const triggerPickerOpen = ref(false);

function onTriggerTypeChange(): void {
    const type = draftTriggerType.value;
    if (!triggerNeedsVariable(type)) {
        updateTimeline({ trigger: buildTrigger('manual', null) });
        triggerPickerOpen.value = false;
        return;
    }
    // 非手动：已绑变量 → 直接换 type 保留变量并持久化；否则只改草稿（select 停在选的项），等用户点
    //   下面「选择变量」按钮再开 picker。⚠ 不在 change 里自动弹 picker——select 的 change 会被 picker 的
    //   onClickOutside 当外部点击立即关掉、cancel 又把下拉重置回 manual，表现为"点了没反应、弹回默认"。
    //   显式按钮开（与 TextElementSection 同款可用模式，onClickOutside 会忽略开启那一下）。
    if (triggerFullName.value) {
        updateTimeline({ trigger: buildTrigger(type, triggerFullName.value) });
    }
}
function onTriggerVariableSelect(fullName: string): void {
    updateTimeline({ trigger: buildTrigger(draftTriggerType.value, fullName) });
    triggerPickerOpen.value = false;
}
function cancelTriggerPicker(): void {
    triggerPickerOpen.value = false;
}
function triggerTypeLabel(ty: string): string {
    const m = t.value.timeline as unknown as Record<string, string>;
    const map: Record<string, string> = {
        manual: m.triggerManual, variableChange: m.triggerVariableChange, schedule: m.triggerSchedule,
    };
    return map[ty] ?? ty;
}

watch(settingsOpen, (open) => { if (!open) { confirmDeleteTimeline.value = false; settingsError.value = null; triggerPickerOpen.value = false; } });

// ---------- 标签 ----------
function propertyLabel(p: string): string {
    const m = t.value.timeline as unknown as Record<string, string>;
    return m['prop' + p.charAt(0).toUpperCase() + p.slice(1)] ?? p;
}
function elementLabel(row: FlatRow): string {
    const short = row.elementId.startsWith('e-') ? row.elementId.slice(2) : row.elementId;
    return row.elementType ? `${short} · ${row.elementType}` : short;
}

// ---------- 宽度测量 + 生命周期 ----------
function measureWidth(): void {
    timeAreaWidth.value = tracksScrollRef.value?.clientWidth ?? timeAreaRef.value?.clientWidth ?? 0;
}
onMounted(() => {
    const el = timeAreaRef.value;
    if (el && typeof ResizeObserver !== 'undefined') {
        resizeObs = new ResizeObserver(() => measureWidth());
        resizeObs.observe(el);
        measureWidth();
    }
});
watch([tl, () => flatRows.value.length], () => { nextTick(measureWidth); });
onBeforeUnmount(() => {
    if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
    playback.exitPreview();
});
watch(() => store.dockOpen, (open) => { if (!open) playback.exitPreview(); });
</script>

<template>
  <div
    class="relative flex flex-col border-t border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none"
    :style="{ height: store.dockHeight + 'px' }"
  >
    <!-- resize handle -->
    <div
      class="absolute top-0 left-0 right-0 h-1 cursor-ns-resize hover:bg-[color:var(--accent)] z-20"
      :title="t.timeline.dockResize"
      @pointerdown="onResizeDown" @pointermove="onResizeMove" @pointerup="onResizeUp" @pointercancel="onResizeUp"
    />

    <!-- header -->
    <div class="flex items-center gap-2 px-3 shrink-0 border-b border-[color:var(--border)]" :style="{ height: '36px' }">
      <Film class="size-4 text-[color:var(--muted-foreground)]" />
      <span class="text-sm font-medium">{{ t.timeline.dockTitle }}</span>
      <template v-if="tl">
        <div class="mx-2 h-4 w-px bg-[color:var(--border)]" />
        <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="t.timeline.rewind" @click="rewind"><SkipBack class="size-4" /></button>
        <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="playing ? t.timeline.pause : t.timeline.play" @click="togglePlay">
          <Pause v-if="playing" class="size-4" /><Play v-else class="size-4" />
        </button>
        <span class="ml-1 text-xs tabular-nums text-[color:var(--muted-foreground)]">{{ formatClock(store.playheadMs) }} / {{ formatClock(durationMs) }}</span>
        <div class="mx-2 h-4 w-px bg-[color:var(--border)]" />
        <span class="text-xs truncate max-w-[180px]">{{ tl.name }}</span>
        <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ tl.fps }}fps</span>
        <div class="mx-1 h-4 w-px bg-[color:var(--border)]" />
        <button
          class="hc-btn p-1 rounded-[var(--radius-sm)] transition-colors"
          :class="store.autoKeyframe ? 'bg-[color:var(--accent)] text-[color:var(--ctp-red)]' : 'text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)]'"
          :title="t.timeline.autoKeyframe" @click="store.toggleAutoKeyframe()"
        ><CircleDot class="size-4" /></button>
        <button
          class="hc-btn p-1 rounded-[var(--radius-sm)] transition-colors"
          :class="settingsOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          :title="t.timeline.dockSettings" @click="settingsOpen = !settingsOpen"
        ><Settings class="size-4" /></button>
        <button
          v-if="store.selectedGroups.size === 1"
          class="hc-btn ml-1 p-1 rounded-[var(--radius-sm)] transition-colors disabled:opacity-40"
          :class="easingEditorOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          :disabled="locked"
          :title="t.timeline.addEasing" @click="easingEditorOpen = !easingEditorOpen"
        ><Spline class="size-4" /></button>
        <button
          v-if="hasSelectedGroup"
          class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--destructive)]/15 text-[color:var(--destructive)] disabled:opacity-40"
          :disabled="locked"
          :title="t.timeline.kfDeleteAria" @click="deleteSelectedGroups"
        ><Trash2 class="size-4" /></button>
      </template>
      <div class="flex-1" />
      <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="t.timeline.dockClose" @click="store.closeDock()"><X class="size-4" /></button>
    </div>

    <!-- 空状态 -->
    <div v-if="!tl" class="flex-1 flex flex-col items-center justify-center gap-3 text-[color:var(--muted-foreground)]">
      <span class="text-sm">{{ t.timeline.dockEmpty }}</span>
      <span class="text-xs">{{ t.timeline.dockEmptyHint }}</span>
      <button class="hc-btn inline-flex items-center gap-1 px-3 py-1.5 rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] disabled:opacity-50" :disabled="creating || locked" @click="createTimeline"><Plus class="size-4" />{{ t.timeline.dockNew }}</button>
    </div>

    <!-- 主体 -->
    <div v-else class="flex-1 flex min-h-0">
      <!-- 左树 -->
      <div class="shrink-0 flex flex-col border-r border-[color:var(--border)]" :style="{ width: LABEL_W + 'px' }">
        <div class="shrink-0 border-b border-[color:var(--border)]" :style="{ height: RULER_H + 'px' }" />
        <div ref="labelsScrollRef" class="flex-1 overflow-hidden">
          <div v-for="(row, i) in flatRows" :key="i" class="group flex items-center px-2 text-xs border-b border-[color:var(--border)]/40" :style="{ height: ROW_H + 'px' }">
            <template v-if="row.kind === 'element'">
              <button class="mr-1 text-[color:var(--muted-foreground)] hover:text-[color:var(--foreground)]" @click="store.toggleExpanded(row.elementId)">
                <ChevronDown v-if="store.isExpanded(row.elementId)" class="size-3.5" /><ChevronRight v-else class="size-3.5" />
              </button>
              <span class="font-medium truncate flex-1">{{ elementLabel(row) }}</span>
              <button v-if="!locked" class="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-[color:var(--accent)] text-[color:var(--muted-foreground)]" :title="t.timeline.dockAddKeyframe" @click="addTransformKeyframe(row.elementId)"><Plus class="size-3.5" /></button>
            </template>
            <template v-else>
              <span class="pl-6 text-[color:var(--muted-foreground)] truncate">{{ propertyLabel(row.property!) }}</span>
            </template>
          </div>
        </div>
      </div>

      <!-- 右时间区 -->
      <div ref="timeAreaRef" class="flex-1 flex flex-col min-w-0 relative">
        <!-- 标尺（可拖 scrub） -->
        <div
          class="shrink-0 relative border-b border-[color:var(--border)] cursor-pointer overflow-hidden"
          :style="{ height: RULER_H + 'px' }"
          @pointerdown="onScrubDown" @pointermove="onScrubMove" @pointerup="onScrubUp" @pointercancel="onScrubUp"
        >
          <div v-for="tick in ticks" :key="tick.timeMs" class="absolute top-0 bottom-0 border-l border-[color:var(--border)]/60" :style="{ left: tick.x + 'px' }">
            <span class="absolute top-0.5 left-1 text-[10px] text-[color:var(--muted-foreground)] whitespace-nowrap">{{ tick.label }}</span>
          </div>
        </div>

        <!-- 轨道（空白拉框选整体帧；块 / 属性帧 pointerdown 已 stopPropagation 不触发框选） -->
        <div
          ref="tracksScrollRef" class="flex-1 overflow-y-auto overflow-x-hidden relative" @scroll="onTracksScroll"
          @pointerdown="onTracksPointerDown" @pointermove="onTracksPointerMove" @pointerup="onTracksPointerUp" @pointercancel="onTracksPointerUp"
        >
          <div v-if="flatRows.length === 0" class="absolute inset-0 flex items-center justify-center px-4 text-center text-xs text-[color:var(--muted-foreground)] pointer-events-none">{{ t.timeline.dockSelectHint }}</div>
          <div
            v-for="(row, i) in flatRows"
            :key="i"
            class="relative border-b border-[color:var(--border)]/40"
            :class="row.kind === 'element' ? 'bg-[color:var(--muted)]/20' : ''"
            :style="{ height: ROW_H + 'px' }"
          >
            <!-- 元素行：整体关键帧块（点选 / Shift 多选 / 横拖改时刻 / 删 / 缓动） -->
            <template v-if="row.kind === 'element'">
              <div
                v-for="g in row.groups"
                :key="g.timeMs"
                class="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rotate-45 cursor-ew-resize border"
                :class="isGroupSel(row.elementId, g.timeMs)
                  ? 'bg-[color:var(--ctp-yellow)] border-[color:var(--foreground)] z-10'
                  : 'bg-[color:var(--ctp-mauve)] border-[color:var(--card)]'"
                :style="{ left: blockLeft(row.elementId, g.timeMs) + 'px' }"
                :title="formatTimeLabel(g.timeMs)"
                @pointerdown="onBlockPointerDown($event, row.elementId, g.timeMs)"
                @pointermove="onBlockPointerMove"
                @pointerup="onBlockPointerUp"
                @pointercancel="onBlockPointerUp"
                @dblclick.stop
              />
            </template>
            <!-- 属性子行：per-property 关键帧块（可点选，选中后 Del 删单帧） -->
            <template v-else>
              <div
                v-for="kf in row.keyframes"
                :key="kf.id"
                class="absolute top-1/2 size-2.5 -translate-x-1/2 -translate-y-1/2 rotate-45 cursor-pointer border"
                :class="store.selectedKeyframeId === kf.id
                  ? 'bg-[color:var(--ctp-yellow)] border-[color:var(--foreground)] z-10'
                  : 'bg-[color:var(--muted-foreground)]/60 border-[color:var(--card)]'"
                :style="{ left: kfX(kf.timeMs) + 'px' }"
                :title="formatTimeLabel(kf.timeMs)"
                @pointerdown.stop
                @click.stop="store.selectKeyframe(kf.id)"
              />
            </template>
          </div>
          <!-- 框选矩形（轨道内容坐标，随内容滚动） -->
          <div v-if="marqueeStyle" class="absolute border border-[color:var(--ctp-blue)] bg-[color:var(--ctp-blue)]/10 pointer-events-none z-20" :style="marqueeStyle" />
        </div>

        <!-- 播放头竖线（贯穿标尺 + 轨道） -->
        <div class="absolute top-0 bottom-0 w-px bg-[color:var(--ctp-red)] pointer-events-none z-10" :style="{ left: playheadX + 'px' }">
          <div class="absolute -left-[4px] top-0 size-0 border-l-[5px] border-r-[5px] border-t-[6px] border-l-transparent border-r-transparent" style="border-top-color: var(--ctp-red)" />
        </div>
      </div>
    </div>

    <!-- 缓动曲线编辑器 popover（选中单个整体帧 + header 曲线按钮打开） -->
    <div
      v-if="easingEditorOpen && selectedGroupKf"
      class="absolute left-2 bottom-full mb-1 z-50 rounded-[var(--radius)] border border-[color:var(--border)] bg-[color:var(--card)] shadow-lg"
    >
      <EasingCurveEditor :model-value="selectedEasing" @update:model-value="onEasingUpdate" />
    </div>

    <!-- timeline 设置 popover -->
    <div
      v-if="settingsOpen && tl"
      class="absolute right-2 top-11 z-50 w-64 max-h-[calc(100%-3.5rem)] overflow-y-auto p-3 flex flex-col gap-2 rounded-[var(--radius)] border border-[color:var(--border)] bg-[color:var(--card)] shadow-lg text-xs"
    >
      <label class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newName }}</span>
        <input :value="tl.name" :disabled="locked" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent disabled:opacity-40" @change="onNameChange" />
      </label>
      <div class="flex gap-2">
        <label class="flex flex-col gap-1 flex-1">
          <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newDuration }}</span>
          <input :value="tl.durationMs" :disabled="locked" type="number" min="100" max="3600000" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent disabled:opacity-40" @change="onDurationChange" />
        </label>
        <label class="flex flex-col gap-1 w-16">
          <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newFps }}</span>
          <input :value="tl.fps" :disabled="locked" type="number" min="1" max="240" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent disabled:opacity-40" @change="onFpsChange" />
        </label>
      </div>
      <p v-if="settingsError" class="text-[10px] text-[color:var(--destructive)] leading-snug">{{ settingsError }}</p>
      <label class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newLoop }}</span>
        <select :value="tl.loopMode" :disabled="locked" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent disabled:opacity-40" @change="onLoopChange">
          <option v-for="m in LOOP_MODES" :key="m" :value="m">{{ loopModeLabel(m) }}</option>
        </select>
      </label>
      <!-- 触发方式：手动 / 变量变化 / 到点 -->
      <label class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.triggerLabel }}</span>
        <select v-model="draftTriggerType" :disabled="locked" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent disabled:opacity-40" @change="onTriggerTypeChange">
          <option v-for="ty in TRIGGER_TYPES" :key="ty" :value="ty">{{ triggerTypeLabel(ty) }}</option>
        </select>
      </label>
      <div v-if="triggerNeedsVariable(draftTriggerType)" class="flex flex-col gap-1">
        <button
          class="px-2 py-1 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] text-left truncate disabled:opacity-40"
          :class="triggerFullName ? '' : 'text-[color:var(--muted-foreground)] italic'"
          :disabled="locked"
          @click="triggerPickerOpen = true"
        >{{ triggerFullName || t.timeline.triggerPickHint }}</button>
        <span class="text-[10px] text-[color:var(--muted-foreground)] leading-snug">{{ t.timeline.triggerVarHint }}</span>
      </div>
      <button
        class="mt-1 inline-flex items-center justify-center gap-1 px-2 py-1 rounded border-t border-[color:var(--border)] text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/15 disabled:opacity-40"
        :disabled="locked"
        @click="confirmDeleteTimeline ? deleteTimeline() : (confirmDeleteTimeline = true)"
      >
        <Trash2 class="size-3.5" />{{ confirmDeleteTimeline ? t.timeline.dockDeleteConfirm : t.timeline.dockDelete }}
      </button>
    </div>

    <!-- 触发变量选择器：VariablePicker 自身向下展开（CSS top:100%），而 dock 在屏幕底部，
         直接放 dock 内会溢出到视口外看不见。用 fixed 居中浮层给它一个有宽度的定位父级，在可见区展开。 -->
    <div v-if="triggerPickerOpen" class="fixed left-1/2 -translate-x-1/2 top-20 w-[min(420px,90vw)] z-[100]">
      <VariablePicker :wall-id="project.wallId" @select="onTriggerVariableSelect" @close="cancelTriggerPicker" />
    </div>
  </div>
</template>
