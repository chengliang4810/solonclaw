package com.jimuqu.solon.claw.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

public class HarnessSummarizationHygieneTest {
    @Test
    void shouldWrapRuntimeSummaryAsReferenceOnlyAssistantMessage() {
        SolonAiLlmGateway.SummaryBoundaryStrategy strategy =
                new SolonAiLlmGateway.SummaryBoundaryStrategy(
                        new FixedSummaryStrategy("do old task"));

        ChatMessage summary =
                strategy.summarize(new PreparedTrace(), Collections.<ChatMessage>emptyList());

        assertThat(summary.getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(summary.getContent()).startsWith(CompressionConstants.SUMMARY_PREFIX);
        assertThat(summary.getContent())
                .contains("background reference")
                .contains("NOT as active instructions")
                .contains("latest user message")
                .contains("do old task");
        assertThat(summary.hasMetadata(ReActAgent.META_SUMMARY)).isTrue();
    }

    @Test
    void shouldRepairWorkingMemoryAfterRuntimeSummaryCompression() {
        SolonAiLlmGateway.SafeHarnessSummarizationInterceptor interceptor =
                new SolonAiLlmGateway.SafeHarnessSummarizationInterceptor(
                        10,
                        8000,
                        new SolonAiLlmGateway.SummaryBoundaryStrategy(
                                new FixedSummaryStrategy("summarized runtime history")));
        ReActTrace trace = traceWithOrphanToolCallAfterCompressionCut();

        interceptor.onObservation(trace, "shell", "done", 1L);

        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        assertThat(messages).hasSizeLessThan(13);
        assertThat(messages.get(0).getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(messages.get(0).getContent()).startsWith(CompressionConstants.SUMMARY_PREFIX);
        AssistantMessage repaired = (AssistantMessage) messages.get(1);
        assertThat(repaired.getToolCalls()).isNullOrEmpty();
        assertThat(messages).extracting(ChatMessage::getRole).doesNotContain(ChatRole.TOOL);
    }

    @Test
    void shouldKeepRepairWrapperWhenHarnessCopiesSummarizationInterceptor() {
        SolonAiLlmGateway.SafeHarnessSummarizationInterceptor interceptor =
                new SolonAiLlmGateway.SafeHarnessSummarizationInterceptor(
                        10,
                        8000,
                        new SolonAiLlmGateway.SummaryBoundaryStrategy(
                                new FixedSummaryStrategy("copied summary")));
        SummarizationInterceptor copied = interceptor.copyWith(10, 8000);
        ReActTrace trace = traceWithOrphanToolCallAfterCompressionCut();

        copied.onObservation(trace, "shell", "done", 1L);

        assertThat(copied)
                .isInstanceOf(SolonAiLlmGateway.SafeHarnessSummarizationInterceptor.class);
        AssistantMessage repaired =
                (AssistantMessage) trace.getWorkingMemory().getMessages().get(1);
        assertThat(repaired.getToolCalls()).isNullOrEmpty();
    }

    @Test
    void shouldKeepNoopBehaviorWhenSummarizationDisabledAndHarnessCopiesInterceptor()
            throws Exception {
        AppConfig config = new AppConfig();
        config.getReact().setSummarizationEnabled(false);
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        Method method =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "buildHarnessSummarizationInterceptor",
                        AppConfig.LlmConfig.class,
                        ChatConfig.class);
        method.setAccessible(true);
        SummarizationInterceptor interceptor =
                (SummarizationInterceptor)
                        method.invoke(gateway, config.getLlm(), new ChatConfig());
        SummarizationInterceptor copied = interceptor.copyWith(10, 8000);
        ReActTrace trace = traceWithOrphanToolCallAfterCompressionCut();
        int before = trace.getWorkingMemory().getMessages().size();

        copied.onObservation(trace, "shell", "done", 1L);

        assertThat(trace.getWorkingMemory().getMessages()).hasSize(before);
    }

    private static ReActTrace traceWithOrphanToolCallAfterCompressionCut() {
        ReActTrace trace = new PreparedTrace();
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("old request 0"));
        trace.getWorkingMemory().addMessage(ChatMessage.ofAssistant("old answer 1"));
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("old request 2"));
        trace.getWorkingMemory().addMessage(assistantWithToolCall("call_orphan"));
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("latest user after orphan"));
        for (int i = 0; i < 8; i++) {
            trace.getWorkingMemory().addMessage(ChatMessage.ofAssistant("tail assistant " + i));
        }
        return trace;
    }

    private static AssistantMessage assistantWithToolCall(String id) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(new ToolCall("0", id, "shell", "{}", Collections.<String, Object>emptyMap()));
        return new AssistantMessage("", false, null, null, calls, null);
    }

    private static class PreparedTrace extends ReActTrace {
        private PreparedTrace() {
            ChatModel chatModel =
                    ChatModel.of("https://example.com/v1/chat/completions")
                            .provider("openai")
                            .model("test-model")
                            .build();
            SessionRecord sessionRecord = new SessionRecord();
            sessionRecord.setSessionId("summary-hygiene-test");
            AgentSession session = new SqliteAgentSession(sessionRecord);
            prepare(new ReActAgentConfig(chatModel), new ReActOptions(chatModel), session, null);
        }
    }

    private static class FixedSummaryStrategy implements SummarizationStrategy {
        private final String content;

        private FixedSummaryStrategy(String content) {
            this.content = content;
        }

        @Override
        public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
            return ChatMessage.ofUser(content).addMetadata(ReActAgent.META_SUMMARY, 1);
        }
    }
}
