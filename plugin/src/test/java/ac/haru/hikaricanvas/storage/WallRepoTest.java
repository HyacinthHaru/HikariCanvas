package ac.haru.hikaricanvas.storage;

import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WallRepo} JSON 升级迁移、单行损坏容错与删除结果契约单测。
 */
class WallRepoTest {

    private static final Logger LOG = Logger.getLogger(WallRepoTest.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Nested
    class ProjectJsonMigration {

        private static final String V1_JSON = """
                {
                  "version": 7,
                  "canvas": {"widthMaps": 2, "heightMaps": 1, "background": "#FFFFFF"},
                  "elements": [
                    {
                      "type": "text",
                      "id": "e-1",
                      "x": 10, "y": 20, "w": 100, "h": 30,
                      "rotation": 0, "locked": false, "visible": true,
                      "text": "hello",
                      "fontId": "ark_pixel",
                      "fontSize": 12,
                      "color": "#000000",
                      "align": "left",
                      "letterSpacing": 0.0,
                      "lineHeight": 1.2,
                      "vertical": false
                    },
                    {
                      "type": "rect",
                      "id": "e-2",
                      "x": 0, "y": 0, "w": 50, "h": 50,
                      "rotation": 0, "locked": false, "visible": true,
                      "fill": "#FF0000"
                    }
                  ],
                  "history": {"undoDepth": 0, "redoDepth": 0}
                }
                """;

        @Test
        void isV1FormDetectsV1() {
            assertTrue(WallRepo.isV1Form(MAPPER, V1_JSON));
        }

        @Test
        void isV1FormRejectsV2() throws Exception {
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, V1_JSON);
            assertFalse(WallRepo.isV1Form(MAPPER, v2));
        }

        @Test
        void isV1FormSafeOnNullAndBlank() {
            assertFalse(WallRepo.isV1Form(MAPPER, null));
            assertFalse(WallRepo.isV1Form(MAPPER, ""));
            assertFalse(WallRepo.isV1Form(MAPPER, "   "));
        }

        @Test
        void isV1FormSafeOnGarbage() {
            assertFalse(WallRepo.isV1Form(MAPPER, "not-json"));
            assertFalse(WallRepo.isV1Form(MAPPER, "{\"unrelated\":\"data\"}"));
        }

        @Test
        void migratedJsonHasLayersAndActiveLayerId() throws Exception {
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, V1_JSON);
            JsonNode root = MAPPER.readTree(v2);

            assertTrue(root.has("layers"), "missing layers");
            assertTrue(root.has("activeLayerId"), "missing activeLayerId");
            assertTrue(root.has("protocolVersion"), "missing protocolVersion");
            assertEquals(ProjectState.PROTOCOL_VERSION, root.get("protocolVersion").asInt());
            assertEquals(1, root.get("layers").size(), "should wrap into single Default Layer");
        }

        @Test
        void migratedJsonPreservesElements() throws Exception {
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, V1_JSON);
            JsonNode root = MAPPER.readTree(v2);

