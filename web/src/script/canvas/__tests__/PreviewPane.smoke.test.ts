// @vitest-environment happy-dom
/**
 * 0.7.1-P3-T3：PreviewPane 渲染 smoke。
 *
 * <p>真实 mount：(1) 空态 project.state==null → 占位「未选择墙」+ 不调 renderProjectState；
 * (2) 有墙 state → canvas.getContext('2d') 被调 + renderProjectState(ctx, state) 被调。
 * happy-dom 的 canvas 不实装 2D context，故 stub getContext 返假 ctx；RAF 同步化让重绘当帧发生。</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

// renderProjectState mock —— 断言被调（以及调用入参）。
// vi.hoisted：mock 工厂会被提升到文件顶，普通顶层 const 在工厂里访问会 TDZ；用 hoisted 让
// fn 与工厂同被提升。PreviewRenderer 还被 LayerThumbnailRenderer 引用，必须整体 mock 掉。
const { renderProjectState } = vi.hoisted(() => ({ renderProjectState: vi.fn() }));
vi.mock('@/render/PreviewRenderer', () => ({
    renderProjectState,
    // project store reset() 会调 resetImageCaches；保留为 noop 避免 import 崩。
    resetImageCaches: () => {},
}));

// wsClient mock —— scriptEdit store import 它（updateActionField → debounce → doSave 才用，
// 但 mock 掉避免 import 真模块时连带 side-effect）。波2 测试不触发 send。
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptCreate: vi.fn(() => Promise.resolve()),
        sendScriptUpdate: vi.fn(() => Promise.resolve()),
        sendScriptDelete: vi.fn(() => Promise.resolve()),
        sendScriptEnable: vi.fn(() => Promise.resolve()),
    }),
}));

import PreviewPane from '../PreviewPane.vue';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useScriptStore } from '@/stores/scripts';
import type { ProjectState, ScriptRule, ScriptAction, Element } from '@/types/protocol';

// RAF 同步化：让 requestPaint 当帧执行 paint。
const realRaf = globalThis.requestAnimationFrame;
const realCaf = globalThis.cancelAnimationFrame;
// 假 2D ctx：getContext 命中即记一次，断言用。
const fakeCtx = {} as CanvasRenderingContext2D;
let getContextSpy: ReturnType<typeof vi.spyOn>;

function fakeState(): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 2, heightMaps: 1, background: '#FFFFFF' },
        layers: [{ id: 'l-1', name: 'L', visible: true, locked: false, opacity: 1, blendMode: 'normal', colorTag: null, elements: [] }],
        activeLayerId: 'l-1',
    } as unknown as ProjectState;
}

describe('PreviewPane 渲染 smoke（0.7.1-P3-T3）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        renderProjectState.mockClear();
        vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 1; });
        vi.stubGlobal('cancelAnimationFrame', () => {});
        getContextSpy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
            .mockReturnValue(fakeCtx as unknown as RenderingContext);
    });
    afterEach(() => {
        getContextSpy.mockRestore();
        vi.stubGlobal('requestAnimationFrame', realRaf);
        vi.stubGlobal('cancelAnimationFrame', realCaf);
    });

    it('空态：占位「未选择墙」+ 不渲染', async () => {
        const wrapper = mount(PreviewPane);
        await nextTick();
        expect(wrapper.text()).toContain('未选择墙');
        expect(renderProjectState).not.toHaveBeenCalled();
        wrapper.unmount();
    });

    it('有墙 state：getContext(2d) + renderProjectState(ctx, state) 被调', async () => {
        const project = useProjectStore();
        const st = fakeState();
        project.setSnapshot(st);
        const wrapper = mount(PreviewPane);
        await nextTick();
        // canvas 拿到 2D 上下文
        expect(getContextSpy).toHaveBeenCalledWith('2d');
        // 渲染当前墙：第一个参数是 ctx，第二个是 state
        expect(renderProjectState).toHaveBeenCalled();
        const [ctxArg, stateArg] = renderProjectState.mock.calls[renderProjectState.mock.calls.length - 1];
        expect(ctxArg).toBe(fakeCtx);
        expect(stateArg).toBe(project.state);
        // 占位文字不再出现
        expect(wrapper.text()).not.toContain('未选择墙');
        wrapper.unmount();
    });

    it('标题渲染（t.value.script.preview.title 解包）', async () => {
        const wrapper = mount(PreviewPane);
        await nextTick();
        expect(wrapper.text()).toContain('预览');
        wrapper.unmount();
    });
});

// ---------- 0.7.1-P3 波2：点选填 elementId + 取当前值 + 高亮 ----------

/** 墙 256×128（widthMaps 2 × heightMaps 1）+ 一个 rect 元素（默认 e-1 在 (100,30) 40×40）。 */
function fakeStateWithElement(el?: Partial<Element>): ProjectState {
    const rect: Element = {
        id: 'e-1', type: 'rect', x: 100, y: 30, w: 40, h: 40, rotation: 0,
        locked: false, visible: true, fill: '#FFFFFF',
        ...(el as object),
    } as unknown as Element;
    return {
        version: 1,
        canvas: { widthMaps: 2, heightMaps: 1, background: '#FFFFFF' },
        layers: [{
            id: 'l-1', name: 'L', visible: true, locked: false, opacity: 1,
            blendMode: 'normal', colorTag: null, elements: [rect],
        }],
        activeLayerId: 'l-1',
    } as unknown as ProjectState;
}

