package ac.haru.hikaricanvas.template;

import java.util.Optional;
import java.util.UUID;

/**
 * 注册表中的单条模板条目 = UI 用 {@link TemplateSpec}（合成）+ pack 原始字节 + 来源元信息。
 *
 * <p>{@code sourceLabel} 用于诊断日志（jar 内路径或 {@code templates/subway.canvas}）；模板 id
 * 冲突时方便服主追踪哪个文件被覆盖了。</p>
 *
 * <p><b>跨用户隔离：</b> {@code ownerUuid} 非空表示该条目来自 {@code user-templates/&lt;uuid&gt;/}
 * 目录，apply 时调用方必须是 owner 或持 {@code canvas.template.use-others} bypass 权限。builtin /
 * server 模板 {@code ownerUuid} = {@link Optional#empty()}，所有玩家可用。</p>
 *
 * <p><b>载体：</b> {@code packBytes} 是 {@code .canvas} pack（{@code manifest.kind="pack"}）原始字节；
 * {@code spec} 是仅供 Gallery / list / preview 统一按 {@link #spec()} 读的<b>合成 spec</b>
 * （id / name / spec / canvas / params），套用走 {@code ProjectImporter.applyPack} 拿这份字节现解。
 * {@code packBytes} 为 null 的退化条目（{@link #isPack()} 返 false）仅供防御判定，正常注册不产出。</p>
 *
 * <p><b>关于 {@code byte[]} 的 record 语义：</b> record 自动生成的
 * {@code equals/hashCode/toString} 对 {@code byte[]} 走引用（identity）语义而非
 * {@code Arrays.equals}。注册表条目从不做值比较（只按 id 存 Map + 遍历），故可接受，
 * 不额外覆写。</p>
 */
public record TemplateEntry(
        TemplateSpec spec,
        TemplateSource source,
        String sourceLabel,
        Optional<UUID> ownerUuid,
        /** pack 原始字节（套用时喂 {@code ProjectImporter.applyPack}）；null = 退化空条目。 */
        byte[] packBytes
) {
    /** 退化条目（无 owner、无 pack 字节）；{@link #isPack()} 返 false。 */
    public TemplateEntry(TemplateSpec spec, TemplateSource source, String sourceLabel) {
        this(spec, source, sourceLabel, Optional.empty(), null);
    }

    /** 退化条目（带 owner、无 pack 字节）；{@link #isPack()} 返 false。 */
    public TemplateEntry(TemplateSpec spec, TemplateSource source, String sourceLabel,
                         Optional<UUID> ownerUuid) {
        this(spec, source, sourceLabel, ownerUuid, null);
    }

    /**
     * 构造一个 {@code .canvas} pack 条目：{@code syntheticSpec} 供 UI 统一读，{@code packBytes}
     * 为 pack 原始字节（套用时经 {@code ProjectImporter.applyPack} 现解）。
     */
    public static TemplateEntry pack(TemplateSpec syntheticSpec, TemplateSource source,
                                     String label, Optional<UUID> owner, byte[] packBytes) {
        return new TemplateEntry(syntheticSpec, source, label, owner, packBytes);
    }

    /** {@code true} = 该条目携带 pack 字节（套用走 {@code ProjectImporter.applyPack}）。 */
    public boolean isPack() {
        return packBytes != null;
    }
}
