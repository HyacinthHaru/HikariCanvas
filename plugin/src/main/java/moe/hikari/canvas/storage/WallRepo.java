package moe.hikari.canvas.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.session.WallKey;
import moe.hikari.canvas.state.ProjectState;
import org.bukkit.block.BlockFace;
import org.jdbi.v3.core.Jdbi;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * walls 表 DAO（M5.5 起替代 DraftRepo + sign_records）。契约见 {@code docs/data-model.md §2.4}。
 *
 * <p>每行 = 一面墙上的一幅画，主键 {@code wall_id}（玩家可见短 ID `w-<8hex>`），
 * `(world, origin, facing)` 唯一。{@code published_at} 是 UI 标签层 nullable timestamp，
 * 不影响底层渲染或池行为。</p>
 */
public final class WallRepo {

    /**
     * walls 表一行的内存视图。
     * @param wallId       主键，玩家可见
     * @param key          位置标识（world/origin/facing）
     * @param state        ProjectState 反序列化结果
     * @param mapIds       占用的 map id 列表
     * @param widthMaps    canvas 宽度（地图单位）
     * @param heightMaps   canvas 高度（地图单位）
     * @param ownerUuid    创建者
     * @param ownerName    创建者名（冗余）
     * @param alias        玩家命名（nullable）
     * @param publishedAt  发布时间戳（nullable）
     * @param createdAt    行创建时间
     * @param updatedAt    行最后修改时间
     */
    public record Wall(
            String wallId,
            WallKey key,
            ProjectState state,
            List<Integer> mapIds,
            int widthMaps,
            int heightMaps,
            UUID ownerUuid,
            String ownerName,
            String alias,
            Long publishedAt,
            long createdAt,
            long updatedAt
    ) {}

    /** 极简查询投影：只取 wall_id + 元数据，不反序列化 ProjectState。/canvas list 用。 */
    public record Summary(
            String wallId,
            String alias,
            String ownerName,
            String world,
            int originX, int originY, int originZ,
            String facing,
            int widthMaps, int heightMaps,
            Long publishedAt,
            long updatedAt
    ) {}

    private static final SecureRandom RNG = new SecureRandom();

    private final Logger log;
    private final Jdbi jdbi;
    private final ObjectMapper mapper;

