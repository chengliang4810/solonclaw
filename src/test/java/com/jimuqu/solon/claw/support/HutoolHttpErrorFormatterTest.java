package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class HutoolHttpErrorFormatterTest {
    @Test
    void shouldRedactFailureResponsePreview() {
        String body =
                "{\"error\":\"token=ghp_hutoolerror12345 api_key=sk-hutool-error-secret\"}";

        String message =
                HutoolHttpErrorFormatter.failure(
                        "Channel upload",
                        500,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(message)
                .contains("Channel upload failed: HTTP 500")
                .contains("response preview:")
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_hutoolerror12345")
                .doesNotContain("sk-hutool-error-secret");
    }

    @Test
    void shouldNormalizeMultilineFailurePreview() {
        String body = "first line token=ghp_hutoolline12345\r\nsecond api_key=sk-hutool-line";

        String message =
                HutoolHttpErrorFormatter.failure(
                        "Channel request",
                        502,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(message)
                .contains("Channel request failed: HTTP 502")
                .contains("first line token=***  second api_key=***")
                .doesNotContain("\r")
                .doesNotContain("\n")
                .doesNotContain("ghp_hutoolline12345")
                .doesNotContain("sk-hutool-line");
    }

    @Test
    void shouldOmitPreviewWhenFailureBodyIsEmpty() {
        String message =
                HutoolHttpErrorFormatter.failure(
                        "Weixin CDN upload",
                        503,
                        new ByteArrayInputStream(new byte[0]));

        assertThat(message).isEqualTo("Weixin CDN upload failed: HTTP 503");
    }

    @Test
    void shouldLimitLargeFailurePreview() {
        StringBuilder body = new StringBuilder("token=ghp_hutoollarge12345 ");
        for (int i = 0; i < 5000; i++) {
            body.append('x');
        }

        String message =
                HutoolHttpErrorFormatter.failure(
                        "Channel request",
                        500,
                        new ByteArrayInputStream(
                                body.toString().getBytes(StandardCharsets.UTF_8)));

        assertThat(message)
                .contains("Channel request failed: HTTP 500")
                .contains("token=***")
                .contains("...[truncated, totalLength=")
                .doesNotContain("ghp_hutoollarge12345");
    }
}
