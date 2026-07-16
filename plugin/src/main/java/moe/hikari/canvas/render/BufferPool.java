package moe.hikari.canvas.render;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link BufferedImage} 复用池。动机与约束见 {@code docs/timeline.md §3.3}
 * 与 {@code docs/architecture.md §5.5}：动画逐帧 rasterize 的 ARGB 分配
 * （主 buffer + slow-path layer buffer）在 8×8 多墙 / 高 fps 下成 GC 压力源。
 *
 * <p><b>线程限定（核心安全设计）：</b>池绑定唯一 owner 线程（AnimationTicker 单线程）。
 * 非 owner 线程的 {@link #acquire} 一律退化为 {@code new BufferedImage}、{@link #release}
 * 一律 no-op —— 既有反应式路径（ProjectionThrottler 异步线程 / WallRestorer 主线程）行为
 * 零变化，{@code CanvasCompositor.rasterize}「每次 new 保证并发安全」的契约对它们仍然成立。
 * 池内部数据结构只在 owner 线程访问，无锁。</p>
 *
 * <p><b>复用语义：</b>按 (width, height) 精确匹配；命中时用
 * {@link AlphaComposite#Clear} 全图清零（与新建 buffer 的 ARGB(0,0,0,0) 像素逐位等价，
 * 量化层 alpha&lt;128 → palette index 0 两路一致，见 rendering.md §6.4）。</p>
 */
public final class BufferPool {

    /** 每种尺寸最多缓存的 buffer 数（主 + 多 layer 同尺寸；超出直接弃给 GC）。 */
    static final int MAX_POOLED_PER_SIZE = 4;

    /** 池总容量上限（异常场景兜底，防多尺寸累积）。 */
    static final int MAX_POOLED_TOTAL = 16;

    private volatile Thread ownerThread;

    /** (w << 32 | h) → 空闲栈。仅 owner 线程访问。 */
    private final Map<Long, ArrayDeque<BufferedImage>> free = new HashMap<>();
    private int totalPooled;

    /** 绑定当前线程为 owner（AnimationTicker 线程启动时调一次）。 */
    public void bindToCurrentThread() {
        this.ownerThread = Thread.currentThread();
    }

    /** 测试 seam：显式绑定。 */
    void bindTo(Thread t) {
        this.ownerThread = t;
    }

    /**
     * 借一张 {@code w×h} 的 ARGB buffer。owner 线程命中池时返回清零后的复用 buffer；
     * 其余情况（非 owner 线程 / 池空 / 尺寸不匹配）新建。
     */
    public BufferedImage acquire(int w, int h) {
        if (Thread.currentThread() != ownerThread) {
            return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        ArrayDeque<BufferedImage> stack = free.get(key(w, h));
        BufferedImage img = stack == null ? null : stack.poll();
        if (img == null) {
            return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        totalPooled--;
        clear(img);
        return img;
    }

    /**
     * 归还 buffer。仅 owner 线程且容量未满时入池；其余情况静默丢弃（交 GC）。
     * {@code null} 安全。
     */
    public void release(BufferedImage img) {
        if (img == null || Thread.currentThread() != ownerThread) return;
        if (img.getType() != BufferedImage.TYPE_INT_ARGB) return;
        if (totalPooled >= MAX_POOLED_TOTAL) return;
        ArrayDeque<BufferedImage> stack =
                free.computeIfAbsent(key(img.getWidth(), img.getHeight()), k -> new ArrayDeque<>());
        if (stack.size() >= MAX_POOLED_PER_SIZE) return;
        stack.push(img);
        totalPooled++;
    }

    /** 测试观察：当前池内 buffer 总数。 */
    int pooledCount() {
        return totalPooled;
    }

    private static long key(int w, int h) {
        return ((long) w << 32) | (h & 0xFFFFFFFFL);
    }

    /** ARGB 全图清零：AlphaComposite.Clear + fillRect，与新建 buffer 像素逐位等价。 */
    private static void clear(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
        } finally {
            g.dispose();
        }
    }
}
