package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.engine.DefaultContextBudgetService;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;

class ContextBudgetServiceTest {
    @Test
    void shouldNotTreatInlineImageDataUriAsLinearTextBudget() throws Exception {
        AppConfig config = config();
        config.getLlm().setContextWindowTokens(4000);
        DefaultContextBudgetService service = new DefaultContextBudgetService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-inline-image-budget");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser(
                                        "请分析这张图 data:image/png;base64," + repeat("A", 16_384)))));

        ContextBudgetDecision decision = service.decide(session, "system", "继续", config.getLlm());

        assertThat(decision.isShouldCompress()).isFalse();
        assertThat(decision.getEstimatedTokens()).isLessThan(decision.getThresholdTokens());
    }

    @Test
    void shouldStillTriggerBudgetCompressionForLongPlainText() throws Exception {
        AppConfig config = config();
        DefaultContextBudgetService service = new DefaultContextBudgetService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-long-text-budget");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("请总结：" + repeat("A", 16_384)))));

        ContextBudgetDecision decision = service.decide(session, "system", "继续", config.getLlm());

        assertThat(decision.isShouldCompress()).isTrue();
        assertThat(decision.getEstimatedTokens())
                .isGreaterThanOrEqualTo(decision.getThresholdTokens());
    }

    @Test
    void shouldIncludeEnabledToolSchemasInTheContextBudget() {
        AppConfig config = config();
        config.getLlm().setContextWindowTokens(8000);
        DefaultContextBudgetService service = new DefaultContextBudgetService(config);
        FunctionToolDesc tool =
                new FunctionToolDesc("schema_heavy_tool")
                        .description(repeat("tool description ", 80))
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"string\",\"description\":\""
                                        + repeat("schema field ", 160)
                                        + "\"}},\"required\":[\"payload\"]}");

        ContextBudgetDecision withoutTools =
                service.decide(new SessionRecord(), "system", "继续", config.getLlm());
        ContextBudgetDecision withTools =
                service.decide(
                        new SessionRecord(),
                        "system",
                        "继续",
                        config.getLlm(),
                        Arrays.<Object>asList(tool));

        assertThat(withTools.getEstimatedTokens())
                .isGreaterThan(withoutTools.getEstimatedTokens() + 400);
    }

    /** 正数输出上限必须先从上下文窗口扣除，再计算输入压缩阈值。 */
    @Test
    void shouldReservePositiveMaxTokensBeforeCalculatingThreshold() {
        AppConfig config = config();
        config.getLlm().setContextWindowTokens(10000);
        config.getLlm().setMaxTokens(2000);
        DefaultContextBudgetService service = new DefaultContextBudgetService(config);

        ContextBudgetDecision decision =
                service.decide(new SessionRecord(), "system", "继续", config.getLlm());

        assertThat(decision.getContextWindowTokens()).isEqualTo(10000);
        assertThat(decision.getThresholdTokens()).isEqualTo(4000);
    }

    /** 输出上限占满或超过窗口时，必须保守钳制输入预算并触发压缩。 */
    @Test
    void shouldUseFullWindowWhenMaxTokensConsumesOrExceedsContextWindow() {
        AppConfig config = config();
        config.getLlm().setContextWindowTokens(10000);
        DefaultContextBudgetService service = new DefaultContextBudgetService(config);

        config.getLlm().setMaxTokens(10000);
        ContextBudgetDecision equalWindow =
                service.decide(new SessionRecord(), "system", "继续", config.getLlm());
        config.getLlm().setMaxTokens(12000);
        ContextBudgetDecision exceedsWindow =
                service.decide(new SessionRecord(), "system", "继续", config.getLlm());

        assertThat(equalWindow.getThresholdTokens()).isEqualTo(1);
        assertThat(exceedsWindow.getThresholdTokens()).isEqualTo(1);
        assertThat(equalWindow.isShouldCompress()).isTrue();
        assertThat(exceedsWindow.isShouldCompress()).isTrue();
    }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        config.getCompression().setEnabled(true);
        config.getCompression().setThresholdPercent(0.5D);
        config.getLlm().setContextWindowTokens(2000);
        return config;
    }

    private String repeat(String value, int count) {
        StringBuilder buffer = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            buffer.append(value);
        }
        return buffer.toString();
    }
}
