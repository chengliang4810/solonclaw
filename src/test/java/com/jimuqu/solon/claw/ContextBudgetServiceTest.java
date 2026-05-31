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

class ContextBudgetServiceTest {
    @Test
    void shouldNotTreatInlineImageDataUriAsLinearTextBudget() throws Exception {
        AppConfig config = config();
        DefaultContextBudgetService service = new DefaultContextBudgetService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-inline-image-budget");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser(
                                        "请分析这张图 data:image/png;base64,"
                                                + repeat("A", 16_384)))));

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
        assertThat(decision.getEstimatedTokens()).isGreaterThanOrEqualTo(decision.getThresholdTokens());
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