            JsonNode elements = root.get("layers").get(0).get("elements");
            assertEquals(2, elements.size());
            assertEquals("e-1", elements.get(0).get("id").asText());
            assertEquals("text", elements.get(0).get("type").asText());
            assertEquals("e-2", elements.get(1).get("id").asText());
            assertEquals("rect", elements.get(1).get("type").asText());
        }

        @Test
        void migratedLayerIsDefaultAndUnlocked() throws Exception {
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, V1_JSON);
            JsonNode layer = MAPPER.readTree(v2).get("layers").get(0);

            assertEquals("Default Layer", layer.get("name").asText());
            assertTrue(layer.get("visible").asBoolean());
            assertFalse(layer.get("locked").asBoolean());
            assertEquals(1.0, layer.get("opacity").asDouble(), 0.0001);
            assertEquals("normal", layer.get("blendMode").asText());
            assertTrue(layer.get("id").asText().startsWith("l-"));
        }

        @Test
        void activeLayerIdPointsToDefaultLayer() throws Exception {
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, V1_JSON);
            JsonNode root = MAPPER.readTree(v2);
            String activeId = root.get("activeLayerId").asText();
            String layerId = root.get("layers").get(0).get("id").asText();
            assertEquals(layerId, activeId);
        }

        @Test
        void migratedProjectStateDeserializesCleanly() throws Exception {
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, V1_JSON);
            ProjectState ps = MAPPER.readValue(v2, ProjectState.class);

            assertEquals(7, ps.version());
            assertEquals(2, ps.canvas().widthMaps());
            assertEquals(1, ps.canvas().heightMaps());
            assertEquals(1, ps.layers().size());
            assertEquals(2, ps.activeLayer().elements().size());
            assertEquals("e-1", ps.activeLayer().elements().get(0).id());
            assertEquals(2, ps.elements().size());
            assertEquals("e-1", ps.elements().get(0).id());
        }

        @Test
        void roundtripOnV2IsIdempotent() throws Exception {
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, V1_JSON);
            String v2Again = WallRepo.migrateProjectJsonV1ToV2(MAPPER, v2);

            ProjectState a = MAPPER.readValue(v2, ProjectState.class);
            ProjectState b = MAPPER.readValue(v2Again, ProjectState.class);
            assertEquals(a.layers().get(0).id(), b.layers().get(0).id(),
                    "layer id should survive idempotent migration");
            assertEquals(a.activeLayerId(), b.activeLayerId());
            assertEquals(a.elements().size(), b.elements().size());
        }

        @Test
        void emptyV1ElementsArrayBecomesEmptyDefaultLayer() throws Exception {
            String emptyV1 = """
                    {
                      "version": 0,
                      "canvas": {"widthMaps": 1, "heightMaps": 1, "background": "#FFFFFF"},
                      "elements": [],
                      "history": {"undoDepth": 0, "redoDepth": 0}
                    }
                    """;
            String v2 = WallRepo.migrateProjectJsonV1ToV2(MAPPER, emptyV1);
            JsonNode root = MAPPER.readTree(v2);
            assertEquals(1, root.get("layers").size());
            assertEquals(0, root.get("layers").get(0).get("elements").size());
            assertNotNull(root.get("activeLayerId"));
        }
    }

    @Nested
    class BadRowIsolation {

        @TempDir
        Path tmpDir;

        private Database database;
        private WallRepo repo;

        @BeforeEach
        void setUp() {
            database = new Database(LOG, tmpDir.resolve("data.db"));
            new MigrationRunner(database.jdbi(), LOG).run();
            repo = new WallRepo(LOG, database.jdbi());
        }

        @AfterEach
        void tearDown() {
            if (database != null) database.close();
        }

        private String createGoodWall(String world, int x) {
            return repo.createWithMapIds(
                    new WallKey(world, x, 64, 0, BlockFace.NORTH),
                    new ProjectState(1, 1, "#FFFFFF"),
                    List.of(1, 2), 1, 1, UUID.randomUUID(), "tester");
        }

        private void corruptProjectJson(String wallId) {
            database.jdbi().useHandle(h -> h
                    .createUpdate("UPDATE walls SET project_json = :j WHERE wall_id = :id")
                    .bind("j", "{ this is not valid json")
                    .bind("id", wallId).execute());
        }

        private void corruptFacing(String wallId) {
            database.jdbi().useHandle(h -> h
                    .createUpdate("UPDATE walls SET facing = :f WHERE wall_id = :id")
                    .bind("f", "SIDEWAYS").bind("id", wallId).execute());
        }

        @Test
        void loadAll_badProjectJson_skipsOnlyThatRow() {
            String good1 = createGoodWall("world", 10);
            String bad = createGoodWall("world", 20);
            String good2 = createGoodWall("world", 30);
            corruptProjectJson(bad);

            List<WallRepo.Wall> walls = repo.loadAll();

            assertEquals(2, walls.size(), "只跳过坏行，好行必须照常返回");
            Set<String> ids = Set.of(walls.get(0).wallId(), walls.get(1).wallId());
            assertTrue(ids.contains(good1) && ids.contains(good2));
            assertFalse(ids.contains(bad));
        }

        @Test
        void loadAll_badFacing_skipsOnlyThatRow() {
            String good = createGoodWall("world", 10);
            String bad = createGoodWall("world", 20);
            corruptFacing(bad);

            List<WallRepo.Wall> walls = repo.loadAll();

            assertEquals(1, walls.size());
            assertEquals(good, walls.get(0).wallId());
        }

        @Test
        void loadAllWallIds_unaffectedByCorruptRows() {
            String good = createGoodWall("world", 10);
            String badJson = createGoodWall("world", 20);
            String badFacing = createGoodWall("world", 30);
            corruptProjectJson(badJson);
            corruptFacing(badFacing);

            Optional<Set<String>> ids = repo.loadAllWallIds();

            assertTrue(ids.isPresent(), "能读到就必须返回 present");
            assertEquals(Set.of(good, badJson, badFacing), ids.get(),
                    "坏行的 wall_id 也必须在 live 集合里——否则它的地图会被当泄漏释放");
        }

        @Test
        void loadAllWallIds_emptyTable_returnsPresentEmptySet() {
            Optional<Set<String>> ids = repo.loadAllWallIds();
            assertTrue(ids.isPresent(), "表是空的 ≠ 读失败；必须能区分");
            assertTrue(ids.get().isEmpty());
        }

        @Test
        void loadAllWallIds_readFailure_returnsEmptyOptionalNotEmptySet() {
            createGoodWall("world", 10);
            database.close();

            Optional<Set<String>> ids = repo.loadAllWallIds();

            assertTrue(ids.isEmpty(),
                    "读失败必须是 Optional.empty()；返回空集合会让 detectLeaks 释放全服在用地图");
            database = null;
        }
    }

    @Nested
    class DeleteResult {

        @TempDir
        Path tmpDir;

        private Database database;
        private WallRepo repo;

        @BeforeEach
        void setUp() {
            database = new Database(LOG, tmpDir.resolve("data.db"));
            new MigrationRunner(database.jdbi(), LOG).run();
            repo = new WallRepo(LOG, database.jdbi());
        }

        @AfterEach
        void tearDown() {
            if (database != null) database.close();
        }

        private String createWall() {
            return repo.createWithMapIds(
                    new WallKey("world_a", 0, 64, 0, BlockFace.NORTH),
                    new ProjectState(1, 1, "#FFFFFF"),
                    List.of(1, 2), 1, 1, UUID.randomUUID(), "tester");
        }

        @Test
        void delete_existingWall_reportsSuccessAndRemovesRow() {
            String wallId = createWall();
            assertTrue(repo.loadById(wallId).isPresent(), "前提：行确实建出来了");

            assertTrue(repo.delete(wallId), "删掉了就要报 true");
            assertTrue(repo.loadById(wallId).isEmpty(), "行确实没了");
        }

        @Test
        void delete_missingWall_reportsFailure() {
            assertFalse(repo.delete("w-nonexistent"),
                    "本来就没有这一行 → 不能报成功");
        }

        @Test
        void delete_afterClose_reportsFailureInsteadOfSwallowing() {
            String wallId = createWall();
            database.close();

            assertFalse(repo.delete(wallId), "写失败必须报 false");
            database = null;
        }
    }

    @Nested
    class ProtocolVersionPersistence {

        @TempDir
        Path tmpDir;

        private Database database;
        private WallRepo repo;

        @BeforeEach
        void setUp() {
            database = new Database(LOG, tmpDir.resolve("data.db"));
            new MigrationRunner(database.jdbi(), LOG).run();
            repo = new WallRepo(LOG, database.jdbi());
        }

        @AfterEach
        void tearDown() {
            if (database != null) database.close();
        }

        private int queryProtocolVersion(String wallId) {
            return database.jdbi().withHandle(h -> h.createQuery(
                            "SELECT protocol_version FROM walls WHERE wall_id = :id")
                    .bind("id", wallId)
                    .mapTo(Integer.class)
                    .one());
        }

        @Test
        void createWithMapIds_storesCurrentProtocolVersionAndSkipsStartupMigration() {
            ProjectState state = new ProjectState(1, 1, "#FFFFFF");
            // 推进编辑计数，防止误绑成 state.version()
            state.bumpVersion();
            state.bumpVersion();
            assertEquals(2L, state.version());
            assertEquals(3, ProjectState.PROTOCOL_VERSION);

            String wallId = repo.createWithMapIds(
                    new WallKey("world", 0, 64, 0, BlockFace.NORTH),
                    state, List.of(1), 1, 1, UUID.randomUUID(), "tester");

            assertEquals(ProjectState.PROTOCOL_VERSION, queryProtocolVersion(wallId),
                    "新建墙必须写入 ProjectState.protocolVersion() (3)，而非 DEFAULT 1 或编辑计数 version()");

            WallRepo.MigrationStats stats = repo.migrateAllToV2();
            assertEquals(0, stats.scanned(), "新建墙已是最新协议版本，启动扫描不应命中");
            assertEquals(0, stats.migrated());
        }

        @Test
        void v2Row_isNotTouchedByMigrateAllToV2_andUpgradesLazilyOnUpdateState() {
            String wallId = repo.createWithMapIds(
                    new WallKey("world", 10, 64, 0, BlockFace.NORTH),
                    new ProjectState(1, 1, "#FFFFFF"),
                    List.of(2), 1, 1, UUID.randomUUID(), "tester");

            long sentinelTs = 1700000000000L;
            database.jdbi().useHandle(h -> h.createUpdate(
                            "UPDATE walls SET protocol_version = 2, updated_at = :ts WHERE wall_id = :id")
                    .bind("ts", sentinelTs)
                    .bind("id", wallId)
                    .execute());

            WallRepo.MigrationStats stats = repo.migrateAllToV2();
            assertEquals(0, stats.scanned(), "protocol_version == 2 的旧行不应被启动迁移扫描重写");
            assertEquals(0, stats.migrated());
            assertEquals(2, queryProtocolVersion(wallId));
            long tsAfterMigrate = database.jdbi().withHandle(h -> h.createQuery(
                            "SELECT updated_at FROM walls WHERE wall_id = :id")
                    .bind("id", wallId)
                    .mapTo(Long.class)
                    .one());
            assertEquals(sentinelTs, tsAfterMigrate, "migrateAllToV2 不应触碰 v2 行");

            ProjectState nextState = new ProjectState(1, 1, "#000000");
            for (int i = 0; i < 5; i++) nextState.bumpVersion();
            repo.updateState(wallId, nextState);

            assertEquals(ProjectState.PROTOCOL_VERSION, queryProtocolVersion(wallId),
                    "updateState 写回时应惰性把 protocol_version 升至 ProjectState.PROTOCOL_VERSION (3)");
        }

        @Test
        void v1Row_migratesDirectlyToCurrentProtocolVersion() {
            String wallId = repo.createWithMapIds(
                    new WallKey("world", 20, 64, 0, BlockFace.NORTH),
                    new ProjectState(1, 1, "#FFFFFF"),
                    List.of(3), 1, 1, UUID.randomUUID(), "tester");
            String v1Json = """
                    {
                      "version": 9,
                      "canvas": {"widthMaps": 1, "heightMaps": 1, "background": "#FFFFFF"},
                      "elements": [],
                      "history": {"undoDepth": 0, "redoDepth": 0}
                    }
                    """;
            database.jdbi().useHandle(h -> h.createUpdate(
                            "UPDATE walls SET project_json = :j, protocol_version = 1 WHERE wall_id = :id")
                    .bind("j", v1Json)
                    .bind("id", wallId)
                    .execute());

            WallRepo.MigrationStats stats = repo.migrateAllToV2();
            assertEquals(1, stats.scanned());
            assertEquals(1, stats.migrated());
            assertEquals(0, stats.failed());
            assertEquals(ProjectState.PROTOCOL_VERSION, queryProtocolVersion(wallId));
        }
    }
}
