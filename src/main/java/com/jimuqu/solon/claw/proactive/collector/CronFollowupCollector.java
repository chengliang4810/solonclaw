package com.jimuqu.solon.claw.proactive.collector;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 定时任务跟进观测采集器，用于发现计划任务失败、投递错误、超期未运行和暂停原因。 */
public class CronFollowupCollector implements ProactiveObservationCollector {
    /** 定时任务跟进采集器的低敏日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(CronFollowupCollector.class);

    /** 采集器稳定名称，用于观测来源、排障和候选生成识别。 */
    public static final String COLLECTOR_NAME = "cron_followup";

    /** 单个定时任务最多读取的近期执行历史数量，避免单次 tick 扫描过多明细。 */
    private static final int RECENT_RUN_LIMIT = 5;

    /** 判定重复失败所需的最小失败次数，避免单次偶发失败直接升级为重复失败。 */
    private static final int REPEATED_FAILURE_THRESHOLD = 2;

    /** payload 中短文本字段最大长度，避免原始输出或错误过长。 */
    private static final int TEXT_MAX_LENGTH = 600;

    /** 执行历史证据中的输出预览最大长度。 */
    private static final int RUN_PREVIEW_MAX_LENGTH = 320;

    /** 定时任务静默输出标记；调度器以该前缀表示本次输出不需要主动投递。 */
    private static final String SILENT_MARKER = "[SILENT]";

    /** 需要进入主动协作判断的失败状态关键词。 */
    private static final List<String> FAILURE_STATUSES =
            Arrays.asList(
                    "error",
                    "failed",
                    "failure",
                    "exception",
                    "timeout",
                    "pending_approval",
                    "失败",
                    "异常",
                    "超时");

    /** 输出中表示需要人工确认、继续处理或协作判断的关键词。 */
    private static final List<String> ACTIONABLE_OUTPUT_KEYWORDS =
            Arrays.asList(
                    "action required",
                    "needs action",
                    "please confirm",
                    "waiting confirmation",
                    "approval",
                    "review",
                    "follow up",
                    "需要处理",
                    "待处理",
                    "请确认",
                    "需要确认",
                    "等待确认",
                    "审批",
                    "审核",
                    "评审",
                    "跟进",
                    "继续");

    /** 定时任务仓储，用于读取任务定义和近期执行历史。 */
    private final CronJobRepository cronJobRepository;

    /**
     * 创建定时任务跟进采集器。
     *
     * @param cronJobRepository 定时任务仓储，必须支持列出任务和查询执行历史。
     */
    public CronFollowupCollector(CronJobRepository cronJobRepository) {
        this.cronJobRepository = cronJobRepository;
    }

    /** 返回定时任务跟进采集器的稳定名称。 */
    @Override
    public String name() {
        return COLLECTOR_NAME;
    }

    /** 主动协作开启且定时任务回看窗口大于 0 时才启用本采集器。 */
    @Override
    public boolean enabled(AppConfig config) {
        AppConfig.ProactiveConfig proactive = config == null ? null : config.getProactive();
        return proactive != null && proactive.isEnabled() && proactive.getCronLookbackDays() > 0;
    }

    /** 从定时任务定义和近期执行历史中采集需要跟进的任务观测。 */
    @Override
    public List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception {
        List<ProactiveObservation> observations = new ArrayList<ProactiveObservation>();
        if (context == null || !enabled(context.getConfig()) || cronJobRepository == null) {
            return observations;
        }
        List<CronJobRecord> jobs = cronJobRepository.listAll();
        if (jobs == null || jobs.isEmpty()) {
            return observations;
        }
        long cutoff = cutoffMillis(context);
        for (CronJobRecord job : jobs) {
            if (job == null || StrUtil.isBlank(job.getJobId())) {
                continue;
            }
            CronRunHistory runHistory = recentRuns(job.getJobId(), cutoff);
            inspectJob(job, runHistory, context.getNowMillis(), observations);
        }
        return observations;
    }

    /**
     * 根据配置计算定时任务回看窗口起点，异常大天数会被夹紧，避免乘法溢出。
     *
     * @param context 当前 tick 上下文。
     * @return 可用于过滤执行历史的起始毫秒时间戳。
     */
    private long cutoffMillis(ProactiveTickContext context) {
        int lookbackDays = context.getConfig().getProactive().getCronLookbackDays();
        return CollectorSupport.lookbackCutoffMillis(context.getNowMillis(), lookbackDays);
    }

