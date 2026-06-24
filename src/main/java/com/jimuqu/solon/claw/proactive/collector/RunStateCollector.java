package com.jimuqu.solon.claw.proactive.collector;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Agent 运行状态观测采集器，用于发现失败、可恢复、验证失败和排队等待的运行记录。 */
public class RunStateCollector implements ProactiveObservationCollector {
    /** 采集器内部日志，仅记录阶段和异常类型，避免暴露运行输入、工具参数或结果内容。 */
    private static final Logger log = LoggerFactory.getLogger(RunStateCollector.class);

    /** 采集器稳定名称，用于观测来源、排障和后续候选生成识别。 */
    public static final String COLLECTOR_NAME = "run_state";

    /** 单次搜索最多读取的近期运行数，避免主动协作 tick 扫描过大范围。 */
    private static final int RECENT_RUN_LIMIT = 200;

    /** 单次补充读取的可恢复运行数，用于覆盖搜索条件未命中的恢复队列。 */
    private static final int RECOVERABLE_RUN_LIMIT = 100;

    /** 单个运行最多纳入的失败工具调用数量，控制观测载荷大小。 */
    private static final int TOOL_ERROR_LIMIT = 5;

    /** payload 中短文本字段最大长度，避免原始错误或预览过长。 */
    private static final int TEXT_MAX_LENGTH = 600;

    /** 工具调用参数与结果预览最大长度，避免把长输出完整带入观测。 */
    private static final int TOOL_PREVIEW_MAX_LENGTH = 300;

    /** 表示运行已经顺利收尾的状态关键词。 */
    private static final List<String> SUCCESS_STATUSES =
            Arrays.asList("success", "completed", "complete", "done", "finished");

    /** 表示运行仍有排队消息或正在占用来源的状态关键词。 */
    private static final List<String> QUEUE_AWARE_STATUSES =
            Arrays.asList("queued", "waiting_approval", "backgrounded", "paused", "running", "interrupting");

    /** 表示运行失败或异常退出的关键词。 */
    private static final List<String> FAILURE_KEYWORDS =
            Arrays.asList(
                    "failed",
                    "failure",
                    "error",
                    "exception",
                    "abort",
                    "aborted",
                    "interrupt",
                    "interrupted",
                    "interrupting",
                    "busy_interrupt",
                    "cancelled",
                    "canceled",
                    "maximum steps",
                    "max steps",
                    "失败",
                    "异常",
                    "中止",
                    "终止",
                    "取消",
                    "打断");

    /** 表示验证、测试、构建或检查失败的关键词。 */
    private static final List<String> VERIFICATION_FAILURE_KEYWORDS =
            Arrays.asList(
                    "verify",
                    "verification",
                    "test failed",
                    "tests failed",
                    "build failed",
                    "lint failed",
                    "check failed",
                    "mvn test",
                    "npm test",
                    "验证失败",
                    "测试失败",
                    "构建失败",
                    "检查失败");

    /** 表示工具调用失败或需要重点纳入证据的状态关键词。 */
    private static final List<String> TOOL_FAILURE_STATUSES =
            Arrays.asList("failed", "failure", "error", "exception", "timeout", "retry_failed");

    /** Agent 运行仓储，用于读取近期运行、可恢复运行、工具调用和排队消息数量。 */
    private final AgentRunRepository agentRunRepository;

