package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.ProjectState;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectionThrottler} 单测：会话级节流间隔覆盖与 Bucket 锁外投影 / 并发串行不变式。
 */
class ProjectionThrottlerTest {

    @Nested
    class SessionIntervalOverride {

        @Test
        void effectiveInterval_defaultsToMinIntervalMs() {
            ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
            assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                    "未设置 override 时回落到构造期 minIntervalMs");
        }

        @Test
        void setIntervalForSession_overridesDefault() {
            ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
            t.setIntervalForSession("sid-1", 50L);
            assertEquals(50L, t.effectiveIntervalForTest("sid-1"),
                    "set 后 effectiveInterval 取 override");
            assertEquals(200L, t.effectiveIntervalForTest("sid-2"));
        }

        @Test
        void clearSessionInterval_removesOverride() {
            ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
            t.setIntervalForSession("sid-1", 50L);
            t.clearSessionInterval("sid-1");
            assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                    "clear 后回落默认");
        }

        @Test
        void setIntervalForSession_nonPositiveActsAsClear() {
            ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
            t.setIntervalForSession("sid-1", 50L);
            t.setIntervalForSession("sid-1", 0L);
            assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                    "intervalMs<=0 等同 clearSessionInterval");
            t.setIntervalForSession("sid-1", 80L);
            t.setIntervalForSession("sid-1", -5L);
            assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                    "负数也等同 clear");
        }

        @Test
        void discardSession_clearsOverride() {
            ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
            t.setIntervalForSession("sid-1", 50L);
            t.discardSession("sid-1");
            assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                    "discardSession 清掉 sessionIntervalOverride 防泄漏");
        }

        @Test
        void setIntervalForSession_nullSessionIdNoop() {
            ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
            t.setIntervalForSession(null, 50L);
            t.clearSessionInterval(null);
        }
    }

    /**
     * 锁序与同会话串行守卫：投影在 Bucket 锁外执行（claim / perform 两段式），
     * 防止与持有 {@link EditSession} 监视器后调用 {@code submit} 的路径形成 ABBA 死锁，
     * 同时保证同一 session 的并发投影串行执行。
     */
    @Nested
    class LockOrderAndSerialization {

        private static final String SID = "sid-lock-order";

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

        private Session sessionWithEditSession() {
            return SessionTestFactory.withWallAndProject(SID, UUID.randomUUID(), "tester", "w-1",
                    new ProjectState(1, 1, "#FFFFFF"));
        }

        private DirtyRegion region() {
            return DirtyRegion.fullCanvas(new ProjectState(1, 1, "#FFFFFF"));
        }

        private void await(CountDownLatch latch, long seconds) {
            try {
                latch.await(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

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
                    "投影期间 submit 不应被 Bucket 锁阻塞");

            releaseProject.countDown();
            projecting.join(5000);
            assertFalse(projecting.isAlive());
        }

        @Test
        void concurrentEditSessionAndProjection_doesNotDeadlock() throws Exception {
            Session session = sessionWithEditSession();
            EditSession es = session.editSession();

            CountDownLatch armTwoHoldsEditSession = new CountDownLatch(1);
            CountDownLatch goArmTwoSubmit = new CountDownLatch(1);
            CountDownLatch armTwoSubmitDone = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ProjectionThrottler t = new TestThrottler(session, (s, r) -> { });

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
            Thread.sleep(300);
            goArmTwoSubmit.countDown();

            assertTrue(armTwoSubmitDone.await(4, TimeUnit.SECONDS),
                    "臂二持 EditSession 时 submit 不应卡在 Bucket 锁上");

            armTwo.join(5000);
            armOne.join(5000);
            assertFalse(armTwo.isAlive());
            assertFalse(armOne.isAlive(), "臂一应在释放 EditSession 锁后顺利完成");
            assertNull(failure.get(), () -> "并发路径不应抛异常: " + failure.get());
        }

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
                    "同一 session 不得有两次投影并发跑（projecting 标志挡住第二次）");
        }
    }
}
