package moe.hikari.canvas.deploy;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import org.bukkit.entity.Player;

/**
 * 所有 {@code ClientboundMapItemDataPacket} 级别的发包必须走这个类。
 *
 * <p>这是 CLAUDE.md / PROPOSAL §5.2.6 规定的架构纪律之一：
 * 把 PacketEvents API 调用集中在单一 sender，
 * 让将来 PacketEvents 2.12.x（for Paper 26.x）的 wrapper 签名变动
 * 只需要修改这一个文件。</p>
 */
public final class MapPacketSender {

    /** 一张完整 Minecraft 地图的像素数（128 × 128）。 */
    public static final int MAP_PIXELS = 128 * 128;

    /**
     * 整张地图全量推送给单个玩家。
     *
     * @param player        目标玩家
     * @param mapId         地图 ID（对应 {@code filled_map} 物品的 map id）
     * @param pixels128x128 128×128 = 16384 字节，每字节是 MC map palette 索引
     */
    public void sendFullMap(Player player, int mapId, byte[] pixels128x128) {
        if (pixels128x128.length != MAP_PIXELS) {
            throw new IllegalArgumentException(
                    "expected " + MAP_PIXELS + " pixels, got " + pixels128x128.length);
        }
        WrapperPlayServerMapData packet = new WrapperPlayServerMapData(
                mapId,
                (byte) 0,   // scale 0 = 最精细
                false,      // trackingPosition
                false,      // locked
                null,       // decorations / icons
                128,        // columns
                128,        // rows
                0,          // x offset
                0,          // z offset
                pixels128x128
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }
}
