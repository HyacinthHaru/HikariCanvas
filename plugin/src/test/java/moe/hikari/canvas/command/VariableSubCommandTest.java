package moe.hikari.canvas.command;

import moe.hikari.canvas.i18n.Messages;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableStore;
import moe.hikari.canvas.variable.plugin.PushRateLimiter;
import moe.hikari.canvas.variable.provider.VariableProvider;
import moe.hikari.canvas.variable.provider.VariableProviderDaemon;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P5-B：{@link VariableSubCommand} 单测。
 *
 * <p>不引 MockBukkit / Mockito —— 用 {@link Proxy} 造 CommandSender + 捕获消息。覆盖：</p>
 *
 * <ul>
 *   <li>权限拒绝（hasPermission=false）</li>
 *   <li>list 无 ns / 有 ns / 空 store</li>
 *   <li>get 存在 / 不存在</li>
 *   <li>set 成功 / 不存在</li>
 *   <li>delete 成功 / 不存在</li>
 *   <li>providers 输出 + 空</li>
 *   <li>reload 触发 hook</li>
 *   <li>inspect wall reference + wall 不存在</li>
 *   <li>tab completion 4 分支</li>
 *   <li>未知 subcommand</li>
 * </ul>
 */
class VariableSubCommandTest {

    private VariableStore store;
    private VariableProviderDaemon daemon;
    private FakeWallSource wallSource;
    private VariableSubCommand cmd;
    private List<String> capturedMessages;
    private CommandSender sender;
    private AtomicBoolean reloadCalled;
    private PushRateLimiter.Config reloadedConfig;
    private Messages messages;

    @BeforeEach
    void setUp() {
        store = new VariableStore(new FakeUserVariableDao(), w -> {});
        daemon = new VariableProviderDaemon();
        wallSource = new FakeWallSource();
        reloadCalled = new AtomicBoolean(false);
        reloadedConfig = new PushRateLimiter.Config(200, 2000, 5000L);
        messages = new Messages(Logger.getLogger("test"));
        messages.loadBuiltIn();
        cmd = new VariableSubCommand(store, daemon, (VariableSubCommand.WallSource) wallSource,
                /*auditLog*/ null,
                () -> {
                    reloadCalled.set(true);
                    return reloadedConfig;
                },
                messages);
        capturedMessages = new ArrayList<>();
        sender = makeSender(true, capturedMessages);  // hasPermission = true by default
    }

    /** Convenience: capturedMessages.toString() 便于 contains() 断言。 */
    private String allMessages() {
        return String.join("\n", capturedMessages);
    }

    // ──────────────────────────────────────────────────────────
    //  权限
    // ──────────────────────────────────────────────────────────

    @Test
    void execute_permissionDenied_doesNothing() {
        List<String> deniedMessages = new ArrayList<>();
        CommandSender denied = makeSender(false, deniedMessages);
        cmd.execute(denied, new String[]{"list"});
        assertEquals(1, deniedMessages.size(), "should send single denial message");
        assertTrue(deniedMessages.get(0).contains("Permission denied"));
    }

    @Test
    void execute_emptyArgs_printsUsage() {
        cmd.execute(sender, new String[]{});
        assertTrue(allMessages().contains("/canvas var"),
                "empty args should print usage");
    }

    @Test
    void execute_unknownSub_printsUsage() {
        cmd.execute(sender, new String[]{"fudge"});
        assertTrue(allMessages().contains("Unknown subcommand"));
    }

    // ──────────────────────────────────────────────────────────
    //  list
    // ──────────────────────────────────────────────────────────

    @Test
    void list_empty_printsNoVariables() {
        cmd.execute(sender, new String[]{"list"});
        assertTrue(allMessages().contains("No variables"));
    }

    @Test
    void list_groupsByNamespace() {
        store.create("alpha", "k1", VarType.STRING, null, null);
        store.create("alpha", "k2", VarType.STRING, null, null);
        store.create("beta", "k1", VarType.NUMBER, null, null);
        cmd.execute(sender, new String[]{"list"});
        String out = allMessages();
        assertTrue(out.contains("alpha"));
        assertTrue(out.contains("beta"));
        assertTrue(out.contains("3 total"));
    }

