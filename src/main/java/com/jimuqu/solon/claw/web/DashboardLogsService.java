package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
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

    /**
     * 创建控制台Logs服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public DashboardLogsService(AppConfig appConfig) {
        this(appConfig, null);
    }

    /**
     * 创建控制台Logs服务实例，并注入运行轨迹索引依赖。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunRepository Agent 运行仓储，用于查询工具事件索引。
     */
    public DashboardLogsService(AppConfig appConfig, AgentRunRepository agentRunRepository) {
        this.appConfig = appConfig;
        this.agentRunRepository = agentRunRepository;
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
        if (!file.exists()) {
            return Collections.emptyList();
        }

        List<String> all = FileUtil.readUtf8Lines(file);
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
        appendRunIndexMatches(filtered, query, lineCount);

        int safeLineCount = lineCount <= 0 ? 100 : Math.min(lineCount, 500);
        int start = Math.max(0, filtered.size() - safeLineCount);
        return new ArrayList<String>(filtered.subList(start, filtered.size()));
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
                    agentRunRepository.searchRuns(
                            null, null, null, query, 0L, 0L, searchLimit)) {
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
                            "run-index:error Dashboard 日志运行索引查询失败: " + e.getMessage(),
                            2000));
        }
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
}
