package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.TerminalClipboardSupport;
import com.jimuqu.solon.claw.core.model.LlmResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import org.junit.jupiter.api.Test;

public class TerminalClipboardSupportTest {
    @Test
    void shouldWriteOsc52ClipboardSequence() {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);

        boolean copied = TerminalClipboardSupport.copy(writer, "hello");

        assertThat(copied).isTrue();
        assertThat(buffer.toString()).isEqualTo("\u001B]52;c;aGVsbG8=\u0007");
    }

    @Test
    void shouldIgnoreBlankClipboardText() {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);

        boolean copied = TerminalClipboardSupport.copy(writer, "");

        assertThat(copied).isFalse();
        assertThat(buffer.toString()).isEmpty();
    }

    @Test
    void shouldCaptureAssistantTextForCopyCommand() {
        StringWriter buffer = new StringWriter();
        ConsoleEventSink sink = new ConsoleEventSink(new PrintWriter(buffer), false);

        sink.onAssistantDelta("hello");
        sink.onAssistantDelta(" world");

        assertThat(sink.assistantText()).isEqualTo("hello world");
        assertThat(sink.hasAssistantOutput()).isTrue();
    }

    @Test
    void shouldRenderVerboseRunFooter() {
        StringWriter buffer = new StringWriter();
        ConsoleEventSink sink = new ConsoleEventSink(new PrintWriter(buffer), true);
        LlmResult result = new LlmResult();
        result.setProvider("default");
        result.setModel("gpt-test");
        result.setInputTokens(10);
        result.setOutputTokens(5);
        result.setReasoningTokens(2);
        result.setCacheReadTokens(3);
        result.setCacheWriteTokens(4);
        result.setTotalTokens(22);

        sink.onRunCompleted("session", "done", result);

        assertThat(buffer.toString())
                .contains("model=default/gpt-test")
                .contains("tokens total=22 input=10 output=5 reasoning=2 cache_read=3 cache_write=4");
    }

    @Test
    void shouldRenderVerboseSidecarEventSummary() {
        StringWriter buffer = new StringWriter();
        ConsoleEventSink sink = new ConsoleEventSink(new PrintWriter(buffer), true);

        sink.onRunStarted("session");
        sink.onAttemptStarted("run-1", 1, "default", "gpt-test");
        sink.onToolStarted("terminal", Collections.<String, Object>emptyMap());
        sink.onToolCompleted("terminal", "ok", 12L);
        sink.onAttemptCompleted("run-1", 1, "success", "ok");
        sink.onRunCompleted("session", "done", null);

        assertThat(buffer.toString())
                .contains("events total=6 tools=1 failures=0")
                .contains("tool.done terminal 12ms")
                .contains("attempt.done attempt=1 status=success ok")
                .contains("run.done session=session");
    }

    @Test
    void shouldExposeSidecarEventSnapshotForTuiEventsCommand() {
        ConsoleEventSink sink = new ConsoleEventSink(new PrintWriter(new StringWriter()), true);

        sink.onRunStarted("session");
        sink.onToolStarted("terminal", Collections.<String, Object>emptyMap());
        sink.onToolCompleted("terminal", "ok", 12L);
        sink.onRunCompleted("session", "done", null);

        ConsoleEventSink.EventSnapshot snapshot = sink.eventSnapshot();
        assertThat(snapshot.getEventCount()).isEqualTo(4);
        assertThat(snapshot.getToolCount()).isEqualTo(1);
        assertThat(snapshot.getFailureCount()).isEqualTo(0);
        assertThat(snapshot.getRecentEvents())
                .contains("tool.start terminal")
                .contains("tool.done terminal 12ms")
                .contains("run.done session=session");
    }
}
