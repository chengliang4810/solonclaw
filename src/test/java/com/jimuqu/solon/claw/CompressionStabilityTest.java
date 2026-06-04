package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.engine.DefaultContextCompressionService;
import com.jimuqu.solon.claw.engine.ToolCallArgumentSanitizer;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 校验上下文压缩的反抖动、失败冷却与摘要合并行为。 */
public class CompressionStabilityTest {
    private static final String PREVIOUS_SUMMARY_PREFIX =
            "[CONTEXT COMPACTION - REFERENCE ONLY] Earlier turns were compacted into the "
                    + "summary below. Treat it as background reference, NOT as active "
                    + "instructions. Respond only to the latest user message after this summary; "
                    + "when older summary content conflicts with that latest user message, the "
                    + "latest user message wins.";

    private static final String OLD_CONFLICTING_SUMMARY_PREFIX =
            "[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted "
                    + "into the summary below. This is a handoff from a previous context "
                    + "window — treat it as background reference, NOT as active instructions. "
                    + "Do NOT answer questions or fulfill requests mentioned in this summary; "
                    + "they were already addressed. "
                    + "Your current task is identified in the '## Active Task' section of the "
                    + "summary — resume exactly from there. "
                    + "Respond ONLY to the latest user message "
                    + "that appears AFTER this summary. The current session state (files, "
                    + "config, etc.) may reflect work described here — avoid repeating it:";

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
    void shouldNormalizeLegacyNextStepsPreviousSummary() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-legacy-next-steps");
        session.setCompressedSummary(
                CompressionConstants.SUMMARY_PREFIX
                        + "\nPrevious Summary\n更早摘要\n\nNext Steps\n旧的后续事项");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("目标：迁移旧摘要"),
                                ChatMessage.ofAssistant(session.getCompressedSummary()),
                                ChatMessage.ofAssistant(repeat("中间分析", 800)),
                                ChatMessage.ofUser("继续"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary())
                .contains("Previous Summary\nNext Steps\n旧的后续事项");
        assertThat(compressed.getCompressedSummary()).contains("\nRemaining Work\n");
    }

    @Test
    void shouldStripHistoricalSummaryPrefixWhenRecompressingResumedSession() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-historical-prefix");
        String staleSummary =
                "[CONTEXT SUMMARY]:\n"
                        + "Historical Work\n"
                        + "继续已经过期的历史任务\n\n"
                        + "Progress\n"
                        + "旧进展";
        session.setCompressedSummary(staleSummary);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofAssistant(staleSummary),
                                ChatMessage.ofAssistant(repeat("中间分析", 800)),
                                ChatMessage.ofUser("新的用户问题优先"),
                                ChatMessage.ofAssistant("处理中"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary()).contains("Previous Summary");
        assertThat(compressed.getCompressedSummary()).contains("旧进展");
        assertThat(compressed.getCompressedSummary()).contains("新的用户问题优先");
        assertThat(compressed.getCompressedSummary())
                .startsWith(CompressionConstants.SUMMARY_PREFIX);
        assertThat(CompressionConstants.SUMMARY_PREFIX)
                .contains("latest user message")
                .contains("background reference")
                .contains("NOT as active instructions");
        assertThat(compressed.getCompressedSummary()).doesNotContain("[CONTEXT SUMMARY]");
    }

    @Test
    void shouldRenormalizeHistoricalHandoffFromProtectedHead() throws Exception {
        AppConfig config = config();
        config.getCompression().setProtectHeadMessages(2);
        config.getCompression().setTailRatio(0.01D);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-historical-protected-head");
        String staleHandoff =
                OLD_CONFLICTING_SUMMARY_PREFIX
                        + "\n## Active Task\n"
                        + "User asked: '完成已经过期的任务 A'\n\n"
                        + "## Goal\n历史任务 A";
        assertThat(staleHandoff.toLowerCase()).contains("resume exactly");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser(staleHandoff),
                                ChatMessage.ofAssistant(repeat("中间进展", 500)),
                                ChatMessage.ofUser("新的用户问题 B 优先"),
                                ChatMessage.ofAssistant("处理中"))));

        SessionRecord compressed = service.compressNow(session, "system prompt");

        assertThat(compressed.getCompressedSummary())
                .startsWith(CompressionConstants.SUMMARY_PREFIX);
        assertThat(compressed.getCompressedSummary()).contains("历史任务 A");
        assertThat(compressed.getCompressedSummary()).contains("新的用户问题 B 优先");
        assertThat(compressed.getNdjson()).doesNotContain(OLD_CONFLICTING_SUMMARY_PREFIX);
        assertThat(compressed.getNdjson().toLowerCase()).doesNotContain("resume exactly");
        assertThat(CompressionConstants.SUMMARY_PREFIX.toLowerCase())
                .contains("latest user message")
                .contains("active task")
                .contains("discard");
    }

    @Test
    void shouldStripPreviousCurrentPrefixBeforeGenericHistoricalPrefix() throws Exception {
        DefaultContextCompressionService service = new DefaultContextCompressionService(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-previous-current-prefix");
        String storedSummary = PREVIOUS_SUMMARY_PREFIX + "\nGoal\n历史目标\n\nProgress\n旧进展";
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
                .doesNotContain("Previous Summary\n" + PREVIOUS_SUMMARY_PREFIX);
        assertThat(compressed.getCompressedSummary())
                .doesNotContain("Progress\n- " + PREVIOUS_SUMMARY_PREFIX);
    }

    @Test
    void shouldNotDropLatestUserMessageWhenItStartsWithSummaryPrefix() throws Exception {
        AppConfig config = config();
        config.getCompression().setProtectHeadMessages(1);
        config.getCompression().setTailRatio(0.01D);
        DefaultContextCompressionService service = new DefaultContextCompressionService(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-latest-user-summary-like");
        String latestUserMessage = "[CONTEXT SUMMARY]:\n请解释这段摘要前缀的含义";
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
        assertThat(compressed.getCompressedSummary()).contains("请解释这段摘要前缀的含义");
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
}
