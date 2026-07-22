package com.jimuqu.solon.claw.profile.task;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.gateway.service.ProfileRuntimeBundle;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** root/default 唯一协作任务调度器；目标 Profile 只执行消息，不持有共享队列。 */
public class ProfileTaskCoordinator implements AutoCloseable {
    /** 调度日志。 */
    private static final Logger log = LoggerFactory.getLogger(ProfileTaskCoordinator.class);

    /** 等待目标 Agent RunHandle 完成注册的最长时间。 */
    private static final long RUN_CANCEL_REGISTRATION_WAIT_MILLIS = 1000L;

    /** 等待已启动调用真实退出的最长时间。 */
    private static final long RUN_CANCEL_COMPLETION_WAIT_MILLIS = 5000L;

    /** 当前应用配置；任务并发上限支持热刷新。 */
    private final AppConfig appConfig;

    /** 共享任务仓储。 */
    private final ProfileTaskRepository repository;

    /** Profile 子运行时管理器。 */
    private final ProfileMultiplexRuntimeManager runtimeManager;

    /** default 网关，用于唤醒 default 来源会话。 */
    private final DefaultGatewayService defaultGatewayService;

    /** 单线程轮询器。 */
    private final ScheduledExecutorService dispatcher;

    /** 不同目标 Profile 的并行执行池；仓储保证同 Profile 只有一个 RUNNING。 */
    private final ExecutorService workers;

    /** 单次模型调用池，避免五个协调 worker 同时等待自己提交的任务。 */
    private final ExecutorService calls;

    /** 防止关闭后继续认领。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 当前模型调用，用于用户取消时主动中断。 */
    private final ConcurrentMap<String, TaskExecution> activeCalls =
            new ConcurrentHashMap<String, TaskExecution>();

    /** 已进入调用体的任务及其目标 Profile 运行时。 */
    private final ConcurrentMap<String, ProfileRuntimeBundle> activeRuntimes =
            new ConcurrentHashMap<String, ProfileRuntimeBundle>();

    /** 线性化任务认领注册与用户取消，消除 Future 尚未可见的窗口。 */
    private final Object executionLock = new Object();

