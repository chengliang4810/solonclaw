package com.jimuqu.solon.claw.profile.task;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.gateway.service.ProfileRuntimeBundle;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** root/default 唯一协作任务调度器；目标 Profile 只执行消息，不持有共享队列。 */
public class ProfileTaskCoordinator implements AutoCloseable {
    /** 调度日志。 */
    private static final Logger log = LoggerFactory.getLogger(ProfileTaskCoordinator.class);

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
    private final ExecutorService calls =
            Executors.newCachedThreadPool(
                    runnable -> new Thread(runnable, "solonclaw-profile-task-call"));

    /** 防止关闭后继续认领。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 当前模型调用，用于用户取消时主动中断。 */
    private final ConcurrentMap<String, Future<GatewayReply>> activeCalls =
            new ConcurrentHashMap<String, Future<GatewayReply>>();

    /** 创建 root 协作任务调度器。 */
    public ProfileTaskCoordinator(
            ProfileTaskRepository repository,
            ProfileMultiplexRuntimeManager runtimeManager,
            DefaultGatewayService defaultGatewayService,
            AppConfig appConfig) {
        this.repository = repository;
        this.runtimeManager = runtimeManager;
        this.defaultGatewayService = defaultGatewayService;
        this.appConfig = appConfig;
        this.dispatcher =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> new Thread(runnable, "solonclaw-profile-task-dispatcher"));
        this.workers =
                Executors.newCachedThreadPool(
                        runnable -> new Thread(runnable, "solonclaw-profile-task-worker"));
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
            ProfileTaskRecord task;
            while ((task = repository.claimNext(concurrency)) != null) {
                final ProfileTaskRecord claimed = task;
                workers.execute(() -> execute(claimed));
            }
        } catch (Exception e) {
            log.warn("Profile task dispatch failed: error={}", ErrorTextSupport.safeError(e));
        }
    }

    /** 在目标 Profile 完整运行时执行一次任务，并按执行令牌落结果。 */
    private void execute(ProfileTaskRecord task) {
        Future<GatewayReply> future = null;
        try {
            ProfileRuntimeBundle bundle = runtimeManager.requireRuntime(task.getTargetProfile());
            future = calls.submit(() -> bundle.handle(taskMessage(task)));
            activeCalls.put(task.getTaskId(), future);
            GatewayReply reply =
                    future.get(Math.max(1, task.getTimeoutMinutes()), TimeUnit.MINUTES);
            finishReply(task, reply);
        } catch (java.util.concurrent.TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            finishFailure(task, "TIMED_OUT", "Collaboration task execution timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (future != null) {
                future.cancel(true);
            }
            finishFailure(task, "INTERRUPTED", "Collaboration task execution interrupted");
        } catch (Exception e) {
            finishFailure(task, "FAILED", ErrorTextSupport.safeError(e));
        } finally {
            if (future != null) {
                activeCalls.remove(task.getTaskId(), future);
            }
        }
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

    /** 主动中断当前 JVM 内的指定任务调用；仓储 CAS 负责拒绝迟到结果。 */
    public void cancelExecution(String taskId) {
        Future<GatewayReply> future = activeCalls.remove(taskId);
        if (future != null) {
            future.cancel(true);
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

    /** 停止认领并中断当前 JVM 内任务。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        dispatcher.shutdownNow();
        workers.shutdownNow();
        calls.shutdownNow();
    }
}