    @Test
    void list_withNamespace_listsVars() {
        store.create("alpha", "k1", VarType.NUMBER, "100", null);
        store.setValue("alpha/k1", "42", null);
        cmd.execute(sender, new String[]{"list", "alpha"});
        String out = allMessages();
        assertTrue(out.contains("alpha/k1"));
        assertTrue(out.contains("42"));
    }

    @Test
    void list_unknownNamespace_returnsEmpty() {
        cmd.execute(sender, new String[]{"list", "zzz"});
        assertTrue(allMessages().contains("No variables in namespace"));
    }

    // ──────────────────────────────────────────────────────────
    //  get
    // ──────────────────────────────────────────────────────────

    @Test
    void get_existing_printsAllMetadata() {
        store.create("system", "time", VarType.STRING, "00:00", "system");
        store.setValue("system/time", "12:34", null);
        cmd.execute(sender, new String[]{"get", "system/time"});
        String out = allMessages();
        assertTrue(out.contains("system/time"));
        assertTrue(out.contains("type"));
        assertTrue(out.contains("STRING"));
        assertTrue(out.contains("12:34"));
        assertTrue(out.contains("source"));
    }

    @Test
    void get_missing_reportsNotFound() {
        cmd.execute(sender, new String[]{"get", "nope/x"});
        assertTrue(allMessages().contains("Variable not found"));
    }

    @Test
    void get_missingArg_printsUsage() {
        cmd.execute(sender, new String[]{"get"});
        assertTrue(allMessages().contains("Usage:"));
    }

    // ──────────────────────────────────────────────────────────
    //  set
    // ──────────────────────────────────────────────────────────

    @Test
    void set_existing_updatesValue() {
        store.create("alpha", "color", VarType.COLOR, "#FFFFFF", null);
        cmd.execute(sender, new String[]{"set", "alpha/color", "#FF0000"});
        assertEquals("#FF0000", store.get("alpha/color").orElseThrow().currentValue());
        assertTrue(allMessages().contains("✓ Set"));
    }

    @Test
    void set_joinsValueWithSpaces() {
        store.create("alpha", "msg", VarType.STRING, null, null);
        cmd.execute(sender, new String[]{"set", "alpha/msg", "hello", "world", "from", "test"});
        assertEquals("hello world from test", store.get("alpha/msg").orElseThrow().currentValue());
    }

    @Test
    void set_missing_reportsNotFound() {
        cmd.execute(sender, new String[]{"set", "nope/x", "val"});
        assertTrue(allMessages().contains("Variable not found"));
    }

    @Test
    void set_insufficientArgs_printsUsage() {
        cmd.execute(sender, new String[]{"set", "x"});
        assertTrue(allMessages().contains("Usage:"));
    }

    // ──────────────────────────────────────────────────────────
    //  delete
    // ──────────────────────────────────────────────────────────

    @Test
    void delete_existing_removesFromStore() {
        store.create("alpha", "tmp", VarType.STRING, null, null);
        cmd.execute(sender, new String[]{"delete", "alpha/tmp"});
        assertTrue(store.get("alpha/tmp").isEmpty());
        assertTrue(allMessages().contains("✓ Deleted"));
    }

    @Test
    void delete_missing_reportsNotFound() {
        cmd.execute(sender, new String[]{"delete", "nope/x"});
        assertTrue(allMessages().contains("Variable not found"));
    }

    // ──────────────────────────────────────────────────────────
    //  providers
    // ──────────────────────────────────────────────────────────

    @Test
    void providers_empty_printsNone() {
        cmd.execute(sender, new String[]{"providers"});
        assertTrue(allMessages().contains("No providers"));
    }

