package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.storage.repository.SqliteChannelStateRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证钉钉渠道使用自定义 emotion 表示消息处理状态。 */
public class DingTalkProcessingEmotionTest {
    @Test
    void shouldSendThinkingThenRecallAndSendDoneOnSuccess() throws Exception {
        RecordingDingTalkAdapter adapter = adapter();
        GatewayMessage message = message("open-cid-1", "msg-1");

        adapter.onProcessingStart(message);
        adapter.onProcessingComplete(message, ProcessingOutcome.SUCCESS);

        assertThat(adapter.calls())
                .containsExactly(
                        "reply:open-cid-1:msg-1:🤔Thinking",
                        "recall:open-cid-1:msg-1:🤔Thinking",
                        "reply:open-cid-1:msg-1:🥳Done");
    }

    @Test
    void shouldRecallThinkingOnFailureWithoutDone() throws Exception {
        RecordingDingTalkAdapter adapter = adapter();
        GatewayMessage message = message("open-cid-2", "msg-2");

        adapter.onProcessingStart(message);
        adapter.onProcessingComplete(message, ProcessingOutcome.FAILURE);

        assertThat(adapter.calls())
                .containsExactly(
                        "reply:open-cid-2:msg-2:🤔Thinking", "recall:open-cid-2:msg-2:🤔Thinking");
    }

    @Test
    void shouldIgnoreDuplicateStartAndCompletion() throws Exception {
        RecordingDingTalkAdapter adapter = adapter();
        GatewayMessage message = message("open-cid-3", "msg-3");

        adapter.onProcessingStart(message);
        adapter.onProcessingStart(message);
        adapter.onProcessingComplete(message, ProcessingOutcome.SUCCESS);
        adapter.onProcessingComplete(message, ProcessingOutcome.SUCCESS);

        assertThat(adapter.calls())
                .containsExactly(
                        "reply:open-cid-3:msg-3:🤔Thinking",
                        "recall:open-cid-3:msg-3:🤔Thinking",
                        "reply:open-cid-3:msg-3:🥳Done");
    }

    @Test
    void shouldIgnoreBlankMessageIdOrConversationId() throws Exception {
        RecordingDingTalkAdapter adapter = adapter();

        adapter.onProcessingStart(message("", "msg-4"));
        adapter.onProcessingStart(message("open-cid-4", ""));
        adapter.onProcessingComplete(message("", "msg-4"), ProcessingOutcome.SUCCESS);
        adapter.onProcessingComplete(message("open-cid-4", ""), ProcessingOutcome.SUCCESS);

        assertThat(adapter.calls()).isEmpty();
    }

    private GatewayMessage message(String chatId, String threadId) {
        GatewayMessage message = new GatewayMessage(PlatformType.DINGTALK, chatId, "user-1", "hi");
        message.setThreadId(threadId);
        return message;
    }

    private RecordingDingTalkAdapter adapter() throws Exception {
        AppConfig appConfig = new AppConfig();
        File runtimeHome = Files.createTempDirectory("solon-claw-dingtalk-emotion").toFile();
        appConfig.getRuntime().setHome(runtimeHome.getAbsolutePath());
        appConfig.getRuntime().setContextDir(new File(runtimeHome, "context").getAbsolutePath());
        appConfig.getRuntime().setSkillsDir(new File(runtimeHome, "skills").getAbsolutePath());
        appConfig.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        appConfig
                .getRuntime()
                .setStateDb(new File(new File(runtimeHome, "data"), "state.db").getAbsolutePath());
        appConfig.getChannels().getDingtalk().setEnabled(true);
        appConfig.getChannels().getDingtalk().setClientId("app-key");
        appConfig.getChannels().getDingtalk().setClientSecret("app-secret");
        appConfig.getChannels().getDingtalk().setRobotCode("robot-code");
        ChannelStateRepository stateRepository =
                new SqliteChannelStateRepository(new SqliteDatabase(appConfig));
        return new RecordingDingTalkAdapter(
                appConfig.getChannels().getDingtalk(),
                stateRepository,
                new AttachmentCacheService(appConfig));
    }

    /** 记录钉钉 emotion API 调用的测试适配器。 */
    private static class RecordingDingTalkAdapter extends DingTalkChannelAdapter {
        private final List<String> calls = new ArrayList<String>();

        RecordingDingTalkAdapter(
                AppConfig.ChannelConfig config,
                ChannelStateRepository channelStateRepository,
                AttachmentCacheService attachmentCacheService) {
            super(config, channelStateRepository, attachmentCacheService);
        }

        @Override
        protected void sendProcessingEmotion(
                String conversationId, String messageId, String emotionName, boolean recall) {
            calls.add(
                    (recall ? "recall" : "reply")
                            + ":"
                            + conversationId
                            + ":"
                            + messageId
                            + ":"
                            + emotionName);
        }

        List<String> calls() {
            return calls;
        }
    }
}
