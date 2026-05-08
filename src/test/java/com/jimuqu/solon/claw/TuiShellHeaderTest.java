package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.TuiShell;
import com.jimuqu.solon.claw.config.AppConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class TuiShellHeaderTest {
    @Test
    void shouldRenderStatusLineFromRuntimeModelConfig() throws Exception {
        AppConfig config = new AppConfig();
        config.getModel().setProviderKey("default");
        config.getModel().setDefaultModel("gpt-test");
        config.getLlm().setReasoningEffort("high");
        TuiShell shell =
                new TuiShell(
                        null,
                        new CliMode(CliMode.Kind.TUI, null, "work"),
                        null,
                        config);

        String line = statusLine(shell, "work");

        assertThat(line)
                .contains("session=work")
                .contains("provider=default")
                .contains("model=gpt-test")
                .contains("reasoning=high");
    }

    @Test
    void shouldRenderFallbackStatusLineWhenConfigIsMissing() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));

        String line = statusLine(shell, "tui");

        assertThat(line)
                .contains("session=tui")
                .contains("provider=default")
                .contains("model=-")
                .contains("reasoning=-");
    }

    @Test
    void shouldExposeShortcutHelpLineInHeader() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));

        String header = renderHeader(shell, "tui");

        assertThat(header).contains("Ctrl-G").contains("Ctrl-S").contains("Ctrl-Y");
    }

    @Test
    void shouldRenderLastTerminalEvents() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));
        Field field = TuiShell.class.getDeclaredField("lastEventSnapshot");
        field.setAccessible(true);
        field.set(
                shell,
                eventSnapshot(
                        5,
                        1,
                        0,
                        Arrays.asList("tool.start terminal", "run.done session=tui")));

        String text = renderEvents(shell);

        assertThat(text)
                .contains("最近一次运行事件：total=5 tools=1 failures=0")
                .contains("1. tool.start terminal")
                .contains("2. run.done session=tui");
    }

    @Test
    void shouldRenderEmptyTerminalEvents() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));

        assertThat(renderEvents(shell)).isEqualTo("暂无终端事件。");
    }

    private String statusLine(TuiShell shell, String sessionId) throws Exception {
        Method method = TuiShell.class.getDeclaredMethod("statusLine", String.class);
        method.setAccessible(true);
        return (String) method.invoke(shell, sessionId);
    }

    private String renderEvents(TuiShell shell) throws Exception {
        Method method = TuiShell.class.getDeclaredMethod("renderEvents");
        method.setAccessible(true);
        return (String) method.invoke(shell);
    }

    private String renderHeader(TuiShell shell, String sessionId) throws Exception {
        java.io.StringWriter buffer = new java.io.StringWriter();
        java.io.PrintWriter writer = new java.io.PrintWriter(buffer);
        Method method = TuiShell.class.getDeclaredMethod("renderHeader", java.io.PrintWriter.class, String.class);
        method.setAccessible(true);
        method.invoke(shell, writer, sessionId);
        return buffer.toString();
    }

    private Object eventSnapshot(int total, int tools, int failures, java.util.List<String> events)
            throws Exception {
        java.lang.reflect.Constructor<ConsoleEventSink.EventSnapshot> constructor =
                ConsoleEventSink.EventSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(total, tools, failures, events);
    }
}
