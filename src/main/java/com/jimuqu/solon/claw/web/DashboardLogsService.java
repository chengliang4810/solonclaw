package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Dashboard 日志读取服务。 */
public class DashboardLogsService {
    /** 注入应用配置，用于控制台Logs。 */
    private final AppConfig appConfig;

    /** Agent 运行仓储用于在日志查询中补充工具与运行事件索引。 */
    private final AgentRunRepository agentRunRepository;

    /** 定时任务仓储用于在日志查询中补充 cron 运行历史索引。 */
    private final CronJobRepository cronJobRepository;

    /**
     * 创建控制台Logs服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public DashboardLogsService(AppConfig appConfig) {
        this(appConfig, null, null);
    }

    /**
     * 创建控制台Logs服务实例，并注入运行轨迹索引依赖。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunRepository Agent 运行仓储，用于查询工具事件索引。
     */
    public DashboardLogsService(AppConfig appConfig, AgentRunRepository agentRunRepository) {
        this(appConfig, agentRunRepository, null);
    }

    /**
     * 创建控制台Logs服务实例，并注入所有结构化运行索引依赖。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunRepository Agent 运行仓储，用于查询工具事件索引。
     * @param cronJobRepository 定时任务仓储，用于查询 cron 执行历史。
     */
    public DashboardLogsService(
            AppConfig appConfig,
            AgentRunRepository agentRunRepository,
            CronJobRepository cronJobRepository) {
        this.appConfig = appConfig;
        this.agentRunRepository = agentRunRepository;
        this.cronJobRepository = cronJobRepository;
    }

    /**
     * 执行read相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @param lineCount 行Count参数。
     * @param level level 参数。
     * @param component component 参数。
     * @param query 日志关键字，便于在大日志文件中定位本轮回归 marker 或异常片段。
     * @return 返回read结果。
     */
    public List<String> read(
            String fileName, int lineCount, String level, String component, String query) {
        File file = resolveFile(fileName);

        List<String> all = file.exists() ? FileUtil.readUtf8Lines(file) : Collections.emptyList();
        List<String> filtered = new ArrayList<String>();
        for (String line : all) {
            if (!matchesLevel(line, level)) {
                continue;
            }
            if (!matchesComponent(line, component)) {
                continue;
            }
            if (!matchesQuery(line, query)) {
                continue;
            }
            filtered.add(SecretRedactor.redact(line, 2000));
        }
        appendStructuredIndexMatches(filtered, query, lineCount);

        int safeLineCount = lineCount <= 0 ? 100 : Math.min(lineCount, 500);
        int start = Math.max(0, filtered.size() - safeLineCount);
        return new ArrayList<String>(filtered.subList(start, filtered.size()));
    }

    /**
     * 读取日志，并在未传关键字时按当前筛选条件直接返回最近日志。
     *
     * @param fileName 文件或目录路径参数。
     * @param lineCount 行Count参数。
     * @param level level 参数。
     * @param component component 参数。
     * @return 返回read结果。
     */
    public List<String> read(String fileName, int lineCount, String level, String component) {
        return read(fileName, lineCount, level, component, null);
    }

    /**
     * 解析文件。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回解析后的文件。
     */
    private File resolveFile(String fileName) {
        String resolved = StrUtil.blankToDefault(fileName, "agent").toLowerCase(Locale.ROOT);
        if (!"gateway".equals(resolved) && !"errors".equals(resolved)) {
            resolved = "agent";
        }
        return FileUtil.file(appConfig.getRuntime().getLogsDir(), resolved + ".log");
    }

