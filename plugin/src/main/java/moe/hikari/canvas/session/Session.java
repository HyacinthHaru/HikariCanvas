package moe.hikari.canvas.session;

import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.UUID;

/**
 * 单个编辑会话。可变 POJO；字段的可见性依赖 {@link SessionManager} 在
 * synchronized 段内访问。调用方不应在持锁外读写字段。
 *
 * <p>字段分阶段生效：</p>
 * <ul>
 *   <li>{@link SessionState#SELECTING}：仅 pos1 / pos2 / face 有意义</li>
 *   <li>{@link SessionState#ISSUED} 及之后：wall / mapIds / wallKey 有意义</li>
 *   <li>{@link SessionState#ACTIVE}：lastActivityAt 跟随 WS 消息更新；
 *       wsDisconnectedAt &gt; 0 表示在 5 分钟宽限窗口内等待重连</li>
 * </ul>
 */
public final class Session {

    private final String id;
    private final UUID playerUuid;
    private final String playerName;
    private final long createdAt;

    private SessionState state;
    private Block pos1;
    private Block pos2;
    private BlockFace face;            // pos1/pos2 共用同一 normal
    private WallResolver.Result.Ok wall;
    private List<Integer> mapIds;
    private WallKey wallKey;
    private ProjectState projectState;
    private EditSession editSession;
    private long lastActivityAt;
    private long wsDisconnectedAt = -1;

    Session(String id, UUID playerUuid, String playerName, long now) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.createdAt = now;
        this.state = SessionState.SELECTING;
        this.lastActivityAt = now;
    }

    public String id() { return id; }
    public UUID playerUuid() { return playerUuid; }
    public String playerName() { return playerName; }
    public long createdAt() { return createdAt; }

    public SessionState state() { return state; }
    public Block pos1() { return pos1; }
    public Block pos2() { return pos2; }
    public BlockFace face() { return face; }
    public WallResolver.Result.Ok wall() { return wall; }
    public List<Integer> mapIds() { return mapIds; }
    public WallKey wallKey() { return wallKey; }
    public ProjectState projectState() { return projectState; }
    public EditSession editSession() { return editSession; }
    public long lastActivityAt() { return lastActivityAt; }
    public long wsDisconnectedAt() { return wsDisconnectedAt; }

    // package-private mutators——只允许 SessionManager 在持锁下修改
    void state(SessionState s) { this.state = s; }
    void pos1(Block b, BlockFace f) { this.pos1 = b; this.face = f; }
    void pos2(Block b, BlockFace f) { this.pos2 = b; this.face = f; }
    void wall(WallResolver.Result.Ok w) { this.wall = w; }
    void mapIds(List<Integer> ids) { this.mapIds = ids; }
    void wallKey(WallKey k) { this.wallKey = k; }
    void projectState(ProjectState ps) { this.projectState = ps; }
    void editSession(EditSession es) { this.editSession = es; }
    void touchActivity(long now) { this.lastActivityAt = now; this.wsDisconnectedAt = -1; }
    void markWsDisconnected(long now) { this.wsDisconnectedAt = now; }
}
