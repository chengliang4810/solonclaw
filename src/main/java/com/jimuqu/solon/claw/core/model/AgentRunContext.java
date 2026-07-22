package com.jimuqu.solon.claw.core.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.StructuredMetadataSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 当前 Agent run 的线程级追踪上下文，集中记录审计事件、工具策略和本轮临时上下文。 */
public class AgentRunContext {
    /** Agent run 上下文持久化 fallback 的低敏诊断日志。 */
    private static final Logger log = LoggerFactory.getLogger(AgentRunContext.class);

    /** 当前线程正在执行的 Agent run，上层编排器进入和退出运行时负责写入与清理。 */
    private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<AgentRunContext>();

    /** 工具策略拒绝 observation 的统一前缀，供审计层识别运行时拒绝结果。 */
    public static final String TOOL_POLICY_REJECTION_PREFIX = "本轮 Web 运行";

    /** 运行仓储，用于追加事件和工具调用审计记录；为空时上下文只承担内存态控制。 */
    private final AgentRunRepository repository;

    /** 本轮 Agent run 的唯一标识。 */
    private final String runId;

    /** 本轮 run 所属会话标识。 */
    private final String sessionId;

    /** 渠道来源键，用于把运行事件关联回具体入口。 */
    private final String sourceKey;

    /** 本轮工具执行默认工作区目录。 */
    private String workspaceDir;

    /** 当前运行阶段，例如模型请求、工具调用或恢复流程。 */
    private String phase;

    /** 运行类型，区分普通会话、定时任务和子 Agent 等来源。 */
    private String runKind;

    /** 父级 run 标识，用于子 Agent 和委派任务串联审计链路。 */
    private String parentRunId;

    /** 当前模型请求重试序号。 */
    private int attemptNo;

    /** 当前尝试使用的模型提供方。 */
    private String provider;

    /** 当前尝试使用的模型名称。 */
    private String model;

    /** 用户本轮上传或渠道转入的附件集合，保留原始顺序供多模态请求使用。 */
    private List<MessageAttachment> userAttachments;

    /** 保存本轮召回的临时记忆上下文，仅用于模型请求，不写入会话历史。 */
    private String memoryPrefetchContext;

    /** 记录临时记忆对应的用户原文，避免恢复或内部提示词误用召回内容。 */
    private String memoryPrefetchUserMessage;

    /** 本轮允许调用的工具名称白名单；为空表示不限制具体工具名。 */
    private List<String> allowedToolNames;

    /** 本轮实际暴露给模型的工具名称快照；null 表示旧调用方未提供快照。 */
    private List<String> enabledToolNames;

    /** 本轮允许尝试的最大工具调用次数；为空或小于等于零表示不限制次数。 */
    private Integer maxToolCalls;

    /** 记录本轮已经尝试的工具调用次数，包含被策略拒绝的调用。 */
    private int attemptedToolCalls;

    /** 本轮已经发送的语义阶段说明数量，跨模型重试和恢复共享。 */
    private int progressUpdateCount;

    /** 本轮已经发送的语义阶段说明集合，用于跨模型尝试执行全量去重。 */
    private final Set<String> emittedProgressUpdates = new LinkedHashSet<String>();

    /** 本轮上一次发送语义阶段说明的时间戳。 */
    private long lastProgressUpdateAt;

    /**
     * 创建 Agent run 上下文。
     *
     * @param repository 运行仓储；为空时跳过持久化审计。
     * @param runId 运行标识。
     * @param sessionId 当前会话标识。
     * @param sourceKey 渠道来源键。
     */
    public AgentRunContext(
            AgentRunRepository repository, String runId, String sessionId, String sourceKey) {
        this.repository = repository;
        this.runId = runId;
        this.sessionId = sessionId;
        this.sourceKey = sourceKey;
    }

    /**
     * 读取当前线程绑定的 Agent run 上下文。
     *
     * @return 当前上下文；未进入 Agent run 时返回 null。
     */
    public static AgentRunContext current() {
        return CURRENT.get();
    }

