package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** 验证错误文本脱敏应直接复用共享工具，避免各服务保留一行转发包装。 */
class ErrorTextSupportReuseTest {
    /** 私有safeError包装只会制造重复调用入口，应改为直接调用ErrorTextSupport。 */
    @Test
    void shouldNotKeepPrivateSafeErrorForwarders() throws Exception {
        String[] paths =
                new String[] {
                    "src/main/java/com/jimuqu/solon/claw/gateway/service/ChannelConnectionManager.java",
                    "src/main/java/com/jimuqu/solon/claw/gateway/feedback/GatewayConversationFeedbackSink.java",
                    "src/main/java/com/jimuqu/solon/claw/llm/dialect/RawResponseLoggingChatDialect.java",
                    "src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillHubService.java",
                    "src/main/java/com/jimuqu/solon/claw/skillhub/source/GitHubSkillSource.java",
                    "src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java",
                    "src/main/java/com/jimuqu/solon/claw/web/DomesticQrSetupService.java",
                };
        for (String path : paths) {
            String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            assertFalse(
                    source.contains("return ErrorTextSupport.safeError("),
                    path + " should call ErrorTextSupport.safeError directly at use sites");
        }
    }
}
