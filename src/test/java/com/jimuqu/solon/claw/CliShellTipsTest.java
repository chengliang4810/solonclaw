package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliShell;
import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.LocalTerminalHelp;
import com.jimuqu.solon.claw.cli.TerminalCommandCatalog;
import com.jimuqu.solon.claw.cli.TerminalSecurityPolicyView;
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

        assertThat(commandList()).containsExactly(TerminalCommandCatalog.SLASH_COMMANDS);
        assertThat(LocalTerminalHelp.text()).contains("/reload-mcp [now|always]");
        assertThat(commandList())
                .contains(
                        "/security",
                        "/security audit",
                        "/security policy",
                        "/security paths",
                        "/security credentials",
                        "/security tool-args",
                        "/security mcp",
                        "/security schema",
                        "/security attachments",
                        "/security terminal-paste",
                        "/security media-cache",
                        "/security tool-results",
                        "/security patch",
                        "/security code-execution",
                        "/security subprocess-env",
                        "/security terminal-output",
                        "/security sudo",
                        "/security process");
        assertThat(shouldHandleInline(shell, "/tips")).isTrue();
        assertThat(shouldHandleInline(shell, "/skin mono")).isTrue();
        assertThat(shouldHandleInline(shell, "/security audit")).isTrue();

        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        int exitCode = sendOnce(shell, writer, "/tips");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString()).contains("终端提示").contains("/queue").contains("/steer");

        StringWriter skinBuffer = new StringWriter();
        int skinExitCode = sendOnce(shell, new PrintWriter(skinBuffer), "/skin mono");
        assertThat(skinExitCode).isEqualTo(0);
        assertThat(skinBuffer.toString()).contains("当前皮肤：mono").contains("/skin <名称>");
    }

    @Test
    void shouldRenderSecurityPolicyLocally() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));

        StringWriter buffer = new StringWriter();
        int exitCode = sendOnce(shell, new PrintWriter(buffer), "/security approvals");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString())
                .contains("审批策略摘要")
                .contains("object_storage_exposure_change")
                .contains("remote_credential_file_transfer")
                .contains("sensitive_file_clipboard_export")
                .contains("终端护栏");
        assertThat(TerminalSecurityPolicyView.render(null, "/security audit"))
                .contains("安全审计摘要")
                .contains("/security mcp")
                .contains("/security terminal-paste")
                .contains("/security media-cache")
                .contains("/security tool-results")
                .contains("/security code-execution")
                .contains("/security process");
        assertThat(TerminalSecurityPolicyView.render(null, "/security urls"))
                .contains("URL 安全策略摘要")
                .contains("unsupportedSchemeBlocked");
        assertThat(TerminalSecurityPolicyView.render(null, "/security paths"))
                .contains("路径安全策略摘要")
                .contains("devicePath");
        assertThat(TerminalSecurityPolicyView.render(null, "/security credentials"))
                .contains("凭据文件策略摘要")
                .contains(".env");
        assertThat(TerminalSecurityPolicyView.render(null, "/security tool-args"))
                .contains("工具参数安全策略摘要")
                .contains("patchTarget");
        assertThat(TerminalSecurityPolicyView.render(null, "/security policy"))
                .contains("MCP")
                .contains("oauthReauth")
                .contains("Tool schema")
                .contains("unsupportedKeywordsStripped")
                .contains("附件下载")
                .contains("redirectChecked")
                .contains("代码执行")
                .contains("timeoutKillsProcess")
                .contains("后台进程")
                .contains("managedRequired")
                .contains("工具输出")
                .contains("persistOversize");
        assertThat(TerminalSecurityPolicyView.render(null, "/security mcp"))
                .contains("MCP 安全策略摘要")
                .contains("structuredReauth");
        assertThat(TerminalSecurityPolicyView.render(null, "/security schema"))
                .contains("工具 schema 安全策略摘要")
                .contains("nullableUnionCollapsed");
        assertThat(TerminalSecurityPolicyView.render(null, "/security attachments"))
                .contains("附件下载安全策略摘要")
                .contains("redirectUrl");
        assertThat(TerminalSecurityPolicyView.render(null, "/security terminal-paste"))
                .contains("终端粘贴附件安全策略摘要")
                .contains("credentialBlocked")
                .contains("rawPathHidden");
        assertThat(TerminalSecurityPolicyView.render(null, "/security media-cache"))
                .contains("媒体缓存安全策略摘要")
                .contains("traversalBlocked")
                .contains("hostPathHidden");
        assertThat(TerminalSecurityPolicyView.render(null, "/security tool-results"))
                .contains("工具输出安全策略摘要")
                .contains("oversizedPersisted");
        assertThat(TerminalSecurityPolicyView.render(null, "/security patch"))
                .contains("补丁工具安全策略摘要")
                .contains("atomicValidation")
                .contains("symlinkEscapeBlocked");
        assertThat(TerminalSecurityPolicyView.render(null, "/security code-execution"))
                .contains("代码执行安全策略摘要")
                .contains("pathPolicy")
                .contains("timeoutKillsProcess");
        assertThat(TerminalSecurityPolicyView.render(null, "/security subprocess-env"))
                .contains("子进程环境安全策略摘要")
                .contains("defaultDenyUnknown")
                .contains("_JIMUQU_FORCE_");
        assertThat(TerminalSecurityPolicyView.render(null, "/security terminal-output"))
                .contains("终端输出安全策略摘要")
                .contains("maxInlineChars")
                .contains("sudoHint");
        assertThat(TerminalSecurityPolicyView.render(null, "/security sudo"))
                .contains("sudo 改写安全策略摘要")
                .contains("passwordRedacted")
                .contains("ptyDisabled");
        assertThat(TerminalSecurityPolicyView.render(null, "/security process"))
                .contains("后台进程安全策略摘要")
                .contains("dangerousChecked")
                .contains("managedRequired");
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

    @Test
    void shouldIncludeRecentEventsInShutdownSummary() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));
        setField(shell, "lastEventSnapshot", eventSnapshot(4, 2, 1));
        setField(shell, "lastReply", "ready");
        StringWriter buffer = new StringWriter();

        renderShutdownSummary(shell, new PrintWriter(buffer), "cli-test");

        assertThat(buffer.toString())
                .contains("终端会话结束：session=cli-test")
                .contains("events=4 tools=2 failures=1")
                .contains("copy=ready");
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

    private static void renderShutdownSummary(CliShell shell, PrintWriter writer, String sessionId)
            throws Exception {
        Method method =
                CliShell.class.getDeclaredMethod(
                        "renderShutdownSummary",
                        PrintWriter.class,
                        String.class,
                        com.jimuqu.solon.claw.cli.LocalTerminalTaskRunner.class);
        method.setAccessible(true);
        method.invoke(shell, writer, sessionId, null);
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
