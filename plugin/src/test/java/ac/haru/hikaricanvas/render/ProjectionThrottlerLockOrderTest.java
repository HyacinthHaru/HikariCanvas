package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.ProjectState;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.17：{@link ProjectionThrottler} 的锁序回归守卫（必修 Bug #2，ABBA 死锁）。
 *
 * <p><b>死锁的两条臂</b>：</p>
 * <ul>
 *   <li>臂一（投影线程）：{@code submit} 持 {@code synchronized(bucket)} → flush →
 *       {@code projectUnderEditLock} → {@code synchronized(editSession)}</li>
 *   <li>臂二（Jetty 线程）：{@code EditSession.setUserVariableValue} 是 {@code synchronized}，
 *       在监视器内同步调 {@code VariableStore.setValue} → {@code notifyReferencingWalls} →
 *       {@code wallDirtyCallback} → {@code SessionManager.submitFullCanvasDirtyByWallAndReport}
 *       → {@code throttler.submit} → {@code synchronized(bucket)}</li>
 * </ul>
 *
 * <p>两臂并发即互等。死锁后 {@code /canvas cancel} 与 {@code SessionReaper} 经
 * {@code preForgetHook.flushNow} 取同一 Bucket 锁，<b>主线程永久冻结</b>。
 * {@code ManualScheduleProvider} 每秒 push 也会走臂二。</p>
 *
 * <p>修法是把投影移出 Bucket 锁（claim / perform 两段式）。本测试正面断言这个不变式，
 * 并用一个真并发场景跑两臂——修复前会挂死（超时失败），修复后两臂都能完成。</p>
 */
class ProjectionThrottlerLockOrderTest {

    private static final String SID = "sid-lock-order";

    /**
     * 覆盖两个 seam：session 查找（绕开 final 的 SessionManager 全装配链）
     * 与投影（{@link CanvasProjector} 是 final class，需要能中途阻塞的假投影撑开并发窗口）。
     */
    private static final class TestThrottler extends ProjectionThrottler {
        private final Session session;
        private final BiConsumer<Session, DirtyRegion> fakeProject;

        TestThrottler(Session session, BiConsumer<Session, DirtyRegion> fakeProject) {
            super(null, null, null, 0L);
            this.session = session;
            this.fakeProject = fakeProject;
        }

        @Override
        Session sessionFor(String sessionId) {
            return SID.equals(sessionId) ? session : null;
        }

        @Override
        void doProject(Session s, DirtyRegion toProject) {
            fakeProject.accept(s, toProject);
        }
    }

    private static Session sessionWithEditSession() {
        return SessionTestFactory.withWallAndProject(SID, UUID.randomUUID(), "tester", "w-1",
                new ProjectState(1, 1, "#FFFFFF"));
    }

    private static DirtyRegion region() {
        return DirtyRegion.fullCanvas(new ProjectState(1, 1, "#FFFFFF"));
    }

    /**
     * 核心不变式：投影执行期间不得持有 Bucket 监视器。
     *
     * <p>Bucket 是私有内部类，测试拿不到实例——改用等价的可观测判据：投影进行中时从另一个
     * 线程 {@code submit} 必须<b>不被阻塞</b>。若投影仍在 Bucket 锁内跑，另一线程的
     * submit 会卡在 {@code synchronized(b)} 上直到投影结束。</p>
     */
    @Test
    void projectionRunsWithoutHoldingBucketLock() throws Exception {
        CountDownLatch projectEntered = new CountDownLatch(1);
        CountDownLatch releaseProject = new CountDownLatch(1);
        AtomicBoolean secondSubmitReturned = new AtomicBoolean(false);

        ProjectionThrottler t = new TestThrottler(sessionWithEditSession(), (s, r) -> {
            projectEntered.countDown();
            await(releaseProject, 5);
        });

        Thread projecting = new Thread(() -> t.submit(SID, region()), "projecting");
        projecting.start();
        assertTrue(projectEntered.await(5, TimeUnit.SECONDS), "投影应被触发");

        Thread second = new Thread(() -> {
            t.submit(SID, region());
            secondSubmitReturned.set(true);
        }, "second-submit");
        second.start();
        second.join(3000);

        assertTrue(secondSubmitReturned.get(),
                "投影期间 submit 被阻塞 = 投影仍在 Bucket 锁内跑，ABBA 死锁面还在");

        releaseProject.countDown();
        projecting.join(5000);
        assertFalse(projecting.isAlive());
    }

