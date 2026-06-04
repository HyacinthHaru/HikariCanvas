package moe.hikari.canvas.render;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BufferPool} 单测（0.6 P2）。动机与线程限定约束见 {@code docs/timeline.md §3.3} 与
 * {@code docs/architecture.md §5.5}。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>owner 线程：acquire→release→acquire 同尺寸复用同实例 + 复用前像素全清零</li>
 *   <li>非 owner 线程：acquire 永远新实例、release 不入池（pooledCount 不变）</li>
 *   <li>尺寸不匹配 → 新实例；MAX_POOLED_PER_SIZE / MAX_POOLED_TOTAL 上限弃多余 release</li>
 * </ul>
 *
 * <p>同包访问 package-private 的 {@code bindTo} / {@code pooledCount} /
 * {@code MAX_POOLED_PER_SIZE} / {@code MAX_POOLED_TOTAL}。线程限定测试在当前测试线程
 * 上 {@code bindTo(Thread.currentThread())}，再用子线程 join 验证非 owner 行为。</p>
 */
class BufferPoolTest {

    private static BufferPool ownedPool() {
        BufferPool pool = new BufferPool();
        pool.bindTo(Thread.currentThread());
        return pool;
    }

    @Test
    void ownerThread_reusesSameInstanceForSameSize() {
        BufferPool pool = ownedPool();
        BufferedImage a = pool.acquire(64, 32);
        pool.release(a);
        assertEquals(1, pool.pooledCount(), "release 后入池");
        BufferedImage b = pool.acquire(64, 32);
        assertSame(a, b, "owner 同尺寸 acquire 复用同实例");
        assertEquals(0, pool.pooledCount(), "acquire 命中后池清空");
    }

    @Test
    void ownerThread_reusedBufferIsCleared() {
        BufferPool pool = ownedPool();
        BufferedImage a = pool.acquire(8, 8);
        // 涂满非零像素
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                a.setRGB(x, y, 0xFFAABBCC);
            }
        }
        pool.release(a);
        BufferedImage b = pool.acquire(8, 8);
        assertSame(a, b, "复用同实例（前置）");
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(0, b.getRGB(x, y),
                        "复用 buffer 应被清零 ARGB(0,0,0,0) at " + x + "," + y);
            }
        }
    }

    @Test
    void sizeMismatch_returnsNewInstance() {
        BufferPool pool = ownedPool();
        BufferedImage a = pool.acquire(64, 32);
        pool.release(a);
        BufferedImage other = pool.acquire(64, 33);   // 高度不同
        assertNotSame(a, other, "尺寸不匹配 → 新实例");
        assertEquals(1, pool.pooledCount(), "原 64×32 仍在池内（未被借走）");
    }

    @Test
    void nonOwnerThread_acquireAlwaysNew_releaseDoesNotPool() throws InterruptedException {
        BufferPool pool = ownedPool();
        // owner 先填一张进池
        BufferedImage seeded = pool.acquire(16, 16);
        pool.release(seeded);
        assertEquals(1, pool.pooledCount(), "owner 入池前置");

        AtomicReference<BufferedImage> childAcquired = new AtomicReference<>();
        AtomicBoolean differentFromSeeded = new AtomicBoolean(false);
        Thread child = new Thread(() -> {
            // 非 owner：即便池里有同尺寸 buffer，也必须新建（不命中池）
            BufferedImage got = pool.acquire(16, 16);
            childAcquired.set(got);
            differentFromSeeded.set(got != seeded);
            // 非 owner release 必须 no-op
            pool.release(got);
            pool.release(pool.acquire(16, 16));
        });
        child.start();
        child.join();

        assertTrue(differentFromSeeded.get(), "非 owner acquire 不命中池，永远新实例");
        assertEquals(1, pool.pooledCount(),
                "非 owner acquire 不消耗池（计数不变）+ 非 owner release 不入池");
    }

    @Test
    void releaseRejectsNullAndWrongType() {
        BufferPool pool = ownedPool();
        pool.release(null);   // null 安全
        assertEquals(0, pool.pooledCount(), "release(null) 不入池");
        // 非 ARGB 类型不入池（contract：getType() != TYPE_INT_ARGB 直接返回）
        BufferedImage rgb = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        pool.release(rgb);
        assertEquals(0, pool.pooledCount(), "非 ARGB buffer 不入池");
    }

    @Test
    void perSizeCapDropsExcess() {
        BufferPool pool = ownedPool();
        // release 比 MAX_POOLED_PER_SIZE 多 1 张同尺寸 → 超出被弃
        int n = BufferPool.MAX_POOLED_PER_SIZE + 1;
        for (int i = 0; i < n; i++) {
            pool.release(new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB));
        }
        assertEquals(BufferPool.MAX_POOLED_PER_SIZE, pool.pooledCount(),
                "同尺寸入池数封顶 MAX_POOLED_PER_SIZE");
    }

    @Test
    void totalCapDropsExcessAcrossSizes() {
        BufferPool pool = ownedPool();
        // 用多种尺寸（每种填满 per-size cap）逼近并越过 MAX_POOLED_TOTAL
        int sizesNeeded = BufferPool.MAX_POOLED_TOTAL / BufferPool.MAX_POOLED_PER_SIZE + 2;
        for (int s = 0; s < sizesNeeded; s++) {
            int w = 100 + s;   // 每种尺寸唯一
            for (int i = 0; i < BufferPool.MAX_POOLED_PER_SIZE; i++) {
                pool.release(new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB));
            }
        }
        assertEquals(BufferPool.MAX_POOLED_TOTAL, pool.pooledCount(),
                "池总量封顶 MAX_POOLED_TOTAL，超出 release 被弃");
        // 再 release 一张已不可入池
        pool.release(new BufferedImage(999, 999, BufferedImage.TYPE_INT_ARGB));
        assertEquals(BufferPool.MAX_POOLED_TOTAL, pool.pooledCount(), "越上限后 release 一律弃");
    }

    @Test
    void poolEmptyAcquireReturnsNewInstance() {
        BufferPool pool = ownedPool();
        // 池空时 acquire 必返新实例，计数保持 0
        BufferedImage a = pool.acquire(40, 40);
        assertEquals(0, pool.pooledCount(), "池空 acquire 不影响计数");
        assertFalse(a == null, "acquire 返回非 null");
        assertEquals(40, a.getWidth());
        assertEquals(40, a.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, a.getType(), "新建 buffer 为 ARGB");
    }
}
