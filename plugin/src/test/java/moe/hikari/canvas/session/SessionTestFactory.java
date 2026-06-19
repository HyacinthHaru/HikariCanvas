package moe.hikari.canvas.session;

import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;

import java.util.UUID;

/**
 * 0.7.0-P1-7b #3：测试用 {@link Session} 工厂。
 *
 * <p>{@code Session} 的构造器与 mutator 都是 package-private（只允许
 * {@link SessionManager} 持锁修改），dispatcher 行为级测试（如
 * {@code moe.hikari.canvas.web.ScriptOpDispatchBehaviorTest}）需要一个已绑 wall
 * 的 session 实例但不想拖入 SessionManager 全装配链——本工厂放在同 package 的
 * test sourceset 里桥一下，不放宽生产可见性。</p>
 */
public final class SessionTestFactory {

    private SessionTestFactory() {}

    /** 造一个已绑定 {@code wallId} 的 session（其余字段保持初始态）。 */
    public static Session withWall(String sessionId, UUID playerUuid,
                                   String playerName, String wallId) {
        Session s = new Session(sessionId, playerUuid, playerName, System.currentTimeMillis());
        s.wallId(wallId);
        return s;
    }

    /**
     * 0.8-A2：造一个已绑 {@code wallId} 且持有活 {@link EditSession} 的 session。
     *
     * <p>{@code projectState} 与 {@code editSession.state()} 共享同一对象引用——与
     * {@code SessionManager} confirm/open 路径的真实装配一致（导入编排照
     * {@code EditOpDispatcher} 范式从 {@code session.projectState()} 读快照推送 +
     * {@code wallRepo.updateState} 持久化，二者必须指向同一对象）。</p>
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