    /**
     * 创建运行状态采集器。
     *
     * @param agentRunRepository Agent 运行仓储，必须支持运行搜索、恢复列表和工具调用查询。
     */
    public RunStateCollector(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    /** 返回运行状态采集器的稳定名称。 */
    @Override
    public String name() {
        return COLLECTOR_NAME;
    }

    /** 主动协作开启且运行回看窗口大于 0 时才启用本采集器。 */
    @Override
    public boolean enabled(AppConfig config) {
        AppConfig.ProactiveConfig proactive = config == null ? null : config.getProactive();
        return proactive != null && proactive.isEnabled() && proactive.getRunLookbackDays() > 0;
    }

    /** 从近期 Agent 运行记录中采集需要跟进的运行状态观测。 */
    @Override
    public List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception {
        List<ProactiveObservation> observations = new ArrayList<ProactiveObservation>();
        if (context == null || !enabled(context.getConfig()) || agentRunRepository == null) {
            return observations;
        }
        long now = context.getNowMillis();
        long cutoff = cutoffMillis(context);
        Map<String, AgentRunRecord> runs = new LinkedHashMap<String, AgentRunRecord>();
        addUniqueRuns(
                runs,
                agentRunRepository.searchRuns(
                        null, null, null, null, cutoff, now, RECENT_RUN_LIMIT));
        addUniqueRuns(runs, agentRunRepository.listRecoverable(RECOVERABLE_RUN_LIMIT));

        Set<String> emitted = new HashSet<String>();
        for (AgentRunRecord run : runs.values()) {
            if (run == null || StrUtil.isBlank(run.getRunId())) {
                continue;
            }
            inspectRun(run, observations, emitted);
        }
        return observations;
    }

    /**
     * 根据配置计算运行回看窗口起点，异常大天数会被夹紧，避免乘法溢出。
     *
     * @param context 当前 tick 上下文。
     * @return 可用于仓储查询的起始毫秒时间戳。
     */
    private long cutoffMillis(ProactiveTickContext context) {
        int lookbackDays = context.getConfig().getProactive().getRunLookbackDays();
        return CollectorSupport.lookbackCutoffMillis(context.getNowMillis(), lookbackDays);
    }

    /**
     * 防御式追加仓储返回列表，并按 runId 去重，避免同一运行来自多个查询入口时重复读取明细。
     *
     * @param target 按 runId 保存的目标运行集合。
     * @param values 仓储返回的候选列表。
     */
    private void addUniqueRuns(Map<String, AgentRunRecord> target, List<AgentRunRecord> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (AgentRunRecord run : values) {
            if (run == null || StrUtil.isBlank(run.getRunId())) {
                continue;
            }
            AgentRunRecord existing = target.get(run.getRunId());
            if (existing == null || shouldReplaceDuplicateRun(existing, run)) {
                target.put(run.getRunId(), run);
            }
        }
    }

    /**
     * 选择重复 runId 的代表记录；优先保留带有可恢复、错误或排队信号的记录，避免去重丢失跟进依据。
     *
     * @param existing 已保存的运行记录。
     * @param candidate 新查询到的同 runId 运行记录。
     * @return 新记录信号更完整时返回 true。
     */
    private boolean shouldReplaceDuplicateRun(AgentRunRecord existing, AgentRunRecord candidate) {
        return signalScore(candidate) > signalScore(existing);
    }

    /**
     * 计算运行记录的主动协作信号分数，仅用于同 runId 去重时挑选更完整的记录。
     *
     * @param run Agent 运行记录。
     * @return 返回越大表示记录携带的跟进信号越强。
     */
    private int signalScore(AgentRunRecord run) {
        int score = 0;
        if (isRecoverableRun(run)) {
            score += 4;
        }
        if (StrUtil.isNotBlank(run.getError())) {
            score += 3;
        }
        if (CollectorSupport.containsKeyword(run.getStatus(), FAILURE_KEYWORDS)
                || CollectorSupport.containsKeyword(run.getExitReason(), FAILURE_KEYWORDS)) {
            score += 2;
        }
        if (CollectorSupport.containsKeyword(run.getStatus(), QUEUE_AWARE_STATUSES)
                || run.isBackgrounded()
                || CollectorSupport.containsKeyword(run.getPhase(), Arrays.asList("queue", "queued", "waiting"))
                || CollectorSupport.containsKeyword(run.getBusyPolicy(), Arrays.asList("queue", "queued"))) {
            score += 1;
        }
        return score;
    }

    /**
     * 检查单个运行记录是否命中运行状态观测，并保证同一运行同一类型只产出一次。
     *
     * @param run Agent 运行记录。
     * @param observations 当前 tick 的观测列表。
     * @param emitted 已产出的 runId/type 去重集合。
     */
    private void inspectRun(
            AgentRunRecord run, List<ProactiveObservation> observations, Set<String> emitted)
            throws Exception {
        int queuedMessages = queuedMessages(run);
        List<Map<String, Object>> toolErrors = failedToolCalls(run.getRunId());
        if (!isSuccessfulFinished(run) && isRecoverableRun(run)) {
            emit(run, "run_recoverable", toolErrors, queuedMessages, observations, emitted);
        }
        if (!isSuccessfulFinished(run) && isVerificationFailed(run, toolErrors)) {
            emit(run, "verification_failed", toolErrors, queuedMessages, observations, emitted);
        }
        if (isFailedNeedsFollowup(run, toolErrors)) {
            emit(
                    run,
                    "run_failed_needs_followup",
                    toolErrors,
                    queuedMessages,
                    observations,
                    emitted);
        }
        if (isQueuedWorkWaiting(run, queuedMessages)) {
            emit(run, "queued_work_waiting", toolErrors, queuedMessages, observations, emitted);
        }
    }

    /**
     * 判断运行是否可恢复，来源包括显式 recoverable 标记、状态值和恢复提示。
     *
     * @param run Agent 运行记录。
     * @return 命中可恢复语义返回 true。
     */
    private boolean isRecoverableRun(AgentRunRecord run) {
        return run.isRecoverable()
                || "recoverable".equals(CollectorSupport.normalize(run.getStatus()))
                || StrUtil.isNotBlank(run.getRecoveryHint());
    }

    /**
     * 判断运行是否是需要跟进的失败，不把已经成功完成的心跳记录误报为失败。
     *
     * @param run Agent 运行记录。
     * @param toolErrors 失败工具调用证据。
     * @return 命中失败跟进语义返回 true。
     */
    private boolean isFailedNeedsFollowup(AgentRunRecord run, List<Map<String, Object>> toolErrors) {
        if (isSuccessfulFinished(run)) {
            return false;
        }
        return CollectorSupport.containsKeyword(run.getStatus(), FAILURE_KEYWORDS)
                || StrUtil.isNotBlank(run.getError())
                || CollectorSupport.containsKeyword(run.getExitReason(), FAILURE_KEYWORDS)
                || CollectorSupport.containsKeyword(run.getFinalReplyPreview(), FAILURE_KEYWORDS)
                || !toolErrors.isEmpty();
    }

    /**
     * 判断运行是否有验证、测试、构建或检查失败证据。
     *
     * @param run Agent 运行记录。
     * @param toolErrors 失败工具调用证据。
     * @return 命中验证失败语义返回 true。
     */
    private boolean isVerificationFailed(
            AgentRunRecord run, List<Map<String, Object>> toolErrors) {
        StringBuilder evidence = new StringBuilder();
        CollectorSupport.appendText(evidence, run.getStatus());
        CollectorSupport.appendText(evidence, run.getExitReason());
        CollectorSupport.appendText(evidence, run.getInputPreview());
        CollectorSupport.appendText(evidence, run.getFinalReplyPreview());
        CollectorSupport.appendText(evidence, run.getError());
        for (Map<String, Object> toolError : toolErrors) {
            CollectorSupport.appendText(evidence, String.valueOf(toolError.get("toolName")));
            CollectorSupport.appendText(evidence, String.valueOf(toolError.get("argsPreview")));
            CollectorSupport.appendText(evidence, String.valueOf(toolError.get("resultPreview")));
            CollectorSupport.appendText(evidence, String.valueOf(toolError.get("error")));
        }
        return CollectorSupport.containsKeyword(evidence.toString(), VERIFICATION_FAILURE_KEYWORDS);
    }

    /**
     * 判断运行是否存在待处理排队消息；成功完成的运行不因残留计数产生观测。
     *
     * @param run Agent 运行记录。
     * @param queuedMessages 当前来源和会话的待处理消息数量。
     * @return 命中排队等待语义返回 true。
     */
    private boolean isQueuedWorkWaiting(AgentRunRecord run, int queuedMessages) {
        if (queuedMessages <= 0 || isSuccessfulFinished(run)) {
            return false;
        }
        return CollectorSupport.containsKeyword(run.getStatus(), QUEUE_AWARE_STATUSES)
                || run.isBackgrounded()
                || CollectorSupport.containsKeyword(run.getPhase(), Arrays.asList("queue", "queued", "waiting"))
                || CollectorSupport.containsKeyword(run.getBusyPolicy(), Arrays.asList("queue", "queued"));
    }

    /**
     * 判断运行是否已经成功完成，用于排除已解决的短暂 busy 心跳记录。
     *
     * @param run Agent 运行记录。
     * @return 成功状态或成功退出原因返回 true。
     */
    private boolean isSuccessfulFinished(AgentRunRecord run) {
        boolean successStatus = containsExactNormalized(run.getStatus(), SUCCESS_STATUSES);
        boolean successExit = containsExactNormalized(run.getExitReason(), SUCCESS_STATUSES);
        return (successStatus || successExit) && run.getFinishedAt() > 0L;
    }

    /**
     * 查询并整理失败工具调用证据，仓储异常或 null 返回不会中断采集。
     *
     * @param runId 运行标识。
     * @return 返回已脱敏、已裁剪的失败工具调用证据列表。
     */
    private List<Map<String, Object>> failedToolCalls(String runId) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try {
            List<ToolCallRecord> toolCalls = agentRunRepository.listToolCalls(runId);
            if (toolCalls == null || toolCalls.isEmpty()) {
                return result;
            }
            Set<String> repeatedKeys = repeatedFailureKeys(toolCalls);
            for (ToolCallRecord toolCall : toolCalls) {
                if (toolCall == null || !isFailedToolCall(toolCall, repeatedKeys)) {
                    continue;
                }
                result.add(toolCallPayload(toolCall));
                if (result.size() >= TOOL_ERROR_LIMIT) {
                    return result;
                }
            }
        } catch (Exception e) {
            logRecoverableCollectionFailure("list_failed_tool_calls", e);
            return result;
        }
        return result;
    }

