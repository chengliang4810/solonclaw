package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 会话持久化记录。 */
@Getter
@Setter
@NoArgsConstructor
public class SessionRecord {
    /** 会话 ID。 */
    private String sessionId;

    /** 来源键。 */
    private String sourceKey;

    /** 分支名。 */
    private String branchName;

    /** 父会话 ID。 */
    private String parentSessionId;

    /** 模型覆盖配置。 */
    private String modelOverride;

    /** 服务层级覆盖配置。 */
    private String serviceTierOverride;

    /** 推理强度覆盖配置。 */
    private String reasoningEffortOverride;

    /** 本轮运行临时 provider 覆盖，不写入持久化存储。 */
    private transient String transientProviderOverride;

    /** 本轮运行临时模型覆盖，不写入持久化存储。 */
    private transient String transientModelOverride;

    /** 平台侧会话/消息锚点 ID。 */
    private String platformMessageId;

    /** 会话扩展元数据 JSON。 */
    private String metadataJson;

    /** 会话消息 NDJSON。 */
    private String ndjson;

    /** 从仓储加载时的会话消息基线，用于保存时合并并发追加。 */
    private transient String persistedNdjson;

    /** 会话标题。 */
    private String title;

    /** 最近一次压缩生成的结构化摘要。 */
    private String compressedSummary;

    /** 会话冻结后的系统提示词快照。 */
    private String systemPromptSnapshot;

    /** ReAct/AgentSession 的 FlowContext 快照 JSON。 */
    private String agentSnapshotJson;

    /** 记录会话中的目标状态JSON。 */
    private String goalStateJson;

    /** 最近一次学习闭环执行时间。 */
    private long lastLearningAt;

    /** 最近一次压缩时间。 */
    private long lastCompressionAt;

    /** 最近一次压缩前估算的输入 token 数。 */
    private int lastCompressionInputTokens;

    /** 压缩连续失败次数。 */
    private int compressionFailureCount;

    /** 最近一次压缩失败时间。 */
    private long lastCompressionFailedAt;

    /** 最近一轮输入 token。 */
    private long lastInputTokens;

    /** 最近一轮输出 token。 */
    private long lastOutputTokens;

    /** 最近一轮 推理 token。 */
    private long lastReasoningTokens;

    /** 最近一轮 cache read token。 */
    private long lastCacheReadTokens;

    /** 最近一轮 cache write token。 */
    private long lastCacheWriteTokens;

    /** 最近一轮总 token。 */
    private long lastTotalTokens;

    /** 累计输入 token。 */
    private long cumulativeInputTokens;

    /** 累计输出 token。 */
    private long cumulativeOutputTokens;

    /** 累计 推理 token。 */
    private long cumulativeReasoningTokens;

    /** 累计 cache read token。 */
    private long cumulativeCacheReadTokens;

    /** 累计 cache write token。 */
    private long cumulativeCacheWriteTokens;

    /** 累计总 token。 */
    private long cumulativeTotalTokens;

    /** 最近一次 usage 统计时间。 */
    private long lastUsageAt;

    /** 最近一次实际使用的 provider。 */
    private String lastResolvedProvider;

    /** 最近一次实际使用的 model。 */
    private String lastResolvedModel;

    /** 创建时间。 */
    private long createdAt;

    /** 更新时间。 */
    private long updatedAt;

    /** 从仓储加载或最近一次成功保存时的并发设置基线，仅用于冲突检测。 */
    private transient Object[] persistedConcurrentSettings;
}
