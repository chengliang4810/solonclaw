package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.LocalTerminalTranscript;
import org.junit.jupiter.api.Test;

public class LocalTerminalTranscriptTest {
    @Test
    void shouldRecognizeTranscriptCommands() {
        LocalTerminalTranscript transcript = new LocalTerminalTranscript();

        assertThat(transcript.isTranscriptCommand("/transcript")).isTrue();
        assertThat(transcript.isTranscriptCommand("/transcript 20")).isTrue();
        assertThat(transcript.isTranscriptCommand("/history")).isFalse();
    }

    @Test
    void shouldRenderRecentVirtualHistory() {
        LocalTerminalTranscript transcript = new LocalTerminalTranscript();

        transcript.user("hello");
        transcript.assistant("world");
        transcript.user("next");

        String rendered = transcript.render("/transcript 2");

        assertThat(rendered)
                .contains("终端虚拟历史：showing=2/3")
                .contains("assistant: world")
                .contains("user: next")
                .doesNotContain("user: hello");
        assertThat(transcript.lines()).contains("user: hello", "assistant: world", "user: next");
    }

    @Test
    void shouldTrimLongTranscriptText() {
        LocalTerminalTranscript transcript = new LocalTerminalTranscript();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 260; i++) {
            longText.append('x');
        }

        transcript.assistant(longText.toString());

        String rendered = transcript.render("/transcript");

        assertThat(rendered).contains("assistant: ").contains("...");
        assertThat(rendered).doesNotContain(longText.toString());
    }

    @Test
    void shouldRenderEmptyTranscript() {
        LocalTerminalTranscript transcript = new LocalTerminalTranscript();

        assertThat(transcript.render("/transcript")).isEqualTo("当前终端暂无虚拟历史。");
    }
}
