package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Agent run 内的一次工具调用轨迹。 */
@Getter
@Setter
@NoArgsConstructor
public class ToolCallRecord {
    /** 记录工具Call中的工具Call标识。 */
    private String toolCallId;

    /** 记录工具Call中的运行标识。 */
    private String runId;

    /** 记录工具Call中的会话标识。 */
    private String sessionId;

    /** 记录工具Call中的来源键。 */
    private String sourceKey;

    /** 记录工具Call中的工具名称。 */
    private String toolName;

    /** 记录工具Call中的状态。 */
    private String status;

    /** 记录工具Call中的参数预览。 */
    private String argsPreview;

    /** 记录工具Call中的结果预览。 */
    private String resultPreview;

    /** 记录工具Call中的结果Ref。 */
    private String resultRef;

    /** 记录工具Call中的错误。 */
    private String error;

    /** 是否启用readOnly。 */
    private boolean readOnly;

    /** 是否启用interruptible。 */
    private boolean interruptible;

    /** 是否启用sideEffecting。 */
    private boolean sideEffecting;

    /** 是否启用结果Indexable。 */
    private boolean resultIndexable;

    /** 记录工具Call中的输出限制字节。 */
    private int outputLimitBytes;

    /** 记录工具Call中的结果大小字节。 */
    private long resultSizeBytes;

    /** 记录工具Call中的execution策略。 */
    private String executionPolicy;

    /** 记录工具Call中的started时间。 */
    private long startedAt;

    /** 记录工具Call中的finished时间。 */
    private long finishedAt;

    /** 记录工具Call中的durationMs。 */
    private long durationMs;
}
