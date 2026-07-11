package com.jimuqu.solon.claw.scheduler;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 后台技能维护调度器，只在 Agent 空闲窗口触发 Curator。 */
@RequiredArgsConstructor
public class SkillCuratorScheduler {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(SkillCuratorScheduler.class);

    /** 注入应用配置，用于技能技能维护调度器。 */
    private final AppConfig appConfig;

    /** 注入技能维护服务，用于调用对应业务能力。 */
    private final SkillCuratorService curatorService;

    /** 注入Agent运行控制服务，用于调用对应业务能力。 */
    private final AgentRunControlService agentRunControlService;

    /** 记录技能技能维护调度器中的running。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 保存执行器服务执行组件，负责调度异步或定时任务。 */
    private ScheduledExecutorService executorService;

    /** 启动当前组件并准备运行资源。 */
    public void start() {
        if (!appConfig.getCurator().isEnabled()) {
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor();
        long tickSeconds =
                Math.max(
                        60L,
                        Math.min(
                                3600L,
                                Math.max(1, appConfig.getCurator().getIntervalHours()) * 3600L));
        executorService.scheduleWithFixedDelay(
                new Runnable() {
                    /** 执行异步任务主体。 */
                    @Override
                    public void run() {
                        tick();
                    }
                },
                60L,
                tickSeconds,
                TimeUnit.SECONDS);
    }

    /** 执行tick相关逻辑。 */
    public void tick() {
        if (!appConfig.getCurator().isEnabled()) {
            return;
        }
        if (!isIdleEnough()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            curatorService.runOnce(false);
        } catch (Exception e) {
            log.warn("[CURATOR] background run failed: error={}", CronJobSupport.safeError(e));
        } finally {
            running.set(false);
        }
    }

    /**
     * 判断是否Idle Enough。
     *
     * @return 如果Idle Enough满足条件则返回 true，否则返回 false。
     */
    private boolean isIdleEnough() {
        if (agentRunControlService != null && agentRunControlService.hasRunningRuns()) {
            return false;
        }
        double minIdleHours = Math.max(0.0D, appConfig.getCurator().getMinIdleHours());
        if (minIdleHours <= 0.0D || agentRunControlService == null) {
            return true;
        }
        long lastFinishedAt = agentRunControlService.lastRunFinishedAt();
        if (lastFinishedAt <= 0L) {
            return true;
        }
        long minIdleMillis = (long) (minIdleHours * 60.0D * 60.0D * 1000.0D);
        return System.currentTimeMillis() - lastFinishedAt >= minIdleMillis;
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }
}
