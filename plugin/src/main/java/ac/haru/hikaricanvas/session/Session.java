package ac.haru.hikaricanvas.session;

import ac.haru.hikaricanvas.deploy.WallResolver;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.ProjectState;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.UUID;

/**
 * 单个编辑会话。可变 POJO；写入路径由 {@link SessionManager} 在 writeLock 临界区内执行，
 * 但读取可能发生在其他线程（Jetty WS 线程 / daemon 节流线程）。为保证跨线程发布可见性，
 * 所有可变字段标记 {@code volatile}：volatile 不影响 lock-free 读性能，
 * 仅补上写后发布屏障，消除撕裂读 / 陈旧读。复合不变量仍依赖 SessionManager 的锁来维护。
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

    private volatile SessionState state;
    private volatile Block pos1;
    private volatile Block pos2;
    private volatile BlockFace face;            // pos1/pos2 共用同一 normal
    private volatile WallResolver.Result.Ok wall;
    private volatile List<Integer> mapIds;
    private volatile WallKey wallKey;
    private volatile String wallId;             // 当前 session 编辑的 wall（confirm 后赋值）
    private volatile ProjectState projectState;
    private volatile EditSession editSession;
    private volatile long lastActivityAt;
    private volatile long wsDisconnectedAt = -1;
    /**
     * 会话级 IP 绑定。首次 WS auth 成功时设值；后续 reconnect 必须 IP 同源。
     * null = 尚未首次 auth；非 null = 已绑定，认 IP 字符串严格相等。
     * 见 CLAUDE.md §lock-state 后的 IP 绑定决策。
     */
    private volatile String boundIp;
    /**
     * 编辑器 UI 语言（前端 auth 帧携带 ui.locale，映射成 game locale id 形态如
     * {@code zh_cn} / {@code en_us}）。用于按编辑器语言渲染脚本校验报错。
     * null = 未知，渲染时回退 Messages 默认 locale。
     */
    private volatile String editorLocale;

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
    public String wallId() { return wallId; }
    public ProjectState projectState() { return projectState; }
    public EditSession editSession() { return editSession; }
    public long lastActivityAt() { return lastActivityAt; }
    public long wsDisconnectedAt() { return wsDisconnectedAt; }
    /** 当前绑定的 client IP；null 表示尚未首次 auth。 */
    public String boundIp() { return boundIp; }
    /** 编辑器 UI 语言（game locale id 形态，如 {@code zh_cn}）；null = 未知。 */
    public String editorLocale() { return editorLocale; }
    /**
     * WS auth 时由 {@code WebServer} 依前端携带的 locale 设置（已经
     * {@code Messages.resolveLocaleId} 规范化 + 兜底）。public：跨 package 由 WebServer 直设，
     * 非编辑不变量的一部分（仅影响外发文案渲染），无需走 SessionManager 锁。
     */
    public void setEditorLocale(String localeId) { this.editorLocale = localeId; }

    // package-private mutators——只允许 SessionManager 在持锁下修改
    void state(SessionState s) { this.state = s; }
    void pos1(Block b, BlockFace f) { this.pos1 = b; this.face = f; }
    void pos2(Block b, BlockFace f) { this.pos2 = b; this.face = f; }
    /** 清空已选角，让玩家在 SELECTING 状态下重新开始（隐式 reselect 用）。 */
    void clearPos() { this.pos1 = null; this.pos2 = null; this.face = null; }
    void wall(WallResolver.Result.Ok w) { this.wall = w; }
    void mapIds(List<Integer> ids) { this.mapIds = ids; }
    void wallKey(WallKey k) { this.wallKey = k; }
    void wallId(String id) { this.wallId = id; }
    void projectState(ProjectState ps) { this.projectState = ps; }
    void editSession(EditSession es) { this.editSession = es; }
    void touchActivity(long now) { this.lastActivityAt = now; this.wsDisconnectedAt = -1; }
    void markWsDisconnected(long now) { this.wsDisconnectedAt = now; }
    /** 首次 WS auth 成功时调；后续 reconnect 必须 IP 同源。 */
    void boundIp(String ip) { this.boundIp = ip; }
}
