package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

public class CompressionCommandTest {
    @Test
    void shouldCompressCurrentSessionWhenSlashCommandCalled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");
        env.send("admin-chat", "admin-user", "start");

        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("目标：完成一个复杂任务"),
                                ChatMessage.ofAssistant("步骤一已经完成，修改了多个文件并分析了错误。"),
                                ChatMessage.ofTool("tool output " + repeat("A", 600), "tool", "1"),
                                ChatMessage.ofUser("继续下一步"),
                                ChatMessage.ofAssistant("继续处理并准备输出最终结果。"),
                                ChatMessage.ofUser("最后确认一下"))));
        env.sessionRepository.save(session);

        GatewayReply reply =
                env.gatewayService.handle(env.message("admin-chat", "admin-user", "/compress"));
        assertThat(reply.getContent()).contains("上下文压缩");

        SessionRecord updated = env.sessionRepository.findById(session.getSessionId());
        assertThat(updated.getCompressedSummary()).contains(CompressionConstants.SUMMARY_PREFIX);
        assertThat(updated.getNdjson()).contains(CompressionConstants.SUMMARY_PREFIX);
    }

    @Test
    void shouldIncludeManualFocusWhenCompressingCurrentSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");
        env.send("admin-chat", "admin-user", "start");

        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("分析当前问题"),
                                ChatMessage.ofAssistant("已经处理第一部分"),
                                ChatMessage.ofTool("tool output " + repeat("C", 500), "tool", "1"),
                                ChatMessage.ofUser("继续推进"),
                                ChatMessage.ofAssistant("准备发布"),
                                ChatMessage.ofUser(repeat("B", 5000)))));
        env.sessionRepository.save(session);

        GatewayReply reply =
                env.gatewayService.handle(
                        env.message("admin-chat", "admin-user", "/compress 发布流程"));
        SessionRecord updated = env.sessionRepository.findById(session.getSessionId());

        assertThat(reply.getContent()).contains("关注主题");
        assertThat(updated.getCompressedSummary()).contains("Focus");
        assertThat(updated.getCompressedSummary()).contains("发布流程");
    }

    @Test
    void shouldCompactCurrentSessionAsJimuquCompatibleAlias() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");
        env.send("admin-chat", "admin-user", "start");

        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("分析当前问题"),
                                ChatMessage.ofAssistant("已经处理第一部分"),
                                ChatMessage.ofTool("tool output " + repeat("D", 500), "tool", "1"),
                                ChatMessage.ofUser("继续推进"),
                                ChatMessage.ofAssistant("准备发布"),
                                ChatMessage.ofUser(repeat("E", 5000)))));
        env.sessionRepository.save(session);

        GatewayReply reply =
                env.gatewayService.handle(
                        env.message("admin-chat", "admin-user", "/compact 发布流程"));
        SessionRecord updated = env.sessionRepository.findById(session.getSessionId());

        assertThat(reply.getContent()).contains("关注主题");
        assertThat(updated.getCompressedSummary()).contains("Focus");
        assertThat(updated.getCompressedSummary()).contains("发布流程");
    }

    @Test
    void shouldDeferManualCompressionWhileSessionRunIsActive() throws Exception {
        BlockingLlmGateway llmGateway = new BlockingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llmGateway);
        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(() -> env.send("admin-chat", "admin-user", "执行长任务"));
        assertThat(llmGateway.started.await(2L, TimeUnit.SECONDS)).isTrue();

        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("分析当前问题"),
                                ChatMessage.ofAssistant("已经处理第一部分"),
                                ChatMessage.ofTool("tool output " + repeat("D", 500), "tool", "1"),
                                ChatMessage.ofUser("继续推进"),
                                ChatMessage.ofAssistant("准备发布"),
                                ChatMessage.ofUser(repeat("E", 5000)))));
        env.sessionRepository.save(session);

        GatewayReply reply =
                env.gatewayService.handle(
                        env.message("admin-chat", "admin-user", "/compact 发布流程"));
        SessionRecord updated = env.sessionRepository.findById(session.getSessionId());

        assertThat(reply.getContent()).contains("正在运行");
        assertThat(reply.isError()).isTrue();
        assertThat(updated.getCompressedSummary()).isNull();
        assertThat(updated.getNdjson()).doesNotContain(CompressionConstants.SUMMARY_PREFIX);

        llmGateway.release();
        running.get(5L, TimeUnit.SECONDS);
        executorService.shutdownNow();
    }

    private String repeat(String value, int count) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < count; i++) {
            buffer.append(value);
        }
        return buffer.toString();
    }

    private static class BlockingLlmGateway implements LlmGateway {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            started.countDown();
            release.await(5L, TimeUnit.SECONDS);
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant("echo:" + userMessage));
            result.setNdjson(
                    MessageSupport.toNdjson(
                            Arrays.asList(ChatMessage.ofAssistant("echo:" + userMessage))));
            result.setRawResponse("fake");
            result.setProvider("openai-responses");
            result.setModel("gpt-5.4");
            result.setInputTokens(1L);
            result.setOutputTokens(1L);
            result.setTotalTokens(2L);
            return result;
        }

        @Override
        public LlmResult resume(SessionRecord session, String systemPrompt, List<Object> toolObjects)
                throws Exception {
            return chat(session, systemPrompt, null, toolObjects);
        }

        private void release() {
            release.countDown();
        }
    }
}