    @Test
    void providers_listsAll() {
        daemon.register(new FakeProvider("alpha", "Alpha Display", true, Duration.ZERO));
        daemon.register(new FakeProvider("beta", "Beta Display", false, Duration.ofSeconds(10)));
        cmd.execute(sender, new String[]{"providers"});
        String out = allMessages();
        assertTrue(out.contains("alpha"));
        assertTrue(out.contains("beta"));
        assertTrue(out.contains("[dynamic]"));
        assertTrue(out.contains("[static]"));
        assertTrue(out.contains("10s"));
    }

    // ──────────────────────────────────────────────────────────
    //  reload
    // ──────────────────────────────────────────────────────────

    @Test
    void reload_invokesHook_andDisplaysConfig() {
        cmd.execute(sender, new String[]{"reload"});
        assertTrue(reloadCalled.get(), "reloadHook should be called");
        String out = allMessages();
        assertTrue(out.contains("Config reloaded"));
        assertTrue(out.contains("200/s"));
        assertTrue(out.contains("2000/s"));
        assertTrue(out.contains("5000ms"));
    }

    @Test
    void reload_hookThrows_reportsFailure() {
        VariableSubCommand failCmd = new VariableSubCommand(store, daemon,
                (VariableSubCommand.WallSource) wallSource, null,
                () -> { throw new RuntimeException("boom"); },
                messages);
        failCmd.execute(sender, new String[]{"reload"});
        assertTrue(allMessages().contains("Reload failed"));
    }

    // ──────────────────────────────────────────────────────────
    //  inspect
    // ──────────────────────────────────────────────────────────

    @Test
    void inspect_wallWithReferences_listsThem() {
        store.create("system", "time", VarType.STRING, null, null);
        store.create("alpha", "score", VarType.NUMBER, "0", null);
        store.setValue("alpha/score", "42", null);
        // 模拟 Compositor markWallReferences
        store.markWallReferences("w-test", Set.of("system/time", "alpha/score"));
        wallSource.addWall("w-test");
        cmd.execute(sender, new String[]{"inspect", "w-test"});
        String out = allMessages();
        assertTrue(out.contains("w-test"));
        assertTrue(out.contains("system/time"));
        assertTrue(out.contains("alpha/score"));
        assertTrue(out.contains("42"));
    }

    @Test
    void inspect_wallWithoutReferences_says_noVars() {
        wallSource.addWall("w-empty");
        cmd.execute(sender, new String[]{"inspect", "w-empty"});
        assertTrue(allMessages().contains("No variables referenced"));
    }

    @Test
    void inspect_missingWall_stillShowsReferences() {
        // wall 不在 wallRepo 但 byWall 有引用（边界场景）
        store.create("system", "time", VarType.STRING, null, null);
        store.markWallReferences("w-ghost", Set.of("system/time"));
        cmd.execute(sender, new String[]{"inspect", "w-ghost"});
        String out = allMessages();
        assertTrue(out.contains("Wall not found"));
        assertTrue(out.contains("system/time"));
    }

    // ──────────────────────────────────────────────────────────
    //  Tab completion
    // ──────────────────────────────────────────────────────────

    @Test
    void completions_emptyArgs_returnsAllSubcommands() {
        List<String> all = cmd.completions(new String[]{});
        assertEquals(VariableSubCommand.SUBCOMMANDS, all);
    }

    @Test
    void completions_firstArg_filtersByPrefix() {
        List<String> filtered = cmd.completions(new String[]{"l"});
        assertEquals(List.of("list"), filtered);
        List<String> filtered2 = cmd.completions(new String[]{"d"});
        assertEquals(List.of("delete"), filtered2);
    }

    @Test
    void completions_list_secondArg_namespaces() {
        store.create("alpha", "k", VarType.STRING, null, null);
        store.create("beta", "k", VarType.STRING, null, null);
        store.create("alphabet", "k", VarType.STRING, null, null);
        List<String> all = cmd.completions(new String[]{"list", ""});
        assertTrue(all.contains("alpha"));
        assertTrue(all.contains("beta"));
        List<String> filtered = cmd.completions(new String[]{"list", "alp"});
        assertTrue(filtered.contains("alpha"));
        assertTrue(filtered.contains("alphabet"));
        assertFalse(filtered.contains("beta"));
    }

