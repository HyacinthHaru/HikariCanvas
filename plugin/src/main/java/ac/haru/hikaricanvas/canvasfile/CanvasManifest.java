package ac.haru.hikaricanvas.canvasfile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * .canvas manifest.json（docs/import-export.md §2.2）。只读所需字段，宽松忽略未知。
 *
 * <p>{@code id} 仅 pack 用（模板自声明 id，供注册表当条目 key，与 DB {@code templates.template_id}
 * 对齐）；缺省时注册表退回文件名 stem。project 工程不含此字段（null）。</p>
 */
public record CanvasManifest(int spec, String kind, long createdAt, String name,
                             int wallWidth, int wallHeight, String id) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static CanvasManifest parse(byte[] json, int maxSpec) throws CanvasImportException {
        JsonNode n;
        try {
            n = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new CanvasImportException("IMPORT_MALFORMED", "manifest 解析失败: " + e.getMessage());
        }
        if (n == null || !n.isObject()) throw new CanvasImportException("IMPORT_MALFORMED", "manifest 非对象");
        int spec = n.path("spec").asInt(0);
        if (spec <= 0) throw new CanvasImportException("IMPORT_MALFORMED", "manifest.spec 缺失/非法");
        if (spec > maxSpec) {
            throw new CanvasImportException("IMPORT_SPEC_UNSUPPORTED",
                "工程格式版本 " + spec + " 高于当前插件支持的 " + maxSpec + "，请升级插件");
        }
        // kind 判别符：project（普通工程）/ pack（模板包，docs/template-pack.md D1）。
        // 两者同为 .canvas、走同一导入管线，pack 只多一层 params.json + ${param} 前置替换。
        String kind = n.path("kind").asText("");
        if (!"project".equals(kind) && !"pack".equals(kind)) {
            throw new CanvasImportException("IMPORT_MALFORMED", "kind 非 project/pack: " + kind);
        }
        JsonNode wall = n.path("wall");
        int w = wall.path("width").asInt(0), h = wall.path("height").asInt(0);
        if (w <= 0 || h <= 0) throw new CanvasImportException("IMPORT_MALFORMED", "manifest.wall 尺寸非法");
        return new CanvasManifest(spec, kind, n.path("created_at").asLong(0),
            n.path("name").asText(null), w, h, n.path("id").asText(null));
    }
}
