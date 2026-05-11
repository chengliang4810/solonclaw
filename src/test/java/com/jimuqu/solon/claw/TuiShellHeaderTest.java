package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.LocalTerminalTaskRunner;
import com.jimuqu.solon.claw.cli.TerminalCommandCatalog;
import com.jimuqu.solon.claw.cli.TuiShell;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
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
    void shouldRenderBusySnapshotWhenRuntimeIsAvailable() throws Exception {
        TuiShell shell =
                new TuiShell(
                        new BusySnapshotRuntime(),
                        new CliMode(CliMode.Kind.TUI, null, "work"));

        String line = statusLine(shell, "work");

        assertThat(line)
                .contains("session=work")
                .contains("busy=steer")
                .contains("run=run-active")
                .contains("queue=2");
    }

    @Test
    void shouldRenderBusySnapshotFromRealCliRuntime() throws Exception {
        com.jimuqu.solon.claw.support.TestEnvironment env =
                com.jimuqu.solon.claw.support.TestEnvironment.withFakeLlm();
        com.jimuqu.solon.claw.cli.CliRuntime runtime =
                new com.jimuqu.solon.claw.cli.CliRuntime(
                        env.commandService, env.conversationOrchestrator);
        runtime.send("work", "hello", null);
        TuiShell shell = new TuiShell(runtime, new CliMode(CliMode.Kind.TUI, null, "work"));

        String line = statusLine(shell, "work");

        assertThat(line).contains("busy=interrupt").contains("run=-").contains("queue=0");
    }

    @Test
    void shouldExposeShortcutHelpLineInHeader() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));

        String header = renderHeader(shell, "tui");

        assertThat(header).contains("Ctrl-G").contains("Ctrl-S").contains("Ctrl-T").contains("Ctrl-Y");
    }

    @Test
    void shouldExposeRotatingTipInHeader() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));

        String header = renderHeader(shell, "tui");

        assertThat(header)
                .contains("tip: ")
                .contains("/tips")
                .contains("/goal")
                .contains("/cron")
                .contains("/kanban")
                .contains("/security")
                .contains("/recap")
                .contains("/reload-mcp")
                .contains("/acp");
    }

    @Test
    void shouldRenderSecurityPolicyLocally() throws Exception {
        TuiShell shell =
                new TuiShell(
                        null,
                        new CliMode(CliMode.Kind.TUI, null, null),
                        null,
                        new AppConfig());
        java.io.StringWriter buffer = new java.io.StringWriter();
        java.io.PrintWriter writer = new java.io.PrintWriter(buffer);

        int exitCode = send(shell, writer, "/security urls");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString())
                .contains("URL 安全策略摘要")
                .contains("允许 scheme")
                .contains("unsupportedSchemeBlocked");
    }

    @Test
    void shouldUseSharedTerminalCommandCatalogForCompletion() throws Exception {
        Field field = TuiShell.class.getDeclaredField("COMMANDS");
        field.setAccessible(true);
        String[] commands = (String[]) field.get(null);

        assertThat(commands).containsExactly(TerminalCommandCatalog.SLASH_COMMANDS);
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

    @Test
    void shouldRenderFooterStatusSummary() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));
        LocalTerminalTaskRunner runner = new LocalTerminalTaskRunner(new PrintWriter(new java.io.StringWriter()));

        String footer = footerLine(shell, "tui", runner);

        assertThat(footer)
                .contains("footer:")
                .contains("session=tui")
                .contains("tasks=0/0")
                .contains("events=0 tools=0 failures=0")
                .contains("copy=empty");
        runner.close();
    }

    @Test
    void shouldRenderFooterWithRecentRuntimeState() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));
        setField(shell, "lastReply", "可复制回复");
        setField(shell, "lastEventSnapshot", eventSnapshot(4, 2, 1, Arrays.asList("tool.start shell")));
        LocalTerminalTaskRunner runner = new LocalTerminalTaskRunner(new PrintWriter(new java.io.StringWriter()));
        runner.submit(
                        "finished task",
                        new Callable<Integer>() {
                            @Override
                            public Integer call() {
                                return Integer.valueOf(0);
                            }
                        })
                .get(1, TimeUnit.SECONDS);

        String footer = footerLine(shell, "tui", runner);

        assertThat(footer)
                .contains("tasks=0/1")
                .contains("events=4 tools=2 failures=1")
                .contains("copy=ready");
        runner.close();
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

    private String footerLine(TuiShell shell, String sessionId, LocalTerminalTaskRunner runner)
            throws Exception {
        Method method =
                TuiShell.class.getDeclaredMethod(
                        "footerLine", String.class, LocalTerminalTaskRunner.class);
        method.setAccessible(true);
        return (String) method.invoke(shell, sessionId, runner);
    }

    private int send(TuiShell shell, PrintWriter writer, String input) throws Exception {
        Method method =
                TuiShell.class.getDeclaredMethod(
                        "send",
                        LocalTerminalTaskRunner.class,
                        PrintWriter.class,
                        String.class,
                        String.class);
        method.setAccessible(true);
        return ((Integer) method.invoke(shell, null, writer, "tui-test", input)).intValue();
    }

    private void setField(TuiShell shell, String name, Object value) throws Exception {
        Field field = TuiShell.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(shell, value);
    }

    private Object eventSnapshot(int total, int tools, int failures, java.util.List<String> events)
            throws Exception {
        java.lang.reflect.Constructor<ConsoleEventSink.EventSnapshot> constructor =
                ConsoleEventSink.EventSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(total, tools, failures, events);
    }

    private static class BusySnapshotRuntime extends com.jimuqu.solon.claw.cli.CliRuntime {
        private BusySnapshotRuntime() {
            super(null, null);
        }

        @Override
        public GatewayReply send(
                String sessionId,
                String input,
                com.jimuqu.solon.claw.core.service.ConversationEventSink eventSink) {
            return GatewayReply.ok(
                    "busy_policy=steer\n"
                            + "source_running=true\n"
                            + "active_run_id=run-active\n"
                            + "queue_pending=2");
        }
    }
}