    /** 写入或清理当前线程绑定的 Agent run 上下文。 */
    public static void setCurrent(AgentRunContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /**
     * 读取运行标识。
     *
     * @return 返回读取到的运行标识。
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 读取会话标识。
     *
     * @return 返回读取到的会话标识。
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 读取来源键。
     *
     * @return 返回读取到的来源键。
     */
    public String getSourceKey() {
        return sourceKey;
    }

    /**
     * 读取Attempt No。
     *
     * @return 返回读取到的Attempt No。
     */
    public int getAttemptNo() {
        return attemptNo;
    }

    /**
     * 写入当前模型请求尝试信息，用于后续事件记录补充 provider/model/attempt。
     *
     * @param attemptNo 当前尝试序号。
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     */
    public void setAttempt(int attemptNo, String provider, String model) {
        this.attemptNo = attemptNo;
        this.provider = provider;
        this.model = model;
    }

    /**
     * 原子登记一条阶段说明，确保模型重试、故障切换和恢复调用共享数量及时间限制。
     *
     * @param text 已完成安全过滤的阶段说明。
     * @param now 当前时间戳。
     * @param minIntervalMs 相邻说明的最小间隔。
     * @param maxCount 本轮允许发送的最大数量。
     * @return 满足约束并已登记时返回 true。
     */
    public synchronized boolean tryRegisterProgressUpdate(
            String text, long now, long minIntervalMs, int maxCount) {
        if (StrUtil.isBlank(text)
                || progressUpdateCount >= Math.max(0, maxCount)
                || emittedProgressUpdates.contains(text)
                || (lastProgressUpdateAt > 0
                        && now - lastProgressUpdateAt < Math.max(0L, minIntervalMs))) {
            return false;
        }
        progressUpdateCount++;
        emittedProgressUpdates.add(text);
        lastProgressUpdateAt = now;
        return true;
    }

    /**
     * 判断本轮是否已经登记过自然阶段说明，供长工具辅助说明避免重复发送。
     *
     * @return 已发送过至少一条阶段说明时返回 true。
     */
    public synchronized boolean hasRegisteredProgressUpdate() {
        return progressUpdateCount > 0;
    }

    /**
     * 读取工具执行工作区目录。
     *
     * @return 返回本轮工具执行默认工作区目录。
     */
    public String getWorkspaceDir() {
        return workspaceDir;
    }

    /**
     * 写入工具执行工作区目录。
     *
     * @param workspaceDir 本轮工具执行默认工作区目录。
     */
    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    /**
     * 读取当前运行阶段。
     *
     * @return 返回当前运行阶段。
     */
    public String getPhase() {
        return phase;
    }

    /**
     * 写入当前运行阶段。
     *
     * @param phase 当前运行阶段。
     */
    public void setPhase(String phase) {
        this.phase = phase;
    }

    /**
     * 读取运行Kind。
     *
     * @return 返回读取到的运行Kind。
     */
    public String getRunKind() {
        return runKind;
    }

    /**
     * 写入运行Kind。
     *
     * @param runKind 当前 run 的类型标记，例如 chat、cron 或 subagent。
     */
    public void setRunKind(String runKind) {
        this.runKind = runKind;
    }

    /**
     * 读取Parent运行标识。
     *
     * @return 返回读取到的Parent运行标识。
     */
    public String getParentRunId() {
        return parentRunId;
    }

    /**
     * 写入Parent运行标识。
     *
     * @param parentRunId parent运行标识。
     */
    public void setParentRunId(String parentRunId) {
        this.parentRunId = parentRunId;
    }

    /** 读取用户附件。 */
    public List<MessageAttachment> getUserAttachments() {
        return userAttachments == null
                ? Collections.<MessageAttachment>emptyList()
                : userAttachments;
    }

    /**
     * 写入用户附件副本，避免调用方后续修改影响本轮多模态上下文。
     *
     * @param userAttachments 渠道消息或前端请求携带的附件集合。
     */
    public void setUserAttachments(List<MessageAttachment> userAttachments) {
        if (CollUtil.isEmpty(userAttachments)) {
            this.userAttachments = null;
        } else {
            this.userAttachments = new ArrayList<MessageAttachment>(userAttachments);
        }
    }

    /**
     * 读取本轮临时记忆上下文。
     *
     * @return 返回只应在模型请求中使用的记忆召回文本。
     */
    public String getMemoryPrefetchContext() {
        return memoryPrefetchContext;
    }

    /**
     * 读取临时记忆关联的用户原文。
     *
     * @return 返回触发本轮记忆召回的用户输入。
     */
    public String getMemoryPrefetchUserMessage() {
        return memoryPrefetchUserMessage;
    }

    /**
     * 写入本轮临时记忆上下文。
     *
     * @param userMessage 触发召回的用户原文。
     * @param memoryPrefetchContext 已召回的记忆上下文文本。
     */
    public void setMemoryPrefetchContext(String userMessage, String memoryPrefetchContext) {
        this.memoryPrefetchUserMessage = userMessage;
        this.memoryPrefetchContext = memoryPrefetchContext;
    }

    /**
     * 写入本轮工具运行策略，用于 Web 回归或受控任务在工具真正执行前进行硬限制。
     *
     * @param allowedToolNames 本轮允许调用的工具名集合；为空表示不限制工具名。
     * @param maxToolCalls 本轮允许尝试的最大工具调用次数；为空或小于等于零表示不限制次数。
     */
    public synchronized void setToolPolicy(List<String> allowedToolNames, Integer maxToolCalls) {
        this.allowedToolNames = normalizeToolNames(allowedToolNames);
        this.maxToolCalls =
                maxToolCalls == null || maxToolCalls.intValue() <= 0 ? null : maxToolCalls;
        this.attemptedToolCalls = 0;
    }

    /**
     * 保存本轮实际暴露给模型的工具名称快照。
     *
     * @param enabledToolNames 已经过 Agent 范围和会话开关过滤的工具名；null 表示未知。
     */
    public synchronized void setEnabledToolNames(List<String> enabledToolNames) {
        this.enabledToolNames =
                enabledToolNames == null ? null : normalizeToolNames(enabledToolNames);
    }

    /**
     * 判断当前上下文是否持有实际工具快照。
     *
     * @return 即使实际快照为空，只要调用方明确提供过也返回 true。
     */
    public synchronized boolean hasEnabledToolNamesSnapshot() {
        return enabledToolNames != null;
    }

    /**
     * 读取本轮实际暴露给模型的工具名称快照。
     *
     * @return 返回不可变工具名列表；未提供快照时返回空列表。
     */
    public synchronized List<String> getEnabledToolNames() {
        return enabledToolNames == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(enabledToolNames));
    }

