package moe.hikari.canvas.script;

import moe.hikari.canvas.storage.ScriptDao;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 脚本内存镜像。线程安全:per-wall compute() 串行化写路径;list 存不可变快照。
 * Dao 抛异常时内存不动(先落库再换内存)。契约 docs/scripting.md §2。
 *
 * <p><b>结构</b>:{@code byWall} 存每墙规则的不可变快照({@code List.copyOf});
 * {@code wallByRule} 是 ruleId → wallId 反查索引,让 {@link #update} 不带 wallId 也能定位。
 * 所有写操作走 {@code byWall.compute(wallId, ...)},同一面墙的写天然串行,
 * 不同墙通常并行(hash 撞同 bin 时会串行,可接受);compute 内抛异常时 mapping 不变(ConcurrentHashMap 契约),
 * 配合"compute 内先调 Dao"实现 DB 失败 → 内存零污染。</p>
 *
 * <p><b>dao 可空</b>:纯内存测试装配传 null,所有持久化调用跳过,
 * sortOrder 用内存序({@code list.size()})。</p>
 */
public final class ScriptStore {

    /** 单墙规则配额超限({@code scripts.max-rules-per-wall})。 */
    public static final class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) { super(message); }
    }

    /** 规则不存在(已删 / id 错 / 墙不匹配)。 */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    /**
     * 墙级 mutation 监听。任何 create / update / delete / setEnabled /
     * clearWall 成功后(compute 外、状态已落定)+ loadFromDb 加载到的每面墙各通知一次。
     * TriggerRouter 收到即整墙 rebuild(≤16 规则,O(墙)便宜),不做 per-rule 增量。
     */
    public interface Listener {
        void onWallScriptsChanged(String wallId);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Logger log;
    private final @Nullable ScriptDao dao;
    /** /canvas reload 热更,volatile 即可(读多写极少)。 */
    private volatile int maxRulesPerWall;

    /** wallId → 该墙规则不可变快照(按 sortOrder 序)。 */
    private final ConcurrentHashMap<String, List<ScriptRule>> byWall = new ConcurrentHashMap<>();
    /** ruleId → wallId 反查索引。 */
    private final ConcurrentHashMap<String, String> wallByRule = new ConcurrentHashMap<>();

    /** 墙级 mutation 监听者(照 SessionManager wallDeleteHooks 风格)。 */
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** @param dao 可 null(纯内存测试装配) */
    public ScriptStore(Logger log, @Nullable ScriptDao dao, int maxRulesPerWall) {
        this.log = log;
        this.dao = dao;
        this.maxRulesPerWall = Math.max(1, maxRulesPerWall);
    }

    /**
     * 创建规则。忽略 incoming.id / incoming.wallId,生成新 id("sr-" + 8 个十六进制字符,
     * SecureRandom;查重碰撞重生成);配额超限抛 {@link QuotaExceededException}。
     * sortOrder = dao.maxSortOrder(wallId)+1(dao null 时用内存序)。
     */
    public ScriptRule create(String wallId, ScriptRule incoming) {
        ScriptRule[] out = new ScriptRule[1];
        byWall.compute(wallId, (w, cur) -> {
            List<ScriptRule> list = cur == null ? List.of() : cur;
            int max = maxRulesPerWall;
            if (list.size() >= max) {
                throw new QuotaExceededException(
                        "wall " + wallId + " 脚本数已达上限 " + max);
            }
            String id = freshId();
            ScriptRule rule = new ScriptRule(id, wallId, incoming.enabled(), incoming.name(),
                    incoming.trigger(), incoming.actions(), incoming.blockLayout());
            // 先落库再换内存:dao 抛异常 → compute 传播,mapping 不变
            if (dao != null) {
                int sortOrder = dao.maxSortOrder(wallId) + 1;
                dao.insert(rule, sortOrder, System.currentTimeMillis());
            }
            List<ScriptRule> next = new ArrayList<>(list);
            next.add(rule);
            wallByRule.put(id, wallId);
            out[0] = rule;
            return List.copyOf(next);
        });
        notifyWall(wallId);
        return out[0];
    }

    /**
     * 全量更新规则(保留原 id/wallId,其余字段取 incoming)。
     * 通过 ruleId → wallId 反查索引定位墙;没有 → {@link NotFoundException}。
     */
    public ScriptRule update(String ruleId, ScriptRule incoming) {
        String wallId = wallByRule.get(ruleId);
        if (wallId == null) throw new NotFoundException("脚本规则不存在: " + ruleId);
        ScriptRule[] out = new ScriptRule[1];
        byWall.compute(wallId, (w, cur) -> {
            int idx = indexOf(cur, ruleId);
            if (idx < 0) throw new NotFoundException("脚本规则不存在: " + ruleId);
            ScriptRule merged = new ScriptRule(ruleId, wallId, incoming.enabled(),
                    incoming.name(), incoming.trigger(), incoming.actions(),
                    incoming.blockLayout());
            if (dao != null) dao.update(merged, System.currentTimeMillis());
            List<ScriptRule> next = new ArrayList<>(cur);
            next.set(idx, merged);
            out[0] = merged;
            return List.copyOf(next);
        });
        notifyWall(wallId);
        return out[0];
    }

    /** 删除规则。不存在 → {@link NotFoundException}。 */
    public void delete(String wallId, String ruleId) {
        byWall.compute(wallId, (w, cur) -> {
            int idx = indexOf(cur, ruleId);
            if (idx < 0) throw new NotFoundException("脚本规则不存在: " + ruleId);
            // dao.delete 返 0 也照样清内存(DB 已无此行,内存收敛到 DB)
            if (dao != null) dao.delete(ruleId);
            List<ScriptRule> next = new ArrayList<>(cur);
            next.remove(idx);
            wallByRule.remove(ruleId);
            return next.isEmpty() ? null : List.copyOf(next);
        });
        notifyWall(wallId);
    }

    /** 翻转 enabled。不存在 → {@link NotFoundException}。返回更新后的规则。 */
    public ScriptRule setEnabled(String wallId, String ruleId, boolean enabled) {
        ScriptRule[] out = new ScriptRule[1];
        byWall.compute(wallId, (w, cur) -> {
            int idx = indexOf(cur, ruleId);
            if (idx < 0) throw new NotFoundException("脚本规则不存在: " + ruleId);
            ScriptRule old = cur.get(idx);
            if (dao != null) dao.setEnabled(ruleId, enabled, System.currentTimeMillis());
            ScriptRule flipped = new ScriptRule(old.id(), old.wallId(), enabled,
                    old.name(), old.trigger(), old.actions(), old.blockLayout());
            List<ScriptRule> next = new ArrayList<>(cur);
            next.set(idx, flipped);
            out[0] = flipped;
            return List.copyOf(next);
        });
        notifyWall(wallId);
        return out[0];
    }

    /** 该墙规则不可变快照;无墙返回空 list(同样不可变)。 */
    public List<ScriptRule> listByWall(String wallId) {
        return byWall.getOrDefault(wallId, List.of());
    }

    /**
     * 全墙不可变快照(TriggerRouter rebuildAll / wallReady 直查用)。
     * {@code Map.copyOf} 浅拷贝即不可变——值本就是 {@code List.copyOf} 快照。
     */
    public Map<String, List<ScriptRule>> snapshotAll() {
        return Map.copyOf(byWall);
    }

    /** 注册墙级 mutation 监听(装配期调用;CopyOnWriteArrayList 线程安全)。 */
    public void addListener(Listener l) {
        listeners.add(l);
    }

    public Optional<ScriptRule> find(String wallId, String ruleId) {
        for (ScriptRule r : listByWall(wallId)) {
            if (r.id().equals(ruleId)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** wall delete hook;内存清(DB 行由 FK CASCADE 随 walls 删除自动清)。 */
    public void clearWall(String wallId) {
        List<ScriptRule> removed = byWall.remove(wallId);
        if (removed != null) {
            for (ScriptRule r : removed) wallByRule.remove(r.id());
            // 状态真有变化才通知(无规则的墙 clear 是 no-op)
            notifyWall(wallId);
        }
    }

    /**
     * 启动期从 DB 全量替换内存。坏 blob 已在 Dao 层单行跳过 + SEVERE;
     * 整体查询失败由 {@link ScriptDao#loadAll} 异常传播(启动期失败应外响,不静默吞)。
     * 非并发安全 vs 同时写——只应在 onEnable 装配期调用。
     */
    public void loadFromDb() {
        if (dao == null) return;
        Map<String, List<ScriptRule>> all = dao.loadAll();
        byWall.clear();
        wallByRule.clear();
        for (Map.Entry<String, List<ScriptRule>> e : all.entrySet()) {
            List<ScriptRule> rules = List.copyOf(e.getValue());
            if (rules.isEmpty()) continue;
            byWall.put(e.getKey(), rules);
            for (ScriptRule r : rules) wallByRule.put(r.id(), e.getKey());
        }
        log.info("ScriptStore loaded " + wallByRule.size() + " rule(s) across "
                + byWall.size() + " wall(s)");
        // 对加载到的每面墙各通知一次(启动恢复让 Router 建索引)
        for (String wallId : byWall.keySet()) {
            notifyWall(wallId);
        }
    }

    /** /canvas reload 热更配额(只影响后续 create,不裁剪已有)。 */
    public void setMaxRulesPerWall(int max) {
        this.maxRulesPerWall = Math.max(1, max);
    }

    // ──────────────────────────────────────────────────────────
    //  内部
    // ──────────────────────────────────────────────────────────

    /**
     * mutation 落定后(compute 外)逐 listener 通知;
     * 异常 try-catch 隔离 + WARNING(照 SessionManager wallDeleteHooks 风格),
     * 单个 listener 抛不影响 store 操作结果与其余 listener。
     */
    private void notifyWall(String wallId) {
        for (Listener l : listeners) {
            try {
                l.onWallScriptsChanged(wallId);
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "ScriptStore listener threw for wall " + wallId, e);
            }
        }
    }

    private int indexOf(@Nullable List<ScriptRule> list, String ruleId) {
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(ruleId)) return i;
        }
        return -1;
    }

    /**
     * "sr-" + 8 hex;对全局反查索引查重,碰撞重生成。
     * 跨墙并发碰撞穿透时(两墙同时 compute、查重都通过)由 DB PRIMARY KEY 兜底:
     * 第二条 insert 抛 → compute 传播,内存不动。
     */
    private String freshId() {
        while (true) {
            String id = String.format("sr-%08x", RANDOM.nextInt());
            if (!wallByRule.containsKey(id)) return id;
        }
    }
}
