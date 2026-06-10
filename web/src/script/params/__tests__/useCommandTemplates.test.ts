/**
 * 0.7.0-P5-F：useCommandTemplates fetch + 缓存 composable 单测。
 *
 * <p>覆盖：① 成功拉到模板列表（形态正确）；② 空配置返空；③ 401 / 网络错 → 空（不抛）；
 * ④ 模块缓存命中（二次 load 不重复 fetch）；⑤ refresh 强制重拉；⑥ 换 session 作废缓存；
 * ⑦ 未鉴权（sessionId=null）→ 空且不发请求。纯 node（stub global fetch + pinia network store）。</p>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useNetworkStore } from '@/stores/network';
import {
    useCommandTemplates,
    __resetCommandTemplatesCache,
    type CommandTemplate,
} from '../useCommandTemplates';

/** 造一个 fetch mock，返指定 JSON + ok 状态；记录调用次数。 */
function stubFetch(json: unknown, ok = true): { calls: () => number } {
    let count = 0;
    const fn = vi.fn(async () => {
        count += 1;
        return {
            ok,
            json: async () => json,
        } as unknown as Response;
    });
    vi.stubGlobal('fetch', fn);
    return { calls: () => count };
}

beforeEach(() => {
    setActivePinia(createPinia());
    __resetCommandTemplatesCache();
    // 默认给个 session（鉴权过）。
    useNetworkStore().sessionId = 'sid-1';
});

afterEach(() => {
    vi.unstubAllGlobals();
});

const SAMPLE: CommandTemplate[] = [
    { id: 'announce', params: [{ name: 'msg', type: 'text', maxLength: 64 }] },
    { id: 'give', params: [] },
];

describe('useCommandTemplates', () => {
    it('成功拉取：templates 填充 + 形态正确', async () => {
        stubFetch({ templates: SAMPLE });
        const cmd = useCommandTemplates();
        const list = await cmd.load();
        expect(list).toEqual(SAMPLE);
        expect(cmd.templates.value).toEqual(SAMPLE);
        expect(cmd.templates.value[0].params[0].maxLength).toBe(64);
    });

    it('空配置 {templates:[]} → 空列表', async () => {
        stubFetch({ templates: [] });
        const cmd = useCommandTemplates();
        expect(await cmd.load()).toEqual([]);
    });

    it('响应缺 templates 字段 → 空列表（?? 兜底）', async () => {
        stubFetch({});
        const cmd = useCommandTemplates();
        expect(await cmd.load()).toEqual([]);
    });

    it('401（res.ok=false） → 空列表，不抛', async () => {
        stubFetch({ error: 'UNAUTHORIZED' }, false);
        const cmd = useCommandTemplates();
        await expect(cmd.load()).resolves.toEqual([]);
    });

    it('网络错（fetch reject） → 空列表，不抛', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => {
            throw new Error('network down');
        }));
        const cmd = useCommandTemplates();
        await expect(cmd.load()).resolves.toEqual([]);
    });

    it('缓存命中：二次 load 不重复 fetch', async () => {
        const { calls } = stubFetch({ templates: SAMPLE });
        const cmd = useCommandTemplates();
        await cmd.load();
        await cmd.load();
        await cmd.load();
        expect(calls()).toBe(1);
    });

    it('refresh 强制重拉（清缓存）', async () => {
        const { calls } = stubFetch({ templates: SAMPLE });
        const cmd = useCommandTemplates();
        await cmd.load();
        await cmd.refresh();
        expect(calls()).toBe(2);
    });

    it('换 session → 缓存作废重拉', async () => {
        const { calls } = stubFetch({ templates: SAMPLE });
        const net = useNetworkStore();
        const cmd = useCommandTemplates();
        await cmd.load();
        expect(calls()).toBe(1);
        // 模拟重连换 session
        net.sessionId = 'sid-2';
        await cmd.load();
        expect(calls()).toBe(2);
    });

    it('未鉴权（sessionId=null） → 空且不发请求', async () => {
        const { calls } = stubFetch({ templates: SAMPLE });
        useNetworkStore().sessionId = null;
        const cmd = useCommandTemplates();
        expect(await cmd.load()).toEqual([]);
        expect(calls()).toBe(0);
    });
});
