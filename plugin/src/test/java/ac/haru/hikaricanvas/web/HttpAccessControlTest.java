package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.storage.WallRepo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 与输入限流面的访问控制守卫：
 * <ul>
 *   <li>{@code GET /api/walls} 仅暴露 {@link WallRepo.PublicSummary} 的白名单公开字段</li>
 *   <li>{@code brush.end} 计入会话输入限流，其余 brush op 保持豁免</li>
 * </ul>
 */
class HttpAccessControlTest {

    @Test
    void publicWallSummary_carriesNoLocationOrIdentity() {
        Set<String> actual = Arrays.stream(WallRepo.PublicSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of("wallId", "alias", "widthMaps", "heightMaps", "publishedAt", "updatedAt"),
                actual,
                "PublicSummary field set changed; must not expose ownerName / world / origin / facing");
    }

    @Test
    void internalSummary_stillCarriesFullFields() {
        Set<String> actual = Arrays.stream(WallRepo.Summary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertTrue(actual.containsAll(
                        List.of("ownerName", "world", "originX", "originY", "originZ", "facing")),
                "In-game /canvas list paths require full Summary fields");
    }

    @Test
    void brushEnd_isRateLimited() {
        assertTrue(BrushOpDispatcher.isRateLimited("brush.end"));
    }

    @Test
    void otherBrushOps_stayExempt() {
        assertFalse(BrushOpDispatcher.isRateLimited("brush.start"));
        assertFalse(BrushOpDispatcher.isRateLimited("brush.point"));
        assertFalse(BrushOpDispatcher.isRateLimited("brush.cancel"));
        assertEquals(1, BrushOpDispatcher.RATE_LIMITED_OPS.size());
    }
}
