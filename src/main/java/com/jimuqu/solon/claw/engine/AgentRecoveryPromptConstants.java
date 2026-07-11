package com.jimuqu.solon.claw.engine;

/** Agent 回复恢复流程共用的提示词与用户兜底文案常量。 */
final class AgentRecoveryPromptConstants {
    /** 模型完成工具调用却未输出最终文本时，用于发起一次无工具中文总结的恢复提示。 */
    static final String EMPTY_REPLY_RECOVERY_PROMPT =
            "你刚刚已经完成了工具调用，但没有输出最终答复。请基于当前会话中的最新工具结果，直接用中文给出简洁最终答复，不要再次调用工具。";

    /** 空回复恢复仍失败时返回给用户的兜底说明，保持原有交互指引不变。 */
    static final String EMPTY_REPLY_FALLBACK = "本轮已完成工具调用，但模型没有返回可读结论。请使用 /retry 重试，或继续给出下一步指令。";

    /** ReAct 步数耗尽后要求模型基于现有轨迹做无工具收敛总结的基础提示。 */
    static final String MAX_STEPS_RECOVERY_PROMPT =
            "你刚刚因为最大推理步数限制而停止。不要再次调用工具。请基于当前会话中已经完成的分析、工具结果、文件修改和观察，直接输出中文收敛答复：优先给出已经完成的结果；若任务仍未彻底完成，明确说明还差什么、最推荐的下一步是什么。";

    /** 运行监督器在步数耗尽后使用的增强提示，额外要求模型承认并依据已执行工具结果。 */
    static final String MAX_STEPS_TOOL_AWARE_RECOVERY_PROMPT =
            MAX_STEPS_RECOVERY_PROMPT
                    + "如果历史里已经有 tool 角色消息，必须承认这些工具已经执行并以工具返回内容为事实依据，禁止声称工具没有调用或没有执行。";

    /** 步数耗尽后的收敛恢复仍失败时返回给用户的兜底说明。 */
    static final String MAX_STEPS_RECOVERY_FALLBACK =
            "本轮执行已达到最大步骤限制，已保留当前进展。请继续给出更聚焦的下一步，或使用 /retry 继续。";

    /** 工具类禁止实例化，避免把常量容器误用为有状态服务。 */
    private AgentRecoveryPromptConstants() {}
}
