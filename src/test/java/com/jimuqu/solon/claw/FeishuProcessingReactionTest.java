package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.gateway.platform.feishu.FeishuChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证飞书渠道使用 message reaction 表示消息处理状态。 */
public class FeishuProcessingReactionTest {
    @Test
    void shouldAddTypingThenRemoveItOnSuccess() throws Exception {
        RecordingFeishuAdapter adapter = adapter();
        GatewayMessage message = message("om_1");

        adapter.onProcessingStart(message);
        adapter.onProcessingComplete(message, ProcessingOutcome.SUCCESS);

        assertThat(adapter.calls()).containsExactly("create:om_1:Typing", "delete:om_1:r-1");
    }

    @Test
    void shouldSwapTypingForCrossMarkOnFailure() throws Exception {
        RecordingFeishuAdapter adapter = adapter();
        GatewayMessage message = message("om_2");

        adapter.onProcessingStart(message);
        adapter.onProcessingComplete(message, ProcessingOutcome.FAILURE);

        assertThat(adapter.calls())
                .containsExactly("create:om_2:Typing", "delete:om_2:r-1", "create:om_2:CrossMark");
    }

    @Test
    void shouldNotAddFailureWhenTypingDeleteFails() throws Exception {
        RecordingFeishuAdapter adapter = adapter();
        adapter.deleteSuccess = false;
        GatewayMessage message = message("om_3");

        adapter.onProcessingStart(message);
        adapter.onProcessingComplete(message, ProcessingOutcome.FAILURE);

        assertThat(adapter.calls()).containsExactly("create:om_3:Typing", "delete:om_3:r-1");
    }

    @Test
    void shouldIgnoreBlankMessageIdAndDuplicateStart() throws Exception {
        RecordingFeishuAdapter adapter = adapter();
        GatewayMessage blank = message("");
        GatewayMessage message = message("om_4");

        adapter.onProcessingStart(blank);
        adapter.onProcessingStart(message);
        adapter.onProcessingStart(message);

        assertThat(adapter.calls()).containsExactly("create:om_4:Typing");
    }

    private GatewayMessage message(String threadId) {
        GatewayMessage message =
                new GatewayMessage(PlatformType.FEISHU, "oc_chat", "ou_user", "hi");
        message.setThreadId(threadId);
        return message;
    }

    private RecordingFeishuAdapter adapter() throws Exception {
        File home = Files.createTempDirectory("solon-claw-feishu-reaction").toFile();
        AppConfig appConfig = new AppConfig();
        appConfig.getRuntime().setHome(home.getAbsolutePath());
        appConfig.getRuntime().setCacheDir(new File(home, "cache").getAbsolutePath());
        AppConfig.ChannelConfig config = new AppConfig.ChannelConfig();
        config.setEnabled(true);
        config.setAppId("cli_xxx");
        config.setAppSecret("secret");
        return new RecordingFeishuAdapter(config, new AttachmentCacheService(appConfig));
    }

    /** 记录飞书 reaction API 调用的测试适配器。 */
    private static class RecordingFeishuAdapter extends FeishuChannelAdapter {
        private final List<String> calls = new ArrayList<String>();
        private boolean deleteSuccess = true;

        RecordingFeishuAdapter(
                AppConfig.ChannelConfig config, AttachmentCacheService attachmentCacheService) {
            super(config, attachmentCacheService);
        }

        @Override
        protected String createProcessingReaction(String messageId, String emojiType) {
            calls.add("create:" + messageId + ":" + emojiType);
            return "r-1";
        }

        @Override
        protected boolean deleteProcessingReaction(String messageId, String reactionId) {
            calls.add("delete:" + messageId + ":" + reactionId);
            return deleteSuccess;
        }

        List<String> calls() {
            return calls;
        }
    }
}
