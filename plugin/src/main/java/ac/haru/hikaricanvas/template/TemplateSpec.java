package ac.haru.hikaricanvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 模板在 UI（Gallery / list / preview）侧的元数据 spec。纯数据 record——加载来源 / 跨用户隔离状态由
 * {@link TemplateEntry} 包装持有。
 *
 * <p>{@code .canvas} pack 注册时由 {@code TemplateRegistry.buildPackSpec} 从 manifest + params.json
 * 合成：填 {@code spec / id / name / canvas（fixed 尺寸）/ params}，其余可空。ready / list 帧下发它供
 * 前端渲染卡片 + 参数表单；套用走 {@code ProjectImporter.applyPack} 现解 pack 字节，不读本 spec。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateSpec(
        int spec,
        String id,
        String name,
        String description,
        Integer version,
        String author,
        List<String> tags,
        String preview,
        TemplateCanvas canvas,
        Map<String, TemplateParam> params
) {
}
