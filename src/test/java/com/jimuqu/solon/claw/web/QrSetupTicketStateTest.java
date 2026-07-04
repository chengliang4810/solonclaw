package com.jimuqu.solon.claw.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QrSetupTicketStateTest {
    @Test
    void shouldManageQrTicketLifecycleAndRedactFailures() {
        QrSetupTicketState state = new QrSetupTicketState(1000L);

        assertThat(state.ticket).isNotBlank();
        assertThat(state.status).isEqualTo("initializing");
        assertThat(state.createdAt).isPositive();
        assertThat(state.updatedAt).isEqualTo(state.createdAt);
        assertThat(state.expiresAt).isGreaterThanOrEqualTo(state.createdAt + 1000L);

        state.mark("pending", "等待扫码");
        assertThat(state.status).isEqualTo("pending");
        assertThat(state.message).isEqualTo("等待扫码");
        assertThat(state.updatedAt).isGreaterThanOrEqualTo(state.createdAt);

        state.fail("qr_failed", "失败 token=sk-test-qrsetup12345");
        assertThat(state.status).isEqualTo("failed");
        assertThat(state.errorCode).isEqualTo("qr_failed");
        assertThat(state.errorMessage).contains("token=***");
        assertThat(state.message).isEqualTo(state.errorMessage);
        assertThat(state.errorMessage).doesNotContain("sk-test-qrsetup12345");
        assertThat(state.isoTime(0L)).isNull();
        assertThat(state.isoTime(state.createdAt)).contains("T");
    }
}
