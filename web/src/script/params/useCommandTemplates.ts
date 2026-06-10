/**
 * 0.7.0-P5-F（K-UI-5/10）：命令模板列表 fetch + 进程内缓存 composable。
 *
 * <p>{@code runCommand} 积木的「命令模板」下拉需要服主在 {@code config.yml} 配的模板列表
 * （每个模板有哪些参数 + 各参数 maxLength）。后端端点
 * {@code GET /api/script/command-templates?sessionId=<sid>} 只返
 * {@code {templates:[{id, params:[{name,type,maxLength}]}]}}，<b>绝不含 command 原文</b>
 * （服主可能在模板里收口 op 命令 / 敏感参数，见 CommandTemplateHandler）。</p>
 *
 * <h2>缓存策略</h2>
 *
 * <p>模板表跟随 config（{@code /canvas reload} 才变），不需每次开积木都重拉。故用<b>模块级
 * 单例缓存</b>：首个 BlockParamInput 触发 {@link load} → fetch → 缓存 promise；后续所有
 * BlockParamInput 复用同一 promise（不会并发打多次请求）。{@link refresh} 强制重拉（reload
 * 后想立即刷新可调）。缓存按 sessionId 维度——换 session（重连）后旧缓存作废重拉。</p>
 *
 * <p>失败（401 / 网络错 / 解析错）→ 返空列表（积木下拉显示"服主还没配命令模板"占位），
 * 不抛给调用方（积木编辑器不应因模板拉取失败而崩）。</p>
 */
import { ref, type Ref } from 'vue';
import { useNetworkStore } from '@/stores/network';

/** 单个模板参数的规格（镜像后端 CommandTemplateHandler 的 params 项）。 */
export interface CommandTemplateParam {
    name: string;
    /** config load 期已归一为 {@code "text"} / {@code "online-player"}。 */
    type: string;
    /** 该参数允许的最大长度（前端 input maxlength 钳位）。 */
    maxLength: number;
}

/** 单个命令模板（id + 参数列表；command 原文永不下发）。 */
export interface CommandTemplate {
    id: string;
    params: CommandTemplateParam[];
}

/** 后端响应顶层形态。 */
interface CommandTemplatesResponse {
    templates?: CommandTemplate[];
}

/**
 * 模块级缓存：按 sessionId 缓存一份「拉取 promise」。
 * key = sessionId（换 session 作废）；value = 正在进行 / 已完成的拉取结果 promise。
 */
let cacheSessionId: string | null = null;
let cachePromise: Promise<CommandTemplate[]> | null = null;

/**
 * 实际发请求（不走缓存）。失败一律 resolve 空列表（不 reject）。
 * sessionId 缺失（未鉴权）也返空——积木下拉走"服主还没配"占位。
 */
async function fetchTemplates(sessionId: string | null): Promise<CommandTemplate[]> {
    if (!sessionId) return [];
    try {
        const params = new URLSearchParams({ sessionId });
        const res = await fetch(`/api/script/command-templates?${params.toString()}`);
        if (!res.ok) return [];
        const json = (await res.json()) as CommandTemplatesResponse;
        return json.templates ?? [];
    } catch {
        // 网络错 / JSON 解析错：静默退空（不阻塞积木编辑）。
        return [];
    }
}

/**
 * composable 返回值：响应式 templates / loading + load / refresh。
 *
 * <p>{@link load} 幂等：多次调用复用同一缓存 promise（缓存命中时不重复 fetch）。
 * {@link refresh} 清缓存后重拉（{@code /canvas reload} 后想立即看到新模板可用）。</p>
 */
export interface UseCommandTemplates {
    /** 已拉到的模板列表（成功前为空数组）。 */
    templates: Ref<CommandTemplate[]>;
    /** 是否正在拉取。 */
    loading: Ref<boolean>;
    /** 触发拉取（命中缓存则复用）。返回 resolve 后的列表。 */
    load: () => Promise<CommandTemplate[]>;
    /** 清缓存强制重拉。 */
    refresh: () => Promise<CommandTemplate[]>;
}

export function useCommandTemplates(): UseCommandTemplates {
    const net = useNetworkStore();
    const templates = ref<CommandTemplate[]>([]);
    const loading = ref(false);

    async function doLoad(force: boolean): Promise<CommandTemplate[]> {
        const sid = net.sessionId;
        // 换 session 或强制刷新 → 作废旧缓存。
        if (force || cacheSessionId !== sid) {
            cacheSessionId = sid;
            cachePromise = null;
        }
        if (!cachePromise) {
            loading.value = true;
            cachePromise = fetchTemplates(sid).finally(() => {
                loading.value = false;
            });
        }
        const list = await cachePromise;
        templates.value = list;
        return list;
    }

    return {
        templates,
        loading,
        load: () => doLoad(false),
        refresh: () => doLoad(true),
    };
}

/**
 * 测试辅助：清空模块级缓存（让每个测试 case 从干净状态开始）。
 * 生产代码不应调用。
 */
export function __resetCommandTemplatesCache(): void {
    cacheSessionId = null;
    cachePromise = null;
}
