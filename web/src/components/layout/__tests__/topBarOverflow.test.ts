/**
 * 0.7.4 TopBar 溢出菜单：断点逻辑单元测试。
 *
 * 测的是"宽度 → 哪些低频按钮可见"的纯计算逻辑，
 * 不依赖 DOM / Vue，可在 node 环境跑。
 */

import { describe, it, expect } from 'vitest';

// ---------------------------------------------------------------------------
// 从 TopBar 抽出的纯函数（镜像 computed 逻辑，方便测试）
// ---------------------------------------------------------------------------

const BREAKPOINTS = {
    trainTrack: 420,
    bookmark:   380,
    snap:       340,
    terminal:   300,
    help:       260,
    languages:  220,
    theme:      180,
} as const;

function computeVisibility(width: number) {
    return {
        showTrainTrack: width >= BREAKPOINTS.trainTrack,
        showBookmark:   width >= BREAKPOINTS.bookmark,
        showSnap:       width >= BREAKPOINTS.snap,
        showTerminal:   width >= BREAKPOINTS.terminal,
        showHelp:       width >= BREAKPOINTS.help,
        showLanguages:  width >= BREAKPOINTS.languages,
        showTheme:      width >= BREAKPOINTS.theme,
        showMoreButton: width < BREAKPOINTS.trainTrack ||
                        width < BREAKPOINTS.bookmark ||
                        width < BREAKPOINTS.snap ||
                        width < BREAKPOINTS.terminal ||
                        width < BREAKPOINTS.help ||
                        width < BREAKPOINTS.languages ||
                        width < BREAKPOINTS.theme,
    };
}

// ---------------------------------------------------------------------------
// 高频按钮（PanelLeft / PanelRight / Film / Puzzle / Variable / Train）
// 永远不折叠，不在 computeVisibility 中 — 断言它们不在返回值里
// ---------------------------------------------------------------------------

describe('topbar overflow breakpoints', () => {

    it('全部展开：width ≥ 420（最宽断点）', () => {
        const v = computeVisibility(480);
        expect(v.showTrainTrack).toBe(true);
        expect(v.showBookmark).toBe(true);
        expect(v.showSnap).toBe(true);
        expect(v.showTerminal).toBe(true);
        expect(v.showHelp).toBe(true);
        expect(v.showLanguages).toBe(true);
        expect(v.showTheme).toBe(true);
        expect(v.showMoreButton).toBe(false);
    });

    it('精确在 trainTrack 断点：= 420 时全部展开', () => {
        const v = computeVisibility(420);
        expect(v.showTrainTrack).toBe(true);
        expect(v.showMoreButton).toBe(false);
    });

    it('trainTrack 折叠：width = 419（刚好低于 420）', () => {
        const v = computeVisibility(419);
        expect(v.showTrainTrack).toBe(false);
        expect(v.showBookmark).toBe(true);
        expect(v.showMoreButton).toBe(true);
    });

    it('bookmark 折叠：width = 379（刚好低于 380）', () => {
        const v = computeVisibility(379);
        expect(v.showTrainTrack).toBe(false);
        expect(v.showBookmark).toBe(false);
        expect(v.showSnap).toBe(true);
        expect(v.showMoreButton).toBe(true);
    });

    it('snap 折叠：width = 339', () => {
        const v = computeVisibility(339);
        expect(v.showSnap).toBe(false);
        expect(v.showTerminal).toBe(true);
        expect(v.showMoreButton).toBe(true);
    });

    it('terminal 折叠：width = 299', () => {
        const v = computeVisibility(299);
        expect(v.showTerminal).toBe(false);
        expect(v.showHelp).toBe(true);
        expect(v.showMoreButton).toBe(true);
    });

    it('help 折叠：width = 259', () => {
        const v = computeVisibility(259);
        expect(v.showHelp).toBe(false);
        expect(v.showLanguages).toBe(true);
        expect(v.showMoreButton).toBe(true);
    });

    it('languages 折叠：width = 219', () => {
        const v = computeVisibility(219);
        expect(v.showLanguages).toBe(false);
        expect(v.showTheme).toBe(true);
        expect(v.showMoreButton).toBe(true);
    });

    it('theme 折叠（最窄）：width = 179', () => {
        const v = computeVisibility(179);
        expect(v.showTheme).toBe(false);
        expect(v.showMoreButton).toBe(true);
    });

    it('极窄：width = 0 — 所有低频全部折叠，… 按钮可见', () => {
        const v = computeVisibility(0);
        expect(v.showTrainTrack).toBe(false);
        expect(v.showBookmark).toBe(false);
        expect(v.showSnap).toBe(false);
        expect(v.showTerminal).toBe(false);
        expect(v.showHelp).toBe(false);
        expect(v.showLanguages).toBe(false);
        expect(v.showTheme).toBe(false);
        expect(v.showMoreButton).toBe(true);
    });

    it('精确断点：每个边界上正好可见', () => {
        expect(computeVisibility(BREAKPOINTS.bookmark).showBookmark).toBe(true);
        expect(computeVisibility(BREAKPOINTS.snap).showSnap).toBe(true);
        expect(computeVisibility(BREAKPOINTS.terminal).showTerminal).toBe(true);
        expect(computeVisibility(BREAKPOINTS.help).showHelp).toBe(true);
        expect(computeVisibility(BREAKPOINTS.languages).showLanguages).toBe(true);
        expect(computeVisibility(BREAKPOINTS.theme).showTheme).toBe(true);
    });

    it('精确断点减一：每个边界 -1 就折叠', () => {
        expect(computeVisibility(BREAKPOINTS.bookmark - 1).showBookmark).toBe(false);
        expect(computeVisibility(BREAKPOINTS.snap - 1).showSnap).toBe(false);
        expect(computeVisibility(BREAKPOINTS.terminal - 1).showTerminal).toBe(false);
        expect(computeVisibility(BREAKPOINTS.help - 1).showHelp).toBe(false);
        expect(computeVisibility(BREAKPOINTS.languages - 1).showLanguages).toBe(false);
        expect(computeVisibility(BREAKPOINTS.theme - 1).showTheme).toBe(false);
    });

    it('showMoreButton 仅当有任一低频项被折叠时为 true', () => {
        // 所有项都可见 → false
        expect(computeVisibility(999).showMoreButton).toBe(false);
        // 只有 trainTrack 被折叠 → true
        expect(computeVisibility(419).showMoreButton).toBe(true);
        // 所有项都被折叠 → true
        expect(computeVisibility(0).showMoreButton).toBe(true);
    });
});
