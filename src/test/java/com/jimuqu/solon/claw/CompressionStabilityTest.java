package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.engine.DefaultContextCompressionService;
import com.jimuqu.solon.claw.engine.ToolCallArgumentSanitizer;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 校验上下文压缩的反抖动、失败冷却与摘要合并行为。 */
public class CompressionStabilityTest {

    @Test
    void shouldSkipRecompressionWhenRecentCompressionDidNotGainEnoughNewContext() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-1");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser(repeat("A", 3200)),
                                ChatMessage.ofAssistant(repeat("B", 3200)))));
        session.setLastCompressionAt(System.currentTimeMillis());
        session.setLastCompressionInputTokens(1500);

        SessionRecord compressed = service.compressIfNeeded(session, "system", "next");

        assertThat(compressed.getCompressedSummary()).isNull();
        assertThat(compressed.getLastCompressionAt()).isEqualTo(session.getLastCompressionAt());
    }

    @Test
    void shouldSkipCompressionDuringFailureCooldown() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-2");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser(repeat("A", 3200)),
                                ChatMessage.ofAssistant(repeat("B", 3200)))));
        session.setLastCompressionFailedAt(System.currentTimeMillis());

        SessionRecord compressed = service.compressIfNeeded(session, "system", "next");

        assertThat(compressed.getCompressedSummary()).isNull();
        assertThat(compressed.getLastCompressionInputTokens()).isZero();
    }

    /** 同一空窗口连续两次无效后，第三次必须明确反抖动跳过且不改写消息历史。 */
    @Test
    void shouldThrashSkipAfterTwoNoopCompressionAttemptsWithoutRewritingNdjson() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-noop-thrash");
        session.setMetadataJson("{\"channel\":\"weixin\"}");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofSystem("system"), ChatMessage.ofUser("继续"))));
        String originalNdjson = session.getNdjson();

        CompressionOutcome first = service.compressNowWithOutcome(session, "system", null);
        CompressionOutcome second = service.compressNowWithOutcome(session, "system", null);
        CompressionOutcome third = service.compressNowWithOutcome(session, "system", null);

        assertThat(first.isSkipped()).isTrue();
        assertThat(second.isSkipped()).isTrue();
        assertThat(third.isSkipped()).isTrue();
        assertThat(third.getWarning()).contains("compression-thrash-skip");
        assertThat(session.getNdjson()).isEqualTo(originalNdjson);
        assertThat(ONode.ofJson(session.getMetadataJson()).get("channel").getString())
                .isEqualTo("weixin");
        assertThat(
                        ONode.ofJson(session.getMetadataJson())
                                .get("compressionThrash")
                                .get("count")
                                .getInt())
                .isEqualTo(2);
    }

    /** 压缩后的提供方真实输入 token 未下降时，必须计入连续无效次数。 */
    @Test
    void shouldCountProviderUsageWithoutInputReductionAsIneffective() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = noopSession("s-usage-thrash");
        service.compressNowWithOutcome(session, "system", null);
        markPendingCompression(session, 1600L, 100L);
        session.setLastInputTokens(1600L);
        session.setLastUsageAt(200L);

        CompressionOutcome outcome = service.compressNowWithOutcome(session, "system", null);

        assertThat(outcome.isSkipped()).isTrue();
        assertThat(outcome.getWarning()).contains("compression-thrash-skip");
        assertThat(
                        ONode.ofJson(session.getMetadataJson())
                                .get("compressionThrash")
                                .get("count")
                                .getInt())
                .isEqualTo(2);
    }

    /** 压缩后的真实输入 token 已下降时，必须清除此前累计的无效次数。 */
    @Test
    void shouldResetThrashCountWhenProviderInputTokensDecrease() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = noopSession("s-usage-effective");
        service.compressNowWithOutcome(session, "system", null);
        markPendingCompression(session, 1600L, 100L);
        session.setLastInputTokens(900L);
        session.setLastUsageAt(200L);

        CompressionOutcome outcome = service.compressNowWithOutcome(session, "system", null);

        assertThat(outcome.isSkipped()).isTrue();
        assertThat(outcome.getWarning()).isNull();
        assertThat(
                        ONode.ofJson(session.getMetadataJson())
                                .get("compressionThrash")
                                .get("count")
                                .getInt())
                .isEqualTo(1);
    }

    /** 新消息进入可压缩中间窗口时，必须解除旧窗口的反抖动抑制。 */
    @Test
    void shouldResetThrashSkipWhenNewCompressibleMessagesAppear() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = noopSession("s-new-window");
        service.compressNowWithOutcome(session, "system", null);
        service.compressNowWithOutcome(session, "system", null);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("旧请求：" + repeat("A", 4000)),
                                ChatMessage.ofAssistant("已完成前置分析"),
                                ChatMessage.ofUser("继续"),
                                ChatMessage.ofAssistant("处理中"))));

        CompressionOutcome outcome = service.compressNowWithOutcome(session, "system", null);

        assertThat(outcome.isCompressed()).isTrue();
        assertThat(outcome.getWarning()).isNull();
    }

    /** 工具参数变化属于新的压缩窗口，不能沿用旧窗口的反抖动计数。 */
    @Test
    void shouldResetThrashWindowWhenAssistantToolCallArgumentsChange() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-tool-call-window");
        session.setNdjson(MessageSupport.toNdjson(compressionMessages("{\"path\":\"old.md\"}")));
        String oldFingerprint = compressionWindowFingerprint(service, session);
        session.setMetadataJson(
                "{\"compressionThrash\":{\"count\":2,\"fingerprint\":\""
                        + oldFingerprint
                        + "\",\"baselineInputTokens\":0,\"compressionAt\":0}}");
        session.setNdjson(MessageSupport.toNdjson(compressionMessages("{\"path\":\"new.md\"}")));

        CompressionOutcome outcome = service.compressNowWithOutcome(session, "system", null);

        assertThat(outcome.isCompressed()).isTrue();
        assertThat(outcome.getWarning()).isNull();
    }

    @Test
    void shouldMergePreviousSummaryWhenCompressingAgain() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-3");
        session.setCompressedSummary(CompressionConstants.SUMMARY_PREFIX + "\n旧摘要内容");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("目标：完成同步"),
                                ChatMessage.ofAssistant(
                                        CompressionConstants.SUMMARY_PREFIX + "\n旧摘要内容"),
                                ChatMessage.ofAssistant("已经完成第一步并修改多个文件。"),
                                ChatMessage.ofTool("tool output " + repeat("C", 500), "tool", "1"),
                                ChatMessage.ofUser("继续推进"),
                                ChatMessage.ofAssistant("继续处理中"),
                                ChatMessage.ofUser("收尾"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary()).contains("Previous Summary");
        assertThat(compressed.getCompressedSummary()).contains("旧摘要内容");
    }

    @Test
    void shouldUseFilterSafeRemainingWorkHeading() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-filter-safe-summary");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("先梳理任务"),
                                ChatMessage.ofAssistant(repeat("中间进展", 400)),
                                ChatMessage.ofUser("继续实现安全审批对齐"),
                                ChatMessage.ofAssistant("处理中"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary()).contains("\nRemaining Work\n");
        assertThat(compressed.getCompressedSummary()).doesNotContain("\nNext Steps\n");
    }

    @Test
    void shouldStripCurrentPrefixFromPreviousSummary() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-previous-current-prefix");
        String storedSummary =
                CompressionConstants.SUMMARY_PREFIX + "\nGoal\n历史目标\n\nProgress\n旧进展";
        session.setCompressedSummary(storedSummary);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofAssistant(storedSummary),
                                ChatMessage.ofAssistant(repeat("中间分析", 800)),
                                ChatMessage.ofUser("新的用户请求"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary()).contains("Previous Summary\nGoal\n历史目标");
        assertThat(compressed.getCompressedSummary())
                .doesNotContain("Previous Summary\n" + CompressionConstants.SUMMARY_PREFIX);
        assertThat(compressed.getCompressedSummary())
                .doesNotContain("Progress\n- " + CompressionConstants.SUMMARY_PREFIX);
    }

    @Test
    void shouldNotDropLatestUserMessageWhenItStartsWithSummaryPrefix() throws Exception {
        AppConfig config = config();
        config.getCompression().setProtectHeadMessages(1);
        config.getCompression().setTailRatio(0.01D);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-latest-user-summary-like");
        String latestUserMessage = CompressionConstants.SUMMARY_PREFIX + "\n请解释这段摘要前缀的含义";
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("旧问题"),
                                ChatMessage.ofAssistant(repeat("中间分析", 800)),
                                ChatMessage.ofUser(latestUserMessage),
                                ChatMessage.ofAssistant("处理中"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getNdjson()).contains("请解释这段摘要前缀的含义");
        assertThat(compressed.getCompressedSummary()).doesNotContain("旧问题");
    }

    @Test
    void shouldFlattenNestedPreviousSummaryAndCapSummaryLength() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-4");
        session.setCompressedSummary(
                CompressionConstants.SUMMARY_PREFIX
                        + "\nPrevious Summary\n更早摘要\n\nGoal\n第一次目标\n\nProgress\n"
                        + repeat("历史进展", 120));
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("目标：持续跟进 code review"),
                                ChatMessage.ofAssistant(session.getCompressedSummary()),
                                ChatMessage.ofAssistant(repeat("中间分析", 1000)),
                                ChatMessage.ofUser("继续推进"),
                                ChatMessage.ofAssistant(repeat("最新进展", 800)),
                                ChatMessage.ofUser("收尾"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(countOccurrences(compressed.getCompressedSummary(), "Previous Summary"))
                .isEqualTo(1);
        assertThat(compressed.getCompressedSummary()).contains("\nGoal\n");
        assertThat(compressed.getCompressedSummary()).contains("\nProgress\n");
        assertThat(compressed.getCompressedSummary().length())
                .isLessThanOrEqualTo(
                        CompressionConstants.SUMMARY_PREFIX.length()
                                + 1
                                + CompressionConstants.MAX_SUMMARY_LENGTH
                                + 3);
    }

    @Test
    void shouldPreferLatestGoalAndDropOldHeadAfterSummaryExists() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-4b");
        session.setCompressedSummary(
                CompressionConstants.SUMMARY_PREFIX + "\nGoal\n老任务\n\nProgress\n旧进展");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("你检测一下是否能找到git之类的工具"),
                                ChatMessage.ofAssistant("git/python/node 都在"),
                                ChatMessage.ofAssistant(session.getCompressedSummary()),
                                ChatMessage.ofAssistant("刚刚检查了 code review 目录"),
                                ChatMessage.ofUser("进度怎么样了"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getNdjson()).doesNotContain("你检测一下是否能找到git之类的工具");
        assertThat(compressed.getCompressedSummary()).contains("进度怎么样了");
        assertThat(compressed.getCompressedSummary()).contains("Previous Summary\nGoal\n老任务");
    }

    @Test
    void shouldForceCompressionWhenRecentRealInputAlreadyExceededThreshold() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-5");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("先做一次检查"),
                                ChatMessage.ofAssistant(repeat("A", 200)),
                                ChatMessage.ofUser("继续"))));
        session.setLastInputTokens(1600);
        session.setLastUsageAt(System.currentTimeMillis());
        session.setLastCompressionAt(System.currentTimeMillis() - 120_000L);

        SessionRecord compressed = service.compressIfNeeded(session, "system", "下一轮继续");

        assertThat(compressed.getCompressedSummary()).contains(CompressionConstants.SUMMARY_PREFIX);
        assertThat(compressed.getLastCompressionInputTokens()).isGreaterThanOrEqualTo(1600);
    }

    @Test
    void shouldNotTriggerCompressionOnlyBecauseInlineImageDataUriIsLarge() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-inline-image-compression");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser(
                                        "请分析这张图 data:image/png;base64," + repeat("A", 16_384)))));

        SessionRecord compressed = service.compressIfNeeded(session, "system", "继续");

        assertThat(compressed.getCompressedSummary()).isNull();
        assertThat(compressed.getLastCompressionInputTokens()).isZero();
    }

    @Test
    void shouldStillTriggerCompressionForLongPlainText() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-long-text-compression");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("旧请求：" + repeat("A", 16_384)),
                                ChatMessage.ofAssistant("已完成前置分析"),
                                ChatMessage.ofUser("继续"))));

        SessionRecord compressed = service.compressIfNeeded(session, "system", "继续");

        assertThat(compressed.getCompressedSummary()).contains(CompressionConstants.SUMMARY_PREFIX);
        assertThat(compressed.getLastCompressionInputTokens()).isGreaterThanOrEqualTo(1000);
    }

    @Test
    void shouldReturnWarningOutcomeWhenCompressionFails() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-fail");
        session.setCompressedSummary(CompressionConstants.SUMMARY_PREFIX + "\n已有摘要");
        session.setNdjson("{not-valid-json");
        String originalNdjson = session.getNdjson();
        String originalSummary = session.getCompressedSummary();

        CompressionOutcome outcome = service.compressNowWithOutcome(session, "system", "focus");

        assertThat(outcome.isFailed()).isTrue();
        assertThat(outcome.getWarning()).contains("压缩摘要生成失败");
        assertThat(outcome.getWarning()).contains("原始上下文已保留");
        assertThat(outcome.getSession()).isSameAs(session);
        assertThat(session.getNdjson()).isEqualTo(originalNdjson);
        assertThat(session.getCompressedSummary()).isEqualTo(originalSummary);
        assertThat(session.getCompressionFailureCount()).isEqualTo(1);
        assertThat(session.getLastCompressionFailedAt()).isGreaterThan(0L);
    }

    /** 压缩模型必须使用独立路由，并在返回完整结构时替换确定性摘要。 */
    @Test
    void shouldUseConfiguredCompressionModelRoute() throws Exception {
        AppConfig config = config();
        config.getCompression().setSummaryProvider("fast");
        config.getCompression().setSummaryModel("flash-model");
        RecordingCompressionGateway gateway =
                new RecordingCompressionGateway(
                        "Goal\n模型目标\n\nProgress\n模型进展\n\nDecisions\n模型决策\n\nFiles\n文件清单\n\nRemaining Work\n模型待办");
        DefaultContextCompressionService service =
                new DefaultContextCompressionService(config, gateway);
        SessionRecord session = compressibleSession("s-model-compression");

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(gateway.provider).isEqualTo("fast");
        assertThat(gateway.model).isEqualTo("flash-model");
        assertThat(gateway.sessionId).startsWith("context-compression-");
        assertThat(compressed.getCompressedSummary()).contains("模型目标", "模型进展", "模型决策", "模型待办");
    }

    /** 压缩辅助模型异常时必须继续使用确定性摘要，不能让主会话压缩失败。 */
    @Test
    void shouldFallbackToDeterministicSummaryWhenCompressionModelFails() throws Exception {
        DefaultContextCompressionService service =
                new DefaultContextCompressionService(
                        config(),
                        new RecordingCompressionGateway(new IllegalStateException("down")));
        SessionRecord session = compressibleSession("s-model-compression-fallback");

        CompressionOutcome outcome = service.compressNowWithOutcome(session, "system prompt", null);

        assertThat(outcome.isFailed()).isFalse();
        assertThat(outcome.isCompressed()).isTrue();
        assertThat(session.getCompressedSummary())
                .contains("Goal", "Progress", "Decisions", "Files", "Remaining Work")
                .doesNotContain("模型目标");
    }

    /** 压缩模型超过辅助任务预算时必须取消模型线程，并立即使用确定性摘要完成压缩。 */
    @Test
    void shouldCancelTimedOutCompressionModelAndUseDeterministicSummary() throws Exception {
        AppConfig config = config();
        config.getLearning().setAuxiliaryTimeoutSeconds(1);
        InterruptibleCompressionGateway gateway = new InterruptibleCompressionGateway();
        DefaultContextCompressionService service =
                new DefaultContextCompressionService(config, gateway);
        SessionRecord session = compressibleSession("s-model-compression-timeout");
        long startedAt = System.nanoTime();

        try {
            CompressionOutcome outcome =
                    service.compressNowWithOutcome(session, "system prompt", null);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(outcome.isFailed()).isFalse();
            assertThat(outcome.isCompressed()).isTrue();
            assertThat(elapsedMillis).isLessThan(3000L);
            assertThat(session.getCompressedSummary())
                    .contains("Goal", "Progress", "Decisions", "Files", "Remaining Work");
            assertThat(gateway.started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.wasInterrupted).isTrue();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldAlwaysProtectLatestUserMessage() throws Exception {
        AppConfig config = config();
        config.getCompression().setProtectHeadMessages(1);
        config.getCompression().setTailRatio(0.01D);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-last-user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("old goal"),
                                ChatMessage.ofAssistant(repeat("middle", 500)),
                                ChatMessage.ofUser("必须保留的最后用户消息"),
                                ChatMessage.ofAssistant("处理中"))));

        SessionRecord compressed = service.compressNow(session, "system");

        assertThat(compressed.getNdjson()).contains("必须保留的最后用户消息");
    }

    /** 验证压缩摘要和写回的 NDJSON 都保留中文，不把长会话状态写成乱码。 */
    @Test
    void shouldPreserveChineseTextInSummaryAndNdjsonWhenCompressing() throws Exception {
        AppConfig config = config();
        config.getCompression().setProtectHeadMessages(1);
        config.getCompression().setTailRatio(0.01D);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-chinese-compression");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("长期回归 Loop：验证历史状态连续"),
                                ChatMessage.ofAssistant("已经完成日志检索，下一步验证会话恢复。"),
                                ChatMessage.ofUser("长期回归 Loop：请继续检查 todo 状态是否保留"),
                                ChatMessage.ofAssistant("处理中"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary())
                .contains("已经完成日志检索")
                .contains("请继续检查 todo 状态是否保留")
                .doesNotContain("闀挎湡")
                .doesNotContain("�");
        assertThat(compressed.getNdjson())
                .contains("长期回归 Loop")
                .contains("请继续检查 todo 状态是否保留")
                .doesNotContain("闀挎湡")
                .doesNotContain("�");
    }

    @Test
    void shouldPreserveRecentCompactedTurnsInSummaryWithoutCopyingProtectedTail() throws Exception {
        AppConfig config = config();
        config.getCompression().setProtectHeadMessages(1);
        config.getCompression().setTailRatio(0.01D);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-compacted-turns");
        String secret = "ghp_compactionrecentturnsecret12345";
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("排查 /tmp/active.py 的失败分支"),
                                ChatMessage.ofAssistant("已经定位 /tmp/active.py 的 failing branch"),
                                ChatMessage.ofTool(
                                        "ValueError: boom in /tmp/active.py token=" + secret,
                                        "terminal",
                                        "call-old"),
                                ChatMessage.ofAssistant("下一步补 /tmp/active.py 的回归测试"),
                                ChatMessage.ofUser("受保护 tail 请求不要复制到摘要里"),
                                ChatMessage.ofAssistant("处理中"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary()).contains("Last Compacted Turns");
        String compactedTurns =
                section(
                        compressed.getCompressedSummary(),
                        "Last Compacted Turns",
                        "Remaining Work");
        assertThat(compressed.getCompressedSummary())
                .contains("ASSISTANT: 已经定位 /tmp/active.py 的 failing branch")
                .contains("TOOL: ValueError: boom in /tmp/active.py");
        assertThat(compressed.getCompressedSummary()).doesNotContain(secret);
        assertThat(compactedTurns).doesNotContain("受保护 tail 请求不要复制到摘要里");
    }

    @Test
    void shouldShrinkToolArgumentsWithoutBreakingJson() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        Method method =
                DefaultContextCompressionService.class.getDeclaredMethod(
                        "shrinkToolArgumentsJson", String.class, int.class);
        method.setAccessible(true);

        String raw = new ONode().set("path", "demo.txt").set("content", repeat("x", 500)).toJson();
        String shrunk = (String) method.invoke(service, raw, 32);

        ONode parsed = ONode.ofJson(shrunk);
        assertThat(parsed.get("path").getString()).isEqualTo("demo.txt");
        assertThat(parsed.get("content").getString()).endsWith("...[truncated]");
    }

    /** 候选模型切换后，尾部保护预算必须随候选上下文窗口重新计算。 */
    @Test
    void shouldUseCandidateContextWindowForTailBudget() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        Method method =
                DefaultContextCompressionService.class.getDeclaredMethod(
                        "findTailStart", List.class, int.class);
        method.setAccessible(true);
        List<ChatMessage> messages =
                Arrays.asList(
                        ChatMessage.ofAssistant(repeat("a", 1000)),
                        ChatMessage.ofAssistant(repeat("b", 1000)),
                        ChatMessage.ofAssistant(repeat("c", 1000)));

        int smallWindowStart = ((Integer) method.invoke(service, messages, 1024)).intValue();
        int largeWindowStart = ((Integer) method.invoke(service, messages, 10000)).intValue();

        assertThat(smallWindowStart).isGreaterThan(largeWindowStart);
    }

    /** 尾部保护预算必须基于扣除输出预留后的有效阈值。 */
    @Test
    void shouldUseEffectiveThresholdForTailBudget() throws Exception {
        AppConfig config = config();
        config.getCompression().setThresholdPercent(0.5D);
        config.getCompression().setTailRatio(0.5D);
        config.getLlm().setMaxTokens(8000);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        Method method =
                DefaultContextCompressionService.class.getDeclaredMethod(
                        "findTailStart", List.class, int.class);
        method.setAccessible(true);
        List<ChatMessage> messages =
                Arrays.asList(
                        ChatMessage.ofAssistant(repeat("a", 1000)),
                        ChatMessage.ofAssistant(repeat("b", 1000)),
                        ChatMessage.ofAssistant(repeat("c", 1000)));

        int tailStart = ((Integer) method.invoke(service, messages, 10000)).intValue();

        assertThat(tailStart).isGreaterThan(0);
    }

    /** 输出上限占满或超过窗口时，压缩侧阈值必须保守钳制到最小输入预算。 */
    @Test
    void shouldClampThresholdWhenMaxTokensConsumesOrExceedsContextWindow() throws Exception {
        AppConfig config = config();
        config.getCompression().setThresholdPercent(0.5D);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        Method method =
                DefaultContextCompressionService.class.getDeclaredMethod(
                        "effectiveThresholdTokens", int.class);
        method.setAccessible(true);

        config.getLlm().setMaxTokens(10000);
        int equalWindowThreshold = ((Integer) method.invoke(service, 10000)).intValue();
        config.getLlm().setMaxTokens(12000);
        int exceedsWindowThreshold = ((Integer) method.invoke(service, 10000)).intValue();

        assertThat(equalWindowThreshold).isEqualTo(1);
        assertThat(exceedsWindowThreshold).isEqualTo(1);
    }

    @Test
    void shouldRepairCorruptedToolCallArgumentsBeforeCompression() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-corrupted-tool-args");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("old request"),
                                ChatMessage.ofAssistant(repeat("middle", 500)),
                                ChatMessage.ofUser("latest request"),
                                assistantWithRawToolCall(
                                        "call_1", "read_file", "{\"path\":\"/tmp/foo"),
                                ChatMessage.ofTool("existing tool output", "read_file", "call_1"),
                                ChatMessage.ofAssistant("working"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getNdjson()).contains("\"arguments\":\"{}\"");
        assertThat(compressed.getNdjson()).contains(ToolCallArgumentSanitizer.CORRUPTION_MARKER);
        assertThat(compressed.getNdjson()).contains("existing tool output");
    }

    @Test
    void shouldTreatEmptyToolCallArgumentsAsEmptyObjectWithoutMarker() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                assistantWithRawToolCall("call_1", "read_file", ""),
                                ChatMessage.ofTool("ok", "read_file", "call_1")));

        int repaired = ToolCallArgumentSanitizer.sanitize(messages);

        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        Map raw = assistant.getToolCallsRaw().get(0);
        Map function = (Map) raw.get("function");
        assertThat(repaired).isZero();
        assertThat(function.get("arguments")).isEqualTo("{}");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getContent()).isEqualTo("ok");
    }

    @Test
    void shouldRejectNonObjectJsonToolCallArguments() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                assistantWithRawToolCall(
                                        "call_1", "read_file", "[\"path\",\".env\"]"),
                                ChatMessage.ofTool("ok", "read_file", "call_1")));

        int repaired = ToolCallArgumentSanitizer.sanitize(messages);

        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        Map raw = assistant.getToolCallsRaw().get(0);
        Map function = (Map) raw.get("function");
        assertThat(repaired).isEqualTo(1);
        assertThat(function.get("arguments")).isEqualTo("{}");
        assertThat(messages.get(1).getContent())
                .startsWith(ToolCallArgumentSanitizer.CORRUPTION_MARKER);
    }

    @Test
    void shouldSanitizeStructuredToolCallArgumentsTogetherWithRawArguments() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                assistantWithRawAndStructuredToolCall(
                                        "call_1", "read_file", "{\"path\":\"x"),
                                ChatMessage.ofTool("ok", "read_file", "call_1")));

        int repaired = ToolCallArgumentSanitizer.sanitize(messages);

        assertThat(repaired).isEqualTo(1);
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        Map raw = assistant.getToolCallsRaw().get(0);
        Map function = (Map) raw.get("function");
        assertThat(function.get("arguments")).isEqualTo("{}");
        assertThat(assistant.getToolCalls().get(0).getArgumentsStr()).isEqualTo("{}");
        assertThat(assistant.getToolCalls().get(0).getArguments()).isEmpty();
        assertThat(messages.get(1).getContent())
                .startsWith(ToolCallArgumentSanitizer.CORRUPTION_MARKER);
    }

    @Test
    void shouldInsertMarkerToolMessageWhenCorruptedToolResultIsMissing() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                assistantWithRawToolCall("call_1", "read_file", "{\"path\":\"x"),
                                ChatMessage.ofUser("next turn")));

        int repaired = ToolCallArgumentSanitizer.sanitize(messages);

        assertThat(repaired).isEqualTo(1);
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).getRole().name()).isEqualTo("TOOL");
        assertThat(messages.get(1).getContent())
                .isEqualTo(ToolCallArgumentSanitizer.CORRUPTION_MARKER);
        assertThat(messages.get(2).getContent()).isEqualTo("next turn");
    }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        config.getCompression().setEnabled(true);
        config.getCompression().setThresholdPercent(0.5D);
        config.getCompression().setProtectHeadMessages(1);
        config.getCompression().setTailRatio(0.2D);
        config.getLlm().setContextWindowTokens(2000);
        return config;
    }

    /** 创建只有受保护消息、没有可压缩中间窗口的会话。 */
    private SessionRecord noopSession(String sessionId) throws Exception {
        SessionRecord session = new SessionRecord();
        session.setSessionId(sessionId);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofSystem("system"), ChatMessage.ofUser("继续"))));
        return session;
    }

    /** 创建包含可压缩中间历史和受保护尾部的会话。 */
    private SessionRecord compressibleSession(String sessionId) throws Exception {
        SessionRecord session = new SessionRecord();
        session.setSessionId(sessionId);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("完成模型路由"),
                                ChatMessage.ofAssistant(repeat("中间执行记录", 500)),
                                ChatMessage.ofUser("继续完成验证"),
                                ChatMessage.ofAssistant("处理中"))));
        return session;
    }

    /** 在测试元数据中模拟等待提供方 usage 验证的上一轮压缩。 */
    @SuppressWarnings("unchecked")
    private void markPendingCompression(
            SessionRecord session, long baselineInputTokens, long compressionAt) {
        Map<String, Object> metadata =
                ONode.deserialize(session.getMetadataJson(), LinkedHashMap.class);
        Map<String, Object> state = (Map<String, Object>) metadata.get("compressionThrash");
        state.put("baselineInputTokens", Long.valueOf(baselineInputTokens));
        state.put("compressionAt", Long.valueOf(compressionAt));
        session.setMetadataJson(ONode.serialize(metadata));
    }

    /** 构造工具调用位于可压缩窗口、最新用户消息位于受保护尾部的会话。 */
    private List<ChatMessage> compressionMessages(String arguments) {
        return Arrays.asList(
                ChatMessage.ofSystem("system"),
                assistantWithRawAndStructuredToolCall("call_1", "read_file", arguments),
                ChatMessage.ofTool("ok", "read_file", "call_1"),
                ChatMessage.ofUser(repeat("最新请求", 400)));
    }

    /** 读取内部压缩窗口指纹，验证工具参数变化会进入新的反抖动窗口。 */
    private String compressionWindowFingerprint(
            DefaultContextCompressionService service, SessionRecord session) throws Exception {
        Method method =
                DefaultContextCompressionService.class.getDeclaredMethod(
                        "resolveCompressionWindow", SessionRecord.class, int.class);
        method.setAccessible(true);
        Object window = method.invoke(service, session, Integer.valueOf(2000));
        Field field = window.getClass().getDeclaredField("fingerprint");
        field.setAccessible(true);
        return (String) field.get(window);
    }

    private String repeat(String value, int count) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < count; i++) {
            buffer.append(value);
        }
        return buffer.toString();
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while (text != null && token != null && token.length() > 0) {
            int idx = text.indexOf(token, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + token.length();
        }
        return count;
    }

    private String section(String text, String startHeader, String endHeader) {
        int start = text.indexOf(startHeader);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int contentStart = start + startHeader.length();
        int end = text.indexOf(endHeader, contentStart);
        assertThat(end).isGreaterThan(contentStart);
        return text.substring(contentStart, end);
    }

    private AssistantMessage assistantWithRawToolCall(
            String callId, String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", name);
        function.put("arguments", arguments);

        Map<String, Object> call = new LinkedHashMap<String, Object>();
        call.put("id", callId);
        call.put("type", "function");
        call.put("function", function);

        List<Map> rawCalls = new ArrayList<Map>();
        rawCalls.add(call);
        return new AssistantMessage("", false, null, rawCalls, null, null);
    }

    private AssistantMessage assistantWithRawAndStructuredToolCall(
            String callId, String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", name);
        function.put("arguments", arguments);

        Map<String, Object> call = new LinkedHashMap<String, Object>();
        call.put("id", callId);
        call.put("type", "function");
        call.put("function", function);

        List<Map> rawCalls = new ArrayList<Map>();
        rawCalls.add(call);
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        toolCalls.add(new ToolCall("0", callId, name, arguments, null));
        return new AssistantMessage("", false, null, rawCalls, toolCalls, null);
    }

    /** 记录压缩辅助会话路由并返回预设结果。 */
    private static class RecordingCompressionGateway extends FakeLlmGateway {
        /** 预设模型正文。 */
        private final String response;

        /** 预设模型异常。 */
        private final RuntimeException failure;

        /** 收到的 Provider 路由。 */
        private String provider;

        /** 收到的模型路由。 */
        private String model;

        /** 收到的辅助会话标识。 */
        private String sessionId;

        /** 创建返回文本的压缩模型替身。 */
        private RecordingCompressionGateway(String response) {
            this.response = response;
            this.failure = null;
        }

        /** 创建抛出异常的压缩模型替身。 */
        private RecordingCompressionGateway(RuntimeException failure) {
            this.response = null;
            this.failure = failure;
        }

        /** 记录独立路由并返回或抛出预设结果。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            provider = session.getTransientProviderOverride();
            model = session.getTransientModelOverride();
            sessionId = session.getSessionId();
            if (failure != null) {
                throw failure;
            }
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant(response));
            return result;
        }
    }

    /** 模拟永不主动返回但能响应线程中断的压缩模型。 */
    private static class InterruptibleCompressionGateway extends FakeLlmGateway {
        /** 标记模型线程已经开始执行。 */
        private final CountDownLatch started = new CountDownLatch(1);

        /** 标记模型线程已经收到取消中断。 */
        private final CountDownLatch interrupted = new CountDownLatch(1);

        /** 记录模型线程是否按预期被中断。 */
        private final AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        /** 阻塞模型调用，直到压缩服务超时后通过 Future.cancel(true) 中断。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new IllegalStateException("阻塞模型不应自然返回");
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }
}
