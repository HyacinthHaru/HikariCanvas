package ac.haru.hikaricanvas.variable;

import ac.haru.hikaricanvas.storage.UserVariableDao;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Logger;

/**
 * 纯内存单元测试共用的空实现 {@link UserVariableDao}，不触碰 SQLite。
 */
public class NoopUserVariableDao extends UserVariableDao {

    public NoopUserVariableDao() {
        super(Logger.getLogger("test"), null);
    }

    @Override
    public void upsert(String wallId, String name, VarType type,
                       @Nullable String defaultValue,
                       @Nullable String currentValue,
                       @Nullable String boundTo,
                       long createdAt, long updatedAt) {
    }

    @Override
    public void delete(String wallId, String name) {
    }

    @Override
    public int deleteByWall(String wallId) {
        return 0;
    }

    @Override
    public List<Row> loadAll() {
        return List.of();
    }

    @Override
    public List<Row> listByWall(String wallId) {
        return List.of();
    }
}
