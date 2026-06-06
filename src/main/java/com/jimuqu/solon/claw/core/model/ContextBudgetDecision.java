package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 上下文预算检查结果。 */
@Getter
@Setter
@NoArgsConstructor
public class ContextBudgetDecision {
    /** 记录上下文预算中的estimatedtoken。 */
    private int estimatedTokens;

    /** 记录上下文预算中的thresholdtoken。 */
    private int thresholdTokens;

    /** 记录上下文预算中的上下文窗口token。 */
    private int contextWindowTokens;

    /** 标记是否需要压缩。 */
    private boolean shouldCompress;

    /** 记录上下文预算中的原因。 */
    private String reason;
}
