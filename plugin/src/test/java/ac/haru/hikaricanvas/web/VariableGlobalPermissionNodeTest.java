package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 全局用户变量（{@code userglobal/*}）各 op 的权限节点选择守卫。
 *
 * <p>重点是 {@code variable.bind}：绑定 = 把变量交给插件 push 接管，是敏感操作，
 * 必须查 {@code canvas.var.bind}（default op）。早先它跟 update / set 一起走
 * {@code canvas.var.global.write.own}——那个节点 default=true 且还有 own 兜底，
 * 等于"任何玩家都能把自己建的全服变量绑到任意插件 namespace"，与
 * {@code dynamic-data.md §9.1} / {@code security.md} 把 bind 定为敏感 op 直接冲突。</p>
 */
class VariableGlobalPermissionNodeTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final String FULL_NAME = "userglobal/server_notice";

    private VariableOpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        VariableStore store = new VariableStore(new NoopUserVariableDao(), w -> { });
        store.configureUserGlobal(new NoopGlobalDao(), 100, 1000);
        store.createGlobal(OWNER, "Owner", "server_notice", VarType.STRING, null);
        dispatcher = new VariableOpDispatcher(null, null, store, null, null, null, null);
    }

    private String nodeFor(String op, UUID caller) {
        return dispatcher.pickGlobalPermissionNode(op, caller, Map.of("fullName", FULL_NAME));
    }

    @Test
    void bindRequiresSensitiveBindNode_evenForOwner() {
        assertEquals("canvas.var.bind", nodeFor("variable.bind", OWNER));
        assertEquals("canvas.var.bind", nodeFor("variable.bind", OTHER));
    }

    /** bind 节点不在 default-true 兜底名单里 → 离线 / 解析超时也不放行。 */
    @Test
    void bindNodeHasNoDefaultTrueFallback() {
        assertFalse(VariableOpDispatcher.isOwnNodeDefaultTrue("canvas.var.bind"));
    }

    /** 其余 op 的 own/any 分流不受影响（回归）。 */
    @Test
    void writeAndDeleteStillSplitByOwnership() {
        assertEquals("canvas.var.global.write.own", nodeFor("variable.set", OWNER));
        assertEquals("canvas.var.global.write.any", nodeFor("variable.set", OTHER));
        assertEquals("canvas.var.global.write.own", nodeFor("variable.update", OWNER));
        assertEquals("canvas.var.global.delete.own", nodeFor("variable.delete", OWNER));
        assertEquals("canvas.var.global.delete.any", nodeFor("variable.delete", OTHER));
        assertEquals("canvas.var.global.create",
                dispatcher.pickGlobalPermissionNode("variable.create", OWNER, Map.of()));
    }

    /** 测试用空实现（本测试不碰 per-wall user 变量持久化）。 */
    private static final class NoopUserVariableDao
            extends ac.haru.hikaricanvas.storage.UserVariableDao {
        NoopUserVariableDao() {
            super(java.util.logging.Logger.getLogger("VariableGlobalPermissionNodeTest"), null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
        }

        @Override
        public void delete(String wallId, String name) {
        }
    }

    /** 全局变量 DAO 空实现（只需让 createGlobal 的配额查询与落库不炸）。 */
    private static final class NoopGlobalDao
            extends ac.haru.hikaricanvas.storage.UserGlobalVariableDao {
        NoopGlobalDao() {
            super(java.util.logging.Logger.getLogger("VariableGlobalPermissionNodeTest"), null);
        }

        @Override
        public void upsert(String name, UUID ownerUuid, String ownerName, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
        }

        @Override
        public void delete(String name) {
        }

        @Override
        public int countByOwner(UUID ownerUuid) {
            return 0;
        }

        @Override
        public int countTotal() {
            return 0;
        }
    }
}
