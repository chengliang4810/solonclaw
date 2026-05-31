package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.MemoryTurnContext;

/** 记忆提供方抽象。 */
public interface MemoryProvider {
    /** 提供方名称。 */
    String name();

    /** 生成注入系统提示词的块。 */
    String systemPromptBlock(String sourceKey) throws Exception;

    /** 按用户输入预取补充上下文。 */
    String prefetch(String sourceKey, String userMessage) throws Exception;

    /** 在一轮对话完成后同步状态。 */
    void syncTurn(String sourceKey, String userMessage, String assistantMessage) throws Exception;

    /** 在一轮对话完成后同步状态，并暴露完整完成轮次上下文。 */
    default void syncTurn(MemoryTurnContext context) throws Exception {
        syncTurn(
                context == null ? null : context.getSourceKey(),
                context == null ? null : context.getUserMessage(),
                context == null ? null : context.getAssistantMessage());
    }
}
