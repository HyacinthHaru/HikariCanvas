package moe.hikari.canvas.variable;

/**
 * 变量别名记录（0.4.2）。详见 {@code docs/variables.md §1.12}。
 *
 * <p>每个 wall 给某个 fullName 可起一个别名，UI 层展示时优先用别名代替原始 fullName。
 * 主键 {@code (wallId, fullName)}：同一 wall 内一个变量只能有一个别名。</p>
 *
 * @param wallId    所属 wall id
 * @param fullName  目标变量完整名（如 {@code user:w-abc/red} / {@code system/server.time}）
 * @param alias     用户起的短名（≤ 64 字符；UI 显示用，不参与 ${var:} 解析）
 * @param createdAt epoch ms
 * @param updatedAt epoch ms
 */
public record VariableAlias(
        String wallId,
        String fullName,
        String alias,
        long createdAt,
        long updatedAt
) {}
