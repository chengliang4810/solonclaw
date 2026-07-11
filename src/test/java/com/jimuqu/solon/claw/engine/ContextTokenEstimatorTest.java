package com.jimuqu.solon.claw.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class ContextTokenEstimatorTest {
    @Test
    void shouldAddTokenCostForImageMarkers() {
        int plain = ContextTokenEstimator.estimateForBudget("请处理这段文本");
        int withImage =
                ContextTokenEstimator.estimateForBudget("请处理这段文本\nMEDIA:/tmp/example.png\n继续分析");

        assertThat(withImage).isGreaterThan(plain + 100);
    }

    @Test
    void shouldCountMultipleAttachmentMarkersConservatively() {
        int estimated =
                ContextTokenEstimator.estimateForBudget(
                        "ATTACHMENT:https://example.test/a.png\n"
                                + "正文\n"
                                + "image_url=https://example.test/b.png");

        assertThat(estimated).isGreaterThanOrEqualTo(3000);
    }
}
