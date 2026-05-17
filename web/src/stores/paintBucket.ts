/**
 * M18 Live Paint — 油漆桶工具配置 store。
 *
 * 范围：用户偏好（当前 fill）跨 wall 持久化，不在 M16 P4.2 reset() 范围内
 *  ——剪贴板 / 笔刷 fill 选择同款语义。
 *
 * 字段：
 *   - currentFill：FillCompat 联合类型（solid / linear / radial）；FillInput 组件直接绑定。
 *
 * 持久化：localStorage('hikari-canvas:paint-bucket')。Read 失败 / 空 → 默认黑色实心。
 */

import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import type { FillCompat } from '@/types/protocol';

const KEY = 'hikari-canvas:paint-bucket';

const DEFAULT_FILL: FillCompat = { type: 'solid', color: '#000000' };

export const usePaintBucketStore = defineStore('paint-bucket', () => {
    const currentFill = ref<FillCompat>(loadFill());
    watch(
        currentFill,
        (v) => {
            try {
                localStorage.setItem(KEY, JSON.stringify(v));
            } catch {
                /* ignore (private mode etc) */
            }
        },
        { deep: true },
    );
    return { currentFill };
});

function loadFill(): FillCompat {
    try {
        const raw = localStorage.getItem(KEY);
        if (raw) {
            const parsed = JSON.parse(raw) as FillCompat;
            // 极简校验：string 或带 type 的对象 → 接受；否则降级
            if (typeof parsed === 'string') return parsed;
            if (parsed && typeof parsed === 'object' && 'type' in parsed) {
                return parsed;
            }
        }
    } catch {
        /* ignore */
    }
    return { ...(DEFAULT_FILL as object) } as FillCompat;
}
