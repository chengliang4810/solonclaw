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
        assertThat(transcript.isTranscriptCommand("/transcript show 1")).isTrue();
        assertThat(transcript.isTranscriptCommand("/transcript inspect 1")).isTrue();
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
                .contains("/transcript show <编号>")
                .contains("assistant: world")
                .contains("user: next")
                .doesNotContain("user: hello");
        assertThat(transcript.count()).isEqualTo(3);
        assertThat(transcript.lines()).contains("user: hello", "assistant: world", "user: next");
    }

    @Test
    void shouldRenderFullTranscriptEntryAndRedactSecrets() {
        LocalTerminalTranscript transcript = new LocalTerminalTranscript();

        transcript.user("hello\napi_key=sk-1234567890abcdef");
        transcript.assistant("world");

        String rendered = transcript.render("/transcript show 1");

        assertThat(rendered)
                .contains("终端虚拟历史详情")
                .contains("index=1")
                .contains("role=user")
                .contains("hello")
                .contains("api_key=***")
                .doesNotContain("sk-1234567890abcdef");
        assertThat(transcript.render("/transcript show")).contains("使用：/transcript show <编号>");
        assertThat(transcript.render("/transcript inspect 9")).contains("当前可用范围：1-2");
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
        String full = transcript.render("/transcript show 1");

        assertThat(rendered).contains("assistant: ").contains("...");
        assertThat(rendered).doesNotContain(longText.toString());
        assertThat(full).contains(longText.toString());
    }

    @Test
    void shouldRenderEmptyTranscript() {
        LocalTerminalTranscript transcript = new LocalTerminalTranscript();

        assertThat(transcript.render("/transcript")).isEqualTo("当前终端暂无虚拟历史。");
    }
}