    /**
     * 判断本轮是否配置了工具运行策略。
     *
     * @return 如果存在工具名或次数限制则返回 true。
     */
    public synchronized boolean hasToolPolicy() {
        return CollUtil.isNotEmpty(allowedToolNames) || maxToolCalls != null;
    }

    /**
     * 读取本轮允许工具白名单。
     *
     * @return 返回不可变工具名列表；未配置时返回空列表。
     */
    public synchronized List<String> getAllowedToolNames() {
        return allowedToolNames == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(allowedToolNames));
    }

    /**
     * 读取本轮最大工具调用次数。
     *
     * @return 返回最大工具调用次数；未配置时返回 null。
     */
    public synchronized Integer getMaxToolCalls() {
        return maxToolCalls;
    }

    /**
     * 记录一次模型发起的工具调用尝试，并返回是否需要拒绝执行。
     *
     * @param toolName 模型请求调用的工具名称。
     * @return 返回拒绝原因；允许执行时返回 null。
     */
    public synchronized String recordToolAttempt(String toolName) {
        attemptedToolCalls++;
        String cleanToolName = StrUtil.trimToEmpty(toolName);
        if (maxToolCalls != null && attemptedToolCalls > maxToolCalls.intValue()) {
            return TOOL_POLICY_REJECTION_PREFIX
                    + "最多允许 "
                    + maxToolCalls
                    + " 次工具调用，当前第 "
                    + attemptedToolCalls
                    + " 次调用 "
                    + cleanToolName
                    + " 已被拒绝执行。";
        }
        if (CollUtil.isNotEmpty(allowedToolNames) && !allowedToolNames.contains(cleanToolName)) {
            return TOOL_POLICY_REJECTION_PREFIX
                    + "只允许调用工具 "
                    + allowedToolNames
                    + "，当前工具 "
                    + cleanToolName
                    + " 已被拒绝执行。";
        }
        return null;
    }

    /**
     * 读取本轮已经尝试的工具调用次数。
     *
     * @return 返回工具调用尝试次数。
     */
    public synchronized int getAttemptedToolCalls() {
        return attemptedToolCalls;
    }

    /**
     * 记录一条运行事件，忽略持久化异常以免影响主对话流程。
     *
     * @param eventType 事件类型，例如 tool.start 或 llm.failed。
     * @param summary 面向诊断页面展示的事件摘要。
     */
    public void event(String eventType, String summary) {
        event(eventType, summary, null);
    }

    /**
     * 记录一条带结构化元数据的运行事件。
     *
     * @param eventType 事件类型，例如 tool.start 或 llm.failed。
     * @param summary 面向诊断页面展示的事件摘要。
     * @param metadata 需要随事件保存的结构化元数据，会在序列化前脱敏。
     */
    public void event(String eventType, String summary, Map<String, Object> metadata) {
        if (repository == null) {
            return;
        }
        try {
            AgentRunEventRecord event = new AgentRunEventRecord();
            event.setEventId(IdSupport.newId());
            event.setRunId(runId);
            event.setSessionId(sessionId);
            event.setSourceKey(sourceKey);
            event.setEventType(eventType);
            event.setPhase(phase);
            event.setSeverity(resolveSeverity(eventType));
            event.setAttemptNo(attemptNo);
            event.setProvider(provider);
            event.setModel(model);
            event.setSummary(safe(summary, 1000));
            event.setMetadataJson(StructuredMetadataSupport.serializeRedacted(metadata));
            event.setCreatedAt(System.currentTimeMillis());
            repository.appendEvent(event);
        } catch (Exception e) {
            log.debug(
                    "Agent run 事件持久化失败，跳过事件 eventType={}, error={}",
                    safe(eventType, 120),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 构造单键元数据 Map，减少事件记录调用点的样板代码。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回仅包含该键值对的元数据 Map。
     */
    public Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    /**
     * 构造双键元数据 Map，减少事件记录调用点的样板代码。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param key2 第二个配置键或映射键。
     * @param value2 第二个待记录值。
     * @return 返回包含两个键值对的元数据 Map。
     */
    public Map<String, Object> metadata(String key, Object value, String key2, Object value2) {
        Map<String, Object> map = metadata(key, value);
        map.put(key2, value2);
        return map;
    }

    /**
     * 保存工具调用审计记录，失败时不打断模型主循环。
     *
     * @param record 待保存的工具调用记录。
     */
    public void saveToolCall(ToolCallRecord record) {
        if (repository == null || record == null) {
            return;
        }
        try {
            repository.saveToolCall(record);
        } catch (Exception e) {
            log.debug(
                    "Agent run 工具调用持久化失败，跳过工具审计 toolName={}, status={}, error={}",
                    safe(record.getToolName(), 200),
                    safe(record.getStatus(), 80),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 对事件摘要或工具预览文本进行脱敏并裁剪到指定长度。
     *
     * @param text 待处理文本。
     * @param limit 最大返回数量。
     * @return 返回可安全展示的文本。
     */
    public static String safe(String text, int limit) {
        String redacted = SecretRedactor.redact(text, limit);
        if (redacted == null) {
            return null;
        }
        return redacted.length() <= limit ? redacted : redacted.substring(0, limit) + "...";
    }

    /**
     * 规范化工具名称集合，去除空白项并保持调用方提供的顺序。
     *
     * @param names 原始工具名称集合。
     * @return 返回去重后的工具名称列表；没有有效项时返回 null。
     */
    private List<String> normalizeToolNames(List<String> names) {
        if (CollUtil.isEmpty(names)) {
            return null;
        }
        Set<String> clean = new LinkedHashSet<String>();
        for (String name : names) {
            String trimmed = StrUtil.trimToEmpty(name);
            if (StrUtil.isNotEmpty(trimmed)) {
                clean.add(trimmed);
            }
        }
        return CollUtil.isEmpty(clean) ? null : new ArrayList<String>(clean);
    }

    /**
     * 根据事件类型推导日志严重程度，供运行日志和诊断页面筛选。
     *
     * @param eventType 事件类型。
     * @return 返回 info、warn 或 error。
     */
    private String resolveSeverity(String eventType) {
        String value = StrUtil.trimToEmpty(eventType).toLowerCase(Locale.ROOT);
        if (value.contains("failed") || value.contains("error")) {
            return "error";
        }
        if (value.contains("fallback")
                || value.contains("recovery")
                || value.contains("compression.failed")
                || value.contains("cancel")) {
            return "warn";
        }
        return "info";
    }
}
