package moe.hikari.canvas.canvasfile;

/**
 * 0.8-A2：导入过程中产生的<b>非致命提示</b>（导入照常完成，仅告知用户某些内容被降级处理）。
 *
 * <p>{@code kind} 取值（前端 {@code ImportProjectModal} 翻成大白话，见 {@code docs/import-export.md §4}）：
 * {@code missing-font} / {@code missing-variable} / {@code missing-icon} /
 * {@code script-command-blocked} / {@code script-invalid} / {@code script-quota} /
 * {@code animation-flattened} / {@code orphan-track-dropped} / {@code asset-quota}。</p>
 *
 * @param kind   稳定的提示类别码（前端据此选大白话文案）
 * @param detail 该类别的具体对象（如缺失字体名、被丢弃的 elementId）；无对象时为空串
 */
public record ImportWarning(String kind, String detail) {}
