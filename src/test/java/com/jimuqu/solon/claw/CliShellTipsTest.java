package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliShell;
import java.io.PrintWriter;
import java.io.StringWriter;
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
}