    @Test
    void completions_get_secondArg_fullNames() {
        store.create("alpha", "k1", VarType.STRING, null, null);
        store.create("alpha", "k2", VarType.STRING, null, null);
        List<String> filtered = cmd.completions(new String[]{"get", "alpha/k"});
        assertTrue(filtered.contains("alpha/k1"));
        assertTrue(filtered.contains("alpha/k2"));
    }

    @Test
    void completions_inspect_secondArg_wallIds() {
        wallSource.addWall("w-aaaa");
        wallSource.addWall("w-bbbb");
        List<String> all = cmd.completions(new String[]{"inspect", ""});
        assertTrue(all.contains("w-aaaa"));
        assertTrue(all.contains("w-bbbb"));
        List<String> filtered = cmd.completions(new String[]{"inspect", "w-a"});
        assertTrue(filtered.contains("w-aaaa"));
        assertFalse(filtered.contains("w-bbbb"));
    }

    @Test
    void completions_unknownSub_returnsEmpty() {
        List<String> empty = cmd.completions(new String[]{"foobar", ""});
        assertTrue(empty.isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  Fakes
    // ──────────────────────────────────────────────────────────

    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() { super(Logger.getLogger("test"), null); }
        @Override public void upsert(String wallId, String name, VarType type,
                                     String defaultValue, String currentValue, String boundTo,
                                     long createdAt, long updatedAt) {}
        @Override public void delete(String wallId, String name) {}
        @Override public List<Row> loadAll() { return new ArrayList<>(); }
    }

    /** {@link VariableSubCommand.WallSource} 测试实现：内存里塞 wallId。 */
    private static final class FakeWallSource implements VariableSubCommand.WallSource {
        private final List<String> ids = new ArrayList<>();
        void addWall(String wallId) { ids.add(wallId); }
        @Override public List<String> allWallIds() { return List.copyOf(ids); }
        @Override public boolean exists(String wallId) { return ids.contains(wallId); }
    }

    private static final class FakeProvider implements VariableProvider {
        private final String ns;
        private final String display;
        private final boolean dynamic;
        private final Duration interval;
        FakeProvider(String ns, String display, boolean dynamic, Duration interval) {
            this.ns = ns;
            this.display = display;
            this.dynamic = dynamic;
            this.interval = interval;
        }
        @Override public String namespace() { return ns; }
        @Override public String displayName() { return display; }
        @Override public void initialize() {}
        @Override public boolean refresh() { return false; }
        @Override public Duration refreshInterval() { return interval; }
        @Override public void shutdown() {}
        @Override public boolean isDynamic() { return dynamic; }
    }

    /**
     * 用 Proxy 造 CommandSender，把所有 {@code sendMessage(...)} 入参拼字符串塞进 {@code messages}。
     * 同时支持 String / Component / String[] 三种重载。{@code hasPermission} 返 fixed 配置值。
     */
    static CommandSender makeSender(boolean hasPerm, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("hasPermission".equals(name)) return hasPerm;
                    if ("getName".equals(name)) return "TestSender";
                    if ("sendMessage".equals(name)) {
                        if (args == null || args.length == 0) return null;
                        Object first = args[0];
                        if (first instanceof String s) {
                            messages.add(s);
                        } else if (first instanceof String[] arr) {
                            for (String s : arr) messages.add(s);
                        } else if (first instanceof Component c) {
                            messages.add(c.toString());
                        } else if (first != null) {
                            messages.add(String.valueOf(first));
                        }
                        return null;
                    }
                    if ("equals".equals(name)) return proxy == args[0];
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("toString".equals(name)) return "CapturingSender";
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0.0;
                    if (rt == float.class) return 0.0f;
                    if (rt.isPrimitive()) return 0;
                    return null;
                });
    }
}
