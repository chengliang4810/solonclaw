package com.jimuqu.solon.claw.profile.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteProfileTaskRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证协作任务回复提交和通知的竞态边界。 */
class ProfileTaskCoordinatorTest {
    /** 测试数据库。 */
    private SqliteDatabase database;

    /** 任务仓储。 */
    private SqliteProfileTaskRepository repository;

    /** 仅记录通知的协调器。 */
    private RecordingCoordinator coordinator;

    /** 创建隔离控制面。 */
    @BeforeEach
    void setUp() throws Exception {
        Path home = Files.createTempDirectory("profile-task-coordinator-test");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        database = new SqliteDatabase(config);
        repository = new SqliteProfileTaskRepository(database);
        coordinator = new RecordingCoordinator(repository, config);
    }

    /** 释放线程池和数据库。 */
    @AfterEach
    void tearDown() {
        coordinator.close();
        database.shutdown();
    }

    /** 错误回复按失败提交，耗尽次数时通知真实 BLOCKED 状态。 */
    @Test
    void shouldPersistErrorReplyAsBlocked() throws Exception {
        ProfileTaskRecord task = task("error");
        task.setMaxAttempts(1);
        repository.save(task);
        ProfileTaskRecord claimed = repository.claimNext(5);

        coordinator.finishReply(claimed, GatewayReply.error("model failed"));

        assertThat(repository.findById("error").getStatus()).isEqualTo("BLOCKED");
        assertThat(coordinator.statuses).containsExactly("BLOCKED");
    }

    /** 取消后的迟到成功结果被 CAS 拒绝且不发送虚假完成通知。 */
    @Test
    void shouldDropLateSuccessAfterCancellation() throws Exception {
        repository.save(task("cancelled"));
        ProfileTaskRecord claimed = repository.claimNext(5);
        repository.cancel("cancelled");

        coordinator.finishReply(claimed, GatewayReply.ok("late result"));

        assertThat(repository.findById("cancelled").getStatus()).isEqualTo("CANCELLED");
        assertThat(coordinator.statuses).isEmpty();
    }

    /** 正常调用必须提交完成状态、结果和完成通知。 */
    @Test
    void shouldPersistSuccessfulExecution() throws Exception {
        ProfileTaskRecord claimed = saveAndClaim(task("completed"));
        ProfileTaskCoordinator.TaskExecution execution =
                coordinator
                .new TaskExecution(claimed.getTaskId(), () -> GatewayReply.ok("completed result"));

        coordinator.execute(claimed, execution);

        ProfileTaskRecord stored = repository.findById("completed");
        assertThat(stored.getStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getResult()).isEqualTo("completed result");
        assertThat(coordinator.statuses).containsExactly("COMPLETED");
    }

    /** 协作结果回流必须标记为后台完成事件，避免误创建用户对话阶段说明通道。 */
    @Test
    void shouldBuildBackgroundSourceNotification() {
        ProfileTaskRecord task = task("callback");
        task.setSourceKey("DINGTALK:chat-1:thread-1:user-1");

        GatewayMessage message =
                ProfileTaskCoordinator.sourceNotificationMessage(
                        task, "COMPLETED", "finished", null);

        assertThat(message.getPlatform()).isEqualTo(PlatformType.DINGTALK);
        assertThat(message.getChatId()).isEqualTo("chat-1");
        assertThat(message.getUserId()).isEqualTo("user-1");
        assertThat(message.getThreadId()).isEqualTo("thread-1");
        assertThat(message.getProfile()).isEqualTo("default");
        assertThat(message.sourceKey()).isEqualTo(task.getSourceKey());
        assertThat(message.getRunKind()).isEqualTo(GatewayMessage.RUN_KIND_DELEGATION_COMPLETION);
        assertThat(message.getText()).contains("callback", "COMPLETED", "finished");
    }

    /** 调用异常必须提交失败状态，不得遗留 RUNNING。 */
    @Test
    void shouldPersistFailedExecution() throws Exception {
        ProfileTaskRecord claimed = saveAndClaim(task("failed"));
        ProfileTaskCoordinator.TaskExecution execution =
                coordinator
                .new TaskExecution(
                        claimed.getTaskId(),
                        () -> {
                            throw new IllegalStateException("controlled failure");
                        });

        coordinator.execute(claimed, execution);

        ProfileTaskRecord stored = repository.findById("failed");
        assertThat(stored.getStatus()).isEqualTo("FAILED");
        assertThat(stored.getError()).contains("controlled failure");
        assertThat(coordinator.statuses).containsExactly("FAILED");
    }

