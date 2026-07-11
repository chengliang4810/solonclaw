package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SkillView;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.CronSupport;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MediaDirectiveSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.tool.runtime.CronAutoDeliveryContext;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DefaultCronScheduler 实现。 */
public class DefaultCronScheduler {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultCronScheduler.class);

    /** SILENTMARKER的统一常量值。 */
    private static final String SILENT_MARKER = "[SILENT]";

    /** EMPTYAgent响应错误的统一常量值。 */
    private static final String EMPTY_AGENT_RESPONSE_ERROR = "Agent 已完成但未生成回复（可能是模型错误、超时或配置错误）";

    /** EMPTYAgent响应输出的统一常量值。 */
    private static final String EMPTY_AGENT_RESPONSE_OUTPUT = "（未生成回复）";

    /** 最大上下文FROMCHARS的统一常量值。 */
    private static final int MAX_CONTEXT_FROM_CHARS = 8000;

    /** 定时任务DISABLEDTOOLSETS的统一常量值。 */
    private static final List<String> CRON_DISABLED_TOOLSETS =
            CronJobService.PROTECTED_CRON_DISABLED_TOOLSETS;

    /** 默认AgentINACTIVITY超时时间秒数的统一常量值。 */
    private static final int DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS = 600;

    /** AgentTIMEOUTPOLLMILLIS的统一常量值。 */
    private static final long AGENT_TIMEOUT_POLL_MILLIS = 500L;

    /** MCPWARMUP执行器的统一常量值。 */
    private static final ExecutorService MCP_WARMUP_EXECUTOR =
            BoundedExecutorFactory.fixed("cron-mcp-warmup", 1, 16);

    /** 安全上下文任务标识的统一常量值。 */
    private static final Pattern SAFE_CONTEXT_JOB_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{3,127}");

    /** 定时任务用户提示消息中的本地时间格式，保持原有 yyyy-MM-dd HH:mm:ss 展示。 */
    private static final DateTimeFormatter USER_MESSAGE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 定时任务运行时H整型的统一常量值。 */
    private static final String CRON_RUNTIME_HINT =
            "[IMPORTANT: 你正在以定时任务身份运行。"
                    + "DELIVERY: 你的最终回复会自动投递给用户；"
                    + "不要调用 send_message，也不要尝试自行投递输出。"
                    + "请把报告或结果作为最终回复输出，由调度器负责投递。"
                    + "SILENT: 如果确实没有任何新内容需要报告，请只回复 \"[SILENT]\"，"
                    + "不要附加其他内容以便抑制投递。不要把 [SILENT] 和正文混在一起。]\n\n";

    /** 注入应用配置，用于默认定时任务调度器。 */
    private final AppConfig appConfig;

    /** 保存定时任务任务仓储依赖，用于访问持久化数据。 */
    private final CronJobRepository cronJobRepository;

    /** 注入定时任务任务服务，用于调用对应业务能力。 */
    private final CronJobService cronJobService;

    /** 记录默认定时任务调度器中的对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 保存消息网关策略仓储依赖，用于访问持久化数据。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 危险命令审批服务，用于在 Cron 脚本启动前统一执行安全检查。 */
    private final DangerousCommandApprovalService dangerousCommandApprovalService;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入本地技能服务，用于调用对应业务能力。 */
    private final LocalSkillService localSkillService;

    /** 注入Agent运行控制服务，用于调用对应业务能力。 */
    private final AgentRunControlService agentRunControlService;

    /** 注入MCP运行时服务，用于调用对应业务能力。 */
    private final McpRuntimeService mcpRuntimeService;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 保存执行器服务执行组件，负责调度异步或定时任务。 */
    private ScheduledExecutorService executorService;

    /**
     * 创建默认定时任务调度器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     */
    public DefaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository) {
        this(
                appConfig,
                cronJobRepository,
                cronJobService,
                conversationOrchestrator,
                deliveryService,
                gatewayPolicyRepository,
                null,
                null,
                null,
                null);
    }

    /**
     * 创建默认定时任务调度器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     */
    public DefaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService) {
        this(
                appConfig,
                cronJobRepository,
                cronJobService,
                conversationOrchestrator,
                deliveryService,
                gatewayPolicyRepository,
                dangerousCommandApprovalService,
                null,
                null,
                null);
    }

    /**
     * 创建默认定时任务调度器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public DefaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AttachmentCacheService attachmentCacheService) {
        this(
                appConfig,
                cronJobRepository,
                cronJobService,
                conversationOrchestrator,
                deliveryService,
                gatewayPolicyRepository,
                dangerousCommandApprovalService,
                attachmentCacheService,
                null,
                null);
    }

    /**
     * 创建默认定时任务调度器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param localSkillService 本地技能服务依赖。
     */
    public DefaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AttachmentCacheService attachmentCacheService,
            LocalSkillService localSkillService) {
        this(
                appConfig,
                cronJobRepository,
                cronJobService,
                conversationOrchestrator,
                deliveryService,
                gatewayPolicyRepository,
                dangerousCommandApprovalService,
                attachmentCacheService,
                localSkillService,
                null);
    }

    /**
     * 创建默认定时任务调度器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     */
    public DefaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AttachmentCacheService attachmentCacheService,
            LocalSkillService localSkillService,
            AgentRunControlService agentRunControlService) {
        this(
                appConfig,
                cronJobRepository,
                cronJobService,
                conversationOrchestrator,
                deliveryService,
                gatewayPolicyRepository,
                dangerousCommandApprovalService,
                attachmentCacheService,
                localSkillService,
                agentRunControlService,
                null);
    }

    /**
     * 创建默认定时任务调度器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param mcpRuntimeService MCP运行时服务依赖。
     */
    public DefaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AttachmentCacheService attachmentCacheService,
            LocalSkillService localSkillService,
            AgentRunControlService agentRunControlService,
            McpRuntimeService mcpRuntimeService) {
        this(
                appConfig,
                cronJobRepository,
                cronJobService,
                conversationOrchestrator,
                deliveryService,
                gatewayPolicyRepository,
                dangerousCommandApprovalService,
                attachmentCacheService,
                localSkillService,
                agentRunControlService,
                mcpRuntimeService,
                null);
    }

    /**
     * 创建默认定时任务调度器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param sessionRepository 会话仓储依赖。
     */
    public DefaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AttachmentCacheService attachmentCacheService,
            LocalSkillService localSkillService,
            AgentRunControlService agentRunControlService,
            McpRuntimeService mcpRuntimeService,
            SessionRepository sessionRepository) {
        this.appConfig = appConfig;
        this.cronJobRepository = cronJobRepository;
        this.cronJobService = cronJobService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.deliveryService = deliveryService;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
        this.dangerousCommandApprovalService = dangerousCommandApprovalService;
        this.attachmentCacheService = attachmentCacheService;
        this.localSkillService = localSkillService;
        this.agentRunControlService = agentRunControlService;
        this.mcpRuntimeService = mcpRuntimeService;
        this.sessionRepository = sessionRepository;
    }

    /** 启动当前组件并准备运行资源。 */
    public void start() {
        if (!appConfig.getScheduler().isEnabled()) {
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                ProfileRuntimeScope.capture(this::tickSafe),
                5,
                appConfig.getScheduler().getTickSeconds(),
                TimeUnit.SECONDS);
    }

    /** 停止当前组件并释放运行状态。 */
    public void stop() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        stop();
    }

    /** 执行tick安全相关逻辑。 */
    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Cron tick failed: error={}", safeError(e));
        }
    }

    /** 执行tick相关逻辑。 */
    public void tick() throws Exception {
        File lockFile = new File(appConfig.getRuntime().getHome(), "jobs/cron.tick.lock");
        FileUtil.mkParentDirs(lockFile);
        FileOutputStream outputStream = new FileOutputStream(lockFile, true);
        FileChannel channel = outputStream.getChannel();
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            outputStream.close();
            return;
        }
        try {
            tickLocked();
        } finally {
            lock.release();
            channel.close();
            outputStream.close();
        }
    }

    /** 执行tickLocked相关逻辑。 */
    private void tickLocked() throws Exception {
        long now = System.currentTimeMillis();
        List<CronJobRecord> jobs = prepareDueJobs(cronJobRepository.listDue(now), now);
        if (jobs.isEmpty()) {
            return;
        }

        List<CronJobRecord> workdirJobs = new ArrayList<CronJobRecord>();
        List<CronJobRecord> parallelJobs = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : jobs) {
            if (hasWorkdir(job)) {
                workdirJobs.add(job);
            } else {
                parallelJobs.add(job);
            }
        }
        for (CronJobRecord job : workdirJobs) {
            executeBestEffort(job);
        }
        runParallelBySource(parallelJobs);
    }

    /**
     * 运行Parallel根据来源。
     *
     * @param jobs jobs 参数。
     */
    private void runParallelBySource(List<CronJobRecord> jobs) throws Exception {
        Map<String, List<CronJobRecord>> grouped = groupBySource(jobs);
        if (grouped.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, grouped.size()));
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (final List<CronJobRecord> sourceJobs : grouped.values()) {
            futures.add(
                    executor.submit(
                            ProfileRuntimeScope.capture(
                                    new Runnable() {
                                        /** 执行异步任务主体。 */
                                        @Override
                                        public void run() {
                                            for (CronJobRecord job : sourceJobs) {
                                                executeBestEffort(job);
                                            }
                                        }
                                    })));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();
    }

    /**
     * 执行Best Effort。
     *
     * @param job job 参数。
     */
    private void executeBestEffort(CronJobRecord job) {
        try {
            execute(job, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn(
                    "Cron job failed: jobId={}, sourceKey={}, error={}",
                    job.getJobId(),
                    job.getSourceKey(),
                    safeError(e));
        }
    }

    /**
     * 判断是否存在Workdir。
     *
     * @param job job 参数。
     * @return 如果Workdir满足条件则返回 true，否则返回 false。
     */
    private boolean hasWorkdir(CronJobRecord job) {
        return job != null && StrUtil.isNotBlank(job.getWorkdir());
    }

    /**
     * 执行prepareDueJobs相关逻辑。
     *
     * @param dueJobs dueJobs 参数。
     * @param now 当前时间戳。
     * @return 返回prepare Due Jobs结果。
     */
    private List<CronJobRecord> prepareDueJobs(List<CronJobRecord> dueJobs, long now)
            throws Exception {
        List<CronJobRecord> result = new ArrayList<CronJobRecord>();
        if (dueJobs == null || dueJobs.isEmpty()) {
            return result;
        }
        for (CronJobRecord job : dueJobs) {
            if (job == null) {
                continue;
            }
            if (recoverMissingNextRun(job, now)) {
                if (job.getNextRunAt() > now) {
                    continue;
                }
            } else if (job.getNextRunAt() <= 0L) {
                continue;
            }
            if (shouldFastForwardMissedRun(job, now)) {
                long next = CronSupport.nextRunAt(job.getCronExpr(), now);
                if (next > now) {
                    cronJobRepository.markRun(job.getJobId(), job.getLastRunAt(), next);
                    log.info(
                            "Cron job missed scheduled window and was fast-forwarded: jobId={},"
                                    + " nextRunAt={}",
                            job.getJobId(),
                            Long.valueOf(next));
                    continue;
                }
            }
            advanceRecurringBeforeRun(job, now);
            result.add(job);
        }
        return result;
    }

    /**
     * 恢复MissingNext运行。
     *
     * @param job job 参数。
     * @param now 当前时间戳。
     * @return 返回recover Missing Next运行结果。
     */
    private boolean recoverMissingNextRun(CronJobRecord job, long now) throws Exception {
        if (job.getNextRunAt() > 0L) {
            return true;
        }
        if (isRecurring(job)) {
            long next = CronSupport.nextRunAt(job.getCronExpr(), recurringRecoveryBase(job, now));
            if (next > 0L) {
                cronJobRepository.markRun(job.getJobId(), job.getLastRunAt(), next);
                job.setNextRunAt(next);
                log.info(
                        "Cron job had no next_run_at and was recovered: jobId={}, nextRunAt={}",
                        job.getJobId(),
                        Long.valueOf(next));
                return true;
            }
            return false;
        }
        Long runAt = recoverableOneShotRunAt(job, now);
        if (runAt == null) {
            return false;
        }
        cronJobRepository.markRun(job.getJobId(), job.getLastRunAt(), runAt.longValue());
        job.setNextRunAt(runAt.longValue());
        log.info(
                "Cron one-shot job had no next_run_at and was recovered: jobId={}, nextRunAt={}",
                job.getJobId(),
                runAt);
        return true;
    }

    /**
     * 执行recurring恢复基础相关逻辑。
     *
     * @param job job 参数。
     * @param now 当前时间戳。
     * @return 返回recurring Recovery Base结果。
     */
    private long recurringRecoveryBase(CronJobRecord job, long now) {
        return job.getLastRunAt() > 0L ? job.getLastRunAt() : now;
    }

    /**
     * 执行recoverableOneShot运行时间相关逻辑。
     *
     * @param job job 参数。
     * @param now 当前时间戳。
     * @return 返回recoverable One Shot运行时间结果。
     */
    private Long recoverableOneShotRunAt(CronJobRecord job, long now) {
        if (job.getLastRunAt() > 0L) {
            return null;
        }
        Long absoluteRunAt = CronSupport.absoluteRunAt(job.getCronExpr());
        long runAt =
                absoluteRunAt == null
                        ? job.getCreatedAt() + oneShotDurationMillis(job.getCronExpr())
                        : absoluteRunAt.longValue();
        return runAt >= now - 120000L ? Long.valueOf(runAt) : null;
    }

    /**
     * 执行oneShotDurationMillis相关逻辑。
     *
     * @param schedule schedule 参数。
     * @return 返回one Shot Duration Millis结果。
     */
    private long oneShotDurationMillis(String schedule) {
        Integer minutes = CronSupport.intervalMinutes(schedule);
        return Math.max(60000L, minutes == null ? 60000L : minutes.intValue() * 60000L);
    }

    /**
     * 判断是否需要Fast Forward Missed运行。
     *
     * @param job job 参数。
     * @param now 当前时间戳。
     * @return 如果Fast Forward Missed运行满足条件则返回 true，否则返回 false。
     */
    private boolean shouldFastForwardMissedRun(CronJobRecord job, long now) {
        if (!isRecurring(job)) {
            return false;
        }
        long nextRunAt = job.getNextRunAt();
        if (nextRunAt <= 0L) {
            return false;
        }
        return now - nextRunAt > missedRunGraceMillis(job.getCronExpr(), now);
    }

    /**
     * 执行advanceRecurringBefore运行相关逻辑。
     *
     * @param job job 参数。
     * @param now 当前时间戳。
     */
    private void advanceRecurringBeforeRun(CronJobRecord job, long now) {
        if (!isRecurring(job)) {
            return;
        }
        try {
            long next = CronSupport.nextRunAt(job.getCronExpr(), now);
            if (next > now && next != job.getNextRunAt()) {
                cronJobRepository.markRun(job.getJobId(), job.getLastRunAt(), next);
                job.setNextRunAt(next);
            }
        } catch (Exception e) {
            log.warn(
                    "Cron pre-run next_run_at advance failed: jobId={}, error={}",
                    job.getJobId(),
                    safeError(e));
        }
    }

    /**
     * 判断是否Recurring。
     *
     * @param job job 参数。
     * @return 如果Recurring满足条件则返回 true，否则返回 false。
     */
    private boolean isRecurring(CronJobRecord job) {
        return job != null && !CronSupport.isOneShot(job.getCronExpr());
    }

    /**
     * 执行missed运行GraceMillis相关逻辑。
     *
     * @param cronExpr 定时任务Expr参数。
     * @param now 当前时间戳。
     * @return 返回missed运行Grace Millis结果。
     */
    private long missedRunGraceMillis(String cronExpr, long now) {
        long period = CronSupport.periodMillis(cronExpr, now);
        long grace = period <= 0L ? 120000L : period / 2L;
        return Math.max(120000L, Math.min(grace, 7200000L));
    }

    /**
     * 运行Now。
     *
     * @param jobId job标识。
     */
    public void runNow(String jobId) throws Exception {
        runNow(jobId, "manual");
    }

    /**
     * 运行Now。
     *
     * @param jobId job标识。
     * @param triggerType trigger类型参数。
     */
    public void runNow(String jobId, String triggerType) throws Exception {
        CronJobRecord job = cronJobRepository.findById(jobId);
        if (job != null) {
            execute(job, System.currentTimeMillis(), StrUtil.blankToDefault(triggerType, "manual"));
        }
    }

    /**
     * 执行当前回调或工具调用。
     *
     * @param job job 参数。
     * @param now 当前时间戳。
     */
    private void execute(CronJobRecord job, long now) throws Exception {
        execute(job, now, queuedTriggerType(job));
    }

    /**
     * 执行当前回调或工具调用。
     *
     * @param job job 参数。
     * @param now 当前时间戳。
     * @param triggerType trigger类型参数。
     */
    private void execute(CronJobRecord job, long now, String triggerType) throws Exception {
        triggerType = StrUtil.blankToDefault(triggerType, "scheduled");
        job.setPendingTriggerType(null);
        long nextRunAt = CronSupport.nextRunAt(job.getCronExpr(), now);
        int completed = nextRepeatCompleted(job);
        int attempt = nextRunAttempt(job, completed);
        boolean done = job.getRepeatTimes() > 0 && completed >= job.getRepeatTimes();
        String nextStatus =
                done || CronSupport.isOneShot(job.getCronExpr()) ? "COMPLETED" : "ACTIVE";
        long storedNextRunAt = nextRunAtAfterExecution(nextStatus, nextRunAt);
        String output = "";
        String error = null;
        String deliveryError = null;
        String deliveryResultJson = null;
        String runStatus = "ok";
        try {
            GatewayReply reply;
            if (job.isNoAgent()) {
                CronScriptResult scriptResult = runScriptResult(job);
                if (!scriptResult.wakeAgent) {
                    output = silentCronOutput(job, "wakeAgent=false");
                    reply = GatewayReply.ok(SILENT_MARKER);
                } else if (StrUtil.isBlank(scriptResult.output)) {
                    output = silentCronOutput(job, "empty output");
                    reply = GatewayReply.ok(SILENT_MARKER);
                } else {
                    output = scriptResult.output;
                    reply = GatewayReply.ok(output);
                }
            } else {
                String prompt = buildPrompt(job);
                if (StrUtil.isNotBlank(job.getScript())) {
                    CronScriptResult scriptResult = null;
                    try {
                        scriptResult = runScriptResult(job);
                    } catch (Exception scriptError) {
                        if (isCronScriptSecurityBlock(scriptError)) {
                            throw scriptError;
                        }
                        prompt = withScriptError(prompt, safeError(scriptError));
                        prompt = scanAssembledPrompt(prompt, job, true);
                    }
                    if (scriptResult != null) {
                        prompt = withScriptOutput(prompt, scriptResult.output);
                        prompt = scanAssembledPrompt(prompt, job, true);
                    }
                }
                String[] parts = SourceKeySupport.split(job.getSourceKey());
                GatewayMessage synthetic =
                        new GatewayMessage(
                                PlatformType.fromName(parts[0]), parts[1], parts[2], prompt);
                synthetic.setThreadId(parts[3]);
                synthetic.setSourceKeyOverride(cronExecutionSourceKey(job));
                String override = modelOverride(job);
                if (StrUtil.isNotBlank(override)) {
                    synthetic.setModelOverride(override);
                }
                if (StrUtil.isNotBlank(job.getWorkdir())) {
                    synthetic.setWorkspaceDirOverride(job.getWorkdir());
                }
                synthetic.setEnabledToolsetsOverride(resolveCronEnabledToolsets(job));
                synthetic.setDisabledToolsetsOverride(
                        new ArrayList<String>(CRON_DISABLED_TOOLSETS));
                warmupMcpTools(job);
                reply = runScheduledWithAutoDeliveryContext(job, synthetic);
                output = reply == null ? "" : reply.getContent();
                if (reply != null && reply.isError()) {
                    error = StrUtil.blankToDefault(output, "Agent reported failure");
                    runStatus = "error";
                } else if (StrUtil.isBlank(output)) {
                    output = EMPTY_AGENT_RESPONSE_OUTPUT;
                    error = EMPTY_AGENT_RESPONSE_ERROR;
                    runStatus = "error";
                }
            }
            cronJobRepository.markRunResult(
                    job.getJobId(),
                    now,
                    storedNextRunAt,
                    runStatus,
                    error,
                    AgentRunPreview.safe(output),
                    completed,
                    nextStatus);
            if (error == null) {
                CronDeliveryReport deliveryReport = deliverBestEffort(job, reply);
                deliveryError = deliveryReport.errorSummary();
                deliveryResultJson = deliveryReport.toJson();
            } else if (reply != null && reply.isError()) {
                CronDeliveryReport deliveryReport =
                        deliverBestEffort(job, GatewayReply.error(cronFailureMessage(job, error)));
                deliveryError = deliveryReport.errorSummary();
                deliveryResultJson = deliveryReport.toJson();
            }
            recordRun(
                    job,
                    now,
                    runStatus,
                    error,
                    output,
                    deliveryError,
                    deliveryResultJson,
                    attempt,
                    triggerType);
        } catch (CronApprovalPendingException e) {
            runStatus = "pending_approval";
            error = safeError(e);
            long retryAt = Math.max(now + 60000L, job.getNextRunAt());
            cronJobRepository.markRunResult(
                    job.getJobId(),
                    now,
                    retryAt,
                    runStatus,
                    error,
                    AgentRunPreview.safe(output),
                    job.getRepeatCompleted(),
                    "PAUSED");
            cronJobService.pause(
                    job.getJobId(),
                    "waiting for approval: " + safeTarget(e.getDetectionDescription()));
            recordRun(job, now, runStatus, error, output, null, null, attempt, triggerType);
        } catch (Exception e) {
            runStatus = "error";
            error = safeError(e);
            cronJobRepository.markRunResult(
                    job.getJobId(),
                    now,
                    storedNextRunAt,
                    runStatus,
                    error,
                    AgentRunPreview.safe(output),
                    completed,
                    done ? "COMPLETED" : "ACTIVE");
            CronDeliveryReport deliveryReport = deliverErrorBestEffort(job, error);
            deliveryError = deliveryReport.errorSummary();
            deliveryResultJson = deliveryReport.toJson();
            recordRun(
                    job,
                    now,
                    runStatus,
                    error,
                    output,
                    deliveryError,
                    deliveryResultJson,
                    attempt,
                    triggerType);
            if (!isCronScriptPathBlock(e)) {
                throw e;
            }
        }
    }

    /**
     * 计算任务层面的重复完成次数；有限重复任务在重试时不能超过配置上限，避免界面显示 2/1 这类越界状态。
     *
     * @param job 当前定时任务。
     * @return 写回任务记录的重复完成次数。
     */
    private int nextRepeatCompleted(CronJobRecord job) {
        int completed = job.getRepeatCompleted() + 1;
        if (job.getRepeatTimes() <= 0) {
            return completed;
        }
        return Math.min(job.getRepeatTimes(), completed);
    }

    /**
     * 计算执行历史的尝试序号；它代表真实运行次数，不能复用被有限 repeat 夹住的任务完成数。
     *
     * @param job 当前定时任务。
     * @param fallbackAttempt 仓储不可用时使用的保守序号。
     * @return 本次 run history 的 attempt。
     */
    private int nextRunAttempt(CronJobRecord job, int fallbackAttempt) {
        try {
            List<CronJobRunRecord> runs = cronJobRepository.listRuns(job.getJobId(), 100);
            int maxAttempt = 0;
            for (CronJobRunRecord run : runs) {
                maxAttempt = Math.max(maxAttempt, run.getAttempt());
            }
            if (maxAttempt > 0) {
                return maxAttempt + 1;
            }
        } catch (Exception e) {
            log.warn(
                    "Cron run attempt lookup failed: jobId={}, error={}",
                    job.getJobId(),
                    safeError(e));
        }
        return Math.max(1, fallbackAttempt);
    }

    /**
     * 完成态任务不再保留下一次执行时间，避免Dashboard把一次性任务展示为仍有后续触发。
     *
     * @param nextStatus 本次执行后写入的任务状态。
     * @param nextRunAt 预计算的下一次执行时间。
     * @return 应写入仓储的下一次执行时间。
     */
    private long nextRunAtAfterExecution(String nextStatus, long nextRunAt) {
        if ("COMPLETED".equalsIgnoreCase(nextStatus)) {
            return 0L;
        }
        return nextRunAt;
    }

    /**
     * 执行排队Trigger类型相关逻辑。
     *
     * @param job job 参数。
     * @return 返回queued Trigger类型结果。
     */
    private String queuedTriggerType(CronJobRecord job) {
        if (job == null || StrUtil.isBlank(job.getPendingTriggerType())) {
            return "scheduled";
        }
        return job.getPendingTriggerType();
    }

    /** 构建定时任务专用执行来源键，避免复用用户主会话导致工具结果串扰。 */
    private String cronExecutionSourceKey(CronJobRecord job) {
        return "CRON:" + safeJobId(job);
    }

    /** 取出可用于内部来源键的任务 ID，异常记录缺失 ID 时给出稳定兜底值。 */
    private String safeJobId(CronJobRecord job) {
        if (job == null || StrUtil.isBlank(job.getJobId())) {
            return "unknown";
        }
        return job.getJobId().trim();
    }

    /**
     * 执行silent定时任务输出相关逻辑。
     *
     * @param job job 参数。
     * @param reason 原因参数。
     * @return 返回silent定时任务输出结果。
     */
    private String silentCronOutput(CronJobRecord job, String reason) {
        return "Cron Job: "
                + StrUtil.blankToDefault(job.getName(), job.getJobId())
                + "\nJob ID: "
                + job.getJobId()
                + "\nStatus: silent ("
                + reason
                + ")"
                + "\n"
                + SILENT_MARKER;
    }

    /**
     * 判断脚本异常是否来自执行前安全检查；此类异常不能作为普通脚本错误交给 Agent 继续处理。
     *
     * @param error 脚本执行前抛出的异常。
     * @return 安全检查已阻断时返回 true。
     */
    private boolean isCronScriptSecurityBlock(Exception error) {
        String message = error == null ? null : error.getMessage();
        return error instanceof CronApprovalPendingException
                || (StrUtil.isNotBlank(message) && message.startsWith("BLOCKED"));
    }

    /**
     * 判断是否定时任务Script路径块。
     *
     * @param error 错误参数。
     * @return 如果定时任务Script路径块满足条件则返回 true，否则返回 false。
     */
    private boolean isCronScriptPathBlock(Exception error) {
        String message = error == null ? null : error.getMessage();
        return StrUtil.isNotBlank(message)
                && (message.startsWith("Cron script not found under workspace/scripts")
                        || message.startsWith("定时任务脚本不在 workspace/scripts 下"));
    }

    /**
     * 执行warmupMCP工具相关逻辑。
     *
     * @param job job 参数。
     */
    private void warmupMcpTools(CronJobRecord job) {
        if (mcpRuntimeService == null) {
            return;
        }
        try {
            MCP_WARMUP_EXECUTOR.submit(
                    ProfileRuntimeScope.capture(
                            new Runnable() {
                                /** 执行异步任务主体。 */
                                @Override
                                public void run() {
                                    warmupMcpToolsNow(job);
                                }
                            }));
        } catch (RejectedExecutionException e) {
            log.warn(
                    "Cron job '{}' MCP initialization skipped: {}",
                    job == null
                            ? "<unknown>"
                            : StrUtil.blankToDefault(job.getName(), job.getJobId()),
                    safeError(e));
        }
    }

    /**
     * 执行warmupMCP工具Now相关逻辑。
     *
     * @param job job 参数。
     */
    private void warmupMcpToolsNow(CronJobRecord job) {
        try {
            int count = 0;
            List<ToolProvider> providers = mcpRuntimeService.resolveEnabledToolProviders();
            for (ToolProvider provider : providers) {
                if (provider == null) {
                    continue;
                }
                Collection<FunctionTool> tools = provider.getTools();
                count += tools == null ? 0 : tools.size();
            }
            if (count > 0) {
                log.info(
                        "Cron job '{}' warmed {} MCP tool(s)",
                        StrUtil.blankToDefault(job.getName(), job.getJobId()),
                        Integer.valueOf(count));
            }
        } catch (Exception e) {
            log.warn(
                    "Cron job '{}' MCP initialization failed (non-fatal): {}",
                    job == null
                            ? "<unknown>"
                            : StrUtil.blankToDefault(job.getName(), job.getJobId()),
                    safeError(e));
        }
    }

    /**
     * 执行withScript输出相关逻辑。
     *
     * @param prompt 提示词参数。
     * @param output 命令执行输出文本。
     * @return 返回with Script输出结果。
     */
    private String withScriptOutput(String prompt, String output) {
        return "## 脚本输出\n"
                + "以下数据由预运行脚本采集，请将其作为分析上下文。\n\n"
                + "```\n"
                + StrUtil.nullToEmpty(output)
                + "\n```\n\n"
                + StrUtil.nullToEmpty(prompt);
    }

    /**
     * 执行withScript错误相关逻辑。
     *
     * @param prompt 提示词参数。
     * @param error 错误参数。
     * @return 返回with Script Error结果。
     */
    private String withScriptError(String prompt, String error) {
        return "## 脚本错误\n"
                + "数据采集脚本执行失败，请在回复中告知用户。\n\n"
                + "```\n"
                + StrUtil.blankToDefault(error, "未知错误")
                + "\n```\n\n"
                + StrUtil.nullToEmpty(prompt);
    }

    /**
     * 运行Scheduled With Inactivity Timeout。
     *
     * @param job job 参数。
     * @param synthetic synthetic 参数。
     * @return 返回Scheduled With Inactivity Timeout结果。
     */
    private GatewayReply runScheduledWithInactivityTimeout(
            CronJobRecord job, GatewayMessage synthetic) throws Exception {
        int timeoutSeconds = agentInactivityTimeoutSeconds();
        if (timeoutSeconds <= 0 || agentRunControlService == null) {
            return conversationOrchestrator.runScheduled(synthetic);
        }
        ExecutorService executor =
                Executors.newSingleThreadExecutor(
                        new ThreadFactory() {
                            /**
                             * 创建Thread。
                             *
                             * @param runnable runnable 参数。
                             * @return 返回创建好的Thread。
                             */
                            @Override
                            public Thread newThread(Runnable runnable) {
                                Thread thread =
                                        new Thread(runnable, "cron-agent-run-" + job.getJobId());
                                thread.setDaemon(true);
                                return thread;
                            }
                        });
        Future<GatewayReply> future =
                executor.submit(
                        ProfileRuntimeScope.capture(
                                new java.util.concurrent.Callable<GatewayReply>() {
                                    /**
                                     * 执行回调调用并返回结果。
                                     *
                                     * @return 返回call结果。
                                     */
                                    @Override
                                    public GatewayReply call() throws Exception {
                                        return conversationOrchestrator.runScheduled(synthetic);
                                    }
                                }));
        boolean inactivityTimeout = false;
        Map<String, Object> activity = null;
        long limitMillis = timeoutSeconds * 1000L;
        try {
            while (true) {
                try {
                    return future.get(AGENT_TIMEOUT_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    logCronBestEffortFailure(job.getJobId(), "agent_poll_wait", e);
                    activity = agentRunControlService.activeRunSummary(synthetic.sourceKey());
                    if (activity == null) {
                        continue;
                    }
                    long lastActivityAt = longValue(activity.get("last_activity_at"), 0L);
                    if (lastActivityAt <= 0L) {
                        continue;
                    }
                    long idleMillis = Math.max(0L, System.currentTimeMillis() - lastActivityAt);
                    if (idleMillis >= limitMillis) {
                        inactivityTimeout = true;
                        break;
                    }
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IllegalStateException(cause);
        } finally {
            if (inactivityTimeout) {
                agentRunControlService.stop(synthetic.sourceKey());
                future.cancel(true);
            }
            executor.shutdownNow();
        }
        if (activity == null) {
            activity = agentRunControlService.activeRunSummary(synthetic.sourceKey());
        }
        long idleSeconds =
                Math.max(
                        0L,
                        longValue(
                                activity == null ? null : activity.get("seconds_since_activity"),
                                timeoutSeconds));
        String lastDesc =
                StrUtil.blankToDefault(
                        stringValue(activity == null ? null : activity.get("last_activity_desc")),
                        "未知");
        String jobName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        String message =
                "定时任务 '"
                        + jobName
                        + "' 已空闲 "
                        + idleSeconds
                        + " 秒（限制 "
                        + timeoutSeconds
                        + " 秒），最后活动："
                        + lastDesc;
        log.error("{}", message);
        throw new TimeoutException(message);
    }

    /**
     * 运行Scheduled With Auto投递上下文。
     *
     * @param job job 参数。
     * @param synthetic synthetic 参数。
     * @return 返回Scheduled With Auto投递上下文结果。
     */
    private GatewayReply runScheduledWithAutoDeliveryContext(
            CronJobRecord job, GatewayMessage synthetic) throws Exception {
        List<CronDeliveryTarget> targets = resolveDeliveryTargets(job);
        if (targets.isEmpty()) {
            return runScheduledWithInactivityTimeout(job, synthetic);
        }
        List<CronAutoDeliveryContext.Target> autoTargets =
                new ArrayList<CronAutoDeliveryContext.Target>();
        for (CronDeliveryTarget target : targets) {
            autoTargets.add(
                    new CronAutoDeliveryContext.Target(
                            target.platform, target.chatId, target.threadId));
        }
        CronAutoDeliveryContext.setAll(autoTargets);
        try {
            return runScheduledWithInactivityTimeout(job, synthetic);
        } finally {
            CronAutoDeliveryContext.clear();
        }
    }

    /**
     * 执行AgentInactivityTimeoutSeconds相关逻辑。
     *
     * @return 返回Agent Inactivity Timeout Seconds结果。
     */
    private int agentInactivityTimeoutSeconds() {
        String envValue =
                StrUtil.trim(ProfileRuntimeScope.environmentValue("SOLONCLAW_CRON_TIMEOUT"));
        if (StrUtil.isNotBlank(envValue)) {
            try {
                int value = (int) Double.parseDouble(envValue);
                return value >= 0 ? value : DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS;
            } catch (Exception e) {
                log.warn(
                        "Invalid SOLONCLAW_CRON_TIMEOUT={}; using default {}s",
                        envValue,
                        DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS);
                return DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS;
            }
        }
        int value =
                appConfig == null || appConfig.getScheduler() == null
                        ? DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS
                        : appConfig.getScheduler().getInactivityTimeoutSeconds();
        return value >= 0 ? value : DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS;
    }

    /**
     * 将输入对象转换为长整型数值。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回long Value结果。
     */
    private long longValue(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value结果。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 投递Best Effort。
     *
     * @param job job 参数。
     * @param reply 回复参数。
     * @return 返回Best Effort结果。
     */
    private CronDeliveryReport deliverBestEffort(CronJobRecord job, GatewayReply reply) {
        try {
            return deliver(job, reply);
        } catch (Exception e) {
            String error = safeError(e);
            log.warn("Cron delivery failed: jobId={}, error={}", job.getJobId(), error);
            markDeliveryErrorBestEffort(job.getJobId(), error);
            return CronDeliveryReport.failed(error);
        }
    }

    /**
     * 执行deliver相关逻辑。
     *
     * @param job job 参数。
     * @param reply 回复参数。
     * @return 返回deliver结果。
     */
    private CronDeliveryReport deliver(CronJobRecord job, GatewayReply reply) throws Exception {
        if (reply == null || StrUtil.isBlank(reply.getContent())) {
            return CronDeliveryReport.skipped("empty");
        }
        if (isSilent(reply.getContent())) {
            return CronDeliveryReport.skipped("silent");
        }
        List<CronDeliveryTarget> targets = resolveDeliveryTargets(job);
        if (targets.isEmpty()) {
            String deliver =
                    Utils.isNotEmpty(job.getDeliverPlatform()) ? job.getDeliverPlatform() : "local";
            if (!"local".equalsIgnoreCase(deliver.trim())) {
                String error = "no delivery target resolved for deliver=" + deliver;
                cronJobRepository.markDeliveryError(job.getJobId(), error);
                return CronDeliveryReport.failed(error);
            }
            return CronDeliveryReport.skipped("local");
        }
        CronDeliveryPayload payload = parseDeliveryPayload(formatDelivery(job, reply.getContent()));
        CronDeliveryReport report = new CronDeliveryReport();
        for (CronDeliveryTarget target : targets) {
            CronResolvedMedia resolvedMedia =
                    resolveMediaAttachments(target.platform, payload.media);
            DeliveryRequest request = new DeliveryRequest();
            request.setPlatform(target.platform);
            request.setChatId(target.chatId);
            request.setThreadId(target.threadId);
            request.setText(removeResolvedMediaTags(payload.text, resolvedMedia.resolved));
            request.setAttachments(resolvedMedia.attachments);
            try {
                if (deliverDashboardMemoryOrigin(target, request)) {
                    // Web 控制台来源没有外部渠道适配器，直接写入会话历史作为可恢复回投。
                } else {
                    deliveryService.deliver(request);
                }
                report.addOk(
                        target,
                        request.getAttachments() == null ? 0 : request.getAttachments().size());
            } catch (Exception e) {
                String error = safeError(e);
                report.addError(
                        target,
                        request.getAttachments() == null ? 0 : request.getAttachments().size(),
                        error);
                log.warn(
                        "Cron delivery target failed: jobId={}, platform={}, chatId={},"
                                + " threadId={}, error={}",
                        job.getJobId(),
                        target.platform,
                        safeTarget(target.chatId),
                        safeTarget(target.threadId),
                        error);
            }
        }
        if (report.hasErrors()) {
            cronJobRepository.markDeliveryError(job.getJobId(), report.errorSummary());
        }
        return report;
    }

    /**
     * 将 Web 控制台来源的定时任务结果写回会话或保留在定时任务历史，避免内部 MEMORY 来源走外部渠道适配器。
     *
     * @param target 投递目标。
     * @param request 投递请求。
     * @return 如果已完成内部 Web 回投则返回 true。
     */
    private boolean deliverDashboardMemoryOrigin(CronDeliveryTarget target, DeliveryRequest request)
            throws Exception {
        if (target == null
                || target.platform != PlatformType.MEMORY
                || !"dashboard".equalsIgnoreCase(StrUtil.nullToEmpty(target.chatId))) {
            return false;
        }
        if (StrUtil.isBlank(target.threadId)) {
            // 控制台本地投递没有可写入的会话线程，运行输出由定时任务历史承担恢复与审计职责。
            return true;
        }
        if (sessionRepository == null) {
            throw new IllegalStateException("Dashboard session repository is not configured");
        }
        SessionRecord session = sessionRepository.findById(target.threadId);
        if (session == null) {
            throw new IllegalStateException("Dashboard session not found: " + target.threadId);
        }
        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        String text = StrUtil.nullToEmpty(request == null ? null : request.getText()).trim();
        if (StrUtil.isBlank(text)) {
            return true;
        }
        if (messages.isEmpty()
                || !StrUtil.equals(
                        StrUtil.nullToEmpty(messages.get(messages.size() - 1).getContent()).trim(),
                        text)) {
            messages.add(ChatMessage.ofAssistant(text));
            session.setNdjson(MessageSupport.toNdjson(messages));
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);
        }
        return true;
    }

    /**
     * 解析投递Payload。
     *
     * @param content 待处理内容。
     * @return 返回解析后的投递Payload。
     */
    private CronDeliveryPayload parseDeliveryPayload(String content) {
        String value = StrUtil.nullToEmpty(content);
        boolean voice = value.contains("[[audio_as_voice]]");
        value = value.replace("[[audio_as_voice]]", "");
        List<CronMediaRef> media = new ArrayList<CronMediaRef>();
        for (MediaDirectiveSupport.MediaDirective directive : MediaDirectiveSupport.parse(value)) {
            media.add(new CronMediaRef(directive.getToken(), directive.getPath(), voice));
        }
        String text = value.replaceAll("\\n{3,}", "\n\n").trim();
        return new CronDeliveryPayload(text, media);
    }

    /**
     * 解析媒体附件。
     *
     * @param platform 平台参数。
     * @param mediaRefs 媒体Refs参数。
     * @return 返回解析后的媒体附件。
     */
    private CronResolvedMedia resolveMediaAttachments(
            PlatformType platform, List<CronMediaRef> mediaRefs) {
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        List<CronMediaRef> resolved = new ArrayList<CronMediaRef>();
        if (attachmentCacheService == null || mediaRefs == null || mediaRefs.isEmpty()) {
            return new CronResolvedMedia(attachments, resolved);
        }
        for (CronMediaRef media : mediaRefs) {
            if (StrUtil.isBlank(media.path)) {
                continue;
            }
            File file = FileUtil.file(media.path);
            if (!file.isFile()) {
                log.warn("Cron MEDIA file not found: {}", media.path);
                continue;
            }
            attachments.add(
                    attachmentCacheService.fromLocalOrGeneratedFile(
                            platform, file, media.voice ? "voice" : null, false, null));
            resolved.add(media);
        }
        return new CronResolvedMedia(attachments, resolved);
    }

    /**
     * 移除Resolved媒体Tags。
     *
     * @param text 待处理文本。
     * @param resolved resolved 参数。
     * @return 返回Resolved媒体Tags结果。
     */
    private String removeResolvedMediaTags(String text, List<CronMediaRef> resolved) {
        String cleaned = StrUtil.nullToEmpty(text);
        if (resolved != null) {
            for (CronMediaRef media : resolved) {
                if (StrUtil.isNotBlank(media.token)) {
                    cleaned = cleaned.replace(media.token, "");
                }
            }
        }
        return cleaned.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 判断是否Silent。
     *
     * @param content 待处理内容。
     * @return 如果Silent满足条件则返回 true，否则返回 false。
     */
    private boolean isSilent(String content) {
        return StrUtil.isNotBlank(content)
                && content.trim().toUpperCase(java.util.Locale.ROOT).startsWith(SILENT_MARKER);
    }

    /**
     * 解析投递Targets。
     *
     * @param job job 参数。
     * @return 返回解析后的投递Targets。
     */
    private List<CronDeliveryTarget> resolveDeliveryTargets(CronJobRecord job) {
        String deliver =
                Utils.isNotEmpty(job.getDeliverPlatform()) ? job.getDeliverPlatform() : "local";
        List<CronDeliveryTarget> targets = new ArrayList<CronDeliveryTarget>();
        Set<String> seen = new LinkedHashSet<String>();
        String[] parts = deliver.split(",");
        for (int i = 0; i < parts.length; i++) {
            CronDeliveryTarget target = resolveSingleDeliveryTarget(job, parts[i]);
            if (target == null) {
                continue;
            }
            String key =
                    target.platform.name()
                            + ":"
                            + StrUtil.nullToEmpty(target.chatId)
                            + ":"
                            + StrUtil.nullToEmpty(target.threadId);
            if (seen.add(key)) {
                targets.add(target);
            }
        }
        return targets;
    }

    /**
     * 解析Single投递Target。
     *
     * @param job job 参数。
     * @param rawTarget 原始Target参数。
     * @return 返回解析后的Single投递Target。
     */
    private CronDeliveryTarget resolveSingleDeliveryTarget(CronJobRecord job, String rawTarget) {
        String target = StrUtil.blankToDefault(rawTarget, "local").trim();
        if ("local".equalsIgnoreCase(target)) {
            return null;
        }

        CronDeliveryTarget origin = originTarget(job);
        if ("origin".equalsIgnoreCase(target)) {
            if (origin != null) {
                return origin;
            }
            return originFallbackHomeTarget(job);
        }

        int firstColon = target.indexOf(':');
        if (firstColon > 0) {
            String platformName = target.substring(0, firstColon);
            PlatformType platform = PlatformType.fromName(platformName);
            if (platform == null) {
                log.warn(
                        "Cron deliver skipped because platform is unknown: jobId={}, target={}",
                        job.getJobId(),
                        target);
                return null;
            }
            String rest = target.substring(firstColon + 1);
            String chatId = rest;
            String threadId = null;
            int secondColon = rest.indexOf(':');
            if (secondColon >= 0) {
                chatId = rest.substring(0, secondColon);
                threadId = rest.substring(secondColon + 1);
            }
            if (StrUtil.isBlank(chatId)) {
                return homeTarget(platform);
            }
            return new CronDeliveryTarget(
                    platform, chatId.trim(), CronJobSupport.normalizeBlank(threadId));
        }

        PlatformType platform = PlatformType.fromName(target);
        if (platform == null) {
            log.warn(
                    "Cron deliver skipped because platform is unknown: jobId={}, platform={}",
                    job.getJobId(),
                    target);
            return null;
        }
        if (origin != null && origin.platform == platform) {
            return origin;
        }
        return homeTarget(platform);
    }

    /**
     * 执行originTarget相关逻辑。
     *
     * @param job job 参数。
     * @return 返回origin Target结果。
     */
    private CronDeliveryTarget originTarget(CronJobRecord job) {
        CronDeliveryTarget explicit = targetFromOriginJson(job.getOriginJson());
        if (explicit != null) {
            return explicit;
        }
        if (StrUtil.isNotBlank(job.getDeliverChatId())) {
            PlatformType platform = deliverPlatform(job);
            if (platform != null) {
                return new CronDeliveryTarget(
                        platform,
                        job.getDeliverChatId(),
                        CronJobSupport.normalizeBlank(job.getDeliverThreadId()));
            }
        }
        String[] parts = SourceKeySupport.split(job.getSourceKey());
        PlatformType platform = PlatformType.fromName(parts[0]);
        if (platform == null || StrUtil.isBlank(parts[1])) {
            return null;
        }
        return new CronDeliveryTarget(
                platform,
                parts[1],
                CronJobSupport.normalizeBlank(
                        StrUtil.blankToDefault(job.getDeliverThreadId(), parts[3])));
    }

    /**
     * 执行targetFromOriginJSON相关逻辑。
     *
     * @param originJson originJSON参数。
     * @return 返回target From Origin JSON结果。
     */
    private CronDeliveryTarget targetFromOriginJson(String originJson) {
        if (StrUtil.isBlank(originJson)) {
            return null;
        }
        Object data;
        try {
            data = ONode.ofJson(originJson).toData();
        } catch (Exception e) {
            log.warn(
                    "Cron origin parse failed: origin={}, error={}",
                    safeText(originJson),
                    safeError(e));
            return null;
        }
        if (!(data instanceof Map)) {
            return null;
        }
        Map<?, ?> origin = (Map<?, ?>) data;
        PlatformType platform =
                PlatformType.fromName(
                        CronJobSupport.firstString(origin, "platform", "channel", "source"));
        String chatId =
                CronJobSupport.firstString(
                        origin, "chat_id", "chatId", "conversation_id", "conversationId");
        String threadId =
                CronJobSupport.firstString(origin, "thread_id", "threadId", "topic_id", "topicId");
        if (platform == null || StrUtil.isBlank(chatId)) {
            return null;
        }
        if (platform == PlatformType.MEMORY
                && "dashboard".equalsIgnoreCase(chatId)
                && StrUtil.isBlank(threadId)) {
            threadId =
                    CronJobSupport.firstString(
                            origin, "user_id", "userId", "session_id", "sessionId");
        }
        return new CronDeliveryTarget(
                platform, chatId.trim(), CronJobSupport.normalizeBlank(threadId));
    }

    /**
     * 执行主渠道Target相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回主渠道Target结果。
     */
    private CronDeliveryTarget homeTarget(PlatformType platform) {
        if (platform == null) {
            return null;
        }
        HomeChannelRecord home;
        try {
            home = gatewayPolicyRepository.getHomeChannel(platform);
        } catch (Exception e) {
            log.warn(
                    "Cron deliver home channel lookup failed: platform={}, error={}",
                    platform,
                    safeError(e));
            return null;
        }
        if (home == null || StrUtil.isBlank(home.getChatId())) {
            return null;
        }
        return new CronDeliveryTarget(
                platform, home.getChatId(), CronJobSupport.normalizeBlank(home.getThreadId()));
    }

    /**
     * 执行origin兜底主渠道Target相关逻辑。
     *
     * @param job job 参数。
     * @return 返回origin兜底主渠道Target结果。
     */
    private CronDeliveryTarget originFallbackHomeTarget(CronJobRecord job) {
        PlatformType source = sourcePlatform(job);
        CronDeliveryTarget target = homeTarget(source);
        if (target != null) {
            return target;
        }
        for (PlatformType platform : PlatformType.values()) {
            if (platform == source || platform == PlatformType.MEMORY) {
                continue;
            }
            target = homeTarget(platform);
            if (target != null) {
                log.info(
                        "Cron job has deliver=origin but no origin/source chat; falling back to {}"
                                + " home channel: jobId={}",
                        platform,
                        job.getJobId());
                return target;
            }
        }
        return null;
    }

    /**
     * 执行来源平台相关逻辑。
     *
     * @param job job 参数。
     * @return 返回来源平台结果。
     */
    private PlatformType sourcePlatform(CronJobRecord job) {
        String[] parts = SourceKeySupport.split(job.getSourceKey());
        return PlatformType.fromName(parts[0]);
    }

    /**
     * 投递平台。
     *
     * @param job job 参数。
     * @return 返回平台结果。
     */
    private PlatformType deliverPlatform(CronJobRecord job) {
        PlatformType platform = PlatformType.fromName(job.getDeliverPlatform());
        if (platform != null) {
            return platform;
        }
        return sourcePlatform(job);
    }

    /**
     * 构建提示词。
     *
     * @param job job 参数。
     * @return 返回创建好的提示词。
     */
    private String buildPrompt(CronJobRecord job) throws Exception {
        StringBuilder prompt = new StringBuilder(CRON_RUNTIME_HINT);
        String rawPrompt = StrUtil.nullToEmpty(job.getPrompt());
        Map<String, Object> view = cronJobService.toView(job);
        List<String> skills =
                view.containsKey("skills")
                        ? (List<String>) view.get("skills")
                        : new ArrayList<String>();
        boolean hasTrustedRuntimeContent = !skills.isEmpty();
        if (!skills.isEmpty()) {
            prompt.append(loadSkillPromptParts(job, skills));
        }
        Object contextFrom = view.get("context_from");
        if (contextFrom instanceof Iterable) {
            for (Object ref : (Iterable<?>) contextFrom) {
                String upstreamJobId = safeContextJobId(ref);
                if (upstreamJobId == null) {
                    continue;
                }
                CronJobRecord upstream = cronJobRepository.findById(upstreamJobId);
                if (upstream != null && StrUtil.isNotBlank(upstream.getLastOutput())) {
                    hasTrustedRuntimeContent = true;
                    prompt.append("上游任务 ")
                            .append(upstream.getJobId())
                            .append(" 最近输出：\n")
                            .append(truncateContextFromOutput(upstream.getLastOutput()))
                            .append("\n\n");
                }
            }
        }
        prompt.append(rawPrompt);
        return scanAssembledPrompt(prompt.toString(), job, hasTrustedRuntimeContent);
    }

    /**
     * 执行scanAssembled提示词相关逻辑。
     *
     * @param prompt 提示词参数。
     * @param job job 参数。
     * @param hasTrustedRuntimeContent 是否已混入技能、脚本输出或上游任务输出。
     * @return 返回scan Assembled提示词结果。
     */
    private String scanAssembledPrompt(
            String prompt, CronJobRecord job, boolean hasTrustedRuntimeContent) {
        if (cronJobService == null || StrUtil.isBlank(prompt)) {
            return prompt;
        }
        try {
            if (hasTrustedRuntimeContent) {
                // 原始用户输入始终严格校验，宽松规则只用于混入的运行期资料。
                cronJobService.scanPrompt(job == null ? null : job.getPrompt());
                return cronJobService.scanTrustedAssembledPrompt(prompt);
            }
            cronJobService.scanPrompt(prompt);
            return prompt;
        } catch (IllegalStateException e) {
            String jobLabel =
                    job == null
                            ? "<unknown>"
                            : StrUtil.blankToDefault(job.getName(), job.getJobId());
            String reason =
                    safeText(
                            StrUtil.blankToDefault(
                                    e.getMessage(), "cron prompt injection scanner"));
            log.warn("Cron job '{}' prompt scanner warning: {}", jobLabel, reason);
            return SecretRedactor.stripDisplayControls(prompt);
        }
    }

    /**
     * 生成安全展示用的上下文任务标识。
     *
     * @param ref ref 参数。
     * @return 返回safe上下文任务标识。
     */
    private String safeContextJobId(Object ref) {
        if (ref == null) {
            return null;
        }
        String value = String.valueOf(ref).trim();
        if (StrUtil.isBlank(value) || !SAFE_CONTEXT_JOB_ID.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    /**
     * 加载技能提示词Parts。
     *
     * @param job job 参数。
     * @param skills 技能参数。
     * @return 返回技能提示词Parts结果。
     */
    private String loadSkillPromptParts(CronJobRecord job, List<String> skills) {
        if (localSkillService == null) {
            return "请先加载并遵循这些技能：" + skills + "\n\n";
        }
        StringBuilder parts = new StringBuilder();
        List<String> skipped = new ArrayList<String>();
        for (String skill : skills) {
            if (StrUtil.isBlank(skill)) {
                continue;
            }
            try {
                SkillView view = localSkillService.viewSkill(skill.trim(), null);
                if (parts.length() > 0) {
                    parts.append("\n\n");
                }
                parts.append("[IMPORTANT: The user has invoked the \"")
                        .append(skill.trim())
                        .append("\" skill for this scheduled job. Follow its instructions.]\n\n")
                        .append(StrUtil.nullToEmpty(view.getContent()).trim());
                bumpSkillLoad(skill.trim());
            } catch (Exception e) {
                skipped.add(skill.trim());
                log.warn(
                        "Cron job '{}' referenced missing skill '{}': {}",
                        StrUtil.blankToDefault(job.getName(), job.getJobId()),
                        skill,
                        safeError(e));
            }
        }
        if (!skipped.isEmpty()) {
            parts.insert(
                    0,
                    "[IMPORTANT: The following skill(s) were listed for this scheduled job but"
                            + " could not be found and were skipped: "
                            + joinNames(skipped)
                            + ". Start your response with a brief notice so the user is"
                            + " aware.]\n\n");
        }
        if (parts.length() == 0) {
            return "";
        }
        return parts.append("\n\n").toString();
    }

    /**
     * 执行bump技能Load相关逻辑。
     *
     * @param skill 技能参数。
     */
    private void bumpSkillLoad(String skill) {
        try {
            localSkillService.bumpUsage(skill, "load");
        } catch (Exception e) {
            log.debug(
                    "Cron job failed to bump skill usage for '{}': error={}",
                    safeText(skill),
                    safeError(e));
        }
    }

    /**
     * 执行joinNames相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回join Names结果。
     */
    private String joinNames(List<String> values) {
        List<String> names = new ArrayList<String>();
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            names.add(value.trim());
        }
        return String.join(", ", names);
    }

    /**
     * 执行truncate上下文From输出相关逻辑。
     *
     * @param output 命令执行输出文本。
     * @return 返回truncate上下文From输出结果。
     */
    private String truncateContextFromOutput(String output) {
        if (output == null || output.length() <= MAX_CONTEXT_FROM_CHARS) {
            return output;
        }
        return output.substring(0, MAX_CONTEXT_FROM_CHARS) + "\n\n[... output truncated ...]";
    }

    /**
     * 执行模型Override相关逻辑。
     *
     * @param job job 参数。
     * @return 返回模型Override结果。
     */
    private String modelOverride(CronJobRecord job) {
        if (job == null || StrUtil.isBlank(job.getModel())) {
            return null;
        }
        StringBuilder override = new StringBuilder();
        if (StrUtil.isNotBlank(job.getProvider())) {
            override.append(job.getProvider().trim()).append(':');
        }
        override.append(job.getModel().trim());
        if (StrUtil.isNotBlank(job.getBaseUrl())) {
            override.append(':').append(job.getBaseUrl().trim());
        }
        return override.toString();
    }

    /**
     * 执行启用状态Toolsets相关逻辑。
     *
     * @param job job 参数。
     * @return 返回enabled Toolsets结果。
     */
    private List<String> enabledToolsets(CronJobRecord job) {
        List<String> result = new ArrayList<String>();
        if (job == null || StrUtil.isBlank(job.getEnabledToolsetsJson())) {
            return result;
        }
        try {
            Object data = ONode.ofJson(job.getEnabledToolsetsJson()).toData();
            if (data instanceof Iterable) {
                for (Object item : (Iterable<?>) data) {
                    String value = item == null ? "" : String.valueOf(item).trim();
                    if (StrUtil.isNotBlank(value) && !result.contains(value)) {
                        result.add(value);
                    }
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Cron enabled_toolsets parse failed: jobId={}, error={}",
                    job.getJobId(),
                    safeError(e));
        }
        return result;
    }

    /**
     * 解析定时任务启用 Toolsets。
     *
     * @param job job 参数。
     * @return 返回解析后的定时任务启用 Toolsets。
     */
    private List<String> resolveCronEnabledToolsets(CronJobRecord job) {
        List<String> perJob = enabledToolsets(job);
        if (!perJob.isEmpty()) {
            return CronJobService.filterProtectedCronToolsets(perJob);
        }
        if (appConfig == null || appConfig.getScheduler() == null) {
            return perJob;
        }
        List<String> fallback = appConfig.getScheduler().getEnabledToolsets();
        if (fallback == null || fallback.isEmpty()) {
            return perJob;
        }
        return CronJobService.filterProtectedCronToolsets(fallback);
    }

    /**
     * 运行Script。
     *
     * @param job job 参数。
     * @return 返回Script结果。
     */
    private String runScript(CronJobRecord job) throws Exception {
        return runScriptResult(job).output;
    }

    /**
     * 运行Script结果。
     *
     * @param job job 参数。
     * @return 返回Script结果。
     */
    private CronScriptResult runScriptResult(CronJobRecord job) throws Exception {
        File scriptsDir =
                FileUtil.file(appConfig.getRuntime().getHome(), "scripts").getCanonicalFile();
        File requested = new File(job.getScript());
        File script =
                (requested.isAbsolute() ? requested : new File(scriptsDir, job.getScript()))
                        .getCanonicalFile();
        if (!CronJobSupport.isUnderDirectory(scriptsDir, script)
                || !script.exists()
                || !script.isFile()) {
            throw new IllegalStateException(
                    "定时任务脚本不在 workspace/scripts 下或文件不存在：" + job.getScript());
        }
        String name = script.getName().toLowerCase();
        String scriptContent = FileUtil.readString(script, StandardCharsets.UTF_8);
        String ruleToolName =
                name.endsWith(".sh") || name.endsWith(".bash")
                        ? ToolNameConstants.EXECUTE_SHELL
                        : ToolNameConstants.EXECUTE_PYTHON;
        guardCronScript(job, scriptContent, ruleToolName);
        List<String> command = new ArrayList<String>();
        if (name.endsWith(".sh") || name.endsWith(".bash")) {
            command.add("bash");
            command.add(bashScriptPath(script));
        } else {
            command.add(defaultPythonCommand());
            command.add(script.getAbsolutePath());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (StrUtil.isNotBlank(job.getWorkdir())) {
            builder.directory(new File(job.getWorkdir()));
        }
        builder.redirectErrorStream(true);
        ProfileRuntimeScope.replaceProcessEnvironment(builder.environment());
        SubprocessEnvironmentSanitizer.sanitize(builder.environment(), appConfig);
        Process process = builder.start();
        byte[] data = readAll(process.getInputStream());
        int timeoutSeconds = scriptTimeoutSeconds();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("定时任务脚本执行超时：" + timeoutSeconds + " 秒");
        }
        String output = new String(data, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("定时任务脚本退出码 " + process.exitValue() + "，输出：" + output);
        }
        return new CronScriptResult(output, parseWakeAgent(job.getJobId(), output));
    }

    /**
     * 执行scriptTimeoutSeconds相关逻辑。
     *
     * @return 返回script Timeout Seconds结果。
     */
    private int scriptTimeoutSeconds() {
        String envValue =
                StrUtil.trim(ProfileRuntimeScope.environmentValue("SOLONCLAW_CRON_SCRIPT_TIMEOUT"));
        if (StrUtil.isNotBlank(envValue)) {
            try {
                int value = (int) Double.parseDouble(envValue);
                if (value > 0) {
                    return value;
                }
            } catch (Exception e) {
                log.warn(
                        "Invalid SOLONCLAW_CRON_SCRIPT_TIMEOUT={}; using config/default", envValue);
            }
        }
        int value =
                appConfig == null || appConfig.getScheduler() == null
                        ? 120
                        : appConfig.getScheduler().getScriptTimeoutSeconds();
        return value > 0 ? value : 120;
    }

    /**
     * 解析Wake Agent。
     *
     * @param output 命令执行输出文本。
     * @return 返回解析后的Wake Agent。
     */
    private boolean parseWakeAgent(String jobId, String output) {
        if (StrUtil.isBlank(output)) {
            return true;
        }
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = StrUtil.trim(lines[i]);
            if (StrUtil.isBlank(line)) {
                continue;
            }
            try {
                Object data = ONode.ofJson(line).toData();
                if (data instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) data;
                    if (map.containsKey("wakeAgent")) {
                        return !Boolean.FALSE.equals(map.get("wakeAgent"));
                    }
                }
            } catch (Exception e) {
                logCronBestEffortFailure(jobId, "wake_agent_parse", e);
                return true;
            }
            return true;
        }
        return true;
    }

    /**
     * 投递Error Best Effort。
     *
     * @param job job 参数。
     * @param error 错误参数。
     * @return 返回Error Best Effort结果。
     */
    private CronDeliveryReport deliverErrorBestEffort(CronJobRecord job, String error) {
        if (!job.isNoAgent()) {
            return CronDeliveryReport.skipped("agent_error");
        }
        try {
            return deliver(job, GatewayReply.error(noAgentScriptFailureMessage(job, error)));
        } catch (Exception e) {
            String deliveryError = safeError(e);
            markDeliveryErrorBestEffort(job.getJobId(), deliveryError);
            return CronDeliveryReport.failed(deliveryError);
        }
    }

    /**
     * 执行阻断提示词Failure消息相关逻辑。
     *
     * @param job job 参数。
     * @param error 错误参数。
     * @return 返回blocked提示词Failure消息结果。
     */
    private String blockedPromptFailureMessage(CronJobRecord job, String error) {
        String taskName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        String time = USER_MESSAGE_TIME_FORMATTER.format(LocalDateTime.now());
        return "# 定时任务："
                + safeText(taskName)
                + "\n\n"
                + "**任务 ID：** "
                + job.getJobId()
                + "\n"
                + "**运行时间：** "
                + time
                + "\n"
                + "**状态：** BLOCKED\n\n"
                + "组装后的提示词（包含已加载的技能内容和脚本上下文）命中了定时任务注入扫描器，因此本次未运行 Agent。\n\n"
                + "**扫描结果：** "
                + safeText(StrUtil.blankToDefault(error, "未知扫描结果"))
                + "\n\n"
                + "恢复任务前，请先审查此任务关联的技能内容或脚本输出。";
    }

    /**
     * 执行noAgentScriptFailure消息相关逻辑。
     *
     * @param job job 参数。
     * @param error 错误参数。
     * @return 返回no Agent Script Failure消息结果。
     */
    private String noAgentScriptFailureMessage(CronJobRecord job, String error) {
        String taskName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        String message = safeText(StrUtil.blankToDefault(error, "未知错误"));
        String time = USER_MESSAGE_TIME_FORMATTER.format(LocalDateTime.now());
        return "⚠ 定时任务监控 '" + safeText(taskName) + "' 脚本执行失败\n\n" + message + "\n\n时间：" + time;
    }

    /**
     * 执行定时任务Failure消息相关逻辑。
     *
     * @param job job 参数。
     * @param error 错误参数。
     * @return 返回定时任务Failure消息结果。
     */
    private String cronFailureMessage(CronJobRecord job, String error) {
        String taskName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        return "⚠ 定时任务 '"
                + safeText(taskName)
                + "' 执行失败：\n"
                + safeText(StrUtil.blankToDefault(error, "未知错误"));
    }

    /**
     * 记录运行。
     *
     * @param job job 参数。
     * @param startedAt startedAt 参数。
     * @param status 状态参数。
     * @param error 错误参数。
     * @param output 命令执行输出文本。
     * @param deliveryError 投递错误参数。
     * @param deliveryResultJson 投递结果JSON响应或执行结果。
     * @param attempt attempt 参数。
     * @param triggerType trigger类型参数。
     */
    private void recordRun(
            CronJobRecord job,
            long startedAt,
            String status,
            String error,
            String output,
            String deliveryError,
            String deliveryResultJson,
            int attempt,
            String triggerType) {
        try {
            CronJobRunRecord run = new CronJobRunRecord();
            run.setRunId(IdSupport.newId());
            run.setJobId(job.getJobId());
            run.setSourceKey(job.getSourceKey());
            run.setTriggerType(StrUtil.blankToDefault(triggerType, "scheduled"));
            run.setAttempt(attempt);
            run.setStartedAt(startedAt);
            run.setFinishedAt(System.currentTimeMillis());
            run.setStatus(status);
            run.setOutput(AgentRunPreview.safe(output));
            run.setError(AgentRunPreview.safe(error));
            run.setDeliveryError(AgentRunPreview.safe(deliveryError));
            run.setDeliveryResultJson(deliveryResultJson);
            run.setSummary(summary(status, error, output, deliveryError));
            cronJobRepository.saveRun(run);
        } catch (Exception e) {
            log.warn(
                    "Cron run history record failed: jobId={}, error={}",
                    job.getJobId(),
                    safeError(e));
        }
    }

    /**
     * 执行摘要相关逻辑。
     *
     * @param status 状态参数。
     * @param error 错误参数。
     * @param output 命令执行输出文本。
     * @param deliveryError 投递错误参数。
     * @return 返回summary结果。
     */
    private String summary(String status, String error, String output, String deliveryError) {
        if (StrUtil.isNotBlank(error)) {
            return status + ": " + AgentRunPreview.safe(error);
        }
        if (StrUtil.isNotBlank(deliveryError)) {
            return status + " (delivery_error): " + AgentRunPreview.safe(deliveryError);
        }
        if (StrUtil.isBlank(output)) {
            return status;
        }
        return status + ": " + AgentRunPreview.safe(output);
    }

    /**
     * 标记投递Error Best Effort。
     *
     * @param jobId job标识。
     * @param error 错误参数。
     */
    private void markDeliveryErrorBestEffort(String jobId, String error) {
        try {
            cronJobRepository.markDeliveryError(jobId, safeText(error));
        } catch (Exception e) {
            logCronBestEffortFailure(jobId, "delivery_error_mark", e);
        }
    }

    /**
     * 记录定时任务调度中的可恢复失败，日志只包含任务标识、阶段和异常类型。
     *
     * @param jobId 定时任务标识。
     * @param phase 发生失败的内部阶段。
     * @param error 捕获到的异常。
     */
    private void logCronBestEffortFailure(String jobId, String phase, Exception error) {
        if ("agent_poll_wait".equals(phase)) {
            log.trace(
                    "Cron scheduler best-effort fallback: jobId={}, phase={}, error={}",
                    CronJobSupport.safeLogJobId(jobId),
                    phase,
                    CronJobSupport.exceptionType(error));
            return;
        }
        log.debug(
                "Cron scheduler best-effort fallback: jobId={}, phase={}, error={}",
                CronJobSupport.safeLogJobId(jobId),
                phase,
                CronJobSupport.exceptionType(error));
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Exception e) {
        if (e == null) {
            return "Exception";
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return safeText(message);
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Text结果。
     */
    private String safeText(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 1000);
    }

    /**
     * 生成安全展示用的Target。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Target结果。
     */
    private String safeTarget(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 160);
    }

    /**
     * 读取全部。
     *
     * @param inputStream 输入流参数。
     * @return 返回读取到的全部。
     */
    private byte[] readAll(java.io.InputStream inputStream) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 执行默认Python命令相关逻辑。
     *
     * @return 返回默认Python命令结果。
     */
    private String defaultPythonCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "python"
                : "python3";
    }

    /**
     * 生成 bash 可识别的脚本路径；Windows 上常见 bash 来自 WSL，不能直接打开反斜杠盘符路径。
     *
     * @param script 已通过 workspace/scripts 边界校验的脚本文件。
     * @return 可传给 bash 的脚本路径。
     */
    private String bashScriptPath(File script) {
        String path = script.getAbsolutePath();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return path;
        }
        if (path.length() > 2 && path.charAt(1) == ':') {
            char drive = Character.toLowerCase(path.charAt(0));
            String rest = path.substring(2).replace('\\', '/');
            if (!rest.startsWith("/")) {
                rest = "/" + rest;
            }
            return "/mnt/" + drive + rest;
        }
        return path.replace('\\', '/');
    }

    /**
     * 在启动 Cron 脚本前执行危险命令、hardline 与网关生命周期检查。
     *
     * @param job 当前定时任务。
     * @param scriptContent 已读取的脚本文本。
     * @param ruleToolName 与实际脚本解释器对应的安全规则工具名。
     */
    private void guardCronScript(CronJobRecord job, String scriptContent, String ruleToolName) {
        if (dangerousCommandApprovalService == null || StrUtil.isBlank(scriptContent)) {
            return;
        }
        DangerousCommandApprovalService.DetectionResult hardline =
                dangerousCommandApprovalService.detectHardline(ruleToolName, scriptContent);
        if (hardline != null) {
            throw new IllegalStateException(
                    "BLOCKED (hardline)：定时任务脚本 "
                            + job.getScript()
                            + " 命中 "
                            + hardline.getDescription()
                            + "。hardline 命令不能从定时任务运行。");
        }

        DangerousCommandApprovalService.DetectionResult dangerous =
                dangerousCommandApprovalService.detect(ruleToolName, scriptContent);
        if (dangerous == null) {
            return;
        }
        if (isCronLifecycleBlocked(dangerous)) {
            throw new IllegalStateException(
                    "BLOCKED (lifecycle)：定时任务脚本 "
                            + job.getScript()
                            + " 命中 "
                            + dangerous.getDescription()
                            + "。网关生命周期命令不能从定时任务运行。");
        }
        String mode = dangerousCommandApprovalService.guardrailCronMode();
        if ("strict".equals(mode)) {
            throw new IllegalStateException(
                    "BLOCKED：定时任务脚本 "
                            + job.getScript()
                            + " 命中危险命令模式（"
                            + dangerous.getDescription()
                            + "），strict 模式不允许无人值守执行。");
        }
        if (!"approval".equals(mode)) {
            return;
        }
        if (approveOrRequestCronScript(job, scriptContent, ruleToolName, dangerous)) {
            return;
        }
        throw new CronApprovalPendingException(
                "BLOCKED：定时任务脚本 "
                        + job.getScript()
                        + " 命中危险命令模式（"
                        + dangerous.getDescription()
                        + "），正在等待用户审批。",
                dangerous.getDescription());
    }

    /**
     * 检查既有审批；未批准时把当前脚本写入原会话的待审批队列。
     *
     * @param job 当前定时任务。
     * @param scriptContent 已读取的脚本文本。
     * @param ruleToolName 与实际脚本解释器对应的安全规则工具名。
     * @param dangerous 危险命令检测结果。
     * @return 已存在可用审批时返回 true。
     */
    private boolean approveOrRequestCronScript(
            CronJobRecord job,
            String scriptContent,
            String ruleToolName,
            DangerousCommandApprovalService.DetectionResult dangerous) {
        try {
            String scope = cronGuardrailScope();
            SqliteAgentSession session = resolveCronApprovalSession(job);
            if (session == null) {
                throw new IllegalStateException("定时任务审批会话不可用");
            }
            List<String> approvalKeys =
                    cronApprovalPatternKeys(job, scriptContent, dangerous, scope);
            for (String key : approvalKeys) {
                boolean approved =
                        dangerousCommandApprovalService.isAlwaysApproved(
                                        ruleToolName, key, scriptContent)
                                || (!"global".equals(scope)
                                        && dangerousCommandApprovalService.isSessionApproved(
                                                session, ruleToolName, key, scriptContent));
                if (!approved) {
                    dangerousCommandApprovalService.storePendingApproval(
                            session,
                            ruleToolName,
                            approvalKeys.get(0),
                            approvalKeys,
                            "定时任务 "
                                    + StrUtil.blankToDefault(job.getName(), job.getJobId())
                                    + " 的脚本需要审批（scope="
                                    + scope
                                    + "）："
                                    + StrUtil.blankToDefault(
                                            dangerous.getDescription(), dangerous.getPatternKey()),
                            "定时任务脚本 "
                                    + StrUtil.blankToDefault(job.getScript(), "<inline>")
                                    + "\n\n"
                                    + scriptContent);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "BLOCKED：Cron approval check failed: " + safeError(e), e);
        }
    }

    /**
     * 解析当前 Cron 审批记忆范围，仅接受现有 job、session、global 三种语义。
     *
     * @return 规范化后的审批范围。
     */
    private String cronGuardrailScope() {
        if (appConfig == null || appConfig.getSecurity() == null) {
            return "job";
        }
        String scope =
                StrUtil.nullToEmpty(appConfig.getSecurity().getGuardrailCronScope())
                        .trim()
                        .toLowerCase(java.util.Locale.ROOT);
        return "global".equals(scope) || "session".equals(scope) ? scope : "job";
    }

    /**
     * 生成用于审批记忆的规则键；job 范围绑定任务与脚本指纹，脚本变更后必须重新审批。
     *
     * @param job 当前定时任务。
     * @param scriptContent 已读取的脚本文本。
     * @param dangerous 危险命令检测结果。
     * @param scope 审批记忆范围。
     * @return 待检查和存储的审批规则键。
     */
    private List<String> cronApprovalPatternKeys(
            CronJobRecord job,
            String scriptContent,
            DangerousCommandApprovalService.DetectionResult dangerous,
            String scope) {
        List<String> result = new ArrayList<String>();
        String jobId = StrUtil.blankToDefault(safeContextJobId(job.getJobId()), "unknown");
        String fingerprint = SecureUtil.sha256(StrUtil.nullToEmpty(scriptContent)).substring(0, 16);
        for (String patternKey : dangerous.effectivePatternKeys()) {
            if (StrUtil.isBlank(patternKey)) {
                continue;
            }
            result.add(
                    "job".equals(scope)
                            ? "cron-job:" + jobId + ":" + fingerprint + ":" + patternKey.trim()
                            : patternKey.trim());
        }
        if (result.isEmpty()) {
            result.add(
                    "job".equals(scope)
                            ? "cron-job:" + jobId + ":" + fingerprint + ":dangerous_command"
                            : "dangerous_command");
        }
        return result;
    }

    /**
     * 获取或创建任务来源绑定的持久化会话，供审批命令与 Dashboard 共用。
     *
     * @param job 当前定时任务。
     * @return 可持久化审批状态的会话；依赖缺失时返回 null。
     */
    private SqliteAgentSession resolveCronApprovalSession(CronJobRecord job) throws Exception {
        if (sessionRepository == null || job == null || StrUtil.isBlank(job.getSourceKey())) {
            return null;
        }
        SessionRecord record = sessionRepository.getBoundSession(job.getSourceKey());
        if (record == null) {
            record = sessionRepository.bindNewSession(job.getSourceKey());
        }
        return new SqliteAgentSession(record, sessionRepository);
    }

    /**
     * 判断危险规则是否会停止、重启或绕过受管方式启动网关进程。
     *
     * @param dangerous 危险命令检测结果。
     * @return 属于网关生命周期规则时返回 true。
     */
    private boolean isCronLifecycleBlocked(
            DangerousCommandApprovalService.DetectionResult dangerous) {
        String key = dangerous == null ? "" : StrUtil.nullToEmpty(dangerous.getPatternKey());
        return "gateway_stop_restart".equals(key)
                || "app_update_restart".equals(key)
                || "gateway_run_detached".equals(key)
                || "kill_agent_process".equals(key)
                || "kill_pgrep_expansion".equals(key);
    }

    /**
     * 格式化投递。
     *
     * @param job job 参数。
     * @param content 待处理内容。
     * @return 返回投递结果。
     */
    private String formatDelivery(CronJobRecord job, String content) {
        if (!job.isWrapResponse()) {
            return content;
        }
        String taskName = safeText(StrUtil.blankToDefault(job.getName(), job.getJobId()));
        String commandTaskName = safeCommandArgument(taskName);
        return "定时任务响应："
                + taskName
                + "\n(job_id: "
                + job.getJobId()
                + ")\n-------------\n\n"
                + content
                + "\n\n如需停止或管理此任务，请直接发送新的消息（例如：\"stop reminder "
                + commandTaskName
                + "\"）。";
    }

    /**
     * 生成可放入用户可见命令示例的安全参数，避免任务名中的换行或引号破坏提示格式。
     *
     * @param value 待展示在命令示例中的原始参数。
     * @return 返回命令示例参数。
     */
    private String safeCommandArgument(String value) {
        String text = safeTarget(StrUtil.blankToDefault(value, "unknown"));
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) {
                buffer.append(' ');
            } else if (c == '"') {
                buffer.append('\'');
            } else {
                buffer.append(c);
            }
        }
        return StrUtil.blankToDefault(buffer.toString().trim(), "unknown");
    }

    /**
     * 执行群组根据来源相关逻辑。
     *
     * @param jobs jobs 参数。
     * @return 返回群组根据来源结果。
     */
    private Map<String, List<CronJobRecord>> groupBySource(List<CronJobRecord> jobs) {
        Map<String, List<CronJobRecord>> grouped = new LinkedHashMap<String, List<CronJobRecord>>();
        for (CronJobRecord job : jobs) {
            String sourceKey = StrUtil.blankToDefault(job.getSourceKey(), "__default__");
            List<CronJobRecord> sourceJobs = grouped.get(sourceKey);
            if (sourceJobs == null) {
                sourceJobs = new ArrayList<CronJobRecord>();
                grouped.put(sourceKey, sourceJobs);
            }
            sourceJobs.add(job);
        }
        return grouped;
    }

    /** 承载定时任务投递Target相关状态和辅助逻辑。 */
    private static class CronDeliveryTarget {
        /** 记录定时任务投递Target中的平台。 */
        private final PlatformType platform;

        /** 记录定时任务投递Target中的聊天标识。 */
        private final String chatId;

        /** 记录定时任务投递Target中的thread标识。 */
        private final String threadId;

        /**
         * 创建定时任务投递Target实例，并注入运行所需依赖。
         *
         * @param platform 平台参数。
         * @param chatId 聊天标识。
         * @param threadId thread标识。
         */
        private CronDeliveryTarget(PlatformType platform, String chatId, String threadId) {
            this.platform = platform;
            this.chatId = chatId;
            this.threadId = threadId;
        }
    }

    /** 承载定时任务媒体Ref相关状态和辅助逻辑。 */
    private static class CronMediaRef {
        /** 记录定时任务媒体Ref中的token。 */
        private final String token;

        /** 记录定时任务媒体Ref中的路径。 */
        private final String path;

        /** 是否启用语音。 */
        private final boolean voice;

        /**
         * 创建定时任务媒体Ref实例，并注入运行所需依赖。
         *
         * @param token token 参数。
         * @param path 文件或目录路径。
         * @param voice 语音参数。
         */
        private CronMediaRef(String token, String path, boolean voice) {
            this.token = token;
            this.path = path;
            this.voice = voice;
        }
    }

    /** 承载定时任务Resolved媒体相关状态和辅助逻辑。 */
    private static class CronResolvedMedia {
        /** 保存附件集合，维持调用顺序或去重语义。 */
        private final List<MessageAttachment> attachments;

        /** 保存resolved集合，维持调用顺序或去重语义。 */
        private final List<CronMediaRef> resolved;

        /**
         * 创建定时任务Resolved媒体实例，并注入运行所需依赖。
         *
         * @param attachments attachments 参数。
         * @param resolved resolved 参数。
         */
        private CronResolvedMedia(
                List<MessageAttachment> attachments, List<CronMediaRef> resolved) {
            this.attachments = attachments;
            this.resolved = resolved;
        }
    }

    /** 承载定时任务投递Report相关状态和辅助逻辑。 */
    private static class CronDeliveryReport {
        /** 记录定时任务投递Report中的delivered。 */
        private int delivered;

        /** 记录定时任务投递Report中的failed。 */
        private int failed;

        /** 记录定时任务投递Report中的total。 */
        private int total;

        /** 记录定时任务投递Report中的skipped。 */
        private String skipped;

        /** 记录定时任务投递Report中的错误。 */
        private String error;

        /** 保存targets映射，便于按键快速查询。 */
        private final List<Map<String, Object>> targets = new ArrayList<Map<String, Object>>();

        /**
         * 执行skipped相关逻辑。
         *
         * @param reason 原因参数。
         * @return 返回skipped结果。
         */
        private static CronDeliveryReport skipped(String reason) {
            CronDeliveryReport report = new CronDeliveryReport();
            report.skipped = reason;
            return report;
        }

        /**
         * 执行failed相关逻辑。
         *
         * @param error 错误参数。
         * @return 返回failed结果。
         */
        private static CronDeliveryReport failed(String error) {
            CronDeliveryReport report = new CronDeliveryReport();
            report.error = SecretRedactor.redact(StrUtil.nullToEmpty(error), 1000);
            report.failed = 1;
            report.total = 1;
            return report;
        }

        /**
         * 追加Ok。
         *
         * @param target target 参数。
         * @param attachmentCount 附件Count参数。
         */
        private void addOk(CronDeliveryTarget target, int attachmentCount) {
            delivered++;
            total++;
            targets.add(targetMap(target, "ok", null, attachmentCount));
        }

        /**
         * 追加错误。
         *
         * @param target target 参数。
         * @param attachmentCount 附件Count参数。
         * @param error 错误参数。
         */
        private void addError(CronDeliveryTarget target, int attachmentCount, String error) {
            failed++;
            total++;
            targets.add(
                    targetMap(
                            target,
                            "error",
                            SecretRedactor.redact(StrUtil.nullToEmpty(error), 1000),
                            attachmentCount));
        }

        /**
         * 判断是否存在Errors。
         *
         * @return 如果Errors满足条件则返回 true，否则返回 false。
         */
        private boolean hasErrors() {
            return failed > 0 || StrUtil.isNotBlank(error);
        }

        /**
         * 执行错误摘要相关逻辑。
         *
         * @return 返回error Summary结果。
         */
        private String errorSummary() {
            if (StrUtil.isNotBlank(error)) {
                return error;
            }
            if (failed <= 0) {
                return null;
            }
            StringBuilder buffer = new StringBuilder();
            buffer.append(failed).append('/').append(total).append(" 个投递目标失败");
            for (Map<String, Object> target : targets) {
                if (!"error".equals(target.get("status"))) {
                    continue;
                }
                buffer.append(": ")
                        .append(target.get("platform"))
                        .append(':')
                        .append(target.get("chat_id"));
                Object targetError = target.get("error");
                if (targetError != null) {
                    buffer.append(" ").append(targetError);
                }
                break;
            }
            return buffer.toString();
        }

        /**
         * 转换为JSON。
         *
         * @return 返回转换后的JSON。
         */
        private String toJson() {
            if (total <= 0 && StrUtil.isBlank(skipped) && StrUtil.isBlank(error)) {
                return null;
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("total", Integer.valueOf(total));
            data.put("delivered", Integer.valueOf(delivered));
            data.put("failed", Integer.valueOf(failed));
            if (StrUtil.isNotBlank(skipped)) {
                data.put("skipped", skipped);
            }
            if (StrUtil.isNotBlank(error)) {
                data.put("error", error);
            }
            data.put("targets", targets);
            return ONode.serialize(data);
        }

        /**
         * 执行target映射相关逻辑。
         *
         * @param target target 参数。
         * @param status 状态参数。
         * @param error 错误参数。
         * @param attachmentCount 附件Count参数。
         * @return 返回target Map结果。
         */
        private Map<String, Object> targetMap(
                CronDeliveryTarget target, String status, String error, int attachmentCount) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("platform", target.platform == null ? null : target.platform.name());
            map.put("chat_id", SecretRedactor.redact(StrUtil.nullToEmpty(target.chatId), 160));
            map.put("thread_id", SecretRedactor.redact(StrUtil.nullToEmpty(target.threadId), 160));
            map.put("status", status);
            map.put("attachments", Integer.valueOf(attachmentCount));
            if (StrUtil.isNotBlank(error)) {
                map.put("error", error);
            }
            return map;
        }
    }

    /** 承载定时任务投递载荷相关状态和辅助逻辑。 */
    private static class CronDeliveryPayload {
        /** 记录定时任务投递载荷中的文本。 */
        private final String text;

        /** 保存媒体集合，维持调用顺序或去重语义。 */
        private final List<CronMediaRef> media;

        /**
         * 创建定时任务投递Payload实例，并注入运行所需依赖。
         *
         * @param text 待处理文本。
         * @param media 媒体参数。
         */
        private CronDeliveryPayload(String text, List<CronMediaRef> media) {
            this.text = text;
            this.media = media;
        }
    }

    /** 表示定时任务Script结果，携带调用方后续判断所需信息。 */
    private static class CronScriptResult {
        /** 记录定时任务Script中的输出。 */
        private final String output;

        /** 是否启用wakeAgent。 */
        private final boolean wakeAgent;

        /**
         * 创建定时任务Script结果实例，并注入运行所需依赖。
         *
         * @param output 命令执行输出文本。
         * @param wakeAgent wakeAgent 参数。
         */
        private CronScriptResult(String output, boolean wakeAgent) {
            this.output = output;
            this.wakeAgent = wakeAgent;
        }
    }

    /** 表示 Cron 脚本已进入待审批状态，调度器需暂停任务而不是按普通失败处理。 */
    private static class CronApprovalPendingException extends RuntimeException {
        /** 用于任务暂停原因展示的危险规则说明。 */
        private final String detectionDescription;

        /**
         * 创建待审批异常。
         *
         * @param message 安全阻断消息。
         * @param detectionDescription 危险规则说明。
         */
        private CronApprovalPendingException(String message, String detectionDescription) {
            super(message);
            this.detectionDescription = detectionDescription;
        }

        /**
         * 读取危险规则说明。
         *
         * @return 脱敏前的规则描述，由调用方统一限制展示长度。
         */
        private String getDetectionDescription() {
            return detectionDescription;
        }
    }

    /** 承载Agent运行预览相关状态和辅助逻辑。 */
    private static class AgentRunPreview {
        /**
         * 执行安全相关逻辑。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回safe结果。
         */
        private static String safe(String value) {
            if (value == null) {
                return null;
            }
            return value.length() <= 4000 ? value : value.substring(0, 4000) + "...";
        }
    }
}
