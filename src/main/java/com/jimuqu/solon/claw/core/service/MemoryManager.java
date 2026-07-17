package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.MemoryPromptSection;
import com.jimuqu.solon.claw.core.model.MemoryTurnContext;
import java.util.Collections;
import java.util.List;

/** 记忆管理器接口。 */
public interface MemoryManager {
    /** 构建注入系统提示词的记忆块。 */
    String buildSystemPrompt(String sourceKey) throws Exception;

    /**
     * 构建可独立参与系统提示词预算分配的记忆内容段。
     *
     * @param sourceKey 渠道来源键。
     * @return 非空记忆内容段列表。
     */
    default List<MemoryPromptSection> buildSystemPromptSections(String sourceKey) throws Exception {
        String content = buildSystemPrompt(sourceKey);
        if (content == null || content.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(
                new MemoryPromptSection(MemoryPromptSection.Type.OTHER, "Memory Manager", content));
    }

    /** 按当前输入预取补充上下文。 */
    String prefetch(String sourceKey, String userMessage) throws Exception;

    /** 在一轮对话完成后同步记忆状态。 */
    void syncTurn(String sourceKey, String userMessage, String assistantMessage) throws Exception;

    /** 在一轮对话完成后同步记忆状态，并暴露完整完成轮次上下文。 */
    default void syncTurn(MemoryTurnContext context) throws Exception {
        syncTurn(
                context == null ? null : context.getSourceKey(),
                context == null ? null : context.getUserMessage(),
                context == null ? null : context.getAssistantMessage());
    }
}
