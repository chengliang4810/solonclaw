package com.jimuqu.solon.claw.core.model;

import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** 当前 Agent run 的追踪上下文。 */
public class AgentRunContext {
    /** 当前的统一常量值。 */
    private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<AgentRunContext>();

    /** 保存仓储依赖，用于访问持久化数据。 */
    private final AgentRunRepository repository;

    /** 记录Agent运行上下文中的运行标识。 */
    private final String runId;

    /** 记录Agent运行上下文中的会话标识。 */
    private final String sessionId;

    /** 记录Agent运行上下文中的来源键。 */
    private final String sourceKey;

    /** 记录Agent运行上下文中的工作区目录。 */
    private String workspaceDir;

    /** 记录Agent运行上下文中的phase。 */
    private String phase;

    /** 记录Agent运行上下文中的运行Kind。 */
    private String runKind;

    /** 记录Agent运行上下文中的parent运行标识。 */
    private String parentRunId;

    /** 记录Agent运行上下文中的attemptNo。 */
    private int attemptNo;

    /** 记录Agent运行上下文中的提供方。 */
    private String provider;

    /** 记录Agent运行上下文中的模型。 */
    private String model;

    /** 保存用户附件集合，维持调用顺序或去重语义。 */
    private java.util.List<MessageAttachment> userAttachments;

    /** 保存本轮召回的临时记忆上下文，仅用于模型请求，不写入会话历史。 */
    private String memoryPrefetchContext;

    /** 记录临时记忆对应的用户原文，避免恢复或内部提示词误用召回内容。 */
    private String memoryPrefetchUserMessage;

    /**
     * 创建Agent运行上下文实例，并注入运行所需依赖。
     *
     * @param repository repository依赖组件。
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
     * 执行当前相关逻辑。
     *
     * @return 返回当前结果。
     */
    public static AgentRunContext current() {
        return CURRENT.get();
    }

    /**
     * 写入当前。
     *
     * @param context 当前请求或运行上下文。
     */
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
     * 写入Attempt。
     *
     * @param attemptNo attemptNo 参数。
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     */
    public void setAttempt(int attemptNo, String provider, String model) {
        this.attemptNo = attemptNo;
        this.provider = provider;
        this.model = model;
    }

    /**
     * 读取工作区Dir。
     *
     * @return 返回读取到的工作区Dir。
     */
    public String getWorkspaceDir() {
        return workspaceDir;
    }

    /**
     * 写入工作区Dir。
     *
     * @param workspaceDir 文件或目录路径参数。
     */
    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    /**
     * 读取Phase。
     *
     * @return 返回读取到的Phase。
     */
    public String getPhase() {
        return phase;
    }

    /**
     * 写入Phase。
     *
     * @param phase phase 参数。
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
     * @param runKind 运行Kind参数。
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

    /**
     * 读取用户附件。
     *
     * @return 返回读取到的用户附件。
     */
    public java.util.List<MessageAttachment> getUserAttachments() {
        return userAttachments == null
                ? java.util.Collections.<MessageAttachment>emptyList()
                : userAttachments;
    }

    /**
     * 写入用户附件。
     *
     * @param userAttachments 用户Attachments参数。
     */
    public void setUserAttachments(java.util.List<MessageAttachment> userAttachments) {
        if (userAttachments == null || userAttachments.isEmpty()) {
            this.userAttachments = null;
        } else {
            this.userAttachments = new java.util.ArrayList<MessageAttachment>(userAttachments);
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
     * 执行事件相关逻辑。
     *
     * @param eventType 事件类型参数。
     * @param summary 摘要参数。
     */
    public void event(String eventType, String summary) {
        event(eventType, summary, null);
    }

    /**
     * 执行事件相关逻辑。
     *
     * @param eventType 事件类型参数。
     * @param summary 摘要参数。
     * @param metadata 元数据参数。
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
            event.setMetadataJson(metadata == null ? null : safe(ONode.serialize(metadata), 4000));
            event.setCreatedAt(System.currentTimeMillis());
            repository.appendEvent(event);
        } catch (Exception ignored) {
        }
    }

    /**
     * 执行元数据相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回元数据结果。
     */
    public Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    /**
     * 执行元数据相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param key2 key2 参数。
     * @param value2 value2 参数。
     * @return 返回元数据结果。
     */
    public Map<String, Object> metadata(String key, Object value, String key2, Object value2) {
        Map<String, Object> map = metadata(key, value);
        map.put(key2, value2);
        return map;
    }

    /**
     * 保存工具Call。
     *
     * @param record 记录参数。
     */
    public void saveToolCall(ToolCallRecord record) {
        if (repository == null || record == null) {
            return;
        }
        try {
            repository.saveToolCall(record);
        } catch (Exception ignored) {
        }
    }

    /**
     * 执行安全相关逻辑。
     *
     * @param text 待处理文本。
     * @param limit 最大返回数量。
     * @return 返回safe结果。
     */
    public static String safe(String text, int limit) {
        String redacted = SecretRedactor.redact(text, limit);
        if (redacted == null) {
            return null;
        }
        return redacted.length() <= limit ? redacted : redacted.substring(0, limit) + "...";
    }

    /**
     * 解析Severity。
     *
     * @param eventType 事件类型参数。
     * @return 返回解析后的Severity。
     */
    private String resolveSeverity(String eventType) {
        String value = eventType == null ? "" : eventType.toLowerCase(java.util.Locale.ROOT);
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