/** 造一条带单个 action 的 workingCopy 规则（path = actions/0）。 */
function ruleWithAction(action: ScriptAction): ScriptRule {
    return {
        id: 'sr-1', wallId: 'w-abc12345', enabled: true, name: 'r',
        trigger: { type: 'wallReady' }, actions: [action], blockLayout: '{}',
    };
}

/**
 * 让 PreviewPane 测到 paneW/paneH（happy-dom 无布局）：stub host 的 clientWidth/clientHeight。
 * 256×128 → scale=1, offset=0,0（canvas 与墙 1:1，client 坐标 = 墙坐标）。
 */
function stubHostSize(w = 256, h = 128) {
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get() { return w; } });
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get() { return h; } });
}
function restoreHostSize() {
    // 删掉自定义 getter 回退到原型默认（happy-dom 默认 0）。
    delete (HTMLElement.prototype as unknown as Record<string, unknown>).clientWidth;
    delete (HTMLElement.prototype as unknown as Record<string, unknown>).clientHeight;
}

describe('PreviewPane 点选填 elementId + 高亮（0.7.1-P3 波2 T5）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        renderProjectState.mockClear();
        vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 1; });
        vi.stubGlobal('cancelAnimationFrame', () => {});
        getContextSpy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
            .mockReturnValue(fakeCtx as unknown as RenderingContext);
        stubHostSize();
        // host rect 固定在 (0,0)：client 坐标减 host left/top 后 = pane 局部坐标。
        vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
            left: 0, top: 0, right: 256, bottom: 128, width: 256, height: 128, x: 0, y: 0, toJSON() {},
        } as DOMRect);
    });
    afterEach(() => {
        getContextSpy.mockRestore();
        vi.restoreAllMocks();
        vi.stubGlobal('requestAnimationFrame', realRaf);
        vi.stubGlobal('cancelAnimationFrame', realCaf);
        restoreHostSize();
    });

    async function setup(action: ScriptAction, bind: string | null = 'actions/0') {
        const project = useProjectStore();
        project.setSnapshot(fakeStateWithElement());
        const scripts = useScriptStore();
        // 必须把规则也放进 scripts 镜像：scriptEdit 的 server-as-truth watch 见 selectedRuleId
        // 对应规则在 scripts 里为 null 会判定"被删"→ closeEditing 清掉 workingCopy / binding。
        scripts.upsert(ruleWithAction(action));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setActiveElementBinding(bind);
        const wrapper = mount(PreviewPane, { attachTo: document.body });
        await nextTick();
        return { project, edit, wrapper };
    }

    function pointerDownAt(wrapper: ReturnType<typeof mount>, clientX: number, clientY: number) {
        const canvas = wrapper.find('canvas');
        return canvas.trigger('pointerdown', { clientX, clientY, button: 0 });
    }

    it('命中元素 + activeElementBinding 设好 → updateActionField 填 elementId', async () => {
        const { edit, wrapper } = await setup({ type: 'setElementProperty', elementId: '', property: 'x', value: '' });
        const spy = vi.spyOn(edit, 'updateActionField');
        // 点 (120, 50) 落在 e-1 的 (100,30)~(140,70) 内
        await pointerDownAt(wrapper, 120, 50);
        expect(spy).toHaveBeenCalledTimes(1);
        const [path, patch] = spy.mock.calls[0];
        expect(path).toBe('actions/0');
        expect((patch as { elementId?: string }).elementId).toBe('e-1');
        wrapper.unmount();
    });

    it('activeElementBinding 为 null → 点选不填', async () => {
        const { edit, wrapper } = await setup({ type: 'setElementProperty', elementId: '', property: 'x', value: '' }, null);
        const spy = vi.spyOn(edit, 'updateActionField');
        await pointerDownAt(wrapper, 120, 50);
        expect(spy).not.toHaveBeenCalled();
        wrapper.unmount();
    });

    it('未命中任何元素（点空白）→ 不填', async () => {
        const { edit, wrapper } = await setup({ type: 'setElementProperty', elementId: '', property: 'x', value: '' });
        const spy = vi.spyOn(edit, 'updateActionField');
        // 点 (10,10) 在元素外
        await pointerDownAt(wrapper, 10, 10);
        expect(spy).not.toHaveBeenCalled();
        wrapper.unmount();
    });

    it('绑定元素后重绘：在 ctx 上画高亮描边（stroke 相关 ctx 方法被调）', async () => {
        // fakeCtx 需带可被调用的绘图方法 spy。
        const strokeRect = vi.fn();
        const ctx = {
            strokeRect, beginPath: vi.fn(), stroke: vi.fn(), rect: vi.fn(),
            save: vi.fn(), restore: vi.fn(), setLineDash: vi.fn(),
        } as unknown as CanvasRenderingContext2D;
        getContextSpy.mockReturnValue(ctx as unknown as RenderingContext);
        const project = useProjectStore();
        project.setSnapshot(fakeStateWithElement());
        const scripts = useScriptStore();
        scripts.upsert(ruleWithAction({ type: 'setElementProperty', elementId: 'e-1', property: 'x', value: '' }));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setActiveElementBinding('actions/0');
        const wrapper = mount(PreviewPane, { attachTo: document.body });
        await nextTick();
        // 重绘已发生（mount + RAF 同步）；绑定的 e-1 应被描边
        expect(strokeRect).toHaveBeenCalled();
        wrapper.unmount();
    });
});

