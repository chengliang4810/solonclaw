package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
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
import com.jimuqu.solon.claw.core.model.SkillView;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.CronSupport;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MediaDirectiveSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.CronAutoDeliveryContext;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DefaultCronScheduler 实现。 */
public class DefaultCronScheduler {
    private static final Logger log = LoggerFactory.getLogger(DefaultCronScheduler.class);
    private static final String SILENT_MARKER = "[SILENT]";
    private static final String EMPTY_AGENT_RESPONSE_ERROR =
            "Agent completed but produced empty response (model error, timeout, or misconfiguration)";
    private static final String EMPTY_AGENT_RESPONSE_OUTPUT = "(No response generated)";
    private static final int MAX_CONTEXT_FROM_CHARS = 8000;
    private static final List<String> CRON_DISABLED_TOOLSETS =
            Arrays.asList("cronjob", "messaging", "clarify");
    private static final int DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS = 600;
    private static final long AGENT_TIMEOUT_POLL_MILLIS = 500L;
    private static final ExecutorService MCP_WARMUP_EXECUTOR =
            BoundedExecutorFactory.fixed("cron-mcp-warmup", 1, 16);
    private static final Pattern SAFE_CONTEXT_JOB_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{3,127}");
    private static final String CRON_PROMPT_BLOCK_PREFIX = "BLOCKED: Cron assembled prompt";
    private static final String CRON_RUNTIME_HINT =
            "[IMPORTANT: You are running as a scheduled cron job. "
                    + "DELIVERY: Your final response will be automatically delivered to the user; "
                    + "do not call send_message or try to deliver the output yourself. "
                    + "Produce the report or output as your final response and the scheduler handles delivery. "
                    + "SILENT: If there is genuinely nothing new to report, respond with exactly \"[SILENT]\" "
                    + "and nothing else to suppress delivery. Never combine [SILENT] with content.]\n\n";

