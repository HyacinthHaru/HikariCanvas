-- baseline = V016。种入一个 wall，验证 V017（建 wall_scripts 表）后旧数据无损。
-- 列名以 V005/V006 walls schema 为准；alias/published_at/template_id/template_version 可空略去。
-- protocol_version 由 V006 加，DEFAULT 1 可不显式提供。
INSERT INTO walls (wall_id, world, origin_x, origin_y, origin_z, facing,
                   width_maps, height_maps, map_ids, project_json,
                   owner_uuid, owner_name, created_at, updated_at)
VALUES ('w-fixture', 'world', 0, 0, 0, 'NORTH',
        2, 2, '1,2,3,4', '{}',
        '00000000-0000-0000-0000-000000000001', 'Tester', 100, 100);