describe('PreviewPane 点选取当前坐标值（0.7.1-P3 波2 T6）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        renderProjectState.mockClear();
        vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 1; });
        vi.stubGlobal('cancelAnimationFrame', () => {});
        getContextSpy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
            .mockReturnValue(fakeCtx as unknown as RenderingContext);
        stubHostSize();
        vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
            left: 0, top: 0, right: 256, bottom: 128, width: 256, height: 128, x: 0, y: 0, toJSON() {},
        } as DOMRect);
    });
    afterEach(() => {
        getContextSpy.mockRestore();
        vi.restoreAllMocks();
        vi.stubGlobal('requestAnimationFrame', realRaf);
        vi.stubGlobal('cancelAnimationFrame', realCaf);
        restoreHostSize();
    });

    /** 元素 e-1：x=100 y=30 w=40 h=40 rotation=15 opacity=0.5。 */
    async function setupWith(action: ScriptAction) {
        const project = useProjectStore();
        project.setSnapshot(fakeStateWithElement({ rotation: 15, opacity: 0.5 } as Partial<Element>));
        const scripts = useScriptStore();
        scripts.upsert(ruleWithAction(action));
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setActiveElementBinding('actions/0');
        const wrapper = mount(PreviewPane, { attachTo: document.body });
        await nextTick();
        const spy = vi.spyOn(edit, 'updateActionField');
        return { edit, wrapper, spy };
    }
    function clickElement(wrapper: ReturnType<typeof mount>) {
        return wrapper.find('canvas').trigger('pointerdown', { clientX: 120, clientY: 50, button: 0 });
    }

    it('moveTo → 填 elementId + patch{x,y}（取元素当前 x/y）', async () => {
        const { wrapper, spy } = await setupWith(
            { type: 'setElementProperties', kind: 'moveTo', elementId: '', patch: { x: '0', y: '0' } } as ScriptAction,
        );
        await clickElement(wrapper);
        expect(spy).toHaveBeenCalledTimes(1);
        const [path, payload] = spy.mock.calls[0];
        expect(path).toBe('actions/0');
        expect(payload).toEqual({ elementId: 'e-1', patch: { x: '100', y: '30' } });
        wrapper.unmount();
    });

    it('resize → patch{w,h}', async () => {
        const { wrapper, spy } = await setupWith(
            { type: 'setElementProperties', kind: 'resize', elementId: '', patch: { w: '1', h: '1' } } as ScriptAction,
        );
        await clickElement(wrapper);
        const [, payload] = spy.mock.calls[0];
        expect(payload).toEqual({ elementId: 'e-1', patch: { w: '40', h: '40' } });
        wrapper.unmount();
    });

    it('rotateTo → patch{rotation}（取元素 rotation=15，round）', async () => {
        const { wrapper, spy } = await setupWith(
            { type: 'setElementProperties', kind: 'rotateTo', elementId: '', patch: { rotation: '0' } } as ScriptAction,
        );
        await clickElement(wrapper);
        const [, payload] = spy.mock.calls[0];
        expect(payload).toEqual({ elementId: 'e-1', patch: { rotation: '15' } });
        wrapper.unmount();
    });

    it('setOpacity → patch{opacity}（取元素 opacity=0.5）', async () => {
        const { wrapper, spy } = await setupWith(
            { type: 'setElementProperties', kind: 'setOpacity', elementId: '', patch: { opacity: '1' } } as ScriptAction,
        );
        await clickElement(wrapper);
        const [, payload] = spy.mock.calls[0];
        expect(payload).toEqual({ elementId: 'e-1', patch: { opacity: '0.5' } });
        wrapper.unmount();
    });

    it('setText（非坐标 friendly）→ 只填 elementId，不取几何', async () => {
        const { wrapper, spy } = await setupWith(
            { type: 'setElementProperties', kind: 'setText', elementId: '', patch: { text: '' } } as ScriptAction,
        );
        await clickElement(wrapper);
        const [, payload] = spy.mock.calls[0];
        expect(payload).toEqual({ elementId: 'e-1' });
        wrapper.unmount();
    });

    it('万能 setElementProperty（单数）→ 只填 elementId（property/value 用户自选）', async () => {
        const { wrapper, spy } = await setupWith(
            { type: 'setElementProperty', elementId: '', property: 'x', value: '' } as ScriptAction,
        );
        await clickElement(wrapper);
        const [, payload] = spy.mock.calls[0];
        expect(payload).toEqual({ elementId: 'e-1' });
        wrapper.unmount();
    });
});
