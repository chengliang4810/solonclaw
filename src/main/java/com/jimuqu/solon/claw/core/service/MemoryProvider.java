package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.MemoryPromptSection;
import com.jimuqu.solon.claw.core.model.MemoryTurnContext;
import java.util.Collections;
import java.util.List;

/** 记忆提供方抽象。 */
public interface MemoryProvider {
    /** 提供方名称。 */
    String name();

    /** 生成注入系统提示词的块。 */
    String systemPromptBlock(String sourceKey) throws Exception;

    /**
     * 生成可独立参与预算分配的记忆内容段；旧 provider 默认作为一个 OTHER 段接入。
     *
     * @param sourceKey 渠道来源键。
     * @return 非空记忆内容段列表。
     */
    default List<MemoryPromptSection> systemPromptSections(String sourceKey) throws Exception {
        String content = systemPromptBlock(sourceKey);
        if (content == null || content.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(
                new MemoryPromptSection(MemoryPromptSection.Type.OTHER, name(), content));
    }

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
