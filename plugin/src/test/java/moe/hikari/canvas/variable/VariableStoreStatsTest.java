package moe.hikari.canvas.variable;

import moe.hikari.canvas.storage.UserVariableDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.2 可观测性：{@link VariableStore#statsByNamespace()} 单测。
 *
 * <p>建若干不同 namespace 的变量（per-wall 形 {@code user:<wallId>} / {@code schedule:<wallId>}
 * 与全局形 {@code system} / {@code scoreboard} / {@code papi} / {@code userglobal} / 插件 namespace），
 * 验证按前缀归类计数正确、各计数之和等于 {@link VariableStore#size()}。</p>
 *
 * <p>用极简 {@link FakeDao}（不连真 SQLite，upsert/delete 仅吞），{@code globalDao} 未 configure
 * → {@code userglobal} 走 {@code persistIfUserGlobal} 的 null-guard 早返，无需 DAO。</p>
 */
class VariableStoreStatsTest {

    private VariableStore store;

    @BeforeEach
    void setUp() {
        store = new VariableStore(new FakeDao(), w -> {});
    }

    @Test
    void emptyStore_returnsEmptyMap() {
        assertTrue(store.statsByNamespace().isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    void countsByNamespacePrefix_andSumEqualsSize() {
        // per-wall 形：取冒号前前缀
        store.create("user:w-1", "score_a", VarType.NUMBER, "0", null);
        store.create("user:w-1", "score_b", VarType.NUMBER, "0", null);
        store.create("user:w-2", "score_c", VarType.NUMBER, "0", null);     // user 共 3
        store.create("schedule:w-1", "eta", VarType.NUMBER, "0", "system"); // schedule 1
        // 全局形：原样作 key
        store.create("system", "server.online", VarType.NUMBER, "0", "system");
        store.create("system", "server.tps", VarType.NUMBER, "0", "system"); // system 2
        store.create("scoreboard", "kills", VarType.NUMBER, "0", "scoreboard"); // scoreboard 1
        store.create("papi", "pct_foo_pct", VarType.STRING, null, "papi");   // papi 1
        store.create("userglobal", "global_msg", VarType.STRING, "hi", null); // userglobal 1
        store.create("bedwars", "red_score", VarType.NUMBER, "0", "BedWars"); // plugin 1

        Map<String, Integer> stats = store.statsByNamespace();

        assertEquals(3, stats.get("user").intValue());
        assertEquals(1, stats.get("schedule").intValue());
        assertEquals(2, stats.get("system").intValue());
        assertEquals(1, stats.get("scoreboard").intValue());
        assertEquals(1, stats.get("papi").intValue());
        assertEquals(1, stats.get("userglobal").intValue());
        assertEquals(1, stats.get("bedwars").intValue());

        int sum = stats.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(store.size(), sum, "各 namespace 计数之和应等于 store.size()");
        assertEquals(10, store.size());
        assertEquals(7, stats.size(), "应有 7 个 namespace 前缀");
    }

    @Test
    void sortedByCountDescending() {
        store.create("user:w-1", "a", VarType.NUMBER, "0", null);
        store.create("user:w-1", "b", VarType.NUMBER, "0", null);
        store.create("user:w-1", "c", VarType.NUMBER, "0", null);  // user = 3
        store.create("system", "x", VarType.NUMBER, "0", "system"); // system = 1

        List<String> order = new ArrayList<>(store.statsByNamespace().keySet());
        assertEquals("user", order.get(0), "计数最高的 namespace 应排在前");
    }

    /** 极简 fake：不连 SQLite，仅满足构造与 user namespace 持久化路径（吞写）。 */
    private static final class FakeDao extends UserVariableDao {
        FakeDao() {
            super(Logger.getLogger("test.varstats"), null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
            // no-op
        }

        @Override
        public void delete(String wallId, String name) {
            // no-op
        }

        @Override
        public List<Row> loadAll() {
            return new ArrayList<>();
        }
    }
}
