-- 移除未使用的 image_uploads.refcount 列。
-- LRU 淘汰通过 ImageStorage.collectReferencedHashes 实时扫描 walls.project_json 计算引用集合。
-- sqlite-jdbc（SQLite >= 3.35）原生支持 ALTER TABLE ... DROP COLUMN。

ALTER TABLE image_uploads DROP COLUMN refcount;
