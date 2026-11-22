package ac.haru.hikaricanvas.deploy;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * 所有 {@code ClientboundMapItemDataPacket} 级别的发包必须走这个类。
 *
 * <p>这是 {@code docs/architecture.md §1.3} 规定的架构纪律之一：
 * 把 PacketEvents API 调用集中在单一 sender（当前锁定 PacketEvents 2.13.0，支持 1.21.x + 26.x），
 * 让未来 wrapper 签名变动只需修改这一个文件。</p>
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
        Objects.requireNonNull(player, "player must not be null");
        if (pixels128x128 == null || pixels128x128.length != MAP_PIXELS) {
            throw new IllegalArgumentException(
                    "expected " + MAP_PIXELS + " pixels, got "
                            + (pixels128x128 == null ? "null" : pixels128x128.length));
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
