-- 移除 image_uploads.refcount dead 列。
-- v1 起代码注释自承「不维护实时增减」，所有 insert 写 refcount=1 后再不 update；
-- LRU 候选实际靠 ImageStorage.collectReferencedHashes 实时 sweep walls.project_json 算。
-- 列空占空间 + 误导 dev，删除之。
--
-- xerial sqlite-jdbc 3.53 (build.gradle.kts) 对应 SQLite >= 3.45，原生支持
-- ALTER TABLE ... DROP COLUMN（SQLite 3.35+ 引入）；无需 12 步骤重建表。
--
-- 决策依据：pre-release SNAPSHOT 阶段激进改 schema 可接受；
-- 0.1.0 发版后 forward-only。当前仍 0.1.x，本次 destructive migration 合规。

ALTER TABLE image_uploads DROP COLUMN refcount;