    /** 创建 root 协作任务调度器。 */
    public ProfileTaskCoordinator(
            ProfileTaskRepository repository,
            ProfileMultiplexRuntimeManager runtimeManager,
            DefaultGatewayService defaultGatewayService,
            AppConfig appConfig) {
        this(
                repository,
                runtimeManager,
                defaultGatewayService,
                appConfig,
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> new Thread(runnable, "solonclaw-profile-task-dispatcher")),
                Executors.newCachedThreadPool(
                        runnable -> new Thread(runnable, "solonclaw-profile-task-worker")),
                Executors.newCachedThreadPool(
                        runnable -> new Thread(runnable, "solonclaw-profile-task-call")));
    }

    /** 创建可注入执行器的协调器，供并发边界测试精确控制调度顺序。 */
    ProfileTaskCoordinator(
            ProfileTaskRepository repository,
            ProfileMultiplexRuntimeManager runtimeManager,
            DefaultGatewayService defaultGatewayService,
            AppConfig appConfig,
            ScheduledExecutorService dispatcher,
            ExecutorService workers,
            ExecutorService calls) {
        this.repository = repository;
        this.runtimeManager = runtimeManager;
        this.defaultGatewayService = defaultGatewayService;
        this.appConfig = appConfig;
        this.dispatcher = dispatcher;
        this.workers = workers;
        this.calls = calls;
    }

    /** 收敛重启遗留任务并开始调度。 */
    public void start() throws Exception {
        repository.interruptRunning("Backend restarted while collaboration task was running");
        dispatcher.scheduleWithFixedDelay(this::dispatchSafely, 0L, 500L, TimeUnit.MILLISECONDS);
    }

    /** 认领当前容量内的任务。 */
    void dispatchSafely() {
        if (closed.get()) {
            return;
        }
        try {
            int concurrency = Math.max(1, appConfig.getTask().getProfileTaskMaxConcurrency());
            while (true) {
                final ProfileTaskRecord claimed;
                final TaskExecution execution;
                synchronized (executionLock) {
                    if (closed.get()) {
                        return;
                    }
                    claimed = repository.claimNext(concurrency);
                    if (claimed == null) {
                        return;
                    }
                    execution = new TaskExecution(claimed.getTaskId(), () -> executeCall(claimed));
                    activeCalls.put(claimed.getTaskId(), execution);
                }
                try {
                    workers.execute(() -> execute(claimed, execution));
                } catch (RuntimeException e) {
                    activeCalls.remove(claimed.getTaskId(), execution);
                    execution.cancel();
                    finishFailure(
                            claimed, "INTERRUPTED", "Collaboration task worker rejected execution");
                    throw e;
                }
            }
        } catch (Exception e) {
            log.warn("Profile task dispatch failed: error={}", ErrorTextSupport.safeError(e));
        }
    }

    /** 在目标 Profile 完整运行时执行一次任务，并按执行令牌落结果。 */
    void execute(ProfileTaskRecord task, TaskExecution execution) {
        try {
            synchronized (executionLock) {
                calls.execute(execution.future());
            }
            GatewayReply reply = execution.future().get(timeoutMillis(task), TimeUnit.MILLISECONDS);
            finishReply(task, reply);
        } catch (java.util.concurrent.TimeoutException e) {
            execution.cancel();
            finishFailure(task, "TIMED_OUT", "Collaboration task execution timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execution.cancel();
            finishFailure(task, "INTERRUPTED", "Collaboration task execution interrupted");
        } catch (Exception e) {
            finishFailure(task, "FAILED", ErrorTextSupport.safeError(e));
        } finally {
            if (!execution.isStarted()) {
                activeCalls.remove(task.getTaskId(), execution);
            }
        }
    }

    /** 在已注册的可取消 Future 内执行目标 Profile 调用。 */
    GatewayReply executeCall(ProfileTaskRecord task) throws Exception {
        ProfileRuntimeBundle bundle = runtimeManager.requireRuntime(task.getTargetProfile());
        activeRuntimes.put(task.getTaskId(), bundle);
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Profile task cancelled before Agent run start");
            }
            return bundle.handle(taskMessage(task));
        } finally {
            activeRuntimes.remove(task.getTaskId(), bundle);
        }
    }

    /** 返回单次协作调用的超时毫秒数。 */
    long timeoutMillis(ProfileTaskRecord task) {
        return TimeUnit.MINUTES.toMillis(Math.max(1, task.getTimeoutMinutes()));
    }

    /** 按网关回复和执行令牌提交任务终态。 */
    void finishReply(ProfileTaskRecord task, GatewayReply reply) throws Exception {
        if (reply == null || reply.isError()) {
            finishFailure(
                    task,
                    "FAILED",
                    reply == null
                            ? "Collaboration task returned no reply"
                            : StrUtil.blankToDefault(
                                    reply.getContent(), "Collaboration task failed"));
            return;
        }
        String result = reply.getContent();
        if (repository.finishAttempt(
                task.getTaskId(), task.getExecutionToken(), "COMPLETED", result, null)) {
            notifySource(task, "COMPLETED", result, null);
        }
    }

    /** 原子取消任务状态并中断当前 JVM 内的调用。 */
    public boolean cancelTask(String taskId) throws Exception {
        synchronized (executionLock) {
            TaskExecution execution = activeCalls.get(taskId);
            if (!repository.cancel(taskId)) {
                return false;
            }
            if (execution != null) {
                execution.cancel();
            }
            stopActiveProfileRun(taskId);
            if (execution != null
                    && execution.isStarted()
                    && !execution.awaitCompletion(RUN_CANCEL_COMPLETION_WAIT_MILLIS)) {
                throw new IllegalStateException(
                        "Profile task cancellation timed out before execution stopped");
            }
            if (execution != null) {
                activeCalls.remove(taskId, execution);
            }
            return true;
        }
    }

    /** 停止目标 Profile 的 Agent RunHandle，并覆盖调用体与 RunHandle 注册之间的短窗口。 */
    private void stopActiveProfileRun(String taskId) {
        ProfileRuntimeBundle bundle = activeRuntimes.get(taskId);
        if (bundle == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + RUN_CANCEL_REGISTRATION_WAIT_MILLIS;
        while (activeRuntimes.get(taskId) == bundle) {
            try {
                AgentRunStopResult result = bundle.stopRun("PROFILE_TASK:" + taskId);
                if (result != null && result.isActiveRun()) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Profile task Agent run cancellation failed: taskId={}, error={}",
                        taskId,
                        ErrorTextSupport.safeError(e));
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 保存失败并唤醒原分配会话，由分配者决定是否修改描述后重试。 */
    private void finishFailure(ProfileTaskRecord task, String status, String error) {
        try {
            if (repository.finishAttempt(
                    task.getTaskId(), task.getExecutionToken(), status, null, error)) {
                String storedStatus =
                        task.getAttemptCount() >= task.getMaxAttempts() ? "BLOCKED" : status;
                notifySource(task, storedStatus, null, error);
            }
        } catch (Exception persistenceError) {
            log.warn(
                    "Profile task failure persistence failed: taskId={}, error={}",
                    task.getTaskId(),
                    ErrorTextSupport.safeError(persistenceError));
        }
    }

    /** 构造目标 Profile 的隔离逻辑会话消息。 */
    private static GatewayMessage taskMessage(ProfileTaskRecord task) {
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.MEMORY,
                        "profile-task-" + task.getTaskId(),
                        "profile-task",
                        "[协作任务]\n任务ID: "
                                + task.getTaskId()
                                + "\n来源智能体: "
                                + task.getSourceProfile()
                                + "\n标题: "
                                + task.getTitle()
                                + "\n任务描述:\n"
                                + task.getPrompt());
        message.setSourceKeyOverride("PROFILE_TASK:" + task.getTaskId());
        return message;
    }

    /** 将任务事件作为新一轮输入唤醒原分配智能体。 */
    void notifySource(ProfileTaskRecord task, String status, String result, String error) {
        GatewayMessage message = sourceNotificationMessage(task, status, result, error);
        try {
            if ("default".equals(task.getSourceProfile())) {
                defaultGatewayService.handle(message);
            } else {
                runtimeManager.route(task.getSourceProfile(), message);
            }
        } catch (Exception e) {
            log.warn(
                    "Profile task source notification failed: taskId={}, sourceProfile={}, error={}",
                    task.getTaskId(),
                    task.getSourceProfile(),
                    ErrorTextSupport.safeError(e));
        }
    }

    /**
     * 构造协作任务结果回流消息，并明确标记为后台委派完成事件。
     *
     * @param task 已结束的协作任务。
     * @param status 最终任务状态。
     * @param result 成功结果，可为空。
     * @param error 失败原因，可为空。
     * @return 交给来源 Profile 继续分析的内部消息。
     */
    static GatewayMessage sourceNotificationMessage(
            ProfileTaskRecord task, String status, String result, String error) {
        String[] source = SourceKeySupport.split(task.getSourceKey());
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.fromName(source[0]),
                        source[1],
                        source[2],
                        "[协作任务事件]\n任务ID: "
                                + task.getTaskId()
                                + "\n执行智能体: "
                                + task.getTargetProfile()
                                + "\n状态: "
                                + status
                                + (result == null ? "" : "\n结果:\n" + result)
                                + (error == null ? "" : "\n错误:\n" + error)
                                + "\n请分析结果并决定下一步；失败时不要机械重试。");
        message.setThreadId(source[3].isEmpty() ? null : source[3]);
        message.setSourceKeyOverride(task.getSourceKey());
        message.setProfile(task.getSourceProfile());
        message.setRunKind(GatewayMessage.RUN_KIND_DELEGATION_COMPLETION);
        return message;
    }

    /** 停止认领并中断当前 JVM 内任务。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (executionLock) {
            dispatcher.shutdownNow();
            try {
                repository.interruptRunning("Backend stopped while collaboration task was running");
            } catch (Exception e) {
                log.warn(
                        "Profile task shutdown state persistence failed: error={}",
                        ErrorTextSupport.safeError(e));
            }
            workers.shutdownNow();
            for (TaskExecution execution : activeCalls.values()) {
                execution.cancel();
            }
            activeCalls.clear();
            for (Map.Entry<String, ProfileRuntimeBundle> entry : activeRuntimes.entrySet()) {
                try {
                    entry.getValue().stopRun("PROFILE_TASK:" + entry.getKey());
                } catch (RuntimeException e) {
                    log.warn(
                            "Profile task Agent run shutdown failed: taskId={}, error={}",
                            entry.getKey(),
                            ErrorTextSupport.safeError(e));
                }
            }
            calls.shutdownNow();
        }
    }

    /** 跟踪单次调用的 Future 状态与真实 callable 生命周期。 */
    final class TaskExecution {
        /** 任务标识。 */
        private final String taskId;

        /** callable 是否已经真正开始。 */
        private final AtomicBoolean started = new AtomicBoolean(false);

        /** callable 真实退出信号。 */
        private final CountDownLatch completed = new CountDownLatch(1);

        /** 可取消调用。 */
        private final FutureTask<GatewayReply> future;

        /** 创建受跟踪调用。 */
        TaskExecution(String taskId, Callable<GatewayReply> callable) {
            this.taskId = taskId;
            this.future =
                    new FutureTask<GatewayReply>(
                            () -> {
                                started.set(true);
                                try {
                                    return callable.call();
                                } finally {
                                    completed.countDown();
                                    activeCalls.remove(taskId, TaskExecution.this);
                                }
                            });
        }

        /** 返回底层 FutureTask。 */
        FutureTask<GatewayReply> future() {
            return future;
        }

        /** 中断尚未完成的调用。 */
        void cancel() {
            future.cancel(true);
        }

        /** 返回 callable 是否已经真正开始。 */
        boolean isStarted() {
            return started.get();
        }

        /** 在限定时间内等待 callable 真实退出。 */
        boolean awaitCompletion(long timeoutMillis) {
            try {
                return completed.await(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