    /**
     * 读取单个定时任务的近期执行历史；仓储异常会标记为历史不可用，避免用“无历史”做误判。
     *
     * @param jobId 定时任务 ID。
     * @param cutoffMillis 回看窗口起点。
     * @return 返回窗口内的执行历史列表。
     */
    private CronRunHistory recentRuns(String jobId, long cutoffMillis) {
        List<CronJobRunRecord> result = new ArrayList<CronJobRunRecord>();
        try {
            List<CronJobRunRecord> runs = cronJobRepository.listRuns(jobId, RECENT_RUN_LIMIT);
            if (runs == null || runs.isEmpty()) {
                return new CronRunHistory(result, true);
            }
            for (CronJobRunRecord run : runs) {
                if (run == null) {
                    continue;
                }
                long occurredAt = Math.max(run.getFinishedAt(), run.getStartedAt());
                if (occurredAt <= 0L || occurredAt >= cutoffMillis) {
                    result.add(run);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "定时任务执行历史读取失败，已标记历史不可用：jobId={}, errorType={}",
                    CollectorSupport.safe(jobId, 120),
                    e.getClass().getSimpleName());
            return new CronRunHistory(result, false);
        }
        return new CronRunHistory(result, true);
    }

    /**
     * 检查单个定时任务是否命中跟进规则，并按规则顺序输出高信号观测。
     *
     * @param job 定时任务记录。
     * @param recentRuns 近期执行历史。
     * @param nowMillis 当前 tick 时间。
     * @param observations 当前观测列表。
     */
    private void inspectJob(
            CronJobRecord job,
            CronRunHistory runHistory,
            long nowMillis,
            List<ProactiveObservation> observations) {
        List<CronJobRunRecord> recentRuns = runHistory.runs;
        if (isPaused(job)) {
            if (StrUtil.isNotBlank(job.getPausedReason())) {
                observations.add(buildObservation(job, "cron_paused_visible_reason", recentRuns, 0));
            }
            return;
        }
        if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
            observations.add(buildObservation(job, "cron_delivery_error", recentRuns, 0));
        }
        int failureCount = failureCount(job, recentRuns);
        if (failureCount >= REPEATED_FAILURE_THRESHOLD) {
            observations.add(
                    buildObservation(job, "cron_repeated_failure", recentRuns, failureCount));
        }
        if (isActive(job) && isDueButNotRun(job, runHistory, nowMillis)) {
            observations.add(buildObservation(job, "cron_due_not_run", recentRuns, 0));
        }
        if (hasActionableOutput(job, recentRuns)) {
            observations.add(buildObservation(job, "cron_actionable_output", recentRuns, 0));
        }
    }

    /**
     * 判断任务是否处于可自动运行状态。
     *
     * @param job 定时任务记录。
     * @return 状态为 ACTIVE 或空状态时返回 true。
     */
    private boolean isActive(CronJobRecord job) {
        String status = CollectorSupport.normalize(job.getStatus());
        return StrUtil.isBlank(status) || "active".equals(status);
    }

    /**
     * 判断任务是否暂停，暂停且带原因时可向用户发起低频跟进。
     *
     * @param job 定时任务记录。
     * @return 状态为 PAUSED 时返回 true。
     */
    private boolean isPaused(CronJobRecord job) {
        return "paused".equals(CollectorSupport.normalize(job.getStatus()));
    }

    /**
     * 统计近期失败次数，结合任务最近状态和执行历史判断重复失败。
     *
     * @param job 定时任务记录。
     * @param recentRuns 近期执行历史。
     * @return 返回失败证据数量。
     */
    private int failureCount(CronJobRecord job, List<CronJobRunRecord> recentRuns) {
        if (!recentRuns.isEmpty() && !isFailedRun(recentRuns.get(0))) {
            return 0;
        }
        int count = 0;
        for (CronJobRunRecord run : recentRuns) {
            if (!isFailedRun(run)) {
                break;
            }
            count++;
        }
        if (count == 0
                && (CollectorSupport.containsKeyword(job.getLastStatus(), FAILURE_STATUSES)
                        || StrUtil.isNotBlank(job.getLastError()))) {
            count = 1;
        }
        return count;
    }