    /**
     * 统计同一工具和错误组合的失败次数，用于识别重复失败工具调用。
     *
     * @param toolCalls 运行内工具调用列表。
     * @return 返回出现两次及以上的失败组合键。
     */
    private Set<String> repeatedFailureKeys(List<ToolCallRecord> toolCalls) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (ToolCallRecord toolCall : toolCalls) {
            if (toolCall == null || !looksToolFailure(toolCall)) {
                continue;
            }
            String key = CollectorSupport.normalize(toolCall.getToolName()) + "|" + CollectorSupport.normalize(toolCall.getError());
            counts.put(key, Integer.valueOf(counts.containsKey(key) ? counts.get(key) + 1 : 1));
        }
        Set<String> repeated = new HashSet<String>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue().intValue() > 1) {
                repeated.add(entry.getKey());
            }
        }
        return repeated;
    }

    /**
     * 判断工具调用是否应纳入失败证据，包含显式失败和重复失败。
     *
     * @param toolCall 工具调用记录。
     * @param repeatedKeys 重复失败组合键。
     * @return 需要纳入观测证据返回 true。
     */
    private boolean isFailedToolCall(ToolCallRecord toolCall, Set<String> repeatedKeys) {
        String key = CollectorSupport.normalize(toolCall.getToolName()) + "|" + CollectorSupport.normalize(toolCall.getError());
        return looksToolFailure(toolCall) || repeatedKeys.contains(key);
    }

    /**
     * 判断工具调用状态、错误或结果预览是否体现失败。
     *
     * @param toolCall 工具调用记录。
     * @return 命中失败语义返回 true。
     */
    private boolean looksToolFailure(ToolCallRecord toolCall) {
        return CollectorSupport.containsKeyword(toolCall.getStatus(), TOOL_FAILURE_STATUSES)
                || StrUtil.isNotBlank(toolCall.getError())
                || CollectorSupport.containsKeyword(toolCall.getResultPreview(), FAILURE_KEYWORDS);
    }

    /**
     * 构造单条工具调用失败证据，所有文本均先脱敏再裁剪。
     *
     * @param toolCall 工具调用记录。
     * @return 返回结构化工具调用证据。
     */
    private Map<String, Object> toolCallPayload(ToolCallRecord toolCall) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("toolCallId", CollectorSupport.safe(toolCall.getToolCallId(), 120));
        payload.put("toolName", CollectorSupport.safe(toolCall.getToolName(), 120));
        payload.put("status", CollectorSupport.safe(toolCall.getStatus(), 80));
        payload.put("argsPreview", CollectorSupport.safe(toolCall.getArgsPreview(), TOOL_PREVIEW_MAX_LENGTH));
        payload.put("resultPreview", CollectorSupport.safe(toolCall.getResultPreview(), TOOL_PREVIEW_MAX_LENGTH));
        payload.put("error", CollectorSupport.safe(toolCall.getError(), TOOL_PREVIEW_MAX_LENGTH));
        payload.put("finishedAt", Long.valueOf(toolCall.getFinishedAt()));
        return payload;
    }

    /**
     * 安全读取当前运行来源和会话的排队消息数量，仓储异常时按无排队处理。
     *
     * @param run Agent 运行记录。
     * @return 返回待处理消息数量。
     */
    private int queuedMessages(AgentRunRecord run) {
        if (StrUtil.isBlank(run.getSourceKey()) || StrUtil.isBlank(run.getSessionId())) {
            return 0;
        }
        try {
            return Math.max(0, agentRunRepository.countQueuedMessages(run.getSourceKey(), run.getSessionId()));
        } catch (Exception e) {
            logRecoverableCollectionFailure("count_queued_messages", e);
            return 0;
        }
    }

    /**
     * 写入单条观测，并通过 runId/type 在内存中去重。
     *
     * @param run Agent 运行记录。
     * @param type 观测类型。
     * @param toolErrors 失败工具调用证据。
     * @param queuedMessages 待处理消息数量。
     * @param observations 当前 tick 的观测列表。
     * @param emitted 已产出观测去重集合。
     */
    private void emit(
            AgentRunRecord run,
            String type,
            List<Map<String, Object>> toolErrors,
            int queuedMessages,
            List<ProactiveObservation> observations,
            Set<String> emitted) {
        String key = run.getRunId() + "|" + type;
        if (!emitted.add(key)) {
            return;
        }
        ProactiveObservation observation = new ProactiveObservation();
        observation.setCollector(COLLECTOR_NAME);
        observation.setSourceKey(CollectorSupport.safe(sourceKey(run), 160));
        observation.setSummary(summary(run, type));
        observation.setStatus("COLLECTED");
        observation.setPayload(payload(run, type, toolErrors, queuedMessages));
        observations.add(observation);
    }

    /**
     * 构造运行状态观测载荷，保留后续决策所需的结构化证据。
     *
     * @param run Agent 运行记录。
     * @param type 观测类型。
     * @param toolErrors 失败工具调用证据。
     * @param queuedMessages 待处理消息数量。
     * @return 返回结构化载荷。
     */
    private Map<String, Object> payload(
            AgentRunRecord run,
            String type,
            List<Map<String, Object>> toolErrors,
            int queuedMessages) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", type);
        payload.put("runId", CollectorSupport.safe(run.getRunId(), 120));
        payload.put("sessionId", CollectorSupport.safe(run.getSessionId(), 120));
        payload.put("sourceKey", CollectorSupport.safe(sourceKey(run), 160));
        payload.put("status", CollectorSupport.safe(run.getStatus(), 80));
        payload.put("phase", CollectorSupport.safe(run.getPhase(), 120));
        payload.put("exitReason", CollectorSupport.safe(run.getExitReason(), 160));
        payload.put("recoverable", Boolean.valueOf(run.isRecoverable()));
        payload.put("recoveryHint", CollectorSupport.safe(run.getRecoveryHint(), TEXT_MAX_LENGTH));
        payload.put("inputPreview", CollectorSupport.safe(run.getInputPreview(), TEXT_MAX_LENGTH));
        payload.put("finalReplyPreview", CollectorSupport.safe(run.getFinalReplyPreview(), TEXT_MAX_LENGTH));
        payload.put("error", CollectorSupport.safe(run.getError(), TEXT_MAX_LENGTH));
        payload.put("lastActivityAt", Long.valueOf(run.getLastActivityAt()));
        payload.put("finishedAt", Long.valueOf(run.getFinishedAt()));
        if (!toolErrors.isEmpty()) {
            payload.put("toolErrors", toolErrors);
        }
        if (queuedMessages > 0) {
            payload.put("queuedMessages", Integer.valueOf(queuedMessages));
        }
        return payload;
    }

    /**
     * 生成面向后续决策层的短摘要。
     *
     * @param run Agent 运行记录。
     * @param type 观测类型。
     * @return 返回简短摘要。
     */
    private String summary(AgentRunRecord run, String type) {
        return CollectorSupport.safe(
                type
                        + ": 运行 "
                        + StrUtil.blankToDefault(run.getRunId(), "unknown")
                        + " 状态 "
                        + StrUtil.blankToDefault(run.getStatus(), "unknown")
                        + " 需要主动协作评估",
                240);
    }

    /**
     * 解析观测来源键，优先使用运行记录来源，缺失时回退到 runId。
     *
     * @param run Agent 运行记录。
     * @return 返回稳定来源键。
     */
    private String sourceKey(AgentRunRecord run) {
        if (StrUtil.isNotBlank(run.getSourceKey())) {
            return run.getSourceKey();
        }
        return "run:" + StrUtil.blankToDefault(run.getRunId(), "unknown");
    }

    /**
     * 判断规范化文本是否精确匹配枚举值，避免 incomplete、finished_failed 这类状态被误判为成功。
     *
     * @param text 候选文本。
     * @param keywords 允许的枚举值。
     * @return 精确命中时返回 true。
     */
    private boolean containsExactNormalized(String text, List<String> keywords) {
        String value = CollectorSupport.normalize(text);
        if (StrUtil.isBlank(value) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StrUtil.isNotBlank(keyword)
                    && value.equals(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记录运行状态采集的可恢复失败；日志只写阶段和异常类型，不输出运行输入、工具内容或异常消息。
     *
     * @param stage 失败阶段。
     * @param error 原始异常。
     */
    private void logRecoverableCollectionFailure(String stage, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "run state collector fallback: stage={}, errorType={}",
                    stage,
                    CollectorSupport.exceptionType(error));
        }
    }
}
