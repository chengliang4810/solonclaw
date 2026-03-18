package com.jimuqu.claw.agent.runtime;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于 Solon AI SimpleAgent 的会话执行实现。
 */
public class SolonAiConversationAgent implements ConversationAgent {
    /** 实际执行对话的 SimpleAgent。 */
    private final SimpleAgent simpleAgent;

    /**
     * 创建基于聊天模型的会话执行 Agent。
     *
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     */
    public SolonAiConversationAgent(ChatModel chatModel, String systemPrompt) {
        this.simpleAgent = SimpleAgent.of(chatModel)
                .name("solonclaw")
                .instruction(systemPrompt)
                .sessionWindowSize(64)
                .build();
    }

    /**
     * 执行一次对话请求。
     *
     * @param request 会话执行请求
     * @param progressConsumer 进度回调
     * @return 最终回复内容
     * @throws Throwable 流式执行过程中的异常
     */
    @Override
    public String execute(ConversationExecutionRequest request, Consumer<String> progressConsumer) throws Throwable {
        InMemoryAgentSession session = InMemoryAgentSession.of(request.getSessionKey());
        for (ChatMessage historyMessage : request.getHistory()) {
            session.addMessage(historyMessage);
        }

        AtomicReference<String> latestChunk = new AtomicReference<>("");

        Flux<AgentChunk> stream = simpleAgent
                .prompt(request.getCurrentMessage())
                .session(session)
                .stream();

        AgentChunk finalChunk = stream.doOnNext(chunk -> {
            String content = chunk.getContent();
            if (content != null && !content.isBlank() && !content.equals(latestChunk.get())) {
                latestChunk.set(content);
                progressConsumer.accept(content);
            }
        }).blockLast();

        if (finalChunk == null) {
            return latestChunk.get();
        }

        return finalChunk.getContent();
    }
}
