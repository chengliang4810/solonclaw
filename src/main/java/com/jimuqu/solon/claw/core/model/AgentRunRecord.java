package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Agent 单轮运行记录。 */
@Getter
@Setter
@NoArgsConstructor
public class AgentRunRecord {
    /** 记录Agent运行中的运行标识。 */
    private String runId;

    /** 记录Agent运行中的会话标识。 */
    private String sessionId;

    /** 记录Agent运行中的来源键。 */
    private String sourceKey;

    /** 记录Agent运行中的运行Kind。 */
    private String runKind;

    /** 记录Agent运行中的parent运行标识。 */
    private String parentRunId;

    /** 记录Agent运行中的Agent名称。 */
    private String agentName;

    /** 记录Agent运行中的Agent快照 JSON。 */
    private String agentSnapshotJson;

    /** 记录Agent运行中的状态。 */
    private String status;

    /** 记录Agent运行中的phase。 */
    private String phase;

    /** 记录Agent运行中的busy策略。 */
    private String busyPolicy;

    /** 是否启用backgrounded。 */
    private boolean backgrounded;

    /** 记录Agent运行中的输入预览。 */
    private String inputPreview;

    /** 记录Agent运行中的最终回复预览。 */
    private String finalReplyPreview;

    /** 记录Agent运行中的提供方。 */
    private String provider;

    /** 记录Agent运行中的模型。 */
    private String model;

    /** 记录Agent运行中的attempts。 */
    private int attempts;

    /** 记录Agent运行中的上下文Estimatetoken。 */
    private int contextEstimateTokens;

    /** 记录Agent运行中的上下文窗口token。 */
    private int contextWindowTokens;

    /** 记录Agent运行中的压缩次数。 */
    private int compressionCount;

    /** 记录Agent运行中的兜底次数。 */
    private int fallbackCount;

    /** 记录Agent运行中的工具Call次数。 */
    private int toolCallCount;

    /** 记录Agent运行中的subtask次数。 */
    private int subtaskCount;

    /** 记录Agent运行中的输入 token。 */
    private long inputTokens;

    /** 记录Agent运行中的输出 token。 */
    private long outputTokens;

    /** 记录Agent运行中的totaltoken。 */
    private long totalTokens;

    /** 记录Agent运行中的排队时间。 */
    private long queuedAt;

    /** 记录Agent运行中的started时间。 */
    private long startedAt;

    /** 记录Agent运行中的心跳时间。 */
    private long heartbeatAt;

    /** 记录Agent运行中的最近一次Activity时间。 */
    private long lastActivityAt;

    /** 记录Agent运行中的finished时间。 */
    private long finishedAt;

    /** 记录Agent运行中的退出原因。 */
    private String exitReason;

    /** 是否启用recoverable。 */
    private boolean recoverable;

    /** 记录Agent运行中的恢复Hint。 */
    private String recoveryHint;

    /** 记录Agent运行中的错误。 */
    private String error;
}