    private final AppConfig appConfig;
    private final CronJobRepository cronJobRepository;
    private final CronJobService cronJobService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final DeliveryService deliveryService;
    private final GatewayPolicyRepository gatewayPolicyRepository;
    private final DangerousCommandApprovalService dangerousCommandApprovalService;
    private final AttachmentCacheService attachmentCacheService;
    private final LocalSkillService localSkillService;
    private final AgentRunControlService agentRunControlService;
    private final McpRuntimeService mcpRuntimeService;
    private ScheduledExecutorService executorService;

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
    }

    public void start() {
        if (!appConfig.getScheduler().isEnabled()) {
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                this::tickSafe, 5, appConfig.getScheduler().getTickSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    public void shutdown() {
        stop();
    }

    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Cron tick failed: error={}", safeError(e));
        }
    }

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
                            new Runnable() {
                                @Override
                                public void run() {
                                    for (CronJobRecord job : sourceJobs) {
                                        executeBestEffort(job);
                                    }
                                }
                            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();
    }

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

    private boolean hasWorkdir(CronJobRecord job) {
        return job != null && StrUtil.isNotBlank(job.getWorkdir());
    }

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
                            "Cron job missed scheduled window and was fast-forwarded: jobId={}, nextRunAt={}",
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

    private long recurringRecoveryBase(CronJobRecord job, long now) {
        return job.getLastRunAt() > 0L ? job.getLastRunAt() : now;
    }

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

    private long oneShotDurationMillis(String schedule) {
        Integer minutes = CronSupport.intervalMinutes(schedule);
        return Math.max(60000L, minutes == null ? 60000L : minutes.intValue() * 60000L);
    }

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

    private boolean isRecurring(CronJobRecord job) {
        return job != null && !CronSupport.isOneShot(job.getCronExpr());
    }

    private long missedRunGraceMillis(String cronExpr, long now) {
        long period = CronSupport.periodMillis(cronExpr, now);
        long grace = period <= 0L ? 120000L : period / 2L;
        return Math.max(120000L, Math.min(grace, 7200000L));
    }

    public void runNow(String jobId) throws Exception {
        runNow(jobId, "manual");
    }

    public void runNow(String jobId, String triggerType) throws Exception {
        CronJobRecord job = cronJobRepository.findById(jobId);
        if (job != null) {
            execute(job, System.currentTimeMillis(), StrUtil.blankToDefault(triggerType, "manual"));
        }
    }

    private void execute(CronJobRecord job, long now) throws Exception {
        execute(job, now, queuedTriggerType(job));
    }

    private void execute(CronJobRecord job, long now, String triggerType) throws Exception {
        triggerType = StrUtil.blankToDefault(triggerType, "scheduled");
        job.setPendingTriggerType(null);
        long nextRunAt = CronSupport.nextRunAt(job.getCronExpr(), now);
        int completed = job.getRepeatCompleted() + 1;
        boolean done = job.getRepeatTimes() > 0 && completed >= job.getRepeatTimes();
        String nextStatus = done || CronSupport.isOneShot(job.getCronExpr()) ? "COMPLETED" : "ACTIVE";
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
                        scanAssembledPrompt(prompt, job);
                    }
                    if (scriptResult != null && !scriptResult.wakeAgent) {
                        output = silentCronOutput(job, "wakeAgent=false");
                        reply = GatewayReply.ok(SILENT_MARKER);
                        cronJobRepository.markRunResult(
                                job.getJobId(),
                                now,
                                nextRunAt,
                                runStatus,
                                null,
                                AgentRunPreview.safe(output),
                                completed,
                                nextStatus);
                        CronDeliveryReport deliveryReport = deliverBestEffort(job, reply);
                        deliveryError = deliveryReport.errorSummary();
                        deliveryResultJson = deliveryReport.toJson();
                        recordRun(job, now, runStatus, null, output, deliveryError, deliveryResultJson, completed, triggerType);
                        return;
                    }
                    if (scriptResult != null && StrUtil.isBlank(scriptResult.output)) {
                        output = silentCronOutput(job, "empty script output");
                        reply = GatewayReply.ok(SILENT_MARKER);
                        cronJobRepository.markRunResult(
                                job.getJobId(),
                                now,
                                nextRunAt,
                                runStatus,
                                null,
                                AgentRunPreview.safe(output),
                                completed,
                                nextStatus);
                        CronDeliveryReport deliveryReport = deliverBestEffort(job, reply);
                        deliveryError = deliveryReport.errorSummary();
                        deliveryResultJson = deliveryReport.toJson();
                        recordRun(job, now, runStatus, null, output, deliveryError, deliveryResultJson, completed, triggerType);
                        return;
                    }
                    if (scriptResult != null) {
                        prompt = withScriptOutput(prompt, scriptResult.output);
                        scanAssembledPrompt(prompt, job);
                    }
                }
                String[] parts = SourceKeySupport.split(job.getSourceKey());
                GatewayMessage synthetic =
                        new GatewayMessage(
                                PlatformType.fromName(parts[0]), parts[1], parts[2], prompt);
                synthetic.setThreadId(parts[3]);
                String override = modelOverride(job);
                if (StrUtil.isNotBlank(override)) {
                    synthetic.setModelOverride(override);
                }
                if (StrUtil.isNotBlank(job.getWorkdir())) {
                    synthetic.setWorkspaceDirOverride(job.getWorkdir());
                }
                synthetic.setEnabledToolsetsOverride(resolveCronEnabledToolsets(job));
                synthetic.setDisabledToolsetsOverride(new ArrayList<String>(CRON_DISABLED_TOOLSETS));
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
                    nextRunAt,
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
            recordRun(job, now, runStatus, error, output, deliveryError, deliveryResultJson, completed, triggerType);
        } catch (Exception e) {
            runStatus = "error";
            error = safeError(e);
            cronJobRepository.markRunResult(
                    job.getJobId(),
                    now,
                    nextRunAt,
                    runStatus,
                    error,
                    AgentRunPreview.safe(output),
                    completed,
                    done ? "COMPLETED" : "ACTIVE");
            CronDeliveryReport deliveryReport = deliverErrorBestEffort(job, error);
            deliveryError = deliveryReport.errorSummary();
            deliveryResultJson = deliveryReport.toJson();
            recordRun(job, now, runStatus, error, output, deliveryError, deliveryResultJson, completed, triggerType);
            if (!isCronScriptPathBlock(e)) {
                throw e;
            }
        }
    }

    private String queuedTriggerType(CronJobRecord job) {
        if (job == null || StrUtil.isBlank(job.getPendingTriggerType())) {
            return "scheduled";
        }
        return job.getPendingTriggerType();
    }

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

    private boolean isCronScriptSecurityBlock(Exception error) {
        String message = error == null ? null : error.getMessage();
        return StrUtil.isNotBlank(message) && message.startsWith("BLOCKED");
    }

    private boolean isCronScriptPathBlock(Exception error) {
        String message = error == null ? null : error.getMessage();
        return StrUtil.isNotBlank(message)
                && message.startsWith("Cron script not found under runtime/scripts");
    }

    private boolean isCronPromptSecurityBlock(String error) {
        return StrUtil.isNotBlank(error) && error.startsWith(CRON_PROMPT_BLOCK_PREFIX);
    }

    private void warmupMcpTools(CronJobRecord job) {
        if (mcpRuntimeService == null) {
            return;
        }
        try {
            MCP_WARMUP_EXECUTOR.submit(
                    new Runnable() {
                        @Override
                        public void run() {
                            warmupMcpToolsNow(job);
                        }
                    });
        } catch (RejectedExecutionException e) {
            log.warn(
                    "Cron job '{}' MCP initialization skipped: {}",
                    job == null ? "<unknown>" : StrUtil.blankToDefault(job.getName(), job.getJobId()),
                    safeError(e));
        }
    }

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
                    job == null ? "<unknown>" : StrUtil.blankToDefault(job.getName(), job.getJobId()),
                    safeError(e));
        }
    }

    private String withScriptOutput(String prompt, String output) {
        return "## Script Output\n"
                + "The following data was collected by a pre-run script. Use it as context for your analysis.\n\n"
                + "```\n"
                + StrUtil.nullToEmpty(output)
                + "\n```\n\n"
                + StrUtil.nullToEmpty(prompt);
    }

    private String withScriptError(String prompt, String error) {
        return "## Script Error\n"
                + "The data-collection script failed. Report this to the user.\n\n"
                + "```\n"
                + StrUtil.blankToDefault(error, "unknown error")
                + "\n```\n\n"
                + StrUtil.nullToEmpty(prompt);
    }

    private GatewayReply runScheduledWithInactivityTimeout(
            CronJobRecord job, GatewayMessage synthetic) throws Exception {
        int timeoutSeconds = agentInactivityTimeoutSeconds();
        if (timeoutSeconds <= 0 || agentRunControlService == null) {
            return conversationOrchestrator.runScheduled(synthetic);
        }
        ExecutorService executor =
                Executors.newSingleThreadExecutor(
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable runnable) {
                                Thread thread = new Thread(runnable, "cron-agent-run-" + job.getJobId());
                                thread.setDaemon(true);
                                return thread;
                            }
                        });
        Future<GatewayReply> future =
                executor.submit(
                        new java.util.concurrent.Callable<GatewayReply>() {
                            @Override
                            public GatewayReply call() throws Exception {
                                return conversationOrchestrator.runScheduled(synthetic);
                            }
                        });
        boolean inactivityTimeout = false;
        Map<String, Object> activity = null;
        long limitMillis = timeoutSeconds * 1000L;
        try {
            while (true) {
                try {
                    return future.get(AGENT_TIMEOUT_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
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
                        "unknown");
        String jobName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        String message =
                "Cron job '"
                        + jobName
                        + "' idle for "
                        + idleSeconds
                        + "s (limit "
                        + timeoutSeconds
                        + "s) - last activity: "
                        + lastDesc;
        log.error("{}", message);
        throw new TimeoutException(message);
    }

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

    private int agentInactivityTimeoutSeconds() {
        String envValue = StrUtil.trim(System.getenv("JIMUQU_CRON_TIMEOUT"));
        if (StrUtil.isNotBlank(envValue)) {
            try {
                int value = (int) Double.parseDouble(envValue);
                return value >= 0 ? value : DEFAULT_AGENT_INACTIVITY_TIMEOUT_SECONDS;
            } catch (Exception e) {
                log.warn(
                        "Invalid JIMUQU_CRON_TIMEOUT={}; using default {}s",
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

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
            CronResolvedMedia resolvedMedia = resolveMediaAttachments(target.platform, payload.media);
            DeliveryRequest request = new DeliveryRequest();
            request.setPlatform(target.platform);
            request.setChatId(target.chatId);
            request.setThreadId(target.threadId);
            request.setText(removeResolvedMediaTags(payload.text, resolvedMedia.resolved));
            request.setAttachments(resolvedMedia.attachments);
            try {
                deliveryService.deliver(request);
                report.addOk(target, request.getAttachments() == null ? 0 : request.getAttachments().size());
            } catch (Exception e) {
                String error = safeError(e);
                report.addError(target, request.getAttachments() == null ? 0 : request.getAttachments().size(), error);
                log.warn(
                        "Cron delivery target failed: jobId={}, platform={}, chatId={}, threadId={}, error={}",
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

    private boolean isSilent(String content) {
        return StrUtil.isNotBlank(content)
                && content.trim().toUpperCase(java.util.Locale.ROOT).startsWith(SILENT_MARKER);
    }

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
            return new CronDeliveryTarget(platform, chatId.trim(), normalizeBlank(threadId));
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

    private CronDeliveryTarget originTarget(CronJobRecord job) {
        CronDeliveryTarget explicit = targetFromOriginJson(job.getOriginJson());
        if (explicit != null) {
            return explicit;
        }
        if (StrUtil.isNotBlank(job.getDeliverChatId())) {
            PlatformType platform = deliverPlatform(job);
            if (platform != null) {
                return new CronDeliveryTarget(
                        platform, job.getDeliverChatId(), normalizeBlank(job.getDeliverThreadId()));
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
                normalizeBlank(
                        StrUtil.blankToDefault(job.getDeliverThreadId(), parts[3])));
    }

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
                PlatformType.fromName(firstString(origin, "platform", "channel", "source"));
        String chatId = firstString(origin, "chat_id", "chatId", "conversation_id", "conversationId");
        String threadId = firstString(origin, "thread_id", "threadId", "topic_id", "topicId");
        if (platform == null || StrUtil.isBlank(chatId)) {
            return null;
        }
        return new CronDeliveryTarget(platform, chatId.trim(), normalizeBlank(threadId));
    }

    private String firstString(Map<?, ?> map, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            Object value = map.get(keys[i]);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

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
        return new CronDeliveryTarget(platform, home.getChatId(), normalizeBlank(home.getThreadId()));
    }

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
                        "Cron job has deliver=origin but no origin/source chat; falling back to {} home channel: jobId={}",
                        platform,
                        job.getJobId());
                return target;
            }
        }
        return null;
    }

    private PlatformType sourcePlatform(CronJobRecord job) {
        String[] parts = SourceKeySupport.split(job.getSourceKey());
        return PlatformType.fromName(parts[0]);
    }

    private PlatformType deliverPlatform(CronJobRecord job) {
        PlatformType platform = PlatformType.fromName(job.getDeliverPlatform());
        if (platform != null) {
            return platform;
        }
        return sourcePlatform(job);
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() == 0 ? null : text;
    }

    private String buildPrompt(CronJobRecord job) throws Exception {
        StringBuilder prompt = new StringBuilder(CRON_RUNTIME_HINT);
        Map<String, Object> view = cronJobService.toView(job);
        List<String> skills =
                view.containsKey("skills") ? (List<String>) view.get("skills") : new ArrayList<String>();
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
                    prompt.append("上游任务 ")
                            .append(upstream.getJobId())
                            .append(" 最近输出：\n")
                            .append(truncateContextFromOutput(upstream.getLastOutput()))
                            .append("\n\n");
                }
            }
        }
        prompt.append(StrUtil.nullToEmpty(job.getPrompt()));
        return scanAssembledPrompt(prompt.toString(), job);
    }

    private String scanAssembledPrompt(String prompt, CronJobRecord job) {
        if (cronJobService == null || StrUtil.isBlank(prompt)) {
            return prompt;
        }
        try {
            cronJobService.scanPrompt(prompt);
            return prompt;
        } catch (IllegalStateException e) {
            String jobLabel =
                    job == null ? "<unknown>" : StrUtil.blankToDefault(job.getName(), job.getJobId());
            String reason =
                    safeText(StrUtil.blankToDefault(e.getMessage(), "cron prompt injection scanner"));
            log.warn(
                    "Cron job '{}' blocked by assembled prompt scanner: {}",
                    jobLabel,
                    reason);
            throw new IllegalStateException(CRON_PROMPT_BLOCK_PREFIX + " matched scanner: " + reason);
        }
    }

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
                    "[IMPORTANT: The following skill(s) were listed for this scheduled job but could not be found and were skipped: "
                            + joinNames(skipped)
                            + ". Start your response with a brief notice so the user is aware.]\n\n");
        }
        if (parts.length() == 0) {
            return "";
        }
        return parts.append("\n\n").toString();
    }

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

    private String joinNames(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private String truncateContextFromOutput(String output) {
        if (output == null || output.length() <= MAX_CONTEXT_FROM_CHARS) {
            return output;
        }
        return output.substring(0, MAX_CONTEXT_FROM_CHARS) + "\n\n[... output truncated ...]";
    }

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

    private List<String> resolveCronEnabledToolsets(CronJobRecord job) {
        List<String> perJob = enabledToolsets(job);
        if (!perJob.isEmpty()) {
            return perJob;
        }
        if (appConfig == null || appConfig.getScheduler() == null) {
            return perJob;
        }
        List<String> fallback = appConfig.getScheduler().getEnabledToolsets();
        if (fallback == null || fallback.isEmpty()) {
            return perJob;
        }
        return new ArrayList<String>(fallback);
    }

    private String runScript(CronJobRecord job) throws Exception {
        return runScriptResult(job).output;
    }

    private CronScriptResult runScriptResult(CronJobRecord job) throws Exception {
        File scriptsDir = FileUtil.file(appConfig.getRuntime().getHome(), "scripts").getCanonicalFile();
        File requested = new File(job.getScript());
        File script = (requested.isAbsolute() ? requested : new File(scriptsDir, job.getScript())).getCanonicalFile();
        if (!isUnderDirectory(scriptsDir, script) || !script.exists() || !script.isFile()) {
            throw new IllegalStateException("Cron script not found under runtime/scripts: " + job.getScript());
        }
        String name = script.getName().toLowerCase();
        String scriptContent = FileUtil.readString(script, StandardCharsets.UTF_8);
        guardCronScript(job, scriptContent);
        List<String> command = new ArrayList<String>();
        if (name.endsWith(".sh") || name.endsWith(".bash")) {
            command.add("bash");
        } else {
            command.add(defaultPythonCommand());
        }
        command.add(script.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        if (StrUtil.isNotBlank(job.getWorkdir())) {
            builder.directory(new File(job.getWorkdir()));
        }
        builder.redirectErrorStream(true);
        SubprocessEnvironmentSanitizer.sanitize(builder.environment(), appConfig);
        Process process = builder.start();
        byte[] data = readAll(process.getInputStream());
        int timeoutSeconds = scriptTimeoutSeconds();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Cron script timed out after " + timeoutSeconds + "s");
        }
        String output = new String(data, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Cron script exited " + process.exitValue() + ": " + output);
        }
        return new CronScriptResult(output, parseWakeAgent(output));
    }

    private boolean isUnderDirectory(File root, File target) throws Exception {
        java.nio.file.Path rootPath = root.getCanonicalFile().toPath().toAbsolutePath().normalize();
        java.nio.file.Path targetPath = target.getCanonicalFile().toPath().toAbsolutePath().normalize();
        if (targetPath.equals(rootPath)) {
            return false;
        }
        return targetPath.startsWith(rootPath);
    }

    private int scriptTimeoutSeconds() {
        String envValue = StrUtil.trim(System.getenv("JIMUQU_CRON_SCRIPT_TIMEOUT"));
        if (StrUtil.isNotBlank(envValue)) {
            try {
                int value = (int) Double.parseDouble(envValue);
                if (value > 0) {
                    return value;
                }
            } catch (Exception e) {
                log.warn("Invalid JIMUQU_CRON_SCRIPT_TIMEOUT={}; using config/default", envValue);
            }
        }
        int value =
                appConfig == null || appConfig.getScheduler() == null
                        ? 120
                        : appConfig.getScheduler().getScriptTimeoutSeconds();
        return value > 0 ? value : 120;
    }

    private boolean parseWakeAgent(String output) {
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
            } catch (Exception ignored) {
                return true;
            }
            return true;
        }
        return true;
    }

    private CronDeliveryReport deliverErrorBestEffort(CronJobRecord job, String error) {
        if (!job.isNoAgent()) {
            if (!isCronPromptSecurityBlock(error)) {
                return CronDeliveryReport.skipped("agent_error");
            }
            try {
                return deliver(job, GatewayReply.error(blockedPromptFailureMessage(job, error)));
            } catch (Exception e) {
                String deliveryError = safeError(e);
                markDeliveryErrorBestEffort(job.getJobId(), deliveryError);
                return CronDeliveryReport.failed(deliveryError);
            }
        }
        try {
            return deliver(job, GatewayReply.error(noAgentScriptFailureMessage(job, error)));
        } catch (Exception e) {
            String deliveryError = safeError(e);
            markDeliveryErrorBestEffort(job.getJobId(), deliveryError);
            return CronDeliveryReport.failed(deliveryError);
        }
    }

    private String blockedPromptFailureMessage(CronJobRecord job, String error) {
        String taskName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        return "# Cron Job: "
                + taskName
                + "\n\n"
                + "**Job ID:** "
                + job.getJobId()
                + "\n"
                + "**Run Time:** "
                + time
                + "\n"
                + "**Status:** BLOCKED\n\n"
                + "The assembled prompt, including loaded skill content and script context, matched the cron injection scanner and the agent was not run.\n\n"
                + "**Scanner result:** "
                + safeText(StrUtil.blankToDefault(error, "unknown scanner result"))
                + "\n\n"
                + "Audit the skill(s) or script output attached to this job before resuming it.";
    }

    private String noAgentScriptFailureMessage(CronJobRecord job, String error) {
        String taskName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        String message = safeText(StrUtil.blankToDefault(error, "unknown error"));
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        return "⚠ Cron watchdog '"
                + taskName
                + "' script failed\n\n"
                + message
                + "\n\nTime: "
                + time;
    }

    private String cronFailureMessage(CronJobRecord job, String error) {
        String taskName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        return "⚠ Cron job '"
                + taskName
                + "' failed:\n"
                + safeText(StrUtil.blankToDefault(error, "unknown error"));
    }

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

    private void markDeliveryErrorBestEffort(String jobId, String error) {
        try {
            cronJobRepository.markDeliveryError(jobId, safeText(error));
        } catch (Exception ignored) {
        }
    }

    private String safeError(Exception e) {
        if (e == null) {
            return "Exception";
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return safeText(message);
    }

    private String safeText(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 1000);
    }

    private String safeTarget(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 160);
    }

    private byte[] readAll(java.io.InputStream inputStream) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String defaultPythonCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "python" : "python3";
    }

    private void guardCronScript(CronJobRecord job, String scriptContent) {
        if (dangerousCommandApprovalService == null || StrUtil.isBlank(scriptContent)) {
            return;
        }
        DangerousCommandApprovalService.DetectionResult hardline =
                dangerousCommandApprovalService.detectHardline(
                        ToolNameConstants.EXECUTE_SHELL, scriptContent);
        if (hardline != null) {
            throw new IllegalStateException(
                    "BLOCKED (hardline): Cron script "
                            + job.getScript()
                            + " matched "
                            + hardline.getDescription()
                            + ". Hardline commands cannot run from cron.");
        }

        DangerousCommandApprovalService.DetectionResult dangerous =
                dangerousCommandApprovalService.detect(ToolNameConstants.EXECUTE_SHELL, scriptContent);
        if (dangerous == null) {
            return;
        }
        if (isCronLifecycleBlocked(dangerous)) {
            throw new IllegalStateException(
                    "BLOCKED (lifecycle): Cron script "
                            + job.getScript()
                            + " matched "
                            + dangerous.getDescription()
                            + ". Gateway lifecycle commands cannot run from cron.");
        }
        String mode = dangerousCommandApprovalService.cronApprovalMode();
        if (!"approve".equals(mode)) {
            throw new IllegalStateException(
                    "BLOCKED: Cron script "
                            + job.getScript()
                            + " matched dangerous command pattern ("
                            + dangerous.getDescription()
                            + ") but cron runs without a user present to approve it. Set approvals.cronMode=approve to allow this.");
        }
    }

    private boolean isCronLifecycleBlocked(
            DangerousCommandApprovalService.DetectionResult dangerous) {
        if (dangerous == null) {
            return false;
        }
        String key = StrUtil.nullToEmpty(dangerous.getPatternKey());
        return "gateway_stop_restart".equals(key)
                || "app_update_restart".equals(key)
                || "gateway_run_detached".equals(key)
                || "kill_agent_process".equals(key)
                || "kill_pgrep_expansion".equals(key);
    }

    private String formatDelivery(CronJobRecord job, String content) {
        if (!job.isWrapResponse()) {
            return content;
        }
        String taskName = StrUtil.blankToDefault(job.getName(), job.getJobId());
        return "Cronjob Response: "
                + taskName
                + "\n(job_id: "
                + job.getJobId()
                + ")\n-------------\n\n"
                + content
                + "\n\nTo stop or manage this job, send me a new message (e.g. \"stop reminder "
                + taskName
                + "\").";
    }

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

    private static class CronDeliveryTarget {
        private final PlatformType platform;
        private final String chatId;
        private final String threadId;

        private CronDeliveryTarget(PlatformType platform, String chatId, String threadId) {
            this.platform = platform;
            this.chatId = chatId;
            this.threadId = threadId;
        }
    }

    private static class CronMediaRef {
        private final String token;
        private final String path;
        private final boolean voice;

        private CronMediaRef(String token, String path, boolean voice) {
            this.token = token;
            this.path = path;
            this.voice = voice;
        }
    }

    private static class CronResolvedMedia {
        private final List<MessageAttachment> attachments;
        private final List<CronMediaRef> resolved;

        private CronResolvedMedia(List<MessageAttachment> attachments, List<CronMediaRef> resolved) {
            this.attachments = attachments;
            this.resolved = resolved;
        }
    }

    private static class CronDeliveryReport {
        private int delivered;
        private int failed;
        private int total;
        private String skipped;
        private String error;
        private final List<Map<String, Object>> targets = new ArrayList<Map<String, Object>>();

        private static CronDeliveryReport skipped(String reason) {
            CronDeliveryReport report = new CronDeliveryReport();
            report.skipped = reason;
            return report;
        }

        private static CronDeliveryReport failed(String error) {
            CronDeliveryReport report = new CronDeliveryReport();
            report.error = SecretRedactor.redact(StrUtil.nullToEmpty(error), 1000);
            report.failed = 1;
            report.total = 1;
            return report;
        }

        private void addOk(CronDeliveryTarget target, int attachmentCount) {
            delivered++;
            total++;
            targets.add(targetMap(target, "ok", null, attachmentCount));
        }

        private void addError(CronDeliveryTarget target, int attachmentCount, String error) {
            failed++;
            total++;
            targets.add(targetMap(target, "error", SecretRedactor.redact(StrUtil.nullToEmpty(error), 1000), attachmentCount));
        }

        private boolean hasErrors() {
            return failed > 0 || StrUtil.isNotBlank(error);
        }

        private String errorSummary() {
            if (StrUtil.isNotBlank(error)) {
                return error;
            }
            if (failed <= 0) {
                return null;
            }
            StringBuilder buffer = new StringBuilder();
            buffer.append(failed).append('/').append(total).append(" delivery target(s) failed");
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

    private static class CronDeliveryPayload {
        private final String text;
        private final List<CronMediaRef> media;

        private CronDeliveryPayload(String text, List<CronMediaRef> media) {
            this.text = text;
            this.media = media;
        }
    }

    private static class CronScriptResult {
        private final String output;
        private final boolean wakeAgent;

        private CronScriptResult(String output, boolean wakeAgent) {
            this.output = output;
            this.wakeAgent = wakeAgent;
        }
    }

    private static class AgentRunPreview {
        private static String safe(String value) {
            if (value == null) {
                return null;
            }
            return value.length() <= 4000 ? value : value.substring(0, 4000) + "...";
        }
    }
}