    /**
     * 真·两臂并发：一条线程持 {@link EditSession} 监视器再 submit（臂二），
     * 另一条线程走正常投影路径（臂一，内部要取 EditSession 锁）。
     *
     * <p>修复前必死锁 → 本用例超时失败。修复后两臂都在超时内完成。</p>
     */
    @Test
    void concurrentEditSessionAndProjection_doesNotDeadlock() throws Exception {
        Session session = sessionWithEditSession();
        EditSession es = session.editSession();

        CountDownLatch armTwoHoldsEditSession = new CountDownLatch(1);
        CountDownLatch goArmTwoSubmit = new CountDownLatch(1);
        CountDownLatch armTwoSubmitDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 投影本体无需阻塞——本用例的窗口由 EditSession 锁天然撑开：臂二先持着 es，
        // 臂一走到取 es 那步就会停住。
        ProjectionThrottler t = new TestThrottler(session, (s, r) -> { });

        // 臂二：持 EditSession 监视器，等信号后再 submit（模拟 setUserVariableValue 的
        // 同步回调链：synchronized 方法内部 → store.setValue → … → throttler.submit）
        Thread armTwo = new Thread(() -> {
            synchronized (es) {
                armTwoHoldsEditSession.countDown();
                await(goArmTwoSubmit, 5);
                try {
                    t.submit(SID, region());
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    armTwoSubmitDone.countDown();
                }
            }
        }, "arm-two-editsession");

        // 臂一：正常 submit → 认领 → 取 EditSession 锁投影
        Thread armOne = new Thread(() -> {
            try {
                t.submit(SID, region());
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }, "arm-one-projection");

        armTwo.start();
        assertTrue(armTwoHoldsEditSession.await(5, TimeUnit.SECONDS), "臂二应先拿到 EditSession 锁");

        armOne.start();
        // 给臂一时间走到"要 EditSession 锁"那步。修复前它此刻仍持着 Bucket 锁，
        // 修复后 Bucket 锁已在认领时释放。这个 sleep 只影响能否命中窗口，命中后
        // 修复前的阻塞是永久的，不存在偶然通过。
        Thread.sleep(300);
        goArmTwoSubmit.countDown();

        assertTrue(armTwoSubmitDone.await(4, TimeUnit.SECONDS),
                "臂二持 EditSession 时 submit 卡在 Bucket 锁上 = ABBA 死锁面还在");

        armTwo.join(5000);
        armOne.join(5000);
        assertFalse(armTwo.isAlive());
        assertFalse(armOne.isAlive(), "臂一未能完成 = 持 Bucket 时被 EditSession 锁挡住");
        assertNull(failure.get(), () -> "并发路径不应抛异常: " + failure.get());
    }

    /** 同一 session 的投影仍必须串行（原本靠 Bucket 监视器，现在靠 projecting 标志）。 */
    @Test
    void concurrentSubmits_projectionStaysSerialized() throws Exception {
        AtomicBoolean inProject = new AtomicBoolean(false);
        AtomicBoolean overlapDetected = new AtomicBoolean(false);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ProjectionThrottler t = new TestThrottler(sessionWithEditSession(), (s, r) -> {
            if (!inProject.compareAndSet(false, true)) {
                overlapDetected.set(true);
            }
            firstEntered.countDown();
            await(release, 3);
            inProject.set(false);
        });

        Thread a = new Thread(() -> t.submit(SID, region()), "sub-a");
        a.start();
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

        Thread b = new Thread(() -> t.submit(SID, region()), "sub-b");
        b.start();
        b.join(3000);

        release.countDown();
        a.join(5000);
        b.join(5000);

        assertFalse(overlapDetected.get(),
                "同一 session 不得有两次投影并发跑（projecting 标志应挡住第二次）");
    }

    private static void await(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