    /**
     * 判断是否匹配级别。
     *
     * @param line 行参数。
     * @param level level 参数。
     * @return 返回matches级别结果。
     */
    private boolean matchesLevel(String line, String level) {
        String normalized = StrUtil.blankToDefault(level, "ALL").toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return true;
        }
        return line.toUpperCase(Locale.ROOT).contains(" " + normalized + " ");
    }

    /**
     * 判断是否匹配Component。
     *
     * @param line 行参数。
     * @param component component 参数。
     * @return 返回matches Component结果。
     */
    private boolean matchesComponent(String line, String component) {
        String normalized = StrUtil.blankToDefault(component, "all").toLowerCase(Locale.ROOT);
        if ("all".equals(normalized)) {
            return true;
        }
        if ("gateway".equals(normalized)) {
            return line.contains(".gateway.");
        }
        if ("tools".equals(normalized)) {
            return line.contains(".tool.");
        }
        if ("cron".equals(normalized)) {
            return line.contains(".scheduler.");
        }
        if ("proactive".equals(normalized)) {
            return line.contains(".proactive.");
        }
        if ("agent".equals(normalized)) {
            return line.contains("com.jimuqu.solon.claw");
        }
        return line.toLowerCase(Locale.ROOT).contains(normalized);
    }

    /**
     * 判断日志行是否匹配关键字过滤；关键字为空时保持原有读取行为。
     *
     * @param line 日志行内容。
     * @param query 用户输入的日志关键字。
     * @return 返回关键字匹配结果。
     */
    private boolean matchesQuery(String line, String query) {
        String normalized = StrUtil.trimToEmpty(query).toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(normalized)) {
            return true;
        }
        return line.toLowerCase(Locale.ROOT).contains(normalized);
    }

    /**
     * 在日志关键字查询中追加结构化运行索引命中，补足文件日志没有直接记录的执行历史。
     *
     * @param filtered 已从日志文件中过滤出的行。
     * @param query 用户输入的日志关键字。
     * @param lineCount 用户请求的最大行数。
     */
    private void appendStructuredIndexMatches(List<String> filtered, String query, int lineCount) {
        appendRunIndexMatches(filtered, query, lineCount);
        appendCronIndexMatches(filtered, query, lineCount);
    }

    /**
     * 在日志关键字查询中追加运行和工具调用索引命中，弥补文件日志未记录工具参数的问题。
     *
     * @param filtered 已从日志文件中过滤出的行。
     * @param query 用户输入的日志关键字。
     * @param lineCount 用户请求的最大行数。
     */
    private void appendRunIndexMatches(List<String> filtered, String query, int lineCount) {
        if (agentRunRepository == null || StrUtil.isBlank(query)) {
            return;
        }
        int safeLineCount = lineCount <= 0 ? 100 : Math.min(lineCount, 500);
        int searchLimit = Math.max(5, Math.min(safeLineCount, 50));
        try {
            Set<String> matchedRunIds = new LinkedHashSet<String>();
            Set<String> appendedToolCallIds = new LinkedHashSet<String>();
            for (AgentRunRecord run :
                    agentRunRepository.searchRuns(null, null, null, query, 0L, 0L, searchLimit)) {
                filtered.add(formatRunIndexLine(run));
                if (StrUtil.isNotBlank(run.getRunId())) {
                    matchedRunIds.add(run.getRunId());
                }
            }
            for (ToolCallRecord tool :
                    agentRunRepository.searchToolCalls(
                            null, null, null, null, query, 0L, 0L, searchLimit)) {
                appendToolIndexLine(filtered, appendedToolCallIds, tool);
            }
            for (String runId : matchedRunIds) {
                for (ToolCallRecord tool :
                        agentRunRepository.searchToolCalls(
                                null, null, runId, null, null, 0L, 0L, searchLimit)) {
                    appendToolIndexLine(filtered, appendedToolCallIds, tool);
                }
            }
        } catch (Exception e) {
            filtered.add(
                    SecretRedactor.redact(
                            "run-index:error Dashboard 日志运行索引查询失败: " + e.getMessage(), 2000));
        }
    }

    /**
     * 在日志关键字查询中追加 cron 任务与运行历史索引命中，便于从日志页定位定时任务结果。
     *
     * @param filtered 已从日志文件中过滤出的行。
     * @param query 用户输入的日志关键字。
     * @param lineCount 用户请求的最大行数。
     */
    private void appendCronIndexMatches(List<String> filtered, String query, int lineCount) {
        if (cronJobRepository == null || StrUtil.isBlank(query)) {
            return;
        }
        String normalizedQuery = StrUtil.trimToEmpty(query).toLowerCase(Locale.ROOT);
        int safeLineCount = lineCount <= 0 ? 100 : Math.min(lineCount, 500);
        int searchLimit = Math.max(5, Math.min(safeLineCount, 50));
        try {
            Set<String> appendedJobIds = new LinkedHashSet<String>();
            Set<String> appendedRunIds = new LinkedHashSet<String>();
            for (CronJobRecord job : cronJobRepository.listAll()) {
                boolean jobMatched = matchesCronJob(job, normalizedQuery);
                List<CronJobRunRecord> matchedRuns = new ArrayList<CronJobRunRecord>();
                for (CronJobRunRecord run :
                        cronJobRepository.listRuns(job.getJobId(), searchLimit)) {
                    if (jobMatched || matchesCronRun(run, normalizedQuery)) {
                        matchedRuns.add(run);
                    }
                }
                if (jobMatched || !matchedRuns.isEmpty()) {
                    appendCronJobIndexLine(filtered, appendedJobIds, job);
                }
                for (CronJobRunRecord run : matchedRuns) {
                    appendCronRunIndexLine(filtered, appendedRunIds, run);
                }
            }
        } catch (Exception e) {
            filtered.add(
                    SecretRedactor.redact(
                            "cron-index:error Dashboard 日志定时任务索引查询失败: " + e.getMessage(), 2000));
        }
    }

    /**
     * 判断 cron 任务元数据是否匹配日志关键字。
     *
     * @param job cron 任务记录。
     * @param normalizedQuery 已小写归一化的查询词。
     * @return 如果任务元数据包含查询词则返回 true。
     */
    private boolean matchesCronJob(CronJobRecord job, String normalizedQuery) {
        if (job == null) {
            return false;
        }
        return containsNormalizedQuery(job.getJobId(), normalizedQuery)
                || containsNormalizedQuery(job.getName(), normalizedQuery)
                || containsNormalizedQuery(job.getCronExpr(), normalizedQuery)
                || containsNormalizedQuery(job.getPrompt(), normalizedQuery)
                || containsNormalizedQuery(job.getDeliverPlatform(), normalizedQuery)
                || containsNormalizedQuery(job.getScript(), normalizedQuery)
                || containsNormalizedQuery(job.getLastStatus(), normalizedQuery)
                || containsNormalizedQuery(job.getLastError(), normalizedQuery)
                || containsNormalizedQuery(job.getLastDeliveryError(), normalizedQuery)
                || containsNormalizedQuery(job.getLastOutput(), normalizedQuery);
    }

    /**
     * 判断 cron 单次运行历史是否匹配日志关键字。
     *
     * @param run cron 运行历史记录。
     * @param normalizedQuery 已小写归一化的查询词。
     * @return 如果运行历史包含查询词则返回 true。
     */
    private boolean matchesCronRun(CronJobRunRecord run, String normalizedQuery) {
        if (run == null) {
            return false;
        }
        return containsNormalizedQuery(run.getRunId(), normalizedQuery)
                || containsNormalizedQuery(run.getJobId(), normalizedQuery)
                || containsNormalizedQuery(run.getTriggerType(), normalizedQuery)
                || containsNormalizedQuery(run.getStatus(), normalizedQuery)
                || containsNormalizedQuery(run.getOutput(), normalizedQuery)
                || containsNormalizedQuery(run.getError(), normalizedQuery)
                || containsNormalizedQuery(run.getDeliveryError(), normalizedQuery)
                || containsNormalizedQuery(run.getDeliveryResultJson(), normalizedQuery)
                || containsNormalizedQuery(run.getSummary(), normalizedQuery);
    }

    /**
     * 使用已归一化查询词执行空安全包含判断。
     *
     * @param value 待检查文本。
     * @param normalizedQuery 已小写归一化的查询词。
     * @return 如果文本包含查询词则返回 true。
     */
    private boolean containsNormalizedQuery(String value, String normalizedQuery) {
        if (StrUtil.isBlank(value) || StrUtil.isBlank(normalizedQuery)) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    /**
     * 追加 cron 任务索引行并按任务标识去重。
     *
     * @param filtered 已从日志文件中过滤出的行。
     * @param appendedJobIds 已追加过的任务标识集合。
     * @param job cron 任务记录。
     */
    private void appendCronJobIndexLine(
            List<String> filtered, Set<String> appendedJobIds, CronJobRecord job) {
        String jobId = StrUtil.nullToDefault(job.getJobId(), "");
        if (!appendedJobIds.add(jobId)) {
            return;
        }
        filtered.add(formatCronJobIndexLine(job));
    }

    /**
     * 追加 cron 运行索引行并按运行标识去重。
     *
     * @param filtered 已从日志文件中过滤出的行。
     * @param appendedRunIds 已追加过的运行标识集合。
     * @param run cron 运行历史记录。
     */
    private void appendCronRunIndexLine(
            List<String> filtered, Set<String> appendedRunIds, CronJobRunRecord run) {
        String runId = StrUtil.nullToDefault(run.getRunId(), "");
        String dedupeKey =
                StrUtil.isBlank(runId)
                        ? StrUtil.nullToEmpty(run.getJobId())
                                + ":"
                                + run.getStartedAt()
                                + ":"
                                + run.getAttempt()
                        : runId;
        if (!appendedRunIds.add(dedupeKey)) {
            return;
        }
        filtered.add(formatCronRunIndexLine(run));
    }

    /**
     * 追加工具索引行并按工具调用标识去重，避免关键词和运行关联查询重复展示同一调用。
     *
     * @param filtered 已从日志文件中过滤出的行。
     * @param appendedToolCallIds 已追加过的工具调用标识集合。
     * @param tool 工具调用记录。
     */
    private void appendToolIndexLine(
            List<String> filtered, Set<String> appendedToolCallIds, ToolCallRecord tool) {
        String toolCallId = StrUtil.nullToDefault(tool.getToolCallId(), "");
        String dedupeKey =
                StrUtil.isBlank(toolCallId)
                        ? StrUtil.nullToEmpty(tool.getRunId())
                                + ":"
                                + StrUtil.nullToEmpty(tool.getToolName())
                                + ":"
                                + tool.getStartedAt()
                        : toolCallId;
        if (!appendedToolCallIds.add(dedupeKey)) {
            return;
        }
        filtered.add(formatToolIndexLine(tool));
    }

    /**
     * 格式化运行索引命中，保持 /api/logs 的字符串行返回契约。
     *
     * @param run 运行记录。
     * @return 返回可展示的索引日志行。
     */
    private String formatRunIndexLine(AgentRunRecord run) {
        return SecretRedactor.redact(
                "run-index:run"
                        + " run_id="
                        + StrUtil.nullToEmpty(run.getRunId())
                        + " session_id="
                        + StrUtil.nullToEmpty(run.getSessionId())
                        + " status="
                        + StrUtil.nullToEmpty(run.getStatus())
                        + " phase="
                        + StrUtil.nullToEmpty(run.getPhase())
                        + " input="
                        + StrUtil.nullToEmpty(run.getInputPreview())
                        + " final="
                        + StrUtil.nullToEmpty(run.getFinalReplyPreview()),
                2000);
    }

    /**
     * 格式化工具调用索引命中，保留工具名、状态和参数摘要。
     *
     * @param tool 工具调用记录。
     * @return 返回可展示的索引日志行。
     */
    private String formatToolIndexLine(ToolCallRecord tool) {
        return SecretRedactor.redact(
                "run-index:tool"
                        + " run_id="
                        + StrUtil.nullToEmpty(tool.getRunId())
                        + " session_id="
                        + StrUtil.nullToEmpty(tool.getSessionId())
                        + " tool="
                        + StrUtil.nullToEmpty(tool.getToolName())
                        + " status="
                        + StrUtil.nullToEmpty(tool.getStatus())
                        + " args="
                        + StrUtil.nullToEmpty(tool.getArgsPreview())
                        + " result="
                        + StrUtil.nullToEmpty(tool.getResultPreview())
                        + " error="
                        + StrUtil.nullToEmpty(tool.getError()),
                2000);
    }

    /**
     * 格式化 cron 任务索引命中，保持 /api/logs 的字符串行返回契约。
     *
     * @param job cron 任务记录。
     * @return 返回可展示的索引日志行。
     */
    private String formatCronJobIndexLine(CronJobRecord job) {
        return SecretRedactor.redact(
                "cron-index:job"
                        + " job_id="
                        + StrUtil.nullToEmpty(job.getJobId())
                        + " name="
                        + StrUtil.nullToEmpty(job.getName())
                        + " schedule="
                        + StrUtil.nullToEmpty(job.getCronExpr())
                        + " status="
                        + StrUtil.nullToEmpty(job.getStatus())
                        + " deliver="
                        + StrUtil.nullToEmpty(job.getDeliverPlatform())
                        + " script="
                        + StrUtil.nullToEmpty(job.getScript())
                        + " prompt="
                        + StrUtil.nullToEmpty(StrUtil.maxLength(job.getPrompt(), 240))
                        + " last_status="
                        + StrUtil.nullToEmpty(job.getLastStatus())
                        + " last_error="
                        + StrUtil.nullToEmpty(job.getLastError())
                        + " last_delivery_error="
                        + StrUtil.nullToEmpty(job.getLastDeliveryError())
                        + " last_output="
                        + StrUtil.nullToEmpty(job.getLastOutput()),
                2000);
    }

    /**
     * 格式化 cron 运行历史索引命中，保留输出、错误和投递错误摘要。
     *
     * @param run cron 运行历史记录。
     * @return 返回可展示的索引日志行。
     */
    private String formatCronRunIndexLine(CronJobRunRecord run) {
        return SecretRedactor.redact(
                "cron-index:run"
                        + " run_id="
                        + StrUtil.nullToEmpty(run.getRunId())
                        + " job_id="
                        + StrUtil.nullToEmpty(run.getJobId())
                        + " trigger="
                        + StrUtil.nullToEmpty(run.getTriggerType())
                        + " attempt="
                        + run.getAttempt()
                        + " status="
                        + StrUtil.nullToEmpty(run.getStatus())
                        + " output="
                        + StrUtil.nullToEmpty(run.getOutput())
                        + " error="
                        + StrUtil.nullToEmpty(run.getError())
                        + " delivery_error="
                        + StrUtil.nullToEmpty(run.getDeliveryError())
                        + " delivery_result="
                        + StrUtil.nullToEmpty(run.getDeliveryResultJson())
                        + " summary="
                        + StrUtil.nullToEmpty(run.getSummary()),
                2000);
    }
}
