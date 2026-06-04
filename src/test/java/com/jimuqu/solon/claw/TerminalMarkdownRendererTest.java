package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.TerminalMarkdownRenderer;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

public class TerminalMarkdownRendererTest {
    @Test
    void shouldRenderMarkdownLinesForTerminal() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer();

        String rendered = renderer.render("# 标题\n- 项目\n1. 步骤\n> 引用\n```java\nint x = 1;\n```\n");

        assertThat(rendered)
                .contains("\u001B[1m\u001B[36m标题\u001B[0m")
                .contains("\u001B[33m• \u001B[0m项目")
                .contains("\u001B[33m1. \u001B[0m步骤")
                .contains("\u001B[2m│ 引用\u001B[0m")
                .contains("\u001B[2m``` java\u001B[0m")
                .contains("\u001B[32mint x = 1;\u001B[0m");
    }

    @Test
    void shouldPreserveAssistantTextWhileRenderingMarkdown() {
        StringWriter buffer = new StringWriter();
        ConsoleEventSink sink = new ConsoleEventSink(new PrintWriter(buffer), false);

        sink.onAssistantDelta("# 标题\n");
        sink.onAssistantDelta("- 项目");
        sink.onRunCompleted("session", null, null);

        assertThat(sink.assistantText()).isEqualTo("# 标题\n- 项目");
        assertThat(buffer.toString())
                .contains("\u001B[1m\u001B[36m标题\u001B[0m")
                .contains("\u001B[33m• \u001B[0m项目");
    }

    @Test
    void shouldBufferPartialMarkdownLinesUntilComplete() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer();

        assertThat(renderer.render("# 标")).isEmpty();
        assertThat(renderer.render("题\n")).contains("\u001B[1m\u001B[36m标题\u001B[0m");
        assertThat(renderer.render("- 项")).isEmpty();
        assertThat(renderer.flush()).contains("\u001B[33m• \u001B[0m项");
    }
}
