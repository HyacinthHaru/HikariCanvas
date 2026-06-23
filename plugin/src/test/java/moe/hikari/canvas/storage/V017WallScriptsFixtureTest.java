package moe.hikari.canvas.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.9.1：示范 fixture 测试——证明迁移 fixture 基建可用（V017 建 wall_scripts 表，旧 wall 无损）。 */
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
