-- drafts 表加上关联的 map_ids 和画布尺寸，让服务器重启后能把草稿像素
-- 重新 compose 到已部署 ItemFrame 挂着的 MapView；再次 /canvas edit 同墙面时
-- 复用这些 map 而不是从池重新分配。

ALTER TABLE drafts ADD COLUMN map_ids TEXT;
ALTER TABLE drafts ADD COLUMN width_maps INTEGER;
ALTER TABLE drafts ADD COLUMN height_maps INTEGER;
