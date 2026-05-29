package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.LocalTerminalTaskRunner;
import com.jimuqu.solon.claw.cli.LocalTerminalTranscript;
import com.jimuqu.solon.claw.cli.TerminalLifecycleSummary;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class TerminalLifecycleSummaryTest {
    @Test
    void shouldRenderShutdownSummaryFromTerminalState() throws Exception {
        LocalTerminalTaskRunner runner = new LocalTerminalTaskRunner(new PrintWriter(new java.io.StringWriter()));
        runner.submit(
                        "done",
                        new Callable<Integer>() {
                            @Override
                            public Integer call() {
                                return Integer.valueOf(0);
                            }
                        })
                .get(1, TimeUnit.SECONDS);
        LocalTerminalTranscript transcript = new LocalTerminalTranscript();
        transcript.user("hello");
        transcript.assistant("world");
        ConsoleEventSink.EventSnapshot events = eventSnapshot(4, 2, 1);

        String summary =
                TerminalLifecycleSummary.render("work", runner, transcript, events, true);

        assertThat(summary)
                .contains("终端会话结束")
                .contains("session=work")
                .contains("tasks=0/1")
                .contains("transcript=2")
                .contains("events=4 tools=2 failures=1")
                .contains("copy=ready");
        runner.close();
    }

    @Test
    void shouldRenderShutdownSummaryFallbacks() {
        String summary = TerminalLifecycleSummary.render(null, null, null, null, false);

        assertThat(summary)
                .contains("session=-")
                .contains("tasks=0/0")
                .contains("transcript=0")
                .contains("events=0 tools=0 failures=0")
                .contains("copy=empty");
    }

    private ConsoleEventSink.EventSnapshot eventSnapshot(int total, int tools, int failures)
            throws Exception {
        java.lang.reflect.Constructor<ConsoleEventSink.EventSnapshot> constructor =
                ConsoleEventSink.EventSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(total, tools, failures, Arrays.asList("tool.start shell"));
    }
}
