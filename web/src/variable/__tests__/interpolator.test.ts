/**
 * 0.4.0-P2-H interpolator 单测。
 *
 * 用 Pinia + 真 useVariableStore 实例（store 不依赖 DOM，纯逻辑）。
 *
 * 覆盖：
 * 1. 纯文本短路 passthrough
 * 2. 单 ${var:user/X} + wallId 注入
 * 3. 变量不存在 + 无 fallback → "???"
 * 4. 变量不存在 + |fallback=N/A
 * 5. currentValue 空 + fallback → fallback
 * 6. currentValue 空 + 无 fallback + defaultValue → defaultValue
 * 7. currentValue 空 + 无 fallback + defaultValue 空 → "???"
 * 8. 多占位符全部替换
 * 9. missingFullNames 集合
 * 10. referencedFullNames 集合
 * 11. wallId 为 null 时 user/X 走字面查询
 * 12. wallId 注入后内部 fullName 形式正确
 * 13. 空 / null / undefined 输入
 * 14. 显式空 fallback ({@code |fallback=}) 应当用空字符串
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useVariableStore } from '@/stores/variables';
import { interpolate, resolveFullName, UNRESOLVED } from '../interpolator';
import type { Variable } from '@/types/variable';

function mkVar(namespace: string, key: string, current: string | null, def: string | null = null): Variable {
    return {
        namespace,
        key,
        type: 'STRING',
        defaultValue: def,
        currentValue: current,
        updatedAt: Date.now(),
        ttl: 0,
        source: 'manual',
    };
}

describe('interpolator.resolveFullName', () => {
    it('user/X + wallId → user:<wallId>/X', () => {
        expect(resolveFullName('user/红队比分', 'w-abc')).toBe('user:w-abc/红队比分');
    });
    it('user/X + wallId null → 字面 user/X', () => {
        expect(resolveFullName('user/X', null)).toBe('user/X');
    });
    it('bedwars/score + wallId → 不动 bedwars/score', () => {
        expect(resolveFullName('bedwars/score', 'w-abc')).toBe('bedwars/score');
    });
    it('system.time + wallId → 字面 system.time', () => {
        expect(resolveFullName('server.time', 'w-abc')).toBe('server.time');
    });
    // 0.4.0-P3-J：wall.* 注入
    it('wall.id + wallId → system:<wallId>/wall.id', () => {
        expect(resolveFullName('wall.id', 'w-abc')).toBe('system:w-abc/wall.id');
    });
    it('wall.alias + wallId → system:<wallId>/wall.alias', () => {
        expect(resolveFullName('wall.alias', 'w-1')).toBe('system:w-1/wall.alias');
    });
    it('wall.X + wallId null → 字面 wall.X', () => {
        expect(resolveFullName('wall.id', null)).toBe('wall.id');
    });
    // 0.4.0-P3-L：schedule.* 注入
    it('schedule.next_departure + wallId → schedule:<wallId>/next_departure', () => {
        expect(resolveFullName('schedule.next_departure', 'w-abc'))
            .toBe('schedule:w-abc/next_departure');
    });
    it('schedule.eta_minutes + wallId → schedule:<wallId>/eta_minutes', () => {
        expect(resolveFullName('schedule.eta_minutes', 'w-1'))
            .toBe('schedule:w-1/eta_minutes');
    });
    it('schedule.X + wallId null → 字面 schedule.X', () => {
        expect(resolveFullName('schedule.next_departure', null))
            .toBe('schedule.next_departure');
    });
    // 0.4.0 bugfix3（Bug A）：用户直觉的 namespace/key 斜杠语法
    it('schedule/X + wallId → schedule:<wallId>/X (slash 语法)', () => {
        expect(resolveFullName('schedule/eta_seconds', 'w-abc'))
            .toBe('schedule:w-abc/eta_seconds');
    });
    it('schedule 斜杠与点号语法产同 fullName', () => {
        expect(resolveFullName('schedule/next_departure', 'w-1'))
            .toBe(resolveFullName('schedule.next_departure', 'w-1'));
    });
    it('wall/X + wallId → system:<wallId>/wall.X (slash 语法)', () => {
        expect(resolveFullName('wall/id', 'w-abc'))
            .toBe('system:w-abc/wall.id');
    });
    it('slash 语法 + wallId null → 字面查询', () => {
        expect(resolveFullName('schedule/eta_seconds', null))
            .toBe('schedule/eta_seconds');
        expect(resolveFullName('wall/id', null))
            .toBe('wall/id');
    });
});

describe('interpolator.interpolate', () => {
    let store: ReturnType<typeof useVariableStore>;

    beforeEach(() => {
        setActivePinia(createPinia());
        store = useVariableStore();
    });

    it('1. 纯文本无 ${var:} 子串 → 原样返回 + 空集合', () => {
        const r = interpolate('Hello world', 'w-abc', store);
        expect(r.text).toBe('Hello world');
        expect(r.referencedFullNames.size).toBe(0);
        expect(r.missingFullNames.size).toBe(0);
    });

    it('2. 单 ${var:user/X} + wallId 注入 + currentValue 命中', () => {
        store.set('user:w-abc/红队比分', mkVar('user:w-abc', '红队比分', '5'));
        const r = interpolate('比分 ${var:user/红队比分} 分', 'w-abc', store);
        expect(r.text).toBe('比分 5 分');
        expect(r.referencedFullNames.has('user:w-abc/红队比分')).toBe(true);
        expect(r.missingFullNames.size).toBe(0);
    });

    it('3. 变量不存在 + 无 fallback → "???"', () => {
        const r = interpolate('${var:user/ghost}', 'w-abc', store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.missingFullNames.has('user:w-abc/ghost')).toBe(true);
    });

    it('4. 变量不存在 + |fallback=N/A → "N/A"', () => {
        const r = interpolate('${var:bedwars/none|fallback=N/A}', 'w-abc', store);
        expect(r.text).toBe('N/A');
        expect(r.missingFullNames.has('bedwars/none')).toBe(true);
    });

    it('5. currentValue 空字符串 + |fallback=F → "F"', () => {
        store.set('user:w-abc/empty', mkVar('user:w-abc', 'empty', ''));
        const r = interpolate('${var:user/empty|fallback=F}', 'w-abc', store);
        expect(r.text).toBe('F');
    });

    it('6. currentValue null + 无 fallback + defaultValue → defaultValue', () => {
        store.set('user:w-abc/def', mkVar('user:w-abc', 'def', null, 'D'));
        const r = interpolate('${var:user/def}', 'w-abc', store);
        expect(r.text).toBe('D');
    });

    it('7. currentValue null + 无 fallback + defaultValue null → "???"', () => {
        store.set('user:w-abc/blank', mkVar('user:w-abc', 'blank', null, null));
        const r = interpolate('${var:user/blank}', 'w-abc', store);
        expect(r.text).toBe(UNRESOLVED);
    });

    it('8. 多占位符全部替换', () => {
        store.set('user:w-abc/red', mkVar('user:w-abc', 'red', '5'));
        store.set('user:w-abc/blue', mkVar('user:w-abc', 'blue', '3'));
        const r = interpolate('红 ${var:user/red} 蓝 ${var:user/blue}', 'w-abc', store);
        expect(r.text).toBe('红 5 蓝 3');
        expect(r.referencedFullNames.size).toBe(2);
    });

    it('9. missingFullNames 仅包含 store 没有的引用', () => {
        store.set('user:w-abc/exists', mkVar('user:w-abc', 'exists', '1'));
        const r = interpolate('${var:user/exists} ${var:user/ghost}', 'w-abc', store);
        expect(r.missingFullNames.has('user:w-abc/ghost')).toBe(true);
        expect(r.missingFullNames.has('user:w-abc/exists')).toBe(false);
    });

    it('10. referencedFullNames 精确匹配实际引用', () => {
        store.set('user:w-abc/a', mkVar('user:w-abc', 'a', '1'));
        const r = interpolate('${var:user/a} ${var:user/a} ${var:bedwars/b|fallback=0}', 'w-abc', store);
        expect(r.referencedFullNames.has('user:w-abc/a')).toBe(true);
        expect(r.referencedFullNames.has('bedwars/b')).toBe(true);
        expect(r.referencedFullNames.size).toBe(2);  // Set 去重
    });

    it('11. wallId null 时 user/X 走字面查询 + missing 标记', () => {
        const r = interpolate('${var:user/X}', null, store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.missingFullNames.has('user/X')).toBe(true);
    });

    it('12. 插件命名空间不受 wallId 注入影响', () => {
        store.set('bedwars/score', mkVar('bedwars', 'score', '42'));
        const r = interpolate('${var:bedwars/score}', 'w-abc', store);
        expect(r.text).toBe('42');
        expect(r.referencedFullNames.has('bedwars/score')).toBe(true);
    });

    it('13. null / undefined / 空字符串 输入 → 空结果 + 空集合', () => {
        const r1 = interpolate(null, 'w-abc', store);
        expect(r1.text).toBe('');
        expect(r1.referencedFullNames.size).toBe(0);
        const r2 = interpolate(undefined, 'w-abc', store);
        expect(r2.text).toBe('');
        const r3 = interpolate('', 'w-abc', store);
        expect(r3.text).toBe('');
    });

    it('14. 显式空 fallback (|fallback=) → 空字符串替换', () => {
        const r = interpolate('${var:user/x|fallback=}', 'w-abc', store);
        expect(r.text).toBe('');
        expect(r.missingFullNames.has('user:w-abc/x')).toBe(true);
    });

    it('15. quoteReplacement-equivalent：替换值含 $ / \\ 不当反向引用', () => {
        store.set('user:w-abc/k', mkVar('user:w-abc', 'k', '$1\\back'));
        const r = interpolate('${var:user/k}', 'w-abc', store);
        expect(r.text).toBe('$1\\back');
    });

    // 0.4.0-P3-J：wall.* 注入端到端
    it('16. ${var:wall.id} + wallId 注入命中 system:<wallId>/wall.id', () => {
        store.set('system:w-abc/wall.id', mkVar('system:w-abc', 'wall.id', 'w-abc'));
        const r = interpolate('Wall=${var:wall.id}', 'w-abc', store);
        expect(r.text).toBe('Wall=w-abc');
        expect(r.referencedFullNames.has('system:w-abc/wall.id')).toBe(true);
    });

    it('17. 混合 ${var:user/X} + ${var:wall.id} 都正确注入替换', () => {
        store.set('user:w-1/score', mkVar('user:w-1', 'score', '42'));
        store.set('system:w-1/wall.id', mkVar('system:w-1', 'wall.id', 'w-1'));
        const r = interpolate(
            '${var:user/score} 分 @ ${var:wall.id}',
            'w-1',
            store,
        );
        expect(r.text).toBe('42 分 @ w-1');
        expect(r.referencedFullNames.size).toBe(2);
        expect(r.referencedFullNames.has('user:w-1/score')).toBe(true);
        expect(r.referencedFullNames.has('system:w-1/wall.id')).toBe(true);
    });

    it('18. wallId null 时 wall.X 字面查询 missing', () => {
        const r = interpolate('${var:wall.id}', null, store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.missingFullNames.has('wall.id')).toBe(true);
    });

    // ──────────────────────────────────────────────────────────
    //  M28-enhance：segments 字段（PreviewRenderer hint chip 用）
    // ──────────────────────────────────────────────────────────

    it('19. 纯文本 → segments 为空数组', () => {
        const r = interpolate('Hello world', 'w-1', store);
        expect(r.segments).toEqual([]);
    });

    it('20. null / undefined / 空字符串 → segments 为空数组', () => {
        expect(interpolate(null, 'w-1', store).segments).toEqual([]);
        expect(interpolate(undefined, 'w-1', store).segments).toEqual([]);
        expect(interpolate('', 'w-1', store).segments).toEqual([]);
    });

    it('21. 单 placeholder → segments 长度 1 + start/end 指向替换后字符', () => {
        store.set('user:w-1/x', mkVar('user:w-1', 'x', 'VALUE'));
        const r = interpolate('A=${var:user/x}!', 'w-1', store);
        expect(r.text).toBe('A=VALUE!');
        expect(r.segments).toHaveLength(1);
        const seg = r.segments[0];
        expect(seg.start).toBe(2); // 'A=' 之后
        expect(seg.end).toBe(7);   // 'A=VALUE'
        expect(r.text.substring(seg.start, seg.end)).toBe('VALUE');
        expect(seg.fullName).toBe('user:w-1/x');
        expect(seg.raw).toBe('${var:user/x}');
    });

    it('22. 多 placeholder → segments 数量 + 各 range 精确', () => {
        store.set('user:w-1/a', mkVar('user:w-1', 'a', '10'));
        store.set('user:w-1/b', mkVar('user:w-1', 'b', '20'));
        const r = interpolate('${var:user/a}+${var:user/b}=30', 'w-1', store);
        expect(r.text).toBe('10+20=30');
        expect(r.segments).toHaveLength(2);
        expect(r.text.substring(r.segments[0].start, r.segments[0].end)).toBe('10');
        expect(r.text.substring(r.segments[1].start, r.segments[1].end)).toBe('20');
        expect(r.segments[0].fullName).toBe('user:w-1/a');
        expect(r.segments[1].fullName).toBe('user:w-1/b');
    });

    it('23. unresolved placeholder（??? 兜底）segments 仍然记录', () => {
        const r = interpolate('X${var:ghost}Y', 'w-1', store);
        expect(r.text).toBe('X???Y');
        expect(r.segments).toHaveLength(1);
        expect(r.segments[0].start).toBe(1);
        expect(r.segments[0].end).toBe(4);
        expect(r.text.substring(r.segments[0].start, r.segments[0].end))
            .toBe(UNRESOLVED);
    });

    it('24. fallback 替换后 segments range 对应 fallback 字符串', () => {
        const r = interpolate('[${var:missing|fallback=N/A}]', 'w-1', store);
        expect(r.text).toBe('[N/A]');
        expect(r.segments).toHaveLength(1);
        expect(r.text.substring(r.segments[0].start, r.segments[0].end))
            .toBe('N/A');
    });

    it('25. 显式空 fallback → segments range 为零宽（start === end）', () => {
        const r = interpolate('[${var:missing|fallback=}]', 'w-1', store);
        expect(r.text).toBe('[]');
        expect(r.segments).toHaveLength(1);
        expect(r.segments[0].start).toBe(r.segments[0].end);
    });

    it('26. 长占位符替换为短值 → segments end 指向短值末尾（layout 不爆 bug 修复关键）', () => {
        // 问题情境：${var:schedule/eta_minutes} 是 30 字符，替换为 "5" 1 字符。
        // segments[0] 应指向短的 "5"，而非原 30 字符；这样 PreviewRenderer 画 hint 时
        // 矩形宽度也是 1 字符宽，而非 30 字符宽
        store.set('schedule:w-1/eta_minutes', mkVar('schedule:w-1', 'eta_minutes', '5'));
        const r = interpolate('ETA ${var:schedule/eta_minutes} min', 'w-1', store);
        expect(r.text).toBe('ETA 5 min');
        expect(r.segments).toHaveLength(1);
        expect(r.segments[0].start).toBe(4);
        expect(r.segments[0].end).toBe(5);
        expect(r.text.substring(r.segments[0].start, r.segments[0].end)).toBe('5');
    });

    // 0.7.4：rail 车次语义变量端到端 resolve。RailScheduleProvider 写
    // schedule:<wallId>/next_cars，metadata 现以 namespace="schedule" 下发（不再是 daemon key
    // schedule_rail）→ picker 选中插入 ${var:schedule/next_cars} → interpolator 注入 wallId →
    // schedule:w-1/next_cars 命中。修复前 picker 会插入 ${var:schedule_rail/next_cars}，
    // interpolator 不识别 schedule_rail 前缀 → 字面查询 miss → 误报"已删除"。
    it('26b. rail 变量 ${var:schedule/next_cars} 注入 wallId 命中真实值（幽灵变量根因回归）', () => {
        store.set('schedule:w-1/next_cars', mkVar('schedule:w-1', 'next_cars', '6'));
        const r = interpolate('编组 ${var:schedule/next_cars} 节', 'w-1', store);
        expect(r.text).toBe('编组 6 节');
        expect(r.referencedFullNames.has('schedule:w-1/next_cars')).toBe(true);
        expect(r.missingFullNames.size).toBe(0);
    });

    it('26c. 旧 daemon key 形态 ${var:schedule_rail/next_cars} 不被注入 → miss（证明必须改 namespace）', () => {
        // 即使 store 有 schedule:w-1/next_cars，旧 schedule_rail 前缀也 resolve 不到——
        // 这正是修复前的 bug 形态，固化为回归保护：metadata 绝不能再下发 schedule_rail。
        store.set('schedule:w-1/next_cars', mkVar('schedule:w-1', 'next_cars', '6'));
        const r = interpolate('${var:schedule_rail/next_cars}', 'w-1', store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.missingFullNames.has('schedule_rail/next_cars')).toBe(true);
    });

    // ──────────────────────────────────────────────────────────
    //  0.4.2 bugfix（Bug 1）：二次扫描兜底
    // ──────────────────────────────────────────────────────────

    it('27. 嵌套 ${${var:X}} → 第一轮替换出新 ${var:Y}，第二轮清理', () => {
        // 数据损坏 / chip roundtrip 漏 escape：variable.currentValue 本身含 `${var:...}` 字面
        store.set('user:w-1/outer', mkVar('user:w-1', 'outer', '${var:user/inner}'));
        store.set('user:w-1/inner', mkVar('user:w-1', 'inner', 'INNER_VAL'));
        const r = interpolate('${var:user/outer}', 'w-1', store);
        // 二次扫描应当把 ${var:user/inner} 解出 INNER_VAL
        expect(r.text).toBe('INNER_VAL');
        // 残留检查（首层 outer + 二层 inner 都已替）
        expect(r.text.indexOf('${var:')).toBe(-1);
    });

    it('28. 二层嵌套但内层指向 missing → 第二轮替换为 ???', () => {
        store.set('user:w-1/outer', mkVar('user:w-1', 'outer', '${var:user/ghost}'));
        const r = interpolate('${var:user/outer}', 'w-1', store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.text.indexOf('${var:')).toBe(-1);
    });

    it('29. 三层嵌套（depth=3 超 MAX_INTERPOLATE_DEPTH=2）→ 最后残留字面会被 PreviewRenderer 兜底', () => {
        // 三层数据极端损坏：interpolate 内部扫到 depth=2 停；剩 ${var:...} 字面交给 PreviewRenderer 兜底
        store.set('user:w-1/a', mkVar('user:w-1', 'a', '${var:user/b}'));
        store.set('user:w-1/b', mkVar('user:w-1', 'b', '${var:user/c}'));
        store.set('user:w-1/c', mkVar('user:w-1', 'c', 'FINAL'));
        const r = interpolate('${var:user/a}', 'w-1', store);
        // depth=2 把 a → b 一次 + b → c 一次 = 替到 FINAL。如果 c 还引 d 才会残留
        expect(r.text).toBe('FINAL');
    });

    it('30. 纯文本无 ${var:} → 不触发二次扫描（性能不退化）', () => {
        // 纯文本短路保护：text.indexOf 检查首次就拿到空 result，循环条件不进
        const r = interpolate('hello world', 'w-1', store);
        expect(r.text).toBe('hello world');
        expect(r.referencedFullNames.size).toBe(0);
    });
});
