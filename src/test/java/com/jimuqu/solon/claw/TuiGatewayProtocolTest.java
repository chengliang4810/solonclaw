package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tui.TuiEnvelope;
import org.junit.jupiter.api.Test;

class TuiGatewayProtocolTest {
    @Test
    void shouldParseSessionIdFromCamelCaseEnvelope() {
        TuiEnvelope envelope =
                TuiEnvelope.parse(
                        "{\"id\":\"7\",\"method\":\"input.send\",\"sessionId\":\"s1\",\"params\":{\"input\":\"hello\",\"busy_mode\":\"queue\"}}");

        assertThat(envelope.getId()).isEqualTo("7");
        assertThat(envelope.getMethod()).isEqualTo("input.send");
        assertThat(envelope.getSessionId()).isEqualTo("s1");
        assertThat(envelope.getParams()).containsEntry("input", "hello");
        assertThat(envelope.getParams()).containsEntry("busy_mode", "queue");
    }

    @Test
    void shouldParseSessionIdFromSnakeCaseEnvelope() {
        TuiEnvelope envelope =
                TuiEnvelope.parse(
                        "{\"id\":\"8\",\"method\":\"session.resume\",\"session_id\":\"s2\",\"params\":{}}");

        assertThat(envelope.getId()).isEqualTo("8");
        assertThat(envelope.getMethod()).isEqualTo("session.resume");
        assertThat(envelope.getSessionId()).isEqualTo("s2");
    }
}
