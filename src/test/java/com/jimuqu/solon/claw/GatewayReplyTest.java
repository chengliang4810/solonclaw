package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import org.junit.jupiter.api.Test;

public class GatewayReplyTest {
    @Test
    void shouldReadTrimmedRuntimeMetadataText() {
        GatewayReply reply = GatewayReply.ok("ok");
        reply.getRuntimeMetadata().put("goal", "  resume  ");

        assertThat(reply.textRuntimeMetadata("goal")).isEqualTo("resume");
        assertThat(reply.textRuntimeMetadata("missing")).isEmpty();

        reply.setRuntimeMetadata(null);

        assertThat(reply.textRuntimeMetadata("goal")).isEmpty();
    }
}
