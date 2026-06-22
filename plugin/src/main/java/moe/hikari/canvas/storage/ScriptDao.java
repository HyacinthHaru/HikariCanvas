package moe.hikari.canvas.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.script.ScriptRule;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code wall_scripts} 表 DAO（0.7.0 P1）。schema 见
 * {@code db-migrations/V017__wall_scripts.sql} + 契约 {@code docs/scripting.md §2}。
 *
 * <p><b>rule_json 整体存</b>：{@link ScriptRule} 经 Jackson 序列化整体落
 * {@code rule_json} 列（trigger/actions/blockLayout 全在内，照 project_json 范式不拆列）。
 * 类型注解（Trigger/Action 双向多态 serializer）自带，直接 {@code new ObjectMapper()} 即可。</p>
 *
 * <p><b>enabled 列权威</b>：{@code enabled} 列是查询真相；{@link #setEnabled} 只翻列、
 * 不重写 rule_json。load 时若 blob 内 enabled 与列不一致，以列为准重建 record——避免两处漂移。</p>
 *
 * <p><b>坏 blob 跳过</b>：{@link #loadByWall} / {@link #loadAll} 遇到反序列化失败的行
 * SEVERE log + 跳过，不拖垮整墙加载。</p>
 *
 * <p><b>写路径异常传播</b>：与 {@link ScheduleDao} 的"吞 + log"不同，insert/update/delete/
 * setEnabled 不捕获——{@link moe.hikari.canvas.script.ScriptStore} 依赖"先落库再换内存"，
 * DAO 抛异常时内存不动。读路径分两档：{@link #loadByWall} 整体失败防御式返回空（运行期
 * 单墙加载不拖垮全局）；{@link #loadAll} 整体失败<b>异常传播</b>（启动期失败应外响）。</p>
 */
public class ScriptDao {

    /** 类内单例 mapper：ScriptRule 序列化形态固定，无需外部配置。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger log;
    private final Jdbi jdbi;

    public ScriptDao(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    /** 插入一条规则。created_at = updated_at = now。异常传播给调用方。 */
    public void insert(ScriptRule rule, int sortOrder, long now) {
        String json = toJson(rule);
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO wall_scripts(id, wall_id, enabled, name, rule_json, "
                        + "sort_order, created_at, updated_at) "
                        + "VALUES(:id, :w, :en, :name, :json, :so, :now, :now)")
                .bind("id", rule.id())
                .bind("w", rule.wallId())
                .bind("en", rule.enabled() ? 1 : 0)
                .bind("name", rule.name())
                .bind("json", json)
                .bind("so", sortOrder)
                .bind("now", now)
                .execute());
    }

    /** 按 id 全量替换 rule_json / name / enabled + updated_at（不动 sort_order / created_at）。 */
    public void update(ScriptRule rule, long now) {
        String json = toJson(rule);
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE wall_scripts SET enabled = :en, name = :name, "
                        + "rule_json = :json, updated_at = :now WHERE id = :id")
                .bind("id", rule.id())
                .bind("en", rule.enabled() ? 1 : 0)
                .bind("name", rule.name())
                .bind("json", json)
                .bind("now", now)
                .execute());
    }

    /** 删除单条规则。返回受影响行数（0 = id 不存在）。 */
    public int delete(String ruleId) {
        return jdbi.withHandle(h -> h.createUpdate(
                "DELETE FROM wall_scripts WHERE id = :id")
                .bind("id", ruleId)
                .execute());
    }

    /** 只翻 enabled 列 + updated_at，不动 rule_json（load 时列覆写 blob 内值）。 */
    public void setEnabled(String ruleId, boolean enabled, long now) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE wall_scripts SET enabled = :en, updated_at = :now WHERE id = :id")
                .bind("id", ruleId)
                .bind("en", enabled ? 1 : 0)
                .bind("now", now)
                .execute());
    }

    /**
     * 加载一面墙的全部规则，按 {@code sort_order ASC, created_at ASC} 排序。
     * 坏 blob 跳过 + SEVERE；整体查询失败返回空 list（读路径防御式）。
     */
    public List<ScriptRule> loadByWall(String wallId) {
        try {
            List<Row> rows = jdbi.withHandle(h -> h.createQuery(
                    "SELECT id, wall_id, enabled, rule_json FROM wall_scripts "
                            + "WHERE wall_id = :w ORDER BY sort_order ASC, created_at ASC")
                    .bind("w", wallId)
                    .map((rs, ctx) -> new Row(
                            rs.getString("id"),
                            rs.getString("wall_id"),
                            rs.getInt("enabled") != 0,
                            rs.getString("rule_json")))
                    .list());
            List<ScriptRule> out = new ArrayList<>(rows.size());
            for (Row row : rows) {
                ScriptRule rule = parseRow(row);
                if (rule != null) out.add(rule);
            }
            return out;
        } catch (Exception e) {
            log.log(Level.WARNING, "ScriptDao.loadByWall failed: " + wallId, e);
            return List.of();
        }
    }

    /**
     * 启动期一次性加载所有规则，按 wall 分桶；每墙内与 {@link #loadByWall} 同序。
     * 坏 blob 单行跳过 + SEVERE；<b>整体查询失败异常传播</b>——启动期 DB 不可用
     * 应与 MigrationRunner 失败同级响起来，不静默吞成"零规则"。
     */
    public Map<String, List<ScriptRule>> loadAll() {
        List<Row> rows = jdbi.withHandle(h -> h.createQuery(
                "SELECT id, wall_id, enabled, rule_json FROM wall_scripts "
                        + "ORDER BY wall_id, sort_order ASC, created_at ASC")
                .map((rs, ctx) -> new Row(
                        rs.getString("id"),
                        rs.getString("wall_id"),
                        rs.getInt("enabled") != 0,
                        rs.getString("rule_json")))
                .list());
        Map<String, List<ScriptRule>> out = new LinkedHashMap<>();
        for (Row row : rows) {
            ScriptRule rule = parseRow(row);
            if (rule != null) {
                out.computeIfAbsent(row.wallId, k -> new ArrayList<>()).add(rule);
            }
        }
        return out;
    }

    /** 该墙当前最大 sort_order；无规则返 -1（ScriptStore 用 +1 追加到尾部）。 */
    public int maxSortOrder(String wallId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COALESCE(MAX(sort_order), -1) FROM wall_scripts WHERE wall_id = :w")
                .bind("w", wallId)
                .mapTo(Integer.class)
                .one());
    }

    // ──────────────────────────────────────────────────────────
    //  内部
    // ──────────────────────────────────────────────────────────

    /**
     * 解析单行：坏 blob 返 null + SEVERE。id / wallId / enabled 以列值为权威重建 record
     * （enabled 双写防漂移，见类注释）。
     */
    private ScriptRule parseRow(Row row) {
        ScriptRule parsed;
        try {
            parsed = MAPPER.readValue(row.ruleJson, ScriptRule.class);
        } catch (Exception e) {
            log.log(Level.SEVERE, "wall_scripts corrupt blob, skipping rule: id=" + row.id
                    + " wall=" + row.wallId, e);
            return null;
        }
        if (parsed.enabled() == row.enabled
                && row.id.equals(parsed.id())
                && row.wallId.equals(parsed.wallId())) {
            return parsed;
        }
        return new ScriptRule(row.id, row.wallId, row.enabled,
                parsed.name(), parsed.trigger(), parsed.actions(), parsed.blockLayout());
    }

    private static String toJson(ScriptRule rule) {
        try {
            return MAPPER.writeValueAsString(rule);
        } catch (JsonProcessingException e) {
            // ScriptRule 全 POJO/record，序列化失败属编程错误；包成 unchecked 传播
            throw new IllegalStateException("ScriptRule 序列化失败: id=" + rule.id(), e);
        }
    }

    /** 内部行视图。 */
    private record Row(String id, String wallId, boolean enabled, String ruleJson) {}
}
