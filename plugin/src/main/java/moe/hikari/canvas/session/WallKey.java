package moe.hikari.canvas.session;

import org.bukkit.block.BlockFace;

/**
 * 墙面排他锁 key。按 {@code docs/architecture.md §3.2}：
 * 同一墙面同一时刻最多一个活跃会话，key = (world, origin, facing)。
 *
 * <p>origin 固定取 bounding box 的 (minX, minY, minZ)，方向归一后
 * 同一面墙无论玩家先选哪个角都产生相同的 key。</p>
 */
public record WallKey(String world, int originX, int originY, int originZ, BlockFace facing) {}
