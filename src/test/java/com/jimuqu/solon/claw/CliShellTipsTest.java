package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliShell;
import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import java.util.Arrays;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

public class CliShellTipsTest {
    @Test
    void shouldHandleTipsLocallyAndExposeCompletion() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));

        assertThat(commandList()).contains("/tips");
        assertThat(shouldHandleInline(shell, "/tips")).isTrue();

        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        int exitCode = sendOnce(shell, writer, "/tips");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString()).contains("终端提示").contains("/queue").contains("/steer");
    }

    @Test
    void shouldRenderEventsLocallyLikeTerminalUi() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));

        assertThat(commandList()).contains("/events");
        assertThat(shouldHandleInline(shell, "/events")).isTrue();
        assertThat(renderEvents(shell)).isEqualTo("暂无终端事件。");

        setField(shell, "lastEventSnapshot", eventSnapshot(3, 1, 1));
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        int exitCode = sendOnce(shell, writer, "/events");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString())
                .contains("最近一次运行事件：total=3 tools=1 failures=1")
                .contains("1. tool.start terminal")
                .contains("2. run.failed session=cli-test");
    }

    private static String[] commandList() throws Exception {
        Field field = CliShell.class.getDeclaredField("COMMANDS");
        field.setAccessible(true);
        return (String[]) field.get(null);
    }

    private static boolean shouldHandleInline(CliShell shell, String input) throws Exception {
        Method method = CliShell.class.getDeclaredMethod("shouldHandleInline", String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(shell, input)).booleanValue();
    }

    private static int sendOnce(CliShell shell, PrintWriter writer, String input) throws Exception {
        Method method =
                CliShell.class.getDeclaredMethod(
                        "sendOnce",
                        com.jimuqu.solon.claw.cli.LocalTerminalTaskRunner.class,
                        PrintWriter.class,
                        String.class,
                        String.class,
                        boolean.class);
        method.setAccessible(true);
        return ((Integer) method.invoke(shell, null, writer, "cli-test", input, Boolean.TRUE)).intValue();
    }

    private static String renderEvents(CliShell shell) throws Exception {
        Method method = CliShell.class.getDeclaredMethod("renderEvents");
        method.setAccessible(true);
        return (String) method.invoke(shell);
    }

    private static void setField(CliShell shell, String name, Object value) throws Exception {
        Field field = CliShell.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(shell, value);
    }

    private static Object eventSnapshot(int total, int tools, int failures) throws Exception {
        Constructor<ConsoleEventSink.EventSnapshot> constructor =
                ConsoleEventSink.EventSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                Integer.valueOf(total),
                Integer.valueOf(tools),
                Integer.valueOf(failures),
                Arrays.asList("tool.start terminal", "run.failed session=cli-test"));
    }
}
