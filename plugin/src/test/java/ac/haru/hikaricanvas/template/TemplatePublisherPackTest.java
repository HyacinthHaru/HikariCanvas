package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.TemplateRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 端到端：{@link TemplatePublisher#publish} 把当前 wall 存成 {@code .canvas} pack。
 *
 * <p>验证：pack 文件落到 {@code user-templates/<uuid>/<slug>.canvas}、DB {@code templates} 行的
 * {@code file_path} 指向它、{@link TemplateRegistry#reload} 后按 <b>templateId</b>（= manifest
 * 自声明 id）能查到 pack 条目——即存的 DB id 与注册表条目 key 一致（apply 才找得到）。
 * compositor 传 {@code null}：缩略图是 best-effort，publish 主链不依赖它。</p>
 */
class TemplatePublisherPackTest {

    private static final Logger LOG = Logger.getLogger("template-publisher-pack-test");

    private Path dataFolder;
    private Database database;
    private TemplateRepo repo;
    private TemplateRegistry registry;
    private TemplatePublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        dataFolder = Files.createTempDirectory("hc-publisher-");
        database = new Database(LOG, dataFolder.resolve("d.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        repo = new TemplateRepo(LOG, database.jdbi());
        registry = new TemplateRegistry(LOG, TemplatePublisherPackTest.class,
                dataFolder.resolve("templates"), dataFolder.resolve("user-templates"));
        registry.reload();
        // compositor=null：preview 渲染 best-effort（publish 内 try/catch 吞掉）
        publisher = new TemplatePublisher(LOG, dataFolder, registry, repo, null, 10);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (dataFolder != null && Files.exists(dataFolder)) {
            try (var paths = Files.walk(dataFolder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
            }
        }
    }

    private static ProjectState oneTextWall(String text) {
        ProjectState s = new ProjectState(2, 1);
        s.addElement(new TextElement("e1", 5, 5, 100, 20, 0, false, true,
                text, "ark_pixel", 12, "#000000", "left", 0f, 1.2f, false,
                null, null, null, null, null, null));
        return s;
    }

    @Test
    void publish_writesPackFile_dbRow_andRegistryPicksItUpById() {
        UUID owner = UUID.randomUUID();
        TemplatePublisher.Result r = publisher.publish(owner, "Steve",
                "mysign", "My Sign", "a sign", TemplateExporter.ParamConfig.empty(),
                oneTextWall("人民广场"), false);

        TemplatePublisher.Result.Ok ok = assertInstanceOf(TemplatePublisher.Result.Ok.class, r);
        String templateId = ok.templateId();
        assertEquals("user-" + owner.toString().replace("-", "").substring(0, 8) + "-mysign", templateId);

        // 1) .canvas 文件落盘
        Path packFile = dataFolder.resolve("user-templates").resolve(owner.toString()).resolve("mysign.canvas");
        assertTrue(Files.exists(packFile), "pack 文件应写到 user-templates/<uuid>/mysign.canvas");

        // 2) DB 行 file_path 指向它
        Optional<TemplateRepo.Row> row = repo.findById(templateId);
        assertTrue(row.isPresent(), "DB templates 应有该行");
        assertEquals("user-templates/" + owner + "/mysign.canvas", row.get().filePath());
        assertFalse(row.get().builtin());

        // 3) 注册表 reload 后按 templateId 查到 pack 条目（DB id 与注册表 key 一致）
        TemplateEntry entry = registry.byId(templateId);
        assertNotNull(entry, "注册表应按 templateId（= manifest.id）查到，而非文件名 stem");
        assertTrue(entry.isPack());
        assertEquals(templateId, entry.spec().id());
        assertEquals(owner, entry.ownerUuid().orElseThrow(), "user 源条目应带 owner（取自目录名）");
        assertTrue(entry.spec().params().containsKey("text_1"), "text 元素应参数化为 text_1");
    }

    @Test
    void delete_removesFileAndDbRow() {
        UUID owner = UUID.randomUUID();
        TemplatePublisher.Result pub = publisher.publish(owner, "Steve",
                "gone", "Gone", null, TemplateExporter.ParamConfig.empty(),
                oneTextWall("x"), false);
        String templateId = ((TemplatePublisher.Result.Ok) pub).templateId();
        Path packFile = dataFolder.resolve("user-templates").resolve(owner.toString()).resolve("gone.canvas");
        assertTrue(Files.exists(packFile));

        TemplatePublisher.Result del = publisher.delete(templateId, owner, false);

        assertInstanceOf(TemplatePublisher.Result.Ok.class, del);
        assertFalse(Files.exists(packFile), "删除应移除 .canvas 文件");
        assertTrue(repo.findById(templateId).isEmpty(), "删除应移除 DB 行");
        assertFalse(registry.byId(templateId) != null && registry.byId(templateId).isPack(),
                "删除并 reload 后注册表不应再有该 pack");
    }
}
