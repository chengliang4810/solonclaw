package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 校验 Solon AI usage 到本地 token 桶的归一化。 */
public class SolonAiLlmGatewayUsageTest {
    @Test
    void shouldStripLeadingThinkTagsFromFinalVisibleText() throws Exception {
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(new AppConfig());
        AssistantMessage message = ChatMessage.ofAssistant("<think>内部推理</think>\n\n最终答复");

        Method extractText =
                SolonAiLlmGateway.class.getDeclaredMethod("extractText", AssistantMessage.class);
        extractText.setAccessible(true);
        Method extractReasoning =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "extractReasoning", AssistantMessage.class);
        extractReasoning.setAccessible(true);

        assertThat((String) extractText.invoke(gateway, message)).isEqualTo("最终答复");
        assertThat((String) extractReasoning.invoke(gateway, message)).isEqualTo("内部推理");
    }

    /** 未闭合或大小写不同的思考块也不能成为非流式最终回复。 */
    @Test
    void shouldSuppressMalformedThinkingOnlyVisibleText() throws Exception {
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(new AppConfig());
        Method extractText =
                SolonAiLlmGateway.class.getDeclaredMethod("extractText", AssistantMessage.class);
        extractText.setAccessible(true);

        assertThat(
                        (String)
                                extractText.invoke(
                                        gateway, ChatMessage.ofAssistant("<THINK>未闭合的内部推理，不应展示")))
                .isEmpty();
    }

    /** 流式分片必须统一过滤不同大小写的思考标签，未闭合块不得变成可见答复。 */
    @Test
    void shouldSuppressAllInlineReasoningTagVariantsInStream() throws Exception {
        Class<?> splitterClass =
                Class.forName("com.jimuqu.solon.claw.llm.SolonAiLlmGateway$ThinkingStreamSplitter");
        Constructor<?> constructor = splitterClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object splitter = constructor.newInstance();
        Method accept = splitterClass.getDeclaredMethod("accept", String.class, boolean.class);
        accept.setAccessible(true);
        Method flushPending = splitterClass.getDeclaredMethod("flushPending");
        flushPending.setAccessible(true);
        Method reasoningText = splitterClass.getDeclaredMethod("reasoningText");
        reasoningText.setAccessible(true);

        assertThat(deltaVisible(accept.invoke(splitter, "<ThInKi", false))).isEmpty();
        assertThat(deltaVisible(accept.invoke(splitter, "Ng>内部推理</tHiNkInG>正式答复", false)))
                .isEqualTo("正式答复");
        assertThat((String) reasoningText.invoke(splitter)).isEqualTo("内部推理");
        assertThat(deltaVisible(flushPending.invoke(splitter))).isEmpty();

        Method visibleText = splitterClass.getDeclaredMethod("visibleText", String.class);
        visibleText.setAccessible(true);
        assertThat((String) visibleText.invoke(null, "<REASONING>内部推理</reasoning>正式答复"))
                .isEqualTo("正式答复");
        assertThat((String) visibleText.invoke(null, "<ThOuGhT>未闭合内部推理")).isEmpty();
    }

    @Test
    void shouldReadOpenaiChatCachedTokensFromPromptDetails() throws Exception {
        ONode source =
                ONode.ofJson(
                        "{"
                                + "\"prompt_tokens\":1000,"
                                + "\"completion_tokens\":50,"
                                + "\"total_tokens\":1050,"
                                + "\"prompt_tokens_details\":{\"cached_tokens\":200},"
                                + "\"completion_tokens_details\":{\"reasoning_tokens\":7}"
                                + "}");

        LlmResult result = collect(new AiUsage(1000L, 0L, 50L, 1050L, source));

        assertThat(result.getInputTokens()).isEqualTo(800L);
        assertThat(result.getOutputTokens()).isEqualTo(50L);
        assertThat(result.getCacheReadTokens()).isEqualTo(200L);
        assertThat(result.getCacheWriteTokens()).isEqualTo(0L);
        assertThat(result.getReasoningTokens()).isEqualTo(7L);
        assertThat(result.getTotalTokens()).isEqualTo(1050L);
        assertThat(result.getRequestCount()).isEqualTo(1L);
    }

    @Test
    void shouldReadOpenaiResponsesCachedTokensFromInputDetails() throws Exception {
        ONode source =
                ONode.ofJson(
                        "{"
                                + "\"input_tokens\":1000,"
                                + "\"output_tokens\":50,"
                                + "\"total_tokens\":1050,"
                                + "\"input_tokens_details\":{\"cached_tokens\":200,\"cache_creation_tokens\":100},"
                                + "\"output_tokens_details\":{\"reasoning_tokens\":9}"
                                + "}");

        LlmResult result = collect(new AiUsage(1000L, 0L, 50L, 1050L, source));

        assertThat(result.getInputTokens()).isEqualTo(700L);
        assertThat(result.getOutputTokens()).isEqualTo(50L);
        assertThat(result.getCacheReadTokens()).isEqualTo(200L);
        assertThat(result.getCacheWriteTokens()).isEqualTo(100L);
        assertThat(result.getReasoningTokens()).isEqualTo(9L);
        assertThat(result.getTotalTokens()).isEqualTo(1050L);
        assertThat(result.getRequestCount()).isEqualTo(1L);
    }

    @Test
    void shouldKeepAnthropicInputSeparateFromPromptCache() throws Exception {
        ONode source =
                ONode.ofJson(
                        "{"
                                + "\"input_tokens\":1000,"
                                + "\"output_tokens\":50,"
                                + "\"cache_read_input_tokens\":200,"
                                + "\"cache_creation_input_tokens\":100"
                                + "}");

        LlmResult result = collect(new AiUsage(1000L, 0L, 50L, 1050L, 100L, 200L, source));

        assertThat(result.getInputTokens()).isEqualTo(1000L);
        assertThat(result.getOutputTokens()).isEqualTo(50L);
        assertThat(result.getCacheReadTokens()).isEqualTo(200L);
        assertThat(result.getCacheWriteTokens()).isEqualTo(100L);
        assertThat(result.getTotalTokens()).isEqualTo(1350L);
        assertThat(result.getRequestCount()).isEqualTo(1L);
    }

    @Test
    void shouldReadExplicitRequestCountFromRawUsage() throws Exception {
        ONode source =
                ONode.ofJson(
                        "{"
                                + "\"prompt_tokens\":10,"
                                + "\"completion_tokens\":5,"
                                + "\"total_tokens\":15,"
                                + "\"request_count\":3"
                                + "}");

        LlmResult result = collect(new AiUsage(10L, 0L, 5L, 15L, source));

        assertThat(result.getInputTokens()).isEqualTo(10L);
        assertThat(result.getOutputTokens()).isEqualTo(5L);
        assertThat(result.getTotalTokens()).isEqualTo(15L);
        assertThat(result.getRequestCount()).isEqualTo(3L);
        assertThat(result.getRawUsageJson()).contains("request_count");
    }

    private LlmResult collect(AiUsage usage) throws Exception {
        Class<?> collectorClass = usageCollectorClass();
        Constructor<?> constructor = collectorClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object collector = constructor.newInstance();

        Method add = collectorClass.getDeclaredMethod("add", AiUsage.class);
        add.setAccessible(true);
        add.invoke(collector, usage);

        LlmResult result = new LlmResult();
        Method applyTo = collectorClass.getDeclaredMethod("applyTo", LlmResult.class);
        applyTo.setAccessible(true);
        applyTo.invoke(collector, result);
        return result;
    }

    /** 读取私有流式分离结果中的用户可见正文。 */
    private String deltaVisible(Object delta) throws Exception {
        java.lang.reflect.Field visible = delta.getClass().getDeclaredField("visible");
        visible.setAccessible(true);
        return (String) visible.get(delta);
    }

    private Class<?> usageCollectorClass() {
        for (Class<?> nested : SolonAiLlmGateway.class.getDeclaredClasses()) {
            if ("UsageCollector".equals(nested.getSimpleName())) {
                return nested;
            }
        }
        throw new IllegalStateException("UsageCollector not found");
    }
}