    public WallRepo(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
        this.mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 创建一个新 wall 行，自动生成 wall_id。返回生成的 wall_id；冲突 5 次则抛。
     * 调用方应在 confirm（新建路径）时一次调用，之后通过 {@link #updateState} 增量更新。
     */
    public String create(WallKey key, ProjectState state, List<Integer> mapIds,
                         int widthMaps, int heightMaps, UUID ownerUuid, String ownerName) {
        String json;
        try {
            json = mapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException("serialize ProjectState failed", e);
        }
        String mapIdsCsv = mapIds == null ? "" :
                mapIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        long now = System.currentTimeMillis();

        for (int attempt = 0; attempt < 5; attempt++) {
            String wallId = generateWallId();
            try {
                jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO walls(wall_id, world, origin_x, origin_y, origin_z, facing, "
                                + "width_maps, height_maps, map_ids, project_json, "
                                + "owner_uuid, owner_name, alias, published_at, "
                                + "template_id, template_version, created_at, updated_at) "
                                + "VALUES(:id, :world, :ox, :oy, :oz, :facing, "
                                + ":w, :h, :mapIds, :json, :ouuid, :oname, NULL, NULL, "
                                + "NULL, NULL, :now, :now)")
                        .bind("id", wallId)
                        .bind("world", key.world())
                        .bind("ox", key.originX())
                        .bind("oy", key.originY())
                        .bind("oz", key.originZ())
                        .bind("facing", key.facing().name())
                        .bind("w", widthMaps)
                        .bind("h", heightMaps)
                        .bind("mapIds", mapIdsCsv)
                        .bind("json", json)
                        .bind("ouuid", ownerUuid.toString())
                        .bind("oname", ownerName)
                        .bind("now", now)
                        .execute());
                return wallId;
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("UNIQUE constraint failed: walls.wall_id"))) {
                    continue; // wall_id 撞了重试
                }
                throw new IllegalStateException("create wall failed", e);
            }
        }
        throw new IllegalStateException("wall_id collision 5x; SecureRandom broken?");
    }

    /** 更新 mapIds（confirm 流程：create 后 reserve 再回写）。 */
    public void updateMapIds(String wallId, List<Integer> mapIds) {
        String csv = mapIds == null ? "" :
                mapIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        long now = System.currentTimeMillis();
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "UPDATE walls SET map_ids = :csv, updated_at = :now WHERE wall_id = :id")
                    .bind("csv", csv)
                    .bind("now", now)
                    .bind("id", wallId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "updateMapIds failed for " + wallId, e);
        }
    }

    /** 仅更新 ProjectState（每次 op 后由 SessionManager 调）。 */
    public void updateState(String wallId, ProjectState state) {
        try {
            String json = mapper.writeValueAsString(state);
            long now = System.currentTimeMillis();
            jdbi.useHandle(h -> h.createUpdate(
                    "UPDATE walls SET project_json = :json, updated_at = :now WHERE wall_id = :id")
                    .bind("json", json)
                    .bind("now", now)
                    .bind("id", wallId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "updateState failed for wall_id=" + wallId, e);
        }
    }

    public Optional<Wall> loadById(String wallId) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM walls WHERE wall_id = :id")
                    .bind("id", wallId)
                    .map((rs, ctx) -> mapRow(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "loadById failed: " + wallId, e);
            return Optional.empty();
        }
    }

    public Optional<Wall> loadByAlias(String alias) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM walls WHERE alias = :a")
                    .bind("a", alias)
                    .map((rs, ctx) -> mapRow(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "loadByAlias failed: " + alias, e);
            return Optional.empty();
        }
    }

    public Optional<Wall> loadByKey(WallKey key) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM walls WHERE world = :w AND origin_x = :x "
                            + "AND origin_y = :y AND origin_z = :z AND facing = :f")
                    .bind("w", key.world())
                    .bind("x", key.originX())
                    .bind("y", key.originY())
                    .bind("z", key.originZ())
                    .bind("f", key.facing().name())
                    .map((rs, ctx) -> mapRow(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "loadByKey failed: " + key, e);
            return Optional.empty();
        }
    }

    /**
     * 反查：含某 mapId 的 wall（wand 瞄 ItemFrame 用）。
     * map_ids 是 CSV，"123,456,789"——LIKE 匹配需考虑边界（开头/末尾/中间/单个）。
     */
    public Optional<Wall> loadByMapId(int mapId) {
        String s = String.valueOf(mapId);
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM walls WHERE map_ids = :exact "
                            + "OR map_ids LIKE :head OR map_ids LIKE :tail OR map_ids LIKE :mid")
                    .bind("exact", s)
                    .bind("head", s + ",%")
                    .bind("tail", "%," + s)
                    .bind("mid",  "%," + s + ",%")
                    .map((rs, ctx) -> mapRow(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "loadByMapId failed: " + mapId, e);
            return Optional.empty();
        }
    }

    public List<Wall> loadAll() {
        try {
            return jdbi.withHandle(h -> h.createQuery("SELECT * FROM walls")
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "loadAll failed", e);
            return List.of();
        }
    }

    /** 列全部 walls 的 Summary（首页用）。 */
    public List<Summary> listAll() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT wall_id, alias, owner_name, world, origin_x, origin_y, origin_z, "
                            + "facing, width_maps, height_maps, published_at, updated_at "
                            + "FROM walls ORDER BY updated_at DESC")
                    .map((rs, ctx) -> new Summary(
                            rs.getString("wall_id"),
                            rs.getString("alias"),
                            rs.getString("owner_name"),
                            rs.getString("world"),
                            rs.getInt("origin_x"), rs.getInt("origin_y"), rs.getInt("origin_z"),
                            rs.getString("facing"),
                            rs.getInt("width_maps"), rs.getInt("height_maps"),
                            (Long) rs.getObject("published_at"),
                            rs.getLong("updated_at")))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "listAll failed", e);
            return List.of();
        }
    }

    public List<Summary> listForOwner(UUID ownerUuid) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT wall_id, alias, owner_name, world, origin_x, origin_y, origin_z, "
                            + "facing, width_maps, height_maps, published_at, updated_at "
                            + "FROM walls WHERE owner_uuid = :u ORDER BY updated_at DESC")
                    .bind("u", ownerUuid.toString())
                    .map((rs, ctx) -> new Summary(
                            rs.getString("wall_id"),
                            rs.getString("alias"),
                            rs.getString("owner_name"),
                            rs.getString("world"),
                            rs.getInt("origin_x"), rs.getInt("origin_y"), rs.getInt("origin_z"),
                            rs.getString("facing"),
                            rs.getInt("width_maps"), rs.getInt("height_maps"),
                            (Long) rs.getObject("published_at"),
                            rs.getLong("updated_at")))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "listForOwner failed: " + ownerUuid, e);
            return List.of();
        }
    }

    /** @return false 表示 alias 已被其他 wall 占用。 */
    public boolean setAlias(String wallId, String alias) {
        long now = System.currentTimeMillis();
        try {
            int rows = jdbi.withHandle(h -> h.createUpdate(
                    "UPDATE walls SET alias = :a, updated_at = :now WHERE wall_id = :id")
                    .bind("a", alias)
                    .bind("now", now)
                    .bind("id", wallId)
                    .execute());
            return rows == 1;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("UNIQUE constraint failed: walls.alias")) {
                return false;
            }
            log.log(Level.WARNING, "setAlias failed: " + wallId, e);
            return false;
        }
    }

    public Long markPublished(String wallId) {
        long now = System.currentTimeMillis();
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "UPDATE walls SET published_at = :now, updated_at = :now WHERE wall_id = :id")
                    .bind("now", now)
                    .bind("id", wallId)
                    .execute());
            return now;
        } catch (Exception e) {
            log.log(Level.WARNING, "markPublished failed: " + wallId, e);
            return null;
        }
    }

    public void markUnpublished(String wallId) {
        long now = System.currentTimeMillis();
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "UPDATE walls SET published_at = NULL, updated_at = :now WHERE wall_id = :id")
                    .bind("now", now)
                    .bind("id", wallId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "markUnpublished failed: " + wallId, e);
        }
    }

    public void delete(String wallId) {
        try {
            jdbi.useHandle(h -> h.createUpdate("DELETE FROM walls WHERE wall_id = :id")
                    .bind("id", wallId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "delete failed: " + wallId, e);
        }
    }

    /**
     * M6-D：写回模板来源。{@code template.apply} 成功后调用，supports {@code /canvas list} 显示
     * "出自哪个模板" + 后续审计。templateId 可为 {@code null}（手动清除）。
     */
    public void setTemplate(String wallId, String templateId, Integer templateVersion) {
        long now = System.currentTimeMillis();
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "UPDATE walls SET template_id = :tid, template_version = :tv, "
                            + "updated_at = :now WHERE wall_id = :id")
                    .bind("tid", templateId)
                    .bind("tv", templateVersion)
                    .bind("now", now)
                    .bind("id", wallId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "setTemplate failed: " + wallId, e);
        }
    }

    // ---------- M8-B：project_json v1 → v2 lazy migration ----------

    /** {@link #migrateAllToV2} 返回的统计。 */
    public record MigrationStats(int scanned, int migrated, int failed) {
        public static final MigrationStats EMPTY = new MigrationStats(0, 0, 0);
    }

    /**
     * 检测 project_json 是否为 v1 形态（含顶层 {@code elements} 但无 {@code layers}）。
     *
     * <p>极简空工程（既无 elements 也无 layers）返 {@code false} —— 它会被
     * {@link ProjectState.JsonCreator} 当作 "新工程" 包装，本质上也是 v1 → v2
     * 升级，但行为等价于一次 noop migrate，无需特殊处理。</p>
     */
    public static boolean isV1Form(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonNode root = mapper.readTree(json);
            return root.has("elements") && !root.has("layers");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把 v1 形态的 project_json 升级到 v2 形态。流程：
     * <ol>
     *   <li>Jackson 反序列化为 {@link ProjectState} —— 内置 {@code @JsonCreator}
     *       已经能识别 {@code elements} 字段并自动包成单一 "Default Layer"</li>
     *   <li>重新序列化为字符串，得到 v2 形态 JSON（含 {@code layers / activeLayerId /
     *       protocolVersion / canvas.gridSize / canvas.guides}）</li>
     * </ol>
     *
     * <p>如果输入已是 v2 形态，方法仍能完成 roundtrip 并返回等价 v2 JSON；
     * 但调用方可用 {@link #isV1Form} 提前过滤避免无意义写。</p>
     *
     * @throws Exception 反序列化或序列化失败；调用方应 catch 后 log warn 跳过坏数据
     */
    public static String migrateProjectJsonV1ToV2(ObjectMapper mapper, String json) throws Exception {
        ProjectState ps = mapper.readValue(json, ProjectState.class);
        return mapper.writeValueAsString(ps);
    }

    /**
     * 启动期一次性扫描 walls 表，把 {@code protocol_version &lt; 2} 的行升级到 v2。
     * 实现细节见 {@code docs/data-model.md §2.4.1}（决策 A：startup full-scan）。
     *
     * <p>容错策略：单行 migrate 失败 → log warn + 该行 protocol_version 保留为 1，
     * 启动继续。下次启动会再尝试；运行期 {@code /canvas open <id>} 也会再次走 lazy 路径
     * （因 {@link ProjectState.JsonCreator} 永远兼容 v1）。</p>
     *
     * @return 统计：scanned = 命中行数；migrated = 成功写回数；failed = 出错跳过数
     */
    public MigrationStats migrateAllToV2() {
        List<Row> rows;
        try {
            rows = jdbi.withHandle(h -> h.createQuery(
                    "SELECT wall_id, project_json FROM walls WHERE protocol_version < 2")
                    .map((rs, ctx) -> new Row(rs.getString("wall_id"), rs.getString("project_json")))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "migrateAllToV2: scan failed; skipping migration", e);
            return MigrationStats.EMPTY;
        }
        if (rows.isEmpty()) return MigrationStats.EMPTY;

        int scanned = 0, migrated = 0, failed = 0;
        for (Row r : rows) {
            scanned++;
            try {
                String newJson = migrateProjectJsonV1ToV2(mapper, r.json);
                // 始终把 protocol_version 标 2 —— 即便内容本就是 v2、roundtrip 没动也算"已确认"，
                // 避免下次启动重复扫
                jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE walls SET project_json = :j, protocol_version = 2 "
                                + "WHERE wall_id = :id")
                        .bind("j", newJson)
                        .bind("id", r.wallId)
                        .execute());
                migrated++;
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "migrateAllToV2: project_json upgrade failed for wall_id=" + r.wallId
                                + " — left at v1, will retry next startup", e);
                failed++;
            }
        }
        return new MigrationStats(scanned, migrated, failed);
    }

    private record Row(String wallId, String json) {}

    // ---------- 私有 ----------

    private Wall mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            ProjectState ps = mapper.readValue(rs.getString("project_json"), ProjectState.class);
            WallKey key = new WallKey(
                    rs.getString("world"),
                    rs.getInt("origin_x"),
                    rs.getInt("origin_y"),
                    rs.getInt("origin_z"),
                    BlockFace.valueOf(rs.getString("facing")));
            List<Integer> mapIds = parseMapIds(rs.getString("map_ids"));
            return new Wall(
                    rs.getString("wall_id"),
                    key,
                    ps,
                    mapIds,
                    rs.getInt("width_maps"),
                    rs.getInt("height_maps"),
                    UUID.fromString(rs.getString("owner_uuid")),
                    rs.getString("owner_name"),
                    rs.getString("alias"),
                    (Long) rs.getObject("published_at"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        } catch (Exception e) {
            throw new java.sql.SQLException("mapRow failed", e);
        }
    }

    private static List<Integer> parseMapIds(String csv) {
        if (csv == null || csv.isEmpty()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            try { out.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static String generateWallId() {
        byte[] buf = new byte[4];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder("w-");
        for (byte b : buf) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
