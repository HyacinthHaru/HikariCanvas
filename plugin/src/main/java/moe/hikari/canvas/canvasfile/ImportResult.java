package moe.hikari.canvas.canvasfile;

import java.util.List;

/**
 * 0.8-A2：一次成功导入的结果。
 *
 * <p>导入成功（未抛 {@link CanvasImportException}）即返回本结果；{@code warnings} 罗列所有被降级
 * 处理的内容（缺字体 / 孤儿动画轨 / 图片配额满等），由端点序列化为
 * {@code {"ok":true,"warnings":[...]}} 下发前端。空列表表示完全无损导入。</p>
 *
 * @param warnings 非致命提示列表（见 {@link ImportWarning}）；永不为 null
 */
public record ImportResult(List<ImportWarning> warnings) {}
