package moe.hikari.canvas.session;

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
}
