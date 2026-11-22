package ac.haru.hikaricanvas.session;

import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.ProjectState;

import java.util.UUID;

/**
 * 测试用 {@link Session} 工厂。
 *
 * <p>{@code Session} 的构造器与 mutator 均为 package-private（仅允许
 * {@link SessionManager} 持锁修改）；本工厂放在同 package 的 test sourceset 中构造预绑
 * wall 的会话实例，无需放宽生产可见性。</p>
 */
public final class SessionTestFactory {

    private SessionTestFactory() {}

    /**
     * 造一个控制台主体的 session（UUID 固定为 {@link Session#CONSOLE_UUID} 索引键，授权依据为 principal）。
     */
    public static Session console(String sessionId, String wallId) {
        return withPrincipal(sessionId, Principal.CONSOLE, Session.CONSOLE_UUID, "CONSOLE", wallId);
    }

    /**
     * 造一个指定主体 + 指定 UUID 的 session（用于验证 {@code principal=PLAYER} 且 UUID 为 nil 时不赋予控制台特权）。
     */
    public static Session withPrincipal(String sessionId, Principal principal, UUID uuid,
                                        String name, String wallId) {
        Session s = new Session(sessionId, uuid, name, System.currentTimeMillis(), principal);
        s.wallId(wallId);
        return s;
    }

    /** 造一个已绑定 {@code wallId} 的 session（其余字段保持初始态）。 */
    public static Session withWall(String sessionId, UUID playerUuid,
                                   String playerName, String wallId) {
        Session s = new Session(sessionId, playerUuid, playerName, System.currentTimeMillis());
        s.wallId(wallId);
        return s;
    }

    /**
     * 造一个已绑 {@code wallId} 且持有活 {@link EditSession} 的 session
     * （{@code projectState} 与 {@code editSession.state()} 共享同一对象引用）。
     */
    public static Session withWallAndProject(String sessionId, UUID playerUuid,
                                             String playerName, String wallId,
                                             ProjectState initialState) {
        Session s = new Session(sessionId, playerUuid, playerName, System.currentTimeMillis());
        s.wallId(wallId);
        s.projectState(initialState);
        s.editSession(new EditSession(initialState));
        return s;
    }
}
