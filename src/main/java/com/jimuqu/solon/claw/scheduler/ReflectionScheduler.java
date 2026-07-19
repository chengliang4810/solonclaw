package com.jimuqu.solon.claw.scheduler;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.CrossSessionReflectionService;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.support.ExecutorShutdownSupport;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仅在 Agent 空闲时触发跨会话反思的独立后台调度器。 */
public class ReflectionScheduler {
    /** 调度日志。 */
    private static final Logger log = LoggerFactory.getLogger(ReflectionScheduler.class);

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 跨会话反思服务。 */
    private final CrossSessionReflectionService reflectionService;

    /** Agent 运行控制服务。 */
    private final AgentRunControlService agentRunControlService;

    /** 防止同一进程内重复运行。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 单线程调度执行器。 */
    private ScheduledExecutorService executorService;

    /**
     * 创建反思调度器。
     *
     * @param appConfig 应用配置。
     * @param reflectionService 跨会话反思服务。
     * @param agentRunControlService Agent 运行控制服务。
     */
    public ReflectionScheduler(
            AppConfig appConfig,
            CrossSessionReflectionService reflectionService,
            AgentRunControlService agentRunControlService) {
        this.appConfig = appConfig;
        this.reflectionService = reflectionService;
        this.agentRunControlService = agentRunControlService;
    }

    /** 按固定延迟启动反思检查。 */
    public synchronized void start() {
        if (appConfig.getReflection() == null
                || !appConfig.getReflection().isEnabled()
                || executorService != null) {
            return;
        }
        long intervalSeconds =
                Math.max(
                        60L,
                        (long) Math.max(1, appConfig.getReflection().getIntervalHours()) * 3600L);
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                this::tickSafe, Math.min(300L, intervalSeconds), intervalSeconds, TimeUnit.SECONDS);
    }

    /** 执行一次空闲门控后的反思检查。 */
    public void tick() throws Exception {
        if (appConfig.getReflection() == null || !appConfig.getReflection().isEnabled()) {
            return;
        }
        if (agentRunControlService != null && agentRunControlService.hasRunningRuns()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            reflectionService.runOnce();
        } finally {
            running.set(false);
        }
    }

    /** 隔离异常，避免固定延迟调度线程退出。 */
    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Cross-session reflection failed: error={}", CronJobSupport.safeError(e));
        }
    }

    /** 关闭调度线程。 */
    public synchronized void shutdown() {
        if (executorService != null) {
            ExecutorShutdownSupport.drain(executorService);
            executorService = null;
        }
    }
}
