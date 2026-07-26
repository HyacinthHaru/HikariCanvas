package ac.haru.hikaricanvas.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
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
 * walls 表 DAO。契约见 {@code docs/data-model.md §2.4}。
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
     * 原子地写一行 walls（含 mapIds）。confirm 路径先 reserve 拿 mapIds，
     * 再一次性 INSERT，避免旧的「先 create 后 updateMapIds」两步无事务半态（旧路径若第二步
     * 失败，walls 行 mapIds 字段为空字符串，但 mapPool 已 reserve）。
     *
     * @return 生成的 wall_id；冲突 5 次则抛
     */
    public String createWithMapIds(WallKey key, ProjectState state, List<Integer> mapIds,
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
                jdbi.useTransaction(h -> h.createUpdate(
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
                if (msg != null && msg.contains("UNIQUE constraint failed: walls.wall_id")) {
                    continue; // wall_id 撞了重试
                }
                throw new IllegalStateException("createWithMapIds failed", e);
            }
        }
        throw new IllegalStateException("wall_id collision 5x; SecureRandom broken?");
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

    /**
     * 全部 wall。<b>单行坏数据跳过而非毒化整个查询</b>。
     *
     * <p>{@link #mapRow} 只对 {@code owner_uuid} 做单行降级；{@code project_json} 反序列化失败
     * 或 {@code facing} 非法（{@code BlockFace.valueOf}）会抛 SQLException 冒泡到这里，原实现
     * 一律 catch 后返回空表 —— 启动期 {@code WallRestorer} 因此恢复 0 面墙且不报错。改为逐行
     * try/catch 跳过坏行并计数。</p>
     *
     * <p><b>泄漏检测不要用本方法</b>：它仍可能返回残缺集合，而 {@code MapPool.detectLeaks} 把
     * 传入集合当 live wall 全集，缺谁就释放谁的地图。那条路径走
     * {@link #loadAllWallIds()}（只读 wall_id 列 + 失败显式返回 empty）。</p>
     */
    public List<Wall> loadAll() {
        try {
            List<Wall> rows = jdbi.withHandle(h -> h.createQuery("SELECT * FROM walls")
                    .map((rs, ctx) -> {
                        try {
                            return mapRow(rs);
                        } catch (Exception rowEx) {
                            String badId;
                            try {
                                badId = rs.getString("wall_id");
                            } catch (Exception ignored) {
                                badId = "<unreadable>";
                            }
                            log.log(Level.WARNING,
                                    "loadAll: skipping unreadable wall row wallId=" + badId, rowEx);
                            return null;
                        }
                    })
                    .list());
            List<Wall> ok = new ArrayList<>(rows.size());
            for (Wall w : rows) {
                if (w != null) ok.add(w);
            }
            if (ok.size() != rows.size()) {
                log.warning("loadAll: " + (rows.size() - ok.size()) + " of " + rows.size()
                        + " wall row(s) unreadable and skipped");
            }
            return ok;
        } catch (Exception e) {
            log.log(Level.WARNING, "loadAll failed", e);
            return List.of();
        }
    }

    /**
     * 全部 wall 的 id 集合，<b>供 {@code MapPool.detectLeaks} 判定 live wall 专用</b>。
     *
     * <p>只 SELECT {@code wall_id} 一列——不碰 {@code project_json} 反序列化、不碰
     * {@code BlockFace.valueOf}，从根上消除「单行坏数据毒化整表」这一整类风险。</p>
     *
     * <p><b>失败必须显式可辨</b>：返回 {@link Optional#empty()} 而非空集合。泄漏检测把传入集合
     * 当 live wall <b>全集</b>，空集合会让全部非 pending 的 RESERVED map 被判泄漏、强制 FREE
     * 并落盘，随后被新墙复用 → 旧墙 ItemFrame 显示新墙像素（跨墙串台，全服级数据损坏）。
     * 与 {@code data-model.md §7.1}「walls 表<b>无对应行</b>才视为泄漏」的语义一致：
     * 读失败 ≠ 表无行。</p>
     */
    public Optional<java.util.Set<String>> loadAllWallIds() {
        try {
            List<String> ids = jdbi.withHandle(h -> h.createQuery("SELECT wall_id FROM walls")
                    .map((rs, ctx) -> rs.getString("wall_id"))
                    .list());
            java.util.Set<String> out = new java.util.HashSet<>(ids.size() * 2);
            for (String id : ids) {
                if (id != null) out.add(id);
            }
            return Optional.of(out);
        } catch (Exception e) {
            log.log(Level.SEVERE, "loadAllWallIds failed; leak detection must be skipped", e);
            return Optional.empty();
        }
    }

    /**
     * 事务感知 {@link #loadAll()}。在调用方已持有的 {@link org.jdbi.v3.core.Handle} 上跑同一查询，
     * 让"读 walls 引用 → 删孤儿图"在单一 IMMEDIATE 写事务的一致视图内原子完成（图片上传 LRU
     * evict 路径用）。与 {@link #loadAll()} 不同：本方法**不吞异常**——它跑在外层事务内，
     * 异常应让外层事务回滚（宁可拒上传也不在不可信引用集下误删在用图）。
     */
    public List<Wall> loadAllOn(org.jdbi.v3.core.Handle h) {
        return h.createQuery("SELECT * FROM walls")
                .map((rs, ctx) -> mapRow(rs))
                .list();
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
                            toLong(rs.getObject("published_at")),
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
                            toLong(rs.getObject("published_at")),
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

    /**
     * 删除 walls 行。<b>返回是否真的删掉了</b>——调用方必须看返回值。
     *
     * <p>原实现是 void + catch 后只写日志：删除因 {@code SQLITE_BUSY}（默认 5s
     * busy_timeout 内没抢到写锁，比如同时在跑图片配额清理的大事务）失败时，调用方
     * {@code SessionManager.deleteWall} 照样返回成功。而它是<b>先</b>把这面墙的地图释放回
     * FREE 池、<b>后</b>删行的，于是留下最坏的组合：walls 行还在（玩家看得到、还能 open），
     * 它引用的地图却已被下一面墙借走 → 两面墙共用一张地图互相覆盖像素。
     *
     * <p>返回 false 时调用方应当把地图释放回滚掉（或者干脆把顺序改成先删行、成功了再放地图），
     * 至少要让玩家知道这次删除没成功，而不是报个成功了事。</p>
     *
     * @return {@code true} = 确实删掉了行；{@code false} = 数据库写失败，或者本来就没有这一行
     */
    public boolean delete(String wallId) {
        try {
            int affected = jdbi.withHandle(h -> h.createUpdate("DELETE FROM walls WHERE wall_id = :id")
                    .bind("id", wallId)
                    .execute());
            if (affected == 0) {
                log.warning("delete: no walls row for " + wallId + " (already gone?)");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.log(Level.SEVERE, "delete failed: " + wallId
                    + " — the wall row is still there while its maps may already have been"
                    + " returned to the pool; caller must not report success", e);
            return false;
        }
    }

    /**
     * 写回模板来源。{@code template.apply} 成功后调用，supports {@code /canvas list} 显示
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

    // ---------- project_json v1 → v2 lazy migration ----------

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
            String ownerRaw = rs.getString("owner_uuid");
            UUID owner;
            try {
                owner = UUID.fromString(ownerRaw);
            } catch (IllegalArgumentException ex) {
                // 外部 DB 数据损坏的防御性降级（与 RailDao/UserGlobalVariableDao 看齐）——
                // nil UUID 匹配 owner 失败更受限（安全），且单行坏数据不阻断整查询。
                log.log(Level.WARNING, "WallRepo.mapRow owner_uuid parse failed, wallId="
                        + rs.getString("wall_id") + ", owner=" + ownerRaw, ex);
                owner = new UUID(0L, 0L);
            }
            return new Wall(
                    rs.getString("wall_id"),
                    key,
                    ps,
                    mapIds,
                    rs.getInt("width_maps"),
                    rs.getInt("height_maps"),
                    owner,
                    rs.getString("owner_name"),
                    rs.getString("alias"),
                    toLong(rs.getObject("published_at")),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        } catch (Exception e) {
            throw new java.sql.SQLException("mapRow failed", e);
        }
    }

    /**
     * robust nullable-long 读取。SQLite JDBC 对 {@code INTEGER} 列的
     * {@code getObject} 不保证返回 {@link Long}（可能是 {@link Integer} / {@link java.math.BigDecimal}
     * 等），直接 {@code (Long)} 强转会 ClassCastException。统一走 {@link Number#longValue()}。
     */
    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
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
