import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { TemplateParam, TemplateSpec } from '@/types/template';

/**
 * 模板注册表客户端缓存。来源 = ready payload 的 {@code templates} 数组
 * （协议 §3.2）。
 *
 * <p>除全量列表外，还维护 UI 侧暂存：当前选中 templateId、当前 params 草稿（按 id
 * 分桶）、TemplateGallery 打开状态。前后端通讯仍由 {@code wsClient.send('template.apply', ...)}
 * 走旧通道。</p>
 */
export const useTemplatesStore = defineStore('templates', () => {
    const list = ref<TemplateSpec[]>([]);
    /** Gallery 模态开关。 */
    const galleryOpen = ref(false);
    /** 当前选中模板 id；null = 未选。 */
    const selectedId = ref<string | null>(null);
    /** 每个模板的 param 草稿；key = templateId，value = param map。 */
    const drafts = ref<Record<string, Record<string, unknown>>>({});

    const byId = computed(() => {
        const m = new Map<string, TemplateSpec>();
        for (const t of list.value) m.set(t.id, t);
        return m;
    });

    function setTemplates(next: TemplateSpec[]) {
        list.value = next ?? [];
        // 同步丢弃 id 不再存在的 draft
        const valid = new Set(list.value.map((t) => t.id));
        for (const id of Object.keys(drafts.value)) {
            if (!valid.has(id)) delete drafts.value[id];
        }
        // 选中项若已消失则清空
        if (selectedId.value && !valid.has(selectedId.value)) {
            selectedId.value = null;
        }
    }

    function openGallery() {
        galleryOpen.value = true;
        // 若无选中，默认指向第一个
        if (!selectedId.value && list.value.length > 0) {
            selectedId.value = list.value[0].id;
            ensureDraft(selectedId.value);
        }
    }

    function closeGallery() {
        galleryOpen.value = false;
    }

    function selectTemplate(id: string | null) {
        selectedId.value = id;
        if (id) ensureDraft(id);
    }

    /** 用模板各 param 的 default 初始化草稿（如果还没初始化）。 */
    function ensureDraft(id: string) {
        if (drafts.value[id]) return;
        const tpl = byId.value.get(id);
        if (!tpl) return;
        const init: Record<string, unknown> = {};
        for (const [k, p] of Object.entries(tpl.params ?? {})) {
            init[k] = (p as TemplateParam).default ?? null;
        }
        drafts.value[id] = init;
    }

    function setParam(templateId: string, key: string, value: unknown) {
        ensureDraft(templateId);
        drafts.value[templateId][key] = value;
    }

    function resetParams(templateId: string) {
        delete drafts.value[templateId];
        ensureDraft(templateId);
    }

    function currentDraft(): Record<string, unknown> {
        if (!selectedId.value) return {};
        ensureDraft(selectedId.value);
        return drafts.value[selectedId.value];
    }

    return {
        list,
        galleryOpen,
        selectedId,
        drafts,
        byId,
        setTemplates,
        openGallery,
        closeGallery,
        selectTemplate,
        ensureDraft,
        setParam,
        resetParams,
        currentDraft,
    };
});
