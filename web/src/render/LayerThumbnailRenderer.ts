/**
 * M8 远期 TODO 项 1：图层缩略图渲染器。
 *
 * <p>设计思路：</p>
 * <ul>
 *   <li>复用 {@link renderProjectState} 单 layer 模式 —— 把目标 layer 包成"伪 ProjectState"
 *       渲染到全分辨率 off-canvas（透明背景：把 canvas.background 强制设为透明），
 *       再缩放到 {@link THUMBNAIL_SIZE}×{@link THUMBNAIL_SIZE} 输出 dataURL。</li>
 *   <li>缓存 key = layerId；hash = elements 简单序列化签名（元素数量 + 各 id + 各
 *       x/y/w/h/rotation/visible 简化值）。任意元素增删改都会改 hash → 重 render。</li>
 *   <li>背景棋盘格交给 CSS（缩略图本身始终透明），节约一次重画。</li>
 * </ul>
 *
 * <p><b>不与后端镜像：</b>缩略图只在前端 UI 显示，无任何协议涉及。</p>
 */

import type { Element, Layer, ProjectState } from '@/types/protocol';
import { renderProjectState } from './PreviewRenderer';

/** 缩略图正方形尺寸（设备像素）。LayerPanel 默认 32×32 显示；2× DPR 屏幕仍清晰。 */
export const THUMBNAIL_SIZE = 64;

interface CacheEntry {
    hash: string;
    dataUrl: string;
}

const cache = new Map<string, CacheEntry>();

/** 计算 layer 的"内容签名"：变了即缩略图需重 render。 */
function computeLayerHash(layer: Layer): string {
    // 长度 + 每 element 的关键视觉字段拼接。不需 SHA；JS string compare 直接 O(n)。
    // 包含 visible（元素隐藏会让缩略图视觉变化）+ 几何（x/y/w/h/rotation）+ id 列表。
    const parts: string[] = [
        `n=${layer.elements.length}`,
        `op=${layer.opacity}`,
        `bm=${layer.blendMode}`,
        `vis=${layer.visible ? 1 : 0}`,
    ];
    for (const e of layer.elements) {
        // 用 JSON.stringify 整 element 太重；只签关键字段；改 fill/text 内容也应触发重画
        // → 简单兜底：把整 element 序列化（thumbnail 不会被很高频调用，元素数也不大）
        parts.push(JSON.stringify(e));
    }
    return parts.join('|');
}

/**
 * 构造一个仅含目标 layer 的伪 ProjectState；canvas.background 强制为完全透明，
 * 让 {@link renderProjectState} 跳过纯色 fillRect（其实它会 fillRect 一次，但 alpha=0 等同透明）。
 *
 * 复用 PreviewRenderer 完整渲染管线 → 双端一致 + dither / blend 等高级路径自动 cover。
 */
function buildSyntheticState(source: ProjectState, layer: Layer): ProjectState {
    return {
        version: source.version,
        protocolVersion: source.protocolVersion,
        canvas: {
            ...source.canvas,
            // 透明背景：solid fill alpha=0；fillToCanvasStyle 会返 #00000000，Canvas 接受
            background: { type: 'solid', color: '#00000000' },
            // 网格 / 参考线不影响 PreviewRenderer.renderProjectState 本身（CSS overlay 渲染）
        },
        layers: [{
            ...layer,
            visible: true,   // 缩略图永远显示（即便 layer 本身 visible=false 也展示其内容）
            opacity: 1,      // 缩略图无 layer-level fade
            blendMode: 'normal', // 缩略图不参与全画布混合
            elements: layer.elements,
        }],
        activeLayerId: layer.id,
        history: source.history,
    } as ProjectState;
}

/**
 * 渲染（或从 cache 取）单 layer 的 64×64 dataURL。
 *
 * <p>DEV-only perf log：首次 render / cache miss 会 console.log 耗时；正常 reuse 静默。</p>
 */
export function renderLayerThumbnail(
    state: ProjectState | null,
    layer: Layer,
): string | null {
    if (!state || !layer) return null;
    const hash = computeLayerHash(layer);
    const cached = cache.get(layer.id);
    if (cached && cached.hash === hash) return cached.dataUrl;

    const t0 = import.meta.env.DEV ? performance.now() : 0;

    const widthPx = state.canvas.widthMaps * 128;
    const heightPx = state.canvas.heightMaps * 128;
    if (widthPx <= 0 || heightPx <= 0) return null;

    // 1) 全分辨率 off-canvas（透明背景 + 单 layer）
    const full = document.createElement('canvas');
    full.width = widthPx;
    full.height = heightPx;
    const fg = full.getContext('2d');
    if (!fg) return null;
    const synthetic = buildSyntheticState(state, layer);
    renderProjectState(fg, synthetic);

    // 2) 缩放到 THUMBNAIL_SIZE×THUMBNAIL_SIZE（保持宽高比，居中 letterbox）
    const thumb = document.createElement('canvas');
    thumb.width = THUMBNAIL_SIZE;
    thumb.height = THUMBNAIL_SIZE;
    const tg = thumb.getContext('2d');
    if (!tg) return null;
    // 缩略图允许平滑（hi-quality）让缩小后视觉更柔；与渲染器主 path（imageSmoothingEnabled=false）独立
    tg.imageSmoothingEnabled = true;
    tg.imageSmoothingQuality = 'medium';
    const ratio = Math.min(THUMBNAIL_SIZE / widthPx, THUMBNAIL_SIZE / heightPx);
    const drawW = Math.max(1, Math.round(widthPx * ratio));
    const drawH = Math.max(1, Math.round(heightPx * ratio));
    const offX = Math.round((THUMBNAIL_SIZE - drawW) / 2);
    const offY = Math.round((THUMBNAIL_SIZE - drawH) / 2);
    tg.drawImage(full, offX, offY, drawW, drawH);

    const dataUrl = thumb.toDataURL('image/png');
    cache.set(layer.id, { hash, dataUrl });

    if (import.meta.env.DEV) {
        const dt = performance.now() - t0;
        // eslint-disable-next-line no-console
        console.debug(
            `[LayerThumbnail] rendered layer ${layer.id} (${layer.elements.length} el) `
            + `in ${dt.toFixed(1)}ms`);
    }
    return dataUrl;
}

/**
 * 主动 invalidate（用户切 layer / element op 完成等场景可手动调；
 * 但常规路径靠 hash 自动失效即可，**通常不必显式调用**）。
 */
export function invalidateLayerThumbnail(layerId: string): void {
    cache.delete(layerId);
}

/** wall 切换 / store reset 时清掉所有 cache，避免内存泄漏。 */
export function clearLayerThumbnailCache(): void {
    cache.clear();
}

// 让 ESLint 不抱怨未使用的 Element 类型导入（仅类型提示用）。
export type _ = Element;
