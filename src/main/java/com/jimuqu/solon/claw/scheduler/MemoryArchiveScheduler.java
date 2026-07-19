package com.jimuqu.solon.claw.scheduler;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.MemoryArchiveService;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.support.ExecutorShutdownSupport;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仅在 Agent 空闲时运行旧每日记忆归档的独立固定延迟调度器。 */
public class MemoryArchiveScheduler {
    /** 调度日志。 */
    private static final Logger log = LoggerFactory.getLogger(MemoryArchiveScheduler.class);

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 记忆归档服务。 */
    private final MemoryArchiveService archiveService;

    /** Agent 运行控制服务。 */
    private final AgentRunControlService agentRunControlService;

    /** 防止同一进程内重复运行。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 单线程固定延迟执行器。 */
    private ScheduledExecutorService executorService;

    /**
     * 创建记忆归档调度器。
     *
     * @param appConfig 应用配置。
     * @param archiveService 记忆归档服务。
     * @param agentRunControlService Agent 运行控制服务。
     */
    public MemoryArchiveScheduler(
            AppConfig appConfig,
            MemoryArchiveService archiveService,
            AgentRunControlService agentRunControlService) {
        this.appConfig = appConfig;
        this.archiveService = archiveService;
        this.agentRunControlService = agentRunControlService;
    }

    /** 按配置周期启动归档检查，首次检查最多延迟五分钟。 */
    public synchronized void start() {
        if (!enabled() || executorService != null) {
            return;
        }
        long intervalSeconds =
                Math.max(
                        60L,
                        (long) Math.max(1, appConfig.getMemory().getArchive().getIntervalHours())
                                * 3600L);
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                this::tickSafe, Math.min(300L, intervalSeconds), intervalSeconds, TimeUnit.SECONDS);
    }

    /** 执行一次空闲和进程内防重入门控后的归档。 */
    public void tick() throws Exception {
        if (!enabled()) {
            return;
        }
        if (agentRunControlService != null && agentRunControlService.hasRunningRuns()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            archiveService.runOnce();
        } finally {
            running.set(false);
        }
    }

    /** 隔离异常，避免固定延迟调度线程退出。 */
    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Memory archive failed: error={}", CronJobSupport.safeError(e));
        }
    }

    /** 判断归档调度是否启用。 */
    private boolean enabled() {
        return appConfig.getMemory() != null
                && appConfig.getMemory().getArchive() != null
                && appConfig.getMemory().getArchive().isEnabled();
    }

    /** 关闭调度线程。 */
    public synchronized void shutdown() {
        if (executorService != null) {
            ExecutorShutdownSupport.drain(executorService);
            executorService = null;
        }
    }
}
