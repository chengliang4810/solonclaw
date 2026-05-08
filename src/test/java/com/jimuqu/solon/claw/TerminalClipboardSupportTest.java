package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.TerminalClipboardSupport;
import java.io.PrintWriter;
import java.io.StringWriter;
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
}
