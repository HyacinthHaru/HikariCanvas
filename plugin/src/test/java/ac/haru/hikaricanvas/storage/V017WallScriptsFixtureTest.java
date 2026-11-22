package ac.haru.hikaricanvas.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** V017 迁移 fixture 单测：新建 {@code wall_scripts} 表且保留已有 {@code walls} 种子数据。 */
class V017WallScriptsFixtureTest extends MigrationFixtureTestBase {

    @Override protected int targetVersion() { return 17; }
    @Override protected String fixtureName() { return "V017__wall_scripts"; }

    @Test
    void wallScriptsTableCreated_andSeedDataPreserved() {
        int tbl = jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='wall_scripts'")
                .mapTo(Integer.class).one());
        assertEquals(1, tbl, "V017 应建 wall_scripts 表");

        String world = jdbi.withHandle(h -> h.createQuery(
                        "SELECT world FROM walls WHERE wall_id='w-fixture'")
                .mapTo(String.class).one());
        assertEquals("world", world, "迁移后 baseline 种子数据应无损");
    }
}
