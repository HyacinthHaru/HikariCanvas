package ac.haru.hikaricanvas.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code default=true} 权限节点兜底规则的守卫。
 *
 * <p>Variable / VariableAlias / Schedule / Rail 四个 dispatcher 此前各写一份
 * {@code if (!granted && isDefaultTrue) granted = true;}——<b>无条件</b>兜底，不区分
 * 「玩家离线 / 权限解析超时」与「在线且被服主显式收回」。后果：服主用 LuckPerms 负权限
 * 收回 {@code canvas.var.write.own} / {@code canvas.schedule.own} /
 * {@code canvas.rail.line.create} 等节点全部形同虚设，且服主无从察觉（fail-open）。
 * {@code ScriptOpDispatcher} 早已修成正确写法，那 4 处没跟进。</p>
 *
 * <p>0.9.17 把判定收敛到
 * {@link MainThreadPerms#grantedWithDefaultTrueFallback}，4 处共用一份 —— 本测试即那份
 * 规则的唯一守卫，同时防止再次复制粘贴分叉。</p>
 */
class DefaultTruePermissionFallbackTest {

    private static MainThreadPerms.Resolved resolved(boolean online, boolean granted) {
        return new MainThreadPerms.Resolved(online, new boolean[]{granted});
    }

    @Test
    void onlineWithNode_granted() {
        assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, true), true));
        assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, true), false));
    }

    /** 本批修复的核心：在线 + 服主显式收回 default-true 节点 → 必须真拒。 */
    @Test
    void onlineButRevoked_deniedEvenForDefaultTrueNode() {
        assertFalse(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, false), true),
                "在线且被显式收回 default=true 节点必须拒——否则 LuckPerms 负权限形同虚设");
    }

    @Test
    void onlineButRevoked_deniedForNonDefaultTrueNode() {
        assertFalse(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, false), false));
    }

    /** 离线 / 解析超时（online=false）仍按 default=true 放行——这是兜底的<b>唯一</b>正当用途。 */
    @Test
    void offline_defaultTrueNodeStillAllowed() {
        assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(false, false), true),
                "离线 / 解析超时 → default=true 节点放行（保留原有兜底语义）");
    }

    /** 离线 + 非 default-true（.any 提权节点）→ fail-closed。 */
    @Test
    void offline_nonDefaultTrueNodeDenied() {
        assertFalse(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(false, false), false),
                "提权节点宁可误拒不可误放");
    }
}