    /** 超时必须中断调用并提交超时状态。 */
    @Test
    void shouldInterruptTimedOutExecution() throws Exception {
        ShortTimeoutCoordinator timeoutCoordinator =
                new ShortTimeoutCoordinator(repository, new AppConfig());
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            ProfileTaskRecord claimed = saveAndClaim(task("timed-out"));
            ProfileTaskCoordinator.TaskExecution execution =
                    timeoutCoordinator
                    .new TaskExecution(
                            claimed.getTaskId(),
                            () -> {
                                try {
                                    new CountDownLatch(1).await();
                                } catch (InterruptedException e) {
                                    interrupted.countDown();
                                    throw e;
                                }
                                return GatewayReply.ok("unreachable");
                            });

            timeoutCoordinator.execute(claimed, execution);

            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.findById("timed-out").getStatus()).isEqualTo("TIMED_OUT");
            assertThat(timeoutCoordinator.statuses).containsExactly("TIMED_OUT");
        } finally {
            timeoutCoordinator.close();
        }
    }

    /** 未认领任务没有执行令牌，取消仍应成功且不得触发空值删除异常。 */
    @Test
    void shouldCancelTaskWithoutRegisteredExecution() throws Exception {
        repository.save(task("ready-cancel"));

        assertThat(coordinator.cancelTask("ready-cancel")).isTrue();

        assertThat(repository.findById("ready-cancel").getStatus()).isEqualTo("CANCELLED");
    }

    /** 已启动调用即使忽略一次中断，取消也必须等待其真实退出后才返回。 */
    @Test
    void shouldWaitForRunningCallToExitBeforeCancellationReturns() throws Exception {
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch interruptIgnored = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger sideEffects = new AtomicInteger();
        RunningCallCoordinator runningCoordinator =
                new RunningCallCoordinator(
                        repository,
                        new AppConfig(),
                        callStarted,
                        interruptIgnored,
                        allowExit,
                        sideEffects);
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        try {
            repository.save(task("running-cancel"));
            runningCoordinator.dispatchSafely();
            assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> cancelled =
                    canceller.submit(() -> runningCoordinator.cancelTask("running-cancel"));
            assertThat(interruptIgnored.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.isDone()).isFalse();

            allowExit.countDown();
            assertThat(cancelled.get(5, TimeUnit.SECONDS)).isTrue();

            assertThat(repository.findById("running-cancel").getStatus()).isEqualTo("CANCELLED");
            assertThat(sideEffects).hasValue(1);
            assertThat(activeCalls(runningCoordinator)).isEmpty();
        } finally {
            allowExit.countDown();
            canceller.shutdownNow();
            runningCoordinator.close();
        }
    }

    /** worker 拒绝执行时必须释放执行令牌并将任务收敛为中断。 */
    @Test
    void shouldInterruptTaskWhenWorkerRejectsExecution() throws Exception {
        ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ExecutorService calls = Executors.newSingleThreadExecutor();
        workers.shutdownNow();
        ProfileTaskCoordinator rejectedCoordinator =
                new ProfileTaskCoordinator(
                        repository, null, null, new AppConfig(), dispatcher, workers, calls);
        try {
            repository.save(task("worker-rejected"));

            rejectedCoordinator.dispatchSafely();

            assertThat(repository.findById("worker-rejected").getStatus()).isEqualTo("INTERRUPTED");
            assertThat(activeCalls(rejectedCoordinator)).isEmpty();
        } finally {
            rejectedCoordinator.close();
        }
    }

    /** 关闭时运行中的任务必须停止调用并持久化为中断。 */
    @Test
    void shouldInterruptActiveTaskOnClose() throws Exception {
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch callInterrupted = new CountDownLatch(1);
        CloseCallCoordinator closeCoordinator =
                new CloseCallCoordinator(repository, new AppConfig(), callStarted, callInterrupted);
        repository.save(task("close-active"));
        closeCoordinator.dispatchSafely();
        assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();

        closeCoordinator.close();

        assertThat(repository.findById("close-active").getStatus()).isEqualTo("INTERRUPTED");
        assertThat(callInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(closeCoordinator.workerFinished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(closeCoordinator.statuses).isEmpty();
    }

    /** 关闭时 worker 队列中已认领但未开始的任务也必须持久化为中断。 */
    @Test
    void shouldInterruptClaimedQueuedTaskOnClose() throws Exception {
        CountDownLatch workerOccupied = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ExecutorService calls = Executors.newSingleThreadExecutor();
        workers.execute(
                () -> {
                    workerOccupied.countDown();
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(workerOccupied.await(5, TimeUnit.SECONDS)).isTrue();
        ProfileTaskCoordinator queuedCoordinator =
                new ProfileTaskCoordinator(
                        repository, null, null, new AppConfig(), dispatcher, workers, calls);
        try {
            repository.save(task("close-queued"));
            queuedCoordinator.dispatchSafely();

            queuedCoordinator.close();

            assertThat(repository.findById("close-queued").getStatus()).isEqualTo("INTERRUPTED");
        } finally {
            releaseWorker.countDown();
            queuedCoordinator.close();
        }
    }

    /** worker 尚未开始执行时取消，预注册 Future 必须阻止后续副作用。 */
    @Test
    void shouldRegisterCallBeforeCancellationCanObserveTask() throws Exception {
        CountDownLatch workerOccupied = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        workers.execute(
                () -> {
                    workerOccupied.countDown();
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(workerOccupied.await(5, TimeUnit.SECONDS)).isTrue();

        BlockingCancelRepository cancelRepository = new BlockingCancelRepository(database);
        cancelRepository.save(task("registration-race"));
        ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor();
        ExecutorService calls = Executors.newSingleThreadExecutor();
        DelayedCallCoordinator raceCoordinator =
                new DelayedCallCoordinator(
                        cancelRepository, new AppConfig(), dispatcher, workers, calls);
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        try {
            raceCoordinator.dispatchSafely();
            Future<Boolean> cancelled =
                    canceller.submit(() -> raceCoordinator.cancelTask("registration-race"));
            assertThat(cancelRepository.cancelPersisted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelRepository.findById("registration-race").getStatus())
                    .isEqualTo("CANCELLED");

            releaseWorker.countDown();
            assertThat(raceCoordinator.workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(raceCoordinator.callStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();

            cancelRepository.allowCancelReturn.countDown();
            assertThat(cancelled.get(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch workerDrained = new CountDownLatch(1);
            workers.execute(workerDrained::countDown);

            assertThat(workerDrained.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelRepository.findById("registration-race").getStatus())
                    .isEqualTo("CANCELLED");
            assertThat(raceCoordinator.sideEffects).hasValue(0);
        } finally {
            releaseWorker.countDown();
            cancelRepository.allowCancelReturn.countDown();
            canceller.shutdownNow();
            raceCoordinator.close();
        }
    }

    /** 构造最小任务。 */
    private static ProfileTaskRecord task(String id) {
        ProfileTaskRecord task = new ProfileTaskRecord();
        task.setTaskId(id);
        task.setSourceProfile("default");
        task.setTargetProfile("researcher");
        task.setSourceKey("MEMORY:room:user");
        task.setTitle(id);
        task.setPrompt("execute " + id);
        return task;
    }

    /** 保存并认领一个任务。 */
    private ProfileTaskRecord saveAndClaim(ProfileTaskRecord task) throws Exception {
        repository.save(task);
        return repository.claimNext(5);
    }

    /** 读取协调器活动调用表，验证拒绝执行后没有泄漏。 */
    private static Map<?, ?> activeCalls(ProfileTaskCoordinator value) throws Exception {
        java.lang.reflect.Field field =
                ProfileTaskCoordinator.class.getDeclaredField("activeCalls");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(value);
    }

    /** 替换外部通知，只记录提交状态。 */
    private static class RecordingCoordinator extends ProfileTaskCoordinator {
        /** 已发送状态。 */
        final List<String> statuses = new ArrayList<String>();

        /** 创建记录型协调器。 */
        RecordingCoordinator(SqliteProfileTaskRepository repository, AppConfig config) {
            super(repository, null, null, config);
        }

        /** 记录通知状态。 */
        @Override
        void notifySource(ProfileTaskRecord task, String status, String result, String error) {
            statuses.add(status);
        }
    }

    /** 将分钟级生产超时缩短为毫秒级的测试协调器。 */
    private static final class ShortTimeoutCoordinator extends RecordingCoordinator {
        /** 创建短超时协调器。 */
        ShortTimeoutCoordinator(SqliteProfileTaskRepository repository, AppConfig config) {
            super(repository, config);
        }

        /** 返回测试用短超时。 */
        @Override
        long timeoutMillis(ProfileTaskRecord task) {
            return 20L;
        }
    }

    /** 模拟忽略首次中断、稍后才真正退出的运行调用。 */
    private static final class RunningCallCoordinator extends ProfileTaskCoordinator {
        /** 调用启动信号。 */
        private final CountDownLatch callStarted;

        /** 首次中断已被忽略的信号。 */
        private final CountDownLatch interruptIgnored;

        /** 允许调用真实退出的信号。 */
        private final CountDownLatch allowExit;

        /** 模拟调用退出前的副作用次数。 */
        private final AtomicInteger sideEffects;

        /** 创建受控运行调用协调器。 */
        RunningCallCoordinator(
                SqliteProfileTaskRepository repository,
                AppConfig config,
                CountDownLatch callStarted,
                CountDownLatch interruptIgnored,
                CountDownLatch allowExit,
                AtomicInteger sideEffects) {
            super(repository, null, null, config);
            this.callStarted = callStarted;
            this.interruptIgnored = interruptIgnored;
            this.allowExit = allowExit;
            this.sideEffects = sideEffects;
        }

        /** 忽略首次中断，并由测试显式放行真实退出。 */
        @Override
        GatewayReply executeCall(ProfileTaskRecord task) throws Exception {
            callStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interruptIgnored.countDown();
            }
            allowExit.await();
            sideEffects.incrementAndGet();
            return GatewayReply.ok("late completion");
        }
    }

    /** 模拟会响应关闭中断的运行调用。 */
    private static final class CloseCallCoordinator extends ProfileTaskCoordinator {
        /** 关闭期间发出的任务通知。 */
        private final List<String> statuses = new ArrayList<String>();

        /** worker 已完成关闭清理的信号。 */
        private final CountDownLatch workerFinished = new CountDownLatch(1);

        /** 调用启动信号。 */
        private final CountDownLatch callStarted;

        /** 调用收到中断的标记。 */
        private final CountDownLatch callInterrupted;

        /** 创建关闭行为协调器。 */
        CloseCallCoordinator(
                SqliteProfileTaskRepository repository,
                AppConfig config,
                CountDownLatch callStarted,
                CountDownLatch callInterrupted) {
            super(repository, null, null, config);
            this.callStarted = callStarted;
            this.callInterrupted = callInterrupted;
        }

        /** 执行任务并标记 worker 已完成关闭清理。 */
        @Override
        void execute(ProfileTaskRecord task, TaskExecution execution) {
            try {
                super.execute(task, execution);
            } finally {
                workerFinished.countDown();
            }
        }

        /** 等待关闭中断并记录结果。 */
        @Override
        GatewayReply executeCall(ProfileTaskRecord task) throws Exception {
            callStarted.countDown();
            try {
                new CountDownLatch(1).await();
                return GatewayReply.ok("unreachable");
            } catch (InterruptedException e) {
                callInterrupted.countDown();
                throw e;
            }
        }

        /** 记录关闭期间错误发出的任务通知。 */
        @Override
        void notifySource(ProfileTaskRecord task, String status, String result, String error) {
            statuses.add(status);
        }
    }

    /** 使用受控 worker 验证注册窗口的协调器。 */
    private static final class DelayedCallCoordinator extends ProfileTaskCoordinator {
        /** 实际模型调用产生的模拟副作用次数。 */
        private final AtomicInteger sideEffects = new AtomicInteger();

        /** worker 已经进入执行方法的信号。 */
        private final CountDownLatch workerStarted = new CountDownLatch(1);

        /** 真实调用体已经启动的信号。 */
        private final CountDownLatch callStarted = new CountDownLatch(1);

        /** 创建使用受控执行器的协调器。 */
        DelayedCallCoordinator(
                SqliteProfileTaskRepository repository,
                AppConfig config,
                ScheduledExecutorService dispatcher,
                ExecutorService workers,
                ExecutorService calls) {
            super(repository, null, null, config, dispatcher, workers, calls);
        }

        /** 标记 worker 已开始处理预注册 Future。 */
        @Override
        void execute(ProfileTaskRecord task, TaskExecution execution) {
            workerStarted.countDown();
            super.execute(task, execution);
        }

        /** 记录只有真实调用开始后才会发生的副作用。 */
        @Override
        GatewayReply executeCall(ProfileTaskRecord task) {
            callStarted.countDown();
            sideEffects.incrementAndGet();
            return GatewayReply.ok("completed");
        }
    }

    /** 在数据库提交取消后阻塞返回，放大取消与 worker 启动的交叉窗口。 */
    private static final class BlockingCancelRepository extends SqliteProfileTaskRepository {
        /** 取消状态已经提交的信号。 */
        private final CountDownLatch cancelPersisted = new CountDownLatch(1);

        /** 允许取消方法返回协调器的信号。 */
        private final CountDownLatch allowCancelReturn = new CountDownLatch(1);

        /** 创建阻塞取消仓储。 */
        BlockingCancelRepository(SqliteDatabase database) {
            super(database);
        }

        /** 提交取消状态后等待测试放行。 */
        @Override
        public boolean cancel(String taskId) throws Exception {
            boolean cancelled = super.cancel(taskId);
            cancelPersisted.countDown();
            allowCancelReturn.await();
            return cancelled;
        }
    }
}
