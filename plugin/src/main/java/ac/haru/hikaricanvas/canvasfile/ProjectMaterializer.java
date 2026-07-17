package ac.haru.hikaricanvas.canvasfile;

import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.state.ElementValidator;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;

/** project.json（untrusted）→ 校验过的 ProjectState。 */
public final class ProjectMaterializer {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private ProjectMaterializer() {}

    public static ProjectState materialize(byte[] projectJson, int sessionWallW, int sessionWallH)
            throws CanvasImportException {
        ProjectState state;
        try {
            // @JsonCreator 处理 v1/v2/v3 迁移；Canvas record 对 widthMaps/heightMaps 做 [1,32] 硬校验
            // （越界直接抛 IllegalArgumentException → 下方 catch 归类 IMPORT_MALFORMED）。
            state = MAPPER.readValue(projectJson, ProjectState.class);
        } catch (Exception e) {
            throw new CanvasImportException("IMPORT_MALFORMED", "project.json 解析失败: " + e.getMessage());
        }
        if (state.canvas().widthMaps() > sessionWallW || state.canvas().heightMaps() > sessionWallH) {
            throw new CanvasImportException("IMPORT_SIZE_MISMATCH",
                "工程尺寸 " + state.canvas().widthMaps() + "x" + state.canvas().heightMaps()
                    + " 超过当前墙 " + sessionWallW + "x" + sessionWallH + "，请开匹配尺寸的新会话");
        }
        try {
            for (Layer layer : state.layers()) {
                for (var el : layer.elements()) {
                    ElementValidator.validateElementForTemplateApply(el);  // 不信任任何元素数值
                }
            }
        } catch (RuntimeException e) {
            // ValidationException(extends RuntimeException) 与任何其它运行期异常一律归 MALFORMED
            throw new CanvasImportException("IMPORT_MALFORMED", "元素校验失败: " + e.getMessage());
        }
        return state;
    }
}
