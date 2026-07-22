package ac.haru.hikaricanvas.template;

import java.util.Optional;
import java.util.UUID;

/**
 * 注册表中的单条模板条目 = 纯数据 {@link TemplateSpec} + 来源元信息。
 *
 * <p>{@code sourceLabel} 用于诊断日志（"templates/subway.yml" 或 jar 内路径）；
 * 模板 id 冲突时方便服主追踪哪个文件被覆盖了。</p>
 *
 * <p><b>跨用户隔离：</b> {@code ownerUuid} 非空表示该条目来自
 * {@code user-templates/&lt;uuid&gt;/} 目录，apply 时调用方必须是 owner 或持
 * {@code canvas.template.use-others} bypass 权限。builtin / server 模板
 * {@code ownerUuid} = {@link Optional#empty()}，所有玩家可用。</p>
 *
 * <p><b>两种来源载体（YAML / .canvas pack）：</b> {@code packBytes} 为 null 时是传统
 * YAML 模板（{@code spec} 由 {@code TemplateLoader} 解析而来，apply 走
 * {@code TemplateInstantiator}）；非空时是 {@code .canvas} pack（{@code manifest.kind="pack"}），
 * {@code spec} 是仅供 Gallery / list / preview 统一按 {@link #spec()} 读的<b>合成 spec</b>
 * （只填 id / name / spec / params，其余 null），apply 走
 * {@code ProjectImporter.applyPack(...)} 拿这份原始字节现解。两条路径的可见性 / 隔离判定
 * （{@link #source}/{@link #ownerUuid}）完全一致。</p>
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
        /** null = YAML 模板；非空 = .canvas pack 的原始字节（apply 时喂 ProjectImporter.applyPack）。 */
        byte[] packBytes
) {
    /** 兼容老调用方（无 ownerUuid 字段，默认 empty = 全局可用；YAML → packBytes=null）。 */
    public TemplateEntry(TemplateSpec spec, TemplateSource source, String sourceLabel) {
        this(spec, source, sourceLabel, Optional.empty(), null);
    }

    /** 兼容老调用方（带 ownerUuid，YAML → packBytes=null）。 */
    public TemplateEntry(TemplateSpec spec, TemplateSource source, String sourceLabel,
                         Optional<UUID> ownerUuid) {
        this(spec, source, sourceLabel, ownerUuid, null);
    }

    /**
     * 构造一个 {@code .canvas} pack 条目：{@code syntheticSpec} 供 UI 统一读，{@code packBytes}
     * 为 pack 原始字节（apply 时经 {@code ProjectImporter.applyPack} 现解）。
     */
    public static TemplateEntry pack(TemplateSpec syntheticSpec, TemplateSource source,
                                     String label, Optional<UUID> owner, byte[] packBytes) {
        return new TemplateEntry(syntheticSpec, source, label, owner, packBytes);
    }

    /** {@code true} = 该条目是 .canvas pack（apply 走 ProjectImporter.applyPack，非 TemplateInstantiator）。 */
    public boolean isPack() {
        return packBytes != null;
    }
}