    /**
     * 判断单次执行历史是否失败，投递错误也视为需要跟进的失败证据。
     *
     * @param run 执行历史记录。
     * @return 命中失败语义返回 true。
     */
    private boolean isFailedRun(CronJobRunRecord run) {
        return CollectorSupport.containsKeyword(run.getStatus(), FAILURE_STATUSES)
                || StrUtil.isNotBlank(run.getError())
                || StrUtil.isNotBlank(run.getDeliveryError());
    }

    /**
     * 判断任务是否已经到期但没有更新运行时间，说明调度可能未触发或被跳过。
     *
     * @param job 定时任务记录。
     * @param recentRuns 近期执行历史，用于修正任务主表可能滞后的情况。
     * @param nowMillis 当前 tick 时间。
     * @return 下次运行时间已过且任务主表和执行历史都没有同一时间后的运行记录时返回 true。
     */
    private boolean isDueButNotRun(
            CronJobRecord job, CronRunHistory runHistory, long nowMillis) {
        long nextRunAt = job.getNextRunAt();
        if (nextRunAt <= 0L || nextRunAt > nowMillis) {
            return false;
        }
        if (job.getLastRunAt() >= nextRunAt) {
            return false;
        }
        if (!runHistory.available) {
            return false;
        }
        for (CronJobRunRecord run : runHistory.runs) {
            long occurredAt = Math.max(run.getFinishedAt(), run.getStartedAt());
            if (occurredAt >= nextRunAt) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断任务最近输出或执行历史输出是否包含可行动事项，并忽略纯静默输出。
     *
     * @param job 定时任务记录。
     * @param recentRuns 近期执行历史。
     * @return 命中可行动语义返回 true。
     */
    private boolean hasActionableOutput(CronJobRecord job, List<CronJobRunRecord> recentRuns) {
        if (isActionableText(job.getLastOutput())) {
            return true;
        }
        for (CronJobRunRecord run : recentRuns) {
            if (isActionableText(run.getOutput()) || isActionableText(run.getSummary())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断文本是否是可行动输出，静默标记本身不应成为主动触达理由。
     *
     * @param text 候选输出文本。
     * @return 包含人工确认、审批、继续处理等关键词时返回 true。
     */
    private boolean isActionableText(String text) {
        return !isSilent(text) && CollectorSupport.containsKeyword(text, ACTIONABLE_OUTPUT_KEYWORDS);
    }

    /**
     * 判断文本是否以调度器静默标记开头。
     *
     * @param text 候选输出文本。
     * @return 以 [SILENT] 开头时返回 true。
     */
    private boolean isSilent(String text) {
        return StrUtil.isNotBlank(text)
                && text.trim().toUpperCase(Locale.ROOT).startsWith(SILENT_MARKER);
    }

    /**
     * 构造定时任务跟进观测，保留后续决策所需的结构化证据。
     *
     * @param job 定时任务记录。
     * @param type 观测类型。
     * @param recentRuns 近期执行历史证据。
     * @param failureCount 重复失败次数，非失败观测可传 0。
     * @return 返回主动协作观测。
     */
    private ProactiveObservation buildObservation(
            CronJobRecord job, String type, List<CronJobRunRecord> recentRuns, int failureCount) {
        ProactiveObservation observation = new ProactiveObservation();
        observation.setCollector(COLLECTOR_NAME);
        observation.setSourceKey(CollectorSupport.safe(sourceKey(job), 160));
        observation.setStatus("COLLECTED");
        observation.setSummary(summary(job, type));
        observation.setPayload(payload(job, type, recentRuns, failureCount));
        return observation;
    }

    /**
     * 构造观测载荷，统一脱敏所有可能来自用户、模型或外部平台的文本字段。
     *
     * @param job 定时任务记录。
     * @param type 观测类型。
     * @param recentRuns 近期执行历史证据。
     * @param failureCount 重复失败次数。
     * @return 返回结构化载荷。
     */
    private Map<String, Object> payload(
            CronJobRecord job, String type, List<CronJobRunRecord> recentRuns, int failureCount) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", type);
        payload.put("jobId", CollectorSupport.safe(job.getJobId(), 120));
        payload.put("name", CollectorSupport.safe(job.getName(), 160));
        payload.put("sourceKey", CollectorSupport.safe(sourceKey(job), 160));
        payload.put("status", CollectorSupport.safe(job.getStatus(), 80));
        payload.put("lastStatus", CollectorSupport.safe(job.getLastStatus(), 80));
        payload.put("evidence", evidencePayload(job, recentRuns, failureCount));
        return payload;
    }

    /**
     * 构造规则证据子载荷，避免上层按顶层 key 数量裁剪时丢失关键字段。
     *
     * @param job 定时任务记录。
     * @param recentRuns 近期执行历史证据。
     * @param failureCount 重复失败次数。
     * @return 返回保留关键错误、输出和时间证据的子载荷。
     */
    private Map<String, Object> evidencePayload(
            CronJobRecord job, List<CronJobRunRecord> recentRuns, int failureCount) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("lastError", CollectorSupport.safe(job.getLastError(), TEXT_MAX_LENGTH));
        evidence.put("deliveryError", CollectorSupport.safe(job.getLastDeliveryError(), TEXT_MAX_LENGTH));
        evidence.put("pausedReason", CollectorSupport.safe(job.getPausedReason(), TEXT_MAX_LENGTH));
        evidence.put("lastOutput", CollectorSupport.safe(job.getLastOutput(), TEXT_MAX_LENGTH));
        evidence.put("lastRunAt", Long.valueOf(job.getLastRunAt()));
        evidence.put("nextRunAt", Long.valueOf(job.getNextRunAt()));
        evidence.put("updatedAt", Long.valueOf(job.getUpdatedAt()));
        if (failureCount > 0) {
            evidence.put("failureCount", Integer.valueOf(failureCount));
        }
        if (!recentRuns.isEmpty()) {
            evidence.put("recentRuns", runPayloads(recentRuns));
        }
        return evidence;
    }

    /**
     * 构造近期执行历史载荷，保留状态、输出摘要、错误和投递错误作为证据。
     *
     * @param recentRuns 近期执行历史。
     * @return 返回已脱敏和裁剪的执行历史列表。
     */
    private List<Map<String, Object>> runPayloads(List<CronJobRunRecord> recentRuns) {
        List<Map<String, Object>> payloads = new ArrayList<Map<String, Object>>();
        for (CronJobRunRecord run : recentRuns) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("runId", CollectorSupport.safe(run.getRunId(), 120));
            payload.put("status", CollectorSupport.safe(run.getStatus(), 80));
            payload.put("triggerType", CollectorSupport.safe(run.getTriggerType(), 80));
            payload.put("output", CollectorSupport.safe(run.getOutput(), RUN_PREVIEW_MAX_LENGTH));
            payload.put("summary", CollectorSupport.safe(run.getSummary(), RUN_PREVIEW_MAX_LENGTH));
            payload.put("error", CollectorSupport.safe(run.getError(), RUN_PREVIEW_MAX_LENGTH));
            payload.put("deliveryError", CollectorSupport.safe(run.getDeliveryError(), RUN_PREVIEW_MAX_LENGTH));
            payload.put("startedAt", Long.valueOf(run.getStartedAt()));
            payload.put("finishedAt", Long.valueOf(run.getFinishedAt()));
            payloads.add(payload);
        }
        return payloads;
    }

    /**
     * 生成面向后续决策层的短摘要。
     *
     * @param job 定时任务记录。
     * @param type 观测类型。
     * @return 返回简短摘要。
     */
    private String summary(CronJobRecord job, String type) {
        return CollectorSupport.safe(
                type
                        + ": 定时任务 "
                        + StrUtil.blankToDefault(job.getJobId(), "unknown")
                        + " 需要主动协作评估",
                240);
    }

    /**
     * 解析观测来源键，优先使用任务来源键，缺失时回退到任务 ID。
     *
     * @param job 定时任务记录。
     * @return 返回稳定来源键。
     */
    private String sourceKey(CronJobRecord job) {
        if (StrUtil.isNotBlank(job.getSourceKey())) {
            return job.getSourceKey();
        }
        return "cron:" + StrUtil.blankToDefault(job.getJobId(), "unknown");
    }

    /** 定时任务执行历史读取结果，区分“确实没有历史”和“历史读取失败”。 */
    private static final class CronRunHistory {
        /** 近期执行历史列表，顺序沿用仓储的最新优先顺序。 */
        private final List<CronJobRunRecord> runs;

        /** 执行历史是否成功读取；false 时禁止依赖“无历史”推断未运行。 */
        private final boolean available;

        /**
         * 创建执行历史读取结果。
         *
         * @param runs 近期执行历史列表。
         * @param available 执行历史是否读取成功。
         */
        private CronRunHistory(List<CronJobRunRecord> runs, boolean available) {
            this.runs = runs;
            this.available = available;
        }
    }
}
