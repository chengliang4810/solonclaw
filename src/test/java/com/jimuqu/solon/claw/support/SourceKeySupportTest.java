package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import org.junit.jupiter.api.Test;

/** 命名 Profile 来源键解析回归测试。 */
class SourceKeySupportTest {
    /** 命名 Profile 前缀不应被误判为渠道平台。 */
    @Test
    void shouldParseProfileScopedSourceKey() {
        String sourceKey = "profile:worker:FEISHU:room:thread:user";

        assertThat(SourceKeySupport.split(sourceKey))
                .containsExactly("FEISHU", "room", "user", "thread");
        DeliveryRequest request = SourceKeySupport.toDeliveryRequest(sourceKey, "done");
        assertThat(request.getProfile()).isEqualTo("worker");
        assertThat(request.getPlatform()).isEqualTo(PlatformType.FEISHU);
        assertThat(request.getChatId()).isEqualTo("room");
        assertThat(request.getThreadId()).isEqualTo("thread");
        assertThat(request.getUserId()).isEqualTo("user");
        assertThat(request.getConversationSourceKey()).isEqualTo(sourceKey);
    }

    /** 来源键构造必须同时覆盖默认 Profile、命名 Profile 和可选线程。 */
    @Test
    void shouldBuildProfileScopedSourceKey() {
        assertThat(SourceKeySupport.build(null, PlatformType.WEIXIN, "room", null, "user"))
                .isEqualTo("WEIXIN:room:user");
        assertThat(SourceKeySupport.build("Worker", PlatformType.FEISHU, "room", "thread", "user"))
                .isEqualTo("profile:worker:FEISHU:room:thread:user");
    }
}
