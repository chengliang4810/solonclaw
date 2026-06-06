package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Supervisor 执行结果。 */
@Getter
@Setter
@NoArgsConstructor
public class AgentRunOutcome {
    /** 记录Agent运行中的最终回复。 */
    private String finalReply;

    /** 记录Agent运行中的结果。 */
    private LlmResult result;

    /** 记录Agent运行中的运行记录。 */
    private AgentRunRecord runRecord;

    /** 记录Agent运行中的压缩Warning。 */
    private String compressionWarning;

    /** 记录Agent运行中的提供方。 */
    private String provider;

    /** 记录Agent运行中的模型。 */
    private String model;

    /** 记录Agent运行中的上下文Estimatetoken。 */
    private int contextEstimateTokens;

    /** 记录Agent运行中的上下文窗口token。 */
    private int contextWindowTokens;

    /** 记录Agent运行中的工作目录。 */
    private String cwd;
}
